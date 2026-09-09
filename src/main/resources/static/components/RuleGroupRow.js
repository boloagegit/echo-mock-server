/**
 * RuleGroupRow - 規則分組檢視表格行
 *
 * 用於分組檢視（group view）中的規則表格行。
 * 支援 preview 展開，行為與列表檢視一致。
 */
const RuleGroupRow = {
  inject: ['t'],
  setup() {
    return { previewId: 'group-rule-preview-' + Vue.useId() };
  },
  props: {
    rule: Object,
    isLoggedIn: Boolean,
    httpLabel: String,
    jmsLabel: String,
    status: Object,
    rulePreviewExpanded: Object,
    rulePreviewLoading: Object,
    rulePreviewError: Object,
    rulePreviewCache: Object,
  },
  emits: [
    'open-edit', 'copy-rule', 'delete-rule', 'show-rule-history',
    'toggle-enabled', 'go-to-responses', 'extend-rule',
    'toggle-rule-preview', 'handle-rule-row-click',
    'clip-copy', 'export-rule-json',
  ],
  methods: {
    shortId, fmtTime, daysLeft, condTooltip, condTags, parseTags, fmtCond, forwardTargetLabel,
  },
  template: /* html */`
<tr :class="{'row-clickable':true}" @click="$emit('handle-rule-row-click', rule)" :title="t('rules.clickPreviewDblEdit')">
    <td class="col-endpoint"><rule-list-identity :rule="rule" :http-label="httpLabel" :jms-label="jmsLabel" :status="status" @clip-copy="$emit('clip-copy',$event)"></rule-list-identity></td>
    <td class="col-cond col-hide-md" :title="condTooltip(rule)">
        <div v-if="condTags(rule).length" class="cond-list">
            <span v-for="(c,i) in condTags(rule)" :key="i" class="cond-tag" :class="c.t" :title="c.v"><span class="cond-label">{{c.label}}</span>{{c.v}}</span>
        </div>
        <span v-else class="sub-info">{{t('rules.noCondition')}}</span>
    </td>
    <td class="col-hide-sm">
        <ui-toggle :checked="rule.enabled!==false" :disabled="!isLoggedIn" :aria-label="t('rules.toggleEnabled', {id:shortId(rule.id)})" @toggle="$emit('toggle-enabled', rule)"></ui-toggle>
    </td>
    <td class="col-priority col-hide-md"><span class="table-metadata">{{rule.priority ?? 0}}</span></td>
    <td class="col-datetime col-hide-md">
        <div class="table-date-stack">
            <span class="sub-info" :title="fmtTime(rule.updatedAt,false)">{{fmtTime(rule.updatedAt)}}</span>
            <span class="rule-updated-by" :title="rule.updatedBy||t('rules.unknownOperator')"><i class="bi bi-person" aria-hidden="true"></i>{{rule.updatedBy||t('rules.unknownOperator')}}</span>
            <ui-badge v-if="!rule.isProtected && daysLeft(rule.createdAt, rule.extendedAt, status?.cleanupRetentionDays) != null && daysLeft(rule.createdAt, rule.extendedAt, status?.cleanupRetentionDays) <= 7" class="badge badge-warning">{{t('rules.daysLeft', {days: daysLeft(rule.createdAt, rule.extendedAt, status?.cleanupRetentionDays)})}}</ui-badge>
        </div>
    </td>
    <td class="col-actions rule-row-action-column"><rule-row-actions :rule="rule" :is-logged-in="isLoggedIn" :expanded="!!rulePreviewExpanded[rule.id]" :preview-id="previewId" @open-edit="$emit('open-edit',$event)" @toggle-rule-preview="$emit('toggle-rule-preview',$event)" @show-rule-history="$emit('show-rule-history',$event)" @copy-rule="$emit('copy-rule',$event)"></rule-row-actions></td>
</tr>
<tr v-if="rulePreviewExpanded[rule.id]" :id="previewId" class="rule-preview-row">
    <td colspan="6" class="rule-preview-cell">
        <div v-if="rulePreviewLoading[rule.id]" class="rule-preview-content rule-preview-state" role="status">
            <i class="bi bi-arrow-clockwise spin" aria-hidden="true"></i><span>{{t('rules.loading')}}</span>
        </div>
        <div v-else-if="rulePreviewError[rule.id]" class="rule-preview-content rule-preview-state rule-preview-error" role="alert">
            <i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>{{t('rules.previewLoadFailed')}}</span>
            <ui-button type="button" class="btn btn-sm btn-secondary" @click.stop="$emit('toggle-rule-preview', rule)"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</ui-button>
        </div>
        <div v-else-if="rulePreviewCache[rule.id]" class="rule-preview-content">
            <div class="pv-header">
                <span class="pv-id" @click="$emit('clip-copy', rulePreviewCache[rule.id].id)" :title="t('rules.copyId')"><i class="bi bi-fingerprint"></i> {{rulePreviewCache[rule.id].id}} <i class="bi bi-clipboard pv-copy-icon"></i></span>
                <span v-if="rulePreviewCache[rule.id].description" class="pv-desc">— {{rulePreviewCache[rule.id].description}}</span>
                <span style="flex:1"></span>
                <ui-button class="btn btn-sm btn-secondary" @click.stop="$emit('open-edit', rulePreviewCache[rule.id])" :disabled="!isLoggedIn" :title="t('rules.edit')" :aria-label="t('rules.edit')"><i class="bi bi-pencil"></i></ui-button>
                <ui-button class="btn btn-sm btn-secondary" @click.stop="$emit('export-rule-json', rulePreviewCache[rule.id].id)" :title="t('rules.exportJson')" :aria-label="t('rules.exportJson')"><i class="bi bi-download"></i></ui-button>
                <ui-button v-if="rulePreviewCache[rule.id].responseId && isLoggedIn" class="btn btn-sm btn-secondary" @click.stop="$emit('go-to-responses', rulePreviewCache[rule.id].responseId)" :title="t('rules.viewResponse')" :aria-label="t('rules.viewResponse')"><i class="bi bi-file-earmark-text"></i></ui-button>
                <ui-button class="btn btn-sm btn-danger" @click.stop="$emit('delete-rule', rulePreviewCache[rule.id].id)" :disabled="!isLoggedIn" :title="t('rules.delete')" :aria-label="t('rules.delete')"><i class="bi bi-trash"></i></ui-button>
            </div>
            <div class="pv-main">
                <div class="pv-fields">
                    <div class="pv-section-title">{{t('rules.pvSectionSettings')}}</div>
<div class="pv-field" v-if="rulePreviewCache[rule.id].targetHost"><span class="pv-label">{{t('rules.targetPrefix')}}</span><code>{{rulePreviewCache[rule.id].targetHost}}</code></div>
                    <div class="pv-field" v-if="rulePreviewCache[rule.id].protocol==='HTTP'&&rulePreviewCache[rule.id].action!=='FORWARD'&&rulePreviewCache[rule.id].faultType!=='CONNECTION_RESET'"><span class="pv-label">{{t('rules.pvStatusCode')}}</span><ui-badge class="badge" :class="rulePreviewCache[rule.id].status<400?'badge-success':rulePreviewCache[rule.id].status<500?'badge-warning':'badge-danger'">{{rulePreviewCache[rule.id].status}}</ui-badge></div>
                    <div class="pv-field" v-if="rulePreviewCache[rule.id].action==='FORWARD'"><span class="pv-label">{{t('rules.forward')}}</span><span>{{forwardTargetLabel(rulePreviewCache[rule.id])}}</span></div>
                    <div class="pv-field" v-if="rulePreviewCache[rule.id].faultType&&rulePreviewCache[rule.id].faultType!=='NONE'"><span class="pv-label">{{t('rules.faultInjection')}}</span><span class="pv-result-value"><i class="bi bi-lightning" aria-hidden="true"></i>{{t('rules.fault_'+rulePreviewCache[rule.id].faultType)}}</span></div>
                    <div class="pv-field" v-if="status?.scenariosEnabled&&rulePreviewCache[rule.id].scenarioName"><span class="pv-label">{{t('rules.pvScenario')}}</span><span class="pv-scenario-value" :title="t('rules.scenarioTooltip',{name:rulePreviewCache[rule.id].scenarioName,required:rulePreviewCache[rule.id].requiredScenarioState||'Started',newState:rulePreviewCache[rule.id].newScenarioState||rulePreviewCache[rule.id].requiredScenarioState||'Started'})"><strong>{{rulePreviewCache[rule.id].scenarioName}}</strong><code>{{rulePreviewCache[rule.id].requiredScenarioState||'Started'}}</code><i class="bi bi-arrow-right" aria-hidden="true"></i><code>{{rulePreviewCache[rule.id].newScenarioState||rulePreviewCache[rule.id].requiredScenarioState||'Started'}}</code></span></div>
                    <div class="pv-field"><span class="pv-label">{{t('rules.pvDelay')}}</span><span>{{rulePreviewCache[rule.id].delayMs||0}} ms</span></div>
                    <div class="pv-field"><span class="pv-label">{{t('rules.pvPriority')}}</span><span>{{rulePreviewCache[rule.id].priority||0}}</span></div>
                    <div class="pv-field"><span class="pv-label">{{t('rules.pvProtected')}}</span><span><i class="bi" :class="rulePreviewCache[rule.id].isProtected ? 'bi-shield-fill-check text-success' : 'bi-shield'" style="margin-right:2px"></i> {{rulePreviewCache[rule.id].isProtected ? t('rules.pvYes') : t('rules.pvNo')}}</span></div>
                    <div class="pv-field" v-if="!rulePreviewCache[rule.id].isProtected && daysLeft(rulePreviewCache[rule.id].createdAt, rulePreviewCache[rule.id].extendedAt, status?.cleanupRetentionDays) != null"><span class="pv-label">{{t('rules.pvDaysLeft')}}</span><span><ui-badge class="badge" :class="daysLeft(rulePreviewCache[rule.id].createdAt, rulePreviewCache[rule.id].extendedAt, status?.cleanupRetentionDays) <= 7 ? 'badge-warning' : 'badge-muted'">{{t('rules.daysLeft', {days: daysLeft(rulePreviewCache[rule.id].createdAt, rulePreviewCache[rule.id].extendedAt, status?.cleanupRetentionDays)})}}</ui-badge> <ui-button v-if="isLoggedIn" class="btn btn-sm btn-secondary" style="margin-left:0.5rem;padding:0.1rem 0.4rem;font-size:0.75rem" @click.stop="$emit('extend-rule', rulePreviewCache[rule.id].id)"><i class="bi bi-calendar-plus"></i> {{t('rules.extend')}}</ui-button></span></div>
                    <div class="pv-field" v-if="rulePreviewCache[rule.id].createdAt"><span class="pv-label">{{t('rules.pvCreated')}}</span><span>{{fmtTime(rulePreviewCache[rule.id].createdAt, false)}}</span></div>
                    <div class="pv-field" v-if="rulePreviewCache[rule.id].updatedAt"><span class="pv-label">{{t('rules.pvUpdated')}}</span><span>{{fmtTime(rulePreviewCache[rule.id].updatedAt, false)}} · {{rulePreviewCache[rule.id].updatedBy||t('rules.unknownOperator')}}</span></div>
                    <div class="pv-section-title pv-section-conditions"><span>{{t('rules.pvSectionConditions')}}</span><span v-if="!rulePreviewCache[rule.id].bodyCondition && !rulePreviewCache[rule.id].queryCondition && !rulePreviewCache[rule.id].headerCondition" class="pv-section-summary">{{t('rules.noCondition')}}</span></div>
                    <div class="pv-field pv-field-cond" v-if="rulePreviewCache[rule.id].bodyCondition"><span class="pv-label">{{t('rules.pvBodyCondition')}}</span><div class="pv-cond-list"><code v-for="(c,i) in rulePreviewCache[rule.id].bodyCondition.split(';').filter(x=>x)" :key="'b'+i" class="pv-cond-item body" @click="$emit('clip-copy', c.trim())" :title="t('rules.clickToCopy')">{{c.trim()}}</code></div></div>
                    <div class="pv-field pv-field-cond" v-if="rulePreviewCache[rule.id].queryCondition"><span class="pv-label">{{t('rules.pvQueryCondition')}}</span><div class="pv-cond-list"><code v-for="(c,i) in rulePreviewCache[rule.id].queryCondition.split(';').filter(x=>x)" :key="'q'+i" class="pv-cond-item query" @click="$emit('clip-copy', c.trim())" :title="t('rules.clickToCopy')">{{c.trim()}}</code></div></div>
                    <div class="pv-field pv-field-cond" v-if="rulePreviewCache[rule.id].headerCondition"><span class="pv-label">{{t('rules.pvHeaderCondition')}}</span><div class="pv-cond-list"><code v-for="(c,i) in rulePreviewCache[rule.id].headerCondition.split(';').filter(x=>x)" :key="'h'+i" class="pv-cond-item header" @click="$emit('clip-copy', c.trim())" :title="t('rules.clickToCopy')">{{c.trim()}}</code></div></div>
                    <div class="pv-field" v-if="rulePreviewCache[rule.id].responseHeaders"><span class="pv-label">{{t('rules.pvResponseHeaders')}}</span><code class="pv-code-copy" @click="$emit('clip-copy', rulePreviewCache[rule.id].responseHeaders)" :title="t('rules.clickToCopy')">{{rulePreviewCache[rule.id].responseHeaders}}</code></div>
                    <div class="pv-field" v-if="Object.keys(parseTags(rulePreviewCache[rule.id].tags)||{}).length"><span class="pv-label">{{t('rules.pvTags')}}</span><span><span class="tag-badge" v-for="(v,k) in parseTags(rulePreviewCache[rule.id].tags)" :key="k">{{k}}:{{v}}</span></span></div>
                </div>
                <div class="pv-body">
                    <div class="pv-body-header">
                        <span class="pv-label">{{rulePreviewCache[rule.id].faultType&&rulePreviewCache[rule.id].faultType!=='NONE'?t('rules.faultInjection'):t('rules.pvResponseContent')}} <ui-badge v-if="rulePreviewCache[rule.id]._isSse" class="badge badge-sse" style="margin-left:4px">SSE</ui-badge></span>
                        <div v-if="!rulePreviewCache[rule.id].faultType||rulePreviewCache[rule.id].faultType==='NONE'" style="display:flex;align-items:center;gap:0.35rem">
                            <ui-button v-if="rulePreviewCache[rule.id]._previewBody" class="btn btn-sm btn-icon btn-secondary" @click="$emit('clip-copy', rulePreviewCache[rule.id].responseBody||rulePreviewCache[rule.id]._previewBody)" :title="t('rules.copyFullContent')" :aria-label="t('rules.copyFullContent')"><i class="bi bi-clipboard"></i></ui-button>
                        </div>
                    </div>
                    <div v-if="rulePreviewCache[rule.id].faultType&&rulePreviewCache[rule.id].faultType!=='NONE'" class="pv-fault-preview"><i class="bi bi-lightning" aria-hidden="true"></i><strong>{{t('rules.fault_'+rulePreviewCache[rule.id].faultType)}}</strong><span>{{rulePreviewCache[rule.id].faultType==='EMPTY_RESPONSE' ? t('modal.faultEmptyResponse'+(rulePreviewCache[rule.id].protocol==='JMS'?'Jms':'Http')+'Hint') : t('modal.faultConnectionReset'+(rulePreviewCache[rule.id].protocol==='JMS'?'Jms':'Http')+'Hint')}}</span></div>
                    <div v-else-if="!rulePreviewCache[rule.id]._previewBody" class="pv-body-empty">{{t('rules.empty')}}</div>
                    <pre v-else class="pv-pre">{{rulePreviewCache[rule.id]._previewBody || t('rules.empty')}}</pre>
                </div>
            </div>
        </div>
    </td>
</tr>
`
};
