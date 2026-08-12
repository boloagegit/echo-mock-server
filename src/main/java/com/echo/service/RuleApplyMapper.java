package com.echo.service;

import com.echo.dto.RuleApplyDocument;
import com.echo.dto.RuleDto;
import com.echo.entity.HttpRuleAction;
import com.echo.entity.JmsRuleAction;
import com.echo.entity.Protocol;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** RuleApplyDocument 與既有 RuleDto 的唯一轉換入口。 */
@Component
@RequiredArgsConstructor
public class RuleApplyMapper {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public RuleDto toRuleDto(RuleApplyDocument document) {
        RuleApplyDocument.Spec spec = document.getSpec();
        return RuleDto.builder()
                .protocol(spec.getProtocol())
                .targetHost(spec.getTargetHost())
                .matchKey(spec.getMatchKey())
                .method(spec.getMethod())
                .bodyCondition(spec.getBodyCondition())
                .queryCondition(spec.getQueryCondition())
                .headerCondition(spec.getHeaderCondition())
                .priority(spec.getPriority())
                .description(spec.getDescription())
                .enabled(spec.getEnabled())
                .isProtected(spec.getProtectedRule())
                .tags(writeMap(spec.getTags()))
                .responseId(spec.getResponseId())
                .responseBody(writeBody(spec.getResponseBody()))
                .responseDescription(spec.getResponseDescription())
                .status(spec.getStatus())
                .responseHeaders(writeMap(spec.getResponseHeaders()))
                .delayMs(spec.getDelayMs())
                .maxDelayMs(spec.getMaxDelayMs())
                .sseEnabled(spec.getSseEnabled())
                .sseLoopEnabled(spec.getSseLoopEnabled())
                .responseContentType(spec.getResponseContentType())
                .action(spec.getAction())
                .forwardTargetMode(spec.getForwardTargetMode())
                .httpTargetConnectionId(spec.getHttpTargetConnectionId())
                .jmsTargetConnectionId(spec.getJmsTargetConnectionId())
                .faultType(spec.getFaultType())
                .scenarioName(spec.getScenarioName())
                .requiredScenarioState(spec.getRequiredScenarioState())
                .newScenarioState(spec.getNewScenarioState())
                .build();
    }

    public RuleApplyDocument fromRuleDto(RuleDto dto) {
        RuleApplyDocument.Spec.SpecBuilder spec = RuleApplyDocument.Spec.builder()
                .protocol(dto.getProtocol())
                .matchKey(dto.getMatchKey())
                .bodyCondition(dto.getBodyCondition())
                .priority(dto.getPriority())
                .description(dto.getDescription())
                .enabled(dto.getEnabled())
                .protectedRule(dto.getIsProtected())
                .tags(readMap(dto.getTags()))
                .delayMs(dto.getDelayMs())
                .maxDelayMs(dto.getMaxDelayMs())
                .faultType(dto.getFaultType())
                .scenarioName(dto.getScenarioName())
                .requiredScenarioState(dto.getRequiredScenarioState())
                .newScenarioState(dto.getNewScenarioState());

        boolean http = dto.getProtocol() == Protocol.HTTP;
        boolean faulting = dto.getFaultType() != null && !"NONE".equalsIgnoreCase(dto.getFaultType());
        boolean forwarding = !faulting && "FORWARD".equalsIgnoreCase(dto.getAction());
        if (http) {
            spec.targetHost(dto.getTargetHost())
                    .method(dto.getMethod())
                    .queryCondition(dto.getQueryCondition())
                    .headerCondition(dto.getHeaderCondition())
                    .action(forwarding ? HttpRuleAction.FORWARD.name() : HttpRuleAction.MOCK.name());
        } else {
            spec.action(forwarding ? JmsRuleAction.FORWARD.name() : JmsRuleAction.MOCK.name());
        }
        if (forwarding) {
            spec.forwardTargetMode(dto.getForwardTargetMode());
            if (http) spec.httpTargetConnectionId(dto.getHttpTargetConnectionId());
            else spec.jmsTargetConnectionId(dto.getJmsTargetConnectionId());
        } else if (faulting) {
            if (http) {
                spec.status(dto.getStatus());
            }
        } else {
            spec.responseId(dto.getResponseId())
                    .responseBody(readBody(dto.getResponseBody()))
                    .responseDescription(dto.getResponseDescription());
            if (http) {
                spec.status(dto.getStatus())
                        .responseHeaders(readMap(dto.getResponseHeaders()))
                        .sseEnabled(dto.getSseEnabled())
                        .sseLoopEnabled(dto.getSseLoopEnabled())
                        .responseContentType(dto.getResponseContentType());
            }
        }

        return RuleApplyDocument.builder()
                .apiVersion(RuleApplyDocument.API_VERSION)
                .kind(RuleApplyDocument.KIND)
                .metadata(RuleApplyDocument.Metadata.builder()
                        .id(dto.getId())
                        .resourceVersion(dto.getVersion())
                        .build())
                .spec(spec.build())
                .build();
    }

    private String writeMap(Map<String, String> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid map value", e);
        }
    }

    private String writeBody(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid responseBody", e);
        }
    }

    private Map<String, String> readMap(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, STRING_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Stored JSON object is invalid", e);
        }
    }

    private JsonNode readBody(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return objectMapper.readTree(value);
            } catch (JsonProcessingException ignored) {
                // 非 JSON 內容仍要原樣保留為文字。
            }
        }
        return TextNode.valueOf(value);
    }
}
