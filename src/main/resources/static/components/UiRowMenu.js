/**
 * UiRowMenu - 表格列的「⋯」選單
 *
 * 包裝 UiDropdownMenu 並自行管理開關狀態，讓每一列不必在父層記錄。
 * 鍵盤行為（↑↓、Home／End、Esc）沿用 UiDropdownMenu；點擊選單外即關閉。
 */
const UiRowMenu = {
  props: {
    items: { type: Array, required: true },
    label: { type: String, required: true },
    icon: { type: String, default: 'bi-three-dots-vertical' },
  },
  emits: ['select'],
  data() {
    return { open: false };
  },
  watch: {
    open(value) {
      if (value) { document.addEventListener('pointerdown', this.onOutside, true); }
      else { document.removeEventListener('pointerdown', this.onOutside, true); }
    },
  },
  unmounted() {
    document.removeEventListener('pointerdown', this.onOutside, true);
  },
  methods: {
    onOutside(event) {
      if (!this.$el.contains(event.target)) { this.open = false; }
    },
    select(key) {
      this.open = false;
      this.$emit('select', key);
    },
  },
  template: /* html */`
    <ui-dropdown-menu class="ui-row-menu" :open="open" :items="items" :trigger-label="label" viewport-safe
      trigger-variant="quiet" trigger-size="compact" :trigger-icon="icon"
      @toggle="open = !open" @close="open = false" @select="select"></ui-dropdown-menu>
  `,
};
