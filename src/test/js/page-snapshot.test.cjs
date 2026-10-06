const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const staticRoot = path.resolve(__dirname, '../../main/resources/static');
const script = fs.readFileSync(path.join(staticRoot, 'ui-snapshot.js'), 'utf8');
const HREF = 'http://localhost:8080/#/rules';

// Runs ui-snapshot.js the way the browser does before first paint, and reports what the shell shows.
function boot({ navigationType = 'reload', snapshot, href = HREF } = {}) {
  const store = new Map();
  if (snapshot !== undefined) store.set('echo.pageSnapshot', JSON.stringify(snapshot));
  const shell = { innerHTML: '<div class="boot-shell__main"></div>', classes: new Set(['boot-shell']) };
  shell.classList = { add: name => shell.classes.add(name) };
  vm.runInNewContext(script, {
    sessionStorage: {
      getItem: key => store.has(key) ? store.get(key) : null,
      removeItem: key => store.delete(key),
    },
    performance: { getEntriesByType: () => [{ type: navigationType }] },
    location: { href },
    document: { getElementById: id => id === 'boot-shell' ? shell : null },
    Date, JSON,
  });
  return { shell, stored: store.has('echo.pageSnapshot') };
}

const fresh = (extra = {}) => ({ href: HREF, savedAt: Date.now(), html: '<div class="layout">rules</div>', ...extra });

test('a reload of the same page paints the snapshot first', () => {
  const { shell, stored } = boot({ snapshot: fresh() });
  assert.equal(shell.innerHTML, '<div class="layout">rules</div>');
  assert.ok(shell.classes.has('boot-shell--snapshot'));
  assert.equal(stored, false, 'a snapshot is used once');
});

test('back and forward to the same page also use it', () => {
  assert.ok(boot({ navigationType: 'back_forward', snapshot: fresh() }).shell.classes.has('boot-shell--snapshot'));
});

test('a fresh navigation, another page or an old snapshot keeps the plain shell', () => {
  for (const options of [
    { navigationType: 'navigate', snapshot: fresh() },
    { snapshot: fresh({ href: 'http://localhost:8080/#/audit' }) },
    { snapshot: fresh({ savedAt: Date.now() - 11 * 60 * 1000 }) },
  ]) {
    const { shell, stored } = boot(options);
    assert.equal(shell.classes.has('boot-shell--snapshot'), false);
    assert.equal(stored, false, 'unusable snapshots are discarded too');
  }
});

test('an unreadable snapshot never breaks startup', () => {
  const store = new Map([['echo.pageSnapshot', '{not json']]);
  assert.doesNotThrow(() => vm.runInNewContext(script, {
    sessionStorage: { getItem: key => store.get(key) ?? null, removeItem: key => store.delete(key) },
    performance: { getEntriesByType: () => [{ type: 'reload' }] },
    location: { href: HREF }, document: { getElementById: () => null }, Date, JSON,
  }));
});
