/**
 * UiCodeViewer - 抽屜裡的長內容檢視器（唯讀）
 *
 * 行號、換行切換、格式化（JSON／XML）、複製與全文搜尋（Enter／Shift+Enter 跳下一筆／上一筆）。
 * 高度依內容，最多 maxHeight（抽屜加寬時更高），超過就在檢視器內捲動；CodeMirror 只繪製
 * 看得到的行，幾百 KB 的內容也不必截斷。
 */
const CODE_VIEWER_MARK_LIMIT = 2000;
const CODE_VIEWER_FORMAT_LIMIT = 2000000;
/** Indents XML one element per line (enough for reading SOAP / JMS payloads). */
const formatXmlForReading = xml => {
  let formatted = '';
  let indent = 0;
  for (const part of xml.replace(/(>)\s*(<)/g, '$1\n$2').split('\n')) {
    const line = part.trim();
    if (!line) continue;
    if (line.startsWith('</')) indent = Math.max(indent - 1, 0);
    formatted += '  '.repeat(indent) + line + '\n';
    if (line.startsWith('<') && !line.startsWith('</') && !line.startsWith('<?') && !line.startsWith('<!') && !line.endsWith('/>') && !line.includes('</')) indent++;
  }
  return formatted.trim();
};
const UiCodeViewer = {
  inject: ['t'],
  props: {
    value: { type: String, default: '' },
    label: { type: String, required: true },
    maxHeight: { type: Number, default: 420 },
  },
  emits: ['copy'],
  data() {
    return { wrap: true, formatted: true, query: '', matchIndex: 0, matchCount: 0, lineCount: 0 };
  },
  computed: {
    mode() {
      const head = this.value.trimStart().charAt(0);
      if (head === '{' || head === '[') return 'application/json';
      if (head === '<') return 'xml';
      return 'text/plain';
    },
    canFormat() {
      return this.mode !== 'text/plain' && this.value.length <= CODE_VIEWER_FORMAT_LIMIT;
    },
    text() {
      if (!this.formatted || !this.canFormat) return this.value;
      if (this.mode === 'xml') return formatXmlForReading(this.value);
      try { return JSON.stringify(JSON.parse(this.value), null, 2); } catch { return this.value; }
    },
  },
  watch: {
    text(value) {
      if (!this.cm) return;
      this.cm.setValue(value);
      this.afterContentChange();
    },
    mode(value) { this.cm?.setOption('mode', value); },
    wrap(value) {
      this.cm?.setOption('lineWrapping', value);
      this.$nextTick(() => this.fit());
    },
    query() {
      clearTimeout(this.searchTimer);
      this.searchTimer = setTimeout(() => this.search(), 150);
    },
  },
  mounted() {
    this.marks = [];
    this.matches = [];
    this.cm = CodeMirror(this.$refs.host, {
      value: this.text,
      mode: this.mode,
      readOnly: true,
      lineNumbers: true,
      lineWrapping: this.wrap,
      viewportMargin: 20,
    });
    this.cm.getInputField()?.setAttribute('aria-label', this.label);
    // Follows the drawer's width (wide view, window resize): re-measure and refit.
    this.resizeObserver = new ResizeObserver(() => { this.cm.refresh(); this.fit(); });
    this.resizeObserver.observe(this.$refs.host);
    this.afterContentChange();
  },
  beforeUnmount() {
    clearTimeout(this.searchTimer);
    this.resizeObserver?.disconnect();
  },
  methods: {
    fmtSize,
    afterContentChange() {
      this.lineCount = this.cm.lineCount();
      this.$nextTick(() => { this.fit(); this.search(); });
    },
    /** As tall as the content, up to the limit (taller in the wide drawer); longer content scrolls inside. */
    fit() {
      if (!this.cm) return;
      const wide = !!this.$el.closest?.('.ui-detail-drawer.is-wide');
      const limit = wide ? Math.max(this.maxHeight, window.innerHeight - 320) : this.maxHeight;
      const content = this.cm.heightAtLine(this.cm.lineCount(), 'local') + 12;
      this.cm.setSize(null, Math.max(48, Math.min(limit, Math.ceil(content))));
    },
    search() {
      if (!this.cm) return;
      this.marks.forEach(mark => mark.clear());
      this.marks = [];
      this.matches = [];
      this.matchIndex = 0;
      const needle = this.query.trim().toLowerCase();
      if (!needle) { this.matchCount = 0; return; }
      const haystack = this.text.toLowerCase();
      for (let at = haystack.indexOf(needle); at !== -1; at = haystack.indexOf(needle, at + needle.length)) {
        this.matches.push(at);
      }
      this.matchCount = this.matches.length;
      // Highlight a bounded number of hits; the count and stepping still cover every match.
      this.cm.operation(() => {
        this.matches.slice(0, CODE_VIEWER_MARK_LIMIT).forEach(at => {
          this.marks.push(this.cm.markText(this.cm.posFromIndex(at), this.cm.posFromIndex(at + needle.length), { className: 'ui-code-viewer__hit' }));
        });
      });
      this.showMatch();
    },
    step(direction) {
      if (!this.matchCount) return;
      this.matchIndex = (this.matchIndex + direction + this.matchCount) % this.matchCount;
      this.showMatch();
    },
    showMatch() {
      this.currentMark?.clear();
      const at = this.matches[this.matchIndex];
      if (at == null) return;
      const from = this.cm.posFromIndex(at);
      const to = this.cm.posFromIndex(at + this.query.trim().length);
      this.currentMark = this.cm.markText(from, to, { className: 'ui-code-viewer__hit is-current' });
      this.cm.scrollIntoView({ from, to }, 48);
    },
  },
  template: /* html */`
    <div class="ui-code-viewer">
      <div class="ui-code-viewer__toolbar">
        <label class="ui-code-viewer__search">
          <span class="visually-hidden">{{t('codeViewer.search')}}</span>
          <i class="bi bi-search" aria-hidden="true"></i>
          <input v-model="query" type="search" :placeholder="t('codeViewer.search')" spellcheck="false"
            @keydown.enter.prevent="step($event.shiftKey ? -1 : 1)">
          <span v-if="query" class="ui-code-viewer__count" aria-live="polite">{{matchCount ? matchIndex + 1 : 0}} / {{matchCount}}</span>
        </label>
        <ui-button type="button" variant="quiet" size="compact" :aria-pressed="wrap ? 'true' : 'false'" @click="wrap = !wrap">{{t('codeViewer.wrap')}}</ui-button>
        <ui-button v-if="canFormat" type="button" variant="quiet" size="compact" :aria-pressed="formatted ? 'true' : 'false'" @click="formatted = !formatted">{{t('codeViewer.format')}}</ui-button>
        <ui-button type="button" variant="quiet" size="compact" icon-only :title="t('codeViewer.copy')" :aria-label="t('codeViewer.copy')" @click="$emit('copy', value)"><i class="bi bi-clipboard" aria-hidden="true"></i></ui-button>
      </div>
      <div ref="host" class="ui-code-viewer__host"></div>
      <div class="ui-code-viewer__status">{{t('codeViewer.status', {lines: lineCount.toLocaleString(), size: fmtSize(value.length)})}}</div>
    </div>
  `,
};
