/**
 * useRuleForm - Rule form Composable
 *
 * All states and logic of the management rule new/edit form, including:
 * - Form status and validation
 * - SSE event editing and preview
 * - Conditional analysis
 * - Test area (HTTP/JMS/SSE)
 * - Response preview and editing
 * - response selector
 * - Form assistance (label, Header)
 * - Modal maximized state
 * - watchers settings
 *
 * @param {Object} deps - Dependent objects
 * @param {Function} deps.showToast - Toast notification function (from useToast)
 * @param {Function} deps.showConfirm - Confirm dialog function (from useToast)
 * @param {Function} deps.t - Translation function (from useI18n)
 * @param {Function} deps.requireLogin - Login check function (from useAuth)
 * @param {Function} deps.login - Login jump function (from useAuth)
 * @param {Function} deps.loadRules - Loading rule function (from useRules)
 * @param {import('vue').Ref} deps.rules - Rule list (from useRules)
 * @param {import('vue').Ref} deps.rulePreviewCache - Rule preview cache (from useRules)
 * @param {import('vue').Ref} deps.ruleDetailId - The details drawer currently displays rule ID (from useRules)
 * @param {Function} deps.loadRuleDetail - Reload rule details (from useRules)
 * @param {Function} deps.rulesMarkDirty - Marking rule data needs to be reloaded (from useRules)
 * @param {Function} deps.responsesMarkDirty - Flag response data needs to be reloaded (from useResponses)
 * @param {import('vue').Ref} deps.responseSseEvents - Response to event SSE (from useResponses)
 * @param {import('vue').Ref} deps.jmsEnabled - Is JMS enabled (from app.js)
 * @returns {Object} Status and methods related to the rule form
 */
