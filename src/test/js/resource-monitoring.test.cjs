const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function fixture(fetcher) {
  const timers = new Map();
  let timerSequence = 0;
  const sandbox = {
    fetch: fetcher, AbortController, Date, Blob,
    setTimeout: (callback) => { const id = ++timerSequence; timers.set(id, callback); return id; },
    clearTimeout: id => timers.delete(id),
  };
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/components/ResourceMonitoringPanel.js'), 'utf8') + '\nthis.panel = ResourceMonitoringPanel;', sandbox);
  const panel = sandbox.panel;
  const events = [];
  const instance = { ...panel.data(), t: key => key, $emit: (...args) => events.push(args) };
  for (const [key, method] of Object.entries(panel.methods)) instance[key] = method.bind(instance);
  for (const [key, getter] of Object.entries(panel.computed)) Object.defineProperty(instance, key, { get: getter.bind(instance) });
  return { instance, panel, timers, events };
}

test('initial and manual refresh only; completed request leaves no timer', async () => {
  let calls = 0;
  const state = fixture(async () => { calls++; return { ok: true, status: 200, json: async () => ({ collectedAt: '2026-09-30T00:00:00Z' }) }; });
  await state.instance.loadSnapshot();
  assert.equal(calls, 1);
  assert.equal(state.timers.size, 0);
  await state.instance.loadSnapshot();
  assert.equal(calls, 2);
  assert.equal(state.timers.size, 0);
});

test('overlapping refresh does not duplicate requests', async () => {
  let finish;
  let calls = 0;
  const state = fixture(() => { calls++; return new Promise(resolve => { finish = resolve; }); });
  const pending = state.instance.loadSnapshot();
  await state.instance.loadSnapshot();
  assert.equal(calls, 1);
  finish({ ok: true, status: 200, json: async () => ({ enabled: true }) });
  await pending;
  assert.equal(state.instance.busy, false);
});

test('failed refresh retains original sample time and marks stale', async () => {
  const state = fixture(async () => ({ ok: false, status: 500 }));
  state.instance.snapshot = { collectedAt: 'old-time' };
  await state.instance.loadSnapshot();
  assert.equal(state.instance.snapshot.collectedAt, 'old-time');
  assert.equal(state.instance.failed, true);
});

test('permission loss clears sensitive snapshot', async () => {
  for (const status of [401, 403]) {
    const state = fixture(async () => ({ ok: false, status }));
    state.instance.snapshot = { sections: { jvm: { values: { heapUsedBytes: 123 } } } };
    await state.instance.loadSnapshot();
    assert.equal(state.instance.snapshot, null);
    assert.equal(state.instance.failed, true);
  }
});

test('unmount aborts collection and prevents a late result from restoring data', async () => {
  let finish;
  let signal;
  // Supply a deferred fetch that also records the AbortSignal.
  const other = fixture((url, options) => { signal = options.signal; return new Promise(resolve => { finish = resolve; }); });
  const pending = other.instance.loadSnapshot();
  other.panel.beforeUnmount.call(other.instance);
  assert.equal(signal.aborted, true);
  finish({ ok: true, status: 200, json: async () => ({ enabled: true }) });
  await pending;
  assert.equal(other.instance.snapshot, null);
  assert.equal(other.timers.size, 0);
});

test('zero, unavailable and no-sample dates have different presentations', () => {
  const { instance } = fixture(async () => ({ ok: false }));
  assert.equal(instance.formatMetric('heapUsedBytes', 0), '0 B');
  assert.equal(instance.formatMetric('byteLimit', 64 * 1024 * 1024), '64 MiB');
  assert.equal(instance.formatMetric('dataFreeBytes', 80 * 1024 ** 3), '80 GiB');
  assert.equal(instance.formatMetric('bufferedBytes', 2048), '2 KiB');
  assert.equal(instance.formatMetric('heapUsedBytes', -1), 'monitoring.unavailable');
  assert.equal(instance.formatMetric('poolActive', null), 'monitoring.unavailable');
  assert.equal(instance.formatMetric('lastListenerExit', null), 'monitoring.noSample');
  assert.equal(instance.formatMetric('maxDeliveryAttempts', -1), 'monitoring.unlimited');
});

test('request deadline aborts once without creating a polling loop', async () => {
  let signal;
  let calls = 0;
  const state = fixture((url, options) => {
    calls++;
    signal = options.signal;
    return new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(new Error('aborted'))));
  });
  const pending = state.instance.loadSnapshot();
  assert.equal(state.timers.size, 1);
  [...state.timers.values()][0]();
  await pending;
  assert.equal(signal.aborted, true);
  assert.equal(calls, 1);
  assert.equal(state.timers.size, 0);
  assert.equal(state.instance.busy, false);
  assert.equal(state.instance.failed, true);
});

