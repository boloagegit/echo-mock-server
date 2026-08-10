/**
 * useRuleApply - 宣告式規則設定流程與表單草稿轉換。
 */
const useRuleApply = ({ showToast, showConfirm, t, requireLogin, login, loadRules, markRulesDirty }) => {
    const ruleApplyText = Vue.ref('');
    const ruleApplyLoading = Vue.ref(false);
    const ruleApplySaving = Vue.ref(false);
    const ruleApplyError = Vue.ref('');
    const ruleApplyOperation = Vue.ref('');
    const ruleApplySchema = Vue.ref(null);
    const ruleApplySchemaError = Vue.ref('');

    const templates = {
        HTTP_MOCK: {
            apiVersion: 'echo.mock/v1',
            kind: 'Rule',
            metadata: {},
            spec: {
                protocol: 'HTTP',
                method: 'GET',
                matchKey: '/api/example',
                action: 'MOCK',
                status: 200,
                responseHeaders: { 'Content-Type': 'application/json' },
                responseBody: { message: 'ok' },
                delayMs: 0,
                priority: 0,
                enabled: true,
                protected: false,
                tags: {}
            }
        },
        HTTP_FORWARD: {
            apiVersion: 'echo.mock/v1',
            kind: 'Rule',
            metadata: {},
            spec: {
                protocol: 'HTTP',
                method: 'GET',
                matchKey: '/api/example',
                action: 'FORWARD',
                forwardTargetMode: 'ORIGINAL_HOST',
                delayMs: 0,
                priority: 0,
                enabled: true,
                protected: false,
                tags: {}
            }
        },
        JMS: {
            apiVersion: 'echo.mock/v1',
            kind: 'Rule',
            metadata: {},
            spec: {
                protocol: 'JMS',
                matchKey: 'ORDER.REQUEST.Q',
                responseBody: { status: 'accepted' },
                delayMs: 0,
                priority: 0,
                enabled: true,
                protected: false,
                tags: {}
            }
        }
    };

    const templateText = template => JSON.stringify(templates[template] || templates.HTTP_MOCK, null, 2);

    const parseJsonObject = value => {
        if (value && typeof value === 'object' && !Array.isArray(value)) { return value; }
        if (!value || typeof value !== 'string') { return {}; }
        try {
            const parsed = JSON.parse(value);
            return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
        } catch { return {}; }
    };

    const parseResponseBody = value => {
        if (value == null || typeof value !== 'string') { return value; }
        const trimmed = value.trim();
        if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) { return value; }
        try { return JSON.parse(value); } catch { return value; }
    };

    const stringifyResponseBody = value => {
        if (value == null) { return ''; }
        return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
    };

    const normalizeResponseBody = value => {
        const parsed = parseResponseBody(value);
        return typeof parsed === 'string' ? parsed : JSON.stringify(parsed);
    };

    const serializeConditions = (conditions, type) => conditions
        .filter(condition => condition.type === type && condition.field && condition.value)
        .map(condition => condition.field + (condition.operator || '=') + condition.value)
        .join(';') || null;

    const deserializeConditions = spec => {
        const result = [];
        const append = (serialized, type) => {
            if (!serialized) { return; }
            serialized.split(';').filter(Boolean).forEach(condition => {
                for (const operator of ['!=', '*=', '~=', '=']) {
                    const index = condition.indexOf(operator);
                    if (index > 0) {
                        result.push({
                            type,
                            field: condition.substring(0, index).trim(),
                            operator,
                            value: condition.substring(index + operator.length).trim()
                        });
                        break;
                    }
                }
            });
        };
        append(spec.bodyCondition, 'body');
        append(spec.queryCondition, 'query');
        append(spec.headerCondition, 'header');
        return result;
    };

    const createDocumentFromForm = ({ form, conditions, editing, responseBody }) => {
        const http = form.protocol === 'HTTP';
        const action = http ? (form.action || 'MOCK') : null;
        const forwarding = http && action === 'FORWARD';
        const usesExistingResponse = !forwarding && form.responseMode === 'existing' && form.responseId;
        const metadataId = form.id || editing?.id;
        const metadataVersion = form.version ?? editing?.version;
        const spec = {
            protocol: form.protocol,
            targetHost: http ? (form.targetHost || null) : null,
            matchKey: form.matchKey,
            method: http ? form.method : null,
            bodyCondition: serializeConditions(conditions, 'body'),
            queryCondition: http ? serializeConditions(conditions, 'query') : null,
            headerCondition: http ? serializeConditions(conditions, 'header') : null,
            priority: Number(form.priority) || 0,
            description: form.description || null,
            enabled: form.enabled !== false,
            protected: form.isProtected === true,
            tags: parseJsonObject(form.tags),
            responseId: usesExistingResponse ? Number(form.responseId) : null,
            responseBody: forwarding ? null : parseResponseBody(responseBody),
            responseDescription: forwarding ? null : (form.responseDescription || null),
            status: http && !forwarding ? Number(form.status || 200) : null,
            responseHeaders: http && !forwarding ? parseJsonObject(form.responseHeaders) : null,
            delayMs: Number(form.delayMs) || 0,
            maxDelayMs: form.maxDelayMs == null || form.maxDelayMs === '' ? null : Number(form.maxDelayMs),
            sseEnabled: http && !forwarding ? form.sseEnabled === true : null,
            sseLoopEnabled: http && !forwarding ? form.sseLoopEnabled === true : null,
            responseContentType: http && !forwarding ? (form.responseContentType || null) : null,
            action,
            forwardTargetMode: forwarding ? (form.forwardTargetMode || 'ORIGINAL_HOST') : null,
            httpTargetConnectionId: forwarding && form.forwardTargetMode === 'CONNECTION'
                ? Number(form.httpTargetConnectionId) : null
        };
        Object.keys(spec).forEach(key => {
            if (spec[key] == null) { delete spec[key]; }
        });
        if (spec.tags && Object.keys(spec.tags).length === 0) { delete spec.tags; }
        if (spec.responseHeaders && Object.keys(spec.responseHeaders).length === 0) { delete spec.responseHeaders; }
        return {
            apiVersion: 'echo.mock/v1',
            kind: 'Rule',
            metadata: metadataId ? { id: metadataId, resourceVersion: metadataVersion } : {},
            spec
        };
    };

    const createFormDraftFromDocument = (document, { currentForm = {}, existingResponseBody = '', existingResponseLoaded = false } = {}) => {
        if (!document || document.apiVersion !== 'echo.mock/v1' || document.kind !== 'Rule' || !document.spec) {
            throw new Error('INVALID_RULE_DOCUMENT');
        }
        const spec = document.spec;
        const documentBody = stringifyResponseBody(spec.responseBody);
        const canKeepExistingResponse = Boolean(spec.responseId) && (
            spec.responseBody == null
            || (existingResponseLoaded
                && normalizeResponseBody(documentBody) === normalizeResponseBody(existingResponseBody))
        );
        const responseMode = canKeepExistingResponse ? 'existing' : 'new';
        return {
            form: {
                ...currentForm,
                id: document.metadata?.id,
                version: document.metadata?.resourceVersion,
                protocol: spec.protocol || 'HTTP',
                targetHost: spec.targetHost || '',
                matchKey: spec.matchKey || '',
                method: spec.method || 'GET',
                priority: spec.priority ?? 0,
                description: spec.description || '',
                enabled: spec.enabled !== false,
                isProtected: spec.protected === true,
                tags: spec.tags && Object.keys(spec.tags).length ? JSON.stringify(spec.tags) : '',
                responseId: responseMode === 'existing' ? spec.responseId : null,
                responseBody: documentBody,
                responseDescription: spec.responseDescription || '',
                responseMode,
                status: spec.status ?? 200,
                responseHeaders: spec.responseHeaders && Object.keys(spec.responseHeaders).length
                    ? JSON.stringify(spec.responseHeaders) : '',
                delayMs: spec.delayMs ?? 0,
                maxDelayMs: spec.maxDelayMs ?? null,
                sseEnabled: spec.sseEnabled === true,
                sseLoopEnabled: spec.sseLoopEnabled === true,
                responseContentType: spec.responseContentType || null,
                action: spec.action || 'MOCK',
                forwardTargetMode: spec.forwardTargetMode || 'ORIGINAL_HOST',
                httpTargetConnectionId: spec.httpTargetConnectionId ?? null,
                bodyCondition: spec.bodyCondition || '',
                queryCondition: spec.queryCondition || '',
                headerCondition: spec.headerCondition || ''
            },
            conditions: deserializeConditions(spec),
            sseEvents: spec.sseEnabled && documentBody
                ? deserializeSseEvents(documentBody)
                : [{ event: '', data: '', id: '', delayMs: 0, type: 'normal' }],
            identity: document.metadata?.id ? {
                id: document.metadata.id,
                version: document.metadata.resourceVersion,
                protocol: spec.protocol
            } : null
        };
    };

    const getPathValue = (document, path) => path.split('.').reduce(
        (value, part) => value == null ? undefined : value[part], document);

    const hasPath = (document, path) => {
        const parts = path.split('.');
        let value = document;
        for (const part of parts) {
            if (!value || typeof value !== 'object' || !Object.prototype.hasOwnProperty.call(value, part)) {
                return false;
            }
            value = value[part];
        }
        return true;
    };

    const lineForPath = (text, path) => {
        const key = path.split('.').pop();
        const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const match = new RegExp('"' + escaped + '"\\s*:').exec(text);
        return match ? text.slice(0, match.index).split('\n').length : null;
    };

    const validationMessage = error => {
        const params = { path: error.path, ...error.details };
        if (Array.isArray(params.allowedValues)) { params.values = params.allowedValues.join(', '); }
        const keys = {
            JSON_SYNTAX: 'rules.applyValidationJsonSyntax',
            DOCUMENT_TYPE: 'rules.applyValidationDocumentType',
            REQUIRED: 'rules.applyValidationRequired',
            CONST: 'rules.applyValidationConst',
            UNKNOWN_FIELD: 'rules.applyValidationUnknown',
            TYPE: 'rules.applyValidationType',
            ALLOWED_VALUES: 'rules.applyValidationAllowed',
            MINIMUM: 'rules.applyValidationMinimum',
            RANGE: 'rules.applyValidationRangeGeneric',
            MAX_LENGTH: 'rules.applyValidationMaxLength',
            NOT_APPLICABLE: 'rules.applyValidationNotApplicable',
            DELAY_RANGE: 'rules.applyValidationDelayRange',
            HTTP_MATCH_KEY: 'rules.applyValidationHttpMatchKey',
            CONDITION_FORMAT: 'rules.applyValidationConditionFormat',
            INVALID_REGEX: 'rules.applyValidationRegex',
            SSE_LOOP_REQUIRES_SSE: 'rules.applyValidationSseLoop',
            CONTENT_TYPE_MISMATCH: 'rules.applyValidationContentType',
            MAP_STRING_VALUE: 'rules.applyValidationStringMap',
            HEADER_NAME: 'rules.applyValidationHeaderName',
            HEADER_VALUE: 'rules.applyValidationHeaderValue',
            LINE_BREAK: 'rules.applyValidationLineBreak',
            UUID: 'rules.applyIdUuid',
            VERSION_REQUIRES_ID: 'rules.applyVersionRequiresId'
        };
        return t(keys[error.code] || 'rules.applyValidationFailed', params);
    };

    const issue = (errors, code, path, details = {}) => {
        errors.push({
            code,
            path,
            details,
            line: lineForPath(ruleApplyText.value, path),
            message: validationMessage({ code, path, details })
        });
    };

    const validateUnknownFields = (errors, document) => {
        const allowed = {
            document: new Set(['apiVersion', 'kind', 'metadata', 'spec']),
            metadata: new Set(['id', 'resourceVersion']),
            spec: new Set((ruleApplySchema.value?.fields || [])
                .filter(field => field.path.startsWith('spec.'))
                .map(field => field.path.substring(5)))
        };
        const inspect = (value, path, keys) => {
            if (!value || typeof value !== 'object' || Array.isArray(value)) { return; }
            Object.keys(value).filter(key => !keys.has(key)).forEach(key => {
                issue(errors, 'UNKNOWN_FIELD', path === 'document' ? key : path + '.' + key, { field: key });
            });
        };
        inspect(document, 'document', allowed.document);
        inspect(document.metadata, 'metadata', allowed.metadata);
        inspect(document.spec, 'spec', allowed.spec);
    };

    const validateConditionValue = (errors, path, value) => {
        if (typeof value !== 'string' || !value.trim()) { return; }
        for (const raw of value.split(';')) {
            const condition = raw.trim();
            const operator = ['!=', '*=', '~=', '='].find(candidate => condition.includes(candidate));
            if (!operator) {
                issue(errors, 'CONDITION_FORMAT', path);
                return;
            }
            const index = condition.indexOf(operator);
            const field = condition.slice(0, index).trim();
            const expected = condition.slice(index + operator.length).trim();
            if (!field || !expected) {
                issue(errors, 'CONDITION_FORMAT', path);
                return;
            }
            if (operator === '~=') {
                try { new RegExp(expected); }
                catch {
                    issue(errors, 'INVALID_REGEX', path);
                    return;
                }
            }
        }
    };

    const validateParsedDocument = document => {
        const errors = [];
        if (!document || typeof document !== 'object' || Array.isArray(document)) {
            issue(errors, 'DOCUMENT_TYPE', '$');
            return errors;
        }
        if (document.metadata != null && (typeof document.metadata !== 'object' || Array.isArray(document.metadata))) {
            issue(errors, 'TYPE', 'metadata', { type: t('rules.applyTypeObject') });
        }
        if (!document.spec || typeof document.spec !== 'object' || Array.isArray(document.spec)) {
            issue(errors, 'REQUIRED', 'spec');
            validateUnknownFields(errors, document);
            return errors;
        }

        validateUnknownFields(errors, document);
        const protocol = document.spec.protocol;
        const action = document.spec.action || 'MOCK';
        const fields = ruleApplySchema.value?.fields || [];
        for (const field of fields) {
            const present = hasPath(document, field.path);
            const value = getPathValue(document, field.path);
            const protocolApplies = !field.protocols?.length || field.protocols.includes(protocol);
            const actionApplies = !field.actions?.length || field.actions.includes(action);
            const applicable = protocolApplies && actionApplies;
            const required = field.requiredWhen === 'ALWAYS'
                || (field.requiredWhen === 'HTTP' && protocol === 'HTTP')
                || (field.requiredWhen === 'HTTP_FORWARD_CONNECTION'
                    && protocol === 'HTTP' && action === 'FORWARD'
                    && document.spec.forwardTargetMode === 'CONNECTION');

            if (present && !applicable) {
                issue(errors, 'NOT_APPLICABLE', field.path, { context: protocol + (action ? ' / ' + action : '') });
                continue;
            }
            if (required && (!present || value == null || (typeof value === 'string' && !value.trim()))) {
                issue(errors, 'REQUIRED', field.path);
                continue;
            }
            if (!present || value == null || !applicable) { continue; }

            const typeMatches = field.type === 'any'
                || (field.type === 'string' && typeof value === 'string')
                || (field.type === 'integer' && Number.isInteger(value))
                || (field.type === 'boolean' && typeof value === 'boolean')
                || (field.type === 'object' && typeof value === 'object' && !Array.isArray(value));
            if (!typeMatches) {
                issue(errors, 'TYPE', field.path, { type: t('rules.applyType' + field.type.charAt(0).toUpperCase() + field.type.slice(1)) });
                continue;
            }
            if (field.allowedValues?.length && !field.allowedValues.includes(value)) {
                issue(errors, 'ALLOWED_VALUES', field.path, { allowedValues: field.allowedValues });
            }
            if (field.minimum != null && value < field.minimum) {
                issue(errors, 'MINIMUM', field.path, { minimum: field.minimum });
            }
            if (field.maximum != null && value > field.maximum) {
                issue(errors, 'RANGE', field.path, { minimum: field.minimum, maximum: field.maximum });
            }
            if (field.maxLength != null && typeof value === 'string' && value.length > field.maxLength) {
                issue(errors, 'MAX_LENGTH', field.path, { maximum: field.maxLength });
            }
            if (field.valueType === 'string' && typeof value === 'object' && !Array.isArray(value)) {
                if (Object.entries(value).some(([key, mapValue]) => !key.trim() || typeof mapValue !== 'string')) {
                    issue(errors, 'MAP_STRING_VALUE', field.path);
                }
            }
        }

        const spec = document.spec;
        if (typeof spec.maxDelayMs === 'number' && typeof spec.delayMs === 'number' && spec.maxDelayMs < spec.delayMs) {
            issue(errors, 'DELAY_RANGE', 'spec.maxDelayMs');
        }
        if (spec.sseLoopEnabled === true && spec.sseEnabled !== true) {
            issue(errors, 'SSE_LOOP_REQUIRES_SSE', 'spec.sseLoopEnabled');
        }
        if (spec.responseContentType && protocol === 'HTTP' && action === 'MOCK') {
            const expected = spec.sseEnabled === true ? 'SSE_EVENTS' : 'TEXT';
            if (spec.responseContentType !== expected) {
                issue(errors, 'CONTENT_TYPE_MISMATCH', 'spec.responseContentType', { expected });
            }
        }
        if (protocol === 'HTTP' && typeof spec.matchKey === 'string' && spec.matchKey.trim()
            && spec.matchKey !== '*' && !spec.matchKey.startsWith('/')) {
            issue(errors, 'HTTP_MATCH_KEY', 'spec.matchKey');
        }
        ['bodyCondition', 'queryCondition', 'headerCondition'].forEach(name => {
            validateConditionValue(errors, 'spec.' + name, spec[name]);
        });
        if (spec.targetHost && /[\r\n]/.test(spec.targetHost)) {
            issue(errors, 'LINE_BREAK', 'spec.targetHost');
        }
        if (spec.responseHeaders && typeof spec.responseHeaders === 'object' && !Array.isArray(spec.responseHeaders)) {
            const headerName = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;
            for (const [name, value] of Object.entries(spec.responseHeaders)) {
                if (!headerName.test(name)) { issue(errors, 'HEADER_NAME', 'spec.responseHeaders.' + name); }
                if (typeof value === 'string' && /[\r\n]/.test(value)) {
                    issue(errors, 'HEADER_VALUE', 'spec.responseHeaders.' + name);
                }
            }
        }
        if (document.metadata?.id) {
            const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
            if (!uuid.test(document.metadata.id)) { issue(errors, 'UUID', 'metadata.id'); }
        } else if (document.metadata?.resourceVersion != null) {
            issue(errors, 'VERSION_REQUIRES_ID', 'metadata.resourceVersion');
        }
        return errors;
    };

    const ruleApplyValidationErrors = Vue.computed(() => {
        if (!ruleApplyText.value.trim()) {
            return [{ code: 'REQUIRED', path: '$', line: null, details: {}, message: t('rules.applyDocumentRequired') }];
        }
        try {
            return validateParsedDocument(JSON.parse(ruleApplyText.value));
        } catch (error) {
            const position = Number(error.message?.match(/position\s+(\d+)/i)?.[1]);
            const line = Number.isFinite(position)
                ? ruleApplyText.value.slice(0, position).split('\n').length
                : null;
            return [{ code: 'JSON_SYNTAX', path: '$', line, details: {}, message: t('rules.applyValidationJsonSyntax') }];
        }
    });

    const loadRuleApplySchema = async () => {
        if (ruleApplySchema.value) { return true; }
        ruleApplyLoading.value = true;
        ruleApplySchemaError.value = '';
        const response = await apiCall('/api/admin/rules/schema', {}, { silent: true });
        ruleApplyLoading.value = false;
        if (!response?.ok) {
            ruleApplySchemaError.value = t('rules.applySchemaLoadFailed');
            ruleApplyError.value = ruleApplySchemaError.value;
            return false;
        }
        ruleApplySchema.value = await response.json();
        return true;
    };

    const setRuleApplyDocument = document => {
        ruleApplyText.value = typeof document === 'string' ? document : JSON.stringify(document, null, 2);
        ruleApplyError.value = '';
        ruleApplyOperation.value = '';
    };

    const updateRuleApplyText = value => {
        ruleApplyText.value = value;
        ruleApplyError.value = '';
        ruleApplyOperation.value = '';
    };

    const readRuleApplyDocument = () => {
        try {
            const parsed = JSON.parse(ruleApplyText.value);
            const firstError = ruleApplyValidationErrors.value[0];
            if (firstError) {
                ruleApplyError.value = firstError.message;
                return null;
            }
            return parsed;
        }
        catch {
            ruleApplyError.value = t('rules.applyInvalidJson');
            return null;
        }
    };

    const localizeValidationError = body => {
        if (body?.validationCode) {
            return validationMessage({
                code: body.validationCode,
                path: body.path || '$',
                details: body
            });
        }
        const message = body?.error;
        const exactMessages = {
            'Apply document is required': 'rules.applyDocumentRequired',
            'spec is required': 'rules.applySpecRequired',
            'spec.protocol is required': 'rules.applyProtocolRequired',
            'spec.matchKey is required': 'rules.applyMatchKeyRequired',
            'spec.method is required for HTTP rules': 'rules.applyMethodRequired',
            'spec.status must be between 100 and 599': 'rules.applyStatusRange',
            'spec.delayMs must be zero or greater': 'rules.applyDelayNonNegative',
            'spec.maxDelayMs must be zero or greater': 'rules.applyMaxDelayNonNegative',
            'spec.maxDelayMs must be greater than or equal to spec.delayMs': 'rules.applyDelayRange',
            'spec.responseId must be greater than zero': 'rules.applyResponseIdPositive',
            'metadata.id must be a UUID': 'rules.applyIdUuid',
            'metadata.resourceVersion requires metadata.id': 'rules.applyVersionRequiresId',
            'metadata.resourceVersion must be zero or greater': 'rules.applyVersionNonNegative',
            'metadata.resourceVersion is required when updating an existing rule': 'rules.applyVersionRequired'
        };
        if (exactMessages[message]) { return t(exactMessages[message]); }

        const unknownField = message?.match(/^Unknown (document|metadata|spec) field: (.+)$/);
        if (unknownField) {
            return t('rules.applyUnknownField', { path: unknownField[1], field: unknownField[2] });
        }
        if (message?.startsWith('apiVersion must be ')) {
            return t('rules.applyApiVersion', { value: message.slice('apiVersion must be '.length) });
        }
        if (message?.startsWith('kind must be ')) {
            return t('rules.applyKind', { value: message.slice('kind must be '.length) });
        }
        const disabledProtocol = message?.match(/^(HTTP|JMS) is not enabled$/);
        if (disabledProtocol) {
            return t('rules.applyProtocolDisabled', { protocol: disabledProtocol[1] });
        }
        return t('rules.applyValidationFailed');
    };

    const resetRuleApply = () => {
        if (ruleApplySaving.value) { return; }
        ruleApplyText.value = '';
        ruleApplyError.value = '';
        ruleApplyOperation.value = '';
    };

    const replaceRuleApplyTemplate = async template => {
        let hasIdentity = false;
        try { hasIdentity = !!JSON.parse(ruleApplyText.value || '{}')?.metadata?.id; } catch { /* invalid JSON can be replaced */ }
        if (hasIdentity) {
            const confirmed = await showConfirm({
                title: t('rules.applyReplaceTitle'),
                message: t('rules.applyReplaceMessage'),
                confirmText: t('rules.applyReplaceConfirm')
            });
            if (!confirmed) { return; }
        }
        ruleApplyText.value = templateText(template);
        ruleApplyError.value = '';
        ruleApplyOperation.value = '';
    };

    const applyRuleDocument = async () => {
        if (!await requireLogin()) { return; }
        ruleApplyError.value = '';
        ruleApplyOperation.value = '';

        const firstValidationError = ruleApplyValidationErrors.value[0];
        if (firstValidationError) {
            ruleApplyError.value = firstValidationError.message;
            return;
        }

        const document = readRuleApplyDocument();
        if (!document) { return; }

        ruleApplySaving.value = true;
        const response = await apiCall('/api/admin/rules/apply', {
            method: 'POST',
            body: JSON.stringify(document)
        }, { silent: true });
        ruleApplySaving.value = false;

        if (!response) {
            ruleApplyError.value = t('toast.networkError');
            return;
        }
        if (response.status === 401 || response.status === 403) {
            login();
            return;
        }
        if (!response.ok) {
            let body = {};
            try { body = await response.json(); } catch { /* use generic error below */ }
            const errors = {
                RESOURCE_VERSION_CONFLICT: t('rules.applyVersionConflict', { version: body.currentResourceVersion ?? '?' }),
                OPTIMISTIC_LOCK_CONFLICT: t('rules.applyConcurrentConflict'),
                PROTOCOL_IMMUTABLE: t('rules.applyProtocolImmutable'),
                RESOURCE_NOT_FOUND: t('rules.applyNotFound'),
                HTTP_CONNECTION_REQUIRED: t('rules.applyHttpConnectionRequired'),
                HTTP_CONNECTION_NOT_FOUND: t('rules.applyHttpConnectionNotFound'),
                HTTP_CONNECTION_DISABLED: t('rules.applyHttpConnectionDisabled'),
                DEFAULT_HTTP_CONNECTION_NOT_FOUND: t('rules.applyDefaultHttpConnectionNotFound')
            };
            ruleApplyError.value = errors[body.error]
                || (response.status === 400 ? localizeValidationError(body) : t('rules.applyFailed'));
            return;
        }

        const result = await response.json();
        ruleApplyText.value = JSON.stringify(result.resource, null, 2);
        ruleApplyOperation.value = result.operation || 'UPDATED';
        showToast(result.operation === 'CREATED' ? t('rules.applyCreated') : t('rules.applyUpdated'), 'success');
        markRulesDirty();
        await loadRules(true);
        return result;
    };

    return {
        ruleApplyText,
        ruleApplyLoading,
        ruleApplySaving,
        ruleApplyError,
        ruleApplyOperation,
        ruleApplySchema,
        ruleApplySchemaError,
        ruleApplyValidationErrors,
        createDocumentFromForm,
        createFormDraftFromDocument,
        setRuleApplyDocument,
        updateRuleApplyText,
        readRuleApplyDocument,
        resetRuleApply,
        loadRuleApplySchema,
        replaceRuleApplyTemplate,
        applyRuleDocument
    };
};
