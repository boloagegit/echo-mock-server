package com.echo.service;

import com.echo.config.JmsProperties;
import com.echo.entity.BaseRule;
import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    /** 索引依規則來源物件重用（來源在規則異動、快取清除後才會換成新的物件）。 */
    private final AtomicReference<HttpSource> cachedHttp = new AtomicReference<>();
    private final AtomicReference<JmsSource> cachedJms = new AtomicReference<>();

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
            RuleOrderAnalyzer.Issue<HttpRule> issue = http.index.issueOf(rule);
            if (issue != null) {
                issues.put(rule.getId(), view(issue, rule, (rule.getMethod() != null ? rule.getMethod() : "*")
                        + " " + rule.getMatchKey()));
            }
        }
        for (JmsRule rule : jms.active) {
            RuleOrderAnalyzer.Issue<JmsRule> issue = jms.index.issueOf(rule);
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
            return Optional.of(view(rule, "HTTP", "*".equals(rule.getMatchKey()), http.index, http.route));
        }
        JmsSource jms = jmsSource();
        return find(jms.all, ruleId).map(rule ->
                view(rule, "JMS", "*".equals(rule.getQueueName()), jms.index, jms.route));
    }

    private <R extends BaseRule> RuleOrderView view(R rule, String protocol, boolean wildcard,
                                                    RuleOrderAnalyzer.Index<R> index, RuleOrderAnalyzer.Route<R> route) {
        Map<String, IssueView> issues = issues().issues();
        List<R> ordered = index.orderFor(rule);
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

    private record HttpSource(Object origin, List<HttpRule> active, RuleOrderAnalyzer.Route<HttpRule> route,
                              RuleOrderAnalyzer.Index<HttpRule> index) {
    }

    private record JmsSource(Object origin, List<JmsRule> all, List<JmsRule> active,
                             RuleOrderAnalyzer.Route<JmsRule> route, RuleOrderAnalyzer.Index<JmsRule> index) {
    }

    private record CachedIssues(Object httpSource, Object jmsSource, IssuesView view) {
    }

    private HttpSource httpSource() {
        HttpRuleSnapshot snapshot = httpRuleService.httpRuleSnapshot();
        HttpSource cached = cachedHttp.get();
        if (cached != null && cached.origin == snapshot) {
            return cached;
        }
        List<HttpRule> active = snapshot.rules();
        HttpRoute route = new HttpRoute();
        HttpSource source = new HttpSource(snapshot, active, route, new RuleOrderAnalyzer.Index<>(active, route));
        cachedHttp.set(source);
        return source;
    }

    private JmsSource jmsSource() {
        List<JmsRule> all = jmsRuleService.findAllJmsRulesCached();
        JmsSource cached = cachedJms.get();
        if (cached != null && cached.origin == all) {
            return cached;
        }
        // 與實際比對相同的候選：停用的與回應已被刪除的規則都不會被考慮
        List<JmsRule> active = jmsRuleService.withValidResponses(
                all.stream().filter(rule -> !Boolean.FALSE.equals(rule.getEnabled())).toList());
        String endpointField = jmsProperties.map(JmsProperties::getEndpointField).orElse(null);
        JmsRoute route = new JmsRoute(endpointField);
        JmsSource source = new JmsSource(all, all, active, route, new RuleOrderAnalyzer.Index<>(active, route));
        cachedJms.set(source);
        return source;
    }

    /** HTTP：host、method 與路徑都要涵蓋；路徑 {@code *} 涵蓋所有路徑，{@code /x/*} 涵蓋以 {@code /x/} 開頭的路徑。 */
    static final class HttpRoute implements RuleOrderAnalyzer.Route<HttpRule> {

        @Override
        public Comparator<HttpRule> order() {
            return HttpRuleService.HTTP_RULE_ORDER;
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
        public String conditionKey(HttpRule rule) {
            return rule.getMatchSpec() != null ? RuleOrderAnalyzer.matchSpecKey(rule.getMatchSpec())
                    : RuleOrderAnalyzer.legacyKey(rule.getBodyCondition(), rule.getQueryCondition(),
                            rule.getHeaderCondition());
        }

        @Override
        public String group(HttpRule rule) {
            return String.valueOf(rule.getMatchKey());
        }

        /** 路徑本身、每一層上層的 {@code /x/*}，以及 {@code *}。 */
        @Override
        public List<String> coveringGroups(HttpRule rule) {
            String path = String.valueOf(rule.getMatchKey());
            List<String> groups = new ArrayList<>();
            groups.add(path);
            if (!"*".equals(path)) {
                for (int i = path.indexOf('/'); i >= 0; i = path.indexOf('/', i + 1)) {
                    String prefix = path.substring(0, i + 1) + "*";
                    if (!prefix.equals(path)) {
                        groups.add(prefix);
                    }
                }
                groups.add("*");
            }
            return groups;
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

        private final String endpointField;

        JmsRoute(String endpointField) {
            this.endpointField = endpointField;
        }

        @Override
        public Comparator<JmsRule> order() {
            return JmsRuleService.JMS_RULE_ORDER;
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
        public String conditionKey(JmsRule rule) {
            return rule.getMatchSpec() != null ? RuleOrderAnalyzer.matchSpecKey(rule.getMatchSpec())
                    : RuleOrderAnalyzer.legacyKey(rule.getBodyCondition());
        }

        @Override
        public String group(JmsRule rule) {
            return String.valueOf(rule.getQueueName());
        }

        @Override
        public List<String> coveringGroups(JmsRule rule) {
            String queue = String.valueOf(rule.getQueueName());
            return "*".equals(queue) ? List.of(queue) : List.of(queue, "*");
        }
    }
}
