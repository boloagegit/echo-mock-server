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
          this.$refs.jsonFormat?.focus();
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
    <div ref="overlay" class="modal-overlay" v-if="show" @keydown="handleKeydown">
      <div ref="dialog" class="modal-box workspace-modal import-modal" role="dialog" aria-modal="true" aria-labelledby="importModalTitle" tabindex="-1">
        <div class="modal-header">
          <div class="modal-heading"><span class="modal-heading-icon"><i class="bi bi-upload" aria-hidden="true"></i></span><h3 id="importModalTitle">{{t('modal.importRule')}}</h3></div>
          <button type="button" class="close-btn" @click="$emit('close')" :aria-label="t('modal.cancel')"><i class="bi bi-x-lg" aria-hidden="true"></i></button>
        </div>
        <div class="modal-body import-modal-body">
          <fieldset class="import-format-fieldset">
            <legend class="form-label">{{t('modal.selectFormat')}}</legend>
            <div class="import-format-options">
              <button ref="jsonFormat" type="button" class="import-format-option" :class="{active:importFormat==='json'}" :aria-pressed="importFormat==='json'" @click="selectFormat('json')">
                <i class="bi bi-filetype-json" aria-hidden="true"></i>
                <span><strong>JSON</strong><small>{{t('modal.importJsonHint')}}</small></span>
                <i v-if="importFormat==='json'" class="bi bi-check2" aria-hidden="true"></i>
              </button>
              <button type="button" class="import-format-option" :class="{active:importFormat==='excel'}" :aria-pressed="importFormat==='excel'" @click="selectFormat('excel')">
                <i class="bi bi-file-earmark-excel" aria-hidden="true"></i>
                <span><strong>Excel</strong><small>{{t('modal.importExcelHint')}}</small></span>
                <i v-if="importFormat==='excel'" class="bi bi-check2" aria-hidden="true"></i>
              </button>
              <button type="button" class="import-format-option" :class="{active:importFormat==='openapi'}" :aria-pressed="importFormat==='openapi'" @click="selectFormat('openapi')">
                <i class="bi bi-filetype-yml" aria-hidden="true"></i>
                <span><strong>OpenAPI</strong><small>{{t('modal.openApiHint')}}</small></span>
                <i v-if="importFormat==='openapi'" class="bi bi-check2" aria-hidden="true"></i>
              </button>
            </div>
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
          <button type="button" class="btn btn-secondary" @click="$emit('close')">{{t('modal.cancel')}}</button>
          <button type="button" class="btn btn-primary" @click="$emit('do-import')" :disabled="!importFile||loading">
            <i class="bi" :class="loading?'bi-arrow-clockwise spin':(importFormat==='openapi'?'bi-eye':'bi-upload')" aria-hidden="true"></i>
            {{importFormat==='openapi' ? t('modal.previewImport') : t('modal.startImport')}}
          </button>
        </div>
      </div>
    </div>
  `
};
