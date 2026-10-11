/**
 * useRouter - URL 路由管理 Composable
 *
 * 管理前端 hash 路由，包含頁面切換、URL 參數同步、
 * 瀏覽器上一頁/下一頁支援。
 * 頁面切換時自動觸發對應的資料載入函式，
 * 篩選條件變更時自動同步至 URL。
 *
 * @param {Object} deps - 依賴物件
 * @param {Ref} deps.page - 當前頁面 ref（來自 app.js）
 * @param {Ref} deps.ruleFilter - 規則篩選條件（來自 useRules）
 * @param {Ref} deps.responseFilter - 回應篩選條件（來自 useResponses）
 * @param {Ref} deps.logFilter - 記錄篩選條件（來自 useStats）
 * @param {Ref} deps.auditFilter - 修訂篩選條件（來自 useAudit）
 * @param {Ref} deps.isAdmin - 是否為管理員（來自 useAuth）
 * @param {Ref} deps.issueReportingEnabled - 後端是否啟用問題回報
 * @param {Function} deps.loadRules - 載入規則函式（來自 useRules）
 * @param {Function} deps.loadLogs - 載入記錄函式（來自 useStats）
 * @param {Function} deps.loadAudit - 載入修訂函式（來自 useAudit）
 * @param {Function} deps.loadResponseSummary - 載入回應摘要函式（來自 useResponses）
 * @param {Function} deps.loadBackupStatus - 載入備份狀態函式（來自 app.js）
 * @param {Function} deps.loadStatus - 載入系統狀態函式（來自 app.js）
 * @param {Function} deps.loadAccounts - 載入帳號清單函式（來自 useAccounts）
 * @returns {{ page: Ref, validPages: Array, parseHash: Function, updateUrl: Function, applyUrlParams: Function, setupRouterWatchers: Function }}
 */
