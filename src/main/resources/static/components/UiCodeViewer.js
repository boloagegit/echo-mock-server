/**
 * UiCodeViewer - Long content viewer, drawer shared with rules editor
 *
 * Line number, line feed switching, formatting (JSON/XML), copying and full-text search (Enter/Shift+Enter skip to next/previous item).
 * The height depends on the content, up to maxHeight (higher when the drawer is widened). If it exceeds the height, it will scroll in the viewer; CodeMirror only draws
 * There is no need to truncate the content of hundreds of KB in visible lines.
 *
 * editable: Editable (response content of the rule editor), reported as update:value. Formatting only changes the display: when turned on it is
 * Read-only typesetting view, edit the original text only after closing it, and the save will always be the original text (template syntax will not be rewritten).
 * fill: The height fills the parent layer and does not scale according to the content. The same CodeMirror instance switches between viewing, editing, and formatting,
 * No rebuilding, so it won't flash blank when switching.
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
    editable: { type: Boolean, default: false },
    fill: { type: Boolean, default: false },
    placeholder: { type: String, default: '' },
  },
  emits: ['copy', 'update:value'],
  data() {
    // Read-only content opens formatted (as in the drawers); editable content opens as written.
    return { wrap: true, formatted: !this.editable, query: '', matchIndex: 0, matchCount: 0, lineCount: 0 };
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
    /** Editable content is read-only while the formatted view is on: what you edit is always the original. */
    readOnly() {
      return !this.editable || (this.formatted && this.canFormat);
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
      // While typing, the editor already holds this text; replacing it would move the cursor.
      if (this.cm.getValue() !== value) this.cm.setValue(value);
      this.afterContentChange();
    },
    readOnly(value) { this.cm?.setOption('readOnly', value); },
    // Starting to edit shows the original text; leaving edit mode returns to the formatted view.
    editable(value) { this.formatted = !value; },
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
      readOnly: this.readOnly,
      lineNumbers: true,
      lineWrapping: this.wrap,
      viewportMargin: 20,
    });
    this.cm.getInputField()?.setAttribute('aria-label', this.label);
    this.cm.on('change', (cm, change) => {
      if (change.origin === 'setValue' || this.readOnly) return;
      this.$emit('update:value', cm.getValue());
    });
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
      if (this.fill) { this.cm.setSize(null, '100%'); return; }
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
    <div class="ui-code-viewer" :class="{'is-fill': fill, 'is-editable': editable, 'is-read-only': readOnly}">
      <div class="ui-code-viewer__toolbar">
        <div v-if="$slots.lead" class="ui-code-viewer__lead"><slot name="lead"></slot></div>
        <label class="ui-code-viewer__search">
          <span class="visually-hidden">{{t('codeViewer.search')}}</span>
          <i class="bi bi-search" aria-hidden="true"></i>
          <input v-model="query" type="search" :placeholder="t('codeViewer.search')" spellcheck="false"
            @keydown.enter.prevent="step($event.shiftKey ? -1 : 1)">
          <span v-if="query" class="ui-code-viewer__count" aria-live="polite">{{matchCount ? matchIndex + 1 : 0}} / {{matchCount}}</span>
        </label>
        <ui-button type="button" variant="quiet" size="compact" icon-only :aria-pressed="wrap ? 'true' : 'false'" :title="t('codeViewer.wrap')" :aria-label="t('codeViewer.wrap')" @click="wrap = !wrap"><i class="bi bi-text-wrap" aria-hidden="true"></i></ui-button>
        <ui-button v-if="canFormat" type="button" variant="quiet" size="compact" icon-only :aria-pressed="formatted ? 'true' : 'false'" :title="t('codeViewer.format')" :aria-label="t('codeViewer.format')" @click="formatted = !formatted"><i class="bi bi-braces" aria-hidden="true"></i></ui-button>
        <ui-button type="button" variant="quiet" size="compact" icon-only :title="t('codeViewer.copy')" :aria-label="t('codeViewer.copy')" @click="$emit('copy', value)"><i class="bi bi-clipboard" aria-hidden="true"></i></ui-button>
        <slot name="tools"></slot>
      </div>
      <div class="ui-code-viewer__body">
        <div ref="host" class="ui-code-viewer__host"></div>
        <span v-if="editable && !value && placeholder" class="ui-code-viewer__placeholder" aria-hidden="true">{{placeholder}}</span>
      </div>
      <div class="ui-code-viewer__status">
        <span>{{t('codeViewer.status', {lines: lineCount.toLocaleString(), size: fmtSize(value.length)})}}</span>
        <span v-if="editable && readOnly" class="ui-code-viewer__mode">{{t('codeViewer.formattedReadOnly')}}</span>
      </div>
    </div>
  `,
};
