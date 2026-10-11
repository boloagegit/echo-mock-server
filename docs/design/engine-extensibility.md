# 設計：匹配引擎與協定擴充架構（Phase 2）

| 項目 | 內容 |
|------|------|
| 狀態 | 草案（待審） |
| 範圍 | 匹配條件模型、Matcher／Responder／Protocol SPI、舊資料相容與遷移步驟 |
| 不在範圍 | UI 視覺改版、新協定實作（gRPC、Kafka 等）、SDK／Testcontainers（Phase 3） |
| 前置 | Phase 1：Flyway 版控 schema、ArchUnit 邊界測試（本設計可先以 `ddl-auto=update` 新增可為空欄位起步） |

## 1. 背景與目標

Echo 的功能已涵蓋 HTTP／JMS mock、轉發、故障注入、SSE、Scenario 與匹配鏈分析，但核心邏輯缺少擴充點，導致：

1. **新增協定成本高**：`Protocol` 是封閉 enum；`ProtocolHandler` 實際上是 21 個方法的 CRUD／DTO 門面；有 18 個檔案依 `Protocol.HTTP/JMS` 或 `instanceof HttpRule/JmsRule` 分支（例如 `RuleApplyContractService` 寫死 `PROTOCOLS = List.of("HTTP", "JMS")`）。
2. **匹配表達力不足**：條件是以 `;` 串接的字串，僅支援 `=`、`!=`、`*=`、`~=` 四種運算子，且只能 AND（`ConditionMatcher.java` 的 `Op` enum）。無法表達 OR／NOT、欄位存在、數值比較、JSON 等值、路徑參數等。
3. **同一套選規則邏輯散落 5 處**：`AbstractMockPipeline.matchRule`、`JmsMockPipeline.matchRule`、`HttpRuleService.findMatchingHttpRuleInternal`、`JmsRuleService.matchJmsRules`、`LogAgent.analyzeMatchChain`。任何語意調整都要同步修改 5 份程式碼。
4. **回應行為寫死在 if/else**：`AbstractMockPipeline.executeAsync` 依序判斷 fault → forward → mock；SSE 播放則在 `UniversalMockController`（830 行）。新增 Webhook 等行為只能繼續往主流程塞。
5. **前後端各自解析條件字串，且結果不一致**（見 §4.4）。

### 目標

- **G1**：新增協定 = 新增一個 `ProtocolAdapter` 模組，不修改既有核心與其他協定的程式碼。
- **G2**：結構化、可組合、可擴充的匹配條件模型，涵蓋 WireMock／MockServer 常見的運算子。
- **G3**：回應行為（mock／forward／fault／sse／未來的 webhook）以 SPI 註冊，主流程不再依類型分支。
- **G4**：**零破壞相容**：既有資料、宣告式文件 `echo.mock/v1`、UI 操作習慣、請求紀錄的 `matchChain` 格式、效能基準都維持不變。
- **G5**：核心（`com.echo.core`）不依賴 Spring、JPA、Servlet，之後可抽成獨立的 Gradle 模組供嵌入式／SDK 使用。

### 非目標

- 本階段不搬移 `http_rules`／`jms_rules` 資料表到通用資料表（列為 §8 的開放決策）。
- 不移除舊的字串條件語法；它會持續可讀可寫，只是不再是唯一的表示方式。

## 2. 現況語意盤點（相容基準）

新模型必須能「位元等價」地重現以下舊行為。這份清單也是差異測試（§7）的規格來源。

### 2.1 條件字串解析（`ConditionMatcher.parseCondition`）

| 規則 | 行為 |
|------|------|
| 分隔 | 以 `;` 切割，無跳脫機制；值不能含 `;` |
| 運算子判定 | 找**第一個** `=`，看前一字元：`~`→MATCHES、`*`→CONTAINS、`!`→NOT_EQUAL，否則 EQUAL |
| 無 `=` | field = 整段字串、value = `""`、op = EQUAL |
| 引號 | 值若前後都有 `"`，比對前去除 |
| `==` | 僅在「點路徑 JSON」與串流 XML 規劃時先替換成 `=`；JSONPath（`$.`）不替換 |

### 2.2 Body 條件派送（`matchBodyPrepared`，依**執行期** body 型別決定）

