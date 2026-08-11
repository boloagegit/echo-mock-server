package com.echo.service;

import com.echo.dto.RuleApplyDocument;
import com.echo.dto.RuleApplySchema;
import com.echo.entity.Protocol;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Describes and enforces the complete declarative-rule document contract. */
@Service
public class RuleApplyContractService {

    private static final List<String> HTTP_METHODS = Arrays.stream(HttpMethod.values())
            .map(HttpMethod::name)
            .toList();
    private static final List<String> PROTOCOLS = List.of("HTTP", "JMS");
    private static final List<String> ACTIONS = List.of("MOCK", "FORWARD");
    private static final List<String> FORWARD_TARGET_MODES =
            List.of("ORIGINAL_HOST", "DEFAULT_CONNECTION", "CONNECTION");
    private static final List<String> RESPONSE_CONTENT_TYPES = List.of("TEXT", "SSE_EVENTS");
    private static final List<String> FAULT_TYPES =
            List.of("NONE", "CONNECTION_RESET", "EMPTY_RESPONSE");
    private static final Pattern HEADER_NAME =
            Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final List<String> HTTP_ONLY = List.of("HTTP");
    private static final List<String> MOCK_ONLY = List.of("MOCK");
    private static final List<String> FORWARD_ONLY = List.of("FORWARD");

    private final RuleApplySchema schema = new RuleApplySchema(
            RuleApplyDocument.API_VERSION,
            RuleApplyDocument.KIND,
            List.of(
                    field("apiVersion", "string", List.of(RuleApplyDocument.API_VERSION), null, null, 32, null, null, null, "ALWAYS", null, false),
                    field("kind", "string", List.of(RuleApplyDocument.KIND), null, null, 32, null, null, null, "ALWAYS", null, false),
                    field("metadata.id", "string", null, null, null, 36, null, null, null, null, null, false),
                    field("metadata.resourceVersion", "integer", null, 0L, null, null, null, null, null, "EXISTING_RESOURCE", null, false),
                    field("spec.protocol", "string", PROTOCOLS, null, null, null, null, null, null, "ALWAYS", null, false),
                    field("spec.targetHost", "string", null, null, null, 255, null, HTTP_ONLY, null, null, null, false),
                    field("spec.matchKey", "string", null, null, null, 255, null, null, null, "ALWAYS", null, false),
                    field("spec.method", "string", HTTP_METHODS, null, null, null, null, HTTP_ONLY, null, "HTTP", null, false),
                    field("spec.bodyCondition", "string", null, null, null, null, null, null, null, null, null, false),
                    field("spec.queryCondition", "string", null, null, null, 500, null, HTTP_ONLY, null, null, null, false),
                    field("spec.headerCondition", "string", null, null, null, 500, null, HTTP_ONLY, null, null, null, false),
                    field("spec.priority", "integer", null, 0L, null, null, 0, null, null, null, null, false),
                    field("spec.description", "string", null, null, null, 255, null, null, null, null, null, false),
                    field("spec.enabled", "boolean", null, null, null, null, true, null, null, null, null, false),
                    field("spec.protected", "boolean", null, null, null, null, false, null, null, null, null, false),
                    field("spec.tags", "object", null, null, null, null, Map.of(), null, null, null, "string", false),
                    field("spec.responseId", "integer", null, 1L, null, null, null, null, MOCK_ONLY, null, null, false),
                    field("spec.responseBody", "any", null, null, null, null, null, null, MOCK_ONLY, null, null, false),
                    field("spec.responseDescription", "string", null, null, null, 255, null, null, MOCK_ONLY, null, null, false),
                    field("spec.status", "integer", null, 100L, 599L, null, 200, HTTP_ONLY, MOCK_ONLY, null, null, false),
                    field("spec.responseHeaders", "object", null, null, null, null, Map.of(), HTTP_ONLY, MOCK_ONLY, null, "string", false),
                    field("spec.delayMs", "integer", null, 0L, null, null, 0, null, null, null, null, false),
                    field("spec.maxDelayMs", "integer", null, 0L, null, null, null, null, null, null, null, false),
                    field("spec.sseEnabled", "boolean", null, null, null, null, false, HTTP_ONLY, MOCK_ONLY, null, null, false),
                    field("spec.sseLoopEnabled", "boolean", null, null, null, null, false, HTTP_ONLY, MOCK_ONLY, null, null, false),
                    field("spec.responseContentType", "string", RESPONSE_CONTENT_TYPES, null, null, null, null, HTTP_ONLY, MOCK_ONLY, null, null, true),
                    field("spec.action", "string", ACTIONS, null, null, null, "MOCK", HTTP_ONLY, null, null, null, false),
                    field("spec.forwardTargetMode", "string", FORWARD_TARGET_MODES, null, null, null, "ORIGINAL_HOST", HTTP_ONLY, FORWARD_ONLY, null, null, false),
                    field("spec.httpTargetConnectionId", "integer", null, 1L, null, null, null, HTTP_ONLY, FORWARD_ONLY, "HTTP_FORWARD_CONNECTION", null, false),
                    field("spec.faultType", "string", FAULT_TYPES, null, null, null, "NONE", null, null, null, null, false),
                    field("spec.scenarioName", "string", null, null, null, 100, null, null, null, null, null, false),
                    field("spec.requiredScenarioState", "string", null, null, null, 100, null, null, null, null, null, false),
                    field("spec.newScenarioState", "string", null, null, null, 100, null, null, null, null, null, false)
            ));

