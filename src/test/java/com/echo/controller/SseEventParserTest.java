package com.echo.controller;

import com.echo.controller.UniversalMockController.SseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.echo.service.HttpRuleService;
import com.echo.service.RequestLogService;
import com.echo.service.ResponseTemplateService;
import com.echo.service.RuleService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class SseEventParserTest {

    @Mock private RuleService ruleService;
    @Mock private HttpRuleService httpRuleService;
    @Mock private RequestLogService requestLogService;
    @Mock private ResponseTemplateService templateService;

    private UniversalMockController controller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        controller = new UniversalMockController(
                ruleService, httpRuleService, requestLogService, templateService, null);
    }

    // --- Valid JSON array parsing ---

    @Test
    void parseSingleEvent() {
        String json = """
                [{"event":"msg","data":"hello","id":"1","delayMs":100}]""";
        List<SseEvent> events = controller.parseSseEvents(json);
        assertThat(events).hasSize(1);
        SseEvent e = events.get(0);
        assertThat(e.event()).isEqualTo("msg");
        assertThat(e.data()).isEqualTo("hello");
        assertThat(e.id()).isEqualTo("1");
        assertThat(e.delayMs()).isEqualTo(100L);
    }

    @Test
    void parseMultipleEvents() {
        String json = """
                [
                  {"event":"a","data":"d1","id":"1","delayMs":0},
                  {"event":"b","data":"d2","id":"2","delayMs":500},
                  {"event":"c","data":"d3","id":"3","delayMs":1000}
                ]""";
        List<SseEvent> events = controller.parseSseEvents(json);
        assertThat(events).hasSize(3);
        assertThat(events.get(0).event()).isEqualTo("a");
        assertThat(events.get(1).event()).isEqualTo("b");
        assertThat(events.get(2).event()).isEqualTo("c");
    }

    @Test
    void parseDataOnlyEvent() {
        String json = """
                [{"data":"hello"}]""";
        List<SseEvent> events = controller.parseSseEvents(json);
        assertThat(events).hasSize(1);
        SseEvent e = events.get(0);
        assertThat(e.data()).isEqualTo("hello");
        assertThat(e.event()).isNull();
        assertThat(e.id()).isNull();
        assertThat(e.delayMs()).isNull();
    }

    // --- Null/empty/blank/empty-array input ---

    @Test
    void nullInput_returnsEmptyList() {
        assertThat(controller.parseSseEvents(null)).isEmpty();
    }

    @Test
    void emptyString_returnsEmptyList() {
        assertThat(controller.parseSseEvents("")).isEmpty();
    }

    @Test
    void blankString_returnsEmptyList() {
        assertThat(controller.parseSseEvents("   ")).isEmpty();
    }

    @Test
    void emptyArray_returnsEmptyList() {
        assertThat(controller.parseSseEvents("[]")).isEmpty();
    }

    // --- Data null or empty string skips event ---

    @Test
    void dataNullEvent_isSkipped() {
        String json = """
                [{"event":"msg","data":null,"id":"1"}]""";
        assertThat(controller.parseSseEvents(json)).isEmpty();
    }

    @Test
    void dataEmptyString_isSkipped() {
        String json = """
                [{"data":""}]""";
        assertThat(controller.parseSseEvents(json)).isEmpty();
    }

    // --- Mixed valid/invalid events ---

    @Test
    void mixedValidAndInvalidEvents() {
        String json = """
                [
                  {"data":"ok"},
                  {"data":null},
                  {"data":"good"},
                  {"data":""}
                ]""";
        List<SseEvent> events = controller.parseSseEvents(json);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).data()).isEqualTo("ok");
        assertThat(events.get(1).data()).isEqualTo("good");
    }

    // --- Non-JSON format and JSON object (not array) ---

    @Test
    void nonJsonFormat_returnsEmptyList() {
        assertThat(controller.parseSseEvents("not json")).isEmpty();
    }

    @Test
    void jsonObject_notArray_returnsEmptyList() {
        assertThat(controller.parseSseEvents("{\"data\":\"hello\"}")).isEmpty();
    }

    // --- delayMs negative and oversized ---

    @Test
    void negativeDelayMs_treatedAsZero() {
        String json = """
                [{"data":"x","delayMs":-100}]""";
        List<SseEvent> events = controller.parseSseEvents(json);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).delayMs()).isEqualTo(0L);
    }

    @Test
    void oversizedDelayMs_cappedAt30000() {
        String json = """
                [{"data":"x","delayMs":999999}]""";
        List<SseEvent> events = controller.parseSseEvents(json);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).delayMs()).isEqualTo(30000L);
    }

    // --- type field parsing ---

    @Test
    void parseEventWithTypeNormal() {
        String json = """
                [{"data":"hello","type":"normal"}]""";
        List<SseEvent> events = controller.parseSseEvents(json);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("normal");
    }

    @Test
    void parseEventWithTypeError() {
        String json = """
                [{"data":"fail","type":"error"}]""";
        List<SseEvent> events = controller.parseSseEvents(json);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("error");
    }

    @Test
    void parseEventWithTypeAbort() {
        String json = """
                [{"data":"bye","type":"abort"}]""";
        List<SseEvent> events = controller.parseSseEvents(json);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("abort");
    }

    @Test
    void parseEventWithoutType_backwardCompatible() {
        String json = """
                [{"event":"msg","data":"hello","id":"1","delayMs":100}]""";
        List<SseEvent> events = controller.parseSseEvents(json);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isNull();
    }

    @Test
    void parseEventWithInvalidType_stillParses() {
        String json = """
                [{"data":"x","type":"unknown"}]""";
        List<SseEvent> events = controller.parseSseEvents(json);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("unknown");
    }

    // --- Template syntax preserved as-is ---

    @Test
    void dataWithTemplateSyntax_preservedAsIs() {
        String json = """
                [{"data":"{{request.path}}"}]""";
        List<SseEvent> events = controller.parseSseEvents(json);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).data()).isEqualTo("{{request.path}}");
    }

    // --- Property 2: SSE event data field invariant ---

    /**
     * Validates: Requirements 3.2, 3.3
     *
     * For any list of SseEvents returned by parseSseEvents(),
     * every event's data field is non-null and non-empty.
     */
    @Property
    void allParsedEventsHaveNonNullNonEmptyData(
            @ForAll("sseJsonArrays") String json) {
        UniversalMockController ctrl = new UniversalMockController(
                null, null, null, null, null);
        List<SseEvent> events = ctrl.parseSseEvents(json);
        for (SseEvent e : events) {
            assertThat(e.data()).isNotNull();
            assertThat(e.data()).isNotEmpty();
        }
    }

    @Provide
    Arbitrary<String> sseJsonArrays() {
        Arbitrary<String> validData = Arbitraries.strings()
                .ofMinLength(1).ofMaxLength(50)
                .filter(s -> !s.contains("\"") && !s.contains("\\"));
        Arbitrary<String> nullableData = Arbitraries.oneOf(
                validData,
                Arbitraries.just(""),
                Arbitraries.just(null));

        Arbitrary<String> eventJson = Combinators.combine(
                nullableData,
                Arbitraries.strings().ofMinLength(0).ofMaxLength(10)
                        .filter(s -> !s.contains("\"") && !s.contains("\\")),
                Arbitraries.longs().between(-1000, 100000)
        ).as((data, event, delay) -> {
            StringBuilder sb = new StringBuilder("{");
            if (data != null) {
                sb.append("\"data\":\"").append(data).append("\"");
            } else {
                sb.append("\"data\":null");
            }
            if (!event.isEmpty()) {
                sb.append(",\"event\":\"").append(event).append("\"");
            }
            sb.append(",\"delayMs\":").append(delay);
            sb.append("}");
            return sb.toString();
        });

        return eventJson.list().ofMinSize(0).ofMaxSize(5)
                .map(items -> "[" + String.join(",", items) + "]");
    }

    // --- Property 3: SSE event serialization round-trip consistency ---

    /**
     * Validates: Requirements 3.1
     *
     * For any valid SseEvent list (each event has non-null non-empty data),
     * serializing to JSON then parsing back produces an equivalent list.
     */
    @Property
    void serializationRoundTripConsistency(
            @ForAll("validSseEventLists") List<SseEvent> original) throws JsonProcessingException {
        UniversalMockController ctrl = new UniversalMockController(
                null, null, null, null, null);
        String json = objectMapper.writeValueAsString(original);
        List<SseEvent> parsed = ctrl.parseSseEvents(json);

        assertThat(parsed).hasSameSizeAs(original);
        for (int i = 0; i < original.size(); i++) {
            SseEvent orig = original.get(i);
            SseEvent result = parsed.get(i);
            assertThat(result.event()).isEqualTo(orig.event());
            assertThat(result.data()).isEqualTo(orig.data());
            assertThat(result.id()).isEqualTo(orig.id());
            // delayMs may be clamped
            Long expectedDelay = orig.delayMs();
            if (expectedDelay != null) {
                if (expectedDelay < 0) {
                    expectedDelay = 0L;
                } else if (expectedDelay > 30000) {
                    expectedDelay = 30000L;
                }
            }
            assertThat(result.delayMs()).isEqualTo(expectedDelay);
            assertThat(result.type()).isEqualTo(orig.type());
        }
    }

    @Provide
    Arbitrary<List<SseEvent>> validSseEventLists() {
        Arbitrary<SseEvent> validEvent = Combinators.combine(
                Arbitraries.strings().ofMinLength(0).ofMaxLength(20).injectNull(0.3),
                Arbitraries.strings().ofMinLength(1).ofMaxLength(50),
                Arbitraries.strings().ofMinLength(0).ofMaxLength(10).injectNull(0.3),
                Arbitraries.longs().between(-500, 60000).injectNull(0.2),
                Arbitraries.of("normal", "error", "abort").injectNull(0.3)
        ).as(SseEvent::new);

        return validEvent.list().ofMinSize(1).ofMaxSize(5);
    }
}
