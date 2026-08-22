#!/usr/bin/env python3
"""
Black-box persistence regression for a running Echo instance.

The script deliberately uses only the Python standard library.  It writes a
unique sentinel set through the HTTP API, verifies the values immediately,
and can verify the same set after the server has been restarted.  It never
uses a global delete endpoint; cleanup is limited to IDs recorded in the
sentinel file.

Examples:

    # Create and verify a sentinel set.  The records are intentionally kept.
    python3 scripts/test-rdbms-persistence.py http://localhost:8080

    # Restart Echo outside this script, then verify the same records.
    python3 scripts/test-rdbms-persistence.py http://localhost:8080 \
        --restart --state-file /tmp/echo-rdbms-sentinel.json

    # Remove only the records recorded in the sentinel file.
    python3 scripts/test-rdbms-persistence.py http://localhost:8080 \
        --cleanup --state-file /tmp/echo-rdbms-sentinel.json

Credentials come from ECHO_TEST_USERNAME / ECHO_TEST_PASSWORD.  The default
is admin/admin for disposable local instances.  ECHO_BASE_URL and
ECHO_RDBMS_STATE_FILE can be used instead of positional/configuration values.
"""

from __future__ import annotations

import argparse
import base64
from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import secrets
import sys
import tempfile
import time
from typing import Any, Dict, Iterable, List, Optional
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


MIN_LARGE_BODY_BYTES = 1024 * 1024
DEFAULT_TIMEOUT_SECONDS = 45
DEFAULT_WAIT_SECONDS = 90
EXPECTED_LOG_REQUESTS = 3
STATE_SCHEMA = 1


class TestFailure(RuntimeError):
    """A fatal test failure which prevents a meaningful continuation."""


@dataclass
class ApiResult:
    status: int
    data: Any
    text: str
    elapsed_ms: float


class ApiClient:
    """Small urllib client which preserves status and response text on errors."""

    def __init__(self, base_url: str, username: str, password: str,
                 timeout: float = DEFAULT_TIMEOUT_SECONDS) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        self.authorization = f"Basic {token}"

    def request(self, method: str, path: str, data: Any = None,
                headers: Optional[Dict[str, str]] = None,
                timeout: Optional[float] = None) -> ApiResult:
        payload = None
        request_headers = {
            "Accept": "application/json",
            "Authorization": self.authorization,
        }
        if data is not None:
            if isinstance(data, bytes):
                payload = data
            elif isinstance(data, str):
                payload = data.encode("utf-8")
            else:
                payload = json.dumps(
                    data, ensure_ascii=False, separators=(",", ":")
                ).encode("utf-8")
            request_headers["Content-Type"] = "application/json; charset=utf-8"
        if headers:
            request_headers.update(headers)

        request = Request(
            f"{self.base_url}{path}",
            data=payload,
            method=method.upper(),
            headers=request_headers,
        )
        started = time.monotonic()
        try:
            with urlopen(request, timeout=timeout or self.timeout) as response:
                raw = response.read()
                status = response.status
        except HTTPError as error:
            raw = error.read() if error.fp else b""
            status = error.code
        except (OSError, URLError, TimeoutError) as error:
            elapsed = (time.monotonic() - started) * 1000
            return ApiResult(0, None, str(error), elapsed)

        elapsed = (time.monotonic() - started) * 1000
        text = raw.decode("utf-8", errors="replace")
        try:
            parsed = json.loads(text) if text.strip() else None
        except json.JSONDecodeError:
            parsed = None
        return ApiResult(status, parsed, text, elapsed)

    def wait_until_ready(self, timeout_seconds: int) -> None:
        deadline = time.monotonic() + timeout_seconds
        last = "no response"
        while time.monotonic() < deadline:
            result = self.request("GET", "/api/admin/status", timeout=3)
            if result.status == 200 and isinstance(result.data, dict):
                return
            last = f"status={result.status}, body={short_result(result)}"
            time.sleep(0.5)
        raise TestFailure(f"server did not become ready within {timeout_seconds}s ({last})")


def short_result(result: ApiResult, limit: int = 500) -> str:
    if result.data is not None:
        try:
            value = json.dumps(result.data, ensure_ascii=False, separators=(",", ":"))
        except (TypeError, ValueError):
            value = str(result.data)
    else:
        value = result.text
    value = value.replace("\n", "\\n")
    return value if len(value) <= limit else value[:limit] + "..."


def query_path(path: str, params: Dict[str, Any]) -> str:
    filtered = [(key, value) for key, value in params.items() if value is not None]
    if not filtered:
        return path
    return f"{path}?{urlencode(filtered)}"


def iso_timestamp(value: Any) -> Optional[datetime]:
    if not isinstance(value, str) or not value.strip():
        return None
    normalized = value.strip()
    if normalized.endswith("Z"):
        normalized = normalized[:-1] + "+00:00"
    try:
        return datetime.fromisoformat(normalized)
    except ValueError:
        return None


def int_value(value: Any) -> Optional[int]:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    if isinstance(value, str) and value.strip().isdigit():
        return int(value.strip())
    return None


