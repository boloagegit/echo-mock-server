#!/usr/bin/env python3
"""
Echo vs WireMock RPS 比較測試

用相同的場景、相同的並發數，分別打 Echo 和 WireMock，比較 RPS 和延遲。

場景：
  1. 簡單匹配（無條件）
  2. JSON body 條件匹配（多候選規則）
  3. 小型 XML + XPath 條件（~2KB）
  4. 大型 XML + XPath 條件（~100KB）

用法：python3 scripts/stress-test-vs-wiremock.py [ECHO_URL] [WM_URL] [DURATION] [CONCURRENCY]
"""

import json
import sys
import time
import urllib.request
import base64
import threading
import statistics
from collections import defaultdict

ECHO_URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
WM_URL = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:9090"
DURATION = int(sys.argv[3]) if len(sys.argv) > 3 else 10
CONCURRENCY = int(sys.argv[4]) if len(sys.argv) > 4 else 20

ECHO_AUTH = base64.b64encode(b"admin:admin").decode()
HOST = "perf.api.test"


# === HTTP helpers ===

def echo_api(method, path, data=None):
    url = f"{ECHO_URL}{path}"
    body = json.dumps(data).encode() if data else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json")
    req.add_header("Authorization", f"Basic {ECHO_AUTH}")
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except:
            return e.code, ""


def wm_api(method, path, data=None):
    url = f"{WM_URL}{path}"
    body = json.dumps(data).encode() if data else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode() if e.fp else ""
        try:
            return e.code, json.loads(raw) if raw.strip() else {}
        except:
            return e.code, raw


def generate_xml(item_count, extra_fields=5):
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<Envelope>',
        '  <Header>',
        '    <AuthToken>TOKEN-ABC-123</AuthToken>',
        '    <TraceId>TRACE-001</TraceId>',
        '  </Header>',
        '  <Body>',
        '    <CreateOrderRequest>',
        '      <OrderType>STANDARD</OrderType>',
        '      <Channel>WEB</Channel>',
        '      <Priority>HIGH</Priority>',
        '      <CustomerId>CUST-9876</CustomerId>',
        '      <Items>',
    ]
    for i in range(item_count):
        lines.append(f'        <Item seq="{i+1}">')
        lines.append(f'          <SKU>SKU-{i+1:04d}</SKU>')
        lines.append(f'          <Quantity>{(i%10)+1}</Quantity>')
        lines.append(f'          <Price>{100+i*10}.00</Price>')
        for j in range(extra_fields):
            lines.append(f'          <Attr{j}>val-{i}-{j}</Attr{j}>')
        lines.append(f'        </Item>')
    lines.extend([
        '      </Items>',
        '    </CreateOrderRequest>',
        '  </Body>',
        '</Envelope>',
    ])
    return "\n".join(lines)


# === RPS runner ===

def run_rps(base_url, path, method, headers, body_bytes, duration, concurrency):
    latencies = []
    errors = 0
    lock = threading.Lock()
    stop_event = threading.Event()

    def worker():
        nonlocal errors
        local_lat = []
        local_err = 0
        while not stop_event.is_set():
            url = f"{base_url}{path}"
            req = urllib.request.Request(url, data=body_bytes, method=method)
            for k, v in headers.items():
                req.add_header(k, v)
            start = time.time()
            try:
                with urllib.request.urlopen(req) as resp:
                    resp.read()
                    local_lat.append((time.time() - start) * 1000)
            except urllib.error.HTTPError as e:
                e.read()
                local_lat.append((time.time() - start) * 1000)
                if e.code >= 500:
                    local_err += 1
            except:
                local_err += 1
        with lock:
            latencies.extend(local_lat)
            errors += local_err

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(concurrency)]
    for t in threads:
        t.start()
    time.sleep(duration)
    stop_event.set()
    for t in threads:
        t.join(timeout=5)

    total = len(latencies)
    rps = total / duration if duration > 0 else 0
    if latencies:
        latencies.sort()
        return {
            "total": total, "rps": rps, "errors": errors,
            "avg": statistics.mean(latencies),
            "p50": latencies[int(len(latencies) * 0.50)],
            "p95": latencies[int(len(latencies) * 0.95)],
            "p99": latencies[int(min(len(latencies) * 0.99, len(latencies) - 1))],
            "min": min(latencies), "max": max(latencies),
        }
    return {"total": 0, "rps": 0, "errors": errors, "avg": 0, "p50": 0, "p95": 0, "p99": 0, "min": 0, "max": 0}


