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
com.echo.core.match      Matcher SPI、內建 matchers、LegacyConditions（舊語法相容層）、BodyPlan
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

已於步驟 1 實作（`com.echo.core.match`）：

```java
/** 運算子擴充點；內建運算子見 BuiltinPredicates。 */
public interface PredicateFactory {
    String op();
    default Set<String> supportedOptions() { return Set.of(); }
    default boolean requiresValue() { return true; }       // exists／absent 為 false
    default boolean passesWhenMissing() { return false; }  // onMissing=auto 時取不到值的結果
    default boolean allowsOnMissing() { return true; }
    CompiledPredicate compile(JsonNode expected, Map<String, JsonNode> options, MatcherLimits limits);
}

public interface CompiledPredicate {
    boolean test(Value actual);        // Value = 文字形式 + 原始 JSON 節點（若有）
}

/** 取值來源擴充點；內建 body／query／header／method／path，協定可註冊例如 jms.property。 */
public interface ValueSourceFactory {
    String source();
    boolean requiresName();
    Set<Selector.Kind> supportedSelectors();
    ValueExtractor create(String name, Selector selector, MatcherLimits limits);
}

public interface ValueExtractor {
    Extracted extract(RequestView request);   // FOUND(values) | MISSING | UNAVAILABLE
}

/** 編譯後的不可變匹配樹；fast path 只回傳布林，explain path 回傳細節。 */
public interface CompiledMatcher {
    boolean matches(EvalContext ctx);
    MatchExplanation explain(EvalContext ctx);   // 給匹配鏈分析與「測試規則」使用
    String cacheKey();                           // 規範化 JSON，作為同一請求的 memo key
    default boolean isUnconditional() { ... }    // 空的 all，規則選擇時視為 fallback
}
```

`MatcherRegistry` 註冊運算子與來源，`MatcherCompiler` 依註冊表檢查並編譯，所有錯誤以
`InvalidMatchSpecException`（含 `$.all[1].op` 形式的位置）在儲存時回報。

取值的三種狀態：

| 狀態 | 意義 | 判斷 |
|------|------|------|
| `FOUND` | 取到一個以上的值 | 依 `quantifier` 對每個值套用運算子 |
| `MISSING` | 欄位不存在 | 依 `onMissing`；`auto` 時由運算子決定 |
| `UNAVAILABLE` | 無法判斷（body 過大、值都超過長度上限） | 一律不符合（外層 `not` 會反轉為符合） |

`ValueExtractor` 目前直接讀 `RequestView.body()`（`BodyView`，延遲解析 JSON／XML）。步驟 3 再加入
`BodyRequirement` 讓 `BodyPlan` 規劃解析方式（例如 JMS 串流 XML），以及 `leaves()` 供計分使用。

規則編譯時機：規則載入索引（§5.2）時編譯一次，與規則版本綁定；執行期不再解析字串。

### 4.3 舊語法的相容策略

已於步驟 2 實作（`LegacyConditions`、`LegacyConditionMatcher`）。

1. **舊片段是獨立的節點型別 `legacy`**，而不是 `Leaf` 的一種 selector：

   ```json
   {"all": [
     {"legacy": {"source": "body",   "condition": "user.type=vip"}},
     {"legacy": {"source": "query",  "condition": "page!=1"}},
     {"legacy": {"source": "header", "condition": "X-Tenant~=acme.*"}}
   ]}
   ```

   原本規劃把 body 片段做成 `{source: body, selector: {legacy: ...}}`。實作時發現舊語意中「運算子」本身也取決於執行期的 body 型別（PLAIN body 是用**整段條件字串**做 contains，連 `=` 和值都算在內），無法拆成「取值 + 運算子」，硬塞進 `Leaf` 只會讓 `Leaf` 的語意變得不一致。因此 `legacy` 節點直接包住整段片段，評估邏輯逐行移植自 `ConditionMatcher`；query 與 header 片段也一併這樣處理，三者行為完全一致。

   body 條件不嘗試「靜態推斷」成 `jsonPath`／`xpath`，因為同一條件遇到 JSON 與 XML 會走不同路徑。只有使用者在 UI 編輯該條件時，才提示升級成明確的 `Leaf`。

2. **轉換只在記憶體中進行**：`LegacyConditions.toMatchNode(body, query, header)` 依 body → query → header 的順序產生 `all[legacy...]`；三個欄位都空時是空的 `all`（無條件 fallback）。資料庫字串不變，不需要資料遷移。

3. **反向轉換**：`LegacyConditions.toStrings(node)` 只接受「空的 `all`」或「只含 `legacy` 節點的 `all`」，其他（用到 `any`、`not` 或新運算子）回傳 empty，呼叫端不可寫回舊欄位。這決定 v1 API 與舊 UI 能不能顯示或編輯該規則（§6）。

4. **memo key 相容**：`legacy` 節點的 `cacheKey()` 維持 `"body:" + 片段`、`"query:" + 片段`、`"header:" + 片段`，讓 `PreparedBody.matchOutcomes` 與 `LogAgent` 的重用機制不需改動。

5. **匹配鏈說明相容**：`explain()` 產生的 `legacy.*` 節點經 `LegacyConditions.legacyDetail()` 可得到與舊版完全相同的文字，例如 `bodyCondition FAIL (expected user.type=vip, actual user.type=normal)`。

6. **差異測試**（`LegacyDifferentialTest`）：舊 `ConditionMatcher` 為標準答案，以 jqwik 隨機產生 5,000 組條件與請求（JSON、XML 含 namespace／SOAP、PLAIN、空白、壞掉的 JSON/XML、各種前綴與運算子、`;` 分隔變化），另加 29 個指定邊界案例與超大 body 案例，比對「是否符合、每個條件的說明文字、分數、暫存 key」四項必須完全一致。已驗證：故意改壞一個分支時，隨機與指定案例都會失敗。

### 4.4 需要先定案的既有不一致

| # | 現象 | 建議 |
|---|------|------|
| 1 | 前端 `useRuleForm.js` 依 `!=`、`*=`、`~=`、`=` 的順序找運算子（任意位置）；後端看**第一個** `=`。`a=x!=y` 在前端解析成 field `a=x`、op `!=`，後端則是 field `a`、value `x!=y` | 以後端為準。**已處理**（見 7.2.8）：前後端共用同一套拆解規則（`LegacyConditions.parts`／`MatchTree.parseLegacyCondition`，以共用案例檔測試） |
| 2 | 前端儲存時會濾掉 value 為空的條件（`c.field && c.value`），所以 `field=` 無法透過表單儲存 | 新模型以 `exists`／`equals ""` 明確表達；舊表單行為維持不變 |
| 3 | `$.a==b` 在 JSONPath 分支不會替換 `==`，實際比對的是 value `"=b"` | 保留舊行為（相容），在條件驗證時提出警告。**已處理**：編輯畫面在簡易與進階模式都提示期望值以 `=` 開頭的等於條件。JSON 欄位路徑與 XML 路徑會把 `==` 當成 `=`（XML 於 7.2.9 統一），JSONPath、query、header 不會 |
| 4 | JSONPath 欄位不存在時 `!=` 為 false，點路徑則為 true | 保留舊行為；新模型以 `onMissing` 明確表達 |
| 5 | 期望值只有一個 `"`（例如 `a="`）時，舊版去引號會丟出 `StringIndexOutOfBoundsException`：query／header 條件會讓請求回 500，body 條件則視為不符合 | **刻意修正**：新引擎把單一 `"` 當成字面值比對。這是相容層唯一與舊版不同的地方 |

