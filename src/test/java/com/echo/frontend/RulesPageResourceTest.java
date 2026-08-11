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
    void keepsDragSortingOffUntilTheUserEnablesIt() throws IOException {
        String composable = resourceText("static/composables/useRules.js");

        assertThat(composable)
                .contains("const ruleDragEnabled = ref(false)")
                .contains("ruleDragEnabled.value && ruleDragAvailable.value")
                .contains("const setRuleDragEnabled = enabled =>")
                .contains("ruleSort.value = { field: 'priority', asc: false }");
    }

    @Test
    void rendersAnAccessibleDragSortToggleAndOnlyShowsHandlesWhenEnabled() throws IOException {
        String component = resourceText("static/components/RulesPage.js");

        assertThat(component)
                .contains("class=\"rule-drag-toggle\"")
                .contains(":checked=\"ruleDragEnabled\"")
                .contains(":disabled=\"!ruleDragAvailable\"")
                .contains("@change=\"$emit('update:ruleDragEnabled', $event.target.checked)\"")
                .contains("<td v-if=\"canDragRules\" class=\"drag-handle-cell\"");
    }

    @Test
    void localizesDragSortControls() throws IOException {
        JsonNode zh = OBJECT_MAPPER.readTree(resourceText("static/i18n/zh-TW.json")).path("rules");
        JsonNode en = OBJECT_MAPPER.readTree(resourceText("static/i18n/en.json")).path("rules");

        assertThat(zh.path("dragSort").asText()).isEqualTo("拖曳排序");
        assertThat(zh.path("dragSortHint").asText()).isNotBlank();
        assertThat(en.path("dragSort").asText()).isEqualTo("Drag to Sort");
        assertThat(en.path("dragSortHint").asText()).isNotBlank();
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
