/** Keep optional floating menus inside the visible viewport without changing action semantics. */
function dropdownViewportPosition(trigger, menu, viewport) {
  const margin = 8, gap = 4;
  const left = Math.max(margin, Math.min(trigger.right - menu.width, viewport.width - menu.width - margin));
  const below = trigger.bottom + gap;
  const top = Math.max(margin, Math.min(below + menu.height <= viewport.height - margin
    ? below : trigger.top - menu.height - gap, viewport.height - menu.height - margin));
  return { position: 'fixed', left: left + 'px', top: top + 'px', right: 'auto', bottom: 'auto',
    maxHeight: Math.max(0, viewport.height - margin * 2) + 'px', overflowY: 'auto' };
}

/** Controlled action menu with shared focus and keyboard behavior. */
const UiDropdownMenu = {
  props: {
    open: { type: Boolean, required: true },
    items: { type: Array, required: true },
    triggerLabel: { type: String, required: true },
    viewportSafe: Boolean,
  },
  emits: ['toggle', 'select', 'close'],
  setup(props, { emit }) {
    const triggerRef = Vue.ref(null);
    const menuRef = Vue.ref(null);
    const menuPosition = Vue.ref({});
    const enabledItems = () => [...(menuRef.value?.querySelectorAll('[role="menuitem"]') || [])]
      .filter(item => !item.disabled);
    const focusItem = index => {
      const items = enabledItems();
      if (!items.length) { menuRef.value?.focus(); return; }
      items[(index + items.length) % items.length].focus();
    };
    const updatePosition = () => {
      if (props.viewportSafe && triggerRef.value?.$el && menuRef.value) {
        menuPosition.value = dropdownViewportPosition(triggerRef.value.$el.getBoundingClientRect(),
          menuRef.value.getBoundingClientRect(), { width: window.innerWidth, height: window.innerHeight });
      }
    };
    const focusFirst = () => Vue.nextTick(async () => {
      updatePosition();
      await Vue.nextTick();
      focusItem(0);
    });
    const onViewportChange = () => { if (props.viewportSafe && props.open) updatePosition(); };
    Vue.onMounted(() => {
      window.addEventListener('resize', onViewportChange);
      window.addEventListener('scroll', onViewportChange, true);
    });
    Vue.onBeforeUnmount(() => {
      window.removeEventListener('resize', onViewportChange);
      window.removeEventListener('scroll', onViewportChange, true);
    });
    const close = () => {
      emit('close');
      Vue.nextTick(() => triggerRef.value?.focus());
    };
    const selectItem = key => {
      emit('select', key);
      Vue.nextTick(() => triggerRef.value?.focus());
    };
    const onKeydown = event => {
      const items = enabledItems();
      const current = items.indexOf(document.activeElement);
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        focusItem(current + 1);
      } else if (event.key === 'ArrowUp') {
        event.preventDefault();
        focusItem(current - 1);
      } else if (event.key === 'Home') {
        event.preventDefault();
        focusItem(0);
      } else if (event.key === 'End') {
        event.preventDefault();
        focusItem(items.length - 1);
      } else if (event.key === 'Escape') {
        event.preventDefault();
        close();
      }
    };
    Vue.watch(() => props.open, value => {
      if (value) { focusFirst(); }
    });
    return { triggerRef, menuRef, menuPosition, focusFirst, onKeydown, selectItem };
  },
  template: /* html */`
    <div class="ui-dropdown-menu data-dropdown-wrapper">
      <ui-button ref="triggerRef" type="button" variant="secondary" icon-only
        :title="triggerLabel" :aria-label="triggerLabel"
        :aria-expanded="open ? 'true' : 'false'" aria-haspopup="menu"
        @click.stop="$emit('toggle')" @keydown.down.prevent="open ? focusFirst() : $emit('toggle')">
        <i class="bi bi-three-dots-vertical" aria-hidden="true"></i>
      </ui-button>
      <Transition name="ui-popover-motion">
        <div v-if="open" ref="menuRef" class="data-dropdown ui-dropdown-menu__panel"
          role="menu" tabindex="-1" :style="viewportSafe ? menuPosition : undefined" :aria-label="triggerLabel" @keydown="onKeydown">
          <template v-for="item in items" :key="item.key">
            <div v-if="item.dividerBefore" class="data-dropdown-divider" role="separator"></div>
            <ui-button type="button" variant="quiet" size="compact" class="data-dropdown-item ui-dropdown-menu__item"
              role="menuitem" :disabled="item.disabled" :title="item.title" @click="selectItem(item.key)">
              <i v-if="item.icon" class="bi" :class="item.icon" aria-hidden="true"></i>
              <span>{{item.label}}</span>
            </ui-button>
          </template>
        </div>
      </Transition>
    </div>
  `,
};
