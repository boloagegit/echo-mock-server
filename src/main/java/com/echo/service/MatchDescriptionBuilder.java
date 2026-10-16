package com.echo.service;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 匹配鏈工具類別
 * <p>
 * 提供匹配鏈 JSON 序列化。
 */
@Slf4j
public class MatchDescriptionBuilder {

    private MatchDescriptionBuilder() {
    }

    /**
     * 將匹配鏈轉換為 JSON 字串（僅保留匹配成功的條目）
     */
    public static String toMatchChainJson(List<? extends MatchChainInfo> chain, boolean matched) {
        if (chain == null || chain.isEmpty() || !matched) {
            return null;
        }
        List<? extends MatchChainInfo> filtered = chain.stream()
                .filter(e -> "match".equals(e.getReason()) || "fallback".equals(e.getReason()))
                .toList();
        if (filtered.isEmpty()) {
            return null;
        }
        return toChainJsonString(filtered);
    }

    /** 情境狀態不符而略過的規則最多記錄幾筆。 */
    static final int MAX_SCENARIO_SKIPPED = 20;

    /**
     * 因情境狀態不符而略過的規則（{@code scenario_state_mismatch}），供處理流程圖顯示；沒有時回傳 null。
     */
    public static String toScenarioSkippedJson(List<? extends MatchChainInfo> chain) {
        if (chain == null || chain.isEmpty()) {
            return null;
        }
        List<? extends MatchChainInfo> skipped = chain.stream()
                .filter(e -> SCENARIO_STATE_MISMATCH.equals(e.getReason()))
                .limit(MAX_SCENARIO_SKIPPED)
                .toList();
        return skipped.isEmpty() ? null : toChainJsonString(skipped);
    }

    /** 匹配鏈中「情境狀態不符」的原因代碼。 */
    public static final String SCENARIO_STATE_MISMATCH = "scenario_state_mismatch";

    /**
     * 將匹配鏈轉換為 JSON 字串（保留全部條目）
     */
    public static String toMatchChainJson(List<? extends MatchChainInfo> chain) {
        if (chain == null || chain.isEmpty()) {
            return null;
        }
        return toChainJsonString(chain);
    }

    private static String toChainJsonString(List<? extends MatchChainInfo> chain) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            MatchChainInfo e = chain.get(i);
            sb.append("{\"ruleId\":\"").append(e.getRuleId()).append("\"");
            sb.append(",\"reason\":\"").append(e.getReason()).append("\"");
            if (e.getEndpoint() != null) {
                sb.append(",\"endpoint\":\"").append(escapeJson(e.getEndpoint())).append("\"");
            }
            if (e.getDescription() != null) {
                sb.append(",\"description\":\"").append(escapeJson(e.getDescription())).append("\"");
            }
            if (e.getCondition() != null) {
                sb.append(",\"condition\":\"").append(escapeJson(e.getCondition())).append("\"");
            }
            if (e.getScore() != null) {
                sb.append(",\"score\":\"").append(escapeJson(e.getScore())).append("\"");
            }
            if (e.getDetail() != null) {
                sb.append(",\"detail\":\"").append(escapeJson(e.getDetail())).append("\"");
            }
            if (e.isNearMiss()) {
                sb.append(",\"nearMiss\":true");
            }
            if (e.getDetailTree() != null) {
                // 已是 JSON（由 MatchExplanationJson 產生），直接放入
                sb.append(",\"detailTree\":").append(e.getDetailTree());
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
