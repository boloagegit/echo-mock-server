package com.echo.service;

import com.echo.entity.RuleAuditLog.Action;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSummaryCalculatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode summaryOf(AuditSummaryCalculator.Result result) throws Exception {
        return mapper.readTree(result.summary());
    }

    @Test
    void httpUpdate_listsChangedFieldsAndSkipsBookkeeping() throws Exception {
        String before = "{\"id\":\"r1\",\"version\":0,\"protocol\":\"HTTP\",\"matchKey\":\"/users/1\",\"method\":\"GET\","
                + "\"targetHost\":\"api.example.com\",\"description\":\"取得使用者\",\"priority\":0,\"delayMs\":0,"
                + "\"tags\":null,\"httpStatus\":200,\"enabled\":true,\"updatedAt\":\"2026-10-16T10:00:00\",\"condition\":\"x\"}";
        String after = "{\"id\":\"r1\",\"version\":1,\"protocol\":\"HTTP\",\"matchKey\":\"/users/1\",\"method\":\"GET\","
                + "\"targetHost\":\"api.example.com\",\"description\":\"取得使用者\",\"priority\":5,\"delayMs\":300,"
                + "\"tags\":\"{\\\"env\\\":\\\"SIT\\\"}\",\"httpStatus\":200,\"enabled\":true,\"updatedAt\":\"2026-10-16T11:00:00\",\"condition\":\"y\"}";

        AuditSummaryCalculator.Result result = AuditSummaryCalculator.compute(mapper, "r1", Action.UPDATE, before, after);

        assertThat(result.protocol()).isEqualTo("HTTP");
        assertThat(result.name()).isEqualTo("/users/1");
        assertThat(result.description()).isEqualTo("取得使用者");
        JsonNode summary = summaryOf(result);
        assertThat(summary.get("method").asText()).isEqualTo("GET");
        assertThat(summary.get("host").asText()).isEqualTo("api.example.com");
        assertThat(summary.get("status").asInt()).isEqualTo(200);
        assertThat(summary.has("enabled")).isFalse();
        assertThat(summary.get("changeCount").asInt()).isEqualTo(3);
        assertThat(summary.get("changes")).extracting(c -> c.get("f").asText())
                .containsExactly("priority", "delayMs", "tags");
        assertThat(summary.get("changes").get(0).get("b").isNumber()).isTrue();
        assertThat(summary.get("changes").get(0).get("b").asText()).isEqualTo("0");
        assertThat(summary.get("changes").get(0).get("a").asText()).isEqualTo("5");
        assertThat(summary.get("changes").get(2).has("b")).isFalse();
        assertThat(summary.get("changes").get(2).get("a").asText()).isEqualTo("{\"env\":\"SIT\"}");
    }

    @Test
    void internalIdsAreListedAfterReadableChanges() throws Exception {
        AuditSummaryCalculator.Result result = AuditSummaryCalculator.compute(mapper, "r1", Action.UPDATE,
                "{\"protocol\":\"HTTP\",\"matchKey\":\"/a\",\"responseId\":5,\"delayMs\":0}",
                "{\"protocol\":\"HTTP\",\"matchKey\":\"/a\",\"responseId\":6,\"delayMs\":300}");

        assertThat(summaryOf(result).get("changes")).extracting(c -> c.get("f").asText())
                .containsExactly("delayMs", "responseId");
    }

    @Test
    void emptyStringAndNullAreTheSameValue() throws Exception {
        AuditSummaryCalculator.Result result = AuditSummaryCalculator.compute(mapper, "r1", Action.UPDATE,
                "{\"protocol\":\"HTTP\",\"matchKey\":\"/a\",\"description\":\"\"}",
                "{\"protocol\":\"HTTP\",\"matchKey\":\"/a\",\"description\":null}");

        assertThat(summaryOf(result).get("changeCount").asInt()).isZero();
        assertThat(result.description()).isNull();
    }

    @Test
    void jmsRule_usesQueueNameAndMarksDisabledOnDelete() throws Exception {
        AuditSummaryCalculator.Result result = AuditSummaryCalculator.compute(mapper, "j1", Action.DELETE,
                "{\"protocol\":\"JMS\",\"queueName\":\"ECHO.REQUEST\",\"description\":\"報價\",\"enabled\":false}", null);

        assertThat(result.protocol()).isEqualTo("JMS");
        assertThat(result.name()).isEqualTo("ECHO.REQUEST");
        JsonNode summary = summaryOf(result);
        assertThat(summary.get("enabled").asBoolean()).isFalse();
        assertThat(summary.has("changes")).isFalse();
    }

    @Test
    void responseBody_countsChangedLines() throws Exception {
        String before = mapper.writeValueAsString(java.util.Map.of("id", 2, "description", "使用者回應",
                "contentType", "text/plain", "body", "{\n  \"id\": 1\n}"));
        String after = mapper.writeValueAsString(java.util.Map.of("id", 2, "description", "使用者回應",
                "contentType", "application/json", "body", "{\n  \"id\": 1,\n  \"name\": \"Alice\"\n}"));

        AuditSummaryCalculator.Result result = AuditSummaryCalculator.compute(mapper, "response-2", Action.UPDATE, before, after);

        assertThat(result.protocol()).isEqualTo("RESPONSE");
        assertThat(result.name()).isEqualTo("使用者回應");
        JsonNode summary = summaryOf(result);
        assertThat(summary.get("contentType").asText()).isEqualTo("application/json");
        JsonNode body = null;
        for (JsonNode change : summary.get("changes")) {
            if ("body".equals(change.get("f").asText())) {
                body = change;
            }
        }
        assertThat(body).isNotNull();
        assertThat(body.get("long").asBoolean()).isTrue();
        assertThat(body.get("add").asInt()).isEqualTo(2);
        assertThat(body.get("del").asInt()).isEqualTo(1);
        assertThat(body.has("b")).isFalse();
    }

    @Test
    void hugeBody_isMarkedChangedWithoutCountingLines() throws Exception {
        String big = "x".repeat(300_000);
        AuditSummaryCalculator.Result result = AuditSummaryCalculator.compute(mapper, "response-1", Action.UPDATE,
                "{\"body\":\"" + big + "\"}", "{\"body\":\"" + big + "y\"}");

        JsonNode change = summaryOf(result).get("changes").get(0);
        assertThat(change.get("long").asBoolean()).isTrue();
        assertThat(change.has("add")).isFalse();
    }

    @Test
    void summaryAlwaysFitsItsColumn() {
        StringBuilder before = new StringBuilder("{\"protocol\":\"HTTP\",\"matchKey\":\"" + "路".repeat(400) + "\"");
        StringBuilder after = new StringBuilder("{\"protocol\":\"HTTP\",\"matchKey\":\"" + "路".repeat(400) + "\"");
        for (int i = 0; i < 20; i++) {
            before.append(",\"field").append(i).append("\":\"").append("舊".repeat(70)).append('"');
            after.append(",\"field").append(i).append("\":\"").append("新".repeat(70)).append('"');
        }
        AuditSummaryCalculator.Result result = AuditSummaryCalculator.compute(mapper, "r1", Action.UPDATE,
                before.append('}').toString(), after.append('}').toString());

        assertThat(result.summary().length()).isLessThanOrEqualTo(AuditSummaryCalculator.SUMMARY_LENGTH);
        assertThat(result.name()).hasSize(AuditSummaryCalculator.NAME_LENGTH).endsWith("…");
        assertThat(result.summary()).contains("\"changeCount\":20");
    }

    @Test
    void invalidJson_returnsEmptyMarker() {
        AuditSummaryCalculator.Result result = AuditSummaryCalculator.compute(mapper, "r1", Action.UPDATE, "not json", "{}");

        assertThat(result.summary()).isEqualTo(AuditSummaryCalculator.EMPTY_SUMMARY);
        assertThat(result.name()).isNull();
    }

    /** 與前端 audit-diff.js 的逐行比對相同（數值由前端算出對照），列表與明細的行數才一致。 */
    @Test
    void lineDelta_matchesTheDetailLineDiff() {
        assertThat(AuditSummaryCalculator.lineDelta("a\nb\nc", "c\na\nb")).containsExactly(1, 1);
        assertThat(AuditSummaryCalculator.lineDelta("a\nb", "a\nB\nc")).containsExactly(2, 1);
        assertThat(AuditSummaryCalculator.lineDelta(null, "a")).containsExactly(1, 0);
        assertThat(AuditSummaryCalculator.lineDelta("x\ny\nz\nw", "w\nz\ny\nx")).containsExactly(3, 3);
        assertThat(AuditSummaryCalculator.lineDelta("1\n2\n3\n4\n5", "1\n3\n2\n4\n6\n5")).containsExactly(2, 1);
        assertThat(AuditSummaryCalculator.lineDelta("same\nlines", "same\nlines")).containsExactly(0, 0);
    }

    @Test
    void lineDelta_tooManyEditsFallsBackToAnEstimate() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            before.append("old").append(i).append('\n');
            after.append("new").append(i).append('\n');
        }
        assertThat(AuditSummaryCalculator.lineDelta(before.toString(), after.toString())).containsExactly(3000, 3000);
    }
}
