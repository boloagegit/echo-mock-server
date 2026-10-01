const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../../main/resources/static');
const source = file => fs.readFileSync(path.join(root, file), 'utf8');
const Vue = {
  ref: value => ({ value }),
  computed: get => ({ get value() { return get(); } }),
  watch() {},
  nextTick: async callback => callback?.(),
};

function guardHarness() {
  const state = { draft: { path: '', conditions: [], body: '' }, open: true, busy: false };
  const calls = { confirms: 0, discards: 0 };
  let resolveConfirm;
  const deps = {
    readDraft: () => state.draft, isOpen: () => state.open, isBusy: () => state.busy,
    discard: () => { calls.discards++; state.open = false; },
    showConfirm: options => {
      calls.confirms++;
      calls.options = options;
      return new Promise(resolve => { resolveConfirm = resolve; });
    },
    t: key => key,
  };
  const context = vm.createContext({ deps });
  vm.runInContext(source('composables/useDiscardGuard.js') + '\nthis.guard = useDiscardGuard(deps);', context);
  context.guard.begin();
  return { state, calls, guard: context.guard, decide: value => resolveConfirm(value) };
}

test('unchanged and reverted drafts close without a prompt', async () => {
  for (const revert of [false, true]) {
    const h = guardHarness();
    if (revert) { h.state.draft.path = '/users'; h.state.draft.path = ''; }
    assert.equal(h.guard.isDirty(), false);
    assert.equal(await h.guard.requestClose(), true);
    assert.equal(h.calls.confirms, 0);
    assert.equal(h.calls.discards, 1);
  }
});

test('cancel preserves every unsaved field and declining remains dirty', async () => {
  const h = guardHarness();
  h.state.draft = { path: '/users', conditions: [{ type: 'body', value: 'Alice' }], body: '{broken JSON' };
  const expected = JSON.stringify(h.state.draft);
  const close = h.guard.requestClose();
  h.decide(false);
  assert.equal(await close, false);
  assert.equal(JSON.stringify(h.state.draft), expected);
  assert.equal(h.state.open, true);
  assert.equal(h.guard.isDirty(), true);
  assert.equal(h.calls.discards, 0);
  assert.equal(h.calls.options.cancelText, 'confirm.continueEditing');
});

test('confirmation discards only once and clears its baseline', async () => {
  const h = guardHarness();
  h.state.draft.body = 'changed';
  const first = h.guard.requestClose();
  const second = h.guard.requestClose();
  assert.equal(first, second);
  assert.equal(h.calls.confirms, 1);
  h.decide(true);
  assert.equal(await first, true);
  assert.equal(h.calls.discards, 1);
  h.state.open = true;
  assert.equal(h.guard.isDirty(), false);
});

test('in-flight save blocks closing and a successful save resets the boundary', async () => {
  const h = guardHarness();
  h.state.draft.body = 'changed';
  h.state.busy = true;
  assert.equal(await h.guard.requestClose(), false);
  assert.equal(h.calls.confirms, 0);
  h.state.busy = false;
  h.guard.begin();
  assert.equal(h.guard.isDirty(), false);
  await h.guard.requestClose();
  assert.equal(h.calls.confirms, 0);
});

test('a save begun while a prompt is open cannot be discarded', async () => {
  const h = guardHarness();
  h.state.draft.path = '/users';
  const close = h.guard.requestClose();
  h.state.busy = true;
  h.decide(true);
  assert.equal(await close, false);
  assert.equal(h.calls.discards, 0);
});

test('object key order is not a modification; array order and invalid documents are', () => {
  const h = guardHarness();
  h.state.draft = { body: '', conditions: [], path: '' };
  assert.equal(h.guard.isDirty(), false);
  h.state.draft.conditions = [{ value: 'A' }, { value: 'B' }];
  h.guard.begin();
  h.state.draft.conditions.reverse();
  assert.equal(h.guard.isDirty(), true);
  h.guard.begin();
  h.state.draft.declarative = '{';
  assert.equal(h.guard.isDirty(), true);
});

