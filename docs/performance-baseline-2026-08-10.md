# Echo 轉發與模擬效能基準（2026-08-10）

> 本文件是保留測試條件與決策背景的歷史工程紀錄，不代表目前預設設定。請以 `application.yml`、README 與最新同環境 A/B 結果為準；文件中的臨時路徑不是專案資料或部署位置。

## 基準範圍

- 基準版本：優化前的本機整合版本
- 測試目的：鎖定 LogAgent、HTTP Mock、HTTP 下游轉發與 JMS 規則匹配的優化前基準。
- 資料隔離：使用系統暫存目錄下的臨時 H2 資料庫；停用備份與定期清理，未使用專案正式 `mockdb`。
- HTTP 下游：本機無輸出測試服務，監聽 `127.0.0.1:18081`。
- JMS：獨立 H2 資料庫與 Embedded Artemis，監聽 `16161`；批次匯入只在此測試程序啟用。
- 請求紀錄：維持正式預設的 database store、3,000 queue、50 batch、5 秒 flush、10,000 筆上限。

## 測試環境

| 項目 | 值 |
| --- | --- |
| 作業系統 | Darwin 25.5.0 arm64 |
| CPU | Apple M5，10 cores |
| 記憶體 | 32 GiB |
| 應用 JVM | Azul OpenJDK 17.0.12 |
| Undertow workers | 32 |
| Hikari max pool | 15 |
| wrk | 4.2.0，kqueue |
| HTTP target pool | max total 30、max per route 10 |

所有 HTTP 測試均先啟動應用並完成暖機。比較優化前後時，必須使用相同 commit 衍生版本、硬體、連線數、測試時間、資料庫模式及請求紀錄設定。

## HTTP Mock 基準

既有 `scripts/stress-test-rps.py`，每個場景 10 秒、20 並發：

| 場景 | RPS | 平均 | p50 | p95 | p99 | 錯誤 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 簡單 JSON、無條件、17 B | 8,953 | 2.2 ms | 2.0 ms | 4.2 ms | 5.8 ms | 0 |
| 複雜 JSON、10 候選＋模板、146 B | 8,203 | 2.4 ms | 2.2 ms | 4.6 ms | 6.6 ms | 0 |
| 小型 XML、XPath、約 2 KiB | 7,885 | 2.5 ms | 2.3 ms | 4.5 ms | 6.9 ms | 0 |
| 大型 XML、XPath、約 99 KiB | 1,448 | 13.9 ms | 7.3 ms | 38.3 ms | 134.7 ms | 0 |

另以 `wrk -t4 -c8 -d10s` 測固定 JSON Mock：

| RPS | 平均 | p50 | p90 | p99 | 錯誤 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 20,849.74 | 447.85 µs | 356 µs | 546 µs | 2.94 ms | 0 |

兩組工具的並發模型不同，數字不可互相比較；優化前後只能用同一列、同一命令比較。

## HTTP 轉發基準

| 場景 | wrk 設定 | RPS | 平均 | p50 | p90 | p99 | 結果 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 即時下游 `/fast` | 4 threads、20 connections、15 s | 14,404.88 | 6.25 ms | 0.92 ms | 11.44 ms | 96.11 ms | 217,519 次，0 錯誤 |
| 100 ms 下游 | 4 threads、20 connections、15 s | 92.95 | 213.23 ms | 213.91 ms | 219.69 ms | 228.86 ms | 1,398 次，0 錯誤 |
| 5 s 下游、read timeout 3 s | 4 threads、40 connections、12 s | 10.74 | 3.16 s | 2.99 s | 6.03 s | 7.02 s | 130 次，全部為預期 502 |

100 ms 下游的中位延遲接近 214 ms，與每個目標最多 10 條連線、同時 20 個呼叫形成兩批執行相符。逾時案例的 p99 達 7.02 秒，表示連線池等待與下游讀取逾時會疊加，實際牆鐘時間沒有被單一 3 秒上限約束。

## 慢下游隔離基準

先以 40 connections 持續呼叫 `/slow/5000`，2 秒後同時以 8 connections 測固定 JSON Mock：

| 純 Mock 狀態 | RPS | 平均 | p50 | p99 |
| --- | ---: | ---: | ---: | ---: |
| 無慢下游干擾 | 20,849.74 | 447.85 µs | 356 µs | 2.94 ms |
| 40 個慢下游請求並行 | 7.84 | 981.39 ms | 998.35 ms | 1.08 s |

