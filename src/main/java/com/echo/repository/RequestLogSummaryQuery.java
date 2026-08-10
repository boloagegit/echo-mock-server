package com.echo.repository;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds only active request-log predicates so H2/SQLite can use composite indexes. */
@Repository
public class RequestLogSummaryQuery {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Result query(Filter filter, int pageNumber, int pageSize,
                        String sortField, boolean ascending) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> contentQuery = builder.createTupleQuery();
        Root<RequestLog> log = contentQuery.from(RequestLog.class);
        contentQuery.multiselect(
                log.get("id"), log.get("ruleId"), log.get("protocol"), log.get("method"),
                log.get("endpoint"), log.get("matched"), log.get("responseTimeMs"),
                log.get("matchTimeMs"), log.get("clientIp"), log.get("requestTime"),
                log.get("targetHost"), log.get("proxyStatus"), log.get("proxyError"),
                log.get("responseStatus"), builder.isNotNull(log.get("requestBody")),
                builder.isNotNull(log.get("responseBody")),
                builder.isNotNull(log.get("matchChain")));
        contentQuery.where(predicates(builder, log, filter));
        contentQuery.orderBy(order(builder, log, sortField, ascending));

        long offset = (long) pageNumber * pageSize;
        TypedQuery<Tuple> typedContent = entityManager.createQuery(contentQuery)
                .setFirstResult((int) Math.min(Integer.MAX_VALUE, offset))
                .setMaxResults(pageSize);
        List<SummaryRow> rows = typedContent.getResultList().stream()
                .map(this::toSummaryRow).toList();

        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<RequestLog> countLog = countQuery.from(RequestLog.class);
        countQuery.select(builder.count(countLog));
        countQuery.where(predicates(builder, countLog, filter));
        long totalElements = entityManager.createQuery(countQuery).getSingleResult();
        int totalPages = totalElements == 0 ? 0
                : (int) Math.min(Integer.MAX_VALUE,
                        (totalElements + pageSize - 1) / pageSize);
        return new Result(rows, totalElements, totalPages);
    }

    private Predicate[] predicates(CriteriaBuilder builder, Root<RequestLog> log, Filter filter) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.ruleId() != null) {
            predicates.add(builder.equal(log.get("ruleId"), filter.ruleId()));
        }
        if (filter.protocol() != null) {
            predicates.add(builder.equal(log.get("protocol"), filter.protocol()));
        }
        if (filter.matched() != null) {
            predicates.add(builder.equal(log.get("matched"), filter.matched()));
        }
        if (filter.keyword() != null) {
            String pattern = "%" + filter.keyword().toLowerCase(Locale.ROOT) + "%";
            predicates.add(builder.or(
                    builder.like(builder.lower(log.get("endpoint")), pattern),
                    builder.like(builder.lower(log.get("targetHost")), pattern),
                    builder.like(builder.lower(log.get("ruleId")), pattern)));
        }
        if (filter.afterId() != null) {
            predicates.add(builder.greaterThan(log.get("id"), filter.afterId()));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private List<Order> order(CriteriaBuilder builder, Root<RequestLog> log,
                              String sortField, boolean ascending) {
        Order primary = ascending ? builder.asc(log.get(sortField)) : builder.desc(log.get(sortField));
        Order tieBreaker = ascending ? builder.asc(log.get("id")) : builder.desc(log.get("id"));
        return List.of(primary, tieBreaker);
    }

    private SummaryRow toSummaryRow(Tuple row) {
        return new SummaryRow(
                row.get(0, Long.class), row.get(1, String.class), row.get(2, Protocol.class),
                row.get(3, String.class), row.get(4, String.class), row.get(5, Boolean.class),
                row.get(6, Number.class), row.get(7, Number.class), row.get(8, String.class),
                row.get(9, java.time.LocalDateTime.class), row.get(10, String.class),
                row.get(11, Number.class), row.get(12, String.class), row.get(13, Number.class),
                row.get(14, Boolean.class), row.get(15, Boolean.class), row.get(16, Boolean.class));
    }

    public record Filter(String ruleId, Protocol protocol, Boolean matched,
                         String keyword, Long afterId) {
    }

    public record SummaryRow(
            Long id, String ruleId, Protocol protocol, String method, String endpoint,
            Boolean matched, Number responseTimeMs, Number matchTimeMs, String clientIp,
            java.time.LocalDateTime requestTime, String targetHost, Number proxyStatus,
            String proxyError, Number responseStatus, Boolean hasRequestBody,
            Boolean hasResponseBody, Boolean hasMatchChain) {
    }

    public record Result(List<SummaryRow> rows, long totalElements, int totalPages) {
        public Result {
            rows = List.copyOf(rows);
        }
    }
}
