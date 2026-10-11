package com.echo.service;

/**
 * 接收 {@link ServeEvent}。在處理請求的執行緒上同步呼叫，實作不可阻塞；需要 I/O 的實作自行排入佇列。
 * <p>
 * {@link #critical()} 為 true 的 listener（請求紀錄）丟出的例外會往上傳：HTTP 回 503，JMS 讓遞送失敗以便重送；
 * 其他 listener 的例外只會記錄，不影響回應。
 */
public interface ServeEventListener {

    void onServed(ServeEvent event);

    default boolean critical() {
        return false;
    }
}
