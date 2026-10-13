/**
 * SseEventsEditor - 規則編輯器的 SSE 事件清單。
 *
 * 每個事件平常只佔一行（第幾秒送出、名稱、內容開頭、大小、跟前一個隔多久），點一下才展開編輯，
 * 一次只展開一個：打開時展開第一個，新增的事件自動展開。收起的事件只是文字，幾百個事件也不會
 * 同時有幾百個輸入框。延遲可用毫秒或秒輸入（存檔一律是毫秒），另有搜尋、延遲一次全改、
 * 整串貼上（JSON 或 text/event-stream 原文）與放大編輯單一事件。
 *
 * 事件物件就地修改（與原本的表格相同）；新增、刪除與整串替換交給上層。
 * 規則編輯器與回應管理共用：回應沒有循環設定（loopable=false），放大由視窗本身處理（expandable=false）。
 */
const SseEventsEditor = {
  inject: ['t'],
  props: {
    events: { type: Array, required: true },
    loopEnabled: { type: Boolean, default: false },
    sharedCount: { type: Number, default: 0 },
    expanded: { type: Boolean, default: false },
    error: { type: String, default: '' },
    loopable: { type: Boolean, default: true },
    expandable: { type: Boolean, default: true },
  },
  emits: ['add', 'remove', 'replace', 'update:loopEnabled', 'update:expanded'],
  data() {
    return {
      openRaw: null,
      query: '',
      units: new WeakMap(),
      drafts: new WeakMap(),
      bulkDelayOpen: false,
      bulkDelayValue: '',
      bulkDelayUnit: 'ms',
      bulkDelayError: '',
      focus: null,
      focusOwnsExpansion: false,
      bulkText: '',
      bulkError: '',
      rawOpen: false,
      dragIndex: null,
    };
  },
  created() {
    // 收起時顯示的內容開頭與大小：只在該事件的資料改變時重算
    this.rowCache = new WeakMap();
  },
  computed: {
    timeline() { return SseEvents.timeline(this.events); },
    searching() { return this.query.trim().length > 0; },
    rows() {
      const { rows } = this.timeline;
      // data 裡的物件讀出來是代理物件，比較前先取回原物件
      const openRaw = Vue.toRaw(this.openRaw);
      const list = [];
      this.events.forEach((event, index) => {
        const raw = Vue.toRaw(event);
        const info = this.describe(raw, event.data);
        if (this.searching && !this.rowMatches(info, event)) return;
        const row = rows[index];
        list.push({ event, raw, index, key: index, ...info, atMs: row.atMs, state: row.state,
          open: raw === openRaw, delayMs: Number.isFinite(event.delayMs) ? event.delayMs : 0,
          missingData: !event.data, delayInvalid: !SseEvents.delayValid(event.delayMs) });
      });
      return list;
    },
    totalLabel() { return SseEvents.formatDuration(this.timeline.totalMs, this.t); },
    endNotice() {
      const { endIndex } = this.timeline;
      if (endIndex < 0) return '';
      return this.t('sseEditor.endsAt', { index: endIndex + 1 });
    },
    eventsShape() { return { list: this.events, length: this.events.length }; },
    focusEvent() {
      if (this.focus?.kind !== 'event') return null;
      const target = Vue.toRaw(this.focus.raw);
      return this.events.find(event => Vue.toRaw(event) === target) || null;
    },
    focusIndex() { return this.focusEvent ? this.events.indexOf(this.focusEvent) : -1; },
    // 只在打開「原始內容」時才產生（幾百個事件時每次輸入都重算會拖慢打字）
    rawText() { return this.rawOpen ? SseEvents.rawStream(this.events, this.loopEnabled, this.t) : ''; },
    unitOptions() {
      return [{ value: 'ms', label: this.t('sseEditor.unitMs') }, { value: 's', label: this.t('sseEditor.unitSec') }];
    },
  },
  watch: {
    // 換了一條規則或整串替換：展開第一個事件、清掉搜尋；只是換了順序（同一批事件）則保持原狀。
    // 同一個陣列變長是「新增事件」：展開新的那個並把游標放進事件資料。
    eventsShape: {
      immediate: true,
      handler(shape, previous) {
        if (!previous || shape.list !== previous.list) {
          const before = previous ? new Set(previous.list.map(event => Vue.toRaw(event))) : null;
          const reordered = before && shape.length === previous.length
            && shape.list.every(event => before.has(Vue.toRaw(event)));
          if (reordered) return;
          this.query = '';
          this.bulkDelayOpen = false;
          this.openRaw = shape.length ? Vue.toRaw(shape.list[0]) : null;
          if (this.focus) this.closeFocus();
        } else if (shape.length > previous.length) {
          this.query = '';
          this.openRaw = Vue.toRaw(shape.list[shape.length - 1]);
          this.$nextTick(() => this.focusData(shape.length - 1));
        } else if (!shape.list.some(event => Vue.toRaw(event) === Vue.toRaw(this.openRaw))) {
          this.openRaw = null;
        }
      },
    },
    expanded(value) {
      // 上層收回（例如按 Esc）時，放大編輯也一起結束
      if (!value && this.focus) { this.focus = null; this.focusOwnsExpansion = false; }
    },
  },
  methods: {
    describe(raw, data) {
      const cached = this.rowCache.get(raw);
      if (cached && cached.data === data) return cached.info;
      const info = { previewText: SseEvents.previewLine(data), sizeLabel: SseEvents.formatBytes(SseEvents.byteLength(data)),
        lowerData: String(data ?? '').toLowerCase() };
      this.rowCache.set(raw, { data, info });
      return info;
    },
    rowMatches(info, event) {
      const needle = this.query.trim().toLowerCase();
      return info.lowerData.includes(needle) || SseEvents.matches({ data: '', event: event.event, id: event.id }, needle);
    },
    timeLabel(ms) { return ms == null ? '' : SseEvents.formatDuration(ms, this.t); },
    /**
     * 收起的事件內容沒變就不重畫：在幾百個事件的規則裡打字時，只有展開中的事件需要更新。
     * 展開中的事件每次都重畫（新物件永遠不相等）。
     */
    memoKey(row) {
      if (row.open) return [{}];
      const { event } = row;
      return [row.index, row.state, row.atMs, row.delayMs, row.delayInvalid, row.missingData, row.previewText,
        row.sizeLabel, event.event, event.type, this.dragIndex === row.index, this.t('sseEditor.total')];
    },
    typeHelp(type) {
      return this.t(type === 'error' ? 'modal.sseTypeErrorHelp' : type === 'abort' ? 'modal.sseTypeAbortHelp' : 'modal.sseTypeNormalHelp');
    },
    nameLabel(event) {
      if (event.type === 'error') return 'error';
      if (event.type === 'abort') return this.t('sseEditor.abortName');
      return event.event || 'message';
    },
    toggle(row) {
      if (!row.open && !this.units.has(row.raw)) this.units.set(row.raw, this.unitOf(row.event));
      this.openRaw = row.open ? null : row.raw;
      if (!row.open) this.$nextTick(() => this.focusData(row.index));
    },
    /** 打開時從內容開頭顯示，游標放在最前面；高度依內容（最多 8 行，超過在框內捲動）。 */
    focusData(index) {
      const field = this.$el?.querySelector(`[data-sse-data="${index}"]`);
      if (!field) return;
      this.fitData(field);
      field.focus({ preventScroll: true });
      field.setSelectionRange(0, 0);
      field.scrollTop = 0;
      field.closest('.sse-ev')?.scrollIntoView({ block: 'nearest' });
    },
    fitData(field) {
      const max = parseFloat(getComputedStyle(field).maxHeight) || 0;
      field.style.height = 'auto';
      const height = field.scrollHeight + 2;
      field.style.height = (max ? Math.min(height, max) : height) + 'px';
    },
    /** 收起的事件之間用 ↑ ↓ 移動焦點（展開中的事件沒有這顆按鈕，所以依按鈕本身的順序找）。 */
    onRowKeydown(event) {
      if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
      event.preventDefault();
      const targets = [...(this.$el?.querySelectorAll('[data-sse-row-target]') || [])];
      const next = targets[targets.indexOf(event.currentTarget) + (event.key === 'ArrowDown' ? 1 : -1)];
      if (next) next.focus();
    },
    /** 展開中的事件：Alt + ↑ ↓ 換到上一個／下一個（方向鍵本身留給輸入框）。 */
    onCardKeydown(event, position) {
      if (!event.altKey || (event.key !== 'ArrowDown' && event.key !== 'ArrowUp')) return;
      event.preventDefault();
      const next = this.rows[position + (event.key === 'ArrowDown' ? 1 : -1)];
      if (!next) return;
      this.openRaw = next.raw;
      this.$nextTick(() => this.focusData(next.index));
    },
    /** 存檔前檢查用：清掉搜尋、收起放大編輯，展開第 index 個事件並把游標放在要修正的欄位。 */
    reveal(index) {
      const event = this.events[index];
      if (!event) return;
      if (this.focus) this.closeFocus();
      this.query = '';
      const raw = Vue.toRaw(event);
      if (!this.units.has(raw)) this.units.set(raw, this.unitOf(event));
      this.openRaw = raw;
      this.$nextTick(() => {
        if (SseEvents.delayValid(event.delayMs)) { this.focusData(index); return; }
        const input = this.$el?.querySelector('.sse-ev.is-open .sse-ed-delay-input');
        input?.focus();
        input?.closest('.sse-ev')?.scrollIntoView({ block: 'nearest' });
      });
    },
    remove(row) {
      if (this.events.length <= 1) return;
      if (row.open) {
        const neighbour = this.events[row.index + 1] || this.events[row.index - 1];
        this.openRaw = neighbour ? Vue.toRaw(neighbour) : null;
      }
      this.$emit('remove', row.index);
    },
    // 延遲：每個事件自己的單位（毫秒／秒），存的永遠是毫秒
    unitOf(event) { return this.units.get(Vue.toRaw(event)) || SseEvents.unitFor(event.delayMs); },
    delayInput(event) {
      const raw = Vue.toRaw(event);
      if (this.drafts.has(raw)) return this.drafts.get(raw);
      return Number.isFinite(event.delayMs) ? SseEvents.toUnit(event.delayMs, this.unitOf(event)) : '';
    },
    /**
     * 打字時單位固定、輸入框保留原樣：不會因為打到 1000 就跳成「1 秒」，也不會把 "1." 改寫掉。
     * 離開輸入框時才換回存好的值。
     */
    onDelayInput(event, value) {
      const raw = Vue.toRaw(event);
      if (!this.units.has(raw)) this.units.set(raw, this.unitOf(event));
      const ms = SseEvents.fromUnit(value, this.units.get(raw));
      this.drafts.set(raw, value);
      event.delayMs = ms == null ? NaN : ms;
    },
    onDelayBlur(event) {
      const raw = Vue.toRaw(event);
      if (SseEvents.delayValid(event.delayMs)) this.drafts.delete(raw);
    },
    setUnit(event, unit) {
      const raw = Vue.toRaw(event);
      this.drafts.delete(raw);
      this.units.set(raw, unit);
    },
    delayError(event) {
      return SseEvents.delayValid(event.delayMs) ? ''
        : this.t('sseEditor.delayInvalid', { max: SseEvents.formatDuration(SseEvents.MAX_DELAY_MS, this.t) });
    },
    // 延遲一次全改
    openBulkDelay() {
      this.bulkDelayOpen = !this.bulkDelayOpen;
      this.bulkDelayError = '';
      if (this.bulkDelayOpen) this.$nextTick(() => this.$refs.bulkDelayInput?.focus());
    },
    applyBulkDelay() {
      const ms = SseEvents.fromUnit(this.bulkDelayValue, this.bulkDelayUnit);
      if (ms == null || !SseEvents.delayValid(ms)) {
        this.bulkDelayError = this.t('sseEditor.delayInvalid', { max: SseEvents.formatDuration(SseEvents.MAX_DELAY_MS, this.t) });
        return;
      }
      this.events.forEach(event => {
        const raw = Vue.toRaw(event);
        this.drafts.delete(raw);
        this.units.set(raw, this.bulkDelayUnit);
        event.delayMs = ms;
      });
      this.bulkDelayOpen = false;
    },
    // 放大編輯（單一事件）與整串貼上：都佔用整個編輯區
    openFocus(focus) {
      this.focusOwnsExpansion = !this.expanded;
      this.focus = focus;
      if (!this.expanded) this.$emit('update:expanded', true);
      // 游標直接放進編輯區：Esc 才會回到清單，而不是被外層當成「關閉視窗」
      this.$nextTick(() => this.$refs.focusCode?.cm?.focus());
    },
    openEventFocus(row) { this.openFocus({ kind: 'event', raw: row.raw }); },
    openBulk() {
      const invalid = this.events.findIndex(event => !SseEvents.delayValid(event.delayMs));
      if (invalid >= 0) {
        // 先修正延遲：否則整串編輯會把無效的延遲寫成 null，再套用就默默變成 0
        this.openRaw = Vue.toRaw(this.events[invalid]);
        this.query = '';
        this.$nextTick(() => this.$el?.querySelector('.sse-ev.is-open .sse-ed-delay-input')?.focus());
        return;
      }
      this.bulkText = serializeSseEvents(this.events);
      this.bulkError = '';
      this.openFocus({ kind: 'bulk' });
    },
    closeFocus() {
      const owned = this.focusOwnsExpansion;
      const closed = this.focus;
      this.focus = null;
      this.focusOwnsExpansion = false;
      if (owned && this.expanded) this.$emit('update:expanded', false);
      // 回到清單後游標放回原處：放大的事件回到它的資料欄，整串貼上回到那顆按鈕
      this.$nextTick(() => {
        if (this.focus) return;
        if (closed?.kind === 'event') {
          const index = this.events.findIndex(event => Vue.toRaw(event) === Vue.toRaw(closed.raw));
          if (index >= 0 && Vue.toRaw(this.openRaw) === Vue.toRaw(closed.raw)) { this.focusData(index); return; }
        }
        this.$el?.querySelector?.('.sse-ed-bulk-btn')?.focus();
      });
    },
    applyBulk() {
      const result = SseEvents.parseBulk(this.bulkText);
      if (result.error) { this.bulkError = this.t(result.error.key, result.error.params); return; }
      this.closeFocus();
      this.$emit('replace', result.events);
    },
    onFocusKeydown(event) {
      if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && this.focus?.kind === 'bulk') {
        // 不讓外層的「儲存並關閉」在套用前存檔（貼上的事件會遺失）
        event.preventDefault();
        event.stopPropagation();
        this.applyBulk();
        return;
      }
      if (event.key !== 'Escape') return;
      event.preventDefault();
      event.stopPropagation();
      this.closeFocus();
    },
    // 拖曳把手排序
    onDragStart(event, row) {
      this.dragIndex = row.index;
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', String(row.index));
      const item = event.target.closest('.sse-ev');
      if (item) event.dataTransfer.setDragImage(item, 16, 16);
    },
    onDrop(event, row) {
      // 只處理拖曳把手；拖曳文字（例如在事件資料裡）照瀏覽器原本的行為
      if (this.dragIndex == null) return;
      event.preventDefault();
      const from = this.dragIndex;
      this.dragIndex = null;
      if (from == null || from === row.index) return;
      const events = [...this.events];
      const [moved] = events.splice(from, 1);
      events.splice(row.index, 0, moved);
      this.$emit('replace', events);
    },
  },
  template: /* html */`
    <div class="sse-ed" :class="{'is-expanded': expanded, 'is-focused': !!focus}">
      <template v-if="!focus">
        <!-- 標題、摘要與工具固定在上方：清單很長時捲動也看得到 -->
        <div class="sse-ed-sticky">
        <div class="sse-ed-head">
          <span class="rule-sse-heading"><span class="response-content-label">{{t('modal.sseEditor')}}</span>
            <span v-if="sharedCount > 1" class="response-shared-warning" :title="t('modal.modifyAffectsAll')"><i class="bi bi-exclamation-triangle" aria-hidden="true"></i>{{t('modal.sharedWarning', {count: sharedCount})}}</span></span>
          <div class="sse-ed-summary">
            <span class="sse-ed-chip"><strong>{{events.length}}</strong>{{t('sseEditor.eventsUnit', {count: events.length})}}</span>
            <span class="sse-ed-chip">{{loopEnabled && timeline.endIndex < 0 ? t('sseEditor.perRound') : t('sseEditor.total')}}<strong>{{totalLabel}}</strong></span>
            <span v-if="endNotice" class="sse-ed-chip is-ends" :title="t('sseEditor.endsHelp')">{{endNotice}}</span>
            <div v-if="loopable" class="form-check form-switch rule-sse-loop">
              <input class="form-check-input" type="checkbox" id="sseLoopToggle" :checked="loopEnabled" @change="$emit('update:loopEnabled', $event.target.checked)">
              <label class="form-check-label" for="sseLoopToggle"><i class="bi bi-arrow-repeat" aria-hidden="true"></i>{{t('modal.sseLoopMode')}}</label>
            </div>
            <ui-button v-if="expandable" type="button" variant="quiet" size="compact" icon-only class="rule-response-expand" :aria-pressed="expanded ? 'true' : 'false'" :title="expanded ? t('modal.collapseContent') : t('modal.expandContent')" :aria-label="expanded ? t('modal.collapseContent') : t('modal.expandContent')" @click="$emit('update:expanded', !expanded)"><i class="bi" :class="expanded ? 'bi-arrows-angle-contract' : 'bi-arrows-angle-expand'" aria-hidden="true"></i></ui-button>
          </div>
        </div>
        <div class="sse-ed-tools">
          <label class="sse-ed-search">
            <i class="bi bi-search" aria-hidden="true"></i>
            <input type="search" class="form-control form-control-sm" v-model="query" :placeholder="t('sseEditor.searchPlaceholder')" :aria-label="t('sseEditor.searchPlaceholder')">
          </label>
          <span v-if="searching" class="sse-ed-match" aria-live="polite">{{t('sseEditor.matchCount', {count: rows.length, total: events.length})}}</span>
          <div class="sse-ed-tool-buttons">
            <ui-button type="button" variant="secondary" size="compact" class="sse-ed-add" @click="$emit('add')"><i class="bi bi-plus-lg" aria-hidden="true"></i>{{t('modal.addSseEvent')}}</ui-button>
            <ui-button type="button" variant="quiet" size="compact" :aria-expanded="bulkDelayOpen ? 'true' : 'false'" @click="openBulkDelay"><i class="bi bi-stopwatch" aria-hidden="true"></i>{{t('sseEditor.bulkDelay')}}</ui-button>
            <ui-button type="button" variant="quiet" size="compact" class="sse-ed-bulk-btn" @click="openBulk"><i class="bi bi-clipboard-plus" aria-hidden="true"></i>{{t('sseEditor.bulkEdit')}}</ui-button>
          </div>
        </div>
        <form v-if="bulkDelayOpen" class="sse-ed-bulkdelay" @submit.prevent="applyBulkDelay" @keydown.esc.stop.prevent="bulkDelayOpen = false">
          <span class="sse-ed-bulkdelay-label">{{t('sseEditor.bulkDelayLabel', {count: events.length})}}</span>
          <input ref="bulkDelayInput" type="number" min="0" step="any" class="form-control form-control-sm sse-ed-delay-input" v-model="bulkDelayValue" :aria-label="t('sseEditor.bulkDelay')" :aria-invalid="bulkDelayError ? 'true' : 'false'">
          <ui-segmented-control size="compact" name="sse-bulk-unit" :aria-label="t('sseEditor.unit')" :options="unitOptions" :model-value="bulkDelayUnit" @update:model-value="bulkDelayUnit = $event"></ui-segmented-control>
          <ui-button type="submit" variant="secondary" size="compact">{{t('sseEditor.apply')}}</ui-button>
          <ui-button type="button" variant="quiet" size="compact" @click="bulkDelayOpen = false">{{t('modal.cancel')}}</ui-button>
          <span v-if="bulkDelayError" class="sse-ed-inline-error" role="alert">{{bulkDelayError}}</span>
        </form>
        </div>
        <ol class="sse-ed-list" :aria-label="t('modal.sseEventsTableLabel')">
          <li v-for="(row, position) in rows" :key="row.key" class="sse-ev"
              :class="['is-' + row.state, 'type-' + (row.event.type || 'normal'), {'is-open': row.open, 'is-dragging': dragIndex === row.index}]"
              v-memo="memoKey(row)"
              @dragover="dragIndex != null && $event.preventDefault()" @drop="onDrop($event, row)">
            <span class="sse-ev-time" :title="row.state === 'skipped' ? t('sseEditor.skippedHelp') : ''">
              <span class="sse-ev-at">{{row.state === 'skipped' ? t('sseEditor.notSent') : timeLabel(row.atMs)}}</span>
              <span v-if="row.open && row.state !== 'skipped'" class="sse-ev-gap">+{{timeLabel(row.delayMs)}}</span>
            </span>
            <span class="sse-ev-dot" aria-hidden="true"></span>
            <div v-if="!row.open" class="sse-ev-line">
              <span class="sse-ev-grip" draggable="true" :title="t('sseEditor.dragToReorder')" @dragstart="onDragStart($event, row)" @dragend="dragIndex = null"><i class="bi bi-grip-vertical" aria-hidden="true"></i></span>
              <button type="button" class="sse-ev-summary" data-sse-row-target @click="toggle(row)" @keydown="onRowKeydown"
                  :aria-label="t('sseEditor.openEvent', {index: row.index + 1})">
                <span class="sse-ev-index">{{row.index + 1}}</span>
                <span class="sse-ev-name" :class="{'is-default': !row.event.event && row.event.type === 'normal'}">{{nameLabel(row.event)}}</span>
                <span v-if="row.missingData" class="sse-ev-preview is-missing">{{t('sseEditor.noData')}}</span>
                <span v-else class="sse-ev-preview">{{row.previewText}}</span>
                <span class="sse-ev-size">{{row.sizeLabel}}</span>
                <span class="sse-ev-delay" :class="{'is-invalid': row.delayInvalid}">+{{row.delayInvalid ? '?' : timeLabel(row.delayMs)}}</span>
              </button>
            </div>
            <div v-else class="sse-ev-card" @keydown="onCardKeydown($event, position)">
              <div class="sse-ev-fields">
                <span class="sse-ev-grip" draggable="true" :title="t('sseEditor.dragToReorder')" @dragstart="onDragStart($event, row)" @dragend="dragIndex = null"><i class="bi bi-grip-vertical" aria-hidden="true"></i></span>
                <span class="sse-ev-index">{{row.index + 1}}</span>
                <select class="form-select form-select-sm sse-ev-type" v-model="row.event.type" :title="typeHelp(row.event.type)" :aria-label="t('modal.sseFieldLabel', {index: row.index + 1, field: t('modal.sseEventType')})">
                  <option value="normal">{{t('modal.sseTypeNormal')}}</option>
                  <option value="error">{{t('modal.sseTypeError')}}</option>
                  <option value="abort">{{t('modal.sseTypeAbort')}}</option>
                </select>
                <input class="form-control form-control-sm sse-ev-event" v-if="row.event.type === 'normal'" v-model="row.event.event" :placeholder="t('sseEditor.namePlaceholder')" :aria-label="t('modal.sseFieldLabel', {index: row.index + 1, field: t('modal.sseEventName')})">
                <span v-else class="sse-ev-fixed" :title="typeHelp(row.event.type)">{{nameLabel(row.event)}}</span>
                <span class="sse-ev-delay-field" :class="{'is-invalid': row.delayInvalid}">
                  <input type="number" min="0" step="any" class="form-control form-control-sm sse-ed-delay-input" :value="delayInput(row.event)" @input="onDelayInput(row.event, $event.target.value)" @blur="onDelayBlur(row.event)" :aria-invalid="row.delayInvalid ? 'true' : 'false'" :aria-label="t('modal.sseFieldLabel', {index: row.index + 1, field: t('modal.sseEventDelay')})">
                  <ui-segmented-control size="compact" :name="'sse-unit-' + row.index" :aria-label="t('sseEditor.unit')" :options="unitOptions" :model-value="unitOf(row.event)" @update:model-value="setUnit(row.event, $event)"></ui-segmented-control>
                </span>
                <span class="sse-ev-card-actions">
                  <ui-button type="button" variant="quiet" size="compact" icon-only @click="toggle(row)" :title="t('sseEditor.collapseEvent')" :aria-label="t('sseEditor.collapseEvent')"><i class="bi bi-chevron-up" aria-hidden="true"></i></ui-button>
                  <ui-button type="button" variant="quiet" size="compact" icon-only class="sse-ev-remove" @click="remove(row)" :disabled="events.length <= 1" :title="t('modal.deleteSseEventAt', {index: row.index + 1})" :aria-label="t('modal.deleteSseEventAt', {index: row.index + 1})"><i class="bi bi-trash" aria-hidden="true"></i></ui-button>
                </span>
              </div>
              <textarea class="form-control form-control-sm sse-ev-data" :data-sse-data="row.index" v-model="row.event.data" spellcheck="false" @input="fitData($event.target)"
                :class="{'is-invalid': row.missingData}" :placeholder="t('modal.sseEventDataPlaceholder')"
                :aria-label="t('modal.sseFieldLabel', {index: row.index + 1, field: t('modal.sseEventData')})"></textarea>
              <div class="sse-ev-foot">
                <input v-if="row.event.type !== 'abort'" class="form-control form-control-sm sse-ev-id" v-model="row.event.id" :placeholder="t('sseEditor.idPlaceholder')" :title="t('modal.sseEventId')" :aria-label="t('modal.sseFieldLabel', {index: row.index + 1, field: t('modal.sseEventId')})">
                <span>{{row.sizeLabel}}</span>
                <span v-if="row.delayInvalid" class="sse-ed-inline-error">{{delayError(row.event)}}</span>
                <span v-else-if="row.missingData" class="sse-ed-inline-error">{{t('modal.sseEventDataRequired')}}</span>
                <span v-else-if="row.state === 'skipped'" class="sse-ed-note">{{t('sseEditor.skippedHelp')}}</span>
                <span class="sse-ev-foot-hint">{{t('sseEditor.switchHint')}}</span>
                <ui-button type="button" variant="secondary" size="compact" class="sse-ev-zoom" @click="openEventFocus(row)"><i class="bi bi-arrows-angle-expand" aria-hidden="true"></i>{{t('sseEditor.zoom')}}</ui-button>
              </div>
            </div>
          </li>
        </ol>
        <div v-if="searching && !rows.length" class="sse-ed-empty">{{t('sseEditor.noMatch')}}</div>
        <details class="sse-ed-raw" @toggle="rawOpen = $event.target.open">
          <summary>{{t('sseEditor.rawPreview')}}</summary>
          <pre v-if="rawOpen">{{rawText}}</pre>
        </details>
        <div v-if="error" class="invalid-feedback rule-sse-error">{{error}}</div>
      </template>
      <div v-else class="sse-ed-focus" @keydown="onFocusKeydown">
        <div class="sse-ed-focus-head">
          <strong v-if="focus.kind === 'event'">{{t('sseEditor.zoomTitle', {index: focusIndex + 1})}}</strong>
          <strong v-else>{{t('sseEditor.bulkTitle')}}</strong>
          <span class="sse-ed-note">{{focus.kind === 'event' ? t('sseEditor.zoomHelp') : t('sseEditor.bulkHelp')}}</span>
          <span class="sse-ed-focus-actions">
            <template v-if="focus.kind === 'bulk'">
              <ui-button type="button" variant="quiet" size="compact" @click="closeFocus">{{t('modal.cancel')}}</ui-button>
              <ui-button type="button" variant="primary" size="compact" @click="applyBulk"><i class="bi bi-check-lg" aria-hidden="true"></i>{{t('sseEditor.bulkApply')}}</ui-button>
            </template>
            <ui-button v-else type="button" variant="secondary" size="compact" @click="closeFocus"><i class="bi bi-arrows-angle-contract" aria-hidden="true"></i>{{t('sseEditor.zoomDone')}}</ui-button>
          </span>
        </div>
        <div v-if="bulkError" class="sse-ed-focus-error" role="alert"><i class="bi bi-exclamation-circle" aria-hidden="true"></i>{{bulkError}}</div>
        <ui-code-viewer v-if="focus.kind === 'event' && focusEvent" ref="focusCode" class="sse-ed-focus-code" fill editable
          :label="t('modal.sseFieldLabel', {index: focusIndex + 1, field: t('modal.sseEventData')})"
          :value="focusEvent.data || ''" @update:value="focusEvent.data = $event"></ui-code-viewer>
        <ui-code-viewer v-else-if="focus.kind === 'bulk'" ref="focusCode" class="sse-ed-focus-code" fill editable
          :label="t('sseEditor.bulkTitle')" :placeholder="t('sseEditor.bulkPlaceholder')"
          :value="bulkText" @update:value="bulkText = $event; bulkError = ''"></ui-code-viewer>
      </div>
    </div>
  `,
};
