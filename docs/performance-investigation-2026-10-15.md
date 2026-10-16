# HTTP 效能與短時間恢復測試（2026-10-15）

後續已完成 [原生 HTTP 入口架構實驗](native-http-performance-2026-10-15.md)。本篇保留改動入口之前的基線與 I/O 調校結果；後續對替代方案的判斷請一併閱讀新報告。

本次量測 `8ee8dcbed814a3dfb5fab1f6b791556d6c81cea7` 與 WireMock standalone 3.13.2，目的為確認最新 Echo 的差距與瓶頸。測試使用獨立暫存資料庫，沒有改動服務實作。

## 結論

- 固定 JSON 的兩輪結果，Echo 約 17,648 req/s，WireMock 約 44,076 req/s；這台機器上 WireMock 吞吐量約為 2.5 倍。Echo 兩輪為 15,993 / 19,304，波動約 21%，不能把平均值當成精確容量承諾。
- 大型 XML 與 2,000 條規則隨機命中則是 Echo 較快。這不是所有請求都輸給 WireMock 的問題。
- 最新取樣出現值得優先調查的 **Undertow 連線狀態重試迴圈**：`HttpReadListener.handleEvent` 第 128 行占 2,248 / 8,413 個 Java execution samples（26.7%）。不能繼續只引用舊報告，把主要原因都歸給 MVC 路由。
- 額外 AB/BA 對照中，只將 I/O 執行緒改為 1，固定 JSON 吞吐量由平均 15,414 提升至 22,421 req/s（+45.5%），每筆程序 CPU 成本降低 37.3%。這是該機器與該情境的診斷結果，未改正式預設。
- 後續多情境回歸顯示單 I/O 有取捨：小回應 +35.3%、模板 +23.0%、快速轉發 +20.3%；256 條連線的固定回應 -6.6%、讀完整份 XML -7.5%。因此保留 CPU-derived 預設，提供[部署覆寫指南](http-io-tuning.md)，不把固定 JSON 的提升當作全面提升。
- 開啟記錄的短時間高壓測試沒有 HTTP / socket error，壓力下降後延遲恢復，最後停止送流量 20 秒後佇列清空。這不等於已通過數小時或數天的耐久測試。
- 高壓時仍有 **185,859 筆記錄在記憶體暫存區被捨棄**；另有 2,172,464 筆因只保留最新 10,000 筆的策略而被略過。成功回應數不能當成完整記錄保存數。

## 條件與限制

| 項目 | 設定 |
| --- | --- |
| 機器 | AMD EPYC 9V74，容器 CPU quota 4 cores，記憶體上限 16 GiB |
| JVM | OpenJDK 21.0.12.1，雙方 `-Xms1g -Xmx1g -XX:+UseG1GC` |
| 壓測工具 | wrk 4.2.0；與服務位於同一容器，共用 CPU quota |
| 規則 | 同主機 4 條情境規則 + 2,000 條精確路徑規則 |
| 記錄 | 基準與 CPU profile 雙方均關閉；恢復測試另開 Echo 預設記錄 |
| 順序 | 第一輪 Echo → WireMock；第二輪 WireMock → Echo，每次重新啟動、重新建資料 |
| 暖機 | 初始固定回應 15s、小 XML 5s、隨機路徑 5s；各測量前另暖機 5s |
| 測量 | 4 個 wrk threads、8 條連線；固定 JSON 20s，其餘各 10s |
| 正確性 | 壓測前檢查所有情境的 status 與完整預期 body；壓測中檢查 wrk 錯誤計數 |

數字為兩輪平均（兩個樣本的中位數亦相同）；p99 也是各輪 p99 的平均，並非合併所有請求後重新計算。wrk 是封閉迴圈負載，不能將其 p99 當成固定到達率、正式網路環境中的尾延遲保證。壓測中沒有逐筆驗證 body，wrk 的錯誤數也不包含 3xx。

此機器與舊文件的 Intel Xeon 機器不同，不能直接相減來推算版本改善幅度。初始基準沒有轉發與延遲回應；這兩項的後續結果見下方「多情境回歸」。JMS、入口 TLS 效能、磁碟故障與長時間耐久性不在本次量測範圍。

