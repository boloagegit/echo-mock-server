package com.echo.service;

import java.util.Collection;
import javax.xml.stream.XMLStreamException;
import com.echo.core.match.XmlStreamScan;
import com.echo.core.match.XmlPath;
import com.echo.core.match.BodyRequirements;
import com.echo.core.match.BodyView;
import com.echo.core.match.CompiledMatcher;
import com.echo.core.match.EvalContext;
import com.echo.core.match.LegacyConditions;
import com.echo.core.match.MatchExplanation;
import com.echo.core.match.MatchNode;
import com.echo.core.match.MatcherCompiler;
import com.echo.core.match.MatcherLimits;
import com.echo.core.match.MatcherRegistry;
import com.echo.core.match.RequestView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 條件匹配服務
 * <p>
 * 支援多種格式的請求內容匹配，類似 WireMock 風格：
 *
 * <h3>支援的運算子</h3>
 * <ul>
 *   <li>{@code field=value} - 等於（預設）</li>
 *   <li>{@code field~=pattern} - 正規表達式匹配</li>
 *   <li>{@code field*=substring} - 包含子字串</li>
 *   <li>{@code field!=value} - 不等於</li>
 * </ul>
 *
 * <h3>支援的路徑格式</h3>
 * <ul>
 *   <li>{@code $.jsonPath} - JSON Path 表達式</li>
 *   <li>{@code //xpath} - XPath 表達式</li>
 *   <li>{@code ?param} - Query 參數</li>
 *   <li>{@code field.nested} - JSON 欄位路徑</li>
 * </ul>
 *
 * 條件一律由 {@code com.echo.core.match} 引擎評估：舊版字串條件經 {@link LegacyConditions} 轉成相容節點，
 * 結構化條件（{@code matchSpec}）直接編譯。這個類別負責請求層的工作：body 預先解析、JMS 串流 XML 預先計算、
 * 條件編譯結果的共用，以及把引擎的說明轉成匹配鏈格式。
 */
@Service
@Slf4j
public class ConditionMatcher {

    private static final int MAX_JSON_SIZE = 10 * 1024 * 1024; // 10MB

    private static final int DEFAULT_MAX_FIELD_VALUE_LENGTH = 2 * 1024 * 1024;

    /** 單一欄位值長度上限，超過則跳過比對（防止 base64 等大值拖慢匹配） */
    private final int maxFieldValueLength;

    private final MatcherCompiler compiler;
    /**
     * 條件字串 → {@link RuleConditions}，以弱參照保存：規則快照（{@code HttpRuleSnapshot}／{@code JmsQueueSnapshot}）
     * 持有使用中的條件，因此不會被回收也不需要容量上限；快照被淘汰後，沒有人使用的條件由 GC 回收。
     * 取代原本上限 10,000 筆的 LRU 快取，避免條件數超過上限時反覆重新編譯。
     */
    private final Cache<ConditionKey, RuleConditions> conditionRegistry = Caffeine.newBuilder()
            .weakValues()
            .build();

    /** 預設建構子，使用 2MB 上限（供測試及非 Spring 環境使用） */
    public ConditionMatcher() {
        this(DEFAULT_MAX_FIELD_VALUE_LENGTH);
    }

    @Autowired
    public ConditionMatcher(@Value("${echo.matcher.max-field-value-length:2097152}") int maxFieldValueLength) {
        this.maxFieldValueLength = maxFieldValueLength;
        this.compiler = new MatcherCompiler(MatcherRegistry.defaults(),
                MatcherLimits.DEFAULT.withMaxFieldValueLength(maxFieldValueLength));
    }

    private record ConditionKey(String matchSpec, String body, String query, String header) {
    }

    /**
     * 一組規則條件（結構化條件，或舊版三個欄位）。第一次比對時才編譯，之後重用。
     * 呼叫端（規則快照）持有此物件，就能讓編譯結果在快照的生命週期內保留。
     */
    public static final class RuleConditions {

        private final ConditionKey key;
        private final MatcherCompiler compiler;
        private volatile CompiledMatcher compiled;

        private RuleConditions(ConditionKey key, MatcherCompiler compiler) {
            this.key = key;
            this.compiler = compiler;
        }

        /** 這組條件對 body 的需求（見 {@link BodyRequirements}）。 */
        public BodyRequirements bodyRequirements() {
            return compiled().bodyRequirements();
        }

        private CompiledMatcher compiled() {
            CompiledMatcher result = compiled;
            if (result == null) {
                // 並行時可能重複編譯一次；結果相同且不可變，因此不需加鎖
                result = key.matchSpec() != null
                        ? compileStored(key.matchSpec())
                        : compiler.compile(LegacyConditions.toMatchNode(key.body(), key.query(), key.header()));
                compiled = result;
            }
            return result;
        }

        /**
         * 儲存時已檢查過；資料庫被直接修改等情況下仍無法編譯時，這條規則視為永遠不符合，
         * 避免每個請求都因例外而失敗。
         */
        private CompiledMatcher compileStored(String matchSpec) {
            try {
                return compiler.compile(STORED_SPEC_READER.readTree(matchSpec));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                log.warn("Rule matchSpec cannot be compiled; the rule will never match: {}", e.getMessage());
                return compiler.compile(new MatchNode.Any(List.of()));
            }
        }
    }

    private static final ObjectMapper STORED_SPEC_READER = new ObjectMapper();

    /**
     * 取得一組條件的共用物件；相同條件在使用期間回傳同一個物件。
     * 規則快照應持有回傳值，讓編譯結果在快照存在期間不被回收。
     */
    public RuleConditions conditions(String matchSpec, String bodyCondition, String queryCondition,
                                     String headerCondition) {
        String spec = hasMatchSpec(matchSpec) ? matchSpec : null;
        return conditionRegistry.get(new ConditionKey(spec, bodyCondition, queryCondition, headerCondition),
                key -> new RuleConditions(key, compiler));
    }

    public RuleConditions conditions(String bodyCondition, String queryCondition, String headerCondition) {
        return conditions(null, bodyCondition, queryCondition, headerCondition);
    }

    private CompiledMatcher compiled(String matchSpec, String bodyCondition, String queryCondition,
                                     String headerCondition) {
        return conditions(matchSpec, bodyCondition, queryCondition, headerCondition).compiled();
    }

    private static boolean hasMatchSpec(String matchSpec) {
        return matchSpec != null && !matchSpec.isBlank();
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final ThreadLocal<DocumentBuilder> DOC_BUILDER = ThreadLocal.withInitial(() -> {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create DocumentBuilder", e);
        }
    });

    @PreDestroy
    public void cleanup() {
        DOC_BUILDER.remove();
    }

    // ==================== Prepared Body ====================

    /**
     * 預先解析的 Body 容器
     * <p>
     * 在批次匹配（多條規則比對同一個 body）時，先 parse 一次 body，
     * 後續每條規則直接使用已 parse 的物件，避免重複 parse。
     */
    public static class PreparedBody {
        private final String raw;
        private final Document xmlDoc;
        private final JsonNode jsonNode;
        private final BodyType type;
        private final Map<String, Boolean> matchOutcomes = new HashMap<>();
        /** 比對引擎的評估狀態，見 {@link #evalContext(String, Map)}。 */
        private EvalContext compiledContext;
        private String contextQueryString;
        private Map<String, String> contextHeaders;
        private Document lazyXmlDoc;
        private boolean lazyXmlParsed;
        private Map<XmlPath, List<String>> xmlValues;

        enum BodyType { XML, JSON, PLAIN, EMPTY, TOO_LARGE }

        private PreparedBody(String raw, Document xmlDoc, JsonNode jsonNode, BodyType type) {
            this.raw = raw;
            this.xmlDoc = xmlDoc;
            this.jsonNode = jsonNode;
            this.type = type;
        }

        public static PreparedBody empty() {
            return new PreparedBody(null, null, null, BodyType.EMPTY);
        }

        public static PreparedBody tooLarge() {
            return new PreparedBody(null, null, null, BodyType.TOO_LARGE);
        }

        /**
         * 保留原始內容供請求紀錄使用，但不建立 JSON tree 或 XML DOM。
         * 僅在候選規則完全沒有 body 條件時使用。
         */
        public static PreparedBody rawOnly(String raw) {
            if (raw == null || raw.isBlank()) {
                return empty();
            }
            return new PreparedBody(raw, null, null, BodyType.PLAIN);
        }

        private static PreparedBody streamingXml(String raw, Map<String, Boolean> outcomes) {
            PreparedBody prepared = new PreparedBody(raw, null, null, BodyType.XML);
            prepared.matchOutcomes.putAll(outcomes);
            return prepared;
        }

        /** 取得已解析的 XML Document，若非 XML 則為 null */
        public Document getXmlDoc() {
            return xmlDoc;
        }

        /** 取得已解析的 JSON 節點，若非 JSON 則為 null */
        public JsonNode getJsonNode() {
            return jsonNode;
        }

        /** 取得原始 body 字串 */
        public String getRaw() {
            return raw;
        }

        /** Lightweight request-time predicate results; never contains parsed body objects. */
        public Map<String, Boolean> getMatchOutcomesSnapshot() {
            return Map.copyOf(matchOutcomes);
        }

        private void restoreMatchOutcomes(Map<String, Boolean> outcomes) {
            if (outcomes != null && !outcomes.isEmpty()) {
                matchOutcomes.putAll(outcomes);
            }
        }

        /** 本請求已評估的 JSONPath 數量（保存在比對引擎的評估狀態中）。 */
        int cachedJsonPathCount() {
            return compiledContext == null ? 0 : compiledContext.cachedValueCount();
        }

        /**
         * 比對引擎的評估狀態。同一請求內 query／header 不變，因此重用同一個 context；
         * 結果暫存直接共用 {@link #matchOutcomes}，JMS 串流 XML 預先算好的結果與背景分析還原的結果都直接重用。
         */
        @SuppressWarnings("ReferenceEquality") // 同一請求傳入的是同一個 headers 物件，比對身分即可
        private EvalContext evalContext(String queryString, Map<String, String> headers) {
            if (compiledContext == null || !Objects.equals(contextQueryString, queryString)
                    || contextHeaders != headers) {
                contextQueryString = queryString;
                contextHeaders = headers;
                compiledContext = new EvalContext(
                        new PreparedRequestView(queryString, headers, new PreparedBodyView()), matchOutcomes);
            }
            return compiledContext;
        }

        /** 把已解析的內容提供給比對引擎，不重新解析。 */
        private final class PreparedBodyView implements BodyView {

            @Override
            public String raw() {
                return raw;
            }

            @Override
            public boolean tooLarge() {
                return type == BodyType.TOO_LARGE;
            }

            @Override
            public JsonNode json() {
                return jsonNode;
            }

            /** 串流掃描過的 XML 沒有 DOM；真的需要（例如匹配鏈說明）時才解析，失敗也只試一次。 */
            @Override
            public Document xml() {
                if (xmlDoc != null) {
                    return xmlDoc;
                }
                if (type == BodyType.XML && !lazyXmlParsed && raw != null) {
                    lazyXmlParsed = true;
                    lazyXmlDoc = parseXmlQuietly(raw);
                }
                return lazyXmlDoc;
            }

            /** 同一請求內相同路徑只查找一次，多條規則共用。 */
            @Override
            public List<String> xmlValues(XmlPath path) {
                Document doc = xml();
                if (doc == null) {
                    return null;
                }
                if (xmlValues == null) {
                    xmlValues = new HashMap<>();
                }
                return xmlValues.computeIfAbsent(path, ignored -> path.select(doc));
            }
        }
    }

    /** Header map 直接沿用呼叫端的物件（不複製），保留舊版依迭代順序取第一個 header 的行為。 */
    private record PreparedRequestView(String queryString, Map<String, String> headers, BodyView body)
            implements RequestView {

        @Override
        public String method() {
            return null;
        }

        @Override
        public String path() {
            return null;
        }
    }

    private static Document parseXmlQuietly(String xml) {
        try {
            DocumentBuilder builder = DOC_BUILDER.get();
            builder.reset();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (SAXException | IOException e) {
            return null;
        }
    }

    /**
     * 預先解析 body，回傳 PreparedBody 供批次匹配使用
     */
    public PreparedBody prepareBody(String body) {
        if (body == null || body.isBlank()) {
            return PreparedBody.empty();
        }
        if (body.length() > MAX_JSON_SIZE) {
            log.warn("Body too large for prepare: {} bytes", body.length());
            return PreparedBody.tooLarge();
        }
        String trimmed = body.trim();
        try {
            if (trimmed.startsWith("<")) {
                DocumentBuilder builder = DOC_BUILDER.get();
                builder.reset();
                Document doc = builder.parse(new InputSource(new StringReader(trimmed)));
                return new PreparedBody(trimmed, doc, null, PreparedBody.BodyType.XML);
            }
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                JsonNode node = objectMapper.readTree(trimmed);
                return new PreparedBody(trimmed, null, node, PreparedBody.BodyType.JSON);
            }
        } catch (Exception e) {
            log.warn("Failed to prepare body: {}", e.getMessage());
        }
        return new PreparedBody(trimmed, null, null, PreparedBody.BodyType.PLAIN);
    }

    /**
     * 依候選規則條件的需求準備 body（§4.5）：沒有條件讀 body 時不解析；XML body 且沒有條件需要 DOM 時，
     * 以串流掃描一次算出所有可串流條件的結果，不建立 DOM；其他情況照常解析，所有規則共用。
     */
    public PreparedBody prepareBody(String body, Collection<RuleConditions> candidates) {
        List<BodyRequirements> requirements = new ArrayList<>(candidates.size());
        for (RuleConditions conditions : candidates) {
            requirements.add(conditions.bodyRequirements());
        }
        return prepareBody(body, BodyRequirements.merge(requirements));
    }

    /** 同上，需求已合併。 */
    public PreparedBody prepareBody(String body, BodyRequirements requirements) {
        if (!requirements.usesBody()) {
            return PreparedBody.rawOnly(body);
        }
        if (body == null || body.isBlank()) {
            return PreparedBody.empty();
        }
        if (body.length() > MAX_JSON_SIZE) {
            log.warn("Body too large for prepare: {} bytes", body.length());
            return PreparedBody.tooLarge();
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("<") || requirements.needsXmlDom()) {
            return prepareBody(body);
        }
        try {
            return PreparedBody.streamingXml(trimmed,
                    XmlStreamScan.evaluate(trimmed, requirements, maxFieldValueLength));
        } catch (XMLStreamException | RuntimeException e) {
            // 串流無法處理（不是合法 XML、namespace 前綴未宣告等）：改用一般解析，由它判斷是 XML 還是純文字
            return prepareBody(body);
        }
    }

    /** Rebuilds parsed body state while reusing predicates already evaluated on the request path. */
    public PreparedBody prepareBody(String body, Map<String, Boolean> matchOutcomes) {
        PreparedBody prepared = prepareBody(body);
        prepared.restoreMatchOutcomes(matchOutcomes);
        return prepared;
    }

    // ==================== Public Matching API ====================

    /**
     * 快速布林匹配（使用預先解析的 body，不建立 detail 物件）
     * <p>
     * 用於規則匹配迴圈，效能最佳。
     */
    public boolean matchesPrepared(String bodyCondition, String queryCondition, String headerCondition,
                                   PreparedBody prepared, String queryString, Map<String, String> headers) {
        return matchesPrepared(null, bodyCondition, queryCondition, headerCondition, prepared, queryString, headers);
    }

    /**
     * 同 {@link #matchesPrepared(String, String, String, PreparedBody, String, Map)}，另外支援結構化條件；
     * 有 {@code matchSpec} 時以它為準，忽略舊版三個欄位。
     */
    public boolean matchesPrepared(String matchSpec, String bodyCondition, String queryCondition,
                                   String headerCondition, PreparedBody prepared, String queryString,
                                   Map<String, String> headers) {
        return compiled(matchSpec, bodyCondition, queryCondition, headerCondition)
                .matches(evalContext(prepared, queryString, headers));
    }

    /**
     * 結構化匹配（使用預先解析的 body，回傳每個條件的 PASS/FAIL 細節）
     * <p>
     * 用於匹配鏈分析，提供 near-miss 偵測所需的詳細資訊。
     */
    public ConditionDetail matchesPreparedWithDetail(String bodyCondition, String queryCondition, String headerCondition,
                                                     PreparedBody prepared, String queryString, Map<String, String> headers) {
        return matchesPreparedWithDetail(null, bodyCondition, queryCondition, headerCondition,
                prepared, queryString, headers);
    }

    /** 同上，另外支援結構化條件；有 {@code matchSpec} 時以它為準。 */
    public ConditionDetail matchesPreparedWithDetail(String matchSpec, String bodyCondition, String queryCondition,
                                                     String headerCondition, PreparedBody prepared,
                                                     String queryString, Map<String, String> headers) {
        return toConditionDetail(compiled(matchSpec, bodyCondition, queryCondition, headerCondition)
                .explain(evalContext(prepared, queryString, headers)));
    }

    private static EvalContext evalContext(PreparedBody prepared, String queryString, Map<String, String> headers) {
        return (prepared != null ? prepared : PreparedBody.empty()).evalContext(queryString, headers);
    }

    /**
     * 舊版條件的說明文字與原本的實作完全相同（見測試的 LegacyConditionOracle）；
     * 結構化條件則把每個條件（葉節點）攤平成一筆，type 為 {@code matchSpec}，
     * 整體是否符合仍以整棵樹（含 any／not）的結果為準。
     */
    private static ConditionDetail toConditionDetail(MatchExplanation explanation) {
        List<ConditionResult> results = new ArrayList<>(explanation.children().size());
        addConditionResults(explanation, false, results);
        return ConditionDetail.builder()
                .overallMatch(explanation.passed())
                .results(results)
                .build();
    }

    /** {@code not} 底下的條件以「不符合才算通過」呈現，讓每一筆的 PASS／FAIL 與它對整體的貢獻一致。 */
    private static void addConditionResults(MatchExplanation explanation, boolean negated,
                                            List<ConditionResult> results) {
        if (explanation.isCondition()) {
            addConditionResult(explanation, negated, results);
            return;
        }
        boolean childNegated = "not".equals(explanation.node()) != negated;
        for (MatchExplanation child : explanation.children()) {
            addConditionResults(child, childNegated, results);
        }
    }

    private static void addConditionResult(MatchExplanation explanation, boolean negated,
                                           List<ConditionResult> results) {
        Optional<MatchNode.LegacySource> legacy = LegacyConditions.sourceOf(explanation);
        if (legacy.isPresent() && !negated) {
            results.add(ConditionResult.builder()
                    .type(legacy.get().fieldName())
                    .condition(explanation.expected())
                    .passed(explanation.passed())
                    .detail(LegacyConditions.legacyDetail(explanation).orElseThrow())
                    .build());
            return;
        }
        String condition = negated ? "not " + explanation.expected() : explanation.expected();
        boolean passed = explanation.passed() != negated;
        String detail = passed
                ? MATCH_SPEC_TYPE + " PASS (" + condition + ")"
                : MATCH_SPEC_TYPE + " FAIL (expected " + condition + ", actual " + explanation.actual() + ")";
        results.add(ConditionResult.builder()
                .type(MATCH_SPEC_TYPE)
                .condition(condition)
                .passed(passed)
                .detail(detail)
                .build());
    }

    /** 結構化條件在匹配鏈中的類型名稱。 */
    public static final String MATCH_SPEC_TYPE = "matchSpec";

    /**
     * 簡易匹配（自動解析 body）
     */
    public boolean matches(String bodyCondition, String queryCondition, String headerCondition,
                          String body, String queryString, Map<String, String> headers) {
        PreparedBody prepared = prepareBody(body);
        return matchesPrepared(bodyCondition, queryCondition, headerCondition, prepared, queryString, headers);
    }

    public boolean matches(String bodyCondition, String queryCondition, String body, String queryString) {
        return matches(bodyCondition, queryCondition, null, body, queryString, null);
    }

    /** 單一條件字串：{@code ?} 開頭的是 query 條件，其餘是 body 條件。 */
    public boolean matches(String condition, String body, String queryString) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        StringJoiner bodyConditions = new StringJoiner(";");
        StringJoiner queryConditions = new StringJoiner(";");
        for (String cond : condition.split(";")) {
            String c = cond.trim();
            if (c.startsWith("?")) {
                queryConditions.add(c.substring(1));
            } else if (!c.isEmpty()) {
                bodyConditions.add(c);
            }
        }
        return matches(bodyConditions.toString(), queryConditions.toString(), body, queryString);
    }

    public boolean matches(String condition, String body) {
        return matches(condition, body, null);
    }

    // ==================== Structured Match Result Classes ====================

    /**
     * 結構化匹配結果，包含所有條件的 PASS/FAIL 細節
     */
    @Getter
    @Builder
    public static class ConditionDetail {
        private final boolean overallMatch;
        private final List<ConditionResult> results;

        public int passedCount() {
            return (int) results.stream().filter(ConditionResult::isPassed).count();
        }

        public int totalCount() {
            return results.size();
        }

        public String score() {
            return passedCount() + "/" + totalCount();
        }
    }

    /**
     * 單一條件的匹配結果
     */
    @Getter
    @Builder
    public static class ConditionResult {
        private final String type;       // "bodyCondition", "queryCondition", "headerCondition"
        private final String condition;  // 原始條件字串
        private final boolean passed;
        private final String detail;     // e.g., "bodyCondition PASS (user.type=vip)"
    }
}
