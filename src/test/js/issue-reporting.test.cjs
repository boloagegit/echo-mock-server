const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const staticRoot = path.resolve(__dirname, '../../main/resources/static');
const source = file => fs.readFileSync(path.join(staticRoot, file), 'utf8');

function vueHarness() {
  const watchers = new Map();
  return {
    Vue: {
      ref: value => ({ value }),
      computed: getter => ({ get value() { return getter(); } }),
      watch: (ref, callback) => {
        if (!watchers.has(ref)) watchers.set(ref, []);
        watchers.get(ref).push(callback);
      },
    },
    async notify(ref) {
      for (const callback of watchers.get(ref) || []) await callback(ref.value);
    },
  };
}

function routerHarness(enabled, hash = '#/issues') {
  const h = vueHarness();
  const calls = { rules: 0, issues: 0, responses: 0 };
  const deps = {
    page: { value: 'rules' }, isAdmin: { value: true },
    issueReportingEnabled: { value: enabled },
    ruleFilter: { value: {} }, responseFilter: { value: '' },
    logFilter: { value: {} }, auditFilter: { value: {} },
    loadRules: () => calls.rules++, loadIssues: () => calls.issues++,
    loadResponseSummary: () => calls.responses++,
    loadLogs() {}, loadAudit() {}, loadAccounts() {}, loadBackupStatus() {}, loadStatus() {},
  };
  const window = { location: { hash }, history: {
    replaceState: (_state, _title, next) => { window.location.hash = next; },
    pushState: (_state, _title, next) => { window.location.hash = next; },
  } };
  const context = vm.createContext({ Vue: h.Vue, deps, window, URLSearchParams, setTimeout, clearTimeout });
  vm.runInContext(source('composables/useRouter.js') + '\nthis.router = useRouter(deps);', context);
  context.router.setupRouterWatchers();
  return { ...h, deps, window, calls, router: context.router };
}

test('disabled or unknown issue flag rejects direct links without loading reports', () => {
  for (const enabled of [false, undefined]) {
    const h = routerHarness(enabled, '#/issues?status=OPEN');
    h.router.applyUrlParams();
    assert.equal(h.deps.page.value, 'rules');
    assert.equal(h.window.location.hash, '#/rules');
    assert.equal(h.calls.issues, 0);
    assert.equal(h.calls.rules, 1);
  }
});

test('enabled flag preserves issue navigation and loading', () => {
  const h = routerHarness(true);
  h.router.applyUrlParams();
  assert.equal(h.deps.page.value, 'issues');
  assert.equal(h.calls.issues, 1);
  assert.equal(h.window.location.hash, '#/issues');
});

test('disabled flag also guards programmatic page changes', async () => {
  const h = routerHarness(false);
  h.deps.page.value = 'issues';
  await h.notify(h.deps.page);
  assert.equal(h.deps.page.value, 'rules');
  assert.equal(h.window.location.hash, '#/rules');
  assert.equal(h.calls.issues, 0);
});

test('disabling the feature while viewing issues redirects without affecting other routes', async () => {
  const h = routerHarness(true);
  h.router.applyUrlParams();
  h.deps.issueReportingEnabled.value = false;
  await h.notify(h.deps.issueReportingEnabled);
  assert.equal(h.deps.page.value, 'rules');
  assert.equal(h.window.location.hash, '#/rules');
  h.window.location.hash = '#/responses';
  h.router.applyUrlParams();
  assert.equal(h.deps.page.value, 'responses');
  assert.equal(h.calls.responses, 1);
});

function issuesHarness(enabled, respond) {
  const h = vueHarness();
  const calls = [];
  let loginChecks = 0;
  const deps = {
    issueReportingEnabled: { value: enabled }, isAdmin: { value: true }, loading: { value: {} },
    showToast() {}, showConfirm: async () => true, t: key => key,
    requireLogin: async () => { loginChecks++; return true; },
  };
  const apiCall = async (url, options) => {
    calls.push({ url, options });
    return respond ? respond(url, options) : { ok: true, json: async () => ({
      results: [{ id: 'existing-report' }], totalElements: 1, totalPages: 1, openCount: 1,
    }) };
  };
  const context = vm.createContext({ Vue: h.Vue, deps, apiCall, URLSearchParams, AbortController, console });
  vm.runInContext(source('composables/useIssues.js') + '\nthis.issues = useIssues(deps);', context);
  return { ...h, deps, calls, issues: context.issues, loginChecks: () => loginChecks };
}

