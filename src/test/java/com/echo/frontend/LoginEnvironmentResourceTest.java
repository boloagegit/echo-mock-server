package com.echo.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登入卡片：環境橫條讓使用者在登入前分辨部署環境，頁尾顯示版本與主機。
 */
class LoginEnvironmentResourceTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void environmentStripStaysHiddenUntilTheServerReportsALabel() throws IOException {
        assertThat(text("login.html"))
                .contains("<div id=\"login-env\" class=\"login-env\" hidden>")
                .contains("if (serverInfo.envLabel) {")
                .contains("env.hidden = false;")
                .contains(".login-env{display:flex;align-items:center;gap:0.5rem;padding:0.625rem 1.75rem;background:var(--primary);color:var(--on-primary);");
    }

    @Test
    void cardWithoutAnEnvironmentLabelStillShowsTheDeploymentAccent() throws IOException {
        assertThat(text("login.html"))
                .contains(".login-card:not(.has-env){box-shadow:inset 0 3px 0 var(--primary)")
                .contains("card.classList.add('has-env');");
    }

    @Test
    void footerShowsVersionAndHostWithoutMarketingCopy() throws IOException {
        String login = text("login.html");

        assertThat(login)
                .contains("<p id=\"login-meta\" class=\"login-meta\"></p>")
                .contains("loginText.version + ' ' + (serverInfo.version || 'dev') + ' · ' + window.location.host")
                .doesNotContain("login-brand")
                .doesNotContain("login-wave")
                .doesNotContain("login-demo");
    }

    @Test
    void environmentAndVersionCopyIsTranslatedInBothLanguages() throws IOException {
        for (String language : new String[]{"zh-TW", "en"}) {
            JsonNode login = OBJECT_MAPPER.readTree(text("i18n/" + language + ".json")).path("login");
            assertThat(login.path("environment").asText()).as(language).contains("{env}");
            assertThat(login.path("version").asText()).as(language).isNotBlank();
            assertThat(login.has("brandTagline")).as(language).isFalse();
        }
    }

    private static String text(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
