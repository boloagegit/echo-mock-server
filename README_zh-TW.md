# Echo Mock Server

企業級雙協定 Mock 伺服器，支援 HTTP 和 JMS 協定，用於開發與測試環境模擬 API 回應。

## 截圖

![Mock Rules - Dark Theme](docs/screenshots/mock-rules-dark.png)

## 功能特色

- **雙協定支援** - HTTP REST API 與 JMS (Artemis) 訊息佇列
- **JMS Proxy** - 無匹配規則時可轉發到 TIBCO EMS 或 Artemis ESB
- **條件匹配** - 根據 Body (JSON/XML)、Query、Header 條件回傳不同回應
- **標籤分類** - 以 JSON 標籤分類規則（`key:value`），可依標籤批次啟用/停用
- **回應管理** - 獨立管理回應內容，多規則可共用同一回應，支援匯出/匯入
- **SSE 串流** - 支援 Server-Sent Events，可編輯事件序列、循環模式、即時預覽
- **動態模板** - WireMock 風格 Handlebars 模板引擎，支援條件、迴圈、JSONPath/XPath
- **Proxy 轉發** - 無匹配規則時自動轉發到原始主機
- **視覺化管理** - Dark/Light Theme Web UI，支援 RWD 響應式設計
- **批次操作** - 匯出/匯入規則與回應、批次刪除 (ADMIN 限定)
- **Excel 匯入** - 支援 Excel 批次匯入規則，可下載匯入範本
- **修訂紀錄** - 追蹤規則變更歷史，自動清理過期紀錄
- **統計監控** - 即時請求統計與命中率追蹤，自動刷新含閒置偵測
- **高效能** - 4K+ RPS (JSON)，2,000 條規則 XML/XPath 匹配比 WireMock 快 14 倍
- **權限控管** - Admin/User 角色分離，支援 LDAP 認證與內建帳號管理
- **內建帳號** - 帳號 CRUD、啟用/停用、密碼重設、忘記密碼、自助註冊
- **Remember Me** - 登入後長期記住，預設與 Session 同步（180 天）
- **規則保護** - 標記規則為受保護，防止被自動清除
- **規則展延** - 延長規則/回應的保留期限，避免被定時清除
- **孤兒清理** - 偵測並清除未被任何規則使用的孤兒回應
- **自動備份** - H2 資料庫排程備份、關機備份、手動觸發備份
- **狀態機場景** - WireMock 風格狀態機，模擬多步驟流程（如訂單→付款→確認）
- **故障注入** - 模擬連線重置、空回應，用於韌性測試
- **假資料產生** - 內建假資料 helpers（姓名、email、電話、地址等），產生擬真回應
- **規則測試** - 在管理介面直接測試規則匹配結果
- **靜態分析** - SpotBugs 靜態程式碼分析
- **零外部依賴** - 內嵌 H2 資料庫、Caffeine 快取
- **內網友善** - 前端使用 WebJars，無需 CDN
- **環境識別** - 協定別名、環境標籤，多環境部署一目了然

## 快速開始

### 環境需求

- Java 17+
- Gradle 8+ (或使用內建 wrapper)

### 啟動服務

```bash
# 開發模式
./gradlew dev

# 預設模式 (需登入 admin/admin)
./gradlew bootRun

# 建置 JAR
./gradlew bootJar
java -jar build/libs/echo-server-*.jar
```

### Docker 部署

```bash
# 建置並啟動
./gradlew bootJar
docker compose up -d

# 或直接拉取 (如有推送到 registry)
docker compose pull && docker compose up -d

# 查看日誌
docker compose logs -f

# 停止
docker compose down
```

環境變數配置：
| 變數 | 預設值 | 說明 |
|------|--------|------|
| `ECHO_ADMIN_USERNAME` | admin | 管理員帳號 |
| `ECHO_ADMIN_PASSWORD` | admin | 管理員密碼 |
| `ECHO_ENV_LABEL` | DOCKER | 環境標籤 |
| `TZ` | Asia/Taipei | 時區 |

