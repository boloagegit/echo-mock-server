const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/ui-accent.js'), 'utf8');

function load({ stored = null, storageThrows = false } = {}) {
  const attributes = {};
  const saved = {};
  const storage = {
    getItem(key) {
      if (storageThrows) { throw new Error('blocked'); }
      return key in saved ? saved[key] : stored;
    },
    setItem(key, value) {
      if (storageThrows) { throw new Error('blocked'); }
      saved[key] = value;
    },
  };
  const window = {};
  const context = {
    window,
    localStorage: storage,
    document: { documentElement: { setAttribute(name, value) { attributes[name] = value; } } },
  };
  vm.runInNewContext(source, context);
  return { attributes, saved, accent: window.EchoAccent };
}

test('defaults to teal before the server answers', () => {
  const { attributes } = load();
  assert.equal(attributes['data-accent'], 'teal');
});

test('reuses the cached accent so the page does not flash the default color', () => {
  const { attributes } = load({ stored: 'blue' });
  assert.equal(attributes['data-accent'], 'blue');
});

test('ignores an unknown cached accent', () => {
  const { attributes } = load({ stored: 'red' });
  assert.equal(attributes['data-accent'], 'teal');
});

test('applies and caches the accent reported by the server', () => {
  const { attributes, saved, accent } = load();
  assert.equal(accent.apply('blue'), 'blue');
  assert.equal(attributes['data-accent'], 'blue');
  assert.equal(saved['echo.uiAccent'], 'blue');
});

test('falls back to teal for missing or unexpected server values', () => {
  const { attributes, accent } = load({ stored: 'blue' });
  assert.equal(accent.apply(undefined), 'teal');
  assert.equal(attributes['data-accent'], 'teal');
  assert.equal(accent.apply('blue;color:red'), 'teal');
});

test('keeps working when browser storage is blocked', () => {
  const { attributes, accent } = load({ storageThrows: true });
  assert.equal(attributes['data-accent'], 'teal');
  assert.equal(accent.apply('blue'), 'blue');
  assert.equal(attributes['data-accent'], 'blue');
});
