package com.echo.frontend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

class ListRefinementResourceTest {
    @Test
    void sharedElementTokensKeepControlsAndSemanticStatesConsistent() throws IOException {
        String css = text("style.css");
        String theme = text("theme.css");

        assertThat(css)
                .contains("--radius-control: 6px;")
                .contains("--radius-panel: 8px;")
                .contains("--control-h: 36px;")
                .contains("--toolbar-h: 32px;")
                .contains("--font-xs: 12px;")
                .contains("--font-sm: 13px;")
                .contains("--font-base: 14px;")
                .contains("--font-md: var(--font-base);")
                .contains("--font-lg: 22px;")
                .contains("--font-weight-regular: 400;")
                .contains("--font-weight-medium: 500;")
                .contains("--font-weight-semibold: 600;")
                .contains("--font-weight-bold: 700;")
                .contains(".workspace-row-primary {\n    min-width: 0;\n    min-height: var(--workspace-row-primary-h);\n    display: flex;\n    align-items: center;\n    line-height: 20px;")
                .contains(".btn-sm { min-height: var(--control-h-sm);")
                .contains(".btn-xs { min-height: var(--control-h-sm);")
                .contains(".btn-icon { padding: 0.3rem; width: var(--control-h); height: var(--control-h);")
                .contains(".btn-icon.btn-sm { width: max(32px, var(--control-h-sm)); height: max(32px, var(--control-h-sm));")
                .contains(".btn-xs.btn-icon { width: max(32px, var(--control-h-sm)); height: max(32px, var(--control-h-sm));")
                .contains(".workspace-pagination .pagination-controls .btn { width: max(32px, var(--control-h-sm)); height: max(32px, var(--control-h-sm)); min-height: max(32px, var(--control-h-sm));")
                .contains(".workspace-page-size .form-control {\n    width: 76px;\n    min-height: var(--control-h-sm);")
                .contains(".connection-actions {\n    display: flex;")
                .contains(".connection-action-group { display: flex;")
                .contains(".connection-action-management {\n    padding-inline-start: var(--space-md);")
                .contains("min-height: var(--control-h-sm);")
                .contains(".btn:disabled, .btn.disabled { opacity: 0.55;")
                .contains(".tag-add-inline input {\n    width: 65px;\n    min-height: var(--control-h-sm);")
                .contains(".tag-add-inline button {\n    width: max(32px, var(--control-h-sm));")
                .contains(".tag-add-inline button:focus-visible { outline: 2px solid var(--primary);")
                .contains(".help-tooltip {\n    position: relative;\n    width: 32px;\n    height: 32px;\n    flex: 0 0 32px;")
                .contains(".rule-table-hint > summary { width: 32px; height: 32px;")
                .contains(".rule-table-hint-popover > button { width: 32px; height: 32px; flex: 0 0 32px;")
                .contains(".rule-filter-bar .btn-group .btn { height: var(--toolbar-h); min-height: var(--toolbar-h);")
                .contains(".connection-form-modal .modal-close {\n    width: 32px;\n    height: 32px;")
                .contains(".connection-form-modal .modal-close:focus-visible { outline: 0;")
                .contains("th { height: 40px;")
                .contains("transition-property: background-color, border-color, color, box-shadow, transform;")
                .contains(".btn:active:not(:disabled) { transform: scale(0.96) }")
                .contains(".rule-endpoint-main code { min-width: 0; display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text); font-size: var(--font-base); font-weight: var(--font-weight-medium) }")
                .doesNotContain("font-weight: 550")
                .doesNotContain("font-weight: 650");
        assertThat(theme)
                .contains("--primary-strong: #075e53;")
                .contains("--primary-strong: #1b4799;")
                .contains("--success-strong: #145f3a;")
                .contains("--warning-strong: #684709;");
        assertThat(css)
                .contains(".cond-tag .cond-label { background: rgba(var(--warning-rgb), 0.2); color: var(--warning-strong) }")
                .contains(".cond-tag.query .cond-label { background: rgba(var(--primary-rgb), 0.2); color: var(--primary-strong) }")
                .contains(".cond-tag.header .cond-label { background: rgba(var(--success-rgb), 0.2); color: var(--success-strong) }");

        int protocolStart = css.lastIndexOf(".badge-http {");
        String protocolRule = css.substring(protocolStart, css.indexOf('}', protocolStart));
        assertThat(protocolRule)
                .contains("background: var(--surface-control)")
                .contains("color: var(--text)")
                .doesNotContain("color: var(--primary)");
    }

