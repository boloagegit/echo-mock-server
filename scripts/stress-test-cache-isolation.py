#!/usr/bin/env python3
"""
快取隔離效能驗證：確認 HTTP/JMS 快取分離後，修改規則不會影響另一協定的快取命中率

測試情境：
  1. 建立多條 HTTP 規則（不同 path）+ 多條 JMS 規則
  2. 持續發送 HTTP mock 請求（模擬正常流量）
  3. 同時不斷修改 JMS 規則（模擬頻繁異動）
  4. 測量 HTTP 請求的延遲是否受 JMS 規則異動影響

預期結果：
  - 修改 JMS 規則不應導致 HTTP 快取失效
  - HTTP 請求延遲在 JMS 異動期間應保持穩定

用法：python3 scripts/stress-test-cache-isolation.py [BASE_URL] [DURATION_SEC] [CONCURRENCY]
"""

import json
import sys
import time
import urllib.request
import base64
import threading
import statistics
from collections import defaultdict

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
DURATION = int(sys.argv[2]) if len(sys.argv) > 2 else 15
CONCURRENCY = int(sys.argv[3]) if len(sys.argv) > 3 else 10
AUTH = base64.b64encode(b"admin:admin").decode()
HOST = "cache-iso.api.test"

HTTP_PATH_COUNT = 20       # 建立幾條不同 path 的 HTTP 規則
JMS_RULE_COUNT = 10        # JMS 規則數量
MODIFY_INTERVAL = 0.1      # JMS 規則修改間隔（秒）
WARMUP_REQUESTS = 3        # 每個 path 的 warm-up 次數


def api(method, path, data=None):
    url = f"{BASE_URL}{path}"
    body = json.dumps(data).encode() if data else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json")
    req.add_header("Authorization", f"Basic {AUTH}")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, ""
    except Exception as ex:
        return 0, str(ex)


def mock_request(path, body=None, headers=None):
    """發送 mock 請求，回傳 (status, latency_ms)"""
    url = f"{BASE_URL}/mock{path}"
    body_bytes = body.encode() if body else None
    req = urllib.request.Request(url, data=body_bytes, method="POST" if body else "GET")
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            resp.read()
            return resp.status, (time.time() - start) * 1000
    except urllib.error.HTTPError as e:
        return e.code, (time.time() - start) * 1000
    except Exception:
        return 0, (time.time() - start) * 1000


# ========== Phase 1: 建立規則 ==========

def setup_rules():
    """建立 HTTP 和 JMS 規則"""
    http_rules = []
    jms_rule_ids = []

    print(f"  建立 {HTTP_PATH_COUNT} 條 HTTP 規則...")
    for i in range(HTTP_PATH_COUNT):
        rule = {
            "protocol": "HTTP",
            "matchKey": f"/cache-iso/api-{i:03d}",
            "method": "GET",
            "targetHost": HOST,
            "responseBody": json.dumps({"path": i, "data": "x" * 200}),
            "status": 200,
            "description": f"Cache isolation HTTP #{i}",
            "sseEnabled": False,
        }
        status, resp = api("POST", "/api/admin/rules", rule)
        if status == 201 and resp:
            http_rules.append(resp)

    print(f"  建立 {JMS_RULE_COUNT} 條 JMS 規則...")
    for i in range(JMS_RULE_COUNT):
        rule = {
            "protocol": "JMS",
            "matchKey": f"CACHE.ISO.Q{i}",
            "responseBody": json.dumps({"queue": i}),
            "description": f"Cache isolation JMS #{i}",
        }
        status, resp = api("POST", "/api/admin/rules", rule)
        if status == 201 and resp:
            jms_rule_ids.append(resp.get("id"))

    return http_rules, jms_rule_ids


# ========== Phase 2: Warm up ==========

def warmup(http_rules):
    """預熱 HTTP 快取"""
    print(f"  預熱 HTTP 快取（{WARMUP_REQUESTS} 次/path）...")
    headers = {"X-Original-Host": HOST}
    for rule in http_rules:
        path = rule.get("matchKey", "")
        for _ in range(WARMUP_REQUESTS):
            mock_request(path, headers=headers)
    time.sleep(0.5)


# ========== Phase 3: 基準測試（無異動） ==========