    public RuleApplySchema schema() {
        return schema;
    }

    public void validate(RuleApplyDocument document) {
        if (document == null) {
            fail("DOCUMENT_REQUIRED", "$", "Apply document is required");
        }
        requireExact("apiVersion", document.getApiVersion(), RuleApplyDocument.API_VERSION);
        requireExact("kind", document.getKind(), RuleApplyDocument.KIND);
        rejectUnknownFields("document", document.getUnknownFields());

        RuleApplyDocument.Metadata metadata = document.getMetadata();
        validateMetadata(metadata);

        RuleApplyDocument.Spec spec = document.getSpec();
        if (spec == null) {
            fail("REQUIRED", "spec", "spec is required");
        }
        rejectUnknownFields("spec", spec.getUnknownFields());
        validateCommonSpec(spec);
        if (spec.getProtocol() == Protocol.HTTP) {
            validateHttpSpec(spec);
        } else {
            validateJmsSpec(spec);
        }
    }

    private void validateMetadata(RuleApplyDocument.Metadata metadata) {
        if (metadata == null) {
            return;
        }
        rejectUnknownFields("metadata", metadata.getUnknownFields());
        if (hasText(metadata.getId())) {
            try {
                UUID.fromString(metadata.getId().trim());
            } catch (IllegalArgumentException e) {
                fail("UUID", "metadata.id", "metadata.id must be a UUID");
            }
        } else if (metadata.getResourceVersion() != null) {
            fail("VERSION_REQUIRES_ID", "metadata.resourceVersion",
                    "metadata.resourceVersion requires metadata.id");
        }
        minimum("metadata.resourceVersion", metadata.getResourceVersion(), 0);
    }

    private void validateCommonSpec(RuleApplyDocument.Spec spec) {
        if (spec.getProtocol() == null) {
            fail("REQUIRED", "spec.protocol", "spec.protocol is required");
        }
        requiredText("spec.matchKey", spec.getMatchKey());
        maxLength("spec.matchKey", spec.getMatchKey(), 255);
        maxLength("spec.description", spec.getDescription(), 255);
        maxLength("spec.responseDescription", spec.getResponseDescription(), 255);
        maxLength("spec.scenarioName", spec.getScenarioName(), 100);
        maxLength("spec.requiredScenarioState", spec.getRequiredScenarioState(), 100);
        maxLength("spec.newScenarioState", spec.getNewScenarioState(), 100);
        minimum("spec.priority", spec.getPriority(), 0);
        minimum("spec.delayMs", spec.getDelayMs(), 0);
        minimum("spec.maxDelayMs", spec.getMaxDelayMs(), 0);
        if (spec.getDelayMs() != null && spec.getMaxDelayMs() != null
                && spec.getMaxDelayMs() < spec.getDelayMs()) {
            fail("DELAY_RANGE", "spec.maxDelayMs",
                    "spec.maxDelayMs must be greater than or equal to spec.delayMs");
        }
        minimum("spec.responseId", spec.getResponseId(), 1);
        validateStringMap("spec.tags", spec.getTags(), false);
        validateCondition("spec.bodyCondition", spec.getBodyCondition());
        String faultType = normalized(spec.getFaultType(), "NONE");
        allowed("spec.faultType", faultType, FAULT_TYPES);
        if ((hasText(spec.getRequiredScenarioState()) || hasText(spec.getNewScenarioState()))
                && !hasText(spec.getScenarioName())) {
            fail("SCENARIO_NAME_REQUIRED", "spec.scenarioName",
                    "spec.scenarioName is required when scenario states are configured");
        }
    }

