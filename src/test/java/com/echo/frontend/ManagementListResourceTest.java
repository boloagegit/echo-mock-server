package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ManagementListResourceTest {

    @Test
    void accountListUsesBoundedServerPagingFiltersAndSorting() throws IOException {
        String composable = resourceText("static/composables/useAccounts.js");
        String component = resourceText("static/components/AccountsPage.js");

        assertThat(composable)
                .contains("/api/admin/builtin-users/page?")
                .contains("params.set('size', String(accountPageSize.value))")
                .contains("roleFilter")
                .contains("enabledFilter")
                .contains("resetFilter")
                .contains("toggleAccountSort")
                .contains("new AbortController()");
        assertThat(component)
                .contains(":pagination-label=\"t('accounts.pagination')\"")
                .contains("accounts.toggleAccountSort('username')")
                .contains("accounts.clearAccountFilters()")
                .contains("t('accounts.neverLoggedIn')");
    }

    @Test
    void issueListUsesBoundedServerPagingSearchSortingAndOwnAccessibilityLabel() throws IOException {
        String composable = resourceText("static/composables/useIssues.js");
        String component = resourceText("static/components/IssuesPage.js");

        assertThat(composable)
                .contains("/api/admin/issues/page?")
                .contains("params.set('size', String(issuePageSize.value))")
                .contains("issueSort")
                .contains("new AbortController()")
                .doesNotContain("/api/admin/issues/count");
        assertThat(component)
                .contains("t('issues.searchPlaceholder')")
                .contains("$emit('toggle-issue-sort','createdAt')")
                .contains(":pagination-label=\"t('issues.pagination')\"")
                .doesNotContain(":pagination-label=\"t('stats.pagination')\"");
    }

    @Test
    void ruleSelectionIsClearedWhenTheVisibleResultSetChanges() throws IOException {
        String composable = resourceText("static/composables/useRules.js");
        String component = resourceText("static/components/RulesPage.js");

        assertThat(composable)
                .contains("const clearRuleSelection = () => { selectedRules.value = []; }")
                .contains("watch(ruleFilter, () => { clearRuleSelection();")
                .contains("watch(ruleSort, () => { clearRuleSelection();")
                .contains("watch(rulePage, () => {")
                .contains("watch(rulePageSize, () => {");
        assertThat(component)
                .contains("ruleFilter.mode")
                .contains("ruleFilter.expiring")
                .contains("r.updatedBy||t('rules.unknownOperator')")
                .contains("$emit('toggle-rule-sort', 'updatedAt')");
    }

    private static String resourceText(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