const useRouter = (deps) => {
    const { watch } = Vue;
    const { page, ruleFilter, responseFilter, logFilter, auditFilter, isAdmin, loadRules, loadLogs, loadAudit, loadResponseSummary, loadBackupStatus, loadStatus, loadAccounts, loadIssues } = deps;
    // The page actually on screen. `page` is the target and drives the sidebar right away.
    const shownPage = deps.shownPage || Vue.ref(page.value);

    // --- 狀態 ---
    const validPages = ['rules', 'responses', 'stats', 'audit', 'issues', 'accounts', 'settings'];
    const isIssuesEnabled = () => deps.issueReportingEnabled?.value === true && typeof loadIssues === 'function';

    /** Starts the data load for a page and returns its promise (or null when there is nothing to wait for). */
    const loadPage = p => {
        if (p === 'rules') return loadRules();
        if (p === 'responses') return loadResponseSummary();
        if (p === 'stats') return loadLogs();
        if (p === 'audit') return loadAudit();
        if (p === 'accounts') return loadAccounts();
        if (p === 'issues' && isIssuesEnabled()) return loadIssues();
        if (p === 'settings') return Promise.all([loadStatus(), loadBackupStatus()]);
        return null;
    };

    /**
     * Page switches work like the detail drawers: the current page stays on screen until
     * the next page's data has arrived (or PAGE_HOLD_MS passes), then the two swap in one
     * frame instead of showing an empty list first. Pages visited before keep their rows,
     * so they swap at once and refresh in place.
     */
    const PAGE_HOLD_MS = 300;
    const visitedPages = new Set();
    let holdToken = 0;
    const showPageWhenReady = (target, pending) => {
        const token = ++holdToken;
        const swap = () => {
            if (token !== holdToken || page.value !== target) { return; }
            shownPage.value = target;
            visitedPages.add(target);
        };
        if (!pending || visitedPages.has(target)) { swap(); return; }
        setTimeout(swap, PAGE_HOLD_MS);
        Promise.resolve(pending).catch(() => {}).finally(swap);
    };
    // A load started by applyUrlParams, reused by the page watcher in the same tick.
    let urlLoad = null;

    /**
     * 解析目前的 hash，回傳頁面名稱與查詢參數
     * @returns {{ pageName: string, params: URLSearchParams }}
     */
    const parseHash = () => {
        const hash = window.location.hash.slice(1) || '/rules';
        const [path, query] = hash.split('?');
        const pageName = path.replace('/', '');
        const params = new URLSearchParams(query || '');
        return { pageName, params };
    };

    /**
     * 根據目前頁面與篩選條件更新 URL hash
     * @param {boolean} [skipHistory=false] - 是否使用 replaceState 而非 pushState
     */
    const updateUrl = (skipHistory = false) => {
        let path = `#/${page.value}`;
        const params = new URLSearchParams();
        if (page.value === 'rules') {
            if (ruleFilter.value.protocol) params.set('protocol', ruleFilter.value.protocol);
            if (ruleFilter.value.enabled) params.set('enabled', ruleFilter.value.enabled);
            if (ruleFilter.value.keyword) params.set('keyword', ruleFilter.value.keyword);
        } else if (page.value === 'responses') {
            if (responseFilter.value) params.set('keyword', responseFilter.value);
        } else if (page.value === 'stats') {
            if (logFilter.value.protocol) params.set('protocol', logFilter.value.protocol);
            if (logFilter.value.matched) params.set('matched', logFilter.value.matched);
            if (logFilter.value.endpoint) params.set('endpoint', logFilter.value.endpoint);
        } else if (page.value === 'audit') {
            if (auditFilter.value.action) params.set('action', auditFilter.value.action);
            if (auditFilter.value.operator) params.set('operator', auditFilter.value.operator);
            if (auditFilter.value.keyword) params.set('keyword', auditFilter.value.keyword);
        }
        const qs = params.toString();
        const newHash = qs ? `${path}?${qs}` : path;
        if (window.location.hash !== newHash) {
            if (skipHistory) window.history.replaceState(null, null, newHash);
            else window.history.pushState(null, null, newHash);
        }
    };

    /**
     * 從 URL hash 解析參數並套用至頁面與篩選條件
     * @returns {Promise|null} 目前頁面資料載入完成的 Promise
     */
    const applyUrlParams = () => {
        const { pageName, params } = parseHash();
        const targetPage = validPages.includes(pageName) ? pageName : 'rules';
        if (targetPage === 'settings' && !isAdmin.value) {
            page.value = 'rules';
        } else if (targetPage === 'accounts' && !isAdmin.value) {
            page.value = 'rules';
        } else if (targetPage === 'issues' && !isIssuesEnabled()) {
            page.value = 'rules';
        } else {
            page.value = targetPage;
        }
        if (page.value === 'rules') {
            ruleFilter.value.protocol = params.get('protocol') || '';
            ruleFilter.value.enabled = params.get('enabled') || '';
            ruleFilter.value.keyword = params.get('keyword') || '';
        } else if (page.value === 'responses') {
            responseFilter.value = params.get('keyword') || '';
        } else if (page.value === 'stats') {
            logFilter.value.protocol = params.get('protocol') || '';
            logFilter.value.matched = params.get('matched') || '';
            logFilter.value.endpoint = params.get('endpoint') || '';
        } else if (page.value === 'audit') {
            auditFilter.value.action = params.get('action') || '';
            auditFilter.value.operator = params.get('operator') || '';
            auditFilter.value.keyword = params.get('keyword') || '';
        }
        // Settings loads its own status; every other page loads here, once.
        const target = page.value;
        const pending = target === 'settings' ? null : loadPage(target);
        Promise.resolve(pending).catch(() => {}).finally(() => visitedPages.add(target));
        urlLoad = { page: target, pending };
        setTimeout(() => { urlLoad = null; });
        updateUrl(true);
        return pending;
    };

    /**
     * 設定所有路由相關的 watchers：
     * - 頁面切換 → 更新 URL 與載入資料
     * - 篩選條件變更 → 同步至 URL
     */
    const setupRouterWatchers = () => {
        // 頁面切換 → 更新 URL
        watch(page, (newPage, oldPage) => {
            if (newPage === 'issues' && !isIssuesEnabled()) {
                page.value = 'rules';
                updateUrl(true);
                return;
            }
            if (newPage !== oldPage) updateUrl();
        });
        if (deps.issueReportingEnabled) {
            watch(deps.issueReportingEnabled, enabled => {
                if (!enabled && page.value === 'issues') {
                    page.value = 'rules';
                    updateUrl(true);
                }
            });
        }
        // 篩選條件 → 同步 URL
        watch(ruleFilter, () => { if (page.value === 'rules') updateUrl(); }, { deep: true });
        watch(responseFilter, () => { if (page.value === 'responses') updateUrl(); });
        watch(logFilter, () => { if (page.value === 'stats') updateUrl(); }, { deep: true });
        watch(auditFilter, () => { if (page.value === 'audit') updateUrl(); }, { deep: true });
        // 頁面切換 → 載入對應資料，資料到齊（或短暫等待後）才換上新頁面
        watch(page, p => {
            const pending = urlLoad && urlLoad.page === p && p !== 'settings' ? urlLoad.pending : loadPage(p);
            urlLoad = null;
            showPageWhenReady(p, pending);
        });
    };

    return {
        page,
        shownPage,
        validPages,
        parseHash,
        updateUrl,
        applyUrlParams,
        setupRouterWatchers
    };
};