JVM 參數在 Dockerfile 中設定（預設 `-Xms256m -Xmx512m`），可透過 docker-compose.yml 的 `environment` 加入 `JAVA_OPTS` 覆蓋。

### 存取服務

| 服務 | URL | 說明 |
|------|-----|------|
| 管理介面 | http://localhost:8080/ | Mock 規則管理 |
| 登入頁面 | http://localhost:8080/login.html | 使用者登入 |
| Mock 端點 | http://localhost:8080/mock/** | 攔截 HTTP 請求 |
| H2 Console | http://localhost:8080/h2-console | 資料庫管理 |

## 規則匹配優先順序

當多個規則符合同一請求時，系統依以下順序選擇：

### 排序優先順序（數字越大越優先）

1. **matchKey 精確度** - 精確路徑優先於萬用 `*`
2. **priority 欄位** - 數字越大越優先（預設 0）
3. **targetHost 精確度**（HTTP）- 有指定 host 優先於空值
4. **建立時間** - 較新的優先

### 匹配邏輯

1. **有條件且匹配** → 立即回傳
2. **無條件規則** → 記為備用，取排序後第一個
3. 最後回傳備用規則或 null

### 範例

| 規則 | targetHost | matchKey | priority | 條件 | 排序 |
|------|------------|----------|----------|------|------|
| A | api.com | /users | 10 | type=vip | 1 |
| B | api.com | /users | 10 | (無) | 2 |
| C | (空) | /users | 10 | (無) | 3 |
| D | api.com | * | 10 | (無) | 4 |
| E | api.com | /users | 1 | (無) | 5 |

- 請求 body 含 `type=vip` → 匹配 A
- 請求 body 不含 `type=vip` → 匹配 B

停用標籤內的規則不參與匹配。

## 標籤分類

標籤用於管理規則，方便依版本或功能分類：

- **JSON 格式** - 如 `{"env":"prod","team":"payment"}`
- **批次控制** - 依標籤批次啟用/停用規則（`PUT /api/admin/rules/tag/{key}/{value}/enable|disable`）
- **快速篩選** - 在規則頁面按標籤篩選，可切換分組檢視

## Mock 規則設定

### HTTP 規則

```json
{
  "protocol": "HTTP",
  "targetHost": "api.example.com",
  "matchKey": "/users",
  "method": "GET",
  "bodyCondition": "custId=K123",
  "queryCondition": "status=active",
  "responseBody": "{\"name\": \"VIP User\"}",
  "httpStatus": 200,
  "delayMs": 100
}
```

### JMS 規則

```json
{
  "protocol": "JMS",
  "queueName": "ORDER.QUEUE",
  "bodyCondition": "//OrderType=VIP",
  "responseBody": "<response><status>OK</status></response>",
  "delayMs": 50
}
```

## JMS 架構

Echo 可作為 JMS Proxy，在開發環境攔截 JMS 訊息：

```
應用服務 ──JMS──▶ Echo (Artemis)  ──JMS──▶ ESB (TIBCO/Artemis)
                 Queue: ECHO.REQUEST      Queue: TARGET.REQUEST
                 有匹配規則 → Mock Response
                 無匹配規則 → 轉發到 Target ESB
```

## 條件匹配語法

### HTTP Body 條件 (JSON)

| 語法 | 說明 | 範例 |
|------|------|------|
| `field=value` | 簡單欄位 | `userId=123` |
| `a.b.c=value` | 巢狀欄位 | `order.customer.id=VIP001` |
| `arr[0].field=value` | 陣列索引 | `items[0].sku=A001` |

### HTTP Query 條件

| 語法 | 說明 | 匹配請求 |
|------|------|---------|
| `status=active` | Query 參數 | `?status=active` |
| `id=123` | Query 參數 | `?id=123&other=x` |

### JMS Body 條件 (XML)

| 語法 | 說明 |
|------|------|
| `element=value` | 簡單元素 (自動轉 `//element`) |
| `//CustomerId=K123` | XPath 任意位置 |
| `/root/order/id=123` | XPath 絕對路徑 |

### 多條件