| 條件前綴／body 型別 | 取值方式 | 欄位不存在時 |
|------|------|------|
| body 為 EMPTY 或 TOO_LARGE | — | 一律 false |
| `$.` 開頭 | JSONPath（Jayway，結果節點 `toString`） | **一律 false（`!=` 也是 false）** |
| `//` 開頭 | XPath（DOM；或 JMS 串流 XML 預先計算） | false |
| JSON body | 點路徑，可帶 `body.` 前綴與 `[n]` 索引；非字串節點用 `toString()` | 只有 `!=` 為 true |
| XML body | 元素名稱或路徑，轉成忽略 namespace 的 XPath；**任一節點**符合即 true | false |
| PLAIN body | `raw.contains(整段條件字串)`（含 `=` 與值） | — |

### 2.3 Query／Header 條件

- Query：以 `&` 切割原始 query string，**不做 URL decode**，取第一個同名參數；不存在時只有 `!=` 為 true。
- Header：名稱不分大小寫，取第一個；不存在時只有 `!=` 為 true；`headers` 為空時一律 false（包含 `!=`）。

### 2.4 通用限制

- 正則使用 `matches()`（全字串比對），pattern 上限 200 字元、輸入上限 10,000 字元、逾時 1 秒，失敗視為 false。
- 欄位值超過 `echo.matcher.max-field-value-length`（預設 2 MB）時跳過，視為 false。
- 同一請求內的評估結果以 `"body:"/"query:"/"header:" + 條件字串` 為 key，暫存在 `PreparedBody.matchOutcomes`，並交給 `LogAgent` 重用。

### 2.5 規則選擇語意

- **HTTP**：候選規則由 host + method 的快取集合依路徑過濾（`*`、精確、`/prefix/*`）；排序為：非 `*` 優先 → priority 高者優先 → 有 targetHost 優先 → createdAt 新者優先。依序評估：有條件且符合即命中；無條件者記為 fallback（取第一個）；最後回傳命中或 fallback。
- **JMS**：三層 fallback：有條件且符合 → 無條件的精確規則 → 無條件的 `*` 規則。
- **Scenario**：規則有 `requiredScenarioState` 且目前狀態不符時跳過，並在匹配鏈加入 `scenario_state_mismatch`。
- **匹配鏈分析**（非同步，`LogAgent`）：skipped／match／fallback／shadowed（只記第一個）／near-miss（部分條件通過，只記第一個）；全部不符則不記錄。

## 3. 目標架構總覽

```
                    ┌──────────────── com.echo.core（純 Java，無 Spring/JPA）──────────────────┐
 Protocol entry ──▶ │ MockEngine                                                              │
 (HTTP servlet,     │   1. RuleIndex.candidates(route)        ← ProtocolAdapter 提供          │
  JMS listener)     │   2. BodyPlan.prepare(request, leaves)  ← 依候選 Matcher 的需求規劃     │
                    │   3. SelectionPolicy.select(...)        ← 預設／JMS 三層 fallback       │
                    │        └ CompiledMatcher.evaluate(...)  ← MatcherFactory 註冊表         │
                    │   4. ScenarioGate / ScenarioTransition                                  │
                    │   5. Responder.respond(...) → ResponsePlan ← ResponderFactory 註冊表    │
                    │   6. ServeEvent → ServeEventListener*（請求紀錄、指標、未來的 Webhook） │
                    └─────────────────────────────────────────────────────────────────────────┘
                                  │ ResponsePlan
                                  ▼
                    ProtocolAdapter.writer() ── HTTP：status/headers/body、SSE 播放、連線重置
                                             └─ JMS：回覆訊息、轉發
 RuleStore SPI ──▶ JPA（沿用 http_rules / jms_rules）｜ 未來：檔案（GitOps）、In-memory（嵌入式）
```

套件規劃（先在同一個 Gradle 模組內，以 ArchUnit 鎖定邊界；穩定後再抽成 `echo-core` 模組）：

