package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceTableResourceTest {

    @Test
    void distinguishesRoutineMetadataFromRetentionWarningsInBothRuleViews() throws IOException {
        for (String path : new String[]{"static/components/RulesPage.js", "static/components/RuleGroupRow.js"}) {
            assertThat(resourceText(path)).contains("<= 7\" class=\"badge badge-warning\"")
                    .contains("t('rules.pvDaysLeft')")
                    .contains("class=\"table-metadata\"");
        }
        assertThat(resourceText("static/components/ResponsesPage.js"))
                .contains("v-if=\"r.usageCount\" class=\"table-metadata\"");
    }

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
                .contains(".col-priority { width: 64px; text-align: center; white-space: nowrap }")
                .contains("td.col-datetime .sub-info")
                .contains("text-overflow: clip")
                .contains(".table-date-stack");
        assertThat(rules).contains("class=\"col-datetime col-hide-md\"").contains("class=\"table-date-stack\"");
        assertThat(groupedRules).contains("class=\"col-datetime col-hide-md\"").contains("class=\"table-date-stack\"");
        assertThat(responses).contains("class=\"col-datetime col-hide-md\"");
        assertThat(audit).contains("class=\"col-datetime\"");
    }

    @Test
    void scrollableRuleAndResponseTablesExposeAnAccurateScrollHint() throws IOException {
        String pagination = resourceText("static/components/WorkspacePagination.js");
        String rules = resourceText("static/components/RulesPage.js");
        String responses = resourceText("static/components/ResponsesPage.js");
        String zhTw = resourceText("static/i18n/zh-TW.json");
        String english = resourceText("static/i18n/en.json");

        assertThat(pagination)
                .contains("body.scrollHeight > body.clientHeight + 1")
                .contains("remaining > 1")
                .contains("class=\"workspace-scroll-hint\"")
                .contains("if (overflowing && this.scrollRegionLabel)")
                .contains("body.setAttribute('role', 'region')")
                .contains("body.focus({ preventScroll: true })")
                .contains("clearScrollAccessibility(body)")
                .contains("this.scrollResizeObserver?.disconnect()")
                .contains("this.scrollMutationObserver?.disconnect()");
        assertThat(rules).contains(":scroll-hint-label=\"t('common.scrollForMore')\"");
        assertThat(rules).contains(":scroll-region-label=\"t('common.scrollableRulesTable')\"");
        assertThat(responses).contains(":scroll-hint-label=\"t('common.scrollForMore')\"");
        assertThat(responses).contains(":scroll-region-label=\"t('common.scrollableResponsesTable')\"");
        assertThat(zhTw).contains("\"scrollForMore\": \"向下捲動\"");
        assertThat(english).contains("\"scrollForMore\": \"Scroll for more\"");
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
