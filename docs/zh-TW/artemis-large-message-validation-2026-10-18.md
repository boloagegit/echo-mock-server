# Artemis 大訊息升級驗證

[English](../artemis-large-message-validation-2026-10-18.md) · [前一輪 spool 實驗](request-log-spool-read-experiment-2026-10-18.md)

## 原本問題與改善方式

先前 Echo 的 JFR 有 823 個末端取樣落在 `ChannelBufferWrapper.writeByte`，呼叫鏈來自 `OutputStream.write` 的逐 byte 迴圈與 `ClientLargeMessageImpl$ActiveMQOutputStream`。官方 [ARTEMIS-6009／PR 6369](https://github.com/apache/artemis/pull/6369) 於 2026-04-21 合併，正是覆寫批次 `write(byte[], offset, length)` 來修正這條路徑。官方作者的提升比例不當作 Echo 成果；逐版核對原始碼，2.54.0 起包含修正。

[Artemis 大訊息文件](https://artemis.apache.org/components/artemis/documentation/latest/large-messages.html) 說明預設門檻為編碼後 100 KiB，文字編碼約每字元 2 bytes；約 64 Ki 字元的文字就可能觸發。UTF-8 bytes、Java 字元數與 broker 編碼大小不能混用。

採用官方 2.57.0 BOM，依[官方遷移說明](https://artemis.apache.org/artemis-tlp-groupid-migration) 統一 Maven group 為 `org.apache.artemis`，Java package 不變。Netty 從 4.1.135 升到 Artemis POM 使用的 4.1.137。依 [Spring Boot JMS 文件](https://docs.spring.io/spring-boot/3.5/reference/messaging/jms.html)，內建 broker 改用 `artemis-jakarta-server`，也移除原本和 Jakarta client 並存的舊 `artemis-jms-client` 實作；沒有把清除重複套件單獨宣稱為效能成果。

兩份凍結 JAR 的應用程式 class CRC 完全相同。持久化、`largeMessageSync=true`、listener 交易、ACK／重試、訊息類型、ID／correlation、回覆佇列與記憶體准入均沿用原設定，沒有自行維護 provider 分叉或訊息解碼器。provider 的間接依賴也一起更新，因此實測不能把所有差異精確歸給單一 patch。

## 應用層實測

四個新 Echo JVM，依舊／新／新／舊執行。Linux 4 CPU 配額、Java 17.0.20.1／G1、`-Xms512m -Xmx512m`、相同主資料庫 seed、保留 10,000 筆、主紀錄寫入上限 1,000／秒、128 KiB padding。每輪先完成 12,000 筆混合暖機；JMS 再暖機 5 秒、量測 30 秒、8 個呼叫端；HTTP 暖機 5 秒、量測 10 秒、16 個連線。兩邊的測試 client 與共用下游固定為 Artemis 2.40.0。計時期間不跑本機編譯或 JFR；只有高壓速度比較關閉備份。

| 情境 | 舊／新平均回應數每秒 | 變化 | 舊／新各輪 P99 的平均，ms |
|---|---:|---:|---:|
| JMS 模擬 | 301.6／578.8 | +91.9% | 40.7／22.2 |
| JMS 轉發 | 108.5／130.7 | +20.4% | 110.9／95.4 |
| HTTP 轉發，短測 | 954.5／955.5 | +0.1% | **53.3／76.4** |

各輪原始回應數每秒：

| JVM | JMS 模擬 | JMS 轉發 | HTTP 轉發 | 包含暖機的總紀錄丟棄 |
|---|---:|---:|---:|---:|
| 舊 1 | 266.5 | 96.8 | 938.5 | 138 |
| 新 1 | 582.5 | 132.2 | 973.1 | 106 |
| 新 2 | 575.2 | 129.2 | 937.9 | 71 |
| 舊 2 | 336.7 | 120.2 | 970.4 | 1 |

回應本文／狀態／correlation 核對皆正確。所有 JMS 量測均無紀錄丟棄或未確認寫入。HTTP 正式窗口的丟棄依序為 0／106／71／1；第一輪舊版的 138 筆在正式窗口外。總數包含暖機、各情境及清空階段。storage retries／未確認寫入均為 0，資源能清空，資料庫 `quick_check` 通過。高壓回應速度不等於可無限持續的完整紀錄寫入速度。

P99 是兩輪 percentile 的平均，並非合併所有請求後重算。每版只有兩個 JVM，舊版 JMS 有明顯波動，轉發也受固定舊 client／下游限制，且短測 HTTP 尾延遲變差。因此不能宣稱所有協定、所有本文都加速，也不是重跑 WireMock 比較。

### HTTP 獨立補測

針對短測尾延遲變差，另開四個 JVM，依舊／新／新／舊執行，使用相同 seed、heap、紀錄設定與舊下游。JMS 維持開啟但沒有流量；HTTP 暖機 30 秒、清空待寫紀錄，再以 16 連線量測轉發 30 秒。不跑 JFR 或其他本機負載。

| JVM | 回應數每秒 | P99，ms | 正式紀錄丟棄，含 retention skip | 其中 retention skip |
|---|---:|---:|---:|---:|
| 舊 1 | 1,074.6 | 47.830 | 3,693 | 3,288 |
| 新 1 | 1,079.3 | 47.128 | 3,295 | 2,841 |
| 新 2 | 1,044.6 | 47.404 | 3,100 | 2,243 |
| 舊 2 | 1,027.4 | 48.369 | 2,202 | 1,652 |

舊／新平均吞吐為 1,051.0／1,062.0（+1.0%），各輪 P99 平均 48.10／47.27 ms。回應核對、停流清空及資料庫檢查均通過；重試與未確認寫入均為 0。含暖機的總丟棄依序 3,982／3,669／3,713／2,305。retention skip 表示已超過設定保留窗口、不再寫入的紀錄，已算在 dropped 內，不重複相加；這是高壓測試，不能宣稱紀錄可持續完整。

獨立、暖機較長的控制沒有重現短測 HTTP 尾延遲增加；暖機、量測時間與前段有無 JMS 流量都不同，因此不能推翻原負面結果，也不能證明所有情境相同。不宣稱 HTTP 有實質加速。

## 正確性與相容性

另外 10 個檢查通過：2.40／2.57 的四種 TCP client／broker 配對都能拒絕錯誤密碼，並正確處理含跳脫字元、中文／emoji 的持久化大 TextMessage，以及 rollback／重送；核對本文、message ID、correlation、自訂屬性及 persistent delivery mode。此外，舊 broker 寫入持久化大訊息、正常關閉後，由新版直接重開同份 journal，確認能讀取／rollback／重送／commit。這不等於斷電、程序崩潰或 broker 群集驗證。

新增永久測試 `ArtemisLargeMessageIntegrationTest`，使用暫存目錄與跨平台 NIO，驗證大 Unicode 文字在 broker 重啟及 rollback 後完整。本機完整測試：主測試 2,219＋移轉 9，零失敗、6 個跳過，SpotBugs 通過。[CI](https://github.com/boloagegit/echo-mock-server/actions/runs/39140666233) 通過 Linux JDK 17／21、含空白路徑的 Windows JDK 17，以及 Docker 部署檢查。

## 十分鐘混合觀察

8 個限速呼叫端、合計 200 筆／秒，HTTP／JMS 各半，混合模擬與真實轉發，128 KiB padding、512 MiB heap、保留 10,000 筆、寫入上限 1,000／秒。六輪各 20 秒暖機後，連續量測 600 秒；client／下游固定舊 2.40。排程備份、housekeeping 與管理介面的列表／詳細／流量查詢持續執行；正式流量期間沒有強制 GC。

正式 120,000 筆回應在 600.071 秒完成且核對正確（HTTP／JMS 各 60,000）。含暖機共處理 144,000 筆紀錄，丟棄、storage retries、未確認寫入與 JMS 失敗均為 0，停流後資源清空。備份完成 7 次、清理 5 份、失敗 0 次；資料庫 `quick_check` 通過，最後保留 9,761 筆。正式期間記錄 1,117 次自然 GC，整體 GC 歷史清除 1,258 筆過期事件。

**原始結果 `passed=false`：七個成長門檻有六個通過。** 執行緒分鐘統計為 186→190.5（+4.5），超過原門檻 +4，未更改門檻或結果。前後 thread dump 顯示 Artemis server worker 20→25、remoting 10→11、paging 4→5、client flow-control 5→6，JMS listener 數相同。這與池逐步暖機相符，但不能證明永久安全。前一輪 2.40 觀察也因執行緒 +5 未過；不同單次觀察不能用來精確歸因。

進一步核對凍結的 2.57 JAR：server worker／paging／client flow-control 預設上限分別為 30／5／10，Echo 沒有覆寫這些設定；結束快照分別為 25／5／6。這些池與 remoting 中新出現的 worker，快照均停在 executor 等待任務的 `TIMED_WAITING`／`ThreadPoolExecutor.getTask`，沒有卡在訊息 callback。逐一比較 thread ID，另確認 4 條舊 remoting worker 及原本 4 條 paging worker 已退出；JMS listener 維持 30→30。remoting 使用閒置 60 秒回收的 cached executor，沒有固定的小上限。這些證據支持正常擴池／回收，說明單看總執行緒 +4 警報不足以判定洩漏；也沒有證明所有池都有硬上限或未來一定持平，原始未過門檻結果保留。

自然 GC 後 heap 分鐘統計成長 5.05 MiB，低於 24 MiB 門檻；native committed 成長 0.21 MiB、direct 0.025 MiB、RSS 13.20 MiB。有限窗口通過不等於完全沒有成長。停流、清空與查詢後的一次診斷 GC，heap 為 104.48 MiB，**程序 RSS 為 1,100.07 MiB**。

## 代價、適用範圍與來源

這項修正用在 Echo 讀取 Artemis 大訊息的路徑；來源及目標都是 EMS 時，不會執行這條 Artemis 複製路徑。TIBCO provider 程式與 client JAR 未變；沒有實測授權 EMS 的效能、ACK 或 failover。保留標準 `TextMessage` 處理，避免為某個 provider 另寫一套 JMS 契約。

[2.57 升級說明](https://artemis.apache.org/components/artemis/documentation/latest/versions.html) 包含 secured Core 握手／群集調整、預設關閉 discovery，以及 selector 的 `%` 萬用字元預設最多 5 個。本次驗證獨立 Echo 的 request/reply，沒有涵蓋所有 selector 或 secured cluster。升級前保留 broker 資料備份；向前升級能讀取，不代表可以安全原地降版。512 MiB heap 不等於程序只用 512 MiB；十分鐘也不能證明永久不會洩漏或 OOM。

修改前 production source 為 `9927869`，修改後 `8e354ea`。JAR SHA-256：舊 `2aa310f13e0faabed77714a512f965aa6bdcc279b77880461854aa7ef05fbbf8`；新 `72fe33085ab0565db6541c89f999ff902109fbfcbf0b3d21353b684e41959133`。兩邊都有前一輪 spool 優化，不能把成果加到之前的 JDBC／spool 或 WireMock 數字。JAR、資料庫、錄製檔與逐筆紀錄均留在 Git 外，只提交精簡文字報告。
