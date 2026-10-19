/**
 * ResponseEditModal - Response editor Modal
 *
 * Used to create or edit shared response content, supporting two modes: normal text and SSE event.
 * Common text uses ui-code-viewer (editable), the toolbar is the same as the drawer; formatting only changes the display, and the save will always be the original text.
 * The SSE event uses the same sse-events-editor as the rule editor (response has no loop settings, zooms in to the full screen of the window itself).
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
    /** The first event that has not been filled in (no data or wrong delay), -1 means it can be saved. */
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
          // Each time you open it, it starts from a clean state: the error message and zoom state that were not saved last time are not brought over.
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
    async requestSave() {
      this.attemptedSave = true;
      if (this.form.contentType === 'sse') {
        // If bulk paste is still open, apply it first and stop on invalid input; wait for the updated events before validating.
        if (this.$refs.sseEditor && !this.$refs.sseEditor.commitPending()) return;
        await this.$nextTick();
        if (!this.sseEvents.length || this.firstInvalidSse >= 0) {
          this.$refs.sseEditor?.reveal(this.firstInvalidSse);
          return;
        }
      }
      this.$emit('save');
    },
    // Adding and deleting the same array (same as the rule editor): The editor can distinguish between "add/delete" and "whole string replacement".
    // Expand new events when adding, keep searching and expanding nearby events when deleting
    addSseEvent() {
      this.sseEvents.push(SseEvents.blank());
    },
    removeSseEvent(idx) {
      if (this.sseEvents.length > 1) this.sseEvents.splice(idx, 1);
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
