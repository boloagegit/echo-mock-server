package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快速載入不閃爍：讀取指示等到明顯需要等待才淡入、忙碌中的按鈕不變暗、
 * 抽屜在下一筆資料到齊前保留目前內容。行為由 loading-hold.test.cjs 驗證，這裡確認每個畫面都接上。
 */
class LoadingFeedbackResourceTest {
    @Test
    void loadingPlaceholdersOnlyAppearWhenTheWaitIsNoticeable() throws IOException {
        assertThat(text("console.css"))
                .contains(".top-loader,\n.list-skeleton,\n.loading-reveal { animation: loadingReveal 160ms var(--ease-standard) 300ms both }")
                .contains("@keyframes loadingReveal { from { opacity: 0 } to { opacity: 1 } }");
        assertThat(text("components/UiDetailDrawer.js"))
                .contains("<div v-if=\"loading\" class=\"ui-detail-drawer__state loading-reveal\" role=\"status\">");
        for (String component : new String[]{"RulesPage", "RuleApplyModal", "RuleEditModal",
                "ResourceMonitoringPanel", "SettingsPage", "StatsPage"}) {
            assertThat(text("components/" + component + ".js")).as(component).contains("loading-reveal");
        }
        for (String list : new String[]{"RulesPage", "ResponsesPage", "StatsPage", "AuditPage", "AccountsPage", "IssuesPage"}) {
            assertThat(text("components/" + list + ".js")).as(list + " skeleton").contains("class=\"list-skeleton\" role=\"status\"");
        }
    }

    @Test
    void busyButtonsKeepTheirStrengthAndOnlyTurnOnALongWait() throws IOException {
        assertThat(text("console.css"))
                .contains(".ui-button:disabled:has(.spin) { opacity: 1 }")
                .contains(".ui-button .spin { animation-delay: 150ms }");
    }

    @Test
    void everyDetailDrawerHoldsTheCurrentRecordUntilTheNextIsReady() throws IOException {
        assertThat(text("utils.js"))
                .contains("const DETAIL_HOLD_MS = 300;")
                .contains("const heldDetailMixin = source => ({");
        for (String drawer : new String[]{"RuleDetail", "ResponseDetail", "AuditPage", "StatsPage"}) {
            assertThat(text("components/" + drawer + ".js")).as(drawer)
                    .contains("mixins: [heldDetailMixin(function () {")
                    .contains(":open=\"held.open\"")
                    .contains(":stale=\"held.stale\"");
        }
        assertThat(text("components/UiDetailDrawer.js"))
                .contains(":class=\"{'is-stale': stale}\"")
                .contains(":aria-busy=\"stale ? 'true' : null\"");
        assertThat(text("console.css"))
                .contains(".ui-detail-drawer.is-stale .ui-detail-drawer__body { opacity: 0.55; transition-delay: 300ms }");
    }

    private static String text(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
