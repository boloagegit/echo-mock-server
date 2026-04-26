/**
 * useTheme - 主題切換 Composable
 *
 * 管理應用程式的主題狀態（light/dark/auto），支援系統偏好跟隨。
 * 主題值儲存於 localStorage，auto 模式下監聽系統 prefers-color-scheme 變化。
 *
 * @param {Function} t - 翻譯函式（來自 useI18n）
 * @returns {{ theme: Ref<string>, toggleTheme: Function, themeIcon: ComputedRef<string>, themeLabel: ComputedRef<string>, applyTheme: Function, cleanupTheme: Function }}
 */
const useTheme = (t) => {
    const { ref, computed } = Vue;

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

    /** 切換主題：light → dark → auto → light */
    const toggleTheme = () => {
        theme.value = theme.value === 'light' ? 'dark' : theme.value === 'dark' ? 'auto' : 'light';
        localStorage.setItem('theme', theme.value);
        applyTheme();
    };

    /** 主題圖示 */
    const themeIcon = computed(() => theme.value === 'light' ? 'bi-sun' : theme.value === 'dark' ? 'bi-moon' : 'bi-circle-half');

    /** 主題標籤（翻譯） */
    const themeLabel = computed(() => theme.value === 'light' ? t('theme.light') : theme.value === 'dark' ? t('theme.dark') : t('theme.auto'));

    /** 清除系統偏好監聽，供 onUnmounted 呼叫 */
    const cleanupTheme = () => {
        systemDarkQuery.removeEventListener('change', onSystemThemeChange);
    };

    return { theme, toggleTheme, themeIcon, themeLabel, applyTheme, cleanupTheme };
};
