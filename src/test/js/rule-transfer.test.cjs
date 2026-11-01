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
      const payload = typeof options?.body === 'string' ? JSON.parse(options.body) : null;
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
const row = (number, extra = {}) => ({ row: number, protocol: 'HTTP', method: 'GET', matchKey: '/source', enabled: true,
  action: 'MOCK', duplicate: false, ...extra });
const preview = { total: 1, enabled: 1, disabled: 0, requirements: [], connections: [], errors: [], rows: [row(1)] };
const forwarding = { ...preview, rows: [row(1, { action: 'FORWARD', connectionKey: 'HTTP:CONNECTION:1' })],
  requirements: [{ key: 'HTTP:CONNECTION:1', protocol: 'HTTP', mode: 'CONNECTION', sourceName: 'payment-uat', rows: [1] }],
  connections: [{ selection: 'HTTP:CONNECTION:9:0', protocol: 'HTTP', mode: 'CONNECTION', name: 'payment-sit' }] };
const confirmations = f => f.calls.filter(call => call.url.endsWith('/confirm'));

/** Builds a plain object that evaluates the modal's computed properties and methods like a mounted component. */
function modal(f, props) {
  const definition = f.context.ImportModal;
  const vm = { t: key => key, ...definition.data(), ...props };
  for (const [name, fn] of Object.entries(definition.computed)) {
    Object.defineProperty(vm, name, { get: () => fn.call(vm) });
  }
  for (const [name, fn] of Object.entries(definition.methods)) { vm[name] = fn.bind(vm); }
  return vm;
}

test('selecting JSON and checking it previews without importing and opens the review step', async () => {
  const f = fixture(async url => url.endsWith('/preview') ? response(preview) : null);
  f.rules.handleImportFile(new ImportFile('rules.json', portable));
  await f.rules.doImport();
  assert.deepEqual(f.calls.map(call => call.url), ['/api/admin/rules/import/preview']);
  assert.equal(f.rules.importPreview.value.total, 1);
  assert.equal(f.rules.importStep.value, 'review');
  assert.equal(f.calls[0].payload.rules[0].id, 'source-id');
});

test('rules matching existing ones start unchecked and unchecked rows are sent as exclusions', async () => {
  const view = { ...preview, total: 2, rows: [row(1), row(2, { duplicate: true })] };
  const f = fixture(async url => {
    if (url.endsWith('/preview')) return response(view);
    if (url.endsWith('/confirm')) return response({ imported: 1 });
  });
  f.rules.handleImportFile(new ImportFile('rules.json', [portable[0], portable[0]]));
  await f.rules.doImport();
  assert.deepEqual([...f.rules.importExcluded.value], [2]);
  await f.rules.doImport();
  assert.deepEqual(confirmations(f)[0].payload.excludedRows, [2]);
});

test('forwarding rules go through the connection step and confirmation sends the chosen destination once', async () => {
  let release;
  const f = fixture(async url => {
    if (url.endsWith('/preview')) return response(forwarding);
    if (url.endsWith('/confirm')) { await new Promise(resolve => { release = resolve; }); return response({ imported: 1 }); }
  });
  f.rules.handleImportFile(new ImportFile('rules.json', portable));
  await f.rules.doImport();
  await f.rules.doImport();
  assert.equal(f.rules.importStep.value, 'connections');
  assert.equal(confirmations(f).length, 0);
  f.rules.setImportConnectionMapping({ key: 'HTTP:CONNECTION:1', selection: 'HTTP:CONNECTION:9:0' });
  const first = f.rules.doImport(); const second = f.rules.doImport();
  await Promise.resolve(); release(); await Promise.all([first, second]);
  assert.equal(confirmations(f).length, 1);
  assert.deepEqual({ ...confirmations(f)[0].payload.connectionMappings }, { 'HTTP:CONNECTION:1': 'HTTP:CONNECTION:9:0' });
  assert.equal(f.rules.importPreview.value, null);
});

test('a suggested destination is preselected but the user still passes the connection step', async () => {
  const view = { ...forwarding, requirements: [{ ...forwarding.requirements[0], suggestion: 'HTTP:CONNECTION:9:0' }] };
  const f = fixture(async url => url.endsWith('/preview') ? response(view) : null);
  f.rules.handleImportFile(new ImportFile('rules.json', portable));
  await f.rules.doImport();
  assert.equal(f.rules.importConnectionMappings.value['HTTP:CONNECTION:1'], 'HTTP:CONNECTION:9:0');
  await f.rules.doImport();
  assert.equal(f.rules.importStep.value, 'connections');
  assert.equal(confirmations(f).length, 0);
});

