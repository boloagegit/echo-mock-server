package com.echo.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class I18nCopyResourceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void keepsTraditionalChineseAndEnglishTranslationKeysAligned() throws IOException {
        Map<String, String> zh = flattenedMessages("static/i18n/zh-TW.json");
        Map<String, String> en = flattenedMessages("static/i18n/en.json");

        assertThat(zh.keySet()).containsExactlyInAnyOrderElementsOf(en.keySet());
    }

    @Test
    void usesConsistentTraditionalChineseProductTerminology() throws IOException {
        JsonNode zh = OBJECT_MAPPER.readTree(resourceText("static/i18n/zh-TW.json"));

        assertThat(zh.at("/modal/mockResponseAction").asText()).isEqualTo("模擬回應");
        assertThat(zh.at("/modal/mockSettingsTitle").asText()).isEqualTo("模擬回應設定");
        assertThat(zh.at("/modal/condFieldBody").asText()).isEqualTo("內容");
        assertThat(zh.at("/modal/condFieldQuery").asText()).isEqualTo("查詢參數");
        assertThat(zh.at("/modal/condFieldHeader").asText()).isEqualTo("標頭");
        assertThat(zh.at("/modal/matchQueue").asText()).isEqualTo("匹配佇列");
        assertThat(zh.at("/modal/replyQueue").asText()).isEqualTo("回覆佇列");
        assertThat(zh.at("/settings/sessionTimeout").asText()).isEqualTo("工作階段逾時");
        assertThat(zh.at("/settings/agentStatus").asText()).isEqualTo("代理程式狀態");
        assertThat(zh.at("/sidebar/workspace").asText()).isEqualTo("工作區");
    }

    @Test
    void doesNotReintroduceMixedLanguageInterfacePhrases() throws IOException {
        String zh = resourceText("static/i18n/zh-TW.json");

        assertThat(zh)
                .doesNotContain("Mock 回應")
                .doesNotContain("Mock 規則")
                .doesNotContain("Mock 請求")
                .doesNotContain("Reply Queue")
                .doesNotContain("Request Body")
                .doesNotContain("Request Headers")
                .doesNotContain("Session 逾時")
                .doesNotContain("Agent 狀態")
                .doesNotContain("Scenario 名稱")
                .doesNotContain("Queue 名稱")
                .doesNotContain("回應 Headers");
    }

    @Test
    void routesSharedNavigationAndControlCopyThroughI18n() throws IOException {
        String sidebar = resourceText("static/components/SidebarNav.js");
        String audit = resourceText("static/components/AuditPage.js");
        String settings = resourceText("static/components/SettingsPage.js");
        String ruleEditor = resourceText("static/components/RuleEditModal.js");

        assertThat(sidebar)
                .doesNotContain(">WORKSPACE<")
                .doesNotContain(">PREFERENCES<")
                .contains("t('sidebar.workspace')")
                .contains("t('sidebar.preferences')");
        assertThat(audit)
                .doesNotContain(">CREATE<")
                .doesNotContain(">UPDATE<")
                .doesNotContain(">DELETE<");
        assertThat(settings)
                .doesNotContain(">None<")
                .doesNotContain(">Basic<")
                .contains("t('settings.authBearerToken')");
        assertThat(ruleEditor)
                .doesNotContain(" : 'Queue'")
                .doesNotContain("placeholder=\"Header\"")
                .contains("t('modal.condPlaceholderHeader')");
    }

    private static Map<String, String> flattenedMessages(String path) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(resourceText(path));
        Map<String, String> result = new LinkedHashMap<>();
        flatten(root, "", result);
        return result;
    }

    private static void flatten(JsonNode node, String prefix, Map<String, String> result) {
        node.properties().forEach(entry -> {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue().isObject()) {
                flatten(entry.getValue(), key, result);
            } else {
                result.put(key, entry.getValue().asText());
            }
        });
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
