#!/usr/bin/env python3
"""
Run the disposable Docker Compose RDBMS matrix one database at a time.

The runner intentionally owns only the Compose project name that it creates.
It never uses a shell and never runs a broad ``docker compose down``.  Each
database gets its own project, host ports, result directory, and cleanup step.

The persistence test contract is:

    python scripts/test-rdbms-persistence.py BASE_URL --state-file FILE
    python scripts/test-rdbms-persistence.py BASE_URL \
        --restart --state-file FILE

The first invocation seeds and verifies data.  The ``--restart`` invocation
verifies the same data after Echo has been restarted by this runner.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import platform
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from typing import Any, Mapping, NamedTuple, Sequence


SUPPORTED_DATABASES = (
    "h2",
    "sqlite",
    "postgresql",
    "mysql",
    "mariadb",
    "sqlserver",
    "oracle",
)

SERVICE_BY_DATABASE = {database: f"echo-{database}" for database in SUPPORTED_DATABASES}
SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIR.parent
DEFAULT_COMPOSE_FILE = REPOSITORY_ROOT / "docker-compose.rdbms.yml"
MATCH_SCRIPT = SCRIPT_DIR / "test-match-scenarios.py"
PERSISTENCE_SCRIPT = SCRIPT_DIR / "test-rdbms-persistence.py"
STRESS_SCRIPT = SCRIPT_DIR / "stress-test-rps.py"
PROJECT_PREFIX = "echo-rdbms-matrix"
DEFAULT_COMMAND_TIMEOUT = 30 * 60
DEFAULT_READINESS_TIMEOUT = 180
DEFAULT_READINESS_INTERVAL = 1.0
DEFAULT_PERFORMANCE_DURATION = 10
DEFAULT_PERFORMANCE_CONCURRENCY = 20


class ConfigurationError(ValueError):
    """Raised when the matrix cannot be run safely."""


class StepFailure(RuntimeError):
    """Raised internally when a required matrix step fails."""


class PortPair(NamedTuple):
    http: int
    jms: int


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def host_metadata() -> dict[str, str]:
    """Return stable host details useful for interpreting matrix results."""
    return {
        "platform": sys.platform,
        "machine": platform.machine() or "unknown",
    }


def is_arm_machine(machine: str) -> bool:
    normalized = (machine or "").strip().lower()
    return normalized.startswith(("arm", "aarch")) or "arm64" in normalized


def performance_comparison(database: str, host: Mapping[str, str]) -> dict[str, Any]:
    """Describe whether this database's performance is comparable on this host."""
    if database == "sqlserver" and is_arm_machine(host.get("machine", "")):
        return {
            "comparable": False,
            "functional_validation": "valid",
            "reason": (
                "SQL Server runs through x86 emulation on an ARM host; "
                "performance is not fairly comparable. Functional validation remains valid."
            ),
        }
    return {
        "comparable": True,
        "functional_validation": "valid",
        "reason": None,
    }


def performance_parameters(duration: int, concurrency: int) -> dict[str, Any]:
    """The single set of RPS conditions shared by every database profile."""
    return {
        "duration_seconds": duration,
        "concurrency": concurrency,
        "script": str(STRESS_SCRIPT),
        "json": True,
    }


def read_json(path: Path) -> tuple[dict[str, Any] | None, str | None]:
    """Read a benchmark result without allowing evidence parsing to crash cleanup."""
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return None, str(exc)
    if not isinstance(value, dict):
        return None, "JSON result is not an object"
    return value, None


def performance_result_summary(database_result: Mapping[str, Any]) -> dict[str, Any]:
    """Keep matrix-level performance summaries self-contained and comparable."""
    performance = database_result.get("performance", {})
    return {
        "database": database_result.get("database"),
        "status": performance.get("status"),
        "passed": performance.get("passed"),
        "parameters": performance.get("parameters"),
        "comparison": performance.get("comparison"),
        "result": performance.get("result"),
    }


