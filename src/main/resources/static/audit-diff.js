/**
 * audit-diff.js - 修訂記錄的逐行比對。
 *
 * 純函式、不依賴 Vue，可在 Node 測試中直接載入。
 * 以 Myers 演算法找出最少的新增與刪除，再切成「有變更的區塊」，每塊前後各留幾行未變的內容。
 * 內容太大或差異太多時回傳 tooLarge，畫面改顯示前後全文，避免瀏覽器卡住。
 */
const AuditDiff = (() => {
    const MAX_CHARS = 200000;
    const MAX_EDITS = 2000;

    const splitLines = (text) => (text === null || text === undefined || text === '') ? [] : String(text).split('\n');

    /** Myers 最短編輯路徑；回傳 [{type:' '|'-'|'+', text}]，差異超過上限時回傳 null。 */
    const editScript = (a, b, maxEdits) => {
        const n = a.length, m = b.length, max = Math.min(n + m, maxEdits);
        const offset = max + 1;
        let v = new Int32Array(2 * max + 3);
        const trace = [];
        for (let d = 0; d <= max; d++) {
            trace.push(v.slice());
            for (let k = -d; k <= d; k += 2) {
                let x = (k === -d || (k !== d && v[offset + k - 1] < v[offset + k + 1])) ? v[offset + k + 1] : v[offset + k - 1] + 1;
                let y = x - k;
                while (x < n && y < m && a[x] === b[y]) { x++; y++; }
                v[offset + k] = x;
                if (x >= n && y >= m) { return backtrack(trace, v, a, b, d, offset); }
            }
        }
        return null;
    };

    const backtrack = (trace, last, a, b, dEnd, offset) => {
        const ops = [];
        let x = a.length, y = b.length;
        for (let d = dEnd; d > 0; d--) {
            const v = trace[d];
            const k = x - y;
            const prevK = (k === -d || (k !== d && v[offset + k - 1] < v[offset + k + 1])) ? k + 1 : k - 1;
            const prevX = v[offset + prevK];
            const prevY = prevX - prevK;
            while (x > prevX && y > prevY) { ops.push({ type: ' ', text: a[x - 1] }); x--; y--; }
            if (x === prevX) { ops.push({ type: '+', text: b[y - 1] }); y--; }
            else { ops.push({ type: '-', text: a[x - 1] }); x--; }
        }
        while (x > 0 && y > 0) { ops.push({ type: ' ', text: a[x - 1] }); x--; y--; }
        return ops.reverse();
    };

    /**
     * 比對兩段文字。
     * @returns {{tooLarge:boolean, added:number, removed:number, hunks:Array<{lines:Array<{type:string,text:string,oldNo:?number,newNo:?number}>}>}}
     *   context 為 Infinity 時只有一個區塊，包含全文。
     */
    const lines = (before, after, options = {}) => {
        const maxChars = options.maxChars ?? MAX_CHARS;
        const context = options.context ?? 3;
        const beforeText = before ?? '', afterText = after ?? '';
        if (String(beforeText).length > maxChars || String(afterText).length > maxChars) {
            return { tooLarge: true, added: 0, removed: 0, hunks: [] };
        }
        const ops = editScript(splitLines(beforeText), splitLines(afterText), options.maxEdits ?? MAX_EDITS);
        if (!ops) { return { tooLarge: true, added: 0, removed: 0, hunks: [] }; }

        let oldNo = 0, newNo = 0, added = 0, removed = 0;
        const numbered = ops.map(op => {
            if (op.type === ' ') { oldNo++; newNo++; return { ...op, oldNo, newNo }; }
            if (op.type === '-') { oldNo++; removed++; return { ...op, oldNo, newNo: null }; }
            newNo++; added++; return { ...op, oldNo: null, newNo };
        });
        if (context === Infinity) { return { tooLarge: false, added, removed, hunks: numbered.length ? [{ lines: numbered }] : [] }; }

        // 留下有變更的行與前後 context 行，相連的合成同一區塊。
        const keep = new Array(numbered.length).fill(false);
        numbered.forEach((line, i) => {
            if (line.type === ' ') { return; }
            for (let j = Math.max(0, i - context); j <= Math.min(numbered.length - 1, i + context); j++) { keep[j] = true; }
        });
        const hunks = [];
        let current = null;
        numbered.forEach((line, i) => {
            if (!keep[i]) { current = null; return; }
            if (!current) { current = { lines: [] }; hunks.push(current); }
            current.lines.push(line);
        });
        return { tooLarge: false, added, removed, hunks };
    };

    return { lines, MAX_CHARS };
})();
