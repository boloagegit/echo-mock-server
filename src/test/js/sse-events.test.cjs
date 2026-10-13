const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/static');

function load() {
  const context = { window: {}, document: {}, Vue: {}, TextDecoder, setTimeout, clearTimeout };
  // 與 index.html 相同：utils.js（createSseParser）在 sse-events.js 之前
  vm.runInNewContext(fs.readFileSync(path.join(root, 'utils.js'), 'utf8') + '\n'
    + fs.readFileSync(path.join(root, 'sse-events.js'), 'utf8') + '\nthis.SseEvents = SseEvents;', context);
  return context.SseEvents;
}
const plain = value => JSON.parse(JSON.stringify(value));
const zh = (key, p) => ({
  'sseEditor.durationMs': `${p.ms} ms`, 'sseEditor.durationSec': `${p.s} 秒`,
  'sseEditor.durationMinSec': `${p.m} 分 ${p.s} 秒`, 'sseEditor.durationMin': `${p.m} 分鐘`
})[key];
const ev = (delayMs, type = 'normal', data = 'x') => ({ event: '', data, id: '', delayMs, type });

test('timeline: cumulative send times and total', () => {
  const S = load();
  const result = S.timeline([ev(300), ev(800), ev(0)]);
  assert.deepEqual(plain(result.rows.map(r => r.atMs)), [300, 1100, 1100]);
  assert.equal(result.totalMs, 1100);
  assert.equal(result.endIndex, -1);
  assert.equal(result.skipped, 0);
});

test('timeline: error ends after sending, abort ends without sending, later events are skipped', () => {
  const S = load();
  const withError = S.timeline([ev(100), ev(200, 'error'), ev(500), ev(500)]);
  assert.deepEqual(plain(withError.rows.map(r => r.state)), ['sent', 'ends-after', 'skipped', 'skipped']);
  assert.equal(withError.totalMs, 300);
  assert.equal(withError.endIndex, 1);
  assert.equal(withError.skipped, 2);
  assert.equal(withError.rows[2].atMs, null);
  const withAbort = S.timeline([ev(100), ev(900, 'abort')]);
  assert.deepEqual(plain(withAbort.rows.map(r => r.state)), ['sent', 'ends']);
  assert.equal(withAbort.totalMs, 1000);
});

test('timeline: missing, negative or non-numeric delays count as 0', () => {
  const S = load();
  assert.equal(S.timeline([ev(undefined), ev(-5), ev('7'), ev(NaN), ev(10)]).totalMs, 10);
});

test('formatDuration: ms, seconds, minutes', () => {
  const S = load();
  assert.equal(S.formatDuration(0, zh), '0 ms');
  assert.equal(S.formatDuration(300, zh), '300 ms');
  assert.equal(S.formatDuration(1000, zh), '1 秒');
  assert.equal(S.formatDuration(1500, zh), '1.5 秒');
  assert.equal(S.formatDuration(1234, zh), '1.234 秒');
  assert.equal(S.formatDuration(59999, zh), '59.999 秒');
  assert.equal(S.formatDuration(60000, zh), '1 分鐘');
  assert.equal(S.formatDuration(90000, zh), '1 分 30 秒');
  assert.equal(S.formatDuration(61500, zh), '1 分 1.5 秒');
  assert.equal(S.formatDuration(600000, zh), '10 分鐘');
});

test('byteLength counts UTF-8 bytes, including Chinese and emoji', () => {
  const S = load();
  assert.equal(S.byteLength('abc'), 3);
  assert.equal(S.byteLength('é'), 2);
  assert.equal(S.byteLength('你好'), 6);
  assert.equal(S.byteLength('😀'), 4);
  assert.equal(S.byteLength(null), 0);
  const sample = '{"delta":"你好😀","n":1}';
  assert.equal(S.byteLength(sample), Buffer.byteLength(sample));
  assert.equal(S.formatBytes(34), '34 B');
  assert.equal(S.formatBytes(3277), '3.2 KB');
  assert.equal(S.formatBytes(5 * 1024 * 1024), '5 MB');
});

