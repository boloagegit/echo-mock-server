/**
 * useToast - Toast Notification and confirmation dialog box Composable
 *
 * Manage Toast notification messages and confirmation dialog boxes (Confirm Modal).
 * Toast automatically disappears: 3 seconds for success/information, 7 seconds for error, 6 seconds for actions (such as "Restore"); up to 3 items can be displayed at the same time.
 * Confirm supports requireInput (requires input confirmation) and danger (dangerous operation) modes.
 *
 * @param {Function} t - Translation function (from useI18n)
 * @returns {{ toasts: Ref<Array>, showToast: Function, confirmState: Ref<Object>, showConfirm: Function }}
 */
const useToast = (t) => {
    const { ref } = Vue;

    const toasts = ref([]);

    const TOAST_TYPES = ['success', 'error', 'info', 'warning'];
    // Reminder (warning) usually requires you to read a sentence and stay longer.
    const toastDuration = (type, action) => type === 'warning' ? 9000 : action ? 6000 : type === 'error' ? 7000 : 3000;
    let toastSeq = 0;

    /**
     * Display Toast notification
     * @param {string} msg - Message content
     * @param {string} type - type ('success' | 'error' | 'info'); other values are treated as info, 'danger' is treated as error
     * @param {{label: string, handler: Function}} [action] - Selected action button (e.g. "Revert")
     */
    const showToast = (msg, type, action = null) => {
        const id = Date.now() + (++toastSeq);
        const kind = type === 'danger' ? 'error' : (TOAST_TYPES.includes(type) ? type : 'info');
        // msg can be {title, detail}: add a line of description to the title, and place the action button below the description
        const rich = msg && typeof msg === 'object';
        toasts.value.push({ id, msg: rich ? msg.detail : msg, title: rich ? msg.title : null, type: kind, action, leaving: false });
        if (toasts.value.length > 3) { toasts.value.shift(); }
        setTimeout(() => { dismissToast(id); }, toastDuration(kind, action));
        return id;
    };

    /** Perform the action on Toast and close the notification immediately */
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
     * Display confirmation dialog box and return Promise<boolean>
     * @param {Object} options - Dialog options
     * @param {string} options.title - Title
     * @param {string} options.message - Message
     * @param {string} [options.confirmText] - Confirm button text
     * @param {string} [options.cancelText] - Cancel button text
     * @param {boolean} [options.danger=false] - Is it a dangerous operating mode?
     * @param {string|null} [options.requireInput=null] - Confirmation text to be entered
     * @param {string} [options.inputLabel=''] - Enter field label
     * @returns {Promise<boolean>} The user confirms by replying true, and canceling the reply false
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
