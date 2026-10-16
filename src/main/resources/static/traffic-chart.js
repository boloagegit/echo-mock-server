/**
 * TrafficChart - 流量概況的純計算（不碰 DOM）：刻度、堆疊長條、時間標籤、沒有資料的格子。
 * 時間一律是伺服器的本地時間字串（YYYY-MM-DDTHH:mm:ss），只做字串切割，不經過瀏覽器時區換算。
 */
const TrafficChart = (() => {
    /** 堆疊順序（由下而上）。 */
    const KINDS = ['mocked', 'forwarded', 'unmatched', 'forwardFailed'];
    const RANGES = ['1h', '24h', '7d'];
    const MODES = ['compact', 'expanded', 'collapsed'];

    const clock = time => (time || '').substring(11, 16);
    const day = time => (time || '').substring(5, 10).replace('-', '/');

    const total = bucket => KINDS.reduce((sum, kind) => sum + (bucket[kind] || 0), 0);

    /** 刻度上限取 1、2、5 × 10ⁿ，最少 4；回傳上限與刻度。 */
    const scale = (max, steps = 4) => {
        const target = Math.max(4, max);
        const rough = target / steps;
        const power = Math.pow(10, Math.floor(Math.log10(rough)));
        const step = [1, 2, 5, 10].map(m => m * power).find(s => s * steps >= target);
        return { max: step * steps, ticks: Array.from({ length: steps + 1 }, (_, i) => i * step) };
    };

    /** 第 i 格的結束時間（下一格的開始，最後一格為 to）。 */
    const bucketEnd = (traffic, i) => i + 1 < traffic.buckets.length ? traffic.buckets[i + 1].start : traffic.to;

    /** 顯示用的時間區間，例如「14:00 – 14:30」；跨日或 7 天範圍帶日期。 */
    const rangeText = (start, end, withDay) => {
        const sameDay = (start || '').substring(0, 10) === (end || '').substring(0, 10)
            || clock(end) === '00:00';
        const from = (withDay ? day(start) + ' ' : '') + clock(start);
        const to = (withDay && !sameDay ? day(end) + ' ' : '') + clock(end);
        return from + ' – ' + to;
    };

    /** x 軸要標示的格子：1 小時每 10 分鐘、24 小時每 3 小時、7 天每天 0 點。 */
    const isLabelled = (traffic, bucket) => {
        const [hh, mm] = clock(bucket.start).split(':').map(Number);
        if (traffic.range === '1h') return mm % 10 === 0;
        if (traffic.range === '24h') return mm === 0 && hh % 3 === 0;
        return hh === 0 && mm === 0;
    };
    const axisLabel = (traffic, bucket) => traffic.range === '7d' ? day(bucket.start) : clock(bucket.start);

    /**
     * 格子是否沒有資料：已達筆數上限、更早的紀錄被刪除時，整格都早於最早紀錄的格子沒有資料（不是沒有流量）。
     */
    const isMissing = (traffic, i) => !!(traffic.truncated && traffic.earliestRecord
        && bucketEnd(traffic, i) <= traffic.earliestRecord);

    /** 長條各段的高度（佔圖高的百分比，由下而上）。 */
    const segments = (bucket, max) => KINDS
        .filter(kind => bucket[kind] > 0)
        .map(kind => ({ kind, value: bucket[kind], percent: max > 0 ? bucket[kind] / max * 100 : 0 }));

    const matchRate = totals => totals && totals.requests > 0
        ? Math.round((totals.requests - totals.unmatched) / totals.requests * 1000) / 10 : null;

    /** 提示框的水平位置：靠左右邊緣時不超出圖表。 */
    const tipAlign = (i, count) => i < count * 0.2 ? 'start' : i >= count * 0.8 ? 'end' : 'center';

    const normalize = (value, allowed, fallback) => allowed.includes(value) ? value : fallback;

    return { KINDS, RANGES, MODES, clock, day, total, scale, bucketEnd, rangeText, isLabelled, axisLabel,
        isMissing, segments, matchRate, tipAlign, normalize };
})();
