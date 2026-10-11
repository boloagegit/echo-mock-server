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
                .contains(":can-drag=\"canDragRules\"");
        assertThat(resourceText("static/components/RuleTable.js"))
                .contains("<td v-if=\"canDrag\" class=\"col-drag drag-handle-cell\" draggable=\"true\"");
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
        String table = resourceText("static/components/RuleTable.js");
        String page = resourceText("static/components/RulesPage.js");

        // Edit stays one click away; history, copy, export and delete live in the row's ⋯ menu.
        assertThat(table)
                .contains("@click=\"$emit('edit', r)\"")
                .contains("<ui-row-menu :items=\"menuItems(r)\" :label=\"t('common.moreActions') + ' ' + r.matchKey\"")
                .contains("{ key: 'history', label: t('rules.history')")
                .contains("{ key: 'copy', label: t('rules.quickCopy')")
                .contains("{ key: 'delete', label: t('rules.delete'), icon: 'bi-trash', danger: true")
                .doesNotContain("dblclick-hint");
        assertThat(page)
                .contains("if (action === 'copy') this.$emit('copy-rule', rule);")
                .contains("else if (action === 'history') this.$emit('show-rule-history', rule);")
                .contains("@menu=\"handleRowMenu\"")
                .doesNotContain("showDblClickHint");
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
