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
    const dataDirty = { responses: false };
    const DATA_TTL = 30000;
    const shouldLoad = () => !dataLastLoaded.responses || dataDirty.responses || (Date.now() - dataLastLoaded.responses > DATA_TTL);
    const markLoaded = () => { dataLastLoaded.responses = Date.now(); dataDirty.responses = false; };
    const markDirty = () => { dataDirty.responses = true; };

    // --- 工具函式 ---
    const matchAll = (text, keywords) => keywords.every(kw => text.includes(kw));

    // --- 狀態 ---
    const responseSummary = ref([]);
    const responseFilter = ref('');
    const savedResponseSort = JSON.parse(localStorage.getItem('responseSort') || 'null');
    const responseSort = ref(savedResponseSort || { field: 'updatedAt', asc: false });
    const responsePage = ref(1);
    const responsePageSize = ref(20);
    const responseUsageFilter = ref('');
    const responseContentTypeFilter = ref('');

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
    const filteredResponseSummary = computed(() => {
        let list = responseSummary.value;
        if (responseUsageFilter.value === 'used') {
            list = list.filter(r => r.usageCount > 0);
        } else if (responseUsageFilter.value === 'unused') {
            list = list.filter(r => r.usageCount === 0);
        }
        if (responseContentTypeFilter.value === 'SSE') {
            list = list.filter(r => r.contentType === 'SSE');
        } else if (responseContentTypeFilter.value === 'GENERAL') {
            list = list.filter(r => !r.contentType || r.contentType !== 'SSE');
        }
        if (responseFilter.value) {
            const keywords = responseFilter.value.toLowerCase().split(/\s+/).filter(k => k);
            list = list.filter(r => matchAll(String(r.id) + ' ' + (r.description || '').toLowerCase(), keywords));
        }
        const f = responseSort.value.field, asc = responseSort.value.asc;
        return [...list].sort((a, b) => { const av = a[f], bv = b[f]; return (av < bv ? -1 : av > bv ? 1 : 0) * (asc ? 1 : -1) });
    });

    const responseTotalPages = computed(() => Math.max(1, Math.ceil(filteredResponseSummary.value.length / responsePageSize.value)));
    const pagedResponseSummary = computed(() => filteredResponseSummary.value.slice((responsePage.value - 1) * responsePageSize.value, responsePage.value * responsePageSize.value));

    /** 切換排序欄位/方向 */
    const toggleResponseSort = f => {
        if (responseSort.value.field === f) responseSort.value.asc = !responseSort.value.asc;
        else { responseSort.value.field = f; responseSort.value.asc = false; }
        localStorage.setItem('responseSort', JSON.stringify(responseSort.value));
        responsePage.value = 1;
    };

    /** 取得排序圖示 class */
    const responseSortIcon = f => responseSort.value.field === f ? (responseSort.value.asc ? 'bi-caret-up-fill' : 'bi-caret-down-fill') : 'bi-arrow-down-up';

    /** 每頁筆數變更 */
    const onResponsePageSizeChange = () => { responsePage.value = 1; };

    // --- watchers: 篩選變更時重置頁碼 ---
    watch(responseFilter, () => responsePage.value = 1);
    watch(responseUsageFilter, () => responsePage.value = 1);
    watch(responseContentTypeFilter, () => responsePage.value = 1);

    // --- 載入 ---
    const loadResponseSummary = async (force) => {
        if (!force && !shouldLoad()) return;
        deps.loading.value.responses = true;
        const r = await apiCall('/api/admin/responses/summary', {}, { errorMsg: t('toast.responseLoadFailed') });
        if (r && r.ok) { responseSummary.value = await r.json(); markLoaded(); }
        Object.keys(responseRulesCache).forEach(k => delete responseRulesCache[k]);
        deps.loading.value.responses = false;
    };

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
        const payload = { description: responseForm.value.description, contentType: responseForm.value.contentType === 'sse' ? 'SSE' : null };
        if (responseForm.value.contentType === 'sse') {
            payload.body = serializeSseEvents(responseSseEvents.value);
        } else {
            payload.body = responseForm.value.body;
        }
        const url = editingResponse.value ? `/api/admin/responses/${editingResponse.value.id}` : '/api/admin/responses';
        const r = await apiCall(url, { method: editingResponse.value ? 'PUT' : 'POST', body: JSON.stringify(payload) }, { errorMsg: t('toast.responseSaveFailed') });
        if (r && r.ok) {
            showToast(editingResponse.value ? t('toast.responseSaveSuccess') : t('toast.responseCreateSuccess'), 'success');
            showResponseModal.value = false;
            markDirty();
            if (deps.onResponseSaved) deps.onResponseSaved();
            loadResponseSummary(true);
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
        const count = responseSummary.value.length;
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
        responseFilter,
        responseSort,
        responsePage,
        responsePageSize,
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
