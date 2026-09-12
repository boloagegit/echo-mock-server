/**
 * SidebarNav component - 側邊導航列
 *
 * 使用全域註冊，不需要 ES Module。
 * 透過 inject 取得 t() 函數。
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
    themeIcon: String,
    themeLabel: String,
    density: String,
    densityIcon: String,
    densityLabel: String,
    helpSeen: Boolean,
    openIssueCount: Number
  },
  emits: [
    'update:page', 'update:sidebarCollapsed', 'update:mobileMenu',
    'show-help', 'toggle-theme', 'toggle-density', 'switch-locale',
    'login', 'logout', 'start-tour', 'change-password'
  ],
  inject: ['t'],
  template: /* html */`
    <aside class="sidebar" :class="{collapsed: sidebarCollapsed, 'mobile-open': mobileMenu}" @click.stop>
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
      <div class="sidebar-nav">
        <div class="nav-section">{{t('sidebar.workspace')}}</div>
        <button type="button" class="nav-item" :class="{active: page==='rules'}" @click="$emit('update:page', 'rules'); $emit('update:mobileMenu', false)">
          <i class="bi bi-list-ul"></i><span class="nav-text">{{t('sidebar.rules')}}</span>
        </button>
        <button type="button" class="nav-item" :class="{active: page==='responses'}" @click="$emit('update:page', 'responses'); $emit('update:mobileMenu', false)">
          <i class="bi bi-file-earmark-text"></i><span class="nav-text">{{t('sidebar.responses')}}</span>
        </button>
        <button type="button" class="nav-item" :class="{active: page==='stats'}" @click="$emit('update:page', 'stats'); $emit('update:mobileMenu', false)">
          <i class="bi bi-clock-history"></i><span class="nav-text">{{t('sidebar.stats')}}</span>
        </button>
        <button type="button" class="nav-item" :class="{active: page==='audit'}" @click="$emit('update:page', 'audit'); $emit('update:mobileMenu', false)">
          <i class="bi bi-journal-text"></i><span class="nav-text">{{t('sidebar.audit')}}</span>
        </button>
        <button type="button" v-if="isLoggedIn" class="nav-item" :class="{active: page==='issues'}" @click="$emit('update:page', 'issues'); $emit('update:mobileMenu', false)">
          <i class="bi bi-flag"></i><span class="nav-text">{{t('sidebar.issues')}}</span>
          <span v-if="openIssueCount>0" class="nav-badge">{{openIssueCount}}</span>
        </button>
        <button type="button" v-if="isAdmin" class="nav-item" :class="{active: page==='accounts'}" @click="$emit('update:page', 'accounts'); $emit('update:mobileMenu', false)">
          <i class="bi bi-people"></i><span class="nav-text">{{t('sidebar.accounts')}}</span>
        </button>
        <button type="button" v-if="isAdmin" class="nav-item" :class="{active: page==='settings'}" @click="$emit('update:page', 'settings'); $emit('update:mobileMenu', false)">
          <i class="bi bi-gear"></i><span class="nav-text">{{t('sidebar.settings')}}</span>
        </button>

        <div class="nav-divider nav-divider--push"></div>
        <div class="nav-section">{{t('sidebar.preferences')}}</div>

        <button type="button" class="nav-item" @click="helpSeen ? $emit('show-help') : $emit('start-tour')" :title="t('sidebar.help')">
          <i class="bi bi-question-circle"></i><span class="nav-text">{{t('sidebar.help')}}</span>
        </button>
        <button type="button" class="nav-item" @click="$emit('toggle-density')" :title="densityLabel">
          <i class="bi" :class="densityIcon"></i><span class="nav-text">{{densityLabel}}</span>
        </button>
        <button type="button" class="nav-item" @click="$emit('toggle-theme')">
          <i class="bi" :class="themeIcon"></i><span class="nav-text">{{themeLabel}}</span>
        </button>
        <button type="button" class="nav-item" @click="$emit('switch-locale')">
          <i class="bi bi-translate"></i><span class="nav-text">{{t('sidebar.languageShort')}}</span>
        </button>

        <div class="nav-divider"></div>

        <div v-if="isLoggedIn" class="nav-user">
          <div class="user-avatar"><i class="bi bi-person-fill"></i></div>
          <span class="nav-text nav-user-name">{{status?.username}}</span>
        </div>
        <div v-else class="nav-user nav-guest">
          <div class="user-avatar"><i class="bi bi-person"></i></div>
          <span class="nav-text nav-user-name">{{t('sidebar.guestMode')}}</span>
        </div>
        <button type="button" v-if="isLoggedIn && status?.isBuiltinUser" class="nav-item" @click="$emit('change-password')">
          <i class="bi bi-key"></i><span class="nav-text">{{t('sidebar.changePassword')}}</span>
        </button>
        <button type="button" v-if="isLoggedIn" class="nav-item" @click="$emit('logout')">
          <i class="bi bi-box-arrow-left"></i><span class="nav-text">{{t('sidebar.logout')}}</span>
        </button>
        <button type="button" v-else class="nav-item" @click="$emit('login')">
          <i class="bi bi-box-arrow-in-right"></i><span class="nav-text">{{t('sidebar.login')}}</span>
        </button>
      </div>
    </aside>
  `
};
