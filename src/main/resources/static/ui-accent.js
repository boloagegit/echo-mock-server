/**
 * ui-accent.js - 部署環境主色
 *
 * 主色由伺服器設定 echo.ui.accent（環境變數 ECHO_UI_ACCENT）決定，
 * 透過 /api/admin/status 的 uiAccent 回傳，用來區分不同部署環境（例如 SIT 青綠、UAT 藍）。
 * 本檔在 <head> 同步載入：先套用上次暫存的主色，避免頁面載入時閃過預設色。
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
        /** 套用伺服器回傳的主色並暫存；未知值回到 teal。 */
        apply: function (value) {
            var accent = normalize(value);
            setAttribute(accent);
            try { localStorage.setItem(STORAGE_KEY, accent); } catch (e) { /* storage unavailable */ }
            return accent;
        }
    };
})();
