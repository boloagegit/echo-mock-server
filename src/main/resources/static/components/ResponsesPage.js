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
    responseTotalElements: Number,
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
    'go-to-rule', 'extend-response', 'clip-copy',
  ],
  inject: ['t'],
  data() {
    return { responseViewportWidth: window.innerWidth };
  },
  computed: {
    responseDetailColspan() {
      const visibleColumns = this.responseViewportWidth <= 768 ? 2 : this.responseViewportWidth <= 1024 ? 3 : 7;
      return visibleColumns + (this.batchSelectResponseMode ? 1 : 0);
    },
    hasResponseFilters() {
      return Boolean(this.responseFilter || this.responseUsageFilter || this.responseContentTypeFilter);
    },
    usageFilterOptions() {
      return [
        { value: 'used', label: this.t('responses.filterUsed') },
        { value: 'unused', label: this.t('responses.filterUnused') },
      ];
    },
    contentTypeFilterOptions() {
      return [
        { value: 'GENERAL', label: this.t('responses.filterGeneral') },
        { value: 'SSE', label: 'SSE' },
      ];
    },
    dataMenuItems() {
      return [
        { key: 'export', label: this.t('responses.exportResponses'), icon: 'bi-box-arrow-up' },
        { key: 'import', label: this.t('responses.importResponses'), icon: 'bi-box-arrow-in-down' },
      ];
    },
  },
  mounted() {
    window.addEventListener('resize', this.syncResponseViewportWidth, { passive: true });
  },
  beforeUnmount() {
    window.removeEventListener('resize', this.syncResponseViewportWidth);
  },
  methods: {
    shortId, fmtTime, fmtSize, daysLeft,
    syncResponseViewportWidth() {
      this.responseViewportWidth = window.innerWidth;
    },
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
    handleDataMenuAction(action) {
      if (action === 'export') { this.$emit('export-responses'); }
      if (action === 'import') { this.$emit('trigger-response-import'); }
    },
  },
  template: /* html */`
    <div class="page workspace-page responses-workspace" :class="{active:true}">
      <div class="page-header">
        <div class="page-heading">
          <h1 class="page-title">{{t('responses.title')}}</h1>
          <span class="page-count">{{responseTotalElements}}</span>
        </div>
        <div class="page-actions">
          <ui-button class="btn btn-secondary" @click="$emit('load-responses', true)" :disabled="loading.responses"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.responses}"></i> {{t('responses.refresh')}}</ui-button>
          <ui-button class="btn btn-secondary" @click="$emit('update:batchSelectResponseMode', !batchSelectResponseMode); $emit('update:selectedResponses', [])" :disabled="!isLoggedIn" :class="{'active':batchSelectResponseMode}"><i class="bi bi-check2-square"></i> {{t('responses.batchSelect')}}</ui-button>
          <ui-button class="btn btn-danger" @click="$emit('delete-selected-responses')" v-if="batchSelectResponseMode && selectedResponses.length"><i class="bi bi-trash"></i> {{t('responses.deleteCount', {count: selectedResponses.length})}}</ui-button>
          <ui-button class="btn btn-primary" @click="$emit('open-response-modal', null)" :disabled="!isLoggedIn"><i class="bi bi-plus-lg"></i> {{t('responses.addResponse')}}</ui-button>
          <ui-dropdown-menu v-if="isLoggedIn && bulkImportExportEnabled" class="resp-data-dropdown-wrapper"
            :open="showResponseDataDropdown" :items="dataMenuItems" :trigger-label="t('responses.exportImport')"
            @toggle="$emit('toggle-response-data-dropdown')" @close="$emit('toggle-response-data-dropdown')"
            @select="handleDataMenuAction"></ui-dropdown-menu>
          <input id="responseImportInput2" type="file" accept=".json" @change="$emit('import-responses', $event)" hidden>
        </div>
      </div>
      <div class="page-context-note">
        <i class="bi bi-info-circle"></i> {{t('responses.sharedInfo')}}
      </div>
      <div class="card workspace-filter-card">
        <div class="card-body filter-row workspace-filter-bar">
          <div class="workspace-filter-controls">
            <ui-toggle-group :model-value="responseUsageFilter" :options="usageFilterOptions"
              :aria-label="t('responses.usageFilter')"
              @update:model-value="$emit('update:responseUsageFilter', $event)"></ui-toggle-group>
            <ui-toggle-group :model-value="responseContentTypeFilter" :options="contentTypeFilterOptions"
              :aria-label="t('responses.contentTypeFilter')"
              @update:model-value="$emit('update:responseContentTypeFilter', $event)"></ui-toggle-group>
            <workspace-search-field
              input-id="responseSearch"
              :model-value="responseFilter"
              :placeholder="t('responses.searchPlaceholder')"
              :aria-label="t('responses.searchPlaceholder')"
              :clear-label="t('responses.clearSearch')"
              :submit-mode="true"
              :submit-label="t('common.searchAction')"
              @search="$emit('update:responseFilter', $event)"
            ></workspace-search-field>
          </div>
        </div>
      </div>
      <ui-filter-chip-list :items="responseFilterChips" :aria-label="t('common.activeFilters')"
        :clear-label="t('responses.clearAll')"
        @remove="$emit('remove-response-chip', $event)"
        @clear="$emit('clear-response-filters')"></ui-filter-chip-list>
      <div class="card card-table workspace-table-card">
        <div class="card-table-body">
        <div v-if="loading.responses && !responseSummary.length" role="status" :aria-label="t('common.loading')">
          <div v-for="i in 6" :key="'sk-resp-'+i" class="sk-row">
            <span class="sk sk-badge sk-w-50"></span>
            <span class="sk sk-text sk-w-30p sk-min-w-80"></span>
            <span class="sk sk-badge sk-w-40"></span>
            <span class="sk sk-text-sm sk-w-50"></span>
            <span class="sk sk-badge sk-w-35"></span>
            <span class="sk sk-text-sm sk-w-70"></span>
            <span class="sk sk-text-sm sk-w-70"></span>
            <span class="sk-actions"><span class="sk sk-btn"></span><span class="sk sk-btn"></span></span>
          </div>
        </div>
        <table v-if="pagedResponseSummary.length" class="table-fixed workspace-table workspace-primary-aligned-table response-list-table">
          <thead><tr>
            <th v-if="batchSelectResponseMode" class="table-select-column"><input type="checkbox" @change="$emit('toggle-select-all-responses', $event)" :checked="selectedResponses.length===pagedResponseSummary.length && pagedResponseSummary.length>0" :aria-label="t('responses.selectAll')"></th>
            <th class="list-identity-heading" :aria-sort="responseSort.field==='id'?(responseSort.asc?'ascending':'descending'):'none'"><span>{{t('responses.thDescription')}}</span><ui-table-sort-header :label="t('responses.thId')" :active="responseSort.field==='id'" :ascending="responseSort.asc" @toggle="$emit('toggle-response-sort', 'id')"></ui-table-sort-header></th>
            <th class="table-type-column col-hide-md">{{t('responses.thType')}}</th>
            <th class="table-value-column col-hide-md" :aria-sort="responseSort.field==='bodySize'?(responseSort.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('responses.thSize')" :active="responseSort.field==='bodySize'" :ascending="responseSort.asc" @toggle="$emit('toggle-response-sort', 'bodySize')"></ui-table-sort-header></th>
            <th class="response-usage-column col-hide-sm" :aria-sort="responseSort.field==='usageCount'?(responseSort.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('responses.thUsageCount')" :active="responseSort.field==='usageCount'" :ascending="responseSort.asc" @toggle="$emit('toggle-response-sort', 'usageCount')"></ui-table-sort-header></th>
            <th class="col-datetime col-hide-md" :aria-sort="responseSort.field==='createdAt'?(responseSort.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('responses.thCreatedAt')" :active="responseSort.field==='createdAt'" :ascending="responseSort.asc" @toggle="$emit('toggle-response-sort', 'createdAt')"></ui-table-sort-header></th>
            <th class="col-datetime col-hide-md" :aria-sort="responseSort.field==='updatedAt'?(responseSort.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('responses.thUpdatedAt')" :active="responseSort.field==='updatedAt'" :ascending="responseSort.asc" @toggle="$emit('toggle-response-sort', 'updatedAt')"></ui-table-sort-header></th>
            <th class="col-actions col-actions-3">{{t('responses.thActions')}}</th>
          </tr></thead>
          <tbody>
            <template v-for="r in pagedResponseSummary" :key="r.id">
              <tr :class="{'selected-row':batchSelectResponseMode && selectedResponses.includes(r.id), 'unused-row':!r.usageCount, 'row-clickable':!batchSelectResponseMode}" @click="!batchSelectResponseMode && r.usageCount && $emit('toggle-response-rules', r)" @dblclick="!batchSelectResponseMode && isLoggedIn && $emit('open-response-modal', r)" :title="!batchSelectResponseMode ? (r.usageCount ? t('responses.clickExpandDblEdit') : t('responses.dblClickEdit')) : ''">
                <td v-if="batchSelectResponseMode"><div class="workspace-row-primary"><input type="checkbox" :value="r.id" :checked="selectedResponses.includes(r.id)" @change="toggleSelection(r.id, $event.target.checked)" :aria-label="t('responses.selectResponse', {id:shortId(r.id)})"></div></td>
                <td class="list-identity-cell">
                  <div class="list-record-name workspace-row-primary">{{r.description||t('responses.noDescription')}}</div>
                  <div class="list-identity-secondary response-identity-meta">
                    <button type="button" class="list-id-copy" @click.stop="$emit('clip-copy',String(r.id))" @dblclick.stop :aria-label="t('rules.copyId')+' '+r.id" :title="String(r.id)">#{{r.id}}<i class="bi bi-copy" aria-hidden="true"></i></button>
                    <span class="response-compact-details">· {{r.contentType==='SSE'?'SSE':t('responses.typeGeneral')}} · {{fmtSize(r.bodySize)}}</span>
                    <button v-if="r.usageCount" type="button" class="response-mobile-state response-mobile-reference-control"
                      @click.stop="$emit('toggle-response-rules', r)" @dblclick.stop
                      :title="t('responses.viewLinkedRules', {count:r.usageCount})"
                      :aria-label="t('responses.viewLinkedRules', {count:r.usageCount})"
                      :aria-expanded="!!r.expanded" :aria-controls="r.expanded?'response-rules-'+r.id:undefined">
                      <span aria-hidden="true">·</span><span>{{t('responses.referenceRules')}}</span><strong class="tabular-nums">{{r.usageCount}}</strong>
                      <i class="bi" :class="r.expanded?'bi-chevron-up':'bi-chevron-down'" aria-hidden="true"></i>
                    </button>
                    <span v-else class="response-mobile-state">· {{t('responses.notUsed')}}</span>
                    <span class="response-mobile-updated">· {{fmtTime(r.updatedAt)}}</span>
                  </div>
                </td>
                <td class="col-hide-md"><div class="workspace-row-primary">
                  <ui-badge v-if="r.contentType==='SSE'" class="badge badge-sse">SSE</ui-badge>
                  <span v-else class="response-type-text">{{t('responses.typeGeneral')}}</span>
                </div>
                </td>
                <td class="col-hide-md"><span class="sub-info workspace-row-primary">{{fmtSize(r.bodySize)}}</span></td>
                <td class="response-usage-column col-hide-sm">
                  <button v-if="r.usageCount" type="button" class="response-reference-control" @click.stop="$emit('toggle-response-rules', r)" @dblclick.stop
                    :title="t('responses.viewLinkedRules', {count:r.usageCount})" :aria-label="t('responses.viewLinkedRules', {count:r.usageCount})"
                    :aria-expanded="!!r.expanded" :aria-controls="r.expanded?'response-rules-'+r.id:undefined">
                    <span>{{t('responses.referenceRules')}}</span><strong class="tabular-nums">{{r.usageCount}}</strong><i class="bi" :class="r.expanded?'bi-chevron-up':'bi-chevron-down'" aria-hidden="true"></i>
                  </button>
                  <span v-else class="response-usage-state is-unused"><span class="workspace-row-primary">{{t('responses.notUsed')}}</span><small v-if="daysLeft(r.updatedAt, r.extendedAt, status?.responseRetentionDays) != null">{{t('responses.orphanDaysLeft', {days: daysLeft(r.updatedAt, r.extendedAt, status?.responseRetentionDays)})}}</small></span>
                </td>
                <td class="col-datetime col-hide-md"><span class="sub-info workspace-row-primary" :title="fmtTime(r.createdAt,false)">{{fmtTime(r.createdAt)}}</span></td>
                <td class="col-datetime col-hide-md"><span class="sub-info workspace-row-primary" :title="fmtTime(r.updatedAt,false)">{{fmtTime(r.updatedAt)}}</span></td>
                <td class="col-actions col-actions-3">
                  <div class="table-row-actions workspace-row-primary workspace-row-primary-end">
                    <ui-button class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('open-response-modal', r)" :title="t('responses.edit')" :aria-label="t('responses.edit')" :disabled="!isLoggedIn"><i class="bi bi-pencil"></i></ui-button>
                    <ui-button v-if="!r.usageCount && daysLeft(r.updatedAt, r.extendedAt, status?.responseRetentionDays) != null" class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('extend-response', r.id)" :title="t('responses.clickExtend')" :aria-label="t('responses.clickExtend')" :disabled="!isLoggedIn"><i class="bi bi-clock-history"></i></ui-button>
                    <span v-else class="response-action-spacer" aria-hidden="true"></span>
                    <ui-button variant="danger" size="compact" icon-only @click.stop="$emit('delete-response', r.id, r.usageCount)" :title="t('responses.delete')" :aria-label="t('responses.delete')" :disabled="!isLoggedIn"><i class="bi bi-trash"></i></ui-button>
                  </div>
                </td>
              </tr>
              <Transition name="ui-detail-row-motion">
              <tr v-if="r.expanded && r.rules" :id="'response-rules-'+r.id" class="rule-preview-row">
                <td :colspan="responseDetailColspan" class="detail-row-cell">
                  <div class="rule-preview-content">
                    <div class="response-linked-rules-header">
                      <strong>{{t('responses.linkedRulesTitle')}}</strong><span class="tabular-nums">{{r.rules.length}}</span>
                    </div>
                    <div v-if="!r.rules.length" class="rule-preview-state">
                      <i class="bi bi-link-45deg" aria-hidden="true"></i><span>{{t('responses.noVisibleLinkedRules')}}</span>
                    </div>
                    <div v-else class="response-linked-rules-list">
                      <a v-for="rule in r.rules" :key="rule.id" href="#rules" class="linked-rule list-linked-record" :aria-label="[rule.protocol, rule.method, rule.matchKey, rule.description].filter(Boolean).join(' ')" @click.stop.prevent="$emit('go-to-rule', rule.id)">
                        <span class="linked-rule-primary"><span class="rule-protocol">{{rule.protocol}}</span><ui-badge v-if="rule.method" class="badge badge-method">{{rule.method}}</ui-badge><code :title="rule.matchKey">{{rule.matchKey}}</code><ui-badge v-if="rule.sseEnabled" class="badge badge-sse">SSE</ui-badge></span>
                        <span class="linked-rule-secondary"><span v-if="rule.description" class="sub-info" :title="rule.description">{{rule.description}}</span><span class="list-inline-id" :title="rule.id">{{shortId(rule.id)}}</span></span>
                        <i class="bi bi-arrow-right linked-rule-open-icon" aria-hidden="true"></i>
                      </a>
                    </div>
                  </div>
                </td>
              </tr>
              </Transition>
            </template>
          </tbody>
        </table>
        <ui-load-state v-if="!pagedResponseSummary.length && !loading.responses" kind="empty" :has-action="hasResponseFilters"
          :icon="hasResponseFilters?'bi-search':'bi-file-earmark-text'"
          :title="hasResponseFilters?t('responses.emptyFilterResult'):t('responses.emptyNoResponses')"
          :hint="hasResponseFilters?'':t('responses.emptyHint')">
          <template #action><ui-button class="btn btn-sm btn-secondary" @click="$emit('clear-response-filters')">{{t('responses.clearFilter')}}</ui-button></template>
        </ui-load-state>
        </div>
        <workspace-pagination
          :page="responsePage" :total-pages="responseTotalPages" :page-size="responsePageSize"
          :pagination-label="t('responses.pagination')"
          :page-status-label="t('stats.pageStatus', {page:responsePage, total:responseTotalPages})"
          :page-size-label="t('stats.pageSize')"
          :first-page-label="t('stats.firstPage')" :previous-page-label="t('stats.previousPage')"
          :next-page-label="t('stats.nextPage')" :last-page-label="t('stats.lastPage')"
          :scroll-hint-label="t('common.scrollForMore')"
          :scroll-region-label="t('common.scrollableResponsesTable')"
          @update:page="$emit('update:responsePage', $event)"
          @update:page-size="$emit('update:responsePageSize', $event)"
        >
          <template #summary>
            <span class="sub-info">{{t('responses.totalCount', {count: responseTotalElements})}}</span>
            <ui-button v-if="responseFilter || responseUsageFilter || responseContentTypeFilter" type="button" variant="quiet" size="compact" class="workspace-filter-reset" :title="t('responses.clickClearFilter')" @click="$emit('clear-response-filters')"><i class="bi bi-funnel-fill" aria-hidden="true"></i> {{t('responses.filtering')}}</ui-button>
          </template>
        </workspace-pagination>
      </div>
    </div>
  `
};