# === 場景定義 ===

small_xml = generate_xml(5, 3)
large_xml = generate_xml(200, 8)

SCENARIOS = [
    {
        "name": "簡單 JSON（無條件）",
        "echo_path": "/mock/perf/simple",
        "wm_path": "/perf/simple",
        "method": "POST",
        "body": '{"hello":"world"}',
        "content_type": "application/json",
        "echo_rules": [
            {"protocol": "HTTP", "matchKey": "/perf/simple", "method": "POST",
             "targetHost": HOST, "responseBody": '{"ok":true}', "status": 200,
             "description": "簡單", "sseEnabled": False},
        ],
        "wm_mappings": [
            {"request": {"urlPath": "/perf/simple", "method": "POST"},
             "response": {"status": 200, "body": '{"ok":true}',
                          "headers": {"Content-Type": "application/json"}}},
        ],
    },
    {
        "name": "JSON 多條件（10 候選）",
        "echo_path": "/mock/perf/complex-json?status=active",
        "wm_path": "/perf/complex-json?status=active",
        "method": "POST",
        "body": json.dumps({"type": "ORDER", "orderId": "ORD-001",
                            "customer": {"name": "John"}, "items": [{"sku": "A1"}]}),
        "content_type": "application/json",
        "echo_rules": (
            [{"protocol": "HTTP", "matchKey": "/perf/complex-json", "method": "POST",
              "targetHost": HOST, "responseBody": json.dumps({"wrong": i}), "status": 200,
              "bodyCondition": f"type=NONEXIST_{i}", "queryCondition": f"x={i}",
              "priority": 0, "description": f"候選 #{i}", "sseEnabled": False}
             for i in range(10)]
            + [{"protocol": "HTTP", "matchKey": "/perf/complex-json", "method": "POST",
                "targetHost": HOST, "responseBody": '{"matched":true}', "status": 200,
                "bodyCondition": "type=ORDER;customer.name=John;$.items[0].sku=A1",
                "queryCondition": "status=active", "priority": 0,
                "description": "目標", "sseEnabled": False}]
        ),
        "wm_mappings": (
            [{"priority": 10 - i,
              "request": {"urlPath": "/perf/complex-json", "method": "POST",
                          "bodyPatterns": [{"matchesJsonPath": f"$[?(@.type == 'NONEXIST_{i}')]"}],
                          "queryParameters": {"x": {"equalTo": str(i)}}},
              "response": {"status": 200, "body": json.dumps({"wrong": i})}}
             for i in range(10)]
            + [{"priority": 100,
                "request": {"urlPath": "/perf/complex-json", "method": "POST",
                            "bodyPatterns": [
                                {"matchesJsonPath": "$[?(@.type == 'ORDER')]"},
                                {"matchesJsonPath": "$.customer[?(@.name == 'John')]"},
                                {"matchesJsonPath": "$.items[0][?(@.sku == 'A1')]"},
                            ],
                            "queryParameters": {"status": {"equalTo": "active"}}},
                "response": {"status": 200, "body": '{"matched":true}',
                             "headers": {"Content-Type": "application/json"}}}]
        ),
    },
    {
        "name": f"小型 XML ~{len(small_xml)//1024}KB + XPath",
        "echo_path": "/mock/perf/small-xml",
        "wm_path": "/perf/small-xml",
        "method": "POST",
        "body": small_xml,
        "content_type": "application/xml",
        "echo_rules": (
            [{"protocol": "HTTP", "matchKey": "/perf/small-xml", "method": "POST",
              "targetHost": HOST, "responseBody": f"<wrong>{i}</wrong>", "status": 200,
              "bodyCondition": f"//OrderType=NONEXIST_{i}", "priority": 0,
              "description": f"XML 候選 #{i}", "sseEnabled": False}
             for i in range(10)]
            + [{"protocol": "HTTP", "matchKey": "/perf/small-xml", "method": "POST",
                "targetHost": HOST, "responseBody": "<result>matched</result>", "status": 200,
                "bodyCondition": "//OrderType=STANDARD;//Channel=WEB;//Priority=HIGH",
                "priority": 0, "description": "XML 目標", "sseEnabled": False}]
        ),
        "wm_mappings": (
            [{"priority": 10 - i,
              "request": {"urlPath": "/perf/small-xml", "method": "POST",
                          "bodyPatterns": [{"matchesXPath": f"//OrderType[text()='NONEXIST_{i}']"}]},
              "response": {"status": 200, "body": f"<wrong>{i}</wrong>"}}
             for i in range(10)]
            + [{"priority": 100,
                "request": {"urlPath": "/perf/small-xml", "method": "POST",
                            "bodyPatterns": [
                                {"matchesXPath": "//OrderType[text()='STANDARD']"},
                                {"matchesXPath": "//Channel[text()='WEB']"},
                                {"matchesXPath": "//Priority[text()='HIGH']"},
                            ]},
                "response": {"status": 200, "body": "<result>matched</result>",
                             "headers": {"Content-Type": "application/xml"}}}]
        ),
    },
    {
        "name": f"大型 XML ~{len(large_xml)//1024}KB + XPath",
        "echo_path": "/mock/perf/large-xml",
        "wm_path": "/perf/large-xml",
        "method": "POST",
        "body": large_xml,
        "content_type": "application/xml",
        "echo_rules": (
            [{"protocol": "HTTP", "matchKey": "/perf/large-xml", "method": "POST",
              "targetHost": HOST, "responseBody": f"<wrong>{i}</wrong>", "status": 200,
              "bodyCondition": f"//OrderType=NONEXIST_{i}", "priority": 0,
              "description": f"大型 XML 候選 #{i}", "sseEnabled": False}
             for i in range(10)]
            + [{"protocol": "HTTP", "matchKey": "/perf/large-xml", "method": "POST",
                "targetHost": HOST, "responseBody": "<result>matched</result>", "status": 200,
                "bodyCondition": "//OrderType=STANDARD;//Channel=WEB;//Priority=HIGH",
                "priority": 0, "description": "大型 XML 目標", "sseEnabled": False}]
        ),
        "wm_mappings": (
            [{"priority": 10 - i,
              "request": {"urlPath": "/perf/large-xml", "method": "POST",
                          "bodyPatterns": [{"matchesXPath": f"//OrderType[text()='NONEXIST_{i}']"}]},
              "response": {"status": 200, "body": f"<wrong>{i}</wrong>"}}
             for i in range(10)]
            + [{"priority": 100,
                "request": {"urlPath": "/perf/large-xml", "method": "POST",
                            "bodyPatterns": [
                                {"matchesXPath": "//OrderType[text()='STANDARD']"},
                                {"matchesXPath": "//Channel[text()='WEB']"},
                                {"matchesXPath": "//Priority[text()='HIGH']"},
                            ]},
                "response": {"status": 200, "body": "<result>matched</result>",
                             "headers": {"Content-Type": "application/xml"}}}]
        ),
    },
]


