/** Manual, ADMIN-only resource snapshots. No polling or request-history storage. */
const ResourceMonitoringPanel = {
  props: { refreshToken: Number, view: { type: String, default: 'overview' }, backupEnabled: { type: Boolean, default: null }, ruleCaches: Object },
  emits: ['loading', 'snapshot', 'navigate'],
  inject: ['t'],
  data() {
    return { snapshot: null, busy: false, failed: false, requestVersion: 0, requestController: null, disposed: false, openDetails: {} };
  },
  mounted() { this.loadSnapshot(); },
  beforeUnmount() {
    this.disposed = true;
    this.requestVersion++;
    this.requestController?.abort();
    this.snapshot = null;
    this.$emit('loading', false);
  },
  watch: { refreshToken() { this.loadSnapshot(); } },
  computed: {
    groups() {
      const fields = {
        jvm: ['heapUsedBytes', 'heapMaxBytes', 'nonHeapUsedBytes', 'nonHeapCommittedBytes', 'directBytes', 'mappedBytes', 'threads', 'uptimeSeconds', 'gcCount', 'gcTimeMs'],
        caches: ['httpRulesEntries', 'httpRulesEvictions', 'jmsRulesEntries', 'jmsRulesEvictions', 'body_entries', 'body_weightedSize', 'body_maximumWeight', 'body_evictionCount', 'template_entries', 'template_weightedSize', 'template_maximumWeight', 'template_evictionCount'],
        scheduler: ['waitingTasks', 'activeWorkers', 'workerCapacity'],
        jms: ['listenerActive', 'listenerExits', 'replySent', 'replyFailures', 'lastListenerExit', 'forwardActive', 'forwardExits', 'forwardFailures', 'receiveTimeouts', 'invalidReplies', 'cleanupFailures', 'lastForwardExit', 'reservedBytes', 'maximumBytes', 'waitingThreads', 'processingRunning', 'maxDeliveryAttempts'],
        http: ['activeForwards', 'completedForwards', 'cancelledForwards', 'rejectedForwards', 'poolLeased', 'poolPending', 'poolIdle', 'poolCapacity', 'bufferedBytes', 'bufferLimitBytes'],
        database: ['poolActive', 'poolIdle', 'poolTotal', 'poolWaiting', 'writerWaiting', 'writerActive'],
        requestLog: ['queueItems', 'processed', 'dropped', 'queueBytes', 'capacityBytes', 'inFlightBytes', 'byteLimit', 'waitingProducers', 'backpressureActive', 'storageUnavailable', 'consumerRunning'],
        applicationLog: ['queueUsed', 'queueCapacity', 'started'],
        storage: ['dataFreeBytes', 'backupFreeBytes'],
      };
      return Object.entries(fields).map(([key, metrics]) => ({ key, metrics, section: this.snapshot?.sections?.[key] }));
    },
    summaries() {
      const fields = {
        jvm: ['heapUsedBytes', 'heapMaxBytes'],
        jms: ['listenerActive', 'forwardActive', 'waitingThreads'],
        http: ['activeForwards', 'poolLeased', 'poolPending'],
        database: ['poolActive', 'poolIdle', 'poolWaiting', 'writerWaiting'],
        requestLog: ['queueItems', 'backpressureActive', 'consumerRunning'],
        storage: ['dataFreeBytes', 'backupFreeBytes'],
      };
      return Object.entries(fields).map(([key, metrics]) => ({ key, metrics, section: this.snapshot?.sections?.[key] }));
    },
    detailGroups() {
      const cumulative = {
        jvm: ['gcCount', 'gcTimeMs'],
        caches: ['httpRulesEvictions', 'jmsRulesEvictions', 'body_evictionCount', 'template_evictionCount'],
        jms: ['listenerExits', 'replySent', 'replyFailures', 'forwardExits', 'forwardFailures', 'receiveTimeouts', 'invalidReplies', 'cleanupFailures'],
        http: ['completedForwards', 'cancelledForwards', 'rejectedForwards'],
        requestLog: ['processed', 'dropped'],
      };
      const limits = {
        jvm: ['heapMaxBytes', 'nonHeapCommittedBytes'],
        caches: ['body_maximumWeight', 'template_maximumWeight'],
        scheduler: ['workerCapacity'],
        jms: ['maximumBytes', 'maxDeliveryAttempts', 'lastListenerExit', 'lastForwardExit'],
        http: ['poolCapacity', 'bufferLimitBytes'],
        requestLog: ['capacityBytes', 'byteLimit'],
        applicationLog: ['queueCapacity'],
      };
      return [
        { key: 'memory', keys: ['jvm'] },
        { key: 'cache', keys: ['caches'] },
        { key: 'jms', keys: ['jms'] },
        { key: 'http', keys: ['http'] },
        { key: 'database', keys: ['database', 'requestLog', 'applicationLog', 'storage'] },
        { key: 'scheduler', keys: ['scheduler'] },
      ].map(group => {
        const sections = group.keys.map(key => this.groups.find(section => section.key === key));
        const columns = ['current', 'cumulative', 'limits'].map(column => ({
          key: column,
          sections: sections.map(section => ({ ...section, metrics: section.metrics.filter(metric => {
            const isCumulative = (cumulative[section.key] || []).includes(metric);
            const isLimit = (limits[section.key] || []).includes(metric);
            return column === 'cumulative' ? isCumulative : column === 'limits' ? isLimit : !isCumulative && !isLimit;
          }) })).filter(section => section.metrics.length),
        }));
        return { ...group, sections, incomplete: sections.filter(section => section.section?.state !== 'AVAILABLE').length,
          columns,
          tone: sections.some(section => section.section?.state === 'FAILED') ? 'danger'
            : sections.some(section => section.section?.state === 'PARTIAL') ? 'warning' : 'neutral' };
      });
    },
    heapPercent() {
      const section = this.snapshot?.sections?.jvm;
      const used = section?.values?.heapUsedBytes;
      const max = section?.values?.heapMaxBytes;
      return this.hasValues(section) && Number.isFinite(used) && used >= 0 && Number.isFinite(max) && max > 0
        ? Math.min(100, Math.round(used / max * 100)) : null;
    },
  },
  methods: {
    hasValues(section) { return section && ['AVAILABLE', 'PARTIAL'].includes(section.state); },
    toggleDetails(key, event) { this.openDetails[key] = event.target.open; },
    navigateToDetail(key) {
      this.$emit('navigate', key === 'storage' ? 'data' : 'monitoring',
        key === 'jvm' ? 'memory' : key === 'requestLog' ? 'database' : key);
    },
    openSection(key) {
      if (!this.detailGroups.some(group => group.key === key)) return;
      this.openDetails[key] = true;
      this.$nextTick(() => {
        const detail = this.$el.querySelector('[data-monitoring-detail="' + key + '"]');
        if (!detail) return;
        detail.open = true;
        detail.querySelector('summary').focus({ preventScroll: true });
        detail.scrollIntoView({ block: 'nearest' });
      });
    },
    cacheHitRate(name) {
      const cache = this.ruleCaches?.[name];
      if (!cache) return this.t('monitoring.unavailable');
      if (!cache.requestCount) return this.t('monitoring.noCacheRequests');
      return Number.isFinite(cache.hitRate) ? (cache.hitRate * 100).toLocaleString(undefined, { maximumFractionDigits: 1 }) + '%' : this.t('monitoring.unavailable');
    },
    detailHint(key) {
      const summaryKey = key === 'memory' ? 'jvm' : key === 'cache' ? 'caches' : key;
      const section = this.snapshot?.sections?.[summaryKey];
      if (!this.hasValues(section)) return this.t('monitoring.states.' + (section?.state || 'UNSUPPORTED'));
      const metrics = { memory: ['heapUsedBytes', 'heapMaxBytes', 'threads'], cache: ['body_weightedSize', 'body_maximumWeight'],
        jms: ['listenerActive', 'forwardActive', 'reservedBytes'], http: ['activeForwards', 'poolLeased', 'poolPending'],
        database: ['poolWaiting', 'writerWaiting'], scheduler: ['waitingTasks', 'activeWorkers', 'workerCapacity'] };
      return metrics[key].map(metric => this.t('monitoring.metrics.' + metric) + ' ' + this.formatMetric(metric, section.values?.[metric])).join(' · ');
    },
    async loadSnapshot() {
      if (this.busy || this.disposed) return;
      this.busy = true;
      this.$emit('loading', true);
      const version = ++this.requestVersion;
      const controller = new AbortController();
      this.requestController = controller;
      // A one-shot request deadline, not a polling timer.
      const deadline = setTimeout(() => controller.abort(), 8000);
      try {
        const response = await fetch('/api/admin/resources', { headers: { Accept: 'application/json' }, cache: 'no-store', signal: controller.signal });
        if (this.disposed || version !== this.requestVersion) return;
        if (response.status === 401 || response.status === 403) this.snapshot = null;
        if (!response.ok) throw new Error('snapshot-unavailable');
        const data = await response.json();
        if (this.disposed || version !== this.requestVersion) return;
        this.snapshot = data;
        this.failed = false;
      } catch {
        if (!this.disposed && version === this.requestVersion) this.failed = true;
      } finally {
        clearTimeout(deadline);
        if (version === this.requestVersion) {
          this.busy = false;
          this.requestController = null;
          this.$emit('loading', false);
          this.$emit('snapshot', { snapshot: this.snapshot, failed: this.failed });
        }
      }
    },
    stateTone(state) { return state === 'FAILED' ? 'danger' : state === 'PARTIAL' ? 'warning' : 'neutral'; },
    collectedTime(value) {
      if (!value) return this.t('monitoring.noSample');
      const date = new Date(value);
      return Number.isNaN(date.getTime()) ? this.t('monitoring.unavailable') : date.toLocaleString();
    },
    formatMetric(key, value) {
      if (value == null) return this.t(key.startsWith('last') ? 'monitoring.noSample' : 'monitoring.unavailable');
      if (typeof value === 'boolean') return this.t(value ? 'monitoring.yes' : 'monitoring.no');
      if (typeof value !== 'number' || !Number.isFinite(value)) return this.t('monitoring.unavailable');
      if (key.startsWith('last')) return this.collectedTime(value);
      if (key === 'maxDeliveryAttempts' && value === -1) return this.t('monitoring.unlimited');
      if (value < 0) return this.t('monitoring.unavailable');
      if (key.endsWith('Bytes') || key === 'byteLimit' || key.includes('weightedSize') || key.includes('maximumWeight')) {
        if (value < 1024) return value.toLocaleString() + ' B';
        const unit = value >= 1024 ** 3 ? 'GiB' : value >= 1024 ** 2 ? 'MiB' : 'KiB';
        const divisor = unit === 'GiB' ? 1024 ** 3 : unit === 'MiB' ? 1024 ** 2 : 1024;
        return (value / divisor).toLocaleString(undefined, { maximumFractionDigits: 2 }) + ' ' + unit;
      }
      return value.toLocaleString() + (key.endsWith('Ms') ? ' ms' : key.endsWith('Seconds') ? ' s' : '');
    },
    exportSnapshot() {
      if (!this.snapshot || this.failed || this.busy) return;
      const url = URL.createObjectURL(new Blob([JSON.stringify(this.snapshot, null, 2)], { type: 'application/json' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = 'echo-resources.json';
      link.click();
      URL.revokeObjectURL(url);
    },
  },
  template: /* html */`
    <section class="resource-monitoring" :aria-busy="busy">
      <div class="resource-monitoring-meta" aria-live="polite">
        <span v-if="busy">{{t('monitoring.loading')}}</span>
        <span v-if="snapshot">{{t('monitoring.collectedAt')}} {{collectedTime(snapshot.collectedAt)}}</span>
        <span v-if="failed" class="resource-monitoring-error">{{t(snapshot ? 'monitoring.stale' : 'monitoring.failed')}}</span>
        <ui-button v-if="failed" variant="secondary" size="compact" @click="loadSnapshot" :disabled="busy">{{t('common.retry')}}</ui-button>
      </div>
      <p v-if="snapshot && !snapshot.enabled" class="settings-state-row">{{t('monitoring.disabledHint')}}</p>
      <template v-else-if="snapshot">
        <div v-show="view === 'overview'" class="settings-card resource-overview">
          <div class="settings-card-header">{{t('monitoring.overviewTitle')}}</div>
          <table class="settings-table resource-overview-table">
            <thead><tr><th scope="col">{{t('monitoring.item')}}</th><th scope="col">{{t('monitoring.currentValues')}}</th><th scope="col" class="settings-table-actions">{{t('monitoring.detailLink')}}</th></tr></thead>
            <tbody><tr v-for="group in summaries" :key="group.key">
              <th scope="row">{{t('monitoring.summary.' + group.key)}}</th>
              <td>
                <ui-status v-if="group.section?.state !== 'AVAILABLE'" :tone="stateTone(group.section?.state)">{{t('monitoring.states.' + (group.section?.state || 'UNSUPPORTED'))}}</ui-status>
                <div v-if="hasValues(group.section)" class="resource-summary-values">
                  <span v-for="key in group.metrics" :key="key"><span class="resource-summary-label">{{t('monitoring.metrics.' + key)}}</span> {{formatMetric(key, group.section.values?.[key])}}</span>
                </div>
                <template v-if="group.key === 'jvm' && heapPercent !== null"><progress class="resource-heap-meter" :value="heapPercent" max="100" :aria-label="t('monitoring.heapUsage')"></progress><span class="resource-heap-percent">{{heapPercent}}%</span><span class="settings-inline-note resource-summary-note">{{t('monitoring.heapSnapshotHint')}}</span></template>
                <span v-if="group.key === 'storage'" class="settings-inline-note resource-summary-note">{{t('settings.schedule')}} · {{backupEnabled == null ? t('monitoring.unavailable') : t(backupEnabled ? 'settings.backupEnabled' : 'settings.backupDisabled')}}</span>
              </td>
              <td class="settings-table-actions"><ui-button variant="quiet" size="compact" @click="navigateToDetail(group.key)">{{t('monitoring.links.' + group.key)}} <i class="bi bi-chevron-right" aria-hidden="true"></i></ui-button></td>
            </tr></tbody>
          </table>
          <p class="settings-section-note">{{t('monitoring.overviewHint')}}</p>
        </div>
        <div v-show="view === 'monitoring'">
          <div class="resource-monitoring-heading"><p class="settings-inline-note resource-monitoring-cumulative">{{t('monitoring.cumulativeHint')}} {{collectedTime(snapshot.processStartedAt)}}</p><ui-button variant="secondary" size="compact" @click="exportSnapshot" :disabled="!snapshot || failed || busy">{{t('monitoring.export')}}</ui-button></div>
          <div class="resource-monitoring-details">
          <details v-for="detail in detailGroups" :key="detail.key" :data-monitoring-detail="detail.key" @toggle="toggleDetails(detail.key, $event)">
            <summary>
              <i class="bi bi-chevron-right resource-monitoring-chevron" aria-hidden="true"></i>
              <span class="resource-monitoring-disclosure-title">{{t('monitoring.details.' + detail.key)}}</span>
              <span class="resource-monitoring-disclosure-hint">{{detailHint(detail.key)}}</span>
              <ui-status v-if="detail.incomplete" :tone="detail.tone" class="resource-monitoring-disclosure-state">{{t('monitoring.incompleteGroups', { count: detail.incomplete })}}</ui-status>
              <span v-else class="resource-monitoring-collection-state">{{t('monitoring.states.AVAILABLE')}}</span>
            </summary>
            <div v-if="openDetails[detail.key]" class="resource-monitoring-detail-content">
              <div class="resource-monitoring-columns">
                <section v-for="column in detail.columns" :key="column.key" class="resource-monitoring-column">
                  <h3>{{t('monitoring.columns.' + column.key)}}</h3>
                  <div v-for="group in column.sections" :key="group.key" class="resource-monitoring-cluster">
                    <h4 v-if="detail.sections.length > 1">{{t('monitoring.groups.' + group.key)}}</h4>
                    <ui-status v-if="group.section?.state !== 'AVAILABLE'" :tone="stateTone(group.section?.state)">{{t('monitoring.states.' + (group.section?.state || 'UNSUPPORTED'))}}</ui-status>
                    <dl v-if="hasValues(group.section)" class="resource-monitoring-metrics">
                      <div v-for="key in group.metrics" :key="key" class="resource-monitoring-metric"><dt>{{t('monitoring.metrics.' + key)}}</dt><dd>{{formatMetric(key, group.section.values?.[key])}}</dd></div>
                    </dl>
                  </div>
                  <p v-if="!column.sections.length" class="settings-inline-note">{{t('monitoring.noMetricsInColumn')}}</p>
                </section>
              </div>
              <p v-if="detail.key === 'cache'" class="settings-inline-note">{{t('monitoring.cacheHitRateSource')}} · HTTP {{cacheHitRate('httpRules')}} · JMS {{cacheHitRate('jmsRules')}}</p>
            </div>
            <p v-if="openDetails[detail.key]" class="settings-section-note">{{t('monitoring.detailNotes.' + detail.key)}}</p>
          </details>
          </div>
          <p class="settings-inline-note resource-monitoring-config">{{t('monitoring.configHint')}}</p>
        </div>
      </template>
    </section>
  `,
};
