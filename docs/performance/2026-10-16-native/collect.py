#!/usr/bin/env python3
"""Validate and retain compact comparison evidence. Usage: collect.py RUN_DIR NEW_DEST_DIR."""
import json
from pathlib import Path
import re
import shutil
import statistics
import sys

source, destination = map(Path, sys.argv[1:3])
destination.mkdir(parents=True, exist_ok=False)
assert not list(source.rglob("ABORTED")), "benchmark contains aborted runs"
rows = []


def milliseconds(value, unit):
    return float(value) * {"us": .001, "ms": 1, "s": 1000}[unit]


for run in sorted(source.glob("*-r*")):
    assert (run / "DONE").is_file(), f"incomplete run: {run}"
    log = (run / "server.log").read_text()
    if run.name.startswith("echo"):
        assert "retain Servlet handling" not in log, "native transport fell back to Servlet"
    for file in sorted(run.glob("wrk-*.txt")):
        value = file.read_text()
        assert not re.search(r"Non-2xx or 3xx responses: [1-9]", value), file
        sockets = re.search(r"Socket errors: connect (\d+), read (\d+), write (\d+), timeout (\d+)", value)
        assert sockets is None or all(int(v) == 0 for v in sockets.groups()), file
        rps = float(re.search(r"Requests/sec:\s*([\d.]+)", value)[1])
        requests = int(re.search(r"(\d+) requests in", value)[1])
        percentiles = {}
        for percent, number, unit in re.findall(r"^\s*(\d+(?:\.\d+)?)%\s+([\d.]+)(us|ms|s)\s*$", value, re.M):
            percentiles[str(float(percent))] = milliseconds(number, unit)
        rows.append(dict(run=run.name, scenario=file.stem[4:], requests=requests, rps=rps,
                         percentilesMs=percentiles))
    target = destination / run.name
    target.mkdir()
    for file in run.iterdir():
        if file.suffix in (".txt", ".json") or file.name == "DONE":
            shutil.copyfile(file, target / file.name)
    # Keep relevant operational messages; large setup/SQL logs remain in the execution workspace.
    (target / "operational-log.txt").write_text("\n".join(
        line for line in log.splitlines() if re.search(r"\b(WARN|ERROR)\b|retain Servlet handling|Started Echo", line)) + "\n")

assert rows, "no measurements"
groups = {}
for row in rows:
    key = ("echo" if row["run"].startswith("echo") else "wiremock", row["scenario"])
    groups.setdefault(key, []).append(row)
means = [dict(product=product, scenario=scenario, rounds=len(values),
              meanRps=statistics.mean(v["rps"] for v in values),
              meanP99Ms=statistics.mean(v["percentilesMs"]["99.0"] for v in values),
              p999MsByRound=[v["percentilesMs"].get("99.9") for v in values])
         for (product, scenario), values in groups.items()]
for file in source.iterdir():
    if file.is_file() and file.suffix in (".txt", ".sha256", ".log"):
        shutil.copyfile(file, destination / file.name)
result = dict(totalMeasuredRequests=sum(r["requests"] for r in rows), phases=len(rows),
              httpErrors=0, socketErrors=0, runs=rows, means=means)
(destination / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(dict(requests=result["totalMeasuredRequests"], phases=len(rows), errors=0)))
