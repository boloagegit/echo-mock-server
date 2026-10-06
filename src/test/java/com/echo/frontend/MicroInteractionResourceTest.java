package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 小細節：側欄選取指示平滑移動（只動 transform、尊重減少動態）、切頁保留列表捲動位置、
 * 主要搜尋欄顯示「/」快捷鍵提示。
 */
class MicroInteractionResourceTest {
    @Test
    void sidebarHighlightSlidesBetweenItemsWithTransformOnly() throws IOException {
        String sidebar = text("components/SidebarNav.js");
        String console = text("console.css");

        assertThat(sidebar)
                .contains("<span v-if=\"indicator\" class=\"nav-indicator\" :class=\"{'is-ready': indicatorReady}\" :style=\"indicator\" aria-hidden=\"true\"></span>")
                .contains("this.navObserver = new ResizeObserver(() => this.placeIndicator());")
                .contains("this.navObserver?.disconnect();");
        assertThat(console)
                .contains("transform: translate(var(--nav-indicator-x), var(--nav-indicator-y));")
                .contains(".nav-indicator.is-ready { transition: transform var(--motion-base) var(--ease-standard)")
                .contains("@media (prefers-reduced-motion: reduce) { .nav-indicator.is-ready { transition: none } }");
    }

    @Test
    void returningToAPageRestoresItsListScrollPosition() throws IOException {
        assertThat(text("app.js"))
                .contains("const pageScroller = () => document.querySelector('.page.active .card-table-body, .page.active .page-scroll');")
                .contains("if (scroller && from) { pageScroll[from] = scroller.scrollTop; }\n        }, { flush: 'pre' });")
                .contains("if (scroller && pageScroll[to]) { scroller.scrollTop = pageScroll[to]; }\n        }, { flush: 'post' });");
    }

    @Test
    void primarySearchFieldsShowTheirShortcut() throws IOException {
        assertThat(text("components/WorkspaceSearchField.js"))
                .contains("<kbd v-if=\"shortcut && !draftValue\" class=\"workspace-search-kbd\" aria-hidden=\"true\">{{shortcut}}</kbd>");
        assertThat(text("style.css")).contains(".workspace-search-input:focus-within .workspace-search-kbd { opacity: 0 }");
        for (String page : new String[]{"RulesPage", "ResponsesPage", "StatsPage", "AuditPage", "AccountsPage", "IssuesPage"}) {
            String source = text("components/" + page + ".js");
            // Only the first (primary) search on each page carries the hint, matching what "/" focuses.
            assertThat(source.indexOf("<workspace-search-field shortcut=\"/\"")).as(page)
                    .isEqualTo(source.indexOf("<workspace-search-field"));
        }
    }

    private static String text(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
