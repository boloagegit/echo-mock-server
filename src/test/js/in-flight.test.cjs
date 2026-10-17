const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/static');

const vueStub = { ref: value => ({ value }), reactive: value => value, computed: fn => ({ get value() { return fn(); } }), watch: () => {} };

function load(files, extra) {
  const storage = new Map();
  const context = { window: {}, document: {}, console, setTimeout, clearTimeout, URLSearchParams, AbortController,
    localStorage: { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) },
    navigator: { platform: 'Linux' }, Vue: vueStub, ...extra };
  vm.runInNewContext(files.map(f => fs.readFileSync(path.join(root, f), 'utf8')).join('\n')
    + '\nthis.createInFlight = createInFlight; this.useRules = typeof useRules === "function" ? useRules : null;', context);
  return context;
}

test('a repeated action for the same item is ignored until the first one finishes', async () => {
  const { createInFlight } = load(['utils.js'], {});
  const inFlight = createInFlight();
  let release;
  let runs = 0;
  const first = inFlight.run('toggle:a', () => { runs++; return new Promise(r => { release = r; }); });
  assert.equal(inFlight.isPending('toggle:a'), true);
  assert.equal(await inFlight.run('toggle:a', async () => { runs++; }), undefined);
  await inFlight.run('toggle:b', async () => { runs++; });
  assert.equal(runs, 2, 'another item is not blocked');
  release('done');
  assert.equal(await first, 'done');
  assert.equal(inFlight.isPending('toggle:a'), false);
  await inFlight.run('toggle:a', async () => { runs++; });
  assert.equal(runs, 3, 'after it finishes the action can run again');
});

test('a failed action releases the item so it can be retried', async () => {
  const { createInFlight } = load(['utils.js'], {});
  const inFlight = createInFlight();
  await assert.rejects(inFlight.run('x', async () => { throw new Error('boom'); }));
  assert.equal(inFlight.isPending('x'), false);
});

test('double-clicking a rule toggle sends one request and flips the rule once', async () => {
  const calls = [];
  let release;
  const toasts = [];
  const ctx = load(['utils.js', 'composables/useRules.js'], {
    fetch: async (url, opt) => {
      calls.push([url, opt && opt.method]);
      await new Promise(r => { release = r; });
      return { ok: true, status: 200, json: async () => ({}), clone() { return this; } };
    }
  });
  const rules = ctx.useRules({ showToast: (...a) => toasts.push(a), showConfirm: async () => true, t: k => k,
    requireLogin: async () => true, login() {}, isLoggedIn: { value: true }, loading: { value: {} },
    page: { value: 'rules' }, httpLabel: { value: 'HTTP' }, jmsLabel: { value: 'JMS' } });
  const rule = { id: 'r1', enabled: true, matchKey: '/a' };
  const first = rules.toggleEnabled(rule);
  const second = rules.toggleEnabled(rule);
  await new Promise(r => setTimeout(r, 0));
  release();
  await Promise.all([first, second]);
  assert.deepEqual(calls.map(c => c[0]), ['/api/admin/rules/r1/disable']);
  assert.equal(rule.enabled, false);
  assert.equal(toasts.length, 1);
});
