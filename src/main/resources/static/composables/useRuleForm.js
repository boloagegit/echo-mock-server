/**
 * useRuleForm - 規則表單 Composable
 *
 * 管理規則新增/編輯表單的所有狀態與邏輯，包含：
 * - 表單狀態與驗證
 * - SSE 事件編輯與預覽
 * - 條件解析
 * - 測試區（HTTP/JMS/SSE）
 * - 回應預覽與編輯
 * - 回應選擇器
 * - 表單輔助（標籤、Header）
 * - Modal 最大化狀態
 * - watchers 設定
 *
 * @param {Object} deps - 依賴物件
 * @param {Function} deps.showToast - Toast 通知函式（來自 useToast）
 * @param {Function} deps.showConfirm - 確認對話框函式（來自 useToast）
 * @param {Function} deps.t - 翻譯函式（來自 useI18n）
 * @param {Function} deps.requireLogin - 登入檢查函式（來自 useAuth）
 * @param {Function} deps.login - 登入跳轉函式（來自 useAuth）
 * @param {Function} deps.loadRules - 載入規則函式（來自 useRules）
 * @param {import('vue').Ref} deps.rules - 規則列表（來自 useRules）
 * @param {import('vue').Ref} deps.rulePreviewCache - 規則預覽快取（來自 useRules）
 * @param {import('vue').Ref} deps.ruleDetailId - 詳情抽屜目前顯示的規則 ID（來自 useRules）
 * @param {Function} deps.loadRuleDetail - 重新載入規則詳情（來自 useRules）
 * @param {Function} deps.rulesMarkDirty - 標記規則資料需重新載入（來自 useRules）
 * @param {Function} deps.responsesMarkDirty - 標記回應資料需重新載入（來自 useResponses）
 * @param {import('vue').Ref} deps.responseSseEvents - 回應 SSE 事件（來自 useResponses）
 * @param {import('vue').Ref} deps.jmsEnabled - JMS 是否啟用（來自 app.js）
 * @returns {Object} 規則表單相關的狀態與方法
 */
