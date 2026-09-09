/**
 * ImportModal - 規則匯入對話框
 *
 * 支援 JSON、Excel 與 OpenAPI 格式的規則匯入，含拖放上傳。
 */
const ImportModal = {
  props: {
    show: Boolean,
    importFormat: String,
    importFile: [Object, null],
    importFileName: String,
    loading: Boolean,
    error: String,
  },
  emits: ['close', 'update:importFormat', 'handle-file', 'do-import'],
  inject: ['t'],
  computed: {
    formatOptions() {
      return [
        { value: 'json', label: 'JSON', description: this.t('modal.importJsonHint'), icon: 'bi-filetype-json' },
        { value: 'excel', label: 'Excel', description: this.t('modal.importExcelHint'), icon: 'bi-file-earmark-excel' },
        { value: 'openapi', label: 'OpenAPI', description: this.t('modal.openApiHint'), icon: 'bi-filetype-yml' },
      ];
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
      if (format === this.importFormat) { return; }
      this.$emit('update:importFormat', format);
      if (this.$refs.fileInput) { this.$refs.fileInput.value = ''; }
    },
    handleDrop(event) {
      this.dragActive = false;
      const file = event.dataTransfer?.files?.[0];
      if (file) { this.$emit('handle-file', file); }
    },
    handleKeydown(event) {
      if (event.key === 'Escape') {
        event.preventDefault();
        this.$emit('close');
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
          <div class="modal-heading"><span class="modal-heading-icon"><i class="bi bi-upload" aria-hidden="true"></i></span><h2 id="importModalTitle">{{t('modal.importRule')}}</h2></div>
          <ui-button type="button" class="close-btn" @click="$emit('close')" :aria-label="t('modal.cancel')"><i class="bi bi-x-lg" aria-hidden="true"></i></ui-button>
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
              <input ref="fileInput" class="visually-hidden" type="file" :accept="fileAccept" @change="$emit('handle-file',$event)">
              <i class="bi" :class="importFile?'bi-file-earmark-check':'bi-cloud-arrow-up'" aria-hidden="true"></i>
              <span v-if="importFile" class="import-file-selected"><strong>{{importFileName}}</strong><small>{{t('modal.importReplaceFile')}}</small></span>
              <span v-else class="import-file-empty"><strong>{{t('modal.clickOrDragFile')}}</strong><small>{{acceptedFileHint}}</small></span>
            </label>
            <div v-if="error" class="import-error" role="alert"><i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>{{error}}</span></div>
            <a v-if="importFormat==='excel'" href="/api/admin/rules/import-template" class="import-template-link"><i class="bi bi-download" aria-hidden="true"></i> {{t('modal.downloadTemplate')}}</a>
          </div>
        </div>
        <div class="modal-footer">
          <ui-button type="button" variant="quiet" @click="$emit('close')">{{t('modal.cancel')}}</ui-button>
          <ui-button type="button" class="btn btn-primary" @click="$emit('do-import')" :disabled="!importFile||loading">
            <i class="bi" :class="loading?'bi-arrow-clockwise spin':(importFormat==='openapi'?'bi-eye':'bi-upload')" aria-hidden="true"></i>
            {{importFormat==='openapi' ? t('modal.previewImport') : t('modal.startImport')}}
          </ui-button>
        </div>
      </div>
    </div>
    </ui-modal-transition>
  `
};