test('compact overview has six groups; details retain all nine sections and every metric', () => {
  const { instance } = fixture(async () => { throw new Error('not requested'); });
  assert.equal(instance.summaries.length, 6);
  assert.equal(instance.detailGroups.length, 6);
  const sections = instance.detailGroups.flatMap(group => group.sections);
  assert.equal(sections.length, 9);
  assert.equal(new Set(sections.map(group => group.key)).size, 9);
  assert.equal(sections.reduce((count, group) => count + group.metrics.length, 0), 89);
  const displayed = instance.detailGroups.flatMap(group => group.columns.flatMap(column => column.sections.flatMap(section => section.metrics.map(metric => section.key + ':' + metric))));
  assert.equal(displayed.length, 89);
  assert.equal(new Set(displayed).size, 89);
  const applicationLog = sections.find(section => section.key === 'applicationLog');
  const diagnosticMetrics = applicationLog.metrics.filter(key => key.startsWith('diagnostic'));
  assert.equal(diagnosticMetrics.length, 15);
  for (const locale of ['en', 'zh-TW']) {
    const messages = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/i18n/' + locale + '.json'), 'utf8'));
    for (const metric of diagnosticMetrics) assert.equal(typeof messages.monitoring.metrics[metric], 'string', metric);
  }
  for (const summary of instance.summaries) {
    const detail = sections.find(group => group.key === summary.key);
    assert.equal(summary.metrics.every(key => detail.metrics.includes(key)), true);
  }
});

test('opening details is local presentation only, starts closed and never refreshes', () => {
  let calls = 0;
  const { instance } = fixture(async () => { calls++; });
  assert.equal(Object.keys(instance.openDetails).length, 0);
  instance.toggleDetails('processing', { target: { open: true } });
  assert.equal(instance.openDetails.processing, true);
  instance.toggleDetails('processing', { target: { open: false } });
  assert.equal(instance.openDetails.processing, false);
  assert.equal(calls, 0);
});

test('disabled, failed and unsupported groups do not masquerade as zero values', () => {
  const { instance } = fixture(async () => {});
  assert.equal(Boolean(instance.hasValues(null)), false);
  for (const state of ['DISABLED', 'UNSUPPORTED', 'FAILED']) assert.equal(instance.hasValues({ state }), false);
  for (const state of ['AVAILABLE', 'PARTIAL']) assert.equal(instance.hasValues({ state }), true);
});

test('collapsed details expose incomplete hidden groups without health claims', () => {
  const { instance } = fixture(async () => {});
  instance.snapshot = { sections: { jvm: { state: 'AVAILABLE' }, caches: { state: 'FAILED' } } };
  const memory = instance.detailGroups.find(group => group.key === 'memory');
  assert.equal(memory.incomplete, 0);
  const caches = instance.detailGroups.find(group => group.key === 'cache');
  assert.equal(caches.incomplete, 1);
  assert.equal(caches.tone, 'danger');
  assert.equal(instance.detailGroups.find(group => group.key === 'jms').sections[0].key, 'jms');
});

test('current, cumulative and configuration metrics retain distinct meanings', () => {
  const { instance } = fixture(async () => {});
  const jms = instance.detailGroups.find(group => group.key === 'jms');
  assert.ok(jms.columns.find(c => c.key === 'current').sections[0].metrics.includes('forwardActive'));
  assert.ok(jms.columns.find(c => c.key === 'cumulative').sections[0].metrics.includes('cleanupFailures'));
  assert.ok(jms.columns.find(c => c.key === 'limits').sections[0].metrics.includes('maxDeliveryAttempts'));
});

test('heap percentage excludes invalid, missing and disabled observations', () => {
  const { instance } = fixture(async () => {});
  for (const state of ['DISABLED', 'FAILED']) {
    instance.snapshot = { sections: { jvm: { state, values: { heapUsedBytes: 400, heapMaxBytes: 500 } } } };
    assert.equal(instance.heapPercent, null);
  }
  instance.snapshot = { sections: { jvm: { state: 'AVAILABLE', values: { heapUsedBytes: 400, heapMaxBytes: 500 } } } };
  assert.equal(instance.heapPercent, 80);
  instance.snapshot.sections.jvm.values.heapMaxBytes = 0;
  assert.equal(instance.heapPercent, null);
});

test('summary navigation does not fetch and maps request-log details to database', () => {
  let calls = 0;
  const { instance, events } = fixture(async () => { calls++; });
  instance.navigateToDetail('requestLog');
  assert.deepEqual(events[0], ['navigate', 'monitoring', 'database']);
  instance.navigateToDetail('storage');
  assert.deepEqual(events[1], ['navigate', 'data', 'storage']);
  assert.equal(calls, 0);
});