function appDraftHarness() {
  const context = vm.createContext({
    Vue, serializeSseEvents: events => JSON.stringify(events), deserializeSseEvents: body => body ? JSON.parse(body) : [],
    deps: { t: key => key },
    form: Vue.ref({ protocol: 'JMS', matchKey: 'ORDER', action: 'MOCK', responseMode: 'new', responseBody: 'OK' }),
    conditions: Vue.ref([]), editing: Vue.ref(null), sseEvents: Vue.ref([]),
    newTag: Vue.ref({ key: '', value: '' }), newHeader: Vue.ref({ key: '', value: '' }),
    previewEditing: Vue.ref(false), previewEditBody: Vue.ref(''), previewResponseBody: Vue.ref(''),
    ruleApplyText: Vue.ref(''), ruleApplyDraftBaseline: Vue.ref(''),
  });
  vm.runInContext(source('composables/useRuleApply.js') + '\nconst { createDocumentFromForm } = useRuleApply(deps);', context);
  vm.runInContext(source('composables/useDiscardGuard.js'), context);
  const app = source('app.js');
  const normalize = app.slice(app.indexOf('const normalizeDraftText ='), app.indexOf('const ruleSaveInFlight ='));
  const readDraft = app.slice(app.indexOf('const readRuleDraft ='), app.indexOf('const discardRuleDraft ='));
  vm.runInContext(normalize + readDraft + `
    this.guard = useDiscardGuard({readDraft: readRuleDraft, isOpen:()=>true, isBusy:()=>false});
    guard.begin();
  `, context);
  return context;
}

test('actual app draft comparison distinguishes cleared and JSON-null declarations from no changes', () => {
  for (const text of ['', 'null']) {
    const h = appDraftHarness();
    h.ruleApplyText.value = h.ruleApplyDraftBaseline.value = '{"spec":{"matchKey":"ORDER"}}';
    h.guard.begin();
    h.ruleApplyText.value = text;
    assert.equal(h.guard.isDirty(), true);
  }
});

test('actual app comparison includes JMS reply queue without changing the declarative contract', () => {
  const h = appDraftHarness();
  h.form.value.replyQueue = 'REPLY.ORDER';
  assert.equal(h.guard.isDirty(), true);
});

test('complete save interval locks both editor bodies and mode controls, including before and after the API response', () => {
  const app = source('app.js');
  const declaration = app.slice(app.indexOf('const applyRuleSettings ='), app.indexOf('// === 帳號管理 composable'));
  assert.ok(declaration.indexOf('ruleSaveInFlight.value = true') < declaration.indexOf('await applyRuleDocument()'));
  assert.ok(declaration.indexOf('finally { ruleSaveInFlight.value = false; }') > declaration.indexOf('markRuleDraftSaved()'));
  const index = source('index.html');
  assert.equal((index.match(/:saving="saving \|\| ruleSaveInFlight \|\| ruleApplySaving \|\| previewSaving"/g) || []).length, 2);
  assert.match(source('components/RuleEditModal.js'), /class="modal-body rule-editor" :inert="saving" :aria-busy="saving"/);
  assert.match(source('components/RuleEditModal.js'), /class="rule-editor-mode-switch" :inert="saving"/);
  assert.match(source('components/RuleApplyModal.js'), /class="modal-body rule-apply-body" :inert="saving" :aria-busy="saving"/);
  assert.ok(app.includes('wasDirty = wasDirty || ruleDraftGuard.isDirty();'));
});

test('leaving a dirty rule for response management waits for confirmation and preserves context on cancellation', async () => {
  for (const confirmed of [false, true]) {
    let resolveClose;
    const navigations = [];
    const context = vm.createContext({
      closeModal: () => new Promise(resolve => { resolveClose = resolve; }),
      goToResponse: id => navigations.push(id),
    });
    const app = source('app.js');
    vm.runInContext(app.slice(app.indexOf('const leaveRuleForResponses ='), app.indexOf('const markRuleDraftSaved ='))
      + '\nthis.leave = leaveRuleForResponses;', context);
    const pending = context.leave(7);
    assert.equal(navigations.length, 0);
    resolveClose(confirmed);
    await pending;
    assert.deepEqual(navigations, confirmed ? ['7'] : []);
  }
  assert.ok(source('index.html').includes('@go-to-responses="leaveRuleForResponses($event)"'));
  assert.ok(!source('components/RuleEditModal.js').includes("$emit('close');$emit('go-to-responses'"));
});

