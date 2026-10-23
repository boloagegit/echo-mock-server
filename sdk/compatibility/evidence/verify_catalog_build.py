#!/usr/bin/env python3
"""Exercise invalid catalog inputs, then restore and verify the normal production JAR."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

REPO = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(args.java_home))
    command = [str(args.gradle), "--offline", "--no-daemon", "--console=plain"]
    fixture = REPO / "sdk/compatibility/catalog-fixtures/1.1.0"
    results = []
    cases = [
        ("missing-artifact", "artifact missing or outside"),
        ("duplicate-version", "duplicate SDK/version"),
        ("no-recommended-version", "exactly one recommended release"),
        ("unknown-guide-input", "unknown guide input"),
        ("unknown-option-artifact", "unknown guide option artifact"),
    ]
    try:
        for name, expected in cases:
            with tempfile.TemporaryDirectory(prefix="echo-catalog-validation-") as directory:
                root = Path(directory)
                shutil.copytree(fixture, root / "one")
                descriptor = root / "one/release.json"
                data = json.loads(descriptor.read_text())
                if name == "missing-artifact":
                    data["artifacts"][0]["fileName"] = "missing.txt"
                elif name == "duplicate-version":
                    shutil.copytree(fixture, root / "two")
                elif name == "no-recommended-version":
                    data["recommended"] = False
                elif name == "unknown-option-artifact":
                    data["guide"]["sections"][0]["options"] = [{"id": "method", "label": "Method", "artifact": "missing"}]
                else:
                    data["guide"]["sections"][0]["snippets"][0]["code"] = "{{json:missing}}"
                descriptor.write_text(json.dumps(data))
                run = subprocess.run(command + ["bundleSdk", "-PsdkCatalogDir=" + str(root)], cwd=REPO,
                                     env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                (output / (name + ".log")).write_text(run.stdout)
                assert run.returncode != 0 and expected in run.stdout, (name, run.stdout)
                results.append({"case": name, "rejected": True, "expectedMessage": expected})
                print("PASS rejected " + name, flush=True)
    finally:
        run = subprocess.run(command + ["bootJar"], cwd=REPO, env=env, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, text=True)
        (output / "restore-production.log").write_text(run.stdout)
        if run.returncode != 0:
            raise RuntimeError("Failed to restore the production build: " + run.stdout)
    jars = list((REPO / "build/libs").glob("echo-server-*.jar"))
    assert len(jars) == 1
    with zipfile.ZipFile(jars[0]) as archive:
        catalog = json.loads(archive.read("BOOT-INF/classes/echo-sdk/catalog.json"))
        assert "catalog-test-client" not in {sdk["id"] for sdk in catalog["sdks"]}
        assert not any("echo-sdk/catalog-test-client/" in name for name in archive.namelist())
    (output / "build-validation-results.json").write_text(json.dumps({
        "passed": True, "cases": results, "productionRestored": True,
        "productionSdkIds": [sdk["id"] for sdk in catalog["sdks"]],
    }, indent=2) + "\n")
    print("PASS production catalog restored without fixture metadata or files", flush=True)


if __name__ == "__main__":
    main()