def parse_databases(values: Sequence[str]) -> list[str]:
    """Parse repeated and/or comma-separated database arguments."""
    tokens: list[str] = []
    for value in values:
        tokens.extend(part.strip().lower() for part in value.split(","))

    if not tokens or "all" in tokens:
        return list(SUPPORTED_DATABASES)

    unknown = sorted(set(tokens) - set(SUPPORTED_DATABASES))
    if unknown:
        choices = ", ".join(SUPPORTED_DATABASES)
        raise ConfigurationError(
            f"unknown database(s): {', '.join(unknown)}; choose from {choices}"
        )

    # Preserve the requested order while avoiding accidental duplicate runs.
    return list(dict.fromkeys(tokens))


def validate_port(value: int, option: str) -> int:
    if value < 0 or value > 65535:
        raise ConfigurationError(f"{option} must be 0 or between 1 and 65535")
    return value


def free_port(reserved: set[int] | None = None) -> int:
    """Ask the OS for an unused IPv4 TCP port, avoiding this run's ports."""
    reserved = reserved or set()
    for _ in range(20):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("127.0.0.1", 0))
            port = int(sock.getsockname()[1])
        if port not in reserved:
            return port
    raise ConfigurationError("could not allocate a free host port")


def allocate_ports(
    databases: Sequence[str], http_port: int, jms_port: int, keep: bool
) -> dict[str, PortPair]:
    """Allocate per-database ports without silently colliding in --keep mode."""
    validate_port(http_port, "--http-port")
    validate_port(jms_port, "--jms-port")

    if keep and len(databases) > 1:
        if http_port and http_port != 0:
            raise ConfigurationError(
                "--keep with multiple databases needs --http-port 0 so ports stay unique"
            )
        if jms_port and jms_port != 0:
            raise ConfigurationError(
                "--keep with multiple databases needs --jms-port 0 so ports stay unique"
            )

    reserved: set[int] = set()
    allocated: dict[str, PortPair] = {}
    for database in databases:
        current_http = http_port or free_port(reserved)
        reserved.add(current_http)
        current_jms = jms_port or free_port(reserved)
        if current_jms == current_http:
            current_jms = free_port(reserved | {current_http})
        reserved.add(current_jms)
        allocated[database] = PortPair(current_http, current_jms)
    return allocated


def sanitize_project_part(value: str) -> str:
    sanitized = re.sub(r"[^a-z0-9_-]+", "-", value.lower()).strip("-_")
    if not sanitized:
        raise ConfigurationError("project prefix must contain letters or numbers")
    return sanitized[:48]


def new_project_name(prefix: str = PROJECT_PREFIX) -> str:
    """Create a unique, owned Compose project name for this invocation."""
    safe_prefix = sanitize_project_part(prefix)
    if safe_prefix == PROJECT_PREFIX:
        safe_prefix = "run"
    stamp = dt.datetime.now().strftime("%Y%m%d%H%M%S")
    return f"{PROJECT_PREFIX}-{safe_prefix}-{stamp}-{os.getpid()}-{uuid.uuid4().hex[:8]}"


def is_owned_project(project_name: str) -> bool:
    return project_name.startswith(f"{PROJECT_PREFIX}-") and bool(
        re.fullmatch(r"[a-z0-9][a-z0-9_-]*", project_name)
    )


def relative_path(path: Path, base: Path) -> str:
    try:
        return str(path.relative_to(base))
    except ValueError:
        return str(path)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def command_display(command: Sequence[str]) -> str:
    """Return a readable command without relying on a shell."""
    if os.name == "nt":
        return subprocess.list2cmdline(list(command))
    import shlex

    return shlex.join(list(command))


