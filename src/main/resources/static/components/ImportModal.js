/**
 * ImportModal - rule import in three steps: choose a file, review every rule, map forwarding connections.
 *
 * JSON, Excel and OpenAPI files share the same review. Nothing is written until the final confirmation,
 * which creates the checked rules in one transaction.
 */
const IMPORT_PROBLEM_DETAILS = {
  'matchKey is required': 'modal.importDetailMatchKeyRequired',
};

const ImportModal = {
  props: {
    show: Boolean,
    importFormat: String,
    importFile: [Object, null],
    importFileName: String,
    loading: Boolean,
    error: String,
    preview: Object,
    connectionMappings: Object,
    step: { type: String, default: 'file' },
    /** The server is re-checking the current selection; only submitting waits for it. */
    revalidating: Boolean,
    excluded: { type: Array, default: () => [] },
    sourceInfo: { type: Object, default: () => ({}) },
    httpLabel: String,
    jmsLabel: String,
  },
  emits: ['close', 'update:importFormat', 'handle-file', 'do-import', 'back', 'set-connection', 'toggle-row', 'set-excluded'],
  inject: ['t'],
  data() {
    return { dragActive: false, previousFocus: null, inertSiblings: [], reviewFilter: 'all' };
  },
  computed: {
    reviewing() { return !!this.preview && this.step !== 'file'; },
    rows() { return this.preview?.rows || []; },
    excludedSet() { return new Set(this.excluded); },
    problemsByRow() {
      const byRow = new Map();
      for (const problem of this.preview?.errors || []) {
        if (problem.code === 'CONNECTION_SELECTION_REQUIRED') { continue; }
        byRow.set(problem.row, [...(byRow.get(problem.row) || []), problem]);
      }
      return byRow;
    },
    fileProblems() { return this.problemsByRow.get(0) || []; },
    includedRows() { return this.rows.filter(row => !this.excludedSet.has(row.row)); },
    blockingRows() { return this.includedRows.filter(row => this.problemsByRow.has(row.row)); },
    duplicateCount() { return this.rows.filter(row => row.duplicate).length; },
    attentionRows() { return this.rows.filter(row => row.duplicate || this.problemsByRow.has(row.row)); },
    forwardRows() { return this.rows.filter(row => row.action === 'FORWARD'); },
    enabledIncluded() { return this.includedRows.filter(row => row.enabled).length; },
    visibleRows() {
      if (this.reviewFilter === 'attention') { return this.attentionRows; }
      if (this.reviewFilter === 'forward') { return this.forwardRows; }
      return this.rows;
    },
    visibleChecked() { return this.visibleRows.filter(row => !this.excludedSet.has(row.row)).length; },
    reviewFilterOptions() {
      return [
        { value: 'all', label: this.t('modal.importFilterAll', { count: this.rows.length }) },
        { value: 'attention', label: this.t('modal.importFilterAttention', { count: this.attentionRows.length }), disabled: !this.attentionRows.length },
        { value: 'forward', label: this.t('modal.importFilterForward', { count: this.forwardRows.length }), disabled: !this.forwardRows.length },
      ];
    },
    activeRequirements() {
      return (this.preview?.requirements || []).filter(item =>
        !Array.isArray(item.rows) || item.rows.some(row => !this.excludedSet.has(row)));
    },
    pendingConnections() { return this.activeRequirements.filter(item => !this.mappingValid(item)).length; },
    steps() {
      const steps = [{ key: 'file', label: this.t('modal.importStepFile') }, { key: 'review', label: this.t('modal.importStepReview') }];
      if (this.step === 'connections' || (this.reviewing && this.activeRequirements.length)) {
        steps.push({ key: 'connections', label: this.t('modal.importStepConnections') });
      }
      const current = steps.findIndex(item => item.key === this.step);
      return steps.map((item, index) => ({ ...item, number: index + 1, current: index === current, complete: index < current }));
    },
    selectedFormat() { return this.formatOptions.find(option => option.value === this.importFormat) || this.formatOptions[0]; },
    formatOptions() {
      return [
        { value: 'json', label: 'JSON', description: this.t('modal.importJsonHint'), icon: 'bi-filetype-json' },
        { value: 'excel', label: 'Excel', description: this.t('modal.importExcelHint'), icon: 'bi-file-earmark-spreadsheet' },
        { value: 'openapi', label: 'OpenAPI', description: this.t('modal.openApiHint'), icon: 'bi-filetype-yml' },
      ].map(option => ({ ...option, disabled: this.loading }));
    },
    fileSizeLabel() {
      const bytes = this.importFile?.size;
      if (!Number.isFinite(bytes)) { return ''; }
      const divisor = bytes >= 1048576 ? 1048576 : bytes >= 1024 ? 1024 : 1;
      return `${Math.round(bytes / divisor * 10) / 10} ${divisor === 1048576 ? 'MB' : divisor === 1024 ? 'KB' : 'B'}`;
    },
    fileAccept() {
      if (this.importFormat === 'openapi') { return '.json,.yaml,.yml'; }
      return this.importFormat === 'json' ? '.json' : '.xlsx,.xls';
    },
    acceptedFileHint() {
      if (this.importFormat === 'openapi') { return this.t('modal.importOpenApiAccepted'); }
      return this.importFormat === 'json' ? this.t('modal.importJsonAccepted') : this.t('modal.importExcelAccepted');
    },
    sourceSummary() {
      const parts = [this.selectedFormat.label];
      if (this.sourceInfo?.title) { parts.push(this.sourceInfo.title + (this.sourceInfo.version ? ' v' + this.sourceInfo.version : '')); }
      if (this.fileSizeLabel) { parts.push(this.fileSizeLabel); }
      return parts.join(' · ');
    },
    canSubmit() {
      if (!this.importFile || this.loading || this.revalidating) { return false; }
      if (!this.reviewing) { return true; }
      if (!this.includedRows.length || this.blockingRows.length || this.fileProblems.length) { return false; }
      return this.step !== 'connections' || this.pendingConnections === 0;
    },
    submitLabel() {
      if (!this.reviewing) { return this.t('modal.importCheckFile'); }
      if (this.step === 'review' && this.activeRequirements.length) { return this.t('modal.importNextConnections'); }
      return this.t('modal.importConfirmNew', { count: this.includedRows.length });
    },
    submitIcon() {
      if (this.loading) { return 'bi-arrow-clockwise spin'; }
      return this.reviewing && !(this.step === 'review' && this.activeRequirements.length) ? 'bi-check2' : 'bi-arrow-right';
    },
    blockedReason() {
      if (!this.reviewing || this.loading) { return ''; }
      if (this.fileProblems.length) { return this.problemText(this.fileProblems[0]); }
      if (!this.includedRows.length) { return this.t('modal.importNothingChecked'); }
      if (this.blockingRows.length) { return this.t('modal.importBlockedRows', { count: this.blockingRows.length }); }
      if (this.step === 'connections' && this.pendingConnections) { return this.t('modal.importPendingConnections', { count: this.pendingConnections }); }
      return '';
    },
  },
  watch: {
    step(current) {
      if (!this.show) { return; }
      if (current === 'file') { this.reviewFilter = 'all'; }
      this.$nextTick(() => {
        if (current === 'review') { this.$refs.reviewTitle?.focus(); }
        else if (current === 'connections') { this.$refs.connectionsTitle?.focus(); }
        else { this.$refs.formatChoices?.focusSelected(); }
      });
    },
    show(open) {
      if (open) {
        this.previousFocus = document.activeElement;
        this.reviewFilter = 'all';
        this.$nextTick(() => {
          this.inertSiblings = makeOverlaySiblingsInert(this.$refs.overlay);
          this.$refs.formatChoices?.focusSelected();
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
    mappingValid(item) {
      return (this.preview?.connections || []).some(option => option.protocol === item.protocol
        && option.selection === this.connectionMappings?.[item.key]);
    },
    methodLabel(row) { return row.protocol === 'JMS' ? 'JMS' : (row.method || 'GET'); },
    protocolLabel(protocol) { return protocol === 'JMS' ? this.jmsLabel || 'JMS' : this.httpLabel || 'HTTP'; },
    rowById(number) { return this.rows.find(row => row.row === number); },
    problemText(problem) {
      const reason = this.t('modal.importProblemCodes.' + problem.code);
      const known = reason !== 'modal.importProblemCodes.' + problem.code;
      if (problem.code === 'INVALID_RULE' && problem.message) {
        const detail = IMPORT_PROBLEM_DETAILS[problem.message];
        return this.t('modal.importProblemWithDetail', { reason, detail: detail ? this.t(detail) : problem.message });
      }
      return known ? reason : problem.message;
    },
    sourceConnectionLabel(requirement) {
      if (requirement.mode === 'ORIGINAL_HOST') { return this.t('modal.importOriginalHostChoice'); }
      if (requirement.mode === 'DEFAULT_CONNECTION') {
        return requirement.sourceName
          ? this.t('modal.importSourceDefaultNamed', { name: requirement.sourceName })
          : this.t('modal.importSourceDefault');
      }
      return requirement.sourceName || this.t('modal.importUnknownConnection');
    },
    /** Two source connections can share a name; their source ID tells them apart. */
    sourceConnectionId(requirement) {
      return requirement.mode === 'CONNECTION' ? String(requirement.key || '').split(':').slice(2).join(':') : '';
    },
    connectionLabel(option) {
      if (option.mode === 'ORIGINAL_HOST') { return this.t('modal.importOriginalHostChoice'); }
      return option.mode === 'DEFAULT_CONNECTION'
        ? this.t('modal.importDefaultConnectionChoice', { name: option.name }) : option.name;
    },
    suggestionNote(requirement) {
      if (!requirement.suggestion || this.connectionMappings?.[requirement.key] !== requirement.suggestion) { return ''; }
      if (requirement.mode === 'ORIGINAL_HOST') { return this.t('modal.importSuggestedHost'); }
      return requirement.mode === 'DEFAULT_CONNECTION' ? this.t('modal.importSuggestedDefault') : this.t('modal.importSuggestedName');
    },
    requirementRows(requirement) {
      return (requirement.rows || []).map(this.rowById).filter(Boolean);
    },
    isChecked(row) { return !this.excludedSet.has(row.row); },
    toggleVisible(event) {
      const visible = this.visibleRows.map(row => row.row);
      const next = event.target.checked
        ? this.excluded.filter(row => !visible.includes(row))
        : [...this.excluded, ...visible];
      this.$emit('set-excluded', next);
    },
    uncheckBlocking() {
      this.$emit('set-excluded', [...this.excluded, ...this.blockingRows.map(row => row.row)]);
    },
    chooseFile() {
      if (this.loading || !this.$refs.fileInput) { return; }
      this.$refs.fileInput.value = '';
      this.$refs.fileInput.click();
    },
    restoreDialog() {
      restoreOverlaySiblings(this.inertSiblings);
      this.inertSiblings = [];
      const focusTarget = this.previousFocus?.isConnected
        ? this.previousFocus
        : document.querySelector('.rules-workspace .data-dropdown-wrapper > button');
      focusTarget?.focus?.();
      this.previousFocus = null;
    },
    selectFormat(format) {
      if (this.loading || format === this.importFormat) { return; }
      this.$emit('update:importFormat', format);
      if (this.$refs.fileInput) { this.$refs.fileInput.value = ''; }
    },
    handleDrop(event) {
      this.dragActive = false;
      if (this.loading) { return; }
      const file = event.dataTransfer?.files?.[0];
      if (file) { this.$emit('handle-file', file); }
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
      <div ref="dialog" class="modal-box workspace-modal import-modal" role="dialog" aria-modal="true" aria-labelledby="importModalTitle" tabindex="-1">
        <div class="modal-header">
          <div class="modal-heading"><h2 id="importModalTitle">{{t('modal.importRule')}}</h2></div>
          <ui-button type="button" class="close-btn" :disabled="loading" @click="$emit('close')" :aria-label="t('modal.cancel')"><i class="bi bi-x-lg" aria-hidden="true"></i></ui-button>
        </div>
        <ol class="import-steps" :aria-label="t('modal.importStepsLabel')">
          <li v-for="item in steps" :key="item.key" :class="{'is-current':item.current, 'is-complete':item.complete}" :aria-current="item.current?'step':null">
            <span class="import-step-number" aria-hidden="true"><i v-if="item.complete" class="bi bi-check2"></i><template v-else>{{item.number}}</template></span>
            <span>{{item.label}}</span>
          </li>
        </ol>

        <div class="modal-body import-modal-body">
          <input ref="fileInput" id="rule-import-file" class="visually-hidden" type="file" :disabled="loading" :accept="fileAccept" :aria-label="t('modal.uploadFile')" @change="$emit('handle-file',$event)">

          <div v-if="!reviewing" class="import-source">
            <fieldset class="import-format-fieldset">
              <legend class="import-section-label">{{t('modal.selectFormat')}}</legend>
              <ui-choice-group ref="formatChoices" class="import-format-options" option-class="import-format-option"
                variant="cards" show-check :model-value="importFormat" :options="formatOptions"
                :aria-label="t('modal.selectFormat')" @update:model-value="selectFormat"></ui-choice-group>
              <a v-if="importFormat==='excel'" href="/api/admin/rules/import-template" class="import-template-link"><i class="bi bi-download" aria-hidden="true"></i>{{t('modal.downloadTemplate')}}</a>
            </fieldset>
            <div class="import-file-field">
              <span class="import-section-label" id="importFileLabel">{{t('modal.importFileLabel')}}</span>
              <div v-if="importFile" class="import-file-card" :class="{'is-dragging':dragActive}"
                @dragenter.prevent="dragActive=true" @dragover.prevent="dragActive=true" @dragleave.prevent="dragActive=false" @drop.prevent="handleDrop">
                <i class="bi bi-file-earmark-check" aria-hidden="true"></i>
                <div class="import-file-copy"><strong :title="importFileName">{{importFileName}}</strong><small>{{sourceSummary}}</small></div>
                <ui-button type="button" variant="quiet" size="compact" :disabled="loading" @click="chooseFile">{{t('modal.importChangeFile')}}</ui-button>
              </div>
              <label v-else for="rule-import-file" class="import-dropzone" :class="{'is-dragging':dragActive, 'is-invalid':error}"
                @dragenter.prevent="dragActive=true" @dragover.prevent="dragActive=true" @dragleave.prevent="dragActive=false" @drop.prevent="handleDrop">
                <i class="bi bi-upload" aria-hidden="true"></i>
                <span class="import-file-empty"><strong>{{t('modal.clickOrDragFile')}}</strong><small>{{acceptedFileHint}}</small></span>
              </label>
              <p class="import-next-hint"><i class="bi bi-shield-check" aria-hidden="true"></i><span>{{t('modal.importNothingWritten')}}</span></p>
            </div>
          </div>

          <section v-else-if="step==='review'" class="import-review" aria-labelledby="importReviewTitle">
            <div class="import-review-head">
              <div class="import-review-heading">
                <h3 ref="reviewTitle" id="importReviewTitle" tabindex="-1">{{t('modal.importReviewTitle')}}</h3>
                <p class="import-review-source"><i class="bi bi-file-earmark-text" aria-hidden="true"></i><span :title="importFileName">{{importFileName}}</span><span class="import-review-meta">{{sourceSummary}}</span></p>
              </div>
              <ui-choice-group class="import-review-filter" variant="compact" :model-value="reviewFilter" :options="reviewFilterOptions"
                :aria-label="t('modal.importFilterLabel')" @update:model-value="reviewFilter=$event"></ui-choice-group>
            </div>

            <div v-if="blockingRows.length" class="import-callout is-danger" role="alert">
              <i class="bi bi-exclamation-octagon" aria-hidden="true"></i>
              <span>{{t('modal.importBlockedHelp', {count: blockingRows.length})}}</span>
              <ui-button type="button" variant="secondary" size="compact" :disabled="loading" @click="uncheckBlocking">{{t('modal.importUncheckBlocked')}}</ui-button>
            </div>
            <div v-else-if="fileProblems.length" class="import-callout is-danger" role="alert">
              <i class="bi bi-exclamation-octagon" aria-hidden="true"></i><span>{{problemText(fileProblems[0])}}</span>
            </div>
            <div v-if="duplicateCount" class="import-callout is-warning">
              <i class="bi bi-files" aria-hidden="true"></i><span>{{t('modal.importDuplicateHelp', {count: duplicateCount})}}</span>
            </div>

            <div class="import-table-wrap">
              <table class="import-table">
                <caption class="visually-hidden">{{t('modal.importReviewTitle')}}</caption>
                <thead>
                  <tr>
                    <th scope="col" class="import-col-check"><input type="checkbox" :checked="visibleRows.length>0 && visibleChecked===visibleRows.length"
                      :indeterminate="visibleChecked>0 && visibleChecked<visibleRows.length" :disabled="loading || !visibleRows.length"
                      :aria-label="t('modal.importSelectVisible')" @change="toggleVisible"></th>
                    <th scope="col" class="import-col-method">{{t('modal.method')}}</th>
                    <th scope="col">{{t('modal.importColumnRule')}}</th>
                    <th scope="col" class="import-col-state">{{t('modal.importColumnState')}}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in visibleRows" :key="row.row" :class="{'is-excluded':!isChecked(row), 'has-problem':problemsByRow.has(row.row)}" @click="$emit('toggle-row', row.row)">
                    <td class="import-col-check" @click.stop>
                      <input type="checkbox" :checked="isChecked(row)" :disabled="loading" @change="$emit('toggle-row', row.row)"
                        :aria-label="t('modal.importSelectRow', {row: row.row, path: row.matchKey || ''})">
                    </td>
                    <td class="import-col-method"><span class="rule-method" :data-method="row.protocol==='HTTP' ? (row.method || 'GET') : null" :data-protocol="row.protocol" :title="protocolLabel(row.protocol)">{{methodLabel(row)}}</span></td>
                    <td class="import-col-rule">
                      <div class="import-rule-identity">
                        <code class="import-rule-path" :title="row.matchKey">{{row.matchKey || t('modal.importNoPath')}}</code>
                        <span v-if="row.targetHost" class="import-rule-host" :title="row.targetHost">{{row.targetHost}}</span>
                      </div>
                      <span v-if="row.description" class="import-rule-desc" :title="row.description">{{row.description}}</span>
                      <ul v-if="problemsByRow.has(row.row)" class="import-row-problems">
                        <li v-for="(problem, index) in problemsByRow.get(row.row)" :key="index"><i class="bi bi-exclamation-circle" aria-hidden="true"></i>{{problemText(problem)}}</li>
                      </ul>
                    </td>
                    <td class="import-col-state">
                      <span class="import-row-row">#{{row.row}}</span>
                      <ui-badge v-if="row.duplicate" tone="warning">{{t('modal.importBadgeDuplicate')}}</ui-badge>
                      <ui-badge v-if="row.action==='FORWARD'" tone="accent">{{t('modal.importBadgeForward')}}</ui-badge>
                      <ui-badge v-else-if="row.action==='FAULT'">{{t('modal.importBadgeFault')}}</ui-badge>
                      <ui-badge v-if="!row.enabled">{{t('modal.importBadgeDisabled')}}</ui-badge>
                    </td>
                  </tr>
                </tbody>
              </table>
              <p v-if="!visibleRows.length" class="import-table-empty">{{t('modal.importNoRows')}}</p>
            </div>

            <ul class="import-facts">
              <li><i class="bi bi-shield-check" aria-hidden="true"></i>{{t('modal.importKeepExisting')}}</li>
              <li><i class="bi bi-clock" aria-hidden="true"></i>{{t('modal.importRetentionNew')}}</li>
              <li v-if="enabledIncluded" class="is-warning"><i class="bi bi-lightning-charge" aria-hidden="true"></i>{{t('modal.importEnabledImpact', {count: enabledIncluded})}}</li>
            </ul>
          </section>

          <section v-else class="import-connections" aria-labelledby="importConnectionsTitle">
            <div class="import-review-head">
              <div class="import-review-heading">
                <h3 ref="connectionsTitle" id="importConnectionsTitle" tabindex="-1">{{t('modal.importConnectionTitle')}}</h3>
                <p>{{t('modal.importConnectionHelp')}}</p>
              </div>
              <span class="import-mapping-status" :class="{'is-ready':pendingConnections===0}" role="status">
                <i class="bi" :class="pendingConnections?'bi-circle':'bi-check2-circle'" aria-hidden="true"></i>
                {{pendingConnections ? t('modal.importPendingConnections', {count: pendingConnections}) : t('modal.importConnectionsReady')}}
              </span>
            </div>
            <ul class="import-mapping-list">
              <li v-for="requirement in activeRequirements" :key="requirement.key" class="import-mapping-row" :class="{'is-pending':!mappingValid(requirement)}">
                <div class="import-mapping-source">
                  <span class="import-field-label">{{t('modal.importSourceLabel')}}</span>
                  <strong :id="'import-source-'+requirement.key">{{sourceConnectionLabel(requirement)}}<span v-if="sourceConnectionId(requirement)" class="import-mapping-source-id">{{t('modal.importSourceId', {id: sourceConnectionId(requirement)})}}</span></strong>
                  <details v-if="requirementRows(requirement).length" class="import-mapping-rules">
                    <summary>{{t('modal.importSourceDetails', {protocol: protocolLabel(requirement.protocol), count: requirementRows(requirement).length})}}</summary>
                    <ul>
                      <li v-for="row in requirementRows(requirement)" :key="row.row" :class="{'is-excluded':!isChecked(row)}">
                        <span class="rule-method" :data-method="row.protocol==='HTTP' ? (row.method || 'GET') : null" :data-protocol="row.protocol">{{methodLabel(row)}}</span>
                        <code :title="row.matchKey">{{row.matchKey}}</code>
                      </li>
                    </ul>
                  </details>
                  <small v-else>{{t('modal.importSourceDetails', {protocol: protocolLabel(requirement.protocol), count: requirement.rules})}}</small>
                </div>
                <i class="bi bi-arrow-right import-mapping-arrow" aria-hidden="true"></i>
                <div class="import-mapping-target">
                  <label class="import-field-label" :for="'import-connection-'+requirement.key">{{t('modal.importDestinationLabel')}}</label>
                  <div class="import-select-wrap">
                    <select class="import-target-select" :disabled="loading" :id="'import-connection-'+requirement.key" :aria-describedby="'import-source-'+requirement.key" :value="connectionMappings?.[requirement.key] || ''"
                      @change="$emit('set-connection', {key: requirement.key, selection: $event.target.value})">
                      <option value="" disabled>{{t('modal.importSelectConnection')}}</option>
                      <option v-for="option in (preview.connections || []).filter(item => item.protocol===requirement.protocol)" :key="option.selection" :value="option.selection">{{connectionLabel(option)}}</option>
                    </select>
                    <i class="bi bi-chevron-down" aria-hidden="true"></i>
                  </div>
                  <small v-if="suggestionNote(requirement)" class="import-suggestion"><i class="bi bi-lightbulb" aria-hidden="true"></i>{{suggestionNote(requirement)}}</small>
                  <small v-else-if="!(preview.connections || []).some(item => item.protocol===requirement.protocol && item.mode!=='ORIGINAL_HOST')" class="import-suggestion is-warning">{{t('modal.importNoConnections')}}</small>
                </div>
              </li>
            </ul>
          </section>

          <div v-if="error" class="import-error" role="alert"><i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>{{error}}</span></div>
        </div>
        <div class="modal-footer import-modal-footer">
          <p v-if="reviewing" class="import-footer-summary" role="status">
            <strong>{{t('modal.importFooterCreate', {count: includedRows.length})}}</strong>
            <span v-if="rows.length>includedRows.length">{{t('modal.importFooterSkip', {count: rows.length-includedRows.length})}}</span>
            <span v-if="blockedReason" class="import-footer-blocked">{{blockedReason}}</span>
          </p>
          <ui-button type="button" variant="quiet" :disabled="loading" @click="$emit('close')">{{t('modal.cancel')}}</ui-button>
          <ui-button v-if="reviewing" type="button" variant="secondary" @click="$emit('back')" :disabled="loading">{{t('modal.importBack')}}</ui-button>
          <ui-button type="button" variant="primary" class="import-submit" @click="$emit('do-import')" :disabled="!canSubmit">
            <i class="bi" :class="submitIcon" aria-hidden="true"></i>
            {{submitLabel}}
          </ui-button>
        </div>
      </div>
    </div>
    </ui-modal-transition>
  `
};
