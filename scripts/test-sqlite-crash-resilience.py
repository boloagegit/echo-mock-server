#!/usr/bin/env python3
"""
SQLite Crash Resilience Test

模擬會讓 H2 chunk 損壞的情境，驗證 SQLite 不會發生：
1. 大量寫入中途強制終止
2. 反覆強制終止 + 重啟驗證資料完整性
3. 並發寫入壓力下強制終止

用法：python3 scripts/test-sqlite-crash-resilience.py
前提：需要先 ./gradlew bootJar（Windows 使用 .\\gradlew.bat bootJar）產生 fat jar
"""

import base64
import json
import os
from pathlib import Path
import random
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
BASE_URL = ""
TEST_USERNAME = os.getenv("ECHO_TEST_USERNAME", "admin")
TEST_PASSWORD = os.getenv("ECHO_TEST_PASSWORD", "admin")
AUTH = base64.b64encode(
    f"{TEST_USERNAME}:{TEST_PASSWORD}".encode()
).decode()
DB_FILE = Path()
SPOOL_FILE = Path()
JAR_FILE = Path()

# ========== 工具函式 ==========

def api(method, path, data=None):
    url = f"{BASE_URL}{path}"
    body = json.dumps(data).encode() if data else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json")
    req.add_header("Authorization", f"Basic {AUTH}")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode() if e.fp else ""
        try:
            return e.code, json.loads(raw) if raw.strip() else {}
        except:
            return e.code, raw
    except Exception:
        return 0, {}


def wait_for_server(timeout=30):
    """等待 server 啟動"""
    for _ in range(timeout):
        try:
            req = urllib.request.Request(f"{BASE_URL}/api/admin/status")
            req.add_header("Accept", "application/json")
            req.add_header("Authorization", f"Basic {AUTH}")
            with urllib.request.urlopen(req, timeout=2) as resp:
                if resp.status == 200:
                    return True
        except:
            pass
        time.sleep(1)
    return False


def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def process_group_options():
    if sys.platform == "win32":
        create_new_process_group = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0x00000200)
        return {"creationflags": create_new_process_group}
    return {"start_new_session": True}


def start_server():
    """啟動 Echo server (fat jar 模式)"""
    normalized_db = str(DB_FILE.resolve()).replace("\\", "/")
    proc = subprocess.Popen(
        [
            "java", "-jar", str(JAR_FILE),
            "--spring.profiles.active=sqlite",
            f"--spring.datasource.url=jdbc:sqlite:{normalized_db}?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL&foreign_keys=ON",
            f"--echo.request-log.durable.spool-path={SPOOL_FILE}",
            f"--server.port={BASE_URL.rsplit(':', 1)[1]}",
            f"--echo.admin.username={TEST_USERNAME}",
            f"--echo.admin.password={TEST_PASSWORD}",
            "--echo.backup.enabled=false",
            "--echo.cleanup.enabled=false",
            "--echo.jms.enabled=false",
        ],
        cwd=PROJECT_ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        **process_group_options(),
    )
    return proc


