/** Manual, ADMIN-only resource snapshots. No polling or request-history storage. */
const ResourceMonitoringPanel = {
  props: { refreshToken: Number },
  emits: ['loading'],
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
        jms: ['listenerActive', 'forwardActive', 'lastListenerExit'],
        http: ['activeForwards', 'poolPending'],
        database: ['poolWaiting', 'writerWaiting'],
        requestLog: ['queueItems', 'backpressureActive'],
        storage: ['dataFreeBytes', 'backupFreeBytes'],
      };
      return Object.entries(fields).map(([key, metrics]) => ({ key, metrics, section: this.snapshot?.sections?.[key] }));
    },
    detailGroups() {
      return [
        { key: 'memory', keys: ['jvm', 'caches'] },
        { key: 'processing', keys: ['jms', 'http', 'scheduler'] },
        { key: 'persistence', keys: ['database', 'requestLog', 'applicationLog', 'storage'] },
      ].map(group => {
        const sections = group.keys.map(key => this.groups.find(section => section.key === key));
        return { ...group, sections, incomplete: sections.filter(section => section.section?.state !== 'AVAILABLE').length,
          tone: sections.some(section => section.section?.state === 'FAILED') ? 'danger'
            : sections.some(section => section.section?.state === 'PARTIAL') ? 'warning' : 'neutral' };
      });
    },
  },
  methods: {
    hasValues(section) { return section && ['AVAILABLE', 'PARTIAL'].includes(section.state); },
    toggleDetails(key, event) { this.openDetails[key] = event.target.open; },
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
    <section class="resource-monitoring" aria-labelledby="resourceMonitoringTitle" :aria-busy="busy">
      <div class="resource-monitoring-heading">
        <div><h2 id="resourceMonitoringTitle" class="resource-monitoring-title">{{t('monitoring.title')}}</h2>
          <p class="settings-inline-note">{{t('monitoring.manualHint')}}</p></div>
        <ui-button variant="secondary" size="compact" @click="exportSnapshot" :disabled="!snapshot || failed || busy">{{t('monitoring.export')}}</ui-button>
      </div>
      <div class="resource-monitoring-meta" aria-live="polite">
        <span v-if="busy">{{t('monitoring.loading')}}</span>
        <span v-if="snapshot">{{t('monitoring.collectedAt')}} {{collectedTime(snapshot.collectedAt)}}</span>
        <span v-if="failed" class="resource-monitoring-error">{{t(snapshot ? 'monitoring.stale' : 'monitoring.failed')}}</span>
        <ui-button v-if="failed" variant="secondary" size="compact" @click="loadSnapshot" :disabled="busy">{{t('common.retry')}}</ui-button>
      </div>
      <p v-if="snapshot && !snapshot.enabled" class="settings-state-row">{{t('monitoring.disabledHint')}}</p>
      <template v-else-if="snapshot">
        <div class="resource-monitoring-overview">
          <section v-for="group in summaries" :key="group.key" class="resource-monitoring-summary">
            <div class="resource-monitoring-summary-heading">
              <h3 class="resource-monitoring-group-title">{{t('monitoring.summary.' + group.key)}}</h3>
              <ui-status v-if="group.section?.state !== 'AVAILABLE'" :tone="stateTone(group.section?.state)">{{t('monitoring.states.' + (group.section?.state || 'UNSUPPORTED'))}}</ui-status>
            </div>
            <dl v-if="hasValues(group.section)" class="resource-monitoring-metrics">
              <div v-for="key in group.metrics" :key="key" class="resource-monitoring-metric">
                <dt>{{t('monitoring.metrics.' + key)}}</dt>
                <dd>{{formatMetric(key, group.section.values?.[key])}}</dd>
              </div>
            </dl>
          </section>
        </div>
        <div class="resource-monitoring-details">
          <details v-for="detail in detailGroups" :key="detail.key" @toggle="toggleDetails(detail.key, $event)">
            <summary>
              <span class="resource-monitoring-disclosure-copy"><span>{{t('monitoring.details.' + detail.key)}}</span><small>{{t('monitoring.details.' + detail.key + 'Hint')}}</small></span>
              <ui-status v-if="detail.incomplete" :tone="detail.tone" class="resource-monitoring-disclosure-state">{{t('monitoring.incompleteGroups', { count: detail.incomplete })}}</ui-status>
              <i class="bi bi-chevron-down resource-monitoring-chevron" aria-hidden="true"></i>
            </summary>
            <div v-if="openDetails[detail.key]" class="resource-monitoring-detail-content">
              <p class="settings-inline-note resource-monitoring-cumulative">{{t('monitoring.cumulativeHint')}} {{collectedTime(snapshot.processStartedAt)}}</p>
              <div class="resource-monitoring-detail-grid">
                <section v-for="group in detail.sections" :key="group.key" class="resource-monitoring-detail-section">
                  <div class="resource-monitoring-summary-heading">
                    <h3 class="resource-monitoring-group-title">{{t('monitoring.groups.' + group.key)}}</h3>
                    <ui-status :tone="stateTone(group.section?.state)">{{t('monitoring.states.' + (group.section?.state || 'UNSUPPORTED'))}}</ui-status>
                  </div>
                  <dl v-if="hasValues(group.section)" class="resource-monitoring-metrics">
                    <div v-for="key in group.metrics" :key="key" class="resource-monitoring-metric">
                      <dt>{{t('monitoring.metrics.' + key)}}</dt>
                      <dd>{{formatMetric(key, group.section.values?.[key])}}</dd>
                    </div>
                  </dl>
                  <p v-if="hasValues(group.section) && ['jvm','caches','scheduler','jms','http'].includes(group.key)" class="settings-inline-note resource-monitoring-note">{{t('monitoring.notes.' + group.key)}}</p>
                </section>
              </div>
            </div>
          </details>
        </div>
      </template>
      <p class="settings-inline-note resource-monitoring-config">{{t('monitoring.configHint')}}</p>
    </section>
  `,
};