    @Test
    void actionButtonsUseTheSharedElementSystemPrimitive() throws IOException {
        String component = text("components/UiButton.js");
        String css = text("style.css");

        assertThat(text("index.html"))
                .contains("/components/UiButton.js?v=20260909.4")
                .contains("/app.js?v=");
        assertThat(text("app.js")).contains("_app.component('ui-button', UiButton);");
        assertThat(component)
                .contains("'ui-button'")
                .contains("`ui-button--${variant}`")
                .contains("`ui-button--${size}`")
                .contains("'ui-button--icon-only'")
                .contains("focus: options => buttonRef.value?.focus(options)")
                .contains("ref: buttonRef")
                .contains("legacyClasses.includes('btn-primary')")
                .contains("legacyClasses.includes('btn-danger')")
                .contains("legacyClasses.includes('btn-quiet')");

        int contractStart = css.lastIndexOf("/*\n * Echo element-system primitive overrides.");
        assertThat(contractStart).isGreaterThan(0);
        String contract = css.substring(contractStart);
        assertThat(contract)
                .contains(".ui-button {\n    height: var(--control-h);\n    min-height: var(--control-h);\n    padding: 0 12px;")
                .contains("gap: 7px;")
                .contains("border-radius: 6px;")
                .contains(".ui-button > i { font-size: 16px;")
                .contains(".ui-button--compact { height: var(--control-h-sm); min-height: var(--control-h-sm);")
                .contains("border-radius: 5px")
                .contains(".ui-button--quiet { border-color: transparent; background: transparent; color: var(--primary) }")
                .contains(".ui-button--danger { border-color: rgba(var(--danger-rgb), 0.5); background: transparent; color: var(--danger-text) }")
                .contains(".ui-button:disabled, .ui-button.disabled { opacity: 0.55;")
                .contains(".card-table .col-actions .ui-button--secondary,");

        assertThat(text("components/RuleEditModal.js"))
                .contains("<ui-button variant=\"quiet\" :disabled=\"saving\" @click=\"$emit('close')\">{{t('modal.cancel')}}</ui-button>")
                .contains("<ui-button class=\"btn btn-secondary\" @click=\"$emit('save',false)\"");

        Pattern directLegacyButton = Pattern.compile("<button\\b[^>]*class=\\\"(?:btn(?:\\s|\\\")|[^\\\"]+\\sbtn(?:\\s|\\\"))");
        for (String page : new String[]{
                "AccountsPage", "AuditPage", "ChangePasswordModal", "ConfirmModal", "ImportModal",
                "IssuesPage", "OpenApiPreviewModal", "PriorityHelpModal", "ResponseEditModal",
                "ResponsesPage", "RuleApplyModal", "RuleEditModal", "RuleTable", "RuleDetail",
                "RulesPage", "SettingsPage", "StatsPage", "TourOverlay", "WorkspacePagination", "UiDetailDrawer"
        }) {
            String source = text("components/" + page + ".js");
            assertThat(source).as(page).contains("<ui-button");
            assertThat(directLegacyButton.matcher(source).find()).as(page + " direct .btn").isFalse();
        }
    }

