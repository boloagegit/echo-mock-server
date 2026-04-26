#!/usr/bin/env python3
"""
JMS 2000 規則匹配效能測試（ServiceName + CustId 條件）

建立 2000 條 JMS 規則，大部分含 //ServiceName + //CustId 條件，
發送 10 次 XML 訊息，取 matchTimeMs 觀察優化效果。

用法：python3 scripts/bench-2000-jms.py [BASE_URL]
"""

import json, sys, time, urllib.request, base64

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
AUTH = base64.b64encode(b"admin:admin").decode()
QUEUE = "ECHO.REQUEST"
TOTAL_RULES = 2000
RUNS = 10
# 所有規則用相同 ServiceName，讓分桶無法過濾，強制遍歷全部候選
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

# 確認 JMS
_, status = api("GET", "/api/admin/status")
if not status.get("jmsEnabled"):
    print("\n⚠ JMS 未啟用")
    sys.exit(1)
print(f"\n  JMS 已啟用")

# 清理
print("\n  清理舊資料...")
api("DELETE", "/api/admin/rules/all")
api("DELETE", "/api/admin/responses/orphans")
api("DELETE", "/api/admin/logs/all")
time.sleep(0.5)

# 目標規則先建立（createdAt 最舊 → 排序最後，強制遍歷全部候選）
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

# 建立 1999 條不匹配規則（ServiceName 相同，CustId 不同）
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

# 觸發快取重建
_, rules = api("GET", "/api/admin/rules")
if isinstance(rules, list) and rules:
    fid = rules[0].get("id")
    if fid:
        api("PUT", f"/api/admin/rules/{fid}/disable")
        api("PUT", f"/api/admin/rules/{fid}/enable")
time.sleep(1)

# 發送測試
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

# 總結
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

# 清理
print("\n清理...")
api("DELETE", "/api/admin/rules/all")
api("DELETE", "/api/admin/responses/orphans")
api("DELETE", "/api/admin/logs/all")
print("完成")
