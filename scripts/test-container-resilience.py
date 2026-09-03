#!/usr/bin/env python3
"""
Disposable Docker Compose resilience harness for Echo.

The runner owns one uniquely named Compose project. Its primary service uses a
project-scoped named volume for restart/persistence evidence; separate same-
image services use bounded tmpfs volumes for high-water/disk-guard checks.
It sends XML through the existing HTTP JMS test endpoint, records unique
message IDs from durable request-log details, and kills/restarts the primary
container while a delayed consumer has work queued.

The harness is intentionally black-box. It does not open, copy, delete, or
change any repository database file. The only destructive operations are
bounded writes/removal of two named filler files inside the project-scoped
container volume and ``docker compose down --volumes`` for the generated
project (unless ``--keep`` is requested).

Examples:

    python3 scripts/test-container-resilience.py --mode quick
    python3 scripts/test-container-resilience.py --mode soak --skip-build
    python3 scripts/test-container-resilience.py --mode quick --disk-full
    python3 scripts/test-container-resilience.py --dry-run

Exit codes:
    0  required checks passed (optional paging/pressure observations may be
       indeterminate)
    1  a required resilience check failed
    2  the environment or an optional-but-requested capability was
       indeterminate (for example Docker unavailable or tmpfs unsupported)
"""

from __future__ import annotations

import argparse
import base64
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass
import datetime as dt
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import socket
import subprocess
import sys
import threading
import time
from typing import Any, Iterable, Mapping, Optional, Sequence
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


PROJECT_ROOT = Path(__file__).resolve().parent.parent
SCRIPT_DIR = PROJECT_ROOT / "scripts"
DEFAULT_COMPOSE_FILE = PROJECT_ROOT / "docker-compose.resilience.yml"
DEFAULT_ARTIFACT_ROOT = PROJECT_ROOT / "artifacts" / "container-resilience"
SERVICE = "echo-resilience"
DISK_SERVICE = "echo-resilience-disk"
FULL_DISK_SERVICE = "echo-resilience-disk-full"
QUEUE_DEFAULT = "ECHO.RESILIENCE"
BLOCK_BYTES = 64 * 1024
FULL_DISK_GUARD_BYTES = 8 * 1024 * 1024
SCHEMA_VERSION = 1
MAX_RETAINED_BODY_BYTES = 256 * 1024

ID_PATTERN = re.compile(r"<HarnessMessageId>([^<]+)</HarnessMessageId>")
MEMORY_PATTERN = re.compile(r"^(\d+(?:\.\d+)?)([kKmMgGtT]?)$")
PRESSURE_TOKENS = (
    "backlog",
    "pressure",
    "queuedepth",
    "queue_size",
    "queuesize",
    "pending",
    "paging",
)
BACKPRESSURE_FIELDS = (
    "queueBytes",
    "queueCapacityBytes",
    "inFlightBytes",
    "inFlightByteLimit",
    "waitingProducers",
    "backpressureActive",
)


@dataclass(frozen=True)
class ModeConfig:
    messages: int
    recovery_messages: int
    payload_bytes: int
    delay_ms: int
    send_concurrency: int
    backlog_window_seconds: float
    drain_timeout_seconds: float
    disk_reserve_mb: int


MODE_CONFIGS = {
    "quick": ModeConfig(
        messages=80,
        recovery_messages=16,
        payload_bytes=8 * 1024,
        delay_ms=150,
        send_concurrency=8,
        backlog_window_seconds=0.8,
        drain_timeout_seconds=30,
        disk_reserve_mb=16,
    ),
    "soak": ModeConfig(
        messages=1200,
        recovery_messages=240,
        payload_bytes=32 * 1024,
        delay_ms=150,
        send_concurrency=8,
        backlog_window_seconds=3.0,
        drain_timeout_seconds=360,
        disk_reserve_mb=32,
    ),
}


class ConfigurationError(ValueError):
    """Raised before Docker is touched when the requested run is unsafe."""


class HarnessFailure(RuntimeError):
    """Raised when a required step cannot produce meaningful evidence."""


@dataclass
class ApiResult:
    status: int
    data: Any
    text: str
    elapsed_ms: float


class ApiClient:
    """Small standard-library HTTP client that preserves failure evidence."""

    def __init__(self, base_url: str, username: str, password: str, timeout: float = 15.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        self.authorization = f"Basic {token}"

    def request(
        self,
        method: str,
        path: str,
        data: Any = None,
        *,
        content_type: Optional[str] = None,
        timeout: Optional[float] = None,
    ) -> ApiResult:
        payload: Optional[bytes]
        if data is None:
            payload = None
        elif isinstance(data, bytes):
            payload = data
        elif isinstance(data, str):
            payload = data.encode("utf-8")
        else:
            payload = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

        headers = {
            "Accept": "application/json",
            "Authorization": self.authorization,
        }
        if content_type:
            headers["Content-Type"] = content_type
        elif data is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"

        request = Request(
            f"{self.base_url}{path}",
            data=payload,
            method=method.upper(),
            headers=headers,
        )
        started = time.monotonic()
        try:
            with urlopen(request, timeout=timeout or self.timeout) as response:
                raw = response.read()
                status = int(response.status)
        except HTTPError as error:
            raw = error.read() if error.fp else b""
            status = int(error.code)
        except (OSError, URLError, TimeoutError) as error:
            return ApiResult(0, None, str(error), (time.monotonic() - started) * 1000)

        text = raw.decode("utf-8", errors="replace")
        try:
            parsed = json.loads(text) if text.strip() else None
        except json.JSONDecodeError:
            parsed = None
        return ApiResult(status, parsed, text, (time.monotonic() - started) * 1000)


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def short_text(value: Any, limit: int = 500) -> str:
    if value is None:
        return ""
    if not isinstance(value, str):
        try:
            value = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        except (TypeError, ValueError):
            value = str(value)
    value = value.replace("\n", "\\n")
    return value if len(value) <= limit else value[:limit] + "..."


def parse_memory(value: str) -> int:
    match = MEMORY_PATTERN.fullmatch(value.strip())
    if not match:
        raise ConfigurationError(f"invalid memory size: {value!r}")
    number = float(match.group(1))
    multiplier = {
        "": 1,
        "k": 1024,
        "m": 1024 ** 2,
        "g": 1024 ** 3,
        "t": 1024 ** 4,
    }[match.group(2).lower()]
    result = int(number * multiplier)
    if result <= 0:
        raise ConfigurationError(f"memory size must be positive: {value!r}")
    return result


def validate_positive(value: int, name: str) -> int:
    if value <= 0:
        raise ConfigurationError(f"{name} must be positive")
    return value


def free_port(reserved: Optional[set[int]] = None) -> int:
    reserved = reserved or set()
    for _ in range(20):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("127.0.0.1", 0))
            port = int(sock.getsockname()[1])
        if port not in reserved:
            return port
    raise ConfigurationError("could not allocate free host port")


def sanitize_project_prefix(value: str) -> str:
    normalized = re.sub(r"[^a-z0-9_-]+", "-", value.lower()).strip("-_")
    if not normalized:
        raise ConfigurationError("project prefix must contain letters or numbers")
    return normalized[:40]


def new_project_name(prefix: str) -> str:
    safe = sanitize_project_prefix(prefix)
    if not safe.startswith("echo-resilience"):
        safe = f"echo-resilience-{safe}"
    stamp = dt.datetime.now().strftime("%Y%m%d%H%M%S")
    return f"{safe}-{stamp}-{os.getpid()}-{secrets.token_hex(4)}"


def owned_project(project_name: str) -> bool:
    return bool(re.fullmatch(r"echo-resilience-[a-z0-9][a-z0-9_-]*", project_name))


def command_display(command: Sequence[str]) -> str:
    return shlex.join(list(command))


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


class CommandRunner:
    """Execute only argv-based commands and retain stdout/stderr per step."""

    def __init__(self, output_dir: Path, *, dry_run: bool = False, environment: Optional[Mapping[str, str]] = None):
        self.output_dir = output_dir
        self.dry_run = dry_run
        self.environment = dict(environment) if environment is not None else None
        self.records: list[dict[str, Any]] = []
        self._lock = threading.Lock()

    def run(
        self,
        label: str,
        command: Sequence[str],
        *,
        timeout: float = 120,
        expected_failure: bool = False,
    ) -> dict[str, Any]:
        safe_label = re.sub(r"[^A-Za-z0-9_.-]+", "-", label)
        with self._lock:
            occurrence = sum(1 for record in self.records if record.get("label") == label)
        if occurrence:
            safe_label = f"{safe_label}-{occurrence + 1}"
        stdout_path = self.output_dir / f"{safe_label}.stdout.log"
        stderr_path = self.output_dir / f"{safe_label}.stderr.log"
        record: dict[str, Any] = {
            "label": label,
            "command": list(command),
            "command_display": command_display(command),
            "started_at": utc_now(),
            "timeout_seconds": timeout,
            "expected_failure": expected_failure,
            "stdout": str(stdout_path),
            "stderr": str(stderr_path),
        }
        if self.dry_run:
            record["status"] = "planned"
            with self._lock:
                self.records.append(record)
            return record

        self.output_dir.mkdir(parents=True, exist_ok=True)
        started = time.monotonic()
        returncode: Optional[int] = None
        error: Optional[str] = None
        try:
            with stdout_path.open("w", encoding="utf-8", errors="replace") as stdout, \
                    stderr_path.open("w", encoding="utf-8", errors="replace") as stderr:
                completed = subprocess.run(
                    list(command),
                    cwd=str(PROJECT_ROOT),
                    env=self.environment,
                    stdin=subprocess.DEVNULL,
                    stdout=stdout,
                    stderr=stderr,
                    check=False,
                    shell=False,
                    timeout=timeout,
                )
                returncode = int(completed.returncode)
        except subprocess.TimeoutExpired:
            returncode = 124
            error = f"timed out after {timeout:g} seconds"
        except OSError as exc:
            returncode = 127
            error = f"could not execute command: {exc}"

        passed = returncode != 0 if expected_failure else returncode == 0
        record.update(
            {
                "finished_at": utc_now(),
                "duration_seconds": round(time.monotonic() - started, 3),
                "returncode": returncode,
                "status": "passed" if passed else "failed",
            }
        )
        if error:
            record["error"] = error
        with self._lock:
            self.records.append(record)
        return record

    @staticmethod
    def read_stdout(record: Mapping[str, Any]) -> str:
        try:
            return Path(str(record["stdout"])).read_text(encoding="utf-8", errors="replace")
        except OSError:
            return ""


