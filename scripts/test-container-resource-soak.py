#!/usr/bin/env python3
"""Finite fixed-rate resource-retention probe, isolated from production and local DBs.

Default: ten measured minutes, 5 JMS + 5 HTTP forwards/sec, then two idle
minutes. Explicit GC only before/after the measured and natural-idle windows.
No claim of permanent leak freedom, production capacity, or zero overhead.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("diagnostic_docker_helpers", Path(__file__).with_name("test-container-diagnostics.py"))
HELPERS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPERS)
check = HELPERS.check


def parse_process(text: str) -> dict:
    values = {}
    for line in text.splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        if key in {"VmRSS", "VmHWM", "RssAnon", "RssFile", "RssShmem", "Threads"}:
            parts = value.split()
            values[key] = int(parts[0]) * (1024 if len(parts) > 1 and parts[1] == "kB" else 1)
    check("VmRSS" in values and "Threads" in values, "Process memory/thread sample unavailable")
    return values


def growth_summary(points: list[tuple[float, float]]) -> dict:
    """Describe slope/range only; no automatic leak verdict from linear fitting."""
    check(len(points) >= 2, "Insufficient observations for a trend")
    xs, ys = zip(*points)
    mean_x, mean_y = sum(xs) / len(xs), sum(ys) / len(ys)
    denominator = sum((x - mean_x) ** 2 for x in xs)
    check(denominator > 0, "Observations have no time separation")
    slope = sum((x - mean_x) * (y - mean_y) for x, y in points) / denominator
    return {"first": ys[0], "last": ys[-1], "minimum": min(ys), "maximum": max(ys),
            "delta": ys[-1] - ys[0], "slopePerMinute": slope * 60, "observations": len(points)}


def validate_workload(result: dict, seconds: int, rate: int) -> None:
    check(result["durationSeconds"] == seconds and result["rate"] == rate, "Wrong workload window/rate")
    check(result["elapsedMs"] >= seconds * 1000, "Measured traffic window was shortened")
    for protocol in ("jms", "http"):
        check(result[protocol]["completed"] == seconds * rate // 2, f"Missing {protocol} replies")


def validate_retention(summary: dict, expected_total: int) -> None:
    check(summary["maxRecords"] == 2000, "Wrong request-log capacity")
    # Durable writer deliberately prunes 10% headroom, rather than deleting on
    # every insertion. Its configured 2000-row ceiling is not an exact row count.
    if expected_total <= 2000:
        check(summary["totalRequests"] == expected_total, "Missing records before retention ceiling")
    else:
        check(1800 <= summary["totalRequests"] <= 2000, "Request-log count outside existing retention watermarks")


def gc_floors(text: str) -> list[dict]:
    rows = []
    pattern = re.compile(r"\[(\d+(?:\.\d+)?)s\].*GC\((\d+)\) Pause (.*?) (\d+)M->(\d+)M\((\d+)M\) ([\d.]+)ms")
    for line in text.splitlines():
        match = pattern.search(line)
        if match:
            uptime, identity, cause, before, after, capacity, duration = match.groups()
            rows.append(dict(uptimeSeconds=float(uptime), gc=int(identity), cause=cause,
                             beforeMiB=int(before), afterMiB=int(after), capacityMiB=int(capacity), durationMs=float(duration)))
    return rows


class Soak(HELPERS.Harness):
    def __init__(self, output: Path, image: str, seconds: int, rate: int, payload: int, idle: int):
        super().__init__(output, image)
        self.seconds, self.rate, self.payload, self.idle = seconds, rate, payload, idle
        self.rows = []
        self.started = time.monotonic()
        self.echo = self.broker = None
        self.api = self.downstream = ""

    def observe(self, phase: str) -> dict:
        state = self.owned("container", self.echo)["State"]
        check(state["Running"] and not state["OOMKilled"], f"Echo stopped unexpectedly: {state}")
        cgroup = self.sample(self.echo)
        check(cgroup.get("oom", 0) == 0 and cgroup.get("oom_kill", 0) == 0, "cgroup OOM observed")
        process = parse_process(self.docker("exec", self.echo, "cat", "/proc/1/status", timeout=15))
        memory_text = self.docker("exec", self.echo, "cat", "/sys/fs/cgroup/memory.stat", timeout=15)
        memory = {key: int(value) for key, value in (line.split() for line in memory_text.splitlines())}
        fds = len(self.docker("exec", self.echo, "ls", "-1", "/proc/1/fd", timeout=15).splitlines())
        row = dict(phase=phase, elapsedSeconds=round(time.monotonic() - self.started, 3),
                   cgroup=cgroup, process=process, memoryStat=memory, fdCount=fds,
                   resources=self.get(self.api, "/api/admin/resources", True),
                   status=self.get(self.api, "/api/admin/status", True),
                   downstream=self.get(self.downstream, "/state"))
        self.rows.append(row)
        with (self.output / "samples.jsonl").open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")
        return row

    def workload(self, seconds: int, sample: bool) -> dict:
        echo_name = self.owned("container", self.echo)["Name"].lstrip("/")
        broker_name = self.owned("container", self.broker)["Name"].lstrip("/")
        args = ["docker", "exec", self.broker, "java", *self.fixture_args(), "steady",
                f"tcp://{echo_name}:61616", self.api, broker_name, str(seconds), str(self.rate), str(self.payload)]
        path = self.output / f"caller-{seconds}s.log"
        started = time.monotonic()
        with path.open("w", encoding="utf-8") as stream:
            caller = subprocess.Popen(args, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, text=True)
            next_sample, next_progress = started, started
            try:
                while caller.poll() is None:
                    now = time.monotonic()
                    check(now - started < seconds + 90, "Fixed-rate driver exceeded its finite deadline")
                    if sample and now >= next_sample:
                        row = self.observe("load")
                        next_sample = time.monotonic() + 15
                        if now >= next_progress:
                            jvm = row["resources"]["sections"]["jvm"]["values"]
                            print(f"load {now-started:.0f}/{seconds}s: heap={jvm['heapUsedBytes']/2**20:.1f}MiB "
                                  f"rss={row['process']['VmRSS']/2**20:.1f}MiB "
                                  f"cgroup={row['cgroup']['memory_current']/2**20:.1f}MiB "
                                  f"threads={jvm['threads']} fds={row['fdCount']}", flush=True)
                            next_progress = now + 60
                    time.sleep(0.5)
                check(caller.returncode == 0, f"Caller failed; inspect {path}")
            finally:
                if caller.poll() is None:
                    caller.terminate()
                    try:
                        caller.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        caller.kill()
                        caller.wait(timeout=5)
        line = next(line for line in path.read_text(encoding="utf-8").splitlines() if line.startswith("STEADY_RESULT "))
        result = json.loads(line.removeprefix("STEADY_RESULT "))
        validate_workload(result, seconds, self.rate)
        return result

    def explicit_gc(self, phase: str) -> dict:
        text = self.docker("exec", self.echo, "jcmd", "1", "GC.run", timeout=45)
        check("Command executed successfully" in text, "Explicit outside-window GC unavailable")
        (self.output / f"{phase}-gc.txt").write_text(text + "\n", encoding="utf-8")
        time.sleep(2)
        return self.observe(phase)

    @staticmethod
    def validate_drained(row: dict, expected_per_protocol: int) -> None:
        sections = row["resources"]["sections"]
        for group, keys in {"jms": ("listenerActive", "forwardActive", "reservedBytes", "waitingThreads",
                                             "replyFailures", "forwardFailures", "cleanupFailures", "receiveTimeouts", "invalidReplies"),
                            "http": ("activeForwards", "poolLeased", "poolPending", "poolIdle", "poolCapacity", "bufferedBytes",
                                     "rejectedForwards", "cancelledForwards"),
                            "requestLog": ("queueItems", "queueBytes", "inFlightBytes", "waitingProducers", "dropped"),
                            "database": ("poolWaiting", "writerWaiting", "writerActive"),
                            "scheduler": ("waitingTasks", "activeWorkers")}.items():
            for key in keys:
                check(sections[group]["values"][key] == 0, f"Residual/unexpected {group}.{key}")
        db = sections["database"]["values"]
        check(db["poolTotal"] <= 3, "SQLite pool exceeded configured capacity")
        check(sections["jms"]["values"]["replySent"] == expected_per_protocol, "JMS reply counter mismatch")
        check(sections["http"]["values"]["completedForwards"] == expected_per_protocol, "HTTP completion counter mismatch")
        request = sections["requestLog"]["values"]
        check(request["consumerRunning"] and not request["storageUnavailable"] and not request["backpressureActive"], "Persistence unhealthy")
        downstream = row["downstream"]
        check(downstream["received"] == expected_per_protocol and downstream["httpReceived"] == expected_per_protocol,
              "Missing/duplicate downstream forwards")
        check(downstream["queues"] == ["DIAGNOSTIC.TARGET"], "Temporary queues remain after idle")
        check(downstream["connections"] == 2, "Unexpected downstream JMS connections")
        diagnostic = sections["applicationLog"]["values"]
        check(diagnostic["diagnosticEnabled"] and diagnostic["diagnosticRunning"], "Default diagnostics not running")
        for key in ("diagnosticTodayBytes", "diagnosticAccepted", "diagnosticDropped", "diagnosticQueueUsed", "diagnosticSinkFailures"):
            check(diagnostic[key] == 0, f"Unexpected healthy-traffic diagnostics: {key}")

    def run(self) -> dict:
        result = dict(owner=self.owner, started=dt.datetime.now(dt.timezone.utc).isoformat(),
                      measuredSeconds=self.seconds, idleSeconds=self.idle, rate=self.rate, payloadBytes=self.payload,
                      cpus=1, memoryBytes=2**30, heapMiB=512, passed=False,
                      gcInterventions="Before load and after natural idle only; neither inside measured window")
        try:
            check(self.jar.is_file() and self.fixture.is_file(), "Build both jars first")
            result["jarSha256"] = hashlib.sha256(self.jar.read_bytes()).hexdigest()
            result["fixtureSha256"] = hashlib.sha256(self.fixture.read_bytes()).hexdigest()
            metadata = json.loads(self.docker("image", "inspect", self.image))[0]
            result["image"] = {key: metadata[key] for key in ("Id", "RepoDigests", "Architecture", "Os")}
            self.image = metadata["Id"]
            self.network = self.docker("network", "create", "--internal", "--label", f"{HELPERS.LABEL}={self.owner}", self.owner)
            self.broker = self.create("soak-downstream", "1", 512 * 2**20, self.fixture_args() + ["broker"], 8081)
            self.api_container = self.broker
            self.downstream = self.base_url(self.broker, 8081)
            self.ready(self.broker, self.downstream, "/state")
            broker_name = self.owned("container", self.broker)["Name"].lstrip("/")
            java = ["-Xmx512m", "-Xlog:gc*:file=/work/logs/gc.log:time,uptime,level,tags", "-jar", "/opt/echo.jar",
                    "--spring.profiles.active=sqlite", "--server.port=8080",
                    "--spring.datasource.url=jdbc:sqlite:/work/data/main.sqlite?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL&foreign_keys=ON",
                    "--echo.request-log.durable.spool-path=/work/data/spool.sqlite", "--echo.request-log.max-records=2000",
                    "--logging.file.path=/work/logs", "--echo.jms.enabled=true", "--echo.jms.target.enabled=true",
                    "--echo.jms.target.type=artemis", f"--echo.jms.target.server-url=tcp://{broker_name}:61616",
                    "--echo.jms.target.queue=DIAGNOSTIC.TARGET", "--echo.jms.target.timeout-seconds=30",
                    "--echo.backup.enabled=false", "--echo.cleanup.enabled=false"]
            # JVM opens GC file before Spring creates its log directory.
            java[1] = "-Xlog:gc*:file=/work/data/gc.log:time,uptime,level,tags"
            self.echo = self.create("soak-echo", "1", 2**30, java, 8080)
            item = self.owned("container", self.echo)
            limits = item["HostConfig"]
            check(limits["Memory"] == 2**30 and limits["MemorySwap"] == 2**30 and limits["NanoCpus"] == 10**9,
                  "Container CPU/memory/swap limits not applied")
            kernel = self.docker("exec", self.echo, "cat", "/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory.swap.max",
                                 "/sys/fs/cgroup/cpu.max").splitlines()
            check(kernel[:2] == [str(2**30), "0"], "Kernel memory/swap limits incorrect")
            quota, period = map(int, kernel[2].split())
            check(quota == period, "Kernel CPU quota incorrect")
            result["kernelLimits"] = kernel
            self.api = self.base_url(self.echo, 8080)
            self.ready(self.echo, self.api, "/api/admin/status", True)
            print("Ready; ten-second warmup (not part of measured window)", flush=True)
            result["warmup"] = self.workload(10, False)
            time.sleep(3)
            result["baseline"] = self.explicit_gc("baseline-post-gc")
            print(f"Starting {self.seconds}s fixed-rate measured traffic", flush=True)
            result["traffic"] = self.workload(self.seconds, True)
            result["loadEnd"] = self.observe("load-end")
            print(f"Traffic complete; observing {self.idle}s natural idle cleanup", flush=True)
            idle_start = time.monotonic()
            while time.monotonic() - idle_start < self.idle:
                time.sleep(min(15, self.idle - (time.monotonic() - idle_start)))
                self.observe("idle")
                print(f"idle {time.monotonic()-idle_start:.0f}/{self.idle}s", flush=True)
            result["naturalIdleEnd"] = self.observe("idle-end-before-gc")
            expected = (self.seconds + 10) * self.rate // 2
            self.validate_drained(result["naturalIdleEnd"], expected)
            summary = self.get(self.api, "/api/admin/logs/summary", True)
            validate_retention(summary, expected * 2)
            result["requestLogSummary"] = summary
            result["final"] = self.explicit_gc("final-post-gc")
            gc_text = self.docker("exec", self.echo, "cat", "/work/data/gc.log")
            (self.output / "gc.log").write_text(gc_text + "\n", encoding="utf-8")
            result["gcFloors"] = gc_floors(gc_text)
            result["passed"] = True
        except Exception as error:
            result["error"] = str(error)
            print(f"FAILED: {error}", flush=True)
        finally:
            if self.echo:
                result["stateBeforePlannedStop"] = self.owned("container", self.echo)["State"]
                if result["stateBeforePlannedStop"]["Running"]:
                    result["finalCgroup"] = self.sample(self.echo)
                try:
                    self.docker("cp", f"{self.echo}:/work/logs", str(self.output / "logs"))
                    self.docker("cp", f"{self.echo}:/work/data/gc.log", str(self.output / "gc.log"))
                except RuntimeError as error:
                    result["captureError"] = str(error)
            for identity, name in ((self.echo, "echo"), (self.broker, "downstream")):
                if identity:
                    (self.output / f"{name}-console.log").write_text(self.docker("logs", identity) + "\n", encoding="utf-8")
            self.write(self.output / "result.json", result)
            for identity in self.containers[:]:
                self.remove_container(identity)
            if self.network:
                self.owned("network", self.network)
                self.docker("network", "rm", self.network)
            result["cleaned"] = not self.containers
            self.write(self.output / "result.json", result)
        print(f"passed={result['passed']}; evidence={self.output}", flush=True)
        return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts" / "resource-soak" / dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--image", default="eclipse-temurin:17-jdk")
    parser.add_argument("--seconds", type=int, default=600)
    parser.add_argument("--rate", type=int, default=10)
    parser.add_argument("--payload-bytes", type=int, default=8192)
    parser.add_argument("--idle-seconds", type=int, default=120)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    check(10 <= args.seconds <= 600 and 2 <= args.rate <= 40 and args.rate % 2 == 0
          and 1024 <= args.payload_bytes <= 131072 and 90 <= args.idle_seconds <= 180, "Workload outside finite safety bounds")
    if args.dry_run:
        print(json.dumps(dict(seconds=args.seconds, rate=args.rate, payloadBytes=args.payload_bytes,
                              idleSeconds=args.idle_seconds, requests=args.seconds * args.rate,
                              cpus=1, containerMiB=1024, heapMiB=512, origins=4, diagnostics="default on, anomalies only")))
        return 0
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    if not args.skip_build:
        log = HELPERS.Harness.command(["./gradlew", "bootJar", "dockerDiagnosticFixtureJar", "-PreleaseVersion=container-diagnostics", "--console=plain"], 360)
        (output / "build.log").write_text(log + "\n", encoding="utf-8")
    return 0 if Soak(output, args.image, args.seconds, args.rate, args.payload_bytes, args.idle_seconds).run()["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
