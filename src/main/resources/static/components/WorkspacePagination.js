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
  },
  emits: ['update:page', 'update:page-size'],
  computed: {
    lastPage() { return Math.max(1, Number(this.totalPages) || 1); },
    currentPage() { return Math.min(this.lastPage, Math.max(1, Number(this.page) || 1)); },
  },
  template: /* html */`
    <div class="card-table-footer workspace-pagination">
      <div class="workspace-pagination-summary"><slot name="summary"></slot></div>
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
