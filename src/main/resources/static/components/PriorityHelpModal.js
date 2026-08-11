/**
 * PriorityHelpModal - 優先順序說明 Modal
 * 顯示 Echo Mock Server 的使用說明，包含快速開始、HTTP、JMS、進階等分頁。
 * 左側顯示目錄 (TOC)，可快速跳轉到特定章節。
 */
const PriorityHelpModal = {
  props: {
    show: Boolean,
    helpTab: String,
  },
  emits: ['close', 'start-tour', 'update:helpTab'],
  inject: ['t'],
  setup(props, { emit }) {
    const { ref, computed, watch, nextTick, onBeforeUnmount, inject } = Vue;
    const t = inject('t');
    const activeTocId = ref('');
    const helpContentRef = ref(null);
    const helpDialogRef = ref(null);
    const helpOverlayRef = ref(null);
    const closeButtonRef = ref(null);
    const helpTabsRef = ref(null);
    const tabOrder = ['start', 'http', 'jms', 'condition', 'advanced'];
    let observer = null;
    let previousFocus = null;
    let inertSiblings = [];

    const tocItems = computed(() => {
      switch (props.helpTab) {
        case 'start': return [
          { id: 'start-what', label: t('help.whatIsEcho') },
          { id: 'start-create', label: t('help.createFirstRule') },
          { id: 'start-test', label: t('help.testYourRule') },
          { id: 'start-response', label: t('help.responseManagement') },
          { id: 'start-keyboard', label: t('help.keyboardShortcuts') },
          { id: 'start-tags', label: t('help.tagsAndGroups') },
          { id: 'start-protect', label: t('help.ruleProtection') },
        ];
        case 'http': return [
          { id: 'http-flow', label: t('help.httpRequestFlow') },
          { id: 'http-fields', label: t('help.httpRuleFields') },
        ];
        case 'jms': return [
          { id: 'jms-flow', label: t('help.jmsMessageFlow') },
          { id: 'jms-fields', label: t('help.jmsRuleFields') },
          { id: 'jms-conn', label: t('help.jmsConnectionInfo') },
          { id: 'jms-reply', label: t('help.jmsRequestReply') },
        ];
        case 'condition': return [
          { id: 'cond-overview', label: t('help.condOverview') },
          { id: 'cond-operators', label: t('help.condOperatorsTitle') },
          { id: 'cond-json-basic', label: t('help.condJsonBasic') },
          { id: 'cond-json-nested', label: t('help.condJsonNested') },
          { id: 'cond-xml', label: t('help.condXml') },
          { id: 'cond-query', label: t('help.condQuery') },
          { id: 'cond-header', label: t('help.condHeaderTitle') },
          { id: 'cond-autodetect', label: t('help.condAutoDetect') },
        ];
        case 'advanced': return [
          { id: 'adv-template', label: t('help.responseTemplate') },
          { id: 'adv-faker', label: t('help.fakerHelpers') },
          { id: 'adv-condloop', label: t('help.conditionAndLoop') },
          { id: 'adv-priority', label: t('help.rulePriority') },
          { id: 'adv-cache', label: t('help.multiInstanceCache') },
          { id: 'adv-sse', label: t('help.sseStreaming') },
          { id: 'adv-fault', label: t('help.faultInjection') },
          { id: 'adv-tips', label: t('help.tips') },
        ];
        default: return [];
      }
    });

    const setupObserver = () => {
      if (observer) {
        observer.disconnect();
      }
      const container = helpContentRef.value;
      if (!container) { return; }
      observer = new IntersectionObserver((entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            activeTocId.value = entry.target.id;
            break;
          }
        }
      }, { root: container, rootMargin: '0px 0px -60% 0px', threshold: 0.1 });
      const sections = container.querySelectorAll('.help-section[id]');
      sections.forEach(s => observer.observe(s));
    };

    watch(() => props.helpTab, () => {
      activeTocId.value = '';
      nextTick(() => {
        setupObserver();
        if (helpContentRef.value) {
          helpContentRef.value.scrollTop = 0;
        }
      });
    });

    watch(() => props.show, (val) => {
      if (val) {
        previousFocus = document.activeElement;
        document.addEventListener('keydown', handleModalKeydown);
        nextTick(() => {
          setupObserver();
          makeBackgroundInert();
          closeButtonRef.value?.focus();
        });
      } else if (observer) {
        observer.disconnect();
        document.removeEventListener('keydown', handleModalKeydown);
        restoreBackground();
        previousFocus?.focus?.();
        previousFocus = null;
      }
    });

    onBeforeUnmount(() => {
      if (observer) { observer.disconnect(); }
      document.removeEventListener('keydown', handleModalKeydown);
      restoreBackground();
    });

    const makeBackgroundInert = () => {
      const overlay = helpOverlayRef.value;
      const parent = overlay?.parentElement;
      if (!parent) { return; }
      inertSiblings = Array.from(parent.children).filter(node => node !== overlay).map(node => ({
        node,
        inert: node.inert,
        ariaHidden: node.getAttribute('aria-hidden')
      }));
      inertSiblings.forEach(({ node }) => {
        node.inert = true;
        node.setAttribute('aria-hidden', 'true');
      });
    };

    const restoreBackground = () => {
      inertSiblings.forEach(({ node, inert, ariaHidden }) => {
        node.inert = inert;
        if (ariaHidden == null) { node.removeAttribute('aria-hidden'); }
        else { node.setAttribute('aria-hidden', ariaHidden); }
      });
      inertSiblings = [];
    };

    const handleModalKeydown = event => {
      if (!props.show) { return; }
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        emit('close');
        return;
      }
      if (event.key !== 'Tab') { return; }
      const focusable = Array.from(helpDialogRef.value?.querySelectorAll('button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])') || []);
      if (!focusable.length) { return; }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    const setHelpTab = tab => emit('update:helpTab', tab);

    const moveHelpTab = event => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) { return; }
      event.preventDefault();
      const current = Math.max(0, tabOrder.indexOf(props.helpTab));
      const next = event.key === 'Home' ? 0
        : event.key === 'End' ? tabOrder.length - 1
        : (current + (event.key === 'ArrowRight' ? 1 : -1) + tabOrder.length) % tabOrder.length;
      emit('update:helpTab', tabOrder[next]);
      nextTick(() => helpTabsRef.value?.querySelector('[role="tab"][aria-selected="true"]')?.focus());
    };

    const scrollTo = (id) => {
      const container = helpContentRef.value;
      if (!container) { return; }
      const el = container.querySelector('#' + id);
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'start' });
        activeTocId.value = id;
      }
    };

    return { tocItems, activeTocId, helpContentRef, helpDialogRef, helpOverlayRef, closeButtonRef, helpTabsRef, scrollTo, setHelpTab, moveHelpTab, t };
  },
  template: /* html */`
  <div ref="helpOverlayRef" class="modal-overlay" v-if="show" @click.self="$emit('close')">
    <div ref="helpDialogRef" class="modal-box help-modal help-fullscreen workspace-modal" role="dialog" aria-modal="true" aria-labelledby="helpModalTitle">
      <div class="modal-header">
        <div class="modal-heading"><span class="modal-heading-icon"><i class="bi bi-book" aria-hidden="true"></i></span><h3 id="helpModalTitle">{{t('help.title')}}</h3></div>
        <div class="modal-header-actions">
          <button type="button" class="btn btn-sm btn-secondary" @click="$emit('start-tour')"><i class="bi bi-signpost-split" aria-hidden="true"></i>{{t('help.startTour')}}</button>
          <button ref="closeButtonRef" type="button" class="close-btn" @click="$emit('close')" :aria-label="t('modal.cancel')"><i class="bi bi-x-lg" aria-hidden="true"></i></button>
        </div>
      </div>
      <div class="modal-body help-modal-body">
        <div ref="helpTabsRef" class="help-tabs" role="tablist" :aria-label="t('help.sections')">
          <button type="button" id="help-tab-start" role="tab" :aria-selected="helpTab==='start'" :tabindex="helpTab==='start'?0:-1" aria-controls="help-panel-start" :class="{active:helpTab==='start'}" @click="setHelpTab('start')" @keydown="moveHelpTab"><i class="bi bi-rocket-takeoff" aria-hidden="true"></i>{{t('help.tabStart')}}</button>
          <button type="button" id="help-tab-http" role="tab" :aria-selected="helpTab==='http'" :tabindex="helpTab==='http'?0:-1" aria-controls="help-panel-http" :class="{active:helpTab==='http'}" @click="setHelpTab('http')" @keydown="moveHelpTab"><i class="bi bi-globe" aria-hidden="true"></i>HTTP</button>
          <button type="button" id="help-tab-jms" role="tab" :aria-selected="helpTab==='jms'" :tabindex="helpTab==='jms'?0:-1" aria-controls="help-panel-jms" :class="{active:helpTab==='jms'}" @click="setHelpTab('jms')" @keydown="moveHelpTab"><i class="bi bi-hdd-network" aria-hidden="true"></i>JMS</button>
          <button type="button" id="help-tab-condition" role="tab" :aria-selected="helpTab==='condition'" :tabindex="helpTab==='condition'?0:-1" aria-controls="help-panel-condition" :class="{active:helpTab==='condition'}" @click="setHelpTab('condition')" @keydown="moveHelpTab"><i class="bi bi-funnel" aria-hidden="true"></i>{{t('help.tabCondition')}}</button>
          <button type="button" id="help-tab-advanced" role="tab" :aria-selected="helpTab==='advanced'" :tabindex="helpTab==='advanced'?0:-1" aria-controls="help-panel-advanced" :class="{active:helpTab==='advanced'}" @click="setHelpTab('advanced')" @keydown="moveHelpTab"><i class="bi bi-gear" aria-hidden="true"></i>{{t('help.tabAdvanced')}}</button>
        </div>
        <div class="help-layout">
          <nav class="help-toc" v-if="tocItems.length" :aria-label="t('help.tableOfContents')">
            <button type="button"
              v-for="item in tocItems" :key="item.id"
              class="help-toc-item" :class="{active: activeTocId === item.id}"
              @click="scrollTo(item.id)"
              :aria-current="activeTocId === item.id ? 'location' : null"
              :title="item.label"
            >{{item.label}}</button>
          </nav>
          <div class="help-content" ref="helpContentRef" tabindex="0" role="region" :aria-label="t('help.content')">
          <!-- 快速開始 -->
          <div v-if="helpTab==='start'" id="help-panel-start" role="tabpanel" aria-labelledby="help-tab-start">
            <div class="help-section" id="start-what">
              <h4>{{t('help.whatIsEcho')}}</h4>
              <p>{{t('help.whatIsEchoDesc')}}</p>
              <pre class="help-diagram">{{t('help.diagramEchoFlow')}}</pre>
            </div>
            <div class="help-section" id="start-create">
              <h4>{{t('help.createFirstRule')}}</h4>
              <ol>
                <li v-html="t('help.step1')"></li>
                <li>{{t('help.step2')}}</li>
                <li v-html="t('help.step3')"></li>
                <li>{{t('help.step4')}}</li>
                <li>{{t('help.step5')}}</li>
              </ol>
            </div>
            <div class="help-section" id="start-test">
              <h4>{{t('help.testYourRule')}}</h4>
              <p><strong>{{t('help.httpTest')}}</strong></p>
              <pre class="help-code">curl http://localhost:8080/mock/api/users \\
  -H "X-Original-Host: api.example.com"</pre>
              <p v-html="'<strong>' + t('help.jmsTest') + '</strong>' + t('help.labelSeparator') + t('help.jmsTestDesc')"></p>
            </div>
            <div class="help-section" id="start-response">
              <h4>{{t('help.responseManagement')}}</h4>
              <p>{{t('help.responseManagementDesc')}}</p>
              <pre class="help-diagram">{{t('help.diagramSharedResponse')}}</pre>
              <ul>
                <li><strong>{{t('modal.createNewResponse')}}</strong>{{t('help.labelSeparator')}}{{t('help.createNewResponseDesc')}}</li>
                <li><strong>{{t('modal.useExisting')}}</strong>{{t('help.labelSeparator')}}{{t('help.useExistingResponseDesc')}}</li>
                <li><strong>{{t('responses.title')}}</strong>{{t('help.labelSeparator')}}{{t('help.responsePageDesc')}}</li>
              </ul>
            </div>
            <div class="help-section" id="start-keyboard">
              <h4>{{t('help.keyboardShortcuts')}}</h4>
              <table class="help-table">
                <tbody>
                  <tr><td><kbd>/</kbd></td><td>{{t('help.kbFocusSearch')}}</td></tr>
                  <tr><td><kbd>←</kbd> <kbd>→</kbd></td><td>{{t('help.kbPrevNext')}}</td></tr>
                  <tr><td><kbd>N</kbd></td><td>{{t('help.kbNewRule')}}</td></tr>
                  <tr><td><kbd>Esc</kbd></td><td>{{t('help.kbCloseModal')}}</td></tr>
                </tbody>
              </table>
              <p class="sub-info">{{t('help.kbHint')}}</p>
            </div>
            <div class="help-section" id="start-tags">
              <h4>{{t('help.tagsAndGroups')}}</h4>
              <p>{{t('help.tagsAndGroupsDesc')}}</p>
              <pre class="help-code">{{t('help.tagExample')}}</pre>
            </div>
            <div class="help-section" id="start-protect">
              <h4>{{t('help.ruleProtection')}}</h4>
              <p>{{t('help.ruleProtectionDesc')}}</p>
            </div>
          </div>
          <!-- HTTP -->
          <div v-if="helpTab==='http'" id="help-panel-http" role="tabpanel" aria-labelledby="help-tab-http">
            <div class="help-section" id="http-flow">
              <h4>{{t('help.httpRequestFlow')}}</h4>
              <pre class="help-diagram">{{t('help.diagramHttpFlow')}}</pre>
            </div>
            <div class="help-section" id="http-fields">
              <h4>{{t('help.httpRuleFields')}}</h4>
              <table class="help-table">
                <tr><td><strong>{{t('modal.sourceHostMatch')}}</strong></td><td v-html="t('help.fieldTargetHost')"></td></tr>
                <tr><td><strong>{{t('modal.method')}}</strong></td><td>{{t('help.fieldMethod')}}</td></tr>
                <tr><td><strong>{{t('modal.pathLabel')}}</strong></td><td v-html="t('help.fieldPath')"></td></tr>
                <tr><td><strong>{{t('modal.statusCode')}}</strong></td><td>{{t('help.fieldStatus')}}</td></tr>
                <tr><td><strong>{{t('modal.delay')}}</strong></td><td>{{t('help.fieldDelay')}}</td></tr>
                <tr><td><strong>{{t('help.headersLabel')}}</strong></td><td v-html="t('help.fieldHeaders')"></td></tr>
              </table>
            </div>
          </div>
          <!-- JMS -->
          <div v-if="helpTab==='jms'" id="help-panel-jms" role="tabpanel" aria-labelledby="help-tab-jms">
            <div class="help-section" id="jms-flow">
              <h4>{{t('help.jmsMessageFlow')}}</h4>
              <pre class="help-diagram">{{t('help.diagramJmsFlow')}}</pre>
            </div>
            <div class="help-section" id="jms-fields">
              <h4>{{t('help.jmsRuleFields')}}</h4>
              <table class="help-table">
                <tr><td><strong>{{t('help.queueLabel')}}</strong></td><td v-html="t('help.jmsFieldQueue')"></td></tr>
                <tr><td><strong>{{t('help.replyQueueLabel')}}</strong></td><td>{{t('help.jmsFieldReplyQueue')}}</td></tr>
                <tr><td><strong>{{t('modal.delay')}}</strong></td><td>{{t('help.jmsFieldDelay')}}</td></tr>
              </table>
            </div>
            <div class="help-section" id="jms-conn">
              <h4>{{t('help.jmsConnectionInfo')}}</h4>
              <pre class="help-code">ConnectionFactory cf = new ActiveMQConnectionFactory(
    "tcp://localhost:61616"
);
Connection conn = cf.createConnection();
Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
Queue queue = session.createQueue("ORDER.REQUEST");
MessageProducer producer = session.createProducer(queue);</pre>
            </div>
            <div class="help-section" id="jms-reply">
              <h4>{{t('help.jmsRequestReply')}}</h4>
              <pre class="help-diagram">{{t('help.diagramJmsReply')}}</pre>
              <p class="sub-info">{{t('help.jmsReplyNote')}}</p>
            </div>
          </div>
          <!-- 條件匹配 -->
          <div v-if="helpTab==='condition'" id="help-panel-condition" role="tabpanel" aria-labelledby="help-tab-condition">
            <div class="help-section" id="cond-overview">
              <h4>{{t('help.condOverview')}}</h4>
              <p v-html="t('help.condOverviewDesc')"></p>
              <table class="help-table">
                <tr><td><span class="cond-tag">{{t('modal.condFieldBody')}}</span></td><td v-html="t('help.condBody')"></td></tr>
                <tr><td><span class="cond-tag query">{{t('modal.condFieldQuery')}}</span></td><td v-html="t('help.condQuery')"></td></tr>
                <tr><td><span class="cond-tag header">{{t('modal.condFieldHeader')}}</span></td><td v-html="t('help.condHeader')"></td></tr>
              </table>
              <p class="sub-info">{{t('help.jmsCondNote')}}</p>
            </div>
            <div class="help-section" id="cond-operators">
              <h4>{{t('help.condOperatorsTitle')}}</h4>
              <table class="help-table">
                <tr><td><code>=</code></td><td>{{t('help.opEquals')}}</td></tr>
                <tr><td><code>!=</code></td><td>{{t('help.opNotEquals')}}</td></tr>
                <tr><td><code>*=</code></td><td>{{t('help.opContains')}}</td></tr>
                <tr><td><code>~=</code></td><td>{{t('help.opRegex')}}</td></tr>
                <tr><td><code>;</code></td><td>{{t('help.opAnd')}}</td></tr>
              </table>
            </div>
            <div class="help-section" id="cond-json-basic">
              <h4>{{t('help.condJsonBasic')}}</h4>
              <pre class="help-code">// {{t('help.requestBodyLabel')}}
{"type": "VIP", "name": "John", "age": 30}

// {{t('help.conditionsLabel')}}
type=VIP             ✓ {{t('help.exSimpleMatch')}}
name=John            ✓ {{t('help.exSimpleMatch')}}
type=VIP;name=John   ✓ {{t('help.exAndMatch')}}
type!=NORMAL         ✓ {{t('help.exNotMatch')}}
name*=oh             ✓ {{t('help.exContainsMatch')}}
name~=^J.*n$         ✓ {{t('help.exRegexMatch')}}</pre>
            </div>
            <div class="help-section" id="cond-json-nested">
              <h4>{{t('help.condJsonNested')}}</h4>
              <pre class="help-code">// {{t('help.requestBodyLabel')}}
{"user": {"name": "John", "address": {"city": "Taipei"}},
 "items": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}]}

// {{t('help.nestedObjectLabel')}}
user.name=John              ✓
user.address.city=Taipei    ✓

// {{t('help.arrayIndexLabel')}}
items[0].id=1               ✓
items[1].name=B             ✓</pre>
            </div>
            <div class="help-section" id="cond-xml">
              <h4>{{t('help.condXml')}}</h4>
              <pre class="help-code">// {{t('help.requestBodyLabel')}}
&lt;order id="123"&gt;
  &lt;customer&gt;&lt;name&gt;John&lt;/name&gt;&lt;/customer&gt;
  &lt;items&gt;
    &lt;item sku="A001"&gt;Widget&lt;/item&gt;
  &lt;/items&gt;
&lt;/order&gt;

// {{t('help.xpathConditionsLabel')}}
//name=John              ✓ {{t('help.exXpathAnywhere')}}
/order/@id=123           ✓ {{t('help.exXpathAttr')}}
//customer/name=John     ✓ {{t('help.exXpathPath')}}
//item/@sku=A001         ✓ {{t('help.exXpathAttr')}}</pre>
            </div>
            <div class="help-section" id="cond-query">
              <h4>{{t('help.condQuery')}}</h4>
              <pre class="help-code">// {{t('help.requestLabel')}}: GET /api/users?page=1&size=20&sort=name

// {{t('help.queryConditionsLabel')}}
page=1               ✓
size=20              ✓
sort=name            ✓
page=1;size=20       ✓ {{t('help.exAndMatch')}}</pre>
            </div>
            <div class="help-section" id="cond-header">
              <h4>{{t('help.condHeaderTitle')}}</h4>
              <pre class="help-code">// {{t('help.requestHeadersLabel')}}
Authorization: Bearer eyJhbGciOi...
Content-Type: application/json
X-Request-Id: abc-123

// {{t('help.headerConditionsLabel')}}
Content-Type=application/json     ✓
Authorization*=Bearer             ✓ {{t('help.exContainsMatch')}}
X-Request-Id~=^[a-z]+-\\d+$       ✓ {{t('help.exRegexMatch')}}
Authorization!=null               ✓ {{t('help.exNotMatch')}}</pre>
            </div>
            <div class="help-section" id="cond-autodetect">
              <h4>{{t('help.condAutoDetect')}}</h4>
              <p v-html="t('help.condAutoDetectDesc')"></p>
            </div>
          </div>
          <!-- 進階 -->
          <div v-if="helpTab==='advanced'" id="help-panel-advanced" role="tabpanel" aria-labelledby="help-tab-advanced">
            <div class="help-section" id="adv-template">
              <h4>{{t('help.responseTemplate')}}</h4>
              <p v-html="t('help.responseTemplateDesc')"></p>
              <pre class="help-code" v-pre>{
  "requestId": "{{randomValue type='UUID'}}",
  "timestamp": "{{now format='yyyy-MM-dd HH:mm:ss'}}",
  "user": "{{jsonPath request.body '$.username'}}",
  "path": "{{request.path}}",
  "query": "{{request.query.id}}"
}</pre>
              <table class="help-table">
                <tr><td><code v-pre>{{request.path}}</code></td><td>{{t('help.templateRequestPath')}}</td></tr>
                <tr><td><code v-pre>{{request.method}}</code></td><td>{{t('help.templateRequestMethod')}}</td></tr>
                <tr><td><code v-pre>{{request.query.xxx}}</code></td><td>{{t('help.templateRequestQuery')}}</td></tr>
                <tr><td><code v-pre>{{request.headers.xxx}}</code></td><td>{{t('help.templateRequestHeaders')}}</td></tr>
                <tr><td><code v-pre>{{{request.body}}}</code></td><td>{{t('help.templateRequestBody')}}</td></tr>
                <tr><td><code v-pre>{{now format='yyyy-MM-dd'}}</code></td><td>{{t('help.templateNow')}}</td></tr>
                <tr><td><code v-pre>{{randomValue type='UUID'}}</code></td><td>{{t('help.templateRandomUuid')}}</td></tr>
                <tr><td><code v-pre>{{jsonPath request.body '$.user.name'}}</code></td><td>{{t('help.templateJsonPath')}}</td></tr>
                <tr><td><code v-pre>{{xPath request.body '/root/name'}}</code></td><td>{{t('help.templateXPath')}}</td></tr>
              </table>
            </div>
            <div class="help-section" id="adv-faker">
              <h4>{{t('help.fakerHelpers')}}</h4>
              <p>{{t('help.fakerHelpersDesc')}}</p>
              <pre class="help-code" v-pre>{
  "name": "{{randomFullName}}",
  "email": "{{randomEmail}}",
  "phone": "{{randomPhoneNumber}}",
  "address": "{{randomStreetAddress}}",
  "city": "{{randomCity}}",
  "country": "{{randomCountry}}",
  "age": {{randomInt min=18 max=65}}
}</pre>
              <table class="help-table">
                <tr><td><code v-pre>{{randomFirstName}}</code></td><td>{{t('help.templateRandomFirstName')}}</td></tr>
                <tr><td><code v-pre>{{randomLastName}}</code></td><td>{{t('help.templateRandomLastName')}}</td></tr>
                <tr><td><code v-pre>{{randomFullName}}</code></td><td>{{t('help.templateRandomFullName')}}</td></tr>
                <tr><td><code v-pre>{{randomEmail}}</code></td><td>{{t('help.templateRandomEmail')}}</td></tr>
                <tr><td><code v-pre>{{randomPhoneNumber}}</code></td><td>{{t('help.templateRandomPhoneNumber')}}</td></tr>
                <tr><td><code v-pre>{{randomCity}}</code></td><td>{{t('help.templateRandomCity')}}</td></tr>
                <tr><td><code v-pre>{{randomCountry}}</code></td><td>{{t('help.templateRandomCountry')}}</td></tr>
                <tr><td><code v-pre>{{randomStreetAddress}}</code></td><td>{{t('help.templateRandomStreetAddress')}}</td></tr>
                <tr><td><code v-pre>{{randomInt min=0 max=100}}</code></td><td>{{t('help.templateRandomInt')}}</td></tr>
              </table>
            </div>
            <div class="help-section" id="adv-condloop">
              <h4>{{t('help.conditionAndLoop')}}</h4>
              <pre class="help-code" v-pre>{{#if (eq request.method 'POST')}}
  {"action": "created"}
{{else}}
  {"action": "retrieved"}
{{/if}}

{"items": [
  {{#each (jsonPath request.body '$.items')}}
    {"id": {{this.id}}, "processed": true}{{#unless @last}},{{/unless}}
  {{/each}}
]}</pre>
              <table class="help-table">
                <tr><td><code v-pre>eq</code> / <code v-pre>ne</code></td><td>{{t('help.helperEqNe')}}</td></tr>
                <tr><td><code v-pre>gt</code> / <code v-pre>lt</code></td><td>{{t('help.helperGtLt')}}</td></tr>
                <tr><td><code v-pre>contains</code></td><td>{{t('help.helperContains')}}</td></tr>
                <tr><td><code v-pre>matches</code></td><td>{{t('help.helperMatches')}}</td></tr>
                <tr><td><code v-pre>split</code></td><td>{{t('help.helperSplit')}}</td></tr>
                <tr><td><code v-pre>size</code></td><td>{{t('help.helperSize')}}</td></tr>
              </table>
            </div>
            <div class="help-section" id="adv-priority">
              <h4>{{t('help.rulePriority')}}</h4>
              <pre class="help-diagram">{{t('help.diagramPriority')}}</pre>
              <p class="sub-info">{{t('help.wildcardNote')}}</p>
            </div>
            <div class="help-section" id="adv-cache">
              <h4>{{t('help.multiInstanceCache')}}</h4>
              <pre class="help-diagram">{{t('help.diagramCache')}}</pre>
              <p class="sub-info" v-html="t('help.cacheIntervalNote')"></p>
            </div>
            <div class="help-section" id="adv-sse">
              <h4>{{t('help.sseStreaming')}}</h4>
              <p v-html="t('help.sseStreamingDesc')"></p>
              <pre class="help-code">{{t('help.sseSequenceExample')}}</pre>
            </div>
            <div class="help-section" id="adv-fault">
              <h4>{{t('help.faultInjection')}}</h4>
              <p v-html="t('help.faultInjectionDesc')"></p>
              <table class="help-table">
                <tr><td><code>NONE</code></td><td>{{t('help.faultNone')}}</td></tr>
                <tr><td><code>CONNECTION_RESET</code></td><td>{{t('help.faultConnectionReset')}}</td></tr>
                <tr><td><code>EMPTY_RESPONSE</code></td><td>{{t('help.faultEmptyResponse')}}</td></tr>
              </table>
            </div>
            <div class="help-section" id="adv-tips">
              <h4>{{t('help.tips')}}</h4>
              <ul>
                <li v-html="t('help.tipWildcard')"></li>
                <li v-html="t('help.tipDelay')"></li>
                <li v-html="t('help.tipFormat')"></li>
                <li v-html="t('help.tipTest')"></li>
                <li v-html="t('help.tipTags')"></li>
                <li v-html="t('help.tipProtect')"></li>
              </ul>
            </div>
          </div>
        </div>
        </div>
      </div>
    </div>
  </div>
  `
};