    @Test
    void badgesStatusesTogglesAndFieldsUseTheSharedElementContracts() throws IOException {
        assertThat(text("index.html"))
                .contains("/components/UiBadge.js?v=20260909.2")
                .contains("/components/UiStatus.js?v=20260909.1")
                .contains("/components/UiToggle.js?v=20260909.1")
                .contains("/components/UiSegmentedControl.js?v=20260912.1")
                .contains("/components/UiToggleGroup.js?v=20260912.1")
                .contains("/components/UiFilterChipList.js?v=20260909.3");
        assertThat(text("app.js"))
                .contains("_app.component('ui-badge', UiBadge);")
                .contains("_app.component('ui-status', UiStatus);")
                .contains("_app.component('ui-toggle', UiToggle);")
                .contains("_app.component('ui-segmented-control', UiSegmentedControl);")
                .contains("_app.component('ui-toggle-group', UiToggleGroup);")
                .contains("_app.component('ui-filter-chip-list', UiFilterChipList);");
        assertThat(text("components/UiBadge.js"))
                .contains("`ui-badge--${tone}`")
                .contains("'badge-success', 'bg-success'")
                .contains("'badge-warning', 'bg-warning'")
                .contains("'badge-danger', 'bg-danger'");
        assertThat(text("components/UiStatus.js"))
                .contains("`ui-status--${props.tone}`")
                .contains("ui-status__dot");
        assertThat(text("components/UiToggle.js"))
                .contains("class=\"ui-toggle\"")
                .contains(":aria-label=\"ariaLabel\"")
                .contains("@click.prevent.stop=\"$emit('toggle')\"");
        assertThat(text("components/UiSegmentedControl.js"))
                .contains("class=\"ui-segmented-control\"")
                .contains("role=\"radiogroup\"")
                .contains("bi-check-lg")
                .contains("ui-segmented-control__description")
                .contains("ui-segmented-control--'+size");
        assertThat(text("components/UiToggleGroup.js"))
                .contains("role=\"group\"")
                .contains(":aria-pressed=\"isSelected(option)\"")
                .contains("this.clearable && this.isSelected(option) ? '' : option.value")
                .doesNotContain("ui-toggle-group__check")
                .doesNotContain(":class=\"{'is-visible':isSelected(option)}\"");
        assertThat(text("components/UiFilterChipList.js"))
                .contains("role=\"group\"")
                .doesNotContain("role=\"listitem\"")
                .contains("t('common.removeFilter', {filter:item.label})")
                .contains("@click=\"$emit('clear')\"");

        String css = text("style.css");
        int contractStart = css.lastIndexOf("/*\n * Echo element-system primitive overrides.");
        String contract = css.substring(contractStart);
        assertThat(contract)
                .contains(".ui-badge {\n    min-height: 22px;")
                .contains("border-radius: 4px;")
                .contains(".ui-status__dot { width: 7px; height: 7px;")
                .contains(".ui-toggle { position: relative; width: 32px; height: 32px;")
                .contains(".ui-toggle__track { width: 32px; height: 18px;")
                .contains(".ui-segmented-control--compact {")
                .contains(".ui-toggle-group {")
                .contains(".ui-toggle-group__option.ui-button.is-selected")
                .contains("box-shadow: inset 0 0 0 1px rgba(var(--primary-rgb), 0.48);")
                .contains(".ui-toggle-group__option.ui-button:first-child { border-radius: 5px 0 0 5px }")
                .contains(".ui-toggle-group__option.ui-button:last-child { border-radius: 0 5px 5px 0 }")
                .contains(".ui-toggle-group__option.ui-button:only-child { border-radius: 5px }")
                .doesNotContain("inset 0 -2px 0 var(--primary)")
                .doesNotContain(".ui-toggle-group__check { color: currentColor }")
                .doesNotContain(".ui-toggle-group__check { width: 13px; opacity: 0 }")
                .contains(".ui-filter-chip-list { min-height: var(--control-h-sm); margin: 0 0 var(--space-sm);")
                .doesNotContain(".filter-chips { min-height: 28px; margin: calc(var(--space-xs) * -1)")
                .doesNotContain("margin: -0.5rem 0 0.5rem 0;")
                .contains(".ui-filter-chip-list .ui-filter-chip .chip-remove {")
                .contains("input.form-control:not([type=\"checkbox\"]):not([type=\"radio\"]),\nselect.form-control,\nselect.form-select { height: var(--control-h);")
                .contains(".form-control-sm,\ninput.form-control-sm:not([type=\"checkbox\"]):not([type=\"radio\"]),")
                .contains(".form-label { font-size: 12px;")
                .contains(".invalid-feedback { font-size: 11px;");

        for (String page : new String[]{"AccountsPage", "AuditPage", "IssuesPage", "ResponsesPage",
                "RuleEditModal", "RuleTable", "RuleDetail", "RulesPage", "SettingsPage", "StatsPage"}) {
            assertThat(text("components/" + page + ".js"))
                    .as(page + " semantic badges")
                    .doesNotContain("<span class=\"badge")
                    .contains("<ui-badge");
        }
        assertThat(text("components/SettingsPage.js"))
                .contains("<ui-status")
                .contains("<connection-targets-table")
                .doesNotContain("status-on")
                .doesNotContain("status-off");
        assertThat(text("components/ConnectionTargetsTable.js"))
                .contains("<ui-dropdown-menu")
                .contains("@click=\"$emit('test', target)\"")
                .contains("@click=\"$emit('edit', target)\"")
                .contains("variant=\"secondary\" size=\"compact\" class=\"connection-action-test\"")
                .doesNotContain("status-on")
                .doesNotContain("status-off");
        assertThat(text("components/RulesPage.js")).contains("<ui-toggle").doesNotContain("toggle-slider");
        assertThat(text("components/RuleTable.js")).contains("<ui-toggle ").doesNotContain("toggle-slider");
        assertThat(text("components/WorkspaceSearchField.js")).contains("<ui-button").doesNotContain("<button");
    }

    @Test
    void listFiltersUseSharedControlsWithCorrectSelectionSemantics() throws IOException {
        for (String page : new String[]{"RulesPage", "ResponsesPage", "AuditPage", "StatsPage"}) {
            String source = text("components/" + page + ".js");
            assertThat(source).as(page + " toggle groups")
                    .contains("<ui-toggle-group")
                    .doesNotContain("<div class=\"btn-group\"");
            assertThat(source).as(page + " filter chips")
                    .contains("<ui-filter-chip-list")
                    .doesNotContain("class=\"filter-chips\"")
                    .doesNotContain("class=\"filter-chip\"");
        }
        for (String page : new String[]{"AccountsPage", "IssuesPage"}) {
            assertThat(text("components/" + page + ".js"))
                    .as(page + " required filters")
                    .contains("<ui-segmented-control")
                    .contains("size=\"compact\"")
                    .doesNotContain("<div class=\"btn-group\"");
        }
        for (String locale : new String[]{"en", "zh-TW"}) {
            var common = new ObjectMapper().readTree(text("i18n/" + locale + ".json")).path("common");
            assertThat(common.path("activeFilters").asText()).isNotBlank();
            assertThat(common.path("removeFilter").asText()).contains("{filter}");
        }
    }

    @Test
    void sharedMotionSystemIsRestrainedAndHonorsReducedMotion() throws IOException {
        String css = text("style.css");
        String filterChips = text("components/UiFilterChipList.js");
        String dropdown = text("components/UiDropdownMenu.js");

        assertThat(css)
                .contains("--motion-fast: 120ms;")
                .contains("--motion-base: 180ms;")
                .contains("--motion-slow: 240ms;")
                .contains("--ease-standard: cubic-bezier(0.2, 0, 0, 1);")
                .contains(".ui-context-icon-enter-from,")
                .contains("transform: scale(0.25); filter: blur(4px)")
                .contains(".ui-filter-chip-motion-enter-from { opacity: 0; transform: translateY(3px) }")
                .contains(".ui-popover-motion-enter-from,")
                .contains("@media (prefers-reduced-motion: reduce)")
                .doesNotContain("transition: all")
                .doesNotContain("transition: 0.2s;");
        assertThat(filterChips)
                .contains("<TransitionGroup v-if=\"items.length\" name=\"ui-filter-chip-motion\"")
                .contains("key=\"__clear_filters__\"");
        assertThat(dropdown)
                .contains("<Transition name=\"ui-popover-motion\">")
                .contains("v-if=\"open\"");
    }

