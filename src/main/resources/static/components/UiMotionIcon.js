/** Fixed-size icon cross-fade that never changes the surrounding layout. */
const UiMotionIcon = {
  props: {
    icon: { type: String, required: true },
    spin: { type: Boolean, default: false },
  },
  template: /* html */`
    <span class="ui-motion-icon" aria-hidden="true">
      <Transition name="ui-context-icon">
        <i :key="icon" class="bi" :class="[icon, {spin: spin}]"></i>
      </Transition>
    </span>
  `,
};
