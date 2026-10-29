# Echo Mock Server

**一個 JAR 就能模擬 HTTP API 與 JMS 佇列，還有能看出「每個請求為什麼命中這條規則」的網頁管理介面。**

[![CI](https://github.com/boloagegit/echo-mock-server/actions/workflows/ci.yml/badge.svg)](https://github.com/boloagegit/echo-mock-server/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/boloagegit/echo-mock-server)](https://github.com/boloagegit/echo-mock-server/releases)
![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-green)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[English](README.md) · **繁體中文**

![Echo 規則列表（深色主題）：HTTP 與 JMS 規則依主機與佇列分組，顯示標籤、條件與命中次數](docs/images/rules.png)

開發和測試時，Echo 代替你的系統要呼叫的外部服務：HTTP 呼叫和 JMS 訊息都照你設定的內容回覆，認不得的請求就轉給真正的系統。規則在瀏覽器裡管理，整個團隊共用。Echo 只是一個 `java -jar` 程序，內建資料庫和訊息 broker，不需要連外網。

## 為什麼選 Echo

- **HTTP 與 JMS 一起管。** REST 與 Artemis 佇列的模擬共用同一套規則、模板與管理介面；沒對到規則的 JMS 訊息可以轉給 TIBCO EMS 或 Artemis ESB。
- **看得出為什麼命中。** 每筆請求紀錄都有處理流程和完整的比對過程：哪些規則是候選、哪個條件通過或沒通過。
- **給團隊用的。** 有登入（內建帳號或 LDAP）與管理者／一般使用者權限，也有修訂紀錄。規則可以用標籤批次啟用或停用，也可以保護、設定保留期限。多台實例可以共用同一個資料庫。
- **適合封閉內網。** 只要一個 JAR，用 `java -jar` 啟動，資料存在 SQLite。前端資源透過 WebJars 打包，不需要 CDN。
- **HTTP 效能實測。** Java 17、512／1024 MiB 比較中，固定回應與 JSON 條件在這組設定下的吞吐高於 WireMock 3.13.2；慢下游轉發幾乎持平，RSS 與延遲則依設定而異。完整結果與限制見[效能](#效能)。

## 快速開始

需要 Java 17 以上。

```bash
# 從 Releases 頁面下載 echo-server-<版本>.jar，然後：
java -jar echo-server-<版本>.jar
```

或從原始碼建置：

```bash
./gradlew bootJar
java -jar build/libs/echo-server-*.jar
```

打開 <http://localhost:8080>，用 **admin / admin** 登入。給別人用之前請先改密碼。資料存在 `./data/mockdb.sqlite`。

> Docker、外部資料庫（PostgreSQL、MySQL、MariaDB、SQL Server、Oracle）、Windows 與多實例部署，見[安裝與部署](docs/zh-TW/install.md)。

使用 Spring Boot／OpenFeign 的應用可在登入後，從左下角帳號選單 → **SDK 中心**（`/#/sdk`）取得內含的 Client SDK。完整 ZIP 包含安裝說明、本機 Maven POM 與設定範例。一般 Echo 建置已將下載檔包進 Server JAR，執行時不需另外放 SDK 檔案或連外下載。詳見 [SDK 說明](sdk/README.md)。

### 一分鐘做出第一個 Mock

先建立一條規則。可以在管理介面建，也可以像下面這樣用 REST API 建：

```bash
curl -u admin:admin -X POST http://localhost:8080/api/admin/rules \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  -d '{
    "protocol": "HTTP",
    "targetHost": "api.example.com",
    "matchKey": "/users/1",
    "method": "GET",
    "status": 200,
    "responseBody": "{\"id\": 1, \"name\": \"Alice\"}"
  }'
```

透過 `/mock` 呼叫，並用 `X-Original-Host` 指定原本要打的主機：

```bash
curl http://localhost:8080/mock/users/1 -H 'X-Original-Host: api.example.com'
# {"id": 1, "name": "Alice"}
```

不想改程式的話，可以把用戶端的 base URL 指向 Echo，或用 Nginx 代理補上這個 header（見[使用方式](docs/zh-TW/install.md#使用方式)）。沒對到規則的請求會轉給原本的主機。

規則也可以依 body、query、header 條件比對，回應可以用模板：

```json
{
  "protocol": "HTTP",
  "targetHost": "api.example.com",
  "matchKey": "/orders",
  "method": "POST",
  "priority": 10,
  "bodyCondition": "customer.tier=VIP",
  "status": 201,
  "responseBody": "{\"orderId\": \"{{randomValue type='UUID'}}\", \"total\": \"{{jsonPath request.body '$.total'}}\"}"
}
```

## 功能

**比對**
- 可以比對 JSON、XML body（JSONPath、XPath）、query 參數與 header；進階運算式用 `matchSpec`。
- 支援優先順序與萬用字元路徑，也內建規則測試。
- 選用的狀態機場景，可以模擬多步驟流程（WireMock 風格）。

**回應**
- Handlebars 模板，支援條件、迴圈、JSONPath、XPath 與假資料；HTTP 和 JMS 共用同一套模板。
- 固定或隨機延遲、故障注入（連線重置或空回應），以及 Server-Sent Events 串流。
- 回應可以獨立管理，多條規則共用。

**協定**
- HTTP 模擬，沒對到規則時轉發到原本的主機。
- JMS 使用內建的 Artemis broker。Echo 也可以監聽共用的外部 broker，沒對到規則的訊息可以轉給 TIBCO EMS 或 Artemis。

**管理介面**
- 深色與淺色主題，支援響應式版面。
- 請求紀錄附流量圖、處理流程圖與比對過程。
- 用環境標籤和主題色區分 SIT、UAT；介面有英文與繁體中文。

**維運**
- 內建帳號或 LDAP，管理者／一般使用者權限，記住登入，以及修訂紀錄。
- 標籤、批次啟用與停用、規則保護、保留與展延，以及孤兒回應清理。
- SQLite 排程備份、多實例快取同步與資源監控。
- 選用的 OpenAPI／Swagger 匯入、Excel 匯入，以及 JSON 匯出與匯入，預設都關閉。

## 畫面

| 處理流程 | 比對過程 |
|---|---|
| ![請求詳情的處理流程，從收到請求到回覆](docs/images/request-flow.png) | ![比對過程列出每條候選規則，以及哪個條件通過或沒通過](docs/images/match-chain.png) |
| **規則編輯** | **請求紀錄** |
| ![規則編輯畫面：條件、回應內容與模板預覽](docs/images/rule-editor.png) | ![請求紀錄與流量總覽：命中、轉發與失敗的請求](docs/images/request-logs.png) |

也有淺色主題：

![淺色主題的規則列表](docs/images/rules-light.png)

## 效能

以下比較使用 [Echo 037d2d7](https://github.com/boloagegit/echo-mock-server/commit/037d2d7d82eed1be3330f3e7979518dbf5290fad) 與真正的 **WireMock 3.13.2 standalone**。兩邊在同一 Linux 環境依序執行：Java 17.0.20.1+1、共用四核心 CPU 配額，伺服器固定兩核心、壓測端與下游固定另兩核心，G1、`-Xms=-Xmx` 為 **512／1024 MiB**、direct memory 上限 128 MiB、NMT。Echo 使用預設原生 HTTP 入口，worker 上限設為 80；WireMock 使用 80 container workers 加 80 非同步回應執行緒。HTTP/1.1 keep-alive、每路徑最多 64 連線、監測 agent 均相同；HTTP 比較關閉 JMS、備份與定期清理。這是公開設定下的比較，沒有窮舉兩個產品的最佳調校。

**短時間吞吐，關閉紀錄。** 全程載入相同的 106 條規則，其中同一路徑有 100 條候選條件。下表為每產品／heap **三個全新 JVM** 的平均每秒筆數；先預熱 20 秒，各情境再預熱 5 秒、量測 8 秒，輪替順序。逐筆核對回應，壓測端 CPU 均有餘裕。這是 64 連線下的觀測容量，**不是絕對最高承載，也不是十分鐘持續峰值**。

| 情境 | Echo 512 | WireMock 512 | Echo 1024 | WireMock 1024 |
|---|---:|---:|---:|---:|
| 固定回應，1 KiB 請求 | 53,020 | 13,774 | 49,943 | 14,057 |
| 100 條 JSON 條件，1 KiB 請求 | 37,081 | 4,685 | 34,942 | 4,866 |
| 單一 JSON 條件，64 KiB 請求 | 12,638 | 6,610 | 12,645 | 7,014 |
| 轉發至 50 ms 下游 | 1,196 | 1,198 | 1,193 | 1,199 |

50 ms 下游主導轉發耗時，因此轉發吞吐幾乎持平。JSON 使用合法本文與等價簡單條件，不代表兩邊的 JSONPath 語言或錯誤輸入語意完全相同。這組設定與舊測試的負載、JVM 不同，不能將倍率解讀成版本間提升幅度。

**相同送入流量的十分鐘混合測試。** 每產品／heap／紀錄組合各用全新 JVM，預熱 60 秒，再以**每秒 200 筆量測 600 秒**：固定／模板／100 條件 JSON／64 KiB JSON／轉發各為 50／50／80／10／10 筆。JSON 條件含 10% 不命中與變動 nonce；轉發送 64 KiB、下游等 50 ms 後回 16 KiB。八組各完成 **120,000 筆正確回應，共 960,000 筆**，沒有回應或連線錯誤、逾時、OOM 或自然 full GC。每設定只有一輪十分鐘，並非統計信賴區間。

下表每對數字均為 **Echo／WireMock**。CPU 為整個程序每完成一筆請求的時間；配置量與 RSS 也量測整個程序。GC 暫停總時間不含流量結束後的診斷 GC；GC 後 heap 則在排空紀錄並執行該診斷 GC 後量測。

| Heap／紀錄 | CPU µs／筆 | 配置量 KiB／筆 | RSS 峰值 MiB | GC 後 heap MiB | GC 暫停 ms |
|---|---:|---:|---:|---:|---:|
| 512 / 關 | 399.3 / 515.0 | 40.4 / 688.2 | 718.9 / 462.1 | 50.9 / 11.6 | 85.7 / 408.6 |
| 512 / 開 | 1,465.9 / 2,217.8 | 213.7 / 689.1 | 804.6 / 658.8 | 53.3 / 146.2 | 277.0 / 2,435.8 |
| 1024 / 關 | 375.3 / 499.3 | 40.5 / 678.7 | 1,055.7 / 784.5 | 57.5 / 12.1 | 24.4 / 255.2 |
| 1024 / 開 | 1,439.8 / 2,216.9 | 214.3 / 674.5 | 1,119.4 / 1,195.1 | 64.9 / 145.9 | 414.7 / 2,371.5 |

下表 P99 為同一送入流量下，**從預定送出到回應完成**的毫秒數，包含壓測端等候排程；每對為 Echo／WireMock。

| 情境 | 512 關 | 512 開 | 1024 關 | 1024 開 |
|---|---:|---:|---:|---:|
| 固定回應 | 3.39 / 3.21 | 3.20 / 10.40 | 3.06 / 3.04 | 3.26 / 11.10 |
| 模板 | 3.47 / 3.28 | 3.33 / 10.37 | 3.07 / 3.20 | 3.42 / 11.04 |
| 100 條 JSON 條件 | 3.27 / 3.42 | 3.47 / 10.66 | 3.05 / 3.28 | 3.53 / 11.68 |
| 64 KiB JSON | 4.67 / 4.43 | 4.35 / 12.93 | 4.66 / 3.87 | 4.40 / 13.82 |
| 轉發 | 58.50 / 56.70 | 58.75 / 67.33 | 57.86 / 56.58 | 58.24 / 67.33 |

Echo 的 RSS 並非全面較低；延遲依情境而異，也不是全面領先。兩邊紀錄皆關閉或設定保留 10,000 筆。Echo 使用硬碟上的非同步 SQLite 與持久化 spool（預設每秒最多寫 1,000 筆、批次保留 9,000～10,000 筆），WireMock 使用保留 10,000 筆的記憶體 journal。Echo 開紀錄的每組均核對 132,000 筆預熱加正式紀錄，零丟棄，停止後資料庫完整性及本文長度檢查通過。持久化與超載策略不同，峰值回覆能力不代表該流量下能完整記錄；RSS 不含 OS page cache，heap 上限也不是整個程序的記憶體上限。

正確性驗證通過 **Java 2,534 項**（略過 6 項人工／外部環境測試）、**JavaScript 183 項**、**Python 67 項**；[CI](https://github.com/boloagegit/echo-mock-server/actions/runs/40803779258) 的 Java 17／21、Windows Java 17 開發檢查與容器建置皆通過。另外以 512 MiB 跑十分鐘，混合 **96,000 筆真正 Artemis JMS、18,000 筆 HTTP 與 8 個並行管理紀錄查詢者**，開啟紀錄，零回應／correlation 錯誤與紀錄丟棄，保存的匹配鏈有效，停止服務及暫存回覆 queue 清理通過。這組 heap 同時包含兩個 broker 與 JMS 呼叫端，不能當成 Echo 單獨的記憶體成績，也不是 TIBCO EMS 相容性驗收。

十分鐘是有限的回歸觀察，不能證明永久穩定或沒有記憶體洩漏。原始日誌、profile、資料庫與 JAR 均留在 Git 外。舊數據保留在[歷史效能文件](docs/performance.md)與 [2026-10-16 驗證](docs/native-http-validation-2026-10-16.md)，請勿與本輪混算。設定及較早證據另見 [HTTP 加速說明](docs/native-http.md)、[JMS 正確性與資源重用](docs/jms-performance-and-correctness.md)及[啟動量測](docs/performance-hardening-2026-10-16.md)。

### XML／XPath：同筆請求共用查詢結果

XML／XPath 比較使用[基準 d1f201c](https://github.com/boloagegit/echo-mock-server/commit/d1f201cbaa5e6a4e0513eb1af50f921501f86a34)、[XPath 共用版 80acf0b](https://github.com/boloagegit/echo-mock-server/commit/80acf0b9d875b9e14200b53dd93e5d26287328df) 與真正的 WireMock 3.13.2 standalone。兩個 Echo 版本的產品程式只差這次四個檔案的共用改動。全部在同一 Linux 主機依序執行，使用 Java 17.0.20.1+1、共用四核心配額、G1、512／1024 MiB heap、**64 條 HTTP/1.1 keep-alive 連線**，關閉紀錄。這組比較使用預設入口／worker 設定及共用 CPU 排程，與上方固定 CPU 分配、調整 worker 的測試不同，絕對數字請分開判讀。

共載入 29 條規則，等比例送到四條路徑：8 條結構化規則查同一個一般 XPath、單條 XPath、8 條不同 XPath、8 條比較同一個 scalar `count()` 結果的規則。每條路徑也等比例使用小 XML、**64 KiB／900 個 Item** 的密集 XML、長文字及 SOAP，nonce 持續變動，逐筆核對對應的命中／未命中回應。因此 **一半流量可以共用查詢**，scalar 刻意在第 5～8 條規則命中；兩邊規則只在這份語料對應，不代表整套規則語言等價。

預熱 45 秒後，**45 秒容量短測**結果如下：

| Heap MiB | Echo 基準每秒筆數 | Echo 共用版每秒筆數 | WireMock 每秒筆數 |
|---|---:|---:|---:|
| 512 | 1,316 | 2,452 | 1,253 |
| 1024 | 1,276 | 2,481 | 1,295 |

每個 JVM 接著以 **200 筆／秒持續五分鐘**；六組各完成 60,000 筆正確回應，合計 **360,000 筆**，零錯誤、零 timeout，壓測端 CPU 有餘裕。512／1024 MiB 下，共用版相對基準每筆整個程序 CPU 減少 **46.7%／48.7%**，每筆配置量估計減少 **53.5%**。從預定送出到完成的 P99 為 **15.04 → 9.24 ms／17.06 → 9.34 ms**，WireMock 為 **48.25／17.10 ms**。結束時 RSS 仍高於 WireMock：512 MiB 為 **719／553 MiB**，1024 MiB 為 **1,064／833 MiB**（Echo 共用版／WireMock）。

收益來自**結構化 `matchSpec` 規則重複使用相同字串的一般 XPath**。三個獨立 JVM fork 微測中，單規則、不同 XPath、超出快取預算的大結果沒有穩定收益。最初密集 XML／不同 XPath 短測曾退步 5～12%，較長控制未持續出現該退步；兩批結果都不能當成所有情境的保證。另以 272～274 bytes 小 XML 做三 fork 微測，重複查詢吞吐約提升到 **4.3 倍**；單規則與不同查詢的結果區間重疊。這些是引擎比對次數，不是 HTTP 每秒筆數。

新增快取只存在於當筆請求，保存文字結果，上限 **128 筆、估算保留量 512 KiB**；額滿後不再保存新查詢，已保存項目仍可用，因此較晚才出現的熱門查詢可能無法受益。XML 仍完整解析，保留原有安全檢查；legacy 舊規則原本已有自己的共用機制。另對候選版開啟紀錄，明確設定本文上限 65,536 字元，核對 **6,000 筆完整、正確的保存內容**；這不是開紀錄的比較容量測試。五分鐘不能證明長期沒有 leak；尚未驗證 2,000 條規則的 HTTP 容量或 TIBCO EMS 相容性。原始量測、profile、資料庫及實驗報告均留在 Git 外。

## 文件

| 文件 | 內容 |
|------|------|
| [安裝與部署](docs/zh-TW/install.md) | JAR、Docker、資料庫 Profile、多實例、Nginx |
| [設定與認證](docs/zh-TW/configuration.md) | `application.yml`、LDAP、內建帳號、權限 |
| [規則與回應](docs/zh-TW/rules.md) | 優先順序、條件、標籤、狀態機場景、模板 |
| [JMS](docs/zh-TW/jms.md) | 內建 broker、ESB 轉發、共用 broker |
| [REST API](docs/zh-TW/api.md) | 主要管理 API |
| [全部文件](docs/zh-TW/README.md) | SSE、資源監控、交易診斷、SQLite 復原等 |

## 參與貢獻

歡迎回報問題、提出想法或送 PR。建置與測試步驟見 [CONTRIBUTING.md](CONTRIBUTING.md)，版本變更見 [CHANGELOG.md](CHANGELOG.md)。安全性問題請依 [SECURITY.md](SECURITY.md) 私下回報。

Echo 使用 Spring Boot 3.5、Undertow、Caffeine、內建 Artemis、Handlebars、Vue 3 與 Bootstrap 5。

## 授權

[MIT](LICENSE)