多條件用分號 `;` 分隔，全部符合才匹配 (AND 邏輯)：
- Body: `custId=K123;type=vip`
- Query: `status=active;page=1`
- Header: `X-Api-Key=abc123;Content-Type*=json`

### HTTP Header 條件

| 語法 | 說明 | 範例 |
|------|------|------|
| `Header=value` | 精確匹配（不區分大小寫） | `X-Api-Key=abc123` |
| `Header!=value` | 不等於 | `Accept!=text/xml` |
| `Header*=value` | 包含 | `Content-Type*=json` |
| `Header~=regex` | 正則匹配 | `Authorization~=Bearer.*` |

## 狀態機場景（Stateful Scenarios）

使用 WireMock 風格的狀態機模擬多步驟流程。每個 Scenario 有 `scenarioName` 和 `currentState`（預設 `Started`）。

規則透過三個欄位控制狀態：
- `scenarioName` — 綁定哪個狀態機
- `requiredScenarioState` — 匹配前提：狀態必須是這個值才會匹配
- `newScenarioState` — 匹配成功後，狀態轉移為這個值

### 範例：訂單流程

```bash
# 規則 1：Started 狀態下 GET → pending
curl -X POST http://localhost:8080/api/admin/rules -u admin:admin \
  -H 'Content-Type: application/json' -d '{
    "protocol":"HTTP", "matchKey":"/order/123", "method":"GET",
    "responseBody":"{\"status\":\"pending\"}", "status":200, "priority":10,
    "scenarioName":"order-flow", "requiredScenarioState":"Started", "newScenarioState":"Started"
  }'

# 規則 2：Started 狀態下 POST /pay → 狀態轉為 Paid
curl -X POST http://localhost:8080/api/admin/rules -u admin:admin \
  -H 'Content-Type: application/json' -d '{
    "protocol":"HTTP", "matchKey":"/order/123/pay", "method":"POST",
    "responseBody":"{\"result\":\"payment-ok\"}", "status":200, "priority":10,
    "scenarioName":"order-flow", "requiredScenarioState":"Started", "newScenarioState":"Paid"
  }'

# 規則 3：Paid 狀態下 GET → paid
curl -X POST http://localhost:8080/api/admin/rules -u admin:admin \
  -H 'Content-Type: application/json' -d '{
    "protocol":"HTTP", "matchKey":"/order/123", "method":"GET",
    "responseBody":"{\"status\":\"paid\"}", "status":200, "priority":10,
    "scenarioName":"order-flow", "requiredScenarioState":"Paid", "newScenarioState":"Paid"
  }'
```

```bash
curl http://localhost:8080/mock/order/123              # → {"status":"pending"}
curl -X POST http://localhost:8080/mock/order/123/pay  # → {"result":"payment-ok"}
curl http://localhost:8080/mock/order/123              # → {"status":"paid"}
```

### 重置狀態

```bash
# 重置單一 scenario
curl -X PUT http://localhost:8080/api/admin/scenarios/order-flow/reset -u admin:admin

# 重置所有 scenarios
curl -X PUT http://localhost:8080/api/admin/scenarios/reset -u admin:admin
```

## 使用方式

### 直接呼叫

```bash
# 有匹配規則 → 回傳 Mock 回應
curl http://localhost:8080/mock/api/users \
  -H "X-Original-Host: api.example.com"

# 無匹配規則 → Proxy 轉發到 google.com
curl http://localhost:8080/mock/search?q=test \
  -H "X-Original-Host: google.com"
```

### 透過 Nginx 代理

```nginx
location /api/ {
    proxy_set_header X-Original-Host api.example.com;
    proxy_pass http://echo-server:8080/mock/;
}
```

## 設定檔

### application.yml

