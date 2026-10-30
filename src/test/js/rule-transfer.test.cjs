const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/static');
class ImportFile {
  constructor(name, rules) { this.name = name; this.content = typeof rules === 'string' ? rules : JSON.stringify(rules); }
  async text() { return this.content; }
}
function response(body, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body, clone() { return this; } };
}
function fixture(respond) {
  const calls = [], toasts = [], storage = new Map();
  const context = { console: { warn() {} }, window: {}, document: {}, navigator: {}, setTimeout, clearTimeout,
    URLSearchParams, AbortController, File: ImportFile,
    localStorage: { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, v) },
    Vue: { ref: value => ({ value }), computed: fn => ({ get value() { return fn(); } }), watch() {} },
    fetch: async (url, options) => {
      const payload = options?.body ? JSON.parse(options.body) : null;
      calls.push({ url, payload });
      return await respond(url, payload) || response({ results: [], totalElements: 0, totalPages: 0 });
    }
  };
  vm.runInNewContext(['utils.js', 'composables/useRules.js', 'components/ImportModal.js']
    .map(file => fs.readFileSync(path.join(root, file), 'utf8')).join('\n') + '\nthis.useRules=useRules;this.ImportModal=ImportModal;', context);
  const rules = context.useRules({ showToast: (...args) => toasts.push(args), showConfirm: async () => true,
    t: key => key, requireLogin: async () => true, login() {}, isLoggedIn: { value: true },
    loading: { value: {} }, page: { value: 'rules' }, httpLabel: { value: 'HTTP' }, jmsLabel: { value: 'JMS' } });
  return { rules, calls, toasts, context };
}
const portable = [{ id: 'source-id', protocol: 'HTTP', exportVersion: 1, matchKey: '/source', responseBody: 'source' }];
const preview = { total: 1, enabled: 1, disabled: 0, requirements: [], connections: [], errors: [] };

test('selecting JSON and previewing does not perform an import', async () => {
  const f = fixture(async url => url.endsWith('/preview') ? response(preview) : null);
  f.rules.handleImportFile(new ImportFile('rules.json', portable));
  await f.rules.doImport();
  assert.deepEqual(f.calls.map(call => call.url), ['/api/admin/rules/import/preview']);
  assert.equal(f.rules.importPreview.value.total, 1);
  assert.equal(f.calls[0].payload.rules[0].id, 'source-id');
});

test('confirmation sends the explicitly selected destination and prevents double submit', async () => {
  let release;
  const view = { ...preview, requirements: [{ key: 'HTTP:CONNECTION:1', protocol: 'HTTP' }],
    connections: [{ selection: 'HTTP:CONNECTION:9:0', protocol: 'HTTP' }] };
  const f = fixture(async url => {
    if (url.endsWith('/preview')) return response(view);
    if (url.endsWith('/confirm')) { await new Promise(resolve => { release = resolve; }); return response({ imported: 1 }); }
  });
  f.rules.handleImportFile(new ImportFile('rules.json', portable));
  await f.rules.doImport();
  f.rules.setImportConnectionMapping({ key: 'HTTP:CONNECTION:1', selection: 'HTTP:CONNECTION:9:0' });
  const first = f.rules.doImport(); const second = f.rules.doImport();
  await Promise.resolve(); release(); await Promise.all([first, second]);
  const confirmations = f.calls.filter(call => call.url.endsWith('/confirm'));
  assert.equal(confirmations.length, 1);
  assert.deepEqual(confirmations[0].payload.connectionMappings, { 'HTTP:CONNECTION:1': 'HTTP:CONNECTION:9:0' });
  assert.equal(f.rules.importPreview.value, null);
});

test('replacing the selected file discards the old preview and connection choices', async () => {
  const f = fixture(async url => url.endsWith('/preview') ? response(preview) : null);
  f.rules.handleImportFile(new ImportFile('first.json', portable)); await f.rules.doImport();
  f.rules.setImportConnectionMapping({ key: 'old-key', selection: 'old-choice' });
  f.rules.handleImportFile(new ImportFile('second.json', [{ ...portable[0], matchKey: '/second' }]));
  assert.equal(f.rules.importPreview.value, null);
  assert.equal(Object.keys(f.rules.importConnectionMappings.value).length, 0);
  await f.rules.doImport();
  assert.equal(f.calls.at(-1).payload.rules[0].matchKey, '/second');
  assert.equal(f.calls.filter(call => call.url.endsWith('/confirm')).length, 0);
});

test('missing connection choices cannot send a confirmation request', async () => {
  const f = fixture(async url => url.endsWith('/preview') ? response({ ...preview,
    requirements: [{ key: 'HTTP:CONNECTION:1', protocol: 'HTTP' }] }) : null);
  f.rules.handleImportFile(new ImportFile('rules.json', portable)); await f.rules.doImport(); await f.rules.doImport();
  assert.equal(f.calls.filter(call => call.url.endsWith('/confirm')).length, 0);
});

test('a lost confirmation reply is not retried automatically and invalidates the preview', async () => {
  const f = fixture(async url => {
    if (url.endsWith('/preview')) return response(preview);
    if (url.endsWith('/confirm')) throw new Error('lost reply after possible commit');
  });
  f.rules.handleImportFile(new ImportFile('rules.json', portable)); await f.rules.doImport(); await f.rules.doImport();
  assert.equal(f.calls.filter(call => call.url.endsWith('/confirm')).length, 1);
  assert.equal(f.rules.importPreview.value, null);
  assert.ok(f.calls.some(call => call.url.includes('/rules/page?')));
});

test('modal blocks invalid rules and choices from the wrong protocol', () => {
  const f = fixture(async () => null);
  const canSubmit = f.context.ImportModal.computed.canSubmit;
  const state = { importFile: {}, loading: false, importFormat: 'json', preview: { ...preview,
    requirements: [{ key: 'source', protocol: 'HTTP' }], connections: [{ selection: 'same-id', protocol: 'JMS' }] },
    connectionMappings: { source: 'same-id' }, blockingErrors: [] };
  assert.equal(canSubmit.call(state), false);
  state.preview.connections[0].protocol = 'HTTP'; assert.equal(canSubmit.call(state), true);
  state.blockingErrors = [{ code: 'INVALID_RULE' }]; assert.equal(canSubmit.call(state), false);
});

test('a file cannot be replaced while its preview request is in flight', async () => {
  let release;
  const f = fixture(async url => {
    if (url.endsWith('/preview')) { await new Promise(resolve => { release = resolve; }); return response(preview); }
  });
  f.rules.handleImportFile(new ImportFile('first.json', portable));
  const pending = f.rules.doImport();
  await new Promise(resolve => setImmediate(resolve));
  f.rules.handleImportFile(new ImportFile('second.json', [{ ...portable[0], matchKey: '/second' }]));
  release(); await pending;
  assert.equal(f.rules.importFileName.value, 'first.json');
  assert.equal(f.rules.importPreview.value.total, 1);
});

test('a server error after confirmation is treated as an uncertain result before another import', async () => {
  const f = fixture(async url => {
    if (url.endsWith('/preview')) return response(preview);
    if (url.endsWith('/confirm')) return response({error: 'post-commit response failure'}, 500);
  });
  f.rules.handleImportFile(new ImportFile('rules.json', portable));
  await f.rules.doImport(); await f.rules.doImport();
  assert.equal(f.calls.filter(call => call.url.endsWith('/confirm')).length, 1);
  assert.equal(f.rules.importPreview.value, null);
  assert.ok(f.calls.some(call => call.url.includes('/rules/page?')));
});
