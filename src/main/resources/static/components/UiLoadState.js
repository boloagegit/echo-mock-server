/** Shared empty and error state surface for list workspaces. */
const UiLoadState = {
  props: {
    kind: {
      type: String,
      required: true,
      validator: value => ['empty', 'error'].includes(value),
    },
    icon: { type: String, required: true },
    title: { type: String, required: true },
    hint: { type: String, default: '' },
    hasAction: { type: Boolean, default: false },
  },
  template: /* html */`
    <div class="ui-load-state" :class="'ui-load-state--'+kind" :role="kind==='error' ? 'alert' : undefined">
      <i class="bi ui-load-state__icon" :class="icon" aria-hidden="true"></i>
      <strong class="ui-load-state__title">{{title}}</strong>
      <p v-if="hint" class="ui-load-state__hint">{{hint}}</p>
      <div v-if="hasAction" class="ui-load-state__action"><slot name="action"></slot></div>
    </div>
  `,
};
