package com.echo.protocol.http.sse;

import com.echo.diagnostics.TransactionDiagnostics.Trace;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** 測試用：以原控制器測試的參數順序啟動播放（沒有初始延遲、不記錄診斷）。 */
final class SsePlayerTestSupport {

    private SsePlayerTestSupport() {
    }

    static CompletableFuture<Void> start(SsePlayer player, SseEmitter emitter, List<SsePlayer.SseEvent> events,
                                         boolean loopEnabled, String queryString, Map<String, String> headers,
                                         String path, String method) {
        return player.play(emitter, events, loopEnabled,
                new SsePlayer.RequestContext(queryString, headers, path, method, null), 0, Trace.NONE);
    }

    /** 播放並等待完成。 */
    static void send(SsePlayer player, SseEmitter emitter, List<SsePlayer.SseEvent> events,
                     boolean loopEnabled, String queryString, Map<String, String> headers,
                     String path, String method) {
        start(player, emitter, events, loopEnabled, queryString, headers, path, method).join();
    }
}
