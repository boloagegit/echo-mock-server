# Echo 效能總覽

本文件彙整目前版本的效能量測、與 WireMock 的比較、版本演進，以及重跑方法。
數字只代表文中的機器與情境，不是通用的產品保證；比較時務必在同一台機器、同一組設定下重跑。
較早的量測保留在[歷史結果](#歷史結果)，並註明當時的版本與環境。

目前可用：[原生 HTTP/1.1 加速路徑](native-http.md)，一般發行檔預設啟用，包含 TLS、觀測、容量與相容性保護；可設定 `ECHO_HTTP_TRANSPORT=servlet` 後重啟切回原入口。[HTTP／JMS 與啟動補強量測](performance-hardening-2026-10-16.md)包含 JMS 資源重用、啟動縮短 15% 的比較，以及前階段 HTTP 實驗數據。

現行建置的完整測試、交錯量測與持續負載證據見 [原生 HTTP 正式整合與驗證](native-http-validation-2026-10-16.md)。

架構實驗起點：[2026-10-15 原生 HTTP 入口與 WireMock 比較](native-http-performance-2026-10-15.md)，包含三種 HTTP 伺服器、專用 mock 入口、固定到達率延遲及壓力恢復。該頁描述當時的獨立建置，現行設定請依上方使用文件。

JMS 的資源重用、容量策略實測取捨、交易與回覆隔離契約見 [JMS 效能與正確性](jms-performance-and-correctness.md)。HTTP 與 JMS 的成績分開判讀。

前階段調查：[2026-10-15 HTTP 效能與恢復調查](performance-investigation-2026-10-15.md)，使用 `8ee8dcb` 重測，包含 Undertow CPU 取樣、I/O 執行緒對照與開啟記錄的高壓恢復結果。環境不同，請勿與下方舊數字直接計算版本提升幅度。

## 重點

- **與 WireMock 比較（2026-10-14）**
  - 兩邊都保留最近 10,000 筆請求紀錄時，Echo 快 3.5～15 倍。WireMock 設定紀錄上限後，每個請求都會數一遍整串紀錄，這是它在這組設定下慢的主因。
  - 兩邊都不記錄請求時，簡單請求（固定 JSON、模板、小 XML）WireMock 快 1.5～4.4 倍；100 KB XML 時 Echo 快 1.8～3.2 倍（取決於條件欄位在 XML 中的位置），2,000 條規則時 Echo 快 2.9 倍。
  - WireMock 預設的請求紀錄沒有上限，持續壓測時 heap 會被紀錄佔滿，處理量從每秒 1 萬多筆一路掉到個位數。
- **請求記錄不等寫入**：模擬回應不再等請求記錄寫進硬碟，簡單請求快 3～7 倍；需要時可切回嚴格模式。
- **改規則不卡請求**：規則變更時在背景重建規則清單，模擬請求不必等待。
- **大量規則不再拖慢請求**：精確路徑的規則直接查表，不再逐條比對。同一主機有 2,000 條 GET 規則時，簡單 GET 請求快約 30%，與只有 4 條規則時差不多（見[同主機大量規則](#同主機大量規則的路徑比對已改善)）。上面與 WireMock 的比較是在這項改善之前量的。

## Echo 與 WireMock 比較（2026-10-14）

### 測試方式

| 項目 | 值 |
| --- | --- |
| 機器 | 4 vCPU（Intel Xeon 2.10 GHz）、15 GiB 記憶體、Linux 6.18 |
| JVM | OpenJDK 21.0.12，兩邊都用 `-Xms1g -Xmx1g -XX:+UseG1GC` |
| Echo | [PR #63](https://github.com/boloagegit/echo-mock-server/pull/63)（請求記錄不等寫入）審查中的版本，SQLite；合併後的版本是 `186843c`，之後只多了請求記錄上限計算與日誌的修正，沒有重新量測 |
| WireMock | 3.13.2 standalone（目前最新穩定版），預設執行緒設定 |
| 壓測工具 | wrk 4.1.0，與伺服器在同一台機器 |
| 回合 | 每組設定 2 輪，Echo 與 WireMock 輪流執行，表中為 2 輪的中位數（即平均） |

每輪的步驟：
1. 啟動伺服器。
2. 載入相同的規則：4 條情境規則，加上 2,000 條 `/item/{n}` 路徑規則，全部在同一主機。
3. 對每個情境送一次請求，記錄回應。這次全部回傳 200 與預期內容；公開的腳本在回應不對時會中止該輪。
4. 暖機 25 秒。
5. 每個情境各壓 10 秒。

規則對照：
- XPath 條件：Echo 寫成 `//Order/Type=VIP`；WireMock 寫成 `matchesXPath: //Order/Type[text()='VIP']`。
- 模板：兩邊都是 `{{request.query.id}}`。
- 兩個 XML 的 `<Type>` 都是第二個元素（見下方「條件欄位的位置」）。

比較三組設定：

| 組別 | Echo | WireMock |
| --- | --- | --- |
| 都記錄請求 | 預設（請求記錄開，保留最新 10,000 筆） | `--max-request-journal-entries 10000` |
| 都不記錄 | `--echo.request-log.enabled=false` | `--no-request-journal --disable-request-logging` |
| 各自預設 | 預設 | 預設（請求紀錄不設上限） |

### 結果（每秒處理筆數，括號內為 p99 延遲）

| 情境 | Echo 記錄 | WireMock 記錄 10k | Echo 不記錄 | WireMock 不記錄 | WireMock 預設 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 固定 JSON，4 連線 | 5,898 (6 ms) | 1,532 (7 ms) | 7,591 (6 ms) | 33,646 (3 ms) | 12,573 (683 ms) |
| 固定 JSON，8 連線 | 8,191 (8 ms) | 1,515 (17 ms) | 9,920 (7 ms) | 38,023 (5 ms) | 846 (707 ms) |
| 固定 JSON，20 連線 | 11,484 (13 ms) | 1,530 (39 ms) | 14,504 (10 ms) | 46,584 (7 ms) | 491 (731 ms) |
| 回應模板 | 6,760 (10 ms) | 1,490 (18 ms) | 8,544 (7 ms) | 23,629 (6 ms) | 138 (706 ms) |
| XML 約 1.5 KB + XPath | 7,401 (8 ms) | 2,147 (16 ms) | 9,951 (7 ms) | 14,540 (5 ms) | 23 |
| XML 約 100 KB + XPath | 4,097 (12 ms) | 911 (66 ms) | 6,262 (8 ms) | 1,941 (15 ms) | 3（有錯誤） |
| 2,000 條規則中隨機一條 | 6,419 (8 ms) | 417 (130 ms) | 9,195 (6 ms) | 3,204 (11 ms) | 2（有錯誤） |

| 壓測後 | Echo 記錄 | WireMock 記錄 10k | Echo 不記錄 | WireMock 不記錄 | WireMock 預設 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 完整回收後的 heap | 102 MB | 611 MB | 59 MB | 14 MB | 1,015 MB |
| 程序記憶體（RSS） | 1,229 MB | 1,269 MB | 1,221 MB | 863 MB | 1,265 MB |
| 執行緒 | 82 | 48 | 77 | 48 | 48 |
| 啟動時間 | 11.8 s | 1.0 s | 12.0 s | 0.9 s | 0.9 s |

「不記錄」兩欄是 WireMock 加上 `--disable-request-logging` 後重測的結果，Echo 也一起重跑，兩邊輪流各 2 輪；啟動時間取自主要量測。WireMock 預設那一欄的 p99 是兩輪平均。兩輪差異很大（例如 2,000 條規則情境為 2 ms 與 1.4 s），所以只列出明確的數字。

### 條件欄位的位置（補充量測）

Echo 以串流方式讀 XML，條件一判斷完就停止，不讀剩下的內容；上面兩個 XML 的 `<Type>` 是第二個元素，Echo 只讀了開頭約 40 bytes。WireMock 的 `matchesXPath` 一律先把整份文件解析成 DOM。把 `<Type>` 移到文件最後再量一次（同一台機器，2 輪中位數，每秒處理筆數；這組的 WireMock 只用 `--no-request-journal`，沒有關閉請求日誌，對 XML 情境影響很小，見上表 1,919 與 1,941）：

| 情境 | Echo 不記錄 | WireMock 不記錄 | Echo 記錄 |
| --- | ---: | ---: | ---: |
| XML 約 1.5 KB，欄位在最前 | 10,087 | 14,907 | 7,401 |
| XML 約 1.5 KB，欄位在最後 | 10,409 | 13,776 | 6,857 |
| XML 約 100 KB，欄位在最前 | 6,721 | 1,919 | 4,097 |
| XML 約 100 KB，欄位在最後 | 3,351 | 1,884 | 2,388 |

大型 XML 欄位在最後時，Echo 仍快約 1.8 倍（欄位在最前時為 3.5 倍）；小型 XML 兩種位置都是 WireMock 較快。

### 怎麼解讀

- **都記錄請求**：Echo 快 3.5～15 倍。
  - WireMock 設定紀錄上限時，每個請求都會數一遍整串紀錄（`removeOldEntries` 對整個佇列計數），所以每秒大約只能處理 1,500 筆。不設上限的預設模式沒有這個成本，但記憶體會一直增加（見下一點）。
  - WireMock 那約 600 MB 的 heap，大部分是紀錄裡留下的 100 KB XML 內容（最後 1 萬筆紀錄中約 5,800 筆是大型 XML），不是 1 萬筆紀錄的固定成本。2,000 條規則那一項是在大型 XML 之後量的，當時 GC 壓力很大，15 倍的差距含有這個因素。
  - Echo 把記錄交給背景執行緒寫入硬碟，回應不等記錄。
- **都不記錄（只比比對與回應）**：
  - 簡單請求 WireMock 快：固定 JSON 快 3.2～4.4 倍，模板快 2.8 倍，小 XML 快 1.5 倍。即使只有 4 條規則，Echo 固定 JSON（8 連線）也只有 14,045，仍比 WireMock 慢 2.7 倍，所以主因不是規則多寡；CPU 取樣顯示大部分時間花在 Spring MVC、Undertow 與統計，Echo 自己的比對與回應約佔 14%（見[已知瓶頸](#已知瓶頸與後續方向)）。
  - 大型 XML Echo 快 1.8～3.2 倍：Echo 用串流讀取，WireMock 解析整份 DOM；差距取決於條件欄位的位置（見上一節）。
  - 2,000 條規則 Echo 快 2.9 倍：Echo 依主機與 method 分組後，在組內逐條比對路徑（這 2,000 條都在同一組）；WireMock 不依主機分組。原因推測是 Echo 每條路徑的比對成本較低，這次沒有從 WireMock 原始碼確認。
- **WireMock 預設設定**：請求紀錄沒有上限，每個請求都留在記憶體裡。
  - 壓測結束時，完整回收後的 heap 是 1,015 MB（上限 1,024 MB），幾乎全被紀錄佔滿，GC 幾乎不停。
  - 依執行順序，處理量是 12,573 → 846 → 491 → 138 → 23 → 3 → 2。各情境的工作量不同，所以這不是乾淨的時間序列，但趨勢明確。
  - 最後兩個情境每輪有 7～8 個非 2xx 回應與 11～14 個逾時。日誌沒有 OutOfMemoryError。
  - 用 WireMock 壓測時，應關閉或限制請求紀錄。
- **資源**：WireMock 啟動快（約 1 秒，Echo 約 12 秒），閒置記憶體與執行緒也較少。Echo 多了管理介面、資料庫、JMS 與背景工作。

### 這次比較沒有涵蓋的

- 只量了 HTTP；WireMock 沒有可以直接對照的 JMS 功能。
- wrk 與伺服器在同一台 4 核機器上，數字包含彼此搶 CPU 的影響；每個情境 10 秒、各 2 輪，不代表長時間穩定性。量測時機器的 1 分鐘平均負載約 4～7。
- WireMock 用預設執行緒設定，沒有針對壓測調整；Echo 也維持預設。

## 請求記錄：不等寫入與嚴格模式

預設（`echo.request-log.wait-for-write=false`）下，模擬回應不等請求記錄寫入硬碟，記錄由背景執行緒批次寫入。寫入跟不上時，會丟掉最舊的等待記錄，保留最新的。同一台機器的量測（3 條規則，每秒處理筆數，各 2 輪）：

| 情境 | 舊版（每筆等寫入） | 不等寫入 | 嚴格模式 | 關閉記錄 |
| --- | ---: | ---: | ---: | ---: |
| 固定 JSON，4 連線 | 1,165 | 7,700 | 1,150 | 10,900 |
| 固定 JSON，8 連線 | 2,170 | 10,960 | 2,100 | 15,800 |
| 固定 JSON，20 連線 | 4,715 | 15,260 | 4,680 | 21,900 |
| 回應模板 | 2,050 | 9,200 | 2,000 | 12,500 |
| XML 約 100 KB | 990 | 3,220 | 970 | 5,070 |

- 「不等寫入」是最終版本。「嚴格模式」與「關閉記錄」是修正審查意見前的版本，兩版的不等寫入模式相差 5% 以內。
- 這組只有 3 條規則，所以數字比上面 WireMock 比較（2,004 條規則）高。
- 嚴格模式（`wait-for-write=true`）與舊版相同：每個回應都等記錄寫入；記錄寫不進去時回 503。
- 在這組很重的壓測裡，約 4% 的記錄在進入暫存檔前被丟掉（壓測期間約 48 萬筆中丟了約 2 萬筆）。其餘的都進了暫存檔；和舊版一樣，資料庫最後只保留最新的 10,000 筆。
- 壓測後的程序記憶體與舊版相同（約 1.2 GB）；完整回收後的 heap 較低：約 58 MB，舊版約 175 MB。
- 詳細行為與設定見[安裝與部署](install.md#backup-and-request-log-spool-boundaries)的 *Backup and request-log spool boundaries*。

## 改規則時不卡請求

規則或回應變更後，規則清單在背景重建；重建期間模擬請求繼續用舊清單，不必等待（[PR #62](https://github.com/boloagegit/echo-mock-server/pull/62)）。

- 改規則的 API 會等新清單生效才回應，所以存檔後立刻送出的模擬請求一定用到新規則。
- 在整合測試中讓重建刻意花 800 ms：舊版重建期間的模擬請求花了 813 ms，新版不必等（測試要求小於 400 ms）。
- 多台部署時，一次修改只重建一次，不再因為讀回自己的同步通知而重建多次。

## 同主機大量規則的路徑比對（已改善）

- **原本**：規則依主機與 method 分組，組內逐條比對路徑。同一主機有 2,000 條 GET 規則時（不記錄），相較於只有 4 條規則：
  - 固定 JSON：4 連線 10,346 → 8,246（-20%），8 連線 14,045 → 10,000（-29%），20 連線 20,132 → 14,494（-28%）。
  - 模板：12,201 → 8,598（-30%）。
  - POST 的 XML 情境不受影響。
  - 4 條規則的基準是比較完後約 10 分鐘另外量的（`ECHO_BENCH_PATH_RULES=0`），沒有和其他組輪流執行。
- **現在**：每組規則另外依路徑建索引。精確路徑的規則直接查表，只有 `*` 與 `/x/*` 規則仍逐條比對，再依原本的優先順序合併，所以比對結果與順序都不變（以 500 組隨機規則與請求驗證新舊結果相同）。同一主機 2,000 條 GET 規則、不記錄、8 連線，改善前 → 改善後（每秒請求數）：
  - 固定 JSON：10,008 → 13,178。
  - 模板：8,835 → 11,955。
  - 2,000 條規則中的一條：8,986 → 11,616。
  - 改善後與只有 4 條規則時差不多。

## 已知瓶頸與後續方向

- **簡單請求的固定成本**：不記錄時，WireMock 的簡單請求仍快 2.7 倍以上（即使只有 4 條規則）。以 JFR 取樣 Echo 處理固定 JSON 時的 CPU 時間：
  | 花在哪裡 | 佔比 |
  | --- | ---: |
  | Spring MVC 找處理程式（`/mock/**` 要與所有管理 API 比對） | 約 21% |
  | Spring MVC 其他處理（參數與回應轉換） | 約 24% |
  | Undertow 網路收發 | 約 23% |
  | Micrometer 逐請求統計 | 約 7% |
  | Spring Security 網址檢查 | 約 7% |
  | Echo 自己的比對與回應 | 約 14% |

  大部分花在框架。讓模擬請求繞過 Spring MVC 或略過統計，大約能快兩成，但會讓請求有兩套處理流程、或改變監控行為，維護成本較高，目前決定不做。
- **啟動時間**：約 12 秒（WireMock 約 1 秒）。主要是 JIT 編譯與主程式搶 CPU（編譯約用 7 秒 CPU），以及從巢狀 jar 載入類別；webjars 工具掃描 classpath 讓主程式多等約 0.85 秒。同一台機器的量測（各 2～3 次）：
  | 啟動方式 | 時間 |
  | --- | ---: |
  | `java -jar`（目前） | 12.3～13.3 s |
  | 先解開 jar 再啟動 | 9.8～10.5 s |
  | 解開 jar＋類別快取（AppCDS） | 7.0～7.2 s |
  | 只用 C1 編譯 | 14.6～15.8 s |
  | 延遲初始化 | 12.3～12.5 s |

  不需要改程式就能用「先解開 jar」：
  ```bash
  java -Djarmode=tools -jar echo-server.jar extract --destination echo
  java -jar echo/echo-server.jar
  ```
  會多一個部署步驟，所以不是預設做法。類別快取必須與同一版程式和 JDK 搭配，適合容器映像，不適合直接以 jar 部署。

## 重跑方法

Echo 與 WireMock 比較，只支援 Linux：
- 需要 java、wrk、curl、python3、jcmd、bc。
- port 18080 要空著。

```bash
./gradlew bootJar
curl -fLo /tmp/wiremock.jar \
  https://repo1.maven.org/maven2/org/wiremock/wiremock-standalone/3.13.2/wiremock-standalone-3.13.2.jar
scripts/bench-vs-wiremock.sh "$(ls -t build/libs/echo-server-*.jar | head -1)" /tmp/wiremock.jar ./bench-out 2
python3 scripts/bench-vs-wiremock-summary.py ./bench-out
```

- `ECHO_BENCH_PATH_RULES=0`：不載入 2,000 條路徑規則，用來量規則數量的影響。
- `ECHO_BENCH_TYPE_LAST=1`：把 XML 的 `<Type>` 放到文件最後。

其他腳本：

| 腳本 | 用途 |
| --- | --- |
| `scripts/stress-test-rps.py` | Echo 各情境每秒處理筆數（Python 用戶端） |
| `scripts/stress-test-vs-wiremock.py` | 以 Python 用戶端比較 Echo 與 WireMock（需先自行啟動 WireMock） |
| `scripts/bench-2000-jms.py` | 2,000 條 JMS 規則的比對時間（需開啟批次匯入 `echo.features.bulk-import-export-enabled=true`） |
| `scripts/perf-test-downstream.py` | 轉發測試用的下游服務 |
| `scripts/stress-test-1600-rules.py`、`stress-test-xml-body.py`、`stress-test-scenario1.py` | 規則數量、XML 大小、單一情境 |
| `scripts/stress-test-memory.py`、`stress-test-jms-match.py` | 記憶體與 JMS 比對壓力 |
| `scripts/test-match-scenarios.py` | 比對情境回歸測試（69 個案例） |

## 歷史結果

以下為較早版本、不同環境的量測，保留作為趨勢參考，不能直接和上面的數字比較。

### 不記錄請求時的 Echo 與 WireMock（2026-09-03）

環境：
- JDK 21 Alpine 容器，512 MiB heap，容器上限 1 GiB。
- 50 並發，每情境 8 秒。
- Echo 關閉請求記錄；WireMock 用 `--no-request-journal --disable-request-logging`。

| 情境 | Echo RPS | WireMock RPS | 倍數 | Echo p95 | WireMock p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 簡單 JSON | 7,304 | 6,532 | 1.12x | 12.0 ms | 13.9 ms |
| JSON，10 條候選規則 | 6,924 | 6,789 | 1.02x | 12.9 ms | 12.1 ms |
| XML 約 1 KB + XPath | 6,466 | 4,593 | 1.41x | 13.5 ms | 17.7 ms |
| XML 約 80 KB + XPath | 3,120 | 250 | 12.46x | 19.8 ms | 312.8 ms |

### 記錄請求時的 Echo 與 WireMock（2026-08-11）

環境：
- Apple Silicon，Java 22.0.2，512 MiB heap。
- 兩邊都開請求紀錄，50 並發，每情境 8 秒。

| 情境 | Echo RPS | WireMock RPS | 倍數 | Echo p95 | WireMock p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 簡單 JSON | 6,327 | 7,068 | 0.90x | 13.1 ms | 12.9 ms |
| JSON，10 條候選規則 | 7,570 | 6,875 | 1.10x | 10.6 ms | 13.0 ms |
| XML 約 1 KB + XPath | 7,136 | 4,617 | 1.55x | 11.3 ms | 24.4 ms |
| XML 約 80 KB + XPath | 3,116 | 483 | 6.46x | 23.9 ms | 180.6 ms |

這兩次使用 Python 用戶端（`scripts/stress-test-vs-wiremock.py`）與 50 並發。推測用戶端本身限制了簡單請求的上限，所以結果和 2026-10-14 用 wrk 量到的差距不同。

### 2,000 條 XPath 規則（更早期）

條件：2,000 條帶 XPath 條件的 HTTP 規則，目標規則排在最後，10 並發，200 個請求。

| 情境 | RPS | p99 |
| --- | ---: | ---: |
| Echo XML | 434 | 47 ms |
| Echo JSON | 1,066 | — |
| WireMock XML | 31 | 427 ms |

### 其他早期量測

- 1,600 條規則的比對時間：冷快取 10 ms、熱快取 6 ms；6 條規則時 3 ms。
- XML 大小：約 1 KB 時 16 ms，約 100 KB 時 35 ms；JSON 維持 4～6 ms。這是當時 DOM 解析的結果，之後已改為串流解析。
- JMS 記憶體量測見 [jms-memory-benchmark-2026-08-22.md](archive/jms-memory-benchmark-2026-08-22.md)。
