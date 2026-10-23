#!/usr/bin/env python3
"""Build one SDK JAR, then run unchanged consumer source/config on real Boot/Cloud runtimes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse

HERE = Path(__file__).resolve().parent
SDK = HERE.parent
REPO = SDK.parent
MATRIX = {
    "3.0.13": "2022.0.5",
    "3.1.12": "2022.0.5",
    "3.2.12": "2023.0.6",
    "3.3.13": "2023.0.6",
    "3.4.13": "2024.0.3",
    "3.5.16": "2025.0.3",
    "4.0.8": "2025.1.3",
    "4.1.1": "2025.1.3",
}


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(command, cwd, logfile, env=None):
    print("RUN " + logfile.name, flush=True)
    with logfile.open("w") as log:
        result = subprocess.run(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=240)
    if result.returncode:
        raise RuntimeError(f"Exit {result.returncode}; see {logfile}")


def launch_echo(args, output):
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # loopback fixture only
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    data = Path(tempfile.mkdtemp(prefix="echo-starter-compat-"))
    (data / "data").mkdir()
    log = (output / "echo-server.log").open("w")
    process = subprocess.Popen([
        str(args.java17_home / "bin/java"), "-Xms64m", "-Xmx512m", "-jar", str(args.echo_server_jar),
        "--server.address=127.0.0.1", f"--server.port={port}", "--echo.jms.enabled=false",
        "--echo.admin.username=starter-probe", "--echo.admin.password=starter-probe-password",
        "--echo.backup.enabled=false", "--echo.cleanup.enabled=false", "--echo.diagnostics.enabled=false",
        "--echo.request-log.enabled=false", "--echo.features.scenarios-enabled=false",
    ], cwd=data, stdout=log, stderr=subprocess.STDOUT)
    base = f"http://127.0.0.1:{port}"
    try:
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("Echo exited; see echo-server.log")
            try:
                with http.open(base + "/actuator/health/readiness", timeout=1) as response:
                    if response.status == 200:
                        return process, log, base, data
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(0.2)
        raise RuntimeError("Echo readiness timed out; see echo-server.log")
    except BaseException:
        process.terminate()
        process.wait(timeout=20)
        log.close()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", type=Path, required=True)
    parser.add_argument("--java17-home", type=Path, required=True)
    parser.add_argument("--java21", type=Path, help="Optional second runtime; SDK still built only once on 17")
    parser.add_argument("--echo-server-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--matrix", default=",".join(MATRIX), help="Comma-separated Boot versions")
    parser.add_argument("--skip-sdk-build", action="store_true")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    args.echo_server_jar = args.echo_server_jar.resolve()
    env = dict(os.environ, JAVA_HOME=str(args.java17_home))
    command = [str(args.gradle), "--no-daemon", "--console=plain", "--max-workers=2"]
    # Use the configured session proxy and system CA trust; do not change the AP's loopback transport.
    proxy = urlparse(os.environ.get("HTTPS_PROXY", ""))
    if proxy.hostname:
        command += [f"-Dhttps.proxyHost={proxy.hostname}", f"-Dhttps.proxyPort={proxy.port or 8080}",
                    f"-Dhttp.proxyHost={proxy.hostname}", f"-Dhttp.proxyPort={proxy.port or 8080}"]
    if Path("/etc/ssl/certs/java/cacerts").exists():
        command += ["-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"]
    if not args.skip_sdk_build:
        run(command + ["jar", "sourcesJar"], SDK, output / "sdk-build.log", env)
    built = SDK / "build/libs/echo-spring-boot-starter-0.1.0-SNAPSHOT.jar"
    sdk = output / built.name
    shutil.copy2(built, sdk)
    sources = SDK / "build/libs/echo-spring-boot-starter-0.1.0-SNAPSHOT-sources.jar"
    if sources.exists():
        shutil.copy2(sources, output / sources.name)
    yaml = HERE / "src/main/resources/application.yml"
    summary = {
        "sdkJarSha256": digest(sdk), "echoServerJarSha256": digest(args.echo_server_jar),
        "applicationYamlSha256": digest(yaml),
        "consumerSourceSha256": digest(HERE / "src/main/java/com/echo/compat/CompatibilityProbe.java"),
        "rows": [],
    }
    process = log = None
    try:
        process, log, base, data = launch_echo(args, output)
        summary["isolatedEchoDataDirectory"] = str(data)
        for boot in args.matrix.split(","):
            cloud = MATRIX[boot]
            run(command + [f"-PbootVersion={boot}", f"-PcloudVersion={cloud}", f"-PsdkJar={sdk}", "prepareProbe"],
                HERE, output / f"boot-{boot}-build.log", env)
            build = HERE / f"build/boot-{boot}"
            classpath = (build / "runtime-classpath.txt").read_text()
            versions = (build / "resolved-versions.txt").read_text().splitlines()
            runtimes = [("17", args.java17_home / "bin/java")]
            if args.java21:
                runtimes.append(("21", args.java21))
            for jdk, java in runtimes:
                result_path = output / f"boot-{boot}-java-{jdk}.json"
                row = {"boot": boot, "cloud": cloud, "jdk": jdk, "resolvedVersions": versions}
                summary["rows"].append(row)
                try:
                    run([str(java), "-Xmx384m", "-cp", classpath, "com.echo.compat.CompatibilityProbe", str(result_path), base],
                        HERE, output / f"boot-{boot}-java-{jdk}.log")
                finally:
                    if result_path.exists():
                        row.update(json.loads(result_path.read_text()))
                if row.get("sdkJarSha256") != summary["sdkJarSha256"] or row.get("applicationYamlSha256") != summary["applicationYamlSha256"]:
                    raise AssertionError("Consumer did not use the identical SDK JAR and application.yml")
                if row.get("bootVersion") != boot:
                    raise AssertionError(f"Requested Boot {boot}, actually ran {row.get('bootVersion')}")
                print(f"PASS Boot {boot}, Cloud {cloud}, Java {jdk}: {len(row['checks'])} checks", flush=True)
        summary["passed"] = True
    except BaseException as failure:
        summary["passed"] = False
        summary["failure"] = str(failure)
        raise
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            log.close()
        (output / "matrix-results.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
