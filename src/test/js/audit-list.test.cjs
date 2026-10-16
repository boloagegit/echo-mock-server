const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/static');

const messages = {
  'audit.typeResponse': '回應', 'audit.typeRule': '規則', 'audit.factStatus': '狀態 {status}', 'audit.factDisabled': '已停用',
  'auditFields.priority': '優先度', 'auditFields.delayMs': '延遲(ms)', 'auditFields.tags': '標籤', 'auditFields.body': '回應內容',
  'auditFields.enabled': '啟用', 'auditFields.contentType': '內容類型', 'auditFields.action': '規則行為',
  'auditValues.yes': '是', 'auditValues.no': '否', 'auditValues.empty': '(空)', 'auditValues.modeForward': '轉發下游',
  'filterChips.type': '類型：'
};
const t = (key, vars = {}) => (messages[key] || key).replace(/\{(\w+)\}/g, (_, k) => String(vars[k]));

function auditFixture(fetch = async () => ({ ok: true, json: async () => ({ results: [], totalElements: 0, totalPages: 0 }) })) {
  const storage = new Map();
  const context = { window: {}, document: {}, console, setTimeout, clearTimeout, fetch, AbortController, URLSearchParams,
    localStorage: { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) },
    Vue: { ref: value => ({ value }), reactive: value => value, computed: fn => ({ get value() { return fn(); } }), watch: () => {} } };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'utils.js'), 'utf8') + '\n'
    + fs.readFileSync(path.join(root, 'composables/useAudit.js'), 'utf8') + '\nthis.create = useAudit;', context);
  return context.create({ showToast: () => {}, showConfirm: async () => true, t, requireLogin: async () => true,
    loading: { value: {} } });
}

test('a rule update row shows its path, method, host and the first three changes without loading snapshots', () => {
  const audit = auditFixture();
  const row = audit.describeAuditRow({
    id: 2, ruleId: 'e3ea5ed3-015a', action: 'UPDATE', targetProtocol: 'HTTP', targetName: '/users/1',
    targetDescription: '取得使用者',
    summary: { method: 'GET', host: 'api.example.com', changeCount: 5, changes: [
      { f: 'priority', b: 0, a: 5 },
      { f: 'tags', a: '{"env":"SIT"}' },
      { f: 'enabled', b: true, a: false },
      { f: 'delayMs', b: 0, a: 300 }
    ] }
  });

  assert.equal(row.name, '/users/1');
  assert.equal(row.named, true);
  assert.equal(row.protocolLabel, 'HTTP');
  assert.deepEqual(Array.from(row.meta), ['GET', 'api.example.com', '取得使用者']);
  assert.equal(row.changes.length, 3);
  assert.deepEqual({ ...row.changes[0] }, { label: '優先度', kind: 'value', before: '0', after: '5' });
  assert.equal(row.changes[1].kind, 'tags');
  assert.deepEqual(Array.from(row.changes[1].added), ['env=SIT']);
  assert.deepEqual(Array.from(row.changes[1].removed), []);
  assert.deepEqual({ ...row.changes[2] }, { label: '啟用', kind: 'value', before: '是', after: '否' });
  assert.equal(row.more, 2);
  assert.equal(row.facts.length, 0);
});

test('response rows show the response number and line counts; huge bodies just say changed', () => {
  const audit = auditFixture();
  const row = audit.describeAuditRow({
    id: 3, ruleId: 'response-12', action: 'UPDATE', targetProtocol: 'RESPONSE', targetName: '使用者回應',
    summary: { contentType: 'application/json', changeCount: 2, changes: [
      { f: 'body', long: true, add: 4, del: 2 }, { f: 'body', long: true }
    ] }
  });

  assert.equal(row.protocolLabel, '回應');
  assert.deepEqual(Array.from(row.meta), ['#12', 'application/json']);
  assert.deepEqual({ ...row.changes[0] }, { label: '回應內容', kind: 'lines', add: 4, del: 2 });
  assert.deepEqual({ ...row.changes[1] }, { label: '回應內容', kind: 'changed' });
  assert.equal(row.more, 0);
});

test('created and deleted rows list facts instead of changes, and unnamed items fall back to their id', () => {
  const audit = auditFixture();
  const deleted = audit.describeAuditRow({ id: 4, ruleId: '33610a12-c817', action: 'DELETE', targetProtocol: 'HTTP',
    summary: { status: 201, enabled: false } });
  assert.equal(deleted.name, '33610a12');
  assert.equal(deleted.named, false);
  assert.deepEqual(Array.from(deleted.facts), ['狀態 201', '已停用']);
  assert.equal(deleted.changes.length, 0);

  const legacy = audit.describeAuditRow({ id: 5, ruleId: 'response-7', action: 'CREATE' });
  assert.equal(legacy.name, '回應 #7');
  assert.equal(legacy.protocol, 'RESPONSE');
});

test('the type filter is sent to the server and shown as a removable chip', async () => {
  const urls = [];
  const audit = auditFixture(async url => { urls.push(url); return { ok: true, json: async () => ({ results: [], totalElements: 0, totalPages: 0 }) }; });
  audit.auditFilter.value = { ...audit.auditFilter.value, type: 'RESPONSE' };

  await audit.loadAudit(true);

  assert.match(urls[0], /[?&]type=RESPONSE(&|$)/);
  assert.deepEqual(Array.from(audit.auditFilterChips.value, c => ({ ...c })), [{ key: 'type', label: '類型：回應' }]);
  audit.clearAuditFilters();
  assert.equal(audit.auditFilter.value.type, '');
});

test('opening a record keeps the list summary even if the detail carries its own copy', async () => {
  const list = { results: [{ id: 9, ruleId: 'r-9', action: 'UPDATE', targetName: '/a',
    summary: { method: 'GET', changeCount: 1, changes: [{ f: 'priority', b: 0, a: 5 }] } }], totalElements: 1, totalPages: 1 };
  const audit = auditFixture(async url => ({ ok: true,
    json: async () => url.includes('detailId') ? { id: 9, beforeJson: '{"priority":0}', afterJson: '{"priority":5}', summary: '{"stale":true}' } : list }));
  await audit.loadAudit(true);
  const log = audit.auditLogs.value[0];

  await audit.toggleAuditDetail(log);
  assert.equal(log.beforeJson, '{"priority":0}');
  assert.equal(audit.describeAuditRow(log).changes[0].label, '優先度');

  await audit.loadAudit(true);
  assert.equal(audit.describeAuditRow(audit.auditLogs.value[0]).meta[0], 'GET');
});
