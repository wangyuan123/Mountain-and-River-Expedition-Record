/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var config = G.Constants.uiSound;
  var preferences = { enabled: true, volume: config.defaultVolume, sound: config.defaultSound };
  var context = null;
  var master = null;
  var buffers = {};
  var voices = [];
  var pending = null;
  var timer = null;
  var generation = 0;
  var lastPlayed = -Infinity;
  var initialized = false;
  var resumePromise = null;

  try {
    var saved = JSON.parse(window.localStorage.getItem(config.storageKey));
    if (saved && typeof saved.enabled === 'boolean') preferences.enabled = saved.enabled;
    if (saved && typeof saved.volume === 'number' && isFinite(saved.volume)) {
      preferences.volume = Math.max(0, Math.min(1, saved.volume));
    }
    // 旧版只保存套装；保留其开关和音量，新音色回到「确认」。
    if (saved && Object.prototype.hasOwnProperty.call(config.cues, saved.sound)) preferences.sound = saved.sound;
  } catch (e) { /* 隐私模式或损坏的偏好不能阻止游戏启动。 */ }

  function audible() {
    return preferences.enabled && preferences.volume > 0 && !document.hidden;
  }

  /** 仅由用户交互创建或恢复音频设备，浏览器拒绝时保持交互可用。 */
  function prepare() {
    if (!audible()) return null;
    try {
      if (!context || context.state === 'closed') {
        var AudioContext = window.AudioContext || window.webkitAudioContext;
        if (!AudioContext) return null;
        context = new AudioContext();
        master = context.createGain();
        master.gain.value = preferences.volume;
        master.connect(context.destination);
        buffers = {};
        resumePromise = null;
      }
      if (context.state !== 'running' && !resumePromise) {
        resumePromise = context.resume().then(function () { resumePromise = null; }, function () { resumePromise = null; });
      }
      return context;
    } catch (e) { return null; }
  }

  /** 缓存短促的按钮合成音；无需下载素材。 */
  function bufferFor(name) {
    if (buffers[name]) return buffers[name];
    var layers = config.cues[name].layers;
    var rate = context.sampleRate;
    var duration = 0;
    layers.forEach(function (layer) {
      duration = Math.max(duration, layer.at + layer.duration);
    });
    var buffer = context.createBuffer(1, Math.ceil(duration * rate), rate);
    var samples = buffer.getChannelData(0);
    var seed = 173;
    layers.forEach(function (layer) {
      var start = Math.floor(layer.at * rate);
      var count = Math.floor(layer.duration * rate);
      var phase = 0;
      var filtered = 0;
      for (var i = 0; i < count; i++) {
        var progress = i / count;
        var envelope = Math.min(1, i / (rate * 0.003)) * Math.pow(1 - progress, 3);
        var frequency = layer.from + (layer.to - layer.from) * progress;
        phase += 2 * Math.PI * frequency / rate;
        var wave;
        if (layer.wave === 'noise') {
          seed = (Math.imul(seed, 1664525) + 1013904223) | 0;
          var noise = (seed >>> 0) / 2147483648 - 1;
          filtered += Math.min(1, 2 * Math.PI * frequency / rate) * (noise - filtered);
          wave = filtered;
        } else {
          wave = Math.sin(phase);
          if (layer.wave === 'metal') wave = wave * 0.7 + Math.sin(phase * 2.76) * 0.3;
        }
        samples[start + i] += wave * envelope * layer.gain;
      }
    });
    // 单个音色峰值限制到 0.28，最多三声叠加也不会削波爆音。
    var peak = 0;
    for (var j = 0; j < samples.length; j++) peak = Math.max(peak, Math.abs(samples[j]));
    if (peak > 0.28) for (var k = 0; k < samples.length; k++) samples[k] *= 0.28 / peak;
    buffers[name] = buffer;
    return buffer;
  }

  function release(source) {
    var index = voices.indexOf(source);
    if (index !== -1) voices.splice(index, 1);
    try { source.disconnect(); } catch (e) { /* 节点可能已经结束。 */ }
  }

  function stop() {
    generation++;
    pending = null;
    window.clearTimeout(timer);
    voices.slice().forEach(function (source) {
      try { source.stop(); } catch (e) { /* 已播放完的节点无需再次停止。 */ }
      release(source);
    });
  }

  function emit(name, token, requestedAt) {
    if (token !== generation || !audible() || !context || context.state !== 'running') return;
    var now = Date.now();
    // 不补播旧操作；快速连点共享冷却时间，避免噪声堆积。
    if (now - requestedAt > config.maxDelayMs || now - lastPlayed < config.cooldownMs) return;
    try {
      if (voices.length >= config.maxVoices) return;
      var source = context.createBufferSource();
      source.buffer = bufferFor(name);
      source.connect(master);
      source.onended = function () { release(source); };
      source.start();
      voices.push(source);
      lastPlayed = now;
    } catch (e) {
      if (source) release(source);
      // 音频设备不可用时，不影响按钮原有行为。
    }
  }

  /** 播放一种交互音；未知音色、静音和后台页面均安静跳过。 */
  function play(name, requestedAt) {
    if (!Object.prototype.hasOwnProperty.call(config.cues, name) || !audible()) return;
    var audio = prepare();
    if (!audio) return;
    var token = generation;
    if (requestedAt == null) requestedAt = Date.now();
    if (audio.state === 'running') emit(name, token, requestedAt);
    else if (resumePromise) resumePromise.then(function () { emit(name, token, requestedAt); });
  }

  function save() {
    try { window.localStorage.setItem(config.storageKey, JSON.stringify(preferences)); } catch (e) { /* 本次会话仍保留设置。 */ }
    syncSettings();
  }

  function syncSettings() {
    var toggle = document.getElementById('uiSoundEnabled');
    var slider = document.getElementById('uiSoundVolume');
    var output = document.getElementById('uiSoundVolumeValue');
    var percent = Math.round(preferences.volume * 100);
    if (toggle) {
      toggle.setAttribute('aria-pressed', String(preferences.enabled));
      toggle.textContent = preferences.enabled ? '音效已开启' : '音效已关闭';
    }
    if (slider) { slider.value = percent; slider.disabled = !preferences.enabled; }
    if (output) output.textContent = percent + '%';
    var soundSelect = document.getElementById('uiSoundChoice');
    if (soundSelect) { soundSelect.value = preferences.sound; soundSelect.disabled = !preferences.enabled; }
    document.querySelectorAll('[data-sound-preview]').forEach(function (button) { button.disabled = !preferences.enabled || percent === 0; });
  }

  function actionable(target) {
    var node = target && (target.nodeType === 3 ? target.parentElement : target);
    while (node && node !== document) {
      if (node.matches && (node.matches('button, a[href], [role=button], [role=tab], [onclick], [data-ui-sound], input[type=button], input[type=submit]') || typeof node.onclick === 'function')) return node;
      node = node.parentElement;
    }
    return null;
  }

  function begin(event) {
    if (event.type === 'click' && event.button > 0) return;
    var target = event.target;
    var control = actionable(target);
    if (event.type === 'keydown') {
      if (event.repeat || event.altKey || event.ctrlKey || event.metaKey) return;
      if (target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName))) return;
      if (event.key !== 'Enter' && event.key !== ' ' && event.key !== 'Backspace' && event.key !== '*' && event.key !== '0') return;
      if (event.key === '*' && (!G.Core || G.Core.route === 'home')) return;
      // 原生控件的键盘激活会生成 click；自定义卡片则在 keydown 上发声。
      if ((event.key === 'Enter' || event.key === ' ') && (!control || control.matches('button, a[href], input'))) return;
    }
    if (!control && event.type !== 'keydown') return;
    if (control && (control.disabled || control.closest('[disabled], [aria-disabled=true], [inert], .disabled'))) return;
    var marker = control && control.closest('[data-ui-sound]');
    // 设置控件自行试听；其他可点击入口统一使用玩家选定的音效。
    if (marker && marker.getAttribute('data-ui-sound') === 'none') return;
    window.clearTimeout(timer);
    generation++;
    pending = { requestedAt: Date.now() };
    prepare();
    // 捕获阶段先解锁设备；当前事件处理结束后再取业务入口标注的音色。
    timer = window.setTimeout(function () {
      var interaction = pending;
      pending = null;
      if (interaction) play(preferences.sound, interaction.requestedAt);
    }, 0);
  }

  G.Sound = {
    /** 幂等绑定到 document，适配 Core.render 重建页面和动态弹窗。 */
    init: function () {
      if (initialized) return;
      initialized = true;
      document.addEventListener('click', begin, true);
      document.addEventListener('keydown', begin, true);
      document.addEventListener('visibilitychange', function () {
        if (!document.hidden) return;
        stop();
        if (context && context.state === 'running') {
          try { context.suspend().catch(function () {}); } catch (e) { /* 不支持挂起时也已停止所有音源。 */ }
        }
      });
    },
    play: play,
    get: function () { return { enabled: preferences.enabled, volume: preferences.volume, sound: preferences.sound }; },
    /** 更换全局按钮音色并试听，未知值不覆盖当前偏好。 */
    setSound: function (name) {
      if (!Object.prototype.hasOwnProperty.call(config.cues, name) || preferences.sound === name) return;
      preferences.sound = name;
      stop();
      save();
      play(name);
    },
    setEnabled: function (enabled) {
      preferences.enabled = !!enabled;
      if (!preferences.enabled) stop();
      save();
    },
    toggle: function () {
      this.setEnabled(!preferences.enabled);
      if (preferences.enabled) play(preferences.sound);
    },
    /** 音量按 0～1 保存为设备偏好，滑动时只调音量，松手才试听。 */
    setVolume: function (volume) {
      if (typeof volume !== 'number' || !isFinite(volume)) return;
      preferences.volume = Math.max(0, Math.min(1, volume));
      if (master && context) master.gain.setTargetAtTime(preferences.volume, context.currentTime, 0.015);
      if (!preferences.volume) stop();
      save();
    },
    renderSettings: function () {
      var percent = Math.round(preferences.volume * 100);
      var disabled = !preferences.enabled;
      var soundOptions = '';
      Object.keys(config.cues).forEach(function (name) {
        soundOptions += '<option value="' + name + '"' + (name === preferences.sound ? ' selected' : '') + '>' + config.cues[name].label + '</option>';
      });
      var html = '<div class="zone-head"><span class="zone-title">按钮音效</span></div>' +
        '<section class="panel sound-settings" aria-label="按钮音效设置" data-ui-sound="none">' +
        '<div class="sound-setting-row"><span id="uiSoundLabel">操作反馈</span><button type="button" class="btn sm" id="uiSoundEnabled" aria-describedby="uiSoundLabel" aria-pressed="' + preferences.enabled + '" onclick="Game.Sound.toggle()">' + (disabled ? '音效已关闭' : '音效已开启') + '</button></div>' +
        '<div class="sound-setting-row"><label for="uiSoundChoice">按钮声音</label><select id="uiSoundChoice" onchange="Game.Sound.setSound(this.value)"' + (disabled ? ' disabled' : '') + '>' + soundOptions + '</select></div>' +
        '<div class="sound-setting-row"><label for="uiSoundVolume">音量</label><input id="uiSoundVolume" type="range" min="0" max="100" step="1" value="' + percent + '"' + (disabled ? ' disabled' : '') + ' oninput="Game.Sound.setVolume(Number(this.value)/100)" onchange="Game.Sound.play(Game.Sound.get().sound)"/><output id="uiSoundVolumeValue" for="uiSoundVolume">' + percent + '%</output></div>' +
        '<div class="sound-previews" aria-label="试听音效">';
      Object.keys(config.cues).forEach(function (name) {
        html += '<button type="button" class="btn sm" data-sound-preview="' + name + '"' + (disabled || !percent ? ' disabled' : '') + ' onclick="Game.Sound.play(&quot;' + name + '&quot;)">试听 · ' + config.cues[name].label + '</button>';
      });
      return html + '</div></section>';
    }
  };
  G.Sound.init();
})(window.Game);
