package com.echo.service;

import com.echo.config.JmsProperties;
import com.echo.entity.BaseRule;
import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 規則的判斷順序與「一定輪不到」的規則（見 {@link RuleOrderAnalyzer}），供規則頁顯示。
 * <p>
 * 使用與實際比對相同的規則來源與排序：HTTP 取自規則快照（啟用中且 Response 有效），JMS 取自快取的全部規則（只看啟用中的）。
 * 結果依來源物件快取，規則異動清除快取後自動重算。
 */
@Service
public class RuleOrderService {

    /** 判斷順序中，沒有條件、但前面已有預設規則而在這個範圍用不到的規則（萬用規則在其他路徑仍可能用到）。 */
    static final String NOT_USED_HERE = "notUsedHere";

    private final HttpRuleService httpRuleService;
    private final JmsRuleService jmsRuleService;
    private final Optional<JmsProperties> jmsProperties;
    private final AtomicReference<CachedIssues> cachedIssues = new AtomicReference<>();

    public RuleOrderService(HttpRuleService httpRuleService, JmsRuleService jmsRuleService,
                            Optional<JmsProperties> jmsProperties) {
        this.httpRuleService = httpRuleService;
        this.jmsRuleService = jmsRuleService;
        this.jmsProperties = jmsProperties;
    }

    /** 判斷順序中的一條規則。 */
    public record Entry(String id, String description, String method, String path, String targetHost,
                        String queueName, Integer priority, boolean enabled, boolean conditional,
                        String bodyCondition, String queryCondition, String headerCondition, String matchSpec,
                        String scenarioName, String requiredScenarioState, boolean self, boolean usedAsDefault,
                        boolean partial, IssueView issue) {
    }

    /**
     * 輪不到的原因；{@code byRuleId} 是擋住它的規則，{@code description}／{@code route} 是輪不到的規則本身
     * （例如 {@code POST /orders}、{@code JMS ORDER}），供列表提示使用。
     */
    public record IssueView(String type, String byRuleId, String byDescription, String description, String route) {
    }

    /**
     * 一條規則的判斷順序。
     *
     * @param wildcard 這條規則適用所有路徑（或所有 queue）；其他路徑的規則會排在它前面，這裡只列出同樣適用的規則
     */
    public record RuleOrderView(String ruleId, String protocol, boolean wildcard, List<Entry> entries) {
        public RuleOrderView {
            entries = List.copyOf(entries);
        }
    }

    /** 所有輪不到的規則（依規則 id）。 */
    public record IssuesView(int count, Map<String, IssueView> issues) {
        public IssuesView {
            issues = Map.copyOf(issues);
        }
    }

    public IssuesView issues() {
        HttpSource http = httpSource();
        JmsSource jms = jmsSource();
        CachedIssues cached = cachedIssues.get();
        if (cached != null && cached.httpSource == http.origin && cached.jmsSource == jms.origin) {
            return cached.view;
        }
        Map<String, IssueView> issues = new LinkedHashMap<>();
        for (HttpRule rule : http.active) {
            RuleOrderAnalyzer.Issue<HttpRule> issue = RuleOrderAnalyzer.issueOf(rule, http.route);
            if (issue != null) {
                issues.put(rule.getId(), view(issue, rule, (rule.getMethod() != null ? rule.getMethod() : "*")
                        + " " + rule.getMatchKey()));
            }
        }
        for (JmsRule rule : jms.active) {
            RuleOrderAnalyzer.Issue<JmsRule> issue = RuleOrderAnalyzer.issueOf(rule, jms.route);
            if (issue != null) {
                issues.put(rule.getId(), view(issue, rule, "JMS " + rule.getQueueName()));
            }
        }
        IssuesView view = new IssuesView(issues.size(), issues);
        cachedIssues.set(new CachedIssues(http.origin, jms.origin, view));
        return view;
    }

