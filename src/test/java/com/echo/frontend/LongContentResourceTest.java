package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抽屜裡的長內容：標題與描述最多兩行可展開、長內容在共用程式碼檢視器裡完整呈現（只繪製可見行、
 * 全文搜尋）、區段可收合且標題固定、條件／標籤／引用規則先顯示前幾筆、點外部收合、可加寬。
 * 行為邏輯由 long-content.test.cjs 驗證。
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
    void longBodiesOpenInTheSharedCodeViewerWithoutTruncation() throws IOException {
        String viewer = text("components/UiCodeViewer.js");

        // CodeMirror draws only the lines in view, so the full body is shown and searched.
        assertThat(viewer)
                .contains("this.cm = CodeMirror(this.$refs.host, {")
                .contains("viewportMargin: 20,")
                .contains("const limit = wide ? Math.max(this.maxHeight, window.innerHeight - 320) : this.maxHeight;")
                .contains("this.matches.slice(0, CODE_VIEWER_MARK_LIMIT)")
                .contains("@keydown.enter.prevent=\"step($event.shiftKey ? -1 : 1)\"")
                .contains(":aria-pressed=\"wrap ? 'true' : 'false'\"");
        assertThat(text("utils.js")).doesNotContain("BODY_PREVIEW_CHARS");
        for (String drawer : new String[]{"RuleDetail", "ResponseDetail"}) {
            assertThat(text("components/" + drawer + ".js")).as(drawer)
                    .contains("<ui-code-viewer v-else-if=\"body\" :key=\"view.id\" :value=\"body\"")
                    .doesNotContain("showFullBody")
                    .doesNotContain("class=\"detail-code\"");
        }
        assertThat(text("console.css"))
                .contains(".ui-code-viewer { border: 1px solid var(--border); border-radius: var(--radius-panel); background: var(--code-bg); overflow: clip }")
                .contains(".ui-code-viewer__toolbar { position: sticky;")
                .contains(".audit-detail-drawer .audit-raw { max-height: 320px; overflow: auto }")
                .contains(".audit-detail-drawer .ac-block-diff { flex-direction: column }")
                .contains(".log-detail-drawer .log-inspector-pane > .pv-pre { max-height: 420px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere }");
        assertThat(text("components/StatsPage.js"))
                .contains("const height = Math.min(420, Math.ceil(cm.defaultTextHeight() * cm.lineCount()) + 12);");
        assertThat(text("index.html").indexOf("/components/UiCodeViewer.js?v="))
                .isPositive().isLessThan(text("index.html").indexOf("/app.js?v="));
    }

    @Test
    void drawerSectionsCollapseKeepTheirHeadingInViewAndSummariseThemselves() throws IOException {
        assertThat(text("components/UiDetailSection.js"))
                .contains("<h3 class=\"detail-section__title\">")
                .contains(":aria-expanded=\"open ? 'true' : 'false'\" :aria-controls=\"bodyId\"")
                .contains("const DETAIL_SECTION_KEY = 'echo.drawerSections';");
        for (String drawer : new String[]{"RuleDetail", "ResponseDetail", "AuditPage", "AccountsPage", "IssuesPage"}) {
            assertThat(text("components/" + drawer + ".js")).as(drawer).contains("<ui-detail-section");
        }
        assertThat(text("console.css"))
                .contains(".ui-detail-section__head { --detail-section-head-h: 38px; position: sticky; top: -4px;");
    }

    @Test
    void drawerClosesOnAnOutsideClickAndCanWidenForLongContent() throws IOException {
        String drawer = text("components/UiDetailDrawer.js");

        assertThat(drawer)
                .contains("const KEEP_OPEN = '[data-detail-row], .ui-detail-drawer, .modal-overlay, [aria-modal=\"true\"], .ui-dropdown-menu, .toast-wrap, .user-menu, .tour-overlay';")
                .contains("document.addEventListener('pointerdown', onPointerDown, true);")
                .contains("document.removeEventListener('pointerdown', onPointerDown, true);")
                // Closing by a click elsewhere leaves focus where the person clicked.
                .contains("if (closedByPointer) {")
                .contains("const WIDE_KEY = 'echo.drawerWide';")
                .contains("class=\"ui-detail-drawer__widen\" :aria-pressed=\"wide ? 'true' : 'false'\"");
        assertThat(text("console.css"))
                .contains(".ui-detail-drawer.is-wide { width: min(var(--drawer-wide-w, 960px), calc(100vw - 280px)) }")
                .contains(".ui-detail-drawer.log-detail-drawer { --drawer-w: 760px; --drawer-wide-w: 1120px }");
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
