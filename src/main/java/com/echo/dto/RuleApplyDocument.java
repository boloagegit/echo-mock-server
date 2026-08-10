package com.echo.dto;

import com.echo.entity.Protocol;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宣告式規則文件。
 *
 * <p>metadata.id 缺省時建立新規則；有 id 時依 resourceVersion 更新既有規則。
 * spec 表示完整期望狀態，而不是局部 patch。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuleApplyDocument {

    public static final String API_VERSION = "echo.mock/v1";
    public static final String KIND = "Rule";

    private String apiVersion;
    private String kind;
    private Metadata metadata;
    private Spec spec;

    @JsonIgnore
    @Builder.Default
    private Map<String, JsonNode> unknownFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnknownField(String name, JsonNode value) {
        unknownFields.put(name, value);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Metadata {
        private String id;
        private Long resourceVersion;

        @JsonIgnore
        @Builder.Default
        private Map<String, JsonNode> unknownFields = new LinkedHashMap<>();

        @JsonAnySetter
        public void captureUnknownField(String name, JsonNode value) {
            unknownFields.put(name, value);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Spec {
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

        @JsonProperty("protected")
        private Boolean protectedRule;

        private Map<String, String> tags;
        private Long responseId;
        private JsonNode responseBody;
        private String responseDescription;
        private Integer status;
        private Map<String, String> responseHeaders;
        private Long delayMs;
        private Long maxDelayMs;
        private Boolean sseEnabled;
        private Boolean sseLoopEnabled;
        private String responseContentType;
        private String action;
        private String forwardTargetMode;
        private Long httpTargetConnectionId;

        @JsonIgnore
        @Builder.Default
        private Map<String, JsonNode> unknownFields = new LinkedHashMap<>();

        @JsonAnySetter
        public void captureUnknownField(String name, JsonNode value) {
            unknownFields.put(name, value);
        }
    }
}
