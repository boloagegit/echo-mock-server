# HTTP／JMS 效能、穩定性與啟動補強

> 本頁保留第一階段的量測與當時的判斷。後續已將 HTTP adapter、標準 Observation、TLS 與相容性保護整合進一般發行檔；現行使用方式見 [HTTP 加速路徑](native-http.md)，最新結果見 [正式整合與驗證](native-http-validation-2026-10-16.md)。下列「實驗 jar」數字不代表後續加入 Observation 的正式建置，也不能把舊版建置指令套到現行版本。

這次把「跑得快」和「能交付的預設」分開驗證。正式版移除前端資源定位的完整 classpath 掃描，並啟用 JMS outbound Session／Producer 重用，保留來源交易、持久化、每筆獨立回覆佇列與現有 listener 並發。原生 HTTP 入口補上資源邊界與失敗處理，仍是建置時選用的實驗，不包含在一般發行 jar。

## 啟動：移除 WebJars 的完整套件掃描

JFR 確認啟動時存在 `WebJarsResourceResolver → WebJarAssetLocator → ClassGraph.scan`，主執行緒有多次等待掃描的事件。將 `webjars-locator-core` 換成 Spring Boot 3.5.16 已管理的 `webjars-locator-lite:1.1.3`，由套件版本 metadata 解析原本的無版本網址。打包移除 core 與 ClassGraph，其他 runtime jar 不變；不需要自訂資源定位器或修改前端網址。

使用正式 Servlet jar、Java 17、512 MiB heap、4 核、同一份已有一條規則的 SQLite 複本，兩組 ABBA、每版各 4 次全新程序。保留預設請求紀錄；JMS 維持預設關閉。OS page cache 未清空，因此是程序冷啟動，不是整台機器冷開機；也沒有同時執行壓測、Gradle 或 JFR。

| 從程序啟動算起 | 原版平均 | Lite 平均 |
|---|---:|---:|
| 管理狀態 API 首次成功 | 10.70 秒 | 9.08 秒 |
| 第一筆實際 Mock 正確回覆 | 10.78 秒 | 9.15 秒 |
| 接著取得全部 11 個畫面依賴檔案 | 10.90 秒 | 9.26 秒 |

第一筆 Mock 可用時間縮短 **15.1%（約 1.62 秒）**。原版範圍 10.68–10.89 秒，Lite 8.93–9.47 秒。第一筆 Mock 本身平均 74.2 → 70.9 ms，沒有將整段掃描延至首筆請求。每輪都驗證實際回覆內容，11 個 JS／CSS／字型檔案的長度與 SHA-256 在新舊版全部相同；整合測試另驗證 `no-cache`、Last-Modified／304 與不存在的檔案回 404。

資料庫完整性檢查、Hibernate schema 更新、設定驗證及正常 bean 初始化都保留，沒有開啟全域 lazy initialization，也沒有降低 JIT 最佳化層級。剩餘啟動仍有 Spring／JPA／MVC 初始化成本；這次不是把啟動壓到 WireMock 的等級。外部資料庫、大量待恢復記錄、啟用 JMS 或不同磁碟，應用各自實際資料再量。

重現用 [startup/probe.py](../scripts/bench/2026-10-16-hardening/startup/probe.py)，需要空閒的 localhost:18082，會建立及清理自己的工作目錄。第一次用舊版建立 seed（不計分），之後所有輪次複製同一 seed；`OUTPUT` 每次必須不同。預設管理員帳密僅用於這個隔離 seed 的規則建立。

```bash
taskset -c 0-3 python3 scripts/bench/2026-10-16-hardening/startup/probe.py \
  /path/to/before.jar /tmp/startup-seed-run \
  --java /path/to/jdk17/bin/java --seed /tmp/startup-seed.sqlite
# 依 before → after → after → before 重跑兩組；每次給不同 OUTPUT。
taskset -c 0-3 python3 scripts/bench/2026-10-16-hardening/startup/probe.py \
  /path/to/after.jar /tmp/startup-after-r1 \
  --java /path/to/jdk17/bin/java --seed /tmp/startup-seed.sqlite
```

## JMS：降低重複建立資源的成本

使用 Spring `CachingConnectionFactory`，每個目標版本最多保留 8 個 idle Session；活躍 Session 仍獨占借用。TemporaryQueue 與 Consumer 每筆建立並清理，沒有共用回覆佇列，所以不要求舊下游新增 correlation ID。快取可用 `echo.jms.forward.session-cache-size=0` 關閉。

