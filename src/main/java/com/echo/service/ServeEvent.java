package com.echo.service;

import com.echo.entity.BaseRule;
import com.echo.entity.Protocol;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * 一筆請求處理完成（已決定回應）時發出的事件，交給 {@link ServeEventListener}（請求紀錄、未來的指標與 Webhook）。
 * <p>
 * 只在處理請求的執行緒上同步傳遞，listener 不可保留可變欄位（headers、candidates、preparedBody）的參照；
 * 需要延後處理的 listener 應自行複製所需資料。
 */
@Value
@Builder
public class ServeEvent {
    String ruleId;
    Protocol protocol;
    String method;
    /** HTTP path 或 JMS endpoint 欄位值。 */
    String endpoint;
    boolean matched;
    int responseTimeMs;
    Integer matchTimeMs;
    String clientIp;
    /** 匹配鏈 JSON。 */
    String matchChain;
    String targetHost;
    boolean forwarded;
    String forwardTarget;
    Integer proxyStatus;
    String proxyError;
    /** 實際回應狀態；連線重置等沒有回應時為 null。 */
    Integer responseStatus;
    String requestBody;
    String responseBody;
    /** 參與比對的候選規則（供匹配鏈分析）；沒有時為 null 或空。 */
    List<? extends BaseRule> candidates;
    ConditionMatcher.PreparedBody preparedBody;
    String queryString;
    Map<String, String> headers;
    String faultType;
    String scenarioName;
    String scenarioFromState;
    String scenarioToState;
    /** 參與比對的候選規則數；沒有比對時為 null。 */
    Integer candidateCount;
    /** 命中的是沒有條件的預設規則（條件都不成立時才選它）；沒有命中時為 null。 */
    Boolean matchedByFallback;
    /** 規則設定的模擬延遲（毫秒），在記錄的處理時間之外；SSE 為第一個事件前的延遲。 */
    Integer delayMs;
    /** SSE 串流的事件數；不是 SSE 時為 null。 */
    Integer sseEventCount;
    /** 因情境狀態不符而略過的規則（匹配鏈格式的 JSON）；沒有時為 null。 */
    String scenarioSkipped;
    /** 交易診斷 ID；未啟用診斷時為 null。 */
    String diagnosticId;
}
