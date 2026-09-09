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
        String component = resourceText("static/components/RuleListParts.js");

        assertThat(component)
                .contains("class=\"btn btn-sm btn-secondary rule-row-edit\"")
                .contains("class=\"rule-row-more\"")
                .contains("t('rules.moreActions')")
                .contains("class=\"rule-row-more-popover\"")
                .contains("invoke('toggle-rule-preview',$event)")
                .contains("invoke('show-rule-history',$event)")
                .contains("invoke('copy-rule',$event)")
                .doesNotContain("class=\"dblclick-hint\"");
        for (String page : new String[]{"RulesPage", "RuleGroupRow"}) {
            assertThat(resourceText("static/components/" + page + ".js"))
                    .contains("<rule-row-actions")
                    .contains("<rule-list-identity")
                    .contains("@show-rule-history=\"$emit('show-rule-history',$event)\"");
        }
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
