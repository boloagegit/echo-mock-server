/** Optional single-choice group used by clearable list filters. */
const UiToggleGroup = {
  props: {
    modelValue: { type: [String, Number, Boolean], default: '' },
    options: { type: Array, required: true },
    ariaLabel: { type: String, required: true },
    clearable: { type: Boolean, default: true },
  },
  emits: ['update:modelValue'],
  methods: {
    isSelected(option) {
      return Object.is(option.value, this.modelValue);
    },
    select(option) {
      if (option.disabled) { return; }
      const nextValue = this.clearable && this.isSelected(option) ? '' : option.value;
      this.$emit('update:modelValue', nextValue);
    },
  },
  template: /* html */`
    <div class="ui-toggle-group" role="group" :aria-label="ariaLabel">
      <ui-button v-for="option in options" :key="String(option.value)"
        type="button" variant="secondary" size="compact"
        class="ui-toggle-group__option"
        :class="{'is-selected':isSelected(option)}"
        :disabled="option.disabled"
        :title="option.title"
        :aria-pressed="isSelected(option)"
        @click="select(option)">
        <i v-if="option.icon" class="bi ui-toggle-group__icon" :class="option.icon" aria-hidden="true"></i>
        <span class="ui-toggle-group__label">{{option.label}}</span>
      </ui-button>
    </div>
  `,
};