def kill_server(proc):
    """模擬非正常關閉；Windows 使用 taskkill 終止整棵 process tree。"""
    if proc.poll() is not None:
        return
    if sys.platform == "win32":
        subprocess.run(
            ["taskkill", "/PID", str(proc.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=20,
            check=False,
        )
    else:
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        except ProcessLookupError:
            pass
    try:
        proc.wait(timeout=20)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=10)


def stop_server(proc):
    """正常停止整個測試 process group，必要時退回強制終止。"""
    if proc.poll() is not None:
        return
    if sys.platform == "win32":
        ctrl_break = getattr(signal, "CTRL_BREAK_EVENT", None)
        if ctrl_break is not None:
            try:
                proc.send_signal(ctrl_break)
                proc.wait(timeout=20)
                return
            except (OSError, ValueError, subprocess.TimeoutExpired):
                pass
        kill_server(proc)
        return
    try:
        os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
        proc.wait(timeout=20)
    except (ProcessLookupError, subprocess.TimeoutExpired):
        kill_server(proc)


def check_db_integrity():
    """使用 Python 標準 sqlite3 驗證，不要求 Windows 額外安裝 CLI。"""
    try:
        with sqlite3.connect(DB_FILE, timeout=10) as connection:
            result = connection.execute("PRAGMA integrity_check").fetchone()
        return bool(result) and result[0] == "ok"
    except sqlite3.Error:
        return False


def count_rules():
    """查詢目前規則數（WAL mode 需要 shm/wal 檔案存在才能讀取最新資料）"""
    try:
        with sqlite3.connect(DB_FILE, timeout=10) as connection:
            result = connection.execute("SELECT COUNT(*) FROM http_rules").fetchone()
        return int(result[0]) if result else 0
    except sqlite3.Error:
        # 表可能不存在（WAL 未 checkpoint 時，schema 在 WAL 裡）
        # 如果 integrity_check 通過就不算失敗
        return 0


def create_rules_burst(count, prefix="crash-test"):
    """快速建立多條規則"""
    created = 0
    for i in range(count):
        s, _ = api("POST", "/api/admin/rules", {
            "protocol": "HTTP",
            "matchKey": f"/api/{prefix}/{i}",
            "method": "GET",
            "responseBody": json.dumps({"test": prefix, "index": i, "data": "x" * 200}),
            "status": 200,
            "description": f"{prefix}-{i}",
            "sseEnabled": False
        })
        if s == 201:
            created += 1
    return created


def create_rule_single(prefix, i):
    """單條規則建立（供並發用）"""
    s, _ = api("POST", "/api/admin/rules", {
        "protocol": "HTTP",
        "matchKey": f"/api/{prefix}/{i}",
        "method": "POST",
        "responseBody": json.dumps({"concurrent": True, "index": i, "payload": "y" * 500}),
        "status": 200,
        "description": f"{prefix}-{i}",
        "sseEnabled": False
    })
    return s == 201


# ========== 測試情境 ==========

def test_kill_during_write():
    """情境 1：大量寫入中途強制終止"""
    print("\n" + "=" * 60)
    print("情境 1：大量寫入中途強制終止")
    print("  模擬：H2 在寫入 chunk 時被殺，最後一個 chunk 寫一半")
    print("=" * 60)

    # 啟動 server
    proc = start_server()
    if not wait_for_server():
        print("  ✗ Server 啟動失敗")
        kill_server(proc)
        return False

    # 先寫一些資料確保 DB 有內容
    print("  → 寫入 30 條基準規則...")
    base_count = create_rules_burst(30, "base")
    print(f"  → 基準寫入完成: {base_count} 條")

    # 開始大量寫入，在寫入過程中 kill
    print("  → 開始密集寫入 + 同時強制終止...")
    
    # 用 thread 持續寫入
    writing = True
    write_count = [0]
    
    def writer():
        i = 0
        while writing:
            s, _ = api("POST", "/api/admin/rules", {
                "protocol": "HTTP",
                "matchKey": f"/api/kill-test/{i}",
                "method": "GET",
                "responseBody": json.dumps({"kill": True, "i": i, "big": "z" * 1000}),
                "status": 200,
                "description": f"kill-{i}",
                "sseEnabled": False
            })
            if s == 201:
                write_count[0] += 1
            i += 1
            time.sleep(0.01)  # 10ms 間隔密集寫入
    
    t = threading.Thread(target=writer, daemon=True)
    t.start()
    
    # 等寫入進行一段時間
    time.sleep(2)
    
    print(f"  → 已寫入 {write_count[0]} 條，執行強制終止...")
    writing = False
    kill_server(proc)
    t.join(timeout=2)
    time.sleep(1)

    # 驗證 DB 完整性
    print("  → 驗證資料庫完整性...")
    integrity = check_db_integrity()
    rule_count = count_rules()
    
    print(f"  → integrity_check: {'ok' if integrity else 'FAILED'}")
    print(f"  → 資料庫中規則數: {rule_count} (WAL 未 checkpoint 時可能為 0)")
    print(f"  → kill 前寫入數: {base_count + write_count[0]}")

    # 最重要的驗證：重啟後能否正常讀取
    print("  → 重新啟動驗證...")
    proc2 = start_server()
    restart_ok = wait_for_server()
    final_rules = 0
    if restart_ok:
        s, data = api("GET", "/api/admin/status")
        final_rules = data.get("ruleCount", 0) if isinstance(data, dict) else 0
        print(f"  → 重啟成功，規則數: {final_rules}")
        stop_server(proc2)
    else:
        kill_server(proc2)
        print("  ✗ 重啟失敗！")

    expected_committed = base_count + write_count[0]
    if integrity and restart_ok and final_rules >= expected_committed:
        print("  ✓ 情境 1 通過：強制終止後資料庫完整，已回覆成功的資料全數保留")
        return True
    else:
        print("  ✗ 情境 1 失敗：資料庫損壞或無法重啟")
        return False


def test_repeated_crash_recovery():
    """情境 2：反覆 crash + 重啟 (5 次)"""
    print("\n" + "=" * 60)
    print("情境 2：反覆 crash + 重啟 (5 輪)")
    print("  模擬：H2 反覆非正常關閉後無法啟動")
    print("=" * 60)

    success = True
    cumulative_rules = 0
    initial_rules = None

    for round_num in range(1, 6):
        print(f"\n  --- 第 {round_num} 輪 ---")
        
        proc = start_server()
        if not wait_for_server():
            print(f"  ✗ 第 {round_num} 輪啟動失敗！資料庫可能損壞")
            kill_server(proc)
            success = False
            break

        if initial_rules is None:
            _, status_data = api("GET", "/api/admin/status")
            initial_rules = status_data.get("ruleCount", 0) if isinstance(status_data, dict) else 0

        # 每輪寫入 10 條
        created = create_rules_burst(10, f"round{round_num}")
        cumulative_rules += created
        print(f"  → 寫入 {created} 條，預期累計 >= {cumulative_rules}")

        # 隨機等 0.5~2 秒後 kill
        wait_time = random.uniform(0.5, 2.0)
        time.sleep(wait_time)
        
        print(f"  → 等待 {wait_time:.1f}s 後強制終止...")
        kill_server(proc)
        time.sleep(0.5)

        # 每輪檢查完整性
        integrity = check_db_integrity()
        current_count = count_rules()
        print(f"  → integrity: {'ok' if integrity else 'FAILED'}, rules: {current_count}")
        
        expected_committed = (initial_rules or 0) + cumulative_rules
        if not integrity or current_count < expected_committed:
            print(f"  ✗ 第 {round_num} 輪後資料庫損壞或已回覆成功的資料遺失！")
            success = False
            break

    # 最終驗證：能否正常啟動並讀取所有資料
    print("\n  --- 最終驗證 ---")
    proc = start_server()
    if wait_for_server():
        s, data = api("GET", "/api/admin/status")
        rule_count = data.get("ruleCount", 0) if isinstance(data, dict) else 0
        print(f"  → 最終啟動成功，規則數: {rule_count}")
        
        # 驗證能正常寫入
        created = create_rules_burst(3, "final-verify")
        print(f"  → 最終寫入驗證: {created}/3 成功")
        
        stop_server(proc)
        
        expected_committed = (initial_rules or 0) + cumulative_rules
        if rule_count >= expected_committed and created == 3:
            print("  ✓ 情境 2 通過：反覆 crash 後資料庫正常")
        else:
            success = False
            print("  ✗ 情境 2 失敗")
    else:
        kill_server(proc)
        print("  ✗ 最終啟動失敗！")
        success = False

    return success


def test_concurrent_write_then_kill():
    """情境 3：多執行緒並發寫入 + 強制終止"""
    print("\n" + "=" * 60)
    print("情境 3：多執行緒並發寫入 + 強制終止")
    print("  模擬：H2 HikariCP 多連線同時寫入時被殺")
    print("=" * 60)

    proc = start_server()
    if not wait_for_server():
        print("  ✗ Server 啟動失敗")
        kill_server(proc)
        return False

    _, status_data = api("GET", "/api/admin/status")
    initial_rules = status_data.get("ruleCount", 0) if isinstance(status_data, dict) else 0

    # 先寫入基準資料
    base = create_rules_burst(20, "concurrent-base")
    print(f"  → 基準資料: {base} 條")

    # 10 個 thread 同時寫入
    print("  → 啟動 10 個並發寫入 thread...")
    
    stop_event = threading.Event()
    concurrent_count = [0]
    lock = threading.Lock()

    def concurrent_writer(thread_id):
        i = 0
        while not stop_event.is_set():
            ok = create_rule_single(f"conc-t{thread_id}", i)
            if ok:
                with lock:
                    concurrent_count[0] += 1
            i += 1
            time.sleep(0.02)

    threads = []
    for tid in range(10):
        t = threading.Thread(target=concurrent_writer, args=(tid,), daemon=True)
        t.start()
        threads.append(t)

    # 讓並發寫入跑 3 秒
    time.sleep(3)

    print(f"  → 並發寫入 {concurrent_count[0]} 條，執行強制終止...")
    stop_event.set()
    kill_server(proc)
    
    for t in threads:
        t.join(timeout=2)
    time.sleep(1)

    # 驗證
    integrity = check_db_integrity()
    rule_count = count_rules()
    print(f"  → integrity_check: {'ok' if integrity else 'FAILED'}")
    print(f"  → 資料庫中規則數: {rule_count}")
    print(f"  → 基準 + 並發寫入: {base} + {concurrent_count[0]}")

    # 確認能重新啟動
    print("  → 重新啟動驗證...")
    proc2 = start_server()
    restart_ok = wait_for_server()
    if restart_ok:
        s, data = api("GET", "/api/admin/status")
        final_rules = data.get("ruleCount", 0) if isinstance(data, dict) else 0
        print(f"  → 重啟成功，規則數: {final_rules}")
        stop_server(proc2)
    else:
        kill_server(proc2)
        print("  ✗ 重啟失敗！")

    expected_committed = initial_rules + base + concurrent_count[0]
    if integrity and restart_ok and rule_count >= expected_committed and final_rules >= expected_committed:
        print("  ✓ 情境 3 通過：並發寫入 + 強制終止後資料庫完整，已回覆成功的資料全數保留")
        return True
    else:
        print("  ✗ 情境 3 失敗：資料庫損壞！")
        return False


# ========== 主程式 ==========

if __name__ == "__main__":
    print("╔══════════════════════════════════════════════════════════════╗")
    print("║  SQLite Crash Resilience Test                               ║")
    print("║  模擬 H2 chunk 損壞的情境，驗證 SQLite 不會發生             ║")
    print("╚══════════════════════════════════════════════════════════════╝")

    jars = sorted(
        (PROJECT_ROOT / "build" / "libs").glob("echo-server-*.jar"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not jars:
        wrapper = ".\\gradlew.bat" if sys.platform == "win32" else "./gradlew"
        print(f"  ⚠ 找不到 fat jar，請先執行 {wrapper} bootJar")
        sys.exit(1)
    JAR_FILE = jars[0].resolve()
    print(f"  使用 jar: {JAR_FILE}")

    with tempfile.TemporaryDirectory(prefix="echo-sqlite-crash-") as temp_dir:
        temp_path = Path(temp_dir)
        DB_FILE = temp_path / "mockdb.sqlite"
        SPOOL_FILE = temp_path / "request-log-spool.sqlite"
        BASE_URL = f"http://127.0.0.1:{free_port()}"
        print(f"  測試資料: {temp_path}")

        results = [
            ("寫入中途強制終止", test_kill_during_write()),
            ("反覆 crash + 重啟", test_repeated_crash_recovery()),
            ("並發寫入 + 強制終止", test_concurrent_write_then_kill()),
        ]

        print("\n" + "=" * 60)
        print("  彙總結果")
        print("=" * 60)
        all_pass = True
        for name, passed in results:
            status = "✓ PASS" if passed else "✗ FAIL"
            print(f"  {status}  {name}")
            if not passed:
                all_pass = False

        print()
        if all_pass:
            print("  🎉 全部通過！SQLite 在所有 crash 情境下資料庫保持完整。")
        else:
            print("  ⚠ 有測試失敗！")

    sys.exit(0 if all_pass else 1)