test('unchecking every forwarding rule skips the connection step and its mapping', async () => {
  const view = { ...forwarding, total: 2, rows: [row(1), forwarding.rows[0] && { ...forwarding.rows[0], row: 2 }],
    requirements: [{ ...forwarding.requirements[0], rows: [2] }] };
  const f = fixture(async url => {
    if (url.endsWith('/preview')) return response(view);
    if (url.endsWith('/confirm')) return response({ imported: 1 });
  });
  f.rules.handleImportFile(new ImportFile('rules.json', [portable[0], portable[0]]));
  await f.rules.doImport();
  f.rules.toggleImportRow(2);
  await f.rules.doImport();
  assert.equal(confirmations(f).length, 1);
  assert.deepEqual({ ...confirmations(f)[0].payload.connectionMappings }, {});
  assert.deepEqual([...confirmations(f)[0].payload.excludedRows], [2]);
});

test('going back from review discards the preview so another file can be chosen', async () => {
  const f = fixture(async url => url.endsWith('/preview') ? response(forwarding) : null);
  f.rules.handleImportFile(new ImportFile('rules.json', portable));
  await f.rules.doImport(); await f.rules.doImport();
  f.rules.importBack();
  assert.equal(f.rules.importStep.value, 'review');
  f.rules.importBack();
  assert.equal(f.rules.importStep.value, 'file');
  assert.equal(f.rules.importPreview.value, null);
  assert.equal(f.rules.importFileName.value, 'rules.json');
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
  assert.equal(confirmations(f).length, 0);
});

test('Excel files are parsed by the server and then share the same preview', async () => {
  const f = fixture(async url => {
    if (url.endsWith('/import-excel/parse')) return response({ rules: portable });
    if (url.endsWith('/preview')) return response(preview);
  });
  f.context.FormData = class { append() {} };
  f.rules.importFormat.value = 'excel';
  f.rules.handleImportFile(new ImportFile('rules.xlsx', 'binary'));
  await f.rules.doImport();
  assert.deepEqual(f.calls.map(call => call.url), ['/api/admin/rules/import-excel/parse', '/api/admin/rules/import/preview']);
  assert.equal(f.calls[1].payload.rules[0].matchKey, '/source');
  assert.equal(confirmations(f).length, 0);
});

test('a lost confirmation reply is not retried automatically and invalidates the preview', async () => {
  const f = fixture(async url => {
    if (url.endsWith('/preview')) return response(preview);
    if (url.endsWith('/confirm')) throw new Error('lost reply after possible commit');
  });
  f.rules.handleImportFile(new ImportFile('rules.json', portable)); await f.rules.doImport(); await f.rules.doImport();
  assert.equal(confirmations(f).length, 1);
  assert.equal(f.rules.importPreview.value, null);
  assert.ok(f.calls.some(call => call.url.includes('/rules/page?')));
});

test('modal blocks invalid checked rules and choices from the wrong protocol', () => {
  const f = fixture(async () => null);
  const view = { ...forwarding, connections: [{ selection: 'same-id', protocol: 'JMS' }] };
  const state = modal(f, { show: true, importFile: {}, loading: false, importFormat: 'json', step: 'connections',
    preview: view, excluded: [], connectionMappings: { 'HTTP:CONNECTION:1': 'same-id' } });
  assert.equal(state.canSubmit, false);
  view.connections[0].protocol = 'HTTP'; assert.equal(state.canSubmit, true);
  view.errors = [{ row: 1, code: 'INVALID_RULE', message: 'matchKey is required' }];
  assert.equal(state.canSubmit, false);
  assert.equal(state.problemText(view.errors[0]), 'modal.importProblemWithDetail');
  state.excluded = [1];
  assert.equal(state.canSubmit, false, 'nothing left to import');
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
  assert.equal(confirmations(f).length, 1);
  assert.equal(f.rules.importPreview.value, null);
  assert.ok(f.calls.some(call => call.url.includes('/rules/page?')));
});

test('a failed list refresh cannot re-enable an already confirmed import', async () => {
  const f = fixture(async url => {
    if (url.endsWith('/preview')) return response(preview);
    if (url.endsWith('/confirm')) return response({imported: 1});
    if (url.includes('/rules/page?')) return {...response({}), json: async () => { throw new SyntaxError('truncated list JSON'); }};
  });
  f.rules.showImportModal.value = true;
  f.rules.handleImportFile(new ImportFile('rules.json', portable));
  await f.rules.doImport(); await f.rules.doImport();
  assert.equal(f.rules.showImportModal.value, false);
  assert.equal(f.rules.importPreview.value, null);
  assert.equal(f.rules.importFile.value, null);
  assert.ok(!f.toasts.some(([message]) => message === 'toast.fileFormatError'));
  await f.rules.doImport();
  assert.equal(confirmations(f).length, 1);
});

test('export sends the checked rule ids or the active filter, never silently everything', async () => {
  const f = fixture(async url => url.includes('/rules/export') ? response([]) : null);
  f.context.URL = { createObjectURL: () => 'blob:x', revokeObjectURL() {} };
  f.context.Blob = class {};
  f.context.document = { createElement: () => ({ click() {} }), body: { appendChild() {}, removeChild() {} } };
  f.rules.selectedRules.value = ['a', 'b'];
  await f.rules.exportRules('selected');
  const post = f.calls.at(-1);
  assert.equal(post.url, '/api/admin/rules/export');
  assert.deepEqual([...post.payload.ids], ['a', 'b']);
});
