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
        <button type="button" class="nav-item" :class="{active: page==='rules'}" :aria-label="t('sidebar.rules')" :title="sidebarCollapsed?t('sidebar.rules'):undefined" :aria-current="page==='rules'?'page':undefined" @click="$emit('update:page', 'rules'); $emit('update:mobileMenu', false)">
          <i class="bi bi-list-ul" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.rules')}}</span>
        </button>
        <button type="button" class="nav-item" :class="{active: page==='responses'}" :aria-label="t('sidebar.responses')" :title="sidebarCollapsed?t('sidebar.responses'):undefined" :aria-current="page==='responses'?'page':undefined" @click="$emit('update:page', 'responses'); $emit('update:mobileMenu', false)">
          <i class="bi bi-file-earmark-text" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.responses')}}</span>
        </button>
        <button type="button" class="nav-item" :class="{active: page==='stats'}" :aria-label="t('sidebar.stats')" :title="sidebarCollapsed?t('sidebar.stats'):undefined" :aria-current="page==='stats'?'page':undefined" @click="$emit('update:page', 'stats'); $emit('update:mobileMenu', false)">
          <i class="bi bi-clock-history" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.stats')}}</span>
        </button>
        <button type="button" class="nav-item" :class="{active: page==='audit'}" :aria-label="t('sidebar.audit')" :title="sidebarCollapsed?t('sidebar.audit'):undefined" :aria-current="page==='audit'?'page':undefined" @click="$emit('update:page', 'audit'); $emit('update:mobileMenu', false)">
          <i class="bi bi-journal-text" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.audit')}}</span>
        </button>
        <button type="button" v-if="isLoggedIn && status?.issueReportingEnabled === true" class="nav-item" :class="{active: page==='issues'}" :aria-label="t('sidebar.issues')" :title="sidebarCollapsed?t('sidebar.issues'):undefined" :aria-current="page==='issues'?'page':undefined" @click="$emit('update:page', 'issues'); $emit('update:mobileMenu', false)">
          <i class="bi bi-flag" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.issues')}}</span>
          <span v-if="openIssueCount>0" class="nav-badge">{{openIssueCount}}</span>
        </button>
        <button type="button" v-if="isAdmin" class="nav-item" :class="{active: page==='accounts'}" :aria-label="t('sidebar.accounts')" :title="sidebarCollapsed?t('sidebar.accounts'):undefined" :aria-current="page==='accounts'?'page':undefined" @click="$emit('update:page', 'accounts'); $emit('update:mobileMenu', false)">
          <i class="bi bi-people" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.accounts')}}</span>
        </button>
        <button type="button" v-if="isAdmin" class="nav-item" :class="{active: page==='settings'}" :aria-label="t('sidebar.settings')" :title="sidebarCollapsed?t('sidebar.settings'):undefined" :aria-current="page==='settings'?'page':undefined" @click="$emit('update:page', 'settings'); $emit('update:mobileMenu', false)">
          <i class="bi bi-gear" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.settings')}}</span>
        </button>

        <div class="nav-divider nav-divider--push"></div>
        <div class="nav-section">{{t('sidebar.preferences')}}</div>

        <button type="button" class="nav-item" @click="$emit('show-help')" :aria-label="t('sidebar.help')" :title="t('sidebar.help')">
          <i class="bi bi-question-circle" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.help')}}</span>
        </button>
        <button type="button" class="nav-item" @click="$emit('toggle-density')" :aria-label="densityLabel" :title="densityLabel">
          <i class="bi" :class="densityIcon" aria-hidden="true"></i><span class="nav-text">{{densityLabel}}</span>
        </button>
        <button type="button" class="nav-item" @click="$emit('toggle-theme')" :aria-label="themeLabel" :title="themeLabel">
          <i class="bi" :class="themeIcon" aria-hidden="true"></i><span class="nav-text">{{themeLabel}}</span>
        </button>
        <button type="button" class="nav-item" @click="$emit('switch-locale')" :aria-label="t('sidebar.languageShort')" :title="t('sidebar.languageShort')">
          <i class="bi bi-translate" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.languageShort')}}</span>
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
        <button type="button" v-if="isLoggedIn && status?.isBuiltinUser" class="nav-item" @click="$emit('change-password')" :aria-label="t('sidebar.changePassword')" :title="t('sidebar.changePassword')">
          <i class="bi bi-key" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.changePassword')}}</span>
        </button>
        <button type="button" v-if="isLoggedIn" class="nav-item" @click="$emit('logout')" :aria-label="t('sidebar.logout')" :title="t('sidebar.logout')">
          <i class="bi bi-box-arrow-left" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.logout')}}</span>
        </button>
        <button type="button" v-else class="nav-item" @click="$emit('login')" :aria-label="t('sidebar.login')" :title="t('sidebar.login')">
          <i class="bi bi-box-arrow-in-right" aria-hidden="true"></i><span class="nav-text">{{t('sidebar.login')}}</span>
        </button>
      </div>
    </aside>
  `
};