### 4.5 Body 解析規劃（`BodyPlan`）

> **已實作**（見 7.2.9）：以 `BodyRequirements` 實作，名稱與下文略有不同。

保留目前的效能特性（只在有 body 條件時解析、JMS 大型 XML 走串流）：

- 引擎蒐集候選規則所有葉節點的 `BodyRequirement`：全是 `NONE` → `rawOnly`；有 `JSON` → 解析一次 Jackson tree；全部是 `XML_STREAM` → 單次 StAX 掃描、預先填入結果（取代現在的 `streamingConditions`）；任一需要 `XML_DOM` → 建一次 DOM 給所有規則共用。
- 「是否可串流」由 selector 自己宣告，不再由 `ConditionMatcher` 用字串規則判斷。

**效能量測（步驟 2，31 條規則、命中最後一條，每請求平均）**：

| 情境 | 舊 `ConditionMatcher` | 新引擎（舊語法相容層） |
|------|------|------|
| JSON body，body＋query＋header 條件 | 約 14～16 µs | 約 9～10 µs（快約 37%） |
| 一般 XML | 約 21 µs | 約 14 µs（快約 34%） |
| SOAP（帶 namespace 前綴的 XML） | 約 1,100 µs | 約 1,100 µs（持平） |

新引擎較快是因為條件在載入時就解析好，每個請求不再重複切字串、解析運算子與組暫存 key。
SOAP 情境兩者都很慢：`CustomerId` 這類條件遇到 `<ns:CustomerId>` 時，`getElementsByTagName` 找不到，
退回 `//*[local-name()='CustomerId']` XPath，每次約 17 µs。這是**既有瓶頸**，步驟 3 規劃以 DOM 走訪比對
local name 取代這段 XPath（以差異測試確保結果與順序相同）；JMS 的串流 XML 路徑不受影響。

### 4.6 匹配鏈與 near-miss

- `explain()` 回傳樹狀結果：每個葉節點包含 expected、actual、passed。
- 計分：以**根節點的直接子節點**計算 `passed/total`（舊規則就是一個扁平的 `all`，結果與現在的 `score()` 相同）。
- `MatchChainEntry` 原有欄位與 JSON 格式不變；新增可選欄位 `detailTree`。前端只在有值時顯示。（已完成，見 7.2.10）

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

`RuleApplyContractService`、`RuleQueryService`、`CacheInvalidationService`、`AdminController`（之後拆成數個 controller 與 `RuleEditService`）中依協定的分支，改為查詢 `ProtocolRegistry` 與 `ProtocolDescriptor`。例如合約 schema 的 `PROTOCOLS` 改為 `registry.ids()`；HTTP 專屬欄位改由 `descriptor.routeSchema()` 提供。

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

### 7.2 實作步驟（同一分支依序提交；每一步都可獨立驗證、可回退）

| # | 內容 | 對外行為 | 狀態 |
|---|------|------|------|
| 1 | ArchUnit 規則、`com.echo.core` 骨架、`MatchSpec` 模型、內建 op 與組合節點、單元測試 | 無 | 完成 |
| 2 | `LegacyConditions` + `legacy` 節點 + 差異測試 | 無 | 完成 |
| 3 | 以 `echo.engine.matcher=legacy\|compiled` 切換（預設 legacy），新引擎接入所有比對呼叫端；JMS 串流 XML 結果直接沿用 | 無（可切回） | 完成（開關已於步驟 9 移除） |
| 4 | `SelectionPolicy` 收斂 5 份選擇邏輯；`RuleIndex` 不可變快照 | 無 | 完成（見 7.2.2） |
| 5 | `Responder`／`ResponsePlan`，SSE 播放移出控制器；`ServeEventListener` 與請求紀錄改用 `ServeEvent` | 無 | 完成（見 7.2.3） |
| 6 | `ProtocolId`、`ProtocolAdapter`、`ProtocolRegistry`，移除核心以外的協定分支 | 無 | 部分完成（見 7.2.4） |
| 7 | `match_spec` 欄位、v2 文件、新 op（exists／absent／數值／in／equalToJson）、`any`／`not` | **新功能** | 完成（見 7.2.5；UI 為步驟 8） |
| 8 | UI 進階條件編輯器、`/match/parse` 端點、前端改用結構化條件 | **新功能** | 完成（見 7.2.6） |
| 9 | 移除 `echo.engine.matcher=legacy` 開關與舊實作（至少間隔一個正式版本） | 無 | 完成（提前於同一 PR，見 7.2.7） |

步驟 1～6 為純重構，屬於「先讓架構可擴充，再加功能」；步驟 7 起才是使用者看得到的變更。

### 7.2.1 步驟 3 實作與驗證紀錄

**接入方式**：切換點放在 `ConditionMatcher` 內部（最窄的接縫），HTTP／JMS pipeline、SSE、規則測試與 `LogAgent`
匹配鏈分析都經由它比對，因此一起切換，不需修改各呼叫端。原規劃的 `BodyPlan` 與「選擇邏輯收斂」移到步驟 4。

- 條件依文字內容編譯一次並以 LRU 快取（上限 10,000）；規則修改後文字不同，自然對應新項目。
- compiled 模式與舊版共用 `PreparedBody.matchOutcomes`，JMS 串流 XML 預先算好的結果、`LogAgent` 還原的結果都直接重用；
  串流 body 只有在條件真的需要時才建立 DOM（與舊版 `matchXPath` 相同）。
- `EvalContext` 新增請求內的中間結果快取，同一個 JSONPath 跨規則只評估一次（與舊版相同）。
  這個落差是用 compiled 模式跑整套既有測試時抓到的。
- 帶 namespace 前綴的 XML 元素改以 DOM 走訪比對 local name，取代 `//*[local-name()='X']` XPath。
- 修正既有問題：`ConditionMatcher` 有兩個建構子且沒有 `@Autowired`，Spring 一直使用無參數建構子，
  `echo.matcher.max-field-value-length` 從未生效；現在設定值會套用。

**驗證結果**