實際 TCP Artemis outbound 探針：兩條路徑完整暖機，每種大小／併發各 8 輪、兩組 ABBA；共 180,000 筆正式量測，逐筆核對唯一回覆內容並檢查暫存佇列清空。

| 訊息大小 | 併發 | 無快取 req/s | 快取 8 req/s | 比值 | 無快取 P99 ms | 快取 P99 ms |
|---|---:|---:|---:|---:|---:|---:|
| 約 1 KiB | 1 | 649 | 909 | 1.40× | 3.10 | 2.65 |
| 約 1 KiB | 8 | 1,754 | 2,630 | 1.50× | 8.56 | 6.40 |
| 約 100 KiB | 1 | 185 | 195 | 1.06× | 8.06 | 7.87 |
| 約 100 KiB | 8 | 285 | 294 | 1.03× | 42.32 | 41.40 |

大小指 1,024／102,400 個 ASCII filler 字元，另有短 ID 前綴；不是 broker 編碼後的 wire bytes。表中為各輪算術平均，P99 也取各輪 P99 平均，並非合併樣本的百分位。大訊息改善有限，個別快取輪次也有較慢結果。此表只量 outbound request/reply，不代表整個 Echo 的處理量。

完整應用比較另外使用持久化來源 Artemis、persistent delivery、SQLite、實際規則匹配與來源回覆。無快取／快取使用相同版本與 `10-30` listener；兩者保留原有接收策略，並停用請求紀錄。每筆檢查本文及 correlation ID，下游回覆不附 correlation ID。最終程式的 4 輪 ABBA 共核對 317,319 筆正式回覆，沒有轉發失敗或殘留下游暫存佇列。

| 完整流程 | 併發 | 無快取 req/s | 快取 req/s | 無快取 P99 ms | 快取 P99 ms |
|---|---:|---:|---:|---:|---:|
| 即時 Mock | 8 | 1,155 | 1,131 | 9.63 | 12.47 |
| 即時 Mock | 30 | 4,227 | 4,137 | 12.45 | 14.21 |
| 轉發 | 8 | 682 | 811 | 20.61 | 16.25 |
| 轉發 | 30 | 1,099 | 1,548 | 54.44 | 39.37 |
| 延遲 50 ms Mock | 30 | 527 | 528 | 61.75 | 60.90 |

完整轉發吞吐改善約 19%／41%。即時 Mock 吞吐約低 2%，P99 也較高；延遲 Mock 大致持平，不能宣稱所有 JMS 情境都改善。快取不參與純 Mock 的回覆路徑，但共用 broker／JVM 與前面負載，僅兩輪不足以歸因或證明所有尾端延遲均無退步。

另保留收斂前、限流停用時的 320,965 筆應用比較（`jms-application-cache-*`）。該批轉發平均改善約 15%／51%，但延遲 Mock 有一輪 P99 154 ms；沒有刪掉這個異常值。最終程式的數字以 `jms-application-final-*` 為準；兩批不合併成平均值。

## 沒有採用的預設：靠 rollback 限制慢速工作

測試過 `10-30` listeners、全域 24／單路由 12 筆慢速工作。30 路轉發雖提升吞吐，但產生 815 次容量拒絕（含暖機），P99 從基準 51–54 ms 增至 269 ms；下一個延遲 Mock 情境出現回覆逾時／不匹配斷言，該輪中止，不列入效能勝出數字。

內建 broker 預設無限重送，仍可能因退避與競爭超出客戶端等待時間。外部 broker 的次數上限與 DLQ 又有不同政策。因此移除整個 rollback 限流原型，包含新增的設定、例外、跨 pipeline 資源 scope 與 UI counter。原本的 listener 上限、記憶體額度、交易及 Scenario 流程維持原樣；沒有把驗證失敗的策略留成需要操作人員自行承擔風險的開關。

這也表示慢下游／刻意延遲仍可能占滿 listener，不能宣稱已完成單一 queue 內的強隔離。後續若要解決，必須連同來源佇列的排程、順序與交易契約設計，不能只加一個會反覆 rollback 的 semaphore。詳見 [JMS 契約與設定](jms-performance-and-correctness.md)。

## HTTP：補上保護再量效能

