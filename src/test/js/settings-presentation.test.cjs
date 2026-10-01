const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function fixture(name, props = {}, api = async () => ({ ok: true, json: async () => [] })) {
  const events = [], calls = [];
  const sandbox = { Date, fmtSize: value => String(value), window: { confirm: () => true },
    apiCall: async (...args) => { calls.push(args); return api(...args); } };
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/components/' + name + '.js'), 'utf8') + '\nthis.component = ' + name, sandbox);
  const component = sandbox.component;
  const instance = { ...component.data(), ...props, t: key => key,
    $emit: (...args) => events.push(args), $nextTick: fn => fn(), $refs: {} };
  for (const [key, method] of Object.entries(component.methods)) instance[key] = method.bind(instance);
  for (const [key, getter] of Object.entries(component.computed || {})) Object.defineProperty(instance, key, { get: getter.bind(instance) });
  return { instance, component, calls, events, sandbox };
}

test('shared target roles preserve YAML priority and distinguish fallback from failover', () => {
  const { instance } = fixture('ConnectionTargetsTable', { protocol: 'jms', yamlJmsTargetConfigured: true });
  assert.equal(instance.roleLabel({ legacy: true, enabled: true }), 'settings.jmsTargetForcedDefault');
  assert.equal(instance.roleLabel({ defaultConnection: true, enabled: true }), 'settings.jmsTargetFallbackDefault');
  assert.equal(instance.roleHint({ defaultConnection: true, enabled: true }), 'settings.connectionFallbackOnly');
  instance.yamlJmsTargetConfigured = false;
  assert.equal(instance.roleLabel({ defaultConnection: true, enabled: true }), 'settings.jmsTargetDefault');
  instance.protocol = 'http';
  assert.equal(instance.roleLabel({ defaultConnection: true, enabled: true }), 'settings.httpTargetDefault');
  assert.equal(instance.roleLabel({ enabled: false }), 'settings.disabled');
});

test('target menu never emits legacy or disabled mutations; default deletion is explained', () => {
  const { instance, events } = fixture('ConnectionTargetsTable', { protocol: 'http' });
  const target = { id: 'test', enabled: true, defaultConnection: true };
  assert.equal(instance.menuItems(target)[1].title, 'settings.defaultConnectionDeleteDisabled');
  instance.selectAction('delete', target);
  instance.selectAction('make-default', target);
  instance.selectAction('delete', { legacy: true });
  instance.selectAction('make-default', { enabled: false });
  instance.selectAction('unknown', { enabled: true });
  assert.equal(events.length, 0);
  target.defaultConnection = false;
  instance.selectAction('make-default', target);
  instance.selectAction('delete', target);
  assert.equal(events.length, 2);
  assert.equal(events[0][0], 'make-default');
  assert.equal(events[0][1], target);
  assert.equal(events[1][0], 'delete');
});

test('numeric HTTP ids are supported and another table menu or focus closes the old menu', () => {
  const { instance, component, events } = fixture('ConnectionTargetsTable', { protocol: 'http' });
  assert.equal(component.props.testingId.length, 2);
  assert.equal(component.props.testingId[1].name, 'Number');
  const own = { closest: () => ({}) };
  const other = { closest: () => ({}) };
  instance.$el = { contains: element => element === own };
  instance.openMenuId = 123;
  instance.closeOutside({ target: own });
  assert.equal(instance.openMenuId, 123);
  instance.closeOutside({ target: other });
  assert.equal(instance.openMenuId, null);
  instance.selectAction('make-default', { id: 123, enabled: true });
  assert.equal(events[0][1].id, 123);
});

