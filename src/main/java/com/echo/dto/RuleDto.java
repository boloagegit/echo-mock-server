package com.echo.dto;

import com.echo.entity.Protocol;
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
    private Integer priority;
    private String description;
    private Boolean enabled;
    private Boolean isProtected;
    private String tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
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
    private String faultType;
    private String scenarioName;
    private String requiredScenarioState;
    private String newScenarioState;
}