純 Mock 吞吐下降 99.96%。這證明同步 HTTP 轉發會占滿 Undertow workers，慢下游可以拖垮完全不需轉發的 Mock 請求；這是目前最高優先級的穩定性問題。

## Request Log 基準

| 負載 | processed | queue | dropped |
| --- | ---: | ---: | ---: |
| 快速 HTTP 轉發，15 s | 7,150 | 3,000（滿） | 207,395 |
| 固定 JSON Mock，10 s | 9,799 | 3,000（滿） | 199,456 |
| 100 ms HTTP 轉發，15 s | 1,371 | 47 | 0 |

快速轉發時，每次請求各產生一行 Mock INFO、一行 forward INFO；queue 滿後又為每次丟棄產生 WARN。單次 15 秒測試形成 642,518 行、約 96 MiB console log，其中包含 207,395 行 queue-full WARN。這個 log storm 本身會消耗 CPU、I/O 並放大故障。

高 RPS 結果不代表請求紀錄具同等持久化能力：目前約 95% 的高速請求紀錄被丟棄。使用者已確認請求紀錄可接受有限資料遺失，因此後續以「Mock／轉發不中斷、佇列有界、丟棄可觀測但不逐筆刷 WARN」為設計原則。

## JMS 規則匹配基準

`scripts/bench-2000-jms.py` 建立 2,000 條同 queue、同 ServiceName 的規則，讓命中規則需走過所有候選；XML body 為 654 B：

| 指標 | 結果 |
| --- | ---: |
| 冷啟動第一次 match | 65 ms |
| 10 次 match 平均 | 11.0 ms |
| 暖機後 9 次平均 | 5.0 ms |
| 暖機後範圍 | 4–8 ms |
| 10 次 response 平均 | 11.2 ms |
| 匹配正確率 | 10/10 |

這是 JMS 規則匹配基準，不是外部 TIBCO／IBM MQ 的端到端 RPS。外部 broker 的網路、broker 設定與 client library 不在本機基準範圍內；之後若要優化 JMS transport，需在公司測試環境補同版本 broker 的獨立 E2E 負載測試。

## 優化後比較門檻

1. 一般 HTTP Mock 四個既有場景的 RPS 不得下降超過 5%，錯誤仍須為 0。
2. 固定 JSON Mock 的隔離 RPS 不得下降超過 5%，p99 不得明顯劣化。
3. 慢下游干擾時，純 Mock 不可再出現 99% 以上崩落；目標至少保留隔離吞吐的 80%，且 p99 回到毫秒級。
4. 3 秒下游逾時的總牆鐘 p99 應受明確上限約束，不再延伸到 7 秒。
5. Request Log queue 必須有界；請求執行緒不得 flush database。滿載可丟棄，但不得逐筆輸出 WARN，且 `processed`、`queued`、`dropped` 必須持續可觀測。
6. JMS 2,000 規則的暖機後平均不得劣化超過 10%，10/10 必須匹配正確。

## 重跑命令

```bash
python3 scripts/stress-test-rps.py http://127.0.0.1:18080 10 20

wrk -t4 -c8 -d10s --latency --timeout 5s \
  -H 'X-Original-Host: baseline.mock' \
  http://127.0.0.1:18080/mock/baseline/static

wrk -t4 -c20 -d15s --latency --timeout 5s \
  http://127.0.0.1:18080/mock/fast

wrk -t4 -c20 -d15s --latency --timeout 5s \
  http://127.0.0.1:18080/mock/slow/100

wrk -t4 -c40 -d12s --latency --timeout 8s \
  http://127.0.0.1:18080/mock/slow/5000

python3 scripts/bench-2000-jms.py http://127.0.0.1:18080
```

慢下游隔離測試必須將 40-connection `/slow/5000` 負載放在背景，等待 2 秒後，再執行固定 JSON Mock 的 wrk 命令。比較時應保留原始輸出與 `/api/admin/agents` 快照。

## 第 1、2 批優化結果（未提交）

本輪聚焦在請求紀錄熱路徑，不處理慢下游隔離：

