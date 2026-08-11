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
    'go-to-rule', 'go-to-response',
    'debounced-load-audit', 'toggle-audit-detail'
  ],
  inject: ['t'],
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
        <div class="page-actions"><button class="btn btn-secondary" @click="$emit('load-audit', true)" :disabled="loading.audit"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.audit}"></i> {{t('audit.refresh')}}</button></div>
      </div>
      <div v-if="auditTruncated" class="page-context-note"><i class="bi bi-info-circle"></i> {{t('audit.truncatedWarning', {days: status?.auditRetentionDays || 30, count: auditLogs.length})}}</div>
      <div class="card workspace-filter-card">
        <div class="card-body filter-row workspace-filter-bar">
          <div class="workspace-filter-controls">
            <div class="btn-group">
              <button class="btn btn-sm" :class="auditFilter.action==='CREATE'?'btn-primary':'btn-secondary'" @click="$emit('update:auditFilter', {...auditFilter, action: auditFilter.action==='CREATE'?'':'CREATE'})">{{t('audit.actionCreate')}}</button>
              <button class="btn btn-sm" :class="auditFilter.action==='UPDATE'?'btn-primary':'btn-secondary'" @click="$emit('update:auditFilter', {...auditFilter, action: auditFilter.action==='UPDATE'?'':'UPDATE'})">{{t('audit.actionUpdate')}}</button>
              <button class="btn btn-sm" :class="auditFilter.action==='DELETE'?'btn-primary':'btn-secondary'" @click="$emit('update:auditFilter', {...auditFilter, action: auditFilter.action==='DELETE'?'':'DELETE'})">{{t('audit.actionDelete')}}</button>
            </div>
            <div class="filter-divider"></div>
            <workspace-search-field
              :model-value="auditFilter.operator"
              :placeholder="t('audit.searchOperator')"
              :aria-label="t('audit.searchOperator')"
              :clear-label="t('audit.clearAll')"
              icon="bi-person" compact :show-clear="false"
              @update:model-value="$emit('update:auditFilter', {...auditFilter, operator:$event})"
            ></workspace-search-field>
            <div class="filter-divider"></div>
            <workspace-search-field
              input-id="auditSearch"
              :model-value="auditFilter.keyword"
              :placeholder="t('audit.searchContent')"
              :aria-label="t('audit.searchContent')"
              :clear-label="t('audit.clearAll')"
              @update:model-value="$emit('update:auditFilter', {...auditFilter, keyword:$event})"
            ></workspace-search-field>
          </div>
        </div>
      </div>
      <div v-if="auditFilterChips.length" class="filter-chips">
        <span class="filter-chip" v-for="c in auditFilterChips" :key="c.key">{{c.label}} <button class="chip-remove" @click="$emit('remove-audit-chip', c.key)"><i class="bi bi-x"></i></button></span>
        <button class="chip-clear" @click="$emit('clear-audit-filters')">{{t('audit.clearAll')}}</button>
      </div>
      <div class="card card-table workspace-table-card">
        <div class="card-table-body">
        <div v-if="loading.audit && !auditLogs.length">
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
            <th class="col-datetime" style="cursor:pointer" @click="$emit('toggle-audit-sort','timestamp')">{{t('audit.thTime')}} <i class="bi" :class="auditSort.field==='timestamp'?(auditSort.asc?'bi-caret-up-fill':'bi-caret-down-fill'):'bi-arrow-down-up'"></i></th>
            <th style="width:92px;cursor:pointer" @click="$emit('toggle-audit-sort','action')">{{t('audit.thAction')}} <i class="bi" :class="auditSort.field==='action'?(auditSort.asc?'bi-caret-up-fill':'bi-caret-down-fill'):'bi-arrow-down-up'"></i></th>
            <th class="col-hide-md" style="width:100px;cursor:pointer" @click="$emit('toggle-audit-sort','operator')">{{t('audit.thOperator')}} <i class="bi" :class="auditSort.field==='operator'?(auditSort.asc?'bi-caret-up-fill':'bi-caret-down-fill'):'bi-arrow-down-up'"></i></th>
            <th class="col-hide-md" style="width:72px">{{t('audit.thType')}}</th>
            <th class="col-id col-hide-md">{{t('audit.thId')}}</th>
            <th>{{t('audit.thEndpoint')}}</th>
            <th class="col-actions col-actions-1">{{t('audit.thActions')}}</th>
          </tr></thead>
          <tbody>
            <template v-for="log in pagedAudit" :key="log.id">
              <tr @click="$emit('toggle-audit-detail', log)" style="cursor:pointer" :class="{active:selectedAudit===log.id}">
                <td class="col-datetime"><span class="sub-info" :title="fmtTime(log.timestamp,false)">{{fmtTime(log.timestamp)}}</span></td>
                <td><span class="badge" :class="'badge-'+log.action?.toLowerCase()">{{log.action}}</span><span v-if="getAuditChangeCount(log)" class="sub-info" style="display:block;margin-top:2px">{{getAuditChangeCount(log)}}</span></td>
                <td class="col-hide-md"><span class="sub-info">{{log.operator}}</span></td>
                <td class="col-hide-md"><span class="badge" :class="log.ruleId && log.ruleId.startsWith('response-') ? 'badge-resp' : 'badge-http'">{{log.ruleId && log.ruleId.startsWith('response-') ? t('audit.typeResponse') : t('audit.typeRule')}}</span></td>
                <td class="col-id col-hide-md"><a v-if="log.action!=='DELETE'" href="#" class="badge badge-id" :title="log.ruleId" @click.stop.prevent="log.ruleId && log.ruleId.startsWith('response-') ? $emit('go-to-response', log.ruleId) : $emit('go-to-rule', log.ruleId)">{{log.ruleId && log.ruleId.startsWith('response-') ? log.ruleId.replace('response-','') : shortId(log.ruleId)}}</a><span v-else class="badge badge-id" :title="log.ruleId">{{log.ruleId && log.ruleId.startsWith('response-') ? log.ruleId.replace('response-','') : shortId(log.ruleId)}}</span></td>
                <td>
                  <div style="display:flex;align-items:center;gap:0.5rem">
                    <code style="font-weight:500" :title="getAuditTarget(log)">{{getAuditTarget(log)}}</code>
                  </div>
                  <div v-if="getAuditDescription(log)" class="sub-info" style="margin-top:2px" :title="getAuditDescription(log)">{{getAuditDescription(log)}}</div>
                </td>
                <td class="col-actions col-actions-1"><button class="btn btn-sm btn-icon btn-secondary" :title="selectedAudit===log.id?t('audit.collapse'):t('audit.expand')" :aria-label="selectedAudit===log.id?t('audit.collapse'):t('audit.expand')"><i class="bi" :class="selectedAudit===log.id?'bi-chevron-up':'bi-chevron-down'"></i></button></td>
              </tr>
              <tr v-if="selectedAudit===log.id" class="rule-preview-row">
                <td colspan="7" style="padding:0">
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
        <div v-if="!pagedAudit.length && !loading.audit" class="empty workspace-empty"><i class="bi bi-inbox"></i><div class="workspace-empty-title">{{t('audit.emptyNoAudit')}}</div><div class="workspace-empty-hint">{{t('audit.emptyHint')}}</div></div>
        </div>
        <workspace-pagination
          :page="auditPage" :total-pages="auditTotalPages" :page-size="auditPageSize"
          :pagination-label="t('stats.pagination')"
          :page-status-label="t('stats.pageStatus', {page:auditPage, total:auditTotalPages})"
          :page-size-label="t('stats.pageSize')"
          :first-page-label="t('stats.firstPage')" :previous-page-label="t('stats.previousPage')"
          :next-page-label="t('stats.nextPage')" :last-page-label="t('stats.lastPage')"
          @update:page="$emit('update:auditPage', $event)"
          @update:page-size="$emit('update:auditPageSize', $event)"
        >
          <template #summary>
            <span class="sub-info">{{t('audit.totalCount', {count: auditTotalElements})}}</span>
            <button v-if="auditFilter.action||auditFilter.operator||auditFilter.keyword" type="button" class="workspace-filter-reset" :title="t('audit.clickClearFilter')" @click="$emit('clear-audit-filters')"><i class="bi bi-funnel-fill" aria-hidden="true"></i> {{t('audit.filtering')}}</button>
          </template>
        </workspace-pagination>
      </div>
    </div>
  `
};
