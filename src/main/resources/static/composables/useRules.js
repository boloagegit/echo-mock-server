/**
 * useRules - 規則管理 Composable
 *
 * 管理規則（rules）的 CRUD、篩選、排序、分頁、批次操作、
 * 拖曳排序、行內預覽、匯出匯入、標籤分組等功能。
 *
 * @param {Object} deps - 依賴物件
 * @param {Function} deps.showToast - Toast 通知函式（來自 useToast）
 * @param {Function} deps.showConfirm - 確認對話框函式（來自 useToast）
 * @param {Function} deps.t - 翻譯函式（來自 useI18n）
 * @param {Function} deps.requireLogin - 登入檢查函式（來自 useAuth）
 * @param {Function} deps.login - 登入跳轉函式（來自 useAuth）
 * @param {import('vue').Ref} deps.isLoggedIn - 是否已登入（來自 useAuth）
 * @param {import('vue').Ref} deps.loading - 全域 loading 狀態
 * @param {import('vue').Ref} deps.page - 當前頁面（來自 app.js）
 * @param {import('vue').Ref} deps.httpLabel - HTTP 協定標籤
 * @param {import('vue').Ref} deps.jmsLabel - JMS 協定標籤
 * @returns {Object} 規則管理相關的狀態與方法
 */
