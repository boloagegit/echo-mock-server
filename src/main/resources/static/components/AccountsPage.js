/**
 * AccountsPage - 帳號管理頁面
 *
 * 顯示內建帳號清單，支援搜尋、新增、啟用/停用、重設密碼、刪除。
 * 忘記密碼標記以醒目 Badge 顯示，重設密碼後以 Modal 顯示臨時密碼（僅一次）。
 */
const AccountsPage = {
  props: {
    accounts: Object,
    loading: Object
  },
  inject: ['t'],
  data() {
    return {
      showCreateModal: false,
      createForm: { username: '', password: '' },
      createAttempted: false,
      creating: false,
      showTempPasswordModal: false,
      tempPassword: '',
      passwordCopied: false,
      previousFocus: null,
      inertSiblings: [],
    };
  },
  computed: {
    roleFilterOptions() {
      return [
        { value: '', label: this.t('accounts.filterAllRoles') },
        { value: 'ROLE_ADMIN', label: this.t('accounts.roleAdmin') },
        { value: 'ROLE_USER', label: this.t('accounts.roleUser') },
      ];
    },
    accountStatusFilterOptions() {
      return [
        { value: '', label: this.t('accounts.filterAllStatuses') },
        { value: 'true', label: this.t('accounts.enabled') },
        { value: 'false', label: this.t('accounts.disabled') },
      ];
    },
    resetFilterOptions() {
      return [{
        value: 'true',
        label: this.t('accounts.filterResetRequested'),
        icon: 'bi-exclamation-triangle',
      }];
    },
    usernameError() {
      if (!this.createAttempted) { return ''; }
      const length = this.createForm.username.trim().length;
      return length < 3 || length > 50 ? this.t('accounts.usernameInvalid') : '';
    },
    passwordError() {
      if (!this.createAttempted) { return ''; }
      return this.createForm.password.length < 6 ? this.t('accounts.passwordInvalid') : '';
    },
    hasActiveFilters() {
      return Boolean(this.accounts.searchKeyword.value || this.accounts.roleFilter.value
        || this.accounts.enabledFilter.value !== '' || this.accounts.resetFilter.value !== '');
    },
  },
  beforeUnmount() {
    restoreOverlaySiblings(this.inertSiblings);
  },
  methods: {
    fmtTime,
    activateDialog(overlay, initialFocus) {
      this.$nextTick(() => {
        this.inertSiblings = makeOverlaySiblingsInert(overlay());
        initialFocus()?.focus?.();
      });
    },
    restoreDialogFocus() {
      restoreOverlaySiblings(this.inertSiblings);
      this.inertSiblings = [];
      this.previousFocus?.focus?.();
      this.previousFocus = null;
    },
    handleDialogKeydown(event, dialog, close) {
      if (event.key === 'Escape') {
        event.preventDefault();
        close();
        return;
      }
      trapDialogFocus(event, dialog());
    },
    openCreateModal() {
      this.previousFocus = document.activeElement;
      this.createForm = { username: '', password: '' };
      this.createAttempted = false;
      this.showCreateModal = true;
      this.activateDialog(() => this.$refs.createAccountOverlay, () => this.$refs.accountUsername);
    },
    closeCreateModal() {
      this.showCreateModal = false;
      this.restoreDialogFocus();
    },
    async submitCreate() {
      if (this.creating) { return; }
      this.createAttempted = true;
      if (this.usernameError || this.passwordError) {
        this.$nextTick(() => this.$refs.createAccountDialog?.querySelector('[aria-invalid="true"]')?.focus());
        return;
      }
      this.creating = true;
      const ok = await this.accounts.createAccount(this.createForm.username.trim(), this.createForm.password);
      this.creating = false;
      if (ok) {
        this.closeCreateModal();
      }
    },
    async handleResetPassword(account) {
      const trigger = document.activeElement;
      const pwd = await this.accounts.resetPassword(account);
      if (pwd) {
        this.previousFocus = trigger;
        this.tempPassword = pwd;
        this.passwordCopied = false;
        this.showTempPasswordModal = true;
        this.activateDialog(() => this.$refs.tempPasswordOverlay, () => this.$refs.tempPasswordDone);
      }
    },
    closeTempPasswordModal() {
      this.tempPassword = '';
      this.passwordCopied = false;
      this.showTempPasswordModal = false;
      this.restoreDialogFocus();
    },
    async copyTempPassword() {
      try {
        await navigator.clipboard.writeText(this.tempPassword);
        this.passwordCopied = true;
      } catch {
        this.passwordCopied = false;
      }
    }
  },
  template: /* html */`
    <div class="page workspace-page accounts-workspace" :class="{active:true}">
      <div class="page-header">
        <div class="page-heading">
          <h1 class="page-title">{{t('accounts.title')}}</h1>
          <span class="page-count">{{accounts.accountTotalElements.value}}</span>
        </div>
        <div class="page-actions">
          <ui-button class="btn btn-secondary" @click="accounts.loadAccounts()" :disabled="loading.accounts"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.accounts}"></i> {{t('accounts.refresh')}}</ui-button>
          <ui-button class="btn btn-primary" @click="openCreateModal()"><i class="bi bi-plus-lg"></i> {{t('accounts.addAccount')}}</ui-button>
        </div>
      </div>

      <!-- Search -->
      <div class="card workspace-filter-card">
        <div class="card-body filter-row workspace-filter-bar">
          <div class="workspace-filter-controls">
            <ui-segmented-control :model-value="accounts.roleFilter.value" :options="roleFilterOptions"
              name="accountRoleFilter" size="compact" :aria-label="t('accounts.filterRole')"
              @update:model-value="accounts.roleFilter.value=$event"></ui-segmented-control>
            <div class="filter-divider" aria-hidden="true"></div>
            <ui-segmented-control :model-value="accounts.enabledFilter.value" :options="accountStatusFilterOptions"
              name="accountStatusFilter" size="compact" :aria-label="t('accounts.filterStatus')"
              @update:model-value="accounts.enabledFilter.value=$event"></ui-segmented-control>
            <div class="filter-divider" aria-hidden="true"></div>
            <ui-toggle-group :model-value="accounts.resetFilter.value" :options="resetFilterOptions"
              :aria-label="t('accounts.filterResetRequested')"
              @update:model-value="accounts.resetFilter.value=$event"></ui-toggle-group>
          <workspace-search-field
            input-id="accountSearch"
            :model-value="accounts.searchKeyword.value"
            :placeholder="t('accounts.searchPlaceholder')"
            :aria-label="t('accounts.searchPlaceholder')"
            :clear-label="t('accounts.searchPlaceholder')"
            :submit-mode="true"
            :submit-label="t('common.searchAction')"
            @search="accounts.searchKeyword.value=$event"
          ></workspace-search-field>
          </div>
        </div>
      </div>

      <!-- Table -->
      <div class="card card-table workspace-table-card">
        <ui-load-state v-if="loading.accountsError && !loading.accounts" kind="error" icon="bi-cloud-slash" :title="t('accounts.loadFailed')" has-action>
          <template #action><ui-button type="button" class="btn btn-sm btn-secondary" @click="accounts.loadAccounts()"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</ui-button></template>
        </ui-load-state>
        <div v-else class="card-table-body">
          <div v-if="loading.accounts && !accounts.filteredAccounts.value.length" role="status" :aria-label="t('common.loading')">
            <div v-for="i in 4" :key="'sk-acc-'+i" class="sk-row">
              <span class="sk sk-text" style="width:120px"></span>
              <span class="sk sk-badge" style="width:60px"></span>
              <span class="sk sk-badge" style="width:50px"></span>
              <span class="sk sk-text-sm" style="width:100px"></span>
              <span class="sk sk-text-sm" style="width:100px"></span>
              <span class="sk sk-btn"></span>
            </div>
          </div>
          <table v-if="accounts.filteredAccounts.value.length" class="table-fixed workspace-table">
            <thead><tr>
              <th :aria-sort="accounts.accountSort.value.field==='username'?(accounts.accountSort.value.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('accounts.thUsername')" :active="accounts.accountSort.value.field==='username'" :ascending="accounts.accountSort.value.asc" @toggle="accounts.toggleAccountSort('username')"></ui-table-sort-header></th>
              <th style="width:80px" :aria-sort="accounts.accountSort.value.field==='role'?(accounts.accountSort.value.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('accounts.thRole')" :active="accounts.accountSort.value.field==='role'" :ascending="accounts.accountSort.value.asc" @toggle="accounts.toggleAccountSort('role')"></ui-table-sort-header></th>
              <th class="col-hide-sm" style="width:80px" :aria-sort="accounts.accountSort.value.field==='enabled'?(accounts.accountSort.value.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('accounts.thEnabled')" :active="accounts.accountSort.value.field==='enabled'" :ascending="accounts.accountSort.value.asc" @toggle="accounts.toggleAccountSort('enabled')"></ui-table-sort-header></th>
              <th class="col-datetime col-hide-md" :aria-sort="accounts.accountSort.value.field==='createdAt'?(accounts.accountSort.value.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('accounts.thCreatedAt')" :active="accounts.accountSort.value.field==='createdAt'" :ascending="accounts.accountSort.value.asc" @toggle="accounts.toggleAccountSort('createdAt')"></ui-table-sort-header></th>
              <th class="col-datetime col-hide-md" :aria-sort="accounts.accountSort.value.field==='lastLoginAt'?(accounts.accountSort.value.asc?'ascending':'descending'):'none'"><ui-table-sort-header :label="t('accounts.thLastLoginAt')" :active="accounts.accountSort.value.field==='lastLoginAt'" :ascending="accounts.accountSort.value.asc" @toggle="accounts.toggleAccountSort('lastLoginAt')"></ui-table-sort-header></th>
              <th class="col-actions col-actions-3">{{t('accounts.thActions')}}</th>
            </tr></thead>
            <tbody>
              <tr v-for="a in accounts.filteredAccounts.value" :key="a.id">
                <td class="list-identity-cell">
                  <span class="list-record-name">{{a.username}}</span>
                  <ui-badge v-if="a.passwordResetRequested" class="badge badge-warning" style="margin-left:6px"><i class="bi bi-exclamation-triangle-fill"></i> {{t('accounts.forgotPasswordBadge')}}</ui-badge>
                </td>
                <td><ui-badge class="badge" :class="a.role==='ROLE_ADMIN'?'badge-http':'badge-muted'">{{a.role==='ROLE_ADMIN'?t('accounts.roleAdmin'):t('accounts.roleUser')}}</ui-badge></td>
                <td class="col-hide-sm"><ui-badge class="badge" :class="a.enabled?'badge-success':'badge-danger'">{{a.enabled?t('accounts.enabled'):t('accounts.disabled')}}</ui-badge></td>
                <td class="col-datetime col-hide-md"><span class="sub-info" :title="fmtTime(a.createdAt,false)">{{fmtTime(a.createdAt)}}</span></td>
                <td class="col-datetime col-hide-md"><span class="sub-info" :title="a.lastLoginAt?fmtTime(a.lastLoginAt,false):t('accounts.neverLoggedIn')">{{a.lastLoginAt?fmtTime(a.lastLoginAt):t('accounts.neverLoggedIn')}}</span></td>
                <td class="col-actions col-actions-3">
                  <div style="display:flex;gap:0.25rem">
                    <ui-button v-if="a.enabled" class="btn btn-sm btn-icon btn-secondary" :title="t('accounts.disable')" :aria-label="t('accounts.disable')+' '+a.username" @click="accounts.disableAccount(a)"><i class="bi bi-pause-circle"></i></ui-button>
                    <ui-button v-else class="btn btn-sm btn-icon btn-secondary" :title="t('accounts.enable')" :aria-label="t('accounts.enable')+' '+a.username" @click="accounts.enableAccount(a)"><i class="bi bi-play-circle"></i></ui-button>
                    <ui-button class="btn btn-sm btn-icon btn-secondary" :title="t('accounts.resetPassword')" :aria-label="t('accounts.resetPassword')+' '+a.username" @click="handleResetPassword(a)"><i class="bi bi-key"></i></ui-button>
                    <ui-button class="btn btn-sm btn-icon btn-danger" :title="t('accounts.delete')" :aria-label="t('accounts.delete')+' '+a.username" @click="accounts.deleteAccount(a)"><i class="bi bi-trash"></i></ui-button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <ui-load-state v-if="!accounts.filteredAccounts.value.length && !loading.accounts" kind="empty" has-action
            :icon="hasActiveFilters?'bi-search':'bi-people'"
            :title="hasActiveFilters?t('accounts.emptySearch'):t('accounts.emptyTitle')"
            :hint="hasActiveFilters?t('accounts.emptySearchHint'):t('accounts.emptyHint')">
            <template #action>
              <ui-button v-if="hasActiveFilters" class="btn btn-sm btn-secondary" @click="accounts.clearAccountFilters()">{{t('accounts.clearFilters')}}</ui-button>
              <ui-button v-else class="btn btn-sm btn-primary" @click="openCreateModal()"><i class="bi bi-person-plus"></i> {{t('accounts.addAccount')}}</ui-button>
            </template>
          </ui-load-state>
        </div>
        <workspace-pagination
          v-if="!loading.accountsError"
          :page="accounts.accountPage.value" :total-pages="accounts.accountTotalPages.value" :page-size="accounts.accountPageSize.value"
          :pagination-label="t('accounts.pagination')"
          :page-status-label="t('stats.pageStatus', {page:accounts.accountPage.value, total:accounts.accountTotalPages.value})"
          :page-size-label="t('stats.pageSize')"
          :first-page-label="t('stats.firstPage')" :previous-page-label="t('stats.previousPage')"
          :next-page-label="t('stats.nextPage')" :last-page-label="t('stats.lastPage')"
          :scroll-hint-label="t('common.scrollForMore')" :scroll-region-label="t('accounts.scrollableTable')"
          @update:page="accounts.accountPage.value=$event"
          @update:page-size="accounts.accountPageSize.value=$event"
        >
          <template #summary>
            <span class="sub-info">{{t('accounts.totalCount', {count:accounts.accountTotalElements.value})}}</span>
            <ui-button v-if="hasActiveFilters" type="button" variant="quiet" size="compact" class="workspace-filter-reset" @click="accounts.clearAccountFilters()"><i class="bi bi-funnel-fill" aria-hidden="true"></i>{{t('accounts.filtering')}}</ui-button>
          </template>
        </workspace-pagination>
      </div>

      <!-- Create Account Modal -->
      <ui-modal-transition>
      <div ref="createAccountOverlay" v-if="showCreateModal" class="modal-overlay" @click.self="closeCreateModal()" @keydown="handleDialogKeydown($event, () => $refs.createAccountDialog, closeCreateModal)">
        <div ref="createAccountDialog" class="modal-box workspace-modal account-modal" role="dialog" aria-modal="true" aria-labelledby="createAccountTitle" tabindex="-1">
          <div class="modal-header">
            <div class="modal-heading"><span class="modal-heading-icon"><i class="bi bi-person-plus" aria-hidden="true"></i></span><h2 id="createAccountTitle">{{t('accounts.createTitle')}}</h2></div>
            <ui-button type="button" class="close-btn" @click="closeCreateModal()" :aria-label="t('modal.cancel')"><i class="bi bi-x-lg" aria-hidden="true"></i></ui-button>
          </div>
          <div class="modal-body account-modal-body">
            <div class="form-group">
              <label class="form-label" for="accountUsername">{{t('accounts.username')}}</label>
              <input ref="accountUsername" id="accountUsername" v-model="createForm.username" class="form-control" :class="{'is-invalid':usernameError}" :placeholder="t('accounts.usernamePlaceholder')" autocomplete="off" :aria-invalid="usernameError?'true':'false'" :aria-describedby="usernameError?'accountUsernameError':null" @keydown.enter="submitCreate()">
              <div v-if="usernameError" id="accountUsernameError" class="invalid-feedback">{{usernameError}}</div>
            </div>
            <div class="form-group">
              <label class="form-label" for="accountPassword">{{t('accounts.password')}}</label>
              <input id="accountPassword" v-model="createForm.password" type="password" class="form-control" :class="{'is-invalid':passwordError}" :placeholder="t('accounts.passwordPlaceholder')" autocomplete="new-password" :aria-invalid="passwordError?'true':'false'" :aria-describedby="passwordError?'accountPasswordError':null" @keydown.enter="submitCreate()">
              <div v-if="passwordError" id="accountPasswordError" class="invalid-feedback">{{passwordError}}</div>
            </div>
          </div>
          <div class="modal-footer">
            <ui-button type="button" variant="quiet" @click="closeCreateModal()">{{t('modal.cancel')}}</ui-button>
            <ui-button type="button" class="btn btn-primary" @click="submitCreate()" :disabled="creating"><i class="bi" :class="creating?'bi-arrow-clockwise spin':'bi-person-plus'" aria-hidden="true"></i>{{t('modal.create')}}</ui-button>
          </div>
        </div>
      </div>
      </ui-modal-transition>

      <!-- Temp Password Modal -->
      <ui-modal-transition>
      <div ref="tempPasswordOverlay" v-if="showTempPasswordModal" class="modal-overlay" @click.self="closeTempPasswordModal()" @keydown="handleDialogKeydown($event, () => $refs.tempPasswordDialog, closeTempPasswordModal)">
        <div ref="tempPasswordDialog" class="modal-box workspace-modal account-modal" role="dialog" aria-modal="true" aria-labelledby="tempPasswordTitle" tabindex="-1">
          <div class="modal-header">
            <div class="modal-heading"><span class="modal-heading-icon"><i class="bi bi-key" aria-hidden="true"></i></span><h2 id="tempPasswordTitle">{{t('accounts.tempPasswordTitle')}}</h2></div>
            <ui-button type="button" class="close-btn" @click="closeTempPasswordModal()" :aria-label="t('modal.cancel')"><i class="bi bi-x-lg" aria-hidden="true"></i></ui-button>
          </div>
          <div class="modal-body temp-password-body">
            <p class="temp-password-message">{{t('accounts.tempPasswordMsg')}}</p>
            <div class="temp-password-field">
              <code class="temp-password-value">{{tempPassword}}</code>
              <ui-button type="button" class="btn btn-secondary temp-password-copy" @click="copyTempPassword"><ui-motion-icon :icon="passwordCopied?'bi-check2':'bi-clipboard'"></ui-motion-icon>{{passwordCopied?t('accounts.passwordCopied'):t('accounts.copyPassword')}}</ui-button>
            </div>
            <p class="temp-password-note"><i class="bi bi-exclamation-circle" aria-hidden="true"></i>{{t('accounts.tempPasswordOnce')}}</p>
          </div>
          <div class="modal-footer">
            <ui-button ref="tempPasswordDone" type="button" class="btn btn-primary" @click="closeTempPasswordModal()">{{t('accounts.done')}}</ui-button>
          </div>
        </div>
      </div>
      </ui-modal-transition>
    </div>
  `
};
