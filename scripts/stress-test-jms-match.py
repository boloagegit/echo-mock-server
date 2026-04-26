#!/usr/bin/env python3
"""
JMS 匹配效能測試：2000 條規則 + 20 欄位 XML body

測試策略：
  - 建立 2000 條 JMS 規則（同一個 queue）
  - 其中大部分有 XPath 條件但不匹配（模擬匹配鏈遍歷）
  - 1 條目標規則（XPath 條件匹配）
  - 用不同的候選規則數量測試：10 / 50 / 100 / 200 / 500 / 1000 / 2000
  - 透過 JMS test API 發送 XML 訊息，查詢日誌取得 matchTimeMs

用法：python3 scripts/stress-test-jms-match.py [BASE_URL]
"""

import json
import sys
import time
import urllib.request
import base64

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
AUTH = base64.b64encode(b"admin:admin").decode()
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
    """產生 20 欄位的 XML 訊息（模擬 ESB 訊息）"""
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
    # 補到指定欄位數
    for i in range(field_count - 15):
        lines.append(f'    <ExtField{i+1}>ExtValue{i+1}</ExtField{i+1}>')
    lines.extend([
        '  </Body>',
        '</ServiceRequest>',
    ])
    return "\n".join(lines)


def wait_for_jms_log(timeout=15):
    """等待 JMS 日誌寫入"""
    for _ in range(timeout):
        time.sleep(1)
        _, logs = api("GET", "/api/admin/logs?protocol=JMS")
        results = logs.get("results", []) if isinstance(logs, dict) else []
        if results:
            return results[0]
    return None


# 測試的候選規則數量
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

# 確認 JMS 啟用
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

    # 清理
    api("DELETE", "/api/admin/rules/all")
    api("DELETE", "/api/admin/responses/orphans")
    api("DELETE", "/api/admin/logs/all")
    time.sleep(0.5)

    # 批次建立候選規則（XPath 條件不匹配）
    sys.stdout.write(f"  建立 {candidate_count} 條規則...")
    sys.stdout.flush()

    BATCH = 100
    created = 0
    for batch_start in range(0, candidate_count - 1, BATCH):
        batch_end = min(batch_start + BATCH, candidate_count - 1)
        batch = []
        for i in range(batch_start, batch_end):
            # 每條規則用不同的 ServiceName 值（模擬 500 種 endpoint）
            # 這樣分桶後每桶只有少量規則
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

    # 建立目標規則（最後建立，排序時 createdAt 最新）
    api("POST", "/api/admin/rules", {
        "protocol": "JMS",
        "matchKey": QUEUE,
        "responseBody": "<result>matched</result>",
        "bodyCondition": "//ServiceName=OrderService;//OrderType=STANDARD;//Channel=WEB;//Priority=HIGH",
        "priority": 0,
        "description": "JMS 目標規則",
    })
    print(f" 完成 ({created + 1} 條)")

    # 清快取
    _, rules = api("GET", "/api/admin/rules")
    if isinstance(rules, list) and rules:
        first_id = rules[0].get("id")
        if first_id:
            api("PUT", f"/api/admin/rules/{first_id}/disable")
            api("PUT", f"/api/admin/rules/{first_id}/enable")
    time.sleep(0.5)

    # 發送 5 次 JMS 測試訊息，取匹配時間
    match_times = []
    response_times = []

    for run in range(5):
        api("DELETE", "/api/admin/logs/all")
        time.sleep(0.3)

        # 發送 JMS 測試訊息
        api("POST", "/api/admin/jms/test", xml_body, content_type="application/xml")

        # 等待日誌
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
                # 第一次印詳細資訊
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

# === 總結 ===
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

# 清理
print()
print("清理...")
api("DELETE", "/api/admin/rules/all")
api("DELETE", "/api/admin/responses/orphans")
api("DELETE", "/api/admin/logs/all")
print("完成")
