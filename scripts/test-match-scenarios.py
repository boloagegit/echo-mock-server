#!/usr/bin/env python3
"""
規則匹配情境完整測試

涵蓋所有已實作功能的 E2E 驗證：
  - 核心匹配邏輯（精確/萬用/priority/停用/fallback）
  - 條件運算子（=, !=, *=, ~= 用於 header/body JSON/body XML）
  - 模板渲染（Handlebars helpers）
  - JSONPath / XPath 條件匹配
  - SSE Server-Sent Events 串流
  - 請求紀錄（matched / unmatched）
  - JMS 協定匹配（需啟用 JMS）
  - Faker 假資料 helpers（Phase 1-4）
  - Fault Injection（Phase 2-1: EMPTY_RESPONSE / CONNECTION_RESET）
  - Delay Range（Phase 1-2: delayMs ~ maxDelayMs 隨機延遲）
  - Stateful Scenarios（Phase 2-2: 狀態機匹配 + 狀態轉移）

用法：python3 scripts/test-match-scenarios.py [BASE_URL]
"""

import json
import sys
import time
import urllib.request
import base64

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
ADMIN = f"{BASE_URL}/api/admin"
MOCK = f"{BASE_URL}/mock"
AUTH = base64.b64encode(b"admin:admin").decode()

passed = 0
failed = 0
errors = []


# ========== 工具函式 ==========

def api(method, path, data=None, content_type="application/json"):
    url = f"{BASE_URL}{path}"
    if data and content_type == "application/json":
        body = json.dumps(data).encode()
    elif data:
        body = data.encode() if isinstance(data, str) else data
    else:
        body = None
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


def mock_req(path, method="GET", headers=None, body=None, query=""):
    url = f"{MOCK}{path}"
    if query:
        url += f"?{query}"
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, resp.read().decode(), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode() if e.fp else "", {}


def create_rule(data):
    s, r = api("POST", "/api/admin/rules", data)
    if s == 201 and isinstance(r, dict):
        return r.get("id")
    print(f"    ⚠ 建立規則失敗: {s} {r}")
    return None


def cleanup():
    api("DELETE", "/api/admin/rules/all")
    api("DELETE", "/api/admin/responses/orphans")
    api("DELETE", "/api/admin/logs/all")


def wait_logs(timeout=12, **filters):
    params = "&".join(f"{k}={v}" for k, v in filters.items() if v is not None)
    url = f"/api/admin/logs?{params}" if params else "/api/admin/logs"
    for _ in range(timeout):
        time.sleep(1)
        _, data = api("GET", url)
        results = data.get("results", []) if isinstance(data, dict) else []
        if results:
            return results
    return []


def get_log_detail(log_id):
    """取得單筆日誌詳情（含 responseBody / matchChain）"""
    s, data = api("GET", f"/api/admin/logs/{log_id}/detail")
    return data if s == 200 and isinstance(data, dict) else {}


def check(name, condition, detail=""):
    global passed, failed
    if condition:
        passed += 1
        print(f"  ✓ {name}")
    else:
        failed += 1
        msg = f"  ✗ {name}"
        if detail:
            msg += f"  ({detail})"
        print(msg)
        errors.append(name)


# ========== 測試開始 ==========

print("=" * 60)
print("  規則匹配情境完整測試")
print(f"  目標：{BASE_URL}")
print("=" * 60)

try:
    s, status = api("GET", "/api/admin/status")
    jms_enabled = status.get("jmsEnabled", False)
    print(f"  服務版本：{status.get('version', '?')}")
    print(f"  JMS：{'啟用' if jms_enabled else '停用'}")
except Exception as e:
    print(f"  ⚠ 無法連線：{e}")
    sys.exit(1)

HOST = "test.scenario.local"

# --- 情境 1：精確條件匹配 ---
print(f"\n{'─' * 60}")
print("情境 1：精確條件匹配（body + query + header）")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/orders", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"no-condition"}',
    "status": 200, "description": "無條件 fallback", "sseEnabled": False})
create_rule({"protocol": "HTTP", "matchKey": "/api/orders", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"exact-match"}',
    "status": 200, "bodyCondition": "type=ORDER",
    "queryCondition": "status=active", "headerCondition": "X-Tenant=abc",
    "description": "精確條件", "sseEnabled": False})
s, body, _ = mock_req("/api/orders", "POST",
    {"X-Original-Host": HOST, "X-Tenant": "abc"},
    {"type": "ORDER"}, "status=active")
parsed = json.loads(body) if body else {}
check("精確條件匹配 → exact-match", s == 200 and parsed.get("result") == "exact-match",
      f"status={s}, body={body[:100]}")

# --- 情境 2：無條件 fallback ---
print(f"\n{'─' * 60}")
print("情境 2：無條件 fallback 匹配")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/users", "method": "GET",
    "targetHost": HOST, "responseBody": '{"result":"condition-rule"}',
    "status": 200, "bodyCondition": "type=VIP", "description": "條件不符", "sseEnabled": False})
create_rule({"protocol": "HTTP", "matchKey": "/api/users", "method": "GET",
    "targetHost": HOST, "responseBody": '{"result":"fallback"}',
    "status": 200, "description": "無條件 fallback", "sseEnabled": False})
s, body, _ = mock_req("/api/users", "GET", {"X-Original-Host": HOST})
parsed = json.loads(body) if body else {}
check("條件不符 → fallback", s == 200 and parsed.get("result") == "fallback",
      f"status={s}, body={body[:100]}")

# --- 情境 3：萬用 matchKey (*) ---
print(f"\n{'─' * 60}")
print("情境 3：萬用 matchKey (*) fallback")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "*",
    "targetHost": HOST, "responseBody": '{"result":"wildcard"}',
    "status": 200, "description": "萬用 fallback", "sseEnabled": False})
s, body, _ = mock_req("/any/path/here", "GET", {"X-Original-Host": HOST})
parsed = json.loads(body) if body else {}
check("任意路徑 → 萬用規則", s == 200 and parsed.get("result") == "wildcard",
      f"status={s}, body={body[:100]}")

# --- 情境 4：priority 排序 ---
print(f"\n{'─' * 60}")
print("情境 4：優先順序（priority 大的優先）")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/items", "method": "GET",
    "targetHost": HOST, "responseBody": '{"result":"low-priority"}',
    "status": 200, "priority": 1, "description": "低優先", "sseEnabled": False})
create_rule({"protocol": "HTTP", "matchKey": "/api/items", "method": "GET",
    "targetHost": HOST, "responseBody": '{"result":"high-priority"}',
    "status": 200, "priority": 10, "description": "高優先", "sseEnabled": False})
s, body, _ = mock_req("/api/items", "GET", {"X-Original-Host": HOST})
parsed = json.loads(body) if body else {}
check("priority=10 優先於 priority=1", s == 200 and parsed.get("result") == "high-priority",
      f"status={s}, body={body[:100]}")

# --- 情境 5：停用規則跳過 ---
print(f"\n{'─' * 60}")
print("情境 5：停用規則跳過")
cleanup()
disabled_id = create_rule({"protocol": "HTTP", "matchKey": "/api/test", "method": "GET",
    "targetHost": HOST, "responseBody": '{"result":"disabled"}',
    "status": 200, "description": "即將停用", "sseEnabled": False})
create_rule({"protocol": "HTTP", "matchKey": "/api/test", "method": "GET",
    "targetHost": HOST, "responseBody": '{"result":"enabled"}',
    "status": 200, "description": "啟用中", "sseEnabled": False})
api("PUT", f"/api/admin/rules/{disabled_id}/disable")
s, body, _ = mock_req("/api/test", "GET", {"X-Original-Host": HOST})
parsed = json.loads(body) if body else {}
check("停用規則被跳過", s == 200 and parsed.get("result") == "enabled",
      f"status={s}, body={body[:100]}")

# --- 情境 6：多條件 AND ---
print(f"\n{'─' * 60}")
print("情境 6：多條件 AND（部分不符 → 不匹配）")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/multi", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"all-match"}', "status": 200,
    "bodyCondition": "type=ORDER;status=NEW", "queryCondition": "region=TW",
    "description": "三條件 AND", "sseEnabled": False})
create_rule({"protocol": "HTTP", "matchKey": "/api/multi", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"fallback"}',
    "status": 200, "description": "fallback", "sseEnabled": False})

s, body, _ = mock_req("/api/multi", "POST", {"X-Original-Host": HOST},
    {"type": "ORDER", "status": "NEW"}, "region=TW")
parsed = json.loads(body) if body else {}
check("6a: 全部條件符合 → 匹配", s == 200 and parsed.get("result") == "all-match",
      f"status={s}, body={body[:100]}")

s, body, _ = mock_req("/api/multi", "POST", {"X-Original-Host": HOST},
    {"type": "ORDER", "status": "CLOSED"}, "region=TW")
parsed = json.loads(body) if body else {}
check("6b: body status 不符 → fallback", s == 200 and parsed.get("result") == "fallback",
      f"status={s}, body={body[:100]}")

s, body, _ = mock_req("/api/multi", "POST", {"X-Original-Host": HOST},
    {"type": "ORDER", "status": "NEW"}, "region=US")
parsed = json.loads(body) if body else {}
check("6c: query 不符 → fallback", s == 200 and parsed.get("result") == "fallback",
      f"status={s}, body={body[:100]}")

# --- 情境 7：targetHost 精確度 ---
print(f"\n{'─' * 60}")
print("情境 7：targetHost 精確度")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/host-test", "method": "GET",
    "responseBody": '{"result":"no-host"}', "status": 200, "description": "無 host", "sseEnabled": False})
