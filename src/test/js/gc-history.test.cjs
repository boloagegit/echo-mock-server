const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function fixture(fetch) {
  const timers = new Map();
  const sandbox = { fetch, AbortController, Date, Blob,
    setTimeout: fn => { const id = timers.size + 1; timers.set(id, fn); return id; }, clearTimeout: id => timers.delete(id) };
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/components/GcHistoryPanel.js'), 'utf8') + '\nthis.panel = GcHistoryPanel;', sandbox);
  const panel = sandbox.panel;
  const instance = { ...panel.data(), t: key => key };
  for (const [key, method] of Object.entries(panel.methods)) instance[key] = method.bind(instance);
  for (const [key, method] of Object.entries(panel.computed)) Object.defineProperty(instance, key, { get: method.bind(instance) });
  return { instance, panel, timers };
}

test('one bounded read per manual refresh, no duplicate in-flight calls or lingering timers', async () => {
  let finish, count = 0;
  const state = fixture((url, options) => { count++; assert.equal(url, '/api/admin/resources/gc?limit=512'); assert.equal(options.cache, 'no-store');
    return new Promise(resolve => { finish = resolve; }); });
  const pending = state.instance.load();
  await state.instance.load();
  assert.equal(count, 1);
  finish({ ok: true, status: 200, json: async () => ({ events: [], collectors: [] }) });
  await pending;
  assert.equal(state.timers.size, 0);
});

test('permission loss clears GC history; temporary failures preserve data with a stale indication', async () => {
  for (const status of [401, 403, 500]) {
    const { instance } = fixture(async () => ({ ok: false, status }));
    instance.history = { events: [{ sequence: 1 }] };
    await instance.load();
    assert.equal(instance.history === null, status !== 500);
    assert.equal(instance.failed, true);
  }
});

test('unmount cancels the request and cannot restore a late GC history result', async () => {
  let finish, signal;
  const { instance, panel, timers } = fixture((url, options) => { signal = options.signal; return new Promise(resolve => { finish = resolve; }); });
  const pending = instance.load();
  panel.beforeUnmount.call(instance);
  assert.equal(signal.aborted, true);
  finish({ ok: true, json: async () => ({ collectors: [], events: [] }) });
  await pending;
  assert.equal(instance.history, null);
  assert.equal(timers.size, 0);
});

test('chart uses timestamps, filters collectors, excludes unknown heap values and pages newest events', () => {
  const { instance } = fixture(async () => {});
  instance.history = { events: Array.from({ length: 60 }, (_, i) => ({ sequence: i + 1, collector: i % 2 ? 'old' : 'young',
    heapCoverageComplete: i !== 0, heapAfterBytes: i * 100, heapMaxBytes: 10000, endedAt: new Date(i * 1000).toISOString() })) };
  assert.equal(instance.rows.length, 25);
  assert.equal(instance.rows[0].sequence, 60);
  assert.equal(instance.pageCount, 3);
  assert.equal(instance.chart.points.length, 59);
  assert.equal(instance.chart.points[0].x, 10);
  assert.equal(instance.chart.points.at(-1).x, 790);
  instance.collector = 'old';
  assert.equal(instance.events.length, 30);
  assert.equal(instance.chart.points.length, 30);
  assert.equal(instance.bytes(null), 'monitoring.unavailable');
  assert.equal(instance.bytes(-(1024 ** 2)), '-1 MiB');
});

test('GC labels exist in both languages and do not promise leak freedom', () => {
  const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/components/GcHistoryPanel.js'), 'utf8');
  for (const locale of ['en', 'zh-TW']) {
    const strings = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/i18n/' + locale + '.json'), 'utf8'));
    for (const match of source.matchAll(/t\('monitoring\.gc\.([^']+)'/g)) assert.equal(typeof strings.monitoring.gc[match[1]], 'string', match[1]);
  }
  assert.doesNotMatch(source, /setInterval|localStorage|method:\s*['"]POST/);
});