    @Test
    void ruleDetailDrawerUsesTheSharedDetailLayout() throws IOException {
        String detail = text("components/RuleDetail.js");
        String console = text("console.css");

        assertThat(detail)
                .contains("<ui-detail-drawer")
                .contains("class=\"detail-section\"")
                .contains("class=\"detail-grid\"")
                .contains("t('rules.pvSectionConditions')")
                .contains("t('rules.pvSectionResponse')")
                .contains("t('rules.pvSectionSettings')")
                .contains("t('rules.pvSearchBody')")
                .contains("t('rules.copyFullContent')");
        assertThat(text("components/RulesPage.js"))
                .contains("<rule-detail")
                .contains(":has-prev=\"detailIndex > 0\"")
                .doesNotContain("rulePreviewExpanded")
                .doesNotContain("pvHighlight");
        assertThat(console)
                .contains(".ui-detail-drawer {")
                .contains(".detail-grid { display: grid; grid-template-columns: 116px minmax(0, 1fr);")
                // The drawer floats over the list (no layout push) and goes full screen on phones.
                .doesNotContain(".main:has(.ui-detail-drawer)")
                .contains("border-radius: var(--radius-surface);\n    background: var(--card);")
                .contains(".ui-detail-drawer { inset: 0; width: 100vw; border: 0; border-radius: 0 }")
                .contains("@container (max-width: 860px) {\n    .rule-table .col-cond { display: none }");
    }

    @Test
    void independentAcceptanceFindingsRemainFixed() throws IOException {
        String help = text("components/PriorityHelpModal.js");
        String settings = text("components/SettingsPage.js") + text("components/ConnectionTargetsTable.js");
        String css = text("style.css");
        assertThat(help)
                .contains("<h3>{{t('modal.condFieldQuery')}}</h3>")
                .doesNotContain("<h3>{{t('help.condQuery')}}</h3>");
        assertThat(settings)
                .contains("target.authType==='NONE'?this.t('settings.authNone')")
                .contains("target.authType==='BASIC'?this.t('settings.authBasic')")
                .contains("this.t('settings.authBearerToken')")
                .contains("class=\"sub-info connection-queue\"")
                .contains("class=\"connection-source-value\"");
        assertThat(css)
                .contains(".pv-forward-target {")
                .contains("overflow-wrap: anywhere;")
                .contains(".connection-queue,\n.connection-source-value {")
                .contains(".connection-name { min-width: 0; overflow-wrap: anywhere; white-space: normal;");

        for (String locale : new String[]{"en.json", "zh-TW.json"}) {
            var messages = new ObjectMapper().readTree(text("i18n/" + locale));
            assertThat(messages.path("modal").path("forwardSpecificConnection").asText()).isNotBlank();
            String priority = messages.path("help").path("diagramPriority").asText();
            assertThat(priority.indexOf("1.")).isLessThan(priority.indexOf("2."));
            assertThat(priority).contains(locale.startsWith("zh") ? "精確路徑" : "Exact paths");
        }
    }

    @Test
    void ruleRowsShareOneTopAlignmentSlotAcrossColumns() throws IOException {
        assertThat(text("style.css"))
                .contains("--rule-row-primary-h: 32px;")
                .contains("min-height: var(--rule-row-primary-h);")
                .contains(".rule-list-table td.col-cond > .cond-list,")
                .contains(".rule-list-table td.col-priority .table-metadata,")
                .contains(".rule-list-table td.col-datetime .table-date-stack > .sub-info:first-child,")
                .contains(".rule-list-table td.rule-row-action-column .rule-row-actions {")
                .contains("justify-content: center;");
    }

    @Test
    void multiLineWorkspaceListsShareOnePrimaryAlignmentSlot() throws IOException {
        String css = text("style.css");
        assertThat(css)
                .contains(".workspace-primary-aligned-table {")
                .contains("--workspace-row-primary-h: 32px;")
                .contains(".workspace-primary-aligned-table tbody > tr:not(.rule-preview-row):not(.audit-expand):not(.log-detail-row) > td {")
                .contains("padding-block: var(--space-xs);")
                .contains("vertical-align: top;")
                .contains(".workspace-row-primary {")
                .contains("min-height: var(--workspace-row-primary-h);")
                .contains(".workspace-row-primary-end { justify-content: flex-end }");

        for (String page : new String[]{"AuditPage", "IssuesPage", "AccountsPage"}) {
            assertThat(text("components/" + page + ".js"))
                    .as(page + " aligned list rows")
                    .contains("workspace-primary-aligned-table")
                    .contains("workspace-row-primary");
        }
        assertThat(text("components/SettingsPage.js"))
                .doesNotContain("workspace-primary-aligned-table");
    }

