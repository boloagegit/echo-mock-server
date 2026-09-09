/** Shared semantic tab list with roving keyboard focus. */
const UiTabs = {
  inheritAttrs: false,
  props: {
    modelValue: { type: [String, Number], required: true },
    items: { type: Array, required: true },
    ariaLabel: { type: String, required: true },
    variant: { type: String, default: 'default', validator: value => ['default', 'compact'].includes(value) },
  },
  emits: ['update:modelValue'],
  methods: {
    isSelected(item) { return Object.is(item.value, this.modelValue); },
    select(item) { if (!item.disabled) this.$emit('update:modelValue', item.value); },
    move(event) {
      if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return;
      const tabs = [...event.currentTarget.parentElement.querySelectorAll('[role="tab"]:not(:disabled)')];
      if (!tabs.length) return;
      event.preventDefault();
      const current = Math.max(0, tabs.indexOf(event.currentTarget));
      const backwards = event.key === 'ArrowLeft' || event.key === 'ArrowUp';
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
        : (current + (backwards ? -1 : 1) + tabs.length) % tabs.length;
      tabs[next].focus();
      tabs[next].click();
    },
  },
  template: /* html */`
    <div class="ui-tabs" :class="'ui-tabs--'+variant" role="tablist" :aria-label="ariaLabel" v-bind="$attrs">
      <button v-for="item in items" :key="String(item.value)" type="button" role="tab"
        class="ui-tabs__tab" :class="{'active':isSelected(item), 'is-active':isSelected(item)}"
        :id="item.id" :aria-selected="isSelected(item)" :aria-controls="item.panelId"
        :tabindex="isSelected(item)?0:-1" :disabled="item.disabled" :title="item.title"
        @click="select(item)" @keydown="move">
        <i v-if="item.icon" class="bi" :class="item.icon" aria-hidden="true"></i>
        <span>{{item.label}}</span>
        <span v-if="item.count != null" class="ui-tabs__count">{{item.count}}</span>
      </button>
    </div>
  `,
};
