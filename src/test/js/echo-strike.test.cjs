const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const staticRoot = path.resolve(__dirname, '../../main/resources/static');
const source = file => fs.readFileSync(path.join(staticRoot, file), 'utf8');

// A 2D context that accepts every call; the game only needs it not to throw.
function fakeContext() {
  const base = {
    measureText: text => ({ width: String(text).length * 6 }),
    createRadialGradient: () => ({ addColorStop() {} }),
    createLinearGradient: () => ({ addColorStop() {} }),
  };
  return new Proxy(base, {
    get: (target, prop) => (prop in target ? target[prop] : () => {}),
    set: (target, prop, value) => { target[prop] = value; return true; },
  });
}

class FakeElement {
  constructor(tag) {
    this.tagName = tag; this.style = {}; this.listeners = {}; this.children = []; this.attributes = {};
    this.classList = { add() {}, remove() {}, contains: () => false };
    this.width = 0; this.height = 0; this.brand = false;
  }
  setAttribute(name, value) { this.attributes[name] = value; }
  getAttribute(name) { return this.attributes[name] ?? null; }
  appendChild(child) { this.children.push(child); return child; }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
  removeEventListener(type, fn) { this.listeners[type] = (this.listeners[type] || []).filter(f => f !== fn); }
  remove() { this.removed = true; }
  focus() { this.ownerDocument.activeElement = this; }
  getContext() { return fakeContext(); }
  getBoundingClientRect() { return { left: 0, top: 0, width: 480, height: 720 }; }
  setPointerCapture() {}
  closest(selector) { return this.brand && selector === '.sidebar-brand' ? this : null; }
  get offsetWidth() { return 0; }
}

function browser() {
  let clock = 1000;
  let frames = [];
  const timers = [];
  const listeners = {};
  const storage = new Map();
  const document = {
    hidden: false, activeElement: null, listeners: {}, capturing: {},
    head: new FakeElement('head'), body: new FakeElement('body'),
    documentElement: Object.assign(new FakeElement('html'), { lang: 'zh-TW' }),
    createElement(tag) { const el = new FakeElement(tag); el.ownerDocument = document; return el; },
    addEventListener(type, fn, options) {
      (document.listeners[type] ||= []).push(fn);
      if (options === true || options?.capture) (document.capturing[type] ||= []).push(fn);
    },
    removeEventListener(type, fn) { document.listeners[type] = (document.listeners[type] || []).filter(f => f !== fn); },
    contains: () => true,
    querySelector: () => null,
  };
  const context = vm.createContext({
    document, Element: FakeElement, navigator: { language: 'en' }, console, Math, Date, Promise,
    innerWidth: 800, innerHeight: 900, devicePixelRatio: 1,
    performance: { now: () => clock },
    matchMedia: () => ({ matches: false }),
    getComputedStyle: () => ({ getPropertyValue: () => '' }),
    localStorage: { getItem: key => (storage.has(key) ? storage.get(key) : null), setItem: (key, value) => storage.set(key, String(value)) },
    requestAnimationFrame: fn => { frames.push(fn); return frames.length; },
    cancelAnimationFrame: () => { frames = []; },
    setTimeout: (fn, ms) => { timers.push({ fn, at: clock + ms }); return timers.length; },
    clearTimeout: () => {},
    addEventListener: (type, fn) => { (listeners[type] ||= []).push(fn); },
    removeEventListener: (type, fn) => { listeners[type] = (listeners[type] || []).filter(f => f !== fn); },
  });
  context.window = context;
  const run = file => vm.runInContext(source(file), context, { filename: file });
  // Advances time frame by frame (60 per second), as requestAnimationFrame would.
  const advance = (seconds, each) => {
    const count = Math.round(seconds * 60);
    for (let n = 0; n < count; n++) {
      clock += 1000 / 60;
      const due = frames; frames = [];
      due.forEach(fn => fn(clock));
      if (each && each(n) === false) return;
    }
  };
  const event = (code, extra) => ({ code, key: code === 'Escape' ? 'Escape' : code, repeat: false, preventDefault() {}, stopImmediatePropagation() {}, ...extra });
  const key = (code, type = 'keydown') => (listeners[type] || []).slice().forEach(fn => fn(event(code)));
  const press = code => { key(code, 'keydown'); key(code, 'keyup'); };
  return { context, document, listeners, storage, run, advance, press, key, timers, now: () => clock };
}

function startGame(options) {
  const b = browser();
  b.run('echo-strike.js');
  const game = b.context.EchoStrike.open(options);
  b.advance(0.2);
  return { ...b, game, snap: () => game.snapshot() };
}

