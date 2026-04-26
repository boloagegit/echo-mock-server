#!/usr/bin/env python3
"""
壓力測試：1600 條規則下的匹配效能

測試策略：
  - 建立 1580 條不同 path 的噪音規則（模擬大量規則的 DB/快取壓力）
  - 建立 20 條同 path 候選規則（模擬匹配鏈遍歷壓力）
  - 其中 1 條是目標規則（全條件 + 模板渲染）
  - 分別測試 cold cache 和 warm cache 的匹配時間

用法：python3 scripts/stress-test-1600-rules.py [BASE_URL]
"""

import json
import sys
import time
import urllib.request
import base64
import concurrent.futures

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
ADMIN_API = f"{BASE_URL}/api/admin"
MOCK_API = f"{BASE_URL}/mock"
AUTH = base64.b64encode(b"admin:admin").decode()
HOST = "stress.api.test"

NOISE_COUNT = 1580
CANDIDATE_COUNT = 19  # 同 path 候選（不含目標規則）
TOTAL = NOISE_COUNT + CANDIDATE_COUNT + 1


def api(method, path, data=None):
    url = f"{BASE_URL}{path}"
    body = json.dumps(data).encode() if data else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json")
    req.add_header("Authorization", f"Basic {AUTH}")
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except:
            return e.code, ""


def mock_request(path, method, headers, body=None):
    url = f"{MOCK_API}{path}"
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in headers.items():
        req.add_header(k, v)
    start = time.time()
    try:
        with urllib.request.urlopen(req) as resp:
            elapsed = time.time() - start
            return resp.status, resp.read().decode(), elapsed, dict(resp.headers)
    except urllib.error.HTTPError as e:
        elapsed = time.time() - start
        return e.code, e.read().decode(), elapsed, {}


def wait_for_log(endpoint_filter, timeout=15):
    """等待日誌寫入並回傳最新一筆"""
    for _ in range(timeout):
        time.sleep(1)
        _, logs = api("GET", f"/api/admin/logs?matched=true&protocol=HTTP&endpoint={endpoint_filter}")
        results = logs.get("results", []) if isinstance(logs, dict) else []
        if results:
            return results[0]
    return None


print("=" * 50)
print(f"壓力測試：{TOTAL} 條規則下的匹配效能")
print(f"目標：{BASE_URL}")
print(f"噪音規則：{NOISE_COUNT} 條（不同 path）")
print(f"同 path 候選：{CANDIDATE_COUNT} 條 + 1 目標規則")
print("=" * 50)
print()

# --- 批次建立噪音規則 ---
print(f"[1/3] 批次建立 {NOISE_COUNT} 條噪音規則...")
start_time = time.time()

# 用 import-batch API 批次建立
BATCH_SIZE = 100
for batch_start in range(0, NOISE_COUNT, BATCH_SIZE):
    batch_end = min(batch_start + BATCH_SIZE, NOISE_COUNT)
    batch = []
    for i in range(batch_start, batch_end):
        batch.append({
            "protocol": "HTTP",
            "matchKey": f"/noise/api/v{i // 100}/resource-{i}",
            "method": "GET",
            "targetHost": f"noise-{i % 50}.api.test",
            "responseBody": json.dumps({"noise": i, "data": f"response-{i}"}),
            "status": 200,
            "bodyCondition": f"id={i}" if i % 3 == 0 else None,
            "queryCondition": f"page={i % 10}" if i % 5 == 0 else None,
            "priority": i % 3,
            "description": f"噪音規則 #{i}",
            "sseEnabled": False,
        })
    status, resp = api("POST", "/api/admin/rules/import-batch", batch)
    progress = min(batch_end, NOISE_COUNT)
    pct = progress * 100 // NOISE_COUNT
    sys.stdout.write(f"\r  進度：{progress}/{NOISE_COUNT} ({pct}%)")
    sys.stdout.flush()

noise_time = time.time() - start_time
print(f"\n  完成 ({noise_time:.1f}s)")

# --- 建立同 path 候選規則 ---
print(f"\n[2/3] 建立 {CANDIDATE_COUNT} 條同 path 候選規則...")

