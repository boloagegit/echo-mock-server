package com.echo.service;

import com.echo.core.match.InvalidMatchSpecException;
import com.echo.dto.RuleDto;
import com.echo.entity.BaseRule;
import com.echo.entity.FaultType;
import com.echo.entity.HttpRuleAction;
import com.echo.entity.JmsRuleAction;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import com.echo.entity.ResponseContentType;
import com.echo.protocol.ProtocolDescriptor;
import com.echo.protocol.ProtocolHandler;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.util.CurrentOperator;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 管理後台各 API 共用的規則讀取與儲存：檢查欄位、處理回應內容與轉發設定、寫入規則並清除規則快取。
 * 建立、更新、宣告式文件與匯入都經過 {@link #save}，確保檢查方式一致。
 */
@Service
@RequiredArgsConstructor
public class RuleEditService {

    private final ProtocolHandlerRegistry protocolHandlerRegistry;
    private final ResponseService responseService;
    private final ResponseContentValidatorRegistry responseContentValidatorRegistry;
    private final CacheManager cacheManager;
    private final Optional<CacheInvalidationService> cacheInvalidationService;
    private final Optional<HttpTargetConnectionService> httpTargetConnectionService;
    private final Optional<JmsTargetConnectionService> jmsTargetConnectionService;

    @Value("${echo.features.scenarios-enabled:false}")
    private boolean scenariosEnabled;

    /** 儲存後的規則與回傳給前端的內容。 */
    public record Saved(BaseRule rule, RuleDto dto) {
    }

    public Optional<RuleDto> findDto(String id) {
        return findDto(id, false);
    }

    public Optional<RuleDto> findDto(String id, boolean includeBody) {
        return protocolHandlerRegistry.findById(id)
                .map(r -> protocolHandlerRegistry.toDto(r, getResponse(r.getResponseId()), includeBody));
    }

    /** 所有規則（含回應內容），供匯出使用。 */
    public List<RuleDto> findAllDtos() {
        return protocolHandlerRegistry.findAllRules().stream()
                .map(r -> protocolHandlerRegistry.toDto(r, getResponse(r.getResponseId())))
                .toList();
    }

    public void requireProtocolEnabled(Protocol protocol) {
        if (!protocolHandlerRegistry.isEnabled(protocol)) {
            throw new IllegalArgumentException(protocol + " is not enabled");
        }
    }

    public void validateScenarioFields(RuleDto dto) {
        boolean hasRequired = dto.getRequiredScenarioState() != null && !dto.getRequiredScenarioState().isBlank();
        boolean hasNew = dto.getNewScenarioState() != null && !dto.getNewScenarioState().isBlank();
        boolean hasName = dto.getScenarioName() != null && !dto.getScenarioName().isBlank();
        if ((hasRequired || hasNew) && !hasName) {
            throw new IllegalArgumentException(
                    "scenarioName is required when requiredScenarioState or newScenarioState is set");
        }
    }

    /** 規則與它建立或改寫的回應在同一個交易內儲存：任一失敗時都不會留下一半的變更。 */
    @Transactional
    public Saved save(RuleDto dto) {
        validateRuleFields(dto);
        applyMatchSpec(dto);
        Response response;
        // 改寫的既有回應在規則寫入之後才存：回應的檢查（其他使用它的 SSE 規則）要看到這條規則的新設定
        Response rewritten = null;
        boolean faulting = dto.getFaultType() != null
                && !dto.getFaultType().isBlank()
                && !FaultType.NONE.name().equals(dto.getFaultType());
        boolean httpForwarding = !faulting && dto.getProtocol() == Protocol.HTTP
                && HttpRuleAction.FORWARD.name().equalsIgnoreCase(dto.getAction());
        boolean jmsForwarding = !faulting && dto.getProtocol() == Protocol.JMS
                && JmsRuleAction.FORWARD.name().equalsIgnoreCase(dto.getAction());
        boolean forwarding = httpForwarding || jmsForwarding;
        if (faulting) {
            if (dto.getProtocol() == Protocol.HTTP) {
                dto.setAction(HttpRuleAction.MOCK.name());
            }
            dto.setResponseId(null);
            dto.setResponseBody(null);
            dto.setResponseDescription(null);
            dto.setResponseHeaders(null);
            dto.setForwardTargetMode(null);
            dto.setHttpTargetConnectionId(null);
            dto.setJmsTargetConnectionId(null);
            dto.setSseEnabled(false);
            dto.setSseLoopEnabled(false);
            response = null;
        } else if (forwarding) {
            if (httpForwarding) {
                httpTargetConnectionService.orElseThrow(() ->
                        new IllegalStateException("HTTP target connection service unavailable"))
                        .validateForwardSelection(
                                dto.getForwardTargetMode(), dto.getHttpTargetConnectionId());
                dto.setJmsTargetConnectionId(null);
            } else {
                jmsTargetConnectionService.orElseThrow(() ->
                        new IllegalStateException("JMS target connection service unavailable"))
                        .validateForwardSelection(
                                dto.getForwardTargetMode(), dto.getJmsTargetConnectionId());
                dto.setHttpTargetConnectionId(null);
            }
            dto.setResponseId(null);
            dto.setResponseBody(null);
            dto.setSseEnabled(false);
            dto.setSseLoopEnabled(false);
            response = null;
        // 使用現有 Response，並改寫它的內容（例如在規則裡編輯 SSE 事件）
        // 空白內容也算改寫（一般回應可以清空；SSE 回應會被下面的驗證擋下），不可當成「沿用」而默默不存
        } else if (dto.getResponseId() != null && Boolean.TRUE.equals(dto.getUpdateExistingResponse())
                && dto.getResponseBody() != null) {
            Response existing = responseService.findById(dto.getResponseId())
                    .orElseThrow(() -> new IllegalArgumentException("Response not found: " + dto.getResponseId()));
            validateSseRule(dto, dto.getResponseBody());
            validateResponseContent(dto);
            existing.setBody(dto.getResponseBody());
            // 跟著規則決定類型：SSE 規則的回應是 SSE；規則改回一般回應時，回應也改回一般
            existing.setContentType(isSseRule(dto) ? Response.SSE : null);
            response = existing;
            rewritten = existing;
        // 使用現有 Response
        } else if (dto.getResponseId() != null && (dto.getResponseBody() == null || dto.getResponseBody().isBlank())) {
            response = responseService.findById(dto.getResponseId())
                    .orElseThrow(() -> new IllegalArgumentException("Response not found: " + dto.getResponseId()));
            validateSseRule(dto, response.getBody());
            if (isSseRule(dto) && !Response.isSse(response.getContentType())) {
                // 舊版建立的 SSE 規則回應沒有標成 SSE；內容已通過 SSE 驗證，順便補上類型
                response.setContentType(Response.SSE);
                response = responseService.save(response);
            }
        } else {
            // 驗證回應內容格式
            validateSseRule(dto, dto.getResponseBody());
            validateResponseContent(dto);
            // 建立新 Response - 自動產生描述
            String desc = dto.getResponseDescription();
            if (desc == null || desc.isBlank()) {
                desc = protocolHandlerRegistry.generateDescription(dto);
            }
            response = responseService.save(Response.builder()
                    .description(desc)
                    .body(dto.getResponseBody())
                    .contentType(isSseRule(dto) ? Response.SSE : null)
                    .build());
            dto.setResponseId(response.getId());
        }
        dto.setResponseBody(null);

        ProtocolHandler handler = protocolHandlerRegistry.getHandler(dto.getProtocol())
                .orElseThrow(() -> new IllegalArgumentException("Unknown protocol: " + dto.getProtocol()));
        BaseRule candidate = handler.fromDto(dto);
        stampForSave(candidate);
        BaseRule saved = handler.save(candidate);
        if (rewritten != null) {
            // 仍在同一個交易內：檢查失敗（例如另一條 SSE 規則也用這個回應）時規則與回應一起復原
            response = responseService.save(rewritten);
        }
        rulesChanged(dto.getProtocol());
        return new Saved(saved, handler.toDto(saved, response, false));
    }

    /** 寫入建立者（新規則）或沿用原建立者，並記錄這次的修改者。 */
    public void stampForSave(BaseRule candidate) {
        String operator = CurrentOperator.username();
        if (candidate.getId() == null) {
            candidate.setCreatedBy(operator);
        } else {
            Optional<? extends BaseRule> existing = protocolHandlerRegistry.findById(candidate.getId());
            if (existing != null) {
                existing.ifPresent(rule -> candidate.setCreatedBy(rule.getCreatedBy()));
            }
        }
        candidate.setUpdatedBy(operator);
    }

    /** 一個協定的規則有變更：清除本機的規則快取並通知其他節點。 */
    public void rulesChanged(Protocol protocol) {
        protocolHandlerRegistry.descriptor(protocol)
                .ifPresent(descriptor -> evictCache(descriptor.rulesCacheName()));
        cacheInvalidationService.ifPresent(s -> s.publishInvalidation(protocol));
    }

    /** 所有協定的規則都可能有變更（例如 Excel 匯入）。 */
    public void allRulesChanged() {
        protocolHandlerRegistry.descriptors().forEach(protocol -> evictCache(protocol.rulesCacheName()));
        cacheInvalidationService.ifPresent(CacheInvalidationService::publishInvalidation);
    }

    private void evictCache(String cacheName) {
        var cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    /**
     * 關閉 Scenario 功能時，不允許建立或變更 Scenario 設定。
     * 既有規則的值可以原樣保存，避免使用者修改其他欄位時意外破壞既有行為。
     */
    private void validateScenarioFeature(RuleDto dto) {
        if (scenariosEnabled) {
            return;
        }
        BaseRule existing = dto.getId() == null
                ? null
                : protocolHandlerRegistry.findById(dto.getId()).orElse(null);
        if (sameScenario(existing, dto)) {
            return;
        }
        if (hasScenario(existing) || hasScenario(dto)) {
            throw new IllegalArgumentException("SCENARIOS_DISABLED");
        }
    }

    private static boolean sameScenario(BaseRule existing, RuleDto dto) {
        return existing != null
                && Objects.equals(normalizeScenarioValue(existing.getScenarioName()), normalizeScenarioValue(dto.getScenarioName()))
                && Objects.equals(normalizeScenarioValue(existing.getRequiredScenarioState()), normalizeScenarioValue(dto.getRequiredScenarioState()))
                && Objects.equals(normalizeScenarioValue(existing.getNewScenarioState()), normalizeScenarioValue(dto.getNewScenarioState()));
    }

    private static boolean hasScenario(BaseRule rule) {
        return rule != null && (notBlank(rule.getScenarioName())
                || notBlank(rule.getRequiredScenarioState())
                || notBlank(rule.getNewScenarioState()));
    }

    private static boolean hasScenario(RuleDto dto) {
        return notBlank(dto.getScenarioName())
                || notBlank(dto.getRequiredScenarioState())
                || notBlank(dto.getNewScenarioState());
    }

    private static String normalizeScenarioValue(String value) {
        return notBlank(value) ? value.trim() : null;
    }

    private void validateRuleFields(RuleDto dto) {
        validateScenarioFields(dto);
        validateScenarioFeature(dto);
        String value = dto.getFaultType();
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            FaultType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported faultType: " + value);
        }
    }

    /**
     * 檢查並正規化結構化條件（見 {@link MatchSpecs}）。
     * <p>
     * 規則以整筆取代的方式儲存，而舊版 UI、Excel 與 v1 文件不認得 {@code matchSpec}：沒有送出結構化條件、
     * 也沒有任何舊版條件時，保留既有規則的結構化條件，避免編輯其他欄位時把進階條件清掉。
     * 要清除進階條件請明確送出 {@code {"all": []}}。
     */
    private void applyMatchSpec(RuleDto dto) {
        JsonNode spec = dto.getMatchSpec();
        boolean hasSpec = spec != null && !spec.isNull() && !spec.isMissingNode();
        boolean hasLegacy = notBlank(dto.getBodyCondition()) || notBlank(dto.getQueryCondition())
                || notBlank(dto.getHeaderCondition());
        if (!hasSpec) {
            dto.setMatchSpec(null);
            if (!hasLegacy && dto.getId() != null) {
                protocolHandlerRegistry.findById(dto.getId())
                        .map(BaseRule::getMatchSpec)
                        .flatMap(MatchSpecs::read)
                        .ifPresent(dto::setMatchSpec);
            }
            return;
        }
        if (hasLegacy) {
            throw new IllegalArgumentException(
                    "matchSpec cannot be combined with bodyCondition, queryCondition or headerCondition");
        }
        ProtocolDescriptor protocol = protocolHandlerRegistry.descriptor(dto.getProtocol())
                .orElseThrow(() -> new IllegalArgumentException("Unknown protocol: " + dto.getProtocol()));
        MatchSpecs.Normalized normalized;
        try {
            normalized = MatchSpecs.normalize(spec, protocol.matchSources());
        } catch (InvalidMatchSpecException e) {
            throw new IllegalArgumentException("Invalid matchSpec: " + e.getMessage(), e);
        }
        dto.setBodyCondition(normalized.bodyCondition());
        dto.setQueryCondition(normalized.queryCondition());
        dto.setHeaderCondition(normalized.headerCondition());
        dto.setMatchSpec(normalized.advanced() ? MatchSpecs.read(normalized.matchSpec()).orElseThrow() : null);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isSseRule(RuleDto dto) {
        return dto.getProtocol() == Protocol.HTTP && Boolean.TRUE.equals(dto.getSseEnabled());
    }

    private void validateResponseContent(RuleDto dto) {
        if (dto.getResponseBody() != null && !dto.getResponseBody().isBlank()) {
            ResponseContentType contentType = ContentTypeConstraints.infer(dto.getProtocol(), dto.getSseEnabled());
            responseContentValidatorRegistry.getValidator(contentType).validate(dto.getResponseBody());
        }
    }

        private void validateSseRule(RuleDto dto, String body) {
        if (dto.getProtocol() != Protocol.HTTP || !Boolean.TRUE.equals(dto.getSseEnabled())) return;
        SseRuleSettings.validateMethod(dto.getMethod());
        SseRuleSettings.headers(dto.getResponseHeaders(), dto.getStatus() == null ? 200 : dto.getStatus());
        long minimum = dto.getDelayMs() == null ? 0 : dto.getDelayMs();
        long maximum = dto.getMaxDelayMs() == null ? minimum : dto.getMaxDelayMs();
        if (minimum < 0 || maximum < minimum) throw new IllegalArgumentException("SSE delay range 無效");
        SseRuleSettings.timeout(SseEventSequence.parse(body),
                maximum, Boolean.TRUE.equals(dto.getSseLoopEnabled()));
    }

    private Response getResponse(Long responseId) {
        return responseId != null ? responseService.findById(responseId).orElse(null) : null;
    }
}
