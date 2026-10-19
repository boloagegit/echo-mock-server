/**
 * ui-snapshot.js - When reorganizing, first draw the screen before leaving
 *
 * app.js saves the current screen (excluding dialog boxes, drawers, notifications and menus) into sessionStorage during pagehide.
 * When reorganizing or returning to the same URL from the previous page/next page, put it into the startup frame before drawing it for the first time.
 * So the first screen is exactly the same as before leaving; after Vue is ready, revealApp will be seamlessly replaced with the real screen.
 * The snapshot is only used once and is only valid within the same page, the same URL, and within ten minutes.
 */
(function () {
    var KEY = 'echo.pageSnapshot';
    try {
        var raw = sessionStorage.getItem(KEY);
        if (!raw) { return; }
        sessionStorage.removeItem(KEY);
        var entry = performance.getEntriesByType ? performance.getEntriesByType('navigation')[0] : null;
        var type = entry ? entry.type : '';
        if (type !== 'reload' && type !== 'back_forward') { return; }
        var snapshot = JSON.parse(raw);
        if (!snapshot || snapshot.href !== location.href || Date.now() - snapshot.savedAt > 600000) { return; }
        var shell = document.getElementById('boot-shell');
        if (!shell) { return; }
        shell.innerHTML = snapshot.html;
        shell.classList.add('boot-shell--snapshot');
    } catch (e) { /* storage unavailable or snapshot unreadable: the plain shell applies */ }
})();