    @Test
    void detailFinishingUsesSemanticLayoutHooksAndSelectedOnlyIndicators() throws IOException {
        String css = text("style.css");
        String segmentedControl = text("components/UiSegmentedControl.js");

        assertThat(segmentedControl)
                .contains("v-if=\"!option.iconOnly && isSelected(option)\"")
                .contains("<Transition name=\"ui-context-icon\">");
        assertThat(css)
                .contains(".ui-segmented-control__check {\n    position: absolute;")
                .contains(".ui-segmented-control--compact .ui-segmented-control__check { display: none }")
                .contains(".ui-segmented-control--compact .ui-segmented-control__option.is-selected {")
                .contains(".pv-header {\n        display: grid;")
                .contains(".pv-header-actions {\n        grid-column: 1 / -1;")
                .contains(".audit-list-table .audit-time-column,")
                .contains(".issues-list-table .issue-disclosure-column { width: 48px }")
                .contains("select.form-select-sm { height: var(--control-h-sm);")
                .contains(".response-editor-modal .response-sse-table .form-control-sm {\n    min-height: 32px;")
                .contains(".response-editor-modal .response-sse-table tbody tr {")
                .contains("grid-template-areas:")
                .contains(".detail-row-cell { padding: 0 !important }")
                .contains(".table-row-actions {")
                .contains(".workspace-modal[tabindex=\"-1\"]:focus-visible { outline: 0 }")
                .contains(".rule-apply-inline > .rule-apply-body {")
                .contains("grid-template-columns: repeat(2, minmax(0, 1fr))")
                .contains(".rule-apply-kv > div,");

        for (String page : new String[]{
                "AccountsPage", "AuditPage", "IssuesPage", "ResponsesPage", "RuleEditModal",
                "RuleTable", "RuleDetail", "RulesPage", "SettingsPage", "SidebarNav", "StatsPage", "ToastContainer",
                "UiDetailDrawer", "UiRowMenu", "ResponseDetail"
        }) {
            assertThat(text("components/" + page + ".js"))
                    .as(page + " static inline styles")
                    .doesNotContain(" style=\"");
        }
        assertThat(text("components/ResponsesPage.js"))
                .contains("{ key: 'delete', label: t('responses.delete'), icon: 'bi-trash', danger: true");
    }

    @Test
    void settingsDeleteConfirmationUsesTheLoadedSystemRuleCount() throws IOException {
        assertThat(text("components/SettingsPage.js"))
                .contains("$emit('delete-all-rules', status.ruleCount)");
        assertThat(text("composables/useRules.js"))
                .contains("const deleteAllRules = async (knownCount = null) =>")
                .contains("knownCount != null && Number.isFinite(Number(knownCount)) ? Number(knownCount) : ruleTotalElements.value");
    }

    @Test
    void everySortableTableHeaderHasKeyboardControlAndSortState() throws IOException {
        for (String page : new String[]{"RuleTable", "ResponsesPage", "AuditPage", "AccountsPage", "IssuesPage", "StatsPage"}) {
            String source = text("components/" + page + ".js");
            assertThat(Pattern.compile("<th\\b[^>]*@click").matcher(source).find()).as(page).isFalse();
            assertThat(source).contains("<ui-table-sort-header").contains(":aria-sort=");
        }
    }

    @Test
    void listHeadersMenusAndLoadStatesUseSharedAccessiblePrimitives() throws IOException {
        assertThat(text("index.html"))
                .contains("/components/UiTableSortHeader.js?v=20260909.1")
                .contains("/components/UiDropdownMenu.js?v=20261007.1")
                .contains("/components/UiLoadState.js?v=20260909.1");
        assertThat(text("app.js"))
                .contains("_app.component('ui-table-sort-header', UiTableSortHeader);")
                .contains("_app.component('ui-dropdown-menu', UiDropdownMenu);")
                .contains("_app.component('ui-load-state', UiLoadState);");

        assertThat(text("components/UiTableSortHeader.js"))
                .contains("variant=\"quiet\"")
                .contains("size=\"compact\"")
                .contains("t('common.sortBy', { field: this.label })")
                .contains("bi-arrow-down-up");
        assertThat(text("components/UiDropdownMenu.js"))
                .contains("role=\"menu\"")
                .contains("role=\"menuitem\"")
                .contains("event.key === 'ArrowDown'")
                .contains("event.key === 'Escape'")
                .contains("triggerRef.value?.focus()")
                .contains("selectItem(item.key)");
        assertThat(text("components/UiLoadState.js"))
                .contains("kind==='error' ? 'alert'")
                .contains("hasAction")
                .contains("class=\"ui-load-state__action\"");

        for (String page : new String[]{"RulesPage", "ResponsesPage", "AuditPage", "AccountsPage", "IssuesPage", "StatsPage"}) {
            String source = text("components/" + page + ".js");
            assertThat(source).as(page + " load state").contains("<ui-load-state");
            assertThat(source).as(page + " loading semantics")
                    .contains("role=\"status\"")
                    .contains(":aria-label=");
        }
        for (String page : new String[]{"RulesPage", "ResponsesPage"}) {
            assertThat(text("components/" + page + ".js"))
                    .contains("<ui-dropdown-menu")
                    .doesNotContain("<div v-if=\"showDataDropdown\" class=\"data-dropdown\"");
        }
        for (String locale : new String[]{"en", "zh-TW"}) {
            var common = new ObjectMapper().readTree(text("i18n/" + locale + ".json")).path("common");
            assertThat(common.path("sortBy").asText()).contains("{field}");
            assertThat(common.path("loading").asText()).isNotBlank();
        }
    }

