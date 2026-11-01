/**
 * RuleExportModal - choose which rules to export as a portable JSON file.
 *
 * The scope follows what the user is looking at: every rule, the current filter result, or the checked rules.
 */
const RuleExportModal = {
  props: {
    show: Boolean,
    scope: { type: String, default: 'all' },
    counts: { type: Object, default: () => ({}) },
    loading: Boolean,
  },
  emits: ['close', 'update:scope', 'export'],
  inject: ['t'],
  data() {
    return { previousFocus: null, inertSiblings: [] };
  },
  computed: {
    scopeOptions() {
      const all = this.counts.all;
      return [
        { value: 'all', icon: 'bi-collection', label: this.t('modal.exportScopeAll'),
          description: all == null ? this.t('modal.exportCounting') : this.t('modal.exportRuleCount', { count: all }) },
        { value: 'filtered', icon: 'bi-funnel', label: this.t('modal.exportScopeFiltered'),
          description: this.counts.filterActive ? this.t('modal.exportRuleCount', { count: this.counts.filtered || 0 }) : this.t('modal.exportNoFilter'),
          disabled: !this.counts.filterActive || !this.counts.filtered },
        { value: 'selected', icon: 'bi-check2-square', label: this.t('modal.exportScopeSelected'),
          description: this.counts.selected ? this.t('modal.exportRuleCount', { count: this.counts.selected }) : this.t('modal.exportNoSelection'),
          disabled: !this.counts.selected },
      ].map(option => ({ ...option, disabled: option.disabled || this.loading }));
    },
    count() {
      if (this.scope === 'selected') { return this.counts.selected || 0; }
      if (this.scope === 'filtered') { return this.counts.filtered || 0; }
      return this.counts.all;
    },
    fileName() {
      return `echo-rules-${new Date().toISOString().slice(0, 10)}${this.scope === 'all' ? '' : '-' + this.scope}.json`;
    },
    canExport() { return !this.loading && this.count !== 0; },
  },
  watch: {
    show(open) {
      if (open) {
        this.previousFocus = document.activeElement;
        this.$nextTick(() => {
          this.inertSiblings = makeOverlaySiblingsInert(this.$refs.overlay);
          this.$refs.scopeChoices?.focusSelected();
        });
        return;
      }
      this.restoreDialog();
    },
  },
  beforeUnmount() {
    this.restoreDialog();
  },
  methods: {
    restoreDialog() {
      restoreOverlaySiblings(this.inertSiblings);
      this.inertSiblings = [];
      const focusTarget = this.previousFocus?.isConnected
        ? this.previousFocus
        : document.querySelector('.rules-workspace .data-dropdown-wrapper > button');
      focusTarget?.focus?.();
      this.previousFocus = null;
    },
    handleKeydown(event) {
      if (event.key === 'Escape') {
        event.preventDefault();
        if (!this.loading) { this.$emit('close'); }
        return;
      }
      trapDialogFocus(event, this.$refs.dialog);
    },
  },
  template: /* html */`
    <ui-modal-transition>
    <div ref="overlay" class="modal-overlay" v-if="show" @keydown="handleKeydown">
      <div ref="dialog" class="modal-box workspace-modal export-modal" role="dialog" aria-modal="true" aria-labelledby="exportModalTitle" tabindex="-1">
        <div class="modal-header">
          <div class="modal-heading"><h2 id="exportModalTitle">{{t('modal.exportTitle')}}</h2></div>
          <ui-button type="button" class="close-btn" :disabled="loading" @click="$emit('close')" :aria-label="t('modal.cancel')"><i class="bi bi-x-lg" aria-hidden="true"></i></ui-button>
        </div>
        <div class="modal-body export-modal-body">
          <fieldset class="import-format-fieldset">
            <legend class="import-section-label">{{t('modal.exportScopeLabel')}}</legend>
            <ui-choice-group ref="scopeChoices" class="export-scope-options" variant="cards" show-check
              :model-value="scope" :options="scopeOptions" :aria-label="t('modal.exportScopeLabel')"
              @update:model-value="$emit('update:scope', $event)"></ui-choice-group>
          </fieldset>
          <div class="export-contents">
            <div>
              <span class="import-section-label">{{t('modal.exportIncludes')}}</span>
              <ul class="import-facts">
                <li><i class="bi bi-check2" aria-hidden="true"></i>{{t('modal.exportIncludesRules')}}</li>
                <li><i class="bi bi-check2" aria-hidden="true"></i>{{t('modal.exportIncludesResponses')}}</li>
                <li><i class="bi bi-check2" aria-hidden="true"></i>{{t('modal.exportIncludesConnections')}}</li>
              </ul>
            </div>
            <div>
              <span class="import-section-label">{{t('modal.exportExcludes')}}</span>
              <ul class="import-facts">
                <li><i class="bi bi-dash" aria-hidden="true"></i>{{t('modal.exportExcludesSecrets')}}</li>
                <li><i class="bi bi-dash" aria-hidden="true"></i>{{t('modal.exportExcludesHistory')}}</li>
              </ul>
            </div>
          </div>
          <p class="import-callout is-warning"><i class="bi bi-exclamation-triangle" aria-hidden="true"></i><span>{{t('modal.exportSensitive')}}</span></p>
        </div>
        <div class="modal-footer import-modal-footer">
          <p class="import-footer-summary"><span class="export-file-name" :title="fileName"><i class="bi bi-filetype-json" aria-hidden="true"></i>{{fileName}}</span></p>
          <ui-button type="button" variant="quiet" :disabled="loading" @click="$emit('close')">{{t('modal.cancel')}}</ui-button>
          <ui-button type="button" variant="primary" class="export-submit" :disabled="!canExport" @click="$emit('export', scope)">
            <i class="bi" :class="loading?'bi-arrow-clockwise spin':'bi-download'" aria-hidden="true"></i>
            {{count == null ? t('modal.exportDownload') : t('modal.exportDownloadCount', {count})}}
          </ui-button>
        </div>
      </div>
    </div>
    </ui-modal-transition>
  `
};