def make_large_xml(run_id: str) -> str:
    """Build deterministic valid XML whose UTF-8 size is at least 1 MiB."""
    prefix = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        f'<persistence-test run="{run_id}">'
    )
    suffix = "</persistence-test>"
    unit = (
        '<item n="{0}"><name>交易測試-資料保存-🚀-漢字-Ελληνικά</name>'
        '<value>跨資料庫 round-trip &amp; Unicode ✓</value></item>'
    )
    items: List[str] = []
    encoded_size = len(prefix.encode("utf-8")) + len(suffix.encode("utf-8"))
    index = 0
    while encoded_size < MIN_LARGE_BODY_BYTES:
        item = unit.format(index)
        items.append(item)
        encoded_size += len(item.encode("utf-8"))
        index += 1
    return prefix + "".join(items) + suffix


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def bool_is(value: Any, expected: bool) -> bool:
    return isinstance(value, bool) and value is expected


class PersistenceTest:
    def __init__(self, client: ApiClient, state_path: Path,
                 wait_seconds: int) -> None:
        self.client = client
        self.state_path = state_path
        self.wait_seconds = wait_seconds
        self.failures: List[str] = []
        self.skips: List[str] = []
        self.ephemeral_rules: List[str] = []
        self.ephemeral_responses: List[int] = []
        self.state: Dict[str, Any] = {}

    def check(self, name: str, condition: bool, detail: str = "") -> None:
        if condition:
            print(f"  PASS {name}")
            return
        message = f"{name}: {detail}" if detail else name
        self.failures.append(message)
        print(f"  FAIL {message}")

    def skip(self, name: str, detail: str = "") -> None:
        message = f"{name}: {detail}" if detail else name
        self.skips.append(message)
        print(f"  SKIP {message}")

    def require(self, result: ApiResult, expected: Iterable[int], operation: str) -> Any:
        allowed = tuple(expected)
        if result.status not in allowed:
            raise TestFailure(
                f"{operation} failed: expected {allowed}, got {result.status}; "
                f"{short_result(result)}"
            )
        return result.data

    def require_dict(self, result: ApiResult, expected: Iterable[int], operation: str) -> Dict[str, Any]:
        data = self.require(result, expected, operation)
        if not isinstance(data, dict):
            raise TestFailure(f"{operation} returned non-object JSON: {short_result(result)}")
        return data

    def create_response(self, description: str, body: str,
                        content_type: str = "TEXT") -> Dict[str, Any]:
        result = self.client.request("POST", "/api/admin/responses", {
            "description": description,
            "body": body,
            "contentType": content_type,
        }, timeout=max(self.client.timeout, 120))
        return self.require_dict(result, (201,), "create response")

    def get_response(self, response_id: int) -> Dict[str, Any]:
        result = self.client.request("GET", f"/api/admin/responses/{response_id}",
                                     timeout=max(self.client.timeout, 120))
        return self.require_dict(result, (200,), f"get response {response_id}")

    def delete_response(self, response_id: int) -> ApiResult:
        return self.client.request("DELETE", f"/api/admin/responses/{response_id}")

    def create_rule(self, payload: Dict[str, Any], label: str) -> Dict[str, Any]:
        result = self.client.request("POST", "/api/admin/rules", payload)
        return self.require_dict(result, (201,), f"create {label} rule")

    def get_rule(self, rule_id: str) -> Dict[str, Any]:
        result = self.client.request("GET", f"/api/admin/rules/{rule_id}",
                                     timeout=max(self.client.timeout, 120))
        return self.require_dict(result, (200,), f"get rule {rule_id}")

    def delete_rule(self, rule_id: str) -> ApiResult:
        return self.client.request("DELETE", f"/api/admin/rules/{rule_id}")

    def run(self) -> None:
        if self.state_path.exists():
            raise TestFailure(
                f"state file already exists: {self.state_path}; verify or cleanup it first, "
                "or choose a different --state-file"
            )
        self.client.wait_until_ready(self.wait_seconds)

        run_id = time.strftime("%Y%m%d%H%M%S") + "-" + secrets.token_hex(4)
        target_host = f"rdbms-persist-{run_id}.test"
        endpoint_prefix = f"/rdbms-persist/{run_id}/logs/"
        main_endpoint = f"/rdbms-persist/{run_id}/main"
        large_body = make_large_xml(run_id)
        large_description = f"rdbms-persist-{run_id}-large-updated"
        initial_large_description = f"rdbms-persist-{run_id}-large"
        log_description = f"rdbms-persist-{run_id}-log"

        print(f"\nPersistence E2E against {self.client.base_url}")
        print(f"  run id: {run_id}")
        print(f"  large XML: {len(large_body.encode('utf-8'))} bytes")

        # Large Response create/read/update proves Unicode, LOB and timestamps.
        large = self.create_response(initial_large_description, large_body)
        large_id = int_value(large.get("id"))
        if large_id is None:
            raise TestFailure(f"created large response has invalid id: {short_result(ApiResult(200, large, '', 0))}")
        self.state.update({
            "schema": STATE_SCHEMA,
            "run_id": run_id,
            "target_host": target_host,
            "main_endpoint": main_endpoint,
            "log_endpoint_prefix": endpoint_prefix,
            "large_response_id": large_id,
            "large_body_bytes": len(large_body.encode("utf-8")),
            "large_body_sha256": sha256_text(large_body),
            "large_description": large_description,
            "log_description": log_description,
            "created_at": datetime.now().isoformat(timespec="seconds"),
            "expected_log_count": EXPECTED_LOG_REQUESTS,
        })
        self.check("large response body is at least 1 MiB", len(large_body.encode("utf-8")) >= MIN_LARGE_BODY_BYTES)
        self.check("large response id is numeric", large_id > 0)
        self.check("large response bodySize is UTF-8 byte length",
                    int_value(large.get("bodySize")) == len(large_body.encode("utf-8")),
                    f"bodySize={large.get('bodySize')}")
        self.check("response contentType enum persisted", large.get("contentType") == "TEXT",
                    f"contentType={large.get('contentType')!r}")
        self.check("response createdAt is a timestamp", iso_timestamp(large.get("createdAt")) is not None)
        self.check("response updatedAt is a timestamp", iso_timestamp(large.get("updatedAt")) is not None)

        fetched_large = self.get_response(large_id)
        self.check("large response read round-trip is exact", fetched_large.get("body") == large_body)
        self.check("large response Unicode survives round-trip", "交易測試" in str(fetched_large.get("body")))
        self.check("large response XML wrapper survives round-trip",
                    str(fetched_large.get("body", "")).startswith('<?xml version="1.0"') and
                    str(fetched_large.get("body", "")).endswith("</persistence-test>"))

        updated_large = self.client.request("PUT", f"/api/admin/responses/{large_id}", {
            "description": large_description,
            "body": large_body,
            "contentType": "TEXT",
        }, timeout=max(self.client.timeout, 120))
        updated_large_data = self.require_dict(updated_large, (200,), "update large response")
        self.check("response update changes description",
                    updated_large_data.get("description") == large_description)
        self.check("response update keeps large body",
                    updated_large_data.get("body") == large_body)
        self.check("response update advances version",
                    int_value(updated_large_data.get("version")) is not None and
                    int_value(updated_large_data.get("version")) >= 1,
                    f"version={updated_large_data.get('version')}")
        self.check("response updatedAt remains valid",
                    iso_timestamp(updated_large_data.get("updatedAt")) is not None)

        # Main rule references the large response but is not invoked, so the
        # log durability test remains small and focused.
        main_rule = self.create_rule({
            "protocol": "HTTP",
            "matchKey": main_endpoint,
            "method": "GET",
            "targetHost": target_host,
            "responseId": large_id,
            "status": 200,
            "priority": 17,
            "enabled": True,
            "isProtected": True,
            "description": f"rdbms-persist-{run_id}-main",
            "delayMs": 0,
            "sseEnabled": False,
            "sseLoopEnabled": False,
            "action": "MOCK",
            "faultType": "NONE",
        }, "main")
        main_rule_id = main_rule.get("id")
        if not isinstance(main_rule_id, str) or not main_rule_id:
            raise TestFailure(f"created main rule has invalid id: {main_rule_id!r}")
        self.state["main_rule_id"] = main_rule_id
        self.check("rule protocol enum persisted", main_rule.get("protocol") == "HTTP")
        self.check("rule response relation persisted",
                    int_value(main_rule.get("responseId")) == large_id)
        self.check("rule enabled boolean persisted", bool_is(main_rule.get("enabled"), True))
        self.check("rule protected boolean persisted", bool_is(main_rule.get("isProtected"), True))
        self.check("rule faultType enum persisted", main_rule.get("faultType") == "NONE",
                    f"faultType={main_rule.get('faultType')!r}")
        self.check("rule createdAt is a timestamp", iso_timestamp(main_rule.get("createdAt")) is not None)
        self.check("rule updatedAt is a timestamp", iso_timestamp(main_rule.get("updatedAt")) is not None)

        fetched_main = self.get_rule(main_rule_id)
        initial_rule_version = int_value(fetched_main.get("version"))
        self.check("rule read keeps target host", fetched_main.get("targetHost") == target_host)
        self.check("rule read keeps response relation",
                    int_value(fetched_main.get("responseId")) == large_id)
        self.check("rule read keeps boolean and enum fields",
                    bool_is(fetched_main.get("enabled"), True) and
                    bool_is(fetched_main.get("isProtected"), True) and
                    fetched_main.get("faultType") == "NONE")

        # Update only metadata.  Omitting responseBody is intentional: sending
        # a body together with responseId asks the API to create a new Response.
        updated_rule = self.client.request("PUT", f"/api/admin/rules/{main_rule_id}", {
            "protocol": "HTTP",
            "matchKey": main_endpoint,
            "method": "GET",
            "targetHost": target_host,
            "responseId": large_id,
            "status": 200,
            "priority": 23,
            "enabled": True,
            "isProtected": False,
            "description": f"rdbms-persist-{run_id}-main-updated",
            "delayMs": 0,
            "sseEnabled": False,
            "sseLoopEnabled": False,
            "action": "MOCK",
            "faultType": "NONE",
        })
        updated_rule_data = self.require_dict(updated_rule, (200,), "update main rule")
        self.check("rule update changes description",
                    updated_rule_data.get("description") == f"rdbms-persist-{run_id}-main-updated")
        self.check("rule update changes protected boolean",
                    bool_is(updated_rule_data.get("isProtected"), False))
        self.check("rule update retains response relation",
                    int_value(updated_rule_data.get("responseId")) == large_id)
        updated_rule_version = int_value(updated_rule_data.get("version"))
        self.check("rule update advances version",
                    updated_rule_version is not None and
                    (initial_rule_version is None or updated_rule_version > initial_rule_version),
                    f"before={initial_rule_version}, after={updated_rule_version}")
        self.check("rule update timestamps are valid",
                    iso_timestamp(updated_rule_data.get("createdAt")) is not None and
                    iso_timestamp(updated_rule_data.get("updatedAt")) is not None)
        self.state["main_rule_version"] = updated_rule_version

        # Small response/rule pair used for durable request-log checks.
        log_response = self.create_response(
            log_description,
            f"<log><run>{run_id}</run><ok>true</ok></log>",
        )
        log_response_id = int_value(log_response.get("id"))
        if log_response_id is None:
            raise TestFailure("log response did not return a numeric id")
        self.state["log_response_id"] = log_response_id
        log_rule = self.create_rule({
            "protocol": "HTTP",
            "matchKey": endpoint_prefix + "*",
            "method": "GET",
            "targetHost": target_host,
            "responseId": log_response_id,
            "status": 200,
            "priority": 9,
            "enabled": True,
            "isProtected": True,
            "description": log_description,
            "delayMs": 0,
            "sseEnabled": False,
            "sseLoopEnabled": False,
            "action": "MOCK",
            "faultType": "NONE",
        }, "log")
        log_rule_id = log_rule.get("id")
        if not isinstance(log_rule_id, str) or not log_rule_id:
            raise TestFailure("log rule did not return a valid id")
        self.state["log_rule_id"] = log_rule_id
        self.check("log rule wildcard relation persisted",
                    int_value(log_rule.get("responseId")) == log_response_id)

        # The declarative apply endpoint has deterministic stale-version
        # handling.  Older deployments without it are reported as skipped.
        self.test_optimistic_lock(run_id, target_host)

        # Verify rules-page filtering/pagination and response summary filtering.
        self.test_rule_query(run_id)
        self.test_response_summary(large_id, len(large_body.encode("utf-8")))

        # Three unique requests make both durable log insertion and pagination
        # observable without touching unrelated request logs.
        for suffix in ("c", "a", "b"):
            endpoint = endpoint_prefix + suffix
            result = self.client.request(
                "GET", "/mock" + endpoint,
                headers={"X-Original-Host": target_host},
                timeout=30,
            )
            self.check(f"mock request {suffix} returns 200", result.status == 200,
                       f"status={result.status}, body={short_result(result, 120)}")

        log_rows = self.wait_for_logs(endpoint_prefix, EXPECTED_LOG_REQUESTS)
        self.test_log_rows(log_rows, endpoint_prefix, log_rule_id)
        self.test_log_summary_and_pagination(endpoint_prefix, len(log_rows))

        # A disposable pair proves DELETE semantics while all sentinel rows
        # remain available for a later restart verification.
        self.test_delete_crud(run_id, target_host)

        self.state["expected_log_count"] = EXPECTED_LOG_REQUESTS
        self.write_state()
        self.report("create/verify")

    def test_optimistic_lock(self, run_id: str, target_host: str) -> None:
        endpoint = f"/rdbms-persist/{run_id}/lock"
        create_doc = {
            "apiVersion": "echo.mock/v1",
            "kind": "Rule",
            "spec": {
                "protocol": "HTTP",
                "targetHost": target_host,
                "matchKey": endpoint,
                "method": "GET",
                "description": f"rdbms-persist-{run_id}-lock",
                "enabled": True,
                "protected": False,
                "priority": 4,
                "delayMs": 0,
                "status": 200,
                "responseBody": {"lock": "v1", "run": run_id},
                "responseContentType": "TEXT",
                "sseEnabled": False,
                "sseLoopEnabled": False,
                "action": "MOCK",
                "faultType": "NONE",
            },
        }
        created = self.client.request("POST", "/api/admin/rules/apply", create_doc,
                                      timeout=max(self.client.timeout, 90))
        if created.status == 404:
            self.skip("optimistic lock conflict", "apply API is not available")
            return
        created_data = self.require_dict(created, (201,), "create apply lock rule")
        resource = created_data.get("resource")
        if not isinstance(resource, dict):
            raise TestFailure("apply create did not return resource")
        metadata = resource.get("metadata")
        spec = resource.get("spec")
        if not isinstance(metadata, dict) or not isinstance(spec, dict):
            raise TestFailure("apply resource is missing metadata/spec")
        rule_id = metadata.get("id")
        response_id = int_value(spec.get("responseId"))
        if not isinstance(rule_id, str) or not rule_id:
            raise TestFailure("apply lock rule id is invalid")
        self.state["lock_rule_id"] = rule_id
        if response_id is not None:
            self.state["lock_response_id"] = response_id

        current = json.loads(json.dumps(resource, ensure_ascii=False))
        current["spec"]["description"] = f"rdbms-persist-{run_id}-lock-updated"
        update = self.client.request("PUT", f"/api/admin/rules/{rule_id}/apply", current,
                                     timeout=max(self.client.timeout, 90))
        self.require_dict(update, (200,), "update apply lock rule")

        stale = json.loads(json.dumps(resource, ensure_ascii=False))
        stale["spec"]["description"] = f"rdbms-persist-{run_id}-lock-stale"
        conflict = self.client.request("PUT", f"/api/admin/rules/{rule_id}/apply", stale,
                                       timeout=max(self.client.timeout, 90))
        conflict_data = conflict.data if isinstance(conflict.data, dict) else {}
        self.check("stale optimistic-lock update returns 409",
                    conflict.status == 409 and
                    conflict_data.get("error") == "RESOURCE_VERSION_CONFLICT",
                    f"status={conflict.status}, body={short_result(conflict)}")

    def test_rule_query(self, run_id: str) -> None:
        path = query_path("/api/admin/rules/page", {
            "protocol": "HTTP",
            "enabled": "true",
            "keyword": f"rdbms-persist-{run_id}",
            "page": 0,
            "size": 1,
            "sort": "description",
            "direction": "asc",
        })
        result = self.client.request("GET", path)
        data = self.require_dict(result, (200,), "query filtered rule page")
        total = int_value(data.get("totalElements"))
        pages = int_value(data.get("totalPages"))
        rows = data.get("results")
        self.check("rule filter returns only test records", total is not None and total >= 2,
                    f"totalElements={data.get('totalElements')}")
        self.check("rule pagination reports multiple pages",
                    pages is not None and pages >= 2,
                    f"totalPages={data.get('totalPages')}")
        self.check("rule page respects size=1", isinstance(rows, list) and len(rows) == 1,
                    f"rows={len(rows) if isinstance(rows, list) else rows!r}")

    def test_response_summary(self, response_id: int, expected_body_size: int) -> None:
        path = query_path("/api/admin/responses/summary", {
            "keyword": self.state["large_description"],
            "usage": "used",
            "page": 0,
            "size": 1,
            "sort": "usageCount",
            "direction": "desc",
        })
        result = self.client.request("GET", path, timeout=max(self.client.timeout, 60))
        data = self.require_dict(result, (200,), "query filtered response summary")
        rows = data.get("results")
        matching = [row for row in rows or []
                    if isinstance(row, dict) and int_value(row.get("id")) == response_id]
        self.check("response summary keyword/usage filter finds large response", len(matching) == 1,
                    f"results={len(rows) if isinstance(rows, list) else rows!r}")
        if matching:
            row = matching[0]
            self.check("response summary keeps UTF-8 bodySize", int_value(row.get("bodySize")) == expected_body_size,
                       f"bodySize={row.get('bodySize')}")
            self.check("response summary reports usage", int_value(row.get("usageCount")) is not None and
                       int_value(row.get("usageCount")) >= 1)
        self.check("response summary pagination reports one matching page",
                    int_value(data.get("totalElements")) == 1 and int_value(data.get("totalPages")) == 1,
                    f"totalElements={data.get('totalElements')}, totalPages={data.get('totalPages')}")

        id_result = self.client.request("GET", query_path(
            "/api/admin/responses/summary", {
                "keyword": str(response_id),
                "page": 0,
                "size": 20,
            }), timeout=max(self.client.timeout, 60))
        id_data = self.require_dict(id_result, (200,), "search response summary by numeric id")
        id_matches = [int_value(row.get("id")) for row in id_data.get("results", [])
                      if isinstance(row, dict)]
        self.check("response summary numeric ID search finds the exact response",
                   response_id in id_matches, f"ids={id_matches}")

        # Exercise the stored SSE_EVENTS value through the public SSE/GENERAL
        # filter names. The literal percent/underscore pair and a deliberately
        # colliding description also prove that LIKE wildcards are escaped.
        sse_description = f"rdbms-persist-{self.state['run_id']}-100%_sse-summary"
        collision_description = f"rdbms-persist-{self.state['run_id']}-100XYZsse-summary"
        sse = self.create_response(sse_description, '[{"data":"ready"}]', "SSE_EVENTS")
        sse_id = int_value(sse.get("id"))
        if sse_id is None:
            raise TestFailure("SSE summary probe did not return a numeric id")
        self.ephemeral_responses.append(sse_id)
        collision = self.create_response(
            collision_description, '[{"data":"collision"}]', "SSE_EVENTS")
        collision_id = int_value(collision.get("id"))
        if collision_id is None:
            raise TestFailure("SSE collision probe did not return a numeric id")
        self.ephemeral_responses.append(collision_id)
        try:
            sse_result = self.client.request("GET", query_path(
                "/api/admin/responses/summary", {
                    "keyword": sse_description,
                    "contentType": "SSE",
                    "page": 0,
                    "size": 10,
                }))
            sse_data = self.require_dict(sse_result, (200,), "filter SSE response summary")
            sse_ids = [int_value(row.get("id")) for row in sse_data.get("results", [])
                       if isinstance(row, dict)]
            self.check("response summary SSE filter uses persisted enum value",
                       sse_ids == [sse_id], f"ids={sse_ids}")
            self.check("response summary treats percent and underscore as literals",
                       collision_id not in sse_ids, f"ids={sse_ids}")

            general_result = self.client.request("GET", query_path(
                "/api/admin/responses/summary", {
                    "keyword": self.state["large_description"],
                    "contentType": "GENERAL",
                    "page": 0,
                    "size": 10,
                }))
            general_data = self.require_dict(
                general_result, (200,), "filter general response summary")
            general_ids = [int_value(row.get("id")) for row in general_data.get("results", [])
                           if isinstance(row, dict)]
            self.check("response summary GENERAL filter excludes SSE",
                       response_id in general_ids and sse_id not in general_ids,
                       f"ids={general_ids}")
        finally:
            for probe_id in (sse_id, collision_id):
                deleted = self.delete_response(probe_id)
                self.check("SSE summary probe cleanup", deleted.status in (200, 404),
                           f"id={probe_id}, status={deleted.status}")
                if probe_id in self.ephemeral_responses:
                    self.ephemeral_responses.remove(probe_id)

    def wait_for_logs(self, endpoint_prefix: str, expected: int) -> List[Dict[str, Any]]:
        deadline = time.monotonic() + self.wait_seconds
        last_rows: List[Dict[str, Any]] = []
        path = query_path("/api/admin/logs", {
            "protocol": "HTTP",
            "matched": "true",
            "endpoint": endpoint_prefix,
            "page": 0,
            "size": 20,
            "sort": "endpoint",
            "direction": "asc",
        })
        while time.monotonic() < deadline:
            result = self.client.request("GET", path)
            if result.status == 200 and isinstance(result.data, dict):
                rows = result.data.get("results")
                if isinstance(rows, list):
                    last_rows = [row for row in rows if isinstance(row, dict)]
                    if len(last_rows) >= expected:
                        return last_rows
            time.sleep(0.4)
        self.check("durable request logs become queryable", False,
                   f"expected {expected}, found {len(last_rows)}")
        return last_rows

    def test_log_rows(self, rows: List[Dict[str, Any]], endpoint_prefix: str,
                      rule_id: str) -> None:
        self.check("durable log query returns all test requests", len(rows) >= EXPECTED_LOG_REQUESTS,
                   f"rows={len(rows)}")
        for row in rows:
            log = row.get("log") if isinstance(row.get("log"), dict) else row
            endpoint = log.get("endpoint") if isinstance(log, dict) else None
            self.check("log filter does not return another endpoint",
                       isinstance(endpoint, str) and endpoint.startswith(endpoint_prefix),
                       f"endpoint={endpoint!r}")
            self.check("log matched boolean is true",
                       isinstance(log, dict) and bool_is(log.get("matched"), True))
            self.check("log protocol enum is HTTP",
                       isinstance(log, dict) and log.get("protocol") == "HTTP")
            self.check("log rule relation is persisted",
                       isinstance(log, dict) and log.get("ruleId") == rule_id)
            self.check("log requestTime is a timestamp",
                       isinstance(log, dict) and iso_timestamp(log.get("requestTime")) is not None)

        if rows:
            log = rows[0].get("log") if isinstance(rows[0].get("log"), dict) else rows[0]
            log_id = int_value(log.get("id")) if isinstance(log, dict) else None
            if log_id is not None:
                detail = self.client.request("GET", f"/api/admin/logs/{log_id}/detail")
                detail_data = self.require_dict(detail, (200,), "get durable log detail")
                self.check("durable log detail retains response body",
                            f"<log><run>{self.state['run_id']}</run>" in str(detail_data.get("responseBody", "")))

    def test_log_summary_and_pagination(self, endpoint_prefix: str,
                                        observed_count: int) -> None:
        summary = self.client.request("GET", "/api/admin/logs/summary")
        summary_data = self.require_dict(summary, (200,), "get request-log summary")
        total = int_value(summary_data.get("totalRequests"))
        matched = int_value(summary_data.get("matchedRequests"))
        self.check("request-log summary counts persisted requests",
                    total is not None and total >= observed_count,
                    f"totalRequests={summary_data.get('totalRequests')}")
        self.check("request-log summary counts matched requests",
                    matched is not None and matched >= observed_count,
                    f"matchedRequests={summary_data.get('matchedRequests')}")
        self.check("request-log summary exposes matchRate",
                    isinstance(summary_data.get("matchRate"), (int, float)))

        path = query_path("/api/admin/logs", {
            "protocol": "HTTP",
            "matched": "true",
            "endpoint": endpoint_prefix,
            "page": 0,
            "size": 2,
            "sort": "endpoint",
            "direction": "asc",
        })
        page = self.client.request("GET", path)
        page_data = self.require_dict(page, (200,), "paginate durable request logs")
        rows = page_data.get("results")
        self.check("request-log pagination respects size=2",
                    isinstance(rows, list) and len(rows) == 2,
                    f"rows={len(rows) if isinstance(rows, list) else rows!r}")
        self.check("request-log pagination reports test total",
                    int_value(page_data.get("totalElements")) == observed_count,
                    f"totalElements={page_data.get('totalElements')}")
        self.check("request-log pagination reports multiple pages",
                    int_value(page_data.get("totalPages")) is not None and
                    int_value(page_data.get("totalPages")) >= 2,
                    f"totalPages={page_data.get('totalPages')}")

    def test_delete_crud(self, run_id: str, target_host: str) -> None:
        body = f"<delete><run>{run_id}</run></delete>"
        response = self.create_response(f"rdbms-persist-{run_id}-delete", body)
        response_id = int_value(response.get("id"))
        if response_id is None:
            raise TestFailure("delete probe response did not return a numeric id")
        self.ephemeral_responses.append(response_id)
        rule = self.create_rule({
            "protocol": "HTTP",
            "matchKey": f"/rdbms-persist/{run_id}/delete",
            "method": "GET",
            "targetHost": target_host,
            "responseId": response_id,
            "status": 200,
            "enabled": True,
            "isProtected": False,
            "description": f"rdbms-persist-{run_id}-delete-rule",
            "delayMs": 0,
            "sseEnabled": False,
            "sseLoopEnabled": False,
            "action": "MOCK",
            "faultType": "NONE",
        }, "delete-probe")
        rule_id = rule.get("id")
        if not isinstance(rule_id, str) or not rule_id:
            raise TestFailure("delete probe rule did not return a valid id")
        self.ephemeral_rules.append(rule_id)
        deleted_rule = self.delete_rule(rule_id)
        self.check("rule delete returns 204", deleted_rule.status == 204,
                   f"status={deleted_rule.status}, body={short_result(deleted_rule)}")
        missing_rule = self.client.request("GET", f"/api/admin/rules/{rule_id}")
        self.check("deleted rule is no longer readable", missing_rule.status == 404,
                   f"status={missing_rule.status}")
        deleted_response = self.delete_response(response_id)
        self.check("response delete returns 200", deleted_response.status == 200,
                   f"status={deleted_response.status}, body={short_result(deleted_response)}")
        missing_response = self.client.request("GET", f"/api/admin/responses/{response_id}")
        self.check("deleted response is no longer readable", missing_response.status == 404,
                   f"status={missing_response.status}")
        if rule_id in self.ephemeral_rules:
            self.ephemeral_rules.remove(rule_id)
        if response_id in self.ephemeral_responses:
            self.ephemeral_responses.remove(response_id)

    def verify(self) -> None:
        state = self.load_state()
        self.state = state
        self.client.wait_until_ready(self.wait_seconds)
        run_id = state.get("run_id")
        if not isinstance(run_id, str) or not run_id:
            raise TestFailure("state file has no run_id")
        large_id = int_value(state.get("large_response_id"))
        main_rule_id = state.get("main_rule_id")
        log_rule_id = state.get("log_rule_id")
        endpoint_prefix = state.get("log_endpoint_prefix")
        if large_id is None or not isinstance(main_rule_id, str) or not isinstance(log_rule_id, str):
            raise TestFailure("state file is missing sentinel IDs")
        if not isinstance(endpoint_prefix, str):
            raise TestFailure("state file is missing log endpoint prefix")

        print(f"\nPersistence restart verification against {self.client.base_url}")
        print(f"  run id: {run_id}")
        large_body = make_large_xml(run_id)
        large = self.get_response(large_id)
        self.check("sentinel large response still exists", int_value(large.get("id")) == large_id)
        self.check("sentinel large response body survives restart",
                    large.get("body") == large_body)
        self.check("sentinel large response checksum survives restart",
                    sha256_text(str(large.get("body", ""))) == state.get("large_body_sha256"))
        self.check("sentinel large response byte size survives restart",
                    int_value(large.get("bodySize")) == state.get("large_body_bytes"))
        self.check("sentinel response timestamp remains valid",
                    iso_timestamp(large.get("createdAt")) is not None and
                    iso_timestamp(large.get("updatedAt")) is not None)
        self.check("sentinel response description survives restart",
                    large.get("description") == state.get("large_description"))

        main = self.get_rule(main_rule_id)
        self.check("sentinel main rule survives restart", main.get("id") == main_rule_id)
        self.check("sentinel main rule retains response relation",
                    int_value(main.get("responseId")) == large_id)
        self.check("sentinel main rule retains boolean/enum fields",
                    bool_is(main.get("enabled"), True) and
                    bool_is(main.get("isProtected"), False) and
                    main.get("faultType") == "NONE")
        self.check("sentinel main rule timestamps remain valid",
                    iso_timestamp(main.get("createdAt")) is not None and
                    iso_timestamp(main.get("updatedAt")) is not None)

        log_rule = self.get_rule(log_rule_id)
        log_response_id = int_value(state.get("log_response_id"))
        self.check("sentinel log rule survives restart", log_rule.get("id") == log_rule_id)
        self.check("sentinel log rule retains response relation",
                    log_response_id is not None and int_value(log_rule.get("responseId")) == log_response_id)

        if isinstance(state.get("lock_rule_id"), str):
            lock = self.get_rule(state["lock_rule_id"])
            self.check("sentinel optimistic-lock rule survives restart",
                       lock.get("id") == state["lock_rule_id"])

        summary_path = query_path("/api/admin/responses/summary", {
            "keyword": state["large_description"],
            "usage": "used",
            "page": 0,
            "size": 1,
            "sort": "usageCount",
            "direction": "desc",
        })
        summary = self.client.request("GET", summary_path, timeout=max(self.client.timeout, 60))
        summary_data = self.require_dict(summary, (200,), "verify response summary after restart")
        summary_ids = [int_value(row.get("id")) for row in summary_data.get("results", [])
                       if isinstance(row, dict)]
        self.check("response summary still filters sentinel after restart", large_id in summary_ids,
                   f"ids={summary_ids}")

        rows = self.wait_for_logs(endpoint_prefix, int_value(state.get("expected_log_count")) or EXPECTED_LOG_REQUESTS)
        self.check("durable request logs survive restart",
                   len(rows) >= (int_value(state.get("expected_log_count")) or EXPECTED_LOG_REQUESTS),
                   f"rows={len(rows)}")
        self.test_log_rows(rows, endpoint_prefix, log_rule_id)
        self.report("restart/verify")

    def cleanup(self) -> None:
        state = self.load_state()
        self.client.wait_until_ready(self.wait_seconds)
        run_id = state.get("run_id")
        if not isinstance(run_id, str) or not run_id:
            raise TestFailure("state file has no run_id; refusing cleanup")
        rule_ids = [state.get(key) for key in (
            "main_rule_id", "log_rule_id", "lock_rule_id")
            if isinstance(state.get(key), str)]
        response_ids = [int_value(state.get(key)) for key in (
            "large_response_id", "log_response_id", "lock_response_id")
            if int_value(state.get(key)) is not None]
        print(f"\nCleaning only sentinel records from {self.state_path}")
        for rule_id in dict.fromkeys(rule_ids):
            existing = self.client.request("GET", f"/api/admin/rules/{rule_id}")
            if existing.status == 404:
                self.check(f"cleanup rule {rule_id}", True, "already absent")
                continue
            if existing.status != 200 or not isinstance(existing.data, dict):
                self.check(f"cleanup rule {rule_id}", False,
                           f"cannot verify ownership, status={existing.status}")
                continue
            if run_id not in str(existing.data.get("description", "")):
                self.check(f"cleanup rule {rule_id}", False,
                           "refused: description does not contain this run id")
                continue
            result = self.delete_rule(rule_id)
            self.check(f"cleanup rule {rule_id}", result.status in (204, 404),
                       f"status={result.status}, body={short_result(result)}")
        for response_id in dict.fromkeys(response_ids):
            existing = self.client.request("GET", f"/api/admin/responses/{response_id}",
                                           timeout=max(self.client.timeout, 60))
            if existing.status == 404:
                self.check(f"cleanup response {response_id}", True, "already absent")
                continue
            if existing.status != 200 or not isinstance(existing.data, dict):
                self.check(f"cleanup response {response_id}", False,
                           f"cannot verify ownership, status={existing.status}")
                continue
            if run_id not in str(existing.data.get("description", "")):
                self.check(f"cleanup response {response_id}", False,
                           "refused: description does not contain this run id")
                continue
            result = self.delete_response(response_id)
            self.check(f"cleanup response {response_id}", result.status in (200, 404),
                       f"status={result.status}, body={short_result(result)}")
        if self.failures:
            self.report("cleanup")
            raise TestFailure("cleanup did not remove every recorded sentinel")
        try:
            self.state_path.unlink()
        except FileNotFoundError:
            pass
        print("  PASS sentinel state file removed")

    def load_state(self) -> Dict[str, Any]:
        try:
            with self.state_path.open("r", encoding="utf-8") as handle:
                state = json.load(handle)
        except (OSError, json.JSONDecodeError) as error:
            raise TestFailure(f"cannot read state file {self.state_path}: {error}") from error
        if not isinstance(state, dict) or state.get("schema") != STATE_SCHEMA:
            raise TestFailure(f"unsupported state file schema in {self.state_path}")
        return state

    def write_state(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        temporary: Optional[Path] = None
        try:
            with tempfile.NamedTemporaryFile(
                    mode="w", encoding="utf-8", dir=self.state_path.parent,
                    prefix=f".{self.state_path.name}.", suffix=".tmp", delete=False) as handle:
                temporary = Path(handle.name)
                json.dump(self.state, handle, ensure_ascii=False, indent=2)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, self.state_path)
        finally:
            if temporary is not None and temporary.exists():
                temporary.unlink()
        print(f"  sentinel state: {self.state_path}")

    def report(self, phase: str) -> None:
        print(f"\n{phase}: {len(self.failures)} failure(s), {len(self.skips)} skip(s)")
        for failure in self.failures:
            print(f"  - {failure}")
        for skip in self.skips:
            print(f"  - {skip}")
        if self.failures:
            raise TestFailure(f"{phase} failed")

    def cleanup_ephemeral(self) -> None:
        # Best-effort cleanup for a failed run; sentinel rows are deliberately
        # left in place for diagnosis and are only removed by --cleanup.
        for rule_id in reversed(self.ephemeral_rules):
            try:
                self.delete_rule(rule_id)
            except (OSError, URLError):
                pass
        for response_id in reversed(self.ephemeral_responses):
            try:
                self.delete_response(response_id)
            except (OSError, URLError):
                pass


