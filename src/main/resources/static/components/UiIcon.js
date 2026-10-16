/**
 * UiIcon - Echo 自有的線條圖示（導覽與帳號動作）
 *
 * 24px 格線、1.75 線寬、圓角端點，與 App 標誌同一套幾何，讓側欄讀起來是同一個家族。
 * 其他介面圖示仍使用 Bootstrap Icons。
 */
const UI_ICON_PATHS = {
  rules: '<rect x="3" y="5" width="4.5" height="3" rx="1.5"/><path d="M10.5 6.5H21"/><rect x="3" y="10.5" width="4.5" height="3" rx="1.5"/><path d="M10.5 12H18"/><rect x="3" y="16" width="4.5" height="3" rx="1.5"/><path d="M10.5 17.5H20"/>',
  responses: '<path d="M5.5 4.5h13a2 2 0 0 1 2 2V15a2 2 0 0 1-2 2H11l-4.25 3.25V17H5.5a2 2 0 0 1-2-2V6.5a2 2 0 0 1 2-2z"/><path d="M10.25 8C9.56 8 9 8.56 9 9.25v.5c0 .55-.45 1-1 1 .55 0 1 .45 1 1v.5c0 .69.56 1.25 1.25 1.25"/><path d="M13.75 8c.69 0 1.25.56 1.25 1.25v.5c0 .55.45 1 1 1-.55 0-1 .45-1 1v.5c0 .69-.56 1.25-1.25 1.25"/>',
  logs: '<path d="M3 12h3.5L9 6l4 12 2.5-6H21"/>',
  audit: '<path d="M3.75 12a8.25 8.25 0 1 0 2.42-5.83"/><path d="M3.75 3.75v4.5h4.5"/><path d="M12 7.75V12l3 2"/>',
  issues: '<path d="M5 20.5V4"/><path d="M5 4.5h12.5l-2.25 4 2.25 4H5"/>',
  accounts: '<circle cx="9" cy="8" r="3.25"/><path d="M3.25 19.25c.7-3.1 2.95-4.75 5.75-4.75s5.05 1.65 5.75 4.75"/><path d="M15.25 5.1a3.25 3.25 0 0 1 0 5.8"/><path d="M17.5 14.75c1.75.65 2.85 2.1 3.25 4.5"/>',
  settings: '<path d="M4 6.5h3M11 6.5h9M4 12h9M17 12h3M4 17.5h1M9 17.5h11"/><circle cx="9" cy="6.5" r="2"/><circle cx="15" cy="12" r="2"/><circle cx="7" cy="17.5" r="2"/>',
  help: '<circle cx="12" cy="12" r="8.75"/><path d="M9.6 9.4a2.5 2.5 0 0 1 4.85.85c0 1.65-2.45 2.2-2.45 3.75"/><path d="M12 17.1v.01"/>',
  key: '<circle cx="8" cy="15.5" r="4"/><path d="M10.9 12.6 20 3.5"/><path d="m16.5 7 2.5 2.5"/><path d="m14.25 9.25 1.75 1.75"/>',
  logout: '<path d="M14 4.5H6.5a1.75 1.75 0 0 0-1.75 1.75v11.5c0 .97.78 1.75 1.75 1.75H14"/><path d="M10 12h10"/><path d="m16.75 8.5 3.5 3.5-3.5 3.5"/>',
  login: '<path d="M10 4.5h7.5c.97 0 1.75.78 1.75 1.75v11.5c0 .97-.78 1.75-1.75 1.75H10"/><path d="M3.75 12h10"/><path d="m10.5 8.5 3.5 3.5-3.5 3.5"/>',
};

const UiIcon = {
  props: {
    name: { type: String, required: true, validator: value => value in UI_ICON_PATHS },
  },
  template: /* html */`
    <svg class="ui-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false" v-html="paths"></svg>
  `,
  computed: {
    paths() { return UI_ICON_PATHS[this.name]; },
  },
};