    @Test
    void tabsAndRequiredChoicesUseSharedKeyboardAccessiblePrimitives() throws IOException {
        assertThat(text("index.html"))
                .contains("/components/UiTabs.js?v=20260909.1")
                .contains("/components/UiChoiceGroup.js?v=20260909.1");
        assertThat(text("app.js"))
                .contains("_app.component('ui-tabs', UiTabs);")
                .contains("_app.component('ui-choice-group', UiChoiceGroup);");
        assertThat(text("components/UiTabs.js"))
                .contains("role=\"tablist\"")
                .contains("role=\"tab\"")
                .contains("event.key === 'Home'")
                .contains("event.key === 'ArrowUp'")
                .contains(":tabindex=\"isSelected(item)?0:-1\"");
        assertThat(text("components/UiChoiceGroup.js"))
                .contains("role=\"radiogroup\"")
                .contains("role=\"radio\"")
                .contains(":aria-checked=\"isSelected(option)\"")
                .contains("focusSelected()")
                .contains("'ArrowDown'");

        for (String page : new String[]{"PriorityHelpModal", "RuleApplyModal", "StatsPage", "RuleEditModal"}) {
            assertThat(text("components/" + page + ".js")).as(page + " tabs").contains("<ui-tabs");
        }
        for (String page : new String[]{"ImportModal", "ResponseEditModal", "RuleEditModal", "SettingsPage"}) {
            assertThat(text("components/" + page + ".js")).as(page + " choices").contains("<ui-choice-group");
        }
        assertThat(text("components/RuleEditModal.js"))
                .doesNotContain("<div class=\"rule-editor-mode-switch\"")
                .doesNotContain("<div class=\"rule-protocol-options\"")
                .doesNotContain("<div class=\"method-group\"")
                .doesNotContain("<div class=\"protocol-switch\"")
                .doesNotContain("<div class=\"response-picker-filters\"");
        assertThat(text("components/ImportModal.js"))
                .contains("this.$refs.formatChoices?.focusSelected()")
                .doesNotContain("<div class=\"import-format-options\"");
        assertThat(text("components/ResponseEditModal.js")).doesNotContain("<div class=\"protocol-switch response-type-switch\"");
        assertThat(text("components/SettingsPage.js")).doesNotContain("<div class=\"protocol-switch\"");

        String css = text("style.css");
        assertThat(css)
                .contains(".ui-tabs__tab:focus-visible")
                .contains(".ui-tabs__tab:disabled")
                .contains(".ui-choice-group__option:focus-visible")
                .contains(".ui-choice-group__option:disabled")
                .contains(".ui-choice-group--cards");
    }

    @Test
    void responseIdentityRetainsSortingAndExposesUsageAcrossBreakpoints() throws IOException {
        String source = text("components/ResponsesPage.js");
        String detail = text("components/ResponseDetail.js");

        assertThat(source)
                .contains("$emit('toggle-response-sort', 'id')")
                .contains("class=\"record-name__id\">#{{r.id}}</span>")
                .contains("if (action === 'copy-id') this.$emit('clip-copy', String(r.id));")
                .contains("<span v-if=\"r.usageCount\" class=\"usage-count\"><strong>{{r.usageCount}}</strong>")
                .contains("{{t('responses.notUsed')}}");
        assertThat(detail)
                .contains("t('responses.orphanDaysLeft', {days: retention})")
                .contains("class=\"rule-method\" :data-method=")
                .contains("class=\"detail-link__path\">{{rule.matchKey}}</code>");
        assertThat(text("console.css"))
                .contains("@container (max-width: 1040px) { .response-table .col-created { display: none } }")
                .contains("@container (max-width: 760px) { .response-table .col-type, .response-table .col-updated { display: none } }");
    }

    @Test
    void primaryWorkspaceListsUseTheSharedDenseInformationLanguage() throws IOException {
        // List and group views render the same RuleTable row: method, endpoint, flags, first condition (+N) and update time.
        assertThat(text("components/RuleTable.js"))
                .contains("class=\"cond-more\"")
                .contains("class=\"cond-chip\" :data-kind=\"condTags(r)[0].t\"")
                .contains("class=\"rule-method\" :data-method=")
                .contains("class=\"rule-flags\"")
                .contains("{{fmtTime(r.updatedAt)}}")
                .contains("<ui-row-menu");
        assertThat(text("components/RulesPage.js")).contains("<rule-table").doesNotContain("<rule-group-row");
        // Request logs use the shared single-line row: time, method, endpoint + description, duration, result, ⋯ menu.
        assertThat(text("components/StatsPage.js"))
                .contains("<span class=\"rule-desc\" :title=\"requestDescription(item)\">{{requestDescription(item)}}</span>")
                .contains("forwardTargetName(item.log.forwardTarget)")
                .contains("class=\"col-duration cell-end cell-mono cell-subtle\">{{item.log.responseTimeMs}} ms</td>")
                .contains("class=\"col-time cell-mono cell-subtle\"")
                .contains("class=\"rule-method\" :data-method=\"item.log.protocol==='HTTP' ? item.log.method : null\"")
                .contains("<ui-row-menu :items=\"logMenuItems(item)\"")
                .contains("t('stats.createRuleFromLog')");
        assertThat(text("components/ResponsesPage.js"))
                .contains("<ui-row-menu :items=\"menuItems(r)\"")
                .contains("class=\"usage-count\"");
        assertThat(text("style.css"))
                .contains("Unified high-density list language")
                .contains(".response-usage-column { width: 156px }")
                .contains("grid-template-columns: repeat(3, 32px)")
                .contains(".log-request-primary { display: grid; grid-template-columns: 40px 56px minmax(0, 1fr);")
                .contains(".logs-table .log-time-column { width: 104px }")
                .contains(".logs-table .log-duration-column { width: 88px; text-align: end }")
                .contains(".logs-table .col-actions-1 { text-align: end }")
                .contains("grid-template-columns: max-content minmax(180px, 260px) minmax(240px, 1fr);")
                .contains(".logs-table,\n    .logs-table thead,\n    .logs-table tbody {\n        display: block;")
                .contains(".response-mobile-reference-control {");
    }