# === 執行 ===

print("=" * 70)
print("  Echo vs WireMock RPS 比較")
print(f"  Echo:       {ECHO_URL}")
print(f"  WireMock:   {WM_URL}")
print(f"  持續: {DURATION}s / 並發: {CONCURRENCY} threads")
print("=" * 70)

all_results = []

for idx, sc in enumerate(SCENARIOS):
    name = sc["name"]
    body_bytes = sc["body"].encode("utf-8")
    body_size = len(body_bytes)

    print(f"\n{'─' * 70}")
    print(f"  [{idx+1}/{len(SCENARIOS)}] {name} ({body_size:,} bytes)")
    print(f"{'─' * 70}")

    # --- Setup Echo ---
    echo_api("DELETE", "/api/admin/rules/all")
    echo_api("DELETE", "/api/admin/responses/orphans")
    for rule in sc["echo_rules"]:
        echo_api("POST", "/api/admin/rules", rule)

    # --- Setup WireMock ---
    wm_api("POST", "/__admin/mappings/reset", {})
    for mapping in sc["wm_mappings"]:
        wm_api("POST", "/__admin/mappings", mapping)

    # --- Warm up ---
    for target, path in [(ECHO_URL, sc["echo_path"]), (WM_URL, sc["wm_path"])]:
        for _ in range(3):
            url = f"{target}{path}"
            req = urllib.request.Request(url, data=body_bytes, method=sc["method"])
            req.add_header("Content-Type", sc["content_type"])
            if target == ECHO_URL:
                req.add_header("X-Original-Host", HOST)
            try:
                with urllib.request.urlopen(req) as r:
                    r.read()
            except:
                pass
    time.sleep(0.5)

    # --- Test Echo ---
    echo_headers = {"Content-Type": sc["content_type"], "X-Original-Host": HOST}
    sys.stdout.write(f"  Echo     測試中 ({DURATION}s)...")
    sys.stdout.flush()
    echo_result = run_rps(ECHO_URL, sc["echo_path"], sc["method"],
                          echo_headers, body_bytes, DURATION, CONCURRENCY)
    print(f" {echo_result['rps']:,.0f} RPS")

    # --- Test WireMock ---
    wm_headers = {"Content-Type": sc["content_type"]}
    sys.stdout.write(f"  WireMock 測試中 ({DURATION}s)...")
    sys.stdout.flush()
    wm_result = run_rps(WM_URL, sc["wm_path"], sc["method"],
                        wm_headers, body_bytes, DURATION, CONCURRENCY)
    print(f" {wm_result['rps']:,.0f} RPS")

    ratio = echo_result["rps"] / wm_result["rps"] if wm_result["rps"] > 0 else 0

    print(f"\n  {'':>12} {'RPS':>8} {'avg':>8} {'p50':>8} {'p95':>8} {'p99':>8}")
    print(f"  {'─'*12} {'─'*8} {'─'*8} {'─'*8} {'─'*8} {'─'*8}")
    print(f"  {'Echo':>12} {echo_result['rps']:>7,.0f} {echo_result['avg']:>7.1f}ms {echo_result['p50']:>7.1f}ms {echo_result['p95']:>7.1f}ms {echo_result['p99']:>7.1f}ms")
    print(f"  {'WireMock':>12} {wm_result['rps']:>7,.0f} {wm_result['avg']:>7.1f}ms {wm_result['p50']:>7.1f}ms {wm_result['p95']:>7.1f}ms {wm_result['p99']:>7.1f}ms")
    print(f"  {'比值':>12} {ratio:>7.2f}x")

    all_results.append({
        "name": name, "body_size": body_size,
        "echo": echo_result, "wm": wm_result, "ratio": ratio,
    })

# === 總結 ===
print()
print("=" * 70)
print("  總結：Echo vs WireMock")
print("=" * 70)
print(f"  {'場景':<30} {'Body':>6} {'Echo RPS':>10} {'WM RPS':>10} {'比值':>8} {'Echo p95':>10} {'WM p95':>10}")
print(f"  {'─'*30} {'─'*6} {'─'*10} {'─'*10} {'─'*8} {'─'*10} {'─'*10}")
for r in all_results:
    bl = f"{r['body_size']//1024}KB" if r['body_size'] >= 1024 else f"{r['body_size']}B"
    print(f"  {r['name']:<30} {bl:>6} {r['echo']['rps']:>9,.0f} {r['wm']['rps']:>9,.0f} {r['ratio']:>7.2f}x {r['echo']['p95']:>9.1f}ms {r['wm']['p95']:>9.1f}ms")
print("=" * 70)

# --- 清理 ---
echo_api("DELETE", "/api/admin/rules/all")
echo_api("DELETE", "/api/admin/responses/orphans")
echo_api("DELETE", "/api/admin/logs/all")
wm_api("POST", "/__admin/mappings/reset", {})
print("\n完成")
