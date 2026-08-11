/**
 * ResponsesPage - 回應管理頁面
 *
 * 顯示共用回應列表，含篩選、排序、批次操作、關聯規則展開。
 */
const ResponsesPage = {
  props: {
    responseSummary: Array,
    loading: Object,
    isLoggedIn: Boolean,
    responseFilter: String,
    responseSort: Object,
    pagedResponseSummary: Array,
    responseFilterChips: Array,
    responseUsageFilter: String,
    responseContentTypeFilter: String,
    batchSelectResponseMode: Boolean,
    selectedResponses: Array,
    showResponseDataDropdown: Boolean,
    bulkImportExportEnabled: Boolean,
    status: Object,
    responsePage: Number,
    responsePageSize: Number,
    responseTotalPages: Number,
  },
  emits: [
    'load-responses', 'open-response-modal', 'delete-response',
    'update:responseFilter', 'update:batchSelectResponseMode',
    'update:selectedResponses', 'update:responseUsageFilter',
    'update:responseContentTypeFilter',
    'update:responsePage', 'update:responsePageSize',
    'toggle-response-sort', 'toggle-select-all-responses',
    'export-responses', 'import-responses', 'delete-selected-responses',
    'toggle-response-rules', 'toggle-response-data-dropdown',
    'trigger-response-import',
    'remove-response-chip', 'clear-response-filters',
    'go-to-rule', 'extend-response',
  ],
  inject: ['t'],
  methods: {
    shortId, fmtTime, fmtSize, daysLeft,
    responseSortIcon(f) {
      return this.responseSort.field === f
        ? (this.responseSort.asc ? 'bi-caret-up-fill' : 'bi-caret-down-fill')
        : 'bi-caret-down';
    },
    toggleSelection(id, checked) {
      const arr = [...this.selectedResponses];
      if (checked) {
        if (!arr.includes(id)) { arr.push(id); }
      } else {
        const idx = arr.indexOf(id);
        if (idx >= 0) { arr.splice(idx, 1); }
      }
      this.$emit('update:selectedResponses', arr);
    },
  },
  template: /* html */`
    <div class="page workspace-page responses-workspace" :class="{active:true}">
      <div class="page-header">
        <div class="page-heading">
          <h1 class="page-title">{{t('responses.title')}}</h1>
          <span class="page-count">{{responseSummary.length}}</span>
        </div>
        <div class="page-actions">
          <button class="btn btn-secondary" @click="$emit('load-responses', true)" :disabled="loading.responses"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.responses}"></i> {{t('responses.refresh')}}</button>
          <button class="btn btn-secondary" @click="$emit('update:batchSelectResponseMode', !batchSelectResponseMode); $emit('update:selectedResponses', [])" :disabled="!isLoggedIn" :class="{'active':batchSelectResponseMode}"><i class="bi bi-check2-square"></i> {{t('responses.batchSelect')}}</button>
          <button class="btn btn-danger" @click="$emit('delete-selected-responses')" v-if="batchSelectResponseMode && selectedResponses.length"><i class="bi bi-trash"></i> {{t('responses.deleteCount', {count: selectedResponses.length})}}</button>
          <button class="btn btn-primary" @click="$emit('open-response-modal', null)" :disabled="!isLoggedIn"><i class="bi bi-plus-lg"></i> {{t('responses.addResponse')}}</button>
          <div v-if="isLoggedIn && bulkImportExportEnabled" class="resp-data-dropdown-wrapper data-dropdown-wrapper">
            <button type="button" class="btn btn-secondary btn-icon" @click.stop="$emit('toggle-response-data-dropdown')" :title="t('responses.exportImport')" :aria-label="t('responses.exportImport')" :aria-expanded="showResponseDataDropdown?'true':'false'" aria-haspopup="menu"><i class="bi bi-three-dots-vertical" aria-hidden="true"></i></button>
            <div v-if="showResponseDataDropdown" class="data-dropdown" role="menu">
              <button type="button" class="data-dropdown-item" role="menuitem" @click="$emit('export-responses')"><i class="bi bi-box-arrow-up" aria-hidden="true"></i> {{t('responses.exportResponses')}}</button>
              <button type="button" class="data-dropdown-item" role="menuitem" @click="$emit('trigger-response-import')"><i class="bi bi-box-arrow-in-down" aria-hidden="true"></i> {{t('responses.importResponses')}}</button>
            </div>
            <input id="responseImportInput2" type="file" accept=".json" @change="$emit('import-responses', $event)" hidden>
          </div>
        </div>
      </div>
      <div class="page-context-note">
        <i class="bi bi-info-circle"></i> {{t('responses.sharedInfo')}}
      </div>
      <div class="card workspace-filter-card">
        <div class="card-body filter-row workspace-filter-bar">
          <div class="workspace-filter-controls">
            <div class="btn-group">
              <button class="btn btn-sm" :class="responseUsageFilter==='used'?'btn-primary':'btn-secondary'" @click="$emit('update:responseUsageFilter', responseUsageFilter==='used'?'':'used')">{{t('responses.filterUsed')}}</button>
              <button class="btn btn-sm" :class="responseUsageFilter==='unused'?'btn-primary':'btn-secondary'" @click="$emit('update:responseUsageFilter', responseUsageFilter==='unused'?'':'unused')">{{t('responses.filterUnused')}}</button>
            </div>
            <div class="filter-divider"></div>
            <div class="btn-group">
              <button class="btn btn-sm" :class="responseContentTypeFilter==='GENERAL'?'btn-primary':'btn-secondary'" @click="$emit('update:responseContentTypeFilter', responseContentTypeFilter==='GENERAL'?'':'GENERAL')">{{t('responses.filterGeneral')}}</button>
              <button class="btn btn-sm" :class="responseContentTypeFilter==='SSE'?'btn-primary':'btn-secondary'" @click="$emit('update:responseContentTypeFilter', responseContentTypeFilter==='SSE'?'':'SSE')">SSE</button>
            </div>
            <div class="filter-divider"></div>
            <workspace-search-field
              input-id="responseSearch"
              :model-value="responseFilter"
              :placeholder="t('responses.searchPlaceholder')"
              :aria-label="t('responses.searchPlaceholder')"
              :clear-label="t('responses.clearSearch')"
              @update:model-value="$emit('update:responseFilter', $event)"
            ></workspace-search-field>
          </div>
        </div>
      </div>
      <div v-if="responseFilterChips.length" class="filter-chips">
        <span class="filter-chip" v-for="c in responseFilterChips" :key="c.key">{{c.label}} <button class="chip-remove" @click="$emit('remove-response-chip', c.key)"><i class="bi bi-x"></i></button></span>
        <button class="chip-clear" @click="$emit('clear-response-filters')">{{t('responses.clearAll')}}</button>
      </div>
      <div class="card card-table workspace-table-card">
        <div class="card-table-body">
        <div v-if="loading.responses && !responseSummary.length">
          <div v-for="i in 6" :key="'sk-resp-'+i" class="sk-row">
            <span class="sk sk-badge" style="width:50px"></span>
            <span class="sk sk-text" style="width:30%;min-width:80px"></span>
            <span class="sk sk-badge" style="width:40px"></span>
            <span class="sk sk-text-sm" style="width:50px"></span>
            <span class="sk sk-badge" style="width:35px"></span>
            <span class="sk sk-text-sm" style="width:70px"></span>
            <span class="sk sk-text-sm" style="width:70px"></span>
            <span style="margin-left:auto;display:flex;gap:4px"><span class="sk sk-btn"></span><span class="sk sk-btn"></span></span>
          </div>
        </div>
        <table v-if="pagedResponseSummary.length" class="table-fixed workspace-table">
          <thead><tr>
            <th v-if="batchSelectResponseMode" style="width:40px"><input type="checkbox" @change="$emit('toggle-select-all-responses', $event)" :checked="selectedResponses.length===pagedResponseSummary.length && pagedResponseSummary.length>0" :aria-label="t('responses.selectAll')"></th>
            <th class="col-id" style="cursor:pointer" @click="$emit('toggle-response-sort', 'id')">{{t('responses.thId')}} <i class="bi" :class="responseSortIcon('id')"></i></th>
            <th>{{t('responses.thDescription')}}</th>
            <th style="width:88px" class="col-hide-md">{{t('responses.thType')}}</th>
            <th style="width:64px;cursor:pointer" class="col-hide-md" @click="$emit('toggle-response-sort', 'bodySize')">{{t('responses.thSize')}} <i class="bi" :class="responseSortIcon('bodySize')"></i></th>
            <th style="width:64px;cursor:pointer" class="col-hide-sm" @click="$emit('toggle-response-sort', 'usageCount')">{{t('responses.thUsageCount')}} <i class="bi" :class="responseSortIcon('usageCount')"></i></th>
            <th class="col-datetime col-hide-md" style="cursor:pointer" @click="$emit('toggle-response-sort', 'createdAt')">{{t('responses.thCreatedAt')}} <i class="bi" :class="responseSortIcon('createdAt')"></i></th>
            <th class="col-datetime col-hide-md" style="cursor:pointer" @click="$emit('toggle-response-sort', 'updatedAt')">{{t('responses.thUpdatedAt')}} <i class="bi" :class="responseSortIcon('updatedAt')"></i></th>
            <th class="col-actions col-actions-3">{{t('responses.thActions')}}</th>
          </tr></thead>
          <tbody>
            <template v-for="r in pagedResponseSummary" :key="r.id">
              <tr :class="{'selected-row':batchSelectResponseMode && selectedResponses.includes(r.id), 'unused-row':!r.usageCount, 'row-clickable':!batchSelectResponseMode}" @click="!batchSelectResponseMode && r.usageCount && $emit('toggle-response-rules', r)" @dblclick="!batchSelectResponseMode && isLoggedIn && $emit('open-response-modal', r)" :title="!batchSelectResponseMode ? (r.usageCount ? t('responses.clickExpandDblEdit') : t('responses.dblClickEdit')) : ''">
                <td v-if="batchSelectResponseMode"><input type="checkbox" :value="r.id" :checked="selectedResponses.includes(r.id)" @change="toggleSelection(r.id, $event.target.checked)" :aria-label="t('responses.selectResponse', {id:shortId(r.id)})"></td>
                <td class="col-id"><span class="badge badge-id">{{r.id}}</span></td>
                <td>
                  <div>{{r.description||t('responses.noDescription')}}</div>
                  <div v-if="r.usageCount" class="sub-info" style="margin-top:2px">{{t('responses.usageCount', {count: r.usageCount})}}</div>
                  <div v-else class="sub-info" style="margin-top:2px;color:var(--danger)">
                    {{t('responses.notUsed')}}
                    <span v-if="daysLeft(r.updatedAt, r.extendedAt, status?.responseRetentionDays) != null" class="badge" :class="daysLeft(r.updatedAt, r.extendedAt, status?.responseRetentionDays) <= 7 ? 'badge-warning' : 'badge-muted'" style="margin-left:4px">{{t('responses.orphanDaysLeft', {days: daysLeft(r.updatedAt, r.extendedAt, status?.responseRetentionDays)})}}</span>
                  </div>
                </td>
                <td class="col-hide-md">
                  <span v-if="r.contentType==='SSE'" class="badge badge-sse">SSE</span>
                  <span v-else class="badge badge-muted">{{t('responses.typeGeneral')}}</span>
                </td>
                <td class="col-hide-md"><span class="sub-info">{{fmtSize(r.bodySize)}}</span></td>
                <td class="col-hide-sm">
                  <span v-if="r.usageCount" class="badge badge-success">{{r.usageCount}}</span>
                  <span v-else class="badge badge-secondary">0</span>
                </td>
                <td class="col-datetime col-hide-md"><span class="sub-info" :title="fmtTime(r.createdAt,false)">{{fmtTime(r.createdAt)}}</span></td>
                <td class="col-datetime col-hide-md"><span class="sub-info" :title="fmtTime(r.updatedAt,false)">{{fmtTime(r.updatedAt)}}</span></td>
                <td class="col-actions col-actions-3">
                  <div style="display:flex;gap:0.25rem">
                    <button v-if="r.usageCount" class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('toggle-response-rules', r)" :title="t('responses.viewLinkedRules', {count: r.usageCount})" :aria-label="t('responses.viewLinkedRules', {count: r.usageCount})"><i class="bi" :class="r.expanded?'bi-chevron-up':'bi-chevron-down'"></i></button>
                    <button v-if="!r.usageCount && daysLeft(r.updatedAt, r.extendedAt, status?.responseRetentionDays) != null" class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('extend-response', r.id)" :title="t('responses.clickExtend')" :aria-label="t('responses.clickExtend')" :disabled="!isLoggedIn"><i class="bi bi-clock-history"></i></button>
                    <button class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('open-response-modal', r)" :title="t('responses.edit')" :aria-label="t('responses.edit')" :disabled="!isLoggedIn"><i class="bi bi-pencil"></i></button>
                    <button class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('delete-response', r.id, r.usageCount)" :title="t('responses.delete')" :aria-label="t('responses.delete')" :disabled="!isLoggedIn"><i class="bi bi-trash"></i></button>
                  </div>
                </td>
              </tr>
              <tr v-if="r.expanded && r.rules" class="rule-preview-row">
                <td :colspan="batchSelectResponseMode?9:8" style="padding:0">
                  <div class="rule-preview-content">
                    <div v-for="rule in r.rules" :key="rule.id" class="linked-rule" @click="$emit('go-to-rule', rule.id)">
                    <span class="badge badge-id" :title="rule.id">{{shortId(rule.id)}}</span>
                    <span class="badge" :class="'badge-'+rule.protocol.toLowerCase()">{{rule.protocol}}</span>
                    <span v-if="rule.sseEnabled" class="badge badge-sse">SSE</span>
                    <code style="font-weight:500">{{rule.matchKey}}</code>
                    <span class="sub-info">{{rule.description}}</span>
                  </div>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
        <div v-if="!pagedResponseSummary.length && !loading.responses" class="empty workspace-empty">
          <i class="bi bi-file-earmark-text"></i>
          <div v-if="responseFilter || responseUsageFilter || responseContentTypeFilter">
            {{t('responses.emptyFilterResult')}}
            <div style="margin-top:0.5rem">
              <button class="btn btn-sm btn-secondary" @click="$emit('clear-response-filters')">{{t('responses.clearFilter')}}</button>
            </div>
          </div>
          <div v-else>
            <div class="workspace-empty-title">{{t('responses.emptyNoResponses')}}</div>
            <div class="workspace-empty-hint">{{t('responses.emptyHint')}}</div>
          </div>
        </div>
        </div>
        <workspace-pagination
          :page="responsePage" :total-pages="responseTotalPages" :page-size="responsePageSize"
          :pagination-label="t('stats.pagination')"
          :page-status-label="t('stats.pageStatus', {page:responsePage, total:responseTotalPages})"
          :page-size-label="t('stats.pageSize')"
          :first-page-label="t('stats.firstPage')" :previous-page-label="t('stats.previousPage')"
          :next-page-label="t('stats.nextPage')" :last-page-label="t('stats.lastPage')"
          @update:page="$emit('update:responsePage', $event)"
          @update:page-size="$emit('update:responsePageSize', $event)"
        >
          <template #summary>
            <span class="sub-info">{{t('responses.totalCount', {count: responseSummary.length})}}</span>
            <button v-if="responseFilter || responseUsageFilter || responseContentTypeFilter" type="button" class="workspace-filter-reset" :title="t('responses.clickClearFilter')" @click="$emit('clear-response-filters')"><i class="bi bi-funnel-fill" aria-hidden="true"></i> {{t('responses.filtering')}}</button>
          </template>
        </workspace-pagination>
      </div>
    </div>
  `
};
