const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const read = file => fs.readFileSync(path.join(__dirname, '../', file), 'utf8');
const flushPromises = () => new Promise(setImmediate);

function element(tag = 'button', attrs = {}, parent = null) {
  const node = {
    tagName: tag.toUpperCase(), nodeType: 1, parentElement: parent, disabled: false,
    getAttribute: key => Object.hasOwn(attrs, key) ? attrs[key] : null,
    setAttribute(key, value) { attrs[key] = value; },
    matches(selector) {
      return selector.split(',').some(part => {
        const s = part.trim();
        if (s[0] === '.') return (attrs.class || '').split(' ').includes(s.slice(1));
        const match = s.match(/^([a-z]+)?(?:\[([^=\]]+)(?:=([^\]]+))?\])?$/);
        if (!match) return false;
        return (!match[1] || match[1] === tag) && (!match[2] ||
          Object.hasOwn(attrs, match[2]) && (match[3] == null || String(attrs[match[2]]) === match[3]));
      });
    },
    closest(selector) {
      let candidate = this;
      while (candidate) { if (candidate.matches(selector)) return candidate; candidate = candidate.parentElement; }
      return null;
    }
  };
  return node;
}

function setup(options = {}) {
  let now = 1000;
  let nextTimer = 0;
  const timers = new Map();
  const events = {};
  const contexts = [];
  const sources = [];
  const storage = new Map();
  if (options.saved !== undefined) storage.set('wargame_ui_sound_v1', options.saved);
  class AudioContext {
    constructor() { this.state = options.suspended ? 'suspended' : 'running'; this.sampleRate = 44100; this.currentTime = 0; contexts.push(this); }
    createGain() { return { gain: { value: 0, setTargetAtTime(value) { this.value = value; } }, connect() {} }; }
    createBuffer(channels, count, rate) {
      const data = new Float32Array(count);
      return { duration: count / rate, getChannelData: () => data };
    }
    createBufferSource() {
      const source = { connect() {}, disconnect() { this.disconnected = true; }, start() { this.started = true; }, stop() { this.stopped = true; } };
      sources.push(source);
      return source;
    }
    resume() {
      this.resumes = (this.resumes || 0) + 1;
      if (options.rejectResume) return Promise.reject(new Error('blocked'));
      if (options.delayResume) return new Promise(resolve => { this.finishResume = () => { this.state = 'running'; resolve(); }; });
      this.state = 'running';
      return Promise.resolve();
    }
    suspend() { this.state = 'suspended'; return Promise.resolve(); }
  }
  const document = {
    hidden: false,
    addEventListener(type, handler, capture) { (events[type] ||= []).push({ handler, capture }); },
    getElementById() { return null; }, querySelectorAll() { return []; }
  };
  const sandbox = {
    document, console, AudioContext: options.unsupported ? undefined : AudioContext,
    Date: class extends Date { static now() { return now; } },
    localStorage: {
      getItem(key) { if (options.badStorage) throw new Error('blocked'); return storage.get(key) || null; },
      setItem(key, value) { if (options.badStorage) throw new Error('blocked'); storage.set(key, value); }
    },
    setTimeout(fn) { timers.set(++nextTimer, fn); return nextTimer; },
    clearTimeout(id) { timers.delete(id); },
    Game: {}
  };
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(read('js/constants.js'), sandbox);
  vm.runInContext(read('js/sound.js'), sandbox);
  return {
    sandbox, document, events, contexts, sources, storage,
    sound: sandbox.Game.Sound,
    advance(ms = 100) { now += ms; },
    fire(target, extra = {}) {
      const event = { target, type: 'click', button: 0, ...extra };
      for (const listener of events[event.type] || []) listener.handler(event);
      return event;
    },
    flush() { const callbacks = [...timers.values()]; timers.clear(); callbacks.forEach(fn => fn()); },
    load(file) { vm.runInContext(read(file), sandbox); },
    duration(name) { return Math.max(...sandbox.Game.Constants.uiSound.cues[name].layers.map(l => l.at + l.duration)); }
  };
}

