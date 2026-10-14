#!/usr/bin/env python3
"""Summarise scripts/bench-vs-wiremock.sh results as Markdown tables.

Usage: python3 scripts/bench-vs-wiremock-summary.py OUT_DIR

Each run directory is named <label>-r<round>. Values are medians over rounds:
requests per second and p99 latency per scenario (with two rounds the median is
their mean, so compare the rounds when they differ a lot), then heap after a full
GC, RSS, threads and startup time. Errors are non-2xx responses plus socket
errors. Runs without a DONE marker are skipped.
"""
import os
import re
import statistics
import sys

SCENARIOS = [
    ("fixed-c4", "Fixed JSON, 4 connections"),
    ("fixed-c8", "Fixed JSON, 8 connections"),
    ("fixed-c20", "Fixed JSON, 20 connections"),
    ("tpl", "Response template"),
    ("xml-small", "XML ~1.5 KB + XPath"),
    ("xml-large", "XML ~100 KB + XPath"),
    ("items2000", "One of 2,000 path rules"),
]


def read(path):
    try:
        with open(path) as f:
            return f.read()
    except OSError:
        return ""


def ms(text, pct):
    m = re.search(rf"^\s+{pct}%\s+([\d.]+)(us|ms|s|m)\s*$", text, re.M)
    if not m:
        return None
    value, unit = float(m.group(1)), m.group(2)
    return {"us": value / 1000, "ms": value, "s": value * 1000, "m": value * 60_000}[unit]


def run_values(run_dir):
    values = {}
    for key, _ in SCENARIOS:
        text = read(os.path.join(run_dir, f"wrk-{key}.txt"))
        if not text:
            values[key] = {"rps": None, "p99": None, "errors": 0}
            continue
        rps = re.search(r"Requests/sec:\s+([\d.]+)", text)
        non2xx = re.search(r"Non-2xx or 3xx responses: (\d+)", text)
        socket = re.search(r"Socket errors: connect (\d+), read (\d+), write (\d+), timeout (\d+)", text)
        values[key] = {
            "rps": float(rps.group(1)) if rps else None,
            "p99": ms(text, 99),
            "errors": (int(non2xx.group(1)) if non2xx else 0)
                      + (sum(int(n) for n in socket.groups()) if socket else 0),
        }
    heap = re.search(r"used (\d+)K", read(os.path.join(run_dir, "heap-after-gc.txt")))
    proc = read(os.path.join(run_dir, "proc.txt"))
    rss = re.search(r"VmRSS:\s+(\d+)", proc)
    threads = re.search(r"Threads:\s+(\d+)", proc)
    startup = re.search(r"startupSec ([\d.]+)", read(os.path.join(run_dir, "startup.txt")))
    values["heapMb"] = int(heap.group(1)) / 1024 if heap else None
    values["rssMb"] = int(rss.group(1)) / 1024 if rss else None
    values["threads"] = int(threads.group(1)) if threads else None
    values["startupSec"] = float(startup.group(1)) if startup else None
    return values


def median(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None


def fmt(value, digits=0):
    return "—" if value is None else f"{value:,.{digits}f}"


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    runs = {}
    for name in sorted(os.listdir(root)):
        match = re.fullmatch(r"(.+)-r(\d+)", name)
        path = os.path.join(root, name)
        if match and os.path.exists(os.path.join(path, "DONE")):
            runs.setdefault(match.group(1), []).append(run_values(path))
    if not runs:
        sys.exit(f"no finished runs in {root}")
    labels = list(runs)

    print("Requests per second (median of rounds; p99 in brackets)\n")
    print("| Scenario | " + " | ".join(labels) + " |")
    print("|---|" + "---:|" * len(labels))
    for key, title in SCENARIOS:
        cells = []
        for label in labels:
            rps = median(r[key]["rps"] for r in runs[label])
            p99 = median(r[key]["p99"] for r in runs[label])
            errors = sum(r[key]["errors"] for r in runs[label])
            cell = f"{fmt(rps)} ({fmt(p99, 1)} ms)"
            cells.append(cell + (f" ⚠ {errors} errors" if errors else ""))
        print(f"| {title} | " + " | ".join(cells) + " |")

    print("\nResources after the run (median)\n")
    print("| | " + " | ".join(labels) + " |")
    print("|---|" + "---:|" * len(labels))
    for key, title, digits in (("heapMb", "Heap after full GC (MB)", 0), ("rssMb", "RSS (MB)", 0),
                               ("threads", "Threads", 0), ("startupSec", "Startup (s)", 1)):
        print(f"| {title} | " + " | ".join(fmt(median(r[key] for r in runs[label]), digits)
                                           for label in labels) + " |")
    print("\nRounds per label: " + ", ".join(f"{label} {len(runs[label])}" for label in labels))


if __name__ == "__main__":
    main()
