/**
 * useStats - 請求記錄統計 Composable
 *
 * 管理請求記錄（logs）的載入、篩選、排序、分頁，
 * 以及自動刷新與閒置偵測機制。
 * 當使用者閒置超過 5 分鐘或分頁隱藏時，自動關閉刷新以節省資源。
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
    let newestId = null;
    let listRequestSequence = 0;
    let lastFullRefresh = 0;
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
        logPage.value = 1;
    };

    /** 取得排序圖示 class */
    const sortIcon = (f) => logSort.value.field === f
        ? (logSort.value.asc ? 'bi-caret-up-fill' : 'bi-caret-down-fill')
        : 'bi-arrow-down-up';

    /** 每頁筆數變更 */
    const onPageSizeChange = () => {
        localStorage.setItem('logPageSize', logPageSize.value);
        logPage.value = 1;
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

    const buildLogQuery = ({ afterId = null, size = logPageSize.value } = {}) => {
        const params = new URLSearchParams();
        params.set('page', afterId == null ? String(Math.max(0, logPage.value - 1)) : '0');
        params.set('size', String(size));
        params.set('sort', logSort.value.field);
        params.set('direction', logSort.value.asc ? 'asc' : 'desc');
        if (logFilter.value.protocol) { params.set('protocol', logFilter.value.protocol); }
        if (logFilter.value.matched) { params.set('matched', logFilter.value.matched); }
        const endpoint = (logFilter.value.endpoint || '').trim();
        if (endpoint) { params.set('endpoint', endpoint); }
        if (afterId != null) { params.set('afterId', String(afterId)); }
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
                newestId = data.newestId != null ? Number(data.newestId) : null;
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
        debouncedLoadLogs.cancel();
        await Promise.all([loadLogPage(), loadLogSummary()]);
        lastFullRefresh = Date.now();
    };

    const debouncedLoadLogs = debounce(() => loadLogPage(), 300);

    // 查詢條件變更後，只向後端查詢新的一頁，不再於瀏覽器掃描全部記錄。
    watch(logFilter, () => {
        logPage.value = 1;
        debouncedLoadLogs();
    }, { deep: true });
    watch(logSort, () => {
        logPage.value = 1;
        debouncedLoadLogs();
    }, { deep: true });
    watch(logPage, () => debouncedLoadLogs());
    watch(logPageSize, () => {
        localStorage.setItem('logPageSize', logPageSize.value);
        logPage.value = 1;
        debouncedLoadLogs();
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

    // --- 自動刷新 ---
    const autoRefresh = ref(false);
    let autoRefreshTimer = null;
    let incrementalRefreshRunning = false;
    const FULL_REFRESH_INTERVAL = 30000;
    const INCREMENTAL_BATCH_SIZE = 200;

    const currentQuerySignature = () => JSON.stringify({
        filter: logFilter.value,
        sort: logSort.value,
        page: logPage.value,
        size: logPageSize.value
    });

    const refreshIncrementally = async () => {
        if (incrementalRefreshRunning) { return; }
        incrementalRefreshRunning = true;
        try {
            // 每 30 秒做一次完整當頁同步，校正清理造成的總筆數變化並更新統計。
            if (Date.now() - lastFullRefresh >= FULL_REFRESH_INTERVAL) {
                await loadLogs(true);
                return;
            }

            const canMergeIncrementally = logPage.value === 1
                && logSort.value.field === 'requestTime'
                && !logSort.value.asc
                && newestId != null;
            if (!canMergeIncrementally) {
                await loadLogPage({ showLoading: false });
                return;
            }

            const signature = currentQuerySignature();
            const response = await apiCall(
                buildLogQuery({ afterId: newestId, size: INCREMENTAL_BATCH_SIZE }),
                {}, { silent: true });
            if (!response || !response.ok || signature !== currentQuerySignature()) { return; }

            const data = await response.json();
            if (signature !== currentQuerySignature()) { return; }
            const newResults = hydrateCachedDetails(data.results || []);
            const available = Number(data.totalElements || 0);
            // 兩次輪詢間若湧入超過單批上限，直接重查當頁，避免遺漏中間記錄。
            if (available > newResults.length) {
                await loadLogPage({ showLoading: false });
                return;
            }
            if (!newResults.length) { return; }

            const merged = new Map();
            [...newResults, ...logs.value].forEach(item => merged.set(item.log.id, item));
            logs.value = [...merged.values()]
                .sort((a, b) => {
                    const byTime = String(b.log.requestTime || '').localeCompare(String(a.log.requestTime || ''));
                    return byTime || Number(b.log.id || 0) - Number(a.log.id || 0);
                })
                .slice(0, logPageSize.value);
            logTotalElements.value += newResults.length;
            serverTotalPages.value = Math.ceil(logTotalElements.value / logPageSize.value);
            logSummary.value = { ...logSummary.value, filteredRequests: logTotalElements.value };
            if (data.newestId != null) {
                newestId = Math.max(newestId, Number(data.newestId));
            }
        } finally {
            incrementalRefreshRunning = false;
        }
    };

    const toggleAutoRefresh = () => {
        autoRefresh.value = !autoRefresh.value;
        if (autoRefresh.value) {
            resetActivity();
            autoRefreshTimer = setInterval(() => {
                if (!deps.loading.value.logs) {
                    refreshIncrementally();
                }
            }, 5000);
            startIdleCheck();
        } else {
            if (autoRefreshTimer) { clearInterval(autoRefreshTimer); autoRefreshTimer = null; }
            stopIdleCheck();
        }
    };

    const stopAutoRefresh = () => {
        if (autoRefreshTimer) { clearInterval(autoRefreshTimer); autoRefreshTimer = null; }
        autoRefresh.value = false;
        stopIdleCheck();
    };

    // --- 閒置偵測 ---
    const IDLE_TIMEOUT = 5 * 60 * 1000;
    let lastActivity = Date.now();
    let idleCheckTimer = null;

    const resetActivity = () => { lastActivity = Date.now(); };

    const startIdleCheck = () => {
        if (idleCheckTimer) { return; }
        idleCheckTimer = setInterval(() => {
            if (autoRefresh.value && (Date.now() - lastActivity > IDLE_TIMEOUT)) {
                stopAutoRefresh();
            }
        }, 30000);
    };

    const stopIdleCheck = () => {
        if (idleCheckTimer) { clearInterval(idleCheckTimer); idleCheckTimer = null; }
    };

    const onVisibilityChange = () => {
        if (document.hidden && autoRefresh.value) {
            stopAutoRefresh();
        }
    };

    // 註冊事件監聽
    document.addEventListener('visibilitychange', onVisibilityChange);
    ['mousemove', 'mousedown', 'keydown', 'touchstart', 'scroll'].forEach(evt => {
        document.addEventListener(evt, resetActivity, { passive: true });
    });

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
        logPage.value = 1;
    };

    const clearLogFilters = () => {
        logFilter.value = { protocol: '', matched: '', endpoint: '' };
        logPage.value = 1;
    };

    // --- 清理函式（供 onUnmounted 呼叫） ---
    const cleanupStats = () => {
        stopAutoRefresh();
        document.removeEventListener('visibilitychange', onVisibilityChange);
        ['mousemove', 'mousedown', 'keydown', 'touchstart', 'scroll'].forEach(evt => {
            document.removeEventListener(evt, resetActivity, { passive: true });
        });
        debouncedLoadLogs.cancel();
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
        debouncedLoadLogs,
        autoRefresh,
        toggleAutoRefresh,
        stopAutoRefresh,
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
