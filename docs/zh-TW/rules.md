# 規則與匹配

[← 文件目錄](README.md)

請求怎麼找到規則、條件語法、狀態機場景與回應模板。

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
  "status": 200,
  "delayMs": 100
}
```

### JMS 規則

```json
{
  "protocol": "JMS",
  "matchKey": "ORDER.QUEUE",
  "bodyCondition": "//OrderType=VIP",
  "responseBody": "<response><status>OK</status></response>",
  "delayMs": 50
}
```

JMS 規則的 `matchKey` 就是佇列名稱；`status` 是 HTTP 回覆的狀態碼。

## 跨環境搬移規則（JSON）

兩端啟用 `ECHO_BULK_IMPORT_EXPORT_ENABLED=true`，並以 ADMIN 登入。
在來源的「規則」頁資料選單匯出 JSON；到目的環境選「匯入 → JSON」，
選擇檔案、檢查預覽，再確認新增。單筆規則的 JSON 匯出也可使用；來源端需啟用要匯出的協定。

- 匯出包含完整回應本文、結構化條件與 SSE 設定，不包含連線密碼、伺服器設定、請求歷史或執行中的場景／標籤狀態。回應本文與標頭可能含敏感資料，請妥善保管檔案。
- 每次匯入都建立**新規則 ID 與本地的新回應**，不會依來源 ID 覆寫既有資料。同一檔案中共用回應的規則，會共用一份新複製的回應。重複匯入同一檔案會再新增一批。
- 建立／修改時間與操作人員改由本環境設定，捨棄來源的建立時間與延長紀錄；保留期限從匯入時起，依**目的環境**的規則／回應保留天數重新計算。保護狀態保留，因此受保護規則仍不會被自動清理。
- 保留來源的啟用／停用狀態。啟用規則在交易完成後立即參與比對，仍受目的環境的場景／標籤設定影響。相同路徑、優先序及較新的建立時間可能改變命中結果，不能保證不同環境有完全相同的路由與比對結果。
- 轉發規則必須在預覽中明確選擇目的環境連線，不會用來源的數字 ID 或名稱自動對應。本地既有連線、預設值與憑證不會被修改；請事先建立所需連線。選擇「本環境預設連線」後，未來會跟隨預設連線的變更；HTTP 的 `X-Original-Host` 則是需明確選取的動態目的地。
- JMS 同時存在已啟用的 YAML 目標與資料庫預設連線時，依既有執行語意優先使用 YAML；若要使用資料庫連線，請明確選取該連線。
- 預覽不寫入資料，也不執行連線測試。確認時會重新驗證選項；選取的連線或預設值已變更時，需重新預覽。協定停用、回應內容不完整、規則無效或儲存失敗，都會拒絕／回復整批 JSON，不留下半批資料。
- 請核對依環境而異的 HTTP `targetHost`／路徑及 JMS `matchKey`（佇列名稱）；這些值原樣複製，目的環境佇列名稱不同時需調整規則。
- 獨立環境需使用不同資料庫或 schema，以及獨立的 JMS 收件佇列／broker。共用資料庫的實例會共用匯入結果；共用 broker 同一佇列的消費者會競爭訊息。匯出匯入仍會消耗資料庫與程式資源，不代表完全沒有負載。

舊版僅含 `responseId`、缺少回應本文的匯出檔會被拒絕，請從更新後的來源重新匯出，
不要手動補上 `exportVersion` 繞過檢查。若確認時網路中斷、收不到結果，請先核對目的環境的規則清單；
交易可能已完成，再次匯入會重複新增。

這個搬移流程使用 JSON 完整來回保存規則。Excel 與 OpenAPI 是依各自格式建立規則，
不能當成既有規則或環境設定的完整備份。

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
| `order/id=123` | 相對路徑：父元素是 `order` 的任何 `id` |
| `//item/@sku=A1` | 屬性值 |

這類簡單路徑以元素與屬性的 **local name** 比對，所以 `CustomerId` 也會符合 `<ns:CustomerId>`；`==` 視為 `=`。HTTP 與 JMS、以及進階條件的 `xpath` selector 都用同一套規則；條件都是簡單路徑時，XML body 只串流掃描一次、不建立 DOM。含 predicate、函式、萬用字元或帶前綴名稱的路徑（例如 `//item[1]`、`//ns:item`）照常使用 XPath。

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

