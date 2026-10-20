# HTTP 加速路徑

一般發行檔預設啟用 Undertow 原生 HTTP/1.1 adapter（`echo.http.transport=native`）；不需要額外建置或啟動參數，也不包含 Jetty/Tomcat 比較用依賴。

```sh
java -jar echo-server.jar
```

原生路徑預設限制單筆 request／response 為 10 MiB、進行中請求為 512 筆、估計緩衝容量為 128 MiB，皆可調整，詳見下方容量設定。設定 `ECHO_HTTP_TRANSPORT=servlet` 後重啟即可切回原入口；資料與規則格式相同。既有部署若已明確設定 `servlet`，會繼續使用該設定。

## 共用的行為

Windows 開發與 Linux 部署使用相同的預設加速路徑；這裡的 `native` 指直接使用 Undertow API，不代表 Linux 專用程式，也不要求 WSL。Windows 開發與測試使用 `.\gradlew.bat dev`、`.\gradlew.bat check`。

PowerShell 切回 Servlet 的方式如下，設定僅作用於目前 shell 及其啟動的程序：

```powershell
$env:ECHO_HTTP_TRANSPORT = 'servlet'
.\gradlew.bat bootRun
# 停止 Echo 後，移除覆寫即可在下次啟動恢復預設加速。
Remove-Item Env:ECHO_HTTP_TRANSPORT -ErrorAction SilentlyContinue
```

原生路徑共用 `HttpMockPipeline`，包含規則快照、模板、JSON/XML 比對、情境狀態、HTTP 轉送、診斷與請求日誌。CPU／資料庫操作和回應編碼放在有上限的 worker pool；網路讀寫與延遲回應不占用 Servlet worker 等待。

`http.server.requests` 使用 Micrometer Observation，保留 method、uri、status、exception、outcome 標籤。設定 tracing bridge 時，標準 receiver tracing handler 能延續傳入的 trace parent；工作執行緒會開啟並關閉對應 scope。這不代表新增了下游 HTTP client span 或 exporter。預設發行檔沒有加入 tracing exporter。

TLS、壓縮與 graceful shutdown 使用 Spring Boot 原本的 Undertow 設定。正常關機會等待已接受的請求，並拒絕後續新請求；超過應用設定的關機期限仍可強制關閉。

## 相容性

以下請求仍交給 Servlet：HTTP/2、Upgrade、Expect（含 100-continue 握手）、HEAD／OPTIONS、SSE、表單、multipart、含編碼或特殊路徑的請求、Forwarded／X-Forwarded 標頭，以及不合法的媒體型別。HTTP/2 的串流取消仍由既有 Servlet 容器處理。

若偵測到額外的 Servlet Filter、MVC configurer、ControllerAdvice、Servlet observation convention、安全設定或非預設 context path／servlet path／編碼／open-in-view，整個 mock 路徑會保留 Servlet 處理，並記錄一次原因。允許同名 bean 覆寫時也保留 Servlet，避免替換既有安全設定後仍被原生路徑略過。內建框架元件使用明確清單；框架升級若增加未知元件，會先退回 Servlet。整合測試也檢查一般請求確實進入原生路徑，避免無聲退回造成效能退步。

已驗證受信任憑證、TLS 1.2／1.3、gzip 與 HTTP/2。測試中 Java client 必須同時提供 TLS 1.2／1.3 的 cipher offer 才能與目前 Undertow 協商 h2，實際連線仍為 TLS 1.3；僅提供 TLS 1.3 的該 client 會降回 HTTP/1.1。沒有關閉憑證或主機名稱驗證來迴避此差異。

## 容量設定

