/**
 * echo-strike.js - Echo Strike，隱藏彩蛋的縱向射擊小遊戲（由 easter-egg.js 在觸發後載入）
 *
 * 原創內容：機體、敵人、名稱都是自己畫的。玩家機用部署主色（SIT 青綠／UAT 藍），
 * 敵人是 HTTP 方法造型，道具是「200 OK」膠囊，三關的 Boss 分別是 404、429、500。
 *
 * 內容：
 * - 三關（STAGING → UAT → PRODUCTION），每關有中途強敵與 Boss，打完三關進入下一輪，速度更快。
 * - 八種敵人：GET、HEAD、POST、PUT、DELETE、PATCH、OPTIONS 砲台、TIMEOUT 定時雷。
 * - 道具：武器（WIDE／BEAM／SEEK，可升到 Lv4）、炸彈、護盾、MOCK 僚機、1UP。
 * - 連鎖：連續擊破提高倍率；每關結算擊破數、最高連鎖與無傷獎勵。
 * - 無限接關：生命用完時 10 秒內按 Z（或點一下）就從原地繼續，關卡與 Boss 進度保留；
 *   分數歸零重算（接關前的分數會先記為最高分），畫面顯示接關次數。
 *
 * 炸彈：鍵盤按 X；滑鼠或觸控按右下角的 BOMB 按鈕（觸控也可以點兩下）。
 * 會清掉畫面上所有敵彈、重創敵人，並短暫無敵。沒有炸彈時按鈕會提示。
 *
 * 效能：
 * - 遊戲本體只在觸發彩蛋後才下載，平常開網站不會載入。
 * - 遊戲邏輯固定每秒 60 次更新，畫面跟著 requestAnimationFrame 繪製；切到別的分頁或關閉就停止。
 * - 子彈、敵人、粒子、特效、文字都用物件池，遊戲中不產生新物件，不會因記憶體回收卡頓。
 * - 圖案在開始時畫進離屏 canvas 一次，之後只貼圖；不使用 shadowBlur 或 filter 這類昂貴效果。
 * - 依 devicePixelRatio 設定畫布解析度，高解析度螢幕不糊。
 * 系統設定「減少動態效果」時不閃光、不震動。
 */
