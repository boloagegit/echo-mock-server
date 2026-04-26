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
      creating: false,
      showTempPasswordModal: false,
      tempPassword: ''
    };
  },
  methods: {
    fmtTime,
    openCreateModal() {
      this.createForm = { username: '', password: '' };
      this.showCreateModal = true;
    },
    closeCreateModal() {
      this.showCreateModal = false;
    },
    async submitCreate() {
      if (this.creating) { return; }
      this.creating = true;
      const ok = await this.accounts.createAccount(this.createForm.username, this.createForm.password);
      this.creating = false;
      if (ok) {
        this.showCreateModal = false;
      }
    },
    async handleResetPassword(account) {
      const pwd = await this.accounts.resetPassword(account);
      if (pwd) {
        this.tempPassword = pwd;
        this.showTempPasswordModal = true;
      }
    },
    closeTempPasswordModal() {
      this.tempPassword = '';
      this.showTempPasswordModal = false;
    }
  },
  template: /* html */`
    <div class="page" :class="{active:true}">
      <div class="page-header">
        <h1 class="page-title">{{t('accounts.title')}}</h1>
        <div style="display:flex;gap:0.75rem;align-items:center;flex-wrap:wrap">
          <button class="btn btn-secondary" @click="accounts.loadAccounts()" :disabled="loading.accounts"><i class="bi bi-arrow-clockwise" :class="{'spin':loading.accounts}"></i> {{t('accounts.refresh')}}</button>
          <button class="btn btn-primary" @click="openCreateModal()"><i class="bi bi-plus-lg"></i> {{t('accounts.addAccount')}}</button>
        </div>
      </div>

      <!-- Search -->
      <div class="card" style="margin-bottom:0.5rem">
        <div class="card-body filter-row">
          <input v-model="accounts.searchKeyword.value" :placeholder="t('accounts.searchPlaceholder')" class="form-control" style="flex:1;min-width:200px">
        </div>
      </div>

      <!-- Table -->
      <div class="card card-table">
        <div class="card-table-body">
          <div v-if="loading.accounts && !accounts.filteredAccounts.value.length">
            <div v-for="i in 4" :key="'sk-acc-'+i" class="sk-row">
              <span class="sk sk-text" style="width:120px"></span>
              <span class="sk sk-badge" style="width:60px"></span>
              <span class="sk sk-badge" style="width:50px"></span>
              <span class="sk sk-text-sm" style="width:100px"></span>
              <span class="sk sk-text-sm" style="width:100px"></span>
              <span class="sk sk-btn"></span>
            </div>
          </div>
          <table v-if="accounts.filteredAccounts.value.length" class="table-fixed">
            <thead><tr>
              <th>{{t('accounts.thUsername')}}</th>
              <th style="width:80px">{{t('accounts.thRole')}}</th>
              <th class="col-hide-sm" style="width:80px">{{t('accounts.thEnabled')}}</th>
              <th class="col-hide-md" style="width:110px">{{t('accounts.thCreatedAt')}}</th>
              <th class="col-hide-md" style="width:110px">{{t('accounts.thLastLoginAt')}}</th>
              <th class="col-actions col-actions-3">{{t('accounts.thActions')}}</th>
            </tr></thead>
            <tbody>
              <tr v-for="a in accounts.filteredAccounts.value" :key="a.id">
                <td>
                  <span style="font-weight:500">{{a.username}}</span>
                  <span v-if="a.passwordResetRequested" class="badge badge-warning" style="margin-left:6px"><i class="bi bi-exclamation-triangle-fill"></i> {{t('accounts.forgotPasswordBadge')}}</span>
                </td>
                <td><span class="badge" :class="a.role==='ROLE_ADMIN'?'badge-http':'badge-muted'">{{a.role==='ROLE_ADMIN'?t('accounts.roleAdmin'):t('accounts.roleUser')}}</span></td>
                <td class="col-hide-sm"><span class="badge" :class="a.enabled?'badge-success':'badge-danger'">{{a.enabled?t('accounts.enabled'):t('accounts.disabled')}}</span></td>
                <td class="col-hide-md"><span class="sub-info" :title="fmtTime(a.createdAt,false)">{{fmtTime(a.createdAt)}}</span></td>
                <td class="col-hide-md"><span class="sub-info" :title="fmtTime(a.lastLoginAt,false)">{{fmtTime(a.lastLoginAt)}}</span></td>
                <td class="col-actions col-actions-3">
                  <div style="display:flex;gap:0.25rem">
                    <button v-if="a.enabled" class="btn btn-sm btn-icon btn-secondary" :title="t('accounts.disable')" @click="accounts.disableAccount(a)"><i class="bi bi-pause-circle"></i></button>
                    <button v-else class="btn btn-sm btn-icon btn-secondary" :title="t('accounts.enable')" @click="accounts.enableAccount(a)"><i class="bi bi-play-circle"></i></button>
                    <button class="btn btn-sm btn-icon btn-secondary" :title="t('accounts.resetPassword')" @click="handleResetPassword(a)"><i class="bi bi-key"></i></button>
                    <button class="btn btn-sm btn-icon btn-danger" :title="t('accounts.delete')" @click="accounts.deleteAccount(a)"><i class="bi bi-trash"></i></button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="!accounts.filteredAccounts.value.length && !loading.accounts" class="empty"><i class="bi bi-people"></i><div>{{t('accounts.title')}}</div></div>
        </div>
      </div>

      <!-- Create Account Modal -->
      <div v-if="showCreateModal" class="modal-overlay" @click.self="closeCreateModal()">
        <div class="modal-box" style="max-width:420px">
          <div class="modal-header">
            <h3>{{t('accounts.createTitle')}}</h3>
            <button class="close-btn" @click="closeCreateModal()"><i class="bi bi-x-lg"></i></button>
          </div>
          <div class="modal-body">
            <div class="form-group" style="margin-bottom:1rem">
              <label class="form-label">{{t('accounts.username')}}</label>
              <input v-model="createForm.username" class="form-control" :placeholder="t('accounts.usernamePlaceholder')" @keydown.enter="submitCreate()">
            </div>
            <div class="form-group">
              <label class="form-label">{{t('accounts.password')}}</label>
              <input v-model="createForm.password" type="password" class="form-control" :placeholder="t('accounts.passwordPlaceholder')" @keydown.enter="submitCreate()">
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn btn-secondary" @click="closeCreateModal()">{{t('modal.cancel')}}</button>
            <button class="btn btn-primary" @click="submitCreate()" :disabled="creating || !createForm.username || !createForm.password">{{t('modal.create')}}</button>
          </div>
        </div>
      </div>

      <!-- Temp Password Modal -->
      <div v-if="showTempPasswordModal" class="modal-overlay" @click.self="closeTempPasswordModal()">
        <div class="modal-box" style="max-width:420px">
          <div class="modal-header">
            <h3>{{t('accounts.tempPasswordTitle')}}</h3>
            <button class="close-btn" @click="closeTempPasswordModal()"><i class="bi bi-x-lg"></i></button>
          </div>
          <div class="modal-body">
            <p style="margin-bottom:0.75rem;color:var(--muted)">{{t('accounts.tempPasswordMsg')}}</p>
            <div style="display:flex;align-items:center;gap:0.5rem">
              <code style="font-size:1.25rem;font-weight:600;padding:0.5rem 1rem;background:var(--bg-secondary);border-radius:6px;flex:1;text-align:center;letter-spacing:1px">{{tempPassword}}</code>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn btn-primary" @click="closeTempPasswordModal()">{{t('confirm.ok')}}</button>
          </div>
        </div>
      </div>
    </div>
  `
};