test('Z starts the game and X bombs: bullets are cleared and an empty stock is reported', () => {
  const g = startGame({ seed: 7, invulnerable: true });
  assert.equal(g.snap().state, 'title');
  g.press('KeyZ');
  assert.equal(g.snap().state, 'play');
  assert.equal(g.snap().bombs, 3);

  g.advance(25);
  assert.ok(g.snap().bullets > 0, 'enemies have fired by now');
  g.press('KeyX');
  assert.equal(g.snap().bombs, 2);
  assert.equal(g.snap().bullets, 0, 'a bomb clears every enemy bullet');

  g.press('KeyX'); g.press('KeyX');
  assert.equal(g.snap().bombs, 0);
  assert.equal(g.snap().noBomb, false);
  g.press('KeyX');
  assert.equal(g.snap().bombs, 0);
  assert.equal(g.snap().noBomb, true, 'pressing with no bombs left shows feedback');
});

test('the on-screen BOMB button works with a mouse; other mouse clicks do nothing', () => {
  const g = startGame({ seed: 3, invulnerable: true });
  const canvas = g.document.body.children[0].children[0];
  const pointer = (x, y) => canvas.listeners.pointerdown.forEach(fn => fn({ clientX: x, clientY: y, pointerType: 'mouse', pointerId: 1 }));

  pointer(240, 360);
  assert.equal(g.snap().state, 'play', 'a click starts the game');
  pointer(240, 360);
  assert.equal(g.snap().bombs, 3, 'clicking the playfield is not a bomb');
  pointer(400, 690);
  assert.equal(g.snap().bombs, 2, 'clicking the BOMB button is');
});

test('first-time players are told which key is the bomb', () => {
  const g = startGame({ seed: 5, invulnerable: true });
  g.press('KeyZ');
  g.advance(3);
  assert.match(g.snap().hint, /X/);
  // pausing does not use the hint up
  g.press('KeyP');
  g.advance(10);
  g.press('KeyP');
  assert.match(g.snap().hint, /X/);
  g.press('KeyX');
  assert.equal(g.snap().hint, '', 'the hint goes away once the bomb has been used');
  assert.equal(g.storage.get('echo.strike.bombed'), '1');
});

test('players who have bombed before are not reminded', () => {
  const b = browser();
  b.storage.set('echo.strike.bombed', '1');
  b.run('echo-strike.js');
  const game = b.context.EchoStrike.open({ seed: 5, invulnerable: true });
  b.advance(0.2);
  b.press('KeyZ');
  b.advance(3);
  assert.equal(game.snapshot().hint, '');
});

test('an unbreakable ship plays through all three stages, every boss, and into the next loop', () => {
  const g = startGame({ seed: 11, invulnerable: true });
  g.press('KeyZ');
  const stages = new Set(), bosses = new Set(), phases = new Set();
  let peak = { bullets: 0, foes: 0, shots: 0, sparks: 0 };
  g.advance(15 * 60, () => {
    const s = g.snap();
    stages.add(s.stage); phases.add(s.phase); if (s.boss) bosses.add(s.boss);
    if (s.phase === 'results') assert.equal(s.foes, 0, 'nothing hostile is left during the stage results');
    for (const k of Object.keys(peak)) peak[k] = Math.max(peak[k], s[k]);
    if (s.loop >= 1 && s.stage === 1 && s.phase === 'waves') return false;
  });
  const end = g.snap();
  assert.deepEqual([...stages].sort(), [1, 2, 3]);
  assert.deepEqual([...bosses].sort(), ['b404', 'b429', 'b500']);
  for (const p of ['intro', 'waves', 'warning', 'boss', 'down', 'results', 'loop']) assert.ok(phases.has(p), 'reached phase ' + p);
  assert.equal(end.loop, 1);
  assert.ok(end.score > 100000, 'score ' + end.score);
  // pools are fixed size: nothing grows without bound
  assert.ok(peak.bullets <= 520 && peak.foes <= 80 && peak.shots <= 200 && peak.sparks <= 420, JSON.stringify(peak));
});

