/**
 * UiDetailDrawer - 列表詳情的右側抽屜
 *
 * 所有列表頁共用：點選一列即在右側開啟詳情，列表仍可操作。
 * - Esc 關閉並把焦點還給開啟前的元素（通常是該列）
 * - ↑／↓（或 k／j）切換上一筆／下一筆，由頁面決定實際資料
 * - 有對話框開啟或焦點在輸入框時不攔截按鍵
 */
const UiDetailDrawer = {
  inject: ['t'],
  props: {
    open: { type: Boolean, default: false },
    title: { type: String, default: '' },
    subtitle: { type: String, default: '' },
    loading: { type: Boolean, default: false },
    error: { type: Boolean, default: false },
    /** The body still shows the previous record while the next one loads (see heldDetailMixin). */
    stale: { type: Boolean, default: false },
    hasPrev: { type: Boolean, default: false },
    hasNext: { type: Boolean, default: false },
  },
  emits: ['close', 'prev', 'next', 'retry'],
  setup(props, { emit }) {
    const panelRef = Vue.ref(null);
    const headingId = `ui-detail-drawer-title-${Math.random().toString(36).slice(2, 8)}`;
    let returnFocus = null;

    const isTyping = el => !!el && (['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName) || el.isContentEditable || !!el.closest?.('.CodeMirror'));
    const modalOpen = () => !!document.querySelector('.modal-overlay, [aria-modal="true"]');

    const close = () => emit('close');
    const onKeydown = event => {
      if (!props.open || event.defaultPrevented || modalOpen()) { return; }
      // Escape inside a field belongs to that field (clear / blur); a second Escape closes the drawer.
      if (event.key === 'Escape' && !isTyping(document.activeElement)) {
        event.preventDefault();
        close();
        return;
      }
      if (isTyping(document.activeElement) || event.altKey || event.ctrlKey || event.metaKey) { return; }
      if ((event.key === 'ArrowDown' || event.key === 'j') && props.hasNext) {
        event.preventDefault();
        emit('next');
      } else if ((event.key === 'ArrowUp' || event.key === 'k') && props.hasPrev) {
        event.preventDefault();
        emit('prev');
      }
    };

    // Runs before the DOM update, so the selected row is still marked when the drawer closes.
    Vue.watch(() => props.open, async value => {
      if (value) {
        if (!panelRef.value?.contains(document.activeElement)) { returnFocus = document.activeElement; }
        await Vue.nextTick();
        panelRef.value?.focus({ preventScroll: true });
        return;
      }
      const selectedRow = document.querySelector('[data-detail-row].is-selected');
      const target = selectedRow || returnFocus;
      returnFocus = null;
      if (target && document.contains(target)) { Vue.nextTick(() => target.focus({ preventScroll: true })); }
    });

    // Capture phase: decide before the app-level Escape cascade closes a modal.
    Vue.onMounted(() => document.addEventListener('keydown', onKeydown, true));
    Vue.onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown, true));

    // Long titles and descriptions show two lines each; "show all" reveals the rest.
    const titleRef = Vue.ref(null);
    const subtitleRef = Vue.ref(null);
    const headerExpanded = Vue.ref(false);
    const headerClamped = Vue.ref(false);
    const overflows = el => !!el && el.scrollHeight > el.clientHeight + 1;
    const measureHeader = async () => {
      await Vue.nextTick();
      if (!headerExpanded.value) { headerClamped.value = overflows(titleRef.value) || overflows(subtitleRef.value); }
    };
    Vue.watch(() => [props.open, props.title, props.subtitle], () => { headerExpanded.value = false; measureHeader(); }, { immediate: true });

    return { panelRef, headingId, close, titleRef, subtitleRef, headerExpanded, headerClamped };
  },
  template: /* html */`
    <Transition name="ui-drawer-motion">
      <aside v-if="open" ref="panelRef" class="ui-detail-drawer" :class="{'is-stale': stale}" role="complementary" :aria-labelledby="headingId" tabindex="-1">
        <header class="ui-detail-drawer__header">
          <div class="ui-detail-drawer__titles">
            <h2 ref="titleRef" :id="headingId" class="ui-detail-drawer__title" :class="{'is-clamped': !headerExpanded}" :title="headerClamped ? title : null">{{title}}</h2>
            <p v-if="subtitle" ref="subtitleRef" class="ui-detail-drawer__subtitle" :class="{'is-clamped': !headerExpanded}" :title="headerClamped ? subtitle : null">{{subtitle}}</p>
            <button v-if="headerClamped" type="button" class="ui-detail-drawer__more" :aria-expanded="headerExpanded ? 'true' : 'false'"
              @click="headerExpanded = !headerExpanded">{{headerExpanded ? t('common.showLess') : t('common.showAll')}}</button>
            <div v-if="$slots.meta" class="ui-detail-drawer__meta"><slot name="meta"></slot></div>
          </div>
          <div class="ui-detail-drawer__nav">
            <ui-button type="button" variant="quiet" size="compact" icon-only :disabled="!hasPrev"
              :title="t('common.previousItem')" :aria-label="t('common.previousItem')" @click="$emit('prev')"><i class="bi bi-chevron-up" aria-hidden="true"></i></ui-button>
            <ui-button type="button" variant="quiet" size="compact" icon-only :disabled="!hasNext"
              :title="t('common.nextItem')" :aria-label="t('common.nextItem')" @click="$emit('next')"><i class="bi bi-chevron-down" aria-hidden="true"></i></ui-button>
            <ui-button type="button" variant="quiet" size="compact" icon-only
              :title="t('common.close')" :aria-label="t('common.close')" @click="close"><i class="bi bi-x-lg" aria-hidden="true"></i></ui-button>
          </div>
        </header>
        <div v-if="$slots.actions" class="ui-detail-drawer__actions"><slot name="actions"></slot></div>
        <div class="ui-detail-drawer__body" :aria-busy="stale ? 'true' : null">
          <div v-if="loading" class="ui-detail-drawer__state loading-reveal" role="status">
            <i class="bi bi-arrow-clockwise spin" aria-hidden="true"></i><span>{{t('common.loading')}}</span>
          </div>
          <div v-else-if="error" class="ui-detail-drawer__state is-error" role="alert">
            <i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>{{t('common.loadFailed')}}</span>
            <ui-button type="button" variant="secondary" size="compact" @click="$emit('retry')"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</ui-button>
          </div>
          <slot v-else></slot>
        </div>
        <footer v-if="$slots.footer" class="ui-detail-drawer__footer"><slot name="footer"></slot></footer>
      </aside>
    </Transition>
  `,
};
