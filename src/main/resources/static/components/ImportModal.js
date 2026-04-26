/**
 * ImportModal - 規則匯入對話框
 *
 * 支援 JSON、Excel、OpenAPI 格式的規則匯入，含拖放上傳。
 */
const ImportModal = {
  props: {
    show: Boolean,
    importFormat: String,
    importFile: [Object, null],
    importFileName: String
  },
  emits: ['close', 'update:importFormat', 'handle-file', 'do-import'],
  inject: ['t'],
  computed: {
    acceptTypes() {
      if (this.importFormat === 'json') { return '.json'; }
      if (this.importFormat === 'excel') { return '.xlsx,.xls'; }
      if (this.importFormat === 'openapi') { return '.json,.yaml,.yml'; }
      return '*';
    }
  },
  template: /* html */`
    <div class="modal-overlay" v-if="show">
      <div class="modal-box" style="max-width:480px">
        <div class="modal-header">
          <h3><i class="bi bi-upload"></i> {{t('modal.importRule')}}</h3>
          <button class="close-btn" @click="$emit('close')" aria-label="Close"><i class="bi bi-x-lg"></i></button>
        </div>
        <div class="modal-body" style="padding:1.5rem">
          <div class="form-section" style="margin:0">
            <div class="form-section-title">{{t('modal.selectFormat')}}</div>
            <div class="import-format-cards">
              <div class="import-card" :class="{active:importFormat==='json'}" @click="$emit('update:importFormat','json')">
                <i class="bi bi-filetype-json"></i>
                <span>JSON</span>
              </div>
              <div class="import-card" :class="{active:importFormat==='excel'}" @click="$emit('update:importFormat','excel')">
                <i class="bi bi-file-earmark-excel"></i>
                <span>Excel</span>
              </div>
              <div class="import-card" :class="{active:importFormat==='openapi'}" @click="$emit('update:importFormat','openapi')">
                <i class="bi bi-filetype-yml"></i>
                <span>OpenAPI</span>
              </div>
            </div>
            <div v-if="importFormat==='openapi'" class="openapi-hint">
              <i class="bi bi-info-circle"></i> {{t('modal.openApiHint')}}
            </div>
            <div class="form-section-title">{{t('modal.uploadFile')}}</div>
            <label class="import-dropzone" :class="{hasFile:importFile}">
              <input type="file" :accept="acceptTypes" @change="$emit('handle-file',$event)" hidden>
              <i class="bi" :class="importFile?'bi-file-earmark-check':'bi-cloud-arrow-up'"></i>
              <span v-if="importFile">{{importFileName}}</span>
              <span v-else>{{t('modal.clickOrDragFile')}}</span>
            </label>
            <a v-if="importFormat==='excel'" href="/api/admin/rules/import-template" class="import-template-link"><i class="bi bi-download"></i> {{t('modal.downloadTemplate')}}</a>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-secondary" @click="$emit('close')">{{t('modal.cancel')}}</button>
          <button class="btn btn-primary" @click="$emit('do-import')" :disabled="!importFile">
            <i class="bi" :class="importFormat==='openapi'?'bi-eye':'bi-upload'"></i>
            {{importFormat==='openapi' ? t('modal.previewImport') : t('modal.startImport')}}
          </button>
        </div>
      </div>
    </div>
  `
};
