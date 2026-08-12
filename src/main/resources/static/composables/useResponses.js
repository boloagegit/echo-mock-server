/**
 * useResponses - 回應管理 Composable
 *
 * 管理回應（responses）的 CRUD、篩選、排序、分頁、批次操作，
 * 以及回應規則展開、匯出匯入等功能。
 *
 * @param {Object} deps - 依賴物件
 * @param {Function} deps.showToast - Toast 通知函式（來自 useToast）
 * @param {Function} deps.showConfirm - 確認對話框函式（來自 useToast）
 * @param {Function} deps.t - 翻譯函式（來自 useI18n）
 * @param {Function} deps.requireLogin - 登入檢查函式（來自 useAuth）
 * @returns {Object} 回應管理相關的狀態與方法
 */
const useResponses = (deps) => {
    const { ref, computed, watch } = Vue;
    const { showToast, showConfirm, t, requireLogin } = deps;

    // --- 資料快取機制 ---
    const dataLastLoaded = { responses: 0 };
    const dataDirty = { responses: false, options: false };
    const DATA_TTL = 30000;
    const shouldLoad = () => !dataLastLoaded.responses || dataDirty.responses || (Date.now() - dataLastLoaded.responses > DATA_TTL);
    const markLoaded = () => { dataLastLoaded.responses = Date.now(); dataDirty.responses = false; };
    const markDirty = () => { dataDirty.responses = true; dataDirty.options = true; };

    // --- 工具函式 ---
    // --- 狀態 ---
    const responseSummary = ref([]);
    const responseOptions = ref([]);
    const responseFilter = ref('');
    const savedResponseSort = JSON.parse(localStorage.getItem('responseSort') || 'null');
    const responseSort = ref(savedResponseSort || { field: 'updatedAt', asc: false });
    const responsePage = ref(1);
    const responsePageSize = ref(20);
    const responseUsageFilter = ref('');
    const responseContentTypeFilter = ref('');
    const responseTotalElements = ref(0);
    const serverResponseTotalPages = ref(0);
    let listRequestSequence = 0;
    let listAbortController = null;
    let responseOptionsLoadedAt = 0;

    // --- Modal 狀態 ---
    const showResponseModal = ref(false);
    const editingResponse = ref(null);
    const responseForm = ref({ description: '', body: '', contentType: 'text' });
    const responseSseEvents = ref([{ event: '', data: '', id: '', delayMs: 0, type: 'normal' }]);

    // --- 批次選取 ---
    const selectedResponses = ref([]);
    const batchSelectResponseMode = ref(false);

    // --- 規則展開快取 ---
    const responseRulesCache = {};

    // --- 匯出匯入 dropdown ---
    const showResponseDataDropdown = ref(false);

    // --- 篩選、排序、分頁 ---
    const filteredResponseSummary = computed(() => responseSummary.value);
    const pagedResponseSummary = computed(() => responseSummary.value);
    const responseTotalPages = computed(() => Math.max(1, serverResponseTotalPages.value));

    /** 切換排序欄位/方向 */
    const toggleResponseSort = f => {
        if (responseSort.value.field === f) responseSort.value.asc = !responseSort.value.asc;
        else { responseSort.value.field = f; responseSort.value.asc = false; }
        localStorage.setItem('responseSort', JSON.stringify(responseSort.value));
    };

    /** 取得排序圖示 class */
    const responseSortIcon = f => responseSort.value.field === f ? (responseSort.value.asc ? 'bi-caret-up-fill' : 'bi-caret-down-fill') : 'bi-arrow-down-up';

    /** 每頁筆數變更 */
    const onResponsePageSizeChange = () => {};

    // --- 載入 ---
    const buildResponseQuery = () => {
        const params = new URLSearchParams();
        params.set('page', String(Math.max(0, responsePage.value - 1)));
        params.set('size', String(responsePageSize.value));
        params.set('sort', responseSort.value.field);
        params.set('direction', responseSort.value.asc ? 'asc' : 'desc');
        const keyword = responseFilter.value.trim();
        if (keyword) { params.set('keyword', keyword); }
        if (responseUsageFilter.value) { params.set('usage', responseUsageFilter.value); }
        if (responseContentTypeFilter.value) { params.set('contentType', responseContentTypeFilter.value); }
        return '/api/admin/responses/summary?' + params.toString();
    };

    const loadResponseSummary = async (force) => {
        if (!force && !shouldLoad()) return;
        const requestId = ++listRequestSequence;
        if (listAbortController) { listAbortController.abort(); }
        const abortController = new AbortController();
        listAbortController = abortController;
        deps.loading.value.responses = true;
        try {
            const r = await apiCall(buildResponseQuery(), { signal: abortController.signal }, { errorMsg: t('toast.responseLoadFailed') });
            if (requestId !== listRequestSequence) { return false; }
            if (r && r.ok) {
                const data = await r.json();
                if (requestId !== listRequestSequence) { return false; }
                responseSummary.value = data.results || [];
                responseTotalElements.value = Number(data.totalElements || 0);
                serverResponseTotalPages.value = Number(data.totalPages || 0);
                if (responsePage.value > responseTotalPages.value) { responsePage.value = responseTotalPages.value; }
                markLoaded();
                Object.keys(responseRulesCache).forEach(k => delete responseRulesCache[k]);
            }
            return !!(r && r.ok);
        } finally {
            if (requestId === listRequestSequence) {
                if (listAbortController === abortController) { listAbortController = null; }
                deps.loading.value.responses = false;
            }
        }
    };

    const loadResponseOptions = async (force) => {
        if (!force && responseOptionsLoadedAt && Date.now() - responseOptionsLoadedAt <= DATA_TTL && !dataDirty.options) {
            return true;
        }
        const r = await apiCall('/api/admin/responses/summary', {}, { errorMsg: t('toast.responseLoadFailed') });
        if (r && r.ok) {
            responseOptions.value = await r.json();
            responseOptionsLoadedAt = Date.now();
            dataDirty.options = false;
            return true;
        }
        return false;
    };

    const reloadResponseFirstPage = () => {
        if (responsePage.value !== 1) { responsePage.value = 1; }
        else { loadResponseSummary(true); }
    };
    watch(responseFilter, reloadResponseFirstPage);
    watch(responseUsageFilter, reloadResponseFirstPage);
    watch(responseContentTypeFilter, reloadResponseFirstPage);
    watch(responseSort, reloadResponseFirstPage, { deep: true });
    watch(responsePage, () => loadResponseSummary(true));
    watch(responsePageSize, reloadResponseFirstPage);

    // --- Modal 操作 ---
    const openResponseModal = async (r) => {
        editingResponse.value = r;
        responseForm.value = r ? { description: r.description || '', body: '', contentType: 'text' } : { description: '', body: '', contentType: 'text' };
        responseSseEvents.value = [{ event: '', data: '', id: '', delayMs: 0, type: 'normal' }];
        if (r) {
            if (r.contentType === 'SSE') {
                responseForm.value.contentType = 'sse';
            }
            const res = await apiCall(`/api/admin/responses/${r.id}`, {}, { silent: true });
            if (res && res.ok) {
                const data = await res.json();
                responseForm.value.body = data.body || '';
                if (responseForm.value.contentType === 'sse') {
                    responseSseEvents.value = deserializeSseEvents(data.body);
                } else {
                    const parsed = deserializeSseEvents(data.body);
                    if (parsed.length && parsed[0].data) {
                        responseForm.value.contentType = 'sse';
                        responseSseEvents.value = parsed;
                    }
                }
            }
        }
        showResponseModal.value = true;
    };

    const saveResponse = async () => {
        if (deps.loading.value.responseSave) return;
        const payload = { description: responseForm.value.description, contentType: responseForm.value.contentType === 'sse' ? 'SSE' : null };
        if (responseForm.value.contentType === 'sse') {
            payload.body = serializeSseEvents(responseSseEvents.value);
        } else {
            payload.body = responseForm.value.body;
        }
        const url = editingResponse.value ? `/api/admin/responses/${editingResponse.value.id}` : '/api/admin/responses';
        deps.loading.value.responseSave = true;
        try {
            const r = await apiCall(url, { method: editingResponse.value ? 'PUT' : 'POST', body: JSON.stringify(payload) }, { errorMsg: t('toast.responseSaveFailed') });
            if (r && r.ok) {
                showToast(editingResponse.value ? t('toast.responseSaveSuccess') : t('toast.responseCreateSuccess'), 'success');
                showResponseModal.value = false;
                markDirty();
                if (deps.onResponseSaved) deps.onResponseSaved();
                loadResponseSummary(true);
            }
        } finally {
            deps.loading.value.responseSave = false;
        }
    };

    const deleteResponse = async (id, usageCount) => {
        const msg = usageCount > 0 ? t('confirm.deleteResponseUsedMsg', {count: usageCount}) : t('confirm.deleteResponseMsg');
        if (!await showConfirm({ title: t('confirm.deleteResponse'), message: msg, confirmText: t('confirm.delete'), danger: true })) return;
        const r = await apiCall(`/api/admin/responses/${id}`, { method: 'DELETE' }, { errorMsg: t('toast.responseDeleteFailed') });
        if (r && r.ok) {
            const d = await r.json();
            showToast(d.deletedRules > 0 ? t('toast.responseDeleteWithRules', {count: d.deletedRules}) : t('toast.responseDeleteSuccess'), 'success');
            markDirty();
            loadResponseSummary(true);
            if (d.deletedRules > 0 && deps.onRulesDirty) { deps.onRulesDirty(); }
        }
    };

    // --- 規則展開 ---
    const toggleResponseRules = async (r) => {
        if (r.expanded) { r.expanded = false; return; }
        if (responseRulesCache[r.id]) { r.rules = responseRulesCache[r.id]; r.expanded = true; return; }
        const res = await apiCall(`/api/admin/responses/${r.id}/rules`, {}, { silent: true });
        if (res && res.ok) { r.rules = await res.json(); responseRulesCache[r.id] = r.rules; r.expanded = true; }
    };

    // --- 批次操作 ---
    const toggleSelectAllResponses = e => { selectedResponses.value = e.target.checked ? pagedResponseSummary.value.map(r => r.id) : []; };

    const exportResponses = async () => {
        const r = await apiCall('/api/admin/responses/export', {}, { errorMsg: t('toast.exportFailed') });
        if (r && r.ok) { const data = await r.json(); const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }); const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = 'responses.json'; a.click(); }
    };

    const importResponses = async (e) => {
        if (!await requireLogin()) return;
        const file = e.target.files[0]; if (!file) return;
        const text = await file.text();
        try {
            const data = JSON.parse(text);
            const arr = Array.isArray(data) ? data : [data];
            const r = await apiCall('/api/admin/responses/import-batch', { method: 'POST', body: JSON.stringify(arr) }, { errorMsg: t('toast.responseImportFailed') });
            if (r && r.ok) { const d = await r.json(); showToast(t('toast.responseImportSuccess', {count: d.imported}), 'success'); markDirty(); loadResponseSummary(true); }
        } catch (err) { showToast(t('toast.responseFileFormatError'), 'error'); }
        e.target.value = '';
    };

    const deleteSelectedResponses = async () => {
        if (!await requireLogin()) return;
        const linkedRules = selectedResponses.value.reduce((sum, id) => {
            const r = responseSummary.value.find(rs => rs.id === id);
            return sum + (r ? r.usageCount : 0);
        }, 0);
        const msg = linkedRules
            ? t('confirm.batchDeleteResponsesMsg', {count: selectedResponses.value.length}) + '\n\n' + t('confirm.batchDeleteResponsesCascadeWarn', {count: linkedRules})
            : t('confirm.batchDeleteResponsesMsg', {count: selectedResponses.value.length});
        if (!await showConfirm({ title: t('confirm.batchDeleteResponses'), message: msg, confirmText: t('confirm.delete'), danger: true })) return;
        const r = await apiCall('/api/admin/responses/batch', { method: 'DELETE', body: JSON.stringify(selectedResponses.value) }, { errorMsg: t('toast.batchDeleteResponsesFailed') });
        if (r && r.ok) {
            const d = await r.json();
            showToast(t('toast.batchDeleteResponsesSuccess', {deleted: d.deleted, deletedRules: d.deletedRules}), 'success');
            selectedResponses.value = [];
            markDirty();
            loadResponseSummary(true);
            if (d.deletedRules > 0 && deps.onRulesDirty) { deps.onRulesDirty(); }
        }
    };

    const deleteAllResponses = async () => {
        if (!await requireLogin()) return;
        const count = responseTotalElements.value;
        if (!await showConfirm({ title: t('confirm.deleteAllResponses'), message: t('confirm.deleteAllResponsesMsg', {count}), confirmText: t('confirm.deleteAll'), danger: true, requireInput: 'DELETE', inputLabel: t('confirm.deleteAllResponsesInputLabel') })) return;
        const r = await apiCall('/api/admin/responses/all', { method: 'DELETE' }, { errorMsg: t('toast.deleteAllResponsesFailed') });
        if (r && r.ok) {
            const d = await r.json();
            showToast(t('toast.deleteAllResponsesSuccess', {deletedResponses: d.deletedResponses, deletedRules: d.deletedRules}), 'success');
            if (deps.onRulesDirty) { deps.onRulesDirty(); }
            loadResponseSummary(true);
        }
    };

    // --- 導航 ---
    const goToResponse = (id) => {
        const rid = id.replace('response-', '');
        deps.page.value = 'responses';
        responseFilter.value = rid;
        responsePage.value = 1;
    };

    // --- Dropdown ---
    const toggleResponseDataDropdown = () => { showResponseDataDropdown.value = !showResponseDataDropdown.value; };
    const closeResponseDataDropdown = (e) => { if (!e.target.closest('.resp-data-dropdown-wrapper')) showResponseDataDropdown.value = false; };
    const triggerResponseImport2 = () => { showResponseDataDropdown.value = false; document.getElementById('responseImportInput2')?.click(); };

    // --- Filter chips ---
    const responseFilterChips = computed(() => {
        const chips = [];
        if (responseFilter.value) chips.push({ key: 'keyword', label: t('filterChips.search') + responseFilter.value });
        if (responseUsageFilter.value === 'used') chips.push({ key: 'usage', label: t('responses.filterUsed') });
        if (responseUsageFilter.value === 'unused') chips.push({ key: 'usage', label: t('responses.filterUnused') });
        if (responseContentTypeFilter.value === 'SSE') chips.push({ key: 'contentType', label: t('filterChips.type') + 'SSE' });
        if (responseContentTypeFilter.value === 'GENERAL') chips.push({ key: 'contentType', label: t('filterChips.type') + t('responses.filterGeneral') });
        return chips;
    });

    const removeResponseChip = (key) => {
        if (key === 'usage') { responseUsageFilter.value = ''; }
        else if (key === 'contentType') { responseContentTypeFilter.value = ''; }
        else { responseFilter.value = ''; }
    };

    const clearResponseFilters = () => { responseFilter.value = ''; responseUsageFilter.value = ''; responseContentTypeFilter.value = ''; };

    // --- 展延回應 ---
    const extendResponse = async (id) => {
        if (!await requireLogin()) { return; }
        if (!await showConfirm({ title: t('confirm.extendResponse'), message: t('confirm.extendResponseMsg') })) { return; }
        const r = await apiCall(`/api/admin/responses/${id}/extend`, { method: 'PUT' }, { errorMsg: t('toast.responseExtendFailed') });
        if (r && r.ok) { showToast(t('toast.responseExtendSuccess'), 'success'); markDirty(); loadResponseSummary(true); }
    };

    // --- 清除孤兒回應 ---
    const deleteOrphanResponses = async () => {
        if (!await requireLogin()) { return; }
        // 先查詢孤兒回應數量
        const countRes = await apiCall('/api/admin/responses/orphan-count', {}, { silent: true });
        if (!countRes || !countRes.ok) { return; }
        const { count } = await countRes.json();
        if (count === 0) {
            showToast(t('toast.deleteOrphanResponsesSuccess', { count: 0 }), 'success');
            return;
        }
        if (!await showConfirm({ title: t('confirm.deleteOrphanResponses'), message: t('confirm.deleteOrphanResponsesMsg', { count }), confirmText: t('confirm.delete'), danger: true })) { return; }
        const r = await apiCall('/api/admin/responses/orphans', { method: 'DELETE' }, { errorMsg: t('toast.deleteOrphanResponsesFailed') });
        if (r && r.ok) {
            const d = await r.json();
            showToast(t('toast.deleteOrphanResponsesSuccess', { count: d.deleted }), 'success');
            markDirty();
            loadResponseSummary(true);
        }
    };

    return {
        responseSummary,
        responseOptions,
        responseFilter,
        responseSort,
        responsePage,
        responsePageSize,
        responseTotalElements,
        filteredResponseSummary,
        pagedResponseSummary,
        responseTotalPages,
        toggleResponseSort,
        responseSortIcon,
        onResponsePageSizeChange,
        showResponseModal,
        editingResponse,
        responseForm,
        responseSseEvents,
        loadResponseSummary,
        loadResponseOptions,
        openResponseModal,
        saveResponse,
        deleteResponse,
        selectedResponses,
        batchSelectResponseMode,
        toggleSelectAllResponses,
        exportResponses,
        importResponses,
        deleteSelectedResponses,
        deleteAllResponses,
        toggleResponseRules,
        responseRulesCache,
        responseUsageFilter,
        responseContentTypeFilter,
        responseFilterChips,
        removeResponseChip,
        clearResponseFilters,
        goToResponse,
        showResponseDataDropdown,
        toggleResponseDataDropdown,
        closeResponseDataDropdown,
        triggerResponseImport2,
        markDirty,
        extendResponse,
        deleteOrphanResponses
    };
};
