/**
 * rule-order.js - Determine which items to display when the sequence is very long (the same path has many rules).
 *
 * Pure function, does not depend on Vue, can be loaded directly in Node test.
 */
const RuleOrder = (() => {
    /** All items will be displayed if they do not exceed this number. */
    const SHOW_ALL_UP_TO = 25;
    /** The number shown at the beginning when exceeded. */
    const HEAD = 15;

    /**
     * Return the items to be displayed. Omitted consecutive items are replaced by {gap: quantity}.
     * Must display: the first few items, the items in {@code keepIds} (the current rule, the rules blocking it, the default response) and one item before and after the current rule.
     *
     * @param entries Items arranged in order of inspection (including id)
     * @param keepIds id must be displayed
     * @param selfId  The current rule is id
     */
    const visibleEntries = (entries, keepIds = [], selfId = null) => {
        if (entries.length <= SHOW_ALL_UP_TO) return entries.slice();
        const keep = new Set();
        for (let i = 0; i < HEAD; i++) keep.add(i);
        entries.forEach((entry, i) => {
            if (keepIds.includes(entry.id)) keep.add(i);
            if (entry.id === selfId) { keep.add(i - 1); keep.add(i); keep.add(i + 1); }
        });
        const result = [];
        let hidden = 0;
        entries.forEach((entry, i) => {
            if (keep.has(i)) {
                if (hidden) { result.push({ gap: hidden }); hidden = 0; }
                result.push(entry);
            } else {
                hidden++;
            }
        });
        if (hidden) result.push({ gap: hidden });
        return result;
    };

    return { visibleEntries, SHOW_ALL_UP_TO };
})();
