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
    helpSeen: Boolean
  },
  emits: [
    'update:page', 'update:sidebarCollapsed', 'update:mobileMenu',
    'show-help', 'toggle-theme', 'toggle-density', 'switch-locale',
    'login', 'logout', 'start-tour'
  ],
  inject: ['t'],
  template: /* html */`
    <aside class="sidebar" :class="{collapsed: sidebarCollapsed, 'mobile-open': mobileMenu}" @click.stop>
      <div class="sidebar-header">
        <div class="sidebar-brand">
          <svg class="brand-icon" viewBox="0 0 24 24" width="20" height="20">
            <g fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path d="M6 17a8 8 0 0 1 0-10" opacity=".5"/>
              <path d="M9 15a5 5 0 0 1 0-6" opacity=".75"/>
              <circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none"/>
              <path d="M15 9a5 5 0 0 1 0 6" opacity=".75"/>
              <path d="M18 7a8 8 0 0 1 0 10" opacity=".5"/>
            </g>
          </svg>
          <span>Echo</span>
          <span v-if="envLabel" class="env-label">{{envLabel}}</span>
        </div>
        <button class="sidebar-toggle" :aria-label="mobileMenu ? 'Close menu' : (sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar')" @click="mobileMenu ? $emit('update:mobileMenu', false) : $emit('update:sidebarCollapsed', !sidebarCollapsed)">
          <i class="bi" :class="mobileMenu ? 'bi-x-lg' : (sidebarCollapsed ? 'bi-chevron-right' : 'bi-chevron-left')"></i>
        </button>
      </div>
      <div class="sidebar-nav">
        <div class="nav-section">WORKSPACE</div>
        <div class="nav-item" :class="{active: page==='rules'}" @click="$emit('update:page', 'rules'); $emit('update:mobileMenu', false)">
          <i class="bi bi-list-ul"></i><span class="nav-text">{{t('sidebar.rules')}}</span>
        </div>
        <div class="nav-item" :class="{active: page==='responses'}" @click="$emit('update:page', 'responses'); $emit('update:mobileMenu', false)">
          <i class="bi bi-file-earmark-text"></i><span class="nav-text">{{t('sidebar.responses')}}</span>
        </div>
        <div class="nav-item" :class="{active: page==='stats'}" @click="$emit('update:page', 'stats'); $emit('update:mobileMenu', false)">
          <i class="bi bi-clock-history"></i><span class="nav-text">{{t('sidebar.stats')}}</span>
        </div>
        <div class="nav-item" :class="{active: page==='audit'}" @click="$emit('update:page', 'audit'); $emit('update:mobileMenu', false)">
          <i class="bi bi-journal-text"></i><span class="nav-text">{{t('sidebar.audit')}}</span>
        </div>
        <div v-if="isAdmin" class="nav-item" :class="{active: page==='accounts'}" @click="$emit('update:page', 'accounts'); $emit('update:mobileMenu', false)">
          <i class="bi bi-people"></i><span class="nav-text">{{t('sidebar.accounts')}}</span>
        </div>
        <div v-if="isAdmin" class="nav-item" :class="{active: page==='settings'}" @click="$emit('update:page', 'settings'); $emit('update:mobileMenu', false)">
          <i class="bi bi-gear"></i><span class="nav-text">{{t('sidebar.settings')}}</span>
        </div>

        <div class="nav-divider" style="margin-top:auto"></div>
        <div class="nav-section">PREFERENCES</div>

        <div class="nav-item" @click="helpSeen ? $emit('show-help') : $emit('start-tour')" :title="t('sidebar.help')">
          <i class="bi bi-question-circle"></i><span class="nav-text">{{t('sidebar.help')}}</span>
          <span v-if="!helpSeen" class="pulse-dot"></span>
        </div>
        <div class="nav-item" @click="$emit('toggle-density')" :title="densityLabel">
          <i class="bi" :class="densityIcon"></i><span class="nav-text">{{densityLabel}}</span>
        </div>
        <div class="nav-item" @click="$emit('toggle-theme')">
          <i class="bi" :class="themeIcon"></i><span class="nav-text">{{themeLabel}}</span>
        </div>
        <div class="nav-item" @click="$emit('switch-locale')">
          <i class="bi bi-translate"></i><span class="nav-text">{{locale==='zh-TW' ? '中' : 'EN'}}</span>
        </div>

        <div class="nav-divider"></div>

        <div v-if="isLoggedIn" class="nav-user">
          <div class="user-avatar"><i class="bi bi-person-fill"></i></div>
          <span class="nav-text nav-user-name">{{status?.username}}</span>
        </div>
        <div v-else class="nav-user nav-guest">
          <div class="user-avatar"><i class="bi bi-person"></i></div>
          <span class="nav-text nav-user-name">{{t('sidebar.guestMode')}}</span>
        </div>
        <div v-if="isLoggedIn" class="nav-item" @click="$emit('logout')">
          <i class="bi bi-box-arrow-left"></i><span class="nav-text">{{t('sidebar.logout')}}</span>
        </div>
        <div v-else class="nav-item" @click="$emit('login')">
          <i class="bi bi-box-arrow-in-right"></i><span class="nav-text">{{t('sidebar.login')}}</span>
        </div>
      </div>
    </aside>
  `
};
