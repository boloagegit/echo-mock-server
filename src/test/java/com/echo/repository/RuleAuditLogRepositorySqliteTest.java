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
        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
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

        Page<Object[]> page = repository.findSummaryPage(RuleAuditLog.Action.UPDATE, "ADMIN", "SQLITE",
                PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "timestamp")));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement().satisfies(row -> {
            assertThat(row).hasSize(7);
            assertThat(Arrays.asList(row)).doesNotContain(largeJson);
            assertThat(row[5]).isEqualTo(true);
            assertThat(row[6]).isEqualTo(true);
        });
        assertThat(repository.findById(saved.getId()))
                .get().extracting(RuleAuditLog::getBeforeJson, RuleAuditLog::getAfterJson)
                .containsExactly(largeJson, largeJson);
    }
}
