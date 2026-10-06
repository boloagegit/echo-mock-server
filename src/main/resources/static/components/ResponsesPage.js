/**
 * ResponsesPage - 回應管理頁面
 *
 * 與規則頁使用同一套列表語言：點選一列開啟右側詳情抽屜（引用規則、內容、保留期限），
 * 雙擊或編輯鈕開啟編輯器，其餘操作收在「⋯」。搜尋邊打邊篩。
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
    responseDetailId: [Number, String],
    responseDetailCache: Object,
    responseDetailLoading: Boolean,
    responseDetailError: Boolean,
  },
  emits: [
    'load-responses', 'open-response-modal', 'delete-response',
    'update:responseFilter', 'update:batchSelectResponseMode',
    'update:selectedResponses', 'update:responseUsageFilter',
    'update:responseContentTypeFilter',
    'update:responsePage', 'update:responsePageSize',
    'toggle-response-sort', 'toggle-select-all-responses',
    'export-responses', 'import-responses', 'delete-selected-responses',
    'toggle-response-data-dropdown',
    'trigger-response-import',
    'remove-response-chip', 'clear-response-filters',
    'open-response-detail', 'close-response-detail', 'load-response-detail',
    'go-to-rule', 'extend-response', 'clip-copy',
  ],
  inject: ['t'],
  computed: {
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
    detailIndex() {
      return this.pagedResponseSummary.findIndex(r => String(r.id) === String(this.responseDetailId));
    },
    detailResponse() {
      if (this.responseDetailId == null) return null;
      return this.pagedResponseSummary[this.detailIndex] || null;
    },
  },
  methods: {
    shortId, fmtTime, fmtSize, daysLeft,
    sortState(field) {
      if (this.responseSort.field !== field) return 'none';
      return this.responseSort.asc ? 'ascending' : 'descending';
    },
    retention(r) {
      if (r.usageCount) return null;
      return daysLeft(r.updatedAt, r.extendedAt, this.status?.responseRetentionDays);
    },
    toggleSelection(id) {
      const next = this.selectedResponses.includes(id)
        ? this.selectedResponses.filter(item => item !== id)
        : [...this.selectedResponses, id];
      this.$emit('update:selectedResponses', next);
    },
    handleDataMenuAction(action) {
      if (action === 'export') { this.$emit('export-responses'); }
      if (action === 'import') { this.$emit('trigger-response-import'); }
    },
    menuItems(r) {
      const t = this.t;
      const items = [{ key: 'copy-id', label: t('rules.copyId'), icon: 'bi-copy' }];
      if (this.retention(r) != null && this.isLoggedIn) {
        items.push({ key: 'extend', label: t('responses.clickExtend'), icon: 'bi-calendar-plus' });
      }
      items.push({ key: 'delete', label: t('responses.delete'), icon: 'bi-trash', danger: true, dividerBefore: true, disabled: !this.isLoggedIn });
      return items;
    },
    handleMenu(action, r) {
      if (action === 'copy-id') this.$emit('clip-copy', String(r.id));
      else if (action === 'extend') this.$emit('extend-response', r.id);
      else if (action === 'delete') this.$emit('delete-response', r.id, r.usageCount);
    },
    selectResponse(r) {
      if (this.batchSelectResponseMode) { this.toggleSelection(r.id); return; }
      if (String(this.responseDetailId) === String(r.id)) { this.$emit('close-response-detail'); return; }
      this.$emit('open-response-detail', r);
    },
    onRowKeydown(event, r) {
      if (event.target !== event.currentTarget) return;
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); this.selectResponse(r); }
    },
    stepDetail(direction) {
      const next = this.pagedResponseSummary[this.detailIndex + direction];
      if (next) {
        this.$emit('open-response-detail', next);
        this.$nextTick(() => document.querySelector('.responses-workspace [data-detail-row].is-selected')?.scrollIntoView({ block: 'nearest' }));
      }
    },
    toggleBatch() {
      this.$emit('update:batchSelectResponseMode', !this.batchSelectResponseMode);
      this.$emit('update:selectedResponses', []);
    },
  },
  template: /* html */`
    <div class="page workspace-page responses-workspace" :class="{active:true}">
      <div class="page-header">
        <div class="page-heading">
          <h1 class="page-title">{{t('responses.title')}}</h1>
          <span class="page-count">{{responseTotalElements}}</span>
          <button type="button" class="help-tooltip tooltip-align-start" :data-tooltip="t('responses.sharedInfo')"
            :aria-label="t('responses.sharedInfoLabel')" @keydown.esc="$event.currentTarget.blur()">
            <i class="bi bi-question-circle" aria-hidden="true"></i>
          </button>
        </div>
        <div class="page-actions">
          <ui-button variant="secondary" @click="$emit('load-responses', true)" :disabled="loading.responses"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.responses}" aria-hidden="true"></i>{{t('responses.refresh')}}</ui-button>
          <ui-button variant="secondary" :class="{'is-toggled':batchSelectResponseMode}" :aria-pressed="batchSelectResponseMode ? 'true' : 'false'" @click="toggleBatch" :disabled="!isLoggedIn" :title="!isLoggedIn?t('rules.loginRequired'):t('responses.batchSelect')"><i class="bi bi-check2-square" aria-hidden="true"></i>{{t('responses.batchSelect')}}</ui-button>
          <ui-button variant="primary" @click="$emit('open-response-modal', null)" :disabled="!isLoggedIn" :title="!isLoggedIn?t('rules.loginRequired'):t('responses.addResponse')"><i class="bi bi-plus-lg" aria-hidden="true"></i>{{t('responses.addResponse')}}</ui-button>
          <ui-dropdown-menu v-if="isLoggedIn && bulkImportExportEnabled" class="resp-data-dropdown-wrapper"
            :open="showResponseDataDropdown" :items="dataMenuItems" :trigger-label="t('responses.exportImport')"
            @toggle="$emit('toggle-response-data-dropdown')" @close="$emit('toggle-response-data-dropdown')"
            @select="handleDataMenuAction"></ui-dropdown-menu>
          <input id="responseImportInput2" type="file" accept=".json" @change="$emit('import-responses', $event)" hidden>
        </div>
      </div>

      <div class="list-toolbar">
        <workspace-search-field shortcut="/"
          input-id="responseSearch"
          :model-value="responseFilter"
          :placeholder="t('responses.searchPlaceholder')"
          :aria-label="t('responses.searchPlaceholder')"
          :clear-label="t('responses.clearSearch')"
          :submit-mode="true"
          @search="$emit('update:responseFilter', $event)"
        ></workspace-search-field>
        <ui-toggle-group :model-value="responseUsageFilter" :options="usageFilterOptions" :aria-label="t('responses.usageFilter')" @update:model-value="$emit('update:responseUsageFilter', $event)"></ui-toggle-group>
        <ui-toggle-group :model-value="responseContentTypeFilter" :options="contentTypeFilterOptions" :aria-label="t('responses.contentTypeFilter')" @update:model-value="$emit('update:responseContentTypeFilter', $event)"></ui-toggle-group>
      </div>
      <ui-filter-chip-list :items="responseFilterChips" :aria-label="t('common.activeFilters')"
        :clear-label="t('responses.clearAll')"
        @remove="$emit('remove-response-chip', $event)"
        @clear="$emit('clear-response-filters')"></ui-filter-chip-list>

      <div v-if="batchSelectResponseMode" class="batch-bar" role="region" :aria-label="t('responses.batchSelect')">
        <span class="batch-bar__count">{{t('responses.selectedCount', {count: selectedResponses.length})}}</span>
        <ui-button variant="danger" size="compact" :disabled="!selectedResponses.length" @click="$emit('delete-selected-responses')"><i class="bi bi-trash" aria-hidden="true"></i>{{t('responses.delete')}}</ui-button>
        <ui-button variant="quiet" size="compact" class="batch-bar__done" @click="toggleBatch">{{t('rules.batchDone')}}</ui-button>
      </div>

      <div class="card card-table list-card">
        <div class="card-table-body">
          <div v-if="loading.responses && !responseSummary.length" class="list-skeleton" role="status" :aria-label="t('common.loading')">
            <div v-for="i in 8" :key="'sk-resp-'+i" class="list-skeleton__row"><span class="sk sk-w-40p"></span><span class="sk sk-w-15p"></span><span class="sk sk-w-15p"></span></div>
          </div>
          <table v-else-if="pagedResponseSummary.length" class="data-table response-table">
            <thead><tr>
              <th v-if="batchSelectResponseMode" class="col-select"><input type="checkbox" @change="$emit('toggle-select-all-responses', $event)" :checked="selectedResponses.length===pagedResponseSummary.length && pagedResponseSummary.length>0" :aria-label="t('responses.selectAll')"></th>
              <th class="col-name" :aria-sort="sortState('id')"><ui-table-sort-header :label="t('responses.thDescription')" :active="responseSort.field==='id'" :ascending="responseSort.asc" @toggle="$emit('toggle-response-sort', 'id')"></ui-table-sort-header></th>
              <th class="col-type">{{t('responses.thType')}}</th>
              <th class="col-size cell-end" :aria-sort="sortState('bodySize')"><ui-table-sort-header :label="t('responses.thSize')" :active="responseSort.field==='bodySize'" :ascending="responseSort.asc" @toggle="$emit('toggle-response-sort', 'bodySize')"></ui-table-sort-header></th>
              <th class="col-usage" :aria-sort="sortState('usageCount')"><ui-table-sort-header :label="t('responses.thUsageCount')" :active="responseSort.field==='usageCount'" :ascending="responseSort.asc" @toggle="$emit('toggle-response-sort', 'usageCount')"></ui-table-sort-header></th>
              <th class="col-created" :aria-sort="sortState('createdAt')"><ui-table-sort-header :label="t('responses.thCreatedAt')" :active="responseSort.field==='createdAt'" :ascending="responseSort.asc" @toggle="$emit('toggle-response-sort', 'createdAt')"></ui-table-sort-header></th>
              <th class="col-updated" :aria-sort="sortState('updatedAt')"><ui-table-sort-header :label="t('responses.thUpdatedAt')" :active="responseSort.field==='updatedAt'" :ascending="responseSort.asc" @toggle="$emit('toggle-response-sort', 'updatedAt')"></ui-table-sort-header></th>
              <th class="col-actions"><span class="visually-hidden">{{t('responses.thActions')}}</span></th>
            </tr></thead>
            <tbody>
              <tr v-for="r in pagedResponseSummary" :key="r.id" data-detail-row tabindex="0"
                :class="{'is-selected': String(responseDetailId)===String(r.id), 'is-checked': batchSelectResponseMode && selectedResponses.includes(r.id)}"
                :aria-selected="String(responseDetailId)===String(r.id) ? 'true' : 'false'"
                @click="selectResponse(r)" @dblclick="!batchSelectResponseMode && isLoggedIn && $emit('open-response-modal', r)" @keydown="onRowKeydown($event, r)">
                <td v-if="batchSelectResponseMode" class="col-select" @click.stop>
                  <input type="checkbox" :checked="selectedResponses.includes(r.id)" @change="toggleSelection(r.id)" :aria-label="t('responses.selectResponse', {id:shortId(String(r.id))})">
                </td>
                <td class="col-name">
                  <span class="record-name">
                    <span class="record-name__title" :class="{'is-empty': !r.description}">{{r.description||t('responses.noDescription')}}</span>
                    <span class="record-name__id">#{{r.id}}</span>
                  </span>
                </td>
                <td class="col-type"><ui-badge v-if="r.contentType==='SSE'" tone="neutral">SSE</ui-badge><span v-else class="cell-subtle">{{t('responses.typeGeneral')}}</span></td>
                <td class="col-size cell-end cell-mono cell-subtle">{{fmtSize(r.bodySize)}}</td>
                <td class="col-usage">
                  <span v-if="r.usageCount" class="usage-count"><strong>{{r.usageCount}}</strong> {{t('responses.ruleUnit')}}</span>
                  <span v-else class="cell-subtle">{{t('responses.notUsed')}}<ui-badge v-if="retention(r) != null && retention(r) <= 7" tone="warning" class="usage-expiry">{{t('rules.daysLeft', {days: retention(r)})}}</ui-badge></span>
                </td>
                <td class="col-created cell-mono cell-subtle" :title="fmtTime(r.createdAt,false)">{{fmtTime(r.createdAt)}}</td>
                <td class="col-updated cell-mono cell-subtle" :title="fmtTime(r.updatedAt,false)">{{fmtTime(r.updatedAt)}}</td>
                <td class="col-actions" @click.stop @dblclick.stop>
                  <span class="row-actions">
                    <ui-button type="button" variant="quiet" size="compact" icon-only :disabled="!isLoggedIn"
                      :title="t('responses.edit')" :aria-label="t('responses.edit') + ' #' + r.id" @click="$emit('open-response-modal', r)"><i class="bi bi-pencil" aria-hidden="true"></i></ui-button>
                    <ui-row-menu :items="menuItems(r)" :label="t('common.moreActions') + ' #' + r.id" @select="handleMenu($event, r)"></ui-row-menu>
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
          <ui-load-state v-else-if="loading.responsesError" kind="error" icon="bi-cloud-slash" :title="t('responses.loadFailed')" has-action>
            <template #action><ui-button variant="secondary" size="compact" @click="$emit('load-responses', true)"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</ui-button></template>
          </ui-load-state>
          <ui-load-state v-else kind="empty" has-action
            :icon="hasResponseFilters?'bi-search':'bi-chat-square-text'"
            :title="hasResponseFilters?t('responses.emptyFilterResult'):t('responses.emptyNoResponses')"
            :hint="hasResponseFilters?'':t('responses.emptyHint')">
            <template #action>
              <ui-button v-if="hasResponseFilters" variant="secondary" size="compact" @click="$emit('clear-response-filters')">{{t('responses.clearFilter')}}</ui-button>
              <ui-button v-else variant="primary" :disabled="!isLoggedIn" @click="$emit('open-response-modal', null)"><i class="bi bi-plus-lg" aria-hidden="true"></i>{{t('responses.createFirstResponse')}}</ui-button>
            </template>
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
          </template>
        </workspace-pagination>
      </div>

      <response-detail :open="responseDetailId != null" :response="detailResponse" :detail="responseDetailId != null ? responseDetailCache[responseDetailId] : null"
        :loading="responseDetailLoading" :error="responseDetailError" :is-logged-in="isLoggedIn" :status="status"
        :has-prev="detailIndex > 0" :has-next="detailIndex >= 0 && detailIndex < pagedResponseSummary.length - 1"
        @close="$emit('close-response-detail')" @prev="stepDetail(-1)" @next="stepDetail(1)"
        @retry="$emit('load-response-detail', responseDetailId)"
        @edit="$emit('open-response-modal', $event)" @menu="handleMenu" @go-to-rule="$emit('go-to-rule', $event)"
        @clip-copy="$emit('clip-copy', $event)"></response-detail>
    </div>
  `
};