```
com.echo.core.model      RuleDefinition, RouteSpec, MatchSpec, ResponseSpec, ProtocolId
com.echo.core.match      Matcher SPI、內建 matchers、LegacyConditionCompiler、BodyPlan
com.echo.core.respond    Responder SPI、ResponsePlan
com.echo.core.engine     MockEngine, RuleIndex, SelectionPolicy, ServeEvent
com.echo.core.spi        ProtocolAdapter, RuleStore, ServeEventListener
com.echo.protocol.http   HttpProtocolAdapter, HttpRuleIndex, HttpResponseWriter, SsePlayback
com.echo.protocol.jms    JmsProtocolAdapter, JmsSelectionPolicy, JmsResponseWriter
com.echo.persistence     JPA entities、LegacyRuleMapper（entity ⇄ RuleDefinition）
```

ArchUnit 規則（Phase 1 先建立）：

- `com.echo.core..` 不得依賴 `org.springframework..`、`jakarta.persistence..`、`jakarta.servlet..`、`com.echo.service..`、`com.echo.entity..`。
- `com.echo.protocol.<x>..` 之間不得互相依賴。
- `com.echo.controller..` 不得直接依賴 `com.echo.repository..`。

## 4. 結構化匹配模型

### 4.1 JSON 表示（`MatchSpec`）

節點分為**組合節點**與**葉節點**：

```jsonc
{
  "all": [                                        // 組合：all | any | not
    { "source": "header", "name": "X-Tenant", "op": "equals", "value": "acme",
      "options": { "caseInsensitive": true } },
    { "any": [
        { "source": "body", "selector": { "jsonPath": "$.order.amount" }, "op": "gte", "value": 1000 },
        { "source": "query", "name": "vip", "op": "exists" }
    ]},
    { "not": { "source": "body", "selector": { "xpath": "//Status" }, "op": "equals", "value": "CANCELLED" } }
  ]
}
```

葉節點欄位：

| 欄位 | 說明 |
|------|------|
| `source` | `body`、`query`、`header`、`cookie`、`path`、`pathParam`、`method`；協定擴充來源以命名空間表示，例如 `jms.property`、`jms.header`。協定透過 `ProtocolDescriptor.sources()` 宣告支援哪些來源 |
| `name` | query／header／cookie／pathParam／property 的名稱 |
| `selector` | body 用：`{"jsonPath"}`、`{"xpath"}`、`{"field"}`（點路徑）、`{"raw": true}`、`{"legacy": "<原始條件>"}`（見 §4.3） |
| `op` | 見下表 |
| `value` | 字串、數字、布林、陣列（`in`）或物件（`equalToJson`／`matchesJsonSchema`） |
| `quantifier` | 取到多個值時：`any`（預設，與舊 XML 行為一致）或 `all` |
| `onMissing` | `fail`、`pass`、`auto`（預設：`notEquals`／`absent` 時 pass，其餘 fail） |
| `options` | `caseInsensitive`、`urlDecode`、`ignoreArrayOrder`、`ignoreExtraElements` 等，由各 op 宣告支援的選項 |

運算子（第一批內建）：

| op | 說明 | 舊語法對應 |
|----|------|------|
| `equals` / `notEquals` | 字串等值 | `=` / `!=` |
| `contains` | 子字串 | `*=` |
| `matches` | 正則（沿用 200／10,000／1 秒限制） | `~=` |
| `exists` / `absent` | 欄位存在與否 | 無 |
| `gt` `gte` `lt` `lte` | 數值比較（BigDecimal；非數值視為 false） | 無 |
| `in` | 值屬於清單 | 無 |
| `equalToJson` | JSON 結構等值，可設 `ignoreArrayOrder`、`ignoreExtraElements` | 無 |
| `matchesJsonSchema` | JSON Schema 驗證（需新增依賴，見 §8） | 無 |

**向前相容**：未知的 `op`、`source`、`selector` 在**儲存時**就驗證失敗（422），不會留到執行期才靜默變成 false。

### 4.2 Java SPI

