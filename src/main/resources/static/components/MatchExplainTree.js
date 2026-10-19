/**
 * MatchExplainTree - Condition tree matching a rule in the chain (detailTree produced by backend MatchExplanationJson).
 *
 * Each group and condition indicate whether it passed or not, and the actual value obtained is listed next to the condition. Groups that failed will be expanded by default, and groups that have passed will be collapsed by default;
 * Conditions that cause the entire rule to fail are marked "Stuck here" in red. The conditions below NOT are colored by "Whether it is helpful to the overall situation".
 */
const MatchExplainNode = {
  name: 'match-explain-node',
  props: {
    node: { type: Object, required: true },
    negated: { type: Boolean, default: false },
    // All upper layers of this node have failed (its failure is one of the reasons why the entire rule has failed)
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
      // The single value of the structured condition is quoted (multiple values are a list), which is consistent with the old format condition and changed to unquoted.
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
