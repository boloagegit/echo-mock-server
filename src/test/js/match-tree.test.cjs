const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/static');

function load() {
  const context = {};
  vm.runInNewContext(fs.readFileSync(path.join(root, 'match-tree.js'), 'utf8') + '\nthis.MatchTree = MatchTree;', context);
  return context.MatchTree;
}
const plain = value => JSON.parse(JSON.stringify(value));

const SPEC = {
  all: [
    { source: 'header', name: 'X-Tenant', op: 'equals', value: 'acme', options: { caseInsensitive: true } },
    { any: [
      { source: 'body', selector: { jsonPath: '$.amount' }, op: 'gte', value: 1000 },
      { source: 'query', name: 'vip', op: 'exists' },
    ] },
    { not: { source: 'body', selector: { jsonPath: '$.status' }, op: 'equals', value: 'CANCELLED' } },
    { source: 'body', selector: { xpath: '//Code' }, op: 'in', value: ['A', 'B'] },
    { source: 'body', selector: { raw: true }, op: 'contains', value: 'x', quantifier: 'all', onMissing: 'pass' },
    { legacy: { source: 'query', condition: 'a=1' } },
  ],
};

test('fromSpec → toSpec round-trips every node kind, selector and option unchanged', () => {
  const MatchTree = load();
  assert.deepEqual(plain(MatchTree.toSpec(MatchTree.fromSpec(SPEC))), SPEC);
});

test('not is a negate flag; double not cancels; a non-group root is wrapped in all', () => {
  const MatchTree = load();
  const tree = MatchTree.fromSpec({ not: { not: { source: 'query', name: 'a', op: 'exists' } } });
  assert.equal(tree.kind, 'group');
  assert.equal(tree.children[0].negate, false);
  assert.deepEqual(plain(MatchTree.toSpec(tree)), { all: [{ source: 'query', name: 'a', op: 'exists' }] });
  const negatedGroup = MatchTree.fromSpec({ not: { any: [{ source: 'query', name: 'a', op: 'exists' }] } });
  assert.equal(negatedGroup.negate, true);
  assert.deepEqual(plain(MatchTree.toSpec(negatedGroup)), { not: { any: [{ source: 'query', name: 'a', op: 'exists' }] } });
});

test('edited values are typed by operator; untouched values keep their original JSON type', () => {
  const MatchTree = load();
  const tree = MatchTree.fromSpec(SPEC);
  const amount = tree.children[1].children[0];
  assert.equal(amount.valueText, '1000');
  amount.valueText = '2500.5';
  const codes = tree.children[3];
  codes.valueText = 'A\n  C \n\n';
  const spec = plain(MatchTree.toSpec(tree));
  assert.equal(spec.all[1].any[0].value, 2500.5);
  assert.deepEqual(spec.all[3].value, ['A', 'C']);

  const json = MatchTree.leaf('body');
  json.op = 'equalToJson';
  json.valueText = '{"items": [1, 2]}';
  json.options = { ignoreArrayOrder: true, caseInsensitive: true };
  assert.deepEqual(plain(MatchTree.toSpec(json)), {
    source: 'body', selector: { jsonPath: '$.' }, op: 'equalToJson', value: { items: [1, 2] },
    options: { ignoreArrayOrder: true },
  });
  json.valueText = '{broken';
  assert.throws(() => MatchTree.toSpec(json));

  const exists = MatchTree.leaf('header');
  exists.name = ' X-Trace ';
  exists.op = 'exists';
  exists.valueText = 'ignored';
  assert.deepEqual(plain(MatchTree.toSpec(exists)), { source: 'header', name: 'X-Trace', op: 'exists' });
});

