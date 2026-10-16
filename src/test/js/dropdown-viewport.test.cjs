const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const sandbox = {};
vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/components/UiDropdownMenu.js'), 'utf8'), sandbox);
const position = sandbox.dropdownViewportPosition;

test('bottom action menu opens above its trigger and remains fully visible', () => {
  const result = position({ right: 1403, top: 825, bottom: 857 }, { width: 184, height: 83 }, { width: 1440, height: 900 });
  assert.equal(result.top, '738px');
  assert.equal(result.position, 'fixed');
  assert.ok(parseFloat(result.top) + 83 < 900);
});

test('top and narrow action menus are clamped without removing items', () => {
  const result = position({ right: 180, top: 10, bottom: 42 }, { width: 184, height: 83 }, { width: 390, height: 844 });
  assert.equal(result.left, '8px');
  assert.equal(result.top, '46px');
  assert.equal(result.overflowY, 'auto');
  const tall = position({ right: 380, top: 400, bottom: 432 }, { width: 184, height: 900 }, { width: 390, height: 844 });
  assert.equal(tall.top, '8px');
  assert.equal(tall.maxHeight, '828px');
});
