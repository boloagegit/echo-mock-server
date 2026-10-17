package com.echo.repository;

import com.echo.entity.RuleAuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:audit-log-projection-test?mode=memory&cache=shared",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.jpa.database-platform=com.echo.config.EchoSqliteDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RuleAuditLogRepositorySqliteTest {

    @Autowired
    private RuleAuditLogRepository repository;

    @Test
    void pagedSummary_shouldExcludeLobsAndPreserveDetailOnSqlite() {
        String largeJson = "{\"body\":\"" + "資料".repeat(256 * 1024) + "\"}";
        RuleAuditLog saved = repository.saveAndFlush(RuleAuditLog.builder()
                .ruleId("sqlite-rule")
                .action(RuleAuditLog.Action.UPDATE)
                .beforeJson(largeJson)
                .afterJson(largeJson)
                .operator("sqlite-admin")
                .timestamp(LocalDateTime.now())
                .build());

        Page<Object[]> page = repository.findSummaryPage(RuleAuditLog.Action.UPDATE, "ADMIN", "SQLITE", null, null,
                PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "timestamp")));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement().satisfies(row -> {
            assertThat(row).hasSize(11);
            assertThat(Arrays.asList(row)).doesNotContain(largeJson);
            assertThat(row[5]).isEqualTo(true);
            assertThat(row[6]).isEqualTo(true);
        });
        assertThat(repository.findById(saved.getId()))
                .get().extracting(RuleAuditLog::getBeforeJson, RuleAuditLog::getAfterJson)
                .containsExactly(largeJson, largeJson);
    }

    @Test
    void summaryColumns_shouldDriveSearchTypeFilterAndBackfillOnSqlite() {
        RuleAuditLog rule = repository.saveAndFlush(RuleAuditLog.builder()
                .ruleId("rule-search").action(RuleAuditLog.Action.UPDATE)
                .beforeJson("{}").afterJson("{}").operator("admin").timestamp(LocalDateTime.now())
                .targetProtocol("HTTP").targetName("/users/1").targetDescription("取得使用者")
                .summary("{\"changeCount\":1}")
                .build());
        RuleAuditLog response = repository.saveAndFlush(RuleAuditLog.builder()
                .ruleId("response-9").action(RuleAuditLog.Action.CREATE)
                .afterJson("{}").operator("admin").timestamp(LocalDateTime.now())
                .targetProtocol("RESPONSE").targetName("使用者回應").summary("{}")
                .build());
        RuleAuditLog legacy = repository.saveAndFlush(RuleAuditLog.builder()
                .ruleId("rule-legacy").action(RuleAuditLog.Action.CREATE)
                .afterJson("{}").operator("admin").timestamp(LocalDateTime.now())
                .build());
        PageRequest page = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "timestamp"));

        assertThat(repository.findSummaryPage(null, null, "USERS/1", null, null, page).getContent())
                .extracting(row -> row[0]).containsExactly(rule.getId());
        assertThat(repository.findSummaryPage(null, null, "取得", null, null, page).getContent())
                .extracting(row -> row[0]).containsExactly(rule.getId());
        assertThat(repository.findSummaryPage(null, null, null, "RESPONSE", null, page).getContent())
                .extracting(row -> row[0]).containsExactly(response.getId());
        assertThat(repository.findSummaryPage(null, null, null, "RULE", null, page).getContent())
                .extracting(row -> row[0]).containsExactlyInAnyOrder(rule.getId(), legacy.getId());

        assertThat(repository.findSummaryPage(null, null, null, null, "response-9", page).getContent())
                .extracting(row -> row[0]).containsExactly(response.getId());
        assertThat(repository.findSummaryPage(null, null, null, null, "response-", page).getContent()).isEmpty();

        assertThat(repository.findIdsWithoutSummary(page)).containsExactly(legacy.getId());
        assertThat(repository.fillSummary(legacy.getId(), "HTTP", "/legacy", null, "{}")).isEqualTo(1);
        assertThat(repository.fillSummary(legacy.getId(), "HTTP", "/other", null, "{}")).isZero();
        assertThat(repository.findIdsWithoutSummary(page)).isEmpty();
        assertThat(repository.findById(legacy.getId())).get()
                .extracting(RuleAuditLog::getTargetName).isEqualTo("/legacy");
    }
}
