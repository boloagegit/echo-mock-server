/**
 * RulesPage - 模擬規則頁面
 *
 * 列表／分組兩種檢視共用 RuleTable；點選一列在右側抽屜（RuleDetail）顯示詳情，
 * ↑↓ 可在目前可見的規則間切換。篩選一律使用可清除的切換群組，搜尋邊打邊篩。
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
    ruleDetailId: String,
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
    'open-rule-detail', 'close-rule-detail', 'load-rule-detail',
    'go-to-responses',
    'clip-copy', 'extend-rule',
    'drag-start', 'drag-over', 'drag-leave', 'drop', 'drag-end',
    'toggle-data-dropdown', 'toggle-tag-group', 'toggle-tag-subgroup',
    'show-more-group', 'show-all-group',
  ],
  computed: {
    hasRuleFilters() {
      return Boolean(this.ruleFilter.keyword || this.ruleFilter.protocol || this.ruleFilter.enabled
        || this.ruleFilter.isProtected || this.ruleFilter.mode || this.ruleFilter.expiring);
    },
    protocolFilterOptions() {
      return [
        { value: 'HTTP', label: this.httpLabel },
        { value: 'JMS', label: this.jmsLabel, disabled: !this.jmsEnabled },
      ];
    },
    enabledFilterOptions() {
      return [
        { value: 'true', label: this.t('rules.filterEnabled') },
        { value: 'false', label: this.t('rules.filterDisabled') },
      ];
    },
    protectionFilterOptions() {
      return [
        { value: 'true', label: this.t('rules.filterProtected') },
        { value: 'false', label: this.t('rules.filterUnprotected') },
      ];
    },
    modeFilterOptions() {
      return ['MOCK', 'FORWARD', 'FAULT'].map(value => ({ value, label: this.t('rules.mode_' + value) }));
    },
    expiringFilterOptions() {
      return [{
        value: 'true',
        label: this.t('rules.filterExpiring'),
        icon: 'bi-hourglass-split',
        title: this.t('rules.filterExpiringHint'),
      }];
    },
    viewModeOptions() {
      return [
        { value: 'list', label: this.t('rules.listView'), title: this.t('rules.listView'), icon: 'bi-list', iconOnly: true },
        { value: 'group', label: this.t('rules.groupView'), title: this.t('rules.groupView'), icon: 'bi-collection', iconOnly: true },
      ];
    },
    dataMenuItems() {
      return [
        { key: 'export', label: this.t('rules.exportRules'), icon: 'bi-box-arrow-up' },
        { key: 'import', label: this.t('rules.importRules'), icon: 'bi-box-arrow-in-down' },
      ];
    },
    groupSections() {
      const sections = [{ key: '_untagged', label: this.t('rules.untagged'), count: this.groupCounts['_untagged'] || 0 }];
      for (const [key, values] of Object.entries(this.tagKeys || {})) {
        sections.push({
          key,
          label: key,
          isTag: true,
          count: values.reduce((sum, value) => sum + (this.groupCounts[key + '=' + value] || 0), 0),
          values: values.map(value => ({ id: key + '=' + value, value, count: this.groupCounts[key + '=' + value] || 0 })),
        });
      }
      return sections;
    },
    /** Rules currently visible in the order shown, used for ↑↓ navigation in the drawer. */
    navRules() {
      if (this.ruleViewMode === 'list') return this.pagedRules;
      const visible = [];
      if (this.expandedTagGroups.includes('_untagged')) {
        visible.push(...this.groupRules('_untagged'));
      }
      for (const section of this.groupSections) {
        if (!section.isTag || !this.expandedTagGroups.includes(section.key)) continue;
        for (const sub of section.values) {
          if (this.expandedTagSubgroups.includes(sub.id)) visible.push(...this.subgroupRules(sub.id));
        }
      }
      return visible;
    },
    detailIndex() {
      return this.navRules.findIndex(rule => rule.id === this.ruleDetailId);
    },
    detailRule() {
      if (!this.ruleDetailId) return null;
      return this.navRules[this.detailIndex] || this.rulePreviewCache[this.ruleDetailId] || null;
    },
  },
  methods: {
    setFilter(key, value) {
      this.$emit('update:ruleFilter', { ...this.ruleFilter, [key]: value });
    },
    clearFilters() {
      this.$emit('update:ruleFilter', { protocol: '', enabled: '', isProtected: '', mode: '', expiring: '', keyword: '' });
    },
    handleDataMenuAction(action) {
      if (action === 'export') { this.$emit('export-rules'); }
      if (action === 'import') { this.$emit('show-import'); }
    },
    toggleSelection(id) {
      const next = this.selectedRules.includes(id)
        ? this.selectedRules.filter(item => item !== id)
        : [...this.selectedRules, id];
      this.$emit('update:selectedRules', next);
    },
    groupRules(key) {
      return (this.rulesByTagGroup[key] || []).slice(0, this.getGroupLimit(key));
    },
    subgroupRules(id) {
      return (this.rulesByTag[id] || []).slice(0, this.getGroupLimit(id));
    },
    selectRule(rule) {
      if (this.ruleDetailId === rule.id) { this.$emit('close-rule-detail'); return; }
      this.$emit('open-rule-detail', rule);
    },
    stepDetail(direction) {
      const next = this.navRules[this.detailIndex + direction];
      if (next) {
        this.$emit('open-rule-detail', next);
        this.$nextTick(() => document.querySelector('.rules-workspace [data-detail-row].is-selected')?.scrollIntoView({ block: 'nearest' }));
      }
    },
    handleRowMenu(action, rule) {
      if (action === 'copy') this.$emit('copy-rule', rule);
      else if (action === 'history') this.$emit('show-rule-history', rule);
      else if (action === 'export') this.$emit('export-rule-json', rule.id);
      else if (action === 'response') this.$emit('go-to-responses', rule.responseId);
      else if (action === 'extend') this.$emit('extend-rule', rule.id);
      else if (action === 'delete') this.$emit('delete-rule', rule.id);
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
            <ui-button variant="secondary" @click="$emit('load-rules', true)" :disabled="loading.rules"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.rules}" aria-hidden="true"></i>{{t('rules.refresh')}}</ui-button>
            <ui-button variant="secondary" :class="{'is-toggled':batchSelectMode}" :aria-pressed="batchSelectMode ? 'true' : 'false'" @click="$emit('toggle-batch-select')" :disabled="!isLoggedIn" :title="!isLoggedIn?t('rules.loginRequired'):t('rules.batchSelect')"><i class="bi bi-check2-square" aria-hidden="true"></i>{{t('rules.batchSelect')}}</ui-button>
            <ui-button variant="primary" @click="$emit('open-create')" :disabled="!isLoggedIn" :title="!isLoggedIn?t('rules.loginRequired'):t('rules.addRule')"><i class="bi bi-plus-lg" aria-hidden="true"></i>{{t('rules.addRule')}}</ui-button>
            <ui-dropdown-menu v-if="isLoggedIn && bulkImportExportEnabled"
                :open="showDataDropdown" :items="dataMenuItems" :trigger-label="t('rules.exportImport')"
                @toggle="$emit('toggle-data-dropdown')" @close="$emit('toggle-data-dropdown')"
                @select="handleDataMenuAction"></ui-dropdown-menu>
        </div>
    </div>
    <div v-if="!jmsEnabled" class="warning-banner"><i class="bi bi-exclamation-triangle" aria-hidden="true"></i> {{t('rules.jmsNotEnabled', {jmsLabel: jmsLabel})}}</div>

    <div class="list-toolbar">
        <workspace-search-field
            input-id="ruleSearch"
            :model-value="ruleFilter.keyword"
            :placeholder="t('rules.searchPlaceholder')"
            :aria-label="t('rules.searchPlaceholder')"
            :clear-label="t('rules.clearSearch')"
            :submit-mode="true"
            @search="setFilter('keyword', $event)"
        ></workspace-search-field>
        <ui-toggle-group :model-value="ruleFilter.protocol" :options="protocolFilterOptions" :aria-label="t('rules.protocolFilter')" @update:model-value="setFilter('protocol', $event)"></ui-toggle-group>
        <ui-toggle-group :model-value="ruleFilter.enabled" :options="enabledFilterOptions" :aria-label="t('rules.statusFilter')" @update:model-value="setFilter('enabled', $event)"></ui-toggle-group>
        <ui-toggle-group :model-value="ruleFilter.isProtected" :options="protectionFilterOptions" :aria-label="t('rules.protectionFilter')" @update:model-value="setFilter('isProtected', $event)"></ui-toggle-group>
        <ui-toggle-group :model-value="ruleFilter.mode" :options="modeFilterOptions" :aria-label="t('rules.filterMode')" @update:model-value="setFilter('mode', $event)"></ui-toggle-group>
        <ui-toggle-group :model-value="ruleFilter.expiring" :options="expiringFilterOptions" :aria-label="t('rules.filterExpiring')" @update:model-value="setFilter('expiring', $event)"></ui-toggle-group>
        <div class="list-toolbar__end">
            <ui-segmented-control :model-value="ruleViewMode" :options="viewModeOptions"
                name="ruleViewMode" size="compact" :aria-label="t('rules.viewMode')"
                @update:model-value="$emit('update:ruleViewMode', $event)"></ui-segmented-control>
        </div>
    </div>
    <ui-filter-chip-list :items="ruleFilterChips" :aria-label="t('common.activeFilters')"
        :clear-label="t('rules.clearAll')"
        @remove="$emit('remove-rule-chip', $event)"
        @clear="$emit('clear-rule-filters')"></ui-filter-chip-list>

    <div v-if="batchSelectMode" class="batch-bar" role="region" :aria-label="t('rules.batchSelect')">
        <span class="batch-bar__count">{{t('rules.selectedCount', {count: selectedRules.length})}}</span>
        <ui-button variant="secondary" size="compact" :disabled="!selectedRules.length" @click="$emit('batch-protect', true)"><i class="bi bi-shield-check" aria-hidden="true"></i>{{t('rules.protect')}}</ui-button>
        <ui-button variant="secondary" size="compact" :disabled="!selectedRules.length" @click="$emit('batch-protect', false)"><i class="bi bi-shield" aria-hidden="true"></i>{{t('rules.unprotect')}}</ui-button>
        <ui-button variant="danger" size="compact" :disabled="!selectedRules.length" @click="$emit('delete-selected')"><i class="bi bi-trash" aria-hidden="true"></i>{{t('rules.delete')}}</ui-button>
        <ui-button variant="quiet" size="compact" class="batch-bar__done" @click="$emit('toggle-batch-select')">{{t('rules.batchDone')}}</ui-button>
    </div>

    <div class="card card-table list-card">
        <ui-load-state v-if="loading.rulesError && !loading.rules" kind="error" icon="bi-cloud-slash" :title="t('rules.loadFailed')" has-action>
            <template #action><ui-button type="button" variant="secondary" size="compact" @click="$emit('load-rules', true)"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</ui-button></template>
        </ui-load-state>

        <template v-else-if="ruleViewMode==='list'">
            <div class="card-table-body">
                <div v-if="loading.rules && !rules.length" class="list-skeleton" role="status" :aria-label="t('common.loading')">
                    <div v-for="i in 8" :key="'sk-rule-'+i" class="list-skeleton__row"><span class="sk sk-w-38"></span><span class="sk sk-w-40p"></span><span class="sk sk-w-15p"></span></div>
                </div>
                <rule-table v-else-if="pagedRules.length" :rules="pagedRules" :selected-id="ruleDetailId"
                    :is-logged-in="isLoggedIn" :status="status" :http-label="httpLabel" :jms-label="jmsLabel"
                    sortable :rule-sort="ruleSort" :batch-select-mode="batchSelectMode" :selected-rules="selectedRules"
                    :can-drag="canDragRules" :drag-row-class="dragRowClass"
                    @select="selectRule" @edit="$emit('open-edit', $event)" @toggle-enabled="$emit('toggle-enabled', $event)"
                    @menu="handleRowMenu" @toggle-selection="toggleSelection" @toggle-select-all="$emit('toggle-select-all', $event)"
                    @sort="$emit('toggle-rule-sort', $event)"
                    @drag-start="(e, r) => $emit('drag-start', e, r)" @drag-over="(e, r) => $emit('drag-over', e, r)"
                    @drag-leave="(e, r) => $emit('drag-leave', e, r)" @drop="$emit('drop', $event)" @drag-end="$emit('drag-end')"></rule-table>
                <ui-load-state v-else kind="empty" has-action
                    :icon="hasRuleFilters?'bi-search':'bi-inbox'"
                    :title="hasRuleFilters?t('rules.emptyFilterResult'):t('rules.emptyNoRules')">
                    <template #action>
                        <ui-button v-if="hasRuleFilters" variant="secondary" size="compact" @click="clearFilters()">{{t('rules.clearFilter')}}</ui-button>
                        <ui-button v-else type="button" variant="primary" @click="$emit('open-create')" :disabled="!isLoggedIn" :title="!isLoggedIn?t('rules.loginRequired'):t('rules.createFirstRule')"><i class="bi bi-plus-lg" aria-hidden="true"></i>{{t('rules.createFirstRule')}}</ui-button>
                    </template>
                </ui-load-state>
            </div>
            <workspace-pagination
                :page="rulePage" :total-pages="ruleTotalPages" :page-size="rulePageSize"
                :pagination-label="t('rules.pagination')"
                :page-status-label="t('stats.pageStatus', {page:rulePage, total:ruleTotalPages})"
                :page-size-label="t('stats.pageSize')"
                :first-page-label="t('stats.firstPage')" :previous-page-label="t('stats.previousPage')"
                :next-page-label="t('stats.nextPage')" :last-page-label="t('stats.lastPage')"
                :scroll-hint-label="t('common.scrollForMore')"
                :scroll-region-label="t('common.scrollableRulesTable')"
                @update:page="$emit('update:rulePage', $event)"
                @update:page-size="$emit('update:rulePageSize', $event); $emit('update:rulePage', 1)"
            >
                <template #summary>
                    <span class="sub-info">{{t('rules.totalCount', {count: ruleTotalElements})}}</span>
                </template>
            </workspace-pagination>
        </template>

        <div v-else class="card-table-body group-view">
            <section v-for="section in groupSections" :key="section.key" class="rule-group">
                <button type="button" class="rule-group__header" :aria-expanded="expandedTagGroups.includes(section.key) ? 'true' : 'false'" @click="$emit('toggle-tag-group', section.key)">
                    <i class="bi bi-chevron-right rule-group__chevron" aria-hidden="true"></i>
                    <span class="rule-group__name" :class="{'is-tag': section.isTag}">{{section.label}}</span>
                    <span class="rule-group__count">{{section.count}}</span>
                </button>
                <template v-if="expandedTagGroups.includes(section.key)">
                    <template v-if="!section.isTag">
                        <div v-if="groupLoading['_untagged']" class="rule-group__state loading-reveal" role="status"><i class="bi bi-arrow-clockwise spin" aria-hidden="true"></i>{{t('rules.loading')}}</div>
                        <rule-table v-if="groupRules('_untagged').length" :rules="groupRules('_untagged')" :selected-id="ruleDetailId"
                            :is-logged-in="isLoggedIn" :status="status" :http-label="httpLabel" :jms-label="jmsLabel"
                            @select="selectRule" @edit="$emit('open-edit', $event)" @toggle-enabled="$emit('toggle-enabled', $event)" @menu="handleRowMenu"></rule-table>
                        <div v-if="(groupCounts['_untagged'] || 0) > (rulesByTagGroup['_untagged']?.length || 0)" class="rule-group__more">
                            <ui-button variant="secondary" size="compact" @click="$emit('show-more-group', '_untagged')">{{t('rules.showMore')}} ({{rulesByTagGroup['_untagged']?.length || 0}}/{{groupCounts['_untagged'] || 0}})</ui-button>
                            <ui-button variant="quiet" size="compact" @click="$emit('show-all-group', '_untagged', groupCounts['_untagged'] || 0)">{{t('rules.showAll')}}</ui-button>
                        </div>
                        <div v-if="!groupLoading['_untagged'] && !(groupCounts['_untagged'] || 0)" class="rule-group__state">{{t('rules.noRules')}}</div>
                    </template>
                    <div v-else class="rule-group__values">
                        <div v-for="sub in section.values" :key="sub.id" class="rule-subgroup">
                            <button type="button" class="rule-group__header is-sub" :aria-expanded="expandedTagSubgroups.includes(sub.id) ? 'true' : 'false'" @click="$emit('toggle-tag-subgroup', sub.id)">
                                <i class="bi bi-chevron-right rule-group__chevron" aria-hidden="true"></i>
                                <ui-badge tone="neutral">{{sub.value}}</ui-badge>
                                <span class="rule-group__count">{{sub.count}}</span>
                            </button>
                            <template v-if="expandedTagSubgroups.includes(sub.id)">
                                <div v-if="groupLoading[sub.id]" class="rule-group__state loading-reveal" role="status"><i class="bi bi-arrow-clockwise spin" aria-hidden="true"></i>{{t('rules.loading')}}</div>
                                <rule-table v-if="subgroupRules(sub.id).length" :rules="subgroupRules(sub.id)" :selected-id="ruleDetailId"
                                    :is-logged-in="isLoggedIn" :status="status" :http-label="httpLabel" :jms-label="jmsLabel"
                                    @select="selectRule" @edit="$emit('open-edit', $event)" @toggle-enabled="$emit('toggle-enabled', $event)" @menu="handleRowMenu"></rule-table>
                                <div v-if="sub.count > (rulesByTag[sub.id]?.length || 0)" class="rule-group__more">
                                    <ui-button variant="secondary" size="compact" @click="$emit('show-more-group', sub.id)">{{t('rules.showMore')}} ({{rulesByTag[sub.id]?.length || 0}}/{{sub.count}})</ui-button>
                                    <ui-button variant="quiet" size="compact" @click="$emit('show-all-group', sub.id, sub.count)">{{t('rules.showAll')}}</ui-button>
                                </div>
                            </template>
                        </div>
                    </div>
                </template>
            </section>
        </div>
    </div>

    <rule-detail :open="!!ruleDetailId" :rule="detailRule" :detail="ruleDetailId ? rulePreviewCache[ruleDetailId] : null"
        :loading="!!(ruleDetailId && rulePreviewLoading[ruleDetailId])" :error="!!(ruleDetailId && rulePreviewError[ruleDetailId])"
        :is-logged-in="isLoggedIn" :status="status" :http-label="httpLabel" :jms-label="jmsLabel"
        :has-prev="detailIndex > 0" :has-next="detailIndex >= 0 && detailIndex < navRules.length - 1"
        @close="$emit('close-rule-detail')" @prev="stepDetail(-1)" @next="stepDetail(1)"
        @retry="$emit('load-rule-detail', ruleDetailId)"
        @edit="$emit('open-edit', $event)" @menu="handleRowMenu" @clip-copy="$emit('clip-copy', $event)"></rule-detail>
</div>
`
};
