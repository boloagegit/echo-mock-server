#!/usr/bin/env python3
"""
XML 條件調整影響檢查

2026-10 起，XML body 條件在 HTTP 與 JMS 的比對方式統一（見 CHANGELOG「XML body conditions behave the same…」）。
這個工具讀取伺服器上所有規則，列出用到「行為有改變」寫法的規則，讓你確認新的結果是否符合預期：

  1. 相對路徑（例如 Order/Status=OPEN、Item/@sku=S1，開頭沒有 /）
     以前：HTTP 一定比不到；JMS 要看同一個 queue 的其他規則而定。
     現在：路徑結尾符合就比得到（例如 <Root><Order><Status>）。
  2. XML 條件用了 ==（例如 Status==OPEN）
     以前：HTTP 把第二個 = 當成期望值的一部分（期望 "=OPEN"），所以比不到；JMS 看情況。
     現在：== 視為 =。JSON 欄位條件本來就這樣處理，只有收到 XML 的請求會不同。

另外有一項無法只看規則判斷，只在報告最後提醒：沒有前綴的名稱（例如 Item）現在也會比到有命名空間前綴的
元素（例如 <ns:Item>）。只有 XML 內容使用前綴時才有影響。

只讀取規則，不會修改任何資料。

用法：
  python3 scripts/check-xml-condition-changes.py [BASE_URL] [--json]
帳號密碼（伺服器要求登入時）：ECHO_TEST_USERNAME / ECHO_TEST_PASSWORD 環境變數，預設 admin／admin。
"""

import base64
import json
import os
import sys
import urllib.request

RELATIVE_PATH = "relativePath"
DOUBLE_EQUALS = "doubleEquals"

# 與伺服器的 XmlPath 相同：含這些字元的是一般 XPath（由 XPath 引擎處理，行為沒有改變）
XPATH_ONLY_CHARS = set("[]()*|:")


def condition_parts(fragment):
    """與伺服器相同的拆法：第一個 = 之前是欄位；= 前一個字元是 ! * ~ 時是 != *= ~=。沒有 = 時回傳 None。"""
    eq = fragment.find("=")
    if eq <= 0:
        return None
    previous = fragment[eq - 1]
    operator = previous + "=" if previous in "!*~" else "="
    field = fragment[:eq - len(operator) + 1].strip()
    value = fragment[eq + 1:].strip()
    return field, operator, value


def is_relative_simple_path(path):
    """開頭不是 / 而且有兩層以上的簡單路徑（例如 Order/Status、Item/@sku）；判斷方式與伺服器的 XmlPath 相同。"""
    if not path or path.startswith("/") or path.startswith("$."):
        return False
    path = path.strip()
    if path.endswith("/text()"):
        path = path[: -len("/text()")]
    if any(ch in XPATH_ONLY_CHARS for ch in path):
        return False
    parts = path.split("/")
    if len(parts) < 2:
        return False
    for i, part in enumerate(parts):
        if not part or part in (".", "..") or any(ch.isspace() for ch in part):
            return False
        if part.startswith("@") and (i != len(parts) - 1 or len(part) == 1):
            return False
    return not parts[0].startswith("@")


def legacy_findings(condition, where):
    """一段舊格式 body 條件（以 ; 分隔）中行為有改變的片段。"""
    findings = []
    for fragment in (condition or "").split(";"):
        fragment = fragment.strip()
        parts = condition_parts(fragment) if fragment else None
        if not parts:
            continue
        field, operator, value = parts
        if field.startswith("$."):
            continue  # JSONPath 不受影響
        if is_relative_simple_path(field):
            findings.append({"type": RELATIVE_PATH, "where": where, "condition": fragment})
        if operator == "=" and value.startswith("="):
            findings.append({"type": DOUBLE_EQUALS, "where": where, "condition": fragment})
    return findings