const useRuleForm = (deps) => {
    const { ref, computed, watch } = Vue;
    const { showToast, showConfirm, t, requireLogin, login,
            loadRules, rulePreviewCache, ruleDetailId, loadRuleDetail, rulesMarkDirty, openRuleOrder,
            responsesMarkDirty, responseSseEvents } = deps;

    // --- 工具函式 ---
    const matchAll = (text, keywords) => keywords.every(kw => text.includes(kw));

    // --- 表單狀態 ---
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

    // --- 進階條件（matchSpec）---
    // simple：原本的條件列（存成三個舊欄位）；advanced：樹狀條件（存成 matchSpec），見 match-tree.js
    const matchMode = ref('simple');
    const matchTree = ref(MatchTree.group('all', []));
    const matchError = ref('');
    // 進階條件無法轉回條件列時，切回簡易模式前先在畫面上確認
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
            } catch { /* 格式錯誤時以空的進階條件開啟，儲存時由後端回報 */ }
        }
        // 條件列無法完整表示的舊版條件（例如沒有運算子）以進階模式的舊格式片段原樣顯示，儲存時不會遺失或改變
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
    /** 目前畫面上的進階條件；不是儲存時（例如切換到宣告式編輯）用寬鬆轉換，不讓條件消失。 */
    const currentMatchSpec = ({ lenient = true } = {}) => (matchMode.value === 'advanced'
        ? MatchTree.toSpec(matchTree.value, lenient) : null);
    const setMatchMode = async mode => {
        if (mode === matchMode.value) { matchSwitchPending.value = false; return; }
        if (mode === 'advanced') {
            const strings = serializeSimpleConditions();
            let tree = MatchTree.fromLegacyStrings(strings.bodyCondition, strings.queryCondition, strings.headerCondition);
            // 由後端切分，與比對時完全相同；失敗時用本地相同規則的結果
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
    /** 確認後切回簡易模式：進階條件清空（儲存時明確送出清除）。 */
    const confirmSimpleMatchMode = () => {
        conditions.value = [];
        matchTree.value = MatchTree.group('all', []);
        matchMode.value = 'simple';
        matchSwitchPending.value = false;
        matchError.value = '';
    };
    const cancelSimpleMatchMode = () => { matchSwitchPending.value = false; };
    /** 套用宣告式文件轉回表單時的條件。 */
    const applyMatchDraft = (spec, draftConditions) => {
        if (spec && typeof spec === 'object') {
            resetMatchState({ matchSpec: spec });
            conditions.value = [];
        } else {
            resetMatchState(null);
            conditions.value = draftConditions || [];
        }
    };
    // 編輯時以與儲存相同的規則即時檢查（不寫入資料），錯誤訊息帶 JSON 路徑
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
        } catch { /* 檢查失敗時不顯示錯誤，儲存時仍由後端把關 */ }
    };
    const validateMatchDebounced = debounce(validateMatchNow, 400);

    // --- Modal 最大化 ---
    const ruleModalMaximized = ref(false);
    const responseModalMaximized = ref(false);

    // --- SSE 事件 ---
    const sseEvents = ref([]);
    const addSseEvent = () => { sseEvents.value.push({ event: '', data: '', id: '', delayMs: 0, type: 'normal' }); };
    const removeSseEvent = (index) => { if (sseEvents.value.length > 1) { sseEvents.value.splice(index, 1); } };
    const buildSsePreview = (events, loopEnabled = false) => {
        if (!events || !events.length) { return ''; }
        const lines = [];
        let terminalEventReached = false;
        for (let i = 0; i < events.length; i++) {
            const evt = events[i];
            if (i > 0) { lines.push(''); }
            if (evt.delayMs > 0) { lines.push(t('ssePreview.delay', {ms: evt.delayMs})); lines.push(''); }
            const type = evt.type || 'normal';
            if (type === 'abort') {
                lines.push(t('ssePreview.abort'));
                terminalEventReached = true;
            }
            if (type === 'error') { lines.push(t('ssePreview.errorEvent')); }
            if (type !== 'abort') {
                const evtName = type === 'error' ? 'error' : (evt.event || 'message');
                lines.push('event: ' + evtName);
                const data = evt.data || '';
                data.split('\n').forEach(line => { lines.push('data: ' + line); });
                if (evt.id) { lines.push('id: ' + evt.id); }
                lines.push('');
            }
            if (type === 'error') { terminalEventReached = true; }
            if (terminalEventReached) {
                const remainingCount = events.length - i - 1;
                if (remainingCount > 0) { lines.push(t('ssePreview.remainingSkipped', {count: remainingCount})); }
                break;
            }
        }
        if (loopEnabled && !terminalEventReached) { lines.push(t('ssePreview.loopPlay')); }
        return lines.join('\n');
    };
    const ssePreview = computed(() => {
        return buildSsePreview(sseEvents.value, Boolean(form.value.sseLoopEnabled));
    });

    // --- SSE 回應預覽 ---
    const responseSsePreview = computed(() => {
        return buildSsePreview(responseSseEvents.value);
    });

    // --- 驗證 ---
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

    // --- Catch-all 偵測 ---
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
            if (!sseEvents.value.length || !sseEvents.value.some(e => e.data && e.data.trim())) return false;
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

    // --- 回應選擇器 ---
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

    // --- 回應預覽 ---
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

    // --- 表單輔助 ---
    const newTag = ref({ key: '', value: '' });
    const addTag = () => { if (!newTag.value.key) return; const tags = parseTags(form.value.tags); tags[newTag.value.key] = newTag.value.value; form.value.tags = JSON.stringify(tags); newTag.value = { key: '', value: '' } };
    const removeTag = k => { const tags = parseTags(form.value.tags); delete tags[k]; form.value.tags = Object.keys(tags).length ? JSON.stringify(tags) : '' };
    const newHeader = ref({ key: '', value: '' });
    const addHeader = () => { if (!newHeader.value.key) return; const h = parseHeaders(form.value.responseHeaders); h[newHeader.value.key] = newHeader.value.value; form.value.responseHeaders = JSON.stringify(h); newHeader.value = { key: '', value: '' } };
    const removeHeader = k => { const h = parseHeaders(form.value.responseHeaders); delete h[k]; form.value.responseHeaders = Object.keys(h).length ? JSON.stringify(h) : '' };

    // --- 測試區 ---
    const testExpanded = ref(false);
    const testParams = ref({ query: '', headersStr: '', body: '', timeout: 30 });
    const testResult = ref(null);
    const testLoading = ref(false);
    const testSseEvents = ref([]);
    const testSseAbort = ref(null);

    /** 依據目前條件自動產生測試資料 */
    const generateTestData = () => {
        const bodyConds = conditions.value.filter(c => c.type === 'body' && c.field && c.value);
        const queryConds = conditions.value.filter(c => c.type === 'query' && c.field && c.value);
        const headerConds = conditions.value.filter(c => c.type === 'header' && c.field && c.value);

        // 判斷 body 格式：條件欄位是 XPath（/ 開頭）時產生 XML
        const isXml = bodyConds.some(c => c.field.startsWith('/'));

        // 產生 Body
        if (bodyConds.length) {
            const resolveValue = (c) => {
                if (c.operator === '=' || c.operator === '*=') { return c.value; }
                if (c.operator === '!=') { return 'other'; }
                if (c.operator === '~=') { return c.value.replace(/[.*+?^${}()|[\]\\]/g, '') || 'test'; }
                return c.value;
            };

            if (isXml) {
                // 產生 XML
                const elements = {};
                for (const c of bodyConds) {
                    const val = resolveValue(c);
                    // 取最後一段作為元素名（//order/type → type, type → type）
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
                // 產生 JSON
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

        // 產生 Query
        if (queryConds.length) {
            testParams.value.query = queryConds
                .map(c => c.field + '=' + ((c.operator === '=' || c.operator === '*=') ? c.value : 'test'))
                .join('&');
        }

        // 產生 Headers
        if (headerConds.length) {
            testParams.value.headersStr = headerConds
                .map(c => c.field + ':' + ((c.operator === '=' || c.operator === '*=') ? c.value : 'test'))
                .join(', ');
        }

        // 展開測試區
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

    // --- 表單操作 ---
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

    /** 舊版條件字串轉成條件列，拆解規則與後端相同（見 MatchTree.parseLegacyCondition）；無法完整表示時不加入，由 resetMatchState 改用進階模式。 */
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
        // 一律讀取最新內容與版本：列表上的資料可能已過時，用舊內容存檔會蓋掉別人的修改
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
        // SSE 模式：序列化表格資料到 responseBody。使用現有回應時，事件與規則一起送出，
        // 由後端在同一個交易內改寫該回應（規則存檔失敗時回應也不會被改到）。
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
        // 使用現有 Response 時：正在編輯的回應內容與規則一起送出，在同一個交易內儲存
        const faulting = form.value.faultType && form.value.faultType !== 'NONE';
        const previewEdited = !faulting && form.value.action !== 'FORWARD' && form.value.responseMode === 'existing'
            && form.value.responseId && previewEditing.value && previewEditBody.value !== previewResponseBody.value;
        if (previewEdited && existingResponseBody === null) existingResponseBody = previewEditBody.value;
        const payload = { ...form.value };
        delete payload.advancedMatch;
        if (matchSpec) {
            payload.matchSpec = matchSpec;
        } else {
            // 規則原本使用進階條件、而現在沒有任何條件時要明確清除；未送 matchSpec 時後端會保留原本的進階條件
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
                // 有判斷順序的問題時只顯示一則提醒（已包含「已儲存」），否則顯示一般的成功訊息
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
                    // 後端會把只用到舊語法的進階條件轉回條件列，畫面跟著儲存結果
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
        // 編輯期間規則被別人改過：表單保持原樣，由使用者決定載入最新版本或保留自己的修改
        if (saveConflict) { return { conflict: saveConflict }; }
        return savedSuccessfully;
    };

    /**
     * 儲存後檢查判斷順序：這條規則永遠輪不到，或它讓其他規則永遠輪不到時，回傳要顯示的提醒（{title, detail, ruleId}）；
     * 沒有問題或檢查失敗時回傳 null（只是提醒，不影響儲存）。
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

    // --- 回應預覽編輯 ---
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
            const r = await apiCall(`/api/admin/responses/${rid}`, { method: 'PUT', body: JSON.stringify(payload) }, { errorMsg: t('toast.responseSaveFailed') });
            if (r && r.ok) {
                showToast(t('toast.responseSaveSuccess'), 'success');
                previewResponseBody.value = previewEditBody.value;
                previewEditing.value = false;
                responsesMarkDirty();
                rulePreviewCache.value = {};
                loadResponsePicker(responsePickerPage.value);
            }
        } catch { showToast(t('toast.responseSaveFailed'), 'error'); }
        previewSaving.value = false;
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

    // --- 回應選擇抽屜關閉 ---
    const closeResponseDropdown = e => {
        if (!responseDropdownOpen.value) return;
        if (!e.target.closest('.response-picker-drawer, .response-picker-change, .response-picker-empty-launch')) {
            responseDropdownOpen.value = false;
        }
    };

    return {
        // 表單狀態
        showModal, editing, form, conditions, conditionsExpanded, formErrors, saving,
        // 進階條件
        matchMode, matchTree, matchError, matchSwitchPending, matchSources,
        setMatchMode, confirmSimpleMatchMode, cancelSimpleMatchMode, currentMatchSpec, applyMatchDraft,
        httpTargetConnections, loadHttpTargetConnections,
        jmsTargetConnections, loadJmsTargetConnections,
        // 驗證
        canSave, validateForm,
        // Catch-all 偵測
        showCatchAllWarning, catchAllConfirmed,
        // Body condition 警告
        showBodyConditionWarning,
        // SSE 事件
        sseEvents, addSseEvent, removeSseEvent, ssePreview,
        // 表單操作
        setProtocol, resetForm, openCreate, copyRule, createFromLog, openEdit, closeModal,
        saveRule, parseConditions, onResponseModeChange,
        // 測試區
        testExpanded, testParams, testResult, testLoading,
        testSseEvents, testSseMode, runTest, stopSseTest, generateTestData,
        // 回應預覽
        previewResponseId, previewResponseBody, previewResponseLoading, previewResponseLoadFailed,
        previewEditing, previewEditBody, previewResponseUsageCount,
        previewSaving, togglePreviewEditing, savePreviewResponse,
        // 回應選擇器
        responsePickerSearch, responseDropdownOpen, filteredResponsePicker,
        responsePickerSseOnly, responsePickerSummary,
        responsePickerPage, responsePickerTotalElements, responsePickerTotalPages,
        responsePickerLoading, responsePickerError,
        openResponsePickerDrawer, submitResponsePickerSearch,
        setResponsePickerSseOnly, changeResponsePickerPage, selectResponseOption, clearResponseSelection,
        // 表單輔助
        newTag, addTag, removeTag, newHeader, addHeader, removeHeader,
        // SSE 回應預覽
        responseSsePreview,
        // Modal 最大化
        ruleModalMaximized, responseModalMaximized,
        // 格式切換

        // watchers
        setupFormWatchers,
        // 回應選擇抽屜關閉
        closeResponseDropdown
    };
};
