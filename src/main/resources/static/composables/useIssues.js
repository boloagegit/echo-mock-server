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
    const { ref, computed, watch } = Vue;
    const { showToast, showConfirm, t, requireLogin, loading, isAdmin } = deps;

    // --- 資料快取 ---
    const dataLastLoaded = { issues: 0 };
    const DATA_TTL = 30000;
    const shouldLoad = () => !dataLastLoaded.issues || (Date.now() - dataLastLoaded.issues > DATA_TTL);
    const markLoaded = () => { dataLastLoaded.issues = Date.now(); };

    // --- 狀態 ---
    const issues = ref([]);
    const issueFilter = ref({ status: '', keyword: '' });
    const issueSort = ref({ field: 'createdAt', asc: false });
    const issuePage = ref(1);
    const issuePageSize = ref(20);
    const issueTotalElements = ref(0);
    const issueServerTotalPages = ref(0);
    const openCount = ref(0);
    let listRequestSequence = 0;
    let listAbortController = null;

    // --- 篩選與分頁 ---
    const filteredIssues = computed(() => issues.value);
    const pagedIssues = computed(() => issues.value);
    const issueTotalPages = computed(() => Math.max(1, issueServerTotalPages.value));

    const buildIssueQuery = () => {
        const params = new URLSearchParams();
        params.set('page', String(Math.max(0, issuePage.value - 1)));
        params.set('size', String(issuePageSize.value));
        params.set('sort', issueSort.value.field);
        params.set('direction', issueSort.value.asc ? 'asc' : 'desc');
        if (issueFilter.value.status) { params.set('status', issueFilter.value.status); }
        const keyword = (issueFilter.value.keyword || '').trim();
        if (keyword) { params.set('keyword', keyword); }
        return '/api/admin/issues/page?' + params.toString();
    };

    const toggleIssueSort = field => {
        issueSort.value = issueSort.value.field === field
            ? { field, asc: !issueSort.value.asc }
            : { field, asc: true };
    };

    const issueSortIcon = field => issueSort.value.field === field
        ? (issueSort.value.asc ? 'bi-caret-up-fill' : 'bi-caret-down-fill')
        : 'bi-arrow-down-up';

    // --- 載入 ---
    const loadIssues = async (force) => {
        if (!force && !shouldLoad()) { return; }
        const requestId = ++listRequestSequence;
        if (listAbortController) { listAbortController.abort(); }
        const abortController = new AbortController();
        listAbortController = abortController;
        loading.value.issues = true;
        loading.value.issuesError = '';
        try {
            const r = await apiCall(buildIssueQuery(), { signal: abortController.signal }, { silent: true });
            if (requestId !== listRequestSequence) { return false; }
            if (r && r.ok) {
                const data = await r.json();
                issueTotalElements.value = Number(data.totalElements || 0);
                issueServerTotalPages.value = Number(data.totalPages || 0);
                if (issueServerTotalPages.value > 0 && issuePage.value > issueServerTotalPages.value) {
                    issuePage.value = issueServerTotalPages.value;
                    return false;
                }
                issues.value = data.results || [];
                openCount.value = Number(data.openCount || 0);
                markLoaded();
                return true;
            }
            loading.value.issuesError = t('issues.loadFailed');
            return false;
        } finally {
            if (requestId === listRequestSequence) {
                if (listAbortController === abortController) { listAbortController = null; }
                loading.value.issues = false;
            }
        }
    };

    const reloadIssuesFromFirstPage = () => {
        if (issuePage.value !== 1) { issuePage.value = 1; }
        else { loadIssues(true); }
    };

    watch(issueFilter, reloadIssuesFromFirstPage, { deep: true });
    watch(issueSort, reloadIssuesFromFirstPage, { deep: true });
    watch(issuePage, () => loadIssues(true));
    watch(issuePageSize, reloadIssuesFromFirstPage);

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
        issueSort,
        issuePage,
        issuePageSize,
        issueTotalElements,
        filteredIssues,
        pagedIssues,
        issueTotalPages,
        openCount,
        toggleIssueSort,
        issueSortIcon,
        loadIssues,
        createIssue,
        replyIssue,
        resolveIssue,
        reopenIssue,
        deleteIssue
    };
};
