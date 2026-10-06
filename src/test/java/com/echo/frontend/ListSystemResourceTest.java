package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 共用列表系統：已遷移的列表頁必須使用同一套列（data-table）、詳情抽屜、列選單與工具列，
 * 讓點選、鍵盤與操作位置在每一頁都一致。遷移一頁就把它加進 MIGRATED_LISTS。
 */
class ListSystemResourceTest {
    /** page component, its row/table component (same file when the page renders rows itself), has batch selection. */
    private static final String[][] MIGRATED_LISTS = {
            {"RulesPage", "RuleTable", "batch"},
            {"ResponsesPage", "ResponsesPage", "batch"},
            {"StatsPage", "StatsPage", ""},
            {"AuditPage", "AuditPage", ""},
    };

    @Test
    void migratedListsUseOneRowAndDrawerLanguage() throws IOException {
        for (String[] list : MIGRATED_LISTS) {
            String page = text("components/" + list[0] + ".js");
            String rows = text("components/" + list[1] + ".js");

            assertThat(rows).as(list[1] + " rows")
                    .contains("class=\"data-table")
                    .contains("data-detail-row tabindex=\"0\"")
                    .contains(":aria-selected=")
                    .contains("<ui-row-menu")
                    .contains("@click.stop @dblclick.stop");
            assertThat(page).as(list[0] + " toolbar and drawer")
                    .contains("<div class=\"list-toolbar\">\n")
                    .contains(":submit-mode=\"true\"")
                    .contains("@prev=\"stepDetail(-1)\" @next=\"stepDetail(1)\"")
                    .doesNotContain("workspace-filter-card")
                    .doesNotContain("colspan");
            int toolbar = page.indexOf("<div class=\"list-toolbar\">");
            assertThat(page.indexOf("<workspace-search-field", toolbar))
                    .as(list[0] + " search comes first in DOM and tab order")
                    .isLessThan(page.indexOf("<ui-toggle-group", toolbar));
        }
    }

    @Test
    void batchActionsLiveInABarInsteadOfShiftingTheHeader() throws IOException {
        for (String[] list : MIGRATED_LISTS) {
            if (!"batch".equals(list[2])) { continue; }
            String page = text("components/" + list[0] + ".js");
            int header = page.indexOf("<div class=\"page-actions\">");
            String headerActions = page.substring(header, page.indexOf("</div>", header));
            assertThat(headerActions).as(list[0] + " header").doesNotContain("variant=\"danger\"");
            assertThat(page).as(list[0] + " batch bar").contains("class=\"batch-bar\" role=\"region\"");
        }
    }

    @Test
    void pageSwitchKeepsTheHeaderStillAndOnlyEasesContentIn() throws IOException {
        String style = text("style.css");
        String console = text("console.css");

        assertThat(style).contains(".page.active { display: flex; flex-direction: column; flex: 1; min-height: 0; overflow: hidden }");
        assertThat(console)
                .contains(".page.active > .list-card,")
                .contains("@keyframes pageContentIn { from { opacity: 0.4; transform: translateY(3px) }")
                .doesNotContain(".page.active > .page-header {");
        // Notes that used to push the toolbar down now live in a header tooltip.
        assertThat(text("components/ResponsesPage.js"))
                .contains(":data-tooltip=\"t('responses.sharedInfo')\"")
                .doesNotContain("page-subtitle");
    }

    @Test
    void auditLoadFailuresAreNotShownAsAnEmptyHistory() throws IOException {
        assertThat(text("composables/useAudit.js")).contains("deps.loading.value.auditError = !(r && r.ok);");
        assertThat(text("components/AuditPage.js"))
                .contains("<ui-load-state v-if=\"loading.auditError && !loading.audit\" kind=\"error\"")
                .contains("t('audit.emptyFilterResult')");
    }

    private static String text(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
