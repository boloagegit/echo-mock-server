package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast loading does not flicker: the read instructions wait until it is obvious that there is a need to wait before fading in, the busy button does not dim,
 * The drawer retains the current content until the next data arrives. The behavior is verified by loading-hold.test.cjs, where every screen is confirmed to be connected.
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
                .contains(":class=\"{'is-stale': stale,")
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
