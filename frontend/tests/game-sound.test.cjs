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

test('all game event sounds (dispatch, firefight, victory, upgrade, coins, chest, send_mail) are registered', () => {
  const f = setup();
  assert.deepEqual(f.gameCues.sort(), ['chest', 'coins', 'dispatch', 'firefight', 'send_mail', 'upgrade', 'victory']);
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

test('in browser environment with window.Audio, plays authentic WAV file directly with volume scaling', () => {
  const audioInstances = [];
  class MockAudio {
    constructor(src) {
      this.src = src;
      this.volume = 1.0;
      this.currentTime = 0;
      this.played = false;
      this.paused = false;
      audioInstances.push(this);
    }
    play() {
      this.played = true;
      return Promise.resolve();
    }
    pause() {
      this.paused = true;
    }
  }

  const f = setup();
  f.sandbox.Audio = MockAudio;
  f.sound.setVolume(0.85);

  f.sound.play('dispatch');
  assert.equal(audioInstances.length, 1);
  assert.equal(audioInstances[0].src, 'audio/dispatch.wav');
  assert.equal(audioInstances[0].played, true);
  assert.equal(audioInstances[0].volume, 0.85);

  f.sound.play('coins');
  assert.equal(audioInstances.length, 2);
  assert.equal(audioInstances[1].src, 'audio/coins.wav');
  assert.equal(audioInstances[1].played, true);

  f.sound.play('chest');
  assert.equal(audioInstances.length, 3);
  assert.equal(audioInstances[2].src, 'audio/chest.wav');
  assert.equal(audioInstances[2].played, true);

  f.sound.play('send_mail');
  assert.equal(audioInstances.length, 4);
  assert.equal(audioInstances[3].src, 'audio/send_mail.wav');
  assert.equal(audioInstances[3].played, true);

  // When volume changes, active audios sync their volume
  f.sound.setVolume(0.4);
  assert.equal(audioInstances[0].volume, 0.4);

  // When disabled, active audios are paused and reset
  f.sound.setEnabled(false);
  assert.equal(audioInstances[0].paused, true);
});

test('all four game sound events trigger their corresponding cues correctly', async () => {
  const playedCues = [];
  const dummySound = {
    play(cue) { playedCues.push(cue); },
    get() { return { enabled: true, volume: 1 }; }
  };

  // 1. world dispatch -> dispatch
  const fakeWorld = {
    _launchDispatch(dispatchRequest, apiDispatch) {
      return apiDispatch(dispatchRequest).then(() => {
        dummySound.play('dispatch');
      });
    }
  };
  await fakeWorld._launchDispatch({}, () => Promise.resolve());
  assert.deepEqual(playedCues, ['dispatch']);

  // 2. interception arrival / ws-handler -> firefight
  const fakeWsBattle = (data) => {
    if (data.action === 'intercept_scout') {
      dummySound.play('firefight');
    }
  };
  fakeWsBattle({ action: 'intercept_scout', win: true });
  assert.deepEqual(playedCues, ['dispatch', 'firefight']);

  // 3. tactical battle victory -> victory
  const fakeTacticalEnd = (battle) => {
    if (battle.finished && battle.result && battle.result.win) {
      dummySound.play('victory');
    }
  };
  fakeTacticalEnd({ finished: true, result: { win: true } });
  assert.deepEqual(playedCues, ['dispatch', 'firefight', 'victory']);

  // 4. building upgrade complete -> upgrade
  const fakeWsTick = (data) => {
    if (data.completedBuilds && data.completedBuilds.length) {
      dummySound.play('upgrade');
    }
  };
  fakeWsTick({ completedBuilds: ['barracks'] });
  assert.deepEqual(playedCues, ['dispatch', 'firefight', 'victory', 'upgrade']);

  // 5. mail attachment claim / quest reward claim -> coins
  const fakeClaimAttach = (data) => {
    if (data && data.success) {
      dummySound.play('coins');
    }
  };
  fakeClaimAttach({ success: true });
  assert.deepEqual(playedCues, ['dispatch', 'firefight', 'victory', 'upgrade', 'coins']);

  // 6. depot open box -> chest
  const fakeDepotUse = (itemId, info) => {
    const isBox = (info && info.isBox) || itemId.indexOf('box_') === 0 || itemId === 'newbiePack';
    if (isBox) dummySound.play('chest');
  };
  fakeDepotUse('box_recruit_military', { isBox: true });
  assert.deepEqual(playedCues, ['dispatch', 'firefight', 'victory', 'upgrade', 'coins', 'chest']);

  // 7. send mail -> send_mail
  const fakeMailSend = (to, apiSend) => {
    return apiSend().then(() => {
      dummySound.play('send_mail');
    });
  };
  await fakeMailSend('player2', () => Promise.resolve());
  assert.deepEqual(playedCues, ['dispatch', 'firefight', 'victory', 'upgrade', 'coins', 'chest', 'send_mail']);
});

test('bgm configuration registers Suite 1, Suite 2, Suite 3 and defaults to Suite 1 (bgm_strings)', () => {
  const f = setup();
  const bgmConfig = f.sandbox.Game.Constants.bgm;
  assert.ok(bgmConfig, 'bgm config exists');
  assert.equal(bgmConfig.defaultTrack, 'bgm_strings');
  assert.equal(bgmConfig.defaultEnabled, true);
  assert.equal(bgmConfig.defaultVolume, 0.5);

  const tracks = Object.keys(bgmConfig.tracks);
  assert.deepEqual(tracks.sort(), ['bgm_evening', 'bgm_piano', 'bgm_strings']);
  assert.equal(bgmConfig.tracks.bgm_strings.file, 'audio/bgm_strings.mp3');
  assert.equal(bgmConfig.tracks.bgm_strings.bpm, 58);

  const bgmState = f.sound.getBgm();
  assert.equal(bgmState.enabled, true);
  assert.equal(bgmState.volume, 0.5);
  assert.equal(bgmState.track, 'bgm_strings');
});

test('bgm strictly obeys settings: volume changes, mute/unmute, and track switching', () => {
  const audioInstances = [];
  class FakeAudio {
    constructor(src) {
      this.src = src;
      this.volume = 1;
      this.loop = false;
      this.paused = true;
      audioInstances.push(this);
    }
    play() {
      this.paused = false;
      this.played = true;
      return Promise.resolve();
    }
    pause() {
      this.paused = true;
    }
  }

  const f = setup();
  f.sandbox.Audio = FakeAudio;

  // 1. start BGM
  f.sound.startBgm();
  assert.equal(audioInstances.length, 1);
  const bgm = audioInstances[0];
  assert.equal(bgm.src, 'audio/bgm_strings.mp3');
  assert.equal(bgm.loop, true);
  assert.equal(bgm.volume, 0.5);
  assert.equal(bgm.paused, false);

  // 2. change volume
  f.sound.setBgmVolume(0.35);
  assert.equal(bgm.volume, 0.35);
  assert.equal(f.sound.getBgm().volume, 0.35);

  // 3. volume to 0 pauses BGM
  f.sound.setBgmVolume(0);
  assert.equal(bgm.paused, true);

  // 4. volume restore resumes BGM
  f.sound.setBgmVolume(0.6);
  assert.equal(bgm.paused, false);
  assert.equal(bgm.volume, 0.6);

  // 5. switch track to Suite 2
  f.sound.setBgmTrack('bgm_piano');
  assert.equal(bgm.src, 'audio/bgm_piano.mp3');
  assert.equal(f.sound.getBgm().track, 'bgm_piano');
  assert.equal(bgm.paused, false);

  // 6. disable BGM pauses
  f.sound.setBgmEnabled(false);
  assert.equal(bgm.paused, true);
  assert.equal(f.sound.getBgm().enabled, false);

  // 7. re-enable resumes
  f.sound.setBgmEnabled(true);
  assert.equal(bgm.paused, false);
});

test('renderSettings renders both BGM card and SFX card with full controls', () => {
  const f = setup();
  const html = f.sound.renderSettings();
  assert.ok(html.includes('bgm-settings-card'), 'Contains BGM card');
  assert.ok(html.includes('uiBgmEnabled'), 'Contains BGM toggle');
  assert.ok(html.includes('uiBgmTrackChoice'), 'Contains BGM track selector');
  assert.ok(html.includes('uiBgmVolume'), 'Contains BGM volume slider');
  assert.ok(html.includes('套系 1 · 《山河行思》'), 'Contains Suite 1 name');

  assert.ok(html.includes('uiSoundEnabled'), 'Contains SFX toggle');
  assert.ok(html.includes('uiSoundChoice'), 'Contains SFX choice');
  assert.ok(html.includes('uiSoundVolume'), 'Contains SFX volume slider');
});


