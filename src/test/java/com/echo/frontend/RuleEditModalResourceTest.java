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
    void exposesMockForwardAndFaultAsMutuallyExclusiveRuleModes() throws IOException {
        String component = resourceText("static/components/RuleEditModal.js");
        int modeOptionsStart = component.indexOf("<div class=\"rule-outcome-options\"");
        int modeOptionsEnd = component.indexOf("</fieldset>", modeOptionsStart);

        assertThat(component)
                .contains("name=\"ruleMode\" value=\"MOCK\"")
                .contains("name=\"ruleMode\" value=\"FORWARD\"")
                .contains("name=\"ruleMode\" value=\"FAULT\"")
                .contains("v-model=\"ruleMode\"")
                .doesNotContain("name=\"ruleAction\"");
        assertThat(modeOptionsStart).isGreaterThanOrEqualTo(0);
        assertThat(modeOptionsEnd).isGreaterThan(modeOptionsStart);
        assertThat(component.substring(modeOptionsStart, modeOptionsEnd)).doesNotContain("<i ");
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
        int faultPanel = component.indexOf("<template v-else-if=\"ruleMode==='FAULT'\">");
        int faultType = component.indexOf("id=\"ruleFaultType\"", faultPanel);
        int mockPanel = component.indexOf("<template v-else>", faultPanel);

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
    void keepsPaneScrollingWhileHidingScrollbarsAroundTheSplitter() throws IOException {
        String stylesheet = resourceText("static/style.css");

        assertThat(stylesheet)
                .contains("scrollbar-gutter: auto;")
                .contains("scrollbar-width: none;")
                .contains(".rule-left::-webkit-scrollbar")
                .contains(".rule-right::-webkit-scrollbar { display: none; width: 0; height: 0 }")
                .contains("flex: 0 0 12px;")
                .contains(".rule-splitter::before")
                .doesNotContain("scrollbar-gutter: stable both-edges;")
                .doesNotContain(".rule-splitter::after");
    }

    @Test
    void rendersExistingResponseSelectionBeforeTheServerPagedDrawer() throws IOException {
        String component = resourceText("static/components/RuleEditModal.js");
        int existingPanel = component.indexOf("class=\"response-existing-panel\"");
        int selectedSummary = component.indexOf("class=\"response-selected-card\"", existingPanel);
        int drawerLaunch = component.indexOf("class=\"btn btn-sm btn-secondary response-picker-change\"", existingPanel);
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
    void appliesDensityTokensToRuleEditorChromeAndControls() throws IOException {
        String stylesheet = resourceText("static/style.css");

        assertThat(stylesheet)
                .contains("--control-h: 38px;")
                .contains("--control-h: 34px; --control-h-sm: 30px;")
                .contains("--control-h: 42px; --control-h-sm: 36px;")
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
