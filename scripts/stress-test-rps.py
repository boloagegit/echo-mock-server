#!/usr/bin/env python3
"""
RPS 壓力測試：測量 Echo Mock Server 的吞吐量

測試場景：
  1. 簡單 JSON（無條件匹配）
  2. 複雜 JSON（多條件 + 模板渲染）
  3. 小型 XML（~3KB + XPath 條件）
  4. 大型 XML（~50KB + XPath 條件）

每個場景用多執行緒並發打，測量 RPS、延遲分佈。

用法：python3 scripts/stress-test-rps.py [BASE_URL] [DURATION_SEC] [CONCURRENCY]
"""

import json
import sys
import time
import urllib.request
import base64
import threading
import statistics
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
DURATION = int(sys.argv[2]) if len(sys.argv) > 2 else 10
CONCURRENCY = int(sys.argv[3]) if len(sys.argv) > 3 else 20
AUTH = base64.b64encode(b"admin:admin").decode()
HOST = "rps-stress.api.test"


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


def generate_xml(item_count, extra_fields=5):
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ord="http://example.com/orders">',
        '  <soapenv:Header>',
        '    <ord:AuthToken>TOKEN-ABC-123</ord:AuthToken>',
        '    <ord:TraceId>TRACE-001</ord:TraceId>',
        '  </soapenv:Header>',
        '  <soapenv:Body>',
        '    <ord:CreateOrderRequest>',
        '      <ord:OrderType>STANDARD</ord:OrderType>',
        '      <ord:Channel>WEB</ord:Channel>',
        '      <ord:Priority>HIGH</ord:Priority>',
        '      <ord:CustomerId>CUST-9876</ord:CustomerId>',
        '      <ord:Items>',
    ]
    for i in range(item_count):
        lines.append(f'        <ord:Item seq="{i+1}">')
        lines.append(f'          <ord:SKU>SKU-{i+1:04d}</ord:SKU>')
        lines.append(f'          <ord:Quantity>{(i%10)+1}</ord:Quantity>')
        lines.append(f'          <ord:Price>{100+i*10}.00</ord:Price>')
        for j in range(extra_fields):
            lines.append(f'          <ord:Attr{j}>val-{i}-{j}</ord:Attr{j}>')
        lines.append(f'        </ord:Item>')
    lines.extend([
        '      </ord:Items>',
        '    </ord:CreateOrderRequest>',
        '  </soapenv:Body>',
        '</soapenv:Envelope>',
    ])
    return "\n".join(lines)


# === 測試場景定義 ===

SCENARIOS = []

# 場景 1：簡單 JSON，無條件
SCENARIOS.append({
    "name": "簡單 JSON（無條件）",
    "path": "/rps/simple-json",
    "rules": [
        {
            "protocol": "HTTP", "matchKey": "/rps/simple-json", "method": "POST",
            "targetHost": HOST, "responseBody": '{"ok":true}',
            "status": 200, "description": "簡單 JSON", "sseEnabled": False,
        },
    ],
    "request_headers": {"Content-Type": "application/json", "X-Original-Host": HOST},
    "request_body": '{"hello":"world"}',
})

# 場景 2：複雜 JSON，多條件 + 模板
template_body = json.dumps({
    "orderId": "{{jsonPath request.body '$.orderId'}}",
    "customer": "{{jsonPath request.body '$.customer.name'}}",
    "itemCount": "{{size (jsonPath request.body '$.items')}}",
    "timestamp": "{{now format='yyyy-MM-dd'}}",
    "traceId": "{{randomValue type='UUID'}}",
}, ensure_ascii=False)

complex_json_body = json.dumps({
    "type": "ORDER", "orderId": "ORD-001",
    "customer": {"name": "John", "level": "VIP"},
    "items": [{"sku": "A1", "qty": 3}, {"sku": "B2", "qty": 1}],
})

# 候選規則（條件不匹配）+ 目標規則
complex_json_rules = []
for i in range(10):
    complex_json_rules.append({
        "protocol": "HTTP", "matchKey": "/rps/complex-json", "method": "POST",
        "targetHost": HOST, "responseBody": json.dumps({"wrong": i}),
        "status": 200, "bodyCondition": f"type=NONEXIST_{i}",
        "queryCondition": f"x={i}", "priority": 0,
        "description": f"JSON 候選 #{i}", "sseEnabled": False,
    })