test('previewLine collapses whitespace and truncates', () => {
  const S = load();
  assert.equal(S.previewLine('{\n  "a": 1,\n  "b": 2\n}'), '{ "a": 1, "b": 2 }');
  assert.equal(S.previewLine('x'.repeat(200), 10), 'xxxxxxxxxx…');
  assert.equal(S.previewLine(''), '');
});

test('ms/s units: opening unit, conversion both ways and the 10 minute limit', () => {
  const S = load();
  assert.equal(S.unitFor(0), 'ms');
  assert.equal(S.unitFor(300), 'ms');
  assert.equal(S.unitFor(1500), 'ms');
  assert.equal(S.unitFor(90000), 's');
  assert.equal(S.toUnit(300, 's'), 0.3);
  assert.equal(S.toUnit(1234, 's'), 1.234);
  assert.equal(S.toUnit(300, 'ms'), 300);
  assert.equal(S.fromUnit('1.5', 's'), 1500);
  assert.equal(S.fromUnit('1.2345', 's'), 1235);
  assert.equal(S.fromUnit('90', 's'), 90000);
  assert.equal(S.fromUnit('250', 'ms'), 250);
  assert.equal(S.fromUnit('', 's'), null);
  assert.equal(S.fromUnit('abc', 'ms'), null);
  assert.equal(S.delayValid(600000), true);
  assert.equal(S.delayValid(600001), false);
  assert.equal(S.delayValid(-1), false);
  assert.equal(S.delayValid(1.5), false);
  // 切換單位時間不變：300 毫秒 → 0.3 秒 → 300 毫秒
  assert.equal(S.fromUnit(String(S.toUnit(300, 's')), 's'), 300);
});

test('search matches data, event name and id, case-insensitive', () => {
  const S = load();
  const event = { event: 'Done', data: '{"orderId":"A1029"}', id: 'evt-7', delayMs: 0, type: 'normal' };
  assert.equal(S.matches(event, 'a1029'), true);
  assert.equal(S.matches(event, 'done'), true);
  assert.equal(S.matches(event, 'EVT-7'), true);
  assert.equal(S.matches(event, 'missing'), false);
  assert.equal(S.matches(event, '  '), true);
});

test('bulk paste: JSON array, object data and validation errors with the event number', () => {
  const S = load();
  const ok = S.parseBulk('[{"data":"one","delayMs":100,"event":"message"},{"data":{"a":1},"type":"error","id":7}]');
  assert.deepEqual(plain(ok.events), [
    { event: 'message', data: 'one', id: '', delayMs: 100, type: 'normal' },
    { event: '', data: '{"a":1}', id: '7', delayMs: 0, type: 'error' }]);
  assert.equal(S.parseBulk('[{"data":"a","delayMs":600001}]').error.key, 'sseEditor.bulkBadDelay');
  assert.equal(S.parseBulk('[{"data":"a","delayMs":600001}]').error.params.index, 1);
  assert.equal(S.parseBulk('[{"data":"a"},{"data":"b","type":"boom"}]').error.params.index, 2);
  assert.equal(S.parseBulk('[1]').error.key, 'sseEditor.bulkItemNotObject');
  assert.equal(S.parseBulk('[]').error.key, 'sseEditor.bulkEmpty');
  assert.equal(S.parseBulk('[{"data":').error.key, 'sseEditor.bulkJsonError');
  assert.equal(S.parseBulk('   ').error.key, 'sseEditor.bulkEmpty');
  // 與伺服器相同：每個事件都要有資料，名稱與 ID 不可換行
  assert.deepEqual(plain(S.parseBulk('[{"data":"a"},{"data":""}]').error), { key: 'sseEditor.bulkNoData', params: { index: 2 } });
  assert.equal(S.parseBulk('[{"data":"a","event":"x\\ny"}]').error.key, 'sseEditor.bulkMultiline');
});