test('local navigation keeps diagnostics available before database status and makes no API calls', () => {
  const { instance, calls, events } = fixture('SettingsPage', { isAdmin: true, status: null });
  assert.equal(instance.tabs.length, 5);
  let opened;
  instance.$refs.resources = { openSection: value => { opened = value; } };
  instance.navigateTab('monitoring', 'jms');
  assert.equal(opened, 'jms');
  assert.equal(instance.activeTab, 'monitoring');
  instance.navigateTab('invalid');
  assert.equal(instance.activeTab, 'monitoring');
  assert.equal(calls.length, 0);
  assert.equal(events.length, 0);
  instance.isAdmin = false;
  instance.navigateTab('connections');
  assert.equal(instance.activeTab, 'monitoring');
  assert.equal(instance.tabs.length, 3);
});

test('only explicit refresh advances monitoring token and emits legacy refresh', () => {
  const { instance, events } = fixture('SettingsPage', { isAdmin: true });
  instance.navigateTab('data');
  assert.equal(instance.resourceRefreshToken, 0);
  instance.refreshStatus();
  assert.equal(instance.resourceRefreshToken, 1);
  assert.equal(events[0][0], 'refresh-status');
  const entry = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/index.html'), 'utf8');
  assert.ok(entry.includes('@refresh-status="loadStatus(); loadBackupStatus()"'));
});

test('losing administrator permission selects an existing tab and clears the retained snapshot', () => {
  const { instance, component } = fixture('SettingsPage', { isAdmin: true });
  instance.activeTab = 'connections';
  instance.resourceSnapshot = { collectedAt: 'old-sample' };
  instance.showJmsTargetForm = true;
  instance.isAdmin = false;
  component.watch.isAdmin.call(instance, false);
  assert.equal(instance.activeTab, 'overview');
  assert.ok(instance.tabs.some(tab => tab.value === instance.activeTab));
  assert.equal(instance.resourceSnapshot, null);
  assert.equal(instance.showJmsTargetForm, false);
});

test('storage preserves unavailable and zero; failed snapshot retains data and permission clear removes it', () => {
  const { instance } = fixture('SettingsPage');
  assert.equal(instance.storageMetric('dataFreeBytes'), 'monitoring.unavailable');
  instance.updateResourceSnapshot({ snapshot: { sections: { storage: { state: 'AVAILABLE', values: { dataFreeBytes: 0, backupFreeBytes: 2 * 1024 ** 3 } } } }, failed: true });
  assert.equal(instance.resourceFailed, true);
  assert.equal(instance.storageMetric('dataFreeBytes'), '0 B');
  assert.equal(instance.storageMetric('backupFreeBytes'), '2 GiB');
  instance.resourceSnapshot.sections.storage.state = 'DISABLED';
  assert.equal(instance.storageMetric('dataFreeBytes'), 'monitoring.unavailable');
  instance.updateResourceSnapshot({ snapshot: null, failed: true });
  assert.equal(instance.resourceSnapshot, null);
});

test('target editors retain version, keep-secret behavior and TLS/timeouts; legacy JMS is read-only', () => {
  const { instance } = fixture('SettingsPage');
  instance.openEditHttpTarget({ id: 'h', version: 3, name: 'HTTP', baseUrl: 'http://localhost', authType: 'BASIC', username: 'test', secretConfigured: true, connectTimeoutSeconds: 5, readTimeoutSeconds: 20, tlsVerificationEnabled: true, enabled: true, defaultConnection: true });
  assert.equal(instance.httpTargetForm.version, 3);
  assert.equal(instance.httpTargetForm.secret, '');
  assert.equal(instance.httpTargetForm.clearSecret, false);
  assert.equal(instance.httpTargetForm.tlsVerificationEnabled, true);
  instance.openEditJmsTarget({ legacy: true });
  assert.equal(instance.showJmsTargetForm, false);
  instance.openEditJmsTarget({ id: 'j', version: 4, name: 'JMS', providerType: 'artemis', serverUrl: 'tcp://localhost', queueName: 'TEST', username: 'test', timeoutSeconds: 15, enabled: true });
  assert.equal(instance.jmsTargetForm.version, 4);
  assert.equal(instance.jmsTargetForm.password, '');
  assert.equal(instance.jmsTargetForm.clearPassword, false);
  assert.equal(instance.jmsTargetForm.timeoutSeconds, 15);
});

