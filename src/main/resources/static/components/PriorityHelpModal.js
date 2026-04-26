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
  emits: ['close', 'update:helpTab'],
  inject: ['t'],
  setup(props, { emit }) {
    const { ref, computed, watch, nextTick, onBeforeUnmount, inject } = Vue;
    const t = inject('t');
    const activeTocId = ref('');
    const helpContentRef = ref(null);
    let observer = null;

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
        nextTick(() => setupObserver());
      } else if (observer) {
        observer.disconnect();
      }
    });

    onBeforeUnmount(() => {
      if (observer) { observer.disconnect(); }
    });

    const scrollTo = (id) => {
      const container = helpContentRef.value;
      if (!container) { return; }
      const el = container.querySelector('#' + id);
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'start' });
        activeTocId.value = id;
      }
    };

    return { tocItems, activeTocId, helpContentRef, scrollTo, t };
  },
  template: /* html */`
  <div class="modal-overlay" v-if="show" >
    <div class="modal-box help-modal help-fullscreen">
      <div class="modal-header">
        <h3><i class="bi bi-lightbulb"></i> {{t('help.title')}}</h3>
        <button class="close-btn" @click="$emit('close')" aria-label="Close"><i class="bi bi-x-lg"></i></button>
      </div>
      <div class="modal-body" style="padding:0;flex:1;overflow:hidden;display:flex;flex-direction:column">
        <div class="help-tabs">
          <button :class="{active:helpTab==='start'}" @click="$emit('update:helpTab','start')"><i class="bi bi-rocket-takeoff"></i> {{t('help.tabStart')}}</button>
          <button :class="{active:helpTab==='http'}" @click="$emit('update:helpTab','http')"><i class="bi bi-globe"></i> HTTP</button>
          <button :class="{active:helpTab==='jms'}" @click="$emit('update:helpTab','jms')"><i class="bi bi-hdd-network"></i> JMS</button>
          <button :class="{active:helpTab==='condition'}" @click="$emit('update:helpTab','condition')"><i class="bi bi-funnel"></i> {{t('help.tabCondition')}}</button>
          <button :class="{active:helpTab==='advanced'}" @click="$emit('update:helpTab','advanced')"><i class="bi bi-gear"></i> {{t('help.tabAdvanced')}}</button>
        </div>
        <div class="help-layout">
          <nav class="help-toc" v-if="tocItems.length" aria-label="Table of contents">
            <div
              v-for="item in tocItems" :key="item.id"
              class="help-toc-item" :class="{active: activeTocId === item.id}"
              @click="scrollTo(item.id)"
              :title="item.label"
            >{{item.label}}</div>
          </nav>
          <div class="help-content" ref="helpContentRef" tabindex="0" role="region" aria-label="Help content">
          <!-- 快速開始 -->
          <div v-if="helpTab==='start'">
            <div class="help-section" id="start-what">
              <h4>{{t('help.whatIsEcho')}}</h4>
              <p>{{t('help.whatIsEchoDesc')}}</p>
              <pre class="help-diagram">┌──────────┐      ┌─────────────┐      ┌──────────┐
│ Your App │─────▶│    Echo     │─────▶│ Real API │
│          │      │ Mock Server │      │ (Proxy)  │
└──────────┘      └─────────────┘      └──────────┘
                         │
                  Match Rule?
                  ├─ Yes → Return Mock
                  └─ No  → Forward</pre>
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
              <p v-html="'<strong>' + t('help.jmsTest') + '</strong>' + '：' + t('help.jmsTestDesc')"></p>
            </div>
            <div class="help-section" id="start-response">
              <h4>{{t('help.responseManagement')}}</h4>
              <p>{{t('help.responseManagementDesc')}}</p>
              <pre class="help-diagram">┌──────────┐     ┌──────────┐
│  Rule A  │────▶│          │
├──────────┤     │ Response │
│  Rule B  │────▶│ (Shared) │
├──────────┤     │          │
│  Rule C  │────▶│          │
└──────────┘     └──────────┘</pre>
              <ul>
                <li><strong>{{t('modal.createNewResponse')}}</strong>：{{t('help.createNewResponseDesc')}}</li>
                <li><strong>{{t('modal.useExisting')}}</strong>：{{t('help.useExistingResponseDesc')}}</li>
                <li><strong>{{t('responses.title')}}</strong>：{{t('help.responsePageDesc')}}</li>
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
              <pre class="help-code">// Tag format (in rule editor)
env:prod
team:payment
version:v2</pre>
            </div>
            <div class="help-section" id="start-protect">
              <h4>{{t('help.ruleProtection')}}</h4>
              <p>{{t('help.ruleProtectionDesc')}}</p>
            </div>
          </div>
          <!-- HTTP -->
          <div v-if="helpTab==='http'">
            <div class="help-section" id="http-flow">
              <h4>{{t('help.httpRequestFlow')}}</h4>
              <pre class="help-diagram">┌─────────────────────────────────────────────┐
│ curl http://localhost:8080/mock/api/users   │
│      -H "X-Original-Host: api.example.com"  │
└─────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────┐
│ Echo Parse Request                          │
│ • Host: api.example.com                     │
│ • Method: GET                               │
│ • Path: /api/users                          │
└─────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────┐
│ Find Matching Rule (by priority)            │
│ 1. Match Host + Method + Path               │
│ 2. Check Conditions (Body/Query/Header)     │
│ 3. Fallback to unconditional rule           │
└─────────────────────────────────────────────┘
                        │
        ┌───────────────┴───────────────┐
        ▼                               ▼
   Rule Found                      No Match
   Return Mock                     Forward to Host</pre>
            </div>
            <div class="help-section" id="http-fields">
              <h4>{{t('help.httpRuleFields')}}</h4>
              <table class="help-table">
                <tr><td><strong>{{t('modal.targetHost')}}</strong></td><td v-html="t('help.fieldTargetHost')"></td></tr>
                <tr><td><strong>{{t('modal.method')}}</strong></td><td>{{t('help.fieldMethod')}}</td></tr>
                <tr><td><strong>{{t('modal.pathLabel')}}</strong></td><td v-html="t('help.fieldPath')"></td></tr>
                <tr><td><strong>{{t('modal.statusCode')}}</strong></td><td>{{t('help.fieldStatus')}}</td></tr>
                <tr><td><strong>{{t('modal.delay')}}</strong></td><td>{{t('help.fieldDelay')}}</td></tr>
                <tr><td><strong>Headers</strong></td><td v-html="t('help.fieldHeaders')"></td></tr>
              </table>
            </div>
          </div>
          <!-- JMS -->
          <div v-if="helpTab==='jms'">
            <div class="help-section" id="jms-flow">
              <h4>{{t('help.jmsMessageFlow')}}</h4>
              <pre class="help-diagram">┌──────────┐      ┌──────────┐      ┌──────────┐
│ Your App │─────▶│   Echo   │─────▶│   ESB    │
│          │ JMS  │ (Artemis)│ JMS  │(TIBCO/MQ)│
└──────────┘      └──────────┘      └──────────┘
                        │
                 Match Rule?
                 ├─ Yes → Reply Mock to ReplyTo
                 └─ No  → Forward to ESB</pre>
            </div>
            <div class="help-section" id="jms-fields">
              <h4>{{t('help.jmsRuleFields')}}</h4>
              <table class="help-table">
                <tr><td><strong>Queue</strong></td><td v-html="t('help.jmsFieldQueue')"></td></tr>
                <tr><td><strong>Reply Queue</strong></td><td>{{t('help.jmsFieldReplyQueue')}}</td></tr>
                <tr><td><strong>{{t('modal.delay')}}</strong></td><td>{{t('help.jmsFieldDelay')}}</td></tr>
              </table>
            </div>
            <div class="help-section" id="jms-conn">
              <h4>{{t('help.jmsConnectionInfo')}}</h4>
              <pre class="help-code">// Java Connection Example
ConnectionFactory cf = new ActiveMQConnectionFactory(
    "tcp://localhost:61616"
);
Connection conn = cf.createConnection();
Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
Queue queue = session.createQueue("ORDER.REQUEST");
MessageProducer producer = session.createProducer(queue);</pre>
            </div>
            <div class="help-section" id="jms-reply">
              <h4>{{t('help.jmsRequestReply')}}</h4>
              <pre class="help-diagram">┌──────────┐  1. Send Request ┌──────────┐
│  Client  │──────────────▶│   Echo   │
│          │  Queue: REQ   │          │
│          │◀──────────────│          │
└──────────┘  2. Reply       └──────────┘
              Queue: ReplyTo</pre>
              <p class="sub-info">{{t('help.jmsReplyNote')}}</p>
            </div>
          </div>
          <!-- 條件匹配 -->
          <div v-if="helpTab==='condition'">
            <div class="help-section" id="cond-overview">
              <h4>{{t('help.condOverview')}}</h4>
              <p v-html="t('help.condOverviewDesc')"></p>
              <table class="help-table">
                <tr><td><span class="cond-tag">Body</span></td><td v-html="t('help.condBody')"></td></tr>
                <tr><td><span class="cond-tag query">Query</span></td><td v-html="t('help.condQuery')"></td></tr>
                <tr><td><span class="cond-tag header">Header</span></td><td v-html="t('help.condHeader')"></td></tr>
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
              <pre class="help-code">// Request Body
{"type": "VIP", "name": "John", "age": 30}

// Conditions
type=VIP             ✓ {{t('help.exSimpleMatch')}}
name=John            ✓ {{t('help.exSimpleMatch')}}
type=VIP;name=John   ✓ {{t('help.exAndMatch')}}
type!=NORMAL         ✓ {{t('help.exNotMatch')}}
name*=oh             ✓ {{t('help.exContainsMatch')}}
name~=^J.*n$         ✓ {{t('help.exRegexMatch')}}</pre>
            </div>
            <div class="help-section" id="cond-json-nested">
              <h4>{{t('help.condJsonNested')}}</h4>
              <pre class="help-code">// Request Body
{"user": {"name": "John", "address": {"city": "Taipei"}},
 "items": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}]}

// Nested Object
user.name=John              ✓
user.address.city=Taipei    ✓

// Array Index
items[0].id=1               ✓
items[1].name=B             ✓</pre>
            </div>
            <div class="help-section" id="cond-xml">
              <h4>{{t('help.condXml')}}</h4>
              <pre class="help-code">// Request Body
&lt;order id="123"&gt;
  &lt;customer&gt;&lt;name&gt;John&lt;/name&gt;&lt;/customer&gt;
  &lt;items&gt;
    &lt;item sku="A001"&gt;Widget&lt;/item&gt;
  &lt;/items&gt;
&lt;/order&gt;

// XPath Conditions
//name=John              ✓ {{t('help.exXpathAnywhere')}}
/order/@id=123           ✓ {{t('help.exXpathAttr')}}
//customer/name=John     ✓ {{t('help.exXpathPath')}}
//item/@sku=A001         ✓ {{t('help.exXpathAttr')}}</pre>
            </div>
            <div class="help-section" id="cond-query">
              <h4>{{t('help.condQuery')}}</h4>
              <pre class="help-code">// Request: GET /api/users?page=1&size=20&sort=name

// Query Conditions
page=1               ✓
size=20              ✓
sort=name            ✓
page=1;size=20       ✓ {{t('help.exAndMatch')}}</pre>
            </div>
            <div class="help-section" id="cond-header">
              <h4>{{t('help.condHeaderTitle')}}</h4>
              <pre class="help-code">// Request Headers
Authorization: Bearer eyJhbGciOi...
Content-Type: application/json
X-Request-Id: abc-123

// Header Conditions (case-insensitive)
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
          <div v-if="helpTab==='advanced'">
            <div class="help-section" id="adv-template">
              <h4>{{t('help.responseTemplate')}}</h4>
              <p v-html="t('help.responseTemplateDesc')"></p>
              <pre class="help-code" v-pre>// Example: Dynamic Response
{
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
              <pre class="help-code" v-pre>// Example: Fake User Profile
{
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
              <pre class="help-code" v-pre>// Conditional
{{#if (eq request.method 'POST')}}
  {"action": "created"}
{{else}}
  {"action": "retrieved"}
{{/if}}

// Loop
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
              <pre class="help-diagram">┌─────────────────────────────────────────┐
│ Rule Sorting (higher = higher priority) │
├─────────────────────────────────────────┤
│ 1. priority field value                 │
│ 2. Exact path > Wildcard (*)            │
│ 3. Has targetHost > No targetHost       │
│ 4. Created time (newer first)           │
└─────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────┐
│ Matching Flow                           │
│ 1. Check rules in sorted order          │
│ 2. Has condition → match → return       │
│ 3. No condition → mark as fallback      │
│ 4. Return fallback if no match          │
└─────────────────────────────────────────┘</pre>
              <p class="sub-info">{{t('help.wildcardNote')}}</p>
            </div>
            <div class="help-section" id="adv-cache">
              <h4>{{t('help.multiInstanceCache')}}</h4>
              <pre class="help-diagram">┌────────────┐                  ┌────────────┐
│ Instance A │                  │ Instance B │
│ (Caffeine) │                  │ (Caffeine) │
└─────┬──────┘                  └─────┬──────┘
      │ Update Rule                   │
      ▼                               │
┌─────────────────────────────────────────────┐
│           cache_events table                │
│ INSERT: RULE_CHANGED @ 20:05:01             │
└─────────────────────────────────────────────┘
                                      │
                        Poll every 5s ┘
                                      ▼
                               Found event
                               Clear cache</pre>
              <p class="sub-info" v-html="t('help.cacheIntervalNote')"></p>
            </div>
            <div class="help-section" id="adv-sse">
              <h4>{{t('help.sseStreaming')}}</h4>
              <p v-html="t('help.sseStreamingDesc')"></p>
              <pre class="help-code">// SSE Event Sequence (configured in rule editor)
Event 1: {"status": "processing"}  (delay: 0ms)
Event 2: {"progress": 50}          (delay: 1000ms)
Event 3: {"status": "done"}        (delay: 2000ms)</pre>
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