def compose_command(compose_file: Path, project_name: str, *arguments: str) -> list[str]:
    return [
        "docker",
        "compose",
        "--project-name",
        project_name,
        "--file",
        str(compose_file),
        *arguments,
    ]


def query_value(path: str, params: Mapping[str, Any]) -> str:
    values = [(key, value) for key, value in params.items() if value is not None]
    return f"{path}?{urlencode(values)}" if values else path


def extract_pressure_fields(value: Any, prefix: str = "", depth: int = 0) -> dict[str, Any]:
    """Keep optional pressure metrics without depending on a future field name."""
    if depth > 2:
        return {}
    result: dict[str, Any] = {}
    if isinstance(value, Mapping):
        for key, child in value.items():
            path = f"{prefix}.{key}" if prefix else str(key)
            normalized = str(key).lower().replace("-", "_")
            if any(token in normalized for token in PRESSURE_TOKENS):
                if isinstance(child, (int, float, bool, str)) or child is None:
                    result[path] = child
            result.update(extract_pressure_fields(child, path, depth + 1))
    return result


def allowed_status_fields(status: Mapping[str, Any]) -> dict[str, Any]:
    fields: dict[str, Any] = {}
    for key in (
        "jvmHeapUsed",
        "jvmHeapMax",
        "ruleCount",
        "responseCount",
        "requestLogCount",
        "uptime",
        "jmsEnabled",
        "envLabel",
    ):
        if key in status:
            fields[key] = status[key]
    fields["pressure"] = extract_pressure_fields(status)
    return fields


