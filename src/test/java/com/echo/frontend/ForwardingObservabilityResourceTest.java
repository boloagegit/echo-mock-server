package com.echo.frontend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ForwardingObservabilityResourceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void rulePreviewUsesTheConnectionIdForItsProtocol() throws IOException {
        String utils = resourceText("static/utils.js");
        String rules = resourceText("static/components/RulesPage.js");
        String groupedRules = resourceText("static/components/RuleGroupRow.js");

        assertThat(utils)
                .contains("const protocol = String(rule?.protocol || '').toUpperCase()")
                .contains("protocol === 'JMS'")
                .contains("rule?.jmsTargetConnectionId")
                .contains("rule?.httpTargetConnectionId")
                .contains("_t('modal.forwardOriginalHost')")
                .contains("'modal.forwardDefaultJmsConnection' : 'modal.forwardDefaultConnection'");
        assertThat(rules).contains("forwardTargetLabel(rulePreviewCache[r.id])");
        assertThat(groupedRules).contains("forwardTargetLabel(rulePreviewCache[rule.id])");
    }

    @Test
    void requestLogsUseExplicitForwardingMetadataAndProtocolAwareStatuses() throws IOException {
        String stats = resourceText("static/components/StatsPage.js");

        assertThat(stats)
                .contains("item.log.forwarded && item.log.proxyError")
                .contains("item.log.forwardTarget")
                .contains("if (log.protocol !== 'HTTP') { return null; }")
                .contains(":colspan=\"logDetailColspan\"")
                .contains("window.matchMedia('(max-width: 1024px)')");
    }

    @Test
    void forwardingLabelsAndResponsiveStylesAreAvailableInBothLanguages() throws IOException {
        var en = OBJECT_MAPPER.readTree(resourceText("static/i18n/en.json")).path("stats");
        var zh = OBJECT_MAPPER.readTree(resourceText("static/i18n/zh-TW.json")).path("stats");
        String stylesheet = resourceText("static/style.css");

        assertThat(en.path("detailForwardTarget").asText()).isNotBlank();
        assertThat(zh.path("detailForwardTarget").asText()).isNotBlank();
        assertThat(en.path("detailForwarded").asText()).isNotBlank();
        assertThat(zh.path("detailForwarded").asText()).isNotBlank();
        assertThat(stylesheet)
                .contains("@media (max-width: 1280px)")
                .contains(".rule-list-table .col-hide-md")
                .contains("@media (max-width: 1200px)")
                .contains(".connection-test-result { margin-top: var(--space-xs); overflow-wrap: anywhere }");
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
