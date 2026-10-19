/**
 * TrafficChart - Pure calculation of traffic profile (without touching DOM): ticks, stacked bars, time labels, grids with no data.
 * The time is always the local time string of the server (YYYY-MM-DDTHH:mm:ss). It only performs string cutting without going through browser time zone conversion.
 */
const TrafficChart = (() => {
    /** Stacking order (bottom up). */
    const KINDS = ['mocked', 'forwarded', 'unmatched', 'forwardFailed'];
    const RANGES = ['1h', '24h', '7d'];
    const MODES = ['compact', 'expanded', 'collapsed'];

    const clock = time => (time || '').substring(11, 16);
    const day = time => (time || '').substring(5, 10).replace('-', '/');

    const total = bucket => KINDS.reduce((sum, kind) => sum + (bucket[kind] || 0), 0);

    /** The upper limit of the scale is 1, 2, 5 × 10ⁿ, with a minimum of 4; the upper limit and scale are returned. */
    const scale = (max, steps = 4) => {
        const target = Math.max(4, max);
        const rough = target / steps;
        const power = Math.pow(10, Math.floor(Math.log10(rough)));
        const step = [1, 2, 5, 10].map(m => m * power).find(s => s * steps >= target);
        return { max: step * steps, ticks: Array.from({ length: steps + 1 }, (_, i) => i * step) };
    };

    /** The end time of the i grid (the beginning of the next grid, the last grid is to). */
    const bucketEnd = (traffic, i) => i + 1 < traffic.buckets.length ? traffic.buckets[i + 1].start : traffic.to;

    /** Display the time interval, such as "14:00 – 14:30"; cross-day or 7-day range with date. */
    const rangeText = (start, end, withDay) => {
        const sameDay = (start || '').substring(0, 10) === (end || '').substring(0, 10)
            || clock(end) === '00:00';
        const from = (withDay ? day(start) + ' ' : '') + clock(start);
        const to = (withDay && !sameDay ? day(end) + ' ' : '') + clock(end);
        return from + ' – ' + to;
    };

    /** The grid to be marked on the x axis: every 10 minutes for 1 hour, every 3 hours for 24 hours, and 0 o'clock every day for 7 days. */
    const isLabelled = (traffic, bucket) => {
        const [hh, mm] = clock(bucket.start).split(':').map(Number);
        if (traffic.range === '1h') return mm % 10 === 0;
        if (traffic.range === '24h') return mm === 0 && hh % 3 === 0;
        return hh === 0 && mm === 0;
    };
    const axisLabel = (traffic, bucket) => traffic.range === '7d' ? day(bucket.start) : clock(bucket.start);

    /**
     * Is there no data in the grid: When the maximum number of records has been reached and the earlier record is deleted, the entire grid earlier than the earliest record will have no data (not no traffic).
     */
    const isMissing = (traffic, i) => !!(traffic.truncated && traffic.earliestRecord
        && bucketEnd(traffic, i) <= traffic.earliestRecord);

    /** The height of each segment of the bar (as a percentage of the image height, from bottom to top). */
    const segments = (bucket, max) => KINDS
        .filter(kind => bucket[kind] > 0)
        .map(kind => ({ kind, value: bucket[kind], percent: max > 0 ? bucket[kind] / max * 100 : 0 }));

    const matchRate = totals => totals && totals.requests > 0
        ? Math.round((totals.requests - totals.unmatched) / totals.requests * 1000) / 10 : null;

    /** The horizontal position of the prompt box: does not exceed the chart when it is close to the left and right edges. */
    const tipAlign = (i, count) => i < count * 0.2 ? 'start' : i >= count * 0.8 ? 'end' : 'center';

    const normalize = (value, allowed, fallback) => allowed.includes(value) ? value : fallback;

    return { KINDS, RANGES, MODES, clock, day, total, scale, bucketEnd, rangeText, isLabelled, axisLabel,
        isMissing, segments, matchRate, tipAlign, normalize };
})();
