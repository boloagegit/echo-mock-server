const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const staticRoot = path.resolve(__dirname, '../../main/resources/static');
const source = file => fs.readFileSync(path.join(staticRoot, file), 'utf8');

// Runs heldDetailMixin against a plain object standing in for the component.
function drawerHarness() {
  const timers = [];
  const context = vm.createContext({
    Vue: { createApp() {}, ref: value => ({ value }), computed() {}, watch() {}, onMounted() {}, onUnmounted() {} },
    setTimeout: callback => { timers.push(callback); return callback; },
    clearTimeout: callback => { const i = timers.indexOf(callback); if (i >= 0) timers.splice(i, 1); },
  });
  vm.runInContext(source('utils.js') + '\nthis.heldDetailMixin = heldDetailMixin;', context);
  const vmState = { record: null, open: false, ready: false, ...context.heldDetailMixin(() => {}).data() };
  const mixin = context.heldDetailMixin(function () {
    return { open: this.open, key: this.record?.id, ready: this.ready, value: this.record };
  });
  let previous;
  const view = () => {
    const heldSource = mixin.computed.heldSource.call(vmState);
    return mixin.computed.held.call({ ...vmState, heldSource });
  };
  const set = patch => {
    Object.assign(vmState, patch);
    const current = mixin.computed.heldSource.call(vmState);
    mixin.watch.heldSource.handler.call(vmState, current, previous);
    previous = current;
    return view();
  };
  const fireTimers = () => timers.splice(0).forEach(callback => callback());
  return { set, fireTimers, timers };
}

test('stepping to a record that is still loading keeps the current record on screen', () => {
  const h = drawerHarness();
  let held = h.set({ open: true, record: { id: 'a' }, ready: true });
  assert.equal(held.value.id, 'a');
  assert.equal(held.open, true);

  held = h.set({ record: { id: 'b' }, ready: false });
  assert.equal(held.value.id, 'a', 'no blank frame between records');
  assert.equal(held.stale, true);
  assert.equal(held.waiting, false);
  assert.equal(held.open, true);

  held = h.set({ ready: true });
  assert.equal(held.value.id, 'b', 'swaps in one step once ready');
  assert.equal(held.stale, false);
});

test('opening from closed waits for data, then falls back to the loading state', () => {
  const h = drawerHarness();
  let held = h.set({ open: true, record: { id: 'a' }, ready: false });
  assert.equal(held.open, false, 'the drawer does not slide in empty');
  assert.equal(h.timers.length, 1);

  h.fireTimers();
  held = h.set({});
  assert.equal(held.open, true);
  assert.equal(held.waiting, true, 'a slow load shows the loading state');
});

test('a failed load counts as ready so the error shows for the new record', () => {
  const h = drawerHarness();
  h.set({ open: true, record: { id: 'a' }, ready: true });
  const held = h.set({ record: { id: 'b' }, ready: true });
  assert.equal(held.value.id, 'b');
  assert.equal(held.stale, false);
});

test('closing forgets the held record so the next open never shows a stale one', () => {
  const h = drawerHarness();
  h.set({ open: true, record: { id: 'a' }, ready: true });
  h.set({ open: false, record: null, ready: false });
  const held = h.set({ open: true, record: { id: 'c' }, ready: false });
  assert.equal(held.open, false);
  assert.notEqual(held.value?.id, 'a');
});

function routerHarness() {
  const watchers = [];
  const loads = {};
  const loader = name => () => new Promise(resolve => { loads[name] = resolve; });
  const deps = {
    page: { value: 'rules' }, shownPage: { value: 'rules' }, isAdmin: { value: true },
    issueReportingEnabled: { value: false },
    ruleFilter: { value: {} }, responseFilter: { value: '' }, logFilter: { value: {} }, auditFilter: { value: {} },
    loadRules: loader('rules'), loadResponseSummary: loader('responses'), loadLogs: loader('stats'),
    loadAudit: loader('audit'), loadAccounts: loader('accounts'), loadIssues() {}, loadBackupStatus() {}, loadStatus() {},
  };
  const window = { location: { hash: '#/rules' }, history: { replaceState() {}, pushState() {} } };
  const context = vm.createContext({
    Vue: { ref: value => ({ value }), watch: (ref, callback) => watchers.push([ref, callback]) },
    deps, window, URLSearchParams, setTimeout, clearTimeout, Promise,
  });
  vm.runInContext(source('composables/useRouter.js') + '\nthis.router = useRouter(deps);', context);
  context.router.setupRouterWatchers();
  const go = async target => {
    const old = deps.page.value;
    deps.page.value = target;
    for (const [ref, callback] of watchers) if (ref === deps.page) await callback(target, old);
  };
  return { deps, loads, go };
}

const tick = ms => new Promise(resolve => setTimeout(resolve, ms));

test('a page switch keeps the current page until the next page has its rows', async () => {
  const h = routerHarness();
  await h.go('responses');
  assert.equal(h.deps.shownPage.value, 'rules', 'old page stays while responses load');
  h.loads.responses();
  await tick(0);
  assert.equal(h.deps.shownPage.value, 'responses');

  // A page visited before already has rows, so it swaps at once and refreshes in place.
  await h.go('rules');
  await h.go('responses');
  assert.equal(h.deps.shownPage.value, 'responses');
});

test('a slow page still appears after the hold period', async () => {
  const h = routerHarness();
  await h.go('audit');
  assert.equal(h.deps.shownPage.value, 'rules');
  await tick(320);
  assert.equal(h.deps.shownPage.value, 'audit');
});

test('a newer switch wins over a slower earlier one', async () => {
  const h = routerHarness();
  await h.go('stats');
  await h.go('accounts');
  h.loads.stats();
  await tick(0);
  assert.equal(h.deps.shownPage.value, 'rules', 'the abandoned stats load does not swap in');
  h.loads.accounts();
  await tick(0);
  assert.equal(h.deps.shownPage.value, 'accounts');
});
