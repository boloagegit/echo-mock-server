package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class SparseStateResourceTest {
    @Test
    void bothRulePreviewsUseInlineConditionSummaryAndCompactEmptyBody() throws IOException {
        for (String page : new String[]{"RulesPage", "RuleGroupRow"}) {
            String source = text("components/" + page + ".js");
            assertThat(source).contains("class=\"pv-section-summary\"")
                    .contains("class=\"pv-body-empty\"")
                    .contains("bodyCondition && !rulePreviewCache[")
                    .contains(".tags)||{}).length")
                    .doesNotContain("<span class=\"pv-label\"></span>");
        }
    }

    @Test
    void editorDistinguishesNoConditionsAndNoSelectedResponse() throws IOException {
        assertThat(text("components/RuleEditModal.js"))
                .contains("class=\"cond-builder-actions\"")
                .contains("v-if=\"!conditions.length\" class=\"sub-info\"")
                .contains("v-if=\"form.responseId\" class=\"response-picker-heading\"")
                .contains("v-show=\"previewResponseBody.length || previewEditing\"")
                .contains("$emit('add-condition')");
        assertThat(text("style.css"))
                .contains(".rule-editor .response-preview-empty { flex: 0 0 auto; min-height: 0;")
                .contains(".cond-builder-actions.is-empty");
    }

    @Test
    void settingsNotesNeverReserveAnEmptyLabelColumn() throws IOException {
        assertThat(text("components/SettingsPage.js"))
                .doesNotContain("<span class=\"settings-label\"></span>")
                .contains("class=\"settings-inline-note\"")
                .contains("<div v-if=\"jmsTargets.length\"><span class=\"connection-guidance-label\">");
    }

    @Test
    void unusedResponsesKeepNeutralMetadataAndExplicitExpiryWarning() throws IOException {
        assertThat(text("style.css")).doesNotContain("tr.unused-row .sub-info { color: var(--danger)")
                .doesNotContain("tr.unused-row td { background: rgba(var(--danger-rgb)");
        assertThat(text("components/ResponsesPage.js"))
                .contains("class=\"response-usage-state is-unused\"")
                .contains("t('responses.orphanDaysLeft'");
    }

    @Test
    void emptyLogBodyUsesTopAlignedMessageWithoutFullHeightInsetPanel() throws IOException {
        assertThat(text("components/StatsPage.js"))
                .contains("class=\"pv-body-empty\">{{t('stats.emptyRequestBody')}}")
                .contains("class=\"pv-body-empty\">{{t('stats.emptyResponseBody')}}");
        String css = text("style.css");
        int start = css.indexOf(".pv-body-empty {");
        String rule = css.substring(start, css.indexOf('}', start));
        assertThat(rule).contains("padding: var(--space-sm) 0")
                .doesNotContain("background:")
                .doesNotContain("min-height:");
    }

    private static String text(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