- Mock／轉發／JMS 的逐請求 `INFO` 降為 `DEBUG`，避免正常流量形成 console I/O。
- queue-full 警告改為 10 秒彙總一次，不再逐筆輸出。
- `RequestLogService` 不再於 LogAgent 不可用時同步寫入 DB；佇列滿載可丟棄紀錄。
- LogTask 改為 queue 有容量時才建立，滿載時不複製候選規則與 body。
- 批次寫入只由單一背景 consumer 執行，請求執行緒不再 flush DB。
- 背景持久化預設最多 1,000 筆／秒；queue 過半後暫停額外匹配鏈重算，直到 backlog 清空。

同一組 `wrk` 命令的結果：

| 場景 | 原基準 RPS | 優化後 RPS | 變化 | 原 p99 | 優化後 p99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 固定 JSON Mock | 20,849.74 | 26,230.51 | +25.8% | 2.94 ms | 1.50 ms |
| 即時 HTTP 下游 | 14,404.88 | 18,952.16 | +31.6% | 96.11 ms | 36.96 ms |

兩者皆為 0 錯誤。快速轉發的 console 由原基準 642,518 行／約 96 MiB，降為 35 行／4.1 KiB。另一組相鄰 A/B 的官方 Python 場景中，舊版單輪產生 210,700 行／33.9 MiB；目前版本即使包含超過一輪與一次中止測試，仍只有 116 行／14.9 KiB。

`scripts/stress-test-rps.py` 在本機重跑期間，另有不屬於本測試的 Gradle test executor 間歇占用 100%–340% CPU，使同一版本的簡單 JSON 在 4,607–9,071 RPS 間波動，故這批 Python 數字不作為最終效能判定。乾淨時目前版本觀測到簡單 JSON 9,071 RPS、複雜 JSON 8,333 RPS、小型 XML 7,302 RPS，皆為 0 錯誤；仍應在沒有其他工作負載的獨立 runner 補跑後再鎖定四場景回歸門檻。

驗證結果：

- `./gradlew t --console=plain`：通過，2 分 15 秒。
- `./gradlew spotbugsMain --console=plain`：通過，7 秒。
- Spring Boot 使用全新臨時 H2 實際啟動成功。

這一輪沒有變更 Entity schema、`application.yml` 預設值或正式 `mockdb`。`echo.agent.log.max-write-rate-per-second` 可在部署環境覆寫；程式內預設為 1,000。

## 第 3 批：HTTP 慢下游隔離與逾時治理（未提交）

實作原則是只將真正的下游 I/O 移到獨立的背景執行緒；一般 Mock 仍在原請求流程直接完成，不付出額外排程成本。

- HTTP pipeline 支援非同步完成轉發，Undertow worker 不再等待下游 socket。
- 依使用者要求，`echo.http.forward.max-concurrent` 程式預設為 `0`，代表不設應用層同時轉發上限；動態執行緒閒置 60 秒後回收。
- 設定正整數可重新啟用應用層上限；該模式容量用盡時以 50 ms 非阻塞 backoff 回應，避免客戶端立即重試形成 502 retry storm。
- 連線池預設提高為單一下游 route 50 條、每個 HTTP client pool 合計 200 條；等待連線池空位改為獨立的 3,000 ms 上限，避免平均 1 s 的健康下游在短暫尖峰下因原 250 ms 設定過早回應 502。指定連線每組 profile 擁有自己的 pool；`X-Original-Host` 共用一個 pool，才是跨 host 合計 200 條。
- 指定 HTTP 連線的解析結果進入 Caffeine cache；更新或刪除時同步清除 cache 與舊 Client。
- `X-Original-Host` 轉發改用相同的隔離執行與逾時策略，仍保留公司內網預設不驗證 HTTPS 憑證，並保留呼叫端 `Authorization` 的原有行為。

以 Fat Jar、全新臨時 H2 與相同本機下游重跑：

| 場景 | 結果 | p99 | 錯誤 |
| --- | ---: | ---: | ---: |
| 固定 JSON Mock，無慢下游 | 38,008.51 RPS | 1.43 ms | 0 |
| 固定 JSON Mock，同時 40 個 5 s 慢轉發 | 44,745.27 RPS | 1.49 ms | 0 |
| 固定 JSON Mock，同時 100 個 5 s 慢轉發 | 53,657.43 RPS | 1.09 ms | 0 |
| 即時 HTTP 下游 | 19,459.82 RPS | 2.51 ms | 0 |
| 40 個 5 s 下游、read timeout 3 s | 116.69 RPS | 3.19 s | 全為預期連線池／逾時回應 |
| 100 個 5 s 下游、read timeout 3 s | 329.93 RPS | 2.94 s | 全為預期連線池／逾時回應 |

