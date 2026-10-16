/** Shared required single-choice button group for modes, types, and rich option cards. */
const UiChoiceGroup = {
  inheritAttrs: false,
  props: {
    modelValue: { type: [String, Number, Boolean], required: true },
    options: { type: Array, required: true },
    ariaLabel: { type: String, required: true },
    variant: { type: String, default: 'default', validator: value => ['default', 'compact', 'cards'].includes(value) },
    optionClass: { type: String, default: '' },
    showCheck: { type: Boolean, default: false },
  },
  emits: ['update:modelValue'],
  methods: {
    isSelected(option) { return Object.is(option.value, this.modelValue); },
    select(option) { if (!option.disabled) this.$emit('update:modelValue', option.value); },
    move(event) {
      if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return;
      const choices = [...event.currentTarget.parentElement.querySelectorAll('[role="radio"]:not(:disabled)')];
      if (!choices.length) return;
      event.preventDefault();
      const current = Math.max(0, choices.indexOf(event.currentTarget));
      const backwards = event.key === 'ArrowLeft' || event.key === 'ArrowUp';
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? choices.length - 1
        : (current + (backwards ? -1 : 1) + choices.length) % choices.length;
      choices[next].focus();
      choices[next].click();
    },
    focusSelected() { this.$el?.querySelector('[role="radio"][aria-checked="true"]')?.focus(); },
  },
  template: /* html */`
    <div class="ui-choice-group" :class="'ui-choice-group--'+variant" role="radiogroup" :aria-label="ariaLabel" v-bind="$attrs">
      <button v-for="option in options" :key="String(option.value)" type="button" role="radio"
        class="ui-choice-group__option" :class="[optionClass, option.class, {'active':isSelected(option), 'is-selected':isSelected(option)}]"
        :aria-checked="isSelected(option)" :tabindex="isSelected(option)?0:-1"
        :disabled="option.disabled" :title="option.title" @click="select(option)" @keydown="move">
        <i v-if="option.icon" class="bi ui-choice-group__icon" :class="option.icon" aria-hidden="true"></i>
        <span class="ui-choice-group__copy">
          <strong v-if="option.description">{{option.label}}</strong>
          <span v-else>{{option.label}}</span>
          <small v-if="option.description">{{option.description}}</small>
        </span>
        <i v-if="showCheck && isSelected(option)" class="bi bi-check2 ui-choice-group__check" aria-hidden="true"></i>
      </button>
    </div>
  `,
};