def run_command(
    command: Sequence[str],
    *,
    cwd: Path,
    output_dir: Path,
    label: str,
    timeout: float,
    env: Mapping[str, str] | None = None,
    dry_run: bool = False,
) -> dict[str, Any]:
    """Run one command and persist stdout/stderr without shell interpolation."""
    result: dict[str, Any] = {
        "label": label,
        "command": list(command),
        "command_display": command_display(command),
        "started_at": utc_now(),
        "timeout_seconds": timeout,
    }
    if dry_run:
        result["status"] = "planned"
        return result

    output_dir.mkdir(parents=True, exist_ok=True)
    safe_label = re.sub(r"[^a-zA-Z0-9_.-]+", "-", label)
    stdout_path = output_dir / f"{safe_label}.stdout.log"
    stderr_path = output_dir / f"{safe_label}.stderr.log"
    started = time.monotonic()
    returncode: int | None = None
    error: str | None = None

    try:
        with stdout_path.open("w", encoding="utf-8", errors="replace") as stdout, \
                stderr_path.open("w", encoding="utf-8", errors="replace") as stderr:
            completed = subprocess.run(
                list(command),
                cwd=str(cwd),
                env=dict(env) if env is not None else None,
                stdin=subprocess.DEVNULL,
                stdout=stdout,
                stderr=stderr,
                check=False,
                shell=False,
                timeout=timeout,
            )
            returncode = completed.returncode
    except subprocess.TimeoutExpired:
        returncode = 124
        error = f"timed out after {timeout:g} seconds"
    except OSError as exc:
        returncode = 127
        error = f"could not execute command: {exc}"

    result.update(
        {
            "finished_at": utc_now(),
            "duration_seconds": round(time.monotonic() - started, 3),
            "returncode": returncode,
            "status": "passed" if returncode == 0 else "failed",
            "stdout": relative_path(stdout_path, output_dir),
            "stderr": relative_path(stderr_path, output_dir),
        }
    )
    if error:
        result["error"] = error
    return result


def compose_command(
    compose_file: Path,
    project_name: str,
    database: str,
    *arguments: str,
) -> list[str]:
    return [
        "docker",
        "compose",
        "--project-name",
        project_name,
        "--file",
        str(compose_file),
        "--profile",
        database,
        *arguments,
    ]


def compose_environment(ports: PortPair) -> dict[str, str]:
    environment = os.environ.copy()
    # These are consumed by Compose interpolation only; credentials remain in
    # the caller's environment and are never copied into result JSON.
    environment["ECHO_HTTP_PORT"] = str(ports.http)
    environment["ECHO_JMS_PORT"] = str(ports.jms)
    return environment


def wait_for_http(
    url: str,
    *,
    timeout: float,
    interval: float,
    output_dir: Path,
    label: str,
    dry_run: bool = False,
) -> dict[str, Any]:
    """Poll the host-mapped status endpoint, not just Docker's container state."""
    result: dict[str, Any] = {
        "label": label,
        "url": url,
        "started_at": utc_now(),
        "timeout_seconds": timeout,
    }
    if dry_run:
        result["status"] = "planned"
        return result

    last_error = "no response"
    started = time.monotonic()
    deadline = started + timeout
    while time.monotonic() < deadline:
        request = urllib.request.Request(url, method="GET")
        request.add_header("Accept", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=min(10.0, max(interval * 2, 2.0))) as response:
                status = int(response.status)
                response.read(4096)
                if 200 <= status < 300:
                    result.update(
                        {
                            "finished_at": utc_now(),
                            "duration_seconds": round(time.monotonic() - started, 3),
                            "status": "passed",
                            "http_status": status,
                        }
                    )
                    return result
                last_error = f"HTTP {status}"
        except urllib.error.HTTPError as exc:
            # A 4xx means the web server is reachable; /api/admin/status is
            # expected to be public, so keep polling until a 2xx is observed.
            last_error = f"HTTP {exc.code}"
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            last_error = str(exc)
        time.sleep(interval)

    result.update(
        {
            "finished_at": utc_now(),
            "duration_seconds": round(time.monotonic() - started, 3),
            "status": "failed",
            "error": f"host readiness timed out: {last_error}",
        }
    )
    return result


