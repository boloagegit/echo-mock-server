/**
 * useRouter - URL Routing Management Composable
 *
 * Management front-end hash routing, including page switching, URL parameter synchronization,
 * Browser previous page/next page support.
 * The corresponding data loading function is automatically triggered when the page is switched.
 * Automatically synchronize to URL when filtering conditions change.
 *
 * @param {Object} deps - Dependent objects
 * @param {Ref} deps.page - Current page ref (from app.js)
 * @param {Ref} deps.ruleFilter - Rule filter conditions (from useRules)
 * @param {Ref} deps.responseFilter - Response filter (from useResponses)
 * @param {Ref} deps.logFilter - Record filter conditions (from useStats)
 * @param {Ref} deps.auditFilter - Revised filter conditions (from useAudit)
 * @param {Ref} deps.isAdmin - Is it an administrator (from useAuth)
 * @param {Ref} deps.issueReportingEnabled - Whether problem reporting is enabled on the backend
 * @param {Function} deps.loadRules - Loading rule function (from useRules)
 * @param {Function} deps.loadLogs - Load record function (from useStats)
 * @param {Function} deps.loadAudit - Load revised function (from useAudit)
 * @param {Function} deps.loadResponseSummary - Load response summary function (from useResponses)
 * @param {Function} deps.loadBackupStatus - Load backup status function (from app.js)
 * @param {Function} deps.loadStatus - Load system status function (from app.js)
 * @param {Function} deps.loadAccounts - Load account list function (from useAccounts)
 * @returns {{ page: Ref, validPages: Array, parseHash: Function, updateUrl: Function, applyUrlParams: Function, setupRouterWatchers: Function }}
 */
const useRouter = (deps) => {
    const { watch } = Vue;
    const { page, ruleFilter, responseFilter, logFilter, auditFilter, isAdmin, loadRules, loadLogs, loadAudit, loadResponseSummary, loadBackupStatus, loadStatus, loadAccounts, loadIssues } = deps;
    // The page actually on screen. `page` is the target and drives the sidebar right away.
    const shownPage = deps.shownPage || Vue.ref(page.value);

    // --- Status ---
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
     * Parse the current hash and return the page name and query parameters
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
     * Update URL hash based on the current page and filter conditions
     * @param {boolean} [skipHistory=false] - Whether to use replaceState instead of pushState
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
            if (logFilter.value.from) params.set('from', logFilter.value.from);
            if (logFilter.value.to) params.set('to', logFilter.value.to);
        } else if (page.value === 'audit') {
            if (auditFilter.value.action) params.set('action', auditFilter.value.action);
            if (auditFilter.value.operator) params.set('operator', auditFilter.value.operator);
            if (auditFilter.value.keyword) params.set('keyword', auditFilter.value.keyword);
            if (auditFilter.value.type) params.set('type', auditFilter.value.type);
            if (auditFilter.value.ruleId) params.set('ruleId', auditFilter.value.ruleId);
        }
        const qs = params.toString();
        const newHash = qs ? `${path}?${qs}` : path;
        if (window.location.hash !== newHash) {
            if (skipHistory) window.history.replaceState(null, null, newHash);
            else window.history.pushState(null, null, newHash);
        }
    };

    /**
     * Parse parameters from URL hash and apply to pages and filter conditions
     * @returns {Promise|null} The current page data loading is completed Promise
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
            // Both ends of the time range must be complete local time, otherwise they are ignored
            const localTime = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/;
            const from = params.get('from') || '', to = params.get('to') || '';
            const validRange = localTime.test(from) && localTime.test(to);
            logFilter.value.from = validRange ? from : '';
            logFilter.value.to = validRange ? to : '';
        } else if (page.value === 'audit') {
            auditFilter.value.action = params.get('action') || '';
            auditFilter.value.operator = params.get('operator') || '';
            auditFilter.value.keyword = params.get('keyword') || '';
            auditFilter.value.type = ['RULE', 'RESPONSE'].includes(params.get('type')) ? params.get('type') : '';
            auditFilter.value.ruleId = params.get('ruleId') || '';
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
     * Set all routing related watchers:
     * - Page switching → Update URL and load data
     * - Filter condition changes → Sync to URL
     */
    const setupRouterWatchers = () => {
        // Page switching → Update URL
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
        // Filter → Sync URL
        watch(ruleFilter, () => { if (page.value === 'rules') updateUrl(); }, { deep: true });
        watch(responseFilter, () => { if (page.value === 'responses') updateUrl(); });
        watch(logFilter, () => { if (page.value === 'stats') updateUrl(); }, { deep: true });
        watch(auditFilter, () => { if (page.value === 'audit') updateUrl(); }, { deep: true });
        // Page switching → Load the corresponding data, and then change to a new page after the data is complete (or wait for a short time)
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