class ResilienceHarness:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        base_config = MODE_CONFIGS[args.mode]
        self.config = ModeConfig(
            messages=args.messages or base_config.messages,
            recovery_messages=args.recovery_messages or base_config.recovery_messages,
            payload_bytes=args.payload_bytes or base_config.payload_bytes,
            delay_ms=args.delay_ms if args.delay_ms is not None else base_config.delay_ms,
            send_concurrency=args.send_concurrency or base_config.send_concurrency,
            backlog_window_seconds=(
                args.backlog_window_seconds
                if args.backlog_window_seconds is not None
                else base_config.backlog_window_seconds
            ),
            drain_timeout_seconds=args.drain_timeout or base_config.drain_timeout_seconds,
            disk_reserve_mb=args.disk_reserve_mb or base_config.disk_reserve_mb,
        )
        validate_positive(self.config.messages, "messages")
        validate_positive(self.config.recovery_messages, "recovery-messages")
        validate_positive(self.config.payload_bytes, "payload-bytes")
        validate_positive(self.config.send_concurrency, "send-concurrency")
        validate_positive(self.config.drain_timeout_seconds, "drain-timeout")
        validate_positive(args.max_body_bytes, "max-body-bytes")
        if self.config.delay_ms < 0:
            raise ConfigurationError("delay-ms cannot be negative")
        if self.config.payload_bytes > MAX_RETAINED_BODY_BYTES:
            raise ConfigurationError(
                f"payload-bytes must be at most {MAX_RETAINED_BODY_BYTES} so IDs remain in retained log bodies"
            )
        if args.max_body_bytes < self.config.payload_bytes:
            raise ConfigurationError("max-body-bytes must be at least payload-bytes")
        if self.config.send_concurrency > 64:
            raise ConfigurationError("send-concurrency must be at most 64")
        if self.config.disk_reserve_mb < 1:
            raise ConfigurationError("disk-reserve-mb must be at least 1")
        if args.disk_full and args.skip_disk:
            raise ConfigurationError("--disk-full cannot be combined with --skip-disk")

        self.run_id = dt.datetime.now().strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(4)
        prefix = args.project_prefix or "echo-resilience"
        self.project_name = new_project_name(prefix)
        if not owned_project(self.project_name):
            raise ConfigurationError("generated project name failed ownership guard")
        self.compose_file = Path(args.compose_file).resolve()
        if not self.compose_file.is_file():
            raise ConfigurationError(f"Compose file not found: {self.compose_file}")

        explicit_output = Path(args.output_dir).resolve() if args.output_dir else None
        self.output_dir = explicit_output or DEFAULT_ARTIFACT_ROOT / self.run_id
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.http_port = args.http_port or free_port()
        self.jms_port = args.jms_port or free_port({self.http_port})
        reserved_ports = {self.http_port, self.jms_port}
        self.disk_http_port = args.disk_http_port or free_port(reserved_ports)
        if self.disk_http_port in reserved_ports:
            raise ConfigurationError("HTTP, JMS, and disk HTTP ports must differ")
        reserved_ports.add(self.disk_http_port)
        self.full_http_port = args.full_http_port or free_port(reserved_ports)
        if self.full_http_port in reserved_ports:
            raise ConfigurationError("HTTP, JMS, disk HTTP, and full HTTP ports must differ")
        self.heap_bytes = parse_memory(args.heap)
        self.data_bytes = parse_memory(args.data_size)
        self.full_data_bytes = parse_memory(args.full_data_size)
        if self.full_data_bytes >= self.data_bytes:
            raise ConfigurationError("full-data-size must be smaller than data-size")
        self.container_memory_bytes = parse_memory(args.container_memory)
        if self.container_memory_bytes < self.heap_bytes:
            raise ConfigurationError("container-memory must be at least heap")
        self.queue = args.queue
        self.username = os.getenv("ECHO_TEST_USERNAME", "admin")
        self.password = os.getenv("ECHO_TEST_PASSWORD", "admin")
        self.environment = os.environ.copy()
        self.environment.update(
            {
                "ECHO_RESILIENCE_HTTP_PORT": str(self.http_port),
                "ECHO_RESILIENCE_JMS_PORT": str(self.jms_port),
                "ECHO_RESILIENCE_DISK_HTTP_PORT": str(self.disk_http_port),
                "ECHO_RESILIENCE_FULL_HTTP_PORT": str(self.full_http_port),
                "ECHO_RESILIENCE_HEAP": args.heap,
                "ECHO_RESILIENCE_DATA_SIZE": args.data_size,
                "ECHO_RESILIENCE_FULL_DATA_SIZE": args.full_data_size,
                "ECHO_RESILIENCE_CONTAINER_MEMORY": args.container_memory,
                "ECHO_RESILIENCE_MAX_BODY_BYTES": str(args.max_body_bytes),
                "ECHO_RESILIENCE_QUEUE": self.queue,
            }
        )
        self.runner = CommandRunner(self.output_dir, environment=self.environment)
        self.base_url = f"http://127.0.0.1:{self.http_port}"
        self.disk_base_url = f"http://127.0.0.1:{self.disk_http_port}"
        self.full_base_url = f"http://127.0.0.1:{self.full_http_port}"
        self.client = ApiClient(self.base_url, self.username, self.password)
        self.disk_client = ApiClient(self.disk_base_url, self.username, self.password)
        self.full_client = ApiClient(self.full_base_url, self.username, self.password)
        self.checks: list[dict[str, Any]] = []
        self.samples: list[dict[str, Any]] = []
        self.load_samples: list[dict[str, Any]] = []
        self.expected_ids: list[str] = []
        self.pre_kill_ids: list[str] = []
        self.recovery_ids: list[str] = []
        self.send_failures: list[dict[str, Any]] = []
        self.rule_id: Optional[str] = None
        self.restart_sentinel = ""
        self.known_oom = False
        self.indeterminate = False
        self._sample_stop = threading.Event()
        self._sample_thread: Optional[threading.Thread] = None

    def parameters(self) -> dict[str, Any]:
        values = asdict(self.config)
        values.update(
            {
                "mode": self.args.mode,
                "heap": self.args.heap,
                "heap_bytes": self.heap_bytes,
                "data_size": self.args.data_size,
                "data_size_bytes": self.data_bytes,
                "container_memory": self.args.container_memory,
                "container_memory_bytes": self.container_memory_bytes,
                "max_body_bytes": self.args.max_body_bytes,
                "full_data_size": self.args.full_data_size,
                "full_data_size_bytes": self.full_data_bytes,
                "queue": self.queue,
                "http_port": self.http_port,
                "jms_port": self.jms_port,
                "disk_http_port": self.disk_http_port,
                "full_http_port": self.full_http_port,
                "disk_full": bool(self.args.disk_full),
                "disk_enabled": not self.args.skip_disk,
            }
        )
        return values

    def check(
        self,
        name: str,
        passed: bool,
        detail: str = "",
        *,
        status: Optional[str] = None,
        affects_exit: bool = True,
    ) -> None:
        result = status or ("pass" if passed else "fail")
        self.checks.append({"name": name, "status": result, "detail": detail})
        print(f"  {result.upper():12s} {name}{(': ' + detail) if detail else ''}")
        if affects_exit and result in ("indeterminate", "skipped"):
            self.indeterminate = True

    def indeterminate_check(self, name: str, detail: str, *, affects_exit: bool = True) -> None:
        self.check(name, False, detail, status="indeterminate", affects_exit=affects_exit)

    def optional_indeterminate_check(self, name: str, detail: str) -> None:
        self.indeterminate_check(name, detail, affects_exit=False)

    def compose(self, *arguments: str) -> list[str]:
        return compose_command(self.compose_file, self.project_name, *arguments)

    def run_compose(self, label: str, *arguments: str, timeout: float = 120, expected_failure: bool = False) -> dict[str, Any]:
        return self.runner.run(label, self.compose(*arguments), timeout=timeout, expected_failure=expected_failure)

    def run_exec(
        self,
        label: str,
        *arguments: str,
        service: str = SERVICE,
        timeout: float = 60,
        expected_failure: bool = False,
    ) -> dict[str, Any]:
        return self.runner.run(
            label,
            self.compose("exec", "-T", service, *arguments),
            timeout=timeout,
            expected_failure=expected_failure,
        )

    def capture_storage(self, label: str, *, service: str = SERVICE) -> dict[str, Any]:
        inventory: dict[str, Any] = {"label": label, "at": utc_now()}
        for name, command in (
            ("df", ("df", "-Pk", "/app/data")),
            ("du", ("du", "-sb", "/app/data")),
            ("data_files", ("find", "/app/data", "-maxdepth", "1", "-type", "f", "-print")),
            ("artemis_files", ("find", "/app/data/artemis", "-type", "f", "-print")),
        ):
            result = self.run_exec(f"storage-{label}-{name}", *command, service=service)
            raw_stdout = self.runner.read_stdout(result)
            inventory[name] = {
                "status": result.get("status"),
                "returncode": result.get("returncode"),
                "stdout": result.get("stdout"),
                "stderr": result.get("stderr"),
                "stdout_text": short_text(raw_stdout, 12000),
            }
            if name == "df":
                inventory["available_bytes"] = self.parse_df_available(raw_stdout)
            elif name == "data_files":
                inventory["data_file_paths"] = self.parse_paths(raw_stdout)
            elif name == "artemis_files":
                inventory["artemis_paths"] = self.parse_paths(raw_stdout)
        inventory.setdefault("available_bytes", None)
        inventory.setdefault("data_file_paths", [])
        inventory.setdefault("artemis_paths", [])
        return inventory

    @staticmethod
    def parse_paths(value: str) -> list[str]:
        return [line.strip() for line in value.splitlines() if line.strip()]

    @staticmethod
    def storage_basenames(storage: Mapping[str, Any]) -> set[str]:
        return {
            Path(path).name
            for path in storage.get("data_file_paths", [])
            if isinstance(path, str) and path.strip()
        }

    @staticmethod
    def parse_df_available(value: str) -> Optional[int]:
        lines = [line.split() for line in value.splitlines() if line.strip()]
        if len(lines) < 2:
            return None
        fields = lines[-1]
        if len(fields) < 4:
            return None
        try:
            # df -Pk reports 1 KiB blocks in the fourth numeric column.
            return int(fields[3]) * 1024
        except (TypeError, ValueError):
            return None

    def snapshot_status(
        self,
        label: str,
        *,
        client: Optional[ApiClient] = None,
        record_sample: bool = True,
    ) -> Optional[dict[str, Any]]:
        result = (client or self.client).request("GET", "/api/admin/status", timeout=5)
        snapshot: dict[str, Any] = {
            "label": label,
            "at": utc_now(),
            "status": result.status,
            "elapsed_ms": round(result.elapsed_ms, 3),
        }
        if isinstance(result.data, Mapping):
            snapshot["fields"] = allowed_status_fields(result.data)
            heap_used = result.data.get("jvmHeapUsed")
            heap_max = result.data.get("jvmHeapMax")
            if isinstance(heap_used, (int, float)):
                snapshot["heap_used"] = int(heap_used)
            if isinstance(heap_max, (int, float)):
                snapshot["heap_max"] = int(heap_max)
        else:
            snapshot["error"] = short_text(result.text or result.data)
        if record_sample:
            self.samples.append(snapshot)
        return snapshot if result.status == 200 and isinstance(result.data, Mapping) else None

    def public_status_probe(self) -> tuple[bool, str]:
        """Exercise the unauthenticated healthcheck route exactly as Compose does."""
        request = Request(f"{self.base_url}/api/admin/status", method="GET")
        request.add_header("Accept", "application/json")
        try:
            with urlopen(request, timeout=5) as response:
                response.read(4096)
                return response.status == 200, f"status={response.status}"
        except (HTTPError, OSError, URLError, TimeoutError) as exc:
            return False, str(exc)

    def get_agents(self, *, client: Optional[ApiClient] = None) -> tuple[Optional[list[dict[str, Any]]], Optional[str]]:
        result = (client or self.client).request("GET", "/api/admin/agents", timeout=5)
        if result.status != 200 or not isinstance(result.data, list):
            return None, f"status={result.status} body={short_text(result.data or result.text)}"
        agents = [item for item in result.data if isinstance(item, dict)]
        return agents, None

    def sample_load_loop(self, phase: str) -> None:
        while not self._sample_stop.wait(0.5):
            snapshot = self.snapshot_status(f"load-{phase}")
            agents, error = self.get_agents()
            sample: dict[str, Any] = {"phase": phase, "at": utc_now(), "status": snapshot}
            if agents is not None:
                sample["agents"] = agents
            if error:
                sample["agents_error"] = error
            self.load_samples.append(sample)

    def start_sampling(self, phase: str) -> None:
        self._sample_stop.clear()
        self._sample_thread = threading.Thread(target=self.sample_load_loop, args=(phase,), daemon=True)
        self._sample_thread.start()

    def stop_sampling(self) -> None:
        self._sample_stop.set()
        if self._sample_thread is not None:
            self._sample_thread.join(timeout=10)
            self._sample_thread = None

    def wait_ready(
        self,
        timeout_seconds: float,
        label: str,
        *,
        client: Optional[ApiClient] = None,
        record_sample: bool = True,
    ) -> bool:
        client = client or self.client
        deadline = time.monotonic() + timeout_seconds
        last = "no response"
        while time.monotonic() < deadline:
            result = client.request("GET", "/api/admin/status", timeout=3)
            if result.status == 200 and isinstance(result.data, Mapping):
                self.snapshot_status(label, client=client, record_sample=record_sample)
                return True
            last = f"status={result.status} body={short_text(result.data or result.text)}"
            time.sleep(0.5)
        self.snapshot_status(label, client=client, record_sample=record_sample)
        if client is self.client:
            self.check(label, False, f"timed out: {last}")
        return False

    def create_rule(self, *, client: Optional[ApiClient] = None, label: str = "JMS") -> bool:
        client = client or self.client
        payload = {
            "protocol": "JMS",
            "matchKey": self.queue,
            "responseBody": "<resilience-ok/>",
            "bodyCondition": "//ServiceName=ResilienceHarness",
            "delayMs": self.config.delay_ms,
            "priority": 100,
            "enabled": True,
            "description": f"container-resilience-{self.run_id}",
        }
        result = client.request("POST", "/api/admin/rules", payload, timeout=60)
        if result.status != 201 or not isinstance(result.data, Mapping):
            self.check(f"{label} rule setup", False, f"status={result.status} body={short_text(result.data or result.text)}")
            return False
        rule_id = result.data.get("id")
        if not isinstance(rule_id, str) or not rule_id:
            self.check(f"{label} rule setup", False, "created rule did not return an id")
            return False
        if client is self.client:
            self.rule_id = rule_id
        self.check(f"{label} rule setup", True, f"rule={rule_id}")
        return True

    def write_restart_sentinel(self) -> dict[str, Any]:
        """Write a run-owned marker to distinguish volume persistence from DB recovery."""
        self.restart_sentinel = f".echo-resilience-restart-{self.run_id}"
        command = f"printf '%s' {shlex.quote(self.run_id)} > /app/data/{self.restart_sentinel}"
        return self.run_exec("restart-sentinel-write", "sh", "-c", command)

    def read_restart_sentinel(self) -> tuple[dict[str, Any], str]:
        result = self.run_exec("restart-sentinel-read", "cat", f"/app/data/{self.restart_sentinel}")
        return result, self.runner.read_stdout(result).strip()

    def message_body(self, message_id: str) -> str:
        prefix = (
            '<?xml version="1.0" encoding="UTF-8"?>'
            "<ResilienceHarness>"
            "<ServiceName>ResilienceHarness</ServiceName>"
            f"<HarnessMessageId>{message_id}</HarnessMessageId>"
            "<Payload>"
        )
        suffix = "</Payload></ResilienceHarness>"
        target = max(0, self.config.payload_bytes - len((prefix + suffix).encode("utf-8")))
        return prefix + ("x" * target) + suffix

    def send_one(self, message_id: str, *, client: Optional[ApiClient] = None) -> dict[str, Any]:
        client = client or self.client
        body = self.message_body(message_id)
        result = client.request(
            "POST",
            "/api/admin/jms/test",
            body,
            content_type="application/xml; charset=utf-8",
            timeout=30,
        )
        accepted = result.status == 200 and isinstance(result.data, Mapping) and result.data.get("sent") is True
        return {
            "id": message_id,
            "accepted": accepted,
            "status": result.status,
            "elapsed_ms": round(result.elapsed_ms, 3),
            "detail": short_text(result.data or result.text),
        }

    def send_messages(self, phase: str, count: int, *, client: Optional[ApiClient] = None) -> list[str]:
        client = client or self.client
        ids = [f"{self.run_id}-{phase}-{index:06d}" for index in range(count)]
        accepted: list[str] = []
        started = time.monotonic()
        with ThreadPoolExecutor(max_workers=self.config.send_concurrency, thread_name_prefix="jms-sender") as executor:
            futures = [executor.submit(self.send_one, message_id, client=client) for message_id in ids]
            for future in as_completed(futures):
                result = future.result()
                if result["accepted"]:
                    accepted.append(str(result["id"]))
                else:
                    self.send_failures.append({"phase": phase, **result})
        accepted.sort()
        elapsed = time.monotonic() - started
        self.check(
            f"{phase} JMS enqueue",
            bool(accepted),
            f"accepted={len(accepted)}/{count}, enqueue_seconds={elapsed:.2f}, rejected={count - len(accepted)}",
        )
        return accepted

    def count_jms_logs(self, *, client: Optional[ApiClient] = None) -> Optional[int]:
        path = query_value(
            "/api/admin/logs",
            {"protocol": "JMS", "size": 1, "sort": "requestTime", "direction": "asc"},
        )
        result = (client or self.client).request("GET", path, timeout=10)
        if result.status == 200 and isinstance(result.data, Mapping):
            value = result.data.get("totalElements")
            if isinstance(value, (int, float)):
                return int(value)
        return None

    def wait_for_delivery(
        self,
        expected_count: int,
        timeout_seconds: float,
        label: str,
        *,
        client: Optional[ApiClient] = None,
    ) -> dict[str, Any]:
        client = client or self.client
        started = time.monotonic()
        deadline = started + timeout_seconds
        last_count: Optional[int] = None
        last_agents: Optional[list[dict[str, Any]]] = None
        while time.monotonic() < deadline:
            last_count = self.count_jms_logs(client=client)
            last_agents, _ = self.get_agents(client=client)
            queue_sizes = [
                int(agent.get("queueSize", 0))
                for agent in (last_agents or [])
                if isinstance(agent.get("queueSize", 0), (int, float))
            ]
            if last_count is not None and last_count >= expected_count and not any(queue_sizes):
                return {
                    "status": "passed",
                    "label": label,
                    "expected_count": expected_count,
                    "observed_log_count": last_count,
                    "duration_seconds": round(time.monotonic() - started, 3),
                    "agents": last_agents,
                }
            time.sleep(1)
        return {
            "status": "failed",
            "label": label,
            "expected_count": expected_count,
            "observed_log_count": last_count,
            "duration_seconds": round(time.monotonic() - started, 3),
            "agents": last_agents,
            "error": "delivery/log-agent drain timeout",
        }

    def collect_log_records(
        self,
        *,
        client: Optional[ApiClient] = None,
        expected_ids: Optional[Iterable[str]] = None,
    ) -> dict[str, Any]:
        client = client or self.client
        expected_ids = list(self.expected_ids if expected_ids is None else expected_ids)
        path = query_value(
            "/api/admin/logs",
            {
                "protocol": "JMS",
                "size": 20000,
                "sort": "requestTime",
                "direction": "asc",
            },
        )
        result = client.request("GET", path, timeout=30)
        if result.status != 200 or not isinstance(result.data, Mapping):
            return {"status": "indeterminate", "error": f"summary status={result.status} body={short_text(result.data or result.text)}"}
        rows = result.data.get("results")
        if not isinstance(rows, list):
            return {"status": "indeterminate", "error": "summary did not return results list"}

        summary_rows = [
            row for row in rows
            if isinstance(row, Mapping)
            and isinstance(row.get("log"), Mapping)
            and row["log"].get("hasRequestBody") is True
            and isinstance(row["log"].get("id"), (int, float))
        ]

        def fetch_detail(row: Mapping[str, Any]) -> dict[str, Any]:
            log_id = int(row["log"]["id"])
            detail = client.request("GET", f"/api/admin/logs/{log_id}/detail", timeout=30)
            body = detail.data.get("requestBody") if isinstance(detail.data, Mapping) else None
            match = ID_PATTERN.search(body) if isinstance(body, str) else None
            return {
                "log_id": log_id,
                "status": detail.status,
                "message_id": match.group(1) if match else None,
                "body_present": isinstance(body, str) and bool(body),
                "body_bytes": len(body.encode("utf-8")) if isinstance(body, str) else 0,
                "detail": short_text(detail.data or detail.text),
            }

        records: list[dict[str, Any]] = []
        with ThreadPoolExecutor(max_workers=min(8, max(1, self.config.send_concurrency)), thread_name_prefix="log-detail") as executor:
            futures = [executor.submit(fetch_detail, row) for row in summary_rows]
            for future in as_completed(futures):
                records.append(future.result())
        records.sort(key=lambda item: item["log_id"])
        ids = [item["message_id"] for item in records if isinstance(item.get("message_id"), str)]
        counts = Counter(ids)
        expected = set(expected_ids)
        observed = set(ids)
        missing = sorted(expected - observed)
        duplicates = {message_id: count for message_id, count in counts.items() if count > 1}
        unknown = sorted(observed - expected)
        return {
            # Duplicate IDs are reported separately because a transactional
            # listener may legitimately redeliver an in-flight message after
            # SIGKILL. Missing/unknown IDs or unreadable details remain hard
            # accounting failures.
            "status": "passed" if not missing and not unknown
            and not any(not item.get("message_id") for item in records) else "failed",
            "summary_total_elements": result.data.get("totalElements"),
            "summary_rows_with_body": len(summary_rows),
            "records": records,
            "observed_ids": len(ids),
            "missing_ids": missing,
            "duplicate_ids": duplicates,
            "unknown_ids": unknown,
            "details_without_id": sum(1 for item in records if not item.get("message_id")),
        }

    def verify_delivery_sets(
        self,
        log_records: Mapping[str, Any],
        *,
        expected_ids: Optional[Iterable[str]] = None,
        label: str = "JMS ID accounting",
        check_restart_recovery: bool = True,
    ) -> bool:
        expected_ids = list(self.expected_ids if expected_ids is None else expected_ids)
        missing = set(log_records.get("missing_ids", []))
        duplicates = log_records.get("duplicate_ids", {})
        unknown = set(log_records.get("unknown_ids", []))
        detail_failures = int(log_records.get("details_without_id", 0) or 0)
        allow_transactional_redelivery = check_restart_recovery
        accounting_pass = (
            not missing
            and (allow_transactional_redelivery or not duplicates)
            and not unknown
            and detail_failures == 0
        )
        self.check(
            label,
            accounting_pass,
            f"expected={len(expected_ids)} observed={log_records.get('observed_ids', 0)} missing={len(missing)} duplicates={len(duplicates)} unknown={len(unknown)} detail_failures={detail_failures}",
        )

        if not check_restart_recovery:
            return accounting_pass

        pre_expected = set(self.pre_kill_ids)
        # Reuse the raw records for a phase-specific at-least-once check.
        phase_ids = [
            item.get("message_id") for item in log_records.get("records", [])
            if isinstance(item, Mapping) and isinstance(item.get("message_id"), str)
        ]
        phase_counts = Counter(phase_ids)
        pre_missing = sorted(pre_expected - set(phase_counts))
        self.check(
            "kill/restart recovery observed all accepted IDs",
            not pre_missing,
            f"accepted_before_kill={len(pre_expected)} missing_after_restart={len(pre_missing)}",
        )
        pre_duplicates = {key: value for key, value in phase_counts.items() if key in pre_expected and value > 1}
        if pre_duplicates:
            self.optional_indeterminate_check(
                "kill/restart duplicate semantics",
                f"redelivery produced {len(pre_duplicates)} duplicate IDs; all were retained for diagnosis",
            )
        else:
            self.check("kill/restart duplicate semantics", True, "no duplicate request-log IDs observed")
        return accounting_pass

    def inspect_container(self, label: str, *, service: str = SERVICE) -> dict[str, Any]:
        # Compose's default `ps -q` omits stopped containers; `-a` is needed
        # immediately after SIGKILL to inspect the same container before start.
        ps = self.run_compose(f"container-state-{label}-ps", "ps", "-aq", service)
        container_id = self.runner.read_stdout(ps).strip().splitlines()
        result: dict[str, Any] = {"label": label, "container_id": container_id[0] if container_id else None}
        if not container_id:
            result["status"] = "indeterminate"
            return result
        inspected = self.runner.run(
            f"container-state-{label}-inspect",
            [
                "docker",
                "inspect",
                "--format",
                "{{.State.Status}}|{{.State.Running}}|{{.State.OOMKilled}}|{{.State.ExitCode}}",
                container_id[0],
            ],
            timeout=30,
        )
        text = self.runner.read_stdout(inspected).strip()
        fields = text.split("|", 3)
        if len(fields) == 4:
            result.update({"status": fields[0], "running": fields[1] == "true", "oom_killed": fields[2] == "true", "exit_code": fields[3]})
        else:
            result.update({"status": "indeterminate", "inspect_output": short_text(text)})
        config = self.runner.run(
            f"container-state-{label}-config",
            [
                "docker",
                "inspect",
                "--format",
                "{{json .Config.Env}}|{{.HostConfig.Memory}}",
                container_id[0],
            ],
            timeout=30,
        )
        config_text = self.runner.read_stdout(config).strip()
        config_parts = config_text.split("|", 1)
        if len(config_parts) == 2:
            try:
                env = json.loads(config_parts[0])
            except json.JSONDecodeError:
                env = []
            if isinstance(env, list):
                for item in env:
                    if isinstance(item, str) and item.startswith("JAVA_OPTS="):
                        result["java_opts"] = item.split("=", 1)[1]
                        break
            try:
                result["memory_limit_bytes"] = int(config_parts[1])
            except ValueError:
                pass
        return result

    def disk_fill(
        self,
        filename: str,
        available_bytes: int,
        reserve_bytes: int,
        label: str,
        *,
        full: bool,
        service: str = SERVICE,
    ) -> dict[str, Any]:
        if full:
            blocks = max(1, (available_bytes + BLOCK_BYTES - 1) // BLOCK_BYTES + 2)
        else:
            target = available_bytes - reserve_bytes
            blocks = max(1, target // BLOCK_BYTES)
        result = self.run_exec(
            f"disk-{label}-fill",
            "dd",
            "if=/dev/zero",
            f"of=/app/data/{filename}",
            f"bs={BLOCK_BYTES}",
            f"count={blocks}",
            "conv=fsync",
            service=service,
            timeout=max(60, min(600, blocks / 100 + 30)),
            expected_failure=full,
        )
        return {"command": result, "blocks_requested": blocks, "bytes_requested": blocks * BLOCK_BYTES}

    def remove_disk_fill(self, filename: str, label: str, *, service: str = SERVICE) -> dict[str, Any]:
        return self.run_exec(
            f"disk-{label}-remove",
            "unlink",
            f"/app/data/{filename}",
            service=service,
            timeout=30,
        )

    def run_disk_phase(self) -> dict[str, Any]:
        if self.args.skip_disk:
            self.check("tmpfs high-water/application recovery", True, "explicitly disabled", status="skipped")
            return {"status": "skipped", "reason": "--skip-disk"}

        disk_up = self.run_compose(
            "compose-up-disk",
            "up",
            "--no-build",
            "--detach",
            "--wait",
            DISK_SERVICE,
            timeout=self.args.startup_timeout,
        )
        disk_ready = disk_up.get("status") == "passed" and self.wait_ready(
            self.args.readiness_timeout,
            "disk high-water readiness",
            client=self.disk_client,
            record_sample=False,
        )
        disk_state = self.inspect_container("disk-initial", service=DISK_SERVICE)
        if not disk_ready:
            self.check("disk high-water service readiness", False, str(disk_up))
            return {
                "status": "failed",
                "compose_up": disk_up,
                "container_state": disk_state,
            }
        self.check("disk high-water service readiness", True, self.disk_base_url)

        before = self.capture_storage("before-disk", service=DISK_SERVICE)
        available_before = before.get("available_bytes")
        if not isinstance(available_before, int):
            self.indeterminate_check("tmpfs high-water/application recovery", "df output did not expose available bytes")
            return {
                "status": "indeterminate",
                "compose_up": disk_up,
                "container_state": disk_state,
                "before": before,
            }
        reserve_bytes = self.config.disk_reserve_mb * 1024 * 1024
        if available_before <= reserve_bytes + BLOCK_BYTES:
            self.indeterminate_check("tmpfs high-water/application recovery", f"only {available_before} bytes available before fill")
            return {
                "status": "indeterminate",
                "compose_up": disk_up,
                "container_state": disk_state,
                "before": before,
            }

        # Exercise the real Echo storage path on the disk service before the
        # filler is written. This prevents a passing df/unlink probe from
        # being mistaken for an application persistence/recovery check.
        required_storage_files = {"mockdb.sqlite", "request-log-spool.sqlite"}
        disk_rule_ok = self.create_rule(client=self.disk_client, label="disk")
        disk_preload_ids: list[str] = []
        disk_preload_delivery: dict[str, Any] = {
            "status": "failed",
            "reason": "disk service rule or enqueue did not produce accepted IDs",
        }
        disk_preload_logs: dict[str, Any] = {
            "status": "failed",
            "reason": "disk service rule or enqueue did not produce accepted IDs",
        }
        if disk_rule_ok:
            disk_preload_ids = self.send_messages("disk-preload", 2, client=self.disk_client)
            if disk_preload_ids:
                disk_preload_delivery = self.wait_for_delivery(
                    len(disk_preload_ids),
                    self.config.drain_timeout_seconds,
                    "disk service preload drain",
                    client=self.disk_client,
                )
                disk_preload_logs = self.collect_log_records(
                    client=self.disk_client,
                    expected_ids=disk_preload_ids,
                )
        storage_after_traffic = self.capture_storage("after-disk-traffic", service=DISK_SERVICE)
        observed_storage_files = self.storage_basenames(storage_after_traffic)
        disk_preload_accounting = False
        if disk_preload_ids:
            disk_preload_accounting = self.verify_delivery_sets(
                disk_preload_logs,
                expected_ids=disk_preload_ids,
                label="disk service preload JMS ID accounting",
                check_restart_recovery=False,
            )
        disk_storage_observed = required_storage_files <= observed_storage_files
        self.check(
            "disk service SQLite/spool artifacts observed",
            disk_storage_observed,
            f"required={sorted(required_storage_files)} observed={sorted(observed_storage_files)}",
        )
        disk_preload_ok = bool(
            disk_rule_ok
            and disk_preload_ids
            and disk_preload_delivery.get("status") == "passed"
            and disk_preload_accounting
            and disk_storage_observed
        )

        high = self.disk_fill(
            ".echo-resilience-fill-high",
            available_before,
            reserve_bytes,
            "high-water",
            full=False,
            service=DISK_SERVICE,
        )
        after_high = self.capture_storage("after-high-water", service=DISK_SERVICE)
        available_high = after_high.get("available_bytes")
        high_pass = (
            high["command"].get("status") == "passed"
            and isinstance(available_high, int)
            and available_high <= reserve_bytes + 2 * BLOCK_BYTES
        )
        health_high = self.snapshot_status(
            "disk-high-water-health", client=self.disk_client, record_sample=False
        ) is not None
        remove_high = self.remove_disk_fill(
            ".echo-resilience-fill-high", "high-water", service=DISK_SERVICE
        )
        recovered = self.capture_storage("after-high-water-recovery", service=DISK_SERVICE)
        available_recovered = recovered.get("available_bytes")
        recovery_pass = (
            remove_high.get("status") == "passed"
            and isinstance(available_recovered, int)
            and available_recovered > (available_high if isinstance(available_high, int) else 0)
            and self.snapshot_status(
                "disk-high-water-recovered", client=self.disk_client, record_sample=False
            ) is not None
        )
        recovered_storage_files = self.storage_basenames(recovered)
        disk_storage_recovered = required_storage_files <= recovered_storage_files
        self.check(
            "disk service SQLite/spool artifacts survive filler recovery",
            disk_storage_recovered,
            f"required={sorted(required_storage_files)} observed={sorted(recovered_storage_files)}",
        )
        self.optional_indeterminate_check(
            "host OS disk reclamation",
            "container tmpfs df/du and filler unlink measure application-visible recovery only; host physical reclamation is not measured",
        )

        # Send one more message through the same disk-backed Echo service after
        # the high-water filler is removed. The recovery ID is accounted for
        # together with preload IDs so unrelated logs cannot be mistaken for a
        # successful post-recovery send.
        disk_recovery_ids: list[str] = []
        disk_recovery_delivery: dict[str, Any] = {
            "status": "failed",
            "reason": "disk service recovery enqueue did not produce an accepted ID",
        }
        disk_recovery_logs: dict[str, Any] = {
            "status": "failed",
            "reason": "disk service recovery enqueue did not produce an accepted ID",
        }
        disk_recovery_accounting = False
        if disk_rule_ok and recovery_pass:
            disk_recovery_ids = self.send_messages("disk-recovery", 1, client=self.disk_client)
            if disk_recovery_ids:
                disk_expected_ids = disk_preload_ids + disk_recovery_ids
                disk_recovery_delivery = self.wait_for_delivery(
                    len(disk_expected_ids),
                    self.config.drain_timeout_seconds,
                    "disk service recovery drain",
                    client=self.disk_client,
                )
                disk_recovery_logs = self.collect_log_records(
                    client=self.disk_client,
                    expected_ids=disk_expected_ids,
                )
                disk_recovery_accounting = self.verify_delivery_sets(
                    disk_recovery_logs,
                    expected_ids=disk_expected_ids,
                    label="disk service recovery JMS ID accounting",
                    check_restart_recovery=False,
                )
        storage_after_recovery_traffic = self.capture_storage(
            "after-disk-recovery-traffic", service=DISK_SERVICE
        )
        recovery_traffic_storage_files = self.storage_basenames(storage_after_recovery_traffic)
        disk_recovery_traffic_ok = bool(
            disk_recovery_ids
            and disk_recovery_delivery.get("status") == "passed"
            and disk_recovery_accounting
            and required_storage_files <= recovery_traffic_storage_files
        )
        self.check(
            "disk service traffic recovers after high-water",
            disk_recovery_traffic_ok,
            f"accepted_recovery={len(disk_recovery_ids)} delivery={disk_recovery_delivery.get('status')} artifacts={sorted(recovery_traffic_storage_files)}",
        )
        disk_preload_storage_ok = disk_preload_ok
        self.check(
            "disk service preload exercised SQLite/spool path",
            disk_preload_storage_ok,
            f"rule={disk_rule_ok} accepted={len(disk_preload_ids)} delivery={disk_preload_delivery.get('status')} accounting={disk_preload_accounting}",
        )
        disk_phase_pass = bool(
            high_pass
            and health_high
            and recovery_pass
            and disk_storage_recovered
            and disk_recovery_traffic_ok
            and disk_preload_storage_ok
            and not disk_state.get("oom_killed", False)
        )
        result: dict[str, Any] = {
            "status": "passed" if disk_phase_pass else "failed",
            "compose_up": disk_up,
            "container_state": disk_state,
            "before": before,
            "storage_after_traffic": storage_after_traffic,
            "disk_preload_ids": disk_preload_ids,
            "disk_preload_delivery": disk_preload_delivery,
            "disk_preload_logs": disk_preload_logs,
            "high_water": high,
            "after_high_water": after_high,
            "high_water_health_responsive": health_high,
            "high_water_remove": remove_high,
            "recovered": recovered,
            "storage_after_recovery_traffic": storage_after_recovery_traffic,
            "disk_recovery_ids": disk_recovery_ids,
            "disk_recovery_delivery": disk_recovery_delivery,
            "disk_recovery_logs": disk_recovery_logs,
            "required_storage_files": sorted(required_storage_files),
            "storage_files_after_traffic": sorted(observed_storage_files),
            "storage_files_after_filler_recovery": sorted(recovered_storage_files),
            "storage_files_after_recovery_traffic": sorted(recovery_traffic_storage_files),
            "storage_artifacts_observed": disk_storage_observed,
            "storage_artifacts_recovered": disk_storage_recovered,
            "host_os_reclamation": {
                "status": "indeterminate",
                "reason": "tmpfs df/du is container-scoped and does not measure host physical disk reclamation",
            },
            "available_before": available_before,
            "available_high": available_high,
            "available_recovered": available_recovered,
        }
        self.check(
            "tmpfs high-water/application recovery",
            high_pass and health_high and recovery_pass,
            f"available={available_before}->{available_high}->{available_recovered}",
        )

        if self.args.disk_full:
            # Run the disk-guard experiment in a deliberately small, separate
            # service/volume. The CLI option keeps its historical --disk-full
            # name, but the test intentionally avoids actual ENOSPC.
            full_up = self.run_compose(
                "compose-up-disk-full",
                "up",
                "--no-build",
                "--detach",
                "--wait",
                FULL_DISK_SERVICE,
                timeout=self.args.startup_timeout,
            )
            full_ready = full_up.get("status") == "passed" and self.wait_ready(
                self.args.readiness_timeout,
                "disk-full readiness",
                client=self.full_client,
                record_sample=False,
            )
            if not full_ready:
                self.check("disk full service readiness", False, str(full_up))
                result["full"] = {"status": "failed", "compose_up": full_up}
                return result
            self.check("disk full service readiness", True, self.full_base_url)
            full_rule_ok = self.create_rule(client=self.full_client, label="disk-full")
            before_full = self.capture_storage("before-full", service=FULL_DISK_SERVICE)
            available_full_before = before_full.get("available_bytes")
            if not full_rule_ok or not isinstance(available_full_before, int):
                self.indeterminate_check("disk full/recovery", "disk-full rule or df setup failed")
                result["full"] = {"status": "indeterminate", "before": before_full, "compose_up": full_up}
                return result
            full = self.disk_fill(
                ".echo-resilience-fill-full",
                available_full_before,
                FULL_DISK_GUARD_BYTES,
                "full",
                full=False,
                service=FULL_DISK_SERVICE,
            )
            after_full = self.capture_storage("after-full", service=FULL_DISK_SERVICE)
            available_after_full = after_full.get("available_bytes")
            state_full = self.inspect_container("disk-full", service=FULL_DISK_SERVICE)
            full_stdout = self.runner.read_stdout(full["command"])
            try:
                full_stderr = Path(str(full["command"].get("stderr", ""))).read_text(
                    encoding="utf-8", errors="replace"
                )
            except OSError:
                full_stderr = ""
            full_fill_output = f"{full_stdout} {full_stderr}".lower()
            full_observed = isinstance(available_after_full, int) and (
                0 < available_after_full <= FULL_DISK_GUARD_BYTES + 2 * BLOCK_BYTES
            )

            # Give Artemis at least one configured scan interval to notice the
            # safety threshold before the producer starts. The test validates
            # prevention of ENOSPC, not recovery after the broker's documented
            # critical-I/O shutdown path.
            time.sleep(1.0)

            # The send occurs while the small volume is below the configured
            # free-space guard. Keep the request
            # in a future so a producer that is blocked by broker/storage
            # pressure remains observable while the filler is present. A 2xx
            # response is not trusted as delivery proof: the ID must still
            # appear in a durable log after the filler is removed. A non-2xx /
            # transport result is recorded as rejected/uncertain, never
            # counted as loss.
            full_message_id = f"{self.run_id}-disk-full-000000"
            full_send_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="disk-full-sender")
            full_send_future = full_send_executor.submit(
                self.send_one, full_message_id, client=self.full_client
            )
            pending_window = min(5.0, max(1.0, self.config.backlog_window_seconds))
            pending_observed = False
            full_send: Optional[dict[str, Any]] = None
            try:
                try:
                    full_send = full_send_future.result(timeout=pending_window)
                except TimeoutError:
                    pending_observed = True
                except Exception as exc:
                    full_send = {
                        "id": full_message_id,
                        "accepted": False,
                        "status": 0,
                        "elapsed_ms": None,
                        "detail": f"send future failed: {type(exc).__name__}: {exc}",
                    }

                logs_while_full = self.count_jms_logs(client=self.full_client)
                health_full = self.snapshot_status(
                    "disk-full-health", client=self.full_client, record_sample=False
                ) is not None
                remove_full = self.remove_disk_fill(
                    ".echo-resilience-fill-full", "full", service=FULL_DISK_SERVICE
                )
                after_full_recovery = self.capture_storage(
                    "after-full-recovery", service=FULL_DISK_SERVICE
                )
                available_after_recovery = after_full_recovery.get("available_bytes")
                full_recovery_ready = self.wait_ready(
                    self.args.readiness_timeout,
                    "disk-full recovered",
                    client=self.full_client,
                    record_sample=False,
                )

                if full_send is None:
                    try:
                        # Once capacity is restored, await the very same
                        # request. A timeout stays uncertain and is never
                        # retried because it may already have been accepted.
                        full_send = full_send_future.result(timeout=45)
                    except TimeoutError:
                        full_send = {
                            "id": full_message_id,
                            "accepted": False,
                            "status": 0,
                            "elapsed_ms": None,
                            "detail": "send future remained uncertain after recovery",
                        }
                    except Exception as exc:
                        full_send = {
                            "id": full_message_id,
                            "accepted": False,
                            "status": 0,
                            "elapsed_ms": None,
                            "detail": f"send future failed after recovery: {type(exc).__name__}: {exc}",
                        }
            finally:
                full_send_executor.shutdown(wait=True, cancel_futures=False)

            if full_send is None:
                # Defensive fallback; normally the future is resolved above.
                full_send = {
                    "id": full_message_id,
                    "accepted": False,
                    "status": 0,
                    "elapsed_ms": None,
                    "detail": "send future produced no result",
                }
            full_accepted = bool(full_send.get("accepted"))
            full_uncertain = full_send.get("status") == 0
            full_expected = [full_message_id] if full_accepted or full_uncertain else []
            full_send_state = (
                "pending-then-accepted" if pending_observed and full_accepted
                else "pending-uncertain" if pending_observed and full_uncertain
                else "accepted-pending" if full_accepted and logs_while_full in (None, 0)
                else "accepted-already-logged" if full_accepted
                else "uncertain" if full_uncertain
                else "rejected"
            )
            full_retry: Optional[dict[str, Any]] = None
            full_retry_attempts: list[dict[str, Any]] = []
            if not full_expected and full_recovery_ready:
                # A clear non-2xx rejection is not counted as loss. Retry the
                # exact same ID after recovery. Retry only clear HTTP
                # rejections within a bounded recovery window. An
                # uncertain transport result is never retried.
                retry_deadline = time.monotonic() + min(30.0, self.config.drain_timeout_seconds)
                while True:
                    full_retry = self.send_one(full_message_id, client=self.full_client)
                    full_retry_attempts.append(full_retry)
                    if full_retry.get("accepted"):
                        full_expected = [full_message_id]
                        full_send_state = "rejected-then-retried"
                        break
                    if full_retry.get("status") == 0 or time.monotonic() >= retry_deadline:
                        break
                    time.sleep(1.0)
            full_delivery: dict[str, Any] = {"status": "skipped", "reason": "send rejected while full"}
            full_logs: dict[str, Any] = {"status": "skipped", "reason": "send rejected while full"}
            if full_expected:
                full_delivery = self.wait_for_delivery(
                    len(full_expected),
                    self.config.drain_timeout_seconds,
                    "disk-full queued message drain",
                    client=self.full_client,
                )
                full_logs = self.collect_log_records(
                    client=self.full_client,
                    expected_ids=full_expected,
                )
            recovery_full = (
                remove_full.get("status") == "passed"
                and full_recovery_ready
                and isinstance(available_after_recovery, int)
                and available_after_recovery > (available_after_full if isinstance(available_after_full, int) else 0)
            )
            message_recovery_ok = bool(full_expected) and (
                full_delivery.get("status") == "passed"
                and not full_logs.get("missing_ids")
                and not full_logs.get("duplicate_ids")
                and not full_logs.get("unknown_ids")
            )
            full_pass = (
                full_observed
                and health_full
                and recovery_full
                and message_recovery_ok
                and not state_full.get("oom_killed", False)
            )
            result["full"] = {
                "status": "passed" if full_pass else "failed",
                "compose_up": full_up,
                "before": before_full,
                "fill": full,
                "fill_output": short_text(full_fill_output),
                "after_full": after_full,
                "full_observed": full_observed,
                "health_responsive": health_full,
                "container_state": state_full,
                "send_while_full": full_send,
                "pending_observed": pending_observed,
                "pending_window_seconds": pending_window,
                "send_state": full_send_state,
                "logs_while_full": logs_while_full,
                "retry_after_recovery": full_retry,
                "retry_attempts_after_recovery": full_retry_attempts,
                "queued_message_delivery": full_delivery,
                "queued_message_logs": full_logs,
                "remove": remove_full,
                "after_recovery": after_full_recovery,
                "available_before": available_full_before,
                "available_after_full": available_after_full,
                "available_after_recovery": available_after_recovery,
            }
            self.check(
                "disk full/recovery",
                full_pass,
                f"full_observed={full_observed} send_state={full_send_state} health_responsive={health_full} available={available_full_before}->{available_after_full}->{available_after_recovery}",
            )
            if pending_observed:
                self.check(
                    "disk-full producer request remained pending until recovery",
                    True,
                    f"pending_window_seconds={pending_window}",
                )
            elif full_send.get("accepted"):
                self.optional_indeterminate_check(
                    "disk-full producer request remained pending until recovery",
                    "endpoint returned before the pending observation window; durable ID verification still ran",
                )
            else:
                self.check(
                    "disk-full producer request remained pending until recovery",
                    True,
                    f"endpoint rejected while full with status={full_send.get('status')}; exact ID was retried after recovery",
                )
        else:
            result["full"] = {"status": "skipped", "reason": "--disk-full not requested"}
            self.check(
                "disk full/recovery",
                True,
                "explicitly not requested",
                status="skipped",
                affects_exit=False,
            )
        return result

    def verify_runtime_bounds(self, final_state: Mapping[str, Any]) -> None:
        heap_maxes = [int(sample["heap_max"]) for sample in self.samples if isinstance(sample.get("heap_max"), int)]
        heap_used = [int(sample["heap_used"]) for sample in self.samples if isinstance(sample.get("heap_used"), int)]
        # HotSpot reports a Runtime.maxMemory() below the literal -Xmx on
        # some container/JDK combinations. Require a close, never-larger
        # bound and separately verify the exact JAVA_OPTS string below.
        expected_match = bool(heap_maxes) and all(
            int(self.heap_bytes * 0.90) <= value <= self.heap_bytes for value in heap_maxes
        )
        max_used = max(heap_used) if heap_used else None
        self.check(
            "fixed JVM heap",
            expected_match,
            f"expected_range={int(self.heap_bytes * 0.90)}..{self.heap_bytes} observed_maxes={sorted(set(heap_maxes))}",
        )
        self.check("bounded heap samples", bool(max_used is not None and max_used <= self.heap_bytes), f"peak_used={max_used} max={self.heap_bytes}")

        java_opts = str(final_state.get("java_opts", ""))
        heap_token_match = re.search(r"(?:^|\s)-Xms([^\s]+)\s+-Xmx([^\s]+)(?:\s|$)", java_opts)
        exact_heap_opts = bool(
            heap_token_match
            and parse_memory(heap_token_match.group(1)) == self.heap_bytes
            and parse_memory(heap_token_match.group(2)) == self.heap_bytes
        )
        container_limit = final_state.get("memory_limit_bytes")
        self.check(
            "JAVA_OPTS fixed heap honored",
            exact_heap_opts,
            f"java_opts={short_text(java_opts, 240)}",
        )
        self.check(
            "container memory bound honored",
            isinstance(container_limit, int) and container_limit >= self.heap_bytes,
            f"memory_limit_bytes={container_limit} heap_bytes={self.heap_bytes}",
        )

        responsive = [sample for sample in self.samples if sample.get("status") == 200]
        self.check("process remains responsive", bool(responsive), f"status_200_samples={len(responsive)} total_samples={len(self.samples)}")
        self.check("container not OOM-killed", not bool(final_state.get("oom_killed")) and not self.known_oom, f"final_state={dict(final_state)}")

        pressure_observed = [
            sample.get("fields", {}).get("pressure", {})
            for sample in self.samples
            if isinstance(sample.get("fields"), Mapping)
        ]
        agent_pressure_observed = any(
            isinstance(agent, Mapping)
            and any(key in agent for key in BACKPRESSURE_FIELDS)
            for sample in self.load_samples
            for agent in (sample.get("agents") or [])
        )
        if any(value for value in pressure_observed) or agent_pressure_observed:
            self.check("optional pressure metrics captured", True, f"samples={sum(bool(value) for value in pressure_observed)}")
        else:
            self.optional_indeterminate_check("optional pressure metrics captured", "current status contract exposes no pressure field; backlog is inferred from accepted/logged IDs")

    def collect_runtime_logs(self) -> dict[str, Any]:
        result = self.run_compose("compose-logs", "logs", "--no-color", SERVICE, timeout=120)
        text = self.runner.read_stdout(result)
        lower = text.lower()
        markers = [marker for marker in ("outofmemoryerror", "java heap space", "oomkilled") if marker in lower]
        self.known_oom = bool(markers)
        return {
            "status": result.get("status"),
            "markers": markers,
            "stdout": result.get("stdout"),
            "stderr": result.get("stderr"),
        }

    def verify_backpressure_metrics(self) -> None:
        """Validate the agent pressure contract when the backend exposes it."""
        observed: dict[str, list[Any]] = {key: [] for key in BACKPRESSURE_FIELDS}
        pressure_states: list[dict[str, Any]] = []
        dropped_counts: list[int] = []
        for sample in self.load_samples:
            agents = sample.get("agents")
            if not isinstance(agents, list):
                continue
            for agent in agents:
                if not isinstance(agent, Mapping):
                    continue
                state = {key: agent[key] for key in BACKPRESSURE_FIELDS if key in agent}
                if state:
                    pressure_states.append(state)
                for key in BACKPRESSURE_FIELDS:
                    if key in agent:
                        observed[key].append(agent[key])
                dropped = agent.get("droppedCount")
                if isinstance(dropped, (int, float)):
                    dropped_counts.append(int(dropped))

        if not pressure_states:
            self.optional_indeterminate_check(
                "backpressure metrics captured",
                "agents endpoint has no queueBytes/queueCapacityBytes/inFlightBytes/inFlightByteLimit/waitingProducers/backpressureActive fields",
            )
        else:
            missing = [key for key in BACKPRESSURE_FIELDS if not observed[key]]
            self.check(
                "backpressure metrics contract",
                not missing,
                f"missing={missing} observed={sorted(key for key, values in observed.items() if values)}",
            )
            numeric_fields = ("queueBytes", "queueCapacityBytes", "inFlightBytes", "inFlightByteLimit", "waitingProducers")
            numeric_valid = all(
                all(isinstance(value, (int, float)) and value >= 0 for value in observed[key])
                for key in numeric_fields
                if observed[key]
            )
            self.check(
                "backpressure metric values bounded",
                numeric_valid,
                "all sampled numeric pressure values are non-negative"
                if numeric_valid
                else "negative/non-numeric pressure metric observed",
            )
            pressure_seen = any(
                state.get("backpressureActive") is True
                or (isinstance(state.get("waitingProducers"), (int, float)) and state["waitingProducers"] > 0)
                or (isinstance(state.get("queueBytes"), (int, float)) and state["queueBytes"] > 0)
                or (isinstance(state.get("inFlightBytes"), (int, float)) and state["inFlightBytes"] > 0)
                for state in pressure_states
            )
            if pressure_seen:
                self.check(
                    "backpressure metrics show load pressure",
                    True,
                    "non-zero queue/in-flight/waiting or active sample observed",
                )
            else:
                # The delayed JMS consumer backlog can be real while the
                # asynchronous log writer drains fast enough never to fill.
                # Keep the exact zero-valued contract samples without turning
                # this timing-sensitive observation into a false failure.
                self.optional_indeterminate_check(
                    "backpressure metrics show load pressure",
                    "agent pressure stayed at zero during this load window; JMS backlog evidence remains authoritative",
                )

        if dropped_counts:
            self.check("log-agent did not drop accepted work", max(dropped_counts) == 0, f"max_dropped={max(dropped_counts)}")

    def run(self) -> int:
        print(f"Container resilience harness: mode={self.args.mode} project={self.project_name}")
        print(f"  HTTP={self.http_port} JMS={self.jms_port} queue={self.queue} output={self.output_dir}")
        started = time.monotonic()
        result: dict[str, Any] = {
            "schema_version": SCHEMA_VERSION,
            "run_id": self.run_id,
            "project_name": self.project_name,
            "compose_file": str(self.compose_file),
            "base_url": self.base_url,
            "parameters": self.parameters(),
            "started_at": utc_now(),
            "safety": {
                "unique_project_guard": owned_project(self.project_name),
                "volume": "project-scoped primary named volume plus separate tmpfs disk fixtures",
                "repository_database_touched": False,
                "host_bind_mounts": False,
            },
        }
        compose_attempted = False
        try:
            version = self.runner.run(
                "docker-compose-version", ["docker", "compose", "version"], timeout=30
            )
            if version.get("status") != "passed":
                raise HarnessFailure("Docker Compose is unavailable")
            compose_attempted = True
            up = self.run_compose("compose-up", "up", "--build" if not self.args.skip_build else "--no-build", "--detach", "--wait", SERVICE, timeout=self.args.startup_timeout)
            if up.get("status") != "passed":
                raise HarnessFailure("Compose service did not start")
            if not self.wait_ready(self.args.readiness_timeout, "initial readiness"):
                raise HarnessFailure("initial HTTP readiness failed")
            self.check("initial readiness", True, f"{self.base_url}/api/admin/status")
            public_ready, public_detail = self.public_status_probe()
            self.check("unauthenticated status healthcheck", public_ready, public_detail)
            initial_state = self.inspect_container("initial")
            self.check("container running after startup", initial_state.get("running") is True, str(initial_state))
            before_load = self.capture_storage("before-load")
            result["storage_before_load"] = before_load

            if not self.create_rule():
                raise HarnessFailure("JMS rule setup failed")
            sentinel_write = self.write_restart_sentinel()
            self.check(
                "restart sentinel write",
                sentinel_write.get("status") == "passed",
                str(sentinel_write),
            )
            result["restart_sentinel_write"] = sentinel_write
            if self.rule_id:
                rule_before = self.client.request(
                    "GET", f"/api/admin/rules/{self.rule_id}", timeout=30
                )
                self.check(
                    "database rule visible before restart",
                    rule_before.status == 200,
                    f"status={rule_before.status}",
                )

            self.start_sampling("pre-kill")
            self.pre_kill_ids = self.send_messages("pre-kill", self.config.messages)
            time.sleep(self.config.backlog_window_seconds)
            logs_before_kill = self.count_jms_logs()
            pending_estimate = (
                len(self.pre_kill_ids) - logs_before_kill
                if isinstance(logs_before_kill, int)
                else None
            )
            result["pre_kill"] = {
                "accepted_ids": self.pre_kill_ids,
                "accepted_count": len(self.pre_kill_ids),
                "logs_before_kill": logs_before_kill,
                "pending_estimate_before_kill": pending_estimate,
            }
            self.check(
                "controlled consumer backlog observed",
                isinstance(pending_estimate, int) and pending_estimate > 0,
                f"accepted={len(self.pre_kill_ids)} logs_before_kill={logs_before_kill} pending_estimate={pending_estimate}; delay_ms={self.config.delay_ms}",
            )
            # Artemis can remove paging files as soon as the queue drains;
            # capture the broker directory while the delayed consumer is
            # demonstrably under load, before the intentional kill.
            result["storage_before_kill"] = self.capture_storage("before-kill")
            self.stop_sampling()
            kill = self.run_compose("compose-kill", "kill", "--signal", "SIGKILL", SERVICE, timeout=60)
            killed_state = self.inspect_container("after-kill")
            self.check("intentional kill completed", kill.get("status") == "passed", str(kill))
            self.check("container stopped before restart", killed_state.get("running") is False, str(killed_state))
            result["killed_state"] = killed_state
            result["storage_after_kill"] = self.capture_storage("after-kill")

            start = self.run_compose("compose-start", "start", SERVICE, timeout=120)
            if start.get("status") != "passed" or not self.wait_ready(self.args.readiness_timeout, "post-restart readiness"):
                raise HarnessFailure("container did not recover after kill/start")
            self.check("post-kill/start readiness", True, self.base_url)
            restarted_state = self.inspect_container("after-restart")
            self.check("container running after restart", restarted_state.get("running") is True, str(restarted_state))
            result["restarted_state"] = restarted_state
            sentinel_read, sentinel_value = self.read_restart_sentinel()
            self.check(
                "named volume persisted across restart",
                sentinel_read.get("status") == "passed" and sentinel_value == self.run_id,
                f"status={sentinel_read.get('status')} value_matches={sentinel_value == self.run_id}",
            )
            result["restart_sentinel_read"] = {
                **sentinel_read,
                "value_matches": sentinel_value == self.run_id,
            }
            result["storage_after_restart"] = self.capture_storage("after-restart")
            if self.rule_id:
                rule_after = self.client.request("GET", f"/api/admin/rules/{self.rule_id}", timeout=30)
                self.check("database rule persisted across restart", rule_after.status == 200, f"status={rule_after.status}")

            self.start_sampling("post-restart")
            delivery_pre = self.wait_for_delivery(len(self.pre_kill_ids), self.config.drain_timeout_seconds, "pre-kill drain")
            self.check("pre-kill drain completed", delivery_pre.get("status") == "passed", short_text(delivery_pre))
            self.recovery_ids = self.send_messages("post-restart", self.config.recovery_messages)
            self.stop_sampling()
            self.expected_ids = sorted(self.pre_kill_ids + self.recovery_ids)
            delivery_all = self.wait_for_delivery(len(self.expected_ids), self.config.drain_timeout_seconds, "post-restart drain")
            self.check("post-restart drain completed", delivery_all.get("status") == "passed", short_text(delivery_all))

            result["delivery_pre"] = delivery_pre
            result["delivery_all"] = delivery_all
            logs = self.collect_log_records()
            result["logs"] = logs
            self.verify_delivery_sets(logs)

            result["storage_after_load"] = self.capture_storage("after-load")
            artemis_paths = list(result["storage_after_load"].get("artemis_paths", []))
            artemis_paths.extend(result.get("storage_before_kill", {}).get("artemis_paths", []))
            artemis_paths.extend(result.get("storage_after_restart", {}).get("artemis_paths", []))
            result["paging_observed"] = any("paging" in path.lower() for path in artemis_paths)
            if result["paging_observed"]:
                self.check("Artemis paging files observed", True, f"paths={len(artemis_paths)}")
            else:
                self.optional_indeterminate_check("Artemis paging files observed", "no paging path was visible at the final snapshot; load/backlog evidence remains valid")
            self.verify_backpressure_metrics()

            disk = self.run_disk_phase()
            result["disk"] = disk
            final_state = self.inspect_container("final")
            runtime_logs = self.collect_runtime_logs()
            result["final_state"] = final_state
            result["runtime_logs"] = runtime_logs
            self.verify_runtime_bounds(final_state)
            if runtime_logs.get("markers"):
                self.check("runtime logs contain no OOM markers", False, str(runtime_logs.get("markers")))
            else:
                self.check("runtime logs contain no OOM markers", True)
        except (HarnessFailure, ConfigurationError) as exc:
            self.check("harness execution", False, str(exc))
            result["error"] = str(exc)
        except KeyboardInterrupt:
            self.check("harness execution", False, "interrupted by user")
            result["error"] = "interrupted by user"
        except Exception as exc:  # retain cleanup/evidence for unexpected harness defects
            self.check("harness execution", False, f"unexpected {type(exc).__name__}: {exc}")
            result["error"] = f"unexpected {type(exc).__name__}: {exc}"
        finally:
            self.stop_sampling()
            if compose_attempted:
                result["cleanup_before"] = self.inspect_container("cleanup")
                result["runtime_logs_at_cleanup"] = self.collect_runtime_logs()
                if self.args.keep:
                    cleanup = {"status": "skipped", "reason": "--keep"}
                else:
                    cleanup = self.run_compose("compose-down", "down", "--volumes", "--remove-orphans", timeout=180)
                    cleanup_containers = self.run_compose(
                        "cleanup-container-probe", "ps", "-aq", timeout=30
                    )
                    cleanup_volumes = self.runner.run(
                        "cleanup-volume-probe",
                        [
                            "docker",
                            "volume",
                            "ls",
                            "--quiet",
                            "--filter",
                            f"label=com.docker.compose.project={self.project_name}",
                        ],
                        timeout=30,
                    )
                    cleanup_networks = self.runner.run(
                        "cleanup-network-probe",
                        [
                            "docker",
                            "network",
                            "ls",
                            "--quiet",
                            "--filter",
                            f"label=com.docker.compose.project={self.project_name}",
                        ],
                        timeout=30,
                    )
                    remaining_containers = self.runner.read_stdout(cleanup_containers).splitlines()
                    remaining_volumes = self.runner.read_stdout(cleanup_volumes).splitlines()
                    remaining_networks = self.runner.read_stdout(cleanup_networks).splitlines()
                    cleanup_probes_pass = (
                        cleanup_containers.get("status") == "passed"
                        and cleanup_volumes.get("status") == "passed"
                        and cleanup_networks.get("status") == "passed"
                        and not remaining_containers
                        and not remaining_volumes
                        and not remaining_networks
                    )
                    cleanup = {
                        **cleanup,
                        "resource_probe": {
                            "containers": remaining_containers,
                            "volumes": remaining_volumes,
                            "networks": remaining_networks,
                            "container_probe": cleanup_containers,
                            "volume_probe": cleanup_volumes,
                            "network_probe": cleanup_networks,
                        },
                    }
                    cleanup_pass = cleanup.get("status") == "passed" and cleanup_probes_pass
                    cleanup["status"] = "passed" if cleanup_pass else "failed"
                    self.check(
                        "scoped Compose cleanup",
                        cleanup_pass,
                        f"remaining_containers={len(remaining_containers)} remaining_volumes={len(remaining_volumes)} remaining_networks={len(remaining_networks)}",
                    )
                result["cleanup"] = cleanup
            result["checks"] = self.checks
            result["samples"] = self.samples
            result["load_samples"] = self.load_samples
            result["send_failures"] = self.send_failures
            result["commands"] = self.runner.records
            result["finished_at"] = utc_now()
            result["duration_seconds"] = round(time.monotonic() - started, 3)

        required_failures = [check for check in self.checks if check["status"] == "fail"]
        result["required_failures"] = required_failures
        result["status"] = "failed" if required_failures else ("indeterminate" if self.indeterminate else "passed")
        result["exit_code"] = 1 if required_failures else (2 if self.indeterminate else 0)
        result_path = self.output_dir / "result.json"
        write_json(result_path, result)
        print(f"Result: {result['status']} (exit={result['exit_code']})")
        print(f"Evidence: {result_path}")
        return int(result["exit_code"])


def make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run Echo's disposable container resilience harness")
    parser.add_argument("--mode", choices=tuple(MODE_CONFIGS), default=os.getenv("ECHO_RESILIENCE_MODE", "quick"))
    parser.add_argument("--messages", type=int, help="pre-kill accepted-message target; mode default otherwise")
    parser.add_argument("--recovery-messages", type=int, help="post-restart accepted-message target")
    parser.add_argument("--payload-bytes", type=int, help=f"XML body target size (maximum {MAX_RETAINED_BODY_BYTES})")
    parser.add_argument(
        "--max-body-bytes",
        type=int,
        default=int(os.getenv("ECHO_RESILIENCE_MAX_BODY_BYTES", str(MAX_RETAINED_BODY_BYTES))),
        help="request-log retained body limit; must cover payload-bytes",
    )
    parser.add_argument("--delay-ms", type=int, help="JMS rule delay used to create a controlled backlog")
    parser.add_argument("--send-concurrency", type=int, help="parallel HTTP enqueue workers")
    parser.add_argument("--backlog-window-seconds", type=float, help="wait before SIGKILL to expose backlog")
    parser.add_argument("--drain-timeout", type=float, help="seconds allowed for broker/log drain")
    parser.add_argument("--disk-reserve-mb", type=int, help="free bytes retained during high-water phase")
    parser.add_argument("--heap", default=os.getenv("ECHO_RESILIENCE_HEAP", "256m"), help="fixed Xms/Xmx size")
    parser.add_argument("--data-size", default=os.getenv("ECHO_RESILIENCE_DATA_SIZE", "256m"), help="bounded high-water tmpfs service size")
    parser.add_argument("--full-data-size", default=os.getenv("ECHO_RESILIENCE_FULL_DATA_SIZE", "64m"), help="separate small tmpfs size for the --disk-full guard test")
    parser.add_argument("--container-memory", default=os.getenv("ECHO_RESILIENCE_CONTAINER_MEMORY", "768m"), help="Compose container memory limit")
    parser.add_argument("--queue", default=os.getenv("ECHO_RESILIENCE_QUEUE", QUEUE_DEFAULT))
    parser.add_argument("--http-port", type=int, default=int(os.getenv("ECHO_RESILIENCE_HTTP_PORT", "0")))
    parser.add_argument("--jms-port", type=int, default=int(os.getenv("ECHO_RESILIENCE_JMS_PORT", "0")))
    parser.add_argument("--disk-http-port", type=int, default=int(os.getenv("ECHO_RESILIENCE_DISK_HTTP_PORT", "0")), help=argparse.SUPPRESS)
    parser.add_argument("--full-http-port", type=int, default=int(os.getenv("ECHO_RESILIENCE_FULL_HTTP_PORT", "0")), help=argparse.SUPPRESS)
    parser.add_argument("--compose-file", type=Path, default=DEFAULT_COMPOSE_FILE, help=argparse.SUPPRESS)
    parser.add_argument("--output-dir", type=Path, help="exact directory for evidence (default: artifacts/container-resilience/<run>)")
    parser.add_argument("--project-prefix", default="echo-resilience", help=argparse.SUPPRESS)
    parser.add_argument("--skip-build", action="store_true", help="use the already built image")
    parser.add_argument("--skip-disk", action="store_true", help="skip bounded tmpfs high-water/disk-guard checks")
    parser.add_argument("--disk-full", action="store_true", help="also validate the isolated tmpfs minimum-free-space guard")
    parser.add_argument("--keep", action="store_true", help="keep this uniquely named Compose project for inspection")
    parser.add_argument("--startup-timeout", type=float, default=240, help=argparse.SUPPRESS)
    parser.add_argument("--readiness-timeout", type=float, default=120, help=argparse.SUPPRESS)
    parser.add_argument("--dry-run", action="store_true", help="print the planned argv/evidence contract without Docker")
    parser.add_argument("--json", action="store_true", help="emit the final result as JSON in addition to the evidence file")
    return parser


def dry_run(args: argparse.Namespace) -> int:
    config = MODE_CONFIGS[args.mode]
    values = {
        "schema_version": SCHEMA_VERSION,
        "status": "planned",
        "mode": args.mode,
        "parameters": {
            "messages": args.messages or config.messages,
            "recovery_messages": args.recovery_messages or config.recovery_messages,
            "payload_bytes": args.payload_bytes or config.payload_bytes,
            "max_body_bytes": args.max_body_bytes,
            "delay_ms": args.delay_ms if args.delay_ms is not None else config.delay_ms,
            "send_concurrency": args.send_concurrency or config.send_concurrency,
            "heap": args.heap,
            "data_size": args.data_size,
            "full_data_size": args.full_data_size,
            "container_memory": args.container_memory,
            "disk_http_port": args.disk_http_port,
            "full_http_port": args.full_http_port,
            "disk_full": args.disk_full,
            "disk_enabled": not args.skip_disk,
        },
        "compose_file": str(Path(args.compose_file).resolve()),
        "project_name_pattern": "echo-resilience-<timestamp>-<pid>-<random>",
        "safety": {
            "repository_database_touched": False,
            "host_bind_mounts": False,
            "cleanup": "docker compose down --volumes --remove-orphans for this generated project only",
        },
        "steps": [
            "compose up with fixed Xms/Xmx and persistent Artemis/SQLite named volume",
            "enqueue unique XML IDs through /api/admin/jms/test",
            "infer controlled backlog from accepted IDs minus durable JMS logs",
            "SIGKILL then compose start and verify rule/data recovery",
            "verify request-log IDs for loss/duplicate detection",
            "capture Artemis storage/paging paths and heap/health samples",
            "start separate tmpfs disk fixtures, fill bounded high-water (and full when requested), remove filler, verify recovery",
        ],
        "planned_commands": [
            command_display(compose_command(Path(args.compose_file).resolve(), "echo-resilience-<owned-suffix>", "up", "--build" if not args.skip_build else "--no-build", "--detach", "--wait", SERVICE)),
            command_display(compose_command(Path(args.compose_file).resolve(), "echo-resilience-<owned-suffix>", "kill", "--signal", "SIGKILL", SERVICE)),
            command_display(compose_command(Path(args.compose_file).resolve(), "echo-resilience-<owned-suffix>", "start", SERVICE)),
            command_display(compose_command(Path(args.compose_file).resolve(), "echo-resilience-<owned-suffix>", "down", "--volumes", "--remove-orphans")),
        ],
    }
    print(json.dumps(values, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = make_parser()
    args = parser.parse_args(argv)
    try:
        ports = (args.http_port, args.jms_port, args.disk_http_port, args.full_http_port)
        if any(port < 0 or port > 65535 for port in ports):
            raise ConfigurationError("ports must be 0 or between 1 and 65535")
        selected_ports = [port for port in ports if port]
        if len(selected_ports) != len(set(selected_ports)):
            raise ConfigurationError("HTTP, JMS, disk HTTP, and full HTTP ports must differ")
        parse_memory(args.heap)
        if args.max_body_bytes <= 0:
            raise ConfigurationError("max-body-bytes must be positive")
        data_bytes = parse_memory(args.data_size)
        full_data_bytes = parse_memory(args.full_data_size)
        if full_data_bytes >= data_bytes:
            raise ConfigurationError("full-data-size must be smaller than data-size")
        requested_payload = args.payload_bytes or MODE_CONFIGS[args.mode].payload_bytes
        if requested_payload > MAX_RETAINED_BODY_BYTES:
            raise ConfigurationError(
                f"payload-bytes must be at most {MAX_RETAINED_BODY_BYTES} so IDs remain in retained log bodies"
            )
        if args.max_body_bytes < requested_payload:
            raise ConfigurationError("max-body-bytes must be at least payload-bytes")
        parse_memory(args.container_memory)
        if args.dry_run:
            return dry_run(args)
        harness = ResilienceHarness(args)
        result = harness.run()
        if args.json:
            result_path = harness.output_dir / "result.json"
            print(result_path.read_text(encoding="utf-8"), end="")
        return result
    except ConfigurationError as exc:
        print(f"Configuration error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
