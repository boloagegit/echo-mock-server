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
      agentsLoading: false,
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
    this.loadScenarios();
  },
  watch: {
    status() {
      this.loadAgents();
      if (this.isAdmin) { this.loadJmsTargets(); this.loadHttpTargets(); }
      this.loadScenarios();
    }
  },
  computed: {
    canSaveHttpTarget() {
      return Boolean(this.httpTargetForm.name.trim() && this.httpTargetForm.baseUrl.trim());
    },
    canSaveJmsTarget() {
      const form = this.jmsTargetForm;
      return Boolean(form.name.trim() && form.serverUrl.trim() && form.queueName.trim());
    },
  },
  methods: {
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
      return {
        name: '', providerType: 'artemis', serverUrl: '', username: '', password: '',
        clearPassword: false, queueName: 'TARGET.REQUEST', timeoutSeconds: 30,
        enabled: true, defaultConnection: this.jmsTargets.length === 0, version: null,
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
        this.notify(this.t('settings.jmsTargetDefaultChanged'));
      }
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
          <span class="settings-page-meta" v-if="status">v{{status.version}} · {{t('settings.configHint')}}</span>
        </div>
        <button class="btn btn-secondary" @click="$emit('refresh-status')" :disabled="loading.status"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.status}"></i> {{t('settings.refresh')}}</button>
      </div>
      <div class="page-scroll">
      <!-- Skeleton -->
      <div v-if="!status" class="settings-grid">
        <div class="settings-card" v-for="i in 6" :key="'sk-'+i">
          <div class="settings-card-header"><span class="sk sk-text" style="width:120px"></span></div>
          <div class="settings-card-body">
            <div class="sk-row" v-for="j in 4" :key="'skr-'+i+'-'+j">
              <span class="sk sk-text" style="width:80px"></span>
              <span class="sk sk-text" style="width:120px"></span>
            </div>
          </div>
        </div>
      </div>

      <!-- Content -->
      <template v-else>
      <div class="settings-grid">
        <div class="settings-card settings-card-wide settings-overview-card">
          <div class="settings-card-header"><i class="bi bi-info-circle"></i> {{t('settings.serviceInfo')}}</div>
          <div class="settings-card-body">
            <div class="settings-item"><span class="settings-label">{{t('settings.version')}}</span><span class="settings-value">{{ status.version }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.httpPort')}}</span><span class="settings-value">{{ status.serverPort }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.envLabel')}}</span><span class="settings-value">{{ status.envLabel || t('settings.notSet') }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.sessionTimeout')}}</span><span class="settings-value">{{ formatSession(status.sessionTimeout) }}</span></div>
          </div>
        </div>
        <div class="settings-card settings-card-wide connection-settings-section connection-http-section" v-if="isAdmin">
          <div class="settings-card-header settings-card-header-actions">
            <span><i class="bi bi-globe2"></i> {{t('settings.httpTargets')}}</span>
            <button class="btn btn-sm btn-primary" @click="openCreateHttpTarget"><i class="bi bi-plus-lg"></i> {{t('settings.httpTargetAdd')}}</button>
          </div>
          <div class="settings-card-body">
            <div v-if="httpTargetsLoading" class="sub-info">{{t('settings.httpTargetLoading')}}</div>
            <div v-else-if="!httpTargets.length" class="connection-empty">
              <i class="bi bi-info-circle"></i>
              <div class="connection-empty-copy"><span class="connection-guidance-label">{{t('settings.connectionStatusLabel')}}</span><strong>{{t('settings.httpTargetEmpty')}}</strong></div>
            </div>
            <div v-for="target in httpTargets" :key="target.id" class="connection-row">
              <div class="connection-identity"><div class="connection-name">{{target.name}}</div><div class="sub-info">{{target.authType}} · {{target.connectTimeoutSeconds}}s / {{target.readTimeoutSeconds}}s</div></div>
              <div class="connection-details">
                <div class="settings-value-sm connection-address">{{target.baseUrl}}</div>
                <div class="sub-info connection-badges">
                  <span :class="target.enabled?'status-on':'status-off'">{{target.enabled?t('settings.enabled'):t('settings.disabled')}}</span>
                  <span v-if="target.defaultConnection" class="badge bg-primary">{{t('settings.httpTargetDefault')}}</span>
                  <span class="badge" :class="target.tlsVerificationEnabled?'bg-success':'bg-secondary'">{{target.tlsVerificationEnabled?t('settings.tlsModeStrict'):t('settings.tlsModeCompatibility')}}</span>
                  <span v-if="target.secretConfigured"><i class="bi bi-key"></i></span>
                </div>
                <div v-if="httpTargetTestResults[target.id]" class="sub-info connection-test-result" :class="httpTargetTestResults[target.id].success?'success':'error'">
                  <i class="bi" :class="httpTargetTestResults[target.id].success?'bi-check-circle':'bi-x-circle'"></i>
                  {{t('settings.lastConnectionTest')}} {{httpTargetTestResults[target.id].testedAt}} ·
                  <template v-if="httpTargetTestResults[target.id].success">HTTP {{httpTargetTestResults[target.id].status}} · {{httpTargetTestResults[target.id].elapsedMs}} ms</template>
                  <template v-else>{{httpTargetTestResults[target.id].error || '-'}}</template>
                </div>
              </div>
              <div class="connection-actions">
                <button class="btn btn-xs btn-secondary" @click="testHttpTarget(target)" :disabled="httpTargetTestingId===target.id"><i class="bi bi-plug" :class="{'spin':httpTargetTestingId===target.id}"></i> {{t('settings.httpTargetTest')}}</button>
                <button v-if="!target.defaultConnection" class="btn btn-xs btn-secondary" @click="makeDefaultHttpTarget(target)" :disabled="!target.enabled"><i class="bi bi-check-circle"></i> {{t('settings.httpTargetSetDefault')}}</button>
                <button class="btn btn-xs btn-secondary" @click="openEditHttpTarget(target)"><i class="bi bi-pencil"></i> {{t('rules.edit')}}</button>
                <button class="btn btn-xs btn-outline-danger" @click="deleteHttpTarget(target)" :disabled="target.defaultConnection" :title="target.defaultConnection?t('settings.defaultConnectionDeleteDisabled'):t('rules.delete')"><i class="bi bi-trash"></i> {{t('rules.delete')}}</button>
              </div>
            </div>
            <div class="connection-guidance">
              <div><span class="connection-guidance-label">{{t('settings.connectionApplyLabel')}}</span><span>{{t('settings.httpTargetSwitchHint')}}</span></div>
              <div><span class="connection-guidance-label">{{t('settings.connectionFallbackLabel')}}</span><span>{{t('settings.httpTargetFallbackHint')}}</span></div>
            </div>
          </div>
        </div>
        <div class="settings-card settings-card-wide connection-settings-section connection-jms-section" v-if="isAdmin">
          <div class="settings-card-header settings-card-header-actions">
            <span><i class="bi bi-diagram-2"></i> {{t('settings.jmsTargets')}}</span>
            <button class="btn btn-sm btn-primary" @click="openCreateJmsTarget"><i class="bi bi-plus-lg"></i> {{t('settings.jmsTargetAdd')}}</button>
          </div>
          <div class="settings-card-body">
            <div v-if="jmsTargetsLoading" class="sub-info">{{t('settings.jmsTargetLoading')}}</div>
            <div v-else-if="!jmsTargets.length" class="connection-empty">
              <i class="bi bi-info-circle"></i>
              <div class="connection-empty-copy"><span class="connection-guidance-label">{{t('settings.connectionSourceLabel')}}</span><strong>{{t('settings.jmsTargetEmpty')}}</strong></div>
            </div>
            <div v-for="target in jmsTargets" :key="target.id" class="connection-row">
              <div class="connection-identity">
                <div class="connection-name">{{target.name}}</div>
                <div class="sub-info">{{target.providerType.toUpperCase()}} · {{target.queueName}}</div>
              </div>
              <div class="connection-details">
                <div class="settings-value-sm connection-address">{{target.serverUrl}}</div>
                <div class="sub-info connection-badges">
                  <span :class="target.enabled?'status-on':'status-off'">{{target.enabled?t('settings.enabled'):t('settings.disabled')}}</span>
                  <span v-if="target.defaultConnection" class="badge bg-primary">{{t('settings.jmsTargetDefault')}}</span>
                  <span v-if="target.legacy" class="badge bg-secondary">application.yml</span>
                  <span v-if="target.passwordConfigured"><i class="bi bi-key"></i></span>
                </div>
                <div v-if="jmsTargetTestResults[target.id]" class="sub-info connection-test-result" :class="jmsTargetTestResults[target.id].success?'success':'error'">
                  <i class="bi" :class="jmsTargetTestResults[target.id].success?'bi-check-circle':'bi-x-circle'"></i>
                  {{t('settings.lastConnectionTest')}} {{jmsTargetTestResults[target.id].testedAt}} ·
                  <template v-if="jmsTargetTestResults[target.id].success">{{jmsTargetTestResults[target.id].elapsedMs}} ms</template>
                  <template v-else>{{jmsTargetTestResults[target.id].error || '-'}}</template>
                </div>
              </div>
              <div class="connection-actions">
                <button class="btn btn-xs btn-secondary" @click="testJmsTarget(target)" :disabled="jmsTargetTestingId===target.id"><i class="bi bi-plug" :class="{'spin':jmsTargetTestingId===target.id}"></i> {{t('settings.jmsTargetTest')}}</button>
                <button v-if="!target.legacy&&!target.defaultConnection" class="btn btn-xs btn-secondary" @click="makeDefaultJmsTarget(target)" :disabled="!target.enabled"><i class="bi bi-check-circle"></i> {{t('settings.jmsTargetSetDefault')}}</button>
                <button v-if="!target.legacy" class="btn btn-xs btn-secondary" @click="openEditJmsTarget(target)"><i class="bi bi-pencil"></i> {{t('rules.edit')}}</button>
                <button v-if="!target.legacy" class="btn btn-xs btn-outline-danger" @click="deleteJmsTarget(target)" :disabled="target.defaultConnection" :title="target.defaultConnection?t('settings.defaultConnectionDeleteDisabled'):t('rules.delete')"><i class="bi bi-trash"></i> {{t('rules.delete')}}</button>
              </div>
            </div>
            <div class="connection-guidance">
              <div><span class="connection-guidance-label">{{t('settings.connectionApplyLabel')}}</span><span>{{t('settings.jmsTargetSwitchHint')}}</span></div>
              <div><span class="connection-guidance-label">{{t('settings.connectionReconnectLabel')}}</span><span>{{t('settings.jmsTargetReconnectHint')}}</span></div>
            </div>
          </div>
        </div>
        <div class="settings-card">
          <div class="settings-card-header"><i class="bi bi-diagram-3"></i> {{t('settings.protocolSettings')}}</div>
          <div class="settings-card-body">
            <div class="settings-item"><span class="settings-label">{{t('settings.httpAlias')}}</span><span class="settings-value">{{ status.httpAlias || t('settings.notSet') }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.jmsAlias')}}</span><span class="settings-value">{{ status.jmsAlias || t('settings.notSet') }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.jmsStatus')}}</span><span class="settings-value"><span :class="jmsEnabled?'status-on':'status-off'">{{ jmsEnabled ? t('settings.enabled') : t('settings.disabled') }}</span></span></div>
            <div class="settings-item" v-if="jmsEnabled"><span class="settings-label">{{t('settings.artemisUrl')}}</span><span class="settings-value settings-value-sm">{{ status.artemisBrokerUrl }}</span></div>
          </div>
        </div>
        <div class="settings-card">
          <div class="settings-card-header"><i class="bi bi-database"></i> {{t('settings.dataStorage')}}</div>
          <div class="settings-card-body">
            <div class="settings-item"><span class="settings-label">{{t('settings.database')}}</span><span class="settings-value settings-value-sm settings-value-code" :title="status.datasourceUrl">{{ status.datasourceUrl }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.ruleRetention')}}</span><span class="settings-value">{{ status.cleanupRetentionDays || 180 }} {{t('settings.days')}}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.responseRetention')}}</span><span class="settings-value">{{ status.responseRetentionDays || 180 }} {{t('settings.days')}}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.auditRetention')}}</span><span class="settings-value">{{ status.auditRetentionDays || 30 }} {{t('settings.days')}}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.statsMaxRecords')}}</span><span class="settings-value">{{ formatNum(status.statsMaxRecords) }} {{t('settings.unit')}}</span></div>
          </div>
        </div>
        <div class="settings-card">
          <div class="settings-card-header"><i class="bi bi-shield-lock"></i> {{t('settings.authSettings')}}</div>
          <div class="settings-card-body">
            <div class="settings-item"><span class="settings-label">{{t('settings.ldapStatus')}}</span><span class="settings-value"><span :class="status.ldapEnabled?'status-on':'status-off'">{{ status.ldapEnabled ? t('settings.enabled') : t('settings.disabled') }}</span></span></div>
            <div class="settings-item" v-if="status.ldapEnabled"><span class="settings-label">{{t('settings.ldapUrl')}}</span><span class="settings-value settings-value-sm">{{ status.ldapUrl }}</span></div>
            <div class="settings-item" v-if="!status.ldapEnabled"><span class="settings-label">{{t('settings.authMode')}}</span><span class="settings-value">{{ status.version === 'dev' ? t('settings.devMode') : t('settings.localAuth') }}</span></div>
          </div>
        </div>
        <div class="settings-card">
          <div class="settings-card-header"><i class="bi bi-bar-chart"></i> {{t('settings.dataStats')}}</div>
          <div class="settings-card-body">
            <div class="settings-item"><span class="settings-label">{{t('settings.ruleCount')}}</span><span class="settings-value">{{ formatNum(status.ruleCount) }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.responseCount')}}</span><span class="settings-value">{{ formatNum(status.responseCount) }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.requestLogCount')}}</span><span class="settings-value">{{ formatNum(status.requestLogCount) }}</span></div>
            <div class="settings-item" v-if="status.orphanRules"><span class="settings-label">{{t('settings.orphanRules')}}</span><span class="settings-value" style="color:var(--warning)">{{ status.orphanRules }}</span></div>
            <div class="settings-item" v-if="status.orphanResponses"><span class="settings-label">{{t('settings.orphanResponses')}}</span><span class="settings-value" style="color:var(--warning)">{{ status.orphanResponses }}</span></div>
            <div class="settings-item" v-if="status.dbFileSize"><span class="settings-label">{{t('settings.dbFileSize')}}</span><span class="settings-value">{{ formatMB(status.dbFileSize) }}</span></div>
          </div>
        </div>
        <div class="settings-card">
          <div class="settings-card-header"><i class="bi bi-cpu"></i> {{t('settings.systemInfo')}}</div>
          <div class="settings-card-body">
            <div class="settings-item"><span class="settings-label">{{t('settings.jvmHeap')}}</span><span class="settings-value">{{ formatHeap() }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.httpRuleCache')}}</span><span class="settings-value settings-value-sm">{{ formatCache('httpRules') }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.jmsRuleCache')}}</span><span class="settings-value settings-value-sm">{{ formatCache('jmsRules') }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.uptime')}}</span><span class="settings-value">{{ formatUptime(status.uptime) }}</span></div>
            <div class="settings-item" v-if="status.username"><span class="settings-label">{{t('settings.currentUser')}}</span><span class="settings-value">{{ status.username }}</span></div>
          </div>
        </div>
        <div class="settings-card">
          <div class="settings-card-header"><i class="bi bi-robot"></i> {{t('settings.agentStatus')}}</div>
          <div class="settings-card-body" v-if="agents.length">
            <template v-for="(a, idx) in agents" :key="a.name">
              <div v-if="idx > 0" style="border-top:1px solid var(--border);margin:0.5rem 0"></div>
              <div class="settings-item"><span class="settings-label">{{t('settings.agentName')}}</span><span class="settings-value" style="font-weight:600">{{ a.name }} <span :class="agentStatusBadgeClass(a.status)" style="margin-left:0.5rem"><i v-if="a.status !== 'RUNNING'" class="bi bi-exclamation-triangle me-1"></i>{{ agentStatusText(a.status) }}</span></span></div>
              <div class="settings-item" v-if="a.description"><span class="settings-label"></span><span class="settings-value sub-info">{{ a.description }}</span></div>
              <div class="settings-item"><span class="settings-label">{{t('settings.agentQueueSize')}}</span><span class="settings-value">{{ formatNum(a.queueSize) }}</span></div>
              <div class="settings-item"><span class="settings-label">{{t('settings.agentProcessed')}}</span><span class="settings-value">{{ formatNum(a.processedCount) }}</span></div>
              <div class="settings-item"><span class="settings-label">{{t('settings.agentDropped')}}</span><span class="settings-value">{{ formatNum(a.droppedCount) }}</span></div>
            </template>
          </div>
          <div class="settings-card-body" v-else>
            <div class="settings-item"><span class="sub-info">{{t('settings.agentNoAgents')}}</span></div>
          </div>
        </div>
        <div class="settings-card">
          <div class="settings-card-header"><i class="bi bi-cloud-arrow-up"></i> {{t('settings.dbBackup')}}</div>
          <div class="settings-card-body" v-if="backupStatus?.enabled">
            <div class="settings-item"><span class="settings-label">{{t('settings.status')}}</span><span class="settings-value"><span class="status-on">{{t('settings.backupEnabled')}}</span></span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.schedule')}}</span><span class="settings-value settings-value-sm">{{ backupStatus.cron }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.retentionDays')}}</span><span class="settings-value">{{ backupStatus.retentionDays }} {{t('settings.days')}}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.path')}}</span><span class="settings-value settings-value-sm">{{ backupStatus.path }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.operation')}}</span><span class="settings-value"><button class="btn btn-sm btn-primary" @click="$emit('trigger-backup')" :disabled="loading.backup"><i class="bi bi-cloud-arrow-up" :class="{'spin':loading.backup}"></i> {{t('settings.backupNow')}}</button></span></div>
            <div class="settings-item" v-if="backupStatus.files?.length">
              <span class="settings-label">{{t('settings.backupList')}}</span>
              <span class="settings-value"><div v-for="f in backupStatus.files" :key="f.name" class="sub-info">{{ f.name }} ({{ fmtSize(f.size) }})</div></span>
            </div>
          </div>
          <div class="settings-card-body" v-else>
            <div class="settings-item"><span class="settings-label">{{t('settings.status')}}</span><span class="settings-value"><span class="status-off">{{t('settings.backupDisabled')}}</span></span></div>
            <div class="settings-item" style="flex-direction:column;align-items:flex-start">
              <span class="settings-label" style="margin-bottom:0.5rem">{{t('settings.enableMethod')}}</span>
              <pre class="sub-info" style="margin:0;font-size:12px;white-space:pre-wrap">echo:
  backup:
    enabled: true
    cron: "0 0 3 * * *"
    path: ./backups
    retention-days: 7</pre>
            </div>
            <div class="settings-item"><span class="settings-label"></span><span class="settings-value sub-info"><i class="bi bi-info-circle"></i> {{t('settings.h2OnlyNote')}}</span></div>
          </div>
        </div>
        <div class="settings-card settings-scenario-card">
          <div class="settings-card-header settings-card-header-actions">
            <span><i class="bi bi-diagram-3" aria-hidden="true"></i> {{t('settings.scenarios')}}</span>
            <button v-if="scenarios.length && !scenariosError" type="button" class="btn btn-xs btn-secondary" @click="resetAllScenarios" :disabled="scenarioResetting!==null">
              <i class="bi" :class="scenarioResetting==='*'?'bi-arrow-clockwise spin':'bi-arrow-counterclockwise'" aria-hidden="true"></i>{{t('settings.resetAllScenarios')}}
            </button>
          </div>
          <div class="settings-card-body scenario-settings-body" aria-live="polite">
            <div v-if="scenariosLoading" class="settings-state-row"><i class="bi bi-arrow-clockwise spin" aria-hidden="true"></i><span>{{t('settings.scenariosLoading')}}</span></div>
            <div v-else-if="scenariosError" class="settings-state-row is-error"><i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>{{t('settings.scenariosLoadFailed')}}</span><button type="button" class="btn btn-xs btn-secondary" @click="loadScenarios">{{t('common.retry')}}</button></div>
            <div v-else-if="scenarios.length" class="scenario-list">
              <div v-for="s in scenarios" :key="s.scenarioName" class="scenario-row">
                <div class="scenario-identity"><strong>{{s.scenarioName}}</strong><span>{{t('settings.currentScenarioState')}}</span></div>
                <code class="scenario-state">{{s.currentState}}</code>
                <button type="button" class="btn btn-xs btn-secondary" @click="resetScenario(s.scenarioName)" :disabled="scenarioResetting!==null" :aria-label="t('settings.scenarioResetNamed', {name:s.scenarioName})">
                  <i class="bi" :class="scenarioResetting===s.scenarioName?'bi-arrow-clockwise spin':'bi-arrow-counterclockwise'" aria-hidden="true"></i>{{t('settings.resetScenario')}}
                </button>
              </div>
            </div>
            <div v-else class="settings-state-row"><i class="bi bi-diagram-3" aria-hidden="true"></i><span>{{t('settings.noScenarios')}}</span></div>
          </div>
        </div>
        <div class="settings-card settings-danger-zone">
          <div class="settings-card-header settings-danger-zone-header"><i class="bi bi-exclamation-triangle"></i> {{t('settings.dangerZone')}}</div>
          <div class="settings-card-body" style="display:flex;flex-direction:column;gap:0.5rem">
            <div class="settings-danger-warning"><i class="bi bi-info-circle" aria-hidden="true"></i><span>{{t('settings.dangerHint')}}</span></div>
            <button class="btn btn-danger" style="width:100%;text-align:left" @click="$emit('delete-all-rules')"><i class="bi bi-trash"></i> {{t('settings.deleteAllRules')}}</button>
            <button class="btn btn-danger" style="width:100%;text-align:left" @click="$emit('delete-all-responses')"><i class="bi bi-trash"></i> {{t('settings.deleteAllResponses')}}</button>
            <button class="btn btn-outline-danger" style="width:100%;text-align:left" @click="$emit('delete-orphan-responses')"><i class="bi bi-trash"></i> {{t('settings.deleteOrphanResponses')}}</button>
            <button class="btn btn-outline-danger" style="width:100%;text-align:left" @click="$emit('delete-all-audit')"><i class="bi bi-trash"></i> {{t('settings.deleteAllAudit')}}</button>
            <button class="btn btn-outline-danger" style="width:100%;text-align:left" @click="$emit('delete-all-logs')"><i class="bi bi-trash"></i> {{t('settings.deleteAllLogs')}}</button>
          </div>
        </div>
      </div>
      <div v-if="showHttpTargetForm" class="modal-overlay" @click.self="showHttpTargetForm=false">
        <div class="modal-box workspace-modal connection-form-modal" style="max-width:680px" role="dialog" aria-modal="true" :aria-label="editingHttpTarget?t('settings.httpTargetEdit'):t('settings.httpTargetAdd')">
          <div class="modal-header"><h3><i class="bi bi-globe2"></i> {{editingHttpTarget?t('settings.httpTargetEdit'):t('settings.httpTargetAdd')}}</h3><button class="modal-close" @click="showHttpTargetForm=false" :aria-label="t('rules.close')" :title="t('rules.close')"><i class="bi bi-x-lg"></i></button></div>
          <div class="modal-body">
            <div class="form-row"><div class="form-group"><label class="form-label" for="httpTargetName">{{t('settings.httpTargetName')}} <span class="required">*</span></label><input id="httpTargetName" class="form-control" v-model="httpTargetForm.name" maxlength="100" required></div><div class="form-group"><label class="form-label" for="httpAuthType">{{t('settings.httpAuthType')}}</label><select id="httpAuthType" class="form-control" v-model="httpTargetForm.authType"><option value="NONE">None</option><option value="BASIC">Basic</option><option value="BEARER">Bearer Token</option></select></div></div>
            <div class="form-group"><label class="form-label" for="httpTargetBaseUrl">{{t('settings.httpTargetBaseUrl')}} <span class="required">*</span></label><input id="httpTargetBaseUrl" class="form-control" v-model="httpTargetForm.baseUrl" placeholder="https://internal-api.example.com" autocomplete="url" spellcheck="false" required></div>
            <div v-if="httpTargetForm.authType!=='NONE'" class="form-row"><div v-if="httpTargetForm.authType==='BASIC'" class="form-group"><label class="form-label" for="httpTargetUsername">{{t('settings.httpTargetUsername')}}</label><input id="httpTargetUsername" class="form-control" v-model="httpTargetForm.username" autocomplete="off"></div><div class="form-group"><label class="form-label" for="httpTargetSecret">{{httpTargetForm.authType==='BEARER'?'Token':t('settings.httpTargetSecret')}}</label><input id="httpTargetSecret" type="password" class="form-control" v-model="httpTargetForm.secret" autocomplete="new-password" :placeholder="editingHttpTarget&&editingHttpTarget.secretConfigured?t('settings.httpTargetSecretKeep'):''"></div></div>
            <label v-if="editingHttpTarget&&editingHttpTarget.secretConfigured" class="form-check"><input type="checkbox" v-model="httpTargetForm.clearSecret"> {{t('settings.httpTargetClearSecret')}}</label>
            <div class="form-row"><div class="form-group"><label class="form-label" for="httpConnectTimeout">{{t('settings.httpConnectTimeout')}}</label><input id="httpConnectTimeout" type="number" min="1" max="300" class="form-control" v-model.number="httpTargetForm.connectTimeoutSeconds"></div><div class="form-group"><label class="form-label" for="httpReadTimeout">{{t('settings.httpReadTimeout')}}</label><input id="httpReadTimeout" type="number" min="1" max="300" class="form-control" v-model.number="httpTargetForm.readTimeoutSeconds"></div></div>
            <div class="connection-form-options"><label class="form-check"><input type="checkbox" v-model="httpTargetForm.enabled" :disabled="editingHttpTarget?.defaultConnection" @change="onHttpTargetEnabledChange"> {{t('settings.enabled')}}</label><label class="form-check"><input type="checkbox" v-model="httpTargetForm.defaultConnection" :disabled="!httpTargetForm.enabled||editingHttpTarget?.defaultConnection"> {{t('settings.httpTargetDefault')}}</label></div>
            <div class="form-group" style="margin-top:1rem;margin-bottom:0">
              <label class="form-label">{{t('settings.tlsModeLabel')}}</label>
              <div class="protocol-switch">
                <button type="button" class="protocol-btn" :class="{active:!httpTargetForm.tlsVerificationEnabled}" @click="httpTargetForm.tlsVerificationEnabled=false"><i class="bi bi-building"></i> {{t('settings.tlsModeCompatibility')}}</button>
                <button type="button" class="protocol-btn" :class="{active:httpTargetForm.tlsVerificationEnabled}" @click="httpTargetForm.tlsVerificationEnabled=true"><i class="bi bi-shield-check"></i> {{t('settings.tlsModeStrict')}}</button>
              </div>
              <div class="sub-info" style="margin-top:0.5rem"><i class="bi bi-info-circle"></i> {{httpTargetForm.tlsVerificationEnabled?t('settings.tlsVerificationStrictHint'):t('settings.tlsVerificationCompatibilityHint')}}</div>
            </div>
          </div>
          <div class="modal-footer"><button class="btn btn-secondary" @click="showHttpTargetForm=false">{{t('rules.close')}}</button><button class="btn btn-primary" @click="saveHttpTarget" :disabled="httpTargetSaving||!canSaveHttpTarget"><i class="bi bi-check-lg"></i> {{t('modal.save')}}</button></div>
        </div>
      </div>
      <div v-if="showJmsTargetForm" class="modal-overlay" @click.self="showJmsTargetForm=false">
        <div class="modal-box workspace-modal connection-form-modal connection-jms-form-modal" style="max-width:620px" role="dialog" aria-modal="true" :aria-label="editingJmsTarget?t('settings.jmsTargetEdit'):t('settings.jmsTargetAdd')">
          <div class="modal-header"><h3><i class="bi bi-diagram-2"></i> {{editingJmsTarget?t('settings.jmsTargetEdit'):t('settings.jmsTargetAdd')}}</h3><button class="modal-close" @click="showJmsTargetForm=false" :aria-label="t('rules.close')" :title="t('rules.close')"><i class="bi bi-x-lg"></i></button></div>
          <div class="modal-body">
            <div class="form-row"><div class="form-group"><label class="form-label" for="jmsTargetName">{{t('settings.jmsTargetName')}} <span class="required">*</span></label><input id="jmsTargetName" class="form-control" v-model="jmsTargetForm.name" maxlength="100" required></div><div class="form-group"><label class="form-label" for="jmsTargetProvider">{{t('settings.jmsTargetProvider')}}</label><select id="jmsTargetProvider" class="form-control" v-model="jmsTargetForm.providerType"><option value="artemis">Artemis</option><option value="tibco">TIBCO EMS</option></select></div></div>
            <div class="form-group"><label class="form-label" for="jmsTargetServerUrl">{{t('settings.jmsTargetServerUrl')}} <span class="required">*</span></label><input id="jmsTargetServerUrl" class="form-control" v-model="jmsTargetForm.serverUrl" placeholder="tcp://host:61616" autocomplete="url" spellcheck="false" required></div>
            <div class="form-row"><div class="form-group"><label class="form-label" for="jmsTargetUsername">{{t('settings.jmsTargetUsername')}}</label><input id="jmsTargetUsername" class="form-control" v-model="jmsTargetForm.username" autocomplete="off"></div><div class="form-group"><label class="form-label" for="jmsTargetPassword">{{t('settings.jmsTargetPassword')}}</label><input id="jmsTargetPassword" type="password" class="form-control" v-model="jmsTargetForm.password" autocomplete="new-password" :placeholder="editingJmsTarget&&editingJmsTarget.passwordConfigured?t('settings.jmsTargetPasswordKeep'):''"></div></div>
            <label v-if="editingJmsTarget&&editingJmsTarget.passwordConfigured" class="form-check"><input type="checkbox" v-model="jmsTargetForm.clearPassword"> {{t('settings.jmsTargetClearPassword')}}</label>
            <div class="form-row"><div class="form-group"><label class="form-label" for="jmsTargetQueue">{{t('settings.jmsTargetQueue')}} <span class="required">*</span></label><input id="jmsTargetQueue" class="form-control" v-model="jmsTargetForm.queueName" spellcheck="false" required></div><div class="form-group"><label class="form-label" for="jmsTargetTimeout">{{t('settings.jmsTargetTimeout')}}</label><input id="jmsTargetTimeout" type="number" min="1" max="300" class="form-control" v-model.number="jmsTargetForm.timeoutSeconds"></div></div>
            <div class="connection-form-options"><label class="form-check"><input type="checkbox" v-model="jmsTargetForm.enabled" :disabled="editingJmsTarget?.defaultConnection" @change="onJmsTargetEnabledChange"> {{t('settings.enabled')}}</label><label class="form-check"><input type="checkbox" v-model="jmsTargetForm.defaultConnection" :disabled="!jmsTargetForm.enabled||editingJmsTarget?.defaultConnection"> {{t('settings.jmsTargetDefault')}}</label></div>
          </div>
          <div class="modal-footer"><button class="btn btn-secondary" @click="showJmsTargetForm=false">{{t('rules.close')}}</button><button class="btn btn-primary" @click="saveJmsTarget" :disabled="jmsTargetSaving||!canSaveJmsTarget"><i class="bi bi-check-lg"></i> {{t('modal.save')}}</button></div>
        </div>
      </div>
      </template>
      </div>
    </div>
  `
};
