package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signal Console 列表樣式契約：方法色碼、條件色彩與寬螢幕單行規則列。
 */
class SignalConsoleListResourceTest {
    @Test
    void ruleIdentityExposesMethodAndProtocolForColourCoding() throws IOException {
        assertThat(text("components/RuleListParts.js"))
                .contains("class=\"rule-protocol\" :data-protocol=\"rule.protocol\"")
                .contains("class=\"badge badge-method\" :data-method=\"rule.method\"");
    }

    @Test
    void everyHttpMethodHasItsOwnColourTokenInBothThemes() throws IOException {
        String css = text("style.css");
        String theme = text("theme.css");

        for (String method : new String[]{"GET", "POST", "PUT", "PATCH", "DELETE"}) {
            String token = "--method-" + method.toLowerCase();
            assertThat(css).contains(".ui-badge.badge-method[data-method=\"" + method + "\"] { color: var(" + token + ") }");
            assertThat(theme.split(token + ":", -1)).as(token).hasSize(3);
        }
        assertThat(css).contains(".rule-protocol[data-protocol=\"JMS\"] { color: var(--protocol-jms);");
    }

    @Test
    void queryConditionsDoNotBorrowTheDeploymentAccent() throws IOException {
        assertThat(text("style.css"))
                .contains(".cond-tag.query .cond-label { background: color-mix(in srgb, var(--method-post) 18%, transparent); color: var(--method-post) }");
    }

    @Test
    void hoverRevealedRowActionsStayReachableByKeyboardAndTouch() throws IOException {
        String css = text("style.css");
        int start = css.indexOf("@media (hover: hover) and (min-width: 1281px) {");
        assertThat(start).isPositive();
        String block = css.substring(start, css.indexOf("\n}\n", start));

        assertThat(block)
                .contains(":focus-within .rule-row-actions > *")
                .contains(".rule-row-more[open]")
                .contains(".rule-row-disclosure[aria-expanded=\"true\"]");
    }

    private static String text(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
