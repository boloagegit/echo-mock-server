package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceTableResourceTest {

    @Test
    void actionColumnsReserveButtonsGapsAndDensityAwarePadding() throws IOException {
        String stylesheet = resourceText("static/style.css");

        assertThat(stylesheet)
                .contains(".table-fixed td.col-actions { overflow: visible; text-overflow: clip }")
                .contains(".col-actions-1 { width: max(72px, calc(32px + var(--cell-px) + var(--cell-px))) }")
                .contains(".col-actions-2 { width: calc(64px + var(--space-xs) + var(--cell-px) + var(--cell-px)) }")
                .contains(".col-actions-3 { width: calc(96px + var(--space-xs) + var(--space-xs) + var(--cell-px) + var(--cell-px)) }")
                .doesNotContain(".logs-table .col-actions-2 {");
    }

    @Test
    void dateColumnsKeepShortTimestampsAndRetentionLabelsVisible() throws IOException {
        String stylesheet = resourceText("static/style.css");
        String rules = resourceText("static/components/RulesPage.js");
        String groupedRules = resourceText("static/components/RuleGroupRow.js");
        String responses = resourceText("static/components/ResponsesPage.js");
        String audit = resourceText("static/components/AuditPage.js");

        assertThat(stylesheet)
                .contains(".col-datetime { width: 132px; font-variant-numeric: tabular-nums }")
                .contains(".col-priority { width: 88px; text-align: center; white-space: nowrap }")
                .contains("td.col-datetime .sub-info")
                .contains("text-overflow: clip")
                .contains(".table-date-stack");
        assertThat(rules).contains("class=\"col-datetime col-hide-md\"").contains("class=\"table-date-stack\"");
        assertThat(groupedRules).contains("class=\"col-datetime col-hide-md\"").contains("class=\"table-date-stack\"");
        assertThat(responses).contains("class=\"col-datetime col-hide-md\"");
        assertThat(audit).contains("class=\"col-datetime\"");
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
