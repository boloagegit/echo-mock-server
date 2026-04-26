/**
 * useRules - 規則管理 Composable
 *
 * 管理規則（rules）的 CRUD、篩選、排序、分頁、批次操作、
 * 行內預覽、匯出匯入、標籤分組等功能。
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

    // --- 工具函式 ---
    const matchAll = (text, keywords) => keywords.every(kw => text.includes(kw));

    // --- 狀態 ---
    const rules = ref([]);
    const savedRuleSort = JSON.parse(localStorage.getItem('ruleSort') || 'null');
    const ruleFilter = ref({ protocol: '', enabled: '', isProtected: '', keyword: '' });
    const ruleSort = ref(savedRuleSort || { field: 'updatedAt', asc: false });
    const rulePage = ref(1);
    const rulePageSize = ref(20);

    // --- 選取 ---
    const selectedRules = ref([]);
    const batchSelectMode = ref(false);

    // --- 標籤分組 ---
    const ruleViewMode = ref(localStorage.getItem('ruleViewMode') || 'list');
    const expandedTagGroups = ref([]);

    const toggleTagGroup = g => { const i = expandedTagGroups.value.indexOf(g); i >= 0 ? expandedTagGroups.value.splice(i, 1) : expandedTagGroups.value.push(g) };

    // --- 子群組展開收合 ---
    const expandedTagSubgroups = ref([]);
    const toggleTagSubgroup = (key) => {
        const i = expandedTagSubgroups.value.indexOf(key);
        if (i >= 0) { expandedTagSubgroups.value.splice(i, 1); }
        else { expandedTagSubgroups.value.push(key); }
    };

    // --- 群組漸進渲染 ---
    const GROUP_PAGE_SIZE = 20;
    const groupVisibleLimit = ref({});
    const getGroupLimit = (key) => groupVisibleLimit.value[key] || GROUP_PAGE_SIZE;
    const showMoreGroup = (key) => { groupVisibleLimit.value = { ...groupVisibleLimit.value, [key]: getGroupLimit(key) + GROUP_PAGE_SIZE }; };
    const showAllGroup = (key, total) => { groupVisibleLimit.value = { ...groupVisibleLimit.value, [key]: total }; };
    // 篩選條件變更時重置 limit
    watch(ruleFilter, () => { groupVisibleLimit.value = {}; }, { deep: true });

    watch(ruleViewMode, v => localStorage.setItem('ruleViewMode', v));

    // --- 篩選、排序、分頁 ---
    const filteredRules = computed(() => {
        let list = rules.value.filter(r => {
            if (ruleFilter.value.protocol && r.protocol !== ruleFilter.value.protocol) return false;
            if (ruleFilter.value.enabled !== '') {
                const en = ruleFilter.value.enabled === 'true';
                if ((r.enabled !== false) !== en) return false;
            }
            if (ruleFilter.value.isProtected !== '') {
                const prot = ruleFilter.value.isProtected === 'true';
                if ((r.isProtected === true) !== prot) return false;
            }
            if (ruleFilter.value.keyword) {
                const keywords = ruleFilter.value.keyword.toLowerCase().split(/\s+/).filter(k => k);
                const tagsStr = r.tags ? Object.entries(parseTags(r.tags)).map(([k,v])=>k+':'+v).join(' ').toLowerCase() : '';
                const searchText = [r.id, r.matchKey, r.description, r.targetHost, tagsStr, r.bodyCondition, r.queryCondition, r.headerCondition, r.scenarioName].join(' ').toLowerCase();
                if (!matchAll(searchText, keywords)) return false;
            }
            return true;
        });
        const f = ruleSort.value.field, asc = ruleSort.value.asc;
        list.sort((a, b) => { const av = a[f], bv = b[f]; return (av < bv ? -1 : av > bv ? 1 : 0) * (asc ? 1 : -1) });
        return list;
    });

    const ruleTotalPages = computed(() => Math.max(1, Math.ceil(filteredRules.value.length / rulePageSize.value)));
    const pagedRules = computed(() => filteredRules.value.slice((rulePage.value - 1) * rulePageSize.value, rulePage.value * rulePageSize.value));

    const tagKeys = computed(() => {
        const keys = {};
        filteredRules.value.forEach(r => {
            const tags = parseTags(r.tags);
            Object.entries(tags).forEach(([k, v]) => { if (!keys[k]) keys[k] = new Set(); keys[k].add(v) });
        });
        return Object.fromEntries(Object.entries(keys).map(([k, v]) => [k, [...v].sort()]));
    });

    const rulesByTag = computed(() => {
        const map = {};
        filteredRules.value.forEach(r => {
            const tags = parseTags(r.tags);
            Object.entries(tags).forEach(([k, v]) => { const key = k + '=' + v; if (!map[key]) map[key] = []; map[key].push(r) });
        });
        return map;
    });

    const rulesByTagGroup = computed(() => ({ '_untagged': filteredRules.value.filter(r => !r.tags) }));

    const toggleRuleSort = f => { ruleSort.value = ruleSort.value.field === f ? { field: f, asc: !ruleSort.value.asc } : { field: f, asc: false }; localStorage.setItem('ruleSort', JSON.stringify(ruleSort.value)); rulePage.value = 1 };
    const ruleSortIcon = f => ruleSort.value.field === f ? (ruleSort.value.asc ? 'bi-caret-up-fill' : 'bi-caret-down-fill') : 'bi-arrow-down-up';

    watch(ruleFilter, () => rulePage.value = 1, { deep: true });

    // --- 載入 ---
    const loadRules = async (force) => {
        if (!force && !shouldLoad()) return;
        loading.value.rules = true;
        const r = await apiCall('/api/admin/rules', {}, { errorMsg: t('toast.ruleLoadFailed') });
        if (r && r.ok) { rules.value = await r.json(); markLoaded(); rulePreviewExpanded.value = {}; }
        loading.value.rules = false;
    };

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
        if (r && r.ok) { rule.enabled = newEnabled; showToast(newEnabled ? t('toast.ruleEnabled') : t('toast.ruleDisabled'), 'success'); }
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
    const handleImportFile = e => {
        const file = e.target.files[0];
        if (file) { importFile.value = file; importFileName.value = file.name; }
    };

    // --- OpenAPI Preview ---
    const showOpenApiPreview = ref(false);
    const openApiPreviewTitle = ref('');
    const openApiPreviewVersion = ref('');
    const openApiPreviewRules = ref([]);
    const openApiImporting = ref(false);

    const doImport = async () => {
        if (!importFile.value) return;
        try {
            if (importFormat.value === 'openapi') {
                // OpenAPI: 先預覽
                const formData = new FormData();
                formData.append('file', importFile.value);
                const r = await fetch('/api/admin/rules/import-openapi/preview', { method: 'POST', body: formData, headers: { 'Accept': 'application/json' } });
                if (r.ok) {
                    const d = await r.json();
                    if (d.success) {
                        openApiPreviewTitle.value = d.title || '';
                        openApiPreviewVersion.value = d.version || '';
                        openApiPreviewRules.value = d.rules || [];
                        showImportModal.value = false;
                        showOpenApiPreview.value = true;
                    } else {
                        showToast((d.errors && d.errors[0]) || t('toast.openApiParseFailed'), 'error');
                    }
                } else {
                    const e = await r.json().catch(() => ({}));
                    showToast((e.errors && e.errors[0]) || t('toast.openApiParseFailed'), 'error');
                }
                importFile.value = null; importFileName.value = '';
                return;
            } else if (importFormat.value === 'json') {
                const text = await importFile.value.text();
                const data = JSON.parse(text);
                const arr = Array.isArray(data) ? data : [data];
                const r = await apiCall('/api/admin/rules/import-batch', { method: 'POST', body: JSON.stringify(arr) }, { errorMsg: t('toast.importFailed') });
                if (r && r.ok) { const d = await r.json(); showToast(t('toast.importSuccess', {count: d.imported}), 'success'); markDirty(); loadRules(true); showImportModal.value = false; }
            } else {
                const formData = new FormData();
                formData.append('file', importFile.value);
                const r = await fetch('/api/admin/rules/import-excel', { method: 'POST', body: formData });
                if (r.ok) { const d = await r.json(); showToast(t('toast.importSuccess', {count: d.imported}), 'success'); markDirty(); loadRules(true); showImportModal.value = false; }
                else { const e = await r.json(); showToast(e.error || t('toast.importFailed'), 'error') }
            }
        } catch (err) { showToast(t('toast.fileFormatError'), 'error') }
        importFile.value = null; importFileName.value = '';
    };

    const confirmOpenApiImport = async (selectedRules) => {
        openApiImporting.value = true;
        try {
            const r = await apiCall('/api/admin/rules/import-openapi/confirm', {
                method: 'POST',
                body: JSON.stringify(selectedRules)
            }, { errorMsg: t('toast.importFailed') });
            if (r && r.ok) {
                const d = await r.json();
                showToast(t('toast.importSuccess', {count: d.imported}), 'success');
                markDirty();
                loadRules(true);
                showOpenApiPreview.value = false;
            }
        } catch (err) {
            showToast(t('toast.importFailed'), 'error');
        }
        openApiImporting.value = false;
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
        const count = rules.value.length;
        if (!await showConfirm({ title: t('confirm.deleteAllRules'), message: t('confirm.deleteAllRulesMsg', {count}), confirmText: t('confirm.deleteAll'), danger: true, requireInput: String(count), inputLabel: t('confirm.deleteAllRulesInputLabel', {count}) })) return;
        const r = await apiCall('/api/admin/rules/all', { method: 'DELETE' }, { errorMsg: t('toast.deleteAllRulesFailed') });
        if (r && r.ok) { const d = await r.json(); showToast(t('toast.deleteAllRulesSuccess', {count: d.deleted}), 'success'); loadRules(); }
    };

    // --- 行內預覽 ---
    const rulePreviewCache = ref({});
    const rulePreviewExpanded = ref({});
    const rulePreviewLoading = ref({});
    const toggleRulePreview = async (rule) => {
        const id = rule.id;
        if (rulePreviewExpanded.value[id]) {
            rulePreviewExpanded.value[id] = false;
            return;
        }
        if (rulePreviewCache.value[id]) {
            rulePreviewExpanded.value[id] = true;
            return;
        }
        rulePreviewLoading.value[id] = true;
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

    return {
        rules,
        ruleFilter,
        ruleSort,
        rulePage,
        rulePageSize,
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
        // OpenAPI Preview
        showOpenApiPreview,
        openApiPreviewTitle,
        openApiPreviewVersion,
        openApiPreviewRules,
        openApiImporting,
        confirmOpenApiImport,
        // 行內預覽
        rulePreviewCache,
        rulePreviewExpanded,
        rulePreviewLoading,
        toggleRulePreview,
        handleRuleRowClick,
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
        // 快取控制（供外部使用）
        markDirty
    };
};
