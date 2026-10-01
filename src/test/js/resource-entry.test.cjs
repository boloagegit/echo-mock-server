const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const staticRoot = path.resolve(__dirname, '../../main/resources/static');

// Execute the real auth helper and real app mount callback together. The old
// DB-backed status request never resolves; diagnostics must still mount once.
async function enterSettings(statusCode) {
  let accessReads = 0, resourceReads = 0, statusReads = 0;
  const timers = new Set();
  const context = {
    Vue: { ref: value => ({ value }) },
    AbortController,
    fetch: async url => {
      assert.equal(url, '/api/admin/resources/access');
      accessReads++;
      return { ok: statusCode === 204, status: statusCode };
    },
    setTimeout: callback => { timers.add(callback); return callback; },
    clearTimeout: callback => timers.delete(callback),
    window: { location: { hash: '#/settings' }, addEventListener() {} },
    sessionStorage: { getItem: () => 'already-seen' },
    themeCtx: { applyTheme() {} }, applyDensity() {},
    locale: { value: 'zh-TW' }, loadLocale: async () => {},
    loadStatus: () => { statusReads++; return new Promise(() => {}); },
    checkForceChangePassword() { throw new Error('status should still be pending'); },
    showDblClickHint: { value: false },
    closeResponseDropdown() {}, closeDataDropdown() {}, closeResponseDataDropdown() {}, handleKeydown() {},
    handleBeforeUnload() {},
  };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(staticRoot, 'composables/useAuth.js'), 'utf8') +
    '\nthis.auth = useAuth(() => {}, key => key);', context);
  Object.assign(context, context.auth);
  context.applyUrlParams = () => {
    if (context.isAdmin.value) resourceReads++;
  };
  const app = fs.readFileSync(path.join(staticRoot, 'app.js'), 'utf8');
  const callback = app.split('onMounted(async () => {')[1].split('onUnmounted(() => {')[0];
  assert.ok(callback, 'real mount callback must be present');
  await vm.runInContext('(async () => {' + callback.replace(/\}\);\s*$/, '') + '})()', context);
  return { accessReads, resourceReads, statusReads, timers, admin: context.isAdmin.value };
}

test('cold admin Settings entry is not gated by a never-ending status query', async () => {
  const result = await enterSettings(204);
  assert.equal(result.accessReads, 1);
  assert.equal(result.resourceReads, 1);
  assert.equal(result.statusReads, 1);
  assert.equal(result.admin, true);
  assert.equal(result.timers.size, 0);
});

test('cold Settings entry cannot bypass ADMIN authorization', async () => {
  for (const status of [401, 403]) {
    const result = await enterSettings(status);
    assert.equal(result.admin, false);
    assert.equal(result.resourceReads, 0);
    assert.equal(result.timers.size, 0);
  }
});
