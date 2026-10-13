const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/static');

function load() {
  const context = {};
  vm.runInNewContext(fs.readFileSync(path.join(root, 'rule-order.js'), 'utf8') + '\nthis.RuleOrder = RuleOrder;', context);
  return context.RuleOrder;
}
const plain = value => JSON.parse(JSON.stringify(value));
const entries = count => Array.from({ length: count }, (_, i) => ({ id: 'r' + i }));

test('short lists are shown in full', () => {
  const RuleOrder = load();
  assert.equal(RuleOrder.visibleEntries(entries(RuleOrder.SHOW_ALL_UP_TO)).length, RuleOrder.SHOW_ALL_UP_TO);
});

test('long lists keep the head, the rule itself with its neighbours and the rules to keep; the rest collapse into gaps', () => {
  const RuleOrder = load();
  const shown = plain(RuleOrder.visibleEntries(entries(200), ['r3', 'r120', 'r180'], 'r120'));
  const ids = shown.map(item => item.id || 'gap:' + item.gap);
  assert.deepEqual(ids, [
    ...Array.from({ length: 15 }, (_, i) => 'r' + i),
    'gap:104', 'r119', 'r120', 'r121', 'gap:58', 'r180', 'gap:19',
  ]);
  const total = shown.reduce((sum, item) => sum + (item.gap || 1), 0);
  assert.equal(total, 200);
});

test('a rule at the very start or end does not add missing neighbours', () => {
  const RuleOrder = load();
  const last = plain(RuleOrder.visibleEntries(entries(40), [], 'r39'));
  assert.deepEqual(last.slice(-3).map(item => item.id || 'gap'), ['gap', 'r38', 'r39']);
});
