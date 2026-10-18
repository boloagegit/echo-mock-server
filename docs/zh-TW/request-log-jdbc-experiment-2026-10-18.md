# SQLite 請求紀錄 JDBC 寫入實驗

[English](../request-log-jdbc-experiment-2026-10-18.md)

後續：[512 MiB 容量、大訊息、寫入限速與 framework 成本](capacity-and-framework-review-2026-10-18.md)。

本輪只比較暫存紀錄搬進主資料庫的寫入成本。規則、回應、系統設定與紀錄查詢繼續使用 Spring Data JPA，既有 SQLite spool 不變。

1. **原本問題**：`RequestLogBatchWriter` 使用 JPA `saveAll` 搭配自增 ID；傳入一整批實體，不代表底層一定使用 JDBC 批次，還有 Hibernate 管理實體的成本。
2. **怎麼改善**：SQLite 新增寫入路徑，每批只準備一份 INSERT，再用 JDBC 批次執行。沿用當前 Hibernate 交易連線、欄位映射與型別繫結，重用原有截短／特殊字元處理與預設時間邏輯；紀錄、處理進度與保留清理仍在同一個 Spring 交易。其他資料庫與已有 ID 的資料保留 JPA 行為。輸入物件不回填生成的 ID；內部 spool consumer 不使用這些 ID。
3. **實測效果**：四個交錯執行的 JVM 全部通過內容、筆數與進度核對。本文各 16 KiB、每批 50 筆時，平均寫入吞吐提高 47.2%、每筆 CPU 成本降低 30.6%、呼叫執行緒配置量降低 19.1%；同樣本文、每批 1 筆也提高 17.6%。測試涵蓋與 JPA 逐欄相同、非預設 JDBC 時區與時間篩選、null／預設時間、批次部分失敗、紀錄送出後進度失敗、外層回滾、保留清理、既有 ID 更新及非 SQLite 回退。
4. **代價與限制**：新增一個依賴 Hibernate 映射 SPI 的小型 adapter，升級 Hibernate 時須驗證。寫入成本探針不能當成 HTTP／JMS 整體吞吐。原有非同步丟棄與寫入速率政策不變；JMS ACK、交易、重試與回覆隔離不變。沒有實際授權 TIBCO EMS 驗證。JDBC 出錯仍由原有流程回滾與恢復，不會偷偷改用 JPA 重寫同批。打包後 SQLite 預設啟用；設定 `ECHO_REQUEST_LOG_JDBC_BATCH_ENABLED=false` 並重啟可回復 JPA。

## 比較結果

每種策略各兩次觀察的平均，保留資料與 schema 相同：

| 本文各欄大小 | 每批筆數 | JPA 筆／秒 | JDBC 筆／秒 | 吞吐變化 | 每筆 CPU 降低 | 每筆配置量降低 |
|---|---:|---:|---:|---:|---:|---:|
| 0 | 1 | 7,579 | 9,883 | +30.4% | 22.7% | 33.0% |
| 0 | 50 | 23,906 | 59,456 | +148.7% | 59.4% | 74.9% |
| 16 KiB | 1 | 5,213 | 6,132 | +17.6% | 15.2% | 14.9% |
| 16 KiB | 50 | 9,863 | 14,522 | +47.2% | 30.6% | 19.1% |

本文各 16 KiB 時，各輪整批 P99 平均：每批 1 筆為 0.687 → 0.684 ms，每批 50 筆為 21.201 → 18.678 ms。這是整批呼叫時間，不是 JMS 回覆延遲，也不是合併樣本的百分位。產品預設每秒寫入 1,000 筆的上限保留；探針取消限速所得的數字不會提高這個設定值。每種策略只有兩次觀察，不能推論統計顯著性或所有主機的效能。

## 比較方法