| 項目 | legacy | compiled |
|------|--------|----------|
| 單元＋整合測試（`./gradlew test`，CI 新增 compiled 工作） | 1,686 通過 | 1,686 通過 |
| 端到端回歸 `test-match-scenarios.py`（HTTP、JMS、SSE、故障注入、Scenarios） | 138/138 | 138/138 |

端到端效能（4 核心機器，伺服器與壓測程式同機；3 輪交替執行取中位數）：

| 情境 | legacy | compiled | 比值 |
|------|--------|----------|------|
| RPS：簡單 JSON（無條件，作為雜訊對照） | 1,276 | 1,277 | 1.00 |
| RPS：複雜 JSON（10 候選＋模板） | 1,383 | 1,401 | 1.01 |
| RPS：小型 XML（~2KB＋XPath） | 1,247 | 1,237 | 0.99 |
| RPS：大型 XML（~99KB＋XPath） | 407 | 404 | 0.99 |
| `stress-test-1600-rules.py` 平均匹配 | 1.0 ms | 0.8 ms | 0.80 |
| `bench-2000-jms.py` 平均匹配 | 2.6 ms | 2.6 ms | 1.00 |

結論：端到端沒有可量測的差異。無條件情境（完全不經過比對引擎）各輪之間就有約 ±6% 的波動，
吞吐量受限於同機的 Python 壓測程式；`matchTimeMs` 以毫秒為單位，也無法分辨微秒級差異。
比對引擎本身的差異以 §4.5 的微基準為準（JSON 與一般 XML 快約 35%，SOAP 持平）。
若要更精確地驗證 5% 關卡，需要在伺服器與壓測程式分開的環境重跑。

**建議**：先在測試環境設定 `ECHO_ENGINE_MATCHER=compiled` 觀察一段時間，確認請求紀錄與匹配鏈正常後，
再於後續版本把預設改為 compiled；步驟 9 才移除舊實作。

### 7.2.2 步驟 4 實作與驗證紀錄

**選擇邏輯**：`com.echo.core.engine.RuleSelector` 取代原本 4 份迴圈（HTTP／JMS 的 service 與 pipeline），
以 `FallbackPolicy` 區分 HTTP（條件命中 → 第一個無條件規則）與 JMS（精確 queue 優先於 `*`）。

**快照**：與 §5.3 原規劃不同，快照不是放在 `AtomicReference`，而是以單一項目存在既有的 `httpRules`／`jmsRules`
Caffeine 快取（`@Cacheable(sync = true)`）。這樣所有既有的清除路徑（規則異動、Response 刪除、清理排程、
多實例同步事件）都會自動淘汰快照，不必逐一改寫；`sync = true` 讓並行請求只重建一次。

- 候選集合依 (host, method) 第一次使用時計算；規則沒提到的 host／method 共用同一個 key，
  所以快照大小只隨規則數量成長，不會因用戶端送出任意 `X-Original-Host` 或 method 而無限增加（JMS 的 endpoint 分桶同理）。
- compiled 模式的條件編譯結果改放在 weak-value 快取，由快照持有強參照，快照被淘汰後即可回收。
- 重建時查詢有效 Response ID：超過 500 筆改為一次讀出所有 ID 再於記憶體取交集，
  避免 `IN` 清單過長（H2 上為 O(n²)，SQL Server／Oracle 會超過參數上限）。

**驗證**：20,000 條規則下，規則異動後的第一個請求約 0.17 秒；持續修改規則的同時壓測，p99 約 0.17 秒，
記憶體測試沒有 heap 成長。

**刻意不做**：背景重建、重建期間繼續用舊快照。這會失去「改完規則下一個請求就生效」的保證，
而上述數字已足夠，因此保留同步重建。

### 7.2.3 步驟 5 實作與驗證紀錄

**回應產生**：pipeline 依序詢問 `Responder`（故障注入 → 規則轉發 → mock），由第一個適用的產生 `ResponsePlan`
（`Content`／`Fault`／`Forwarded`）。延遲與 Scenario 轉移仍由 pipeline 統一處理；協定可覆寫 `responders()` 加入新的回應方式。
`Responder` 目前放在 `com.echo.pipeline`，因為它需要 `MockRequest` 與規則 entity；等步驟 7 有了 `ResponseSpec` 再評估是否抽到核心。

**SSE**：`UniversalMockController` 從 930 行縮到約 410 行，只負責把 HTTP 請求轉成 `MockRequest` 並交給 pipeline 或
`protocol.http.sse.SseMockHandler`，以及寫回、延遲與連線重置。

- `SsePlayer`：事件排程狀態機（原控制器內的 `SsePlayback`），獨立的 scheduler 與關機處理，邏輯原樣搬移。
- `SseMockHandler`：SSE 請求流程。與原規劃不同，SSE 沒有改成 `ResponsePlan.Stream` 走一般 pipeline，因為現行 SSE 路徑有幾個
  刻意不同的行為：沒有命中的 GET／HEAD 不轉發、SSE 規則遇到 HEAD／OPTIONS 回 405 且不寫紀錄、連線重置不套用延遲、
  只有故障或 Scenario 時才附上匹配分析資料。併入 pipeline 會改變這些行為，留待步驟 7 加入 `sse` 回應類型時再一起處理。

**請求紀錄**：`RequestLogService.record` 原本有 26 個參數，改為接收一個 `ServeEvent`（舊多載保留，轉成 `ServeEvent`）。
pipeline 與 SSE 都透過 `ServeEventDispatcher` 發出事件：請求紀錄一定排第一個、失敗一定往上傳（HTTP 503、JMS 重送）；
其他 `ServeEventListener` bean（未來的指標、Webhook）在後面依序呼叫，失敗只記錄、不影響回應。
`ServeEvent` 放在 `com.echo.service` 而非核心，因為它攜帶規則 entity 與 `PreparedBody`。

**驗證**：既有測試中對 `record(...)` 26 個參數的驗證全部改為檢查 `ServeEvent` 欄位，期望值不變；
SSE 播放測試改為直接測 `SsePlayer`；另新增 `ServeEventDispatcherTest` 與 pipeline 的 `publish()` 測試。

### 7.2.4 步驟 6 實作與驗證紀錄

**做了什麼**：每個協定在自己的 `ProtocolHandler` 提供一份 `ProtocolDescriptor`（規則 entity、規則快取名稱、
清單搜尋欄位、mock／轉發的 action 值、多實例同步的事件名稱）；`ProtocolHandlerRegistry` 就是協定註冊表，
只包含已啟用的協定（JMS 未啟用時沒有 JMS handler）。下列跨協定程式改為查詢註冊表，不再寫死 HTTP／JMS：

- `RuleQueryService`：規則清單查詢、分組查詢逐一查詢已啟用協定（原本以 `echo.jms.enabled` 判斷，與 handler 是否存在同義）。
- `CacheInvalidationService`：事件名稱與要清除的快取由描述提供；事件字串（`HTTP_RULE_CHANGED`、`JMS_RULE_CHANGED`、
  `RULE_CHANGED`）不變，舊版與新版實例可以混合運作。本實例未啟用的協定事件不清除任何快取。
