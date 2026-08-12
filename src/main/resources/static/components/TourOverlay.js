/**
 * TourOverlay - 規則編輯器導覽層
 *
 * 僅負責導覽顯示與鍵盤操作；步驟定位仍由 app.js 管理。
 */
const TourOverlay = {
  props: {
    active: Boolean,
    step: Number,
    steps: Array,
    highlightStyle: Object,
    tooltipStyle: Object
  },
  emits: ['prev', 'next', 'skip'],
  inject: ['t'],
  mounted() {
    this.previousFocus = document.activeElement;
    this.inertSiblings = makeOverlaySiblingsInert(this.$el);
    this._onKeydown = event => this.handleKeydown(event);
    document.addEventListener('keydown', this._onKeydown);
    this.focusTooltip();
  },
  beforeUnmount() {
    document.removeEventListener('keydown', this._onKeydown);
    restoreOverlaySiblings(this.inertSiblings);
    this.inertSiblings = [];
    this.previousFocus?.focus?.();
  },
  watch: {
    step() { this.focusTooltip(); }
  },
  methods: {
    focusTooltip() {
      this.$nextTick(() => this.$refs.tooltip?.focus());
    },
    handleKeydown(event) {
      if (!this.active) { return; }
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        this.$emit('skip');
        return;
      }
      if (event.key === 'ArrowLeft' && this.step > 0) {
        event.preventDefault();
        this.$emit('prev');
        return;
      }
      if (event.key === 'ArrowRight') {
        event.preventDefault();
        this.$emit('next');
        return;
      }
      if (event.key !== 'Tab') { return; }
      const focusable = getDialogFocusable(this.$refs.tooltip);
      if (!focusable.length) { return; }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
  },
  template: /* html */`
    <div v-if="active" class="tour-overlay">
      <div class="tour-highlight" :style="highlightStyle" aria-hidden="true"></div>
      <section ref="tooltip" class="tour-tooltip" :style="tooltipStyle" role="dialog" aria-modal="true" aria-labelledby="tourStepTitle" aria-describedby="tourStepBody" tabindex="-1">
        <div class="tour-tooltip-title" id="tourStepTitle">
          <i class="bi bi-lightbulb" aria-hidden="true"></i>
          <span>{{steps[step]?.title}}</span>
        </div>
        <div class="tour-tooltip-body" id="tourStepBody">{{steps[step]?.body}}</div>
        <div class="tour-tooltip-footer">
          <span class="tour-tooltip-steps" :aria-label="t('tour.progress', {current:step+1, total:steps.length})">{{step + 1}} / {{steps.length}}</span>
          <div class="tour-tooltip-actions">
            <button v-if="step > 0" type="button" class="btn btn-sm btn-secondary" @click="$emit('prev')"><i class="bi bi-arrow-left" aria-hidden="true"></i>{{t('tour.prev')}}</button>
            <button type="button" class="btn btn-sm btn-secondary" @click="$emit('skip')">{{t('tour.skip')}}</button>
            <button type="button" class="btn btn-sm btn-primary" @click="$emit('next')">{{step < steps.length - 1 ? t('tour.next') : t('tour.finish')}}<i class="bi" :class="step < steps.length - 1?'bi-arrow-right':'bi-check2'" aria-hidden="true"></i></button>
          </div>
        </div>
      </section>
    </div>
  `
};
