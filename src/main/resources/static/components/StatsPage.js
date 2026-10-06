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
    scenarioEnabled: Boolean,
    httpLabel: String,
    jmsLabel: String,
    rules: Array,
    logDetailExpanded: Object,
    detectMode: { type: Function, default: null }
  },
  emits: [
    'load-logs', 'update:logFilter', 'update:logSort',
    'update:logPage', 'update:logPageSize',
    'toggle-sort', 'toggle-match-chain', 'toggle-log-detail', 'go-to-rule',
    'remove-log-chip', 'clear-log-filters', 'clip-copy',
    'create-rule-from-log'
  ],
  inject: ['t'],
  mixins: [heldDetailMixin(function () {
    const item = this.selectedLogItem;
    return { open: !!item && !this.loading.logsError, key: item?.log.id, ready: !item?._detailLoading, value: item };
  })],
  data() {
    return {
      bodySearch: {},
      bodySearchIdx: {},
      bodyFormatted: {},
      inspectorTab: 'body',
    };
  },
  computed: {
    hasLogFilters() {
      return Boolean(this.logFilter.protocol || this.logFilter.matched || this.logFilter.endpoint);
    },
    selectedLogItem() {
      return this.pagedLogs.find(item => !!this.logDetailExpanded[item.log.id]) || null;
    },
    selectedIndex() {
      return this.selectedLogItem ? this.pagedLogs.indexOf(this.selectedLogItem) : -1;
    },
    shownLogItem() {
      return this.held.value;
    },
    inspectorTabs() {
      const id = this.shownLogItem?.log?.id || 'none';
      return [
        { value: 'body', label: this.t('stats.inspectorBodyTab'), icon: 'bi-braces', id: 'log-tab-body-' + id, panelId: 'log-inspector-body-' + id },
        { value: 'overview', label: this.t('stats.inspectorOverviewTab'), icon: 'bi-list-ul', id: 'log-tab-overview-' + id, panelId: 'log-inspector-overview-' + id },
        { value: 'trace', label: this.t('stats.matchChainTitle'), icon: 'bi-diagram-3', count: this.shownLogItem?.matchChainData?.length || null,
          disabled: !this.shownLogItem?.matchChainData?.length, id: 'log-tab-trace-' + id, panelId: 'log-inspector-trace-' + id },
      ];
    },
    protocolFilterOptions() {
      return [
        { value: 'HTTP', label: this.httpLabel },
        { value: 'JMS', label: this.jmsLabel, disabled: !this.jmsEnabled },
      ];
    },
    resultFilterOptions() {
      return [
        { value: 'true', label: this.t('stats.filterMatched') },
        { value: 'false', label: this.t('stats.filterUnmatched') },
      ];
    },
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
      if (log.protocol !== 'HTTP') { return null; }
      return log.proxyStatus != null ? log.proxyStatus : log.responseStatus;
    },
    forwardTargetName(value) {
      if (!value) { return ''; }
      const separator = value.indexOf('|');
      return separator === -1 ? value.trim() : value.slice(0, separator).trim();
    },
    requestDescription(item) {
      if (item.rule?.description) { return item.rule.description; }
      if (item.log.matched && item.log.ruleId) { return this.t('stats.deletedRuleDescription'); }
      if (item.log.forwarded) { return this.t('stats.defaultForwardDescription'); }
      return this.t('stats.unmatchedDescription');
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
      this.cmInstances[refKey].getInputField()?.setAttribute(
        'aria-label',
        refKey.startsWith('reqBody-') ? this.t('stats.detailRequestBody') : this.t('stats.detailResponseBody')
      );
      this.fitCodeMirror(this.cmInstances[refKey], el);
    },
    /**
     * A formatted body gets a definite height: as tall as its lines, at most 420px. Long bodies
     * then scroll inside the editor, which only renders the lines in view.
     */
    fitCodeMirror(cm, container) {
      if (!cm || !container) { return; }
      const height = Math.min(420, Math.ceil(cm.defaultTextHeight() * cm.lineCount()) + 12);
      container.style.height = height + 'px';
      cm.refresh();
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
          this.cmInstances[refKey].getInputField()?.setAttribute(
            'aria-label',
            refKey.startsWith('reqBody-') ? this.t('stats.detailRequestBody') : this.t('stats.detailResponseBody')
          );
          this.fitCodeMirror(this.cmInstances[refKey], el);
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
    logOutcome(log) {
      if (log.forwarded && log.proxyError) return this.t('stats.forwardFailed');
      if (log.forwarded) return this.t('stats.forwarded');
      return log.matched ? this.t('stats.matched') : this.t('stats.unmatched');
    },
    logTone(log) {
      if (log.forwarded && log.proxyError) return 'danger';
      if (log.forwarded) return 'neutral';
      return log.matched ? 'success' : 'danger';
    },
    logMenuItems(item, inDrawer = false) {
      const t = this.t;
      const items = [];
      if (!inDrawer && (item.log.hasResponseBody || item._detail?.responseBody)) {
        items.push({ key: 'create-rule', label: t('stats.createRuleFromLog'), icon: 'bi-plus-circle' });
      }
      if (!inDrawer && item.log.ruleId) {
        items.push({ key: 'open-rule', label: t('stats.openMatchedRule'), icon: 'bi-box-arrow-up-right' });
      }
      items.push({ key: 'copy-endpoint', label: t('stats.copyEndpoint'), icon: 'bi-copy' });
      if (item.log.diagnosticId) {
        items.push({ key: 'copy-diagnostic', label: t('stats.copyDiagnosticId'), icon: 'bi-fingerprint' });
      }
      return items;
    },
    handleLogMenu(action, item) {
      if (action === 'create-rule') this.$emit('create-rule-from-log', item._detail || item.log);
      else if (action === 'open-rule') this.$emit('go-to-rule', item.log.ruleId);
      else if (action === 'copy-endpoint') this.$emit('clip-copy', item.log.endpoint);
      else if (action === 'copy-diagnostic') this.$emit('clip-copy', item.log.diagnosticId);
    },
    selectLog(item) {
      this.$emit('toggle-log-detail', item);
    },
    onRowKeydown(event, item) {
      if (event.target !== event.currentTarget) return;
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); this.selectLog(item); }
    },
    stepDetail(direction) {
      const next = this.pagedLogs[this.selectedIndex + direction];
      if (next) {
        this.$emit('toggle-log-detail', next);
        this.$nextTick(() => document.querySelector('.logs-workspace [data-detail-row].is-selected')?.scrollIntoView({ block: 'nearest' }));
      }
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
          <ui-button type="button" variant="secondary" @click="$emit('load-logs', true)" :disabled="loading.logs">
            <i class="bi bi-arrow-clockwise" :class="{'spin':loading.logs}" aria-hidden="true"></i>{{t('stats.refresh')}}
          </ui-button>
        </div>
      </div>

      <div class="list-toolbar">
        <workspace-search-field shortcut="/"
          input-id="logSearch"
          :model-value="logFilter.endpoint"
          :placeholder="t('stats.searchPlaceholder')"
          :aria-label="t('stats.searchLabel')"
          :clear-label="t('stats.clearSearch')"
          :submit-mode="true"
          @search="$emit('update:logFilter', {...logFilter, endpoint:$event})"
        ></workspace-search-field>
        <ui-toggle-group :model-value="logFilter.protocol" :options="protocolFilterOptions" :aria-label="t('stats.protocolFilter')"
          @update:model-value="$emit('update:logFilter', {...logFilter, protocol:$event})"></ui-toggle-group>
        <ui-toggle-group :model-value="logFilter.matched" :options="resultFilterOptions" :aria-label="t('stats.resultFilter')"
          @update:model-value="$emit('update:logFilter', {...logFilter, matched:$event})"></ui-toggle-group>
      </div>

      <ui-filter-chip-list :items="logFilterChips" :aria-label="t('common.activeFilters')"
        :clear-label="t('stats.clearAll')"
        @remove="$emit('remove-log-chip', $event)"
        @clear="$emit('clear-log-filters')"></ui-filter-chip-list>

      <div class="card card-table list-card">
        <ui-load-state v-if="loading.logsError && !loading.logs" kind="error" icon="bi-cloud-slash" :title="t('stats.loadFailed')" has-action>
          <template #action><ui-button type="button" variant="secondary" size="compact" @click="$emit('load-logs', true)"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</ui-button></template>
        </ui-load-state>
        <div v-else class="card-table-body">
          <div v-if="loading.logs && !logs.length" class="list-skeleton" role="status" :aria-label="t('stats.loadingLogs')">
            <div v-for="i in 8" :key="'sk-log-'+i" class="list-skeleton__row"><span class="sk sk-w-15p"></span><span class="sk sk-w-40p"></span><span class="sk sk-w-15p"></span></div>
          </div>
          <table v-else-if="pagedLogs.length" class="data-table log-table">
            <caption class="visually-hidden">{{t('stats.tableCaption')}}</caption>
            <thead><tr>
              <th class="col-time" :aria-sort="sortAria('requestTime')">
                <ui-table-sort-header :label="t('stats.thTime')" :active="logSort.field==='requestTime'" :ascending="logSort.asc"
                  :aria-label="t('stats.sortBy', {field:t('stats.thTime')})" @toggle="$emit('toggle-sort','requestTime')"></ui-table-sort-header>
              </th>
              <th class="col-method">{{t('rules.thMethod')}}</th>
              <th class="col-request" :aria-sort="sortAria('endpoint')">
                <ui-table-sort-header :label="t('stats.thRequest')" :active="logSort.field==='endpoint'" :ascending="logSort.asc"
                  :aria-label="t('stats.sortBy', {field:t('stats.thRequest')})" @toggle="$emit('toggle-sort','endpoint')"></ui-table-sort-header>
              </th>
              <th class="col-duration cell-end" :aria-sort="sortAria('responseTimeMs')">
                <ui-table-sort-header :label="t('stats.thDuration')" :active="logSort.field==='responseTimeMs'" :ascending="logSort.asc"
                  :aria-label="t('stats.sortBy', {field:t('stats.thDuration')})" @toggle="$emit('toggle-sort','responseTimeMs')"></ui-table-sort-header>
              </th>
              <th class="col-result">{{t('stats.thResult')}}</th>
              <th class="col-actions"><span class="visually-hidden">{{t('stats.thActions')}}</span></th>
            </tr></thead>
            <tbody>
              <tr v-for="item in pagedLogs" :key="item.log.id" :id="'log-summary-'+item.log.id" data-detail-row tabindex="0"
                :class="{'is-selected': !!logDetailExpanded[item.log.id]}" :aria-selected="logDetailExpanded[item.log.id] ? 'true' : 'false'"
                @click="selectLog(item)" @keydown="onRowKeydown($event, item)">
                <td class="col-time cell-mono cell-subtle" :title="fmtTime(item.log.requestTime,false)">{{logDate(item.log.requestTime)}} {{logClock(item.log.requestTime)}}</td>
                <td class="col-method">
                  <span class="rule-method" :data-method="item.log.protocol==='HTTP' ? item.log.method : null" :data-protocol="item.log.protocol">{{item.log.protocol==='HTTP' ? (item.log.method || 'HTTP') : 'JMS'}}</span>
                </td>
                <td class="col-request">
                  <span class="rule-identity">
                    <code class="rule-path" :title="item.log.endpoint">{{item.log.endpoint}}</code>
                    <span class="rule-desc" :title="requestDescription(item)">{{requestDescription(item)}}</span>
                    <span v-if="item.log.forwardTarget" class="log-forward" :title="item.log.forwardTarget"><i class="bi bi-arrow-right" aria-hidden="true"></i>{{forwardTargetName(item.log.forwardTarget)}}</span>
                  </span>
                </td>
                <td class="col-duration cell-end cell-mono cell-subtle">{{item.log.responseTimeMs}} ms</td>
                <td class="col-result">
                  <span class="log-result-line">
                    <ui-status :tone="logTone(item.log)" :title="item.log.proxyError || null">{{logOutcome(item.log)}}</ui-status>
                    <span v-if="logStatusCode(item.log) != null" class="log-status-code"
                      :class="logStatusCode(item.log)<400?'is-success':logStatusCode(item.log)<500?'is-warning':'is-danger'">{{logStatusCode(item.log)}}</span>
                  </span>
                </td>
                <td class="col-actions" @click.stop @dblclick.stop>
                  <span class="row-actions">
                    <ui-row-menu :items="logMenuItems(item)" :label="t('common.moreActions') + ' ' + item.log.endpoint" @select="handleLogMenu($event, item)"></ui-row-menu>
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
          <ui-load-state v-else kind="empty" has-action
            :icon="hasLogFilters?'bi-search':'bi-inbox'"
            :title="hasLogFilters?t('stats.emptyNoMatch'):t('stats.emptyNoLogs')"
            :hint="hasLogFilters?t('stats.emptyNoMatchHint'):t('stats.emptyHint')">
            <template #action><ui-button v-if="hasLogFilters" type="button" variant="secondary" size="compact" @click="$emit('clear-log-filters')">{{t('stats.clearAll')}}</ui-button></template>
          </ui-load-state>
        </div>

        <workspace-pagination v-if="!loading.logsError"
          :page="logPage" :total-pages="totalPages" :page-size="logPageSize"
          :pagination-label="t('stats.pagination')"
          :page-status-label="t('stats.pageStatus', {page:logPage, total:totalPages})"
          :page-size-label="t('stats.pageSize')"
          :first-page-label="t('stats.firstPage')" :previous-page-label="t('stats.previousPage')"
          :next-page-label="t('stats.nextPage')" :last-page-label="t('stats.lastPage')"
          :scroll-hint-label="t('common.scrollForMore')"
          :scroll-region-label="t('stats.tableCaption')"
          @update:page="$emit('update:logPage', $event)"
          @update:page-size="$emit('update:logPageSize', $event)"
        >
          <template #summary>
            <span class="sub-info">{{t('stats.totalCount', {count: logSummary.filteredRequests ?? logs.length})}}</span>
          </template>
        </workspace-pagination>
      </div>

      <ui-detail-drawer class="log-detail-drawer" :open="held.open" :stale="held.stale"
        :title="shownLogItem?.log.endpoint || ''" :subtitle="shownLogItem ? requestDescription(shownLogItem) : ''"
        :has-prev="selectedIndex > 0" :has-next="selectedIndex >= 0 && selectedIndex < pagedLogs.length - 1"
        @close="selectedLogItem && $emit('toggle-log-detail', selectedLogItem)" @prev="stepDetail(-1)" @next="stepDetail(1)">
        <template v-if="shownLogItem" #meta>
          <span class="rule-method" :data-method="shownLogItem.log.protocol==='HTTP' ? shownLogItem.log.method : null" :data-protocol="shownLogItem.log.protocol">{{shownLogItem.log.protocol==='HTTP' ? (shownLogItem.log.method || 'HTTP') : 'JMS'}}</span>
          <ui-status :tone="logTone(shownLogItem.log)">{{logOutcome(shownLogItem.log)}}</ui-status>
          <span v-if="logStatusCode(shownLogItem.log) != null" class="log-status-code"
            :class="logStatusCode(shownLogItem.log)<400?'is-success':logStatusCode(shownLogItem.log)<500?'is-warning':'is-danger'">{{logStatusCode(shownLogItem.log)}}</span>
          <span class="detail-mono">{{shownLogItem.log.responseTimeMs}} ms · {{fmtTime(shownLogItem.log.requestTime, false)}}</span>
        </template>
        <template v-if="shownLogItem" #actions>
          <ui-button v-if="shownLogItem.log.hasResponseBody || shownLogItem._detail?.responseBody" type="button" variant="primary" size="compact"
            @click="$emit('create-rule-from-log', shownLogItem._detail || shownLogItem.log)"><i class="bi bi-plus-circle" aria-hidden="true"></i>{{t('stats.createRuleFromLog')}}</ui-button>
          <ui-button v-if="shownLogItem.log.ruleId" type="button" variant="secondary" size="compact" @click="$emit('go-to-rule', shownLogItem.log.ruleId)"><i class="bi bi-box-arrow-up-right" aria-hidden="true"></i>{{t('stats.openMatchedRule')}}</ui-button>
          <ui-row-menu :items="logMenuItems(shownLogItem, true)" :label="t('common.moreActions')" @select="handleLogMenu($event, shownLogItem)"></ui-row-menu>
        </template>
        <section v-if="shownLogItem" class="log-inspector" :id="'log-detail-'+shownLogItem.log.id">
          <ui-tabs class="log-inspector-tabs" variant="compact" v-model="inspectorTab"
            :items="inspectorTabs" :aria-label="t('stats.inspectorViews')"></ui-tabs>

          <div v-if="shownLogItem._detailLoading" class="log-inspector-loading loading-reveal" role="status" aria-live="polite">
            <i class="bi bi-arrow-clockwise spin" aria-hidden="true"></i>{{t('stats.loadingDetail')}}
          </div>

          <div v-else-if="shownLogItem._detailError" class="log-inspector-loading log-inspector-error" role="alert">
            <i class="bi bi-cloud-slash" aria-hidden="true"></i><span>{{t('stats.detailLoadFailed')}}</span>
            <ui-button type="button" variant="secondary" size="compact" @click="$emit('toggle-log-detail', shownLogItem)"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</ui-button>
          </div>

          <div v-else-if="inspectorTab==='body'" class="log-inspector-content log-inspector-body-grid"
            role="tabpanel" :id="'log-inspector-body-'+shownLogItem.log.id" :aria-labelledby="'log-tab-body-'+shownLogItem.log.id">
            <section class="log-inspector-pane ui-detail-panel" :aria-labelledby="'request-body-heading-'+shownLogItem.log.id">
              <div class="log-inspector-pane-header">
                <h2 class="ui-detail-panel-heading" :id="'request-body-heading-'+shownLogItem.log.id">{{t('stats.detailRequestBody')}}</h2>
                <span class="log-body-size tabular-nums">{{fmtSize(shownLogItem._detail?.requestBody?.length || 0)}}</span>
                <div v-if="shownLogItem._detail?.requestBody" class="log-body-tools">
                  <div class="pv-search-bar">
                    <input :value="bodySearch['reqBody-'+shownLogItem.log.id]||''"
                      @input="setBodySearch('reqBody-'+shownLogItem.log.id, $event.target.value)"
                      :placeholder="t('rules.pvSearchBody')" :aria-label="t('stats.searchRequestBody')"
                      @keydown.enter.prevent="bodyNavSearch('reqBody-'+shownLogItem.log.id, bodyFormatted['reqBody-'+shownLogItem.log.id] ? getFormattedText('reqBody-'+shownLogItem.log.id, shownLogItem._detail.requestBody) : shownLogItem._detail.requestBody, $event.shiftKey ? -1 : 1)">
                    <span v-if="bodySearch['reqBody-'+shownLogItem.log.id]" class="pv-search-count">{{bodyMatchLabel('reqBody-'+shownLogItem.log.id, bodyFormatted['reqBody-'+shownLogItem.log.id] ? getFormattedText('reqBody-'+shownLogItem.log.id, shownLogItem._detail.requestBody) : shownLogItem._detail.requestBody)}}</span>
                    <button v-if="bodySearch['reqBody-'+shownLogItem.log.id]" type="button"
                      @click="bodyNavSearch('reqBody-'+shownLogItem.log.id, bodyFormatted['reqBody-'+shownLogItem.log.id] ? getFormattedText('reqBody-'+shownLogItem.log.id, shownLogItem._detail.requestBody) : shownLogItem._detail.requestBody, -1)"
                      :title="t('rules.pvSearchPrev')" :aria-label="t('rules.pvSearchPrev')"><i class="bi bi-chevron-up" aria-hidden="true"></i></button>
                    <button v-if="bodySearch['reqBody-'+shownLogItem.log.id]" type="button"
                      @click="bodyNavSearch('reqBody-'+shownLogItem.log.id, bodyFormatted['reqBody-'+shownLogItem.log.id] ? getFormattedText('reqBody-'+shownLogItem.log.id, shownLogItem._detail.requestBody) : shownLogItem._detail.requestBody, 1)"
                      :title="t('rules.pvSearchNext')" :aria-label="t('rules.pvSearchNext')"><i class="bi bi-chevron-down" aria-hidden="true"></i></button>
                  </div>
                  <ui-button type="button" class="btn btn-sm btn-icon btn-secondary"
                    :class="{'active': bodyFormatted['reqBody-'+shownLogItem.log.id]}"
                    :aria-pressed="!!bodyFormatted['reqBody-'+shownLogItem.log.id]"
                    @click.stop="toggleBodyFormat('reqBody-'+shownLogItem.log.id, shownLogItem._detail.requestBody)"
                    :title="t('rules.pvFormat')" :aria-label="t('rules.pvFormat')"><i class="bi bi-braces" aria-hidden="true"></i></ui-button>
                  <ui-button type="button" class="btn btn-sm btn-icon btn-secondary" @click.stop="copyBody(shownLogItem._detail.requestBody)"
                    :title="t('rules.copyFullContent')" :aria-label="t('rules.copyFullContent')"><i class="bi bi-clipboard" aria-hidden="true"></i></ui-button>
                </div>
              </div>
              <div v-if="shownLogItem._detail?.requestBody && bodyFormatted['reqBody-'+shownLogItem.log.id]"
                :ref="'reqBody-'+shownLogItem.log.id" class="pv-cm-container"></div>
              <pre v-else-if="shownLogItem._detail?.requestBody" :ref="'reqBody-'+shownLogItem.log.id" class="pv-pre"><template v-if="bodySearch['reqBody-'+shownLogItem.log.id]"><template v-for="(seg,si) in bodyHighlight('reqBody-'+shownLogItem.log.id, shownLogItem._detail.requestBody)" :key="si"><span v-if="seg.hl" class="pv-highlight" :class="{'pv-highlight-current': bodyHlIsCurrent('reqBody-'+shownLogItem.log.id, shownLogItem._detail.requestBody, si)}">{{seg.text}}</span><template v-else>{{seg.text}}</template></template></template><template v-else>{{shownLogItem._detail.requestBody}}</template></pre>
              <div v-else class="pv-body-empty">{{t('stats.emptyRequestBody')}}</div>
            </section>

            <section class="log-inspector-pane ui-detail-panel" :aria-labelledby="'response-body-heading-'+shownLogItem.log.id">
              <div class="log-inspector-pane-header">
                <h2 class="ui-detail-panel-heading" :id="'response-body-heading-'+shownLogItem.log.id">{{t('stats.detailResponseBody')}}</h2>
                <span class="log-body-size tabular-nums">{{fmtSize(shownLogItem._detail?.responseBody?.length || 0)}}</span>
                <div v-if="shownLogItem._detail?.responseBody" class="log-body-tools">
                  <div class="pv-search-bar">
                    <input :value="bodySearch['resBody-'+shownLogItem.log.id]||''"
                      @input="setBodySearch('resBody-'+shownLogItem.log.id, $event.target.value)"
                      :placeholder="t('rules.pvSearchBody')" :aria-label="t('stats.searchResponseBody')"
                      @keydown.enter.prevent="bodyNavSearch('resBody-'+shownLogItem.log.id, bodyFormatted['resBody-'+shownLogItem.log.id] ? getFormattedText('resBody-'+shownLogItem.log.id, shownLogItem._detail.responseBody) : shownLogItem._detail.responseBody, $event.shiftKey ? -1 : 1)">
                    <span v-if="bodySearch['resBody-'+shownLogItem.log.id]" class="pv-search-count">{{bodyMatchLabel('resBody-'+shownLogItem.log.id, bodyFormatted['resBody-'+shownLogItem.log.id] ? getFormattedText('resBody-'+shownLogItem.log.id, shownLogItem._detail.responseBody) : shownLogItem._detail.responseBody)}}</span>
                    <button v-if="bodySearch['resBody-'+shownLogItem.log.id]" type="button"
                      @click="bodyNavSearch('resBody-'+shownLogItem.log.id, bodyFormatted['resBody-'+shownLogItem.log.id] ? getFormattedText('resBody-'+shownLogItem.log.id, shownLogItem._detail.responseBody) : shownLogItem._detail.responseBody, -1)"
                      :title="t('rules.pvSearchPrev')" :aria-label="t('rules.pvSearchPrev')"><i class="bi bi-chevron-up" aria-hidden="true"></i></button>
                    <button v-if="bodySearch['resBody-'+shownLogItem.log.id]" type="button"
                      @click="bodyNavSearch('resBody-'+shownLogItem.log.id, bodyFormatted['resBody-'+shownLogItem.log.id] ? getFormattedText('resBody-'+shownLogItem.log.id, shownLogItem._detail.responseBody) : shownLogItem._detail.responseBody, 1)"
                      :title="t('rules.pvSearchNext')" :aria-label="t('rules.pvSearchNext')"><i class="bi bi-chevron-down" aria-hidden="true"></i></button>
                  </div>
                  <ui-button type="button" class="btn btn-sm btn-icon btn-secondary"
                    :class="{'active': bodyFormatted['resBody-'+shownLogItem.log.id]}"
                    :aria-pressed="!!bodyFormatted['resBody-'+shownLogItem.log.id]"
                    @click.stop="toggleBodyFormat('resBody-'+shownLogItem.log.id, shownLogItem._detail.responseBody)"
                    :title="t('rules.pvFormat')" :aria-label="t('rules.pvFormat')"><i class="bi bi-braces" aria-hidden="true"></i></ui-button>
                  <ui-button type="button" class="btn btn-sm btn-icon btn-secondary" @click.stop="copyBody(shownLogItem._detail.responseBody)"
                    :title="t('rules.copyFullContent')" :aria-label="t('rules.copyFullContent')"><i class="bi bi-clipboard" aria-hidden="true"></i></ui-button>
                </div>
              </div>
              <div v-if="shownLogItem._detail?.responseBody && bodyFormatted['resBody-'+shownLogItem.log.id]"
                :ref="'resBody-'+shownLogItem.log.id" class="pv-cm-container"></div>
              <pre v-else-if="shownLogItem._detail?.responseBody" :ref="'resBody-'+shownLogItem.log.id" class="pv-pre"><template v-if="bodySearch['resBody-'+shownLogItem.log.id]"><template v-for="(seg,si) in bodyHighlight('resBody-'+shownLogItem.log.id, shownLogItem._detail.responseBody)" :key="si"><span v-if="seg.hl" class="pv-highlight" :class="{'pv-highlight-current': bodyHlIsCurrent('resBody-'+shownLogItem.log.id, shownLogItem._detail.responseBody, si)}">{{seg.text}}</span><template v-else>{{seg.text}}</template></template></template><template v-else>{{shownLogItem._detail.responseBody}}</template></pre>
              <div v-else class="pv-body-empty">{{t('stats.emptyResponseBody')}}</div>
            </section>
          </div>

          <div v-else-if="inspectorTab==='overview'" class="log-inspector-content log-overview-surface"
            role="tabpanel" :id="'log-inspector-overview-'+shownLogItem.log.id" :aria-labelledby="'log-tab-overview-'+shownLogItem.log.id">
            <section class="log-overview-section ui-detail-panel" :aria-labelledby="'request-heading-'+shownLogItem.log.id">
              <h2 class="log-detail-heading" :id="'request-heading-'+shownLogItem.log.id">{{t('stats.sectionRequest')}}</h2>
              <dl class="log-detail-fields">
                <div v-if="shownLogItem.log.diagnosticId"><dt>{{t('stats.diagnosticId')}}</dt><dd class="d-flex align-items-center gap-2">
                  <code>{{shownLogItem.log.diagnosticId}}</code>
                  <ui-button type="button" class="btn btn-sm btn-icon btn-secondary"
                    @click.stop="copyBody(shownLogItem.log.diagnosticId)"
                    :title="t('stats.copyDiagnosticId')" :aria-label="t('stats.copyDiagnosticId')"><i class="bi bi-clipboard" aria-hidden="true"></i></ui-button>
                </dd></div>
                <div><dt>{{t('stats.detailTime')}}</dt><dd class="tabular-nums">{{fmtTime(shownLogItem.log.requestTime, false)}}</dd></div>
                <div><dt>{{t('stats.detailProtocol')}}</dt><dd><ui-badge class="badge" :class="'badge-'+shownLogItem.log.protocol?.toLowerCase()">{{shownLogItem.log.protocol}}</ui-badge></dd></div>
                <div v-if="shownLogItem.log.method"><dt>{{t('stats.detailMethod')}}</dt><dd><span class="log-method">{{shownLogItem.log.method}}</span></dd></div>
                <div><dt>{{t('stats.detailEndpoint')}}</dt><dd><code>{{shownLogItem.log.endpoint}}</code></dd></div>
                <div v-if="shownLogItem.log.targetHost"><dt>{{t('stats.detailTargetHost')}}</dt><dd><code>{{shownLogItem.log.targetHost}}</code></dd></div>
                <div v-if="shownLogItem.log.forwardTarget"><dt>{{t('stats.detailForwardTarget')}}</dt><dd><code>{{shownLogItem.log.forwardTarget}}</code></dd></div>
                <div v-if="shownLogItem.log.clientIp"><dt>{{t('stats.detailClientIp')}}</dt><dd>{{shownLogItem.log.clientIp}}</dd></div>
              </dl>
            </section>
            <section class="log-overview-section ui-detail-panel" :aria-labelledby="'result-heading-'+shownLogItem.log.id">
              <h2 class="log-detail-heading" :id="'result-heading-'+shownLogItem.log.id">{{t('stats.sectionMatch')}}</h2>
              <dl class="log-detail-fields">
                <div><dt>{{t('stats.detailMatched')}}</dt><dd>
                  <span class="log-outcome" :class="shownLogItem.log.matched?'log-outcome-success':'log-outcome-danger'">{{shownLogItem.log.matched ? t('stats.matched') : t('stats.unmatched')}}</span>
                </dd></div>
                <div v-if="shownLogItem.log.ruleId"><dt>{{t('stats.detailRuleId')}}</dt><dd><a href="#" class="log-rule-link" @click.prevent.stop="$emit('go-to-rule', shownLogItem.log.ruleId)">{{shownLogItem.log.ruleId}}</a></dd></div>
                <div v-if="shownLogItem.rule?.description"><dt>{{t('stats.detailRuleDesc')}}</dt><dd>{{shownLogItem.rule.description}}</dd></div>
                <div><dt>{{t('stats.detailDuration')}}</dt><dd class="tabular-nums">{{shownLogItem.log.responseTimeMs}} ms</dd></div>
                <div><dt>{{t('stats.detailForwarded')}}</dt><dd>{{shownLogItem.log.forwarded ? t('stats.forwarded') : t('stats.notForwarded')}}</dd></div>
                <div v-if="shownLogItem.log.protocol==='HTTP' && shownLogItem.log.responseStatus != null"><dt>{{t('stats.detailResponseStatus')}}</dt><dd><span class="log-status-code" :class="shownLogItem.log.responseStatus<400?'is-success':shownLogItem.log.responseStatus<500?'is-warning':'is-danger'">{{shownLogItem.log.responseStatus}}</span></dd></div>
                <div v-if="shownLogItem.log.faultType && shownLogItem.log.faultType !== 'NONE'"><dt>{{t('stats.detailFaultType')}}</dt><dd><span class="log-outcome log-outcome-warning"><i class="bi bi-lightning" aria-hidden="true"></i>{{t('rules.fault_' + shownLogItem.log.faultType)}}</span></dd></div>
                <div v-if="scenarioEnabled && shownLogItem.log.scenarioName"><dt>{{t('stats.detailScenario')}}</dt><dd>{{shownLogItem.log.scenarioName}}</dd></div>
                <div v-if="scenarioEnabled && shownLogItem.log.scenarioToState"><dt>{{t('stats.detailScenarioTransition')}}</dt><dd class="log-scenario-transition"><span>{{shownLogItem.log.scenarioFromState || 'Started'}}</span><i class="bi bi-arrow-right" aria-hidden="true"></i><strong>{{shownLogItem.log.scenarioToState}}</strong></dd></div>
                <div v-if="shownLogItem.log.matchTimeMs != null"><dt>{{t('stats.detailMatchTime')}}</dt><dd class="tabular-nums">{{shownLogItem.log.matchTimeMs}} ms</dd></div>
                <div v-if="shownLogItem.log.matchTimeMs != null && shownLogItem.log.responseTimeMs > shownLogItem.log.matchTimeMs"><dt>{{t('stats.detailOtherTime')}}</dt><dd class="tabular-nums">{{shownLogItem.log.responseTimeMs - shownLogItem.log.matchTimeMs}} ms</dd></div>
                <div v-if="shownLogItem.log.protocol==='HTTP' && shownLogItem.log.proxyStatus != null"><dt>{{t('stats.detailProxyStatus')}}</dt><dd><span class="log-status-code" :class="shownLogItem.log.proxyStatus<400?'is-success':shownLogItem.log.proxyStatus<500?'is-warning':'is-danger'">{{shownLogItem.log.proxyStatus}}</span></dd></div>
                <div v-if="shownLogItem.log.proxyError"><dt>{{t('stats.detailProxyError')}}</dt><dd class="log-error-text">{{shownLogItem.log.proxyError}}</dd></div>
              </dl>
            </section>
          </div>

          <div v-else class="log-inspector-content log-inspector-trace ui-detail-panel" role="tabpanel"
            :id="'log-inspector-trace-'+shownLogItem.log.id" :aria-labelledby="'log-tab-trace-'+shownLogItem.log.id">
            <ol class="match-chain-list">
              <li v-for="(c,i) in shownLogItem.matchChainData" :key="c.ruleId" class="match-chain-item" :class="{'match-chain-match':c.reason==='match'}">
                <span class="match-chain-num" role="img" :aria-label="t('stats.matchChainStep', {step:i+1})">{{i+1}}</span>
                <div class="match-chain-identity">
                  <div class="match-chain-rule">
                    <a v-if="c.endpoint" href="#" @click.prevent.stop="$emit('go-to-rule', c.ruleId)" class="match-chain-id" :title="c.ruleId">{{shortId(c.ruleId)}}</a>
                    <span v-else class="match-chain-id" :title="c.ruleId">{{shortId(c.ruleId)}}</span>
                    <code v-if="c.endpoint" class="match-chain-endpoint" :title="c.endpoint">{{c.endpoint}}</code>
                  </div>
                  <span v-if="c.description" class="match-chain-desc">{{c.description}}</span>
                </div>
                <div class="match-chain-evaluation">
                  <span class="match-chain-reason" :class="'reason-'+c.reason">{{reasonText(c.reason)}}</span>
                  <code v-if="c.condition" class="match-chain-cond">{{c.condition}}</code>
                  <div v-if="c.mismatch" class="match-chain-mismatch"><i class="bi bi-exclamation-triangle" aria-hidden="true"></i><span>{{c.mismatch}}</span></div>
                </div>
              </li>
            </ol>
          </div>
        </section>
      </ui-detail-drawer>
    </div>
  `
};
