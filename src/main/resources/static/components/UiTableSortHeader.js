/** Consistent keyboard button and icon for sortable table headings. */
const UiTableSortHeader = {
  props: {
    label: { type: String, required: true },
    active: { type: Boolean, default: false },
    ascending: { type: Boolean, default: true },
    ariaLabel: { type: String, default: '' },
  },
  emits: ['toggle'],
  inject: ['t'],
  computed: {
    icon() {
      if (!this.active) { return 'bi-arrow-down-up'; }
      return this.ascending ? 'bi-caret-up-fill' : 'bi-caret-down-fill';
    },
    accessibleLabel() {
      return this.ariaLabel || this.t('common.sortBy', { field: this.label });
    },
  },
  template: /* html */`
    <ui-button type="button" variant="quiet" size="compact" class="table-sort-button ui-table-sort-header"
      :class="{'is-active':active}" :aria-label="accessibleLabel" @click="$emit('toggle')">
      <span>{{label}}</span>
      <i class="bi" :class="icon" aria-hidden="true"></i>
    </ui-button>
  `,
};