    private void validateHttpSpec(RuleApplyDocument.Spec spec) {
        requiredText("spec.method", spec.getMethod());
        allowed("spec.method", spec.getMethod(), HTTP_METHODS);
        if (!("*".equals(spec.getMatchKey()) || spec.getMatchKey().startsWith("/"))) {
            fail("HTTP_MATCH_KEY", "spec.matchKey",
                    "spec.matchKey must start with / or be *");
        }
        maxLength("spec.targetHost", spec.getTargetHost(), 255);
        rejectLineBreaks("spec.targetHost", spec.getTargetHost());
        maxLength("spec.queryCondition", spec.getQueryCondition(), 500);
        maxLength("spec.headerCondition", spec.getHeaderCondition(), 500);
        validateCondition("spec.queryCondition", spec.getQueryCondition());
        validateCondition("spec.headerCondition", spec.getHeaderCondition());

        String action = normalized(spec.getAction(), "MOCK");
        allowed("spec.action", action, ACTIONS);
        if (isFaulting(spec)) {
            validateFaultSpec(spec, "HTTP");
        } else if ("FORWARD".equals(action)) {
            validateForwardSpec(spec);
        } else {
            validateMockSpec(spec);
        }
    }

    private void validateMockSpec(RuleApplyDocument.Spec spec) {
        rejectPresent("spec.forwardTargetMode", spec.getForwardTargetMode(), "HTTP MOCK");
        rejectPresent("spec.httpTargetConnectionId", spec.getHttpTargetConnectionId(), "HTTP MOCK");
        range("spec.status", spec.getStatus(), 100, 599);
        validateStringMap("spec.responseHeaders", spec.getResponseHeaders(), true);
        if (Boolean.TRUE.equals(spec.getSseLoopEnabled()) && !Boolean.TRUE.equals(spec.getSseEnabled())) {
            fail("SSE_LOOP_REQUIRES_SSE", "spec.sseLoopEnabled",
                    "spec.sseLoopEnabled requires spec.sseEnabled");
        }
        if (hasText(spec.getResponseContentType())) {
            allowed("spec.responseContentType", spec.getResponseContentType(), RESPONSE_CONTENT_TYPES);
            String expected = Boolean.TRUE.equals(spec.getSseEnabled()) ? "SSE_EVENTS" : "TEXT";
            if (!expected.equals(spec.getResponseContentType())) {
                fail("CONTENT_TYPE_MISMATCH", "spec.responseContentType",
                        "spec.responseContentType must match spec.sseEnabled",
                        Map.of("expected", expected));
            }
        }
    }

    private void validateForwardSpec(RuleApplyDocument.Spec spec) {
        rejectPresent("spec.responseId", spec.getResponseId(), "HTTP FORWARD");
        rejectPresent("spec.responseBody", spec.getResponseBody(), "HTTP FORWARD");
        rejectPresent("spec.responseDescription", spec.getResponseDescription(), "HTTP FORWARD");
        rejectPresent("spec.status", spec.getStatus(), "HTTP FORWARD");
        rejectPresent("spec.responseHeaders", spec.getResponseHeaders(), "HTTP FORWARD");
        rejectTrue("spec.sseEnabled", spec.getSseEnabled(), "HTTP FORWARD");
        rejectTrue("spec.sseLoopEnabled", spec.getSseLoopEnabled(), "HTTP FORWARD");
        rejectPresent("spec.responseContentType", spec.getResponseContentType(), "HTTP FORWARD");

        String mode = normalized(spec.getForwardTargetMode(), "ORIGINAL_HOST");
        allowed("spec.forwardTargetMode", mode, FORWARD_TARGET_MODES);
        if ("CONNECTION".equals(mode)) {
            if (spec.getHttpTargetConnectionId() == null) {
                fail("REQUIRED", "spec.httpTargetConnectionId",
                        "spec.httpTargetConnectionId is required for CONNECTION forwarding");
            }
            minimum("spec.httpTargetConnectionId", spec.getHttpTargetConnectionId(), 1);
        } else {
            rejectPresent("spec.httpTargetConnectionId", spec.getHttpTargetConnectionId(), mode);
        }
    }

