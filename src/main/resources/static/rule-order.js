/**
 * rule-order.js - 判斷順序很長時（同一個路徑有很多規則）要顯示哪些項目。
 *
 * 純函式、不依賴 Vue，可在 Node 測試中直接載入。
 */
const RuleOrder = (() => {
    /** 項目不超過這個數量時全部顯示。 */
    const SHOW_ALL_UP_TO = 25;
    /** 超過時開頭顯示的數量。 */
    const HEAD = 15;

    /**
     * 回傳要顯示的項目，被省略的連續項目以 {gap: 數量} 代替。
     * 一定顯示：開頭幾項、{@code keepIds} 中的項目（目前這條規則、擋住它的規則、預設回應）與目前這條規則前後各一項。
     *
     * @param entries 依檢查順序排列的項目（有 id）
     * @param keepIds 一定要顯示的 id
     * @param selfId  目前這條規則的 id
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