test('connection dialogs use shared focus trapping, Escape closing and trigger restoration', () => {
  const { instance, sandbox } = fixture('SettingsPage');
  let focused = 0, restored = 0, trapped = 0;
  const previous = { focus: () => { restored++; } };
  const overlay = {};
  const input = { focus: () => { focused++; } };
  const dialog = { closest: () => overlay, querySelector: () => input };
  sandbox.document = { activeElement: previous, contains: element => element === previous };
  sandbox.makeOverlaySiblingsInert = element => { assert.equal(element, overlay); return ['saved-inert-state']; };
  sandbox.restoreOverlaySiblings = state => { assert.equal(state[0], 'saved-inert-state'); };
  sandbox.trapDialogFocus = (event, element) => { assert.equal(element, dialog); trapped++; };
  instance.$el = { querySelector: () => dialog };
  instance.showJmsTargetForm = true;
  instance.onConnectionDialogChanged('jms', true);
  assert.equal(focused, 1);
  const event = { key: 'Escape', currentTarget: { querySelector: () => dialog }, preventDefault() {}, stopPropagation() {} };
  instance.onConnectionDialogKeydown(event, 'jms');
  assert.equal(trapped, 1);
  assert.equal(instance.showJmsTargetForm, false);
  instance.onConnectionDialogChanged('jms', false);
  assert.equal(restored, 1);
  assert.equal(instance.connectionInertState.length, 0);
});

test('HTTP and JMS save/test/default/delete retain endpoints and cancellation', async () => {
  for (const protocol of ['Http', 'Jms']) {
    const lower = protocol.toLowerCase();
    const { instance, calls, sandbox } = fixture('SettingsPage', {}, async () => ({ ok: true, json: async () => ({ success: true, elapsedMs: 4, status: 200 }) }));
    instance['load' + protocol + 'Targets'] = async () => {};
    instance[lower + 'TargetForm'] = { name: 'test', baseUrl: 'http://localhost', serverUrl: 'tcp://localhost', queueName: 'TEST', timeoutSeconds: '5', connectTimeoutSeconds: '5', readTimeoutSeconds: '20', version: 7 };
    instance['editing' + protocol + 'Target'] = { id: 'test-id' };
    await instance['save' + protocol + 'Target']();
    assert.equal(calls[0][0], '/api/admin/' + lower + '-target-connections/test-id');
    assert.equal(calls[0][1].method, 'PUT');
    assert.equal(JSON.parse(calls[0][1].body).version, 7);
    await instance['test' + protocol + 'Target']({ id: 'test-id' });
    assert.equal(instance[lower + 'TargetTestingId'], null);
    assert.equal(instance[lower + 'TargetTestResults']['test-id'].success, true);
    await instance['makeDefault' + protocol + 'Target']({ id: 'test-id' });
    sandbox.window.confirm = () => false;
    await instance['delete' + protocol + 'Target']({ id: 'test-id' });
    assert.equal(calls.length, 3);
    sandbox.window.confirm = () => true;
    await instance['delete' + protocol + 'Target']({ id: 'test-id' });
    assert.equal(calls[3][1].method, 'DELETE');
  }
});

test('new settings keys and six detail groups exist in both locales', () => {
  for (const locale of ['en', 'zh-TW']) {
    const strings = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/i18n/' + locale + '.json'), 'utf8'));
    for (const key of ['overview', 'monitoring', 'connections', 'data', 'service']) assert.ok(strings.settings.tabs[key]);
    for (const key of ['memory', 'cache', 'jms', 'http', 'database', 'scheduler']) assert.ok(strings.monitoring.details[key]);
    for (const key of ['connectionNameSource', 'connectionAddressDestination', 'connectionDefaultRole', 'lastConnectionTest', 'credentialsConfigured', 'backupVerificationHint', 'manualRefreshHint']) assert.ok(strings.settings[key], locale + ':' + key);
  }
});
