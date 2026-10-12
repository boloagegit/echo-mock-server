package com.echo.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 進階條件（matchSpec）編輯器的接線：載入順序、元件註冊、儲存與宣告式文件的轉換、顯示與多語系。 */
class AdvancedMatchEditorResourceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void loadsTheTreeModelBeforeItsUsersAndRegistersTheEditor() throws IOException {
        String index = resourceText("static/index.html");
        int matchTree = index.indexOf("/match-tree.js");
        assertThat(matchTree).isPositive().isLessThan(index.indexOf("/utils.js"));
        assertThat(index.indexOf("/components/MatchTreeEditor.js")).isPositive()
                .isLessThan(index.indexOf("/components/RuleEditModal.js"));
        assertThat(resourceText("static/app.js")).contains("_app.component('match-tree-node', MatchTreeNode);");
        assertThat(resourceText("static/components/MatchTreeEditor.js"))
                .contains("name: 'MatchTreeNode'")
                .contains("<match-tree-node v-for=\"child in node.children\"");
    }

    @Test
    void modalSwitchesBetweenSimpleAndAdvancedWithoutDroppingConditions() throws IOException {
        String index = resourceText("static/index.html");
        assertThat(index).contains(":match-mode=\"matchMode\" :match-tree=\"matchTree\"")
                .contains("@set-match-mode=\"setMatchMode\" @confirm-match-simple=\"confirmSimpleMatchMode\"");
        String modal = resourceText("static/components/RuleEditModal.js");
        assertThat(modal).contains("'set-match-mode', 'confirm-match-simple', 'cancel-match-simple'")
                .contains("<match-tree-node :node=\"matchTree\" :sources=\"matchSources\" root>")
                .contains("v-if=\"matchSwitchPending\" class=\"match-switch-confirm\" role=\"alert\"")
                .contains("v-if=\"matchError\" class=\"match-error\" role=\"alert\"")
                // == 的提醒在簡易與進階模式都會出現
                .contains("? (props.matchTree ? MatchTree.doubleEqualsConditions(props.matchTree) : [])")
                .contains("MatchTree.hasDoubleEquals(c.field + (c.operator || '=') + (c.value || ''), c.type)");

        String form = resourceText("static/composables/useRuleForm.js");
        // 進階規則改用簡易模式且沒有條件時要明確清除；否則後端會保留原本的進階條件
        assertThat(form).contains("payload.matchSpec = hadAdvanced && noLegacy ? { all: [] } : null;")
                .contains("if (!strings || !MatchTree.toSimpleRows(strings.bodyCondition, strings.queryCondition, strings.headerCondition)) {")
                .contains("'/api/admin/rules/match/validate'")
                .contains("'/api/admin/rules/match/parse'");
        // 條件列與後端用同一套拆解規則；無法完整表示的舊版條件改以進階模式原樣顯示，不會在儲存時遺失
        assertThat(form).contains("const rows = MatchTree.toSimpleRows(r.bodyCondition, r.queryCondition, r.headerCondition);")
                .contains("if (r && MatchTree.toSimpleRows(r.bodyCondition, r.queryCondition, r.headerCondition) === null) {")
                .doesNotContain("for (const op of ['!=', '*=', '~=', '='])");
        assertThat(resourceText("static/composables/useRuleApply.js"))
                .contains("MatchTree.parseLegacyCondition(raw.trim())")
                .doesNotContain("['!=', '*=', '~=', '=']");
        String app = resourceText("static/app.js");
        assertThat(app).contains("matchSpec: currentMatchSpec(),")
                .contains("applyMatchDraft(draft.matchSpec, draft.conditions);");
    }

    @Test
    void declarativeDocumentsBecomeV2OnlyWhenTheyCarryMatch() throws IOException {
        String apply = resourceText("static/composables/useRuleApply.js");
        assertThat(apply).contains("document.spec.match != null ? API_V2 : API_V1")
                .contains("apiVersion: spec.match ? API_V2 : API_V1,")
                // 只有舊格式片段的進階條件寫回舊欄位，文件維持 v1
                .contains("const legacyStrings = matchSpec ? legacyStringsOf(matchSpec) : null;")
                .contains("issue(errors, 'MATCH_CONFLICT', 'spec.match');")
                .contains("'/api/admin/rules/schema?apiVersion=' + encodeURIComponent(API_V2)");
    }

    @Test
    void listAndDetailSummarizeStructuredConditions() throws IOException {
        String utils = resourceText("static/utils.js");
        assertThat(utils).contains("const hasMatchSpec = r =>")
                .contains("if (hasMatchSpec(r)) return matchSummary(r).count;")
                .contains("[{t:'advanced', v:summary.line, label:_t('rules.advancedMatch')}]");
        assertThat(resourceText("static/components/RuleDetail.js"))
                .contains("if (hasMatchSpec(this.view)) return condCount(this.view);")
                .contains("class=\"match-summary\"");
    }

    @Test
    void everyEditorLabelIsTranslatedInBothLocales() throws IOException {
        JsonNode zh = OBJECT_MAPPER.readTree(resourceText("static/i18n/zh-TW.json"));
        JsonNode en = OBJECT_MAPPER.readTree(resourceText("static/i18n/en.json"));
        List<String> zhKeys = keys(zh.path("match"), "");
        assertThat(zhKeys).containsExactlyInAnyOrderElementsOf(keys(en.path("match"), ""));
        for (String op : List.of("equals", "notEquals", "contains", "matches", "exists", "absent",
                "gt", "gte", "lt", "lte", "in", "equalToJson")) {
            assertThat(zhKeys).contains("op." + op);
        }
        assertThat(zh.path("match").path("legacyNotSimpleNote").asText()).isNotBlank();
        assertThat(zh.path("match").path("doubleEqualsWarning").asText()).contains("{conditions}");
        assertThat(en.path("match").path("doubleEqualsWarning").asText()).contains("{conditions}");
        for (String key : List.of("advancedMatch", "applyFieldSpecMatch", "applyValidationMatchConflict",
                "applyValidationMatch")) {
            assertThat(zh.path("rules").path(key).asText()).isNotBlank();
            assertThat(en.path("rules").path(key).asText()).isNotBlank();
        }
    }

    private static List<String> keys(JsonNode node, String prefix) {
        List<String> result = new ArrayList<>();
        node.properties().forEach(entry -> {
            if (entry.getValue().isObject()) {
                result.addAll(keys(entry.getValue(), prefix + entry.getKey() + "."));
            } else {
                result.add(prefix + entry.getKey());
            }
        });
        return result;
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
