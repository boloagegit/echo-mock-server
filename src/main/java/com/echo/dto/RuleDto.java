package com.echo.dto;

import com.echo.entity.Protocol;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Rule data transfer object
 * <p>
 * Unify the API return format of the HTTP/JMS rules.
 * Conversion method provided by ProtocolHandler: toDto() / fromDto()
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleDto {
    private String id;
    private Long version;
    private Protocol protocol;
    private String targetHost;
    private String matchKey;
    private String method;
    private String bodyCondition;
    private String queryCondition;
    private String headerCondition;
    /**
     * Structured condition (JSON object). When there is a value, it will prevail. The three condition fields of the old version must be empty;
     * Conditions that can be expressed using legacy strings will be converted to legacy fields when saved. To clear the advanced conditions please send {@code {"all": []}}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode matchSpec;
    /** Read only: This rule uses structured conditions (older versions of UI cannot edit their conditions). */
    private Boolean advancedMatch;
    private Integer priority;
    private String description;
    private Boolean enabled;
    private Boolean isProtected;
    private String tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime extendedAt;
    private String condition;
    private Long responseId;
    private String responseBody;

    /**
     * When it is true, responseBody overwrites the existing response pointed to by responseId (stored in the same transaction as the rule),
     * Instead of creating a new response. Only used in requests and will not appear in responses.
     */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private Boolean updateExistingResponse;
    private String responseDescription;
    private Integer status;
    private String responseHeaders;
    private Long delayMs;
    private Long maxDelayMs;
    private Boolean sseEnabled;
    private Boolean sseLoopEnabled;
    private String responseContentType;
    private String action;
    private String forwardTargetMode;
    private Long httpTargetConnectionId;
    private String jmsTargetConnectionId;
    private String faultType;
    private String scenarioName;
    private String requiredScenarioState;
    private String newScenarioState;
}
