package com.echo.service;

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

    public List<Response> findAll() {
        return responseRepository.findAll();
    }

    public long count() {
        return responseRepository.count();
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

    /** 取得 Response 摘要（含使用數量）- 使用聚合查詢 */
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
                        .contentType((String) r[3])
                        .usageCount(usageMap.getOrDefault(id, 0L).intValue())
                        .createdAt((LocalDateTime) r[4])
                        .updatedAt((LocalDateTime) r[5])
                        .extendedAt((LocalDateTime) r[6])
                        .build();
                })
                .toList();
    }

    /** 取得回應摘要分頁；列表只載入當頁，不載入大型 body。 */
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
                        .contentType(row.contentType())
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
        log.info("Saving response: {}", response.getDescription());
        Response saved = responseRepository.save(response);
        if (saved.getId() != null) {
            responseBodyCache.invalidate(saved.getId());
        }
        return saved;
    }

    @Transactional
    public void deleteById(Long id) {
        log.info("Deleting response: id={}", id);
        responseBodyCache.invalidate(id);
        responseRepository.deleteById(id);
    }

    /** 刪除 Response 並連帶刪除引用的 Rule，回傳刪除的 Rule 數量 */
    @Transactional
    public int deleteWithRules(Long id) {
        log.info("Deleting response with rules: id={}", id);
        int rulesDeleted = protocolHandlerRegistry.deleteByResponseId(id);
        responseBodyCache.invalidate(id);
        responseRepository.deleteById(id);
        if (rulesDeleted > 0) {
            evictRuleCache();
        }
        return rulesDeleted;
    }

    /** 刪除全部 Response 和 Rule，回傳刪除數量 */
    @Transactional
    public DeleteAllResult deleteAll() {
        int ruleCount = (int) protocolHandlerRegistry.getAllHandlers().stream()
                .mapToLong(h -> h.count()).sum();
        protocolHandlerRegistry.getAllHandlers().forEach(h -> h.deleteAll());
        int respCount = (int) responseRepository.count();
        responseRepository.deleteAll();
        responseBodyCache.invalidateAll();
        evictRuleCache();
        log.info("Deleted all: {} responses, {} rules", respCount, ruleCount);
        return new DeleteAllResult(respCount, ruleCount);
    }
    
    private void evictRuleCache() {
        for (String cacheName : CacheConfig.ALL_RULE_CACHES) {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    /** 計算孤兒規則數量（responseId 指向不存在的 Response）- 使用 Registry */
    public long countOrphanRules() {
        return protocolHandlerRegistry.countOrphanRules();
    }

    /** 計算孤兒回應數量（未被任何規則引用的 Response） */
    public long countOrphanResponses() {
        return responseRepository.countOrphanResponses();
    }

    /** 刪除所有孤兒回應（不考慮時間），回傳刪除數量 */
    @Transactional
    public int deleteOrphanResponses() {
        List<Long> orphanIds = responseRepository.findAllOrphanResponseIds();
        if (orphanIds.isEmpty()) {
            return 0;
        }
        for (Long id : orphanIds) {
            responseBodyCache.invalidate(id);
        }
        responseRepository.deleteAllById(orphanIds);
        log.info("Deleted {} orphan responses", orphanIds.size());
        return orphanIds.size();
    }

    /** 展延單一回應 */
    @Transactional
    public boolean extendResponse(Long id) {
        return extendResponses(List.of(id)) > 0;
    }

    /** 批次展延回應 */
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
