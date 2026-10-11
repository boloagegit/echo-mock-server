package com.echo.pipeline;

import com.echo.entity.FaultType;

import java.util.concurrent.CompletionStage;

/**
 * {@link Responder} 決定的回應：由 pipeline 統一套用延遲、寫入請求紀錄，再交給協定寫回。
 */
public sealed interface ResponsePlan {

    /** 立即可用的回應內容（mock）。 */
    record Content(MockResponse response) implements ResponsePlan {
    }

    /** 故障注入；{@code response} 是故障發生前要寫回的內容（連線重置時協定會忽略它）。 */
    record Fault(FaultType type, MockResponse response) implements ResponsePlan {
    }

    /** 轉發到下游，回應在 I/O 完成後才可用。 */
    record Forwarded(CompletionStage<MockResponse> response) implements ResponsePlan {
    }
}
