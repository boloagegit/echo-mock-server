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
    submitMode: { type: Boolean, default: false },
    submitLabel: { type: String, default: '' },
  },
  emits: ['update:modelValue', 'search'],
  data() {
    return { draftValue: this.modelValue };
  },
  computed: {
    normalizedDraft() {
      return this.draftValue.trim();
    },
    searchUnchanged() {
      return this.normalizedDraft === this.modelValue.trim();
    },
  },
  watch: {
    modelValue(value) {
      this.draftValue = value;
    },
  },
  methods: {
    onInput(event) {
      this.draftValue = event.target.value;
      if (!this.submitMode) {
        this.$emit('update:modelValue', this.draftValue);
      }
    },
    submitSearch() {
      if (!this.submitMode || this.searchUnchanged) { return; }
      this.$emit('search', this.normalizedDraft);
    },
    clearSearch() {
      const hadAppliedSearch = !!this.modelValue;
      this.draftValue = '';
      if (this.submitMode) {
        if (hadAppliedSearch) { this.$emit('search', ''); }
      } else {
        this.$emit('update:modelValue', '');
      }
    },
  },
  template: /* html */`
    <form class="workspace-search-field" :class="{'workspace-search-compact':compact, 'workspace-search-submit-mode':submitMode}" role="search" @submit.prevent="submitSearch">
      <div class="workspace-search-input">
        <label v-if="inputId && ariaLabel" class="visually-hidden" :for="inputId">{{ariaLabel}}</label>
        <i class="bi" :class="icon" aria-hidden="true"></i>
        <input
          :id="inputId || null"
          class="form-control form-control-sm"
          type="search"
          :value="draftValue"
          :placeholder="placeholder"
          :aria-label="inputId ? null : ariaLabel"
          spellcheck="false"
          @input="onInput"
          @keydown.enter.prevent="submitSearch"
        >
        <ui-button
          v-if="showClear && draftValue"
          type="button"
          variant="quiet"
          size="compact"
          icon-only
          class="workspace-search-clear"
          :title="clearLabel"
          :aria-label="clearLabel"
          @click="clearSearch"
        ><i class="bi bi-x" aria-hidden="true"></i></ui-button>
      </div>
      <ui-button
        v-if="submitMode"
        type="submit"
        variant="secondary"
        size="compact"
        class="workspace-search-submit"
        :disabled="searchUnchanged"
      ><i class="bi bi-search" aria-hidden="true"></i><span>{{submitLabel}}</span></ui-button>
    </form>
  `,
};