def run_http_traffic(paths, duration, label, stop_event=None):
    """並發發送 HTTP mock 請求，收集延遲"""
    latencies = []
    errors = 0
    lock = threading.Lock()
    local_stop = stop_event or threading.Event()

    def worker():
        nonlocal errors
        local_lat = []
        local_err = 0
        idx = 0
        headers = {"X-Original-Host": HOST}
        while not local_stop.is_set():
            path = paths[idx % len(paths)]
            idx += 1
            status, lat = mock_request(path, headers=headers)
            if status == 200:
                local_lat.append(lat)
            else:
                local_err += 1
        with lock:
            latencies.extend(local_lat)
            errors += local_err

    threads = []
    for _ in range(CONCURRENCY):
        t = threading.Thread(target=worker, daemon=True)
        t.start()
        threads.append(t)

    if stop_event is None:
        time.sleep(duration)
        local_stop.set()

    for t in threads:
        t.join(timeout=5)

    return latencies, errors


def compute_stats(latencies, duration):
    """計算延遲統計"""
    if not latencies:
        return {"total": 0, "rps": 0, "avg": 0, "p50": 0, "p95": 0, "p99": 0, "min": 0, "max": 0}
    latencies.sort()
    n = len(latencies)
    return {
        "total": n,
        "rps": n / duration if duration > 0 else 0,
        "avg": statistics.mean(latencies),
        "p50": latencies[int(n * 0.50)],
        "p95": latencies[int(n * 0.95)],
        "p99": latencies[int(min(n * 0.99, n - 1))],
        "min": min(latencies),
        "max": max(latencies),
    }


def print_stats(label, stats):
    print(f"\n  [{label}]")
    print(f"    RPS:    {stats['rps']:,.0f}")
    print(f"    總請求: {stats['total']:,}")
    print(f"    延遲 (ms):")
    print(f"      avg: {stats['avg']:.1f}  p50: {stats['p50']:.1f}  p95: {stats['p95']:.1f}  p99: {stats['p99']:.1f}")
    print(f"      min: {stats['min']:.1f}  max: {stats['max']:.1f}")


# ========== Phase 4: 異動測試 ==========

def jms_modifier(jms_rule_ids, stop_event, modify_count_ref):
    """持續修改 JMS 規則"""
    count = 0
    while not stop_event.is_set():
        for rid in jms_rule_ids:
            if stop_event.is_set():
                break
            # 讀取現有規則
            status, rule = api("GET", f"/api/admin/rules/{rid}")
            if status != 200 or not rule:
                continue
            # 修改 description 觸發 cache evict
            rule["description"] = f"Modified at {time.time():.3f} count={count}"
            api("PUT", f"/api/admin/rules/{rid}", rule)
            count += 1
            time.sleep(MODIFY_INTERVAL)
    modify_count_ref.append(count)


def http_modifier(http_rules, stop_event, modify_count_ref):
    """持續修改 HTTP 規則（用於對照組）"""
    count = 0
    while not stop_event.is_set():
        for rule in http_rules:
            if stop_event.is_set():
                break
            rid = rule.get("id")
            if not rid:
                continue
            status, current = api("GET", f"/api/admin/rules/{rid}")
            if status != 200 or not current:
                continue
            current["description"] = f"HTTP modified at {time.time():.3f} count={count}"
            api("PUT", f"/api/admin/rules/{rid}", current)
            count += 1
            time.sleep(MODIFY_INTERVAL)
    modify_count_ref.append(count)


# ========== Main ==========

print("=" * 70)
print("  快取隔離效能驗證")
print(f"  目標: {BASE_URL}")
print(f"  持續: {DURATION}s / 並發: {CONCURRENCY} threads")
print(f"  HTTP 規則: {HTTP_PATH_COUNT} 條 / JMS 規則: {JMS_RULE_COUNT} 條")
print("=" * 70)

# 清理
api("DELETE", "/api/admin/rules/all")
api("DELETE", "/api/admin/responses/orphans")
api("DELETE", "/api/admin/logs/all")
time.sleep(0.5)

# 建立規則
http_rules, jms_rule_ids = setup_rules()
paths = [r.get("matchKey", "") for r in http_rules]

if not paths:
    print("  ❌ 無法建立 HTTP 規則，中止測試")
    sys.exit(1)

# 預熱
warmup(http_rules)

# --- 測試 A: 基準（無任何異動）---
print(f"\n{'─' * 70}")
print(f"  測試 A: 基準測試（無異動，純 HTTP 流量 {DURATION}s）")
sys.stdout.write("  測試中...")
sys.stdout.flush()

