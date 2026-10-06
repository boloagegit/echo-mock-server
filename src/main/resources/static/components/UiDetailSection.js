/**
 * UiDetailSection - 抽屜裡可收合的區段
 *
 * 標題列在捲動時固定在抽屜頂端，隨時知道正在看哪一段；收合時在標題旁顯示摘要。
 * 收合狀態依 id 記在這個瀏覽器，下次打開同類詳情維持一樣。
 */
const DETAIL_SECTION_KEY = 'echo.drawerSections';
const readCollapsedSections = () => {
  try { return JSON.parse(localStorage.getItem(DETAIL_SECTION_KEY) || '{}'); } catch { return {}; }
};
const UiDetailSection = {
  props: {
    id: { type: String, required: true },
    title: { type: String, required: true },
    summary: { type: String, default: '' },
  },
  data() {
    return { open: !readCollapsedSections()[this.id], bodyId: 'detail-section-' + this.id.replace(/[^\w-]/g, '-') };
  },
  methods: {
    toggle() {
      this.open = !this.open;
      const collapsed = readCollapsedSections();
      if (this.open) delete collapsed[this.id]; else collapsed[this.id] = true;
      try { localStorage.setItem(DETAIL_SECTION_KEY, JSON.stringify(collapsed)); } catch { /* storage unavailable */ }
    },
  },
  template: /* html */`
    <section class="detail-section ui-detail-section" :class="{'is-collapsed': !open}">
      <div class="detail-section__head ui-detail-section__head">
        <h3 class="detail-section__title">
          <button type="button" class="ui-detail-section__toggle" :aria-expanded="open ? 'true' : 'false'" :aria-controls="bodyId" @click="toggle">
            <i class="bi bi-chevron-right ui-detail-section__chevron" aria-hidden="true"></i>{{title}}
          </button>
        </h3>
        <span v-if="summary" class="ui-detail-section__summary">{{summary}}</span>
        <div v-if="open && $slots.tools" class="detail-section__tools"><slot name="tools"></slot></div>
      </div>
      <div v-show="open" :id="bodyId" class="ui-detail-section__body"><slot></slot></div>
    </section>
  `,
};
