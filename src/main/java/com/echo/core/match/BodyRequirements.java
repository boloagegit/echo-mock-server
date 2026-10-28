package com.echo.core.match;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conditional requirements for request body are used to determine how body should be prepared (§4.5). Provided by compiled conditions
 * ({@link CompiledMatcher#bodyRequirements()}), the calling end combines the requirements of all candidate rules and decides:
 * <ul>
 *   <li>is not needed at all body: does not resolve;</li>
 *   <li>XML body and no condition requires the complete DOM: scan once with {@link XmlStreamScan} to pre-calculate the result of the streaming conditions;</li>
 *   <li>Known JSON paths: retain only their values after validating the first root; otherwise share the complete JSON tree or XML DOM.</li>
 * </ul>
 * Immutable and can be shared among multiple threads.
 */
public final class BodyRequirements {
    private static final Pattern ROOT =
            Pattern.compile("^\\$\\.([A-Za-z_][A-Za-z0-9_]*)(?=$|[.\\[])");
    private static final Pattern OBJECT_PATH =
            Pattern.compile("^\\$\\.[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*$");

    static String jsonPath(String expression) {
        if (expression.indexOf('$', 1) >= 0) return null;
        if (OBJECT_PATH.matcher(expression).matches()
                && expression.chars().filter(c -> c == '.').count() <= 16)
            return expression.substring(2);
        var match = ROOT.matcher(expression);
        return match.find() ? match.group(1) : null;
    }

    private final Set<String> jsonPaths;
    private volatile JsonProjection jsonProjection;

    /** body is not required (query, header, etc. conditions). */
    public static final BodyRequirements NONE = new BodyRequirements(false, false, Map.of());

    /** Complete analysis is required (conditions that cannot be judged, such as externally registered value sources, will be regarded as such). */
    public static final BodyRequirements FULL = new BodyRequirements(true, true, Map.of());

    /** Only body original text or JSON is required, DOM is not required for XML body. */
    static final BodyRequirements BODY_WITHOUT_XML = new BodyRequirements(true, false, Map.of());

    private final boolean usesBody;
    private final boolean needsXmlDom;

    /** Conditions that can be streamed, key is the result of the condition that is temporarily stored in key, and the same condition is only counted once. */
    private final Map<String, StreamCheck> streamChecks;

    private BodyRequirements(
            boolean usesBody, boolean needsXmlDom, Map<String, StreamCheck> streamChecks) {
        this(usesBody, needsXmlDom, streamChecks, usesBody ? null : Set.of());
    }

    private BodyRequirements(
            boolean usesBody,
            boolean needsXmlDom,
            Map<String, StreamCheck> streamChecks,
            Set<String> jsonPaths) {
        this.jsonPaths = jsonPaths == null ? null : Set.copyOf(jsonPaths);
        this.usesBody = usesBody || !streamChecks.isEmpty();
        this.needsXmlDom = needsXmlDom;
        this.streamChecks = streamChecks;
    }

    /** XML body can be streamed to calculate the result conditions (DOM is not required). */
    static BodyRequirements streamable(StreamCheck check) {
        return new BodyRequirements(true, false, Map.of(check.cacheKey(), check));
    }

    /** Combine requirements for multiple conditions. */
    public static BodyRequirements merge(Collection<BodyRequirements> requirements) {
        boolean usesBody = false;
        boolean needsXmlDom = false;
        Map<String, StreamCheck> checks = null;
        Set<String> fields = new HashSet<>();
        boolean fullJson = false;
        for (BodyRequirements requirement : requirements) {
            usesBody |= requirement.usesBody;
            if (requirement.usesBody) {
                if (requirement.jsonPaths == null) fullJson = true;
                else fields.addAll(requirement.jsonPaths);
            }
            needsXmlDom |= requirement.needsXmlDom;
            if (!requirement.streamChecks.isEmpty()) {
                if (checks == null) {
                    checks = new LinkedHashMap<>();
                }
                checks.putAll(requirement.streamChecks);
            }
        }
        if (!usesBody) {
            return NONE;
        }
        return new BodyRequirements(
                true,
                needsXmlDom,
                checks == null ? Map.of() : Map.copyOf(checks),
                fullJson || fields.size() > 16 ? null : fields);
    }

    /** Required object paths; null requires an unrestricted complete tree. */
    public Set<String> jsonPaths() {
        return jsonPaths;
    }

    BodyRequirements withJsonPath(String path) {
        return new BodyRequirements(usesBody, needsXmlDom, streamChecks, Set.of(path));
    }

    public JsonProjection jsonProjection() {
        if (jsonPaths == null) return null;
        JsonProjection result = jsonProjection;
        if (result == null) {
            result = JsonProjection.paths(jsonPaths);
            jsonProjection = result;
        }
        return result;
    }

    public static boolean covers(Set<String> available, Set<String> required) {
        if (required == null) return false;
        for (String path : required) {
            if (available.contains(path)) continue;
            boolean covered = false;
            for (int at = path.indexOf('.'); at >= 0; at = path.indexOf('.', at + 1)) {
                if (available.contains(path.substring(0, at))) {
                    covered = true;
                    break;
                }
            }
            if (!covered) return false;
        }
        return true;
    }

    /** Bounded immutable metadata, containing rule paths and no request values. */
    public record JsonProjection(boolean wholeValue, Map<String, JsonProjection> children) {
        private static final JsonProjection WHOLE = new JsonProjection(true, Map.of());

        public JsonProjection {
            children = Map.copyOf(children);
        }

        public static JsonProjection fields(Set<String> fields) {
            Map<String, JsonProjection> result = new HashMap<>();
            for (String field : fields) result.put(field, WHOLE);
            return new JsonProjection(false, result);
        }

        private static JsonProjection paths(Set<String> paths) {
            MutableProjection root = new MutableProjection();
            int nodes = 1;
            for (String path : paths) {
                MutableProjection current = root;
                for (String part : path.split("\\.")) {
                    if (current.whole) break;
                    MutableProjection next = current.children.get(part);
                    if (next == null) {
                        next = new MutableProjection();
                        current.children.put(part, next);
                        if (++nodes > 64) {
                            Set<String> roots = new HashSet<>();
                            for (String value : paths) roots.add(value.split("\\.", 2)[0]);
                            return fields(roots);
                        }
                    }
                    current = next;
                }
                current.whole = true;
                current.children.clear();
            }
            return root.freeze();
        }
    }

    private static final class MutableProjection {
        private boolean whole;
        private final Map<String, MutableProjection> children = new HashMap<>();

        private JsonProjection freeze() {
            if (whole) return JsonProjection.WHOLE;
            Map<String, JsonProjection> result = new HashMap<>();
            children.forEach((name, value) -> result.put(name, value.freeze()));
            return new JsonProjection(false, result);
        }
    }

    /** Is there any condition that will read body; otherwise no need to parse. */
    public boolean usesBody() {
        return usesBody;
    }

    /** Is there a condition that requires the complete DOM when XML body (unable to stream). */
    public boolean needsXmlDom() {
        return needsXmlDom;
    }

    /** Number of conditions that can be streamed. */
    public int streamCheckCount() {
        return streamChecks.size();
    }

    Collection<StreamCheck> streamChecks() {
        return streamChecks.values();
    }

    /**
     * A condition that can be evaluated in a stream scan: each value on the path is given to {@link Probe} in sequence,
     * The result is temporarily stored in the requested result as {@link #cacheKey()} and used directly during comparison.
     */
    interface StreamCheck {

        XmlPath path();

        /** Same as {@link CompiledMatcher#cacheKey()} of condition. */
        String cacheKey();

        /** Evaluation status of a single request. */
        Probe newProbe();
    }

    /** Evaluation status of a streamable condition within a single request. Not thread safe. */
    interface Probe {

        /**
         * Hand over the next value on the path (with leading and trailing whitespace removed).
         *
         * @return Return true when the result is determined and no more values are needed.
         */
        boolean offer(String value);

        /** There is a value on the path, but it exceeds the upper limit of the length and is skipped (the same as DOM skipping a value that is too long). */
        default void skipped() {}

        /** Results after the scan ends (or ends early). */
        boolean result();
    }

    @Override
    public String toString() {
        return "BodyRequirements[usesBody="
                + usesBody
                + ", needsXmlDom="
                + needsXmlDom
                + ", streamChecks="
                + List.copyOf(streamChecks.keySet())
                + "]";
    }
}
