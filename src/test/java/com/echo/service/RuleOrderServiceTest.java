package com.echo.service;

import com.echo.config.JmsProperties;
import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 規則判斷順序與「一定輪不到」的規則（{@link RuleOrderAnalyzer}、{@link RuleOrderService}）。 */
class RuleOrderServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);

    private final HttpRuleService httpRuleService = mock(HttpRuleService.class);
    private final JmsRuleService jmsRuleService = mock(JmsRuleService.class);

    private static HttpRule http(String id, String method, String path, int priority, String body) {
        return HttpRule.builder().id(id).description("desc-" + id).method(method).matchKey(path)
                .priority(priority).bodyCondition(body).enabled(true).createdAt(T0).build();
    }

    private static JmsRule jms(String id, String queue, int priority, String body) {
        return JmsRule.builder().id(id).description("desc-" + id).queueName(queue).priority(priority)
                .bodyCondition(body).enabled(true).createdAt(T0).build();
    }

    private RuleOrderService service(List<HttpRule> httpRules, List<JmsRule> jmsRules, String endpointField) {
        when(httpRuleService.httpRuleSnapshot())
                .thenReturn(new HttpRuleSnapshot(httpRules, HttpRuleService.HTTP_RULE_ORDER, null));
        when(jmsRuleService.findAllJmsRulesCached()).thenReturn(jmsRules);
        JmsProperties properties = new JmsProperties();
        properties.setEndpointField(endpointField);
        return new RuleOrderService(httpRuleService, jmsRuleService, Optional.of(properties));
    }

    private RuleOrderService service(HttpRule... rules) {
        return service(List.of(rules), List.of(), null);
    }

    @Test
    void identicalConditionsAfterAnEarlierRuleAreNeverReached() {
        RuleOrderService service = service(
                http("vip", "POST", "/orders", 10, "type=vip"),
                http("big", "POST", "/orders", 5, "amount=1000"),
                http("vip-old", "POST", "/orders", 1, " type=vip "));

        RuleOrderService.IssuesView issues = service.issues();

        assertThat(issues.count()).isEqualTo(1);
        assertThat(issues.issues()).containsOnlyKeys("vip-old");
        assertThat(issues.issues().get("vip-old"))
                .isEqualTo(new RuleOrderService.IssueView(RuleOrderAnalyzer.DUPLICATE_CONDITIONS, "vip", "desc-vip",
                        "desc-vip-old", "POST /orders"));
    }

    @Test
    void aRuleWithoutConditionsDoesNotBlockLaterRulesWithConditions() {
        RuleOrderService service = service(
                http("default", "POST", "/orders", 10, null),
                http("vip", "POST", "/orders", 1, "type=vip"));

        assertThat(service.issues().count()).isZero();
    }

    @Test
    void onlyTheFirstRuleWithoutConditionsIsUsed() {
        RuleOrderService service = service(
                http("users", "GET", "/users/*", 0, null),
                http("users-v2", "GET", "/users/*", 0, ""));

        // 排序條件都相同時，與實際比對一樣維持規則來源的順序
        assertThat(service.issues().issues()).containsOnlyKeys("users-v2");
        assertThat(service.issues().issues().get("users-v2").type()).isEqualTo(RuleOrderAnalyzer.EARLIER_DEFAULT);
    }

    @Test
    void anEarlierRuleMustCoverEveryRequestOfTheLaterOne() {
        HttpRule hostOnly = http("host-only", "GET", "/stock", 10, "a=1");
        hostOnly.setTargetHost("api.internal");
        RuleOrderService service = service(List.of(
                // 前綴涵蓋精確路徑；method 未指定涵蓋所有 method
                http("prefix", null, "/orders/*", 10, "type=vip"),
                http("exact", "POST", "/orders/1", 5, "type=vip"),
                // 精確路徑不涵蓋前綴；指定 host 的規則不涵蓋未指定 host 的規則
                http("narrow", "GET", "/items/1", 10, "a=1"),
                http("wide", "GET", "/items/*", 5, "a=1"),
                hostOnly,
                http("any-host", "GET", "/stock", 5, "a=1")), List.of(), null);

        assertThat(service.issues().issues()).containsOnlyKeys("exact");
    }

    @Test
    void rulesThatOnlyApplyInAScenarioStateDoNotBlockOthers() {
        HttpRule gated = http("gated", "POST", "/orders", 10, "type=vip");
        gated.setScenarioName("checkout");
        gated.setRequiredScenarioState("Started");
        RuleOrderService service = service(gated, http("plain", "POST", "/orders", 5, "type=vip"));

        assertThat(service.issues().count()).isZero();
    }

    @Test
    void structuredConditionsCompareByContent() {
        HttpRule first = http("first", "POST", "/orders", 10, null);
        first.setMatchSpec("{\"source\":\"header\",\"name\":\"X-Region\",\"op\":\"equals\",\"value\":\"TW\"}");
        HttpRule reordered = http("reordered", "POST", "/orders", 5, null);
        reordered.setMatchSpec("{\"op\":\"equals\",\"value\":\"TW\",\"name\":\"X-Region\",\"source\":\"header\"}");
        HttpRule different = http("different", "POST", "/orders", 1, null);
        different.setMatchSpec("{\"source\":\"header\",\"name\":\"X-Region\",\"op\":\"equals\",\"value\":\"JP\"}");

        assertThat(service(first, reordered, different).issues().issues()).containsOnlyKeys("reordered");
    }

    @Test
    void orderViewListsCompetingRulesInCheckingOrderAndMarksTheDefault() {
        HttpRule gatedDefault = http("gated-default", "POST", "/orders", 20, null);
        gatedDefault.setScenarioName("checkout");
        gatedDefault.setRequiredScenarioState("Paid");
        RuleOrderService service = service(
                http("wildcard", null, "*", 0, null),
                http("default", "POST", "/orders", 0, null),
                http("vip", "POST", "/orders", 10, "type=vip"),
                gatedDefault,
                http("other-path", "POST", "/items", 50, "type=vip"),
                http("vip-old", "POST", "/orders", 1, "type=vip"));

        RuleOrderService.RuleOrderView view = service.orderFor("vip").orElseThrow();

        assertThat(view.protocol()).isEqualTo("HTTP");
        assertThat(view.wildcard()).isFalse();
        assertThat(view.entries()).extracting(RuleOrderService.Entry::id)
                .containsExactly("gated-default", "vip", "vip-old", "default", "wildcard");
        assertThat(view.entries()).filteredOn(RuleOrderService.Entry::self).extracting(RuleOrderService.Entry::id)
                .containsExactly("vip");
        RuleOrderService.Entry gated = view.entries().get(0);
        assertThat(gated.conditional()).isFalse();
        assertThat(gated.usedAsDefault()).isFalse();
        assertThat(gated.requiredScenarioState()).isEqualTo("Paid");
        assertThat(view.entries().get(2).issue().type()).isEqualTo(RuleOrderAnalyzer.DUPLICATE_CONDITIONS);
        assertThat(view.entries().get(3).usedAsDefault()).isTrue();
        // 萬用規則在其他路徑仍會用到，只在這個範圍標示用不到
        assertThat(view.entries().get(4).issue().type()).isEqualTo(RuleOrderService.NOT_USED_HERE);
        assertThat(service.issues().issues()).containsOnlyKeys("vip-old");

        assertThat(view.entries()).noneMatch(RuleOrderService.Entry::partial);

        RuleOrderService.RuleOrderView wildcard = service.orderFor("wildcard").orElseThrow();
        assertThat(wildcard.wildcard()).isTrue();
        assertThat(wildcard.entries()).extracting(RuleOrderService.Entry::id).containsExactly("wildcard");
    }

    @Test
    void rulesThatApplyToOnlySomeOfTheRequestsAreListedButNeverTheDefault() {
        HttpRule hostDefault = http("host-default", "GET", "*", 0, null);
        hostDefault.setTargetHost("default");
        HttpRule anyHostDefault = http("any-host-default", null, "*", 0, null);
        RuleOrderService service = service(http("users", null, "/users/*", 0, null), hostDefault, anyHostDefault);

        RuleOrderService.RuleOrderView view = service.orderFor("users").orElseThrow();

        assertThat(view.entries()).extracting(RuleOrderService.Entry::id)
                .containsExactlyInAnyOrder("users", "host-default", "any-host-default");
        RuleOrderService.Entry partial = view.entries().stream().filter(e -> e.id().equals("host-default"))
                .findFirst().orElseThrow();
        assertThat(partial.partial()).isTrue();
        assertThat(partial.usedAsDefault()).isFalse();
        assertThat(partial.issue().type()).isEqualTo(RuleOrderService.NOT_USED_HERE);
        assertThat(view.entries().get(0).id()).isEqualTo("users");
        assertThat(view.entries().get(0).usedAsDefault()).isTrue();
        // 只適用部分請求的規則不會被判定輪不到；涵蓋範圍不同的萬用規則也不會互相判定
        assertThat(service.issues().issues()).isEmpty();
    }

    @Test
    void disabledRulesAreShownWithTheRulesTheyWouldCompeteWith() {
        HttpRule disabled = http("off", "POST", "/orders", 50, "type=vip");
        disabled.setEnabled(false);
        RuleOrderService service = service(http("vip", "POST", "/orders", 10, "type=vip"));
        when(httpRuleService.findHttpRuleById("off")).thenReturn(Optional.of(disabled));

        RuleOrderService.RuleOrderView view = service.orderFor("off").orElseThrow();

        assertThat(view.entries()).extracting(RuleOrderService.Entry::id).containsExactly("off", "vip");
        assertThat(view.entries().get(0).enabled()).isFalse();
        assertThat(view.entries().get(0).issue()).isNull();
        assertThat(service.orderFor("missing")).isEmpty();
    }

    @Test
    void jmsUsesQueuesAndEndpointBuckets() {
        RuleOrderService service = service(List.of(), List.of(
                jms("pay", "ORDER", 10, "ServiceName=Pay"),
                jms("pay-copy", "ORDER", 5, "ServiceName=Pay"),
                jms("any-default", "*", 10, null),
                jms("queue-default", "ORDER", 0, null),
                jms("wild-default-2", "*", 0, null)), "ServiceName");

        assertThat(service.issues().issues()).containsOnlyKeys("pay-copy", "wild-default-2");

        RuleOrderService.RuleOrderView view = service.orderFor("queue-default").orElseThrow();
        assertThat(view.protocol()).isEqualTo("JMS");
        // queue 指定的規則排在萬用 queue 之前；限定 ServiceName=Pay 的規則只適用部分訊息
        assertThat(view.entries()).extracting(RuleOrderService.Entry::id)
                .containsExactly("pay", "pay-copy", "queue-default", "any-default", "wild-default-2");
        assertThat(view.entries().get(0).partial()).isTrue();
        assertThat(view.entries().get(2).usedAsDefault()).isTrue();
        assertThat(view.entries().get(3).issue().type()).isEqualTo(RuleOrderService.NOT_USED_HERE);
    }

    @Test
    void jmsRulesLimitedToAnotherEndpointValueDoNotCoverOthers() {
        RuleOrderService service = service(List.of(), List.of(
                jms("pay", "ORDER", 10, "ServiceName=Pay;amount=1"),
                jms("all", "ORDER", 5, "amount=1")), "ServiceName");

        assertThat(service.issues().count()).isZero();
    }

    @Test
    void issuesAreReusedUntilTheRulesChange() {
        RuleOrderService service = service(http("a", "GET", "/a", 1, null), http("b", "GET", "/a", 0, null));
        RuleOrderService.IssuesView first = service.issues();
        assertThat(service.issues()).isSameAs(first);

        when(httpRuleService.httpRuleSnapshot()).thenReturn(new HttpRuleSnapshot(
                List.of(http("a", "GET", "/a", 1, null)), HttpRuleService.HTTP_RULE_ORDER, null));
        assertThat(service.issues()).isNotSameAs(first);
        assertThat(service.issues().count()).isZero();
    }
}
