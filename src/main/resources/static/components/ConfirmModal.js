/**
 * ConfirmModal - 確認對話框
 *
 * 通用確認/取消對話框，支援危險操作樣式與輸入驗證。
 */
const ConfirmModal = {
  props: {
    confirmState: Object
  },
  emits: [],
  inject: ['t'],
  mounted() { this._onKey = e => this._trapFocus(e); },
  watch: {
    'confirmState.show'(v) {
      if (v) {
        document.addEventListener('keydown', this._onKey);
        this.$nextTick(() => {
          const el = this.$el?.querySelector?.('.modal-box');
          if (el) { const btn = el.querySelector('button, input'); if (btn) { btn.focus(); } }
        });
      } else {
        document.removeEventListener('keydown', this._onKey);
      }
    }
  },
  methods: {
    _trapFocus(e) {
      if (e.key !== 'Tab') { return; }
      const box = this.$el?.querySelector?.('.modal-box');
      if (!box) { return; }
      const focusable = box.querySelectorAll('button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])');
      if (!focusable.length) { return; }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey) {
        if (document.activeElement === first) { e.preventDefault(); last.focus(); }
      } else {
        if (document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    }
  },
  template: /* html */`
    <div class="modal-overlay" v-if="confirmState.show" @click.self="confirmState.onCancel" @keyup.escape="confirmState.onCancel">
      <div class="modal-box" style="max-width:420px" role="dialog" aria-modal="true">
        <div class="modal-header">
          <h3><i class="bi" :class="confirmState.danger?'bi-exclamation-triangle text-danger':'bi-question-circle'"></i> {{confirmState.title}}</h3>
          <button class="close-btn" @click="confirmState.onCancel" aria-label="Close"><i class="bi bi-x-lg"></i></button>
        </div>
        <div class="modal-body" style="padding:1.5rem">
          <p style="white-space:pre-line;margin:0">{{confirmState.message}}</p>
          <div v-if="confirmState.requireInput!=null" style="margin-top:1rem">
            <label class="form-label">{{confirmState.inputLabel}}</label>
            <input class="form-control" v-model="confirmState.inputValue" @keyup.enter="confirmState.inputValue===confirmState.requireInput && confirmState.onConfirm()">
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-secondary" @click="confirmState.onCancel">{{confirmState.cancelText}}</button>
          <button class="btn" :class="confirmState.danger?'btn-danger':'btn-primary'" @click="confirmState.onConfirm" :disabled="confirmState.requireInput!=null && confirmState.inputValue!==confirmState.requireInput">{{confirmState.confirmText}}</button>
        </div>
      </div>
    </div>
  `
};
