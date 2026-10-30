/**
 * ImportModal - Rules Import Dialog
 *
 * Supports rule import in JSON, Excel and OpenAPI formats, including drag-and-drop upload.
 */
const ImportModal = {
  props: {
    show: Boolean,
    importFormat: String,
    importFile: [Object, null],
    importFileName: String,
    loading: Boolean,
    error: String,
    preview: Object,
    connectionMappings: Object,
  },
  emits: ['close', 'update:importFormat', 'handle-file', 'do-import', 'reset-preview', 'set-connection'],
  inject: ['t'],
  computed: {
    isPreview() { return this.importFormat === 'json' && !!this.preview; },
    pendingConnections() {
      return (this.preview?.requirements || []).filter(item => !(this.preview.connections || [])
        .some(option => option.protocol === item.protocol && option.selection === this.connectionMappings?.[item.key])).length;
    },
    selectedFormat() { return this.formatOptions.find(option => option.value === this.importFormat) || this.formatOptions[0]; },
    fileSizeLabel() {
      const bytes = this.importFile?.size;
      if (!Number.isFinite(bytes)) { return ''; }
      const divisor = bytes >= 1048576 ? 1048576 : bytes >= 1024 ? 1024 : 1;
      return `${Math.round(bytes / divisor * 10) / 10} ${divisor === 1048576 ? 'MB' : divisor === 1024 ? 'KB' : 'B'}`;
    },
    blockingErrors() {
      return (this.preview?.errors || []).filter(item => item.code !== 'CONNECTION_SELECTION_REQUIRED');
    },
    canSubmit() {
      if (!this.importFile || this.loading) { return false; }
      if (this.importFormat !== 'json' || !this.preview) { return true; }
      return this.blockingErrors.length === 0 && (this.preview.requirements || []).every(item =>
        (this.preview.connections || []).some(option => option.protocol === item.protocol
          && option.selection === this.connectionMappings?.[item.key]));
    },
    submitLabel() {
      if (this.importFormat === 'json' && this.preview) {
        return this.t('modal.importConfirmNew', {count: this.preview.total});
      }
      return this.importFormat === 'json' || this.importFormat === 'openapi'
        ? this.t('modal.previewImport') : this.t('modal.startImport');
    },
    formatOptions() {
      return [
        { value: 'json', label: 'JSON', hint: this.t('modal.importJsonHint'), icon: 'bi-filetype-json' },
        { value: 'excel', label: 'Excel', hint: this.t('modal.importExcelHint'), icon: 'bi-file-earmark-excel' },
        { value: 'openapi', label: 'OpenAPI', hint: this.t('modal.openApiHint'), icon: 'bi-filetype-yml' },
      ].map(option => ({...option, title: option.hint, disabled: this.loading}));
    },
    fileAccept() {
      if (this.importFormat === 'openapi') { return '.json,.yaml,.yml'; }
      return this.importFormat === 'json' ? '.json' : '.xlsx,.xls';
    },
    acceptedFileHint() {
      if (this.importFormat === 'openapi') { return this.t('modal.importOpenApiAccepted'); }
      return this.importFormat === 'json' ? this.t('modal.importJsonAccepted') : this.t('modal.importExcelAccepted');
    },
  },
  data() {
    return { dragActive: false, previousFocus: null, inertSiblings: [] };
  },
  watch: {
    isPreview(reviewing) {
      if (!this.show) { return; }
      this.$nextTick(() => {
        if (reviewing) { this.$refs.reviewTitle?.focus(); }
        else { this.$refs.formatChoices?.focusSelected(); }
      });
    },
    show(open) {
      if (open) {
        this.previousFocus = document.activeElement;
        this.$nextTick(() => {
          this.inertSiblings = makeOverlaySiblingsInert(this.$refs.overlay);
          this.$refs.formatChoices?.focusSelected();
        });
        return;
      }
      this.restoreDialog();
    },
  },
  beforeUnmount() {
    this.restoreDialog();
  },
  methods: {
    sourceConnectionLabel(requirement) {
      const name = requirement.sourceName;
      if (name === 'DEFAULT_CONNECTION') { return this.t('modal.importSourceDefault'); }
      if (name === 'ORIGINAL_HOST') { return this.t('modal.importOriginalHostChoice'); }
      return name && name !== 'CONNECTION' ? name : this.t('modal.importUnknownConnection');
    },
    chooseFile() {
      if (this.loading || !this.$refs.fileInput) { return; }
      this.$refs.fileInput.value = '';
      this.$refs.fileInput.click();
    },
    connectionLabel(option) {
      if (option.mode === 'ORIGINAL_HOST') { return this.t('modal.importOriginalHostChoice'); }
      return option.mode === 'DEFAULT_CONNECTION'
        ? this.t('modal.importDefaultConnectionChoice', {name: option.name}) : option.name;
    },
    restoreDialog() {
      restoreOverlaySiblings(this.inertSiblings);
      this.inertSiblings = [];
      const focusTarget = this.previousFocus?.isConnected
        ? this.previousFocus
        : document.querySelector('.rules-workspace .data-dropdown-wrapper > button');
      focusTarget?.focus?.();
      this.previousFocus = null;
    },
    selectFormat(format) {
      if (this.loading || format === this.importFormat) { return; }
      this.$emit('update:importFormat', format);
      if (this.$refs.fileInput) { this.$refs.fileInput.value = ''; }
    },
    handleDrop(event) {
      this.dragActive = false;
      if (this.loading) { return; }
      const file = event.dataTransfer?.files?.[0];
      if (file) { this.$emit('handle-file', file); }
    },
    handleKeydown(event) {
      if (event.key === 'Escape') {
        event.preventDefault();
        if (!this.loading) { this.$emit('close'); }
        return;
      }
      trapDialogFocus(event, this.$refs.dialog);
    },
  },
  template: /* html */`
    <ui-modal-transition>
    <div ref="overlay" class="modal-overlay" v-if="show" @keydown="handleKeydown">
      <div ref="dialog" class="modal-box workspace-modal import-modal" role="dialog" aria-modal="true" aria-labelledby="importModalTitle" tabindex="-1">
        <div class="modal-header">
          <div class="modal-heading"><h2 id="importModalTitle">{{t('modal.importRule')}}</h2></div>
          <ui-button type="button" class="close-btn" :disabled="loading" @click="$emit('close')" :aria-label="t('modal.cancel')"><i class="bi bi-x-lg" aria-hidden="true"></i></ui-button>
        </div>
        <div class="modal-body import-modal-body">
          <ol v-if="importFormat==='json'" class="import-steps" :aria-label="t('modal.importStepsLabel')">
            <li :class="{'is-current':!isPreview, 'is-complete':isPreview}" :aria-current="!isPreview?'step':null">
              <span class="import-step-number" aria-hidden="true"><i v-if="isPreview" class="bi bi-check2"></i><template v-else>1</template></span>
              <span>{{t('modal.importStepFile')}}</span>
            </li>
            <li :class="{'is-current':isPreview}" :aria-current="isPreview?'step':null">
              <span class="import-step-number" aria-hidden="true">2</span><span>{{t('modal.importStepReview')}}</span>
            </li>
          </ol>

          <fieldset v-if="!isPreview" class="import-format-fieldset">
            <legend class="form-label">{{t('modal.selectFormat')}}</legend>
            <ui-choice-group ref="formatChoices" class="import-format-options" option-class="import-format-option"
              variant="compact" :model-value="importFormat" :options="formatOptions"
              :aria-label="t('modal.selectFormat')" @update:model-value="selectFormat"></ui-choice-group>
            <p class="import-format-hint">{{selectedFormat.hint}}</p>
          </fieldset>

          <input ref="fileInput" id="rule-import-file" class="visually-hidden" type="file" :disabled="loading" :accept="fileAccept" :aria-label="t('modal.uploadFile')" @change="$emit('handle-file',$event)">
          <div v-if="importFile" class="import-file-card">
            <i class="bi bi-file-earmark-check" aria-hidden="true"></i>
            <div class="import-file-copy"><strong :title="importFileName">{{importFileName}}</strong><small>{{selectedFormat.label}}<template v-if="fileSizeLabel"> · {{fileSizeLabel}}</template></small></div>
            <ui-button type="button" variant="quiet" size="compact" :disabled="loading" @click="chooseFile">{{t('modal.importChangeFile')}}</ui-button>
          </div>
          <div v-else class="form-group import-upload-field">
            <label for="rule-import-file" class="import-dropzone" :class="{'is-dragging':dragActive, 'is-invalid':error}"
              @dragenter.prevent="dragActive=true" @dragover.prevent="dragActive=true" @dragleave.prevent="dragActive=false" @drop.prevent="handleDrop">
              <i class="bi bi-cloud-arrow-up" aria-hidden="true"></i>
              <span class="import-file-empty"><strong>{{t('modal.clickOrDragFile')}}</strong><small>{{acceptedFileHint}}</small></span>
            </label>
          </div>
          <a v-if="!isPreview && importFormat==='excel'" href="/api/admin/rules/import-template" class="import-template-link"><i class="bi bi-download" aria-hidden="true"></i> {{t('modal.downloadTemplate')}}</a>

          <section v-if="isPreview" class="import-preview" aria-live="polite" aria-labelledby="importPreviewTitle">
            <h3 ref="reviewTitle" id="importPreviewTitle" tabindex="-1">{{t('modal.importReviewTitle')}}</h3>
            <dl class="import-summary">
              <div><dt>{{t('modal.importSummaryNew')}}</dt><dd>{{preview.total}}</dd></div>
              <div class="import-summary-enabled"><dt>{{t('modal.importSummaryEnabled')}}</dt><dd>{{preview.enabled}}</dd></div>
              <div><dt>{{t('modal.importSummaryDisabled')}}</dt><dd>{{preview.disabled}}</dd></div>
            </dl>
            <div class="import-behavior">
              <p><i class="bi bi-shield-check" aria-hidden="true"></i><span>{{t('modal.importKeepExisting')}}</span></p>
              <p><i class="bi bi-clock" aria-hidden="true"></i><span>{{t('modal.importRetentionNew')}}</span></p>
            </div>
            <p v-if="preview.enabled>0" class="import-activation-note"><i class="bi bi-info-circle" aria-hidden="true"></i><span>{{t('modal.importEnabledImpact', {count:preview.enabled})}}</span></p>

            <section v-if="preview.requirements?.length" class="import-connections" aria-labelledby="importConnectionsTitle">
              <div class="import-section-heading"><h4 id="importConnectionsTitle">{{t('modal.importConnectionTitle')}}</h4><span class="import-mapping-status" :class="{'is-ready':pendingConnections===0}">{{pendingConnections?t('modal.importPendingConnections', {count:pendingConnections}):t('modal.importConnectionsReady')}}</span></div>
              <p>{{t('modal.importConnectionHelp')}}</p>
              <div v-for="requirement in preview.requirements" :key="requirement.key" class="import-mapping-row">
                <div class="import-mapping-source"><span class="import-field-label">{{t('modal.importSourceLabel')}}</span><strong :id="'import-source-'+requirement.key" :title="sourceConnectionLabel(requirement)">{{sourceConnectionLabel(requirement)}}</strong><small>{{t('modal.importSourceDetails', {protocol:requirement.protocol, count:requirement.rules})}}</small></div>
                <i class="bi bi-arrow-right import-mapping-arrow" aria-hidden="true"></i>
                <div class="import-mapping-target">
                  <label class="import-field-label" :for="'import-connection-'+requirement.key">{{t('modal.importDestinationLabel')}}</label>
                  <div class="import-select-wrap">
                    <select class="import-target-select" :disabled="loading" :id="'import-connection-'+requirement.key" :aria-describedby="'import-source-'+requirement.key" :value="connectionMappings?.[requirement.key] || ''"
                      @change="$emit('set-connection', {key: requirement.key, selection: $event.target.value})">
                      <option value="" disabled>{{t('modal.importSelectConnection')}}</option>
                      <option v-for="option in (preview.connections || []).filter(item => item.protocol===requirement.protocol)" :key="option.selection" :value="option.selection">{{connectionLabel(option)}}</option>
                    </select>
                    <i class="bi bi-chevron-down" aria-hidden="true"></i>
                  </div>
                </div>
              </div>
            </section>
            <ul v-if="blockingErrors.length" class="import-problems" role="alert">
              <li v-for="(problem,index) in blockingErrors" :key="index">{{t('modal.importProblem', {row:problem.row, message:problem.message})}}</li>
            </ul>
          </section>
          <div v-if="error" class="import-error" role="alert"><i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>{{error}}</span></div>
        </div>
        <div class="modal-footer import-modal-footer">
          <ui-button type="button" variant="quiet" :disabled="loading" @click="$emit('close')">{{t('modal.cancel')}}</ui-button>
          <ui-button v-if="isPreview" type="button" variant="quiet" @click="$emit('reset-preview')" :disabled="loading">{{t('modal.importBack')}}</ui-button>
          <ui-button type="button" class="btn btn-primary import-submit" @click="$emit('do-import')" :disabled="!canSubmit">
            <i class="bi" :class="loading?'bi-arrow-clockwise spin':(isPreview?'bi-check2':(importFormat==='excel'?'bi-upload':'bi-arrow-right'))" aria-hidden="true"></i>
            {{submitLabel}}
          </ui-button>
        </div>
      </div>
    </div>
    </ui-modal-transition>
  `
};