    private void validateJmsSpec(RuleApplyDocument.Spec spec) {
        rejectPresent("spec.targetHost", spec.getTargetHost(), "JMS");
        rejectPresent("spec.method", spec.getMethod(), "JMS");
        rejectPresent("spec.queryCondition", spec.getQueryCondition(), "JMS");
        rejectPresent("spec.headerCondition", spec.getHeaderCondition(), "JMS");
        rejectPresent("spec.status", spec.getStatus(), "JMS");
        rejectPresent("spec.responseHeaders", spec.getResponseHeaders(), "JMS");
        rejectPresent("spec.sseEnabled", spec.getSseEnabled(), "JMS");
        rejectPresent("spec.sseLoopEnabled", spec.getSseLoopEnabled(), "JMS");
        rejectPresent("spec.responseContentType", spec.getResponseContentType(), "JMS");
        rejectPresent("spec.action", spec.getAction(), "JMS");
        rejectPresent("spec.forwardTargetMode", spec.getForwardTargetMode(), "JMS");
        rejectPresent("spec.httpTargetConnectionId", spec.getHttpTargetConnectionId(), "JMS");
        if (isFaulting(spec)) {
            validateFaultSpec(spec, "JMS");
        }
    }

    private void validateFaultSpec(RuleApplyDocument.Spec spec, String protocol) {
        rejectPresent("spec.responseId", spec.getResponseId(), protocol + " FAULT");
        rejectPresent("spec.responseBody", spec.getResponseBody(), protocol + " FAULT");
        rejectPresent("spec.responseDescription", spec.getResponseDescription(), protocol + " FAULT");
        rejectPresent("spec.responseHeaders", spec.getResponseHeaders(), protocol + " FAULT");
        rejectTrue("spec.sseEnabled", spec.getSseEnabled(), protocol + " FAULT");
        rejectTrue("spec.sseLoopEnabled", spec.getSseLoopEnabled(), protocol + " FAULT");
        rejectPresent("spec.responseContentType", spec.getResponseContentType(), protocol + " FAULT");
        if ("HTTP".equals(protocol)) {
            if ("FORWARD".equals(normalized(spec.getAction(), "MOCK"))) {
                fail("FAULT_ACTION_CONFLICT", "spec.action",
                        "spec.action must be MOCK when spec.faultType is enabled");
            }
            rejectPresent("spec.forwardTargetMode", spec.getForwardTargetMode(), "HTTP FAULT");
            rejectPresent("spec.httpTargetConnectionId", spec.getHttpTargetConnectionId(), "HTTP FAULT");
            range("spec.status", spec.getStatus(), 100, 599);
        }
    }

    private boolean isFaulting(RuleApplyDocument.Spec spec) {
        return !"NONE".equals(normalized(spec.getFaultType(), "NONE"));
    }

    private void validateCondition(String path, String value) {
        if (!hasText(value)) {
            return;
        }
        for (String rawCondition : value.split(";", -1)) {
            String condition = rawCondition.trim();
            String operator = List.of("!=", "*=", "~=", "=").stream()
                    .filter(condition::contains)
                    .findFirst()
                    .orElse(null);
            if (operator == null) {
                fail("CONDITION_FORMAT", path, path + " contains an invalid condition");
            }
            int index = condition.indexOf(operator);
            String field = condition.substring(0, index).trim();
            String expected = condition.substring(index + operator.length()).trim();
            if (field.isEmpty() || expected.isEmpty()) {
                fail("CONDITION_FORMAT", path, path + " contains an invalid condition");
            }
            if ("~=".equals(operator)) {
                try {
                    Pattern.compile(expected);
                } catch (PatternSyntaxException e) {
                    fail("INVALID_REGEX", path, path + " contains an invalid regular expression");
                }
            }
        }
    }

