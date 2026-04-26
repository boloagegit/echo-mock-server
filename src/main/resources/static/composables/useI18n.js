/**
 * useI18n - 國際化 Composable
 *
 * 負責語系偵測、切換與翻譯函式 t()。
 * 語系偵測優先順序：localStorage → navigator.language → 預設 en
 * t() 支援 key 路徑查找與 {param} 插值。
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
     * 載入指定語系的翻譯檔
     * @param {string} lang - 語系代碼，例如 'zh-TW' 或 'en'
     */
    const loadLocale = async (lang) => {
        try {
            const r = await fetch(`/i18n/${lang}.json`);
            if (r.ok) { messages.value = await r.json(); }
        } catch (e) { console.warn('Failed to load locale:', lang, e); }
    };

    /**
     * 翻譯函式，支援巢狀 key 與參數插值
     * @param {string} key - 翻譯鍵，例如 'toast.success'
     * @param {object} [params] - 插值參數，例如 { count: 3 }
     * @returns {string} 翻譯後的字串，找不到時回傳 key 本身
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
     * 切換語系（zh-TW ↔ en），並持久化至 localStorage
     */
    const switchLocale = async () => {
        locale.value = locale.value === 'zh-TW' ? 'en' : 'zh-TW';
        localStorage.setItem('echo_locale', locale.value);
        await loadLocale(locale.value);
    };

    return { locale, messages, t, switchLocale, loadLocale };
};
