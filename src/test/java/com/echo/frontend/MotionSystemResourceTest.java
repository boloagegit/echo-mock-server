package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MotionSystemResourceTest {

    @Test
    void loadsAndRegistersSharedMotionPrimitivesBeforeTheApplication() throws IOException {
        String index = resourceText("static/index.html");
        String application = resourceText("static/app.js");

        assertThat(index)
                .contains("/components/UiModalTransition.js?v=20260909.1")
                .contains("/components/UiMotionIcon.js?v=20260909.1");
        assertThat(index.indexOf("/components/UiModalTransition.js"))
                .isLessThan(index.indexOf("/app.js"));
        assertThat(application)
                .contains("_app.component('ui-modal-transition', UiModalTransition)")
                .contains("_app.component('ui-motion-icon', UiMotionIcon)");
    }

    @Test
    void usesSharedTransitionsForModalModeAndExpandedRowContextChanges() throws IOException {
        for (String modal : new String[]{
                "ImportModal", "ConfirmModal", "PriorityHelpModal", "ResponseEditModal",
                "OpenApiPreviewModal", "RuleEditModal", "ChangePasswordModal",
                "AccountsPage", "IssuesPage", "SettingsPage"}) {
            assertThat(resourceText("static/components/" + modal + ".js"))
                    .contains("<ui-modal-transition>")
                    .contains("</ui-modal-transition>");
        }
        assertThat(resourceText("static/components/RuleEditModal.js"))
                .contains("<Transition name=\"ui-mode-panel-motion\" mode=\"out-in\">");
        for (String list : new String[]{
                "RulesPage", "RuleGroupRow", "ResponsesPage", "AuditPage", "IssuesPage", "StatsPage"}) {
            assertThat(resourceText("static/components/" + list + ".js"))
                    .contains("<Transition name=\"ui-detail-row-motion\">");
        }
    }

    @Test
    void keepsStatusIconGeometryStableAndHonorsReducedMotion() throws IOException {
        String icon = resourceText("static/components/UiMotionIcon.js");
        String stylesheet = resourceText("static/style.css");

        assertThat(icon)
                .contains("class=\"ui-motion-icon\"")
                .contains("<Transition name=\"ui-context-icon\">")
                .contains(":key=\"icon\"");
        assertThat(stylesheet)
                .contains(".ui-motion-icon {")
                .contains("width: 16px;")
                .contains("height: 16px;")
                .contains(".ui-modal-motion-enter-active")
                .contains(".ui-mode-panel-motion-enter-active")
                .contains(".ui-detail-row-motion-enter-active")
                .contains("@media (prefers-reduced-motion: reduce)")
                .contains("transition-duration: 0.01ms !important")
                .doesNotContain("transition: all");
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