## 最新基準

| 情境 | Echo req/s | WireMock req/s | Echo p99 | WireMock p99 |
| --- | ---: | ---: | ---: | ---: |
| 固定 JSON | 17,648 | 44,076 | 5.62 ms | 3.96 ms |
| 模板 | 14,099 | 25,594 | 6.2 ms | 4.2 ms |
| 約 1.5 KB XML + 條件 | 13,061 | 17,081 | 6.0 ms | 4.6 ms |
| 約 100 KB XML + 條件 | 10,416 | 2,223 | 6.3 ms | 17.6 ms |
| 2,000 條路徑隨機命中 | 16,064 | 4,735 | 5.1 ms | 12.7 ms |

以上所有測量的 wrk HTTP / socket error 計數為零。XML 的條件欄位位於文件前端，Echo 可用串流比對提早結束；不能將這組數字推廣到所有 XPath 表達式與欄位位置。

固定 JSON 的程序 CPU 時間（`/proc/PID/stat` 的 user + system，包含 GC 與背景執行緒）除以完成請求數，兩輪平均為 Echo **189.2 µs/req**、WireMock **57.2 µs/req**。這顯示伺服器端成本有實際差距；它不是單筆請求延遲，也不是純規則比對時間。工具與服務共用 CPU，因此吞吐量比值不必等於 CPU/req 比值。

壓測後 full GC 的 heap 約 Echo 58 MiB、WireMock 14 MiB；RSS 約 Echo 1,198 MiB、WireMock 866 MiB。RSS 包含 JVM 已提交的 heap、metaspace、native memory 等，不能當成存活物件大小或記憶體洩漏證據。

## CPU 與配置取樣

另開新程序，完成初始暖機後，使用 JFR `settings=profile` 記錄固定 JSON 的 5s 暖機 + 45s 測量。這組吞吐量受 profiler 影響，不混入上方基準。

Echo 共有 8,413 個 execution samples。以堆疊最接近頂端的已知函式庫歸類：

| 歸類 | 取樣占比 |
| --- | ---: |
| Undertow / XNIO | 52.8% |
| Spring web 其他處理 | 18.5% |
| Spring 其他處理 | 9.8% |
| Micrometer | 4.3% |
| MVC handler lookup | 3.8% |
| Echo | 3.8% |
| Spring Security | 3.2% |
| JDK / 其他 | 3.9% |

這是 Java 執行取樣的歸類，不是所有 CPU（例如 kernel、GC worker）的精確占比，也不是可以直接消除的成本比例。分類腳本附在資料目錄中，inclusive stack 統計會重疊，不能相加。

### Undertow 狀態重試

從實際載入的 Undertow 2.3.24.Final 對應 source JAR 核對：

```java
while (requestStateUpdater.get(this) != 0) {
    if (requestStateUpdater.compareAndSet(this, 1, 2)) {
        // suspend reads, then return
    }
}
```

`HttpReadListener.handleEvent` 頂端 frame 出現在第 128 行的 samples 有 2,248（26.7%）；另有 911 個落在第 137 行的方法呼叫處，**不把這 911 個也算成忙等**。這是 I/O 執行緒與工作執行緒切換連線狀態時的重試線索，並非正在比對 mock 規則。尚不能單靠這次 profile 宣稱 Undertow 普遍有缺陷；CPU quota、執行緒數與本機 keep-alive 高頻流量都可能影響其占比。

### 配置與 GC

Echo 的 allocation sampling 熱點包括 `HttpServletRequestImpl.getParameterMap`、Undertow security context、regex matcher 與集合 iterator。前兩者分別約占 JFR allocation sample 權重的 14.1% 與 8.5%。權重是取樣估計，不能當成精確配置位元組數。

Echo 在該錄製區間有 52 次 GC，總 pause 約 0.125s、最長約 7.73ms；WireMock 有 131 次，總 pause 約 0.177s、最長約 10ms。這次證據不支持「Echo 較慢主要因為 GC 停頓特別多」的說法，仍不能據此排除 GC CPU 或配置本身的成本。

關閉記錄後，pipeline 仍建立 match-chain JSON 與 ServeEvent，屬於可研究的省工方向；目前沒有證據顯示單改這裡就能追回大部分差距。

