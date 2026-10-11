package com.echo.service;

import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 規則快照必須與原本逐次查詢的結果完全一致，且大小不隨用戶端輸入（host、method、endpoint 值）成長。
 * 原本的篩選邏輯以 {@link #legacyHttpCandidates} 與 {@link #legacyBucket} 原樣保留作為標準答案。
 */
class RuleSnapshotTest {

    private static final List<String> HOSTS = List.of("api.example.com", "API.example.com", "pay.example.com", "");
    private static final List<String> METHODS = List.of("GET", "get", "POST", "DELETE");
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 0, 0);

    // ==================== HTTP ====================

    @Property(tries = 1000)
    void httpSnapshotMatchesLegacyLookup(@ForAll("httpRules") List<HttpRule> rules,
                                         @ForAll("requestHosts") String host,
                                         @ForAll("requestMethods") String method) {
        HttpRuleSnapshot snapshot = new HttpRuleSnapshot(rules, HttpRuleService.HTTP_RULE_ORDER, null);

        // 先用其他請求填入快取，確認共用 key 不會讓結果互相污染
        snapshot.candidates("unlisted.example", "PATCH");
        snapshot.candidates(host, "OPTIONS");

        assertThat(ids(snapshot.candidates(host, method))).isEqualTo(ids(legacyHttpCandidates(rules, host, method)));
    }

    @Test
    void httpSnapshotDoesNotGrowWithArbitraryHostsAndMethods() {
        List<HttpRule> rules = List.of(
                http("api.example.com", "GET", "/a", 0, 0),
                http(null, "POST", "/b", 0, 1),
                http("", null, "*", 0, 2));
        HttpRuleSnapshot snapshot = new HttpRuleSnapshot(rules, HttpRuleService.HTTP_RULE_ORDER, null);

        for (int i = 0; i < 10_000; i++) {
            snapshot.candidates("host-" + i + ".example", "METHOD" + i);
            snapshot.candidates("api.example.com", "X" + i);
        }

        // (2 種 host key) × (GET、POST、未列出 3 種 method key) 的上限，實際只用到其中幾個
        assertThat(snapshot.candidateSetCount()).isLessThanOrEqualTo(6);
    }

    @Provide
    Arbitrary<List<HttpRule>> httpRules() {
        return Combinators.combine(
                        Arbitraries.of(HOSTS).injectNull(0.3),
                        Arbitraries.of(METHODS).injectNull(0.3),
                        Arbitraries.of("/a", "/b", "*", "/a/*"),
                        Arbitraries.integers().between(-1, 2),
                        Arbitraries.integers().between(0, 3))
                .as(RuleSnapshotTest::http)
                .list().ofMaxSize(12)
                .map(list -> {
                    List<HttpRule> withIds = new ArrayList<>();
                    for (int i = 0; i < list.size(); i++) {
                        HttpRule rule = list.get(i);
                        rule.setId(String.format("r%02d", i));
                        withIds.add(rule);
                    }
                    return withIds;
                });
    }

    @Provide
    Arbitrary<String> requestHosts() {
        return Arbitraries.of("api.example.com", "API.example.com", "pay.example.com", "other.example", "default");
    }

    @Provide
    Arbitrary<String> requestMethods() {
        return Arbitraries.of("GET", "get", "Get", "POST", "PUT", "DELETE", "delete");
    }

    private static HttpRule http(String host, String method, String path, int priority, int ageMinutes) {
        return HttpRule.builder()
                .id(UUID.randomUUID().toString())
                .targetHost(host).method(method).matchKey(path)
                .priority(priority).enabled(true)
                .createdAt(BASE.minusMinutes(ageMinutes))
                .build();
    }

    /** 原 HttpProtocolHandler.findByHostAndMethod 的 Java 篩選 + HttpRuleService 的排序。 */
    private static List<HttpRule> legacyHttpCandidates(List<HttpRule> rules, String targetHost, String method) {
        List<HttpRule> result = new ArrayList<>();
        for (HttpRule rule : rules) {
            boolean hostMatches = targetHost.equals(rule.getTargetHost())
                    || rule.getTargetHost() == null || rule.getTargetHost().isEmpty();
            boolean methodMatches = rule.getMethod() == null || rule.getMethod().equalsIgnoreCase(method);
            if (hostMatches && methodMatches) {
                result.add(rule);
            }
        }
        return result.stream().sorted(HttpRuleService.HTTP_RULE_ORDER).toList();
    }

    // ==================== JMS ====================

    @Property(tries = 1000)
    void jmsBucketsMatchLegacyFiltering(@ForAll("jmsRules") List<JmsRule> rules,
                                        @ForAll("endpointValues") String endpointValue) {
        JmsQueueSnapshot snapshot = new JmsQueueSnapshot(rules, "ServiceName", null);

        snapshot.bucket("SomethingElse");

        assertThat(ids(snapshot.bucket(endpointValue)))
                .isEqualTo(ids(legacyBucket(rules, "ServiceName", endpointValue)));
        assertThat(ids(snapshot.rules())).isEqualTo(ids(rules));
    }

    @Test
    void jmsSnapshotDoesNotGrowWithArbitraryEndpointValues() {
        List<JmsRule> rules = List.of(
                jms("ServiceName=OrderService;CustId=1"),
                jms("//ServiceName=PaymentService"),
                jms(null));
        JmsQueueSnapshot snapshot = new JmsQueueSnapshot(rules, "ServiceName", null);

        for (int i = 0; i < 10_000; i++) {
            assertThat(snapshot.bucket("Service-" + i)).hasSize(1);
        }
        snapshot.bucket("OrderService");
        snapshot.bucket("PaymentService");

        assertThat(snapshot.bucketCount()).isEqualTo(2);
    }

    @Test
    void jmsSnapshotWithoutEndpointFieldReturnsAllRules() {
        List<JmsRule> rules = List.of(jms("ServiceName=OrderService"), jms(null));

        assertThat(new JmsQueueSnapshot(rules, " ", null).bucket("OrderService")).hasSize(2);
        assertThat(new JmsQueueSnapshot(rules, null, null).bucket("OrderService")).hasSize(2);
    }

    @Provide
    Arbitrary<List<JmsRule>> jmsRules() {
        Arbitrary<String> fragment = Arbitraries.of(
                "ServiceName=OrderService", "//ServiceName=PaymentService", "servicename=OrderService",
                "ServiceName!=OrderService", "ServiceName~=Order.*", "ServiceName=\"OrderService\"",
                "CustId=1", "//CustId=2", "ServiceName", "=x", "ServiceName = PaymentService");
        return fragment.list().ofMaxSize(3).map(parts -> parts.isEmpty() ? null : String.join(";", parts))
                .injectNull(0.2)
                .map(RuleSnapshotTest::jms)
                .list().ofMaxSize(10);
    }

    @Provide
    Arbitrary<String> endpointValues() {
        return Arbitraries.of("OrderService", "PaymentService", "Order.*", "Unknown", "", " ").injectNull(0.1);
    }

    private static JmsRule jms(String bodyCondition) {
        return JmsRule.builder().id(UUID.randomUUID().toString()).queueName("Q")
                .bodyCondition(bodyCondition).enabled(true).priority(0).createdAt(BASE).build();
    }

    /** 原 JmsRuleService.findBucketedJmsRules + bodyConditionMatchesEndpoint。 */
    private static List<JmsRule> legacyBucket(List<JmsRule> rules, String endpointField, String endpointValue) {
        if (endpointValue == null || endpointValue.isBlank()) {
            return rules;
        }
        List<JmsRule> bucketed = new ArrayList<>();
        for (JmsRule rule : rules) {
            String cond = rule.getBodyCondition();
            if (cond == null || cond.isBlank() || matchesEndpoint(cond, endpointField, endpointValue)) {
                bucketed.add(rule);
            }
        }
        return bucketed;
    }

    private static boolean matchesEndpoint(String bodyCondition, String endpointField, String endpointValue) {
        for (String part : bodyCondition.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String normalized = trimmed.startsWith("//") ? trimmed.substring(2) : trimmed;
            int eqIdx = normalized.indexOf('=');
            if (eqIdx <= 0) {
                continue;
            }
            char prev = normalized.charAt(eqIdx - 1);
            String field = prev == '!' || prev == '*' || prev == '~'
                    ? normalized.substring(0, eqIdx - 1).trim()
                    : normalized.substring(0, eqIdx).trim();
            String value = normalized.substring(eqIdx + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (field.equalsIgnoreCase(endpointField)) {
                return value.equals(endpointValue);
            }
        }
        return true;
    }

    // ==================== 記憶體 ====================

    @Test
    void conditionRegistryReleasesConditionsWhenSnapshotIsGone() throws Exception {
        ConditionMatcher matcher = new ConditionMatcher(2 * 1024 * 1024, ConditionMatcher.EngineMode.COMPILED);
        WeakReference<ConditionMatcher.RuleConditions> released = pinAndDrop(matcher);

        for (int i = 0; i < 20 && released.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }

        assertThat(released.get()).as("conditions no longer used by any snapshot are collectable").isNull();
    }

    @Test
    void conditionRegistryKeepsConditionsWhileSnapshotIsAlive() throws Exception {
        ConditionMatcher matcher = new ConditionMatcher(2 * 1024 * 1024, ConditionMatcher.EngineMode.COMPILED);
        HttpRule rule = http(null, "GET", "/a", 0, 0);
        rule.setBodyCondition("user.type=vip");
        HttpRuleSnapshot snapshot = new HttpRuleSnapshot(List.of(rule), HttpRuleService.HTTP_RULE_ORDER, matcher);
        ConditionMatcher.RuleConditions pinned = matcher.conditions("user.type=vip", null, null);
        WeakReference<ConditionMatcher.RuleConditions> ref = new WeakReference<>(pinned);
        pinned = null;

        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(20);
        }

        assertThat(ref.get()).isNotNull();
        assertThat(matcher.conditions("user.type=vip", null, null)).isSameAs(ref.get());
        assertThat(snapshot.ruleCount()).isEqualTo(1);
    }

    private static WeakReference<ConditionMatcher.RuleConditions> pinAndDrop(ConditionMatcher matcher) {
        HttpRule rule = http(null, "GET", "/a", 0, 0);
        rule.setBodyCondition("user.type=gone");
        new HttpRuleSnapshot(List.of(rule), HttpRuleService.HTTP_RULE_ORDER, matcher);
        return new WeakReference<>(matcher.conditions("user.type=gone", null, null));
    }

    private static <T> List<String> ids(List<T> rules) {
        return rules.stream().map(r -> r instanceof HttpRule h ? h.getId() : ((JmsRule) r).getId()).toList();
    }
}
