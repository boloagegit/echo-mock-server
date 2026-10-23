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
      downloading: '', downloadFailed: false, inputValues: {}, optionSelections: {},
      copied: '', copiedTimer: 0, focusAfterRender: '' };
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
    isRecommended() { return this.selectedRelease?.version === this.selectedSdk?.recommendedVersion; },
    // Installation methods that name the file they need, keyed by artifact id.
    artifactUses() {
      const uses = {};
      for (const section of this.guideSections) {
        for (const option of section.options || []) {
          if (option.artifact) (uses[option.artifact] ||= []).push(this.localized(option.label));
        }
      }
      return uses;
    },
    // The chosen installation method decides the main download; otherwise the first declared file.
    primaryArtifact() {
      const artifacts = this.selectedRelease?.artifacts || [];
      for (const section of this.guideSections) {
        const id = this.option(section)?.artifact;
        const linked = id && artifacts.find(artifact => artifact.id === id);
        if (linked) return linked;
      }
      return artifacts[0] || null;
    },
    primaryUse() {
      for (const section of this.guideSections) {
        const option = this.option(section);
        if (option?.artifact && option.artifact === this.primaryArtifact?.id) return this.localized(option.label);
      }
      return '';
    },
    otherArtifacts() { return (this.selectedRelease?.artifacts || []).filter(artifact => artifact !== this.primaryArtifact); },
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
        if (section.options?.length) options[section.id] = this.optionSelections[section.id] &&
          section.options.some(option => option.id === this.optionSelections[section.id])
          ? this.optionSelections[section.id] : section.options[0].id;
        for (const input of section.inputs || []) {
          inputs[input.id] = input.defaultValue.replaceAll('{{echoMockUrl}}', new URL('mock', window.location.href).href);
        }
      }
      this.inputValues = inputs;
      this.optionSelections = options;
      this.downloadFailed = false;
    },
    'selection.sdkId'() { this.$nextTick(() => { if (this.$refs.scroll) this.$refs.scroll.scrollTop = 0; }); },
  },
  mounted() { if (this.isLoggedIn) this.load(); },
  updated() {
    if (!this.focusAfterRender) return;
    const target = this.focusAfterRender === 'heading' ? this.$refs.releaseHeading
      : this.$el.querySelector?.(`.sdk-catalog-card[data-sdk-id="${CSS.escape(this.focusAfterRender)}"] .sdk-catalog-open`);
    if (target) { target.focus({ preventScroll: this.focusAfterRender !== 'heading' }); this.focusAfterRender = ''; }
  },
  beforeUnmount() { clearTimeout(this.copiedTimer); },
  methods: {
    fmtSize,
    localized(value) {
      if (typeof value === 'string') return value;
      return value?.[this.locale] || value?.en || value?.['zh-TW'] || Object.values(value || {})[0] || '';
    },
    channelTone(channel) { return { stable: 'success', preview: 'accent', deprecated: 'warning' }[channel] || 'neutral'; },
    recommendedRelease(sdk) { return sdk.releases.find(release => release.version === sdk.recommendedVersion) || sdk.releases[0]; },
    versionLabel(release) {
      return [release.version, this.t('sdk.channel.' + release.channel),
        release.version === this.selectedSdk.recommendedVersion ? this.t('sdk.recommended') : '']
        .filter(Boolean).join(' · ');
    },
    openSdk(sdk) {
      this.focusAfterRender = 'heading';
      this.$emit('update:selection', { sdkId: sdk.id, version: sdk.recommendedVersion });
    },
    selectVersion(version) {
      this.$emit('update:selection', { sdkId: this.selectedSdk.id, version });
    },
    showCatalog() {
      this.focusAfterRender = this.selection.sdkId;
      this.$emit('update:selection', { sdkId: '', version: '' });
    },
    optionId(section) { return this.optionSelections[section.id] || section.options?.[0]?.id || ''; },
    option(section) { return section.options?.find(option => option.id === this.optionId(section)); },
    options(section) { return (section.options || []).map(option => ({ value: option.id, label: this.localized(option.label) })); },
    optionArtifact(section) {
      const id = this.option(section)?.artifact;
      return id ? this.selectedRelease.artifacts.find(artifact => artifact.id === id) : null;
    },
    snippets(section) { return [...(section.snippets || []), ...(this.option(section)?.snippets || [])]; },
    code(template) {
      return template.replace(/\{\{(json:)?([A-Za-z][A-Za-z0-9_-]*)\}\}/g, (token, json, id) =>
        Object.hasOwn(this.inputValues, id) ? (json ? JSON.stringify(this.inputValues[id]) : this.inputValues[id]) : token);
    },
    copy(key, text) {
      this.$emit('clip-copy', text);
      this.copied = key;
      clearTimeout(this.copiedTimer);
      this.copiedTimer = setTimeout(() => { this.copied = ''; }, 2000);
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
    <section class="page workspace-page sdk-page active" :aria-labelledby="selectedSdk && selectedRelease ? 'sdk-release-title' : 'sdk-page-title'">
      <div class="page-header">
        <div class="page-heading">
          <h1 v-if="!selectedSdk || !selectedRelease" id="sdk-page-title" class="page-title">{{t('sdk.title')}}</h1>
          <ui-button v-else variant="quiet" size="compact" class="sdk-back" @click="showCatalog">
            <i class="bi bi-arrow-left" aria-hidden="true"></i>{{t('sdk.backToCatalog')}}
          </ui-button>
          <span v-if="isLoggedIn && catalog && !selection.sdkId && catalog.sdks.length" class="page-count"
            :aria-label="t('sdk.sdkCount', {count: catalog.sdks.length})">{{catalog.sdks.length}}</span>
        </div>
      </div>
      <div ref="scroll" class="page-scroll sdk-scroll">
        <ui-load-state v-if="!isLoggedIn" kind="empty" icon="bi-box-arrow-in-right"
          :title="t('sdk.loginRequired')" :has-action="true">
          <template #action><ui-button variant="primary" @click="$emit('login')">{{t('sidebar.login')}}</ui-button></template>
        </ui-load-state>
        <div v-else-if="loading" class="sdk-skeleton" role="status">
          <span class="visually-hidden">{{t('sdk.loading')}}</span>
          <div class="sdk-skeleton-line is-wide" aria-hidden="true"></div>
          <div class="sdk-skeleton-row" aria-hidden="true"></div>
          <div class="sdk-skeleton-row" aria-hidden="true"></div>
        </div>
        <ui-load-state v-else-if="failed" kind="error" icon="bi-exclamation-circle"
          :title="t('sdk.loadFailed')" :hint="t('sdk.loadFailedHint')" :has-action="true">
          <template #action><ui-button @click="load"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i>{{t('common.retry')}}</ui-button></template>
        </ui-load-state>
        <ui-load-state v-else-if="selectionMissing" kind="error" icon="bi-search"
          :title="t('sdk.selectionMissing')"
          :hint="t('sdk.selectionMissingHint', {name: [selection.sdkId, selection.version].filter(Boolean).join(' ')})" :has-action="true">
          <template #action><ui-button @click="showCatalog">{{t('sdk.backToCatalog')}}</ui-button></template>
        </ui-load-state>
        <template v-else-if="catalog">

          <!-- Catalog -->
          <div v-if="!selectedSdk" class="sdk-catalog">
            <p class="sdk-intro">{{t('sdk.description')}}</p>
            <ui-load-state v-if="!catalog.sdks.length" kind="empty" icon="bi-box"
              :title="t('sdk.empty')" :hint="t('sdk.emptyHint')"></ui-load-state>
            <template v-else>
              <label v-if="catalog.sdks.length>1" class="sdk-search">
                <span class="visually-hidden">{{t('sdk.search')}}</span>
                <i class="bi bi-search" aria-hidden="true"></i>
                <input type="search" class="form-control" v-model="query" :placeholder="t('sdk.search')">
              </label>
              <ul class="sdk-catalog-list" role="list">
                <li v-for="sdk in visibleSdks" :key="sdk.id">
                  <article class="sdk-catalog-card" :data-sdk-id="sdk.id" :aria-labelledby="'sdk-name-'+sdk.id" @click="openSdk(sdk)">
                    <div class="sdk-catalog-identity">
                      <h2 :id="'sdk-name-'+sdk.id">{{localized(sdk.name)}}</h2>
                      <p>{{localized(sdk.description)}}</p>
                    </div>
                    <dl class="sdk-catalog-facts">
                      <div><dt>{{t('sdk.language')}}</dt><dd>{{localized(sdk.language)}}</dd></div>
                      <div><dt>{{t('sdk.requirements')}}</dt>
                        <dd>{{recommendedRelease(sdk).compatibility.map(localized).join(' · ')}}</dd></div>
                      <div><dt>{{t('sdk.recommended')}}</dt>
                        <dd><span class="sdk-version">{{sdk.recommendedVersion}}</span>
                          <ui-badge :tone="channelTone(recommendedRelease(sdk).channel)">{{t('sdk.channel.' + recommendedRelease(sdk).channel)}}</ui-badge>
                          <span v-if="sdk.releases.length>1" class="sdk-muted">{{t('sdk.versionCount', {count: sdk.releases.length})}}</span></dd></div>
                    </dl>
                    <ui-button class="sdk-catalog-open" :aria-label="t('sdk.viewNamedSdk', {name: localized(sdk.name)})">
                      {{t('sdk.viewSdk')}}<i class="bi bi-arrow-right" aria-hidden="true"></i>
                    </ui-button>
                  </article>
                </li>
              </ul>
              <ui-load-state v-if="!visibleSdks.length" kind="empty" icon="bi-search" :title="t('sdk.noResults')"
                :has-action="true">
                <template #action><ui-button @click="query=''">{{t('sdk.clearSearch')}}</ui-button></template>
              </ui-load-state>
            </template>
          </div>

          <!-- Release -->
          <div v-else class="sdk-release" :data-sdk-id="selectedSdk.id" :data-sdk-version="selectedRelease.version">
            <header class="sdk-release-header">
              <div class="sdk-release-identity">
                <h1 id="sdk-release-title" ref="releaseHeading" tabindex="-1">{{localized(selectedSdk.name)}}</h1>
                <p>{{localized(selectedSdk.description)}}</p>
              </div>
              <div class="sdk-version-picker">
                <label for="sdk-version">{{t('sdk.version')}}</label>
                <select id="sdk-version" class="form-control" :value="selectedRelease.version" @change="selectVersion($event.target.value)">
                  <option v-for="release in selectedSdk.releases" :key="release.version" :value="release.version">{{versionLabel(release)}}</option>
                </select>
              </div>
              <div class="sdk-requirements">
                <span class="sdk-requirements-label">{{t('sdk.requirements')}}</span>
                <ul role="list">
                  <li v-for="(item, index) in selectedRelease.compatibility" :key="index"><i class="bi bi-check2" aria-hidden="true"></i>{{localized(item)}}</li>
                </ul>
              </div>
            </header>

            <div v-if="selectedRelease.channel==='deprecated' || !isRecommended || selectedRelease.channel==='preview'"
              class="sdk-notice" :class="{'is-warning': selectedRelease.channel==='deprecated'}" role="note">
              <i class="bi" :class="selectedRelease.channel==='deprecated' ? 'bi-exclamation-triangle' : 'bi-info-circle'" aria-hidden="true"></i>
              <p>
                <template v-if="selectedRelease.channel==='deprecated'">{{t('sdk.notice.deprecated')}}</template>
                <template v-else-if="!isRecommended">{{t('sdk.notice.older', {version: selectedSdk.recommendedVersion})}}</template>
                <template v-if="selectedRelease.channel==='preview'"> {{t('sdk.notice.preview')}}</template>
              </p>
              <ui-button v-if="!isRecommended" size="compact" @click="selectVersion(selectedSdk.recommendedVersion)">
                {{t('sdk.useRecommended', {version: selectedSdk.recommendedVersion})}}
              </ui-button>
            </div>

            <div class="sdk-release-body">
              <aside class="sdk-download" aria-labelledby="sdk-download-title">
                <div class="sdk-download-head">
                  <h2 id="sdk-download-title">{{t('sdk.download')}}</h2>
                  <span class="sdk-muted">
                    <span class="sdk-version">{{selectedRelease.version}}</span>
                    <ui-badge :tone="channelTone(selectedRelease.channel)">{{t('sdk.channel.' + selectedRelease.channel)}}</ui-badge>
                  </span>
                </div>
                <p class="sdk-muted sdk-release-date">{{t('sdk.releasedOn', {date: selectedRelease.releaseDate})}}</p>

                <div v-if="primaryArtifact" class="sdk-file is-primary">
                  <div class="sdk-file-title">
                    <h3>{{localized(primaryArtifact.label)}}</h3>
                    <span class="sdk-muted">{{fmtSize(primaryArtifact.size)}}</span>
                  </div>
                  <p v-if="primaryUse" class="sdk-file-use"><i class="bi bi-check2-circle" aria-hidden="true"></i>{{t('sdk.forMethod', {method: primaryUse})}}</p>
                  <p v-if="primaryArtifact.description" class="sdk-file-description">{{localized(primaryArtifact.description)}}</p>
                  <ui-button variant="primary" class="sdk-file-download" :disabled="!!downloading" :data-artifact-id="primaryArtifact.id"
                    :aria-label="t('sdk.downloadArtifact', {name: localized(primaryArtifact.label)})" @click="download(primaryArtifact)">
                    <i class="bi" :class="downloading===primaryArtifact.downloadUrl ? 'bi-arrow-repeat spin' : 'bi-download'" aria-hidden="true"></i>
                    {{downloading===primaryArtifact.downloadUrl ? t('sdk.downloading') : t('sdk.downloadArtifact', {name: localized(primaryArtifact.label)})}}
                  </ui-button>
                  <p class="sdk-file-name">{{primaryArtifact.fileName}}</p>
                  <details class="sdk-checksum">
                    <summary>{{t('sdk.checksum')}}</summary>
                    <div class="sdk-checksum-value">
                      <span>{{primaryArtifact.sha256}}</span>
                      <ui-button variant="quiet" size="compact" icon-only :aria-label="t('sdk.copyChecksum', {name: localized(primaryArtifact.label)})"
                        @click="copy('sha-'+primaryArtifact.id, primaryArtifact.sha256)">
                        <i class="bi" :class="copied==='sha-'+primaryArtifact.id ? 'bi-check2' : 'bi-copy'" aria-hidden="true"></i>
                      </ui-button>
                    </div>
                  </details>
                </div>

                <template v-if="otherArtifacts.length">
                  <h3 class="sdk-other-title">{{t('sdk.otherFiles')}}</h3>
                  <ul class="sdk-other-files" role="list">
                    <li v-for="artifact in otherArtifacts" :key="artifact.id" class="sdk-file">
                      <div class="sdk-file-title">
                        <h4>{{localized(artifact.label)}} <span class="sdk-muted">{{fmtSize(artifact.size)}}</span></h4>
                        <ui-button size="compact" :disabled="!!downloading" :data-artifact-id="artifact.id"
                          :aria-label="t('sdk.downloadArtifact', {name: localized(artifact.label)})" @click="download(artifact)">
                          <i class="bi" :class="downloading===artifact.downloadUrl ? 'bi-arrow-repeat spin' : 'bi-download'" aria-hidden="true"></i>{{downloading===artifact.downloadUrl ? t('sdk.downloading') : t('sdk.download')}}
                        </ui-button>
                      </div>
                      <p v-if="artifactUses[artifact.id]" class="sdk-file-use">{{t('sdk.forMethod', {method: artifactUses[artifact.id].join(' / ')})}}</p>
                      <p v-if="artifact.description" class="sdk-file-description">{{localized(artifact.description)}}</p>
                      <p class="sdk-file-name">{{artifact.fileName}}</p>
                      <details class="sdk-checksum">
                        <summary>{{t('sdk.checksum')}}</summary>
                        <div class="sdk-checksum-value">
                          <span>{{artifact.sha256}}</span>
                          <ui-button variant="quiet" size="compact" icon-only :aria-label="t('sdk.copyChecksum', {name: localized(artifact.label)})"
                            @click="copy('sha-'+artifact.id, artifact.sha256)">
                            <i class="bi" :class="copied==='sha-'+artifact.id ? 'bi-check2' : 'bi-copy'" aria-hidden="true"></i>
                          </ui-button>
                        </div>
                      </details>
                    </li>
                  </ul>
                </template>
                <p v-if="downloadFailed" class="sdk-error" role="alert"><i class="bi bi-exclamation-circle" aria-hidden="true"></i>{{t('sdk.downloadFailed')}}</p>
              </aside>

              <section class="sdk-guide" aria-labelledby="sdk-guide-title">
                <h2 id="sdk-guide-title" class="sdk-guide-title">{{t('sdk.setupSteps')}}</h2>
                <ol class="sdk-steps">
                  <li v-for="section in guideSections" :key="section.id" class="sdk-step"
                    :aria-labelledby="'sdk-guide-'+section.id" :data-guide-id="section.id">
                    <h3 :id="'sdk-guide-'+section.id">{{localized(section.title)}}</h3>
                    <p v-for="(paragraph, index) in section.paragraphs || []" :key="'section-'+index" class="sdk-step-text">{{localized(paragraph)}}</p>
                    <ui-segmented-control v-if="section.options?.length" class="sdk-step-options" :name="'sdk-guide-'+section.id"
                      :aria-label="localized(section.title)" :model-value="optionId(section)" :options="options(section)"
                      @update:model-value="optionSelections[section.id]=$event"></ui-segmented-control>
                    <p v-for="(paragraph, index) in option(section)?.paragraphs || []" :key="'option-'+index" class="sdk-step-text">{{localized(paragraph)}}</p>
                    <div v-if="optionArtifact(section)" class="sdk-step-file">
                      <i class="bi bi-file-earmark" aria-hidden="true"></i>
                      <span>{{t('sdk.requiresFile')}} <strong>{{localized(optionArtifact(section).label)}}</strong>
                        <span class="sdk-muted">{{optionArtifact(section).fileName}}</span></span>
                    </div>
                    <div v-if="section.inputs?.length" class="sdk-fields">
                      <div v-for="input in section.inputs" :key="input.id" class="sdk-field">
                        <label :for="'sdk-input-'+input.id">{{localized(input.label)}}</label>
                        <input :id="'sdk-input-'+input.id" :type="input.type" class="form-control" v-model="inputValues[input.id]"
                          spellcheck="false" autocomplete="off" :aria-describedby="input.hint ? 'sdk-input-hint-'+input.id : undefined">
                        <small v-if="input.hint" :id="'sdk-input-hint-'+input.id">{{localized(input.hint)}}</small>
                      </div>
                    </div>
                    <figure v-for="(snippet, index) in snippets(section)" :key="optionId(section)+'-'+index" class="sdk-snippet">
                      <figcaption class="sdk-snippet-head">
                        <span>{{localized(snippet.label)}}</span>
                        <ui-button variant="quiet" size="compact" :aria-label="t('sdk.copySnippet', {name: localized(snippet.label)})"
                          @click="copy(section.id+'-'+index, code(snippet.code))">
                          <i class="bi" :class="copied===section.id+'-'+index ? 'bi-check2' : 'bi-copy'" aria-hidden="true"></i>
                          {{copied===section.id+'-'+index ? t('sdk.copied') : t('sdk.copy')}}
                        </ui-button>
                      </figcaption>
                      <pre class="sdk-code" tabindex="0" :aria-label="localized(snippet.label)"><code>{{code(snippet.code)}}</code></pre>
                    </figure>
                  </li>
                </ol>
              </section>
            </div>
          </div>
        </template>
      </div>
    </section>
  `,
};
