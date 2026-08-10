package com.echo.service;

import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import com.echo.repository.HttpRuleRepository;
import com.echo.repository.JmsRuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:rule-page-test?mode=memory&cache=shared",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "echo.jms.enabled=true"
})
@Import(RuleQueryService.class)
class RuleQueryServiceSqliteTest {

    @Autowired
    private RuleQueryService service;

    @Autowired
    private HttpRuleRepository httpRepository;

    @Autowired
    private JmsRuleRepository jmsRepository;

    @Test
    void query_shouldFilterAndMergeProtocolsOnSqlite() {
        httpRepository.saveAndFlush(HttpRule.builder()
                .id("sqlite-http")
                .matchKey("/sqlite/orders")
                .method("GET")
                .description("production payment")
                .enabled(true)
                .isProtected(false)
                .priority(20)
                .build());
        jmsRepository.saveAndFlush(JmsRule.builder()
                .id("sqlite-jms")
                .queueName("SQLITE.ORDER")
                .description("production payment")
                .enabled(true)
                .isProtected(false)
                .priority(10)
                .build());

        RuleQueryService.RuleQueryResult result = service.query(new RuleQueryService.RuleQuery(
                null, true, false, "production payment", 0, 1, "priority", "desc"));

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.rules()).extracting(r -> r.getId()).containsExactly("sqlite-http");
    }

    @Test
    void groupedQueries_shouldRemainExactOnSqliteWithJsonWhitespace() {
        httpRepository.saveAndFlush(HttpRule.builder()
                .id("sqlite-tagged")
                .matchKey("/sqlite/tagged")
                .method("GET")
                .tags("{\"env\": \"prod\", \"team\": \"payment\"}")
                .enabled(true)
                .isProtected(false)
                .priority(20)
                .build());
        httpRepository.saveAndFlush(HttpRule.builder()
                .id("sqlite-untagged")
                .matchKey("/sqlite/untagged")
                .method("GET")
                .enabled(true)
                .isProtected(false)
                .priority(10)
                .build());

        RuleQueryService.RuleQuery query = new RuleQueryService.RuleQuery(
                com.echo.entity.Protocol.HTTP, true, false, null,
                0, 20, "priority", "desc");
        RuleQueryService.RuleGroupSummary summary = service.queryGroupSummary(query);
        RuleQueryService.RuleGroupContent content = service.queryGroup(query, "env", "prod", 20);

        assertThat(summary.counts()).containsEntry("_untagged", 1L)
                .containsEntry("env=prod", 1L)
                .containsEntry("team=payment", 1L);
        assertThat(content.rules()).extracting(r -> r.getId()).containsExactly("sqlite-tagged");
    }
}
