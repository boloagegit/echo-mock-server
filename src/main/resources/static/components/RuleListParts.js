/** Shared identity and actions keep flat and grouped rule lists equivalent. */
const RuleListIdentity = {
  inject: ['t'],
  props: { rule: Object, httpLabel: String, jmsLabel: String, status: Object },
  emits: ['clip-copy'],
  methods: {
    shortId, parseTags, condTags, fmtTime,
    tagEntries(rule) {
      return Object.entries(parseTags(rule.tags));
    },
  },
  template: /* html */`
    <div class="list-identity">
      <div class="rule-endpoint-main">
        <span class="rule-protocol" :data-protocol="rule.protocol" :title="rule.protocol==='HTTP'?httpLabel:jmsLabel">{{rule.protocol}}</span>
        <span class="rule-method-slot"><ui-badge v-if="rule.protocol==='HTTP'" class="badge badge-method" :data-method="rule.method">{{rule.method}}</ui-badge></span>
        <code :title="rule.matchKey">{{rule.matchKey}}</code>
        <span v-if="rule.isProtected || rule.sseEnabled || rule.action==='FORWARD' || (rule.faultType && rule.faultType!=='NONE')" class="rule-endpoint-flags">
          <i v-if="rule.isProtected" class="bi bi-shield-fill-check text-success" :title="t('rules.isProtected')" :aria-label="t('rules.isProtected')"></i>
          <ui-badge v-if="rule.sseEnabled" class="badge badge-sse">SSE</ui-badge>
          <ui-badge v-if="rule.action==='FORWARD'" class="badge badge-muted">{{t('rules.forward')}}</ui-badge>
          <ui-badge v-if="rule.faultType && rule.faultType!=='NONE'" class="badge badge-muted" :title="t('rules.fault_'+rule.faultType)">{{t('rules.faultInjection')}}</ui-badge>
        </span>
      </div>
      <div class="list-identity-secondary">
        <span class="rule-endpoint-details" :class="{'has-description':!!rule.description}">
          <span v-if="rule.description" class="rule-description" :title="rule.description">{{rule.description}}</span>
          <span class="rule-technical-meta">
            <button type="button" class="list-id-copy" @click.stop="$emit('clip-copy',rule.id)" @dblclick.stop :title="rule.id" :aria-label="t('rules.copyId')+' '+rule.id">{{shortId(rule.id)}}<i class="bi bi-copy" aria-hidden="true"></i></button>
            <span v-if="rule.targetHost && rule.targetHost!=='default'" class="rule-source-host" :title="t('rules.targetPrefix')+rule.targetHost"><span>{{t('rules.targetPrefix')}}</span><code>{{rule.targetHost}}</code></span>
            <span v-if="status?.scenariosEnabled && rule.scenarioName" class="rule-scenario-meta" :title="t('rules.scenarioTooltip',{name:rule.scenarioName,required:rule.requiredScenarioState||'Started',newState:rule.newScenarioState||rule.requiredScenarioState||'Started'})"><i class="bi bi-diagram-3" aria-hidden="true"></i><code>{{rule.scenarioName}}</code></span>
            <span v-if="tagEntries(rule).length" class="rule-tag-summary" :title="tagEntries(rule).map(([k,v])=>k+'='+v).join(', ')">{{tagEntries(rule)[0][0]}}:{{tagEntries(rule)[0][1]}}<span v-if="tagEntries(rule).length>1"> +{{tagEntries(rule).length-1}}</span></span>
          </span>
        </span>
        <span class="rule-responsive-meta"><button type="button" class="list-id-copy rule-responsive-id" @click.stop="$emit('clip-copy',rule.id)" @dblclick.stop :title="rule.id" :aria-label="t('rules.copyId')+' '+rule.id">{{shortId(rule.id)}}<i class="bi bi-copy" aria-hidden="true"></i></button><span class="rule-compact-condition" :title="condTags(rule).length?condTags(rule).map(c=>c.label+' '+c.v).join('; '):t('rules.noCondition')">{{condTags(rule).length ? condTags(rule)[0].label+' '+condTags(rule)[0].v : t('rules.noCondition')}}<span v-if="condTags(rule).length>1"> +{{condTags(rule).length-1}}</span> · P{{rule.priority ?? 0}} · {{fmtTime(rule.updatedAt)}}</span><span class="rule-compact-enabled"> · {{rule.enabled!==false?t('rules.filterEnabled'):t('rules.filterDisabled')}}</span><span class="rule-compact-flags"><i v-if="rule.isProtected" class="bi bi-shield-fill-check text-success" :title="t('rules.isProtected')"></i><ui-badge v-if="rule.sseEnabled" class="badge badge-sse">SSE</ui-badge><ui-badge v-if="rule.action==='FORWARD'" class="badge badge-muted">{{t('rules.forward')}}</ui-badge><ui-badge v-if="rule.faultType && rule.faultType!=='NONE'" class="badge badge-muted" :title="t('rules.fault_'+rule.faultType)">{{t('rules.faultInjection')}}</ui-badge></span></span>
      </div>
    </div>`
};

