/**
 * StatsPage - 請求記錄頁面
 *
 * 顯示 Mock 請求記錄，含統計摘要、篩選、排序、匹配鏈追蹤。
 * 列表顯示摘要，所選紀錄正下方 lazy load detail（body / matchChain）。
 * 關閉再開啟使用快取，不重新查詢。
 * ref key 全部以 log.id 為索引，避免排序/分頁/刷新後狀態錯位。
 */
const StatsPage = {
  props: {
    logs: Array,
    logSummary: Object,
    loading: Object,
    logFilter: Object,
    logSort: Object,
    logPage: Number,
    logPageSize: Number,
    pagedLogs: Array,
    totalPages: Number,
    logFilterChips: Array,
    jmsEnabled: Boolean,
    httpLabel: String,
    jmsLabel: String,
    rules: Array,
    autoRefresh: Boolean,
    logDetailExpanded: Object,
    detectMode: { type: Function, default: null }
  },
  emits: [
    'load-logs', 'update:logFilter', 'update:logSort',
    'update:logPage', 'update:logPageSize',
    'toggle-sort', 'toggle-match-chain', 'toggle-log-detail', 'go-to-rule',
    'toggle-auto-refresh', 'remove-log-chip', 'clear-log-filters', 'clip-copy',
    'create-rule-from-log'
  ],
  inject: ['t'],
  data() {
    return {
      bodySearch: {},
      bodySearchIdx: {},
      bodyFormatted: {},
      inspectorTab: 'body',
    };
  },
  computed: {
    selectedLogItem() {
      return this.pagedLogs.find(item => !!this.logDetailExpanded[item.log.id]) || null;
    }
  },
  watch: {
    selectedLogItem(item) {
      if (this.inspectorTab === 'trace' && !item?.matchChainData?.length) {
        this.inspectorTab = 'body';
      }
    },
    logDetailExpanded: {
      deep: true,
      handler(newVal) {
        this.$nextTick(() => {
          for (const item of this.pagedLogs) {
            const id = item.log.id;
            if (!id || !newVal[id]) { continue; }
            // 新選取的紀錄 — 自動啟用 CodeMirror 格式化
            const detail = item._detail;
            if (!detail) { continue; }
            if (detail.requestBody) {
              const rk = 'reqBody-' + id;
              if (!this.bodyFormatted[rk]) { this.toggleBodyFormat(rk, detail.requestBody); }
            }
            if (detail.responseBody) {
              const rk = 'resBody-' + id;
              if (!this.bodyFormatted[rk]) { this.toggleBodyFormat(rk, detail.responseBody); }
            }
          }
        });
      }
    }
  },
  updated() {
    this.$nextTick(() => {
      for (const item of this.pagedLogs) {
        const id = item.log.id;
        if (!id || !this.logDetailExpanded[id]) { continue; }
        const detail = item._detail;
        if (!detail) { continue; }
        for (const [prefix, body] of [['reqBody-', detail.requestBody], ['resBody-', detail.responseBody]]) {
          if (!body) { continue; }
          const refKey = prefix + id;
          if (this.bodyFormatted[refKey]) {
            this._reinitCmIfNeeded(refKey, body);
          }
        }
      }
    });
  },
  beforeUnmount() {
    // 清理所有 CodeMirror instances 和 marks
    if (this.cmInstances) {
      for (const key of Object.keys(this.cmInstances)) {
        try { this.cmInstances[key].toTextArea(); } catch { /* ignore */ }
      }
      this.cmInstances = {};
    }
    if (this.cmMarks) {
      for (const key of Object.keys(this.cmMarks)) {
        if (this.cmMarks[key]) { this.cmMarks[key].forEach(m => { try { m.clear(); } catch { /* ignore */ } }); }
      }
      this.cmMarks = {};
    }
  },
  methods: {
    shortId, fmtTime, fmtSize, reasonText,
    logDate(value) {
      const formatted = fmtTime(value, false);
      return formatted ? formatted.slice(5, 10) : '-';
    },
    logClock(value) {
      const formatted = fmtTime(value, false);
      return formatted ? formatted.slice(11, 19) : '';
    },
    sortAria(field) {
      if (this.logSort.field !== field) { return 'none'; }
      return this.logSort.asc ? 'ascending' : 'descending';
    },
    logStatusCode(log) {
      return log.proxyStatus != null ? log.proxyStatus : log.responseStatus;
    },
    reasonIcon(reason) {
      if (reason === 'match') { return 'bi-check2-circle'; }
      if (reason === 'disabled') { return 'bi-pause-circle'; }
      if (reason === 'condition_not_match') { return 'bi-slash-circle'; }
      return 'bi-dash-circle';
    },
    /** 取得 detail body（從 lazy-loaded _detail） */
    _getBody(item, type) {
      const detail = item._detail;
      if (!detail) { return null; }
      return type === 'req' ? detail.requestBody : detail.responseBody;
    },
    /** 收合再展開後 DOM 被重建，CodeMirror 需要重新掛載 */
    _reinitCmIfNeeded(refKey, text) {
      this.cmInstances = this.cmInstances || {};
      const cm = this.cmInstances[refKey];
      const refs = this.$refs[refKey];
      const el = Array.isArray(refs) ? refs[0] : refs;
      if (!el) { return; }
      // 如果 el 是空的（DOM 重建後），重新初始化
      if (cm) {
        // 檢查 cm 的 wrapper 是否還在 DOM 中
        try {
          if (el.contains(cm.getWrapperElement())) { return; } // 仍在 DOM，不需重建
        } catch { /* ignore */ }
        // wrapper 不在 DOM → 清理舊 instance
        try { cm.toTextArea(); } catch { /* ignore */ }
        delete this.cmInstances[refKey];
      }
      // 重新建立
      const _detectMode = this.detectMode || ((t) => { const s = (t || '').trim(); if (s.startsWith('{') || s.startsWith('[')) { return 'application/json'; } if (s.startsWith('<')) { return 'xml'; } return 'text/plain'; });
      const mode = _detectMode(text);
      let formatted = text;
      if (mode === 'application/json') {
        try { formatted = JSON.stringify(JSON.parse(text), null, 2); } catch { /* keep original */ }
      } else if (mode === 'xml') {
        formatted = this.formatXml(text);
      }
      el.textContent = '';
      this.cmInstances[refKey] = CodeMirror(el, {
        value: formatted,
        mode: mode,
        readOnly: true,
        lineNumbers: true,
        lineWrapping: false,
        theme: 'default'
      });
    },
    setBodySearch(refKey, val) {
      this.bodySearch = { ...this.bodySearch, [refKey]: val };
      this.bodySearchIdx = { ...this.bodySearchIdx, [refKey]: 0 };
      this.cmHighlight(refKey);
    },
    bodyHighlight(refKey, text) {
      const kw = (this.bodySearch[refKey] || '').trim();
      if (!kw || !text) { return [{ text, hl: false }]; }
      const parts = [];
      const lower = text.toLowerCase();
      const kwLower = kw.toLowerCase();
      let pos = 0;
      let idx = lower.indexOf(kwLower, pos);
      while (idx !== -1) {
        if (idx > pos) { parts.push({ text: text.slice(pos, idx), hl: false }); }
        parts.push({ text: text.slice(idx, idx + kw.length), hl: true });
        pos = idx + kw.length;
        idx = lower.indexOf(kwLower, pos);
      }
      if (pos < text.length) { parts.push({ text: text.slice(pos), hl: false }); }
      return parts;
    },
    bodyMatchCount(refKey, text) {
      const kw = (this.bodySearch[refKey] || '').trim();
      if (!kw || !text) { return 0; }
      const lower = text.toLowerCase();
      const kwLower = kw.toLowerCase();
      let count = 0;
      let pos = 0;
      while ((pos = lower.indexOf(kwLower, pos)) !== -1) { count++; pos += kwLower.length; }
      return count;
    },
    bodyMatchLabel(refKey, text) {
      const total = this.bodyMatchCount(refKey, text);
      if (total === 0) { return this.t('stats.bodySearchNoMatches'); }
      return this.t('stats.bodySearchMatches', {current: (this.bodySearchIdx[refKey] || 0) + 1, total});
    },
    bodyNavSearch(refKey, text, dir) {
      const total = this.bodyMatchCount(refKey, text);
      if (total === 0) { return; }
      let cur = (this.bodySearchIdx[refKey] || 0) + dir;
      if (cur >= total) { cur = 0; }
      if (cur < 0) { cur = total - 1; }
      this.bodySearchIdx = { ...this.bodySearchIdx, [refKey]: cur };
      const cm = this.cmInstances && this.cmInstances[refKey];
      if (cm) {
        this.cmHighlight(refKey);
        return;
      }
      this.$nextTick(() => {
        const refs = this.$refs[refKey];
        const el = Array.isArray(refs) ? refs[0] : refs;
        if (!el) { return; }
        const active = el.querySelector('.pv-highlight-current');
        if (active) { active.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }
      });
    },
    bodyHlIsCurrent(refKey, text, segIdx) {
      const parts = this.bodyHighlight(refKey, text);
      let matchIdx = 0;
      for (let i = 0; i < parts.length; i++) {
        if (parts[i].hl) {
          if (i === segIdx) { return matchIdx === (this.bodySearchIdx[refKey] || 0); }
          matchIdx++;
        }
      }
      return false;
    },
    cmHighlight(refKey) {
      const cm = this.cmInstances && this.cmInstances[refKey];
      if (!cm) { return; }
      this.cmMarks = this.cmMarks || {};
      if (this.cmMarks[refKey]) { this.cmMarks[refKey].forEach(m => m.clear()); }
      this.cmMarks[refKey] = [];
      const kw = (this.bodySearch[refKey] || '').trim();
      if (!kw) { return; }
      const text = cm.getValue();
      const kwLower = kw.toLowerCase();
      const lower = text.toLowerCase();
      let pos = 0;
      let matchIdx = 0;
      const curIdx = this.bodySearchIdx[refKey] || 0;
      while ((pos = lower.indexOf(kwLower, pos)) !== -1) {
        const from = cm.posFromIndex(pos);
        const to = cm.posFromIndex(pos + kw.length);
        const css = matchIdx === curIdx ? 'background: var(--warning, #ffc107); color: #000; border-radius: 2px;' : 'background: rgba(var(--warning-rgb, 255,193,7), 0.35); border-radius: 2px;';
        this.cmMarks[refKey].push(cm.markText(from, to, { css }));
        if (matchIdx === curIdx) { cm.scrollIntoView(from, 60); }
        matchIdx++;
        pos += kw.length;
      }
    },
    toggleBodyFormat(refKey, text) {
      this.bodyFormatted = { ...this.bodyFormatted, [refKey]: !this.bodyFormatted[refKey] };
      if (this.bodyFormatted[refKey]) {
        this.$nextTick(() => {
          const refs = this.$refs[refKey];
          const el = Array.isArray(refs) ? refs[0] : refs;
          if (!el) { return; }
          const _detectMode = this.detectMode || ((t) => { const s = (t || '').trim(); if (s.startsWith('{') || s.startsWith('[')) { return 'application/json'; } if (s.startsWith('<')) { return 'xml'; } return 'text/plain'; });
          const mode = _detectMode(text);
          let formatted = text;
          if (mode === 'application/json') {
            try { formatted = JSON.stringify(JSON.parse(text), null, 2); } catch { /* keep original */ }
          } else if (mode === 'xml') {
            formatted = this.formatXml(text);
          }
          el.textContent = '';
          this.cmInstances = this.cmInstances || {};
          if (this.cmInstances[refKey]) { try { this.cmInstances[refKey].toTextArea(); } catch { /* ignore */ } }
          this.cmInstances[refKey] = CodeMirror(el, {
            value: formatted,
            mode: mode,
            readOnly: true,
            lineNumbers: true,
            lineWrapping: false,
            theme: 'default'
          });
        });
      } else {
        if (this.cmInstances && this.cmInstances[refKey]) {
          const refs = this.$refs[refKey];
          const el = Array.isArray(refs) ? refs[0] : refs;
          try { this.cmInstances[refKey].toTextArea(); } catch { /* ignore */ }
          delete this.cmInstances[refKey];
          if (el) { el.textContent = text || ''; }
        }
      }
    },
    formatXml(xml) {
      let formatted = '';
      let indent = 0;
      const parts = (xml || '').replace(/(>)\s*(<)/g, '$1\n$2').split('\n');
      for (const part of parts) {
        const trimmed = part.trim();
        if (!trimmed) { continue; }
        if (trimmed.startsWith('</')) { indent = Math.max(indent - 1, 0); }
        formatted += '  '.repeat(indent) + trimmed + '\n';
        if (trimmed.startsWith('<') && !trimmed.startsWith('</') && !trimmed.startsWith('<?') && !trimmed.endsWith('/>') && !trimmed.includes('</')) { indent++; }
      }
      return formatted.trim();
    },
    getFormattedText(refKey, text) {
      if (this.cmInstances && this.cmInstances[refKey]) {
        return this.cmInstances[refKey].getValue();
      }
      return text;
    },
    copyBody(text) {
      this.$emit('clip-copy', text);
    },
    moveInspectorTab(event) {
      const tabs = ['body', 'overview'];
      if (this.selectedLogItem?.matchChainData?.length) { tabs.push('trace'); }
      const keys = ['ArrowLeft', 'ArrowRight', 'Home', 'End'];
      if (!keys.includes(event.key)) { return; }
      event.preventDefault();
      const current = Math.max(0, tabs.indexOf(this.inspectorTab));
      const next = event.key === 'Home' ? 0
        : event.key === 'End' ? tabs.length - 1
        : (current + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
      this.inspectorTab = tabs[next];
      this.$nextTick(() => this.$refs.inspectorTabs?.querySelector('[role="tab"][aria-selected="true"]')?.focus());
    },
  },
  template: /* html */`
    <div class="page workspace-page logs-workspace" :class="{active:true}">
      <div class="page-header">
        <div class="page-heading">
          <h1 class="page-title">{{t('stats.title')}}</h1>
          <span class="page-count">{{logSummary.filteredRequests ?? logs.length}}</span>
          <button v-if="logSummary.maxRecords" type="button" class="help-tooltip tooltip-align-start"
            :data-tooltip="t('stats.maxRecordsInfo', {count: logSummary.maxRecords})"
            :aria-label="t('stats.maxRecordsHelp')" @keydown.esc="$event.currentTarget.blur()">
            <i class="bi bi-question-circle" aria-hidden="true"></i>
          </button>
        </div>
        <div class="page-actions">
          <label class="inline-toggle" @click.stop :title="t('stats.autoRefreshTooltip')">
            <span>{{t('stats.autoRefresh')}}</span>
            <span class="toggle">
              <input type="checkbox" :checked="autoRefresh" @change="$emit('toggle-auto-refresh')">
              <span class="toggle-slider"></span>
            </span>
          </label>
          <button type="button" class="btn btn-secondary" @click="$emit('load-logs', true)" :disabled="loading.logs">
            <i class="bi bi-arrow-clockwise" :class="{'spin':loading.logs}" aria-hidden="true"></i>
            {{t('stats.refresh')}}
          </button>
        </div>
      </div>

      <div class="card workspace-filter-card">
        <div class="card-body filter-row workspace-filter-bar">
          <div class="workspace-filter-controls">
            <div class="btn-group" role="group" :aria-label="t('stats.protocolFilter')">
              <button type="button" class="btn btn-sm" :class="logFilter.protocol==='HTTP'?'btn-primary':'btn-secondary'"
                :aria-pressed="logFilter.protocol==='HTTP'"
                @click="$emit('update:logFilter', {...logFilter, protocol: logFilter.protocol==='HTTP'?'':'HTTP'})">{{httpLabel}}</button>
              <button type="button" class="btn btn-sm" :class="logFilter.protocol==='JMS'?'btn-primary':'btn-secondary'"
                :aria-pressed="logFilter.protocol==='JMS'" :disabled="!jmsEnabled"
                @click="$emit('update:logFilter', {...logFilter, protocol: logFilter.protocol==='JMS'?'':'JMS'})">{{jmsLabel}}</button>
            </div>
            <div class="filter-divider" aria-hidden="true"></div>
            <div class="btn-group" role="group" :aria-label="t('stats.resultFilter')">
              <button type="button" class="btn btn-sm" :class="logFilter.matched==='true'?'btn-primary':'btn-secondary'"
                :aria-pressed="logFilter.matched==='true'"
                @click="$emit('update:logFilter', {...logFilter, matched: logFilter.matched==='true'?'':'true'})">{{t('stats.filterMatched')}}</button>
              <button type="button" class="btn btn-sm" :class="logFilter.matched==='false'?'btn-primary':'btn-secondary'"
                :aria-pressed="logFilter.matched==='false'"
                @click="$emit('update:logFilter', {...logFilter, matched: logFilter.matched==='false'?'':'false'})">{{t('stats.filterUnmatched')}}</button>
            </div>
            <div class="filter-divider" aria-hidden="true"></div>
            <div class="workspace-search-field" role="search">
              <label class="visually-hidden" for="logSearch">{{t('stats.searchLabel')}}</label>
              <i class="bi bi-search" aria-hidden="true"></i>
              <input id="logSearch" :value="logFilter.endpoint"
                @input="$emit('update:logFilter', {...logFilter, endpoint: $event.target.value})"
                :placeholder="t('stats.searchPlaceholder')" class="form-control">
              <button v-if="logFilter.endpoint" type="button" class="workspace-search-clear"
                @click="$emit('update:logFilter', {...logFilter, endpoint:''})"
                :aria-label="t('stats.clearSearch')" :title="t('stats.clearSearch')"><i class="bi bi-x" aria-hidden="true"></i></button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="logFilterChips.length" class="filter-chips" role="group" :aria-label="t('stats.activeFilters')">
        <span class="filter-chip" v-for="c in logFilterChips" :key="c.key">
          {{c.label}}
          <button type="button" class="chip-remove" @click="$emit('remove-log-chip', c.key)"
            :aria-label="t('stats.removeFilter', {filter: c.label})"><i class="bi bi-x" aria-hidden="true"></i></button>
        </span>
        <button type="button" class="chip-clear" @click="$emit('clear-log-filters')">{{t('stats.clearAll')}}</button>
      </div>

      <div class="card card-table workspace-table-card">
        <div v-if="loading.logsError && !loading.logs" class="workspace-load-error" role="alert">
          <i class="bi bi-cloud-slash" aria-hidden="true"></i>
          <strong>{{t('stats.loadFailed')}}</strong>
          <button type="button" class="btn btn-sm btn-secondary" @click="$emit('load-logs', true)"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</button>
        </div>
        <div v-else class="card-table-body">
          <div v-if="loading.logs && !logs.length" role="status" :aria-label="t('stats.loadingLogs')">
            <div v-for="i in 6" :key="'sk-log-'+i" class="sk-row">
              <span class="sk sk-text-sm log-skeleton-time"></span>
              <span class="sk sk-badge log-skeleton-protocol"></span>
              <span class="sk sk-badge log-skeleton-method"></span>
              <span class="sk sk-text log-skeleton-endpoint"></span>
              <span class="sk sk-text-sm log-skeleton-duration"></span>
              <span class="sk sk-badge log-skeleton-result"></span>
              <span class="sk sk-text log-skeleton-detail"></span>
            </div>
          </div>

          <table v-if="pagedLogs.length" class="table-fixed workspace-table logs-table">
            <caption class="visually-hidden">{{t('stats.tableCaption')}}</caption>
            <thead>
              <tr>
                <th class="log-time-column" :aria-sort="sortAria('requestTime')">
                  <button type="button" class="table-sort-button" @click="$emit('toggle-sort','requestTime')"
                    :aria-label="t('stats.sortBy', {field: t('stats.thTime')})">
                    <span>{{t('stats.thTime')}}</span>
                    <i class="bi" :class="logSort.field==='requestTime'?(logSort.asc?'bi-caret-up-fill':'bi-caret-down-fill'):'bi-arrow-down-up'" aria-hidden="true"></i>
                  </button>
                </th>
                <th :aria-sort="sortAria('endpoint')">
                  <button type="button" class="table-sort-button" @click="$emit('toggle-sort','endpoint')"
                    :aria-label="t('stats.sortBy', {field: t('stats.thRequest')})">
                    <span>{{t('stats.thRequest')}}</span>
                    <i class="bi" :class="logSort.field==='endpoint'?(logSort.asc?'bi-caret-up-fill':'bi-caret-down-fill'):'bi-arrow-down-up'" aria-hidden="true"></i>
                  </button>
                </th>
                <th class="col-hide-md log-duration-column" :aria-sort="sortAria('responseTimeMs')">
                  <button type="button" class="table-sort-button" @click="$emit('toggle-sort','responseTimeMs')"
                    :aria-label="t('stats.sortBy', {field: t('stats.thDuration')})">
                    <span>{{t('stats.thDuration')}}</span>
                    <i class="bi" :class="logSort.field==='responseTimeMs'?(logSort.asc?'bi-caret-up-fill':'bi-caret-down-fill'):'bi-arrow-down-up'" aria-hidden="true"></i>
                  </button>
                </th>
                <th>{{t('stats.thResult')}}</th>
                <th class="col-actions col-actions-2">{{t('stats.thActions')}}</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="item in pagedLogs" :key="item.log.id">
                <tr class="row-clickable log-summary-row" :id="'log-summary-'+item.log.id"
                  :class="{'is-expanded': logDetailExpanded[item.log.id]}"
                  @click="$emit('toggle-log-detail', item)" :title="t('stats.clickExpand')">
                  <td class="log-time-cell" :title="fmtTime(item.log.requestTime,false)">
                    <span class="log-time-date">{{logDate(item.log.requestTime)}}</span>
                    <span class="log-time-clock">{{logClock(item.log.requestTime)}}</span>
                  </td>
                  <td>
                    <div class="log-request-primary">
                      <span class="badge" :class="'badge-'+item.log.protocol?.toLowerCase()">{{item.log.protocol}}</span>
                      <span v-if="item.log.protocol==='HTTP' && item.log.method" class="log-method">{{item.log.method}}</span>
                      <code :title="item.log.endpoint">{{item.log.endpoint}}</code>
                    </div>
                    <div v-if="item.log.targetHost" class="log-request-secondary">
                      <span>{{t('stats.hostLabel')}}</span>
                      <code :title="item.log.targetHost">{{item.log.targetHost}}</code>
                    </div>
                  </td>
                  <td class="col-hide-md log-duration-cell"><span>{{item.log.responseTimeMs}}</span><small>ms</small></td>
                  <td>
                    <div class="log-result">
                      <template v-if="item.log.matched">
                        <span class="log-outcome log-outcome-success"><i class="bi bi-check2-circle" aria-hidden="true"></i>{{t('stats.matched')}}</span>
                      </template>
                      <template v-else-if="item.log.targetHost && item.log.proxyError">
                        <span class="log-outcome log-outcome-danger" :title="item.log.proxyError"><i class="bi bi-x-circle" aria-hidden="true"></i>{{t('stats.forwardFailed')}}</span>
                      </template>
                      <template v-else-if="item.log.targetHost">
                        <span class="log-outcome"><i class="bi bi-arrow-right-circle" aria-hidden="true"></i>{{t('stats.forwarded')}}</span>
                      </template>
                      <template v-else>
                        <span class="log-outcome log-outcome-danger"><i class="bi bi-x-circle" aria-hidden="true"></i>{{t('stats.unmatched')}}</span>
                      </template>
                      <span v-if="logStatusCode(item.log) != null" class="log-status-code"
                        :class="logStatusCode(item.log)<400?'is-success':logStatusCode(item.log)<500?'is-warning':'is-danger'">{{logStatusCode(item.log)}}</span>
                    </div>
                    <div v-if="item.log.matched && item.rule" class="log-result-secondary">
                      <a href="#" class="log-rule-link" :title="item.rule.id" @click.prevent.stop="$emit('go-to-rule', item.rule.id)">{{shortId(item.rule.id)}}</a>
                      <span v-if="item.rule.description" :title="item.rule.description">{{item.rule.description}}</span>
                    </div>
                    <div v-else-if="item.log.matched && item.log.ruleId" class="log-result-secondary">
                      <span class="log-rule-link" :title="item.log.ruleId">{{shortId(item.log.ruleId)}}</span>
                      <span>{{t('stats.deleted')}}</span>
                    </div>
                    <div v-else-if="item.log.proxyError" class="log-result-secondary" :title="item.log.proxyError">{{item.log.proxyError}}</div>
                  </td>
                  <td class="col-actions col-actions-2">
                    <div class="log-row-actions">
                      <button v-if="item.log.hasResponseBody || (item._detail && item._detail.responseBody)" type="button"
                        class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('create-rule-from-log', item._detail || item.log)"
                        :title="t('stats.createRuleFromLog')" :aria-label="t('stats.createRuleFromLog')"><i class="bi bi-plus-circle" aria-hidden="true"></i></button>
                      <button type="button" class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('toggle-log-detail', item)"
                        :aria-expanded="!!logDetailExpanded[item.log.id]" :aria-controls="'log-detail-'+item.log.id"
                        :title="logDetailExpanded[item.log.id]?t('stats.collapseTrace'):t('stats.expandTrace')"
                        :aria-label="logDetailExpanded[item.log.id]?t('stats.collapseTrace'):t('stats.expandTrace')">
                        <i class="bi" :class="logDetailExpanded[item.log.id]?'bi-chevron-up':'bi-chevron-down'" aria-hidden="true"></i>
                      </button>
                    </div>
                  </td>
                </tr>

                <tr v-if="logDetailExpanded[item.log.id]" class="log-detail-row">
                  <td colspan="5" class="log-detail-cell" @click.stop>
                    <div class="log-detail-slot" :id="'log-detail-slot-'+item.log.id"></div>
                  </td>
                </tr>

              </template>
            </tbody>
          </table>

          <div v-if="!pagedLogs.length && !loading.logs" class="empty workspace-empty">
            <i class="bi" :class="(logFilter.protocol||logFilter.matched||logFilter.endpoint)?'bi-search':'bi-inbox'" aria-hidden="true"></i>
            <div class="workspace-empty-title">{{(logFilter.protocol||logFilter.matched||logFilter.endpoint) ? t('stats.emptyNoMatch') : t('stats.emptyNoLogs')}}</div>
            <div class="workspace-empty-hint">{{(logFilter.protocol||logFilter.matched||logFilter.endpoint) ? t('stats.emptyNoMatchHint') : t('stats.emptyHint')}}</div>
            <button v-if="logFilter.protocol||logFilter.matched||logFilter.endpoint" type="button" class="btn btn-secondary" @click="$emit('clear-log-filters')">{{t('stats.clearAll')}}</button>
          </div>
        </div>

        <div v-if="!loading.logsError" class="card-table-footer">
          <div class="log-footer-summary">
            <span class="sub-info">{{t('stats.totalCount', {count: logSummary.filteredRequests ?? logs.length})}}</span>
            <button v-if="logFilter.protocol||logFilter.matched||logFilter.endpoint" type="button" class="log-filter-reset"
              :title="t('stats.clickClearFilter')" @click="$emit('clear-log-filters')"><i class="bi bi-funnel-fill" aria-hidden="true"></i> {{t('stats.filtering')}}</button>
          </div>
          <div class="pagination-controls" role="navigation" :aria-label="t('stats.pagination')">
            <button type="button" class="btn btn-sm btn-secondary" @click="$emit('update:logPage', 1)" :disabled="logPage===1" :aria-label="t('stats.firstPage')"><i class="bi bi-chevron-double-left" aria-hidden="true"></i></button>
            <button type="button" class="btn btn-sm btn-secondary" @click="$emit('update:logPage', logPage-1)" :disabled="logPage===1" :aria-label="t('stats.previousPage')"><i class="bi bi-chevron-left" aria-hidden="true"></i></button>
            <span class="tabular-nums" :aria-label="t('stats.pageStatus', {page: logPage, total: totalPages})">{{logPage}} / {{totalPages}}</span>
            <button type="button" class="btn btn-sm btn-secondary" @click="$emit('update:logPage', logPage+1)" :disabled="logPage>=totalPages" :aria-label="t('stats.nextPage')"><i class="bi bi-chevron-right" aria-hidden="true"></i></button>
            <button type="button" class="btn btn-sm btn-secondary" @click="$emit('update:logPage', totalPages)" :disabled="logPage>=totalPages" :aria-label="t('stats.lastPage')"><i class="bi bi-chevron-double-right" aria-hidden="true"></i></button>
          </div>
          <label class="log-page-size"><span class="visually-hidden">{{t('stats.pageSize')}}</span>
            <select :value="logPageSize" @change="$emit('update:logPageSize', Number($event.target.value))" class="form-control" :aria-label="t('stats.pageSize')">
              <option :value="10">10</option><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option>
            </select>
          </label>
        </div>

        <Teleport v-if="selectedLogItem && !loading.logsError" defer :to="'#log-detail-slot-'+selectedLogItem.log.id">
        <section class="log-inspector" :id="'log-detail-'+selectedLogItem.log.id"
          :aria-labelledby="'log-summary-'+selectedLogItem.log.id">
          <header class="log-inspector-header">
            <div class="log-inspector-identity">
              <span class="badge" :class="'badge-'+selectedLogItem.log.protocol?.toLowerCase()">{{selectedLogItem.log.protocol}}</span>
              <span v-if="selectedLogItem.log.protocol==='HTTP' && selectedLogItem.log.method" class="log-method">{{selectedLogItem.log.method}}</span>
              <code :title="selectedLogItem.log.endpoint">{{selectedLogItem.log.endpoint}}</code>
              <span v-if="logStatusCode(selectedLogItem.log) != null" class="log-status-code"
                :class="logStatusCode(selectedLogItem.log)<400?'is-success':logStatusCode(selectedLogItem.log)<500?'is-warning':'is-danger'">{{logStatusCode(selectedLogItem.log)}}</span>
              <span class="log-inspector-duration tabular-nums">{{selectedLogItem.log.responseTimeMs}} ms</span>
            </div>
            <div class="log-inspector-actions">
              <button v-if="selectedLogItem.log.hasResponseBody || selectedLogItem._detail?.responseBody" type="button"
                class="btn btn-sm btn-secondary" @click.stop="$emit('create-rule-from-log', selectedLogItem._detail || selectedLogItem.log)">
                <i class="bi bi-plus-circle" aria-hidden="true"></i>{{t('stats.createRuleFromLog')}}
              </button>
              <button type="button" class="btn btn-sm btn-icon btn-secondary"
                @click.stop="$emit('toggle-log-detail', selectedLogItem)"
                :title="t('stats.closeInspector')" :aria-label="t('stats.closeInspector')">
                <i class="bi bi-x-lg" aria-hidden="true"></i>
              </button>
            </div>
          </header>

          <div ref="inspectorTabs" class="log-inspector-tabs" role="tablist" :aria-label="t('stats.inspectorViews')">
            <button type="button" role="tab" :aria-selected="inspectorTab==='body'" :tabindex="inspectorTab==='body'?0:-1"
              :aria-controls="'log-inspector-body-'+selectedLogItem.log.id"
              :class="{'is-active': inspectorTab==='body'}" @click="inspectorTab='body'" @keydown="moveInspectorTab">
              <i class="bi bi-braces" aria-hidden="true"></i>{{t('stats.inspectorBodyTab')}}
            </button>
            <button type="button" role="tab" :aria-selected="inspectorTab==='overview'" :tabindex="inspectorTab==='overview'?0:-1"
              :aria-controls="'log-inspector-overview-'+selectedLogItem.log.id"
              :class="{'is-active': inspectorTab==='overview'}" @click="inspectorTab='overview'" @keydown="moveInspectorTab">
              <i class="bi bi-list-ul" aria-hidden="true"></i>{{t('stats.inspectorOverviewTab')}}
            </button>
            <button type="button" role="tab" :aria-selected="inspectorTab==='trace'" :tabindex="inspectorTab==='trace'?0:-1"
              :aria-controls="'log-inspector-trace-'+selectedLogItem.log.id"
              :class="{'is-active': inspectorTab==='trace'}" :disabled="!selectedLogItem.matchChainData?.length"
              @click="inspectorTab='trace'" @keydown="moveInspectorTab">
              <i class="bi bi-diagram-3" aria-hidden="true"></i>{{t('stats.matchChainTitle')}}
              <span v-if="selectedLogItem.matchChainData?.length" class="log-inspector-tab-count">{{selectedLogItem.matchChainData.length}}</span>
            </button>
          </div>

          <div v-if="selectedLogItem._detailLoading" class="log-inspector-loading" role="status" aria-live="polite">
            <i class="bi bi-arrow-clockwise spin" aria-hidden="true"></i>{{t('stats.loadingDetail')}}
          </div>

          <div v-else-if="selectedLogItem._detailError" class="log-inspector-loading log-inspector-error" role="alert">
            <i class="bi bi-cloud-slash" aria-hidden="true"></i><span>{{t('stats.detailLoadFailed')}}</span>
            <button type="button" class="btn btn-sm btn-secondary" @click="$emit('toggle-log-detail', selectedLogItem)"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</button>
          </div>

          <div v-else-if="inspectorTab==='body'" class="log-inspector-content log-inspector-body-grid"
            role="tabpanel" :id="'log-inspector-body-'+selectedLogItem.log.id">
            <section class="log-inspector-pane" :aria-labelledby="'request-body-heading-'+selectedLogItem.log.id">
              <div class="log-inspector-pane-header">
                <h3 :id="'request-body-heading-'+selectedLogItem.log.id">
                  <i class="bi bi-arrow-up-circle" aria-hidden="true"></i>{{t('stats.detailRequestBody')}}
                </h3>
                <span class="log-body-size tabular-nums">{{fmtSize(selectedLogItem._detail?.requestBody?.length || 0)}}</span>
                <div v-if="selectedLogItem._detail?.requestBody" class="log-body-tools">
                  <div class="pv-search-bar">
                    <input :value="bodySearch['reqBody-'+selectedLogItem.log.id]||''"
                      @input="setBodySearch('reqBody-'+selectedLogItem.log.id, $event.target.value)"
                      :placeholder="t('rules.pvSearchBody')" :aria-label="t('stats.searchRequestBody')"
                      @keydown.enter.prevent="bodyNavSearch('reqBody-'+selectedLogItem.log.id, bodyFormatted['reqBody-'+selectedLogItem.log.id] ? getFormattedText('reqBody-'+selectedLogItem.log.id, selectedLogItem._detail.requestBody) : selectedLogItem._detail.requestBody, $event.shiftKey ? -1 : 1)">
                    <span v-if="bodySearch['reqBody-'+selectedLogItem.log.id]" class="pv-search-count">{{bodyMatchLabel('reqBody-'+selectedLogItem.log.id, bodyFormatted['reqBody-'+selectedLogItem.log.id] ? getFormattedText('reqBody-'+selectedLogItem.log.id, selectedLogItem._detail.requestBody) : selectedLogItem._detail.requestBody)}}</span>
                    <button v-if="bodySearch['reqBody-'+selectedLogItem.log.id]" type="button"
                      @click="bodyNavSearch('reqBody-'+selectedLogItem.log.id, bodyFormatted['reqBody-'+selectedLogItem.log.id] ? getFormattedText('reqBody-'+selectedLogItem.log.id, selectedLogItem._detail.requestBody) : selectedLogItem._detail.requestBody, -1)"
                      :title="t('rules.pvSearchPrev')" :aria-label="t('rules.pvSearchPrev')"><i class="bi bi-chevron-up" aria-hidden="true"></i></button>
                    <button v-if="bodySearch['reqBody-'+selectedLogItem.log.id]" type="button"
                      @click="bodyNavSearch('reqBody-'+selectedLogItem.log.id, bodyFormatted['reqBody-'+selectedLogItem.log.id] ? getFormattedText('reqBody-'+selectedLogItem.log.id, selectedLogItem._detail.requestBody) : selectedLogItem._detail.requestBody, 1)"
                      :title="t('rules.pvSearchNext')" :aria-label="t('rules.pvSearchNext')"><i class="bi bi-chevron-down" aria-hidden="true"></i></button>
                  </div>
                  <button type="button" class="btn btn-sm btn-icon btn-secondary"
                    :class="{'active': bodyFormatted['reqBody-'+selectedLogItem.log.id]}"
                    :aria-pressed="!!bodyFormatted['reqBody-'+selectedLogItem.log.id]"
                    @click.stop="toggleBodyFormat('reqBody-'+selectedLogItem.log.id, selectedLogItem._detail.requestBody)"
                    :title="t('rules.pvFormat')" :aria-label="t('rules.pvFormat')"><i class="bi bi-braces" aria-hidden="true"></i></button>
                  <button type="button" class="btn btn-sm btn-icon btn-secondary" @click.stop="copyBody(selectedLogItem._detail.requestBody)"
                    :title="t('rules.copyFullContent')" :aria-label="t('rules.copyFullContent')"><i class="bi bi-clipboard" aria-hidden="true"></i></button>
                </div>
              </div>
              <div v-if="selectedLogItem._detail?.requestBody && bodyFormatted['reqBody-'+selectedLogItem.log.id]"
                :ref="'reqBody-'+selectedLogItem.log.id" class="pv-cm-container"></div>
              <pre v-else-if="selectedLogItem._detail?.requestBody" :ref="'reqBody-'+selectedLogItem.log.id" class="pv-pre"><template v-if="bodySearch['reqBody-'+selectedLogItem.log.id]"><template v-for="(seg,si) in bodyHighlight('reqBody-'+selectedLogItem.log.id, selectedLogItem._detail.requestBody)" :key="si"><span v-if="seg.hl" class="pv-highlight" :class="{'pv-highlight-current': bodyHlIsCurrent('reqBody-'+selectedLogItem.log.id, selectedLogItem._detail.requestBody, si)}">{{seg.text}}</span><template v-else>{{seg.text}}</template></template></template><template v-else>{{selectedLogItem._detail.requestBody}}</template></pre>
              <div v-else class="log-body-empty"><i class="bi bi-file-earmark" aria-hidden="true"></i>{{t('stats.emptyRequestBody')}}</div>
            </section>

            <section class="log-inspector-pane" :aria-labelledby="'response-body-heading-'+selectedLogItem.log.id">
              <div class="log-inspector-pane-header">
                <h3 :id="'response-body-heading-'+selectedLogItem.log.id">
                  <i class="bi bi-arrow-down-circle" aria-hidden="true"></i>{{t('stats.detailResponseBody')}}
                </h3>
                <span class="log-body-size tabular-nums">{{fmtSize(selectedLogItem._detail?.responseBody?.length || 0)}}</span>
                <div v-if="selectedLogItem._detail?.responseBody" class="log-body-tools">
                  <div class="pv-search-bar">
                    <input :value="bodySearch['resBody-'+selectedLogItem.log.id]||''"
                      @input="setBodySearch('resBody-'+selectedLogItem.log.id, $event.target.value)"
                      :placeholder="t('rules.pvSearchBody')" :aria-label="t('stats.searchResponseBody')"
                      @keydown.enter.prevent="bodyNavSearch('resBody-'+selectedLogItem.log.id, bodyFormatted['resBody-'+selectedLogItem.log.id] ? getFormattedText('resBody-'+selectedLogItem.log.id, selectedLogItem._detail.responseBody) : selectedLogItem._detail.responseBody, $event.shiftKey ? -1 : 1)">
                    <span v-if="bodySearch['resBody-'+selectedLogItem.log.id]" class="pv-search-count">{{bodyMatchLabel('resBody-'+selectedLogItem.log.id, bodyFormatted['resBody-'+selectedLogItem.log.id] ? getFormattedText('resBody-'+selectedLogItem.log.id, selectedLogItem._detail.responseBody) : selectedLogItem._detail.responseBody)}}</span>
                    <button v-if="bodySearch['resBody-'+selectedLogItem.log.id]" type="button"
                      @click="bodyNavSearch('resBody-'+selectedLogItem.log.id, bodyFormatted['resBody-'+selectedLogItem.log.id] ? getFormattedText('resBody-'+selectedLogItem.log.id, selectedLogItem._detail.responseBody) : selectedLogItem._detail.responseBody, -1)"
                      :title="t('rules.pvSearchPrev')" :aria-label="t('rules.pvSearchPrev')"><i class="bi bi-chevron-up" aria-hidden="true"></i></button>
                    <button v-if="bodySearch['resBody-'+selectedLogItem.log.id]" type="button"
                      @click="bodyNavSearch('resBody-'+selectedLogItem.log.id, bodyFormatted['resBody-'+selectedLogItem.log.id] ? getFormattedText('resBody-'+selectedLogItem.log.id, selectedLogItem._detail.responseBody) : selectedLogItem._detail.responseBody, 1)"
                      :title="t('rules.pvSearchNext')" :aria-label="t('rules.pvSearchNext')"><i class="bi bi-chevron-down" aria-hidden="true"></i></button>
                  </div>
                  <button type="button" class="btn btn-sm btn-icon btn-secondary"
                    :class="{'active': bodyFormatted['resBody-'+selectedLogItem.log.id]}"
                    :aria-pressed="!!bodyFormatted['resBody-'+selectedLogItem.log.id]"
                    @click.stop="toggleBodyFormat('resBody-'+selectedLogItem.log.id, selectedLogItem._detail.responseBody)"
                    :title="t('rules.pvFormat')" :aria-label="t('rules.pvFormat')"><i class="bi bi-braces" aria-hidden="true"></i></button>
                  <button type="button" class="btn btn-sm btn-icon btn-secondary" @click.stop="copyBody(selectedLogItem._detail.responseBody)"
                    :title="t('rules.copyFullContent')" :aria-label="t('rules.copyFullContent')"><i class="bi bi-clipboard" aria-hidden="true"></i></button>
                </div>
              </div>
              <div v-if="selectedLogItem._detail?.responseBody && bodyFormatted['resBody-'+selectedLogItem.log.id]"
                :ref="'resBody-'+selectedLogItem.log.id" class="pv-cm-container"></div>
              <pre v-else-if="selectedLogItem._detail?.responseBody" :ref="'resBody-'+selectedLogItem.log.id" class="pv-pre"><template v-if="bodySearch['resBody-'+selectedLogItem.log.id]"><template v-for="(seg,si) in bodyHighlight('resBody-'+selectedLogItem.log.id, selectedLogItem._detail.responseBody)" :key="si"><span v-if="seg.hl" class="pv-highlight" :class="{'pv-highlight-current': bodyHlIsCurrent('resBody-'+selectedLogItem.log.id, selectedLogItem._detail.responseBody, si)}">{{seg.text}}</span><template v-else>{{seg.text}}</template></template></template><template v-else>{{selectedLogItem._detail.responseBody}}</template></pre>
              <div v-else class="log-body-empty"><i class="bi bi-file-earmark" aria-hidden="true"></i>{{t('stats.emptyResponseBody')}}</div>
            </section>
          </div>

          <div v-else-if="inspectorTab==='overview'" class="log-inspector-content log-overview-surface"
            role="tabpanel" :id="'log-inspector-overview-'+selectedLogItem.log.id">
            <section class="log-overview-section" :aria-labelledby="'request-heading-'+selectedLogItem.log.id">
              <h3 class="log-detail-heading" :id="'request-heading-'+selectedLogItem.log.id">{{t('stats.sectionRequest')}}</h3>
              <dl class="log-detail-fields">
                <div><dt>{{t('stats.detailTime')}}</dt><dd class="tabular-nums">{{fmtTime(selectedLogItem.log.requestTime, false)}}</dd></div>
                <div><dt>{{t('stats.detailProtocol')}}</dt><dd><span class="badge" :class="'badge-'+selectedLogItem.log.protocol?.toLowerCase()">{{selectedLogItem.log.protocol}}</span></dd></div>
                <div v-if="selectedLogItem.log.method"><dt>{{t('stats.detailMethod')}}</dt><dd><span class="log-method">{{selectedLogItem.log.method}}</span></dd></div>
                <div><dt>{{t('stats.detailEndpoint')}}</dt><dd><code>{{selectedLogItem.log.endpoint}}</code></dd></div>
                <div v-if="selectedLogItem.log.targetHost"><dt>{{t('stats.detailTargetHost')}}</dt><dd><code>{{selectedLogItem.log.targetHost}}</code></dd></div>
                <div v-if="selectedLogItem.log.clientIp"><dt>{{t('stats.detailClientIp')}}</dt><dd>{{selectedLogItem.log.clientIp}}</dd></div>
              </dl>
            </section>
            <section class="log-overview-section" :aria-labelledby="'result-heading-'+selectedLogItem.log.id">
              <h3 class="log-detail-heading" :id="'result-heading-'+selectedLogItem.log.id">{{t('stats.sectionMatch')}}</h3>
              <dl class="log-detail-fields">
                <div><dt>{{t('stats.detailMatched')}}</dt><dd>
                  <span class="log-outcome" :class="selectedLogItem.log.matched?'log-outcome-success':'log-outcome-danger'">
                    <i class="bi" :class="selectedLogItem.log.matched?'bi-check2-circle':'bi-x-circle'" aria-hidden="true"></i>
                    {{selectedLogItem.log.matched ? t('stats.matched') : t('stats.unmatched')}}
                  </span>
                </dd></div>
                <div v-if="selectedLogItem.log.ruleId"><dt>{{t('stats.detailRuleId')}}</dt><dd><a href="#" class="log-rule-link" @click.prevent.stop="$emit('go-to-rule', selectedLogItem.log.ruleId)">{{selectedLogItem.log.ruleId}}</a></dd></div>
                <div v-if="selectedLogItem.rule?.description"><dt>{{t('stats.detailRuleDesc')}}</dt><dd>{{selectedLogItem.rule.description}}</dd></div>
                <div><dt>{{t('stats.detailDuration')}}</dt><dd class="tabular-nums">{{selectedLogItem.log.responseTimeMs}} ms</dd></div>
                <div v-if="selectedLogItem.log.responseStatus != null"><dt>{{t('stats.detailResponseStatus')}}</dt><dd><span class="log-status-code" :class="selectedLogItem.log.responseStatus<400?'is-success':selectedLogItem.log.responseStatus<500?'is-warning':'is-danger'">{{selectedLogItem.log.responseStatus}}</span></dd></div>
                <div v-if="selectedLogItem.log.faultType && selectedLogItem.log.faultType !== 'NONE'"><dt>{{t('stats.detailFaultType')}}</dt><dd><span class="log-outcome log-outcome-warning"><i class="bi bi-lightning" aria-hidden="true"></i>{{t('rules.fault_' + selectedLogItem.log.faultType)}}</span></dd></div>
                <div v-if="selectedLogItem.log.scenarioName"><dt>{{t('stats.detailScenario')}}</dt><dd>{{selectedLogItem.log.scenarioName}}</dd></div>
                <div v-if="selectedLogItem.log.scenarioToState"><dt>{{t('stats.detailScenarioTransition')}}</dt><dd class="log-scenario-transition"><span>{{selectedLogItem.log.scenarioFromState || 'Started'}}</span><i class="bi bi-arrow-right" aria-hidden="true"></i><strong>{{selectedLogItem.log.scenarioToState}}</strong></dd></div>
                <div v-if="selectedLogItem.log.matchTimeMs != null"><dt>{{t('stats.detailMatchTime')}}</dt><dd class="tabular-nums">{{selectedLogItem.log.matchTimeMs}} ms</dd></div>
                <div v-if="selectedLogItem.log.matchTimeMs != null && selectedLogItem.log.responseTimeMs > selectedLogItem.log.matchTimeMs"><dt>{{t('stats.detailOtherTime')}}</dt><dd class="tabular-nums">{{selectedLogItem.log.responseTimeMs - selectedLogItem.log.matchTimeMs}} ms</dd></div>
                <div v-if="selectedLogItem.log.proxyStatus != null"><dt>{{t('stats.detailProxyStatus')}}</dt><dd><span class="log-status-code" :class="selectedLogItem.log.proxyStatus<400?'is-success':selectedLogItem.log.proxyStatus<500?'is-warning':'is-danger'">{{selectedLogItem.log.proxyStatus}}</span></dd></div>
                <div v-if="selectedLogItem.log.proxyError"><dt>{{t('stats.detailProxyError')}}</dt><dd class="log-error-text">{{selectedLogItem.log.proxyError}}</dd></div>
              </dl>
            </section>
          </div>

          <div v-else class="log-inspector-content log-inspector-trace" role="tabpanel"
            :id="'log-inspector-trace-'+selectedLogItem.log.id">
            <ol class="match-chain-list">
              <li v-for="(c,i) in selectedLogItem.matchChainData" :key="c.ruleId" class="match-chain-item" :class="{'match-chain-match':c.reason==='match'}">
                <span class="match-chain-num" :aria-label="t('stats.matchChainStep', {step:i+1})">{{i+1}}</span>
                <div class="match-chain-identity">
                  <div class="match-chain-rule">
                    <a v-if="c.endpoint" href="#" @click.prevent.stop="$emit('go-to-rule', c.ruleId)" class="match-chain-id" :title="c.ruleId">{{shortId(c.ruleId)}}</a>
                    <span v-else class="match-chain-id" :title="c.ruleId">{{shortId(c.ruleId)}}</span>
                    <code v-if="c.endpoint" class="match-chain-endpoint" :title="c.endpoint">{{c.endpoint}}</code>
                  </div>
                  <span v-if="c.description" class="match-chain-desc">{{c.description}}</span>
                </div>
                <div class="match-chain-evaluation">
                  <span class="match-chain-reason" :class="'reason-'+c.reason"><i class="bi" :class="reasonIcon(c.reason)" aria-hidden="true"></i>{{reasonText(c.reason)}}</span>
                  <code v-if="c.condition" class="match-chain-cond">{{c.condition}}</code>
                  <div v-if="c.mismatch" class="match-chain-mismatch"><i class="bi bi-exclamation-triangle" aria-hidden="true"></i><span>{{c.mismatch}}</span></div>
                </div>
              </li>
            </ol>
          </div>
        </section>
        </Teleport>
      </div>
    </div>
  `
};