    public Optional<RuleOrderView> orderFor(String ruleId) {
        HttpSource http = httpSource();
        Optional<HttpRule> httpRule = find(http.active, ruleId).or(() -> httpRuleService.findHttpRuleById(ruleId));
        if (httpRule.isPresent()) {
            HttpRule rule = httpRule.get();
            return Optional.of(view(rule, "HTTP", "*".equals(rule.getMatchKey()), http.active, http.route));
        }
        JmsSource jms = jmsSource();
        return find(jms.all, ruleId).map(rule ->
                view(rule, "JMS", "*".equals(rule.getQueueName()), jms.active, jms.route));
    }

    private <R extends BaseRule> RuleOrderView view(R rule, String protocol, boolean wildcard, List<R> active,
                                                    RuleOrderAnalyzer.Route<R> route) {
        Map<String, IssueView> issues = issues().issues();
        List<R> ordered = RuleOrderAnalyzer.orderFor(rule, active, route);
        List<Entry> entries = new ArrayList<>(ordered.size());
        boolean defaultFound = false;
        for (R entry : ordered) {
            boolean enabled = !Boolean.FALSE.equals(entry.getEnabled());
            boolean conditional = route.conditional(entry);
            boolean self = entry.getId().equals(rule.getId());
            // 只適用這條規則一部分請求的規則：列出來，但不能當成所有請求的預設回應
            boolean partial = !self && !route.covers(entry, rule);
            boolean usedAsDefault = false;
            IssueView issue = enabled ? issues.get(entry.getId()) : null;
            if (enabled && !conditional) {
                if (!defaultFound && !partial && !RuleOrderAnalyzer.gated(entry)) {
                    usedAsDefault = issue == null;
                    defaultFound = usedAsDefault;
                } else if (defaultFound && issue == null) {
                    issue = new IssueView(NOT_USED_HERE, null, null, null, null);
                }
            }
            entries.add(entry(entry, conditional, self, usedAsDefault, partial, issue));
        }
        return new RuleOrderView(rule.getId(), protocol, wildcard, entries);
    }

    private static Entry entry(BaseRule rule, boolean conditional, boolean self, boolean usedAsDefault,
                               boolean partial, IssueView issue) {
        HttpRule http = rule instanceof HttpRule h ? h : null;
        JmsRule jms = rule instanceof JmsRule j ? j : null;
        return new Entry(rule.getId(), rule.getDescription(),
                http != null ? http.getMethod() : null, http != null ? http.getMatchKey() : null,
                http != null ? http.getTargetHost() : null, jms != null ? jms.getQueueName() : null,
                rule.getPriority(), !Boolean.FALSE.equals(rule.getEnabled()), conditional,
                rule.getBodyCondition(), http != null ? http.getQueryCondition() : null,
                http != null ? http.getHeaderCondition() : null, rule.getMatchSpec(),
                rule.getScenarioName(), rule.getRequiredScenarioState(), self, usedAsDefault, partial, issue);
    }

    private static <R extends BaseRule> IssueView view(RuleOrderAnalyzer.Issue<R> issue, R rule, String route) {
        return new IssueView(issue.type(), issue.by().getId(), issue.by().getDescription(),
                rule.getDescription(), route);
    }

    private static <R extends BaseRule> Optional<R> find(List<R> rules, String id) {
        return rules.stream().filter(rule -> id.equals(rule.getId())).findFirst();
    }

    // ==================== 規則來源 ====================

    private record HttpSource(Object origin, List<HttpRule> active, RuleOrderAnalyzer.Route<HttpRule> route) {
    }

    private record JmsSource(Object origin, List<JmsRule> all, List<JmsRule> active,
                             RuleOrderAnalyzer.Route<JmsRule> route) {
    }

    private record CachedIssues(Object httpSource, Object jmsSource, IssuesView view) {
    }

    private HttpSource httpSource() {
        HttpRuleSnapshot snapshot = httpRuleService.httpRuleSnapshot();
        List<HttpRule> active = snapshot.rules();
        return new HttpSource(snapshot, active, new HttpRoute(active));
    }

