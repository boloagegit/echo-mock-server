/**
 * StatsPage - 請求記錄頁面
 *
 * 顯示 Mock 請求記錄，含統計摘要、篩選、排序、匹配鏈追蹤。
 * 列表只顯示摘要，展開時 lazy load detail（body / matchChain）。
 * 收合再展開使用快取，不重新查詢。
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
      bodyOverflow: {},
      bodySearch: {},
      bodySearchIdx: {},
      bodyFormatted: {},
    };
  },
  watch: {
    logDetailExpanded: {
      deep: true,
      handler(newVal) {
        this.$nextTick(() => {
          for (const item of this.pagedLogs) {
            const id = item.log.id;
            if (!id || !newVal[id]) { continue; }
            // 新展開的 row — 自動啟用 CodeMirror 格式化
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
        // 檢查 overflow（僅非 CodeMirror 模式）
        for (const [prefix, body] of [['reqBody-', detail.requestBody], ['resBody-', detail.responseBody]]) {
          if (!body) { continue; }
          const refKey = prefix + id;
          if (this.bodyFormatted[refKey]) {
            // CodeMirror 模式：若 DOM 被重建（收合再展開），需重新掛載
            this._reinitCmIfNeeded(refKey, body);
            continue;
          }
          const refs = this.$refs[refKey];
          const el = Array.isArray(refs) ? refs[0] : refs;
          if (!el) { continue; }
          const overflows = el.scrollHeight > el.clientHeight + 2;
          if (overflows !== !!this.bodyOverflow[refKey]) {
            this.bodyOverflow = { ...this.bodyOverflow, [refKey]: overflows };
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
    shortId, fmtTime, reasonText,
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
  },
  template: /* html */`
    <div class="page" :class="{active:true}">
      <div class="page-header">
        <h1 class="page-title">{{t('stats.title')}}</h1>
        <div style="display:flex;gap:0.75rem;align-items:center;flex-wrap:wrap">
          <label class="toggle" @click.stop :title="t('stats.autoRefreshTooltip')">
            <input type="checkbox" :checked="autoRefresh" @change="$emit('toggle-auto-refresh')">
            <span class="toggle-slider"></span>
          </label>
          <span style="font-size:var(--font-sm);color:var(--muted);white-space:nowrap">{{t('stats.autoRefresh')}}</span>
          <button class="btn btn-secondary" @click="$emit('load-logs', true)" :disabled="loading.logs"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.logs}"></i> {{t('stats.refresh')}}</button>
        </div>
      </div>
      <div v-if="logSummary.maxRecords" class="alert alert-info">
        <i class="bi bi-info-circle"></i> {{t('stats.maxRecordsInfo', {count: logSummary.maxRecords})}}
      </div>
      <div class="card" style="margin-bottom:0.5rem">
        <div class="card-body filter-row">
          <div style="display:flex;gap:0.5rem;flex-wrap:wrap;align-items:center;flex:1">
            <div class="btn-group">
              <button class="btn btn-sm" :class="logFilter.protocol==='HTTP'?'btn-primary':'btn-secondary'" @click="$emit('update:logFilter', {...logFilter, protocol: logFilter.protocol==='HTTP'?'':'HTTP'})">{{httpLabel}}</button>
              <button class="btn btn-sm" :class="logFilter.protocol==='JMS'?'btn-primary':'btn-secondary'" @click="$emit('update:logFilter', {...logFilter, protocol: logFilter.protocol==='JMS'?'':'JMS'})" :disabled="!jmsEnabled">{{jmsLabel}}</button>
            </div>
            <div class="filter-divider"></div>
            <div class="btn-group">
              <button class="btn btn-sm" :class="logFilter.matched==='true'?'btn-primary':'btn-secondary'" @click="$emit('update:logFilter', {...logFilter, matched: logFilter.matched==='true'?'':'true'})">{{t('stats.filterMatched')}}</button>
              <button class="btn btn-sm" :class="logFilter.matched==='false'?'btn-primary':'btn-secondary'" @click="$emit('update:logFilter', {...logFilter, matched: logFilter.matched==='false'?'':'false'})">{{t('stats.filterUnmatched')}}</button>
            </div>
            <div class="filter-divider"></div>
            <input id="logSearch" :value="logFilter.endpoint" @input="$emit('update:logFilter', {...logFilter, endpoint: $event.target.value})" :placeholder="t('stats.searchPlaceholder')" class="form-control" style="flex:1;min-width:120px">
          </div>
        </div>
      </div>
      <div v-if="logFilterChips.length" class="filter-chips">
        <span class="filter-chip" v-for="c in logFilterChips" :key="c.key">{{c.label}} <button class="chip-remove" @click="$emit('remove-log-chip', c.key)"><i class="bi bi-x"></i></button></span>
        <button class="chip-clear" @click="$emit('clear-log-filters')">{{t('stats.clearAll')}}</button>
      </div>
      <div class="card card-table">
        <div class="card-table-body">
        <div v-if="loading.logs && !logs.length">
          <div v-for="i in 6" :key="'sk-log-'+i" class="sk-row">
            <span class="sk sk-text-sm" style="width:70px"></span>
            <span class="sk sk-badge" style="width:38px"></span>
            <span class="sk sk-badge" style="width:42px"></span>
            <span class="sk sk-text" style="width:30%;min-width:80px"></span>
            <span class="sk sk-text-sm" style="width:45px"></span>
            <span class="sk sk-badge" style="width:50px"></span>
            <span class="sk sk-text" style="width:20%"></span>
          </div>
        </div>
        <table v-if="pagedLogs.length" class="table-fixed">
          <thead><tr>
            <th style="width:96px;cursor:pointer" @click="$emit('toggle-sort','requestTime')">{{t('stats.thTime')}} <i class="bi" :class="logSort.field==='requestTime'?(logSort.asc?'bi-caret-up-fill':'bi-caret-down-fill'):'bi-arrow-down-up'"></i></th>
            <th style="cursor:pointer" @click="$emit('toggle-sort','endpoint')">{{t('stats.thRequest')}} <i class="bi" :class="logSort.field==='endpoint'?(logSort.asc?'bi-caret-up-fill':'bi-caret-down-fill'):'bi-arrow-down-up'"></i></th>
            <th class="col-hide-md" style="width:88px;cursor:pointer" @click="$emit('toggle-sort','responseTimeMs')">{{t('stats.thDuration')}} <i class="bi" :class="logSort.field==='responseTimeMs'?(logSort.asc?'bi-caret-up-fill':'bi-caret-down-fill'):'bi-arrow-down-up'"></i></th>
            <th>{{t('stats.thResult')}}</th>
            <th class="col-actions col-actions-2">{{t('stats.thActions')}}</th>
          </tr></thead>
          <tbody>
            <template v-for="item in pagedLogs" :key="item.log.id">
            <tr class="row-clickable" @click="$emit('toggle-log-detail', item)" :title="t('stats.clickExpand')">
              <td><span class="sub-info" :title="fmtTime(item.log.requestTime,false)">{{fmtTime(item.log.requestTime)}}</span></td>
              <td>
                <div style="display:flex;align-items:center;gap:0.5rem">
                  <span class="badge" :class="'badge-'+item.log.protocol?.toLowerCase()">{{item.log.protocol}}</span>
                  <span v-if="item.log.protocol==='HTTP' && item.log.method" class="badge badge-method">{{item.log.method}}</span>
                  <code style="font-weight:500" :title="item.log.endpoint">{{item.log.endpoint}}</code>
                </div>
                <div v-if="item.log.targetHost" style="margin-top:2px;font-size:0.85em"><i class="bi bi-arrow-right"></i> {{item.log.targetHost}}</div>
              </td>
              <td class="col-hide-md"><span class="sub-info">{{item.log.responseTimeMs}}ms</span></td>
              <td>
                <template v-if="item.log.matched && item.rule">
                  <span class="badge badge-success" style="margin-right:4px">{{t('stats.matched')}}</span>
                  <a href="#" class="badge badge-id" :title="item.rule.id" @click.prevent.stop="$emit('go-to-rule', item.rule.id)">{{shortId(item.rule.id)}}</a>
                  <span class="sub-info" :title="item.rule.description">{{item.rule.description}}</span>
                </template>
                <template v-else-if="item.log.matched && item.log.ruleId">
                  <span class="badge badge-success" style="margin-right:4px">{{t('stats.matched')}}</span>
                  <span class="badge badge-id" :title="item.log.ruleId">{{shortId(item.log.ruleId)}}</span>
                  <span class="sub-info" style="color:var(--muted)">{{t('stats.deleted')}}</span>
                </template>
                <template v-else-if="item.log.targetHost">
                  <span class="badge badge-secondary" style="margin-right:4px">{{t('stats.forwarded')}}</span>
                  <span v-if="item.log.proxyStatus" class="badge" :class="item.log.proxyStatus<400?'badge-success':item.log.proxyStatus<500?'badge-warning':'badge-danger'">{{item.log.proxyStatus}}</span>
                  <span v-else-if="item.log.proxyError" class="badge badge-danger" :title="item.log.proxyError">{{t('stats.forwardFailed')}}</span>
                </template>
                <template v-else>
                  <span class="badge badge-danger">{{t('stats.unmatched')}}</span>
                </template>
              </td>
              <td class="col-actions col-actions-2">
                <div style="display:flex;gap:0.25rem">
                  <button v-if="item.log.hasResponseBody || (item._detail && item._detail.responseBody)" class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('create-rule-from-log', item._detail || item.log)" :title="t('stats.createRuleFromLog')"><i class="bi bi-plus-circle"></i></button>
                  <button class="btn btn-sm btn-icon btn-secondary" :title="logDetailExpanded[item.log.id]?t('stats.collapseTrace'):t('stats.expandTrace')"><i class="bi" :class="logDetailExpanded[item.log.id]?'bi-chevron-up':'bi-chevron-down'"></i></button>
                </div>
              </td>
            </tr>
            <tr v-if="logDetailExpanded[item.log.id]" class="rule-preview-row">
              <td colspan="5" style="padding:0">
                <div class="rule-preview-content">
                  <div v-if="item._detailLoading" style="text-align:center;padding:1rem"><i class="bi bi-arrow-clockwise spin"></i></div>
                  <div class="log-detail-grid">
                    <div class="pv-fields">
                      <div class="pv-section-title">{{t('stats.sectionRequest')}}</div>
                      <div class="pv-field"><span class="pv-label">{{t('stats.detailTime')}}</span><span>{{fmtTime(item.log.requestTime, false)}}</span></div>
                      <div class="pv-field"><span class="pv-label">{{t('stats.detailProtocol')}}</span><span class="badge" :class="'badge-'+item.log.protocol?.toLowerCase()">{{item.log.protocol}}</span></div>
                      <div class="pv-field" v-if="item.log.method"><span class="pv-label">{{t('stats.detailMethod')}}</span><span class="badge badge-method">{{item.log.method}}</span></div>
                      <div class="pv-field"><span class="pv-label">{{t('stats.detailEndpoint')}}</span><code>{{item.log.endpoint}}</code></div>
                      <div class="pv-field" v-if="item.log.targetHost"><span class="pv-label">{{t('stats.detailTargetHost')}}</span><span>{{item.log.targetHost}}</span></div>
                      <div class="pv-field" v-if="item.log.clientIp"><span class="pv-label">{{t('stats.detailClientIp')}}</span><span>{{item.log.clientIp}}</span></div>
                    </div>
                    <div class="pv-fields">
                      <div class="pv-section-title">{{t('stats.sectionMatch')}}</div>
                      <div class="pv-field"><span class="pv-label">{{t('stats.detailMatched')}}</span><span class="badge" :class="item.log.matched?'badge-success':'badge-danger'">{{item.log.matched ? t('stats.matched') : t('stats.unmatched')}}</span></div>
                      <div class="pv-field" v-if="item.log.ruleId"><span class="pv-label">{{t('stats.detailRuleId')}}</span><a href="#" class="badge badge-id" @click.prevent.stop="$emit('go-to-rule', item.log.ruleId)">{{item.log.ruleId}}</a></div>
                      <div class="pv-field" v-if="item.rule?.description"><span class="pv-label">{{t('stats.detailRuleDesc')}}</span><span>{{item.rule.description}}</span></div>
                      <div class="pv-field"><span class="pv-label">{{t('stats.detailDuration')}}</span><span>{{item.log.responseTimeMs}} ms</span></div>
                      <div class="pv-field" v-if="item.log.responseStatus"><span class="pv-label">{{t('stats.detailResponseStatus')}}</span><span class="badge" :class="item.log.responseStatus<400?'badge-success':item.log.responseStatus<500?'badge-warning':'badge-danger'">{{item.log.responseStatus}}</span></div>
                      <div class="pv-field" v-if="item.log.faultType && item.log.faultType !== 'NONE'"><span class="pv-label">{{t('stats.detailFaultType')}}</span><span class="badge badge-warning"><i class="bi bi-lightning"></i> {{t('rules.fault_' + item.log.faultType)}}</span></div>
                      <div class="pv-field" v-if="item.log.matchTimeMs != null"><span class="pv-label">{{t('stats.detailMatchTime')}}</span><span>{{item.log.matchTimeMs}} ms</span></div>
                      <div class="pv-field" v-if="item.log.matchTimeMs != null && item.log.responseTimeMs > item.log.matchTimeMs"><span class="pv-label">{{t('stats.detailOtherTime')}}</span><span>{{item.log.responseTimeMs - item.log.matchTimeMs}} ms</span></div>
                      <div class="pv-field" v-if="item.log.proxyStatus"><span class="pv-label">{{t('stats.detailProxyStatus')}}</span><span class="badge" :class="item.log.proxyStatus<400?'badge-success':'badge-danger'">{{item.log.proxyStatus}}</span></div>
                      <div class="pv-field" v-if="item.log.proxyError"><span class="pv-label">{{t('stats.detailProxyError')}}</span><span style="color:var(--danger)">{{item.log.proxyError}}</span></div>
                    </div>
                  </div>
                  <div v-if="item.log.scenarioName && item.log.scenarioToState" style="margin-top:0.75rem">
                    <div class="pv-label" style="margin-bottom:0.25rem">Scenario</div>
                    <div style="display:flex;align-items:center;gap:0.5rem;padding:0.4rem 0.6rem;background:var(--card-bg);border-radius:6px;border:1px solid var(--border)">
                      <span>🔄</span>
                      <span style="font-weight:600">{{item.log.scenarioName}}</span>
                      <span style="color:var(--text-muted)">:</span>
                      <span>{{item.log.scenarioFromState || 'Started'}}</span>
                      <span style="color:var(--text-muted)">→</span>
                      <span style="font-weight:600;color:var(--primary)">{{item.log.scenarioToState}}</span>
                    </div>
                  </div>
                  <div v-if="item.matchChainData?.length" style="margin-top:0.75rem">
                    <div class="pv-label" style="margin-bottom:0.5rem">{{t('stats.matchedRuleTitle')}}</div>
                    <div v-for="(c,i) in item.matchChainData" :key="c.ruleId" class="match-chain-item" :class="{'match-chain-match':c.reason==='match'}">
                      <div class="match-chain-main">
                        <span class="match-chain-num">{{i+1}}</span>
                        <a v-if="c.endpoint" href="#" @click.prevent.stop="$emit('go-to-rule', c.ruleId)" class="match-chain-id" :title="c.ruleId">{{shortId(c.ruleId)}}</a>
                        <span v-else class="match-chain-id" :title="c.ruleId">{{shortId(c.ruleId)}}</span>
                        <code v-if="c.endpoint" class="match-chain-endpoint" style="font-weight:500">{{c.endpoint}}</code>
                        <span v-if="c.description" class="match-chain-desc">{{c.description}}</span>
                        <span v-if="c.condition" class="match-chain-cond">{{c.condition}}</span>
                        <span class="match-chain-reason" :class="'reason-'+c.reason">{{reasonText(c.reason)}}</span>
                      </div>
                      <div v-if="c.mismatch" class="match-chain-mismatch"><i class="bi bi-exclamation-triangle"></i> {{c.mismatch}}</div>
                    </div>
                  </div>
                  <div v-if="item._detail && (item._detail.requestBody || item._detail.responseBody)" style="margin-top:0.75rem">
                    <div class="pv-section-title">{{t('stats.sectionBody')}}</div>
                    <div class="log-detail-grid log-body-grid">
                      <div v-if="item._detail.requestBody" class="log-body-col">
                        <div class="pv-body-header" style="display:flex;align-items:center;justify-content:space-between;margin-bottom:0.25rem">
                          <span class="pv-label"><i class="bi bi-arrow-up-circle"></i> {{t('stats.detailRequestBody')}}</span>
                          <div style="display:flex;align-items:center;gap:0.35rem">
                            <div class="pv-search-bar">
                              <input :value="bodySearch['reqBody-'+item.log.id]||''" @input="setBodySearch('reqBody-'+item.log.id, $event.target.value)" :placeholder="t('rules.pvSearchBody')" @keydown.enter.prevent="bodyNavSearch('reqBody-'+item.log.id, bodyFormatted['reqBody-'+item.log.id] ? getFormattedText('reqBody-'+item.log.id, item._detail.requestBody) : item._detail.requestBody, $event.shiftKey ? -1 : 1)">
                              <span v-if="bodySearch['reqBody-'+item.log.id]" class="pv-search-count">{{(bodySearchIdx['reqBody-'+item.log.id]||0)+1}}/{{bodyMatchCount('reqBody-'+item.log.id, bodyFormatted['reqBody-'+item.log.id] ? getFormattedText('reqBody-'+item.log.id, item._detail.requestBody) : item._detail.requestBody)}}</span>
                              <button v-if="bodySearch['reqBody-'+item.log.id]" @click="bodyNavSearch('reqBody-'+item.log.id, bodyFormatted['reqBody-'+item.log.id] ? getFormattedText('reqBody-'+item.log.id, item._detail.requestBody) : item._detail.requestBody, -1)" :title="t('rules.pvSearchPrev')"><i class="bi bi-chevron-up"></i></button>
                              <button v-if="bodySearch['reqBody-'+item.log.id]" @click="bodyNavSearch('reqBody-'+item.log.id, bodyFormatted['reqBody-'+item.log.id] ? getFormattedText('reqBody-'+item.log.id, item._detail.requestBody) : item._detail.requestBody, 1)" :title="t('rules.pvSearchNext')"><i class="bi bi-chevron-down"></i></button>
                            </div>
                            <button class="btn btn-sm btn-icon btn-secondary" :class="{'active': bodyFormatted['reqBody-'+item.log.id]}" @click.stop="toggleBodyFormat('reqBody-'+item.log.id, item._detail.requestBody)" :title="t('rules.pvFormat')"><i class="bi bi-code-slash"></i></button>
                            <button class="btn btn-sm btn-icon btn-secondary" @click.stop="copyBody(item._detail.requestBody)" :title="t('rules.copyFullContent')"><i class="bi bi-clipboard"></i></button>
                          </div>
                        </div>
                        <div v-if="bodyFormatted['reqBody-'+item.log.id]" :ref="'reqBody-'+item.log.id" class="pv-cm-container" :class="{'expanded': item._reqExpanded}"></div>
                        <pre v-else :ref="'reqBody-'+item.log.id" class="pv-pre" :class="{'expanded': item._reqExpanded}"><template v-if="bodySearch['reqBody-'+item.log.id] && item._detail.requestBody"><template v-for="(seg,si) in bodyHighlight('reqBody-'+item.log.id, item._detail.requestBody)" :key="si"><span v-if="seg.hl" class="pv-highlight" :class="{'pv-highlight-current': bodyHlIsCurrent('reqBody-'+item.log.id, item._detail.requestBody, si)}">{{seg.text}}</span><template v-else>{{seg.text}}</template></template></template><template v-else>{{item._detail.requestBody}}</template></pre>
                        <button v-if="item._reqExpanded || bodyOverflow['reqBody-'+item.log.id] || bodyFormatted['reqBody-'+item.log.id]" class="pv-expand-btn" @click.stop="item._reqExpanded = !item._reqExpanded">
                          <i class="bi" :class="item._reqExpanded ? 'bi-chevron-compact-up' : 'bi-chevron-compact-down'"></i>
                          {{item._reqExpanded ? t('rules.pvCollapse') : t('rules.pvExpand')}}
                        </button>
                      </div>
                      <div v-if="item._detail.responseBody" class="log-body-col">
                        <div class="pv-body-header" style="display:flex;align-items:center;justify-content:space-between;margin-bottom:0.25rem">
                          <span class="pv-label"><i class="bi bi-arrow-down-circle"></i> {{t('stats.detailResponseBody')}}</span>
                          <div style="display:flex;align-items:center;gap:0.35rem">
                            <div class="pv-search-bar">
                              <input :value="bodySearch['resBody-'+item.log.id]||''" @input="setBodySearch('resBody-'+item.log.id, $event.target.value)" :placeholder="t('rules.pvSearchBody')" @keydown.enter.prevent="bodyNavSearch('resBody-'+item.log.id, bodyFormatted['resBody-'+item.log.id] ? getFormattedText('resBody-'+item.log.id, item._detail.responseBody) : item._detail.responseBody, $event.shiftKey ? -1 : 1)">
                              <span v-if="bodySearch['resBody-'+item.log.id]" class="pv-search-count">{{(bodySearchIdx['resBody-'+item.log.id]||0)+1}}/{{bodyMatchCount('resBody-'+item.log.id, bodyFormatted['resBody-'+item.log.id] ? getFormattedText('resBody-'+item.log.id, item._detail.responseBody) : item._detail.responseBody)}}</span>
                              <button v-if="bodySearch['resBody-'+item.log.id]" @click="bodyNavSearch('resBody-'+item.log.id, bodyFormatted['resBody-'+item.log.id] ? getFormattedText('resBody-'+item.log.id, item._detail.responseBody) : item._detail.responseBody, -1)" :title="t('rules.pvSearchPrev')"><i class="bi bi-chevron-up"></i></button>
                              <button v-if="bodySearch['resBody-'+item.log.id]" @click="bodyNavSearch('resBody-'+item.log.id, bodyFormatted['resBody-'+item.log.id] ? getFormattedText('resBody-'+item.log.id, item._detail.responseBody) : item._detail.responseBody, 1)" :title="t('rules.pvSearchNext')"><i class="bi bi-chevron-down"></i></button>
                            </div>
                            <button class="btn btn-sm btn-icon btn-secondary" :class="{'active': bodyFormatted['resBody-'+item.log.id]}" @click.stop="toggleBodyFormat('resBody-'+item.log.id, item._detail.responseBody)" :title="t('rules.pvFormat')"><i class="bi bi-code-slash"></i></button>
                            <button class="btn btn-sm btn-icon btn-secondary" @click.stop="copyBody(item._detail.responseBody)" :title="t('rules.copyFullContent')"><i class="bi bi-clipboard"></i></button>
                          </div>
                        </div>
                        <div v-if="bodyFormatted['resBody-'+item.log.id]" :ref="'resBody-'+item.log.id" class="pv-cm-container" :class="{'expanded': item._resExpanded}"></div>
                        <pre v-else :ref="'resBody-'+item.log.id" class="pv-pre" :class="{'expanded': item._resExpanded}"><template v-if="bodySearch['resBody-'+item.log.id] && item._detail.responseBody"><template v-for="(seg,si) in bodyHighlight('resBody-'+item.log.id, item._detail.responseBody)" :key="si"><span v-if="seg.hl" class="pv-highlight" :class="{'pv-highlight-current': bodyHlIsCurrent('resBody-'+item.log.id, item._detail.responseBody, si)}">{{seg.text}}</span><template v-else>{{seg.text}}</template></template></template><template v-else>{{item._detail.responseBody}}</template></pre>
                        <button v-if="item._resExpanded || bodyOverflow['resBody-'+item.log.id] || bodyFormatted['resBody-'+item.log.id]" class="pv-expand-btn" @click.stop="item._resExpanded = !item._resExpanded">
                          <i class="bi" :class="item._resExpanded ? 'bi-chevron-compact-up' : 'bi-chevron-compact-down'"></i>
                          {{item._resExpanded ? t('rules.pvCollapse') : t('rules.pvExpand')}}
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              </td>
            </tr>
            </template>
          </tbody>
        </table>
        <div v-if="!pagedLogs.length && !loading.logs" class="empty"><i class="bi bi-inbox"></i><div>{{t('stats.emptyNoLogs')}}</div></div>
        </div>
        <div class="card-table-footer">
          <span class="sub-info">{{t('stats.totalCount', {count: logs.length})}}</span>
          <span v-if="logFilter.protocol||logFilter.matched||logFilter.endpoint" class="badge badge-warning" style="margin-left:8px;cursor:pointer" :title="t('stats.clickClearFilter')" @click="$emit('update:logFilter', {protocol:'',matched:'',endpoint:''})"><i class="bi bi-funnel-fill"></i> {{t('stats.filtering')}}</span>
          <div class="pagination-controls">
            <button class="btn btn-sm btn-secondary" @click="$emit('update:logPage', 1)" :disabled="logPage===1" aria-label="First page"><i class="bi bi-chevron-double-left"></i></button>
            <button class="btn btn-sm btn-secondary" @click="$emit('update:logPage', logPage-1)" :disabled="logPage===1" aria-label="Previous page"><i class="bi bi-chevron-left"></i></button>
            <span>{{logPage}} / {{totalPages}}</span>
            <button class="btn btn-sm btn-secondary" @click="$emit('update:logPage', logPage+1)" :disabled="logPage>=totalPages" aria-label="Next page"><i class="bi bi-chevron-right"></i></button>
            <button class="btn btn-sm btn-secondary" @click="$emit('update:logPage', totalPages)" :disabled="logPage>=totalPages" aria-label="Last page"><i class="bi bi-chevron-double-right"></i></button>
          </div>
          <select :value="logPageSize" @change="$emit('update:logPageSize', Number($event.target.value))" class="form-control" style="width:auto" aria-label="Page size"><option :value="10">10</option><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option></select>
        </div>
      </div>
    </div>
  `
};
