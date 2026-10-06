/**
 * WorkspaceSearchField - shared search control for dense workspace toolbars.
 *
 * In submit mode the applied keyword is emitted through `search` after the
 * user pauses typing (debounced) or presses Enter, so lists filter as you type
 * without sending a request per keystroke. Unchanged values are never re-sent.
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
    debounceMs: { type: Number, default: 350 },
    /** Key that focuses this field from anywhere on the page, shown as a hint while it is empty. */
    shortcut: { type: String, default: '' },
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
      if (value.trim() !== this.normalizedDraft) { this.draftValue = value; }
    },
  },
  beforeUnmount() {
    clearTimeout(this.searchTimer);
  },
  methods: {
    onInput(event) {
      this.draftValue = event.target.value;
      if (!this.submitMode) {
        this.$emit('update:modelValue', this.draftValue);
        return;
      }
      clearTimeout(this.searchTimer);
      this.searchTimer = setTimeout(() => this.submitSearch(), this.debounceMs);
    },
    submitSearch() {
      clearTimeout(this.searchTimer);
      if (!this.submitMode || this.searchUnchanged) { return; }
      this.$emit('search', this.normalizedDraft);
    },
    clearSearch() {
      clearTimeout(this.searchTimer);
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
    <form class="workspace-search-field" :class="{'workspace-search-compact':compact}" role="search" @submit.prevent="submitSearch">
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
          @keydown.esc="draftValue ? ($event.stopPropagation(), clearSearch()) : $event.target.blur()"
        >
        <kbd v-if="shortcut && !draftValue" class="workspace-search-kbd" aria-hidden="true">{{shortcut}}</kbd>
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
    </form>
  `,
};