const useRuleForm = (deps) => {
    const { ref, computed, watch } = Vue;
    const { showToast, showConfirm, t, requireLogin, login,
            loadRules, rulePreviewCache, ruleDetailId, loadRuleDetail, rulesMarkDirty, openRuleOrder,
            responsesMarkDirty, responseSseEvents } = deps;

    // --- Utility function ---
    const matchAll = (text, keywords) => keywords.every(kw => text.includes(kw));

    // --- Form status ---
    const showModal = ref(false);
    const editing = ref(null);
    const form = ref({ protocol: 'HTTP', action: 'MOCK', forwardTargetMode: 'ORIGINAL_HOST', httpTargetConnectionId: null, jmsTargetConnectionId: null, targetHost: '', matchKey: '', method: 'GET', status: 200, delayMs: 0, maxDelayMs: null, priority: 0, responseBody: '', condition: '', description: '', enabled: true, isProtected: false, tags: '', responseMode: 'new', responseId: null, faultType: 'NONE', scenarioName: '', requiredScenarioState: '', newScenarioState: '' });
    const httpTargetConnections = ref([]);
    const loadHttpTargetConnections = async () => {
        const response = await apiCall('/api/admin/http-target-connections', {}, { silent: true });
        if (response && response.ok) httpTargetConnections.value = await response.json();
    };
    const jmsTargetConnections = ref([]);
    const loadJmsTargetConnections = async () => {
        const response = await apiCall('/api/admin/jms-target-connections', {}, { silent: true });
        if (response && response.ok) jmsTargetConnections.value = await response.json();
    };
    const loadTargetConnectionsForProtocol = () => form.value.protocol === 'JMS'
        ? loadJmsTargetConnections() : loadHttpTargetConnections();
    const conditions = ref([]);
    const conditionsExpanded = ref(true);
    const formErrors = ref({});

    // --- Advanced conditions (matchSpec) ---
    // simple: original condition column (saved as three old fields); advanced: tree condition (saved as matchSpec), see match-tree.js
    const matchMode = ref('simple');
    const matchTree = ref(MatchTree.group('all', []));
    const matchError = ref('');
    // When advanced conditions cannot be transferred back to the condition column, confirm on the screen before switching back to simple mode.
    const matchSwitchPending = ref(false);
    const matchSources = computed(() => (form.value.protocol === 'JMS' ? ['body'] : ['body', 'query', 'header']));
    const hasMatchSpecObject = r => !!(r && r.matchSpec && typeof r.matchSpec === 'object');
    const resetMatchState = (r = null) => {
        matchError.value = '';
        matchSwitchPending.value = false;
        if (hasMatchSpecObject(r)) {
            try {
                matchTree.value = MatchTree.fromSpec(r.matchSpec);
                matchMode.value = 'advanced';
                return;
            } catch { /* When the format is wrong, it is opened with empty advanced conditions and will be reported by the backend when saving. */ }
        }
        // Older conditions that cannot be fully expressed in the conditional column (for example, without operators) are displayed as they are in the old format fragment of advanced mode and will not be lost or changed when saved.
        if (r && MatchTree.toSimpleRows(r.bodyCondition, r.queryCondition, r.headerCondition) === null) {
            matchTree.value = MatchTree.fromLegacyStrings(r.bodyCondition, r.queryCondition, r.headerCondition);
            matchMode.value = 'advanced';
            return;
        }
        matchTree.value = MatchTree.group('all', []);
        matchMode.value = 'simple';
    };
    const serializeSimpleConditions = () => {
        const join = type => conditions.value.filter(c => c.type === type && c.field && c.value).map(c => c.field + (c.operator || '=') + c.value).join(';') || null;
        return { bodyCondition: join('body'), queryCondition: join('query'), headerCondition: join('header') };
    };
    /** The advanced conditions on the current screen; use loose transformation when not saving (such as switching to declarative editing) to prevent the conditions from disappearing. */
    const currentMatchSpec = ({ lenient = true } = {}) => (matchMode.value === 'advanced'
        ? MatchTree.toSpec(matchTree.value, lenient) : null);
    const setMatchMode = async mode => {
        if (mode === matchMode.value) { matchSwitchPending.value = false; return; }
        if (mode === 'advanced') {
            const strings = serializeSimpleConditions();
            let tree = MatchTree.fromLegacyStrings(strings.bodyCondition, strings.queryCondition, strings.headerCondition);
            // Split by the backend, exactly the same as the comparison; when it fails, the result of the same local rule is used
            const response = await apiCall('/api/admin/rules/match/parse', {
                method: 'POST', body: JSON.stringify({ protocol: form.value.protocol, ...strings })
            }, { silent: true });
            if (response && response.ok) {
                try { tree = MatchTree.fromSpec((await response.json()).matchSpec); } catch { /* keep local */ }
            }
            matchTree.value = tree;
            matchMode.value = 'advanced';
            matchSwitchPending.value = false;
            return;
        }
        const strings = MatchTree.toLegacyStrings(matchTree.value);
        if (!strings || !MatchTree.toSimpleRows(strings.bodyCondition, strings.queryCondition, strings.headerCondition)) {
            matchSwitchPending.value = true;
            return;
        }
        conditions.value = [];
        parseConditions(strings);
        matchMode.value = 'simple';
        matchError.value = '';
    };
    /** Switch back to simple mode after confirmation: Advanced conditions are cleared (explicitly send clear when saving). */
    const confirmSimpleMatchMode = () => {
        conditions.value = [];
        matchTree.value = MatchTree.group('all', []);
        matchMode.value = 'simple';
        matchSwitchPending.value = false;
        matchError.value = '';
    };
    const cancelSimpleMatchMode = () => { matchSwitchPending.value = false; };
    /** Apply conditions when declarative documents are converted back to forms. */
    const applyMatchDraft = (spec, draftConditions) => {
        if (spec && typeof spec === 'object') {
            resetMatchState({ matchSpec: spec });
            conditions.value = [];
        } else {
            resetMatchState(null);
            conditions.value = draftConditions || [];
        }
    };
    // When editing, check immediately with the same rules as saving (no data is written), error message with JSON path
    let matchValidationSeq = 0;
    const validateMatchNow = async () => {
        if (matchMode.value !== 'advanced' || !showModal.value) { matchError.value = ''; return; }
        let spec;
        try { spec = MatchTree.toSpec(matchTree.value); }
        catch { matchError.value = t('match.invalidJsonValue'); return; }
        const seq = ++matchValidationSeq;
        const response = await apiCall('/api/admin/rules/match/validate', {
            method: 'POST', body: JSON.stringify({ protocol: form.value.protocol, matchSpec: spec })
        }, { silent: true });
        if (seq !== matchValidationSeq || !response || !response.ok) return;
        try {
            const result = await response.json();
            if (seq === matchValidationSeq) matchError.value = result.valid === false ? (result.error || '') : '';
        } catch { /* No error will be displayed when the check fails, and the backend will still check it when saving. */ }
    };
    const validateMatchDebounced = debounce(validateMatchNow, 400);

    // --- Modal Maximize ---
    const ruleModalMaximized = ref(false);
    const responseModalMaximized = ref(false);

    // --- SSE event ---
    const sseEvents = ref([]);
    const addSseEvent = () => { sseEvents.value.push({ event: '', data: '', id: '', delayMs: 0, type: 'normal' }); };
    const removeSseEvent = (index) => { if (sseEvents.value.length > 1) { sseEvents.value.splice(index, 1); } };
    // --- Verification ---
    const validateForm = () => {
        const errors = {};
        const faulting = form.value.faultType && form.value.faultType !== 'NONE';
        if (form.value.protocol === 'HTTP') {
            if (!form.value.matchKey) errors.matchKey = t('validation.matchKeyRequired');
            else if (!form.value.matchKey.startsWith('/') && form.value.matchKey !== '*') errors.matchKey = t('validation.matchKeyFormat');
            if (!form.value.method) errors.method = t('validation.methodRequired');
            if (form.value.action !== 'FORWARD' && (!faulting || form.value.faultType === 'EMPTY_RESPONSE')) {
                if (!form.value.status) errors.status = t('validation.statusRequired');
                else if (form.value.status < 100 || form.value.status > 599) errors.status = t('validation.statusRange');
            } else if (!faulting && form.value.forwardTargetMode === 'CONNECTION' && !form.value.httpTargetConnectionId) {
                errors.httpTargetConnectionId = t('validation.httpConnectionRequired');
            } else if (!faulting && form.value.forwardTargetMode === 'CONNECTION'
                && !httpTargetConnections.value.some(target => target.id === form.value.httpTargetConnectionId && target.enabled)) {
                errors.httpTargetConnectionId = t('validation.httpConnectionDisabled');
            } else if (!faulting && form.value.forwardTargetMode === 'DEFAULT_CONNECTION'
                && !httpTargetConnections.value.some(target => target.enabled && target.defaultConnection)) {
                errors.httpTargetConnectionId = t('validation.httpDefaultConnectionRequired');
            }
        } else if (form.value.protocol === 'JMS') {
            if (!form.value.matchKey) errors.matchKey = t('validation.queueNameRequired');
            if (!faulting && form.value.action === 'FORWARD') {
                if (form.value.forwardTargetMode === 'CONNECTION' && !form.value.jmsTargetConnectionId) {
                    errors.jmsTargetConnectionId = t('validation.jmsConnectionRequired');
                } else if (form.value.forwardTargetMode === 'CONNECTION'
                    && !jmsTargetConnections.value.some(target => target.id === form.value.jmsTargetConnectionId && target.enabled)) {
                    errors.jmsTargetConnectionId = t('validation.jmsConnectionDisabled');
                } else if (form.value.forwardTargetMode === 'DEFAULT_CONNECTION'
                    && !jmsTargetConnections.value.some(target => target.enabled && target.defaultConnection)) {
                    errors.jmsTargetConnectionId = t('validation.jmsDefaultConnectionRequired');
                }
            }
        }
        const requiresResponse = !faulting
            && form.value.action !== 'FORWARD';
        if (requiresResponse && form.value.responseMode === 'new') {
            if (!form.value.responseBody) errors.responseBody = t('validation.responseBodyRequired');
            else if (form.value.sseEnabled) {
                try {
                    const arr = JSON.parse(form.value.responseBody);
                    if (!Array.isArray(arr)) { errors.responseBody = t('validation.sseArrayFormat'); }
                    else if (arr.length === 0) { errors.responseBody = t('validation.sseArrayEmpty'); }
                } catch (e) { errors.responseBody = t('validation.sseJsonError', {message: e.message}); }
            }
        } else if (requiresResponse && form.value.responseMode === 'existing') {
            if (!form.value.responseId) errors.responseId = t('validation.responseIdRequired');
        }
        if (form.value.delayMs < 0) errors.delayMs = t('validation.delayNonNegative');
        if (form.value.maxDelayMs != null && form.value.maxDelayMs < 0) {
            errors.maxDelayMs = t('validation.delayNonNegative');
        }
        if (form.value.maxDelayMs != null && form.value.maxDelayMs > 0 && form.value.delayMs > form.value.maxDelayMs) {
            errors.maxDelayMs = t('validation.maxDelayMustBeGreater');
        }
        if ((form.value.requiredScenarioState || form.value.newScenarioState) && !form.value.scenarioName) {
            errors.scenarioName = t('validation.scenarioNameRequired');
        }
        formErrors.value = errors;
        return Object.keys(errors).length === 0;
    };

    // --- Catch-all detection ---
    const showCatchAllWarning = ref(false);
    const catchAllConfirmed = ref(false);
    const isCatchAll = computed(() => {
        const key = (form.value.matchKey || '').trim();
        if (!key || /^\/?\*{1,3}\/?$/.test(key) || key === '/**') {
            if (matchMode.value === 'advanced') return !MatchTree.hasConditions(matchTree.value);
            return !conditions.value.some(c => c.field && c.value);
        }
        return false;
    });

    const NO_BODY_METHODS = ['GET', 'HEAD', 'OPTIONS'];
    const showBodyConditionWarning = computed(() => {
        if (form.value.protocol !== 'HTTP') { return false; }
        if (!NO_BODY_METHODS.includes(form.value.method)) { return false; }
        if (matchMode.value === 'advanced') return MatchTree.usesBody(matchTree.value);
        return conditions.value.some(c => c.type === 'body' && c.field && c.value);
    });

    const canSave = computed(() => {
        const faulting = form.value.faultType && form.value.faultType !== 'NONE';
        if (faulting) {
            if (!form.value.matchKey) return false;
            if (form.value.protocol === 'HTTP' && !form.value.method) return false;
            if (form.value.protocol === 'HTTP' && form.value.faultType === 'EMPTY_RESPONSE' && !form.value.status) return false;
            return true;
        }
        if (form.value.protocol === 'HTTP' && form.value.action === 'FORWARD') {
            if (!form.value.matchKey || !form.value.method) return false;
            if (form.value.forwardTargetMode === 'CONNECTION') {
                return httpTargetConnections.value.some(target =>
                    target.id === form.value.httpTargetConnectionId && target.enabled
                );
            }
            if (form.value.forwardTargetMode === 'DEFAULT_CONNECTION') {
                return httpTargetConnections.value.some(target => target.enabled && target.defaultConnection);
            }
            return true;
        }
        if (form.value.protocol === 'JMS' && form.value.action === 'FORWARD') {
            if (!form.value.matchKey) return false;
            if (form.value.forwardTargetMode === 'CONNECTION') {
                return jmsTargetConnections.value.some(target =>
                    target.id === form.value.jmsTargetConnectionId && target.enabled
                );
            }
            return jmsTargetConnections.value.some(target => target.enabled && target.defaultConnection);
        }
        if (form.value.sseEnabled && form.value.protocol === 'HTTP') {
            // Same as server: each event must have data, delay is an integer millisecond from 0–600000
            if (!sseEvents.value.length || !sseEvents.value.every(e => e.data
                && Number.isInteger(e.delayMs) && e.delayMs >= 0 && e.delayMs <= 600000)) return false;
            if (form.value.responseMode === 'existing' && !form.value.responseId) return false;
        } else if (form.value.responseMode === 'existing') {
            if (!form.value.responseId) return false;
        } else {
            if (!form.value.responseBody) return false;
        }
        if (form.value.protocol === 'HTTP') {
            if (!form.value.matchKey || !form.value.method || !form.value.status) return false;
        }
        return true;
    });

    // --- response selector ---
    const responsePickerSearch = ref('');
    const responsePickerAppliedSearch = ref('');
    const responseDropdownOpen = ref(false);
    const responsePickerSseOnly = ref(false);
    const responsePickerResults = ref([]);
    const responsePickerPage = ref(0);
    const responsePickerPageSize = 20;
    const responsePickerTotalElements = ref(0);
    const responsePickerTotalPages = ref(0);
    const responsePickerLoading = ref(false);
    const responsePickerError = ref(false);
    const selectedResponseSummary = ref(null);
    let responsePickerRequestSequence = 0;
    let responsePickerAbortController = null;

    const filteredResponsePicker = computed(() => responsePickerResults.value);
    const responsePickerSummary = computed(() => {
        const selected = selectedResponseSummary.value;
        if (!selected) return responsePickerResults.value;
        return responsePickerResults.value.some(response => response.id === selected.id)
            ? responsePickerResults.value
            : [selected, ...responsePickerResults.value];
    });

    const loadResponsePicker = async (requestedPage = responsePickerPage.value) => {
        const requestId = ++responsePickerRequestSequence;
        responsePickerAbortController?.abort();
        const abortController = new AbortController();
        responsePickerAbortController = abortController;
        responsePickerLoading.value = true;
        responsePickerError.value = false;
        const page = Math.max(0, Number(requestedPage) || 0);
        const params = new URLSearchParams({
            page: String(page),
            size: String(responsePickerPageSize),
            sort: 'updatedAt',
            direction: 'desc'
        });
        if (responsePickerAppliedSearch.value.trim()) params.set('keyword', responsePickerAppliedSearch.value.trim());
        if (responsePickerSseOnly.value) params.set('contentType', 'SSE');
        try {
            const response = await apiCall(`/api/admin/responses/summary?${params.toString()}`, { signal: abortController.signal }, { silent: true });
            if (requestId !== responsePickerRequestSequence) return false;
            if (!response || !response.ok) {
                responsePickerError.value = true;
                return false;
            }
            const data = await response.json();
            if (requestId !== responsePickerRequestSequence) return false;
            responsePickerResults.value = data.results || [];
            responsePickerPage.value = Number(data.page || 0);
            responsePickerTotalElements.value = Number(data.totalElements || 0);
            responsePickerTotalPages.value = Number(data.totalPages || 0);
            return true;
        } catch (error) {
            if (error?.name !== 'AbortError' && requestId === responsePickerRequestSequence) responsePickerError.value = true;
            return false;
        } finally {
            if (requestId === responsePickerRequestSequence) {
                responsePickerLoading.value = false;
                if (responsePickerAbortController === abortController) responsePickerAbortController = null;
            }
        }
    };

    const openResponsePickerDrawer = () => {
        responseDropdownOpen.value = true;
        loadResponsePicker(0);
    };
    const submitResponsePickerSearch = () => {
        responsePickerAppliedSearch.value = responsePickerSearch.value.trim();
        loadResponsePicker(0);
    };
    const setResponsePickerSseOnly = value => {
        responsePickerSseOnly.value = Boolean(value);
        loadResponsePicker(0);
    };
    const changeResponsePickerPage = page => loadResponsePicker(page);
    const selectResponseOption = response => {
        if (!response) return;
        selectedResponseSummary.value = response;
        form.value.responseId = response.id;
        responseDropdownOpen.value = false;
        responsePickerSearch.value = '';
        responsePickerAppliedSearch.value = '';
    };
    const clearResponseSelection = () => {
        form.value.responseId = null;
        selectedResponseSummary.value = null;
        sseEvents.value = [{ event: '', data: '', id: '', delayMs: 0, type: 'normal' }];
    };

    // --- Response preview ---
    const previewResponseId = ref(null);
    const previewResponseBody = ref('');
    const previewResponseLoading = ref(false);
    const previewResponseLoadFailed = ref(false);
    const previewEditing = ref(false);
    const previewEditBody = ref('');
    const previewResponseUsageCount = ref(0);
    const previewSaving = ref(false);
    const resetResponsePreview = () => {
        previewLoadSequence += 1;
        previewResponseId.value = null;
        previewResponseBody.value = '';
        previewResponseLoading.value = false;
        previewResponseLoadFailed.value = false;
        previewEditing.value = false;
        previewEditBody.value = '';
        previewResponseUsageCount.value = 0;
    };

    // --- Form Assistant ---
    const newTag = ref({ key: '', value: '' });
    const addTag = () => { if (!newTag.value.key) return; const tags = parseTags(form.value.tags); tags[newTag.value.key] = newTag.value.value; form.value.tags = JSON.stringify(tags); newTag.value = { key: '', value: '' } };
    const removeTag = k => { const tags = parseTags(form.value.tags); delete tags[k]; form.value.tags = Object.keys(tags).length ? JSON.stringify(tags) : '' };
    const newHeader = ref({ key: '', value: '' });
    const addHeader = () => { if (!newHeader.value.key) return; const h = parseHeaders(form.value.responseHeaders); h[newHeader.value.key] = newHeader.value.value; form.value.responseHeaders = JSON.stringify(h); newHeader.value = { key: '', value: '' } };
    const removeHeader = k => { const h = parseHeaders(form.value.responseHeaders); delete h[k]; form.value.responseHeaders = Object.keys(h).length ? JSON.stringify(h) : '' };

    // --- Test area ---
    const testExpanded = ref(false);
    const testParams = ref({ query: '', headersStr: '', body: '', timeout: 30 });
    const testResult = ref(null);
    const testLoading = ref(false);
    const testSseEvents = ref([]);
    const testSseAbort = ref(null);

    /** Automatically generate test data based on current conditions */
    const generateTestData = () => {
        const bodyConds = conditions.value.filter(c => c.type === 'body' && c.field && c.value);
        const queryConds = conditions.value.filter(c => c.type === 'query' && c.field && c.value);
        const headerConds = conditions.value.filter(c => c.type === 'header' && c.field && c.value);

        // Determine body format: XML is generated when the condition field is XPath (starting with /)
        const isXml = bodyConds.some(c => c.field.startsWith('/'));

        // Generate Body
        if (bodyConds.length) {
            const resolveValue = (c) => {
                if (c.operator === '=' || c.operator === '*=') { return c.value; }
                if (c.operator === '!=') { return 'other'; }
                if (c.operator === '~=') { return c.value.replace(/[.*+?^${}()|[\]\\]/g, '') || 'test'; }
                return c.value;
            };

            if (isXml) {
                // Generate XML
                const elements = {};
                for (const c of bodyConds) {
                    const val = resolveValue(c);
                    // Take the last paragraph as the element name (//order/type → type, type → type)
                    const parts = c.field.split('/').filter(Boolean);
                    const tag = parts[parts.length - 1] || c.field;
                    elements[tag] = val;
                }
                let xml = '<?xml version="1.0" encoding="UTF-8"?>\n<root>\n';
                for (const [tag, val] of Object.entries(elements)) {
                    xml += '  <' + tag + '>' + val + '</' + tag + '>\n';
                }
                xml += '</root>';
                testParams.value.body = xml;
            } else {
                // Generate JSON
                const obj = {};
                for (const c of bodyConds) {
                    const val = resolveValue(c);
                    const parts = c.field.replace(/^\$\./, '').split('.');
                    let cur = obj;
                    for (let i = 0; i < parts.length - 1; i++) {
                        const p = parts[i];
                        const arrMatch = p.match(/^(.+)\[(\d+)\]$/);
                        if (arrMatch) {
                            if (!cur[arrMatch[1]]) { cur[arrMatch[1]] = []; }
                            const idx = parseInt(arrMatch[2]);
                            while (cur[arrMatch[1]].length <= idx) { cur[arrMatch[1]].push({}); }
                            cur = cur[arrMatch[1]][idx];
                        } else {
                            if (!cur[p] || typeof cur[p] !== 'object') { cur[p] = {}; }
                            cur = cur[p];
                        }
                    }
                    const lastPart = parts[parts.length - 1];
                    const lastArr = lastPart.match(/^(.+)\[(\d+)\]$/);
                    if (lastArr) {
                        if (!cur[lastArr[1]]) { cur[lastArr[1]] = []; }
                        cur[lastArr[1]][parseInt(lastArr[2])] = val;
                    } else {
                        cur[lastPart] = val;
                    }
                }
                testParams.value.body = JSON.stringify(obj, null, 2);
            }
        }

        // Generate Query
        if (queryConds.length) {
            testParams.value.query = queryConds
                .map(c => c.field + '=' + ((c.operator === '=' || c.operator === '*=') ? c.value : 'test'))
                .join('&');
        }

        // Generate Headers
        if (headerConds.length) {
            testParams.value.headersStr = headerConds
                .map(c => c.field + ':' + ((c.operator === '=' || c.operator === '*=') ? c.value : 'test'))
                .join(', ');
        }

        // Expand test area
        if (!testExpanded.value) {
            testExpanded.value = true;
        }
    };
    const testSseMode = ref(false);

    const stopSseTest = () => {
        if (testSseAbort.value) {
            testSseAbort.value.abort();
            testSseAbort.value = null;
        }
        testLoading.value = false;
    };

    const runTest = async () => {
        if (!editing.value) return;
        stopSseTest();
        testResult.value = null;
        testSseEvents.value = [];
        testLoading.value = true;
        const isSse = form.value.sseEnabled && form.value.protocol === 'HTTP';
        testSseMode.value = isSse;
        if (isSse) {
            const path = form.value.matchKey === '*' ? '/test' : form.value.matchKey;
            const q = testParams.value.query;
            const url = '/mock' + path + (q ? '?' + q : '');
            const hdrs = { 'Accept': 'text/event-stream', 'X-Original-Host': form.value.targetHost || '' };
            if (testParams.value.headersStr) {
                testParams.value.headersStr.split(',').forEach(h => {
                    const colon = h.indexOf(':');
                    const k = h.slice(0, colon).trim(), v = h.slice(colon + 1).trim();
                    if (k && v) { hdrs[k] = v; }
                });
            }
            const ac = new AbortController();
            testSseAbort.value = ac;
            const startTime = Date.now();
            let reader;
            try {
                const method = form.value.method ? form.value.method.toUpperCase() : 'GET';
                const options = { method, headers: hdrs, signal: ac.signal };
                if (method !== 'GET' && method !== 'HEAD') {
                    options.body = testParams.value.body;
                    if (!Object.keys(hdrs).some(k => k.toLowerCase() === 'content-type')) hdrs['Content-Type'] = 'application/json';
                }
                const r = await fetch(url, options);
                if (r.status !== 200 || !r.body || !/^text\/event-stream(?:\s*;|$)/i.test(r.headers.get('Content-Type') || '')) {
                    const body = await r.text();
                    if (testSseAbort.value === ac) testResult.value = { status: r.status, body: body || 'Request failed', elapsed: Date.now() - startTime };
                    return;
                }
                reader = r.body.getReader();
                const decoder = new TextDecoder();
                const parser = createSseParser(event => {
                    if (testSseAbort.value === ac) testSseEvents.value.push({ ...event, time: Date.now() - startTime });
                });
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) {
                        parser.push(decoder.decode());
                        parser.finish();
                        break;
                    }
                    parser.push(decoder.decode(value, { stream: true }));
                }
            } catch (e) {
                if (e.name !== 'AbortError' && testSseAbort.value === ac) {
                    testResult.value = { status: 0, body: e.message, elapsed: Date.now() - startTime };
                }
            } finally {
                if (reader) reader.releaseLock();
                if (testSseAbort.value === ac) {
                    testSseAbort.value = null;
                    testLoading.value = false;
                }
            }
        } else {
            try {
                const payload = { body: testParams.value.body };
                if (form.value.protocol === 'HTTP') {
                    payload.query = testParams.value.query;
                    const headers = {};
                    if (testParams.value.headersStr) {
                        testParams.value.headersStr.split(',').forEach(h => {
                            const [k, v] = h.split(':').map(s => s.trim());
                            if (k && v) { headers[k] = v; }
                        });
                    }
                    payload.headers = headers;
                } else {
                    payload.timeout = testParams.value.timeout;
                }
                const r = await apiCall(`/api/admin/rules/${editing.value.id}/test`, { method: 'POST', body: JSON.stringify(payload) }, { errorMsg: t('toast.testFailed') });
                if (r && r.ok) {
                    testResult.value = await r.json();
                    try { testResult.value.body = JSON.stringify(JSON.parse(testResult.value.body), null, 2); } catch {}
                } else if (r) {
                    testResult.value = { status: r.status, body: 'Request failed', elapsed: 0 };
                } else {
                    testResult.value = { status: 0, body: t('toast.networkFailedShort'), elapsed: 0 };
                }
            } catch (e) {
                testResult.value = { status: 500, body: e.message, elapsed: 0 };
            }
            testLoading.value = false;
        }
    };

    // --- Form operations ---
    const setProtocol = p => {
        form.value.protocol = p;
        form.value.matchKey = p === 'JMS' ? '*' : '';
        form.value.action = 'MOCK';
        form.value.forwardTargetMode = p === 'JMS' ? 'DEFAULT_CONNECTION' : 'ORIGINAL_HOST';
        form.value.httpTargetConnectionId = null;
        form.value.jmsTargetConnectionId = null;
        if (p === 'JMS') form.value.sseEnabled = false;
        loadTargetConnectionsForProtocol();
        conditions.value = [];
        resetMatchState(null);
        localStorage.setItem('lastProtocol', p);
    };

    const resetForm = () => {
        const protocol = localStorage.getItem('lastProtocol') || 'HTTP';
        return { protocol, action: 'MOCK', forwardTargetMode: protocol === 'JMS' ? 'DEFAULT_CONNECTION' : 'ORIGINAL_HOST', httpTargetConnectionId: null, jmsTargetConnectionId: null, targetHost: '', matchKey: '', method: 'GET', status: 200, delayMs: 0, maxDelayMs: null, priority: 0, responseBody: '', responseDescription: '', responseHeaders: '', bodyCondition: '', queryCondition: '', headerCondition: '', description: '', enabled: true, isProtected: false, tags: '', responseMode: 'new', responseId: null, sseEnabled: false, sseLoopEnabled: false, faultType: 'NONE', scenarioName: '', requiredScenarioState: '', newScenarioState: '' };
    };

    const onResponseModeChange = () => {
        if (form.value.responseMode === 'existing') {
            responsePickerSearch.value = '';
            responsePickerAppliedSearch.value = '';
            loadResponsePicker(0);
        }
        responseDropdownOpen.value = false;
    };

    /** The old version of conditional strings is converted into conditional columns, and the disassembly rules are the same as the backend (see MatchTree.parseLegacyCondition); it is not added when it cannot be fully expressed, and resetMatchState uses advanced mode instead. */
    const parseConditions = r => {
        const rows = MatchTree.toSimpleRows(r.bodyCondition, r.queryCondition, r.headerCondition);
        if (rows) conditions.value.push(...rows);
    };

    const openCreate = async () => {
        if (!await requireLogin()) return;
        editing.value = null;
        resetResponsePreview();
        form.value = resetForm();
        form.value.matchKey = form.value.protocol === 'JMS' ? '*' : '';
        conditions.value = [];
        resetMatchState(null);
        sseEvents.value = [{ event: '', data: '', id: '', delayMs: 0, type: 'normal' }];
        formErrors.value = {};
        catchAllConfirmed.value = false;
        showCatchAllWarning.value = false;
        loadTargetConnectionsForProtocol();
        showModal.value = true;
    };

    const copyRule = async r => {
        if (!await requireLogin()) return;
        editing.value = null;
        resetResponsePreview();
        form.value = { ...r, id: undefined, description: (r.description || '') + t('common.copySuffix'), responseMode: 'new', responseId: null };
        conditions.value = [];
        catchAllConfirmed.value = false;
        showCatchAllWarning.value = false;
        sseEvents.value = (r.sseEnabled && r.responseBody) ? deserializeSseEvents(r.responseBody) : [{ event: '', data: '', id: '', delayMs: 0, type: 'normal' }];
        parseConditions(r);
        resetMatchState(r);
        loadTargetConnectionsForProtocol();
        showModal.value = true;
    };

    const createFromLog = async (ruleDto) => {
        if (!await requireLogin()) return;
        editing.value = null;
        resetResponsePreview();
        form.value = {
            ...resetForm(),
            protocol: ruleDto.protocol || 'HTTP',
            matchKey: ruleDto.matchKey || '',
            method: ruleDto.method || 'GET',
            targetHost: ruleDto.targetHost || '',
            status: ruleDto.status || 200,
            responseBody: ruleDto.responseBody || '',
            description: ruleDto.description || '',
            enabled: ruleDto.enabled !== false,
            priority: ruleDto.priority || 0,
            delayMs: ruleDto.delayMs || 0,
            maxDelayMs: null,
            responseMode: 'new',
            responseId: null,
            faultType: 'NONE'
        };
        conditions.value = [];
        resetMatchState(null);
        sseEvents.value = [{ event: '', data: '', id: '', delayMs: 0, type: 'normal' }];
        formErrors.value = {};
        catchAllConfirmed.value = false;
        showCatchAllWarning.value = false;
        loadTargetConnectionsForProtocol();
        showModal.value = true;
    };

    const openEdit = async r => {
        if (!await requireLogin()) return;
        stopSseTest();
        testResult.value = null;
        testSseEvents.value = [];
        testSseMode.value = false;
        editing.value = r;
        formErrors.value = {};
        catchAllConfirmed.value = false;
        showCatchAllWarning.value = false;
        resetResponsePreview();
        // Always read the latest content and version: the information on the list may be out of date, and saving old content will overwrite other people's modifications.
        const res = await apiCall(`/api/admin/rules/${r.id}`, {}, { silent: true });
        if (res && res.ok) { r = await res.json(); }
        form.value = { ...r, action: r.action || 'MOCK', forwardTargetMode: r.forwardTargetMode || (r.protocol === 'JMS' ? 'DEFAULT_CONNECTION' : 'ORIGINAL_HOST'), responseMode: r.responseId ? 'existing' : 'new' };
        conditions.value = [];
        sseEvents.value = (r.sseEnabled && r.responseBody) ? deserializeSseEvents(r.responseBody) : [{ event: '', data: '', id: '', delayMs: 0, type: 'normal' }];
        parseConditions(r);
        resetMatchState(r);
        if (form.value.responseMode === 'existing') loadResponsePicker(0);
        loadTargetConnectionsForProtocol();
        showModal.value = true;
    };

    const closeModal = () => {
        responsePickerAbortController?.abort();
        responsePickerAbortController = null;
        responseDropdownOpen.value = false;
        responsePickerSearch.value = '';
        responsePickerAppliedSearch.value = '';
        responsePickerResults.value = [];
        responsePickerPage.value = 0;
        responsePickerTotalElements.value = 0;
        responsePickerTotalPages.value = 0;
        responsePickerError.value = false;
        selectedResponseSummary.value = null;
        showModal.value = false;
        editing.value = null;
        form.value = resetForm();
        conditions.value = [];
        resetMatchState(null);
        sseEvents.value = [];
        formErrors.value = {};
        newTag.value = { key: '', value: '' };
        newHeader.value = { key: '', value: '' };
        catchAllConfirmed.value = false;
        showCatchAllWarning.value = false;
        testExpanded.value = false;
        testResult.value = null;
        testSseEvents.value = [];
        testSseMode.value = false;
        stopSseTest();
        testParams.value = { query: '', headersStr: '', body: '', timeout: 30 };
        resetResponsePreview();
        conditionsExpanded.value = true;
    };

    const saving = ref(false);

    const saveRule = async (andClose = false) => {
        // SSE mode: Serialize table data to responseBody. When using existing responses, the event is sent along with the rule,
        // The response is overwritten by the backend within the same transaction (the response will not be modified if the rule save fails).
        let existingResponseBody = null;
        if (form.value.action !== 'FORWARD' && form.value.sseEnabled && form.value.protocol === 'HTTP') {
            const serialized = serializeSseEvents(sseEvents.value);
            if (form.value.responseMode === 'existing' && form.value.responseId) {
                existingResponseBody = serialized;
            } else {
                form.value.responseBody = serialized;
            }
        }
        if (!validateForm()) {
            showToast(t('toast.formValidationError'), 'error');
            return;
        }
        if (isCatchAll.value && !catchAllConfirmed.value) {
            showCatchAllWarning.value = true;
            return;
        }
        let matchSpec = null;
        if (matchMode.value === 'advanced') {
            try { matchSpec = MatchTree.toSpec(matchTree.value); }
            catch {
                matchError.value = t('match.invalidJsonValue');
                showToast(t('match.invalidJsonValue'), 'error');
                return;
            }
        }
        saving.value = true;
        const simple = matchMode.value === 'advanced'
            ? { bodyCondition: null, queryCondition: null, headerCondition: null }
            : serializeSimpleConditions();
        form.value.bodyCondition = simple.bodyCondition;
        form.value.queryCondition = simple.queryCondition;
        form.value.headerCondition = simple.headerCondition;
        // When using the existing Response: the response content being edited is sent together with the rules and stored in the same transaction
        const faulting = form.value.faultType && form.value.faultType !== 'NONE';
        const previewEdited = !faulting && form.value.action !== 'FORWARD' && form.value.responseMode === 'existing'
            && form.value.responseId && previewEditing.value && previewEditBody.value !== previewResponseBody.value;
        if (previewEdited && existingResponseBody === null) existingResponseBody = previewEditBody.value;
        const payload = { ...form.value };
        delete payload.advancedMatch;
        if (matchSpec) {
            payload.matchSpec = matchSpec;
        } else {
            // If the rule originally used advanced conditions, but now there are no conditions, it must be cleared clearly; when matchSpec is not sent, the backend will retain the original advanced conditions.
            const hadAdvanced = hasMatchSpecObject(editing.value);
            const noLegacy = !simple.bodyCondition && !simple.queryCondition && !simple.headerCondition;
            payload.matchSpec = hadAdvanced && noLegacy ? { all: [] } : null;
        }
        if (faulting) {
            payload.action = 'MOCK';
            payload.responseBody = null;
            payload.responseId = null;
            payload.responseDescription = null;
            payload.responseHeaders = null;
            payload.sseEnabled = false;
            payload.sseLoopEnabled = false;
            payload.forwardTargetMode = null;
            payload.httpTargetConnectionId = null;
            payload.jmsTargetConnectionId = null;
        } else if (payload.action === 'FORWARD') {
            payload.responseBody = null;
            payload.responseId = null;
            payload.sseEnabled = false;
            payload.sseLoopEnabled = false;
            if (payload.protocol === 'HTTP') {
                payload.jmsTargetConnectionId = null;
                if (payload.forwardTargetMode !== 'CONNECTION') payload.httpTargetConnectionId = null;
            } else {
                payload.httpTargetConnectionId = null;
                if (payload.forwardTargetMode !== 'CONNECTION') payload.jmsTargetConnectionId = null;
            }
        } else if (payload.responseMode === 'existing') {
            payload.responseBody = existingResponseBody;
            payload.updateExistingResponse = existingResponseBody !== null;
        }
        else { payload.responseId = null; }
        delete payload.responseMode;
        let savedSuccessfully = false;
        let saveConflict = null;
        try {
            const url = editing.value ? `/api/admin/rules/${editing.value.id}` : '/api/admin/rules';
            const r = await apiCall(url, { method: editing.value ? 'PUT' : 'POST', body: JSON.stringify(payload) },
                { errorMsg: t('toast.ruleSaveFailed'), quietStatuses: [409] });
            if (r && r.ok) {
                const saved = await r.json();
                savedSuccessfully = true;
                if (payload.updateExistingResponse) {
                    if (previewEdited) { previewResponseBody.value = previewEditBody.value; previewEditing.value = false; }
                    responsesMarkDirty();
                }
                // Only display a reminder (including "Saved") when there is a problem with determining the order, otherwise display a general success message
                const savedMessage = editing.value ? t('toast.ruleSaveSuccess') : t('toast.ruleCreateSuccess');
                neverReachedWarning(saved).then(warning => {
                    if (!warning) { showToast(savedMessage, 'success'); return; }
                    showToast({ title: warning.title, detail: warning.detail }, 'warning',
                        openRuleOrder ? { label: t('rules.order.viewOrder'), handler: () => openRuleOrder(warning.ruleId) } : null);
                });
                rulesMarkDirty();
                delete rulePreviewCache.value[saved.id];
                if (ruleDetailId?.value === saved.id) { loadRuleDetail(saved.id, { force: true }); }
                if (andClose) { closeModal(); loadRules(true); }
                else {
                    editing.value = saved;
                    // The backend will convert advanced conditions that only use the old syntax back to the condition column, and the screen will store the results accordingly.
                    form.value.matchSpec = saved.matchSpec ?? null;
                    form.value.advancedMatch = saved.advancedMatch;
                    conditions.value = [];
                    parseConditions(saved);
                    resetMatchState(saved);
                    form.value.id = saved.id;
                    form.value.version = saved.version;
                    form.value.responseId = saved.responseId;
                    form.value.createdAt = saved.createdAt;
                    form.value.updatedAt = saved.updatedAt;
                    loadRules(true);
                }
            }
            else if (r && r.status === 409 && editing.value) { saveConflict = editing.value.id; }
            else if (r && (r.status === 401 || r.status === 403)) { login(); }
        } catch (e) { showToast(t('toast.ruleSaveFailed'), 'error'); }
        saving.value = false;
        // The rules were changed by others during editing: the form remains as it is, and it is up to the user to load the latest version or keep their own changes.
        if (saveConflict) { return { conflict: saveConflict }; }
        return savedSuccessfully;
    };

    /**
     * After saving, check evaluation order: return a warning ({title, detail, ruleId}) if this rule is unreachable or makes other rules unreachable;
     * null is returned when there is no problem or the check fails (it is just a reminder and does not affect storage).
     */
    const neverReachedWarning = async saved => {
        try {
            const r = await apiCall('/api/admin/rules/order-issues', {}, { silent: true });
            if (!r || !r.ok) { return null; }
            const issues = (await r.json()).issues || {};
            const own = issues[saved.id];
            if (own) {
                const rule = own.byDescription || (own.byRuleId || '').slice(0, 8);
                return {
                    title: t('rules.order.savedNeverTitle'),
                    detail: t(own.type === 'duplicateConditions' ? 'rules.order.savedDuplicate' : 'rules.order.savedEarlierDefault', { rule }),
                    ruleId: saved.id,
                };
            }
            const blocked = Object.entries(issues).filter(([, issue]) => issue.byRuleId === saved.id);
            if (!blocked.length) { return null; }
            const [firstId, first] = blocked[0];
            const rule = first.description || firstId.slice(0, 8);
            return blocked.length === 1
                ? { title: t('rules.order.savedBlocksTitle'), detail: t('rules.order.savedBlocksOther', { rule }), ruleId: firstId }
                : { title: t('rules.order.savedBlocksManyTitle', { count: blocked.length }), detail: t('rules.order.savedBlocksOthers', { rule }), ruleId: firstId };
        } catch {
            return null;
        }
    };

    // --- Response preview edit ---
    const togglePreviewEditing = async () => {
        if (!previewEditing.value) {
            const rid = form.value.responseId;
            if (!rid) return;
            try {
                const r = await apiCall(`/api/admin/responses/${rid}/rules`, {}, { silent: true });
                if (r && r.ok) {
                    const rules = await r.json();
                    previewResponseUsageCount.value = rules.length;
                }
            } catch { previewResponseUsageCount.value = 0; }
            previewEditBody.value = previewResponseBody.value;
            previewEditing.value = true;
        } else {
            previewEditing.value = false;
        }
    };

    const savePreviewResponse = async () => {
        const rid = form.value.responseId;
        if (!rid) return;
        previewSaving.value = true;
        try {
            const resp = selectedResponseSummary.value;
            const payload = { description: resp?.description || '', body: previewEditBody.value };
            if (resp?.version != null) { payload.version = resp.version; }
            const r = await apiCall(`/api/admin/responses/${rid}`, { method: 'PUT', body: JSON.stringify(payload) }, { errorMsg: t('toast.responseSaveFailed'), quietStatuses: [409] });
            if (r && r.status === 409) { showToast(t('toast.responseConflict'), 'error'); return; }
            if (r && r.ok) {
                const saved = await r.json().catch(() => null);
                if (saved && resp) { resp.version = saved.version; }
                showToast(t('toast.responseSaveSuccess'), 'success');
                previewResponseBody.value = previewEditBody.value;
                previewEditing.value = false;
                responsesMarkDirty();
                rulePreviewCache.value = {};
                loadResponsePicker(responsePickerPage.value);
            }
        } catch { showToast(t('toast.responseSaveFailed'), 'error'); }
        finally { previewSaving.value = false; }
    };


    let previewLoadSequence = 0;
    const loadSelectedResponse = async id => {
        const sequence = ++previewLoadSequence;
        previewResponseId.value = null;
        previewResponseLoading.value = Boolean(id);
        previewResponseLoadFailed.value = false;
        previewResponseBody.value = '';
        if (!id) {
            selectedResponseSummary.value = null;
            return;
        }
        try {
            const [response, rulesResponse] = await Promise.all([
                apiCall(`/api/admin/responses/${id}`, {}, { silent: true }),
                apiCall(`/api/admin/responses/${id}/rules`, {}, { silent: true })
            ]);
            if (sequence !== previewLoadSequence || form.value.responseId !== id) return;
            if (!response || !response.ok) {
                previewResponseLoadFailed.value = true;
                return;
            }
            const data = await response.json();
            const rules = rulesResponse?.ok ? await rulesResponse.json() : [];
            selectedResponseSummary.value = {
                id: data.id,
                description: data.description || '',
                version: data.version ?? null,
                bodySize: Number(data.bodySize || 0),
                contentType: data.contentType,
                usageCount: Array.isArray(rules) ? rules.length : 0,
                createdAt: data.createdAt,
                updatedAt: data.updatedAt
            };
            previewResponseBody.value = typeof data.body === 'string' ? data.body : '';
            previewResponseId.value = id;
            if (form.value.sseEnabled && previewResponseBody.value) {
                sseEvents.value = deserializeSseEvents(previewResponseBody.value);
            }
        } catch {
            if (sequence === previewLoadSequence && form.value.responseId === id) {
                previewResponseLoadFailed.value = true;
            }
        } finally {
            if (sequence === previewLoadSequence && form.value.responseId === id) {
                previewResponseLoading.value = false;
            }
        }
    };

    // --- Watchers ---
    const setupFormWatchers = () => {
        watch([matchTree, matchMode, () => form.value.protocol], () => {
            if (matchMode.value === 'advanced') validateMatchDebounced();
            else { validateMatchDebounced.cancel(); matchError.value = ''; }
        }, { deep: true });

        watch(isCatchAll, val => {
            if (!val) { showCatchAllWarning.value = false; catchAllConfirmed.value = false; }
        });

        // The response content is a bound ui-code-viewer: it follows form.responseBody / the selected
        // response by itself, so mode, action and fault switches no longer re-create an editor.
        watch(() => form.value.responseId, (id, oldId) => {
            previewEditing.value = false;
            previewEditBody.value = '';
            if (id !== oldId) loadSelectedResponse(id);
        });

        watch(() => form.value.responseMode, () => {
            previewEditing.value = false;
            previewEditBody.value = '';
        });

        watch(() => form.value.sseEnabled, (sse, oldSse) => {
            if (sse) {
                const body = form.value.responseMode === 'existing' ? previewResponseBody.value : form.value.responseBody;
                sseEvents.value = deserializeSseEvents(body);
            } else if (!sse && oldSse) {
                if (sseEvents.value.length) {
                    const serialized = serializeSseEvents(sseEvents.value);
                    if (form.value.responseMode === 'existing' && form.value.responseId) {
                        previewResponseBody.value = serialized;
                    } else {
                        form.value.responseBody = serialized;
                    }
                }
            }
        });

        watch(testExpanded, v => { if (v) setTimeout(() => document.getElementById('testSection')?.scrollIntoView({behavior:'smooth',block:'end'}), 50); });

        watch(previewEditing, editing => {
            // (handled by togglePreviewEditing)
        });
    };

    // --- Response to select drawer close ---
    const closeResponseDropdown = e => {
        if (!responseDropdownOpen.value) return;
        if (!e.target.closest('.response-picker-drawer, .response-picker-change, .response-picker-empty-launch')) {
            responseDropdownOpen.value = false;
        }
    };

    return {
        // Form status
        showModal, editing, form, conditions, conditionsExpanded, formErrors, saving,
        // Advanced conditions
        matchMode, matchTree, matchError, matchSwitchPending, matchSources,
        setMatchMode, confirmSimpleMatchMode, cancelSimpleMatchMode, currentMatchSpec, applyMatchDraft,
        httpTargetConnections, loadHttpTargetConnections,
        jmsTargetConnections, loadJmsTargetConnections,
        // Verify
        canSave, validateForm,
        // Catch-all detection
        showCatchAllWarning, catchAllConfirmed,
        // Body condition Warning
        showBodyConditionWarning,
        // SSE event
        sseEvents, addSseEvent, removeSseEvent,
        // Form operations
        setProtocol, resetForm, openCreate, copyRule, createFromLog, openEdit, closeModal,
        saveRule, parseConditions, onResponseModeChange,
        // Test area
        testExpanded, testParams, testResult, testLoading,
        testSseEvents, testSseMode, runTest, stopSseTest, generateTestData,
        // Response preview
        previewResponseId, previewResponseBody, previewResponseLoading, previewResponseLoadFailed,
        previewEditing, previewEditBody, previewResponseUsageCount,
        previewSaving, togglePreviewEditing, savePreviewResponse,
        // Response selector
        responsePickerSearch, responseDropdownOpen, filteredResponsePicker,
        responsePickerSseOnly, responsePickerSummary,
        responsePickerPage, responsePickerTotalElements, responsePickerTotalPages,
        responsePickerLoading, responsePickerError,
        openResponsePickerDrawer, submitResponsePickerSearch,
        setResponsePickerSseOnly, changeResponsePickerPage, selectResponseOption, clearResponseSelection,
        // Form assistance
        newTag, addTag, removeTag, newHeader, addHeader, removeHeader,
        // SSE response preview
        // Modal Maximize
        ruleModalMaximized, responseModalMaximized,
        // Format switching

        // watchers
        setupFormWatchers,
        // Response to select drawer close
        closeResponseDropdown
    };
};
