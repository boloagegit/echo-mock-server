/**
 * useI18n - International Composable
 *
 * Responsible for language detection, switching and translation function t().
 * Language detection priority: localStorage → navigator.language → Default en
 * t() supports key path search and {param} interpolation.
 *
 * @returns {{ locale: Ref<string>, messages: Ref<object>, t: Function, switchLocale: Function, loadLocale: Function }}
 */
const useI18n = () => {
    const { ref } = Vue;

    const savedLocale = localStorage.getItem('echo_locale');
    const detectedLocale = (navigator.language || '').startsWith('zh') ? 'zh-TW' : 'en';
    const locale = ref(savedLocale || detectedLocale);
    const messages = ref({});

    /**
     * Load the translation file for the specified language
     * @param {string} lang - Language code, such as 'zh-TW' or 'en'
     */
    const loadLocale = async (lang) => {
        try {
            const r = await fetch(`/i18n/${lang}.json?v=20261023.1`);
            if (r.ok) { messages.value = await r.json(); }
        } catch (e) { console.warn('Failed to load locale:', lang, e); }
    };

    /**
     * Translation function, supports nested key and parameter interpolation
     * @param {string} key - translation key, e.g. 'toast.success'
     * @param {object} [params] - interpolation parameters, for example { count: 3 }
     * @returns {string} The translated string will return key itself when it cannot be found.
     */
    const t = (key, params) => {
        const parts = key.split('.');
        let val = messages.value;
        for (const p of parts) {
            if (val == null || typeof val !== 'object') { return key; }
            val = val[p];
        }
        if (val == null || typeof val !== 'string') { return key; }
        if (params) {
            return val.replace(/\{(\w+)\}/g, (_, name) => params[name] != null ? params[name] : `{${name}}`);
        }
        return val;
    };

    /**
     * Switch language (zh-TW ↔ en) and persist to localStorage
     */
    const switchLocale = async () => {
        locale.value = locale.value === 'zh-TW' ? 'en' : 'zh-TW';
        localStorage.setItem('echo_locale', locale.value);
        await loadLocale(locale.value);
    };

    return { locale, messages, t, switchLocale, loadLocale };
};
