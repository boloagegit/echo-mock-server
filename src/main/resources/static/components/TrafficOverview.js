/**
 * TrafficOverview - Traffic overview at the top of the request record page: simplified (default), expanded, collapsed, choose to save in the browser.
 * No query when collapsing; data comes from GET/api/admin/logs/traffic (server-side statistics and short-term sharing, see RequestLogTrafficService).
 * Click on a long bar → emit select-time {from, to}; click "View these requests" → emit show-unmatched {protocol, method, endpoint, from, to}.
 */
const TRAFFIC_MODE_KEY = 'echo.traffic.mode';
const TRAFFIC_RANGE_KEY = 'echo.traffic.range';

const readTrafficPref = (key, allowed, fallback) => {
  try { return TrafficChart.normalize(localStorage.getItem(key), allowed, fallback); } catch { return fallback; }
};
const writeTrafficPref = (key, value) => {
  try { localStorage.setItem(key, value); } catch { /* If it cannot be saved, it will only affect the default value when it is opened next time. */ }
};

const TrafficOverview = {
  props: {
    httpLabel: { type: String, default: 'HTTP' },
    jmsLabel: { type: String, default: 'JMS' },
    // Time filtering of the current list; mark that cell when it happens to be a certain cell
    selectedFrom: { type: String, default: '' },
    selectedTo: { type: String, default: '' },
  },
  emits: ['select-time', 'show-unmatched'],
  inject: ['t'],
  data() {
    return {
      mode: readTrafficPref(TRAFFIC_MODE_KEY, TrafficChart.MODES, 'compact'),
      range: readTrafficPref(TRAFFIC_RANGE_KEY, TrafficChart.RANGES, '24h'),
      traffic: null,
      loading: false,
      failed: false,
      hover: -1,
      focusIndex: -1,
    };
  },
  computed: {
    rangeOptions() {
      return TrafficChart.RANGES.map(value => ({ value, label: this.t('stats.traffic.range_' + value) }));
    },
    buckets() { return this.traffic?.buckets || []; },
    axis() { return TrafficChart.scale(Math.max(0, ...this.buckets.map(TrafficChart.total)), this.expanded ? 4 : 2); },
    expanded() { return this.mode === 'expanded'; },
    totals() { return this.traffic?.totals || null; },
    matchRate() { return TrafficChart.matchRate(this.totals); },
    responseTime() { return this.traffic?.responseTime || {}; },
    topUnmatched() { return this.traffic?.topUnmatched || []; },
    bars() {
      return this.buckets.map((bucket, i) => ({
        i,
        bucket,
        total: TrafficChart.total(bucket),
        missing: TrafficChart.isMissing(this.traffic, i),
        segments: TrafficChart.segments(bucket, this.axis.max),
        selected: !!this.selectedFrom && this.selectedFrom === bucket.start
          && this.selectedTo === TrafficChart.bucketEnd(this.traffic, i),
        label: TrafficChart.isLabelled(this.traffic, bucket) ? TrafficChart.axisLabel(this.traffic, bucket) : '',
      }));
    },
    tip() {
      const bar = this.bars[this.hover];
      if (!bar) { return null; }
      return { ...bar, time: this.timeText(bar.i), align: TrafficChart.tipAlign(bar.i, this.bars.length),
        left: (bar.i + 0.5) / this.bars.length * 100 };
    },
    bucketText() {
      const minutes = this.traffic?.bucketMinutes;
      if (!minutes) { return ''; }
      return minutes % 60 === 0 ? this.t('stats.traffic.bucketHours', { n: minutes / 60 })
        : this.t('stats.traffic.bucketMinutes', { n: minutes });
    },
    truncatedNote() {
      const tr = this.traffic;
      if (!tr || !tr.truncated || !tr.earliestRecord || tr.earliestRecord <= tr.from) { return ''; }
      return this.t('stats.traffic.truncated', { count: tr.maxRecords.toLocaleString(), time: fmtTime(tr.earliestRecord) });
    },
  },
  watch: {
    range(value) { writeTrafficPref(TRAFFIC_RANGE_KEY, value); this.load(); },
    mode(value, previous) {
      writeTrafficPref(TRAFFIC_MODE_KEY, value);
      if (previous === 'collapsed' && !this.traffic) { this.load(); }
    },
  },
  mounted() { this.load(); },
  beforeUnmount() { if (this.abort) { this.abort.abort(); } },
  methods: {
    /** Refresh (the list's refresh button will also be called). No query is performed when collapsing. */
    async load() {
      if (this.mode === 'collapsed') { return; }
      if (this.abort) { this.abort.abort(); }
      const abort = new AbortController();
      this.abort = abort;
      this.loading = true;
      this.failed = false;
      try {
        const r = await apiCall('/api/admin/logs/traffic?range=' + encodeURIComponent(this.range),
          { signal: abort.signal }, { silent: true });
        if (abort.signal.aborted) { return; }
        if (r && r.ok) {
          this.traffic = await r.json();
          if (this.hover >= this.buckets.length) { this.hover = -1; }
        } else {
          this.failed = true;
        }
      } catch {
        if (!abort.signal.aborted) { this.failed = true; }
      } finally {
        if (this.abort === abort) { this.loading = false; this.abort = null; }
      }
    },
    setMode(mode) { this.mode = mode; this.hover = -1; },
    timeText(i) {
      return TrafficChart.rangeText(this.buckets[i].start, TrafficChart.bucketEnd(this.traffic, i), this.range === '7d');
    },
    barLabel(bar) {
      if (bar.missing) { return this.timeText(bar.i) + ' · ' + this.t('stats.traffic.missing'); }
      return this.t('stats.traffic.barLabel', { time: this.timeText(bar.i), total: bar.total,
        mocked: bar.bucket.mocked, forwarded: bar.bucket.forwarded, unmatched: bar.bucket.unmatched,
        failed: bar.bucket.forwardFailed });
    },
    select(bar) {
      if (bar.missing) { return; }
      this.$emit('select-time', { from: bar.bucket.start, to: TrafficChart.bucketEnd(this.traffic, bar.i) });
    },
    showUnmatched(item) {
      // The end time is the time when the profile is generated: the list and the number of statistics cover the same period, and requests that come in after that are not counted.
      this.$emit('show-unmatched', { protocol: item.protocol, method: item.method || '', endpoint: item.endpoint,
        from: this.traffic.from, to: this.traffic.generatedAt || this.traffic.to });
    },
    protocolLabel(item) {
      if (item.protocol === 'JMS') { return this.jmsLabel; }
      return item.method || this.httpLabel;
    },
    tabIndexOf(bar) {
      const current = this.focusIndex >= 0 ? this.focusIndex : this.bars.length - 1;
      return bar.i === current ? 0 : -1;
    },
    onKey(event, bar) {
      const step = { ArrowLeft: -1, ArrowRight: 1, Home: -bar.i, End: this.bars.length - 1 - bar.i }[event.key];
      if (step === undefined) {
        if (event.key === 'Escape') { this.hover = -1; }
        return;
      }
      event.preventDefault();
      const next = Math.min(this.bars.length - 1, Math.max(0, bar.i + step));
      this.focusIndex = next;
      this.hover = next;
      this.$nextTick(() => this.$refs.plot?.querySelectorAll('.traffic-bar')[next]?.focus());
    },
    fmtNumber(value) { return value == null ? '–' : Number(value).toLocaleString(); },
    fmtMs(value) { return value == null ? '–' : (value >= 1000 ? (value / 1000).toFixed(value >= 10000 ? 0 : 1) + ' s' : value + ' ms'); },
  },
  template: /* html */`
    <section class="traffic card" :class="'is-' + mode" aria-labelledby="traffic-title">
      <div class="traffic-head">
        <h2 id="traffic-title" class="traffic-title">{{t('stats.traffic.title')}}</h2>
        <template v-if="mode !== 'collapsed'">
          <ui-segmented-control v-model="range" :options="rangeOptions" name="traffic-range" size="compact"
            :aria-label="t('stats.traffic.rangeLabel')"></ui-segmented-control>
          <span v-if="expanded && bucketText" class="traffic-muted">{{bucketText}}</span>
          <dl v-if="!expanded && totals" class="traffic-inline">
            <div><dt>{{t('stats.traffic.requests')}}</dt><dd>{{fmtNumber(totals.requests)}}</dd></div>
            <div><dt>{{t('stats.traffic.matchRate')}}</dt><dd>{{matchRate == null ? '–' : matchRate + '%'}}</dd></div>
            <div><dt>{{t('stats.traffic.forwarded')}}</dt><dd>{{fmtNumber(totals.forwarded + totals.forwardFailed)}}</dd></div>
            <div><dt>{{t('stats.traffic.unmatched')}}</dt><dd>{{fmtNumber(totals.unmatched)}}</dd></div>
            <div><dt>{{t('stats.traffic.responseShort')}}</dt><dd>{{fmtMs(responseTime.typicalMs)}} / {{fmtMs(responseTime.slowMs)}}</dd></div>
          </dl>
        </template>
        <span v-else class="traffic-muted">{{t('stats.traffic.collapsed')}}</span>
        <span class="traffic-actions">
          <ui-button v-if="mode === 'compact'" type="button" variant="quiet" size="compact" @click="setMode('expanded')">
            <i class="bi bi-arrows-angle-expand" aria-hidden="true"></i>{{t('stats.traffic.expand')}}
          </ui-button>
          <ui-button v-else-if="mode === 'expanded'" type="button" variant="quiet" size="compact" @click="setMode('compact')">
            <i class="bi bi-arrows-angle-contract" aria-hidden="true"></i>{{t('stats.traffic.shrink')}}
          </ui-button>
          <ui-button v-if="mode !== 'collapsed'" type="button" variant="quiet" size="compact" :aria-expanded="true"
            aria-controls="traffic-body" @click="setMode('collapsed')">
            <i class="bi bi-chevron-up" aria-hidden="true"></i>{{t('stats.traffic.collapse')}}
          </ui-button>
          <ui-button v-else type="button" variant="quiet" size="compact" :aria-expanded="false" aria-controls="traffic-body"
            @click="setMode('compact')">
            <i class="bi bi-chevron-down" aria-hidden="true"></i>{{t('stats.traffic.show')}}
          </ui-button>
        </span>
      </div>

      <div v-if="mode !== 'collapsed'" id="traffic-body" class="traffic-body">
        <p v-if="failed && !traffic" class="traffic-muted traffic-state">
          {{t('stats.traffic.loadFailed')}}
          <button type="button" class="detail-inline-action" @click="load">{{t('common.retry')}}</button>
        </p>
        <div v-else-if="!traffic" class="traffic-skeleton" :class="{'is-tall': expanded}" role="status" :aria-label="t('stats.traffic.loading')"></div>
        <template v-else>
          <dl v-if="expanded" class="traffic-metrics">
            <div><dd>{{fmtNumber(totals.requests)}}</dd><dt>{{t('stats.traffic.requests')}}</dt></div>
            <div><dd>{{matchRate == null ? '–' : matchRate}}<small v-if="matchRate != null">%</small></dd><dt>{{t('stats.traffic.matchRateLong')}}</dt></div>
            <div><dd>{{fmtNumber(totals.forwarded + totals.forwardFailed)}}</dd><dt>{{t('stats.traffic.forwardedLong', {failed: fmtNumber(totals.forwardFailed)})}}</dt></div>
            <div><dd>{{fmtNumber(totals.unmatched)}}</dd><dt>{{t('stats.traffic.unmatched')}}</dt></div>
            <div><dd>{{fmtMs(responseTime.typicalMs)}} / {{fmtMs(responseTime.slowMs)}}</dd><dt>{{t('stats.traffic.responseLong')}}</dt></div>
          </dl>

          <div class="traffic-main">
            <div class="traffic-chart">
              <div ref="plot" class="traffic-plot" :class="{'has-axis': expanded}" @mouseleave="hover = -1">
                <div v-if="expanded" class="traffic-grid" aria-hidden="true">
                  <span v-for="tick in axis.ticks" :key="tick" :style="{bottom: tick / axis.max * 100 + '%'}"><em>{{tick}}</em></span>
                </div>
                <div class="traffic-bars" role="group" :aria-label="t('stats.traffic.chartLabel')">
                  <button v-for="bar in bars" :key="bar.bucket.start" type="button" class="traffic-bar"
                    :class="{'is-missing': bar.missing, 'is-hover': hover === bar.i, 'is-selected': bar.selected}"
                    :tabindex="tabIndexOf(bar)" :aria-label="barLabel(bar)" :aria-pressed="bar.selected"
                    :aria-disabled="bar.missing"
                    @mouseenter="hover = bar.i" @focus="hover = bar.i; focusIndex = bar.i" @blur="hover = -1"
                    @keydown="onKey($event, bar)" @click="select(bar)">
                    <span class="traffic-stack">
                      <span v-for="seg in bar.segments" :key="seg.kind" class="traffic-seg" :class="'is-' + seg.kind"
                        :style="{height: seg.percent + '%'}"></span>
                    </span>
                  </button>
                </div>
                <div v-if="tip" class="traffic-tip" :class="'is-' + tip.align" :style="{left: tip.left + '%'}" aria-hidden="true">
                  <b>{{tip.time}}<template v-if="!tip.missing"> · {{t('stats.traffic.count', {n: fmtNumber(tip.total)})}}</template></b>
                  <template v-if="tip.missing"><span class="traffic-muted">{{t('stats.traffic.missing')}}</span></template>
                  <template v-else>
                    <span v-for="kind in ['mocked','forwarded','unmatched','forwardFailed']" :key="kind" class="traffic-tip-row">
                      <span><i class="traffic-key" :class="'is-' + kind"></i>{{t('stats.traffic.kind_' + kind)}}</span><span>{{tip.bucket[kind]}}</span>
                    </span>
                    <span v-if="tip.total" class="traffic-tip-hint">{{t('stats.traffic.clickHint')}}</span>
                  </template>
                </div>
              </div>
              <div class="traffic-axis" :class="{'has-axis': expanded}" aria-hidden="true">
                <span v-for="bar in bars" :key="bar.bucket.start">{{bar.label}}</span>
              </div>
              <div v-if="expanded" class="traffic-legend">
                <span v-for="kind in ['mocked','forwarded','unmatched','forwardFailed']" :key="kind"><i class="traffic-key" :class="'is-' + kind"></i>{{t('stats.traffic.kind_' + kind)}}</span>
              </div>
            </div>

            <div v-if="expanded" class="traffic-side">
              <h3>{{t('stats.traffic.topUnmatched')}} <span class="traffic-muted">· {{t('stats.traffic.inRange')}}</span></h3>
              <p v-if="!topUnmatched.length" class="traffic-muted">{{t('stats.traffic.noUnmatched')}}</p>
              <ol v-else class="traffic-top">
                <li v-for="item in topUnmatched" :key="item.protocol + item.method + item.endpoint">
                  <code :title="item.endpoint"><span class="traffic-muted">{{protocolLabel(item)}}</span> {{item.endpoint}}</code>
                  <span class="traffic-count tabular-nums">{{fmtNumber(item.count)}}</span>
                  <button type="button" class="detail-inline-action" @click="showUnmatched(item)">{{t('stats.traffic.showRequests')}}</button>
                </li>
              </ol>
            </div>
          </div>

          <p v-if="!expanded && topUnmatched.length" class="traffic-hint">
            {{t('stats.traffic.topOne')}}
            <code>{{protocolLabel(topUnmatched[0])}} {{topUnmatched[0].endpoint}}</code>
            {{t('stats.traffic.times', {n: fmtNumber(topUnmatched[0].count)})}}
            <button type="button" class="detail-inline-action" @click="showUnmatched(topUnmatched[0])">{{t('stats.traffic.showRequests')}}</button>
            <button type="button" class="detail-inline-action" @click="setMode('expanded')">{{t('stats.traffic.showAll')}}</button>
          </p>
          <p v-if="truncatedNote" class="traffic-hint traffic-muted"><i class="bi bi-info-circle" aria-hidden="true"></i> {{truncatedNote}}</p>
        </template>
      </div>
    </section>
  `,
};