test('bulk paste: text/event-stream copied from a real stream', () => {
  const S = load();
  const wire = 'event: message\ndata: {"delta":"你好"}\nid: 1\n\ndata: line one\ndata: line two\n\n: comment\nevent: done\ndata: [DONE]';
  const parsed = S.parseBulk(wire);
  assert.deepEqual(plain(parsed.events), [
    { event: 'message', data: '{"delta":"你好"}', id: '1', delayMs: 0, type: 'normal' },
    { event: '', data: 'line one\nline two', id: '', delayMs: 0, type: 'normal' },
    { event: 'done', data: '[DONE]', id: '', delayMs: 0, type: 'normal' }]);
  assert.equal(S.parseBulk('\r\nevent: x\r\ndata: y\r\n\r\n').events[0].data, 'y');
  assert.equal(S.parseBulk('just some text').error.key, 'sseEditor.bulkUnreadable');
  assert.equal(S.parseBulk('data:\n\n').error.key, 'sseEditor.bulkNoData');
  // 與伺服器相同：ID 不可含 NUL；開頭的 BOM（從檔案複製時常見）不影響解析
  assert.equal(S.parseBulk('[{"data":"a","id":"x\\u0000"}]').error.key, 'sseEditor.bulkBadId');
  assert.equal(S.parseBulk('\uFEFF[{"data":"a"}]').events.length, 1);
  assert.equal(S.parseBulk('\uFEFFdata: a\n\n').events[0].data, 'a');
});

test('wiring: load order, registration, rule editor usage and every text key in both languages', () => {
  const read = file => fs.readFileSync(path.join(root, file), 'utf8');
  const index = read('index.html');
  const at = name => index.indexOf(name + '?v=');
  assert.ok(at('/utils.js') > 0 && at('/utils.js') < at('/sse-events.js'));
  assert.ok(at('/sse-events.js') < at('/components/SseEventsEditor.js'));
  assert.ok(at('/components/SseEventsEditor.js') < at('/components/RuleEditModal.js'));
  assert.ok(read('app.js').includes("_app.component('sse-events-editor', SseEventsEditor);"));
  const modal = read('components/RuleEditModal.js');
  assert.ok(modal.includes('<sse-events-editor v-if="form.sseEnabled && form.protocol===\'HTTP\'" class="sse-editor-wrap rule-sse-editor"'));
  assert.ok(!modal.includes('rule-sse-table'), 'the old event table is gone');
  const sources = read('components/SseEventsEditor.js') + read('sse-events.js');
  const keys = [...new Set([...sources.matchAll(/'((?:sseEditor|modal)\.[A-Za-z]+)'/g)].map(m => m[1]))];
  assert.ok(keys.length > 30);
  for (const lang of ['zh-TW', 'en']) {
    const messages = JSON.parse(read(`i18n/${lang}.json`));
    for (const key of keys) {
      const [group, name] = key.split('.');
      assert.equal(typeof messages[group]?.[name], 'string', `${lang}: ${key}`);
    }
  }
});

test('raw stream text: delays, multi-line data, id, error end with skipped count, loop note', () => {
  const S = load();
  const t = (key, p = {}) => ({ 'ssePreview.delay': `-- ${p.ms} --`, 'ssePreview.abort': '[ABORT]',
    'ssePreview.errorEvent': '[ERROR]', 'ssePreview.remainingSkipped': `${p.count} skipped`, 'ssePreview.loopPlay': 'LOOP' })[key];
  assert.equal(S.rawStream([], false, t), '');
  assert.equal(S.rawStream([{ event: '', data: 'a\nb', id: '7', delayMs: 300, type: 'normal' }], true, t),
    '-- 300 --\n\nevent: message\ndata: a\ndata: b\nid: 7\n\nLOOP');
  const ended = S.rawStream([{ event: 'x', data: 'one', id: '', delayMs: 0, type: 'error' },
    { event: '', data: 'two', id: '', delayMs: 0, type: 'normal' }], true, t);
  assert.equal(ended, '[ERROR]\nevent: error\ndata: one\n\n1 skipped');
  assert.equal(S.rawStream([{ event: '', data: 'x', id: '', delayMs: 5, type: 'abort' }], false, t), '-- 5 --\n\n[ABORT]');
});

