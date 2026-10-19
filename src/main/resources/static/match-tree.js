/**
 * match-tree.js - Conversion between structured conditions (matchSpec) and editing screen tree model.
 *
 * Pure function, does not depend on Vue, can be loaded directly in Node test. For the backend format, see MatchSpecCodec:
 *   {all:[...]} {any:[...]} {not:node} {legacy:{source,condition}}
 *   {source, name?, selector?, op, value?, quantifier?, onMissing?, options?}
 *
 * Edit screen nodes:
 *   group  {id, kind:'group', op:'all'|'any', negate, children:[]}
 *   leaf   {id, kind:'leaf', source, name, selectorKind, selector, op, valueText, options, negate, ...}
 *   legacy {id, kind:'legacy', source, condition, negate}
 * `not` is represented by negate on the node; two consecutive layers of not will cancel each other out.
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

    /** The expected value is the text in the input box; in is separated by newlines, and equalToJson is formatted JSON. */
    const valueToText = (op, value) => {
        if (value === undefined || value === null) return '';
        if (op === 'in' && Array.isArray(value)) return value.map(v => (typeof v === 'string' ? v : JSON.stringify(v))).join('\n');
        if (op === 'equalToJson') return JSON.stringify(value, null, 2);
        return typeof value === 'string' ? value : JSON.stringify(value);
    };

    /** Backend JSON → Edit tree. The root node must be group to facilitate adding new conditions. */
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

    /** Expected value text → JSON; if the text is not changed, the original JSON will be used (for example, the number 1000 will not become the string "1000"). */
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
     * Edit tree → backend JSON. An exception is thrown when the JSON format of equalToJson is incorrect (prompted by the screen);
     * When lenient is changed, the original text is retained (switching to declarative editing and other "not saving" situations cannot make the condition disappear).
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

    /** The three condition strings of the old version → all containing only the old version of the fragment (the same cutting method as the backend LegacyConditions.toMatchNode). */
    const fromLegacyStrings = (bodyCondition, queryCondition, headerCondition) => {
        const children = [];
        [['body', bodyCondition], ['query', queryCondition], ['header', headerCondition]].forEach(([source, text]) => {
            (text || '').split(';').map(part => part.trim()).filter(part => part).forEach(part => children.push(legacy(source, part)));
        });
        return group('all', children);
    };

    /**
     * Disassemble a single old version condition, the rules are the same as the backend comparison ({@code LegacyConditions.parts}): look at the first =,
     * When the preceding character is ~, *, !, it is ~=, *=, != respectively, otherwise it is =. For example, a=x!=y is the field a and the value x!=y.
     * Without =, or = returns null as the first character.
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
     * Expect an "equal" condition whose value begins with = (usually written as ==), and the = will be treated as part of the expected value:
     * JSONPath (starting with $.), query and header do not treat == as =; JSON field paths and XML paths do (§4.4 #3, §7.2.9).
     */
    const hasDoubleEquals = (condition, source = 'body') => {
        const parsed = parseLegacyCondition(condition);
        return !!parsed && parsed.operator === '=' && parsed.value.startsWith('=')
            && (source !== 'body' || parsed.field.startsWith('$.'));
    };

    /** Old format snippet text with the above problem in the tree. */
    const doubleEqualsConditions = node => (node.kind === 'group'
        ? node.children.flatMap(doubleEqualsConditions)
        : (node.kind === 'legacy' && hasDoubleEquals((node.condition || '').trim(), node.source) ? [node.condition.trim()] : []));

    /** Only when the conditional column is written back to the string and the disassembly result remains unchanged can it be expressed by the conditional column (for example, "a! =b" written back to "a!=b" will become not equal). */
    const simpleRowOf = (type, condition) => {
        const parsed = parseLegacyCondition(condition);
        if (!parsed || !parsed.field || !parsed.value) return null;
        const again = parseLegacyCondition(parsed.field + parsed.operator + parsed.value);
        if (!again || again.field !== parsed.field || again.operator !== parsed.operator || again.value !== parsed.value) return null;
        return { type, ...parsed };
    };

    /**
     * The old version of three strings is converted into a conditional column in simple mode (the fields are retained as they are, including XPath at the beginning of //).
     * When any fragment cannot be fully represented by the condition column, null is returned, and the caller switches to advanced mode display to avoid losing or changing conditions during storage.
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

    /** If the tree is only an old version fragment that "matches all", return three old version strings; otherwise, return null (cannot switch back to simple mode without losing the conditions). */
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

    /** Condition number (leaf nodes and legacy fragments). */
    const count = node => (node.kind === 'group' ? node.children.reduce((sum, child) => sum + count(child), 0) : 1);
    const countSpec = spec => { try { return count(fromSpec(spec)); } catch { return 0; } };

    /** Text for a single condition, such as header[X-Tenant] = acme, body $.amount >= 1000. */
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

    /** Multi-line summary: Combination nodes are marked with ALL/ANY and indented for details and prompt text. */
    const describeLines = (node, depth = 0) => {
        const indent = '  '.repeat(depth);
        if (node.kind !== 'group') return [indent + describeLeaf(node)];
        const head = indent + (node.negate ? 'NOT ' : '') + (node.op === 'all' ? 'ALL' : 'ANY');
        return [head, ...node.children.flatMap(child => describeLines(child, depth + 1))];
    };

    /** One-line summary for use with rule lists. */
    const describe = node => {
        if (node.kind !== 'group') return describeLeaf(node);
        const joiner = node.op === 'all' ? ' AND ' : ' OR ';
        const inner = node.children.map(child => (child.kind === 'group' ? '(' + describe(child) + ')' : describe(child))).join(joiner);
        return node.negate ? 'NOT (' + inner + ')' : inner;
    };

    const summarizeSpec = spec => {
        try {
            const tree = fromSpec(spec);
            // ALL of the root node is the default and does not need to be displayed separately.
            const lines = tree.op === 'all' && !tree.negate
                ? tree.children.flatMap(child => describeLines(child, 0))
                : describeLines(tree, 0);
            return { line: describe(tree), lines, count: count(tree) };
        } catch {
            return { line: JSON.stringify(spec), lines: [JSON.stringify(spec)], count: 0 };
        }
    };

    /** Whether there are any conditions in the tree (if not, it is a wildcard rule). */
    const hasConditions = tree => count(tree) > 0;

    /** Does it contain old format clips (the screen prompts them to use the simple mode comparison method). */
    const hasLegacy = node => (node.kind === 'group' ? node.children.some(hasLegacy) : node.kind === 'legacy');

    /** Is there a condition for the body source (GET etc. without body method reminder). */
    const usesBody = node => (node.kind === 'group' ? node.children.some(usesBody) : node.source === 'body');

    /** Find the parent node with id for deletion and relocation. */
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

    // ==================== Tree comparison description of matching chain (backend MatchExplanationJson) ====================

    const LEGACY_OPERATORS = { '=': 'equals', '!=': 'notEquals', '*=': 'contains', '~=': 'matches' };

    const isExplainGroup = node => !!node && (node.node === 'all' || node.node === 'any' || node.node === 'not');

    /** Nodes listed at the top level: when the root node is "all must match", the child nodes are listed directly (the same as the rule summary, the default ALL is not displayed separately). */
    const explainTopLevel = tree => {
        if (!tree) return [];
        return tree.node === 'all' ? (tree.children || []) : [tree];
    };

    /** The top level condition number and pass number. */
    const explainCounts = tree => {
        const top = explainTopLevel(tree);
        return { total: top.length, passed: top.filter(node => node.passed).length };
    };

    /** Whether the node is helpful to the overall result: Under NOT, it is considered helpful if it does not match. */
    const explainOk = (node, negated) => !!node.passed !== !!negated;

    /**
     * Display fields for a single condition {source, target, op, value, options}; op is the name in OPS (translated by the screen).
     * Only {source, raw} cannot be disassembled.
     */
    const explainLeaf = node => {
        if (node.node === 'legacy') {
            const parsed = parseLegacyCondition(node.condition);
            if (!parsed) return { source: node.source, raw: node.condition };
            return { source: node.source, target: parsed.field, op: LEGACY_OPERATORS[parsed.operator], value: parsed.value };
        }
        if (!isObject(node.spec)) return { source: null, raw: node.description || '' };
        try {
            const item = fromSpec(node.spec).children[0];
            const target = item.source === 'body'
                ? (item.selectorKind === 'raw' ? '' : item.selector)
                : item.name;
            let value = item.valueText;
            if (node.valueOmitted) value = null;
            else if (NO_VALUE_OPS.includes(item.op)) value = '';
            else if (item.op === 'in') value = '[' + item.valueText.split('\n').join(', ') + ']';
            else if (item.op === 'equalToJson') value = JSON.stringify(node.spec.value);
            return { source: item.source, target, op: item.op, value, options: Object.keys(item.options || {}) };
        } catch {
            return { source: node.spec.source || null, raw: JSON.stringify(node.spec) };
        }
    };

    return {
        isExplainGroup, explainTopLevel, explainCounts, explainOk, explainLeaf,
        OPS, NO_VALUE_OPS, NUMERIC_OPS, SELECTOR_KINDS, OP_SYMBOLS,
        group, leaf, legacy, fromSpec, toSpec, fromLegacyStrings, toLegacyStrings, optionsFor,
        parseLegacyCondition, toSimpleRows, hasDoubleEquals, doubleEqualsConditions,
        count, countSpec, describe, describeLines, summarizeSpec, hasConditions, hasLegacy, usesBody, findParent, remove,
    };
})();
