# 現行 HTTP adapter 的驗證資料

閱讀入口：[正式整合與驗證](../../native-http-validation-2026-10-16.md)；設定：[HTTP 加速路徑](../../native-http.md)。

`compare.sh` 與 `soak.py` 是 Linux 部署量測工具，使用 cgroup v2／`taskset` 或 `/proc`；Windows 開發可直接使用 Gradle wrapper，不需執行這兩個工具。後續新增的平台前置檢查不改變工作負載；`script-sha256.json` 保留原始量測當時的腳本雜湊。

- `default-native-check.json`：後續將 native 改為預設後的 186 項聚焦回歸全部通過、SpotBugs、新 JAR 雜湊，以及打包檔的預設啟用／Servlet 切換實測；沒有重新壓測。
- `release-check.json`：效能量測版本的完整回歸，2,085 項（2,079 通過、6 跳過），以及當時量測 jar SHA-256。
- `verification.json`：同一 jar 的 98 個量測階段、39,495,614 筆請求，HTTP／socket 錯誤皆為 0。
- `source-sha256.json`：相對基底工作區新增／修改的 29 個建置、程式與測試檔案；`script-sha256.json` 為重跑腳本。
- `artifact.json`：runtime library 比對；沒有新增比較伺服器或測試用 tracing bridge 到發行 jar。
- `matrix/`、`wiremock128/`、`throughput-comparison.json`：兩種 WireMock 工作池與逐列取較佳值的吞吐比較。
- `latency/`：原 G1 設定、固定到達率 ABBA；`gc20/`：兩產品共同調整 G1 停頓目標的補測。20 ms 調整未採用。
- `soak/`：十輪各 60 秒混合負載，逐筆回應核對、故障注入與每輪資源快照。

較早的 `full-check.json`、`native-regression-before-expect.json` 是整合過程的檢查；最終結果以 `release-check.json` 為準。`focused-check.json` 是修正後 63 項聚焦檢查。兩份 `failure-before-*-fix.json` 保留回歸抓到的日誌恢復／容量發布競態；新測試在通知當下觀察狀態與容量。

`header-parity-test-correction.json` 保留測試假設的修正：原測試將 header 中的合法 whitespace 一律預期為 400，但容器處理後實際回 200；最終改為與強制 Servlet 處理的同一請求比較狀態及本文。管理權限與特殊路徑測試仍保留。

主矩陣沿用歷史工作負載。後續僅加入預設關閉的 `LATENCY_INCLUDE_PEAK` 選項，供 GC 補測在延遲量測後加測峰值；一般矩陣與原到達率流程不變。各目錄保留 JVM、server args、CPU affinity、暖機、原始 wrk 輸出及作業訊息；大型 server log／JFR／jar／SQLite 不放入版本庫。