complex_json_rules.append({
    "protocol": "HTTP", "matchKey": "/rps/complex-json", "method": "POST",
    "targetHost": HOST, "responseBody": template_body,
    "status": 200,
    "bodyCondition": "type=ORDER;customer.name=John;$.items[0].sku=A1",
    "queryCondition": "status=active",
    "headerCondition": "Content-Type*=json;X-Tenant=abc",
    "responseHeaders": '{"X-Matched":"true"}',
    "priority": 0, "description": "JSON 目標（模板）", "sseEnabled": False,
})

SCENARIOS.append({
    "name": "複雜 JSON（10 候選 + 模板渲染）",
    "path": "/rps/complex-json",
    "rules": complex_json_rules,
    "request_headers": {
        "Content-Type": "application/json",
        "X-Original-Host": HOST, "X-Tenant": "abc",
    },
    "request_body": complex_json_body,
    "query": "?status=active",
})

# 場景 3：小型 XML ~3KB
small_xml = generate_xml(5, 3)
small_xml_rules = []
for i in range(10):
    small_xml_rules.append({
        "protocol": "HTTP", "matchKey": "/rps/small-xml", "method": "POST",
        "targetHost": HOST, "responseBody": f"<wrong>{i}</wrong>",
        "status": 200, "bodyCondition": f"//OrderType=NONEXIST_{i}",
        "priority": 0, "description": f"XML 候選 #{i}", "sseEnabled": False,
    })
small_xml_rules.append({
    "protocol": "HTTP", "matchKey": "/rps/small-xml", "method": "POST",
    "targetHost": HOST, "responseBody": "<result>matched</result>",
    "status": 200, "bodyCondition": "//OrderType=STANDARD;//Channel=WEB;//Priority=HIGH",
    "priority": 0, "description": "小型 XML 目標", "sseEnabled": False,
})

SCENARIOS.append({
    "name": f"小型 XML（~{len(small_xml)//1024}KB + XPath）",
    "path": "/rps/small-xml",
    "rules": small_xml_rules,
    "request_headers": {"Content-Type": "application/xml", "X-Original-Host": HOST},
    "request_body": small_xml,
})

# 場景 4：大型 XML ~50KB
large_xml = generate_xml(200, 8)
large_xml_rules = []
for i in range(10):
    large_xml_rules.append({
        "protocol": "HTTP", "matchKey": "/rps/large-xml", "method": "POST",
        "targetHost": HOST, "responseBody": f"<wrong>{i}</wrong>",
        "status": 200, "bodyCondition": f"//OrderType=NONEXIST_{i}",
        "priority": 0, "description": f"大型 XML 候選 #{i}", "sseEnabled": False,
    })
large_xml_rules.append({
    "protocol": "HTTP", "matchKey": "/rps/large-xml", "method": "POST",
    "targetHost": HOST, "responseBody": "<result>matched</result>",
    "status": 200, "bodyCondition": "//OrderType=STANDARD;//Channel=WEB;//Priority=HIGH",
    "priority": 0, "description": "大型 XML 目標", "sseEnabled": False,
})

SCENARIOS.append({
    "name": f"大型 XML（~{len(large_xml)//1024}KB + XPath）",
    "path": "/rps/large-xml",
    "rules": large_xml_rules,
    "request_headers": {"Content-Type": "application/xml", "X-Original-Host": HOST},
    "request_body": large_xml,
})