- `AdminController`（現為 `RuleEditService`）：Excel 匯入與規則異動後的快取清除。

ArchUnit 新增規則：`RuleQueryService` 與 `CacheInvalidationService` 不得引用 `HttpRule`／`JmsRule` 等協定類別
（已用刻意加入的依賴確認規則會失敗）；`ProtocolDescriptorContractTest` 檢查每個描述的搜尋欄位、action 型別與快取名稱。

**刻意延後**（需要步驟 7 的規則模型，現在做會產生之後要丟掉的轉接程式）：

- `Protocol` enum 換成 `ProtocolId`：enum 存在 entity、DTO、API 與前端，換掉的成本高，而目前只有兩個協定。
- 核心層的 `ProtocolAdapter`（`RouteCodec`、`RuleIndex`、`ResponseWriter`）：目前執行期的協定差異已集中在
  `HttpMockPipeline`／`JmsMockPipeline` 與 `SseMockHandler`，等 `RuleDefinition`／`ResponseSpec` 出現後再抽成核心介面。
- 仍保留的協定分支：`RuleApplyContractService` 的 v1 文件驗證、`AdminController` 的「從請求紀錄建立規則」（現為 `RequestLogController`）
  與故障／轉發欄位正規化、SSE 規則驗證（現為 `RuleEditService`）。這些都依附在現行 DTO 欄位上，會在步驟 7 的 v2 文件與 `ResponseSpec` 一起改寫。

### 7.2.5 步驟 7 實作與驗證紀錄

**儲存**：`http_rules`／`jms_rules` 新增可為空的 `match_spec`（`Length.LONG32`，`ddl-auto: update` 自動加欄位）。
與 §6.2 原規劃不同：**只有無法用舊字串表達的條件才寫入 `match_spec`**，可表達的（只由 `legacy` 片段組成的 `all`）
寫回三個舊欄位、`match_spec` 為 null。原因是舊版 UI、Excel 會直接編輯舊欄位，若兩邊都存，`match_spec` 會在
舊 UI 編輯後過期，而且它優先，結果會與畫面不一致。

**儲存時檢查**（`MatchSpecs`）：結構、運算子與選項（沿用核心的 `MatcherCompiler`）、各協定可用的取值來源
（`ProtocolDescriptor.matchSources()`：HTTP 為 body／query／header，JMS 為 body；method／path 已由路由決定，不開放），
以及大小上限：64 KiB、16 層、256 個節點、`in` 最多 1,000 個值。錯誤訊息帶 JSON 路徑，例如 `$.all[1].any[0].op`。

**整筆取代與舊介面**：`PUT /rules/{id}` 是整筆取代，而舊版 UI 的宣告式編輯、Excel 與 v1 文件都不認得 `matchSpec`。
因此請求中沒有 `matchSpec`、也沒有任何舊版條件時，保留既有規則的 `matchSpec`；要清除請送 `{"all": []}`。
`matchSpec` 與舊版條件同時出現時拒絕（400），不猜使用者的意圖。

**比對**：有 `matchSpec` 的規則一律用新引擎（不受 `echo.engine.matcher` 影響）；舊規則的呼叫路徑完全不變（開關後來於步驟 9 移除）。
已儲存的條件若無法編譯（例如資料庫被直接修改），該規則視為永遠不符合並記錄警告，不讓每個請求都失敗。
候選規則中有 `matchSpec` 時一律先解析 body；JMS 只在這種情況改用完整解析，其餘維持串流 XML。
匹配鏈分析把結構化條件攤平成逐筆結果（type 為 `matchSpec`），`not` 底下的條件以「對整體的貢獻」呈現 PASS／FAIL。
請求紀錄 spool 的候選規則紀錄新增可為空的 `matchSpec`，舊資料讀回時為 null。

**v2 文件**：`echo.mock/v2` = v1 的所有欄位 + `spec.match`（與舊版條件互斥）。v1 完全不變，帶 `spec.match` 仍回
`UNKNOWN_FIELD`。與 §6.1 原規劃不同：進階規則的 manifest 直接以 v2 輸出，而不是回 409，這樣匯出／檢視不會失敗；
`/rules/schema?apiVersion=echo.mock/v2` 提供 v2 schema。

**驗證**：

| 項目 | 結果 |
|------|------|
| 完整檢查（測試、SpotBugs、H2 轉換工具） | 通過 |
| 全部測試以 compiled 引擎重跑 | 1,836 通過 |
| 端到端回歸 `test-match-scenarios.py` | 138/138 |
| 舊規則 RPS（步驟 6 對照，交替兩輪，每情境 15 秒） | 簡單 JSON +0.5%、小型 XML +1%、大型 XML −3%，皆在雜訊內 |
| 「複雜 JSON」單獨交替三輪（第一次對照顯示 −7%，再確認） | 步驟 6 平均 1,079 RPS、步驟 7 平均 1,115 RPS（+3%）；先前差異來自情境順序與暖機 |

進階條件壓測（同一路徑 50 條候選、目標規則排最後，16 執行緒、各 10 秒）：

| 情境 | RPS | p50 | p99 |
|------|-----|-----|-----|
| 舊版字串條件（兩個 JSON 欄位相等） | 1,130 | 13.0 ms | 34.8 ms |
| 等價的結構化條件 | 1,409 | 10.0 ms | 29.2 ms |
| 新運算子（any／not／gte／in／exists） | 1,600 | 9.0 ms | 23.5 ms |
| `equalToJson` + 忽略順序，200 個元素（10 條候選） | 915 | 16.1 ms | 38.3 ms |

結構化條件比等價的舊字串條件快（新引擎編譯一次、JSONPath 取值共用）；最貴的 `equalToJson` 在上限內仍維持約 900 RPS。

記憶體：200 條結構化條件規則，每輪全部改成新條件（強迫重新編譯與快照重建）並送請求，共 8 輪；
每輪兩次 Full GC 後 heap 為 95、95、94、96、94、94、94、88 MiB，沒有成長，舊的編譯結果會隨快照淘汰而回收。

### 7.2.6 步驟 8 實作與驗證紀錄

**編輯器**：條件區新增「簡易／進階」切換。簡易模式就是原本的條件列，操作與欄位順序不變；進階模式是遞迴的樹狀編輯器
（`MatchTreeEditor.js`），群組可選「全部符合／任一符合」，每個節點可切換 NOT，條件可選取值來源（依協定：HTTP 為
body／query／header，JMS 只有 body）、body 取值方式（JSONPath、XPath、欄位路徑、整個 body）、運算子與選項。
樹與後端 JSON 的轉換集中在 `match-tree.js`（純函式，Node 測試覆蓋）：未修改的期望值沿用原本的 JSON 型別
（數字 1000 不會被存成字串），`not` 以節點上的旗標表示。

