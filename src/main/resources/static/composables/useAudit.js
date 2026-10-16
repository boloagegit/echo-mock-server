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
    const auditFilter = ref({ action: '', operator: '', keyword: '', type: '', ruleId: '' });
    // 單一規則／回應的最近記錄（詳細面板下方的時間軸），以 ruleId 為鍵。
    const targetHistory = ref({});
    const TARGET_HISTORY_SIZE = 8;
    const savedAuditSort = JSON.parse(localStorage.getItem('auditSort') || 'null');
    const auditSort = ref(savedAuditSort || { field: 'timestamp', asc: false });
    const auditPage = ref(1);
    const auditPageSize = ref(parseInt(localStorage.getItem('auditPageSize')) || 20);
    const auditTotalElements = ref(0);
    // 伺服器的「今天」（修訂時間沒有時區，以伺服器日期判斷今天／昨天）。
    const auditToday = ref('');
    const serverTotalPages = ref(0);
    let listRequestSequence = 0;
    let listAbortController = null;

    const detailCache = {};
    const detailCacheOrder = [];
    const DETAIL_CACHE_LIMIT = 100;
    const cacheDetail = (id, detail) => {
        if (!detailCache[id]) { detailCacheOrder.push(id); }
        // 列表摘要以列表 API 的版本為準，明細只補上前後快照。
        const { summary, ...snapshot } = detail || {};
        detailCache[id] = snapshot;
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
        if (auditFilter.value.type) { params.set('type', auditFilter.value.type); }
        if (auditFilter.value.ruleId) { params.set('ruleId', auditFilter.value.ruleId); }
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
            // A failed load must not masquerade as "no audit logs yet".
            deps.loading.value.auditError = !(r && r.ok);
            if (r && r.ok) {
                const data = await r.json();
                if (requestId !== listRequestSequence) { return false; }
                auditLogs.value = hydrateCachedDetails(data.results || []);
                targetHistory.value = {};
                auditTotalElements.value = Number(data.totalElements || 0);
                auditToday.value = typeof data.today === 'string' ? data.today : '';
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

    /** 載入某個規則／回應最近的修訂記錄；同一次列表載入內只抓一次。 */
    const loadTargetHistory = async (ruleId) => {
        if (!ruleId || targetHistory.value[ruleId]) { return; }
        targetHistory.value = { ...targetHistory.value, [ruleId]: { loading: true, items: [], total: 0, failed: false } };
        const params = new URLSearchParams({ page: '0', size: String(TARGET_HISTORY_SIZE), sort: 'timestamp', direction: 'desc', ruleId });
        let entry = { loading: false, items: [], total: 0, failed: true };
        try {
            const r = await apiCall('/api/admin/audit?' + params.toString(), {}, { silent: true });
            if (r && r.ok) {
                const data = await r.json();
                entry = { loading: false, items: data.results || [], total: Number(data.totalElements || 0), failed: false };
            }
        } finally {
            targetHistory.value = { ...targetHistory.value, [ruleId]: entry };
        }
    };

    /** 只看某一個規則或回應的全部修訂記錄。 */
    const filterAuditTarget = (ruleId) => {
        auditFilter.value = { action: '', operator: '', keyword: '', type: '', ruleId: ruleId || '' };
    };

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
                cacheDetail(log.id, await r.json());
                Object.assign(log, detailCache[log.id], { _detailLoaded: true });
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
        groupId: t('auditFields.groupId'), name: t('auditFields.name'), sseEvents: t('auditFields.sseEvents'),
        action: t('auditFields.action'), faultType: t('auditFields.faultType'), forwardTargetMode: t('auditFields.forwardTargetMode'),
        httpTargetConnectionId: t('auditFields.httpTargetConnectionId'), jmsTargetConnectionId: t('auditFields.jmsTargetConnectionId')
    });

    const AUDIT_HIDDEN_FIELDS = new Set(['id', 'version', 'createdAt', 'updatedAt', 'createdBy', 'updatedBy', 'extendedAt', 'bodySize', 'protocol', 'condition']);

    const fieldLabel = (key) => AUDIT_FIELD_LABELS()[key] || key;

    const formatFieldValue = (key, val) => {
        if (val === null || val === undefined || val === '') { return t('auditValues.empty'); }
        if (typeof val === 'boolean') { return val ? t('auditValues.yes') : t('auditValues.no'); }
        if (typeof val === 'object') { return JSON.stringify(val, null, 2); }
        const s = String(val);
        const enumLabels = {
            action: { MOCK: t('auditValues.modeMock'), FORWARD: t('auditValues.modeForward'), FAULT: t('auditValues.modeFault') },
            faultType: { NONE: t('auditValues.faultNone'), CONNECTION_RESET: t('auditValues.faultConnectionReset'), EMPTY_RESPONSE: t('auditValues.faultEmptyResponse') },
            forwardTargetMode: { ORIGINAL_HOST: t('auditValues.targetOriginalHost'), DEFAULT: t('auditValues.targetDefaultConnection'), CONNECTION: t('auditValues.targetSavedConnection') }
        };
        if (enumLabels[key]?.[s]) { return enumLabels[key][s]; }
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
                const isEmpty = (v) => v === null || v === undefined || v === '';
                for (const key of allKeys) {
                    if (AUDIT_HIDDEN_FIELDS.has(key)) { continue; }
                    const bv = before[key], av = after[key];
                    if (isEmpty(bv) && isEmpty(av)) { continue; }
                    if (JSON.stringify(bv) !== JSON.stringify(av)) {
                        if (key === 'tags') {
                            const bt = tagPairs(bv) || [], at = tagPairs(av) || [];
                            changes.push({ label: fieldLabel(key), kind: 'tags', added: at.filter(x => !bt.includes(x)), removed: bt.filter(x => !at.includes(x)) });
                            continue;
                        }
                        const b = formatFieldValue(key, bv), a = formatFieldValue(key, av);
                        // beforeText/afterText 是逐行比對用的原文；沒有值時為空字串，不是「(空)」。
                        changes.push({ label: fieldLabel(key), before: b, after: a, long: isLong(b) || isLong(a),
                            beforeText: isEmpty(bv) ? '' : b, afterText: isEmpty(av) ? '' : a });
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
                const v = formatFieldValue(key, val);
                fields.push({ label: fieldLabel(key), value: v, long: isLong(v) });
            }
            return { type: log.action === 'CREATE' ? 'create' : 'delete', changes: fields };
        } catch (e) {
            return { type: 'error', raw: log.afterJson || log.beforeJson };
        }
    };

    const escapeHtml = (s) => s?.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') || '';

    const getAuditTarget = (log) => {
        if (log.targetName) { return log.targetName; }
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
        if (!log.beforeJson && !log.afterJson) {
            const count = log.summary?.changeCount;
            return count ? t('audit.fieldCount', {count}) : '';
        }
        const result = getAuditChanges(log);
        if (result.type === 'update' && result.changes.length > 0) { return t('audit.fieldCount', {count: result.changes.length}); }
        return '';
    };

    // --- 列表摘要（不需載入前後快照）---
    const LIST_CHANGE_LIMIT = 3;

    const tagPairs = (raw) => {
        if (raw === null || raw === undefined || raw === '') { return []; }
        try {
            const tags = JSON.parse(raw);
            if (tags && typeof tags === 'object' && !Array.isArray(tags)) {
                return Object.entries(tags).map(([k, v]) => k + '=' + (typeof v === 'string' ? v : JSON.stringify(v)));
            }
        } catch (e) { /* 截短的值無法解析，改顯示原文 */ }
        return null;
    };

    const listChange = (c) => {
        const label = fieldLabel(c.f);
        if (c.long) {
            return Number.isFinite(c.add) ? { label, kind: 'lines', add: c.add, del: c.del || 0 } : { label, kind: 'changed' };
        }
        if (c.f === 'tags') {
            const before = tagPairs(c.b), after = tagPairs(c.a);
            if (before && after) {
                return { label, kind: 'tags', added: after.filter(x => !before.includes(x)), removed: before.filter(x => !after.includes(x)) };
            }
        }
        return {
            label, kind: 'value',
            before: c.b === undefined ? null : formatFieldValue(c.f, c.b),
            after: c.a === undefined ? null : formatFieldValue(c.f, c.a)
        };
    };

    /** 列表每一列要顯示的名稱、說明行與「改了什麼」，全部來自後端的小摘要。 */
    const describeAuditRow = (log) => {
        const summary = log.summary || {};
        const response = !!log.ruleId?.startsWith('response-');
        const id = response ? log.ruleId.replace('response-', '') : log.ruleId;
        const protocol = log.targetProtocol || (response ? 'RESPONSE' : '');
        const meta = [];
        if (response) {
            meta.push('#' + id);
            if (summary.contentType) { meta.push(summary.contentType); }
        } else {
            if (summary.method) { meta.push(summary.method); }
            if (summary.host) { meta.push(summary.host); }
            if (log.targetDescription) { meta.push(log.targetDescription); }
        }
        const allChanges = Array.isArray(summary.changes) ? summary.changes : [];
        const changes = log.action === 'UPDATE' ? allChanges.slice(0, LIST_CHANGE_LIMIT).map(listChange) : [];
        const total = Number(summary.changeCount) || allChanges.length;
        const facts = [];
        if (log.action !== 'UPDATE') {
            if (summary.status) { facts.push(t('audit.factStatus', {status: summary.status})); }
            if (summary.enabled === false) { facts.push(t('audit.factDisabled')); }
        }
        return {
            name: log.targetName || (response ? t('audit.typeResponse') + ' #' + id : shortId(id || '')),
            named: !!log.targetName,
            protocol,
            protocolLabel: protocol === 'RESPONSE' ? t('audit.typeResponse') : protocol,
            meta,
            changes,
            more: Math.max(0, total - changes.length),
            facts
        };
    };

    // --- Filter chips ---
    const auditFilterChips = computed(() => {
        const chips = [];
        if (auditFilter.value.action) {
            const actionLabel = {
                CREATE: t('audit.actionCreate'),
                UPDATE: t('audit.actionUpdate'),
                DELETE: t('audit.actionDelete')
            }[auditFilter.value.action] || auditFilter.value.action;
            chips.push({ key: 'action', label: t('filterChips.action') + actionLabel });
        }
        if (auditFilter.value.operator) chips.push({ key: 'operator', label: t('filterChips.operator') + auditFilter.value.operator });
        if (auditFilter.value.keyword) chips.push({ key: 'keyword', label: t('filterChips.keyword') + auditFilter.value.keyword });
        if (auditFilter.value.ruleId) {
            const id = auditFilter.value.ruleId;
            const named = auditLogs.value.find(log => log.ruleId === id && log.targetName);
            const fallback = id.startsWith('response-') ? t('audit.typeResponse') + ' #' + id.replace('response-', '') : shortId(id);
            chips.push({ key: 'ruleId', label: t('filterChips.target') + (named ? named.targetName : fallback) });
        }
        if (auditFilter.value.type) {
            const typeLabel = auditFilter.value.type === 'RESPONSE' ? t('audit.typeResponse') : t('audit.typeRule');
            chips.push({ key: 'type', label: t('filterChips.type') + typeLabel });
        }
        return chips;
    });

    const removeAuditChip = (key) => { auditFilter.value[key] = ''; };

    const clearAuditFilters = () => { auditFilter.value = { action: '', operator: '', keyword: '', type: '', ruleId: '' }; };

    return {
        auditLogs,
        selectedAudit,
        auditFilter,
        auditSort,
        auditPage,
        auditPageSize,
        auditTotalElements,
        auditToday,
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
        describeAuditRow,
        targetHistory,
        loadTargetHistory,
        filterAuditTarget,
        escapeHtml,
        auditFilterChips,
        removeAuditChip,
        clearAuditFilters
    };
};