def default_state_path() -> Path:
    configured = os.getenv("ECHO_RDBMS_STATE_FILE")
    if configured:
        return Path(configured).expanduser()
    return Path(tempfile.gettempdir()) / "echo-rdbms-persistence-sentinel.json"


def parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Black-box Echo cross-database persistence test")
    parser.add_argument(
        "base_url", nargs="?", default=os.getenv("ECHO_BASE_URL", "http://localhost:8080"),
        help="running Echo base URL (default: ECHO_BASE_URL or http://localhost:8080)",
    )
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--verify-only", action="store_true",
                       help="verify an existing sentinel without changing data")
    modes.add_argument("--restart", action="store_true",
                       help="wait for a restarted server, then verify the existing sentinel")
    modes.add_argument("--cleanup", action="store_true",
                       help="delete only IDs recorded in the sentinel file")
    parser.add_argument("--state-file", type=Path, default=default_state_path(),
                        help="sentinel JSON path (default: ECHO_RDBMS_STATE_FILE or system temp)")
    parser.add_argument("--wait-seconds", type=int, default=DEFAULT_WAIT_SECONDS,
                        help=f"server readiness/log wait timeout (default: {DEFAULT_WAIT_SECONDS})")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS,
                        help=f"per-request timeout in seconds (default: {DEFAULT_TIMEOUT_SECONDS})")
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    if args.wait_seconds <= 0 or args.timeout <= 0:
        print("--wait-seconds and --timeout must be positive", file=sys.stderr)
        return 2
    username = os.getenv("ECHO_TEST_USERNAME", "admin")
    password = os.getenv("ECHO_TEST_PASSWORD", "admin")
    client = ApiClient(args.base_url, username, password, args.timeout)
    test = PersistenceTest(client, args.state_file.expanduser(), args.wait_seconds)
    try:
        if args.cleanup:
            test.cleanup()
        elif args.verify_only or args.restart:
            test.verify()
        else:
            test.run()
        return 0
    except TestFailure as error:
        test.cleanup_ephemeral()
        # Preserve any IDs already created so a failed run can still be
        # inspected or removed with the explicit --cleanup mode.
        if test.state.get("run_id") and not test.state_path.exists():
            try:
                test.write_state()
            except OSError:
                pass
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        test.cleanup_ephemeral()
        if test.state.get("run_id") and not test.state_path.exists():
            try:
                test.write_state()
            except OSError:
                pass
        print("ERROR: interrupted", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