def persistence_command(base_url: str, phase: str, state_path: Path) -> list[str]:
    command = [
        sys.executable,
        str(PERSISTENCE_SCRIPT),
        base_url,
        "--state-file",
        str(state_path),
    ]
    if phase == "after":
        command.insert(3, "--restart")
    return command


def make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Run the disposable RDBMS Compose profiles sequentially, execute "
            "E2E/persistence checks, restart Echo, and collect evidence."
        )
    )
    parser.add_argument(
        "--databases",
        nargs="+",
        default=list(SUPPORTED_DATABASES),
        metavar="DB[,DB...]",
        help="profiles to run (default: all; supports h2 sqlite postgresql mysql mariadb sqlserver oracle)",
    )
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="omit --build and use the already available image",
    )
    parser.add_argument(
        "--keep",
        action="store_true",
        help="keep each uniquely named Compose project up; otherwise down -v is guaranteed",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        help="directory for per-DB logs/results (default: artifacts/rdbms-matrix/<run>)",
    )
    parser.add_argument(
        "--http-port",
        type=int,
        default=0,
        help="host HTTP port; 0 chooses a free port per database (default: 0)",
    )
    parser.add_argument(
        "--jms-port",
        type=int,
        default=0,
        help="host JMS port; 0 chooses a free port per database (default: 0)",
    )
    parser.add_argument(
        "--performance",
        action="store_true",
        help="run stress-test-rps.py after functional and restart-persistence checks",
    )
    parser.add_argument(
        "--performance-duration",
        "--rps-duration",
        dest="performance_duration",
        type=int,
        default=DEFAULT_PERFORMANCE_DURATION,
        help=f"same RPS duration for every database (default: {DEFAULT_PERFORMANCE_DURATION}s)",
    )
    parser.add_argument(
        "--performance-concurrency",
        "--rps-concurrency",
        dest="performance_concurrency",
        type=int,
        default=DEFAULT_PERFORMANCE_CONCURRENCY,
        help=f"same RPS worker count for every database (default: {DEFAULT_PERFORMANCE_CONCURRENCY})",
    )
    parser.add_argument(
        "--project-prefix",
        "--project-name",
        dest="project_prefix",
        default=PROJECT_PREFIX,
        help="owned project-name prefix; a unique suffix is always appended",
    )
    parser.add_argument(
        "--compose-file",
        type=Path,
        default=DEFAULT_COMPOSE_FILE,
        help=argparse.SUPPRESS,
    )
    parser.add_argument(
        "--command-timeout",
        type=float,
        default=DEFAULT_COMMAND_TIMEOUT,
        help=argparse.SUPPRESS,
    )
    parser.add_argument(
        "--readiness-timeout",
        type=float,
        default=DEFAULT_READINESS_TIMEOUT,
        help=argparse.SUPPRESS,
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the planned matrix without invoking Docker or test scripts",
    )
    return parser


