#!/usr/bin/env python3
"""
情境一壓力測試：最複雜的 HTTP 匹配路徑

用法：python3 scripts/stress-test-scenario1.py [BASE_URL]
"""

import json
import sys
import time
import urllib.request
import base64

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
ADMIN_API = f"{BASE_URL}/api/admin"
MOCK_API = f"{BASE_URL}/mock"
AUTH = base64.b64encode(b"admin:admin").decode()
HOST = "stress.api.test"


def api(method, path, data=None, auth=True):
    url = f"{BASE_URL}{path}"
    body = json.dumps(data).encode() if data else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json")
    if auth:
        req.add_header("Authorization", f"Basic {AUTH}")
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read().decode() if e.fp else ""
        try:
            return e.code, json.loads(body)
        except:
            return e.code, body


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
            resp_body = resp.read().decode()
            return resp.status, resp_body, elapsed, dict(resp.headers)
    except urllib.error.HTTPError as e:
        elapsed = time.time() - start
        return e.code, e.read().decode(), elapsed, {}


print("=========================================")
print("情境一：多條件匹配 + 模板渲染")
print(f"目標：{BASE_URL}")
print("=========================================")
print()

# --- 建立 6 條規則 ---

rules = [
    {
        "label": "萬用 fallback (matchKey=*)",
        "data": {
            "protocol": "HTTP", "matchKey": "*", "method": "POST",
            "targetHost": HOST, "responseBody": '{"fallback":"wildcard"}',
            "status": 200, "priority": 0, "description": "萬用 fallback",
            "sseEnabled": False,
        },
    },
    {
        "label": "無條件 fallback",
        "data": {
            "protocol": "HTTP", "matchKey": "/api/v1/orders", "method": "POST",
            "targetHost": HOST, "responseBody": '{"fallback":"no-condition"}',
            "status": 200, "priority": 0, "description": "無條件 fallback",
            "sseEnabled": False,
        },
    },
    {
        "label": "不匹配條件規則",
        "data": {
            "protocol": "HTTP", "matchKey": "/api/v1/orders", "method": "POST",
            "targetHost": HOST, "responseBody": '{"wrong":true}',
            "status": 200, "bodyCondition": "type=REFUND",
            "queryCondition": "status=cancelled", "headerCondition": "X-Channel=internal",
            "priority": 0, "description": "不匹配的條件規則",
            "sseEnabled": False,
        },
    },
    {
        "label": "部分匹配規則（header 不符）",
        "data": {
            "protocol": "HTTP", "matchKey": "/api/v1/orders", "method": "POST",
            "targetHost": HOST, "responseBody": '{"partial":true}',
            "status": 200, "bodyCondition": "type=ORDER",
            "queryCondition": "status=active", "headerCondition": "X-Channel=internal",
            "priority": 0, "description": "部分匹配規則（header 不符）",
            "sseEnabled": False,
        },
    },
    {
        "label": "目標規則：全條件 + 模板 ★",
        "data": {
            "protocol": "HTTP", "matchKey": "/api/v1/orders", "method": "POST",
            "targetHost": HOST,
            "responseBody": json.dumps({
                "orderId": "{{jsonPath request.body '$.orderId'}}",
                "customer": "{{jsonPath request.body '$.customer.name'}}",
                "itemCount": "{{size (jsonPath request.body '$.items')}}",
                "path": "{{request.path}}",
                "method": "{{request.method}}",
                "status": "{{request.query.status}}",
                "timestamp": "{{now format='yyyy-MM-dd'}}",
                "traceId": "{{randomValue type='UUID'}}",
            }, ensure_ascii=False),
            "status": 200,
            "bodyCondition": "type=ORDER;customer.name=John;$.items[0].sku=A1",
            "queryCondition": "status=active;type=vip",
            "headerCondition": "Content-Type*=json;X-Tenant=abc",
            "responseHeaders": '{"X-Trace-Id":"echo-trace","X-Matched":"true"}',
            "priority": 0, "description": "目標規則：全條件 + 模板",
            "sseEnabled": False,
        },
    },
    {
        "label": "無 host 規則",
        "data": {
            "protocol": "HTTP", "matchKey": "/api/v1/orders", "method": "POST",
            "responseBody": '{"no-host":true}',
            "status": 200, "priority": 0, "description": "無 host 規則",
            "sseEnabled": False,
        },
    },
]

