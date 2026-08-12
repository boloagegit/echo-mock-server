package com.echo.service;

import com.echo.dto.RuleApplyDocument;
import com.echo.dto.RuleApplySchema;
import com.echo.entity.Protocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleApplyContractServiceTest {

    private RuleApplyContractService contract;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        contract = new RuleApplyContractService(true);
        objectMapper = new ObjectMapper();
    }

    @Test
    void schemaOmitsScenarioFieldsWhenFeatureIsDisabled() {
        RuleApplyContractService disabledContract = new RuleApplyContractService(false);

        assertThat(disabledContract.schema().fields())
                .extracting(RuleApplySchema.Field::path)
                .doesNotContain("spec.scenarioName", "spec.requiredScenarioState", "spec.newScenarioState");
    }

    @Test
    void schemaDocumentsEverySupportedLeafFieldExactlyOnce() {
        Set<String> expected = Set.of(
                "apiVersion", "kind", "metadata.id", "metadata.resourceVersion",
                "spec.protocol", "spec.targetHost", "spec.matchKey", "spec.method",
                "spec.bodyCondition", "spec.queryCondition", "spec.headerCondition",
                "spec.priority", "spec.description", "spec.enabled", "spec.protected",
                "spec.tags", "spec.responseId", "spec.responseBody", "spec.responseDescription",
                "spec.status", "spec.responseHeaders", "spec.delayMs", "spec.maxDelayMs",
                "spec.sseEnabled", "spec.sseLoopEnabled", "spec.responseContentType",
                "spec.action", "spec.forwardTargetMode", "spec.httpTargetConnectionId",
                "spec.jmsTargetConnectionId",
                "spec.faultType", "spec.scenarioName", "spec.requiredScenarioState",
                "spec.newScenarioState");

        RuleApplySchema schema = contract.schema();
        Set<String> actual = schema.fields().stream()
                .map(RuleApplySchema.Field::path)
                .collect(Collectors.toSet());

        assertThat(schema.apiVersion()).isEqualTo(RuleApplyDocument.API_VERSION);
        assertThat(schema.kind()).isEqualTo(RuleApplyDocument.KIND);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(schema.fields()).extracting(RuleApplySchema.Field::path).doesNotHaveDuplicates();
        assertThat(schema.fields().stream()
                .filter(RuleApplySchema.Field::readOnly)
                .map(RuleApplySchema.Field::path))
                .contains("apiVersion", "kind", "metadata.id", "metadata.resourceVersion");
    }

    @Test
    void createRejectsClientSuppliedIdentity() {
        RuleApplyDocument document = httpMock();
        document.setMetadata(RuleApplyDocument.Metadata.builder()
                .id("997a9b59-e57a-4777-9522-580cf38a5422")
                .build());

        assertValidation(() -> contract.validateForCreate(document),
                "SYSTEM_FIELD_READ_ONLY", "metadata.id");
    }

    @Test
    void updateRequiresPathBoundIdentityAndResourceVersion() {
        String expectedId = "997a9b59-e57a-4777-9522-580cf38a5422";
        RuleApplyDocument document = httpMock();
        document.setMetadata(RuleApplyDocument.Metadata.builder()
                .id("4e8cae15-ded1-4856-98ea-74e8719fd52a")
                .resourceVersion(0L)
                .build());

        assertValidation(() -> contract.validateForUpdate(document, expectedId),
                "RESOURCE_ID_MISMATCH", "metadata.id");

        document.getMetadata().setId(expectedId);
        document.getMetadata().setResourceVersion(null);
        assertValidation(() -> contract.validateForUpdate(document, expectedId),
                "SYSTEM_FIELD_REQUIRED", "metadata.resourceVersion");
    }

    @Test
    void acceptsHttpMockHttpForwardAndJmsDocuments() {
        RuleApplyDocument httpMock = httpMock();
        RuleApplyDocument httpForward = base(Protocol.HTTP, "/orders")
                .spec(RuleApplyDocument.Spec.builder()
                        .protocol(Protocol.HTTP)
                        .matchKey("/orders")
                        .method("POST")
                        .action("FORWARD")
                        .forwardTargetMode("ORIGINAL_HOST")
                        .build())
                .build();
        RuleApplyDocument jms = base(Protocol.JMS, "ORDER.REQUEST.Q").build();

        assertThatCode(() -> contract.validate(httpMock)).doesNotThrowAnyException();
        assertThatCode(() -> contract.validate(httpForward)).doesNotThrowAnyException();
        assertThatCode(() -> contract.validate(jms)).doesNotThrowAnyException();
    }

    @Test
    void acceptsJmsForwardAndRequiresAConnectionForExplicitMode() {
        RuleApplyDocument forward = base(Protocol.JMS, "ORDER.REQUEST.Q")
                .spec(RuleApplyDocument.Spec.builder()
                        .protocol(Protocol.JMS)
                        .matchKey("ORDER.REQUEST.Q")
                        .action("FORWARD")
                        .forwardTargetMode("CONNECTION")
                        .jmsTargetConnectionId("7")
                        .build())
                .build();

        assertThatCode(() -> contract.validate(forward)).doesNotThrowAnyException();

        forward.getSpec().setJmsTargetConnectionId(null);
        assertValidation(forward, "REQUIRED", "spec.jmsTargetConnectionId");
    }

    @Test
    void rejectsOriginalHostModeForJmsForwarding() {
        RuleApplyDocument forward = base(Protocol.JMS, "ORDER.REQUEST.Q")
                .spec(RuleApplyDocument.Spec.builder()
                        .protocol(Protocol.JMS)
                        .matchKey("ORDER.REQUEST.Q")
                        .action("FORWARD")
                        .forwardTargetMode("ORIGINAL_HOST")
                        .build())
                .build();

        assertValidation(forward, "ALLOWED_VALUES", "spec.forwardTargetMode");
    }

    @Test
    void acceptsFaultAndScenarioDocuments() {
        RuleApplyDocument httpFault = base(Protocol.HTTP, "/fault")
                .spec(RuleApplyDocument.Spec.builder()
                        .protocol(Protocol.HTTP)
                        .matchKey("/fault")
                        .method("GET")
                        .action("MOCK")
                        .status(503)
                        .faultType("EMPTY_RESPONSE")
                        .scenarioName("checkout")
                        .requiredScenarioState("Started")
                        .newScenarioState("Failed")
                        .build())
                .build();
        RuleApplyDocument jmsFault = base(Protocol.JMS, "ORDER.Q")
                .spec(RuleApplyDocument.Spec.builder()
                        .protocol(Protocol.JMS)
                        .matchKey("ORDER.Q")
                        .faultType("CONNECTION_RESET")
                        .build())
                .build();

        assertThatCode(() -> contract.validate(httpFault)).doesNotThrowAnyException();
        assertThatCode(() -> contract.validate(jmsFault)).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidAllowedValueWithExactPath() {
        RuleApplyDocument document = httpMock();
        document.getSpec().setMethod("FETCH");

        assertValidation(document, "ALLOWED_VALUES", "spec.method");
    }

    @Test
    void rejectsProtocolSpecificAndActionSpecificFields() {
        RuleApplyDocument jms = base(Protocol.JMS, "ORDER.Q").build();
        jms.getSpec().setStatus(200);
        assertValidation(jms, "NOT_APPLICABLE", "spec.status");

        RuleApplyDocument forward = base(Protocol.HTTP, "/forward")
                .spec(RuleApplyDocument.Spec.builder()
                        .protocol(Protocol.HTTP)
                        .matchKey("/forward")
                        .method("GET")
                        .action("FORWARD")
                        .forwardTargetMode("ORIGINAL_HOST")
                        .responseBody(objectMapper.getNodeFactory().textNode("ignored"))
                        .build())
                .build();
        assertValidation(forward, "NOT_APPLICABLE", "spec.responseBody");
    }

    @Test
    void rejectsInvalidCrossFieldCombinations() {
        RuleApplyDocument connectionForward = base(Protocol.HTTP, "/forward")
                .spec(RuleApplyDocument.Spec.builder()
                        .protocol(Protocol.HTTP)
                        .matchKey("/forward")
                        .method("GET")
                        .action("FORWARD")
                        .forwardTargetMode("CONNECTION")
                        .build())
                .build();
        assertValidation(connectionForward, "REQUIRED", "spec.httpTargetConnectionId");

        RuleApplyDocument sseLoop = httpMock();
        sseLoop.getSpec().setSseLoopEnabled(true);
        sseLoop.getSpec().setSseEnabled(false);
        assertValidation(sseLoop, "SSE_LOOP_REQUIRES_SSE", "spec.sseLoopEnabled");

        RuleApplyDocument delays = httpMock();
        delays.getSpec().setDelayMs(100L);
        delays.getSpec().setMaxDelayMs(50L);
        assertValidation(delays, "DELAY_RANGE", "spec.maxDelayMs");

        RuleApplyDocument scenarioWithoutName = httpMock();
        scenarioWithoutName.getSpec().setNewScenarioState("Paid");
        assertValidation(scenarioWithoutName, "SCENARIO_NAME_REQUIRED", "spec.scenarioName");

        RuleApplyDocument ambiguousFault = httpMock();
        ambiguousFault.getSpec().setFaultType("CONNECTION_RESET");
        ambiguousFault.getSpec().setResponseBody(null);
        ambiguousFault.getSpec().setResponseHeaders(null);
        ambiguousFault.getSpec().setAction("FORWARD");
        assertValidation(ambiguousFault, "FAULT_ACTION_CONFLICT", "spec.action");
    }

    @Test
    void rejectsMalformedConditionRegexAndHeader() {
        RuleApplyDocument condition = httpMock();
        condition.getSpec().setQueryCondition("missing-operator");
        assertValidation(condition, "CONDITION_FORMAT", "spec.queryCondition");

        RuleApplyDocument regex = httpMock();
        regex.getSpec().setHeaderCondition("X-Request~=[");
        assertValidation(regex, "INVALID_REGEX", "spec.headerCondition");

        RuleApplyDocument header = httpMock();
        header.getSpec().setResponseHeaders(Map.of("Invalid Header", "value"));
        assertValidation(header, "HEADER_NAME", "spec.responseHeaders.Invalid Header");
    }

    private RuleApplyDocument httpMock() {
        return base(Protocol.HTTP, "/orders")
                .spec(RuleApplyDocument.Spec.builder()
                        .protocol(Protocol.HTTP)
                        .matchKey("/orders")
                        .method("POST")
                        .action("MOCK")
                        .status(200)
                        .responseHeaders(Map.of("Content-Type", "application/json"))
                        .responseBody(objectMapper.createObjectNode().put("ok", true))
                        .delayMs(0L)
                        .priority(0)
                        .enabled(true)
                        .protectedRule(false)
                        .build())
                .build();
    }

    private RuleApplyDocument.RuleApplyDocumentBuilder base(Protocol protocol, String matchKey) {
        return RuleApplyDocument.builder()
                .apiVersion(RuleApplyDocument.API_VERSION)
                .kind(RuleApplyDocument.KIND)
                .metadata(RuleApplyDocument.Metadata.builder().build())
                .spec(RuleApplyDocument.Spec.builder()
                        .protocol(protocol)
                        .matchKey(matchKey)
                        .responseBody(objectMapper.getNodeFactory().textNode("ok"))
                        .build());
    }

    private void assertValidation(RuleApplyDocument document, String code, String path) {
        assertValidation(() -> contract.validate(document), code, path);
    }

    private void assertValidation(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                                  String code, String path) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(RuleApplyValidationException.class, error -> {
                    assertThat(error.getValidationCode()).isEqualTo(code);
                    assertThat(error.getPath()).isEqualTo(path);
                });
    }
}