def planned_database_result(
    database: str,
    *,
    compose_file: Path,
    project_name: str,
    ports: PortPair,
    skip_build: bool,
    output_dir: Path,
    command_timeout: float,
    readiness_timeout: float,
    host: Mapping[str, str] | None = None,
    performance: bool = False,
    performance_duration: int = DEFAULT_PERFORMANCE_DURATION,
    performance_concurrency: int = DEFAULT_PERFORMANCE_CONCURRENCY,
) -> dict[str, Any]:
    host = host or host_metadata()
    service = SERVICE_BY_DATABASE[database]
    base_url = f"http://127.0.0.1:{ports.http}"
    up_arguments = ["up"]
    if not skip_build:
        up_arguments.append("--build")
    up_arguments.extend(["--wait", "--detach", service])
    db_dir = output_dir / database
    persistence_state = db_dir / "persistence.state.json"
    performance_info: dict[str, Any] = {
        "enabled": performance,
        "status": "planned" if performance else "skipped",
        "parameters": performance_parameters(performance_duration, performance_concurrency),
        "comparison": performance_comparison(database, host),
    }
    steps = [
        run_command(
            compose_command(compose_file, project_name, database, *up_arguments),
            cwd=REPOSITORY_ROOT,
            output_dir=db_dir,
            label="compose-up",
            timeout=command_timeout,
            dry_run=True,
        ),
        wait_for_http(
            f"{base_url}/api/admin/status",
            timeout=readiness_timeout,
            interval=DEFAULT_READINESS_INTERVAL,
            output_dir=db_dir,
            label="host-readiness",
            dry_run=True,
        ),
        run_command(
            [sys.executable, str(MATCH_SCRIPT), base_url],
            cwd=REPOSITORY_ROOT,
            output_dir=db_dir,
            label="match-scenarios",
            timeout=command_timeout,
            dry_run=True,
        ),
        run_command(
            persistence_command(base_url, "before", persistence_state),
            cwd=REPOSITORY_ROOT,
            output_dir=db_dir,
            label="persistence-before",
            timeout=command_timeout,
            dry_run=True,
        ),
        run_command(
            compose_command(compose_file, project_name, database, "restart", service),
            cwd=REPOSITORY_ROOT,
            output_dir=db_dir,
            label="compose-restart",
            timeout=command_timeout,
            dry_run=True,
        ),
        wait_for_http(
            f"{base_url}/api/admin/status",
            timeout=readiness_timeout,
            interval=DEFAULT_READINESS_INTERVAL,
            output_dir=db_dir,
            label="post-restart-readiness",
            dry_run=True,
        ),
        run_command(
            persistence_command(base_url, "after", persistence_state),
            cwd=REPOSITORY_ROOT,
            output_dir=db_dir,
            label="persistence-after",
            timeout=command_timeout,
            dry_run=True,
        ),
    ]
    if performance:
        performance_output = db_dir / "performance.json"
        steps.append(
            run_command(
                [
                    sys.executable,
                    str(STRESS_SCRIPT),
                    base_url,
                    str(performance_duration),
                    str(performance_concurrency),
                    "--json",
                    "--json-output",
                    str(performance_output),
                ],
                cwd=REPOSITORY_ROOT,
                output_dir=db_dir,
                label="performance-rps",
                timeout=command_timeout,
                dry_run=True,
            )
        )
        performance_info["result_file"] = relative_path(performance_output, db_dir)
    else:
        performance_info["status"] = "skipped"
    return {
        "database": database,
        "service": service,
        "project_name": project_name,
        "ports": ports._asdict(),
        "base_url": base_url,
        "host": dict(host),
        "performance": performance_info,
        "steps": steps,
    }


