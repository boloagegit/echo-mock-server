const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/static');
const source = fs.readFileSync(path.join(root, 'utils.js'), 'utf8');
function parserFixture() {
  const context = { window: {}, document: {}, Vue: {}, TextDecoder, setTimeout, clearTimeout };
  vm.runInNewContext(source + '\nthis.parser = createSseParser;', context);
  const events = [];
  return { parser: context.parser(e => events.push(JSON.parse(JSON.stringify(e)))), events };
}
test('SSE handles every byte split, CRLF/LF/CR, empty leading data and UTF-8 Chinese/emoji', () => {
  const wire = ':comment\r\nid:42\r\nevent:update\r\ndata:\r\ndata: 中文😀\r\ndata:\r\n\r\ndata:two\n\ndata:three\r\r';
  const bytes = new TextEncoder().encode(wire);
  for (let split = 0; split <= bytes.length; split++) {
    const { parser, events } = parserFixture();
    const decoder = new TextDecoder();
    parser.push(decoder.decode(bytes.slice(0, split), { stream: true }));
    parser.push(decoder.decode(bytes.slice(split), { stream: true }));
    parser.push(decoder.decode()); parser.finish();
    assert.deepEqual(events, [{ event: 'update', data: '\n中文😀\n', id: '42' },
      { event: '', data: 'two', id: '42' }, { event: '', data: 'three', id: '42' }]);
  }
  const { parser, events } = parserFixture(), decoder = new TextDecoder();
  for (const byte of bytes) parser.push(decoder.decode(Uint8Array.of(byte), { stream: true }));
  parser.push(decoder.decode()); parser.finish();
  assert.equal(events[0].data, '\n中文😀\n');
});
test('empty data dispatches, id-only and event-only do not; NUL id ignored and EOF discards partial event', () => {
  const { parser, events } = parserFixture();
  parser.push('id:one\n\nevent:ignored\n\ndata\n\nid:a\0b\ndata:ok\n\ndata:unfinished\n');
  parser.finish();
  assert.deepEqual(events, [{ event: '', data: '', id: 'one' }, { event: '', data: 'ok', id: 'one' }]);
});
function formFixture(fetch) {
  const context = { window: {}, document: {}, TextDecoder, AbortController, setTimeout, clearTimeout, fetch,
    Vue: { ref: value => ({ value }), computed: fn => ({ get value() { return fn(); } }), watch: () => {} } };
  vm.runInNewContext(source + '\n' + fs.readFileSync(path.join(root, 'composables/useRuleForm.js'), 'utf8') + '\nthis.create = useRuleForm;', context);
  const form = context.create({ t: x => x });
  form.editing.value = { id: 'synthetic' };
  form.form.value = { protocol: 'HTTP', sseEnabled: true, method: 'POST', matchKey: '/ai', targetHost: 'synthetic' };
  form.testParams.value = { query: 'q=test', headersStr: 'X-Url:urn:test', body: '{"prompt":"中文😀"}' };
  return form;
}
test('SSE test uses configured method/body and releases the reader', async () => {
  let options, url, released = false, reads = 0;
  const bytes = new TextEncoder().encode('data:中文😀\n\n');
  const form = formFixture(async (u, o) => { url = u; options = o; return { status: 200,
    headers: new Headers({ 'Content-Type': 'text/event-stream;charset=UTF-8' }), body: { getReader: () => ({
      read: async () => reads++ === 0 ? { value: bytes, done: false } : { done: true }, releaseLock: () => { released = true; }
    }) } }; });
  await form.runTest();
  assert.equal(url, '/mock/ai?q=test'); assert.equal(options.method, 'POST');
  assert.equal(options.body, '{"prompt":"中文😀"}'); assert.equal(options.headers['Content-Type'], 'application/json');
  assert.equal(options.headers['X-Url'], 'urn:test'); assert.equal(form.testSseEvents.value[0].data, '中文😀');
  assert.equal(released, true); assert.equal(form.testLoading.value, false);
});
test('non-200 and normal JSON responses are displayed as HTTP results', async () => {
  for (const status of [204, 429, 200]) {
    const form = formFixture(async () => ({ status, headers: new Headers({ 'Content-Type': 'application/json' }),
      body: {}, text: async () => 'synthetic error' }));
    await form.runTest(); assert.equal(form.testResult.value.status, status);
    assert.equal(form.testSseEvents.value.length, 0); assert.equal(form.testLoading.value, false);
  }
});
test('aborting an old SSE test cannot clear a newer test or add its events', async () => {
  let resolveOld, releaseOld = false;
  let calls = 0;
  const form = formFixture(async () => ({ status: 200, headers: new Headers({ 'Content-Type': 'text/event-stream' }),
    body: { getReader: () => ++calls === 1 ? {
      read: () => new Promise(resolve => { resolveOld = resolve; }), releaseLock: () => { releaseOld = true; }
    } : { read: () => new Promise(() => {}), releaseLock: () => {} } } }));
  const old = form.runTest(); await Promise.resolve(); await Promise.resolve();
  form.runTest(); await Promise.resolve(); await Promise.resolve();
  resolveOld({ done: true }); await old;
  assert.equal(form.testLoading.value, true); assert.equal(releaseOld, true);
  form.stopSseTest(); assert.equal(form.testLoading.value, false);
});
