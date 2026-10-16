/** Read-only, bounded GC notification history. Refreshing never triggers garbage collection. */
const GcHistoryPanel = {
  props: { refreshToken: Number },
  inject: ['t'],
  data() { return { history: null, busy: false, failed: false, disposed: false, controller: null, collector: '', page: 0 }; },
  mounted() { this.load(); },
  beforeUnmount() { this.disposed = true; this.controller?.abort(); this.history = null; },
  watch: { refreshToken() { this.load(); }, collector() { this.page = 0; } },
  computed: {
    events() { return (this.history?.events || []).filter(event => !this.collector || event.collector === this.collector); },
    rows() { return [...this.events].reverse().slice(this.page * 25, (this.page + 1) * 25); },
    pageCount() { return Math.max(1, Math.ceil(this.events.length / 25)); },
    chart() {
      const events = this.events.filter(event => event.heapCoverageComplete && Number.isFinite(event.heapAfterBytes)
        && Number.isFinite(Date.parse(event.endedAt))).sort((a, b) => Date.parse(a.endedAt) - Date.parse(b.endedAt));
      if (!events.length) return null;
      const start = Date.parse(events[0].endedAt), end = Date.parse(events.at(-1).endedAt);
      const max = Math.max(1, ...events.map(event => Math.max(event.heapAfterBytes, event.heapMaxBytes || 0)));
      const points = events.map(event => ({ event, x: 10 + (Date.parse(event.endedAt) - start) / Math.max(1, end - start) * 780,
        y: 170 - event.heapAfterBytes / max * 150 }));
      return { points, line: points.map(point => point.x + ',' + point.y).join(' '), max, start, end };
    },
  },
  methods: {
    async load() {
      if (this.busy || this.disposed) return;
      this.busy = true;
      const controller = new AbortController();
      this.controller = controller;
      const deadline = setTimeout(() => controller.abort(), 8000);
      try {
        const response = await fetch('/api/admin/resources/gc?limit=512', {
          headers: { Accept: 'application/json' }, cache: 'no-store', signal: controller.signal,
        });
        if (this.disposed) return;
        if (response.status === 401 || response.status === 403) this.history = null;
        if (!response.ok) throw new Error('gc-unavailable');
        const history = await response.json();
        if (this.disposed) return;
        this.history = history;
        this.page = 0;
        if (!history.collectors?.includes(this.collector)) this.collector = '';
        this.failed = false;
      } catch {
        if (!this.disposed) this.failed = true;
      } finally {
        clearTimeout(deadline);
        this.busy = false;
        this.controller = null;
      }
    },
    bytes(value) {
      if (!Number.isFinite(value)) return this.t('monitoring.unavailable');
      return (value / 1024 ** 2).toLocaleString(undefined, { maximumFractionDigits: 2 }) + ' MiB';
    },
    time(value) { return value == null ? this.t('monitoring.noSample') : new Date(value).toLocaleString(); },
    exportHistory() {
      if (!this.history || this.failed || this.busy) return;
      const url = URL.createObjectURL(new Blob([JSON.stringify(this.history, null, 2)], { type: 'application/json' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = 'echo-gc-history.json';
      link.click();
      URL.revokeObjectURL(url);
    },
  },
  template: /* html */`
    <section class="gc-history" :aria-busy="busy">
      <div class="gc-history-toolbar">
        <h3>{{t('monitoring.gc.title')}}</h3>
        <ui-button variant="secondary" size="compact" @click="load" :disabled="busy">{{t('monitoring.gc.refresh')}}</ui-button>
        <ui-button variant="quiet" size="compact" @click="exportHistory" :disabled="!history || busy || failed">{{t('monitoring.export')}}</ui-button>
      </div>
      <p class="settings-inline-note">{{t('monitoring.gc.hint')}}</p>
      <p v-if="failed" class="resource-monitoring-error" role="status">{{t(history ? 'monitoring.stale' : 'monitoring.failed')}}</p>
      <template v-if="history">
        <p v-if="history.state !== 'AVAILABLE'" role="status">{{t('monitoring.states.' + history.state)}}</p>
        <template v-if="['AVAILABLE', 'PARTIAL'].includes(history.state)">
          <p class="settings-inline-note">{{t('monitoring.gc.retention', { shown: history.events.length, capacity: history.capacity, total: history.totalEvents, overwritten: history.overwrittenEvents, expired: history.expiredEvents, hours: history.maxAgeSeconds / 3600 })}}</p>
          <p class="settings-inline-note">{{t('monitoring.gc.recordingSince')}} {{time(history.recordingStartedAt)}}</p>
          <p v-if="history.notificationFailures || history.unsupportedCollectors.length" class="resource-monitoring-error">{{t('monitoring.gc.incomplete', { failures: history.notificationFailures })}} {{history.unsupportedCollectors.join(', ')}}</p>
          <label class="gc-collector-filter">{{t('monitoring.gc.collector')}}
            <select v-model="collector"><option value="">{{t('monitoring.gc.allCollectors')}}</option><option v-for="name in history.collectors" :key="name" :value="name">{{name}}</option></select>
          </label>
          <figure v-if="chart" class="gc-chart">
            <figcaption>{{t('monitoring.gc.afterTrend')}} · {{t('monitoring.gc.scale')}} {{bytes(chart.max)}}</figcaption>
            <svg viewBox="0 0 800 180" role="img" :aria-label="t('monitoring.gc.afterTrend')">
              <line x1="10" y1="170" x2="790" y2="170" class="gc-chart-axis" />
              <polyline :points="chart.line" fill="none" class="gc-chart-line" />
              <circle v-for="point in chart.points" :key="point.event.sequence" :cx="point.x" :cy="point.y" r="3" class="gc-chart-dot"><title>{{time(point.event.endedAt)}} · {{point.event.collector}} · {{bytes(point.event.heapAfterBytes)}}</title></circle>
            </svg>
            <div class="gc-chart-times"><span>{{time(chart.start)}}</span><span>{{time(chart.end)}}</span></div>
          </figure>
          <p v-if="!events.length" class="settings-inline-note">{{t('monitoring.gc.empty')}}</p>
          <div v-else class="gc-table-scroll">
            <table class="settings-table gc-events-table">
              <thead><tr><th scope="col">{{t('monitoring.gc.time')}}</th><th scope="col">{{t('monitoring.gc.kind')}}</th><th scope="col">{{t('monitoring.gc.duration')}}</th><th scope="col">{{t('monitoring.gc.before')}}</th><th scope="col">{{t('monitoring.gc.after')}}</th><th scope="col">{{t('monitoring.gc.reclaimed')}}</th><th scope="col">{{t('monitoring.gc.pools')}}</th></tr></thead>
              <tbody><tr v-for="event in rows" :key="event.sequence">
                <td>{{time(event.endedAt)}}<small>#{{event.sequence}}</small></td>
                <td>{{event.collector}}<small>{{event.action}} · {{event.cause}}</small></td>
                <td>{{event.durationMs.toLocaleString()}} ms</td><td>{{bytes(event.heapBeforeBytes)}}</td><td>{{bytes(event.heapAfterBytes)}}</td><td>{{bytes(event.reclaimedBytes)}}</td>
                <td><details class="gc-pool-details"><summary>{{t('monitoring.gc.pools')}}</summary><dl><template v-for="pool in event.pools" :key="pool.name"><dt>{{pool.name}} ({{pool.type}})</dt><dd>{{bytes(pool.beforeBytes)}} → {{bytes(pool.afterBytes)}}</dd></template></dl></details></td>
              </tr></tbody>
            </table>
          </div>
          <div v-if="pageCount > 1" class="gc-history-toolbar">
            <ui-button variant="quiet" size="compact" :disabled="page === 0" @click="page--">{{t('monitoring.gc.previous')}}</ui-button><span>{{page + 1}} / {{pageCount}}</span><ui-button variant="quiet" size="compact" :disabled="page + 1 >= pageCount" @click="page++">{{t('monitoring.gc.next')}}</ui-button>
          </div>
          <p class="settings-section-note">{{t('monitoring.gc.interpretation')}}</p>
        </template>
      </template>
    </section>
  `,
};
