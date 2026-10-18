#!/usr/bin/env python3
"""Isolated HTTP/JMS load-idle observations; JDK 17+, Python stdlib, Windows/Linux.

Creates its own Echo process/database/downstream. Never targets a running deployment.
Natural GC is archived throughout; explicit GC happens ONLY at idle checkpoints.
"""
import argparse
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

MIB = 1024 ** 2
MAIN = "com.echo.diagnostics.MemoryObservationFixture"
AUTH = "Basic " + base64.b64encode(b"admin:admin").decode("ascii")


def parse_histogram(text):
    classes = {}
    for line in text.splitlines():
        match = re.match(r"\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)", line)
        if match:
            instances, size, name = match.groups()
            # Same binary name can occur under different class loaders.
            row = classes.setdefault(name, {"instances": 0, "bytes": 0})
            row["instances"] += int(instances)
            row["bytes"] += int(size)
    if not classes:
        raise ValueError("No class histogram rows; diagnostic result is unavailable")
    return classes


def histogram_growth(baseline, current):
    rows = []
    for name, value in current.items():
        previous = baseline.get(name, {"instances": 0, "bytes": 0})
        delta = value["bytes"] - previous["bytes"]
        if delta > 0:
            rows.append({"class": name, "bytes": value["bytes"], "byteGrowth": delta,
                         "instances": value["instances"], "instanceGrowth": value["instances"] - previous["instances"]})
    return sorted(rows, key=lambda row: (-row["byteGrowth"], row["class"]))[:30]


def assess_growth(values, allowance):
    """Compare equivalent post-warmup, post-idle-GC samples; insufficient data is not a pass."""
    if len(values) < 4:
        return {"status": "insufficient-data", "samples": len(values)}
    if any(not isinstance(value, (int, float)) or value < 0 for value in values):
        return {"status": "insufficient-data", "samples": len(values)}
    baseline = statistics.median(values[:2])
    final = statistics.median(values[-2:])
    x_mean = (len(values) - 1) / 2
    y_mean = statistics.mean(values)
    slope = sum((i - x_mean) * (value - y_mean) for i, value in enumerate(values)) / sum((i - x_mean) ** 2 for i in range(len(values)))
    growth = final - baseline
    return {"status": "growth-detected" if growth > allowance and slope > 0 else "no-growth-detected-in-window",
            "samples": len(values), "baseline": baseline, "final": final, "growth": growth,
            "slopePerCycle": slope, "allowedGrowth": allowance}


def assess_resources(checkpoints, args):
    series = {"heap": ([point["heapAfterBytes"] for point in checkpoints], args.allowed_growth_mib * MIB),
              "nativeCommitted": ([point["nativeCommittedBytes"] for point in checkpoints], args.allowed_native_growth_mib * MIB)}
    for metric, limit in [("directBytes", 8 * MIB), ("nonHeapUsedBytes", 16 * MIB), ("threads", 4)]:
        series[metric] = ([point["resources"]["sections"]["jvm"]["values"].get(metric) for point in checkpoints], limit)
    if checkpoints and "VmRSS" in checkpoints[0]["process"]:
        series["rss"] = ([point["process"]["VmRSS"] * 1024 for point in checkpoints], args.allowed_native_growth_mib * MIB)
        series["fileDescriptors"] = ([point["process"]["fdCount"] for point in checkpoints], 8)
    return {key: dict(assess_growth(values, limit), unit="count" if key in ("threads", "fileDescriptors") else "bytes")
            for key, (values, limit) in series.items()}