test('initialization stays silent and binds one capture listener despite rerenders', () => {
  const f = setup();
  f.sound.init(); f.sound.init(); f.sound.renderSettings();
  assert.equal(f.contexts.length, 0);
  assert.equal(f.events.click.length, 1);
  assert.equal(f.events.keydown.length, 1);
  assert.equal(f.events.click[0].capture, true);
  assert.equal(f.sound.get().volume, 0.4);
  assert.equal(f.sound.get().sound, 'confirm');
  assert.deepEqual(Object.keys(f.sandbox.Game.Constants.uiSound.cues), ['confirm', 'mechanicalImpact']);
});

test('nested icon click uses the selected sound once even if the button disappears', () => {
  const f = setup();
  const card = element('div', { onclick: 'open()' });
  f.fire(element('img', {}, card));
  card.parentElement = null;
  assert.equal(f.contexts.length, 1);
  assert.equal(f.sources.length, 0);
  f.flush();
  assert.equal(f.sources.length, 1);
  assert.ok(Math.abs(f.sources[0].buffer.duration - f.duration('confirm')) < 0.001);
});

test('real router keeps automatic navigation silent and user actions use selected sound', () => {
  const f = setup();
  f.load('js/core.js');
  const core = f.sandbox.Game.Core;
  core.render = () => {};
  core.go('world'); f.flush();
  assert.equal(f.contexts.length, 0);
  for (const route of ['world', 'settings', null]) {
    f.advance(); f.fire(element());
    if (route) core.go(route); else core.back();
    f.flush();
    assert.ok(Math.abs(f.sources.at(-1).buffer.duration - f.duration('confirm')) < 0.001);
  }
  assert.equal(f.sources.length, 3);
});

test('disabled controls, disabled ancestors, blanks and right clicks stay silent', () => {
  const f = setup();
  const disabled = element(); disabled.disabled = true;
  const ariaDisabled = element('div', { 'aria-disabled': 'true' });
  for (const target of [disabled, element('span', {}, disabled), element('button', {}, ariaDisabled), element('p'), element('input'), element('textarea')]) {
    f.fire(target); f.flush();
  }
  f.fire(element(), { button: 2 }); f.flush();
  assert.equal(f.sources.length, 0);
  assert.equal(f.contexts.length, 0);
});

test('settings subtree suppresses global clicks; other buttons use the selected sound', () => {
  const f = setup();
  f.fire(element('button', {}, element('section', { 'data-ui-sound': 'none' }))); f.flush();
  assert.equal(f.contexts.length, 0);
  f.fire(element('button', { 'data-ui-sound': 'mechanicalImpact' })); f.flush();
  assert.ok(Math.abs(f.sources[0].buffer.duration - f.duration('confirm')) < 0.001);
});

test('map toolbar buttons use selected sound and delayed events are discarded', () => {
  const f = setup();
  f.fire(element('button', { class: 'page-back-button', 'data-map': 'refresh' })); f.flush();
  assert.ok(Math.abs(f.sources[0].buffer.duration - f.duration('confirm')) < 0.001);
  f.advance();
  f.fire(element('button', { class: 'page-back-button', 'data-map': 'back' })); f.flush();
  assert.ok(Math.abs(f.sources[1].buffer.duration - f.duration('confirm')) < 0.001);
  f.advance(); f.fire(element()); f.advance(300); f.flush();
  assert.equal(f.sources.length, 2);
});

test('native keyboard activation waits for click, custom keyboard cards play once', () => {
  const f = setup();
  f.fire(element(), { type: 'keydown', key: 'Enter' }); f.flush();
  assert.equal(f.sources.length, 0);
  f.fire(element(), { detail: 0 }); f.flush();
  assert.equal(f.sources.length, 1);
  f.advance();
  const custom = element('div', { role: 'button', onclick: 'open()' });
  f.fire(custom, { type: 'keydown', key: 'Enter' }); f.flush();
  assert.equal(f.sources.length, 2);
  f.fire(custom, { type: 'keydown', key: 'Enter', repeat: true }); f.flush();
  f.fire(element('input'), { type: 'keydown', key: 'Backspace' }); f.flush();
  assert.equal(f.sources.length, 2);
});