test('pending schema mode switch never resumes into a busy, closed, or different editor session', async () => {
  for (const stateChange of ['busy', 'closed', 'session', 'typed']) {
    let resolveSchema;
    let dirty = false;
    let rebases = 0;
    const context = vm.createContext({
      ruleSaveInFlight: Vue.ref(false), saving: Vue.ref(false), ruleApplySaving: Vue.ref(false), previewSaving: Vue.ref(false),
      ruleEditorSession: 1, showModal: Vue.ref(true), ruleEditorMode: Vue.ref('form'),
      ruleDraftGuard: { isDirty: () => dirty },
      loadRuleApplySchema: () => new Promise(resolve => { resolveSchema = resolve; }),
      form: Vue.ref({}), conditions: Vue.ref([]), editing: Vue.ref(null), currentRuleResponseBody: () => '',
      createDocumentFromForm: () => ({ spec: { matchKey: '' } }),
      ruleApplyText: Vue.ref(''), ruleApplyFormSource: Vue.ref(''), ruleApplyDraftBaseline: Vue.ref(''),
      setRuleApplyDocument: () => { context.ruleApplyText.value = '{"spec":{}}'; },
      rememberRuleEditorMode() {}, markRuleDraftSaved: () => rebases++,
    });
    const app = source('app.js');
    vm.runInContext(app.slice(app.indexOf('const changeRuleEditorMode ='), app.indexOf('const applyRuleSettings ='))
      + '\nthis.changeMode = changeRuleEditorMode;', context);
    const pending = context.changeMode('declarative');
    if (stateChange === 'busy') context.ruleSaveInFlight.value = true;
    if (stateChange === 'closed') context.showModal.value = false;
    if (stateChange === 'session') context.ruleEditorSession++;
    if (stateChange === 'typed') dirty = true;
    resolveSchema(true);
    await pending;
    assert.equal(context.ruleEditorMode.value, stateChange === 'typed' ? 'declarative' : 'form');
    assert.equal(rebases, 0);
  }
});

test('unsaved inactive editor and uncommitted auxiliary inputs can remain dirty after saving the form', () => {
  const h = guardHarness();
  h.state.draft = { spec: { matchKey: '/saved' }, declarative: '{broken', pendingTag: { key: 'env', value: 'test' } };
  h.guard.begin({ ...h.state.draft, declarative: null, pendingTag: { key: '', value: '' } });
  assert.equal(h.guard.isDirty(), true);
});

test('failed confirmation does not permanently lock closing', async () => {
  const h = guardHarness();
  h.state.draft.path = '/users';
  const first = h.guard.requestClose();
  h.decide(false);
  await first;
  const next = h.guard.requestClose();
  assert.equal(h.calls.confirms, 2);
  h.decide(true);
  assert.equal(await next, true);
});

function tourHarness() {
  let finishes = 0;
  const storage = new Map();
  const context = vm.createContext({
    Vue, deps: { t: key => key, onFinish: () => finishes++ },
    localStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) },
  });
  vm.runInContext(source('composables/useTour.js') + '\nthis.tour = useTour(deps);', context);
  return { tour: context.tour, storage, finishes: () => finishes };
}

test('tour skip invokes context restoration exactly once', () => {
  const h = tourHarness();
  h.tour.startTour();
  h.tour.skipTour();
  h.tour.skipTour();
  assert.equal(h.finishes(), 1);
  assert.equal(h.tour.tourActive.value, false);
  assert.equal(h.storage.get('echo_help_seen'), '1');
});

test('finishing every tour step also restores its entry context', () => {
  const h = tourHarness();
  h.tour.startTour();
  for (let i = 0; i < h.tour.tourSteps.value.length; i++) h.tour.nextStep();
  assert.equal(h.finishes(), 1);
  assert.equal(h.tour.tourStep.value, 0);
});

function menuPosition(trigger, bounds, width = 168, height = 76) {
  const context = vm.createContext({ trigger, bounds, width, height,
    shortId() {}, parseTags() {}, condTags() {}, fmtTime() {} });
  vm.runInContext(source('components/RuleListParts.js') + '\nthis.position = ruleMenuPosition(trigger, bounds, width, height);', context);
  return context.position;
}

test('lower-edge menu is clamped above the table footer, including its last action', () => {
  const bounds = { left: 233, right: 1415, top: 139, bottom: 830 };
  const p = menuPosition({ left: 1371, top: 812, height: 32 }, bounds);
  assert.equal(p.top, 750);
  assert.ok(p.top + 76 <= bounds.bottom - 4);
});

test('first-row menu retains its nearby placement', () => {
  const p = menuPosition({ left: 1371, top: 199, height: 32 }, { left: 233, right: 1415, top: 139, bottom: 830 });
  assert.equal(p.top, 177);
  assert.equal(p.left, 1199);
});

test('narrow and top-edge menus stay inside the available viewport', () => {
  const bounds = { left: 24, right: 366, top: 139, bottom: 760 };
  const p = menuPosition({ left: 112, top: 143, height: 32 }, bounds);
  assert.ok(p.left >= bounds.left + 4);
  assert.ok(p.left + 168 <= bounds.right - 4);
  assert.ok(p.top >= bounds.top + 4);
});

