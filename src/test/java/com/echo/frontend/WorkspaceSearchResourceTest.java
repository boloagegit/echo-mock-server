package com.echo.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceSearchResourceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void submitModeKeepsDraftSeparateUntilEnterOrSearchButton() throws IOException {
        String component = resourceText("static/components/WorkspaceSearchField.js");

        assertThat(component)
                .contains("@submit.prevent=\"submitSearch\"")
                .contains("@keydown.enter.prevent=\"submitSearch\"")
                .contains("this.draftValue = event.target.value")
                .contains("if (!this.submitMode)")
                .contains("this.$emit('search', this.normalizedDraft)")
                .contains("type=\"submit\"")
                .contains(":disabled=\"searchUnchanged\"");
    }

    @Test
    void submitButtonIsASeparateControlWithStableSpacing() throws IOException {
        String component = resourceText("static/components/WorkspaceSearchField.js");
        String styles = resourceText("static/style.css");

        assertThat(component)
                .contains("<div class=\"workspace-search-input\">")
                .contains("class=\"workspace-search-submit\"");
        assertThat(styles)
                .contains(".workspace-search-submit-mode {")
                .contains("gap: var(--space-sm);")
                .contains(".workspace-search-input { position: relative; flex: 1; min-width: 0 }")
                .doesNotContain(".workspace-search-submit-mode .workspace-search-clear");
    }

    @Test
    void primaryWorkspaceListsUseExplicitSearchSubmission() throws IOException {
        assertExplicitSearch("static/components/RulesPage.js");
        assertExplicitSearch("static/components/ResponsesPage.js");
        assertExplicitSearch("static/components/StatsPage.js");
        assertExplicitSearch("static/components/AuditPage.js");
        assertExplicitSearch("static/components/AccountsPage.js");
    }

    @Test
    void searchActionIsLocalized() throws IOException {
        JsonNode zh = OBJECT_MAPPER.readTree(resourceText("static/i18n/zh-TW.json"));
        JsonNode en = OBJECT_MAPPER.readTree(resourceText("static/i18n/en.json"));

        assertThat(zh.path("common").path("searchAction").asText()).isEqualTo("搜尋");
        assertThat(en.path("common").path("searchAction").asText()).isEqualTo("Search");
    }

    private static void assertExplicitSearch(String path) throws IOException {
        assertThat(resourceText(path))
                .contains(":submit-mode=\"true\"")
                .contains(":submit-label=\"t('common.searchAction')\"")
                .contains("@search=");
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