def match_spec_findings(node, where="match"):
    """結構化條件（matchSpec）中的 body xpath 相對路徑，以及其中的舊格式 body 片段。"""
    findings = []
    if isinstance(node, list):
        for item in node:
            findings.extend(match_spec_findings(item, where))
        return findings
    if not isinstance(node, dict):
        return findings
    for key in ("all", "any"):
        if isinstance(node.get(key), list):
            findings.extend(match_spec_findings(node[key], where))
    if isinstance(node.get("not"), dict):
        findings.extend(match_spec_findings(node["not"], where))
    legacy = node.get("legacy")
    if isinstance(legacy, dict) and legacy.get("source") == "body":
        findings.extend(legacy_findings(legacy.get("condition"), where))
    selector = node.get("selector")
    if node.get("source") == "body" and isinstance(selector, dict) and isinstance(selector.get("xpath"), str):
        if is_relative_simple_path(selector["xpath"]):
            findings.append({"type": RELATIVE_PATH, "where": where, "condition": "xpath " + selector["xpath"]})
    return findings


def rule_findings(rule):
    """一條規則中所有行為有改變的條件。"""
    findings = legacy_findings(rule.get("bodyCondition"), "body")
    spec = rule.get("matchSpec")
    if isinstance(spec, str):
        try:
            spec = json.loads(spec)
        except ValueError:
            spec = None
    if spec is not None:
        findings.extend(match_spec_findings(spec))
    return findings


def describe_route(rule):
    if rule.get("protocol") == "JMS":
        return "JMS " + str(rule.get("queueName") or rule.get("matchKey") or "")
    return "{} {}".format(rule.get("method") or "*", rule.get("matchKey") or "")


def analyse(rules):
    """回傳 {checked, affected: [{rule, route, protocol, enabled, findings}]}。"""
    affected = []
    for rule in rules:
        findings = rule_findings(rule)
        if findings:
            affected.append({
                "id": rule.get("id"),
                "description": rule.get("description") or "",
                "route": describe_route(rule),
                "protocol": rule.get("protocol") or "HTTP",
                "enabled": rule.get("enabled") is not False,
                "findings": findings,
            })
    return {"checked": len(rules), "affected": affected}


BEFORE = {
    ("HTTP", RELATIVE_PATH): "以前一定比不到；現在 XML 有這個路徑（結尾符合）就比得到",
    ("JMS", RELATIVE_PATH): "以前要看同一個 queue 的其他規則才知道比不比得到；現在 XML 有這個路徑（結尾符合）就比得到",
    ("HTTP", DOUBLE_EQUALS): "收到 XML 時：以前把第二個 = 當成期望值，所以比不到；現在 == 視為 =（JSON 不受影響）",
    ("JMS", DOUBLE_EQUALS): "收到 XML 時：以前看同一個 queue 的其他規則而定；現在 == 視為 =",
}


def render_text(report):
    lines = ["XML 條件調整影響檢查", "=" * 20,
             "共檢查 {} 條規則，{} 條用到行為有改變的寫法。".format(report["checked"], len(report["affected"])), ""]
    if not report["affected"]:
        lines.append("沒有規則用到相對路徑或 XML 的 ==，這次調整不會改變你的規則結果。")
    for item in report["affected"]:
        title = "{}  「{}」  ({})".format(item["route"], item["description"] or "（沒有說明）", item["id"])
        if not item["enabled"]:
            title += "  [已停用]"
        lines.append(title)
        for finding in item["findings"]:
            lines.append("    {}：{}".format(finding["condition"], BEFORE[(item["protocol"], finding["type"])]))
        lines.append("")
    if report["affected"]:
        lines += [
            "請確認：上面的規則現在可能會命中以前不會命中的請求。符合預期就不用改；不符合預期，",
            "把相對路徑改成從根開始的完整路徑（例如 /Root/Order/Status），或把 == 改成 =。",
        ]
    lines += [
        "",
        "另外提醒：沒有前綴的名稱（例如 Item）現在也會比到有命名空間前綴的元素（例如 <ns:Item>）。",
        "只有 XML 內容使用前綴時才有影響，無法只看規則判斷。",
    ]
    return "\n".join(lines)


def fetch_rules(base_url):
    user = os.getenv("ECHO_TEST_USERNAME", "admin")
    password = os.getenv("ECHO_TEST_PASSWORD", "admin")
    request = urllib.request.Request(base_url.rstrip("/") + "/api/admin/rules", headers={
        "Accept": "application/json",
        "Authorization": "Basic " + base64.b64encode("{}:{}".format(user, password).encode()).decode(),
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def main(argv):
    args = [arg for arg in argv if not arg.startswith("--")]
    base_url = args[0] if args else "http://localhost:8080"
    report = analyse(fetch_rules(base_url))
    if "--json" in argv:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(render_text(report))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