## I/O 執行緒對照

為驗證上面的線索，再跑四個全新 Echo 程序：預設 → 單 I/O → 單 I/O → 預設。相同 JVM、2,004 條規則、關閉記錄、8 條連線，固定 JSON 正式測量延長為 30s。唯一服務設定差異為 `--server.undertow.threads.io=1`；worker threads 皆維持 application.yml 設定的 32。

| 設定 | 第一輪 req/s | 第二輪 req/s | 平均 req/s | 平均 p99 | 平均程序 CPU/req |
| --- | ---: | ---: | ---: | ---: | ---: |
| 預設 4 I/O threads | 14,733 | 16,094 | 15,414 | 6.36 ms | 224.94 µs |
| 1 I/O thread | 22,434 | 22,408 | 22,421 | 4.01 ms | 140.97 µs |

四次正式測量均無 wrk HTTP / socket error。單 I/O 吞吐量平均增加 45.5%，每筆程序 CPU 成本降低 37.3%。這是同一批 AB/BA 實驗內的比較，不能把這裡的單 I/O 數字直接與先前不同測量時段的預設數字相減。

第二輪各自另錄製 5s 暖機 + 20s 的 JFR，只用於定位，不算入這張吞吐量表。

在這組獨立 profile 中，預設設定有 1,013 / 3,833（26.4%）個 execution samples 落在 `HttpReadListener.handleEvent` 第 128 行；單 I/O 為 320 / 2,574（12.4%）。吞吐、程序 CPU/req 與該重試迴圈的取樣占比一起改善，支持先研究執行緒競爭成本；不代表已完全消除它。這兩份 CPU 摘要只匯出 execution samples，其中 allocation / GC 欄位為零代表未匯出，不能解讀為沒有配置或 GC。

**此組適用範圍：**本機 keep-alive、小型固定回應、4-core quota。後續擴大負載後發現吞吐取捨，見下一節；TLS 效能、跨主機網路與其他 CPU 配額仍未量測。

## 多情境回歸

同一台機器再以預設 → 單 I/O → 單 I/O → 預設的順序啟動四個獨立程序。兩種設定皆關閉請求記錄，worker threads 皆為 32。基準的 2,004 條規則增加 6 條測試規則，總計 2,010 條。初始暖機 25s，各負載另暖機 3s、正式測量 10s。

以下為兩輪平均，差異以同批預設值為分母。這批與前述 30s 固定回應實驗的時間、規則數不同，不能混算提升幅度。

| 情境 | 預設 req/s | 單 I/O req/s | 吞吐差異 | 預設 p99 | 單 I/O p99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 固定 JSON，8 連線 | 16,093 | 21,775 | +35.3% | 5.91 ms | 4.21 ms |
| 固定 JSON，64 連線 | 27,648 | 29,416 | +6.4% | 26.41 ms | 14.02 ms |
| 固定 JSON，256 連線 | 33,183 | 30,996 | -6.6% | 51.08 ms | 22.36 ms |
| 模板，8 連線 | 14,980 | 18,427 | +23.0% | 5.89 ms | 4.46 ms |
| 約 100 KB XML，條件在前端，8 連線 | 10,180 | 12,571 | +23.5% | 6.67 ms | 4.82 ms |
| 約 100 KB XML，條件在最後，8 連線 | 3,608 | 3,338 | -7.5% | 9.52 ms | 9.29 ms |
| 256 KiB 固定回應，64 連線 | 9,626 | 10,180 | +5.8% | 36.72 ms | 28.80 ms |
| 快速下游轉發，64 連線 | 5,093 | 6,126 | +20.3% | 44.88 ms | 38.41 ms |
| 下游延遲 50ms，64 連線 | 1,231 | 1,225 | -0.5% | 57.63 ms | 59.67 ms |
| Mock 延遲 50ms，64 連線 | 1,248 | 1,245 | -0.2% | 55.07 ms | 56.51 ms |
| 最後回到固定 JSON，8 連線 | 16,193 | 20,287 | +25.3% | 5.81 ms | 4.17 ms |

