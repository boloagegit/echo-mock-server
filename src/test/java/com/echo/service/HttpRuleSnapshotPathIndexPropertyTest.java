package com.echo.service;

import com.echo.entity.HttpRule;
import com.echo.protocol.http.HttpPathPattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 規則快照的路徑索引查到的候選，與「取出 host + method 的全部候選再逐條比對路徑」完全相同，順序也相同。
 */
class HttpRuleSnapshotPathIndexPropertyTest {

    private static final List<String> RULE_PATHS = Arrays.asList(
            "/orders", "/orders/1", "/orders/2", "/orders/*", "/orders/1/*", "/items", "/items/*", "*", "/", "/*",
            "/orders/*/x", null);
    private static final List<String> REQUEST_PATHS = Arrays.asList(
            "/orders", "/orders/", "/orders/1", "/orders/2", "/orders/3", "/orders/1/a", "/orders/*", "/items",
            "/items/9", "/", "/other", "*", "", null);
    private static final List<String> METHODS = Arrays.asList("GET", "get", "POST", null);
    private static final List<String> HOSTS = Arrays.asList("a.test", "b.test", "", null);

    @Property(tries = 500)
    void pathIndexReturnsTheSameRulesInTheSameOrder(@ForAll("ruleSets") List<HttpRule> rules) {
        HttpRuleSnapshot snapshot = new HttpRuleSnapshot(rules, HttpRuleService.HTTP_RULE_ORDER, null);

        for (String host : List.of("a.test", "b.test", "unknown.test")) {
            for (String method : List.of("GET", "Get", "POST", "PUT")) {
                for (String path : REQUEST_PATHS) {
                    List<HttpRule> expected = snapshot.candidates(host, method).stream()
                            .filter(rule -> HttpPathPattern.matches(rule.getMatchKey(), path))
                            .toList();
                    assertThat(snapshot.candidates(host, method, path))
                            .as("host=%s method=%s path=%s", host, method, path)
                            .containsExactlyElementsOf(expected);
                }
            }
        }
    }

    @Provide
    Arbitrary<List<HttpRule>> ruleSets() {
        Arbitrary<HttpRule> rule = Combinators.combine(
                Arbitraries.of(HOSTS),
                Arbitraries.of(METHODS),
                Arbitraries.of(RULE_PATHS),
                Arbitraries.integers().between(0, 3),
                Arbitraries.of(true, false)
        ).as((host, method, path, priority, conditional) -> {
            HttpRule built = HttpRule.builder()
                    .targetHost(host).method(method).matchKey(path)
                    .priority(priority)
                    .bodyCondition(conditional ? "a=1" : null)
                    .build();
            return built;
        });
        return rule.list().ofMinSize(0).ofMaxSize(40).map(list -> {
            List<HttpRule> withIds = new ArrayList<>(list.size());
            LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
            for (int i = 0; i < list.size(); i++) {
                HttpRule r = list.get(i);
                r.setId(String.format("r%03d", i));
                r.setCreatedAt(base.plusSeconds(i));
                withIds.add(r);
            }
            return withIds;
        });
    }
}
