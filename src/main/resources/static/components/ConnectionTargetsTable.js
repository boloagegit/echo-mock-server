/** Shared HTTP/JMS target presentation. All mutations remain owned by SettingsPage. */
const ConnectionTargetsTable = {
  props: {
    protocol: { type: String, required: true },
    targets: { type: Array, required: true },
    testResults: { type: Object, required: true },
    testingId: [String, Number],
    yamlJmsTargetConfigured: Boolean,
  },
  emits: ['test', 'edit', 'make-default', 'delete'],
  inject: ['t'],
  data() { return { openMenuId: null }; },
  mounted() {
    document.addEventListener('click', this.closeOutside, true);
    document.addEventListener('focusin', this.closeOutside);
  },
  beforeUnmount() {
    document.removeEventListener('click', this.closeOutside, true);
    document.removeEventListener('focusin', this.closeOutside);
  },
  watch: { targets() { this.openMenuId = null; } },
  methods: {
    closeOutside(event) {
      if (!this.$el.contains(event.target) || !event.target.closest('.connection-target-menu')) this.openMenuId = null;
    },
    displayName(target) { return target.legacy ? this.t('settings.jmsApplicationTarget') : target.name; },
    roleLabel(target) {
      if (!target.enabled) return this.t('settings.disabled');
      if (target.legacy) return this.t('settings.jmsTargetForcedDefault');
      if (!target.defaultConnection) return this.t('settings.connectionRuleSelected');
      return this.t(this.protocol === 'http' ? 'settings.httpTargetDefault'
        : this.yamlJmsTargetConfigured ? 'settings.jmsTargetFallbackDefault' : 'settings.jmsTargetDefault');
    },
    roleHint(target) {
      if (!target.enabled) return this.t('settings.connectionDisabledHint');
      if (target.legacy) return this.t('settings.connectionYamlPriority');
      if (!target.defaultConnection) return this.t('settings.connectionRuleSelectedHint');
      return this.t(this.protocol === 'jms' && this.yamlJmsTargetConfigured
        ? 'settings.connectionFallbackOnly' : 'settings.connectionDefaultHint');
    },
    authLabel(target) {
      return target.authType==='NONE'?this.t('settings.authNone')
        :target.authType==='BASIC'?this.t('settings.authBasic'):this.t('settings.authBearerToken');
    },
    menuItems(target) {
      return [
        { key: 'make-default', label: this.t(this.protocol === 'http' ? 'settings.httpTargetSetDefault'
          : this.yamlJmsTargetConfigured ? 'settings.jmsTargetSetFallbackDefault' : 'settings.jmsTargetSetDefault'),
          disabled: !target.enabled || target.defaultConnection },
        { key: 'delete', label: this.t('rules.delete'), dividerBefore: true, disabled: target.defaultConnection,
          title: target.defaultConnection ? this.t('settings.defaultConnectionDeleteDisabled') : '' },
      ];
    },
    selectAction(action, target) {
      this.openMenuId = null;
      if (target.legacy || !this.menuItems(target).some(item => item.key === action && !item.disabled)) return;
      this.$emit(action, target);
    },
  },
  template: /* html */`
    <table class="settings-table connection-targets-table">
      <thead><tr><th scope="col">{{t('settings.connectionNameSource')}}</th><th scope="col">{{t('settings.connectionAddressDestination')}}</th><th scope="col">{{t('settings.connectionDefaultRole')}}</th><th scope="col">{{t('settings.lastConnectionTest')}}</th><th scope="col" class="settings-table-actions">{{t('settings.operation')}}</th></tr></thead>
      <tbody><tr v-for="target in targets" :key="target.id">
        <td class="connection-target-identity"><span class="connection-name">{{displayName(target)}}</span><span class="connection-target-meta">{{target.legacy ? 'application.yml' : t('settings.connectionDatabaseSource')}} · {{target.enabled?t('settings.enabled'):t('settings.disabled')}}</span><span v-if="target.secretConfigured || target.passwordConfigured" class="connection-target-meta">{{t('settings.credentialsConfigured')}}</span></td>
        <td class="connection-target-address"><span class="connection-address">{{protocol === 'http' ? target.baseUrl : target.serverUrl}}</span>
          <template v-if="protocol === 'http'"><span class="connection-target-meta">{{authLabel(target)}} · {{target.connectTimeoutSeconds}} / {{target.readTimeoutSeconds}} {{t('settings.unitSec')}}</span><span class="connection-target-meta">{{target.tlsVerificationEnabled?t('settings.tlsModeStrict'):t('settings.tlsModeCompatibility')}}</span></template>
          <span v-else class="sub-info connection-queue">{{target.providerType?.toUpperCase()}} · {{target.queueName}}</span>
        </td>
        <td class="connection-target-role"><span>{{roleLabel(target)}}</span><span class="connection-target-meta">{{roleHint(target)}}</span></td>
        <td class="connection-target-test"><template v-if="testResults[target.id]"><span class="connection-test-outcome" :class="testResults[target.id].success?'success':'error'">{{t(testResults[target.id].success ? 'settings.connectionTestPassed' : 'settings.connectionTestFailed')}}<template v-if="testResults[target.id].success"> · {{testResults[target.id].elapsedMs}} ms<span v-if="protocol === 'http'"> · HTTP {{testResults[target.id].status}}</span></template></span><span class="connection-target-meta">{{testResults[target.id].testedAt}}</span><span v-if="!testResults[target.id].success" class="connection-target-meta">{{testResults[target.id].error || t('monitoring.unavailable')}}</span></template><span v-else class="connection-target-meta">{{t('settings.connectionNotTested')}}</span></td>
        <td class="settings-table-actions"><div class="connection-target-actions">
          <ui-button variant="secondary" size="compact" class="connection-action-test" @click="$emit('test', target)" :disabled="testingId != null"><i v-if="testingId === target.id" class="bi bi-arrow-clockwise spin" aria-hidden="true"></i>{{t(protocol === 'http' ? 'settings.httpTargetTest' : 'settings.jmsTargetTest')}}</ui-button>
          <template v-if="!target.legacy"><ui-button variant="quiet" size="compact" icon-only class="connection-action-edit" :title="t('rules.edit')" :aria-label="t('rules.edit') + ' ' + displayName(target)" @click="$emit('edit', target)"><i class="bi bi-pencil" aria-hidden="true"></i></ui-button><ui-dropdown-menu viewport-safe class="connection-target-menu" :open="openMenuId === target.id" :items="menuItems(target)" :trigger-label="t('settings.connectionMoreActions', {name: displayName(target)})" @toggle="openMenuId = openMenuId === target.id ? null : target.id" @close="openMenuId = null" @select="selectAction($event, target)"></ui-dropdown-menu></template>
          <span v-else class="connection-target-meta connection-readonly">{{t('settings.connectionConfigReadonly')}}</span>
        </div></td>
      </tr></tbody>
    </table>
  `,
};