    private JmsSource jmsSource() {
        List<JmsRule> all = jmsRuleService.findAllJmsRulesCached();
        List<JmsRule> active = all.stream().filter(rule -> !Boolean.FALSE.equals(rule.getEnabled())).toList();
        String endpointField = jmsProperties.map(JmsProperties::getEndpointField).orElse(null);
        return new JmsSource(all, all, active, new JmsRoute(active, endpointField));
    }

    /** HTTP：host、method 與路徑都要涵蓋；路徑 {@code *} 涵蓋所有路徑，{@code /x/*} 涵蓋以 {@code /x/} 開頭的路徑。 */
    static final class HttpRoute implements RuleOrderAnalyzer.Route<HttpRule> {

        private final Map<String, List<HttpRule>> byPath = new HashMap<>();
        private final Set<String> prefixPaths = new LinkedHashSet<>();
        private final Comparator<HttpRule> order;

        HttpRoute(List<HttpRule> rules) {
            this.order = RuleOrderAnalyzer.ranked(rules, HttpRuleService.HTTP_RULE_ORDER);
            for (HttpRule rule : rules) {
                String path = rule.getMatchKey();
                byPath.computeIfAbsent(path, key -> new ArrayList<>()).add(rule);
                if (path != null && path.endsWith("/*")) {
                    prefixPaths.add(path);
                }
            }
        }

        @Override
        public Comparator<HttpRule> order() {
            return order;
        }

        @Override
        public boolean covers(HttpRule broader, HttpRule narrower) {
            return hostCovers(broader.getTargetHost(), narrower.getTargetHost())
                    && methodCovers(broader.getMethod(), narrower.getMethod())
                    && pathCovers(broader.getMatchKey(), narrower.getMatchKey());
        }

        @Override
        public boolean overlaps(HttpRule other, HttpRule rule) {
            return hostsCompatible(other.getTargetHost(), rule.getTargetHost())
                    && (other.getMethod() == null || rule.getMethod() == null
                        || other.getMethod().equalsIgnoreCase(rule.getMethod()))
                    && pathCovers(other.getMatchKey(), rule.getMatchKey());
        }

        @Override
        public boolean conditional(HttpRule rule) {
            return rule.getMatchSpec() != null || RuleOrderAnalyzer.notBlank(rule.getBodyCondition())
                    || RuleOrderAnalyzer.notBlank(rule.getQueryCondition())
                    || RuleOrderAnalyzer.notBlank(rule.getHeaderCondition());
        }

        @Override
        public boolean sameConditions(HttpRule a, HttpRule b) {
            if (a.getMatchSpec() != null || b.getMatchSpec() != null) {
                return RuleOrderAnalyzer.sameMatchSpec(a.getMatchSpec(), b.getMatchSpec());
            }
            return RuleOrderAnalyzer.sameText(a.getBodyCondition(), b.getBodyCondition())
                    && RuleOrderAnalyzer.sameText(a.getQueryCondition(), b.getQueryCondition())
                    && RuleOrderAnalyzer.sameText(a.getHeaderCondition(), b.getHeaderCondition());
        }

        @Override
        public Collection<HttpRule> candidatesFor(HttpRule rule) {
            List<HttpRule> candidates = new ArrayList<>(byPath.getOrDefault(rule.getMatchKey(), List.of()));
            for (String prefix : prefixPaths) {
                if (!prefix.equals(rule.getMatchKey()) && pathCovers(prefix, rule.getMatchKey())) {
                    candidates.addAll(byPath.get(prefix));
                }
            }
            if (!"*".equals(rule.getMatchKey())) {
                candidates.addAll(byPath.getOrDefault("*", List.of()));
            }
            return candidates;
        }

        static boolean hostCovers(String broader, String narrower) {
            return broader == null || broader.isEmpty() || (narrower != null && broader.equals(narrower));
        }

        private static boolean hostsCompatible(String a, String b) {
            return a == null || a.isEmpty() || b == null || b.isEmpty() || a.equals(b);
        }

