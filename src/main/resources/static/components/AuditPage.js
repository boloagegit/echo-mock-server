/**
 * AuditPage - 修訂記錄頁面
 *
 * 顯示規則與回應的修訂記錄，支援篩選、排序、展開差異比對。
 */
const AuditPage = {
  props: {
    auditLogs: Array,
    loading: Object,
    selectedAudit: [String, Number, null],
    auditFilter: Object,
    auditSort: Object,
    auditPage: Number,
    auditPageSize: Number,
    auditTotalElements: Number,
    pagedAudit: Array,
    auditTotalPages: Number,
    auditTruncated: Boolean,
    auditFilterChips: Array,
    isAdmin: Boolean,
    status: Object,
    getAuditChanges: Function,
    getAuditChangeCount: Function,
    getAuditTarget: Function,
    getAuditDescription: Function,
    getAuditProtocol: Function
  },
  emits: [
    'load-audit', 'update:selectedAudit', 'update:auditFilter',
    'update:auditPage', 'update:auditPageSize',
    'toggle-audit-sort', 'delete-all-audit',
    'remove-audit-chip', 'clear-audit-filters',
    'go-to-rule', 'go-to-response', 'toggle-audit-detail', 'clip-copy'
  ],
  inject: ['t'],
  mixins: [heldDetailMixin(function () {
    const log = this.selectedLog;
    return { open: !!log, key: log?.id, ready: !log?._detailLoading, value: log };
  })],
  computed: {
    shownLog() {
      return this.held.value;
    },
    hasAuditFilters() {
      return Boolean(this.auditFilter.action || this.auditFilter.operator || this.auditFilter.keyword);
    },
    selectedIndex() {
      return this.pagedAudit.findIndex(log => log.id === this.selectedAudit);
    },
    selectedLog() {
      return this.selectedIndex >= 0 ? this.pagedAudit[this.selectedIndex] : null;
    },
    actionFilterOptions() {
      return [
        { value: 'CREATE', label: this.t('audit.actionCreate') },
        { value: 'UPDATE', label: this.t('audit.actionUpdate') },
        { value: 'DELETE', label: this.t('audit.actionDelete') },
      ];
    },
  },
  methods: {
    shortId, fmtTime,
    auditActionLabel(action) {
      return ({
        CREATE: this.t('audit.actionCreate'),
        UPDATE: this.t('audit.actionUpdate'),
        DELETE: this.t('audit.actionDelete'),
      })[action] || action;
    },
    sortState(field) {
      if (this.auditSort.field !== field) return 'none';
      return this.auditSort.asc ? 'ascending' : 'descending';
    },
    isResponse(log) {
      return !!log.ruleId?.startsWith('response-');
    },
    targetType(log) {
      return this.isResponse(log) ? this.t('audit.typeResponse') : this.t('audit.typeRule');
    },
    targetId(log) {
      return this.isResponse(log) ? '#' + log.ruleId.replace('response-', '') : shortId(log.ruleId);
    },
    targetName(log) {
      return (log.beforeJson || log.afterJson) ? this.getAuditTarget(log) : this.targetType(log);
    },
    openTarget(log) {
      if (this.isResponse(log)) this.$emit('go-to-response', log.ruleId);
      else this.$emit('go-to-rule', log.ruleId);
    },
    auditMenuItems(log) {
      const items = [];
      if (log.action !== 'DELETE') {
        items.push({ key: 'open', label: this.isResponse(log) ? this.t('audit.openResponse') : this.t('audit.openRule'), icon: 'bi-box-arrow-up-right' });
      }
      items.push({ key: 'copy-id', label: this.t('rules.copyId'), icon: 'bi-copy' });
      return items;
    },
    handleAuditMenu(action, log) {
      if (action === 'open') this.openTarget(log);
      else if (action === 'copy-id') this.$emit('clip-copy', this.isResponse(log) ? log.ruleId.replace('response-', '') : log.ruleId);
    },
    onRowKeydown(event, log) {
      if (event.target !== event.currentTarget) return;
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); this.$emit('toggle-audit-detail', log); }
    },
    stepDetail(direction) {
      const next = this.pagedAudit[this.selectedIndex + direction];
      if (next) {
        this.$emit('toggle-audit-detail', next);
        this.$nextTick(() => document.querySelector('.audit-workspace [data-detail-row].is-selected')?.scrollIntoView({ block: 'nearest' }));
      }
    },
  },
  template: /* html */`
    <div class="page workspace-page audit-workspace" :class="{active:true}">
      <div class="page-header">
        <div class="page-heading">
          <h1 class="page-title">{{t('audit.title')}}</h1>
          <span class="page-count">{{auditTotalElements}}</span>
          <button v-if="auditTruncated" type="button" class="help-tooltip tooltip-align-start is-warning"
            :data-tooltip="t('audit.truncatedWarning', {days: status?.auditRetentionDays || 30, count: auditLogs.length})"
            :aria-label="t('audit.truncatedWarning', {days: status?.auditRetentionDays || 30, count: auditLogs.length})" @keydown.esc="$event.currentTarget.blur()">
            <i class="bi bi-exclamation-triangle" aria-hidden="true"></i>
          </button>
        </div>
        <div class="page-actions"><ui-button variant="secondary" @click="$emit('load-audit', true)" :disabled="loading.audit"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.audit}" aria-hidden="true"></i>{{t('audit.refresh')}}</ui-button></div>
      </div>

      <div class="list-toolbar">
        <workspace-search-field shortcut="/"
          input-id="auditSearch"
          :model-value="auditFilter.keyword"
          :placeholder="t('audit.searchContent')"
          :aria-label="t('audit.searchContent')"
          :clear-label="t('audit.clearAll')"
          :submit-mode="true"
          @search="$emit('update:auditFilter', {...auditFilter, keyword:$event})"
        ></workspace-search-field>
        <workspace-search-field
          input-id="auditOperatorSearch"
          :model-value="auditFilter.operator"
          :placeholder="t('audit.searchOperator')"
          :aria-label="t('audit.searchOperator')"
          :clear-label="t('audit.clearAll')"
          icon="bi-person" compact
          :submit-mode="true"
          @search="$emit('update:auditFilter', {...auditFilter, operator:$event})"
        ></workspace-search-field>
        <ui-toggle-group :model-value="auditFilter.action" :options="actionFilterOptions" :aria-label="t('audit.actionFilter')"
          @update:model-value="$emit('update:auditFilter', {...auditFilter, action:$event})"></ui-toggle-group>
      </div>
      <ui-filter-chip-list :items="auditFilterChips" :aria-label="t('common.activeFilters')"
        :clear-label="t('audit.clearAll')"
        @remove="$emit('remove-audit-chip', $event)"
        @clear="$emit('clear-audit-filters')"></ui-filter-chip-list>

      <div class="card card-table list-card">
        <ui-load-state v-if="loading.auditError && !loading.audit" kind="error" icon="bi-cloud-slash" :title="t('audit.loadFailed')" has-action>
          <template #action><ui-button variant="secondary" size="compact" @click="$emit('load-audit', true)"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</ui-button></template>
        </ui-load-state>
        <div v-else class="card-table-body">
          <div v-if="loading.audit && !auditLogs.length" class="list-skeleton" role="status" :aria-label="t('common.loading')">
            <div v-for="i in 8" :key="'sk-audit-'+i" class="list-skeleton__row"><span class="sk sk-w-15p"></span><span class="sk sk-w-38"></span><span class="sk sk-w-40p"></span></div>
          </div>
          <table v-else-if="pagedAudit.length" class="data-table audit-table">
            <thead><tr>
              <th class="col-time" :aria-sort="sortState('timestamp')"><ui-table-sort-header :label="t('audit.thTime')" :active="auditSort.field==='timestamp'" :ascending="auditSort.asc" @toggle="$emit('toggle-audit-sort','timestamp')"></ui-table-sort-header></th>
              <th class="col-action" :aria-sort="sortState('action')"><ui-table-sort-header :label="t('audit.thAction')" :active="auditSort.field==='action'" :ascending="auditSort.asc" @toggle="$emit('toggle-audit-sort','action')"></ui-table-sort-header></th>
              <th class="col-target">{{t('audit.thTarget')}}</th>
              <th class="col-operator" :aria-sort="sortState('operator')"><ui-table-sort-header :label="t('audit.thOperator')" :active="auditSort.field==='operator'" :ascending="auditSort.asc" @toggle="$emit('toggle-audit-sort','operator')"></ui-table-sort-header></th>
              <th class="col-actions"><span class="visually-hidden">{{t('audit.thActions')}}</span></th>
            </tr></thead>
            <tbody>
              <tr v-for="log in pagedAudit" :key="log.id" data-detail-row tabindex="0"
                :class="{'is-selected': selectedAudit===log.id}" :aria-selected="selectedAudit===log.id ? 'true' : 'false'"
                @click="$emit('toggle-audit-detail', log)" @keydown="onRowKeydown($event, log)">
                <td class="col-time cell-mono cell-subtle" :title="fmtTime(log.timestamp,false)">{{fmtTime(log.timestamp)}}</td>
                <td class="col-action">
                  <span class="audit-action">
                    <ui-badge class="badge" :class="'badge-'+log.action?.toLowerCase()">{{auditActionLabel(log.action)}}</ui-badge>
                    <span v-if="getAuditChangeCount(log)" class="cell-subtle">{{getAuditChangeCount(log)}}</span>
                  </span>
                </td>
                <td class="col-target">
                  <span class="record-name">
                    <span class="record-name__title">{{targetName(log)}}</span>
                    <span class="record-name__id">{{targetType(log)}} · {{targetId(log)}}</span>
                    <span v-if="getAuditDescription(log)" class="rule-desc">{{getAuditDescription(log)}}</span>
                  </span>
                </td>
                <td class="col-operator cell-subtle">{{log.operator}}</td>
                <td class="col-actions" @click.stop @dblclick.stop>
                  <span class="row-actions"><ui-row-menu :items="auditMenuItems(log)" :label="t('common.moreActions') + ' ' + targetName(log)" @select="handleAuditMenu($event, log)"></ui-row-menu></span>
                </td>
              </tr>
            </tbody>
          </table>
          <ui-load-state v-else kind="empty" :has-action="hasAuditFilters" :icon="hasAuditFilters ? 'bi-search' : 'bi-journal-text'"
            :title="hasAuditFilters ? t('audit.emptyFilterResult') : t('audit.emptyNoAudit')" :hint="hasAuditFilters ? '' : t('audit.emptyHint')">
            <template #action><ui-button variant="secondary" size="compact" @click="$emit('clear-audit-filters')">{{t('audit.clearAll')}}</ui-button></template>
          </ui-load-state>
        </div>
        <workspace-pagination v-if="!loading.auditError"
          :page="auditPage" :total-pages="auditTotalPages" :page-size="auditPageSize"
          :pagination-label="t('audit.pagination')"
          :page-status-label="t('stats.pageStatus', {page:auditPage, total:auditTotalPages})"
          :page-size-label="t('stats.pageSize')"
          :first-page-label="t('stats.firstPage')" :previous-page-label="t('stats.previousPage')"
          :next-page-label="t('stats.nextPage')" :last-page-label="t('stats.lastPage')"
          :scroll-hint-label="t('common.scrollForMore')"
          :scroll-region-label="t('audit.title')"
          @update:page="$emit('update:auditPage', $event)"
          @update:page-size="$emit('update:auditPageSize', $event)"
        >
          <template #summary>
            <span class="sub-info">{{t('audit.totalCount', {count: auditTotalElements})}}</span>
          </template>
        </workspace-pagination>
      </div>

      <ui-detail-drawer class="audit-detail-drawer" :open="held.open" :title="shownLog ? targetName(shownLog) : ''"
        :subtitle="shownLog ? targetType(shownLog) + ' · ' + targetId(shownLog) : ''"
        :loading="held.waiting" :stale="held.stale" :has-prev="selectedIndex > 0" :has-next="selectedIndex >= 0 && selectedIndex < pagedAudit.length - 1"
        @close="selectedLog && $emit('toggle-audit-detail', selectedLog)" @prev="stepDetail(-1)" @next="stepDetail(1)">
        <template v-if="shownLog" #meta>
          <ui-badge class="badge" :class="'badge-'+shownLog.action?.toLowerCase()">{{auditActionLabel(shownLog.action)}}</ui-badge>
          <span class="detail-mono">{{fmtTime(shownLog.timestamp, false)}}</span>
          <span class="cell-subtle">{{shownLog.operator}}</span>
        </template>
        <template v-if="shownLog && shownLog.action!=='DELETE'" #actions>
          <ui-button variant="secondary" size="compact" @click="openTarget(shownLog)"><i class="bi bi-box-arrow-up-right" aria-hidden="true"></i>{{isResponse(shownLog) ? t('audit.openResponse') : t('audit.openRule')}}</ui-button>
        </template>
        <ui-detail-section v-if="shownLog" id="audit.changes" class="audit-changes" :title="t('audit.changesTitle')" :summary="getAuditChangeCount(shownLog) ? String(getAuditChangeCount(shownLog)) : ''">
                  <template v-for="detail in [getAuditChanges(shownLog)]" :key="shownLog.id">
                  <template v-if="detail.type==='update'">
                    <div v-if="detail.changes.length" class="ac-list">
                      <template v-for="c in detail.changes" :key="c.label">
                        <div v-if="!c.long" class="ac-row">
                          <span class="ac-label">{{c.label}}</span>
                          <span class="ac-val ac-before" :class="{'ac-empty':c.before==='(空)'}">{{c.before}}</span>
                          <i class="bi bi-arrow-right ac-arrow"></i>
                          <span class="ac-val ac-after" :class="{'ac-empty':c.after==='(空)'}">{{c.after}}</span>
                        </div>
                        <div v-else class="ac-block">
                          <div class="ac-block-label">{{c.label}}</div>
                          <div class="ac-block-diff">
                            <div class="ac-block-panel ac-block-before">
                              <div class="ac-block-title">{{t('audit.beforeChange')}}</div>
                              <ui-code-viewer :value="String(c.before ?? '')" :label="c.label + ' · ' + t('audit.beforeChange')" :max-height="260" @copy="$emit('clip-copy', $event)"></ui-code-viewer>
                            </div>
                            <div class="ac-block-panel ac-block-after">
                              <div class="ac-block-title">{{t('audit.afterChange')}}</div>
                              <ui-code-viewer :value="String(c.after ?? '')" :label="c.label + ' · ' + t('audit.afterChange')" :max-height="260" @copy="$emit('clip-copy', $event)"></ui-code-viewer>
                            </div>
                          </div>
                        </div>
                      </template>
                    </div>
                    <div v-else class="audit-no-change">{{t('audit.noSubstantialChange')}}</div>
                  </template>
                  <template v-else-if="detail.type==='error'">
                    <ui-code-viewer :value="String(detail.raw ?? '')" :label="t('audit.changesTitle')" @copy="$emit('clip-copy', $event)"></ui-code-viewer>
                  </template>
                  <template v-else-if="detail.changes?.length">
                    <div class="ac-list">
                      <template v-for="c in detail.changes" :key="c.label">
                        <div v-if="!c.long" class="ac-row">
                          <span class="ac-label">{{c.label}}</span>
                          <span class="ac-val">{{c.value}}</span>
                        </div>
                        <div v-else class="ac-block">
                          <div class="ac-block-label">{{c.label}}</div>
                          <ui-code-viewer :value="String(c.value ?? '')" :label="c.label" :max-height="260" @copy="$emit('clip-copy', $event)"></ui-code-viewer>
                        </div>
                      </template>
                    </div>
                  </template>
                  <div v-else class="audit-no-change">{{t('audit.noChangeData')}}</div>
                  </template>
        </ui-detail-section>
      </ui-detail-drawer>
    </div>
  `
};
