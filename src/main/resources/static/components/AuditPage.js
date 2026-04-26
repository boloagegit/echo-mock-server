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
    'debounced-load-audit'
  ],
  inject: ['t'],
  methods: {
    shortId, fmtTime,
  },
  template: /* html */`
    <div class="page" :class="{active:true}">
      <div class="page-header">
        <h1 class="page-title">{{t('audit.title')}}</h1>
        <button class="btn btn-secondary" @click="$emit('load-audit', true)" :disabled="loading.audit"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.audit}"></i> {{t('audit.refresh')}}</button>
      </div>
      <div v-if="auditTruncated" class="alert alert-info" style="margin-bottom:1rem"><i class="bi bi-info-circle"></i> {{t('audit.truncatedWarning', {days: status?.auditRetentionDays || 30, count: auditLogs.length})}}</div>
      <div class="card" style="margin-bottom:0.5rem">
        <div class="card-body filter-row">
          <div style="display:flex;gap:0.5rem;flex-wrap:wrap;align-items:center;flex:1">
            <div class="btn-group">
              <button class="btn btn-sm" :class="auditFilter.action==='CREATE'?'btn-primary':'btn-secondary'" @click="$emit('update:auditFilter', {...auditFilter, action: auditFilter.action==='CREATE'?'':'CREATE'})">CREATE</button>
              <button class="btn btn-sm" :class="auditFilter.action==='UPDATE'?'btn-primary':'btn-secondary'" @click="$emit('update:auditFilter', {...auditFilter, action: auditFilter.action==='UPDATE'?'':'UPDATE'})">UPDATE</button>
              <button class="btn btn-sm" :class="auditFilter.action==='DELETE'?'btn-primary':'btn-secondary'" @click="$emit('update:auditFilter', {...auditFilter, action: auditFilter.action==='DELETE'?'':'DELETE'})">DELETE</button>
            </div>
            <div class="filter-divider"></div>
            <input :value="auditFilter.operator" @input="$emit('update:auditFilter', {...auditFilter, operator: $event.target.value})" :placeholder="t('audit.searchOperator')" class="form-control" style="flex:1;min-width:80px;max-width:140px">
            <div class="filter-divider"></div>
            <input id="auditSearch" :value="auditFilter.keyword" @input="$emit('update:auditFilter', {...auditFilter, keyword: $event.target.value})" :placeholder="t('audit.searchContent')" class="form-control" style="flex:1;min-width:120px">
          </div>
        </div>
      </div>
      <div v-if="auditFilterChips.length" class="filter-chips">
        <span class="filter-chip" v-for="c in auditFilterChips" :key="c.key">{{c.label}} <button class="chip-remove" @click="$emit('remove-audit-chip', c.key)"><i class="bi bi-x"></i></button></span>
        <button class="chip-clear" @click="$emit('clear-audit-filters')">{{t('audit.clearAll')}}</button>
      </div>
      <div class="card card-table">
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
        <table v-if="pagedAudit.length" class="table-fixed">
          <thead><tr>
            <th style="width:96px;cursor:pointer" @click="$emit('toggle-audit-sort','timestamp')">{{t('audit.thTime')}} <i class="bi" :class="auditSort.field==='timestamp'?(auditSort.asc?'bi-caret-up-fill':'bi-caret-down-fill'):'bi-arrow-down-up'"></i></th>
            <th style="width:92px;cursor:pointer" @click="$emit('toggle-audit-sort','action')">{{t('audit.thAction')}} <i class="bi" :class="auditSort.field==='action'?(auditSort.asc?'bi-caret-up-fill':'bi-caret-down-fill'):'bi-arrow-down-up'"></i></th>
            <th class="col-hide-md" style="width:100px;cursor:pointer" @click="$emit('toggle-audit-sort','operator')">{{t('audit.thOperator')}} <i class="bi" :class="auditSort.field==='operator'?(auditSort.asc?'bi-caret-up-fill':'bi-caret-down-fill'):'bi-arrow-down-up'"></i></th>
            <th class="col-hide-md" style="width:72px">{{t('audit.thType')}}</th>
            <th class="col-id col-hide-md">{{t('audit.thId')}}</th>
            <th>{{t('audit.thEndpoint')}}</th>
            <th class="col-actions col-actions-1">{{t('audit.thActions')}}</th>
          </tr></thead>
          <tbody>
            <template v-for="log in pagedAudit" :key="log.id">
              <tr @click="$emit('update:selectedAudit', selectedAudit===log.id?null:log.id)" style="cursor:pointer" :class="{active:selectedAudit===log.id}">
                <td><span class="sub-info" :title="fmtTime(log.timestamp,false)">{{fmtTime(log.timestamp)}}</span></td>
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
                <td class="col-actions col-actions-1"><button class="btn btn-sm btn-icon btn-secondary" :title="selectedAudit===log.id?t('audit.collapse'):t('audit.expand')"><i class="bi" :class="selectedAudit===log.id?'bi-chevron-up':'bi-chevron-down'"></i></button></td>
              </tr>
              <tr v-if="selectedAudit===log.id" class="rule-preview-row">
                <td colspan="7" style="padding:0">
                  <div class="rule-preview-content">
                  <template v-if="getAuditChanges(log).type==='update'">
                    <div v-if="getAuditChanges(log).changes.length" class="ac-list">
                      <template v-for="c in getAuditChanges(log).changes" :key="c.label">
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
                  <template v-else-if="getAuditChanges(log).type==='error'">
                    <pre class="audit-raw">{{getAuditChanges(log).raw}}</pre>
                  </template>
                  <template v-else-if="getAuditChanges(log).changes?.length">
                    <div class="ac-list">
                      <template v-for="c in getAuditChanges(log).changes" :key="c.label">
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
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
        <div v-if="!pagedAudit.length && !loading.audit" class="empty"><i class="bi bi-inbox"></i><div>{{t('audit.emptyNoAudit')}}</div></div>
        </div>
        <div class="card-table-footer">
          <span class="sub-info">{{t('audit.totalCount', {count: auditLogs.length})}}</span>
          <span v-if="auditFilter.action||auditFilter.operator||auditFilter.keyword" class="badge badge-warning" style="margin-left:8px;cursor:pointer" :title="t('audit.clickClearFilter')" @click="$emit('clear-audit-filters')"><i class="bi bi-funnel-fill"></i> {{t('audit.filtering')}}</span>
          <div class="pagination-controls">
            <button class="btn btn-sm btn-secondary" @click="$emit('update:auditPage', 1)" :disabled="auditPage===1" aria-label="First page"><i class="bi bi-chevron-double-left"></i></button>
            <button class="btn btn-sm btn-secondary" @click="$emit('update:auditPage', auditPage-1)" :disabled="auditPage===1" aria-label="Previous page"><i class="bi bi-chevron-left"></i></button>
            <span>{{auditPage}} / {{auditTotalPages}}</span>
            <button class="btn btn-sm btn-secondary" @click="$emit('update:auditPage', auditPage+1)" :disabled="auditPage>=auditTotalPages" aria-label="Next page"><i class="bi bi-chevron-right"></i></button>
            <button class="btn btn-sm btn-secondary" @click="$emit('update:auditPage', auditTotalPages)" :disabled="auditPage>=auditTotalPages" aria-label="Last page"><i class="bi bi-chevron-double-right"></i></button>
          </div>
          <select :value="auditPageSize" @change="$emit('update:auditPageSize', Number($event.target.value))" class="form-control" style="width:auto" aria-label="Page size"><option :value="10">10</option><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option></select>
        </div>
      </div>
    </div>
  `
};
