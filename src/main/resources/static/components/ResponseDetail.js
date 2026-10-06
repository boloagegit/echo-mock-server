/**
 * ResponseDetail - 回應詳情抽屜
 *
 * 顯示引用此回應的規則（可直接跳到規則）、回應內容與保留資訊。
 * 版面與 RuleDetail 相同：上方常用動作，其餘收在「⋯」。
 */
const ResponseDetail = {
  inject: ['t'],
  mixins: [heldDetailMixin(function () {
    return { open: this.open, key: this.response?.id, ready: !!this.detail || this.error, value: { response: this.response, detail: this.detail } };
  })],
  props: {
    open: Boolean,
    response: { type: Object, default: null },
    detail: { type: Object, default: null },
    loading: Boolean,
    error: Boolean,
    isLoggedIn: Boolean,
    status: Object,
    hasPrev: Boolean,
    hasNext: Boolean,
  },
  emits: ['close', 'prev', 'next', 'retry', 'edit', 'menu', 'go-to-rule', 'clip-copy'],
  data() {
    return { showFullBody: false, bodyExpanded: false, showAllRules: false };
  },
  watch: {
    'view.id'() {
      this.showFullBody = false;
      this.bodyExpanded = false;
      this.showAllRules = false;
    },
  },
  computed: {
    DETAIL_LIST_PREVIEW: () => DETAIL_LIST_PREVIEW,
    BODY_PREVIEW_CHARS: () => BODY_PREVIEW_CHARS,
    shownDetail() {
      return this.held.value.detail;
    },
    view() {
      return { ...(this.held.value.response || {}), ...(this.shownDetail || {}) };
    },
    isSse() {
      return this.view.contentType === 'SSE';
    },
    retention() {
      if (this.view.usageCount) return null;
      return daysLeft(this.view.updatedAt, this.view.extendedAt, this.status?.responseRetentionDays);
    },
    bodyFull() {
      return formatBodyForReading(this.shownDetail?.body || '');
    },
    bodyTruncated() {
      return !this.showFullBody && this.bodyFull.length > BODY_PREVIEW_CHARS;
    },
    formattedBody() {
      return this.bodyTruncated ? this.bodyFull.slice(0, BODY_PREVIEW_CHARS) : this.bodyFull;
    },
    linkedRules() {
      return this.shownDetail?.rules || [];
    },
    visibleLinkedRules() {
      return this.showAllRules ? this.linkedRules : this.linkedRules.slice(0, DETAIL_LIST_PREVIEW.links);
    },
    menuItems() {
      const t = this.t;
      const items = [];
      if (this.retention != null && this.isLoggedIn) {
        items.push({ key: 'extend', label: t('responses.clickExtend'), icon: 'bi-calendar-plus' });
      }
      items.push({ key: 'delete', label: t('responses.delete'), icon: 'bi-trash', danger: true, dividerBefore: items.length > 0, disabled: !this.isLoggedIn });
      return items;
    },
  },
  methods: {
    fmtTime, fmtSize, shortId,
  },
  template: /* html */`
    <ui-detail-drawer :open="held.open" :title="view.description || t('responses.noDescription')" :subtitle="view.id != null ? '#' + view.id : ''"
      :loading="held.waiting" :error="error && !shownDetail" :stale="held.stale" :has-prev="hasPrev" :has-next="hasNext"
      class="response-detail" @close="$emit('close')" @prev="$emit('prev')" @next="$emit('next')" @retry="$emit('retry')">
      <template #meta>
        <ui-badge tone="neutral">{{isSse ? 'SSE' : t('responses.typeGeneral')}}</ui-badge>
        <span class="detail-mono">{{fmtSize(view.bodySize)}}</span>
        <ui-status :tone="view.usageCount ? 'success' : 'neutral'">{{view.usageCount ? t('responses.usageCount', {count: view.usageCount}) : t('responses.notUsed')}}</ui-status>
      </template>
      <template #actions>
        <ui-button type="button" variant="primary" size="compact" :disabled="!isLoggedIn" @click="$emit('edit', view)"><i class="bi bi-pencil" aria-hidden="true"></i>{{t('responses.edit')}}</ui-button>
        <ui-button type="button" variant="secondary" size="compact" @click="$emit('clip-copy', String(view.id))"><i class="bi bi-copy" aria-hidden="true"></i>{{t('rules.copyId')}}</ui-button>
        <ui-row-menu :items="menuItems" :label="t('common.moreActions')" @select="$emit('menu', $event, view)"></ui-row-menu>
      </template>

      <section class="detail-section">
        <div class="detail-section__head">
          <h3 class="detail-section__title">{{t('responses.linkedRulesTitle')}}</h3>
          <span class="detail-section__tools detail-mono">{{(shownDetail?.rules || []).length}}</span>
        </div>
        <p v-if="shownDetail && !shownDetail.rules.length" class="detail-empty">{{view.usageCount ? t('responses.noVisibleLinkedRules') : t('responses.notUsed')}}</p>
        <ul v-else-if="shownDetail" class="detail-links">
          <li v-for="rule in visibleLinkedRules" :key="rule.id">
            <button type="button" class="detail-link" @click="$emit('go-to-rule', rule.id)">
              <span class="rule-method" :data-method="rule.protocol==='HTTP' ? (rule.method || 'GET') : null" :data-protocol="rule.protocol">{{rule.protocol==='HTTP' ? (rule.method || 'GET') : 'JMS'}}</span>
              <code class="detail-link__path">{{rule.matchKey}}</code>
              <span v-if="rule.description" class="detail-link__desc">{{rule.description}}</span>
              <i class="bi bi-arrow-right detail-link__go" aria-hidden="true"></i>
            </button>
          </li>
        </ul>
        <button v-if="linkedRules.length > DETAIL_LIST_PREVIEW.links" type="button" class="detail-inline-action"
          :aria-expanded="showAllRules ? 'true' : 'false'" @click="showAllRules = !showAllRules">
          {{showAllRules ? t('common.showLess') : t('common.showAllCount', {count: linkedRules.length})}}</button>
      </section>

      <section class="detail-section">
        <div class="detail-section__head">
          <h3 class="detail-section__title">{{t('rules.pvResponseContent')}}</h3>
          <div v-if="formattedBody" class="detail-section__tools">
            <ui-button type="button" variant="quiet" size="compact" icon-only :title="bodyExpanded ? t('common.collapseBlock') : t('common.expandBlock')"
              :aria-label="bodyExpanded ? t('common.collapseBlock') : t('common.expandBlock')" :aria-pressed="bodyExpanded ? 'true' : 'false'"
              @click="bodyExpanded = !bodyExpanded"><i class="bi" :class="bodyExpanded ? 'bi-arrows-angle-contract' : 'bi-arrows-angle-expand'" aria-hidden="true"></i></ui-button>
            <ui-button type="button" variant="quiet" size="compact" icon-only :title="t('rules.copyFullContent')" :aria-label="t('rules.copyFullContent')"
              @click="$emit('clip-copy', shownDetail.body)"><i class="bi bi-clipboard" aria-hidden="true"></i></ui-button>
          </div>
        </div>
        <p v-if="shownDetail && !formattedBody" class="detail-empty">{{t('rules.empty')}}</p>
        <pre v-else-if="formattedBody" class="detail-code" :class="{'is-expanded': bodyExpanded}">{{formattedBody}}</pre>
        <p v-if="bodyTruncated" class="detail-truncated">
          <span>{{t('common.previewTruncated', {shown: fmtSize(BODY_PREVIEW_CHARS), total: fmtSize(bodyFull.length)})}}</span>
          <button type="button" class="detail-inline-action" @click="showFullBody = true">{{t('common.showFullContent')}}</button>
        </p>
      </section>

      <section class="detail-section">
        <div class="detail-section__head"><h3 class="detail-section__title">{{t('responses.detailInfo')}}</h3></div>
        <dl class="detail-grid">
          <dt>ID</dt><dd class="detail-mono">#{{view.id}}</dd>
          <template v-if="view.createdAt"><dt>{{t('responses.thCreatedAt')}}</dt><dd class="detail-mono">{{fmtTime(view.createdAt, false)}}</dd></template>
          <template v-if="view.updatedAt"><dt>{{t('responses.thUpdatedAt')}}</dt><dd class="detail-mono">{{fmtTime(view.updatedAt, false)}}</dd></template>
          <template v-if="retention != null">
            <dt>{{t('rules.pvDaysLeft')}}</dt>
            <dd><ui-badge :tone="retention <= 7 ? 'warning' : 'neutral'">{{t('responses.orphanDaysLeft', {days: retention})}}</ui-badge></dd>
          </template>
        </dl>
      </section>
    </ui-detail-drawer>
  `,
};
