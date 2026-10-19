/**
 * useIssues - Issue Report Composable
 *
 * Manage the loading, filtering, creation, reply, and status changes of Issue Report.
 *
 * @param {Object} deps - Dependent objects
 * @param {Function} deps.showToast - Toast notification function
 * @param {Function} deps.showConfirm - Confirm dialog function
 * @param {Function} deps.t - Translation function
 * @param {Function} deps.requireLogin - Login check function
 * @param {Ref} deps.loading - Global loading status
 * @param {Ref} deps.isAdmin - Are you an administrator?
 * @param {Ref} deps.issueReportingEnabled - Whether problem reporting is enabled on the backend
 */
const useIssues = (deps) => {
    const { ref, computed, watch } = Vue;
    const { showToast, showConfirm, t, requireLogin, loading, isAdmin } = deps;
    const issueReportingEnabled = deps.issueReportingEnabled || ref(false);

    // --- Data cache ---
    const dataLastLoaded = { issues: 0 };
    const DATA_TTL = 30000;
    const shouldLoad = () => !dataLastLoaded.issues || (Date.now() - dataLastLoaded.issues > DATA_TTL);
    const markLoaded = () => { dataLastLoaded.issues = Date.now(); };

    // --- Status ---
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

    // --- Filtering and paging ---
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

    // --- Loading ---
    const loadIssues = async (force) => {
        if (!issueReportingEnabled.value) { return false; }
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
    watch(issueReportingEnabled, enabled => {
        if (enabled) { return; }
        listRequestSequence++;
        if (listAbortController) { listAbortController.abort(); listAbortController = null; }
        issues.value = [];
        openCount.value = 0;
        issueTotalElements.value = 0;
        issueServerTotalPages.value = 0;
        dataLastLoaded.issues = 0;
        loading.value.issues = false;
        loading.value.issuesError = '';
    });

    // --- Create ---
    const createIssue = async (title, description) => {
        if (!issueReportingEnabled.value) { return false; }
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

    // --- Admin Operation ---
    const replyIssue = async (id, reply) => {
        if (!issueReportingEnabled.value) { return false; }
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

    // Repeated clicks are ignored when actions for the same question are being processed.
    const inFlight = createInFlight();

    const resolveIssue = (id) => inFlight.run('resolve:' + id, async () => {
        if (!issueReportingEnabled.value) { return false; }
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
    });

    const reopenIssue = (id) => inFlight.run('reopen:' + id, async () => {
        if (!issueReportingEnabled.value) { return false; }
        if (!await requireLogin()) { return false; }
        const r = await apiCall(`/api/admin/issues/${id}/reopen`, { method: 'PUT' }, { silent: true });
        if (r && r.ok) {
            showToast(t('issues.reopenSuccess'), 'success');
            await loadIssues(true);
            return true;
        }
        if (r) { showToast(t('issues.reopenFailed'), 'error'); }
        return false;
    });

    const deleteIssue = (id) => inFlight.run('delete:' + id, async () => {
        if (!issueReportingEnabled.value) { return false; }
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
    });

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
