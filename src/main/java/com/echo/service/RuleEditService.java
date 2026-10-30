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
import org.springframework.transaction.annotation.Isolation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read and store rules shared by all API in the management background: check fields, process response content and forwarding settings, write rules, and clear the rule cache.
 * Create, update, declarative files and import all go through {@link #save} to ensure consistent checking methods.
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

    /** The stored rules and the content returned to the front end. */
    public record Saved(BaseRule rule, RuleDto dto) {
    }

    public Optional<RuleDto> findDto(String id) {
        return findDto(id, false);
    }

    public Optional<RuleDto> findDto(String id, boolean includeBody) {
        return protocolHandlerRegistry.findById(id)
                .map(r -> protocolHandlerRegistry.toDto(r, getResponse(r.getResponseId()), includeBody));
    }

    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public Optional<RuleDto> findExportDto(String id) {
        return protocolHandlerRegistry.findById(id)
                .map(rule -> exportDto(rule, exportConnectionNames()));
    }

    /** All rules and response content are read from one consistent export transaction. */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public List<RuleDto> findAllDtos() {
        Map<String, String> names = exportConnectionNames();
        return protocolHandlerRegistry.findAllRules().stream()
                .map(rule -> exportDto(rule, names))
                .toList();
    }

    private RuleDto exportDto(BaseRule rule, Map<String, String> names) {
        Response response = getResponse(rule.getResponseId());
        if (rule.getResponseId() != null && response == null) {
            throw new IllegalArgumentException("Cannot export a rule whose response is missing");
        }
        RuleDto dto = protocolHandlerRegistry.toDto(rule, response, true);
        dto.setExportVersion(1);
        if (response != null) {
            dto.setResponseDescription(response.getDescription());
            dto.setResponseContentType(response.getContentType());
        }
        dto.setSourceConnectionName(names.get(RuleImportService.connectionKey(dto)));
        return dto;
    }

    private Map<String, String> exportConnectionNames() {
        Map<String, String> names = new HashMap<>();
        httpTargetConnectionService.ifPresent(service -> service.list().forEach(connection -> {
            names.put("HTTP:CONNECTION:" + connection.id(), connection.name());
            if (connection.defaultConnection() && connection.enabled()) {
                names.put("HTTP:DEFAULT_CONNECTION", connection.name());
            }
        }));
        jmsTargetConnectionService.ifPresent(service -> {
            var connections = service.list();
            boolean databaseDefault = connections.stream().anyMatch(connection -> !connection.legacy()
                    && connection.enabled() && connection.defaultConnection());
            connections.forEach(connection -> {
                names.put("JMS:CONNECTION:" + connection.id(), connection.name());
                if (connection.defaultConnection() && connection.enabled()
                        && (!connection.legacy() || !databaseDefault)) {
                    names.put("JMS:DEFAULT_CONNECTION", connection.name());
                }
            });
        });
        return names;
    }

    /** Read-only validation shared by import preview and atomic import. The caller passes a copy. */
    public void validateImport(RuleDto dto) {
        requireProtocolEnabled(dto.getProtocol());
        if (dto.getMatchKey() == null || dto.getMatchKey().isBlank()) {
            throw new IllegalArgumentException("matchKey is required");
        }
        validateRuleFields(dto);
        applyMatchSpec(dto);
        ProtocolHandler handler = protocolHandlerRegistry.getHandler(dto.getProtocol()).orElseThrow();
        handler.fromDto(dto);
        boolean faulting = dto.getFaultType() != null && !dto.getFaultType().isBlank()
                && !FaultType.NONE.name().equals(dto.getFaultType());
        if (faulting) {
            return;
        }
        if ("FORWARD".equalsIgnoreCase(dto.getAction())) {
            if (dto.getProtocol() == Protocol.HTTP) {
                httpTargetConnectionService.orElseThrow().validateForwardSelection(
                        dto.getForwardTargetMode(), dto.getHttpTargetConnectionId());
            } else {
                jmsTargetConnectionService.orElseThrow().validateForwardSelection(
                        dto.getForwardTargetMode(), dto.getJmsTargetConnectionId());
            }
        } else {
            validateSseRule(dto, dto.getResponseBody());
            validateResponseContent(dto);
        }
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

    /** A rule is stored in the same transaction as the responses it creates or overrides: no half of the changes are left behind if either fails. */
    @Transactional
    public Saved save(RuleDto dto) {
        validateRuleFields(dto);
        applyMatchSpec(dto);
        Response response;
        // The overwritten existing response is not saved until the rule is written: checks of the response (other SSE rules that use it) need to see the new settings for this rule
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
        // Use existing Response and overwrite its content (e.g. edit SSE event in rules)
        // Blank content is also considered to be rewritten (general responses can be cleared; SSE responses will be blocked by the following verification) and cannot be regarded as "inherited" and silently stored.
        } else if (dto.getResponseId() != null && Boolean.TRUE.equals(dto.getUpdateExistingResponse())
                && dto.getResponseBody() != null) {
            Response existing = responseService.findById(dto.getResponseId())
                    .orElseThrow(() -> new IllegalArgumentException("Response not found: " + dto.getResponseId()));
            validateSseRule(dto, dto.getResponseBody());
            validateResponseContent(dto);
            existing.setBody(dto.getResponseBody());
            // The type is determined by the rule: SSE. The response of the rule is SSE; when the rule is changed back to a normal response, the response is also changed back to normal.
            existing.setContentType(isSseRule(dto) ? Response.SSE : null);
            response = existing;
            rewritten = existing;
        // Use existing Response
        } else if (dto.getResponseId() != null && (dto.getResponseBody() == null || dto.getResponseBody().isBlank())) {
            response = responseService.findById(dto.getResponseId())
                    .orElseThrow(() -> new IllegalArgumentException("Response not found: " + dto.getResponseId()));
            validateSseRule(dto, response.getBody());
            if (isSseRule(dto) && !Response.isSse(response.getContentType())) {
                // The SSE rule response created by the old version is not marked as SSE; the content has passed SSE verification, and the type is added by the way.
                response.setContentType(Response.SSE);
                response = responseService.save(response);
            }
        } else {
            // Verify response content format
            validateSseRule(dto, dto.getResponseBody());
            validateResponseContent(dto);
            // Create new Response - automatically generate description
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
            // Still in the same transaction: when the check fails (for example, another SSE rule also responds with this) the rule is restored together with the response
            response = responseService.save(rewritten);
        }
        rulesChanged(dto.getProtocol());
        return new Saved(saved, handler.toDto(saved, response, false));
    }

    /** Write the creator (new rule) or use the original creator, and record the modifier this time. */
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

    /** The rules of a protocol have changed: clear the local rule cache and notify other nodes. */
    public void rulesChanged(Protocol protocol) {
        protocolHandlerRegistry.descriptor(protocol)
                .ifPresent(descriptor -> evictCache(descriptor.rulesCacheName()));
        cacheInvalidationService.ifPresent(s -> s.publishInvalidation(protocol));
    }

    /** All agreed rules are subject to change (e.g. Excel import). */
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
     * When the Scenario function is turned off, it is not allowed to create or change the Scenario settings.
     * The values of existing rules can be saved as they are to prevent users from accidentally destroying existing behavior when modifying other fields.
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
     * Check and normalize structured conditions (see {@link MatchSpecs}).
     * <p>
     * The rules are stored in the form of block replacement, and the old versions of UI, Excel and v1 files are not recognized. {@code matchSpec}: No structured conditions are sent.
     * When there are no old conditions, keep the structured conditions of the existing rules to avoid clearing the advanced conditions when editing other fields.
     * To clear the advanced conditions please send {@code {"all": []}} explicitly.
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