test('legacy strings convert to legacy fragments and back only when nothing advanced is used', () => {
  const MatchTree = load();
  const tree = MatchTree.fromLegacyStrings('a=1; b!=2 ;', 'q=1', null);
  assert.deepEqual(plain(tree.children.map(c => [c.kind, c.source, c.condition])),
    [['legacy', 'body', 'a=1'], ['legacy', 'body', 'b!=2'], ['legacy', 'query', 'q=1']]);
  assert.deepEqual(plain(MatchTree.toLegacyStrings(tree)), { bodyCondition: 'a=1;b!=2', queryCondition: 'q=1', headerCondition: null });

  tree.children[0].negate = true;
  assert.equal(MatchTree.toLegacyStrings(tree), null);
  tree.children[0].negate = false;
  tree.children.push(MatchTree.leaf('header'));
  assert.equal(MatchTree.toLegacyStrings(tree), null);
  assert.equal(MatchTree.toLegacyStrings(MatchTree.group('any', [])), null);
  assert.deepEqual(plain(MatchTree.toLegacyStrings(MatchTree.group('all', []))),
    { bodyCondition: null, queryCondition: null, headerCondition: null });
});

test('summaries count leaves, mark negation and groups, and survive malformed specs', () => {
  const MatchTree = load();
  const summary = MatchTree.summarizeSpec(SPEC);
  assert.equal(summary.count, 7);
  assert.equal(summary.lines[0], 'header[X-Tenant] = acme');
  assert.deepEqual(summary.lines.slice(1, 4), ['ANY', '  body $.amount >= 1000', '  query[vip] exists']);
  assert.ok(summary.lines.includes('NOT body $.status = CANCELLED'));
  assert.ok(summary.line.startsWith('header[X-Tenant] = acme AND (body $.amount >= 1000 OR query[vip] exists) AND NOT'));
  assert.equal(MatchTree.countSpec({ bogus: true }), 1);
  assert.equal(MatchTree.countSpec('nope'), 0);
  assert.equal(MatchTree.summarizeSpec('nope').count, 0);
});

test('usesBody, hasConditions and remove walk nested groups', () => {
  const MatchTree = load();
  const tree = MatchTree.fromSpec(SPEC);
  assert.equal(MatchTree.usesBody(tree), true);
  assert.equal(MatchTree.hasConditions(MatchTree.group('all', [])), false);
  const nested = tree.children[1].children[1];
  MatchTree.remove(tree, nested.id);
  assert.equal(tree.children[1].children.length, 1);
  assert.equal(MatchTree.findParent(tree, tree.children[1].children[0].id), tree.children[1]);
});

// 與後端 LegacyConditionsTest 共用同一份案例，確保前端拆解條件的方式與比對時相同
const FIXTURE = JSON.parse(fs.readFileSync(
  path.resolve(__dirname, '../resources/fixtures/legacy-condition-parts.json'), 'utf8'));

test('legacy conditions split exactly like the backend (shared fixture)', () => {
  const MatchTree = load();
  assert.ok(FIXTURE.cases.length > 10);
  for (const { condition, parts } of FIXTURE.cases) {
    assert.deepEqual(plain(MatchTree.parseLegacyCondition(condition)), parts, condition);
  }
});

test('simple rows keep // paths and refuse conditions they cannot represent exactly', () => {
  const MatchTree = load();
  for (const { condition, simpleRow } of FIXTURE.cases) {
    assert.equal(MatchTree.toSimpleRows(condition, null, null) !== null, simpleRow, condition);
  }
  assert.deepEqual(plain(MatchTree.toSimpleRows('//Order/Status=OPEN; a=x!=y', 'page!=2', 'X-Type~=v.*')), [
    { type: 'body', field: '//Order/Status', operator: '=', value: 'OPEN' },
    { type: 'body', field: 'a', operator: '=', value: 'x!=y' },
    { type: 'query', field: 'page', operator: '!=', value: '2' },
    { type: 'header', field: 'X-Type', operator: '~=', value: 'v.*' },
  ]);
  // 一個片段無法表示就整組交給進階模式，不能只丟掉那一個
  assert.equal(MatchTree.toSimpleRows('a=1;hello', null, null), null);
  assert.deepEqual(plain(MatchTree.toSimpleRows(null, ' ; ', '')), []);
});

