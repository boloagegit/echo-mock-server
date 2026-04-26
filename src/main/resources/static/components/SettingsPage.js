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
      scenarios: [],
      scenariosLoading: false,
    };
  },
  mounted() {
    this.loadAgents();
    this.loadScenarios();
  },
  watch: {
    status() {
      this.loadAgents();
      this.loadScenarios();
    }
  },
  methods: {
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
    async loadScenarios() {
      this.scenariosLoading = true;
      try {
        const res = await apiCall('/api/admin/scenarios', {}, { silent: true });
        if (res && res.ok) {
          this.scenarios = await res.json();
        }
      } catch (e) {
        // best-effort, ignore errors
      } finally {
        this.scenariosLoading = false;
      }
    },
    async resetScenario(name) {
      try {
        const res = await apiCall('/api/admin/scenarios/' + encodeURIComponent(name) + '/reset', { method: 'PUT' });
        if (res && res.ok) {
          showToast(this.t('toast.scenarioResetSuccess'), 'success');
          await this.loadScenarios();
        } else {
          showToast(this.t('toast.scenarioResetFailed'), 'danger');
        }
      } catch (e) {
        showToast(this.t('toast.scenarioResetFailed'), 'danger');
      }
    },
    async resetAllScenarios() {
      try {
        const res = await apiCall('/api/admin/scenarios/reset', { method: 'PUT' });
        if (res && res.ok) {
          showToast(this.t('toast.scenarioResetAllSuccess'), 'success');
          await this.loadScenarios();
        } else {
          showToast(this.t('toast.scenarioResetFailed'), 'danger');
        }
      } catch (e) {
        showToast(this.t('toast.scenarioResetFailed'), 'danger');
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
    }
  },
  template: /* html */`
    <div class="page" :class="{active:true}">
      <div class="page-header">
        <h1 class="page-title">{{t('settings.title')}} <span class="page-hint" v-if="status">v{{status.version}} · {{t('settings.configHint')}}</span></h1>
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
        <div class="settings-card">
          <div class="settings-card-header"><i class="bi bi-info-circle"></i> {{t('settings.serviceInfo')}}</div>
          <div class="settings-card-body">
            <div class="settings-item"><span class="settings-label">{{t('settings.version')}}</span><span class="settings-value">{{ status.version }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.httpPort')}}</span><span class="settings-value">{{ status.serverPort }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.envLabel')}}</span><span class="settings-value">{{ status.envLabel || t('settings.notSet') }}</span></div>
            <div class="settings-item"><span class="settings-label">{{t('settings.sessionTimeout')}}</span><span class="settings-value">{{ formatSession(status.sessionTimeout) }}</span></div>
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
            <div class="settings-item"><span class="settings-label">{{t('settings.database')}}</span><span class="settings-value settings-value-sm">{{ status.datasourceUrl }}</span></div>
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
              <pre class="sub-info" style="margin:0;font-size:var(--font-sm);white-space:pre-wrap">echo:
  backup:
    enabled: true
    cron: "0 0 3 * * *"
    path: ./backups
    retention-days: 7</pre>
            </div>
            <div class="settings-item"><span class="settings-label"></span><span class="settings-value sub-info"><i class="bi bi-info-circle"></i> {{t('settings.h2OnlyNote')}}</span></div>
          </div>
        </div>
        <div class="settings-card">
          <div class="settings-card-header"><i class="bi bi-diagram-3"></i> {{t('settings.scenarios')}}</div>
          <div class="settings-card-body" v-if="scenarios.length">
            <div v-for="s in scenarios" :key="s.id" class="settings-item">
              <span class="settings-label">{{s.scenarioName}}</span>
              <span class="settings-value" style="display:flex;align-items:center;gap:0.5rem">
                <span class="badge" :class="s.currentState === 'Started' ? 'badge-muted' : 'badge-info'">{{s.currentState}}</span>
                <button v-if="isLoggedIn && s.currentState !== 'Started'" class="btn btn-sm btn-secondary" @click="resetScenario(s.scenarioName)"><i class="bi bi-arrow-counterclockwise"></i> {{t('settings.resetScenario')}}</button>
              </span>
            </div>
          </div>
          <div class="settings-card-body" v-else>
            <div class="settings-item"><span class="sub-info">{{t('settings.noScenarios')}}</span></div>
          </div>
          <div v-if="scenarios.length && isLoggedIn" class="settings-card-body" style="border-top:1px solid var(--border);padding-top:0.5rem">
            <button class="btn btn-sm btn-secondary" @click="resetAllScenarios()"><i class="bi bi-arrow-counterclockwise"></i> {{t('settings.resetAllScenarios')}}</button>
          </div>
        </div>
        <div class="settings-card" style="border-color:var(--danger)">
          <div class="settings-card-header" style="color:var(--danger);flex-direction:column;align-items:flex-start;gap:0.25rem"><div style="display:flex;align-items:center;gap:0.5rem"><i class="bi bi-exclamation-triangle"></i> {{t('settings.dangerZone')}}</div><span class="sub-info" style="font-weight:400">{{t('settings.dangerHint')}}</span></div>
          <div class="settings-card-body" style="display:flex;flex-direction:column;gap:0.5rem">
            <button class="btn btn-danger" style="width:100%;text-align:left" @click="$emit('delete-all-rules')"><i class="bi bi-trash"></i> {{t('settings.deleteAllRules')}}</button>
            <button class="btn btn-danger" style="width:100%;text-align:left" @click="$emit('delete-all-responses')"><i class="bi bi-trash"></i> {{t('settings.deleteAllResponses')}}</button>
            <button class="btn btn-outline-danger" style="width:100%;text-align:left" @click="$emit('delete-orphan-responses')"><i class="bi bi-trash"></i> {{t('settings.deleteOrphanResponses')}}</button>
            <button class="btn btn-outline-danger" style="width:100%;text-align:left" @click="$emit('delete-all-audit')"><i class="bi bi-trash"></i> {{t('settings.deleteAllAudit')}}</button>
            <button class="btn btn-outline-danger" style="width:100%;text-align:left" @click="$emit('delete-all-logs')"><i class="bi bi-trash"></i> {{t('settings.deleteAllLogs')}}</button>
          </div>
        </div>
      </div>
      </template>
      </div>
    </div>
  `
};
