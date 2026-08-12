/**
 * useStats - 請求記錄統計 Composable
 *
 * 管理請求記錄（logs）的載入、篩選、排序與分頁。
 *
 * 列表 API 只回傳摘要（不含 body / matchChain），
 * 展開明細時才 lazy load detail，並快取結果避免重複查詢。
 *
 * @param {Object} deps - 依賴物件
 * @param {Function} deps.showToast - Toast 通知函式（來自 useToast）
 * @param {Function} deps.t - 翻譯函式（來自 useI18n）
 * @returns {Object}
 */
const useStats = (deps) => {
    const { ref, computed, watch } = Vue;
    const { showToast, t } = deps;

    // --- 資料快取機制 ---
    const dataLastLoaded = { stats: 0 };
    const DATA_TTL = 30000;
    const shouldLoad = () => !dataLastLoaded.stats || (Date.now() - dataLastLoaded.stats > DATA_TTL);
    const markLoaded = () => { dataLastLoaded.stats = Date.now(); };

    // --- 狀態 ---
    const logs = ref([]);
    const logSummary = ref({});
    const logFilter = ref({ protocol: '', matched: '', endpoint: '' });

    const savedLogSort = JSON.parse(localStorage.getItem('logSort') || 'null');
    const logSort = ref(savedLogSort || { field: 'requestTime', asc: false });
    const logPage = ref(1);
    const logPageSize = ref(parseInt(localStorage.getItem('logPageSize')) || 20);
    const logTotalElements = ref(0);
    const serverTotalPages = ref(0);
    let listRequestSequence = 0;
    let listAbortController = null;

    // --- Detail cache: keyed by log.id ---
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
    // --- 展開狀態: keyed by log.id ---
    const logDetailExpanded = ref({});

    // --- 篩選、排序與分頁 ---
    // 清單已由後端完成篩選、排序與分頁；保留這三個 computed 名稱以相容既有元件。
    const filteredLogs = computed(() => logs.value);
    const sortedLogs = computed(() => logs.value);
    const pagedLogs = computed(() => logs.value);
    const totalPages = computed(() => Math.max(1, serverTotalPages.value));

    /** 切換排序欄位/方向 */
    const toggleSort = (field) => {
        if (logSort.value.field === field) {
            logSort.value.asc = !logSort.value.asc;
        } else {
            logSort.value.field = field;
            logSort.value.asc = false;
        }
        localStorage.setItem('logSort', JSON.stringify(logSort.value));
    };

    /** 取得排序圖示 class */
    const sortIcon = (f) => logSort.value.field === f
        ? (logSort.value.asc ? 'bi-caret-up-fill' : 'bi-caret-down-fill')
        : 'bi-arrow-down-up';

    /** 每頁筆數變更 */
    const onPageSizeChange = () => {
        localStorage.setItem('logPageSize', logPageSize.value);
    };

    const hydrateCachedDetails = (items) => {
        items.forEach(item => {
            const key = item.log.id;
            if (key && detailCache[key]) {
                item._detail = detailCache[key];
                if (item._detail.matchChain) {
                    item.matchChainData = JSON.parse(item._detail.matchChain || '[]');
                }
            }
        });
        return items;
    };

    const buildLogQuery = () => {
        const params = new URLSearchParams();
        params.set('page', String(Math.max(0, logPage.value - 1)));
        params.set('size', String(logPageSize.value));
        params.set('sort', logSort.value.field);
        params.set('direction', logSort.value.asc ? 'asc' : 'desc');
        if (logFilter.value.protocol) { params.set('protocol', logFilter.value.protocol); }
        if (logFilter.value.matched) { params.set('matched', logFilter.value.matched); }
        const endpoint = (logFilter.value.endpoint || '').trim();
        if (endpoint) { params.set('endpoint', endpoint); }
        return '/api/admin/logs?' + params.toString();
    };

    const loadLogPage = async ({ showLoading = true } = {}) => {
        const requestId = ++listRequestSequence;
        if (listAbortController) { listAbortController.abort(); }
        const abortController = new AbortController();
        listAbortController = abortController;
        if (showLoading) { deps.loading.value.logs = true; }
        if (showLoading) { deps.loading.value.logsError = ''; }
        try {
            const response = await apiCall(buildLogQuery(), { signal: abortController.signal }, { silent: true });
            if (requestId !== listRequestSequence) { return false; }
            if (response && response.ok) {
                const data = await response.json();
                if (requestId !== listRequestSequence) { return false; }
                const newResults = hydrateCachedDetails(data.results || []);
                logs.value = newResults;
                logTotalElements.value = Number(data.totalElements || 0);
                serverTotalPages.value = Number(data.totalPages || 0);
                logSummary.value = { ...logSummary.value, filteredRequests: logTotalElements.value };
                if (logPage.value > totalPages.value) {
                    logPage.value = totalPages.value;
                }
                markLoaded();
                deps.loading.value.logsError = '';
            } else if (showLoading) {
                deps.loading.value.logsError = t('stats.loadFailed');
            }
            return !!(response && response.ok);
        } finally {
            if (requestId === listRequestSequence) {
                if (listAbortController === abortController) { listAbortController = null; }
                if (showLoading) { deps.loading.value.logs = false; }
            }
        }
    };

    const loadLogSummary = async () => {
        const response = await apiCall('/api/admin/logs/summary', {}, { silent: true });
        if (response && response.ok) {
            logSummary.value = { ...(await response.json()), filteredRequests: logTotalElements.value };
        }
    };

    // --- 載入（列表只拿當頁摘要，統計另行低頻刷新） ---
    const loadLogs = async (force) => {
        if (!force && !shouldLoad()) { return; }
        await Promise.all([loadLogPage(), loadLogSummary()]);
    };

    const reloadLogsFromFirstPage = () => {
        if (logPage.value !== 1) { logPage.value = 1; }
        else { loadLogPage(); }
    };

    // 查詢條件變更後，只向後端查詢新的一頁，不再於瀏覽器掃描全部記錄。
    watch(logFilter, () => {
        reloadLogsFromFirstPage();
    }, { deep: true });
    watch(logSort, () => {
        reloadLogsFromFirstPage();
    }, { deep: true });
    watch(logPage, () => loadLogPage());
    watch(logPageSize, () => {
        localStorage.setItem('logPageSize', logPageSize.value);
        reloadLogsFromFirstPage();
    });

    // --- Lazy load detail ---
    const loadLogDetail = async (item) => {
        const id = item.log.id;
        if (!id) { return; }
        // 已快取 → 直接使用
        if (detailCache[id]) {
            item._detail = detailCache[id];
            if (item._detail.matchChain) {
                item.matchChainData = JSON.parse(item._detail.matchChain || '[]');
            }
            return;
        }
        // 沒有明細可載入
        if (!item.log.hasRequestBody && !item.log.hasResponseBody && !item.log.hasMatchChain) {
            return;
        }
        item._detailLoading = true;
        item._detailError = false;
        try {
            const res = await apiCall('/api/admin/logs/' + id + '/detail', {}, { silent: true });
            if (res && res.ok) {
                const detail = await res.json();
                cacheDetail(id, detail);
                item._detail = detail;
                if (detail.matchChain) {
                    item.matchChainData = JSON.parse(detail.matchChain || '[]');
                }
            } else {
                item._detailError = true;
            }
        } finally {
            item._detailLoading = false;
        }
    };

    // --- matchChain 展開 ---
    const toggleMatchChain = (item) => {
        item.showChain = !item.showChain;
        if (item.showChain && !item.matchChainData && item._detail && item._detail.matchChain) {
            item.matchChainData = JSON.parse(item._detail.matchChain || '[]');
        }
    };

    // --- 詳情 Inspector（單一選取，以 log.id 為 key） ---
    const toggleLogDetail = async (item) => {
        const key = item.log.id;
        if (!key) { return; }
        const wasExpanded = !!logDetailExpanded.value[key];
        if (wasExpanded && item._detailError) {
            await loadLogDetail(item);
            logDetailExpanded.value = { ...logDetailExpanded.value };
            return;
        }
        logDetailExpanded.value = wasExpanded ? {} : { [key]: true };
        if (!wasExpanded) {
            await loadLogDetail(item);
            // 重新觸發 watch（detail 載入完成後）
            logDetailExpanded.value = { ...logDetailExpanded.value };
        }
    };

    // --- Filter chips ---
    const logFilterChips = computed(() => {
        const chips = [];
        if (logFilter.value.protocol) {
            chips.push({ key: 'protocol', label: t('filterChips.protocol') + (logFilter.value.protocol === 'HTTP' ? deps.httpLabel.value : deps.jmsLabel.value) });
        }
        if (logFilter.value.matched) {
            chips.push({ key: 'matched', label: t('filterChips.status') + (logFilter.value.matched === 'true' ? t('stats.filterMatched') : t('stats.filterUnmatched')) });
        }
        if (logFilter.value.endpoint) {
            chips.push({ key: 'endpoint', label: t('filterChips.endpoint') + logFilter.value.endpoint });
        }
        return chips;
    });

    const removeLogChip = (key) => {
        logFilter.value[key] = '';
    };

    const clearLogFilters = () => {
        logFilter.value = { protocol: '', matched: '', endpoint: '' };
    };

    // --- 清理函式（供 onUnmounted 呼叫） ---
    const cleanupStats = () => {
        if (listAbortController) { listAbortController.abort(); }
    };

    return {
        logs,
        logSummary,
        logFilter,
        logSort,
        logPage,
        logPageSize,
        logTotalElements,
        filteredLogs,
        sortedLogs,
        pagedLogs,
        totalPages,
        toggleSort,
        sortIcon,
        onPageSizeChange,
        loadLogs,
        toggleMatchChain,
        logDetailExpanded,
        toggleLogDetail,
        loadLogDetail,
        logFilterChips,
        removeLogChip,
        clearLogFilters,
        cleanupStats
    };
};
