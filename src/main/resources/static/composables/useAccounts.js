/**
 * useAccounts - 帳號管理 Composable
 */
const useAccounts = (deps) => {
    const { ref, computed } = Vue;
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

    const filteredAccounts = computed(() => {
        const kw = searchKeyword.value.trim().toLowerCase();
        if (!kw) { return accounts.value; }
        return accounts.value.filter(a => a.username.toLowerCase().includes(kw));
    });

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
        loading.value.accounts = true;
        const r = await apiCall('/api/admin/builtin-users', {}, { silent: true });
        if (r && r.ok) { accounts.value = await r.json(); }
        loading.value.accounts = false;
    };

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
        if (!await showConfirm({ title: t('confirm.resetPasswordTitle'), message: t('confirm.resetPasswordMsg') })) { return null; }
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

    return { accounts, searchKeyword, filteredAccounts, loadAccounts, createAccount, deleteAccount, enableAccount, disableAccount, resetPassword };
};
