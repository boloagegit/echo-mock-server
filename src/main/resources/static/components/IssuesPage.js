/**
 * IssuesPage - Issue Report 頁面
 *
 * 使用者回報的問題清單，沿用共用列表語言：工具列搜尋與篩選、資料列、右側詳情抽屜與列選單。
 * 抽屜內呈現描述與管理者回覆，管理者可直接回覆、標記解決／重新開啟或刪除。
 */
const IssuesPage = {
  props: {
    issues: Array,
    loading: Object,
    issueFilter: Object,
    issueSort: Object,
    issuePage: Number,
    issuePageSize: Number,
    issueTotalElements: Number,
    pagedIssues: Array,
    issueTotalPages: Number,
    filteredIssues: Array,
    issueSortIcon: Function,
    isAdmin: Boolean
  },
  emits: [
    'load-issues', 'create-issue', 'reply-issue',
    'resolve-issue', 'reopen-issue', 'delete-issue',
    'update:issueFilter', 'update:issuePage', 'update:issuePageSize', 'toggle-issue-sort'
  ],
  inject: ['t'],
  data() {
    return {
      showCreateModal: false,
      newTitle: '',
      newDescription: '',
      createAttempted: false,
      creating: false,
      createError: '',
      selectedIssueId: null,
      replyText: '',
      replying: false,
      saveShortcutKey: SAVE_SHORTCUT_KEY,
      previousFocus: null,
      inertSiblings: []
    };
  },
  computed: {
    hasIssueFilters() {
      return Boolean(this.issueFilter.status || this.issueFilter.keyword);
    },
    issueStatusFilterOptions() {
      return [
        { value: 'OPEN', label: this.t('issues.open') },
        { value: 'RESOLVED', label: this.t('issues.resolved') },
      ];
    },
    issueFilterChips() {
      const chips = [];
      if (this.issueFilter.status) {
        chips.push({ key: 'status', label: this.t('filterChips.status') + this.statusLabel(this.issueFilter.status) });
      }
      if (this.issueFilter.keyword) {
        chips.push({ key: 'keyword', label: this.t('filterChips.keyword') + this.issueFilter.keyword });
      }
      return chips;
    },
    selectedIndex() {
      return this.pagedIssues.findIndex(issue => issue.id === this.selectedIssueId);
    },
    selectedIssue() {
      return this.selectedIndex >= 0 ? this.pagedIssues[this.selectedIndex] : null;
    },
    createTitleError() {
      if (!this.createAttempted) { return ''; }
      const length = this.newTitle.trim().length;
      return length === 0 ? this.t('issues.titleRequired') : '';
    },
    createDescriptionError() {
      if (!this.createAttempted) { return ''; }
      return this.newDescription.trim() ? '' : this.t('issues.descriptionRequired');
    }
  },
  watch: {
    selectedIssueId() {
      this.replying = false;
      this.replyText = '';
    },
  },
  beforeUnmount() {
    restoreOverlaySiblings(this.inertSiblings);
  },
  methods: {
    fmtTime,
    statusLabel(status) {
      return status === 'OPEN' ? this.t('issues.open') : this.t('issues.resolved');
    },
    sortState(field) {
      if (this.issueSort.field !== field) return 'none';
      return this.issueSort.asc ? 'ascending' : 'descending';
    },
    removeFilterChip(key) {
      this.$emit('update:issueFilter', { ...this.issueFilter, [key]: '' });
    },
    selectIssue(issue) {
      this.selectedIssueId = this.selectedIssueId === issue.id ? null : issue.id;
    },
    onRowKeydown(event, issue) {
      if (event.target !== event.currentTarget) return;
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); this.selectIssue(issue); }
    },
    stepDetail(direction) {
      const next = this.pagedIssues[this.selectedIndex + direction];
      if (next) {
        this.selectedIssueId = next.id;
        this.$nextTick(() => document.querySelector('.issues-workspace [data-detail-row].is-selected')?.scrollIntoView({ block: 'nearest' }));
      }
    },
    issueMenuItems(issue) {
      if (!this.isAdmin) return [];
      const t = this.t;
      return [
        { key: 'reply', label: issue.adminReply ? t('issues.editReply') : t('issues.reply'), icon: 'bi-reply' },
        issue.status === 'OPEN'
          ? { key: 'resolve', label: t('issues.resolve'), icon: 'bi-check2-circle' }
          : { key: 'reopen', label: t('issues.reopen'), icon: 'bi-arrow-counterclockwise' },
        { key: 'delete', label: t('issues.delete'), icon: 'bi-trash', danger: true, dividerBefore: true },
      ];
    },
    handleIssueMenu(action, issue) {
      if (action === 'reply') { this.selectedIssueId = issue.id; this.$nextTick(() => this.startReply(issue)); }
      else if (action === 'resolve') this.$emit('resolve-issue', issue.id);
      else if (action === 'reopen') this.$emit('reopen-issue', issue.id);
      else if (action === 'delete') this.$emit('delete-issue', issue.id);
    },
    startReply(issue) {
      this.replying = true;
      this.replyText = issue.adminReply || '';
      this.$nextTick(() => this.$refs.replyInput?.focus());
    },
    onReplyKeydown(event, issue) {
      if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
        event.preventDefault();
        this.submitReply(issue);
      }
    },
    submitReply(issue) {
      if (!this.replyText.trim()) { return; }
      this.$emit('reply-issue', issue.id, this.replyText);
      this.replying = false;
      this.replyText = '';
    },
    openCreate() {
      this.previousFocus = document.activeElement;
      this.newTitle = '';
      this.newDescription = '';
      this.createAttempted = false;
      this.createError = '';
      this.showCreateModal = true;
      this.$nextTick(() => {
        this.inertSiblings = makeOverlaySiblingsInert(this.$refs.issueCreateOverlay);
        this.$refs.issueTitle?.focus();
      });
    },
    closeCreate() {
      if (this.creating) { return; }
      this.showCreateModal = false;
      restoreOverlaySiblings(this.inertSiblings);
      this.inertSiblings = [];
      this.previousFocus?.focus?.();
      this.previousFocus = null;
    },
    handleCreateKeydown(event) {
      if (event.key === 'Escape') {
        event.preventDefault();
        this.closeCreate();
        return;
      }
      if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
        event.preventDefault();
        this.submitCreate();
        return;
      }
      trapDialogFocus(event, this.$refs.issueCreateDialog);
    },
    submitCreate() {
      if (this.creating) { return; }
      this.createAttempted = true;
      this.createError = '';
      if (this.createTitleError || this.createDescriptionError) {
        this.$nextTick(() => this.$refs.issueCreateDialog?.querySelector('[aria-invalid="true"]')?.focus());
        return;
      }
      this.creating = true;
      this.$emit('create-issue', this.newTitle.trim(), this.newDescription.trim(), ok => {
        this.creating = false;
        if (ok) {
          this.closeCreate();
          return;
        }
        this.createError = this.t('issues.createFailed');
      });
    }
  },
  template: /* html */`
    <div class="page workspace-page issues-workspace" :class="{active:true}">
      <div class="page-header">
        <div class="page-heading">
          <h1 class="page-title">{{t('issues.title')}}</h1>
          <span class="page-count">{{issueTotalElements}}</span>
        </div>
        <div class="page-actions">
          <ui-button variant="secondary" @click="$emit('load-issues', true)" :disabled="loading.issues"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.issues}" aria-hidden="true"></i>{{t('issues.refresh')}}</ui-button>
          <ui-button variant="primary" @click="openCreate"><i class="bi bi-plus-lg" aria-hidden="true"></i>{{t('issues.create')}}</ui-button>
        </div>
      </div>

      <div class="list-toolbar">
        <workspace-search-field shortcut="/"
          input-id="issueSearch"
          :model-value="issueFilter.keyword"
          :placeholder="t('issues.searchPlaceholder')"
          :aria-label="t('issues.searchPlaceholder')"
          :clear-label="t('issues.clearSearch')"
          :submit-mode="true"
          @search="$emit('update:issueFilter', {...issueFilter,keyword:$event})"
        ></workspace-search-field>
        <ui-toggle-group :model-value="issueFilter.status" :options="issueStatusFilterOptions" :aria-label="t('issues.statusFilter')"
          @update:model-value="$emit('update:issueFilter', {...issueFilter,status:$event})"></ui-toggle-group>
      </div>
      <ui-filter-chip-list :items="issueFilterChips" :aria-label="t('common.activeFilters')"
        :clear-label="t('issues.clearFilters')"
        @remove="removeFilterChip($event)"
        @clear="$emit('update:issueFilter',{status:'',keyword:''})"></ui-filter-chip-list>

      <div class="card card-table list-card">
        <ui-load-state v-if="loading.issuesError && !loading.issues" kind="error" icon="bi-cloud-slash" :title="t('issues.loadFailed')" has-action>
          <template #action><ui-button variant="secondary" size="compact" @click="$emit('load-issues', true)"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</ui-button></template>
        </ui-load-state>
        <div v-else class="card-table-body">
          <div v-if="loading.issues && !issues.length" class="list-skeleton" role="status" :aria-label="t('common.loading')">
            <div v-for="i in 6" :key="'sk-issue-'+i" class="list-skeleton__row"><span class="sk sk-w-15p"></span><span class="sk sk-w-40p"></span><span class="sk sk-w-15p"></span></div>
          </div>
          <table v-else-if="pagedIssues.length" class="data-table issues-table">
            <thead><tr>
              <th class="col-status" :aria-sort="sortState('status')"><ui-table-sort-header :label="t('issues.thStatus')" :active="issueSort.field==='status'" :ascending="issueSort.asc" @toggle="$emit('toggle-issue-sort','status')"></ui-table-sort-header></th>
              <th class="col-title" :aria-sort="sortState('title')"><ui-table-sort-header :label="t('issues.thTitle')" :active="issueSort.field==='title'" :ascending="issueSort.asc" @toggle="$emit('toggle-issue-sort','title')"></ui-table-sort-header></th>
              <th class="col-owner" :aria-sort="sortState('createdBy')"><ui-table-sort-header :label="t('issues.thCreatedBy')" :active="issueSort.field==='createdBy'" :ascending="issueSort.asc" @toggle="$emit('toggle-issue-sort','createdBy')"></ui-table-sort-header></th>
              <th class="col-time" :aria-sort="sortState('createdAt')"><ui-table-sort-header :label="t('issues.thTime')" :active="issueSort.field==='createdAt'" :ascending="issueSort.asc" @toggle="$emit('toggle-issue-sort','createdAt')"></ui-table-sort-header></th>
              <th class="col-actions"><span class="visually-hidden">{{t('issues.thActions')}}</span></th>
            </tr></thead>
            <tbody>
              <tr v-for="issue in pagedIssues" :key="issue.id" data-detail-row tabindex="0"
                :class="{'is-selected': selectedIssueId===issue.id}" :aria-selected="selectedIssueId===issue.id ? 'true' : 'false'"
                @click="selectIssue(issue)" @keydown="onRowKeydown($event, issue)">
                <td class="col-status"><ui-status :tone="issue.status==='OPEN' ? 'warning' : 'success'">{{statusLabel(issue.status)}}</ui-status></td>
                <td class="col-title">
                  <span class="record-name">
                    <span class="record-name__title">{{issue.title}}</span>
                    <i v-if="issue.adminReply" class="bi bi-reply issue-replied" :title="t('issues.replied')" :aria-label="t('issues.replied')"></i>
                  </span>
                </td>
                <td class="col-owner cell-subtle">{{issue.createdBy}}</td>
                <td class="col-time cell-mono cell-subtle" :title="fmtTime(issue.createdAt,false)">{{fmtTime(issue.createdAt)}}</td>
                <td class="col-actions" @click.stop @dblclick.stop>
                  <span class="row-actions"><ui-row-menu v-if="isAdmin" :items="issueMenuItems(issue)" :label="t('common.moreActions') + ' ' + issue.title" @select="handleIssueMenu($event, issue)"></ui-row-menu></span>
                </td>
              </tr>
            </tbody>
          </table>
          <ui-load-state v-else kind="empty" :has-action="hasIssueFilters"
            :icon="hasIssueFilters?'bi-search':'bi-inbox'"
            :title="hasIssueFilters?t('issues.emptyFiltered'):t('issues.empty')"
            :hint="hasIssueFilters?t('issues.emptyFilteredHint'):t('issues.emptyHint')">
            <template #action><ui-button variant="secondary" size="compact" @click="$emit('update:issueFilter',{status:'',keyword:''})">{{t('issues.clearFilters')}}</ui-button></template>
          </ui-load-state>
        </div>
        <workspace-pagination
          v-if="!loading.issuesError"
          :page="issuePage" :total-pages="issueTotalPages" :page-size="issuePageSize"
          :pagination-label="t('issues.pagination')"
          :page-status-label="t('stats.pageStatus', {page:issuePage, total:issueTotalPages})"
          :page-size-label="t('stats.pageSize')"
          :first-page-label="t('stats.firstPage')" :previous-page-label="t('stats.previousPage')"
          :next-page-label="t('stats.nextPage')" :last-page-label="t('stats.lastPage')"
          @update:page="$emit('update:issuePage', $event)"
          @update:page-size="$emit('update:issuePageSize', $event)"
        >
          <template #summary><span class="sub-info">{{t('issues.totalCount', {count: issueTotalElements})}}</span></template>
        </workspace-pagination>
      </div>

      <ui-detail-drawer class="issue-detail-drawer" :open="!!selectedIssue" :title="selectedIssue ? selectedIssue.title : ''"
        :subtitle="selectedIssue ? selectedIssue.createdBy + ' · ' + fmtTime(selectedIssue.createdAt, false) : ''"
        :has-prev="selectedIndex > 0" :has-next="selectedIndex >= 0 && selectedIndex < pagedIssues.length - 1"
        @close="selectedIssueId = null" @prev="stepDetail(-1)" @next="stepDetail(1)">
        <template v-if="selectedIssue" #meta>
          <ui-status :tone="selectedIssue.status==='OPEN' ? 'warning' : 'success'">{{statusLabel(selectedIssue.status)}}</ui-status>
          <span v-if="selectedIssue.resolvedAt" class="cell-subtle">{{t('issues.resolvedAt')}} <span class="detail-mono">{{fmtTime(selectedIssue.resolvedAt, false)}}</span></span>
        </template>
        <template v-if="selectedIssue && isAdmin" #actions>
          <ui-button v-if="!replying" variant="secondary" size="compact" @click="startReply(selectedIssue)"><i class="bi bi-reply" aria-hidden="true"></i>{{selectedIssue.adminReply ? t('issues.editReply') : t('issues.reply')}}</ui-button>
          <ui-button v-if="selectedIssue.status==='OPEN'" variant="secondary" size="compact" @click="$emit('resolve-issue', selectedIssue.id)"><i class="bi bi-check2-circle" aria-hidden="true"></i>{{t('issues.resolve')}}</ui-button>
          <ui-button v-else variant="secondary" size="compact" @click="$emit('reopen-issue', selectedIssue.id)"><i class="bi bi-arrow-counterclockwise" aria-hidden="true"></i>{{t('issues.reopen')}}</ui-button>
          <ui-row-menu :items="[{ key: 'delete', label: t('issues.delete'), icon: 'bi-trash', danger: true }]" :label="t('common.moreActions')"
            @select="handleIssueMenu($event, selectedIssue)"></ui-row-menu>
        </template>
        <ui-detail-section v-if="selectedIssue" id="issue.conversation" class="issue-conversation" :title="t('issues.conversation')">
          <article class="issue-message">
            <header class="issue-message__head"><strong>{{selectedIssue.createdBy}}</strong><span class="detail-mono">{{fmtTime(selectedIssue.createdAt, false)}}</span></header>
            <p class="issue-message__body">{{selectedIssue.description}}</p>
          </article>
          <article v-if="selectedIssue.adminReply && !replying" class="issue-message is-reply">
            <header class="issue-message__head"><strong>{{selectedIssue.repliedBy || t('issues.adminReply')}}</strong><span v-if="selectedIssue.repliedAt" class="detail-mono">{{fmtTime(selectedIssue.repliedAt, false)}}</span></header>
            <p class="issue-message__body">{{selectedIssue.adminReply}}</p>
          </article>
          <div v-if="isAdmin && replying" class="issue-reply-form">
            <label class="visually-hidden" for="issueReplyInput">{{t('issues.replyPlaceholder')}}</label>
            <textarea id="issueReplyInput" ref="replyInput" v-model="replyText" class="form-control issue-reply-input" rows="4" :placeholder="t('issues.replyPlaceholder')" @keydown="onReplyKeydown($event, selectedIssue)"></textarea>
            <div class="issue-reply-actions">
              <span class="modal-footer-hint"><kbd>{{saveShortcutKey}}</kbd><kbd>Enter</kbd>{{t('issues.submitReply')}}</span>
              <ui-button variant="quiet" size="compact" @click="replying = false">{{t('issues.cancel')}}</ui-button>
              <ui-button variant="primary" size="compact" @click="submitReply(selectedIssue)" :disabled="!replyText.trim()">{{t('issues.submitReply')}}</ui-button>
            </div>
          </div>
        </ui-detail-section>
      </ui-detail-drawer>

      <!-- Create Modal -->
      <ui-modal-transition>
      <div ref="issueCreateOverlay" v-if="showCreateModal" class="modal-overlay" @keydown="handleCreateKeydown">
        <div ref="issueCreateDialog" class="modal-box workspace-modal issue-create-modal" role="dialog" aria-modal="true" aria-labelledby="issueCreateTitle" tabindex="-1">
          <div class="modal-header">
            <div class="modal-heading"><h2 id="issueCreateTitle">{{t('issues.createTitle')}}</h2></div>
            <ui-button type="button" class="close-btn" @click="closeCreate" :disabled="creating" :aria-label="t('issues.cancel')"><i class="bi bi-x-lg" aria-hidden="true"></i></ui-button>
          </div>
          <div class="modal-body issue-create-body">
            <div v-if="createError" class="issue-create-error" role="alert"><i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>{{createError}}</span></div>
            <div class="form-group">
              <label class="form-label" for="issueTitle">{{t('issues.titleLabel')}}</label>
              <input ref="issueTitle" id="issueTitle" v-model="newTitle" class="form-control" :class="{'is-invalid':createTitleError}" maxlength="200" :placeholder="t('issues.titlePlaceholder')" :aria-invalid="createTitleError?'true':'false'" :aria-describedby="createTitleError?'issueTitleError':null" @keyup.enter="submitCreate">
              <div class="field-meta"><span v-if="createTitleError" id="issueTitleError" class="invalid-feedback">{{createTitleError}}</span><span class="field-character-count">{{newTitle.length}} / 200</span></div>
            </div>
            <div class="form-group">
              <label class="form-label" for="issueDescription">{{t('issues.descriptionLabel')}}</label>
              <textarea id="issueDescription" v-model="newDescription" class="form-control issue-description-input" :class="{'is-invalid':createDescriptionError}" rows="7" maxlength="5000" :placeholder="t('issues.descriptionPlaceholder')" :aria-invalid="createDescriptionError?'true':'false'" :aria-describedby="createDescriptionError?'issueDescriptionError':null"></textarea>
              <div class="field-meta"><span v-if="createDescriptionError" id="issueDescriptionError" class="invalid-feedback">{{createDescriptionError}}</span><span class="field-character-count">{{newDescription.length}} / 5000</span></div>
            </div>
          </div>
          <div class="modal-footer">
            <span class="modal-footer-hint"><kbd>{{saveShortcutKey}}</kbd><kbd>Enter</kbd>{{t('issues.submit')}}</span>
            <ui-button type="button" variant="quiet" @click="closeCreate" :disabled="creating">{{t('issues.cancel')}}</ui-button>
            <ui-button type="button" variant="primary" @click="submitCreate" :disabled="creating"><i class="bi" :class="creating?'bi-arrow-clockwise spin':'bi-send'" aria-hidden="true"></i>{{t('issues.submit')}}</ui-button>
          </div>
        </div>
      </div>
      </ui-modal-transition>
    </div>
  `
};