**切換規則**：簡易 → 進階時，條件列交由後端 `POST /rules/match/parse` 轉成舊格式片段（與比對時的切法相同），
比對方式不變。進階 → 簡易只在樹完全由舊格式片段組成時直接切換；否則在畫面上先確認，確認後清除進階條件，
儲存時明確送出 `{"all": []}`（不送 `matchSpec` 時後端會保留原本的進階條件）。

**即時檢查**：編輯時以 `POST /rules/match/validate` 檢查（與儲存相同的規則、不寫入資料），一律回 200，
不合法時附錯誤與 JSON 路徑，顯示在條件區最上方。JSONPath 錯誤訊息不再帶出 Jayway 的內部例外文字。

**宣告式文件**：文件有 `spec.match` 時自動為 `echo.mock/v2`，否則維持 v1，使用者不需要修改版本欄位；
schema 改讀 v2（v1 的超集合），`spec.match` 與舊版條件同時出現時在前端即提示。表單與文件互相切換時保留條件
（切換不是儲存，`equalToJson` 的 JSON 尚未寫完時以原文字保留）。

**顯示**：規則清單以「進階」標籤與一行摘要顯示，提示文字為多行縮排的樹；詳情以縮排的條件列表顯示，條件數以樹中的條件為準。

**原規劃的差異**：簡易模式的條件列原本沿用前端自己的解析，之後已在 7.2.8 改為與後端相同的規則。

**驗證**：`match-tree.test.cjs`（轉換與摘要）、`AdvancedMatchEditorResourceTest`（載入順序、接線、多語系）、
`AdvancedMatchIntegrationTest`（parse／validate 端點），以及實際啟動伺服器以 Playwright 操作：舊規則轉進階再轉回、
即時錯誤出現與消失、切回簡易的確認與取消、宣告式 v2 往返、儲存後後端內容（新條件存在、數字型別保留），
瀏覽器主控台沒有錯誤。

### 7.2.7 步驟 9 實作與驗證紀錄

**為什麼提前**：原規劃在開關上線後至少間隔一個正式版本才移除。實際上開關只存在於這個尚未合併的分支，
從未出現在任何正式版本，沒有部署依賴它；兩種模式在步驟 3 起每一步都以完整測試、端到端情境與壓測對照過。
提前移除可以避免把「之後一定要刪」的雙引擎程式碼與 CI 工作帶進 main。

**做了什麼**：

- `ConditionMatcher` 只保留新引擎：刪除 `EngineMode`、舊版逐條比對與匹配鏈說明的實作（1,552 → 937 行）。
  保留的是請求層的工作：body 預先解析、JMS 串流 XML 預先計算（結果寫入同一份暫存，引擎直接沿用）、條件編譯與共用。
- 舊實作搬到測試程式 `LegacyConditionOracle`（逐行凍結，只去掉串流 XML 與新引擎相關部分），作為差異測試的標準答案；
  `LegacyDifferentialTest`、`ConditionMatcherLegacyEquivalenceTest` 與它比對結果、說明文字、分數與暫存 key。
- 移除 `echo.engine.matcher`／`ECHO_ENGINE_MATCHER` 設定、Gradle 的 `-PechoEngineMatcher` 與 CI 的 `test-compiled-matcher` 工作。

**相容性**：資料庫與 API 不變；舊版字串條件由 `LegacyConditionMatcher` 以相同語意評估，已儲存的規則不需轉換。
唯一刻意的差異沿用步驟 2：期望值只有一個 `"` 時，舊版丟出例外（HTTP 500），新引擎視為字面值比對。

**驗證**（步驟 8 的 jar 預設走舊引擎，與步驟 9 交替執行；4 核心機器，伺服器與壓測程式同機）：

| 項目 | 步驟 8（legacy） | 步驟 9 |
|------|------|------|
| 完整檢查（測試、SpotBugs、H2 轉換工具） | — | 1,835 通過（6 個略過） |
| 端到端回歸 `test-match-scenarios.py` | — | 138/138 |
| RPS：簡單 JSON（兩輪） | 1,171／1,248 | 1,224／1,230 |
| RPS：複雜 JSON | 1,317／1,393 | 1,433／1,391 |
| RPS：小型 XML | 1,242／1,319 | 1,248／1,259 |
| RPS：大型 XML | 459／422 | 475／418 |
| `stress-test-1600-rules.py` 10 次平均匹配 | 4.8／0.4 ms | 0.4／3.2 ms |
| JMS 2,000 條規則，40 則訊息：第 1 則 `matchTimeMs` | 26／25 ms | 47／41 ms |
| 同上，第 11～40 則平均 | 0.87／0.87 ms | 0.77／0.93 ms |
| 記憶體：2,000 條規則＋灌入任意值後 Full GC 的 heap | 96 MB | 97 MB |

結論：暖機後 HTTP 與 JMS 沒有差異（RPS 的差距與同版本兩輪之間的差距相當；1,600 條規則的平均受單次尖峰影響，兩版都有）。
**有差異的地方**：規則載入或異動後的第一則訊息，新引擎要先編譯所有候選規則的條件，2,000 條規則約多 15～20 ms；
編譯結果由規則快照持有，之後不再重算，heap 因此多約 1～2 MB。
快照本身也是在規則異動後的第一個請求才建立，所以改成「建立快照時預先編譯」不會讓第一個請求變快；要真正移出請求路徑，
必須在每次規則異動（多處的快取清除）後於背景重建快照。為了每次異動一次、2,000 條規則約 15～20 ms 的差異不值得，暫不處理。

*更正*：先前記錄的步驟 9 數據誤用了建置目錄裡較舊的 jar（腳本取第一個符合檔名的檔案），並非步驟 9 的程式；
上表為改用指定檔名的 jar 重新量測的結果，測試數量不受影響（測試直接以原始碼執行）。

### 7.2.8 簡易模式條件與後端一致（§4.4 #1）

**問題**：簡易模式的條件列由前端自行解析，與後端比對時的規則不同，打開舊規則再儲存就可能改變或遺失條件：

- 前端依 `!=`、`*=`、`~=`、`=` 的順序在任意位置找運算子；後端看第一個 `=`。`a=x!=y` 在畫面上變成欄位 `a=x`、不等於 `y`，
  實際比對卻是欄位 `a` 等於 `x!=y`。
- body 條件開頭的 `//` 在畫面上被拿掉，儲存後 `//Order/Status=OPEN` 變成 `Order/Status=OPEN`：JSON body 的結果改變，
  帶路徑的 XML 條件則從此不再符合。
- 沒有運算子的條件（例如 `hello`）在畫面上直接消失，儲存後就被刪除。
- 宣告式文件（`useRuleApply.js`）與 v1 文件檢查（前後端）也用同樣的搜尋方式。

**做法**：