### 進階條件（`matchSpec`）

`;` 串接的「而且」條件不夠用時，規則可以在 `matchSpec` 寫結構化條件（REST API、JSON 匯入匯出，
或 `echo.mock/v2` 文件的 `spec.match`）。支援 `all`／`any`／`not` 組合，以及運算子：`equals`、`notEquals`、
`contains`、`matches`、`exists`、`absent`、`gt`／`gte`／`lt`／`lte`（數值）、`in`、`equalToJson`。

```json
{
  "protocol": "HTTP", "matchKey": "/orders", "method": "POST", "responseBody": "{\"tier\":\"gold\"}",
  "matchSpec": {"all": [
    {"source": "header", "name": "X-Tenant", "op": "equals", "value": "acme"},
    {"any": [
      {"source": "body", "selector": {"jsonPath": "$.amount"}, "op": "gte", "value": 1000},
      {"source": "query", "name": "vip", "op": "exists"}
    ]},
    {"not": {"source": "body", "selector": {"jsonPath": "$.status"}, "op": "equals", "value": "CANCELLED"}}
  ]}
}
```

- 取值來源：HTTP 規則可用 `body`、`query`、`header`；JMS 規則可用 `body`（selector：`jsonPath`、`xpath`、`field`、`raw`）。
- 條件不合法時，儲存就會被拒絕並指出位置（例如 `$.all[1].any[0].op`）。上限：64 KiB、16 層、256 個節點、`in` 最多 1,000 個值。
- `matchSpec` 與 `bodyCondition`／`queryCondition`／`headerCondition` 互斥；只由 `{"legacy": {"source": ..., "condition": ...}}`
  片段組成的 `all` 會存成舊欄位。
- 規則編輯畫面的條件區可以從「簡易」切到「進階」，用群組、NOT 與新運算子組合條件；原本的簡易條件會變成舊格式片段，
  比對方式完全不變。只剩舊格式片段時可以直接切回簡易模式，否則會先確認是否清除進階條件。編輯時會即時向後端檢查，並指出出錯的位置。
- 請求中沒有 `matchSpec` 也沒有舊版條件時會保留規則原本的結構化條件（避免舊的呼叫端誤刪）；要清除請送 `{"all": []}`。

## 狀態機場景（Stateful Scenarios）

規則可透過 `scenarioName` 加入指定狀態機，只在 `requiredScenarioState`
符合時命中，並在命中後切換至 `newScenarioState`。每個 Scenario 的初始狀態都是
`Started`。

```json
{
  "protocol": "HTTP",
  "matchKey": "/orders/123/pay",
  "method": "POST",
  "scenarioName": "order-flow",
  "requiredScenarioState": "Started",
  "newScenarioState": "Paid",
  "responseBody": "{\"result\":\"payment-ok\"}"
}
```

使用 `PUT /api/admin/scenarios/{name}/reset` 重置單一 Scenario，或使用
`PUT /api/admin/scenarios/reset` 重置全部 Scenario。

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

### JSONPath / XPath

```handlebars
{{jsonPath request.body '$.user.name'}}
{{xPath request.body '//name/text()'}}
```

### HTTP 與 JMS

各協定共用同一套模板：HTTP 回應、SSE 事件與 JMS 回覆都能使用。內容沒有 `{{` 時原樣回傳，不增加任何成本。

| 變數 | HTTP | JMS |
|------|------|-----|
| `request.body` | 請求內容 | 訊息內容 |
| `request.headers.xxx` | 請求標頭 | `JMSMessageID`、`JMSCorrelationID`、`JMSType`、`JMSDestination`（監聽的 queue）與訊息上的自訂屬性 |
| `request.path` | 請求路徑 | queue，有端點欄位值時一併附上（`ECHO.REQUEST \| Order`） |
| `request.method`、`request.query.xxx` | 方法、query 參數 | 空值 |

```handlebars
<reply><orderId>{{xPath request.body '//orderId/text()'}}</orderId><ref>{{request.headers.JMSCorrelationID}}</ref></reply>
```
