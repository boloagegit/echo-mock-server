const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const staticRoot = path.resolve(__dirname, '../../main/resources/static');
const source = file => fs.readFileSync(path.join(staticRoot, file), 'utf8');

function load(files, extra = {}) {
  const storage = new Map();
  const context = vm.createContext({
    Vue: { createApp() {}, ref: value => ({ value }), computed() {}, watch() {}, onMounted() {}, onUnmounted() {} },
    _t: key => key,
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, String(value)) },
    ...extra,
  });
  vm.runInContext(files.map(source).join('\n') + '\nthis.exports = { UiCodeViewer: typeof UiCodeViewer !== "undefined" ? UiCodeViewer : null, UiDetailSection: typeof UiDetailSection !== "undefined" ? UiDetailSection : null, RuleDetail: typeof RuleDetail !== "undefined" ? RuleDetail : null };', context);
  return { ...context.exports, storage };
}

test('the code viewer formats JSON and XML up to 2 MB and shows plain text as it is', () => {
  const { UiCodeViewer } = load(['utils.js', 'components/UiCodeViewer.js']);
  const view = value => {
    const state = { value, formatted: true };
    state.mode = UiCodeViewer.computed.mode.call(state);
    state.canFormat = UiCodeViewer.computed.canFormat.call(state);
    return UiCodeViewer.computed.text.call(state);
  };
  assert.equal(view('{"a":1}'), '{\n  "a": 1\n}');
  assert.equal(view('<a><b/></a>'), '<a>\n  <b/>\n</a>');
  assert.equal(
    view('<?xml version="1.0"?><env:Envelope><env:Body><order id="1"><item>A</item><empty/></order></env:Body></env:Envelope>'),
    '<?xml version="1.0"?>\n<env:Envelope>\n  <env:Body>\n    <order id="1">\n      <item>A</item>\n      <empty/>\n    </order>\n  </env:Body>\n</env:Envelope>',
    'one element per line; a leaf with text stays on its line');
  assert.equal(view('plain text'), 'plain text');
  const huge = '{"a":"' + 'x'.repeat(2000001) + '"}';
  assert.equal(view(huge), huge, 'formatting is skipped above 2 MB');
});

test('search counts every match in the full text and steps around the ends', () => {
  const { UiCodeViewer } = load(['utils.js', 'components/UiCodeViewer.js']);
  const marks = [];
  const vmState = {
    text: 'Customer customer CUSTOMER', query: 'customer', marks: [], matches: [], matchIndex: 0, matchCount: 0,
    cm: { operation: fn => fn(), posFromIndex: i => i, markText: () => { const m = { clear() {} }; marks.push(m); return m; }, scrollIntoView() {} },
  };
  vmState.showMatch = UiCodeViewer.methods.showMatch.bind(vmState);
  UiCodeViewer.methods.search.call(vmState);
  assert.equal(vmState.matchCount, 3);
  UiCodeViewer.methods.step.call(vmState, -1);
  assert.equal(vmState.matchIndex, 2, 'stepping back from the first match wraps to the last');
});

test('a collapsed drawer section stays collapsed the next time', () => {
  const { UiDetailSection, storage } = load(['components/UiDetailSection.js']);
  const section = { id: 'rule.response', ...UiDetailSection.data.call({ id: 'rule.response' }) };
  assert.equal(section.open, true);
  UiDetailSection.methods.toggle.call(section);
  assert.equal(JSON.parse(storage.get('echo.drawerSections'))['rule.response'], true);
  assert.equal(UiDetailSection.data.call({ id: 'rule.response' }).open, false);
});

test('many conditions show the first six across groups, then all on request', () => {
  const { RuleDetail } = load(['utils.js', 'components/RuleDetail.js']);
  const computed = RuleDetail.computed;
  const groups = [
    { type: 'body', items: Array.from({ length: 4 }, (_, i) => ({ v: 'b' + i })) },
    { type: 'query', items: Array.from({ length: 5 }, (_, i) => ({ v: 'q' + i })) },
  ];
  const vmState = { conditionGroups: groups, showAllConditions: false };
  vmState.conditionCount = computed.conditionCount.call(vmState);
  const visible = computed.visibleConditionGroups.call(vmState);
  assert.equal(vmState.conditionCount, 9);
  assert.deepEqual(visible.map(g => g.items.length), [4, 2]);
  vmState.showAllConditions = true;
  assert.equal(computed.visibleConditionGroups.call(vmState), groups);
});
