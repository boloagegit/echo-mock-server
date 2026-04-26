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
 * @param {import('vue').Ref} deps.rulePreviewExpanded - 規則預覽展開狀態（來自 useRules）
 * @param {Function} deps.rulesMarkDirty - 標記規則資料需重新載入（來自 useRules）
 * @param {Function} deps.loadResponseSummary - 載入回應摘要函式（來自 useResponses）
 * @param {import('vue').Ref} deps.responseSummary - 回應摘要列表（來自 useResponses）
 * @param {Function} deps.responsesMarkDirty - 標記回應資料需重新載入（來自 useResponses）
 * @param {import('vue').Ref} deps.responseSseEvents - 回應 SSE 事件（來自 useResponses）
 * @param {Function} deps.renderEditor - 渲染編輯器函式（來自 useEditor）
 * @param {Function} deps.editEditorRef - 編輯器 ref getter（來自 useEditor）
 * @param {import('vue').Ref} deps.editFormatted - 編輯器格式化狀態（來自 useEditor）
 * @param {Function} deps.previewEditorRef - 預覽編輯器 ref getter（來自 useEditor）
 * @param {import('vue').Ref} deps.previewFormatted - 預覽格式化狀態（來自 useEditor）
 * @param {Function} deps.responseFormEditorRef - 回應表單編輯器 ref getter（來自 useEditor）
 * @param {import('vue').Ref} deps.responseFormFormatted - 回應表單格式化狀態（來自 useEditor）
 * @param {Function} deps.detectMode - 偵測內容模式函式（來自 useEditor）
 * @param {Object} deps.editors - 編輯器物件（來自 useEditor）
 * @param {import('vue').Ref} deps.jmsEnabled - JMS 是否啟用（來自 app.js）
 * @returns {Object} 規則表單相關的狀態與方法
 */
