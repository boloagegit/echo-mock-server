/** UiStatus - text-and-dot status indicator; color never carries meaning alone. */
const UiStatus = {
  inheritAttrs: false,
  props: {
    tone: {
      type: String,
      default: 'neutral',
      validator: value => ['neutral', 'success', 'warning', 'danger'].includes(value)
    }
  },
  setup(props, { attrs, slots }) {
    return () => Vue.h('span', {
      ...attrs,
      class: ['ui-status', `ui-status--${props.tone}`, attrs.class]
    }, [Vue.h('span', { class: 'ui-status__dot', 'aria-hidden': 'true' }), slots.default?.()]);
  }
};
