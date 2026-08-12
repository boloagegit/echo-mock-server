package com.echo.service;

import com.echo.entity.BaseRule;
import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import com.echo.entity.Protocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Performs bounded, cross-protocol queries for the rule administration list. */
@Service
@RequiredArgsConstructor
public class RuleQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int ID_QUERY_CHUNK_SIZE = 500;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("updatedAt", "createdAt", "priority");
    private static final List<String> HTTP_SEARCH_FIELDS = List.of(
            "id", "matchKey", "description", "targetHost", "tags",
            "bodyCondition", "queryCondition", "headerCondition");
    private static final List<String> JMS_SEARCH_FIELDS = List.of(
            "id", "queueName", "description", "tags", "bodyCondition");

    private final EntityManager entityManager;

    @Value("${echo.jms.enabled:false}")
    private boolean jmsEnabled;

    @Transactional(readOnly = true)
    public RuleQueryResult query(RuleQuery query) {
        int requestedPage = Math.max(0, query.page());
        int pageSize = Math.max(1, Math.min(query.size(), MAX_PAGE_SIZE));
        String sortField = normalizeSortField(query.sortField());
        boolean ascending = "asc".equalsIgnoreCase(query.direction());
        List<String> keywords = tokenize(query.keyword());

        boolean includeHttp = query.protocol() == null || query.protocol() == Protocol.HTTP;
        boolean includeJms = jmsEnabled && (query.protocol() == null || query.protocol() == Protocol.JMS);

        long httpCount = includeHttp
                ? count(HttpRule.class, query.enabled(), query.isProtected(), keywords, HTTP_SEARCH_FIELDS)
                : 0;
        long jmsCount = includeJms
                ? count(JmsRule.class, query.enabled(), query.isProtected(), keywords, JMS_SEARCH_FIELDS)
                : 0;
        long totalElements = httpCount + jmsCount;
        int totalPages = totalElements == 0 ? 0
                : (int) Math.ceil((double) totalElements / pageSize);
        int page = totalPages == 0 ? 0 : Math.min(requestedPage, totalPages - 1);
        long offset = (long) page * pageSize;
        long prefixSize = Math.min(totalElements, offset + pageSize);

        List<BaseRule> candidates = new ArrayList<>();
        if (httpCount > 0) {
            int limit = Math.toIntExact(Math.min(httpCount, prefixSize));
            candidates.addAll(fetch(HttpRule.class, query.enabled(), query.isProtected(), keywords,
                    HTTP_SEARCH_FIELDS, sortField, ascending, limit));
        }
        if (jmsCount > 0) {
            int limit = Math.toIntExact(Math.min(jmsCount, prefixSize));
            candidates.addAll(fetch(JmsRule.class, query.enabled(), query.isProtected(), keywords,
                    JMS_SEARCH_FIELDS, sortField, ascending, limit));
        }

        candidates.sort(ruleComparator(sortField, ascending));
        int fromIndex = Math.toIntExact(Math.min(offset, candidates.size()));
        int toIndex = Math.min(fromIndex + pageSize, candidates.size());
        return new RuleQueryResult(List.copyOf(candidates.subList(fromIndex, toIndex)),
                page, pageSize, totalElements, totalPages);
    }

    /** Returns group counts without materializing rule entities. */
    @Transactional(readOnly = true)
    public RuleGroupSummary queryGroupSummary(RuleQuery query) {
        List<TagRow> rows = findTagRows(query);
        Map<String, Set<String>> valuesByKey = new TreeMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("_untagged", 0L);

        for (TagRow row : rows) {
            if (row.tags() == null || row.tags().isEmpty()) {
                counts.compute("_untagged", (key, count) -> count == null ? 1L : count + 1);
                continue;
            }
            parseTags(row.tags()).forEach((key, value) -> {
                valuesByKey.computeIfAbsent(key, ignored -> new TreeSet<>()).add(value);
                counts.merge(groupId(key, value), 1L, Long::sum);
            });
        }

        Map<String, List<String>> tagKeys = new LinkedHashMap<>();
        valuesByKey.forEach((key, values) -> tagKeys.put(key, List.copyOf(values)));
        return new RuleGroupSummary(tagKeys, counts, rows.size());
    }

    /** Loads only the requested tag group's first {@code limit} sorted rules. */
    @Transactional(readOnly = true)
    public RuleGroupContent queryGroup(RuleQuery query, String key, String value, int requestedLimit) {
        List<TagRow> matchingRows = findTagRows(query).stream()
                .filter(row -> belongsToGroup(row.tags(), key, value))
                .toList();
        int limit = Math.max(1, Math.min(requestedLimit, Math.max(1, matchingRows.size())));
        String sortField = normalizeSortField(query.sortField());
        boolean ascending = "asc".equalsIgnoreCase(query.direction());

        List<String> httpIds = matchingRows.stream()
                .filter(row -> row.protocol() == Protocol.HTTP)
                .map(TagRow::id)
                .toList();
        List<String> jmsIds = matchingRows.stream()
                .filter(row -> row.protocol() == Protocol.JMS)
                .map(TagRow::id)
                .toList();

        List<BaseRule> candidates = new ArrayList<>();
        candidates.addAll(fetchByIds(HttpRule.class, httpIds, sortField, ascending, limit));
        candidates.addAll(fetchByIds(JmsRule.class, jmsIds, sortField, ascending, limit));
        candidates.sort(ruleComparator(sortField, ascending));
        return new RuleGroupContent(List.copyOf(candidates.subList(0, Math.min(limit, candidates.size()))),
                matchingRows.size());
    }

    private List<TagRow> findTagRows(RuleQuery query) {
        List<String> keywords = tokenize(query.keyword());
        List<TagRow> rows = new ArrayList<>();
        if (query.protocol() == null || query.protocol() == Protocol.HTTP) {
            rows.addAll(findTagRows(HttpRule.class, Protocol.HTTP, query.enabled(), query.isProtected(),
                    keywords, HTTP_SEARCH_FIELDS));
        }
        if (jmsEnabled && (query.protocol() == null || query.protocol() == Protocol.JMS)) {
            rows.addAll(findTagRows(JmsRule.class, Protocol.JMS, query.enabled(), query.isProtected(),
                    keywords, JMS_SEARCH_FIELDS));
        }
        return rows;
    }

    private <T extends BaseRule> List<TagRow> findTagRows(Class<T> entityType, Protocol protocol,
                                                           Boolean enabled, Boolean isProtected,
                                                           List<String> keywords,
                                                           List<String> searchFields) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> criteria = cb.createQuery(Object[].class);
        Root<T> root = criteria.from(entityType);
        criteria.multiselect(root.get("id"), root.get("tags"));
        criteria.where(predicates(cb, root, enabled, isProtected, keywords, searchFields));
        return entityManager.createQuery(criteria).getResultList().stream()
                .map(row -> new TagRow((String) row[0], (String) row[1], protocol))
                .toList();
    }

    private <T extends BaseRule> List<T> fetchByIds(Class<T> entityType, List<String> ids,
                                                     String sortField, boolean ascending, int limit) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<T> result = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += ID_QUERY_CHUNK_SIZE) {
            List<String> chunk = ids.subList(from, Math.min(from + ID_QUERY_CHUNK_SIZE, ids.size()));
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<T> criteria = cb.createQuery(entityType);
            Root<T> root = criteria.from(entityType);
            criteria.select(root).where(root.get("id").in(chunk));
            criteria.orderBy(ascending
                    ? List.of(cb.asc(root.get(sortField)), cb.asc(root.get("id")))
                    : List.of(cb.desc(root.get(sortField)), cb.desc(root.get("id"))));
            result.addAll(entityManager.createQuery(criteria).setMaxResults(limit).getResultList());
        }
        result.sort((left, right) -> ruleComparator(sortField, ascending).compare(left, right));
        return result.subList(0, Math.min(limit, result.size()));
    }

    private <T extends BaseRule> long count(Class<T> entityType, Boolean enabled,
                                             Boolean isProtected, List<String> keywords,
                                             List<String> searchFields) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> criteria = cb.createQuery(Long.class);
        Root<T> root = criteria.from(entityType);
        criteria.select(cb.count(root));
        criteria.where(predicates(cb, root, enabled, isProtected, keywords, searchFields));
        return entityManager.createQuery(criteria).getSingleResult();
    }

    private <T extends BaseRule> List<T> fetch(Class<T> entityType, Boolean enabled,
                                                Boolean isProtected, List<String> keywords,
                                                List<String> searchFields, String sortField,
                                                boolean ascending, int limit) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> criteria = cb.createQuery(entityType);
        Root<T> root = criteria.from(entityType);
        criteria.select(root);
        criteria.where(predicates(cb, root, enabled, isProtected, keywords, searchFields));
        List<Order> order = ascending
                ? List.of(cb.asc(root.get(sortField)), cb.asc(root.get("id")))
                : List.of(cb.desc(root.get(sortField)), cb.desc(root.get("id")));
        criteria.orderBy(order);
        TypedQuery<T> typedQuery = entityManager.createQuery(criteria);
        typedQuery.setMaxResults(limit);
        return typedQuery.getResultList();
    }

    private Predicate[] predicates(CriteriaBuilder cb, Root<?> root, Boolean enabled,
                                   Boolean isProtected, List<String> keywords,
                                   List<String> searchFields) {
        List<Predicate> result = new ArrayList<>();
        if (enabled != null) {
            result.add(enabled ? cb.isTrue(root.get("enabled")) : cb.isFalse(root.get("enabled")));
        }
        if (isProtected != null) {
            result.add(isProtected ? cb.isTrue(root.get("isProtected")) : cb.isFalse(root.get("isProtected")));
        }
        for (String keyword : keywords) {
            String pattern = "%" + escapeLike(keyword) + "%";
            List<Predicate> matches = new ArrayList<>();
            for (String field : searchFields) {
                Expression<String> text = cb.lower(cb.coalesce(root.get(field).as(String.class), ""));
                matches.add(cb.like(text, pattern, '\\'));
            }
            result.add(cb.or(matches.toArray(Predicate[]::new)));
        }
        return result.toArray(Predicate[]::new);
    }

    private Comparator<BaseRule> ruleComparator(String sortField, boolean ascending) {
        Comparator<BaseRule> comparator = switch (sortField) {
            case "createdAt" -> Comparator.comparing(BaseRule::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "priority" -> Comparator.comparing(BaseRule::getPriority,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(BaseRule::getUpdatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        comparator = comparator.thenComparing(BaseRule::getId,
                Comparator.nullsLast(Comparator.naturalOrder()));
        if (!ascending) {
            comparator = comparator.reversed();
        }
        return comparator;
    }

    private String normalizeSortField(String field) {
        return ALLOWED_SORT_FIELDS.contains(field) ? field : "updatedAt";
    }

    private List<String> tokenize(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return List.of(keyword.toLowerCase(Locale.ROOT).trim().split("\\s+"));
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private boolean belongsToGroup(String tags, String key, String value) {
        if ("_untagged".equals(key)) {
            return tags == null || tags.isEmpty();
        }
        return value != null && value.equals(parseTags(tags).get(key));
    }

    private Map<String, String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(tags);
            if (root == null || !root.isObject()) {
                return Map.of();
            }
            Map<String, String> parsed = new LinkedHashMap<>();
            root.properties().forEach(entry -> parsed.put(entry.getKey(), tagValue(entry.getValue())));
            return parsed;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String tagValue(JsonNode value) {
        return value.isTextual() ? value.asText() : value.toString();
    }

    private String groupId(String key, String value) {
        return key + "=" + value;
    }

    public record RuleQuery(Protocol protocol, Boolean enabled, Boolean isProtected,
                            String keyword, int page, int size, String sortField,
                            String direction) {
    }

    public record RuleQueryResult(List<BaseRule> rules, int page, int size,
                                  long totalElements, int totalPages) {
        public RuleQueryResult {
            rules = List.copyOf(rules);
        }

        @Override
        public List<BaseRule> rules() {
            return new ArrayList<>(rules);
        }
    }

    public record RuleGroupSummary(Map<String, List<String>> tagKeys,
                                   Map<String, Long> counts, long totalElements) {
        public RuleGroupSummary {
            tagKeys = Map.copyOf(tagKeys);
            counts = Map.copyOf(counts);
        }

        @Override
        public Map<String, List<String>> tagKeys() {
            return new LinkedHashMap<>(tagKeys);
        }

        @Override
        public Map<String, Long> counts() {
            return new LinkedHashMap<>(counts);
        }
    }

    public record RuleGroupContent(List<BaseRule> rules, long totalElements) {
        public RuleGroupContent {
            rules = List.copyOf(rules);
        }

        @Override
        public List<BaseRule> rules() {
            return new ArrayList<>(rules);
        }
    }

    private record TagRow(String id, String tags, Protocol protocol) {
    }
}
