/** Compare only on editor boundaries; never retain draft history or poll. */
const draftFingerprint = value => {
    const normalize = item => {
        if (Array.isArray(item)) return item.map(normalize);
        if (item && typeof item === 'object') {
            return Object.fromEntries(Object.keys(item).sort()
                .filter(key => item[key] !== undefined)
                .map(key => [key, normalize(item[key])]));
        }
        return item;
    };
    return JSON.stringify(normalize(value));
};

const useDiscardGuard = ({ readDraft, isOpen, isBusy, discard, showConfirm, t }) => {
    let baseline = null;
    let pending = null;
    const begin = (draft = readDraft()) => { baseline = draftFingerprint(draft); };
    const reset = () => { baseline = null; };
    const isDirty = () => isOpen() && baseline !== null
        && draftFingerprint(readDraft()) !== baseline;
    const requestClose = () => {
        if (pending) return pending;
        if (isBusy()) return Promise.resolve(false);
        if (!isDirty()) {
            discard();
            reset();
            return Promise.resolve(true);
        }
        pending = (async () => {
            try {
                const confirmed = await showConfirm({
                    title: t('confirm.discardRuleTitle'),
                    message: t('confirm.discardRuleMessage'),
                    confirmText: t('confirm.discardChanges'),
                    cancelText: t('confirm.continueEditing'),
                    danger: true
                });
                if (!confirmed || isBusy()) return false;
                discard();
                reset();
                return true;
            } finally { pending = null; }
        })();
        return pending;
    };
    return { begin, reset, isDirty, requestClose };
};
