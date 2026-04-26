/**
 * RuleEditModal - 規則建立/編輯 Modal
 * 最大的元件，包含協定切換、條件匹配、回應編輯、SSE 編輯器、測試區等功能。
 * CodeMirror 編輯器使用 DOM id-based getter pattern 讓父層 renderEditor() 仍可運作。
 */
const RuleEditModal = {
    props: {
        show: Boolean,
        editing: Object,
        form: Object,
        conditions: Array,
        formErrors: Object,
        canSave: Boolean,
        saving: Boolean,
        maximized: Boolean,
        showCatchAllWarning: Boolean,
        catchAllConfirmed: Boolean,
        showBodyConditionWarning: Boolean,
        jmsEnabled: Boolean,
        httpLabel: String,
        jmsLabel: String,
        responseSummary: Array,
        filteredResponsePicker: Array,
        responsePickerSearch: String,
        responseDropdownOpen: Boolean,
        responsePickerSseOnly: Boolean,
        sseEvents: Array,
        ssePreview: String,
        testExpanded: Boolean,
        testParams: Object,
        testResult: Object,
        testLoading: Boolean,
        testSseEvents: Array,
        testSseMode: Boolean,
        previewResponseBody: String,
        previewEditing: Boolean,
        previewEditBody: String,
        previewResponseUsageCount: Number,
        previewSaving: Boolean,
        previewFormatted: Boolean,
        editFormatted: Boolean,
        newTag: Object,
        newHeader: Object,
    },
    emits: [
        'close', 'save', 'update:maximized', 'update:catch-all-confirmed',
        'set-protocol', 'on-response-mode-change',
        'add-condition', 'remove-condition',
        'add-tag', 'remove-tag', 'add-header', 'remove-header',
        'update:test-expanded', 'run-test', 'stop-sse-test', 'generate-test-data',
        'add-sse-event', 'remove-sse-event',
        'update:response-picker-search', 'update:response-dropdown-open',
        'update:response-picker-sse-only',
        'toggle-preview-editing', 'save-preview-response', 'toggle-preview-format',
        'toggle-edit-format',
        'go-to-responses',
        'clear-response-selection',
        'reorder-sse-events',
        'apply-template',
    ],
    inject: ['t'],
    setup(props, { emit }) {
        const { ref } = Vue;
        // 已選擇的回應 computed（避免 template 中重複 find）
        const selectedResponse = Vue.computed(() =>
            props.responseSummary.find(r => r.id === props.form.responseId)
        );
        // Splitter drag logic (local to component)
        const savedSplitRatio = parseFloat(localStorage.getItem('echo_modal_split_ratio'));
        const splitRatio = ref(savedSplitRatio > 0 && savedSplitRatio < 1 ? savedSplitRatio : 0.35);
        const splitterDragging = ref(false);
        const applySplitRatio = () => {
            const editor = document.querySelector('.rule-editor');
            if (!editor) { return; }
            const left = editor.querySelector('.rule-left');
            if (!left) { return; }
            const w = editor.clientWidth - 6;
            left.style.width = Math.round(w * splitRatio.value) + 'px';
        };
        const startSplitterDrag = (e) => {
            e.preventDefault();
            splitterDragging.value = true;
            document.body.classList.add('splitter-dragging');
            const editor = document.querySelector('.rule-editor');
            if (!editor) { return; }
            const rect = editor.getBoundingClientRect();
            const totalW = rect.width - 6;
            const onMove = (ev) => {
                const x = ev.clientX - rect.left - 3;
                const ratio = Math.max(0.15, Math.min(0.65, x / totalW));
                splitRatio.value = ratio;
                const left = editor.querySelector('.rule-left');
                if (left) { left.style.width = Math.round(totalW * ratio) + 'px'; }
            };
            const onUp = () => {
                splitterDragging.value = false;
                document.body.classList.remove('splitter-dragging');
                localStorage.setItem('echo_modal_split_ratio', splitRatio.value.toFixed(4));
                document.removeEventListener('mousemove', onMove);
                document.removeEventListener('mouseup', onUp);
            };
            document.addEventListener('mousemove', onMove);
            document.addEventListener('mouseup', onUp);
        };
        // SSE drag logic (local to component)
        const sseDragIndex = ref(null);
        const sseRowClass = (evt) => {
            if (evt.type === 'error') { return 'sse-row-error'; }
            if (evt.type === 'abort') { return 'sse-row-abort'; }
            return '';
        };
        const onSseDragStart = (e, index) => { sseDragIndex.value = index; e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', String(index)); };
        const onSseDragOver = (e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; };
        const onSseDrop = (e, index) => {
            e.preventDefault();
            if (sseDragIndex.value !== null && sseDragIndex.value !== index) {
                const arr = [...props.sseEvents];
                const item = arr.splice(sseDragIndex.value, 1)[0];
                arr.splice(index, 0, item);
                emit('reorder-sse-events', arr);
            }
            sseDragIndex.value = null;
        };
        const onSseDragEnd = () => { sseDragIndex.value = null; };
        // Apply split ratio when modal opens
        Vue.watch(() => props.show, (v) => { if (v) { Vue.nextTick(applySplitRatio); } });
        return {
            selectedResponse,
            splitterDragging, startSplitterDrag,
            sseDragIndex, sseRowClass, onSseDragStart, onSseDragOver, onSseDrop, onSseDragEnd,
            parseTags, parseHeaders, fmtTime, fmtSize,
        };
    },
    template: /* html */`
    <div class="modal-overlay" v-if="show" :style="maximized?'padding:0':''">
        <div class="modal-box rule-modal-fullscreen" :class="{maximized:maximized}">
            <div class="modal-header">
                <h3><i class="bi" :class="editing?'bi-pencil-square':'bi-plus-circle'"></i> {{editing ? t('modal.editRule') : t('modal.addRule')}}</h3>
                <div style="display:flex;align-items:center;gap:0.25rem">
                    <button class="close-btn" @click="$emit('update:maximized',!maximized)" :title="maximized ? t('modal.restoreWindow') : t('modal.fullscreen')" :aria-label="maximized ? t('modal.restoreWindow') : t('modal.fullscreen')"><i class="bi" :class="maximized?'bi-fullscreen-exit':'bi-arrows-fullscreen'"></i></button>
                    <button class="close-btn" @click="$emit('close')" aria-label="Close"><i class="bi bi-x-lg"></i></button>
                </div>
            </div>
            <div class="modal-body rule-editor">
                <!-- 左側：匹配條件 + 測試 -->
                <div class="rule-left">
                    <!-- 協定切換 + 狀態開關 -->
                    <div class="form-block">
                        <div class="protocol-switch" style="margin-bottom:0.5rem">
                            <button type="button" class="protocol-btn" :class="{active:form.protocol==='HTTP'}" @click="$emit('set-protocol','HTTP')">
                                <i class="bi bi-globe"></i> {{httpLabel}}
                            </button>
                            <button type="button" class="protocol-btn jms" :class="{active:form.protocol==='JMS'}" :disabled="!jmsEnabled" @click="jmsEnabled&&$emit('set-protocol','JMS')">
                                <i class="bi bi-envelope"></i> {{jmsLabel}}
                            </button>
                        </div>
                        <div style="display:flex;gap:0.4rem;align-items:center;flex-wrap:wrap">
                            <div class="meta-field status" :class="{enabled:form.enabled, disabled:!form.enabled}" @click="form.enabled=!form.enabled">
                                <i class="bi" :class="form.enabled?'bi-check-circle-fill':'bi-x-circle'"></i>
                                <span>{{form.enabled ? t('modal.formEnabled') : t('modal.formDisabled')}}</span>
                            </div>
                            <div class="meta-field status" :class="{enabled:form.isProtected, disabled:!form.isProtected}" @click="form.isProtected=!form.isProtected" :title="t('modal.protectedTooltip')">
                                <i class="bi" :class="form.isProtected?'bi-shield-fill-check':'bi-shield'"></i>
                                <span>{{form.isProtected ? t('modal.formProtected') : t('modal.formUnprotected')}}</span>
                            </div>
                            <div v-if="form.protocol==='HTTP'" class="meta-field status" :class="{enabled:form.sseEnabled, disabled:!form.sseEnabled}" @click="form.sseEnabled=!form.sseEnabled">
                                <i class="bi bi-broadcast"></i>
                                <span>SSE</span>
                            </div>
                        </div>
                    </div>
                    <!-- 匹配路徑 -->
                    <div class="form-block">
                        <div class="form-block-header"><i class="bi bi-signpost-2"></i> {{form.protocol==='HTTP' ? t('modal.matchPath') : 'Queue'}}</div>
                        <template v-if="form.protocol==='HTTP'">
                            <div class="form-group" style="margin-bottom:0.5rem">
                                <label class="form-label">{{t('modal.method')}} <span class="required">*</span></label>
                                <div class="method-group">
                                    <button v-for="m in ['GET','POST','PUT','DELETE','PATCH']" :key="m" type="button" class="method-btn" :class="[m, {active: form.method===m}]" @click="form.method=m">{{m}}</button>
                                </div>
                                <div v-if="formErrors.method" class="invalid-feedback" style="display:block">{{formErrors.method}}</div>
                            </div>
                            <div class="form-group" style="margin-bottom:0.5rem">
                                <label class="form-label">{{t('modal.pathLabel')}} <span class="required">*</span></label>
                                <input class="form-control" v-model="form.matchKey" :class="{'is-invalid':formErrors.matchKey}" placeholder="/api/users/{id}">
                                <div v-if="formErrors.matchKey" class="invalid-feedback" style="display:block">{{formErrors.matchKey}}</div>
                            </div>
                            <div class="form-group" style="margin-bottom:0">
                                <label class="form-label">{{t('modal.targetHost')}}</label>
                                <input class="form-control" v-model="form.targetHost" placeholder="api.example.com">
                            </div>
                        </template>
                        <template v-else>
                            <div class="form-group" style="margin-bottom:0.5rem">
                                <label class="form-label">Queue <span class="required">*</span></label>
                                <input class="form-control" v-model="form.matchKey" :class="{'is-invalid':formErrors.matchKey}" placeholder="QUEUE.NAME">
                                <div v-if="formErrors.matchKey" class="invalid-feedback" style="display:block">{{formErrors.matchKey}}</div>
                            </div>
                            <div class="form-group" style="margin-bottom:0">
                                <label class="form-label">{{t('modal.replyQueue')}}</label>
                                <input class="form-control" v-model="form.replyQueue" placeholder="REPLY.QUEUE">
                            </div>
                        </template>
                    </div>
                    <!-- 條件匹配 -->
                    <div class="form-block">
                        <div class="form-block-header">
                            <i class="bi bi-funnel"></i> {{t('modal.conditionMatch')}}
                            <span v-if="conditions.length" class="badge badge-muted ms-auto">{{conditions.length}}</span>
                        </div>
                        <div class="cond-builder">
                            <div v-for="(c,i) in conditions" :key="i" class="cond-row">
                                <select v-if="form.protocol==='HTTP'" class="form-control cond-type" v-model="c.type">
                                    <option value="body">Body</option>
                                    <option value="query">Query</option>
                                    <option value="header">Header</option>
                                </select>
                                <input class="form-control" v-model="c.field" :placeholder="c.type==='query' ? t('modal.condPlaceholderParam') : c.type==='header' ? t('modal.condPlaceholderHeader') : t('modal.condPlaceholderField')">
                                <select class="form-control cond-op" v-model="c.operator">
                                    <option value="=">=</option>
                                    <option value="!=">!=</option>
                                    <option value="*=">*=</option>
                                    <option value="~=">~=</option>
                                </select>
                                <input class="form-control" v-model="c.value" :placeholder="t('modal.condPlaceholderValue')">
                                <button type="button" class="cond-remove" @click="$emit('remove-condition',i)"><i class="bi bi-x"></i></button>
                            </div>
                            <button type="button" class="cond-add" @click="$emit('add-condition')"><i class="bi bi-plus"></i> {{t('modal.addCondition')}}</button>
                            <div v-if="showBodyConditionWarning" class="cond-warning"><i class="bi bi-info-circle"></i> {{t('modal.bodyConditionWarning', {method: form.method})}}</div>
                        </div>
                    </div>
                    <!-- 回應設定 -->
                    <div class="form-block">
                        <div class="form-block-header"><i class="bi bi-sliders"></i> {{t('modal.responseSettings')}}</div>
                        <div style="display:grid;grid-template-columns:1fr 1fr;gap:0.5rem">
                            <div class="form-group" style="margin-bottom:0" :title="t('modal.priorityTooltip')">
                                <label class="form-label">{{t('modal.priority')}}</label>
                                <input type="number" class="form-control" v-model.number="form.priority" min="0" placeholder="0">
                            </div>
                            <div v-if="form.protocol==='HTTP'" class="form-group" style="margin-bottom:0">
                                <label class="form-label">{{t('modal.statusCode')}}</label>
                                <input type="number" class="form-control" v-model.number="form.status" min="100" max="599" :class="{'is-invalid':formErrors.status}">
                            </div>
                            <div class="form-group" style="margin-bottom:0">
                                <label class="form-label"><i class="bi bi-lightning"></i> {{t('modal.faultType')}}</label>
                                <select class="form-control" v-model="form.faultType">
                                    <option value="NONE">{{t('modal.faultNone')}}</option>
                                    <option value="CONNECTION_RESET">{{t('modal.faultConnectionReset')}}</option>
                                    <option value="EMPTY_RESPONSE">{{t('modal.faultEmptyResponse')}}</option>
                                </select>
                            </div>
                        </div>
                        <div style="display:grid;grid-template-columns:1fr 1fr auto;gap:0.5rem;margin-top:0.5rem;align-items:end">
                            <div class="form-group" style="margin-bottom:0">
                                <label class="form-label">{{t('modal.delay')}} (ms)</label>
                                <input type="number" class="form-control" v-model.number="form.delayMs" min="0" placeholder="0" :class="{'is-invalid':formErrors.delayMs}">
                            </div>
                            <div class="form-group" style="margin-bottom:0">
                                <label class="form-label">Max <span class="hint">{{t('modal.delayFixedHint')}}</span></label>
                                <input type="number" class="form-control" v-model.number="form.maxDelayMs" min="0" :placeholder="t('modal.delayFixedHint')" :class="{'is-invalid':formErrors.maxDelayMs}">
                            </div>
                            <span class="param-unit" style="padding-bottom:0.5rem">ms</span>
                        </div>
                        <div v-if="form.responseMode==='new'" class="form-group" style="margin-top:0.5rem;margin-bottom:0">
                            <label class="form-label">{{t('modal.responseDescription')}}</label>
                            <input class="form-control" v-model="form.responseDescription" :placeholder="t('modal.responseDescription')">
                        </div>
                        <div v-if="form.protocol==='HTTP'" style="margin-top:0.5rem">
                            <label class="form-label">{{t('modal.responseHeaders')}}</label>
                            <div class="meta-tags">
                                <span v-for="(v,k) in parseHeaders(form.responseHeaders)" :key="k" class="tag-chip">
                                    <span class="tag-key">{{k}}</span><span class="tag-val">{{v}}</span>
                                    <i class="bi bi-x" @click="$emit('remove-header',k)"></i>
                                </span>
                                <div class="tag-add-inline">
                                    <input v-model="newHeader.key" placeholder="Header" @keyup.enter="$emit('add-header')" style="width:90px">
                                    <span class="tag-sep">:</span>
                                    <input v-model="newHeader.value" :placeholder="t('modal.condPlaceholderValue')" @keyup.enter="$emit('add-header')" style="width:90px">
                                    <button type="button" @click="$emit('add-header')"><i class="bi bi-plus"></i></button>
                                </div>
                            </div>
                        </div>
                    </div>
                    <!-- Scenario 狀態機 -->
                    <div class="form-block">
                        <div class="form-block-header"><i class="bi bi-diagram-3"></i> {{t('modal.scenario')}}</div>
                        <div class="form-group" style="margin-bottom:0.5rem">
                            <label class="form-label">{{t('modal.scenarioName')}}</label>
                            <input class="form-control" v-model="form.scenarioName" :placeholder="t('modal.optional')">
                        </div>
                        <div style="display:grid;grid-template-columns:1fr 1fr;gap:0.5rem">
                            <div class="form-group" style="margin-bottom:0">
                                <label class="form-label">{{t('modal.requiredState')}}</label>
                                <input class="form-control" v-model="form.requiredScenarioState" :placeholder="t('modal.scenarioStatePlaceholder')">
                            </div>
                            <div class="form-group" style="margin-bottom:0">
                                <label class="form-label">{{t('modal.newState')}}</label>
                                <input class="form-control" v-model="form.newScenarioState" :placeholder="t('modal.scenarioStatePlaceholder')">
                            </div>
                        </div>
                        <div v-if="(form.requiredScenarioState || form.newScenarioState) && !form.scenarioName" class="invalid-feedback" style="display:block">{{t('validation.scenarioNameRequired')}}</div>
                    </div>
                    <!-- 規則資訊 -->
                    <div class="form-block">
                        <div class="form-block-header"><i class="bi bi-info-circle"></i> {{t('modal.ruleInfo')}}</div>
                        <div class="form-group" style="margin-bottom:0.5rem">
                            <label class="form-label">{{t('modal.ruleDescription')}}</label>
                            <input class="form-control" v-model="form.description" :placeholder="t('modal.ruleDescription')">
                        </div>
                        <div>
                            <label class="form-label">{{t('modal.tags')}}</label>
                            <div class="meta-tags">
                                <span v-for="(v,k) in parseTags(form.tags)" :key="k" class="tag-chip">
                                    <span class="tag-key">{{k}}</span><span class="tag-val">{{v}}</span>
                                    <i class="bi bi-x" @click="$emit('remove-tag',k)"></i>
                                </span>
                                <div class="tag-add-inline">
                                    <input v-model="newTag.key" placeholder="key" @keyup.enter="$emit('add-tag')">
                                    <span class="tag-sep">:</span>
                                    <input v-model="newTag.value" placeholder="value" @keyup.enter="$emit('add-tag')">
                                    <button type="button" @click="$emit('add-tag')"><i class="bi bi-plus"></i></button>
                                </div>
                            </div>
                        </div>
                    </div>
                    <!-- 測試區 (僅編輯時) -->
                    <div v-if="editing" class="form-block">
                        <div class="form-block-header" @click="$emit('update:test-expanded',!testExpanded)" style="cursor:pointer">
                            <i class="bi" :class="testExpanded?'bi-chevron-down':'bi-chevron-right'"></i>
                            <i class="bi bi-play-circle"></i> {{t('modal.testRule')}}
                        </div>
                        <div v-show="testExpanded" style="padding:0.75rem 0;display:flex;flex-direction:column;gap:0.5rem">
                            <div class="test-info" style="display:flex;flex-wrap:wrap;gap:0.4rem;align-items:center;padding:0.4rem 0.6rem;background:var(--bg);border-radius:4px;font-size:var(--font-sm)">
                                <template v-if="form.protocol==='HTTP'">
                                    <span class="badge badge-method">{{form.method||'GET'}}</span>
                                    <code style="font-weight:500">/mock{{form.matchKey==='*'?'/test':form.matchKey}}</code>
                                    <span v-if="form.targetHost" class="sub-info"><i class="bi bi-arrow-right"></i> {{form.targetHost}}</span>
                                </template>
                                <template v-else>
                                    <span class="badge badge-jms">JMS</span>
                                    <code style="font-weight:500">{{form.matchKey||'*'}}</code>
                                </template>
                                <span v-if="conditions.length" class="sub-info" style="margin-left:auto">{{conditions.length}} {{t('modal.conditionMatch').toLowerCase()}}</span>
                            </div>
                            <template v-if="form.protocol==='HTTP'">
                                <input class="form-control form-control-sm" v-model="testParams.query" :placeholder="'Query: '+(form.queryCondition||'key=value&...')">
                                <input class="form-control form-control-sm" v-model="testParams.headersStr" placeholder="Headers: Header1:value1, Header2:value2">
                                <textarea class="form-control form-control-sm" v-model="testParams.body" rows="6" :placeholder="'Body: '+(form.bodyCondition||'Request Body')"></textarea>
                            </template>
                            <template v-else>
                                <textarea class="form-control form-control-sm" v-model="testParams.body" rows="6" :placeholder="'Message: '+(form.bodyCondition||'JMS Message Body')"></textarea>
                                <div style="display:flex;align-items:center;gap:0.5rem">
                                    <span style="font-size:var(--font-sm);color:var(--muted)">Timeout:</span>
                                    <input type="number" class="form-control form-control-sm" v-model.number="testParams.timeout" style="width:60px">
                                    <span style="font-size:var(--font-sm);color:var(--muted)">{{t('modal.seconds')}}</span>
                                </div>
                            </template>
                            <div style="display:flex;gap:0.5rem;align-items:center">
                                <button class="btn btn-primary btn-sm" @click="$emit('run-test')" :disabled="testLoading" style="align-self:flex-start">
                                    <i class="bi" :class="testLoading?'bi-hourglass-split':'bi-send'"></i> {{t('modal.sendTest')}}
                                </button>
                                <button class="btn btn-secondary btn-sm" @click="$emit('generate-test-data')" :disabled="!conditions.length" :title="t('modal.generateTestData')">
                                    <i class="bi bi-magic"></i> {{t('modal.generateTestData')}}
                                </button>
                                <button v-if="testLoading && testSseMode" class="btn btn-outline-danger btn-sm" @click="$emit('stop-sse-test')">
                                    <i class="bi bi-stop-circle"></i> {{t('modal.stop')}}
                                </button>
                            </div>
                            <!-- SSE 測試結果 -->
                            <div v-if="testSseMode && testSseEvents.length" style="margin-top:0.25rem">
                                <div style="display:flex;align-items:center;gap:0.4rem;margin-bottom:0.3rem">
                                    <i class="bi bi-broadcast" style="color:var(--muted)"></i>
                                    <span class="param-label">{{t('modal.sseEvents')}}</span>
                                    <span class="badge bg-info ms-auto">{{testSseEvents.length}} {{t('modal.events')}}</span>
                                </div>
                                <div class="test-result" style="margin:0;padding:0">
                                    <div v-for="(ev, i) in testSseEvents" :key="i" style="padding:0.4rem 0.75rem;border-bottom:1px solid var(--border);font-size:var(--font-sm)">
                                        <div style="display:flex;align-items:center;gap:0.5rem;margin-bottom:0.15rem">
                                            <span style="color:var(--muted)">{{ev.time}}ms</span>
                                            <span v-if="ev.event" class="badge bg-secondary" style="font-size:var(--font-xs)">{{ev.event}}</span>
                                            <span v-if="ev.id" style="color:var(--muted);font-size:var(--font-xs)">id={{ev.id}}</span>
                                        </div>
                                        <pre style="margin:0;white-space:pre-wrap;word-break:break-all;font-size:var(--font-sm)">{{ev.data}}</pre>
                                    </div>
                                </div>
                            </div>
                            <!-- 一般測試結果 -->
                            <div v-if="!testSseMode && testResult" style="margin-top:0.25rem">
                                <div style="display:flex;align-items:center;gap:0.4rem;margin-bottom:0.3rem">
                                    <i class="bi bi-terminal" style="color:var(--muted)"></i>
                                    <span class="param-label">{{t('modal.testResult')}}</span>
                                    <span class="badge ms-auto" :class="testResult.status<400?'bg-success':'bg-danger'">
                                        {{testResult.status}} ({{testResult.elapsed}}ms)
                                    </span>
                                </div>
                                <pre class="test-result" style="margin:0">{{testResult.body}}</pre>
                            </div>
                            <!-- SSE 模式但出錯 -->
                            <div v-if="testSseMode && testResult" style="margin-top:0.25rem">
                                <div style="display:flex;align-items:center;gap:0.4rem;margin-bottom:0.3rem">
                                    <i class="bi bi-exclamation-triangle" style="color:var(--muted)"></i>
                                    <span class="param-label">{{t('modal.error')}}</span>
                                    <span class="badge bg-danger ms-auto">{{testResult.status}}</span>
                                </div>
                                <pre class="test-result" style="margin:0">{{testResult.body}}</pre>
                            </div>
                        </div>
                    </div>
                </div>
                <!-- 拖拽分隔線 -->
                <div class="rule-splitter" :class="{dragging:splitterDragging}" @mousedown="startSplitterDrag"></div>
                <!-- 右側：回應內容 -->
                <div class="rule-right">
                    <!-- 回應模式 + 統一選擇器 -->
                    <div class="form-block">
                        <div class="form-block-header"><i class="bi bi-arrow-left-right"></i> {{t('modal.responseMode')}}</div>
                        <div style="display:flex;align-items:center;gap:0.4rem;flex-wrap:wrap">
                            <div class="protocol-switch">
                                <button type="button" class="protocol-btn" :class="{active:form.responseMode==='new'}" @click="form.responseMode='new'">
                                    <i class="bi bi-file-earmark-plus"></i> {{t('modal.createNewResponse')}}
                                </button>
                                <button type="button" class="protocol-btn" :class="{active:form.responseMode==='existing'}" @click="form.responseMode='existing';$emit('on-response-mode-change')">
                                    <i class="bi bi-collection"></i> {{t('modal.useExisting')}}
                                </button>
                            </div>
                            <div v-if="form.responseMode==='new' && form.protocol==='HTTP' && !form.sseEnabled" style="margin-left:auto;display:flex;align-items:center;gap:0.35rem;font-size:var(--font-xs);color:var(--muted)">
                                <span>{{t('modal.template')}}:</span>
                                <button type="button" class="btn btn-xs btn-secondary" @click="$emit('apply-template','json')">JSON</button>
                                <button type="button" class="btn btn-xs btn-secondary" @click="$emit('apply-template','xml')">XML</button>
                                <button type="button" class="btn btn-xs btn-secondary" @click="$emit('apply-template','text')">Text</button>
                            </div>
                        </div>
                        <div v-if="form.responseMode==='existing'" style="margin-top:0.5rem;display:flex;flex-direction:column;gap:0.5rem">
                            <!-- 搜尋與篩選 -->
                            <div class="response-select-wrapper">
                                <div class="meta-field" style="gap:0.3rem">
                                    <i class="bi bi-search"></i>
                                    <div class="btn-group" style="flex:none">
                                        <button type="button" class="btn btn-xs" :class="responsePickerSseOnly?'btn-secondary':'btn-primary'" @click="$emit('update:response-picker-sse-only',false)" style="font-size:var(--font-xs);padding:0.2rem 0.5rem">{{t('modal.all')}}</button>
                                        <button type="button" class="btn btn-xs" :class="responsePickerSseOnly?'btn-primary':'btn-secondary'" @click="$emit('update:response-picker-sse-only',true)" style="font-size:var(--font-xs);padding:0.2rem 0.5rem">SSE</button>
                                    </div>
                                    <div style="position:relative;flex:1">
                                        <input class="meta-inline-input text-input" :value="responsePickerSearch" @input="$emit('update:response-picker-search',$event.target.value)" @focus="$emit('update:response-dropdown-open',true)" :placeholder="t('modal.searchResponse')" style="width:100%">
                                        <button v-if="responsePickerSearch" type="button" class="btn btn-sm btn-icon" @click="$emit('update:response-picker-search','')" style="position:absolute;right:2px;top:50%;transform:translateY(-50%);padding:0.2rem;width:24px;height:24px;background:none;border:none;color:var(--muted)">
                                            <i class="bi bi-x"></i>
                                        </button>
                                    </div>
                                </div>
                                <div v-if="responseDropdownOpen" class="response-dropdown">
                                    <div v-for="r in filteredResponsePicker" :key="r.id" @click="form.responseId=r.id;$emit('update:response-dropdown-open',false);$emit('update:response-picker-search','')" class="response-dropdown-item" :class="{selected:form.responseId===r.id}">
                                        <div class="response-dropdown-row1">
                                            <i v-if="form.responseId===r.id" class="bi bi-check-lg" style="color:var(--primary);flex:none"></i>
                                            <span class="badge badge-id">#{{r.id}}</span>
                                            <span class="response-dropdown-desc">{{r.description||t('responses.noDescription')}}</span>
                                            <span v-if="r.contentType==='SSE'" class="badge badge-sse" style="margin-left:auto;flex:none">SSE</span>
                                        </div>
                                        <div class="response-dropdown-row2">
                                            <span>{{fmtSize(r.bodySize||0)}}</span>
                                            <span v-if="r.usageCount">· {{t('modal.rulesUsing', {count: r.usageCount})}}</span>
                                            <span v-if="r.updatedAt">· {{fmtTime(r.updatedAt)}}</span>
                                        </div>
                                    </div>
                                    <div v-if="!filteredResponsePicker.length" class="response-empty">{{t('modal.noMatchingResponse')}}</div>
                                </div>
                            </div>
                            <!-- 已選擇的回應 -->
                            <div v-if="form.responseId" class="response-selected-card">
                                <span class="badge badge-id">#{{form.responseId}}</span>
                                <span class="response-selected-desc">{{selectedResponse?.description||t('responses.noDescription')}}</span>
                                <span v-if="selectedResponse?.contentType==='SSE'" class="badge badge-sse">SSE</span>
                                <span v-else class="badge badge-muted">{{t('modal.responseTypeGeneral')}}</span>
                                <span class="sub-info">{{fmtSize(selectedResponse?.bodySize||0)}}</span>
                                <span v-if="previewResponseUsageCount > 1" class="badge badge-warning" style="font-size:var(--font-xs)" :title="t('modal.modifyAffectsAll')">
                                    <i class="bi bi-exclamation-triangle"></i> {{t('modal.sharedCount', {count: previewResponseUsageCount})}}
                                </span>
                                <span v-if="form.sseEnabled && selectedResponse?.contentType!=='SSE'" class="badge badge-warning" style="font-size:var(--font-xs)" :title="t('modal.notSseTooltip')">
                                    <i class="bi bi-exclamation-triangle"></i> {{t('modal.notSse')}}
                                </span>
                                <span style="flex:1"></span>
                                <button type="button" class="btn btn-xs btn-icon btn-secondary" @click="$emit('go-to-responses',form.responseId)" :title="t('modal.goToResponseManagement')">
                                    <i class="bi bi-box-arrow-up-right"></i>
                                </button>
                                <button type="button" class="btn btn-xs btn-icon btn-secondary" @click="$emit('clear-response-selection')" :title="t('modal.clearSelection')">
                                    <i class="bi bi-x"></i>
                                </button>
                            </div>
                        </div>
                    </div>
                    <!-- 回應內容 -->
                    <div class="form-block response-content-block">
                        <div class="form-block-header"><i class="bi bi-code-square"></i> {{t('modal.responseContent')}}</div>
                        <!-- SSE 表格編輯器 -->
                        <div v-if="form.sseEnabled && form.protocol==='HTTP'" class="sse-editor-wrap" style="display:flex;flex-direction:column;gap:0.5rem;overflow:auto">
                            <div class="d-flex align-items-center gap-2">
                                <span class="sub-info"><i class="bi bi-broadcast"></i> {{t('modal.sseEditor')}}</span>
                                <div class="form-check form-switch ms-auto" style="margin:0">
                                    <input class="form-check-input" type="checkbox" id="sseLoopToggle" v-model="form.sseLoopEnabled">
                                    <label class="form-check-label" for="sseLoopToggle" style="font-size:var(--font-sm)"><i class="bi bi-arrow-repeat"></i> {{t('modal.sseLoopMode')}}</label>
                                </div>
                            </div>
                            <div class="sse-table" style="overflow:auto">
                                <table style="font-size:var(--font-base)">
                                    <thead>
                                        <tr>
                                            <th style="width:28px"></th>
                                            <th style="width:100px">類型</th>
                                            <th style="width:120px">event</th>
                                            <th>data <span class="text-danger">*</span></th>
                                            <th style="width:80px">id</th>
                                            <th style="width:80px">延遲(ms)</th>
                                            <th style="width:40px"></th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        <tr v-for="(evt, idx) in sseEvents" :key="idx"
                                            :class="sseRowClass(evt)"
                                            draggable="true"
                                            @dragstart="onSseDragStart($event, idx)"
                                            @dragover="onSseDragOver($event)"
                                            @drop="onSseDrop($event, idx)"
                                            @dragend="onSseDragEnd">
                                            <td class="sse-drag-handle" style="cursor:grab;text-align:center;vertical-align:middle">
                                                <i class="bi bi-grip-vertical"></i>
                                            </td>
                                            <td>
                                                <select class="form-select form-select-sm" v-model="evt.type">
                                                    <option value="normal">normal</option>
                                                    <option value="error">error</option>
                                                    <option value="abort">abort</option>
                                                </select>
                                            </td>
                                            <td>
                                                <input class="form-control form-control-sm" v-model="evt.event" :placeholder="t('modal.sseEventName')">
                                            </td>
                                            <td>
                                                <textarea class="form-control form-control-sm" v-model="evt.data" :placeholder="t('modal.sseEventData')" rows="1" style="min-height:30px;resize:vertical;font-family:var(--font-mono);font-size:var(--font-xs)"></textarea>
                                            </td>
                                            <td>
                                                <input class="form-control form-control-sm" v-model="evt.id" placeholder="ID">
                                            </td>
                                            <td>
                                                <input class="form-control form-control-sm" type="number" v-model.number="evt.delayMs" min="0" placeholder="0">
                                            </td>
                                            <td style="text-align:center;vertical-align:middle">
                                                <button type="button" class="btn btn-xs btn-icon btn-outline-danger" @click="$emit('remove-sse-event',idx)" :disabled="sseEvents.length<=1" :title="t('modal.deleteSseEvent')">
                                                    <i class="bi bi-trash"></i>
                                                </button>
                                            </td>
                                        </tr>
                                    </tbody>
                                </table>
                            </div>
                            <div style="display:flex;align-items:center;gap:0.5rem">
                                <button type="button" class="cond-add" @click="$emit('add-sse-event')" style="flex:none">
                                    <i class="bi bi-plus-circle"></i> {{t('modal.addSseEvent')}}
                                </button>
                                <span class="sub-info" style="margin-left:auto">{{t('modal.sseEventCount', {count: sseEvents.length})}}</span>
                            </div>
                            <div v-if="ssePreview" class="sse-preview" style="flex-shrink:0">
                                <div class="sub-info" style="font-size:var(--font-xs);margin-bottom:4px"><i class="bi bi-eye"></i> {{t('modal.sseStreamPreview')}}</div>
                                <pre style="margin:0;font-size:var(--font-xs);white-space:pre-wrap;word-break:break-all">{{ssePreview}}</pre>
                            </div>
                            <div v-if="formErrors.responseBody" class="invalid-feedback" style="display:block">{{formErrors.responseBody}}</div>
                        </div>
                        <!-- 使用現有回應 (非 SSE) -->
                        <div v-else-if="form.responseMode==='existing'" style="flex:1;display:flex;flex-direction:column;gap:0.5rem;min-height:0">
                            <div v-if="form.responseId && previewResponseBody" class="response-preview-wrap">
                                <div class="preview-toolbar">
                                    <button type="button" class="btn btn-xs" :class="previewEditing?'btn-warning':'btn-secondary'" @click="$emit('toggle-preview-editing')" :title="previewEditing ? t('modal.cancelEdit') : t('modal.editResponse2')">
                                        <i class="bi" :class="previewEditing?'bi-x-lg':'bi-pencil'"></i>
                                        {{previewEditing ? t('modal.cancelEdit') : t('modal.editResponse2')}}
                                    </button>
                                    <span v-if="previewEditing && previewResponseUsageCount > 1" class="badge badge-warning" style="font-size:var(--font-xs)" :title="t('modal.modifyAffectsAll')">
                                        <i class="bi bi-exclamation-triangle"></i> {{t('modal.sharedWarning', {count: previewResponseUsageCount})}}
                                    </span>
                                    <button type="button" class="btn btn-xs btn-secondary" @click="$emit('toggle-preview-format')">
                                        <i class="bi" :class="previewFormatted?'bi-code':'bi-braces'"></i>
                                        {{previewFormatted ? t('modal.plainText') : t('modal.format')}}
                                        <span v-if="previewResponseBody.length>512000" class="text-warning">{{t('modal.largeFile')}}</span>
                                    </button>
                                    <span style="font-size:var(--font-xs);color:var(--muted)">{{(previewResponseBody.length/1024).toFixed(1)}} KB</span>
                                    <button v-if="previewEditing" type="button" class="btn btn-xs btn-primary" @click="$emit('save-preview-response')" :disabled="previewSaving" style="margin-left:auto">
                                        <i class="bi" :class="previewSaving?'bi-hourglass-split':'bi-check-lg'"></i> {{t('modal.saveResponse')}}
                                    </button>
                                </div>
                                <div id="rulePreviewEditor" class="preview-editor" :class="{editing:previewEditing}"></div>
                            </div>
                            <div v-else-if="form.responseId" class="response-preview-loading"><i class="bi bi-hourglass-split spin"></i> {{t('modal.loadingResponse')}}</div>
                            <div v-else class="response-preview-empty">{{t('modal.selectResponse')}}</div>
                        </div>
                        <!-- 建立新回應 (非 SSE) -->
                        <div v-else style="flex:1;display:flex;flex-direction:column;gap:0.5rem;min-height:0">
                            <div class="edit-toolbar">
                                <button type="button" class="btn btn-xs btn-secondary" @click="$emit('toggle-edit-format')">
                                    <i class="bi" :class="editFormatted?'bi-code':'bi-braces'"></i>
                                    {{editFormatted ? t('modal.plainText') : t('modal.format')}}
                                </button>
                                <span class="sub-info" style="margin-left:auto">{{fmtSize(form.responseBody?.length || 0)}}</span>
                                <span v-if="(form.responseBody?.length || 0) > 5242880" class="badge badge-warning" :title="t('modal.exceedCacheTooltip')"><i class="bi bi-exclamation-triangle"></i></span>
                            </div>
                            <div id="ruleEditEditor" class="edit-editor"></div>
                        </div>
                    </div>
                </div>
            </div>
            <div v-if="showCatchAllWarning" class="catch-all-warning">
                <i class="bi bi-exclamation-triangle-fill"></i>
                <span>{{t('modal.catchAllWarning')}}</span>
                <label class="catch-all-confirm-label">
                    <input type="checkbox" :checked="catchAllConfirmed" @change="$emit('update:catch-all-confirmed', $event.target.checked)">
                    {{t('modal.catchAllConfirm')}}
                </label>
            </div>
            <div class="modal-footer">
                <button class="btn btn-secondary" @click="$emit('close')">{{t('modal.cancel')}}</button>
                <button class="btn btn-primary" @click="$emit('save',false)" :disabled="!canSave||saving"><i class="bi" :class="saving?'bi-arrow-clockwise spin':'bi-check-lg'"></i> {{t('modal.save')}}</button>
                <button class="btn btn-primary" @click="$emit('save',true)" :disabled="!canSave||saving"><i class="bi" :class="saving?'bi-arrow-clockwise spin':'bi-check-lg'"></i> {{t('modal.saveAndClose')}}</button>
            </div>
        </div>
    </div>
    `
};
