package com.echo.service;

import com.echo.config.JmsProperties;
import com.echo.entity.BaseRule;
import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import com.echo.protocol.http.HttpPathPattern;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rule evaluation order and definitely unreachable rules for the rules page; see {@link RuleOrderAnalyzer}.
 * <p>
 * Use the same candidates and ordering as actual matching: enabled HTTP snapshot rules with valid Responses, and enabled JMS rules from the cached full list.
 * Cache results by source-object identity; recompute automatically after rule changes invalidate the source cache.
 */
@Service
public class RuleOrderService {

    /** Unconditional rules unused in this scope because an earlier default already covers it; wildcard rules may remain useful on other paths. */
    static final String NOT_USED_HERE = "notUsedHere";

    private final HttpRuleService httpRuleService;
    private final JmsRuleService jmsRuleService;
    private final Optional<JmsProperties> jmsProperties;
    private final AtomicReference<CachedIssues> cachedIssues = new AtomicReference<>();
    /** Reuse the index by source-object identity; rule changes and cache invalidation replace the source object. */
    private final AtomicReference<HttpSource> cachedHttp = new AtomicReference<>();
    private final AtomicReference<JmsSource> cachedJms = new AtomicReference<>();

    public RuleOrderService(HttpRuleService httpRuleService, JmsRuleService jmsRuleService,
                            Optional<JmsProperties> jmsProperties) {
        this.httpRuleService = httpRuleService;
        this.jmsRuleService = jmsRuleService;
        this.jmsProperties = jmsProperties;
    }

    /** One rule in evaluation order. */
    public record Entry(String id, String description, String method, String path, String targetHost,
                        String queueName, Integer priority, boolean enabled, boolean conditional,
                        String bodyCondition, String queryCondition, String headerCondition, String matchSpec,
                        String scenarioName, String requiredScenarioState, boolean self, boolean usedAsDefault,
                        boolean partial, IssueView issue) {
    }

    /**
     * Unreachability reason: {@code byRuleId} identifies the shadowing rule; {@code description}/{@code route} describe the unreachable rule itself,
     * (such as {@code POST /orders}, {@code JMS ORDER}), for list prompts.
     */
    public record IssueView(String type, String byRuleId, String byDescription, String description, String route) {
    }

    /**
     * Evaluation order for one rule.
     *
     * @param wildcard This rule covers all paths or queues; rules for other paths precede it, so show only rules with the same applicable scope here.
     */
    public record RuleOrderView(String ruleId, String protocol, boolean wildcard, List<Entry> entries) {
        public RuleOrderView {
            entries = List.copyOf(entries);
        }
    }

    /** All unreachable rules, keyed by rule id. */
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
            // Include partially overlapping rules in the list, but do not treat them as defaults for every request.
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

    // ==================== Rule source ====================

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
        // Use the actual matching candidates, excluding disabled rules and rules whose responses were deleted.
        // Actual matching orders source candidates by id before applying a stable priority sort; preserve that order to break ties identically.
        List<JmsRule> active = jmsRuleService.withValidResponses(all.stream()
                .filter(rule -> !Boolean.FALSE.equals(rule.getEnabled()))
                .sorted(Comparator.comparing(JmsRule::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList());
        String endpointField = jmsProperties.map(JmsProperties::getEndpointField).orElse(null);
        JmsRoute route = new JmsRoute(endpointField);
        JmsSource source = new JmsSource(all, all, active, route, new RuleOrderAnalyzer.Index<>(active, route));
        cachedJms.set(source);
        return source;
    }

    /** HTTP coverage includes host, method, and path; {@code *} covers every path and {@code /x/*} covers paths starting with {@code /x/}. */
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

        /** The exact path, each ancestor's {@code /x/*} pattern, and {@code *}. */
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

        static boolean pathCovers(String broader, String narrower) {
            return HttpPathPattern.covers(broader, narrower);
        }
    }

    /** JMS coverage requires the same queue or {@code *}; with endpoint bucketing, an earlier rule cannot require a different endpoint value. */
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
