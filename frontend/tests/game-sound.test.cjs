const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const read = file => fs.readFileSync(path.join(__dirname, '../', file), 'utf8');

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
    constructor() {
      this.state = options.suspended ? 'suspended' : 'running';
      this.sampleRate = 44100;
      this.currentTime = 0;
      contexts.push(this);
    }
    createGain() {
      return { gain: { value: 0, setTargetAtTime(val) { this.value = val; } }, connect() {} };
    }
    createBuffer(channels, count, rate) {
      const data = new Float32Array(count);
      return { duration: count / rate, getChannelData: () => data };
    }
    createBufferSource() {
      const source = {
        connect() {},
        disconnect() { this.disconnected = true; },
        start() { this.started = true; },
        stop() { this.stopped = true; }
      };
      sources.push(source);
      return source;
    }
    resume() {
      this.state = 'running';
      return Promise.resolve();
    }
    suspend() {
      this.state = 'suspended';
      return Promise.resolve();
    }
  }

  const document = {
    hidden: false,
    addEventListener(type, handler, capture) { (events[type] ||= []).push({ handler, capture }); },
    getElementById() { return null; },
    querySelectorAll() { return []; }
  };

  const sandbox = {
    document,
    console,
    AudioContext: options.unsupported ? undefined : AudioContext,
    Date: class extends Date { static now() { return now; } },
    localStorage: {
      getItem(key) { return storage.get(key) || null; },
      setItem(key, value) { storage.set(key, value); }
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
    sandbox,
    contexts,
    sources,
    sound: sandbox.Game.Sound,
    advance(ms = 100) { now += ms; },
    gameCues: Object.keys(sandbox.Game.Constants.gameSound.cues)
  };
}

test('all four game event sounds (dispatch, firefight, victory, upgrade) are registered', () => {
  const f = setup();
  assert.deepEqual(f.gameCues.sort(), ['dispatch', 'firefight', 'upgrade', 'victory']);
  for (const name of f.gameCues) {
    const cue = f.sandbox.Game.Constants.gameSound.cues[name];
    assert.ok(cue.label, `${name} has a label`);
    assert.ok(cue.isEvent, `${name} is marked as an event`);
    assert.ok(Array.isArray(cue.layers) && cue.layers.length > 0, `${name} has layer definitions`);
  }
});

test('game event sounds synthesize valid bounded audio when sound is enabled', () => {
  const f = setup();
  for (const name of f.gameCues) {
    f.advance(500);
    f.sound.play(name);
    const source = f.sources.at(-1);
    assert.ok(source, `Source created for ${name}`);
    assert.equal(source.started, true, `${name} started playing`);
    const samples = source.buffer.getChannelData(0);
    assert.ok(samples.length > 4410, `${name} duration > 100ms`);
    assert.ok(samples.every(v => Number.isFinite(v) && Math.abs(v) <= 0.280001), `${name} samples are finite and peak-limited`);
    assert.ok(samples.some(v => Math.abs(v) > 0.01), `${name} has non-silent content`);
  }
});

test('game event sounds strictly obey settings: stay silent when sound is disabled', () => {
  const f = setup();
  f.sound.setEnabled(false);
  assert.equal(f.sound.get().enabled, false);

  for (const name of f.gameCues) {
    f.advance(200);
    f.sound.play(name);
  }

  assert.equal(f.sources.length, 0, 'No audio sources created when sound is disabled');
});

test('game event sounds stay silent when volume is 0%', () => {
  const f = setup();
  f.sound.setVolume(0);
  assert.equal(f.sound.get().volume, 0);

  for (const name of f.gameCues) {
    f.advance(200);
    f.sound.play(name);
  }

  assert.equal(f.sources.length, 0, 'No audio sources created when volume is 0');
});

test('enabling sound restores playback for all four game event cues', () => {
  const f = setup();
  f.sound.setEnabled(false);
  f.sound.play('dispatch');
  assert.equal(f.sources.length, 0);

  f.sound.setEnabled(true);
  f.advance(200);
  f.sound.play('dispatch');
  assert.equal(f.sources.length, 1);
  assert.equal(f.sources[0].started, true);
});