test('numeric keyboard shortcut dispatches a real click through the shared sound handler', () => {
  const f = setup();
  f.load('js/core.js');
  const button = element();
  button.onclick = () => {};
  button.click = () => { f.fire(button); button.onclick(); };
  f.document.querySelectorAll = () => [button];
  f.sandbox.Game.Core.pressNumber(1); f.flush();
  assert.equal(f.sources.length, 1);
  assert.ok(Math.abs(f.sources[0].buffer.duration - f.duration('confirm')) < 0.001);
  f.advance();
  f.sandbox.Game.Core.render = () => {};
  f.fire(element('body'), { type: 'keydown', key: '0' });
  f.sandbox.Game.Core.pressNumber(0); f.flush();
  assert.equal(f.sources.length, 2);
  assert.ok(Math.abs(f.sources[1].buffer.duration - f.duration('confirm')) < 0.001);
});

test('cooldown and voice limit contain rapid clicking; ended sources are disconnected', () => {
  const f = setup();
  for (let i = 0; i < 15; i++) { f.fire(element()); f.flush(); }
  assert.equal(f.sources.length, 1);
  for (let i = 0; i < 6; i++) { f.advance(); f.fire(element()); f.flush(); }
  assert.equal(f.sources.length, 3);
  f.sources[0].onended();
  assert.equal(f.sources[0].disconnected, true);
  f.advance(); f.fire(element()); f.flush();
  assert.equal(f.sources.length, 4);
});

test('muting stops live voices and pending gestures immediately, persists and restores', () => {
  const f = setup();
  f.sound.play('confirm');
  f.fire(element());
  f.sound.setEnabled(false); f.flush();
  assert.equal(f.sources.length, 1);
  assert.equal(f.sources[0].stopped, true);
  assert.equal(f.sources[0].disconnected, true);
  const saved = f.storage.get('wargame_ui_sound_v1');
  const reload = setup({ saved });
  reload.fire(element()); reload.flush();
  assert.equal(reload.sound.get().enabled, false);
  assert.equal(reload.contexts.length, 0);
});

test('volume zero stays silent and clamped volume survives invalid input', () => {
  const f = setup();
  f.sound.setVolume(0); f.sound.play('confirm');
  assert.equal(f.contexts.length, 0);
  f.sound.setVolume(2); assert.equal(f.sound.get().volume, 1);
  f.sound.setVolume(NaN); f.sound.setVolume('bad');
  assert.equal(f.sound.get().volume, 1);
  f.sound.play('confirm');
  f.sound.setVolume(0);
  assert.equal(f.sources[0].stopped, true);
});

test('corrupt or unavailable storage and unsupported audio do not break interaction', () => {
  for (const options of [{ saved: 'oops' }, { saved: 'null' }, { badStorage: true }, { unsupported: true }]) {
    const f = setup(options);
    assert.doesNotThrow(() => { f.fire(element()); f.flush(); f.sound.setVolume(0.3); f.sound.setEnabled(false); });
  }
});

test('suspended audio unlocks in the gesture and emits at most one cue on resume', async () => {
  const f = setup({ suspended: true, delayResume: true });
  f.fire(element()); f.flush();
  assert.equal(f.contexts[0].resumes, 1);
  assert.equal(f.sources.length, 0);
  f.contexts[0].finishResume(); await flushPromises();
  assert.equal(f.sources.length, 1);
});

test('resume failures are contained and stale or muted gestures are never replayed', async () => {
  const rejected = setup({ suspended: true, rejectResume: true });
  rejected.fire(element()); rejected.flush(); await flushPromises();
  assert.equal(rejected.sources.length, 0);
  for (const action of ['timeout', 'mute', 'newer']) {
    const f = setup({ suspended: true, delayResume: true });
    f.fire(element()); f.flush();
    if (action === 'timeout') f.advance(300);
    if (action === 'mute') f.sound.setEnabled(false);
    if (action === 'newer') { f.fire(element('button')); f.flush(); }
    f.contexts[0].finishResume(); await flushPromises();
    assert.equal(f.sources.length, action === 'newer' ? 1 : 0);
    if (action === 'newer') assert.ok(Math.abs(f.sources[0].buffer.duration - f.duration('confirm')) < 0.001);
  }
});

test('backgrounding stops all sources and returns without replaying old UI actions', async () => {
  const f = setup();
  f.sound.play('confirm'); f.fire(element());
  f.document.hidden = true; f.fire(null, { type: 'visibilitychange' }); f.flush();
  assert.equal(f.sources[0].stopped, true);
  assert.equal(f.contexts[0].state, 'suspended');
  f.sound.play('confirm');
  f.document.hidden = false; f.fire(null, { type: 'visibilitychange' });
  await flushPromises();
  assert.equal(f.sources.length, 1);
  f.advance(); f.fire(element()); f.flush(); await flushPromises();
  assert.equal(f.sources.length, 2);
});

