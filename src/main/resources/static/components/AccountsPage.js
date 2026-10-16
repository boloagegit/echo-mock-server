/**
 * AccountsPage - 帳號管理頁面
 *
 * 內建帳號清單，使用共用列表語言：工具列搜尋與篩選、資料列、右側詳情抽屜與列選單。
 * 忘記密碼以警示標記顯示；重設密碼後以 Modal 顯示臨時密碼（僅一次）。
 */
const AccountsPage = {
  props: {
    accounts: Object,
    loading: Object
  },
  inject: ['t'],
  data() {
    return {
      selectedAccountId: null,
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
    list() {
      return this.accounts.filteredAccounts.value;
    },
    sort() {
      return this.accounts.accountSort.value;
    },
    selectedIndex() {
      return this.list.findIndex(account => account.id === this.selectedAccountId);
    },
    selectedAccount() {
      return this.selectedIndex >= 0 ? this.list[this.selectedIndex] : null;
    },
    roleFilterOptions() {
      return [
        { value: 'ROLE_ADMIN', label: this.t('accounts.roleAdmin') },
        { value: 'ROLE_USER', label: this.t('accounts.roleUser') },
      ];
    },
    accountStatusFilterOptions() {
      return [
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
    accountFilterChips() {
      const accounts = this.accounts;
      const chips = [];
      if (accounts.searchKeyword.value) {
        chips.push({ key: 'keyword', label: this.t('filterChips.keyword') + accounts.searchKeyword.value });
      }
      if (accounts.roleFilter.value) {
        chips.push({ key: 'role', label: this.t('filterChips.role') + this.roleLabel(accounts.roleFilter.value) });
      }
      if (accounts.enabledFilter.value !== '') {
        chips.push({ key: 'enabled', label: this.t('filterChips.status') + (accounts.enabledFilter.value === 'true' ? this.t('accounts.enabled') : this.t('accounts.disabled')) });
      }
      if (accounts.resetFilter.value !== '') {
        chips.push({ key: 'reset', label: this.t('accounts.filterResetRequested') });
      }
      return chips;
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
      return this.accountFilterChips.length > 0;
    },
  },
  beforeUnmount() {
    restoreOverlaySiblings(this.inertSiblings);
  },
  methods: {
    fmtTime,
    roleLabel(role) {
      return role === 'ROLE_ADMIN' ? this.t('accounts.roleAdmin') : this.t('accounts.roleUser');
    },
    initial(account) {
      return (account.username || '?').charAt(0).toUpperCase();
    },
    sortState(field) {
      if (this.sort.field !== field) return 'none';
      return this.sort.asc ? 'ascending' : 'descending';
    },
    removeFilterChip(key) {
      const accounts = this.accounts;
      ({ keyword: accounts.searchKeyword, role: accounts.roleFilter, enabled: accounts.enabledFilter, reset: accounts.resetFilter })[key].value = '';
    },
    selectAccount(account) {
      this.selectedAccountId = this.selectedAccountId === account.id ? null : account.id;
    },
    closeDetail() {
      this.selectedAccountId = null;
    },
    onRowKeydown(event, account) {
      if (event.target !== event.currentTarget) return;
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); this.selectAccount(account); }
    },
    stepDetail(direction) {
      const next = this.list[this.selectedIndex + direction];
      if (next) {
        this.selectedAccountId = next.id;
        this.$nextTick(() => document.querySelector('.accounts-workspace [data-detail-row].is-selected')?.scrollIntoView({ block: 'nearest' }));
      }
    },
    accountMenuItems(account) {
      const t = this.t;
      return [
        account.enabled
          ? { key: 'disable', label: t('accounts.disable'), icon: 'bi-pause-circle' }
          : { key: 'enable', label: t('accounts.enable'), icon: 'bi-play-circle' },
        { key: 'reset', label: t('accounts.resetPassword'), icon: 'bi-key' },
        { key: 'delete', label: t('accounts.delete'), icon: 'bi-trash', danger: true, dividerBefore: true },
      ];
    },
    handleAccountMenu(action, account) {
      if (action === 'enable') this.accounts.enableAccount(account);
      else if (action === 'disable') this.accounts.disableAccount(account);
      else if (action === 'reset') this.handleResetPassword(account);
      else if (action === 'delete') this.accounts.deleteAccount(account);
    },
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
          <ui-button variant="secondary" @click="accounts.loadAccounts()" :disabled="loading.accounts"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.accounts}" aria-hidden="true"></i>{{t('accounts.refresh')}}</ui-button>
          <ui-button variant="primary" @click="openCreateModal()"><i class="bi bi-plus-lg" aria-hidden="true"></i>{{t('accounts.addAccount')}}</ui-button>
        </div>
      </div>

      <div class="list-toolbar">
        <workspace-search-field shortcut="/"
          input-id="accountSearch"
          :model-value="accounts.searchKeyword.value"
          :placeholder="t('accounts.searchPlaceholder')"
          :aria-label="t('accounts.searchPlaceholder')"
          :clear-label="t('accounts.clearFilters')"
          :submit-mode="true"
          @search="accounts.searchKeyword.value=$event"
        ></workspace-search-field>
        <ui-toggle-group :model-value="accounts.roleFilter.value" :options="roleFilterOptions" :aria-label="t('accounts.filterRole')"
          @update:model-value="accounts.roleFilter.value=$event"></ui-toggle-group>
        <ui-toggle-group :model-value="accounts.enabledFilter.value" :options="accountStatusFilterOptions" :aria-label="t('accounts.filterStatus')"
          @update:model-value="accounts.enabledFilter.value=$event"></ui-toggle-group>
        <ui-toggle-group :model-value="accounts.resetFilter.value" :options="resetFilterOptions" :aria-label="t('accounts.filterResetRequested')"
          @update:model-value="accounts.resetFilter.value=$event"></ui-toggle-group>
      </div>
      <ui-filter-chip-list :items="accountFilterChips" :aria-label="t('common.activeFilters')"
        :clear-label="t('accounts.clearFilters')"
        @remove="removeFilterChip($event)"
        @clear="accounts.clearAccountFilters()"></ui-filter-chip-list>

      <div class="card card-table list-card">
        <ui-load-state v-if="loading.accountsError && !loading.accounts" kind="error" icon="bi-cloud-slash" :title="t('accounts.loadFailed')" has-action>
          <template #action><ui-button variant="secondary" size="compact" @click="accounts.loadAccounts()"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</ui-button></template>
        </ui-load-state>
        <div v-else class="card-table-body">
          <div v-if="loading.accounts && !list.length" class="list-skeleton" role="status" :aria-label="t('common.loading')">
            <div v-for="i in 6" :key="'sk-acc-'+i" class="list-skeleton__row"><span class="sk sk-w-15p"></span><span class="sk sk-w-38"></span><span class="sk sk-w-15p"></span></div>
          </div>
          <table v-else-if="list.length" class="data-table accounts-table">
            <thead><tr>
              <th class="col-user" :aria-sort="sortState('username')"><ui-table-sort-header :label="t('accounts.thUsername')" :active="sort.field==='username'" :ascending="sort.asc" @toggle="accounts.toggleAccountSort('username')"></ui-table-sort-header></th>
              <th class="col-role" :aria-sort="sortState('role')"><ui-table-sort-header :label="t('accounts.thRole')" :active="sort.field==='role'" :ascending="sort.asc" @toggle="accounts.toggleAccountSort('role')"></ui-table-sort-header></th>
              <th class="col-status" :aria-sort="sortState('enabled')"><ui-table-sort-header :label="t('accounts.thEnabled')" :active="sort.field==='enabled'" :ascending="sort.asc" @toggle="accounts.toggleAccountSort('enabled')"></ui-table-sort-header></th>
              <th class="col-created" :aria-sort="sortState('createdAt')"><ui-table-sort-header :label="t('accounts.thCreatedAt')" :active="sort.field==='createdAt'" :ascending="sort.asc" @toggle="accounts.toggleAccountSort('createdAt')"></ui-table-sort-header></th>
              <th class="col-login" :aria-sort="sortState('lastLoginAt')"><ui-table-sort-header :label="t('accounts.thLastLoginAt')" :active="sort.field==='lastLoginAt'" :ascending="sort.asc" @toggle="accounts.toggleAccountSort('lastLoginAt')"></ui-table-sort-header></th>
              <th class="col-actions"><span class="visually-hidden">{{t('accounts.thActions')}}</span></th>
            </tr></thead>
            <tbody>
              <tr v-for="a in list" :key="a.id" data-detail-row tabindex="0"
                :class="{'is-selected': selectedAccountId===a.id, 'is-disabled': !a.enabled}" :aria-selected="selectedAccountId===a.id ? 'true' : 'false'"
                @click="selectAccount(a)" @keydown="onRowKeydown($event, a)">
                <td class="col-user">
                  <span class="account-name">
                    <span class="account-avatar" aria-hidden="true">{{initial(a)}}</span>
                    <span class="record-name__title">{{a.username}}</span>
                    <ui-badge v-if="a.passwordResetRequested" tone="warning" class="account-flag"><i class="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>{{t('accounts.forgotPasswordBadge')}}</ui-badge>
                  </span>
                </td>
                <td class="col-role"><ui-badge :tone="a.role==='ROLE_ADMIN' ? 'accent' : 'neutral'">{{roleLabel(a.role)}}</ui-badge></td>
                <td class="col-status"><ui-status :tone="a.enabled ? 'success' : 'neutral'">{{a.enabled ? t('accounts.enabled') : t('accounts.disabled')}}</ui-status></td>
                <td class="col-created cell-mono cell-subtle" :title="fmtTime(a.createdAt,false)">{{fmtTime(a.createdAt)}}</td>
                <td class="col-login cell-subtle" :class="{'cell-mono': a.lastLoginAt}" :title="a.lastLoginAt ? fmtTime(a.lastLoginAt,false) : t('accounts.neverLoggedIn')">{{a.lastLoginAt ? fmtTime(a.lastLoginAt) : t('accounts.neverLoggedIn')}}</td>
                <td class="col-actions" @click.stop @dblclick.stop>
                  <span class="row-actions"><ui-row-menu :items="accountMenuItems(a)" :label="t('common.moreActions') + ' ' + a.username" @select="handleAccountMenu($event, a)"></ui-row-menu></span>
                </td>
              </tr>
            </tbody>
          </table>
          <ui-load-state v-else kind="empty" has-action
            :icon="hasActiveFilters ? 'bi-search' : 'bi-people'"
            :title="hasActiveFilters ? t('accounts.emptySearch') : t('accounts.emptyTitle')"
            :hint="hasActiveFilters ? t('accounts.emptySearchHint') : t('accounts.emptyHint')">
            <template #action>
              <ui-button v-if="hasActiveFilters" variant="secondary" size="compact" @click="accounts.clearAccountFilters()">{{t('accounts.clearFilters')}}</ui-button>
              <ui-button v-else variant="primary" size="compact" @click="openCreateModal()"><i class="bi bi-person-plus" aria-hidden="true"></i>{{t('accounts.addAccount')}}</ui-button>
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
          </template>
        </workspace-pagination>
      </div>

      <ui-detail-drawer class="account-detail-drawer" :open="!!selectedAccount" :title="selectedAccount ? selectedAccount.username : ''"
        :subtitle="selectedAccount ? '#' + selectedAccount.id : ''"
        :has-prev="selectedIndex > 0" :has-next="selectedIndex >= 0 && selectedIndex < list.length - 1"
        @close="closeDetail()" @prev="stepDetail(-1)" @next="stepDetail(1)">
        <template v-if="selectedAccount" #meta>
          <ui-badge :tone="selectedAccount.role==='ROLE_ADMIN' ? 'accent' : 'neutral'">{{roleLabel(selectedAccount.role)}}</ui-badge>
          <ui-status :tone="selectedAccount.enabled ? 'success' : 'neutral'">{{selectedAccount.enabled ? t('accounts.enabled') : t('accounts.disabled')}}</ui-status>
        </template>
        <template v-if="selectedAccount" #actions>
          <ui-button variant="secondary" size="compact" @click="handleResetPassword(selectedAccount)"><i class="bi bi-key" aria-hidden="true"></i>{{t('accounts.resetPassword')}}</ui-button>
          <ui-button v-if="selectedAccount.enabled" variant="secondary" size="compact" @click="accounts.disableAccount(selectedAccount)"><i class="bi bi-pause-circle" aria-hidden="true"></i>{{t('accounts.disable')}}</ui-button>
          <ui-button v-else variant="secondary" size="compact" @click="accounts.enableAccount(selectedAccount)"><i class="bi bi-play-circle" aria-hidden="true"></i>{{t('accounts.enable')}}</ui-button>
          <ui-row-menu :items="[{ key: 'delete', label: t('accounts.delete'), icon: 'bi-trash', danger: true }]" :label="t('common.moreActions')"
            @select="handleAccountMenu($event, selectedAccount)"></ui-row-menu>
        </template>
        <template v-if="selectedAccount">
          <div v-if="selectedAccount.passwordResetRequested" class="detail-notice is-warning" role="note">
            <i class="bi bi-exclamation-triangle" aria-hidden="true"></i>
            <span>{{selectedAccount.passwordResetRequestedAt
              ? t('accounts.resetRequestedAt', {time: fmtTime(selectedAccount.passwordResetRequestedAt, false)})
              : t('accounts.resetRequested')}}</span>
          </div>
          <ui-detail-section id="account.info" :title="t('accounts.detailInfo')">
            <dl class="detail-grid">
              <dt>{{t('accounts.thLastLoginAt')}}</dt>
              <dd :class="{'detail-mono': selectedAccount.lastLoginAt}">{{selectedAccount.lastLoginAt ? fmtTime(selectedAccount.lastLoginAt, false) : t('accounts.neverLoggedIn')}}</dd>
              <dt>{{t('accounts.thCreatedAt')}}</dt><dd class="detail-mono">{{fmtTime(selectedAccount.createdAt, false)}}</dd>
              <template v-if="selectedAccount.updatedAt"><dt>{{t('accounts.thUpdatedAt')}}</dt><dd class="detail-mono">{{fmtTime(selectedAccount.updatedAt, false)}}</dd></template>
              <template v-if="selectedAccount.forceChangePassword"><dt>{{t('accounts.password')}}</dt><dd>{{t('accounts.mustChangePassword')}}</dd></template>
            </dl>
          </ui-detail-section>
        </template>
      </ui-detail-drawer>

      <!-- Create Account Modal -->
      <ui-modal-transition>
      <div ref="createAccountOverlay" v-if="showCreateModal" class="modal-overlay" @keydown="handleDialogKeydown($event, () => $refs.createAccountDialog, closeCreateModal)">
        <div ref="createAccountDialog" class="modal-box workspace-modal account-modal" role="dialog" aria-modal="true" aria-labelledby="createAccountTitle" tabindex="-1">
          <div class="modal-header">
            <div class="modal-heading"><h2 id="createAccountTitle">{{t('accounts.createTitle')}}</h2></div>
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
            <ui-button type="button" variant="primary" @click="submitCreate()" :disabled="creating"><i class="bi" :class="creating?'bi-arrow-clockwise spin':'bi-person-plus'" aria-hidden="true"></i>{{t('modal.create')}}</ui-button>
          </div>
        </div>
      </div>
      </ui-modal-transition>

      <!-- Temp Password Modal -->
      <ui-modal-transition>
      <div ref="tempPasswordOverlay" v-if="showTempPasswordModal" class="modal-overlay" @keydown="handleDialogKeydown($event, () => $refs.tempPasswordDialog, closeTempPasswordModal)">
        <div ref="tempPasswordDialog" class="modal-box workspace-modal account-modal" role="dialog" aria-modal="true" aria-labelledby="tempPasswordTitle" tabindex="-1">
          <div class="modal-header">
            <div class="modal-heading"><h2 id="tempPasswordTitle">{{t('accounts.tempPasswordTitle')}}</h2></div>
            <ui-button type="button" class="close-btn" @click="closeTempPasswordModal()" :aria-label="t('modal.cancel')"><i class="bi bi-x-lg" aria-hidden="true"></i></ui-button>
          </div>
          <div class="modal-body temp-password-body">
            <p class="temp-password-message">{{t('accounts.tempPasswordMsg')}}</p>
            <div class="temp-password-field">
              <code class="temp-password-value">{{tempPassword}}</code>
              <ui-button type="button" variant="secondary" class="temp-password-copy" @click="copyTempPassword"><ui-motion-icon :icon="passwordCopied?'bi-check2':'bi-clipboard'"></ui-motion-icon>{{passwordCopied?t('accounts.passwordCopied'):t('accounts.copyPassword')}}</ui-button>
            </div>
            <p class="temp-password-note"><i class="bi bi-exclamation-circle" aria-hidden="true"></i>{{t('accounts.tempPasswordOnce')}}</p>
          </div>
          <div class="modal-footer">
            <ui-button ref="tempPasswordDone" type="button" variant="primary" @click="closeTempPasswordModal()">{{t('accounts.done')}}</ui-button>
          </div>
        </div>
      </div>
      </ui-modal-transition>
    </div>
  `
};
