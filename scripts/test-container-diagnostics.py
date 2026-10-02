#!/usr/bin/env python3
"""Short, isolated real-Echo/JMS diagnostics test under Docker cgroup limits.

No production endpoints or repository database mounts. Builds an opt-in fixture
jar, uses the official Java 17 image, and removes only resources with this run's
unique ownership label. Artifacts contain synthetic traffic only. This is not a
long-run, TIBCO compatibility, leak-proof, or maximum-capacity certification.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import json
from pathlib import Path
import secrets
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parent.parent
LABEL = "com.echo.diagnostic-test"
TIERS = (("1cpu-1g", "1", 1024 * 1024 * 1024, 512),
         ("half-cpu-768m", "0.5", 768 * 1024 * 1024, 256))
ALL_TIERS = (*TIERS, ("half-cpu-512m", "0.5", 512 * 1024 * 1024, 256))
FIXTURE_MAIN = "com.echo.diagnostics.ContainerDiagnosticFixture"


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def parse_cgroup(text: str) -> dict:
    lines = text.strip().splitlines()
    if len(lines) < 3 or not lines[0].isdigit() or not lines[1].isdigit():
        raise ValueError("cgroup v2 memory sample unavailable")
    values = {"memory_current": int(lines[0]), "memory_peak": int(lines[1])}
    for line in lines[2:]:
        key, number = line.split()
        values[key] = int(number)
    return values


def parse_diagnostic(line: str) -> dict:
    check(line.startswith("ECHO_TX "), "Unknown diagnostic line")
    return dict(field.split("=", 1) for field in line.split()[1:] if "=" in field)


def validate_summary(line: str, case: str, caller: dict, downstream: dict, diagnostic_ids: set[str]) -> dict:
    fields = parse_diagnostic(line)
    check(fields.get("event") == "END" and fields.get("protocol") == "JMS", "Expected one final JMS summary")
    check(fields.get("failure") == case and fields.get("waitResult") == case, "Wrong observed failure")
    check(fields.get("stage") == "RECEIVE_REPLY", "Wrong failure stage")
    check(fields.get("sendReturned") == "true", "Outbound send did not return")
    check(fields.get("callerReply") == "SEND_RETURNED", "Caller reply did not return")
    check(fields.get("inId") == caller["messageId"], "Inbound identity lost")
    check(fields.get("outId") == downstream["messageId"], "Outbound identity lost")
    check(fields.get("outReplyTo") == downstream["replyTo"], "ReplyTo identity lost")
    check(fields.get("trace") in diagnostic_ids, "Request Log cannot correlate file summary")
    check(fields.get("timeoutMs") == "30000", "Not the original 30-second wait")
    check(fields.get("endpoint") == "ECHO_DIAG_" + case, "Already-parsed ServiceName missing")
    check("cleanupFailure" not in fields and "metadataPartial" not in fields, "Cleanup/metadata error")
    check(len(line.encode("utf-8")) + 1 <= 2048, "Diagnostic line exceeds byte budget")
    if case == "NO_REPLY":
        check(float(caller["elapsedMs"]) >= 29000 and int(fields["waitMs"]) >= 29000, "Wait returned too early")
    else:
        check("BytesMessage" in fields.get("replyType", "") and bool(fields.get("replyId")), "Invalid type not identifiable")
    check("<Request>" not in line and "non-text-fixture" not in line, "Body leaked into diagnostics")
    return fields


class Harness:
    def __init__(self, output: Path, image: str, tiers: tuple = TIERS, diagnostics_off: bool = False, startup_only: bool = False):
        self.output = output
        self.owner = "echo-diag-" + secrets.token_hex(5)
        self.image = image
        self.tiers = tiers
        self.diagnostics_off = diagnostics_off
        self.startup_only = startup_only
        self.containers: list[str] = []
        self.network: str | None = None
        self.api_container: str | None = None
        self.jar = ROOT / "build" / "libs" / "echo-server-container-diagnostics.jar"
        self.fixture = ROOT / "build" / "libs" / "echo-diagnostic-fixture.jar"

    @staticmethod
    def command(args: list[str], timeout: float = 120) -> str:
        result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}): {args[:5]}\n{result.stdout[-3000:]}\n{result.stderr[-3000:]}")
        # docker logs can send container stderr to the client's stderr. Preserve it.
        if args[:2] == ["docker", "logs"]:
            return (result.stdout + result.stderr).strip()
        return result.stdout.strip()

    def docker(self, *args: str, timeout: float = 120) -> str:
        return self.command(["docker", *args], timeout)

    def owned(self, kind: str, identity: str) -> dict:
        item = json.loads(self.docker(kind, "inspect", identity))[0]
        labels = item.get("Labels") if kind == "network" else item["Config"].get("Labels")
        check((labels or {}).get(LABEL) == self.owner, "Refusing cleanup of non-owned resource")
        return item

    def create(self, suffix: str, cpus: str, memory: int, java: list[str], port: int) -> str:
        identity = self.docker("create", "--name", f"{self.owner}-{suffix}",
                               "--label", f"{LABEL}={self.owner}", "--network", self.network or "none",
                               "--cpus", cpus, "--memory", str(memory), "--memory-swap", str(memory),
                               "--workdir", "/work/data",
                               "--mount", f"type=bind,src={self.jar},dst=/opt/echo.jar,readonly",
                               "--mount", f"type=bind,src={self.fixture},dst=/opt/fixture.jar,readonly",
                               self.image, "java", *java)
        self.containers.append(identity)
        self.docker("start", identity)
        return identity

    def base_url(self, identity: str, port: int) -> str:
        # --internal networks intentionally do not publish/NAT host ports.
        name = self.owned("container", identity)["Name"].lstrip("/")
        return f"http://{name}:{port}"

    def get(self, base: str, path: str, authenticated: bool = False) -> dict:
        check(self.api_container is not None, "No isolated HTTP client container")
        args = ["exec", self.api_container, "curl", "--fail", "--silent", "--show-error", "--max-time", "15",
                "--header", "Accept: application/json"]
        if authenticated:
            # Public fixture defaults, only inside this disposable test instance.
            args += ["--user", "admin:admin"]
        return json.loads(self.docker(*args, base + path, timeout=20))

    def ready(self, identity: str, base: str, path: str, authenticated: bool = False) -> dict:
        deadline = time.monotonic() + 240
        last_error = ""
        while time.monotonic() < deadline:
            state = self.owned("container", identity)["State"]
            check(state["Running"], f"Container stopped before readiness: {state}")
            try:
                return self.get(base, path, authenticated)
            except (OSError, ValueError, RuntimeError) as error:
                last_error = str(error)
                time.sleep(1)
        raise RuntimeError("Readiness timeout: " + last_error)

    def write(self, path: Path, value: object) -> None:
        path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    def sample(self, identity: str) -> dict:
        text = self.docker("exec", identity, "cat", "/sys/fs/cgroup/memory.current",
                           "/sys/fs/cgroup/memory.peak", "/sys/fs/cgroup/memory.events",
                           "/sys/fs/cgroup/cpu.stat", timeout=10)
        return {"time": time.time(), **parse_cgroup(text)}

    def fixture_args(self, heap: int = 128) -> list[str]:
        return [f"-Xmx{heap}m", "-Dloader.path=/opt/fixture.jar", f"-Dloader.main={FIXTURE_MAIN}",
                "-cp", "/opt/echo.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]

    def tier(self, name: str, cpus: str, memory: int, heap: int) -> dict:
        output = self.output / name
        output.mkdir()
        result: dict = {"tier": name, "cpus": cpus, "container_bytes": memory, "heap_mib": heap,
                        "diagnostics_off": self.diagnostics_off, "startup_only": self.startup_only}
        broker = echo = None
        stop_samples = threading.Event()
        sampler = None
        samples: list[dict] = []
        try:
            broker = self.create(name + "-downstream", "1", 512 * 1024 * 1024, self.fixture_args() + ["broker"], 8081)
            self.api_container = broker
            downstream_url = self.base_url(broker, 8081)
            self.ready(broker, downstream_url, "/state")
            broker_name = self.owned("container", broker)["Name"].lstrip("/")
            java = [f"-Xmx{heap}m", "-jar", "/opt/echo.jar", "--spring.profiles.active=sqlite",
                    "--server.port=8080", "--spring.datasource.url=jdbc:sqlite:/work/data/main.sqlite?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL&foreign_keys=ON",
                    "--echo.request-log.durable.spool-path=/work/data/spool.sqlite",
                    "--logging.file.path=/work/logs", "--echo.jms.enabled=true",
                    "--echo.jms.target.enabled=true", "--echo.jms.target.type=artemis",
                    f"--echo.jms.target.server-url=tcp://{broker_name}:61616",
                    "--echo.jms.target.queue=DIAGNOSTIC.TARGET", "--echo.jms.target.timeout-seconds=30",
                    "--echo.backup.enabled=false", "--echo.cleanup.enabled=false"]
            if self.diagnostics_off:
                java.append("--echo.diagnostics.enabled=false")
            # Do not override diagnostics: prove the approved default is enabled.
            echo = self.create(name + "-echo", cpus, memory, java, 8080)
            inspect = self.owned("container", echo)
            check(inspect["HostConfig"]["Memory"] == memory, "Memory cap not applied")
            check(inspect["HostConfig"]["MemorySwap"] == memory, "Swap cap not applied")
            check(inspect["HostConfig"]["NanoCpus"] == int(float(cpus) * 1_000_000_000), "CPU cap not applied")
            kernel_limits = self.docker("exec", echo, "cat", "/sys/fs/cgroup/memory.max",
                                       "/sys/fs/cgroup/memory.swap.max", "/sys/fs/cgroup/cpu.max").splitlines()
            check(len(kernel_limits) == 3 and int(kernel_limits[0]) == memory and kernel_limits[1] == "0",
                  "Kernel memory/swap caps not applied")
            quota, period = (int(value) for value in kernel_limits[2].split())
            check(quota / period == float(cpus), "Kernel CPU quota not applied")
            result["kernel_limits"] = dict(memory_max=kernel_limits[0], swap_max=kernel_limits[1], cpu_max=kernel_limits[2])
            def sample_loop() -> None:
                while not stop_samples.is_set():
                    try:
                        samples.append(self.sample(echo))
                    except (RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
                        samples.append({"time": time.time(), "error": str(error)})
                    stop_samples.wait(1)
            sampler = threading.Thread(target=sample_loop, daemon=True)
            sampler.start()
            api = self.base_url(echo, 8080)
            self.ready(echo, api, "/api/admin/status", True)
            initial = self.get(api, "/api/admin/resources", True)
            application = initial["sections"]["applicationLog"]["values"]
            check(application["diagnosticEnabled"] == (not self.diagnostics_off)
                  and application["diagnosticRunning"] == (not self.diagnostics_off), "Wrong diagnostic state")
            check(not application["diagnosticDetailed"], "Default unexpectedly logs normal traffic")
            result["initial_resources"] = initial
            if self.startup_only:
                time.sleep(3)
                result.update(final_resources=self.get(api, "/api/admin/resources", True), passed=True)
                return result
            echo_name = inspect["Name"].lstrip("/")
            def traffic(case: str, count: int, concurrency: int) -> dict:
                print(f"{name}: {case} x{count} concurrency={concurrency}", flush=True)
                stdout = self.docker("exec", broker, "java", *self.fixture_args(), "caller",
                                     f"tcp://{echo_name}:61616", case, str(count), str(concurrency), timeout=180)
                (output / f"caller-{case}-{count}.txt").write_text(stdout + "\n", encoding="utf-8")
                line = next(line for line in stdout.splitlines() if line.startswith("FIXTURE_RESULT "))
                data = json.loads(line.removeprefix("FIXTURE_RESULT "))
                check(data["requests"] == count, "Lost caller replies")
                return data
            result["normal"] = traffic("NORMAL", 400, 4)
            healthy = self.get(api, "/api/admin/resources", True)
            healthy_diagnostics = healthy["sections"]["applicationLog"]["values"]
            check(healthy_diagnostics["diagnosticAccepted"] == 0 and healthy_diagnostics["diagnosticTodayBytes"] == 0,
                  "Healthy traffic generated diagnostic file bytes")
            result["healthy_resources"] = healthy
            # Concurrent healthy traffic must still complete while a listener is waiting 30s.
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as worker:
                timeout_future = worker.submit(traffic, "NO_REPLY", 1, 1)
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    if any(item["case"] == "NO_REPLY" for item in self.get(downstream_url, "/state")["anomalies"]):
                        break
                    time.sleep(0.2)
                else:
                    raise AssertionError("Downstream did not receive the no-reply request")
                result["during_wait"] = traffic("NORMAL", 20, 2)
                result["no_reply"] = timeout_future.result()
            result["invalid_type"] = traffic("INVALID_TYPE", 1, 1)
            result["recovery"] = traffic("NORMAL", 50, 4)
            deadline = time.monotonic() + 20
            while True:
                final = self.get(api, "/api/admin/resources", True)
                state = self.get(downstream_url, "/state")
                jms = final["sections"]["jms"]["values"]
                diagnostics = final["sections"]["applicationLog"]["values"]
                logs = self.get(api, "/api/admin/logs?protocol=JMS&size=500", True)
                if (logs["totalElements"] == 472 and jms["listenerActive"] == 0 and jms["forwardActive"] == 0
                        and diagnostics["diagnosticQueueUsed"] == 0 and state["queues"] == ["DIAGNOSTIC.TARGET"]):
                    break
                check(time.monotonic() < deadline, "Persistence/processing/temporary queues did not drain")
                time.sleep(0.5)
            check(state["received"] == 472, "Lost or duplicate downstream requests")
            check(jms["replySent"] == 472 and jms["replyFailures"] == 0, "Caller reply count mismatch")
            check(jms["receiveTimeouts"] == 1 and jms["invalidReplies"] == 1, "Failure counters mismatch")
            check(jms["cleanupFailures"] == 0 and jms["forwardFailures"] == 0, "Cleanup or unexpected forward exceptions")
            check(diagnostics["diagnosticAccepted"] == 2 and diagnostics["diagnosticEmissionAttempts"] == 2, "Not one summary per anomaly")
            for key in ("diagnosticDropped", "diagnosticSinkFailures", "diagnosticRateLimited", "diagnosticBudgetDropped"):
                check(diagnostics[key] == 0, f"Diagnostic omission: {key}")
            self.docker("cp", f"{echo}:/work/logs", str(output / "logs"))
            lines = [line for path in (output / "logs" / "diagnostics").glob("transactions-*.log")
                     for line in path.read_text(encoding="utf-8").splitlines()]
            check(len(lines) == 2, "Not exactly two diagnostic lines")
            ids = {row["log"]["diagnosticId"] for row in logs["results"]}
            summaries = []
            for case, caller in (("NO_REPLY", result["no_reply"]), ("INVALID_TYPE", result["invalid_type"])):
                line = next(line for line in lines if parse_diagnostic(line).get("failure") == case)
                downstream = next(row for row in state["anomalies"] if row["case"] == case)
                summaries.append(validate_summary(line, case, caller["results"][0], downstream, ids))
            result.update(final_resources=final, downstream=state, summaries=summaries,
                          request_logs=logs, passed=True)
        except Exception as error:
            result.update(passed=False, error=str(error))
        finally:
            stop_samples.set()
            if sampler:
                sampler.join(12)
            result["samples"] = samples
            if echo:
                inspect = self.owned("container", echo)
                result["container_state_before_stop"] = inspect["State"]
                result["limits"] = {key: inspect["HostConfig"][key] for key in ("Memory", "MemorySwap", "NanoCpus")}
                if inspect["State"]["Running"]:
                    result["final_cgroup"] = self.sample(echo)
                if inspect["State"]["OOMKilled"] or not inspect["State"]["Running"]:
                    result.update(passed=False, error="Echo exited or was OOM-killed")
                if result.get("final_cgroup", {}).get("oom_kill", 0) or result.get("final_cgroup", {}).get("oom", 0):
                    result.update(passed=False, error="cgroup observed an OOM event")
            for identity, label in ((echo, "echo"), (broker, "downstream")):
                if identity:
                    (output / f"{label}-console.log").write_text(self.docker("logs", identity), encoding="utf-8")
            self.write(output / "result.json", result)
            for identity in (echo, broker):
                if identity:
                    self.remove_container(identity)
        print(f"{name}: passed={result.get('passed')} {result.get('error', '')}", flush=True)
        return result

    def remove_container(self, identity: str) -> None:
        item = self.owned("container", identity)
        if item["State"]["Running"]:
            self.docker("stop", "--time", "20", identity, timeout=45)
        self.docker("rm", identity)
        self.containers.remove(identity)

    def run(self) -> dict:
        result: dict = {"owner": self.owner, "started": dt.datetime.now(dt.timezone.utc).isoformat(), "tiers": []}
        try:
            check(self.jar.is_file() and self.fixture.is_file(), "Build the application and fixture first")
            metadata = json.loads(self.docker("image", "inspect", self.image))[0]
            result["image"] = {key: metadata[key] for key in ("Id", "RepoDigests", "Architecture", "Os")}
            self.image = metadata["Id"]  # Freeze the local image identity for the complete run.
            self.network = self.docker("network", "create", "--internal", "--label", f"{LABEL}={self.owner}", self.owner)
            for tier in self.tiers:
                result["tiers"].append(self.tier(*tier))
        finally:
            for identity in self.containers[:]:
                self.remove_container(identity)
            if self.network:
                self.owned("network", self.network)
                self.docker("network", "rm", self.network)
            result["cleaned"] = not self.containers
            result["passed"] = len(result["tiers"]) == len(self.tiers) and all(tier["passed"] for tier in result["tiers"])
            self.write(self.output / "result.json", result)
        return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts" / "transaction-diagnostics-docker" / dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--image", default="eclipse-temurin:17-jdk")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--tiers", default=",".join(tier[0] for tier in TIERS), help="Comma-separated named tiers; 512m is a deliberate insufficient-headroom investigation")
    parser.add_argument("--startup-only", action="store_true", help="Readiness/resource check only; does not certify business scenarios")
    parser.add_argument("--diagnostics-off", action="store_true", help="Startup-only control for investigating baseline memory constraints")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    selected = args.tiers.split(",")
    lookup = {tier[0]: tier for tier in ALL_TIERS}
    check(bool(selected) and len(selected) == len(set(selected)) and all(tier in lookup for tier in selected), "Unknown/duplicate tier")
    check(not args.diagnostics_off or args.startup_only, "Disabled diagnostics only supported for startup comparison")
    tiers = tuple(lookup[tier] for tier in selected)
    if args.dry_run:
        print(json.dumps({"tiers": tiers, "image": args.image, "traffic_per_tier": 0 if args.startup_only else 472,
                          "no_reply_seconds": 30, "diagnostics": "disabled startup control" if args.diagnostics_off else "default enabled, anomaly only",
                          "cleanup": "exact run-owned containers/network only"}, indent=2))
        return 0
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    if not args.skip_build:
        build = Harness.command(["./gradlew", "bootJar", "dockerDiagnosticFixtureJar", "-PreleaseVersion=container-diagnostics", "--console=plain"], timeout=360)
        (output / "build.log").write_text(build + "\n", encoding="utf-8")
    result = Harness(output, args.image, tiers, args.diagnostics_off, args.startup_only).run()
    print(f"Evidence: {output}")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
