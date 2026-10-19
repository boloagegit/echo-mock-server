#!/usr/bin/env python3
"""
JMS 2000 rule matching performance test (ServiceName + CustId condition)

Create 2000 JMS rules, most of which contain //ServiceName + //CustId conditions,
Send the XML message 10 times and take matchTimeMs to observe the optimization effect.

Usage: python3 scripts/bench-2000-jms.py [BASE_URL]
"""

import json, os, sys, time, urllib.request, base64

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
AUTH = base64.b64encode(
    f"{os.getenv('ECHO_TEST_USERNAME', 'admin')}:{os.getenv('ECHO_TEST_PASSWORD', 'admin')}".encode()
).decode()
QUEUE = "ECHO.REQUEST"
TOTAL_RULES = 2000
RUNS = 10
# All rules use the same ServiceName, the handicap bucket cannot be filtered, and all candidates are forced to be traversed
SAME_SERVICE = True


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


def wait_jms_log(timeout=15):
    for _ in range(timeout):
        time.sleep(1)
        _, logs = api("GET", "/api/admin/logs?protocol=JMS")
        results = logs.get("results", []) if isinstance(logs, dict) else []
        if results:
            return results[0]
    return None


XML_BODY = """<?xml version="1.0" encoding="UTF-8"?>
<ServiceRequest>
  <Header>
    <ServiceName>OrderService</ServiceName>
    <Operation>CreateOrder</Operation>
    <TransactionId>TXN-20260401-001</TransactionId>
    <Timestamp>2026-04-01T10:00:00Z</Timestamp>
    <SourceSystem>CRM</SourceSystem>
  </Header>
  <Body>
    <CustId>CUST-9876</CustId>
    <OrderType>STANDARD</OrderType>
    <Channel>WEB</Channel>
    <Priority>HIGH</Priority>
    <CustomerName>John Doe</CustomerName>
    <Region>APAC</Region>
    <Currency>TWD</Currency>
    <Amount>15000.00</Amount>
    <ProductCode>PROD-A001</ProductCode>
    <Quantity>5</Quantity>
  </Body>
</ServiceRequest>"""

xml_size = len(XML_BODY.encode("utf-8"))

print("=" * 60)
print("  JMS 2000 規則匹配效能測試")
print(f"  目標：{BASE_URL}")
print(f"  規則數：{TOTAL_RULES}，測試次數：{RUNS}")
print(f"  條件模式：//ServiceName=xxx;//CustId=yyy")
print(f"  XML body：{xml_size:,} bytes")
print("=" * 60)

# Confirm JMS
_, status = api("GET", "/api/admin/status")
if not status.get("jmsEnabled"):
    print("\n⚠ JMS 未啟用")
    sys.exit(1)
print(f"\n  JMS 已啟用")

# Cleanup
print("\n  清理舊資料...")
api("DELETE", "/api/admin/rules/all")
api("DELETE", "/api/admin/responses/orphans")
api("DELETE", "/api/admin/logs/all")
time.sleep(0.5)

# The target rule is established first (createdAt is the oldest → sorted last, forcing all candidates to be traversed)
print(f"  建立 {TOTAL_RULES} 條規則...")
api("POST", "/api/admin/rules", {
    "protocol": "JMS",
    "matchKey": QUEUE,
    "responseBody": "<result>matched</result>",
    "bodyCondition": "//ServiceName=OrderService;//CustId=CUST-9876",
    "priority": 0,
    "description": "目標規則（最舊）",
})
time.sleep(0.3)

# Create 1999 non-matching rules (ServiceName is the same, CustId is different)
BATCH = 100
created = 0
for batch_start in range(0, TOTAL_RULES - 1, BATCH):
    batch_end = min(batch_start + BATCH, TOTAL_RULES - 1)
    batch = []
    for i in range(batch_start, batch_end):
        svc = "OrderService" if SAME_SERVICE else f"Service_{i % 200}"
        cust = f"CUST-{i:04d}"
        batch.append({
            "protocol": "JMS",
            "matchKey": QUEUE,
            "responseBody": f"<wrong>{i}</wrong>",
            "bodyCondition": f"//ServiceName={svc};//CustId={cust}",
            "priority": 0,
            "description": f"候選 #{i} ({svc}, {cust})",
        })
    api("POST", "/api/admin/rules/import-batch", batch)
    created += len(batch)
    sys.stdout.write(f"\r  建立中... {created}/{TOTAL_RULES - 1}")
    sys.stdout.flush()

print(f"\r  建立完成：{created + 1} 條規則                ")

# Cache rebuild triggered
_, rules = api("GET", "/api/admin/rules")
if isinstance(rules, list) and rules:
    fid = rules[0].get("id")
    if fid:
        api("PUT", f"/api/admin/rules/{fid}/disable")
        api("PUT", f"/api/admin/rules/{fid}/enable")
time.sleep(1)

# Send test
print(f"\n  開始測試（{RUNS} 次）...")
match_times = []
response_times = []

for run in range(RUNS):
    api("DELETE", "/api/admin/logs/all")
    time.sleep(0.3)

    api("POST", "/api/admin/jms/test", XML_BODY, content_type="application/xml")

    entry = wait_jms_log(timeout=15)
    if entry:
        log = entry.get("log", {})
        mt = log.get("matchTimeMs")
        rt = log.get("responseTimeMs")
        matched = log.get("matched", False)

        if mt is not None:
            match_times.append(mt)
        if rt is not None:
            response_times.append(rt)

        status_icon = "✓" if matched else "✗"
        print(f"    #{run+1:2d}  match={mt}ms  resp={rt}ms  {status_icon}")

        if run == 0:
            chain_str = log.get("matchChain", "")
            chain_len = 0
            if chain_str:
                try:
                    chain_len = len(json.loads(chain_str))
                except:
                    pass
            if chain_len:
                print(f"         匹配鏈長度：{chain_len}")
    else:
        print(f"    #{run+1:2d}  ⚠ 超時")

# Summary
print()
print("=" * 60)
if match_times:
    avg = sum(match_times) / len(match_times)
    mn = min(match_times)
    mx = max(match_times)
    avg_r = sum(response_times) / len(response_times) if response_times else 0
    print(f"  規則數：{TOTAL_RULES}")
    print(f"  匹配時間：平均 {avg:.1f}ms，最小 {mn}ms，最大 {mx}ms")
    print(f"  回應時間：平均 {avg_r:.1f}ms")
    print(f"  全部數據：{match_times}")
else:
    print("  ⚠ 未取得匹配時間")
print("=" * 60)

# Cleanup
print("\n清理...")
api("DELETE", "/api/admin/rules/all")
api("DELETE", "/api/admin/responses/orphans")
api("DELETE", "/api/admin/logs/all")
print("完成")
