/** Data-driven SDK catalog, release selection and installation guides. */
const SdkPage = {
  props: {
    isLoggedIn: Boolean,
    locale: { type: String, default: 'en' },
    selection: { type: Object, default: () => ({ sdkId: '', version: '' }) },
  },
  emits: ['login', 'clip-copy', 'update:selection'],
  inject: ['t'],
  data() {
    return { catalog: null, loading: false, failed: false, loadToken: 0, query: '',
      downloading: '', downloadFailed: false, inputValues: {}, optionSelections: {} };
  },
  computed: {
    selectedSdk() { return this.catalog?.sdks.find(sdk => sdk.id === this.selection.sdkId) || null; },
    selectedRelease() {
      return this.selectedSdk?.releases.find(release =>
        release.version === (this.selection.version || this.selectedSdk.recommendedVersion)) || null;
    },
    selectionMissing() {
      return this.catalog && this.selection.sdkId && (!this.selectedSdk || !this.selectedRelease);
    },
    visibleSdks() {
      const query = this.query.trim().toLowerCase();
      return (this.catalog?.sdks || []).filter(sdk => !query ||
        [this.localized(sdk.name), this.localized(sdk.description), this.localized(sdk.language)]
          .join(' ').toLowerCase().includes(query));
    },
    guideSections() { return this.selectedRelease?.guide?.sections || []; },
  },
  watch: {
    isLoggedIn(loggedIn) {
      if (loggedIn) this.load();
      else { this.loadToken++; this.catalog = null; this.loading = false; }
    },
    selectedRelease(release) {
      const inputs = {};
      const options = {};
      for (const section of release?.guide?.sections || []) {
        if (section.options?.length) options[section.id] = section.options[0].id;
        for (const input of section.inputs || []) {
          inputs[input.id] = input.defaultValue.replaceAll('{{echoMockUrl}}', new URL('mock', window.location.href).href);
        }
      }
      this.inputValues = inputs;
      this.optionSelections = options;
      this.downloadFailed = false;
    },
  },
  mounted() { if (this.isLoggedIn) this.load(); },
  methods: {
    fmtSize,
    localized(value) {
      if (typeof value === 'string') return value;
      return value?.[this.locale] || value?.en || value?.['zh-TW'] || Object.values(value || {})[0] || '';
    },
    openSdk(sdk) {
      this.$emit('update:selection', { sdkId: sdk.id, version: sdk.recommendedVersion });
    },
    selectVersion(version) {
      this.$emit('update:selection', { sdkId: this.selectedSdk.id, version });
    },
    showCatalog() { this.$emit('update:selection', { sdkId: '', version: '' }); },
    optionId(section) { return this.optionSelections[section.id] || section.options?.[0]?.id || ''; },
    option(section) { return section.options?.find(option => option.id === this.optionId(section)); },
    options(section) { return (section.options || []).map(option => ({ value: option.id, label: this.localized(option.label) })); },
    snippets(section) { return [...(section.snippets || []), ...(this.option(section)?.snippets || [])]; },
    code(template) {
      return template.replace(/\{\{(json:)?([A-Za-z][A-Za-z0-9_-]*)\}\}/g, (token, json, id) =>
        Object.hasOwn(this.inputValues, id) ? (json ? JSON.stringify(this.inputValues[id]) : this.inputValues[id]) : token);
    },
    async load() {
      const token = ++this.loadToken;
      this.loading = true;
      this.failed = false;
      try {
        const response = await fetch('/api/admin/sdk', { headers: { Accept: 'application/json' } });
        if (!response.ok) throw new Error('SDK catalog: ' + response.status);
        const catalog = await response.json();
        if (catalog.schemaVersion !== 1 || !Array.isArray(catalog.sdks)) throw new Error('Unsupported SDK catalog');
        if (this.isLoggedIn && token === this.loadToken) this.catalog = catalog;
      } catch {
        if (token === this.loadToken) this.failed = true;
      } finally {
        if (token === this.loadToken) this.loading = false;
      }
    },
    async download(artifact) {
      if (this.downloading) return;
      this.downloading = artifact.downloadUrl;
      this.downloadFailed = false;
      try {
        const response = await fetch(artifact.downloadUrl, { headers: { Accept: artifact.mediaType } });
        if (!response.ok) throw new Error('SDK download: ' + response.status);
        const url = URL.createObjectURL(await response.blob());
        const link = document.createElement('a');
        link.href = url;
        link.download = artifact.fileName;
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      } catch {
        this.downloadFailed = true;
      } finally {
        this.downloading = '';
      }
    },
  },
  template: /* html */`
    <section class="sdk-page settings-workspace">
      <div class="page-header"><h1 class="page-title">{{t('sdk.title')}}</h1></div>
      <p class="sdk-intro">{{t('sdk.description')}}</p>
      <ui-load-state v-if="!isLoggedIn" kind="empty" icon="bi-box-arrow-in-right"
        :title="t('sdk.loginRequired')" :has-action="true">
        <template #action><ui-button @click="$emit('login')">{{t('sidebar.login')}}</ui-button></template>
      </ui-load-state>
      <p v-else-if="loading" role="status">{{t('common.loading')}}</p>
      <ui-load-state v-else-if="failed" kind="error" icon="bi-exclamation-circle"
        :title="t('sdk.loadFailed')" :has-action="true">
        <template #action><ui-button @click="load">{{t('common.retry')}}</ui-button></template>
      </ui-load-state>
      <ui-load-state v-else-if="selectionMissing" kind="error" icon="bi-search"
        :title="t('sdk.selectionMissing')" :has-action="true">
        <template #action><ui-button @click="showCatalog">{{t('sdk.backToCatalog')}}</ui-button></template>
      </ui-load-state>
      <template v-else-if="catalog">
        <div v-if="!selectedSdk" class="sdk-catalog">
          <ui-load-state v-if="!catalog.sdks.length" kind="empty" icon="bi-box"
            :title="t('sdk.empty')"></ui-load-state>
          <template v-else>
            <label v-if="catalog.sdks.length>1" class="sdk-search">
              <span class="visually-hidden">{{t('sdk.search')}}</span>
              <input type="search" class="form-control" v-model="query" :placeholder="t('sdk.search')">
            </label>
            <div class="sdk-catalog-grid">
              <article v-for="sdk in visibleSdks" :key="sdk.id" class="settings-card sdk-catalog-card" :data-sdk-id="sdk.id">
                <div class="settings-card-body">
                  <span class="sdk-language">{{localized(sdk.language)}}</span>
                  <h2>{{localized(sdk.name)}}</h2>
                  <p>{{localized(sdk.description)}}</p>
                  <div class="sdk-catalog-version">
                    <span class="sdk-version">{{sdk.recommendedVersion}}</span>
                    <span class="sdk-release-tag">{{t('sdk.recommended')}}</span>
                    <span>{{t('sdk.versionCount', {count: sdk.releases.length})}}</span>
                  </div>
                  <ui-button @click="openSdk(sdk)">{{t('sdk.viewSdk')}}</ui-button>
                </div>
              </article>
            </div>
            <p v-if="!visibleSdks.length" class="sdk-note">{{t('sdk.noResults')}}</p>
          </template>
        </div>

        <div v-else class="sdk-release" :data-sdk-id="selectedSdk.id" :data-sdk-version="selectedRelease.version">
          <div class="sdk-release-toolbar">
            <ui-button variant="quiet" @click="showCatalog"><i class="bi bi-arrow-left" aria-hidden="true"></i> {{t('sdk.backToCatalog')}}</ui-button>
            <div class="sdk-version-picker">
              <label for="sdk-version">{{t('sdk.version')}}</label>
              <select id="sdk-version" class="form-control" :value="selectedRelease.version" @change="selectVersion($event.target.value)">
                <option v-for="release in selectedSdk.releases" :key="release.version" :value="release.version">
                  {{release.version}} · {{t('sdk.channel.' + release.channel)}}{{release.version===selectedSdk.recommendedVersion ? ' · ' + t('sdk.recommended') : ''}}
                </option>
              </select>
            </div>
          </div>
          <div class="sdk-release-heading">
            <h2>{{localized(selectedSdk.name)}} <span class="sdk-version">{{selectedRelease.version}}</span></h2>
            <span class="sdk-release-tag">{{t('sdk.channel.' + selectedRelease.channel)}}</span>
            <p>{{localized(selectedSdk.description)}}</p>
            <p class="sdk-compatibility"><span v-for="(item, index) in selectedRelease.compatibility" :key="index">{{localized(item)}}</span></p>
          </div>
          <div class="sdk-artifacts">
            <article v-for="artifact in selectedRelease.artifacts" :key="artifact.id" class="settings-card sdk-artifact-card">
              <div class="settings-card-body">
                <div class="sdk-artifact-info">
                  <h3>{{localized(artifact.label)}}</h3>
                  <p v-if="artifact.description">{{localized(artifact.description)}}</p>
                  <p class="sdk-file">{{artifact.fileName}} · {{fmtSize(artifact.size)}}</p>
                  <details class="sdk-checksum"><summary>{{t('sdk.checksum')}}</summary><code>{{artifact.sha256}}</code></details>
                </div>
                <ui-button variant="primary" :disabled="!!downloading" :data-artifact-id="artifact.id" @click="download(artifact)">
                  <i class="bi bi-download" aria-hidden="true"></i> {{t('sdk.downloadArtifact', {name: localized(artifact.label)})}}
                </ui-button>
              </div>
            </article>
          </div>
          <p v-if="downloadFailed" class="sdk-error" role="alert">{{t('sdk.downloadFailed')}}</p>
          <div class="settings-grid sdk-guides">
            <section v-for="section in guideSections" :key="section.id" class="settings-card"
              :class="{'sdk-guide-wide': !section.inputs?.length && !section.options?.length}"
              :aria-labelledby="'sdk-guide-'+section.id" :data-guide-id="section.id">
              <h2 :id="'sdk-guide-'+section.id" class="settings-card-header">{{localized(section.title)}}</h2>
              <div class="settings-card-body">
                <p v-for="(paragraph, index) in section.paragraphs || []" :key="'section-'+index">{{localized(paragraph)}}</p>
                <ui-segmented-control v-if="section.options?.length" :name="'sdk-guide-'+section.id"
                  :aria-label="localized(section.title)" :model-value="optionId(section)" :options="options(section)"
                  @update:model-value="optionSelections[section.id]=$event"></ui-segmented-control>
                <p v-for="(paragraph, index) in option(section)?.paragraphs || []" :key="'option-'+index" class="sdk-step">{{localized(paragraph)}}</p>
                <div v-if="section.inputs?.length" class="sdk-config-fields">
                  <template v-for="input in section.inputs" :key="input.id">
                    <label :for="'sdk-input-'+input.id">{{localized(input.label)}}</label>
                    <input :id="'sdk-input-'+input.id" :type="input.type" class="form-control" v-model="inputValues[input.id]"
                      :aria-describedby="input.hint ? 'sdk-input-hint-'+input.id : undefined">
                    <small v-if="input.hint" :id="'sdk-input-hint-'+input.id">{{localized(input.hint)}}</small>
                  </template>
                </div>
                <div v-for="(snippet, index) in snippets(section)" :key="index" class="sdk-snippet">
                  <div class="sdk-snippet-label">{{localized(snippet.label)}}</div>
                  <pre class="sdk-code"><code>{{code(snippet.code)}}</code></pre>
                  <ui-button size="compact" @click="$emit('clip-copy', code(snippet.code))">{{t('sdk.copySnippet', {name: localized(snippet.label)})}}</ui-button>
                </div>
              </div>
            </section>
          </div>
        </div>
      </template>
    </section>
  `,
};
