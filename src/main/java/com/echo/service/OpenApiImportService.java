package com.echo.service;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.converter.SwaggerConverter;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI/Swagger 規格匯入服務
 * <p>
 * 解析 OpenAPI 3.x / Swagger 2.x 規格檔案，為每個 path + method 組合
 * 產生 HttpRule + Response 預覽資料。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiImportService {

    private final ObjectMapper objectMapper;

    @Data
    @Builder
    public static class OpenApiParseResult {
        private boolean success;
        private List<String> errors;
        private String title;
        private String version;
        private List<RuleDto> rules;
    }

    /**
     * 解析 OpenAPI spec 內容，產生規則預覽清單
     * <p>
     * 支援 OpenAPI 3.x 和 Swagger 2.x（自動偵測並轉換）
     */
    public OpenApiParseResult parse(String content) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);

        // 先嘗試 OpenAPI 3.x，失敗再嘗試 Swagger 2.x
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(content, null, options);

        if (result.getOpenAPI() == null) {
            // 嘗試 Swagger 2.x 轉換
            try {
                result = new SwaggerConverter().readContents(content, null, options);
            } catch (Exception e) {
                log.debug("Swagger 2.x conversion failed: {}", e.getMessage());
            }
        }

        if (result.getOpenAPI() == null) {
            List<String> errors = result.getMessages() != null ? result.getMessages() : List.of("Failed to parse OpenAPI spec");
            return OpenApiParseResult.builder()
                    .success(false)
                    .errors(errors)
                    .rules(List.of())
                    .build();
        }

        OpenAPI openAPI = result.getOpenAPI();
        String title = openAPI.getInfo() != null ? openAPI.getInfo().getTitle() : "OpenAPI Import";
        String version = openAPI.getInfo() != null ? openAPI.getInfo().getVersion() : "";

        List<RuleDto> rules = new ArrayList<>();

        if (openAPI.getPaths() != null) {
            openAPI.getPaths().forEach((path, pathItem) ->
                    extractOperations(path, pathItem, rules));
        }

        List<String> warnings = result.getMessages() != null ? result.getMessages() : List.of();

        return OpenApiParseResult.builder()
                .success(true)
                .errors(warnings)
                .title(title)
                .version(version)
                .rules(rules)
                .build();
    }

    private void extractOperations(String path, PathItem pathItem, List<RuleDto> rules) {
        Map<String, Operation> ops = new LinkedHashMap<>();
        if (pathItem.getGet() != null) { ops.put("GET", pathItem.getGet()); }
        if (pathItem.getPost() != null) { ops.put("POST", pathItem.getPost()); }
        if (pathItem.getPut() != null) { ops.put("PUT", pathItem.getPut()); }
        if (pathItem.getDelete() != null) { ops.put("DELETE", pathItem.getDelete()); }
        if (pathItem.getPatch() != null) { ops.put("PATCH", pathItem.getPatch()); }
        if (pathItem.getHead() != null) { ops.put("HEAD", pathItem.getHead()); }
        if (pathItem.getOptions() != null) { ops.put("OPTIONS", pathItem.getOptions()); }

        ops.forEach((method, operation) -> {
            RuleDto rule = buildRuleFromOperation(path, method, operation);
            if (rule != null) {
                rules.add(rule);
            }
        });
    }

    private RuleDto buildRuleFromOperation(String path, String method, Operation operation) {
        // 找最佳回應：優先 200/201，其次 2xx，最後取第一個
        String statusCode = "200";
        ApiResponse bestResponse = null;

        ApiResponses responses = operation.getResponses();
        if (responses != null && !responses.isEmpty()) {
            // 優先找 200 或 201
            if (responses.get("200") != null) {
                statusCode = "200";
                bestResponse = responses.get("200");
            } else if (responses.get("201") != null) {
                statusCode = "201";
                bestResponse = responses.get("201");
            } else {
                // 找第一個 2xx
                for (Map.Entry<String, ApiResponse> entry : responses.entrySet()) {
                    if (entry.getKey().startsWith("2")) {
                        statusCode = entry.getKey();
                        bestResponse = entry.getValue();
                        break;
                    }
                }
                // 都沒有就取第一個
                if (bestResponse == null) {
                    Map.Entry<String, ApiResponse> first = responses.entrySet().iterator().next();
                    statusCode = first.getKey();
                    bestResponse = first.getValue();
                }
            }
        }

        int httpStatus;
        try {
            httpStatus = Integer.parseInt(statusCode);
        } catch (NumberFormatException e) {
            httpStatus = 200;
        }

        // 產生回應 body
        String responseBody = "";
        String contentType = "application/json";
        if (bestResponse != null && bestResponse.getContent() != null) {
            Map.Entry<String, MediaType> mediaEntry = pickMediaType(bestResponse.getContent());
            if (mediaEntry != null) {
                contentType = mediaEntry.getKey();
                responseBody = generateResponseBody(mediaEntry.getValue());
            }
        }

        // 產生描述
        String desc = "[OpenAPI] " + method + " " + path;
        if (operation.getSummary() != null && !operation.getSummary().isBlank()) {
            desc += " - " + operation.getSummary();
        }

        // 產生 Content-Type header
        String responseHeaders = null;
        if (contentType != null && !contentType.equals("application/json")) {
            try {
                responseHeaders = objectMapper.writeValueAsString(Map.of("Content-Type", contentType));
            } catch (JsonProcessingException e) {
                // ignore
            }
        }

        // 產生標籤
        String tags = null;
        if (operation.getTags() != null && !operation.getTags().isEmpty()) {
            Map<String, String> tagMap = new LinkedHashMap<>();
            tagMap.put("source", "openapi");
            tagMap.put("tag", operation.getTags().get(0));
            try {
                tags = objectMapper.writeValueAsString(tagMap);
            } catch (JsonProcessingException e) {
                // ignore
            }
        } else {
            try {
                tags = objectMapper.writeValueAsString(Map.of("source", "openapi"));
            } catch (JsonProcessingException e) {
                // ignore
            }
        }

        return RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey(path)
                .method(method)
                .status(httpStatus)
                .description(desc)
                .responseBody(responseBody)
                .responseHeaders(responseHeaders)
                .tags(tags)
                .enabled(true)
                .priority(0)
                .delayMs(0L)
                .faultType("NONE")
                .build();
    }

    /**
     * 從 Content 中選取最佳 MediaType：優先 application/json，其次 application/xml，最後取第一個
     */
    private Map.Entry<String, MediaType> pickMediaType(Content content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        if (content.get("application/json") != null) {
            return Map.entry("application/json", content.get("application/json"));
        }
        if (content.get("application/xml") != null) {
            return Map.entry("application/xml", content.get("application/xml"));
        }
        return content.entrySet().iterator().next();
    }

    /**
     * 從 MediaType 產生回應 body：優先 example → examples → schema
     */
    @SuppressWarnings("unchecked")
    private String generateResponseBody(MediaType mediaType) {
        // 1. 直接 example
        if (mediaType.getExample() != null) {
            return toJsonString(mediaType.getExample());
        }

        // 2. examples 中取第一個
        if (mediaType.getExamples() != null && !mediaType.getExamples().isEmpty()) {
            var firstExample = mediaType.getExamples().values().iterator().next();
            if (firstExample.getValue() != null) {
                return toJsonString(firstExample.getValue());
            }
        }

        // 3. 從 schema 產生範例
        if (mediaType.getSchema() != null) {
            Object generated = generateFromSchema(mediaType.getSchema(), 0);
            if (generated != null) {
                return toJsonString(generated);
            }
        }

        return "";
    }

    /**
     * 從 Schema 遞迴產生範例值
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object generateFromSchema(Schema<?> schema, int depth) {
        if (schema == null || depth > 5) {
            return null;
        }

        // 有 example 直接用
        if (schema.getExample() != null) {
            return schema.getExample();
        }

        // 有 enum 取第一個
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            return schema.getEnum().get(0);
        }

        String type = schema.getType();

        // array
        if ("array".equals(type) && schema.getItems() != null) {
            Object item = generateFromSchema(schema.getItems(), depth + 1);
            return item != null ? List.of(item) : List.of();
        }

        // object 或有 properties
        if ("object".equals(type) || schema.getProperties() != null) {
            Map<String, Schema> props = schema.getProperties();
            if (props == null || props.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> obj = new LinkedHashMap<>();
            props.forEach((key, propSchema) -> {
                Object val = generateFromSchema(propSchema, depth + 1);
                obj.put(key, val != null ? val : defaultForType(propSchema));
            });
            return obj;
        }

        return defaultForType(schema);
    }

    private Object defaultForType(Schema<?> schema) {
        if (schema == null) {
            return null;
        }
        String type = schema.getType();
        String format = schema.getFormat();
        if (type == null) {
            return "string";
        }
        return switch (type) {
            case "string" -> stringDefault(format);
            case "integer" -> "int64".equals(format) ? 0L : 0;
            case "number" -> 0.0;
            case "boolean" -> true;
            default -> "string";
        };
    }

    private String stringDefault(String format) {
        if (format == null) {
            return "string";
        }
        return switch (format) {
            case "date" -> "2000-01-01";
            case "date-time" -> "2000-01-01T00:00:00Z";
            case "email" -> "user@example.com";
            case "uri", "url" -> "https://example.com";
            case "uuid" -> "550e8400-e29b-41d4-a716-446655440000";
            default -> "string";
        };
    }

    private String toJsonString(Object obj) {
        if (obj instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return String.valueOf(obj);
        }
    }
}