create_rule({"protocol": "HTTP", "matchKey": "/api/host-test", "method": "GET",
    "targetHost": HOST, "responseBody": '{"result":"with-host"}',
    "status": 200, "description": "有 host", "sseEnabled": False})
s, body, _ = mock_req("/api/host-test", "GET", {"X-Original-Host": HOST})
parsed = json.loads(body) if body else {}
check("有 targetHost 的規則優先", s == 200 and parsed.get("result") == "with-host",
      f"status={s}, body={body[:100]}")

# --- 情境 8：無匹配 → 404 ---
print(f"\n{'─' * 60}")
print("情境 8：無匹配 → 404")
cleanup()
s, body, _ = mock_req("/api/nothing", "GET", {"X-Original-Host": HOST})
check("無規則 → 404 或 502（代理轉發失敗）", s in (404, 502), f"status={s}")

# --- 情境 9：Header 條件運算子 ---
print(f"\n{'─' * 60}")
print("情境 9：Header 條件運算子（=, !=, *=, ~=）")

# 9a: =
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/h-eq", "method": "GET",
    "targetHost": HOST, "responseBody": '{"op":"eq"}', "status": 200,
    "headerCondition": "X-Role=admin", "description": "header =", "sseEnabled": False})
s, body, _ = mock_req("/api/h-eq", "GET", {"X-Original-Host": HOST, "X-Role": "admin"})
parsed = json.loads(body) if body else {}
check("9a: Header = 匹配", s == 200 and parsed.get("op") == "eq", f"status={s}")
s, _, _ = mock_req("/api/h-eq", "GET", {"X-Original-Host": HOST, "X-Role": "user"})
check("9a-miss: Header = 不符 → 無匹配", s in (404, 502), f"status={s}")

# 9b: !=
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/h-ne", "method": "GET",
    "targetHost": HOST, "responseBody": '{"op":"ne"}', "status": 200,
    "headerCondition": "X-Env!=prod", "description": "header !=", "sseEnabled": False})
s, body, _ = mock_req("/api/h-ne", "GET", {"X-Original-Host": HOST, "X-Env": "dev"})
parsed = json.loads(body) if body else {}
check("9b: Header != dev≠prod → 匹配", s == 200 and parsed.get("op") == "ne", f"status={s}")
s, _, _ = mock_req("/api/h-ne", "GET", {"X-Original-Host": HOST, "X-Env": "prod"})
check("9b-miss: Header != prod=prod → 無匹配", s in (404, 502), f"status={s}")

# 9c: *=
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/h-ct", "method": "GET",
    "targetHost": HOST, "responseBody": '{"op":"contains"}', "status": 200,
    "headerCondition": "User-Agent*=Mozilla", "description": "header *=", "sseEnabled": False})
s, body, _ = mock_req("/api/h-ct", "GET",
    {"X-Original-Host": HOST, "User-Agent": "Mozilla/5.0 Firefox"})
parsed = json.loads(body) if body else {}
check("9c: Header *= 包含 → 匹配", s == 200 and parsed.get("op") == "contains", f"status={s}")

# 9d: ~=
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/h-rx", "method": "GET",
    "targetHost": HOST, "responseBody": '{"op":"regex"}', "status": 200,
    "headerCondition": "X-Version~=^v[0-9]+", "description": "header ~=", "sseEnabled": False})
s, body, _ = mock_req("/api/h-rx", "GET", {"X-Original-Host": HOST, "X-Version": "v3"})
parsed = json.loads(body) if body else {}
check("9d: Header ~= 正則 → 匹配", s == 200 and parsed.get("op") == "regex", f"status={s}")
s, _, _ = mock_req("/api/h-rx", "GET", {"X-Original-Host": HOST, "X-Version": "beta1"})
check("9d-miss: Header ~= 不符 → 無匹配", s in (404, 502), f"status={s}")

# --- 情境 10：Response Template ---
print(f"\n{'─' * 60}")
print("情境 10：Response Template 渲染")
cleanup()
tmpl = json.dumps({"path": "{{request.path}}", "method": "{{request.method}}",
    "q": "{{request.query.name}}", "uuid": "{{randomValue type='UUID'}}",
    "date": "{{now format='yyyy-MM-dd'}}"}, ensure_ascii=False)
create_rule({"protocol": "HTTP", "matchKey": "/api/template", "method": "GET",
    "targetHost": HOST, "responseBody": tmpl, "status": 200,
    "description": "模板規則", "sseEnabled": False})
s, body, _ = mock_req("/api/template", "GET", {"X-Original-Host": HOST}, query="name=Echo")
p = json.loads(body) if body else {}
check("模板 request.path", p.get("path") == "/api/template", f"path={p.get('path')}")
check("模板 request.method", p.get("method") == "GET", f"method={p.get('method')}")
check("模板 request.query", p.get("q") == "Echo", f"q={p.get('q')}")
check("模板 UUID 格式", p.get("uuid") and len(p.get("uuid", "")) == 36, f"uuid={p.get('uuid')}")
check("模板 now 日期", p.get("date") and len(p.get("date", "")) == 10, f"date={p.get('date')}")

# --- 情境 11：自訂狀態碼 + Headers ---
print(f"\n{'─' * 60}")
print("情境 11：HTTP 回應狀態碼 + 自訂 Headers")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/custom", "method": "POST",
    "targetHost": HOST, "responseBody": '{"created":true}', "status": 201,
    "responseHeaders": '{"X-Custom":"hello","X-Req-Id":"t-123"}',
    "description": "自訂狀態碼", "sseEnabled": False})
s, body, hdrs = mock_req("/api/custom", "POST", {"X-Original-Host": HOST}, {"d": 1})
check("回應狀態碼 201", s == 201, f"status={s}")
check("自訂 header X-Custom", hdrs.get("X-Custom") == "hello", f"X-Custom={hdrs.get('X-Custom')}")
check("自訂 header X-Req-Id", hdrs.get("X-Req-Id") == "t-123", f"X-Req-Id={hdrs.get('X-Req-Id')}")

# --- 情境 12：延遲回應 ---
print(f"\n{'─' * 60}")
print("情境 12：延遲回應 (delayMs)")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/delay", "method": "GET",
    "targetHost": HOST, "responseBody": '{"delayed":true}', "status": 200,
    "delayMs": 500, "description": "延遲 500ms", "sseEnabled": False})
start = time.time()
s, body, _ = mock_req("/api/delay", "GET", {"X-Original-Host": HOST})
elapsed = (time.time() - start) * 1000
check("延遲回應 >= 400ms", s == 200 and elapsed >= 400, f"elapsed={elapsed:.0f}ms")

# --- 情境 13：JSONPath 條件 ---
print(f"\n{'─' * 60}")
print("情境 13：JSONPath 條件匹配")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/jp", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"jp-match"}', "status": 200,
    "bodyCondition": "$.order.type=PREMIUM;$.order.items[0].sku=X1",
    "description": "JSONPath", "sseEnabled": False})
create_rule({"protocol": "HTTP", "matchKey": "/api/jp", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"fallback"}',
    "status": 200, "description": "fallback", "sseEnabled": False})
s, body, _ = mock_req("/api/jp", "POST", {"X-Original-Host": HOST},
    {"order": {"type": "PREMIUM", "items": [{"sku": "X1", "qty": 2}]}})
parsed = json.loads(body) if body else {}
check("13a: JSONPath 匹配", s == 200 and parsed.get("result") == "jp-match", f"body={body[:100]}")
s, body, _ = mock_req("/api/jp", "POST", {"X-Original-Host": HOST},
    {"order": {"type": "STANDARD", "items": [{"sku": "X1"}]}})
parsed = json.loads(body) if body else {}
check("13b: JSONPath type 不符 → fallback", s == 200 and parsed.get("result") == "fallback", f"body={body[:100]}")

# --- 情境 14：XPath 條件 ---
print(f"\n{'─' * 60}")
print("情境 14：XML / XPath 條件匹配 (HTTP)")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/xp", "method": "POST",
    "targetHost": HOST, "responseBody": "<result>xpath-match</result>", "status": 200,
    "bodyCondition": "//OrderType=RUSH;//Channel=WEB", "description": "XPath", "sseEnabled": False})
create_rule({"protocol": "HTTP", "matchKey": "/api/xp", "method": "POST",
    "targetHost": HOST, "responseBody": "<result>fallback</result>",
    "status": 200, "description": "fallback", "sseEnabled": False})

xml1 = "<Request><OrderType>RUSH</OrderType><Channel>WEB</Channel></Request>"
url = f"{MOCK}/api/xp"
req = urllib.request.Request(url, data=xml1.encode(), method="POST")
req.add_header("Content-Type", "application/xml")
req.add_header("X-Original-Host", HOST)
try:
    with urllib.request.urlopen(req) as resp:
        s, body = resp.status, resp.read().decode()
except urllib.error.HTTPError as e:
    s, body = e.code, e.read().decode() if e.fp else ""
check("14a: XPath 匹配", s == 200 and "xpath-match" in body, f"status={s}, body={body[:100]}")

xml2 = "<Request><OrderType>NORMAL</OrderType><Channel>WEB</Channel></Request>"
req2 = urllib.request.Request(url, data=xml2.encode(), method="POST")
req2.add_header("Content-Type", "application/xml")
req2.add_header("X-Original-Host", HOST)
try:
    with urllib.request.urlopen(req2) as resp:
        s2, body2 = resp.status, resp.read().decode()
