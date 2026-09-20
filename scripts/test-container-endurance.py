#!/usr/bin/env python3
"""Run a bounded, disposable SQLite endurance test in Docker Compose.

The eight-hour default is a duration, not a message count. This script never
mounts a repository/SIT database or backup directory. It owns a uniquely named
Compose project and removes only that project's containers and volumes.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import math
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import sqlite3
import statistics
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent.parent
COMPOSE = ROOT / "docker-compose.resilience.yml"
ARTIFACTS = ROOT / "artifacts" / "container-endurance"
MEMORY = re.compile(r"^[1-9][0-9]*[mMgG]$")
ID = re.compile(r"<HarnessMessageId>([^<]+)</HarnessMessageId>")


def command(args: list[str], *, env: dict[str, str] | None = None,
            timeout: int = 300, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(args, cwd=ROOT, env=env, text=True,
                            capture_output=True, timeout=timeout, check=False)
    if check and result.returncode:
        detail = (result.stderr or result.stdout).strip()[-1200:]
        raise RuntimeError(f"{args[0]} {args[1] if len(args) > 1 else ''} failed: {detail}")
    return result


def free_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def memory_bytes(value: str) -> int:
    number, unit = int(value[:-1]), value[-1].lower()
    return number * (1024 ** (2 if unit == "m" else 3))


def request(port: int, username: str, password: str, method: str, path: str,
            payload: dict | str | None = None, timeout: int = 20) -> tuple[int, object]:
    body = None
    headers = {"Accept": "application/json"}
    token = base64.b64encode(f"{username}:{password}".encode()).decode()
    headers["Authorization"] = f"Basic {token}"
    if isinstance(payload, dict):
        body = json.dumps(payload, separators=(",", ":")).encode()
        headers["Content-Type"] = "application/json"
    elif isinstance(payload, str):
        body = payload.encode()
        headers["Content-Type"] = "application/xml; charset=utf-8"
    req = Request(f"http://127.0.0.1:{port}{path}", data=body,
                  headers=headers, method=method)
    try:
        with urlopen(req, timeout=timeout) as response:
            status, raw = response.status, response.read()
    except HTTPError as error:
        status, raw = error.code, error.read()
    except (OSError, URLError, TimeoutError) as error:
        return 0, str(error)
    text = raw.decode("utf-8", errors="replace")
    try:
        return int(status), json.loads(text)
    except json.JSONDecodeError:
        return int(status), text


def compose(project: str, env: dict[str, str], *args: str,
            timeout: int = 300, check: bool = True) -> subprocess.CompletedProcess[str]:
    return command(["docker", "compose", "-f", str(COMPOSE), "-p", project, *args],
                   env=env, timeout=timeout, check=check)


def wait_ready(port: int, username: str, password: str, seconds: int = 150) -> None:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        status, _ = request(port, username, password, "GET", "/api/admin/status", timeout=3)
        if status == 200:
            return
        time.sleep(1)
    raise RuntimeError("Echo did not become ready")


def create_rule(port: int, username: str, password: str,
                payload: dict) -> str:
    status, body = request(port, username, password, "POST", "/api/admin/rules", payload)
    if status != 201 or not isinstance(body, dict) or not body.get("id"):
        raise RuntimeError(f"rule creation failed: HTTP {status}")
    return str(body["id"])


def xml_body(message_id: str, size: int) -> str:
    prefix = ("<ResilienceHarness><ServiceName>ResilienceHarness</ServiceName>"
              f"<HarnessMessageId>{message_id}</HarnessMessageId><Payload>")
    suffix = "</Payload></ResilienceHarness>"
    return prefix + "x" * max(0, size - len(prefix) - len(suffix)) + suffix


def inspect_state(container: str) -> dict:
    result = command(["docker", "inspect", "--format", "{{json .State}}", container])
    return json.loads(result.stdout)


def sample(container: str, port: int, username: str, password: str) -> dict:
    state = inspect_state(container)
    restart_count = command(["docker", "inspect", "--format", "{{.RestartCount}}", container])
    stats = command(["docker", "stats", "--no-stream", "--format", "{{json .}}", container],
                    timeout=30, check=False)
    if stats.returncode or not stats.stdout.strip():
        raise RuntimeError("Docker resource sample is unavailable")
    storage = command(["docker", "exec", container, "sh", "-c",
                       "for p in /app/data/mockdb.sqlite /app/data/mockdb.sqlite-wal; do "
                       "if [ -f \"$p\" ]; then stat -c %s \"$p\"; else echo 0; fi; done"],
                      timeout=15)
    db_bytes, wal_bytes = (int(value) for value in storage.stdout.splitlines())
    status, _ = request(port, username, password, "GET", "/api/admin/status", timeout=5)
    return {"at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
            "running": bool(state.get("Running")), "oom_killed": bool(state.get("OOMKilled")),
            "restart_count": int(restart_count.stdout.strip()), "health_http": status,
            "db_bytes": db_bytes, "wal_bytes": wal_bytes,
            "docker_stats": json.loads(stats.stdout)}


def memory_trend(samples_file: Path) -> dict:
    readings = []
    for line in samples_file.read_text().splitlines():
        sample_data = json.loads(line)
        percentage = sample_data["docker_stats"]["MemPerc"].rstrip("%")
        readings.append(float(percentage))
    if not readings:
        return {"samples": 0, "assessment": "unavailable"}
    edge = max(1, len(readings) // 10)
    return {"samples": len(readings), "first_decile_median_percent": round(statistics.median(readings[:edge]), 2),
            "last_decile_median_percent": round(statistics.median(readings[-edge:]), 2),
            "peak_percent": round(max(readings), 2),
            "assessment": "review trend with GC log; container memory includes cache, not just Java heap"}


def check_database(container: str, run_id: str, accepted: set[str]) -> dict:
    # Copy only run-owned synthetic data, after the container has stopped and
    # SQLite has closed its connections. The copy is discarded after inspection.
    with tempfile.TemporaryDirectory(prefix="echo-endurance-audit-") as directory:
        target = Path(directory)
        for suffix in ("", "-wal", "-shm"):
            name = "mockdb.sqlite" + suffix
            command(["docker", "cp", f"{container}:/app/data/{name}", str(target / name)],
                    check=(suffix == ""))
        with sqlite3.connect(f"file:{target / 'mockdb.sqlite'}?mode=ro", uri=True) as db:
            integrity = [row[0] for row in db.execute("PRAGMA integrity_check")]
            foreign_keys = db.execute("PRAGMA foreign_key_check").fetchall()
            seen: set[str] = set()
            for (body,) in db.execute("SELECT request_body FROM request_log WHERE request_body IS NOT NULL"):
                if body:
                    seen.update(ID.findall(body))
            rules = db.execute("SELECT COUNT(*) FROM http_rules WHERE description LIKE ?",
                               (f"%{run_id}%",)).fetchone()[0]
            jms_rules = db.execute("SELECT COUNT(*) FROM jms_rules WHERE description LIKE ?",
                                   (f"%{run_id}%",)).fetchone()[0]
    missing = sorted(accepted - seen)
    return {"integrity": integrity, "foreign_key_errors": len(foreign_keys),
            "accepted": len(accepted), "seen": len(accepted & seen),
            "missing_count": len(missing), "missing_examples": missing[:10],
            "http_rules": rules, "jms_rules": jms_rules}


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--duration-seconds", type=int, default=8 * 3600)
    p.add_argument("--requests-per-minute", type=int, default=30,
                   help="total HTTP + JMS rate; alternating protocols")
    p.add_argument("--sample-seconds", type=int, default=60)
    p.add_argument("--large-every", type=int, default=50,
                   help="use a 128 KiB XML body every N requests")
    p.add_argument("--heap", default="512m")
    p.add_argument("--container-memory", default="1024m")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--output-root", type=Path, default=ARTIFACTS)
    return p


def main() -> int:
    args = parser().parse_args()
    if not (10 <= args.duration_seconds <= 48 * 3600):
        raise ValueError("duration must be between 10 seconds and 48 hours")
    if not (1 <= args.requests_per_minute <= 600):
        raise ValueError("requests per minute must be 1..600")
    if not (1 <= args.sample_seconds <= 3600) or args.large_every < 1:
        raise ValueError("sample-seconds and large-every must be positive")
    if not MEMORY.fullmatch(args.heap) or not MEMORY.fullmatch(args.container_memory):
        raise ValueError("heap and container-memory must be positive sizes like 512m or 1g")
    if memory_bytes(args.heap) >= memory_bytes(args.container_memory):
        raise ValueError("container-memory must exceed the JVM heap for native/runtime overhead")
    expected_requests = math.ceil(args.duration_seconds * args.requests_per_minute / 60)
    if expected_requests > 100_000:
        raise ValueError("at most 100000 requests per run; lower the rate or duration")
    if args.dry_run:
        print(json.dumps({"duration_seconds": args.duration_seconds,
                          "requests_per_minute": args.requests_per_minute,
                          "sample_seconds": args.sample_seconds,
                          "heap": args.heap, "container_memory": args.container_memory,
                          "database": "unique Docker named volume; no SIT/repository mounts",
                          "backup": "disabled in the test container",
                          "cleanup": "only the generated Compose project and image"}, indent=2))
        return 0

    if not shutil.which("docker"):
        raise ValueError("Docker CLI is unavailable")
    daemon = command(["docker", "info", "--format", "{{.ServerVersion}}"], check=False)
    if daemon.returncode:
        raise ValueError("Docker daemon is unavailable")

    run_id = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d-%H%M%S") + "-" + secrets.token_hex(4)
    project = "echo-endurance-" + run_id.lower()
    image = "echo-mock-server:endurance-" + run_id.lower()
    username, password = "endurance-admin", secrets.token_urlsafe(24)
    port, jms_port = free_port(), free_port()
    while jms_port == port:
        jms_port = free_port()
    env = {**os.environ, "ECHO_IMAGE": image, "ECHO_ADMIN_USERNAME": username,
           "ECHO_ADMIN_PASSWORD": password, "ECHO_REMEMBER_ME_KEY": secrets.token_urlsafe(32),
           "ECHO_RESILIENCE_HTTP_PORT": str(port), "ECHO_RESILIENCE_JMS_PORT": str(jms_port),
           "ECHO_RESILIENCE_HEAP": args.heap,
           "ECHO_RESILIENCE_CONTAINER_MEMORY": args.container_memory,
           "ECHO_RESILIENCE_QUEUE": "ECHO.RESILIENCE",
           "ECHO_RESILIENCE_MAX_RECORDS": str(max(20_000, expected_requests + 100))}
    output = args.output_root.resolve() / run_id
    output.mkdir(parents=True, exist_ok=False)
    result: dict = {"run_id": run_id, "project": project, "started_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                    "duration_seconds": args.duration_seconds, "requests_per_minute": args.requests_per_minute,
                    "heap": args.heap, "container_memory": args.container_memory,
                    "accepted": 0, "failed_requests": 0, "samples": 0, "passed": False}
    accepted: set[str] = set()
    container = ""
    project_created = False
    try:
        jar = command([str(ROOT / "gradlew"), "bootJar", "--console=plain"], timeout=900)
        (output / "build.log").write_text(jar.stdout + jar.stderr)
        existing = command(["docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={project}"])
        if existing.stdout.strip():
            raise RuntimeError("generated Compose project name already exists")
        project_created = True
        started = compose(project, env, "up", "-d", "--build", "--no-deps", "echo-resilience", timeout=900)
        (output / "compose-up.log").write_text(started.stdout + started.stderr)
        container = compose(project, env, "ps", "-q", "echo-resilience").stdout.strip()
        if not container:
            raise RuntimeError("Compose did not return the Echo container ID")
        wait_ready(port, username, password)
        http_path = f"/endurance/{run_id}"
        http_rule = create_rule(port, username, password, {
            "protocol": "HTTP", "matchKey": http_path, "method": "POST",
            "responseBody": json.dumps({"run": run_id}), "status": 200,
            "description": f"container-endurance-{run_id}-http"})
        jms_rule = create_rule(port, username, password, {
            "protocol": "JMS", "matchKey": "ECHO.RESILIENCE",
            "bodyCondition": "//ServiceName=ResilienceHarness",
            "responseBody": "<endurance-ok/>", "delayMs": 0, "priority": 100,
            "enabled": True, "description": f"container-endurance-{run_id}-jms"})
        result["rule_ids"] = [http_rule, jms_rule]
        deadline = time.monotonic() + args.duration_seconds
        period = 60 / args.requests_per_minute
        next_send = time.monotonic()
        next_sample = next_send
        count = 0
        with (output / "samples.jsonl").open("w") as samples, \
                (output / "requests.jsonl").open("w") as requests:
            while time.monotonic() < deadline:
                now = time.monotonic()
                if now >= next_sample:
                    observed = sample(container, port, username, password)
                    samples.write(json.dumps(observed) + "\n")
                    samples.flush()
                    result["samples"] += 1
                    if not observed["running"] or observed["oom_killed"] or observed["health_http"] != 200:
                        raise RuntimeError("Echo is unhealthy during the endurance run")
                    next_sample = now + args.sample_seconds
                if now < next_send:
                    time.sleep(min(0.5, next_send - now, max(0, deadline - now)))
                    continue
                count += 1
                message_id = f"{run_id}-{count:08d}"
                size = 128 * 1024 if count % args.large_every == 0 else 8 * 1024
                body = xml_body(message_id, size)
                if count % 2:
                    status, response = request(port, username, password, "POST", "/mock" + http_path, body)
                    ok = status == 200 and run_id in str(response)
                    protocol = "HTTP"
                else:
                    status, response = request(port, username, password, "POST", "/api/admin/jms/test", body)
                    ok = status == 200 and isinstance(response, dict) and response.get("sent") is True
                    protocol = "JMS"
                requests.write(json.dumps({"id": message_id, "protocol": protocol,
                                           "status": status, "accepted": ok}) + "\n")
                requests.flush()
                if ok:
                    accepted.add(message_id)
                    result["accepted"] += 1
                else:
                    result["failed_requests"] += 1
                    raise RuntimeError(f"{protocol} request failed with HTTP {status}")
                next_send = max(next_send + period, time.monotonic())

        # Allow async request-log writes to drain before closing SQLite.
        time.sleep(15)
        captured_gc_logs = []
        for suffix in ("", ".0", ".1", ".2", ".3", ".4", ".5"):
            name = "gc.log" + suffix
            copied = command(["docker", "cp", f"{container}:/tmp/{name}", str(output / name)],
                             check=False)
            if copied.returncode == 0:
                captured_gc_logs.append(name)
        result["gc_logs_captured"] = captured_gc_logs
        if not captured_gc_logs:
            raise RuntimeError("JVM GC log could not be captured")
        result["memory_trend"] = memory_trend(output / "samples.jsonl")
        compose(project, env, "stop", "-t", "60", "echo-resilience", timeout=90)
        result["database"] = check_database(container, run_id, accepted)
        if result["database"]["integrity"] != ["ok"] or result["database"]["foreign_key_errors"]:
            raise RuntimeError("SQLite integrity or foreign-key check failed")
        if result["database"]["missing_count"] or result["database"]["http_rules"] != 1 \
                or result["database"]["jms_rules"] != 1:
            raise RuntimeError("accepted request logs or sentinel rules are missing")
        compose(project, env, "start", "echo-resilience", timeout=120)
        wait_ready(port, username, password)
        for rule_id in result["rule_ids"]:
            status, body = request(port, username, password, "GET", f"/api/admin/rules/{rule_id}")
            if status != 200 or not isinstance(body, dict) or body.get("id") != rule_id:
                raise RuntimeError("sentinel rule was not readable after restart")
        status, _ = request(port, username, password, "POST", "/mock" + http_path,
                            xml_body(f"{run_id}-after-restart", 1024))
        if status != 200:
            raise RuntimeError("SQLite-backed rule did not respond after restart")
        result["passed"] = True
    except (Exception, KeyboardInterrupt) as error:
        result["error"] = str(error) or type(error).__name__
    finally:
        if container:
            try:
                logs = command(["docker", "logs", container], check=False, timeout=30)
                (output / "echo.log").write_text(logs.stdout + logs.stderr)
                result["final_container_state"] = inspect_state(container)
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
                result["log_or_state_capture_error"] = str(error)
        if project_created:
            try:
                down = compose(project, env, "down", "--volumes", "--remove-orphans",
                               timeout=180, check=False)
                result["cleanup_exit_code"] = down.returncode
                (output / "compose-down.log").write_text(down.stdout + down.stderr)
                remaining = command(["docker", "ps", "-aq", "--filter",
                                     f"label=com.docker.compose.project={project}"], check=False)
                result["remaining_containers"] = remaining.stdout.splitlines()
                volumes = command(["docker", "volume", "ls", "-q", "--filter",
                                   f"label=com.docker.compose.project={project}"], check=False)
                result["remaining_volumes"] = volumes.stdout.splitlines()
                result["passed"] = result["passed"] and down.returncode == 0 \
                    and not result["remaining_containers"] and not result["remaining_volumes"]
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
                result["cleanup_error"] = str(error)
                result["passed"] = False
        try:
            image_remove = command(["docker", "image", "rm", image], check=False)
            result["image_cleanup_exit_code"] = image_remove.returncode
            result["passed"] = result["passed"] and image_remove.returncode == 0
        except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
            result["image_cleanup_error"] = str(error)
            result["passed"] = False
        result["finished_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
        (output / "result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
        print(json.dumps({"passed": result["passed"], "accepted": result["accepted"],
                          "error": result.get("error"), "result": str(output / "result.json")},
                         ensure_ascii=False))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ValueError as error:
        print(f"configuration error: {error}", file=sys.stderr)
        sys.exit(2)