(function () {
    'use strict';

    var W = 480, H = 720, STEP = 1 / 60, MAX_STEPS = 5, TAU = Math.PI * 2;
    var HI_KEY = 'echo.strike.hiscore';
    var BOMBED_KEY = 'echo.strike.bombed';

    var TEXT = {
        en: {
            title: 'ECHO STRIKE', tagline: 'Three deployments. Three incidents.',
            start: 'Press Z, Space or Enter to start', tapStart: 'Tap to start', clickStart: 'or click anywhere',
            keys: [['Arrows / WASD', 'Move'], ['(automatic)', 'Fire'], ['Shift', 'Slow move, show hitbox'], ['X', 'Bomb: clears every bullet'], ['P', 'Pause'], ['Esc', 'Exit']],
            touchKeys: [['Drag', 'Move (fires on its own)'], ['BOMB', 'Tap the button, or double-tap']],
            paused: 'PAUSED', resume: 'P or tap to resume',
            over: 'CONNECTION LOST', retry: 'Z to retry · Esc to exit', tapRetry: 'Tap to retry', record: 'NEW RECORD', reached: 'Reached',
            continueTitle: 'CONTINUE?', continueKey: 'Z to continue · unlimited', continueTap: 'Tap to continue · unlimited', continueNote: 'Score starts again from 0', continues: 'CONTINUE',
            hi: 'HI', chain: 'CHAIN', stage: 'STAGE', loopTag: 'LOOP',
            bomb: 'BOMB', noBomb: 'NO BOMBS LEFT', bombHint: 'X = BOMB: clears every enemy bullet', bombHintTouch: 'Tap BOMB (bottom right) to clear every bullet',
            danger: 'Too many bullets! Press X to bomb', dangerTouch: 'Too many bullets! Tap BOMB',
            stages: [
                { name: 'STAGING', sub: 'Smoke tests are flying' },
                { name: 'UAT', sub: 'Users click everything' },
                { name: 'PRODUCTION', sub: 'Friday, 5 pm' },
            ],
            bosses: {
                b404: { name: '404 NOT FOUND', warn: 'A page went missing' },
                b429: { name: '429 TOO MANY REQUESTS', warn: 'Rate limit exceeded' },
                b500: { name: '500 INTERNAL SERVER ERROR', warn: 'Production is down' },
            },
            warning: 'WARNING', retryAfter: 'Retry-After', clear: 'STAGE CLEAR', kills: 'Kills', maxChain: 'Max chain', noMiss: 'No-miss bonus', stageBonus: 'Stage bonus', total: 'Score',
            loop: 'ALL INCIDENTS RESOLVED', loopNext: 'Loop {n} begins · everything is faster',
            pickups: { spread: 'WIDE SHOT', laser: 'BEAM', homing: 'SEEKER', bomb: '+1 BOMB', shield: 'SHIELD', drone: 'MOCK DRONE', life: '1UP', levelUp: 'LEVEL UP', max: 'MAX' },
            weapons: { spread: 'WIDE', laser: 'BEAM', homing: 'SEEK' },
        },
        'zh-TW': {
            title: 'ECHO STRIKE', tagline: '三次部署，三場事故。',
            start: '按 Z、空白鍵或 Enter 開始', tapStart: '點一下開始', clickStart: '或用滑鼠點一下',
            keys: [['方向鍵 / WASD', '移動'], ['（自動）', '射擊'], ['Shift', '慢速移動、顯示判定點'], ['X', '炸彈：清光所有敵彈'], ['P', '暫停'], ['Esc', '離開']],
            touchKeys: [['拖曳', '移動（自動射擊）'], ['BOMB', '點右下角按鈕，或點兩下']],
            paused: '暫停', resume: '按 P 或點一下繼續',
            over: '連線中斷', retry: '按 Z 重來 · Esc 離開', tapRetry: '點一下重來', record: '新紀錄', reached: '到達',
            continueTitle: '要接關嗎？', continueKey: '按 Z 接關 · 次數不限', continueTap: '點一下接關 · 次數不限', continueNote: '關卡進度保留，分數從 0 重新計算', continues: '接關',
            hi: '最高', chain: '連鎖', stage: 'STAGE', loopTag: 'LOOP',
            bomb: 'BOMB', noBomb: '沒有炸彈了', bombHint: 'X = 炸彈：清光所有敵彈', bombHintTouch: '點右下角 BOMB 清光所有敵彈',
            danger: '子彈太多！按 X 放炸彈', dangerTouch: '子彈太多！點 BOMB',
            stages: [
                { name: 'STAGING', sub: '冒煙測試滿天飛' },
                { name: 'UAT', sub: '使用者什麼都按' },
                { name: 'PRODUCTION', sub: '週五下午五點' },
            ],
            bosses: {
                b404: { name: '404 NOT FOUND', warn: '有個頁面不見了' },
                b429: { name: '429 TOO MANY REQUESTS', warn: '請求量超過上限' },
                b500: { name: '500 INTERNAL SERVER ERROR', warn: '正式環境掛了' },
            },
            warning: '警告', retryAfter: 'Retry-After', clear: '本關完成', kills: '擊破', maxChain: '最高連鎖', noMiss: '無傷獎勵', stageBonus: '過關獎勵', total: '分數',
            loop: '所有事故已排除', loopNext: '第 {n} 輪開始 · 一切都更快了',
            pickups: { spread: '散射', laser: '雷射', homing: '追蹤', bomb: '炸彈 +1', shield: '護盾', drone: 'MOCK 僚機', life: '1UP', levelUp: '升級', max: 'MAX' },
            weapons: { spread: 'WIDE', laser: 'BEAM', homing: 'SEEK' },
        },
    };

    // Enemies are shaped after HTTP methods; colours follow the method colours of the rule list.
    var ENEMY_TYPES = {
        GET: { label: 'GET', color: 'get', hp: 1, score: 100, r: 15, speed: 130, w: 48, h: 42 },
        HEAD: { label: 'HEAD', color: 'head', hp: 1, score: 120, r: 12, speed: 0, w: 42, h: 36 },
        POST: { label: 'POST', color: 'post', hp: 3, score: 200, r: 18, speed: 90, w: 62, h: 44 },
        PUT: { label: 'PUT', color: 'put', hp: 7, score: 400, r: 20, speed: 110, w: 58, h: 54 },
        DELETE: { label: 'DEL', color: 'delete', hp: 2, score: 300, r: 15, speed: 60, w: 46, h: 46 },
        PATCH: { label: 'PATCH', color: 'patch', hp: 16, score: 900, r: 26, speed: 40, w: 84, h: 74 },
        OPTIONS: { label: 'OPT', color: 'options', hp: 8, score: 500, r: 18, speed: 0, w: 56, h: 56 },
        TIMEOUT: { label: '408', color: 'timeout', hp: 4, score: 350, r: 16, speed: 50, w: 46, h: 46 },
    };
    var STAGES = [
        { boss: 'b404', length: 50, accent: 'primary', mid: ['PUT', 'PUT'], sky: ['#0f3b40', '#0b2236', '#12304a'],
            mix: [['GET', 5], ['HEAD', 4], ['POST', 3], ['OPTIONS', 1]] },
        { boss: 'b429', length: 55, accent: 'post', mid: ['PATCH'], sky: ['#13275a', '#24164a', '#0e2a4a'],
            mix: [['GET', 3], ['HEAD', 3], ['POST', 3], ['PUT', 2], ['DELETE', 2], ['OPTIONS', 2], ['TIMEOUT', 1]] },
        { boss: 'b500', length: 60, accent: 'danger', mid: ['PATCH', 'PATCH'], sky: ['#4a1220', '#2e1030', '#40180e'],
            mix: [['GET', 2], ['HEAD', 2], ['POST', 3], ['PUT', 2], ['DELETE', 3], ['PATCH', 1], ['OPTIONS', 2], ['TIMEOUT', 2]] },
    ];
    var BOSSES = {
        b404: { hp: 280, hw: 78, hh: 42, color: 'b404' },
        b429: { hp: 320, hw: 92, hh: 40, color: 'b429' },
        b500: { hp: 360, hw: 86, hh: 36, color: 'b500' },
    };
    var WEAPONS = ['spread', 'laser', 'homing'];
    var CAPSULES = { spread: 'WIDE', laser: 'BEAM', homing: 'SEEK', bomb: 'BOMB', shield: 'SHLD', drone: 'MOCK', life: '1UP' };

    // ---------- helpers ----------
    var clamp = function (v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; };
    var lang = function () {
        var saved = null;
        try { saved = localStorage.getItem('echo_locale'); } catch (e) { /* storage unavailable */ }
        var value = saved || document.documentElement.lang || navigator.language || 'en';
        return value.indexOf('zh') === 0 ? 'zh-TW' : 'en';
    };
    var readNumber = function (key) { try { return Number(localStorage.getItem(key)) || 0; } catch (e) { return 0; } };
    var writeNumber = function (key, v) { try { localStorage.setItem(key, String(v)); } catch (e) { /* storage unavailable */ } };
    var readHi = function () { return readNumber(HI_KEY); };
    var writeHi = function (v) { writeNumber(HI_KEY, v); };
    var pool = function (size, make) { var list = []; for (var i = 0; i < size; i++) { var o = make(); o.on = false; list.push(o); } return list; };
    var spawn = function (list) { for (var i = 0; i < list.length; i++) { if (!list[i].on) { list[i].on = true; return list[i]; } } return null; };
    var count = function (list) { var n = 0; for (var i = 0; i < list.length; i++) if (list[i].on) n++; return n; };
    var pad = function (n) { var s = String(n); while (s.length < 8) s = '0' + s; return s; };
    // Small seeded generator (mulberry32): a run can be replayed with the same seed.
    var makeRandom = function (seed) {
        var a = seed >>> 0 || 1;
        return function () {
            a = (a + 0x6D2B79F5) | 0;
            var x = Math.imul(a ^ (a >>> 15), 1 | a);
            x = (x + Math.imul(x ^ (x >>> 7), 61 | x)) ^ x;
            return ((x ^ (x >>> 14)) >>> 0) / 4294967296;
        };
    };
    var shade = function (hex, amount) {
        var n = parseInt(hex.slice(1), 16), r = n >> 16, g = (n >> 8) & 255, b = n & 255;
        var target = amount < 0 ? 0 : 255, p = Math.abs(amount);
        return 'rgb(' + Math.round(r + (target - r) * p) + ',' + Math.round(g + (target - g) * p) + ',' + Math.round(b + (target - b) * p) + ')';
    };
    var rr = function (g, x, y, w, h, r) {
        g.beginPath();
        if (g.roundRect) g.roundRect(x, y, w, h, r); else g.rect(x, y, w, h);
    };
    var poly = function (g, points) {
        g.beginPath(); g.moveTo(points[0], points[1]);
        for (var i = 2; i < points.length; i += 2) g.lineTo(points[i], points[i + 1]);
        g.closePath();
    };
    var mirror = function (half) {
        // half: x,y pairs for the right side, from the nose round to the tail; returns the full outline.
        var out = half.slice();
        for (var i = half.length - 2; i >= 0; i -= 2) if (half[i] !== 0) out.push(-half[i], half[i + 1]);
        return out;
    };

    // ---------- sprites (drawn once into offscreen canvases) ----------
    var sprite = function (w, h, draw) {
        var c = document.createElement('canvas');
        c.width = Math.ceil(w * sprite.scale); c.height = Math.ceil(h * sprite.scale);
        var g = c.getContext('2d');
        g.scale(sprite.scale, sprite.scale);
        draw(g, w, h);
        c.w = w; c.h = h;
        return c;
    };
    sprite.scale = 2;

    // A white copy of a sprite, drawn over it for one frame when it is hit.
    var flashOf = function (src) {
        var c = document.createElement('canvas');
        c.width = src.width; c.height = src.height; c.w = src.w; c.h = src.h;
        var g = c.getContext('2d');
        g.drawImage(src, 0, 0);
        g.globalCompositeOperation = 'source-in';
        g.fillStyle = 'rgba(255,255,255,0.85)'; g.fillRect(0, 0, c.width, c.height);
        return c;
    };

    var glowCircle = function (g, x, y, r, color, alpha) {
        var grad = g.createRadialGradient(x, y, 0, x, y, r);
        grad.addColorStop(0, color); grad.addColorStop(1, 'rgba(0,0,0,0)');
        g.globalAlpha = alpha; g.fillStyle = grad; g.beginPath(); g.arc(x, y, r, 0, TAU); g.fill(); g.globalAlpha = 1;
    };
    var vgrad = function (g, y0, y1, top, bottom) { var grad = g.createLinearGradient(0, y0, 0, y1); grad.addColorStop(0, top); grad.addColorStop(1, bottom); return grad; };
    var hgrad = function (g, x0, x1, edge, mid) { var grad = g.createLinearGradient(x0, 0, x1, 0); grad.addColorStop(0, edge); grad.addColorStop(0.5, mid); grad.addColorStop(1, edge); return grad; };

    var plate = function (g, pal, label, x, y, color, size) {
        g.font = '700 ' + size + 'px ' + pal.mono;
        var tw = g.measureText(label).width + 7, th = size + 5;
        g.fillStyle = 'rgba(7,9,12,0.88)'; g.strokeStyle = color; g.lineWidth = 1.1;
        rr(g, x - tw / 2, y - th / 2, tw, th, 3); g.fill(); g.stroke();
        g.fillStyle = color; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(label, x, y + 0.5);
    };

    // Player ship, nose up. bank -1 (left) .. 1 (right) squashes the wings and darkens the far one.
    var drawShip = function (g, pal, bank) {
        glowCircle(g, 0, 6, 26, pal.primary, 0.16);
        g.save();
        g.scale(1 - Math.abs(bank) * 0.16, 1);
        g.lineJoin = 'round';
        var wing = [0, -6, 9, -4, 23, 9, 23, 14, 8, 12, 6, 16];
        g.fillStyle = vgrad(g, -6, 16, shade(pal.primary, 0.25), pal.primaryDeep);
        g.strokeStyle = pal.ink; g.lineWidth = 1.2;
        poly(g, mirror(wing)); g.fill(); g.stroke();
        // the wing on the far side of the bank is in shadow
        if (bank) {
            g.fillStyle = 'rgba(0,0,0,0.28)';
            poly(g, bank > 0 ? [0, -6, -9, -4, -23, 9, -23, 14, -8, 12, -6, 16] : [0, -6, 9, -4, 23, 9, 23, 14, 8, 12, 6, 16]); g.fill();
        }
        // wing tips and stripes
        g.fillStyle = '#ffffff'; g.globalAlpha = 0.85;
        g.fillRect(20.5, 7, 2.5, 6); g.fillRect(-23, 7, 2.5, 6);
        g.globalAlpha = 1; g.fillStyle = pal.warning;
        poly(g, [11, 3, 17, 8, 15, 9, 10, 5]); g.fill();
        poly(g, [-11, 3, -17, 8, -15, 9, -10, 5]); g.fill();
        // fuselage
        g.fillStyle = hgrad(g, -7, 7, pal.primaryDeep, shade(pal.primary, 0.45));
        poly(g, mirror([0, -25, 5, -13, 7, 3, 5, 17])); g.fill(); g.stroke();
        g.strokeStyle = 'rgba(11,14,18,0.45)'; g.lineWidth = 0.8;
        g.beginPath(); g.moveTo(-5, 3); g.lineTo(5, 3); g.moveTo(-4, 10); g.lineTo(4, 10); g.stroke();
        // engines
        g.fillStyle = pal.ink; rr(g, -6.5, 14, 4.5, 4, 1); g.fill(); rr(g, 2, 14, 4.5, 4, 1); g.fill();
        // cockpit glass
        var glass = g.createLinearGradient(0, -16, 0, -1);
        glass.addColorStop(0, '#ffffff'); glass.addColorStop(0.45, shade(pal.primary, 0.6)); glass.addColorStop(1, pal.ink);
        g.fillStyle = glass; g.beginPath(); g.ellipse(0, -8, 3.4, 7, 0, 0, TAU); g.fill();
        g.fillStyle = 'rgba(255,255,255,0.9)'; g.beginPath(); g.ellipse(-1.1, -11, 1, 2.4, 0, 0, TAU); g.fill();
        g.restore();
    };

    var ENEMY_DRAW = {
        GET: function (g, c, pal) {
            g.fillStyle = vgrad(g, -16, 16, shade(c, 0.2), shade(c, -0.45)); g.strokeStyle = shade(c, -0.6); g.lineWidth = 1.2;
            poly(g, mirror([0, 17, 8, 3, 21, -6, 21, -11, 7, -8, 0, -15])); g.fill(); g.stroke();
            g.fillStyle = shade(c, -0.7); g.beginPath(); g.ellipse(0, 5, 3, 5, 0, 0, TAU); g.fill();
            g.fillStyle = 'rgba(255,255,255,0.7)'; g.beginPath(); g.ellipse(0.8, 7, 1, 2, 0, 0, TAU); g.fill();
            plate(g, pal, 'GET', 0, -6, c, 8);
        },
        HEAD: function (g, c, pal) {
            g.fillStyle = vgrad(g, -14, 16, shade(c, -0.4), shade(c, 0.25)); g.strokeStyle = shade(c, -0.6); g.lineWidth = 1.1;
            poly(g, mirror([0, 16, 15, -12, 0, -5])); g.fill(); g.stroke();
            g.strokeStyle = 'rgba(255,255,255,0.55)'; g.beginPath(); g.moveTo(0, 13); g.lineTo(0, -3); g.stroke();
            plate(g, pal, 'HEAD', 0, -2, c, 7);
        },
        POST: function (g, c, pal) {
            g.fillStyle = shade(c, -0.55);
            g.beginPath(); g.arc(-25, 2, 6, 0, TAU); g.arc(25, 2, 6, 0, TAU); g.fill();
            g.fillStyle = vgrad(g, -10, 12, shade(c, 0.15), shade(c, -0.5)); g.strokeStyle = shade(c, -0.65); g.lineWidth = 1.2;
            g.beginPath(); g.ellipse(0, 3, 26, 10, 0, 0, TAU); g.fill(); g.stroke();
            g.fillStyle = vgrad(g, -12, 2, '#ffffff', shade(c, -0.2));
            g.beginPath(); g.ellipse(0, -2, 11, 8, 0, Math.PI, TAU); g.fill();
            g.fillStyle = '#ffffff';
            for (var i = -2; i <= 2; i++) { g.globalAlpha = 0.7; g.beginPath(); g.arc(i * 9, 8, 1.4, 0, TAU); g.fill(); }
            g.globalAlpha = 1;
            plate(g, pal, 'POST', 0, 3, c, 8);
        },
        PUT: function (g, c, pal) {
            g.fillStyle = shade(c, -0.65);
            rr(g, -26, -18, 8, 36, 3); g.fill(); rr(g, 18, -18, 8, 36, 3); g.fill();
            g.strokeStyle = 'rgba(255,255,255,0.18)'; g.lineWidth = 1;
            g.beginPath(); for (var k = -14; k <= 14; k += 6) { g.moveTo(-26, k); g.lineTo(-18, k); g.moveTo(18, k); g.lineTo(26, k); } g.stroke();
            g.fillStyle = vgrad(g, -20, 20, shade(c, 0.15), shade(c, -0.45)); g.strokeStyle = shade(c, -0.65); g.lineWidth = 1.4;
            poly(g, [-18, -20, 18, -20, 22, -14, 22, 16, 16, 22, -16, 22, -22, 16, -22, -14]); g.fill(); g.stroke();
            g.fillStyle = shade(c, -0.6); g.beginPath(); g.arc(0, 8, 6, 0, TAU); g.fill();
            g.fillStyle = shade(c, 0.4); g.beginPath(); g.arc(0, 8, 2.2, 0, TAU); g.fill();
            plate(g, pal, 'PUT', 0, -8, c, 9);
        },
        DELETE: function (g, c, pal) {
            var pts = [];
            for (var i = 0; i < 16; i++) { var a = i / 16 * TAU, r = i % 2 ? 11 : 21; pts.push(Math.cos(a) * r, Math.sin(a) * r); }
            g.fillStyle = vgrad(g, -20, 20, shade(c, 0.1), shade(c, -0.5)); g.strokeStyle = shade(c, -0.65); g.lineWidth = 1.2;
            poly(g, pts); g.fill(); g.stroke();
            g.fillStyle = pal.ink; g.beginPath(); g.arc(0, 0, 9, 0, TAU); g.fill();
            plate(g, pal, 'DEL', 0, 0, c, 8);
        },
        PATCH: function (g, c, pal) {
            var pts = [];
            for (var i = 0; i < 8; i++) { var a = i / 8 * TAU + Math.PI / 8; pts.push(Math.cos(a) * 32, Math.sin(a) * 30); }
            g.fillStyle = vgrad(g, -30, 30, shade(c, 0.1), shade(c, -0.55)); g.strokeStyle = shade(c, -0.7); g.lineWidth = 1.6;
            poly(g, pts); g.fill(); g.stroke();
            // the patch: two crossed bandage strips
            g.save(); g.rotate(Math.PI / 4);
            g.fillStyle = '#f3e7d3'; rr(g, -22, -6, 44, 12, 5); g.fill(); rr(g, -6, -22, 12, 44, 5); g.fill();
            g.fillStyle = 'rgba(120,90,60,0.45)';
            for (var d = -4; d <= 4; d += 4) { g.beginPath(); g.arc(d, -2, 0.9, 0, TAU); g.arc(d, 2, 0.9, 0, TAU); g.fill(); }
            g.restore();
            g.fillStyle = shade(c, 0.35);
            for (var r2 = 0; r2 < 8; r2++) { var a2 = r2 / 8 * TAU + Math.PI / 8; g.beginPath(); g.arc(Math.cos(a2) * 26, Math.sin(a2) * 24, 1.6, 0, TAU); g.fill(); }
            plate(g, pal, 'PATCH', 0, 27, c, 9);
        },
        OPTIONS: function (g, c, pal) {
            g.fillStyle = shade(c, -0.55);
            for (var i = 0; i < 4; i++) { g.save(); g.rotate(i * Math.PI / 2); rr(g, -3.5, 14, 7, 12, 2); g.fill(); g.restore(); }
            g.fillStyle = vgrad(g, -20, 20, shade(c, 0.25), shade(c, -0.5)); g.strokeStyle = shade(c, -0.7); g.lineWidth = 1.4;
            g.beginPath(); g.arc(0, 0, 18, 0, TAU); g.fill(); g.stroke();
            g.strokeStyle = 'rgba(255,255,255,0.25)'; g.beginPath(); g.arc(0, 0, 13, 0, TAU); g.stroke();
            g.fillStyle = pal.ink; g.beginPath(); g.arc(0, 0, 9, 0, TAU); g.fill();
            plate(g, pal, 'OPT', 0, 0, c, 8);
        },
        TIMEOUT: function (g, c, pal) {
            g.fillStyle = shade(c, -0.4);
            for (var i = 0; i < 8; i++) { g.save(); g.rotate(i * Math.PI / 4); rr(g, -2, -21, 4, 7, 1.5); g.fill(); g.restore(); }
            g.fillStyle = vgrad(g, -16, 16, shade(c, 0.15), shade(c, -0.5)); g.strokeStyle = shade(c, -0.7); g.lineWidth = 1.3;
            g.beginPath(); g.arc(0, 0, 15, 0, TAU); g.fill(); g.stroke();
            g.fillStyle = '#f6efe3'; g.beginPath(); g.arc(0, 0, 9.5, 0, TAU); g.fill();
            g.strokeStyle = pal.ink; g.lineWidth = 1;
            g.beginPath(); for (var t = 0; t < 12; t++) { var a = t / 12 * TAU; g.moveTo(Math.cos(a) * 7.5, Math.sin(a) * 7.5); g.lineTo(Math.cos(a) * 9, Math.sin(a) * 9); } g.stroke();
            g.lineWidth = 1.4; g.beginPath(); g.moveTo(0, 0); g.lineTo(0, -6); g.moveTo(0, 0); g.lineTo(4, 2); g.stroke();
            plate(g, pal, '408', 0, 17, c, 7);
        },
    };

    var BOSS_DRAW = {
        b404: function (g, c, pal) {
            glowCircle(g, 0, 0, 100, c, 0.14);
            // a browser window whose page is missing
            g.fillStyle = vgrad(g, -44, 44, '#1a2030', '#0d1018'); g.strokeStyle = c; g.lineWidth = 2.5;
            rr(g, -88, -44, 176, 88, 10); g.fill(); g.stroke();
            g.fillStyle = shade(c, -0.55); rr(g, -88, -44, 176, 18, [10, 10, 0, 0]); g.fill();
            ['#ff8a80', '#f0b95a', '#6fd39a'].forEach(function (dot, n) { g.fillStyle = dot; g.beginPath(); g.arc(-76 + n * 10, -35, 3, 0, TAU); g.fill(); });
            g.fillStyle = 'rgba(255,255,255,0.12)'; rr(g, -40, -40, 110, 10, 5); g.fill();
            g.setLineDash([5, 4]); g.strokeStyle = shade(c, -0.25); g.lineWidth = 1.5;
            rr(g, -74, -18, 148, 52, 6); g.stroke(); g.setLineDash([]);
            g.fillStyle = c; g.font = '800 34px ' + pal.mono; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText('404', -14, 2);
            g.font = '700 9px ' + pal.mono; g.globalAlpha = 0.85; g.fillText('NOT FOUND', -14, 24); g.globalAlpha = 1;
            // magnifier "eye"
            g.strokeStyle = c; g.lineWidth = 3; g.beginPath(); g.arc(46, 4, 12, 0, TAU); g.stroke();
            g.beginPath(); g.moveTo(54, 13); g.lineTo(64, 24); g.stroke();
            g.fillStyle = 'rgba(255,255,255,0.18)'; g.beginPath(); g.arc(46, 4, 10, 0, TAU); g.fill();
            g.fillStyle = '#ffffff'; g.beginPath(); g.arc(46, 4, 3.5, 0, TAU); g.fill();
        },
        b429: function (g, c, pal) {
            glowCircle(g, 0, 0, 110, c, 0.12);
            // a turnstile gate with a rate-limit display
            g.fillStyle = shade(c, -0.6);
            rr(g, -98, -38, 22, 80, 5); g.fill(); rr(g, 76, -38, 22, 80, 5); g.fill();
            g.fillStyle = shade(c, -0.25);
            for (var s = -30; s <= 30; s += 12) { g.fillRect(-96, s, 18, 4); g.fillRect(78, s, 18, 4); }
            g.fillStyle = vgrad(g, -40, 40, '#262012', '#100d08'); g.strokeStyle = c; g.lineWidth = 2.5;
            poly(g, [-76, -40, 76, -40, 84, -30, 84, 30, 60, 40, -60, 40, -84, 30, -84, -30]); g.fill(); g.stroke();
            g.fillStyle = '#0a0806'; g.strokeStyle = shade(c, -0.3); g.lineWidth = 1.5;
            rr(g, -58, -32, 116, 40, 5); g.fill(); g.stroke();
            g.fillStyle = c; g.font = '800 26px ' + pal.mono; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText('429', 0, -17);
            g.font = '700 8px ' + pal.mono; g.globalAlpha = 0.85; g.fillText('TOO MANY REQUESTS', 0, 0); g.globalAlpha = 1;
            // the core housing; the glowing core is drawn live when the gate opens
            g.fillStyle = shade(c, -0.7); g.beginPath(); g.arc(0, 24, 12, 0, TAU); g.fill();
            g.strokeStyle = shade(c, -0.2); g.lineWidth = 2; g.beginPath(); g.arc(0, 24, 12, 0, TAU); g.stroke();
        },
        b500: function (g, c, pal) {
            glowCircle(g, 0, 0, 104, c, 0.16);
            g.fillStyle = vgrad(g, -36, 36, '#2a1416', '#120a0b'); g.strokeStyle = c; g.lineWidth = 3;
            poly(g, [-70, -36, 70, -36, 88, -20, 88, 18, 70, 32, 26, 32, 16, 40, -16, 40, -26, 32, -70, 32, -88, 18, -88, -20]); g.fill(); g.stroke();
            // cracks
            g.strokeStyle = shade(c, 0.2); g.lineWidth = 1.2; g.globalAlpha = 0.7;
            g.beginPath(); g.moveTo(-60, -36); g.lineTo(-52, -24); g.lineTo(-58, -14); g.lineTo(-48, -4);
            g.moveTo(62, 32); g.lineTo(54, 20); g.lineTo(60, 10); g.stroke(); g.globalAlpha = 1;
            g.fillStyle = c; g.font = '800 32px ' + pal.mono; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText('500', 0, -10);
            g.font = '700 8px ' + pal.mono; g.globalAlpha = 0.85; g.fillText('INTERNAL SERVER ERROR', 0, 14); g.globalAlpha = 1;
            g.fillStyle = shade(c, -0.45); rr(g, -12, 28, 24, 14, 4); g.fill();
            // turret mounts; the turrets themselves are separate targets
            g.fillStyle = shade(c, -0.65); g.beginPath(); g.arc(-70, 28, 15, 0, TAU); g.arc(70, 28, 15, 0, TAU); g.fill();
        },
    };

    var buildSprites = function (pal) {
        var s = {};
        s.player = [-1, 0, 1].map(function (bank) { return sprite(52, 56, function (g) { g.translate(26, 28); drawShip(g, pal, bank); }); });
        s.flames = [12, 17, 22].map(function (len) {
            return sprite(14, 26, function (g) {
                var grad = g.createLinearGradient(0, 0, 0, len);
                grad.addColorStop(0, '#ffffff'); grad.addColorStop(0.3, pal.warning); grad.addColorStop(0.7, pal.danger); grad.addColorStop(1, 'rgba(0,0,0,0)');
                g.fillStyle = grad; g.beginPath(); g.moveTo(2, 0); g.lineTo(12, 0); g.lineTo(7, len); g.closePath(); g.fill();
            });
        });
        s.hitbox = sprite(16, 16, function (g) {
            g.strokeStyle = pal.primary; g.lineWidth = 1.5; g.beginPath(); g.arc(8, 8, 6, 0, TAU); g.stroke();
            g.fillStyle = '#ffffff'; g.beginPath(); g.arc(8, 8, 3, 0, TAU); g.fill();
        });
        s.shield = sprite(76, 76, function (g) {
            var grad = g.createRadialGradient(38, 38, 20, 38, 38, 36);
            grad.addColorStop(0, 'rgba(0,0,0,0)'); grad.addColorStop(0.8, pal.primary); grad.addColorStop(1, 'rgba(0,0,0,0)');
            g.globalAlpha = 0.55; g.fillStyle = grad; g.beginPath(); g.arc(38, 38, 36, 0, TAU); g.fill();
            g.globalAlpha = 0.9; g.strokeStyle = '#ffffff'; g.lineWidth = 1.5; g.beginPath(); g.arc(38, 38, 30, -2.4, -1.4); g.stroke(); g.globalAlpha = 1;
        });
        s.drone = sprite(24, 24, function (g) {
            g.translate(12, 12);
            glowCircle(g, 0, 0, 12, pal.primary, 0.3);
            g.fillStyle = '#d8e2ee'; g.strokeStyle = pal.ink; g.lineWidth = 1;
            poly(g, [0, -9, 7, 2, 4, 7, -4, 7, -7, 2]); g.fill(); g.stroke();
            g.fillStyle = pal.primary; g.beginPath(); g.arc(0, 1, 3, 0, TAU); g.fill();
        });
        s.enemies = {}; s.enemyFlash = {};
        Object.keys(ENEMY_TYPES).forEach(function (key) {
            var t = ENEMY_TYPES[key], color = pal.colors[t.color];
            s.enemies[key] = sprite(t.w, t.h, function (g) { g.translate(t.w / 2, t.h / 2); glowCircle(g, 0, 0, t.r + 10, color, 0.18); ENEMY_DRAW[key](g, color, pal); });
            s.enemyFlash[key] = flashOf(s.enemies[key]);
        });
        s.bosses = {}; s.bossFlash = {};
        Object.keys(BOSS_DRAW).forEach(function (key) {
            s.bosses[key] = sprite(220, 120, function (g) { g.translate(110, 60); BOSS_DRAW[key](g, pal.colors[BOSSES[key].color], pal); });
            s.bossFlash[key] = flashOf(s.bosses[key]);
        });
        s.turret = sprite(34, 34, function (g) {
            g.translate(17, 17); var c = pal.colors.b500;
            g.fillStyle = vgrad(g, -14, 14, shade(c, 0.15), shade(c, -0.5)); g.strokeStyle = shade(c, -0.7); g.lineWidth = 1.4;
            g.beginPath(); g.arc(0, 0, 13, 0, TAU); g.fill(); g.stroke();
            g.fillStyle = pal.ink; rr(g, -3, 4, 6, 12, 2); g.fill();
            g.fillStyle = '#ffffff'; g.globalAlpha = 0.8; g.beginPath(); g.arc(0, -2, 3, 0, TAU); g.fill(); g.globalAlpha = 1;
        });
        s.turretFlash = flashOf(s.turret);
        // player shots
        s.shot = sprite(10, 22, function (g) {
            glowCircle(g, 5, 11, 10, pal.primary, 0.55);
            g.fillStyle = '#ffffff'; rr(g, 3.5, 2, 3, 18, 1.5); g.fill();
        });
        s.spread = sprite(12, 12, function (g) {
            glowCircle(g, 6, 6, 6, pal.warning, 0.8);
            g.fillStyle = '#ffffff'; g.beginPath(); g.arc(6, 6, 2.2, 0, TAU); g.fill();
        });
        s.laser = sprite(12, 36, function (g) {
            var grad = g.createLinearGradient(0, 0, 12, 0);
            grad.addColorStop(0, 'rgba(0,0,0,0)'); grad.addColorStop(0.5, pal.colors.post); grad.addColorStop(1, 'rgba(0,0,0,0)');
            g.fillStyle = grad; g.fillRect(0, 0, 12, 36);
            g.fillStyle = '#ffffff'; g.fillRect(5, 0, 2, 36);
        });
        s.homing = sprite(14, 14, function (g) {
            glowCircle(g, 7, 7, 7, pal.success, 0.75);
            g.fillStyle = '#ffffff'; g.beginPath(); g.moveTo(7, 1.5); g.lineTo(10.5, 10); g.lineTo(3.5, 10); g.closePath(); g.fill();
        });
        s.drone_shot = sprite(8, 14, function (g) {
            glowCircle(g, 4, 7, 6, pal.primary, 0.5);
            g.fillStyle = '#e8fffb'; rr(g, 2.5, 1, 3, 12, 1.5); g.fill();
        });
        // enemy bullets: round, so they never need rotating
        s.orbs = {
            orb: sprite(16, 16, function (g) { glowCircle(g, 8, 8, 8, pal.danger, 0.9); g.fillStyle = '#ffffff'; g.beginPath(); g.arc(8, 8, 3, 0, TAU); g.fill(); }),
            big: sprite(26, 26, function (g) {
                glowCircle(g, 13, 13, 13, pal.colors.patch, 0.85);
                g.strokeStyle = '#ffffff'; g.lineWidth = 1.5; g.globalAlpha = 0.6; g.beginPath(); g.arc(13, 13, 7, 0, TAU); g.stroke(); g.globalAlpha = 1;
                g.fillStyle = '#ffffff'; g.beginPath(); g.arc(13, 13, 4, 0, TAU); g.fill();
            }),
            needle: sprite(14, 14, function (g) {
                glowCircle(g, 7, 7, 7, pal.warning, 0.85);
                g.fillStyle = '#ffffff'; poly(g, [7, 2, 10, 7, 7, 12, 4, 7]); g.fill();
            }),
        };
        s.capsules = {};
        Object.keys(CAPSULES).forEach(function (kind) {
            var color = pal.capsule[kind];
            s.capsules[kind] = sprite(48, 26, function (g) {
                glowCircle(g, 24, 13, 22, color, 0.28);
                g.fillStyle = vgrad(g, 4, 22, '#1d242e', '#0b0e12'); g.strokeStyle = color; g.lineWidth = 2;
                rr(g, 4, 4, 40, 18, 9); g.fill(); g.stroke();
                g.fillStyle = 'rgba(255,255,255,0.18)'; rr(g, 8, 6, 32, 5, 2.5); g.fill();
                g.fillStyle = color; g.font = '800 9px ' + pal.mono; g.textAlign = 'center'; g.textBaseline = 'middle';
                g.fillText(CAPSULES[kind], 24, 13.5);
            });
        });
        s.bombIcon = sprite(18, 18, function (g) {
            g.fillStyle = vgrad(g, 4, 16, '#3a2a2a', '#120c0c'); g.strokeStyle = pal.danger; g.lineWidth = 1.4;
            g.beginPath(); g.arc(9, 10.5, 6, 0, TAU); g.fill(); g.stroke();
            g.strokeStyle = pal.warning; g.lineWidth = 1.4; g.beginPath(); g.moveTo(12, 5.5); g.quadraticCurveTo(14, 2, 16, 2.5); g.stroke();
            g.fillStyle = '#ffffff'; g.beginPath(); g.arc(16, 2.5, 1.4, 0, TAU); g.fill();
            g.fillStyle = 'rgba(255,255,255,0.45)'; g.beginPath(); g.arc(7, 8.5, 1.6, 0, TAU); g.fill();
        });
        // effects, one per colour, scaled when drawn
        s.ring = {}; s.glow = {};
        pal.fxColors.forEach(function (color) {
            s.ring[color] = sprite(64, 64, function (g) { g.strokeStyle = color; g.lineWidth = 3; g.beginPath(); g.arc(32, 32, 28, 0, TAU); g.stroke(); g.globalAlpha = 0.35; g.lineWidth = 7; g.beginPath(); g.arc(32, 32, 27, 0, TAU); g.stroke(); });
            s.glow[color] = sprite(64, 64, function (g) { glowCircle(g, 32, 32, 32, color, 0.9); glowCircle(g, 32, 32, 12, '#ffffff', 0.8); });
        });
        // per-stage nebula, drawn at 1x: it is soft anyway and is scrolled every frame
        s.sky = STAGES.map(function (stage, n) {
            var c = document.createElement('canvas'); c.width = W; c.height = H; c.w = W; c.h = H;
            var g = c.getContext('2d'), rnd = makeRandom(97 + n * 31);
            for (var k = 0; k < 7; k++) {
                var x = rnd() * W, y = rnd() * H, r = 120 + rnd() * 180, color = stage.sky[k % stage.sky.length];
                for (var copy = -1; copy <= 1; copy++) glowCircle(g, x, y + copy * H, r, color, 0.55);
            }
            return c;
        });
        return s;
    };

    var palette = function () {
        // The game always has a dark background, so it keeps its own colours and only takes the deployment
        // accent from the page (light theme colours would be too dark here).
        var root = document.documentElement;
        var blue = root.getAttribute('data-accent') === 'blue';
        var mono = (getComputedStyle(root).getPropertyValue('--font-mono') || '').trim() || 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
        var p = {
            primary: blue ? '#8db2ff' : '#46d3be', primaryDeep: blue ? '#2f4f99' : '#137565',
            success: '#5fcb8c', warning: '#f0b95a', danger: '#ff8a80',
            mono: mono, ink: '#0b0e12', panel: '#12161c', bg: '#05070a', text: '#e8edf3', muted: '#8a94a3',
            colors: {
                get: '#6fd39a', head: '#7fe0e6', post: '#7fb4ff', put: '#f0b95a', delete: '#ff8a80', patch: '#c9aeff',
                options: '#b8c6d9', timeout: '#ffad6b', b404: '#9fb1ff', b429: '#f0b95a', b500: '#ff6f6a',
            },
        };
        p.colors.primary = p.primary; p.colors.danger = p.danger;
        p.capsule = { spread: p.warning, laser: p.colors.post, homing: p.success, bomb: p.danger, shield: p.primary, drone: '#d8e2ee', life: '#ff8fc4' };
        var fx = [p.primary, p.success, p.warning, p.danger, '#ffffff'];
        Object.keys(p.colors).forEach(function (key) { if (fx.indexOf(p.colors[key]) < 0) fx.push(p.colors[key]); });
        p.fxColors = fx;
        return p;
    };

    // ---------- game ----------
    var game = null;

    var create = function (options) {
        var t = TEXT[lang()];
        var pal = palette();
        var sp = buildSprites(pal);
        var random = makeRandom(options.seed || (Date.now() ^ ((performance.now() * 1000) | 0)));
        var rand = function (a, b) { return a + random() * (b - a); };
        var pick = function (list) { return list[(random() * list.length) | 0]; };
        var reduced = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
        // Phones and tablets get touch instructions instead of the keyboard ones.
        var touchOnly = !!(window.matchMedia && window.matchMedia('(hover: none) and (pointer: coarse)').matches);
        // Automated tests can make the ship unbreakable to walk through every stage.
        var invulnerable = options.invulnerable === true;

        var overlay = document.createElement('div');
        overlay.className = 'echo-strike';
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true');
        overlay.setAttribute('aria-label', 'Echo Strike');
        var canvas = document.createElement('canvas');
        canvas.className = 'echo-strike__canvas';
        canvas.tabIndex = 0;
        canvas.setAttribute('aria-label', t.title + ' — ' + (touchOnly ? t.tapStart : t.start) + '. ' + (touchOnly ? t.bombHintTouch : t.bombHint));
        var close = document.createElement('button');
        close.type = 'button'; close.className = 'echo-strike__close'; close.setAttribute('aria-label', t.keys[5][1]); close.title = t.keys[5][1];
        close.innerHTML = '<i class="bi bi-x-lg" aria-hidden="true"></i>';
        overlay.appendChild(canvas); overlay.appendChild(close);
        document.body.appendChild(overlay);
        var ctx = canvas.getContext('2d');

        var fit = function () {
            var dpr = Math.min(window.devicePixelRatio || 1, 2);
            var scale = Math.min((window.innerWidth - 24) / W, (window.innerHeight - 24) / H);
            canvas.style.width = Math.floor(W * scale) + 'px';
            canvas.style.height = Math.floor(H * scale) + 'px';
            canvas.width = Math.round(W * scale * dpr); canvas.height = Math.round(H * scale * dpr);
            ctx.setTransform(canvas.width / W, 0, 0, canvas.height / H, 0, 0);
            ctx.imageSmoothingEnabled = true;
        };
        fit();

        // ---------- state ----------
        var input = { left: false, right: false, up: false, down: false, fire: false, focus: false };
        var touch = { id: null, x: 0, y: 0, lastTap: 0, dx: 0 };
        var stars = []; for (var si = 0; si < 90; si++) stars.push({ x: rand(0, W), y: rand(0, H), z: si < 45 ? 0.35 : si < 75 ? 0.6 : 1 });
        var shots = pool(200, function () { return { x: 0, y: 0, vx: 0, vy: 0, kind: 'shot', dmg: 1, r: 4 }; });
        var orbs = pool(520, function () { return { x: 0, y: 0, vx: 0, vy: 0, r: 4, kind: 'orb' }; });
        var foes = pool(80, function () { return { x: 0, y: 0, vx: 0, vy: 0, t: 0, hp: 1, type: 'GET', fire: 0, phase: 0, flash: 0, turn: 0, tx: 0, side: 1, timer: 0 }; });
        var sparks = pool(420, function () { return { x: 0, y: 0, vx: 0, vy: 0, life: 0, max: 1, color: '#fff', size: 2 }; });
        var fx = pool(72, function () { return { x: 0, y: 0, img: null, life: 0, max: 1, from: 1, to: 1 }; });
        var pops = pool(24, function () { return { x: 0, y: 0, text: '', color: '#fff', life: 0, max: 1, size: 12 }; });
        var caps = pool(10, function () { return { x: 0, y: 0, vy: 0, t: 0, kind: 'weapon' }; });
        var drones = [{ x: W / 2, y: H }, { x: W / 2, y: H }];
        var boss = { on: false, kind: 'b404', x: W / 2, y: -80, hp: 0, max: 0, t: 0, phase: 0, fire: 0, flash: 0, mode: 'enter', modeTime: 0, alpha: 1, cx: W / 2, burst: 0, open: false,
            turrets: [{ dx: -70, hp: 0, max: 0, flash: 0, fire: 0 }, { dx: 70, hp: 0, max: 0, flash: 0, fire: 0 }] };
        var player, state, score, hi = readHi(), lives, bombs, weapon, level, droneCount, shield, loop, stageIndex, phase, phaseTime, stageTime,
            spawnClock, midDone, dropsDone, chain, chainTime, maxChain, stageChain, stageKills, stageMisses, totalKills, shake, flashAlpha,
            banner, bannerSub, bannerTime, bannerColor, hint, hintTime, fireClock, droneClock, bombFx, bombX, bombY, noBombTime, dangerClock,
            dangerCooldown, bombedThisRun, gameoverTime, continueTime, continues, results, scoreText, scoreShown, hiText, hiShown, chainText, chainShown, newRecord, bank;
        var everBombed = readNumber(BOMBED_KEY) > 0;

        var difficulty = function () { return 1 + loop * 0.35 + stageIndex * 0.12 + Math.min(stageTime / 60, 1) * 0.25; };
        var say = function (text, sub, seconds, color) { banner = text; bannerSub = sub || ''; bannerTime = seconds; bannerColor = color || pal.text; };
        var tip = function (text, seconds) { hint = text; hintTime = seconds; };

        var startStage = function () {
            phase = 'intro'; phaseTime = 0; stageTime = 0; spawnClock = 2.6; midDone = false; dropsDone = 0;
            stageKills = 0; stageMisses = 0; stageChain = 0;
            var info = t.stages[stageIndex];
            say(t.stage + ' ' + (stageIndex + 1) + (loop ? '  ·  ' + t.loopTag + ' ' + (loop + 1) : ''), info.name + ' — ' + info.sub, 2.6, pal.colors[STAGES[stageIndex].accent]);
        };

        var reset = function () {
            player = { x: W / 2, y: H - 100, inv: 2, alive: true, respawn: 0 };
            score = 0; lives = 3; bombs = 3; weapon = 'spread'; level = 1; droneCount = 0; shield = false; loop = 0; stageIndex = 0;
            chain = 0; chainTime = 0; maxChain = 0; totalKills = 0; shake = 0; flashAlpha = 0; banner = ''; bannerSub = ''; bannerTime = 0; bannerColor = pal.text;
            hint = ''; hintTime = 0; fireClock = 0; droneClock = 0; bombFx = 0; bombX = 0; bombY = 0; noBombTime = 0; dangerClock = 0; dangerCooldown = 0;
            bombedThisRun = false; gameoverTime = 0; continueTime = 0; continues = 0; results = null; scoreShown = -1; hiShown = -1; chainShown = -1; newRecord = false; bank = 0;
            [shots, orbs, foes, sparks, fx, pops, caps].forEach(function (list) { list.forEach(function (o) { o.on = false; }); });
            boss.on = false;
            stageTime = 0;
            startStage();
        };
        var begin = function () { reset(); state = 'play'; };
        loop = 0; stageIndex = 0; stageTime = 0;
        reset();
        state = 'title';

        // ---------- effects ----------
        var burst = function (x, y, color, n, speed) {
            for (var k = 0; k < n; k++) {
                var p = spawn(sparks); if (!p) return;
                var a = random() * TAU, s = rand(speed * 0.3, speed);
                p.x = x; p.y = y; p.vx = Math.cos(a) * s; p.vy = Math.sin(a) * s; p.life = p.max = rand(0.35, 0.85); p.color = color; p.size = rand(1.5, 3.6);
            }
        };
        var effect = function (img, x, y, from, to, life) {
            var e = spawn(fx); if (!e) return;
            e.img = img; e.x = x; e.y = y; e.from = from; e.to = to; e.life = e.max = life;
        };
        var explode = function (x, y, color, size) {
            effect(sp.glow[color] || sp.glow['#ffffff'], x, y, size * 1.6, size * 0.4, 0.35);
            effect(sp.ring[color] || sp.ring['#ffffff'], x, y, size * 0.4, size * 2.2, 0.45);
            burst(x, y, color, Math.min(30, 8 + (size / 3) | 0), 120 + size * 4);
            burst(x, y, '#ffffff', 4, 160);
        };
        var popup = function (x, y, text, color, size) {
            var p = spawn(pops); if (!p) return;
            p.x = x; p.y = y; p.text = text; p.color = color; p.size = size || 12; p.life = p.max = 0.9;
        };
        var bump = function (amount) { if (!reduced) shake = Math.max(shake, amount); };
        var flash = function (amount) { if (!reduced) flashAlpha = Math.max(flashAlpha, amount); };

        // ---------- scoring ----------
        var multiplier = function () { return 1 + Math.min(7, (chain / 10) | 0); };
        var addScore = function (points) { score += points * (1 + loop); if (score > hi) hi = score; };

        // ---------- enemies ----------
        var enemyShot = function (x, y, angle, speed, kind) {
            var o = spawn(orbs); if (!o) return;
            o.kind = kind || 'orb'; o.r = o.kind === 'big' ? 7 : o.kind === 'needle' ? 3 : 4;
            o.x = x; o.y = y; o.vx = Math.cos(angle) * speed; o.vy = Math.sin(angle) * speed;
        };
        var ring = function (x, y, n, offset, speed, kind) { for (var k = 0; k < n; k++) enemyShot(x, y, offset + k / n * TAU, speed, kind); };
        var aimAt = function (x, y) { return Math.atan2(player.y - y, player.x - x); };

        var addFoe = function (type, x, y, vx, vy) {
            var f = spawn(foes); if (!f) return null;
            var def = ENEMY_TYPES[type], d = difficulty();
            f.type = type; f.x = x; f.y = y; f.vx = vx || 0; f.vy = vy == null ? def.speed : vy; f.t = 0; f.phase = rand(0, TAU);
            f.hp = Math.ceil(def.hp * (0.8 + d * 0.3)); f.fire = rand(0.6, 1.6) / d; f.flash = 0; f.turn = 0; f.tx = x; f.side = 1; f.timer = 4;
            return f;
        };

        var formation = function (type) {
            var d = difficulty(), n, k, f, side;
            if (type === 'GET') {
                n = 4 + ((d * 2) | 0); side = random() < 0.5;
                for (k = 0; k < n; k++) addFoe('GET', side ? 60 + k * 38 : W - 60 - k * 38, -30 - k * 34);
            } else if (type === 'HEAD') {
                // a swarm that swoops in from one side and curls down across the screen
                side = random() < 0.5 ? 1 : -1; n = 5 + (d | 0);
                for (k = 0; k < n; k++) {
                    f = addFoe('HEAD', side > 0 ? -20 - k * 30 : W + 20 + k * 30, 70 + rand(-10, 10), side * 230, 40);
                    if (f) f.turn = side * 1.1;
                }
            } else if (type === 'POST') {
                n = 3 + (d | 0);
                for (k = 0; k < n; k++) addFoe('POST', W * (k + 1) / (n + 1), -40 - (k % 2) * 40);
            } else if (type === 'PUT') {
                for (k = 0; k < 2; k++) addFoe('PUT', k ? W * 0.72 : W * 0.28, -40);
            } else if (type === 'DELETE') {
                n = 3 + (d | 0);
                for (k = 0; k < n; k++) addFoe('DELETE', rand(40, W - 40), -40 - k * 60, 0, 60);
            } else if (type === 'PATCH') {
                addFoe('PATCH', rand(120, W - 120), -50);
            } else if (type === 'OPTIONS') {
                n = d > 1.4 ? 2 : 1;
                for (k = 0; k < n; k++) {
                    side = k % 2 ? 1 : -1;
                    f = addFoe('OPTIONS', side < 0 ? -30 : W + 30, 110 + k * 90 + rand(0, 40), 0, 0);
                    if (f) { f.side = side; f.tx = side < 0 ? rand(60, 160) : rand(W - 160, W - 60); }
                }
            } else if (type === 'TIMEOUT') {
                n = 2 + (d > 1.5 ? 1 : 0);
                for (k = 0; k < n; k++) addFoe('TIMEOUT', W * (k + 1) / (n + 1) + rand(-30, 30), -30 - k * 50);
            }
        };

        var spawnWave = function () {
            var d = difficulty(), mix = STAGES[stageIndex].mix, total = 0, k;
            if (stageTime < 8) formation('GET');
            else {
                for (k = 0; k < mix.length; k++) total += mix[k][1];
                var roll = random() * total;
                for (k = 0; k < mix.length; k++) { roll -= mix[k][1]; if (roll <= 0) { formation(mix[k][0]); break; } }
            }
            spawnClock = rand(1.5, 2.5) / d;
        };

        var dropCapsule = function (x, y, kind) {
            var c = spawn(caps); if (!c) return;
            c.x = clamp(x, 30, W - 30); c.y = y; c.vy = 70; c.t = 0; c.kind = kind || 'weapon';
        };

        var killFoe = function (f) {
            var def = ENEMY_TYPES[f.type], color = pal.colors[def.color];
            chain++; chainTime = 1.8; if (chain > maxChain) maxChain = chain; if (chain > stageChain) stageChain = chain;
            var gained = def.score * multiplier();
            addScore(gained);
            stageKills++; totalKills++;
            explode(f.x, f.y, color, def.r * 1.4);
            if (def.score >= 400) popup(f.x, f.y - 10, '+' + gained * (1 + loop), color, 12);
            bump(f.type === 'PATCH' ? 7 : f.type === 'PUT' || f.type === 'OPTIONS' ? 3 : 1.5);
            var roll = random();
            if (f.type === 'PATCH') { dropCapsule(f.x, f.y, 'weapon'); if (roll < 0.5) dropCapsule(f.x + 30, f.y, roll < 0.25 ? 'shield' : 'drone'); }
            else if (f.type === 'PUT' && roll < 0.35) dropCapsule(f.x, f.y, 'weapon');
            else if (f.type === 'OPTIONS' && roll < 0.35) dropCapsule(f.x, f.y, pick(['weapon', 'shield', 'drone']));
            else if (f.type === 'TIMEOUT' && roll < 0.25) dropCapsule(f.x, f.y, 'bomb');
            else if (roll < 0.04) dropCapsule(f.x, f.y, 'weapon');
            else if (roll < 0.055) dropCapsule(f.x, f.y, 'bomb');
            f.on = false;
        };

        // ---------- boss ----------
        var startBoss = function () {
            var def = BOSSES[STAGES[stageIndex].boss];
            boss.on = true; boss.kind = STAGES[stageIndex].boss; boss.x = W / 2; boss.cx = W / 2; boss.y = -80; boss.t = 0; boss.phase = 0;
            boss.fire = 1.6; boss.flash = 0; boss.mode = 'enter'; boss.modeTime = 0; boss.alpha = 1; boss.burst = 0; boss.open = false;
            boss.max = boss.hp = Math.round(def.hp * (1 + loop * 0.6));
            for (var k = 0; k < 2; k++) { var tr = boss.turrets[k]; tr.max = tr.hp = boss.kind === 'b500' ? Math.round(55 * (1 + loop * 0.5)) : 0; tr.flash = 0; tr.fire = 1 + k * 0.5; }
            phase = 'boss'; phaseTime = 0;
        };
        var bossVulnerable = function () { return boss.on && boss.mode !== 'enter' && boss.alpha > 0.6; };
        var hitBoss = function (dmg) {
            if (!bossVulnerable()) return false;
            boss.hp -= boss.open ? dmg * 2 : dmg; boss.flash = 0.05; addScore(10);
            return true;
        };
        var hitTurret = function (tr, dmg) {
            tr.hp -= dmg; tr.flash = 0.05; addScore(10);
            if (tr.hp <= 0) {
                explode(boss.x + tr.dx, boss.y + 28, pal.colors.b500, 30); bump(6);
                addScore(3000); popup(boss.x + tr.dx, boss.y + 10, '+' + 3000 * (1 + loop), pal.warning, 13);
            }
        };
        var turretAt = function (x, y, r) {
            if (!boss.on || boss.kind !== 'b500' || boss.mode === 'enter') return null;
            for (var k = 0; k < 2; k++) {
                var tr = boss.turrets[k]; if (tr.hp <= 0) continue;
                var dx = boss.x + tr.dx - x, dy = boss.y + 28 - y, rad = 15 + r;
                if (dx * dx + dy * dy < rad * rad) return tr;
            }
            return null;
        };

        var defeatBoss = function () {
            boss.on = false;
            var color = pal.colors[BOSSES[boss.kind].color];
            for (var b = 0; b < 7; b++) explode(boss.x + rand(-80, 80), boss.y + rand(-30, 30), b % 2 ? color : pal.warning, rand(24, 44));
            bump(16); flash(0.6);
            for (var i = 0; i < orbs.length; i++) if (orbs[i].on) { orbs[i].on = false; burst(orbs[i].x, orbs[i].y, pal.success, 1, 40); }
            addScore(20000);
            dropCapsule(boss.x - 30, boss.y, 'life'); dropCapsule(boss.x + 30, boss.y, 'bomb');
            phase = 'down'; phaseTime = 0;
        };

        var updateBoss = function (dt) {
            var d = 1 + loop * 0.25, k, a;
            boss.t += dt; boss.modeTime += dt; if (boss.flash > 0) boss.flash -= dt;
            if (boss.mode === 'enter') {
                boss.y += 60 * dt;
                if (boss.y >= 140) { boss.y = 140; boss.mode = boss.kind === 'b429' ? 'burst' : 'shown'; boss.modeTime = 0; }
                return;
            }
            var ratio = boss.hp / boss.max;
            boss.phase = ratio > 0.5 ? 0 : 1;
            if (!player.alive) return;
            var bs = (170 + loop * 30);
            if (boss.kind === 'b404') {
                // fades out, moves while invisible, comes back with a ring
                if (boss.mode === 'shown') {
                    boss.alpha = Math.min(1, boss.alpha + dt * 3);
                    boss.x = boss.cx + Math.sin(boss.t * 0.7) * 90;
                    boss.fire -= dt;
                    if (boss.fire <= 0) {
                        a = aimAt(boss.x, boss.y + 30);
                        for (k = -2; k <= 2; k++) enemyShot(boss.x, boss.y + 30, a + k * 0.15, bs, 'orb');
                        boss.fire = 0.9 / d;
                    }
                    if (boss.phase === 1 && (boss.modeTime % 3) < 1.6) {
                        boss.burst -= dt;
                        if (boss.burst <= 0) { for (k = 0; k < 2; k++) enemyShot(boss.x, boss.y + 10, boss.t * 3.2 + k * Math.PI, bs * 0.8, 'needle'); boss.burst = 0.09; }
                    }
                    if (boss.modeTime > 6.5) { boss.mode = 'fade'; boss.modeTime = 0; }
                } else if (boss.mode === 'fade') {
                    boss.alpha = Math.max(0.12, 1 - boss.modeTime * 2);
                    if (boss.modeTime > 0.5) { boss.mode = 'hidden'; boss.modeTime = 0; boss.cx = rand(120, W - 120); }
                } else if (boss.mode === 'hidden') {
                    boss.x += (boss.cx - boss.x) * Math.min(1, dt * 4);
                    if (boss.modeTime > 0.7) { boss.mode = 'shown'; boss.modeTime = 0; ring(boss.x, boss.y, 16 + boss.phase * 6, boss.t, bs * 0.8, 'big'); }
                }
            } else if (boss.kind === 'b429') {
                // fires a stream, then cools down for "Retry-After" seconds with its core open
                boss.x = W / 2 + Math.sin(boss.t * 0.45) * 110;
                if (boss.mode === 'burst') {
                    boss.open = false;
                    boss.fire -= dt;
                    if (boss.fire <= 0) {
                        a = aimAt(boss.x, boss.y + 24);
                        if (boss.phase === 0) enemyShot(boss.x, boss.y + 24, a + rand(-0.05, 0.05), bs * 1.25, 'needle');
                        else for (k = -1; k <= 1; k++) enemyShot(boss.x, boss.y + 24, a + k * 0.28, bs * 1.2, 'needle');
                        boss.fire = 0.075; boss.burst++;
                    }
                    if (boss.burst >= 16 + loop * 4) {
                        boss.mode = 'cool'; boss.modeTime = 0; boss.burst = 0;
                        for (k = 0; k < 2; k++) { var m = addFoe('GET', boss.x + (k ? 60 : -60), boss.y + 20, k ? 40 : -40, 120); if (m) m.fire = 0.8; }
                    }
                } else {
                    boss.open = true;
                    boss.fire -= dt;
                    if (boss.fire <= 0) { ring(boss.x, boss.y, 10, boss.t, bs * 0.7, 'orb'); boss.fire = 1.1; }
                    if (boss.modeTime > (boss.phase === 0 ? 2.6 : 1.9) / d) { boss.mode = 'burst'; boss.modeTime = 0; boss.fire = 0.3; }
                }
            } else {
                // 500: three phases plus two side turrets that can be shot off first
                boss.x = W / 2 + Math.sin(boss.t * 0.6) * 120;
                boss.phase = ratio > 0.66 ? 0 : ratio > 0.33 ? 1 : 2;
                boss.fire -= dt;
                if (boss.fire <= 0) {
                    if (boss.phase === 0) { a = aimAt(boss.x, boss.y + 30); for (k = -2; k <= 2; k++) enemyShot(boss.x, boss.y + 30, a + k * 0.16, bs, 'orb'); boss.fire = 0.75 / d; }
                    else if (boss.phase === 1) { for (k = 0; k < 4; k++) enemyShot(boss.x, boss.y + 20, boss.t * 3 + k * Math.PI / 2, bs * 0.9, 'needle'); boss.fire = 0.16; }
                    else { ring(boss.x, boss.y + 10, 18, boss.t, bs * 0.75, 'big'); boss.fire = 0.6 / d; }
                }
                for (k = 0; k < 2; k++) {
                    var tr = boss.turrets[k]; if (tr.hp <= 0) continue;
                    if (tr.flash > 0) tr.flash -= dt;
                    tr.fire -= dt;
                    if (tr.fire <= 0) { var tx = boss.x + tr.dx, ty = boss.y + 36; a = aimAt(tx, ty); for (var n = -1; n <= 1; n++) enemyShot(tx, ty, a + n * 0.1, bs * 1.1, 'orb'); tr.fire = 1.2 / d; }
                }
            }
        };

        // ---------- player ----------
        var hitPlayer = function () {
            if (player.inv > 0 || !player.alive || invulnerable) return;
            if (shield) {
                shield = false; player.inv = 1.5;
                explode(player.x, player.y, pal.primary, 40); bump(5);
                for (var i = 0; i < orbs.length; i++) { var o = orbs[i]; if (!o.on) continue; var dx = o.x - player.x, dy = o.y - player.y; if (dx * dx + dy * dy < 12100) o.on = false; }
                return;
            }
            explode(player.x, player.y, pal.primary, 50); burst(player.x, player.y, '#ffffff', 20, 260);
            bump(10); flash(0.35);
            lives--; player.alive = false; player.respawn = 1.3; stageMisses++;
            level = Math.max(1, level - 1); droneCount = Math.max(0, droneCount - 1); chain = 0;
            if (lives < 0) {
                // the run is not over yet: offer a continue first
                state = 'continue'; continueTime = 10;
                if (score > readHi()) { newRecord = true; writeHi(score); }
            }
        };

        var bombButton = { x: W - 172, y: H - 50, w: 162, h: 40 };
        var inBombButton = function (p) { return p.x >= bombButton.x && p.x <= bombButton.x + bombButton.w && p.y >= bombButton.y - 8 && p.y <= bombButton.y + bombButton.h; };

        var continueRun = function () {
            if (state !== 'continue') return;
            continues++; lives = 3; bombs = Math.max(bombs, 3); score = 0; chain = 0; newRecord = false;
            player.respawn = 0.4; state = 'play';
        };

        var useBomb = function () {
            if (state !== 'play' || !player.alive) return;
            if (bombs <= 0) { noBombTime = 1; popup(bombButton.x + bombButton.w / 2, bombButton.y - 12, t.noBomb, pal.danger, 12); return; }
            bombs--; bombFx = 1; bombX = player.x; bombY = player.y; flash(0.5); bump(12);
            bombedThisRun = true;
            if (!everBombed) { everBombed = true; writeNumber(BOMBED_KEY, 1); if (hintTime > 0) hintTime = 0; }
            // every enemy bullet becomes a few points
            var cleared = 0, i;
            for (i = 0; i < orbs.length; i++) { var o = orbs[i]; if (!o.on) continue; o.on = false; cleared++; burst(o.x, o.y, pal.success, 1, 50); }
            if (cleared) { addScore(cleared * 10); popup(player.x, player.y - 40, '+' + cleared * 10 * (1 + loop), pal.success, 12); }
            for (i = 0; i < foes.length; i++) { var f = foes[i]; if (f.on && f.y > -20) { f.hp -= 14; f.flash = 0.1; if (f.hp <= 0) killFoe(f); } }
            if (boss.on) {
                hitBoss(45);
                for (i = 0; i < 2; i++) if (boss.turrets[i].hp > 0 && boss.mode !== 'enter') hitTurret(boss.turrets[i], 30);
            }
            player.inv = Math.max(player.inv, 1.6);
        };

        var fireShots = function () {
            var x = player.x, y = player.y - 18, s, k, o;
            var narrow = input.focus ? 0.4 : 1;
            if (weapon === 'spread') {
                var spreads = [[-0.08, 0.08], [-0.22, -0.08, 0.08, 0.22], [-0.34, -0.2, -0.07, 0.07, 0.2, 0.34], [-0.46, -0.32, -0.19, -0.07, 0.07, 0.19, 0.32, 0.46]][level - 1];
                s = spawn(shots); if (s) { s.kind = 'shot'; s.x = x; s.y = y; s.vx = 0; s.vy = -740; s.dmg = 1.2; s.r = 5; }
                for (k = 0; k < spreads.length; k++) { o = spawn(shots); if (!o) break; var a = spreads[k] * narrow; o.kind = 'spread'; o.x = x; o.y = y; o.vx = Math.sin(a) * 620; o.vy = -Math.cos(a) * 620; o.dmg = 0.8; o.r = 5; }
                fireClock = 0.1;
            } else if (weapon === 'laser') {
                var offsets = [[0], [-6, 6], [-10, 0, 10], [-14, -5, 5, 14]][level - 1];
                for (k = 0; k < offsets.length; k++) { o = spawn(shots); if (!o) break; o.kind = 'laser'; o.x = x + offsets[k] * narrow; o.y = y - 8; o.vx = 0; o.vy = -1000; o.dmg = 1.1 + level * 0.15; o.r = 5; }
                fireClock = 0.07;
            } else {
                s = spawn(shots); if (s) { s.kind = 'shot'; s.x = x; s.y = y; s.vx = 0; s.vy = -740; s.dmg = 1; s.r = 5; }
                for (k = 0; k < level; k++) {
                    o = spawn(shots); if (!o) break;
                    var side = k % 2 ? 1 : -1;
                    o.kind = 'homing'; o.x = x + side * 12; o.y = y + 8; o.vx = side * 160; o.vy = -260; o.dmg = 1.4; o.r = 6;
                }
                fireClock = 0.14;
            }
        };

        var nearestTarget = function (x, y) {
            var best = null, dist = 1e9;
            for (var i = 0; i < foes.length; i++) { var f = foes[i]; if (!f.on || f.y < 0) continue; var d = (f.x - x) * (f.x - x) + (f.y - y) * (f.y - y); if (d < dist) { dist = d; best = f; } }
            if (bossVulnerable()) { var bd = (boss.x - x) * (boss.x - x) + (boss.y - y) * (boss.y - y); if (bd < dist) best = boss; }
            return best;
        };

        var collect = function (c) {
            var label, color = pal.capsule[c.kind === 'weapon' ? WEAPONS[((c.t * 0.8) | 0) % WEAPONS.length] : c.kind];
            addScore(500);
            if (c.kind === 'bomb') { if (bombs < 5) { bombs++; label = t.pickups.bomb + (touchOnly ? '' : ' · X'); } else { addScore(2000); label = t.pickups.max; } }
            else if (c.kind === 'shield') { shield = true; label = t.pickups.shield; }
            else if (c.kind === 'drone') { if (droneCount < 2) { droneCount++; label = t.pickups.drone; } else { addScore(2000); label = t.pickups.max; } }
            else if (c.kind === 'life') { if (lives < 5) { lives++; label = t.pickups.life; } else { addScore(5000); label = t.pickups.max; } }
            else {
                var kind = WEAPONS[((c.t * 0.8) | 0) % WEAPONS.length];
                if (kind === weapon) { level = Math.min(level + 1, 4); label = t.pickups.levelUp + ' Lv' + level; }
                else { weapon = kind; level = Math.max(level, 2); label = t.pickups[kind]; }
            }
            popup(c.x, c.y - 16, label, color, 13);
            burst(c.x, c.y, color, 12, 160);
        };

        // ---------- update ----------
        var update = function (dt) {
            var i, o, f;
            var sky = phase === 'boss' ? 2.2 : 1;
            for (i = 0; i < stars.length; i++) { var st = stars[i]; st.y += (40 + 160 * st.z) * dt * sky; if (st.y > H) { st.y = -2; st.x = rand(0, W); } }
            for (i = 0; i < sparks.length; i++) { o = sparks[i]; if (!o.on) continue; o.life -= dt; if (o.life <= 0) { o.on = false; continue; } o.x += o.vx * dt; o.y += o.vy * dt; o.vx *= 0.96; o.vy *= 0.96; }
            for (i = 0; i < fx.length; i++) { o = fx[i]; if (!o.on) continue; o.life -= dt; if (o.life <= 0) o.on = false; }
            for (i = 0; i < pops.length; i++) { o = pops[i]; if (!o.on) continue; o.life -= dt; o.y -= 26 * dt; if (o.life <= 0) o.on = false; }
            if (bannerTime > 0) bannerTime -= dt;
            if (hintTime > 0) hintTime -= dt;
            if (noBombTime > 0) noBombTime -= dt;
            if (shake > 0) shake = Math.max(0, shake - 30 * dt);
            if (flashAlpha > 0) flashAlpha = Math.max(0, flashAlpha - dt * 1.6);
            if (bombFx > 0) bombFx = Math.max(0, bombFx - dt * 1.3);
            if (state === 'over') { gameoverTime += dt; return; }
            if (state === 'continue') {
                continueTime -= dt;
                if (continueTime <= 0) { state = 'over'; gameoverTime = 0; }
                return;
            }
            if (state !== 'play') return;

            phaseTime += dt;
            // player
            var mx = 0;
            if (player.alive) {
                var speed = input.focus ? 150 : 290, my = (input.down ? 1 : 0) - (input.up ? 1 : 0);
                mx = (input.right ? 1 : 0) - (input.left ? 1 : 0);
                if (mx && my) { mx *= 0.7071; my *= 0.7071; }
                player.x = clamp(player.x + mx * speed * dt, 18, W - 18);
                player.y = clamp(player.y + my * speed * dt, 40, H - 64);
                if (player.inv > 0) player.inv -= dt;
                fireClock -= dt;
                if (fireClock <= 0 && phase !== 'results' && phase !== 'loop') fireShots();
                // drones trail the ship and fire straight ahead
                for (i = 0; i < droneCount; i++) {
                    var dr = drones[i], gap = input.focus ? 20 : 34;
                    var tx = player.x + (i ? gap : -gap), ty = player.y + 12;
                    dr.x += (tx - dr.x) * Math.min(1, dt * 10); dr.y += (ty - dr.y) * Math.min(1, dt * 10);
                }
                droneClock -= dt;
                if (droneCount && droneClock <= 0 && phase !== 'results' && phase !== 'loop') {
                    for (i = 0; i < droneCount; i++) { o = spawn(shots); if (!o) break; o.kind = 'drone_shot'; o.x = drones[i].x; o.y = drones[i].y - 10; o.vx = 0; o.vy = -760; o.dmg = 0.6; o.r = 4; }
                    droneClock = 0.13;
                }
            } else {
                player.respawn -= dt;
                if (player.respawn <= 0 && lives >= 0) { player.alive = true; player.x = W / 2; player.y = H - 100; player.inv = 2.4; drones[0].x = drones[1].x = player.x; drones[0].y = drones[1].y = H; }
            }
            if (touch.id != null) mx = clamp(touch.dx / 6, -1, 1);
            touch.dx *= 0.8;
            bank += (mx - bank) * Math.min(1, dt * 12);

            // player shots
            for (i = 0; i < shots.length; i++) {
                o = shots[i]; if (!o.on) continue;
                if (o.kind === 'homing') {
                    var target = nearestTarget(o.x, o.y);
                    if (target) {
                        var want = Math.atan2(target.y - o.y, target.x - o.x), cur = Math.atan2(o.vy, o.vx), sp2 = Math.min(Math.sqrt(o.vx * o.vx + o.vy * o.vy) + 900 * dt, 620);
                        var diff = Math.atan2(Math.sin(want - cur), Math.cos(want - cur));
                        cur += clamp(diff, -6 * dt, 6 * dt);
                        o.vx = Math.cos(cur) * sp2; o.vy = Math.sin(cur) * sp2;
                    }
                }
                o.x += o.vx * dt; o.y += o.vy * dt;
                if (o.y < -30 || o.x < -30 || o.x > W + 30 || o.y > H + 30) o.on = false;
            }

            // stage flow
            if (phase === 'intro') {
                if (phaseTime > 2.6) {
                    phase = 'waves'; phaseTime = 0;
                    if (!everBombed && stageIndex === 0 && loop === 0) tip(touchOnly ? t.bombHintTouch : t.bombHint, 5);
                }
            } else if (phase === 'waves') {
                var stage = STAGES[stageIndex];
                stageTime += dt;
                if (stageTime < stage.length) {
                    spawnClock -= dt; if (spawnClock <= 0) spawnWave();
                    if (!midDone && stageTime > stage.length * 0.5) { midDone = true; for (i = 0; i < stage.mid.length; i++) formation(stage.mid[i]); }
                    if (dropsDone === 0 && stageTime > stage.length * 0.3) { dropsDone = 1; dropCapsule(rand(80, W - 80), -20, droneCount < 2 ? 'drone' : 'weapon'); }
                    if (dropsDone === 1 && stageTime > stage.length * 0.7) { dropsDone = 2; dropCapsule(rand(80, W - 80), -20, shield ? 'bomb' : 'shield'); }
                } else if (count(foes) === 0 || stageTime > stage.length + 8) {
                    phase = 'warning'; phaseTime = 0;
                    var bt = t.bosses[stage.boss];
                    say(t.warning, bt.name + ' — ' + bt.warn, 2.6, pal.danger);
                }
            } else if (phase === 'warning') {
                if (phaseTime > 2.6) startBoss();
            } else if (phase === 'boss') {
                if (boss.on) updateBoss(dt);
                if (boss.on && boss.hp <= 0) defeatBoss();
            } else if (phase === 'down') {
                if (phaseTime > 1.6) {
                    var noMiss = stageMisses === 0 ? 10000 * (stageIndex + 1) : 0, stageBonus = 5000 * (stageIndex + 1);
                    addScore(noMiss + stageBonus);
                    results = { stage: stageIndex + 1, kills: stageKills, chain: stageChain, noMiss: noMiss * (1 + loop), bonus: stageBonus * (1 + loop) };
                    phase = 'results'; phaseTime = 0;
                }
            } else if (phase === 'results') {
                if (phaseTime > 4.5) {
                    results = null;
                    if (stageIndex === STAGES.length - 1) {
                        loop++; stageIndex = 0; phase = 'loop'; phaseTime = 0;
                        say(t.loop, t.loopNext.replace('{n}', String(loop + 1)), 3, pal.primary);
                    } else { stageIndex++; startStage(); }
                }
            } else if (phase === 'loop') {
                if (phaseTime > 3) startStage();
            }

            // enemies
            var d = difficulty();
            for (i = 0; i < foes.length; i++) {
                f = foes[i]; if (!f.on) continue;
                f.t += dt; if (f.flash > 0) f.flash -= dt;
                if (f.type === 'POST') f.x += Math.sin(f.t * 2.4 + f.phase) * 120 * dt;
                else if (f.type === 'HEAD') {
                    // the swarm's velocity turns steadily, so it curls across and down
                    var ang = Math.atan2(f.vy, f.vx) + f.turn * dt, vel = Math.sqrt(f.vx * f.vx + f.vy * f.vy);
                    if (f.t > 0.6) { f.vx = Math.cos(ang) * vel; f.vy = Math.sin(ang) * vel; }
                    if (f.t > 2.2) f.turn *= 0.97;
                } else if (f.type === 'PUT') {
                    if (f.y > 150 && f.t < 6) f.vy = Math.max(0, f.vy - 160 * dt);
                    if (f.t > 6) f.vy = Math.min(f.vy + 120 * dt, 120);
                } else if (f.type === 'DELETE' && f.y > 60 && f.t < 4 && player.alive) {
                    var a = aimAt(f.x, f.y); f.vx += Math.cos(a) * 420 * dt; f.vy += Math.sin(a) * 420 * dt;
                    var len = Math.sqrt(f.vx * f.vx + f.vy * f.vy), cap = 230 * d; if (len > cap) { f.vx *= cap / len; f.vy *= cap / len; }
                } else if (f.type === 'PATCH') {
                    f.x += Math.sin(f.t * 0.8 + f.phase) * 40 * dt;
                    if (f.y > 170 && f.t < 10) f.vy = Math.max(0, f.vy - 60 * dt);
                    if (f.t > 10) f.vy = Math.min(f.vy + 60 * dt, 90);
                } else if (f.type === 'OPTIONS') {
                    // slides in from its side, holds position, then leaves the same way
                    if (f.t < 7) f.x += (f.tx - f.x) * Math.min(1, dt * 2.2);
                    else f.vx = f.side * -160;
                } else if (f.type === 'TIMEOUT') {
                    f.x += Math.sin(f.t * 1.3 + f.phase) * 30 * dt;
                    if (f.y > 30) {
                        f.timer -= dt;
                        if (f.timer <= 0) {
                            // timed out: bursts into a ring of bullets instead of giving points
                            ring(f.x, f.y, 14, f.phase, 150 + 30 * d, 'orb');
                            explode(f.x, f.y, pal.colors.timeout, 26); bump(3);
                            f.on = false; continue;
                        }
                    }
                }
                f.x += f.vx * dt; f.y += f.vy * dt;
                if (f.y > H + 60 || f.x < -90 || f.x > W + 90 || (f.type === 'HEAD' && f.t > 8)) { f.on = false; continue; }
                if (f.y > 0 && f.y < H * 0.72 && player.alive) {
                    f.fire -= dt;
                    if (f.fire <= 0) {
                        var bs = 150 + 40 * d, k;
                        if (f.type === 'GET') { enemyShot(f.x, f.y, aimAt(f.x, f.y), bs); f.fire = rand(1.6, 2.8) / d; }
                        else if (f.type === 'POST') { var base = aimAt(f.x, f.y); for (k = -1; k <= 1; k++) enemyShot(f.x, f.y, base + k * 0.22, bs); f.fire = rand(1.6, 2.4) / d; }
                        else if (f.type === 'PUT') { ring(f.x, f.y, 10, f.t, bs * 0.8, 'orb'); f.fire = 1.4 / d; }
                        else if (f.type === 'PATCH') { for (k = 0; k < 3; k++) enemyShot(f.x, f.y, f.t * 2.2 + k * 2.094, bs * 0.85, 'big'); f.fire = 0.14; }
                        else if (f.type === 'OPTIONS' && f.t > 0.8 && f.t < 7) { var aim = aimAt(f.x, f.y); for (k = 0; k < 3; k++) enemyShot(f.x, f.y + 10, aim, bs * (1 + k * 0.12), 'needle'); f.fire = 1.4 / d; }
                        else { f.fire = 9; }
                    }
                }
            }

            // enemy bullets
            var hitR = invulnerable ? 0 : 4;
            for (i = 0; i < orbs.length; i++) {
                o = orbs[i]; if (!o.on) continue;
                o.x += o.vx * dt; o.y += o.vy * dt;
                if (o.y < -24 || o.y > H + 24 || o.x < -24 || o.x > W + 24) { o.on = false; continue; }
                if (player.alive && player.inv <= 0 && hitR) {
                    var dx = o.x - player.x, dy = o.y - player.y, rad = o.r + hitR - 1;
                    if (dx * dx + dy * dy < rad * rad) { o.on = false; hitPlayer(); }
                }
            }

            // capsules (the weapon capsule cycles colour: it gives the weapon it shows)
            for (i = 0; i < caps.length; i++) {
                var c = caps[i]; if (!c.on) continue;
                c.t += dt; c.y += c.vy * dt; c.x += Math.sin(c.t * 2) * 30 * dt;
                if (c.y > H + 20) { c.on = false; continue; }
                if (player.alive && Math.abs(c.x - player.x) < 28 && Math.abs(c.y - player.y) < 24) { c.on = false; collect(c); }
            }

            // collisions: shots vs foes / boss / turrets
            var bossDef = boss.on ? BOSSES[boss.kind] : null;
            for (i = 0; i < shots.length; i++) {
                o = shots[i]; if (!o.on) continue;
                for (var j = 0; j < foes.length; j++) {
                    f = foes[j]; if (!f.on || f.y < -10) continue;
                    var rr2 = ENEMY_TYPES[f.type].r + o.r, ex = f.x - o.x, ey = f.y - o.y;
                    if (ex * ex + ey * ey < rr2 * rr2) {
                        f.hp -= o.dmg; f.flash = 0.06; if (o.kind !== 'laser' || f.hp <= 0) o.on = false;
                        if (f.hp <= 0) killFoe(f);
                        if (!o.on) break;
                    }
                }
                if (!o.on || !boss.on) continue;
                var tr = turretAt(o.x, o.y, o.r);
                if (tr) { o.on = false; hitTurret(tr, o.dmg); continue; }
                if (Math.abs(o.x - boss.x) < bossDef.hw && Math.abs(o.y - boss.y) < bossDef.hh && bossVulnerable()) { o.on = false; hitBoss(o.dmg); }
            }

            // ramming
            if (player.alive && player.inv <= 0) {
                for (i = 0; i < foes.length; i++) {
                    f = foes[i]; if (!f.on) continue;
                    var fr = ENEMY_TYPES[f.type].r;
                    if (Math.abs(f.x - player.x) < fr + 5 && Math.abs(f.y - player.y) < fr + 5) { killFoe(f); hitPlayer(); break; }
                }
                if (boss.on && bossDef && Math.abs(boss.x - player.x) < bossDef.hw && Math.abs(boss.y - player.y) < bossDef.hh && bossVulnerable()) hitPlayer();
            }

            // chain decays when nothing is destroyed for a moment
            if (chainTime > 0) { chainTime -= dt; if (chainTime <= 0) chain = 0; }

            // a reminder about the bomb when the screen gets crowded
            if (dangerCooldown > 0) dangerCooldown -= dt;
            dangerClock -= dt;
            if (dangerClock <= 0 && player.alive) {
                dangerClock = 0.25;
                if (bombs > 0 && !bombedThisRun && dangerCooldown <= 0) {
                    var near = 0;
                    for (i = 0; i < orbs.length; i++) { o = orbs[i]; if (!o.on) continue; var ddx = o.x - player.x, ddy = o.y - player.y; if (ddx * ddx + ddy * ddy < 14400) near++; }
                    if (near >= 10) { tip(touchOnly ? t.dangerTouch : t.danger, 2.5); dangerCooldown = 25; }
                }
            }
        };

        // ---------- render ----------
        var drawSprite = function (img, x, y, alpha) {
            if (alpha != null) ctx.globalAlpha = alpha;
            ctx.drawImage(img, x - img.w / 2, y - img.h / 2, img.w, img.h);
            if (alpha != null) ctx.globalAlpha = 1;
        };
        var text = function (str, x, y, size, color, align, weight) {
            ctx.font = (weight || 700) + ' ' + size + 'px ' + pal.mono; ctx.fillStyle = color; ctx.textAlign = align || 'center'; ctx.textBaseline = 'middle'; ctx.fillText(str, x, y);
        };
        var keycap = function (label, x, y, align) {
            ctx.font = '700 11px ' + pal.mono;
            var w = Math.max(22, ctx.measureText(label).width + 12), left = align === 'right' ? x - w : x;
            ctx.fillStyle = 'rgba(255,255,255,0.08)'; ctx.strokeStyle = 'rgba(255,255,255,0.35)'; ctx.lineWidth = 1;
            rr(ctx, left, y - 10, w, 20, 4); ctx.fill(); ctx.stroke();
            ctx.fillStyle = 'rgba(255,255,255,0.18)'; ctx.fillRect(left + 2, y + 7, w - 4, 1.5);
            text(label, left + w / 2, y, 11, pal.text, 'center', 700);
            return w;
        };
        var controls = function (y) {
            var rows = touchOnly ? t.touchKeys : t.keys;
            for (var n = 0; n < rows.length; n++) {
                var ry = y + n * 28, isBomb = rows[n][0] === 'X' || rows[n][0] === 'BOMB';
                keycap(rows[n][0], W / 2 - 12, ry, 'right');
                text(rows[n][1], W / 2 + 4, ry, 12, isBomb ? pal.warning : pal.muted, 'left', isBomb ? 700 : 500);
            }
        };
        var band = function (y, h, color, alpha) {
            ctx.globalAlpha = alpha; ctx.fillStyle = 'rgba(5,7,10,0.78)'; ctx.fillRect(0, y - h / 2, W, h);
            ctx.fillStyle = color; ctx.fillRect(0, y - h / 2, W, 2); ctx.fillRect(0, y + h / 2 - 2, W, 2);
            ctx.globalAlpha = 1;
        };

        var drawBackground = function (now) {
            ctx.fillStyle = pal.bg; ctx.fillRect(-20, -20, W + 40, H + 40);
            var skyImg = sp.sky[stageIndex], scroll = ((now / 1000) * 14) % H;
            ctx.drawImage(skyImg, 0, scroll - H, W, H); ctx.drawImage(skyImg, 0, scroll, W, H);
            // a faint grid scrolling with the stage
            var accent = pal.colors[STAGES[stageIndex].accent], gy = ((now / 1000) * 40) % 48, i;
            ctx.strokeStyle = accent; ctx.globalAlpha = 0.07; ctx.lineWidth = 1; ctx.beginPath();
            for (i = 24; i < W; i += 48) { ctx.moveTo(i, 0); ctx.lineTo(i, H); }
            for (i = gy - 48; i < H; i += 48) { ctx.moveTo(0, i); ctx.lineTo(W, i); }
            ctx.stroke();
            for (i = 0; i < stars.length; i++) { var st = stars[i]; ctx.globalAlpha = 0.25 + st.z * 0.6; ctx.fillStyle = st.z === 1 ? '#ffffff' : pal.muted; ctx.fillRect(st.x, st.y, st.z * 2, st.z * (2 + st.z * 3)); }
            ctx.globalAlpha = 1;
        };

        var drawBoss = function (now) {
            var img = sp.bosses[boss.kind], alpha = boss.alpha;
            drawSprite(img, boss.x, boss.y, alpha < 1 ? alpha : null);
            if (boss.flash > 0 && alpha > 0.6 && ((now / 70) | 0) % 2 === 0) drawSprite(sp.bossFlash[boss.kind], boss.x, boss.y, 0.35);
            if (boss.kind === 'b429') {
                if (boss.open) {
                    var pulse = 0.6 + Math.sin(now / 80) * 0.3;
                    drawSprite(sp.glow[pal.warning], boss.x, boss.y + 24, pulse);
                    var left = Math.max(0, ((boss.phase === 0 ? 2.6 : 1.9) / (1 + loop * 0.25)) - boss.modeTime);
                    text(t.retryAfter + ': ' + left.toFixed(1) + 's', boss.x, boss.y + 58, 11, pal.warning, 'center', 700);
                }
            } else if (boss.kind === 'b500' && boss.mode !== 'enter') {
                for (var k = 0; k < 2; k++) {
                    var tr = boss.turrets[k]; if (tr.hp <= 0) continue;
                    drawSprite(sp.turret, boss.x + tr.dx, boss.y + 28);
                    if (tr.flash > 0 && ((now / 70) | 0) % 2 === 0) drawSprite(sp.turretFlash, boss.x + tr.dx, boss.y + 28, 0.6);
                }
            }
        };

        var drawHud = function (now) {
            var i;
            if (score !== scoreShown) { scoreShown = score; scoreText = pad(score); }
            if (hi !== hiShown) { hiShown = hi; hiText = t.hi + ' ' + pad(hi); }
            text(scoreText, 16, 30, 17, pal.text, 'left');
            text(hiText, W - 16, 30, 12, pal.muted, 'right', 600);
            if (continues) text(t.continues + ' ×' + continues, W - 16, 50, 10, pal.muted, 'right', 600);
            text(t.stage + ' ' + (stageIndex + 1), W / 2, 30, 11, pal.muted, 'center', 600);
            if (chain >= 3) {
                if (chain !== chainShown) { chainShown = chain; chainText = t.chain + ' ' + chain + '  ×' + multiplier(); }
                text(chainText, 16, 52, 12, chain >= 30 ? pal.warning : pal.primary, 'left', 700);
                ctx.fillStyle = 'rgba(255,255,255,0.12)'; ctx.fillRect(16, 62, 90, 3);
                ctx.fillStyle = pal.primary; ctx.fillRect(16, 62, 90 * (chainTime / 1.8), 3);
            }
            if (boss.on && boss.mode !== 'enter') {
                var color = pal.colors[BOSSES[boss.kind].color];
                text(t.bosses[boss.kind].name, W / 2, 76, 10, color, 'center', 700);
                ctx.fillStyle = 'rgba(255,255,255,0.12)'; ctx.fillRect(40, 86, W - 80, 6);
                ctx.fillStyle = color; ctx.fillRect(40, 86, (W - 80) * Math.max(0, boss.hp / boss.max), 6);
                ctx.fillStyle = 'rgba(5,7,10,0.9)';
                if (boss.kind === 'b500') { ctx.fillRect(40 + (W - 80) * 0.66 - 1, 86, 2, 6); ctx.fillRect(40 + (W - 80) * 0.33 - 1, 86, 2, 6); }
                else ctx.fillRect(40 + (W - 80) * 0.5 - 1, 86, 2, 6);
            }
            // bottom bar
            ctx.fillStyle = 'rgba(5,7,10,0.55)'; ctx.fillRect(0, H - 56, W, 56);
            ctx.fillStyle = 'rgba(255,255,255,0.08)'; ctx.fillRect(0, H - 56, W, 1);
            for (i = 0; i < Math.max(lives, 0); i++) ctx.drawImage(sp.player[1], 10 + i * 22, H - 44, 22, 24);
            var ix = 14 + Math.max(lives, 0) * 22;
            if (shield) { ctx.drawImage(sp.shield, ix, H - 46, 26, 26); ix += 28; }
            for (i = 0; i < droneCount; i++) { ctx.drawImage(sp.drone, ix, H - 42, 18, 18); ix += 18; }
            // weapon + level pips
            text(t.weapons[weapon], W / 2 - 12, H - 36, 12, pal.capsule[weapon], 'center', 700);
            for (i = 0; i < 4; i++) { ctx.fillStyle = i < level ? pal.capsule[weapon] : 'rgba(255,255,255,0.15)'; ctx.fillRect(W / 2 - 34 + i * 12, H - 22, 9, 5); }
            // the bomb button: says which key it is and can be clicked or tapped
            var b = bombButton, jiggle = noBombTime > 0 && !reduced ? Math.sin(now / 25) * 3 * noBombTime : 0;
            var edge = noBombTime > 0 ? pal.danger : bombs > 0 ? 'rgba(255,138,128,0.65)' : 'rgba(255,255,255,0.2)';
            ctx.fillStyle = noBombTime > 0 ? 'rgba(255,138,128,0.16)' : 'rgba(255,255,255,0.05)'; ctx.strokeStyle = edge; ctx.lineWidth = 1.5;
            rr(ctx, b.x + jiggle, b.y, b.w, b.h, 8); ctx.fill(); ctx.stroke();
            var kx = b.x + jiggle + 8;
            if (touchOnly) text(t.bomb, kx + 4, b.y + b.h / 2, 11, pal.danger, 'left', 800);
            else { keycap('X', kx, b.y + b.h / 2, 'left'); text(t.bomb, kx + 28, b.y + b.h / 2, 10, pal.danger, 'left', 800); }
            var icons = b.x + jiggle + (touchOnly ? 52 : 70);
            if (bombs === 0) text('0', icons + 6, b.y + b.h / 2, 12, pal.muted, 'left', 700);
            for (i = 0; i < bombs; i++) ctx.drawImage(sp.bombIcon, icons + i * 14, b.y + 11, 18, 18);
        };

        var drawOverlays = function (now) {
            // stage card / warning banner
            if (bannerTime > 0) {
                var alpha = Math.min(1, bannerTime * 2, (2.6 - bannerTime) * 3 + 0.2);
                var warn = banner === t.warning;
                band(H * 0.4, 86, bannerColor, alpha);
                ctx.globalAlpha = warn ? alpha * (0.6 + 0.4 * Math.abs(Math.sin(now / 160))) : alpha;
                text(banner, W / 2, H * 0.4 - 12, banner.length > 22 ? 18 : 26, bannerColor, 'center', 800);
                ctx.globalAlpha = alpha;
                if (bannerSub) text(bannerSub, W / 2, H * 0.4 + 20, 12, pal.text, 'center', 600);
                ctx.globalAlpha = 1;
            }
            if (hintTime > 0 && state === 'play') {
                ctx.globalAlpha = Math.min(1, hintTime * 2);
                ctx.font = '700 12px ' + pal.mono;
                var hw = ctx.measureText(hint).width + 28;
                ctx.fillStyle = 'rgba(5,7,10,0.85)'; ctx.strokeStyle = pal.warning; ctx.lineWidth = 1.2;
                rr(ctx, W / 2 - hw / 2, H - 98, hw, 28, 14); ctx.fill(); ctx.stroke();
                text(hint, W / 2, H - 84, 12, pal.warning, 'center', 700);
                ctx.globalAlpha = 1;
            }
            if (results) {
                var a2 = Math.min(1, phaseTime * 3);
                ctx.globalAlpha = a2;
                ctx.fillStyle = 'rgba(5,7,10,0.85)'; ctx.strokeStyle = pal.primary; ctx.lineWidth = 1.5;
                rr(ctx, 70, H * 0.26, W - 140, 230, 12); ctx.fill(); ctx.stroke();
                text(t.stage + ' ' + results.stage + '  ' + t.clear, W / 2, H * 0.26 + 34, 20, pal.primary, 'center', 800);
                var rows = [[t.kills, String(results.kills)], [t.maxChain, String(results.chain)], [t.stageBonus, '+' + results.bonus], [t.noMiss, results.noMiss ? '+' + results.noMiss : '—'], [t.total, scoreText]];
                for (var n = 0; n < rows.length; n++) {
                    if (phaseTime < 0.35 + n * 0.3) break;
                    var ry = H * 0.26 + 80 + n * 30;
                    text(rows[n][0], 100, ry, 13, pal.muted, 'left', 600);
                    text(rows[n][1], W - 100, ry, 13, n === rows.length - 1 ? pal.text : n === 3 && results.noMiss ? pal.success : pal.text, 'right', 700);
                }
                ctx.globalAlpha = 1;
            }
        };

        var render = function (now) {
            var i, o;
            ctx.save();
            if (shake > 0) ctx.translate(rand(-shake, shake), rand(-shake, shake));
            drawBackground(now);
            // capsules
            for (i = 0; i < caps.length; i++) { var c = caps[i]; if (!c.on) continue; drawSprite(sp.capsules[c.kind === 'weapon' ? WEAPONS[((c.t * 0.8) | 0) % WEAPONS.length] : c.kind], c.x, c.y); }
            // enemies
            for (i = 0; i < foes.length; i++) {
                var f = foes[i]; if (!f.on) continue;
                drawSprite(sp.enemies[f.type], f.x, f.y);
                if (f.flash > 0 && ((now / 60) | 0) % 2 === 0) drawSprite(sp.enemyFlash[f.type], f.x, f.y, 0.75);
                if (f.type === 'TIMEOUT' && f.y > 30) {
                    // the countdown ring empties until the mine goes off
                    var left = Math.max(0, f.timer / 4);
                    ctx.strokeStyle = left < 0.3 && ((now / 90) | 0) % 2 ? '#ffffff' : pal.colors.timeout; ctx.lineWidth = 2.5;
                    ctx.beginPath(); ctx.arc(f.x, f.y, 21, -Math.PI / 2, -Math.PI / 2 + left * TAU); ctx.stroke();
                }
            }
            if (boss.on) drawBoss(now);
            // shots
            for (i = 0; i < shots.length; i++) { o = shots[i]; if (!o.on) continue; drawSprite(sp[o.kind], o.x, o.y); }
            // player
            if (player.alive && state !== 'title' && (player.inv <= 0 || ((now / 80) | 0) % 2 === 0)) {
                for (i = 0; i < droneCount; i++) drawSprite(sp.drone, drones[i].x, drones[i].y);
                drawSprite(sp.flames[((now / 60) | 0) % 3], player.x - 4, player.y + 28, 0.85);
                drawSprite(sp.flames[((now / 60 + 1) | 0) % 3], player.x + 4, player.y + 28, 0.85);
                drawSprite(sp.player[bank < -0.35 ? 0 : bank > 0.35 ? 2 : 1], player.x, player.y);
                if (shield) drawSprite(sp.shield, player.x, player.y, 0.75 + Math.sin(now / 150) * 0.2);
                if (input.focus) drawSprite(sp.hitbox, player.x, player.y);
            }
            // enemy bullets on top so they are always readable
            for (i = 0; i < orbs.length; i++) { o = orbs[i]; if (!o.on) continue; drawSprite(sp.orbs[o.kind], o.x, o.y); }
            // effects and sparks
            for (i = 0; i < fx.length; i++) {
                o = fx[i]; if (!o.on) continue;
                var p = 1 - o.life / o.max, size = o.from + (o.to - o.from) * p;
                ctx.globalAlpha = o.life / o.max; ctx.drawImage(o.img, o.x - size / 2, o.y - size / 2, size, size);
            }
            for (i = 0; i < sparks.length; i++) { o = sparks[i]; if (!o.on) continue; ctx.globalAlpha = o.life / o.max; ctx.fillStyle = o.color; ctx.fillRect(o.x, o.y, o.size, o.size); }
            ctx.globalAlpha = 1;
            if (bombFx > 0) {
                var radius = (1 - bombFx) * 560;
                ctx.globalAlpha = bombFx; ctx.drawImage(sp.ring[pal.primary], bombX - radius, bombY - radius, radius * 2, radius * 2);
                ctx.globalAlpha = bombFx * 0.6; ctx.drawImage(sp.ring['#ffffff'], bombX - radius * 0.8, bombY - radius * 0.8, radius * 1.6, radius * 1.6);
                ctx.globalAlpha = 1;
            }
            // score popups
            for (i = 0; i < pops.length; i++) { o = pops[i]; if (!o.on) continue; ctx.globalAlpha = Math.min(1, o.life / o.max * 2); text(o.text, o.x, o.y, o.size, o.color, 'center', 800); }
            ctx.globalAlpha = 1;
            ctx.restore();
            if (flashAlpha > 0) { ctx.fillStyle = '#ffffff'; ctx.globalAlpha = flashAlpha; ctx.fillRect(0, 0, W, H); ctx.globalAlpha = 1; }

            drawHud(now);
            if (state !== 'title') drawOverlays(now);

            if (state === 'title') {
                ctx.fillStyle = 'rgba(5,7,10,0.72)'; ctx.fillRect(0, 0, W, H);
                drawSprite(sp.glow[pal.primary], W / 2, H * 0.2, 0.35);
                drawSprite(sp.player[1], W / 2, H * 0.19 + Math.sin(now / 400) * 6);
                drawSprite(sp.flames[((now / 60) | 0) % 3], W / 2 - 4, H * 0.19 + 28 + Math.sin(now / 400) * 6, 0.85);
                drawSprite(sp.flames[((now / 60 + 1) | 0) % 3], W / 2 + 4, H * 0.19 + 28 + Math.sin(now / 400) * 6, 0.85);
                text(t.title, W / 2, H * 0.3, 40, pal.primary, 'center', 800);
                text(t.tagline, W / 2, H * 0.3 + 34, 12, pal.muted, 'center', 500);
                // a line-up of what is coming
                var lineup = ['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'OPTIONS', 'TIMEOUT', 'PATCH'];
                for (i = 0; i < lineup.length; i++) {
                    var img = sp.enemies[lineup[i]], sc = Math.min(1, 34 / img.h);
                    ctx.drawImage(img, 44 + i * 56 - img.w * sc / 2, H * 0.43 - img.h * sc / 2 + Math.sin(now / 300 + i) * 2, img.w * sc, img.h * sc);
                }
                ctx.globalAlpha = 0.55 + 0.45 * Math.abs(Math.sin(now / 500));
                text(touchOnly ? t.tapStart : t.start, W / 2, H * 0.52, 15, pal.text, 'center', 700);
                ctx.globalAlpha = 1;
                if (!touchOnly) text(t.clickStart, W / 2, H * 0.52 + 20, 10, pal.muted, 'center', 500);
                controls(H * 0.6);
                if (hi > 0) text(hiText || (t.hi + ' ' + pad(hi)), W / 2, H * 0.6 + (touchOnly ? t.touchKeys : t.keys).length * 28 + 14, 12, pal.muted, 'center', 600);
            } else if (state === 'pause') {
                ctx.fillStyle = 'rgba(5,7,10,0.7)'; ctx.fillRect(0, 0, W, H);
                text(t.paused, W / 2, H * 0.3, 30, pal.text); text(t.resume, W / 2, H * 0.3 + 34, 12, pal.muted, 'center', 500);
                controls(H * 0.45);
            } else if (state === 'continue') {
                ctx.fillStyle = 'rgba(5,7,10,0.72)'; ctx.fillRect(0, 0, W, H);
                text(t.continueTitle, W / 2, H * 0.32, 30, pal.warning, 'center', 800);
                var secs = Math.max(0, Math.ceil(continueTime)), tick = continueTime - Math.floor(continueTime);
                text(String(secs), W / 2, H * 0.43, 64 + (reduced ? 0 : tick * 10), pal.text, 'center', 800);
                ctx.globalAlpha = 0.55 + 0.45 * Math.abs(Math.sin(now / 400));
                text(touchOnly ? t.continueTap : t.continueKey, W / 2, H * 0.53, 15, pal.text, 'center', 700);
                ctx.globalAlpha = 1;
                text(t.continueNote, W / 2, H * 0.53 + 26, 11, pal.muted, 'center', 500);
                text(scoreText, W / 2, H * 0.62, 18, pal.muted, 'center', 700);
                if (newRecord) text(t.record, W / 2, H * 0.62 + 24, 12, pal.warning, 'center', 800);
            } else if (state === 'over') {
                ctx.fillStyle = 'rgba(5,7,10,' + Math.min(0.78, gameoverTime) + ')'; ctx.fillRect(0, 0, W, H);
                text(t.over, W / 2, H * 0.36, 30, pal.danger, 'center', 800);
                text(scoreText, W / 2, H * 0.44, 24, pal.text);
                if (newRecord) text(t.record, W / 2, H * 0.44 + 28, 13, pal.warning, 'center', 800);
                text(t.reached + ': ' + t.stage + ' ' + (stageIndex + 1) + (loop ? ' · ' + t.loopTag + ' ' + (loop + 1) : '') + ' · ' + t.kills + ' ' + totalKills + ' · ' + t.maxChain + ' ' + maxChain, W / 2, H * 0.52, 11, pal.muted, 'center', 600);
                text(hiText, W / 2, H * 0.56, 12, pal.muted, 'center', 600);
                if (gameoverTime > 0.8) text(touchOnly ? t.tapRetry : t.retry, W / 2, H * 0.64, 13, pal.text, 'center', 600);
            }
        };

        // ---------- loop ----------
        var raf = 0, last = 0, acc = 0, running = false;
        var frame = function (now) {
            raf = requestAnimationFrame(frame);
            var dt = Math.min((now - last) / 1000, STEP * MAX_STEPS); last = now; acc += dt;
            var steps = 0;
            while (acc >= STEP && steps < MAX_STEPS) { update(STEP); acc -= STEP; steps++; }
            render(now);
        };
        var start = function () { if (running) return; running = true; last = performance.now(); acc = 0; raf = requestAnimationFrame(frame); };
        var stop = function () { running = false; cancelAnimationFrame(raf); };

        // ---------- input ----------
        var KEYMAP = { ArrowLeft: 'left', KeyA: 'left', ArrowRight: 'right', KeyD: 'right', ArrowUp: 'up', KeyW: 'up', ArrowDown: 'down', KeyS: 'down',
            KeyZ: 'fire', Space: 'fire', Enter: 'fire', NumpadEnter: 'fire', ShiftLeft: 'focus', ShiftRight: 'focus', KeyX: 'bomb' };
        var onKeyDown = function (e) {
            // The game owns the keyboard while open: nothing reaches the app underneath.
            e.stopImmediatePropagation();
            if (e.key === 'Escape') { e.preventDefault(); api.close(); return; }
            if (e.code === 'Tab') { e.preventDefault(); (document.activeElement === close ? canvas : close).focus(); return; }
            if (document.activeElement === close && (e.code === 'Enter' || e.code === 'Space')) return;
            var action = KEYMAP[e.code];
            if (e.code === 'KeyP' && (state === 'play' || state === 'pause')) { state = state === 'play' ? 'pause' : 'play'; e.preventDefault(); return; }
            if (!action) return;
            e.preventDefault();
            if (action === 'fire' && !e.repeat) {
                if (state === 'title') { begin(); return; }
                if (state === 'continue') { continueRun(); return; }
                if (state === 'over' && gameoverTime > 0.8) { begin(); return; }
            }
            if (action === 'bomb') { if (!e.repeat) useBomb(); return; }
            input[action] = true;
        };
        var onKeyUp = function (e) {
            e.stopImmediatePropagation();
            var action = KEYMAP[e.code]; if (action) { input[action] = false; e.preventDefault(); }
        };
        var toGame = function (e) { var r = canvas.getBoundingClientRect(); return { x: (e.clientX - r.left) * W / r.width, y: (e.clientY - r.top) * H / r.height }; };
        var onPointerDown = function (e) {
            var p = toGame(e), now = performance.now();
            // Any pointer, mouse included, can start, resume and press the bomb button.
            if (state === 'title' || (state === 'over' && gameoverTime > 0.8)) { begin(); return; }
            if (state === 'continue') { continueRun(); return; }
            if (state === 'pause') { state = 'play'; return; }
            if (state === 'play' && inBombButton(p)) { useBomb(); return; }
            if (e.pointerType === 'mouse') return;
            if (now - touch.lastTap < 280) useBomb();
            touch.lastTap = now; touch.id = e.pointerId; touch.x = p.x; touch.y = p.y; touch.dx = 0;
            if (canvas.setPointerCapture) canvas.setPointerCapture(e.pointerId);
        };
        var onPointerMove = function (e) {
            if (e.pointerId !== touch.id || !player.alive || state !== 'play') return;
            var p = toGame(e);
            player.x = clamp(player.x + (p.x - touch.x) * 1.3, 18, W - 18);
            player.y = clamp(player.y + (p.y - touch.y) * 1.3, 40, H - 64);
            touch.dx += p.x - touch.x;
            touch.x = p.x; touch.y = p.y;
        };
        var onPointerUp = function (e) { if (e.pointerId === touch.id) touch.id = null; };
        var releaseKeys = function () { Object.keys(input).forEach(function (k) { input[k] = false; }); };
        var onVisibility = function () {
            if (document.hidden) { stop(); if (state === 'play') state = 'pause'; releaseKeys(); }
            else start();
        };
        var onBlur = function () { if (state === 'play') state = 'pause'; releaseKeys(); };

        window.addEventListener('keydown', onKeyDown, true);
        window.addEventListener('keyup', onKeyUp, true);
        window.addEventListener('resize', fit);
        window.addEventListener('blur', onBlur);
        document.addEventListener('visibilitychange', onVisibility);
        canvas.addEventListener('pointerdown', onPointerDown);
        canvas.addEventListener('pointermove', onPointerMove);
        canvas.addEventListener('pointerup', onPointerUp);
        canvas.addEventListener('pointercancel', onPointerUp);
        close.addEventListener('click', function () { api.close(); });
        overlay.addEventListener('pointerdown', function (e) { if (e.target === overlay) api.close(); });

        var api = {
            close: function () {
                stop();
                if (score > readHi()) writeHi(score);
                window.removeEventListener('keydown', onKeyDown, true);
                window.removeEventListener('keyup', onKeyUp, true);
                window.removeEventListener('resize', fit);
                window.removeEventListener('blur', onBlur);
                document.removeEventListener('visibilitychange', onVisibility);
                overlay.remove();
                game = null;
                var back = options && options.returnFocus;
                if (back && back.focus && document.contains(back)) back.focus();
            },
            /** Read-only view of the run, for automated tests. */
            snapshot: function () {
                return {
                    state: state, phase: phase, stage: stageIndex + 1, loop: loop, score: score, lives: lives, continues: continues, bombs: bombs, weapon: weapon, level: level,
                    drones: droneCount, shield: shield, chain: chain, boss: boss.on ? boss.kind : null, bossHp: boss.on ? boss.hp : 0,
                    foes: count(foes), bullets: count(orbs), shots: count(shots), sparks: count(sparks), noBomb: noBombTime > 0, hint: hintTime > 0 ? hint : '',
                };
            },
        };
        start();
        requestAnimationFrame(function () { overlay.classList.add('is-open'); canvas.focus(); });
        return api;
    };

    window.EchoStrike = {
        open: function (options) { if (!game) game = create(options || {}); return game; },
        isOpen: function () { return !!game; },
        close: function () { if (game) game.close(); },
    };
})();
