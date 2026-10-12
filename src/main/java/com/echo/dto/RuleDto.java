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
 * 規則資料傳輸物件
 * <p>
 * 統一 HTTP/JMS 規則的 API 回傳格式。
 * 轉換方法由 ProtocolHandler 提供：toDto() / fromDto()
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
     * 結構化條件（JSON 物件）。有值時以它為準，舊版三個條件欄位必須為空；
     * 可用舊版字串表達的條件在儲存時會轉成舊欄位。要清除進階條件請送 {@code {"all": []}}。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode matchSpec;
    /** 唯讀：此規則使用結構化條件（舊版 UI 無法編輯其條件）。 */
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
