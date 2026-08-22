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

The positional interface is intentionally kept compatible with the original
script.  ``--json`` emits one machine-readable JSON document on stdout;
``--json-output PATH`` can additionally persist the same document for callers
which already capture stdout/stderr separately.
"""

import argparse
import base64
import json
import os
import sys
import time
import urllib.request
import urllib.error
import threading
import statistics
from collections import defaultdict


DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_DURATION = 10
DEFAULT_CONCURRENCY = 20
DEFAULT_REQUEST_TIMEOUT = 30.0
DEFAULT_CLEANUP_ATTEMPTS = 5
DEFAULT_CLEANUP_RETRY_DELAY = 0.5
DEFAULT_LOG_DRAIN_TIMEOUT = 120.0
DEFAULT_LOG_DRAIN_INTERVAL = 0.5
HOST = "rps-stress.api.test"

# Keep these names available for callers which imported the old script.  The
# executable path now parses arguments in ``main`` instead of at import time,
# which also makes the benchmark safe to exercise from unit tests.
BASE_URL = DEFAULT_BASE_URL
DURATION = DEFAULT_DURATION
CONCURRENCY = DEFAULT_CONCURRENCY


def auth_header() -> str:
    credentials = (
        f"{os.getenv('ECHO_TEST_USERNAME', 'admin')}:"
        f"{os.getenv('ECHO_TEST_PASSWORD', 'admin')}"
    )
    return base64.b64encode(credentials.encode("utf-8")).decode("ascii")


def is_success_status(status: int) -> bool:
    """Return whether an HTTP status is a successful (2xx) response."""
    return 200 <= status < 300


def cleanup_api(path, base_url, attempts=DEFAULT_CLEANUP_ATTEMPTS,
                retry_delay=DEFAULT_CLEANUP_RETRY_DELAY):
    """Run an idempotent cleanup, retrying only transient HTTP conflicts/errors."""
    attempts = max(1, attempts)
    last_status = 0
    for attempt in range(1, attempts + 1):
        last_status, _ = api("DELETE", path, base_url=base_url)
        if is_success_status(last_status):
            return {"passed": True, "status": last_status, "attempts": attempt}
        retryable = last_status == 409 or 500 <= last_status < 600
        if not retryable or attempt == attempts:
            break
        time.sleep(retry_delay)
    return {"passed": False, "status": last_status, "attempts": attempt}


def wait_for_log_agent_drain(base_url, timeout=DEFAULT_LOG_DRAIN_TIMEOUT,
                             interval=DEFAULT_LOG_DRAIN_INTERVAL):
    """Wait until accepted request logs have left the durable background queue."""
    started = time.monotonic()
    deadline = started + max(0.0, timeout)
    last_queue_size = None
    while True:
        status, agents = api("GET", "/api/admin/agents", base_url=base_url)
        if not is_success_status(status) or not isinstance(agents, list):
            return {
                "passed": False,
                "status": status,
                "reason": "agent status endpoint unavailable",
                "duration_seconds": time.monotonic() - started,
            }
        log_agent = next(
            (agent for agent in agents
             if isinstance(agent, dict) and agent.get("name") == "log-agent"),
            None,
        )
        if log_agent is None:
            return {
                "passed": False,
                "status": status,
                "reason": "log-agent status is missing",
                "duration_seconds": time.monotonic() - started,
            }
        try:
            last_queue_size = int(log_agent.get("queueSize", -1))
        except (TypeError, ValueError):
            last_queue_size = -1
        if last_queue_size == 0:
            return {
                "passed": True,
                "status": status,
                "queue_size": 0,
                "duration_seconds": time.monotonic() - started,
            }
        if time.monotonic() >= deadline:
            return {
                "passed": False,
                "status": status,
                "queue_size": last_queue_size,
                "reason": "log-agent queue did not drain before timeout",
                "duration_seconds": time.monotonic() - started,
            }
        time.sleep(interval)


def api(method, path, data=None, base_url=None):
    """Call the admin API and preserve non-2xx/request failures as status 0+."""
    url = f"{(base_url or BASE_URL).rstrip('/')}{path}"
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json")
    req.add_header("Authorization", f"Basic {auth_header()}")
    try:
        with urllib.request.urlopen(req, timeout=DEFAULT_REQUEST_TIMEOUT) as resp:
            raw = resp.read()
            status = int(resp.status)
    except urllib.error.HTTPError as error:
        try:
            raw = error.read()
        except OSError:
            raw = b""
        status = int(error.code)
    except (urllib.error.URLError, TimeoutError, OSError):
        # A status of zero is never successful and is represented explicitly
        # in the JSON status histogram as a request/transport error.
        return 0, ""

    try:
        return status, json.loads(raw.decode("utf-8")) if raw.strip() else None
    except (UnicodeDecodeError, json.JSONDecodeError):
        return status, raw.decode("utf-8", errors="replace")


def mock_request(base_url, path, query, headers, body_bytes):
    """Issue one mock request, returning status 0 for transport failures."""
    url = f"{base_url.rstrip('/')}/mock{path}{query}"
    req = urllib.request.Request(url, data=body_bytes, method="POST")
    for key, value in headers.items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=DEFAULT_REQUEST_TIMEOUT) as resp:
            resp.read()
            return int(resp.status)
    except urllib.error.HTTPError as error:
        try:
            error.read()
        except OSError:
            pass
        return int(error.code)
    except (urllib.error.URLError, TimeoutError, OSError):
        return 0


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
        lines.append('        </ord:Item>')
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


def run_scenario(scenario, base_url=None, duration=None, concurrency=None, verbose=True):
    """執行單一場景的 RPS 測試，並將所有非 2xx 記為失敗。"""
    base_url = base_url or BASE_URL
    duration = DURATION if duration is None else duration
    concurrency = CONCURRENCY if concurrency is None else concurrency
    name = scenario["name"]
    path = scenario["path"]
    query = scenario.get("query", "")
    headers = scenario["request_headers"]
    body_bytes = scenario["request_body"].encode("utf-8")

    setup_errors = 0
    setup_request_errors = 0
    setup_non_2xx = 0
    for rule in scenario["rules"]:
        status, _ = api("POST", "/api/admin/rules", rule, base_url)
        if not is_success_status(status):
            setup_errors += 1
            if status == 0:
                setup_request_errors += 1
            else:
                setup_non_2xx += 1

    warmup_errors = 0
    warmup_request_errors = 0
    warmup_non_2xx = 0
    for _ in range(2):
        status = mock_request(base_url, path, query, headers, body_bytes)
        if not is_success_status(status):
            warmup_errors += 1
            if status == 0:
                warmup_request_errors += 1
            else:
                warmup_non_2xx += 1
    time.sleep(0.3)

    latencies = []
    status_codes = defaultdict(int)
    lock = threading.Lock()
    stop_event = threading.Event()
    errors = [0]
    request_errors = [0]
    non_2xx = [0]

    def worker():
        local_latencies = []
        local_errors = 0
        local_request_errors = 0
        local_non_2xx = 0
        local_status = defaultdict(int)
        while not stop_event.is_set():
            started = time.monotonic()
            status = mock_request(base_url, path, query, headers, body_bytes)
            local_latencies.append((time.monotonic() - started) * 1000)
            local_status[status] += 1
            if not is_success_status(status):
                local_errors += 1
                if status == 0:
                    local_request_errors += 1
                else:
                    local_non_2xx += 1
        with lock:
            latencies.extend(local_latencies)
            errors[0] += local_errors
            request_errors[0] += local_request_errors
            non_2xx[0] += local_non_2xx
            for status, count in local_status.items():
                status_codes[status] += count

    threads = []
    for _ in range(concurrency):
        thread = threading.Thread(target=worker, daemon=True)
        thread.start()
        threads.append(thread)
    time.sleep(duration)
    stop_event.set()
    for thread in threads:
        thread.join(timeout=5)

    latencies.sort()
    total = len(latencies)
    if latencies:
        summary = {
            "avg": statistics.mean(latencies),
            "p50": latencies[int(len(latencies) * 0.50)],
            "p95": latencies[int(len(latencies) * 0.95)],
            "p99": latencies[int(min(len(latencies) * 0.99, len(latencies) - 1))],
            "min": min(latencies),
            "max": max(latencies),
        }
    else:
        summary = {key: 0 for key in ("avg", "p50", "p95", "p99", "min", "max")}
    result = {
        "name": name,
        "total": total,
        "rps": total / duration if duration > 0 else 0,
        "errors": setup_errors + warmup_errors + errors[0],
        "request_errors": setup_request_errors + warmup_request_errors + request_errors[0],
        "non_2xx": setup_non_2xx + warmup_non_2xx + non_2xx[0],
        "setup_errors": setup_errors,
        "warmup_errors": warmup_errors,
        **summary,
        "status_codes": dict(status_codes),
        "body_size": len(body_bytes),
    }
    if verbose:
        print(f"\n{'─' * 65}")
        print(f"  {name}")
        print(f"  Body: {len(body_bytes):,} bytes")
        print(f"  規則數: {len(scenario['rules'])}")
        print(f"  RPS: {result['rps']:,.0f}  總請求: {result['total']:,}  錯誤: {result['errors']}")
    return result


def run_benchmark(base_url=BASE_URL, duration=DURATION, concurrency=CONCURRENCY,
                  verbose=True):
    """Run all scenarios and return the JSON-serializable benchmark result."""
    if duration < 0:
        raise ValueError("duration must be zero or greater")
    if concurrency < 1:
        raise ValueError("concurrency must be at least 1")

    if verbose:
        print("=" * 65)
        print("  RPS 壓力測試")
        print(f"  目標: {base_url}")
        print(f"  持續: {duration}s / 並發: {concurrency} threads")
        print("=" * 65)

    all_results = []
    cleanup_errors = 0
    cleanup_request_errors = 0
    cleanup_non_2xx = 0
    cleanup_retries = 0

    def cleanup(path):
        nonlocal cleanup_errors, cleanup_request_errors, cleanup_non_2xx, cleanup_retries
        result = cleanup_api(path, base_url)
        cleanup_retries += result["attempts"] - 1
        if not result["passed"]:
            cleanup_errors += 1
            status = result["status"]
            if status == 0:
                cleanup_request_errors += 1
            else:
                cleanup_non_2xx += 1

    cleanup("/api/admin/rules/all")
    cleanup("/api/admin/responses/orphans")
    cleanup("/api/admin/logs/all")
    time.sleep(0.5)

    for index, scenario in enumerate(SCENARIOS):
        if verbose:
            print(f"\n  [{index + 1}/{len(SCENARIOS)}] {scenario['name']}")
            sys.stdout.write(f"  測試中 ({duration}s)...")
            sys.stdout.flush()
        result = run_scenario(scenario, base_url, duration, concurrency, verbose=False)
        all_results.append(result)
        if verbose:
            print(" 完成")
            print(f"  RPS: {result['rps']:,.0f}  總請求: {result['total']:,}  "
                  f"錯誤: {result['errors']}")

        cleanup("/api/admin/rules/all")
        cleanup("/api/admin/responses/orphans")
        time.sleep(0.3)

    log_drain = wait_for_log_agent_drain(base_url)
    cleanup("/api/admin/logs/all")
    total_errors = sum(result["errors"] for result in all_results) + cleanup_errors
    if not log_drain["passed"]:
        total_errors += 1
    total_request_errors = (
        sum(result["request_errors"] for result in all_results) + cleanup_request_errors
    )
    total_non_2xx = sum(result["non_2xx"] for result in all_results) + cleanup_non_2xx
    payload = {
        "schema_version": 1,
        "script": "stress-test-rps.py",
        "base_url": base_url,
        "parameters": {
            "duration_seconds": duration,
            "concurrency": concurrency,
            "scenario_count": len(SCENARIOS),
        },
        "duration": duration,
        "duration_seconds": duration,
        "concurrency": concurrency,
        "scenarios": all_results,
        "results": all_results,
        "cleanup_errors": cleanup_errors,
        "cleanup_retries": cleanup_retries,
        "log_drain": log_drain,
        "total_requests": sum(result["total"] for result in all_results),
        "errors": total_errors,
        "total_errors": total_errors,
        "request_errors": total_request_errors,
        "non_2xx": total_non_2xx,
        "passed": total_errors == 0,
    }
    if verbose:
        print()
        print("=" * 65)
        print("  總結")
        print("=" * 65)
        print(f"  {'場景':<35} {'Body':>8} {'RPS':>8} {'avg':>7} {'p50':>7} {'p95':>7} {'p99':>7}")
        print(f"  {'─'*35} {'─'*8} {'─'*8} {'─'*7} {'─'*7} {'─'*7} {'─'*7}")
        for result in all_results:
            body_label = (
                f"{result['body_size']//1024}KB"
                if result["body_size"] >= 1024
                else f"{result['body_size']}B"
            )
            print(f"  {result['name']:<35} {body_label:>8} "
                  f"{result['rps']:>7,.0f} {result['avg']:>6.1f}ms "
                  f"{result['p50']:>6.1f}ms {result['p95']:>6.1f}ms "
                  f"{result['p99']:>6.1f}ms")
        print("=" * 65)
        print(f"\n完成（{'PASS' if payload['passed'] else 'FAIL'}）")
    return payload


def make_parser():
    parser = argparse.ArgumentParser(
        description="Measure Echo mock throughput across four representative scenarios."
    )
    parser.add_argument("base_url", nargs="?", default=DEFAULT_BASE_URL,
                        help=f"Echo base URL (default: {DEFAULT_BASE_URL})")
    parser.add_argument("duration", nargs="?", type=int, default=DEFAULT_DURATION,
                        help=f"duration in seconds (default: {DEFAULT_DURATION})")
    parser.add_argument("concurrency", nargs="?", type=int, default=DEFAULT_CONCURRENCY,
                        help=f"worker threads (default: {DEFAULT_CONCURRENCY})")
    parser.add_argument("--json", "--machine-readable", dest="json_output", action="store_true",
                        help="emit one machine-readable JSON document on stdout")
    parser.add_argument("--json-output", "--json-file", dest="json_file", type=os.fspath,
                        help="also write the machine-readable JSON document to PATH")
    return parser


def main(argv=None):
    args = make_parser().parse_args(argv)
    if args.duration < 0:
        print("error: duration must be zero or greater", file=sys.stderr)
        return 2
    if args.concurrency < 1:
        print("error: concurrency must be at least 1", file=sys.stderr)
        return 2
    try:
        payload = run_benchmark(args.base_url, args.duration, args.concurrency,
                                verbose=not args.json_output)
    except Exception as error:
        payload = {
            "schema_version": 1,
            "script": "stress-test-rps.py",
            "base_url": args.base_url,
            "parameters": {
                "duration_seconds": args.duration,
                "concurrency": args.concurrency,
            },
            "duration": args.duration,
            "duration_seconds": args.duration,
            "concurrency": args.concurrency,
            "errors": 1,
            "request_errors": 0,
            "non_2xx": 0,
            "passed": False,
            "error": str(error),
        }
        if not args.json_output:
            print(f"error: {error}", file=sys.stderr)

    encoded = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.json_file:
        output_path = os.path.abspath(args.json_file)
        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        with open(output_path, "w", encoding="utf-8") as output:
            output.write(encoded)
    if args.json_output:
        sys.stdout.write(encoded)
    return 0 if payload.get("passed") else 1


if __name__ == "__main__":
    raise SystemExit(main())