def execute_database(
    database: str,
    *,
    compose_file: Path,
    project_name: str,
    ports: PortPair,
    skip_build: bool,
    keep: bool,
    output_dir: Path,
    command_timeout: float,
    readiness_timeout: float,
    dry_run: bool,
    host: Mapping[str, str] | None = None,
    performance: bool = False,
    performance_duration: int = DEFAULT_PERFORMANCE_DURATION,
    performance_concurrency: int = DEFAULT_PERFORMANCE_CONCURRENCY,
) -> dict[str, Any]:
    host = host or host_metadata()
    service = SERVICE_BY_DATABASE[database]
    db_dir = output_dir / database
    db_dir.mkdir(parents=True, exist_ok=True)
    base_url = f"http://127.0.0.1:{ports.http}"
    status_url = f"{base_url}/api/admin/status"
    environment = compose_environment(ports)
    result: dict[str, Any] = {
        "database": database,
        "service": service,
        "project_name": project_name,
        "ports": ports._asdict(),
        "base_url": base_url,
        "host": dict(host),
        "performance": {
            "enabled": performance,
            "status": "pending" if performance else "skipped",
            "parameters": performance_parameters(performance_duration, performance_concurrency),
            "comparison": performance_comparison(database, host),
        },
        "started_at": utc_now(),
        "steps": [],
        "passed": False,
    }
    required_failed = False
    compose_attempted = False

    def add_step(step: dict[str, Any], required: bool = True) -> dict[str, Any]:
        nonlocal required_failed
        result["steps"].append(step)
        if required and step.get("status") != "passed":
            required_failed = True
        return step

    def run_compose(label: str, arguments: Sequence[str], required: bool = True) -> dict[str, Any]:
        return add_step(
            run_command(
                compose_command(compose_file, project_name, database, *arguments),
                cwd=REPOSITORY_ROOT,
                output_dir=db_dir,
                label=label,
                timeout=command_timeout,
                env=environment,
                dry_run=dry_run,
            ),
            required=required,
        )

    def run_script(label: str, command: Sequence[str], required: bool = True) -> dict[str, Any]:
        return add_step(
            run_command(
                command,
                cwd=REPOSITORY_ROOT,
                output_dir=db_dir,
                label=label,
                timeout=command_timeout,
                env=os.environ.copy(),
                dry_run=dry_run,
            ),
            required=required,
        )

    def run_readiness(label: str) -> dict[str, Any]:
        return add_step(
            wait_for_http(
                status_url,
                timeout=readiness_timeout,
                interval=DEFAULT_READINESS_INTERVAL,
                output_dir=db_dir,
                label=label,
                dry_run=dry_run,
            )
        )

    def skip_step(label: str, reason: str) -> dict[str, Any]:
        """Record a dependent validation that could not be attempted."""
        step = {"label": label, "status": "skipped", "reason": reason}
        result["steps"].append(step)
        return step

    def step_ok(step: Mapping[str, Any]) -> bool:
        return dry_run or step.get("status") == "passed"

    def skip_performance(reason: str) -> None:
        result["performance"].update(
            {"status": "skipped", "passed": None, "reason": reason}
        )

    def run_performance() -> dict[str, Any]:
        nonlocal required_failed
        performance_output = db_dir / "performance.json"
        step = run_script(
            "performance-rps",
            [
                sys.executable,
                str(STRESS_SCRIPT),
                base_url,
                str(performance_duration),
                str(performance_concurrency),
                "--json",
                "--json-output",
                str(performance_output),
            ],
        )
        result["performance"]["result_file"] = relative_path(performance_output, db_dir)
        result["performance"]["step"] = step
        if dry_run:
            result["performance"]["status"] = "planned"
            result["performance"]["passed"] = None
            return step
        parsed, parse_error = read_json(performance_output)
        if parsed is not None:
            result["performance"]["result"] = parsed
            result["performance"]["passed"] = bool(
                step.get("status") == "passed" and parsed.get("passed")
            )
            result["performance"]["status"] = (
                "passed" if result["performance"]["passed"] else "failed"
            )
            if not result["performance"]["passed"]:
                required_failed = True
        else:
            result["performance"]["passed"] = False
            result["performance"]["status"] = "failed"
            if parse_error:
                result["performance"]["result_error"] = parse_error
            required_failed = True
        return step

    try:
        up_arguments = ["up"]
        if not skip_build:
            up_arguments.append("--build")
        up_arguments.extend(["--wait", "--detach", service])
        compose_attempted = True
        up_step = run_compose("compose-up", up_arguments)

        if step_ok(up_step):
            ready_step = run_readiness("host-readiness")
        else:
            ready_step = skip_step(
                "host-readiness", "skipped because compose-up did not pass"
            )

        if step_ok(ready_step):
            match_step = run_script(
                "match-scenarios",
                [sys.executable, str(MATCH_SCRIPT), base_url],
            )
            persistence_state = db_dir / "persistence.state.json"
            if PERSISTENCE_SCRIPT.is_file() or dry_run:
                before_step = run_script(
                    "persistence-before",
                    persistence_command(base_url, "before", persistence_state),
                )
            else:
                before_step = add_step(
                    {
                        "label": "persistence-before",
                        "status": "missing",
                        "error": f"missing {PERSISTENCE_SCRIPT}",
                    }
                )

            restart_step = run_compose("compose-restart", ["restart", service])
            if step_ok(restart_step):
                post_ready = run_readiness("post-restart-readiness")
            else:
                post_ready = skip_step(
                    "post-restart-readiness",
                    "skipped because compose-restart did not pass",
                )
            if step_ok(post_ready):
                if PERSISTENCE_SCRIPT.is_file() or dry_run:
                    after_step = run_script(
                        "persistence-after",
                        persistence_command(base_url, "after", persistence_state),
                    )
                else:
                    after_step = add_step(
                        {
                            "label": "persistence-after",
                            "status": "missing",
                            "error": f"missing {PERSISTENCE_SCRIPT}",
                        }
                    )
            else:
                after_step = skip_step(
                    "persistence-after",
                    "skipped because post-restart-readiness did not pass",
                )
        else:
            match_step = skip_step(
                "match-scenarios", "skipped because host-readiness did not pass"
            )
            before_step = skip_step(
                "persistence-before", "skipped because host-readiness did not pass"
            )
            restart_step = skip_step(
                "compose-restart", "skipped because host-readiness did not pass"
            )
            post_ready = skip_step(
                "post-restart-readiness", "skipped because compose-restart was not attempted"
            )
            after_step = skip_step(
                "persistence-after", "skipped because post-restart-readiness was not attempted"
            )

        validation_steps = [
            up_step,
            ready_step,
            match_step,
            before_step,
            restart_step,
            post_ready,
            after_step,
        ]
        if performance:
            failed_steps = [
                str(step.get("label"))
                for step in validation_steps
                if not step_ok(step)
            ]
            if failed_steps:
                skip_performance(
                    "skipped because prerequisite step(s) did not pass: "
                    + ", ".join(failed_steps)
                )
            else:
                run_performance()
    except KeyboardInterrupt:
        result["interrupted"] = True
        required_failed = True
    except Exception as exc:  # keep cleanup and the next DB running
        required_failed = True
        result["error"] = f"unexpected runner error: {exc}"
    finally:
        if performance and result["performance"].get("status") == "pending":
            skip_performance("skipped because a required matrix step raised an error")
        # Logs and ps output must be collected before volumes are removed.
        if compose_attempted:
            run_compose("compose-ps", ["ps", "--all"], required=False)
            run_compose("compose-logs", ["logs", "--no-color", "--timestamps"], required=False)

        if keep:
            result["cleanup"] = {"status": "kept", "project_name": project_name}
        elif is_owned_project(project_name):
            down_step = run_compose(
                "compose-down",
                ["down", "--volumes", "--remove-orphans"],
                required=False,
            )
            result["cleanup"] = {
                "status": "removed" if down_step.get("status") == "passed" else "failed",
                "project_name": project_name,
            }
            if down_step.get("status") != "passed":
                required_failed = True
        else:
            result["cleanup"] = {
                "status": "refused",
                "error": "refused cleanup because project name is not runner-owned",
            }
            required_failed = True

    result["finished_at"] = utc_now()
    result["passed"] = not required_failed
    return result


