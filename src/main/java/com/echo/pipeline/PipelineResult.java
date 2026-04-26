package com.echo.pipeline;

import lombok.Builder;
import lombok.Getter;

/**
 * Pipeline 執行結果 Value Object
 * <p>
 * 封裝 pipeline 執行的完整結果，供 Controller / Listener 使用。
 * 包含回應內容、匹配資訊、計時資料與延遲設定。
 */
@Getter
@Builder
public class PipelineResult {

    /** 回應內容 */
    private final MockResponse response;

    /** 匹配到的規則 ID */
    private final String ruleId;

    /** 是否匹配成功 */
    private final boolean matched;

    /** 匹配耗時（毫秒） */
    private final int matchTimeMs;

    /** 回應耗時（毫秒） */
    private final int responseTimeMs;

    /** 匹配鏈 JSON */
    private final String matchChainJson;

    /** 延遲時間（毫秒） */
    private final long delayMs;

    /** 故障注入類型（null 表示 NONE） */
    private final String faultType;

    /** Scenario 名稱（狀態轉移時記錄，可選） */
    private final String scenarioName;

    /** Scenario 轉移後的新狀態（可選） */
    private final String scenarioNewState;
}