const useRuleForm = (deps) => {
    const { ref, computed, watch } = Vue;
    const { showToast, showConfirm, t, requireLogin, login,
            loadRules, rulePreviewCache, rulePreviewExpanded, rulesMarkDirty,
            loadResponseSummary, responseSummary, responsesMarkDirty, responseSseEvents,
            renderEditor, editEditorRef, editFormatted, previewEditorRef, previewFormatted,
            responseFormEditorRef, responseFormFormatted } = deps;

    // --- 工具函式 ---
    const matchAll = (text, keywords) => keywords.every(kw => text.includes(kw));

    // --- 表單狀態 ---
    const showModal = ref(false);
    const editing = ref(null);
    const form = ref({ protocol: 'HTTP', targetHost: '', matchKey: '', method: 'GET', status: 200, delayMs: 0, maxDelayMs: null, priority: 0, responseBody: '', condition: '', description: '', enabled: true, isProtected: false, tags: '', responseMode: 'new', responseId: null, faultType: 'NONE', scenarioName: '', requiredScenarioState: '', newScenarioState: '' });
    const conditions = ref([]);
    const conditionsExpanded = ref(true);
    const formErrors = ref({});

    // --- Modal 最大化 ---
    const ruleModalMaximized = ref(false);
    const responseModalMaximized = ref(false);

    // --- SSE 事件 ---
    const sseEvents = ref([]);
    const addSseEvent = () => { sseEvents.value.push({ event: '', data: '', id: '', delayMs: 0, type: 'normal' }); };
    const removeSseEvent = (index) => { if (sseEvents.value.length > 1) { sseEvents.value.splice(index, 1); } };
    const ssePreview = computed(() => {
        const events = sseEvents.value;
        if (!events || !events.length) { return ''; }
        const lines = [];
        events.forEach((evt, i) => {
            if (i > 0) { lines.push(''); }
            if (evt.delayMs > 0) { lines.push(t('ssePreview.delay', {ms: evt.delayMs})); lines.push(''); }
            const type = evt.type || 'normal';
            if (type === 'abort') { lines.push(t('ssePreview.abort')); return; }
            if (type === 'error') { lines.push(t('ssePreview.errorEvent')); }
            const evtName = type === 'error' ? 'error' : (evt.event || 'message');
            lines.push('event: ' + evtName);
            const data = evt.data || '';
            data.split('\n').forEach(line => { lines.push('data: ' + line); });
            if (evt.id) { lines.push('id: ' + evt.id); }
            lines.push('');
        });
        if (form.value.sseLoopEnabled) { lines.push(t('ssePreview.loopPlay')); }
        return lines.join('\n');
    });

    // --- SSE 回應預覽 ---
    const responseSsePreview = computed(() => {
        const events = responseSseEvents.value;
        if (!events || !events.length) { return ''; }
        const lines = [];
        events.forEach((evt, i) => {
            if (i > 0) { lines.push(''); }
            if (evt.delayMs > 0) { lines.push(t('ssePreview.delay', {ms: evt.delayMs})); lines.push(''); }
            const type = evt.type || 'normal';
            if (type === 'abort') { lines.push(t('ssePreview.abort')); return; }
            if (type === 'error') { lines.push(t('ssePreview.errorEvent')); }
            const evtName = type === 'error' ? 'error' : (evt.event || 'message');
            lines.push('event: ' + evtName);
            const data = evt.data || '';
            data.split('\n').forEach(line => { lines.push('data: ' + line); });
            if (evt.id) { lines.push('id: ' + evt.id); }
            lines.push('');
        });
        return lines.join('\n');
    });

    // --- 驗證 ---
    const validateForm = () => {
        const errors = {};
        if (form.value.protocol === 'HTTP') {
            if (!form.value.matchKey) errors.matchKey = t('validation.matchKeyRequired');
            else if (!form.value.matchKey.startsWith('/') && form.value.matchKey !== '*') errors.matchKey = t('validation.matchKeyFormat');
            if (!form.value.method) errors.method = t('validation.methodRequired');
            if (!form.value.status) errors.status = t('validation.statusRequired');
            else if (form.value.status < 100 || form.value.status > 599) errors.status = t('validation.statusRange');
        } else if (form.value.protocol === 'JMS') {
            if (!form.value.matchKey) errors.matchKey = t('validation.queueNameRequired');
        }
        if (form.value.responseMode === 'new') {
            if (!form.value.responseBody) errors.responseBody = t('validation.responseBodyRequired');
            else if (form.value.sseEnabled) {
                try {
                    const arr = JSON.parse(form.value.responseBody);
                    if (!Array.isArray(arr)) { errors.responseBody = t('validation.sseArrayFormat'); }
                    else if (arr.length === 0) { errors.responseBody = t('validation.sseArrayEmpty'); }
                } catch (e) { errors.responseBody = t('validation.sseJsonError', {message: e.message}); }
            }
        } else if (form.value.responseMode === 'existing') {
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
            return !conditions.value.some(c => c.field && c.value);
        }
        return false;
    });

    const NO_BODY_METHODS = ['GET', 'HEAD', 'OPTIONS'];
    const showBodyConditionWarning = computed(() => {
        if (form.value.protocol !== 'HTTP') { return false; }
        if (!NO_BODY_METHODS.includes(form.value.method)) { return false; }
        return conditions.value.some(c => c.type === 'body' && c.field && c.value);
    });

    const canSave = computed(() => {
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
    const responseDropdownOpen = ref(false);
    const responsePickerSseOnly = ref(false);
    const filteredResponsePicker = computed(() => {
        let list = responseSummary.value;
        if (responsePickerSseOnly.value) { list = list.filter(r => r.contentType === 'SSE'); }
        if (responsePickerSearch.value) {
            const keywords = responsePickerSearch.value.toLowerCase().split(/\s+/).filter(k => k);
            list = list.filter(r => matchAll(String(r.id) + ' ' + (r.description || '').toLowerCase(), keywords));
        }
        return [...list].sort((a, b) => {
            const ta = a.updatedAt || a.createdAt || '';
            const tb = b.updatedAt || b.createdAt || '';
            return tb.localeCompare(ta);
        });
    });

    // --- 回應預覽 ---
    const previewResponseId = ref(null);
    const previewResponseBody = ref('');
    const previewEditing = ref(false);
    const previewEditBody = ref('');
    const previewResponseUsageCount = ref(0);
    const previewSaving = ref(false);

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

        // 判斷 body 格式：從原始 bodyCondition 偵測是否為 XPath (// 或 / 開頭)
        const rawBodyCond = editing.value?.bodyCondition || '';
        const isXml = rawBodyCond.startsWith('//') || rawBodyCond.startsWith('/');

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
                    const parts = c.field.split('.');
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
                    const [k, v] = h.split(':').map(s => s.trim());
                    if (k && v) { hdrs[k] = v; }
                });
            }
            const ac = new AbortController();
            testSseAbort.value = ac;
            const startTime = Date.now();
            try {
                const r = await fetch(url, { method: 'GET', headers: hdrs, signal: ac.signal });
                if (!r.ok || !r.body) {
                    const body = await r.text();
                    testResult.value = { status: r.status, body: body || 'Request failed', elapsed: Date.now() - startTime };
                    testLoading.value = false;
                    return;
                }
                const reader = r.body.getReader();
                const decoder = new TextDecoder();
                let buf = '';
                let curEvent = { event: '', data: '', id: '' };
                const pushEvent = () => {
                    if (curEvent.data || curEvent.event || curEvent.id) {
                        testSseEvents.value.push({ ...curEvent, time: Date.now() - startTime });
                    }
                    curEvent = { event: '', data: '', id: '' };
                };
                const readLoop = async () => {
                    while (true) {
                        const { done, value } = await reader.read();
                        if (done) { pushEvent(); break; }
                        buf += decoder.decode(value, { stream: true });
                        const lines = buf.split('\n');
                        buf = lines.pop();
                        for (const line of lines) {
                            if (line === '') { pushEvent(); continue; }
                            if (line.startsWith(':')) { continue; }
                            const idx = line.indexOf(':');
                            let field, val;
                            if (idx === -1) { field = line; val = ''; }
                            else { field = line.substring(0, idx); val = line.substring(idx + 1).replace(/^ /, ''); }
                            if (field === 'data') { curEvent.data = curEvent.data ? curEvent.data + '\n' + val : val; }
                            else if (field === 'event') { curEvent.event = val; }
                            else if (field === 'id') { curEvent.id = val; }
                        }
                    }
                };
                await readLoop();
            } catch (e) {
                if (e.name !== 'AbortError') {
                    testResult.value = { status: 0, body: e.message, elapsed: Date.now() - startTime };
                }
            }
            testSseAbort.value = null;
            testLoading.value = false;
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
    const setProtocol = p => { form.value.protocol = p; form.value.matchKey = p === 'JMS' ? '*' : ''; if (p === 'JMS') { form.value.sseEnabled = false; } conditions.value = []; localStorage.setItem('lastProtocol', p) };

    const resetForm = () => ({ protocol: localStorage.getItem('lastProtocol') || 'HTTP', targetHost: '', matchKey: '', method: 'GET', status: 200, delayMs: 0, maxDelayMs: null, priority: 0, responseBody: '', responseDescription: '', responseHeaders: '', bodyCondition: '', queryCondition: '', headerCondition: '', description: '', enabled: true, isProtected: false, tags: '', responseMode: 'new', responseId: null, sseEnabled: false, sseLoopEnabled: false, faultType: 'NONE', scenarioName: '', requiredScenarioState: '', newScenarioState: '' });

    const onResponseModeChange = () => {
        if (form.value.responseMode === 'existing') {
            form.value.responseBody = ''; form.value.status = 200; form.value.delayMs = 0;
            responsePickerSearch.value = ''; loadResponseSummary();
        } else {
            form.value.responseId = null;
        }
    };

    const parseConditions = r => {
        const parseWithOp = (str) => {
            for (const op of ['!=', '*=', '~=', '=']) {
                const idx = str.indexOf(op);
                if (idx > 0) {
                    return { field: str.substring(0, idx).trim(), operator: op, value: str.substring(idx + op.length).trim() };
                }
            }
            return null;
        };
        if (r.bodyCondition) r.bodyCondition.split(';').forEach(c => {
            const parsed = parseWithOp(c);
            if (parsed) {
                let f = parsed.field;
                if (f.startsWith('//')) f = f.substring(2);
                conditions.value.push({ type: 'body', field: f, operator: parsed.operator, value: parsed.value });
            }
        });
        if (r.queryCondition) r.queryCondition.split(';').forEach(c => {
            const parsed = parseWithOp(c);
            if (parsed) conditions.value.push({ type: 'query', field: parsed.field, operator: parsed.operator, value: parsed.value });
        });
        if (r.headerCondition) r.headerCondition.split(';').forEach(c => {
            const parsed = parseWithOp(c);
            if (parsed) conditions.value.push({ type: 'header', field: parsed.field, operator: parsed.operator, value: parsed.value });
        });
    };

    const openCreate = async () => {
        if (!await requireLogin()) return;
        editing.value = null;
        form.value = resetForm();
        form.value.matchKey = form.value.protocol === 'JMS' ? '*' : '';
        conditions.value = [];
        sseEvents.value = [{ event: '', data: '', id: '', delayMs: 0, type: 'normal' }];
        formErrors.value = {};
        catchAllConfirmed.value = false;
        showCatchAllWarning.value = false;
        loadResponseSummary();
        showModal.value = true;
    };

    const copyRule = async r => {
        if (!await requireLogin()) return;
        editing.value = null;
        form.value = { ...r, id: undefined, description: (r.description || '') + t('common.copySuffix'), responseMode: 'new', responseId: null };
        conditions.value = [];
        catchAllConfirmed.value = false;
        showCatchAllWarning.value = false;
        sseEvents.value = (r.sseEnabled && r.responseBody) ? deserializeSseEvents(r.responseBody) : [{ event: '', data: '', id: '', delayMs: 0, type: 'normal' }];
        parseConditions(r);
        loadResponseSummary();
        showModal.value = true;
    };

    const createFromLog = async (ruleDto) => {
        if (!await requireLogin()) return;
        editing.value = null;
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
        sseEvents.value = [{ event: '', data: '', id: '', delayMs: 0, type: 'normal' }];
        formErrors.value = {};
        catchAllConfirmed.value = false;
        showCatchAllWarning.value = false;
        loadResponseSummary();
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
        previewEditing.value = false;
        previewEditBody.value = '';
        previewResponseBody.value = '';
        if (!r.responseId) {
            const res = await apiCall(`/api/admin/rules/${r.id}`, {}, { silent: true });
            if (res && res.ok) { const full = await res.json(); r = full; }
        }
        form.value = { ...r, responseMode: r.responseId ? 'existing' : 'new' };
        conditions.value = [];
        sseEvents.value = (r.sseEnabled && r.responseBody) ? deserializeSseEvents(r.responseBody) : [{ event: '', data: '', id: '', delayMs: 0, type: 'normal' }];
        parseConditions(r);
        loadResponseSummary();
        showModal.value = true;
        if (r.responseId) {
            const res = await apiCall(`/api/admin/responses/${r.responseId}`, {}, { silent: true });
            if (res && res.ok) {
                const d = await res.json();
                previewResponseBody.value = d.body;
                if (form.value.sseEnabled && d.body) {
                    sseEvents.value = deserializeSseEvents(d.body);
                }
            }
        }
    };

    const closeModal = () => {
        showModal.value = false;
        editing.value = null;
        form.value = resetForm();
        conditions.value = [];
        sseEvents.value = [];
        formErrors.value = {};
        catchAllConfirmed.value = false;
        showCatchAllWarning.value = false;
        testExpanded.value = false;
        testResult.value = null;
        testSseEvents.value = [];
        testSseMode.value = false;
        stopSseTest();
        testParams.value = { query: '', headersStr: '', body: '', timeout: 30 };
        previewResponseId.value = null;
        previewEditing.value = false;
        previewEditBody.value = '';
        previewResponseUsageCount.value = 0;
        conditionsExpanded.value = true;
    };

    const saving = ref(false);

    const saveRule = async (andClose = false) => {
        // SSE 模式：序列化表格資料到 responseBody
        if (form.value.sseEnabled && form.value.protocol === 'HTTP') {
            const serialized = serializeSseEvents(sseEvents.value);
            if (form.value.responseMode === 'existing' && form.value.responseId) {
                const rr = await apiCall(`/api/admin/responses/${form.value.responseId}`, { method: 'PUT', body: JSON.stringify({ body: serialized }) }, { errorMsg: t('toast.responseSaveFailed') });
                if (!rr || !rr.ok) { showToast(t('toast.sseSaveFailed'), 'error'); return; }
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
        saving.value = true;
        const bodyConds = conditions.value.filter(c => c.type === 'body' && c.field && c.value).map(c => c.field + (c.operator || '=') + c.value).join(';');
        const queryConds = conditions.value.filter(c => c.type === 'query' && c.field && c.value).map(c => c.field + (c.operator || '=') + c.value).join(';');
        const headerConds = conditions.value.filter(c => c.type === 'header' && c.field && c.value).map(c => c.field + (c.operator || '=') + c.value).join(';');
        form.value.bodyCondition = bodyConds || null;
        form.value.queryCondition = queryConds || null;
        form.value.headerCondition = headerConds || null;
        // 使用現有 Response 時：若正在編輯回應內容，先自動儲存
        if (form.value.responseMode === 'existing' && form.value.responseId && previewEditing.value && previewEditBody.value !== previewResponseBody.value) {
            const resp = responseSummary.value.find(r => r.id === form.value.responseId);
            const rr = await apiCall(`/api/admin/responses/${form.value.responseId}`, { method: 'PUT', body: JSON.stringify({ description: resp?.description || '', body: previewEditBody.value }) }, { errorMsg: t('toast.responseSaveFailed') });
            if (!rr || !rr.ok) { showToast(t('toast.responseSaveFailed'), 'error'); return; }
            previewResponseBody.value = previewEditBody.value;
            previewEditing.value = false;
            renderEditor('preview', previewEditorRef, previewResponseBody.value, true);
            responsesMarkDirty();
        }
        const payload = { ...form.value };
        if (payload.responseMode === 'existing') { payload.responseBody = null; }
        else { payload.responseId = null; }
        delete payload.responseMode;
        try {
            const url = editing.value ? `/api/admin/rules/${editing.value.id}` : '/api/admin/rules';
            const r = await apiCall(url, { method: editing.value ? 'PUT' : 'POST', body: JSON.stringify(payload) }, { errorMsg: t('toast.ruleSaveFailed') });
            if (r && r.ok) {
                const saved = await r.json();
                showToast(editing.value ? t('toast.ruleSaveSuccess') : t('toast.ruleCreateSuccess'), 'success');
                rulesMarkDirty();
                delete rulePreviewCache.value[saved.id];
                rulePreviewExpanded.value[saved.id] = false;
                if (andClose) { closeModal(); loadRules(true); }
                else {
                    editing.value = saved;
                    form.value.id = saved.id;
                    form.value.version = saved.version;
                    form.value.responseId = saved.responseId;
                    form.value.createdAt = saved.createdAt;
                    form.value.updatedAt = saved.updatedAt;
                    loadRules(true);
                }
            }
            else if (r && (r.status === 401 || r.status === 403)) { login(); }
        } catch (e) { showToast(t('toast.ruleSaveFailed'), 'error'); }
        saving.value = false;
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
            previewFormatted.value = false;
            renderEditor('preview', previewEditorRef, previewEditBody.value, false, v => { previewEditBody.value = v; });
        } else {
            previewEditing.value = false;
            previewFormatted.value = false;
            renderEditor('preview', previewEditorRef, previewResponseBody.value, true);
        }
    };

    const savePreviewResponse = async () => {
        const rid = form.value.responseId;
        if (!rid) return;
        previewSaving.value = true;
        try {
            const resp = responseSummary.value.find(r => r.id === rid);
            const payload = { description: resp?.description || '', body: previewEditBody.value };
            const r = await apiCall(`/api/admin/responses/${rid}`, { method: 'PUT', body: JSON.stringify(payload) }, { errorMsg: t('toast.responseSaveFailed') });
            if (r && r.ok) {
                showToast(t('toast.responseSaveSuccess'), 'success');
                previewResponseBody.value = previewEditBody.value;
                previewEditing.value = false;
                previewFormatted.value = false;
                renderEditor('preview', previewEditorRef, previewResponseBody.value, true);
                responsesMarkDirty();
                rulePreviewCache.value = {};
                loadResponseSummary(true);
            }
        } catch { showToast(t('toast.responseSaveFailed'), 'error'); }
        previewSaving.value = false;
    };

    const togglePreviewFormat = () => { previewFormatted.value = !previewFormatted.value; renderEditor('preview', previewEditorRef, previewEditing.value ? previewEditBody.value : previewResponseBody.value, !previewEditing.value, previewEditing.value ? v => { previewEditBody.value = v; } : undefined); };
    const toggleEditFormat = () => { editFormatted.value = !editFormatted.value; renderEditor('edit', editEditorRef, form.value.responseBody, false, v => { form.value.responseBody = v; }); };

    // --- Watchers ---
    const setupFormWatchers = () => {
        watch(isCatchAll, val => {
            if (!val) { showCatchAllWarning.value = false; catchAllConfirmed.value = false; }
        });

        watch(showModal, open => {
            if (open) {
                Vue.nextTick(() => {
                    if (form.value.responseMode === 'new' && !form.value.sseEnabled) {
                        renderEditor('edit', editEditorRef, form.value.responseBody, false, v => { form.value.responseBody = v; });
                    } else if (form.value.responseMode === 'existing' && previewResponseBody.value) {
                        renderEditor('preview', previewEditorRef, previewResponseBody.value, !previewEditing.value);
                    }
                });
            }
        });

        watch(() => form.value.responseId, async (id, oldId) => {
            previewFormatted.value = false;
            previewEditing.value = false;
            previewEditBody.value = '';
            if (id && id !== oldId) {
                const r = await apiCall(`/api/admin/responses/${id}`, {}, { silent: true });
                if (r && r.ok) {
                    const d = await r.json();
                    previewResponseBody.value = d.body;
                    if (form.value.sseEnabled && d.body) {
                        sseEvents.value = deserializeSseEvents(d.body);
                    }
                }
            } else if (!id) {
                previewResponseBody.value = '';
            }
        });

        watch(previewResponseBody, body => {
            if (body && !previewFormatted.value && !previewEditing.value) {
                Vue.nextTick(() => renderEditor('preview', previewEditorRef, body, true));
            }
        });

        watch(() => form.value.responseMode, mode => {
            editFormatted.value = false;
            previewFormatted.value = false;
            previewEditing.value = false;
            previewEditBody.value = '';
            if (mode === 'new') {
                if (!form.value.sseEnabled) {
                    renderEditor('edit', editEditorRef, form.value.responseBody, false, v => { form.value.responseBody = v; });
                }
            }
            else if (mode === 'existing' && previewResponseBody.value) renderEditor('preview', previewEditorRef, previewResponseBody.value, true);
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
                if (form.value.responseMode === 'new') {
                    Vue.nextTick(() => renderEditor('edit', editEditorRef, form.value.responseBody, false, v => { form.value.responseBody = v; }));
                } else if (form.value.responseMode === 'existing' && previewResponseBody.value) {
                    Vue.nextTick(() => renderEditor('preview', previewEditorRef, previewResponseBody.value, !previewEditing.value));
                }
            }
        });

        watch(testExpanded, v => { if (v) setTimeout(() => document.getElementById('testSection')?.scrollIntoView({behavior:'smooth',block:'end'}), 50); });

        watch(previewEditing, editing => {
            // (handled by togglePreviewEditing)
        });
    };

    // --- 回應 dropdown 關閉 ---
    const closeResponseDropdown = e => { if (!e.target.closest('.response-select-wrapper')) responseDropdownOpen.value = false; };

    return {
        // 表單狀態
        showModal, editing, form, conditions, conditionsExpanded, formErrors, saving,
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
        previewResponseId, previewResponseBody,
        previewEditing, previewEditBody, previewResponseUsageCount,
        previewSaving, togglePreviewEditing, savePreviewResponse,
        // 回應選擇器
        responsePickerSearch, responseDropdownOpen, filteredResponsePicker,
        responsePickerSseOnly,
        // 表單輔助
        newTag, addTag, removeTag, newHeader, addHeader, removeHeader,
        // SSE 回應預覽
        responseSsePreview,
        // Modal 最大化
        ruleModalMaximized, responseModalMaximized,
        // 格式切換
        togglePreviewFormat, toggleEditFormat,
        // watchers
        setupFormWatchers,
        // 回應 dropdown 關閉
        closeResponseDropdown
    };
};
