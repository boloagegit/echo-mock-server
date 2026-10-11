/**
 * ToastContainer - Toast 通知容器
 *
 * 顯示操作成功/失敗的 Toast 訊息。
 */
const ToastContainer = {
  props: {
    toasts: Array
  },
  inject: ['t'],
  emits: ['dismiss', 'action'],
  template: /* html */`
    <div class="toast-wrap" role="region" :aria-label="t('common.notifications')">
      <div v-for="toast in toasts" :key="toast.id" class="toast toast-item" :class="[toast.type, {leaving: toast.leaving, 'has-action': !!toast.action}]"
        :role="toast.type==='error' ? 'alert' : 'status'">
        <i class="bi toast-icon" :class="toast.type==='success'?'bi-check-circle-fill':toast.type==='error'?'bi-x-circle-fill':'bi-info-circle-fill'" aria-hidden="true"></i>
        <span class="toast-msg">{{toast.msg}}</span>
        <ui-button v-if="toast.action" type="button" variant="quiet" size="compact" class="toast-action" @click="$emit('action', toast.id)">{{toast.action.label}}</ui-button>
        <ui-button type="button" variant="quiet" size="compact" icon-only class="toast-close" @click="$emit('dismiss', toast.id)" :aria-label="t('common.close')"><i class="bi bi-x"></i></ui-button>
        <div class="toast-progress"></div>
      </div>
    </div>
  `
};
