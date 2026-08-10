/**
 * RuleEditModal - 規則建立/編輯 Modal
 * 最大的元件，包含協定切換、條件匹配、回應編輯、SSE 編輯器、測試區等功能。
 * CodeMirror 編輯器使用 DOM id-based getter pattern 讓父層 renderEditor() 仍可運作。
 */
const RuleEditModal = {
    props: {
        show: Boolean,
        editing: Object,
        editorMode: { type: String, default: 'form' },
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
        httpTargetConnections: Array,
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
        previewResponseLoading: Boolean,
        previewResponseLoadFailed: Boolean,
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
        'change-editor-mode',
    ],
    inject: ['t'],
    setup(props, { emit }) {
        const { ref } = Vue;
        const t = Vue.inject('t');
        const dialogRef = ref(null);
        const responsePickerInput = ref(null);
        const responsePickerActiveIndex = ref(-1);
        let previousFocus = null;
        let lastOutsideInteraction = null;

        const rememberOutsideInteraction = event => {
            if (props.show || !(event.target instanceof HTMLElement)) return;
            lastOutsideInteraction = event.target.closest('button, a[href], input, select, textarea, [tabindex]');
        };

        const focusableElements = () => {
            if (!dialogRef.value) return [];
            return [...dialogRef.value.querySelectorAll(
                'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [contenteditable="true"], [tabindex]:not([tabindex="-1"])'
            )].filter(element => element.offsetParent !== null);
        };
        const closeResponsePicker = () => {
            responsePickerActiveIndex.value = -1;
            emit('update:response-dropdown-open', false);
        };
        const responseOptionId = response => response ? `rule-response-option-${String(response.id).replace(/[^a-zA-Z0-9_-]/g, '-')}` : undefined;
        const scrollActiveResponseIntoView = () => Vue.nextTick(() => {
            const response = props.filteredResponsePicker[responsePickerActiveIndex.value];
            if (response) document.getElementById(responseOptionId(response))?.scrollIntoView({ block: 'nearest' });
        });
        const openResponsePicker = () => {
            emit('update:response-dropdown-open', true);
            const selectedIndex = props.filteredResponsePicker.findIndex(response => response.id === props.form.responseId);
            responsePickerActiveIndex.value = selectedIndex >= 0 ? selectedIndex : (props.filteredResponsePicker.length ? 0 : -1);
            scrollActiveResponseIntoView();
        };
        const selectResponse = response => {
            if (!response) return;
            props.form.responseId = response.id;
            emit('update:response-picker-search', '');
            closeResponsePicker();
        };
        const moveResponsePicker = direction => {
            const count = props.filteredResponsePicker.length;
            if (!count) return;
            if (!props.responseDropdownOpen) {
                emit('update:response-dropdown-open', true);
                responsePickerActiveIndex.value = direction > 0 ? 0 : count - 1;
                scrollActiveResponseIntoView();
                return;
            }
            const current = responsePickerActiveIndex.value < 0 ? 0 : responsePickerActiveIndex.value;
            responsePickerActiveIndex.value = (current + direction + count) % count;
            scrollActiveResponseIntoView();
        };
        const onResponsePickerKeydown = event => {
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                event.preventDefault();
                moveResponsePicker(event.key === 'ArrowDown' ? 1 : -1);
            } else if (event.key === 'Home' && props.responseDropdownOpen) {
                event.preventDefault();
                responsePickerActiveIndex.value = props.filteredResponsePicker.length ? 0 : -1;
                scrollActiveResponseIntoView();
            } else if (event.key === 'End' && props.responseDropdownOpen) {
                event.preventDefault();
                responsePickerActiveIndex.value = props.filteredResponsePicker.length - 1;
                scrollActiveResponseIntoView();
            } else if (event.key === 'Enter' && props.responseDropdownOpen && responsePickerActiveIndex.value >= 0) {
                event.preventDefault();
                selectResponse(props.filteredResponsePicker[responsePickerActiveIndex.value]);
            } else if (event.key === 'Escape' && props.responseDropdownOpen) {
                event.preventDefault();
                event.stopPropagation();
                closeResponsePicker();
            }
        };
        const onResponseSearchInput = event => {
            emit('update:response-picker-search', event.target.value);
            if (!props.responseDropdownOpen) openResponsePicker();
        };
        const clearResponseSearch = () => {
            emit('update:response-picker-search', '');
            responsePickerInput.value?.focus();
            openResponsePicker();
        };
        const onDialogKeydown = event => {
            if (event.key === 'Escape') {
                event.preventDefault();
                event.stopPropagation();
                if (props.responseDropdownOpen) closeResponsePicker();
                else emit('close');
                return;
            }
            if (event.key !== 'Tab') return;
            const focusable = focusableElements();
            if (!focusable.length) {
                event.preventDefault();
                dialogRef.value?.focus();
                return;
            }
            const first = focusable[0];
            const last = focusable[focusable.length - 1];
            if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogRef.value || !dialogRef.value?.contains(document.activeElement))) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first.focus();
            }
        };
        // 已選擇的回應 computed（避免 template 中重複 find）
        const selectedResponse = Vue.computed(() =>
            props.responseSummary.find(r => r.id === props.form.responseId)
        );
        const forwardSelection = Vue.computed({
            get: () => props.form.forwardTargetMode === 'CONNECTION'
                ? 'CONNECTION:' + (props.form.httpTargetConnectionId || '')
                : (props.form.forwardTargetMode || 'ORIGINAL_HOST'),
            set: value => {
                if (value.startsWith('CONNECTION:')) {
                    props.form.forwardTargetMode = 'CONNECTION';
                    props.form.httpTargetConnectionId = Number(value.substring('CONNECTION:'.length));
                } else {
                    props.form.forwardTargetMode = value;
                    props.form.httpTargetConnectionId = null;
                }
            }
        });
        const availableHttpTargetConnections = Vue.computed(() =>
            (props.httpTargetConnections || []).filter(target =>
                target.enabled || target.id === props.form.httpTargetConnectionId
            )
        );
        const defaultHttpTargetConnection = Vue.computed(() =>
            (props.httpTargetConnections || []).find(target => target.defaultConnection && target.enabled)
        );
        const mockAdvancedOpen = ref(false);
        const forwardAdvancedOpen = ref(false);
        const responseHeaderCount = Vue.computed(() =>
            props.form.protocol === 'HTTP' ? Object.keys(parseHeaders(props.form.responseHeaders)).length : 0
        );
        const hasAdvancedTiming = () => Number(props.form.delayMs || 0) > 0 || Number(props.form.maxDelayMs || 0) > 0;
        const delaySummary = Vue.computed(() => {
            const minimum = Number(props.form.delayMs || 0);
            const maximum = Number(props.form.maxDelayMs || 0);
            if (maximum > minimum) return t('modal.delayRangeSummary', { min: minimum, max: maximum });
            if (minimum > 0) return t('modal.delayValueSummary', { value: minimum });
            return t('modal.noDelay');
        });
        const mockAdvancedSummary = Vue.computed(() => {
            if (!hasAdvancedTiming() && responseHeaderCount.value === 0) {
                return props.form.protocol === 'HTTP' ? t('modal.advancedMockHint') : t('modal.advancedJmsHint');
            }
            const parts = [delaySummary.value];
            if (responseHeaderCount.value > 0) {
                parts.push(t('modal.headerCountSummary', { count: responseHeaderCount.value }));
            }
            return parts.join(' · ');
        });
        const forwardAdvancedSummary = Vue.computed(() =>
            hasAdvancedTiming() ? delaySummary.value : t('modal.advancedForwardHint')
        );
        const syncAdvancedDisclosure = () => {
            const timingConfigured = hasAdvancedTiming();
            mockAdvancedOpen.value = timingConfigured || responseHeaderCount.value > 0;
            forwardAdvancedOpen.value = timingConfigured;
        };
        const setMockAdvancedOpen = event => { mockAdvancedOpen.value = event.currentTarget.open; };
        const setForwardAdvancedOpen = event => { forwardAdvancedOpen.value = event.currentTarget.open; };
        const selectedForwardTarget = Vue.computed(() => {
            const mode = props.form.forwardTargetMode || 'ORIGINAL_HOST';
            if (mode === 'CONNECTION') {
                return (props.httpTargetConnections || []).find(target => target.id === props.form.httpTargetConnectionId);
            }
            if (mode === 'DEFAULT_CONNECTION') {
                return defaultHttpTargetConnection.value;
            }
            return null;
        });
        const forwardSourceSummary = Vue.computed(() => {
            const method = props.form.method || 'GET';
            const path = !props.form.matchKey || props.form.matchKey === '*' ? '/…' : props.form.matchKey;
            return `${method} ${path}`;
        });
        const forwardTargetLabel = Vue.computed(() => {
            const mode = props.form.forwardTargetMode || 'ORIGINAL_HOST';
            if (mode === 'ORIGINAL_HOST') return 'X-Original-Host';
            if (selectedForwardTarget.value) return selectedForwardTarget.value.name;
            return mode === 'DEFAULT_CONNECTION' ? t('modal.forwardDefaultMissing') : '—';
        });
        const forwardDestinationUrl = Vue.computed(() => {
            const path = !props.form.matchKey || props.form.matchKey === '*' ? '/…' : props.form.matchKey;
            if ((props.form.forwardTargetMode || 'ORIGINAL_HOST') === 'ORIGINAL_HOST') {
                return `X-Original-Host${path}`;
            }
            const target = selectedForwardTarget.value;
            if (!target) return '—';
            return target.baseUrl.replace(/\/+$/, '') + (path.startsWith('/') ? path : '/' + path);
        });
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
        Vue.watch(() => props.show, (v) => {
            if (v) {
                syncAdvancedDisclosure();
                previousFocus = document.activeElement instanceof HTMLElement && document.activeElement !== document.body
                    ? document.activeElement
                    : lastOutsideInteraction;
                Vue.nextTick(() => {
                    applySplitRatio();
                    dialogRef.value?.focus();
                });
            } else if (previousFocus instanceof HTMLElement && document.contains(previousFocus)) {
                const focusTarget = previousFocus;
                Vue.nextTick(() => focusTarget.focus());
                previousFocus = null;
            }
        });
        Vue.watch(() => props.filteredResponsePicker, list => {
            if (!props.responseDropdownOpen) return;
            const selectedIndex = list.findIndex(response => response.id === props.form.responseId);
            responsePickerActiveIndex.value = selectedIndex >= 0 ? selectedIndex : (list.length ? 0 : -1);
        });
        Vue.watch(() => props.responseDropdownOpen, open => {
            if (!open) responsePickerActiveIndex.value = -1;
        });
        Vue.watch(() => [props.formErrors?.delayMs, props.formErrors?.maxDelayMs], errors => {
            if (!props.show || !errors.some(Boolean)) return;
            if (props.form.protocol === 'HTTP' && props.form.action === 'FORWARD') forwardAdvancedOpen.value = true;
            else mockAdvancedOpen.value = true;
        });
        Vue.watch(() => [props.form.protocol, props.form.action], ([protocol, action]) => {
            if (!props.show || !hasAdvancedTiming()) return;
            if (protocol === 'HTTP' && action === 'FORWARD') forwardAdvancedOpen.value = true;
            else mockAdvancedOpen.value = true;
        });
        Vue.onMounted(() => document.addEventListener('pointerdown', rememberOutsideInteraction, true));
        Vue.onBeforeUnmount(() => {
            document.removeEventListener('pointerdown', rememberOutsideInteraction, true);
            if (previousFocus instanceof HTMLElement && document.contains(previousFocus)) previousFocus.focus();
        });
        return {
            dialogRef, responsePickerInput, responsePickerActiveIndex,
            responseOptionId, openResponsePicker, closeResponsePicker, selectResponse,
            onResponsePickerKeydown, onResponseSearchInput, clearResponseSearch, onDialogKeydown,
            selectedResponse, forwardSelection, availableHttpTargetConnections, defaultHttpTargetConnection,
            selectedForwardTarget, forwardSourceSummary, forwardTargetLabel, forwardDestinationUrl,
            mockAdvancedOpen, forwardAdvancedOpen, mockAdvancedSummary, forwardAdvancedSummary,
            setMockAdvancedOpen, setForwardAdvancedOpen,
            splitterDragging, startSplitterDrag,
            sseDragIndex, sseRowClass, onSseDragStart, onSseDragOver, onSseDrop, onSseDragEnd,
            parseTags, parseHeaders, fmtTime, fmtSize,
        };
    },
    template: /* html */`
    <div class="modal-overlay" v-if="show" :style="maximized?'padding:0':''" @keydown="onDialogKeydown">
        <div ref="dialogRef" class="modal-box rule-modal-fullscreen workspace-modal" :class="{maximized:maximized}" role="dialog" aria-modal="true" :aria-label="editing ? t('modal.editRule') : t('modal.addRule')" tabindex="-1">
            <div class="modal-header">
                <div class="rule-modal-heading-area">
                    <div class="modal-heading">
                        <span class="modal-heading-icon"><i class="bi" :class="editing?'bi-pencil-square':'bi-plus-circle'"></i></span>
                        <h3>{{editing ? t('modal.editRule') : t('modal.addRule')}}</h3>
                    </div>
                    <div class="rule-editor-mode-switch" role="group" :aria-label="t('modal.settingMode')">
                        <button type="button" :aria-pressed="editorMode==='form'" :class="{active:editorMode==='form'}" @click="$emit('change-editor-mode','form')">
                            <i class="bi bi-ui-checks-grid" aria-hidden="true"></i>{{t('modal.formSettingMode')}}
                        </button>
                        <button type="button" :aria-pressed="editorMode==='declarative'" :class="{active:editorMode==='declarative'}" @click="$emit('change-editor-mode','declarative')">
                            <i class="bi bi-braces" aria-hidden="true"></i>{{t('modal.declarativeSettingMode')}}
                        </button>
                    </div>
                </div>
                <div class="rule-modal-actions">
                    <button class="close-btn" @click="$emit('update:maximized',!maximized)" :title="maximized ? t('modal.restoreWindow') : t('modal.fullscreen')" :aria-label="maximized ? t('modal.restoreWindow') : t('modal.fullscreen')"><i class="bi" :class="maximized?'bi-fullscreen-exit':'bi-arrows-fullscreen'"></i></button>
                    <button class="close-btn" @click="$emit('close')" :aria-label="t('modal.cancel')"><i class="bi bi-x-lg"></i></button>
                </div>
            </div>
            <div v-if="editorMode==='form'" class="modal-body rule-editor">
                <!-- 左側：匹配條件 + 測試 -->
                <div class="rule-left">
                    <div class="rule-pane-heading">
                        <span class="rule-pane-heading-icon"><i class="bi bi-funnel"></i></span>
                        <span class="rule-pane-title-row">
                            <strong>{{t('modal.ruleConditions')}}</strong>
                            <button type="button" class="help-tooltip tooltip-align-start" :data-tooltip="t('modal.ruleConditionsHint')" :aria-label="t('modal.ruleConditions') + '：' + t('modal.ruleConditionsHint')" @keydown.esc="$event.currentTarget.blur()">
                                <i class="bi bi-question-circle" aria-hidden="true"></i>
                            </button>
                        </span>
                    </div>
                    <!-- 協定與規則狀態 -->
                    <div class="form-block rule-identity-block" data-tour="protocol">
                        <div class="rule-control-row">
                            <div id="ruleProtocolLabel" class="rule-control-label">{{t('modal.protocolLabel')}}</div>
                            <div class="rule-protocol-options" role="group" aria-labelledby="ruleProtocolLabel">
                                <button type="button" class="rule-protocol-option" :class="{'is-selected':form.protocol==='HTTP'}" :aria-pressed="form.protocol==='HTTP'" @click="$emit('set-protocol','HTTP')">
                                    <i class="bi bi-globe" aria-hidden="true"></i>
                                    <span>{{httpLabel}}</span>
                                </button>
                                <button type="button" class="rule-protocol-option" :class="{'is-selected':form.protocol==='JMS'}" :aria-pressed="form.protocol==='JMS'" :disabled="!jmsEnabled" @click="jmsEnabled&&$emit('set-protocol','JMS')">
                                    <i class="bi bi-envelope" aria-hidden="true"></i>
                                    <span>{{jmsLabel}}</span>
                                </button>
                            </div>
                        </div>
                        <div class="rule-control-row">
                            <div id="ruleStateLabel" class="rule-control-label">{{t('modal.ruleState')}}</div>
                            <div class="rule-state-controls" role="group" aria-labelledby="ruleStateLabel">
                                <button type="button" class="rule-state-control" :class="{'is-active':form.enabled}" :aria-pressed="form.enabled" @click="form.enabled=!form.enabled">
                                    <i class="bi" :class="form.enabled?'bi-check-square-fill':'bi-square'" aria-hidden="true"></i>
                                    <span>{{form.enabled ? t('modal.formEnabled') : t('modal.formDisabled')}}</span>
                                </button>
                                <button type="button" class="rule-state-control" :class="{'is-active':form.isProtected,'is-risk':!form.isProtected}" :aria-pressed="form.isProtected" @click="form.isProtected=!form.isProtected" :title="t('modal.protectedTooltip')">
                                    <i class="bi" :class="form.isProtected?'bi-shield-fill-check':'bi-shield'" aria-hidden="true"></i>
                                    <span>{{form.isProtected ? t('modal.formProtected') : t('modal.formUnprotected')}}</span>
                                </button>
                                <button type="button" v-if="form.protocol==='HTTP' && form.action!=='FORWARD'" class="rule-state-control" :class="{'is-active':form.sseEnabled,'is-selected':form.sseEnabled}" :aria-pressed="form.sseEnabled" @click="form.sseEnabled=!form.sseEnabled">
                                    <i class="bi bi-broadcast" aria-hidden="true"></i>
                                    <span>SSE</span>
                                </button>
                            </div>
                        </div>
                    </div>
                    <!-- 匹配路徑 -->
                    <div class="form-block" data-tour="match">
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
                                <label class="form-label">{{t('modal.sourceHostMatch')}}</label>
                                <input class="form-control" v-model="form.targetHost" placeholder="api.example.com">
                                <div class="sub-info source-host-hint" style="margin-top:0.3rem">{{t('modal.sourceHostMatchHint')}}</div>
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
                    <div class="form-block" data-tour="conditions">
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
                    <!-- 規則資訊 -->
                    <div class="form-block">
                        <div class="form-block-header"><i class="bi bi-info-circle"></i> {{t('modal.ruleInfo')}}</div>
                        <div class="rule-info-grid">
                            <div class="form-group">
                                <label class="form-label" for="ruleDescription">{{t('modal.ruleDescription')}}</label>
                                <input id="ruleDescription" class="form-control" v-model="form.description" :placeholder="t('modal.ruleDescription')" maxlength="255">
                            </div>
                            <div class="form-group">
                                <div class="form-label-row">
                                    <label class="form-label" for="rulePriority">{{t('modal.priority')}}</label>
                                    <button type="button" class="help-tooltip tooltip-align-end" :data-tooltip="t('modal.priorityTooltip')" :aria-label="t('modal.priority') + '：' + t('modal.priorityTooltip')" @keydown.esc="$event.currentTarget.blur()">
                                        <i class="bi bi-question-circle" aria-hidden="true"></i>
                                    </button>
                                </div>
                                <input id="rulePriority" type="number" class="form-control" v-model.number="form.priority" min="0" step="1" placeholder="0">
                            </div>
                        </div>
                        <div>
                            <label class="form-label">{{t('modal.tags')}}</label>
                            <div class="meta-tags">
                                <span v-for="(v,k) in parseTags(form.tags)" :key="k" class="tag-chip">
                                    <span class="tag-key">{{k}}</span><span class="tag-val">{{v}}</span>
                                    <button type="button" class="tag-chip-remove" @click="$emit('remove-tag',k)" :aria-label="t('modal.removeTag', {key:k})" :title="t('modal.removeTag', {key:k})"><i class="bi bi-x" aria-hidden="true"></i></button>
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
                    <!-- Fault Injection -->
                    <div class="form-block">
                        <div class="form-block-header"><i class="bi bi-lightning"></i> {{t('modal.faultType')}}</div>
                        <select class="form-control" v-model="form.faultType">
                            <option value="NONE">{{t('modal.faultNone')}}</option>
                            <option value="CONNECTION_RESET">{{t('modal.faultConnectionReset')}}</option>
                            <option value="EMPTY_RESPONSE">{{t('modal.faultEmptyResponse')}}</option>
                        </select>
                    </div>
                    <!-- Scenario 狀態機 -->
                    <div class="form-block" v-if="form.protocol === 'HTTP' || form.protocol === 'JMS'">
                        <div class="form-block-header"><i class="bi bi-diagram-3"></i> {{t('modal.scenario')}}</div>
                        <div class="row g-2">
                            <div class="col-12">
                                <label class="form-label">{{t('modal.scenarioName')}}</label>
                                <input class="form-control" v-model="form.scenarioName" :placeholder="t('modal.optional')">
                            </div>
                            <div class="col-6">
                                <label class="form-label">{{t('modal.requiredState')}}</label>
                                <input class="form-control" v-model="form.requiredScenarioState" :placeholder="t('modal.scenarioStatePlaceholder')">
                            </div>
                            <div class="col-6">
                                <label class="form-label">{{t('modal.newState')}}</label>
                                <input class="form-control" v-model="form.newScenarioState" :placeholder="t('modal.scenarioStatePlaceholder')">
                            </div>
                        </div>
                        <div v-if="(form.requiredScenarioState || form.newScenarioState) && !form.scenarioName" class="invalid-feedback" style="display:block">{{t('validation.scenarioNameRequired')}}</div>
                    </div>
                    <!-- 測試區 (僅編輯時) -->
                    <div v-if="editing" class="form-block rule-test-block">
                        <button type="button" class="form-block-header rule-test-toggle" :aria-expanded="testExpanded" aria-controls="ruleTestPanel" @click="$emit('update:test-expanded',!testExpanded)">
                            <i class="bi" :class="testExpanded?'bi-chevron-down':'bi-chevron-right'" aria-hidden="true"></i>
                            <i class="bi bi-play-circle" aria-hidden="true"></i><span>{{t('modal.testRule')}}</span>
                        </button>
                        <div v-show="testExpanded" id="ruleTestPanel" class="rule-test-panel">
                            <div class="rule-test-target">
                                <div class="rule-test-target-primary">
                                    <template v-if="form.protocol==='HTTP'">
                                        <span class="badge badge-method">{{form.method||'GET'}}</span>
                                        <code>/mock{{form.matchKey==='*'?'/test':form.matchKey}}</code>
                                    </template>
                                    <template v-else>
                                        <span class="badge badge-jms">JMS</span>
                                        <code>{{form.matchKey||'*'}}</code>
                                    </template>
                                </div>
                                <span v-if="form.targetHost" class="rule-test-host"><i class="bi bi-arrow-right" aria-hidden="true"></i>{{form.targetHost}}</span>
                                <span v-if="conditions.length" class="rule-test-condition-count">{{t('modal.testConditionCount', {count:conditions.length})}}</span>
                            </div>
                            <div class="rule-test-fields">
                                <template v-if="form.protocol==='HTTP'">
                                    <div class="form-group">
                                        <label class="form-label" for="testQuery">{{t('modal.testQuery')}}</label>
                                        <input id="testQuery" class="form-control form-control-sm" v-model="testParams.query" :placeholder="form.queryCondition||'key=value&...'">
                                    </div>
                                    <div class="form-group">
                                        <label class="form-label" for="testHeaders">{{t('modal.testHeaders')}}</label>
                                        <input id="testHeaders" class="form-control form-control-sm" v-model="testParams.headersStr" :placeholder="t('modal.testHeadersPlaceholder')">
                                    </div>
                                    <div class="form-group rule-test-wide">
                                        <label class="form-label" for="testBody">{{t('modal.testBody')}}</label>
                                        <textarea id="testBody" class="form-control form-control-sm rule-test-body" v-model="testParams.body" rows="6" :placeholder="form.bodyCondition||t('modal.testBodyPlaceholder')"></textarea>
                                    </div>
                                </template>
                                <template v-else>
                                    <div class="form-group rule-test-wide">
                                        <label class="form-label" for="testMessage">{{t('modal.testMessage')}}</label>
                                        <textarea id="testMessage" class="form-control form-control-sm rule-test-body" v-model="testParams.body" rows="6" :placeholder="form.bodyCondition||t('modal.testMessagePlaceholder')"></textarea>
                                    </div>
                                    <div class="form-group rule-test-timeout">
                                        <label class="form-label" for="testTimeout">{{t('modal.testTimeout')}}</label>
                                        <div class="input-affix"><input id="testTimeout" type="number" class="form-control form-control-sm" v-model.number="testParams.timeout" min="1"><span class="input-affix-postfix">{{t('modal.seconds')}}</span></div>
                                    </div>
                                </template>
                            </div>
                            <div class="rule-test-actions">
                                <button type="button" class="btn btn-primary btn-sm" @click="$emit('run-test')" :disabled="testLoading">
                                    <i class="bi" :class="testLoading?'bi-arrow-clockwise spin':'bi-send'" aria-hidden="true"></i>{{t('modal.sendTest')}}
                                </button>
                                <button type="button" class="btn btn-secondary btn-sm" @click="$emit('generate-test-data')" :disabled="!conditions.length" :title="t('modal.generateTestData')">
                                    <i class="bi bi-magic" aria-hidden="true"></i>{{t('modal.generateTestData')}}
                                </button>
                                <button v-if="testLoading && testSseMode" type="button" class="btn btn-danger btn-sm" @click="$emit('stop-sse-test')">
                                    <i class="bi bi-stop-circle" aria-hidden="true"></i>{{t('modal.stop')}}
                                </button>
                            </div>
                            <section v-if="testSseMode && testSseEvents.length" class="rule-test-output" aria-live="polite">
                                <div class="rule-test-output-header">
                                    <span><i class="bi bi-broadcast" aria-hidden="true"></i>{{t('modal.sseEvents')}}</span>
                                    <span class="rule-test-output-meta">{{t('modal.sseEventCount', {count:testSseEvents.length})}}</span>
                                </div>
                                <ol class="rule-test-sse-list">
                                    <li v-for="(ev, i) in testSseEvents" :key="i" class="rule-test-sse-event">
                                        <div class="rule-test-sse-meta"><span>{{t('modal.ssePreviewEventIndex', {index:i+1})}}</span><span>{{ev.time}} ms</span><code v-if="ev.event">event: {{ev.event}}</code><code v-if="ev.id">id: {{ev.id}}</code></div>
                                        <pre>{{ev.data}}</pre>
                                    </li>
                                </ol>
                            </section>
                            <section v-if="!testSseMode && testResult" class="rule-test-output" :class="{'is-error':testResult.status>=400}" aria-live="polite">
                                <div class="rule-test-output-header">
                                    <span><i class="bi" :class="testResult.status<400?'bi-terminal':'bi-exclamation-triangle'" aria-hidden="true"></i>{{testResult.status<400?t('modal.testResult'):t('modal.error')}}</span>
                                    <span class="rule-test-output-meta tabular-nums">{{testResult.status}} · {{testResult.elapsed}} ms</span>
                                </div>
                                <pre class="test-result">{{testResult.body}}</pre>
                            </section>
                            <section v-if="testSseMode && testResult" class="rule-test-output is-error" aria-live="assertive">
                                <div class="rule-test-output-header"><span><i class="bi bi-exclamation-triangle" aria-hidden="true"></i>{{t('modal.error')}}</span><span class="rule-test-output-meta">{{testResult.status}}</span></div>
                                <pre class="test-result">{{testResult.body}}</pre>
                            </section>
                        </div>
                    </div>
                </div>
                <!-- 拖拽分隔線 -->
                <div class="rule-splitter" :class="{dragging:splitterDragging}" @mousedown="startSplitterDrag" aria-hidden="true"><i class="bi bi-grip-vertical"></i></div>
                <!-- 右側：規則命中後的處理方式與完整結果設定 -->
                <div class="rule-right">
                    <div class="result-primary-row">
                        <div class="rule-pane-heading">
                            <span class="rule-pane-heading-icon"><i class="bi bi-sign-turn-right"></i></span>
                            <span class="rule-pane-title-row">
                                <strong>{{t('modal.ruleResult')}}</strong>
                                <button type="button" class="help-tooltip tooltip-align-start" :data-tooltip="t('modal.ruleResultPaneHint')" :aria-label="t('modal.ruleResult') + '：' + t('modal.ruleResultPaneHint')" @keydown.esc="$event.currentTarget.blur()">
                                    <i class="bi bi-question-circle" aria-hidden="true"></i>
                                </button>
                            </span>
                        </div>
                        <fieldset v-if="form.protocol==='HTTP'" class="result-action-selector">
                            <legend class="visually-hidden">{{t('modal.httpRuleAction')}}</legend>
                            <div class="rule-outcome-options" role="radiogroup" :aria-label="t('modal.httpRuleAction')">
                                <label class="rule-outcome-option" :class="{'is-selected':form.action!=='FORWARD'}">
                                    <input type="radio" name="ruleAction" value="MOCK" v-model="form.action">
                                    <span class="rule-outcome-copy">
                                        <strong><i class="bi bi-reply" aria-hidden="true"></i>{{t('modal.mockResponseAction')}}</strong>
                                    </span>
                                </label>
                                <label class="rule-outcome-option" :class="{'is-selected':form.action==='FORWARD'}">
                                    <input type="radio" name="ruleAction" value="FORWARD" v-model="form.action" @change="form.sseEnabled=false">
                                    <span class="rule-outcome-copy">
                                        <strong><i class="bi bi-box-arrow-up-right" aria-hidden="true"></i>{{t('modal.forwardAction')}}</strong>
                                    </span>
                                </label>
                            </div>
                        </fieldset>
                    </div>
                    <template v-if="form.protocol==='HTTP' && form.action==='FORWARD'">
                        <div class="form-block forward-settings">
                            <div class="form-block-header"><i class="bi bi-hdd-network"></i> {{t('modal.forwardTarget')}}</div>
                            <div class="form-group">
                                <label class="form-label" for="httpForwardConnection">{{t('modal.forwardConnection')}}</label>
                                <select id="httpForwardConnection" class="form-control" v-model="forwardSelection" :class="{'is-invalid':formErrors.httpTargetConnectionId}">
                                    <option value="ORIGINAL_HOST">{{t('modal.forwardOriginalHost')}}</option>
                                    <option value="DEFAULT_CONNECTION" :disabled="!defaultHttpTargetConnection">{{t('modal.forwardDefaultConnection')}}{{defaultHttpTargetConnection ? ' · '+defaultHttpTargetConnection.name : ' · '+t('modal.forwardDefaultMissing')}}</option>
                                    <option v-for="target in availableHttpTargetConnections" :key="target.id" :value="'CONNECTION:'+target.id" :disabled="!target.enabled">
                                        {{target.name}} · {{target.baseUrl}}{{target.enabled ? '' : ' · '+t('modal.connectionDisabled')}}
                                    </option>
                                </select>
                                <div v-if="formErrors.httpTargetConnectionId" class="invalid-feedback" style="display:block">{{formErrors.httpTargetConnectionId}}</div>
                            </div>
                            <dl class="forward-summary" aria-live="polite">
                                <div class="forward-summary-row">
                                    <dt>{{t('modal.forwardMatchedRequest')}}</dt>
                                    <dd><code>{{forwardSourceSummary}}</code></dd>
                                </div>
                                <div class="forward-summary-row">
                                    <dt>{{t('modal.forwardUsesConnection')}}</dt>
                                    <dd>{{forwardTargetLabel}}</dd>
                                </div>
                                <div class="forward-summary-row">
                                    <dt>{{t('modal.forwardDestination')}}</dt>
                                    <dd><code>{{forwardDestinationUrl}}</code></dd>
                                </div>
                                <div v-if="selectedForwardTarget" class="forward-summary-row">
                                    <dt>{{t('modal.httpsVerification')}}</dt>
                                    <dd>{{selectedForwardTarget.tlsVerificationEnabled ? t('settings.tlsModeStrict') : t('settings.tlsModeCompatibility')}}</dd>
                                </div>
                            </dl>
                            <details class="result-advanced-disclosure" :open="forwardAdvancedOpen" @toggle="setForwardAdvancedOpen">
                                <summary>
                                    <span class="result-advanced-summary-icon"><i class="bi bi-sliders" aria-hidden="true"></i></span>
                                    <span class="result-advanced-summary-copy">
                                        <strong>{{t('modal.advancedSettings')}}</strong>
                                        <small>{{forwardAdvancedSummary}}</small>
                                    </span>
                                    <i class="bi bi-chevron-down result-advanced-chevron" aria-hidden="true"></i>
                                </summary>
                                <div class="result-advanced-content">
                                    <div class="result-delay-group">
                                        <span class="result-advanced-field-title">{{t('modal.forwardDelay')}}</span>
                                        <div class="result-delay-inputs">
                                            <div class="form-group">
                                                <label class="visually-hidden" for="forwardDelayMs">{{t('modal.delayMinimum')}}</label>
                                                <div class="input-affix" :class="{'is-invalid':formErrors.delayMs}">
                                                    <span class="input-affix-prefix" aria-hidden="true">Min</span>
                                                    <input id="forwardDelayMs" type="number" class="form-control" v-model.number="form.delayMs" min="0" placeholder="0">
                                                    <span class="input-affix-postfix" aria-hidden="true">ms</span>
                                                </div>
                                                <div v-if="formErrors.delayMs" class="invalid-feedback result-field-error">{{formErrors.delayMs}}</div>
                                            </div>
                                            <div class="form-group">
                                                <label class="visually-hidden" for="forwardMaxDelayMs">{{t('modal.delayMaximum')}}</label>
                                                <div class="input-affix" :class="{'is-invalid':formErrors.maxDelayMs}">
                                                    <span class="input-affix-prefix" aria-hidden="true">Max</span>
                                                    <input id="forwardMaxDelayMs" type="number" class="form-control" v-model.number="form.maxDelayMs" min="0" :placeholder="t('modal.delayFixedHint')">
                                                    <span class="input-affix-postfix" aria-hidden="true">ms</span>
                                                </div>
                                                <div v-if="formErrors.maxDelayMs" class="invalid-feedback result-field-error">{{formErrors.maxDelayMs}}</div>
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            </details>
                            <div class="page-context-note forward-behavior-note">
                                <i class="bi bi-info-circle"></i>
                                <span>{{(form.forwardTargetMode||'ORIGINAL_HOST')==='ORIGINAL_HOST' ? t('modal.forwardOriginalHostRequired') : t('modal.forwardNoMockHint')}}</span>
                            </div>
                        </div>
                    </template>
                    <template v-else>
                    <!-- 回應模式 + 統一選擇器 -->
                    <div class="form-block mock-result-settings" data-tour="response">
                        <div class="response-mode-toolbar">
                            <span class="response-mode-label"><i class="bi bi-arrow-left-right" aria-hidden="true"></i>{{t('modal.responseMode')}}</span>
                            <div class="protocol-switch">
                                <button type="button" class="protocol-btn" :class="{active:form.responseMode==='new'}" :aria-pressed="form.responseMode==='new'" @click="form.responseMode='new';$emit('on-response-mode-change')">
                                    <i class="bi bi-file-earmark-plus"></i> {{t('modal.createNewResponse')}}
                                </button>
                                <button type="button" class="protocol-btn" :class="{active:form.responseMode==='existing'}" :aria-pressed="form.responseMode==='existing'" @click="form.responseMode='existing';$emit('on-response-mode-change')">
                                    <i class="bi bi-collection"></i> {{t('modal.useExisting')}}
                                </button>
                            </div>
                            <div v-if="form.protocol==='HTTP'" class="response-status-field">
                                <label for="ruleStatus">{{t('modal.statusCode')}}</label>
                                <input id="ruleStatus" type="number" class="form-control" v-model.number="form.status" min="100" max="599" :class="{'is-invalid':formErrors.status}">
                                <div v-if="formErrors.status" class="invalid-feedback result-field-error">{{formErrors.status}}</div>
                            </div>
                            <div v-if="form.responseMode==='new' && form.protocol==='HTTP' && !form.sseEnabled" class="response-template-actions">
                                <span><i class="bi bi-lightning-charge"></i> {{t('modal.template')}}</span>
                                <button type="button" class="btn btn-xs btn-secondary" @click="$emit('apply-template','json')">JSON</button>
                                <button type="button" class="btn btn-xs btn-secondary" @click="$emit('apply-template','xml')">XML</button>
                                <button type="button" class="btn btn-xs btn-secondary" @click="$emit('apply-template','text')">Text</button>
                            </div>
                        </div>
                        <div v-if="form.responseMode==='new'" class="mock-core-fields">
                            <div class="form-group result-description-field">
                                <label class="form-label" for="ruleResponseDescription">{{t('modal.responseDescription')}}</label>
                                <input id="ruleResponseDescription" class="form-control" v-model="form.responseDescription" :placeholder="t('modal.responseDescription')" maxlength="255">
                            </div>
                        </div>
                        <div v-if="form.responseMode==='existing'" class="response-existing-panel" :class="{'has-selection':form.responseId}">
                            <!-- 搜尋與篩選 -->
                            <div class="response-select-wrapper">
                                <div class="response-picker-heading">
                                    <label for="ruleResponsePickerInput">{{t('modal.responsePickerLabel')}}</label>
                                    <span>{{t('modal.responsesAvailable', {count: filteredResponsePicker.length})}}</span>
                                </div>
                                <div class="response-picker-control" :class="{'is-open':responseDropdownOpen}">
                                    <i class="bi bi-search response-picker-search-icon" aria-hidden="true"></i>
                                    <div class="response-picker-filters" role="group" :aria-label="t('modal.responseTypeFilter')">
                                        <button type="button" class="response-picker-filter" :class="{'is-selected':!responsePickerSseOnly}" :aria-pressed="!responsePickerSseOnly" @click="$emit('update:response-picker-sse-only',false)">{{t('modal.all')}}</button>
                                        <button type="button" class="response-picker-filter" :class="{'is-selected':responsePickerSseOnly}" :aria-pressed="responsePickerSseOnly" @click="$emit('update:response-picker-sse-only',true)">SSE</button>
                                    </div>
                                    <input id="ruleResponsePickerInput" ref="responsePickerInput" class="response-picker-input" role="combobox" aria-autocomplete="list" aria-controls="ruleResponsePickerList" :aria-expanded="responseDropdownOpen" :aria-activedescendant="responseDropdownOpen && responsePickerActiveIndex >= 0 ? responseOptionId(filteredResponsePicker[responsePickerActiveIndex]) : undefined" :value="responsePickerSearch" @input="onResponseSearchInput" @focus="openResponsePicker" @click="openResponsePicker" @keydown="onResponsePickerKeydown" :placeholder="t('modal.searchResponse')">
                                    <button v-if="responsePickerSearch" type="button" class="response-picker-clear" @click="clearResponseSearch" :aria-label="t('modal.clearSearch')" :title="t('modal.clearSearch')"><i class="bi bi-x-lg" aria-hidden="true"></i></button>
                                    <i class="bi bi-chevron-down response-picker-chevron" :class="{'is-open':responseDropdownOpen}" aria-hidden="true"></i>
                                </div>
                                <div v-if="responseDropdownOpen" id="ruleResponsePickerList" class="response-dropdown" role="listbox" :aria-label="t('modal.responsePickerResults')">
                                    <div v-for="(r,index) in filteredResponsePicker" :id="responseOptionId(r)" :key="r.id" role="option" :aria-selected="form.responseId===r.id" @mouseenter="responsePickerActiveIndex=index" @mousedown.prevent="selectResponse(r)" class="response-dropdown-item" :class="{selected:form.responseId===r.id,active:responsePickerActiveIndex===index}">
                                        <div class="response-dropdown-row1">
                                            <i class="bi response-dropdown-check" :class="form.responseId===r.id?'bi-check-lg':'bi-circle'" aria-hidden="true"></i>
                                            <span class="response-dropdown-desc">{{r.description||t('responses.noDescription')}}</span>
                                            <span v-if="r.contentType==='SSE'" class="response-type-label">SSE</span>
                                        </div>
                                        <div class="response-dropdown-row2">
                                            <code>#{{r.id}}</code>
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
                                <div class="response-selected-content">
                                    <span class="response-selected-label"><i class="bi bi-check-circle-fill" aria-hidden="true"></i>{{t('modal.currentSelection')}}</span>
                                    <strong class="response-selected-desc">{{selectedResponse?.description||t('responses.noDescription')}}</strong>
                                    <div class="response-selected-meta">
                                        <code>#{{form.responseId}}</code>
                                        <span>{{selectedResponse?.contentType==='SSE' ? 'SSE' : t('modal.responseTypeGeneral')}}</span>
                                        <span>{{fmtSize(selectedResponse?.bodySize||0)}}</span>
                                        <span v-if="selectedResponse?.usageCount">{{t('modal.rulesUsing', {count: selectedResponse.usageCount})}}</span>
                                    </div>
                                    <span v-if="(selectedResponse?.usageCount||previewResponseUsageCount) > 1" class="response-shared-warning" :title="t('modal.modifyAffectsAll')"><i class="bi bi-exclamation-triangle" aria-hidden="true"></i>{{t('modal.sharedCount', {count: selectedResponse?.usageCount||previewResponseUsageCount})}}</span>
                                    <span v-if="form.sseEnabled && selectedResponse?.contentType!=='SSE'" class="response-shared-warning" :title="t('modal.notSseTooltip')"><i class="bi bi-exclamation-triangle" aria-hidden="true"></i>{{t('modal.notSse')}}</span>
                                </div>
                                <div class="response-selected-actions">
                                    <button type="button" class="btn btn-sm btn-icon btn-secondary" @click="$emit('go-to-responses',form.responseId)" :title="t('modal.goToResponseManagement')" :aria-label="t('modal.goToResponseManagement')"><i class="bi bi-box-arrow-up-right"></i></button>
                                    <button type="button" class="btn btn-sm btn-icon btn-secondary" @click="$emit('clear-response-selection')" :title="t('modal.clearSelection')" :aria-label="t('modal.clearSelection')"><i class="bi bi-x-lg"></i></button>
                                </div>
                            </div>
                        </div>
                        <details class="result-advanced-disclosure" :open="mockAdvancedOpen" @toggle="setMockAdvancedOpen">
                            <summary>
                                <span class="result-advanced-summary-icon"><i class="bi bi-sliders" aria-hidden="true"></i></span>
                                <span class="result-advanced-summary-copy">
                                    <strong>{{t('modal.advancedSettings')}}</strong>
                                    <small>{{mockAdvancedSummary}}</small>
                                </span>
                                <i class="bi bi-chevron-down result-advanced-chevron" aria-hidden="true"></i>
                            </summary>
                            <div class="result-advanced-content" :class="{'has-headers':form.protocol==='HTTP'}">
                                <div class="result-delay-group">
                                    <span class="result-advanced-field-title">{{t('modal.responseDelay')}}</span>
                                    <div class="result-delay-inputs">
                                        <div class="form-group">
                                            <label class="visually-hidden" for="mockDelayMs">{{t('modal.delayMinimum')}}</label>
                                            <div class="input-affix" :class="{'is-invalid':formErrors.delayMs}">
                                                <span class="input-affix-prefix" aria-hidden="true">Min</span>
                                                <input id="mockDelayMs" type="number" class="form-control" v-model.number="form.delayMs" min="0" placeholder="0">
                                                <span class="input-affix-postfix" aria-hidden="true">ms</span>
                                            </div>
                                            <div v-if="formErrors.delayMs" class="invalid-feedback result-field-error">{{formErrors.delayMs}}</div>
                                        </div>
                                        <div class="form-group">
                                            <label class="visually-hidden" for="mockMaxDelayMs">{{t('modal.delayMaximum')}}</label>
                                            <div class="input-affix" :class="{'is-invalid':formErrors.maxDelayMs}">
                                                <span class="input-affix-prefix" aria-hidden="true">Max</span>
                                                <input id="mockMaxDelayMs" type="number" class="form-control" v-model.number="form.maxDelayMs" min="0" :placeholder="t('modal.delayFixedHint')">
                                                <span class="input-affix-postfix" aria-hidden="true">ms</span>
                                            </div>
                                            <div v-if="formErrors.maxDelayMs" class="invalid-feedback result-field-error">{{formErrors.maxDelayMs}}</div>
                                        </div>
                                    </div>
                                </div>
                                <div v-if="form.protocol==='HTTP'" class="result-headers-field">
                                    <span class="result-advanced-field-title">{{t('modal.responseHeaders')}}</span>
                                    <div class="meta-tags">
                                        <span v-for="(v,k) in parseHeaders(form.responseHeaders)" :key="k" class="tag-chip">
                                            <span class="tag-key">{{k}}</span><span class="tag-val">{{v}}</span>
                                            <button type="button" class="tag-chip-remove" @click="$emit('remove-header',k)" :aria-label="t('modal.removeResponseHeader', {key:k})" :title="t('modal.removeResponseHeader', {key:k})"><i class="bi bi-x" aria-hidden="true"></i></button>
                                        </span>
                                        <div class="tag-add-inline">
                                            <input v-model="newHeader.key" placeholder="Header" :aria-label="t('modal.responseHeaderName')" @keyup.enter="$emit('add-header')">
                                            <span class="tag-sep">:</span>
                                            <input v-model="newHeader.value" :placeholder="t('modal.condPlaceholderValue')" :aria-label="t('modal.responseHeaderValue')" @keyup.enter="$emit('add-header')">
                                            <button type="button" @click="$emit('add-header')" :aria-label="t('modal.addResponseHeader')" :title="t('modal.addResponseHeader')"><i class="bi bi-plus"></i></button>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </details>
                    </div>
                    <!-- 回應內容 -->
                    <div class="form-block response-content-block">
                        <!-- SSE 表格編輯器 -->
                        <div v-if="form.sseEnabled && form.protocol==='HTTP'" class="sse-editor-wrap rule-sse-editor">
                            <div class="rule-sse-toolbar">
                                <span class="response-content-label"><i class="bi bi-broadcast" aria-hidden="true"></i>{{t('modal.sseEditor')}}</span>
                                <div class="form-check form-switch rule-sse-loop">
                                    <input class="form-check-input" type="checkbox" id="sseLoopToggle" v-model="form.sseLoopEnabled">
                                    <label class="form-check-label" for="sseLoopToggle"><i class="bi bi-arrow-repeat" aria-hidden="true"></i>{{t('modal.sseLoopMode')}}</label>
                                </div>
                            </div>
                            <div class="sse-table rule-sse-table">
                                <table :aria-label="t('modal.sseEventsTableLabel')">
                                    <thead>
                                        <tr>
                                            <th class="rule-sse-drag-column"><span class="visually-hidden">{{t('modal.actions')}}</span></th>
                                            <th class="rule-sse-type-column">{{t('modal.sseEventType')}}</th>
                                            <th class="rule-sse-name-column">{{t('modal.sseEventName')}}</th>
                                            <th>{{t('modal.sseEventData')}} <span class="text-danger" aria-hidden="true">*</span></th>
                                            <th class="rule-sse-id-column">{{t('modal.sseEventId')}}</th>
                                            <th class="rule-sse-delay-column">{{t('modal.sseEventDelay')}}</th>
                                            <th class="rule-sse-action-column">{{t('modal.actions')}}</th>
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
                                            <td class="sse-drag-handle">
                                                <i class="bi bi-grip-vertical" aria-hidden="true"></i>
                                            </td>
                                            <td>
                                                <select class="form-select form-select-sm" v-model="evt.type" :aria-label="t('modal.sseFieldLabel', {index:idx+1, field:t('modal.sseEventType')})">
                                                    <option value="normal">{{t('modal.sseTypeNormal')}}</option>
                                                    <option value="error">{{t('modal.sseTypeError')}}</option>
                                                    <option value="abort">{{t('modal.sseTypeAbort')}}</option>
                                                </select>
                                            </td>
                                            <td>
                                                <input class="form-control form-control-sm" v-model="evt.event" :placeholder="t('modal.sseEventNamePlaceholder')" :aria-label="t('modal.sseFieldLabel', {index:idx+1, field:t('modal.sseEventName')})">
                                            </td>
                                            <td>
                                                <textarea class="form-control form-control-sm rule-sse-data" v-model="evt.data" :placeholder="t('modal.sseEventDataPlaceholder')" rows="2" :aria-label="t('modal.sseFieldLabel', {index:idx+1, field:t('modal.sseEventData')})"></textarea>
                                            </td>
                                            <td>
                                                <input class="form-control form-control-sm" v-model="evt.id" :placeholder="t('modal.sseEventIdPlaceholder')" :aria-label="t('modal.sseFieldLabel', {index:idx+1, field:t('modal.sseEventId')})">
                                            </td>
                                            <td>
                                                <div class="input-affix rule-sse-delay"><input class="form-control form-control-sm" type="number" v-model.number="evt.delayMs" min="0" max="30000" placeholder="0" :aria-label="t('modal.sseFieldLabel', {index:idx+1, field:t('modal.sseEventDelay')})"><span class="input-affix-postfix">{{t('modal.millisecondsShort')}}</span></div>
                                            </td>
                                            <td class="rule-sse-action-cell">
                                                <button type="button" class="btn btn-sm btn-icon btn-secondary" @click="$emit('remove-sse-event',idx)" :disabled="sseEvents.length<=1" :title="t('modal.deleteSseEventAt', {index:idx+1})" :aria-label="t('modal.deleteSseEventAt', {index:idx+1})">
                                                    <i class="bi bi-trash" aria-hidden="true"></i>
                                                </button>
                                            </td>
                                        </tr>
                                    </tbody>
                                </table>
                            </div>
                            <div class="rule-sse-actions">
                                <button type="button" class="btn btn-sm btn-secondary" @click="$emit('add-sse-event')">
                                    <i class="bi bi-plus-lg" aria-hidden="true"></i>{{t('modal.addSseEvent')}}
                                </button>
                                <span class="sub-info">{{t('modal.sseEventCount', {count: sseEvents.length})}}</span>
                            </div>
                            <div v-if="ssePreview" class="sse-preview rule-sse-preview">
                                <div class="rule-sse-preview-title"><i class="bi bi-eye" aria-hidden="true"></i>{{t('modal.sseStreamPreview')}}</div>
                                <pre>{{ssePreview}}</pre>
                            </div>
                            <div v-if="formErrors.responseBody" class="invalid-feedback rule-sse-error">{{formErrors.responseBody}}</div>
                        </div>
                        <!-- 使用現有回應 (非 SSE) -->
                        <div v-else-if="form.responseMode==='existing'" class="response-content-mode">
                            <div class="preview-toolbar">
                                <span class="response-content-label"><i class="bi bi-code-square"></i>{{t('modal.responseContent')}}</span>
                                <template v-if="form.responseId && !previewResponseLoading && !previewResponseLoadFailed">
                                    <button type="button" class="btn btn-xs" :class="previewEditing?'btn-warning':'btn-secondary'" @click="$emit('toggle-preview-editing')" :title="previewEditing ? t('modal.cancelEdit') : t('modal.editResponse2')">
                                        <i class="bi" :class="previewEditing?'bi-x-lg':'bi-pencil'"></i>
                                        {{previewEditing ? t('modal.cancelEdit') : ((selectedResponse?.usageCount||previewResponseUsageCount) > 1 ? t('modal.editSharedResponse') : t('modal.editResponse2'))}}
                                    </button>
                                    <span v-if="previewEditing && previewResponseUsageCount > 1" class="badge badge-warning" style="font-size:10px" :title="t('modal.modifyAffectsAll')">
                                        <i class="bi bi-exclamation-triangle"></i> {{t('modal.sharedWarning', {count: previewResponseUsageCount})}}
                                    </span>
                                    <button type="button" class="btn btn-xs btn-secondary" @click="$emit('toggle-preview-format')">
                                        <i class="bi" :class="previewFormatted?'bi-code':'bi-braces'"></i>
                                        {{previewFormatted ? t('modal.plainText') : t('modal.format')}}
                                        <span v-if="previewResponseBody.length>512000" class="text-warning">{{t('modal.largeFile')}}</span>
                                    </button>
                                    <span v-if="!previewResponseBody.length" class="response-body-state"><i class="bi bi-file-earmark" aria-hidden="true"></i>{{t('modal.emptyResponseBody')}}</span>
                                    <span class="response-body-size">{{fmtSize(previewResponseBody.length)}}</span>
                                    <button v-if="previewEditing" type="button" class="btn btn-xs btn-primary" @click="$emit('save-preview-response')" :disabled="previewSaving" style="margin-left:auto">
                                        <i class="bi" :class="previewSaving?'bi-hourglass-split':'bi-check-lg'"></i> {{t('modal.saveResponse')}}
                                    </button>
                                </template>
                            </div>
                            <div v-if="form.responseId && previewResponseLoading" class="response-preview-loading"><i class="bi bi-hourglass-split spin"></i> {{t('modal.loadingResponse')}}</div>
                            <div v-else-if="form.responseId && previewResponseLoadFailed" class="response-preview-empty response-preview-error"><i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>{{t('modal.responseLoadFailed')}}</span></div>
                            <div v-else-if="form.responseId" class="response-preview-wrap">
                                <div id="rulePreviewEditor" class="preview-editor" :class="{editing:previewEditing}"></div>
                            </div>
                            <div v-else class="response-preview-empty">{{t('modal.selectResponse')}}</div>
                        </div>
                        <!-- 建立新回應 (非 SSE) -->
                        <div v-else class="response-content-mode">
                            <div class="edit-toolbar">
                                <span class="response-content-label"><i class="bi bi-code-square"></i>{{t('modal.responseContent')}}</span>
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
                    </template>
                </div>
            </div>
            <slot v-else name="declarative"></slot>
            <div v-if="editorMode==='form' && showCatchAllWarning" class="catch-all-warning">
                <i class="bi bi-exclamation-triangle-fill"></i>
                <span>{{t('modal.catchAllWarning')}}</span>
                <label class="catch-all-confirm-label">
                    <input type="checkbox" :checked="catchAllConfirmed" @change="$emit('update:catch-all-confirmed', $event.target.checked)">
                    {{t('modal.catchAllConfirm')}}
                </label>
            </div>
            <div v-if="editorMode==='form'" class="modal-footer" data-tour="save">
                <span v-if="!canSave" class="sub-info" style="margin-right:auto"><i class="bi bi-info-circle"></i> {{t('modal.requiredFieldsHint')}}</span>
                <button class="btn btn-secondary" @click="$emit('close')">{{t('modal.cancel')}}</button>
                <button class="btn btn-secondary" @click="$emit('save',false)" :disabled="!canSave||saving"><i class="bi" :class="saving?'bi-arrow-clockwise spin':'bi-floppy'"></i> {{t('modal.save')}}</button>
                <button class="btn btn-primary" @click="$emit('save',true)" :disabled="!canSave||saving"><i class="bi" :class="saving?'bi-arrow-clockwise spin':'bi-check2-circle'"></i> {{t('modal.saveAndClose')}}</button>
            </div>
        </div>
    </div>
    `
};
