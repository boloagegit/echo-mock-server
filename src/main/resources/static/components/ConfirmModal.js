/**
 * ConfirmModal - 通用確認對話框
 *
 * 保留一般、危險與輸入確認三種流程，集中處理焦點、鍵盤及驗證回饋。
 */
const ConfirmModal = {
  props: {
    confirmState: Object
  },
  inject: ['t'],
  data() {
    return {
      attempted: false,
      previousFocus: null,
      inertSiblings: []
    };
  },
  computed: {
    hasRequiredInput() {
      return this.confirmState.requireInput != null;
    },
    inputMismatch() {
      return this.attempted && this.hasRequiredInput && this.confirmState.inputValue !== this.confirmState.requireInput;
    }
  },
  mounted() {
    this._onKey = event => this.handleKeydown(event);
  },
  beforeUnmount() {
    document.removeEventListener('keydown', this._onKey);
    this.restoreBackground();
  },
  watch: {
    'confirmState.show'(visible) {
      if (visible) {
        this.attempted = false;
        this.previousFocus = document.activeElement;
        document.addEventListener('keydown', this._onKey);
        this.$nextTick(() => {
          this.makeBackgroundInert();
          this.$refs.cancelButton?.focus();
        });
        return;
      }
      document.removeEventListener('keydown', this._onKey);
      this.restoreBackground();
      this.previousFocus?.focus?.();
      this.previousFocus = null;
    }
  },
  methods: {
    makeBackgroundInert() {
      const overlay = this.$el;
      const parent = overlay?.parentElement;
      if (!parent) { return; }
      this.inertSiblings = Array.from(parent.children).filter(node => node !== overlay).map(node => ({
        node,
        inert: node.inert,
        ariaHidden: node.getAttribute('aria-hidden')
      }));
      this.inertSiblings.forEach(({ node }) => {
        node.inert = true;
        node.setAttribute('aria-hidden', 'true');
      });
    },
    restoreBackground() {
      this.inertSiblings.forEach(({ node, inert, ariaHidden }) => {
        node.inert = inert;
        if (ariaHidden == null) { node.removeAttribute('aria-hidden'); }
        else { node.setAttribute('aria-hidden', ariaHidden); }
      });
      this.inertSiblings = [];
    },
    handleKeydown(event) {
      if (!this.confirmState.show) { return; }
      if (event.key === 'Escape') {
        event.preventDefault();
        this.confirmState.onCancel();
        return;
      }
      if (event.key !== 'Tab') { return; }
      const box = this.$refs.dialog;
      if (!box) { return; }
      const focusable = Array.from(box.querySelectorAll('button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])'));
      if (!focusable.length) { return; }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    },
    submitConfirm() {
      this.attempted = true;
      if (this.hasRequiredInput && this.confirmState.inputValue !== this.confirmState.requireInput) {
        this.$nextTick(() => this.$refs.confirmInput?.focus());
        return;
      }
      this.confirmState.onConfirm();
    }
  },
  template: /* html */`
    <div v-if="confirmState.show" class="modal-overlay" @click.self="confirmState.onCancel">
      <div ref="dialog" class="modal-box workspace-modal confirm-modal" :class="{'confirm-modal-danger':confirmState.danger}" role="alertdialog" aria-modal="true" aria-labelledby="confirmDialogTitle" aria-describedby="confirmDialogMessage">
        <div class="modal-header">
          <div class="modal-heading">
            <span class="modal-heading-icon"><i class="bi" :class="confirmState.danger?'bi-exclamation-triangle':'bi-question-circle'" aria-hidden="true"></i></span>
            <h3 id="confirmDialogTitle">{{confirmState.title}}</h3>
          </div>
          <button type="button" class="close-btn" @click="confirmState.onCancel" :aria-label="confirmState.cancelText"><i class="bi bi-x-lg" aria-hidden="true"></i></button>
        </div>
        <div class="modal-body confirm-modal-body">
          <p id="confirmDialogMessage" class="confirm-modal-message">{{confirmState.message}}</p>
          <div v-if="hasRequiredInput" class="confirm-input-group">
            <label class="form-label" for="confirmInput">{{confirmState.inputLabel}}</label>
            <p class="confirm-input-hint">{{t('confirm.enterToConfirm', {value:confirmState.requireInput})}}</p>
            <input ref="confirmInput" id="confirmInput" class="form-control" :class="{'is-invalid':inputMismatch}" v-model="confirmState.inputValue" autocomplete="off" :aria-invalid="inputMismatch?'true':'false'" :aria-describedby="inputMismatch?'confirmInputHint confirmInputError':'confirmInputHint'" @input="attempted=false" @keyup.enter="submitConfirm">
            <span id="confirmInputHint" class="visually-hidden">{{t('confirm.enterToConfirm', {value:confirmState.requireInput})}}</span>
            <div v-if="inputMismatch" id="confirmInputError" class="invalid-feedback" role="alert">{{t('confirm.inputMismatch')}}</div>
          </div>
        </div>
        <div class="modal-footer">
          <button ref="cancelButton" type="button" class="btn btn-secondary confirm-cancel" @click="confirmState.onCancel">{{confirmState.cancelText}}</button>
          <button type="button" class="btn" :class="confirmState.danger?'btn-danger':'btn-primary'" @click="submitConfirm"><i v-if="confirmState.danger" class="bi bi-trash" aria-hidden="true"></i>{{confirmState.confirmText}}</button>
        </div>
      </div>
    </div>
  `
};