        static boolean methodCovers(String broader, String narrower) {
            return broader == null || (narrower != null && broader.equalsIgnoreCase(narrower));
        }

        /** 與 {@code HttpProtocolHandler.matchesRequestPath} 一致：完全相同、{@code /x/*} 前綴或 {@code *}。 */
        static boolean pathCovers(String broader, String narrower) {
            if (broader == null || narrower == null) {
                return false;
            }
            if ("*".equals(broader)) {
                return true;
            }
            if ("*".equals(narrower)) {
                return false;
            }
            if (broader.endsWith("/*")) {
                String prefix = broader.substring(0, broader.length() - 1);
                String narrowerPrefix = narrower.endsWith("/*") ? narrower.substring(0, narrower.length() - 1) : narrower;
                return narrowerPrefix.startsWith(prefix);
            }
            return broader.equals(narrower);
        }
    }

    /** JMS：queue 相同或 {@code *}；依 endpoint 欄位分桶時，較早的規則不能限定成別的 endpoint 值。 */
    static final class JmsRoute implements RuleOrderAnalyzer.Route<JmsRule> {

        private final Map<String, List<JmsRule>> byQueue = new HashMap<>();
        private final String endpointField;
        private final Comparator<JmsRule> order;

        JmsRoute(List<JmsRule> rules, String endpointField) {
            this.endpointField = endpointField;
            this.order = RuleOrderAnalyzer.ranked(rules, JmsRuleService.JMS_RULE_ORDER);
            for (JmsRule rule : rules) {
                byQueue.computeIfAbsent(rule.getQueueName(), key -> new ArrayList<>()).add(rule);
            }
        }

        @Override
        public Comparator<JmsRule> order() {
            return order;
        }

        @Override
        public boolean covers(JmsRule broader, JmsRule narrower) {
            boolean queue = "*".equals(broader.getQueueName())
                    || (broader.getQueueName() != null && broader.getQueueName().equals(narrower.getQueueName()));
            if (!queue) {
                return false;
            }
            if (endpointField == null || endpointField.isBlank()) {
                return true;
            }
            String broaderValue = JmsQueueSnapshot.endpointValueOf(broader.getBodyCondition(), endpointField);
            return broaderValue == null
                    || broaderValue.equals(JmsQueueSnapshot.endpointValueOf(narrower.getBodyCondition(), endpointField));
        }

        @Override
        public boolean overlaps(JmsRule other, JmsRule rule) {
            boolean queue = "*".equals(other.getQueueName())
                    || (other.getQueueName() != null && other.getQueueName().equals(rule.getQueueName()));
            if (!queue || endpointField == null || endpointField.isBlank()) {
                return queue;
            }
            String otherValue = JmsQueueSnapshot.endpointValueOf(other.getBodyCondition(), endpointField);
            String ruleValue = JmsQueueSnapshot.endpointValueOf(rule.getBodyCondition(), endpointField);
            return otherValue == null || ruleValue == null || otherValue.equals(ruleValue);
        }

        @Override
        public boolean conditional(JmsRule rule) {
            return rule.getMatchSpec() != null || RuleOrderAnalyzer.notBlank(rule.getBodyCondition());
        }

        @Override
        public boolean sameConditions(JmsRule a, JmsRule b) {
            if (a.getMatchSpec() != null || b.getMatchSpec() != null) {
                return RuleOrderAnalyzer.sameMatchSpec(a.getMatchSpec(), b.getMatchSpec());
            }
            return RuleOrderAnalyzer.sameText(a.getBodyCondition(), b.getBodyCondition());
        }

        @Override
        public Collection<JmsRule> candidatesFor(JmsRule rule) {
            List<JmsRule> candidates = new ArrayList<>(byQueue.getOrDefault(rule.getQueueName(), List.of()));
            if (!"*".equals(rule.getQueueName())) {
                candidates.addAll(byQueue.getOrDefault("*", List.of()));
            }
            return candidates;
        }
    }
}
