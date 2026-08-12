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