test('equality conditions whose extra = is kept are flagged (JSONPath, query and header)', () => {
  const MatchTree = load();
  assert.equal(MatchTree.hasDoubleEquals('$.a==b'), true);
  assert.equal(MatchTree.hasDoubleEquals('x-type==vip', 'header'), true);
  assert.equal(MatchTree.hasDoubleEquals('page==1', 'query'), true);
  // JSON 欄位路徑與 XML 路徑會把 == 當成 =
  for (const condition of ['//Status==OPEN', 'type==vip', 'Order/Status==OPEN', 'a = =b']) {
    assert.equal(MatchTree.hasDoubleEquals(condition), false, condition);
  }
  for (const condition of ['$.a=b', '$.a!==b', '$.a*==b', 'hello', '=x']) {
    assert.equal(MatchTree.hasDoubleEquals(condition), false, condition);
  }
  const tree = MatchTree.group('all', [MatchTree.legacy('body', ' $.a==b '), MatchTree.legacy('body', 'Status==OPEN'),
    MatchTree.legacy('query', 'p=1'), MatchTree.group('any', [MatchTree.legacy('header', 'X==y')])]);
  assert.deepEqual(plain(MatchTree.doubleEqualsConditions(tree)), ['$.a==b', 'X==y']);
});

// ==================== 匹配鏈的樹狀比對說明（detailTree） ====================

const EXPLAINED = {
  node: 'all', passed: false, children: [
    { node: 'leaf', passed: true, spec: { source: 'body', selector: { jsonPath: '$.type' }, op: 'equals', value: 'vip' }, actual: '"vip"' },
    { node: 'leaf', passed: false, spec: { source: 'header', name: 'X-Region', op: 'in', value: ['TW', 'HK'] }, actual: '"JP"' },
    { node: 'not', passed: true, children: [{ node: 'leaf', passed: false, spec: { source: 'header', name: 'X-Test', op: 'exists' }, missing: true }] },
    { node: 'legacy', source: 'query', passed: true, condition: 'page!=0', actual: '1' },
  ],
};

test('explain helpers: a root ALL lists its children; counts are top-level conditions', () => {
  const MatchTree = load();
  assert.equal(MatchTree.explainTopLevel(EXPLAINED).length, 4);
  assert.deepEqual(plain(MatchTree.explainCounts(EXPLAINED)), { total: 4, passed: 3 });
  const single = { node: 'any', passed: true, children: [] };
  assert.deepEqual(plain(MatchTree.explainTopLevel(single)), [single]);
  assert.deepEqual(plain(MatchTree.explainCounts(null)), { total: 0, passed: 0 });
  assert.equal(MatchTree.isExplainGroup(EXPLAINED.children[2]), true);
  assert.equal(MatchTree.isExplainGroup(EXPLAINED.children[0]), false);
});

test('explain helpers: under NOT a failing condition is what makes the group pass', () => {
  const MatchTree = load();
  const testHeader = EXPLAINED.children[2].children[0];
  assert.equal(MatchTree.explainOk(testHeader, true), true);
  assert.equal(MatchTree.explainOk(testHeader, false), false);
  assert.equal(MatchTree.explainOk(EXPLAINED.children[0], false), true);
});

test('explainLeaf: structured and legacy conditions split into target, op and value', () => {
  const MatchTree = load();
  assert.deepEqual(plain(MatchTree.explainLeaf(EXPLAINED.children[0])),
    { source: 'body', target: '$.type', op: 'equals', value: 'vip', options: [] });
  assert.deepEqual(plain(MatchTree.explainLeaf(EXPLAINED.children[1])),
    { source: 'header', target: 'X-Region', op: 'in', value: '[TW, HK]', options: [] });
  assert.equal(MatchTree.explainLeaf(EXPLAINED.children[2].children[0]).value, '');
  assert.deepEqual(plain(MatchTree.explainLeaf(EXPLAINED.children[3])),
    { source: 'query', target: 'page', op: 'notEquals', value: '0' });
  assert.equal(MatchTree.explainLeaf({ node: 'leaf', passed: true, spec: { source: 'body', selector: { raw: true }, op: 'equalToJson', value: { a: 1 } } }).value, '{"a":1}');
  assert.equal(MatchTree.explainLeaf({ node: 'leaf', passed: true, valueOmitted: true, spec: { source: 'header', name: 'X', op: 'in' } }).value, null);
  assert.deepEqual(plain(MatchTree.explainLeaf({ node: 'leaf', passed: false, description: 'custom thing' })), { source: null, raw: 'custom thing' });
  assert.deepEqual(plain(MatchTree.explainLeaf({ node: 'legacy', source: 'body', passed: false, condition: 'novalue' })), { source: 'body', raw: 'novalue' });
});
