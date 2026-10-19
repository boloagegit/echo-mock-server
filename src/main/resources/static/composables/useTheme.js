/**
 * useTheme - Theme switch Composable
 *
 * Manage the theme status of the application (light/dark/auto) and support system preference following.
 * The theme value is stored in localStorage, and the system prefers-color-scheme changes are monitored in auto mode.
 *
 * @returns {{ theme: Ref<string>, setTheme: Function, applyTheme: Function, cleanupTheme: Function }}
 */
const useTheme = () => {
    const { ref } = Vue;

    const theme = ref(localStorage.getItem('theme') || 'dark');
    const systemDarkQuery = window.matchMedia('(prefers-color-scheme: dark)');

    /** Apply to DOM based on current theme value */
    const applyTheme = () => {
        const effective = theme.value === 'auto' ? (systemDarkQuery.matches ? 'dark' : 'light') : theme.value;
        document.documentElement.setAttribute('data-theme', effective);
        document.documentElement.style.colorScheme = effective;
    };

    /** When system preferences are changed, auto mode is automatically applied */
    const onSystemThemeChange = () => { if (theme.value === 'auto') { applyTheme(); } };
    systemDarkQuery.addEventListener('change', onSystemThemeChange);

    /** Set theme (dark / light / auto), unknown values are ignored */
    const setTheme = (value) => {
        if (!['dark', 'light', 'auto'].includes(value)) { return; }
        theme.value = value;
        localStorage.setItem('theme', theme.value);
        applyTheme();
    };

    /** Clear system preference listening for onUnmounted calls */
    const cleanupTheme = () => {
        systemDarkQuery.removeEventListener('change', onSystemThemeChange);
    };

    return { theme, setTheme, applyTheme, cleanupTheme };
};
