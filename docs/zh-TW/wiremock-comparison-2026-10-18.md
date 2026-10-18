# Echo / WireMock 效能、記憶體與穩定性對照

[English](../wiremock-comparison-2026-10-18.md)

較新產物的 HTTP 對照與 512 MiB 大訊息觀察，見[容量與 framework 檢視](capacity-and-framework-review-2026-10-18.md)；負載與紀錄保證不同，需分開解讀。

測量的 Echo 功能程式版本為 `f9a60fc82cc4200a6d66e159e344251c919f3714`，WireMock standalone **3.13.2**。所有測試在 Linux／JDK 17 執行，同一台主機的 CPU quota 為 4 核心。以下是特定合成負載的觀察，不是正式容量或全年不洩漏的保證。

後續檢查發現，`echo.jms.enabled=false` 雖停止 Echo 的 JMS 處理，Spring Boot 仍會啟動閒置的內建 Artemis broker。以下表格保留修正前的原始數字，包含這項額外成本。[後續修正](long-running-stability-2026-10-18.md#停用-jms-與內建-broker-啟動)另有驗證，不能把這些表格當成修正後產物的成績。

## HTTP 速度

雙方相同 G1、1 GiB heap、四條等價規則、HTTP/1.1 loopback。每情境暖機 5 秒、量測 10 秒，每產品／紀錄模式各兩個全新 JVM，以交替順序執行。每個收到的狀態與本文都驗證，沒有內容或 socket 錯誤。P99 為兩輪 P99 的平均，不是合併樣本的 P99。

### 請求紀錄關閉

| 情境 | Echo req/s | WireMock req/s | 吞吐比 | Echo P99 ms | WireMock P99 ms |
|---|---:|---:|---:|---:|---:|
| fixed-c16 | 72,058 | 56,452 | 1.28× | 8.060 | 5.101 |
| fixed-c64 | 118,557 | 67,186 | 1.76× | 3.721 | 5.954 |
| template-c16 | 78,864 | 30,790 | 2.56× | 1.976 | 5.454 |
| xml16k-c16 | 37,231 | 21,248 | 1.75× | 4.871 | 6.184 |
| forward16k-c16 | 12,095 | 9,304 | 1.30× | 6.784 | 5.654 |

Echo 在這五個情境的吞吐較高，但固定回應（c16）與轉發的 P99 沒有領先；不能把吞吐勝出當成所有指標都較好。只有四條規則，未包含 TLS、複雜模板或大量規則。

### 請求紀錄開啟、保留 10,000 筆

| 情境 | Echo req/s | WireMock req/s | 吞吐比 | Echo P99 ms | WireMock P99 ms |
|---|---:|---:|---:|---:|---:|
| fixed-c16 | 35,826 | 2,603 | 13.76× | 11.564 | 34.987 |
| fixed-c64 | 68,696 | 2,597 | 26.45× | 8.505 | 85.307 |
| template-c16 | 41,212 | 2,634 | 15.65× | 4.190 | 90.532 |
| xml16k-c16 | 16,266 | 2,658 | 6.12× | 6.490 | 30.657 |
| forward16k-c16 | 6,416 | 1,819 | 3.53× | 10.723 | 32.479 |

Echo 使用既有預設的非同步 SQLite 紀錄與 1,000/s 寫入上限；WireMock 為記憶體 journal、設定 `--max-request-journal-entries 10000`、關閉純文字 notifier。持久性與排隊政策不同。Echo 的 `dropped` 每輪約 226 萬；核對警告總數，可分出兩輪各 2,211,662／2,236,567 筆保留範圍淘汰，以及 46,362／28,912 筆 handoff 丟棄。過載時允許丟棄紀錄是預設的設計取捨，用來限制記憶體並優先維持回應，不代表回應內容錯誤。這組不能解讀為每筆完整落地的吞吐。

WireMock 此版本在 `AbstractRequestJournal.removeOldEntries()` 以 `getAllKeys().count()` 逐筆遍歷 `ConcurrentLinkedDeque`，是有上限 journal 額外成本的可確認來源；不是已量出的完整因果占比，也不能泛化成 WireMock 所有設定。

### 追加檢查：固定回應受暖機時間影響

另以修正 broker 啟動的 `cdf00cb` 與 WireMock 3.13.2 對照：同一主機、Java 17／G1／1 GiB heap、關閉紀錄、16 條連線，四個全新 JVM 依 Echo／WireMock／WireMock／Echo 執行。每輪先暖機 5 秒、量測 10 秒，再增加 30 秒流量，最後量測 10 秒。兩邊都開啟 GC／safepoint 紀錄；同一 JVM 前後階段沒有修改 HTTP 實作。

| 階段 | Echo req/s | WireMock req/s | Echo 各輪 P99 平均 ms | WireMock 各輪 P99 平均 ms |
|---|---:|---:|---:|---:|
| 累積約 5 秒流量後 | 76,343 | 52,457 | 7.880 | 5.292 |
| 累積約 45 秒流量後 | 75,018 | 56,649 | 1.666 | 4.420 |

兩輪的 P99 變化方向一致；所有收到的狀態與本文正確，socket 錯誤為零。Echo 前段各 10 秒的 GC 總暫停為 163.4／175.2 ms，後段為 21.3／20.0 ms。這支持暖機時間是先前 fixed-c16 數字的影響因素之一，還沒有拆開 JIT、GC 與 CPU 排程各自的成本。只測一種固定回應、每產品兩個 JVM，不能證明已完全暖機，也沒有解決轉發／JMS 的尾端延遲。原始表格保留；追加原始資料放在 Git 外的 `echo-results-review-20261018/tail-latency`。

## HTTP 記憶體與 30 分鐘穩定性

兩個被比較的程序都只有 HTTP 流量，JMS 處理與備份均停用；Echo 仍啟動了上述閒置內建 broker，WireMock 則沒有 broker。雙方各 100 req/s、四條連線、五條等價規則，固定、變動 query 模板、16 KiB XML 條件、16 KiB 真實轉發、10 ms 延遲各 20%。雙方都使用 JDK 17／G1／1 GiB heap／NMT summary，保留 10,000 筆紀錄。暖機 120 秒後，六段各五分鐘，段間停兩邊流量、閒置 15 秒後診斷 GC。

兩個被比較的程序同機並行；大部分時段另有獨立 100 req/s Echo HTTP＋JMS 驗收，該驗收在本對照最後一段結束前已完成；這是固定低於容量的穩定性觀察，與最高速度測試沒有重疊。

| 指標 | Echo | WireMock |
|---|---:|---:|
| 正式核對筆數 | 180000 | 180000 |
| GC 後 heap：基準 → 最後 MiB | 58.2 → 50.7 | 128.9 → 128.6 |
| 閒置後 RSS：基準 → 最後 MiB | 1182.7 → 1233.2 | 710.7 → 1204.5 |
| 程序 RSS 最高 MiB | 1233.2 | 1204.5 |
| OS 執行緒：基準 → 最後 | 108 → 109 | 38 → 35 |
| FD：基準 → 最後 | 48 → 48 | 15 → 15 |
| 自然 GC 次數 | 72 | 66 |
| 自然 GC 總暫停 ms | 522.3 | 1519.311 |
| 自然 GC 最長暫停 ms | 38.017 | 123.851 |

**資源門檻結果：** 未全數通過；以下保留各項原始判定，不能據此直接斷言服務崩潰或洩漏。

| 產品 | GC 後 heap | NMT committed | RSS | OS 執行緒 | FD |
|---|---|---|---|---|---|
| echo | 門檻內 | 門檻內 | 門檻內 | 門檻內 | 門檻內 |
| wiremock | 門檻內 | 門檻內 | 標記成長 | 門檻內 | 門檻內 |

兩邊各完成 180,000 筆正式請求及 7 輪改寫回應核對，無回應內容錯誤、OOM 或非預期退出。Echo 五項資源門檻全數通過；WireMock 僅 RSS 被標記。WireMock 第一個五分鐘的 RSS 增加 484.9 MiB，之後二十五分鐘僅增加 8.9 MiB，GC 後 heap 與 NMT committed 沒有同步成長。這較符合 JVM 保留頁面逐步駐留的現象，不能直接判定洩漏，也不把原始 RSS 旗標改成通過。

GC 後 heap 與 RSS 是不同指標。RSS 也包括已駐留的 JVM heap 頁面、類別、JIT、執行緒與 native 分配，GC 不一定立即歸還給 OS。完整七個檢查點與逐次 GC 原始檔保留在測量產物；自然 GC 統計包含啟動與暖機，與七次閒置診斷 GC 分開計數。最早一次對照因暖機排程門檻而未進正式流量，原始失敗保留；重跑只把同一速率門檻套在正式流量，並完整保存暖機延遲。

對照工具在停止 JVM 前先刪除暫存資料目錄，讓 Echo 在量測完成後的收尾階段出現 SQLite spool 警告。這是測試工具的生命週期缺陷，不能視為負載期間的儲存故障。原本只掃描 `ERROR`／OOM，沒有納入這些 `WARN`；後續須先停 JVM 再刪資料，並檢查警告。本組不作為正常關閉時紀錄排空的驗證。

## 獨立 HTTP＋JMS 維運驗收

Echo 共完成 180,000 筆／1,800 秒，HTTP 與 JMS 各 90,000；HTTP 75% 模擬／25% 轉發，JMS 50% 模擬／50% 真實 Artemis 轉發。整體驗收：通過。管理操作 66 輪、1716 次 HTTP/API 檢查、132 次真實 JMS 改寫核對。備份 138 次、鎖衝突 0 次。

這組使用 384 MiB heap、JMS concurrency 2–4、1,000 筆保留量與 60 秒 GC 事件期限以加速觀察回收，不與上面兩個 HTTP-only 程序直接比較記憶體。每五分鐘停流量診斷；不能稱為不中斷的三十分鐘壓測。

JMS 的獨立同步紀錄模式，修正前／後模擬回覆約 904／892 req/s（-1.3%），真實 Artemis 轉發約 478／492 req/s（+2.8%）；每種兩輪，小差異不能判定確定加速或退步。原始精確筆數核對因 wrk 結束時的在途請求失敗，保留原始結果，另核對 DB 多出的 24–30 筆在兩次 c16 的 32 筆上限內，processed 與 DB 相符且 dropped=0。

WireMock 無 JMS 等價項目；沒有 TIBCO EMS 實機驗證，不推論 EMS 的吞吐、ACK 或 failover。兩項修正、代價與回歸測試見[穩定性說明](long-running-stability-2026-10-18.md)。

## 驗證與產物

JDK 17 完整檢查共 2,188 項、0 失敗、6 略過，SpotBugs 通過。功能版本的 [CI](https://github.com/boloagegit/echo-mock-server/actions/runs/39103053156) 包含 Linux JDK 17／21、Windows JDK 17（含空白路徑）與容器停止／重啟，全部通過。後續文件更新不改變測量產物。

原始產物保存在原始碼庫外：

- `runtime-fixes-wiremock-20261018`: 設定、交替 HTTP 測速、摘要與 journal bytecode 證據。
- `runtime-fixes-http-stability-retry-20261018`: 相同 HTTP 情境、逐程序採樣、JVM 檢查點、逐次 GC 與報告。
- `runtime-fixes-30min-20261018`: HTTP／JMS／管理／備份混合觀察、GC 歷史與報告。

各目錄包含隔離測試工具與凍結的原始碼／JAR 識別；觀察工具不修改產品預設。
