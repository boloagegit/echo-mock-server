/**
 * MatchTreeNode - 進階條件（matchSpec）的樹狀編輯器，一個元件遞迴顯示群組與條件。
 * 節點模型與轉換見 match-tree.js；這裡直接修改傳入的節點物件（父層持有整棵樹）。
 */
const MatchTreeNode = {
    name: 'MatchTreeNode',
    props: {
        node: { type: Object, required: true },
        // 此協定可用的取值來源，例如 ['body', 'query', 'header']
        sources: { type: Array, required: true },
        depth: { type: Number, default: 0 },
        // 根節點不能刪除，也不能被包成 not 以外的東西
        root: { type: Boolean, default: false },
    },
    emits: ['remove'],
    inject: ['t'],
    setup(props) {
        const t = Vue.inject('t');
        const ops = MatchTree.OPS;
        const opLabel = op => t('match.op.' + op);
        const needsValue = op => !MatchTree.NO_VALUE_OPS.includes(op);
        const optionsFor = op => MatchTree.optionsFor(op);
        const addLeaf = () => props.node.children.push(MatchTree.leaf(props.sources[0]));
        const addGroup = () => props.node.children.push(MatchTree.group(props.node.op === 'all' ? 'any' : 'all', [MatchTree.leaf(props.sources[0])]));
        const removeChild = id => { props.node.children = props.node.children.filter(child => child.id !== id); };
        const onSourceChange = node => {
            if (node.source === 'body') {
                node.name = '';
                if (!node.selectorKind) { node.selectorKind = 'jsonPath'; node.selector = '$.'; }
            } else {
                node.selectorKind = '';
                node.selector = '';
            }
        };
        const selectorPlaceholder = kind => (kind === 'xpath' ? '//OrderType' : kind === 'field' ? 'order.type' : '$.order.type');
        const valuePlaceholder = op => (op === 'in' ? t('match.valueListPlaceholder')
            : op === 'equalToJson' ? '{"key": "value"}'
                : MatchTree.NUMERIC_OPS.includes(op) ? '1000' : t('modal.condPlaceholderValue'));
        return { t, ops, opLabel, needsValue, optionsFor, addLeaf, addGroup, removeChild, onSourceChange, selectorPlaceholder, valuePlaceholder };
    },
    template: /* html */`
    <div v-if="node.kind === 'group'" class="match-group" :class="{'is-root': root, 'is-negated': node.negate}" :data-depth="depth"
         role="group" :aria-label="node.op === 'all' ? t('match.groupAll') : t('match.groupAny')">
        <div class="match-group__head">
            <button type="button" class="match-not" :class="{'is-on': node.negate}" :aria-pressed="node.negate ? 'true' : 'false'"
                    :title="t('match.notHint')" @click="node.negate = !node.negate">NOT</button>
            <select class="form-control match-group__op" v-model="node.op" :aria-label="t('match.groupOperator')">
                <option value="all">{{t('match.groupAll')}}</option>
                <option value="any">{{t('match.groupAny')}}</option>
            </select>
            <span class="match-group__hint">{{node.op === 'all' ? t('match.groupAllHint') : t('match.groupAnyHint')}}</span>
            <button v-if="!root" type="button" class="cond-remove ms-auto" @click="$emit('remove', node.id)"
                    :aria-label="t('match.removeGroup')" :title="t('match.removeGroup')"><i class="bi bi-x" aria-hidden="true"></i></button>
        </div>
        <div class="match-group__body">
            <match-tree-node v-for="child in node.children" :key="child.id" :node="child" :sources="sources"
                             :depth="depth + 1" @remove="removeChild"></match-tree-node>
            <p v-if="!node.children.length" class="sub-info match-group__empty">{{node.op === 'all' ? t('match.emptyAll') : t('match.emptyAny')}}</p>
            <div class="match-group__actions">
                <button type="button" class="cond-add" @click="addLeaf"><i class="bi bi-plus" aria-hidden="true"></i> {{t('modal.addCondition')}}</button>
                <button v-if="depth < 3" type="button" class="cond-add" @click="addGroup"><i class="bi bi-diagram-3" aria-hidden="true"></i> {{t('match.addGroup')}}</button>
            </div>
        </div>
    </div>
    <div v-else-if="node.kind === 'legacy'" class="match-leaf is-legacy" :class="{'is-negated': node.negate}">
        <div class="match-leaf__row">
            <button type="button" class="match-not" :class="{'is-on': node.negate}" :aria-pressed="node.negate ? 'true' : 'false'"
                    :title="t('match.notHint')" @click="node.negate = !node.negate">NOT</button>
            <select class="form-control match-leaf__source" v-model="node.source" :aria-label="t('match.source')">
                <option v-for="s in sources" :key="s" :value="s">{{t('match.source_' + s)}}</option>
            </select>
            <input class="form-control match-leaf__wide match-leaf__legacy" v-model="node.condition" :aria-label="t('match.legacyCondition')"
                   :title="t('match.legacyNote')" :placeholder="t('match.legacyPlaceholder')">
            <button type="button" class="cond-remove" @click="$emit('remove', node.id)" :aria-label="t('match.removeCondition')"
                    :title="t('match.removeCondition')"><i class="bi bi-x" aria-hidden="true"></i></button>
        </div>
    </div>
    <div v-else class="match-leaf" :class="{'is-negated': node.negate}">
        <div class="match-leaf__row">
            <button type="button" class="match-not" :class="{'is-on': node.negate}" :aria-pressed="node.negate ? 'true' : 'false'"
                    :title="t('match.notHint')" @click="node.negate = !node.negate">NOT</button>
            <select class="form-control match-leaf__source" v-model="node.source" @change="onSourceChange(node)" :aria-label="t('match.source')">
                <option v-for="s in sources" :key="s" :value="s">{{t('match.source_' + s)}}</option>
            </select>
            <template v-if="node.source === 'body'">
                <select class="form-control match-leaf__selector-kind" v-model="node.selectorKind" :aria-label="t('match.selectorKind')">
                    <option value="jsonPath">JSONPath</option>
                    <option value="xpath">XPath</option>
                    <option value="field">{{t('match.selector_field')}}</option>
                    <option value="raw">{{t('match.selector_raw')}}</option>
                </select>
                <input v-if="node.selectorKind !== 'raw'" class="form-control match-leaf__wide" v-model="node.selector"
                       :aria-label="t('match.selector')" :placeholder="selectorPlaceholder(node.selectorKind)">
            </template>
            <input v-else class="form-control match-leaf__wide" v-model="node.name" :aria-label="t('match.name')"
                   :placeholder="node.source === 'query' ? t('modal.condPlaceholderParam') : t('modal.condPlaceholderHeader')">
            <button type="button" class="cond-remove" @click="$emit('remove', node.id)" :aria-label="t('match.removeCondition')"
                    :title="t('match.removeCondition')"><i class="bi bi-x" aria-hidden="true"></i></button>
        </div>
        <div class="match-leaf__row">
            <select class="form-control match-leaf__op" v-model="node.op" :aria-label="t('match.operator')">
                <option v-for="op in ops" :key="op" :value="op">{{opLabel(op)}}</option>
            </select>
            <textarea v-if="needsValue(node.op) && (node.op === 'in' || node.op === 'equalToJson')" class="form-control match-leaf__wide match-leaf__value"
                      v-model="node.valueText" rows="2" :aria-label="t('match.value')" :placeholder="valuePlaceholder(node.op)"></textarea>
            <input v-else-if="needsValue(node.op)" class="form-control match-leaf__wide" v-model="node.valueText"
                   :inputmode="['gt','gte','lt','lte'].includes(node.op) ? 'decimal' : null"
                   :aria-label="t('match.value')" :placeholder="valuePlaceholder(node.op)">
            <span v-else class="match-leaf__wide sub-info">{{t('match.noValue')}}</span>
        </div>
        <div v-if="optionsFor(node.op).length" class="match-leaf__options">
            <label v-for="option in optionsFor(node.op)" :key="option" class="match-option">
                <input type="checkbox" v-model="node.options[option]"> {{t('match.option_' + option)}}
            </label>
        </div>
    </div>
    `,
};