```yaml
server:
  port: 8080
  servlet:
    session:
      timeout: 180d

echo:
  env-label:                    # 環境標籤（如 DEV、SIT、UAT）
  remember-me:
    key: echo-remember-me-secret  # Remember Me 加密金鑰
    validity: 180d                # Remember Me cookie 有效期
  admin:
    username: admin             # 管理員帳號
    password: admin             # 管理員密碼（支援 {bcrypt} 前綴）
  storage:
    mode: database              # database 或 file
  cache:
    body:
      max-size-mb: 50           # Response body 快取上限 (MB)
      threshold-kb: 5120        # 超過此大小的 body 不快取 (KB)
      expire-minutes: 720       # 快取過期時間 (分鐘)
    sync-interval-ms: 5000      # 多實例 cache 同步間隔 (毫秒)
  jms:
    enabled: false              # 設為 true 啟用 JMS
    port: 61616                 # Artemis 監聽埠
    queue: ECHO.REQUEST         # 監聽的 Queue
    endpoint-field: ServiceName # 從訊息 body 提取端點識別欄位
    target:
      enabled: false            # 設為 true 啟用轉發到 ESB
      type: tibco               # artemis 或 tibco
      server-url: tcp://esb-server:7222
      timeout-seconds: 30
      queue: TARGET.REQUEST     # 目標 Queue
  http:
    alias: HTTP                 # HTTP 協定顯示名稱
  stats:
    retention-days: 7           # 統計資料保留天數
  request-log:
    store: database             # memory 或 database
    max-records: 10000          # 最大記錄數
    include-body: true          # 是否記錄請求/回應 body
    max-body-size: 65536        # body 記錄上限 (bytes)
  audit:
    retention-days: 30          # 修訂紀錄保留天數
  cleanup:
    enabled: true               # 啟用定時清除
    cron: "0 0 3 * * *"         # 每天凌晨 3 點執行
    rule-retention-days: 180    # 規則保留天數
    response-retention-days: 180 # 回應保留天數
  backup:
    enabled: true               # 啟用 H2 自動備份
    cron: "0 0 3 * * *"         # 每天凌晨 3 點執行
    path: ./backups             # 備份目錄
    retention-days: 7           # 備份保留天數
    on-shutdown: true           # 應用關閉時備份
  builtin-account:
    self-registration: false    # 設為 true 允許自助註冊
  ldap:
    enabled: false
    url: ldap://ldap.example.com:389
    base-dn: dc=example,dc=com
    user-pattern: uid={0},ou=users
```

## 使用者認證

### 認證模式

| Profile | 說明 | 使用場景 |
|---------|------|----------|
| `dev` | 開發模式，免登入，啟用自助註冊 | 本地開發 |
| `default` | 需登入，預設帳號 admin/admin | 測試環境 |
| LDAP | LDAP 認證（設定 `echo.ldap.enabled=true`） | 正式環境 |

### 權限控管

| 角色 | 權限 |
|------|------|
| ADMIN | 系統設定、批次操作（匯出/匯入/全部刪除）、帳號管理 |
| USER | 管理 Mock 規則、查看統計與紀錄、修改自己的密碼 |
| 訪客 | 唯讀瀏覽規則、回應、統計、修訂紀錄（無需登入） |

### 內建帳號管理

ADMIN 可透過管理介面管理內建帳號：
- 建立/刪除帳號
- 啟用/停用帳號
- 重設密碼（產生臨時密碼，首次登入強制修改）
- 使用者可自行修改密碼（`PUT /api/account/change-password`）
- 忘記密碼請求（公開端點，含速率限制）
- 自助註冊（需啟用 `echo.builtin-account.self-registration`）

## API 端點

### 規則管理

