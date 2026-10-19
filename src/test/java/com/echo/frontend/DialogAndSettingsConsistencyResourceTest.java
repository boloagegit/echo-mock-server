package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings page and dialog box follow the language of the list page: the title bar is aligned, the tab position is the same as the toolbar, and there are only secondary actions in the card.
 * The dialog box has no decorative icon, cancel it to neutral, and the form can be saved. Ctrl／⌘+Enter will not be closed by clicking on the background.
 */
class DialogAndSettingsConsistencyResourceTest {
    @Test
    void settingsTabsSitWhereEveryOtherPageHasItsToolbar() throws IOException {
        String settings = text("components/SettingsPage.js");
        int header = settings.indexOf("<div class=\"page-header\">");
        int headerEnd = settings.indexOf("<div class=\"page-scroll\">", header);

        // Version, uptime and port live in the header, so nothing pushes the tabs down.
        assertThat(settings.substring(header, headerEnd))
                .contains("class=\"page-meta settings-context\"")
                .contains("<div class=\"page-actions\">")
                .contains(":title=\"t('settings.manualRefreshHint')\"");
        assertThat(settings.indexOf("<ui-tabs class=\"settings-tabs\"", headerEnd))
                .isGreaterThan(headerEnd)
                .isLessThan(settings.indexOf("<section id=\"settings-content\"", headerEnd));
        assertThat(settings).doesNotContain("settings-manual-note");
        assertThat(text("style.css"))
                .contains(".settings-tabs .ui-tabs__tab.active::after { content: '';");
    }

    @Test
    void settingsCardsUseTextHeadingsAndSecondaryActions() throws IOException {
        String settings = text("components/SettingsPage.js");

        assertThat(settings)
                .doesNotContain("<div class=\"settings-card-header\"><i class=\"bi")
                .doesNotContain("variant=\"primary\" size=\"compact\"");
        assertThat(text("components/ConnectionTargetsTable.js"))
                .contains("icon-only class=\"connection-action-edit\"")
                .contains(":aria-label=\"t('rules.edit') + ' ' + displayName(target)\"");
    }

    @Test
    void dialogsCarryNoDecorativeHeaderIconsAndShareTheFooterLanguage() throws IOException {
        for (String dialog : new String[]{"AccountsPage", "ChangePasswordModal", "ImportModal", "IssuesPage",
                "PriorityHelpModal", "OpenApiPreviewModal", "ResponseEditModal", "RuleEditModal", "SettingsPage"}) {
            assertThat(text("components/" + dialog + ".js")).as(dialog).doesNotContain("modal-heading-icon");
        }
        // The confirm dialog keeps its icon: it tells a destructive confirmation apart.
        assertThat(text("components/ConfirmModal.js")).contains("modal-heading-icon");
        assertThat(text("utils.js")).contains("const SAVE_SHORTCUT_KEY =");
        for (String dialog : new String[]{"RuleEditModal", "ResponseEditModal"}) {
            assertThat(text("components/" + dialog + ".js")).as(dialog)
                    .contains("class=\"modal-footer-hint\"><kbd>{{saveShortcutKey}}</kbd><kbd>Enter</kbd>");
        }
        assertThat(text("console.css")).contains(".modal-footer > .ui-button--quiet { color: var(--muted) }");
    }

    @Test
    void connectionFormsSaveWithTheShortcutAndIgnoreBackdropClicks() throws IOException {
        String settings = text("components/SettingsPage.js");

        assertThat(settings)
                .contains("if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {")
                .doesNotContain("@click.self=\"showHttpTargetForm=false\"")
                .doesNotContain("@click.self=\"showJmsTargetForm=false\"");
        assertThat(text("components/AccountsPage.js")).doesNotContain("@click.self=");
    }

    private static String text(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
