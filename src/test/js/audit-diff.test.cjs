const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/static');
const context = {};
vm.runInNewContext(fs.readFileSync(path.join(root, 'audit-diff.js'), 'utf8') + '\nthis.AuditDiff = AuditDiff;', context);
const { AuditDiff } = context;
const plain = r => JSON.parse(JSON.stringify(r));

test('only changed lines and their neighbours are shown, numbered on both sides', () => {
  const before = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j'].join('\n');
  const after = ['a', 'b', 'c', 'd', 'E', 'f', 'g', 'h', 'i', 'j', 'k'].join('\n');
  const diff = plain(AuditDiff.lines(before, after, { context: 1 }));

  assert.equal(diff.tooLarge, false);
  assert.equal(diff.added, 2);
  assert.equal(diff.removed, 1);
  assert.equal(diff.hunks.length, 2);
  assert.deepEqual(diff.hunks[0].lines.map(l => l.type + l.text), [' d', '-e', '+E', ' f']);
  assert.deepEqual(diff.hunks[0].lines[1], { type: '-', text: 'e', oldNo: 5, newNo: null });
  assert.deepEqual(diff.hunks[0].lines[2], { type: '+', text: 'E', oldNo: null, newNo: 5 });
  assert.deepEqual(diff.hunks[1].lines.map(l => l.type + l.text), [' j', '+k']);
});

test('full view keeps every line in one block; empty or identical text has no changes', () => {
  const full = plain(AuditDiff.lines('x\ny', 'x\nz', { context: Infinity }));
  assert.equal(full.hunks.length, 1);
  assert.deepEqual(full.hunks[0].lines.map(l => l.type + l.text), [' x', '-y', '+z']);

  assert.deepEqual(plain(AuditDiff.lines('same', 'same')).hunks, []);
  const created = plain(AuditDiff.lines(null, 'one\ntwo'));
  assert.equal(created.added, 2);
  assert.equal(created.removed, 0);
});

test('very large text or too many differences fall back to showing both versions', () => {
  assert.equal(AuditDiff.lines('x'.repeat(AuditDiff.MAX_CHARS + 1), 'y').tooLarge, true);
  const a = Array.from({ length: 50 }, (_, i) => 'a' + i).join('\n');
  const b = Array.from({ length: 50 }, (_, i) => 'b' + i).join('\n');
  assert.equal(AuditDiff.lines(a, b, { maxEdits: 20 }).tooLarge, true);
  assert.equal(AuditDiff.lines(a, b).tooLarge, false);
});