| Method | Path | 說明 |
|--------|------|------|
| GET | /api/admin/rules | 列出所有規則 |
| GET | /api/admin/rules/{id} | 取得規則（含回應 body） |
| POST | /api/admin/rules | 建立規則 |
| PUT | /api/admin/rules/{id} | 更新規則 |
| DELETE | /api/admin/rules/{id} | 刪除規則 |
| POST | /api/admin/rules/{id}/test | 測試規則匹配 |
| PUT | /api/admin/rules/{id}/enable | 啟用規則 |
| PUT | /api/admin/rules/{id}/disable | 停用規則 |
| PUT | /api/admin/rules/{id}/protect | 保護規則 |
| PUT | /api/admin/rules/{id}/unprotect | 取消保護 |
| PUT | /api/admin/rules/{id}/extend | 展延規則 |
| PUT | /api/admin/rules/batch/enable | 批次啟用 |
| PUT | /api/admin/rules/batch/disable | 批次停用 |
| PUT | /api/admin/rules/batch/protect | 批次保護 |
| PUT | /api/admin/rules/batch/unprotect | 批次取消保護 |
| PUT | /api/admin/rules/batch/extend | 批次展延 |
| PUT | /api/admin/rules/tag/{key}/{value}/enable | 依標籤啟用 (ADMIN) |
| PUT | /api/admin/rules/tag/{key}/{value}/disable | 依標籤停用 (ADMIN) |
| GET | /api/admin/rules/export | 匯出全部規則 (ADMIN) |
| GET | /api/admin/rules/{id}/json | 匯出單筆規則 |
| POST | /api/admin/rules/import | 匯入單筆規則 (ADMIN) |
| POST | /api/admin/rules/import-batch | 批次匯入 (ADMIN) |
| POST | /api/admin/rules/import-excel | Excel 匯入 (ADMIN) |
| GET | /api/admin/rules/import-template | 下載 Excel 匯入範本 |
| DELETE | /api/admin/rules/batch | 批次刪除 (ADMIN) |
| DELETE | /api/admin/rules/all | 全部刪除 (ADMIN) |

### 回應管理

| Method | Path | 說明 |
|--------|------|------|
| GET | /api/admin/responses | 列出回應（支援 keyword 搜尋） |
| GET | /api/admin/responses/summary | 回應摘要（含使用數） |
| GET | /api/admin/responses/{id} | 取得回應 |
| GET | /api/admin/responses/{id}/rules | 使用此回應的規則 |
| POST | /api/admin/responses | 建立回應 |
| PUT | /api/admin/responses/{id} | 更新回應 |
| DELETE | /api/admin/responses/{id} | 刪除回應（連帶刪除關聯規則） |
| PUT | /api/admin/responses/{id}/extend | 展延回應 |
| PUT | /api/admin/responses/batch/extend | 批次展延回應 |
| GET | /api/admin/responses/orphan-count | 孤兒回應數量 |
| DELETE | /api/admin/responses/orphans | 刪除孤兒回應 |
| GET | /api/admin/responses/export | 匯出全部回應 (ADMIN) |
| POST | /api/admin/responses/import-batch | 批次匯入回應 (ADMIN) |
| DELETE | /api/admin/responses/batch | 批次刪除回應 (ADMIN) |
| DELETE | /api/admin/responses/all | 全部刪除回應 (ADMIN) |

### 紀錄查詢

| Method | Path | 說明 |
|--------|------|------|
| GET | /api/admin/logs | 請求紀錄（支援 ruleId/protocol/matched/endpoint 篩選） |
| GET | /api/admin/logs/summary | 請求紀錄摘要 |
| DELETE | /api/admin/logs/all | 刪除全部請求紀錄 (ADMIN) |
| GET | /api/admin/rules/{id}/audit | 單筆規則修訂紀錄 |
| GET | /api/admin/audit | 全部修訂紀錄 |
| DELETE | /api/admin/audit/all | 刪除全部修訂紀錄 (ADMIN) |

### 系統管理

| Method | Path | 說明 |
|--------|------|------|
| GET | /api/admin/status | 系統狀態（含 JVM、DB、統計） |
| GET | /api/admin/backup/status | 備份狀態與檔案列表 |
| POST | /api/admin/backup | 手動觸發備份 |

### 帳號管理

| Method | Path | 說明 |
|--------|------|------|
| GET | /api/admin/builtin-users | 列出內建帳號 (ADMIN) |
| POST | /api/admin/builtin-users | 建立帳號 (ADMIN) |
| PUT | /api/admin/builtin-users/{id}/enable | 啟用帳號 (ADMIN) |
| PUT | /api/admin/builtin-users/{id}/disable | 停用帳號 (ADMIN) |
| DELETE | /api/admin/builtin-users/{id} | 刪除帳號 (ADMIN) |
| POST | /api/admin/builtin-users/{id}/reset-password | 重設密碼 (ADMIN) |
| POST | /api/admin/builtin-users/forgot-password | 忘記密碼（公開） |
| POST | /api/admin/builtin-users/register | 自助註冊（公開，需啟用） |
| PUT | /api/account/change-password | 修改自己的密碼（已登入） |

