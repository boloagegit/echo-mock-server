/**
 * useTheme - 主題切換 Composable
 *
 * 管理應用程式的主題狀態（light/dark/auto），支援系統偏好跟隨。
 * 主題值儲存於 localStorage，auto 模式下監聽系統 prefers-color-scheme 變化。
 *
 * @returns {{ theme: Ref<string>, setTheme: Function, applyTheme: Function, cleanupTheme: Function }}
 */
const useTheme = () => {
    const { ref } = Vue;

    const theme = ref(localStorage.getItem('theme') || 'dark');
    const systemDarkQuery = window.matchMedia('(prefers-color-scheme: dark)');

    /** 根據目前主題值套用至 DOM */
    const applyTheme = () => {
        const effective = theme.value === 'auto' ? (systemDarkQuery.matches ? 'dark' : 'light') : theme.value;
        document.documentElement.setAttribute('data-theme', effective);
        document.documentElement.style.colorScheme = effective;
    };

    /** 系統偏好變更時，auto 模式自動套用 */
    const onSystemThemeChange = () => { if (theme.value === 'auto') { applyTheme(); } };
    systemDarkQuery.addEventListener('change', onSystemThemeChange);

    /** 設定主題（dark / light / auto），未知值忽略 */
    const setTheme = (value) => {
        if (!['dark', 'light', 'auto'].includes(value)) { return; }
        theme.value = value;
        localStorage.setItem('theme', theme.value);
        applyTheme();
    };

    /** 清除系統偏好監聽，供 onUnmounted 呼叫 */
    const cleanupTheme = () => {
        systemDarkQuery.removeEventListener('change', onSystemThemeChange);
    };

    return { theme, setTheme, applyTheme, cleanupTheme };
};
