# 測試說明

完整回歸維持使用 `./gradlew t`。以下整理轉發資源清理的測試分工，不改變既有測試的執行條件或排除規則。

## 轉發資源清理

| 測試類別 | 類型 | 驗證範圍 |
| --- | --- | --- |
| `JmsTargetForwarderCleanupTest` | 單元測試，34 個參數化案例 | 清理順序、成功／逾時／非文字回覆、建立／發送／接收失敗、清理失敗後汰換連線、保留原始結果、併發及舊連線的延遲錯誤 |
| `JmsTargetForwarderCleanupIntegrationTest` | 內嵌 Artemis，3 個案例 | 三條轉發路徑在連續回覆及逾時後不遺留臨時 queue，連線仍保持可用 |
| `HttpOutboundForwarderCleanupIntegrationTest` | 本機 HTTP server，2 個案例 | 多個下游的空池及 metrics 自動移除，使用中及等待中的請求不受清理影響，清理後可重新轉發 |

JMS 的 `LEGACY`、`DEFAULT`、`SELECTED` 三條入口由 `JmsForwardingRoute` 提供共用設定。此輔助類別不放驗證斷言，也不建立 broker，避免單元測試和整合測試互相依賴。

併發單元案例使用自己持有的 executor 和 latch；即使斷言失敗，也會放行並關閉測試工作執行緒。整合案例自行建立及關閉測試 server，不需要事先啟動 Echo、Docker 或外部 broker。

## 執行方式

只跑 JMS 清理單元測試：

```sh
./gradlew test --tests 'com.echo.jms.JmsTargetForwarderCleanupTest'
```

只跑清理整合測試：

```sh
./gradlew test \
  --tests 'com.echo.jms.JmsTargetForwarderCleanupIntegrationTest' \
  --tests 'com.echo.service.HttpOutboundForwarderCleanupIntegrationTest'
```

包含既有轉發行為的相關回歸：

```sh
./gradlew test --tests '*JmsTargetForwarder*' --tests '*HttpOutboundForwarder*'
```

完整回歸與正式程式靜態檢查：

```sh
./gradlew t spotbugsMain
```

以上不是長時間負載測試。既有外部 JMS／手動端到端測試仍依其原本環境條件執行；內嵌 broker 測試不等同於實際外部 JMS 部署環境驗收。
