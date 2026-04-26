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
      selectedIndices: []
    };
  },
  computed: {
    allSelected() {
      return this.rules && this.selectedIndices.length === this.rules.length;
    },
    selectedRules() {
      return this.selectedIndices.map(i => this.rules[i]);
    }
  },
  watch: {
    rules(val) {
      if (val && val.length) {
        this.selectedIndices = val.map((_, i) => i);
      }
    }
  },
  methods: {
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
    truncate(str, len) {
      if (!str) { return ''; }
      return str.length > len ? str.substring(0, len) + '...' : str;
    }
  },
  template: /* html */`
    <div class="modal-overlay" v-if="show">
      <div class="modal-box" style="max-width:800px;max-height:85vh;display:flex;flex-direction:column">
        <div class="modal-header">
          <h3><i class="bi bi-filetype-yml"></i> {{t('modal.openApiPreviewTitle')}}</h3>
          <button class="close-btn" @click="$emit('close')" aria-label="Close"><i class="bi bi-x-lg"></i></button>
        </div>
        <div class="modal-body" style="padding:var(--block-py) 1.5rem;overflow-y:auto;flex:1">
          <div class="openapi-preview-info">
            <span v-if="title"><strong>{{title}}</strong></span>
            <span v-if="version" class="text-muted" style="margin-left:0.5rem">v{{version}}</span>
            <span class="text-muted" style="margin-left:auto">{{t('modal.openApiRuleCount', {total: rules.length, selected: selectedIndices.length})}}</span>
          </div>
          <table class="openapi-preview-table" role="grid">
            <thead>
              <tr>
                <th style="width:40px"><input type="checkbox" :checked="allSelected" @change="toggleAll" :aria-label="t('modal.openApiSelectAll')"></th>
                <th style="width:80px">{{t('modal.method')}}</th>
                <th>{{t('modal.pathLabel')}}</th>
                <th style="width:60px">{{t('modal.statusCode')}}</th>
                <th>{{t('modal.description')}}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(rule, idx) in rules" :key="idx" @click="toggleIndex(idx)" style="cursor:pointer" :class="{selected: isSelected(idx)}">
                <td><input type="checkbox" :checked="isSelected(idx)" @click.stop="toggleIndex(idx)" :aria-label="rule.matchKey"></td>
                <td><span class="method-badge" :class="methodClass(rule.method)">{{rule.method}}</span></td>
                <td class="mono">{{rule.matchKey}}</td>
                <td class="text-center">{{rule.status}}</td>
                <td class="text-muted">{{truncate(rule.description ? rule.description.replace('[OpenAPI] ' + rule.method + ' ' + rule.matchKey + ' - ', '') : '', 50)}}</td>
              </tr>
            </tbody>
          </table>
          <div v-if="!rules || !rules.length" class="text-center text-muted" style="padding:2rem">
            {{t('modal.openApiNoRules')}}
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-secondary" @click="$emit('close')">{{t('modal.cancel')}}</button>
          <button class="btn btn-primary" @click="confirm" :disabled="!selectedIndices.length || loading">
            <span v-if="loading" class="spinner-sm"></span>
            <i v-else class="bi bi-upload"></i>
            {{t('modal.openApiConfirmImport', {count: selectedIndices.length})}}
          </button>
        </div>
      </div>
    </div>
  `
};
