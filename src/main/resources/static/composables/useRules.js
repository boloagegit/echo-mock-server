/**
 * useRules - Rule Management Composable
 *
 * CRUD, filtering, sorting, paging, batch operations of management rules (rules),
 * Inline previews, export/import, tag grouping, and related actions.
 *
 * @param {Object} deps - Dependent objects
 * @param {Function} deps.showToast - Toast notification function (from useToast)
 * @param {Function} deps.showConfirm - Confirm dialog function (from useToast)
 * @param {Function} deps.t - Translation function (from useI18n)
 * @param {Function} deps.requireLogin - Login check function (from useAuth)
 * @param {Function} deps.login - Login jump function (from useAuth)
 * @param {import('vue').Ref} deps.isLoggedIn - Are you logged in (from useAuth)
 * @param {import('vue').Ref} deps.loading - Global loading status
 * @param {import('vue').Ref} deps.page - Current page (from app.js)
 * @param {import('vue').Ref} deps.httpLabel - HTTP protocol tag
 * @param {import('vue').Ref} deps.jmsLabel - JMS protocol tag
 * @returns {Object} Status and methods related to rule management
 */
const useRules = (deps) => {
    const { ref, computed, watch } = Vue;
    const { showToast, showConfirm, t, requireLogin, login, isLoggedIn, loading, httpLabel, jmsLabel } = deps;

    // --- Data caching mechanism ---
    const dataLastLoaded = { rules: 0 };
    const dataDirty = { rules: false };
    const DATA_TTL = 30000;
    const shouldLoad = () => !dataLastLoaded.rules || dataDirty.rules || (Date.now() - dataLastLoaded.rules > DATA_TTL);
    const markLoaded = () => { dataLastLoaded.rules = Date.now(); dataDirty.rules = false; };
    const markDirty = () => { dataDirty.rules = true; };

    // --- Status ---
    const rules = ref([]);
    const savedRuleSort = JSON.parse(localStorage.getItem('ruleSort') || 'null');
    const ruleFilter = ref({ protocol: '', enabled: '', isProtected: '', mode: '', expiring: '', unreachable: '', keyword: '' });
    const ruleSort = ref(savedRuleSort || { field: 'updatedAt', asc: false });
    const rulePage = ref(1);
    const rulePageSize = ref(20);
    const serverRuleTotalElements = ref(0);
    const serverRuleTotalPages = ref(0);
    let listRequestSequence = 0;
    let listAbortController = null;

    // --- Select ---
    const selectedRules = ref([]);
    const batchSelectMode = ref(false);

    // --- Tag grouping ---
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

    // --- Expand and collapse subgroups ---
    const expandedTagSubgroups = ref([]);
    const toggleTagSubgroup = async (key) => {
        const i = expandedTagSubgroups.value.indexOf(key);
        if (i >= 0) { expandedTagSubgroups.value.splice(i, 1); }
        else {
            expandedTagSubgroups.value.push(key);
            if (!groupLoaded.value[key]) { await loadRuleGroup(key, getGroupLimit(key)); }
        }
    };

    // --- Group progressive rendering ---
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
    // Reset when filter conditions change limit
    watch(ruleFilter, () => { groupVisibleLimit.value = {}; }, { deep: true });

    // --- Filter, sort, paging ---
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

    const toggleRuleSort = f => { ruleSort.value = ruleSort.value.field === f ? { field: f, asc: !ruleSort.value.asc } : { field: f, asc: false }; localStorage.setItem('ruleSort', JSON.stringify(ruleSort.value)); };
    const ruleSortIcon = f => ruleSort.value.field === f ? (ruleSort.value.asc ? 'bi-caret-up-fill' : 'bi-caret-down-fill') : 'bi-arrow-down-up';

    // --- Loading ---
    const buildRuleQuery = () => {
        const params = new URLSearchParams();
        params.set('page', String(Math.max(0, rulePage.value - 1)));
        params.set('size', String(rulePageSize.value));
        params.set('sort', ruleSort.value.field);
        params.set('direction', ruleSort.value.asc ? 'asc' : 'desc');
        if (ruleFilter.value.protocol) { params.set('protocol', ruleFilter.value.protocol); }
        if (ruleFilter.value.enabled !== '') { params.set('enabled', ruleFilter.value.enabled); }
        if (ruleFilter.value.isProtected !== '') { params.set('isProtected', ruleFilter.value.isProtected); }
        if (ruleFilter.value.mode) { params.set('mode', ruleFilter.value.mode); }
        if (ruleFilter.value.expiring !== '') { params.set('expiring', ruleFilter.value.expiring); }
        if (ruleFilter.value.unreachable) { params.set('unreachable', ruleFilter.value.unreachable); }
        const keyword = (ruleFilter.value.keyword || '').trim();
        if (keyword) { params.set('keyword', keyword); }
        return '/api/admin/rules/page?' + params.toString();
    };

    const buildRuleGroupSummaryQuery = () => {
        const params = new URLSearchParams();
        if (ruleFilter.value.protocol) { params.set('protocol', ruleFilter.value.protocol); }
        if (ruleFilter.value.enabled !== '') { params.set('enabled', ruleFilter.value.enabled); }
        if (ruleFilter.value.isProtected !== '') { params.set('isProtected', ruleFilter.value.isProtected); }
        if (ruleFilter.value.mode) { params.set('mode', ruleFilter.value.mode); }
        if (ruleFilter.value.expiring !== '') { params.set('expiring', ruleFilter.value.expiring); }
        if (ruleFilter.value.unreachable) { params.set('unreachable', ruleFilter.value.unreachable); }
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
        if (ruleFilter.value.mode) { params.set('mode', ruleFilter.value.mode); }
        if (ruleFilter.value.expiring !== '') { params.set('expiring', ruleFilter.value.expiring); }
        if (ruleFilter.value.unreachable) { params.set('unreachable', ruleFilter.value.unreachable); }
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
                // Details may have changed on the server; refetch the open drawer instead of showing stale data.
                rulePreviewCache.value = {};
                if (ruleDetailId.value) { loadRuleDetail(ruleDetailId.value); }
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

    const reloadRulesFromFirstPage = () => {
        if (rulePage.value !== 1) { rulePage.value = 1; }
        else { loadRules(true); }
    };

    const clearRuleSelection = () => { selectedRules.value = []; };
    watch(ruleFilter, () => { clearRuleSelection(); reloadRulesFromFirstPage(); }, { deep: true });
    watch(ruleSort, () => { clearRuleSelection(); reloadRulesFromFirstPage(); }, { deep: true });
    watch(rulePage, () => {
        clearRuleSelection();
        if (ruleViewMode.value === 'list') { loadRules(true); }
    });
    watch(rulePageSize, () => {
        clearRuleSelection();
        if (ruleViewMode.value === 'list') { reloadRulesFromFirstPage(); }
    });
    watch(ruleViewMode, v => {
        localStorage.setItem('ruleViewMode', v);
        rulePage.value = 1;
        selectedRules.value = [];
        loadRules(true);
    });

    // --- CRUD ---
    // Repeated clicks are ignored when the action of the same rule (or the same batch of actions) is being processed.
    const inFlight = createInFlight();
    const deleteRule = id => inFlight.run('delete:' + id, async () => {
        if (!await requireLogin()) { return; }
        if (!await showConfirm({ title: t('confirm.deleteRule'), message: t('confirm.deleteRuleMsg'), confirmText: t('confirm.delete'), danger: true })) { return; }
        const backup = [...rules.value];
        rules.value = rules.value.filter(r => r.id !== id);
        const r = await apiCall(`/api/admin/rules/${id}`, { method: 'DELETE' }, { errorMsg: t('toast.ruleDeleteFailed') });
        if (r && r.ok) {
            if (ruleDetailId.value === id) { closeRuleDetail(); }
            showToast(t('toast.ruleDeleteSuccess'), 'success');
            markDirty();
            await loadRules(true);
        } else {
            rules.value = backup;
            if (r && (r.status === 401 || r.status === 403)) { login(); }
        }
    });

    const extendRule = id => inFlight.run('extend:' + id, async () => {
        if (!await requireLogin()) return;
        if (!await showConfirm({ title: t('confirm.extendRule'), message: t('confirm.extendRuleMsg') })) return;
        const r = await apiCall(`/api/admin/rules/${id}/extend`, { method: 'PUT' }, { errorMsg: t('toast.ruleExtendFailed') });
        if (r && r.ok) { showToast(t('toast.ruleExtendSuccess'), 'success'); markDirty(); loadRules(true); }
    });

    /**
     * Toggle enabled status: effective immediately and provide "Revert" in notification.
     * This is a reversible operation, so it will no longer be confirmed one after another; irreversible operations such as deletion will still be confirmed.
     */
    const toggleEnabled = (rule, { offerUndo = true } = {}) => inFlight.run('toggle:' + rule.id, async () => {
        if (!await requireLogin()) return;
        const newEnabled = rule.enabled === false;
        const action = newEnabled ? 'enable' : 'disable';
        const r = await apiCall(`/api/admin/rules/${rule.id}/${action}`, { method: 'PUT' }, { errorMsg: t('toast.ruleStatusFailed') });
        if (r && r.ok) {
            rule.enabled = newEnabled;
            const name = rule.description || rule.matchKey || shortId(rule.id);
            const undo = offerUndo ? { label: t('common.undo'), handler: () => toggleEnabled(rule, { offerUndo: false }) } : null;
            showToast(t(newEnabled ? 'toast.ruleEnabledNamed' : 'toast.ruleDisabledNamed', { name }), 'success', undo);
            // Update in place: reloading would re-sort by update time and make the row jump away.
            markDirty();
            if (ruleDetailId.value === rule.id && rulePreviewCache.value[rule.id]) { rulePreviewCache.value[rule.id].enabled = newEnabled; }
        }
    });

    const toggleSelectAll = e => { selectedRules.value = e.target.checked ? pagedRules.value.map(r => r.id) : [] };

    // --- Export and import ---
    const ruleFilterParams = () => {
        const params = new URLSearchParams();
        if (ruleFilter.value.protocol) { params.set('protocol', ruleFilter.value.protocol); }
        if (ruleFilter.value.enabled !== '') { params.set('enabled', ruleFilter.value.enabled); }
        if (ruleFilter.value.isProtected !== '') { params.set('isProtected', ruleFilter.value.isProtected); }
        if (ruleFilter.value.mode) { params.set('mode', ruleFilter.value.mode); }
        if (ruleFilter.value.expiring !== '') { params.set('expiring', ruleFilter.value.expiring); }
        if (ruleFilter.value.unreachable) { params.set('unreachable', ruleFilter.value.unreachable); }
        const keyword = (ruleFilter.value.keyword || '').trim();
        if (keyword) { params.set('keyword', keyword); }
        return params;
    };
    const showExportModal = ref(false);
    const exportScope = ref('all');
    const exportCounts = ref({ all: null, filtered: 0, selected: 0, filterActive: false });
    const exporting = ref(false);
    const openExportModal = async () => {
        showDataDropdown.value = false;
        const filterActive = ruleFilterParams().toString() !== '';
        const selected = selectedRules.value.length;
        exportCounts.value = { all: filterActive ? null : ruleTotalElements.value, filtered: ruleTotalElements.value, selected, filterActive };
        exportScope.value = selected ? 'selected' : filterActive ? 'filtered' : 'all';
        showExportModal.value = true;
        if (filterActive) {
            const r = await apiCall('/api/admin/rules/page?page=0&size=1', {}, { silent: true });
            if (r && r.ok) {
                const d = await r.json().catch(() => null);
                if (d && Number.isFinite(d.totalElements)) { exportCounts.value = { ...exportCounts.value, all: d.totalElements }; }
            }
        }
    };
    const exportRules = async (scope = exportScope.value) => {
        if (exporting.value) { return; }
        exporting.value = true;
        try {
            const r = scope === 'selected'
                ? await apiCall('/api/admin/rules/export', { method: 'POST', body: JSON.stringify({ ids: selectedRules.value }) }, { errorMsg: t('toast.exportFailed') })
                : await apiCall('/api/admin/rules/export' + (scope === 'filtered' ? '?' + ruleFilterParams().toString() : ''), {}, { errorMsg: t('toast.exportFailed') });
            if (r && r.ok) {
                const data = await r.json();
                const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
                const a = document.createElement('a'); a.href = URL.createObjectURL(blob);
                a.download = exportFileName(scope); a.click();
                showExportModal.value = false;
                showToast(t('toast.exportSuccess', {count: data.length}), 'success');
            }
        } finally {
            exporting.value = false;
        }
    };
    const exportFileName = scope => `echo-rules-${new Date().toISOString().slice(0, 10)}${scope === 'all' ? '' : '-' + scope}.json`;

    const showImportModal = ref(false);
    const importFormat = ref('json');
    const importFile = ref(null);
    const importFileName = ref('');
    const importPreview = ref(null);
    const importConnectionMappings = ref({});
    /** Row numbers the user unchecked; rules matching existing ones start unchecked. */
    const importExcluded = ref([]);
    /** file → review → connections (only when an included rule forwards). */
    const importStep = ref('file');
    const importSourceInfo = ref({ title: '', version: '' });
    let importRulesDraft = null;
    const resetImportPreview = () => {
        importPreview.value = null;
        importConnectionMappings.value = {};
        importExcluded.value = [];
        importStep.value = 'file';
        importSourceInfo.value = { title: '', version: '' };
        importRulesDraft = null;
    };
    const setImportConnectionMapping = ({ key, selection }) => {
        importConnectionMappings.value = { ...importConnectionMappings.value, [key]: selection };
    };
    const setImportExcluded = rows => { importExcluded.value = [...new Set(rows)].sort((x, y) => x - y); };
    const toggleImportRow = row => {
        setImportExcluded(importExcluded.value.includes(row)
            ? importExcluded.value.filter(item => item !== row) : [...importExcluded.value, row]);
    };
    /** Requirements still used by at least one checked rule. */
    const activeImportRequirements = () => (importPreview.value?.requirements || []).filter(item =>
        !Array.isArray(item.rows) || item.rows.some(row => !importExcluded.value.includes(row)));
    const importMappingValid = item => (importPreview.value?.connections || []).some(option =>
        option.protocol === item.protocol && option.selection === importConnectionMappings.value[item.key]);
    const applyImportPreview = (preview, previousMappings = {}) => {
        importPreview.value = preview;
        const mappings = {};
        for (const item of preview.requirements || []) {
            const previous = previousMappings[item.key];
            const stillOffered = (preview.connections || []).some(option => option.protocol === item.protocol && option.selection === previous);
            const choice = stillOffered ? previous : item.suggestion;
            if (choice) { mappings[item.key] = choice; }
        }
        importConnectionMappings.value = mappings;
    };
    const handleImportFile = input => {
        if (deps.loading.value.importRules) { return; }
        resetImportPreview();
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

    const refreshAfterImport = async () => {
        markDirty();
        try { await loadRules(true); }
        catch (err) {
            loading.value.rulesError = t('rules.loadFailed');
            showToast(t('toast.ruleLoadFailed'), 'error');
        }
    };

    /** Reads the chosen file into portable rules; every format then shares one review. */
    const readImportRules = async () => {
        if (importFormat.value === 'json') {
            let data;
            try { data = JSON.parse(await importFile.value.text()); }
            catch { throw new Error(t('modal.importFileFormatError')); }
            return Array.isArray(data) ? data : [data];
        }
        const formData = new FormData();
        formData.append('file', importFile.value);
        const url = importFormat.value === 'openapi' ? '/api/admin/rules/import-openapi/preview' : '/api/admin/rules/import-excel/parse';
        const r = await apiCall(url, { method: 'POST', body: formData }, { silent: true });
        if (!r) { throw new Error(t('toast.networkError')); }
        const d = await r.json().catch(() => ({}));
        if (!r.ok || (importFormat.value === 'openapi' && !d.success)) {
            throw new Error(importFormat.value === 'openapi'
                ? t('modal.importOpenApiParseFailed', { detail: (d.errors && d.errors[0]) || d.error || '' })
                : t('modal.importExcelParseFailed'));
        }
        if (importFormat.value === 'openapi') { importSourceInfo.value = { title: d.title || '', version: d.version || '' }; }
        return d.rules || [];
    };

    const requestImportPreview = async (previousMappings = {}) => {
        const r = await apiCall('/api/admin/rules/import/preview', {
            method: 'POST', body: JSON.stringify({ rules: importRulesDraft, connectionMappings: {} })
        }, { silent: true });
        const d = r ? await r.json().catch(() => ({})) : {};
        if (!r || !r.ok) {
            deps.loading.value.importError = r ? (d.error || t('modal.importRequestFailed')) : t('toast.networkError');
            return false;
        }
        applyImportPreview(d, previousMappings);
        return true;
    };

    const confirmImport = async () => {
        if (activeImportRequirements().some(item => !importMappingValid(item))) {
            deps.loading.value.importError = t('modal.importChooseConnections');
            importStep.value = 'connections';
            return;
        }
        const connectionMappings = {};
        for (const item of activeImportRequirements()) { connectionMappings[item.key] = importConnectionMappings.value[item.key]; }
        let r;
        try {
            r = await apiCall('/api/admin/rules/import/confirm', {
                method: 'POST', body: JSON.stringify({ rules: importRulesDraft, connectionMappings, excludedRows: importExcluded.value })
            }, { silent: true });
        } catch { r = null; }
        const d = r ? await r.json().catch(() => ({})) : {};
        if (r && r.ok) {
            showImportModal.value = false;
            resetImportPreview();
            importFile.value = null;
            importFileName.value = '';
            showToast(t('toast.importSuccess', {count: d.imported}), 'success');
            await refreshAfterImport();
        } else if (r && r.status < 500) {
            const errors = d.errors || [];
            if (errors.some(item => item.code === 'CONNECTION_SELECTION_REQUIRED')) {
                // A destination changed after the preview: refresh the choices and keep those still offered.
                const excluded = importExcluded.value;
                await requestImportPreview(importConnectionMappings.value);
                importExcluded.value = excluded;
                importStep.value = 'connections';
                deps.loading.value.importError = t('modal.importConnectionChanged');
            } else {
                importPreview.value = { ...importPreview.value, errors };
                importStep.value = 'review';
                deps.loading.value.importError = t('modal.importRequestFailed');
            }
        } else {
            // A lost reply or server error cannot prove whether the transaction committed.
            deps.loading.value.importError = t('modal.importResultUnknown');
            resetImportPreview();
            await refreshAfterImport();
        }
    };

    /** The dialog's primary action for the current step. */
    const doImport = async () => {
        if (!importFile.value || deps.loading.value.importRules) return;
        deps.loading.value.importRules = true;
        deps.loading.value.importError = '';
        try {
            if (!importPreview.value) {
                importRulesDraft = await readImportRules();
                if (!importRulesDraft.length) {
                    deps.loading.value.importError = t('modal.importNoRules');
                    importRulesDraft = null;
                    return;
                }
                if (await requestImportPreview()) {
                    setImportExcluded((importPreview.value.rows || []).filter(row => row.duplicate).map(row => row.row));
                    importStep.value = 'review';
                }
                return;
            }
            if (importStep.value === 'review' && activeImportRequirements().length) {
                importStep.value = 'connections';
                return;
            }
            await confirmImport();
        } catch (err) {
            deps.loading.value.importError = err?.message || t('modal.importFileFormatError');
        } finally {
            deps.loading.value.importRules = false;
        }
    };
    const importBack = () => {
        deps.loading.value.importError = '';
        if (importStep.value === 'connections') { importStep.value = 'review'; return; }
        resetImportPreview();
    };

    // --- Batch operation ---
    const batchProtect = (protect) => inFlight.run('batch', async () => {
        if (!await requireLogin()) return;
        const url = protect ? '/api/admin/rules/batch/protect' : '/api/admin/rules/batch/unprotect';
        // The backend only reports the actual number of changes (those already protected are not counted), and the notification displays the selected number.
        const count = selectedRules.value.length;
        const r = await apiCall(url, { method: 'PUT', body: JSON.stringify(selectedRules.value) }, { errorMsg: t('toast.batchOperationFailed') });
        if (r && r.ok) { showToast(protect ? t('toast.batchProtected', {count}) : t('toast.batchUnprotected', {count}), 'success'); selectedRules.value = []; markDirty(); loadRules(true); }
    });

    const deleteSelectedRules = () => inFlight.run('batch', async () => {
        if (!await requireLogin()) return;
        const protectedCount = rules.value.filter(r => selectedRules.value.includes(r.id) && r.isProtected).length;
        const msg = protectedCount
            ? t('confirm.batchDeleteRulesMsg', {count: selectedRules.value.length}) + '\n\n' + t('confirm.batchDeleteProtectedWarn', {count: protectedCount})
            : t('confirm.batchDeleteRulesMsg', {count: selectedRules.value.length});
        if (!await showConfirm({ title: t('confirm.batchDeleteRules'), message: msg, confirmText: t('confirm.delete'), danger: true })) return;
        const r = await apiCall('/api/admin/rules/batch', { method: 'DELETE', body: JSON.stringify(selectedRules.value) }, { errorMsg: t('toast.batchDeleteFailed') });
        if (r && r.ok) { const d = await r.json(); showToast(t('toast.batchDeleteSuccess', {count: d.deleted}), 'success'); selectedRules.value = []; markDirty(); loadRules(true); }
    });

    const deleteAllRules = (knownCount = null) => inFlight.run('all', async () => {
        if (!await requireLogin()) return;
        const count = knownCount != null && Number.isFinite(Number(knownCount)) ? Number(knownCount) : ruleTotalElements.value;
        if (!await showConfirm({ title: t('confirm.deleteAllRules'), message: t('confirm.deleteAllRulesMsg', {count}), confirmText: t('confirm.deleteAll'), danger: true, requireInput: String(count), inputLabel: t('confirm.deleteAllRulesInputLabel', {count}) })) return;
        const r = await apiCall('/api/admin/rules/all', { method: 'DELETE' }, { errorMsg: t('toast.deleteAllRulesFailed') });
        if (r && r.ok) { const d = await r.json(); showToast(t('toast.deleteAllRulesSuccess', {count: d.deleted}), 'success'); markDirty(); loadRules(true); }
    });

    // ---Details Drawer ---
    const ruleDetailId = ref(null);
    const rulePreviewCache = ref({});
    const rulePreviewLoading = ref({});
    const rulePreviewError = ref({});
    const hydrateForwardTarget = async (data) => {
        if (data?.action !== 'FORWARD' || data?.forwardTargetMode === 'ORIGINAL_HOST') return;
        const protocol = String(data.protocol || '').toUpperCase();
        const isJms = protocol === 'JMS';
        const endpoint = isJms ? '/api/admin/jms-target-connections' : '/api/admin/http-target-connections';
        const response = await apiCall(endpoint, {}, { silent: true });
        if (!response || !response.ok) return;
        const targets = await response.json().catch(() => []);
        if (!Array.isArray(targets)) return;
        const connectionId = isJms ? data.jmsTargetConnectionId : data.httpTargetConnectionId;
        const target = data.forwardTargetMode === 'CONNECTION'
            ? targets.find(item => String(item.id) === String(connectionId))
            : targets.find(item => item.defaultConnection);
        if (!target) return;
        data._forwardTargetName = target.name || '';
        data._forwardTargetEndpoint = isJms ? (target.serverUrl || '') : (target.baseUrl || '');
    };
    const loadRuleDetail = async (id, { force = false } = {}) => {
        if (!id || rulePreviewLoading.value[id] || (!force && rulePreviewCache.value[id])) { return; }
        rulePreviewLoading.value[id] = true;
        rulePreviewError.value[id] = false;
        const r = await apiCall(`/api/admin/rules/${id}`, {}, { silent: true });
        if (r && r.ok) {
            const data = await r.json();
            await hydrateForwardTarget(data);
            const body = data.responseBody || '';
            // SSE Content: Display the actual sent stream text, with a one-line summary (number of events, full length, loop, where to end the connection)
            const events = data.sseEnabled ? deserializeSseEvents(body) : [];
            if (events.length && events[0].data) {
                data._previewBody = SseEvents.rawStream(events, data.sseLoopEnabled === true, t);
                data._sseSummary = SseEvents.summary(events, data.sseLoopEnabled === true);
            } else {
                data._previewBody = body;
            }
            rulePreviewCache.value[id] = data;
        } else {
            rulePreviewError.value[id] = true;
        }
        rulePreviewLoading.value[id] = false;
    };
    const openRuleDetail = rule => {
        if (!rule?.id) { return; }
        ruleDetailId.value = rule.id;
        loadRuleDetail(rule.id);
    };
    const closeRuleDetail = () => { ruleDetailId.value = null; };


    // --- Filter chips ---
    const ruleFilterChips = computed(() => {
        const chips = [];
        if (ruleFilter.value.protocol) chips.push({ key: 'protocol', label: t('filterChips.protocol') + (ruleFilter.value.protocol === 'HTTP' ? httpLabel.value : jmsLabel.value) });
        if (ruleFilter.value.enabled) chips.push({ key: 'enabled', label: t('filterChips.status') + (ruleFilter.value.enabled === 'true' ? t('rules.filterEnabled') : t('rules.filterDisabled')) });
        if (ruleFilter.value.isProtected) chips.push({ key: 'isProtected', label: t('filterChips.protection') + (ruleFilter.value.isProtected === 'true' ? t('rules.filterProtected') : t('rules.filterUnprotected')) });
        if (ruleFilter.value.mode) chips.push({ key: 'mode', label: t('filterChips.mode') + t('rules.mode_' + ruleFilter.value.mode) });
        if (ruleFilter.value.expiring) chips.push({ key: 'expiring', label: t('rules.filterExpiring') });
        if (ruleFilter.value.unreachable) chips.push({ key: 'unreachable', label: t('rules.order.filterLabel') });
        if (ruleFilter.value.keyword) chips.push({ key: 'keyword', label: t('filterChips.keyword') + ruleFilter.value.keyword });
        return chips;
    });
    const removeRuleChip = key => { ruleFilter.value[key] = ''; };
    const clearRuleFilters = () => { ruleFilter.value = { protocol: '', enabled: '', isProtected: '', mode: '', expiring: '', unreachable: '', keyword: '' }; };

    // --- Others ---
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

    const goToRule = id => { deps.page.value = 'rules'; ruleFilter.value.keyword = id; openRuleDetail({ id }); };

    // --- Export and import integration dropdown ---
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
        showExportModal,
        exportScope,
        exportCounts,
        exporting,
        openExportModal,
        batchProtect,
        deleteSelectedRules,
        deleteAllRules,
        showImportModal,
        importFormat,
        importFile,
        importFileName,
        handleImportFile,
        doImport,
        importPreview,
        importConnectionMappings,
        resetImportPreview,
        setImportConnectionMapping,
        importExcluded,
        importStep,
        importSourceInfo,
        setImportExcluded,
        toggleImportRow,
        importBack,
        // Details drawer
        ruleDetailId,
        rulePreviewCache,
        rulePreviewLoading,
        rulePreviewError,
        loadRuleDetail,
        openRuleDetail,
        closeRuleDetail,
        // Filter chips
        ruleFilterChips,
        removeRuleChip,
        clearRuleFilters,
        // Other
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
        // Cache control (for external use)
        markDirty
    };
};
