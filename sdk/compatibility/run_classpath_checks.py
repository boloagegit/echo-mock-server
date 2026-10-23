#!/usr/bin/env python3
"""After run_matrix.py, check optional dependencies by actually removing their JARs."""
import argparse
import json
import os
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java17", type=Path, required=True)
    parser.add_argument("--java21", type=Path)
    parser.add_argument("--matrix-output", type=Path, required=True)
    parser.add_argument("--only-web", action="store_true", help="Run servlet HTTP AP checks on Boot 3.5 and 4.1")
    parser.add_argument("--only-without-loadbalancer", action="store_true", help="Run fixed-URL AP checks without the optional LB module")
    args = parser.parse_args()
    summary = json.loads((args.matrix_output / "matrix-results.json").read_text())
    # Gradle may remove previous version-specific compile outputs when the same
    # task changes buildDirectory. Compile the AP and supplemental probes once
    # against the oldest dependencies, then reuse that bytecode on every runtime.
    extra = HERE / "build/classpath-probes"
    extra.mkdir(parents=True, exist_ok=True)
    first_classpath = (HERE / f"build/boot-{summary['rows'][0]['boot']}/runtime-classpath.txt").read_text()
    subprocess.run([str(args.java17.parent / "javac"), "--release", "17", "-cp", first_classpath,
                    "-d", str(extra), str(HERE / "src/main/java/com/echo/compat/CompatibilityProbe.java"),
                    str(HERE / "src/main/java/com/echo/compat/WithoutFeignProbe.java"),
                    str(HERE / "src/main/java/com/echo/compat/WithoutRetryProbe.java"),
                    str(HERE / "src/main/java/com/echo/compat/WebApplicationProbe.java"),
                    str(HERE / "src/main/java/com/echo/compat/WithoutLoadBalancerProbe.java")], check=True)
    checks = []
    for row in summary["rows"]:
        java = args.java17 if row["jdk"] == "17" else args.java21
        if java is None:
            continue
        classpath = (HERE / f"build/boot-{row['boot']}/runtime-classpath.txt").read_text().split(os.pathsep)
        probes = [
            ("WithoutFeignProbe", lambda path: not (Path(path).name.startswith("feign-")
                or Path(path).name.startswith("spring-cloud-openfeign-")
                or Path(path).name.startswith("spring-cloud-starter-openfeign-"))),
            ("WithoutRetryProbe", lambda path: not Path(path).name.startswith("spring-retry-")),
        ]
        if args.only_web:
            if row["boot"] not in ("3.5.16", "4.1.1"):
                continue
            probes = [("WebApplicationProbe", lambda path: True)]
        elif args.only_without_loadbalancer:
            probes = [("WithoutLoadBalancerProbe", lambda path: not Path(path).name.startswith(
                ("spring-cloud-loadbalancer-", "spring-cloud-starter-loadbalancer-")))]
        for probe, keep in probes:
            filtered = os.pathsep.join([str(extra), str(HERE / "src/main/resources")]
                                      + [path for path in classpath if keep(path)])
            logfile = args.matrix_output / f"boot-{row['boot']}-java-{row['jdk']}-{probe}.log"
            with logfile.open("w") as log:
                result = subprocess.run([str(java), "-Xmx384m", "-cp", filtered, "com.echo.compat." + probe],
                                        cwd=HERE, stdout=log, stderr=subprocess.STDOUT, timeout=90)
            check = {"boot": row["boot"], "jdk": row["jdk"], "probe": probe, "passed": result.returncode == 0}
            checks.append(check)
            print(("PASS " if check["passed"] else "FAIL ") + str(check), flush=True)
            report = ("web-results.json" if args.only_web else "without-loadbalancer-results.json"
                      if args.only_without_loadbalancer else "classpath-results.json")
            (args.matrix_output / report).write_text(json.dumps(checks, indent=2) + "\n")
            if not check["passed"]:
                raise RuntimeError("Classpath probe failed; see " + str(logfile))


if __name__ == "__main__":
    main()