test('every sound produces finite bounded samples and releases its node', () => {
  const f = setup();
  f.sound.play('unknown');
  assert.equal(f.contexts.length, 0);
  for (const name of Object.keys(f.sandbox.Game.Constants.uiSound.cues)) {
    f.advance(); f.sound.play(name);
    const source = f.sources.at(-1);
    const samples = source.buffer.getChannelData(0);
    assert.ok(samples.length > 1000);
    assert.ok(samples.every(value => Number.isFinite(value) && Math.abs(value) <= 0.280001));
    assert.ok(samples.some(value => Math.abs(value) > 0.01));
    assert.equal(samples[0], 0);
    assert.ok(Math.abs(samples.at(-1)) < 0.001);
    source.onended();
    assert.equal(source.disconnected, true);
  }
});

test('settings handlers parse and audition their own sounds with correct disabled states', () => {
  const f = setup();
  const html = f.sound.renderSettings();
  assert.match(html, /id="uiSoundVolume"[^>]*value="40"/);
  assert.match(html, /id="uiSoundChoice"/);
  assert.match(html, /value="confirm" selected/);
  assert.equal((html.match(/data-sound-preview=/g) || []).length, 2);
  assert.match(html, /data-sound-preview="confirm"/);
  assert.match(html, /data-sound-preview="mechanicalImpact"/);
  for (const match of html.matchAll(/(?:onclick|oninput|onchange)="([^"]+)"/g)) {
    assert.doesNotThrow(() => new Function(match[1].replaceAll('&quot;', '"')));
  }
  f.sound.setEnabled(false);
  const muted = f.sound.renderSettings();
  assert.match(muted, /aria-pressed="false"/);
  assert.equal((muted.match(/data-sound-preview="[^"]+" disabled/g) || []).length, 2);
  assert.match(muted, /id="uiSoundChoice"[^>]*disabled/);
});

test('selected sound persists and changes every button waveform', () => {
  const f = setup();
  const original = f.sound.get();
  assert.equal(original.sound, 'confirm');
  f.fire(element()); f.flush();
  const confirm = f.sources.at(-1).buffer;
  f.advance();
  f.sound.setSound('mechanicalImpact');
  assert.equal(f.sound.get().sound, 'mechanicalImpact');
  const impact = f.sources.at(-1).buffer;
  assert.notEqual(impact.duration, confirm.duration);
  assert.notDeepEqual([...impact.getChannelData(0).slice(100, 200)], [...confirm.getChannelData(0).slice(100, 200)]);
  f.advance();
  f.fire(element()); f.flush();
  assert.equal(f.sources.at(-1).buffer, impact);
  const reloaded = setup({ saved: f.storage.get('wargame_ui_sound_v1') });
  assert.equal(reloaded.sound.get().sound, 'mechanicalImpact');
  assert.equal(reloaded.sound.get().volume, original.volume);
  reloaded.sound.setSound('unknown');
  assert.equal(reloaded.sound.get().sound, 'mechanicalImpact');
});

test('old pack preferences preserve mute and volume but default to confirm', () => {
  const f = setup({ saved: JSON.stringify({ enabled: false, volume: 0.2, pack: 'workshopGears' }) });
  assert.equal(f.sound.get().enabled, false);
  assert.equal(f.sound.get().volume, 0.2);
  assert.equal(f.sound.get().sound, 'confirm');
});

test('production page loads sound before interactions and exposes settings in the real view', () => {
  const index = read('index.html');
  assert.ok(index.indexOf('js/constants.js') < index.indexOf('js/sound.js'));
  assert.ok(index.indexOf('js/sound.js') < index.indexOf('js/core.js'));
  const f = setup();
  f.load('js/core.js'); f.load('js/main-view.js');
  const view = { innerHTML: '' };
  f.sandbox.Game.Core.views.settings(view);
  assert.match(view.innerHTML, /id="uiSoundEnabled"/);
  assert.match(view.innerHTML, /试听 · 确认/);
  assert.match(view.innerHTML, /试听 · 清脆机械碰撞/);
  assert.equal(f.contexts.length, 0);
});
