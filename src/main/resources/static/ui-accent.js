/**
 * ui-accent.js - Main color of deployment environment
 *
 * The main color is determined by the server setting echo.ui.accent (environment variable ECHO_UI_ACCENT),
 * uiAccent return through /api/admin/status, used to distinguish different deployment environments (such as SIT cyan, UAT blue).
 * This file is loaded simultaneously in <head>: first apply the last temporarily saved main color to avoid flashing the default color when the page is loading.
 */
(function () {
    var ACCENTS = ['teal', 'blue'];
    var STORAGE_KEY = 'echo.uiAccent';

    var normalize = function (value) {
        return ACCENTS.indexOf(value) >= 0 ? value : 'teal';
    };
    var setAttribute = function (accent) {
        document.documentElement.setAttribute('data-accent', accent);
    };

    var cached = null;
    try { cached = localStorage.getItem(STORAGE_KEY); } catch (e) { /* storage unavailable */ }
    setAttribute(normalize(cached));

    window.EchoAccent = {
        /** Apply the main color returned by the server and save it temporarily; the unknown value returns to teal. */
        apply: function (value) {
            var accent = normalize(value);
            setAttribute(accent);
            try { localStorage.setItem(STORAGE_KEY, accent); } catch (e) { /* storage unavailable */ }
            return accent;
        }
    };
})();
