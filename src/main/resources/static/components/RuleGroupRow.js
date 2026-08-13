/**
 * RuleGroupRow - 規則分組檢視表格行
 *
 * 用於分組檢視（group view）中的規則表格行。
 * 支援 preview 展開，行為與列表檢視一致。
 */
const RuleGroupRow = {
  inject: ['t'],
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
    'open-edit', 'copy-rule', 'delete-rule',
    'toggle-enabled', 'go-to-responses', 'extend-rule',
    'toggle-rule-preview', 'handle-rule-row-click',
    'clip-copy', 'export-rule-json',
  ],
  methods: {
    shortId, fmtTime, daysLeft, condTooltip, condTags, parseTags, fmtCond,
  },
  template: /* html */`
<tr :class="{'row-clickable':true}" @click="$emit('handle-rule-row-click', rule)" :title="t('rules.clickPreviewDblEdit')">
    <td class="col-id">
        <span class="badge badge-id" :title="rule.id">{{shortId(rule.id)}}</span>
        <i v-if="rule.isProtected" class="bi bi-shield-fill-check text-success" :title="t('rules.isProtected')" style="margin-left:4px"></i>
    </td>
    <td class="col-type col-hide-sm"><span class="badge" :class="'badge-'+rule.protocol.toLowerCase()">{{rule.protocol==='HTTP'?httpLabel:jmsLabel}}</span></td>
    <td class="col-endpoint">
        <div style="display:flex;align-items:center;gap:0.5rem">
            <span v-if="rule.protocol==='HTTP'" class="badge badge-method">{{rule.method}}</span>
            <code style="font-weight:500" :title="rule.matchKey">{{rule.matchKey}}</code>
            <span v-if="rule.sseEnabled" class="badge badge-sse" :title="t('rules.sseStream')">SSE</span>
            <span v-if="rule.action==='FORWARD'" class="badge badge-muted"><i class="bi bi-box-arrow-up-right"></i> {{t('rules.forward')}}</span>
            <span v-if="rule.faultType&&rule.faultType!=='NONE'" class="badge badge-muted" :title="t('rules.fault_'+rule.faultType)"><i class="bi bi-lightning" aria-hidden="true"></i> {{t('rules.faultInjection')}}</span>
        </div>
        <div v-if="rule.description" class="sub-info" style="margin-top:2px" :title="rule.description">{{rule.description}}</div>
        <div v-if="rule.targetHost||(status?.scenariosEnabled&&rule.scenarioName)" class="rule-endpoint-meta"><span v-if="rule.targetHost" class="rule-source-host" :title="t('rules.targetPrefix')+rule.targetHost"><span>{{t('rules.targetPrefix')}}</span><code>{{rule.targetHost}}</code></span><span v-if="status?.scenariosEnabled&&rule.scenarioName" class="rule-scenario-meta" :title="t('rules.scenarioTooltip',{name:rule.scenarioName,required:rule.requiredScenarioState||'Started',newState:rule.newScenarioState||rule.requiredScenarioState||'Started'})"><i class="bi bi-diagram-3" aria-hidden="true"></i><code>{{rule.scenarioName}}</code></span></div>
    </td>
    <td class="col-cond col-hide-md" :title="condTooltip(rule)">
        <div v-if="condTags(rule).length" class="cond-list">
            <span v-for="(c,i) in condTags(rule)" :key="i" class="cond-tag" :class="c.t" :title="c.v"><span class="cond-label">{{c.label}}</span>{{c.v}}</span>
        </div>
        <span v-else class="sub-info">{{t('rules.noCondition')}}</span>
    </td>
    <td class="col-hide-sm">
        <label class="toggle" @click.stop :class="{'disabled':!isLoggedIn}">
            <input type="checkbox" :checked="rule.enabled!==false" @click.prevent="$emit('toggle-enabled', rule)" :disabled="!isLoggedIn" :aria-label="t('rules.toggleEnabled', {id:shortId(rule.id)})">
            <span class="toggle-slider"></span>
        </label>
    </td>
    <td class="col-priority col-hide-md"><span class="badge badge-muted">{{rule.priority ?? 0}}</span></td>
    <td class="col-datetime col-hide-md">
        <div class="table-date-stack">
            <span class="sub-info" :title="fmtTime(rule.createdAt,false)">{{fmtTime(rule.createdAt)}}</span>
            <span v-if="!rule.isProtected && daysLeft(rule.createdAt, rule.extendedAt, status?.cleanupRetentionDays) != null" class="badge" :class="daysLeft(rule.createdAt, rule.extendedAt, status?.cleanupRetentionDays) <= 7 ? 'badge-warning' : 'badge-muted'">{{t('rules.daysLeft', {days: daysLeft(rule.createdAt, rule.extendedAt, status?.cleanupRetentionDays)})}}</span>
        </div>
    </td>
    <td class="col-actions col-actions-3">
        <div style="display:flex;gap:0.25rem">
            <button class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('toggle-rule-preview', rule)" :title="rulePreviewExpanded[rule.id]?t('rules.collapsePreview'):t('rules.expandPreview')" :aria-label="rulePreviewExpanded[rule.id]?t('rules.collapsePreview'):t('rules.expandPreview')"><i class="bi" :class="rulePreviewExpanded[rule.id]?'bi-chevron-up':'bi-chevron-down'"></i></button>
            <button class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('copy-rule', rule)" :title="!isLoggedIn?t('rules.loginRequired'):t('rules.quickCopy')" :aria-label="t('rules.quickCopy')" :disabled="!isLoggedIn"><i class="bi bi-copy"></i></button>
            <button class="btn btn-sm btn-icon btn-secondary" @click.stop="$emit('open-edit', rule)" :title="!isLoggedIn?t('rules.loginRequired'):t('rules.edit')" :aria-label="t('rules.edit')" :disabled="!isLoggedIn"><i class="bi bi-pencil"></i></button>
        </div>
    </td>
</tr>
<tr v-if="rulePreviewExpanded[rule.id]" class="rule-preview-row">
    <td colspan="8" class="rule-preview-cell">
        <div v-if="rulePreviewLoading[rule.id]" class="rule-preview-content rule-preview-state" role="status">
            <i class="bi bi-arrow-clockwise spin" aria-hidden="true"></i><span>{{t('rules.loading')}}</span>
        </div>
        <div v-else-if="rulePreviewError[rule.id]" class="rule-preview-content rule-preview-state rule-preview-error" role="alert">
            <i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>{{t('rules.previewLoadFailed')}}</span>
            <button type="button" class="btn btn-sm btn-secondary" @click.stop="$emit('toggle-rule-preview', rule)"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</button>
        </div>
        <div v-else-if="rulePreviewCache[rule.id]" class="rule-preview-content">
            <div class="pv-header">
                <span class="pv-id" @click="$emit('clip-copy', rulePreviewCache[rule.id].id)" :title="t('rules.copyId')"><i class="bi bi-fingerprint"></i> {{rulePreviewCache[rule.id].id}} <i class="bi bi-clipboard pv-copy-icon"></i></span>
                <span v-if="rulePreviewCache[rule.id].description" class="pv-desc">— {{rulePreviewCache[rule.id].description}}</span>
                <span style="flex:1"></span>
                <button class="btn btn-sm btn-secondary" @click.stop="$emit('open-edit', rulePreviewCache[rule.id])" :disabled="!isLoggedIn" :title="t('rules.edit')" :aria-label="t('rules.edit')"><i class="bi bi-pencil"></i></button>
                <button class="btn btn-sm btn-secondary" @click.stop="$emit('export-rule-json', rulePreviewCache[rule.id].id)" :title="t('rules.exportJson')" :aria-label="t('rules.exportJson')"><i class="bi bi-download"></i></button>
                <button v-if="rulePreviewCache[rule.id].responseId && isLoggedIn" class="btn btn-sm btn-secondary" @click.stop="$emit('go-to-responses', rulePreviewCache[rule.id].responseId)" :title="t('rules.viewResponse')" :aria-label="t('rules.viewResponse')"><i class="bi bi-file-earmark-text"></i></button>
                <button class="btn btn-sm btn-danger" @click.stop="$emit('delete-rule', rulePreviewCache[rule.id].id)" :disabled="!isLoggedIn" :title="t('rules.delete')" :aria-label="t('rules.delete')"><i class="bi bi-trash"></i></button>
            </div>
            <div class="pv-main">
                <div class="pv-fields">
                    <div class="pv-section-title">{{t('rules.pvSectionSettings')}}</div>
                    <div class="pv-field" v-if="rulePreviewCache[rule.id].protocol==='HTTP'&&rulePreviewCache[rule.id].action!=='FORWARD'&&rulePreviewCache[rule.id].faultType!=='CONNECTION_RESET'"><span class="pv-label">{{t('rules.pvStatusCode')}}</span><span class="badge" :class="rulePreviewCache[rule.id].status<400?'badge-success':rulePreviewCache[rule.id].status<500?'badge-warning':'badge-danger'">{{rulePreviewCache[rule.id].status}}</span></div>
                    <div class="pv-field" v-if="rulePreviewCache[rule.id].action==='FORWARD'"><span class="pv-label">{{t('rules.forward')}}</span><span>{{rulePreviewCache[rule.id].forwardTargetMode==='CONNECTION' ? '#'+rulePreviewCache[rule.id].httpTargetConnectionId : rulePreviewCache[rule.id].forwardTargetMode}}</span></div>
                    <div class="pv-field" v-if="rulePreviewCache[rule.id].faultType&&rulePreviewCache[rule.id].faultType!=='NONE'"><span class="pv-label">{{t('rules.faultInjection')}}</span><span class="pv-result-value"><i class="bi bi-lightning" aria-hidden="true"></i>{{t('rules.fault_'+rulePreviewCache[rule.id].faultType)}}</span></div>
                    <div class="pv-field" v-if="status?.scenariosEnabled&&rulePreviewCache[rule.id].scenarioName"><span class="pv-label">{{t('rules.pvScenario')}}</span><span class="pv-scenario-value" :title="t('rules.scenarioTooltip',{name:rulePreviewCache[rule.id].scenarioName,required:rulePreviewCache[rule.id].requiredScenarioState||'Started',newState:rulePreviewCache[rule.id].newScenarioState||rulePreviewCache[rule.id].requiredScenarioState||'Started'})"><strong>{{rulePreviewCache[rule.id].scenarioName}}</strong><code>{{rulePreviewCache[rule.id].requiredScenarioState||'Started'}}</code><i class="bi bi-arrow-right" aria-hidden="true"></i><code>{{rulePreviewCache[rule.id].newScenarioState||rulePreviewCache[rule.id].requiredScenarioState||'Started'}}</code></span></div>
                    <div class="pv-field"><span class="pv-label">{{t('rules.pvDelay')}}</span><span>{{rulePreviewCache[rule.id].delayMs||0}} ms</span></div>
                    <div class="pv-field"><span class="pv-label">{{t('rules.pvPriority')}}</span><span>{{rulePreviewCache[rule.id].priority||0}}</span></div>
                    <div class="pv-field"><span class="pv-label">{{t('rules.pvProtected')}}</span><span><i class="bi" :class="rulePreviewCache[rule.id].isProtected ? 'bi-shield-fill-check text-success' : 'bi-shield'" style="margin-right:2px"></i> {{rulePreviewCache[rule.id].isProtected ? t('rules.pvYes') : t('rules.pvNo')}}</span></div>
                    <div class="pv-field" v-if="!rulePreviewCache[rule.id].isProtected && daysLeft(rulePreviewCache[rule.id].createdAt, rulePreviewCache[rule.id].extendedAt, status?.cleanupRetentionDays) != null"><span class="pv-label">{{t('rules.pvDaysLeft')}}</span><span><span class="badge" :class="daysLeft(rulePreviewCache[rule.id].createdAt, rulePreviewCache[rule.id].extendedAt, status?.cleanupRetentionDays) <= 7 ? 'badge-warning' : 'badge-muted'">{{t('rules.daysLeft', {days: daysLeft(rulePreviewCache[rule.id].createdAt, rulePreviewCache[rule.id].extendedAt, status?.cleanupRetentionDays)})}}</span> <button v-if="isLoggedIn" class="btn btn-sm btn-secondary" style="margin-left:0.5rem;padding:0.1rem 0.4rem;font-size:0.75rem" @click.stop="$emit('extend-rule', rulePreviewCache[rule.id].id)"><i class="bi bi-calendar-plus"></i> {{t('rules.extend')}}</button></span></div>
                    <div class="pv-field" v-if="rulePreviewCache[rule.id].createdAt"><span class="pv-label">{{t('rules.pvCreated')}}</span><span>{{fmtTime(rulePreviewCache[rule.id].createdAt, false)}}</span></div>
                    <div class="pv-field" v-if="rulePreviewCache[rule.id].updatedAt"><span class="pv-label">{{t('rules.pvUpdated')}}</span><span>{{fmtTime(rulePreviewCache[rule.id].updatedAt, false)}}</span></div>
                    <div class="pv-section-title" style="margin-top:0.5rem">{{t('rules.pvSectionConditions')}}</div>
                    <div class="pv-field pv-field-cond" v-if="rulePreviewCache[rule.id].bodyCondition"><span class="pv-label">{{t('rules.pvBodyCondition')}}</span><div class="pv-cond-list"><code v-for="(c,i) in rulePreviewCache[rule.id].bodyCondition.split(';').filter(x=>x)" :key="'b'+i" class="pv-cond-item body" @click="$emit('clip-copy', c.trim())" :title="t('rules.clickToCopy')">{{c.trim()}}</code></div></div>
                    <div class="pv-field pv-field-cond" v-if="rulePreviewCache[rule.id].queryCondition"><span class="pv-label">{{t('rules.pvQueryCondition')}}</span><div class="pv-cond-list"><code v-for="(c,i) in rulePreviewCache[rule.id].queryCondition.split(';').filter(x=>x)" :key="'q'+i" class="pv-cond-item query" @click="$emit('clip-copy', c.trim())" :title="t('rules.clickToCopy')">{{c.trim()}}</code></div></div>
                    <div class="pv-field pv-field-cond" v-if="rulePreviewCache[rule.id].headerCondition"><span class="pv-label">{{t('rules.pvHeaderCondition')}}</span><div class="pv-cond-list"><code v-for="(c,i) in rulePreviewCache[rule.id].headerCondition.split(';').filter(x=>x)" :key="'h'+i" class="pv-cond-item header" @click="$emit('clip-copy', c.trim())" :title="t('rules.clickToCopy')">{{c.trim()}}</code></div></div>
                    <div class="pv-field" v-if="rulePreviewCache[rule.id].responseHeaders"><span class="pv-label">{{t('rules.pvResponseHeaders')}}</span><code class="pv-code-copy" @click="$emit('clip-copy', rulePreviewCache[rule.id].responseHeaders)" :title="t('rules.clickToCopy')">{{rulePreviewCache[rule.id].responseHeaders}}</code></div>
                    <div class="pv-field" v-if="rulePreviewCache[rule.id].tags"><span class="pv-label">{{t('rules.pvTags')}}</span><span><span class="tag-badge" v-for="(v,k) in parseTags(rulePreviewCache[rule.id].tags)" :key="k">{{k}}:{{v}}</span></span></div>
                    <div class="pv-field" v-if="!rulePreviewCache[rule.id].bodyCondition && !rulePreviewCache[rule.id].queryCondition && !rulePreviewCache[rule.id].headerCondition"><span class="pv-label"></span><span class="sub-info">{{t('rules.noCondition')}}</span></div>
                </div>
                <div class="pv-body">
                    <div class="pv-body-header">
                        <span class="pv-label">{{rulePreviewCache[rule.id].faultType&&rulePreviewCache[rule.id].faultType!=='NONE'?t('rules.faultInjection'):t('rules.pvResponseContent')}} <span v-if="rulePreviewCache[rule.id]._isSse" class="badge badge-sse" style="margin-left:4px">SSE</span></span>
                        <div v-if="!rulePreviewCache[rule.id].faultType||rulePreviewCache[rule.id].faultType==='NONE'" style="display:flex;align-items:center;gap:0.35rem">
                            <button v-if="rulePreviewCache[rule.id]._previewBody" class="btn btn-sm btn-icon btn-secondary" @click="$emit('clip-copy', rulePreviewCache[rule.id].responseBody||rulePreviewCache[rule.id]._previewBody)" :title="t('rules.copyFullContent')" :aria-label="t('rules.copyFullContent')"><i class="bi bi-clipboard"></i></button>
                        </div>
                    </div>
                    <div v-if="rulePreviewCache[rule.id].faultType&&rulePreviewCache[rule.id].faultType!=='NONE'" class="pv-fault-preview"><i class="bi bi-lightning" aria-hidden="true"></i><strong>{{t('rules.fault_'+rulePreviewCache[rule.id].faultType)}}</strong><span>{{rulePreviewCache[rule.id].faultType==='EMPTY_RESPONSE' ? t('modal.faultEmptyResponse'+(rulePreviewCache[rule.id].protocol==='JMS'?'Jms':'Http')+'Hint') : t('modal.faultConnectionReset'+(rulePreviewCache[rule.id].protocol==='JMS'?'Jms':'Http')+'Hint')}}</span></div>
                    <pre v-else class="pv-pre">{{rulePreviewCache[rule.id]._previewBody || t('rules.empty')}}</pre>
                </div>
            </div>
        </div>
    </td>
</tr>
`
};
