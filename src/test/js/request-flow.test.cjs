const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/static');

function load() {
  const context = {};
  vm.runInNewContext(fs.readFileSync(path.join(root, 'request-flow.js'), 'utf8') + '\nthis.RequestFlow = RequestFlow;', context);
  return context.RequestFlow;
}
const plain = value => JSON.parse(JSON.stringify(value));

const base = { protocol: 'HTTP', method: 'POST', endpoint: '/orders', clientIp: '10.0.0.1', targetHost: 'api.shop',
  responseTimeMs: 12, matchTimeMs: 2, matched: true, ruleId: 'a1b2c3d4e5', forwarded: false, responseStatus: 200 };

test('1. mock response: matched by conditions, delay and scenario transition', () => {
  const RequestFlow = load();
  const flow = RequestFlow.build({ ...base, scenarioName: 'checkout', scenarioFromState: 'Started', scenarioToState: 'Paid' },
    { candidateCount: 5, matchedByFallback: false, delayMs: 200 },
    [{ ruleId: 'a1b2c3d4e5', reason: 'match', description: '一般訂單' }]);
  assert.equal(flow.route, 'mock');
  assert.equal(flow.match.candidateCount, 5);
  assert.equal(flow.match.description, '一般訂單');
  assert.equal(flow.match.byFallback, false);
  assert.equal(flow.mock.tone, 'ok');
  assert.equal(flow.mock.delayMs, 200);
  assert.deepEqual(plain(flow.mock.scenario), { name: 'checkout', from: 'Started', to: 'Paid' });
  assert.equal(flow.forward.tone, 'off');
  assert.equal(flow.reply.tone, 'ok');
  assert.deepEqual(plain(flow.timing), { matchMs: 2, otherMs: 10, otherKind: 'response', otherTone: 'neutral', delayMs: 200, totalMs: 212, sse: false });
});

test('B. a default rule without conditions is reported as such', () => {
  const RequestFlow = load();
  const flow = RequestFlow.build(base, { candidateCount: 2, matchedByFallback: true }, null);
  assert.equal(flow.match.byFallback, true);
  assert.equal(flow.match.hasTrace, false);
});

test('2. SSE stream counts events; the stream time itself is not part of the total', () => {
  const RequestFlow = load();
  const flow = RequestFlow.build({ ...base, method: 'GET' }, { candidateCount: 1, sseEventCount: 4, delayMs: 0 }, []);
  assert.equal(flow.route, 'mock');
  assert.equal(flow.mock.sse, true);
  assert.equal(flow.mock.sseEventCount, 4);
  assert.equal(flow.mock.delayMs, null);
  assert.equal(flow.timing.sse, true);
});

test('3. fault injection: connection reset has no status; empty response keeps it', () => {
  const RequestFlow = load();
  const reset = RequestFlow.build({ ...base, faultType: 'CONNECTION_RESET', responseStatus: null }, {}, []);
  assert.equal(reset.mock.fault, 'CONNECTION_RESET');
  assert.equal(reset.mock.tone, 'warn');
  assert.equal(reset.reply.connectionReset, true);
  assert.equal(reset.reply.tone, 'warn');
  const empty = RequestFlow.build({ ...base, faultType: 'EMPTY_RESPONSE' }, {}, []);
  assert.equal(empty.reply.emptyResponse, true);
  assert.equal(empty.reply.status, 200);
  const none = RequestFlow.build({ ...base, faultType: 'NONE' }, {}, []);
  assert.equal(none.mock.fault, null);
});

test('4. a matched rule that forwards takes the forward branch', () => {
  const RequestFlow = load();
  const flow = RequestFlow.build({ ...base, forwarded: true, proxyStatus: 201, responseStatus: 201, forwardTarget: 'SIT | https://sit.example' }, {}, []);
  assert.equal(flow.route, 'forward');
  assert.equal(flow.forward.byRule, true);
  assert.equal(flow.forward.outcome, 'ok');
  assert.equal(flow.forward.target, 'SIT | https://sit.example');
  assert.equal(flow.mock.tone, 'off');
  assert.equal(flow.timing.otherKind, 'forward');
});