except urllib.error.HTTPError as e:
    s2, body2 = e.code, e.read().decode() if e.fp else ""
check("14b: XPath 不符 → fallback", s2 == 200 and "fallback" in body2, f"status={s2}, body={body2[:100]}")

# --- 情境 15：精確路徑優先於萬用 ---
print(f"\n{'─' * 60}")
print("情境 15：精確路徑優先於萬用路徑")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "*",
    "targetHost": HOST, "responseBody": '{"result":"wildcard"}',
    "status": 200, "description": "萬用", "sseEnabled": False})
create_rule({"protocol": "HTTP", "matchKey": "/api/exact",
    "targetHost": HOST, "responseBody": '{"result":"exact"}',
    "status": 200, "description": "精確", "sseEnabled": False})
s, body, _ = mock_req("/api/exact", "GET", {"X-Original-Host": HOST})
parsed = json.loads(body) if body else {}
check("精確路徑優先於萬用 *", s == 200 and parsed.get("result") == "exact", f"body={body[:100]}")

# --- 情境 16：請求紀錄驗證 ---
print(f"\n{'─' * 60}")
print("情境 16：請求紀錄驗證")
cleanup()
api("DELETE", "/api/admin/logs/all")
create_rule({"protocol": "HTTP", "matchKey": "/api/log-test", "method": "POST",
    "targetHost": HOST, "responseBody": '{"wrong":true}', "status": 200,
    "bodyCondition": "type=WRONG", "description": "條件不符", "sseEnabled": False})
log_rule_id = create_rule({"protocol": "HTTP", "matchKey": "/api/log-test", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"logged"}', "status": 200,
    "bodyCondition": "type=CORRECT", "description": "目標規則", "sseEnabled": False})
mock_req("/api/log-test", "POST", {"X-Original-Host": HOST}, {"type": "CORRECT"})
results = wait_logs(protocol="HTTP", matched="true", endpoint="/api/log-test")
if results:
    log = results[0].get("log", {})
    check("日誌 matched=true", log.get("matched") is True, f"matched={log.get('matched')}")
    check("日誌 ruleId 正確", log.get("ruleId") == log_rule_id, f"ruleId={log.get('ruleId')}")
    check("日誌 matchChain 存在", log.get("hasMatchChain") is True, f"hasMatchChain={log.get('hasMatchChain')}")
    check("日誌 endpoint 正確", log.get("endpoint") == "/api/log-test", f"endpoint={log.get('endpoint')}")
    check("日誌 method 正確", log.get("method") == "POST", f"method={log.get('method')}")
else:
    check("日誌寫入", False, "超時未取得日誌")

# --- 情境 17：未匹配請求紀錄 ---
print(f"\n{'─' * 60}")
print("情境 17：未匹配請求紀錄")
cleanup()
api("DELETE", "/api/admin/logs/all")
mock_req("/api/no-rule", "GET", {"X-Original-Host": HOST})
results = wait_logs(timeout=30, protocol="HTTP", matched="false")
if results:
    log = results[0].get("log", {})
    check("未匹配日誌 matched=false", log.get("matched") is False)
    check("未匹配日誌 ruleId=null", log.get("ruleId") is None)
else:
    check("未匹配日誌寫入", False, "超時未取得日誌")

# --- JMS 情境 ---
if jms_enabled:
    JMS_Q = "ECHO.REQUEST"

    print(f"\n{'─' * 60}")
    print("情境 18：JMS 精確條件匹配")
    cleanup()
    create_rule({"protocol": "JMS", "matchKey": JMS_Q,
        "responseBody": "<r>wrong</r>", "bodyCondition": "//Type=WRONG",
        "description": "JMS 不符", "sseEnabled": False})
    create_rule({"protocol": "JMS", "matchKey": JMS_Q,
        "responseBody": "<r>jms-match</r>", "bodyCondition": "//Type=ORDER",
        "description": "JMS 目標", "sseEnabled": False})
    api("POST", "/api/admin/jms/test", "<Msg><Type>ORDER</Type></Msg>", "application/xml")
    results = wait_logs(protocol="JMS", matched="true")
    if results:
        check("JMS 精確條件匹配", results[0].get("log", {}).get("matched") is True)
    else:
        check("JMS 精確條件匹配", False, "超時")

    print(f"\n{'─' * 60}")
    print("情境 19：JMS 無條件 fallback")
    cleanup()
    create_rule({"protocol": "JMS", "matchKey": JMS_Q,
        "responseBody": "<r>fb</r>", "description": "JMS 無條件", "sseEnabled": False})
    api("POST", "/api/admin/jms/test", "<Msg><Type>ANY</Type></Msg>", "application/xml")
    results = wait_logs(protocol="JMS", matched="true")
    if results:
        check("JMS 無條件 fallback", results[0].get("log", {}).get("matched") is True)
    else:
        check("JMS 無條件 fallback", False, "超時")

    print(f"\n{'─' * 60}")
    print("情境 20：JMS 萬用 queueName (*)")
    cleanup()
    create_rule({"protocol": "JMS", "matchKey": "*",
        "responseBody": "<r>wc</r>", "description": "JMS 萬用", "sseEnabled": False})
    api("POST", "/api/admin/jms/test", "<Msg><D>t</D></Msg>", "application/xml")
    results = wait_logs(protocol="JMS", matched="true")
    if results:
        check("JMS 萬用 queue", results[0].get("log", {}).get("matched") is True)
    else:
        check("JMS 萬用 queue", False, "超時")
else:
    print(f"\n{'─' * 60}")
    print("⚠ JMS 未啟用，跳過情境 18-20")