| 設定 | 預設 | 用途 |
|---|---:|---|
| `echo.http.native.workers` | 繼承 `server.undertow.threads.worker`（預設 32） | pipeline 與編碼執行緒 |
| `echo.http.native.max-requests` | 512 | 含延遲與轉送在內的進行中請求上限 |
| `echo.http.native.max-body-bytes` | 10485760 | 單筆 request／response 的編碼 bytes 上限 |
| `echo.http.native.max-buffered-bytes` | 134217728 | adapter 為讀取、解碼與編碼保留的估計總容量 |
| `echo.http.request-timeout-ms` | 40000 | 從接收請求至回應傳送完成的總期限，包含上傳、排隊、pipeline 與延遲 |

容量不足回 503，request 過大回 413，response 過大回 502，非同步回應逾時回 504。尚未讀完的 HTTP/1 request 拒絕後關閉連線，不為了重用連線而等待惡意或卡住的上傳。Servlet fallback 使用原本的 Servlet／轉送限制，不共用原生 admission 計數。

本文使用 Undertow 非阻塞接收：等待上傳資料時不占用應用工作執行緒。期限使用該連線的
I/O 執行緒計時，並持續到回應送完；客戶端停止讀取，也會釋放名額與緩衝。尚未送出回應且
本文已完整收到時，逾時回 504；本文未收完或回應已送出一部分時，逾時關閉連線。
過期排隊工作會移出佇列。已開始的 CPU／資料庫操作無法安全地強制中斷，其請求名額與已保留的本文額度會繼續占用，直到工作實際退出；非同步轉送完成後的 pipeline／listener 處理也納入相同計帳。取消與期限檢查會阻止已觀察到取消後的後續階段，已提交的紀錄或情境狀態不回滾。
極慢的一般下載須調整總期限；明確要求 SSE 的請求仍走原有 Servlet 路徑。

資源監控提供 `nativeWorkerActive` 與 `nativeWorkerCapacity`，用來確認實際忙碌的工作執行緒；`nativeDrainingRequests` 表示連線已結束、仍有工作尚未退出的請求數。這些請求仍包含在 `nativeActive` 與已保留的緩衝用量中；非同步完成處理也可能占用額度，即使 native worker 已空閒。

轉送回應的解碼儲存量在讀取前逐步預留；完成解碼後，字串的估計額度會保留到排隊與送出結束。這補上了下游讀完、worker 尚未處理回應時的計算缺口，因此大型回應在容量不足時可能較早收到 503。超過 256 Ki 個 UTF-16 code unit 且 UTF-8 不膨脹的一般回應，使用最多八個 32 KiB bytes 緩衝批次送出，另有預檢與 chars 緩衝，估計預留共約 324 KiB；送完上一組才重用。非 ASCII 與小回應保留 JDK 較快的一次編碼路徑，配置前會保留暫存陣列的保守額度，完成後縮到實際送出大小。兩條路徑都在送出標頭前確認完整 UTF-8 長度與上限。

緩衝容量是 adapter 的保守預留，不是整個 JVM heap 的硬上限：共用規則、模板產生中的字串、日誌、TLS、資料庫與下游 client 仍有各自的記憶體需求。這個逾時也不是對同步 CPU／資料庫工作的強制中斷。

可觀察的 metrics：`echo.http.native.requests`、`echo.http.native.active`、`echo.http.native.buffered.bytes`、`echo.http.native.queued`。請求完成、斷線、逾時及容量拒絕皆有釋放驗證；單一清理 callback 失敗不能阻止其他 callback 釋放資源。

效能設定必須依部署量測。四核心比較使用 2 個 I/O、8 個 worker；這不是所有機器的通用最佳值。現行驗證見 [正式整合與驗證](native-http-validation-2026-10-16.md)，比較與持續負載腳本位於 [scripts/bench/2026-10-16-native](../scripts/bench/2026-10-16-native/)，歷史結果見 [效能與穩定性報告](performance-hardening-2026-10-16.md)。

上述比較腳本使用 Linux 的 `taskset` 與 cgroup v2，持續負載腳本讀取 `/proc`；請在 Linux 部署測試機執行。Windows 用來驗證開發與功能，Linux 的吞吐、延遲與資源量測才作為部署效能依據。
