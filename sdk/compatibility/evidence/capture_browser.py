"""Capture unmodified product UI and the live proof AP; never inject response data."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright


def capture(manifest, output):
    screenshots = output / "screenshots"
    screenshots.mkdir(exist_ok=True)
    captures = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path="/usr/bin/chromium", headless=True,
                                             args=["--no-sandbox", "--disable-dev-shm-usage"])
        context = browser.new_context(viewport={"width": 1440, "height": 1100}, locale="zh-TW",
                                      color_scheme="light", record_har_path=str(output / "ap-browser-network.har"),
                                      record_har_content="embed")
        errors = []
        for app in manifest["apps"]:
            page = context.new_page()
            page.on("pageerror", lambda error: errors.append(str(error)))
            page.goto(app["base"] + "/proof", wait_until="networkidle")
            page.wait_for_function("window.proofEvidence?.complete === true", timeout=30000)
            evidence = page.evaluate("window.proofEvidence")
            (output / (app["label"] + "-browser.json")).write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n")
            if not evidence.get("passed"):
                page.screenshot(path=str(screenshots / (app["label"] + "-FAILED.png")), full_page=True)
                raise AssertionError("Live proof AP failed: " + json.dumps(evidence, ensure_ascii=False))
            assert evidence["meta"]["sdkJarSha256"] == manifest["sdkJarSha256"]
            assert evidence["meta"]["applicationYamlSha256"] == manifest["applicationYamlSha256"]
            assert evidence["meta"]["boot"] == app["boot"] and evidence["meta"]["sdkEnabled"] == app["enabled"]
            page.evaluate("document.fonts.ready")
            image_path = screenshots / (app["label"] + ".png")
            page.screenshot(path=str(image_path), full_page=True)
            evidence["screenshot"] = str(image_path)
            evidence["appUrl"] = app["base"] + "/proof"
            captures.append(evidence)
            print("CAPTURED " + image_path.name, flush=True)
            page.close()
        context.close()
        assert not errors, errors

        # Authenticate against the isolated server, using its real form-login session.
        echo = manifest["echoBase"]
        ui = browser.new_context(viewport={"width": 1600, "height": 1100}, locale="zh-TW", color_scheme="light")
        ui.add_init_script("localStorage.setItem('echo_help_seen','1');localStorage.setItem('echo_locale','zh-TW');localStorage.setItem('ruleViewMode','list');localStorage.setItem('echo_theme','light');")
        ui.request.post(echo + "/api/auth/login", form={"username": "sdk-proof", "password": "local-proof-only"})
        status = ui.request.get(echo + "/api/admin/status", headers={"Accept": "application/json"}).json()
        assert status.get("isLoggedIn") and status.get("isAdmin"), status
        page = ui.new_page()
        page.goto(echo + "/#/rules?keyword=SDK%20PROOF", wait_until="networkidle")
        page.get_by_text("SDK PROOF - Spring Boot 3.5.16", exact=True).first.wait_for(state="visible")
        page.get_by_text("SDK PROOF - Spring Boot 4.1.1", exact=True).first.wait_for(state="visible")
        page.evaluate("document.fonts.ready")
        page.screenshot(path=str(screenshots / "echo-rules.png"), full_page=True)

        stats_url = echo + "/#/stats?endpoint=sdk-proof"
        page.goto(stats_url, wait_until="networkidle")
        page.locator("tr[data-detail-row]").first.wait_for(state="visible")
        page.screenshot(path=str(screenshots / "echo-requests.png"), full_page=True)
        for boot in ["4.1.1", "3.5.16"]:
            if boot == "3.5.16":
                page.goto(echo + "/", wait_until="networkidle")
                page.goto(stats_url, wait_until="networkidle")
                page.locator("tr[data-detail-row]").first.wait_for(state="visible")
            row = page.locator("tr[data-detail-row]").filter(has_text=boot).first
            row.click()
            page.locator("[id^='log-tab-body-']").click()
            page.locator(".log-inspector-body-grid").wait_for(state="visible")
            page.get_by_text("serverMarker", exact=False).first.wait_for(state="visible")
            page.screenshot(path=str(screenshots / ("echo-log-detail-boot-" + boot + ".png")), full_page=False)
        (output / "echo-ui-observed.txt").write_text(page.locator("body").inner_text())
        ui.close()
        browser.close()
    return captures
