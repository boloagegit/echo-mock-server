/**
 * ToastContainer - Toast notification container
 *
 * Display the Toast message of operation success/failure.
 */
const ToastContainer = {
  props: {
    toasts: Array
  },
  inject: ['t'],
  emits: ['dismiss', 'action'],
  template: /* html */`
    <div class="toast-wrap" role="region" :aria-label="t('common.notifications')">
      <div v-for="toast in toasts" :key="toast.id" class="toast toast-item" :class="[toast.type, {leaving: toast.leaving, 'has-action': !!toast.action, 'is-rich': !!toast.title}]"
        :role="toast.type==='error' ? 'alert' : 'status'">
        <i class="bi toast-icon" :class="toast.type==='success'?'bi-check-circle-fill':toast.type==='error'?'bi-x-circle-fill':toast.type==='warning'?'bi-exclamation-triangle-fill':'bi-info-circle-fill'" aria-hidden="true"></i>
        <div v-if="toast.title" class="toast-body">
          <strong class="toast-title">{{toast.title}}</strong>
          <span v-if="toast.msg" class="toast-msg">{{toast.msg}}</span>
          <ui-button v-if="toast.action" type="button" variant="secondary" size="compact" class="toast-action" @click="$emit('action', toast.id)">{{toast.action.label}}<i class="bi bi-arrow-right" aria-hidden="true"></i></ui-button>
        </div>
        <template v-else>
          <span class="toast-msg">{{toast.msg}}</span>
          <ui-button v-if="toast.action" type="button" variant="quiet" size="compact" class="toast-action" @click="$emit('action', toast.id)">{{toast.action.label}}</ui-button>
        </template>
        <ui-button type="button" variant="quiet" size="compact" icon-only class="toast-close" @click="$emit('dismiss', toast.id)" :aria-label="t('common.close')"><i class="bi bi-x"></i></ui-button>
        <div class="toast-progress"></div>
      </div>
    </div>
  `
};