- 維持既有 pipeline、規則／模板／轉發／記錄流程，快取取得 singleton service 的結果。
- 專用有限工作池、有限排隊、活躍請求上限，以及本文／回覆大小與緩衝估算額度；不讓 XML／模板／資料庫運算跑在 I/O 執行緒。
- 超大本文回 413、超大回覆回 502、容量不足回 503。未知長度上傳到上限就停止讀取，不在拒絕路徑無限 drain 本文。
- 上傳期限關閉停滯連線；延遲、逾時、斷線與回應結束皆回收名額及排程。`CONNECTION_RESET` 仍關閉連線，不被本文限制變成 HTTP 錯誤回覆。
- 管理 API、特殊 URI／headers、SSE 等依原 fallback 規則走 Servlet。

預設實驗額度：512 筆活躍請求、512 個工作排隊位置、單本文 10 MiB、緩衝估算 128 MiB。這是 payload 的保守估算，不是 JVM heap 的硬上限。上傳期限從工作執行緒開始讀取時計算，不是涵蓋排隊、任意阻塞與所有運算的絕對總期限。管理端與 mock 的工作池分開，仍共享 JVM、CPU、資料庫與 heap。

原生入口尚未完成全部 Servlet observation／分散式 tracing、TLS 與長時間運行相容性驗證，因此沒有替換正式 HTTP 預設。[前一版架構量測](native-http-performance-2026-10-15.md) 是加上本次保護前的結果，不能直接當成此版本數字。

## HTTP 實測結果

加入上述保護的實驗 jar，與 WireMock 3.13.2 比較。每組設定各兩輪；WireMock 分別測 32／128 個 container workers，表中每一列取兩種設定中較高的平均吞吐作比較，不以較慢的設定製造優勢。六次程序、78 個量測階段合計 23,763,648 筆，wrk 回報 HTTP／socket 錯誤皆為 0；量測前後另核對模板、XML、轉發錯誤碼、延遲下限與完整大回覆內容。

| 情境 | Echo req/s | WireMock 較佳 req/s | 比值 |
|---|---:|---:|---:|
| 固定回覆，併發 8 | 63,824 | 45,427 | 1.41× |
| 固定回覆，併發 64 | 135,560 | 61,899 | 2.19× |
| 固定回覆，併發 256 | 143,538 | 66,980 | 2.14× |
| 動態模板，併發 8 | 57,013 | 26,286 | 2.17× |
| 2,000 條路徑規則，併發 8 | 62,417 | 5,022 | 12.43× |
| 小 XML，併發 8 | 36,504 | 16,845 | 2.17× |
| 大 XML、條件在前，併發 8 | 17,363 | 2,435 | 7.13× |
| 大 XML、條件在尾，併發 8 | 4,398 | 1,379 | 3.19× |
| 256 KiB 回覆，併發 64 | 15,821 | 2,528 | 6.26× |
| 快速下游轉發，併發 64 | 26,527 | 2,082 | 12.74× |
| 50 ms 下游轉發，併發 64 | 1,245 | 1,189 | 1.05× |
| 50 ms 延遲 Mock，併發 64 | 1,254 | 1,223 | 1.03× |
| 壓力後固定回覆，併發 8 | 65,918 | 47,230 | 1.40× |

慢速下游與刻意延遲的處理量受等待時間限制，改善只有約 3–5%。表中的大型規則與 XML 收益也與兩者匹配方式有關，不能全歸因於 HTTP 伺服器。這批資料的 jar SHA 與回歸摘要記錄於 `http-regression.json`；它在後續 WebJars 啟動依賴調整前建置，不能把其記憶體／啟動數字當成調整後的結果。

兩輪 Echo 轉發結束後，active／leased／pending／buffered response bytes 均歸零，沒有容量拒絕或目標轉發失敗。此檢查及短時間測量不能代替長時間 soak。

這批壓力後強制 GC 的 heap live size：Echo 約 60 MiB，WireMock 約 20 MiB；Echo metaspace 約 121 MiB，WireMock 約 29 MiB。Echo RSS 約 1.24–1.32 GiB，WireMock（128 workers）約 1.05–1.06 GiB；兩者都配置 `-Xms1g -Xmx1g`，RSS 包含已提交 heap 與非 heap，不等於 live objects。這些數據不支持「記憶體也全面勝出」。

### 相同到達率下的延遲

後續使用已含 WebJars Lite 的最終實驗 jar（SHA 見 `http-regression-final.json`），以 wrk2、64 連線、每階段 30 秒補一組 Echo → WireMock；WireMock 使用 32 container workers。這組只有一次、沒有 ABBA，不能從它證明所有負載的尾端延遲穩定勝出。兩者皆無 HTTP／socket 錯誤。

