/**
 * OpenApiPreviewModal - OpenAPI 匯入預覽對話框
 *
 * 顯示從 OpenAPI spec 解析出的規則清單，使用者可勾選/取消個別規則後確認匯入。
 */
const OpenApiPreviewModal = {
  props: {
    show: Boolean,
    title: String,
    version: String,
    rules: Array,
    loading: Boolean
  },
  emits: ['close', 'confirm'],
  inject: ['t'],
  data() {
    return {
      selectedIndices: [],
      previousFocus: null,
      inertSiblings: []
    };
  },
  computed: {
    allSelected() {
      return Boolean(this.rules?.length) && this.selectedIndices.length === this.rules.length;
    },
    selectedRules() {
      return this.selectedIndices.map(i => this.rules[i]);
    },
    partiallySelected() {
      return this.selectedIndices.length > 0 && !this.allSelected;
    }
  },
  watch: {
    rules(val) {
      if (val && val.length) {
        this.selectedIndices = val.map((_, i) => i);
      }
    },
    show(open) {
      if (open) {
        this.previousFocus = document.activeElement;
        this.$nextTick(() => {
          this.inertSiblings = makeOverlaySiblingsInert(this.$refs.overlay);
          (this.$refs.selectAll || this.$refs.dialog)?.focus();
        });
        return;
      }
      this.restoreDialog();
    }
  },
  beforeUnmount() {
    this.restoreDialog();
  },
  methods: {
    restoreDialog() {
      restoreOverlaySiblings(this.inertSiblings);
      this.inertSiblings = [];
      this.previousFocus?.isConnected && this.previousFocus.focus?.();
      this.previousFocus = null;
    },
    handleKeydown(event) {
      if (event.key === 'Escape' && !this.loading) {
        event.preventDefault();
        this.$emit('close');
        return;
      }
      trapDialogFocus(event, this.$refs.dialog);
    },
    toggleAll() {
      if (this.allSelected) {
        this.selectedIndices = [];
      } else {
        this.selectedIndices = this.rules.map((_, i) => i);
      }
    },
    toggleIndex(idx) {
      const pos = this.selectedIndices.indexOf(idx);
      if (pos >= 0) {
        this.selectedIndices.splice(pos, 1);
      } else {
        this.selectedIndices.push(idx);
      }
    },
    isSelected(idx) {
      return this.selectedIndices.includes(idx);
    },
    confirm() {
      this.$emit('confirm', this.selectedRules);
    },
    methodClass(method) {
      const m = (method || '').toUpperCase();
      return {
        'GET': 'badge-get',
        'POST': 'badge-post',
        'PUT': 'badge-put',
        'DELETE': 'badge-delete',
        'PATCH': 'badge-patch'
      }[m] || 'badge-default';
    },
    cleanDescription(rule) {
      if (!rule?.description) { return this.t('responses.noDescription'); }
      return rule.description.replace('[OpenAPI] ' + rule.method + ' ' + rule.matchKey + ' - ', '');
    }
  },
  template: /* html */`
    <div ref="overlay" class="modal-overlay" v-if="show" @keydown="handleKeydown">
      <div ref="dialog" class="modal-box workspace-modal openapi-preview-modal" role="dialog" aria-modal="true" aria-labelledby="openApiPreviewTitle" tabindex="-1">
        <div class="modal-header">
          <div class="modal-heading"><span class="modal-heading-icon"><i class="bi bi-filetype-yml" aria-hidden="true"></i></span><h3 id="openApiPreviewTitle">{{t('modal.openApiPreviewTitle')}}</h3></div>
          <button type="button" class="close-btn" @click="$emit('close')" :disabled="loading" :aria-label="t('modal.cancel')"><i class="bi bi-x-lg" aria-hidden="true"></i></button>
        </div>
        <div class="modal-body openapi-preview-body">
          <div class="openapi-preview-info">
            <div class="openapi-preview-document"><strong>{{title || t('modal.openApiUntitled')}}</strong><span v-if="version">v{{version}}</span></div>
            <span class="openapi-preview-count" role="status">{{t('modal.openApiRuleCount', {total: rules.length, selected: selectedIndices.length})}}</span>
          </div>
          <div v-if="rules && rules.length" class="openapi-preview-table-wrap">
            <table class="openapi-preview-table">
              <thead>
                <tr>
                  <th class="openapi-select-column"><input ref="selectAll" type="checkbox" :checked="allSelected" :indeterminate="partiallySelected" @change="toggleAll" :aria-label="t('modal.openApiSelectAll')"></th>
                  <th class="openapi-method-column">{{t('modal.method')}}</th>
                  <th>{{t('modal.pathLabel')}}</th>
                  <th class="openapi-status-column">{{t('modal.statusCode')}}</th>
                  <th>{{t('modal.description')}}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(rule, idx) in rules" :key="rule.method + ':' + rule.matchKey + ':' + idx" @click="toggleIndex(idx)" :class="{'is-selected':isSelected(idx)}">
                  <td><input type="checkbox" :checked="isSelected(idx)" @click.stop="toggleIndex(idx)" :aria-label="t('modal.openApiSelectRule', {method:rule.method, path:rule.matchKey})"></td>
                  <td><span class="method-badge" :class="methodClass(rule.method)">{{rule.method}}</span></td>
                  <td><code class="openapi-rule-path">{{rule.matchKey}}</code></td>
                  <td class="openapi-rule-status">{{rule.status}}</td>
                  <td><span class="openapi-rule-description" :title="cleanDescription(rule)">{{cleanDescription(rule)}}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="openapi-preview-empty">
            <i class="bi bi-file-earmark-x" aria-hidden="true"></i><span>{{t('modal.openApiNoRules')}}</span>
          </div>
        </div>
        <div class="modal-footer">
          <span class="openapi-preview-footer-count">{{t('modal.openApiRuleCount', {total: rules.length, selected: selectedIndices.length})}}</span>
          <button type="button" class="btn btn-secondary" @click="$emit('close')" :disabled="loading">{{t('modal.cancel')}}</button>
          <button type="button" class="btn btn-primary" @click="confirm" :disabled="!selectedIndices.length || loading">
            <i class="bi" :class="loading?'bi-arrow-clockwise spin':'bi-upload'" aria-hidden="true"></i>
            {{t('modal.openApiConfirmImport', {count: selectedIndices.length})}}
          </button>
        </div>
      </div>
    </div>
  `
};