```java
/** 由 MatcherRegistry 依 op 名稱查找；內建與外掛都走同一條路。 */
public interface PredicateFactory {
    String op();
    Set<String> supportedOptions();
    CompiledPredicate compile(JsonNode value, Map<String, JsonNode> options) throws InvalidMatchSpecException;
}

public interface CompiledPredicate {
    boolean test(ExtractedValue actual);          // 已處理 NULL／MISSING 之外的實際值
}

/** 從請求取值；body selector 另外宣告 BodyRequirement，供 BodyPlan 規劃解析方式。 */
public interface ValueExtractor {
    Extracted extract(RequestView request, PreparedBody body);   // FOUND(values) | NULL | MISSING
    BodyRequirement bodyRequirement();                           // NONE | RAW | JSON | XML_DOM | XML_STREAM(selector)
}

/** 編譯後的不可變匹配樹；fast path 只回傳布林，explain path 回傳細節。 */
public interface CompiledMatcher {
    boolean matches(EvalContext ctx);
    MatchExplanation explain(EvalContext ctx);   // 給匹配鏈分析與「測試規則」使用
    String cacheKey();                           // 規範化序列化，作為同一請求的 memo key
    List<CompiledLeaf> leaves();                 // 給 BodyPlan 與 near-miss 計分
}
```

規則編譯時機：規則載入索引（§5.2）時編譯一次，與規則版本綁定；執行期不再解析字串。

### 4.3 舊語法的相容策略

1. **`LegacyConditionCompiler`** 把三個舊欄位轉成 `all[...]`。每個以 `;` 切出的片段：
   - `queryCondition` 片段 → `{source: query, name, op, value, onMissing: auto}`，並帶 `options.legacyQuery = true`（不 decode、取第一個）。
   - `headerCondition` 片段 → `{source: header, ...}`，並帶 `options.legacyEmptyHeadersFail = true`，重現 §2.3「headers 為空時 `!=` 也是 false」。
   - `bodyCondition` 片段 → **`{source: body, selector: {legacy: "<原片段>"}}`**。`legacy` selector 原封不動地實作 §2.2 的執行期派送（JSONPath／XPath／點路徑／XML 元素／PLAIN contains），包含各分支在欄位不存在時的不同行為。

   body 條件不嘗試「靜態推斷」成 `jsonPath`／`xpath`，因為舊語意取決於**執行期**的 body 型別（同一條件遇到 JSON 與 XML 會走不同路徑）。只有使用者在 UI 編輯該條件時，才提示升級成明確的 selector。

2. **反向轉換**：`MatchSpec` 若只由「all + 舊語法可表達的葉節點」組成，就能無損還原成三個舊字串。這決定 v1 API 與舊 UI 能不能顯示或編輯該規則（§6）。

3. **memo key 相容**：legacy 葉節點的 `cacheKey()` 維持 `"body:" + 片段`、`"query:" + 片段`、`"header:" + 片段`，讓 `PreparedBody.matchOutcomes` 與 `LogAgent` 的重用機制不需改動。

### 4.4 需要先定案的既有不一致

| # | 現象 | 建議 |
|---|------|------|
| 1 | 前端 `useRuleForm.js` 依 `!=`、`*=`、`~=`、`=` 的順序找運算子（任意位置）；後端看**第一個** `=`。`a=x!=y` 在前端解析成 field `a=x`、op `!=`，後端則是 field `a`、value `x!=y` | 以後端為準。新增 `POST /api/admin/match/parse`（或在 RuleDto 附上結構化條件），前端不再自行解析字串 |
| 2 | 前端儲存時會濾掉 value 為空的條件（`c.field && c.value`），所以 `field=` 無法透過表單儲存 | 新模型以 `exists`／`equals ""` 明確表達；舊表單行為維持不變 |
| 3 | `$.a==b` 在 JSONPath 分支不會替換 `==`，實際比對的是 value `"=b"` | 保留舊行為（相容），在條件驗證時提出警告 |
| 4 | JSONPath 欄位不存在時 `!=` 為 false，點路徑則為 true | 保留舊行為；新模型以 `onMissing` 明確表達 |

### 4.5 Body 解析規劃（`BodyPlan`）

保留目前的效能特性（只在有 body 條件時解析、JMS 大型 XML 走串流）：

- 引擎蒐集候選規則所有葉節點的 `BodyRequirement`：全是 `NONE` → `rawOnly`；有 `JSON` → 解析一次 Jackson tree；全部是 `XML_STREAM` → 單次 StAX 掃描、預先填入結果（取代現在的 `streamingConditions`）；任一需要 `XML_DOM` → 建一次 DOM 給所有規則共用。
- 「是否可串流」由 selector 自己宣告，不再由 `ConditionMatcher` 用字串規則判斷。

