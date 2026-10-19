const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/static');

function statsFixture(fetch) {
  const storage = new Map();
  const context = { window: {}, document: {}, console, setTimeout, clearTimeout, fetch, AbortController, URLSearchParams,
    localStorage: { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) },
    Vue: { ref: value => ({ value }), reactive: value => value, computed: fn => ({ get value() { return fn(); } }), watch: () => {} } };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'utils.js'), 'utf8') + '\n'
    + fs.readFileSync(path.join(root, 'composables/useStats.js'), 'utf8') + '\nthis.create = useStats;', context);
  return context.create({ showToast: () => {}, t: key => key, httpLabel: { value: 'HTTP' }, jmsLabel: { value: 'JMS' },
    loading: { value: {} } });
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

  // The loaded details are fetched from the cache and no more requests are made.
  const again = { log: { id: 42, hasRequestBody: false, hasResponseBody: false, hasMatchChain: false } };
  await stats.loadLogDetail(again);
  assert.equal(urls.length, 1);
  assert.equal(again._detail.candidateCount, 3);
});

test('"show these requests" filters by exact method and path, shown as one removable tag', async () => {
  const urls = [];
  const stats = statsFixture(async url => {
    urls.push(url);
    return { ok: true, status: 200, json: async () => ({ results: [], totalElements: 0, totalPages: 0 }) };
  });
  stats.logFilter.value = { ...stats.logFilter.value, protocol: 'HTTP', matched: 'false', method: 'GET', path: '/refund' };

  await stats.loadLogs(true);

  const list = urls.find(url => url.startsWith('/api/admin/logs?'));
  const params = new URL(list, 'http://x').searchParams;
  assert.equal(params.get('method'), 'GET');
  assert.equal(params.get('path'), '/refund');
  assert.equal(params.get('endpoint'), null);
  const tag = stats.logFilterChips.value.find(chip => chip.key === 'path');
  assert.equal(tag.label, 'filterChips.endpointGET /refund');

  stats.removeLogChip('path');
  assert.equal(stats.logFilter.value.method, '');
  assert.equal(stats.logFilter.value.path, '');
  assert.equal(stats.logFilter.value.matched, 'false');
});
