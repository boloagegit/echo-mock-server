/**
 * easter-egg.js - 隱藏彩蛋的觸發器
 *
 * 輸入 ↑ ↑ ↓ ↓ ← → ← → A B（最後兩鍵也接受 B A）開啟 Echo Strike 小遊戲。
 * - 只在伺服器設定允許時生效（/api/admin/status 的 uiEasterEgg，對應 ECHO_UI_EASTER_EGG，預設開啟）。
 * - 游標在輸入框、下拉選單、可編輯區或程式碼編輯器時不判斷，打字不會誤觸。
 * - 兩鍵間隔超過 1.5 秒或按錯就重新計算。
 * - 遊戲本體（echo-strike.js）在觸發後才載入，平常只有這個監聽器。
 */
(function () {
    var SEQUENCE = ['ArrowUp', 'ArrowUp', 'ArrowDown', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'ArrowLeft', 'ArrowRight'];
    var ENDINGS = ['ab', 'ba'];
    var KEY_GAP_MS = 1500;
    var GAME_SRC = '/echo-strike.js?v=20261008.2';

    var enabled = false;
    var keys = [];
    var lastKeyAt = 0;
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

    var trigger = function () {
        var opener = document.activeElement;
        if (!reducedMotion()) {
            document.documentElement.classList.add('easter-egg-flash');
            setTimeout(function () { document.documentElement.classList.remove('easter-egg-flash'); }, 450);
        }
        loadGame().then(function (game) { game.open({ returnFocus: opener }); }).catch(function () { /* stays a secret */ });
    };

    window.addEventListener('keydown', function (event) {
        if (!enabled || event.repeat || event.ctrlKey || event.metaKey || event.altKey) return;
        if (window.EchoStrike && window.EchoStrike.isOpen()) return;
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

    window.EchoEasterEgg = {
        /** Called with the server setting each time the status is applied; only an explicit true enables it. */
        setEnabled: function (value) { enabled = value === true; if (!enabled) keys = []; },
    };
})();