def assert_drained(resources):
    expected = {"jms": ["listenerActive", "forwardActive", "reservedBytes", "waitingThreads", "cleanupFailures", "replyFailures", "forwardFailures"],
                "http": ["nativeActive", "nativeBufferedBytes", "nativeQueued", "nativeWorkerActive", "activeForwards", "poolLeased", "poolPending", "bufferedBytes"],
                "requestLog": ["queueItems", "queueBytes", "inFlightBytes", "waitingProducers", "dropped"],
                "scheduler": ["waitingTasks", "activeWorkers"], "database": ["poolActive", "poolWaiting", "writerWaiting", "writerActive"]}
    for section, keys in expected.items():
        data = resources["sections"][section]
        if data["state"] not in ("AVAILABLE", "PARTIAL"):
            raise AssertionError(f"Missing observations: {section}")
        for key in keys:
            if data["values"].get(key) != 0:
                raise AssertionError(f"Resource did not drain: {section}.{key}={data['values'].get(key)}")
    logs = resources["sections"]["requestLog"]["values"]
    if not logs.get("consumerRunning") or logs.get("storageUnavailable") or logs.get("backpressureActive"):
        raise AssertionError("Request log persistence is unhealthy")


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def stop(process):
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)


class Observation:
    def __init__(self, args):
        self.args = args
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=False)
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.cursor = 0
        self.started = None
        self.events = 0
        self.expired_events = 0
        self.http_stall_connections = 0
        self.checkpoints = []
        self.histogram_baseline = None
        self.echo = self.downstream = None
        self.base = ""
        self.env = {key: value for key, value in os.environ.items() if not key.startswith(("ECHO_", "SPRING_"))}
        self.java = str(Path(shutil.which(args.java) or args.java).resolve())
        self.jcmd = str(Path(self.java).with_name("jcmd.exe" if sys.platform == "win32" else "jcmd"))

    def api(self, path, body=None, base=None):
        request = urllib.request.Request((base or self.base) + path,
                data=json.dumps(body).encode() if body is not None else None,
                headers={"Authorization": AUTH, "Accept": "application/json", "Content-Type": "application/json"})
        with self.opener.open(request, timeout=10) as response:
            payload = response.read()
            return json.loads(payload) if payload else None

    def fixture(self, *args):
        return [self.java, "-Xmx192m", f"-Dloader.path={self.args.fixture.resolve()}", f"-Dloader.main={MAIN}",
                "-cp", str(self.args.jar.resolve()), "org.springframework.boot.loader.launch.PropertiesLauncher", *map(str, args)]

    def ready(self, process, base, path):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise AssertionError("Child exited before readiness; see process logs")
            try:
                self.api(path, base=base)
                return
            except (OSError, urllib.error.URLError):
                time.sleep(.5)
        raise TimeoutError("Readiness timeout")

    def sample(self, phase):
        if self.echo.poll() is not None:
            raise AssertionError("Echo exited unexpectedly (possible OOM); see echo.log")
        history = self.api(f"/api/admin/resources/gc?after={self.cursor}&limit=512")
        if history["state"] != "AVAILABLE" or history["missedEvents"] or history["notificationFailures"]:
            raise AssertionError("GC observations incomplete; cannot claim a passing observation run")
        if self.started and self.started != history["processStartedAt"]:
            raise AssertionError("Echo restarted during observation")
        self.started = history["processStartedAt"]
        self.expired_events = history["expiredEvents"]
        with (self.output / "gc-events.ndjson").open("a", encoding="utf-8") as stream:
            for event in history["events"]:
                stream.write(json.dumps(dict(phase=phase, **event), ensure_ascii=False) + "\n")
                self.events += 1
        self.cursor = history["nextAfter"]
        if history["hasMore"]:
            raise AssertionError("GC rate exceeded this sampler's page budget; increase sampling frequency")
        resources = self.api("/api/admin/resources")
        process = {}
        if sys.platform == "linux":
            for line in Path(f"/proc/{self.echo.pid}/status").read_text().splitlines():
                if line.startswith(("VmRSS:", "VmHWM:", "Threads:")):
                    key, value, *_ = line.split()
                    process[key.rstrip(":")] = int(value)
            process["fdCount"] = len(list(Path(f"/proc/{self.echo.pid}/fd").iterdir()))
        row = {"phase": phase, "time": dt.datetime.now(dt.timezone.utc).isoformat(),
               "gcCursor": self.cursor, "resources": resources, "process": process}
        with (self.output / "resources.ndjson").open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(row) + "\n")
        return row, history["events"]

    def open_http_stalls(self):
        """Only the harness-owned Echo; mix declared and chunked incomplete uploads."""
        sockets = []
        try:
            for index in range(8):
                peer = socket.create_connection(("127.0.0.1", int(self.base.rsplit(":", 1)[1])), timeout=5)
                sockets.append(peer)
                # Two unknown-length bodies reserve 80 MiB of the existing 128 MiB budget;
                # the other six declare small bodies. No payload or memory limits are weakened.
                framing = "Transfer-Encoding: chunked\r\n\r\n100\r\nx" if index < 2 else "Content-Length: 256\r\n\r\nx"
                peer.sendall(("POST /mock/observe/stall HTTP/1.1\r\nHost: localhost\r\n"
                              "Content-Type: text/plain\r\n" + framing).encode("ascii"))
            return sockets
        except BaseException:
            for peer in sockets:
                peer.close()
            raise

    def verify_http_stalls_recovered(self, peers, phase):
        # Verify worker recovery BEFORE closing local sockets. Closing them first would hide
        # the original defect: lease counters drained while blocking reads still held workers.
        row, _ = self.sample(phase + "-stall-recovery")
        values = row["resources"]["sections"]["http"]["values"]
        for key in ["nativeActive", "nativeBufferedBytes", "nativeQueued", "nativeWorkerActive"]:
            if values.get(key) != 0:
                raise AssertionError(f"HTTP stalled upload retained {key}={values.get(key)}")
        for peer in peers:
            peer.settimeout(.2)
            try:
                if peer.recv(1):
                    raise AssertionError("Expected incomplete upload to close without an HTTP response")
            except ConnectionResetError:
                pass  # Both FIN and RST terminate the incomplete HTTP/1 request safely.
        self.http_stall_connections += len(peers)

    def load(self, work, seconds, phase):
        peers = self.open_http_stalls() if self.args.http_stalls and not phase.startswith("warmup") else []
        try:
            result = self.load_client(work, seconds, phase)
            if peers:
                self.verify_http_stalls_recovered(peers, phase)
            return result
        finally:
            for peer in peers:
                peer.close()

    def load_client(self, work, seconds, phase):
        with (self.output / f"{phase}-load.log").open("w", encoding="utf-8") as log:
            client = subprocess.Popen(self.fixture("load", self.base, self.jms, seconds, self.args.rate, self.args.payload_bytes),
                                      cwd=work, env=self.env, stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + seconds + 90
                while client.poll() is None:
                    self.sample(phase)
                    if time.monotonic() > deadline:
                        raise TimeoutError("Load process exceeded deadline")
                    time.sleep(2)
                if client.returncode != 0:
                    raise AssertionError(f"Load correctness failed; see {phase}-load.log")
            finally:
                stop(client)
        text = (self.output / f"{phase}-load.log").read_text(encoding="utf-8")
        result = json.loads(next(line.removeprefix("OBS_RESULT ") for line in text.splitlines() if line.startswith("OBS_RESULT ")))
        if not phase.startswith("warmup") and result["elapsedSeconds"] > seconds * 1.2 + 5:
            raise AssertionError("Requested load could not be sustained; lower the rate and rerun")
        return result

    def checkpoint(self, phase):
        # Never force GC during load. First observe natural idle and verify drain.
        deadline = time.monotonic() + self.args.idle_seconds
        while True:
            row, _ = self.sample(phase + "-natural-idle")
            if time.monotonic() >= deadline:
                break
            time.sleep(min(2, deadline - time.monotonic()))
        # Background DB work can briefly borrow a connection. Require two clean samples,
        # with a bounded deadline; do not turn a single coincidental sample into a failure/pass.
        drain_deadline = time.monotonic() + 15
        clean_samples = 0
        while clean_samples < 2:
            try:
                assert_drained(row["resources"])
                clean_samples += 1
            except AssertionError:
                clean_samples = 0
                if time.monotonic() >= drain_deadline:
                    raise
            if clean_samples < 2:
                if time.monotonic() >= drain_deadline:
                    raise AssertionError("Resources did not remain drained")
                time.sleep(.5)
                row, _ = self.sample(phase + "-drain")
        broker = self.api("/state", base=self.downstream_base)
        if broker["failed"] or sorted(broker["queues"]) != ["OBS.TARGET"]:
            raise AssertionError("Downstream failure or temporary queue retention")
        before = self.cursor
        command = subprocess.run([self.jcmd, str(self.echo.pid), "GC.run"], capture_output=True, text=True, timeout=30, check=True)
        (self.output / f"{phase}-explicit-gc.txt").write_text(command.stdout, encoding="utf-8")
        deadline = time.monotonic() + 10
        checkpoint_event = None
        while time.monotonic() < deadline:
            row, events = self.sample(phase + "-post-gc")
            candidates = [event for event in events if event["sequence"] > before and event["cause"] == "Diagnostic Command"]
            if candidates:
                checkpoint_event = max(candidates, key=lambda event: event["sequence"])
                break
            time.sleep(.1)
        if not checkpoint_event or not checkpoint_event["heapCoverageComplete"]:
            raise AssertionError("No complete explicit-GC notification; cannot compare retained heap")
        nmt = subprocess.run([self.jcmd, str(self.echo.pid), "VM.native_memory", "summary", "scale=KB"],
                             capture_output=True, text=True, timeout=20, check=True)
        (self.output / f"{phase}-native-memory.txt").write_text(nmt.stdout, encoding="utf-8")
        threads = subprocess.run([self.jcmd, str(self.echo.pid), "Thread.print"],
                                 capture_output=True, text=True, timeout=20, check=True)
        (self.output / f"{phase}-threads.txt").write_text(threads.stdout, encoding="utf-8")
        committed = re.search(r"Total: reserved=\d+KB, committed=(\d+)KB", nmt.stdout)
        if not committed:
            raise AssertionError("Native memory summary unavailable")
        point = {"phase": phase, "heapAfterBytes": checkpoint_event["heapAfterBytes"], "gcEvent": checkpoint_event,
                 "resources": row["resources"], "process": row["process"], "downstream": broker,
                 "nativeCommittedBytes": int(committed.group(1)) * 1024}
        if self.args.class_histograms:
            # Explicit opt-in, disposable JVM only. -all avoids requesting another full GC,
            # but still pauses for inspection and includes objects allocated since the checkpoint.
            histogram = subprocess.run([self.jcmd, str(self.echo.pid), "GC.class_histogram", "-all"],
                                       capture_output=True, text=True, timeout=60, check=True)
            (self.output / f"{phase}-classes.txt").write_text(histogram.stdout, encoding="utf-8")
            classes = parse_histogram(histogram.stdout)
            if self.histogram_baseline is None:
                self.histogram_baseline = classes
            point["classGrowthSuspects"] = histogram_growth(self.histogram_baseline, classes)
        self.checkpoints.append(point)
        return point

    def run(self):
        result = {"passed": False, "platform": sys.platform, "cycles": self.args.cycles,
                  "secondsPerCycle": self.args.seconds, "idleSeconds": self.args.idle_seconds, "rate": self.args.rate,
                  "heapMiB": self.args.heap_mib, "payloadBytes": self.args.payload_bytes,
                  "warmupSecondsPerCycle": self.args.warmup_seconds, "warmupCycles": self.args.warmup_cycles,
                  "httpStallProbesEnabled": self.args.http_stalls,
                  "classHistograms": self.args.class_histograms,
                  "histogramCaveat": "Shallow sizes by class, including unreachable objects; growth suggests candidates, not retaining owners or leak proof",
                  "gcInterventions": "Only after natural idle and resource drain; never during load",
                  "limitation": "Finite regression observation, not proof of leak freedom or a production throughput benchmark"}
        try:
            if not Path(self.java).is_file() or not Path(self.jcmd).is_file():
                raise FileNotFoundError("A full JDK is required: select its bin/java with --java; sibling jcmd must exist")
            if not self.args.jar.is_file() or not self.args.fixture.is_file():
                raise FileNotFoundError("Build bootJar and memoryObservationFixtureJar first")
            result["jarSha256"] = hashlib.sha256(self.args.jar.read_bytes()).hexdigest()
            result["fixtureSha256"] = hashlib.sha256(self.args.fixture.read_bytes()).hexdigest()
            with tempfile.TemporaryDirectory(prefix="Echo Memory Observation ") as temporary:
                work = Path(temporary)
                ports = set()
                while len(ports) < 4:
                    ports.add(free_port())
                http_port, source_port, downstream_http, downstream_jms = sorted(ports)
                self.base = f"http://127.0.0.1:{http_port}"
                self.jms = f"tcp://127.0.0.1:{source_port}"
                self.downstream_base = f"http://127.0.0.1:{downstream_http}"
                with (self.output / "downstream.log").open("w", encoding="utf-8") as downstream_log, (self.output / "echo.log").open("w", encoding="utf-8") as echo_log:
                    try:
                        self.downstream = subprocess.Popen(self.fixture("downstream", downstream_http, downstream_jms),
                                cwd=work, env=self.env, stdout=downstream_log, stderr=subprocess.STDOUT)
                        self.ready(self.downstream, self.downstream_base, "/state")
                        self.echo = subprocess.Popen([self.java, f"-Xms{self.args.heap_mib}m", f"-Xmx{self.args.heap_mib}m",
                                "-XX:+UseG1GC", "-XX:NativeMemoryTracking=summary", "-Xlog:gc*:file=gc.log:time,uptime,level,tags",
                                "-jar", str(self.args.jar.resolve()), "--server.address=127.0.0.1", f"--server.port={http_port}",
                                "--echo.http.transport=native", "--server.undertow.threads.io=2", "--server.undertow.threads.worker=8",
                                "--echo.backup.enabled=false", "--echo.cleanup.enabled=false", "--echo.jms.enabled=true",
                                "--echo.monitoring.gc-history-max-age-seconds=60",
                                f"--echo.http.request-timeout-ms={8000 if self.args.http_stalls else 40000}",
                                f"--echo.jms.port={source_port}", "--echo.jms.concurrency=2-4", "--echo.jms.target.enabled=true",
                                "--echo.jms.target.type=artemis", f"--echo.jms.target.server-url=tcp://127.0.0.1:{downstream_jms}",
                                "--echo.jms.target.username=", "--echo.jms.target.password=", "--echo.jms.target.queue=OBS.TARGET",
                                "--echo.request-log.max-records=1000", "--echo.request-log.enabled=true",
                                "--echo.request-log.store=database", "--logging.level.root=WARN", "--logging.level.com.echo=INFO"],
                                cwd=work, env=self.env, stdout=echo_log, stderr=subprocess.STDOUT)
                        self.ready(self.echo, self.base, "/api/admin/resources")
                        (self.output / "run.json").write_text(json.dumps({"baseUrl": self.base, "echoPid": self.echo.pid,
                                "java": self.java, "startedAt": dt.datetime.now(dt.timezone.utc).isoformat()}, indent=2), encoding="utf-8")
                        for path, body, extra in [("fixed", "ok", {}), ("template", "{{request.query.id}}", {}), ("delay", "ok", {"delayMs": 10})]:
                            self.api("/api/admin/rules", {"protocol": "HTTP", "matchKey": "/observe/" + path, "method": "GET", "responseBody": body, "status": 200, **extra})
                        target = self.api("/api/admin/http-target-connections", {"name": "observation", "baseUrl": self.downstream_base,
                                "authType": "NONE", "connectTimeoutSeconds": 2, "readTimeoutSeconds": 2, "enabled": True, "tlsVerificationEnabled": True})
                        self.api("/api/admin/rules", {"protocol": "HTTP", "matchKey": "/observe/forward", "method": "POST",
                                "action": "FORWARD", "forwardTargetMode": "CONNECTION", "httpTargetConnectionId": target["id"]})
                        for mode in ["mock", "forward"]:
                            self.api("/api/admin/rules", {"protocol": "JMS", "matchKey": "ECHO.REQUEST", "bodyCondition": "//ServiceName=" + mode,
                                    "responseBody": "ok", "action": mode.upper()})
                        # Reconnect during warm-up too: lazy broker event loops may only start
                        # when new connections rotate through the pool. Preserve every raw result.
                        result["warmup"] = [self.load(work, self.args.warmup_seconds, f"warmup-{cycle + 1:03d}")
                                            for cycle in range(self.args.warmup_cycles)]
                        self.checkpoint("baseline")
                        result["traffic"] = []
                        for cycle in range(self.args.cycles):
                            name = f"cycle-{cycle + 1:03d}"
                            result["traffic"].append(self.load(work, self.args.seconds, name))
                            point = self.checkpoint(name)
                            print(f"{name}: retained heap {point['heapAfterBytes'] / MIB:.2f} MiB; GC events {self.events}", flush=True)
                    finally:
                        stop(self.echo)
                        stop(self.downstream)
                        if (work / "gc.log").exists():
                            shutil.copy2(work / "gc.log", self.output / "jvm-gc.log")
            result["growth"] = assess_resources(self.checkpoints, self.args)
            if not self.expired_events:
                raise AssertionError("No GC history expiry observed; lengthen the run to exercise housekeeping")
            result["passed"] = all(series["status"] == "no-growth-detected-in-window" for series in result["growth"].values())
        except Exception as error:
            result["error"] = str(error)
        finally:
            result["gcEventsArchived"] = self.events
            result["gcEventsExpired"] = self.expired_events
            result["httpStallConnectionsRecovered"] = self.http_stall_connections
            result["checkpoints"] = self.checkpoints
            (self.output / "summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps({key: value for key, value in result.items() if key not in ("checkpoints", "traffic", "warmup")}, indent=2), flush=True)
        return 0 if result["passed"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--fixture", type=Path, default=Path("build/libs/echo-memory-observation-fixture.jar"))
    parser.add_argument("--output", required=True, type=Path, help="New output directory; existing paths are never overwritten")
    parser.add_argument("--java", default="java")
    parser.add_argument("--cycles", type=int, default=10)
    parser.add_argument("--seconds", type=int, default=60)
    parser.add_argument("--idle-seconds", type=int, default=15)
    parser.add_argument("--warmup-seconds", type=int, default=60)
    parser.add_argument("--warmup-cycles", type=int, default=1,
                        help="Repeat load/reconnect warm-up before the baseline; increase for lazy connection/thread pools")
    parser.add_argument("--rate", type=int, default=100)
    parser.add_argument("--payload-bytes", type=int, default=16384)
    parser.add_argument("--heap-mib", type=int, default=384)
    parser.add_argument("--allowed-growth-mib", type=int, default=24)
    parser.add_argument("--allowed-native-growth-mib", type=int, default=128)
    parser.add_argument("--class-histograms", action="store_true",
                        help="Inspect class counts/sizes at idle checkpoints in the disposable JVM; adds pauses, not a throughput benchmark")
    parser.add_argument("--http-stalls", action="store_true",
                        help="Mix eight stalled uploads into each load cycle and require server-side recovery before closing the clients")
    args = parser.parse_args()
    if args.cycles < 3 or min(args.seconds, args.warmup_seconds, args.warmup_cycles, args.idle_seconds) < 1 or args.heap_mib < 256 or min(args.allowed_growth_mib, args.allowed_native_growth_mib) < 0:
        parser.error("Need >=3 cycles, positive durations, >=256 MiB heap, and nonnegative growth allowance")
    if args.http_stalls and args.seconds < 10:
        parser.error("--http-stalls needs >=10-second load cycles for the eight-second request deadline")
    return Observation(args).run()


if __name__ == "__main__":
    sys.exit(main())
