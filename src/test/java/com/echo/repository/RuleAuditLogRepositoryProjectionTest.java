package com.echo.repository;

import com.echo.entity.RuleAuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class RuleAuditLogRepositoryProjectionTest {

    @Autowired
    private RuleAuditLogRepository repository;

    @Test
    void pagedSummary_shouldExcludeLobsAndPreserveDetailOnH2() {
        String largeJson = "{\"body\":\"" + "x".repeat(512 * 1024) + "\"}";
        RuleAuditLog first = repository.save(audit("rule-one", RuleAuditLog.Action.CREATE,
                null, largeJson, "alice", LocalDateTime.now().minusSeconds(1)));
        repository.save(audit("response-2", RuleAuditLog.Action.UPDATE,
                largeJson, largeJson, "bob", LocalDateTime.now()));
        repository.flush();

        Page<Object[]> page = repository.findSummaryPage(RuleAuditLog.Action.CREATE, "ALI", "RULE-ONE",
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "timestamp")));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement().satisfies(row -> {
            assertThat(row).hasSize(7);
            assertThat(Arrays.asList(row)).doesNotContain(largeJson);
            assertThat(row[0]).isEqualTo(first.getId());
            assertThat(row[5]).isEqualTo(false);
            assertThat(row[6]).isEqualTo(true);
        });
        assertThat(repository.findById(first.getId()))
                .get().extracting(RuleAuditLog::getAfterJson).isEqualTo(largeJson);
    }

    private RuleAuditLog audit(String ruleId, RuleAuditLog.Action action, String beforeJson,
                               String afterJson, String operator, LocalDateTime timestamp) {
        return RuleAuditLog.builder()
                .ruleId(ruleId)
                .action(action)
                .beforeJson(beforeJson)
                .afterJson(afterJson)
                .operator(operator)
                .timestamp(timestamp)
                .build();
    }
}
