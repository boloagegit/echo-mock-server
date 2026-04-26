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
  emits: ['dismiss'],
  template: /* html */`
    <div class="toast-wrap">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="[t.type, {leaving: t.leaving}]" style="position:relative;overflow:hidden">
        <i class="bi toast-icon" :class="t.type==='success'?'bi-check-circle-fill':t.type==='error'?'bi-x-circle-fill':'bi-info-circle-fill'"></i>
        <span class="toast-msg">{{t.msg}}</span>
        <button class="toast-close" @click="$emit('dismiss', t.id)" aria-label="Close"><i class="bi bi-x"></i></button>
        <div class="toast-progress"></div>
      </div>
    </div>
  `
};
