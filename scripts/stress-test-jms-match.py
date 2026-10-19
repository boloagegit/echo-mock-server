#!/usr/bin/env python3
"""
JMS matching performance test: 2000 rules + 20 fields XML body

Test strategy:
  - Create 2000 JMS rules (same queue)
  - Most of them have XPath condition but do not match (simulating match chain traversal)
  - 1 target rule (XPath condition match)
  - Tested with different number of candidate rules: 10 / 50 / 100 / 200 / 500 / 1000 / 2000
  - Send XML message through JMS test API, query the log to obtain matchTimeMs

Usage: python3 scripts/stress-test-jms-match.py [BASE_URL]
"""

import json
import os
import sys
import time
import urllib.request
import base64

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
AUTH = base64.b64encode(
    f"{os.getenv('ECHO_TEST_USERNAME', 'admin')}:{os.getenv('ECHO_TEST_PASSWORD', 'admin')}".encode()
).decode()
QUEUE = "ECHO.REQUEST"


def api(method, path, data=None, content_type="application/json"):
    url = f"{BASE_URL}{path}"
    body = json.dumps(data).encode() if data and content_type == "application/json" else (data.encode() if data else None)
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", content_type)
    req.add_header("Accept", "application/json")
    req.add_header("Authorization", f"Basic {AUTH}")
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


def generate_xml_body(field_count=20):
    """Generate 20-field XML message (emulate ESB message)"""
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<ServiceRequest>',
        '  <Header>',
        '    <ServiceName>OrderService</ServiceName>',
        '    <Operation>CreateOrder</Operation>',
        '    <TransactionId>TXN-20260330-001</TransactionId>',
        '    <Timestamp>2026-03-30T10:00:00Z</Timestamp>',
        '    <SourceSystem>CRM</SourceSystem>',
        '  </Header>',
        '  <Body>',
        '    <OrderType>STANDARD</OrderType>',
        '    <Channel>WEB</Channel>',
        '    <Priority>HIGH</Priority>',
        '    <CustomerId>CUST-9876</CustomerId>',
        '    <CustomerName>John Doe</CustomerName>',
        '    <Region>APAC</Region>',
        '    <Currency>TWD</Currency>',
        '    <Amount>15000.00</Amount>',
        '    <ProductCode>PROD-A001</ProductCode>',
        '    <Quantity>5</Quantity>',
        '    <ShippingMethod>EXPRESS</ShippingMethod>',
        '    <PaymentMethod>CREDIT_CARD</PaymentMethod>',
        '    <DiscountCode>VIP2026</DiscountCode>',
        '    <Notes>Rush order - handle with care</Notes>',
        '    <Status>NEW</Status>',
    ]
    # Fill in the specified number of fields
    for i in range(field_count - 15):
        lines.append(f'    <ExtField{i+1}>ExtValue{i+1}</ExtField{i+1}>')
    lines.extend([
        '  </Body>',
        '</ServiceRequest>',
    ])
    return "\n".join(lines)


def wait_for_jms_log(timeout=15):
    """Waiting for JMS log to be written"""
    for _ in range(timeout):
        time.sleep(1)
        _, logs = api("GET", "/api/admin/logs?protocol=JMS")
        results = logs.get("results", []) if isinstance(logs, dict) else []
        if results:
            return results[0]
    return None


# Number of candidate rules tested
CANDIDATE_COUNTS = [10, 50, 100, 200, 500, 1000, 2000]

xml_body = generate_xml_body(20)
xml_size = len(xml_body.encode("utf-8"))

print("=" * 60)
print("  JMS 匹配效能測試")
print(f"  目標：{BASE_URL}")
print(f"  Queue：{QUEUE}")
print(f"  XML body：{xml_size:,} bytes, 20 欄位")
print(f"  測試候選數：{CANDIDATE_COUNTS}")
print("=" * 60)

# Confirm JMS enabled
_, status = api("GET", "/api/admin/status")
if not status.get("jmsEnabled"):
    print("\n  ⚠ JMS 未啟用，請設定 echo.jms.enabled=true")
    sys.exit(1)
print(f"\n  JMS 已啟用，Artemis: {status.get('artemisBrokerUrl', '?')}")

all_results = []

