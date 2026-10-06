/**
 * RuleDetail - 規則詳情抽屜
 *
 * 由規則列表點選開啟。上方是常用動作（編輯、複製、查看回應），其餘收在「⋯」；
 * 內容依「匹配條件 → 回應 → 規則資訊」排列，與編輯器的閱讀順序一致。
 */
const RuleDetail = {
  inject: ['t'],
  mixins: [heldDetailMixin(function () {
    return { open: this.open, key: this.rule?.id, ready: !!this.detail || this.error, value: { rule: this.rule, detail: this.detail } };
  })],
  props: {
    open: Boolean,
    rule: { type: Object, default: null },
    detail: { type: Object, default: null },
    loading: Boolean,
    error: Boolean,
    isLoggedIn: Boolean,
    status: Object,
    httpLabel: String,
    jmsLabel: String,
    hasPrev: Boolean,
    hasNext: Boolean,
  },
  emits: ['close', 'prev', 'next', 'retry', 'edit', 'menu', 'clip-copy'],
  data() {
    return { bodySearch: '', bodyMatchIndex: 0, showFullBody: false, bodyExpanded: false, showAllConditions: false, showAllTags: false };
  },
  computed: {
    DETAIL_LIST_PREVIEW: () => DETAIL_LIST_PREVIEW,
    BODY_PREVIEW_CHARS: () => BODY_PREVIEW_CHARS,
    shownDetail() {
      return this.held.value.detail;
    },
    view() {
      return this.held.value.detail || this.held.value.rule || {};
    },
    isHttp() {
      return this.view.protocol !== 'JMS';
    },
    hasFault() {
      return !!this.view.faultType && this.view.faultType !== 'NONE';
    },
    modeLabel() {
      if (this.hasFault) return this.t('rules.mode_FAULT');
      return this.view.action === 'FORWARD' ? this.t('rules.mode_FORWARD') : this.t('rules.mode_MOCK');
    },
    showStatusCode() {
      return this.isHttp && this.view.action !== 'FORWARD' && this.view.faultType !== 'CONNECTION_RESET' && this.view.status != null;
    },
    conditionGroups() {
      return condTagsGrouped(this.view);
    },
    conditionCount() {
      return this.conditionGroups.reduce((sum, group) => sum + group.items.length, 0);
    },
    /** Many conditions show the first few; the rest are one click away. */
    visibleConditionGroups() {
      if (this.showAllConditions || this.conditionCount <= DETAIL_LIST_PREVIEW.conditions) return this.conditionGroups;
      let room = DETAIL_LIST_PREVIEW.conditions;
      return this.conditionGroups.map(group => {
        const items = group.items.slice(0, room);
        room -= items.length;
        return { ...group, items };
      }).filter(group => group.items.length);
    },
    tagEntries() {
      return Object.entries(parseTags(this.view.tags));
    },
    visibleTagEntries() {
      return this.showAllTags ? this.tagEntries : this.tagEntries.slice(0, DETAIL_LIST_PREVIEW.tags);
    },
    retention() {
      if (this.view.isProtected) return null;
      return daysLeft(this.view.createdAt, this.view.extendedAt, this.status?.cleanupRetentionDays);
    },
    bodyFull() {
      return formatBodyForReading(this.shownDetail?._previewBody || '');
    },
    bodyTruncated() {
      return !this.showFullBody && this.bodyFull.length > BODY_PREVIEW_CHARS;
    },
    body() {
      return this.bodyTruncated ? this.bodyFull.slice(0, BODY_PREVIEW_CHARS) : this.bodyFull;
    },
    bodySegments() {
      const keyword = this.bodySearch.trim();
      if (!keyword || !this.body) return [{ text: this.body, hit: false }];
      const parts = [];
      const lower = this.body.toLowerCase();
      const needle = keyword.toLowerCase();
      let pos = 0;
      let index = lower.indexOf(needle);
      let hit = 0;
      while (index !== -1) {
        if (index > pos) parts.push({ text: this.body.slice(pos, index), hit: false });
        parts.push({ text: this.body.slice(index, index + keyword.length), hit: true, current: hit === this.bodyMatchIndex });
        hit++;
        pos = index + keyword.length;
        index = lower.indexOf(needle, pos);
      }
      if (pos < this.body.length) parts.push({ text: this.body.slice(pos), hit: false });
      return parts;
    },
    bodyMatchCount() {
      return this.bodySegments.filter(part => part.hit).length;
    },
    menuItems() {
      const t = this.t;
      const items = [
        { key: 'history', label: t('rules.history'), icon: 'bi-journal-text' },
        { key: 'export', label: t('rules.exportJson'), icon: 'bi-download' },
      ];
      if (this.retention != null && this.isLoggedIn) {
        items.push({ key: 'extend', label: t('rules.extend'), icon: 'bi-calendar-plus' });
      }
      items.push({ key: 'delete', label: t('rules.delete'), icon: 'bi-trash', danger: true, dividerBefore: true, disabled: !this.isLoggedIn });
      return items;
    },
  },
  watch: {
    'view.id'() {
      this.bodySearch = '';
      this.bodyMatchIndex = 0;
      this.showFullBody = false;
      this.bodyExpanded = false;
      this.showAllConditions = false;
      this.showAllTags = false;
    },
    bodySearch() {
      this.bodyMatchIndex = 0;
    },
  },
  methods: {
    shortId, fmtTime, fmtSize, forwardTargetLabel, forwardTargetEndpoint,
    stepMatch(direction) {
      if (!this.bodyMatchCount) return;
      this.bodyMatchIndex = (this.bodyMatchIndex + direction + this.bodyMatchCount) % this.bodyMatchCount;
      this.$nextTick(() => this.$refs.bodyPre?.querySelector('.is-current')?.scrollIntoView({ block: 'nearest' }));
    },
    statusTone(code) {
      return code < 400 ? 'success' : code < 500 ? 'warning' : 'danger';
    },
    conditionTitle(type) {
      return this.t(type === 'body' ? 'rules.pvBodyCondition' : type === 'query' ? 'rules.pvQueryCondition' : 'rules.pvHeaderCondition');
    },
  },
  template: /* html */`
    <ui-detail-drawer :open="held.open" :title="view.matchKey || ''" :subtitle="view.description || ''"
      :loading="held.waiting" :error="error && !shownDetail" :stale="held.stale" :has-prev="hasPrev" :has-next="hasNext"
      class="rule-detail" @close="$emit('close')" @prev="$emit('prev')" @next="$emit('next')" @retry="$emit('retry')">
      <template #meta>
        <span class="rule-method" :data-method="isHttp ? (view.method || 'GET') : null" :data-protocol="view.protocol">{{isHttp ? (view.method || 'GET') : 'JMS'}}</span>
        <ui-status :tone="view.enabled===false ? 'neutral' : 'success'">{{view.enabled===false ? t('rules.filterDisabled') : t('rules.filterEnabled')}}</ui-status>
        <ui-badge tone="neutral">{{modeLabel}}</ui-badge>
        <ui-badge v-if="view.isProtected" tone="success"><i class="bi bi-shield-fill-check" aria-hidden="true"></i>{{t('rules.isProtected')}}</ui-badge>
        <ui-badge v-if="view.sseEnabled" tone="neutral">SSE</ui-badge>
      </template>
      <template #actions>
        <ui-button type="button" variant="primary" size="compact" :disabled="!isLoggedIn" @click="$emit('edit', view)"><i class="bi bi-pencil" aria-hidden="true"></i>{{t('rules.edit')}}</ui-button>
        <ui-button type="button" variant="secondary" size="compact" :disabled="!isLoggedIn" @click="$emit('menu', 'copy', view)"><i class="bi bi-copy" aria-hidden="true"></i>{{t('rules.quickCopy')}}</ui-button>
        <ui-button v-if="view.responseId && isLoggedIn" type="button" variant="secondary" size="compact" @click="$emit('menu', 'response', view)"><i class="bi bi-chat-square-text" aria-hidden="true"></i>{{t('rules.viewResponse')}}</ui-button>
        <ui-row-menu :items="menuItems" :label="t('common.moreActions')" @select="$emit('menu', $event, view)"></ui-row-menu>
      </template>

      <section class="detail-section">
        <div class="detail-section__head"><h3 class="detail-section__title">{{t('rules.pvSectionConditions')}}</h3></div>
        <p v-if="!conditionGroups.length" class="detail-empty">{{t('rules.noCondition')}}</p>
        <dl v-else class="detail-grid">
          <template v-for="group in visibleConditionGroups" :key="group.type">
            <dt>{{conditionTitle(group.type)}}</dt>
            <dd class="detail-chips">
              <button v-for="(item, i) in group.items" :key="group.type+i" type="button" class="cond-chip is-copyable" :data-kind="group.type"
                :title="t('rules.clickToCopy')" @click="$emit('clip-copy', item.v.trim())"><code>{{item.v.trim()}}</code></button>
            </dd>
          </template>
        </dl>
        <button v-if="conditionCount > DETAIL_LIST_PREVIEW.conditions" type="button" class="detail-inline-action"
          :aria-expanded="showAllConditions ? 'true' : 'false'" @click="showAllConditions = !showAllConditions">
          {{showAllConditions ? t('common.showLess') : t('common.showAllCount', {count: conditionCount})}}</button>
      </section>

      <section class="detail-section">
        <div class="detail-section__head"><h3 class="detail-section__title">{{t('rules.pvSectionResponse')}}</h3></div>
        <dl class="detail-grid">
          <template v-if="showStatusCode"><dt>{{t('rules.pvStatusCode')}}</dt><dd><ui-badge :tone="statusTone(view.status)">{{view.status}}</ui-badge></dd></template>
          <template v-if="view.action==='FORWARD'">
            <dt>{{t('rules.forward')}}</dt>
            <dd class="rule-detail-forward"><span>{{forwardTargetLabel(view)}}</span><strong v-if="view._forwardTargetName">{{view._forwardTargetName}}</strong><code v-if="forwardTargetEndpoint(view)">{{forwardTargetEndpoint(view)}}</code></dd>
          </template>
          <template v-if="hasFault"><dt>{{t('rules.faultInjection')}}</dt><dd>{{t('rules.fault_'+view.faultType)}}</dd></template>
          <template v-if="status?.scenariosEnabled && view.scenarioName">
            <dt>{{t('rules.pvScenario')}}</dt>
            <dd><strong>{{view.scenarioName}}</strong> <code>{{view.requiredScenarioState||'Started'}}</code> → <code>{{view.newScenarioState||view.requiredScenarioState||'Started'}}</code></dd>
          </template>
          <dt>{{t('rules.pvDelay')}}</dt><dd class="detail-mono">{{view.delayMs || 0}} ms</dd>
          <template v-if="view.responseHeaders"><dt>{{t('rules.pvResponseHeaders')}}</dt><dd><code>{{view.responseHeaders}}</code></dd></template>
        </dl>
        <template v-if="!hasFault">
          <div class="detail-section__head">
            <h4 class="detail-subtitle">{{t('rules.pvResponseContent')}}</h4>
            <div v-if="body" class="detail-section__tools">
              <label class="detail-search">
                <span class="visually-hidden">{{t('rules.pvSearchBody')}}</span>
                <i class="bi bi-search" aria-hidden="true"></i>
                <input v-model="bodySearch" type="search" :placeholder="t('rules.pvSearchBody')"
                  @keydown.enter.prevent="stepMatch($event.shiftKey ? -1 : 1)">
                <span v-if="bodySearch" class="detail-search__count">{{bodyMatchCount ? bodyMatchIndex + 1 : 0}}/{{bodyMatchCount}}</span>
              </label>
              <ui-button type="button" variant="quiet" size="compact" icon-only :title="bodyExpanded ? t('common.collapseBlock') : t('common.expandBlock')"
                :aria-label="bodyExpanded ? t('common.collapseBlock') : t('common.expandBlock')" :aria-pressed="bodyExpanded ? 'true' : 'false'"
                @click="bodyExpanded = !bodyExpanded"><i class="bi" :class="bodyExpanded ? 'bi-arrows-angle-contract' : 'bi-arrows-angle-expand'" aria-hidden="true"></i></ui-button>
              <ui-button type="button" variant="quiet" size="compact" icon-only :title="t('rules.copyFullContent')" :aria-label="t('rules.copyFullContent')"
                @click="$emit('clip-copy', view.responseBody || shownDetail?._previewBody || body)"><i class="bi bi-clipboard" aria-hidden="true"></i></ui-button>
            </div>
          </div>
          <p v-if="!body && shownDetail" class="detail-empty">{{t('rules.empty')}}</p>
          <pre v-else-if="body" ref="bodyPre" class="detail-code" :class="{'is-expanded': bodyExpanded}"><template v-for="(part, i) in bodySegments" :key="i"><mark v-if="part.hit" :class="{'is-current': part.current}">{{part.text}}</mark><template v-else>{{part.text}}</template></template></pre>
          <p v-if="bodyTruncated" class="detail-truncated">
            <span>{{t('common.previewTruncated', {shown: fmtSize(BODY_PREVIEW_CHARS), total: fmtSize(bodyFull.length)})}}</span>
            <button type="button" class="detail-inline-action" @click="showFullBody = true">{{t('common.showFullContent')}}</button>
          </p>
        </template>
      </section>

      <section class="detail-section">
        <div class="detail-section__head"><h3 class="detail-section__title">{{t('rules.pvSectionSettings')}}</h3></div>
        <dl class="detail-grid">
          <dt>ID</dt>
          <dd><button type="button" class="detail-copy" :title="t('rules.copyId')" @click="$emit('clip-copy', view.id)"><code>{{view.id}}</code><i class="bi bi-copy" aria-hidden="true"></i></button></dd>
          <template v-if="view.targetHost && view.targetHost!=='default'"><dt>{{t('rules.pvTargetHost')}}</dt><dd><code>{{view.targetHost}}</code></dd></template>
          <dt>{{t('rules.pvPriority')}}</dt><dd class="detail-mono">{{view.priority || 0}}</dd>
          <dt>{{t('rules.pvProtected')}}</dt><dd>{{view.isProtected ? t('rules.pvYes') : t('rules.pvNo')}}</dd>
          <template v-if="retention != null">
            <dt>{{t('rules.pvDaysLeft')}}</dt>
            <dd><ui-badge :tone="retention <= 7 ? 'warning' : 'neutral'">{{t('rules.daysLeft', {days: retention})}}</ui-badge></dd>
          </template>
          <template v-if="tagEntries.length">
            <dt>{{t('rules.pvTags')}}</dt>
            <dd class="detail-chips"><ui-badge v-for="([k, v]) in visibleTagEntries" :key="k" tone="neutral">{{k}}={{v}}</ui-badge>
              <button v-if="tagEntries.length > DETAIL_LIST_PREVIEW.tags" type="button" class="detail-inline-action" :aria-expanded="showAllTags ? 'true' : 'false'"
                @click="showAllTags = !showAllTags">{{showAllTags ? t('common.showLess') : t('common.showAllCount', {count: tagEntries.length})}}</button></dd>
          </template>
          <template v-if="view.createdAt"><dt>{{t('rules.pvCreated')}}</dt><dd class="detail-mono">{{fmtTime(view.createdAt, false)}}</dd></template>
          <template v-if="view.updatedAt"><dt>{{t('rules.pvUpdated')}}</dt><dd><span class="detail-mono">{{fmtTime(view.updatedAt, false)}}</span> · {{view.updatedBy || t('rules.unknownOperator')}}</dd></template>
        </dl>
      </section>
    </ui-detail-drawer>
  `,
};