    private void validateStringMap(String path, Map<String, String> value, boolean headers) {
        if (value == null) {
            return;
        }
        for (Map.Entry<String, String> entry : value.entrySet()) {
            if (!hasText(entry.getKey()) || entry.getValue() == null) {
                fail("MAP_STRING_VALUE", path, path + " keys and values must be strings");
            }
            if (headers) {
                if (!HEADER_NAME.matcher(entry.getKey()).matches()) {
                    fail("HEADER_NAME", path + "." + entry.getKey(), "Invalid HTTP header name");
                }
                if (containsLineBreak(entry.getValue())) {
                    fail("HEADER_VALUE", path + "." + entry.getKey(), "HTTP header values cannot contain line breaks");
                }
            }
        }
    }

    private void rejectUnknownFields(String path, Map<String, ?> unknownFields) {
        if (unknownFields != null && !unknownFields.isEmpty()) {
            String field = unknownFields.keySet().iterator().next();
            fail("UNKNOWN_FIELD", path + "." + field,
                    "Unknown " + path + " field: " + field,
                    Map.of("field", field));
        }
    }

    private void requireExact(String path, String actual, String expected) {
        if (!expected.equals(actual)) {
            fail("CONST", path, path + " must be " + expected, Map.of("expected", expected));
        }
    }

    private void requiredText(String path, String value) {
        if (!hasText(value)) {
            fail("REQUIRED", path, path + " is required");
        }
    }

    private void allowed(String path, String value, List<String> allowedValues) {
        if (!allowedValues.contains(value)) {
            fail("ALLOWED_VALUES", path, path + " contains an unsupported value",
                    Map.of("allowedValues", allowedValues));
        }
    }

    private void range(String path, Number value, long minimum, long maximum) {
        if (value != null && (value.longValue() < minimum || value.longValue() > maximum)) {
            fail("RANGE", path, path + " must be between " + minimum + " and " + maximum,
                    Map.of("minimum", minimum, "maximum", maximum));
        }
    }

    private void minimum(String path, Number value, long minimum) {
        if (value != null && value.longValue() < minimum) {
            fail("MINIMUM", path, path + " must be " + minimum + " or greater",
                    Map.of("minimum", minimum));
        }
    }

    private void maxLength(String path, String value, int maximum) {
        if (value != null && value.length() > maximum) {
            fail("MAX_LENGTH", path, path + " exceeds the maximum length",
                    Map.of("maximum", maximum));
        }
    }

    private void rejectLineBreaks(String path, String value) {
        if (containsLineBreak(value)) {
            fail("LINE_BREAK", path, path + " cannot contain line breaks");
        }
    }

    private void rejectPresent(String path, Object value, String context) {
        if (value != null) {
            fail("NOT_APPLICABLE", path, path + " is not applicable to " + context,
                    Map.of("context", context));
        }
    }

    private void rejectTrue(String path, Boolean value, String context) {
        if (Boolean.TRUE.equals(value)) {
            fail("NOT_APPLICABLE", path, path + " is not applicable to " + context,
                    Map.of("context", context));
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean containsLineBreak(String value) {
        return value != null && (value.contains("\r") || value.contains("\n"));
    }

    private static String normalized(String value, String defaultValue) {
        return hasText(value) ? value.trim() : defaultValue;
    }

    private static RuleApplySchema.Field field(
            String path,
            String type,
            List<String> allowedValues,
            Long minimum,
            Long maximum,
            Integer maxLength,
            Object defaultValue,
            List<String> protocols,
            List<String> actions,
            String requiredWhen,
            String valueType,
            boolean readOnly) {
        return new RuleApplySchema.Field(path, type, allowedValues, minimum, maximum,
                maxLength, defaultValue, protocols, actions, requiredWhen, valueType, readOnly);
    }

    private static void fail(String code, String path, String message) {
        throw new RuleApplyValidationException(code, path, message);
    }

    private static void fail(String code, String path, String message, Map<String, Object> details) {
        throw new RuleApplyValidationException(code, path, message, new LinkedHashMap<>(details));
    }
}