    @Test
    void linkedRulesAndDisclosuresExposeNativeNavigationAndState() throws IOException {
        // Rule details open in a labelled drawer; rows expose their selection state instead of an expanded preview row.
        assertThat(text("components/RuleTable.js"))
                .contains("data-detail-row tabindex=\"0\"")
                .contains(":aria-selected=\"selectedId===r.id ? 'true' : 'false'\"")
                .contains("@keydown=\"onRowKeydown($event, r)\"");
        assertThat(text("components/UiDetailDrawer.js"))
                .contains(":aria-labelledby=\"headingId\"")
                .contains("event.key === 'Escape'");
        // Linked rules are real buttons inside the response drawer and jump straight to the rule.
        assertThat(text("components/ResponseDetail.js"))
                .contains("<button type=\"button\" class=\"detail-link\" @click=\"$emit('go-to-rule', rule.id)\">")
                .contains("t('responses.linkedRulesTitle')")
                .contains("t('responses.noVisibleLinkedRules')");
        assertThat(text("components/AuditPage.js"))
                .contains(":aria-expanded=\"selectedAudit===log.id\"")
                .contains(":id=\"'audit-detail-'+log.id\"")
                .contains("{{auditActionLabel(log.action)}}")
                .contains("auditViewportWidth: window.innerWidth")
                .contains(":colspan=\"auditDetailColspan\"");
        assertThat(text("composables/useAudit.js"))
                .contains("createdBy', 'updatedBy'")
                .contains("action: t('auditFields.action')")
                .contains("faultType: t('auditFields.faultType')")
                .contains("forwardTargetMode: t('auditFields.forwardTargetMode')")
                .contains("enumLabels[key]?.[s]");
        assertThat(text("components/IssuesPage.js"))
                .contains("issueViewportWidth: window.innerWidth")
                .contains(":colspan=\"issueDetailColspan\"");
        // Row actions never trigger the row's own click or double-click, and the row menu closes on Escape.
        assertThat(text("components/RuleTable.js"))
                .contains("<td class=\"col-actions\" @click.stop @dblclick.stop>")
                .contains("<ui-row-menu");
        assertThat(text("components/UiDropdownMenu.js")).contains("event.key === 'Escape'");
    }

    @Test
    void dialogTitlesAndInspectorSectionsKeepASequentialHeadingOutline() throws IOException {
        for (String component : new String[]{"AccountsPage", "ChangePasswordModal", "ConfirmModal", "ImportModal",
                "IssuesPage", "OpenApiPreviewModal", "PriorityHelpModal", "RuleEditModal", "SettingsPage"}) {
            assertThat(text("components/" + component + ".js"))
                    .as(component + " dialog title")
                    .doesNotContain("<h3 id=\"");
        }
        assertThat(text("components/ResponseEditModal.js"))
                .contains("<h2 id=\"responseEditorTitle\"")
                .contains("<h3 id=\"sseEventsTitle\"")
                .contains("<h4 id=\"ssePreviewTitle\"");
        assertThat(text("components/PriorityHelpModal.js"))
                .contains("<h2 id=\"helpModalTitle\"")
                .doesNotContain("<h4>");
        assertThat(text("components/StatsPage.js"))
                .contains("<h2 class=\"ui-detail-panel-heading\" :id=\"'request-body-heading-'")
                .contains("getInputField()?.setAttribute(")
                .contains("role=\"img\" :aria-label=\"t('stats.matchChainStep'");
    }

    @Test
    void requestLogInspectorReusesRuleDetailPanelsAndTextOnlyOutcomes() throws IOException {
        String stats = text("components/StatsPage.js");
        String css = text("style.css");
        assertThat(stats)
                .contains("class=\"log-inspector-pane ui-detail-panel\"")
                .contains("class=\"log-overview-section ui-detail-panel\"")
                .contains("class=\"log-inspector-content log-inspector-trace ui-detail-panel\"")
                .contains("class=\"pv-body-empty\"")
                .doesNotContain("bi-check2-circle")
                .doesNotContain("bi-x-circle")
                .doesNotContain("reasonIcon(c.reason)");
        assertThat(css)
                .contains(".ui-detail-panel {")
                .contains("border: 1px solid var(--separator);")
                .contains("background: var(--surface-neutral-soft);")
                .contains(".ui-detail-panel-heading {");
        for (String language : new String[]{"zh-TW", "en"}) {
            var labels = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(text("i18n/" + language + ".json")).path("reasonText");
            for (String reason : new String[]{"match", "nearMiss", "shadowed", "mismatch", "scenario_state_mismatch"}) {
                assertThat(labels.path(reason).asText())
                        .as(language + " " + reason + " is text-only")
                        .doesNotContain("✓", "✗", "⚠", "◌", "⊘");
            }
        }
    }