const useRules = (deps) => {
    const { ref, computed, watch } = Vue;
    const { showToast, showConfirm, t, requireLogin, login, isLoggedIn, loading, httpLabel, jmsLabel } = deps;

    // --- 資料快取機制 ---
    const dataLastLoaded = { rules: 0 };
    const dataDirty = { rules: false };
    const DATA_TTL = 30000;
    const shouldLoad = () => !dataLastLoaded.rules || dataDirty.rules || (Date.now() - dataLastLoaded.rules > DATA_TTL);
    const markLoaded = () => { dataLastLoaded.rules = Date.now(); dataDirty.rules = false; };
    const markDirty = () => { dataDirty.rules = true; };

    // --- 狀態 ---
    const rules = ref([]);
    const savedRuleSort = JSON.parse(localStorage.getItem('ruleSort') || 'null');
    const ruleFilter = ref({ protocol: '', enabled: '', isProtected: '', keyword: '' });
    const ruleSort = ref(savedRuleSort || { field: 'updatedAt', asc: false });
    const rulePage = ref(1);
    const rulePageSize = ref(20);
    const serverRuleTotalElements = ref(0);
    const serverRuleTotalPages = ref(0);
    let listRequestSequence = 0;
    let listAbortController = null;

    // --- 選取 ---
    const selectedRules = ref([]);
    const batchSelectMode = ref(false);
    const ruleDragEnabled = ref(false);

    // --- 標籤分組 ---
    const ruleViewMode = ref(localStorage.getItem('ruleViewMode') || 'list');
    const expandedTagGroups = ref([]);
    const tagKeys = ref({});
    const groupCounts = ref({ '_untagged': 0 });
    const rulesByTag = ref({});
    const rulesByTagGroup = ref({ '_untagged': [] });
    const groupLoading = ref({});
    const groupLoaded = ref({});
    let groupLoadGeneration = 0;

    const toggleTagGroup = async g => {
        const i = expandedTagGroups.value.indexOf(g);
        if (i >= 0) { expandedTagGroups.value.splice(i, 1); return; }
        expandedTagGroups.value.push(g);
        if (g === '_untagged' && !groupLoaded.value[g]) { await loadRuleGroup(g, getGroupLimit(g)); }
    };

    // --- 子群組展開收合 ---
    const expandedTagSubgroups = ref([]);
    const toggleTagSubgroup = async (key) => {
        const i = expandedTagSubgroups.value.indexOf(key);
        if (i >= 0) { expandedTagSubgroups.value.splice(i, 1); }
        else {
            expandedTagSubgroups.value.push(key);
            if (!groupLoaded.value[key]) { await loadRuleGroup(key, getGroupLimit(key)); }
        }
    };

    // --- 群組漸進渲染 ---
    const GROUP_PAGE_SIZE = 20;
    const groupVisibleLimit = ref({});
    const getGroupLimit = (key) => groupVisibleLimit.value[key] || GROUP_PAGE_SIZE;
    const showMoreGroup = async (key) => {
        const limit = getGroupLimit(key) + GROUP_PAGE_SIZE;
        groupVisibleLimit.value = { ...groupVisibleLimit.value, [key]: limit };
        await loadRuleGroup(key, limit);
    };
    const showAllGroup = async (key, total) => {
        groupVisibleLimit.value = { ...groupVisibleLimit.value, [key]: total };
        await loadRuleGroup(key, total);
    };
    // 篩選條件變更時重置 limit
    watch(ruleFilter, () => { groupVisibleLimit.value = {}; }, { deep: true });

    // --- 篩選、排序、分頁 ---
    const filteredRules = computed(() => {
        return ruleViewMode.value === 'list' ? rules.value : [];
    });

    const ruleTotalElements = computed(() => serverRuleTotalElements.value);
    const ruleTotalPages = computed(() => ruleViewMode.value === 'list'
        ? Math.max(1, serverRuleTotalPages.value)
        : 1);
    const pagedRules = computed(() => ruleViewMode.value === 'list'
        ? rules.value
        : []);

    const toggleRuleSort = f => { ruleSort.value = ruleSort.value.field === f ? { field: f, asc: !ruleSort.value.asc } : { field: f, asc: false }; localStorage.setItem('ruleSort', JSON.stringify(ruleSort.value)); rulePage.value = 1 };
    const ruleSortIcon = f => ruleSort.value.field === f ? (ruleSort.value.asc ? 'bi-caret-up-fill' : 'bi-caret-down-fill') : 'bi-arrow-down-up';

    // --- 載入 ---
    const buildRuleQuery = () => {
        const params = new URLSearchParams();
        params.set('page', String(Math.max(0, rulePage.value - 1)));
        params.set('size', String(rulePageSize.value));
        params.set('sort', ruleSort.value.field);
        params.set('direction', ruleSort.value.asc ? 'asc' : 'desc');
        if (ruleFilter.value.protocol) { params.set('protocol', ruleFilter.value.protocol); }
        if (ruleFilter.value.enabled !== '') { params.set('enabled', ruleFilter.value.enabled); }
        if (ruleFilter.value.isProtected !== '') { params.set('isProtected', ruleFilter.value.isProtected); }
        const keyword = (ruleFilter.value.keyword || '').trim();
        if (keyword) { params.set('keyword', keyword); }
        return '/api/admin/rules/page?' + params.toString();
    };

    const buildRuleGroupSummaryQuery = () => {
        const params = new URLSearchParams();
        if (ruleFilter.value.protocol) { params.set('protocol', ruleFilter.value.protocol); }
        if (ruleFilter.value.enabled !== '') { params.set('enabled', ruleFilter.value.enabled); }
        if (ruleFilter.value.isProtected !== '') { params.set('isProtected', ruleFilter.value.isProtected); }
        const keyword = (ruleFilter.value.keyword || '').trim();
        if (keyword) { params.set('keyword', keyword); }
        return '/api/admin/rules/groups?' + params.toString();
    };

    const buildRuleGroupQuery = (groupKey, limit) => {
        const params = new URLSearchParams();
        const separator = groupKey.indexOf('=');
        params.set('key', groupKey === '_untagged' ? '_untagged' : groupKey.substring(0, separator));
        if (groupKey !== '_untagged') { params.set('value', groupKey.substring(separator + 1)); }
        params.set('limit', String(Math.max(1, limit)));
        params.set('sort', ruleSort.value.field);
        params.set('direction', ruleSort.value.asc ? 'asc' : 'desc');
        if (ruleFilter.value.protocol) { params.set('protocol', ruleFilter.value.protocol); }
        if (ruleFilter.value.enabled !== '') { params.set('enabled', ruleFilter.value.enabled); }
        if (ruleFilter.value.isProtected !== '') { params.set('isProtected', ruleFilter.value.isProtected); }
        const keyword = (ruleFilter.value.keyword || '').trim();
        if (keyword) { params.set('keyword', keyword); }
        return '/api/admin/rules/group?' + params.toString();
    };

    const loadRuleGroup = async (groupKey, limit) => {
        const generation = groupLoadGeneration;
        groupLoading.value = { ...groupLoading.value, [groupKey]: true };
        try {
            const r = await apiCall(buildRuleGroupQuery(groupKey, limit), {}, { errorMsg: t('toast.ruleLoadFailed') });
            if (generation !== groupLoadGeneration || !r || !r.ok) { return false; }
            const data = await r.json();
            if (generation !== groupLoadGeneration) { return false; }
            if (groupKey === '_untagged') {
                rulesByTagGroup.value = { ...rulesByTagGroup.value, '_untagged': data.results || [] };
            } else {
                rulesByTag.value = { ...rulesByTag.value, [groupKey]: data.results || [] };
            }
            groupLoaded.value = { ...groupLoaded.value, [groupKey]: true };
            return true;
        } finally {
            if (generation === groupLoadGeneration) {
                groupLoading.value = { ...groupLoading.value, [groupKey]: false };
            }
        }
    };

    const loadRules = async (force) => {
        if (!force && !shouldLoad()) return;
        if (force) { debouncedRuleLoad.cancel(); }
        const requestId = ++listRequestSequence;
        if (listAbortController) { listAbortController.abort(); }
        const abortController = new AbortController();
        listAbortController = abortController;
        loading.value.rules = true;
        loading.value.rulesError = '';
        try {
            const paged = ruleViewMode.value === 'list';
            const url = paged ? buildRuleQuery() : buildRuleGroupSummaryQuery();
            const r = await apiCall(url, { signal: abortController.signal }, { errorMsg: t('toast.ruleLoadFailed') });
            if (requestId !== listRequestSequence) { return false; }
            if (r && r.ok) {
                const data = await r.json();
                if (requestId !== listRequestSequence) { return false; }
                if (paged) {
                    rules.value = data.results || [];
                    serverRuleTotalElements.value = Number(data.totalElements || 0);
                    serverRuleTotalPages.value = Number(data.totalPages || 0);
                    const actualPage = Number(data.page || 0) + 1;
                    if (rulePage.value !== actualPage) { rulePage.value = actualPage; }
                } else {
                    groupLoadGeneration++;
                    rules.value = [];
                    tagKeys.value = data.tagKeys || {};
                    groupCounts.value = data.counts || { '_untagged': 0 };
                    rulesByTag.value = {};
                    rulesByTagGroup.value = { '_untagged': [] };
                    groupLoaded.value = {};
                    groupLoading.value = {};
                    expandedTagGroups.value = [];
                    expandedTagSubgroups.value = [];
                    serverRuleTotalElements.value = Number(data.totalElements || 0);
                    serverRuleTotalPages.value = 0;
                }
                markLoaded();
                rulePreviewExpanded.value = {};
            } else {
                loading.value.rulesError = t('rules.loadFailed');
            }
            return !!(r && r.ok);
        } finally {
            if (requestId === listRequestSequence) {
                if (listAbortController === abortController) { listAbortController = null; }
                loading.value.rules = false;
            }
        }
    };

    const debouncedRuleLoad = debounce(() => loadRules(true), 300);

    watch(ruleFilter, () => {
        rulePage.value = 1;
        debouncedRuleLoad();
    }, { deep: true });
    watch(ruleSort, () => {
        rulePage.value = 1;
        debouncedRuleLoad();
    }, { deep: true });
    watch(rulePage, () => {
        if (ruleViewMode.value === 'list') { debouncedRuleLoad(); }
    });
    watch(rulePageSize, () => {
        rulePage.value = 1;
        if (ruleViewMode.value === 'list') { debouncedRuleLoad(); }
    });
    watch(ruleViewMode, v => {
        localStorage.setItem('ruleViewMode', v);
        rulePage.value = 1;
        selectedRules.value = [];
        loadRules(true);
    });

    // --- CRUD ---
    const deleteRule = async id => {
        if (!await requireLogin()) { return; }
        if (!await showConfirm({ title: t('confirm.deleteRule'), message: t('confirm.deleteRuleMsg'), confirmText: t('confirm.delete'), danger: true })) { return; }
        const backup = [...rules.value];
        rules.value = rules.value.filter(r => r.id !== id);
        const r = await apiCall(`/api/admin/rules/${id}`, { method: 'DELETE' }, { errorMsg: t('toast.ruleDeleteFailed') });
        if (r && r.ok) {
            showToast(t('toast.ruleDeleteSuccess'), 'success');
            markDirty();
            await loadRules(true);
        } else {
            rules.value = backup;
            if (r && (r.status === 401 || r.status === 403)) { login(); }
        }
    };

    const extendRule = async id => {
        if (!await requireLogin()) return;
        if (!await showConfirm({ title: t('confirm.extendRule'), message: t('confirm.extendRuleMsg') })) return;
        const r = await apiCall(`/api/admin/rules/${id}/extend`, { method: 'PUT' }, { errorMsg: t('toast.ruleExtendFailed') });
        if (r && r.ok) { showToast(t('toast.ruleExtendSuccess'), 'success'); markDirty(); loadRules(true); }
    };

    const toggleEnabled = async rule => {
        if (!await requireLogin()) return;
        const newEnabled = rule.enabled === false;
        const action = newEnabled ? 'enable' : 'disable';
        const msg = newEnabled ? t('confirm.enableRuleMsg') : t('confirm.disableRuleMsg');
        if (!await showConfirm({ title: newEnabled ? t('confirm.enableRule') : t('confirm.disableRule'), message: msg })) return;
        const r = await apiCall(`/api/admin/rules/${rule.id}/${action}`, { method: 'PUT' }, { errorMsg: t('toast.ruleStatusFailed') });
        if (r && r.ok) {
            rule.enabled = newEnabled;
            showToast(newEnabled ? t('toast.ruleEnabled') : t('toast.ruleDisabled'), 'success');
            markDirty();
            await loadRules(true);
        }
    };

    const toggleSelectAll = e => { selectedRules.value = e.target.checked ? pagedRules.value.map(r => r.id) : [] };

    // --- 匯出匯入 ---
    const exportRules = async () => {
        const r = await apiCall('/api/admin/rules/export', {}, { errorMsg: t('toast.exportFailed') });
        if (r && r.ok) {
            const data = await r.json();
            const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
            const a = document.createElement('a'); a.href = URL.createObjectURL(blob);
            a.download = `echo-rules-${new Date().toISOString().slice(0, 10)}.json`; a.click();
            showToast(t('toast.exportSuccess', {count: data.length}), 'success');
        }
    };

    const showImportModal = ref(false);
    const importFormat = ref('json');
    const importFile = ref(null);
    const importFileName = ref('');
    const handleImportFile = input => {
        const file = input instanceof File ? input : input?.target?.files?.[0];
        deps.loading.value.importError = '';
        if (!file) return;
        const lowerName = file.name.toLowerCase();
        const valid = importFormat.value === 'json'
            ? lowerName.endsWith('.json')
            : importFormat.value === 'openapi'
                ? lowerName.endsWith('.json') || lowerName.endsWith('.yaml') || lowerName.endsWith('.yml')
                : lowerName.endsWith('.xlsx') || lowerName.endsWith('.xls');
        if (!valid) {
            importFile.value = null;
            importFileName.value = '';
            const errorKey = importFormat.value === 'json'
                ? 'modal.importJsonFileRequired'
                : importFormat.value === 'openapi'
                    ? 'modal.importOpenApiFileRequired'
                    : 'modal.importExcelFileRequired';
            deps.loading.value.importError = t(errorKey);
            return;
        }
        importFile.value = file;
        importFileName.value = file.name;
    };
    // --- OpenAPI Preview ---
    const showOpenApiPreview = ref(false);
    const openApiPreviewTitle = ref('');
    const openApiPreviewVersion = ref('');
    const openApiPreviewRules = ref([]);
    const openApiImporting = ref(false);

    const doImport = async () => {
        if (!importFile.value || deps.loading.value.importRules) return;
        deps.loading.value.importRules = true;
        deps.loading.value.importError = '';
        try {
            if (importFormat.value === 'openapi') {
                // OpenAPI: 先預覽
                const formData = new FormData();
                formData.append('file', importFile.value);
                const r = await apiCall('/api/admin/rules/import-openapi/preview', { method: 'POST', body: formData }, { silent: true });
                const d = r ? await r.json().catch(() => ({})) : {};
                if (r && r.ok && d.success) {
                    openApiPreviewTitle.value = d.title || '';
                    openApiPreviewVersion.value = d.version || '';
                    openApiPreviewRules.value = d.rules || [];
                    showOpenApiPreview.value = true;
                    showImportModal.value = false;
                    importFile.value = null;
                    importFileName.value = '';
                } else {
                    deps.loading.value.importError = (d.errors && d.errors[0]) || d.error || t('toast.openApiParseFailed');
                }
                return;
            }
            if (importFormat.value === 'json') {
                const text = await importFile.value.text();
                const data = JSON.parse(text);
                const arr = Array.isArray(data) ? data : [data];
                const r = await apiCall('/api/admin/rules/import-batch', { method: 'POST', body: JSON.stringify(arr) }, { errorMsg: t('toast.importFailed') });
                if (r && r.ok) { const d = await r.json(); showToast(t('toast.importSuccess', {count: d.imported}), 'success'); markDirty(); loadRules(true); showImportModal.value = false; }
                else { deps.loading.value.importError = t('modal.importRequestFailed'); }
            } else {
                const formData = new FormData();
                formData.append('file', importFile.value);
                const r = await apiCall('/api/admin/rules/import-excel', { method: 'POST', body: formData }, { silent: true });
                if (r && r.ok) { const d = await r.json(); showToast(t('toast.importSuccess', {count: d.imported}), 'success'); markDirty(); loadRules(true); showImportModal.value = false; }
                else {
                    const error = r ? await r.json().catch(() => ({})) : {};
                    deps.loading.value.importError = error.error || (r ? t('modal.importRequestFailed') : t('toast.networkError'));
                    showToast(deps.loading.value.importError, 'error');
                }
            }
        } catch (err) {
            deps.loading.value.importError = t('modal.importFileFormatError');
            showToast(t('toast.fileFormatError'), 'error');
        } finally {
            deps.loading.value.importRules = false;
        }
        if (!deps.loading.value.importError) { importFile.value = null; importFileName.value = ''; }
    };

    // --- 批次操作 ---
    const batchProtect = async (protect) => {
        if (!await requireLogin()) return;
        const url = protect ? '/api/admin/rules/batch/protect' : '/api/admin/rules/batch/unprotect';
        const r = await apiCall(url, { method: 'PUT', body: JSON.stringify(selectedRules.value) }, { errorMsg: t('toast.batchOperationFailed') });
        if (r && r.ok) { const d = await r.json(); showToast(protect ? t('toast.batchProtected', {count: d.updated}) : t('toast.batchUnprotected', {count: d.updated}), 'success'); selectedRules.value = []; markDirty(); loadRules(true); }
    };

    const deleteSelectedRules = async () => {
        if (!await requireLogin()) return;
        const protectedCount = rules.value.filter(r => selectedRules.value.includes(r.id) && r.isProtected).length;
        const msg = protectedCount
            ? t('confirm.batchDeleteRulesMsg', {count: selectedRules.value.length}) + '\n\n' + t('confirm.batchDeleteProtectedWarn', {count: protectedCount})
            : t('confirm.batchDeleteRulesMsg', {count: selectedRules.value.length});
        if (!await showConfirm({ title: t('confirm.batchDeleteRules'), message: msg, confirmText: t('confirm.delete'), danger: true })) return;
        const r = await apiCall('/api/admin/rules/batch', { method: 'DELETE', body: JSON.stringify(selectedRules.value) }, { errorMsg: t('toast.batchDeleteFailed') });
        if (r && r.ok) { const d = await r.json(); showToast(t('toast.batchDeleteSuccess', {count: d.deleted}), 'success'); selectedRules.value = []; markDirty(); loadRules(true); }
    };

    const deleteAllRules = async () => {
        if (!await requireLogin()) return;
        const count = ruleTotalElements.value;
        if (!await showConfirm({ title: t('confirm.deleteAllRules'), message: t('confirm.deleteAllRulesMsg', {count}), confirmText: t('confirm.deleteAll'), danger: true, requireInput: String(count), inputLabel: t('confirm.deleteAllRulesInputLabel', {count}) })) return;
        const r = await apiCall('/api/admin/rules/all', { method: 'DELETE' }, { errorMsg: t('toast.deleteAllRulesFailed') });
        if (r && r.ok) { const d = await r.json(); showToast(t('toast.deleteAllRulesSuccess', {count: d.deleted}), 'success'); markDirty(); loadRules(true); }
    };

    // --- 行內預覽 ---
    const rulePreviewCache = ref({});
    const rulePreviewExpanded = ref({});
    const rulePreviewLoading = ref({});
    const rulePreviewError = ref({});
    const toggleRulePreview = async (rule) => {
        const id = rule.id;
        if (rulePreviewExpanded.value[id] && !rulePreviewError.value[id]) {
            rulePreviewExpanded.value[id] = false;
            return;
        }
        if (rulePreviewCache.value[id]) {
            rulePreviewExpanded.value[id] = true;
            return;
        }
        rulePreviewLoading.value[id] = true;
        rulePreviewError.value[id] = false;
        rulePreviewExpanded.value[id] = true;
        const r = await apiCall(`/api/admin/rules/${id}`, {}, { silent: true });
        if (r && r.ok) {
            const data = await r.json();
            const body = data.responseBody || '';
            // SSE 內容：格式化為 SSE 預覽
            if (data.sseEnabled) {
                const events = deserializeSseEvents(body);
                if (events.length && events[0].data) {
                    const lines = [];
                    events.forEach((evt, i) => {
                        if (i > 0) { lines.push(''); }
                        if (evt.delayMs > 0) { lines.push(t('ssePreview.delay', {ms: evt.delayMs})); lines.push(''); }
                        const type = evt.type || 'normal';
                        if (type === 'abort') { lines.push(t('ssePreview.abort')); return; }
                        if (type === 'error') { lines.push(t('ssePreview.errorEvent')); }
                        const evtName = type === 'error' ? 'error' : (evt.event || 'message');
                        lines.push('event: ' + evtName);
                        (evt.data || '').split('\n').forEach(line => { lines.push('data: ' + line); });
                        if (evt.id) { lines.push('id: ' + evt.id); }
                        lines.push('');
                    });
                    data._previewBody = lines.join('\n');
                    data._isSse = true;
                } else {
                    data._previewBody = body;
                }
            } else {
                data._previewBody = body;
            }
            rulePreviewCache.value[id] = data;
        } else {
            rulePreviewError.value[id] = true;
        }
        rulePreviewLoading.value[id] = false;
    };

    let ruleClickTimer = null;
    const handleRuleRowClick = (r) => {
        if (ruleClickTimer) {
            clearTimeout(ruleClickTimer);
            ruleClickTimer = null;
            if (isLoggedIn.value) { if (deps.openEdit) deps.openEdit(r); }
        } else {
            ruleClickTimer = setTimeout(() => {
                ruleClickTimer = null;
                toggleRulePreview(r);
            }, 250);
        }
    };

    // --- 拖曳排序 ---
    const dragState = ref({ dragging: false, dragId: null, overId: null, overPos: null });
    const ruleDragAvailable = computed(() => isLoggedIn.value && !batchSelectMode.value && ruleViewMode.value === 'list');
    const canDragRules = computed(() => ruleDragEnabled.value && ruleDragAvailable.value && ruleSort.value.field === 'priority' && !ruleSort.value.asc);

    const setRuleDragEnabled = enabled => {
        if (!enabled || !ruleDragAvailable.value) {
            ruleDragEnabled.value = false;
            resetDrag();
            return;
        }
        ruleDragEnabled.value = true;
        if (ruleSort.value.field !== 'priority' || ruleSort.value.asc) {
            ruleSort.value = { field: 'priority', asc: false };
            localStorage.setItem('ruleSort', JSON.stringify(ruleSort.value));
        }
    };

    const onDragStart = (e, rule) => {
        if (!canDragRules.value) return;
        dragState.value = { dragging: true, dragId: rule.id, overId: null, overPos: null };
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', rule.id);
        e.target.closest('tr').classList.add('drag-source');
    };
    const onDragOver = (e, rule) => {
        if (!dragState.value.dragging || rule.id === dragState.value.dragId) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        const rect = e.target.closest('tr').getBoundingClientRect();
        const mid = rect.top + rect.height / 2;
        dragState.value.overId = rule.id;
        dragState.value.overPos = e.clientY < mid ? 'before' : 'after';
    };
    const onDragLeave = (e, rule) => {
        if (dragState.value.overId === rule.id) {
            const tr = e.target.closest('tr');
            if (tr && !tr.contains(e.relatedTarget)) {
                dragState.value.overId = null;
                dragState.value.overPos = null;
            }
        }
    };
    const onDrop = async (e) => {
        e.preventDefault();
        const { dragId, overId, overPos } = dragState.value;
        if (!dragId || !overId || dragId === overId) { resetDrag(); return; }
        const list = pagedRules.value;
        const dragIdx = list.findIndex(r => r.id === dragId);
        const overIdx = list.findIndex(r => r.id === overId);
        if (dragIdx < 0 || overIdx < 0) { resetDrag(); return; }
        let insertIdx = overPos === 'before' ? overIdx : overIdx + 1;
        if (dragIdx < insertIdx) { insertIdx--; }
        if (insertIdx === dragIdx) { resetDrag(); return; }
        const ordered = list.map(r => r.id);
        ordered.splice(dragIdx, 1);
        ordered.splice(insertIdx, 0, dragId);
        const maxPri = Math.max(...list.map(r => r.priority ?? 0));
        const updates = [];
        ordered.forEach((id, i) => {
            const newPri = maxPri - i;
            const rule = list.find(r => r.id === id);
            if (rule && (rule.priority ?? 0) !== newPri) {
                updates.push({ id, priority: newPri, version: rule.version });
            }
        });
        resetDrag();
        if (!updates.length) return;
        showToast(t('toast.updatingSort'), 'success');
        let failed = 0;
        for (const u of updates) {
            const r = await apiCall(`/api/admin/rules/${u.id}`, { method: 'PUT', body: JSON.stringify({ priority: u.priority, version: u.version }) }, { silent: true });
            if (!r || !r.ok) { failed++; }
        }
        if (failed) { showToast(t('toast.sortUpdateFailed', {count: failed}), 'error'); }
        else { showToast(t('toast.sortUpdated'), 'success'); }
        markDirty();
        await loadRules(true);
    };
    const onDragEnd = () => { resetDrag(); };
    const resetDrag = () => {
        document.querySelectorAll('.drag-source').forEach(el => el.classList.remove('drag-source'));
        dragState.value = { dragging: false, dragId: null, overId: null, overPos: null };
    };
    const dragRowClass = (rule) => {
        if (!dragState.value.dragging) return '';
        if (rule.id === dragState.value.dragId) return 'drag-source';
        if (rule.id === dragState.value.overId) return dragState.value.overPos === 'before' ? 'drag-over-before' : 'drag-over-after';
        return '';
    };

    watch(ruleDragAvailable, available => {
        if (!available && ruleDragEnabled.value) setRuleDragEnabled(false);
    });
    watch(ruleSort, sort => {
        if (ruleDragEnabled.value && (sort.field !== 'priority' || sort.asc)) setRuleDragEnabled(false);
    }, { deep: true });

    // --- Filter chips ---
    const ruleFilterChips = computed(() => {
        const chips = [];
        if (ruleFilter.value.protocol) chips.push({ key: 'protocol', label: t('filterChips.protocol') + (ruleFilter.value.protocol === 'HTTP' ? httpLabel.value : jmsLabel.value) });
        if (ruleFilter.value.enabled) chips.push({ key: 'enabled', label: t('filterChips.status') + (ruleFilter.value.enabled === 'true' ? t('rules.filterEnabled') : t('rules.filterDisabled')) });
        if (ruleFilter.value.isProtected) chips.push({ key: 'isProtected', label: t('filterChips.protection') + (ruleFilter.value.isProtected === 'true' ? t('rules.filterProtected') : t('rules.filterUnprotected')) });
        if (ruleFilter.value.keyword) chips.push({ key: 'keyword', label: t('filterChips.keyword') + ruleFilter.value.keyword });
        return chips;
    });
    const removeRuleChip = key => { ruleFilter.value[key] = ''; };
    const clearRuleFilters = () => { ruleFilter.value = { protocol: '', enabled: '', isProtected: '', keyword: '' }; };

    // --- 其他 ---
    const showPriorityHelp = ref(false);
    const helpTab = ref('start');

    const clipCopy = async (text) => {
        try {
            if (navigator.clipboard && window.isSecureContext) {
                await navigator.clipboard.writeText(text);
            } else {
                const ta = document.createElement('textarea');
                ta.value = text;
                ta.style.position = 'fixed';
                ta.style.left = '-9999px';
                document.body.appendChild(ta);
                ta.select();
                document.execCommand('copy');
                document.body.removeChild(ta);
            }
            showToast(t('toast.copiedToClipboard'), 'success');
        } catch {
            showToast(t('toast.copyFailed'), 'danger');
        }
    };

    const exportRuleJson = async (id) => {
        const r = await apiCall(`/api/admin/rules/${id}/json`, {}, { silent: true });
        if (r && r.ok) { const data = await r.json(); const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }); const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = `rule-${id.substring(0, 8)}.json`; a.click(); showToast(t('toast.ruleJsonExported'), 'success'); }
    };

    const goToRule = id => { deps.page.value = 'rules'; ruleFilter.value.keyword = id; };

    // --- 匯出匯入整合 dropdown ---
    const showDataDropdown = ref(false);
    const toggleDataDropdown = () => { showDataDropdown.value = !showDataDropdown.value; };
    const closeDataDropdown = e => { if (!e.target.closest('.data-dropdown-wrapper')) showDataDropdown.value = false; };
    const triggerResponseImport = () => { showDataDropdown.value = false; document.getElementById('responseImportInput')?.click(); };

    const confirmOpenApiImport = async (selectedRules) => {
        if (openApiImporting.value || !selectedRules?.length) return;
        openApiImporting.value = true;
        try {
            const r = await apiCall('/api/admin/rules/import-openapi/confirm', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(selectedRules)
            });
            if (r && r.ok) {
                const d = await r.json();
                showToast(t('toast.importSuccess', { count: d.imported }), 'success');
                showOpenApiPreview.value = false;
                markDirty();
                loadRules(true);
            }
        } catch (e) {
            showToast(t('toast.importFailed'), 'error');
        } finally {
            openApiImporting.value = false;
        }
    };

    return {
        rules,
        ruleFilter,
        ruleSort,
        rulePage,
        rulePageSize,
        ruleTotalElements,
        filteredRules,
        pagedRules,
        ruleTotalPages,
        toggleRuleSort,
        ruleSortIcon,
        selectedRules,
        batchSelectMode,
        toggleSelectAll,
        ruleViewMode,
        expandedTagGroups,
        toggleTagGroup,
        expandedTagSubgroups,
        toggleTagSubgroup,
        tagKeys,
        rulesByTag,
        rulesByTagGroup,
        groupCounts,
        groupLoading,
        groupVisibleLimit,
        getGroupLimit,
        showMoreGroup,
        showAllGroup,
        loadRules,
        deleteRule,
        extendRule,
        toggleEnabled,
        exportRules,
        batchProtect,
        deleteSelectedRules,
        deleteAllRules,
        showImportModal,
        importFormat,
        importFile,
        importFileName,
        handleImportFile,
        doImport,
        // 行內預覽
        rulePreviewCache,
        rulePreviewExpanded,
        rulePreviewLoading,
        rulePreviewError,
        toggleRulePreview,
        handleRuleRowClick,
        // 拖曳排序
        dragState,
        ruleDragEnabled,
        ruleDragAvailable,
        setRuleDragEnabled,
        canDragRules,
        onDragStart,
        onDragOver,
        onDragLeave,
        onDrop,
        onDragEnd,
        dragRowClass,
        // Filter chips
        ruleFilterChips,
        removeRuleChip,
        clearRuleFilters,
        // 其他
        showPriorityHelp,
        helpTab,
        clipCopy,
        exportRuleJson,
        goToRule,
        // Dropdown
        showDataDropdown,
        toggleDataDropdown,
        closeDataDropdown,
        triggerResponseImport,
        // OpenAPI Preview
        showOpenApiPreview,
        openApiPreviewTitle,
        openApiPreviewVersion,
        openApiPreviewRules,
        openApiImporting,
        confirmOpenApiImport,
        // 快取控制（供外部使用）
        markDirty
    };
};
