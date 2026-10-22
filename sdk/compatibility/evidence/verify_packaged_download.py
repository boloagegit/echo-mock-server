#!/usr/bin/env python3
"""Verify the SDK Center and versioned downloads from an isolated executable Echo JAR."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import parse_qs, urlsplit
import zipfile

from playwright.sync_api import sync_playwright

from capture_evidence import free_port, request, wait_json

HERE = Path(__file__).resolve().parent


def digest(data):
    return hashlib.sha256(data).hexdigest()


def localized(value):
    return value if isinstance(value, str) else value.get("zh-TW", value.get("en", next(iter(value.values()))))


def wait_release(page, sdk_id, version):
    page.wait_for_function(
        "(s) => { const e=document.querySelector('.sdk-release'); return e && e.dataset.sdkId===s[0] && e.dataset.sdkVersion===s[1]; }",
        arg=[sdk_id, version])
    params = parse_qs(urlsplit(page.url).fragment.partition("?")[2])
    assert params.get("sdk") == [sdk_id] and params.get("version") == [version], page.url


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--echo-server-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--downloads-only", action="store_true", help="Skip the unchanged OpenFeign AP compatibility probes")
    parser.add_argument("--expect-fixtures", action="store_true", help="Check the explicitly included multi-release catalog fixture")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    screenshots = output / "screenshots"
    screenshots.mkdir(exist_ok=True)
    downloads = output / "downloads"
    downloads.mkdir(exist_ok=True)
    server_jar = args.echo_server_jar.resolve()
    report = {"startedAt": datetime.now(timezone.utc).isoformat(),
              "verificationScope": "downloads" if args.downloads_only else "downloads-and-runtime",
              "echoServerJarSha256": digest(server_jar.read_bytes()), "downloads": []}
    with zipfile.ZipFile(server_jar) as archive:
        prefix = "BOOT-INF/classes/echo-sdk/"
        catalog = json.loads(archive.read(prefix + "catalog.json"))
        assert catalog["schemaVersion"] == 1
        sdk_ids = [sdk["id"] for sdk in catalog["sdks"]]
        if args.expect_fixtures:
            assert "catalog-test-client" in sdk_ids
            fixture = next(sdk for sdk in catalog["sdks"] if sdk["id"] == "catalog-test-client")
            assert {release["version"] for release in fixture["releases"]} == {"1.0.0", "1.1.0"}
        else:
            assert "catalog-test-client" not in sdk_ids, "Test fixtures leaked into production"
            assert not any("echo-sdk/catalog-test-client/" in name for name in archive.namelist())
        for sdk in catalog["sdks"]:
            assert sdk["recommendedVersion"] in [release["version"] for release in sdk["releases"]]
            for release in sdk["releases"]:
                for artifact in release["artifacts"]:
                    raw = archive.read(prefix + sdk["id"] + "/" + release["version"] + "/" + artifact["fileName"])
                    assert digest(raw) == artifact["sha256"] and len(raw) == artifact["size"]
        assert not any(name.startswith("BOOT-INF/lib/echo-spring-boot") for name in archive.namelist())
    (output / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
    report["sdkCount"] = len(catalog["sdks"])
    report["releaseCount"] = sum(len(sdk["releases"]) for sdk in catalog["sdks"])
    errors = []
    with tempfile.TemporaryDirectory(prefix="echo-sdk-center-deployment-") as directory:
        deployment = Path(directory)
        shutil.copy2(server_jar, deployment / "echo-server.jar")
        (deployment / "data").mkdir()
        report["deploymentFilesBeforeStart"] = sorted(path.name for path in deployment.iterdir())
        base = "http://127.0.0.1:" + str(free_port())
        report["echoBase"] = base
        log = (output / "packaged-server.log").open("w")
        process = subprocess.Popen([
            str(args.java_home / "bin/java"), "-Xms64m", "-Xmx512m", "-jar", "echo-server.jar",
            "--server.address=127.0.0.1", "--server.port=" + base.rsplit(":", 1)[1],
            "--echo.admin.username=sdk-proof", "--echo.admin.password=local-proof-only",
            "--echo.jms.enabled=false", "--echo.backup.enabled=false", "--echo.cleanup.enabled=false",
            "--echo.diagnostics.enabled=false",
        ], cwd=deployment, stdout=log, stderr=subprocess.STDOUT)
        try:
            wait_json(process, base + "/actuator/health/readiness")
            report["anonymousStatus"] = {}
            routes = ["/api/admin/sdk"] + [
                artifact["downloadUrl"] for sdk in catalog["sdks"] for release in sdk["releases"]
                for artifact in release["artifacts"]]
            for route in routes:
                status, _ = request(base + route)
                assert status == 401, (route, status)
                report["anonymousStatus"][route] = status
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(executable_path="/usr/bin/chromium", headless=True,
                                                     args=["--no-sandbox", "--disable-dev-shm-usage"])
                context = browser.new_context(viewport={"width": 1440, "height": 1100}, locale="zh-TW")
                context.add_init_script(
                    "localStorage.setItem('echo_locale','zh-TW');localStorage.setItem('echo_help_seen','1');")
                page = context.new_page()
                page.on("pageerror", lambda error: errors.append(str(error)))
                page.goto(base + "/login.html", wait_until="networkidle")
                page.locator("[name=username]").fill("sdk-proof")
                page.locator("[name=password]").fill("local-proof-only")
                page.locator("button[type=submit]").click()
                page.wait_for_url(base + "/", timeout=30000)
                assert page.locator(".sidebar-nav").get_by_role("button", name="SDK 中心", exact=True).count() == 0
                assert page.get_by_role("button", name="SDK 中心", exact=True).count() == 0
                page.locator(".user-trigger").click()
                menu = page.locator("#sidebar-user-menu")
                menu.get_by_role("button", name="SDK 中心", exact=True).wait_for()
                page.screenshot(path=str(screenshots / "sdk-menu.png"), full_page=True, animations="disabled")
                menu.get_by_role("button", name="SDK 中心", exact=True).click()
                menu.wait_for(state="hidden")
                page.locator(".sdk-catalog-card").first.wait_for()
                assert page.locator(".sdk-catalog-card").count() == len(catalog["sdks"])
                assert page.url.endswith("#/sdk")
                info_response = context.request.get(base + "/api/admin/sdk", headers={"Accept": "application/json"})
                assert info_response.status == 200 and info_response.json() == catalog
                report["entryPoint"] = "account-menu"
                report["sdkAbsentFromPrimaryNavigation"] = True
                page.evaluate("document.fonts.ready")
                page.screenshot(path=str(screenshots / "sdk-center.png"), full_page=True, animations="disabled")
                if args.expect_fixtures:
                    search = page.get_by_placeholder("搜尋 SDK 名稱、語言或用途")
                    search.fill("Catalog test client")
                    assert page.locator(".sdk-catalog-card").count() == 1
                    search.fill("")
                    assert page.locator(".sdk-catalog-card").count() == len(catalog["sdks"])
                    report["catalogSearchWorks"] = True

                for index, sdk in enumerate(catalog["sdks"]):
                    if index:
                        page.get_by_role("button", name="返回 SDK 清單", exact=True).click()
                        page.locator(".sdk-catalog").wait_for()
                    page.locator(".sdk-catalog-card[data-sdk-id='" + sdk["id"] + "']").get_by_role("button").click()
                    wait_release(page, sdk["id"], sdk["recommendedVersion"])
                    for release in sdk["releases"]:
                        page.get_by_label("版本", exact=True).select_option(release["version"])
                        wait_release(page, sdk["id"], release["version"])
                        page.reload(wait_until="networkidle")
                        wait_release(page, sdk["id"], release["version"])
                        for section in release["guide"]["sections"]:
                            assert localized(section["title"]) in page.locator(".sdk-release").inner_text()
                        for artifact in release["artifacts"]:
                            with page.expect_response(base + artifact["downloadUrl"]) as response_event:
                                with page.expect_download() as download_event:
                                    page.locator("[data-artifact-id='" + artifact["id"] + "']").click()
                            response = response_event.value
                            download = download_event.value
                            assert response.status == 200 and download.suggested_filename == artifact["fileName"]
                            destination = downloads / sdk["id"] / release["version"] / artifact["fileName"]
                            destination.parent.mkdir(parents=True, exist_ok=True)
                            download.save_as(destination)
                            raw = destination.read_bytes()
                            assert len(raw) == artifact["size"] and digest(raw) == artifact["sha256"]
                            report["downloads"].append({
                                "sdkId": sdk["id"], "version": release["version"], "artifactId": artifact["id"],
                                "url": response.url, "status": response.status, "file": str(destination),
                                "contentType": response.headers.get("content-type"),
                                "contentDisposition": response.headers.get("content-disposition"),
                                "bytes": len(raw), "sha256": digest(raw)})
                        if sdk["id"] == "spring-openfeign":
                            jar = next(artifact for artifact in release["artifacts"] if artifact["id"] == "jar")
                            bundle = next(artifact for artifact in release["artifacts"] if artifact["id"] == "bundle")
                            location = downloads / sdk["id"] / release["version"]
                            with zipfile.ZipFile(location / bundle["fileName"]) as archive:
                                assert archive.testzip() is None
                                assert archive.read(jar["fileName"]) == (location / jar["fileName"]).read_bytes()
                            page.get_by_text("Maven", exact=True).click()
                            page.locator(".sdk-snippet").filter(has_text="mvn install:install-file").wait_for()
                            assert release["version"] in page.locator(".sdk-snippet").first.inner_text()
                            page.get_by_label("要導向的 Feign client", exact=True).fill("companyOrders")
                            assert '"companyOrders"' in page.locator("[data-guide-id=configure] code").inner_text()
                            page.get_by_label("要導向的 Feign client", exact=True).fill("orderClient")
                            page.get_by_text("Gradle", exact=True).click()
                            page.wait_for_function(
                                "document.querySelectorAll('[data-guide-id=install] .ui-segmented-control__check').length === 1")
                        page.screenshot(path=str(screenshots / ("sdk-" + sdk["id"] + "-" + release["version"] + ".png")),
                                        full_page=True, animations="disabled")
                    if len(sdk["releases"]) > 1:
                        previous = sdk["releases"][-1]["version"]
                        page.get_by_label("版本", exact=True).select_option(sdk["recommendedVersion"])
                        wait_release(page, sdk["id"], sdk["recommendedVersion"])
                        page.go_back()
                        wait_release(page, sdk["id"], previous)
                        page.go_forward()
                        wait_release(page, sdk["id"], sdk["recommendedVersion"])
                        report["versionHistoryWorks"] = True

                page.goto(base + "/#/sdk?sdk=missing&version=99", wait_until="networkidle")
                page.get_by_text("找不到指定的 SDK 或版本", exact=True).wait_for()
                page.get_by_role("button", name="返回 SDK 清單", exact=True).click()
                page.locator(".sdk-catalog").wait_for()
                report["unknownSelectionDoesNotFallBack"] = True
                page.locator(".sidebar-toggle").click()
                page.locator(".user-trigger").click()
                menu.get_by_role("button", name="SDK 中心", exact=True).click()
                menu.wait_for(state="hidden")
                page.locator(".sdk-catalog").wait_for()
                page.locator(".sidebar-toggle").click()
                report["collapsedSidebarMenuWorks"] = True
                page.set_viewport_size({"width": 393, "height": 852})
                page.locator(".mobile-menu-btn").click()
                page.locator(".user-trigger").click()
                menu.get_by_role("button", name="SDK 中心", exact=True).click()
                menu.wait_for(state="hidden")
                assert page.locator(".sidebar.mobile-open").count() == 0
                page.locator(".sdk-catalog").wait_for()
                assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
                page.screenshot(path=str(screenshots / "sdk-center-mobile.png"), full_page=True, animations="disabled")
                report["mobileMenuWorks"] = True
                assert not errors, errors
                report["browserErrors"] = errors
                context.close()
                browser.close()
            report["passed"] = True
            print("PASS: SDK catalog, versioned guides, deep links, menu and every declared download", flush=True)
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            log.close()
            report["packagedServerStopped"] = process.poll() is not None
            report["completedAt"] = datetime.now(timezone.utc).isoformat()
            (output / "packaged-download-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")

    if args.downloads_only:
        return
    actual_jar = next(item for item in report["downloads"]
                      if item["sdkId"] == "spring-openfeign" and item["artifactId"] == "jar"
                      and item["version"] == next(sdk["recommendedVersion"] for sdk in catalog["sdks"] if sdk["id"] == "spring-openfeign"))
    child_output = output / "downloaded-sdk-live"
    subprocess.run([
        sys.executable, str(HERE / "capture_evidence.py"),
        "--java-home", str(args.java_home), "--echo-server-jar", str(server_jar),
        "--sdk-jar", actual_jar["file"], "--output", str(child_output),
    ], check=True)
    child = json.loads((child_output / "proof-results.json").read_text())
    assert child["verification"]["passed"] and child["sdkJarSha256"] == actual_jar["sha256"]
    report["downloadedSdkRuntimeVerification"] = child["verification"]
    report["downloadedSdkRuntimeReport"] = str(child_output / "proof-results.json")
    (output / "packaged-download-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print("PASS: downloaded SDK also works in Boot 3.5.16 and 4.1.1", flush=True)


if __name__ == "__main__":
    main()
