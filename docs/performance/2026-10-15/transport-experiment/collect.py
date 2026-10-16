#!/usr/bin/env python3
"""Archive compact raw evidence from completed/failed runs; leave jars, DBs and JFRs outside git."""
import json
import shutil
import subprocess
import sys
from pathlib import Path

source = Path(sys.argv[1]).resolve()
destination = Path(__file__).resolve().parent / "results" / source.name
destination.mkdir(parents=True, exist_ok=True)
patterns = (
    "wrk-*.txt", "cpu-*.txt", "quota-*.txt", "proc*.txt", "heap-*.txt", "startup.txt",
    "setup-check.txt", "server-args.txt", "forward-metrics-after.json", "agents-*.json",
    "memory-timeline.txt", "status-after.json", "DONE", "ABORTED", "profile-summary.json",
)
for run in source.iterdir():
    if not run.is_dir() or not ((run / "DONE").exists() or (run / "ABORTED").exists()):
        continue
    target = destination / run.name
    target.mkdir(exist_ok=True)
    for pattern in patterns:
        for path in run.glob(pattern):
            saved = target / path.name
            if saved.exists():
                saved.chmod(0o644)
            shutil.copy2(path, saved)
            # /proc snapshots can carry read-only permissions; archives must remain refreshable.
            saved.chmod(0o644)
for name in ("progress.log", "downstream-direct.txt", "STOPPED.txt"):
    if (source / name).exists():
        shutil.copy2(source / name, destination / name)
summary = json.loads(subprocess.check_output(
    [sys.executable, str(Path(__file__).resolve().parent.parent / "summarize.py"), str(source)], text=True))
# Incomplete measurements are visible in the source progress log, not included as comparison results.
summary = {name: result for name, result in summary.items() if result["done"]}
(destination / "results.json").write_text(json.dumps(summary, indent=2) + "\n")
print(destination)
