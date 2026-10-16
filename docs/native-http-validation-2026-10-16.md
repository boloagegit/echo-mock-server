# 原生 HTTP 正式整合與驗證

原生 HTTP/1.1 adapter 已包含在一般發行檔。本文量測時以 `ECHO_HTTP_TRANSPORT=native` 啟用；後續依部署決定改為預設 `native`，明確設定 `servlet` 仍可切回原入口。下方 jar 雜湊、測試數與效能數字保留量測當時的版本，不代表改預設後重新壓測。移除原本的實驗 source set 與 Jetty／Tomcat server 比較依賴，改由 Spring Boot 的 Undertow customizer 安裝；TLS、壓縮與 graceful shutdown 繼續由 Boot 配置。設定、容量回應與相容性範圍見 [使用文件](native-http.md)。

本次補上 Micrometer Observation 與傳入 trace parent 的延續、工作執行緒 scope 清理、實際 TLS／HTTP/2／gzip、100-continue 與 Servlet 擴充回退。HTTP/2 不使用以關閉整條連線實作取消的 HTTP/1 adapter，避免一條串流影響同連線其他請求。

完整回歸另抓到既有日誌 spool 的兩個競態：失敗 future 先完成、恢復旗標後清除；以及通知完成後才釋放容量。呼叫端立刻再寫可能被誤拒絕，或在收到結果後仍看到未釋放的容量。現在先隔離舊連線、發布恢復狀態並釋放應釋放的容量，再通知呼叫端。已提交成功的記錄仍回報成功，未提交的失敗記錄不會混入後續交易。新增測試在 future 完成的同一刻觀察狀態與容量，避免只靠排程運氣重現；原本公開 append／下一筆寫入的測試也保留。

## 驗證與重現方式

預設啟用後的補驗證：不設定 `ECHO_HTTP_TRANSPORT` 執行 186 項 HTTP／原生資源與相容性測試，全部通過，SpotBugs 通過。另以新打包的 JAR 實際啟動，確認未指定 transport 時原生請求計數增加、狀態與 Unicode 本文正確；設定 `ECHO_HTTP_TRANSPORT=servlet` 時回應同樣正確且沒有原生請求計數。兩個既有觀測測試改為等待請求完成後的統計發布，避免客戶端先收到回應造成過早斷言。新 JAR 雜湊與檢查結果見 [default-native-check.json](performance/2026-10-16-native/default-native-check.json)；下列完整回歸與壓測保留改預設前的結果。

最終完整檢查：2,085 項測試中 2,079 項通過、6 項既有外部依賴測試跳過；另有 9 項 H2 遷移測試的有效結果，Error Prone 與 SpotBugs 通過。修正後的日誌／原生 HTTP 聚焦檢查有 63 項全部通過。完整回歸以 native 設定執行；HTTP/2、SSE 與自訂擴充依契約保留 Servlet 處理。

量測 jar SHA-256：`61a5cd4dc161eeecc74d7e851fad0d953376291645647cb7b5399eb5b4da9e8a`。相較 WebJars Lite 調整後的正式 Servlet jar，runtime library 集合沒有增加或減少；Brave bridge 只存在於測試依賴。

建置／測試使用 Java 17；獨立程序效能與持續負載使用 Java 21.0.12.1、1 GiB heap、G1、CPU 0–3。伺服器與負載工具共用四核心，不同負載各自執行，沒有同時編譯或錄製 JFR。

```sh
# 一般建置與預設路徑
./gradlew check bootJar -PtestForks=2 --no-daemon
# 使用 native 設定跑完整回歸，特殊請求依契約仍可走 Servlet
ECHO_HTTP_TRANSPORT=native ./gradlew check bootJar -PtestForks=2 --no-daemon

# 兩輪交錯比較：Echo → WireMock → WireMock → Echo
taskset -c 0-3 bash docs/performance/2026-10-16-native/compare.sh \
  /path/to/echo.jar /path/to/wiremock-standalone-3.13.2.jar /tmp/new-latency latency 2
taskset -c 0-3 bash docs/performance/2026-10-16-native/compare.sh \
  /path/to/echo.jar /path/to/wiremock-standalone-3.13.2.jar /tmp/new-matrix matrix 2
# 補測較大的 WireMock worker pool；每列取 32／128 中較好的平均吞吐
WM_ONLY=1 WM_BENCH_WORKERS=128 taskset -c 0-3 bash docs/performance/2026-10-16-native/compare.sh \
  /path/to/echo.jar /path/to/wiremock-standalone-3.13.2.jar /tmp/new-wiremock128 matrix 2
python3 docs/performance/2026-10-16-native/collect.py /tmp/new-latency /tmp/checked-latency

# 兩產品共同調整 G1 停頓目標，並在每輪延遲測試後補固定回應峰值測量
JAVA_TOOL_OPTIONS=-XX:MaxGCPauseMillis=20 LATENCY_INCLUDE_PEAK=1 \
  taskset -c 0-3 bash docs/performance/2026-10-16-native/compare.sh \
  /path/to/echo.jar /path/to/wiremock-standalone-3.13.2.jar /tmp/new-gc20 latency 2

# 開啟資料庫請求日誌，混合負載、斷線、超大本文與下游故障恢復
taskset -c 0-3 python3 docs/performance/2026-10-16-native/soak.py \
  /path/to/echo.jar /tmp/new-soak 10 60
```