def run_scenario(scenario):
    """執行單一場景的 RPS 測試"""
    name = scenario["name"]
    path = scenario["path"]
    query = scenario.get("query", "")
    headers = scenario["request_headers"]
    body_str = scenario["request_body"]
    body_bytes = body_str.encode("utf-8")

    # 建立規則
    for rule in scenario["rules"]:
        api("POST", "/api/admin/rules", rule)

    # warm up（2 次）
    for _ in range(2):
        url = f"{BASE_URL}/mock{path}{query}"
        req = urllib.request.Request(url, data=body_bytes, method="POST")
        for k, v in headers.items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req) as resp:
                resp.read()
        except:
            pass
    time.sleep(0.3)

    # 並發測試
    latencies = []
    errors = 0
    status_codes = defaultdict(int)
    lock = threading.Lock()
    stop_event = threading.Event()

    def worker():
        nonlocal errors
        local_latencies = []
        local_errors = 0
        local_status = defaultdict(int)

        while not stop_event.is_set():
            url = f"{BASE_URL}/mock{path}{query}"
            req = urllib.request.Request(url, data=body_bytes, method="POST")
            for k, v in headers.items():
                req.add_header(k, v)
            start = time.time()
            try:
                with urllib.request.urlopen(req) as resp:
                    resp.read()
                    elapsed = (time.time() - start) * 1000
                    local_latencies.append(elapsed)
                    local_status[resp.status] += 1
            except urllib.error.HTTPError as e:
                elapsed = (time.time() - start) * 1000
                local_latencies.append(elapsed)
                local_status[e.code] += 1
                if e.code >= 500:
                    local_errors += 1
            except Exception:
                local_errors += 1

        with lock:
            latencies.extend(local_latencies)
            errors += local_errors
            for k, v in local_status.items():
                status_codes[k] += v

    threads = []
    for _ in range(CONCURRENCY):
        t = threading.Thread(target=worker, daemon=True)
        t.start()
        threads.append(t)

    time.sleep(DURATION)
    stop_event.set()
    for t in threads:
        t.join(timeout=5)

    # 計算結果
    total = len(latencies)
    rps = total / DURATION if DURATION > 0 else 0

    if latencies:
        latencies.sort()
        avg = statistics.mean(latencies)
        p50 = latencies[int(len(latencies) * 0.50)]
        p95 = latencies[int(len(latencies) * 0.95)]
        p99 = latencies[int(min(len(latencies) * 0.99, len(latencies) - 1))]
        mn = min(latencies)
        mx = max(latencies)
    else:
        avg = p50 = p95 = p99 = mn = mx = 0

    return {
        "name": name,
        "total": total,
        "rps": rps,
        "errors": errors,
        "avg": avg,
        "p50": p50,
        "p95": p95,
        "p99": p99,
        "min": mn,
        "max": mx,
        "status_codes": dict(status_codes),
        "body_size": len(body_bytes),
    }


# === 執行 ===

print("=" * 65)
print(f"  RPS 壓力測試")
print(f"  目標: {BASE_URL}")
print(f"  持續: {DURATION}s / 並發: {CONCURRENCY} threads")
print("=" * 65)

# 清理
api("DELETE", "/api/admin/rules/all")
api("DELETE", "/api/admin/responses/orphans")
api("DELETE", "/api/admin/logs/all")
time.sleep(0.5)

all_results = []

for i, scenario in enumerate(SCENARIOS):
    print(f"\n{'─' * 65}")
    print(f"  [{i+1}/{len(SCENARIOS)}] {scenario['name']}")
    print(f"  Body: {len(scenario['request_body']):,} bytes")
    print(f"  規則數: {len(scenario['rules'])}")
    sys.stdout.write(f"  測試中 ({DURATION}s)...")
    sys.stdout.flush()

    result = run_scenario(scenario)
    all_results.append(result)

    print(f" 完成")
    print(f"\n  RPS:     {result['rps']:,.0f}")
    print(f"  總請求:  {result['total']:,}")
    print(f"  錯誤:    {result['errors']}")
    print(f"  延遲 (ms):")
    print(f"    avg: {result['avg']:.1f}  p50: {result['p50']:.1f}  p95: {result['p95']:.1f}  p99: {result['p99']:.1f}")
    print(f"    min: {result['min']:.1f}  max: {result['max']:.1f}")

    # 清理規則（保持乾淨）
    api("DELETE", "/api/admin/rules/all")
    api("DELETE", "/api/admin/responses/orphans")
    time.sleep(0.3)

# === 總結 ===
print()
print("=" * 65)
print("  總結")
print("=" * 65)
print(f"  {'場景':<35} {'Body':>8} {'RPS':>8} {'avg':>7} {'p50':>7} {'p95':>7} {'p99':>7}")
print(f"  {'─'*35} {'─'*8} {'─'*8} {'─'*7} {'─'*7} {'─'*7} {'─'*7}")
for r in all_results:
    body_label = f"{r['body_size']//1024}KB" if r['body_size'] >= 1024 else f"{r['body_size']}B"
    print(f"  {r['name']:<35} {body_label:>8} {r['rps']:>7,.0f} {r['avg']:>6.1f}ms {r['p50']:>6.1f}ms {r['p95']:>6.1f}ms {r['p99']:>6.1f}ms")
print("=" * 65)

# 清理
api("DELETE", "/api/admin/logs/all")
print("\n完成")
