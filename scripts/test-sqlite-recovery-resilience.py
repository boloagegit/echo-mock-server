#!/usr/bin/env python3
"""Disposable black-box SQLite lock, OOM, corruption, and restore validation."""

from __future__ import annotations

import base64
import datetime as dt
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request


PROJECT_ROOT = Path(__file__).resolve().parent.parent
ARTIFACT_ROOT = PROJECT_ROOT / "artifacts" / "sqlite-resilience"
FIXTURE_SOURCE = PROJECT_ROOT / "scripts" / "fixtures" / "OomTriggerAgent.java"
AUTH = base64.b64encode(b"admin:admin").decode("ascii")


class TestFailure(RuntimeError):
    pass


class Server:
    def __init__(self, process: subprocess.Popen, log_file, port: int, log_path: Path):
        self.process = process
        self.log_file = log_file
        self.port = port
        self.log_path = log_path

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def close_log(self) -> None:
        if not self.log_file.closed:
            self.log_file.close()


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def api(base_url: str, method: str, path: str, data=None, timeout: float = 15.0):
    payload = None if data is None else json.dumps(data).encode("utf-8")
    request = urllib.request.Request(base_url + path, data=payload, method=method)
    request.add_header("Accept", "application/json")
    request.add_header("Authorization", f"Basic {AUTH}")
    if payload is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8", errors="replace")
            return response.status, json.loads(body) if body.strip() else None
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace") if error.fp else ""
        try:
            parsed = json.loads(body) if body.strip() else None
        except json.JSONDecodeError:
            parsed = body
        return error.code, parsed
    except (OSError, TimeoutError) as error:
        return 0, str(error)


def wait_ready(server: Server, timeout: float = 30.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if server.process.poll() is not None:
            return False
        status, _ = api(server.base_url, "GET", "/api/admin/status", timeout=2)
        if status == 200:
            return True
        time.sleep(0.25)
    return False


def wait_exit(server: Server, timeout: float = 20.0) -> int | None:
    try:
        code = server.process.wait(timeout=timeout)
        server.close_log()
        return code
    except subprocess.TimeoutExpired:
        return None


def stop_server(server: Server) -> None:
    if server.process.poll() is None:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(server.process.pid), "/T", "/F"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        else:
            try:
                os.killpg(os.getpgid(server.process.pid), signal.SIGTERM)
                server.process.wait(timeout=15)
            except (ProcessLookupError, subprocess.TimeoutExpired):
                try:
                    os.killpg(os.getpgid(server.process.pid), signal.SIGKILL)
                except ProcessLookupError:
                    pass
                server.process.wait(timeout=10)
    server.close_log()


def start_server(jar: Path, fixture: Path, output: Path, label: str, *,
                 recovery_mode: str = "fail-fast", backup_enabled: bool = False,
                 java_options: list[str] | None = None) -> Server:
    port = free_port()
    database = fixture / "mockdb.sqlite"
    spool = fixture / "request-log-spool.sqlite"
    backups = fixture / "backups"
    log_path = output / f"{label}.log"
    log_file = log_path.open("wb")
    db_url = str(database.resolve()).replace("\\", "/")
    command = ["java", *(java_options or []), "-jar", str(jar),
               "--spring.profiles.active=sqlite",
               f"--spring.datasource.url=jdbc:sqlite:{db_url}?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL&foreign_keys=ON",
               f"--server.port={port}",
               "--echo.admin.username=admin", "--echo.admin.password=admin",
               f"--echo.sqlite.recovery.mode={recovery_mode}",
               "--echo.sqlite.recovery.runtime-check-interval-ms=250",
               f"--echo.backup.enabled={'true' if backup_enabled else 'false'}",
               "--echo.backup.on-shutdown=false",
               f"--echo.backup.path={backups}",
               "--echo.cleanup.enabled=false", "--echo.jms.enabled=false",
               "--echo.request-log.enabled=false"]
    options = {"cwd": PROJECT_ROOT, "stdout": log_file, "stderr": subprocess.STDOUT}
    if os.name == "nt":
        options["creationflags"] = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0x00000200)
    else:
        options["start_new_session"] = True
    process = subprocess.Popen(command, **options)
    return Server(process, log_file, port, log_path)


def rule_payload(name: str) -> dict:
    return {"protocol": "HTTP", "matchKey": f"/resilience/{name}", "method": "GET",
            "responseBody": json.dumps({"name": name}), "status": 200,
            "description": name, "sseEnabled": False}


