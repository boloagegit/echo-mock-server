/**
 * echo-strike.js - Echo Strike，隱藏彩蛋的縱向射擊小遊戲（由 easter-egg.js 在觸發後載入）
 *
 * 原創內容：機體、敵人、名稱都是自己畫的。玩家機用部署主色（SIT 青綠／UAT 藍），
 * 敵人是 HTTP 方法標籤造型（顏色沿用列表的方法色），道具是「200 OK」，大魔王是「500」。
 *
 * 效能：
 * - 遊戲邏輯固定每秒 60 次更新，畫面跟著 requestAnimationFrame 繪製；切到別的分頁或關閉就停止。
 * - 子彈、敵人、粒子都用物件池，遊戲中不產生新物件，不會因記憶體回收卡頓。
 * - 圖案在開始時畫進離屏 canvas 一次，之後只貼圖；不使用 shadowBlur 這類昂貴效果。
 * - 依 devicePixelRatio 設定畫布解析度，高解析度螢幕不糊。
 * 系統設定「減少動態效果」時不閃光、不震動。
 */
(function () {
    'use strict';

    var W = 480, H = 720, STEP = 1 / 60, MAX_STEPS = 5;
    var HI_KEY = 'echo.strike.hiscore';

    var TEXT = {
        en: {
            title: 'ECHO STRIKE', start: 'Press Z or Space to start', move: 'Arrows / WASD  move', fire: 'Z / Space  fire',
            bomb: 'X  bomb', pause: 'P  pause', exit: 'Esc  exit', paused: 'PAUSED', resume: 'P to resume',
            over: 'CONNECTION LOST', retry: 'Z to retry · Esc to exit', clear: 'INCIDENT RESOLVED',
            next: 'Next deployment incoming…', boss: 'WARNING · 500 INTERNAL SERVER ERROR', hi: 'HI', touch: 'Drag to move · double-tap to bomb', tapStart: 'Tap to start', tapRetry: 'Tap to retry',
        },
        'zh-TW': {
            title: 'ECHO STRIKE', start: '按 Z 或空白鍵開始', move: '方向鍵 / WASD  移動', fire: 'Z / 空白鍵  射擊',
            bomb: 'X  炸彈', pause: 'P  暫停', exit: 'Esc  離開', paused: '暫停', resume: '按 P 繼續',
            over: '連線中斷', retry: '按 Z 重來 · Esc 離開', clear: '事件已排除',
            next: '下一波部署即將到來…', boss: '警告 · 500 INTERNAL SERVER ERROR', hi: '最高', touch: '拖曳移動 · 點兩下放炸彈', tapStart: '點一下開始', tapRetry: '點一下重來',
        },
    };

    var ENEMY_TYPES = {
        GET: { label: 'GET', color: 'get', hp: 1, score: 100, r: 16, speed: 120 },
        POST: { label: 'POST', color: 'post', hp: 3, score: 200, r: 18, speed: 90 },
        PUT: { label: 'PUT', color: 'put', hp: 6, score: 400, r: 19, speed: 70 },
        DELETE: { label: 'DEL', color: 'delete', hp: 2, score: 300, r: 16, speed: 200 },
        PATCH: { label: 'PATCH', color: 'patch', hp: 14, score: 800, r: 24, speed: 40 },
    };
    var WEAPONS = ['spread', 'laser', 'homing'];

    // ---------- helpers ----------
    var clamp = function (v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; };
    var rand = function (a, b) { return a + Math.random() * (b - a); };
    var pick = function (list) { return list[(Math.random() * list.length) | 0]; };
    var lang = function () {
        var saved = null;
        try { saved = localStorage.getItem('echo_locale'); } catch (e) { /* storage unavailable */ }
        var value = saved || document.documentElement.lang || navigator.language || 'en';
        return value.indexOf('zh') === 0 ? 'zh-TW' : 'en';
    };
    var readHi = function () { try { return Number(localStorage.getItem(HI_KEY)) || 0; } catch (e) { return 0; } };
    var writeHi = function (v) { try { localStorage.setItem(HI_KEY, String(v)); } catch (e) { /* storage unavailable */ } };
    var pool = function (size, make) { var list = []; for (var i = 0; i < size; i++) { var o = make(); o.on = false; list.push(o); } return list; };
    var spawn = function (list) { for (var i = 0; i < list.length; i++) { if (!list[i].on) { list[i].on = true; return list[i]; } } return null; };

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

    var glowCircle = function (g, x, y, r, color, alpha) {
        var grad = g.createRadialGradient(x, y, 0, x, y, r);
        grad.addColorStop(0, color); grad.addColorStop(1, 'rgba(0,0,0,0)');
        g.globalAlpha = alpha; g.fillStyle = grad; g.beginPath(); g.arc(x, y, r, 0, Math.PI * 2); g.fill(); g.globalAlpha = 1;
    };

    var buildSprites = function (pal) {
        var s = {};
        s.player = sprite(44, 44, function (g) {
            g.translate(22, 22);
            glowCircle(g, 0, 4, 22, pal.primary, 0.18);
            g.fillStyle = pal.primary; g.strokeStyle = pal.ink; g.lineWidth = 1.2;
            g.beginPath();
            g.moveTo(0, -19); g.lineTo(5, -7); g.lineTo(17, 8); g.lineTo(17, 12); g.lineTo(5, 8); g.lineTo(4, 14);
            g.lineTo(-4, 14); g.lineTo(-5, 8); g.lineTo(-17, 12); g.lineTo(-17, 8); g.lineTo(-5, -7); g.closePath();
            g.fill(); g.stroke();
            g.fillStyle = pal.ink; g.globalAlpha = 0.35; g.beginPath(); g.moveTo(0, -12); g.lineTo(2.5, -4); g.lineTo(-2.5, -4); g.closePath(); g.fill(); g.globalAlpha = 1;
            g.fillStyle = '#ffffff'; g.beginPath(); g.ellipse(0, -6, 2, 4, 0, 0, Math.PI * 2); g.fill();
        });
        s.flame = sprite(12, 18, function (g) {
            var grad = g.createLinearGradient(0, 0, 0, 18);
            grad.addColorStop(0, '#ffffff'); grad.addColorStop(0.35, pal.warning); grad.addColorStop(1, 'rgba(0,0,0,0)');
            g.fillStyle = grad; g.beginPath(); g.moveTo(2, 0); g.lineTo(10, 0); g.lineTo(6, 18); g.closePath(); g.fill();
        });
        s.enemies = {};
        Object.keys(ENEMY_TYPES).forEach(function (key) {
            var t = ENEMY_TYPES[key], color = pal.method[t.color];
            var w = t.r * 2 + 18, h = t.r * 2 + 10;
            s.enemies[key] = sprite(w, h, function (g) {
                g.translate(w / 2, h / 2);
                glowCircle(g, 0, 0, t.r + 8, color, 0.22);
                // wings
                g.fillStyle = color; g.globalAlpha = 0.55;
                g.beginPath(); g.moveTo(-t.r - 8, -2); g.lineTo(-t.r + 2, -6); g.lineTo(-t.r + 2, 6); g.closePath(); g.fill();
                g.beginPath(); g.moveTo(t.r + 8, -2); g.lineTo(t.r - 2, -6); g.lineTo(t.r - 2, 6); g.closePath(); g.fill();
                g.globalAlpha = 1;
                // body: a method badge
                var bw = t.r * 2, bh = Math.max(16, t.r * 1.2);
                g.fillStyle = pal.panel; g.strokeStyle = color; g.lineWidth = 2;
                g.beginPath(); g.roundRect(-bw / 2, -bh / 2, bw, bh, 5); g.fill(); g.stroke();
                g.fillStyle = color; g.font = '700 ' + (t.label.length > 3 ? 10 : 12) + 'px ' + pal.mono;
                g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(t.label, 0, 0.5);
            });
        });
        s.boss = sprite(200, 96, function (g) {
            g.translate(100, 48);
            glowCircle(g, 0, 0, 96, pal.danger, 0.18);
            g.fillStyle = pal.panel; g.strokeStyle = pal.danger; g.lineWidth = 3;
            g.beginPath(); g.roundRect(-84, -34, 168, 64, 10); g.fill(); g.stroke();
            g.fillStyle = pal.danger;
            [-70, 70].forEach(function (x) { g.beginPath(); g.roundRect(x - 12, 22, 24, 18, 4); g.fill(); });
            g.beginPath(); g.roundRect(-10, 28, 20, 16, 4); g.fill();
            g.font = '800 30px ' + pal.mono; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText('500', 0, -10);
            g.font = '700 9px ' + pal.mono; g.globalAlpha = 0.85; g.fillText('INTERNAL SERVER ERROR', 0, 14); g.globalAlpha = 1;
        });
        s.shot = sprite(8, 18, function (g) {
            glowCircle(g, 4, 9, 9, pal.primary, 0.5);
            g.fillStyle = '#ffffff'; g.beginPath(); g.roundRect(2.5, 1, 3, 16, 1.5); g.fill();
        });
        s.spread = sprite(10, 10, function (g) {
            glowCircle(g, 5, 5, 5, pal.warning, 0.7);
            g.fillStyle = '#ffffff'; g.beginPath(); g.arc(5, 5, 2, 0, Math.PI * 2); g.fill();
        });
        s.laser = sprite(10, 32, function (g) {
            var grad = g.createLinearGradient(0, 0, 10, 0);
            grad.addColorStop(0, 'rgba(0,0,0,0)'); grad.addColorStop(0.5, pal.method.post); grad.addColorStop(1, 'rgba(0,0,0,0)');
            g.fillStyle = grad; g.fillRect(0, 0, 10, 32);
            g.fillStyle = '#ffffff'; g.fillRect(4, 0, 2, 32);
        });
        s.homing = sprite(12, 12, function (g) {
            glowCircle(g, 6, 6, 6, pal.success, 0.7);
            g.fillStyle = '#ffffff'; g.beginPath(); g.moveTo(6, 1); g.lineTo(9, 9); g.lineTo(3, 9); g.closePath(); g.fill();
        });
        s.orb = sprite(14, 14, function (g) {
            glowCircle(g, 7, 7, 7, pal.danger, 0.85);
            g.fillStyle = '#ffffff'; g.beginPath(); g.arc(7, 7, 2.6, 0, Math.PI * 2); g.fill();
        });
        s.capsules = {};
        WEAPONS.concat('bomb').forEach(function (kind) {
            var color = kind === 'spread' ? pal.warning : kind === 'laser' ? pal.method.post : kind === 'homing' ? pal.success : pal.danger;
            s.capsules[kind] = sprite(40, 22, function (g) {
                glowCircle(g, 20, 11, 20, color, 0.25);
                g.fillStyle = pal.panel; g.strokeStyle = color; g.lineWidth = 2;
                g.beginPath(); g.roundRect(3, 3, 34, 16, 8); g.fill(); g.stroke();
                g.fillStyle = color; g.font = '700 9px ' + pal.mono; g.textAlign = 'center'; g.textBaseline = 'middle';
                g.fillText(kind === 'bomb' ? 'BOMB' : '200', 20, 11.5);
            });
        });
        return s;
    };

    var palette = function () {
        var css = getComputedStyle(document.documentElement);
        var v = function (name, fallback) { return (css.getPropertyValue(name) || '').trim() || fallback; };
        return {
            primary: v('--primary', '#46d3be'), success: v('--success', '#5fcb8c'), warning: v('--warning', '#f0b95a'), danger: v('--danger', '#ff8a80'),
            method: { get: v('--method-get', '#6fd39a'), post: v('--method-post', '#7fb4ff'), put: v('--method-put', '#f0b95a'), patch: v('--method-patch', '#c9aeff'), delete: v('--method-delete', '#ff8a80') },
            mono: v('--font-mono', 'monospace'), ink: '#0b0e12', panel: '#12161c', bg: '#07090c', text: '#e8edf3', muted: '#8a94a3',
        };
    };

    // ---------- game ----------
    var game = null;

    var create = function (options) {
        var t = TEXT[lang()];
        var pal = palette();
        var sp = buildSprites(pal);
        var reduced = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
        // Phones and tablets get touch instructions instead of the keyboard ones.
        var touchOnly = !!(window.matchMedia && window.matchMedia('(hover: none) and (pointer: coarse)').matches);

        var overlay = document.createElement('div');
        overlay.className = 'echo-strike';
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true');
        overlay.setAttribute('aria-label', 'Echo Strike');
        var canvas = document.createElement('canvas');
        canvas.className = 'echo-strike__canvas';
        canvas.tabIndex = 0;
        canvas.setAttribute('aria-label', t.title + ' — ' + t.start);
        var close = document.createElement('button');
        close.type = 'button'; close.className = 'echo-strike__close'; close.setAttribute('aria-label', t.exit); close.title = t.exit;
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

        // state
        var input = { left: false, right: false, up: false, down: false, fire: false, bomb: false };
        var touch = { id: null, x: 0, y: 0, lastTap: 0 };
        var stars = []; for (var i = 0; i < 90; i++) stars.push({ x: rand(0, W), y: rand(0, H), z: pick([0.35, 0.6, 1]) });
        var shots = pool(160, function () { return { x: 0, y: 0, vx: 0, vy: 0, kind: 'shot', dmg: 1, r: 4 }; });
        var orbs = pool(420, function () { return { x: 0, y: 0, vx: 0, vy: 0, r: 4 }; });
        var foes = pool(60, function () { return { x: 0, y: 0, vx: 0, vy: 0, t: 0, hp: 1, type: 'GET', fire: 0, phase: 0, flash: 0 }; });
        var sparks = pool(320, function () { return { x: 0, y: 0, vx: 0, vy: 0, life: 0, max: 1, color: '#fff', size: 2 }; });
        var caps = pool(6, function () { return { x: 0, y: 0, vy: 0, t: 0, kind: 'spread' }; });
        var boss = { on: false, x: W / 2, y: -60, hp: 0, max: 0, t: 0, phase: 0, fire: 0, flash: 0 };
        var player, state, score, hi = readHi(), lives, bombs, weapon, level, elapsed, spawnClock, loop, shake, flashAlpha, banner, bannerTime, fireClock, bombFx, gameoverTime;

        var reset = function () {
            player = { x: W / 2, y: H - 90, inv: 2, alive: true, respawn: 0 };
            score = 0; lives = 3; bombs = 3; weapon = 'spread'; level = 1; elapsed = 0; spawnClock = 1.5; loop = 0;
            shake = 0; flashAlpha = 0; banner = ''; bannerTime = 0; fireClock = 0; bombFx = 0;
            [shots, orbs, foes, sparks, caps].forEach(function (list) { list.forEach(function (o) { o.on = false; }); });
            boss.on = false;
        };
        reset();
        state = 'title';

        var difficulty = function () { return 1 + loop * 0.35 + Math.min(elapsed / 90, 1) * 0.4; };
        var say = function (text, seconds) { banner = text; bannerTime = seconds; };

        var burst = function (x, y, color, count, speed) {
            for (var n = 0; n < count; n++) {
                var p = spawn(sparks); if (!p) return;
                var a = Math.random() * Math.PI * 2, s = rand(speed * 0.3, speed);
                p.x = x; p.y = y; p.vx = Math.cos(a) * s; p.vy = Math.sin(a) * s; p.life = p.max = rand(0.35, 0.8); p.color = color; p.size = rand(1.5, 3.5);
            }
        };
        var bump = function (amount) { if (!reduced) shake = Math.max(shake, amount); };

        var enemyShot = function (x, y, angle, speed) {
            var o = spawn(orbs); if (!o) return;
            o.x = x; o.y = y; o.vx = Math.cos(angle) * speed; o.vy = Math.sin(angle) * speed;
        };
        var aimAt = function (x, y) { return Math.atan2(player.y - y, player.x - x); };

        var addFoe = function (type, x, y, vx, vy) {
            var f = spawn(foes); if (!f) return null;
            var def = ENEMY_TYPES[type], d = difficulty();
            f.type = type; f.x = x; f.y = y; f.vx = vx || 0; f.vy = vy == null ? def.speed : vy; f.t = 0; f.phase = rand(0, Math.PI * 2);
            f.hp = Math.ceil(def.hp * (0.8 + d * 0.3)); f.fire = rand(0.6, 1.6) / d; f.flash = 0;
            return f;
        };

        var spawnWave = function () {
            var d = difficulty(), roll = Math.random(), n, k;
            if (elapsed < 12 || roll < 0.32) {
                n = 4 + ((d * 2) | 0); var fromLeft = Math.random() < 0.5;
                for (k = 0; k < n; k++) addFoe('GET', fromLeft ? 60 + k * 40 : W - 60 - k * 40, -30 - k * 34);
            } else if (roll < 0.55) {
                n = 3 + (d | 0);
                for (k = 0; k < n; k++) addFoe('POST', W * (k + 1) / (n + 1), -40 - (k % 2) * 40);
            } else if (roll < 0.72) {
                for (k = 0; k < 2; k++) { var p = addFoe('PUT', k ? W * 0.72 : W * 0.28, -40); if (p) p.vy = 110; }
            } else if (roll < 0.88) {
                n = 3 + (d | 0);
                for (k = 0; k < n; k++) addFoe('DELETE', rand(40, W - 40), -40 - k * 60, 0, 60);
            } else {
                addFoe('PATCH', rand(120, W - 120), -50);
            }
            spawnClock = rand(1.4, 2.4) / d;
        };

        var startBoss = function () {
            boss.on = true; boss.x = W / 2; boss.y = -70; boss.t = 0; boss.phase = 0; boss.fire = 1.5; boss.flash = 0;
            boss.max = boss.hp = Math.round(320 * (1 + loop * 0.6));
            say(t.boss, 2.6);
        };

        var dropCapsule = function (x, y, kind) {
            var c = spawn(caps); if (!c) return;
            c.x = x; c.y = y; c.vy = 70; c.t = 0; c.kind = kind || 'power';
        };

        var killFoe = function (f) {
            var def = ENEMY_TYPES[f.type];
            score += def.score * (1 + loop);
            burst(f.x, f.y, pal.method[def.color], 14, 220);
            bump(f.type === 'PATCH' ? 6 : 2);
            if (f.type === 'PATCH' || Math.random() < (f.type === 'PUT' ? 0.35 : 0.06)) dropCapsule(f.x, f.y, Math.random() < 0.18 ? 'bomb' : 'power');
            f.on = false;
        };

        var hitPlayer = function () {
            if (player.inv > 0 || !player.alive) return;
            burst(player.x, player.y, pal.primary, 40, 300);
            bump(10); flashAlpha = reduced ? 0 : 0.35;
            lives--; player.alive = false; player.respawn = 1.2;
            level = Math.max(1, level - 1);
            if (lives < 0) { state = 'over'; gameoverTime = 0; if (score > hi) { hi = score; writeHi(hi); } }
        };

        var useBomb = function () {
            if (bombs <= 0 || !player.alive || state !== 'play') return;
            bombs--; bombFx = 1; flashAlpha = reduced ? 0 : 0.5; bump(12);
            orbs.forEach(function (o) { if (o.on) { o.on = false; burst(o.x, o.y, pal.danger, 1, 60); } });
            foes.forEach(function (f) { if (f.on && f.y > -20) { f.hp -= 12; if (f.hp <= 0) killFoe(f); } });
            if (boss.on && boss.y > 0) { boss.hp -= 40; boss.flash = 0.2; }
            player.inv = Math.max(player.inv, 1.2);
        };

        var fireShots = function () {
            var x = player.x, y = player.y - 16, s;
            if (weapon === 'spread') {
                var spreads = [[-0.08, 0.08], [-0.22, -0.08, 0.08, 0.22], [-0.34, -0.2, -0.07, 0.07, 0.2, 0.34], [-0.46, -0.32, -0.19, -0.07, 0.07, 0.19, 0.32, 0.46]][level - 1];
                s = spawn(shots); if (s) { s.kind = 'shot'; s.x = x; s.y = y; s.vx = 0; s.vy = -720; s.dmg = 1.2; s.r = 5; }
                spreads.forEach(function (a) { var o = spawn(shots); if (!o) return; o.kind = 'spread'; o.x = x; o.y = y; o.vx = Math.sin(a) * 600; o.vy = -Math.cos(a) * 600; o.dmg = 0.8; o.r = 5; });
                fireClock = 0.1;
            } else if (weapon === 'laser') {
                var offsets = [[0], [-6, 6], [-10, 0, 10], [-14, -5, 5, 14]][level - 1];
                offsets.forEach(function (dx) { var o = spawn(shots); if (!o) return; o.kind = 'laser'; o.x = x + dx; o.y = y - 8; o.vx = 0; o.vy = -980; o.dmg = 1.1 + level * 0.15; o.r = 5; });
                fireClock = 0.07;
            } else {
                s = spawn(shots); if (s) { s.kind = 'shot'; s.x = x; s.y = y; s.vx = 0; s.vy = -720; s.dmg = 1; s.r = 5; }
                for (var k = 0; k < level; k++) {
                    var o = spawn(shots); if (!o) break;
                    var side = k % 2 ? 1 : -1;
                    o.kind = 'homing'; o.x = x + side * 12; o.y = y + 8; o.vx = side * 160; o.vy = -260; o.dmg = 1.4; o.r = 6;
                }
                fireClock = 0.14;
            }
        };

        var nearestTarget = function (x, y) {
            var best = null, dist = 1e9;
            foes.forEach(function (f) { if (!f.on || f.y < 0) return; var d = (f.x - x) * (f.x - x) + (f.y - y) * (f.y - y); if (d < dist) { dist = d; best = f; } });
            if (boss.on && boss.y > 0) { var bd = (boss.x - x) * (boss.x - x) + (boss.y - y) * (boss.y - y); if (bd < dist) best = boss; }
            return best;
        };

        // ---------- update ----------
        var update = function (dt) {
            var i, o, f;
            for (i = 0; i < stars.length; i++) { var st = stars[i]; st.y += (40 + 160 * st.z) * dt; if (st.y > H) { st.y = -2; st.x = rand(0, W); } }
            for (i = 0; i < sparks.length; i++) { o = sparks[i]; if (!o.on) continue; o.life -= dt; if (o.life <= 0) { o.on = false; continue; } o.x += o.vx * dt; o.y += o.vy * dt; o.vx *= 0.96; o.vy *= 0.96; }
            if (bannerTime > 0) bannerTime -= dt;
            if (shake > 0) shake = Math.max(0, shake - 30 * dt);
            if (flashAlpha > 0) flashAlpha = Math.max(0, flashAlpha - dt * 1.6);
            if (bombFx > 0) bombFx = Math.max(0, bombFx - dt * 1.4);
            if (state === 'over') { gameoverTime += dt; return; }
            if (state !== 'play' && state !== 'clear') return;

            elapsed += dt;
            // player
            if (player.alive) {
                var speed = 280, mx = (input.right ? 1 : 0) - (input.left ? 1 : 0), my = (input.down ? 1 : 0) - (input.up ? 1 : 0);
                if (mx && my) { mx *= 0.7071; my *= 0.7071; }
                player.x = clamp(player.x + mx * speed * dt, 18, W - 18);
                player.y = clamp(player.y + my * speed * dt, 40, H - 24);
                if (player.inv > 0) player.inv -= dt;
                fireClock -= dt;
                if (fireClock <= 0 && state === 'play') fireShots();
            } else {
                player.respawn -= dt;
                if (player.respawn <= 0 && lives >= 0) { player.alive = true; player.x = W / 2; player.y = H - 90; player.inv = 2.2; }
            }

            // player shots
            for (i = 0; i < shots.length; i++) {
                o = shots[i]; if (!o.on) continue;
                if (o.kind === 'homing') {
                    var target = nearestTarget(o.x, o.y);
                    if (target) {
                        var want = Math.atan2(target.y - o.y, target.x - o.x), cur = Math.atan2(o.vy, o.vx), sp2 = Math.min(Math.hypot(o.vx, o.vy) + 900 * dt, 620);
                        var diff = Math.atan2(Math.sin(want - cur), Math.cos(want - cur));
                        cur += clamp(diff, -6 * dt, 6 * dt);
                        o.vx = Math.cos(cur) * sp2; o.vy = Math.sin(cur) * sp2;
                    }
                }
                o.x += o.vx * dt; o.y += o.vy * dt;
                if (o.y < -30 || o.x < -30 || o.x > W + 30 || o.y > H + 30) o.on = false;
            }

            // spawning
            if (!boss.on && state === 'play') {
                if (elapsed > 72) { if (!foes.some(function (q) { return q.on; })) startBoss(); }
                else { spawnClock -= dt; if (spawnClock <= 0) spawnWave(); }
            }

            // enemies
            var d = difficulty();
            for (i = 0; i < foes.length; i++) {
                f = foes[i]; if (!f.on) continue;
                f.t += dt; if (f.flash > 0) f.flash -= dt;
                if (f.type === 'POST') f.x += Math.sin(f.t * 2.4 + f.phase) * 120 * dt;
                if (f.type === 'PUT' && f.y > 150 && f.t < 6) f.vy = Math.max(0, f.vy - 160 * dt);
                if (f.type === 'PUT' && f.t > 6) f.vy = Math.min(f.vy + 120 * dt, 120);
                if (f.type === 'DELETE' && f.y > 60 && f.t < 4 && player.alive) {
                    var a = aimAt(f.x, f.y); f.vx += Math.cos(a) * 420 * dt; f.vy += Math.sin(a) * 420 * dt;
                    var len = Math.hypot(f.vx, f.vy), cap = 230 * d; if (len > cap) { f.vx *= cap / len; f.vy *= cap / len; }
                }
                if (f.type === 'PATCH') f.x += Math.sin(f.t * 0.8 + f.phase) * 40 * dt;
                f.x += f.vx * dt; f.y += f.vy * dt;
                if (f.y > H + 60 || f.x < -80 || f.x > W + 80) { f.on = false; continue; }
                if (f.y > 0 && f.y < H * 0.7 && player.alive) {
                    f.fire -= dt;
                    if (f.fire <= 0) {
                        var bs = 150 + 40 * d, k;
                        if (f.type === 'GET') { enemyShot(f.x, f.y, aimAt(f.x, f.y), bs); f.fire = rand(1.6, 2.8) / d; }
                        else if (f.type === 'POST') { var base = aimAt(f.x, f.y); for (k = -1; k <= 1; k++) enemyShot(f.x, f.y, base + k * 0.22, bs); f.fire = rand(1.6, 2.4) / d; }
                        else if (f.type === 'PUT') { for (k = 0; k < 10; k++) enemyShot(f.x, f.y, k / 10 * Math.PI * 2 + f.t, bs * 0.8); f.fire = 1.4 / d; }
                        else if (f.type === 'PATCH') { for (k = 0; k < 3; k++) enemyShot(f.x, f.y, f.t * 2.2 + k * 2.094, bs * 0.85); f.fire = 0.12; }
                        else { f.fire = 9; }
                    }
                }
            }

            // boss
            if (boss.on) {
                boss.t += dt; if (boss.flash > 0) boss.flash -= dt;
                if (boss.y < 130) boss.y += 50 * dt;
                else {
                    boss.x = W / 2 + Math.sin(boss.t * 0.6) * 130;
                    var ratio = boss.hp / boss.max;
                    boss.phase = ratio > 0.66 ? 0 : ratio > 0.33 ? 1 : 2;
                    boss.fire -= dt;
                    if (boss.fire <= 0 && player.alive) {
                        var bsp = 170 + loop * 30, n2;
                        if (boss.phase === 0) { var ba = aimAt(boss.x, boss.y + 30); for (n2 = -2; n2 <= 2; n2++) enemyShot(boss.x, boss.y + 30, ba + n2 * 0.16, bsp); boss.fire = 0.75; }
                        else if (boss.phase === 1) { for (n2 = 0; n2 < 4; n2++) enemyShot(boss.x, boss.y + 20, boss.t * 3 + n2 * Math.PI / 2, bsp * 0.9); enemyShot(boss.x - 70, boss.y + 30, aimAt(boss.x - 70, boss.y + 30), bsp); enemyShot(boss.x + 70, boss.y + 30, aimAt(boss.x + 70, boss.y + 30), bsp); boss.fire = 0.16; }
                        else { for (n2 = 0; n2 < 18; n2++) enemyShot(boss.x, boss.y + 10, n2 / 18 * Math.PI * 2 + boss.t, bsp * 0.75); boss.fire = 0.55; }
                    }
                }
            }

            // enemy bullets
            for (i = 0; i < orbs.length; i++) {
                o = orbs[i]; if (!o.on) continue;
                o.x += o.vx * dt; o.y += o.vy * dt;
                if (o.y < -20 || o.y > H + 20 || o.x < -20 || o.x > W + 20) { o.on = false; continue; }
                if (player.alive && player.inv <= 0) { var dx = o.x - player.x, dy = o.y - player.y; if (dx * dx + dy * dy < 25) { o.on = false; hitPlayer(); } }
            }

            // capsules
            for (i = 0; i < caps.length; i++) {
                var c = caps[i]; if (!c.on) continue;
                c.t += dt; c.y += c.vy * dt; c.x += Math.sin(c.t * 2) * 30 * dt;
                if (c.y > H + 20) { c.on = false; continue; }
                if (player.alive && Math.abs(c.x - player.x) < 26 && Math.abs(c.y - player.y) < 22) {
                    c.on = false; score += 500;
                    if (c.kind === 'bomb') bombs = Math.min(bombs + 1, 5);
                    else { var kind = WEAPONS[((c.t * 0.8) | 0) % WEAPONS.length]; if (kind === weapon) level = Math.min(level + 1, 4); else { weapon = kind; level = Math.max(level, 2); } }
                    burst(c.x, c.y, pal.success, 12, 160);
                }
            }

            // collisions: shots vs foes / boss
            for (i = 0; i < shots.length; i++) {
                o = shots[i]; if (!o.on) continue;
                for (var j = 0; j < foes.length; j++) {
                    f = foes[j]; if (!f.on || f.y < -10) continue;
                    var rr = ENEMY_TYPES[f.type].r + o.r, ex = f.x - o.x, ey = f.y - o.y;
                    if (ex * ex + ey * ey < rr * rr) {
                        f.hp -= o.dmg; f.flash = 0.06; if (o.kind !== 'laser' || f.hp <= 0) o.on = false;
                        if (f.hp <= 0) killFoe(f);
                        if (!o.on) break;
                    }
                }
                if (o.on && boss.on && boss.y > 40 && Math.abs(o.x - boss.x) < 84 && Math.abs(o.y - boss.y) < 34) {
                    o.on = false; boss.hp -= o.dmg; boss.flash = 0.05; score += 10;
                }
            }
            if (boss.on && boss.hp <= 0) {
                boss.on = false; score += 20000 * (1 + loop);
                for (var b = 0; b < 6; b++) burst(boss.x + rand(-70, 70), boss.y + rand(-25, 25), b % 2 ? pal.danger : pal.warning, 24, 320);
                bump(16); flashAlpha = reduced ? 0 : 0.6;
                orbs.forEach(function (q) { q.on = false; });
                state = 'clear'; say(t.clear, 3.2); elapsed = 0;
                setTimeout(function () { if (game && state === 'clear') { loop++; elapsed = 0; spawnClock = 2; state = 'play'; say(t.next, 2); } }, 3400);
            }

            // ramming
            if (player.alive && player.inv <= 0) {
                for (i = 0; i < foes.length; i++) { f = foes[i]; if (!f.on) continue; var fr = ENEMY_TYPES[f.type].r; if (Math.abs(f.x - player.x) < fr + 6 && Math.abs(f.y - player.y) < fr + 6) { killFoe(f); hitPlayer(); break; } }
            }
            if (score > hi) hi = score;
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

        var render = function (now) {
            var i, o;
            ctx.save();
            if (shake > 0) ctx.translate(rand(-shake, shake), rand(-shake, shake));
            ctx.fillStyle = pal.bg; ctx.fillRect(-20, -20, W + 40, H + 40);
            // stars
            for (i = 0; i < stars.length; i++) { var st = stars[i]; ctx.globalAlpha = 0.25 + st.z * 0.6; ctx.fillStyle = st.z === 1 ? '#ffffff' : pal.muted; ctx.fillRect(st.x, st.y, st.z * 2, st.z * (2 + st.z * 3)); }
            ctx.globalAlpha = 1;
            // capsules (cycle colour = the weapon you would get)
            for (i = 0; i < caps.length; i++) { var c = caps[i]; if (!c.on) continue; drawSprite(c.kind === 'bomb' ? sp.capsules.bomb : sp.capsules[WEAPONS[((c.t * 0.8) | 0) % WEAPONS.length]], c.x, c.y); }
            // enemies
            for (i = 0; i < foes.length; i++) { var f = foes[i]; if (!f.on) continue; drawSprite(sp.enemies[f.type], f.x, f.y, f.flash > 0 ? 0.55 : null); }
            if (boss.on) {
                drawSprite(sp.boss, boss.x, boss.y, boss.flash > 0 ? 0.6 : null);
                ctx.fillStyle = 'rgba(255,255,255,0.12)'; ctx.fillRect(40, 16, W - 80, 6);
                ctx.fillStyle = pal.danger; ctx.fillRect(40, 16, (W - 80) * Math.max(0, boss.hp / boss.max), 6);
            }
            // shots
            for (i = 0; i < shots.length; i++) { o = shots[i]; if (!o.on) continue; drawSprite(sp[o.kind], o.x, o.y); }
            // player
            if (player.alive && (player.inv <= 0 || ((now / 80) | 0) % 2 === 0)) {
                drawSprite(sp.flame, player.x, player.y + 22, 0.7 + Math.sin(now / 40) * 0.3);
                drawSprite(sp.player, player.x, player.y);
            }
            // enemy bullets on top so they are always readable
            for (i = 0; i < orbs.length; i++) { o = orbs[i]; if (!o.on) continue; drawSprite(sp.orb, o.x, o.y); }
            // sparks
            for (i = 0; i < sparks.length; i++) { o = sparks[i]; if (!o.on) continue; ctx.globalAlpha = o.life / o.max; ctx.fillStyle = o.color; ctx.fillRect(o.x, o.y, o.size, o.size); }
            ctx.globalAlpha = 1;
            if (bombFx > 0) { ctx.strokeStyle = pal.primary; ctx.globalAlpha = bombFx; ctx.lineWidth = 6; ctx.beginPath(); ctx.arc(player.x, player.y, (1 - bombFx) * 520, 0, Math.PI * 2); ctx.stroke(); ctx.globalAlpha = 1; }
            ctx.restore();
            if (flashAlpha > 0) { ctx.fillStyle = '#ffffff'; ctx.globalAlpha = flashAlpha; ctx.fillRect(0, 0, W, H); ctx.globalAlpha = 1; }

            // HUD
            text(String(score).padStart(8, '0'), 16, 40, 16, pal.text, 'left');
            text(t.hi + ' ' + String(hi).padStart(8, '0'), W - 16, 40, 12, pal.muted, 'right', 600);
            for (i = 0; i < Math.max(lives, 0); i++) ctx.drawImage(sp.player, 14 + i * 22, H - 34, 20, 20);
            for (i = 0; i < bombs; i++) { ctx.drawImage(sp.capsules.bomb, W - 54 - i * 36, H - 32, 32, 18); }
            text(weapon.toUpperCase() + ' Lv' + level, W / 2, H - 22, 11, pal.muted, 'center', 600);
            if (bannerTime > 0) { ctx.globalAlpha = Math.min(1, bannerTime * 2); text(banner, W / 2, H * 0.4, banner.length > 24 ? 14 : 20, pal.text); ctx.globalAlpha = 1; }

            if (state === 'title') {
                ctx.fillStyle = 'rgba(7,9,12,0.72)'; ctx.fillRect(0, 0, W, H);
                drawSprite(sp.player, W / 2, H * 0.3 + Math.sin(now / 400) * 6);
                text(t.title, W / 2, H * 0.42, 36, pal.primary, 'center', 800);
                text(touchOnly ? t.tapStart : t.start, W / 2, H * 0.5, 14, pal.text, 'center', 600);
                (touchOnly ? [t.touch] : [t.move, t.fire, t.bomb, t.pause, t.exit]).forEach(function (line, n) { text(line, W / 2, H * 0.6 + n * 22, 12, pal.muted, 'center', 500); });
            } else if (state === 'pause') {
                ctx.fillStyle = 'rgba(7,9,12,0.6)'; ctx.fillRect(0, 0, W, H);
                text(t.paused, W / 2, H * 0.45, 28, pal.text); text(t.resume, W / 2, H * 0.52, 13, pal.muted, 'center', 500);
            } else if (state === 'over') {
                ctx.fillStyle = 'rgba(7,9,12,' + Math.min(0.75, gameoverTime) + ')'; ctx.fillRect(0, 0, W, H);
                text(t.over, W / 2, H * 0.42, 28, pal.danger, 'center', 800);
                text(String(score).padStart(8, '0'), W / 2, H * 0.5, 22, pal.text);
                text(t.hi + ' ' + String(hi).padStart(8, '0'), W / 2, H * 0.55, 13, pal.muted, 'center', 600);
                if (gameoverTime > 0.8) text(touchOnly ? t.tapRetry : t.retry, W / 2, H * 0.63, 13, pal.text, 'center', 600);
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
        var KEYMAP = { ArrowLeft: 'left', KeyA: 'left', ArrowRight: 'right', KeyD: 'right', ArrowUp: 'up', KeyW: 'up', ArrowDown: 'down', KeyS: 'down', KeyZ: 'fire', Space: 'fire', KeyX: 'bomb' };
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
                if (state === 'title') { reset(); state = 'play'; return; }
                if (state === 'over' && gameoverTime > 0.8) { reset(); state = 'play'; return; }
            }
            if (action === 'bomb' && !e.repeat) { useBomb(); return; }
            input[action] = true;
        };
        var onKeyUp = function (e) {
            e.stopImmediatePropagation();
            var action = KEYMAP[e.code]; if (action) { input[action] = false; e.preventDefault(); }
        };
        var toGame = function (e) { var r = canvas.getBoundingClientRect(); return { x: (e.clientX - r.left) * W / r.width, y: (e.clientY - r.top) * H / r.height }; };
        var onPointerDown = function (e) {
            if (e.pointerType === 'mouse') return;
            var p = toGame(e), now = performance.now();
            if (state === 'title' || (state === 'over' && gameoverTime > 0.8)) { reset(); state = 'play'; return; }
            if (now - touch.lastTap < 280) useBomb();
            touch.lastTap = now; touch.id = e.pointerId; touch.x = p.x; touch.y = p.y;
            canvas.setPointerCapture(e.pointerId);
        };
        var onPointerMove = function (e) {
            if (e.pointerId !== touch.id || !player.alive || state !== 'play') return;
            var p = toGame(e);
            player.x = clamp(player.x + (p.x - touch.x) * 1.3, 18, W - 18);
            player.y = clamp(player.y + (p.y - touch.y) * 1.3, 40, H - 24);
            touch.x = p.x; touch.y = p.y;
        };
        var onPointerUp = function (e) { if (e.pointerId === touch.id) touch.id = null; };
        var onVisibility = function () {
            if (document.hidden) { stop(); if (state === 'play') state = 'pause'; Object.keys(input).forEach(function (k) { input[k] = false; }); }
            else start();
        };
        var onBlur = function () { if (state === 'play') state = 'pause'; Object.keys(input).forEach(function (k) { input[k] = false; }); };

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