不設應用層上限時，40 與 100 個慢轉發干擾下的 Mock 吞吐都高於當次無干擾數字，屬於本機測試波動；可確定的結論是沒有再現原基準由 20,849.74 RPS 崩落到 7.84 RPS 的問題，p99 仍在 1.09–1.49 ms。不設應用層上限代表高並行度會建立更多 platform threads；單一下游 50 條／全部 200 條的連線池、3,000 ms pool-acquire timeout 與 read timeout 仍會限制真正下游 I/O，並非無限 socket 或無限等待。

正確性驗證：

- `./gradlew t --console=plain`：1,086 項、0 失敗，6 項既有外部／條件式跳過。
- 實際本機 HTTP 下游每筆延遲約 1 s，同一 route 並行 50 筆：50/50 皆回應 200，無連線池等待逾時。
- `./gradlew spotbugsMain --console=plain`：通過。
- Python 黑箱 E2E（Embedded Artemis 啟用）：114/114 通過。
- 無應用層上限最終版使用全新臨時 H2 重跑 HTTP／SSE 黑箱 E2E：103/103 通過。
- 指定 HTTP 連線轉發、狀態碼與 Request Log 一致。
- 自簽 HTTPS `X-Original-Host` 實際轉發回傳 208 及正確 body，憑證與 hostname 驗證仍依預設跳過。

本批不變更 Entity schema、`application.yml` 預設值、轉發規則語意或正式資料庫；所有效能與 JMS E2E 輸出都寫入系統暫存目錄，未加入版本控制。

## 第 4 批：HTTP 連線生命週期與可觀測性（未提交）

這一批使用系統暫存目錄下的臨時 H2，沒有讀寫正式 `mockdb`。下游仍為本機無輸出測試服務，測試時關閉 JMS、備份與排程清理。

實作內容：

- 一般 HTTP `DeferredResult` 預設逾時由 30 s 對齊為 40 s，高於目前預設 pool 3 s + connect 5 s + response 30 s 的最壞路徑；可用 `echo.http.request-timeout-ms` 覆寫。若 profile 主動設定更長的 connect/read timeout，部署時必須將這個總逾時一併提高。
- 每 30 s 由單一 maintenance scheduler 清理 expired 與閒置 30 s 連線，不為每個 profile 額外建立清理 thread。
- 從 pool 取出閒置超過 5 s 的連線前進行 stale validation，降低內網 LB／防火牆已關閉 keep-alive 後的第一筆 reset。
- `GET /api/admin/http-target-connections/metrics` 回傳 active/executor task、pool leased/pending/available/capacity，以及每個 profile 的 capacity/pool/connect/read/other 失敗分類。
- 總完成量使用 executor `completedExecutorTasks` 觀察；不在每筆成功轉發上寫共用計數器，避免監控本身降低 RPS。失敗與 timeout 仍維持逐筆精確計數，所以可以用完成量與失敗量分開觀察。

相同指令在當次測試中的前後比較（JIT 暖機後數字）：

| 場景 | 本批修改前 | 本批修改後 | 結果 |
| --- | ---: | ---: | --- |
| 即時下游，50 並行 | 17,310.10 RPS，p99 32.32 ms | 18,558.22 RPS，p99 10.01 ms | +7.2%，0 錯誤 |
| 1 s 下游，50 並行 | 46.83 RPS，p99 1.01 s | 44.82 RPS，p99 1.01 s | -4.3%，0 錯誤 |
| 1 s 下游，100 並行 | 45.83 RPS，p99 2.02 s | 48.91 RPS，p99 2.02 s | +6.7%，0 錯誤 |
| 1 s 下游，200 並行 | 60.82 RPS，p99 4.01 s，136 個非 2xx | 60.34 RPS，p99 4.01 s，149 個非 2xx | pool 3 s 保護維持 |
| 100 個 5 s 慢轉發干擾下的 Mock | 50,202.82 RPS，p99 1.20 ms | 51,915.68 RPS，p99 1.00 ms | +3.4%，Mock 隔離維持 |

黑箱正確性驗證：

