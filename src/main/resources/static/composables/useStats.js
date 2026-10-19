/**
 * useStats - Request record statistics Composable
 *
 * Manage the loading, filtering, sorting and paging of request records (logs).
 *
 * List API only returns the summary (excluding body / matchChain),
 * Only expand the details lazy load detail, and cache the results to avoid repeated queries.
 *
 * @param {Object} deps - Dependent objects
 * @param {Function} deps.showToast - Toast notification function (from useToast)
 * @param {Function} deps.t - Translation function (from useI18n)
 * @returns {Object}
 */
const useStats = (deps) => {
    const { ref, computed, watch } = Vue;
    const { showToast, t } = deps;

    // --- Data caching mechanism ---
    const dataLastLoaded = { stats: 0 };
    const DATA_TTL = 30000;
    const shouldLoad = () => !dataLastLoaded.stats || (Date.now() - dataLastLoaded.stats > DATA_TTL);
    const markLoaded = () => { dataLastLoaded.stats = Date.now(); };

    // --- Status ---
    const logs = ref([]);
    const logSummary = ref({});
    // method / path: exactly the same method and URL (traffic profile "look at these requests"); endpoint is a partial comparison keyword
    const logFilter = ref({ protocol: '', matched: '', endpoint: '', method: '', path: '', from: '', to: '' });

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
    // --- Expanded status: keyed by log.id ---
    const logDetailExpanded = ref({});

    // --- Filtering, sorting and paging ---
    // The list has been filtered, sorted and paginated by the backend; these three computed names are retained for compatibility with existing components.
    const filteredLogs = computed(() => logs.value);
    const sortedLogs = computed(() => logs.value);
    const pagedLogs = computed(() => logs.value);
    const totalPages = computed(() => Math.max(1, serverTotalPages.value));

    /** Switch sorting field/direction */
    const toggleSort = (field) => {
        if (logSort.value.field === field) {
            logSort.value.asc = !logSort.value.asc;
        } else {
            logSort.value.field = field;
            logSort.value.asc = false;
        }
        localStorage.setItem('logSort', JSON.stringify(logSort.value));
    };

    /** Get sorting icon class */
    const sortIcon = (f) => logSort.value.field === f
        ? (logSort.value.asc ? 'bi-caret-up-fill' : 'bi-caret-down-fill')
        : 'bi-arrow-down-up';

    /** Page-size changes */
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
        if (logFilter.value.method) { params.set('method', logFilter.value.method); }
        if (logFilter.value.path) { params.set('path', logFilter.value.path); }
        // Time range (the grid clicked in the traffic profile), the server's local time string is sent back as is
        if (logFilter.value.from) { params.set('from', logFilter.value.from); }
        if (logFilter.value.to) { params.set('to', logFilter.value.to); }
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

    // --- Loading (the list only takes the summary of the current page, and the statistics will be refreshed at a low frequency) ---
    const loadLogs = async (force) => {
        if (!force && !shouldLoad()) { return; }
        await Promise.all([loadLogPage(), loadLogSummary()]);
    };

    const reloadLogsFromFirstPage = () => {
        if (logPage.value !== 1) { logPage.value = 1; }
        else { loadLogPage(); }
    };

    // After the query conditions are changed, only the new page is queried from the backend, and all records are no longer scanned in the browser.
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
        // Cached → Use directly
        if (detailCache[id]) {
            item._detail = detailCache[id];
            if (item._detail.matchChain) {
                item.matchChainData = JSON.parse(item._detail.matchChain || '[]');
            }
            return;
        }
        // Loading even without this article and comparison chain: "Processing Process" requires details such as the number of candidate rules, default rules, delay and situation skipping
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

    // --- matchChain Expand ---
    const toggleMatchChain = (item) => {
        item.showChain = !item.showChain;
        if (item.showChain && !item.matchChainData && item._detail && item._detail.matchChain) {
            item.matchChainData = JSON.parse(item._detail.matchChain || '[]');
        }
    };

    // --- Details Inspector (single selection, log.id is key) ---
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
            // Retrigger watch (after detail is loaded)
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
        if (logFilter.value.path) {
            const method = logFilter.value.method ? logFilter.value.method + ' ' : '';
            chips.push({ key: 'path', label: t('filterChips.endpoint') + method + logFilter.value.path });
        }
        if (logFilter.value.endpoint) {
            chips.push({ key: 'endpoint', label: t('filterChips.keyword') + logFilter.value.endpoint });
        }
        if (logFilter.value.from || logFilter.value.to) {
            const from = logFilter.value.from, to = logFilter.value.to;
            const withDay = from.substring(0, 10) !== to.substring(0, 10) || to.substring(11, 16) === '00:00';
            chips.push({ key: 'time', label: t('filterChips.time') + TrafficChart.rangeText(from, to, withDay) });
        }
        return chips;
    });

    const removeLogChip = (key) => {
        if (key === 'time') {
            logFilter.value = { ...logFilter.value, from: '', to: '' };
        } else if (key === 'path') {
            logFilter.value = { ...logFilter.value, method: '', path: '' };
        } else {
            logFilter.value[key] = '';
        }
    };

    const clearLogFilters = () => {
        logFilter.value = { protocol: '', matched: '', endpoint: '', method: '', path: '', from: '', to: '' };
    };

    // --- Cleanup function (called by onUnmounted) ---
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
