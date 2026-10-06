package com.echo.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEditModalResourceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void exposesRunTestToTheRootTemplate() throws IOException {
        String app = resourceText("static/app.js");
        assertThat(app.substring(app.indexOf("return { locale,")))
                .contains("testSseMode, runTest, stopSseTest");
        assertThat(resourceText("static/index.html")).contains("@run-test=\"runTest\"");
    }

    @Test
    void associatesMatchingFieldsAndAnnouncesMethodSelection() throws IOException {
        String component = resourceText("static/components/RuleEditModal.js");
        for (String field : new String[]{"rule-http-path", "rule-source-host", "rule-jms-queue", "rule-jms-reply"}) {
            assertThat(component).contains("for=\"" + field + "\"").contains("id=\"" + field + "\"");
        }
        assertThat(component).contains("<ui-choice-group class=\"method-group\"")
                .contains("v-model=\"form.method\"")
                .contains(":aria-label=\"t('modal.method')\"");
        assertThat(resourceText("static/components/UiChoiceGroup.js"))
                .contains("role=\"radiogroup\"")
                .contains(":aria-checked=\"isSelected(option)\"");
    }

    @Test
    void restoresPaneRatioAfterReturningFromDeclarativeMode() throws IOException {
        assertThat(resourceText("static/components/RuleEditModal.js"))
                .contains("Vue.watch(() => props.editorMode, mode => {")
                .contains("props.show && mode === 'form'")
                .contains("Vue.nextTick(applySplitRatio)");
    }

    @Test
    void exposesMockForwardAndFaultAsMutuallyExclusiveRuleModes() throws IOException {
        String component = resourceText("static/components/RuleEditModal.js");
        String segmentedControl = resourceText("static/components/UiSegmentedControl.js");

        assertThat(component)
                .contains("{ value: 'MOCK'")
                .contains("{ value: 'FORWARD'")
                .contains("{ value: 'FAULT'")
                .contains("<ui-segmented-control")
                .contains("v-model=\"ruleMode\"")
                .contains(":description=\"ruleModeDescription\"")
                .doesNotContain("name=\"ruleAction\"");
        assertThat(segmentedControl)
                .contains("role=\"radiogroup\"")
                .contains("type=\"radio\"")
                .contains(":checked=\"isSelected(option)\"")
                .contains("@change=\"select(option)\"")
                .contains("bi-check-lg")
                .contains(":aria-describedby=\"description ? descriptionId : undefined\"");
    }

    @Test
    void keepsNewResponseEditorOutsideTheModeTransitionRoot() throws IOException {
        String component = resourceText("static/components/RuleEditModal.js");
        int modeTransition = component.indexOf("<Transition name=\"ui-mode-panel-motion\" mode=\"out-in\">");
        int modeTransitionEnd = component.indexOf("</Transition>", modeTransition);
        int responseContent = component.indexOf("<div v-if=\"ruleMode==='MOCK'\" class=\"form-block response-content-block\">");

        assertThat(modeTransition).isGreaterThanOrEqualTo(0);
        assertThat(modeTransitionEnd).isGreaterThan(modeTransition);
        assertThat(responseContent).isGreaterThan(modeTransitionEnd);
        assertThat(component.substring(modeTransition, modeTransitionEnd))
                .doesNotContain("response-content-block");
        assertThat(component.substring(responseContent))
                .contains("class=\"response-content-mode\"")
                .contains("id=\"ruleEditEditor\"");
    }

    @Test
    void mapsFaultModeToExistingActionAndFaultTypeContract() throws IOException {
        String component = resourceText("static/components/RuleEditModal.js");

        assertThat(component)
                .contains("if (mode === 'FAULT')")
                .contains("props.form.action = 'MOCK'")
                .contains("props.form.faultType = 'CONNECTION_RESET'")
                .contains("props.form.faultType = 'NONE'");
    }

    @Test
    void rendersFaultConfigurationAsItsOwnModePanel() throws IOException {
        String component = resourceText("static/components/RuleEditModal.js");
        int faultPanel = component.indexOf("<div v-else-if=\"ruleMode==='FAULT'\"");
        int faultType = component.indexOf("id=\"ruleFaultType\"", faultPanel);
        int mockPanel = component.indexOf("<div v-else key=\"mock\"", faultPanel);

        assertThat(faultPanel).isGreaterThanOrEqualTo(0);
        assertThat(faultType).isGreaterThan(faultPanel).isLessThan(mockPanel);
        assertThat(component.substring(faultPanel, mockPanel))
                .contains("value=\"CONNECTION_RESET\"")
                .contains("value=\"EMPTY_RESPONSE\"")
                .doesNotContain("value=\"NONE\"");
    }

    @Test
    void rendersForwardConnectionAsAnExplicitSelectControl() throws IOException {
        String component = resourceText("static/components/RuleEditModal.js");

        assertThat(component)
                .contains("<select id=\"ruleForwardConnection\" class=\"form-control forward-connection-select-control\"")
                .contains("class=\"forward-connection-select-indicator\"")
                .contains("bi bi-chevron-down");
    }

    @Test
    void rendersSplitterAsTheDragIndicatorWithoutAGripButton() throws IOException {
        String component = resourceText("static/components/RuleEditModal.js");
        int splitterStart = component.indexOf("<div class=\"rule-splitter\"");
        int splitterEnd = component.indexOf("</div>", splitterStart);

        assertThat(splitterStart).isGreaterThanOrEqualTo(0);
        assertThat(splitterEnd).isGreaterThan(splitterStart);
        assertThat(component.substring(splitterStart, splitterEnd))
                .contains("role=\"separator\"")
                .contains("@keydown.left.prevent")
                .contains("@keydown.right.prevent")
                .doesNotContain("bi-grip-vertical")
                .doesNotContain("<button");
    }

    @Test
    void keepsPaneScrollingVisibleAndStacksTheEditorBeforeItBecomesCramped() throws IOException {
        String stylesheet = resourceText("static/style.css");

        assertThat(stylesheet)
                .contains("scrollbar-gutter: stable;")
                .contains("scrollbar-width: thin;")
                .contains("scrollbar-color: var(--border2) transparent;")
                .contains(".rule-left::-webkit-scrollbar")
                .contains(".rule-right::-webkit-scrollbar { width: 8px; height: 8px }")
                .contains("@media (max-width: 1080px)")
                .contains("flex: 0 0 12px;")
                .contains(".rule-splitter::before")
                .doesNotContain("background-attachment: local, local, scroll, scroll;")
                .doesNotContain("scrollbar-width: none;")
                .doesNotContain(".rule-splitter::after");
    }

    @Test
    void speaksTheSameLanguageAsTheListsAndDrawers() throws IOException {
        String component = resourceText("static/components/RuleEditModal.js");
        String stylesheet = resourceText("static/style.css");
        String console = resourceText("static/console.css");

        // The header names the rule being edited, with the same method colour as the list.
        assertThat(component)
                .contains("<p v-if=\"form.matchKey\" class=\"rule-modal-context\">")
                .contains("<span class=\"rule-method\" :data-method=\"form.protocol==='HTTP' ? (form.method || 'GET') : null\"")
                .doesNotContain("modal-heading-icon")
                .doesNotContain("rule-pane-heading-icon");
        // On/off state uses switches; section headings are text only.
        assertThat(component)
                .contains("<ui-toggle :checked=\"form.enabled\"")
                .contains("<ui-toggle :checked=\"form.isProtected\"")
                .doesNotContain("class=\"rule-state-control\"")
                .doesNotContain("<div class=\"form-block-header\"><i ");
        // A solid accent fill is reserved for the primary button; selections are tinted.
        assertThat(stylesheet)
                .doesNotContain(".protocol-btn.active { background: var(--btn-primary)")
                .doesNotContain(".rule-editor-mode-switch button.active { background: var(--btn-primary)")
                .contains(".rule-editor .form-block-header { color: var(--text-subtle); font-size: 11px; letter-spacing: 0.08em }");
        assertThat(console).contains(".modal-footer > .ui-button--quiet { color: var(--muted) }");
    }

    @Test
    void methodAndPathReadAsOneRequestLineAndLabelsStayQuiet() throws IOException {
        String component = resourceText("static/components/RuleEditModal.js");
        int line = component.indexOf("<div class=\"rule-request-line\">");

        assertThat(line).isPositive();
        assertThat(component.indexOf("<ui-choice-group class=\"method-group\"", line)).isGreaterThan(line)
                .isLessThan(component.indexOf("id=\"rule-http-path\"", line));
        assertThat(resourceText("static/console.css"))
                .contains(".rule-left { container: rule-left / inline-size }")
                .contains("@container rule-left (max-width: 500px) {")
                .contains(".ui-choice-group.rule-protocol-options > .rule-protocol-option { border: 0; background: transparent; box-shadow: none }");
        // Field labels never outweigh the values they describe.
        assertThat(resourceText("static/style.css"))
                .contains(".form-label { display: block; font-size: var(--font-base); font-weight: var(--font-weight-medium); color: var(--muted);")
                .contains(".response-template-actions .ui-button--quiet {");
        assertThat(component).doesNotContain("btn btn-xs btn-secondary");
    }

    @Test
    void ctrlOrCmdEnterSavesAndClosesWhenTheRuleCanBeSaved() throws IOException {
        assertThat(resourceText("static/components/RuleEditModal.js"))
                .contains("if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && props.editorMode === 'form') {")
                .contains("if (props.canSave && !props.saving) emit('save', true);")
                .contains("<span v-else class=\"modal-footer-hint\"><kbd>{{saveShortcutKey}}</kbd><kbd>Enter</kbd>");
    }

    @Test
    void alignsPaneHeadingsAboveTheirPrimaryControls() throws IOException {
        String component = resourceText("static/components/RuleEditModal.js");
        String stylesheet = resourceText("static/style.css");
        int rightPane = component.indexOf("<div class=\"rule-right\">");
        int rightHeading = component.indexOf("<div class=\"rule-pane-heading\">", rightPane);
        int primaryControls = component.indexOf("<div class=\"result-primary-row\">", rightPane);

        assertThat(rightPane).isGreaterThanOrEqualTo(0);
        assertThat(rightHeading).isGreaterThan(rightPane).isLessThan(primaryControls);
        assertThat(stylesheet)
                .contains(".rule-pane-heading { min-height: 32px;")
                .contains(".result-primary-row { display: block;")
                .contains(".result-primary-row > .ui-segmented-control-field { width: 100% }");
    }

    @Test
    void rendersExistingResponseSelectionBeforeTheServerPagedDrawer() throws IOException {
        String component = resourceText("static/components/RuleEditModal.js");
        int existingPanel = component.indexOf("class=\"response-existing-panel\"");
        int selectedSummary = component.indexOf("class=\"response-selected-card\"", existingPanel);
        int drawerLaunch = component.indexOf("class=\"response-picker-change\"", existingPanel);
        int drawer = component.indexOf("id=\"ruleResponsePickerDrawer\"", drawerLaunch);

        assertThat(existingPanel).isGreaterThanOrEqualTo(0);
        assertThat(selectedSummary).isGreaterThan(existingPanel).isLessThan(drawerLaunch);
        assertThat(component.substring(existingPanel, drawerLaunch))
                .contains("t('modal.currentSelection')")
                .contains("response-selected-actions");
        assertThat(drawer).isGreaterThan(drawerLaunch);
        assertThat(component.substring(drawerLaunch))
                .contains("modal.searchDifferentResponseLabel")
                .contains("class=\"response-picker-drawer\"")
                .contains("@click=\"toggleResponsePicker\"")
                .contains("aria-controls=\"ruleResponsePickerDrawer\"")
                .contains("@submit.prevent=\"$emit('search-response-picker')\"")
                .contains("@click=\"$emit('change-response-picker-page',responsePickerPage + 1)\"")
                .contains("modal.closeResponsePicker")
                .contains(":aria-expanded=\"responseDropdownOpen\"");
        assertThat(component)
                .doesNotContain("t('modal.responsesAvailable'")
                .contains("v-else ref=\"responsePickerLaunch\"")
                .contains("class=\"rule-right-content\" :inert=\"responseDropdownOpen ? '' : null\"")
                .contains(":aria-hidden=\"responseDropdownOpen ? 'true' : undefined\"")
                .contains("!element.closest('[inert]')")
                .contains("if (props.responseDropdownOpen) closeResponsePicker();")
                .contains("Vue.nextTick(() => responsePickerInput.value?.focus());")
                .doesNotContain("class=\"modal-overlay response-picker");
    }

    @Test
    void loadsResponsePickerWithServerSideSearchAndPagination() throws IOException {
        String composable = resourceText("static/composables/useRuleForm.js");

        assertThat(composable)
                .contains("const responsePickerPageSize = 20;")
                .contains("page: String(page)")
                .contains("size: String(responsePickerPageSize)")
                .contains("params.set('keyword', responsePickerAppliedSearch.value.trim())")
                .contains("params.set('contentType', 'SSE')")
                .contains("/api/admin/responses/summary?${params.toString()}")
                .contains("responsePickerResults.value = data.results || []")
                .contains("responsePickerTotalElements.value = Number(data.totalElements || 0)")
                .contains("responsePickerAbortController?.abort()");
    }

    @Test
    void offersUsefulNextStepsWhenTheResponsePickerIsEmpty() throws IOException {
        String component = resourceText("static/components/RuleEditModal.js");

        assertThat(component)
                .contains("class=\"response-picker-state response-picker-empty-state\"")
                .contains("class=\"response-picker-empty-actions\"")
                .contains("form.responseMode='new';closeResponsePicker();$emit('on-response-mode-change')")
                .contains("closeResponsePicker();$emit('go-to-responses','')")
                .doesNotContain("$emit('close');$emit('go-to-responses'")
                .contains("t('modal.createNewResponse')")
                .contains("t('modal.goToResponseManagement')");
    }

    @Test
    void appliesDensityTokensToRuleEditorChromeAndControls() throws IOException {
        String stylesheet = resourceText("static/style.css");

        assertThat(stylesheet)
                .contains("--control-h: 36px;")
                .contains("--control-h: 32px; --control-h-sm: 28px;")
                .contains("--control-h: 40px; --control-h-sm: 36px;")
                .contains("--editor-section-py: 6px; --editor-field-gap: 6px;")
                .contains("--editor-section-py: 14px; --editor-field-gap: 12px;")
                .contains("--editor-content-min-h: 380px;")
                .contains("--editor-content-min-h: 460px;")
                .contains("min-height: var(--control-h);")
                .contains("min-height: var(--modal-header-h);")
                .contains("min-height: var(--modal-footer-h);")
                .contains("padding-inline-end: var(--editor-pane-inline)")
                .contains("padding-inline-start: var(--editor-pane-inline)")
                .contains("min-height: var(--editor-selection-h)")
                .contains("padding: var(--editor-selection-py) var(--editor-selection-px)")
                .contains("padding-block: var(--editor-toolbar-padding)")
                .contains("gap: var(--editor-field-gap);")
                .contains("padding: var(--editor-field-gap) var(--editor-selection-px);")
                .contains("padding: 0 var(--editor-selection-px) var(--editor-field-gap);");
    }

    @Test
    void localizesRuleModeAndFaultPanelCopy() throws IOException {
        JsonNode zh = OBJECT_MAPPER.readTree(resourceText("static/i18n/zh-TW.json")).path("modal");
        JsonNode en = OBJECT_MAPPER.readTree(resourceText("static/i18n/en.json")).path("modal");

        assertThat(zh.path("ruleMode").asText()).isEqualTo("規則模式");
        assertThat(zh.path("faultAction").asText()).isEqualTo("故障注入");
        assertThat(zh.path("forwardAction").asText()).isEqualTo("轉發下游");
        assertThat(en.path("ruleMode").asText()).isEqualTo("Rule Mode");
        assertThat(en.path("faultAction").asText()).isEqualTo("Inject Fault");
        assertThat(en.path("forwardAction").asText()).isEqualTo("Forward Downstream");
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
