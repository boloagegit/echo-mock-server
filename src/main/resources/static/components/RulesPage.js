/**
 * RulesPage - 模擬規則頁面
 *
 * 顯示所有 Mock 規則，支援篩選、排序、批次操作、拖曳排序、分組檢視、規則預覽。
 */
const RulesPage = {
  inject: ['t'],
  props: {
    rules: Array,
    loading: Object,
    jmsEnabled: Boolean,
    isLoggedIn: Boolean,
    httpLabel: String,
    jmsLabel: String,
    status: Object,
    ruleFilter: Object,
    ruleSort: Object,
    ruleViewMode: String,
    pagedRules: Array,
    ruleTotalElements: Number,
    ruleTotalPages: Number,
    rulePage: Number,
    rulePageSize: Number,
    batchSelectMode: Boolean,
    selectedRules: Array,
    ruleFilterChips: Array,
    showDblClickHint: Boolean,
    rulePreviewExpanded: Object,
    rulePreviewLoading: Object,
    rulePreviewError: Object,
    rulePreviewCache: Object,
    canDragRules: Boolean,
    showDataDropdown: Boolean,
    bulkImportExportEnabled: Boolean,
    expandedTagGroups: Array,
    expandedTagSubgroups: Array,
    tagKeys: Object,
    rulesByTag: Object,
    rulesByTagGroup: Object,
    groupCounts: Object,
    groupLoading: Object,
    filteredRules: Array,
    getGroupLimit: Function,
    ruleSortIcon: Function,
    dragRowClass: Function
  },
  emits: [
    'load-rules', 'open-create', 'toggle-batch-select',
    'batch-protect', 'delete-selected', 'export-rules',
    'show-import',
    'update:ruleFilter', 'update:ruleSort',
    'update:ruleViewMode', 'update:rulePage', 'update:rulePageSize',
    'update:batchSelectMode', 'update:selectedRules',
    'toggle-rule-sort', 'toggle-select-all', 'toggle-enabled',
    'open-edit', 'copy-rule', 'delete-rule', 'export-rule-json', 'show-rule-history',
    'remove-rule-chip', 'clear-rule-filters',
    'toggle-rule-preview', 'handle-rule-row-click',
    'dismiss-dblclick-hint',
    'go-to-responses',
    'clip-copy', 'extend-rule',
    'drag-start', 'drag-over', 'drag-leave', 'drop', 'drag-end',
    'toggle-data-dropdown', 'toggle-tag-group', 'toggle-tag-subgroup',
    'show-more-group', 'show-all-group',
  ],
  data() {
    return {
      pvExpanded: {},
      pvSearch: {},
      pvSearchIdx: {},
      pvOverflow: {},
    };
  },
  updated() {
    for (const key of Object.keys(this.rulePreviewExpanded)) {
      if (!this.rulePreviewExpanded[key]) { continue; }
      const refs = this.$refs['pvPre_' + key];
      const el = Array.isArray(refs) ? refs[0] : refs;
      if (!el) { continue; }
      const overflows = el.scrollHeight > el.clientHeight + 2;
      if (overflows !== !!this.pvOverflow[key]) {
        this.pvOverflow = { ...this.pvOverflow, [key]: overflows };
      }
    }
  },
  methods: {
    shortId, fmtTime, daysLeft, condTooltip, condTags, parseTags, fmtCond,
    setFilter(key, value) {
      const f = { ...this.ruleFilter };
      f[key] = value;
      this.$emit('update:ruleFilter', f);
    },
    toggleFilter(key, value) {
      const f = { ...this.ruleFilter };
      f[key] = f[key] === value ? '' : value;
      this.$emit('update:ruleFilter', f);
    },
    clearFilters() {
      this.$emit('update:ruleFilter', { protocol: '', enabled: '', isProtected: '', keyword: '' });
    },
    toggleSelection(id) {
      const arr = [...this.selectedRules];
      const idx = arr.indexOf(id);
      if (idx >= 0) { arr.splice(idx, 1); } else { arr.push(id); }
      this.$emit('update:selectedRules', arr);
    },
    togglePvExpand(id) {
      this.pvExpanded = { ...this.pvExpanded, [id]: !this.pvExpanded[id] };
    },
    setPvSearch(id, val) {
      this.pvSearch = { ...this.pvSearch, [id]: val };
      this.pvSearchIdx = { ...this.pvSearchIdx, [id]: 0 };
    },
    pvHighlight(id, text) {
      const kw = (this.pvSearch[id] || '').trim();
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
    pvMatchCount(id, text) {
      const kw = (this.pvSearch[id] || '').trim();
      if (!kw || !text) { return 0; }
      const lower = text.toLowerCase();
      const kwLower = kw.toLowerCase();
      let count = 0;
      let pos = 0;
      while ((pos = lower.indexOf(kwLower, pos)) !== -1) { count++; pos += kwLower.length; }
      return count;
    },
    pvNavSearch(id, text, dir) {
      const total = this.pvMatchCount(id, text);
      if (total === 0) { return; }
      let cur = (this.pvSearchIdx[id] || 0) + dir;
      if (cur >= total) { cur = 0; }
      if (cur < 0) { cur = total - 1; }
      this.pvSearchIdx = { ...this.pvSearchIdx, [id]: cur };
      this.$nextTick(() => {
        const refs = this.$refs['pvPre_' + id];
        const el = Array.isArray(refs) ? refs[0] : refs;
        if (!el) { return; }
        const active = el.querySelector('.pv-highlight-current');
        if (active) { active.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }
      });
    },
    pvHlIsCurrent(id, segIdx) {
      const parts = this.pvHighlight(id, this.rulePreviewCache[id]?._previewBody || '');
      let matchIdx = 0;
      for (let i = 0; i < parts.length; i++) {
        if (parts[i].hl) {
          if (i === segIdx) { return matchIdx === (this.pvSearchIdx[id] || 0); }
          matchIdx++;
        }
      }
      return false;
    },
  },
  template: /* html */`
<div class="page workspace-page rules-workspace" :class="{active:true}">
    <div class="page-header">
        <div class="page-heading">
            <h1 class="page-title">{{t('rules.title')}}</h1>
            <span class="page-count">{{ruleTotalElements}}</span>
        </div>
        <div class="page-actions">
            <button class="btn btn-secondary" @click="$emit('load-rules', true)" :disabled="loading.rules"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.rules}"></i> {{t('rules.refresh')}}</button>
            <button class="btn btn-secondary" @click="$emit('toggle-batch-select')" :disabled="!isLoggedIn" :title="!isLoggedIn?t('rules.loginRequired'):t('rules.batchSelect')" :class="{'active':batchSelectMode}"><i class="bi bi-check2-square"></i> {{t('rules.batchSelect')}}</button>
            <template v-if="batchSelectMode && selectedRules.length">
                <button class="btn btn-secondary" @click="$emit('batch-protect', true)"><i class="bi bi-shield-check"></i> {{t('rules.protectCount', {count: selectedRules.length})}}</button>
                <button class="btn btn-secondary" @click="$emit('batch-protect', false)"><i class="bi bi-shield"></i> {{t('rules.unprotect')}}</button>
                <button class="btn btn-danger" @click="$emit('delete-selected')"><i class="bi bi-trash"></i> {{t('rules.deleteCount', {count: selectedRules.length})}}</button>
            </template>
            <button class="btn btn-primary" @click="$emit('open-create')" :disabled="!isLoggedIn" :title="!isLoggedIn?t('rules.loginRequired'):t('rules.addRule')"><i class="bi bi-plus-lg"></i> {{t('rules.addRule')}}</button>
            <div v-if="isLoggedIn && bulkImportExportEnabled" class="data-dropdown-wrapper">
                <button type="button" class="btn btn-secondary btn-icon" @click.stop="$emit('toggle-data-dropdown')" :title="t('rules.exportImport')" :aria-label="t('rules.exportImport')" :aria-expanded="showDataDropdown?'true':'false'" aria-haspopup="menu"><i class="bi bi-three-dots-vertical" aria-hidden="true"></i></button>
                <div v-if="showDataDropdown" class="data-dropdown" role="menu">
                    <button type="button" class="data-dropdown-item" role="menuitem" @click="$emit('export-rules')"><i class="bi bi-box-arrow-up" aria-hidden="true"></i> {{t('rules.exportRules')}}</button>
                    <button type="button" class="data-dropdown-item" role="menuitem" @click="$emit('show-import')"><i class="bi bi-box-arrow-in-down" aria-hidden="true"></i> {{t('rules.importRules')}}</button>
                </div>
            </div>
        </div>
    </div>
    <div v-if="!jmsEnabled" class="warning-banner"><i class="bi bi-exclamation-triangle"></i> {{t('rules.jmsNotEnabled', {jmsLabel: jmsLabel})}}</div>
    <div class="card rule-filter-card workspace-filter-card">
        <div class="card-body filter-row rule-filter-bar workspace-filter-bar">
            <div class="rule-filter-controls workspace-filter-controls">
                <div class="btn-group">
                    <button class="btn btn-sm" :class="ruleFilter.protocol==='HTTP'?'btn-primary':'btn-secondary'" @click="toggleFilter('protocol','HTTP')">{{httpLabel}}</button>
                    <button class="btn btn-sm" :class="ruleFilter.protocol==='JMS'?'btn-primary':'btn-secondary'" @click="toggleFilter('protocol','JMS')" :disabled="!jmsEnabled">{{jmsLabel}}</button>
                </div>
                <div class="btn-group">
                    <button class="btn btn-sm" :class="ruleFilter.enabled==='true'?'btn-primary':'btn-secondary'" @click="toggleFilter('enabled','true')">{{t('rules.filterEnabled')}}</button>
                    <button class="btn btn-sm" :class="ruleFilter.enabled==='false'?'btn-primary':'btn-secondary'" @click="toggleFilter('enabled','false')">{{t('rules.filterDisabled')}}</button>
                </div>
                <div class="btn-group">
                    <button class="btn btn-sm" :class="ruleFilter.isProtected==='true'?'btn-primary':'btn-secondary'" @click="toggleFilter('isProtected','true')">{{t('rules.filterProtected')}}</button>
                    <button class="btn btn-sm" :class="ruleFilter.isProtected==='false'?'btn-primary':'btn-secondary'" @click="toggleFilter('isProtected','false')">{{t('rules.filterUnprotected')}}</button>
                </div>
                <workspace-search-field
                    input-id="ruleSearch"
                    :model-value="ruleFilter.keyword"
                    :placeholder="t('rules.searchPlaceholder')"
                    :aria-label="t('rules.searchPlaceholder')"
                    :clear-label="t('rules.clearSearch')"
                    :submit-mode="true"
                    :submit-label="t('common.searchAction')"
                    @search="setFilter('keyword', $event)"
                ></workspace-search-field>
            </div>
            <div class="rule-view-controls">
                <div class="btn-group">
                    <button class="btn btn-sm btn-secondary" :class="{'active':ruleViewMode==='list'}" @click="$emit('update:ruleViewMode', 'list')" :title="t('rules.listView')" :aria-label="t('rules.listView')"><i class="bi bi-list"></i></button>
                    <button class="btn btn-sm btn-secondary" :class="{'active':ruleViewMode==='group'}" @click="$emit('update:ruleViewMode', 'group')" :title="t('rules.groupView')" :aria-label="t('rules.groupView')"><i class="bi bi-collection"></i></button>
                </div>
            </div>
        </div>
    </div>
    <div v-if="ruleFilterChips.length" class="filter-chips">
        <span class="filter-chip" v-for="c in ruleFilterChips" :key="c.key">{{c.label}} <button class="chip-remove" @click="$emit('remove-rule-chip', c.key)"><i class="bi bi-x"></i></button></span>
        <button class="chip-clear" @click="$emit('clear-rule-filters')">{{t('rules.clearAll')}}</button>
    </div>
    <div v-if="showDblClickHint && pagedRules.length" class="dblclick-hint">
        <i class="bi bi-info-circle"></i> {{t('rules.dblClickHint')}}
        <button class="hint-dismiss" @click="$emit('dismiss-dblclick-hint')" :title="t('rules.close')"><i class="bi bi-x"></i></button>
    </div>
    <div class="card card-table">
        <div v-if="loading.rulesError && !loading.rules" class="workspace-load-error" role="alert">
            <i class="bi bi-cloud-slash" aria-hidden="true"></i>
            <strong>{{t('rules.loadFailed')}}</strong>
            <button type="button" class="btn btn-sm btn-secondary" @click="$emit('load-rules', true)"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</button>
        </div>
        <!-- 列表檢視 -->
        <template v-else-if="ruleViewMode==='list'">
        <!-- Skeleton -->
        <div v-if="loading.rules && !rules.length" class="card-table-body">
            <div v-for="i in 6" :key="'sk-rule-'+i" class="sk-row">
                <span class="sk sk-badge" style="width:60px"></span>
                <span class="sk sk-badge" style="width:38px"></span>
                <span class="sk sk-text" style="width:40%;min-width:80px"></span>
                <span class="sk sk-text" style="width:15%"></span>
                <span style="margin-left:auto;display:flex;gap:4px"><span class="sk sk-btn"></span><span class="sk sk-btn"></span></span>
            </div>
        </div>
        <div class="card-table-body">
        <table v-if="pagedRules.length" class="table-fixed">
            <thead><tr>
                <th v-if="canDragRules" style="width:28px"></th>
                <th v-if="batchSelectMode" style="width:40px"><input type="checkbox" @change="$emit('toggle-select-all', $event)" :checked="selectedRules.length===pagedRules.length && pagedRules.length>0" :aria-label="t('rules.selectAll')"></th>
                <th class="col-id">{{t('rules.thId')}}</th>
                <th class="col-type col-hide-sm">{{t('rules.thProtocol')}}</th>
                <th class="col-endpoint">{{t('rules.thEndpoint')}}</th>
                <th class="col-cond col-hide-md">{{t('rules.thCondition')}}</th>
                <th class="col-hide-sm" style="width:72px">{{t('rules.thEnabled')}}</th>
                <th class="col-hide-md" style="width:56px;cursor:pointer" @click="$emit('toggle-rule-sort', 'priority')">{{t('rules.thPriority')}} <i class="bi" :class="ruleSortIcon('priority')"></i></th>
                <th class="col-datetime col-hide-md" style="cursor:pointer" @click="$emit('toggle-rule-sort', 'createdAt')">{{t('rules.thCreatedAt')}} <i class="bi" :class="ruleSortIcon('createdAt')"></i></th>
                <th class="col-actions col-actions-4">{{t('rules.thActions')}}</th>
            </tr></thead>
            <tbody>
                <template v-for="r in pagedRules" :key="r.id">
                <tr :class="[{'selected-row':batchSelectMode && selectedRules.includes(r.id), 'row-clickable':!batchSelectMode}, dragRowClass(r)]" @click="!batchSelectMode && $emit('handle-rule-row-click', r)" :title="!batchSelectMode ? t('rules.clickPreviewDblEdit') : ''" @dragover="(e) => $emit('drag-over', e, r)" @dragleave="(e) => $emit('drag-leave', e, r)" @drop="$emit('drop', $event)">
                    <td v-if="canDragRules" class="drag-handle-cell" draggable="true" :aria-label="t('rules.dragRule', {id:shortId(r.id)})" :title="t('rules.dragRule', {id:shortId(r.id)})" @dragstart="(e) => $emit('drag-start', e, r)" @dragend="$emit('drag-end')"><i class="bi bi-grip-vertical" aria-hidden="true"></i></td>
                    <td v-if="batchSelectMode"><input type="checkbox" :checked="selectedRules.includes(r.id)" @change="toggleSelection(r.id)" :aria-label="t('rules.selectRule', {id:shortId(r.id)})"></td>
                    <td class="col-id">
                        <span class="badge badge-id" :title="r.id">{{shortId(r.id)}}</span>
                        <i v-if="r.isProtected" class="bi bi-shield-fill-check text-success" :title="t('rules.isProtected')" style="margin-left:4px"></i>
                    </td>
                    <td class="col-type col-hide-sm"><span class="badge" :class="'badge-'+r.protocol.toLowerCase()">{{r.protocol==='HTTP'?httpLabel:jmsLabel}}</span></td>
                    <td class="col-endpoint">
                        <div class="rule-endpoint-main">
                            <span v-if="r.protocol==='HTTP'" class="badge badge-method">{{r.method}}</span>
                            <code :title="r.matchKey">{{r.matchKey}}</code>
                            <span v-if="r.sseEnabled" class="badge badge-sse" :title="t('rules.sseStream')">SSE</span>
                            <span v-if="r.action==='FORWARD'" class="badge badge-muted"><i class="bi bi-box-arrow-up-right"></i> {{t('rules.forward')}}</span>
                            <span v-if="r.faultType&&r.faultType!=='NONE'" class="badge badge-muted" :title="t('rules.fault_'+r.faultType)"><i class="bi bi-lightning" aria-hidden="true"></i> {{t('rules.faultInjection')}}</span>
                        </div>
                        <div v-if="r.description" class="sub-info rule-description" :title="r.description">{{r.description}}</div>
                        <div v-if="r.targetHost || (status?.scenariosEnabled && r.scenarioName) || r.tags" class="rule-endpoint-meta">
                            <span v-if="r.targetHost" class="rule-source-host" :title="t('rules.targetPrefix')+r.targetHost"><span>{{t('rules.targetPrefix')}}</span><code>{{r.targetHost}}</code></span>
                            <span v-if="status?.scenariosEnabled && r.scenarioName" class="rule-scenario-meta" :title="t('rules.scenarioTooltip',{name:r.scenarioName,required:r.requiredScenarioState||'Started',newState:r.newScenarioState||r.requiredScenarioState||'Started'})"><i class="bi bi-diagram-3" aria-hidden="true"></i><code>{{r.scenarioName}}</code></span>
                            <span v-for="(v,k) in parseTags(r.tags)" :key="k" class="badge badge-tag" :title="k+'='+v">{{k}}:{{v}}</span>
                        </div>
                    </td>
                    <td class="col-cond col-hide-md" :title="condTooltip(r)">
                        <div v-if="condTags(r).length" class="cond-list">
                            <span v-for="(c,i) in condTags(r)" :key="i" class="cond-tag" :class="c.t" :title="c.v"><span class="cond-label">{{c.label}}</span>{{c.v}}</span>
                        </div>
                        <span v-else class="sub-info">{{t('rules.noCondition')}}</span>
                    </td>
                    <td class="col-hide-sm">
                        <label class="toggle" @click.stop :class="{'disabled':!isLoggedIn}">
                            <input type="checkbox" :checked="r.enabled!==false" @click.prevent="$emit('toggle-enabled', r)" :disabled="!isLoggedIn" :aria-label="t('rules.thEnabled')">
                            <span class="toggle-slider"></span>
                        </label>
                    </td>
                    <td class="col-hide-md"><span class="badge badge-muted">{{r.priority ?? 0}}</span></td>
                    <td class="col-datetime col-hide-md">
                        <div class="table-date-stack">
                            <span class="sub-info" :title="fmtTime(r.createdAt,false)">{{fmtTime(r.createdAt)}}</span>
                            <span v-if="!r.isProtected && daysLeft(r.createdAt, r.extendedAt, status?.cleanupRetentionDays) != null" class="badge" :class="daysLeft(r.createdAt, r.extendedAt, status?.cleanupRetentionDays) <= 7 ? 'badge-warning' : 'badge-muted'">{{t('rules.daysLeft', {days: daysLeft(r.createdAt, r.extendedAt, status?.cleanupRetentionDays)})}}</span>
                        </div>
                    </td>
                    <td class="col-actions col-actions-4">
                        <div style="display:flex;gap:0.25rem">
                            <button class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('toggle-rule-preview', r)" :title="rulePreviewExpanded[r.id]?t('rules.collapsePreview'):t('rules.expandPreview')" :aria-label="rulePreviewExpanded[r.id]?t('rules.collapsePreview'):t('rules.expandPreview')"><i class="bi" :class="rulePreviewExpanded[r.id]?'bi-chevron-up':'bi-chevron-down'"></i></button>
                            <button class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('show-rule-history', r)" :title="t('rules.history')" :aria-label="t('rules.history')"><i class="bi bi-clock-history"></i></button>
                            <button class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('copy-rule', r)" :title="!isLoggedIn?t('rules.loginRequired'):t('rules.quickCopy')" :aria-label="t('rules.quickCopy')" :disabled="!isLoggedIn"><i class="bi bi-copy"></i></button>
                            <button class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('open-edit', r)" :title="!isLoggedIn?t('rules.loginRequired'):t('rules.edit')" :aria-label="t('rules.edit')" :disabled="!isLoggedIn"><i class="bi bi-pencil"></i></button>
                        </div>
                    </td>
                </tr>
                <tr v-if="rulePreviewExpanded[r.id]" class="rule-preview-row">
                    <td :colspan="(canDragRules ? 1 : 0) + (batchSelectMode ? 9 : 8)" class="rule-preview-cell">
                        <div v-if="rulePreviewLoading[r.id]" class="rule-preview-content rule-preview-state" role="status">
                            <i class="bi bi-arrow-clockwise spin" aria-hidden="true"></i><span>{{t('rules.loading')}}</span>
                        </div>
                        <div v-else-if="rulePreviewCache[r.id]" class="rule-preview-content">
                            <div class="pv-header">
                                <button type="button" class="pv-id" @click="$emit('clip-copy', rulePreviewCache[r.id].id)" :title="t('rules.copyId')"><i class="bi bi-fingerprint" aria-hidden="true"></i><span>{{rulePreviewCache[r.id].id}}</span><i class="bi bi-clipboard pv-copy-icon" aria-hidden="true"></i></button>
                                <span v-if="rulePreviewCache[r.id].description" class="pv-desc">— {{rulePreviewCache[r.id].description}}</span>
                                <div class="pv-header-actions">
                                    <button class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('open-edit', rulePreviewCache[r.id])" :disabled="!isLoggedIn" :title="t('rules.edit')" :aria-label="t('rules.edit')"><i class="bi bi-pencil" aria-hidden="true"></i></button>
                                    <button class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('export-rule-json', rulePreviewCache[r.id].id)" :title="t('rules.exportJson')" :aria-label="t('rules.exportJson')"><i class="bi bi-download" aria-hidden="true"></i></button>
                                    <button v-if="rulePreviewCache[r.id].responseId && isLoggedIn" class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('go-to-responses', rulePreviewCache[r.id].responseId)" :title="t('rules.viewResponse')" :aria-label="t('rules.viewResponse')"><i class="bi bi-file-earmark-text" aria-hidden="true"></i></button>
                                    <button class="btn btn-sm btn-icon btn-danger" @click.stop="$emit('delete-rule', rulePreviewCache[r.id].id)" :disabled="!isLoggedIn" :title="t('rules.delete')" :aria-label="t('rules.delete')"><i class="bi bi-trash" aria-hidden="true"></i></button>
                                </div>
                            </div>
                            <div class="pv-main">
                                <div class="pv-fields">
                                    <div class="pv-section-title">{{t('rules.pvSectionSettings')}}</div>
                                    <div class="pv-field" v-if="rulePreviewCache[r.id].protocol==='HTTP'&&rulePreviewCache[r.id].action!=='FORWARD'&&rulePreviewCache[r.id].faultType!=='CONNECTION_RESET'"><span class="pv-label">{{t('rules.pvStatusCode')}}</span><span class="badge" :class="rulePreviewCache[r.id].status<400?'badge-success':rulePreviewCache[r.id].status<500?'badge-warning':'badge-danger'">{{rulePreviewCache[r.id].status}}</span></div>
                                    <div class="pv-field" v-if="rulePreviewCache[r.id].action==='FORWARD'"><span class="pv-label">{{t('rules.forward')}}</span><span>{{rulePreviewCache[r.id].forwardTargetMode==='CONNECTION' ? '#'+rulePreviewCache[r.id].httpTargetConnectionId : rulePreviewCache[r.id].forwardTargetMode}}</span></div>
                                    <div class="pv-field" v-if="rulePreviewCache[r.id].faultType&&rulePreviewCache[r.id].faultType!=='NONE'"><span class="pv-label">{{t('rules.faultInjection')}}</span><span class="pv-result-value"><i class="bi bi-lightning" aria-hidden="true"></i>{{t('rules.fault_'+rulePreviewCache[r.id].faultType)}}</span></div>
                                    <div class="pv-field" v-if="status?.scenariosEnabled && rulePreviewCache[r.id].scenarioName"><span class="pv-label">{{t('rules.pvScenario')}}</span><span class="pv-scenario-value" :title="t('rules.scenarioTooltip',{name:rulePreviewCache[r.id].scenarioName,required:rulePreviewCache[r.id].requiredScenarioState||'Started',newState:rulePreviewCache[r.id].newScenarioState||rulePreviewCache[r.id].requiredScenarioState||'Started'})"><strong>{{rulePreviewCache[r.id].scenarioName}}</strong><code>{{rulePreviewCache[r.id].requiredScenarioState||'Started'}}</code><i class="bi bi-arrow-right" aria-hidden="true"></i><code>{{rulePreviewCache[r.id].newScenarioState||rulePreviewCache[r.id].requiredScenarioState||'Started'}}</code></span></div>
                                    <div class="pv-field"><span class="pv-label">{{t('rules.pvDelay')}}</span><span>{{rulePreviewCache[r.id].delayMs||0}} ms</span></div>
                                    <div class="pv-field"><span class="pv-label">{{t('rules.pvPriority')}}</span><span>{{rulePreviewCache[r.id].priority||0}}</span></div>
                                    <div class="pv-field"><span class="pv-label">{{t('rules.pvProtected')}}</span><span><i class="bi" :class="rulePreviewCache[r.id].isProtected ? 'bi-shield-fill-check text-success' : 'bi-shield'" style="margin-right:2px"></i> {{rulePreviewCache[r.id].isProtected ? t('rules.pvYes') : t('rules.pvNo')}}</span></div>
                                    <div class="pv-field" v-if="!rulePreviewCache[r.id].isProtected && daysLeft(rulePreviewCache[r.id].createdAt, rulePreviewCache[r.id].extendedAt, status?.cleanupRetentionDays) != null"><span class="pv-label">{{t('rules.pvDaysLeft')}}</span><span><span class="badge" :class="daysLeft(rulePreviewCache[r.id].createdAt, rulePreviewCache[r.id].extendedAt, status?.cleanupRetentionDays) <= 7 ? 'badge-warning' : 'badge-muted'">{{t('rules.daysLeft', {days: daysLeft(rulePreviewCache[r.id].createdAt, rulePreviewCache[r.id].extendedAt, status?.cleanupRetentionDays)})}}</span> <button v-if="isLoggedIn" class="btn btn-sm btn-secondary" style="margin-left:0.5rem;padding:0.1rem 0.4rem;font-size:0.75rem" @click.stop="$emit('extend-rule', rulePreviewCache[r.id].id)"><i class="bi bi-calendar-plus"></i> {{t('rules.extend')}}</button></span></div>
                                    <div class="pv-field" v-if="rulePreviewCache[r.id].createdAt"><span class="pv-label">{{t('rules.pvCreated')}}</span><span>{{fmtTime(rulePreviewCache[r.id].createdAt, false)}}</span></div>
                                    <div class="pv-field" v-if="rulePreviewCache[r.id].updatedAt"><span class="pv-label">{{t('rules.pvUpdated')}}</span><span>{{fmtTime(rulePreviewCache[r.id].updatedAt, false)}}</span></div>
                                    <div class="pv-section-title pv-section-conditions">{{t('rules.pvSectionConditions')}}</div>
                                    <div class="pv-field pv-field-cond" v-if="rulePreviewCache[r.id].bodyCondition"><span class="pv-label">{{t('rules.pvBodyCondition')}}</span><div class="pv-cond-list"><code v-for="(c,i) in rulePreviewCache[r.id].bodyCondition.split(';').filter(x=>x)" :key="'b'+i" class="pv-cond-item body" @click="$emit('clip-copy', c.trim())" :title="t('rules.clickToCopy')">{{c.trim()}}</code></div></div>
                                    <div class="pv-field pv-field-cond" v-if="rulePreviewCache[r.id].queryCondition"><span class="pv-label">{{t('rules.pvQueryCondition')}}</span><div class="pv-cond-list"><code v-for="(c,i) in rulePreviewCache[r.id].queryCondition.split(';').filter(x=>x)" :key="'q'+i" class="pv-cond-item query" @click="$emit('clip-copy', c.trim())" :title="t('rules.clickToCopy')">{{c.trim()}}</code></div></div>
                                    <div class="pv-field pv-field-cond" v-if="rulePreviewCache[r.id].headerCondition"><span class="pv-label">{{t('rules.pvHeaderCondition')}}</span><div class="pv-cond-list"><code v-for="(c,i) in rulePreviewCache[r.id].headerCondition.split(';').filter(x=>x)" :key="'h'+i" class="pv-cond-item header" @click="$emit('clip-copy', c.trim())" :title="t('rules.clickToCopy')">{{c.trim()}}</code></div></div>
                                    <div class="pv-field" v-if="rulePreviewCache[r.id].responseHeaders"><span class="pv-label">{{t('rules.pvResponseHeaders')}}</span><code class="pv-code-copy" @click="$emit('clip-copy', rulePreviewCache[r.id].responseHeaders)" :title="t('rules.clickToCopy')">{{rulePreviewCache[r.id].responseHeaders}}</code></div>
                                    <div class="pv-field" v-if="rulePreviewCache[r.id].tags"><span class="pv-label">{{t('rules.pvTags')}}</span><span><span class="tag-badge" v-for="(v,k) in parseTags(rulePreviewCache[r.id].tags)" :key="k">{{k}}:{{v}}</span></span></div>
                                    <div class="pv-field" v-if="!rulePreviewCache[r.id].bodyCondition && !rulePreviewCache[r.id].queryCondition && !rulePreviewCache[r.id].headerCondition"><span class="pv-label"></span><span class="sub-info">{{t('rules.noCondition')}}</span></div>
                                </div>
                                <div class="pv-body">
                                    <div class="pv-body-header">
                                        <span class="pv-label">{{rulePreviewCache[r.id].faultType&&rulePreviewCache[r.id].faultType!=='NONE'?t('rules.faultInjection'):t('rules.pvResponseContent')}} <span v-if="rulePreviewCache[r.id]._isSse" class="badge badge-sse pv-sse-badge">SSE</span></span>
                                        <div v-if="!rulePreviewCache[r.id].faultType||rulePreviewCache[r.id].faultType==='NONE'" class="pv-body-tools">
                                            <div v-if="rulePreviewCache[r.id]._previewBody" class="pv-search-bar">
                                                <input :value="pvSearch[r.id]||''" @input="setPvSearch(r.id, $event.target.value)" :placeholder="t('rules.pvSearchBody')" :aria-label="t('rules.pvSearchBody')" @keydown.enter.prevent="pvNavSearch(r.id, rulePreviewCache[r.id]._previewBody, 1)" @keydown.shift.enter.prevent="pvNavSearch(r.id, rulePreviewCache[r.id]._previewBody, -1)">
                                                <span v-if="pvSearch[r.id]" class="pv-search-count">{{pvMatchCount(r.id, rulePreviewCache[r.id]._previewBody)?(pvSearchIdx[r.id]||0)+1:0}}/{{pvMatchCount(r.id, rulePreviewCache[r.id]._previewBody)}}</span>
                                                <button v-if="pvSearch[r.id]" type="button" @click="pvNavSearch(r.id, rulePreviewCache[r.id]._previewBody, -1)" :title="t('rules.pvSearchPrev')" :aria-label="t('rules.pvSearchPrev')"><i class="bi bi-chevron-up" aria-hidden="true"></i></button>
                                                <button v-if="pvSearch[r.id]" type="button" @click="pvNavSearch(r.id, rulePreviewCache[r.id]._previewBody, 1)" :title="t('rules.pvSearchNext')" :aria-label="t('rules.pvSearchNext')"><i class="bi bi-chevron-down" aria-hidden="true"></i></button>
                                            </div>
                                            <button v-if="rulePreviewCache[r.id]._previewBody" class="btn btn-sm btn-icon btn-secondary" @click="$emit('clip-copy', rulePreviewCache[r.id].responseBody||rulePreviewCache[r.id]._previewBody)" :title="t('rules.copyFullContent')" :aria-label="t('rules.copyFullContent')"><i class="bi bi-clipboard"></i></button>
                                        </div>
                                    </div>
                                    <div v-if="rulePreviewCache[r.id].faultType&&rulePreviewCache[r.id].faultType!=='NONE'" class="pv-fault-preview"><i class="bi bi-lightning" aria-hidden="true"></i><strong>{{t('rules.fault_'+rulePreviewCache[r.id].faultType)}}</strong><span>{{rulePreviewCache[r.id].faultType==='EMPTY_RESPONSE' ? t('modal.faultEmptyResponse'+(rulePreviewCache[r.id].protocol==='JMS'?'Jms':'Http')+'Hint') : t('modal.faultConnectionReset'+(rulePreviewCache[r.id].protocol==='JMS'?'Jms':'Http')+'Hint')}}</span></div>
                                    <pre v-else :ref="'pvPre_'+r.id" class="pv-pre" :class="{'expanded': pvExpanded[r.id]}"><template v-if="pvSearch[r.id] && rulePreviewCache[r.id]._previewBody"><template v-for="(seg,si) in pvHighlight(r.id, rulePreviewCache[r.id]._previewBody)" :key="si"><span v-if="seg.hl" class="pv-highlight" :class="{'pv-highlight-current': pvHlIsCurrent(r.id, si)}">{{seg.text}}</span><template v-else>{{seg.text}}</template></template></template><template v-else>{{rulePreviewCache[r.id]._previewBody || t('rules.empty')}}</template></pre>
                                    <button v-if="(!rulePreviewCache[r.id].faultType||rulePreviewCache[r.id].faultType==='NONE')&&rulePreviewCache[r.id]._previewBody && (pvExpanded[r.id] || pvOverflow[r.id])" class="pv-expand-btn" @click="togglePvExpand(r.id)">
                                        <i class="bi" :class="pvExpanded[r.id]?'bi-chevron-compact-up':'bi-chevron-compact-down'" aria-hidden="true"></i>
                                        {{pvExpanded[r.id] ? t('rules.pvCollapse') : t('rules.pvExpand')}}
                                    </button>
                                </div>
                            </div>
                        </div>
                        <div v-else-if="rulePreviewError[r.id]" class="rule-preview-content rule-preview-state rule-preview-error" role="alert">
                            <i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>{{t('rules.previewLoadFailed')}}</span>
                            <button type="button" class="btn btn-sm btn-secondary" @click.stop="$emit('toggle-rule-preview', r)"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</button>
                        </div>
                    </td>
                </tr>
                </template>
            </tbody>
        </table>
        <div v-if="!pagedRules.length && !loading.rules" class="empty">
            <i class="bi bi-inbox"></i>
            <div v-if="ruleFilter.keyword || ruleFilter.protocol || ruleFilter.enabled || ruleFilter.isProtected">
                {{t('rules.emptyFilterResult')}}
                <div style="margin-top:0.5rem">
                    <button class="btn btn-sm btn-secondary" @click="clearFilters()">{{t('rules.clearFilter')}}</button>
                </div>
            </div>
            <div v-else>
                {{t('rules.emptyNoRules')}}
            </div>
        </div>
        </div>
        <workspace-pagination
            :page="rulePage" :total-pages="ruleTotalPages" :page-size="rulePageSize"
            :pagination-label="t('stats.pagination')"
            :page-status-label="t('stats.pageStatus', {page:rulePage, total:ruleTotalPages})"
            :page-size-label="t('stats.pageSize')"
            :first-page-label="t('stats.firstPage')" :previous-page-label="t('stats.previousPage')"
            :next-page-label="t('stats.nextPage')" :last-page-label="t('stats.lastPage')"
            @update:page="$emit('update:rulePage', $event)"
            @update:page-size="$emit('update:rulePageSize', $event); $emit('update:rulePage', 1)"
        >
            <template #summary>
                <span class="sub-info">{{t('rules.totalCount', {count: ruleTotalElements})}}</span>
                <button v-if="ruleFilter.protocol||ruleFilter.enabled||ruleFilter.isProtected||ruleFilter.keyword" type="button" class="workspace-filter-reset" :title="t('rules.clickClearFilter')" @click="clearFilters()"><i class="bi bi-funnel-fill" aria-hidden="true"></i> {{t('rules.filtering')}}</button>
            </template>
        </workspace-pagination>
        </template>
        <!-- 分組檢視 -->
        <template v-else>
        <div class="card-body page-scroll" style="padding:0">
            <div class="tag-group-header" @click="$emit('toggle-tag-group', '_untagged')">
                <i class="bi" :class="expandedTagGroups.includes('_untagged')?'bi-chevron-down':'bi-chevron-right'"></i>
                <span>{{t('rules.untagged')}}</span>
                <span class="badge badge-muted">{{groupCounts['_untagged'] || 0}}</span>
            </div>
            <div v-if="expandedTagGroups.includes('_untagged')" class="tag-group-content">
                <div v-if="groupLoading['_untagged']" class="sub-info" style="padding:0.75rem 1rem"><i class="bi bi-arrow-clockwise spin"></i> {{t('rules.loading')}}</div>
                <table v-if="rulesByTagGroup['_untagged']?.length" class="tag-group-table">
                    <thead><tr>
                        <th class="col-id">{{t('rules.thId')}}</th>
                        <th class="col-type col-hide-sm">{{t('rules.thProtocol')}}</th>
                        <th class="col-endpoint">{{t('rules.thEndpoint')}}</th>
                        <th class="col-cond col-hide-md">{{t('rules.thCondition')}}</th>
                        <th class="col-hide-sm" style="width:72px">{{t('rules.thEnabled')}}</th>
                        <th class="col-hide-md" style="width:56px">{{t('rules.thPriority')}}</th>
                        <th class="col-datetime col-hide-md">{{t('rules.thCreatedAt')}}</th>
                        <th class="col-actions col-actions-4">{{t('rules.thActions')}}</th>
                    </tr></thead>
                    <tbody>
                        <rule-group-row v-for="r in rulesByTagGroup['_untagged'].slice(0, getGroupLimit('_untagged'))" :key="r.id"
                            :rule="r" :is-logged-in="isLoggedIn" :http-label="httpLabel" :jms-label="jmsLabel" :status="status"
                            :rule-preview-expanded="rulePreviewExpanded" :rule-preview-loading="rulePreviewLoading" :rule-preview-error="rulePreviewError" :rule-preview-cache="rulePreviewCache"
                            @open-edit="$emit('open-edit', $event)" @copy-rule="$emit('copy-rule', $event)"
                            @delete-rule="$emit('delete-rule', $event)" @toggle-enabled="$emit('toggle-enabled', $event)"
                            @go-to-responses="$emit('go-to-responses', $event)" @extend-rule="$emit('extend-rule', $event)"
                            @toggle-rule-preview="$emit('toggle-rule-preview', $event)" @handle-rule-row-click="$emit('handle-rule-row-click', $event)"
                            @clip-copy="$emit('clip-copy', $event)" @export-rule-json="$emit('export-rule-json', $event)"
                        ></rule-group-row>
                    </tbody>
                </table>
                <div v-if="(groupCounts['_untagged'] || 0) > (rulesByTagGroup['_untagged']?.length || 0)" class="tag-group-more">
                    <button class="btn btn-sm btn-secondary" @click="$emit('show-more-group', '_untagged')">{{t('rules.showMore')}} ({{rulesByTagGroup['_untagged']?.length || 0}}/{{groupCounts['_untagged'] || 0}})</button>
                    <button class="btn btn-sm btn-secondary" @click="$emit('show-all-group', '_untagged', groupCounts['_untagged'] || 0)">{{t('rules.showAll')}}</button>
                </div>
                <div v-if="!groupLoading['_untagged'] && !(groupCounts['_untagged'] || 0)" class="sub-info" style="padding:0.5rem 1rem">{{t('rules.noRules')}}</div>
            </div>
            <template v-for="(values, key) in tagKeys" :key="key">
                <div class="tag-group-header" @click="$emit('toggle-tag-group', key)">
                    <i class="bi" :class="expandedTagGroups.includes(key)?'bi-chevron-down':'bi-chevron-right'"></i>
                    <span style="font-weight:500">{{key}}</span>
                    <span class="badge badge-muted">{{values.reduce((sum, val) => sum + (groupCounts[key+'='+val] || 0), 0)}}</span>
                </div>
                <template v-if="expandedTagGroups.includes(key)">
                    <div v-for="val in values" :key="val" class="tag-subgroup">
                        <div class="tag-subgroup-header" @click="$emit('toggle-tag-subgroup', key+'='+val)">
                            <i class="bi" :class="expandedTagSubgroups.includes(key+'='+val)?'bi-chevron-down':'bi-chevron-right'" style="font-size:0.7rem;color:var(--muted)"></i>
                            <span class="badge badge-tag">{{val}}</span>
                            <span class="sub-info">{{t('common.count', {count: groupCounts[key+'='+val] || 0})}}</span>
                        </div>
                        <template v-if="expandedTagSubgroups.includes(key+'='+val)">
                        <div v-if="groupLoading[key+'='+val]" class="sub-info" style="padding:0.75rem 1rem"><i class="bi bi-arrow-clockwise spin"></i> {{t('rules.loading')}}</div>
                        <table v-if="rulesByTag[key+'='+val]?.length" class="tag-group-table">
                            <thead><tr>
                                <th class="col-id">{{t('rules.thId')}}</th>
                                <th class="col-type col-hide-sm">{{t('rules.thProtocol')}}</th>
                                <th class="col-endpoint">{{t('rules.thEndpoint')}}</th>
                                <th class="col-cond col-hide-md">{{t('rules.thCondition')}}</th>
                                <th class="col-hide-sm" style="width:72px">{{t('rules.thEnabled')}}</th>
                                <th class="col-hide-md" style="width:56px">{{t('rules.thPriority')}}</th>
                                <th class="col-datetime col-hide-md">{{t('rules.thCreatedAt')}}</th>
                                <th class="col-actions col-actions-4">{{t('rules.thActions')}}</th>
                            </tr></thead>
                            <tbody>
                                <rule-group-row v-for="r in rulesByTag[key+'='+val].slice(0, getGroupLimit(key+'='+val))" :key="r.id"
                                    :rule="r" :is-logged-in="isLoggedIn" :http-label="httpLabel" :jms-label="jmsLabel" :status="status"
                                    :rule-preview-expanded="rulePreviewExpanded" :rule-preview-loading="rulePreviewLoading" :rule-preview-error="rulePreviewError" :rule-preview-cache="rulePreviewCache"
                                    @open-edit="$emit('open-edit', $event)" @copy-rule="$emit('copy-rule', $event)"
                                    @delete-rule="$emit('delete-rule', $event)" @toggle-enabled="$emit('toggle-enabled', $event)"
                                    @go-to-responses="$emit('go-to-responses', $event)" @extend-rule="$emit('extend-rule', $event)"
                                    @toggle-rule-preview="$emit('toggle-rule-preview', $event)" @handle-rule-row-click="$emit('handle-rule-row-click', $event)"
                                    @clip-copy="$emit('clip-copy', $event)" @export-rule-json="$emit('export-rule-json', $event)"
                                ></rule-group-row>
                            </tbody>
                        </table>
                        <div v-if="(groupCounts[key+'='+val] || 0) > (rulesByTag[key+'='+val]?.length || 0)" class="tag-group-more">
                            <button class="btn btn-sm btn-secondary" @click="$emit('show-more-group', key+'='+val)">{{t('rules.showMore')}} ({{rulesByTag[key+'='+val]?.length || 0}}/{{groupCounts[key+'='+val] || 0}})</button>
                            <button class="btn btn-sm btn-secondary" @click="$emit('show-all-group', key+'='+val, groupCounts[key+'='+val] || 0)">{{t('rules.showAll')}}</button>
                        </div>
                        </template>
                    </div>
                </template>
            </template>
        </div>
        </template>
    </div>
</div>
`
};