def create_rule(server: Server, name: str) -> str:
    status, body = api(server.base_url, "POST", "/api/admin/rules", rule_payload(name))
    if status != 201 or not isinstance(body, dict) or not body.get("id"):
        raise TestFailure(f"create rule {name} failed with status {status}")
    return str(body["id"])


def require_rule(server: Server, rule_id: str) -> None:
    status, body = api(server.base_url, "GET", f"/api/admin/rules/{rule_id}")
    if status != 200 or not isinstance(body, dict) or body.get("id") != rule_id:
        raise TestFailure(f"rule {rule_id} was not readable after recovery")


def integrity(database: Path) -> bool:
    try:
        with sqlite3.connect(database, timeout=2) as connection:
            row = connection.execute("PRAGMA integrity_check").fetchone()
            foreign_key_error = connection.execute("PRAGMA foreign_key_check").fetchone()
        return bool(row) and row[0] == "ok" and foreign_key_error is None
    except sqlite3.Error:
        return False


def build_oom_agent(work: Path) -> Path:
    classes = work / "agent-classes"
    classes.mkdir()
    subprocess.run(["javac", "-d", str(classes), str(FIXTURE_SOURCE)], check=True,
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    manifest = work / "oom-agent.mf"
    manifest.write_text("Manifest-Version: 1.0\nPremain-Class: com.echo.resilience.OomTriggerAgent\n\n",
                        encoding="utf-8")
    agent = work / "oom-trigger-agent.jar"
    subprocess.run(["jar", "--create", "--file", str(agent), "--manifest", str(manifest),
                    "-C", str(classes), "."], check=True, stdout=subprocess.PIPE,
                   stderr=subprocess.PIPE)
    return agent


def check(checks: list[dict], name: str, passed: bool, detail: str = "") -> None:
    checks.append({"name": name, "status": "passed" if passed else "failed", "detail": detail})
    if not passed:
        raise TestFailure(f"{name}: {detail}")


def scenario_lock(jar: Path, root: Path, output: Path, checks: list[dict]) -> None:
    fixture = root / "lock"
    fixture.mkdir()
    server = start_server(jar, fixture, output, "lock")
    try:
        check(checks, "lock server starts", wait_ready(server))
        database = fixture / "mockdb.sqlite"
        result: dict = {}
        with sqlite3.connect(database, timeout=2) as locker:
            locker.execute("BEGIN IMMEDIATE")

            def write_during_lock():
                result["status"], result["body"] = api(
                    server.base_url, "POST", "/api/admin/rules", rule_payload("after-lock"), timeout=15)

            writer = threading.Thread(target=write_during_lock)
            writer.start()
            time.sleep(1)
            locker.rollback()
            writer.join(timeout=15)
        check(checks, "temporary SQLite lock recovers", result.get("status") == 201,
              f"status={result.get('status')}")
        check(checks, "database remains healthy after lock", integrity(database))
    finally:
        stop_server(server)


def scenario_oom(jar: Path, root: Path, output: Path, checks: list[dict]) -> None:
    fixture = root / "oom"
    fixture.mkdir()
    agent = build_oom_agent(fixture)
    trigger = fixture / "trigger-oom"
    options = ["-Xms192m", "-Xmx192m", "-XX:+ExitOnOutOfMemoryError",
               f"-javaagent:{agent}={trigger}"]
    server = start_server(jar, fixture, output, "oom", java_options=options)
    rule_ids: list[str] = []
    try:
        check(checks, "bounded OOM server starts", wait_ready(server))
        rule_ids = [create_rule(server, f"before-oom-{index}") for index in range(5)]
        trigger.touch()
        exit_code = wait_exit(server)
        check(checks, "OOM exits the whole JVM", exit_code is not None and exit_code != 0,
              f"exit_code={exit_code}")
        log_text = server.log_path.read_text(encoding="utf-8", errors="replace").lower()
        check(checks, "OOM marker is recorded", "outofmemoryerror" in log_text or "java heap space" in log_text)
    finally:
        stop_server(server)

    restarted = start_server(jar, fixture, output, "oom-restart")
    try:
        check(checks, "Echo restarts after OOM", wait_ready(restarted))
        for rule_id in rule_ids:
            require_rule(restarted, rule_id)
        check(checks, "acknowledged rules survive OOM", len(rule_ids) == 5)
        create_rule(restarted, "after-oom")
        check(checks, "database is writable after OOM restart", True)
        check(checks, "database remains healthy after OOM", integrity(fixture / "mockdb.sqlite"))
    finally:
        stop_server(restarted)


def scenario_corruption(jar: Path, root: Path, output: Path, checks: list[dict]) -> None:
    fixture = root / "corruption"
    fixture.mkdir()
    seeded = start_server(jar, fixture, output, "corruption-seed", backup_enabled=True)
    rule_id = ""
    try:
        check(checks, "backup seed server starts", wait_ready(seeded))
        rule_id = create_rule(seeded, "before-backup")
        status, body = api(seeded.base_url, "POST", "/api/admin/backup")
        check(checks, "verified SQLite backup is created", status == 200,
              f"status={status} body={body}")
        database = fixture / "mockdb.sqlite"
        with sqlite3.connect(database, timeout=5) as checkpoint:
            checkpoint.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchone()
        database.write_bytes(b"deliberately corrupt sqlite database")
        exit_code = wait_exit(seeded, timeout=15)
        check(checks, "runtime corruption exits the whole JVM", exit_code == 70,
              f"exit_code={exit_code}")
    finally:
        stop_server(seeded)

    corrupt_bytes = database.read_bytes()

    failed = start_server(jar, fixture, output, "corruption-fail-fast")
    try:
        exit_code = wait_exit(failed, timeout=15)
        check(checks, "corrupt SQLite fails startup", exit_code is not None and exit_code != 0,
              f"exit_code={exit_code}")
        check(checks, "fail-fast preserves corrupt database", database.read_bytes() == corrupt_bytes)
    finally:
        stop_server(failed)

    restored = start_server(jar, fixture, output, "corruption-restore",
                            recovery_mode="restore-latest")
    try:
        check(checks, "restore-latest starts Echo", wait_ready(restored))
        require_rule(restored, rule_id)
        check(checks, "backup-era rule survives restore", True)
        check(checks, "restored database is healthy", integrity(database))
        quarantines = list(fixture.glob("mockdb.sqlite.corrupt-*"))
        quarantine_databases = [path for path in quarantines
                                if not path.name.endswith(("-wal", "-shm"))]
        receipts = list((fixture / "backups").glob("sqlite-recovery-*.properties"))
        check(checks, "corrupt database is quarantined", len(quarantine_databases) == 1,
              f"files={[path.name for path in quarantines]}")
        check(checks, "corrupt WAL and SHM are preserved",
              any(path.name.endswith("-wal") for path in quarantines)
              and any(path.name.endswith("-shm") for path in quarantines),
              f"files={[path.name for path in quarantines]}")
        check(checks, "recovery receipt is recorded", len(receipts) == 1,
              f"files={[path.name for path in receipts]}")
        create_rule(restored, "after-restore")
        check(checks, "database is writable after restore", True)
    finally:
        stop_server(restored)


def main() -> int:
    jars = sorted((PROJECT_ROOT / "build" / "libs").glob("echo-server-*.jar"),
                  key=lambda path: path.stat().st_mtime, reverse=True)
    if not jars:
        print("No fat jar found. Run ./gradlew bootJar first.", file=sys.stderr)
        return 2

    run_id = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = ARTIFACT_ROOT / run_id
    output.mkdir(parents=True)
    checks: list[dict] = []
    result = {"runId": run_id, "jar": jars[0].name, "checks": checks,
              "status": "running", "limitations": [
                  "OOM is intentionally triggered by a test-only Java agent inside a 192 MiB Echo JVM.",
                  "All databases are disposable; this is not production deployment evidence."]}

    try:
        with tempfile.TemporaryDirectory(prefix="echo-sqlite-resilience-") as temp:
            root = Path(temp)
            scenario_lock(jars[0], root, output, checks)
            scenario_oom(jars[0], root, output, checks)
            scenario_corruption(jars[0], root, output, checks)
        result["status"] = "passed"
        return_code = 0
    except (TestFailure, subprocess.CalledProcessError, OSError) as error:
        result["status"] = "failed"
        result["error"] = str(error)
        return_code = 1
    finally:
        result["completedAt"] = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
        (output / "result.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False, indent=2))
        print(f"Evidence: {output / 'result.json'}")
    return return_code


if __name__ == "__main__":
    raise SystemExit(main())
