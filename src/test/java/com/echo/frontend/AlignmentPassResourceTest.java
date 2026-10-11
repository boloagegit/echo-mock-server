package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 對齊微調：側欄與頁首同一條中線、共用左緣、表頭與資料同邊、分頁列等高，以及手機寬的版面修正。
 * 只驗證排版契約；這些規則不改變任何操作或資料流程。
 */
class AlignmentPassResourceTest {
    @Test
    void sidebarBrandSharesThePageHeaderCentreLineAndOneLeftEdge() throws IOException {
        String console = text("console.css");

        // main padding 16px + 48px header → centre 40px; the boot shell paints the mark at the same spot.
        assertThat(console)
                .contains(".sidebar-header { min-height: 64px; padding: 16px 12px 0 22px; align-items: center }")
                .contains(".boot-shell__sidebar { padding: 24px 12px 0 22px }")
                .contains(".service-status { padding-inline: 9px }")
                .contains(".user-trigger { padding-inline: 9px }");
        assertThat(console.indexOf(".boot-shell__sidebar { padding: 24px 12px 0 22px }"))
                .as("the override follows the boot shell's base rule")
                .isGreaterThan(console.indexOf(".boot-shell__sidebar { flex: 0 0 var(--sidebar-width);"));
    }

    @Test
    void searchTakesTheRemainingToolbarWidth() throws IOException {
        assertThat(text("console.css"))
                .contains(".list-toolbar > .workspace-search-field { max-width: none }")
                .contains(".list-toolbar:has(> .list-toolbar__end) > .workspace-search-field { flex-basis: 100% }");
    }

    @Test
    void sortableHeadingsAlignWithTheirValuesAndMatchPlainHeadings() throws IOException {
        assertThat(text("console.css"))
                .contains(".data-table th .ui-table-sort-header.ui-button {\n    margin-inline: -6px 0;\n    padding-inline: 5px;\n    color: var(--text-subtle);\n    font-weight: var(--font-weight-medium);")
                .contains(".data-table th .ui-table-sort-header.ui-button.is-active { color: var(--text); font-weight: var(--font-weight-semibold) }")
                .contains(".rule-table th.col-priority .ui-table-sort-header.ui-button { margin-inline: 0 -6px }")
                .contains(".rule-table td.col-priority { width: 96px; text-align: end }")
                .contains(".log-table .col-duration { width: 108px }");
    }

    @Test
    void switchingProtocolKeepsTheRequestRowsInPlaceInNarrowerPanes() throws IOException {
        String console = text("console.css");

        // A 1280px window (or 1366px at 125%) gives the request pane under 500px; the HTTP request
        // line must stay one row there, because JMS fills the same slot with one row.
        assertThat(console)
                .contains("@container rule-left (max-width: 500px) {\n    .rule-request-line .method-group .method-btn { padding-inline: 6px }\n}")
                .contains("@container rule-left (max-width: 600px) {\n    .rule-protocol-fields .source-host-hint { min-height: 2lh }\n}")
                .contains("@container rule-left (max-width: 380px) {\n    .rule-request-line { grid-template-columns: minmax(0, 1fr) }");
        // Desktop pane headings stay on one line; the hint shortens instead of pushing the switch down.
        assertThat(console)
                .contains("    .rule-editor .rule-pane-heading { flex-wrap: nowrap }")
                .contains("    .rule-editor .rule-pane-hint { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }");
    }

    @Test
    void responseModeToolbarWrapsInsteadOfSqueezingItsLabels() throws IOException {
        assertThat(text("console.css"))
                .contains(".response-mode-toolbar { flex-wrap: wrap; row-gap: var(--space-xs) }")
                .contains(".response-mode-label,\n.response-mode-toolbar .protocol-btn { white-space: nowrap }");
    }

    @Test
    void footersAndEmptyStatesAreBalanced() throws IOException {
        assertThat(text("console.css"))
                .contains(".workspace-page-size .form-control { height: max(32px, var(--control-h-sm)); min-height: max(32px, var(--control-h-sm)) }")
                .contains(".card-table-body:has(> .ui-load-state) { display: flex; flex-direction: column }")
                .contains(".resource-overview-table td.settings-table-actions > .ui-button { margin-block: -6px; margin-inline-end: -8px }");
    }

    @Test
    void editorPanesKeepEqualSpaceAroundTheDivider() throws IOException {
        assertThat(text("console.css"))
                .contains(".rule-left { padding-inline-end: max(0px, calc(var(--editor-pane-inline) - 10px)) }")
                .contains(".rule-editor .rule-pane-heading { flex-wrap: wrap; row-gap: var(--space-sm) }")
                .contains(".ui-detail-drawer__actions .ui-row-menu { margin-inline-end: -8px }");
    }

    @Test
    void phoneWidthsKeepTheEditorHeaderSettingsAndListsReadable() throws IOException {
        String console = text("console.css");
        String phone = console.substring(console.indexOf("/* ===== Phone widths (2026-10-07) ===== */"));

        assertThat(phone)
                .contains(".rule-modal-fullscreen > .modal-header { display: grid; grid-template-columns: minmax(0, 1fr) auto auto;")
                .contains(".rule-modal-fullscreen .rule-modal-actions { display: contents }")
                .contains(".rule-modal-fullscreen .rule-modal-actions > .rule-state-controls { grid-column: 1 / -1; grid-row: 2 }")
                .contains(".resource-overview-table tr { grid-template-columns: minmax(0, 1fr) }")
                .contains("@container (max-width: 480px) {")
                .contains(".rule-table .rule-method { min-width: 0 }")
                .contains(".log-table .col-result { width: 128px }")
                // No keyboard shortcut hint on phones, so the three footer buttons stay on one row.
                .contains("@media (max-width: 640px), (hover: none) and (pointer: coarse) {\n    .rule-modal-fullscreen > .modal-footer > .modal-footer-hint { display: none }");
        // The editor's controls are only rearranged on phones, never hidden.
        assertThat(phone).doesNotContain(".close-btn { display: none }");
    }

    private static String text(String name) throws IOException {
        return new ClassPathResource("static/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
}
