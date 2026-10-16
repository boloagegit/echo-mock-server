/**
 * easter-egg.js - 隱藏彩蛋的觸發器
 *
 * 兩種方式開啟 Echo Strike 小遊戲：
 * - 輸入 ↑ ↑ ↓ ↓ ← → ← → A B（最後兩鍵也接受 B A）。
 * - 快速連點側邊欄左上角的 Echo 圖示 5 下（滑鼠、觸控都可以）。
 * 規則：
 * - 只在伺服器設定允許時生效（/api/admin/status 的 uiEasterEgg，對應 ECHO_UI_EASTER_EGG，預設開啟）。
 * - 游標在輸入框、下拉選單、可編輯區或程式碼編輯器時不判斷，打字不會誤觸。
 * - 兩鍵間隔超過 1.5 秒或按錯就重新計算；連點時兩下間隔超過 0.6 秒就重新計算。
 * - 圖示偶爾「眨眼」一次（登入後約半分鐘，之後每隔幾分鐘），暗示它可以點；
 *   分頁在背景、遊戲開著或系統設定「減少動態效果」時不眨。
 * - 遊戲本體（echo-strike.js）在觸發後才載入，平常只有這個監聽器和一個計時器。
 */
(function () {
    var SEQUENCE = ['ArrowUp', 'ArrowUp', 'ArrowDown', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'ArrowLeft', 'ArrowRight'];
    var ENDINGS = ['ab', 'ba'];
    var KEY_GAP_MS = 1500;
    var CLICKS = 5;
    var CLICK_GAP_MS = 600;
    var BRAND = '.sidebar-brand';
    var WINK_MS = 900;
    var GAME_SRC = '/echo-strike.js?v=20261016.1';

    var enabled = false;
    var keys = [];
    var lastKeyAt = 0;
    var clicks = 0;
    var lastClickAt = 0;
    var winkTimer = 0;
    var loading = null;

    var isTyping = function (target) {
        if (!(target instanceof Element)) return false;
        if (target.isContentEditable) return true;
        if (target.closest('input, textarea, select, [contenteditable="true"], .CodeMirror')) return true;
        return false;
    };

    var normalize = function (event) {
        if (event.key && event.key.indexOf('Arrow') === 0) return event.key;
        var key = (event.key || '').toLowerCase();
        return key === 'a' || key === 'b' ? key : null;
    };

    var matches = function () {
        if (keys.length < SEQUENCE.length + 2) return false;
        var tail = keys.slice(-(SEQUENCE.length + 2));
        for (var i = 0; i < SEQUENCE.length; i++) {
            if (tail[i] !== SEQUENCE[i]) return false;
        }
        return ENDINGS.indexOf(tail[SEQUENCE.length] + tail[SEQUENCE.length + 1]) !== -1;
    };

    var loadGame = function () {
        if (window.EchoStrike) return Promise.resolve(window.EchoStrike);
        if (loading) return loading;
        loading = new Promise(function (resolve, reject) {
            var script = document.createElement('script');
            script.src = GAME_SRC;
            script.async = true;
            script.onload = function () { window.EchoStrike ? resolve(window.EchoStrike) : reject(new Error('missing game')); };
            script.onerror = function () { loading = null; script.remove(); reject(new Error('load failed')); };
            document.head.appendChild(script);
        });
        return loading;
    };

    var reducedMotion = function () {
        return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    };
    var gameOpen = function () { return !!(window.EchoStrike && window.EchoStrike.isOpen()); };

    var trigger = function () {
        var opener = document.activeElement;
        if (!reducedMotion()) {
            document.documentElement.classList.add('easter-egg-flash');
            setTimeout(function () { document.documentElement.classList.remove('easter-egg-flash'); }, 450);
        }
        loadGame().then(function (game) { game.open({ returnFocus: opener }); }).catch(function () { /* stays a secret */ });
    };

    // The logo winks: a CSS class added for one short animation.
    var wink = function () {
        if (reducedMotion()) return;
        var brand = document.querySelector(BRAND);
        if (!brand) return;
        brand.classList.remove('is-winking');
        void brand.offsetWidth;
        brand.classList.add('is-winking');
        setTimeout(function () { brand.classList.remove('is-winking'); }, WINK_MS);
    };
    var scheduleWink = function (delayMs) {
        clearTimeout(winkTimer);
        winkTimer = setTimeout(function () {
            if (!enabled) return;
            if (!document.hidden && !gameOpen()) wink();
            scheduleWink((4 + Math.random() * 4) * 60000);
        }, delayMs);
    };

    window.addEventListener('keydown', function (event) {
        if (!enabled || event.repeat || event.ctrlKey || event.metaKey || event.altKey) return;
        if (gameOpen()) return;
        if (isTyping(event.target)) return;
        var key = normalize(event);
        var now = performance.now();
        if (!key) { keys = []; return; }
        if (now - lastKeyAt > KEY_GAP_MS) keys = [];
        lastKeyAt = now;
        keys.push(key);
        if (keys.length > SEQUENCE.length + 2) keys.shift();
        if (matches()) { keys = []; trigger(); }
    });

    // Capture phase: the sidebar stops click propagation, so a bubbling listener would never hear the logo.
    document.addEventListener('click', function (event) {
        if (!enabled || gameOpen()) return;
        if (!(event.target instanceof Element) || !event.target.closest(BRAND)) return;
        var now = performance.now();
        if (now - lastClickAt > CLICK_GAP_MS) clicks = 0;
        lastClickAt = now;
        clicks++;
        if (clicks >= CLICKS) { clicks = 0; trigger(); }
        else if (clicks >= 2) wink();
    }, true);

    window.EchoEasterEgg = {
        /** Called with the server setting each time the status is applied; only an explicit true enables it. */
        setEnabled: function (value) {
            var was = enabled;
            enabled = value === true;
            if (!enabled) { keys = []; clicks = 0; clearTimeout(winkTimer); }
            else if (!was) scheduleWink((20 + Math.random() * 25) * 1000);
        },
        isEnabled: function () { return enabled; },
    };
})();
