package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 部署環境主色（echo.ui.accent）的前端契約：
 * 主色與主題是兩個獨立維度，主色在 head 同步套用，並由 status API 覆寫。
 */
class UiAccentResourceTest {
    @Test
    void themeDefinesBothAccentsForBothThemes() throws IOException {
        String theme = text("theme.css");

        assertThat(theme)
                .contains(":root {\n    color-scheme: dark;")
                .contains(":root[data-accent=\"blue\"] {")
                .contains(":root[data-theme=\"light\"] {")
                .contains(":root[data-theme=\"light\"][data-accent=\"blue\"] {");
        assertThat(theme.indexOf(":root[data-accent=\"blue\"]"))
                .as("light theme must come after the dark blue accent so it can reset neutrals")
                .isLessThan(theme.indexOf(":root[data-theme=\"light\"] {"));
        assertThat(theme.split("--on-primary:", -1)).hasSize(5);
    }

    @Test
    void textOnPrimaryFillsFollowsTheAccent() throws IOException {
        String css = text("style.css");

        assertThat(css)
                .contains(".btn-primary { background: var(--btn-primary); color: var(--on-primary);")
                .contains(".ui-button--primary { border-color: var(--btn-primary); background: var(--btn-primary); color: var(--on-primary);")
                .doesNotContain("background: var(--btn-primary); color: #fff");
    }

    @Test
    void environmentLabelUsesTheAccent() throws IOException {
        assertThat(text("style.css"))
                .contains(".env-label { background: rgba(var(--primary-rgb), 0.12); color: var(--primary);")
                .doesNotContain(".env-label { background: rgba(var(--warning-rgb)");
    }

    @Test
    void accentIsAppliedBeforeFirstPaintAndRefreshedFromStatus() throws IOException {
        String index = text("index.html");
        String login = text("login.html");
        String head = index.substring(0, index.indexOf("</head>"));
        String loginHead = login.substring(0, login.indexOf("</head>"));

        assertThat(head).contains("<script src=\"/ui-accent.js?v=20261006.1\"></script>");
        assertThat(loginHead).contains("<script src=\"/ui-accent.js?v=20261006.1\"></script>");
        assertThat(text("app.js")).contains("window.EchoAccent?.apply(data.uiAccent);");
        assertThat(login).contains("window.EchoAccent.apply(data.uiAccent);");
    }

    @Test
    void accentScriptOnlyAcceptsKnownValuesAndSurvivesBlockedStorage() throws IOException {
        String script = text("ui-accent.js");

        assertThat(script)
                .contains("var ACCENTS = ['teal', 'blue'];")
                .contains("return ACCENTS.indexOf(value) >= 0 ? value : 'teal';")
                .contains("try { cached = localStorage.getItem(STORAGE_KEY); } catch (e)")
                .contains("try { localStorage.setItem(STORAGE_KEY, accent); } catch (e)");
    }

    @Test
    void monospaceFontIsBundledWithoutCdn() throws IOException {
        String css = text("style.css");

        assertThat(css)
                .contains("src: url('/webjars/fontsource__jetbrains-mono/files/jetbrains-mono-latin-400-normal.woff2')")
                .contains("--font-mono: 'JetBrains Mono', 'Cascadia Mono', 'Consolas',")
                .doesNotContain("fonts.googleapis.com");
        assertThat(text("index.html")).doesNotContain("fonts.googleapis.com");
    }

    private static String text(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
