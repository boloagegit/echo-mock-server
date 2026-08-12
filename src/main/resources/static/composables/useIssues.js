/**
 * useIssues - Issue Report Composable
 *
 * 管理 Issue Report 的載入、篩選、建立、回覆、狀態變更。
 *
 * @param {Object} deps - 依賴物件
 * @param {Function} deps.showToast - Toast 通知函式
 * @param {Function} deps.showConfirm - 確認對話框函式
 * @param {Function} deps.t - 翻譯函式
 * @param {Function} deps.requireLogin - 登入檢查函式
 * @param {Ref} deps.loading - 全域 loading 狀態
 * @param {Ref} deps.isAdmin - 是否為管理員
 */
const useIssues = (deps) => {
    const { ref, computed } = Vue;
    const { showToast, showConfirm, t, requireLogin, loading, isAdmin } = deps;

    // --- 資料快取 ---
    const dataLastLoaded = { issues: 0 };
    const DATA_TTL = 30000;
    const shouldLoad = () => !dataLastLoaded.issues || (Date.now() - dataLastLoaded.issues > DATA_TTL);
    const markLoaded = () => { dataLastLoaded.issues = Date.now(); };

    // --- 狀態 ---
    const issues = ref([]);
    const issueFilter = ref({ status: '' });
    const issuePage = ref(1);
    const issuePageSize = ref(20);

    // --- 篩選與分頁 ---
    const filteredIssues = computed(() => {
        let arr = issues.value;
        if (issueFilter.value.status) {
            arr = arr.filter(i => i.status === issueFilter.value.status);
        }
        return arr;
    });

    const pagedIssues = computed(() => {
        const start = (issuePage.value - 1) * issuePageSize.value;
        return filteredIssues.value.slice(start, start + issuePageSize.value);
    });

    const issueTotalPages = computed(() => Math.ceil(filteredIssues.value.length / issuePageSize.value) || 1);

    const openCount = computed(() => issues.value.filter(i => i.status === 'OPEN').length);

    // --- 載入 ---
    const loadIssues = async (force) => {
        if (!force && !shouldLoad()) { return; }
        loading.value.issues = true;
        loading.value.issuesError = '';
        const r = await apiCall('/api/admin/issues', {}, { silent: true });
        if (r && r.ok) {
            issues.value = await r.json();
            issuePage.value = 1;
            markLoaded();
        } else { loading.value.issuesError = t('issues.loadFailed'); }
        loading.value.issues = false;
    };

    // --- 建立 ---
    const createIssue = async (title, description) => {
        if (!await requireLogin()) { return false; }
        const r = await apiCall('/api/admin/issues', {
            method: 'POST',
            body: JSON.stringify({ title, description })
        }, { silent: true });
        if (r && r.ok) {
            showToast(t('issues.createSuccess'), 'success');
            await loadIssues(true);
            return true;
        }
        if (r) {
            const body = await r.json().catch(() => ({}));
            showToast(body.error || t('issues.createFailed'), 'error');
        }
        return false;
    };

    // --- Admin 操作 ---
    const replyIssue = async (id, reply) => {
        if (!await requireLogin()) { return false; }
        const r = await apiCall(`/api/admin/issues/${id}/reply`, {
            method: 'PUT',
            body: JSON.stringify({ reply })
        }, { silent: true });
        if (r && r.ok) {
            showToast(t('issues.replySuccess'), 'success');
            await loadIssues(true);
            return true;
        }
        if (r) { showToast(t('issues.replyFailed'), 'error'); }
        return false;
    };

    const resolveIssue = async (id) => {
        if (!await requireLogin()) { return false; }
        if (!await showConfirm({ title: t('issues.confirmResolve'), message: t('issues.confirmResolveMsg') })) { return false; }
        const r = await apiCall(`/api/admin/issues/${id}/resolve`, { method: 'PUT' }, { silent: true });
        if (r && r.ok) {
            showToast(t('issues.resolveSuccess'), 'success');
            await loadIssues(true);
            return true;
        }
        if (r) { showToast(t('issues.resolveFailed'), 'error'); }
        return false;
    };

    const reopenIssue = async (id) => {
        if (!await requireLogin()) { return false; }
        const r = await apiCall(`/api/admin/issues/${id}/reopen`, { method: 'PUT' }, { silent: true });
        if (r && r.ok) {
            showToast(t('issues.reopenSuccess'), 'success');
            await loadIssues(true);
            return true;
        }
        if (r) { showToast(t('issues.reopenFailed'), 'error'); }
        return false;
    };

    const deleteIssue = async (id) => {
        if (!await requireLogin()) { return false; }
        if (!await showConfirm({ title: t('issues.confirmDelete'), message: t('issues.confirmDeleteMsg'), confirmText: t('issues.delete'), danger: true })) { return false; }
        const r = await apiCall(`/api/admin/issues/${id}`, { method: 'DELETE' }, { silent: true });
        if (r && r.ok) {
            showToast(t('issues.deleteSuccess'), 'success');
            await loadIssues(true);
            return true;
        }
        if (r) { showToast(t('issues.deleteFailed'), 'error'); }
        return false;
    };

    return {
        issues,
        issueFilter,
        issuePage,
        issuePageSize,
        filteredIssues,
        pagedIssues,
        issueTotalPages,
        openCount,
        loadIssues,
        createIssue,
        replyIssue,
        resolveIssue,
        reopenIssue,
        deleteIssue
    };
};