- 預設 HTTP profile 轉發與下游 418 status passthrough 正確。
- 規則指定 `X-Original-Host` 實際連到自簽 HTTPS，仍保留公司內網預設不驗證憑證行為。
- 1 s read timeout 實際於約 1.01 s 回傳 502，指標正確累計 `readTimeouts=1`。
- 200 並行壓力的 pool acquire 失敗會被獨立分類為 `poolTimeouts`。
- 停止請求 35 s 後，pool `available` 實際由 50 降為 0，證明 idle cleanup 有執行。
- `./gradlew t spotbugsMain --console=plain`：1,088 項測試、0 失敗、6 項既有條件式跳過；SpotBugs 通過。

轉發仍使用 blocking RestTemplate；取消 `CompletionStage` 不能保證立即中止正在等待 socket 的工作。本批沒有用關閉共用 pool 的方式強制取消，避免誤傷同 profile 的其他正常請求；真正的 per-request cancellation 留給後續非阻塞 HTTP client 重構。

## 第 5 批：非阻塞 HTTP client 與端到端取消（未提交）

本批只重構 outbound HTTP transport。入口仍是 Spring MVC + Undertow，規則匹配、JPA、JMS 與既有同步管理 API 都沒有改成 reactive。實際下游 I/O 改由 Reactor Netty event loop 執行，不再為每個慢下游占用一條 `http-forward-*` platform thread。

實作內容：

- 每個 HTTP profile 快取一個 Reactor Netty client 與連線池；`X-Original-Host` 共用獨立 client。原本總連線 200、單一下游 50、pool acquire 3 s、idle 30 s 的保護語意維持。
- 自訂非阻塞 admission limiter 補上跨 route 總量與主動 deadline；等待 timeout、取消與完成都會歸還 permit。動態 `X-Original-Host` 的 route 狀態在最後一筆完成後移除，不會因大量不同 host 永久成長。
- Controller timeout 會沿 `PipelineResult`、預設連線 fallback、指定連線一路取消到 Reactor subscription；取消中的 socket 立即交還 pool，不再只取消表面的 `CompletableFuture`。
- profile 預設 `tlsVerificationEnabled=false` 行為不變；自簽憑證與 hostname 不相符的 HTTPS 皆已實際驗證可轉發。啟用驗證的 profile 仍使用正常憑證檢查。
- 回應仍完整讀成 `String`，沒有擅自加入 256 KiB 上限；512 KiB response、gzip、非 2xx status passthrough 與宣告 charset 解碼均有測試。送出 String body 時依 `Content-Type` charset（未指定則 UTF-8）重新計算 `Content-Length`，避免改成 chunked transfer 影響舊式內網下游。
- metrics 新增 `ioThreads`、`completedForwards` 與 `cancelledForwards`；舊有 `executorThreads`、`completedExecutorTasks`、`validateAfterInactivitySeconds` JSON 欄位保留為相容 alias。
- Mac 執行環境同時打包 Apple Silicon／Intel 的 Netty native DNS resolver；Linux／Windows 仍使用各自的正常 resolver。

相同本機下游與 wrk 條件的比較：

| 場景 | blocking 基準 | 非阻塞 client | 變化 |
| --- | ---: | ---: | ---: |
| 即時下游，50 並行 | 18,558.22 RPS，p99 10.01 ms | 17,965.57 RPS，p99 10.13 ms | -3.2%，0 錯誤 |
| 1 s 下游，50 並行 | 44.82 RPS，p99 1.01 s | 46.61 RPS，p99 1.01 s | +4.0%，0 錯誤 |
| 1 s 下游，100 並行 | 48.91 RPS，p99 2.02 s | 47.27 RPS，p99 2.02 s | -3.4%，0 錯誤 |
| 1 s 下游，200 並行 | 60.34 RPS，149 個非 2xx | 61.61 RPS，151 個非 2xx | +2.1%，pool 3 s 保護相同 |
| 100 個 5 s 慢轉發干擾下的 Mock | 51,915.68 RPS，p99 1.00 ms | 48,768.74 RPS，p99 1.32 ms | -6.1%，仍保留 93.9% 隔離吞吐 |

測試期間同機另有獨立 Gradle test worker 長時間使用 CPU，因此剔除明顯受干擾的 9k RPS 樣本；表格採用低系統負載、JIT 暖機後的相同命令結果，原始輸出只保留在系統暫存目錄。

