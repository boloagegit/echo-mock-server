#!/usr/bin/env python3
"""Run actual Echo + four independent APs and capture browser/network evidence."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

HERE = Path(__file__).resolve().parent
COMPAT = HERE.parent
REPO = COMPAT.parent.parent
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
ADMIN = "Basic " + base64.b64encode(b"sdk-proof:local-proof-only").decode()


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def request(url, method="GET", payload=None, admin=False):
    headers = {"Accept": "application/json"}
    if admin:
        headers["Authorization"] = ADMIN
    if payload is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=None if payload is None else json.dumps(payload).encode(), method=method, headers=headers)
    try:
        response = HTTP.open(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.status, json.loads(raw) if raw else None


def free_port():
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        return reservation.getsockname()[1]


def wait_json(process, url, deadline=60, admin=False):
    until = time.monotonic() + deadline
    while time.monotonic() < until:
        if process.poll() is not None:
            raise RuntimeError("Owned process exited before ready: " + url)
        try:
            status, body = request(url, admin=admin)
            if status == 200:
                return body
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.2)
    raise RuntimeError("Readiness timed out: " + url)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--echo-server-jar", type=Path, required=True)
    parser.add_argument("--sdk-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--hold", action="store_true", help="Keep the loopback demo alive until OUTPUT/STOP exists (max 10 min)")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    (output / "STOP").unlink(missing_ok=True)
    java = args.java_home / "bin/java"
    sdk = args.sdk_jar.resolve()
    echo_jar = args.echo_server_jar.resolve()
    run_id = "sdk-proof-" + uuid.uuid4().hex[:12]
    upstream_requests = []
    upstream_lock = threading.Lock()

    class Original(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            entry = {"timestamp": datetime.now(timezone.utc).isoformat(), "path": self.path,
                     "received": body, "originalHostHeader": self.headers.get("X-Original-Host")}
            with upstream_lock:
                upstream_requests.append(entry)
                with (output / "original-backend-requests.jsonl").open("a") as log:
                    log.write(json.dumps(entry) + "\n")
            response = json.dumps({"servedBy": "ORIGINAL", "serverMarker": run_id, "received": body}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)

        def log_message(self, *args):
            pass

    original = ThreadingHTTPServer(("127.0.0.1", 0), Original)
    threading.Thread(target=original.serve_forever, daemon=True).start()
    original_base = "http://127.0.0.1:" + str(original.server_port)
    processes, logs, owned_rules = [], [], []
    report = {"runId": run_id, "sdkJarSha256": sha(sdk), "echoServerJarSha256": sha(echo_jar),
              "apSourceSha256": sha(HERE / "ProofApplication.java"), "applicationYamlSha256": sha(HERE / "proof-application.yml"),
              "originalBackend": original_base, "apps": [], "startedAt": datetime.now(timezone.utc).isoformat()}
    echo_base = None

    def launch(command, directory, label):
        log = (output / (label + ".log")).open("w")
        process = subprocess.Popen(command, cwd=directory, stdout=log, stderr=subprocess.STDOUT)
        logs.append(log)
        processes.append(process)
        return process

    try:
        # Compile this AP once; reuse identical bytecode and YAML on both framework generations.
        classes = output / "ap-classes"
        classes.mkdir(exist_ok=True)
        compile_cp = (COMPAT / "build/boot-3.5.16/runtime-classpath.txt").read_text()
        dependencies = [item for item in compile_cp.split(":") if item.endswith(".jar") and "echo-spring-boot-starter" not in item]
        subprocess.run([str(args.java_home / "bin/javac"), "--release", "17", "-parameters", "-cp", ":".join(dependencies + [str(sdk)]),
                        "-d", str(classes), str(HERE / "ProofApplication.java")], check=True)
        for name in ["proof-application.yml", "proof.html"]:
            shutil.copy2(HERE / name, classes / name)
        report["compiledApClassSha256"] = sha(classes / "com/echo/proof/ProofApplication.class")

        data = Path(tempfile.mkdtemp(prefix="echo-sdk-screenshot-"))
        (data / "data").mkdir()
        echo_base = "http://127.0.0.1:" + str(free_port())
        echo = launch([str(java), "-Xms64m", "-Xmx512m", "-jar", str(echo_jar),
                       "--server.address=127.0.0.1", "--server.port=" + echo_base.rsplit(":", 1)[1],
                       "--echo.admin.username=sdk-proof", "--echo.admin.password=local-proof-only",
                       "--echo.jms.enabled=false", "--echo.backup.enabled=false", "--echo.cleanup.enabled=false",
                       "--echo.diagnostics.enabled=false", "--echo.request-log.enabled=true", "--echo.request-log.wait-for-write=true"], data, "echo-server")
        wait_json(echo, echo_base + "/actuator/health/readiness")
        report["echoBase"] = echo_base
        report["echoBuild"] = request(echo_base + "/api/admin/status", admin=True)[1]["build"]
        report["isolatedEchoDataDirectory"] = str(data)
        print("READY actual Echo " + echo_base, flush=True)

        for boot in ["3.5.16", "4.1.1"]:
            body_template = ('{"servedBy":"ECHO","serverMarker":"' + run_id
                             + '","matchedBoot":"' + boot + '","received": {{{request.body}}} }')
            document = {"apiVersion": "echo.mock/v1", "kind": "Rule", "spec": {
                "protocol": "HTTP", "method": "POST", "matchKey": "/sdk-proof/orders",
                "targetHost": original_base.removeprefix("http://"), "description": "SDK PROOF - Spring Boot " + boot,
                "enabled": True, "action": "MOCK", "status": 200,
                "headerCondition": "X-Proof-Boot=" + boot + ";Authorization=Bearer sdk-proof-application",
                "responseBody": body_template, "responseContentType": "TEXT",
                "responseHeaders": {"Content-Type": "application/json", "X-SDK-Proof-Run": run_id},
                "tags": {"proof": run_id, "boot": boot}}}
            status, created = request(echo_base + "/api/admin/rules/apply", "POST", document, admin=True)
            if status != 201:
                raise RuntimeError("Rule fixture failed: " + str(created))
            resource = created["resource"]
            owned_rules.append(resource["metadata"]["id"])
            (output / ("echo-rule-" + boot + ".json")).write_text(json.dumps(resource, ensure_ascii=False, indent=2) + "\n")
        report["ownedRuleIds"] = owned_rules

        pending = []
        for boot in ["3.5.16", "4.1.1"]:
            runtime_cp = (COMPAT / ("build/boot-" + boot) / "runtime-classpath.txt").read_text()
            dependencies = [item for item in runtime_cp.split(":") if item.endswith(".jar") and "echo-spring-boot-starter" not in item]
            for enabled in [True, False]:
                label = "boot-" + boot + ("-on" if enabled else "-off")
                base = "http://127.0.0.1:" + str(free_port())
                command = [str(java), "-Xms32m", "-Xmx256m", "-cp", ":".join([str(classes), str(sdk)] + dependencies),
                           "com.echo.proof.ProofApplication", "--spring.config.location=classpath:/proof-application.yml",
                           "--server.address=127.0.0.1", "--server.port=" + base.rsplit(":", 1)[1],
                           "--proof.echo-url=" + echo_base, "--proof.original-url=" + original_base, "--proof.run=" + run_id]
                if not enabled:
                    command.append("--echo.client.enabled=false")
                process = launch(command, output, label)
                app = {"label": label, "base": base, "boot": boot, "enabled": enabled, "pid": process.pid}
                report["apps"].append(app)
                pending.append((process, app))
        for process, app in pending:
            meta = wait_json(process, app["base"] + "/api/proof/meta")
            assert meta["sdkJarSha256"] == report["sdkJarSha256"] and meta["applicationYamlSha256"] == report["applicationYamlSha256"]
            assert meta["boot"] == app["boot"] and meta["sdkEnabled"] == app["enabled"]
            app["metadata"] = meta
            print("READY " + app["label"] + " " + app["base"], flush=True)
        (output / "live-manifest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")

        from capture_browser import capture
        while True:
            try:
                captures = capture(report, output)
                break
            except Exception as error:
                if not args.hold:
                    raise
                (output / "capture-error.txt").write_text(str(error))
                print("CAPTURE ERROR: " + str(error) + "; write RETRY to retry or STOP to end", flush=True)
                deadline = time.monotonic() + 600
                while not (output / "RETRY").exists() and not (output / "STOP").exists() and time.monotonic() < deadline:
                    time.sleep(0.25)
                if not (output / "RETRY").exists():
                    raise
                (output / "RETRY").unlink()
        report["browserCaptures"] = captures

        positive, negative = [], []
        for capture in captures:
            assert capture["passed"]
            for call in capture["calls"]:
                (positive if call["payload"]["servedBy"] == "ECHO" else negative).append(call["result"]["requestId"])
        deadline = time.monotonic() + 20
        while True:
            status, summary = request(echo_base + "/api/admin/logs?size=50", admin=True)
            details = []
            for entry in summary["results"]:
                detail = request(echo_base + "/api/admin/logs/" + str(entry["log"]["id"]) + "/detail", admin=True)[1]
                details.append(detail)
            logged = {json.loads(detail["requestBody"])["requestId"] for detail in details}
            if set(positive).issubset(logged) or time.monotonic() >= deadline:
                break
            time.sleep(0.1)
        original_ids = {entry["received"]["requestId"] for entry in upstream_requests}
        assert set(positive).issubset(logged) and not set(negative).intersection(logged)
        assert set(negative).issubset(original_ids) and not set(positive).intersection(original_ids)
        assert all(detail["matched"] and detail["ruleId"] in owned_rules for detail in details)
        for detail in details:
            sent = json.loads(detail["requestBody"])
            received = json.loads(detail["responseBody"])
            assert received["received"] == sent and received["servedBy"] == "ECHO"
        report["verification"] = {"passed": True, "echoRequestIds": positive, "originalRequestIds": negative,
                                  "echoLogCount": len(details), "originalBackendCount": len(upstream_requests),
                                  "sameJarForAllFourAps": True, "sameYamlForAllFourAps": True,
                                  "sameCompiledApForBoot3And4": True, "controlOverride": "echo.client.enabled=false"}
        (output / "echo-log-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
        (output / "echo-log-details.json").write_text(json.dumps(details, ensure_ascii=False, indent=2) + "\n")
        (output / "proof-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print("VERIFIED: SDK ON reached actual Echo; SDK OFF / unselected reached original; request IDs matched both sinks", flush=True)
        if args.hold:
            print("HOLD: write " + str(output / "STOP") + " to stop owned demo processes", flush=True)
            deadline = time.monotonic() + 600
            while not (output / "STOP").exists() and time.monotonic() < deadline:
                time.sleep(0.25)
    finally:
        if echo_base:
            for rule_id in owned_rules:
                try:
                    request(echo_base + "/api/admin/rules/" + rule_id, "DELETE", admin=True)
                except Exception:
                    pass
        for process in reversed(processes):
            process.terminate()
        for process in reversed(processes):
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for log in logs:
            log.close()
        original.shutdown()
        original.server_close()
        (output / "lifecycle.json").write_text(json.dumps({"stoppedAt": datetime.now(timezone.utc).isoformat(),
                                                         "allOwnedProcessesStopped": all(p.poll() is not None for p in processes)}) + "\n")


if __name__ == "__main__":
    main()