test('losing every life offers an unlimited continue that keeps the stage and starts the score again', () => {
  const g = startGame({ seed: 2 });
  g.press('KeyZ');
  const runUntilContinue = () => g.advance(20 * 60, () => (g.snap().state === 'continue' ? false : undefined));

  runUntilContinue();
  const lost = g.snap();
  assert.equal(lost.state, 'continue');
  assert.ok(lost.score > 0);
  assert.equal(Number(g.storage.get('echo.strike.hiscore')), lost.score, 'the score so far is kept as the high score');

  g.advance(3);
  assert.equal(g.snap().state, 'continue', 'the world waits while the countdown runs');
  g.press('KeyZ');
  const resumed = g.snap();
  assert.equal(resumed.state, 'play');
  assert.equal(resumed.continues, 1);
  assert.equal(resumed.lives, 3);
  assert.ok(resumed.bombs >= 3);
  assert.equal(resumed.score, 0);
  assert.equal(resumed.stage, lost.stage, 'the run continues where it stopped');
  assert.equal(resumed.phase, lost.phase);

  // there is no limit on continues
  for (let n = 2; n <= 4; n++) {
    runUntilContinue();
    assert.equal(g.snap().state, 'continue');
    g.press('KeyZ');
    assert.equal(g.snap().continues, n);
  }

  // letting the countdown run out ends the run; Z then starts a new one from stage 1
  runUntilContinue();
  g.advance(10.5);
  assert.equal(g.snap().state, 'over');
  g.advance(1);
  g.press('KeyZ');
  const fresh = g.snap();
  assert.equal(fresh.state, 'play');
  assert.equal(fresh.stage, 1);
  assert.equal(fresh.continues, 0);
  assert.equal(fresh.score, 0);
  assert.equal(fresh.lives, 3);
});

test('a boss takes its minions with it: nothing hostile is left during the stage results', () => {
  // seeds where a boss used to fall with minions still on screen
  for (const seed of [1, 5, 13]) {
    const g = startGame({ seed, invulnerable: true });
    g.press('KeyZ');
    let results = 0;
    g.advance(10 * 60, () => {
      const s = g.snap();
      if (s.phase === 'results') { results++; assert.equal(s.foes, 0, 'seed ' + seed); }
      if (s.loop >= 1) return false;
    });
    assert.ok(results > 0, 'seed ' + seed + ' reached a stage result');
  }
});

test('a tap or click also takes the continue', () => {
  const g = startGame({ seed: 2 });
  const canvas = g.document.body.children[0].children[0];
  g.press('KeyZ');
  g.advance(20 * 60, () => (g.snap().state === 'continue' ? false : undefined));
  canvas.listeners.pointerdown.forEach(fn => fn({ clientX: 240, clientY: 300, pointerType: 'touch', pointerId: 4 }));
  assert.equal(g.snap().state, 'play');
  assert.equal(g.snap().continues, 1);
});

test('P pauses, Esc closes, the keyboard goes back to the page and focus returns', () => {
  const h = browser();
  h.run('echo-strike.js');
  const opener = h.document.createElement('button');
  const game = h.context.EchoStrike.open({ seed: 1, returnFocus: opener });
  h.advance(0.1);
  assert.equal(h.listeners.keydown.length, 1);
  h.press('KeyZ'); h.press('KeyP');
  assert.equal(game.snapshot().state, 'pause');
  h.press('KeyP');
  assert.equal(game.snapshot().state, 'play');
  h.press('Escape');
  assert.equal(h.listeners.keydown.length, 0, 'the game no longer owns the keyboard');
  assert.equal(h.context.EchoStrike.isOpen(), false);
  assert.equal(h.document.activeElement, opener);
});

test('clicking the Echo logo five times quickly opens the game, only when the server allows it', () => {
  const b = browser();
  b.run('easter-egg.js');
  const logo = b.document.createElement('div'); logo.brand = true;
  const other = b.document.createElement('div');
  const click = target => b.document.listeners.click.forEach(fn => fn({ target }));
  const loads = () => b.document.head.children.filter(el => String(el.src).includes('/echo-strike.js?v=')).length;

  // The sidebar stops click propagation, so only a capturing listener hears the logo.
  assert.equal(b.document.capturing.click.length, 1);
  for (let n = 0; n < 5; n++) click(logo);
  assert.equal(loads(), 0, 'off until the server status enables it');

  b.context.EchoEasterEgg.setEnabled(true);
  assert.equal(b.context.EchoEasterEgg.isEnabled(), true);
  for (let n = 0; n < 4; n++) { click(logo); b.advance(0.7); }
  assert.equal(loads(), 0, 'slow clicks do not count');
  for (let n = 0; n < 4; n++) click(logo);
  click(other);
  assert.equal(loads(), 0, 'clicks elsewhere do not count');
  for (let n = 0; n < 5; n++) click(logo);
  assert.equal(loads(), 1, 'five quick clicks load the game');

  b.context.EchoEasterEgg.setEnabled(false);
  assert.equal(b.context.EchoEasterEgg.isEnabled(), false);
});

test('the logo wink is scheduled only while the easter egg is enabled', () => {
  const b = browser();
  b.run('easter-egg.js');
  assert.equal(b.timers.length, 0);
  b.context.EchoEasterEgg.setEnabled(true);
  assert.equal(b.timers.length, 1);
  const first = b.timers[0];
  assert.ok(first.at - b.now() >= 20000 && first.at - b.now() <= 45000, 'first wink 20–45 s after enabling');
  b.context.EchoEasterEgg.setEnabled(true);
  assert.equal(b.timers.length, 1, 're-applying the same status does not add another timer');
});