test('summary: count, total, loop, and where the connection ends', () => {
  const S = load();
  assert.deepEqual(plain(S.summary([ev(300), ev(800)], true)),
    { count: 2, totalMs: 1100, endAt: 0, end: null, skipped: 0, loops: true });
  assert.equal(S.summary([ev(300)], false).loops, false);
  // 錯誤／中止事件結束連線：開了循環也不會重播
  assert.deepEqual(plain(S.summary([ev(100), ev(200, 'error'), ev(500)], true)),
    { count: 3, totalMs: 300, endAt: 2, end: 'error', skipped: 1, loops: false });
  assert.deepEqual(plain(S.summary([ev(100), ev(900, 'abort')], false)),
    { count: 2, totalMs: 1000, endAt: 2, end: 'abort', skipped: 0, loops: false });
  assert.equal(S.summary(null, true).count, 0);
});

test('rule detail: SSE summary line in both languages, built from the loaded rule', () => {
  const read = file => fs.readFileSync(path.join(root, file), 'utf8');
  const S = load();
  const context = { SseEvents: S, heldDetailMixin: () => ({}), DETAIL_LIST_PREVIEW: {}, shortId() {}, fmtTime() {},
    forwardTargetLabel() {}, forwardTargetEndpoint() {} };
  vm.runInNewContext(read('components/RuleDetail.js') + '\nthis.RuleDetail = RuleDetail;', context);
  const computed = context.RuleDetail.computed;
  for (const lang of ['zh-TW', 'en']) {
    const messages = JSON.parse(read(`i18n/${lang}.json`));
    const t = (key, p = {}) => key.split('.').reduce((o, k) => o?.[k], messages).replace(/\{(\w+)\}/g, (_, k) => p[k]);
    const line = (events, loop, extra = {}) => computed.sse.call({ t, isHttp: true, view: { sseEnabled: true, ...extra },
      shownDetail: { _sseSummary: S.summary(events, loop) } });
    const looping = line([ev(24000), ev(300)], true);
    const ended = line([ev(100), ev(200, 'error'), ev(5), ev(5)], true);
    const aborted = line([ev(100), ev(900, 'abort')], false);
    if (lang === 'zh-TW') {
      assert.deepEqual(plain(looping), { count: 2, text: '2 個事件 · 全長 24.3 秒 · 循環播放', end: '' });
      assert.deepEqual(plain(ended), { count: 4, text: '4 個事件 · 全長 300 ms', end: '第 2 個事件後結束連線（後面 2 個不會送出）' });
      assert.equal(aborted.end, '第 2 個事件中止連線');
      assert.equal(line([ev(0)], false).text, '1 個事件 · 全長 0 ms · 播放一次');
    } else {
      assert.equal(looping.text, '2 events · 24.3 s total · Loops');
      assert.equal(ended.end, 'Connection closes after event 2 (2 later events not sent)');
      assert.equal(aborted.end, 'Connection aborts at event 2');
    }
    // 不是 SSE、JMS，或內容讀不到時不顯示
    assert.equal(line([ev(0)], false, { sseEnabled: false }), null);
    assert.equal(computed.sse.call({ t, isHttp: false, view: { sseEnabled: true }, shownDetail: { _sseSummary: S.summary([ev(0)]) } }), null);
    assert.equal(computed.sse.call({ t, isHttp: true, view: { sseEnabled: true }, shownDetail: {} }), null);
  }
  const detail = read('components/RuleDetail.js');
  assert.ok(detail.includes('<template v-if="sse">'));
  const rules = read('composables/useRules.js');
  assert.ok(rules.includes('SseEvents.rawStream(events, data.sseLoopEnabled === true, t)'));
  assert.ok(rules.includes('data._sseSummary = SseEvents.summary(events, data.sseLoopEnabled === true)'));
});

test('responses page: SSE responses use the same event editor, without loop or its own expand button', () => {
  const read = file => fs.readFileSync(path.join(root, file), 'utf8');
  const modal = read('components/ResponseEditModal.js');
  assert.ok(modal.includes('<sse-events-editor ref="sseEditor" class="sse-editor-wrap response-sse-editor" :events="sseEvents" :loopable="false" :expandable="false"'));
  assert.ok(!modal.includes('sse-table') && !modal.includes('previewEntries'), 'the old table and side preview are gone');
  const editor = read('components/SseEventsEditor.js');
  assert.ok(editor.includes('<div v-if="loopable" class="form-check form-switch rule-sse-loop">'));
  assert.ok(editor.includes('<ui-button v-if="expandable" type="button"'));
  // 規則編輯器維持原樣：預設有循環與放大
  assert.ok(!read('components/RuleEditModal.js').includes(':loopable='));
});

