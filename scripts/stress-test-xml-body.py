#!/usr/bin/env python3
"""
壓力測試：複雜 XML body 對匹配效能的影響

測試策略：
  - 產生不同大小的 XML body（1KB / 10KB / 50KB / 100KB）
  - 每種大小搭配多條 XPath 條件的候選規則
  - 比較 JSON vs XML 的匹配時間差異

用法：python3 scripts/stress-test-xml-body.py [BASE_URL]
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
HOST = "xml-stress.api.test"


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


def mock_request(path, method, headers, body_str):
    url = f"{MOCK_API}{path}"
    data = body_str.encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in headers.items():
        req.add_header(k, v)
    start = time.time()
    try:
        with urllib.request.urlopen(req) as resp:
            elapsed = time.time() - start
            return resp.status, resp.read().decode(), elapsed
    except urllib.error.HTTPError as e:
        elapsed = time.time() - start
        return e.code, e.read().decode(), elapsed


def wait_for_logs(endpoint_filter, expected_count=1, timeout=15):
    for _ in range(timeout):
        time.sleep(1)
        _, logs = api("GET", f"/api/admin/logs?matched=true&protocol=HTTP&endpoint={endpoint_filter}")
        results = logs.get("results", []) if isinstance(logs, dict) else []
        if len(results) >= expected_count:
            return results
    return []


def generate_xml(item_count, extra_fields_per_item=5):
    """產生模擬 SOAP/ESB 風格的 XML"""
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ord="http://example.com/orders">',
        '  <soapenv:Header>',
        '    <ord:AuthToken>TOKEN-ABC-123-XYZ</ord:AuthToken>',
        '    <ord:TraceId>TRACE-20260329-001</ord:TraceId>',
        '    <ord:Timestamp>2026-03-29T12:00:00Z</ord:Timestamp>',
        '  </soapenv:Header>',
        '  <soapenv:Body>',
        '    <ord:CreateOrderRequest>',
        '      <ord:OrderHeader>',
        '        <ord:OrderType>STANDARD</ord:OrderType>',
        '        <ord:Channel>WEB</ord:Channel>',
        '        <ord:Priority>HIGH</ord:Priority>',
        '        <ord:CustomerId>CUST-9876</ord:CustomerId>',
        '        <ord:CustomerName>John Doe</ord:CustomerName>',
        '        <ord:Region>APAC</ord:Region>',
        '        <ord:Currency>TWD</ord:Currency>',
        '      </ord:OrderHeader>',
        '      <ord:OrderItems>',
    ]

    for i in range(item_count):
        lines.append(f'        <ord:Item seq="{i+1}">')
        lines.append(f'          <ord:SKU>SKU-{i+1:04d}</ord:SKU>')
        lines.append(f'          <ord:ProductName>Product {i+1}</ord:ProductName>')
        lines.append(f'          <ord:Quantity>{(i % 10) + 1}</ord:Quantity>')
        lines.append(f'          <ord:UnitPrice>{100 + i * 10}.00</ord:UnitPrice>')
        lines.append(f'          <ord:Discount>{i % 5}.0</ord:Discount>')
        for j in range(extra_fields_per_item):
            lines.append(f'          <ord:Attr{j+1}>value-{i}-{j}</ord:Attr{j+1}>')
        lines.append(f'        </ord:Item>')

    lines.extend([
        '      </ord:OrderItems>',
        '      <ord:ShippingInfo>',
        '        <ord:Method>EXPRESS</ord:Method>',
        '        <ord:Address>123 Test Street, Taipei</ord:Address>',
        '        <ord:PostalCode>10001</ord:PostalCode>',
        '        <ord:Country>TW</ord:Country>',
        '      </ord:ShippingInfo>',
        '      <ord:PaymentInfo>',
        '        <ord:Method>CREDIT_CARD</ord:Method>',
        '        <ord:CardLast4>1234</ord:CardLast4>',
        '        <ord:BillingAddress>456 Bill Ave, Taipei</ord:BillingAddress>',
        '      </ord:PaymentInfo>',
        '    </ord:CreateOrderRequest>',
        '  </soapenv:Body>',
        '</soapenv:Envelope>',
    ])
    return "\n".join(lines)


def generate_json(item_count, extra_fields_per_item=5):
    """產生等效的 JSON body"""
    items = []
    for i in range(item_count):
        item = {
            "sku": f"SKU-{i+1:04d}",
            "productName": f"Product {i+1}",
            "quantity": (i % 10) + 1,
            "unitPrice": 100 + i * 10,
            "discount": i % 5,
        }
        for j in range(extra_fields_per_item):
            item[f"attr{j+1}"] = f"value-{i}-{j}"
        items.append(item)

    return json.dumps({
        "orderType": "STANDARD",
        "channel": "WEB",
        "priority": "HIGH",
        "customerId": "CUST-9876",
        "customerName": "John Doe",
        "region": "APAC",
        "currency": "TWD",
        "items": items,
        "shipping": {
            "method": "EXPRESS",
            "address": "123 Test Street, Taipei",
            "postalCode": "10001",
            "country": "TW",
        },
        "payment": {
            "method": "CREDIT_CARD",
            "cardLast4": "1234",
            "billingAddress": "456 Bill Ave, Taipei",
        },
    }, ensure_ascii=False)


# XML body 大小配置：(item_count, extra_fields, 預估大小標籤)
XML_SIZES = [
    (5, 3, "~1KB"),
    (50, 5, "~10KB"),
    (200, 8, "~50KB"),
    (400, 10, "~100KB"),
]

CANDIDATE_COUNT = 10  # 同 path 候選規則數

print("=" * 60)
print("壓力測試：XML body 大小 vs 匹配效能")
print(f"目標：{BASE_URL}")
print(f"每組候選規則：{CANDIDATE_COUNT} 條 + 1 目標規則")
print("=" * 60)

all_results = []

for size_idx, (item_count, extra_fields, size_label) in enumerate(XML_SIZES):
    xml_body = generate_xml(item_count, extra_fields)
    json_body = generate_json(item_count, extra_fields)
    xml_size = len(xml_body.encode("utf-8"))
    json_size = len(json_body.encode("utf-8"))

    print(f"\n{'─' * 60}")
    print(f"  測試組 {size_idx + 1}: {size_label}")
    print(f"  XML: {xml_size:,} bytes / JSON: {json_size:,} bytes")
    print(f"  Items: {item_count}, Extra fields/item: {extra_fields}")
    print(f"{'─' * 60}")

    # --- 清理 ---
    api("DELETE", "/api/admin/rules/all")
    api("DELETE", "/api/admin/responses/orphans")
    api("DELETE", "/api/admin/logs/all")
    time.sleep(0.3)

    # --- 建立候選規則（XPath 條件不匹配）---
    xml_path = f"/xml/orders-{size_idx}"
    json_path = f"/json/orders-{size_idx}"

    for i in range(CANDIDATE_COUNT):
        # XML 候選
        api("POST", "/api/admin/rules", {
            "protocol": "HTTP", "matchKey": xml_path, "method": "POST",
            "targetHost": HOST,
            "responseBody": f"<wrong>{i}</wrong>",
            "status": 200,
            "bodyCondition": f"//OrderType=NONEXIST_{i}",
            "priority": 0,
            "description": f"XML 候選 #{i}",
            "sseEnabled": False,
        })
        # JSON 候選
        api("POST", "/api/admin/rules", {
            "protocol": "HTTP", "matchKey": json_path, "method": "POST",
            "targetHost": HOST,
            "responseBody": json.dumps({"wrong": i}),
            "status": 200,
            "bodyCondition": f"orderType=NONEXIST_{i}",
            "priority": 0,
            "description": f"JSON 候選 #{i}",
            "sseEnabled": False,
        })

    # --- 建立目標規則 ---
    # XML 目標：多個 XPath 條件
    api("POST", "/api/admin/rules", {
        "protocol": "HTTP", "matchKey": xml_path, "method": "POST",
        "targetHost": HOST,
        "responseBody": "<result>xml-matched</result>",
        "status": 200,
        "bodyCondition": "//OrderType=STANDARD;//Channel=WEB;//Priority=HIGH",
        "priority": 0,
        "description": "XML 目標規則",
        "sseEnabled": False,
    })

    # JSON 目標：多個欄位條件
    api("POST", "/api/admin/rules", {
        "protocol": "HTTP", "matchKey": json_path, "method": "POST",
        "targetHost": HOST,
        "responseBody": json.dumps({"result": "json-matched"}),
        "status": 200,
        "bodyCondition": "orderType=STANDARD;channel=WEB;priority=HIGH",
        "priority": 0,
        "description": "JSON 目標規則",
        "sseEnabled": False,
    })

    # 清快取
    time.sleep(0.3)

    # --- 測試 XML ---
    xml_times = []
    for run in range(5):
        api("DELETE", "/api/admin/logs/all")
        # 清快取（disable/enable 任一規則）
        _, rules_resp = api("GET", "/api/admin/rules")
        if isinstance(rules_resp, list) and rules_resp:
            first_id = rules_resp[0].get("id")
            if first_id:
                api("PUT", f"/api/admin/rules/{first_id}/disable")
                api("PUT", f"/api/admin/rules/{first_id}/enable")
        time.sleep(0.2)

        status, resp, elapsed = mock_request(
            f"{xml_path}?status=active", "POST",
            {"Content-Type": "application/xml", "X-Original-Host": HOST},
            xml_body,
        )

        results = wait_for_logs(xml_path.replace("/", "%2F"), timeout=15)
        if results:
            mt = results[0].get("log", {}).get("matchTimeMs")
            if mt is not None:
                xml_times.append(mt)

    # --- 測試 JSON ---
    json_times = []
    for run in range(5):
        api("DELETE", "/api/admin/logs/all")
        _, rules_resp = api("GET", "/api/admin/rules")
        if isinstance(rules_resp, list) and rules_resp:
            first_id = rules_resp[0].get("id")
            if first_id:
                api("PUT", f"/api/admin/rules/{first_id}/disable")
                api("PUT", f"/api/admin/rules/{first_id}/enable")
        time.sleep(0.2)

        status, resp, elapsed = mock_request(
            f"{json_path}?status=active", "POST",
            {"Content-Type": "application/json", "X-Original-Host": HOST},
            json_body,
        )

        results = wait_for_logs(json_path.replace("/", "%2F"), timeout=15)
        if results:
            mt = results[0].get("log", {}).get("matchTimeMs")
            if mt is not None:
                json_times.append(mt)

    # --- 結果 ---
    xml_avg = sum(xml_times) / len(xml_times) if xml_times else -1
    json_avg = sum(json_times) / len(json_times) if json_times else -1
    ratio = xml_avg / json_avg if json_avg > 0 else -1

    print(f"\n  XML  匹配時間 (5 次): {xml_times}  平均: {xml_avg:.1f} ms")
    print(f"  JSON 匹配時間 (5 次): {json_times}  平均: {json_avg:.1f} ms")
    if ratio > 0:
        print(f"  XML / JSON 比值:     {ratio:.1f}x")

    all_results.append({
        "size": size_label,
        "xml_bytes": xml_size,
        "json_bytes": json_size,
        "xml_avg": xml_avg,
        "json_avg": json_avg,
        "ratio": ratio,
    })

# === 總結 ===
print()
print("=" * 60)
print("  總結：XML vs JSON 匹配效能")
print("=" * 60)
print(f"  {'大小':<10} {'XML bytes':>12} {'JSON bytes':>12} {'XML avg':>10} {'JSON avg':>10} {'比值':>8}")
print(f"  {'─'*10} {'─'*12} {'─'*12} {'─'*10} {'─'*10} {'─'*8}")
for r in all_results:
    ratio_str = f"{r['ratio']:.1f}x" if r['ratio'] > 0 else "N/A"
    xml_str = f"{r['xml_avg']:.1f} ms" if r['xml_avg'] >= 0 else "N/A"
    json_str = f"{r['json_avg']:.1f} ms" if r['json_avg'] >= 0 else "N/A"
    print(f"  {r['size']:<10} {r['xml_bytes']:>12,} {r['json_bytes']:>12,} {xml_str:>10} {json_str:>10} {ratio_str:>8}")
print("=" * 60)

# --- 清理 ---
print()
print("清理...")
api("DELETE", "/api/admin/rules/all")
api("DELETE", "/api/admin/responses/orphans")
api("DELETE", "/api/admin/logs/all")
print("完成")