### 4.6 匹配鏈與 near-miss

- `explain()` 回傳樹狀結果：每個葉節點包含 expected、actual、passed。
- 計分：以**根節點的直接子節點**計算 `passed/total`（舊規則就是一個扁平的 `all`，結果與現在的 `score()` 相同）。
- `MatchChainEntry` 原有欄位與 JSON 格式不變；新增可選欄位 `detailTree`。前端只在有值時顯示。

## 5. 引擎、協定與回應 SPI

### 5.1 ProtocolAdapter

```java
public record ProtocolId(String value) {                 // 取代 enum；DB 欄位本來就以字串儲存
    public static final ProtocolId HTTP = new ProtocolId("HTTP");
    public static final ProtocolId JMS  = new ProtocolId("JMS");
}

public interface ProtocolAdapter {
    ProtocolId id();
    ProtocolDescriptor descriptor();     // 顯示名稱、route 欄位 schema、支援的 sources／responders、是否啟用
    RouteCodec routeCodec();             // 驗證與正規化 route（HTTP：host/method/path；JMS：queue/endpoint）
    RuleIndex buildIndex(List<CompiledRule> rules);   // 候選查詢與排序（precedence）
    SelectionPolicy selectionPolicy();   // 預設：條件命中 → 第一個無條件；JMS 覆寫為三層
    ResponseWriter responseWriter();     // 把 ResponsePlan 寫回該協定
}
```

`RuleApplyContractService`、`RuleQueryService`、`CacheInvalidationService`、`AdminController` 中依協定的分支，改為查詢 `ProtocolRegistry` 與 `ProtocolDescriptor`。例如合約 schema 的 `PROTOCOLS` 改為 `registry.ids()`；HTTP 專屬欄位改由 `descriptor.routeSchema()` 提供。

### 5.2 RuleIndex 與快取

- 以**不可變快照**取代目前以 host/method、queue 為 key 的 Caffeine 規則快取：規則變更（或收到 `CacheInvalidationService` 的多實例事件）時，重建該協定的 `RuleIndex` 並以 `AtomicReference` 原子替換。
- 快照中保存的是 `CompiledRule`（不可變，不是 JPA entity），所以背景執行緒（`LogAgent`）可以直接使用，不再需要 `CandidateSnapshot` 深拷貝。
- HTTP 索引：精確路徑用 `Map`、`/prefix/*` 用前綴樹、`*` 獨立一份清單；排序規則與 §2.5 完全相同，並以既有的 1,600 條規則壓測驗證重建成本。
- 路徑樣板（`/users/{id}`）屬於 route 層，放在 Phase 2 後段：`HttpRouteSpec.path` 新增 `template` 型態，抽出的參數提供給 `pathParam` 來源與 Handlebars（`{{request.pathParams.id}}`）。

### 5.3 SelectionPolicy

把 §2.5 的 5 份重複實作收斂成：

```java
public interface SelectionPolicy {
    Selection select(List<CompiledRule> orderedCandidates, EvalContext ctx, ScenarioGate gate);
}
```

- `DefaultSelectionPolicy`（HTTP）：重現「條件命中 → 第一個無條件 fallback」。
- `JmsSelectionPolicy`：重現三層 fallback。
- `HttpRuleService.findMatchingHttpRuleInternal`、`JmsRuleService.matchJmsRules` 改為委派給 policy（或在確認沒有呼叫端後刪除）；`LogAgent` 的分析改用同一份 `CompiledMatcher.explain()`。

### 5.4 Responder 與 ResponsePlan

```java
public interface ResponderFactory {
    String type();                                   // mock | forward | fault | sse | (webhook)
    Set<ProtocolId> supportedProtocols();            // 例如 sse 只支援 HTTP；儲存時驗證
    Responder create(JsonNode spec);
}

public interface Responder {
    CompletionStage<ResponsePlan> respond(ResponseContext ctx);   // 包含模板渲染、轉發等
}

public sealed interface ResponsePlan
        permits ResponsePlan.Content, ResponsePlan.Stream, ResponsePlan.Fault, ResponsePlan.Forwarded {}
```