Linux／JDK 17、G1、Heap 512 MiB，SQLite WAL／`synchronous=NORMAL`，相同 schema／索引，保留 10,000 筆。四個新 JVM 依 JPA／JDBC／JDBC／JPA 執行；每組至少暖機 8 秒及 10,000 筆，正式量測 15 秒。每批 1／50 筆，request／response body 各為 0／16 KiB。探針沒有套用產品的寫入速率上限，也沒有並行建置或其他負載。每組結束核對保留內容、筆數與進度；CPU 包含整個程序，配置量只計呼叫執行緒，兩者均包含建立輸入資料的成本。

```bash
./gradlew requestLogWriteCost \
  -PrequestLogCostOutput=build/observations/writer-jpa \
  -PrequestLogCostMode=jpa
./gradlew requestLogWriteCost \
  -PrequestLogCostOutput=build/observations/writer-jdbc \
  -PrequestLogCostMode=jdbc
```

Windows 改用 `gradlew.bat` 與 PowerShell 引數，例如 `'-PrequestLogCostOutput=build/observations/writer-jdbc'`。輸出目錄須尚未存在。原始日誌與結果留在 Git 外的 `jdbc-request-log-experiment-20261018/comparison`，manifest 保存順序、來源雜湊、各輪數字與核對結果。同一工作目錄的 `probe-at-measurement.java` 保存當時的探針；後續補啟動策略確認、Windows 日誌路徑、初始化同步與複合映射回退，一般欄位的計時寫入路徑未改。暫時資料庫在程式停止前核對，關閉後刪除。

## 應用回歸

實作來源為 `ab7b292`。本機 JDK 17 `check`／SpotBugs 通過：2,209 項測試、0 失敗、6 項略過。[跨平台 CI](https://github.com/boloagegit/echo-mock-server/actions/runs/39118743651) 全部通過 Linux JDK 17／21、Windows JDK 17（含空白路徑），以及容器探針、停止與重建。

預設啟用的凍結產物，在 6 × 15 秒暖機後完成 **600.050 秒不間斷、每秒合計 200 筆**負載。正式 120,000 筆回應全部正確：HTTP 60,000 筆（固定／模板／轉發／延遲），JMS 60,000 筆（模擬／真實 Artemis 轉發）；暖機另計 18,000 筆。ERROR、OOM、記錄丟棄／未確認寫入、儲存重試均為 0。結束後佇列與緩衝排空，下游沒有殘留暫時 Queue。

七項成長門檻全部通過。負載前後視窗差為：自然 GC 後 Heap +0.78 MiB、NMT committed +7.06 MiB、direct +0.016 MiB、non-heap +6.73 MiB、JVM 執行緒 +2.5、RSS +9.23 MiB、FD 不變。非整數筆數是視窗中位數，邊界分鐘可能不滿整分鐘。負載前／排空快照的 RSS 為 895.9 → 913.5 MiB，自然 GC 後 Heap 為 109.99 → 111.48 MiB；通過不代表完全沒有增加。

全程未要求 GC，正式負載觀察 210 次自然 GC，含暖機共封存 248 筆，GC 歷史過期清除 232 筆。含啟動、暖機與排空，備份成功 51 次、失敗 0，清理 48 份，最後保留 3 份／約 53.5 MiB。正式停滯上傳恢復 8 條，暖機另有 48 條。

正式 HTTP 固定／模板／轉發／延遲 P99 分別為 3.086／2.959／5.870／14.221 ms，JMS 模擬／轉發為 10.822／17.383 ms。這是固定流量下的功能與穩定性回歸，不能解讀成 JMS 整體加速 47%；10 分鐘與 Young GC 也無法證明永久無洩漏。已檢視既有啟動／broker WARN 類別，沒有 spool 故障或未預期的執行期錯誤。詳見 [觀察方法與門檻](gc-observability.md)。

凍結應用產物 SHA-256：
`6cc5334607b3ca04d45003620530fc08b1c2b3795864ec05b8fb948150a7c87a`；
負載 fixture 為 `b16759299ed35f361ac0965f494318cee13988cdc85a2d6ce0142845dd14eeee`。

驗收原始資料位於 Git 外的 `jdbc-request-log-experiment-20261018/continuous-10min`。