兩輪、短時間測量不足以建立精確的信賴區間。尤其小幅差異不能推廣為通用結論；但完整 XML 的兩個單 I/O 樣本都低於兩個預設樣本，已足以反對僅憑小回應結果全面更換預設。

**正確性與恢復：**44 個正式負載階段的 wrk HTTP / socket error 均為零。每個程序在負載前後檢查精確回應 body、status、大回應的完整 262,144 bytes、延遲不短於 45ms，以及下游 503 原樣傳回。503 是額外的預期錯誤回應檢查，不計入 wrk 成功負載。四個程序在最後的 `activeForwards`、`pool.leased`、`pool.pending`、`pool.bufferedResponseBytes` 均為零，無容量拒絕與轉發 transport failure。

**下游限制：**使用同容器中的 Node HTTP fixture，單獨直接量測約 40,803 req/s，高於這批 Echo 轉發約 5,000–6,000 req/s。它提供基本容量對照，但同容器共享 CPU 與 loopback，仍不是獨立下游、真實網路或 TLS 的容量保證。50ms 情境主要受等待時間限制，不能據此判定兩種設定的 CPU 上限相同。

**決定：**保留 Undertow 與現有自動 I/O 預設。以 `SERVER_UNDERTOW_THREADS_IO=1` 作為可比較的部署選項，適用性由實際負載決定。現有證據既不足以支持全面固定為 1，也沒有完成替代 HTTP server 的實測，不能宣稱換 Tomcat／Jetty 一定更快。

## 改善優先順序

1. **先處理 HTTP 入口的執行緒配置與狀態切換成本。** 多情境回歸已完成，部署選項與還原方式已補入文件。若進一步修改實作，應以這批混合負載作比較，避免用高併發或完整 XML 的吞吐換取單一案例的分數。
2. **再減少框架與診斷的重複工作。** 包含參數 map、路徑判斷、match-chain JSON；保留可觀測性與既有語意，逐項做量測對照。先前「MVC 約 45%」不能當成目前這台機器的結論。
3. **獨立處理完整記錄需求。** 預設模式在超載時會捨棄待寫記錄；若產品目標要求每筆可稽核，就必須另外比較等待寫入、拒絕流量或提高儲存處理量的成本。

現有證據不支持先換資料庫、重寫規則引擎，或直接承諾改掉 MVC 就能追平 WireMock。

## 開啟記錄的恢復測試

使用相同 2,004 條規則、固定 JSON、預設非同步記錄與最新 10,000 筆保留上限。每階段前都有 5s 暖機：

| 階段 | 測量時間 | req/s | p99 |
| --- | ---: | ---: | ---: |
| 原始負載，8 連線 | 20s | 10,799 | 11.74 ms |
| 高負載，256 連線 | 60s | 22,279 | 70.05 ms |
| 回到 8 連線 | 20s | 12,661 | 6.36 ms |
| 停流量 10s，再跑 8 連線 | 20s | 12,393 | 6.52 ms |

四階段均無 wrk HTTP / socket error；持續的暖機也可能影響前後差異，因此只判定這次沒有持續惡化，不宣稱高壓使服務變快。

- 高壓階段結束，log agent queue 約 28,684 筆；回到 8 連線後約 11,032 筆；首次停流量 10s 後仍有 2,000 筆；最後停流量 20s 後為零。
- Full GC 後 heap：壓力前 60.2 MiB，最後 59.6 MiB。恢復期間每 2s 取樣的 RSS 最高約 1,230 MiB，最後約 1,217 MiB。沒有要求 RSS 回到剛啟動時，因 JVM 仍保留已提交 heap。
- `RequestLogHandoff` 的警告累計 **185,859 筆**被捨棄，原因為記憶體緩衝區超過 20,000 筆 / 64 MiB 上限。
- LogAgent 另略過 **2,172,464 筆**依保留上限本來就會刪除的舊記錄，兩者合計為 agents API 的 `droppedCount=2,358,323`。不能把合計全稱為意外資料遺失。
- 以上累計包含暖機與各測量階段，不能除以只包含正式測量的請求數來算遺失率。

這次驗證的是單次短高壓後的恢復；尚未證明沒有慢性洩漏、下游故障恢復或磁碟故障安全性。

