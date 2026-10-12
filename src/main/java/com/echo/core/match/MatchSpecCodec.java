package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@link MatchNode} 與 JSON 之間的轉換。
 * <p>
 * 這裡只檢查結構；運算子、來源、選項是否存在由 {@link MatcherCompiler} 依註冊表檢查。
 *
 * <pre>{@code
 * {"all": [ <node>, ... ]}
 * {"any": [ <node>, ... ]}
 * {"not": <node>}
 * {"source": "header", "name": "X-Tenant", "op": "equals", "value": "acme",
 *  "quantifier": "any", "onMissing": "auto", "options": {"caseInsensitive": true}}
 * {"source": "body", "selector": {"jsonPath": "$.amount"}, "op": "gte", "value": 1000}
 * {"legacy": {"source": "body", "condition": "user.type=vip"}}
 * }</pre>
 */
public final class MatchSpecCodec {

    private static final Set<String> LEAF_FIELDS =
            Set.of("source", "name", "selector", "op", "value", "quantifier", "onMissing", "options");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private MatchSpecCodec() {
    }

    /**
     * 從 JSON 解析條件樹。
     *
     * @throws InvalidMatchSpecException 結構不合法
     */
    public static MatchNode parse(JsonNode json) {
        return parse(json, "$");
    }

    /** 規範化的 JSON：欄位順序固定、省略預設值、選項依名稱排序。 */
    public static ObjectNode toJson(MatchNode node) {
        ObjectNode json = NODES.objectNode();
        if (node instanceof MatchNode.All all) {
            json.set("all", toJsonArray(all.children()));
        } else if (node instanceof MatchNode.Any any) {
            json.set("any", toJsonArray(any.children()));
        } else if (node instanceof MatchNode.Not not) {
            json.set("not", toJson(not.child()));
        } else if (node instanceof MatchNode.Legacy legacy) {
            json.putObject("legacy")
                    .put("source", legacy.source().jsonName())
                    .put("condition", legacy.condition());
        } else {
            writeLeaf((MatchNode.Leaf) node, json);
        }
        return json;
    }

    // ==================== parse ====================

    private static MatchNode parse(JsonNode json, String path) {
        if (json == null || !json.isObject()) {
            throw new InvalidMatchSpecException(path, "expected an object");
        }
        if (json.has("all") || json.has("any")) {
            String key = json.has("all") ? "all" : "any";
            requireOnlyField(json, key, path);
            List<MatchNode> children = parseChildren(json.get(key), path + "." + key);
            return "all".equals(key) ? new MatchNode.All(children) : new MatchNode.Any(children);
        }
        if (json.has("not")) {
            requireOnlyField(json, "not", path);
            return new MatchNode.Not(parse(json.get("not"), path + ".not"));
        }
        if (json.has("legacy")) {
            requireOnlyField(json, "legacy", path);
            return parseLegacy(json.get("legacy"), path + ".legacy");
        }
        return parseLeaf(json, path);
    }