lat_a, err_a = run_http_traffic(paths, DURATION, "基準")
stats_a = compute_stats(lat_a, DURATION)
print(" 完成")
print_stats("A: 基準（無異動）", stats_a)

# 重新預熱
warmup(http_rules)

# --- 測試 B: JMS 異動（應不影響 HTTP 快取）---
print(f"\n{'─' * 70}")
print(f"  測試 B: JMS 規則持續異動 + HTTP 流量 ({DURATION}s)")
sys.stdout.write("  測試中...")
sys.stdout.flush()

stop_b = threading.Event()
jms_modify_count = []
modifier_thread = threading.Thread(
    target=jms_modifier, args=(jms_rule_ids, stop_b, jms_modify_count), daemon=True
)
modifier_thread.start()

lat_b, err_b = run_http_traffic(paths, DURATION, "JMS 異動")
stop_b.set()
modifier_thread.join(timeout=5)

stats_b = compute_stats(lat_b, DURATION)
print(" 完成")
print_stats("B: JMS 異動中", stats_b)
print(f"    JMS 規則修改次數: {jms_modify_count[0] if jms_modify_count else 0}")

# 重新預熱
warmup(http_rules)

# --- 測試 C: HTTP 異動（對照組，應影響 HTTP 快取）---
print(f"\n{'─' * 70}")
print(f"  測試 C: HTTP 規則持續異動 + HTTP 流量 ({DURATION}s)（對照組）")
sys.stdout.write("  測試中...")
sys.stdout.flush()

stop_c = threading.Event()
http_modify_count = []
modifier_thread_c = threading.Thread(
    target=http_modifier, args=(http_rules, stop_c, http_modify_count), daemon=True
)
modifier_thread_c.start()

lat_c, err_c = run_http_traffic(paths, DURATION, "HTTP 異動")
stop_c.set()
modifier_thread_c.join(timeout=5)

stats_c = compute_stats(lat_c, DURATION)
print(" 完成")
print_stats("C: HTTP 異動中（對照組）", stats_c)
print(f"    HTTP 規則修改次數: {http_modify_count[0] if http_modify_count else 0}")

# ========== 總結 ==========
print()
print("=" * 70)
print("  總結比較")
print("=" * 70)
print(f"  {'測試':<30} {'RPS':>8} {'avg':>8} {'p50':>8} {'p95':>8} {'p99':>8}")
print(f"  {'─'*30} {'─'*8} {'─'*8} {'─'*8} {'─'*8} {'─'*8}")
for label, s in [("A: 基準（無異動）", stats_a), ("B: JMS 異動中", stats_b), ("C: HTTP 異動中（對照）", stats_c)]:
    print(f"  {label:<30} {s['rps']:>7,.0f} {s['avg']:>7.1f}ms {s['p50']:>7.1f}ms {s['p95']:>7.1f}ms {s['p99']:>7.1f}ms")

# 計算影響比例
if stats_a["avg"] > 0:
    b_impact = ((stats_b["avg"] - stats_a["avg"]) / stats_a["avg"]) * 100
    c_impact = ((stats_c["avg"] - stats_a["avg"]) / stats_a["avg"]) * 100
    print(f"\n  JMS 異動對 HTTP 延遲影響: {b_impact:+.1f}%")
    print(f"  HTTP 異動對 HTTP 延遲影響: {c_impact:+.1f}%（對照組）")

    if abs(b_impact) < 15:
        print("\n  ✅ 快取隔離有效：JMS 異動未顯著影響 HTTP 效能")
    else:
        print("\n  ⚠️  JMS 異動對 HTTP 效能有顯著影響，需進一步調查")

if stats_a["rps"] > 0:
    b_rps_impact = ((stats_b["rps"] - stats_a["rps"]) / stats_a["rps"]) * 100
    c_rps_impact = ((stats_c["rps"] - stats_a["rps"]) / stats_a["rps"]) * 100
    print(f"\n  RPS 變化：")
    print(f"    B vs A: {b_rps_impact:+.1f}%")
    print(f"    C vs A: {c_rps_impact:+.1f}%")

print("=" * 70)

# 清理
api("DELETE", "/api/admin/rules/all")
api("DELETE", "/api/admin/responses/orphans")
api("DELETE", "/api/admin/logs/all")
print("\n完成")
