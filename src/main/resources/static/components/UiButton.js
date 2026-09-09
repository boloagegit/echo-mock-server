const UiButton = {
    inheritAttrs: false,
    props: {
        variant: {
            type: String,
            default: '',
            validator: value => ['', 'primary', 'secondary', 'quiet', 'danger'].includes(value)
        },
        size: {
            type: String,
            default: '',
            validator: value => ['', 'default', 'compact'].includes(value)
        },
        iconOnly: { type: Boolean, default: false }
    },
    setup(props, { attrs, slots, expose }) {
        const buttonRef = Vue.ref(null);
        expose({
            focus: options => buttonRef.value?.focus(options),
            blur: () => buttonRef.value?.blur(),
            click: () => buttonRef.value?.click()
        });
        const flattenClasses = value => {
            if (!value) { return []; }
            if (typeof value === 'string') { return value.split(/\s+/).filter(Boolean); }
            if (Array.isArray(value)) { return value.flatMap(flattenClasses); }
            if (typeof value === 'object') { return Object.keys(value).filter(key => value[key]); }
            return [];
        };
        return () => {
            const legacyClasses = flattenClasses(attrs.class);
            const variant = props.variant
                || (legacyClasses.includes('btn-primary') ? 'primary' : '')
                || (legacyClasses.includes('btn-danger') || legacyClasses.includes('btn-outline-danger') ? 'danger' : '')
                || (legacyClasses.includes('btn-quiet') ? 'quiet' : '')
                || 'secondary';
            const size = props.size
                || (legacyClasses.includes('btn-sm') || legacyClasses.includes('btn-xs') ? 'compact' : 'default');
            const iconOnly = props.iconOnly || legacyClasses.includes('btn-icon');
            return Vue.h('button', {
                ...attrs,
                ref: buttonRef,
                class: [
                    'ui-button',
                    `ui-button--${variant}`,
                    `ui-button--${size}`,
                    { 'ui-button--icon-only': iconOnly },
                    // Keep layout hooks while templates migrate to semantic props.
                    // UiButton CSS remains the visual authority.
                    legacyClasses
                ]
            }, slots.default?.());
        };
    }
};
