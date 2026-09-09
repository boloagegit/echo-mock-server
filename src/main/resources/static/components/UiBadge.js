/**
 * UiBadge - shared descriptive and semantic status label.
 *
 * `tone` expresses meaning; legacy badge classes remain supported while
 * templates migrate, but this component owns dimensions and visual states.
 */
const UiBadge = {
  inheritAttrs: false,
  props: {
    tone: {
      type: String,
      default: '',
      validator: value => ['', 'neutral', 'accent', 'success', 'warning', 'danger'].includes(value)
    }
  },
  setup(props, { attrs, slots }) {
    const flattenClasses = value => {
      if (!value) { return []; }
      if (typeof value === 'string') { return value.split(/\s+/).filter(Boolean); }
      if (Array.isArray(value)) { return value.flatMap(flattenClasses); }
      if (typeof value === 'object') { return Object.keys(value).filter(key => value[key]); }
      return [];
    };
    return () => {
      const legacyClasses = flattenClasses(attrs.class);
      const tone = props.tone
        || (legacyClasses.some(name => ['badge-success', 'bg-success'].includes(name)) ? 'success' : '')
        || (legacyClasses.some(name => ['badge-warning', 'bg-warning'].includes(name)) ? 'warning' : '')
        || (legacyClasses.some(name => ['badge-danger', 'bg-danger'].includes(name)) ? 'danger' : '')
        || 'neutral';
      return Vue.h('span', {
        ...attrs,
        class: ['ui-badge', `ui-badge--${tone}`, legacyClasses]
      }, slots.default?.());
    };
  }
};