test('narrow rule editor footer keeps its validation hint separate from actions', () => {
  assert.match(source('style.css'), /@media \(max-width: 576px\)\s*\{\s*\.rule-modal-fullscreen > \.modal-footer \.modal-footer-status \{ flex-basis: 100% \}/);
});

function formHarness(ok) {
  const calls = [];
  const deps = {
    t: key => key, showToast() {}, showConfirm: async () => true,
    requireLogin: async () => true, login() {}, loadRules() {}, rulesMarkDirty() {}, responsesMarkDirty() {},
    rulePreviewCache: { value: {} }, rulePreviewExpanded: { value: {} },
    responseSseEvents: { value: [] }, renderEditor() {},
    editFormatted: { value: false }, previewFormatted: { value: false },
    responseFormFormatted: { value: false }, jmsEnabled: { value: true },
  };
  const context = vm.createContext({
    Vue, deps, console, AbortController, URLSearchParams,
    localStorage: { getItem: () => null, setItem() {} },
    parseTags: () => ({}), parseHeaders: () => ({}),
    apiCall: async (url, options = {}) => {
      calls.push({ url, options });
      return { ok, status: ok ? 200 : 409, json: async () => url.includes('target-connections') ? []
        : { id: 'saved-rule', version: 1, responseId: 5 } };
    },
  });
  vm.runInContext(source('composables/useRuleForm.js') + '\nthis.form = useRuleForm(deps);', context);
  return { form: context.form, calls };
}

test('form save reports success for the guard without altering its API payload', async () => {
  const h = formHarness(true);
  await h.form.openCreate();
  h.form.form.value.matchKey = '/guard-test';
  h.form.form.value.responseBody = 'OK';
  assert.equal(await h.form.saveRule(false), true);
  assert.equal(h.form.showModal.value, true);
  assert.equal(h.form.form.value.id, 'saved-rule');
  assert.equal(h.form.saving.value, false);
  const save = h.calls.find(call => call.options.method === 'POST');
  assert.equal(save.url, '/api/admin/rules');
  assert.equal(JSON.parse(save.options.body).responseBody, 'OK');
});

test('a failed save never reports clean state and retains the draft', async () => {
  const h = formHarness(false);
  await h.form.openCreate();
  h.form.form.value.matchKey = '/guard-test';
  h.form.form.value.responseBody = 'OK';
  assert.equal(await h.form.saveRule(false), false);
  assert.equal(h.form.showModal.value, true);
  assert.equal(h.form.form.value.responseBody, 'OK');
  assert.equal(h.form.saving.value, false);
});

test('all sidebar buttons have permanent names and current-page semantics', () => {
  const sidebar = source('components/SidebarNav.js');
  const buttons = sidebar.match(/<button[^>]*class="nav-item"[^>]*>/g);
  assert.equal(buttons.length, 14);
  assert.ok(buttons.every(button => button.includes(':aria-label=')));
  assert.equal(buttons.filter(button => button.includes(':aria-current=')).length, 7);
  assert.ok(!sidebar.includes("helpSeen ? $emit"));
});

test('condition controls and discard text are localized in both supported languages', () => {
  const component = source('components/RuleEditModal.js');
  for (const key of ['conditionTypeLabel', 'conditionFieldLabel', 'conditionOperatorLabel', 'conditionValueLabel', 'removeConditionLabel']) {
    assert.ok(component.includes("t('modal." + key + "',{index:i+1})"));
    for (const language of ['en', 'zh-TW']) {
      assert.ok(JSON.parse(source('i18n/' + language + '.json')).modal[key].includes('{index}'));
    }
  }
  for (const language of ['en', 'zh-TW']) {
    const confirm = JSON.parse(source('i18n/' + language + '.json')).confirm;
    for (const key of ['discardRuleTitle', 'discardRuleMessage', 'discardChanges', 'continueEditing']) assert.ok(confirm[key]);
  }
});

test('entry resources wire the guard, help flow, save baseline and unload cleanup', () => {
  const index = source('index.html');
  assert.ok(index.indexOf('/composables/useDiscardGuard.js') < index.indexOf('/app.js'));
  assert.ok(index.includes('@start-tour="startTour"'));
  const app = source('app.js');
  for (const snippet of ['isBusy: () => ruleSaveInFlight.value', 'if (!await saveRuleForm(andClose)) return;',
    'let wasDirty = ruleDraftGuard.isDirty();', 'if (e.defaultPrevented) return;',
    "window.removeEventListener('beforeunload', handleBeforeUnload)"] ) assert.ok(app.includes(snippet));
  const css = source('style.css');
  assert.ok(css.includes('color: var(--success-strong)'));
  assert.ok(css.includes('.settings-danger-zone-header { color: var(--danger-text) }'));
});