- 後端 `LegacyConditions.parts` 是唯一的拆解規則，比對（`LegacyConditionMatcher`）與 v1 文件檢查都用它；
  前端 `MatchTree.parseLegacyCondition` 是同一規則的 JS 版本。共用案例檔
  `src/test/resources/fixtures/legacy-condition-parts.json` 同時由 `LegacyConditionsTest` 與 `match-tree.test.cjs` 驗證。
  沒有改用每次開啟都呼叫後端，避免表單開啟變成非同步、多一次往返。
- 條件列保留欄位原文（包括 `//`）。條件列寫回字串後拆解結果必須不變，否則不算能用條件列表示
  （例如 `a! =b` 寫回會變成 `a!=b`）。
- 任何片段無法用條件列完整表示時，整條規則以進階模式的舊格式片段原樣顯示，並提示原因；儲存時後端把只含舊格式片段的
  條件寫回舊欄位，內容不變。從進階切回簡易時同樣檢查，無法表示時先確認。宣告式文件也一樣：只含舊格式片段的進階條件
  輸出為 v1 的三個欄位。
- 產生測試資料時改依條件欄位判斷 XML（`/` 開頭），JSONPath 欄位去掉 `$.` 再組 JSON。
- `i18n/*.json` 的快取版本在步驟 8 新增文字時沒有更新，一併更新。

**驗證**：共用案例（18 個，含 `a=x!=y`、`$.a==b`、`a! =b`、沒有運算子等）前後端結果相同；以修改前（步驟 8）與修改後的 jar
實際操作「打開舊規則、不修改直接儲存」：修改前 `//Order/Status=OPEN;a=x!=y` 存成 `Order/Status=OPEN;a=x!=y`、`hello` 被刪除；
修改後兩條規則的條件都原樣保留，`hello` 以進階模式顯示並有提示，瀏覽器主控台沒有錯誤。

### 7.2.9 Body 解析規劃與 XML 語意統一（§4.5）

**做了什麼**：

- 編譯後的條件提供 `BodyRequirements`：不讀 body、只需要原文或 JSON、可串流的 XML 簡單路徑（`XmlPath`）、需要 DOM。
  `All`／`Any`／`Not` 合併子節點的需求；外部註冊的取值來源預設視為需要完整解析（`ValueSourceFactory.readsBody()`）。
- `ConditionMatcher.prepareBody(body, 候選規則條件)` 是 HTTP 與 JMS 共用的決策：沒有條件讀 body 時不解析；
  XML 且沒有條件需要 DOM 時以 `XmlStreamScan` 掃描一次，把可串流條件的結果預先填入結果暫存（不建立 DOM，
  所有結果確定後提前結束）；其他情況照常解析一次給所有規則共用。串流無法處理（例如 namespace 前綴未宣告）時改用一般解析，
  不再直接當成純文字。
- 串流掃描搬進核心，舊版條件與進階條件的簡單 XPath 都用它；原本 `ConditionMatcher` 以字串判斷能否串流的程式移除
  （約 300 行）。JMS 不再因為候選規則中有進階條件就整批改用 DOM；HTTP 的 XML body 也會串流。

**XML 語意統一**：實作前比對發現，同一個條件在 JMS 串流與完整解析（HTTP，或 JMS 有任何無法串流的候選規則時）結果不同，
JMS 的結果因此會受其他規則影響。經確認統一成串流的結果：

| 情況 | 串流（原 JMS） | 完整解析（原 HTTP） | 統一後 |
|------|------|------|------|
| 相對路徑 `Order/Status=OPEN` | 結尾符合 | 永遠不符合 | 結尾符合 |
| 同時有 `<ns:Item>` 與 `<Item>`，條件 `Item=5` | 兩者都看 | 只看 `<Item>` | 兩者都看（local name） |
| `Status==OPEN` | 期望值 `OPEN` | 期望值 `=OPEN` | 期望值 `OPEN` |

`XmlPath` 是簡單路徑的唯一定義，DOM 查找（`XmlPath.select`）與串流掃描用同一套規則；進階條件的簡單 `xpath` selector
也照此規則（不分 namespace 前綴），含 predicate、函式、萬用字元或帶前綴名稱的路徑照常交給 XPath。
`LegacyConditionOracle` 以 `[B1]` 標示這兩項刻意調整。

**驗證**：`XmlStreamScanEquivalenceTest`（400 組隨機 XML：namespace 前綴、屬性、巢狀、CDATA、實體、註解、空白，
搭配隨機路徑與舊版／進階條件，串流結果必須等於 DOM 結果，並含過長值的略過規則）、`ConditionMatcherBodyPlanTest`
（不解析／串流／DOM／退回一般解析／JSON／匹配鏈說明一致）、差異測試與整套測試。

**效能**（`main`（c3d4825）與本步驟交替執行兩輪；4 核心，伺服器與壓測程式同機；JMS 為 2,000 條規則、40 則訊息、
取第 11～40 則 `matchTimeMs` 平均）：

| 情境 | `main` 第 1／2 輪 | 本步驟 第 1／2 輪 |
|------|------|------|
| JMS 舊版條件 `//ServiceName;//CustId`，約 1 KB | 6.0／5.9 ms | 8.5\*／5.5 ms |
| 同上，訊息補到約 100 KB | 92.2／100.6 ms | 9.9／6.6 ms |
| JMS 進階條件（簡單 XPath），約 1 KB | 106.5／108.9 ms | 8.5／6.1 ms |
| 同上，約 100 KB | 1,407／1,406 ms | 9.8／5.8 ms |
| HTTP RPS：簡單 JSON | 1,085／1,184 | 1,120／1,160 |
| HTTP RPS：複雜 JSON | 1,268／1,263 | 1,232／1,235 |
| HTTP RPS：小型 XML | 1,175／1,185 | 1,374／1,356 |
| HTTP RPS：大型 XML（~99 KB） | 429／360 | 812／725 |
| 端到端回歸 `test-match-scenarios.py` | — | 138/138 |

\* 第 1 輪的本步驟版本尚未快取合併後的需求：2,000 條規則每則訊息要重新查詢與合併條件需求（約 0.7 ms），
小訊息因此慢約 2.5 ms。JMS 的候選清單取自規則快照、在快照存在期間是同一個物件，改為依清單物件快取合併結果
（weak key，快照淘汰後一併回收；HTTP 候選清單每次依 path 篩選，不快取）後，第 2 輪與另外四次交替的小訊息測試
（`main` 0.73／0.87 ms、本步驟 0.73／0.87 ms）都已無差異。

結論：JMS 不再因進階條件改用 DOM，大型 XML 不論 HTTP 或 JMS 都明顯變快；其餘情境持平。
另外一次測試中途因為磁碟空間不足（歷次壓測遺留的資料目錄），內嵌 JMS broker 暫停接收訊息，該次結果作廢重跑；
壓測腳本已改為每次結束後刪除資料目錄。