const ruleMenuPosition = (trigger, bounds, width, height, gap = 4) => ({
  left: Math.max(bounds.left + gap, Math.min(trigger.left - width - gap, bounds.right - width - gap)),
  top: Math.max(bounds.top + gap, Math.min(trigger.top + trigger.height / 2 - height / 2, bounds.bottom - height - gap))
});

const RuleRowActions = {
  inject: ['t'],
  props: { rule: Object, isLoggedIn: Boolean, expanded: Boolean, previewId: String },
  emits: ['open-edit', 'toggle-rule-preview', 'show-rule-history', 'copy-rule'],
  data() { return { popoverStyle: { visibility: 'hidden' } }; },
  beforeUnmount() { this.removeMenuListeners(); },
  methods: {
    removeMenuListeners() {
      window.removeEventListener('resize', this._positionMenu);
      document.removeEventListener('scroll', this._positionMenu, true);
      document.removeEventListener('pointerdown', this._outsideMenu);
      if (this._menuFrame) cancelAnimationFrame(this._menuFrame);
      this._menuFrame = null;
    },
    onMenuToggle() {
      this.removeMenuListeners();
      this.popoverStyle = { visibility: 'hidden' };
      if (!this.$refs.moreMenu?.open) return;
      this._positionMenu = () => {
        if (this._menuFrame) return;
        this._menuFrame = requestAnimationFrame(() => {
          this._menuFrame = null;
          this.positionMenu();
        });
      };
      this._outsideMenu = event => {
        if (!this.$refs.moreMenu?.contains(event.target)) this.$refs.moreMenu.open = false;
      };
      window.addEventListener('resize', this._positionMenu);
      document.addEventListener('scroll', this._positionMenu, { capture: true, passive: true });
      document.addEventListener('pointerdown', this._outsideMenu);
      this.$nextTick(() => this.positionMenu());
    },
    positionMenu() {
      const menu = this.$refs.moreMenu;
      const panel = this.$refs.morePanel;
      if (!menu?.open || !panel) return;
      const bounds = { left: 0, top: 0, right: window.innerWidth, bottom: window.innerHeight };
      for (let node = menu.parentElement; node; node = node.parentElement) {
        const style = getComputedStyle(node);
        const rect = node.getBoundingClientRect();
        if (/(auto|scroll|hidden|clip)/.test(style.overflowX)) {
          bounds.left = Math.max(bounds.left, rect.left + node.clientLeft);
          bounds.right = Math.min(bounds.right, rect.left + node.clientLeft + node.clientWidth);
        }
        if (/(auto|scroll|hidden|clip)/.test(style.overflowY)) {
          bounds.top = Math.max(bounds.top, rect.top + node.clientTop);
          bounds.bottom = Math.min(bounds.bottom, rect.top + node.clientTop + node.clientHeight);
        }
      }
      const anchor = menu.getBoundingClientRect();
      const trigger = menu.querySelector('summary').getBoundingClientRect();
      const position = ruleMenuPosition(trigger, bounds, panel.offsetWidth, panel.offsetHeight);
      this.popoverStyle = { left: (position.left - anchor.left) + 'px', top: (position.top - anchor.top) + 'px' };
    },
    invoke(action, event) {
      const menu = event.currentTarget.closest('details');
      if (menu) { menu.open = false; menu.querySelector('summary')?.focus(); }
      this.$emit(action, this.rule);
    }
  },
  template: /* html */`
    <div class="rule-row-actions" @click.stop @dblclick.stop>
      <ui-button type="button" class="btn btn-sm btn-secondary rule-row-edit" @click="$emit('open-edit',rule)" :title="!isLoggedIn?t('rules.loginRequired'):t('rules.edit')" :disabled="!isLoggedIn"><i class="bi bi-pencil" aria-hidden="true"></i><span>{{t('rules.edit')}}</span></ui-button>
      <ui-button type="button" class="btn btn-sm btn-icon btn-secondary rule-row-disclosure" @click="$emit('toggle-rule-preview',rule)" :aria-expanded="expanded" :aria-controls="expanded?previewId:undefined" :title="expanded?t('rules.collapsePreview'):t('rules.expandPreview')" :aria-label="expanded?t('rules.collapsePreview'):t('rules.expandPreview')"><i class="bi" :class="expanded?'bi-chevron-up':'bi-chevron-down'" aria-hidden="true"></i></ui-button>
      <details ref="moreMenu" class="rule-row-more" @toggle="onMenuToggle" @keydown.esc.stop.prevent="$event.currentTarget.open=false;$event.currentTarget.querySelector('summary').focus()">
        <summary class="btn btn-sm btn-icon btn-secondary" :aria-label="t('rules.moreActions')+' '+rule.id" :title="t('rules.moreActions')"><i class="bi bi-three-dots-vertical" aria-hidden="true"></i></summary>
        <div ref="morePanel" class="rule-row-more-popover" :style="popoverStyle">
          <button type="button" @click="invoke('show-rule-history',$event)"><i class="bi bi-clock-history" aria-hidden="true"></i><span>{{t('rules.history')}}</span></button>
          <button type="button" @click="invoke('copy-rule',$event)" :disabled="!isLoggedIn"><i class="bi bi-copy" aria-hidden="true"></i><span>{{t('rules.quickCopy')}}</span></button>
        </div>
      </details>
    </div>`
};
