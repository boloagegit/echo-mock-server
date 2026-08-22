package com.echo.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Server-side response summary filtering and pagination shared by all database profiles. */
@Repository
public class ResponseSummaryQuery {

    private static final String USAGE_JOIN = """
            LEFT JOIN (
                SELECT usage_rows.response_id, SUM(usage_rows.usage_count) AS usage_count
                FROM (
                    SELECT response_id, COUNT(*) AS usage_count
                    FROM http_rules WHERE response_id IS NOT NULL GROUP BY response_id
                    UNION ALL
                    SELECT response_id, COUNT(*) AS usage_count
                    FROM jms_rules WHERE response_id IS NOT NULL GROUP BY response_id
                ) usage_rows
                GROUP BY usage_rows.response_id
            ) response_usage ON response_usage.response_id = r.id
            """;

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "r.id",
            "description", "r.description",
            "bodySize", "r.body_size",
            "usageCount", "COALESCE(response_usage.usage_count, 0)",
            "createdAt", "r.created_at",
            "updatedAt", "r.updated_at");

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Result query(Filter filter, int pageNumber, int pageSize,
                        String sortField, boolean ascending) {
        List<String> predicates = new ArrayList<>();
        if (filter.keyword() != null) {
            String keywordPredicate = "LOWER(r.description) LIKE :keyword ESCAPE '!'";
            if (parseIdKeyword(filter.keyword()) != null) {
                keywordPredicate = "(" + keywordPredicate + " OR r.id = :keywordId)";
            }
            predicates.add(keywordPredicate);
        }
        if ("SSE".equals(filter.contentType())) {
            predicates.add("r.content_type = 'SSE_EVENTS'");
        } else if ("GENERAL".equals(filter.contentType())) {
            predicates.add("(r.content_type IS NULL OR r.content_type <> 'SSE_EVENTS')");
        }
        if ("used".equals(filter.usage())) {
            predicates.add("COALESCE(response_usage.usage_count, 0) > 0");
        } else if ("unused".equals(filter.usage())) {
            predicates.add("COALESCE(response_usage.usage_count, 0) = 0");
        }

        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        String orderColumn = SORT_COLUMNS.getOrDefault(sortField, "r.updated_at");
        String direction = ascending ? " ASC" : " DESC";
        String selectSql = """
                SELECT r.id, r.description, r.body_size, r.content_type,
                       r.created_at, r.updated_at, r.extended_at,
                       COALESCE(response_usage.usage_count, 0)
                FROM responses r
                """ + USAGE_JOIN + where
                + " ORDER BY " + orderColumn + direction + ", r.id" + direction;

        Query contentQuery = entityManager.createNativeQuery(selectSql);
        bindKeyword(contentQuery, filter.keyword());
        long offset = (long) pageNumber * pageSize;
        contentQuery.setFirstResult((int) Math.min(Integer.MAX_VALUE, offset));
        contentQuery.setMaxResults(pageSize);

        @SuppressWarnings("unchecked")
        List<Object[]> rawRows = contentQuery.getResultList();
        List<SummaryRow> rows = rawRows.stream().map(this::toSummaryRow).toList();

        String countSql = "SELECT COUNT(*) FROM responses r " + USAGE_JOIN + where;
        Query countQuery = entityManager.createNativeQuery(countSql);
        bindKeyword(countQuery, filter.keyword());
        long totalElements = ((Number) countQuery.getSingleResult()).longValue();
        int totalPages = totalElements == 0 ? 0
                : (int) Math.min(Integer.MAX_VALUE, (totalElements + pageSize - 1) / pageSize);
        return new Result(rows, totalElements, totalPages);
    }

    private void bindKeyword(Query query, String keyword) {
        if (keyword != null) {
            String escaped = keyword.toLowerCase(Locale.ROOT)
                    .replace("!", "!!")
                    .replace("%", "!%")
                    .replace("_", "!_");
            query.setParameter("keyword", "%" + escaped + "%");
            Long keywordId = parseIdKeyword(keyword);
            if (keywordId != null) {
                query.setParameter("keywordId", keywordId);
            }
        }
    }

    private Long parseIdKeyword(String keyword) {
        if (keyword == null || !keyword.trim().matches("[0-9]+")) {
            return null;
        }
        try {
            return Long.valueOf(keyword.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private SummaryRow toSummaryRow(Object[] row) {
        return new SummaryRow(
                number(row[0]).longValue(),
                (String) row[1],
                row[2] == null ? 0 : number(row[2]).intValue(),
                (String) row[3],
                toLocalDateTime(row[4]),
                toLocalDateTime(row[5]),
                toLocalDateTime(row[6]),
                row[7] == null ? 0 : number(row[7]).intValue());
    }

    private Number number(Object value) {
        if (value instanceof Number number) { return number; }
        return Long.parseLong(String.valueOf(value));
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) { return null; }
        if (value instanceof LocalDateTime dateTime) { return dateTime; }
        if (value instanceof Timestamp timestamp) { return timestamp.toLocalDateTime(); }
        String text = String.valueOf(value).trim().replace(' ', 'T');
        return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    public record Filter(String keyword, String usage, String contentType) {
    }

    public record SummaryRow(
            Long id, String description, Integer bodySize, String contentType,
            LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime extendedAt,
            int usageCount) {
    }

    public record Result(List<SummaryRow> rows, long totalElements, int totalPages) {
        public Result {
            rows = List.copyOf(rows);
        }
    }
}
