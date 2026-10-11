package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceTableResourceTest {

    @Test
    void distinguishesRoutineMetadataFromRetentionWarningsInBothRuleViews() throws IOException {
        // Rows flag only rules that expire within a week; the drawer always shows the remaining days.
        assertThat(resourceText("static/components/RuleTable.js"))
                .contains("retentionDays(r) != null && retentionDays(r) <= 7\" tone=\"warning\"");
        assertThat(resourceText("static/components/RuleDetail.js"))
                .contains("t('rules.pvDaysLeft')")
                .contains(":tone=\"retention <= 7 ? 'warning' : 'neutral'\"");
        assertThat(resourceText("static/components/ResponsesPage.js")).contains("class=\"usage-count\"");
        assertThat(resourceText("static/components/ResponseDetail.js")).contains("t('responses.linkedRulesTitle')");
    }

    @Test
    void actionColumnsReserveButtonsGapsAndDensityAwarePadding() throws IOException {
        String stylesheet = resourceText("static/style.css");

        // Every list's action column holds quiet buttons and the row menu without clipping them.
        assertThat(resourceText("static/console.css"))
                .contains(".data-table td.col-actions { width: 104px; overflow: visible; text-align: end }")
                .contains(".row-actions { display: inline-flex; align-items: center; justify-content: flex-end; gap: 2px }")
                .contains(".audit-table .col-actions { width: 56px }")
                .contains(".log-table .col-actions { width: 56px }");
        assertThat(stylesheet).doesNotContain(".col-actions-1 {").doesNotContain(".logs-table");
    }

    @Test
    void dateColumnsKeepShortTimestampsAndRetentionLabelsVisible() throws IOException {
        String stylesheet = resourceText("static/style.css");
        String rules = resourceText("static/components/RuleTable.js");
        String responses = resourceText("static/components/ResponsesPage.js");
        String audit = resourceText("static/components/AuditPage.js");

        // Timestamps are short mono cells; the full time is on hover.
        assertThat(resourceText("static/console.css"))
                .contains(".data-table .cell-mono { font-family: var(--font-mono); font-size: var(--font-xs) }")
                .contains(".log-table .col-time { width: 132px }")
                .contains(".audit-table .col-time { width: 124px }");
        assertThat(stylesheet).doesNotContain(".table-date-stack");
        // Rule rows keep the short timestamp visible and expose the full time and operator on hover.
        assertThat(rules).contains("class=\"col-updated cell-mono cell-subtle\" :title=\"fmtTime(r.updatedAt,false)")
                .contains("{{fmtTime(r.updatedAt)}}");
        assertThat(responses).contains("class=\"col-updated cell-mono cell-subtle\" :title=\"fmtTime(r.updatedAt,false)\"");
        assertThat(audit).contains("class=\"col-time cell-mono cell-subtle\" :title=\"fmtTime(log.timestamp,false)\">{{fmtTime(log.timestamp)}}</td>");
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

    @Test
    void ruleColumnsCollapseByAvailableWidthInsteadOfSpanningPreviewRows() throws IOException {
        String rules = resourceText("static/components/RulesPage.js");
        String table = resourceText("static/components/RuleTable.js");
        String stylesheet = resourceText("static/style.css");
        String console = resourceText("static/console.css");

        // Details live in the drawer, so there are no preview rows whose colspan must track visible columns.
        assertThat(rules).doesNotContain("colspan").doesNotContain("syncRuleViewportWidth");
        assertThat(table).doesNotContain("colspan");
        assertThat(stylesheet).contains("container-type: inline-size");
        assertThat(console)
                .contains("@container (max-width: 980px) {\n    .rule-table .col-updated { display: none }")
                .contains("@container (max-width: 860px) {\n    .rule-table .col-cond { display: none }")
                .contains("@container (max-width: 680px) {\n    .rule-table .col-priority { display: none }");
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