| 目標 req/s | Echo 實際 req/s | WireMock 實際 req/s | Echo P99 ms | WireMock P99 ms | Echo P99.9 ms | WireMock P99.9 ms |
|---|---:|---:|---:|---:|---:|---:|
| 20,000 | 19,975 | 19,815 | 2.74 | 2.95 | 19.57 | 5.76 |
| 40,000 | 39,949 | 39,949 | 2.83 | 9.48 | 7.49 | 18.86 |

20k 情境的 Echo P99.9 較差，40k 情境較好；不能把最大吞吐優勢當作「任意流量延遲都更低」。本機負載工具與服務共用 CPU，加上只量一輪，尚未區分 JIT、GC、排程或客戶端取樣的影響。這也是保持 HTTP 新入口為實驗版、而非直接宣稱全面取代 WireMock 的原因。

重跑時使用同一個 matrix 腳本與新輸出目錄，設定 `PERF_MODE=latency ECHO_BENCH_WORKERS=8 ECHO_BENCH_IO=2 WM_LATENCY_WORKERS=32`，並在 PATH 提供 `wrk2`。

## 驗證範圍與重現

環境：同一 Linux 容器、4 核 CPU 配額，效能量測固定在 CPU 0–3；伺服器、負載工具及 fixture 共用這 4 核。JMS Java 17，outbound 探針 heap 512 MiB，完整應用 heap 1 GiB。HTTP 執行 Java 21、兩產品各 1 GiB heap；建置與測試使用 Java 17。下游測試 Artemis 非持久化；完整應用的來源 broker 持久化。

```bash
./gradlew check bootJar -PtestForks=2 --no-daemon
taskset -c 0-3 ./gradlew jmsForwardingCost -PjmsBenchmarkOutput=/tmp/jms-outbound
taskset -c 0-3 ./gradlew jmsApplicationCost -PjmsBenchmarkOutput=/tmp/jms-app
```

HTTP 比較使用既有腳本，需在 PATH 準備 Java 21、`wrk`、Node、`jcmd`；輸出目錄必須是新目錄。兩者 request log／journal 都關閉，Echo 使用 2 I/O、8 workers；WireMock 開啟 async response、32 async threads。

```bash
./gradlew bootJar -PhttpTransportExperiment
ECHO_BENCH_WORKERS=8 ECHO_BENCH_IO=2 WM_BENCH_WORKERS=128 \
  taskset -c 0-3 bash scripts/bench/2026-10-15/transport-experiment/matrix.sh \
  build/http-experiment/echo-server-0.0.0-SNAPSHOT-http-experiment.jar \
  /path/to/wiremock-standalone-3.13.2.jar /tmp/http-matrix 2
```

每個程序先暖機，各情境再暖機 3 秒、量測 10 秒，順序 Echo → WireMock → WireMock → Echo。另以 `WM_ONLY=1 WM_BENCH_WORKERS=32` 與新目錄補測 WireMock 較小工作池。HTTP 測試是最大輸出負載下的吞吐與延遲；不同產品的 P99 並非相同到達率比較。

正式版 `check bootJar` 通過：2,051 個 JUnit 測試中 2,045 通過、6 個外部 fixture／專有 client 相關測試跳過，另有 9 個 H2 遷移測試通過；Error Prone 編譯與 SpotBugs 檢查通過。一般 jar 已檢查不包含 HTTP 實驗類別及移除的 JMS 限流原型。

自足 Artemis 整合測試驗證多路同時轉發、重複且不帶 correlation ID 的下游回覆、失敗後重連、目標退休與暫存佇列清理。來源交易與 Scenario 的原有回歸測試繼續保留。

HTTP 實驗版在啟動依賴調整後另有 133 個測試全部通過，SpotBugs 通過。涵蓋原有匹配、模板、記錄、權限、SSE fallback、前端資源，以及新增的容量滿載、合計緩衝額度、宣告／chunked 超大本文、UTF-8 回覆大小、慢速上傳、斷線與資源復原。

沒有將這些測試解讀為跨 broker／資料庫的 exactly-once 保證。沒有實測 TIBCO 專有 client、外部金融網路／TLS 或多日 soak。原有非同步請求紀錄在壓力下可丟棄待寫紀錄的契約也未改變；需要逐筆等待記錄持久化時，仍須使用既有 `wait-for-write` 設定。

原始資料未保留在 repository，數字以本文為準；啟動量測腳本位於 [scripts/bench/2026-10-16-hardening](../scripts/bench/2026-10-16-hardening/)。效能比較不以關閉來源交易、持久化或正確性檢查換取數字。
