/**
 * useToast - Toast 通知與確認對話框 Composable
 *
 * 管理 Toast 通知訊息與確認對話框（Confirm Modal）。
 * Toast 自動消失：成功／資訊 3 秒、錯誤 7 秒、帶動作（如「復原」）6 秒；最多同時顯示 3 則。
 * Confirm 支援 requireInput（需輸入確認）與 danger（危險操作）模式。
 *
 * @param {Function} t - 翻譯函式（來自 useI18n）
 * @returns {{ toasts: Ref<Array>, showToast: Function, confirmState: Ref<Object>, showConfirm: Function }}
 */
const useToast = (t) => {
    const { ref } = Vue;

    const toasts = ref([]);

    const TOAST_TYPES = ['success', 'error', 'info'];
    const toastDuration = (type, action) => action ? 6000 : type === 'error' ? 7000 : 3000;
    let toastSeq = 0;

    /**
     * 顯示 Toast 通知
     * @param {string} msg - 訊息內容
     * @param {string} type - 類型（'success' | 'error' | 'info'）；其他值視為 info，'danger' 視為 error
     * @param {{label: string, handler: Function}} [action] - 選用的動作按鈕（例如「復原」）
     */
    const showToast = (msg, type, action = null) => {
        const id = Date.now() + (++toastSeq);
        const kind = type === 'danger' ? 'error' : (TOAST_TYPES.includes(type) ? type : 'info');
        toasts.value.push({ id, msg, type: kind, action, leaving: false });
        if (toasts.value.length > 3) { toasts.value.shift(); }
        setTimeout(() => { dismissToast(id); }, toastDuration(kind, action));
        return id;
    };

    /** 執行 Toast 上的動作並立即關閉該則通知 */
    const runToastAction = (id) => {
        const toast = toasts.value.find(item => item.id === id);
        if (!toast?.action || toast.leaving) { return; }
        dismissToast(id);
        toast.action.handler();
    };

    const dismissToast = (id) => {
        const t = toasts.value.find(item => item.id === id);
        if (t && !t.leaving) {
            t.leaving = true;
            setTimeout(() => { toasts.value = toasts.value.filter(item => item.id !== id); }, 250);
        }
    };

    const confirmState = ref({ show: false, title: '', message: '', confirmText: '', cancelText: '', danger: false, requireInput: null, inputLabel: '', inputValue: '', onConfirm: null, onCancel: null });

    /**
     * 顯示確認對話框，回傳 Promise<boolean>
     * @param {Object} options - 對話框選項
     * @param {string} options.title - 標題
     * @param {string} options.message - 訊息
     * @param {string} [options.confirmText] - 確認按鈕文字
     * @param {string} [options.cancelText] - 取消按鈕文字
     * @param {boolean} [options.danger=false] - 是否為危險操作模式
     * @param {string|null} [options.requireInput=null] - 需輸入的確認文字
     * @param {string} [options.inputLabel=''] - 輸入欄位標籤
     * @returns {Promise<boolean>} 使用者確認回傳 true，取消回傳 false
     */
    const showConfirm = ({ title, message, confirmText = t('confirm.ok'), cancelText = t('confirm.cancel'), danger = false, requireInput = null, inputLabel = '' }) => {
        return new Promise(resolve => {
            confirmState.value = {
                show: true, title, message, confirmText, cancelText, danger, requireInput, inputLabel, inputValue: '',
                onConfirm: () => { confirmState.value.show = false; resolve(true); },
                onCancel: () => { confirmState.value.show = false; resolve(false); }
            };
        });
    };

    return { toasts, showToast, dismissToast, runToastAction, confirmState, showConfirm };
};