正確性與穩定性驗證：

- Python HTTP／SSE 黑箱 E2E：103/103 通過。
- 指定 profile 回傳下游 418、預設 profile fallback、512 KiB body 皆正確。
- 自簽且 hostname mismatch 的 HTTPS profile 實際回傳 200。
- 將 MVC request timeout 設為 1 s，5 s 下游實際於 1.126 s 回 504；一秒後 metrics 為 `active=0`、`leased=0`、`cancelled=1`。
- 呼叫端主動斷線不是 Servlet MVC 能可靠即時通知 application 的事件；這種情況仍由 response timeout 回收。若未來要求 client disconnect 當下立刻取消，需另行評估將入口改成 WebFlux，不能只靠替換 outbound client 保證。

## 第 6 批：移除單一下游限制、統一全域容量（未提交）

- 移除單一下游 route 50 條的獨立限制，不再保留 `max-connections-per-route` 設定。
- `echo.http.forward.max-connections` 改為全部 HTTP 轉發共用的單一並行容量，預設由 200 提高為 1,000。
- 可使用環境變數 `ECHO_HTTP_FORWARD_MAX_CONNECTIONS` 覆蓋，不需修改程式碼。
- 所有指定 HTTP profile 與 `X-Original-Host` 轉發共用同一個非阻塞 limiter；因此多個目標同時繁忙時，合計最多占用設定值，不再是每個 profile 各有一份額度。
- Reactor Netty 仍依遠端位址維護連線池，但單一 pool 的容量設為全域上限；真正的跨 pool 合計由共用 limiter 控制。
- 舊監控欄位 `maxConnectionsPerPool`、`maxConnectionsPerRoute` 暫時保留為相容別名，回報新的全域上限，避免既有監控解析失敗。

## 第 7 批：Reactive 容量防護與固定 heap A/B（未提交）

### 穩定性強化

- 新增 `echo.http.forward.max-pending-requests`，預設 1,000。全域 1,000 條轉發連線用盡後，只允許固定數量的非阻塞等待者；再超出的請求立即回應 `HTTP_FORWARD_PENDING_CAPACITY_EXHAUSTED`，不再無界累積 future、request body 與 timeout task。設為 0 代表不等待，不代表無限制。
- 新增 `echo.http.forward.max-response-body-bytes`，預設 10 MiB。已知 `Content-Length` 會在讀取前拒絕；未知長度或 chunked 回應則逐 chunk 計數，超過上限立即取消下游讀取，不會先把完整回應聚合到 heap 才檢查。
- `echo.http.request-timeout-ms` 與 `echo.http.forward.pool-acquire-timeout-ms` 都正式列入設定；HTTP profile 儲存與解析時會驗證 `pool acquire + connect + read` 必須小於 MVC 總時間預算，避免外層先回 504、內層仍繼續占用資源。錯誤訊息已提供中英文 i18n。
- 轉發請求會移除 RFC hop-by-hop headers，也會解析 `Connection` header 指定的額外逐跳欄位；request body 仍重新計算正確的 `Content-Length`。
- HTTP profile 更新或刪除時，舊連線池先標記 retired，等該版本的進行中請求結束才關閉；不再因設定更新中斷正在轉發的正常請求。
- metrics 新增 pending 上限、response body 上限、容量拒絕與 response-too-large 分類。等待逾時、容量拒絕、大量取消與回應過大都會歸還 permit。

對應環境變數：

| 設定 | 預設 |
| --- | ---: |
| `ECHO_HTTP_FORWARD_MAX_CONNECTIONS` | 1,000 |
| `ECHO_HTTP_FORWARD_MAX_PENDING_REQUESTS` | 1,000 |
| `ECHO_HTTP_FORWARD_MAX_RESPONSE_BODY_BYTES` | 10,485,760 bytes |
| `ECHO_HTTP_FORWARD_POOL_ACQUIRE_TIMEOUT_MS` | 3,000 ms |
| `ECHO_HTTP_REQUEST_TIMEOUT_MS` | 40,000 ms |

### 正確性驗證

- `./gradlew t spotbugsMain`：1,112 項測試、0 失敗、0 錯誤、6 項條件式跳過；SpotBugs 通過。
- Python HTTP／SSE 黑箱 E2E：103/103 通過。
- 實際 socket 測試涵蓋 chunked body 越界、完整 hop-by-hop header 移除、profile 更新期間的進行中請求，以及全域容量 1／pending 1 的即時拒絕。
- limiter 另以 500 個等待者同時取消，確認 pending 計數歸零且下一筆仍可取得 permit。

