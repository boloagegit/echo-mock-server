package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signal Console 列表契約：方法色碼、條件色彩、列操作與詳情抽屜的鍵盤行為。
 */
class SignalConsoleListResourceTest {
    @Test
    void ruleRowsExposeMethodAndProtocolForColourCoding() throws IOException {
        assertThat(text("components/RuleTable.js"))
                .contains("class=\"rule-method\" :data-method=\"r.protocol==='HTTP' ? (r.method || 'GET') : null\" :data-protocol=\"r.protocol\"");
    }

    @Test
    void everyHttpMethodHasItsOwnColourTokenInBothThemes() throws IOException {
        String console = text("console.css");
        String theme = text("theme.css");

        for (String method : new String[]{"GET", "POST", "PUT", "PATCH", "DELETE"}) {
            String token = "--method-" + method.toLowerCase();
            assertThat(console)
                    .contains(".rule-method[data-method=\"" + method + "\"] { color: var(" + token + ") }")
                    .contains(".ui-badge.badge-method[data-method=\"" + method + "\"] { color: var(" + token + ") }");
            assertThat(theme.split(token + ":", -1)).as(token).hasSize(3);
        }
        assertThat(console).contains(".rule-method[data-protocol=\"JMS\"] { color: var(--protocol-jms) }");
    }

    @Test
    void queryConditionsDoNotBorrowTheDeploymentAccent() throws IOException {
        assertThat(text("console.css"))
                .contains(".cond-chip[data-kind=\"query\"] { --cond-color: var(--method-post) }")
                .contains(".cond-chip[data-kind=\"body\"] { --cond-color: var(--warning) }")
                .contains(".cond-chip[data-kind=\"header\"] { --cond-color: var(--success) }");
    }

    @Test
    void rowActionsStayVisibleAndNeverTriggerTheRowItself() throws IOException {
        String console = text("console.css");

        // Actions are always visible (quiet), so touch and keyboard users never depend on hover.
        assertThat(console).doesNotContain("@media (hover: hover) and (min-width: 1281px)");
        assertThat(text("components/RuleTable.js"))
                .contains("<td class=\"col-toggle\" @click.stop @dblclick.stop>")
                .contains("<td class=\"col-actions\" @click.stop @dblclick.stop>");
        assertThat(text("components/UiDropdownMenu.js"))
                .contains("@click.stop @dblclick.stop")
                .contains("'is-danger': item.danger");
    }

    @Test
    void drawerKeyboardLeavesFieldsAndDialogsAlone() throws IOException {
        String drawer = text("components/UiDetailDrawer.js");

        assertThat(drawer)
                .contains("const modalOpen = () => !!document.querySelector('.modal-overlay, [aria-modal=\"true\"]');")
                .contains("if (event.key === 'Escape' && !isTyping(document.activeElement)) {")
                .contains("(event.key === 'ArrowDown' || event.key === 'j') && props.hasNext")
                .contains("(event.key === 'ArrowUp' || event.key === 'k') && props.hasPrev")
                .contains("document.addEventListener('keydown', onKeydown, true)")
                .contains("document.querySelector('[data-detail-row].is-selected')");
    }

    @Test
    void rowSelectionIsImmediateWithoutADoubleClickDelay() throws IOException {
        assertThat(text("composables/useRules.js"))
                .doesNotContain("ruleClickTimer")
                .doesNotContain("setTimeout(() => {\n                ruleClickTimer = null;")
                .contains("const openRuleDetail = rule => {");
        assertThat(text("components/RuleTable.js"))
                .contains("@click=\"batchSelectMode ? $emit('toggle-selection', r.id) : $emit('select', r)\"");
    }

    private static String text(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