### 7.2.10 匹配鏈的條件樹（§4.6）

請求紀錄「匹配鏈」原本只顯示規則與原始條件文字，進階條件是一整段 JSON，看不出哪個群組或條件沒過；
near-miss 的逐條結果雖然有記錄（`detail`），但畫面讀的是不存在的 `mismatch` 欄位，從來沒有顯示。

- **後端**：`MatchExplanation` 新增 `leaf`（結構化條件的原始節點）；`ConditionMatcher.explainPrepared` 回傳樹狀說明，
  原本的 `matchesPreparedWithDetail` 由它攤平而來，結果不變。`LogAgent` 只為會顯示的條目（match、第一個 shadowed、
  第一個 near-miss）以 `MatchExplanationJson` 產生 `detailTree`，因此每筆紀錄最多三棵樹。
- **格式**：群組 `{node:"all|any|not", passed, children}`；結構化條件 `{node:"leaf", passed, spec, actual}`；
  舊格式條件 `{node:"legacy", source, passed, condition, actual}`；取不到值為 `missing`，body 太大為 `unavailable`。
- **大小上限**：一棵樹最多 200 個節點、約 8,000 字（超過時該群組其餘條件不列出，標 `truncated`），單一文字 300 字、`spec` 超過 1,000 字時省略期望值
  （`valueOmitted`）。只在背景分析時產生，請求路徑與 fast path 不變；分析被略過（佇列壅塞）時也不會產生。
- **畫面**：根節點的「全部要符合」不另外顯示，直接列出各條件（與規則摘要相同）。沒過的群組預設展開、已通過的收起；
  造成整條規則沒過的條件標示「卡在這裡」，NOT 底下的條件以「對整體是否有幫助」著色。原始條件改為按需顯示。
  沒有 `detailTree` 的舊紀錄照舊顯示原始條件，near-miss／skipped 另外顯示原本就有的逐條結果。

**驗證**：`MatchExplanationJsonTest`（群組、實際值、缺值、舊格式、上限）、`MatchChainAnalysisTest`（只有評估過條件的條目附上樹）、
`match-tree.test.cjs`（顯示用的拆解與 NOT 著色），以及實際啟動伺服器建立進階與舊格式規則、送出請求後以 Playwright
檢查深色與淺色主題的畫面（無前端錯誤）。

### 7.2.11 請求處理流程圖

請求紀錄新增「處理流程」分頁（預設開啟），把一筆請求實際走過的路畫成步驟：收到請求 → 比對規則 → 模擬回應／SSE 串流／轉發
（沒走的一邊淡化）或找不到規則 → 回給呼叫端，並以時間條拆成比對、產生回應或等待轉發對象、模擬延遲三段。

- **路線**（依 `AbstractMockPipeline` 與 `SseMockHandler` 的實際順序）：條件命中、改用沒有條件的規則（JMS 先指定佇列再萬用）、
  情境狀態不符而略過；命中後為模擬回應、SSE、故障注入（連線中斷／空回應）或規則設定的轉發；沒命中時依預設轉發設定、
  `X-Original-Host` 轉發，或回 404（JMS 回錯誤訊息）。轉發再分為正常、對方回錯誤狀態、連不上或逾時。
- **新增紀錄欄位**（皆可為空，舊紀錄顯示為未知）：`candidateCount`、`matchedByFallback`（`RuleSelector.Selection.conditionMatched`
  的反面）、`delayMs`（規則的模擬延遲；HTTP 與 JMS 都在寫入紀錄之後才等待，因此不含在 `responseTimeMs` 內）、
  `sseEventCount`、`scenarioSkipped`（匹配鏈格式，最多 20 筆）。經 `ServeEvent` → `LogTask` → spool → `RequestLog`／
  `LogEntry`，只在詳細資料 API 回傳，列表查詢不變。spool 記錄改為不寫入空欄位。
- **畫面**：`request-flow.js` 為純函式（Node 測試涵蓋 7 條路線與時間條），`RequestFlowView` 負責呈現；抽屜一般寬度時由上而下，
  加寬到 820px 以上改為由左而右（container query）。「看匹配鏈」直接切到匹配鏈分頁。

**驗證**：`AbstractMockPipelineTest`（候選數、延遲、備選、情境略過）、`UniversalMockControllerSseTest`（SSE 事件數）、
`RequestLogSpoolTest`（新欄位經 spool 重啟後保留）、`RequestLogIntegrationTest`（詳細資料 API 回傳新欄位）、
`request-flow.test.cjs`，以及實際啟動伺服器送出 7 種請求（含延遲與情境、情境略過、連線中斷、SSE、萬用規則、轉發失敗、
規則轉發），以 Playwright 截圖檢查一般與加寬抽屜（無前端錯誤）。

### 7.2.12 規則判斷順序與輪不到的規則

規則詳細資料新增「判斷順序」，規則列表標出一定輪不到的規則（標記說明原因），並提供「輪不到」篩選（規則清單 API 的 `unreachable=true`，依 id 分段以 IN 查詢，與分頁、其他篩選一起使用）（`RuleOrderAnalyzer`、`RuleOrderService`、`RuleOrderController`）。

- **與實際比對一致**：規則來源與排序和比對相同（HTTP 規則快照與 `HTTP_RULE_ORDER`；JMS 快取的規則與 `JMS_RULE_ORDER`，
  抽成常數共用），排序條件相同時維持規則來源的順序（與穩定排序相同）。挑選規則見 `RuleSelector`：先依序檢查有條件的規則，
  都不成立時才用第一條沒有條件的規則，所以沒有條件的規則不會擋住後面有條件的規則。
- **只判斷確定的情況**：較早的規則 E 適用規則 R 的所有請求（HTTP：host、method、路徑都涵蓋，`/x/*` 涵蓋以 `/x/` 開頭的路徑；
  JMS：queue 相同或 `*`，依 endpoint 欄位分桶時 E 不能限定成別的值），E 不受情境狀態限制，而且兩者條件文字完全相同
  （結構化條件以 JSON 內容比較），或兩者都沒有條件。寫法不同但意思重疊的條件不判斷。
- **判斷順序**另外列出只適用部分請求的規則（例如只限某個 host 的萬用規則），標示「只在部分請求時檢查」，不用來判定輪不到；
  排在預設回應之後的沒有條件規則標示「這裡輪不到」（它在其他路徑仍可能用到）。
- **效能**：每條規則的條件只轉成一次比對用的文字（結構化條件的 JSON 欄位依名稱排序），規則依路徑（或 queue）與條件分組，
  判斷一條規則時只看可能涵蓋它、而且條件相同（或同樣沒有條件）的那一組，遇到排在它之後的規則就停止。索引與結果依規則來源物件快取，
  規則異動清除快取後重算。實測（單執行緒，未暖機的第一次較慢）：

  | 情境 | 改寫前 | 改寫後 |
  |------|------|------|
  | 20,000 條規則分散在 2,000 個路徑，檢查全部 | 約 1.1 秒 | 約 0.1 秒 |
  | 同一路徑 2,000 條舊格式條件規則 | 約 0.24 秒 | 約 0.01 秒 |
  | 同一路徑 2,000 條結構化條件規則 | 約 4.5 秒 | 約 0.15 秒 |
  | 單一規則的判斷順序 | — | 數毫秒（重用索引） |