### 單 I/O、記錄開啟的補充驗證

另外使用 `SERVER_UNDERTOW_THREADS_IO=1` 重跑相同恢復流程，仍為 2,004 條規則、1 GiB heap、預設非同步記錄與保留最新 10,000 筆。這是單次補充驗證，沒有與先前預設設定的恢復測試做同批交錯比較，不拿兩次數字計算改善幅度。

| 階段 | req/s | p99 |
| --- | ---: | ---: |
| 原始負載，8 連線 | 14,685 | 5.01 ms |
| 高負載，256 連線 | 23,879 | 32.55 ms |
| 回到 8 連線 | 15,758 | 4.53 ms |
| 停流量 10s，再跑 8 連線 | 15,582 | 4.73 ms |

四階段均無 wrk HTTP / socket error。最後停流量 20s 後 queue size、queue bytes 與 in-flight bytes 都為零；full GC 後 heap 從 60.1 MiB 回到 59.5 MiB。這次沒有觀察到 `RequestLogHandoff` 暫存區捨棄，仍有 **2,712,632 筆**因最新 10,000 筆保留策略而略過。這既不是完整記錄保存的保證，也不代表其他負載下不會發生暫存區捨棄。

### 既有行為回歸

以 `SERVER_UNDERTOW_THREADS_IO=1`、Java 17 專案測試 toolchain 執行四個既有測試類別，共 **58 個測試通過，0 failure / error / skipped**：

- `HttpMockIntegrationTest`：16 個。
- `SseForwardingRegressionIntegrationTest`：7 個。
- `UniversalMockControllerTest`：18 個。
- `UniversalMockControllerSseTest`：17 個。

前兩類為 Spring Boot 隨機 port 整合測試，後兩類為 controller 測試；不把它們當成 SSE/TLS 的效能或長時間穩定性證據。

## 重跑與原始資料

腳本位於 [scripts/bench/2026-10-15](../scripts/bench/2026-10-15/)；原始資料未保留在 repository，數字以本文為準。

```bash
# 需 java、jcmd、wrk、curl、python3；18080 必須空閒。
bash scripts/bench/2026-10-15/run.sh ECHO_JAR WIREMOCK_JAR OUT_DIR 2
PERF_MODE=profile bash scripts/bench/2026-10-15/run.sh ECHO_JAR WIREMOCK_JAR PROFILE_DIR 1
PERF_MODE=recovery bash scripts/bench/2026-10-15/run.sh ECHO_JAR WIREMOCK_JAR RECOVERY_DIR 1
PERF_MODE=io-compare bash scripts/bench/2026-10-15/run.sh ECHO_JAR WIREMOCK_JAR IO_COMPARE_DIR 2
PERF_MODE=io-workloads bash scripts/bench/2026-10-15/run.sh ECHO_JAR WIREMOCK_JAR WORKLOADS_DIR 2
SERVER_UNDERTOW_THREADS_IO=1 PERF_MODE=recovery bash scripts/bench/2026-10-15/run.sh ECHO_JAR WIREMOCK_JAR IO1_RECOVERY_DIR 1

jfr print --json --events jdk.ExecutionSample,jdk.ObjectAllocationSample,jdk.GarbageCollection PROFILE_DIR/echo-log-off-r1/profile.jfr > profile.json
python3 scripts/bench/2026-10-15/analyze-jfr.py profile.json
python3 scripts/bench/2026-10-15/summarize.py OUT_DIR
```

OUT_DIR 必須選用新目錄，腳本會覆寫同名 run 子目錄。此腳本以當時的 `scripts/bench-vs-wiremock.sh` fixtures 為基礎，保留一份研究快照；不取代原本完整比較腳本。

`io-workloads` 另需 Node，會在 127.0.0.1:18081 啟動並清理隨附的 downstream fixture；該連接埠也必須空閒。

最後保存的重跑腳本將 I/O 對照組明確指定為 4、worker 明確指定為 32，與本次實際值相同，避免呼叫者先前匯出的 `SERVER_UNDERTOW_THREADS_IO=1` 汙染對照組。在不同 CPU 數的機器上，4 不一定等於該機器的自動預設。