for candidate_count in CANDIDATE_COUNTS:
    print(f"\n{'─' * 60}")
    print(f"  候選規則數：{candidate_count}")
    print(f"{'─' * 60}")

    # Cleanup
    api("DELETE", "/api/admin/rules/all")
    api("DELETE", "/api/admin/responses/orphans")
    api("DELETE", "/api/admin/logs/all")
    time.sleep(0.5)

    # Batch creation candidate rule (XPath condition does not match)
    sys.stdout.write(f"  建立 {candidate_count} 條規則...")
    sys.stdout.flush()

    BATCH = 100
    created = 0
    for batch_start in range(0, candidate_count - 1, BATCH):
        batch_end = min(batch_start + BATCH, candidate_count - 1)
        batch = []
        for i in range(batch_start, batch_end):
            # Use different ServiceName values for each rule (simulating 500 endpoint)
            # There are only a few rules in each bucket after bucketing.
            svc_name = f"Service_{i % 500}"
            batch.append({
                "protocol": "JMS",
                "matchKey": QUEUE,
                "responseBody": f"<wrong>{i}</wrong>",
                "bodyCondition": f"//ServiceName={svc_name};//OrderType=NONEXIST_{i}",
                "priority": 0,
                "description": f"JMS 候選 #{i}",
            })
        api("POST", "/api/admin/rules/import-batch", batch)
        created += len(batch)

    # Create target rule (created last, createdAt is the latest when sorting)
    api("POST", "/api/admin/rules", {
        "protocol": "JMS",
        "matchKey": QUEUE,
        "responseBody": "<result>matched</result>",
        "bodyCondition": "//ServiceName=OrderService;//OrderType=STANDARD;//Channel=WEB;//Priority=HIGH",
        "priority": 0,
        "description": "JMS 目標規則",
    })
    print(f" 完成 ({created + 1} 條)")

    # Clear cache
    _, rules = api("GET", "/api/admin/rules")
    if isinstance(rules, list) and rules:
        first_id = rules[0].get("id")
        if first_id:
            api("PUT", f"/api/admin/rules/{first_id}/disable")
            api("PUT", f"/api/admin/rules/{first_id}/enable")
    time.sleep(0.5)

    # Send JMS test message 5 times and get the matching time
    match_times = []
    response_times = []

    for run in range(5):
        api("DELETE", "/api/admin/logs/all")
        time.sleep(0.3)

        # Send JMS test message
        api("POST", "/api/admin/jms/test", xml_body, content_type="application/xml")

        # Waiting for log
        log_entry = wait_for_jms_log(timeout=15)
        if log_entry:
            log = log_entry.get("log", {})
            mt = log.get("matchTimeMs")
            rt = log.get("responseTimeMs")
            matched = log.get("matched", False)
            if mt is not None:
                match_times.append(mt)
            if rt is not None:
                response_times.append(rt)

            if run == 0:
                # First print details
                desc = log.get("matchDescription", "")
                chain_str = log.get("matchChain", "")
                chain_len = 0
                if chain_str:
                    try:
                        chain_len = len(json.loads(chain_str))
                    except:
                        pass
                print(f"  匹配結果：{'✓' if matched else '✗'}")
                print(f"  匹配描述：{desc[:80]}")
                if chain_len:
                    print(f"  匹配鏈長度：{chain_len}")

    if match_times:
        avg_match = sum(match_times) / len(match_times)
        avg_resp = sum(response_times) / len(response_times) if response_times else 0
        print(f"\n  匹配時間 (5 次): {match_times}")
        print(f"    平均: {avg_match:.1f} ms, 最小: {min(match_times)}, 最大: {max(match_times)}")
        print(f"  回應時間 (5 次): {response_times}")
        print(f"    平均: {avg_resp:.1f} ms")
        all_results.append({
            "candidates": candidate_count,
            "match_times": match_times,
            "avg_match": avg_match,
            "min_match": min(match_times),
            "max_match": max(match_times),
            "avg_resp": avg_resp,
        })
    else:
        print("\n  ⚠ 未取得匹配時間")
        all_results.append({
            "candidates": candidate_count,
            "match_times": [],
            "avg_match": -1, "min_match": -1, "max_match": -1, "avg_resp": -1,
        })

# === Summary ===
print()
print("=" * 60)
print("  總結：JMS 匹配效能 vs 候選規則數")
print(f"  XML body: {xml_size:,} bytes, 20 欄位")
print("=" * 60)
print(f"  {'候選數':>8} {'平均匹配':>10} {'最小':>8} {'最大':>8} {'平均回應':>10}")
print(f"  {'─'*8} {'─'*10} {'─'*8} {'─'*8} {'─'*10}")
for r in all_results:
    if r["avg_match"] >= 0:
        print(f"  {r['candidates']:>8} {r['avg_match']:>9.1f}ms {r['min_match']:>7}ms {r['max_match']:>7}ms {r['avg_resp']:>9.1f}ms")
    else:
        print(f"  {r['candidates']:>8} {'N/A':>10} {'N/A':>8} {'N/A':>8} {'N/A':>10}")
print("=" * 60)

# Cleanup
print()
print("清理...")
api("DELETE", "/api/admin/rules/all")
api("DELETE", "/api/admin/responses/orphans")
api("DELETE", "/api/admin/logs/all")
print("完成")