    private static List<MatchNode> parseChildren(JsonNode array, String path) {
        if (!array.isArray()) {
            throw new InvalidMatchSpecException(path, "expected an array");
        }
        List<MatchNode> children = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            children.add(parse(array.get(i), path + "[" + i + "]"));
        }
        return children;
    }

    private static void requireOnlyField(JsonNode json, String field, String path) {
        if (json.size() != 1) {
            throw new InvalidMatchSpecException(path, "'" + field + "' must be the only field of its object");
        }
    }

    private static MatchNode.Leaf parseLeaf(JsonNode json, String path) {
        Iterator<String> names = json.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!LEAF_FIELDS.contains(name)) {
                throw new InvalidMatchSpecException(path + "." + name, "unknown field");
            }
        }
        String source = requiredText(json, "source", path);
        String op = requiredText(json, "op", path);
        String name = optionalText(json, "name", path);
        Selector selector = json.has("selector") ? parseSelector(json.get("selector"), path + ".selector") : null;
        MatchNode.Quantifier quantifier = parseEnum(json, "quantifier", MatchNode.Quantifier.class, path);
        MatchNode.OnMissing onMissing = parseEnum(json, "onMissing", MatchNode.OnMissing.class, path);
        Map<String, JsonNode> options = parseOptions(json.get("options"), path + ".options");
        return new MatchNode.Leaf(source, name, selector, op, json.get("value"), quantifier, onMissing, options);
    }

    private static MatchNode.Legacy parseLegacy(JsonNode json, String path) {
        if (json == null || !json.isObject() || json.size() != 2 || !json.has("source") || !json.has("condition")) {
            throw new InvalidMatchSpecException(path, "expected an object with exactly source and condition");
        }
        String sourceName = requiredText(json, "source", path);
        MatchNode.LegacySource source = MatchNode.LegacySource.fromJsonName(sourceName);
        if (source == null) {
            throw new InvalidMatchSpecException(path + ".source", "expected body, query or header");
        }
        String condition = requiredText(json, "condition", path);
        try {
            return new MatchNode.Legacy(source, condition);
        } catch (IllegalArgumentException e) {
            throw new InvalidMatchSpecException(path + ".condition", e.getMessage(), e);
        }
    }

    private static Selector parseSelector(JsonNode json, String path) {
        if (!json.isObject() || json.size() != 1) {
            throw new InvalidMatchSpecException(path,
                    "expected an object with exactly one of jsonPath, xpath, field, raw");
        }
        String key = json.fieldNames().next();
        Selector.Kind kind = Selector.Kind.fromJsonName(key);
        if (kind == null) {
            throw new InvalidMatchSpecException(path + "." + key, "unknown selector");
        }
        JsonNode value = json.get(key);
        if (kind == Selector.Kind.RAW) {
            if (!value.isBoolean() || !value.booleanValue()) {
                throw new InvalidMatchSpecException(path + ".raw", "expected true");
            }
            return Selector.raw();
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new InvalidMatchSpecException(path + "." + key, "expected a non-blank string");
        }
        return new Selector(kind, value.asText());
    }

    private static Map<String, JsonNode> parseOptions(JsonNode json, String path) {
        if (json == null) {
            return Map.of();
        }
        if (!json.isObject()) {
            throw new InvalidMatchSpecException(path, "expected an object");
        }
        Map<String, JsonNode> options = new LinkedHashMap<>();
        json.properties().forEach(entry -> options.put(entry.getKey(), entry.getValue()));
        return options;
    }

    private static String requiredText(JsonNode json, String field, String path) {
        String text = optionalText(json, field, path);
        if (text == null) {
            throw new InvalidMatchSpecException(path + "." + field, "is required");
        }
        return text;
    }

    private static String optionalText(JsonNode json, String field, String path) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new InvalidMatchSpecException(path + "." + field, "expected a non-blank string");
        }
        return value.asText();
    }

    private static <E extends Enum<E>> E parseEnum(JsonNode json, String field, Class<E> type, String path) {
        String text = optionalText(json, field, path);
        if (text == null) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(text)) {
                return constant;
            }
        }
        throw new InvalidMatchSpecException(path + "." + field, "unknown value '" + text + "'");
    }

    // ==================== write ====================

    private static ArrayNode toJsonArray(List<MatchNode> children) {
        ArrayNode array = NODES.arrayNode();
        children.forEach(child -> array.add(toJson(child)));
        return array;
    }

    private static void writeLeaf(MatchNode.Leaf leaf, ObjectNode json) {
        json.put("source", leaf.source());
        if (leaf.name() != null) {
            json.put("name", leaf.name());
        }
        if (leaf.selector() != null) {
            ObjectNode selector = json.putObject("selector");
            if (leaf.selector().kind() == Selector.Kind.RAW) {
                selector.put("raw", true);
            } else {
                selector.put(leaf.selector().kind().jsonName(), leaf.selector().expression());
            }
        }
        json.put("op", leaf.op());
        if (leaf.value() != null) {
            json.set("value", leaf.value());
        }
        if (leaf.quantifier() != MatchNode.Quantifier.ANY) {
            json.put("quantifier", leaf.quantifier().name().toLowerCase(Locale.ROOT));
        }
        if (leaf.onMissing() != MatchNode.OnMissing.AUTO) {
            json.put("onMissing", leaf.onMissing().name().toLowerCase(Locale.ROOT));
        }
        if (!leaf.options().isEmpty()) {
            ObjectNode options = json.putObject("options");
            new TreeMap<>(leaf.options()).forEach(options::set);
        }
    }
}
