/**
 * UiToggle - controlled 32px switch with an accessible 32px hit area.
 *
 * Drawn from the `checked` prop (aria-checked), not from a native checkbox: a checkbox whose
 * click is cancelled restores its old state after the event, which hid changes the parent made
 * during the click (the editor's enabled / protected switches looked unchanged).
 */
const UiToggle = {
  props: {
    checked: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
    ariaLabel: { type: String, required: true }
  },
  emits: ['toggle'],
  template: /* html */`
    <button type="button" role="switch" class="ui-toggle" :class="{'is-disabled':disabled}" :aria-checked="checked ? 'true' : 'false'"
      :aria-label="ariaLabel" :disabled="disabled" @click.stop="$emit('toggle')">
      <span class="ui-toggle__track" aria-hidden="true"></span>
    </button>
  `
};
