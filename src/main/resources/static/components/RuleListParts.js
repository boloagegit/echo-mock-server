/** Shared identity and actions keep flat and grouped rule lists equivalent. */
const RuleListIdentity = {
  inject: ['t'],
  props: { rule: Object, httpLabel: String, jmsLabel: String, status: Object },
  emits: ['clip-copy'],
  methods: { shortId, parseTags },
  template: /* html */`
    <div class="list-identity">
      <div class="rule-endpoint-main">
        <span class="rule-protocol" :title="rule.protocol==='HTTP'?httpLabel:jmsLabel">{{rule.protocol}}</span>
        <ui-badge v-if="rule.protocol==='HTTP'" class="badge badge-method">{{rule.method}}</ui-badge>
        <code :title="rule.matchKey">{{rule.matchKey}}</code>
        <i v-if="rule.isProtected" class="bi bi-shield-fill-check text-success" :title="t('rules.isProtected')" :aria-label="t('rules.isProtected')"></i>
        <ui-badge v-if="rule.sseEnabled" class="badge badge-sse">SSE</ui-badge>
        <ui-badge v-if="rule.action==='FORWARD'" class="badge badge-muted">{{t('rules.forward')}}</ui-badge>
        <ui-badge v-if="rule.faultType && rule.faultType!=='NONE'" class="badge badge-muted" :title="t('rules.fault_'+rule.faultType)">{{t('rules.faultInjection')}}</ui-badge>
      </div>
      <div class="list-identity-secondary">
        <span v-if="rule.description" class="rule-description" :title="rule.description">{{rule.description}}</span>
        <button type="button" class="list-id-copy" @click.stop="$emit('clip-copy',rule.id)" @dblclick.stop :title="rule.id" :aria-label="t('rules.copyId')+' '+rule.id">{{shortId(rule.id)}}<i class="bi bi-copy" aria-hidden="true"></i></button>
      </div>
      <div v-if="(rule.targetHost && rule.targetHost!=='default') || (status?.scenariosEnabled && rule.scenarioName) || Object.keys(parseTags(rule.tags)).length" class="rule-endpoint-meta">
        <span v-if="rule.targetHost && rule.targetHost!=='default'" class="rule-source-host" :title="t('rules.targetPrefix')+rule.targetHost"><span>{{t('rules.targetPrefix')}}</span><code>{{rule.targetHost}}</code></span>
        <span v-if="status?.scenariosEnabled && rule.scenarioName" class="rule-scenario-meta" :title="t('rules.scenarioTooltip',{name:rule.scenarioName,required:rule.requiredScenarioState||'Started',newState:rule.newScenarioState||rule.requiredScenarioState||'Started'})"><i class="bi bi-diagram-3" aria-hidden="true"></i><code>{{rule.scenarioName}}</code></span>
        <ui-badge v-for="(v,k) in parseTags(rule.tags)" :key="k" class="badge badge-tag" :title="k+'='+v">{{k}}:{{v}}</ui-badge>
      </div>
    </div>`
};

const RuleRowActions = {
  inject: ['t'],
  props: { rule: Object, isLoggedIn: Boolean, expanded: Boolean, previewId: String },
  emits: ['open-edit', 'toggle-rule-preview', 'show-rule-history', 'copy-rule'],
  methods: {
    invoke(action, event) {
      const menu = event.currentTarget.closest('details');
      if (menu) { menu.open = false; menu.querySelector('summary')?.focus(); }
      this.$emit(action, this.rule);
    }
  },
  template: /* html */`
    <div class="rule-row-actions" @click.stop @dblclick.stop>
      <ui-button type="button" class="btn btn-sm btn-secondary rule-row-edit" @click="$emit('open-edit',rule)" :title="!isLoggedIn?t('rules.loginRequired'):t('rules.edit')" :disabled="!isLoggedIn"><i class="bi bi-pencil" aria-hidden="true"></i><span>{{t('rules.edit')}}</span></ui-button>
      <details class="rule-row-more" @keydown.esc.stop.prevent="$event.currentTarget.open=false;$event.currentTarget.querySelector('summary').focus()">
        <summary class="btn btn-sm btn-icon btn-secondary" :aria-label="t('rules.moreActions')+' '+rule.id" :title="t('rules.moreActions')"><i class="bi bi-three-dots-vertical" aria-hidden="true"></i></summary>
        <div class="rule-row-more-popover">
          <button type="button" @click="invoke('toggle-rule-preview',$event)" :aria-expanded="expanded" :aria-controls="expanded?previewId:undefined"><i class="bi" :class="expanded?'bi-chevron-up':'bi-chevron-down'" aria-hidden="true"></i><span>{{expanded?t('rules.collapsePreview'):t('rules.expandPreview')}}</span></button>
          <button type="button" @click="invoke('show-rule-history',$event)"><i class="bi bi-clock-history" aria-hidden="true"></i><span>{{t('rules.history')}}</span></button>
          <button type="button" @click="invoke('copy-rule',$event)" :disabled="!isLoggedIn"><i class="bi bi-copy" aria-hidden="true"></i><span>{{t('rules.quickCopy')}}</span></button>
        </div>
      </details>
    </div>`
};
