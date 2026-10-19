package com.echo.core.match;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conditional requirements for request body are used to determine how body should be prepared (§4.5). Provided by compiled conditions
 * ({@link CompiledMatcher#bodyRequirements()}), the calling end combines the requirements of all candidate rules and decides:
 * <ul>
 *   <li>is not needed at all body: does not resolve;</li>
 *   <li>XML body and no condition requires the complete DOM: scan once with {@link XmlStreamScan} to pre-calculate the result of the streaming conditions;</li>
 *   <li>Other cases: parse as usual (JSON tree or DOM), shared by all rules.</li>
 * </ul>
 * Immutable and can be shared among multiple threads.
 */
public final class BodyRequirements {

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

    private BodyRequirements(boolean usesBody, boolean needsXmlDom, Map<String, StreamCheck> streamChecks) {
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
        for (BodyRequirements requirement : requirements) {
            usesBody |= requirement.usesBody;
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
        return new BodyRequirements(true, needsXmlDom, checks == null ? Map.of() : Map.copyOf(checks));
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
        default void skipped() {
        }

        /** Results after the scan ends (or ends early). */
        boolean result();
    }

    @Override
    public String toString() {
        return "BodyRequirements[usesBody=" + usesBody + ", needsXmlDom=" + needsXmlDom
                + ", streamChecks=" + List.copyOf(streamChecks.keySet()) + "]";
    }
}
