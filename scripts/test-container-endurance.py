#!/usr/bin/env python3
"""Run a bounded, disposable SQLite endurance test in Docker Compose.

The eight-hour default is a duration, not a message count. This script never
mounts a host or repository database or backup directory. It owns a uniquely named
Compose project and removes only that project's containers and volumes.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import datetime as dt
import hashlib
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
JSON_ID = re.compile(r'"messageId"\s*:\s*"([^"]+)"')
SCHEDULE_WINDOW_SECONDS = 5 * 60


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


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def request(port: int, username: str, password: str, method: str, path: str,
            payload: dict | str | None = None, timeout: int = 20,
            content_type: str | None = None) -> tuple[int, object]:
    body = None
    headers = {"Accept": "application/json"}
    token = base64.b64encode(f"{username}:{password}".encode()).decode()
    headers["Authorization"] = f"Basic {token}"
    if isinstance(payload, dict):
        body = json.dumps(payload, separators=(",", ":")).encode()
        headers["Content-Type"] = "application/json"
    elif isinstance(payload, str):
        body = payload.encode()
        headers["Content-Type"] = content_type or "application/xml; charset=utf-8"
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


def json_body(message_id: str, size: int) -> str:
    prefix = '{"messageId":' + json.dumps(message_id) + ',"payload":"'
    suffix = '"}'
    return prefix + "x" * max(0, size - len(prefix.encode()) - len(suffix.encode())) + suffix


def large_xml_response(run_id: str, size: int, *, templated: bool) -> str:
    mode = "xml-template-large" if templated else "static-large-xml"
    message = ("{{xPath request.body '//HarnessMessageId/text()'}}"
               if templated else "plain")
    prefix = (f"<result><run>{run_id}</run><mode>{mode}</mode>"
              f"<messageId>{message}</messageId><payload>")
    suffix = "</payload></result>"
    if len(prefix.encode()) + len(suffix.encode()) > size:
        raise ValueError("large XML response size is too small for its envelope")
    return prefix + "x" * (size - len(prefix.encode()) - len(suffix.encode())) + suffix


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
    status, status_body = request(port, username, password, "GET", "/api/admin/status", timeout=5)
    agent_status, agents = request(port, username, password, "GET", "/api/admin/agents", timeout=5)
    return {"at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
            "running": bool(state.get("Running")), "oom_killed": bool(state.get("OOMKilled")),
            "restart_count": int(restart_count.stdout.strip()), "health_http": status,
            "db_bytes": db_bytes, "wal_bytes": wal_bytes,
            "docker_stats": json.loads(stats.stdout),
            "application": status_body if isinstance(status_body, dict) else {},
            "agents": agents if agent_status == 200 and isinstance(agents, list) else []}


def percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * quantile) - 1))
    return round(ordered[index], 2)


def schedule_window_results(expected: dict[int, int], misses: dict[int, int],
                            minimum_percent: float) -> list[dict]:
    results = []
    for window in sorted(expected):
        expected_slots = expected[window]
        missed_slots = misses.get(window, 0)
        attained = 100.0 * (expected_slots - missed_slots) / max(1, expected_slots)
        results.append({"window": window, "expected": expected_slots,
                        "misses": missed_slots, "attainment_percent": round(attained, 4),
                        "passed": attained >= minimum_percent})
    return results


def hourly_summary(hour: int, records: list[dict], samples: list[dict],
                   expected_slots: int, schedule_misses: int, in_flight: int = 0,
                   interval_seconds: int = 3600) -> dict:
    latencies = [float(record["latency_ms"]) for record in records if record.get("accepted")]
    completed = len(records)
    accepted = sum(1 for record in records if record.get("accepted"))
    response_bytes = sum(int(record.get("response_bytes", 0)) for record in records)
    latest = samples[-1] if samples else {}
    return {
        "hour": hour,
        "expected_slots": expected_slots,
        "completed": completed,
        "accepted": accepted,
        "failed": completed - accepted,
        "schedule_misses": schedule_misses,
        "in_flight": in_flight,
        "completed_tps": round(completed / max(1, interval_seconds), 4),
        "response_bytes": response_bytes,
        "response_mib_per_second": round(
            response_bytes / (1024 * 1024) / max(1, interval_seconds), 4),
        "p95_ms": percentile(latencies, 0.95),
        "p99_ms": percentile(latencies, 0.99),
        "latest_sample": latest,
    }


def write_hourly_report(output: Path, summary: dict) -> None:
    hour = int(summary["hour"])
    stem = output / f"hour-{hour:02d}"
    stem.with_suffix(".json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n")
    latest = summary.get("latest_sample") or {}
    docker_stats = latest.get("docker_stats") or {}
    application = latest.get("application") or {}
    lines = [
        f"# Endurance hour {hour}",
        "",
        f"- Expected slots: {summary['expected_slots']}",
        f"- Completed: {summary['completed']}",
        f"- Accepted: {summary['accepted']}",
        f"- Failed: {summary['failed']}",
        f"- Schedule misses: {summary['schedule_misses']}",
        f"- In flight at report time: {summary['in_flight']}",
        f"- Completed TPS: {summary['completed_tps']}",
        f"- Response throughput: {summary['response_mib_per_second']} MiB/s",
        f"- p95 / p99: {summary['p95_ms']} ms / {summary['p99_ms']} ms",
        f"- Heap used / max: {application.get('jvmHeapUsed', 'n/a')} / {application.get('jvmHeapMax', 'n/a')}",
        f"- Container memory: {docker_stats.get('MemUsage', 'n/a')} ({docker_stats.get('MemPerc', 'n/a')})",
        f"- DB / WAL bytes: {latest.get('db_bytes', 'n/a')} / {latest.get('wal_bytes', 'n/a')}",
        "",
        "This is an operational checkpoint. Final pass/fail is decided after drain, database audit, and restart validation.",
    ]
    stem.with_suffix(".md").write_text("\n".join(lines) + "\n")


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


def execute_load_request(index: int, run_id: str, scheduled_at: float,
                         port: int, username: str, password: str,
                         paths: dict[str, str], large_every: int,
                         scheduled_iso: str | None = None) -> dict:
    started = time.monotonic()
    started_iso = dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds")
    message_id = f"{run_id}-{index:08d}"
    size = 128 * 1024 if index % large_every == 0 else 8 * 1024
    mode = ("static", "json-template", "xml-template", "jms")[(index - 1) % 4]
    if mode == "json-template":
        body = json_body(message_id, size)
        status, response = request(port, username, password, "POST", paths[mode], body,
                                   content_type="application/json; charset=utf-8")
        accepted = status == 200 and message_id in str(response)
    elif mode == "xml-template":
        body = xml_body(message_id, size)
        status, response = request(port, username, password, "POST", paths[mode], body)
        accepted = status == 200 and message_id in str(response)
    elif mode == "static":
        body = xml_body(message_id, size)
        status, response = request(port, username, password, "POST", paths[mode], body)
        accepted = status == 200 and run_id in str(response) and "static" in str(response)
    else:
        body = xml_body(message_id, size)
        status, response = request(port, username, password, "POST", "/api/admin/jms/test", body)
        accepted = status == 200 and isinstance(response, dict) and response.get("sent") is True
    finished = time.monotonic()
    response_bytes = (len(response.encode("utf-8"))
                      if isinstance(response, str)
                      else len(json.dumps(response, separators=(",", ":")).encode("utf-8")))
    return {
        "id": message_id,
        "mode": mode,
        "status": status,
        "accepted": accepted,
        "scheduled_at": scheduled_iso,
        "started_at": started_iso,
        "scheduled_lag_ms": round(max(0.0, started - scheduled_at) * 1000, 2),
        "latency_ms": round((finished - started) * 1000, 2),
        "response_bytes": response_bytes,
        "completed_at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds"),
    }


def check_database(container: str, run_id: str, accepted: set[str], max_records: int) -> dict:
    # Copy only run-owned synthetic data, after the container has stopped and
    # SQLite has closed its connections. The copy is discarded after inspection.
    with tempfile.TemporaryDirectory(prefix="echo-endurance-audit-") as directory:
        target = Path(directory)
        for suffix in ("", "-wal", "-shm"):
            name = "mockdb.sqlite" + suffix
            command(["docker", "cp", f"{container}:/app/data/{name}", str(target / name)],
                    check=(suffix == ""))
        command(["docker", "cp", f"{container}:/app/data/request-log-spool.sqlite",
                 str(target / "request-log-spool.sqlite")])
        with sqlite3.connect(f"file:{target / 'mockdb.sqlite'}?mode=ro", uri=True) as db:
            integrity = [row[0] for row in db.execute("PRAGMA integrity_check")]
            foreign_keys = db.execute("PRAGMA foreign_key_check").fetchall()
            seen: set[str] = set()
            for (body,) in db.execute("SELECT request_body FROM request_log WHERE request_body IS NOT NULL"):
                if body:
                    seen.update(ID.findall(body))
                    seen.update(JSON_ID.findall(body))
            rules = db.execute("SELECT COUNT(*) FROM http_rules WHERE description LIKE ?",
                               (f"%{run_id}%",)).fetchone()[0]
            jms_rules = db.execute("SELECT COUNT(*) FROM jms_rules WHERE description LIKE ?",
                                   (f"%{run_id}%",)).fetchone()[0]
            checkpoints = dict(db.execute(
                "SELECT spool_id, last_sequence FROM request_log_checkpoint").fetchall())
        with sqlite3.connect(f"file:{target / 'request-log-spool.sqlite'}?mode=ro", uri=True) as spool:
            spool_id_row = spool.execute(
                "SELECT metadata_value FROM spool_metadata WHERE metadata_key = 'spool_id'").fetchone()
            spool_id = spool_id_row[0] if spool_id_row else None
            checkpoint = int(checkpoints.get(spool_id, 0)) if spool_id else 0
            pending_after_checkpoint = spool.execute(
                "SELECT COUNT(*) FROM request_log_spool WHERE sequence_id > ?", (checkpoint,)).fetchone()[0]
            spool_max_sequence = spool.execute(
                "SELECT COALESCE(MAX(sequence_id), 0) FROM request_log_spool").fetchone()[0]
    retained_floor = min(len(accepted), max(1, max_records - min(1_000, max_records // 10)))
    unknown = sorted(seen - accepted)
    return {"integrity": integrity, "foreign_key_errors": len(foreign_keys),
            "accepted": len(accepted), "retained": len(seen), "retained_floor": retained_floor,
            "unknown_count": len(unknown), "unknown_examples": unknown[:10],
            "spool_id": spool_id, "checkpoint": checkpoint,
            "spool_max_sequence": spool_max_sequence,
            "pending_after_checkpoint": pending_after_checkpoint,
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
    p.add_argument("--max-requests", type=int, default=100_000,
                   help="run safety cap; absolute maximum is 400000")
    p.add_argument("--max-in-flight", type=int, default=64,
                   help="bounded HTTP/JMS client concurrency")
    p.add_argument("--report-seconds", type=int, default=3600,
                   help="checkpoint report interval; production endurance uses one hour")
    p.add_argument("--uat-24h", action="store_true",
                   help="use 24h, 4 TPS, Xmx3g, 6 GiB container, 400000-request cap")
    p.add_argument("--uat-2h-40tps", action="store_true",
                   help="use 2h, 40 TPS, Xmx3g, 6 GiB container, 300000-request cap")
    p.add_argument("--sqlite-large-xml", action="store_true",
                   help="use 3h, 4 TPS, Xmx3g, 6 GiB, and 5 MiB plain/template XML responses")
    p.add_argument("--large-xml-response-mib", type=int, default=0,
                   help="make the static and XML-template responses this many MiB")
    p.add_argument("--min-schedule-attainment-percent", type=float, default=99.0,
                   help="minimum submitted-slot percentage in every five-minute window")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--output-root", type=Path, default=ARTIFACTS)
    return p


def main() -> int:
    args = parser().parse_args()
    selected_profiles = sum((args.uat_24h, args.uat_2h_40tps, args.sqlite_large_xml))
    if selected_profiles > 1:
        raise ValueError("choose only one endurance profile")
    if args.uat_24h:
        args.duration_seconds = 24 * 3600
        args.requests_per_minute = 240
        args.sample_seconds = 60
        args.report_seconds = 3600
        args.heap = "3g"
        args.container_memory = "6g"
        args.max_requests = 400_000
    elif args.uat_2h_40tps:
        args.duration_seconds = 2 * 3600
        args.requests_per_minute = 2_400
        args.sample_seconds = 60
        args.report_seconds = 3600
        args.heap = "3g"
        args.container_memory = "6g"
        args.max_requests = 300_000
    elif args.sqlite_large_xml:
        args.duration_seconds = 3 * 3600
        args.requests_per_minute = 240
        args.sample_seconds = 60
        args.report_seconds = 3600
        args.heap = "3g"
        args.container_memory = "6g"
        args.max_requests = 50_000
        args.large_xml_response_mib = 5
    if not (10 <= args.duration_seconds <= 48 * 3600):
        raise ValueError("duration must be between 10 seconds and 48 hours")
    if not (1 <= args.requests_per_minute <= 2_400):
        raise ValueError("requests per minute must be 1..2400")
    if not (1 <= args.sample_seconds <= 3600) or args.large_every < 1:
        raise ValueError("sample-seconds and large-every must be positive")
    if not (1 <= args.report_seconds <= 24 * 3600) or not (1 <= args.max_in_flight <= 512):
        raise ValueError("report-seconds and max-in-flight are outside their safe bounds")
    if not (0 <= args.large_xml_response_mib <= 8):
        raise ValueError("large-xml-response-mib must be 0..8")
    if not (90.0 <= args.min_schedule_attainment_percent <= 100.0):
        raise ValueError("min-schedule-attainment-percent must be 90..100")
    if not MEMORY.fullmatch(args.heap) or not MEMORY.fullmatch(args.container_memory):
        raise ValueError("heap and container-memory must be positive sizes like 512m or 1g")
    if memory_bytes(args.heap) >= memory_bytes(args.container_memory):
        raise ValueError("container-memory must exceed the JVM heap for native/runtime overhead")
    expected_requests = math.ceil(args.duration_seconds * args.requests_per_minute / 60)
    if not (1 <= args.max_requests <= 400_000) or expected_requests > args.max_requests:
        raise ValueError("expected requests exceed the configured safety cap (absolute maximum 400000)")
    if args.dry_run:
        print(json.dumps({"duration_seconds": args.duration_seconds,
                          "requests_per_minute": args.requests_per_minute,
                          "sample_seconds": args.sample_seconds,
                          "report_seconds": args.report_seconds,
                          "expected_requests": expected_requests,
                          "max_requests": args.max_requests,
                          "max_in_flight": args.max_in_flight,
                          "large_xml_response_mib": args.large_xml_response_mib,
                          "min_schedule_attainment_percent": args.min_schedule_attainment_percent,
                          "schedule_window_seconds": SCHEDULE_WINDOW_SECONDS,
                          "heap": args.heap, "container_memory": args.container_memory,
                          "database": "unique Docker named volume; no host/repository mounts",
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
           "ECHO_RESILIENCE_MAX_RECORDS": "10000"}
    output = args.output_root.resolve() / run_id
    output.mkdir(parents=True, exist_ok=False)
    result: dict = {"run_id": run_id, "project": project, "started_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                    "duration_seconds": args.duration_seconds, "requests_per_minute": args.requests_per_minute,
                    "heap": args.heap, "container_memory": args.container_memory,
                    "expected_requests": expected_requests, "max_in_flight": args.max_in_flight,
                    "large_xml_response_mib": args.large_xml_response_mib,
                    "min_schedule_attainment_percent": args.min_schedule_attainment_percent,
                    "schedule_window_seconds": SCHEDULE_WINDOW_SECONDS,
                    "request_log_max_records": 10_000,
                    "accepted": 0, "failed_requests": 0, "schedule_misses": 0,
                    "samples": 0, "hourly_reports": 0, "passed": False}
    accepted: set[str] = set()
    container = ""
    project_created = False
    try:
        jar = command([str(ROOT / "gradlew"), "bootJar", "--console=plain"], timeout=900)
        (output / "build.log").write_text(jar.stdout + jar.stderr)
        jar_candidates = [path for path in (ROOT / "build" / "libs").glob("echo-server-*.jar")
                          if not path.name.endswith("-plain.jar")]
        if not jar_candidates:
            raise RuntimeError("bootJar completed but no Echo executable JAR was found")
        built_jar = max(jar_candidates, key=lambda path: path.stat().st_mtime_ns)
        result["source_commit"] = command(["git", "rev-parse", "HEAD"]).stdout.strip()
        result["source_dirty"] = bool(command(["git", "status", "--porcelain"]).stdout.strip())
        result["jar"] = {"name": built_jar.name, "sha256": sha256_file(built_jar)}
        existing = command(["docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={project}"])
        if existing.stdout.strip():
            raise RuntimeError("generated Compose project name already exists")
        project_created = True
        started = compose(project, env, "up", "-d", "--build", "--no-deps", "echo-resilience", timeout=900)
        (output / "compose-up.log").write_text(started.stdout + started.stderr)
        container = compose(project, env, "ps", "-q", "echo-resilience").stdout.strip()
        if not container:
            raise RuntimeError("Compose did not return the Echo container ID")
        image_id = command(["docker", "image", "inspect", "--format", "{{.Id}}", image])
        result["image_id"] = image_id.stdout.strip()
        runtime = command(["docker", "exec", container, "java", "-version"], check=False)
        (output / "runtime.txt").write_text(runtime.stdout + runtime.stderr)
        wait_ready(port, username, password)
        paths = {
            "static": f"/mock/endurance/{run_id}/static",
            "json-template": f"/mock/endurance/{run_id}/json-template",
            "xml-template": f"/mock/endurance/{run_id}/xml-template",
        }
        response_size = args.large_xml_response_mib * 1024 * 1024
        static_response = (large_xml_response(run_id, response_size, templated=False)
                           if response_size else json.dumps({"run": run_id, "mode": "static"}))
        xml_template_response = (
            large_xml_response(run_id, response_size, templated=True)
            if response_size else
            "<result><mode>xml-template</mode><messageId>"
            "{{xPath request.body '//HarnessMessageId/text()'}}"
            "</messageId></result>")
        static_rule = create_rule(port, username, password, {
            "protocol": "HTTP", "matchKey": paths["static"].removeprefix("/mock"), "method": "POST",
            "responseBody": static_response, "status": 200,
            "description": f"container-endurance-{run_id}-static"})
        json_rule = create_rule(port, username, password, {
            "protocol": "HTTP", "matchKey": paths["json-template"].removeprefix("/mock"), "method": "POST",
            "responseBody": '{"mode":"json-template","messageId":"{{jsonPath request.body \'$.messageId\'}}"}',
            "status": 200, "description": f"container-endurance-{run_id}-json-template"})
        xml_rule = create_rule(port, username, password, {
            "protocol": "HTTP", "matchKey": paths["xml-template"].removeprefix("/mock"), "method": "POST",
            "responseBody": xml_template_response,
            "status": 200, "description": f"container-endurance-{run_id}-xml-template"})
        jms_rule = create_rule(port, username, password, {
            "protocol": "JMS", "matchKey": "ECHO.RESILIENCE",
            "bodyCondition": "//ServiceName=ResilienceHarness",
            "responseBody": "<endurance-ok/>", "delayMs": 0, "priority": 100,
            "enabled": True, "description": f"container-endurance-{run_id}-jms"})
        result["rule_ids"] = [static_rule, json_rule, xml_rule, jms_rule]
        run_started = time.monotonic()
        run_wall_started = dt.datetime.now(dt.timezone.utc)
        deadline = run_started + args.duration_seconds
        period = 60 / args.requests_per_minute
        next_send = run_started
        next_sample = next_send
        next_report = run_started + args.report_seconds
        slot_count = 0
        pending: dict[concurrent.futures.Future, int] = {}
        records_by_hour: dict[int, list[dict]] = {}
        samples_by_hour: dict[int, list[dict]] = {}
        expected_by_hour: dict[int, int] = {}
        misses_by_hour: dict[int, int] = {}
        expected_by_window: dict[int, int] = {}
        misses_by_window: dict[int, int] = {}

        with (output / "samples.jsonl").open("w") as samples, \
                (output / "requests.jsonl").open("w") as requests, \
                concurrent.futures.ThreadPoolExecutor(max_workers=args.max_in_flight,
                                                       thread_name_prefix="endurance-load") as load_pool, \
                concurrent.futures.ThreadPoolExecutor(max_workers=1,
                                                       thread_name_prefix="endurance-sampler") as sample_pool:
            sample_future: concurrent.futures.Future | None = None
            sample_hour = 1

            def collect_requests(*, wait: bool = False) -> None:
                if not pending:
                    return
                if wait:
                    done, _ = concurrent.futures.wait(pending)
                else:
                    done = {future for future in pending if future.done()}
                for future in done:
                    hour = pending.pop(future)
                    record = future.result()
                    records_by_hour.setdefault(hour, []).append({
                        "accepted": record["accepted"], "latency_ms": record["latency_ms"],
                        "response_bytes": record["response_bytes"]})
                    requests.write(json.dumps(record) + "\n")
                    if record["accepted"]:
                        accepted.add(record["id"])
                        result["accepted"] += 1
                    else:
                        result["failed_requests"] += 1
                if done:
                    requests.flush()

            def collect_sample(*, wait: bool = False) -> None:
                nonlocal sample_future
                if sample_future is None or (not wait and not sample_future.done()):
                    return
                observed = sample_future.result()
                samples.write(json.dumps(observed) + "\n")
                samples.flush()
                samples_by_hour.setdefault(sample_hour, []).append(observed)
                result["samples"] += 1
                sample_future = None
                if not observed["running"] or observed["oom_killed"] \
                        or observed["health_http"] != 200:
                    raise RuntimeError("Echo is unhealthy during the endurance run")

            while time.monotonic() < deadline:
                now = time.monotonic()
                collect_requests()
                collect_sample()
                if result["failed_requests"]:
                    raise RuntimeError("one or more endurance requests failed")

                if now >= next_sample:
                    if sample_future is None:
                        sample_hour = int((now - run_started) // args.report_seconds) + 1
                        sample_future = sample_pool.submit(sample, container, port, username, password)
                    next_sample += args.sample_seconds

                while now - next_send >= period and next_send < deadline:
                    slot_count += 1
                    missed_hour = int((next_send - run_started) // args.report_seconds) + 1
                    missed_window = int((next_send - run_started) // SCHEDULE_WINDOW_SECONDS) + 1
                    expected_by_hour[missed_hour] = expected_by_hour.get(missed_hour, 0) + 1
                    misses_by_hour[missed_hour] = misses_by_hour.get(missed_hour, 0) + 1
                    expected_by_window[missed_window] = expected_by_window.get(missed_window, 0) + 1
                    misses_by_window[missed_window] = misses_by_window.get(missed_window, 0) + 1
                    result["schedule_misses"] += 1
                    next_send += period

                if now >= next_send and next_send < deadline:
                    slot_count += 1
                    hour = int((next_send - run_started) // args.report_seconds) + 1
                    window = int((next_send - run_started) // SCHEDULE_WINDOW_SECONDS) + 1
                    expected_by_hour[hour] = expected_by_hour.get(hour, 0) + 1
                    expected_by_window[window] = expected_by_window.get(window, 0) + 1
                    if len(pending) >= args.max_in_flight:
                        misses_by_hour[hour] = misses_by_hour.get(hour, 0) + 1
                        misses_by_window[window] = misses_by_window.get(window, 0) + 1
                        result["schedule_misses"] += 1
                    else:
                        scheduled_iso = (run_wall_started + dt.timedelta(
                            seconds=next_send - run_started)).isoformat(timespec="milliseconds")
                        future = load_pool.submit(
                            execute_load_request, slot_count, run_id, next_send,
                            port, username, password, paths, args.large_every, scheduled_iso)
                        pending[future] = hour
                    next_send += period

                while now >= next_report:
                    report_hour = int((next_report - run_started) // args.report_seconds)
                    report_samples = [sample_value for hour_values in samples_by_hour.values()
                                      for sample_value in hour_values]
                    summary = hourly_summary(
                        report_hour, records_by_hour.get(report_hour, []), report_samples,
                        expected_by_hour.get(report_hour, 0), misses_by_hour.get(report_hour, 0),
                        sum(1 for hour in pending.values() if hour == report_hour),
                        args.report_seconds)
                    write_hourly_report(output, summary)
                    result["hourly_reports"] = max(result["hourly_reports"], report_hour)
                    next_report += args.report_seconds

                if now < next_send:
                    time.sleep(min(0.05, next_send - now, max(0, deadline - now)))

            collect_requests(wait=True)
            collect_sample(wait=True)
            if result["failed_requests"]:
                raise RuntimeError("one or more endurance requests failed")
            final_hour = max(1, math.ceil(args.duration_seconds / args.report_seconds))
            for report_hour in range(1, final_hour + 1):
                summary = hourly_summary(
                    report_hour, records_by_hour.get(report_hour, []),
                    samples_by_hour.get(report_hour, []),
                    expected_by_hour.get(report_hour, 0), misses_by_hour.get(report_hour, 0),
                    interval_seconds=args.report_seconds)
                write_hourly_report(output, summary)
            result["hourly_reports"] = final_hour
            windows = schedule_window_results(
                expected_by_window, misses_by_window,
                args.min_schedule_attainment_percent)
            result["schedule_windows"] = windows
            result["worst_schedule_attainment_percent"] = min(
                (window["attainment_percent"] for window in windows), default=100.0)
            result["failed_schedule_windows"] = [
                window for window in windows if not window["passed"]]
            if result["failed_schedule_windows"]:
                raise RuntimeError("fixed-arrival schedule fell below the five-minute threshold")

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
        result["database"] = check_database(container, run_id, accepted, 10_000)
        if result["database"]["integrity"] != ["ok"] or result["database"]["foreign_key_errors"]:
            raise RuntimeError("SQLite integrity or foreign-key check failed")
        if result["database"]["retained"] < result["database"]["retained_floor"] \
                or result["database"]["unknown_count"] \
                or result["database"]["pending_after_checkpoint"] \
                or result["database"]["http_rules"] != 3 \
                or result["database"]["jms_rules"] != 1:
            raise RuntimeError("retained request logs, durable checkpoint, or sentinel rules are invalid")
        compose(project, env, "start", "echo-resilience", timeout=120)
        wait_ready(port, username, password)
        for rule_id in result["rule_ids"]:
            status, body = request(port, username, password, "GET", f"/api/admin/rules/{rule_id}")
            if status != 200 or not isinstance(body, dict) or body.get("id") != rule_id:
                raise RuntimeError("sentinel rule was not readable after restart")
        status, _ = request(port, username, password, "POST", paths["static"],
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
