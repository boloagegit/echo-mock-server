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
- **效能有實測。** 與 WireMock 的比較連同測試方法與腳本一起公開，WireMock 比較快的情境也照實列出，見[效能](#效能)。

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

同一台 4 vCPU Linux 機器，兩邊都載入 2,004 條規則（Echo 與 WireMock 3.13.2，wrk，2026-10-14）。下表是每秒處理筆數：

| 情境 | Echo 記錄 | WireMock 記錄 1 萬筆 | Echo 不記錄 | WireMock 不記錄 |
|------|---------:|---------:|---------:|---------:|
| 固定 JSON，8 連線 | 8,191 | 1,515 | 9,920 | 38,023 |
| 回應模板 | 6,760 | 1,490 | 8,544 | 23,629 |
| XML 約 1.5 KB + XPath | 7,401 | 2,147 | 9,951 | 14,540 |
| XML 約 100 KB + XPath | 4,097 | 911 | 6,262 | 1,941 |
| 2,000 條規則中隨機一條 | 6,419 | 417 | 9,195 | 3,204 |

- **有記錄請求時。** 兩邊都保留最近 10,000 筆請求紀錄，Echo 快 3.5～15 倍。
- **都不記錄時。** 簡單請求 WireMock 快 1.5～4.4 倍；大 XML Echo 快 1.8～3.2 倍，2,000 條規則時 Echo 快 2.9 倍。
- **啟動時間。** WireMock 約 1 秒，Echo 約 12 秒。

這些數字只代表這組情境。完整的測試方式、已知瓶頸與重跑腳本見 [docs/performance.md](docs/performance.md)。

## 文件

| 文件 | 內容 |
|------|------|
| [安裝與部署](docs/zh-TW/install.md) | JAR、Docker、資料庫 Profile、從 H2 遷移、多實例、Nginx |
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