### 狀態機管理

| Method | Path | 說明 |
|--------|------|------|
| PUT | /api/admin/scenarios/{name}/reset | 重置單一 Scenario 為 Started |
| PUT | /api/admin/scenarios/reset | 重置所有 Scenarios 為 Started |

### JMS 測試

| Method | Path | 說明 |
|--------|------|------|
| POST | /api/admin/jms/test | 發送測試訊息到 ECHO.REQUEST |

## 動態回應模板

WireMock 風格的 Handlebars 模板引擎，在回應內容中使用 `{{...}}` 語法產生動態內容。

### 基礎語法

```handlebars
{{request.path}}                              // 請求路徑
{{request.method}}                            // HTTP 方法
{{request.query.xxx}}                         // Query 參數
{{request.headers.xxx}}                       // Header 值
{{{request.body}}}                            // 請求 Body
{{now format='yyyy-MM-dd'}}                   // 格式化時間
{{randomValue type='UUID'}}                   // 隨機 UUID
{{randomValue length=8 type='ALPHANUMERIC'}}  // 隨機字串
```

### 條件與迴圈

```handlebars
{{#if (eq request.method 'POST')}}Created{{else}}Other{{/if}}
{{#each (split request.query.ids ',')}}{{this}}{{/each}}
```

比較運算子：`eq`, `ne`, `gt`, `lt`, `contains`, `matches`

### 假資料 Helpers

```handlebars
{{randomFirstName}}                           // 隨機名字
{{randomLastName}}                            // 隨機姓氏
{{randomFullName}}                            // 隨機全名
{{randomEmail}}                               // 隨機 email
{{randomPhoneNumber}}                         // 隨機電話 (xxx) xxx-xxxx
{{randomCity}}                                // 隨機城市
{{randomCountry}}                             // 隨機國家
{{randomStreetAddress}}                       // 隨機地址
{{randomInt min=1 max=100}}                   // 隨機整數
```

### JSONPath / XPath

```handlebars
{{jsonPath request.body '$.user.name'}}
{{xPath request.body '//name/text()'}}
```

## 多實例部署

Database 模式支援多實例部署，透過 DB 同步 cache 失效事件。

### 快取同步機制

每個實例使用本地 Caffeine 快取，透過 `cache_events` 表同步失效：

```
實例 A 修改規則 → 寫入 cache_events (RULE_CHANGED)
                          ↓
實例 B 每 N 秒輪詢 → 發現新事件 → 清除本地快取
```

- **輪詢間隔**: 預設 5 秒，可調整 `echo.cache.sync-interval-ms`
- **事件清理**: 每小時自動清理 10 分鐘前的舊事件
- **效能影響**: 極小，每次輪詢只是一個簡單的 timestamp 查詢

### 設定範例

```yaml
echo:
  storage:
    mode: database
  cache:
    sync-interval-ms: 10000  # 改為 10 秒 (預設 5000)

spring:
  datasource:
    url: jdbc:postgresql://db-host:5432/echo  # 共用資料庫
```

### 啟用條件

- `echo.storage.mode=database` → 啟用快取同步
- `echo.storage.mode=file` → 停用（單實例不需要）

## 效能測試

測試環境：macOS, Apple Silicon, Java 17, 20 並發執行緒, 每場景 10 秒。

### 規則數量影響（1,600 條規則）

| 指標 | 6 條規則 | 1,600 條規則 |
|------|---------|-------------|
| Cold cache 匹配 | 3 ms | 10 ms |
| Warm cache 匹配 | 3 ms | 6 ms |
| 10 次平均匹配 | — | 5.0 ms |

