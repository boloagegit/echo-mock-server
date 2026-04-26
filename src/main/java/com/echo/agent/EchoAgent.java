package com.echo.agent;

/**
 * 統一的背景任務代理介面，定義 Agent 生命週期與監控契約。
 */
public interface EchoAgent {

    /**
     * 取得 Agent 名稱。
     */
    String getName();

    /**
     * 取得 Agent 描述（說明用途）。
     */
    String getDescription();

    /**
     * 取得 Agent 目前狀態。
     */
    AgentStatus getStatus();

    /**
     * 取得 Agent 統計資訊（佇列大小、已處理數、已丟棄數）。
     */
    AgentStats getStats();

    /**
     * 提交任務給 Agent。
     * 當 Agent 非 RUNNING 狀態時應拒絕任務。
     *
     * @param task 要提交的任務
     */
    void submit(Object task);

    /**
     * 啟動 Agent。
     */
    void start();

    /**
     * 關閉 Agent，執行最終 flush 後轉為 STOPPED。
     */
    void shutdown();
}
