/**
 * ChangePasswordModal - 修改密碼對話框
 *
 * 同時支援使用者主動修改與登入後必須修改兩種狀態。
 */
const ChangePasswordModal = {
  props: {
    show: Boolean,
    form: Object,
    error: String,
    submitting: Boolean,
    required: Boolean,
  },
  emits: ['close', 'logout', 'submit', 'update:form'],
  inject: ['t'],
  data() {
    return { attempted: false, previousFocus: null, inertSiblings: [] };
  },
  computed: {
    oldPasswordError() {
      return this.attempted && !this.form.oldPassword ? this.t('accounts.changePassword.oldPasswordRequired') : '';
    },
    newPasswordError() {
      if (!this.attempted) { return ''; }
      return !this.form.newPassword || this.form.newPassword.length < 6
        ? this.t('accounts.changePassword.newPasswordInvalid')
        : '';
    },
  },
  watch: {
    show(open) {
      this.attempted = false;
      if (open) {
        this.previousFocus = document.activeElement;
        document.addEventListener('keydown', this._onKey);
        this.$nextTick(() => {
          this.makeBackgroundInert();
          this.$refs.oldPassword?.focus();
        });
        return;
      }
      document.removeEventListener('keydown', this._onKey);
      this.restoreBackground();
      this.previousFocus?.focus?.();
      this.previousFocus = null;
    },
  },
  mounted() {
    this._onKey = event => this.handleKeydown(event);
  },
  beforeUnmount() {
    document.removeEventListener('keydown', this._onKey);
    this.restoreBackground();
  },
  methods: {
    makeBackgroundInert() {
      const overlay = this.$el;
      const parent = overlay?.parentElement;
      if (!parent) { return; }
      this.inertSiblings = Array.from(parent.children).filter(node => node !== overlay).map(node => ({
        node,
        inert: node.inert,
        ariaHidden: node.getAttribute('aria-hidden'),
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
    updateField(field, value) {
      this.$emit('update:form', { ...this.form, [field]: value });
    },
    submit() {
      this.attempted = true;
      if (this.oldPasswordError || this.newPasswordError) {
        this.$nextTick(() => this.$refs.dialog?.querySelector('[aria-invalid="true"]')?.focus());
        return;
      }
      this.$emit('submit');
    },
    handleKeydown(event) {
      if (!this.show) { return; }
      if (event.key === 'Escape' && !this.required) {
        event.preventDefault();
        this.$emit('close');
        return;
      }
      if (event.key !== 'Tab') { return; }
      const focusable = Array.from(this.$refs.dialog?.querySelectorAll('button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])') || []);
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
  },
  template: /* html */`
    <div v-if="show" class="modal-overlay" @keydown="handleKeydown">
      <div ref="dialog" class="modal-box workspace-modal credential-modal" role="dialog" aria-modal="true" aria-labelledby="changePasswordTitle">
        <div class="modal-header">
          <div class="modal-heading"><span class="modal-heading-icon"><i class="bi bi-shield-lock" aria-hidden="true"></i></span><h3 id="changePasswordTitle">{{t('accounts.changePassword.title')}}</h3></div>
          <button v-if="!required" type="button" class="close-btn" @click="$emit('close')" :aria-label="t('modal.cancel')"><i class="bi bi-x-lg" aria-hidden="true"></i></button>
        </div>
        <div class="modal-body credential-modal-body">
          <p class="credential-intro">{{required ? t('accounts.changePassword.requiredDescription') : t('accounts.changePassword.description')}}</p>
          <div class="form-group">
            <label class="form-label" for="changeCurrentPassword">{{t('accounts.changePassword.oldPassword')}}</label>
            <input ref="oldPassword" id="changeCurrentPassword" :value="form.oldPassword" @input="updateField('oldPassword',$event.target.value)" type="password" autocomplete="current-password" class="form-control" :class="{'is-invalid':oldPasswordError}" :placeholder="t('accounts.changePassword.oldPasswordPlaceholder')" :aria-invalid="oldPasswordError?'true':'false'" :aria-describedby="oldPasswordError?'changeCurrentPasswordError':null" @keydown.enter="submit">
            <div v-if="oldPasswordError" id="changeCurrentPasswordError" class="invalid-feedback">{{oldPasswordError}}</div>
          </div>
          <div class="form-group">
            <label class="form-label" for="changeNewPassword">{{t('accounts.changePassword.newPassword')}}</label>
            <input id="changeNewPassword" :value="form.newPassword" @input="updateField('newPassword',$event.target.value)" type="password" autocomplete="new-password" class="form-control" :class="{'is-invalid':newPasswordError}" :placeholder="t('accounts.changePassword.newPasswordPlaceholder')" :aria-invalid="newPasswordError?'true':'false'" :aria-describedby="newPasswordError?'changeNewPasswordError':null" @keydown.enter="submit">
            <div v-if="newPasswordError" id="changeNewPasswordError" class="invalid-feedback">{{newPasswordError}}</div>
          </div>
          <div v-if="error" class="credential-error" role="alert"><i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>{{error}}</span></div>
        </div>
        <div class="modal-footer">
          <button v-if="!required" type="button" class="btn btn-secondary" @click="$emit('close')">{{t('modal.cancel')}}</button>
          <button v-else type="button" class="btn btn-secondary" @click="$emit('logout')"><i class="bi bi-box-arrow-right" aria-hidden="true"></i>{{t('sidebar.logout')}}</button>
          <button type="button" class="btn btn-primary" @click="submit" :disabled="submitting"><i class="bi" :class="submitting?'bi-arrow-clockwise spin':'bi-check2-circle'" aria-hidden="true"></i>{{t('accounts.changePassword.submit')}}</button>
        </div>
      </div>
    </div>
  `,
};
