package com.echo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JMS 配置屬性
 */
@Component
@ConfigurationProperties(prefix = "echo.jms")
@Getter
@Setter
public class JmsProperties {

    private boolean enabled = false;
    private String alias = "JMS";
    private int port = 61616;
    private String username = "admin";
    private String password = "admin";

    /** Echo Embedded Artemis 上監聽的 Queue（外部服務連入的入口） */
    private String queue = "ECHO.REQUEST";

    /** 從 JMS 訊息 body 提取端點識別欄位（XML: element name, JSON: key name） */
    private String endpointField = "ServiceName";

    /** JMS 訊息解析可同時使用的 heap 比例；額度不足時 listener 會等待，讓 broker 形成背壓。 */
    private int processingMemoryPercent = 25;

    /** XML DOM 相對於編碼後訊息大小的保守記憶體估算倍率。 */
    private int xmlMemoryExpansionFactor = 8;

    /** Embedded Artemis 可使用的 heap 比例；超過後依 PAGE 策略寫入磁碟。 */
    private int brokerMemoryPercent = 15;

    /** 是否將 Artemis journal、paging 與 large message 寫入磁碟；正式環境必須保持啟用。 */
    private boolean persistent = true;

    /** Artemis journal、paging 與 large-message 檔案目錄。 */
    private String dataDirectory = "./data/artemis";

    /** 每個 listener consumer 最多預取的 encoded bytes；避免 30 個 consumer 各囤 1 MiB。 */
    private int consumerWindowSize = 64 * 1024;

    /** Broker 開始阻擋 producer 前必須保留的磁碟空間，避免真的寫到 ENOSPC。 */
    private long minDiskFreeBytes = 512L * 1024 * 1024;

    /** Artemis 重新檢查可用磁碟空間的間隔。 */
    private int diskScanPeriodMs = 1_000;

    /** 暫時性 listener 失敗的第一次重送等待時間。 */
    private long redeliveryDelayMs = 250;

    /** 重送等待時間的倍增倍率，避免儲存或記憶體故障時 busy-loop。 */
    private double redeliveryMultiplier = 2.0;

    /** 重送等待時間上限。負值不接受。 */
    private long maxRedeliveryDelayMs = 5_000;

    /** -1 表示不因重送次數自動丟棄訊息。 */
    private int maxDeliveryAttempts = -1;

    private Target target = new Target();

    @Getter
    @Setter
    public static class Target {
        private boolean enabled = false;
        private String type = "tibco";  // artemis | tibco
        private String serverUrl;
        private String username;
        private String password;
        private int timeoutSeconds = 30;

        /** 目標 JMS Server 上要轉發到的 Queue */
        private String queue = "TARGET.REQUEST";
    }
}
