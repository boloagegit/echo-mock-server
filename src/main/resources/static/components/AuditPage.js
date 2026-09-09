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
    'go-to-rule', 'go-to-response', 'toggle-audit-detail'
  ],
  inject: ['t'],
  computed: {
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
  },
  template: /* html */`
    <div class="page workspace-page audit-workspace" :class="{active:true}">
      <div class="page-header">
        <div class="page-heading">
          <h1 class="page-title">{{t('audit.title')}}</h1>
          <span class="page-count">{{auditTotalElements}}</span>
        </div>
        <div class="page-actions"><ui-button class="btn btn-secondary" @click="$emit('load-audit', true)" :disabled="loading.audit"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.audit}"></i> {{t('audit.refresh')}}</ui-button></div>
      </div>
      <div v-if="auditTruncated" class="page-context-note"><i class="bi bi-info-circle"></i> {{t('audit.truncatedWarning', {days: status?.auditRetentionDays || 30, count: auditLogs.length})}}</div>
      <div class="card workspace-filter-card">
        <div class="card-body filter-row workspace-filter-bar">
          <div class="workspace-filter-controls">
            <ui-toggle-group :model-value="auditFilter.action" :options="actionFilterOptions"
              :aria-label="t('audit.actionFilter')"
              @update:model-value="$emit('update:auditFilter', {...auditFilter, action:$event})"></ui-toggle-group>
            <div class="filter-divider"></div>
            <workspace-search-field
              :model-value="auditFilter.operator"
              :placeholder="t('audit.searchOperator')"
              :aria-label="t('audit.searchOperator')"
              :clear-label="t('audit.clearAll')"
              icon="bi-person" compact
              :submit-mode="true"
              :submit-label="t('common.searchAction')"
              @search="$emit('update:auditFilter', {...auditFilter, operator:$event})"
            ></workspace-search-field>
            <div class="filter-divider"></div>
            <workspace-search-field
              input-id="auditSearch"
              :model-value="auditFilter.keyword"
              :placeholder="t('audit.searchContent')"
              :aria-label="t('audit.searchContent')"
              :clear-label="t('audit.clearAll')"
              :submit-mode="true"
              :submit-label="t('common.searchAction')"
              @search="$emit('update:auditFilter', {...auditFilter, keyword:$event})"
            ></workspace-search-field>
          </div>
        </div>
      </div>
      <ui-filter-chip-list :items="auditFilterChips" :aria-label="t('common.activeFilters')"
        :clear-label="t('audit.clearAll')"
        @remove="$emit('remove-audit-chip', $event)"
        @clear="$emit('clear-audit-filters')"></ui-filter-chip-list>
      <div class="card card-table workspace-table-card">
        <div class="card-table-body">
        <div v-if="loading.audit && !auditLogs.length" role="status" :aria-label="t('common.loading')">
          <div v-for="i in 6" :key="'sk-audit-'+i" class="sk-row">
            <span class="sk sk-text-sm" style="width:90px"></span>
            <span class="sk sk-badge" style="width:60px"></span>
            <span class="sk sk-text-sm" style="width:60px"></span>
            <span class="sk sk-badge" style="width:40px"></span>
            <span class="sk sk-badge" style="width:70px"></span>
            <span class="sk sk-text" style="width:30%;min-width:80px"></span>
            <span class="sk sk-btn"></span>
          </div>
        </div>
        <table v-if="pagedAudit.length" class="table-fixed workspace-table">
          <thead><tr>
            <th class="col-datetime" :aria-sort="auditSort.field==='timestamp'?(auditSort.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('audit.thTime')" :active="auditSort.field==='timestamp'" :ascending="auditSort.asc" @toggle="$emit('toggle-audit-sort','timestamp')"></ui-table-sort-header></th>
            <th style="width:92px" :aria-sort="auditSort.field==='action'?(auditSort.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('audit.thAction')" :active="auditSort.field==='action'" :ascending="auditSort.asc" @toggle="$emit('toggle-audit-sort','action')"></ui-table-sort-header></th>
            <th>{{t('audit.thTarget')}}</th>
            <th class="col-hide-md" style="width:132px" :aria-sort="auditSort.field==='operator'?(auditSort.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('audit.thOperator')" :active="auditSort.field==='operator'" :ascending="auditSort.asc" @toggle="$emit('toggle-audit-sort','operator')"></ui-table-sort-header></th>
            <th class="col-actions col-actions-1">{{t('audit.thActions')}}</th>
          </tr></thead>
          <tbody>
            <template v-for="log in pagedAudit" :key="log.id">
              <tr @click="$emit('toggle-audit-detail', log)" style="cursor:pointer" :class="{active:selectedAudit===log.id}">
                <td class="col-datetime"><span class="sub-info" :title="fmtTime(log.timestamp,false)">{{fmtTime(log.timestamp)}}</span></td>
                <td><ui-badge class="badge" :class="'badge-'+log.action?.toLowerCase()">{{log.action}}</ui-badge><span v-if="getAuditChangeCount(log)" class="sub-info" style="display:block;margin-top:2px">{{getAuditChangeCount(log)}}</span></td>
                <td class="list-identity-cell">
                  <div class="list-record-name" :title="(log.beforeJson||log.afterJson)?getAuditTarget(log):log.ruleId">{{(log.beforeJson||log.afterJson)?getAuditTarget(log):(log.ruleId?.startsWith('response-')?t('audit.typeResponse'):t('audit.typeRule'))}}</div>
                  <div class="list-identity-secondary">
                    <span v-if="log.beforeJson||log.afterJson">{{log.ruleId?.startsWith('response-')?t('audit.typeResponse'):t('audit.typeRule')}}</span>
                    <a v-if="log.action!=='DELETE'" href="#" class="list-inline-id" :title="log.ruleId" @click.stop.prevent="log.ruleId?.startsWith('response-')?$emit('go-to-response',log.ruleId):$emit('go-to-rule',log.ruleId)">{{log.ruleId?.startsWith('response-')?log.ruleId.replace('response-',''):shortId(log.ruleId)}}</a>
                    <span v-else class="list-inline-id" :title="log.ruleId">{{log.ruleId}}</span>
                    <span v-if="getAuditDescription(log)">{{getAuditDescription(log)}}</span>
                    <span v-if="!log.beforeJson && !log.afterJson">{{t('auditValues.expandForDetail')}}</span>
                  </div>
                </td>
                <td class="col-hide-md"><span class="sub-info">{{log.operator}}</span></td>
                <td class="col-actions col-actions-1"><ui-button class="btn btn-sm btn-icon btn-secondary" :title="selectedAudit===log.id?t('audit.collapse'):t('audit.expand')" :aria-label="selectedAudit===log.id?t('audit.collapse'):t('audit.expand')" :aria-expanded="selectedAudit===log.id" :aria-controls="selectedAudit===log.id?'audit-detail-'+log.id:undefined"><i class="bi" :class="selectedAudit===log.id?'bi-chevron-up':'bi-chevron-down'"></i></ui-button></td>
              </tr>
              <tr v-if="selectedAudit===log.id" :id="'audit-detail-'+log.id" class="rule-preview-row">
                <td colspan="5" style="padding:0">
                  <div class="rule-preview-content workspace-detail-surface">
                  <div v-if="log._detailLoading" class="audit-no-change"><i class="bi bi-arrow-clockwise spin"></i> {{t('audit.loadingDetail')}}</div>
                  <template v-else>
                  <template v-for="detail in [getAuditChanges(log)]" :key="log.id">
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
                              <pre>{{c.before}}</pre>
                            </div>
                            <div class="ac-block-panel ac-block-after">
                              <div class="ac-block-title">{{t('audit.afterChange')}}</div>
                              <pre>{{c.after}}</pre>
                            </div>
                          </div>
                        </div>
                      </template>
                    </div>
                    <div v-else class="audit-no-change">{{t('audit.noSubstantialChange')}}</div>
                  </template>
                  <template v-else-if="detail.type==='error'">
                    <pre class="audit-raw">{{detail.raw}}</pre>
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
                          <pre class="ac-block-pre">{{c.value}}</pre>
                        </div>
                      </template>
                    </div>
                  </template>
                  <div v-else class="audit-no-change">{{t('audit.noChangeData')}}</div>
                  </template>
                  </template>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
        <ui-load-state v-if="!pagedAudit.length && !loading.audit" kind="empty" icon="bi-inbox"
          :title="t('audit.emptyNoAudit')" :hint="t('audit.emptyHint')"></ui-load-state>
        </div>
        <workspace-pagination
          :page="auditPage" :total-pages="auditTotalPages" :page-size="auditPageSize"
          :pagination-label="t('audit.pagination')"
          :page-status-label="t('stats.pageStatus', {page:auditPage, total:auditTotalPages})"
          :page-size-label="t('stats.pageSize')"
          :first-page-label="t('stats.firstPage')" :previous-page-label="t('stats.previousPage')"
          :next-page-label="t('stats.nextPage')" :last-page-label="t('stats.lastPage')"
          @update:page="$emit('update:auditPage', $event)"
          @update:page-size="$emit('update:auditPageSize', $event)"
        >
          <template #summary>
            <span class="sub-info">{{t('audit.totalCount', {count: auditTotalElements})}}</span>
            <ui-button v-if="auditFilter.action||auditFilter.operator||auditFilter.keyword" type="button" variant="quiet" size="compact" class="workspace-filter-reset" :title="t('audit.clickClearFilter')" @click="$emit('clear-audit-filters')"><i class="bi bi-funnel-fill" aria-hidden="true"></i> {{t('audit.filtering')}}</ui-button>
          </template>
        </workspace-pagination>
      </div>
    </div>
  `
};
