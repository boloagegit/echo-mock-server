/** UiToggle - controlled 32px switch with an accessible 32px hit area. */
const UiToggle = {
  props: {
    checked: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
    ariaLabel: { type: String, required: true }
  },
  emits: ['toggle'],
  template: /* html */`
    <label class="ui-toggle" :class="{'is-disabled':disabled}" @click.stop>
      <input type="checkbox" :checked="checked" :disabled="disabled" :aria-label="ariaLabel" @click.prevent.stop="$emit('toggle')">
      <span class="ui-toggle__track" aria-hidden="true"></span>
    </label>
  `
};
