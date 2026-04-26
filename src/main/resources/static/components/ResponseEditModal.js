/**
 * ResponseEditModal - 回應編輯 Modal
 *
 * 用於建立或編輯共用回應內容，支援一般文字與 SSE 事件兩種模式。
 * CodeMirror 編輯器由父元件管理，透過 ref 傳入。
 */
const ResponseEditModal = {
  props: {
    show: Boolean,
    editing: Object,
    form: Object,
    maximized: Boolean,
    sseEvents: Array,
    ssePreview: String,
    responseFormFormatted: Boolean,
  },
  emits: [
    'close', 'save', 'update:form', 'update:maximized',
    'update:sseEvents', 'toggle-format',
  ],
  inject: ['t'],
  methods: {
    fmtSize,
    sseRowClass(evt) {
      if (evt.type === 'error') { return 'sse-row-error'; }
      if (evt.type === 'abort') { return 'sse-row-abort'; }
      return '';
    },
    addSseEvent() {
      const events = [...this.sseEvents, { event: '', data: '', id: '', delayMs: 0, type: 'normal' }];
      this.$emit('update:sseEvents', events);
    },
    removeSseEvent(idx) {
      const events = this.sseEvents.filter((_, i) => i !== idx);
      this.$emit('update:sseEvents', events);
    },
    updateContentType(ct) {
      this.$emit('update:form', { ...this.form, contentType: ct });
    },
    updateDescription(val) {
      this.$emit('update:form', { ...this.form, description: val });
    },
  },
  template: /* html */`
    <div class="modal-overlay" v-if="show" :style="maximized?'padding:0':''">
      <div class="modal-box response-modal" :class="{maximized:maximized}">
        <div class="modal-header">
          <h3><i class="bi" :class="editing?'bi-pencil-square':'bi-plus-circle'"></i> {{editing ? t('modal.editResponse') : t('modal.addResponse')}}</h3>
          <div style="display:flex;align-items:center;gap:0.25rem">
            <button class="close-btn" @click="$emit('update:maximized', !maximized)" :title="maximized ? t('modal.restoreWindow') : t('modal.fullscreen')" :aria-label="maximized ? t('modal.restoreWindow') : t('modal.fullscreen')"><i class="bi" :class="maximized?'bi-fullscreen-exit':'bi-arrows-fullscreen'"></i></button>
            <button class="close-btn" @click="$emit('close')" aria-label="Close"><i class="bi bi-x-lg"></i></button>
          </div>
        </div>
        <div class="modal-body" style="flex:1;display:flex;flex-direction:column">
          <div class="form-group"><label class="form-label">{{t('modal.description')}}</label><input class="form-control" :value="form.description" @input="updateDescription($event.target.value)" :placeholder="t('modal.descriptionPlaceholder')"></div>
          <div class="form-group">
            <label class="form-label">{{t('modal.responseType')}}</label>
            <div class="protocol-switch">
              <button type="button" class="protocol-btn" :class="{active:form.contentType==='text'}" @click="updateContentType('text')">
                <i class="bi bi-file-earmark-text"></i> {{t('modal.responseTypeGeneral')}}
              </button>
              <button type="button" class="protocol-btn" :class="{active:form.contentType==='sse'}" @click="updateContentType('sse')">
                <i class="bi bi-broadcast"></i> {{t('modal.responseTypeSse')}}
              </button>
            </div>
          </div>
          <div v-if="form.contentType==='text'" class="form-group" style="flex:1;display:flex;flex-direction:column;min-height:0">
            <div style="display:flex;align-items:center;gap:0.5rem;margin-bottom:0.5rem">
              <label class="form-label" style="margin:0">{{t('modal.responseContent')}}</label>
              <button type="button" class="btn btn-xs btn-secondary" @click="$emit('toggle-format')">
                <i class="bi" :class="responseFormFormatted?'bi-code':'bi-braces'"></i>
                {{responseFormFormatted ? t('modal.plainText') : t('modal.format')}}
              </button>
              <span class="sub-info" style="margin-left:auto">{{fmtSize(form.body?.length || 0)}}</span>
              <span v-if="(form.body?.length || 0) > 5242880" class="badge badge-warning" :title="t('modal.exceedCacheThreshold')"><i class="bi bi-exclamation-triangle"></i> {{t('modal.exceedCacheThreshold')}}</span>
            </div>
            <div id="responseFormEditorEl" class="edit-editor" style="flex:1;min-height:200px"></div>
          </div>
          <div v-else class="form-group" style="flex:1;display:flex;flex-direction:column;overflow:auto">
            <div class="sse-table" style="overflow:auto;flex-shrink:0">
              <table style="font-size:var(--font-base)">
                <thead>
                  <tr>
                    <th style="width:100px">類型</th>
                    <th style="width:120px">event</th>
                    <th>data <span class="text-danger">*</span></th>
                    <th style="width:80px">id</th>
                    <th style="width:80px">延遲(ms)</th>
                    <th style="width:40px"></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(evt, idx) in sseEvents" :key="idx" :class="sseRowClass(evt)">
                    <td>
                      <select class="form-select form-select-sm" v-model="evt.type">
                        <option value="normal">normal</option>
                        <option value="error">error</option>
                        <option value="abort">abort</option>
                      </select>
                    </td>
                    <td><input class="form-control form-control-sm" v-model="evt.event" :placeholder="t('modal.sseEventName')"></td>
                    <td><textarea class="form-control form-control-sm" v-model="evt.data" :placeholder="t('modal.sseEventData')" rows="1" style="min-height:30px;resize:vertical;font-family:var(--font-mono);font-size:var(--font-xs)"></textarea></td>
                    <td><input class="form-control form-control-sm" v-model="evt.id" placeholder="ID"></td>
                    <td><input class="form-control form-control-sm" type="number" v-model.number="evt.delayMs" min="0" placeholder="0"></td>
                    <td style="text-align:center;vertical-align:middle">
                      <button type="button" class="btn btn-xs btn-icon btn-outline-danger" @click="removeSseEvent(idx)" :disabled="sseEvents.length<=1" :title="t('modal.deleteSseEvent')">
                        <i class="bi bi-trash"></i>
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div style="display:flex;align-items:center;gap:0.5rem;margin-top:0.5rem">
              <button type="button" class="cond-add" @click="addSseEvent()" style="flex:none">
                <i class="bi bi-plus-circle"></i> {{t('modal.addSseEvent')}}
              </button>
              <span class="sub-info" style="margin-left:auto">{{t('modal.sseEventCount', {count: sseEvents.length})}}</span>
            </div>
            <div v-if="ssePreview" class="sse-preview" style="margin-top:0.5rem;flex-shrink:0">
              <div class="sub-info" style="font-size:var(--font-xs);margin-bottom:4px"><i class="bi bi-eye"></i> {{t('modal.sseStreamPreview')}}</div>
              <pre style="margin:0;font-size:var(--font-xs);white-space:pre-wrap;word-break:break-all">{{ssePreview}}</pre>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-secondary" @click="$emit('close')">{{t('modal.cancel')}}</button>
          <button class="btn btn-primary" @click="$emit('save')"><i class="bi bi-check-lg"></i> {{editing ? t('modal.update') : t('modal.create')}}</button>
        </div>
      </div>
    </div>
  `
};
