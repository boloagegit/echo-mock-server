/**
 * ui-snapshot.js - 重新整理時先畫出離開前的畫面
 *
 * app.js 在 pagehide 時把目前畫面（不含對話框、抽屜、通知與選單）存進 sessionStorage。
 * 重新整理或上一頁／下一頁回到同一網址時，這裡在第一次繪製前把它放進開機外框，
 * 所以第一個畫面就和離開前一模一樣；Vue 準備好之後 revealApp 再無縫換成真正的畫面。
 * 快照只用一次，且只在同一分頁、同一網址、十分鐘內有效。
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
