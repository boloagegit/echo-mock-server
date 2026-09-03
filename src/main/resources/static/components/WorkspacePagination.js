/**
 * WorkspacePagination - shared footer layout and paging controls for workspace tables.
 */
const WorkspacePagination = {
  props: {
    page: { type: Number, default: 1 },
    totalPages: { type: Number, default: 1 },
    pageSize: { type: Number, default: 20 },
    paginationLabel: { type: String, default: '' },
    pageStatusLabel: { type: String, default: '' },
    pageSizeLabel: { type: String, default: '' },
    firstPageLabel: { type: String, default: '' },
    previousPageLabel: { type: String, default: '' },
    nextPageLabel: { type: String, default: '' },
    lastPageLabel: { type: String, default: '' },
    scrollHintLabel: { type: String, default: '' },
    scrollRegionLabel: { type: String, default: '' },
  },
  emits: ['update:page', 'update:page-size'],
  data() {
    return {
      showScrollHint: false,
      scrollBody: null,
      scrollResizeObserver: null,
      scrollMutationObserver: null,
    };
  },
  computed: {
    lastPage() { return Math.max(1, Number(this.totalPages) || 1); },
    currentPage() { return Math.min(this.lastPage, Math.max(1, Number(this.page) || 1)); },
  },
  mounted() {
    this.$nextTick(this.bindScrollBody);
  },
  updated() {
    this.$nextTick(this.bindScrollBody);
  },
  beforeUnmount() {
    this.unbindScrollBody();
  },
  methods: {
    findScrollBody() {
      const card = this.$el.parentElement;
      if (!card) return null;
      const bodies = Array.from(card.querySelectorAll(':scope > .card-table-body'));
      return bodies.find(body => body.querySelector('table')) || bodies.at(-1) || null;
    },
    bindScrollBody() {
      const body = this.findScrollBody();
      if (body === this.scrollBody) {
        this.syncScrollHint();
        return;
      }
      this.unbindScrollBody();
      this.scrollBody = body;
      if (!body) return;
      body.addEventListener('scroll', this.syncScrollHint, { passive: true });
      this.scrollResizeObserver = new ResizeObserver(this.syncScrollHint);
      this.scrollResizeObserver.observe(body);
      this.scrollMutationObserver = new MutationObserver(this.syncScrollHint);
      this.scrollMutationObserver.observe(body, { childList: true, subtree: true });
      this.syncScrollHint();
    },
    unbindScrollBody() {
      if (this.scrollBody) {
        this.scrollBody.removeEventListener('scroll', this.syncScrollHint);
        this.clearScrollAccessibility(this.scrollBody);
      }
      this.scrollResizeObserver?.disconnect();
      this.scrollMutationObserver?.disconnect();
      this.scrollBody = null;
      this.scrollResizeObserver = null;
      this.scrollMutationObserver = null;
      this.showScrollHint = false;
    },
    syncScrollHint() {
      const body = this.scrollBody;
      if (!body) {
        this.showScrollHint = false;
        return;
      }
      const remaining = body.scrollHeight - body.clientHeight - body.scrollTop;
      const overflowing = body.scrollHeight > body.clientHeight + 1;
      if (overflowing && this.scrollRegionLabel) {
        body.tabIndex = 0;
        body.setAttribute('role', 'region');
        body.setAttribute('aria-label', this.scrollRegionLabel);
        body.dataset.workspaceScrollRegion = 'true';
      } else {
        this.clearScrollAccessibility(body);
      }
      this.showScrollHint = Boolean(this.scrollHintLabel) && overflowing && remaining > 1;
    },
    clearScrollAccessibility(body) {
      if (body?.dataset.workspaceScrollRegion !== 'true') return;
      body.removeAttribute('tabindex');
      body.removeAttribute('role');
      body.removeAttribute('aria-label');
      delete body.dataset.workspaceScrollRegion;
    },
    scrollForward() {
      const body = this.scrollBody;
      if (!body) return;
      body.focus({ preventScroll: true });
      body.scrollBy({ top: Math.max(120, body.clientHeight * 0.75), behavior: 'smooth' });
    },
  },
  template: /* html */`
    <div class="card-table-footer workspace-pagination">
      <div class="workspace-pagination-summary">
        <slot name="summary"></slot>
        <button v-if="showScrollHint" type="button" class="workspace-scroll-hint" @click="scrollForward" :title="scrollHintLabel">
          <i class="bi bi-chevron-down" aria-hidden="true"></i><span>{{scrollHintLabel}}</span>
        </button>
      </div>
      <div class="pagination-controls" role="navigation" :aria-label="paginationLabel">
        <button type="button" class="btn btn-sm btn-secondary" @click="$emit('update:page', 1)" :disabled="currentPage===1" :aria-label="firstPageLabel"><i class="bi bi-chevron-double-left" aria-hidden="true"></i></button>
        <button type="button" class="btn btn-sm btn-secondary" @click="$emit('update:page', currentPage-1)" :disabled="currentPage===1" :aria-label="previousPageLabel"><i class="bi bi-chevron-left" aria-hidden="true"></i></button>
        <span class="tabular-nums">{{pageStatusLabel || (currentPage + ' / ' + lastPage)}}</span>
        <button type="button" class="btn btn-sm btn-secondary" @click="$emit('update:page', currentPage+1)" :disabled="currentPage>=lastPage" :aria-label="nextPageLabel"><i class="bi bi-chevron-right" aria-hidden="true"></i></button>
        <button type="button" class="btn btn-sm btn-secondary" @click="$emit('update:page', lastPage)" :disabled="currentPage>=lastPage" :aria-label="lastPageLabel"><i class="bi bi-chevron-double-right" aria-hidden="true"></i></button>
      </div>
      <label class="workspace-page-size">
        <span class="visually-hidden">{{pageSizeLabel}}</span>
        <select class="form-control" :value="pageSize" :aria-label="pageSizeLabel" @change="$emit('update:page-size', Number($event.target.value))">
          <option :value="10">10</option><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option>
        </select>
      </label>
    </div>
  `,
};
