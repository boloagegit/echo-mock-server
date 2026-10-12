package com.echo.core.match;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 條件對請求 body 的需求，用來決定 body 要怎麼準備（§4.5）。由編譯後的條件提供
 * （{@link CompiledMatcher#bodyRequirements()}），呼叫端把所有候選規則的需求合併後決定：
 * <ul>
 *   <li>完全不需要 body：不解析；</li>
 *   <li>XML body 且沒有條件需要完整的 DOM：以 {@link XmlStreamScan} 掃描一次，預先算出可串流條件的結果；</li>
 *   <li>其他情況：照常解析（JSON tree 或 DOM），由所有規則共用。</li>
 * </ul>
 * 不可變，可在多執行緒間共用。
 */
public final class BodyRequirements {

    /** 不需要 body（query、header 等條件）。 */
    public static final BodyRequirements NONE = new BodyRequirements(false, false, Map.of());

    /** 需要完整解析（無法判斷需求的條件，例如外部註冊的取值來源，一律視為此類）。 */
    public static final BodyRequirements FULL = new BodyRequirements(true, true, Map.of());

    /** 只需要 body 原文或 JSON，XML body 時不需要 DOM。 */
    static final BodyRequirements BODY_WITHOUT_XML = new BodyRequirements(true, false, Map.of());

    private final boolean usesBody;
    private final boolean needsXmlDom;
    /** 可串流的條件，key 為條件的結果暫存 key，相同條件只算一次。 */
    private final Map<String, StreamCheck> streamChecks;

    private BodyRequirements(boolean usesBody, boolean needsXmlDom, Map<String, StreamCheck> streamChecks) {
        this.usesBody = usesBody || !streamChecks.isEmpty();
        this.needsXmlDom = needsXmlDom;
        this.streamChecks = streamChecks;
    }

    /** XML body 時可用串流算出結果的條件（不需要 DOM）。 */
    static BodyRequirements streamable(StreamCheck check) {
        return new BodyRequirements(true, false, Map.of(check.cacheKey(), check));
    }

    /** 合併多個條件的需求。 */
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

    /** 是否有任何條件會讀 body；否則不必解析。 */
    public boolean usesBody() {
        return usesBody;
    }

    /** XML body 時是否有條件需要完整的 DOM（無法串流）。 */
    public boolean needsXmlDom() {
        return needsXmlDom;
    }

    /** 可串流的條件數量。 */
    public int streamCheckCount() {
        return streamChecks.size();
    }

    Collection<StreamCheck> streamChecks() {
        return streamChecks.values();
    }

    /**
     * 一個可在串流掃描中算出結果的條件：路徑上的每個值依序交給 {@link Probe}，
     * 結果以 {@link #cacheKey()} 存進請求的結果暫存，比對時直接使用。
     */
    interface StreamCheck {

        XmlPath path();

        /** 與條件的 {@link CompiledMatcher#cacheKey()} 相同。 */
        String cacheKey();

        /** 單一請求的評估狀態。 */
        Probe newProbe();
    }

    /** 單一請求內，一個可串流條件的評估狀態。不是執行緒安全的。 */
    interface Probe {

        /**
         * 交給路徑上的下一個值（已去除前後空白）。
         *
         * @return 結果已確定、不需要更多值時回傳 true
         */
        boolean offer(String value);

        /** 路徑上有一個值，但超過長度上限而略過（與 DOM 取值時略過過長的值相同）。 */
        default void skipped() {
        }

        /** 掃描結束（或提前結束）後的結果。 */
        boolean result();
    }

    @Override
    public String toString() {
        return "BodyRequirements[usesBody=" + usesBody + ", needsXmlDom=" + needsXmlDom
                + ", streamChecks=" + List.copyOf(streamChecks.keySet()) + "]";
    }
}
