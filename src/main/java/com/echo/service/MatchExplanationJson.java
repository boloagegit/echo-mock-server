package com.echo.service;

import com.echo.core.match.LegacyConditions;
import com.echo.core.match.MatchExplanation;
import com.echo.core.match.MatchNode;
import com.echo.core.match.MatchSpecCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * 把 {@link MatchExplanation} 轉成匹配鏈的 {@code detailTree} JSON，供請求紀錄的「比對過程」以樹狀顯示。
 * <ul>
 *   <li>群組：{@code {"node":"all|any|not","passed":true,"children":[...]}}</li>
 *   <li>結構化條件：{@code {"node":"leaf","passed":false,"spec":{...},"actual":"\"JP\""}}</li>
 *   <li>舊格式條件：{@code {"node":"legacy","source":"body","passed":true,"condition":"a=b","actual":"b"}}</li>
 * </ul>
 * 取不到值時以 {@code "missing":true} 表示（結構化條件無法以該方式取值時為 {@code "unavailable":true}），不帶 {@code actual}。
 * 紀錄會存進資料庫或記憶體（記憶體模式預設保留 10,000 筆），因此限制大小：節點數、整棵樹的大約字數、
 * 文字長度與期望值長度都有上限，超過時以 {@code truncated}、{@code valueOmitted} 標示。
 */
public final class MatchExplanationJson {

    static final int MAX_NODES = 200;
    /** 整棵樹的大約字數上限（一般規則的樹不到 1,000 字）。 */
    static final int MAX_TREE_CHARS = 8_000;
    static final int MAX_TEXT_LENGTH = 300;
    static final int MAX_SPEC_LENGTH = 1_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LEAF_MISSING = "missing";
    private static final String LEAF_UNAVAILABLE = "unavailable";

    private MatchExplanationJson() {
    }

    public static String toJson(MatchExplanation explanation) {
        return write(explanation, new Budget()).toString();
    }

    /** 已寫入的節點數與大約字數。 */
    private static final class Budget {
        private int nodes;
        private int chars;

        private boolean exhausted() {
            return nodes >= MAX_NODES || chars >= MAX_TREE_CHARS;
        }
    }

    private static ObjectNode write(MatchExplanation explanation, Budget budget) {
        budget.nodes++;
        ObjectNode json = MAPPER.createObjectNode();
        Optional<MatchNode.LegacySource> legacy = LegacyConditions.sourceOf(explanation);
        if (legacy.isPresent()) {
            json.put("node", "legacy");
            json.put("source", legacy.get().jsonName());
            json.put("passed", explanation.passed());
            json.put("condition", truncate(explanation.expected()));
            writeLegacyActual(explanation.actual(), json);
            budget.chars += json.toString().length();
            return json;
        }
        if (explanation.isCondition()) {
            json.put("node", "leaf");
            json.put("passed", explanation.passed());
            writeSpec(explanation, json);
            writeLeafActual(explanation.actual(), json);
            budget.chars += json.toString().length();
            return json;
        }
        json.put("node", explanation.node());
        json.put("passed", explanation.passed());
        budget.chars += 40;
        ArrayNode children = json.putArray("children");
        for (MatchExplanation child : explanation.children()) {
            if (budget.exhausted()) {
                json.put("truncated", true);
                break;
            }
            children.add(write(child, budget));
        }
        return json;
    }

    private static void writeSpec(MatchExplanation explanation, ObjectNode json) {
        MatchNode.Leaf leaf = explanation.leaf();
        if (leaf == null) {
            // 外部註冊的節點沒有結構化條件，只能顯示說明文字
            json.put("description", truncate(explanation.expected()));
            return;
        }
        ObjectNode spec = MatchSpecCodec.toJson(leaf);
        if (spec.has("value") && spec.toString().length() > MAX_SPEC_LENGTH) {
            spec.remove("value");
            json.put("valueOmitted", true);
        }
        json.set("spec", spec);
    }

    /** 結構化條件的實際值已是顯示用的摘要（字串加引號、多個值為清單），取不到值時為 missing／unavailable。 */
    private static void writeLeafActual(String actual, ObjectNode json) {
        if (actual == null || LEAF_MISSING.equals(actual)) {
            json.put("missing", true);
        } else if (LEAF_UNAVAILABLE.equals(actual)) {
            json.put("unavailable", true);
        } else {
            json.put("actual", truncate(actual));
        }
    }

    /** 舊格式條件的實際值為 {@code 欄位=值}；欄位不含 =，取不到值時舊版顯示 null 或 not found。 */
    private static void writeLegacyActual(String actual, ObjectNode json) {
        String value = actual == null ? null : actual.substring(actual.indexOf('=') + 1);
        if (value == null || "null".equals(value) || "not found".equals(value)) {
            json.put("missing", true);
        } else {
            json.put("actual", truncate(value));
        }
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH) + "…";
    }
}