for i in range(CANDIDATE_COUNT):
    conditions = []
    if i % 2 == 0:
        body_cond = f"type=TYPE_{i}"
        query_cond = f"filter=val_{i}"
        header_cond = f"X-Tag=tag_{i}"
    else:
        body_cond = f"category=CAT_{i};subtype=SUB_{i}"
        query_cond = f"page={i}"
        header_cond = f"X-Version={i}"

    api("POST", "/api/admin/rules", {
        "protocol": "HTTP",
        "matchKey": "/api/v1/orders",
        "method": "POST",
        "targetHost": HOST,
        "responseBody": json.dumps({"candidate": i}),
        "status": 200,
        "bodyCondition": body_cond,
        "queryCondition": query_cond,
        "headerCondition": header_cond,
        "priority": 0,
        "description": f"候選規則 #{i}（條件不符）",
        "sseEnabled": False,
    })

print(f"  完成")

# --- 建立目標規則 ---
print(f"\n[3/3] 建立目標規則（全條件 + 模板渲染）...")

template_body = json.dumps({
    "orderId": "{{jsonPath request.body '$.orderId'}}",
    "customer": "{{jsonPath request.body '$.customer.name'}}",
    "itemCount": "{{size (jsonPath request.body '$.items')}}",
    "path": "{{request.path}}",
    "method": "{{request.method}}",
    "status": "{{request.query.status}}",
    "timestamp": "{{now format='yyyy-MM-dd'}}",
    "traceId": "{{randomValue type='UUID'}}",
}, ensure_ascii=False)

status, target_rule = api("POST", "/api/admin/rules", {
    "protocol": "HTTP",
    "matchKey": "/api/v1/orders",
    "method": "POST",
    "targetHost": HOST,
    "responseBody": template_body,
    "status": 200,
    "bodyCondition": "type=ORDER;customer.name=John;$.items[0].sku=A1",
    "queryCondition": "status=active;type=vip",
    "headerCondition": "Content-Type*=json;X-Tenant=abc",
    "responseHeaders": '{"X-Trace-Id":"echo-trace","X-Matched":"true"}',
    "priority": 0,
    "description": "目標規則：全條件 + 模板",
    "sseEnabled": False,
})
target_id = target_rule.get("id", "?") if isinstance(target_rule, dict) else "?"
print(f"  ID: {target_id}")

# --- 確認規則總數 ---
_, status_resp = api("GET", "/api/admin/status")
rule_count = status_resp.get("ruleCount", "?") if isinstance(status_resp, dict) else "?"
print(f"\n  目前規則總數：{rule_count}")

# --- 清除日誌 ---
api("DELETE", "/api/admin/logs/all")

# === 測試 1：Cold Cache ===
print()
print("=" * 50)
print("  測試 1：Cold Cache（清除快取後首次請求）")
print("=" * 50)

# 清除快取 — 透過修改任一規則觸發 @CacheEvict
# 用 disable/enable 目標規則來清快取
api("PUT", f"/api/admin/rules/{target_id}/disable")
api("PUT", f"/api/admin/rules/{target_id}/enable")
time.sleep(0.5)

request_body = {
    "type": "ORDER",
    "orderId": "ORD-20260329-001",
    "customer": {"name": "John", "level": "VIP"},
    "items": [
        {"sku": "A1", "qty": 3, "price": 100},
        {"sku": "B2", "qty": 1, "price": 250},
    ],
}

status, resp_body, elapsed, resp_headers = mock_request(
    "/api/v1/orders?status=active&type=vip", "POST",
    {"Content-Type": "application/json", "X-Original-Host": HOST, "X-Tenant": "abc"},
    request_body,
)

print(f"\n  HTTP {status} (網路耗時: {elapsed*1000:.1f}ms)")
try:
    parsed = json.loads(resp_body)
    print(f"  orderId:   {parsed.get('orderId')}")
    print(f"  customer:  {parsed.get('customer')}")
    print(f"  itemCount: {parsed.get('itemCount')}")
except:
    print(f"  body: {resp_body[:200]}")