test('disabled issue feature makes no reads, mutations, confirmations, or login prompts', async () => {
  const h = issuesHarness(false);
  assert.equal(await h.issues.loadIssues(true), false);
  for (const ref of [h.issues.issueFilter, h.issues.issueSort, h.issues.issuePage, h.issues.issuePageSize]) {
    await h.notify(ref);
  }
  assert.equal(await h.issues.createIssue('Example', 'Details'), false);
  assert.equal(await h.issues.replyIssue('example', 'Reply'), false);
  assert.equal(await h.issues.resolveIssue('example'), false);
  assert.equal(await h.issues.reopenIssue('example'), false);
  assert.equal(await h.issues.deleteIssue('example'), false);
  assert.equal(h.calls.length, 0);
  assert.equal(h.loginChecks(), 0);
});

test('enabled issue loading and mutations preserve existing request semantics', async () => {
  const h = issuesHarness(true);
  await h.issues.loadIssues(true);
  assert.ok(h.calls[0].url.startsWith('/api/admin/issues/page?'));
  assert.equal(h.issues.openCount.value, 1);
  assert.equal(h.issues.issues.value[0].id, 'existing-report');
  assert.equal(await h.issues.createIssue('Example', 'Details'), true);
  const create = h.calls.find(call => call.options.method === 'POST');
  assert.equal(create.url, '/api/admin/issues');
  assert.deepEqual(JSON.parse(create.options.body), { title: 'Example', description: 'Details' });
});

test('disabling clears only browser cache and aborts late results; re-enabling loads existing reports', async () => {
  let complete;
  const h = issuesHarness(true, () => new Promise(resolve => { complete = resolve; }));
  const pending = h.issues.loadIssues(true);
  const signal = h.calls[0].options.signal;
  h.deps.issueReportingEnabled.value = false;
  await h.notify(h.deps.issueReportingEnabled);
  assert.equal(signal.aborted, true);
  assert.equal(h.deps.loading.value.issues, false);
  complete({ ok: true, json: async () => ({ results: [{ id: 'stale' }], openCount: 1 }) });
  await pending;
  assert.equal(h.issues.issues.value.length, 0);
  assert.equal(h.issues.openCount.value, 0);
  h.deps.issueReportingEnabled.value = true;
  const reload = h.issues.loadIssues();
  assert.equal(h.calls.length, 2);
  complete({ ok: true, json: async () => ({
    results: [{ id: 'existing-report' }], totalElements: 1, totalPages: 1, openCount: 1,
  }) });
  await reload;
  assert.equal(h.issues.issues.value[0].id, 'existing-report');
});

test('navigation, page mount, app wiring, default config and cached asset versions agree', () => {
  const guard = "status?.issueReportingEnabled === true";
  assert.ok(source('components/SidebarNav.js').includes('v-if="isLoggedIn && ' + guard + '"'));
  // Pages render from shownPage, which follows page once the next page's data is ready.
  assert.ok(source('index.html').includes("v-if=\"shownPage==='issues' && " + guard + '"'));
  const app = source('app.js');
  assert.ok(app.includes('const issueReportingEnabled = computed(() => status.value?.issueReportingEnabled === true)'));
  assert.ok(app.includes('isAdmin, issueReportingEnabled, loadRules:'));
  assert.ok(app.includes('loading, isAdmin, issueReportingEnabled }'));
  const versions = {
    'composables/useRouter.js': '20261007.2', 'composables/useIssues.js': '20261001.1',
    'components/SidebarNav.js': '20261007.1', 'app.js': '20261007.3',
  };
  for (const [asset, version] of Object.entries(versions)) {
    assert.ok(source('index.html').includes(asset + '?v=' + version), asset);
  }
  const config = fs.readFileSync(path.join(staticRoot, '../application.yml'), 'utf8');
  assert.ok(config.includes('issue-reporting-enabled: ${ECHO_ISSUE_REPORTING_ENABLED:false}'));
});
