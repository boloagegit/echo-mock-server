const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/static');

function load() {
  const context = {};
  vm.runInNewContext(fs.readFileSync(path.join(root, 'traffic-chart.js'), 'utf8') + '\nthis.TrafficChart = TrafficChart;', context);
  return context.TrafficChart;
}
const plain = value => JSON.parse(JSON.stringify(value));
const T = load();

const bucket = (start, mocked = 0, forwarded = 0, unmatched = 0, forwardFailed = 0) =>
  ({ start, mocked, forwarded, unmatched, forwardFailed });
const day = {
  range: '24h', to: '2026-10-12T15:00:00', truncated: false, earliestRecord: '2026-10-01T00:00:00',
  buckets: [bucket('2026-10-12T14:00:00', 3, 1), bucket('2026-10-12T14:30:00', 0, 0, 2, 1)],
};

test('scale rounds up to 1, 2 or 5 steps and never collapses to zero', () => {
  assert.deepEqual(plain(T.scale(0)), { max: 4, ticks: [0, 1, 2, 3, 4] });
  assert.deepEqual(plain(T.scale(62)), { max: 80, ticks: [0, 20, 40, 60, 80] });
  assert.equal(T.scale(1234).max, 2000);
  assert.equal(T.scale(80).max, 80);
});

test('bucket ends, time text and labels come straight from the server strings', () => {
  assert.equal(T.bucketEnd(day, 0), '2026-10-12T14:30:00');
  assert.equal(T.bucketEnd(day, 1), '2026-10-12T15:00:00');
  assert.equal(T.rangeText('2026-10-12T14:00:00', '2026-10-12T14:30:00', false), '14:00 – 14:30');
  assert.equal(T.rangeText('2026-10-12T18:00:00', '2026-10-13T00:00:00', true), '10/12 18:00 – 00:00');
  assert.equal(T.rangeText('2026-10-12T23:58:00', '2026-10-13T00:00:00', false), '23:58 – 00:00');
  assert.equal(T.isLabelled(day, bucket('2026-10-12T15:00:00')), true);
  assert.equal(T.isLabelled(day, bucket('2026-10-12T14:00:00')), false);
  assert.equal(T.isLabelled({ range: '1h' }, bucket('2026-10-12T14:20:00')), true);
  assert.equal(T.isLabelled({ range: '7d' }, bucket('2026-10-12T00:00:00')), true);
  assert.equal(T.axisLabel({ range: '7d' }, bucket('2026-10-12T00:00:00')), '10/12');
});

test('buckets before the earliest retained record are missing only when records were removed', () => {
  const truncated = { ...day, truncated: true, earliestRecord: '2026-10-12T14:40:00' };
  assert.equal(T.isMissing(truncated, 0), true);
  assert.equal(T.isMissing(truncated, 1), false);
  assert.equal(T.isMissing(day, 0), false);
  assert.equal(T.isMissing({ ...truncated, earliestRecord: null }, 0), false);
});

test('segments stack in a fixed order and skip empty kinds', () => {
  assert.deepEqual(plain(T.segments(day.buckets[1], 4)).map(s => [s.kind, s.percent]),
    [['unmatched', 50], ['forwardFailed', 25]]);
  assert.equal(T.total(day.buckets[0]), 4);
});

test('match rate, tooltip alignment and stored preferences', () => {
  assert.equal(T.matchRate({ requests: 1284, unmatched: 98 }), 92.4);
  assert.equal(T.matchRate({ requests: 0, unmatched: 0 }), null);
  assert.equal(T.tipAlign(0, 48), 'start');
  assert.equal(T.tipAlign(24, 48), 'center');
  assert.equal(T.tipAlign(47, 48), 'end');
  assert.equal(T.normalize('7d', T.RANGES, '24h'), '7d');
  assert.equal(T.normalize('30d', T.RANGES, '24h'), '24h');
  assert.equal(T.normalize(null, T.MODES, 'compact'), 'compact');
});
