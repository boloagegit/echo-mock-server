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
        // List and group views share one drawer, so the forward target is rendered in RuleDetail only.
        String ruleDetail = resourceText("static/components/RuleDetail.js");

        assertThat(utils)
                .contains("const protocol = String(rule?.protocol || '').toUpperCase()")
                .contains("protocol === 'JMS'")
                .contains("rule?.jmsTargetConnectionId")
                .contains("rule?.httpTargetConnectionId")
                .contains("_t('modal.forwardSpecificConnection', { id })")
                .contains("const forwardTargetEndpoint = rule => rule?._forwardTargetEndpoint || ''")
                .contains("_t('modal.forwardOriginalHost')")
                .contains("'modal.forwardDefaultJmsConnection' : 'modal.forwardDefaultConnection'");
        assertThat(ruleDetail)
                .contains("forwardTargetLabel(view)")
                .contains("forwardTargetLabel, forwardTargetEndpoint")
                .contains("view._forwardTargetName")
                .contains("forwardTargetEndpoint(view)");
        String useRules = resourceText("static/composables/useRules.js");
        assertThat(useRules)
                .contains("const hydrateForwardTarget = async (data) =>")
                .contains("/api/admin/http-target-connections")
                .contains("/api/admin/jms-target-connections")
                .contains("data._forwardTargetName = target.name || ''")
                .contains("await hydrateForwardTarget(data)");
    }

    @Test
    void requestLogsUseExplicitForwardingMetadataAndProtocolAwareStatuses() throws IOException {
        String stats = resourceText("static/components/StatsPage.js");

        assertThat(stats)
                .contains("if (log.forwarded && log.proxyError) return this.t('stats.forwardFailed');")
                .contains("item.log.forwardTarget")
                .contains("if (log.protocol !== 'HTTP') { return null; }")
                .contains("<ui-detail-drawer class=\"log-detail-drawer\"");
        // Columns now collapse by available width (container queries) instead of a JS media query + colspan.
        assertThat(resourceText("static/console.css"))
                .contains("@container (max-width: 900px) { .log-table .col-duration { display: none } }");
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
        // Lists drop secondary columns by their own width (container queries), not viewport breakpoints.
        assertThat(resourceText("static/console.css"))
                .contains("@container (max-width: 900px) { .log-table .col-duration { display: none } }")
                .contains("@container (max-width: 720px) { .log-table .col-time { display: none }");
        assertThat(stylesheet)
                .doesNotContain(".rule-list-table .col-hide-md")
                .contains(".connection-test-outcome { display: block; font-size: var(--font-xs); font-variant-numeric: tabular-nums }");
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
