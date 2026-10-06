package com.echo.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登入頁品牌區：裝飾不干擾輔助技術、環境標籤來自 status、文案雙語齊全、窄螢幕只留表單。
 */
class LoginBrandResourceTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void decorativeWaveIsHiddenFromAssistiveTechnology() throws IOException {
        assertThat(text("login.html"))
                .contains("<svg class=\"login-wave\" viewBox=\"0 0 520 520\" fill=\"none\" stroke=\"currentColor\" stroke-linecap=\"round\" aria-hidden=\"true\">")
                .contains("<section class=\"login-brand\" aria-labelledby=\"login-tagline\">");
    }

    @Test
    void environmentLabelStaysHiddenUntilTheServerReportsOne() throws IOException {
        assertThat(text("login.html"))
                .contains("<span id=\"login-env\" class=\"login-env\" hidden></span>")
                .contains("if (data.envLabel) {")
                .contains("envLabel.textContent = data.envLabel;");
    }

    @Test
    void brandCopyIsTranslatedInBothLanguages() throws IOException {
        for (String language : new String[]{"zh-TW", "en"}) {
            JsonNode login = OBJECT_MAPPER.readTree(text("i18n/" + language + ".json")).path("login");
            for (String key : new String[]{"heading", "brandTagline", "brandDescription", "demoLabel", "demoMock", "demoReply", "demoForward"}) {
                assertThat(login.path(key).asText()).as(language + " login." + key).isNotBlank();
            }
        }
        assertThat(text("login.html")).contains("data-i18n-aria-label=\"login.demoLabel\"");
    }

    @Test
    void narrowScreensKeepOnlyTheFormAndTheSharedLogo() throws IOException {
        assertThat(text("login.html"))
                .contains("@media(max-width:900px){.login-shell{grid-template-columns:1fr}.login-brand{display:none}")
                .contains(".login-header .login-logo{display:flex}");
    }

    private static String text(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
