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

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