規則數量對匹配效能影響極小。規則以 `host + path + method` 為快取 key，只有同端點的候選規則會被遍歷，而非全部 1,600 條。

### XML Body 大小影響

| Body 大小 | XML 匹配 | JSON 匹配 | XML / JSON |
|-----------|----------|-----------|------------|
| ~1KB | 16.2 ms | 5.2 ms | 3.1x |
| ~10KB | 10.0 ms | 3.8 ms | 2.6x |
| ~50KB | 23.4 ms | 4.2 ms | 5.6x |
| ~100KB | 34.8 ms | 6.0 ms | 5.8x |

XML 匹配成本隨 body 大小線性增長（DOM 解析）。JSON 匹配不受 body 大小影響（~4-6ms），因 Jackson 的欄位查找為 O(1)。

### 快取機制

- **規則快取**: Caffeine, 10,000 entries, 12 小時過期
- **Response Body 快取**: 50MB 上限, 5MB 閾值, 12 小時過期
- 規則變更時自動失效

### 大量規則匹配（2,000 條規則，worst case）

測試條件：2,000 條 HTTP 規則含 XPath 條件（`//ServiceName=xxx;//CustId=yyy`），目標規則排序最後（worst case 全遍歷），10 併發，200 請求。

| 場景 | RPS | avg | p50 | p95 | p99 |
|------|-----|-----|-----|-----|-----|
| Echo XML（2,000 規則，XPath） | 434 | 22.7ms | 22.6ms | 34.8ms | 47.0ms |
| Echo JSON（2,000 規則，欄位匹配） | 1,066 | 9.3ms | 8.0ms | 24.8ms | 28.7ms |
| WireMock XML（2,000 規則，XPath） | 31 | 311.9ms | 311.3ms | 400.7ms | 427.0ms |

Echo 的 XML/XPath 匹配在大量規則下比 WireMock **快 14 倍**，歸功於簡單 XPath 模式的 `getElementsByTagName` 快速路徑與預編譯 `XPathExpression` 快取。JSON 匹配更快（1,066 RPS），因 Jackson 的欄位查找為 O(1) hash lookup。

### 執行壓力測試

```bash
# 單一場景匹配時間
python3 scripts/stress-test-scenario1.py

# 1,600 條規則影響測試
python3 scripts/stress-test-1600-rules.py

# XML vs JSON body 大小比較
python3 scripts/stress-test-xml-body.py

# RPS 吞吐量測試
python3 scripts/stress-test-rps.py [URL] [秒數] [並發數]

# Echo vs WireMock 比較（需要 libs/wiremock-standalone.jar）
python3 scripts/stress-test-vs-wiremock.py [ECHO_URL] [WM_URL] [秒數] [並發數]

# 2,000 條 JMS 規則匹配時間
python3 scripts/bench-2000-jms.py

# JMS 匹配壓力測試
python3 scripts/stress-test-jms-match.py [BASE_URL]

# 記憶體壓力測試
python3 scripts/stress-test-memory.py [BASE_URL]

# 快取隔離壓力測試
python3 scripts/stress-test-cache-isolation.py

# 匹配情境回歸測試（138 個案例）
python3 scripts/test-match-scenarios.py
```

## 測試

```bash
# 執行測試
./gradlew test

# 快速測試（別名）
./gradlew t

# 測試覆蓋率報告
./gradlew test jacocoTestReport
# 報告位置: build/reports/jacoco/test/html/index.html
```

## 技術棧

| 類別 | 技術 |
|------|------|
| Framework | Spring Boot 3.5.13 |
| Web Server | Undertow |
| Database | H2 (Embedded) |
| Cache | Caffeine |
| Messaging | Artemis (Embedded) |
| Security | Spring Security |
| Template | Handlebars 4.5 |
| JSON Path | JsonPath 2.10 |
| Excel | Apache POI |
| Static Analysis | SpotBugs |
| Frontend | Vue.js 3.5 + Bootstrap 5.3 + Bootstrap Icons 1.13 + CodeMirror 5 (WebJars) |
| Build | Gradle 8 |

## License

MIT License
