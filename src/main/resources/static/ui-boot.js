/**
 * ui-boot.js - 首次繪製前套用使用者偏好
 *
 * 在 <head> 同步執行：主題與密度在 Vue 掛載前就套用，重新整理時不會先閃過預設樣式。
 * 偏好值仍由 useTheme / setDensity 管理，這裡只讀取。
 */
(function () {
    var root = document.documentElement;
    try {
        var theme = localStorage.getItem('theme') || 'dark';
        var effective = theme === 'auto'
            ? (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
            : theme;
        root.setAttribute('data-theme', effective === 'light' ? 'light' : 'dark');
        root.style.colorScheme = effective === 'light' ? 'light' : 'dark';
        var density = localStorage.getItem('echo_density');
        if (density === 'compact' || density === 'normal' || density === 'comfortable') {
            root.setAttribute('data-density', density);
        }
    } catch (e) { /* storage unavailable: CSS defaults apply */ }
})();