- `ResponseSpec` 的形式為 `{ "type": "mock", ... }`；舊欄位由 `LegacyRuleMapper` 依現行優先序轉換：`faultType != NONE` → `fault`；`action = FORWARD` → `forward`；HTTP 且 `sseEnabled` → `sse`；其他 → `mock`。
- 延遲（`delayMs`／`maxDelayMs`）是規則層級的屬性，由引擎統一套用，不屬於個別 responder。
- **SSE 播放**從 `UniversalMockController` 移到 `com.echo.protocol.http.SsePlayback`，由 `HttpResponseWriter` 處理 `ResponsePlan.Stream`；控制器只負責接收請求並交給引擎。
- Scenario 狀態轉移維持在引擎主流程中（會影響後續匹配，不能非同步），不做成 listener。

### 5.5 ServeEventListener

```java
public interface ServeEventListener {
    void onServed(ServeEvent event);   // 不可阻塞；需要 I/O 的實作自行排入佇列
}
```

- 請求紀錄（`RequestLogService.record` 目前有 20 多個參數）改為接收一個 `ServeEvent`；JMS 在紀錄不可用時必須讓遞送失敗的語意保留，因此請求紀錄 listener 標記為 `critical`，例外會往上傳。
- Phase 3 的 Webhook、Prometheus 指標、請求驗證 API 都以 listener 的形式掛上。

## 6. API 與儲存

### 6.1 宣告式文件

- `echo.mock/v1`：完全不變。讀取時，規則若無法以舊字串表達（使用了 `any`／`not`／新 op），回傳 **409** 並說明需改用 v2，絕不靜默丟棄條件。
- `echo.mock/v2`（新增）：

```yaml
apiVersion: echo.mock/v2
kind: Rule
metadata: { id: ..., resourceVersion: 3, tags: { team: payment } }
spec:
  protocol: HTTP
  route:    { host: api.example.com, method: POST, path: /orders }
  match:    { all: [ ... ] }            # §4.1
  response: { type: mock, status: 201, headers: {...}, responseId: 42 }
  behavior: { priority: 10, delayMs: 100, maxDelayMs: 300,
              scenario: { name: order, requiredState: Started, newState: Paid } }
```

v1 與 v2 透過同一個 `RuleDefinition` 雙向轉換；`/rules/schema` 提供兩個版本的 schema。

### 6.2 儲存

- `http_rules`、`jms_rules` 各新增可為空的 `match_spec`（`Length.LONG32`，與現有長文字欄位相同，7 種資料庫都已驗證可用）。
- 讀取：`match_spec` 有值時以它為準；否則由三個舊欄位即時編譯。
- 寫入：可表達成舊語法時，同時寫入舊欄位（舊版 UI、匯出、Excel 都能繼續使用）與 `match_spec`；不可表達時，舊欄位寫入 `null`，`match_spec` 為唯一來源，並在 DTO 標記 `advancedMatch = true`。
- 匯出／匯入（JSON、Excel）：JSON 匯出帶 `matchSpec`；Excel 範本維持舊欄位，遇到進階規則時在該列標註「需使用 JSON 匯出」。

### 6.3 UI

- 現有條件列（type／field／operator／value）就是扁平 `all` 的視覺化，保持原樣與欄位順序（遵守 PRODUCT.md 的「保留肌肉記憶」）。
- 新增「進階」切換：顯示樹狀編輯器（群組 any／all／not），以及新 op（exists、數值比較、in）與 selector 型態下拉選單。
- 前端改用後端回傳的結構化條件，不再自行解析字串（解決 §4.4 #1）。

## 7. 驗證與遷移步驟

### 7.1 品質關卡

