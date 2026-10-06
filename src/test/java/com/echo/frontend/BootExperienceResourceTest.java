package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重新整理不閃爍：偏好在首次繪製前套用、靜態外框蓋住啟動過程、快取身分讓側欄一次到位。
 */
class BootExperienceResourceTest {
    @Test
    void preferencesApplyBeforeFirstPaint() throws IOException {
        String index = text("index.html");
        String head = index.substring(0, index.indexOf("</head>"));

        assertThat(head).contains("<script src=\"/ui-boot.js?v=20261007.3\"></script>");
        assertThat(text("ui-boot.js"))
                .contains("root.setAttribute('data-theme', effective === 'light' ? 'light' : 'dark');")
                .contains("root.setAttribute('data-density', density);")
                .contains("catch (e) { /* storage unavailable: CSS defaults apply */ }");
    }

    @Test
    void staticShellCoversStartupWithTheAppGeometry() throws IOException {
        String index = text("index.html");
        String console = text("console.css");

        assertThat(index.indexOf("<div id=\"boot-shell\" class=\"boot-shell\" aria-hidden=\"true\">"))
                .isPositive()
                .isLessThan(index.indexOf("<div id=\"app\" v-cloak>"));
        assertThat(console)
                .contains(".boot-shell__sidebar { flex: 0 0 var(--sidebar-width);")
                .contains(".boot-shell.is-leaving { opacity: 0; pointer-events: none }")
                .contains("@media (prefers-reduced-motion: reduce) { .boot-shell { transition: none } }");
    }

    @Test
    void nothingContentfulPaintsBeforeTheAppIsReady() throws IOException {
        // Browsers keep the previous page until the first contentful paint, so a reload
        // swaps straight from the old page to the finished one instead of via a skeleton.
        assertThat(text("ui-boot.js")).contains("root.classList.add('is-booting');");
        assertThat(text("console.css"))
                .contains(".is-booting #app { visibility: hidden }")
                .contains(".boot-shell > * { animation: loadingReveal 160ms var(--ease-standard) 300ms both }");
        assertThat(text("app.js"))
                .contains("root.classList.remove('is-booting');")
                .contains("performance.now() < BOOT_SHELL_VISIBLE_AFTER_MS) { shell.remove(); return; }");
    }

    @Test
    void reloadPaintsASnapshotOfThePageBeingLeftWithoutTransientOrSensitiveParts() throws IOException {
        String index = text("index.html");
        String app = text("app.js");

        // Restored synchronously right after the shell, before the first paint.
        assertThat(index.indexOf("<script src=\"/ui-snapshot.js?v="))
                .isGreaterThan(index.indexOf("<div id=\"boot-shell\""))
                .isLessThan(index.indexOf("<div id=\"app\" v-cloak>"));
        assertThat(app)
                .contains("window.addEventListener('pagehide', savePageSnapshot);")
                .contains("shownPage.value === 'settings'")
                .contains(".modal-overlay, [aria-modal=\"true\"], .ui-detail-drawer, .toast-wrap, .ui-dropdown-menu__panel, .user-menu, .tour-overlay, .top-loader, input[type=\"password\"]")
                .contains("if (shell.classList.contains('boot-shell--snapshot') || performance.now() < BOOT_SHELL_VISIBLE_AFTER_MS) { shell.remove(); return; }");
        assertThat(text("composables/useAuth.js")).contains("sessionStorage.removeItem('echo.pageSnapshot');");
        // Icons and code text are ready on the first paint.
        assertThat(index)
                .contains("<link rel=\"preload\" href=\"/webjars/bootstrap-icons/font/fonts/bootstrap-icons.woff2?")
                .contains("jetbrains-mono-latin-400-normal.woff2\" as=\"font\" type=\"font/woff2\" crossorigin>");
    }

    @Test
    void shellLeavesOnlyWhenTranslationsIdentityAndFirstRowsAreReady() throws IOException {
        String app = text("app.js");

        assertThat(app)
                .contains("const revealFallback = setTimeout(revealApp, 2500);")
                .contains("const BOOT_DATA_WAIT_MS = 600;")
                // A cached identity only shortens the wait; it no longer reveals an empty list.
                .contains("if (cachedStatus) { setTimeout(revealApp, BOOT_DATA_WAIT_MS); }")
                .doesNotContain("if (cachedStatus) { revealApp(); }")
                .contains("const firstPage = applyUrlParams();")
                .contains("await Promise.race([firstPage, new Promise(resolve => { dataWait = setTimeout(resolve, BOOT_DATA_WAIT_MS); })]);")
                .contains("sessionStorage.setItem(STATUS_CACHE_KEY")
                .doesNotContain("'datasourceUrl', ")
                .doesNotContain("'ldapUrl'");
        assertThat(text("composables/useAuth.js")).contains("sessionStorage.removeItem('echo.statusCache');");
    }

    private static String text(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
