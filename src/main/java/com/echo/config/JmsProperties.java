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
    /** Startup policy description only; does not suppress Artemis warnings or change routing. */
    private boolean policySummaryEnabled = true;
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

    /** 從哪裡接收 JMS 請求：內建 Artemis（預設）或外部共用 broker（例如 ESB）。 */
    private Source source = new Source();

    private Target target = new Target();

    /**
     * JMS 請求來源。{@code embedded} 使用 Echo 內建的 Artemis；
     * {@code artemis} 或 {@code tibco} 連到外部 broker 的 {@link #queue}，
     * 多台 Echo 監聽同一個 queue 時由 broker 把每則訊息只交給其中一台。
     */
    @Getter
    @Setter
    public static class Source {
        public static final String EMBEDDED = "embedded";

        private String type = EMBEDDED;  // embedded | artemis | tibco
        private String serverUrl;
        private String username;
        private String password;
        /** 連不上外部 broker 時，多久重試一次。 */
        private long reconnectIntervalMs = 5_000;

        public boolean isExternal() {
            return isExternalType(type);
        }

        /** 供設定類別的條件判斷使用：空白或 {@code embedded} 以外的值都是外部 broker。 */
        public static boolean isExternalType(String type) {
            return type != null && !type.isBlank() && !EMBEDDED.equalsIgnoreCase(type.trim());
        }

        /** 轉成轉送目標的設定格式，沿用各 broker 的 ConnectionFactory 建立方式。 */
        public Target asConnectionTarget() {
            Target connection = new Target();
            connection.setType(type == null ? null : type.trim());
            connection.setServerUrl(serverUrl);
            connection.setUsername(username);
            connection.setPassword(password);
            return connection;
        }
    }

    @Getter
    @Setter
    public static class Target {
        private boolean enabled = false;
        private String type = "tibco";  // artemis | tibco
        private String serverUrl;
        private String username;
        private String password;
        private int timeoutSeconds = 30;

        /** Keep legacy YAML targets within the same finite range as database targets. */
        public void setTimeoutSeconds(int timeoutSeconds) {
            if (timeoutSeconds < 1 || timeoutSeconds > 300) {
                throw new IllegalArgumentException(
                        "echo.jms.target.timeout-seconds must be between 1 and 300");
            }
            this.timeoutSeconds = timeoutSeconds;
        }

        /** 目標 JMS Server 上要轉發到的 Queue */
        private String queue = "TARGET.REQUEST";
    }
}
