/** RuleApplyModal - K8s 風格的宣告式 JSON 規則編輯器。 */
const RuleApplyModal = {
  inject: ['t'],
  props: {
    active: Boolean,
    documentText: String,
    loading: Boolean,
    saving: Boolean,
    error: String,
    operation: String,
    schema: Object,
    schemaError: String,
    validationErrors: { type: Array, default: () => [] },
    jmsEnabled: Boolean
  },
  emits: ['close', 'apply', 'replace-template', 'update:documentText'],
  data() {
    return {
      localFormatError: '',
      inspectorMode: 'summary',
      fieldSearch: ''
    };
  },
  computed: {
    parsedDocument() {
      try { return JSON.parse(this.documentText || '{}'); } catch { return null; }
    },
    metadata() { return this.parsedDocument?.metadata || {}; },
    spec() { return this.parsedDocument?.spec || {}; },
    resourceMode() { return this.metadata.id ? this.t('rules.applyUpdateMode') : this.t('rules.applyCreateMode'); },
    visibleError() { return this.localFormatError || this.error || this.validationErrors[0]?.message || ''; },
    applicableFields() {
      const protocol = this.spec.protocol;
      const action = this.spec.action || 'MOCK';
      const faulting = this.spec.faultType && this.spec.faultType !== 'NONE';
      const faultExcluded = new Set([
        'spec.responseId', 'spec.responseBody', 'spec.responseDescription',
        'spec.responseHeaders', 'spec.sseEnabled', 'spec.sseLoopEnabled',
        'spec.responseContentType', 'spec.forwardTargetMode', 'spec.httpTargetConnectionId',
        'spec.jmsTargetConnectionId'
      ]);
      return (this.schema?.fields || []).filter(field => {
        const protocolMatches = !field.protocols?.length || !protocol || field.protocols.includes(protocol);
        const actionMatches = !field.actions?.length || field.actions.includes(action);
        const connectionResetStatus = faulting
          && this.spec.faultType === 'CONNECTION_RESET'
          && field.path === 'spec.status';
        return protocolMatches && actionMatches
          && !(faulting && faultExcluded.has(field.path))
          && !connectionResetStatus;
      });
    },
    filteredFields() {
      const query = this.fieldSearch.trim().toLocaleLowerCase();
      if (!query) { return this.applicableFields; }
      return this.applicableFields.filter(field => [
        field.path,
        this.fieldDescription(field),
        ...(field.allowedValues || [])
      ].join(' ').toLocaleLowerCase().includes(query));
    }
  },
  watch: {
    active(isActive) {
      if (isActive) {
        this.localFormatError = '';
        this.$nextTick(() => this.$refs.editorInput?.focus());
      }
    }
  },
  mounted() {
    if (this.active) { this.$nextTick(() => this.$refs.editorInput?.focus()); }
  },
  methods: {
    updateDocument(event) {
      this.localFormatError = '';
      this.$emit('update:documentText', event.target.value);
    },
    formatDocument() {
      try {
        const formatted = JSON.stringify(JSON.parse(this.documentText), null, 2);
        this.localFormatError = '';
        this.$emit('update:documentText', formatted);
        this.$nextTick(() => this.$refs.editorInput?.focus());
      } catch {
        this.localFormatError = this.t('rules.applyInvalidJson');
        this.$refs.editorInput?.focus();
      }
    },
    fieldDescription(field) {
      const suffix = field.path.split('.').map(part => part.charAt(0).toUpperCase() + part.slice(1)).join('');
      return this.t('rules.applyField' + suffix);
    },
    fieldType(field) {
      const suffix = field.type.charAt(0).toUpperCase() + field.type.slice(1);
      return this.t('rules.applyType' + suffix);
    },
    fieldRequirement(field) {
      if (this.spec.faultType === 'CONNECTION_RESET' && field.path === 'spec.status') {
        return this.t('rules.applyOptional');
      }
      const keys = {
        ALWAYS: 'rules.applyRequiredAlways',
        HTTP: 'rules.applyRequiredHttp',
        EXISTING_RESOURCE: 'rules.applyRequiredExisting',
        HTTP_FORWARD_CONNECTION: 'rules.applyRequiredConnection',
        JMS_FORWARD_CONNECTION: 'rules.applyRequiredJmsConnection'
      };
      return field.requiredWhen ? this.t(keys[field.requiredWhen]) : this.t('rules.applyOptional');
    },
    fieldDefault(field) {
      if (field.defaultValue == null) { return null; }
      return typeof field.defaultValue === 'string'
        ? field.defaultValue
        : JSON.stringify(field.defaultValue);
    }
  },
  template: /* html */`
    <div class="rule-apply-inline">
        <div class="modal-body rule-apply-body">
          <div class="rule-apply-editor-pane">
            <div class="rule-apply-toolbar">
              <div class="rule-apply-template-controls" role="group" :aria-label="t('rules.applyTemplates')">
                <button class="btn btn-sm btn-secondary" @click="$emit('replace-template','HTTP_MOCK')"><i class="bi bi-reply" aria-hidden="true"></i> {{t('rules.applyHttpMockTemplate')}}</button>
                <button class="btn btn-sm btn-secondary" @click="$emit('replace-template','HTTP_FORWARD')"><i class="bi bi-box-arrow-up-right" aria-hidden="true"></i> {{t('rules.applyHttpForwardTemplate')}}</button>
                <button class="btn btn-sm btn-secondary" @click="$emit('replace-template','HTTP_FAULT')"><i class="bi bi-lightning" aria-hidden="true"></i> {{t('rules.applyHttpFaultTemplate')}}</button>
                <button class="btn btn-sm btn-secondary" @click="$emit('replace-template','JMS')" :disabled="!jmsEnabled"><i class="bi bi-envelope" aria-hidden="true"></i> {{t('rules.applyJmsTemplate')}}</button>
              </div>
              <span class="rule-apply-document-state">
                <i class="bi" :class="metadata.id ? 'bi-pencil-square' : 'bi-plus-circle'" aria-hidden="true"></i>
                {{resourceMode}}
              </span>
              <button class="btn btn-sm btn-secondary" @click="formatDocument" :disabled="loading"><i class="bi bi-braces" aria-hidden="true"></i> {{t('rules.applyFormat')}}</button>
            </div>

            <div class="rule-apply-editor-shell" :class="{'is-loading':loading, 'has-error':visibleError}">
              <div v-if="loading" class="rule-apply-loading"><i class="bi bi-arrow-clockwise spin" aria-hidden="true"></i> {{t('rules.loading')}}</div>
              <textarea ref="editorInput" class="rule-apply-textarea" :value="documentText" @input="updateDocument" @keydown.ctrl.enter.prevent="$emit('apply')" @keydown.meta.enter.prevent="$emit('apply')" :aria-label="t('rules.applyEditorLabel')" :aria-invalid="!!visibleError" aria-describedby="ruleApplyFeedback" spellcheck="false" autocapitalize="off" autocomplete="off"></textarea>
            </div>
            <div v-if="visibleError" id="ruleApplyFeedback" class="rule-apply-feedback is-error" role="alert">
              <i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>{{visibleError}}</span>
            </div>
            <div v-else-if="operation" id="ruleApplyFeedback" class="rule-apply-feedback is-success" role="status">
              <i class="bi bi-check-circle" aria-hidden="true"></i><span>{{operation==='CREATED' ? t('rules.applyCreatedCanonical') : t('rules.applyUpdatedCanonical')}}</span>
            </div>
            <div v-else-if="schema" id="ruleApplyFeedback" class="rule-apply-feedback is-valid" role="status">
              <i class="bi bi-check2" aria-hidden="true"></i><span>{{t('rules.applyValidationPassed')}}</span>
            </div>
          </div>

          <aside class="rule-apply-inspector" :aria-label="t('rules.applyInspector')">
            <div class="rule-apply-inspector-switch" role="group" :aria-label="t('rules.applyInspector')">
              <button type="button" :class="{active:inspectorMode==='summary'}" :aria-pressed="inspectorMode==='summary'" @click="inspectorMode='summary'">{{t('rules.applySummaryTab')}}</button>
              <button type="button" :class="{active:inspectorMode==='fields'}" :aria-pressed="inspectorMode==='fields'" @click="inspectorMode='fields'">{{t('rules.applyFieldReference')}}</button>
            </div>

            <div v-if="inspectorMode==='summary'" class="rule-apply-inspector-content">
              <section v-if="validationErrors.length" class="rule-apply-issues" aria-labelledby="ruleApplyIssuesHeading">
                <div id="ruleApplyIssuesHeading" class="rule-apply-section-heading">{{t('rules.applyIssues', {count:validationErrors.length})}}</div>
                <ol>
                  <li v-for="item in validationErrors" :key="item.path + item.code">
                    <div><code>{{item.path}}</code><span v-if="item.line">{{t('rules.applyLine', {line:item.line})}}</span></div>
                    <p>{{item.message}}</p>
                  </li>
                </ol>
              </section>

              <section class="rule-apply-summary">
                <div class="rule-apply-section-heading">{{t('rules.applyResource')}}</div>
                <dl class="rule-apply-kv">
                  <div><dt>{{t('rules.applyMode')}}</dt><dd>{{resourceMode}}</dd></div>
                  <div><dt>{{t('rules.applyProtocol')}}</dt><dd><code>{{spec.protocol || '—'}}</code></dd></div>
                  <div><dt>{{t('rules.applyMatchKey')}}</dt><dd><code>{{spec.matchKey || '—'}}</code></dd></div>
                  <div><dt>{{t('rules.applyRuleId')}}</dt><dd><code :title="metadata.id">{{metadata.id || t('rules.applyServerGenerated')}}</code></dd></div>
                  <div><dt>{{t('rules.applyResourceVersion')}}</dt><dd><code>{{metadata.resourceVersion ?? t('rules.applyAfterCreate')}}</code></dd></div>
                </dl>
              </section>

              <section class="rule-apply-guidance">
                <div class="rule-apply-section-heading">{{t('rules.applyBehavior')}}</div>
                <ul>
                  <li><i class="bi bi-file-earmark-check"></i><span>{{t('rules.applyFullState')}}</span></li>
                  <li><i class="bi bi-shield-check"></i><span>{{t('rules.applyVersionSafety')}}</span></li>
                  <li><i class="bi bi-diagram-2"></i><span>{{t('rules.applySharedResponseSafety')}}</span></li>
                </ul>
              </section>
            </div>

            <div v-else class="rule-apply-field-reference">
              <label class="visually-hidden" for="ruleApplyFieldSearch">{{t('rules.applyFieldSearch')}}</label>
              <div class="rule-apply-field-search">
                <i class="bi bi-search" aria-hidden="true"></i>
                <input id="ruleApplyFieldSearch" v-model="fieldSearch" :placeholder="t('rules.applyFieldSearch')">
              </div>
              <p class="rule-apply-field-scope">{{t('rules.applyFieldScope', {protocol:spec.protocol || t('rules.applyAllProtocols')})}}</p>
              <div v-if="schemaError" class="rule-apply-reference-empty" role="alert">{{schemaError}}</div>
              <div v-else-if="!filteredFields.length" class="rule-apply-reference-empty">{{t('rules.applyNoMatchingFields')}}</div>
              <div v-else class="rule-apply-field-list">
                <details v-for="field in filteredFields" :key="field.path" class="rule-apply-field-item">
                  <summary>
                    <code>{{field.path}}</code>
                    <span>{{fieldType(field)}} · {{fieldRequirement(field)}}</span>
                  </summary>
                  <div class="rule-apply-field-detail">
                    <p>{{fieldDescription(field)}}</p>
                    <dl>
                      <div v-if="field.allowedValues?.length"><dt>{{t('rules.applyAllowedValues')}}</dt><dd><code>{{field.allowedValues.join(' | ')}}</code></dd></div>
                      <div v-if="field.minimum!=null || field.maximum!=null"><dt>{{t('rules.applyValueRange')}}</dt><dd><code>{{field.minimum ?? '—'}} … {{field.maximum ?? '∞'}}</code></dd></div>
                      <div v-if="field.maxLength!=null"><dt>{{t('rules.applyMaxLength')}}</dt><dd>{{field.maxLength}}</dd></div>
                      <div v-if="fieldDefault(field)!=null"><dt>{{t('rules.applyDefaultValue')}}</dt><dd><code>{{fieldDefault(field)}}</code></dd></div>
                      <div v-if="field.readOnly"><dt>{{t('rules.applyValueSource')}}</dt><dd>{{t('rules.applySystemDerived')}}</dd></div>
                    </dl>
                  </div>
                </details>
              </div>
            </div>
          </aside>
        </div>

        <div class="modal-footer rule-apply-footer">
          <span class="rule-apply-shortcut"><kbd>{{t('rules.applyShortcutControlKey')}}</kbd><span>+</span><kbd>{{t('rules.applyShortcutEnterKey')}}</kbd> {{t('rules.applyShortcut')}}</span>
          <button class="btn btn-secondary" @click="$emit('close')" :disabled="saving">{{t('modal.cancel')}}</button>
          <button class="btn btn-primary" @click="$emit('apply')" :disabled="saving || loading">
            <i class="bi" :class="saving ? 'bi-arrow-clockwise spin' : 'bi-check2-circle'" aria-hidden="true"></i>
            {{saving ? t('rules.applying') : t('rules.applyAction')}}
          </button>
        </div>
    </div>
  `
};
