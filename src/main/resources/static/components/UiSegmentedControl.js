let uiSegmentedControlSequence = 0;

/** Required single-choice control for peer modes or views. */
const UiSegmentedControl = {
  props: {
    modelValue: { type: [String, Number, Boolean], required: true },
    options: { type: Array, required: true },
    name: { type: String, required: true },
    ariaLabel: { type: String, required: true },
    description: { type: String, default: '' },
    size: {
      type: String,
      default: 'default',
      validator: value => ['default', 'compact'].includes(value),
    },
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const descriptionId = `ui-segmented-control-description-${++uiSegmentedControlSequence}`;
    const isSelected = option => Object.is(option.value, props.modelValue);
    const select = option => {
      if (!option.disabled) emit('update:modelValue', option.value);
    };
    return { descriptionId, isSelected, select };
  },
  template: /* html */`
    <div class="ui-segmented-control-field">
      <div class="ui-segmented-control" :class="'ui-segmented-control--'+size"
        role="radiogroup" :aria-label="ariaLabel"
        :aria-describedby="description ? descriptionId : undefined"
        :style="{'--ui-segment-count':options.length}">
        <label v-for="option in options" :key="String(option.value)"
          class="ui-segmented-control__option"
          :class="{'is-selected':isSelected(option), 'is-disabled':option.disabled, 'is-icon-only':option.iconOnly}"
          :title="option.title">
          <input class="ui-segmented-control__input" type="radio" :name="name"
            :value="option.value" :checked="isSelected(option)" :disabled="option.disabled"
            :aria-describedby="description ? descriptionId : undefined" required
            @change="select(option)">
          <i v-if="option.icon" class="bi ui-segmented-control__icon" :class="option.icon" aria-hidden="true"></i>
          <span class="ui-segmented-control__label" :class="{'visually-hidden':option.iconOnly}">{{option.label}}</span>
          <Transition name="ui-context-icon">
            <i v-if="!option.iconOnly && isSelected(option)" class="bi bi-check-lg ui-segmented-control__check is-visible" aria-hidden="true"></i>
          </Transition>
        </label>
      </div>
      <p v-if="description" :id="descriptionId" class="ui-segmented-control__description">{{description}}</p>
    </div>
  `,
};
