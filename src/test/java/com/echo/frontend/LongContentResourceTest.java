package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抽屜裡的長內容：標題與描述最多兩行可展開、程式碼區塊有高度上限可展開、
 * 超大內容只先渲染開頭、條件／標籤／引用規則先顯示前幾筆。行為邏輯由 long-content.test.cjs 驗證。
 */
class LongContentResourceTest {
    @Test
    void drawerHeaderClampsLongTitlesAndOffersTheFullText() throws IOException {
        assertThat(text("components/UiDetailDrawer.js"))
                .contains(":class=\"{'is-clamped': !headerExpanded}\" :title=\"headerClamped ? title : null\"")
                .contains("<button v-if=\"headerClamped\" type=\"button\" class=\"ui-detail-drawer__more\" :aria-expanded=\"headerExpanded ? 'true' : 'false'\"")
                .contains("const overflows = el => !!el && el.scrollHeight > el.clientHeight + 1;");
        assertThat(text("console.css"))
                .contains(".ui-detail-drawer__subtitle.is-clamped { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden }");
    }

    @Test
    void longBodiesStayInTheirBoxAndRenderOnlyTheirBeginningUntilAsked() throws IOException {
        assertThat(text("utils.js"))
                .contains("const BODY_PREVIEW_CHARS = 64 * 1024;")
                .contains("const DETAIL_LIST_PREVIEW = { conditions: 6, links: 8, tags: 8 };");
        for (String drawer : new String[]{"RuleDetail", "ResponseDetail"}) {
            assertThat(text("components/" + drawer + ".js")).as(drawer)
                    .contains("return !this.showFullBody && this.bodyFull.length > BODY_PREVIEW_CHARS;")
                    .contains("class=\"detail-code\" :class=\"{'is-expanded': bodyExpanded}\"")
                    .contains("@click=\"showFullBody = true\">{{t('common.showFullContent')}}</button>")
                    .contains(":aria-pressed=\"bodyExpanded ? 'true' : 'false'\"");
        }
        assertThat(text("console.css"))
                .contains(".detail-code.is-expanded { max-height: none }")
                .contains(".audit-detail-drawer .audit-raw { max-height: 320px; overflow: auto }")
                .contains(".audit-detail-drawer .ac-block-diff { flex-direction: column }")
                .contains(".log-detail-drawer .log-inspector-pane > .pv-pre { max-height: 420px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere }");
        // Formatted log bodies get a definite height so the editor only renders the lines in view.
        assertThat(text("components/StatsPage.js"))
                .contains("const height = Math.min(420, Math.ceil(cm.defaultTextHeight() * cm.lineCount()) + 12);")
                .contains("this.fitCodeMirror(this.cmInstances[refKey], el);");
    }

    @Test
    void longListsShowTheFirstFewWithAShowAllToggle() throws IOException {
        String rule = text("components/RuleDetail.js");
        assertThat(rule)
                .contains("<template v-for=\"group in visibleConditionGroups\" :key=\"group.type\">")
                .contains("v-for=\"([k, v]) in visibleTagEntries\"")
                .contains("t('common.showAllCount', {count: conditionCount})");
        assertThat(text("components/ResponseDetail.js"))
                .contains("<li v-for=\"rule in visibleLinkedRules\" :key=\"rule.id\">")
                .contains("t('common.showAllCount', {count: linkedRules.length})");
        assertThat(text("console.css"))
                .contains(".detail-chips .ui-badge { max-width: 100%; white-space: normal; overflow-wrap: anywhere;")
                .contains(".detail-link > .rule-method { flex: none; width: 52px }");
    }

    private static String text(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
