package com.echo.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.core.settings.impl.AddressFullMessagePolicy;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisMode;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Artemis 配置 - 從 echo.jms 讀取設定
 * <p>
 * Embedded mode 同時啟用 in-VM transport 和 TCP acceptor。
 * App 內部的 JmsListener 和 JmsTemplate 走 in-VM，
 * 外部服務可透過 TCP (預設 tcp://localhost:61616) 連入發送訊息。
 * <p>
 * 注意：使用 fully qualified @Configuration 避免與 Artemis Configuration 類別衝突
 * <p>
 * 設定外部 broker（{@code echo.jms.source.type} 不是 embedded）時不啟動內建 Artemis，
 * 改由 {@link ExternalJmsSourceConfig} 提供連線。
 */
@org.springframework.context.annotation.Configuration
@ConditionalOnProperty(name = "echo.jms.enabled", havingValue = "true")
@ConditionalOnExpression("!T(com.echo.config.JmsProperties$Source).isExternalType('${echo.jms.source.type:embedded}')")
@Slf4j
public class ArtemisConfig {

    private static final int DEFAULT_PAGE_SIZE_BYTES = 1024 * 1024;
    private static final int PAGE_CACHE_MAX_PAGES = 2;
    /** 只用來讓暫時佇列套用自己的 address setting；不會改變佇列名稱。 */
    static final String TEMPORARY_QUEUE_NAMESPACE = "echo-temporary-queues";
    /**
     * Broker 檢查可刪除佇列的間隔（Artemis 預設 30 秒）。客戶端請求很快時，等待刪除的佇列數約為
     * 每秒請求數 ×（閒置時間 + 檢查間隔），所以縮短到 5 秒。
     */
    static final long TEMPORARY_QUEUE_SCAN_PERIOD_MS = 5_000;

    @Bean
    @Primary
    public ArtemisProperties artemisProperties(JmsProperties jmsProperties) {
        ArtemisProperties props = new ArtemisProperties();
        props.setMode(ArtemisMode.EMBEDDED);
        props.setUser(jmsProperties.getUsername());
        props.setPassword(jmsProperties.getPassword());
        props.getEmbedded().setEnabled(true);
        // 關閉 persistence 時 Artemis 會用 heap 組裝 large message，paging 尚未介入就可能 OOM。
        props.getEmbedded().setPersistent(jmsProperties.isPersistent());
        props.getEmbedded().setDataDirectory(jmsProperties.getDataDirectory());
        return props;
    }

    /**
     * 加入 TCP acceptor，讓外部服務可透過 tcp://localhost:{port} 連入
     */
    @Bean
    public ArtemisConfigurationCustomizer artemisConfigurationCustomizer(JmsProperties jmsProperties) {
        return configuration -> {
            int port = jmsProperties.getPort();
            String acceptorUrl = "tcp://0.0.0.0:" + port
                    + "?protocols=CORE,OPENWIRE,AMQP"
                    + "&anycastPrefix=jms.queue."
                    + "&multicastPrefix=jms.topic.";
            try {
                configuration.addAcceptorConfiguration("tcp", acceptorUrl);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to configure Artemis TCP acceptor", e);
            }

            int memoryPercent = jmsProperties.getBrokerMemoryPercent();
            if (memoryPercent <= 0 || memoryPercent > 50) {
                throw new IllegalArgumentException("JMS broker memory percent must be between 1 and 50");
            }
            long maxHeapBytes = Runtime.getRuntime().maxMemory();
            long brokerMaxBytes = maxHeapBytes / 100 * memoryPercent
                    + maxHeapBytes % 100 * memoryPercent / 100;
            long boundedBrokerMaxBytes = Math.max(1, brokerMaxBytes);
            configuration.setGlobalMaxSize(boundedBrokerMaxBytes);
            configureDiskGuard(configuration, jmsProperties);
            // Keep large-message writes synchronous so an acknowledged durable
            // message is present on disk before the broker confirms delivery.
            configuration.setLargeMessageSync(true);
            AddressSettings addressSettings = new AddressSettings()
                    .setAddressFullMessagePolicy(AddressFullMessagePolicy.PAGE)
                    .setMaxSizeBytes(boundedBrokerMaxBytes)
                    .setPageSizeBytes(pageSizeBytes(boundedBrokerMaxBytes))
                    .setPageCacheMaxSize(PAGE_CACHE_MAX_PAGES)
                    .setDefaultConsumerWindowSize(jmsProperties.getConsumerWindowSize());
            configureRedelivery(addressSettings, jmsProperties);
            configuration.addAddressSetting("#", addressSettings);
            configureTemporaryQueueCleanup(configuration, jmsProperties);

            if (jmsProperties.isPolicySummaryEnabled()) {
                log.info("Echo JMS wildcard policy: maxDeliveryAttempts={}, deadLetterConfigured={}, "
                                + "expiryConfigured={}, fullPolicy=PAGE, pagingThresholdBytes={}, "
                                + "minDiskFreeBytes={}. AMQ222165/AMQ222166 describe address settings, "
                                + "not resource cleanup; expired messages are not retained without an expiry address.",
                        addressSettings.getMaxDeliveryAttempts(),
                        addressSettings.getDeadLetterAddress() != null,
                        addressSettings.getExpiryAddress() != null,
                        boundedBrokerMaxBytes, configuration.getMinDiskFree());
            }
            if (addressSettings.getMaxDeliveryAttempts() > 0
                    && addressSettings.getDeadLetterAddress() == null) {
                log.warn("Echo JMS wildcard policy has finite delivery attempts and no dead-letter address: "
                        + "messages are removed when attempts are exhausted. Artemis warnings remain enabled.");
            }

            log.info("Artemis TCP acceptor configured on port {}, paging after {} MB",
                    port, boundedBrokerMaxBytes / (1024 * 1024));
        };
    }

    private static int pageSizeBytes(long brokerMaxBytes) {
        long maxPageSize = Math.max(1, Math.min(DEFAULT_PAGE_SIZE_BYTES, brokerMaxBytes));
        return (int) maxPageSize;
    }

    private static void configureDiskGuard(
            org.apache.activemq.artemis.core.config.Configuration configuration,
            JmsProperties properties) {
        long minDiskFreeBytes = properties.getMinDiskFreeBytes();
        int diskScanPeriodMs = properties.getDiskScanPeriodMs();
        if (minDiskFreeBytes <= 0) {
            throw new IllegalArgumentException("JMS minimum free disk bytes must be positive");
        }
        if (diskScanPeriodMs < 100) {
            throw new IllegalArgumentException("JMS disk scan period must be at least 100 ms");
        }
        // Artemis blocks producers at this guard before an ENOSPC can trigger
        // its critical I/O shutdown path. Once space returns, producers resume.
        configuration.setMinDiskFree(minDiskFreeBytes);
        configuration.setDiskScanPeriod(diskScanPeriodMs);
    }

    /**
     * 沒有 consumer、閒置超過設定時間的暫時佇列由 broker 刪除。
     * <p>
     * 暫時佇列原本只在建立它的連線關閉時才刪除。客戶端若在一條長連線上每次請求都建新的回覆佇列
     * 卻不刪，每個佇列約佔 50 KB heap，
     * 幾天後就會耗盡記憶體。仍有 consumer 的佇列不受影響；客戶端之後再對已刪除的佇列建立 consumer 時，
     * Artemis client 會自動重建。只套用到暫時佇列，其他佇列沿用 {@code #} 的設定。
     */
    private static void configureTemporaryQueueCleanup(
            org.apache.activemq.artemis.core.config.Configuration configuration,
            JmsProperties properties) {
        long idleDeleteMs = properties.getTemporaryQueueIdleDeleteMs();
        if (idleDeleteMs < 0) {
            throw new IllegalArgumentException("JMS temporary queue idle delete time must not be negative");
        }
        if (idleDeleteMs == 0) {
            return;
        }
        configuration.setTemporaryQueueNamespace(TEMPORARY_QUEUE_NAMESPACE);
        configuration.setAddressQueueScanPeriod(TEMPORARY_QUEUE_SCAN_PERIOD_MS);
        // 與 # 的設定合併：分頁、記憶體上限與重送設定不變，只多了閒置刪除。
        // 訊息數不限：沒有 consumer 的回覆佇列裡只剩客戶端已放棄等待的逾時回覆。
        configuration.addAddressSetting(TEMPORARY_QUEUE_NAMESPACE + ".#", new AddressSettings()
                .setAutoDeleteCreatedQueues(true)
                .setAutoDeleteQueuesDelay(idleDeleteMs)
                .setAutoDeleteQueuesMessageCount(-1));
    }

    private static void configureRedelivery(AddressSettings settings, JmsProperties properties) {
        long delayMs = properties.getRedeliveryDelayMs();
        long maxDelayMs = properties.getMaxRedeliveryDelayMs();
        double multiplier = properties.getRedeliveryMultiplier();
        int maxAttempts = properties.getMaxDeliveryAttempts();
        if (delayMs < 0 || maxDelayMs < 0 || maxDelayMs < delayMs) {
            throw new IllegalArgumentException(
                    "JMS redelivery delays must be non-negative and max delay must not be smaller than delay");
        }
        if (!Double.isFinite(multiplier) || multiplier < 1.0) {
            throw new IllegalArgumentException("JMS redelivery multiplier must be finite and at least 1");
        }
        if (maxAttempts == 0 || maxAttempts < -1) {
            throw new IllegalArgumentException(
                    "JMS max delivery attempts must be -1 or a positive number");
        }
        settings.setRedeliveryDelay(delayMs)
                .setRedeliveryMultiplier(multiplier)
                .setMaxRedeliveryDelay(maxDelayMs)
                .setMaxDeliveryAttempts(maxAttempts);
    }
}
