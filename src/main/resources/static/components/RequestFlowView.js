/**
 * RequestFlowView - "Processing flow" of request records: Receive request → Compare rules → Simulate response or forward (the road not taken is downplayed) → Return to the calling end,
 * and allocation of processing time. The data is organized by {@link RequestFlow.build}; when the container is wide enough, it is arranged horizontally, otherwise it is arranged from top to bottom.
 */
const RequestFlowView = {
  props: {
    item: { type: Object, required: true },
    ruleDescription: { type: String, default: '' }
  },
  emits: ['show-trace'],
  inject: ['t'],
  computed: {
    flow() {
      return RequestFlow.build(this.item.log, this.item._detail || null, this.item.matchChainData || null);
    },
    segments() { return RequestFlow.timingSegments(this.flow.timing); },
    description() { return this.flow.match.description || this.ruleDescription; },
    skippedShown() { return this.flow.match.skipped.slice(0, 3); },
    replyStatusTone() {
      const status = this.flow.reply.status;
      if (status == null) { return ''; }
      return status >= 500 ? 'is-bad' : status >= 400 ? 'is-warn' : 'is-ok';
    }
  },
  methods: {
    ms(value) { return Number(value || 0).toLocaleString(); },
    shortId(id) { return id ? String(id).slice(0, 8) : ''; },
    targetName(value) {
      if (!value) { return ''; }
      const separator = value.indexOf('|');
      return separator === -1 ? value.trim() : value.slice(0, separator).trim();
    },
    targetUrl(value) {
      if (!value) { return ''; }
      const separator = value.indexOf('|');
      return separator === -1 ? '' : value.slice(separator + 1).trim();
    },
    stepIcon(tone) {
      return { ok: 'bi-check-circle-fill', warn: 'bi-exclamation-circle-fill', bad: 'bi-x-circle-fill' }[tone] || 'bi-dash-circle';
    },
    segmentLabel(segment) {
      return this.t('stats.flow.time_' + segment.key, { ms: this.ms(segment.ms) });
    }
  },
  template: `
    <div class="rf" :class="{'is-forward': flow.route==='forward', 'is-not-found': flow.route==='notFound'}">
      <ol class="rf-steps" :aria-label="t('stats.flow.title')">
        <!-- 1. Request received -->
        <li class="rf-step rf-received">
          <section class="rf-card is-ok">
            <h4 class="rf-title"><i class="bi bi-box-arrow-in-right" aria-hidden="true"></i>{{flow.received.jms ? t('stats.flow.receivedJms') : t('stats.flow.received')}}</h4>
            <code class="rf-strong">{{flow.received.jms ? flow.received.endpoint : (flow.received.method + ' ' + flow.received.endpoint)}}</code>
            <div class="rf-muted">{{flow.received.protocol}}<template v-if="flow.received.clientIp"> · {{flow.received.clientIp}}</template></div>
            <div v-if="!flow.received.jms && flow.received.targetHost && flow.received.targetHost!=='default'" class="rf-muted">Host: {{flow.received.targetHost}}</div>
          </section>
        </li>

        <!-- 2. Comparison rules -->
        <li class="rf-step rf-match">
          <section class="rf-card" :class="'is-' + flow.match.tone">
            <h4 class="rf-title"><i class="bi bi-funnel" aria-hidden="true"></i>{{t('stats.flow.match')}}</h4>
            <div>
              <template v-if="flow.match.candidateCount===0">{{t('stats.flow.noCandidates')}}</template>
              <template v-else>
                <span v-if="flow.match.candidateCount!=null">{{t('stats.flow.candidates', {count: flow.match.candidateCount})}} · </span>
                <template v-if="flow.match.matched"><b>{{t('stats.flow.matched')}}</b> <code :title="flow.match.ruleId">{{shortId(flow.match.ruleId)}}</code></template>
                <b v-else>{{t('stats.flow.noneMatched')}}</b>
              </template>
            </div>
            <div v-if="flow.match.matched && (description || flow.match.byFallback)" class="rf-muted">
              <template v-if="description">{{description}}</template><template v-if="description && flow.match.byFallback"> · </template><template v-if="flow.match.byFallback">{{t('stats.flow.byFallback')}}</template>
            </div>
            <div v-if="flow.match.nearMiss" class="rf-muted">{{t('stats.flow.nearMiss', {rule: shortId(flow.match.nearMiss.ruleId), score: flow.match.nearMiss.score})}}</div>
            <div v-if="flow.match.matchTimeMs!=null" class="rf-muted">{{t('stats.flow.matchTime', {ms: ms(flow.match.matchTimeMs)})}}</div>
            <div v-if="flow.match.skipped.length" class="rf-skipped">
              <div class="rf-skipped-head"><i class="bi bi-skip-forward" aria-hidden="true"></i>{{t('stats.flow.skipped', {count: flow.match.skipped.length})}}</div>
              <div v-for="skip in skippedShown" :key="skip.ruleId" class="rf-muted rf-skipped-row">
                <code :title="skip.ruleId">{{shortId(skip.ruleId)}}</code>
                <span v-if="skip.required">{{t('stats.flow.skippedState', {required: skip.required, current: skip.current})}}</span>
                <span v-else>{{skip.detail}}</span>
              </div>
              <div v-if="flow.match.skipped.length > skippedShown.length" class="rf-muted">{{t('stats.flow.skippedMore', {count: flow.match.skipped.length - skippedShown.length})}}</div>
            </div>
            <button v-if="flow.match.hasTrace" type="button" class="rf-link" @click="$emit('show-trace')">
              {{t('stats.flow.showTrace')}}<i class="bi bi-chevron-right" aria-hidden="true"></i>
            </button>
          </section>
        </li>

        <!-- 3. Simulated response/forwarding (or rule not found) -->
        <li class="rf-step rf-route">
          <div v-if="flow.route==='notFound'" class="rf-fork is-single">
            <section class="rf-card is-warn">
              <h4 class="rf-title"><i class="bi bi-slash-circle" aria-hidden="true"></i>{{t('stats.flow.notFound')}}</h4>
              <div>{{t('stats.flow.notFoundDetail')}}</div>
            </section>
          </div>
          <div v-else class="rf-fork">
            <section class="rf-card" :class="'is-' + flow.mock.tone" :aria-current="flow.route==='mock' ? 'step' : null">
              <h4 class="rf-title">
                <i class="bi" :class="flow.mock.sse ? 'bi-broadcast' : 'bi-file-earmark-code'" aria-hidden="true"></i>{{flow.mock.sse && flow.route==='mock' ? t('stats.flow.sse') : t('stats.flow.mock')}}
              </h4>
              <template v-if="flow.route==='mock'">
                <div v-if="flow.mock.fault"><span class="rf-pill is-warn"><i class="bi bi-lightning" aria-hidden="true"></i>{{t('rules.fault_' + flow.mock.fault)}}</span></div>
                <div v-else-if="flow.mock.sse">{{t('stats.flow.sseEvents', {count: flow.mock.sseEventCount})}}</div>
                <div v-else>{{t('stats.flow.mockContent')}}</div>
                <div v-if="flow.mock.delayMs" class="rf-muted">{{t('stats.flow.delay', {ms: ms(flow.mock.delayMs)})}}</div>
                <div v-if="flow.mock.scenario" class="rf-muted">{{t('stats.flow.scenario', {name: flow.mock.scenario.name})}}{{flow.mock.scenario.from}} → <b>{{flow.mock.scenario.to}}</b></div>
              </template>
              <div v-else class="rf-muted">{{t('stats.flow.notTaken')}}</div>
            </section>
            <section class="rf-card" :class="'is-' + flow.forward.tone" :aria-current="flow.route==='forward' ? 'step' : null">
              <h4 class="rf-title"><i class="bi bi-signpost-split" aria-hidden="true"></i>{{t('stats.flow.forward')}}</h4>
              <template v-if="flow.route==='forward'">
                <div class="rf-muted">{{flow.forward.byRule ? t('stats.flow.forwardByRule') : t('stats.flow.forwardUnmatched')}}</div>
                <div v-if="flow.forward.target">
                  <span>{{targetName(flow.forward.target)}}</span>
                  <code v-if="targetUrl(flow.forward.target)" class="rf-url">{{targetUrl(flow.forward.target)}}</code>
                </div>
                <div v-if="flow.forward.outcome==='failed'"><span class="rf-pill is-bad">{{t('stats.flow.forwardFailed')}}</span> <span class="rf-error">{{flow.forward.proxyError}}</span></div>
                <div v-else-if="flow.forward.proxyStatus!=null">{{t('stats.flow.forwardStatus')}} <span class="rf-pill" :class="flow.forward.outcome==='errorStatus' ? 'is-warn' : 'is-ok'">{{flow.forward.proxyStatus}}</span></div>
                <div v-else>{{t('stats.flow.forwardDone')}}</div>
                <div v-if="flow.forward.delayMs" class="rf-muted">{{t('stats.flow.delay', {ms: ms(flow.forward.delayMs)})}}</div>
              </template>
              <div v-else class="rf-muted">{{t('stats.flow.notTaken')}}</div>
            </section>
          </div>
        </li>

        <!-- 4. Return to the calling party -->
        <li class="rf-step rf-reply">
          <section class="rf-card" :class="'is-' + flow.reply.tone">
            <h4 class="rf-title"><i class="bi bi-reply" aria-hidden="true"></i>{{flow.reply.jms ? t('stats.flow.replyJms') : t('stats.flow.reply')}}</h4>
            <div v-if="flow.reply.connectionReset">{{t('stats.flow.connectionReset')}}</div>
            <div v-else-if="flow.reply.status!=null"><span class="rf-pill" :class="replyStatusTone">{{flow.reply.status}}</span><span v-if="flow.reply.emptyResponse" class="rf-muted"> · {{t('stats.flow.emptyResponse')}}</span></div>
            <div v-else-if="flow.reply.jms">{{t('stats.flow.replySent')}}</div>
            <div class="rf-muted">{{t('stats.flow.total', {ms: ms(flow.timing.totalMs)})}}</div>
          </section>
        </li>
      </ol>

      <section class="rf-time" :aria-label="t('stats.flow.timeTitle', {ms: ms(flow.timing.totalMs)})">
        <div class="rf-time-title">{{t('stats.flow.timeTitle', {ms: ms(flow.timing.totalMs)})}}</div>
        <div v-if="segments.length" class="rf-bar" aria-hidden="true">
          <span v-for="segment in segments" :key="segment.key" class="rf-bar-part" :class="'is-' + segment.key + (segment.tone==='bad' ? ' is-bad' : '')" :style="{width: segment.width + '%'}"></span>
        </div>
        <ul class="rf-legend">
          <li v-for="segment in segments" :key="segment.key"><i class="rf-swatch" :class="'is-' + segment.key + (segment.tone==='bad' ? ' is-bad' : '')" aria-hidden="true"></i>{{segmentLabel(segment)}}</li>
        </ul>
        <div v-if="flow.timing.delayMs" class="rf-note">{{t('stats.flow.delayNote')}}</div>
        <div v-if="flow.timing.sse" class="rf-note">{{t('stats.flow.sseNote')}}</div>
        <div v-if="!flow.complete" class="rf-note">{{t('stats.flow.loadingDetail')}}</div>
      </section>
    </div>
  `
};
