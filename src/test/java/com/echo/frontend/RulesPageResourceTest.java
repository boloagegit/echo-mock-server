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
    void dragSortingWasRemoved() throws IOException {
        // 拖曳排序已移除：它只送出優先順序，整筆更新 API 一律拒絕，從未能正常運作。優先順序在編輯畫面設定。
        for (String file : new String[] {"static/composables/useRules.js", "static/components/RulesPage.js",
                "static/components/RuleTable.js", "static/app.js", "static/index.html"}) {
            assertThat(resourceText(file)).doesNotContainIgnoringCase("drag");
        }
        JsonNode zh = OBJECT_MAPPER.readTree(resourceText("static/i18n/zh-TW.json"));
        JsonNode en = OBJECT_MAPPER.readTree(resourceText("static/i18n/en.json"));
        assertThat(zh.path("rules").has("dragRule")).isFalse();
        assertThat(en.path("toast").has("sortUpdated")).isFalse();
        assertThat(resourceText("application.yml")).doesNotContain("rule-drag-sort-enabled");
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
