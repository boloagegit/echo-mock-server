/**
 * RuleOrderView - 規則詳細資料的「判斷順序」：同一種請求會依什麼順序檢查哪些規則，
 * 先依序檢查有條件的規則，都不成立時才用第一條沒設條件的規則；並標出一定輪不到的規則與原因。
 * 資料來自 GET /api/admin/rules/{id}/order（與實際比對相同的排序）。
 */
const RuleOrderView = {
  props: {
    ruleId: { type: String, required: true },
    // 規則內容變更後（版本改變）重新載入
    version: { type: [String, Number], default: null }
  },
  emits: ['open-rule'],
  inject: ['t'],
  data() {
    return { order: null, loading: false, failed: false, expanded: {} };
  },
  computed: {
    numbered() {
      if (!this.order) { return []; }
      const conditional = this.order.entries.filter(entry => entry.conditional);
      const fallback = this.order.entries.filter(entry => !entry.conditional);
      return [...conditional, ...fallback].map((entry, i) => ({ ...entry, number: i + 1 }));
    },
    conditionalEntries() { return this.numbered.filter(entry => entry.conditional); },
    fallbackEntries() { return this.numbered.filter(entry => !entry.conditional); },
    numberById() {
      const map = {};
      this.numbered.forEach(entry => { map[entry.id] = entry.number; });
      return map;
    },
    defaultNumber() { return this.numbered.find(entry => entry.usedAsDefault)?.number || null; },
    self() { return this.numbered.find(entry => entry.self) || null; },
    /** 一定要顯示的項目：這條規則、擋住它的規則、預設回應。 */
    keepIds() {
      return [this.self?.id, this.self?.issue?.byRuleId, this.numbered.find(entry => entry.usedAsDefault)?.id]
        .filter(Boolean);
    },
    stages() {
      return [
        { key: 'conditional', entries: this.conditionalEntries },
        { key: 'fallback', entries: this.fallbackEntries },
      ].map(stage => ({
        ...stage,
        shown: this.expanded[stage.key] ? stage.entries
          : RuleOrder.visibleEntries(stage.entries, this.keepIds, this.self?.id),
      }));
    },
    scope() { return this.self ? this.route(this.self) : ''; },
    jms() { return this.order?.protocol === 'JMS'; }
  },
  watch: {
    ruleId: { immediate: true, handler() { this.expanded = {}; this.load(); } },
    version() { this.load(); }
  },
  methods: {
    async load() {
      const id = this.ruleId;
      this.loading = true;
      this.failed = false;
      try {
        const r = await apiCall('/api/admin/rules/' + encodeURIComponent(id) + '/order', {}, { silent: true });
        if (id !== this.ruleId) { return; }
        if (r && r.ok) {
          this.order = await r.json();
        } else {
          this.order = null;
          this.failed = true;
        }
      } catch {
        if (id === this.ruleId) { this.order = null; this.failed = true; }
      } finally {
        if (id === this.ruleId) { this.loading = false; }
      }
    },
    route(entry) {
      if (entry.queueName != null) { return entry.queueName; }
      const host = entry.targetHost && entry.targetHost !== 'default' ? ' @' + entry.targetHost : '';
      return (entry.method || this.t('rules.order.anyMethod')) + ' ' + entry.path + host;
    },
    /** 只適用部分請求時，列出它限定的 method、路徑與 host（host 為 default 也要寫出來）。 */
    partialScope(entry) {
      if (entry.queueName != null) { return entry.queueName; }
      const host = entry.targetHost ? ' · Host: ' + entry.targetHost : '';
      return (entry.method || this.t('rules.order.anyMethod')) + ' ' + entry.path + host;
    },
    conditionText(entry) {
      if (!entry.conditional) { return this.t('rules.order.noConditions'); }
      if (entry.matchSpec) {
        try { return MatchTree.summarizeSpec(JSON.parse(entry.matchSpec)).line; } catch { return entry.matchSpec; }
      }
      return [['body', entry.bodyCondition], ['query', entry.queryCondition], ['header', entry.headerCondition]]
        .filter(([, value]) => value && value.trim())
        .map(([source, value]) => source + ' ' + value.trim())
        .join(' · ');
    },
    issueReason(entry) {
      const issue = entry.issue;
      if (!issue) { return ''; }
      if (issue.type === 'duplicateConditions') {
        return this.t('rules.order.reasonDuplicate', { n: this.numberById[issue.byRuleId] || '?' });
      }
      if (issue.type === 'earlierDefault') {
        return this.t('rules.order.reasonEarlierDefault', { n: this.numberById[issue.byRuleId] || '?' });
      }
      return this.t('rules.order.reasonNotUsedHere', { n: this.defaultNumber || '?' });
    },
    issueLabel(entry) {
      return entry.issue?.type === 'notUsedHere' ? this.t('rules.order.notUsedHere') : this.t('rules.order.never');
    },
    gate(entry) {
      return entry.scenarioName && entry.requiredScenarioState
        ? this.t('rules.order.gated', { name: entry.scenarioName, state: entry.requiredScenarioState }) : '';
    },
    open(entry) {
      if (!entry.self) { this.$emit('open-rule', entry.id); }
    }
  },
  template: `
    <div class="ro">
      <p v-if="loading && !order" class="detail-empty">{{t('rules.order.loading')}}</p>
      <p v-else-if="failed" class="detail-empty">{{t('rules.order.loadFailed')}} <button type="button" class="detail-inline-action" @click="load">{{t('common.retry')}}</button></p>
      <template v-else-if="order">
        <p class="ro-scope">{{t(jms ? 'rules.order.scopeJms' : 'rules.order.scope', {scope: scope, count: numbered.length})}}</p>
        <p v-if="order.wildcard" class="ro-note"><i class="bi bi-info-circle" aria-hidden="true"></i>{{t('rules.order.wildcardNote')}}</p>
        <p v-if="self && !self.enabled" class="ro-note"><i class="bi bi-pause-circle" aria-hidden="true"></i>{{t('rules.order.selfDisabled')}}</p>

        <template v-for="stage in stages" :key="stage.key">
          <div v-if="stage.entries.length" class="ro-stage">
            <span class="ro-stage-n" aria-hidden="true">{{stage.key==='conditional' ? 1 : 2}}</span>
            <span>{{t('rules.order.stage_' + stage.key)}}</span><span class="ro-muted">{{t('rules.order.stageNote_' + stage.key)}}</span>
          </div>
          <ol v-if="stage.entries.length" class="ro-list">
            <li v-for="(entry, i) in stage.shown" :key="entry.id || 'gap-' + i">
              <button v-if="entry.gap" type="button" class="ro-gap" @click="expanded = {...expanded, [stage.key]: true}">
                <i class="bi bi-three-dots" aria-hidden="true"></i>{{t('rules.order.showHidden', {count: entry.gap, total: stage.entries.length})}}
              </button>
              <component v-else :is="entry.self ? 'div' : 'button'" :type="entry.self ? null : 'button'" class="ro-item"
                :class="{'is-self': entry.self, 'is-issue': entry.issue, 'is-default': entry.usedAsDefault, 'is-off': !entry.enabled}"
                :title="entry.self ? null : t('rules.order.openRule')" @click="open(entry)">
                <span class="ro-n tabular-nums">{{entry.number}}</span>
                <span class="ro-main">
                  <span class="ro-name">
                    <b>{{entry.description || t('rules.order.untitled')}}</b>
                    <code class="ro-muted">{{entry.id.slice(0, 8)}}</code>
                    <span v-if="entry.self" class="ro-self">{{t('rules.order.thisRule')}}</span>
                  </span>
                  <span class="ro-muted"><code>{{conditionText(entry)}}</code><template v-if="entry.priority != null"> · {{t('rules.order.priority', {n: entry.priority})}}</template></span>
                  <span v-if="gate(entry)" class="ro-muted">{{gate(entry)}}</span>
                  <span v-if="entry.partial" class="ro-muted">{{t('rules.order.partial', {route: partialScope(entry)})}}</span>
                  <span v-if="!entry.enabled" class="ro-muted">{{t('rules.order.disabled')}}</span>
                  <span v-if="entry.issue" class="ro-why">{{issueReason(entry)}}</span>
                </span>
                <span class="ro-side">
                  <ui-badge v-if="entry.issue" tone="warning"><i class="bi bi-exclamation-triangle" aria-hidden="true"></i>{{issueLabel(entry)}}</ui-badge>
                  <ui-badge v-else-if="entry.usedAsDefault" tone="success">{{t('rules.order.default')}}</ui-badge>
                  <span class="ro-route">{{route(entry)}}</span>
                </span>
              </component>
            </li>
          </ol>
        </template>

        <p class="ro-end"><i class="bi bi-arrow-return-right" aria-hidden="true"></i>{{t(jms ? 'rules.order.endJms' : 'rules.order.end')}}</p>
        <p class="ro-note ro-footnote">{{t('rules.order.limitNote')}}</p>
      </template>
    </div>
  `
};
