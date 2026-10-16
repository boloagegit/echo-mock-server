# HTTP I/O 執行緒調校

Echo 保留 Undertow 依可見 CPU 數決定 I/O 執行緒的預設行為。不要把「I/O 執行緒越多越快」或「全部設成 1 最快」當成通則。

在本次 4-core CPU quota、32 個 worker threads 的測試中，單一 I/O 降低了部分連線狀態重試成本，小型回應、模板與快速轉發有改善；完整 XML 解析及高併發的吞吐則有取捨。原始資料與限制見 [2026-10-15 調查](performance-investigation-2026-10-15.md)。

後續 44 個負載階段沒有回報 HTTP / socket error，轉發使用中資源均可歸零；58 個既有 HTTP／SSE 相關測試通過。另一次開啟記錄的短高壓測試也完成恢復。這些結果支持把 1 作為可測量的部署選項，不構成不同硬體與長時間負載的保證。

## 設定

在既有啟動方式中加入環境變數，重新啟動服務後生效：

```bash
export SERVER_UNDERTOW_THREADS_IO=1
# 接著執行原本的 Echo 啟動命令。
```

Docker Compose 可在既有 Echo service 的 `environment` 加入：

```yaml
SERVER_UNDERTOW_THREADS_IO: "1"
```

也可以在啟動參數使用 `--server.undertow.threads.io=1`。這是 Spring Boot 已支援的設定，沒有新增專用 profile，也不需要調整資料庫 profile。

這個值控制 **入口 Undertow 的 I/O 執行緒**，不會把 worker threads 從目前預設 32 改為 1，也不會更改 outbound Reactor Netty 的執行緒或全域轉發連線上限。`/api/admin/http-target-connections/metrics` 的 `ioThreads` 是 outbound 的數值，不能用它判定 Undertow 的設定是否生效。

## 選擇與驗證

- 小型 mock 回應、低至中併發，或希望降低尾延遲：可先對照測量 `1`，同時看吞吐、p99 與 CPU。
- 高併發、需要完整解析大型 body，或混合負載：保留自動值作基準，不只用固定 JSON 的結果選設定。
- TLS、大量長連線、不同 CPU quota 與跨主機網路：本次沒有量出適用的最佳值，應以實際流量測量。

比較時只改 I/O 執行緒數，保持 JVM、worker threads、規則、請求記錄模式與下游條件一致；暖機後交錯測量兩種設定。轉發測試結束後，也要檢查 `activeForwards`、`pool.leased`、`pool.pending` 與 `pool.bufferedResponseBytes` 是否歸零。

請求記錄開啟時還要看寫入佇列與 dropped 計數；HTTP 變快不代表記錄儲存也變快。需要完整留存的情境，應另外評估嚴格寫入模式與儲存容量。

## 還原

移除環境變數或啟動參數，重新啟動服務即可恢復 CPU-derived 預設。Shell 啟動方式：

```bash
unset SERVER_UNDERTOW_THREADS_IO
# 接著執行原本的 Echo 啟動命令。
```

若同時在 YAML、環境變數與 CLI 設了值，必須移除實際生效的覆寫；CLI 參數的優先順序高於環境變數。
