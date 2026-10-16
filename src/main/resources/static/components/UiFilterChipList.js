/** Shared presentation and accessible actions for active list filters. */
const UiFilterChipList = {
  props: {
    items: { type: Array, required: true },
    ariaLabel: { type: String, required: true },
    clearLabel: { type: String, required: true },
  },
  emits: ['remove', 'clear'],
  inject: ['t'],
  template: /* html */`
    <TransitionGroup v-if="items.length" name="ui-filter-chip-motion" tag="div"
      class="ui-filter-chip-list filter-chips" role="group" :aria-label="ariaLabel">
      <span v-for="item in items" :key="item.key" class="ui-filter-chip filter-chip">
        <span class="ui-filter-chip__label" :title="item.label">{{item.label}}</span>
        <ui-button type="button" variant="quiet" size="compact" icon-only class="chip-remove"
          :aria-label="t('common.removeFilter', {filter:item.label})"
          @click="$emit('remove', item.key)">
          <i class="bi bi-x" aria-hidden="true"></i>
        </ui-button>
      </span>
      <ui-button key="__clear_filters__" type="button" variant="quiet" size="compact" class="chip-clear"
        @click="$emit('clear')">{{clearLabel}}</ui-button>
    </TransitionGroup>
  `,
};
