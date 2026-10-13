/**
 * ResponseEditModal - 回應編輯 Modal
 *
 * 用於建立或編輯共用回應內容，支援一般文字與 SSE 事件兩種模式。
 * 一般文字使用共用的 ui-code-viewer（可編輯），工具列與抽屜相同；格式化只改變顯示，存檔永遠是原文。
 * SSE 事件使用與規則編輯器相同的 sse-events-editor（回應沒有循環設定，放大用視窗本身的全螢幕）。
 */
const ResponseEditModal = {
  props: {
    show: Boolean,
    editing: Object,
    form: Object,
    maximized: Boolean,
    saving: Boolean,
    sseEvents: Array,
  },
  emits: [
    'close', 'save', 'update:form', 'update:maximized',
    'update:sseEvents', 'copy-text',
  ],
  inject: ['t'],
  data() {
    return {
      saveShortcutKey: SAVE_SHORTCUT_KEY,
      attemptedSave: false,
      returnFocusTo: null,
      inertedElements: [],
      sseExpanded: false,
    };
  },
  computed: {
    responseTypeOptions() {
      return [
        { value: 'text', label: this.t('modal.responseTypeGeneral'), icon: 'bi-file-earmark-text' },
        { value: 'sse', label: this.t('modal.responseTypeSse'), icon: 'bi-broadcast' },
      ];
    },
    /** 第一個還沒填好的事件（沒有資料或延遲不對），-1 表示都可以存。 */
    firstInvalidSse() {
      return this.sseEvents.findIndex(event => !event?.data || !SseEvents.delayValid(event.delayMs));
    },
    sseError() {
      if (!this.attemptedSave || this.form.contentType !== 'sse') return '';
      if (!this.sseEvents.length) return this.t('validation.sseArrayEmpty');
      return this.firstInvalidSse >= 0 ? this.t('sseEditor.fixBeforeSave', { index: this.firstInvalidSse + 1 }) : '';
    },
  },
  watch: {
    show: {
      immediate: true,
      handler(open) {
        if (open) {
          // 每次打開都從乾淨的狀態開始：上一次沒存成的錯誤提示與放大狀態不帶過來
          this.attemptedSave = false;
          this.sseExpanded = false;
          this.activateDialog();
        }
        else { this.deactivateDialog(); }
      },
    },
    'form.contentType'() {
      this.attemptedSave = false;
      this.sseExpanded = false;
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
      if (this.form.contentType === 'sse' && (!this.sseEvents.length || this.firstInvalidSse >= 0)) {
        this.$refs.sseEditor?.reveal(this.firstInvalidSse);
        return;
      }
      this.$emit('save');
    },
    addSseEvent() {
      this.$emit('update:sseEvents', [...this.sseEvents, SseEvents.blank()]);
    },
    removeSseEvent(idx) {
      this.$emit('update:sseEvents', this.sseEvents.filter((_, i) => i !== idx));
    },
    updateContentType(contentType) {
      this.$emit('update:form', { ...this.form, contentType });
    },
    updateDescription(description) {
      this.$emit('update:form', { ...this.form, description });
    },
    updateBody(body) {
      this.$emit('update:form', { ...this.form, body });
    },
  },
  template: /* html */`
    <ui-modal-transition>
    <div ref="overlay" class="modal-overlay" v-if="show" :style="maximized?'padding:0':''">
      <div ref="dialog" class="modal-box response-modal workspace-modal response-editor-modal" :class="{maximized:maximized}" role="dialog" aria-modal="true" aria-labelledby="responseEditorTitle" @keydown="handleDialogKeydown">
        <div class="modal-header">
          <div class="modal-heading">
            <h2 id="responseEditorTitle">{{editing ? t('modal.editResponse') : t('modal.addResponse')}}</h2>
          </div>
          <div class="response-modal-actions">
            <ui-button type="button" class="close-btn" @click="$emit('update:maximized', !maximized)" :title="maximized ? t('modal.restoreWindow') : t('modal.fullscreen')" :aria-label="maximized ? t('modal.restoreWindow') : t('modal.fullscreen')"><i class="bi" :class="maximized?'bi-fullscreen-exit':'bi-arrows-fullscreen'" aria-hidden="true"></i></ui-button>
            <ui-button type="button" class="close-btn" @click="$emit('close')" :title="t('modal.cancel')" :aria-label="t('modal.cancel')"><i class="bi bi-x-lg" aria-hidden="true"></i></ui-button>
          </div>
        </div>
        <div class="modal-body">
          <div class="form-group"><label class="form-label" for="responseDescription">{{t('modal.description')}}</label><input ref="descriptionInput" id="responseDescription" class="form-control" :value="form.description" @input="updateDescription($event.target.value)" :placeholder="t('modal.descriptionPlaceholder')" maxlength="255"></div>
          <div class="form-group">
            <span class="form-label" id="responseTypeLabel">{{t('modal.responseType')}}</span>
            <ui-choice-group class="protocol-switch response-type-switch" option-class="protocol-btn" variant="compact" show-check
              :model-value="form.contentType" :options="responseTypeOptions" :aria-label="t('modal.responseType')"
              @update:model-value="updateContentType"></ui-choice-group>
          </div>
          <div v-if="form.contentType==='text'" class="form-group response-text-editor">
            <div class="response-editor-toolbar">
              <label class="form-label">{{t('modal.responseContent')}}</label>
              <span class="sub-info response-content-size">{{fmtSize(form.body?.length || 0)}}</span>
              <span v-if="(form.body?.length || 0) > 5242880" class="badge badge-warning" :title="t('modal.exceedCacheTooltip')"><i class="bi bi-exclamation-triangle" aria-hidden="true"></i> {{t('modal.exceedCacheThreshold')}}</span>
            </div>
            <ui-code-viewer class="response-form-code" fill editable :label="t('modal.responseContent')" :placeholder="t('modal.responseBodyPlaceholder')"
              :value="form.body || ''" @update:value="updateBody" @copy="$emit('copy-text', $event)"></ui-code-viewer>
          </div>
          <div v-else class="form-group response-sse-workspace" :class="{'is-expanded': sseExpanded}">
            <sse-events-editor ref="sseEditor" class="sse-editor-wrap response-sse-editor" :events="sseEvents" :loopable="false" :expandable="false"
              :shared-count="editing?.usageCount || 0" :expanded="sseExpanded" @update:expanded="sseExpanded = $event" :error="sseError"
              @add="addSseEvent" @remove="removeSseEvent" @replace="$emit('update:sseEvents', $event)"></sse-events-editor>
          </div>
        </div>
        <div class="modal-footer">
          <span class="modal-footer-hint"><kbd>{{saveShortcutKey}}</kbd><kbd>Enter</kbd>{{t('modal.save')}}</span>
          <ui-button type="button" variant="quiet" @click="$emit('close')">{{t('modal.cancel')}}</ui-button>
          <ui-button type="button" variant="primary" @click="requestSave" :disabled="saving"><ui-motion-icon :icon="saving?'bi-arrow-clockwise':'bi-check-lg'" :spin="saving"></ui-motion-icon> {{editing ? t('modal.update') : t('modal.create')}}</ui-button>
        </div>
      </div>
    </div>
    </ui-modal-transition>
  `
};
