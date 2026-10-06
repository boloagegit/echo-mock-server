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

        assertThat(head).contains("<script src=\"/ui-boot.js?v=20261007.1\"></script>");
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
