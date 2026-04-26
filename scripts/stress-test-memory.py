#!/usr/bin/env python3
"""
Worst case 記憶體測試

策略：
  1. 建立 2000 條 HTTP 規則 + 2000 條 JMS 規則（各有不同 path/queue）
  2. 每條規則的 response body 為 500KB（接近快取 threshold）
  3. 觸發所有規則的快取載入
  4. 填滿 request log（memory 模式 10,000 筆）
  5. 查看 heap 使用量

用法：python3 scripts/stress-test-memory.py [BASE_URL]
"""

import json, sys, time, urllib.request, base64

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
AUTH = base64.b64encode(b"admin:admin").decode()

def api(method, path, data=None, ct="application/json"):
    url = f"{BASE}{path}"
    body = json.dumps(data).encode() if data and ct == "application/json" else (data.encode() if data else None)
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", ct)
    req.add_header("Accept", "application/json")
    req.add_header("Authorization", f"Basic {AUTH}")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode() if e.fp else ""
        try: return e.code, json.loads(raw) if raw.strip() else {}
        except: return e.code, raw
    except Exception as ex:
        return 0, str(ex)

def get_heap():
    _, status = api("GET", "/api/admin/status")
    if isinstance(status, dict):
        used = status.get("jvmHeapUsed", 0)
        max_h = status.get("jvmHeapMax", 0)
        return used, max_h
    return 0, 0

def fmt_mb(b):
    return f"{b / 1024 / 1024:.0f}MB"

# 大 response body（接近 500KB threshold）
BIG_BODY = json.dumps({"data": "x" * (400 * 1024), "padding": "y" * (90 * 1024)})
BODY_SIZE = len(BIG_BODY)

print("=" * 60)
print("  Worst Case 記憶體測試")
print(f"  目標：{BASE}")
print(f"  Response body 大小：{BODY_SIZE:,} bytes ({fmt_mb(BODY_SIZE)})")
print("=" * 60)

# 基準
used, max_h = get_heap()
print(f"\n  基準 heap：{fmt_mb(used)} / {fmt_mb(max_h)}")

# 清理
api("DELETE", "/api/admin/rules/all")
api("DELETE", "/api/admin/responses/orphans")
api("DELETE", "/api/admin/logs/all")
time.sleep(1)

used, _ = get_heap()
print(f"  清理後 heap：{fmt_mb(used)}")

# === Phase 1：建立 2000 條 HTTP 規則（大 body）===
print(f"\n[Phase 1] 建立 2000 條 HTTP 規則（每條 {fmt_mb(BODY_SIZE)} body）...")
HTTP_COUNT = 2000
BATCH = 50

for s in range(0, HTTP_COUNT, BATCH):
    e = min(s + BATCH, HTTP_COUNT)
    batch = []
    for i in range(s, e):
        batch.append({
            "protocol": "HTTP",
            "matchKey": f"/mem/api-{i}",
            "method": "GET",
            "targetHost": f"host-{i % 100}.test",
            "responseBody": BIG_BODY,
            "status": 200,
            "bodyCondition": f"id={i}" if i % 3 == 0 else None,
            "priority": i % 5,
            "description": f"HTTP 規則 #{i}",
            "sseEnabled": False,
        })
    status, resp = api("POST", "/api/admin/rules/import-batch", batch)
    if s % 200 == 0:
        used, _ = get_heap()
        pct = (s + BATCH) * 100 // HTTP_COUNT
        sys.stdout.write(f"\r  進度：{min(s + BATCH, HTTP_COUNT)}/{HTTP_COUNT} ({pct}%) heap={fmt_mb(used)}")
        sys.stdout.flush()

used, _ = get_heap()
print(f"\n  HTTP 規則建立完成，heap：{fmt_mb(used)}")

# === Phase 2：建立 2000 條 JMS 規則（大 body）===
_, status = api("GET", "/api/admin/status")
jms_enabled = status.get("jmsEnabled", False) if isinstance(status, dict) else False

if jms_enabled:
    print(f"\n[Phase 2] 建立 2000 條 JMS 規則...")
    JMS_COUNT = 2000
    for s in range(0, JMS_COUNT, BATCH):
        e = min(s + BATCH, JMS_COUNT)
        batch = []
        for i in range(s, e):
            batch.append({
                "protocol": "JMS",
                "matchKey": f"QUEUE.{i % 100}",
                "responseBody": BIG_BODY,
                "bodyCondition": f"//type=TYPE_{i}",
                "priority": i % 5,
                "description": f"JMS 規則 #{i}",
            })
        api("POST", "/api/admin/rules/import-batch", batch)
        if s % 200 == 0:
            used, _ = get_heap()
            pct = (s + BATCH) * 100 // JMS_COUNT
            sys.stdout.write(f"\r  進度：{min(s + BATCH, JMS_COUNT)}/{JMS_COUNT} ({pct}%) heap={fmt_mb(used)}")
            sys.stdout.flush()

    used, _ = get_heap()
    print(f"\n  JMS 規則建立完成，heap：{fmt_mb(used)}")
else:
    print("\n[Phase 2] JMS 未啟用，跳過")

# === Phase 3：觸發快取載入（打 mock 請求）===
print(f"\n[Phase 3] 觸發快取載入（打 200 個不同 path 的 mock 請求）...")
for i in range(200):
    url = f"{BASE}/mock/mem/api-{i}"
    req = urllib.request.Request(url, method="GET")
    req.add_header("X-Original-Host", f"host-{i % 100}.test")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            r.read()
    except:
        pass

time.sleep(2)
used, _ = get_heap()
print(f"  快取載入後 heap：{fmt_mb(used)}")

# === Phase 4：填滿 request log ===
print(f"\n[Phase 4] 填滿 request log（打 1000 次請求）...")
for i in range(1000):
    url = f"{BASE}/mock/mem/api-{i % HTTP_COUNT}"
    req = urllib.request.Request(url, method="GET")
    req.add_header("X-Original-Host", f"host-{i % 100}.test")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            r.read()
    except:
        pass

time.sleep(2)
used, _ = get_heap()
print(f"  request log 填滿後 heap：{fmt_mb(used)}")

# === 總結 ===
_, status = api("GET", "/api/admin/status")
rule_count = status.get("ruleCount", "?") if isinstance(status, dict) else "?"
response_count = status.get("responseCount", "?") if isinstance(status, dict) else "?"
log_count = status.get("requestLogCount", "?") if isinstance(status, dict) else "?"
used, max_h = get_heap()

print()
print("=" * 60)
print("  Worst Case 記憶體總結")
print("=" * 60)
print(f"  規則數：{rule_count}")
print(f"  回應數：{response_count}")
print(f"  日誌數：{log_count}")
print(f"  Response body 大小：{fmt_mb(BODY_SIZE)} x {response_count}")
print(f"  Heap 使用：{fmt_mb(used)} / {fmt_mb(max_h)} ({used * 100 // max_h if max_h else 0}%)")
print("=" * 60)

# 清理
print("\n清理...")
api("DELETE", "/api/admin/rules/all")
api("DELETE", "/api/admin/responses/orphans")
api("DELETE", "/api/admin/logs/all")
time.sleep(2)
used, _ = get_heap()
print(f"清理後 heap：{fmt_mb(used)}")
print("完成")
