/**
 * SidebarNav component - 側邊導航列
 *
 * 使用全域註冊，不需要 ES Module。
 * 透過 inject 取得 t() 函數。
 *
 * 側欄只放導覽；主題、密度、語言、說明與帳號操作收在底部的使用者選單。
 */
const SidebarNav = {
  props: {
    page: String,
    sidebarCollapsed: Boolean,
    mobileMenu: Boolean,
    envLabel: String,
    isAdmin: Boolean,
    isLoggedIn: Boolean,
    status: Object,
    jmsEnabled: Boolean,
    locale: String,
    theme: String,
    density: String,
    helpSeen: Boolean,
    openIssueCount: Number
  },
  emits: [
    'update:page', 'update:sidebarCollapsed', 'update:mobileMenu',
    'show-help', 'set-theme', 'set-density', 'switch-locale',
    'login', 'logout', 'start-tour', 'change-password'
  ],
  inject: ['t'],
  data() {
    return { userMenuOpen: false };
  },
  computed: {
    userName() {
      return this.isLoggedIn ? (this.status?.username || '') : this.t('sidebar.guestMode');
    },
    userRole() {
      if (!this.isLoggedIn) return '';
      return this.isAdmin ? this.t('sidebar.roleAdmin') : this.t('sidebar.roleUser');
    },
    userInitial() {
      return this.isLoggedIn && this.status?.username ? this.status.username.charAt(0).toUpperCase() : '';
    },
    httpPort() {
      return this.status?.serverPort ? ':' + this.status.serverPort : '';
    },
    jmsPort() {
      const match = /:(\d+)$/.exec(this.status?.artemisBrokerUrl || '');
      return match ? ':' + match[1] : '';
    },
    themeOptions() {
      return ['dark', 'light', 'auto'].map(value => ({ value, label: this.t('theme.' + value) }));
    },
    densityOptions() {
      return ['compact', 'normal', 'comfortable'].map(value => ({ value, label: this.t('density.' + value) }));
    },
    localeOptions() {
      return [{ value: 'zh-TW', label: this.t('sidebar.languageZh') }, { value: 'en', label: this.t('sidebar.languageEn') }];
    }
  },
  mounted() {
    document.addEventListener('pointerdown', this.onDocumentPointerDown);
  },
  unmounted() {
    document.removeEventListener('pointerdown', this.onDocumentPointerDown);
  },
  methods: {
    go(target) {
      this.$emit('update:page', target);
      this.$emit('update:mobileMenu', false);
    },
    toggleUserMenu() {
      this.userMenuOpen ? this.closeUserMenu(false) : this.openUserMenu();
    },
    openUserMenu() {
      this.userMenuOpen = true;
      this.$nextTick(() => this.$refs.userMenu?.querySelector('input, button')?.focus());
    },
    closeUserMenu(restoreFocus = true) {
      if (!this.userMenuOpen) return;
      this.userMenuOpen = false;
      if (restoreFocus) this.$nextTick(() => this.$refs.userTrigger?.focus());
    },
    onDocumentPointerDown(event) {
      if (!this.userMenuOpen) return;
      if (this.$refs.userMenu?.contains(event.target) || this.$refs.userTrigger?.contains(event.target)) return;
      this.closeUserMenu(false);
    },
    onUserMenuFocusOut(event) {
      const next = event.relatedTarget;
      if (!next || this.$refs.userMenu?.contains(next) || this.$refs.userTrigger?.contains(next)) return;
      this.closeUserMenu(false);
    },
    setLocale(value) {
      if (value !== this.locale) this.$emit('switch-locale');
    },
    runMenuAction(action) {
      this.closeUserMenu();
      this.$emit(action);
    }
  },
  template: /* html */`
    <aside class="sidebar" :class="{collapsed: sidebarCollapsed, 'mobile-open': mobileMenu, 'has-user-menu': userMenuOpen}" @click.stop>
      <div class="sidebar-header">
        <div class="sidebar-brand">
          <span class="brand-mark" aria-hidden="true">
            <img class="brand-icon" src="/favicon.ico?v=20260906.2" alt="" width="22" height="22">
          </span>
          <span>Echo</span>
          <span v-if="envLabel" class="env-label">{{envLabel}}</span>
        </div>
        <button class="sidebar-toggle" :aria-label="mobileMenu ? t('sidebar.closeMenu') : (sidebarCollapsed ? t('sidebar.expandSidebar') : t('sidebar.collapseSidebar'))" @click="mobileMenu ? $emit('update:mobileMenu', false) : $emit('update:sidebarCollapsed', !sidebarCollapsed)">
          <ui-motion-icon :icon="mobileMenu ? 'bi-x-lg' : (sidebarCollapsed ? 'bi-chevron-right' : 'bi-chevron-left')"></ui-motion-icon>
        </button>
      </div>
      <nav class="sidebar-nav" :aria-label="t('sidebar.workspace')">
        <div class="nav-section">{{t('sidebar.workspace')}}</div>
        <button type="button" class="nav-item" :class="{active: page==='rules'}" :aria-label="t('sidebar.rules')" :title="sidebarCollapsed?t('sidebar.rules'):undefined" :aria-current="page==='rules'?'page':undefined" @click="go('rules')">
          <i class="bi bi-list-ul" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.rules')}}</span>
        </button>
        <button type="button" class="nav-item" :class="{active: page==='responses'}" :aria-label="t('sidebar.responses')" :title="sidebarCollapsed?t('sidebar.responses'):undefined" :aria-current="page==='responses'?'page':undefined" @click="go('responses')">
          <i class="bi bi-chat-square-text" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.responses')}}</span>
        </button>
        <button type="button" class="nav-item" :class="{active: page==='stats'}" :aria-label="t('sidebar.stats')" :title="sidebarCollapsed?t('sidebar.stats'):undefined" :aria-current="page==='stats'?'page':undefined" @click="go('stats')">
          <i class="bi bi-activity" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.stats')}}</span>
        </button>
        <button type="button" class="nav-item" :class="{active: page==='audit'}" :aria-label="t('sidebar.audit')" :title="sidebarCollapsed?t('sidebar.audit'):undefined" :aria-current="page==='audit'?'page':undefined" @click="go('audit')">
          <i class="bi bi-clock-history" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.audit')}}</span>
        </button>
        <button type="button" v-if="isLoggedIn && status?.issueReportingEnabled === true" class="nav-item" :class="{active: page==='issues'}" :aria-label="t('sidebar.issues')" :title="sidebarCollapsed?t('sidebar.issues'):undefined" :aria-current="page==='issues'?'page':undefined" @click="go('issues')">
          <i class="bi bi-flag" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.issues')}}</span>
          <span v-if="openIssueCount>0" class="nav-badge">{{openIssueCount}}</span>
        </button>

        <template v-if="isAdmin">
          <div class="nav-section">{{t('sidebar.manage')}}</div>
          <button type="button" class="nav-item" :class="{active: page==='accounts'}" :aria-label="t('sidebar.accounts')" :title="sidebarCollapsed?t('sidebar.accounts'):undefined" :aria-current="page==='accounts'?'page':undefined" @click="go('accounts')">
            <i class="bi bi-people" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.accounts')}}</span>
          </button>
          <button type="button" class="nav-item" :class="{active: page==='settings'}" :aria-label="t('sidebar.settings')" :title="sidebarCollapsed?t('sidebar.settings'):undefined" :aria-current="page==='settings'?'page':undefined" @click="go('settings')">
            <i class="bi bi-sliders" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.settings')}}</span>
          </button>
        </template>
      </nav>

      <div class="sidebar-footer">
        <section class="service-status" :aria-label="t('sidebar.serviceStatus')">
          <div class="service-status__title">{{t('sidebar.serviceStatus')}}</div>
          <div class="service-status__row">
            <span class="service-status__dot is-on" aria-hidden="true"></span>
            <span class="service-status__name">HTTP</span>
            <span class="service-status__value">{{httpPort || t('sidebar.serviceRunning')}}</span>
          </div>
          <div class="service-status__row">
            <span class="service-status__dot" :class="{'is-on': jmsEnabled}" aria-hidden="true"></span>
            <span class="service-status__name">JMS</span>
            <span class="service-status__value">{{jmsEnabled ? (jmsPort || t('sidebar.serviceRunning')) : t('sidebar.serviceDisabled')}}</span>
          </div>
        </section>

        <div class="user-menu-anchor">
          <Transition name="ui-popover-motion">
            <div v-if="userMenuOpen" id="sidebar-user-menu" ref="userMenu" class="user-menu" role="dialog" :aria-label="t('sidebar.userMenu')" @keydown.esc.prevent="closeUserMenu()" @focusout="onUserMenuFocusOut">
              <div class="user-menu__identity">
                <span class="user-menu__name">{{userName}}</span>
                <span v-if="userRole" class="user-menu__role">{{userRole}}</span>
              </div>
              <div class="user-menu__section-title">{{t('sidebar.preferences')}}</div>
              <div class="user-menu__pref">
                <span class="user-menu__pref-label" aria-hidden="true">{{t('sidebar.appearance')}}</span>
                <ui-segmented-control size="compact" name="sidebar-theme" :aria-label="t('sidebar.appearance')"
                  :model-value="theme" :options="themeOptions" @update:model-value="$emit('set-theme', $event)"></ui-segmented-control>
              </div>
              <div class="user-menu__pref">
                <span class="user-menu__pref-label" aria-hidden="true">{{t('sidebar.density')}}</span>
                <ui-segmented-control size="compact" name="sidebar-density" :aria-label="t('sidebar.density')"
                  :model-value="density" :options="densityOptions" @update:model-value="$emit('set-density', $event)"></ui-segmented-control>
              </div>
              <div class="user-menu__pref">
                <span class="user-menu__pref-label" aria-hidden="true">{{t('sidebar.language')}}</span>
                <ui-segmented-control size="compact" name="sidebar-locale" :aria-label="t('sidebar.language')"
                  :model-value="locale" :options="localeOptions" @update:model-value="setLocale"></ui-segmented-control>
              </div>
              <div class="user-menu__divider" role="separator"></div>
              <button type="button" class="user-menu__item" @click="runMenuAction('show-help')">
                <i class="bi bi-question-circle" aria-hidden="true"></i><span>{{t('sidebar.help')}}</span>
              </button>
              <button type="button" v-if="isLoggedIn && status?.isBuiltinUser" class="user-menu__item" @click="runMenuAction('change-password')">
                <i class="bi bi-key" aria-hidden="true"></i><span>{{t('sidebar.changePassword')}}</span>
              </button>
              <button type="button" v-if="isLoggedIn" class="user-menu__item user-menu__item--danger" @click="runMenuAction('logout')">
                <i class="bi bi-box-arrow-left" aria-hidden="true"></i><span>{{t('sidebar.logout')}}</span>
              </button>
              <button type="button" v-else class="user-menu__item" @click="runMenuAction('login')">
                <i class="bi bi-box-arrow-in-right" aria-hidden="true"></i><span>{{t('sidebar.login')}}</span>
              </button>
            </div>
          </Transition>
          <button type="button" ref="userTrigger" class="user-trigger" :class="{'is-open': userMenuOpen, 'is-guest': !isLoggedIn}"
            :aria-label="t('sidebar.userMenuFor', {name: userName})" :title="sidebarCollapsed ? userName : undefined"
            aria-haspopup="dialog" :aria-expanded="userMenuOpen ? 'true' : 'false'" aria-controls="sidebar-user-menu"
            @click="toggleUserMenu">
            <span class="user-avatar" aria-hidden="true">
              <template v-if="userInitial">{{userInitial}}</template>
              <i v-else class="bi bi-person"></i>
            </span>
            <span class="user-trigger__text nav-text">
              <span class="user-trigger__name">{{userName}}</span>
              <span v-if="userRole" class="user-trigger__role">{{userRole}}</span>
            </span>
            <i class="bi bi-chevron-expand user-trigger__chevron" aria-hidden="true"></i>
          </button>
        </div>
      </div>
    </aside>
  `
};
