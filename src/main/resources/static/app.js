const _app = createApp({
    setup() {
        const jmsEnabled = ref(false), sidebarCollapsed = ref(false), mobileMenu = ref(false);
        const status = ref(null);
        const httpAlias = ref('HTTP');
        const jmsAlias = ref('JMS');
        const httpLabel = computed(() => httpAlias.value && httpAlias.value !== 'HTTP' ? `HTTP (${httpAlias.value})` : 'HTTP');
        const jmsLabel = computed(() => jmsAlias.value && jmsAlias.value !== 'JMS' ? `JMS (${jmsAlias.value})` : 'JMS');
        const envLabel = ref('');
        const loading = ref({ rules: false, logs: false, audit: false, responses: false, backup: false, status: false, accounts: false });
        const backupStatus = ref(null);
        const page = ref('rules');

        // === 基礎 composables（必須最先初始化，其他 composable 依賴這些） ===
        // i18n 國際化（useI18n composable）
        const { locale, messages, t, switchLocale, loadLocale } = useI18n();
        _t = t;
        // Toast 通知與確認對話框（useToast composable）
        const { toasts, showToast, dismissToast, confirmState, showConfirm } = useToast(t);
        _showToast = showToast;
        // 認證與權限管理（useAuth composable）
        const { isAdmin, isLoggedIn, login, logout, requireLogin } = useAuth(showConfirm, t);

        // === 獨立工具 composables ===
        const themeCtx = useTheme(t);
        const { theme, toggleTheme, themeIcon, themeLabel } = themeCtx;

        // Density (compact / normal / comfortable)
        const density = ref(localStorage.getItem('echo_density') || 'normal');
        const applyDensity = () => { document.documentElement.dataset.density = density.value; };
        const toggleDensity = () => {
            density.value = density.value === 'compact' ? 'normal' : density.value === 'normal' ? 'comfortable' : 'compact';
            localStorage.setItem('echo_density', density.value);
            applyDensity();
        };
        const densityIcon = computed(() => density.value === 'compact' ? 'bi-arrows-collapse' : density.value === 'comfortable' ? 'bi-arrows-expand' : 'bi-arrows');
        const densityLabel = computed(() => {
            const labels = { compact: t('density.compact'), normal: t('density.normal'), comfortable: t('density.comfortable') };
            return labels[density.value] || density.value;
        });

        // Tour (interactive onboarding)
        const tourCtx = useTour({ t });
        const { tourActive, tourStep, helpSeen, startTour: _startTour, nextStep: _nextStep, prevStep: _prevStep, skipTour, tourSteps } = tourCtx;
        const startTour = () => { openCreate(); setTimeout(() => _startTour(), 400); };
        const tourHighlightStyle = ref({ display: 'none' });
        const tourTooltipStyle = ref({ display: 'none' });
        const updateTourPosition = () => {
            if (!tourActive.value) { tourHighlightStyle.value = { display: 'none' }; tourTooltipStyle.value = { display: 'none' }; return; }
            const step = tourSteps.value[tourStep.value];
            if (!step) { return; }
            const el = document.querySelector(step.target);
            if (!el) { tourHighlightStyle.value = { display: 'none' }; tourTooltipStyle.value = { display: 'none' }; return; }
            const rect = el.getBoundingClientRect();
            tourHighlightStyle.value = { top: (rect.top - 4) + 'px', left: (rect.left - 4) + 'px', width: (rect.width + 8) + 'px', height: (rect.height + 8) + 'px' };
            const below = rect.bottom + 12;
            const above = rect.top - 12;
            if (below + 160 < window.innerHeight) {
                tourTooltipStyle.value = { top: below + 'px', left: Math.max(8, rect.left) + 'px' };
            } else {
                tourTooltipStyle.value = { bottom: (window.innerHeight - above) + 'px', left: Math.max(8, rect.left) + 'px' };
            }
        };
        const nextStep = () => { _nextStep(); Vue.nextTick(updateTourPosition); };
        const prevStep = () => { _prevStep(); Vue.nextTick(updateTourPosition); };
        watch(tourActive, (v) => { if (v) { Vue.nextTick(updateTourPosition); } });
        watch(tourStep, () => { Vue.nextTick(updateTourPosition); });
        // CodeMirror (useEditor composable)
        const editorCtx = useEditor(t);
        const { editors, previewFormatted, editFormatted, responseFormFormatted, previewEditorRef, editEditorRef, responseFormEditorRef, renderEditor, detectMode, cleanupEditors } = editorCtx;

        // === 資料 composables ===
        // 請求記錄統計（useStats composable）
        const statsCtx = useStats({ showToast, t, loading, httpLabel, jmsLabel });
        const { logs, logSummary, logFilter, logSort, logPage, logPageSize, pagedLogs, totalPages, toggleSort, sortIcon, onPageSizeChange, loadLogs, debouncedLoadLogs, autoRefresh, toggleAutoRefresh, stopAutoRefresh, toggleMatchChain, logDetailExpanded, toggleLogDetail, logFilterChips, removeLogChip, clearLogFilters, cleanupStats } = statsCtx;
        // 修訂記錄（useAudit composable）
        const auditCtx = useAudit({ showToast, showConfirm, t, requireLogin, loading });
        const { auditLogs, selectedAudit, auditFilter, auditSort, auditPage, auditPageSize, filteredAudit: _filteredAudit, sortedAudit: _sortedAudit, pagedAudit, auditTotalPages, auditTruncated, toggleAuditSort, auditSortIcon, onAuditPageSizeChange, loadAudit, debouncedLoadAudit, deleteAllAuditLogs, formatAuditJson, getAuditChanges, getAuditTarget, getAuditDescription, getAuditProtocol, getAuditChangeCount, auditFilterChips, removeAuditChip, clearAuditFilters } = auditCtx;

        // === 業務 composables ===
        // 規則管理（useRules composable）
        const rulesCtx = useRules({ showToast, showConfirm, t, requireLogin, login, isLoggedIn, loading, page, httpLabel, jmsLabel, openEdit: r => openEdit(r) });
        const { rules, ruleFilter, ruleSort, rulePage, rulePageSize, filteredRules, pagedRules, ruleTotalPages, toggleRuleSort, ruleSortIcon, selectedRules, batchSelectMode, toggleSelectAll, ruleViewMode, expandedTagGroups, toggleTagGroup, expandedTagSubgroups, toggleTagSubgroup, tagKeys, rulesByTag, rulesByTagGroup, groupVisibleLimit, getGroupLimit, showMoreGroup, showAllGroup, loadRules, deleteRule, extendRule, toggleEnabled, exportRules, batchProtect, deleteSelectedRules, deleteAllRules, showImportModal, importFormat, importFile, importFileName, handleImportFile, doImport, showOpenApiPreview, openApiPreviewTitle, openApiPreviewVersion, openApiPreviewRules, openApiImporting, confirmOpenApiImport, rulePreviewCache, rulePreviewExpanded, rulePreviewLoading, toggleRulePreview, handleRuleRowClick, ruleFilterChips, removeRuleChip, clearRuleFilters, showPriorityHelp, helpTab, clipCopy, exportRuleJson, goToRule, showDataDropdown, toggleDataDropdown, closeDataDropdown, triggerResponseImport } = rulesCtx;
        // 回應管理（useResponses composable）
        const responsesCtx = useResponses({ showToast, showConfirm, t, requireLogin, loading, page, onRulesDirty: () => { rulesCtx.markDirty(); loadRules(true); }, onResponseSaved: () => { rulePreviewCache.value = {}; } });
        const { responseSummary, responseFilter, responseSort, responsePage, responsePageSize, filteredResponseSummary, pagedResponseSummary, responseTotalPages, toggleResponseSort, responseSortIcon, onResponsePageSizeChange, showResponseModal, editingResponse, responseForm, responseSseEvents, loadResponseSummary, openResponseModal, saveResponse, deleteResponse, selectedResponses, batchSelectResponseMode, toggleSelectAllResponses, exportResponses, importResponses, deleteSelectedResponses, deleteAllResponses, toggleResponseRules, responseUsageFilter: responseUsageFilter, responseContentTypeFilter, responseFilterChips, removeResponseChip, clearResponseFilters, goToResponse, showResponseDataDropdown, toggleResponseDataDropdown, closeResponseDataDropdown, triggerResponseImport2, extendResponse, deleteOrphanResponses } = responsesCtx;
        // 規則表單（useRuleForm composable）
        const ruleFormCtx = useRuleForm({ showToast, showConfirm, t, requireLogin, login, loadRules, rulePreviewCache, rulePreviewExpanded, rulesMarkDirty: rulesCtx.markDirty, loadResponseSummary, responseSummary, responsesMarkDirty: responsesCtx.markDirty, responseSseEvents, renderEditor, editEditorRef, editFormatted, previewEditorRef, previewFormatted, responseFormEditorRef, responseFormFormatted, detectMode, editors, jmsEnabled });
        const { showModal, editing, form, conditions, conditionsExpanded, formErrors, saving, canSave, validateForm, showCatchAllWarning, catchAllConfirmed, showBodyConditionWarning, sseEvents, addSseEvent, removeSseEvent, ssePreview, setProtocol, openCreate, copyRule, createFromLog, openEdit, closeModal, saveRule, onResponseModeChange, testExpanded, testParams, testResult, testLoading, testSseEvents, testSseMode, runTest, stopSseTest, generateTestData, previewResponseId, previewResponseBody, previewEditing, previewEditBody, previewResponseUsageCount, previewSaving, togglePreviewEditing, savePreviewResponse, responsePickerSearch, responseDropdownOpen, filteredResponsePicker, responsePickerSseOnly, newTag, addTag, removeTag, newHeader, addHeader, removeHeader, responseSsePreview, ruleModalMaximized, responseModalMaximized, togglePreviewFormat, toggleEditFormat, setupFormWatchers, closeResponseDropdown } = ruleFormCtx;

        // === 帳號管理 composable ===
        const accountsCtx = useAccounts({ showToast, showConfirm, t, requireLogin, login, loading });
        const { accounts, searchKeyword: accountSearchKeyword, filteredAccounts, loadAccounts, createAccount, deleteAccount, enableAccount, disableAccount, resetPassword } = accountsCtx;

        // === 路由（依賴 filters） ===
        const loadBackupStatus = async () => {
            const r = await apiCall('/api/admin/backup/status', {}, { silent: true });
            if (r && r.ok) { backupStatus.value = await r.json(); }
        };

        // === app.js 專屬邏輯 ===
        const autoResize = e => { const el = e.target; el.style.height = 'auto'; el.style.height = Math.min(el.scrollHeight, 300) + 'px' };
        let statusLastLoaded = 0;
        const loadStatus = async () => {
            if (loading.value.status) { return; }
            loading.value.status = true;
            const r = await apiCall('/api/admin/status', {}, { silent: true });
            if (r && r.ok) {
                const data = await r.json();
                status.value = data;
                jmsEnabled.value = data.jmsEnabled;
                isAdmin.value = data.isAdmin === true;
                isLoggedIn.value = data.isLoggedIn === true;
                httpAlias.value = data.httpAlias || 'HTTP';
                jmsAlias.value = data.jmsAlias || 'JMS';
                envLabel.value = data.envLabel || '';
                document.title = envLabel.value ? `Echo - ${envLabel.value}` : 'Echo Mock Server';
                if (data.orphanRules > 0) { showToast(t('toast.orphanRulesWarning', {count: data.orphanRules}), 'error'); }
            }
            setTimeout(() => { loading.value.status = false; }, 2000);
        };

        // === 強制改密碼 Modal ===
        const showForceChangePassword = ref(false);
        const forceChangePwdForm = ref({ oldPassword: '', newPassword: '' });
        const forceChangePwdError = ref('');
        const forceChangePwdSubmitting = ref(false);
        const checkForceChangePassword = () => {
            if (status.value && status.value.forceChangePassword === true && status.value.isBuiltinUser === true) {
                showForceChangePassword.value = true;
            }
        };
        const submitForceChangePassword = async () => {
            if (forceChangePwdSubmitting.value) { return; }
            forceChangePwdError.value = '';
            if (!forceChangePwdForm.value.newPassword || forceChangePwdForm.value.newPassword.length < 6) {
                forceChangePwdError.value = t('toast.passwordLengthError');
                return;
            }
            forceChangePwdSubmitting.value = true;
            const r = await apiCall('/api/account/change-password', {
                method: 'PUT',
                body: JSON.stringify({ oldPassword: forceChangePwdForm.value.oldPassword, newPassword: forceChangePwdForm.value.newPassword })
            }, { silent: true });
            forceChangePwdSubmitting.value = false;
            if (r && r.ok) {
                showToast(t('toast.passwordChanged'), 'success');
                showForceChangePassword.value = false;
                forceChangePwdForm.value = { oldPassword: '', newPassword: '' };
                await loadStatus();
            } else if (r) {
                try {
                    const body = await r.json();
                    const code = body.error;
                    const errorCodeMap = { 'OLD_PASSWORD_INCORRECT': 'toast.oldPasswordIncorrect', 'PASSWORD_TOO_SHORT': 'toast.passwordLengthError' };
                    forceChangePwdError.value = (code && errorCodeMap[code]) ? t(errorCodeMap[code]) : (body.error || t('toast.passwordChangeFailed'));
                } catch { forceChangePwdError.value = t('toast.passwordChangeFailed'); }
            }
        };

        // === 路由（依賴 loadStatus, loadBackupStatus, filters） ===
        const routerCtx = useRouter({ page, ruleFilter, responseFilter, logFilter, auditFilter, isAdmin, loadRules: () => loadRules(), loadLogs, loadAudit, loadResponseSummary: () => loadResponseSummary(), loadBackupStatus, loadStatus, loadAccounts, stopAutoRefresh });
        const { applyUrlParams } = routerCtx;
        const triggerBackup = async () => {
            if (!await showConfirm({ title: t('confirm.triggerBackup'), message: t('confirm.triggerBackupMsg') })) return;
            loading.value.backup = true;
            const r = await apiCall('/api/admin/backup', { method: 'POST' }, { errorMsg: t('toast.backupFailed') });
            if (r && r.ok) {
                const d = await r.json();
                let msg = t('toast.backupSuccess');
                if (d.compactBefore != null && d.compactAfter != null) {
                    const fmt = (b) => (b / 1024 / 1024).toFixed(1);
                    msg += ` — ${t('toast.compactResult', { before: fmt(d.compactBefore), after: fmt(d.compactAfter) })}`;
                }
                showToast(msg, 'success');
                loadBackupStatus();
            }
            loading.value.backup = false;
        };

        // === Watchers ===
        routerCtx.setupRouterWatchers();
        setupFormWatchers();
        // 回應編輯 Modal watchers
        watch(showResponseModal, open => {
            responseFormFormatted.value = false;
            if (open && responseForm.value.contentType !== 'sse') renderEditor('responseForm', responseFormEditorRef, responseForm.value.body, false, v => { responseForm.value.body = v; });
        });
        watch(() => responseForm.value.contentType, (ct, oldCt) => {
            if (ct === 'sse' && oldCt === 'text') {
                responseSseEvents.value = deserializeSseEvents(responseForm.value.body);
            } else if (ct === 'text' && oldCt === 'sse') {
                responseForm.value.body = serializeSseEvents(responseSseEvents.value);
                Vue.nextTick(() => renderEditor('responseForm', responseFormEditorRef, responseForm.value.body, false, v => { responseForm.value.body = v; }));
            }
        });
        const toggleResponseFormFormat = () => { responseFormFormatted.value = !responseFormFormatted.value; renderEditor('responseForm', responseFormEditorRef, responseForm.value.body, false, v => { responseForm.value.body = v; }); };
        const handleKeydown = e => {
            const anyModal = showModal.value || showPriorityHelp.value || showResponseModal.value || showImportModal.value || showOpenApiPreview.value || confirmState.value.show || showForceChangePassword.value;
            if (e.key === 'Escape') {
                if (confirmState.value.show) { confirmState.value.onCancel?.(); }
                else if (showModal.value) { closeModal(); }
                else if (showResponseModal.value) { showResponseModal.value = false; }
                else if (showOpenApiPreview.value) { showOpenApiPreview.value = false; }
                else if (showImportModal.value) { showImportModal.value = false; }
                else if (showPriorityHelp.value) { showPriorityHelp.value = false; }
                return;
            }
            const tag = document.activeElement?.tagName;
            if (anyModal || tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || document.activeElement?.isContentEditable) { return; }
            if (e.key === '/') {
                e.preventDefault();
                const searchMap = { rules: 'ruleSearch', stats: 'logSearch', audit: 'auditSearch', responses: 'responseSearch' };
                const el = document.getElementById(searchMap[page.value]);
                if (el) { el.focus(); }
            } else if (e.key === 'ArrowLeft') {
                const pageMap = { rules: rulePage, stats: logPage, audit: auditPage, responses: responsePage };
                const p = pageMap[page.value];
                if (p && p.value > 1) { p.value--; }
            } else if (e.key === 'ArrowRight') {
                const pageMap = { rules: [rulePage, ruleTotalPages], stats: [logPage, totalPages], audit: [auditPage, auditTotalPages], responses: [responsePage, responseTotalPages] };
                const pair = pageMap[page.value];
                if (pair && pair[0].value < pair[1].value) { pair[0].value++; }
            } else if (e.key === 'n') {
                if (page.value === 'rules') { openCreate(); }
                else if (page.value === 'responses') { openResponseModal(); }
            } else if (e.key === '[') {
                sidebarCollapsed.value = !sidebarCollapsed.value;
            }
        };

        onMounted(async () => { 
            themeCtx.applyTheme();
            applyDensity(); 
            await loadLocale(locale.value);
            await loadStatus();
            checkForceChangePassword();
            loadRules(true); 
            applyUrlParams(); 
            if (!sessionStorage.getItem('echo_dblclick_hint_shown') && isLoggedIn.value) { showDblClickHint.value = true; }
            window.addEventListener('hashchange', applyUrlParams); 
            window.addEventListener('click', closeResponseDropdown); 
            window.addEventListener('click', closeDataDropdown);
            window.addEventListener('click', closeResponseDataDropdown);
            window.addEventListener('keydown', handleKeydown);
        });
        onUnmounted(() => { 
            cleanupStats();
            themeCtx.cleanupTheme();
            window.removeEventListener('hashchange', applyUrlParams); 
            window.removeEventListener('click', closeResponseDropdown);
            window.removeEventListener('click', closeDataDropdown);
            window.removeEventListener('click', closeResponseDataDropdown);
            window.removeEventListener('keydown', handleKeydown); 
            debouncedLoadAudit.cancel(); 
            cleanupEditors();
        });
        // 雙擊編輯提示
        const showDblClickHint = ref(false);
        const dismissDblClickHint = () => { showDblClickHint.value = false; sessionStorage.setItem('echo_dblclick_hint_shown', '1'); };

        // 從請求記錄建立規則
        const applyTemplate = (type) => {
            const templates = {
                json: '{\n  "status": "ok",\n  "data": {}\n}',
                xml: '<response>\n  <status>OK</status>\n</response>',
                text: 'OK'
            };
            form.value.responseBody = templates[type] || '';
            Vue.nextTick(() => renderEditor('edit', editEditorRef, form.value.responseBody, false, v => { form.value.responseBody = v; }));
        };
        const createRuleFromLog = async (logEntry) => {
            if (!await requireLogin()) { return; }
            const logId = logEntry.id;
            if (!logId) { showToast(t('toast.operationFailed'), 'error'); return; }
            const r = await apiCall(`/api/admin/logs/${logId}/to-rule`, {}, { silent: true });
            if (r && r.ok) {
                const ruleDto = await r.json();
                createFromLog(ruleDto);
            } else {
                showToast(t('toast.operationFailed'), 'error');
            }
        };

        // 清除全部請求記錄
        const deleteAllLogs = async () => {
            if (!await requireLogin()) { return; }
            if (!await showConfirm({ title: t('confirm.deleteAllLogs'), message: t('confirm.deleteAllLogsMsg'), confirmText: t('confirm.deleteAll'), danger: true, requireInput: 'DELETE', inputLabel: t('confirm.deleteAllLogsInputLabel') })) { return; }
            const r = await apiCall('/api/admin/logs/all', { method: 'DELETE' }, { errorMsg: t('toast.deleteAllLogsFailed') });
            if (r && r.ok) { const d = await r.json(); showToast(t('toast.deleteAllLogsSuccess', {count: d.deleted}), 'success'); loadLogs(true); }
        };
        return { locale, messages, t, switchLocale, loadLocale, page, rules, jmsEnabled, showModal, editing, form, conditions, conditionsExpanded, toasts, dismissToast, sidebarCollapsed, mobileMenu, autoResize, canSave, saving, formErrors, validateForm, showCatchAllWarning, catchAllConfirmed, showBodyConditionWarning, setProtocol, openCreate, copyRule, createFromLog, openEdit, closeModal, saveRule, deleteRule, extendRule, toggleEnabled, loadRules, loadStatus, loadLogs, debouncedLoadLogs, debouncedLoadAudit, logs, logSummary, logFilter, logSort, logPage, logPageSize, pagedLogs, totalPages, toggleSort, sortIcon, onPageSizeChange, auditLogs, loadAudit, selectedAudit, auditFilter, auditSort, auditPage, auditPageSize, pagedAudit, auditTotalPages, toggleAuditSort, auditSortIcon, onAuditPageSizeChange, formatAuditJson, getAuditChanges, getAuditTarget, getAuditDescription, getAuditProtocol, getAuditChangeCount, status, isAdmin, isLoggedIn, login, logout, httpAlias, jmsAlias, httpLabel, jmsLabel, envLabel, selectedRules, batchSelectMode, ruleFilter, ruleSort, rulePage, rulePageSize, filteredRules, pagedRules, ruleTotalPages, toggleRuleSort, ruleSortIcon, toggleSelectAll, exportRules, showImportModal, importFormat, importFile, importFileName, handleImportFile, doImport, showOpenApiPreview, openApiPreviewTitle, openApiPreviewVersion, openApiPreviewRules, openApiImporting, confirmOpenApiImport, batchProtect, deleteSelectedRules, deleteAllRules, deleteAllResponses, deleteAllAuditLogs, deleteAllLogs, showPriorityHelp, helpTab, onResponseModeChange, responseSummary, responseFilter, responseSort, responsePage, responsePageSize, filteredResponseSummary, pagedResponseSummary, responseTotalPages, toggleResponseSort, responseSortIcon, onResponsePageSizeChange, showResponseModal, editingResponse, responseForm, loadResponseSummary, openResponseModal, saveResponse, deleteResponse, responsePickerSearch, responseDropdownOpen, filteredResponsePicker, loading, newTag, parseTags, addTag, removeTag, newHeader, parseHeaders, addHeader, removeHeader, responsePickerSseOnly, fmtTime, shortId, daysLeft, fmtSize, toggleResponseRules, selectedResponses, batchSelectResponseMode, toggleSelectAllResponses, exportResponses, importResponses, deleteSelectedResponses, ruleViewMode, expandedTagGroups, toggleTagGroup, expandedTagSubgroups, toggleTagSubgroup, tagKeys, rulesByTag, rulesByTagGroup, getGroupLimit, showMoreGroup, showAllGroup, toggleMatchChain, logDetailExpanded, toggleLogDetail, goToRule, goToResponse, testExpanded, testParams, testResult, testLoading, runTest, testSseEvents, testSseMode, stopSseTest, generateTestData, previewResponseId, previewResponseBody, previewEditorRef, editEditorRef, responseFormEditorRef, previewFormatted, editFormatted, responseFormFormatted, togglePreviewFormat, toggleEditFormat, toggleResponseFormFormat, detectMode, previewEditing, previewEditBody, previewResponseUsageCount, previewSaving, togglePreviewEditing, savePreviewResponse, backupStatus, triggerBackup, theme, toggleTheme, themeIcon, themeLabel, density, toggleDensity, densityIcon, densityLabel, confirmState, showConfirm, auditTruncated, ruleFilterChips, removeRuleChip, clearRuleFilters, logFilterChips, removeLogChip, clearLogFilters, auditFilterChips, removeAuditChip, clearAuditFilters, responseFilterChips, removeResponseChip, clearResponseFilters, autoRefresh, toggleAutoRefresh, rulePreviewCache, rulePreviewExpanded, rulePreviewLoading, toggleRulePreview, handleRuleRowClick, clipCopy, exportRuleJson, condCount, condTooltip, condTags, fmtCond, showDblClickHint, dismissDblClickHint, responseUnusedOnly: responseUsageFilter, responseContentTypeFilter, showDataDropdown, toggleDataDropdown, triggerResponseImport, showResponseDataDropdown, toggleResponseDataDropdown, triggerResponseImport2, ruleModalMaximized, responseModalMaximized, sseEnabled: computed(() => form.value.sseEnabled), sseEvents, addSseEvent, removeSseEvent, ssePreview, responseSsePreview, responseSseEvents, extendResponse, deleteOrphanResponses, accountsCtx, loadAccounts, showForceChangePassword, forceChangePwdForm, forceChangePwdError, forceChangePwdSubmitting, submitForceChangePassword, createRuleFromLog, applyTemplate, tourActive, tourStep, helpSeen, startTour, nextStep, prevStep, skipTour, tourSteps, tourHighlightStyle, tourTooltipStyle };
        }
});
_app.component('sidebar-nav', SidebarNav);
_app.component('toast-container', ToastContainer);
_app.component('confirm-modal', ConfirmModal);
_app.component('import-modal', ImportModal);
_app.component('openapi-preview-modal', OpenApiPreviewModal);
_app.component('audit-page', AuditPage);
_app.component('stats-page', StatsPage);
_app.component('response-edit-modal', ResponseEditModal);
_app.component('responses-page', ResponsesPage);
_app.component('accounts-page', AccountsPage);
_app.component('settings-page', SettingsPage);
_app.component('priority-help-modal', PriorityHelpModal);
_app.component('rules-page', RulesPage);
_app.component('rule-edit-modal', RuleEditModal);
_app.component('rule-group-row', RuleGroupRow);
_app.provide('t', (...args) => _t(...args));
_app.mount('#app');