def default_output_dir() -> Path:
    run_id = dt.datetime.now().strftime("%Y%m%d-%H%M%S") + f"-{os.getpid()}"
    return REPOSITORY_ROOT / "artifacts" / "rdbms-matrix" / run_id


def main(argv: Sequence[str] | None = None) -> int:
    parser = make_parser()
    args = parser.parse_args(argv)
    try:
        databases = parse_databases(args.databases)
        compose_file = args.compose_file.resolve()
        if not args.dry_run and not compose_file.is_file():
            raise ConfigurationError(f"Compose file does not exist: {compose_file}")
        if args.command_timeout <= 0 or args.readiness_timeout <= 0:
            raise ConfigurationError("timeouts must be positive")
        if args.performance_duration < 0:
            raise ConfigurationError("--performance-duration must be zero or greater")
        if args.performance_concurrency < 1:
            raise ConfigurationError("--performance-concurrency must be at least 1")
        ports = allocate_ports(databases, args.http_port, args.jms_port, args.keep)
        project_name = new_project_name(args.project_prefix)
        output_dir = (args.output_dir or default_output_dir()).resolve()
    except ConfigurationError as exc:
        parser.error(str(exc))

    plan_header = {
        "started_at": utc_now(),
        "project_name": project_name,
        "compose_file": str(compose_file),
        "databases": databases,
        "output_dir": str(output_dir),
        "skip_build": args.skip_build,
        "keep": args.keep,
        "host": host_metadata(),
        "performance": {
            "enabled": args.performance,
            "parameters": performance_parameters(
                args.performance_duration, args.performance_concurrency
            ),
        },
    }

    if args.dry_run:
        plan = dict(plan_header)
        plan["databases_plan"] = [
            planned_database_result(
                database,
                compose_file=compose_file,
                project_name=project_name,
                ports=ports[database],
                skip_build=args.skip_build,
                output_dir=output_dir,
                command_timeout=args.command_timeout,
                readiness_timeout=args.readiness_timeout,
                host=plan_header["host"],
                performance=args.performance,
                performance_duration=args.performance_duration,
                performance_concurrency=args.performance_concurrency,
            )
            for database in databases
        ]
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        return 0

    if shutil.which("docker") is None:
        print("error: docker executable was not found", file=sys.stderr)
        return 2
    if not MATCH_SCRIPT.is_file():
        print(f"error: missing E2E script: {MATCH_SCRIPT}", file=sys.stderr)
        return 2
    if args.performance and not STRESS_SCRIPT.is_file():
        print(f"error: missing performance script: {STRESS_SCRIPT}", file=sys.stderr)
        return 2

    output_dir.mkdir(parents=True, exist_ok=True)
    matrix: dict[str, Any] = dict(plan_header)
    matrix["databases"] = []
    matrix["results"] = []
    matrix_path = output_dir / "matrix-result.json"
    try:
        for database in databases:
            print(f"\n=== {database} ({ports[database].http}/{ports[database].jms}) ===")
            database_result = execute_database(
                database,
                compose_file=compose_file,
                project_name=project_name,
                ports=ports[database],
                skip_build=args.skip_build,
                keep=args.keep,
                output_dir=output_dir,
                command_timeout=args.command_timeout,
                readiness_timeout=args.readiness_timeout,
                dry_run=False,
                host=plan_header["host"],
                performance=args.performance,
                performance_duration=args.performance_duration,
                performance_concurrency=args.performance_concurrency,
            )
            matrix["results"].append(database_result)
            matrix["databases"].append(database)
            write_json(output_dir / database / "result.json", database_result)
            print(f"{database}: {'PASS' if database_result['passed'] else 'FAIL'}")
    except KeyboardInterrupt:
        matrix["interrupted"] = True
        if args.performance:
            matrix["performance"]["results"] = [
                performance_result_summary(result) for result in matrix["results"]
            ]
        write_json(matrix_path, matrix)
        print("\ninterrupted; completed database results were saved", file=sys.stderr)
        return 130

    matrix["finished_at"] = utc_now()
    if args.performance:
        matrix["performance"]["results"] = [
            performance_result_summary(result) for result in matrix["results"]
        ]
    matrix["passed"] = bool(matrix["results"]) and all(
        result.get("passed") for result in matrix["results"]
    )
    write_json(matrix_path, matrix)
    print(f"\nMatrix result: {'PASS' if matrix['passed'] else 'FAIL'}")
    print(f"Evidence: {matrix_path}")
    return 0 if matrix["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