### 512 MiB heap 容量曲線

比較版本：目前 Reactive hardening 工作樹與 Blocking commit `d66212b`。兩者都使用 OpenJDK 22.0.2、`-Xms512m -Xmx512m`、全新臨時 H2、相同 1 秒非阻塞本機下游（11-byte JSON body）、相同 Undertow 設定、database request log，以及相同 1,000 全域／單 route 連線容量。每檔以 `wrk -t4 -d8s --timeout 6s` 測試；測試前主動 full GC，測試中每秒取樣 process、JVM 與 socket 指標。

| 並行 | Blocking RPS | Reactive RPS | Blocking p99 | Reactive p99 | 非 2xx／socket error |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 50 | 41.77 | 41.77 | 1.02 s | 1.01 s | 兩版皆 0 |
| 100 | 87.12 | 87.46 | 1.01 s | 1.01 s | 兩版皆 0 |
| 200 | 179.84 | 173.90 | 1.01 s | 1.02 s | 兩版皆 0 |
| 500 | 436.44 | 436.65 | 1.01 s | 1.01 s | 兩版皆 0 |
| 1,000 | 870.85 | 871.38 | 1.01 s | 1.02 s | 兩版皆 0 |

吞吐在此 1 秒下游場景幾乎相同；Reactive 的目的不是讓下游回得更快，而是用固定 event-loop 資源維持相同吞吐。

| 並行 | Blocking peak threads | Reactive peak threads | Blocking peak heap | Reactive peak heap | Blocking peak RSS | Reactive peak RSS |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 50 | 145 | 126 | 94.2 MiB | 100.5 MiB | 788.9 MiB | 855.7 MiB |
| 100 | 197 | 125 | 130.5 MiB | 138.7 MiB | 795.0 MiB | 856.2 MiB |
| 200 | 296 | 126 | 204.2 MiB | 212.0 MiB | 807.1 MiB | 856.4 MiB |
| 500 | 596 | 126 | 332.2 MiB | 346.2 MiB | 824.6 MiB | 856.8 MiB |
| 1,000 | 1,097 | 127 | 356.4 MiB | 348.7 MiB | 819.1 MiB | 860.2 MiB |

在 1,000 並行時，Reactive 以 127 條 process threads 完成與 Blocking 1,097 條 threads 相同的吞吐，峰值 thread 數減少 88.4%。兩版都只有 3 次 young GC、0 次 full GC；Reactive GC 時間 12 ms，Blocking 為 14 ms。TCP socket 峰值相同（約 2,001，包含 client 與 downstream 兩側），證明 Reactive 沒有以偷偷降低真正連線數換取 thread 數下降。

RSS 沒有下降：Reactive 約 860 MiB、Blocking 約 819 MiB；這包含 512 MiB Java heap 之外的 JVM、class metadata、direct buffer、native transport 與 thread stack resident pages。不能只看 RSS 宣稱全面省記憶體。真正明確改善的是 thread 數不再隨慢下游並行線性成長，以及取消能傳遞到底層 socket：1,000 並行的 wrk 結束後，Reactive metrics 立即回到 `active=0`，Blocking 快照仍有 153 筆背景工作，之後才自然完成。

CPU 每秒 `ps` 取樣在各檔沒有一致優劣（Reactive 1,000 並行平均 50.1%、Blocking 29.7%，500 並行則為 21.1% 與 25.8%），且 8 秒樣本容易受 JIT、GC 與取樣時間影響；本輪只保留原始數值，不據此宣稱 CPU 改善。若要做正式容量承諾，應在獨立 Linux runner 以 30–60 秒穩態、固定 CPU quota 與 JFR／async-profiler 再測。

這組 1,000 並行結果只證明小回應的 transport 容量，不代表可以同時緩衝 1,000 筆 10 MiB 回應。回應仍需完整組成 `String` 才能交給既有 MVC pipeline，因此部署值必須依實際 body 大小與 heap 一起估算；10 MiB 是單筆防線，不是全域記憶體保證。

原始輸出只保留在系統暫存目錄；測試全程未讀寫正式 `mockdb`。