log_entry = wait_for_log("/api/v1/orders")
if log_entry:
    log = log_entry.get("log", {})
    print(f"\n  匹配時間 (ms):  {log.get('matchTimeMs', '?')}")
    print(f"  回應時間 (ms):  {log.get('responseTimeMs', '?')}")
    print(f"  匹配描述:        {log.get('matchDescription', '?')}")

    chain = log.get("matchChain")
    if chain:
        try:
            items = json.loads(chain) if isinstance(chain, str) else chain
            print(f"\n  匹配鏈長度：{len(items)} 條規則")
            match_count = sum(1 for x in items if x.get("reason") == "match")
            skip_count = sum(1 for x in items if x.get("reason") in ("skipped", "fallback"))
            nomatch_count = sum(1 for x in items if x.get("reason") == "condition_not_match")
            disabled_count = sum(1 for x in items if x.get("reason") == "disabled")
            print(f"    match: {match_count}, condition_not_match: {nomatch_count}, skipped/fallback: {skip_count}, disabled: {disabled_count}")
        except:
            pass
    cold_match = log.get("matchTimeMs")
    cold_response = log.get("responseTimeMs")
else:
    print("\n  ⚠ 找不到日誌")
    cold_match = "?"
    cold_response = "?"

# === 測試 2：Warm Cache ===
api("DELETE", "/api/admin/logs/all")
time.sleep(0.5)

print()
print("=" * 50)
print("  測試 2：Warm Cache（快取命中）")
print("=" * 50)

status, resp_body, elapsed, _ = mock_request(
    "/api/v1/orders?status=active&type=vip", "POST",
    {"Content-Type": "application/json", "X-Original-Host": HOST, "X-Tenant": "abc"},
    request_body,
)

print(f"\n  HTTP {status} (網路耗時: {elapsed*1000:.1f}ms)")

log_entry = wait_for_log("/api/v1/orders")
if log_entry:
    log = log_entry.get("log", {})
    warm_match = log.get("matchTimeMs")
    warm_response = log.get("responseTimeMs")
    print(f"\n  匹配時間 (ms):  {warm_match}")
    print(f"  回應時間 (ms):  {warm_response}")
else:
    print("\n  ⚠ 找不到日誌")
    warm_match = "?"
    warm_response = "?"

# === 測試 3：連續 10 次取平均 ===
api("DELETE", "/api/admin/logs/all")
time.sleep(0.5)

print()
print("=" * 50)
print("  測試 3：連續 10 次請求取平均")
print("=" * 50)

curl_times = []
for i in range(10):
    s, _, e, _ = mock_request(
        "/api/v1/orders?status=active&type=vip", "POST",
        {"Content-Type": "application/json", "X-Original-Host": HOST, "X-Tenant": "abc"},
        request_body,
    )
    curl_times.append(e * 1000)
    time.sleep(0.05)

# 等日誌全部寫入
time.sleep(12)

_, logs = api("GET", "/api/admin/logs?matched=true&protocol=HTTP")
results = logs.get("results", []) if isinstance(logs, dict) else []

match_times = []
response_times = []
for r in results:
    log = r.get("log", {})
    mt = log.get("matchTimeMs")
    rt = log.get("responseTimeMs")
    if mt is not None:
        match_times.append(mt)
    if rt is not None:
        response_times.append(rt)

print(f"\n  請求次數:        {len(curl_times)}")
print(f"  日誌記錄數:      {len(match_times)}")

if match_times:
    print(f"\n  匹配時間 (ms):")
    print(f"    平均:  {sum(match_times)/len(match_times):.1f}")
    print(f"    最小:  {min(match_times)}")
    print(f"    最大:  {max(match_times)}")

if response_times:
    print(f"\n  回應時間 (ms):")
    print(f"    平均:  {sum(response_times)/len(response_times):.1f}")
    print(f"    最小:  {min(response_times)}")
    print(f"    最大:  {max(response_times)}")

if curl_times:
    print(f"\n  網路耗時 (ms):")
    print(f"    平均:  {sum(curl_times)/len(curl_times):.1f}")
    print(f"    最小:  {min(curl_times):.1f}")
    print(f"    最大:  {max(curl_times):.1f}")

# === 總結 ===
print()
print("=" * 50)
print("  總結")
print("=" * 50)
print(f"  規則總數:          {rule_count}")
print(f"  同 path 候選數:    {CANDIDATE_COUNT + 1}")
print(f"  Cold cache 匹配:   {cold_match} ms")
print(f"  Warm cache 匹配:   {warm_match} ms")
if match_times:
    print(f"  10 次平均匹配:     {sum(match_times)/len(match_times):.1f} ms")
print("=" * 50)

# --- 清理 ---
print()
print("清理測試規則...")
api("DELETE", "/api/admin/rules/all")
api("DELETE", "/api/admin/responses/orphans")
api("DELETE", "/api/admin/logs/all")
print("完成")