test('5/6. unmatched forwarding: success, error status and failure', () => {
  const RequestFlow = load();
  const unmatched = { ...base, matched: false, ruleId: null, forwarded: true };
  const ok = RequestFlow.build({ ...unmatched, proxyStatus: 200 }, { candidateCount: 0 }, null);
  assert.equal(ok.forward.byRule, false);
  assert.equal(ok.forward.target, 'api.shop');
  assert.equal(ok.forward.tone, 'ok');
  assert.equal(ok.match.tone, 'warn');
  assert.equal(ok.match.candidateCount, 0);
  const errorStatus = RequestFlow.build({ ...unmatched, proxyStatus: 500, responseStatus: 500 }, {}, null);
  assert.equal(errorStatus.forward.outcome, 'errorStatus');
  assert.equal(errorStatus.forward.tone, 'warn');
  assert.equal(errorStatus.reply.tone, 'bad');
  const failed = RequestFlow.build({ ...unmatched, proxyError: 'Read timed out', responseStatus: 502, responseTimeMs: 3010, matchTimeMs: 1 }, {}, null);
  assert.equal(failed.forward.outcome, 'failed');
  assert.equal(failed.forward.tone, 'bad');
  assert.equal(failed.reply.tone, 'bad');
  assert.equal(failed.timing.otherTone, 'bad');
  assert.equal(failed.timing.totalMs, 3010);
});

test('7. no rule and nothing to forward to', () => {
  const RequestFlow = load();
  const flow = RequestFlow.build({ ...base, matched: false, ruleId: null, responseStatus: 404 },
    { candidateCount: 3 },
    [{ ruleId: 'r1', reason: 'near-miss', score: '2/3' }]);
  assert.equal(flow.route, 'notFound');
  assert.deepEqual(plain(flow.match.nearMiss), { ruleId: 'r1', score: '2/3' });
  assert.equal(flow.reply.tone, 'warn');
  assert.equal(flow.timing.delayMs, 0);
});

test('rules skipped because of the scenario state are listed with their states', () => {
  const RequestFlow = load();
  const skipped = JSON.stringify([
    { ruleId: 'gated', reason: 'scenario_state_mismatch', description: '付款後', detail: 'scenario: checkout (required: Paid, current: Started)' },
    { ruleId: 'odd', reason: 'scenario_state_mismatch', detail: 'something else' },
  ]);
  const flow = RequestFlow.build({ ...base, matched: false, ruleId: null, responseStatus: 404 }, { scenarioSkipped: skipped }, null);
  assert.deepEqual(plain(flow.match.skipped), [
    { ruleId: 'gated', description: '付款後', scenario: 'checkout', required: 'Paid', current: 'Started' },
    { ruleId: 'odd', description: '', detail: 'something else' },
  ]);
  assert.deepEqual(plain(RequestFlow.build(base, { scenarioSkipped: 'not json' }, null).match.skipped), []);
});

test('JMS: no HTTP status; reply goes to the reply queue', () => {
  const RequestFlow = load();
  const flow = RequestFlow.build({ ...base, protocol: 'JMS', method: 'JMS', endpoint: 'ORDER', responseStatus: 200 }, {}, null);
  assert.equal(flow.received.jms, true);
  assert.equal(flow.reply.jms, true);
  assert.equal(flow.reply.status, null);
});

test('old records without details still build; the flow says details are pending', () => {
  const RequestFlow = load();
  const flow = RequestFlow.build(base, null, null);
  assert.equal(flow.complete, false);
  assert.equal(flow.match.candidateCount, null);
  assert.equal(flow.timing.delayMs, 0);
});

test('timing segments: proportional widths, a 2% minimum and 100% in total', () => {
  const RequestFlow = load();
  const segments = RequestFlow.timingSegments({ matchMs: 1, otherMs: 3009, otherKind: 'forward', otherTone: 'bad', delayMs: 0 });
  assert.deepEqual(plain(segments.map(s => s.key)), ['match', 'forward']);
  assert.ok(segments[0].width >= 1.9);
  assert.equal(segments[1].tone, 'bad');
  assert.ok(Math.abs(segments.reduce((sum, s) => sum + s.width, 0) - 100) < 0.5);
  assert.deepEqual(plain(RequestFlow.timingSegments({ matchMs: 0, otherMs: 0, otherKind: 'response', delayMs: 0 })), []);
  const withDelay = RequestFlow.timingSegments({ matchMs: 2, otherMs: 10, otherKind: 'response', delayMs: 200 });
  assert.deepEqual(plain(withDelay.map(s => s.key)), ['match', 'response', 'delay']);
});