test('responses page: saving SSE with an unfinished event opens that event instead of saving', () => {
  const read = file => fs.readFileSync(path.join(root, file), 'utf8');
  const S = load();
  const context = { SseEvents: S, SAVE_SHORTCUT_KEY: 'Ctrl', fmtSize() {}, document: {} };
  vm.runInNewContext(read('components/ResponseEditModal.js') + '\nthis.ResponseEditModal = ResponseEditModal;', context);
  const { computed, methods } = context.ResponseEditModal;
  const make = (events, contentType = 'sse') => {
    const emitted = [];
    const revealed = [];
    const self = { form: { contentType }, sseEvents: events, attemptedSave: false, t: (key, p) => `${key}:${JSON.stringify(p || {})}`,
      $emit: (...args) => emitted.push(args), $refs: { sseEditor: { reveal: index => revealed.push(index) } } };
    Object.defineProperty(self, 'firstInvalidSse', { get: () => computed.firstInvalidSse.call(self) });
    Object.defineProperty(self, 'sseError', { get: () => computed.sseError.call(self) });
    return { self, emitted, revealed };
  };
  const good = { event: '', data: 'a', id: '', delayMs: 0, type: 'normal' };
  const ok = make([good, { ...good, delayMs: 600000 }]);
  methods.requestSave.call(ok.self);
  assert.deepEqual(ok.emitted, [['save']]);
  assert.equal(ok.self.sseError, '');
  const noData = make([good, { ...good, data: '' }, { ...good, delayMs: NaN }]);
  assert.equal(noData.self.sseError, '', 'no message before the first save attempt');
  methods.requestSave.call(noData.self);
  assert.deepEqual(noData.emitted, []);
  assert.deepEqual(noData.revealed, [1]);
  assert.equal(noData.self.sseError, 'sseEditor.fixBeforeSave:{"index":2}');
  const badDelay = make([{ ...good, delayMs: 1.5 }]);
  methods.requestSave.call(badDelay.self);
  assert.deepEqual(badDelay.revealed, [0]);
  // 一般文字回應不受 SSE 事件影響
  const text = make([{ ...good, data: '' }], 'text');
  methods.requestSave.call(text.self);
  assert.deepEqual(text.emitted, [['save']]);
  // 新增與刪除交給上層（新增的是空白事件）
  const edits = make([good]);
  methods.addSseEvent.call(edits.self);
  methods.removeSseEvent.call(edits.self, 0);
  assert.deepEqual(plain(edits.emitted), plain([['update:sseEvents', [good, S.blank()]], ['update:sseEvents', []]]));
});

test('event editor: reveal clears the search, leaves the zoomed view and opens the event', () => {
  const read = file => fs.readFileSync(path.join(root, file), 'utf8');
  const S = load();
  const context = { SseEvents: S, Vue: { toRaw: value => value }, serializeSseEvents: JSON.stringify };
  vm.runInNewContext(read('components/SseEventsEditor.js') + '\nthis.SseEventsEditor = SseEventsEditor;', context);
  const { methods } = context.SseEventsEditor;
  const events = [ev(0), ev(2000, 'normal', '')];
  const focused = [];
  const self = { events, query: 'abc', focus: { kind: 'bulk' }, openRaw: null, units: new WeakMap(), $el: null,
    closeFocus() { this.focus = null; }, unitOf: methods.unitOf, $nextTick: fn => fn.call(self), focusData: index => focused.push(index) };
  methods.reveal.call(self, 1);
  assert.equal(self.query, '');
  assert.equal(self.focus, null);
  assert.equal(self.openRaw, events[1]);
  assert.equal(self.units.get(events[1]), 's', 'keeps the unit the delay is shown in');
  assert.deepEqual(focused, [1]);
  methods.reveal.call(self, 9);
  assert.equal(self.openRaw, events[1], 'an index past the end changes nothing');
});
