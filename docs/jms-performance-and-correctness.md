# JMS 效能與正確性

這次改善轉發資源重用，保持來源接收與交易流程。接收交易仍在原 listener 執行緒完成；不把訊息丟到背景後提早確認。

## 資源重用

- 使用 Spring `CachingConnectionFactory` 重用 Session 與固定目的地的 Producer，沒有自行實作 JMS Session pool。
- 每個借出的 Session 只能由該次呼叫使用，直到 `close()` 歸還。每個目標版本預設最多保留 8 個 idle Session；這不是活躍 Session 的上限。
- 每筆轉發仍建立獨立的 TemporaryQueue 與 Consumer。無論成功、逾時或錯誤，都關閉 Consumer 並刪除 Queue。
- 不能直接重用回覆 Queue：部分舊系統不設定 correlation ID，晚到或重複的回覆可能被下一筆請求誤收。
- 關閉 Spring 自動 exception reconnect，由現有目標 client 的引用計數管理退休與重建，避免一筆錯誤直接關掉其他進行中的 Session。
- 目標設定變更／刪除時，現有工作使用原 client 完成；退休 client 最後一個使用者離開時，連同快取及 provider factory 一起回收。

`CachedJmsConnectionFactory` 只處理快取與關閉，`JmsTargetForwarder` 保留轉發、診斷、逾時與目標切換的責任。來源 broker 的 ConnectionFactory 不套用這個 outbound 快取。

## 來源交易與慢速工作

來源 broker 的 transaction、listener 並發、記憶體額度、Scenario、記錄與回覆流程保持原有契約。這次不把訊息交給背景執行緒後提早 ACK，也沒有加入新的 source rollback／重送條件。

評估過以容量拒絕保留立即 Mock 的處理餘裕，但完整壓測顯示：低上限會使正常併發反覆重送、拉高 P99，甚至超過客戶端等待時間。因此這個原型已移除，沒有留下新的 `work.enabled` 設定、例外、跨 pipeline 的資源 scope 或 UI counter。失敗數據保留在 [補強報告](performance-hardening-2026-10-16.md)。

慢下游或刻意延遲仍可能占滿現有 listener；本次沒有宣稱解決單一 queue 中不同情境的強隔離。來源預取、訊息群組、順序限制及 broker 的重送／DLQ 政策仍按原設定運作。

這些改動不提供跨來源 broker、下游 broker 與資料庫的 exactly-once 保證。下游已收到請求後發生不確定失敗，或來源交易重送，仍須依原有的冪等／重送契約處理。

## 驗證與重現

- 真實 Artemis：4 路並行，每筆下游回兩次且不帶 correlation ID，逐筆檢查內容沒有串錯；工作完成後沒有遺留 TemporaryQueue。
- legacy、default 與具名目標：成功／逾時後恢復、快取啟用時 send 失敗後重連、設定移除後回收資源。
- 既有 HTTP/JMS 匹配、模板、記錄、診斷、交易、記憶體額度及架構測試繼續執行。

快取成本探針：

```bash
./gradlew jmsForwardingCost -PjmsBenchmarkOutput=/tmp/echo-jms-cost
```

使用真實 loopback TCP Artemis，兩種路徑都先暖機，再以 ABBA 順序比較 `session-cache-size=0/8`；每種訊息大小／併發各 8 輪，每筆核對回覆內容，每輪檢查回覆 Queue 清空。

此探針量的是 outbound request/reply 成本，**不是完整來源 queue → 規則匹配 → reply 的產品容量**。它使用 Java 17、512 MiB heap、非持久化測試 broker；不代表外部 TIBCO／持久化 journal 的吞吐或故障恢復能力。正式測量結果與完整檢查紀錄另存於本次效能報告。

回復舊的每次建立 Session 行為可將 `session-cache-size` 設為 `0`。不要藉由停用來源交易、移除記憶體額度或關閉 broker persistence 來追求數字。

完整應用探針（持久化來源 broker、實際規則 pipeline、逐筆核對內容與 correlation ID）：

```bash
./gradlew jmsApplicationCost -PjmsBenchmarkOutput=/tmp/echo-jms-app
```

兩個 JMS probe 都使用 Java／Artemis，可在 Windows 開發機以 PowerShell 執行，輸出放在本機目錄：

```powershell
.\gradlew.bat jmsForwardingCost '-PjmsBenchmarkOutput=build/jms-forwarding-cost'
.\gradlew.bat jmsApplicationCost '-PjmsBenchmarkOutput=build/jms-application-cost'
```

Windows 結果用於開發診斷；部署的效能比較仍在 Linux 上使用相同 JVM、broker 與負載條件重跑。

以 ABBA 比較 cache 0／8，兩者使用相同來源接收策略；它是同版本配置比較，並非舊 commit 與新 commit 的直接比較。來源交易、persistent delivery 及 journal 保留；下游 fixture 非持久化，請求紀錄停用。
