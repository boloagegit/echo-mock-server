/**
 * WorkspaceSearchField - shared search control for dense workspace toolbars.
 */
const WorkspaceSearchField = {
  props: {
    modelValue: { type: String, default: '' },
    inputId: { type: String, default: '' },
    placeholder: { type: String, default: '' },
    ariaLabel: { type: String, default: '' },
    clearLabel: { type: String, default: '' },
    icon: { type: String, default: 'bi-search' },
    compact: { type: Boolean, default: false },
    showClear: { type: Boolean, default: true },
  },
  emits: ['update:modelValue'],
  template: /* html */`
    <div class="workspace-search-field" :class="{'workspace-search-compact':compact}" role="search">
      <label v-if="inputId && ariaLabel" class="visually-hidden" :for="inputId">{{ariaLabel}}</label>
      <i class="bi" :class="icon" aria-hidden="true"></i>
      <input
        :id="inputId || null"
        class="form-control"
        type="search"
        :value="modelValue"
        :placeholder="placeholder"
        :aria-label="inputId ? null : ariaLabel"
        spellcheck="false"
        @input="$emit('update:modelValue', $event.target.value)"
      >
      <button
        v-if="showClear && modelValue"
        type="button"
        class="workspace-search-clear"
        :title="clearLabel"
        :aria-label="clearLabel"
        @click="$emit('update:modelValue', '')"
      ><i class="bi bi-x" aria-hidden="true"></i></button>
    </div>
  `,
};
