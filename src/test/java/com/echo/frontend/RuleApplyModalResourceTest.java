package com.echo.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RuleApplyModalResourceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void showsSystemManagedFieldsAsReadOnlyValuesBesideTheEditor() throws IOException {
        String component = resourceText("static/components/RuleApplyModal.js");

        assertThat(component)
                .contains("systemFields: { type: Object")
                .contains("class=\"rule-apply-system-area\"")
                .contains("t('rules.applySystemFields')")
                .contains("<dt>apiVersion</dt>")
                .contains("<dt>kind</dt>")
                .contains("<dt>metadata.id</dt>")
                .contains("<dt>metadata.resourceVersion</dt>")
                .contains("t('rules.applyFullStateWarning')");
    }

    @Test
    void protectsIdentityClientSideAndUsesAPathBoundUpdateEndpoint() throws IOException {
        String composable = resourceText("static/composables/useRuleApply.js");

        assertThat(composable)
                .contains("const ruleApplyIdentity = Vue.ref(null)")
                .contains("const enforceSystemFields = text =>")
                .contains("setValue(document, 'apiVersion'")
                .contains("setValue(document.metadata, 'id', ruleApplyIdentity.value.id)")
                .contains("'/api/admin/rules/' + encodeURIComponent(ruleId) + '/apply'")
                .contains("method: ruleId ? 'PUT' : 'POST'");
    }

    @Test
    void localizesReadOnlyAndFullReplacementGuidance() throws IOException {
        JsonNode zh = OBJECT_MAPPER.readTree(resourceText("static/i18n/zh-TW.json")).path("rules");
        JsonNode en = OBJECT_MAPPER.readTree(resourceText("static/i18n/en.json")).path("rules");

        assertThat(zh.path("applySystemFields").asText()).contains("唯讀");
        assertThat(zh.path("applyFullStateWarning").asText()).contains("完整覆蓋").contains("不是局部修改");
        assertThat(en.path("applySystemFields").asText()).contains("read-only");
        assertThat(en.path("applyFullStateWarning").asText()).contains("not a partial update");
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
