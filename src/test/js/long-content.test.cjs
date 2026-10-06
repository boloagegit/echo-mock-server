const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const staticRoot = path.resolve(__dirname, '../../main/resources/static');
const source = file => fs.readFileSync(path.join(staticRoot, file), 'utf8');

const context = vm.createContext({
  Vue: { createApp() {}, ref: value => ({ value }), computed() {}, watch() {}, onMounted() {}, onUnmounted() {} },
  _t: key => key,
});
vm.runInContext(source('utils.js') + `
this.formatBodyForReading = formatBodyForReading;
this.BODY_PREVIEW_CHARS = BODY_PREVIEW_CHARS;
this.DETAIL_LIST_PREVIEW = DETAIL_LIST_PREVIEW;`, context);
vm.runInContext(source('components/RuleDetail.js') + '\nthis.RuleDetail = RuleDetail;', context);
const computed = context.RuleDetail.computed;

test('small JSON is pretty-printed; other or very large bodies are shown as they are', () => {
  assert.equal(context.formatBodyForReading('{"a":1}'), '{\n  "a": 1\n}');
  assert.equal(context.formatBodyForReading('plain <text>'), 'plain <text>');
  const huge = '{"a":"' + 'x'.repeat(600000) + '"}';
  assert.equal(context.formatBodyForReading(huge), huge, 'formatting is skipped above 512 KB');
  assert.equal(context.formatBodyForReading(''), '');
});

test('a very large body renders only its beginning until the reader asks for all of it', () => {
  const vmState = { showFullBody: false, shownDetail: { _previewBody: 'y'.repeat(context.BODY_PREVIEW_CHARS + 10) } };
  vmState.bodyFull = computed.bodyFull.call(vmState);
  vmState.bodyTruncated = computed.bodyTruncated.call(vmState);
  assert.equal(vmState.bodyTruncated, true);
  assert.equal(computed.body.call(vmState).length, context.BODY_PREVIEW_CHARS);
  vmState.showFullBody = true;
  vmState.bodyTruncated = computed.bodyTruncated.call(vmState);
  assert.equal(computed.body.call(vmState).length, context.BODY_PREVIEW_CHARS + 10);
});

test('many conditions show the first six across groups, then all on request', () => {
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