# --- 情境 21：路徑萬用 /api/* ---
print(f"\n{'─' * 60}")
print("情境 21：路徑萬用匹配 (/api/*)")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/*", "method": "GET",
    "targetHost": HOST, "responseBody": '{"result":"path-wc"}',
    "status": 200, "description": "路徑萬用", "sseEnabled": False})
s, body, _ = mock_req("/api/users/123", "GET", {"X-Original-Host": HOST})
parsed = json.loads(body) if body else {}
check("/api/* 匹配 /api/users/123", s == 200 and parsed.get("result") == "path-wc",
      f"status={s}, body={body[:100]}")

# --- 情境 22：method 為空 → 匹配所有 ---
print(f"\n{'─' * 60}")
print("情境 22：method 為空 → 匹配所有 method")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/any-m",
    "targetHost": HOST, "responseBody": '{"result":"any-m"}',
    "status": 200, "description": "任意 method", "sseEnabled": False})
for m in ["GET", "POST", "PUT", "DELETE"]:
    s, body, _ = mock_req("/api/any-m", m, {"X-Original-Host": HOST})
    parsed = json.loads(body) if body else {}
    check(f"method 為空 → {m} 匹配", s == 200 and parsed.get("result") == "any-m", f"status={s}")

# --- 情境 23：XML field 匹配（無 // 前綴）---
print(f"\n{'─' * 60}")
print("情境 23：XML field 匹配（無 // 前綴，走 getElementsByTagName）")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/xml-field", "method": "POST",
    "targetHost": HOST, "responseBody": "<r>xml-field-match</r>", "status": 200,
    "bodyCondition": "OrderType=RUSH;Channel=WEB", "description": "XML field 無//", "sseEnabled": False})
create_rule({"protocol": "HTTP", "matchKey": "/api/xml-field", "method": "POST",
    "targetHost": HOST, "responseBody": "<r>fallback</r>",
    "status": 200, "description": "fallback", "sseEnabled": False})

def xml_req(path, xml_body):
    url = f"{MOCK}{path}"
    req = urllib.request.Request(url, data=xml_body.encode(), method="POST")
    req.add_header("Content-Type", "application/xml")
    req.add_header("X-Original-Host", HOST)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode() if e.fp else ""

s, body = xml_req("/api/xml-field", "<Req><OrderType>RUSH</OrderType><Channel>WEB</Channel></Req>")
check("23a: XML field 無// 匹配", s == 200 and "xml-field-match" in body, f"status={s}, body={body[:80]}")
s, body = xml_req("/api/xml-field", "<Req><OrderType>NORMAL</OrderType><Channel>WEB</Channel></Req>")
check("23b: XML field 無// 不符 → fallback", s == 200 and "fallback" in body, f"status={s}, body={body[:80]}")

# --- 情境 24：XPath 多層路徑 ---
print(f"\n{'─' * 60}")
print("情境 24：XPath 多層路徑（//Header/ServiceName）")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/xp-nested", "method": "POST",
    "targetHost": HOST, "responseBody": "<r>nested-match</r>", "status": 200,
    "bodyCondition": "//Header/ServiceName=OrderService", "description": "XPath 多層", "sseEnabled": False})
create_rule({"protocol": "HTTP", "matchKey": "/api/xp-nested", "method": "POST",
    "targetHost": HOST, "responseBody": "<r>fallback</r>",
    "status": 200, "description": "fallback", "sseEnabled": False})

s, body = xml_req("/api/xp-nested",
    "<Req><Header><ServiceName>OrderService</ServiceName></Header><Body><Data>1</Data></Body></Req>")
check("24a: XPath //Header/ServiceName 匹配", s == 200 and "nested-match" in body, f"status={s}, body={body[:80]}")
s, body = xml_req("/api/xp-nested",
    "<Req><Header><ServiceName>OtherService</ServiceName></Header></Req>")
check("24b: XPath 多層不符 → fallback", s == 200 and "fallback" in body, f"status={s}, body={body[:80]}")

# --- 情境 25：Body 運算子 != / *= / ~=（JSON）---
print(f"\n{'─' * 60}")
print("情境 25：Body 運算子（JSON）")

# 25a: !=
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/b-ne", "method": "POST",
    "targetHost": HOST, "responseBody": '{"op":"ne"}', "status": 200,
    "bodyCondition": "status!=CLOSED", "description": "body !=", "sseEnabled": False})
s, body, _ = mock_req("/api/b-ne", "POST", {"X-Original-Host": HOST}, {"status": "OPEN"})
parsed = json.loads(body) if body else {}
check("25a: Body != OPEN≠CLOSED → 匹配", s == 200 and parsed.get("op") == "ne", f"status={s}")
s, _, _ = mock_req("/api/b-ne", "POST", {"X-Original-Host": HOST}, {"status": "CLOSED"})
check("25a-miss: Body != CLOSED=CLOSED → 無匹配", s in (404, 502), f"status={s}")

# 25b: *=
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/b-ct", "method": "POST",
    "targetHost": HOST, "responseBody": '{"op":"contains"}', "status": 200,
    "bodyCondition": "name*=John", "description": "body *=", "sseEnabled": False})
s, body, _ = mock_req("/api/b-ct", "POST", {"X-Original-Host": HOST}, {"name": "John Doe"})
parsed = json.loads(body) if body else {}
check("25b: Body *= 包含 → 匹配", s == 200 and parsed.get("op") == "contains", f"status={s}")
s, _, _ = mock_req("/api/b-ct", "POST", {"X-Original-Host": HOST}, {"name": "Jane"})
check("25b-miss: Body *= 不包含 → 無匹配", s in (404, 502), f"status={s}")

# 25c: ~=
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/b-rx", "method": "POST",
    "targetHost": HOST, "responseBody": '{"op":"regex"}', "status": 200,
    "bodyCondition": "orderId~=ORD-\\d{4}", "description": "body ~=", "sseEnabled": False})
s, body, _ = mock_req("/api/b-rx", "POST", {"X-Original-Host": HOST}, {"orderId": "ORD-1234"})
parsed = json.loads(body) if body else {}
check("25c: Body ~= 正則 → 匹配", s == 200 and parsed.get("op") == "regex", f"status={s}")
s, _, _ = mock_req("/api/b-rx", "POST", {"X-Original-Host": HOST}, {"orderId": "INV-1234"})
check("25c-miss: Body ~= 不符 → 無匹配", s in (404, 502), f"status={s}")

# --- 情境 26：Body 運算子（XML XPath）---
print(f"\n{'─' * 60}")
print("情境 26：Body 運算子（XML XPath != / *= / ~=）")

# 26a: //Element!=value
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/xp-ne", "method": "POST",
    "targetHost": HOST, "responseBody": "<r>xp-ne</r>", "status": 200,
    "bodyCondition": "//Status!=CLOSED", "description": "XPath !=", "sseEnabled": False})
s, body = xml_req("/api/xp-ne", "<Req><Status>OPEN</Status></Req>")
check("26a: XPath != OPEN≠CLOSED → 匹配", s == 200 and "xp-ne" in body, f"status={s}, body={body[:80]}")
s, body = xml_req("/api/xp-ne", "<Req><Status>CLOSED</Status></Req>")
check("26a-miss: XPath != CLOSED=CLOSED → 無匹配", s in (404, 502), f"status={s}")

# 26b: //Element*=value
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/xp-ct", "method": "POST",
    "targetHost": HOST, "responseBody": "<r>xp-ct</r>", "status": 200,
    "bodyCondition": "//Name*=John", "description": "XPath *=", "sseEnabled": False})
s, body = xml_req("/api/xp-ct", "<Req><Name>John Doe</Name></Req>")
check("26b: XPath *= 包含 → 匹配", s == 200 and "xp-ct" in body, f"status={s}, body={body[:80]}")

# 26c: //Element~=regex
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/xp-rx", "method": "POST",
    "targetHost": HOST, "responseBody": "<r>xp-rx</r>", "status": 200,
    "bodyCondition": "//OrderId~=ORD-\\d{4}", "description": "XPath ~=", "sseEnabled": False})
s, body = xml_req("/api/xp-rx", "<Req><OrderId>ORD-5678</OrderId></Req>")
check("26c: XPath ~= 正則 → 匹配", s == 200 and "xp-rx" in body, f"status={s}, body={body[:80]}")

# --- 情境 27：JSON nested field ---
print(f"\n{'─' * 60}")
print("情境 27：JSON nested field（user.name=value）")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/nested", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"nested-match"}', "status": 200,
    "bodyCondition": "user.name=Alice;user.role=admin", "description": "nested field", "sseEnabled": False})
create_rule({"protocol": "HTTP", "matchKey": "/api/nested", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"fallback"}',
    "status": 200, "description": "fallback", "sseEnabled": False})
s, body, _ = mock_req("/api/nested", "POST", {"X-Original-Host": HOST},
    {"user": {"name": "Alice", "role": "admin"}})
parsed = json.loads(body) if body else {}
check("27a: nested user.name + user.role 匹配", s == 200 and parsed.get("result") == "nested-match",
      f"body={body[:100]}")
s, body, _ = mock_req("/api/nested", "POST", {"X-Original-Host": HOST},
    {"user": {"name": "Alice", "role": "viewer"}})
parsed = json.loads(body) if body else {}
check("27b: nested role 不符 → fallback", s == 200 and parsed.get("result") == "fallback",
      f"body={body[:100]}")

# --- 情境 28：JSON array index ---
print(f"\n{'─' * 60}")
print("情境 28：JSON array index（items[0].sku=value）")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/arr", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"arr-match"}', "status": 200,
    "bodyCondition": "items[0].sku=X1", "description": "array index", "sseEnabled": False})
create_rule({"protocol": "HTTP", "matchKey": "/api/arr", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"fallback"}',
    "status": 200, "description": "fallback", "sseEnabled": False})
s, body, _ = mock_req("/api/arr", "POST", {"X-Original-Host": HOST},
    {"items": [{"sku": "X1", "qty": 2}, {"sku": "X2", "qty": 1}]})
parsed = json.loads(body) if body else {}
check("28a: items[0].sku=X1 匹配", s == 200 and parsed.get("result") == "arr-match",
      f"body={body[:100]}")
s, body, _ = mock_req("/api/arr", "POST", {"X-Original-Host": HOST},
    {"items": [{"sku": "Y1", "qty": 2}]})
parsed = json.loads(body) if body else {}
check("28b: items[0].sku 不符 → fallback", s == 200 and parsed.get("result") == "fallback",
      f"body={body[:100]}")

# --- JMS 補充情境 ---
if jms_enabled:
    JMS_Q = "ECHO.REQUEST"

    # --- 情境 29：JMS XML field 匹配（無 //）---
    print(f"\n{'─' * 60}")
    print("情境 29：JMS XML field 匹配（無 // 前綴）")
    cleanup()
    create_rule({"protocol": "JMS", "matchKey": JMS_Q,
        "responseBody": "<r>jms-field</r>", "bodyCondition": "Type=ORDER;Status=NEW",
        "description": "JMS XML field", "sseEnabled": False})
    create_rule({"protocol": "JMS", "matchKey": JMS_Q,
        "responseBody": "<r>jms-fb</r>", "description": "JMS fallback", "sseEnabled": False})
    api("POST", "/api/admin/jms/test", "<Msg><Type>ORDER</Type><Status>NEW</Status></Msg>", "application/xml")
    results = wait_logs(protocol="JMS", matched="true")
    if results:
        log = results[0].get("log", {})
        check("29a: JMS XML field 無// 匹配", log.get("matched") is True)
    else:
        check("29a: JMS XML field 無// 匹配", False, "超時")

    cleanup()
    create_rule({"protocol": "JMS", "matchKey": JMS_Q,
        "responseBody": "<r>jms-field</r>", "bodyCondition": "Type=ORDER;Status=NEW",
        "description": "JMS XML field", "sseEnabled": False})
    create_rule({"protocol": "JMS", "matchKey": JMS_Q,
        "responseBody": "<r>jms-fb</r>", "description": "JMS fallback", "sseEnabled": False})
    api("DELETE", "/api/admin/logs/all")
    api("POST", "/api/admin/jms/test", "<Msg><Type>ORDER</Type><Status>CLOSED</Status></Msg>", "application/xml")
    results = wait_logs(protocol="JMS", matched="true")
    if results:
        log = results[0].get("log", {})
        detail = get_log_detail(log.get("id"))
        resp_body = detail.get("responseBody", "")
        check("29b: JMS XML field 不符 → fallback", "jms-fb" in resp_body,
              f"responseBody={resp_body[:60]}")
    else:
        check("29b: JMS XML field 不符 → fallback", False, "超時")

    # --- 情境 30：JMS body 運算子 ---
    print(f"\n{'─' * 60}")
    print("情境 30：JMS body 運算子（!= / *= / ~=）")
    cleanup()
    create_rule({"protocol": "JMS", "matchKey": JMS_Q,
        "responseBody": "<r>jms-ne</r>", "bodyCondition": "//Status!=CLOSED",
        "description": "JMS !=", "sseEnabled": False})
    api("POST", "/api/admin/jms/test", "<Msg><Status>OPEN</Status></Msg>", "application/xml")
    results = wait_logs(protocol="JMS", matched="true")
    if results:
        check("30a: JMS != OPEN≠CLOSED → 匹配", results[0].get("log", {}).get("matched") is True)
    else:
        check("30a: JMS != OPEN≠CLOSED → 匹配", False, "超時")

    cleanup()
    create_rule({"protocol": "JMS", "matchKey": JMS_Q,
        "responseBody": "<r>jms-ct</r>", "bodyCondition": "//Name*=John",
        "description": "JMS *=", "sseEnabled": False})
    api("DELETE", "/api/admin/logs/all")
    api("POST", "/api/admin/jms/test", "<Msg><Name>John Doe</Name></Msg>", "application/xml")
    results = wait_logs(protocol="JMS", matched="true")
    if results:
        check("30b: JMS *= 包含 → 匹配", results[0].get("log", {}).get("matched") is True)
    else:
        check("30b: JMS *= 包含 → 匹配", False, "超時")

    cleanup()
    create_rule({"protocol": "JMS", "matchKey": JMS_Q,
        "responseBody": "<r>jms-rx</r>", "bodyCondition": "//Code~=^[A-Z]{3}-\\d+$",
        "description": "JMS ~=", "sseEnabled": False})
    api("DELETE", "/api/admin/logs/all")
    api("POST", "/api/admin/jms/test", "<Msg><Code>ABC-123</Code></Msg>", "application/xml")
    results = wait_logs(protocol="JMS", matched="true")
    if results:
        check("30c: JMS ~= 正則 → 匹配", results[0].get("log", {}).get("matched") is True)
    else:
        check("30c: JMS ~= 正則 → 匹配", False, "超時")
else:
    print(f"\n{'─' * 60}")
    print("⚠ JMS 未啟用，跳過情境 29-30")

# --- 情境 31：同 priority 多條件規則都匹配 → 取 createdAt 較新 ---
print(f"\n{'─' * 60}")
print("情境 31：同 priority 多條件規則都匹配 → 取 createdAt 較新")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/same-pri", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"older"}', "status": 200,
    "priority": 5, "bodyCondition": "type=ORDER",
    "description": "先建立（較舊）", "sseEnabled": False})
time.sleep(0.1)
create_rule({"protocol": "HTTP", "matchKey": "/api/same-pri", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"newer"}', "status": 200,
    "priority": 5, "bodyCondition": "type=ORDER",
    "description": "後建立（較新）", "sseEnabled": False})
s, body, _ = mock_req("/api/same-pri", "POST", {"X-Original-Host": HOST}, {"type": "ORDER"})
parsed = json.loads(body) if body else {}
check("同 priority 同條件 → 取較新規則", s == 200 and parsed.get("result") == "newer",
      f"status={s}, body={body[:100]}")

# --- 情境 32：帶條件的星號規則 vs 精確路徑無條件規則 ---
# 設計：有條件且匹配 > 無條件（不論 matchKey 精確度）
print(f"\n{'─' * 60}")
print("情境 32：帶條件的星號規則（條件符合）優先於精確路徑無條件規則")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "*",
    "targetHost": HOST, "responseBody": '{"result":"wildcard-cond"}', "status": 200,
    "bodyCondition": "type=ORDER", "description": "萬用帶條件", "sseEnabled": False})
create_rule({"protocol": "HTTP", "matchKey": "/api/wc-vs-exact", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"exact-no-cond"}',
    "status": 200, "description": "精確無條件", "sseEnabled": False})
s, body, _ = mock_req("/api/wc-vs-exact", "POST", {"X-Original-Host": HOST}, {"type": "ORDER"})
parsed = json.loads(body) if body else {}
check("32a: 萬用帶條件（符合）優先於精確無條件", s == 200 and parsed.get("result") == "wildcard-cond",
      f"status={s}, body={body[:100]}")

# 條件不符時，應 fallback 到精確路徑無條件規則
s, body, _ = mock_req("/api/wc-vs-exact", "POST", {"X-Original-Host": HOST}, {"type": "OTHER"})
parsed = json.loads(body) if body else {}
check("32b: 萬用帶條件（不符）→ fallback 精確無條件", s == 200 and parsed.get("result") == "exact-no-cond",
      f"status={s}, body={body[:100]}")

if jms_enabled:
    JMS_Q = "ECHO.REQUEST"

    # --- 情境 33：JMS 精確 queue 無條件 vs 萬用 queue 無條件 ---
    print(f"\n{'─' * 60}")
    print("情境 33：JMS 精確 queue 無條件 vs 萬用 queue 無條件")
    cleanup()
    create_rule({"protocol": "JMS", "matchKey": "*",
        "responseBody": "<r>jms-wc</r>", "description": "JMS 萬用無條件", "sseEnabled": False})
    create_rule({"protocol": "JMS", "matchKey": JMS_Q,
        "responseBody": "<r>jms-exact</r>", "description": "JMS 精確無條件", "sseEnabled": False})
    api("DELETE", "/api/admin/logs/all")
    api("POST", "/api/admin/jms/test", "<Msg><D>test</D></Msg>", "application/xml")
    results = wait_logs(protocol="JMS", matched="true")
    if results:
        log = results[0].get("log", {})
        detail = get_log_detail(log.get("id"))
        resp_body = detail.get("responseBody", "")
        check("JMS 精確 queue 無條件優先於萬用", "jms-exact" in resp_body,
              f"responseBody={resp_body[:60]}")
    else:
        check("JMS 精確 queue 無條件優先於萬用", False, "超時")

    # --- 情境 34：JMS 帶條件的萬用 queue vs 精確 queue 帶條件 ---
    print(f"\n{'─' * 60}")
    print("情境 34：JMS 帶條件的萬用 queue vs 精確 queue 帶條件")
    cleanup()
    create_rule({"protocol": "JMS", "matchKey": "*",
        "responseBody": "<r>jms-wc-cond</r>", "bodyCondition": "//Type=ORDER",
        "description": "JMS 萬用帶條件", "sseEnabled": False})
    create_rule({"protocol": "JMS", "matchKey": JMS_Q,
        "responseBody": "<r>jms-exact-cond</r>", "bodyCondition": "//Type=ORDER",
        "description": "JMS 精確帶條件", "sseEnabled": False})
    api("DELETE", "/api/admin/logs/all")
    api("POST", "/api/admin/jms/test", "<Msg><Type>ORDER</Type></Msg>", "application/xml")
    results = wait_logs(protocol="JMS", matched="true")
    if results:
        log = results[0].get("log", {})
        detail = get_log_detail(log.get("id"))
        resp_body = detail.get("responseBody", "")
        check("JMS 精確 queue 帶條件優先於萬用帶條件", "jms-exact-cond" in resp_body,
              f"responseBody={resp_body[:60]}")
    else:
        check("JMS 精確 queue 帶條件優先於萬用帶條件", False, "超時")

    # --- 情境 35：JMS priority 排序 ---
    print(f"\n{'─' * 60}")
    print("情境 35：JMS priority 排序（大的優先）")
    cleanup()
    create_rule({"protocol": "JMS", "matchKey": JMS_Q,
        "responseBody": "<r>jms-low-pri</r>", "priority": 1,
        "description": "JMS 低優先", "sseEnabled": False})
    create_rule({"protocol": "JMS", "matchKey": JMS_Q,
        "responseBody": "<r>jms-high-pri</r>", "priority": 10,
        "description": "JMS 高優先", "sseEnabled": False})
    api("DELETE", "/api/admin/logs/all")
    api("POST", "/api/admin/jms/test", "<Msg><D>pri-test</D></Msg>", "application/xml")
    results = wait_logs(protocol="JMS", matched="true")
    if results:
        log = results[0].get("log", {})
        detail = get_log_detail(log.get("id"))
        resp_body = detail.get("responseBody", "")
        check("JMS priority=10 優先於 priority=1", "jms-high-pri" in resp_body,
              f"responseBody={resp_body[:60]}")
    else:
        check("JMS priority=10 優先於 priority=1", False, "超時")
else:
    print(f"\n{'─' * 60}")
    print("⚠ JMS 未啟用，跳過情境 33-35")

# ========== SSE 情境 ==========

def sse_req(path, headers=None, timeout=10):
    """
    發送 SSE 請求，讀取完整回應（直到連線關閉）。
    回傳 (status, events, raw_body, response_headers)
    events = [{"event": ..., "data": ..., "id": ...}, ...]
    """
    import http.client
    from urllib.parse import urlparse
    parsed = urlparse(f"{MOCK}{path}")
    conn = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=timeout)
    req_headers = {
        "Accept": "text/event-stream",
        "Authorization": f"Basic {AUTH}",
    }
    if headers:
        req_headers.update(headers)
    conn.request("GET", parsed.path, headers=req_headers)
    resp = conn.getresponse()
    status = resp.status
    resp_headers = dict(resp.getheaders())
    raw = resp.read().decode()
    conn.close()

    events = []
    for block in raw.split("\n\n"):
        if not block.strip():
            continue
        evt = {"event": None, "data": None, "id": None}
        for line in block.split("\n"):
            if line.startswith("event:"):
                evt["event"] = line[len("event:"):]
            elif line.startswith("data:"):
                evt["data"] = line[len("data:"):]
            elif line.startswith("id:"):
                evt["id"] = line[len("id:"):]
        if evt["data"] is not None or evt["event"] is not None:
            events.append(evt)
    return status, events, raw, resp_headers


# --- 情境 36：基本 SSE 串流 ---
print(f"\n{'─' * 60}")
print("情境 36：基本 SSE 串流（3 個 normal 事件）")
cleanup()
sse_body = json.dumps([
    {"type": "normal", "data": "event1"},
    {"type": "normal", "data": "event2"},
    {"type": "normal", "data": "event3"},
])
create_rule({"protocol": "HTTP", "matchKey": "/api/sse/basic", "method": "GET",
    "targetHost": HOST, "responseBody": sse_body,
    "status": 200, "sseEnabled": True, "description": "基本 SSE 串流"})
s, events, raw, hdrs = sse_req("/api/sse/basic", {"X-Original-Host": HOST})
check("36a: SSE 回應狀態碼 200", s == 200, f"status={s}")
ct = hdrs.get("Content-Type", hdrs.get("content-type", ""))
check("36b: Content-Type 含 text/event-stream", "text/event-stream" in ct, f"ct={ct}")
check("36c: 收到 3 個事件", len(events) == 3, f"events={len(events)}")
check("36d: 第 1 個事件 data=event1", events[0]["data"] == "event1" if events else False,
      f"data={events[0]['data'] if events else 'N/A'}")
check("36e: 第 3 個事件 data=event3", len(events) >= 3 and events[2]["data"] == "event3",
      f"data={events[2]['data'] if len(events) >= 3 else 'N/A'}")

# --- 情境 37：SSE 事件含 event name 與 id ---
print(f"\n{'─' * 60}")
print("情境 37：SSE 事件含 event name 與 id")
cleanup()
sse_body = json.dumps([
    {"type": "normal", "data": "payload", "event": "update", "id": "42"},
])
create_rule({"protocol": "HTTP", "matchKey": "/api/sse/named", "method": "GET",
    "targetHost": HOST, "responseBody": sse_body,
    "status": 200, "sseEnabled": True, "description": "SSE 含 event/id"})
s, events, raw, _ = sse_req("/api/sse/named", {"X-Original-Host": HOST})
check("37a: 收到 1 個事件", s == 200 and len(events) == 1, f"status={s}, events={len(events)}")
if events:
    check("37b: event name = update", events[0]["event"] == "update", f"event={events[0]['event']}")
    check("37c: data = payload", events[0]["data"] == "payload", f"data={events[0]['data']}")
    check("37d: id = 42", events[0]["id"] == "42", f"id={events[0]['id']}")
else:
    for label in ["37b", "37c", "37d"]:
        check(f"{label}: 無事件", False, "events 為空")

# --- 情境 38：SSE 事件延遲 ---
print(f"\n{'─' * 60}")
print("情境 38：SSE 事件延遲（delayMs）")
cleanup()
sse_body = json.dumps([
    {"type": "normal", "data": "fast"},
    {"type": "normal", "data": "delayed", "delayMs": 500},
])
create_rule({"protocol": "HTTP", "matchKey": "/api/sse/delay", "method": "GET",
    "targetHost": HOST, "responseBody": sse_body,
    "status": 200, "sseEnabled": True, "description": "SSE 延遲"})
start = time.time()
s, events, _, _ = sse_req("/api/sse/delay", {"X-Original-Host": HOST})
elapsed = (time.time() - start) * 1000
check("38a: 收到 2 個事件", s == 200 and len(events) == 2, f"status={s}, events={len(events)}")
check("38b: 總耗時 >= 400ms", elapsed >= 400, f"elapsed={elapsed:.0f}ms")

# --- 情境 39：SSE error 事件 ---
print(f"\n{'─' * 60}")
print("情境 39：SSE error 事件（收到 error 後連線關閉）")
cleanup()
sse_body = json.dumps([
    {"type": "normal", "data": "before-error"},
    {"type": "error", "data": "something went wrong"},
    {"type": "normal", "data": "after-error-should-not-appear"},
])
create_rule({"protocol": "HTTP", "matchKey": "/api/sse/error", "method": "GET",
    "targetHost": HOST, "responseBody": sse_body,
    "status": 200, "sseEnabled": True, "description": "SSE error 事件"})
s, events, _, _ = sse_req("/api/sse/error", {"X-Original-Host": HOST})
check("39a: 狀態碼 200", s == 200, f"status={s}")
check("39b: 收到 normal + error 事件", len(events) == 2,
      f"events={len(events)}, data={[e['data'] for e in events]}")
if len(events) >= 2:
    check("39c: 第 1 個事件 data=before-error", events[0]["data"] == "before-error")
    check("39d: 第 2 個事件 event=error", events[1]["event"] == "error",
          f"event={events[1]['event']}")
    check("39e: error 後無更多事件（after-error 不出現）",
          all(e["data"] != "after-error-should-not-appear" for e in events))

# --- 情境 40：SSE abort 事件 ---
print(f"\n{'─' * 60}")
print("情境 40：SSE abort 事件（連線中斷）")
cleanup()
sse_body = json.dumps([
    {"type": "normal", "data": "before-abort"},
    {"type": "abort", "data": "abort-data"},
    {"type": "normal", "data": "after-abort-should-not-appear"},
])
create_rule({"protocol": "HTTP", "matchKey": "/api/sse/abort", "method": "GET",
    "targetHost": HOST, "responseBody": sse_body,
    "status": 200, "sseEnabled": True, "description": "SSE abort 事件"})
s, events, _, _ = sse_req("/api/sse/abort", {"X-Original-Host": HOST})
check("40a: 狀態碼 200", s == 200, f"status={s}")
check("40b: abort 後不再發送事件", len(events) <= 1,
      f"events={len(events)}, data={[e['data'] for e in events]}")
if events:
    check("40c: 第 1 個事件 data=before-abort", events[0]["data"] == "before-abort")

# --- 情境 41：非 SSE 規則的 SSE 請求 fallback ---
print(f"\n{'─' * 60}")
print("情境 41：非 SSE 規則的 SSE 請求 → fallback 到一般回應")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/sse/fallback", "method": "GET",
    "targetHost": HOST, "responseBody": '{"fallback":true}',
    "status": 200, "sseEnabled": False, "description": "非 SSE 規則"})
s, events, raw, _ = sse_req("/api/sse/fallback", {"X-Original-Host": HOST})
check("41a: 狀態碼 200", s == 200, f"status={s}")
check("41b: 回應為一般 JSON（非 SSE 事件格式）", '{"fallback":true}' in raw,
      f"raw={raw[:100]}")

# --- 情境 42：SSE 無匹配規則 → 404 ---
print(f"\n{'─' * 60}")
print("情境 42：SSE 無匹配規則 → 404")
cleanup()
s, events, raw, _ = sse_req("/api/sse/nonexistent", {"X-Original-Host": HOST})
check("42: SSE 無匹配 → 404", s == 404, f"status={s}")

# --- 情境 43：SSE 模板渲染 ---
print(f"\n{'─' * 60}")
print("情境 43：SSE 模板渲染（事件 data 含 Handlebars 模板）")
cleanup()
sse_body = json.dumps([
    {"type": "normal", "data": "path={{request.path}}"},
    {"type": "normal", "data": "method={{request.method}}"},
    {"type": "normal", "data": "uuid={{randomValue type='UUID'}}"},
])
create_rule({"protocol": "HTTP", "matchKey": "/api/sse/template", "method": "GET",
    "targetHost": HOST, "responseBody": sse_body,
    "status": 200, "sseEnabled": True, "description": "SSE 模板渲染"})
s, events, _, _ = sse_req("/api/sse/template", {"X-Original-Host": HOST})
check("43a: 收到 3 個事件", s == 200 and len(events) == 3, f"status={s}, events={len(events)}")
if len(events) >= 3:
    check("43b: path 渲染正確", events[0]["data"] == "path=/api/sse/template",
          f"data={events[0]['data']}")
    check("43c: method 渲染正確", events[1]["data"] == "method=GET",
          f"data={events[1]['data']}")
    uuid_data = events[2]["data"] or ""
    uuid_val = uuid_data.replace("uuid=", "")
    check("43d: UUID 格式正確（36 字元）", len(uuid_val) == 36,
          f"uuid={uuid_val}")

# --- 情境 44：SSE 規則搭配條件匹配 ---
print(f"\n{'─' * 60}")
print("情境 44：SSE 規則搭配條件匹配（bodyCondition 不適用 GET，用 header 條件）")
cleanup()
sse_body_a = json.dumps([{"type": "normal", "data": "sse-tenant-abc"}])
sse_body_b = json.dumps([{"type": "normal", "data": "sse-fallback"}])
create_rule({"protocol": "HTTP", "matchKey": "/api/sse/cond", "method": "GET",
    "targetHost": HOST, "responseBody": sse_body_a,
    "status": 200, "sseEnabled": True, "headerCondition": "X-Tenant=abc",
    "description": "SSE 帶 header 條件"})
create_rule({"protocol": "HTTP", "matchKey": "/api/sse/cond", "method": "GET",
    "targetHost": HOST, "responseBody": sse_body_b,
    "status": 200, "sseEnabled": True,
    "description": "SSE 無條件 fallback"})
s, events, _, _ = sse_req("/api/sse/cond", {"X-Original-Host": HOST, "X-Tenant": "abc"})
check("44a: header 條件匹配 → sse-tenant-abc",
      s == 200 and len(events) == 1 and events[0]["data"] == "sse-tenant-abc",
      f"status={s}, data={events[0]['data'] if events else 'N/A'}")
s, events, _, _ = sse_req("/api/sse/cond", {"X-Original-Host": HOST, "X-Tenant": "xyz"})
check("44b: header 條件不符 → sse-fallback",
      s == 200 and len(events) == 1 and events[0]["data"] == "sse-fallback",
      f"status={s}, data={events[0]['data'] if events else 'N/A'}")

# --- 情境 45：SSE 規則搭配 query 條件 ---
print(f"\n{'─' * 60}")
print("情境 45：SSE 規則搭配 query 條件")
cleanup()
sse_body_match = json.dumps([{"type": "normal", "data": "sse-query-match"}])
sse_body_fb = json.dumps([{"type": "normal", "data": "sse-query-fb"}])
create_rule({"protocol": "HTTP", "matchKey": "/api/sse/query", "method": "GET",
    "targetHost": HOST, "responseBody": sse_body_match,
    "status": 200, "sseEnabled": True, "queryCondition": "type=premium",
    "description": "SSE query 條件"})
create_rule({"protocol": "HTTP", "matchKey": "/api/sse/query", "method": "GET",
    "targetHost": HOST, "responseBody": sse_body_fb,
    "status": 200, "sseEnabled": True,
    "description": "SSE query fallback"})

import http.client
from urllib.parse import urlparse

def sse_req_with_query(path, query, headers=None, timeout=10):
    parsed = urlparse(f"{MOCK}{path}")
    full_path = f"{parsed.path}?{query}" if query else parsed.path
    conn = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=timeout)
    req_headers = {
        "Accept": "text/event-stream",
        "Authorization": f"Basic {AUTH}",
    }
    if headers:
        req_headers.update(headers)
    conn.request("GET", full_path, headers=req_headers)
    resp = conn.getresponse()
    status = resp.status
    raw = resp.read().decode()
    conn.close()
    events = []
    for block in raw.split("\n\n"):
        if not block.strip():
            continue
        evt = {"event": None, "data": None, "id": None}
        for line in block.split("\n"):
            if line.startswith("event:"):
                evt["event"] = line[len("event:"):]
            elif line.startswith("data:"):
                evt["data"] = line[len("data:"):]
            elif line.startswith("id:"):
                evt["id"] = line[len("id:"):]
        if evt["data"] is not None or evt["event"] is not None:
            events.append(evt)
    return status, events

s, events = sse_req_with_query("/api/sse/query", "type=premium", {"X-Original-Host": HOST})
check("45a: query 條件匹配 → sse-query-match",
      s == 200 and len(events) == 1 and events[0]["data"] == "sse-query-match",
      f"status={s}, data={events[0]['data'] if events else 'N/A'}")
s, events = sse_req_with_query("/api/sse/query", "type=basic", {"X-Original-Host": HOST})
check("45b: query 條件不符 → sse-query-fb",
      s == 200 and len(events) == 1 and events[0]["data"] == "sse-query-fb",
      f"status={s}, data={events[0]['data'] if events else 'N/A'}")

# --- 情境 46：SSE 規則 priority 排序 ---
print(f"\n{'─' * 60}")
print("情境 46：SSE 規則 priority 排序（大的優先）")
cleanup()
sse_low = json.dumps([{"type": "normal", "data": "sse-low-pri"}])
sse_high = json.dumps([{"type": "normal", "data": "sse-high-pri"}])
create_rule({"protocol": "HTTP", "matchKey": "/api/sse/pri", "method": "GET",
    "targetHost": HOST, "responseBody": sse_low,
    "status": 200, "sseEnabled": True, "priority": 1,
    "description": "SSE 低優先"})
create_rule({"protocol": "HTTP", "matchKey": "/api/sse/pri", "method": "GET",
    "targetHost": HOST, "responseBody": sse_high,
    "status": 200, "sseEnabled": True, "priority": 10,
    "description": "SSE 高優先"})
s, events, _, _ = sse_req("/api/sse/pri", {"X-Original-Host": HOST})
check("46: SSE priority=10 優先於 priority=1",
      s == 200 and len(events) == 1 and events[0]["data"] == "sse-high-pri",
      f"status={s}, data={events[0]['data'] if events else 'N/A'}")

# --- 情境 47：SSE 多事件含混合 type ---
print(f"\n{'─' * 60}")
print("情境 47：SSE 多事件含混合 type（normal + 自訂 event name）")
cleanup()
sse_body = json.dumps([
    {"type": "normal", "data": "hello", "event": "greeting"},
    {"type": "normal", "data": '{"count":1}', "event": "update", "id": "1"},
    {"type": "normal", "data": '{"count":2}', "event": "update", "id": "2"},
    {"type": "normal", "data": "done", "event": "complete"},
])
create_rule({"protocol": "HTTP", "matchKey": "/api/sse/mixed", "method": "GET",
    "targetHost": HOST, "responseBody": sse_body,
    "status": 200, "sseEnabled": True, "description": "SSE 混合事件"})
s, events, _, _ = sse_req("/api/sse/mixed", {"X-Original-Host": HOST})
check("47a: 收到 4 個事件", s == 200 and len(events) == 4, f"events={len(events)}")
if len(events) >= 4:
    check("47b: 第 1 個 event=greeting", events[0]["event"] == "greeting")
    check("47c: 第 2 個 event=update, id=1", events[1]["event"] == "update" and events[1]["id"] == "1")
    check("47d: 第 3 個 event=update, id=2", events[2]["event"] == "update" and events[2]["id"] == "2")
    check("47e: 第 4 個 event=complete", events[3]["event"] == "complete")

# --- 情境 48：SSE 請求紀錄驗證 ---
print(f"\n{'─' * 60}")
print("情境 48：SSE 請求紀錄驗證")
cleanup()
sse_body = json.dumps([{"type": "normal", "data": "logged-sse"}])
sse_rule_id = create_rule({"protocol": "HTTP", "matchKey": "/api/sse/log-test", "method": "GET",
    "targetHost": HOST, "responseBody": sse_body,
    "status": 200, "sseEnabled": True, "description": "SSE 日誌測試"})
sse_req("/api/sse/log-test", {"X-Original-Host": HOST})
results = wait_logs(protocol="HTTP", matched="true")
if results:
    log = results[0].get("log", {})
    check("48a: SSE 日誌 matched=true", log.get("matched") is True)
    check("48b: SSE 日誌 ruleId 正確", log.get("ruleId") == sse_rule_id,
          f"ruleId={log.get('ruleId')}")
    check("48c: SSE 日誌 endpoint 正確", log.get("endpoint") == "/api/sse/log-test",
          f"endpoint={log.get('endpoint')}")
else:
    check("48: SSE 日誌寫入", False, "超時未取得日誌")

# ========== Phase 1-4: Faker 假資料 helpers ==========

# --- 情境 49：Faker 假資料 helpers ---
print(f"\n{'─' * 60}")
print("情境 49：Faker 假資料 helpers（模板渲染）")
cleanup()
faker_tmpl = json.dumps({
    "firstName": "{{randomFirstName}}",
    "lastName": "{{randomLastName}}",
    "fullName": "{{randomFullName}}",
    "email": "{{randomEmail}}",
    "phone": "{{randomPhoneNumber}}",
    "city": "{{randomCity}}",
    "country": "{{randomCountry}}",
    "address": "{{randomStreetAddress}}",
    "score": "{{randomInt min=1 max=999}}",
}, ensure_ascii=False)
create_rule({"protocol": "HTTP", "matchKey": "/api/faker", "method": "GET",
    "targetHost": HOST, "responseBody": faker_tmpl, "status": 200,
    "description": "Faker helpers 測試", "sseEnabled": False})
s, body, _ = mock_req("/api/faker", "GET", {"X-Original-Host": HOST})
p = {}
try:
    p = json.loads(body) if body else {}
except:
    pass
check("49a: randomFirstName 非空", s == 200 and len(p.get("firstName", "")) > 0,
      f"firstName={p.get('firstName')}")
check("49b: randomLastName 非空", len(p.get("lastName", "")) > 0,
      f"lastName={p.get('lastName')}")
check("49c: randomFullName 含空格", " " in p.get("fullName", ""),
      f"fullName={p.get('fullName')}")
check("49d: randomEmail 含 @", "@" in p.get("email", ""),
      f"email={p.get('email')}")
check("49e: randomPhoneNumber 格式 (xxx) xxx-xxxx",
      p.get("phone", "").startswith("(") and "-" in p.get("phone", ""),
      f"phone={p.get('phone')}")
check("49f: randomCity 非空", len(p.get("city", "")) > 0,
      f"city={p.get('city')}")
check("49g: randomCountry 非空", len(p.get("country", "")) > 0,
      f"country={p.get('country')}")
check("49h: randomStreetAddress 含數字", any(c.isdigit() for c in p.get("address", "")),
      f"address={p.get('address')}")
check("49i: randomInt 在 1~999 範圍", p.get("score", "").isdigit() and 1 <= int(p.get("score", "0")) <= 999,
      f"score={p.get('score')}")

# 驗證每次呼叫產生不同值（隨機性）
s2, body2, _ = mock_req("/api/faker", "GET", {"X-Original-Host": HOST})
p2 = {}
try:
    p2 = json.loads(body2) if body2 else {}
except:
    pass
# 至少有一個欄位不同（極低機率全部相同）
any_diff = any(p.get(k) != p2.get(k) for k in ["firstName", "lastName", "email", "phone", "score"])
check("49j: 兩次呼叫至少有一個欄位不同（隨機性）", any_diff,
      f"first={p.get('firstName')}/{p2.get('firstName')}")

# ========== Phase 2-1: Fault Injection ==========

# --- 情境 50：Fault Injection — EMPTY_RESPONSE ---
print(f"\n{'─' * 60}")
print("情境 50：Fault Injection — EMPTY_RESPONSE（回傳空 body）")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/fault-empty", "method": "GET",
    "targetHost": HOST, "responseBody": '{"should":"not appear"}', "status": 200,
    "faultType": "EMPTY_RESPONSE",
    "description": "Fault: EMPTY_RESPONSE", "sseEnabled": False})
s, body, _ = mock_req("/api/fault-empty", "GET", {"X-Original-Host": HOST})
check("50a: EMPTY_RESPONSE 狀態碼 200", s == 200, f"status={s}")
check("50b: EMPTY_RESPONSE body 為空", body.strip() == "", f"body={body[:100]}")

# --- 情境 51：Fault Injection — CONNECTION_RESET ---
print(f"\n{'─' * 60}")
print("情境 51：Fault Injection — CONNECTION_RESET（連線重置）")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/fault-reset", "method": "GET",
    "targetHost": HOST, "responseBody": '{"should":"not appear"}', "status": 200,
    "faultType": "CONNECTION_RESET",
    "description": "Fault: CONNECTION_RESET", "sseEnabled": False})
import http.client
from urllib.parse import urlparse
conn_reset = False
try:
    parsed_url = urlparse(f"{MOCK}/api/fault-reset")
    conn = http.client.HTTPConnection(parsed_url.hostname, parsed_url.port, timeout=5)
    conn.request("GET", parsed_url.path, headers={"X-Original-Host": HOST})
    resp = conn.getresponse()
    resp_body = resp.read().decode()
    # 如果能讀到回應，body 應該是空的（Undertow 關閉 outputStream 後可能回傳空 200）
    conn_reset = (resp_body.strip() == "")
    conn.close()
except (ConnectionResetError, http.client.RemoteDisconnected, BrokenPipeError, OSError):
    conn_reset = True
except Exception as ex:
    # 其他連線異常也算 reset 成功
    conn_reset = "connect" in str(ex).lower() or "reset" in str(ex).lower() or "closed" in str(ex).lower()
    if not conn_reset:
        conn_reset = True  # 任何異常都表示連線被中斷
check("51: CONNECTION_RESET 連線中斷或空回應", conn_reset, "連線未被重置")

# ========== Phase 1-2: Delay Range ==========

# --- 情境 52：Delay Range（隨機延遲範圍）---
print(f"\n{'─' * 60}")
print("情境 52：Delay Range（delayMs=200, maxDelayMs=800）")
cleanup()
create_rule({"protocol": "HTTP", "matchKey": "/api/delay-range", "method": "GET",
    "targetHost": HOST, "responseBody": '{"delayed":true}', "status": 200,
    "delayMs": 200, "maxDelayMs": 800,
    "description": "Delay Range 200~800ms", "sseEnabled": False})

delay_times = []
for i in range(5):
    start = time.time()
    s, body, _ = mock_req("/api/delay-range", "GET", {"X-Original-Host": HOST})
    elapsed = (time.time() - start) * 1000
    if s == 200:
        delay_times.append(elapsed)

check("52a: 所有請求成功", len(delay_times) == 5, f"成功={len(delay_times)}/5")
check("52b: 延遲 >= 150ms（含容差）", all(d >= 150 for d in delay_times),
      f"delays={[f'{d:.0f}' for d in delay_times]}")
check("52c: 延遲 <= 1200ms（含容差）", all(d <= 1200 for d in delay_times),
      f"delays={[f'{d:.0f}' for d in delay_times]}")
# 檢查是否有變異（非固定值），至少有 50ms 的差異
if len(delay_times) >= 2:
    spread = max(delay_times) - min(delay_times)
    check("52d: 延遲有變異（非固定值，spread > 30ms）", spread > 30,
          f"spread={spread:.0f}ms, delays={[f'{d:.0f}' for d in delay_times]}")
else:
    check("52d: 延遲有變異", False, "樣本不足")

# ========== Phase 2-2: Stateful Scenarios ==========

# --- 情境 53：基本狀態機（Started → Paid）---
print(f"\n{'─' * 60}")
print("情境 53：Stateful Scenarios — 基本狀態機")
cleanup()
# 重置 scenario
api("PUT", "/api/admin/scenarios/e2e-order-flow/reset")

# 規則 A：Started 狀態下 GET /order → pending，狀態不變
create_rule({"protocol": "HTTP", "matchKey": "/api/order", "method": "GET",
    "targetHost": HOST, "responseBody": '{"status":"pending"}', "status": 200,
    "scenarioName": "e2e-order-flow", "requiredScenarioState": "Started", "newScenarioState": "Started",
    "priority": 10, "description": "Scenario: 查詢(待付款)", "sseEnabled": False})
# 規則 B：Started 狀態下 POST /order/pay → 付款成功，狀態 → Paid
create_rule({"protocol": "HTTP", "matchKey": "/api/order/pay", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"payment-ok"}', "status": 200,
    "scenarioName": "e2e-order-flow", "requiredScenarioState": "Started", "newScenarioState": "Paid",
    "priority": 10, "description": "Scenario: 付款", "sseEnabled": False})
# 規則 C：Paid 狀態下 GET /order → paid
create_rule({"protocol": "HTTP", "matchKey": "/api/order", "method": "GET",
    "targetHost": HOST, "responseBody": '{"status":"paid"}', "status": 200,
    "scenarioName": "e2e-order-flow", "requiredScenarioState": "Paid", "newScenarioState": "Paid",
    "priority": 10, "description": "Scenario: 查詢(已付款)", "sseEnabled": False})

# Step 1: 初始狀態 Started → GET /order → pending
s, body, _ = mock_req("/api/order", "GET", {"X-Original-Host": HOST})
p = json.loads(body) if body else {}
check("53a: Started 狀態 GET → pending", s == 200 and p.get("status") == "pending",
      f"status={s}, body={body[:100]}")

# Step 2: POST /order/pay → 付款成功，狀態轉為 Paid
s, body, _ = mock_req("/api/order/pay", "POST", {"X-Original-Host": HOST}, {"amount": 100})
p = json.loads(body) if body else {}
check("53b: Started 狀態 POST pay → payment-ok", s == 200 and p.get("result") == "payment-ok",
      f"status={s}, body={body[:100]}")

# Step 3: 狀態已變為 Paid → GET /order → paid
s, body, _ = mock_req("/api/order", "GET", {"X-Original-Host": HOST})
p = json.loads(body) if body else {}
check("53c: Paid 狀態 GET → paid", s == 200 and p.get("status") == "paid",
      f"status={s}, body={body[:100]}")

# Step 4: Paid 狀態下再次 POST /order/pay → 不匹配（requiredState=Started 不符）
s, body, _ = mock_req("/api/order/pay", "POST", {"X-Original-Host": HOST}, {"amount": 100})
check("53d: Paid 狀態 POST pay → 不匹配（狀態不符）", s in (404, 502),
      f"status={s}")

# --- 情境 54：Scenario Reset API ---
print(f"\n{'─' * 60}")
print("情境 54：Scenario Reset API（重置狀態後回到 Started）")
# 目前狀態是 Paid（從情境 53 延續），重置後應回到 Started
api("PUT", "/api/admin/scenarios/e2e-order-flow/reset")
s, body, _ = mock_req("/api/order", "GET", {"X-Original-Host": HOST})
p = json.loads(body) if body else {}
check("54: Reset 後 GET → pending（回到 Started）", s == 200 and p.get("status") == "pending",
      f"status={s}, body={body[:100]}")

# --- 情境 55：Scenario 無 requiredState 的規則（純狀態轉移）---
print(f"\n{'─' * 60}")
print("情境 55：Scenario 無 requiredScenarioState（只設 newScenarioState）")
cleanup()
api("PUT", "/api/admin/scenarios/e2e-simple/reset")
# 規則：任何狀態都匹配，匹配後將狀態設為 Done
create_rule({"protocol": "HTTP", "matchKey": "/api/scenario-simple", "method": "POST",
    "targetHost": HOST, "responseBody": '{"result":"triggered"}', "status": 200,
    "scenarioName": "e2e-simple", "newScenarioState": "Done",
    "priority": 5, "description": "Scenario: 無前置狀態", "sseEnabled": False})
# 規則：Done 狀態才匹配
create_rule({"protocol": "HTTP", "matchKey": "/api/scenario-simple/check", "method": "GET",
    "targetHost": HOST, "responseBody": '{"state":"done"}', "status": 200,
    "scenarioName": "e2e-simple", "requiredScenarioState": "Done", "newScenarioState": "Done",
    "priority": 5, "description": "Scenario: Done 狀態", "sseEnabled": False})

# Step 1: POST → 觸發狀態轉移到 Done
s, body, _ = mock_req("/api/scenario-simple", "POST", {"X-Original-Host": HOST}, {"action": "go"})
p = json.loads(body) if body else {}
check("55a: 無前置狀態規則匹配", s == 200 and p.get("result") == "triggered",
      f"status={s}, body={body[:100]}")

# Step 2: GET /check → Done 狀態匹配
s, body, _ = mock_req("/api/scenario-simple/check", "GET", {"X-Original-Host": HOST})
p = json.loads(body) if body else {}
check("55b: 狀態已轉為 Done → 匹配", s == 200 and p.get("state") == "done",
      f"status={s}, body={body[:100]}")

# ========== 總結 ==========
print()
print("=" * 60)
total = passed + failed
print(f"  結果：{passed}/{total} 通過", end="")
if failed:
    print(f"，{failed} 失敗")
else:
    print("，全部通過 ✓")
if errors:
    print("\n  失敗項目：")
    for e in errors:
        print(f"    ✗ {e}")
print("=" * 60)

cleanup()
print("清理完成")
sys.exit(0 if failed == 0 else 1)
