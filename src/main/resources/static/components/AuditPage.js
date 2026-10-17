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
    getAuditProtocol: Function,
    describeAuditRow: Function,
    auditToday: String,
    targetHistory: Object
  },
  emits: [
    'load-audit', 'update:selectedAudit', 'update:auditFilter',
    'update:auditPage', 'update:auditPageSize',
    'toggle-audit-sort', 'delete-all-audit',
    'remove-audit-chip', 'clear-audit-filters',
    'go-to-rule', 'go-to-response', 'toggle-audit-detail', 'clip-copy',
    'load-target-history', 'filter-audit-target'
  ],
  data() {
    return { fullDiff: {} };
  },
  watch: {
    // 打開某筆記錄時，順便載入同一個規則／回應的最近記錄。
    'shownLog.ruleId': {
      immediate: true,
      handler(ruleId) { if (ruleId) this.$emit('load-target-history', ruleId); }
    }
  },
  inject: ['t'],
  components: {
    // 一列修訂記錄「改了什麼」的精簡列表；寬螢幕放在自己的欄位，窄螢幕放在名稱下方。
    'audit-change-summary': {
      props: { row: Object },
      inject: ['t'],
      methods: {
        changeTitle(c) {
          if (c.kind !== 'value') return c.label;
          const none = this.t('auditValues.none');
          return c.label + ': ' + (c.before === null ? none : c.before) + ' → ' + (c.after === null ? none : c.after);
        }
      },
      template: /* html */`
        <span class="audit-change-summary">
          <template v-for="(c, i) in row.changes" :key="i">
            <span class="audit-change" :class="'is-' + c.kind" :title="changeTitle(c)">
              <span class="audit-change__label">{{c.label}}</span>
              <template v-if="c.kind==='value'">
                <span class="audit-change__old" :class="{'is-empty': c.before===null}">{{c.before===null ? t('auditValues.none') : c.before}}</span>
                <i class="bi bi-arrow-right audit-change__arrow" aria-hidden="true"></i><span class="visually-hidden">{{t('audit.changedTo')}}</span>
                <span class="audit-change__new" :class="{'is-empty': c.after===null}">{{c.after===null ? t('auditValues.none') : c.after}}</span>
              </template>
              <template v-else-if="c.kind==='tags'">
                <span v-for="tag in c.added" :key="'+'+tag" class="audit-change__new">+{{tag}}</span>
                <span v-for="tag in c.removed" :key="'-'+tag" class="audit-change__old">−{{tag}}</span>
              </template>
              <span v-else-if="c.kind==='lines'" class="audit-change__lines">{{t('audit.lineDelta', {add: c.add, del: c.del})}}</span>
              <span v-else class="audit-change__lines">{{t('audit.contentChanged')}}</span>
            </span>
          </template>
          <span v-if="row.more" class="audit-change-more">{{t('audit.moreFields', {count: row.more})}}</span>
          <span v-if="row.facts.length" class="audit-change-facts">{{row.facts.join(' · ')}}</span>
        </span>`
    }
  },
  mixins: [heldDetailMixin(function () {
    const log = this.selectedLog;
    return { open: !!log, key: log?.id, ready: !log?._detailLoading, value: log };
  })],
  computed: {
    shownLog() {
      return this.held.value;
    },
    hasAuditFilters() {
      return Boolean(this.auditFilter.action || this.auditFilter.operator || this.auditFilter.keyword || this.auditFilter.type || this.auditFilter.ruleId);
    },
    selectedIndex() {
      return this.pagedAudit.findIndex(log => log.id === this.selectedAudit);
    },
    selectedLog() {
      return this.selectedIndex >= 0 ? this.pagedAudit[this.selectedIndex] : null;
    },
    /** 時間軸只抓最新幾筆；打開的是更舊的記錄時把它補進去，並在前面標出中間略過的部分。 */
    shownHistory() {
      const log = this.shownLog;
      const history = log?.ruleId ? (this.targetHistory?.[log.ruleId] || null) : null;
      if (!history || history.loading || history.failed || history.items.some(e => e.id === log.id)) { return history; }
      const items = history.items.filter(e => String(e.timestamp || '') >= String(log.timestamp || ''));
      const older = history.items.slice(items.length);
      return { ...history, items: [...items, { ...log, _gapBefore: items.length > 0 }, ...older] };
    },
    groupedByDay() {
      return this.auditSort.field === 'timestamp';
    },
    /** 列表內容：依日期分段（依時間排序時），每列附上顯示用摘要。 */
    auditRows() {
      const rows = [];
      let day = null;
      for (const log of this.pagedAudit) {
        const key = String(log.timestamp || '').substring(0, 10);
        if (this.groupedByDay && key && key !== day) {
          day = key;
          rows.push({ key: 'day-' + key, day: key, label: this.dayLabel(key) });
        }
        rows.push({ key: log.id, log, view: this.describeAuditRow(log) });
      }
      return rows;
    },
    typeFilterOptions() {
      return [
        { value: 'RULE', label: this.t('audit.typeRule') },
        { value: 'RESPONSE', label: this.t('audit.typeResponse') },
      ];
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
    /** 修訂時間是伺服器時區，所以「今天／昨天」以伺服器日期判斷；不知道時只顯示日期。 */
    dayLabel(key) {
      const today = this.auditToday;
      if (!/^\d{4}-\d{2}-\d{2}$/.test(today || '')) return key;
      const [y, m, d] = today.split('-').map(Number);
      const prev = new Date(Date.UTC(y, m - 1, d - 1)).toISOString().substring(0, 10);
      if (key === today) return this.t('audit.dayToday', {date: key});
      if (key === prev) return this.t('audit.dayYesterday', {date: key});
      return key;
    },
    /** 長內容的逐行比對；同一筆記錄同一欄位只算一次。 */
    diffOf(log, change) {
      const full = !!this.fullDiff[log.id + ':' + change.label];
      const key = log.id + ':' + change.label + ':' + (full ? 'full' : 'changed');
      this._diffCache = this._diffCache || new Map();
      if (!this._diffCache.has(key)) {
        if (this._diffCache.size > 50) this._diffCache.clear();
        this._diffCache.set(key, AuditDiff.lines(change.beforeText ?? '', change.afterText ?? '', { context: full ? Infinity : 3 }));
      }
      return this._diffCache.get(key);
    },
    toggleFullDiff(log, change) {
      const key = log.id + ':' + change.label;
      this.fullDiff = { ...this.fullDiff, [key]: !this.fullDiff[key] };
    },
    historyEntryLabel(entry) {
      const count = entry.summary?.changeCount;
      return this.auditActionLabel(entry.action) + (entry.action === 'UPDATE' && count ? ' · ' + this.t('audit.fieldCount', {count}) : '');
    },
    rowTime(log) {
      return this.groupedByDay ? String(log.timestamp || '').substring(11, 16) : fmtTime(log.timestamp);
    },
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
      if (log.targetName) return log.targetName;
      return (log.beforeJson || log.afterJson) ? this.getAuditTarget(log) : this.describeAuditRow(log).name;
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
        <ui-toggle-group :model-value="auditFilter.type" :options="typeFilterOptions" :aria-label="t('audit.typeFilter')"
          @update:model-value="$emit('update:auditFilter', {...auditFilter, type:$event})"></ui-toggle-group>
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
          <table v-else-if="pagedAudit.length" class="data-table audit-table" :class="{'is-grouped': groupedByDay}">
            <thead><tr>
              <th class="col-time" :aria-sort="sortState('timestamp')"><ui-table-sort-header :label="t('audit.thTime')" :active="auditSort.field==='timestamp'" :ascending="auditSort.asc" @toggle="$emit('toggle-audit-sort','timestamp')"></ui-table-sort-header></th>
              <th class="col-action" :aria-sort="sortState('action')"><ui-table-sort-header :label="t('audit.thAction')" :active="auditSort.field==='action'" :ascending="auditSort.asc" @toggle="$emit('toggle-audit-sort','action')"></ui-table-sort-header></th>
              <th class="col-target">{{t('audit.thTarget')}}</th>
              <th class="col-changes">{{t('audit.thChanges')}}</th>
              <th class="col-operator" :aria-sort="sortState('operator')"><ui-table-sort-header :label="t('audit.thOperator')" :active="auditSort.field==='operator'" :ascending="auditSort.asc" @toggle="$emit('toggle-audit-sort','operator')"></ui-table-sort-header></th>
              <th class="col-actions"><span class="visually-hidden">{{t('audit.thActions')}}</span></th>
            </tr></thead>
            <tbody>
              <template v-for="row in auditRows" :key="row.key">
                <tr v-if="row.day" class="audit-day-row">
                  <td class="col-time"><span class="audit-day-row__label">{{row.label}}</span></td>
                  <td class="col-action"></td>
                  <td class="col-target"><span class="audit-day-row__label is-narrow">{{row.label}}</span></td>
                  <td class="col-changes"></td><td class="col-operator"></td><td class="col-actions"></td>
                </tr>
                <tr v-else data-detail-row tabindex="0"
                  :class="{'is-selected': selectedAudit===row.log.id}" :aria-selected="selectedAudit===row.log.id ? 'true' : 'false'"
                  @click="$emit('toggle-audit-detail', row.log)" @keydown="onRowKeydown($event, row.log)">
                  <td class="col-time cell-mono cell-subtle" :title="fmtTime(row.log.timestamp,false)">{{rowTime(row.log)}}</td>
                  <td class="col-action"><ui-badge class="badge" :class="'badge-'+row.log.action?.toLowerCase()">{{auditActionLabel(row.log.action)}}</ui-badge></td>
                  <td class="col-target">
                    <div class="audit-target">
                      <div class="audit-target__top">
                        <span class="cell-mono cell-subtle" :title="fmtTime(row.log.timestamp,false)">{{rowTime(row.log)}}</span>
                        <ui-badge class="badge" :class="'badge-'+row.log.action?.toLowerCase()">{{auditActionLabel(row.log.action)}}</ui-badge>
                        <span class="audit-target__operator cell-subtle">{{row.log.operator}}</span>
                      </div>
                      <span class="audit-target__name" :class="{'is-deleted': row.log.action==='DELETE', 'is-unnamed': !row.view.named}">{{row.view.name}}</span>
                      <span class="audit-target__meta">
                        <span v-if="row.view.protocol" class="audit-proto" :class="'is-' + row.view.protocol.toLowerCase()">{{row.view.protocolLabel}}</span>
                        <span class="audit-target__meta-text">{{row.view.meta.join(' · ')}}</span>
                      </span>
                      <audit-change-summary class="audit-target__changes" :row="row.view"></audit-change-summary>
                    </div>
                  </td>
                  <td class="col-changes"><audit-change-summary :row="row.view"></audit-change-summary></td>
                  <td class="col-operator cell-subtle">{{row.log.operator}}</td>
                  <td class="col-actions" @click.stop @dblclick.stop>
                    <span class="row-actions"><ui-row-menu :items="auditMenuItems(row.log)" :label="t('common.moreActions') + ' ' + row.view.name" @select="handleAuditMenu($event, row.log)"></ui-row-menu></span>
                  </td>
                </tr>
              </template>
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
        <template v-if="shownLog" #actions>
          <ui-button v-if="shownLog.action!=='DELETE'" variant="secondary" size="compact" @click="openTarget(shownLog)"><i class="bi bi-box-arrow-up-right" aria-hidden="true"></i>{{isResponse(shownLog) ? t('audit.openResponse') : t('audit.openRule')}}</ui-button>
          <ui-button v-if="auditFilter.ruleId !== shownLog.ruleId" variant="secondary" size="compact" @click="$emit('filter-audit-target', shownLog.ruleId)"><i class="bi bi-funnel" aria-hidden="true"></i>{{t('audit.filterThisItem')}}</ui-button>
        </template>
        <ui-detail-section v-if="shownLog" id="audit.changes" class="audit-changes" :title="t('audit.changesTitle')" :summary="getAuditChangeCount(shownLog) ? String(getAuditChangeCount(shownLog)) : ''">
                  <template v-for="detail in [getAuditChanges(shownLog)]" :key="shownLog.id">
                  <template v-if="detail.type==='update'">
                    <div v-if="detail.changes.length" class="ac-list">
                      <template v-for="c in detail.changes" :key="c.label">
                        <div v-if="c.kind==='tags'" class="ac-row">
                          <span class="ac-label">{{c.label}}</span>
                          <span class="ac-tags">
                            <span v-for="tag in c.added" :key="'+'+tag" class="ac-val ac-after">+ {{tag}}</span>
                            <span v-for="tag in c.removed" :key="'-'+tag" class="ac-val ac-before">− {{tag}}</span>
                          </span>
                        </div>
                        <div v-else-if="!c.long" class="ac-row">
                          <span class="ac-label">{{c.label}}</span>
                          <span class="ac-val ac-before" :class="{'ac-empty':c.beforeText===''}">{{c.before}}</span>
                          <i class="bi bi-arrow-right ac-arrow" aria-hidden="true"></i><span class="visually-hidden">{{t('audit.changedTo')}}</span>
                          <span class="ac-val ac-after" :class="{'ac-empty':c.afterText===''}">{{c.after}}</span>
                        </div>
                        <div v-else-if="!diffOf(shownLog, c).tooLarge" class="ac-block ac-diff">
                          <div class="ac-block-label ac-diff__head">
                            <span>{{c.label}}</span>
                            <span class="ac-diff__count">{{t('audit.lineDelta', {add: diffOf(shownLog, c).added, del: diffOf(shownLog, c).removed})}}</span>
                            <ui-button variant="quiet" size="compact" class="ac-diff__toggle" :aria-pressed="!!fullDiff[shownLog.id + ':' + c.label]" @click="toggleFullDiff(shownLog, c)">{{fullDiff[shownLog.id + ':' + c.label] ? t('audit.diffChangedOnly') : t('audit.diffShowAll')}}</ui-button>
                            <ui-button variant="quiet" size="compact" :aria-label="t('audit.copyAfter')" :title="t('audit.copyAfter')" @click="$emit('clip-copy', c.afterText)"><i class="bi bi-clipboard" aria-hidden="true"></i></ui-button>
                          </div>
                          <div class="ac-diff__body" role="table" :aria-label="c.label">
                            <template v-for="(hunk, hi) in diffOf(shownLog, c).hunks" :key="hi">
                              <div v-if="hi > 0" class="ac-diff__gap" aria-hidden="true">⋯</div>
                              <div v-for="(line, li) in hunk.lines" :key="hi + '-' + li" class="ac-diff__line" :class="{'is-add': line.type==='+', 'is-del': line.type==='-'}" role="row">
                                <span class="ac-diff__no" role="cell">{{line.oldNo ?? ''}}</span>
                                <span class="ac-diff__no" role="cell">{{line.newNo ?? ''}}</span>
                                <span class="ac-diff__sign" role="cell">{{line.type === ' ' ? '' : (line.type === '+' ? '+' : '−')}}<span class="visually-hidden">{{line.type==='+' ? t('audit.lineAdded') : line.type==='-' ? t('audit.lineRemoved') : ''}}</span></span>
                                <span class="ac-diff__text" role="cell">{{line.text}}</span>
                              </div>
                            </template>
                          </div>
                        </div>
                        <div v-else class="ac-block">
                          <div class="ac-block-label">{{c.label}}</div>
                          <div class="ac-block-diff">
                            <div class="ac-block-panel ac-block-before">
                              <div class="ac-block-title">{{t('audit.beforeChange')}}</div>
                              <ui-code-viewer :value="String(c.beforeText ?? c.before ?? '')" :label="c.label + ' · ' + t('audit.beforeChange')" :max-height="260" @copy="$emit('clip-copy', $event)"></ui-code-viewer>
                            </div>
                            <div class="ac-block-panel ac-block-after">
                              <div class="ac-block-title">{{t('audit.afterChange')}}</div>
                              <ui-code-viewer :value="String(c.afterText ?? c.after ?? '')" :label="c.label + ' · ' + t('audit.afterChange')" :max-height="260" @copy="$emit('clip-copy', $event)"></ui-code-viewer>
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
        <ui-detail-section v-if="shownLog" id="audit.history" class="audit-history" :title="t('audit.historyTitle')"
          :summary="shownHistory && shownHistory.total ? String(shownHistory.total) : ''">
          <div v-if="!shownHistory || shownHistory.loading" class="audit-no-change">{{t('common.loading')}}</div>
          <div v-else-if="shownHistory.failed" class="audit-no-change">{{t('audit.historyLoadFailed')}}</div>
          <ol v-else class="audit-history__list">
            <template v-for="entry in shownHistory.items" :key="entry.id">
            <li v-if="entry._gapBefore" class="audit-history__gap" aria-hidden="true">⋯</li>
            <li class="audit-history__item" :class="{'is-current': entry.id === shownLog.id}" :aria-current="entry.id === shownLog.id ? 'true' : null">
              <span class="audit-history__dot" aria-hidden="true"></span>
              <span class="audit-history__what">{{historyEntryLabel(entry)}}<span v-if="entry.id === shownLog.id" class="audit-history__current">{{t('audit.currentEntry')}}</span></span>
              <span class="audit-history__when cell-subtle"><span class="detail-mono">{{fmtTime(entry.timestamp)}}</span> · {{entry.operator}}</span>
            </li>
            </template>
          </ol>
          <ui-button v-if="shownHistory && shownHistory.total > shownHistory.items.length && auditFilter.ruleId !== shownLog.ruleId" variant="ghost" size="compact" class="audit-history__more"
            @click="$emit('filter-audit-target', shownLog.ruleId)">{{t('audit.showAllHistory', {count: shownHistory.total})}}</ui-button>
        </ui-detail-section>
      </ui-detail-drawer>
    </div>
  `
};