created_ids = []
for i, rule in enumerate(rules, 1):
    status, body = api("POST", "/api/admin/rules", rule["data"])
    rid = body.get("id", "?") if isinstance(body, dict) else "?"
    created_ids.append(rid)
    marker = " ★" if "目標" in rule["label"] else ""
    print(f"  [{i}/6] {rule['label']}: {rid[:8]}..{marker}")

target_id = created_ids[4]

# --- 清除舊日誌 ---
api("DELETE", "/api/admin/logs/all")

print()
print("--- 發送 Mock 請求 ---")
print()

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
    "/api/v1/orders?status=active&type=vip",
    "POST",
    {
        "Content-Type": "application/json",
        "X-Original-Host": HOST,
        "X-Tenant": "abc",
    },
    request_body,
)

print(f"  HTTP {status} (curl 耗時: {elapsed*1000:.1f}ms)")
print()
try:
    parsed = json.loads(resp_body)
    print("  回應 body:")
    for k, v in parsed.items():
        print(f"    {k}: {v}")
except:
    print(f"  回應 body: {resp_body[:500]}")

print()
if resp_headers.get("X-Trace-Id"):
    print(f"  X-Trace-Id: {resp_headers['X-Trace-Id']}")
if resp_headers.get("X-Matched"):
    print(f"  X-Matched:  {resp_headers['X-Matched']}")

# --- 等日誌寫入（database 模式每 10 秒 flush）---
print()
print("等待日誌寫入 (最多 12 秒)...")
for attempt in range(12):
    time.sleep(1)
    _, check = api("GET", "/api/admin/logs?matched=true&protocol=HTTP")
    check_results = check.get("results", []) if isinstance(check, dict) else []
    if check_results:
        print(f"  日誌已寫入 ({attempt+1}s)")
        break
    sys.stdout.write(".")
    sys.stdout.flush()
else:
    print("\n  超時，嘗試直接查詢...")

# --- 查詢請求日誌 ---
print()
print("=========================================")
print("  查詢匹配時間")
print("=========================================")

_, logs = api("GET", "/api/admin/logs?matched=true&protocol=HTTP")
results = logs.get("results", []) if isinstance(logs, dict) else []

if not results:
    print("  找不到匹配的請求日誌")
else:
    log = results[0].get("log", {})
    rule_info = results[0].get("rule", {})

    print(f"  匹配規則 ID:    {log.get('ruleId', '?')}")
    print(f"  規則描述:        {rule_info.get('description', '?')}")
    print(f"  匹配時間 (ms):  {log.get('matchTimeMs', '?')}")
    print(f"  回應時間 (ms):  {log.get('responseTimeMs', '?')}")
    print(f"  匹配描述:        {log.get('matchDescription', '?')}")
    print(f"  條件:            {log.get('conditionMatched', '?')}")
    print()

    chain = log.get("matchChain")
    if chain:
        try:
            items = json.loads(chain) if isinstance(chain, str) else chain
            print("  匹配鏈明細:")
            for i, item in enumerate(items):
                reason = item.get("reason", "?")
                desc = item.get("description", "")
                rid = item.get("ruleId", "?")[:8]
                mismatch = item.get("mismatch", "")
                line = f"    [{i+1}] {rid}..  {reason:<22} {desc}"
                if mismatch:
                    line += f"\n        ← {mismatch}"
                print(line)
        except:
            print(f"  匹配鏈: {chain}")

print()
print("=========================================")

# --- 清理 ---
print()
print("清理測試規則...")
api("DELETE", "/api/admin/rules/all")
api("DELETE", "/api/admin/responses/orphans")
print("完成")
