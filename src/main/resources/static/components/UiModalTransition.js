/** Shared productive-motion wrapper for modal overlays. */
const UiModalTransition = {
  setup(_, { slots }) {
    return () => Vue.h(
      Vue.Transition,
      { name: 'ui-modal-motion' },
      { default: () => slots.default?.() },
    );
  },
};