比較需要 `wrk`、`wrk2`、Node、`jcmd`、curl；持續負載腳本需要 Python `aiohttp`。輸出必須使用新的目錄。兩產品的吞吐／固定到達率比較都關閉請求日誌，使用相同 JVM 與 heap；Echo 為 2 I/O、8 workers。吞吐矩陣測 WireMock 32／128 container workers，逐列取較佳平均值；固定到達率與 GC 補測使用 32 workers。所有 WireMock 測試都開啟 async response、32 async threads。

持續負載採每秒最多約 400 次的受控發送，逐筆核對狀態與本文，不用它宣稱最大吞吐。設定 native 單本文上限 1 MiB，正常回應最大為 12 KiB Unicode，另注入超過 1 MiB 的本文驗證 413。每輪負載停止後取樣資源並強制 GC，因此這些 GC 取樣不參與延遲比較。

## 吞吐：採用 WireMock 較佳工作池結果

相同工作負載、Echo 兩輪、WireMock 32／128 workers 各兩輪。共 **21,738,066 筆、78 個量測階段**，HTTP／socket 錯誤皆為 0。每列取 WireMock 兩種工作池設定中較高的平均吞吐。

| 情境 | Echo req/s | WireMock 較佳 req/s | WireMock workers | 比值 |
|---|---:|---:|---:|---:|
| 固定回應，併發 8 | 59,178 | 43,661 | 128 | 1.36× |
| 固定回應，併發 64 | 113,280 | 59,840 | 128 | 1.89× |
| 固定回應，併發 256 | 118,294 | 65,782 | 32 | 1.80× |
| 模板，併發 8 | 54,628 | 25,133 | 128 | 2.17× |
| 2,000 條路徑規則，併發 8 | 50,866 | 4,965 | 128 | 10.24× |
| 小 XML，併發 8 | 35,368 | 16,080 | 128 | 2.20× |
| 大 XML、條件在前，併發 8 | 16,422 | 2,429 | 128 | 6.76× |
| 大 XML、條件在尾，併發 8 | 4,129 | 1,337 | 128 | 3.09× |
| 256 KiB 回應，併發 64 | 14,527 | 2,410 | 128 | 6.03× |
| 快速下游轉送，併發 64 | 24,225 | 2,140 | 128 | 11.32× |
| 50 ms 下游轉送，併發 64 | 1,243 | 1,185 | 128 | 1.05× |
| 50 ms 延遲 Mock，併發 64 | 1,250 | 1,218 | 128 | 1.03× |
| 壓力後固定回應，併發 8 | 58,156 | 42,004 | 128 | 1.38× |

慢轉送使用 32 workers 時，WireMock 約 505 req/s；改成 128 後超過 1,180 req/s，因此該情境的實際優勢約 5%，不是 2.5 倍。刻意延遲 50 ms 的收益也小。規則與 XML 的差異包含共用 pipeline 的匹配方式，不能全歸因於 HTTP 入口。

兩輪 Echo 在全部負載結束後，轉送 active／leased／pending／buffered bytes 皆歸零，沒有容量拒絕或目標轉送失敗。原始資料見 [matrix](performance/2026-10-16-native/matrix/) 與 [wiremock128](performance/2026-10-16-native/wiremock128/)。

## 固定到達率延遲：預設 G1 停頓目標

ABBA、每個產品兩輪，每輪 20k／40k req/s 各 30 秒，64 連線。八個階段合計 **7,176,502 筆**，HTTP／socket 錯誤皆為 0。

| 目標 req/s | Echo 平均實際 req/s | WireMock 平均實際 req/s | Echo 平均 P99 ms | WireMock 平均 P99 ms | Echo P99.9 兩輪 ms | WireMock P99.9 兩輪 ms |
|---|---:|---:|---:|---:|---|---|
| 20,000 | 19,895 | 19,975 | 2.59 | 3.26 | 10.65、4.22 | 6.03、138.62 |
| 40,000 | 39,950 | 39,788 | 3.27 | 9.68 | 11.78、19.02 | 14.73、43.04 |

