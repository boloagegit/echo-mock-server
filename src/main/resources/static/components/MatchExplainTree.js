/**
 * MatchExplainTree - 匹配鏈中一條規則的條件樹（後端 MatchExplanationJson 產生的 detailTree）。
 *
 * 每個群組與條件標示是否通過、條件旁列出實際取到的值。沒過的群組預設展開、已通過的群組收起；
 * 造成整條規則沒過的條件以紅色標示「卡在這裡」。NOT 底下的條件以「對整體是否有幫助」著色。
 */
const MatchExplainNode = {
  name: 'match-explain-node',
  props: {
    node: { type: Object, required: true },
    negated: { type: Boolean, default: false },
    // 這個節點的所有上層都沒過（它沒過就是整條規則沒過的原因之一）
    failPath: { type: Boolean, default: false }
  },
  inject: ['t'],
  data() {
    return { open: !MatchTree.explainOk(this.node, this.negated) };
  },
  computed: {
    ok() { return MatchTree.explainOk(this.node, this.negated); },
    group() { return MatchTree.isExplainGroup(this.node); },
    stuck() { return !this.ok && this.failPath; },
    childNegated() { return this.node.node === 'not' ? !this.negated : this.negated; },
    childFailPath() { return this.stuck; },
    counts() {
      const children = this.node.children || [];
      return { total: children.length, passed: children.filter(child => child.passed).length };
    },
    leaf() { return this.group ? null : MatchTree.explainLeaf(this.node); },
    opLabel() {
      const op = this.leaf && this.leaf.op;
      return op ? this.t('match.op.' + op) : '';
    },
    actualText() {
      if (this.node.missing) { return this.t('stats.matchTree.missing'); }
      if (this.node.unavailable) { return this.t('stats.matchTree.unavailable'); }
      let value = this.node.actual ?? '';
      // 結構化條件的單一值帶引號（多個值為清單），與舊格式條件一致改為不帶引號
      if (this.node.node === 'leaf' && value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
        value = value.slice(1, -1);
      }
      return value;
    },
    iconClass() {
      if (this.ok) { return 'bi-check-circle'; }
      return this.stuck ? 'bi-x-circle-fill' : 'bi-x-circle';
    }
  },
  template: `
    <li class="mx-node" :class="{'is-group':group}">
      <div class="mx-row" :class="{'is-ok':ok,'is-stuck':stuck,'is-other':!ok&&!stuck}">
        <i class="bi mx-icon" :class="iconClass" role="img" :aria-label="ok?t('stats.matchTree.passed'):t('stats.matchTree.failed')"></i>
        <button v-if="group" type="button" class="mx-group" :aria-expanded="open" @click="open=!open">
          <span class="mx-group-label">{{t('stats.matchTree.' + node.node)}}</span>
          <span v-if="node.node!=='not'" class="mx-count tabular-nums">{{t('stats.matchTree.count', counts)}}</span>
          <i class="bi mx-chevron" :class="open?'bi-chevron-down':'bi-chevron-right'" aria-hidden="true"></i>
        </button>
        <template v-else>
          <span class="mx-cond">
            <span v-if="leaf.source" class="mx-source">{{leaf.source}}</span>
            <code v-if="leaf.raw!=null" class="mx-target">{{leaf.raw}}</code>
            <template v-else>
              <code v-if="leaf.target" class="mx-target">{{leaf.target}}</code>
              <span class="mx-op">{{opLabel}}</span>
              <code v-if="leaf.value" class="mx-value">{{leaf.value}}</code>
              <span v-else-if="leaf.value===null" class="mx-muted">{{t('stats.matchTree.valueOmitted')}}</span>
            </template>
            <span v-if="stuck" class="mx-stuck">{{t('stats.matchTree.stuck')}}</span>
          </span>
          <span class="mx-actual" :class="{'is-missing':node.missing||node.unavailable}">
            <span class="mx-actual-label">{{t('stats.matchTree.actual')}}</span>{{actualText}}
          </span>
        </template>
      </div>
      <ul v-if="group && open" class="mx-children">
        <match-explain-node v-for="(child, i) in node.children" :key="i" :node="child"
          :negated="childNegated" :fail-path="childFailPath"></match-explain-node>
        <li v-if="node.truncated" class="mx-truncated">{{t('stats.matchTree.truncated')}}</li>
      </ul>
    </li>
  `
};

const MatchExplainTree = {
  props: {
    tree: { type: Object, required: true }
  },
  inject: ['t'],
  computed: {
    top() { return MatchTree.explainTopLevel(this.tree); },
    failPath() { return !this.tree.passed; },
    truncated() { return this.tree.node === 'all' && !!this.tree.truncated; }
  },
  template: `
    <ul class="mx-tree">
      <match-explain-node v-for="(node, i) in top" :key="i" :node="node" :fail-path="failPath"></match-explain-node>
      <li v-if="truncated" class="mx-truncated">{{t('stats.matchTree.truncated')}}</li>
    </ul>
  `
};
