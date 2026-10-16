package com.echo.service;

import com.echo.core.match.InvalidMatchSpecException;
import com.echo.core.match.LegacyConditions;
import com.echo.core.match.MatchNode;
import com.echo.core.match.MatchSpecCodec;
import com.echo.core.match.MatcherCompiler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 儲存規則時檢查並正規化結構化條件（{@code matchSpec}）。
 * <p>
 * 所有錯誤在儲存時回報（含 JSON 路徑），不會留到請求進來才變成「不符合」。能用舊版條件字串表達的條件
 * 會轉回三個舊欄位，{@code matchSpec} 只保存真正需要的進階條件，避免新舊欄位不一致。
 */
public final class MatchSpecs {

    /** 條件 JSON 的長度上限（字元）。 */
    public static final int MAX_SPEC_CHARS = 64 * 1024;
    /** 組合節點（all／any／not）最多巢狀層數。 */
    public static final int MAX_DEPTH = 16;
    /** 條件樹節點總數上限。 */
    public static final int MAX_NODES = 256;
    /** {@code in} 清單最多項目數。 */
    public static final int MAX_IN_VALUES = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MatcherCompiler COMPILER = MatcherCompiler.withDefaults();

    private MatchSpecs() {
    }

    /**
     * 正規化後的條件：{@code matchSpec} 與三個舊欄位只會有一邊有值（都沒有時為無條件規則）。
     *
     * @param matchSpec       規範化的條件 JSON；可用舊欄位表達時為 null
     * @param bodyCondition   舊版 body 條件
     * @param queryCondition  舊版 query 條件
     * @param headerCondition 舊版 header 條件
     */
    public record Normalized(String matchSpec, String bodyCondition, String queryCondition,
                             String headerCondition) {

        public boolean advanced() {
            return matchSpec != null;
        }
    }

    /**
     * 檢查並正規化條件。
     *
     * @param spec           使用者提供的條件 JSON
     * @param allowedSources 此協定可用的取值來源（見 {@code ProtocolDescriptor#matchSources()}）
     * @throws InvalidMatchSpecException 條件不合法，訊息包含 JSON 路徑
     */
    public static Normalized normalize(JsonNode spec, Set<String> allowedSources) {
        if (spec == null || spec.isNull() || spec.isMissingNode()) {
            throw new InvalidMatchSpecException("$", "expected an object");
        }
        if (spec.toString().length() > MAX_SPEC_CHARS) {
            throw new InvalidMatchSpecException("$", "is longer than " + MAX_SPEC_CHARS + " characters");
        }
        MatchNode node = MatchSpecCodec.parse(spec);
        new Walker(allowedSources).walk(node, "$", 0);
        // 運算子、來源、選項與期望值的完整檢查
        COMPILER.compile(node);

        Optional<LegacyConditions.Strings> legacy = LegacyConditions.toStrings(node);
        if (legacy.isPresent()) {
            LegacyConditions.Strings strings = legacy.get();
            return new Normalized(null, strings.bodyCondition(), strings.queryCondition(),
                    strings.headerCondition());
        }
        return new Normalized(MatchSpecCodec.toJson(node).toString(), null, null, null);
    }

    /** 讀取已儲存的條件 JSON；格式錯誤時回傳 empty（只用於顯示，比對時另有保護）。 */
    public static Optional<JsonNode> read(String stored) {
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readTree(stored));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /** 條件樹中的條件數（葉節點與舊版片段），供自動產生的描述使用。 */
    public static int conditionCount(String stored) {
        return read(stored).map(MatchSpecs::conditionCount).orElse(0);
    }

    public static int conditionCount(JsonNode spec) {
        if (spec == null || spec.isNull()) {
            return 0;
        }
        try {
            return count(MatchSpecCodec.parse(spec));
        } catch (InvalidMatchSpecException e) {
            return 0;
        }
    }

    /** DTO 的條件 JSON 轉成儲存用字串；沒有條件時為 null。 */
    public static String toStored(JsonNode spec) {
        return spec == null || spec.isNull() || spec.isMissingNode() ? null : spec.toString();
    }

    private static int count(MatchNode node) {
        if (node instanceof MatchNode.All all) {
            return all.children().stream().mapToInt(MatchSpecs::count).sum();
        }
        if (node instanceof MatchNode.Any any) {
            return any.children().stream().mapToInt(MatchSpecs::count).sum();
        }
        if (node instanceof MatchNode.Not not) {
            return count(not.child());
        }
        return 1;
    }

    /** 檢查巢狀層數、節點數、來源與 {@code in} 清單長度。 */
    private static final class Walker {
        private final Set<String> allowedSources;
        private int nodes;

        private Walker(Set<String> allowedSources) {
            this.allowedSources = allowedSources;
        }

        void walk(MatchNode node, String path, int depth) {
            if (++nodes > MAX_NODES) {
                throw new InvalidMatchSpecException(path, "has more than " + MAX_NODES + " nodes in total");
            }
            if (depth > MAX_DEPTH) {
                throw new InvalidMatchSpecException(path, "is nested deeper than " + MAX_DEPTH + " levels");
            }
            if (node instanceof MatchNode.All all) {
                walkChildren(all.children(), path + ".all", depth);
            } else if (node instanceof MatchNode.Any any) {
                walkChildren(any.children(), path + ".any", depth);
            } else if (node instanceof MatchNode.Not not) {
                walk(not.child(), path + ".not", depth + 1);
            } else if (node instanceof MatchNode.Legacy legacy) {
                requireSource(legacy.source().jsonName(), path + ".legacy.source");
            } else {
                MatchNode.Leaf leaf = (MatchNode.Leaf) node;
                requireSource(leaf.source(), path + ".source");
                JsonNode value = leaf.value();
                if ("in".equals(leaf.op()) && value != null && value.isArray() && value.size() > MAX_IN_VALUES) {
                    throw new InvalidMatchSpecException(path + ".value",
                            "has more than " + MAX_IN_VALUES + " values");
                }
            }
        }

        private void walkChildren(java.util.List<MatchNode> children, String path, int depth) {
            for (int i = 0; i < children.size(); i++) {
                walk(children.get(i), path + "[" + i + "]", depth + 1);
            }
        }

        private void requireSource(String source, String path) {
            if (!allowedSources.contains(source)) {
                throw new InvalidMatchSpecException(path, "source '" + source
                        + "' is not available for this protocol (available: " + new TreeSet<>(allowedSources) + ")");
            }
        }
    }
}