平均 P99 是每輪百分位的算術平均，不是合併樣本後的百分位。P99.9 列出每輪原值，保留 WireMock 138.62 ms 的離群輪次，也保留 Echo 第一輪 20k 較差的結果。40k 的 P99 兩輪都支持 Echo 較低；20k 的 P99.9 並非每輪勝出，不能宣稱任意流量下皆全面優於 WireMock。

原始檔及各輪結果見 [latency](performance/2026-10-16-native/latency/)。此批使用相同的 G1 預設停頓目標，沒有加 profiler；前後 cgroup 計數沒有新增 CPU quota throttling，仍不能單靠百分位結果判定尖峰的唯一原因。

## 未採用的 GC 調整

另外讓兩產品共同使用 `-XX:MaxGCPauseMillis=20`，相同 20k／40k 到達率再做兩輪 ABBA，並在每輪結束補測併發 64 的固定回應峰值。共 **10,581,046 筆、12 個階段**，HTTP／socket 錯誤皆為 0。

| 目標 req/s | Echo 平均 P99 ms | WireMock 平均 P99 ms | Echo P99.9 兩輪 ms | WireMock P99.9 兩輪 ms |
|---|---:|---:|---|---|
| 20,000 | 2.29 | 3.08 | 10.10、3.98 | 10.49、5.98 |
| 40,000 | 5.03 | 9.03 | 18.22、40.45 | 15.20、39.20 |

20k 的變化不大，Echo 40k 的 P99／P99.9 反而比原設定差，沒有支持將此參數加入預設的證據。補測峰值平均為 Echo 108,849、WireMock 59,889 req/s；它是在延遲測試後量測，與主矩陣的暖機順序不同，不能把兩張表的差額直接當成 GC 調整的吞吐成本。

**沒有變更產品的 JVM GC 預設，也不把 20 ms 參數列為通用建議。** 原始資料見 [gc20](performance/2026-10-16-native/gc20/)。停頓目標不是最大停頓時間保證；這組結果亦不足以單獨歸因所有尖峰。

## 開啟日誌的混合負載與恢復

十輪各 60 秒，另有暖機及五秒下游斷線注入；每輪之間暫停取樣。共核對 **234,206 筆回應**，涵蓋固定回應、模板、XML、Unicode 大回應、延遲、成功／錯誤轉送。另注入 80 次客戶端斷線、10 筆超大上傳，並在負載中更新規則 10 次、立即驗證新內容。

結果沒有非預期狀態或本文錯誤。下游斷線的 480 筆轉送依預期回 502；恢復後立即確認轉送成功。每輪結束的 native active／queued／buffered bytes，以及轉送 active／leased／pending／buffered response bytes 均歸零。

| 觀察值 | 結果 |
|---|---|
| 第 1–10 輪回收後 heap | 57.2–58.4 MiB |
| 第 1–10 輪執行緒 | 固定 75 條 |
| 檔案描述符 | 141–195，結束為 141，沒有持續累積 |
| 日誌 agent | 處理 234,276 筆，dropped = 0，結束 queue／in-flight = 0 |
| RSS | 第 1 輪約 1,137 MiB → 第 10 輪約 1,159 MiB |

日誌處理數包含已進入 pipeline、後來斷線的請求；超大本文在 pipeline 前拒絕。RSS 包含已提交 heap 與非 heap，不能等同於回收後仍存活的物件；此處保留其約 22 MiB 的增加，不把十多分鐘觀察解讀為長期零記憶體洩漏。負載發送上限約 400 req/s，此測試用於驗證正確性與資源恢復，不是開啟日誌時的最大吞吐比較。

原始資料見 [soak](performance/2026-10-16-native/soak/)。

## 證據與限制

原始結果、測試摘要、失敗原因與來源檔案 SHA-256 在 [2026-10-16-native](performance/2026-10-16-native/)。較早的 [JMS 與啟動數據](performance-hardening-2026-10-16.md)保留原本的 jar SHA 與範圍，不與本次 HTTP 數據混成一組。

前階段 20k req/s 的單輪 P99.9 尖峰曾達 19.57 ms。另一次獨立 JFR 取樣出現約 29.5 ms 的 GC 暫停，該輪 cgroup 沒有 CPU quota throttling；這是線索，不能據此認定前一輪尖峰的唯一原因。後續結論應以無 profiler 的重複量測判讀，不刪除離群輪次。

TLS 使用本機測試憑證與受信任的 client trust store；不是外部金融網路驗收。短期持續負載不能取代多日運行、實際資料庫與真實下游的部署驗收。HTTP 記憶體占用、啟動時間與所有負載的尾端延遲也不能單憑吞吐勝出就宣稱全面優於 WireMock。
