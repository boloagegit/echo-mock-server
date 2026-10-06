/**
 * SettingsPage - 系統設定頁面
 *
 * 顯示服務資訊、協定設定、資料儲存、認證設定、備份狀態，以及危險操作區。
 */
const SettingsPage = {
  props: {
    status: Object,
    isAdmin: Boolean,
    isLoggedIn: Boolean,
    loading: Object,
    backupStatus: Object,
    jmsEnabled: Boolean,
  },
  emits: [
    'trigger-backup', 'delete-all-rules', 'delete-all-responses',
    'delete-all-audit', 'delete-all-logs', 'refresh-status',
    'delete-orphan-responses',
  ],
  inject: ['t'],
  data() {
    return {
      agents: [],
      activeTab: 'overview',
      resourceSnapshot: null,
      resourceFailed: false,
      connectionReturnFocus: null,
      connectionInertState: [],
      agentsLoading: false,
      resourceRefreshToken: 0,
      resourceLoading: false,
      jmsTargets: [],
      jmsTargetsLoading: false,
      jmsTargetSaving: false,
      jmsTargetTestingId: null,
      httpTargets: [],
      httpTargetsLoading: false,
      httpTargetSaving: false,
      httpTargetTestingId: null,
      httpTargetTestResults: {},
      jmsTargetTestResults: {},
      showHttpTargetForm: false,
      editingHttpTarget: null,
      httpTargetForm: {
        name: '', baseUrl: '', authType: 'NONE', username: '', secret: '', clearSecret: false,
        connectTimeoutSeconds: 5, readTimeoutSeconds: 30, tlsVerificationEnabled: false,
        enabled: true, defaultConnection: false, version: null,
      },
      showJmsTargetForm: false,
      editingJmsTarget: null,
      jmsTargetForm: {
        name: '', providerType: 'artemis', serverUrl: '', username: '', password: '',
        clearPassword: false, queueName: 'TARGET.REQUEST', timeoutSeconds: 30,
        enabled: true, defaultConnection: false, version: null,
      },
      scenarios: [],
      scenariosLoading: false,
      scenariosError: false,
      scenarioResetting: null,
    };
  },
  mounted() {
    this.loadAgents();
    if (this.isAdmin) { this.loadJmsTargets(); this.loadHttpTargets(); }
    if (this.scenarioEnabled) { this.loadScenarios(); }
  },
  watch: {
    isAdmin(admin) {
      if (!admin) {
        this.activeTab = 'overview';
        this.resourceSnapshot = null;
        this.resourceFailed = false;
        this.showHttpTargetForm = false;
        this.showJmsTargetForm = false;
      }
    },
    showHttpTargetForm(open) { this.onConnectionDialogChanged('http', open); },
    showJmsTargetForm(open) { this.onConnectionDialogChanged('jms', open); },
    status() {
      this.loadAgents();
      if (this.isAdmin) { this.loadJmsTargets(); this.loadHttpTargets(); }
      if (this.scenarioEnabled) {
        this.loadScenarios();
      } else {
        this.scenarios = [];
        this.scenariosError = false;
      }
    }
  },
  beforeUnmount() { restoreOverlaySiblings(this.connectionInertState); },
  computed: {
    tabs() {
      return ['overview', ...(this.isAdmin ? ['monitoring', 'connections'] : []), 'data', 'service'].map(value => ({
        value, label: this.t('settings.tabs.' + value), id: 'settings-tab-' + value, panelId: 'settings-content',
      }));
    },
    databaseKind() {
      const url = this.status?.datasourceUrl || '';
      if (url.startsWith('jdbc:sqlite:')) return 'SQLite';
      if (url.startsWith('jdbc:h2:')) return 'H2';
      return this.t('settings.database');
    },
    scenarioEnabled() {
      return this.status?.scenariosEnabled === true;
    },
    canSaveHttpTarget() {
      return Boolean(this.httpTargetForm.name.trim() && this.httpTargetForm.baseUrl.trim());
    },
    canSaveJmsTarget() {
      const form = this.jmsTargetForm;
      return Boolean(form.name.trim() && form.serverUrl.trim() && form.queueName.trim());
    },
    yamlJmsTargetConfigured() {
      return this.jmsTargets.some(target => target.legacy && target.enabled);
    },
    activeJmsTarget() {
      return this.jmsTargets.find(target => target.legacy && target.enabled)
        || this.jmsTargets.find(target => !target.legacy && target.defaultConnection && target.enabled)
        || null;
    },
    tlsModeOptions() {
      return [
        { value: false, label: this.t('settings.tlsModeCompatibility'), icon: 'bi-building' },
        { value: true, label: this.t('settings.tlsModeStrict'), icon: 'bi-shield-check' },
      ];
    },
  },
  methods: {
    onConnectionDialogChanged(protocol, open) {
      if (open) {
        this.connectionReturnFocus = document.activeElement;
        this.$nextTick(() => {
          if (!(protocol === 'http' ? this.showHttpTargetForm : this.showJmsTargetForm)) return;
          const dialog = this.$el.querySelector('.connection-' + protocol + '-form-modal');
          this.connectionInertState = makeOverlaySiblingsInert(dialog?.closest('.modal-overlay'));
          dialog?.querySelector('input')?.focus();
        });
      } else {
        restoreOverlaySiblings(this.connectionInertState);
        this.connectionInertState = [];
        const previous = this.connectionReturnFocus;
        this.connectionReturnFocus = null;
        this.$nextTick(() => { if (previous && document.contains(previous)) previous.focus(); });
      }
    },
    onConnectionDialogKeydown(event, protocol) {
      const dialog = event.currentTarget.querySelector('[role="dialog"]');
      trapDialogFocus(event, dialog);
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        if (protocol === 'http') this.showHttpTargetForm = false;
        else this.showJmsTargetForm = false;
      }
    },
    navigateTab(tab, section) {
      if (!this.tabs.some(item => item.value === tab)) return;
      this.activeTab = tab;
      if (section && tab === 'monitoring') this.$nextTick(() => this.$refs.resources?.openSection(section));
    },
    updateResourceSnapshot({ snapshot, failed }) {
      this.resourceSnapshot = snapshot;
      this.resourceFailed = failed;
    },
    storageMetric(key) {
      const section = this.resourceSnapshot?.sections?.storage;
      if (!section || !['AVAILABLE', 'PARTIAL'].includes(section.state)) return this.t('monitoring.unavailable');
      const value = section.values?.[key];
      if (!Number.isFinite(value) || value < 0) return this.t('monitoring.unavailable');
      const unit = value >= 1024 ** 3 ? 'GiB' : value >= 1024 ** 2 ? 'MiB' : value >= 1024 ? 'KiB' : 'B';
      const divisor = unit === 'GiB' ? 1024 ** 3 : unit === 'MiB' ? 1024 ** 2 : unit === 'KiB' ? 1024 : 1;
      return (value / divisor).toLocaleString(undefined, { maximumFractionDigits: 2 }) + ' ' + unit;
    },
    refreshStatus() {
      this.resourceRefreshToken++;
      this.$emit('refresh-status');
    },
    notify(message, type = 'success') {
      if (typeof _showToast === 'function') { _showToast(message, type); }
    },
    connectionTestResult(result) {
      return { ...result, testedAt: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) };
    },
    async loadHttpTargets() {
      this.httpTargetsLoading = true;
      try {
        const res = await apiCall('/api/admin/http-target-connections', {}, { silent: true });
        if (res && res.ok) this.httpTargets = await res.json();
      } finally { this.httpTargetsLoading = false; }
    },
    emptyHttpTargetForm() {
      return { name: '', baseUrl: '', authType: 'NONE', username: '', secret: '', clearSecret: false,
        connectTimeoutSeconds: 5, readTimeoutSeconds: 30, tlsVerificationEnabled: false,
        enabled: true, defaultConnection: this.httpTargets.length === 0, version: null };
    },
    openCreateHttpTarget() {
      this.editingHttpTarget = null;
      this.httpTargetForm = this.emptyHttpTargetForm();
      this.showHttpTargetForm = true;
    },
    openEditHttpTarget(target) {
      this.editingHttpTarget = target;
      this.httpTargetForm = { version: target.version, name: target.name, baseUrl: target.baseUrl,
        authType: target.authType, username: target.username || '', secret: '', clearSecret: false,
        connectTimeoutSeconds: target.connectTimeoutSeconds, readTimeoutSeconds: target.readTimeoutSeconds,
        tlsVerificationEnabled: target.tlsVerificationEnabled, enabled: target.enabled,
        defaultConnection: target.defaultConnection };
      this.showHttpTargetForm = true;
    },
    async saveHttpTarget() {
      const f = this.httpTargetForm;
      if (!f.name.trim() || !f.baseUrl.trim()) { this.notify(this.t('settings.httpTargetRequired'), 'error'); return; }
      this.httpTargetSaving = true;
      try {
        const editing = this.editingHttpTarget;
        const url = editing ? '/api/admin/http-target-connections/' + editing.id : '/api/admin/http-target-connections';
        const res = await apiCall(url, { method: editing ? 'PUT' : 'POST', body: JSON.stringify({ ...f,
          connectTimeoutSeconds: Number(f.connectTimeoutSeconds), readTimeoutSeconds: Number(f.readTimeoutSeconds) }) },
          { silent: true });
        if (res && res.ok) {
          this.showHttpTargetForm = false;
          await this.loadHttpTargets();
          this.notify(this.t('settings.httpTargetSaved'));
        } else {
          let code = '';
          if (res) {
            try { code = (await res.json()).error || ''; } catch { /* use localized generic error */ }
          }
          this.notify(res ? this.httpTargetErrorMessage(code) : this.t('toast.networkError'), 'error');
        }
      } finally { this.httpTargetSaving = false; }
    },
    async makeDefaultHttpTarget(target) {
      const res = await apiCall('/api/admin/http-target-connections/' + target.id + '/default', { method: 'PUT' });
      if (res && res.ok) { await this.loadHttpTargets(); this.notify(this.t('settings.httpTargetDefaultChanged')); }
    },
    onHttpTargetEnabledChange() {
      if (!this.httpTargetForm.enabled) this.httpTargetForm.defaultConnection = false;
    },
    httpTargetErrorMessage(code) {
      const messages = {
        HTTP_CONNECTION_NAME_EXISTS: 'httpTargetErrorNameExists',
        HTTP_BASE_URL_REQUIRED: 'httpTargetErrorBaseUrlRequired',
        INVALID_HTTP_BASE_URL: 'httpTargetErrorBaseUrlInvalid',
        UNSUPPORTED_HTTP_AUTH_TYPE: 'httpTargetErrorAuthType',
        HTTP_USERNAME_REQUIRED: 'httpTargetErrorUsernameRequired',
        HTTP_CONNECT_TIMEOUT_OUT_OF_RANGE: 'httpTargetErrorConnectTimeout',
        HTTP_READ_TIMEOUT_OUT_OF_RANGE: 'httpTargetErrorReadTimeout',
        HTTP_TIMEOUT_BUDGET_EXCEEDED: 'httpTargetErrorTimeoutBudget',
        DEFAULT_HTTP_CONNECTION_MUST_BE_ENABLED: 'httpTargetErrorDefaultEnabled',
        DEFAULT_HTTP_CONNECTION_CANNOT_BE_DISABLED: 'httpTargetErrorDefaultDisabled',
        USE_ANOTHER_HTTP_CONNECTION_AS_DEFAULT_FIRST: 'httpTargetErrorDefaultFirst'
      };
      return this.t('settings.' + (messages[code] || 'httpTargetErrorGeneric'));
    },
    async testHttpTarget(target) {
      this.httpTargetTestingId = target.id;
      try {
        const res = await apiCall('/api/admin/http-target-connections/' + target.id + '/test', { method: 'POST' });
        if (res && res.ok) {
          const result = await res.json();
          this.httpTargetTestResults[target.id] = this.connectionTestResult(result);
          this.notify(result.success ? this.t('settings.httpTargetTestSuccess', { ms: result.elapsedMs, status: result.status })
            : this.t('settings.httpTargetTestFailed', { error: result.error || '-' }), result.success ? 'success' : 'error');
        }
      } finally { this.httpTargetTestingId = null; }
    },
    async deleteHttpTarget(target) {
      if (!window.confirm(this.t('settings.httpTargetDeleteConfirm', { name: target.name }))) return;
      const res = await apiCall('/api/admin/http-target-connections/' + target.id, { method: 'DELETE' });
      if (res && res.ok) { await this.loadHttpTargets(); this.notify(this.t('settings.httpTargetDeleted')); }
    },
    async loadJmsTargets() {
      this.jmsTargetsLoading = true;
      try {
        const res = await apiCall('/api/admin/jms-target-connections', {}, { silent: true });
        if (res && res.ok) { this.jmsTargets = await res.json(); }
      } finally {
        this.jmsTargetsLoading = false;
      }
    },
    emptyJmsTargetForm() {
      const hasStoredTarget = this.jmsTargets.some(target => !target.legacy);
      return {
        name: '', providerType: 'artemis', serverUrl: '', username: '', password: '',
        clearPassword: false, queueName: 'TARGET.REQUEST', timeoutSeconds: 30,
        enabled: true, defaultConnection: !hasStoredTarget, version: null,
      };
    },
    openCreateJmsTarget() {
      this.editingJmsTarget = null;
      this.jmsTargetForm = this.emptyJmsTargetForm();
      this.showJmsTargetForm = true;
    },
    openEditJmsTarget(target) {
      if (target.legacy) { return; }
      this.editingJmsTarget = target;
      this.jmsTargetForm = {
        version: target.version, name: target.name, providerType: target.providerType,
        serverUrl: target.serverUrl, username: target.username || '', password: '',
        clearPassword: false, queueName: target.queueName,
        timeoutSeconds: target.timeoutSeconds, enabled: target.enabled,
        defaultConnection: target.defaultConnection,
      };
      this.showJmsTargetForm = true;
    },
    async saveJmsTarget() {
      const f = this.jmsTargetForm;
      if (!f.name.trim() || !f.serverUrl.trim() || !f.queueName.trim()) {
        this.notify(this.t('settings.jmsTargetRequired'), 'error');
        return;
      }
      this.jmsTargetSaving = true;
      try {
        const editing = this.editingJmsTarget;
        const url = editing
          ? '/api/admin/jms-target-connections/' + editing.id
          : '/api/admin/jms-target-connections';
        const res = await apiCall(url, {
          method: editing ? 'PUT' : 'POST',
          body: JSON.stringify({ ...f, timeoutSeconds: Number(f.timeoutSeconds) })
        });
        if (res && res.ok) {
          this.showJmsTargetForm = false;
          await this.loadJmsTargets();
          this.notify(this.t('settings.jmsTargetSaved'));
        }
      } finally {
        this.jmsTargetSaving = false;
      }
    },
    async makeDefaultJmsTarget(target) {
      const res = await apiCall('/api/admin/jms-target-connections/' + target.id + '/default', { method: 'PUT' });
      if (res && res.ok) {
        await this.loadJmsTargets();
        this.notify(this.t(this.yamlJmsTargetConfigured
          ? 'settings.jmsTargetFallbackDefaultChanged'
          : 'settings.jmsTargetDefaultChanged'));
      }
    },
    jmsTargetDisplayName(target) {
      return target?.legacy ? this.t('settings.jmsApplicationTarget') : (target?.name || '—');
    },
    onJmsTargetEnabledChange() {
      if (!this.jmsTargetForm.enabled) {
        this.jmsTargetForm.defaultConnection = false;
      }
    },
    async testJmsTarget(target) {
      this.jmsTargetTestingId = target.id;
      try {
        const res = await apiCall('/api/admin/jms-target-connections/' + target.id + '/test', { method: 'POST' });
        if (res && res.ok) {
          const result = await res.json();
          this.jmsTargetTestResults[target.id] = this.connectionTestResult(result);
          this.notify(result.success
            ? this.t('settings.jmsTargetTestSuccess', { ms: result.elapsedMs })
            : this.t('settings.jmsTargetTestFailed', { error: result.error || '-' }),
          result.success ? 'success' : 'error');
        }
      } finally {
        this.jmsTargetTestingId = null;
      }
    },
    async deleteJmsTarget(target) {
      if (!window.confirm(this.t('settings.jmsTargetDeleteConfirm', { name: target.name }))) { return; }
      const res = await apiCall('/api/admin/jms-target-connections/' + target.id, { method: 'DELETE' });
      if (res && res.ok) {
        await this.loadJmsTargets();
        this.notify(this.t('settings.jmsTargetDeleted'));
      }
    },
    async loadScenarios() {
      if (!this.scenarioEnabled) { return; }
      this.scenariosLoading = true;
      this.scenariosError = false;
      try {
        const res = await apiCall('/api/admin/scenarios', {}, { silent: true });
        if (res && res.ok) {
          this.scenarios = await res.json();
        } else {
          this.scenariosError = true;
        }
      } catch (e) {
        this.scenariosError = true;
      } finally {
        this.scenariosLoading = false;
      }
    },
    async resetScenario(name) {
      if (!window.confirm(this.t('settings.scenarioResetConfirm', { name }))) { return; }
      this.scenarioResetting = name;
      try {
        const res = await apiCall(`/api/admin/scenarios/${encodeURIComponent(name)}/reset`, { method: 'PUT' }, { silent: true });
        if (res && res.ok) {
          this.notify(this.t('toast.scenarioResetSuccess'));
          await this.loadScenarios();
        } else {
          this.notify(this.t('toast.scenarioResetFailed'), 'error');
        }
      } catch (e) {
        this.notify(this.t('toast.scenarioResetFailed'), 'error');
      } finally {
        this.scenarioResetting = null;
      }
    },
    async resetAllScenarios() {
      if (!window.confirm(this.t('settings.scenarioResetAllConfirm'))) { return; }
      this.scenarioResetting = '*';
      try {
        const res = await apiCall('/api/admin/scenarios/reset', { method: 'PUT' }, { silent: true });
        if (res && res.ok) {
          this.notify(this.t('toast.scenarioResetAllSuccess'));
          await this.loadScenarios();
        } else {
          this.notify(this.t('toast.scenarioResetFailed'), 'error');
        }
      } catch (e) {
        this.notify(this.t('toast.scenarioResetFailed'), 'error');
      } finally {
        this.scenarioResetting = null;
      }
    },
    async loadAgents() {
      this.agentsLoading = true;
      try {
        const res = await apiCall('/api/admin/agents', {}, { silent: true });
        if (res && res.ok) {
          this.agents = await res.json();
        }
      } catch (e) {
        // best-effort, ignore errors
      } finally {
        this.agentsLoading = false;
      }
    },
    agentStatusBadgeClass(status) {
      return status === 'RUNNING' ? 'badge bg-success' : 'badge bg-warning text-dark';
    },
    agentStatusText(status) {
      const map = { RUNNING: 'agentStatusRunning', STOPPED: 'agentStatusStopped', STARTING: 'agentStatusStarting', STOPPING: 'agentStatusStopping' };
      return this.t('settings.' + (map[status] || 'agentStatusStopped'));
    },
    fmtSize,
    formatUptime(seconds) {
      if (!seconds && seconds !== 0) { return '-'; }
      const d = Math.floor(seconds / 86400);
      const h = Math.floor((seconds % 86400) / 3600);
      const m = Math.floor((seconds % 3600) / 60);
      if (d > 0) { return d + this.t('settings.unitDay') + ' ' + h + this.t('settings.unitHour') + ' ' + m + this.t('settings.unitMin'); }
      if (h > 0) { return h + this.t('settings.unitHour') + ' ' + m + this.t('settings.unitMin'); }
      return m + this.t('settings.unitMin');
    },
    formatSession(val) {
      if (!val) { return '-'; }
      const m = val.match(/^(\d+)([dhms])$/);
      if (!m) { return val; }
      const n = parseInt(m[1]);
      const units = { d: 'settings.unitDay', h: 'settings.unitHour', m: 'settings.unitMin', s: 'settings.unitSec' };
      return n + ' ' + this.t(units[m[2]] || m[2]);
    },
    formatNum(v) {
      return v != null ? v.toLocaleString() : '-';
    },
    formatMB(bytes) {
      if (!bytes) { return '-'; }
      if (bytes < 1024 * 1024) { return (bytes / 1024).toFixed(0) + ' KB'; }
      return (bytes / 1024 / 1024).toFixed(1) + ' MB';
    },
    formatHeap() {
      const used = this.status?.jvmHeapUsed || 0;
      const max = this.status?.jvmHeapMax || 0;
      const pct = max > 0 ? Math.round(used / max * 100) : 0;
      return this.formatMB(used) + ' / ' + this.formatMB(max) + ' (' + pct + '%)';
    },
    formatCache(name) {
      const cache = this.status?.ruleCaches?.[name];
      if (!cache) { return '-'; }
      const hitRate = cache.requestCount > 0 ? (cache.hitRate * 100).toFixed(1) + '%' : '-';
      return this.t('settings.cacheStatsValue', {
        entries: this.formatNum(cache.entries),
        hitRate,
        evictions: this.formatNum(cache.evictionCount)
      });
    }
  },
  template: /* html */`
    <div class="page workspace-page settings-workspace" :class="{active:true}">
      <div class="page-header">
        <div class="page-heading">
          <h1 class="page-title">{{t('settings.title')}}</h1>
        </div>
        <ui-button variant="secondary" size="compact" @click="refreshStatus" :disabled="isAdmin ? resourceLoading : loading.status"><i class="bi bi-arrow-clockwise" :class="{'spin':isAdmin ? resourceLoading : loading.status}"></i> {{t('settings.refresh')}}</ui-button>
      </div>
      <div class="page-scroll">
      <div v-if="status" class="settings-context"><ui-badge v-if="status.envLabel" tone="neutral">{{status.envLabel}}</ui-badge><span>{{t('settings.version')}} {{status.version}}</span><span>{{t('settings.uptime')}} {{formatUptime(status.uptime)}}</span><span>HTTP {{status.serverPort}}</span></div>
      <ui-tabs class="settings-tabs" v-model="activeTab" :items="tabs" :aria-label="t('settings.title')"></ui-tabs>
      <section id="settings-content" class="settings-tab-panel" role="tabpanel" :aria-labelledby="'settings-tab-' + activeTab" tabindex="0">
      <resource-monitoring-panel ref="resources" v-if="isAdmin" v-show="activeTab === 'overview' || activeTab === 'monitoring'" :view="activeTab" :backup-enabled="backupStatus?.enabled ?? null" :rule-caches="status?.ruleCaches" :refresh-token="resourceRefreshToken" @loading="resourceLoading=$event" @snapshot="updateResourceSnapshot" @navigate="navigateTab"></resource-monitoring-panel>
      <p v-if="activeTab !== 'overview' && activeTab !== 'monitoring'" class="settings-inline-note settings-manual-note">{{t('settings.manualRefreshHint')}}</p>
      <!-- Skeleton -->
      <div v-if="!status && activeTab !== 'monitoring' && (activeTab !== 'overview' || !isAdmin)" class="settings-grid loading-reveal">
        <div class="settings-card" v-for="i in 6" :key="'sk-'+i">
          <div class="settings-card-header"><span class="sk sk-text sk-w-120"></span></div>
          <div class="settings-card-body">
            <div class="sk-row" v-for="j in 4" :key="'skr-'+i+'-'+j">
              <span class="sk sk-text sk-w-80"></span>
              <span class="sk sk-text sk-w-120"></span>
            </div>
          </div>
        </div>
      </div>

      <!-- Content -->
      <template v-if="status">
      <div class="settings-grid">
        <div v-show="activeTab === 'service'" class="settings-card">
          <div class="settings-card-header"><i class="bi bi-info-circle"></i> {{t('settings.serviceInfo')}}</div>
          <div class="settings-card-body">
            <div class="settings-item"><span class="settings-label">{{t('settings.version')}}</span><span class="settings-value">{{ status.version }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.httpPort')}}</span><span class="settings-value">{{ status.serverPort }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.envLabel')}}</span><span class="settings-value">{{ status.envLabel || t('settings.notSet') }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.sessionTimeout')}}</span><span class="settings-value">{{ formatSession(status.sessionTimeout) }}</span></div>
            <div class="settings-item" v-if="status.username"><span class="settings-label">{{t('settings.currentUser')}}</span><span class="settings-value">{{status.username}}</span></div>
          </div>
        </div>
        <div class="settings-card settings-card-wide connection-settings-section connection-http-section" v-if="isAdmin" v-show="activeTab === 'connections'">
          <div class="settings-card-header settings-card-header-actions">
            <span><i class="bi bi-globe2"></i> {{t('settings.httpTargets')}}</span>
            <ui-button variant="primary" size="compact" @click="openCreateHttpTarget"><i class="bi bi-plus-lg"></i> {{t('settings.httpTargetAdd')}}</ui-button>
          </div>
          <div class="settings-card-body">
            <div v-if="httpTargetsLoading" class="sub-info">{{t('settings.httpTargetLoading')}}</div>
            <div v-else-if="!httpTargets.length" class="connection-empty">
              <i class="bi bi-info-circle"></i>
              <div class="connection-empty-copy"><span class="connection-guidance-label">{{t('settings.connectionStatusLabel')}}</span><strong>{{t('settings.httpTargetEmpty')}}</strong></div>
            </div>
            <connection-targets-table v-if="httpTargets.length" protocol="http" :targets="httpTargets" :test-results="httpTargetTestResults" :testing-id="httpTargetTestingId" @test="testHttpTarget" @edit="openEditHttpTarget" @make-default="makeDefaultHttpTarget" @delete="deleteHttpTarget"></connection-targets-table>
            <div class="connection-guidance">
              <div><span class="connection-guidance-label">{{t('settings.connectionApplyLabel')}}</span><span>{{t('settings.httpTargetSwitchHint')}}</span></div>
              <div><span class="connection-guidance-label">{{t('settings.connectionFallbackLabel')}}</span><span>{{t('settings.httpTargetFallbackHint')}}</span></div>
            </div>
          </div>
        </div>
        <div class="settings-card settings-card-wide connection-settings-section connection-jms-section" v-if="isAdmin" v-show="activeTab === 'connections'">
          <div class="settings-card-header settings-card-header-actions">
            <span><i class="bi bi-diagram-2"></i> {{t('settings.jmsTargets')}}</span>
            <ui-button variant="primary" size="compact" @click="openCreateJmsTarget"><i class="bi bi-plus-lg"></i> {{t('settings.jmsTargetAdd')}}</ui-button>
          </div>
          <div class="settings-card-body">
            <div v-if="jmsTargetsLoading" class="sub-info">{{t('settings.jmsTargetLoading')}}</div>
            <div v-else-if="!jmsTargets.length" class="connection-empty">
              <i class="bi bi-info-circle"></i>
              <div class="connection-empty-copy"><span class="connection-guidance-label">{{t('settings.connectionSourceLabel')}}</span><strong>{{t('settings.jmsTargetEmpty')}}</strong></div>
            </div>
            <connection-targets-table v-if="jmsTargets.length" protocol="jms" :targets="jmsTargets" :test-results="jmsTargetTestResults" :testing-id="jmsTargetTestingId" :yaml-jms-target-configured="yamlJmsTargetConfigured" @test="testJmsTarget" @edit="openEditJmsTarget" @make-default="makeDefaultJmsTarget" @delete="deleteJmsTarget"></connection-targets-table>
            <div class="connection-guidance">
              <div v-if="jmsTargets.length"><span class="connection-guidance-label">{{t('settings.connectionSourceLabel')}}</span><span v-if="activeJmsTarget" class="connection-source-value"><strong>{{jmsTargetDisplayName(activeJmsTarget)}}</strong> · {{activeJmsTarget.serverUrl}} · {{activeJmsTarget.queueName}}</span><span v-else>{{t('settings.jmsTargetNoDefault')}}</span></div>
              <div><span class="connection-guidance-label">{{t('settings.connectionPriorityLabel')}}</span><span>{{t('settings.jmsTargetPriorityHint')}}</span></div>
              <div><span class="connection-guidance-label">{{t('settings.connectionApplyLabel')}}</span><span>{{t('settings.jmsTargetSwitchHint')}}</span></div>
              <div><span class="connection-guidance-label">{{t('settings.connectionReconnectLabel')}}</span><span>{{t('settings.jmsTargetReconnectHint')}}</span></div>
            </div>
          </div>
        </div>
        <div v-show="activeTab === 'service'" class="settings-card">
          <div class="settings-card-header"><i class="bi bi-diagram-3"></i> {{t('settings.protocolSettings')}}</div>
          <div class="settings-card-body">
            <div class="settings-item"><span class="settings-label">{{t('settings.httpAlias')}}</span><span class="settings-value">{{ status.httpAlias || t('settings.notSet') }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.jmsAlias')}}</span><span class="settings-value">{{ status.jmsAlias || t('settings.notSet') }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.jmsStatus')}}</span><span class="settings-value"><ui-status :tone="jmsEnabled?'success':'neutral'">{{ jmsEnabled ? t('settings.enabled') : t('settings.disabled') }}</ui-status></span></div>
            <div class="settings-item" v-if="jmsEnabled"><span class="settings-label">{{t('settings.artemisUrl')}}</span><span class="settings-value settings-value-sm">{{ status.artemisBrokerUrl }}</span></div>
          </div>
        </div>
        <div v-show="activeTab === 'data'" class="settings-card">
          <div class="settings-card-header"><i class="bi bi-database"></i> {{t('settings.dataStorage')}}</div>
          <div class="settings-card-body">
            <div class="settings-item"><span class="settings-label">{{t('settings.database')}}</span><span class="settings-value">{{databaseKind}}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.ruleRetention')}}</span><span class="settings-value">{{ status.cleanupRetentionDays ?? 180 }} {{t('settings.days')}}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.responseRetention')}}</span><span class="settings-value">{{ status.responseRetentionDays ?? 180 }} {{t('settings.days')}}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.auditRetention')}}</span><span class="settings-value">{{ status.auditRetentionDays ?? 30 }} {{t('settings.days')}}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.statsMaxRecords')}}</span><span class="settings-value">{{ formatNum(status.statsMaxRecords) }} {{t('settings.unit')}}</span></div>
            <template v-if="isAdmin"><div class="settings-item"><span class="settings-label">{{t('monitoring.metrics.dataFreeBytes')}}</span><span class="settings-value">{{storageMetric('dataFreeBytes')}}</span></div><div class="settings-item"><span class="settings-label">{{t('monitoring.metrics.backupFreeBytes')}}</span><span class="settings-value">{{storageMetric('backupFreeBytes')}}</span></div><p class="settings-inline-note" v-if="resourceSnapshot">{{t('monitoring.collectedAt')}} {{new Date(resourceSnapshot.collectedAt).toLocaleString()}}<span v-if="resourceFailed" class="resource-monitoring-error"> · {{t('monitoring.stale')}}</span></p></template>
          </div>
        </div>
        <div v-show="activeTab === 'service'" class="settings-card">
          <div class="settings-card-header"><i class="bi bi-shield-lock"></i> {{t('settings.authSettings')}}</div>
          <div class="settings-card-body">
            <div class="settings-item"><span class="settings-label">{{t('settings.ldapStatus')}}</span><span class="settings-value"><ui-status :tone="status.ldapEnabled?'success':'neutral'">{{ status.ldapEnabled ? t('settings.enabled') : t('settings.disabled') }}</ui-status></span></div>
            <div class="settings-item" v-if="status.ldapEnabled"><span class="settings-label">{{t('settings.ldapUrl')}}</span><span class="settings-value settings-value-sm">{{ status.ldapUrl }}</span></div>
            <div class="settings-item" v-if="!status.ldapEnabled"><span class="settings-label">{{t('settings.authMode')}}</span><span class="settings-value">{{ status.version === 'dev' ? t('settings.devMode') : t('settings.localAuth') }}</span></div>
          </div>
        </div>
        <div v-show="activeTab === 'data'" class="settings-card">
          <div class="settings-card-header"><i class="bi bi-bar-chart"></i> {{t('settings.dataStats')}}</div>
          <div class="settings-card-body">
            <div class="settings-item"><span class="settings-label">{{t('settings.ruleCount')}}</span><span class="settings-value">{{ formatNum(status.ruleCount) }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.responseCount')}}</span><span class="settings-value">{{ formatNum(status.responseCount) }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.requestLogCount')}}</span><span class="settings-value">{{ formatNum(status.requestLogCount) }}</span></div>
            <div class="settings-item" v-if="status.orphanRules != null"><span class="settings-label">{{t('settings.orphanRules')}}</span><span class="settings-value">{{ formatNum(status.orphanRules) }}</span></div>
            <div class="settings-item" v-if="status.orphanResponses != null"><span class="settings-label">{{t('settings.orphanResponses')}}</span><span class="settings-value">{{ formatNum(status.orphanResponses) }}</span></div>
            <div class="settings-item" v-if="status.dbFileSize"><span class="settings-label">{{t('settings.dbFileSize')}}</span><span class="settings-value">{{ formatMB(status.dbFileSize) }}</span></div>
          </div>
        </div>
        <div v-if="!isAdmin" v-show="activeTab === 'overview'" class="settings-card">
          <div class="settings-card-header"><i class="bi bi-cpu"></i> {{t('settings.systemInfo')}}</div>
          <div class="settings-card-body">
            <div class="settings-item"><span class="settings-label">{{t('settings.jvmHeap')}}</span><span class="settings-value">{{ formatHeap() }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.httpRuleCache')}}</span><span class="settings-value settings-value-sm">{{ formatCache('httpRules') }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.jmsRuleCache')}}</span><span class="settings-value settings-value-sm">{{ formatCache('jmsRules') }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.uptime')}}</span><span class="settings-value">{{ formatUptime(status.uptime) }}</span></div>
            <div class="settings-item" v-if="status.username"><span class="settings-label">{{t('settings.currentUser')}}</span><span class="settings-value">{{ status.username }}</span></div>
          </div>
        </div>
        <div v-show="activeTab === 'service'" class="settings-card">
          <div class="settings-card-header"><i class="bi bi-robot"></i> {{t('settings.agentStatus')}}</div>
          <div class="settings-card-body" v-if="agents.length">
            <template v-for="(a, idx) in agents" :key="a.name">
              <div v-if="idx > 0" class="settings-card-divider"></div>
              <div class="settings-item"><span class="settings-label">{{t('settings.agentName')}}</span><span class="settings-value settings-agent-name">{{ a.name }} <span :class="agentStatusBadgeClass(a.status)" class="inline-badge"><i v-if="a.status !== 'RUNNING'" class="bi bi-exclamation-triangle me-1"></i>{{ agentStatusText(a.status) }}</span></span></div>
              <p class="settings-inline-note" v-if="a.description">{{ a.name==='log-agent' ? t('settings.agentLogDescription') : a.description }}</p>
              <div class="settings-item"><span class="settings-label">{{t('settings.agentQueueSize')}}</span><span class="settings-value">{{ formatNum(a.queueSize) }}</span></div>
              <div class="settings-item"><span class="settings-label">{{t('settings.agentProcessed')}}</span><span class="settings-value">{{ formatNum(a.processedCount) }}</span></div>
              <div class="settings-item"><span class="settings-label">{{t('settings.agentDropped')}}</span><span class="settings-value">{{ formatNum(a.droppedCount) }}</span></div>
            </template>
          </div>
          <div class="settings-card-body" v-else>
            <div class="settings-item"><span class="sub-info">{{t('settings.agentNoAgents')}}</span></div>
          </div>
        </div>
        <div v-show="activeTab === 'data'" class="settings-card settings-card-wide settings-backup-card">
          <div class="settings-card-header settings-card-header-actions"><span>{{t('settings.dbBackup')}}</span><ui-button v-if="backupStatus?.enabled" variant="primary" size="compact" @click="$emit('trigger-backup')" :disabled="loading.backup"><i v-if="loading.backup" class="bi bi-arrow-clockwise spin" aria-hidden="true"></i>{{t('settings.backupNow')}}</ui-button></div>
          <div class="settings-card-body" v-if="backupStatus?.enabled">
            <div class="settings-backup-meta"><span>{{t('settings.backupEnabled')}}</span><span>{{t('settings.schedule')}} <code>{{backupStatus.cron}}</code></span><span>{{t('settings.retentionDays')}} {{backupStatus.retentionDays}} {{t('settings.days')}}</span><span>{{t('settings.path')}} <code>{{backupStatus.path}}</code></span></div>
            <table v-if="backupStatus.files?.length" class="settings-table settings-backup-table"><thead><tr><th scope="col">{{t('settings.backupList')}}</th><th scope="col" class="settings-number">{{t('settings.fileSize')}}</th></tr></thead><tbody><tr v-for="f in backupStatus.files" :key="f.name"><td><code>{{f.name}}</code></td><td class="settings-number">{{fmtSize(f.size)}}</td></tr></tbody></table>
            <p v-else class="settings-inline-note settings-backup-empty">{{t('settings.backupNoFiles')}}</p>
            <p class="settings-section-note">{{t('settings.backupVerificationHint')}}</p>
          </div>
          <div class="settings-card-body" v-else-if="backupStatus">
            <div class="settings-item"><span class="settings-label">{{t('settings.status')}}</span><span class="settings-value"><ui-status tone="neutral">{{t('settings.backupDisabled')}}</ui-status></span></div>
            <div class="settings-item settings-item--stacked">
              <span class="settings-label settings-label--spaced">{{t('settings.enableMethod')}}</span>
              <pre class="sub-info settings-config-example">echo:
  backup:
    enabled: true
    cron: "0 0 3 * * *"
    path: ./backups
    retention-days: 7</pre>
            </div>
            <p class="settings-inline-note"><i class="bi bi-info-circle" aria-hidden="true"></i> {{t('settings.h2OnlyNote')}}</p>
          </div>
          <div class="settings-card-body" v-else><p class="settings-inline-note">{{t('monitoring.unavailable')}}</p></div>
        </div>
        <div v-if="scenarioEnabled" v-show="activeTab === 'service'" class="settings-card settings-scenario-card">
          <div class="settings-card-header settings-card-header-actions">
            <span><i class="bi bi-diagram-3" aria-hidden="true"></i> {{t('settings.scenarios')}}</span>
            <ui-button v-if="scenarios.length && !scenariosError" type="button" class="btn btn-xs btn-secondary" @click="resetAllScenarios" :disabled="scenarioResetting!==null">
              <i class="bi" :class="scenarioResetting==='*'?'bi-arrow-clockwise spin':'bi-arrow-counterclockwise'" aria-hidden="true"></i>{{t('settings.resetAllScenarios')}}
            </ui-button>
          </div>
          <div class="settings-card-body scenario-settings-body" aria-live="polite">
            <div v-if="scenariosLoading" class="settings-state-row"><i class="bi bi-arrow-clockwise spin" aria-hidden="true"></i><span>{{t('settings.scenariosLoading')}}</span></div>
            <div v-else-if="scenariosError" class="settings-state-row is-error"><i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>{{t('settings.scenariosLoadFailed')}}</span><ui-button type="button" class="btn btn-xs btn-secondary" @click="loadScenarios">{{t('common.retry')}}</ui-button></div>
            <div v-else-if="scenarios.length" class="scenario-list">
              <div v-for="s in scenarios" :key="s.scenarioName" class="scenario-row">
                <div class="scenario-identity"><strong>{{s.scenarioName}}</strong><span>{{t('settings.currentScenarioState')}}</span></div>
                <code class="scenario-state">{{s.currentState}}</code>
                <ui-button type="button" class="btn btn-xs btn-secondary" @click="resetScenario(s.scenarioName)" :disabled="scenarioResetting!==null" :aria-label="t('settings.scenarioResetNamed', {name:s.scenarioName})">
                  <i class="bi" :class="scenarioResetting===s.scenarioName?'bi-arrow-clockwise spin':'bi-arrow-counterclockwise'" aria-hidden="true"></i>{{t('settings.resetScenario')}}
                </ui-button>
              </div>
            </div>
            <div v-else class="settings-state-row"><i class="bi bi-diagram-3" aria-hidden="true"></i><span>{{t('settings.noScenarios')}}</span></div>
          </div>
        </div>
        <details v-show="activeTab === 'data'" class="settings-card settings-card-wide settings-danger-zone settings-disclosure">
          <summary class="settings-card-header settings-danger-zone-header"><i class="bi bi-chevron-right settings-disclosure-chevron" aria-hidden="true"></i> {{t('settings.dangerZone')}}<span class="settings-disclosure-meta">{{t('settings.dangerHint')}}</span></summary>
          <div class="settings-card-body settings-danger-actions">
            <div class="settings-danger-warning"><i class="bi bi-info-circle" aria-hidden="true"></i><span>{{t('settings.dangerHint')}}</span></div>
            <ui-button variant="danger" class="settings-danger-action" @click="$emit('delete-all-rules', status.ruleCount)"><i class="bi bi-trash"></i> {{t('settings.deleteAllRules')}}</ui-button>
            <ui-button variant="danger" class="settings-danger-action" @click="$emit('delete-all-responses')"><i class="bi bi-trash"></i> {{t('settings.deleteAllResponses')}}</ui-button>
            <ui-button variant="danger" class="settings-danger-action" @click="$emit('delete-orphan-responses')"><i class="bi bi-trash"></i> {{t('settings.deleteOrphanResponses')}}</ui-button>
            <ui-button variant="danger" class="settings-danger-action" @click="$emit('delete-all-audit')"><i class="bi bi-trash"></i> {{t('settings.deleteAllAudit')}}</ui-button>
            <ui-button variant="danger" class="settings-danger-action" @click="$emit('delete-all-logs')"><i class="bi bi-trash"></i> {{t('settings.deleteAllLogs')}}</ui-button>
          </div>
        </details>
        <div v-show="activeTab === 'service'" class="settings-card">
          <div class="settings-card-header">{{t('settings.configurationAndMonitoring')}}</div>
          <div class="settings-card-body"><p class="settings-inline-note">{{t('settings.configHint')}}</p><p class="settings-inline-note">{{t('monitoring.configHint')}}</p><p class="settings-inline-note">{{t('settings.manualRefreshHint')}}</p></div>
        </div>
        <details v-show="activeTab === 'service'" class="settings-card settings-card-wide settings-disclosure"><summary class="settings-card-header"><i class="bi bi-chevron-right settings-disclosure-chevron" aria-hidden="true"></i>{{t('settings.databaseConnectionDetails')}}<span class="settings-disclosure-meta">{{databaseKind}}</span></summary><div class="settings-card-body"><code class="settings-database-url">{{status.datasourceUrl}}</code></div></details>
      </div>
      </template>
      </section>
      <ui-modal-transition>
      <div v-if="showHttpTargetForm" class="modal-overlay" @click.self="showHttpTargetForm=false" @keydown="onConnectionDialogKeydown($event, 'http')">
        <div class="modal-box workspace-modal connection-form-modal connection-http-form-modal" role="dialog" aria-modal="true" aria-labelledby="httpTargetFormTitle" tabindex="-1">
          <div class="modal-header"><h2 id="httpTargetFormTitle"><i class="bi bi-globe2"></i> {{editingHttpTarget?t('settings.httpTargetEdit'):t('settings.httpTargetAdd')}}</h2><ui-button type="button" variant="quiet" size="compact" icon-only class="modal-close" @click="showHttpTargetForm=false" :aria-label="t('rules.close')" :title="t('rules.close')"><i class="bi bi-x-lg"></i></ui-button></div>
          <div class="modal-body">
            <div class="form-row"><div class="form-group"><label class="form-label" for="httpTargetName">{{t('settings.httpTargetName')}} <span class="required">*</span></label><input id="httpTargetName" class="form-control" v-model="httpTargetForm.name" maxlength="100" required></div><div class="form-group"><label class="form-label" for="httpAuthType">{{t('settings.httpAuthType')}}</label><select id="httpAuthType" class="form-control" v-model="httpTargetForm.authType"><option value="NONE">{{t('settings.authNone')}}</option><option value="BASIC">{{t('settings.authBasic')}}</option><option value="BEARER">{{t('settings.authBearerToken')}}</option></select></div></div>
            <div class="form-group"><label class="form-label" for="httpTargetBaseUrl">{{t('settings.httpTargetBaseUrl')}} <span class="required">*</span></label><input id="httpTargetBaseUrl" class="form-control" v-model="httpTargetForm.baseUrl" placeholder="https://internal-api.example.com" autocomplete="url" spellcheck="false" required></div>
            <div v-if="httpTargetForm.authType!=='NONE'" class="form-row"><div v-if="httpTargetForm.authType==='BASIC'" class="form-group"><label class="form-label" for="httpTargetUsername">{{t('settings.httpTargetUsername')}}</label><input id="httpTargetUsername" class="form-control" v-model="httpTargetForm.username" autocomplete="off"></div><div class="form-group"><label class="form-label" for="httpTargetSecret">{{httpTargetForm.authType==='BEARER'?t('settings.authToken'):t('settings.httpTargetSecret')}}</label><input id="httpTargetSecret" type="password" class="form-control" v-model="httpTargetForm.secret" autocomplete="new-password" :placeholder="editingHttpTarget&&editingHttpTarget.secretConfigured?t('settings.httpTargetSecretKeep'):''"></div></div>
            <label v-if="editingHttpTarget&&editingHttpTarget.secretConfigured" class="form-check"><input type="checkbox" v-model="httpTargetForm.clearSecret"> {{t('settings.httpTargetClearSecret')}}</label>
            <div class="form-row"><div class="form-group"><label class="form-label" for="httpConnectTimeout">{{t('settings.httpConnectTimeout')}}</label><input id="httpConnectTimeout" type="number" min="1" max="300" class="form-control" v-model.number="httpTargetForm.connectTimeoutSeconds"></div><div class="form-group"><label class="form-label" for="httpReadTimeout">{{t('settings.httpReadTimeout')}}</label><input id="httpReadTimeout" type="number" min="1" max="300" class="form-control" v-model.number="httpTargetForm.readTimeoutSeconds"></div></div>
            <div class="connection-form-options"><label class="form-check"><input type="checkbox" v-model="httpTargetForm.enabled" :disabled="editingHttpTarget?.defaultConnection" @change="onHttpTargetEnabledChange"> {{t('settings.enabled')}}</label><label class="form-check"><input type="checkbox" v-model="httpTargetForm.defaultConnection" :disabled="!httpTargetForm.enabled||editingHttpTarget?.defaultConnection"> {{t('settings.httpTargetDefault')}}</label></div>
            <div class="form-group connection-form-section">
              <label class="form-label">{{t('settings.tlsModeLabel')}}</label>
              <ui-choice-group class="protocol-switch" option-class="protocol-btn" variant="compact"
                v-model="httpTargetForm.tlsVerificationEnabled" :options="tlsModeOptions"
                :aria-label="t('settings.tlsModeLabel')"></ui-choice-group>
              <div class="sub-info connection-form-hint"><i class="bi bi-info-circle"></i> {{httpTargetForm.tlsVerificationEnabled?t('settings.tlsVerificationStrictHint'):t('settings.tlsVerificationCompatibilityHint')}}</div>
            </div>
          </div>
          <div class="modal-footer"><ui-button variant="quiet" @click="showHttpTargetForm=false">{{t('rules.close')}}</ui-button><ui-button class="btn btn-primary" @click="saveHttpTarget" :disabled="httpTargetSaving||!canSaveHttpTarget"><i class="bi bi-check-lg"></i> {{t('modal.save')}}</ui-button></div>
        </div>
      </div>
      </ui-modal-transition>
      <ui-modal-transition>
      <div v-if="showJmsTargetForm" class="modal-overlay" @click.self="showJmsTargetForm=false" @keydown="onConnectionDialogKeydown($event, 'jms')">
        <div class="modal-box workspace-modal connection-form-modal connection-jms-form-modal" role="dialog" aria-modal="true" aria-labelledby="jmsTargetFormTitle" tabindex="-1">
          <div class="modal-header"><h2 id="jmsTargetFormTitle"><i class="bi bi-diagram-2"></i> {{editingJmsTarget?t('settings.jmsTargetEdit'):t('settings.jmsTargetAdd')}}</h2><ui-button type="button" variant="quiet" size="compact" icon-only class="modal-close" @click="showJmsTargetForm=false" :aria-label="t('rules.close')" :title="t('rules.close')"><i class="bi bi-x-lg"></i></ui-button></div>
          <div class="modal-body">
            <div class="form-row"><div class="form-group"><label class="form-label" for="jmsTargetName">{{t('settings.jmsTargetName')}} <span class="required">*</span></label><input id="jmsTargetName" class="form-control" v-model="jmsTargetForm.name" maxlength="100" required></div><div class="form-group"><label class="form-label" for="jmsTargetProvider">{{t('settings.jmsTargetProvider')}}</label><select id="jmsTargetProvider" class="form-control" v-model="jmsTargetForm.providerType"><option value="artemis">Artemis</option><option value="tibco">TIBCO EMS</option></select></div></div>
            <div class="form-group"><label class="form-label" for="jmsTargetServerUrl">{{t('settings.jmsTargetServerUrl')}} <span class="required">*</span></label><input id="jmsTargetServerUrl" class="form-control" v-model="jmsTargetForm.serverUrl" placeholder="tcp://host:61616" autocomplete="url" spellcheck="false" required></div>
            <div class="form-row"><div class="form-group"><label class="form-label" for="jmsTargetUsername">{{t('settings.jmsTargetUsername')}}</label><input id="jmsTargetUsername" class="form-control" v-model="jmsTargetForm.username" autocomplete="off"></div><div class="form-group"><label class="form-label" for="jmsTargetPassword">{{t('settings.jmsTargetPassword')}}</label><input id="jmsTargetPassword" type="password" class="form-control" v-model="jmsTargetForm.password" autocomplete="new-password" :placeholder="editingJmsTarget&&editingJmsTarget.passwordConfigured?t('settings.jmsTargetPasswordKeep'):''"></div></div>
            <label v-if="editingJmsTarget&&editingJmsTarget.passwordConfigured" class="form-check"><input type="checkbox" v-model="jmsTargetForm.clearPassword"> {{t('settings.jmsTargetClearPassword')}}</label>
            <div class="form-row"><div class="form-group"><label class="form-label" for="jmsTargetQueue">{{t('settings.jmsTargetQueue')}} <span class="required">*</span></label><input id="jmsTargetQueue" class="form-control" v-model="jmsTargetForm.queueName" spellcheck="false" required></div><div class="form-group"><label class="form-label" for="jmsTargetTimeout">{{t('settings.jmsTargetTimeout')}}</label><input id="jmsTargetTimeout" type="number" min="1" max="300" class="form-control" v-model.number="jmsTargetForm.timeoutSeconds"></div></div>
            <div class="connection-form-options"><label class="form-check"><input type="checkbox" v-model="jmsTargetForm.enabled" :disabled="editingJmsTarget?.defaultConnection" @change="onJmsTargetEnabledChange"> {{t('settings.enabled')}}</label><label class="form-check"><input type="checkbox" v-model="jmsTargetForm.defaultConnection" :disabled="!jmsTargetForm.enabled||editingJmsTarget?.defaultConnection"> {{t(yamlJmsTargetConfigured?'settings.jmsTargetFallbackDefault':'settings.jmsTargetDefault')}}</label></div>
          </div>
          <div class="modal-footer"><ui-button variant="quiet" @click="showJmsTargetForm=false">{{t('rules.close')}}</ui-button><ui-button class="btn btn-primary" @click="saveJmsTarget" :disabled="jmsTargetSaving||!canSaveJmsTarget"><i class="bi bi-check-lg"></i> {{t('modal.save')}}</ui-button></div>
        </div>
      </div>
      </ui-modal-transition>
      </div>
    </div>
  `
};
