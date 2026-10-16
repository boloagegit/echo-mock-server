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
    void searchAppliesAfterAPauseOrOnEnterWithoutResendingTheSameKeyword() throws IOException {
        String component = resourceText("static/components/WorkspaceSearchField.js");

        // Lists filter as you type, but each keystroke does not hit the server.
        assertThat(component)
                .contains("debounceMs: { type: Number, default: 350 }")
                .contains("this.searchTimer = setTimeout(() => this.submitSearch(), this.debounceMs);")
                .contains("@keydown.enter.prevent=\"submitSearch\"")
                .contains("if (!this.submitMode || this.searchUnchanged) { return; }")
                .contains("this.$emit('search', this.normalizedDraft)")
                .contains("beforeUnmount() {\n    clearTimeout(this.searchTimer);")
                .doesNotContain("type=\"submit\"")
                .doesNotContain("workspace-search-submit");
    }

    @Test
    void searchFieldUsesTheSharedControlHeight() throws IOException {
        String component = resourceText("static/components/WorkspaceSearchField.js");
        String styles = resourceText("static/style.css");

        assertThat(component)
                .contains("<div class=\"workspace-search-input\">")
                .contains("class=\"form-control form-control-sm\"")
                .contains("@keydown.esc=");
        assertThat(styles)
                .contains(".workspace-search-input { position: relative; flex: 1; min-width: 0 }")
                .contains(".workspace-search-field .form-control { height: var(--control-h-sm); min-height: var(--control-h-sm) }")
                .doesNotContain(".workspace-search-submit-mode .workspace-search-clear")
                .doesNotContain(".workspace-filter-bar .workspace-search-submit,")
                .doesNotContain(".workspace-filter-controls > .btn { height: var(--toolbar-h)");
    }

    @Test
    void primaryWorkspaceListsApplyTheirKeywordThroughTheSharedSearchEvent() throws IOException {
        assertExplicitSearch("static/components/RulesPage.js");
        assertExplicitSearch("static/components/ResponsesPage.js");
        assertExplicitSearch("static/components/StatsPage.js");
        assertExplicitSearch("static/components/AuditPage.js");
        assertExplicitSearch("static/components/AccountsPage.js");
        assertExplicitSearch("static/components/IssuesPage.js");
    }

    @Test
    void searchActionIsLocalized() throws IOException {
        JsonNode zh = OBJECT_MAPPER.readTree(resourceText("static/i18n/zh-TW.json"));
        JsonNode en = OBJECT_MAPPER.readTree(resourceText("static/i18n/en.json"));

        assertThat(zh.path("common").path("searchAction").asText()).isEqualTo("搜尋");
        assertThat(en.path("common").path("searchAction").asText()).isEqualTo("Search");
    }

    @Test
    void stickyTableHeadersUseAnOpaqueThemeSurface() throws IOException {
        String styles = resourceText("static/style.css");
        String theme = resourceText("static/theme.css");

        assertThat(styles)
                .contains(".card-table table { border-collapse: separate; border-spacing: 0 }")
                .contains(".card-table thead {")
                .contains(".card-table th {")
                .contains("background: var(--table-head);")
                .contains("background-clip: padding-box;")
                .contains("z-index: 4;")
                .contains("position: static;");
        assertThat(theme)
                .contains("--table-head: #14171b;")
                .contains("--table-head: #ffffff;");
    }

    private static void assertExplicitSearch(String path) throws IOException {
        assertThat(resourceText(path))
                .contains(":submit-mode=\"true\"")
                .doesNotContain(":submit-label=")
                .contains("@search=");
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
