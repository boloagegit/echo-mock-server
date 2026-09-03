package com.echo.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RulesPageResourceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void gatesDragSortingWithTheDeploymentFeatureFlag() throws IOException {
        String composable = resourceText("static/composables/useRules.js");

        assertThat(composable)
                .contains("ruleDragSortEnabled?.value === true")
                .contains("watch(ruleDragSortEnabled, enabled =>")
                .contains("ruleSort.value = { field: 'priority', asc: false }");
    }

    @Test
    void removesTheUserToggleAndOnlyShowsHandlesWhenTheFeatureIsAvailable() throws IOException {
        String component = resourceText("static/components/RulesPage.js");

        assertThat(component)
                .doesNotContain("rule-drag-toggle")
                .doesNotContain("update:ruleDragEnabled")
                .contains("<td v-if=\"canDragRules\" class=\"drag-handle-cell\"");
    }

    @Test
    void localizesTheDragHandleLabelWithoutExposingAUserToggle() throws IOException {
        JsonNode zh = OBJECT_MAPPER.readTree(resourceText("static/i18n/zh-TW.json")).path("rules");
        JsonNode en = OBJECT_MAPPER.readTree(resourceText("static/i18n/en.json")).path("rules");

        assertThat(zh.path("dragRule").asText()).isNotBlank();
        assertThat(en.path("dragRule").asText()).isNotBlank();
        assertThat(zh.has("dragSort")).isFalse();
        assertThat(en.has("dragSort")).isFalse();
    }

    @Test
    void prioritizesEditingAndKeepsSecondaryRowActionsInACompactDisclosure() throws IOException {
        String component = resourceText("static/components/RulesPage.js");

        assertThat(component)
                .contains("class=\"btn btn-sm btn-secondary rule-row-edit\"")
                .contains("class=\"rule-row-more\"")
                .contains("t('rules.moreActions')")
                .contains("class=\"rule-row-more-popover\"")
                .contains("$emit('toggle-rule-preview', r)")
                .contains("$emit('show-rule-history', r)")
                .contains("$emit('copy-rule', r)")
                .doesNotContain("class=\"dblclick-hint\"");
    }

    @Test
    void givesTheEmptyRuleStateAnActionAndUsesTheRulePaginationLabel() throws IOException {
        String component = resourceText("static/components/RulesPage.js");
        JsonNode zh = OBJECT_MAPPER.readTree(resourceText("static/i18n/zh-TW.json")).path("rules");
        JsonNode en = OBJECT_MAPPER.readTree(resourceText("static/i18n/en.json")).path("rules");

        assertThat(component)
                .contains("t('rules.createFirstRule')")
                .contains("@click=\"$emit('open-create')\"")
                .contains(":pagination-label=\"t('rules.pagination')\"")
                .doesNotContain(":pagination-label=\"t('stats.pagination')\"");
        assertThat(zh.path("pagination").asText()).isEqualTo("規則分頁");
        assertThat(en.path("pagination").asText()).isEqualTo("Rule pagination");
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
