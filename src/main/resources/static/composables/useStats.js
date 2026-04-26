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

    // --- Detail cache: keyed by log.id ---
    const detailCache = {};
    // --- 展開狀態: keyed by log.id ---
    const logDetailExpanded = ref({});

    // --- 篩選、排序與分頁 ---
    const filteredLogs = computed(() => {
        return logs.value.filter(item => {
            const log = item.log;
            if (logFilter.value.protocol && log.protocol !== logFilter.value.protocol) { return false; }
            if (logFilter.value.matched === 'true' && !log.matched) { return false; }
            if (logFilter.value.matched === 'false' && log.matched) { return false; }
            if (logFilter.value.endpoint) {
                const kw = logFilter.value.endpoint.toLowerCase();
                const searchText = [log.endpoint, log.targetHost, log.ruleId].filter(Boolean).join(' ').toLowerCase();
                if (!searchText.includes(kw)) { return false; }
            }
            return true;
        });
    });

    const sortedLogs = computed(() => {
        const arr = [...filteredLogs.value];
        const { field, asc } = logSort.value;
        arr.sort((a, b) => {
            let va = a.log[field], vb = b.log[field];
            if (va == null) { return 1; }
            if (vb == null) { return -1; }
            if (typeof va === 'string') { return asc ? va.localeCompare(vb) : vb.localeCompare(va); }
            return asc ? va - vb : vb - va;
        });
        return arr;
    });

    const pagedLogs = computed(() => {
        const start = (logPage.value - 1) * logPageSize.value;
        return sortedLogs.value.slice(start, start + logPageSize.value);
    });

    const totalPages = computed(() => Math.ceil(filteredLogs.value.length / logPageSize.value) || 1);

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
        logPage.value = 1;
    };

    watch(logFilter, () => logPage.value = 1, { deep: true });

    // --- 載入（列表只拿摘要） ---
    const loadLogs = async (force) => {
        if (!force && !shouldLoad()) { return; }
        deps.loading.value.logs = true;

        // 保留展開狀態（以 log.id 為 key）
        const prevExpanded = { ...logDetailExpanded.value };

        const [logsRes, summaryRes] = await Promise.all([
            apiCall('/api/admin/logs', {}, { silent: true }),
            apiCall('/api/admin/logs/summary', {}, { silent: true })
        ]);
        if (logsRes && logsRes.ok) {
            const d = await logsRes.json();
            const newResults = d.results || [];

            // 還原展開狀態（以 log.id 為 key，不受排序/分頁影響）
            const newExpanded = {};
            newResults.forEach(item => {
                const key = item.log.id;
                if (key && prevExpanded[key]) {
                    newExpanded[key] = true;
                }
                // 還原已快取的 detail
                if (key && detailCache[key]) {
                    item._detail = detailCache[key];
                    if (item._detail.matchChain) {
                        item.matchChainData = JSON.parse(item._detail.matchChain || '[]');
                    }
                }
            });

            logs.value = newResults;
            logDetailExpanded.value = newExpanded;
        }
        if (summaryRes && summaryRes.ok) {
            logSummary.value = await summaryRes.json();
        }
        markLoaded();
        deps.loading.value.logs = false;
    };

    const debouncedLoadLogs = debounce(() => loadLogs(true), 300);

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
        const res = await apiCall('/api/admin/logs/' + id + '/detail', {}, { silent: true });
        if (res && res.ok) {
            const detail = await res.json();
            detailCache[id] = detail;
            item._detail = detail;
            if (detail.matchChain) {
                item.matchChainData = JSON.parse(detail.matchChain || '[]');
            }
        }
        item._detailLoading = false;
    };

    // --- 自動刷新 ---
    const autoRefresh = ref(false);
    let autoRefreshTimer = null;

    const toggleAutoRefresh = () => {
        autoRefresh.value = !autoRefresh.value;
        if (autoRefresh.value) {
            resetActivity();
            autoRefreshTimer = setInterval(() => {
                if (!deps.loading.value.logs) {
                    loadLogs(true);
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

    // --- 行內詳細展開（以 log.id 為 key） ---
    const toggleLogDetail = async (item) => {
        const key = item.log.id;
        if (!key) { return; }
        const wasExpanded = !!logDetailExpanded.value[key];
        logDetailExpanded.value = { ...logDetailExpanded.value, [key]: !wasExpanded };
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