- **畫面上的數量**：判斷順序超過 25 條時只顯示開頭 15 條、這條規則與前後各一條、擋住它的規則與預設回應，其餘以
  「顯示其餘 N 條」收合；規則列表以篩選分頁顯示，數量不影響畫面。

**驗證**：`RuleOrderServiceTest`（重複條件、預設規則、涵蓋範圍、情境限制、結構化條件、判斷順序、部分適用、停用規則、JMS 分桶、快取）、
`RuleOrderSoundnessPropertyTest`（隨機規則與請求，以實際的候選查詢與 `RuleSelector` 驗證被判定輪不到的規則從未被選中；
拿掉涵蓋檢查的錯誤版本會被這個測試抓到）、`RuleOrderIntegrationTest`（API、刪除規則後立即更新），以及實際啟動伺服器以
Playwright 檢查規則列表的標記、「輪不到」篩選與判斷順序畫面（無前端錯誤）。

### 7.2.13 請求紀錄的流量概況

請求紀錄頁上方的流量概況（`TrafficOverview`、`traffic-chart.js`；後端 `RequestLogTrafficService`、`RequestLogTrafficQuery`、
`GET /api/admin/logs/traffic`）：最近 1 小時、24 小時或 7 天，每格依結果分成模擬回應、轉發、沒比對到、轉發失敗
（沒比對到優先；比對到規則且有 `proxyError` 為轉發失敗），加上回應時間估計與最常沒比對到的請求。預設精簡，可展開或收合，
選擇存在瀏覽器；點一格長條即在列表加上時間篩選（`GET /api/admin/logs` 的 `from`／`to`），已因筆數上限刪除的時段標為沒有資料。

資源使用的取捨：

- **在資料庫內計數**：以時間比較的 `CASE` 分格（不用資料庫日期函式，外部資料庫也適用），只取回幾十列；
  涵蓋索引 `idx_log_traffic (requestTime, matched, forwarded, proxyError, responseTimeMs)` 讓查詢不讀排在後面的 body 與比對過程。
  最初版本把每筆紀錄串流到 Java 統計，7 天、100,000 筆時每次產生約 29 MB 短期物件，改為資料庫計數後約 0.2 MB 且與筆數無關。
- **回應時間**以 28 個固定區間計數，在區間內依名次比例估計中位數與最慢 5%，不保留個別數值。
- **共用與單一計算**：結果依時間範圍暫存 5 秒，同一時間只有一個統計在執行，所以很多人同時開啟只查一次、最多佔用一條資料庫連線
  （連線池只有 3 條，也要寫入紀錄與讀取規則）。收合時前端不查詢。記憶體模式直接走訪既有的環形緩衝區。

實測（4 核心，測試程式與伺服器同一台；以登入後的 session 呼叫，與瀏覽器相同）：

| 項目 | 10,000 筆 | 100,000 筆 |
|------|------|------|
| 單次統計（7 天，含筆數、最早紀錄與最常沒比對到） | 約 22 ms | 約 140 ms |
| 單次統計產生的短期物件 | 約 0.2 MB | 約 0.2 MB |
| 60 人同時開啟（三種範圍各 20 人）的資料庫統計次數 | 3 次 | 3 次 |
| 30 人連續不停查詢 30 秒（約 4 萬次）的資料庫統計次數 | 18 次 | 18 次 |
| 上述壓力後 GC 完的 heap | 54.8 → 55.5 MB | 54.1 → 55.8 MB |

同樣壓力改打既有的 `/api/admin/logs/summary`，延遲與模擬請求變慢的程度相同或更多，表示這些數字來自請求量本身（同一台機器的 CPU），
不是流量概況。既有資料庫升級時，索引於啟動時自動建立（已以舊版建立的資料庫驗證）。

**驗證**：`RequestLogTrafficServiceTest`（分格對齊、分類、回應時間估計、筆數上限、記憶體模式、共用快取、16 執行緒同時查詢只計算一次）、
`RequestLogTrafficQuerySqliteIntegrationTest`（實際 SQLite 的分格、回應時間區間、最常沒比對到、時間篩選、查詢計畫使用涵蓋索引）、
`traffic-chart.test.cjs`，以及實際啟動伺服器以 Playwright 檢查三種顯示、點選篩選、鍵盤操作、收合不查詢、淺色主題、手機寬度與筆數上限（無前端錯誤）。

### 7.3 風險

| 風險 | 緩解 |
|------|------|
| 語意漂移（尤其是 §2.2 的執行期派送與缺漏欄位行為） | `legacy` 節點原樣搬移演算法，加上差異測試（舊實作保留為測試標準答案）；功能開關已於步驟 9 移除 |
| 樹狀評估造成額外配置、降低吞吐量 | fast path 只回傳布林；葉節點與 predicate 預先編譯；效能關卡把關 |
| JMS 串流 XML 的記憶體特性改變 | `XML_STREAM` 需求由 selector 宣告，維持「全部可串流才串流」的條件；`JmsMessageMemoryBudget` 不變 |
| 多實例快取一致性 | 沿用 `CacheInvalidationService` 事件，只把「清除 Caffeine」換成「重建快照」 |
| 7 種資料庫的新欄位 | 可為空的 `LONG32` 欄位；以 `scripts/test-rdbms-matrix.py` 驗證；Phase 1 的 Flyway 完成後改用版控 migration |

## 8. 待決策事項

1. **儲存的最終形態**：長期是否將 `http_rules`／`jms_rules` 合併為通用的 `rules` 表（`protocol`、`route_key`、`route_spec`、`match_spec`、`response_spec`）？建議：新協定直接使用通用表；HTTP／JMS 等 Flyway 上線、有正式 migration 後再評估是否搬移。
2. **`matchesJsonSchema` 的依賴**：需要引入 JSON Schema 驗證函式庫（例如 networknt `json-schema-validator`）。建議放在步驟 7 之後，視需求再加入。
3. **`echo-core` 抽成獨立 Gradle 模組的時機**：建議步驟 6 完成、ArchUnit 規則穩定後立即進行，作為 Phase 3 嵌入式模式、Java SDK、Testcontainers 的前提。
4. **舊字串語法的定位**：建議永久保留讀寫（相容與快速輸入），但新功能只出現在結構化模型中。
5. **v2 文件命名**：`echo.mock/v2` 或沿用 v1 加上可選的 `spec.match`？建議採用 v2，讓 v1 的語意保持凍結、容易說明。
