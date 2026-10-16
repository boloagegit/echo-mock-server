#!/usr/bin/env python3
"""Compare completed repeats; give WireMock the better of both measured pool configurations."""
import json
import statistics
from pathlib import Path

root = Path(__file__).resolve().parent / "results"
main = json.loads((root / "native-final/results.json").read_text())
extra = json.loads((root / "native-wiremock32/results.json").read_text())
groups = {
    "echo": [main[f"echo-native-r{i}"] for i in (1, 2)],
    "wm128": [main[f"wiremock-async-r{i}"] for i in (1, 2)],
    "wm32": [extra[f"wiremock-32-r{i}"] for i in (1, 2)],
}
for runs in groups.values():
    for run in runs:
        assert run["done"] and len(run["cases"]) == 13
        assert all(case["http_errors"] == case["socket_errors"] == 0 for case in run["cases"].values())

cases = [
    ("fixed-c8", "固定 JSON，8 連線"),
    ("fixed-c64", "固定 JSON，64 連線"),
    ("fixed-c256", "固定 JSON，256 連線"),
    ("tpl", "模板，8 連線"),
    ("xml-small", "1.5 KB XML，8 連線"),
    ("xml-large", "100 KB XML／條件在前，8 連線"),
    ("xml-last", "100 KB XML／條件在末，8 連線"),
    ("items2000", "2,000 個路徑隨機查找，8 連線"),
    ("big-response-c64", "256 KiB 回應，64 連線"),
    ("forward-fast-c64", "快速轉發，64 連線"),
    ("forward-slow-c64", "下游等待 50 ms，64 連線"),
    ("delay-c64", "Mock 延遲 50 ms，64 連線"),
    ("recovered-c8", "混合負載後固定 JSON，8 連線"),
]

def mean(group, case, field):
    return statistics.mean(run["cases"][case][field] for run in groups[group])

rows = {}
print("| 情境 | Echo req/s | WireMock 較高 req/s | 吞吐倍數 | Echo P99 ms | WireMock 較低 P99 ms |")
print("|---|---:|---:|---:|---:|---:|")
for case, label in cases:
    echo_rate = mean("echo", case, "rps")
    wm_rate_group = max(("wm32", "wm128"), key=lambda group: mean(group, case, "rps"))
    wm_p99_group = min(("wm32", "wm128"), key=lambda group: mean(group, case, "p99_ms"))
    wm_rate = mean(wm_rate_group, case, "rps")
    echo_p99 = mean("echo", case, "p99_ms")
    wm_p99 = mean(wm_p99_group, case, "p99_ms")
    rows[case] = {
        "echo_rps": echo_rate, "wiremock_rps": wm_rate, "rps_ratio": echo_rate / wm_rate,
        "wiremock_rps_configuration": wm_rate_group, "echo_p99_ms": echo_p99,
        "wiremock_p99_ms": wm_p99, "wiremock_p99_configuration": wm_p99_group,
        "echo_rps_repeats": [run["cases"][case]["rps"] for run in groups["echo"]],
    }
    print(f"| {label} | {echo_rate:,.0f} | {wm_rate:,.0f} | {echo_rate/wm_rate:.2f}× | {echo_p99:.2f} | {wm_p99:.2f} |")
(root.parent / "comparison.json").write_text(json.dumps(rows, indent=2) + "\n")
