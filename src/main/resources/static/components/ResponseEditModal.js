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
    saving: Boolean,
    sseEvents: Array,
    ssePreview: String,
    responseFormFormatted: Boolean,
  },
  emits: [
    'close', 'save', 'update:form', 'update:maximized',
    'update:sseEvents', 'toggle-format',
  ],
  inject: ['t'],
  data() {
    return {
      attemptedSave: false,
      returnFocusTo: null,
      inertedElements: [],
    };
  },
  computed: {
    sseErrors() {
      if (!this.attemptedSave || this.form.contentType !== 'sse') { return []; }
      return this.sseEvents.map(event => {
        const data = event?.data == null ? '' : String(event.data);
        const rawDelay = event?.delayMs;
        const delay = rawDelay === '' || rawDelay == null ? 0 : Number(rawDelay);
        return {
          dataRequired: data.length === 0,
          delayInvalid: !Number.isInteger(delay) || delay < 0 || delay > 30000,
        };
      });
    },
    hasSseErrors() {
      return !this.sseEvents.length || this.sseErrors.some(error => error.dataRequired || error.delayInvalid);
    },
    hasPreviewContent() {
      return this.sseEvents.some(event => {
        const type = event?.type || 'normal';
        return type === 'abort' || String(event?.data || '').length > 0;
      });
    },
    previewEntries() {
      if (!this.hasPreviewContent) { return []; }
      const entries = [];
      for (let index = 0; index < this.sseEvents.length; index++) {
        const event = this.sseEvents[index] || {};
        const type = event.type || 'normal';
        const data = event.data == null ? '' : String(event.data);
        const eventName = type === 'error' ? 'error' : (event.event || 'message');
        entries.push({
          index: index + 1,
          type,
          typeLabel: this.t(type === 'error' ? 'modal.sseTypeError' : type === 'abort' ? 'modal.sseTypeAbort' : 'modal.sseTypeNormal'),
          eventName,
          dataLines: data.split('\n'),
          id: event.id || '',
          delayMs: Number(event.delayMs) || 0,
          terminal: type === 'error' || type === 'abort',
        });
        if (type === 'error' || type === 'abort') { break; }
      }
      return entries;
    },
    previewSkippedCount() {
      const terminal = this.previewEntries.find(entry => entry.terminal);
      return terminal ? Math.max(0, this.sseEvents.length - terminal.index) : 0;
    },
  },
  watch: {
    show: {
      immediate: true,
      handler(open) {
        if (open) { this.activateDialog(); }
        else { this.deactivateDialog(); }
      },
    },
    'form.contentType'() {
      this.attemptedSave = false;
    },
  },
  beforeUnmount() {
    this.deactivateDialog();
  },
  methods: {
    fmtSize,
    activateDialog() {
      this.returnFocusTo = document.activeElement;
      this.$nextTick(() => {
        const dialog = this.$refs.dialog;
        const overlay = this.$refs.overlay;
        if (!dialog || !overlay) { return; }
        this.setBackgroundInert(overlay);
        this.$refs.descriptionInput?.focus();
      });
    },
    deactivateDialog() {
      this.inertedElements.forEach(({ element, inert }) => { element.inert = inert; });
      this.inertedElements = [];
      const target = this.returnFocusTo;
      this.returnFocusTo = null;
      if (target?.isConnected) { this.$nextTick(() => target.focus()); }
    },
    setBackgroundInert(overlay) {
      let current = overlay;
      while (current?.parentElement && current.parentElement !== document.body) {
        [...current.parentElement.children].forEach(element => {
          if (element === current) { return; }
          this.inertedElements.push({ element, inert: element.inert });
          element.inert = true;
        });
        current = current.parentElement;
      }
    },
    focusableElements() {
      const dialog = this.$refs.dialog;
      if (!dialog) { return []; }
      const selector = 'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [href], [tabindex]:not([tabindex="-1"])';
      return [...dialog.querySelectorAll(selector)].filter(element => element.getClientRects().length > 0);
    },
    handleDialogKeydown(event) {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        this.$emit('close');
        return;
      }
      if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
        event.preventDefault();
        this.requestSave();
        return;
      }
      if (event.key !== 'Tab') { return; }
      const elements = this.focusableElements();
      if (!elements.length) { return; }
      const first = elements[0];
      const last = elements[elements.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    },
    requestSave() {
      this.attemptedSave = true;
      if (this.form.contentType === 'sse' && this.hasSseErrors) {
        this.$nextTick(() => this.$refs.dialog?.querySelector('[data-sse-invalid="true"]')?.focus());
        return;
      }
      this.$emit('save');
    },
    addSseEvent() {
      const events = [...this.sseEvents, { event: '', data: '', id: '', delayMs: 0, type: 'normal' }];
      this.$emit('update:sseEvents', events);
      this.$nextTick(() => this.$refs.dialog?.querySelector(`[data-sse-event-name="${events.length - 1}"]`)?.focus());
    },
    removeSseEvent(idx) {
      const events = this.sseEvents.filter((_, i) => i !== idx);
      this.$emit('update:sseEvents', events);
      this.$nextTick(() => {
        const nextIndex = Math.min(idx, events.length - 1);
        this.$refs.dialog?.querySelector(`[data-sse-type="${nextIndex}"]`)?.focus();
      });
    },
    updateSseEvent(idx, field, value) {
      const events = this.sseEvents.map((event, eventIndex) => (
        eventIndex === idx ? { ...event, [field]: value } : event
      ));
      this.$emit('update:sseEvents', events);
    },
    updateSseDelay(idx, value) {
      const delayMs = value === '' ? '' : Number(value);
      this.updateSseEvent(idx, 'delayMs', delayMs);
    },
    sseTypeHelp(type) {
      const keys = {
        normal: 'modal.sseTypeNormalHelp',
        error: 'modal.sseTypeErrorHelp',
        abort: 'modal.sseTypeAbortHelp',
      };
      return this.t(keys[type] || keys.normal);
    },
    sseFieldLabel(field, idx) {
      return this.t('modal.sseFieldLabel', { field, index: idx + 1 });
    },
    sseError(idx, field) {
      return Boolean(this.sseErrors[idx]?.[field]);
    },
    updateContentType(contentType) {
      this.$emit('update:form', { ...this.form, contentType });
    },
    updateDescription(description) {
      this.$emit('update:form', { ...this.form, description });
    },
  },
  template: /* html */`
    <div ref="overlay" class="modal-overlay" v-if="show" :style="maximized?'padding:0':''">
      <div ref="dialog" class="modal-box response-modal workspace-modal response-editor-modal" :class="{maximized:maximized}" role="dialog" aria-modal="true" aria-labelledby="responseEditorTitle" @keydown="handleDialogKeydown">
        <div class="modal-header">
          <div class="modal-heading">
            <span class="modal-heading-icon"><i class="bi" :class="editing?'bi-pencil-square':'bi-plus-circle'" aria-hidden="true"></i></span>
            <h3 id="responseEditorTitle">{{editing ? t('modal.editResponse') : t('modal.addResponse')}}</h3>
          </div>
          <div class="response-modal-actions">
            <button type="button" class="close-btn" @click="$emit('update:maximized', !maximized)" :title="maximized ? t('modal.restoreWindow') : t('modal.fullscreen')" :aria-label="maximized ? t('modal.restoreWindow') : t('modal.fullscreen')"><i class="bi" :class="maximized?'bi-fullscreen-exit':'bi-arrows-fullscreen'" aria-hidden="true"></i></button>
            <button type="button" class="close-btn" @click="$emit('close')" :title="t('modal.cancel')" :aria-label="t('modal.cancel')"><i class="bi bi-x-lg" aria-hidden="true"></i></button>
          </div>
        </div>
        <div class="modal-body">
          <div class="form-group"><label class="form-label" for="responseDescription">{{t('modal.description')}}</label><input ref="descriptionInput" id="responseDescription" class="form-control" :value="form.description" @input="updateDescription($event.target.value)" :placeholder="t('modal.descriptionPlaceholder')" maxlength="255"></div>
          <div class="form-group">
            <span class="form-label" id="responseTypeLabel">{{t('modal.responseType')}}</span>
            <div class="protocol-switch response-type-switch" role="group" aria-labelledby="responseTypeLabel">
              <button type="button" class="protocol-btn" :class="{active:form.contentType==='text'}" :aria-pressed="form.contentType==='text'" @click="updateContentType('text')">
                <i class="bi bi-file-earmark-text" aria-hidden="true"></i><span>{{t('modal.responseTypeGeneral')}}</span><i v-if="form.contentType==='text'" class="bi bi-check2 response-type-check" aria-hidden="true"></i>
              </button>
              <button type="button" class="protocol-btn" :class="{active:form.contentType==='sse'}" :aria-pressed="form.contentType==='sse'" @click="updateContentType('sse')">
                <i class="bi bi-broadcast" aria-hidden="true"></i><span>{{t('modal.responseTypeSse')}}</span><i v-if="form.contentType==='sse'" class="bi bi-check2 response-type-check" aria-hidden="true"></i>
              </button>
            </div>
          </div>
          <div v-if="form.contentType==='text'" class="form-group response-text-editor">
            <div class="response-editor-toolbar">
              <label class="form-label">{{t('modal.responseContent')}}</label>
              <button type="button" class="btn btn-xs btn-secondary" @click="$emit('toggle-format')">
                <i class="bi" :class="responseFormFormatted?'bi-code':'bi-braces'" aria-hidden="true"></i>
                {{responseFormFormatted ? t('modal.plainText') : t('modal.format')}}
              </button>
              <span class="sub-info response-content-size">{{fmtSize(form.body?.length || 0)}}</span>
              <span v-if="(form.body?.length || 0) > 5242880" class="badge badge-warning" :title="t('modal.exceedCacheTooltip')"><i class="bi bi-exclamation-triangle" aria-hidden="true"></i> {{t('modal.exceedCacheThreshold')}}</span>
            </div>
            <div id="responseFormEditorEl" class="edit-editor"></div>
          </div>
          <div v-else class="form-group response-sse-workspace">
            <section class="response-sse-editor-pane" aria-labelledby="sseEventsTitle">
              <div class="response-sse-section-header">
                <div class="response-sse-section-title">
                  <h4 id="sseEventsTitle">{{t('modal.sseEventsTitle')}}</h4>
                  <button type="button" class="help-tooltip tooltip-align-start" :data-tooltip="t('modal.sseEventsHelp')" :aria-label="t('modal.sseEventsHelp')"><i class="bi bi-question-circle" aria-hidden="true"></i></button>
                  <span class="response-sse-count">{{t('modal.sseEventCount', {count: sseEvents.length})}}</span>
                </div>
                <button type="button" class="btn btn-sm btn-secondary response-sse-add" @click="addSseEvent()"><i class="bi bi-plus-lg" aria-hidden="true"></i> {{t('modal.addSseEvent')}}</button>
              </div>
              <div class="sse-table response-sse-table" tabindex="0" :aria-label="t('modal.sseEventsTableLabel')">
                <table>
                  <caption class="visually-hidden">{{t('modal.sseEventsTableLabel')}}</caption>
                  <thead><tr>
                    <th class="sse-col-index" scope="col">#</th>
                    <th class="sse-col-type" scope="col">{{t('modal.sseEventType')}}</th>
                    <th class="sse-col-event" scope="col">{{t('modal.sseEventName')}}</th>
                    <th class="sse-col-data" scope="col">{{t('modal.sseEventData')}} <span class="text-danger" aria-hidden="true">*</span></th>
                    <th class="sse-col-id" scope="col">{{t('modal.sseEventId')}}</th>
                    <th class="sse-col-delay" scope="col">{{t('modal.sseEventDelay')}}</th>
                    <th class="sse-col-actions" scope="col"><span class="visually-hidden">{{t('modal.actions')}}</span></th>
                  </tr></thead>
                  <tbody>
                    <tr v-for="(evt, idx) in sseEvents" :key="idx">
                      <td class="sse-event-index">{{idx + 1}}</td>
                      <td><select class="form-select form-select-sm" :data-sse-type="idx" :value="evt.type" @change="updateSseEvent(idx, 'type', $event.target.value)" :title="sseTypeHelp(evt.type)" :aria-label="sseFieldLabel(t('modal.sseEventType'), idx)"><option value="normal">{{t('modal.sseTypeNormal')}}</option><option value="error">{{t('modal.sseTypeError')}}</option><option value="abort">{{t('modal.sseTypeAbort')}}</option></select></td>
                      <td><input class="form-control form-control-sm" :data-sse-event-name="idx" :value="evt.type==='error' ? 'error' : (evt.type==='abort' ? '' : evt.event)" @input="updateSseEvent(idx, 'event', $event.target.value)" :placeholder="evt.type==='abort' ? t('modal.notApplicableShort') : t('modal.sseEventNamePlaceholder')" :disabled="evt.type!=='normal'" :title="evt.type==='normal' ? '' : sseTypeHelp(evt.type)" :aria-label="sseFieldLabel(t('modal.sseEventName'), idx)"></td>
                      <td>
                        <textarea class="form-control form-control-sm sse-data-input" :class="{'is-invalid':sseError(idx, 'dataRequired')}" :data-sse-invalid="sseError(idx, 'dataRequired')" :value="evt.data" @input="updateSseEvent(idx, 'data', $event.target.value)" :placeholder="t('modal.sseEventDataPlaceholder')" :aria-label="sseFieldLabel(t('modal.sseEventData'), idx)" :aria-invalid="sseError(idx, 'dataRequired') ? 'true' : 'false'" :aria-describedby="sseError(idx, 'dataRequired') ? 'sse-data-error-'+idx : null" rows="4"></textarea>
                        <div v-if="sseError(idx, 'dataRequired')" class="invalid-feedback" :id="'sse-data-error-'+idx">{{t('modal.sseEventDataRequired')}}</div>
                      </td>
                      <td><input class="form-control form-control-sm" :value="evt.type==='abort' ? '' : evt.id" @input="updateSseEvent(idx, 'id', $event.target.value)" :placeholder="evt.type==='abort' ? t('modal.notApplicableShort') : t('modal.sseEventIdPlaceholder')" :disabled="evt.type==='abort'" :title="evt.type==='abort' ? sseTypeHelp(evt.type) : ''" :aria-label="sseFieldLabel(t('modal.sseEventId'), idx)"></td>
                      <td>
                        <div class="input-affix sse-delay-affix" :class="{'is-invalid':sseError(idx, 'delayInvalid')}">
                          <input class="form-control form-control-sm" type="number" :data-sse-invalid="sseError(idx, 'delayInvalid')" :value="evt.delayMs" @input="updateSseDelay(idx, $event.target.value)" min="0" max="30000" step="1" inputmode="numeric" placeholder="0" :aria-label="sseFieldLabel(t('modal.sseEventDelay'), idx)" :aria-invalid="sseError(idx, 'delayInvalid') ? 'true' : 'false'" :aria-describedby="sseError(idx, 'delayInvalid') ? 'sse-delay-error-'+idx : null">
                          <span class="input-affix-postfix" aria-hidden="true">{{t('modal.millisecondsShort')}}</span>
                        </div>
                        <div v-if="sseError(idx, 'delayInvalid')" class="invalid-feedback" :id="'sse-delay-error-'+idx">{{t('modal.sseEventDelayInvalid')}}</div>
                      </td>
                      <td class="sse-event-actions"><button type="button" class="btn btn-icon sse-remove-event" @click="removeSseEvent(idx)" :disabled="sseEvents.length<=1" :title="t('modal.deleteSseEventAt', {index: idx + 1})" :aria-label="t('modal.deleteSseEventAt', {index: idx + 1})"><i class="bi bi-trash" aria-hidden="true"></i></button></td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </section>
            <section class="response-sse-preview-pane" role="region" aria-labelledby="ssePreviewTitle">
              <div class="response-sse-section-header">
                <div class="response-sse-section-title"><i class="bi bi-eye" aria-hidden="true"></i><h4 id="ssePreviewTitle">{{t('modal.sseStreamPreview')}}</h4><button type="button" class="help-tooltip tooltip-align-end" :data-tooltip="t('modal.ssePreviewHelp')" :aria-label="t('modal.ssePreviewHelp')"><i class="bi bi-question-circle" aria-hidden="true"></i></button></div>
              </div>
              <div v-if="previewEntries.length" class="response-sse-preview-list">
                <article v-for="entry in previewEntries" :key="entry.index" class="response-sse-preview-event" :class="'is-'+entry.type">
                  <div class="response-sse-preview-meta">
                    <span class="response-sse-preview-index">{{t('modal.ssePreviewEventIndex', {index:entry.index})}}</span>
                    <span class="response-sse-preview-type"><i class="bi" :class="entry.type==='error'?'bi-exclamation-triangle':entry.type==='abort'?'bi-stop-circle':'bi-broadcast-pin'" aria-hidden="true"></i>{{entry.typeLabel}}</span>
                    <span v-if="entry.delayMs" class="response-sse-preview-delay"><i class="bi bi-clock" aria-hidden="true"></i>{{t('modal.ssePreviewDelay', {ms:entry.delayMs})}}</span>
                  </div>
                  <code v-if="entry.type!=='abort'" class="response-sse-wire">
                    <span class="response-sse-wire-line"><span class="response-sse-wire-key">event:</span><span>{{entry.eventName}}</span></span>
                    <span v-for="(line,lineIndex) in entry.dataLines" :key="lineIndex" class="response-sse-wire-line"><span class="response-sse-wire-key">data:</span><span>{{line}}</span></span>
                    <span v-if="entry.id" class="response-sse-wire-line"><span class="response-sse-wire-key">id:</span><span>{{entry.id}}</span></span>
                  </code>
                  <div v-if="entry.type==='error'" class="response-sse-terminal"><i class="bi bi-x-octagon" aria-hidden="true"></i>{{t('modal.ssePreviewErrorEnds')}}</div>
                  <div v-else-if="entry.type==='abort'" class="response-sse-terminal"><i class="bi bi-stop-circle" aria-hidden="true"></i>{{t('modal.ssePreviewAbortEnds')}}</div>
                </article>
                <div v-if="previewSkippedCount" class="response-sse-preview-skipped"><i class="bi bi-skip-forward" aria-hidden="true"></i>{{t('ssePreview.remainingSkipped', {count:previewSkippedCount})}}</div>
              </div>
              <div v-else class="response-sse-preview-empty"><i class="bi bi-code-square" aria-hidden="true"></i><span>{{t('modal.ssePreviewEmpty')}}</span></div>
            </section>
          </div>
        </div>
        <div class="modal-footer">
          <span class="response-save-shortcut">{{t('modal.saveShortcutHint')}}</span>
          <button type="button" class="btn btn-secondary" @click="$emit('close')">{{t('modal.cancel')}}</button>
          <button type="button" class="btn btn-primary" @click="requestSave" :disabled="saving"><i class="bi" :class="saving?'bi-arrow-clockwise spin':'bi-check-lg'" aria-hidden="true"></i> {{editing ? t('modal.update') : t('modal.create')}}</button>
        </div>
      </div>
    </div>
  `
};
