/**
 * useAudit - 修訂記錄 Composable
 *
 * 管理修訂記錄（audit logs）的伺服器分頁、篩選、排序，
 * 以及單筆明細 lazy load、變更比對與格式化顯示。
 *
 * @param {Object} deps - 依賴物件
 * @param {Function} deps.showToast - Toast 通知函式（來自 useToast）
 * @param {Function} deps.showConfirm - 確認對話框函式（來自 useToast）
 * @param {Function} deps.t - 翻譯函式（來自 useI18n）
 * @param {Function} deps.requireLogin - 登入檢查函式（來自 useAuth）
 * @returns {Object} 修訂記錄狀態與操作
 */
const useAudit = (deps) => {
    const { ref, computed, watch } = Vue;
    const { showToast, showConfirm, t, requireLogin } = deps;

    // --- 資料快取機制 ---
    const dataLastLoaded = { audit: 0 };
    const DATA_TTL = 30000;
    const shouldLoad = () => !dataLastLoaded.audit || (Date.now() - dataLastLoaded.audit > DATA_TTL);
    const markLoaded = () => { dataLastLoaded.audit = Date.now(); };

    // --- 狀態 ---
    const auditLogs = ref([]);
    const selectedAudit = ref(null);
    const auditFilter = ref({ action: '', operator: '', keyword: '' });
    const savedAuditSort = JSON.parse(localStorage.getItem('auditSort') || 'null');
    const auditSort = ref(savedAuditSort || { field: 'timestamp', asc: false });
    const auditPage = ref(1);
    const auditPageSize = ref(parseInt(localStorage.getItem('auditPageSize')) || 20);
    const auditTotalElements = ref(0);
    const serverTotalPages = ref(0);
    let listRequestSequence = 0;
    let listAbortController = null;

    const detailCache = {};
    const detailCacheOrder = [];
    const DETAIL_CACHE_LIMIT = 100;
    const cacheDetail = (id, detail) => {
        if (!detailCache[id]) { detailCacheOrder.push(id); }
        detailCache[id] = detail;
        while (detailCacheOrder.length > DETAIL_CACHE_LIMIT) {
            delete detailCache[detailCacheOrder.shift()];
        }
    };

    // --- 篩選、排序、分頁 ---
    // 列表已由後端完成篩選、排序與分頁；保留 computed 名稱相容既有畫面。
    const filteredAudit = computed(() => auditLogs.value);
    const sortedAudit = computed(() => auditLogs.value);
    const pagedAudit = computed(() => auditLogs.value);
    const auditTotalPages = computed(() => Math.max(1, serverTotalPages.value));
    const auditTruncated = computed(() => false);

    /** 切換排序欄位/方向 */
    const toggleAuditSort = (field) => {
        if (auditSort.value.field === field) auditSort.value.asc = !auditSort.value.asc;
        else { auditSort.value.field = field; auditSort.value.asc = false; }
        localStorage.setItem('auditSort', JSON.stringify(auditSort.value));
    };

    /** 取得排序圖示 class */
    const auditSortIcon = (f) => auditSort.value.field === f
        ? (auditSort.value.asc ? 'bi-caret-up-fill' : 'bi-caret-down-fill')
        : 'bi-arrow-down-up';

    /** 每頁筆數變更 */
    const onAuditPageSizeChange = () => {
        localStorage.setItem('auditPageSize', auditPageSize.value);
    };

    // --- 載入與刪除 ---
    const buildAuditQuery = () => {
        const params = new URLSearchParams();
        params.set('page', String(Math.max(0, auditPage.value - 1)));
        params.set('size', String(auditPageSize.value));
        params.set('sort', auditSort.value.field);
        params.set('direction', auditSort.value.asc ? 'asc' : 'desc');
        if (auditFilter.value.action) { params.set('action', auditFilter.value.action); }
        const operator = (auditFilter.value.operator || '').trim();
        const keyword = (auditFilter.value.keyword || '').trim();
        if (operator) { params.set('operator', operator); }
        if (keyword) { params.set('keyword', keyword); }
        return '/api/admin/audit?' + params.toString();
    };

    const hydrateCachedDetails = (items) => {
        items.forEach(item => {
            if (detailCache[item.id]) { Object.assign(item, detailCache[item.id], { _detailLoaded: true }); }
        });
        return items;
    };

    const loadAuditPage = async () => {
        const requestId = ++listRequestSequence;
        if (listAbortController) { listAbortController.abort(); }
        const abortController = new AbortController();
        listAbortController = abortController;
        deps.loading.value.audit = true;
        try {
            const r = await apiCall(buildAuditQuery(), { signal: abortController.signal }, { silent: true });
            if (requestId !== listRequestSequence) { return false; }
            if (r && r.ok) {
                const data = await r.json();
                if (requestId !== listRequestSequence) { return false; }
                auditLogs.value = hydrateCachedDetails(data.results || []);
                auditTotalElements.value = Number(data.totalElements || 0);
                serverTotalPages.value = Number(data.totalPages || 0);
                selectedAudit.value = null;
                if (auditPage.value > auditTotalPages.value) { auditPage.value = auditTotalPages.value; }
                markLoaded();
            }
            return !!(r && r.ok);
        } finally {
            if (requestId === listRequestSequence) {
                if (listAbortController === abortController) { listAbortController = null; }
                deps.loading.value.audit = false;
            }
        }
    };

    const loadAudit = async (force) => {
        if (!force && !shouldLoad()) return;
        await loadAuditPage();
    };

    const reloadAuditFromFirstPage = () => {
        if (auditPage.value !== 1) { auditPage.value = 1; }
        else { loadAuditPage(); }
    };

    watch(auditFilter, () => {
        reloadAuditFromFirstPage();
    }, { deep: true });
    watch(auditSort, () => {
        reloadAuditFromFirstPage();
    }, { deep: true });
    watch(auditPage, () => loadAuditPage());
    watch(auditPageSize, () => {
        localStorage.setItem('auditPageSize', auditPageSize.value);
        reloadAuditFromFirstPage();
    });

    const toggleAuditDetail = async (log) => {
        if (selectedAudit.value === log.id) {
            selectedAudit.value = null;
            return;
        }
        selectedAudit.value = log.id;
        if (log._detailLoaded) { return; }
        if (detailCache[log.id]) {
            Object.assign(log, detailCache[log.id], { _detailLoaded: true });
            return;
        }
        log._detailLoading = true;
        try {
            const r = await apiCall('/api/admin/audit?detailId=' + encodeURIComponent(log.id), {}, { silent: true });
            if (r && r.ok) {
                const detail = await r.json();
                cacheDetail(log.id, detail);
                Object.assign(log, detail, { _detailLoaded: true });
            }
        } finally {
            log._detailLoading = false;
        }
    };

    const deleteAllAuditLogs = async () => {
        if (!await requireLogin()) return;
        if (!await showConfirm({ title: t('confirm.deleteAllAudit'), message: t('confirm.deleteAllAuditMsg'), confirmText: t('confirm.deleteAll'), danger: true, requireInput: 'DELETE', inputLabel: t('confirm.deleteAllAuditInputLabel') })) return;
        const r = await apiCall('/api/admin/audit/all', { method: 'DELETE' }, { errorMsg: t('toast.deleteAllAuditFailed') });
        if (r && r.ok) { const d = await r.json(); showToast(t('toast.deleteAllAuditSuccess', {count: d.deleted}), 'success'); loadAudit(true); }
    };

    // --- 欄位標籤與格式化 ---
    const AUDIT_FIELD_LABELS = () => ({
        matchKey: t('auditFields.matchKey'), targetHost: t('auditFields.targetHost'), method: t('auditFields.method'),
        bodyCondition: t('auditFields.bodyCondition'), queryCondition: t('auditFields.queryCondition'), headerCondition: t('auditFields.headerCondition'),
        httpStatus: t('auditFields.httpStatus'), httpHeaders: t('auditFields.httpHeaders'), delayMs: t('auditFields.delayMs'),
        priority: t('auditFields.priority'), description: t('auditFields.description'), enabled: t('auditFields.enabled'), isProtected: t('auditFields.isProtected'),
        tags: t('auditFields.tags'), sseEnabled: t('auditFields.sseEnabled'), sseLoopEnabled: t('auditFields.sseLoopEnabled'),
        responseId: t('auditFields.responseId'), queueName: t('auditFields.queueName'),
        correlationIdPattern: t('auditFields.correlationIdPattern'), body: t('auditFields.body'), contentType: t('auditFields.contentType'),
        groupId: t('auditFields.groupId'), name: t('auditFields.name'), sseEvents: t('auditFields.sseEvents')
    });

    const AUDIT_HIDDEN_FIELDS = new Set(['id', 'version', 'createdAt', 'updatedAt', 'extendedAt', 'bodySize', 'protocol', 'condition']);

    const fieldLabel = (key) => AUDIT_FIELD_LABELS()[key] || key;

    const formatFieldValue = (val) => {
        if (val === null || val === undefined || val === '') { return t('auditValues.empty'); }
        if (typeof val === 'boolean') { return val ? t('auditValues.yes') : t('auditValues.no'); }
        if (typeof val === 'object') { return JSON.stringify(val, null, 2); }
        const s = String(val);
        try {
            const parsed = JSON.parse(s);
            if (typeof parsed === 'object' && parsed !== null) { return JSON.stringify(parsed, null, 2); }
        } catch (e) { /* not JSON */ }
        return s;
    };

    const formatAuditJson = (json) => {
        try { const o = JSON.parse(json); delete o.id; delete o.version; return JSON.stringify(o, null, 2); } catch (e) { return json; }
    };

    const getAuditChanges = (log) => {
        try {
            const before = log.beforeJson ? JSON.parse(log.beforeJson) : null;
            const after = log.afterJson ? JSON.parse(log.afterJson) : null;
            const isLong = (s) => s.length > 80 || s.includes('\n');
            if (log.action === 'UPDATE' && before && after) {
                const changes = [];
                const allKeys = new Set([...Object.keys(before), ...Object.keys(after)]);
                for (const key of allKeys) {
                    if (AUDIT_HIDDEN_FIELDS.has(key)) { continue; }
                    const bv = before[key], av = after[key];
                    if (JSON.stringify(bv) !== JSON.stringify(av)) {
                        const b = formatFieldValue(bv), a = formatFieldValue(av);
                        changes.push({ label: fieldLabel(key), before: b, after: a, long: isLong(b) || isLong(a) });
                    }
                }
                return { type: 'update', changes };
            }
            const obj = after || before;
            if (!obj) { return { type: 'empty', changes: [] }; }
            const fields = [];
            for (const key of Object.keys(obj)) {
                if (AUDIT_HIDDEN_FIELDS.has(key)) { continue; }
                const val = obj[key];
                if (val === null || val === undefined || val === '') { continue; }
                const v = formatFieldValue(val);
                fields.push({ label: fieldLabel(key), value: v, long: isLong(v) });
            }
            return { type: log.action === 'CREATE' ? 'create' : 'delete', changes: fields };
        } catch (e) {
            return { type: 'error', raw: log.afterJson || log.beforeJson };
        }
    };

    const escapeHtml = (s) => s?.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') || '';

    const getAuditTarget = (log) => {
        if (!log.beforeJson && !log.afterJson) { return t('auditValues.expandForDetail'); }
        try {
            const o = JSON.parse(log.afterJson || log.beforeJson);
            if (log.ruleId && log.ruleId.startsWith('response-')) { return o.description || t('auditValues.unknown'); }
            return o.matchKey || o.description || t('auditValues.unknown');
        } catch (e) { return t('auditValues.parseError'); }
    };

    const getAuditDescription = (log) => {
        if (!log.beforeJson && !log.afterJson) { return ''; }
        try {
            const o = JSON.parse(log.afterJson || log.beforeJson);
            if (log.ruleId && log.ruleId.startsWith('response-')) { return o.contentType ? t('auditValues.typePrefix') + o.contentType : ''; }
            return o.matchKey ? o.description : '';
        } catch (e) { return ''; }
    };

    const getAuditProtocol = (log) => {
        if (log.ruleId && log.ruleId.startsWith('response-')) { return 'RESP'; }
        if (!log.beforeJson && !log.afterJson) { return ''; }
        try { const o = JSON.parse(log.afterJson || log.beforeJson); return o.protocol || ''; } catch (e) { return ''; }
    };

    const getAuditChangeCount = (log) => {
        if (log.action !== 'UPDATE') { return ''; }
        const result = getAuditChanges(log);
        if (result.type === 'update' && result.changes.length > 0) { return t('audit.fieldCount', {count: result.changes.length}); }
        return '';
    };

    // --- Filter chips ---
    const auditFilterChips = computed(() => {
        const chips = [];
        if (auditFilter.value.action) chips.push({ key: 'action', label: t('filterChips.action') + auditFilter.value.action });
        if (auditFilter.value.operator) chips.push({ key: 'operator', label: t('filterChips.operator') + auditFilter.value.operator });
        if (auditFilter.value.keyword) chips.push({ key: 'keyword', label: t('filterChips.keyword') + auditFilter.value.keyword });
        return chips;
    });

    const removeAuditChip = (key) => { auditFilter.value[key] = ''; };

    const clearAuditFilters = () => { auditFilter.value = { action: '', operator: '', keyword: '' }; };

    return {
        auditLogs,
        selectedAudit,
        auditFilter,
        auditSort,
        auditPage,
        auditPageSize,
        auditTotalElements,
        filteredAudit,
        sortedAudit,
        pagedAudit,
        auditTotalPages,
        auditTruncated,
        toggleAuditSort,
        auditSortIcon,
        onAuditPageSizeChange,
        loadAudit,
        toggleAuditDetail,
        deleteAllAuditLogs,
        AUDIT_FIELD_LABELS,
        AUDIT_HIDDEN_FIELDS,
        fieldLabel,
        formatFieldValue,
        formatAuditJson,
        getAuditChanges,
        getAuditTarget,
        getAuditDescription,
        getAuditProtocol,
        getAuditChangeCount,
        escapeHtml,
        auditFilterChips,
        removeAuditChip,
        clearAuditFilters
    };
};
