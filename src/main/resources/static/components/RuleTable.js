/**
 * RuleTable - 規則表格（列表檢視與分組檢視共用）
 *
 * 一列一條規則：點一下列開啟右側詳情抽屜，雙擊或按編輯鈕開啟編輯器，
 * 啟用開關立即生效，其餘操作收在「⋯」選單。欄位依容器寬度逐步隱藏。
 */
const RuleTable = {
  inject: ['t'],
  props: {
    rules: { type: Array, required: true },
    selectedId: { type: String, default: null },
    isLoggedIn: Boolean,
    status: Object,
    httpLabel: String,
    jmsLabel: String,
    sortable: { type: Boolean, default: false },
    ruleSort: { type: Object, default: () => ({}) },
    batchSelectMode: Boolean,
    selectedRules: { type: Array, default: () => [] },
    canDrag: Boolean,
    dragRowClass: { type: Function, default: () => '' },
  },
  emits: ['select', 'edit', 'toggle-enabled', 'menu', 'toggle-selection', 'toggle-select-all', 'sort',
    'drag-start', 'drag-over', 'drag-leave', 'drop', 'drag-end'],
  methods: {
    shortId, fmtTime, daysLeft, condTags, condTooltip,
    methodLabel(rule) {
      return rule.protocol === 'JMS' ? 'JMS' : (rule.method || 'GET');
    },
    retentionDays(rule) {
      if (rule.isProtected) return null;
      return daysLeft(rule.createdAt, rule.extendedAt, this.status?.cleanupRetentionDays);
    },
    sortState(field) {
      if (this.ruleSort.field !== field) return 'none';
      return this.ruleSort.asc ? 'ascending' : 'descending';
    },
    menuItems(rule) {
      const t = this.t;
      const items = [
        { key: 'copy', label: t('rules.quickCopy'), icon: 'bi-copy', disabled: !this.isLoggedIn },
        { key: 'history', label: t('rules.history'), icon: 'bi-journal-text' },
        { key: 'export', label: t('rules.exportJson'), icon: 'bi-download' },
      ];
      if (rule.responseId && this.isLoggedIn) {
        items.push({ key: 'response', label: t('rules.viewResponse'), icon: 'bi-chat-square-text' });
      }
      items.push({ key: 'delete', label: t('rules.delete'), icon: 'bi-trash', danger: true, dividerBefore: true, disabled: !this.isLoggedIn });
      return items;
    },
    onRowKeydown(event, rule) {
      if (event.target !== event.currentTarget) return;
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        this.$emit('select', rule);
      }
    },
  },
  template: /* html */`
    <table class="data-table rule-table">
      <thead><tr>
        <th v-if="canDrag" class="col-drag"><span class="visually-hidden">{{t('rules.dragColumn')}}</span></th>
        <th v-if="batchSelectMode" class="col-select">
          <input type="checkbox" :checked="selectedRules.length===rules.length && rules.length>0" :aria-label="t('rules.selectAll')" @change="$emit('toggle-select-all', $event)">
        </th>
        <th class="col-method">{{t('rules.thMethod')}}</th>
        <th class="col-endpoint">{{t('rules.thEndpoint')}}</th>
        <th class="col-cond">{{t('rules.thCondition')}}</th>
        <th class="col-priority cell-center" :aria-sort="sortable ? sortState('priority') : null">
          <ui-table-sort-header v-if="sortable" :label="t('rules.thPriority')" :active="ruleSort.field==='priority'" :ascending="ruleSort.asc" @toggle="$emit('sort', 'priority')"></ui-table-sort-header>
          <template v-else>{{t('rules.thPriority')}}</template>
        </th>
        <th class="col-updated" :aria-sort="sortable ? sortState('updatedAt') : null">
          <ui-table-sort-header v-if="sortable" :label="t('rules.thUpdated')" :active="ruleSort.field==='updatedAt'" :ascending="ruleSort.asc" @toggle="$emit('sort', 'updatedAt')"></ui-table-sort-header>
          <template v-else>{{t('rules.thUpdated')}}</template>
        </th>
        <th class="col-toggle">{{t('rules.thEnabled')}}</th>
        <th class="col-actions"><span class="visually-hidden">{{t('rules.thActions')}}</span></th>
      </tr></thead>
      <tbody>
        <tr v-for="r in rules" :key="r.id" data-detail-row tabindex="0"
          :class="[{'is-selected': selectedId===r.id, 'is-disabled': r.enabled===false, 'is-checked': batchSelectMode && selectedRules.includes(r.id)}, dragRowClass(r)]"
          :aria-selected="selectedId===r.id ? 'true' : 'false'"
          @click="batchSelectMode ? $emit('toggle-selection', r.id) : $emit('select', r)"
          @dblclick="!batchSelectMode && isLoggedIn && $emit('edit', r)"
          @keydown="onRowKeydown($event, r)"
          @dragover="(e) => $emit('drag-over', e, r)" @dragleave="(e) => $emit('drag-leave', e, r)" @drop="$emit('drop', $event)">
          <td v-if="canDrag" class="col-drag drag-handle-cell" draggable="true" :title="t('rules.dragRule', {id:shortId(r.id)})"
            @click.stop @dragstart="(e) => $emit('drag-start', e, r)" @dragend="$emit('drag-end')"><i class="bi bi-grip-vertical" aria-hidden="true"></i></td>
          <td v-if="batchSelectMode" class="col-select" @click.stop>
            <input type="checkbox" :checked="selectedRules.includes(r.id)" :aria-label="t('rules.selectRule', {id:shortId(r.id)})" @change="$emit('toggle-selection', r.id)">
          </td>
          <td class="col-method">
            <span class="rule-method" :data-method="r.protocol==='HTTP' ? (r.method || 'GET') : null" :data-protocol="r.protocol"
              :title="r.protocol==='HTTP' ? httpLabel : jmsLabel">{{methodLabel(r)}}</span>
          </td>
          <td class="col-endpoint">
            <div class="rule-identity">
              <code class="rule-path" :title="r.matchKey">{{r.matchKey}}</code>
              <span v-if="r.description" class="rule-desc" :title="r.description">{{r.description}}</span>
              <span class="rule-flags">
                <i v-if="r.isProtected" class="bi bi-shield-fill-check rule-flag-protected" :title="t('rules.isProtected')" role="img" :aria-label="t('rules.isProtected')"></i>
                <ui-badge v-if="r.sseEnabled" tone="neutral">SSE</ui-badge>
                <ui-badge v-if="r.action==='FORWARD'" tone="neutral">{{t('rules.forward')}}</ui-badge>
                <ui-badge v-if="r.faultType && r.faultType!=='NONE'" tone="warning" :title="t('rules.fault_'+r.faultType)">{{t('rules.faultInjection')}}</ui-badge>
                <ui-badge v-if="retentionDays(r) != null && retentionDays(r) <= 7" tone="warning">{{t('rules.daysLeft', {days: retentionDays(r)})}}</ui-badge>
              </span>
            </div>
          </td>
          <td class="col-cond" :title="condTooltip(r)">
            <span v-if="condTags(r).length" class="rule-cond">
              <span class="cond-chip" :data-kind="condTags(r)[0].t"><span class="cond-chip__label">{{condTags(r)[0].label}}</span><code>{{condTags(r)[0].v}}</code></span>
              <span v-if="condTags(r).length>1" class="cond-more">+{{condTags(r).length-1}}</span>
            </span>
            <span v-else class="cell-subtle">{{t('rules.noCondition')}}</span>
          </td>
          <td class="col-priority cell-center cell-mono">{{r.priority ?? 0}}</td>
          <td class="col-updated cell-mono cell-subtle" :title="fmtTime(r.updatedAt,false) + ' · ' + (r.updatedBy || t('rules.unknownOperator'))">{{fmtTime(r.updatedAt)}}</td>
          <td class="col-toggle" @click.stop @dblclick.stop>
            <ui-toggle :checked="r.enabled!==false" :disabled="!isLoggedIn" :aria-label="t('rules.toggleEnabled', {id:shortId(r.id)})" @toggle="$emit('toggle-enabled', r)"></ui-toggle>
          </td>
          <td class="col-actions" @click.stop @dblclick.stop>
            <span class="row-actions">
              <ui-button type="button" variant="quiet" size="compact" icon-only :disabled="!isLoggedIn"
                :title="isLoggedIn ? t('rules.edit') : t('rules.loginRequired')" :aria-label="t('rules.edit') + ' ' + r.matchKey"
                @click="$emit('edit', r)"><i class="bi bi-pencil" aria-hidden="true"></i></ui-button>
              <ui-row-menu :items="menuItems(r)" :label="t('common.moreActions') + ' ' + r.matchKey" @select="$emit('menu', $event, r)"></ui-row-menu>
            </span>
          </td>
        </tr>
      </tbody>
    </table>
  `,
};
