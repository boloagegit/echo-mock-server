package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Echo 自有圖示：導覽與帳號動作使用同一套線條圖示（24 格線、1.75 線寬），
 * 與 App 標誌一致；圖示僅裝飾，名稱由文字或 aria-label 提供。
 */
class UiIconResourceTest {
    @Test
    void navigationAndAccountActionsUseTheEchoIconSet() throws IOException {
        String icon = text("components/UiIcon.js");
        String sidebar = text("components/SidebarNav.js");

        assertThat(icon)
                .contains("<svg class=\"ui-icon\" viewBox=\"0 0 24 24\" aria-hidden=\"true\" focusable=\"false\"")
                .contains("validator: value => value in UI_ICON_PATHS");
        for (String name : new String[]{"rules", "responses", "logs", "audit", "issues", "accounts", "settings", "help", "key", "logout", "login"}) {
            assertThat(icon).as(name).contains("  " + name + ": '");
            assertThat(sidebar).as(name).contains("<ui-icon name=\"" + name + "\"></ui-icon>");
        }
        assertThat(sidebar).doesNotContain("bi-list-ul").doesNotContain("bi-clock-history").doesNotContain("bi-sliders");
        assertThat(text("console.css"))
                .contains(".ui-icon { width: 18px; height: 18px; flex: none; fill: none; stroke: currentColor; stroke-width: 1.75; stroke-linecap: round; stroke-linejoin: round }")
                .contains(".nav-item.active .ui-icon { color: var(--primary) }");
        assertThat(text("app.js")).contains("_app.component('ui-icon', UiIcon);");
        assertThat(text("index.html").indexOf("/components/UiIcon.js?v="))
                .isPositive().isLessThan(text("index.html").indexOf("/app.js?v="));
    }

    private static String text(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
