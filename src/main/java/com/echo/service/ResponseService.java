package com.echo.service;

import com.echo.config.AfterCommit;
import com.echo.config.CacheConfig;
import com.echo.entity.Response;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.ResponseRepository;
import com.echo.repository.ResponseSummaryQuery;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResponseService {

    private final ResponseRepository responseRepository;
    private final ResponseSummaryQuery responseSummaryQuery;
    private final ProtocolHandlerRegistry protocolHandlerRegistry;
    private final Cache<Long, String> responseBodyCache;
    private final CacheManager cacheManager;
    private final Optional<CacheInvalidationService> cacheInvalidationService;

    public List<Response> findAll() {
        return responseRepository.findAll();
    }

    public long count() {
        return responseRepository.count();
    }

    public Map<String, Object> getBodyCacheStats() {
        Map<String, Object> result = new HashMap<>();
        result.put("entries", responseBodyCache.estimatedSize());
        responseBodyCache.policy().eviction().ifPresent(eviction -> {
            result.put("weightedSize", eviction.weightedSize().orElse(0L));
            result.put("maximumWeight", eviction.getMaximum());
        });
        var stats = responseBodyCache.stats();
        result.put("requestCount", stats.requestCount());
        result.put("evictionCount", stats.evictionCount());
        return result;
    }

    public Optional<Response> findById(Long id) {
        return responseRepository.findById(id);
    }

    public List<Response> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return findAll();
        }
        return responseRepository.findByDescriptionContainingIgnoreCase(keyword);
    }

    /** Get Response summary (including usage quantity) - use aggregate query */
    public List<ResponseSummary> findAllWithUsage() {
        List<Object[]> summaries = responseRepository.findAllSummary();
        Map<Long, Long> usageMap = new HashMap<>();
        protocolHandlerRegistry.getAllHandlers().forEach(h ->
                h.countGroupByResponseId().forEach(row ->
                        usageMap.merge((Long) row[0], (Long) row[1], Long::sum)));
        
        return summaries.stream()
                .map(r -> {
                    Long id = (Long) r[0];
                    return ResponseSummary.builder()
                        .id(id)
                        .description((String) r[1])
                        .bodySize((Integer) r[2])
                        .contentType(Response.normalizeContentType((String) r[3]))
                        .usageCount(usageMap.getOrDefault(id, 0L).intValue())
                        .createdAt((LocalDateTime) r[4])
                        .updatedAt((LocalDateTime) r[5])
                        .extendedAt((LocalDateTime) r[6])
                        .build();
                })
                .toList();
    }

    /** Get response summary pagination; the list only loads the current page, not the large body. */
    public ResponseSummaryPage findSummaryPage(String keyword, String usage, String contentType,
                                               int requestedPage, int requestedSize,
                                               String sortField, String direction) {
        int pageSize = Math.max(1, Math.min(200, requestedSize));
        int page = Math.max(0, requestedPage);
        String normalizedKeyword = normalize(keyword);
        String normalizedUsage = normalizeOption(usage, "used", "unused");
        String normalizedContentType = normalizeOption(contentType, "GENERAL", "SSE");
        boolean ascending = "asc".equalsIgnoreCase(direction);
        ResponseSummaryQuery.Result result = responseSummaryQuery.query(
                new ResponseSummaryQuery.Filter(normalizedKeyword, normalizedUsage, normalizedContentType),
                page, pageSize, sortField, ascending);

        int effectivePage = result.totalPages() == 0 ? 0 : Math.min(page, result.totalPages() - 1);
        if (effectivePage != page) {
            result = responseSummaryQuery.query(
                    new ResponseSummaryQuery.Filter(normalizedKeyword, normalizedUsage, normalizedContentType),
                    effectivePage, pageSize, sortField, ascending);
        }
        List<ResponseSummary> summaries = result.rows().stream()
                .map(row -> ResponseSummary.builder()
                        .id(row.id())
                        .description(row.description())
                        .bodySize(row.bodySize())
                        .contentType(Response.normalizeContentType(row.contentType()))
                        .usageCount(row.usageCount())
                        .createdAt(row.createdAt())
                        .updatedAt(row.updatedAt())
                        .extendedAt(row.extendedAt())
                        .build())
                .toList();
        return new ResponseSummaryPage(summaries, effectivePage, pageSize,
                result.totalElements(), result.totalPages());
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) { return null; }
        return value.trim();
    }

    private String normalizeOption(String value, String first, String second) {
        if (first.equals(value) || second.equals(value)) { return value; }
        return null;
    }

    @Transactional
    public Response save(Response response) {
        response.setContentType(Response.normalizeContentType(response.getContentType()));
        validateSseContent(response);
        log.info("Saving response: {}", response.getDescription());
        boolean existing = response.getId() != null;
        // Detached metadata-only updates can retain the cache. A managed entity may
        // already have been mutated in this transaction, so its current contents
        // cannot be used as an old-value snapshot; invalidate conservatively.
        boolean contentChanged = existing && contentChanged(response);
        Response saved = responseRepository.save(response);
        if (contentChanged) {
            bodiesChanged(List.of(saved.getId()));
        }
        return saved;
    }

    private boolean contentChanged(Response response) {
        return responseRepository.findById(response.getId())
                .map(current -> current == response || !Objects.equals(current.getBody(), response.getBody())
                        || !Objects.equals(Response.normalizeContentType(current.getContentType()), response.getContentType()))
                .orElse(true);
    }

    private void validateSseContent(Response response) {
        if (Response.isSse(response.getContentType())) SseEventSequence.parse(response.getBody());
        if (response.getId() == null) return;
        for (var rule : protocolHandlerRegistry.findByResponseId(response.getId())) {
            if (rule instanceof com.echo.entity.HttpRule httpRule && Boolean.TRUE.equals(httpRule.getSseEnabled())) {
                long minimum = rule.getDelayMs() == null ? 0 : rule.getDelayMs();
                long maximum = rule.getMaxDelayMs() == null ? minimum : Math.max(minimum, rule.getMaxDelayMs());
                SseRuleSettings.timeout(SseEventSequence.parse(response.getBody()), maximum,
                        Boolean.TRUE.equals(httpRule.getSseLoopEnabled()));
            }
        }
    }

    @Transactional
    public void deleteById(Long id) {
        log.info("Deleting response: id={}", id);
        responseRepository.deleteById(id);
        bodiesChanged(List.of(id));
    }

    /** Delete Response and delete the referenced Rule, and return the number of deleted Rule */
    @Transactional
    public int deleteWithRules(Long id) {
        log.info("Deleting response with rules: id={}", id);
        int rulesDeleted = protocolHandlerRegistry.deleteByResponseId(id);
        responseRepository.deleteById(id);
        bodiesChanged(List.of(id));
        if (rulesDeleted > 0) {
            evictRuleCache();
            cacheInvalidationService.ifPresent(CacheInvalidationService::publishInvalidation);
        }
        return rulesDeleted;
    }

    /** Delete all Response and Rule, return the deletion quantity */
    @Transactional
    public DeleteAllResult deleteAll() {
        int ruleCount = (int) protocolHandlerRegistry.getAllHandlers().stream()
                .mapToLong(h -> h.count()).sum();
        protocolHandlerRegistry.getAllHandlers().forEach(h -> h.deleteAll());
        int respCount = (int) responseRepository.count();
        responseRepository.deleteAll();
        responseBodyCache.invalidateAll();
        AfterCommit.schedule(new AllBodies(responseBodyCache), responseBodyCache::invalidateAll);
        evictRuleCache();
        cacheInvalidationService.ifPresent(s -> {
            s.publishAllResponsesChanged();
            s.publishInvalidation();
        });
        log.info("Deleted all: {} responses, {} rules", respCount, ruleCount);
        return new DeleteAllResult(respCount, ruleCount);
    }
    
    /** Invalidate the local response-body cache, repeat after commit, and notify other instances. */
    private void bodiesChanged(List<Long> ids) {
        responseBodyCache.invalidateAll(ids);
        AfterCommit.schedule(new Bodies(responseBodyCache, List.copyOf(ids)),
                () -> responseBodyCache.invalidateAll(ids));
        cacheInvalidationService.ifPresent(s -> s.publishResponsesChanged(ids));
    }

    private record Bodies(Object cache, List<Long> ids) {
    }

    private record AllBodies(Object cache) {
    }

    private void evictRuleCache() {
        for (String cacheName : CacheConfig.ALL_RULE_CACHES) {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    /** Counting number of orphan rules (responseId points to non-existent Response) - use Registry */
    public long countOrphanRules() {
        return protocolHandlerRegistry.countOrphanRules();
    }

    /** Counting the number of orphan responses (Response not referenced by any rule) */
    public long countOrphanResponses() {
        return responseRepository.countOrphanResponses();
    }

    /** Delete all orphan responses (regardless of time) and return the deletion number */
    @Transactional
    public int deleteOrphanResponses() {
        List<Long> orphanIds = responseRepository.findAllOrphanResponseIds();
        if (orphanIds.isEmpty()) {
            return 0;
        }
        responseRepository.deleteAllById(orphanIds);
        bodiesChanged(orphanIds);
        log.info("Deleted {} orphan responses", orphanIds.size());
        return orphanIds.size();
    }

    /** Delayed single response */
    @Transactional
    public boolean extendResponse(Long id) {
        return extendResponses(List.of(id)) > 0;
    }

    /** Batch extension response */
    @Transactional
    public int extendResponses(List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return responseRepository.extendResponses(ids, LocalDateTime.now());
    }

    public record DeleteAllResult(int deletedResponses, int deletedRules) {}

    public record ResponseSummaryPage(
            List<ResponseSummary> results,
            int page,
            int size,
            long totalElements,
            int totalPages) {
        public ResponseSummaryPage {
            results = List.copyOf(results);
        }
    }

    @Getter
    @Builder
    public static class ResponseSummary {
        private Long id;
        private String description;
        private Integer bodySize;
        private String contentType;
        private int usageCount;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDateTime extendedAt;
    }
}
