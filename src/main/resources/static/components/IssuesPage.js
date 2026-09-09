/**
 * IssuesPage - Issue Report 頁面
 *
 * 顯示使用者回報的問題，支援建立、篩選、回覆、resolve/reopen。
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
      expandedId: null,
      replyText: '',
      replyingId: null,
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
        { value: '', label: this.t('issues.all') },
        { value: 'OPEN', label: this.t('issues.open') },
        { value: 'RESOLVED', label: this.t('issues.resolved') },
      ];
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
  beforeUnmount() {
    restoreOverlaySiblings(this.inertSiblings);
  },
  methods: {
    fmtTime,
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
    },
    toggleExpand(id) {
      this.expandedId = this.expandedId === id ? null : id;
      this.replyingId = null;
      this.replyText = '';
    },
    startReply(id, existing) {
      this.replyingId = id;
      this.replyText = existing || '';
    },
    async submitReply(id) {
      if (!this.replyText.trim()) { return; }
      this.$emit('reply-issue', id, this.replyText);
      this.replyingId = null;
      this.replyText = '';
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
          <ui-button class="btn btn-secondary" @click="$emit('load-issues', true)" :disabled="loading.issues">
            <i class="bi bi-arrow-clockwise" :class="{'spin':loading.issues}"></i> {{t('issues.refresh')}}
          </ui-button>
          <ui-button class="btn btn-primary" @click="openCreate">
            <i class="bi bi-plus-lg"></i> {{t('issues.create')}}
          </ui-button>
        </div>
      </div>

      <!-- Filter -->
      <div class="card workspace-filter-card">
        <div class="card-body filter-row workspace-filter-bar">
          <div class="workspace-filter-controls">
            <ui-segmented-control :model-value="issueFilter.status" :options="issueStatusFilterOptions"
              name="issueStatusFilter" size="compact" :aria-label="t('issues.statusFilter')"
              @update:model-value="$emit('update:issueFilter', {...issueFilter,status:$event})"></ui-segmented-control>
            <div class="filter-divider" aria-hidden="true"></div>
            <workspace-search-field
              input-id="issueSearch"
              :model-value="issueFilter.keyword"
              :placeholder="t('issues.searchPlaceholder')"
              :aria-label="t('issues.searchPlaceholder')"
              :clear-label="t('issues.clearSearch')"
              :submit-mode="true" :submit-label="t('common.searchAction')"
              @search="$emit('update:issueFilter', {...issueFilter,keyword:$event})"
            ></workspace-search-field>
          </div>
        </div>
      </div>

      <!-- List -->
      <div class="card card-table workspace-table-card">
        <ui-load-state v-if="loading.issuesError && !loading.issues" kind="error" icon="bi-cloud-slash" :title="t('issues.loadFailed')" has-action>
          <template #action><ui-button type="button" class="btn btn-sm btn-secondary" @click="$emit('load-issues', true)"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</ui-button></template>
        </ui-load-state>
        <div v-else class="card-table-body">
          <div v-if="loading.issues && !issues.length" role="status" :aria-label="t('common.loading')">
            <div v-for="i in 5" :key="'sk-issue-'+i" class="sk-row">
              <span class="sk sk-badge" style="width:70px"></span>
              <span class="sk sk-text" style="width:40%"></span>
              <span class="sk sk-text-sm" style="width:80px"></span>
              <span class="sk sk-text-sm" style="width:100px"></span>
            </div>
          </div>
          <table v-if="pagedIssues.length" class="table-fixed workspace-table">
            <thead><tr>
              <th style="width:94px" :aria-sort="issueSort.field==='status'?(issueSort.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('issues.thStatus')" :active="issueSort.field==='status'" :ascending="issueSort.asc" @toggle="$emit('toggle-issue-sort','status')"></ui-table-sort-header></th>
              <th :aria-sort="issueSort.field==='title'?(issueSort.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('issues.thTitle')" :active="issueSort.field==='title'" :ascending="issueSort.asc" @toggle="$emit('toggle-issue-sort','title')"></ui-table-sort-header></th>
              <th class="col-hide-md" style="width:120px" :aria-sort="issueSort.field==='createdBy'?(issueSort.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('issues.thCreatedBy')" :active="issueSort.field==='createdBy'" :ascending="issueSort.asc" @toggle="$emit('toggle-issue-sort','createdBy')"></ui-table-sort-header></th>
              <th class="col-datetime" :aria-sort="issueSort.field==='createdAt'?(issueSort.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('issues.thTime')" :active="issueSort.field==='createdAt'" :ascending="issueSort.asc" @toggle="$emit('toggle-issue-sort','createdAt')"></ui-table-sort-header></th>
              <th class="col-actions col-actions-1">{{t('issues.thActions')}}</th>
            </tr></thead>
            <tbody>
              <template v-for="issue in pagedIssues" :key="issue.id">
                <tr @click="toggleExpand(issue.id)" style="cursor:pointer" :class="{active:expandedId===issue.id}">
                  <td>
                    <ui-badge class="badge" :class="issue.status==='OPEN'?'badge-warning':'badge-success'">{{issue.status==='OPEN'?t('issues.open'):t('issues.resolved')}}</ui-badge>
                  </td>
                  <td>
                    <div style="font-weight:500">{{issue.title}}</div>
                  </td>
                  <td class="col-hide-md"><span class="sub-info">{{issue.createdBy}}</span></td>
                  <td class="col-datetime"><span class="sub-info" :title="fmtTime(issue.createdAt,false)">{{fmtTime(issue.createdAt)}}</span></td>
                  <td class="col-actions col-actions-1">
                    <ui-button class="btn btn-sm btn-icon btn-secondary" :title="expandedId===issue.id?t('issues.collapse'):t('issues.expand')" :aria-label="expandedId===issue.id?t('issues.collapse'):t('issues.expand')" :aria-expanded="expandedId===issue.id" :aria-controls="'issue-detail-'+issue.id">
                      <i class="bi" :class="expandedId===issue.id?'bi-chevron-up':'bi-chevron-down'"></i>
                    </ui-button>
                  </td>
                </tr>
                <Transition name="ui-detail-row-motion">
                <tr v-if="expandedId===issue.id" class="rule-preview-row">
                  <td colspan="5" style="padding:0">
                    <div :id="'issue-detail-'+issue.id" class="rule-preview-content workspace-detail-surface issue-detail">
                      <!-- Description -->
                      <div class="issue-message issue-message-user">
                        <div class="issue-message-label"><i class="bi bi-person"></i> {{t('issues.description')}}</div>
                        <div class="issue-message-content">{{issue.description}}</div>
                      </div>
                      <!-- Admin Reply -->
                      <div v-if="issue.adminReply" class="issue-message issue-message-admin">
                        <div class="issue-message-label">
                          <i class="bi bi-reply"></i> {{t('issues.adminReply')}}
                          <span v-if="issue.repliedBy"> — {{issue.repliedBy}}</span>
                          <span v-if="issue.repliedAt"> · {{fmtTime(issue.repliedAt)}}</span>
                        </div>
                        <div class="issue-message-content">{{issue.adminReply}}</div>
                      </div>
                      <!-- Resolved info -->
                      <div v-if="issue.resolvedAt" style="margin-bottom:1rem" class="sub-info">
                        <i class="bi bi-check-circle"></i> {{t('issues.resolvedAt')}} {{fmtTime(issue.resolvedAt, false)}}
                      </div>
                      <!-- Reply form (admin) -->
                      <div v-if="isAdmin && replyingId===issue.id" class="issue-reply-form">
                        <textarea v-model="replyText" class="form-control" rows="3" :placeholder="t('issues.replyPlaceholder')" style="margin-bottom:0.5rem"></textarea>
                        <div style="display:flex;gap:0.5rem">
                          <ui-button class="btn btn-sm btn-primary" @click.stop="submitReply(issue.id)" :disabled="!replyText.trim()">{{t('issues.submitReply')}}</ui-button>
                          <ui-button variant="quiet" size="compact" @click.stop="replyingId=null">{{t('issues.cancel')}}</ui-button>
                        </div>
                      </div>
                      <!-- Admin actions -->
                      <div v-if="isAdmin" class="issue-actions">
                        <ui-button v-if="replyingId!==issue.id" class="btn btn-sm btn-secondary" @click.stop="startReply(issue.id, issue.adminReply)">
                          <i class="bi bi-reply"></i> {{t('issues.reply')}}
                        </ui-button>
                        <ui-button v-if="issue.status==='OPEN'" class="btn btn-sm btn-success" @click.stop="$emit('resolve-issue', issue.id)">
                          <i class="bi bi-check-lg"></i> {{t('issues.resolve')}}
                        </ui-button>
                        <ui-button v-if="issue.status==='RESOLVED'" class="btn btn-sm btn-warning" @click.stop="$emit('reopen-issue', issue.id)">
                          <i class="bi bi-arrow-counterclockwise"></i> {{t('issues.reopen')}}
                        </ui-button>
                        <ui-button class="btn btn-sm btn-danger" @click.stop="$emit('delete-issue', issue.id)">
                          <i class="bi bi-trash"></i> {{t('issues.delete')}}
                        </ui-button>
                      </div>
                    </div>
                  </td>
                </tr>
                </Transition>
              </template>
            </tbody>
          </table>
          <ui-load-state v-if="!pagedIssues.length && !loading.issues" kind="empty" :has-action="hasIssueFilters"
            :icon="hasIssueFilters?'bi-search':'bi-inbox'"
            :title="hasIssueFilters?t('issues.emptyFiltered'):t('issues.empty')"
            :hint="hasIssueFilters?t('issues.emptyFilteredHint'):t('issues.emptyHint')">
            <template #action><ui-button class="btn btn-sm btn-secondary" @click="$emit('update:issueFilter',{status:'',keyword:''})">{{t('issues.clearFilters')}}</ui-button></template>
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

      <!-- Create Modal -->
      <ui-modal-transition>
      <div ref="issueCreateOverlay" v-if="showCreateModal" class="modal-overlay" @click.self="closeCreate" @keydown="handleCreateKeydown">
        <div ref="issueCreateDialog" class="modal-box workspace-modal issue-create-modal" role="dialog" aria-modal="true" aria-labelledby="issueCreateTitle" tabindex="-1">
          <div class="modal-header">
            <div class="modal-heading"><span class="modal-heading-icon"><i class="bi bi-flag" aria-hidden="true"></i></span><h2 id="issueCreateTitle">{{t('issues.createTitle')}}</h2></div>
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
            <ui-button type="button" variant="quiet" @click="closeCreate" :disabled="creating">{{t('issues.cancel')}}</ui-button>
            <ui-button type="button" class="btn btn-primary" @click="submitCreate" :disabled="creating"><i class="bi" :class="creating?'bi-arrow-clockwise spin':'bi-send'" aria-hidden="true"></i>{{t('issues.submit')}}</ui-button>
          </div>
        </div>
      </div>
      </ui-modal-transition>
    </div>
  `
};
