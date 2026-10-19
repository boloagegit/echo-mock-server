/**
 * SseEventsEditor - Rules Editor's SSE event listing.
 *
 * Each event usually only occupies one line (seconds to send, name, content start, size, how long since the previous one), click to expand the edit,
 * Only expand one at a time: expand the first one when opening, and newly added events will be expanded automatically. The collapsed event is just text, hundreds of events will not
 * There are hundreds of input boxes at the same time. The delay can be entered in milliseconds or seconds (saves are always in milliseconds). There are also search, delay, and full change at once.
 * Paste the entire string (JSON or text/event-stream original text) and zoom in to edit a single event.
 *
 * The event object is modified in place (same as the original table); addition, deletion and whole string replacement are handed over to the upper layer.
 * The rule editor is shared with response management: responses have no loop settings (loopable=false), and magnification is handled by the window itself (expandable=false).
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
      bulkOriginal: '',
      bulkError: '',
      rawOpen: false,
      dragIndex: null,
    };
  },
  created() {
    // The beginning and size of the content displayed when collapsed: only recalculated when the data of the event changes.
    this.rowCache = new WeakMap();
  },
  computed: {
    timeline() { return SseEvents.timeline(this.events); },
    searching() { return this.query.trim().length > 0; },
    rows() {
      const { rows } = this.timeline;
      // The object in data is read as a proxy object. The original object must be retrieved before comparison.
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
    /** "Full string pasting" is on but not applied yet: the outer save button must be pressable (applied or blocked by commitPending before saving). */
    bulkPending() { return this.focus?.kind === 'bulk'; },
    // Only occurs when "original content" is opened (recalculating every input when hundreds of events will slow down typing)
    rawText() { return this.rawOpen ? SseEvents.rawStream(this.events, this.loopEnabled, this.t) : ''; },
    unitOptions() {
      return [{ value: 'ms', label: this.t('sseEditor.unitMs') }, { value: 's', label: this.t('sseEditor.unitSec') }];
    },
  },
  watch: {
    // Changed a rule or replaced the whole string: expand the first event and clear the search; just change the order (the same batch of events) and keep it as it is.
    // The same array variable is "new event": expand the new one and put the cursor into the event data.
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
      // When the upper layer is retracted (for example, pressing Esc), the enlarged editing is also ended.
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
     * Collapsed events will not be redrawn if their content remains unchanged: When typing in rules for hundreds of events, only the expanding events need to be updated.
     * Expanding events are redrawn every time (new objects are never equal).
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
    /** When opened, display from the beginning of the content, with the cursor at the front; height according to the content (up to 8 lines, more than scrolling in the box). */
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
    /** Use ↑ ↓ to move focus between collapsed events (expanding events do not have this button, so search in the order of the buttons themselves). */
    onRowKeydown(event) {
      if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
      event.preventDefault();
      const targets = [...(this.$el?.querySelectorAll('[data-sse-row-target]') || [])];
      const next = targets[targets.indexOf(event.currentTarget) + (event.key === 'ArrowDown' ? 1 : -1)];
      if (next) next.focus();
    },
    /** Expanding event: Alt + ↑ ↓ changes to the previous/next one (the arrow keys themselves are reserved for the input box). */
    onCardKeydown(event, position) {
      if (!event.altKey || (event.key !== 'ArrowDown' && event.key !== 'ArrowUp')) return;
      event.preventDefault();
      const next = this.rows[position + (event.key === 'ArrowDown' ? 1 : -1)];
      if (!next) return;
      this.openRaw = next.raw;
      this.$nextTick(() => this.focusData(next.index));
    },
    /** To check before saving: clear the search, close the zoom edit, expand the index event and place the cursor in the field to be corrected. */
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
    // Delay: Each event has its own unit (milliseconds/second), and milliseconds are always stored.
    unitOf(event) { return this.units.get(Vue.toRaw(event)) || SseEvents.unitFor(event.delayMs); },
    delayInput(event) {
      const raw = Vue.toRaw(event);
      if (this.drafts.has(raw)) return this.drafts.get(raw);
      return Number.isFinite(event.delayMs) ? SseEvents.toUnit(event.delayMs, this.unitOf(event)) : '';
    },
    /**
     * The unit is fixed when typing, and the input box remains as it is: it will not jump to "1 second" just because it hits 1000, nor will "1." be rewritten.
     * Only change the saved value when leaving the input box.
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
    // Delay a full change
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
    // Zoom editing (single event) and entire string pasting: both occupy the entire editing area
    openFocus(focus) {
      this.focusOwnsExpansion = !this.expanded;
      this.focus = focus;
      if (!this.expanded) this.$emit('update:expanded', true);
      // Only when the cursor is placed directly into the editing area: Esc will it return to the list instead of being treated as a "close window" by the outer layer.
      this.$nextTick(() => this.$refs.focusCode?.cm?.focus());
    },
    openEventFocus(row) { this.openFocus({ kind: 'event', raw: row.raw }); },
    openBulk() {
      const invalid = this.events.findIndex(event => !SseEvents.delayValid(event.delayMs));
      if (invalid >= 0) {
        // Correct the delay first: otherwise the whole series of editors will write the invalid delay as null, and then it will become 0 silently.
        this.openRaw = Vue.toRaw(this.events[invalid]);
        this.query = '';
        this.$nextTick(() => this.$el?.querySelector('.sse-ev.is-open .sse-ed-delay-input')?.focus());
        return;
      }
      this.bulkText = serializeSseEvents(this.events);
      this.bulkOriginal = this.bulkText;
      this.bulkError = '';
      this.openFocus({ kind: 'bulk' });
    },
    closeFocus() {
      const owned = this.focusOwnsExpansion;
      const closed = this.focus;
      this.focus = null;
      this.focusOwnsExpansion = false;
      if (owned && this.expanded) this.$emit('update:expanded', false);
      // After returning to the list, the cursor is returned to its original position: the zoomed event is returned to its data bar, and the entire string is pasted back to the button
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
      // Just turn it off without changing it, without replacing it with a new event object.
      if (this.bulkText === this.bulkOriginal) { this.closeFocus(); return true; }
      const result = SseEvents.parseBulk(this.bulkText);
      if (result.error) { this.bulkError = this.t(result.error.key, result.error.params); return false; }
      this.closeFocus();
      this.$emit('replace', result.events);
      return true;
    },
    /**
     * Call before outer save: Apply first when "Full string paste" is still open, otherwise the old event will be saved and the pasted content will disappear when the window is closed.
     * On invalid input, stay in place, show the error, and return false without saving.
     */
    commitPending() {
      if (this.focus?.kind !== 'bulk') return true;
      if (this.applyBulk()) return true;
      this.$nextTick(() => this.$refs.focusCode?.cm?.focus());
      return false;
    },
    onFocusKeydown(event) {
      if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && this.focus?.kind === 'bulk') {
        // Prevent the outer Save and Close action from saving before pending paste is applied, which would lose pasted events.
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
    // Drag handle sorting
    onDragStart(event, row) {
      this.dragIndex = row.index;
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', String(row.index));
      const item = event.target.closest('.sse-ev');
      if (item) event.dataTransfer.setDragImage(item, 16, 16);
    },
    onDrop(event, row) {
      // Only handle drag handles; dragging text (e.g. in event data) behaves as native browser behavior
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
        <!-- The title, abstract and tools are fixed at the top: even if the list is very long, it can be seen when scrolling -->
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
