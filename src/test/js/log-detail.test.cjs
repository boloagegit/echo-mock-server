const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/static');

function statsFixture(fetch) {
  const storage = new Map();
  const context = { window: {}, document: {}, console, setTimeout, clearTimeout, fetch,
    localStorage: { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) },
    Vue: { ref: value => ({ value }), reactive: value => value, computed: fn => ({ get value() { return fn(); } }), watch: () => {} } };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'utils.js'), 'utf8') + '\n'
    + fs.readFileSync(path.join(root, 'composables/useStats.js'), 'utf8') + '\nthis.create = useStats;', context);
  return context.create({ showToast: () => {}, t: key => key });
}

test('the flow tab gets its details even when a log has no bodies or match chain', async () => {
  const urls = [];
  const stats = statsFixture(async url => {
    urls.push(url);
    return { ok: true, status: 200, json: async () => ({ candidateCount: 3, matchedByFallback: false, scenarioSkipped: '[]' }) };
  });
  const item = { log: { id: 42, hasRequestBody: false, hasResponseBody: false, hasMatchChain: false } };

  await stats.loadLogDetail(item);

  assert.deepEqual(urls, ['/api/admin/logs/42/detail']);
  assert.equal(item._detail.candidateCount, 3);
  assert.equal(item._detailLoading, false);

  // 已載入的明細從快取取用，不再發請求
  const again = { log: { id: 42, hasRequestBody: false, hasResponseBody: false, hasMatchChain: false } };
  await stats.loadLogDetail(again);
  assert.equal(urls.length, 1);
  assert.equal(again._detail.candidateCount, 3);
});