1. **差異測試（必要）**：舊的 `ConditionMatcher` 保留為測試 oracle。以 jqwik 產生條件字串（含 `;`、`==`、引號、各前綴、缺漏欄位）與 body（JSON、XML 含 namespace、PLAIN、空字串、超大），驗證 `legacy.matchesPrepared(...) == compiled.matches(...)`，`explain()` 的 score 也必須相同。
2. **選擇語意測試**：對 HTTP／JMS 各建立固定的規則集（含 priority、targetHost、`*`、Scenario、disabled），比對新舊 `matchRule` 選中的規則 ID 與匹配鏈 JSON。
3. **效能關卡**：`scripts/stress-test-rps.py`、`stress-test-1600-rules.py`、`bench-2000-jms.py`、`stress-test-xml-body.py`。p50／p99 退步不得超過 5%，配置量（JFR allocation）不得明顯上升。
4. **前端契約**：既有的 `src/test/java/com/echo/frontend` 與 `src/test/js` 測試全部通過。

### 7.2 PR 拆分（每一步都可獨立上線、可回退）

| # | 內容 | 對外行為 |
|---|------|------|
| 1 | ArchUnit 規則、`com.echo.core` 骨架、`MatchSpec` 模型、內建 op 與組合節點、單元測試 | 無 |
| 2 | `LegacyConditionCompiler` + `legacy` selector + 差異測試 | 無 |
| 3 | `BodyPlan`、`CompiledMatcher` 接入 pipeline 與 `LogAgent`，以 `echo.engine.matcher=legacy\|compiled` 切換（預設 legacy）；跑效能關卡後改預設為 compiled | 無（可切回） |
| 4 | `SelectionPolicy` 收斂 5 份選擇邏輯；`RuleIndex` 不可變快照 | 無 |
| 5 | `Responder`／`ResponsePlan`，SSE 播放移出控制器；`ServeEventListener` 與請求紀錄改用 `ServeEvent` | 無 |
| 6 | `ProtocolId`、`ProtocolAdapter`、`ProtocolRegistry`，移除核心以外的協定分支 | 無 |
| 7 | `match_spec` 欄位、v2 文件、新 op（exists／absent／數值／in／equalToJson）、`any`／`not` | **新功能** |
| 8 | UI 進階條件編輯器、`/match/parse` 端點、前端改用結構化條件 | **新功能** |
| 9 | 移除 `echo.engine.matcher=legacy` 開關與舊實作（至少間隔一個正式版本） | 無 |

PR 1～6 為純重構，屬於「先讓架構可擴充，再加功能」；PR 7 起才是使用者看得到的變更。

### 7.3 風險

| 風險 | 緩解 |
|------|------|
| 語意漂移（尤其是 §2.2 的執行期派送與缺漏欄位行為） | `legacy` selector 原樣搬移演算法，加上差異測試；功能開關可立即切回 |
| 樹狀評估造成額外配置、降低吞吐量 | fast path 只回傳布林；葉節點與 predicate 預先編譯；效能關卡把關 |
| JMS 串流 XML 的記憶體特性改變 | `XML_STREAM` 需求由 selector 宣告，維持「全部可串流才串流」的條件；`JmsMessageMemoryBudget` 不變 |
| 多實例快取一致性 | 沿用 `CacheInvalidationService` 事件，只把「清除 Caffeine」換成「重建快照」 |
| 7 種資料庫的新欄位 | 可為空的 `LONG32` 欄位；以 `scripts/test-rdbms-matrix.py` 驗證；Phase 1 的 Flyway 完成後改用版控 migration |

## 8. 待決策事項

1. **儲存的最終形態**：長期是否將 `http_rules`／`jms_rules` 合併為通用的 `rules` 表（`protocol`、`route_key`、`route_spec`、`match_spec`、`response_spec`）？建議：新協定直接使用通用表；HTTP／JMS 等 Flyway 上線、有正式 migration 後再評估是否搬移。
2. **`matchesJsonSchema` 的依賴**：需要引入 JSON Schema 驗證函式庫（例如 networknt `json-schema-validator`）。建議放在 PR 7 之後，視需求再加入。
3. **`echo-core` 抽成獨立 Gradle 模組的時機**：建議 PR 6 完成、ArchUnit 規則穩定後立即進行，作為 Phase 3 嵌入式模式、Java SDK、Testcontainers 的前提。
4. **舊字串語法的定位**：建議永久保留讀寫（相容與快速輸入），但新功能只出現在結構化模型中。
5. **v2 文件命名**：`echo.mock/v2` 或沿用 v1 加上可選的 `spec.match`？建議採用 v2，讓 v1 的語意保持凍結、容易說明。
