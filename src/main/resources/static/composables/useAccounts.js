/**
 * useAccounts - 帳號管理 Composable
 */
const useAccounts = (deps) => {
    const { ref, computed, watch } = Vue;
    const { showToast, showConfirm, t, requireLogin, login, loading } = deps;

    const errorCodeMap = {
        'USERNAME_EXISTS': 'toast.usernameExists',
        'USERNAME_LENGTH': 'toast.usernameLengthError',
        'PASSWORD_TOO_SHORT': 'toast.passwordLengthError',
        'OLD_PASSWORD_INCORRECT': 'toast.oldPasswordIncorrect',
        'CANNOT_DELETE_ONLY_ADMIN': 'toast.cannotDeleteOnlyAdmin',
        'ACCOUNT_DISABLED': 'login.accountDisabled'
    };

    const accounts = ref([]);
    const searchKeyword = ref('');
    const roleFilter = ref('');
    const enabledFilter = ref('');
    const resetFilter = ref('');
    const accountSort = ref({ field: 'username', asc: true });
    const accountPage = ref(1);
    const accountPageSize = ref(20);
    const accountTotalElements = ref(0);
    const accountServerTotalPages = ref(0);
    let listRequestSequence = 0;
    let listAbortController = null;

    const filteredAccounts = computed(() => accounts.value);
    const accountTotalPages = computed(() => Math.max(1, accountServerTotalPages.value));

    const buildAccountQuery = () => {
        const params = new URLSearchParams();
        params.set('page', String(Math.max(0, accountPage.value - 1)));
        params.set('size', String(accountPageSize.value));
        params.set('sort', accountSort.value.field);
        params.set('direction', accountSort.value.asc ? 'asc' : 'desc');
        const keyword = searchKeyword.value.trim();
        if (keyword) { params.set('keyword', keyword); }
        if (roleFilter.value) { params.set('role', roleFilter.value); }
        if (enabledFilter.value !== '') { params.set('enabled', enabledFilter.value); }
        if (resetFilter.value !== '') { params.set('passwordResetRequested', resetFilter.value); }
        return '/api/admin/builtin-users/page?' + params.toString();
    };

    const toggleAccountSort = field => {
        accountSort.value = accountSort.value.field === field
            ? { field, asc: !accountSort.value.asc }
            : { field, asc: true };
    };

    const accountSortIcon = field => accountSort.value.field === field
        ? (accountSort.value.asc ? 'bi-caret-up-fill' : 'bi-caret-down-fill')
        : 'bi-arrow-down-up';

    const clearAccountFilters = () => {
        searchKeyword.value = '';
        roleFilter.value = '';
        enabledFilter.value = '';
        resetFilter.value = '';
    };

    const resolveError = async (r, fallbackKey) => {
        try {
            const body = await r.json();
            const code = body.error;
            return (code && errorCodeMap[code]) ? t(errorCodeMap[code]) : (body.error || t(fallbackKey));
        } catch { return t(fallbackKey); }
    };

    /** 通用 API 操作：登入檢查 → 可選確認 → 呼叫 → toast → reload */
    const exec = async (url, opts, successKey, failKey, confirm) => {
        if (!await requireLogin()) { return null; }
        if (confirm && !await showConfirm(confirm)) { return null; }
        const r = await apiCall(url, opts, { silent: true });
        if (r && r.ok) {
            showToast(t(successKey), 'success');
            await loadAccounts();
            return r;
        }
        if (r) {
            showToast(await resolveError(r, failKey), 'error');
            if (r.status === 401 || r.status === 403) { login(); }
        }
        return null;
    };

    const loadAccounts = async () => {
        const requestId = ++listRequestSequence;
        if (listAbortController) { listAbortController.abort(); }
        const abortController = new AbortController();
        listAbortController = abortController;
        loading.value.accounts = true;
        loading.value.accountsError = '';
        try {
            const r = await apiCall(buildAccountQuery(), { signal: abortController.signal }, { silent: true });
            if (requestId !== listRequestSequence) { return false; }
            if (r && r.ok) {
                const data = await r.json();
                if (requestId !== listRequestSequence) { return false; }
                accountTotalElements.value = Number(data.totalElements || 0);
                accountServerTotalPages.value = Number(data.totalPages || 0);
                if (accountServerTotalPages.value > 0 && accountPage.value > accountServerTotalPages.value) {
                    accountPage.value = accountServerTotalPages.value;
                    return false;
                }
                accounts.value = data.results || [];
                return true;
            }
            loading.value.accountsError = t('accounts.loadFailed');
            return false;
        } finally {
            if (requestId === listRequestSequence) {
                if (listAbortController === abortController) { listAbortController = null; }
                loading.value.accounts = false;
            }
        }
    };

    const reloadAccountsFromFirstPage = () => {
        if (accountPage.value !== 1) { accountPage.value = 1; }
        else { loadAccounts(); }
    };

    watch([searchKeyword, roleFilter, enabledFilter, resetFilter], reloadAccountsFromFirstPage);
    watch(accountSort, reloadAccountsFromFirstPage, { deep: true });
    watch(accountPage, loadAccounts);
    watch(accountPageSize, reloadAccountsFromFirstPage);

    const createAccount = async (username, password) => {
        const r = await exec('/api/admin/builtin-users',
            { method: 'POST', body: JSON.stringify({ username, password }) },
            'toast.accountCreated', 'toast.accountCreateFailed');
        return !!r;
    };

    const deleteAccount = async (account) => {
        const r = await exec(`/api/admin/builtin-users/${account.id}`,
            { method: 'DELETE' },
            'toast.accountDeleted', 'toast.accountDeleteFailed',
            { title: t('confirm.deleteAccount'), message: t('confirm.deleteAccountMsg'), confirmText: t('accounts.delete'), danger: true });
        return !!r;
    };

    const enableAccount = async (account) => {
        const r = await exec(`/api/admin/builtin-users/${account.id}/enable`,
            { method: 'PUT' },
            'toast.accountEnabled', 'toast.accountStatusFailed',
            { title: t('confirm.enableAccount'), message: t('confirm.enableAccountMsg') });
        return !!r;
    };

    const disableAccount = async (account) => {
        const r = await exec(`/api/admin/builtin-users/${account.id}/disable`,
            { method: 'PUT' },
            'toast.accountDisabled', 'toast.accountStatusFailed',
            { title: t('confirm.disableAccount'), message: t('confirm.disableAccountMsg') });
        return !!r;
    };

    const resetPassword = async (account) => {
        if (!await requireLogin()) { return null; }
        if (!await showConfirm({
            title: t('confirm.resetPasswordTitle'),
            message: t('confirm.resetPasswordMsg'),
            confirmText: t('accounts.resetPassword')
        })) { return null; }
        const r = await apiCall(`/api/admin/builtin-users/${account.id}/reset-password`, { method: 'POST' }, { silent: true });
        if (r && r.ok) {
            const data = await r.json();
            showToast(t('toast.passwordResetSuccess'), 'success');
            await loadAccounts();
            return data.tempPassword;
        }
        if (r) {
            showToast(await resolveError(r, 'toast.passwordResetFailed'), 'error');
            if (r.status === 401 || r.status === 403) { login(); }
        }
        return null;
    };

    return {
        accounts, searchKeyword, roleFilter, enabledFilter, resetFilter,
        accountSort, accountPage, accountPageSize, accountTotalElements, accountTotalPages,
        filteredAccounts, toggleAccountSort, accountSortIcon, clearAccountFilters,
        loadAccounts, createAccount, deleteAccount, enableAccount, disableAccount, resetPassword
    };
};
