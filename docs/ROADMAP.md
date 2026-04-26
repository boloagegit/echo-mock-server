# Echo Mock Server — Product Roadmap

## 產品定位

企業內網環境的 JMS + HTTP 雙協定 Mock Server，搭配視覺化管理介面。
核心差異化：JMS 支援、企業管理功能（LDAP、角色權限、審計、備份）、內網友善。

## Echo 核心優勢

- JMS + HTTP 雙協定
- Web UI 管理介面
- 企業管理功能：LDAP、Admin/User 角色權限、審計紀錄、帳號管理
- 標籤分類 + 批次操作 + Excel 匯入
- 規則保護/展延、孤兒清理、自動備份
- 匹配鏈分析（match / near-miss / mismatch / shadowed / skipped / fallback）
- SSE Server-Sent Events 串流模擬
- 回應獨立管理（多規則共用同一回應）
- 零外部依賴、內網友善（WebJars 無需 CDN）
- 單 JAR 部署、秒級啟動
- 內建帳號管理 + 自助註冊
- 國際化 UI（英文 + 繁體中文）

## 待開發功能

| 功能 | 說明 |
|------|------|
| OpenAPI/Swagger 匯入 | 上傳 spec 自動產生 mock 規則 |
| Stateful Scenarios | 狀態機模擬多步驟對話流程 |
| Fault Injection | connection reset、empty response |
| Webhook/Callback | 匹配後主動呼叫另一個 URL |
| Faker 假資料產生 | 內建假名字、地址、email 等產生器 |
| Multipart 匹配 | 檔案上傳 API 的 request 匹配 |
| Java SDK | 原生 client library |
| Testcontainers | 一行 code 在測試中啟動 Echo |

## Roadmap

### Phase 1 — Quick Rule from Request Log + Delay Range + OpenAPI Import

- [x] **1-1. Quick Rule from Request Log（從請求紀錄快速建立規則）**

**做法**：在 Request Log 頁面加「Create Rule from this log」功能。

**流程**：
1. 開 proxy 轉發到真實環境
2. 跑一輪測試
3. 到 Request Log 頁面查看紀錄
4. 挑選想要 mock 的請求，點「建立規則」
5. 自動帶入 matchKey、method、targetHost、responseBody
6. 微調後儲存

**優勢**：
- 可選擇性建立，不會錄進一堆不需要的 stub
- 可先看 log 確認內容再決定
- 不需要額外的「錄製模式」開關
- 更符合 Echo「視覺化管理」的定位

- [x] **1-2. Delay Range（隨機延遲範圍）**

**做法**：規則新增 maxDelayMs 欄位，每次回應時在 delayMs ~ maxDelayMs 範圍內隨機取值。

- [x] **1-3. OpenAPI/Swagger Import（從 API 規格匯入）**

**做法**：上傳 OpenAPI spec（JSON/YAML）→ 解析每個 path + method → 預覽後批次建立 HttpRule + Response。

**流程**：
1. 在 Web UI 上傳 OpenAPI spec 檔案
2. 解析 paths → 為每個 operation 建立 HttpRule
3. 從 responses/examples 欄位取回應內容建立 Response
4. 預覽匯入結果，使用者可勾選/取消個別規則
5. 確認後批次建立

**實作重點**：
- 支援 OpenAPI 3.x / Swagger 2.x
- 支援 JSON 和 YAML 格式
- 從 `examples`、`example`、`schema` 欄位產生回應 body
- 匯入時自動設定 Content-Type header

- [x] **1-4. Faker 假資料產生器**

**做法**：在 ResponseTemplateService 新增 Handlebars helper：
- `{{randomFirstName}}`、`{{randomLastName}}`、`{{randomFullName}}`
- `{{randomEmail}}`、`{{randomPhoneNumber}}`
- `{{randomCity}}`、`{{randomCountry}}`、`{{randomStreetAddress}}`
- `{{randomInt min=0 max=100}}`

> 註：隨機字串功能已由 `{{randomValue length=10 type='ALPHANUMERIC'}}` 提供，不另建 `randomString`。

### Phase 2 — Fault Injection + Stateful Scenarios

- [x] **2-1. Fault Injection（故障注入）**

**做法**：BaseRule 新增 `faultType` 欄位（enum: NONE / CONNECTION_RESET / EMPTY_RESPONSE），在回應階段根據 faultType 決定行為。

**使用範例**：
- 電商系統：模擬付款 API timeout，驗證前端是否正確顯示「請稍後再試」
- 微服務：模擬下游服務斷線，驗證 circuit breaker 是否正常觸發
- JMS 場景：模擬 ESB 回應延遲或無回應，驗證 timeout 處理

- [x] **2-2. Stateful Scenarios（狀態機）**

**做法**：新增 Scenario entity（`scenarioName` + `currentState`），規則新增兩個欄位：
- `requiredScenarioState`：匹配前提
- `newScenarioState`：匹配成功後轉移狀態

**使用範例**：
- 訂單流程：第一次 GET /order/123 回傳 `status: pending`，呼叫 POST /order/123/pay 後，再 GET 回傳 `status: paid`
- 輪詢模擬：前 3 次 GET /job/status 回傳 `processing`，第 4 次回傳 `completed`

### Phase 3 — Webhook/Callback + Multipart

- [ ] **3-1. Webhook/Callback**
- 匹配後主動呼叫另一個 URL，模擬非同步通知場景
- 搭配 JMS 場景更有意義：收到 JMS 訊息後觸發 HTTP callback

- [ ] **3-2. Multipart Request 匹配**
- 檔案上傳 API 的 mock 需求

### Phase 4 — SDK / Testcontainers（視用戶需求）

- [ ] **4-1. Java SDK**
- [ ] **4-2. Testcontainers 整合**
- [ ] **4-3. gRPC**
