# Echo 文件

[← 回到專案首頁](../../README_zh-TW.md) · [English](../README.md)

## 入門

| 文件 | 內容 |
|------|------|
| [安裝與部署](install.md) | 啟動 JAR、Windows、Docker、資料庫 Profile、從 H2 遷移、多實例部署，以及如何把流量導進 Echo（直接呼叫、Nginx、對外 HTTPS） |
| [設定與認證](configuration.md) | `application.yml` 主要設定、認證模式、權限與內建帳號 |
| [規則與比對](rules.md) | 優先順序、標籤、HTTP 與 JMS 規則、條件語法、`matchSpec`、狀態機場景與回應模板 |
| [JMS](jms.md) | 內建 broker、轉發到 TIBCO EMS 或 Artemis、從共用 broker 接收 |
| [REST API](api.md) | 管理介面背後的主要 API |

## 功能細節（英文）

| 文件 | 內容 |
|------|------|
| [SSE 模擬](../sse-mock.md) | Server-Sent Events 的契約、事件序列與播放方式 |
| [資源監控](../resource-monitoring.md) | 設定頁的資源快照與 JMS 政策診斷 |
| [GC 紀錄與記憶體觀察](gc-observability.md) | 每次 GC 結果、容量上限與可重複的 HTTP／JMS 負載／閒置測試 |
| [交易診斷](../transaction-diagnostics.md) | 用來追難以重現問題的有限交易診斷 |
| [SQLite 復原](../sqlite-recovery.md) | Echo 啟動時如何檢查與復原 SQLite |

## 效能與驗證

| 文件 | 內容 |
|------|------|
| [效能總覽](../performance.md) | 與 WireMock 比較、請求記錄模式、已知瓶頸與重跑方法 |
| [HTTP 加速](../native-http.md) | 預設原生入口、容量上限、相容性與切回方式 |
| [原生 HTTP 驗證](../native-http-validation-2026-10-16.md) | 現行比較、延遲、生命週期檢查與證據 |
| [JMS 效能與正確性](../jms-performance-and-correctness.md) | Session 重用、回覆隔離與交易行為 |
| [HTTP I/O 調校](../http-io-tuning.md) | Undertow I/O 執行緒設定，以及什麼情況下調整才有幫助 |
| [效能與啟動補強](../performance-hardening-2026-10-16.md) | JMS Session 重用、啟動縮短 15%，以及前階段 HTTP 實驗 |
| [原生 HTTP 實驗](../native-http-performance-2026-10-15.md) | 2026-10-15 原型與 WireMock 的比較 |
| [HTTP 效能調查](../performance-investigation-2026-10-15.md) | 2026-10-15 的 CPU 取樣、I/O 執行緒與高壓恢復 |
| [容器韌性驗證](../container-resilience-validation.md)（英文） | JMS XML 壓力、重啟與儲存空間測試 |
| [容器長時間驗證](../container-endurance-validation.md)（英文） | 8 小時與 24 小時長時間測試 |
| [封存](../archive/README.md) | 較早的效能量測與工程紀錄 |

## 專案

| 文件 | 內容 |
|------|------|
| [Roadmap](../ROADMAP.md) | 功能狀態與規劃 |
| [匹配引擎擴充設計](../design/engine-extensibility.md) | 匹配引擎與協定的設計 |
| [參與貢獻](../../CONTRIBUTING.md)（英文） | 建置、測試與 PR 流程 |
| [版本變更](../../CHANGELOG.md)（英文） | 每個版本的變更 |
| [腳本說明](../../scripts/README.md) | 效能、遷移與驗證腳本 |
