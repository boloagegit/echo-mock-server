#!/usr/bin/env python3
"""Safely migrate an offline Echo H2 database into a verified SQLite database.

The script never writes to the source H2 database and never exposes a partial SQLite
database at the requested target path. It creates the Hibernate schema in a staging
database, migrates all application tables in one transaction, compares deterministic
per-table SHA-256 digests, starts Echo against the staged database for API smoke tests,
and only then atomically publishes the final SQLite file.
"""

from __future__ import annotations

import argparse
import getpass
import hashlib
import json
import os
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_H2_PATH = PROJECT_ROOT / "mockdb"
DEFAULT_SQLITE_PATH = PROJECT_ROOT / "mockdb.sqlite"
DEFAULT_BACKUP_DIR = PROJECT_ROOT / "backups"
APPLICATION_TABLES = (
    "responses",
    "http_rules",
    "jms_rules",
    "builtin_users",
    "rule_audit_logs",
    "request_log",
    "request_log_checkpoint",
    "cache_events",
    "issue_reports",
    "scenarios",
    "jms_target_connections",
    "http_target_connections",
)
SQLITE_OPTIONS = "journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL&foreign_keys=ON"


class MigrationError(RuntimeError):
    """A fail-closed migration error."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="將離線 H2 完整遷移至經過驗證的 SQLite 暫存檔後再原子切換"
    )
    parser.add_argument(
        "--h2-path",
        default=str(DEFAULT_H2_PATH),
        help="H2 路徑（可含或不含 .mv.db）",
    )
    parser.add_argument(
        "--sqlite-path",
        default=str(DEFAULT_SQLITE_PATH),
        help="最終 SQLite 路徑；必須尚不存在",
    )
    parser.add_argument("--h2-user", default="sa")
    parser.add_argument(
        "--backup-dir",
        default=str(DEFAULT_BACKUP_DIR),
        help="H2 回復備份與遷移驗證報告目錄",
    )
    parser.add_argument(
        "--h2-password",
        default=None,
        help="不建議直接放在 command line；省略時會安全提示輸入",
    )
    parser.add_argument("--yes", action="store_true", help="略過最後的互動確認")
    parser.add_argument(
        "--keep-staging",
        action="store_true",
        help="失敗時保留暫存 SQLite，僅供除錯",
    )
    return parser.parse_args()


def normalize_h2_paths(value: str) -> tuple[Path, Path]:
    supplied = Path(value).expanduser().resolve()
    if supplied.name.endswith(".mv.db"):
        file_path = supplied
        base_path = Path(str(supplied)[: -len(".mv.db")])
    else:
        base_path = supplied
        file_path = Path(str(supplied) + ".mv.db")
    return base_path, file_path


def sqlite_jdbc_url(path: Path) -> str:
    normalized = str(path.resolve()).replace("\\", "/")
    return f"jdbc:sqlite:{normalized}?{SQLITE_OPTIONS}"


def h2_jdbc_url(path: Path) -> str:
    normalized = str(path.resolve()).replace("\\", "/")
    return f"jdbc:h2:file:{normalized};ACCESS_MODE_DATA=r;IFEXISTS=TRUE"


def find_h2_jar() -> Path:
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    cache = gradle_home / "caches" / "modules-2" / "files-2.1" / "com.h2database" / "h2"
    jars = [
        path
        for path in cache.rglob("h2-*.jar")
        if "sources" not in path.name and "javadoc" not in path.name
    ]
    if not jars:
        wrapper = ".\\gradlew.bat" if sys.platform == "win32" else "./gradlew"
        raise MigrationError(f"找不到 H2 JDBC driver，請先執行 {wrapper} build")
    return max(jars, key=lambda path: path.stat().st_mtime)


def check_java() -> None:
    try:
        result = subprocess.run(
            ["java", "-version"], capture_output=True, text=True, timeout=10, check=False
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise MigrationError("Java 不可用，遷移需要 Java 17+") from exc
    if result.returncode != 0:
        raise MigrationError("Java 無法正常執行")


def check_h2_offline(jar: Path, url: str, user: str, password: str) -> None:
    command = [
        "java",
        "-cp",
        str(jar),
        "org.h2.tools.Shell",
        "-url",
        url,
        "-user",
        user,
        "-password",
        password,
        "-sql",
        "SELECT COUNT(*) AS TABLE_COUNT FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC';",
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=30, check=False)
    except subprocess.TimeoutExpired as exc:
        raise MigrationError("H2 連線逾時；請先停止 Echo 及其他使用此資料庫的程式") from exc
    combined_output = result.stdout + "\n" + result.stderr
    if result.returncode != 0 or "Error" in combined_output or "Exception" in combined_output:
        detail = combined_output.strip().splitlines()
        tail = detail[-1] if detail else "未知錯誤"
        raise MigrationError(f"無法以唯讀模式開啟 H2；請確認服務已停止、帳密正確：{tail}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def fsync_file(path: Path) -> None:
    with path.open("rb") as stream:
        os.fsync(stream.fileno())


def create_verified_h2_backup(source: Path, backup_dir: Path) -> Path:
    backup_dir.mkdir(parents=True, exist_ok=True)
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    unique_suffix = uuid.uuid4().hex[:8]
    source_stem = source.stem
    if source_stem.endswith(".mv"):
        source_stem = source_stem[:-3]
    backup = backup_dir / f"{source_stem}-pre-sqlite-{timestamp}-{unique_suffix}.mv.db"
    shutil.copy2(source, backup)
    fsync_file(backup)
    source_hash = sha256_file(source)
    backup_hash = sha256_file(backup)
    if source_hash != backup_hash:
        backup.unlink(missing_ok=True)
        raise MigrationError("H2 備份 SHA-256 不一致，遷移已中止")
    return backup


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def request_json(port: int, path: str, timeout: float = 3.0) -> tuple[int, object]:
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}", headers={"Accept": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
            return response.status, json.loads(body) if body else None
    except urllib.error.HTTPError as exc:
        return exc.code, None
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
        return 0, None


def terminate_process(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    if sys.platform == "win32":
        ctrl_break = getattr(signal, "CTRL_BREAK_EVENT", None)
        if ctrl_break is not None:
            try:
                process.send_signal(ctrl_break)
                process.wait(timeout=20)
                return
            except (OSError, ValueError, subprocess.TimeoutExpired):
                pass
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=20,
            check=False,
        )
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
        return
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGTERM)
        process.wait(timeout=20)
    except (ProcessLookupError, subprocess.TimeoutExpired):
        if process.poll() is None:
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            process.wait(timeout=10)


def gradle_wrapper() -> str:
    name = "gradlew.bat" if sys.platform == "win32" else "gradlew"
    return str(PROJECT_ROOT / name)


def process_group_options() -> dict[str, object]:
    if sys.platform == "win32":
        create_new_process_group = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0x00000200)
        return {"creationflags": create_new_process_group}
    return {"start_new_session": True}


def staging_spool_path(db_path: Path) -> Path:
    """Return a unique spool path that cannot overlap the live/default spool."""
    resolved = db_path.expanduser().resolve()
    return resolved.parent / f".{resolved.name}.request-log-spool-{uuid.uuid4().hex}.sqlite"


def start_sqlite_app(db_path: Path, log_path: Path, expected: dict[str, int] | None) -> dict:
    port = free_port()
    command = [gradle_wrapper(), "bootRun", "--no-daemon"]
    spool_path = staging_spool_path(db_path)
    environment = os.environ.copy()
    environment.update(
        {
            "SPRING_PROFILES_ACTIVE": "sqlite",
            "SPRING_DATASOURCE_URL": sqlite_jdbc_url(db_path),
            "SERVER_PORT": str(port),
            "ECHO_BACKUP_ENABLED": "false",
            "ECHO_CLEANUP_ENABLED": "false",
            "ECHO_JMS_ENABLED": "false",
            "ECHO_REQUEST_LOG_SPOOL_PATH": str(spool_path),
        }
    )

    with log_path.open("wb") as log_stream:
        process = subprocess.Popen(
            command,
            cwd=PROJECT_ROOT,
            env=environment,
            stdout=log_stream,
            stderr=subprocess.STDOUT,
            **process_group_options(),
        )
        try:
            deadline = time.monotonic() + 120
            status_data = None
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    break
                status, data = request_json(port, "/api/admin/status")
                if status == 200 and isinstance(data, dict):
                    status_data = data
                    break
                time.sleep(1)
            if status_data is None:
                tail = tail_text(log_path)
                raise MigrationError(f"SQLite 驗證用 Echo 啟動失敗：\n{tail}")

            datasource = str(status_data.get("datasourceUrl", ""))
            if "jdbc:sqlite:" not in datasource or db_path.name not in datasource:
                raise MigrationError(f"Echo 啟動後使用了錯誤資料源：{datasource}")

            if expected is not None:
                # 驗證程序刻意關閉 JMS，status 的 ruleCount 因此只包含 HTTP handler。
                expected_rules = expected["http_rules"]
                if int(status_data.get("ruleCount", -1)) != expected_rules:
                    raise MigrationError("啟動後規則數與遷移報告不一致")
                if int(status_data.get("responseCount", -1)) != expected["responses"]:
                    raise MigrationError("啟動後回應數與遷移報告不一致")
                if int(status_data.get("requestLogCount", -1)) != expected["request_log"]:
                    raise MigrationError("啟動後請求紀錄數與遷移報告不一致")
                smoke_paths = (
                    "/api/admin/rules?page=0&size=10",
                    "/api/admin/responses?page=0&size=10",
                    "/api/admin/logs?page=0&size=10",
                    "/api/admin/audit?page=0&size=10",
                )
                for path in smoke_paths:
                    status, _ = request_json(port, path, timeout=10)
                    if status != 200:
                        raise MigrationError(f"啟動後功能驗證失敗：GET {path} -> HTTP {status}")
            return status_data
        finally:
            terminate_process(process)
            remove_sqlite_files(spool_path)


def tail_text(path: Path, lines: int = 30) -> str:
    try:
        content = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return "（無啟動紀錄）"
    return "\n".join(content[-lines:])


def verify_schema(path: Path) -> None:
    with sqlite3.connect(path) as connection:
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
    missing = set(APPLICATION_TABLES) - tables
    if missing:
        raise MigrationError(f"Hibernate 未建立完整 SQLite schema，缺少：{sorted(missing)}")
    unexpected = tables - set(APPLICATION_TABLES) - {"sqlite_sequence"}
    if unexpected:
        raise MigrationError(
            f"SQLite schema 出現遷移程式尚未涵蓋的資料表：{sorted(unexpected)}"
        )


def run_jdbc_migration(
    h2_url: str,
    sqlite_url: str,
    h2_user: str,
    h2_password: str,
    report_path: Path,
) -> dict:
    environment = os.environ.copy()
    environment.update(
        {
            "ECHO_MIGRATION_H2_URL": h2_url,
            "ECHO_MIGRATION_H2_USER": h2_user,
            "ECHO_MIGRATION_H2_PASSWORD": h2_password,
            "ECHO_MIGRATION_SQLITE_URL": sqlite_url,
            "ECHO_MIGRATION_REPORT": str(report_path),
        }
    )
    result = subprocess.run(
        [gradle_wrapper(), "migrateH2ToSqlite", "--no-daemon"],
        cwd=PROJECT_ROOT,
        env=environment,
        text=True,
        capture_output=True,
        timeout=600,
        check=False,
    )
    if result.returncode != 0:
        detail = "\n".join((result.stdout + "\n" + result.stderr).splitlines()[-40:])
        raise MigrationError(f"JDBC 遷移或內容核對失敗：\n{detail}")
    if not report_path.exists():
        raise MigrationError("遷移完成但未產生驗證報告")
    return json.loads(report_path.read_text(encoding="utf-8"))


def validate_report(report: dict) -> dict[str, int]:
    tables = report.get("tables")
    if not isinstance(tables, list) or len(tables) != len(APPLICATION_TABLES):
        raise MigrationError("驗證報告的資料表數量不正確")
    counts: dict[str, int] = {}
    for table in tables:
        name = table.get("table")
        if name not in APPLICATION_TABLES:
            raise MigrationError(f"驗證報告包含未知資料表：{name}")
        source_rows = int(table.get("sourceRows", -1))
        target_rows = int(table.get("targetRows", -2))
        source_hash = table.get("sourceSha256")
        target_hash = table.get("targetSha256")
        if source_rows != target_rows or source_hash != target_hash:
            raise MigrationError(f"資料表 {name} 的筆數或 SHA-256 不一致")
        counts[name] = source_rows
    if set(counts) != set(APPLICATION_TABLES):
        raise MigrationError("驗證報告缺少必要資料表")
    if report.get("integrityCheck") != "ok" or report.get("foreignKeyCheck") != "ok":
        raise MigrationError("SQLite integrity_check 或 foreign_key_check 未通過")
    return counts


def verify_sqlite_file(path: Path) -> None:
    with sqlite3.connect(path) as connection:
        integrity = connection.execute("PRAGMA integrity_check").fetchone()
        foreign_key_errors = connection.execute("PRAGMA foreign_key_check").fetchall()
    if not integrity or integrity[0] != "ok":
        raise MigrationError("最終 SQLite integrity_check 失敗")
    if foreign_key_errors:
        raise MigrationError("最終 SQLite foreign_key_check 失敗")


def publish_atomic_database(staging: Path, target: Path, candidate: Path) -> None:
    try:
        with sqlite3.connect(staging, isolation_level=None) as source:
            source.execute("PRAGMA wal_checkpoint(FULL)").fetchone()
            # VACUUM INTO 會產生一致、壓縮且不依賴 WAL/SHM 的單檔快照。
            source.execute("VACUUM INTO ?", (str(candidate),))
        with sqlite3.connect(candidate, isolation_level=None) as connection:
            journal_mode = connection.execute("PRAGMA journal_mode").fetchone()
            if not journal_mode or journal_mode[0].lower() != "delete":
                raise MigrationError("最終 SQLite 快照仍依賴 WAL sidecar")
    except sqlite3.Error as exc:
        raise MigrationError(f"建立單檔 SQLite 快照失敗：{exc}") from exc
    for sidecar in (Path(str(candidate) + "-wal"), Path(str(candidate) + "-shm")):
        sidecar.unlink(missing_ok=True)
    verify_sqlite_file(candidate)
    fsync_file(candidate)
    if target.exists():
        raise MigrationError(f"最終目標在遷移期間被建立，拒絕覆蓋：{target}")
    os.replace(candidate, target)
    fsync_directory(target.parent)


def fsync_directory(path: Path) -> None:
    # Windows has no portable equivalent for opening and fsyncing a directory.
    # The database file itself was already fsynced before the atomic replace.
    if sys.platform == "win32":
        return
    directory_fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)


def remove_sqlite_files(path: Path) -> None:
    for candidate in (path, Path(str(path) + "-wal"), Path(str(path) + "-shm")):
        candidate.unlink(missing_ok=True)


def confirm(args: argparse.Namespace, h2_file: Path, sqlite_path: Path) -> bool:
    print("\n即將執行離線遷移：")
    print(f"  H2 唯讀來源 : {h2_file}")
    print(f"  SQLite 目標 : {sqlite_path}")
    print("  正式 SQLite 只會在全部核對與啟動測試通過後出現。")
    if args.yes:
        return True
    return input("\n確認開始？輸入 yes：").strip().lower() == "yes"


def main() -> int:
    args = parse_args()
    os.chdir(PROJECT_ROOT)
    h2_base, h2_file = normalize_h2_paths(args.h2_path)
    sqlite_path = Path(args.sqlite_path).expanduser().resolve()
    backup_dir = Path(args.backup_dir).expanduser().resolve()
    sqlite_path.parent.mkdir(parents=True, exist_ok=True)
    password = args.h2_password
    if password is None:
        password = getpass.getpass("H2 密碼（預設通常為空，直接 Enter）：")

    if not h2_file.is_file():
        raise MigrationError(f"H2 資料庫不存在：{h2_file}")
    if any(
        candidate.exists()
        for candidate in (
            sqlite_path,
            Path(str(sqlite_path) + "-wal"),
            Path(str(sqlite_path) + "-shm"),
        )
    ):
        raise MigrationError(f"SQLite 目標已存在，為避免覆蓋資料已中止：{sqlite_path}")
    if not confirm(args, h2_file, sqlite_path):
        print("已取消，沒有修改任何資料。")
        return 0

    check_java()
    h2_jar = find_h2_jar()
    source_url = h2_jdbc_url(h2_base)
    check_h2_offline(h2_jar, source_url, args.h2_user, password)

    backup = create_verified_h2_backup(h2_file, backup_dir)
    token = uuid.uuid4().hex
    staging = sqlite_path.parent / f".{sqlite_path.name}.migrating-{token}"
    candidate = sqlite_path.parent / f".{sqlite_path.name}.verified-{token}"
    report_temp = sqlite_path.parent / f".{sqlite_path.name}.report-{token}.json"
    log_path = sqlite_path.parent / f".{sqlite_path.name}.startup-{token}.log"

    print(f"\n✓ H2 已備份並核對 SHA-256：{backup}")
    try:
        print("→ 建立 SQLite schema...")
        start_sqlite_app(staging, log_path, expected=None)
        verify_schema(staging)

        print("→ 以單一交易遷移並逐表核對 SHA-256...")
        report = run_jdbc_migration(
            source_url,
            sqlite_jdbc_url(staging),
            args.h2_user,
            password,
            report_temp,
        )
        counts = validate_report(report)

        print("→ 使用遷移後 SQLite 啟動 Echo 並驗證查詢 API...")
        start_sqlite_app(staging, log_path, expected=counts)

        timestamp = time.strftime("%Y%m%d-%H%M%S")
        final_report = backup_dir / f"h2-to-sqlite-{timestamp}-{token[:8]}.json"
        shutil.copy2(report_temp, final_report)
        fsync_file(final_report)

        print("→ 建立單檔一致性快照並原子發布...")
        publish_atomic_database(staging, sqlite_path, candidate)

        total = sum(counts.values())
        print("\n遷移完成，所有檢查均通過：")
        print(f"  SQLite       : {sqlite_path}")
        print(f"  遷移資料      : {total:,} 筆 / {len(counts)} 張表")
        print(f"  驗證報告      : {final_report}")
        print(f"  H2 回復備份   : {backup}")
        print("\n啟動 SQLite：")
        if sys.platform == "win32":
            print("  PowerShell: $env:SPRING_PROFILES_ACTIVE='sqlite'; .\\gradlew.bat bootRun")
        else:
            print("  SPRING_PROFILES_ACTIVE=sqlite ./gradlew bootRun")
        print("\n原 H2 不會被刪除；確認正式運行穩定前請保留它。")
        return 0
    finally:
        if not args.keep_staging:
            remove_sqlite_files(staging)
        remove_sqlite_files(candidate)
        report_temp.unlink(missing_ok=True)
        log_path.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\n已取消；正式 SQLite 目標未被發布。", file=sys.stderr)
        raise SystemExit(130)
    except MigrationError as exc:
        print(f"\n遷移失敗：{exc}", file=sys.stderr)
        print("正式 SQLite 目標未被發布；H2 來源未被修改。", file=sys.stderr)
        raise SystemExit(1)
