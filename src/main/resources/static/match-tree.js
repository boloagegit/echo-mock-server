/**
 * match-tree.js - 結構化條件（matchSpec）與編輯畫面樹狀模型之間的轉換。
 *
 * 純函式、不依賴 Vue，可在 Node 測試中直接載入。後端格式見 MatchSpecCodec：
 *   {all:[...]} {any:[...]} {not:node} {legacy:{source,condition}}
 *   {source, name?, selector?, op, value?, quantifier?, onMissing?, options?}
 *
 * 編輯畫面的節點：
 *   group  {id, kind:'group', op:'all'|'any', negate, children:[]}
 *   leaf   {id, kind:'leaf', source, name, selectorKind, selector, op, valueText, options, negate, ...}
 *   legacy {id, kind:'legacy', source, condition, negate}
 * `not` 以節點上的 negate 表示；連續兩層 not 會互相抵銷。
 */
const MatchTree = (() => {
    const OPS = ['equals', 'notEquals', 'contains', 'matches', 'exists', 'absent', 'gt', 'gte', 'lt', 'lte', 'in', 'equalToJson'];
    const NO_VALUE_OPS = ['exists', 'absent'];
    const NUMERIC_OPS = ['gt', 'gte', 'lt', 'lte'];
    const CASE_INSENSITIVE_OPS = ['equals', 'notEquals', 'contains'];
    const SELECTOR_KINDS = ['jsonPath', 'xpath', 'field', 'raw'];
    const OP_SYMBOLS = { equals: '=', notEquals: '!=', contains: '*=', matches: '~=', gt: '>', gte: '>=', lt: '<', lte: '<=' };

    let nextId = 1;
    const newId = () => 'm' + (nextId++);

    const group = (op = 'all', children = []) => ({ id: newId(), kind: 'group', op, negate: false, children });
    const leaf = (source = 'body') => ({
        id: newId(), kind: 'leaf', source, name: '', selectorKind: source === 'body' ? 'jsonPath' : '',
        selector: source === 'body' ? '$.' : '', op: 'equals', valueText: '', options: {}, negate: false,
        quantifier: null, onMissing: null, original: null,
    });
    const legacy = (source, condition) => ({ id: newId(), kind: 'legacy', source, condition, negate: false });

    const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);

    /** 期望值在輸入框中的文字；in 以換行分隔，equalToJson 為格式化的 JSON。 */
    const valueToText = (op, value) => {
        if (value === undefined || value === null) return '';
        if (op === 'in' && Array.isArray(value)) return value.map(v => (typeof v === 'string' ? v : JSON.stringify(v))).join('\n');
        if (op === 'equalToJson') return JSON.stringify(value, null, 2);
        return typeof value === 'string' ? value : JSON.stringify(value);
    };

    /** 後端 JSON → 編輯樹。根節點一定是 group，方便新增條件。 */
    const fromSpec = spec => {
        const root = toNode(spec, false);
        return root.kind === 'group' ? root : group('all', [root]);
    };

    const toNode = (json, negate) => {
        if (!isObject(json)) throw new Error('Invalid matchSpec node');
        if ('not' in json) return toNode(json.not, !negate);
        if (Array.isArray(json.all) || Array.isArray(json.any)) {
            const op = Array.isArray(json.all) ? 'all' : 'any';
            const node = group(op, json[op].map(child => toNode(child, false)));
            node.negate = negate;
            return node;
        }
        if (isObject(json.legacy)) {
            const node = legacy(json.legacy.source, json.legacy.condition);
            node.negate = negate;
            return node;
        }
        const node = leaf(json.source);
        const selector = isObject(json.selector) ? json.selector : null;
        const selectorKind = selector ? SELECTOR_KINDS.find(kind => kind in selector) || 'jsonPath' : '';
        Object.assign(node, {
            name: json.name || '',
            selectorKind,
            selector: selector && selectorKind !== 'raw' ? String(selector[selectorKind]) : '',
            op: json.op,
            valueText: valueToText(json.op, json.value),
            options: isObject(json.options) ? { ...json.options } : {},
            quantifier: json.quantifier || null,
            onMissing: json.onMissing || null,
            negate,
            original: { op: json.op, text: valueToText(json.op, json.value), value: json.value },
        });
        return node;
    };

    /** 期望值文字 → JSON；文字沒改時沿用原本的 JSON（例如數字 1000 不會變成字串 "1000"）。 */
    const textToValue = (node, lenient) => {
        if (NO_VALUE_OPS.includes(node.op)) return undefined;
        if (node.original && node.original.op === node.op && node.original.text === node.valueText) return node.original.value;
        const text = node.valueText ?? '';
        if (node.op === 'in') return text.split('\n').map(line => line.trim()).filter(line => line);
        if (node.op === 'equalToJson') {
            if (!lenient) return JSON.parse(text);
            try { return JSON.parse(text); } catch { return text; }
        }
        if (NUMERIC_OPS.includes(node.op)) {
            const number = Number(text.trim());
            return text.trim() !== '' && Number.isFinite(number) ? number : text;
        }
        return text;
    };

    /**
     * 編輯樹 → 後端 JSON。equalToJson 的 JSON 格式錯誤時丟出例外（由畫面提示）；
     * lenient 時改以原始文字保留（切換到宣告式編輯等「不是儲存」的情況，不能讓條件消失）。
     */
    const toSpec = (node, lenient = false) => {
        let json;
        if (node.kind === 'group') {
            json = { [node.op]: node.children.map(child => toSpec(child, lenient)) };
        } else if (node.kind === 'legacy') {
            json = { legacy: { source: node.source, condition: (node.condition || '').trim() } };
        } else {
            json = { source: node.source };
            if (node.source !== 'body' && node.name) json.name = node.name.trim();
            if (node.source === 'body') {
                json.selector = node.selectorKind === 'raw' ? { raw: true } : { [node.selectorKind || 'jsonPath']: (node.selector || '').trim() };
            }
            json.op = node.op;
            const value = textToValue(node, lenient);
            if (value !== undefined) json.value = value;
            if (node.quantifier) json.quantifier = node.quantifier;
            if (node.onMissing) json.onMissing = node.onMissing;
            const options = {};
            Object.entries(node.options || {}).forEach(([key, enabled]) => {
                if (enabled && optionsFor(node.op).includes(key)) options[key] = true;
            });
            if (Object.keys(options).length) json.options = options;
        }
        return node.negate ? { not: json } : json;
    };

    const optionsFor = op => (CASE_INSENSITIVE_OPS.includes(op) ? ['caseInsensitive']
        : op === 'equalToJson' ? ['ignoreArrayOrder', 'ignoreExtraElements'] : []);

    /** 舊版三個條件字串 → 只含舊版片段的 all（與後端 LegacyConditions.toMatchNode 相同的切法）。 */
    const fromLegacyStrings = (bodyCondition, queryCondition, headerCondition) => {
        const children = [];
        [['body', bodyCondition], ['query', queryCondition], ['header', headerCondition]].forEach(([source, text]) => {
            (text || '').split(';').map(part => part.trim()).filter(part => part).forEach(part => children.push(legacy(source, part)));
        });
        return group('all', children);
    };

    /**
     * 拆解單一舊版條件，規則與後端比對時相同（{@code LegacyConditions.parts}）：看第一個 =，
     * 它前一個字元是 ~、*、! 時分別是 ~=、*=、!=，否則是 =。例如 a=x!=y 是欄位 a、值 x!=y。
     * 沒有 =，或 = 在第一個字元時回傳 null。
     */
    const parseLegacyCondition = condition => {
        const text = String(condition ?? '');
        const eq = text.indexOf('=');
        if (eq <= 0) return null;
        const previous = text.charAt(eq - 1);
        const operator = previous === '~' || previous === '*' || previous === '!' ? previous + '=' : '=';
        return { field: text.substring(0, eq - operator.length + 1).trim(), operator, value: text.substring(eq + 1).trim() };
    };

    /**
     * 期望值以 = 開頭的「等於」條件（通常是寫成 ==）。舊版只有 JSON body 的欄位路徑會把 == 當成 =，
     * JSONPath、XPath、XML body、query 與 header 都會把多出的 = 當成期望值的一部分（§4.4 #3）。
     */
    const hasDoubleEquals = condition => {
        const parsed = parseLegacyCondition(condition);
        return !!parsed && parsed.operator === '=' && parsed.value.startsWith('=');
    };

    /** 樹中有上述問題的舊格式片段原文。 */
    const doubleEqualsConditions = node => (node.kind === 'group'
        ? node.children.flatMap(doubleEqualsConditions)
        : (node.kind === 'legacy' && hasDoubleEquals((node.condition || '').trim()) ? [node.condition.trim()] : []));

    /** 條件列寫回字串後拆解結果不變，才算能用條件列表示（例如 "a! =b" 寫回 "a!=b" 會變成不等於）。 */
    const simpleRowOf = (type, condition) => {
        const parsed = parseLegacyCondition(condition);
        if (!parsed || !parsed.field || !parsed.value) return null;
        const again = parseLegacyCondition(parsed.field + parsed.operator + parsed.value);
        if (!again || again.field !== parsed.field || again.operator !== parsed.operator || again.value !== parsed.value) return null;
        return { type, ...parsed };
    };

    /**
     * 舊版三個字串轉成簡易模式的條件列（欄位原樣保留，包括 // 開頭的 XPath）。
     * 任何片段無法用條件列完整表示時回傳 null，呼叫端改用進階模式顯示，避免儲存時遺失或改變條件。
     */
    const toSimpleRows = (bodyCondition, queryCondition, headerCondition) => {
        const rows = [];
        for (const [type, text] of [['body', bodyCondition], ['query', queryCondition], ['header', headerCondition]]) {
            for (const part of (text || '').split(';').map(item => item.trim()).filter(item => item)) {
                const row = simpleRowOf(type, part);
                if (!row) return null;
                rows.push(row);
            }
        }
        return rows;
    };

    /** 若樹只是「全部符合」的舊版片段，回傳三個舊版字串；否則回傳 null（無法切回簡易模式而不遺失條件）。 */
    const toLegacyStrings = tree => {
        if (tree.kind !== 'group' || tree.op !== 'all' || tree.negate) return null;
        const parts = { body: [], query: [], header: [] };
        for (const child of tree.children) {
            if (child.kind !== 'legacy' || child.negate || !parts[child.source]) return null;
            const condition = (child.condition || '').trim();
            if (condition) parts[child.source].push(condition);
        }
        const join = list => (list.length ? list.join(';') : null);
        return { bodyCondition: join(parts.body), queryCondition: join(parts.query), headerCondition: join(parts.header) };
    };

    /** 條件數（葉節點與舊版片段）。 */
    const count = node => (node.kind === 'group' ? node.children.reduce((sum, child) => sum + count(child), 0) : 1);
    const countSpec = spec => { try { return count(fromSpec(spec)); } catch { return 0; } };

    /** 單一條件的文字，例如 header[X-Tenant] = acme、body $.amount >= 1000。 */
    const describeLeaf = node => {
        const prefix = node.negate ? 'NOT ' : '';
        if (node.kind === 'legacy') return prefix + node.source + ': ' + node.condition;
        let target = node.source;
        if (node.source !== 'body' && node.name) target += '[' + node.name + ']';
        if (node.source === 'body') target += node.selectorKind === 'raw' ? ' (raw)' : ' ' + (node.selector || '');
        const op = OP_SYMBOLS[node.op] || node.op;
        if (NO_VALUE_OPS.includes(node.op)) return prefix + target + ' ' + op;
        const value = node.op === 'in' ? '[' + node.valueText.split('\n').filter(v => v.trim()).join(', ') + ']'
            : node.op === 'equalToJson' ? 'JSON' : node.valueText;
        return prefix + target + ' ' + op + ' ' + value;
    };

    /** 多行摘要：組合節點以 ALL／ANY 標示並縮排，供詳情與提示文字使用。 */
    const describeLines = (node, depth = 0) => {
        const indent = '  '.repeat(depth);
        if (node.kind !== 'group') return [indent + describeLeaf(node)];
        const head = indent + (node.negate ? 'NOT ' : '') + (node.op === 'all' ? 'ALL' : 'ANY');
        return [head, ...node.children.flatMap(child => describeLines(child, depth + 1))];
    };

    /** 一行摘要，供規則清單使用。 */
    const describe = node => {
        if (node.kind !== 'group') return describeLeaf(node);
        const joiner = node.op === 'all' ? ' AND ' : ' OR ';
        const inner = node.children.map(child => (child.kind === 'group' ? '(' + describe(child) + ')' : describe(child))).join(joiner);
        return node.negate ? 'NOT (' + inner + ')' : inner;
    };

    const summarizeSpec = spec => {
        try {
            const tree = fromSpec(spec);
            // 根節點的 ALL 是預設，不需要另外顯示
            const lines = tree.op === 'all' && !tree.negate
                ? tree.children.flatMap(child => describeLines(child, 0))
                : describeLines(tree, 0);
            return { line: describe(tree), lines, count: count(tree) };
        } catch {
            return { line: JSON.stringify(spec), lines: [JSON.stringify(spec)], count: 0 };
        }
    };

    /** 樹中是否有任何條件（沒有時為萬用規則）。 */
    const hasConditions = tree => count(tree) > 0;

    /** 是否含舊格式片段（畫面上提示它們沿用簡易模式的比對方式）。 */
    const hasLegacy = node => (node.kind === 'group' ? node.children.some(hasLegacy) : node.kind === 'legacy');

    /** 是否有 body 來源的條件（GET 等無 body 方法的提醒）。 */
    const usesBody = node => (node.kind === 'group' ? node.children.some(usesBody) : node.source === 'body');

    /** 以 id 找到父節點，供刪除與搬移使用。 */
    const findParent = (root, id) => {
        if (root.kind !== 'group') return null;
        for (const child of root.children) {
            if (child.id === id) return root;
            const found = findParent(child, id);
            if (found) return found;
        }
        return null;
    };

    const remove = (root, id) => {
        const parent = findParent(root, id);
        if (parent) parent.children = parent.children.filter(child => child.id !== id);
    };

    return {
        OPS, NO_VALUE_OPS, NUMERIC_OPS, SELECTOR_KINDS, OP_SYMBOLS,
        group, leaf, legacy, fromSpec, toSpec, fromLegacyStrings, toLegacyStrings, optionsFor,
        parseLegacyCondition, toSimpleRows, hasDoubleEquals, doubleEqualsConditions,
        count, countSpec, describe, describeLines, summarizeSpec, hasConditions, hasLegacy, usesBody, findParent, remove,
    };
})();
