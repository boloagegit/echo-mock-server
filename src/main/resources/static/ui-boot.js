/**
 * ui-boot.js - Apply user preferences before first drawing
 *
 * Synchronous execution in <head>: The theme and density are applied before Vue is mounted, and the default style will not be flashed first when reorganizing.
 * The preference value is still managed by useTheme / setDensity and is only read here.
 */
(function () {
    var root = document.documentElement;
    // The app stays invisible until it is ready (see revealApp), so the first contentful
    // paint is the finished page; browsers keep showing the previous page until then.
    root.classList.add('is-booting');
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
