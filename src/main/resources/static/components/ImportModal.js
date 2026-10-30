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
        { value: 'json', label: 'JSON', description: this.t('modal.importJsonHint'), icon: 'bi-filetype-json' },
        { value: 'excel', label: 'Excel', description: this.t('modal.importExcelHint'), icon: 'bi-file-earmark-excel' },
        { value: 'openapi', label: 'OpenAPI', description: this.t('modal.openApiHint'), icon: 'bi-filetype-yml' },
      ].map(option => ({...option, disabled: this.loading}));
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
          <fieldset class="import-format-fieldset">
            <legend class="form-label">{{t('modal.selectFormat')}}</legend>
            <ui-choice-group ref="formatChoices" class="import-format-options" option-class="import-format-option"
              variant="cards" show-check :model-value="importFormat" :options="formatOptions"
              :aria-label="t('modal.selectFormat')" @update:model-value="selectFormat"></ui-choice-group>
          </fieldset>

          <div class="form-group import-upload-field">
            <span class="form-label">{{t('modal.uploadFile')}}</span>
            <label class="import-dropzone" :class="{hasFile:importFile, 'is-dragging':dragActive, 'is-invalid':error}"
              @dragenter.prevent="dragActive=true" @dragover.prevent="dragActive=true" @dragleave.prevent="dragActive=false" @drop.prevent="handleDrop">
              <input ref="fileInput" class="visually-hidden" type="file" :disabled="loading" :accept="fileAccept" @change="$emit('handle-file',$event)">
              <i class="bi" :class="importFile?'bi-file-earmark-check':'bi-cloud-arrow-up'" aria-hidden="true"></i>
              <span v-if="importFile" class="import-file-selected"><strong>{{importFileName}}</strong><small>{{t('modal.importReplaceFile')}}</small></span>
              <span v-else class="import-file-empty"><strong>{{t('modal.clickOrDragFile')}}</strong><small>{{acceptedFileHint}}</small></span>
            </label>
            <div v-if="error" class="import-error" role="alert"><i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>{{error}}</span></div>
            <a v-if="importFormat==='excel'" href="/api/admin/rules/import-template" class="import-template-link"><i class="bi bi-download" aria-hidden="true"></i> {{t('modal.downloadTemplate')}}</a>
          </div>
          <section v-if="importFormat==='json' && preview" class="import-preview" aria-live="polite">
            <h3>{{t('modal.importPreviewTitle')}}</h3>
            <p>{{t('modal.importNewRulesHint', {count: preview.total})}}</p>
            <p>{{t('modal.importActivationHint', {enabled: preview.enabled, disabled: preview.disabled})}}</p>
            <p>{{t('modal.importRetentionHint')}}</p>
            <div v-for="requirement in preview.requirements" :key="requirement.key" class="form-group">
              <label class="form-label" :for="'import-connection-'+requirement.key">{{t('modal.importSourceConnection', {protocol:requirement.protocol, name:requirement.sourceName || requirement.protocol, count:requirement.rules})}}</label>
              <select class="form-select" :disabled="loading" :id="'import-connection-'+requirement.key" :value="connectionMappings?.[requirement.key] || ''"
                @change="$emit('set-connection', {key: requirement.key, selection: $event.target.value})">
                <option value="" disabled>{{t('modal.importSelectConnection')}}</option>
                <option v-for="option in (preview.connections || []).filter(item => item.protocol===requirement.protocol)" :key="option.selection" :value="option.selection">{{connectionLabel(option)}}</option>
              </select>
            </div>
            <ul v-if="blockingErrors.length" class="import-problems" role="alert">
              <li v-for="(problem,index) in blockingErrors" :key="index">{{t('modal.importProblem', {row:problem.row, message:problem.message})}}</li>
            </ul>
          </section>
        </div>
        <div class="modal-footer">
          <ui-button type="button" variant="quiet" :disabled="loading" @click="$emit('close')">{{t('modal.cancel')}}</ui-button>
          <ui-button v-if="importFormat==='json' && preview" type="button" variant="quiet" @click="$emit('reset-preview')" :disabled="loading">{{t('modal.importResetPreview')}}</ui-button>
          <ui-button type="button" class="btn btn-primary" @click="$emit('do-import')" :disabled="!canSubmit">
            <i class="bi" :class="loading?'bi-arrow-clockwise spin':(importFormat==='openapi'?'bi-eye':'bi-upload')" aria-hidden="true"></i>
            {{submitLabel}}
          </ui-button>
        </div>
      </div>
    </div>
    </ui-modal-transition>
  `
};