    @Test
    void identityKeepsProtectedAndNonDefaultSourceInformationAndFullIdCopy() throws IOException {
        assertThat(text("components/RuleTable.js"))
                .contains("r.isProtected")
                .contains("t('rules.toggleEnabled'")
                .contains("r.updatedBy");
        assertThat(text("components/RuleDetail.js"))
                .contains("view.targetHost!=='default'")
                .contains("t('rules.pvTargetHost')")
                .contains("$emit('clip-copy', view.id)")
                .contains("t('rules.copyId')")
                .contains("view.updatedBy");
    }

    @Test
    void longSelectionAndScenarioNamesCanWrapInsteadOfDisappearing() throws IOException {
        String css = text("style.css");
        for (String selector : new String[]{".response-picker-drawer-copy > strong", ".scenario-identity strong", ".scenario-state"}) {
            int start = css.indexOf(selector + " {");
            String rule = css.substring(start, css.indexOf('}', start));
            assertThat(rule).contains("overflow-wrap: anywhere").contains("white-space: normal")
                    .doesNotContain("text-overflow: ellipsis");
        }
    }

    @Test
    void responsePaginationAndAuditTargetHaveOwnLocalizedLabels() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        for (String locale : new String[]{"en", "zh-TW"}) {
            var messages = mapper.readTree(text("i18n/" + locale + ".json"));
            assertThat(messages.path("responses").path("pagination").asText()).isNotBlank()
                    .isNotEqualTo(messages.path("stats").path("pagination").asText());
            assertThat(messages.path("audit").path("thTarget").asText()).isNotBlank();
            assertThat(messages.path("audit").path("pagination").asText()).isNotBlank()
                    .isNotEqualTo(messages.path("stats").path("pagination").asText());
        }
        assertThat(text("components/ResponsesPage.js")).contains("t('responses.pagination')")
                .doesNotContain("t('stats.pagination')");
        assertThat(text("components/AuditPage.js")).contains("t('audit.pagination')")
                .doesNotContain("t('stats.pagination')");
    }

    @Test
    void selectsRetainTheirChevronAndAuditDetailsUseLocalizedLabels() throws IOException {
        String css = text("style.css");
        assertThat(css)
                .contains("select.form-control { cursor: pointer; appearance: none; background-image: url(")
                .contains(".connection-form-modal .form-control {\n    border-color: var(--separator);\n    background-color: var(--surface-control);")
                .doesNotContain(".connection-form-modal .form-control {\n    border-color: var(--separator);\n    background: var(--surface-control);");

        for (String locale : new String[]{"en", "zh-TW"}) {
            var messages = new ObjectMapper().readTree(text("i18n/" + locale + ".json"));
            assertThat(messages.path("auditFields").path("action").asText()).isNotBlank();
            assertThat(messages.path("auditFields").path("forwardTargetMode").asText()).isNotBlank();
            assertThat(messages.path("auditValues").path("modeForward").asText()).isNotBlank();
            assertThat(messages.path("auditValues").path("targetSavedConnection").asText()).isNotBlank();
        }
    }

    @Test
    void unconditionalRulesArePresentedAsIntentionalDefaultMatches() throws IOException {
        assertThat(text("i18n/zh-TW.json")).contains("\"noCondition\": \"預設匹配\"");
        assertThat(text("i18n/en.json")).contains("\"noCondition\": \"Default match\"");
        assertThat(text("composables/useI18n.js")).contains("/i18n/${lang}.json?v=20261006.3");
    }

    @Test
    void tagComposerUsesLocalizedLabelsAndFullSizeControls() throws IOException {
        String modal = text("components/RuleEditModal.js");
        assertThat(modal)
                .contains("t('modal.tagKeyPlaceholder')")
                .contains("t('modal.tagValuePlaceholder')")
                .contains("t('modal.addTag')")
                .doesNotContain("placeholder=\"key\"")
                .doesNotContain("placeholder=\"value\"");
        for (String locale : new String[]{"en", "zh-TW"}) {
            var messages = new ObjectMapper().readTree(text("i18n/" + locale + ".json")).path("modal");
            assertThat(messages.path("tagKeyPlaceholder").asText()).isNotBlank();
            assertThat(messages.path("tagValuePlaceholder").asText()).isNotBlank();
            assertThat(messages.path("addTag").asText()).isNotBlank();
        }
    }

    @Test
    void generatedResponseEditorsHaveAccessibleNames() throws IOException {
        assertThat(text("composables/useEditor.js"))
                .contains("editors[key].getInputField().setAttribute('aria-label', t('modal.responseContent'))")
                .contains("ta.setAttribute('aria-label', t('modal.responseContent'))");
    }

    private static String text(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
