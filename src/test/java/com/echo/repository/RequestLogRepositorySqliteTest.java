package com.echo.repository;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:request-log-projection-test?mode=memory&cache=shared",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RequestLogRepositorySqliteTest {

    @Autowired
    private RequestLogRepository repository;

    @Test
    void summaryAndDetailQueries_shouldWorkWithLargeLobOnSqlite() {
        String largeBody = "大型內容".repeat(64 * 1024);
        RequestLog saved = repository.saveAndFlush(RequestLog.builder()
                .protocol(Protocol.HTTP)
                .method("POST")
                .endpoint("/sqlite-large")
                .matched(true)
                .responseTimeMs(7)
                .requestTime(LocalDateTime.now())
                .forwarded(true)
                .forwardTarget("safe-target | tcp://127.0.0.1:61616 | queue.out")
                .requestBody(largeBody)
                .responseBody(largeBody)
                .matchChain("[]")
                .build());

        List<Object[]> rows = repository.findSummaryProjections(PageRequest.of(0, 10));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).hasSize(23);
        assertThat(Arrays.asList(rows.get(0))).doesNotContain(largeBody);
        assertThat(rows.get(0)[11]).isEqualTo(true);
        assertThat(rows.get(0)[12]).isEqualTo("safe-target | tcp://127.0.0.1:61616 | queue.out");
        assertThat(rows.get(0)[20]).isEqualTo(true);
        assertThat(rows.get(0)[21]).isEqualTo(true);
        assertThat(rows.get(0)[22]).isEqualTo(true);
        assertThat(repository.findById(saved.getId()))
                .get()
                .extracting(RequestLog::getRequestBody, RequestLog::getResponseBody)
                .containsExactly(largeBody, largeBody);
    }

    @Test
    void pagedSummary_shouldFilterAndPaginateOnSqlite() {
        RequestLog first = repository.saveAndFlush(RequestLog.builder()
                .ruleId("sqlite-rule-1")
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint("/sqlite/page-one")
                .targetHost("sqlite.example")
                .matched(true)
                .responseTimeMs(20)
                .requestTime(LocalDateTime.now().minusSeconds(1))
                .build());
        RequestLog second = repository.saveAndFlush(RequestLog.builder()
                .ruleId("sqlite-rule-2")
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint("/sqlite/page-two")
                .matched(false)
                .responseTimeMs(10)
                .requestTime(LocalDateTime.now())
                .build());

        Page<Object[]> filtered = repository.findSummaryPage(null, Protocol.HTTP, false, "PAGE-TWO", null,
                PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "requestTime")));
        Page<Object[]> incremental = repository.findSummaryPage(null, null, null, null, first.getId(),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "requestTime")));

        assertThat(filtered.getTotalElements()).isEqualTo(1);
        assertThat(filtered.getContent()).extracting(row -> row[4]).containsExactly("/sqlite/page-two");
        assertThat(incremental.getContent()).extracting(row -> ((Number) row[0]).longValue())
                .containsExactly(second.getId());
    }
}
