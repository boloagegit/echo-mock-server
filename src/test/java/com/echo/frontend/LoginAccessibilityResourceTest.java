package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAccessibilityResourceTest {

    @Test
    void associatesEveryLoginFlowLabelWithItsInput() throws IOException {
        String login = resourceText("static/login.html");

        assertThat(login)
                .contains("for=\"login-username\"")
                .contains("for=\"login-password\"")
                .contains("for=\"forgot-username\"")
                .contains("for=\"register-username\"")
                .contains("for=\"register-password\"");
    }

    @Test
    void updatesDocumentLanguageWhenLoginLocaleChanges() throws IOException {
        String login = resourceText("static/login.html");

        assertThat(login)
                .contains("document.documentElement.lang = locale === 'zh-TW' ? 'zh-TW' : 'en';");
    }

    /** One neutral "fading E" mark: the same strokes in the favicon, the sidebar and the login card. */
    private static final String[] MARK_STROKES = {
            "<path d=\"M6.5 4.5V19.5\"/>",
            "<path d=\"M6.5 4.5H18\"/>",
            "<path d=\"M6.5 12H15.5\" stroke-opacity=\"0.78\"/>",
            "<path d=\"M6.5 19.5H14.5\" stroke-opacity=\"0.55\"/>"
    };

    @Test
    void appLogoIsSharedByFaviconSidebarAndLogin() throws IOException {
        String index = resourceText("static/index.html");
        String login = resourceText("static/login.html");
        String sidebar = resourceText("static/components/SidebarNav.js");
        String favicon = resourceText("static/favicon.svg");

        for (String page : new String[]{index, login}) {
            assertThat(page)
                    .contains("<link rel=\"icon\" type=\"image/svg+xml\" href=\"/favicon.svg?v=20261007.1\">")
                    .contains("<link rel=\"icon\" type=\"image/x-icon\" href=\"/favicon.ico?v=20261007.1\" sizes=\"any\">");
        }
        for (String stroke : MARK_STROKES) {
            assertThat(favicon).contains(stroke);
            assertThat(sidebar).contains(stroke);
            assertThat(login).contains(stroke);
        }
        assertThat(sidebar)
                .contains("class=\"brand-mark\" aria-hidden=\"true\"")
                .contains("<span class=\"brand-sub\">Mock Server</span>");
        assertThat(login).contains("<div class=\"login-logo\"><svg class=\"login-mark\"");
        // The mark stays neutral; deployment accents belong to the environment label only.
        // The tile never inverts between themes, so switching theme does not flip the logo.
        assertThat(resourceText("static/theme.css")).contains("--brand-tile: #1f2329;").contains("--brand-tile: #16191d;")
                .doesNotContain("--brand-ink: #14171b;");
        assertThat(favicon).doesNotContainIgnoringCase("#82a8f7").doesNotContainIgnoringCase("#46d3be");
        assertThat(new ClassPathResource("static/favicon.ico").exists()).isTrue();
    }

    @Test
    void appLogoMotionIsSubtleAndCanBeReduced() throws IOException {
        String index = resourceText("static/index.html");
        String login = resourceText("static/login.html");
        String sidebar = resourceText("static/components/SidebarNav.js");
        String css = resourceText("static/style.css");

        assertThat(index)
                .contains("/style.css?v=20261007.15")
                .contains("/components/SidebarNav.js?v=20261007.9");
        assertThat(sidebar).contains("class=\"brand-mark\" aria-hidden=\"true\"");
        assertThat(css)
                .contains(".sidebar-brand:hover .brand-icon { transform: scale(1.06) rotate(-3deg) }")
                .contains("animation: brandEcho var(--motion-slow) var(--ease-standard)")
                .contains("@keyframes brandEcho")
                .doesNotContain("animation: brandEcho infinite");
        assertThat(login)
                .contains(".login-logo:hover .login-mark{transform:scale(1.05) rotate(-3deg)}")
                .contains("@media(prefers-reduced-motion:reduce)")
                .contains(".login-logo:hover::after{animation:none}");
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
