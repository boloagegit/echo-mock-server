/**
 * audit-diff.js - Line-by-line comparison of revision records.
 *
 * Pure function, does not depend on Vue, can be loaded directly in Node test.
 * Use the Myers algorithm to find the minimum additions and deletions, and then cut them into "changed blocks", leaving a few lines of unchanged content before and after each block.
 * When the content is too large or has too many differences, tooLarge is returned, and the screen is changed to display the full text before and after to prevent the browser from getting stuck.
 */
const AuditDiff = (() => {
    const MAX_CHARS = 200000;
    const MAX_EDITS = 2000;
    /** "Show full text" can only draw a few lines; longer content only displays changed blocks to avoid generating too many elements at once. */
    const MAX_FULL_LINES = 5000;

    const splitLines = (text) => (text === null || text === undefined || text === '') ? [] : String(text).split('\n');

    /** Myers shortest editing path; return [{type:' '|'-'|'+', text}], return null when the difference exceeds the upper limit. */
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
     * Compare two paragraphs of text.
     * @returns {{tooLarge:boolean, added:number, removed:number, lineCount:number, hunks:Array<{lines:Array<{type:string,text:string,oldNo:?number,newNo:?number}>}>}}
     *   When context is Infinity, there is only one block, including the full text; when it exceeds MAX_FULL_LINES, only the changed blocks are still returned.
     */
    const lines = (before, after, options = {}) => {
        const maxChars = options.maxChars ?? MAX_CHARS;
        let context = options.context ?? 3;
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
        const lineCount = numbered.length;
        if (context === Infinity) {
            if (lineCount <= MAX_FULL_LINES) { return { tooLarge: false, added, removed, lineCount, hunks: lineCount ? [{ lines: numbered }] : [] }; }
            context = 3;
        }

        // Leave the changed lines and the context lines before and after them, and combine them into the same block.
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
        return { tooLarge: false, added, removed, lineCount, hunks };
    };

    return { lines, MAX_CHARS, MAX_FULL_LINES };
})();
