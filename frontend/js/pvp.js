/* global window, document */
window.Game = window.Game || {};
(function (G) {
  'use strict';
  G.gameNow = G.gameNow || function () { return Date.now(); };
  var Core = G.Core, C = G.Constants.pvp;
  var state = null, battle = null, lobby = null, config = null, report = null;
  var context = '', timer = null, lastPoll = 0, lastBeat = 0, reading = null;
  var busy = false, error = '', notice = '', pending = null, dirty = false, saving = false, saveTimer = null;
  var lobbyLoading = false, readAgain = false, loadedAttemptAt = 0;
  var drafts = {}, loadedAttempt = '', rendered = '', estimate = null, dismissed = '', tab = 'lobby';
  var actions = { ADVANCE: '前进', RETREAT: '后退', HOLD: '待命' };
  var resultNames = { WIN: '演习胜利', LOSS: '演习失利', DRAW: '双方平局', VOID: '本局作废' };
  var reasons = { NO_COMMANDER_OR_SKILL: '未携带该技能', NOT_ENGAGED: '尚未交战', OUTSIDE_SKILL_WINDOW: '非技能生效回合', WINDOW_OPEN: '本回合技能窗口开启', NO_VALID_RANGE_TARGET: '无有效射程目标', NO_SAME_UNIT_REFERENCE: '无存活同名参考单位', BULWARK_PRIORITY: '坚守阵地优先，借甲不生效', APPLIED_IF_LEARNED: '已学习时适用', ELIMINATED_BEFORE_COUNTER: '受击后全歼，无法反击', NO_LIVING_ATTACKER: '攻击方已全歼', NO_APPLICABLE_ATTACK_DOMAIN: '没有适用的攻击领域', LOAD_TIMEOUT: '对手未在20秒内完成入场', ENGINE_ERROR: '战斗异常，已作废', RULES_INCOMPATIBLE: '规则不兼容', OUTAGE_TOO_LONG: '服务故障时间过长', ACCOUNT_UNAVAILABLE: '参战账号不可用，本局作废', SURRENDER: '主动认输', COMBAT: '战斗结算' };
  function esc(v) { return G.escapeHtml(String(v == null ? '' : v)); }
  function unit(id) { return G.DATA.units[id] || {}; }
  function name(id) { return unit(id).name || id; }
  function id() { return window.crypto && window.crypto.randomUUID ? window.crypto.randomUUID() : 'pvp_' + Date.now() + '_' + Math.random().toString(36).slice(2); }
  function key() { var c = G.API.client; return c.serverId + ':' + c.getToken(); }
  function now() { return G.gameNow(); }
  function clock(ms) { return G.formatDuration(ms / 1000); }
  function active() { return state && ['QUEUED', 'ACCEPTING', 'LOADING', 'COUNTDOWN', 'BATTLING', 'RECOVERING'].indexOf(state.status) >= 0; }
  /** 声望达到门槛后开放对战页操作，账号状态尚未加载时保持锁定。 */
  function prestigeLocked() { return !G.ServerRules.value('pvpAllowed', false); }
  function reset() {
    state = battle = lobby = config = report = estimate = pending = null;
    busy = dirty = saving = false; error = notice = loadedAttempt = rendered = dismissed = ''; drafts = {}; tab = 'lobby';
    lastPoll = lastBeat = loadedAttemptAt = 0; lobbyLoading = false; if (saveTimer) clearTimeout(saveTimer);
  }
  function syncClock(data) { if (data) G.GameTime.sync(data.serverTime); }
  function show() { if (Core.route === 'pvp') render(document.getElementById('view')); }
  function view() { if (Core.route !== 'pvp') G.go('pvp'); else show(); }
  function request(method, path, body) { return G.API.client.request(method, '/game/pvp/' + path, body, { silent: true, retry: 0, timeout: method === 'GET' ? 10000 : 120000 }); }

  /** 每次登录/大区切换重建本地状态；异步响应只应用到发起请求的账号。 */
  function start() {
    if (!G.API.client.isLoggedIn()) return;
    if (key() !== context) { context = key(); reset(); }
    if (!timer) timer = setInterval(tick, 250);
    return recover();
  }
  function applyStatus(s) {
    if (!s || s.region !== G.API.client.serverId || state && s.version < state.version) return;
    var previous = state; state = s; syncClock(s);
    if (s.sessionId && ['LOADING', 'COUNTDOWN', 'BATTLING', 'RECOVERING'].indexOf(s.status) >= 0) {
      if (!battle || battle.sessionId !== s.sessionId) { battle = null; report = null; tab = 'lobby'; drafts = {}; }
      // 双方接受后立即打开指挥页；切到其他真实功能后不会被每次回合推送强行拉回。
      if (!previous || previous.sessionId !== s.sessionId || previous.status === 'ACCEPTING') view();
    }
    if (!active() && previous && active() && !s.lastSessionId) notice = '匹配已结束，可重新开始。';
  }
  function applyBattle(b) {
    if (!b || state && b.sessionId !== state.sessionId && b.sessionId !== state.lastSessionId) return;
    if (battle && battle.sessionId === b.sessionId && (b.version < battle.version || b.round < battle.round)) return;
    var same = battle && battle.sessionId === b.sessionId && battle.round === b.round;
    if (!same) { drafts = Object.assign({}, b.draft || {}); dirty = false; saving = false; }
    else if (b.locked || !dirty && !saving) drafts = Object.assign({}, b.draft || {});
    battle = b; syncClock(b);
  }
  function recover() {
    if (reading) { readAgain = true; return reading; }
    var captured = context;
    reading = request('GET', 'status').then(function (s) {
      if (captured !== context || key() !== captured) return;
      applyStatus(s);
      if (s.sessionId && s.status !== 'ACCEPTING') return request('GET', 'battle?sessionId=' + encodeURIComponent(s.sessionId)).then(function (b) {
        if (captured !== context || key() !== captured) return;
        applyBattle(b); show();
        // 入场上报失败后仍可补报；限制频率，避免恢复查询形成即时重试循环。
        if (b.status === 'LOADING' && !b.loaded && !pending && (loadedAttempt !== b.sessionId || Date.now() - loadedAttemptAt >= C.battlePollMs)) {
          loadedAttempt = b.sessionId; loadedAttemptAt = Date.now();
          return mutate('loaded', { sessionId: b.sessionId }, function (r) { applyBattle(r); });
        }
      });
      if (s.lastSessionId && s.lastSessionId !== dismissed && !report && (!previousReport() || previousReport() !== s.lastSessionId)) {
        return loadReport(s.lastSessionId, !!battle);
      }
      show();
    }).catch(function (e) { if (captured === context) { error = e.message; show(); } }).finally(function () {
      reading = null;
      // 查询期间到达的推送可能已经改变状态，完成后再读取，避免错过20秒入场窗口。
      if (readAgain) { readAgain = false; setTimeout(recover, 0); }
    });
    return reading;
  }
  function previousReport() { return report && report.sessionId; }

  /** 写请求结果不明时先查询，不自动重试；手动重试复用同一requestId和原始内容。 */
  function mutate(path, body, apply, original) {
    if (busy || pending && !original) return Promise.resolve();
    var req = original || JSON.parse(JSON.stringify(Object.assign({ requestId: id() }, body))), captured = context;
    busy = true; error = ''; show();
    return request('POST', path, req).then(function (r) {
      if (captured !== context || key() !== captured) return;
      pending = null; notice = ''; if (apply) apply(r); show();
    }).catch(function (e) {
      if (captured !== context) return;
      error = e.message;
      if (e.uncertain) pending = { path: path, body: req, apply: apply };
      if (reading) { setTimeout(recover, 0); return; }
      return recover();
    }).finally(function () { if (captured === context) { busy = false; show(); } });
  }
  function retry() {
    if (!pending || busy) return;
    var p = pending;
    recover().then(function () {
      if (pending !== p) return;
      return mutate(p.path, p.body, p.apply, p.body);
    });
  }
  function tick() {
    if (!G.API.client.isLoggedIn() || Core.route === 'login' || key() !== context) { if (key() !== context) { context = key(); reset(); } return; }
    updateTimes();
    var t = Date.now();
    if (active() && state.ticketId && t - lastBeat >= C.heartbeatMs) {
      lastBeat = t; var captured = context;
      request('POST', 'queue/heartbeat', { ticketId: state.ticketId }).then(function (s) {
        if (captured === context) {
          applyStatus(s); show();
          if (s.sessionId && s.status !== 'ACCEPTING' || s.lastSessionId) recover();
        }
      }).catch(function () { recover(); });
    }
    var interval = state && state.ticketId ? C.queuePollMs : C.battlePollMs;
    if (active() && (!G.WS || !G.WS.connected || state.status === 'LOADING') && t - lastPoll >= interval) { lastPoll = t; recover(); }
    // WebSocket丢失单条截止事件时也查询一次；不会按客户端时间自行推进回合。
    if (active() && state.deadlineAt && now() >= state.deadlineAt && t - lastPoll >= C.battlePollMs) { lastPoll = t; recover(); }
  }
  function updateTimes() {
    var el = document.getElementById('pvp-clock');
    if (el && state) el.textContent = state.status === 'QUEUED' ? clock(now() - state.queuedAt) : clock((battle && battle.deadlineAt || state.deadlineAt) - now());
    var hint = document.getElementById('pvp-search-hint');
    if (hint && state && state.status === 'QUEUED') {
      var wait = now() - state.queuedAt;
      hint.textContent = wait >= 120000 ? '当前合适对手较少，继续寻找中' : wait >= 30000 ? '正在扩大搜索范围' : '正在寻找旗鼓相当的对手';
    }
    var lock = document.getElementById('pvp-lock');
    if (lock && battle) lock.disabled = busy || saving || !!pending || battle.locked || battle.auto || battle.status !== 'BATTLING' || now() >= battle.deadlineAt;
  }
  function loadLobby(slot) {
    if (lobbyLoading) return Promise.resolve();
    lobbyLoading = true;
    var captured = context;
    return request('GET', 'lobby' + (slot == null ? '' : '?citySlot=' + slot)).then(function (l) {
      if (captured !== context) return;
      lobby = l; applyStatus(l.status);
      if (!config) {
        config = JSON.parse(JSON.stringify(l.preset));
        if (!Object.keys(config.army || {}).length) config.army = defaultArmy(config.battlefield);
      }
      show();
    }).catch(function (e) { error = e.message; show(); }).finally(function () { lobbyLoading = false; });
  }
  /** 后端按战场类型返回默认编队；缺快照时保持空选择，禁止在浏览器重新分配上限。 */
  function defaultArmy(field) { return Object.assign({}, (lobby.available.defaultArmies || {})[field] || {}); }
  function setCount(k, value) { config.army[k] = Number(value); estimate = null; }
  function setDefault(k, value) { config.defaults[k] = { action: value }; estimate = null; }
  function setField(field) { config.battlefield = field; config.army = defaultArmy(field); estimate = null; rendered = ''; show(); }
  function setCity(slot) { config.citySlot = Number(slot); config.commanderId = null; config.army = {}; estimate = null; loadLobby(slot).then(function () { config.army = defaultArmy(config.battlefield); rendered = ''; show(); }); }
  function savePreset() {
    if (prestigeLocked()) return Promise.resolve();
    var body = Object.assign({}, config, { presetVersion: lobby.presetVersion });
    return mutate('preset', body, function (r) { lobby.presetVersion = r.presetVersion; estimate = r.snapshot; applyStatus(r.status); notice = '演习编队与默认战术已保存'; });
  }
  function queue() {
    if (prestigeLocked() || !config || !lobby) return;
    dismissed = ''; report = battle = null; tab = 'lobby';
    // 将独立默认战术先保存；队列只使用服务端冻结后的配置版本。
    savePreset().then(function () {
      if (prestigeLocked() || pending || error || busy) return;
      return mutate('queue', { citySlot: config.citySlot, battlefield: config.battlefield, army: config.army, commanderId: config.commanderId, presetVersion: lobby.presetVersion }, applyStatus);
    });
  }
  function cancel() { return mutate('queue/cancel', { ticketId: state.ticketId }, applyStatus); }
  function accept() { return mutate('accept', { ticketId: state.ticketId, sessionId: state.sessionId }, function (s) { applyStatus(s); recover(); }); }
  function setOrder(k, field, value) {
    if (!battle || battle.locked || battle.auto || now() >= battle.deadlineAt) return;
    drafts[k] = Object.assign({}, drafts[k] || battle.mine.defaults[k], field === 'action' ? { action: value } : { focusTarget: value || null });
    dirty = true; notice = '正在保存指令…';
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = setTimeout(function () { saveOrders(false); }, 120);
  }
  function saveOrders(lock) {
    if (!battle || busy || pending || saving || battle.locked || now() >= battle.deadlineAt) return Promise.resolve();
    if (saveTimer) clearTimeout(saveTimer);
    saving = true; var selected = JSON.stringify(drafts), round = battle.round;
    return mutate('orders', { sessionId: battle.sessionId, round: round, revision: battle.revision + 1, orders: JSON.parse(selected), lock: !!lock }, function (b) {
      if (JSON.stringify(drafts) === selected || b.round !== round) dirty = false;
      applyBattle(b); notice = lock ? '本回合已锁定，等待对手' : '指令已保存';
      if (b.status === 'FINISHED' || b.status === 'VOID') recover();
    }).finally(function () {
      saving = false;
      if (dirty && !pending && !error && battle && battle.round === round && !battle.locked) saveTimer = setTimeout(function () { saveOrders(false); }, 120);
      show();
    });
  }
  function auto() { return mutate('auto', { sessionId: battle.sessionId, expectedRound: battle.round, enabled: !battle.auto }, function (b) { dirty = false; applyBattle(b); }); }
  function surrender() { return mutate('surrender', { sessionId: battle.sessionId }, function (b) { applyBattle(b); recover(); loadReport(b.sessionId, true); }); }
  function loadReport(session, open) {
    var captured = context;
    return request('GET', 'report?sessionId=' + encodeURIComponent(session)).then(function (r) { if (captured !== context) return; report = r; tab = 'report'; if (open) view(); else show(); }).catch(function (e) { error = e.message; show(); });
  }
  function backLobby() { dismissed = state && state.lastSessionId || report && report.sessionId || ''; report = null; battle = null; tab = 'lobby'; rendered = ''; lobby = null; return loadLobby(config ? config.citySlot : 0); }
  function flash() {
    return (error ? '<div class="pvp-error" role="alert">' + esc(error) + '</div>' : '') + (notice ? '<div class="pvp-notice" role="status">' + esc(notice) + '</div>' : '')
      + (pending ? '<div class="pvp-error">操作结果尚未确认，已查询服务端状态。<button class="btn" onclick="Game.Pvp.retry()">使用原请求再次确认</button></div>' : '');
  }
  function summary(s) {
    if (!s) return '';
    var armyKeys = Object.keys(s.army || {}).filter(function (k) { return (Number(s.army[k]) || 0) > 0; });
    var h = '<div class="pvp-summary">';
    h += '<div class="pvp-summary-header">';
    h += '<div class="pvp-summary-commander"><span class="pvp-summary-badge">带兵将领</span><strong>' + esc(s.commanderName) + '</strong></div>';
    h += '<div class="pvp-summary-power"><span>演习实力</span><b>' + esc(s.power) + '</b></div>';
    h += '</div>';
    h += '<div class="pvp-summary-units">';
    armyKeys.forEach(function (k) {
      h += '<span class="pvp-unit-chip"><span class="unit-name">' + esc(name(k)) + '</span><b class="unit-qty">' + s.army[k] + '</b></span>';
    });
    if (!armyKeys.length) h += '<span class="pvp-muted">未指派兵力</span>';
    h += '</div>';
    h += '<div class="pvp-summary-footer">司令官：<b>' + esc(s.chief && s.chief.name || '未任命') + '</b> · 行动火力 <b>×' + (s.firepowerMultiplier == null ? '待同步' : Number(s.firepowerMultiplier).toFixed(1)) + '</b> <span class="pvp-muted">（实力估值已包含此倍率）</span></div>';
    h += '</div>';
    return h;
  }
  function disabled() { return busy || pending ? ' disabled' : ''; }

  /** 生成声望未达标时的战备准入限制卡片与行动引导。 */
  function lockedHtml(prestige) {
    var min = C.minPrestige || 10000;
    var pct = Math.min(100, Math.max(0, (prestige / min) * 100)).toFixed(1);
    var needed = Math.max(0, min - prestige);
    var fmtNeeded = typeof G !== 'undefined' && G.fmt ? G.fmt(needed) : needed;

    var h = '<div class="pvp-prestige-overlay" role="dialog" aria-modal="true" aria-label="PvP对战解锁条件">';
    h += '<div class="pvp-prestige-message" role="status" aria-live="polite">';
    h += '<div class="pvp-prestige-badge-row">';
    h += '<div class="pvp-prestige-icon-wrap" aria-hidden="true">';
    h += '<svg class="pvp-prestige-icon" viewBox="0 0 48 48" width="40" height="40" fill="none">';
    h += '<circle cx="24" cy="24" r="22" stroke="currentColor" stroke-width="2" stroke-dasharray="3 3" opacity="0.35"/>';
    h += '<path d="M24 7 L37 13 V23 C37 31.5 24 39 24 39 C24 39 11 31.5 11 23 V13 Z" fill="currentColor" fill-opacity="0.08" stroke="currentColor" stroke-width="2.2" stroke-linejoin="round"/>';
    h += '<rect x="18" y="22" width="12" height="10" rx="2" fill="currentColor" fill-opacity="0.88"/>';
    h += '<path d="M21 22 V18 C21 16.34 22.34 15 24 15 C25.66 15 27 16.34 27 18 V22" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/>';
    h += '<circle cx="24" cy="26" r="1.5" fill="#fff"/>';
    h += '</svg>';
    h += '</div>';
    h += '<span class="pvp-prestige-tag">战备准入限制</span>';
    h += '</div>';
    h += '<strong class="pvp-prestige-title">声望达到 ' + min + ' 后解锁 PvP 对战功能</strong>';
    h += '<div class="pvp-prestige-progress-wrap">';
    h += '<div class="pvp-prestige-val-row">';
    h += '<p class="pvp-prestige-val">当前声望：' + prestige + ' / ' + min + '</p>';
    h += '<span class="pvp-prestige-percent">' + pct + '%</span>';
    h += '</div>';
    h += '<div class="pvp-prestige-track" role="progressbar" aria-valuenow="' + prestige + '" aria-valuemin="0" aria-valuemax="' + min + '">';
    h += '<div class="pvp-prestige-bar" style="width:' + pct + '%"></div>';
    h += '</div>';
    h += '<div class="pvp-prestige-remain">还需 <b>' + fmtNeeded + '</b> 点声望解锁真人匹配权限</div>';
    h += '</div>';
    h += '<div class="pvp-prestige-guide">';
    h += '<div class="pvp-prestige-guide-title">快速提升声望途径：</div>';
    h += '<ul class="pvp-prestige-guide-list">';
    h += '<li><span>⚔️</span> 出征野地据点与战术防御</li>';
    h += '<li><span>📜</span> 推进主线任务提升军衔等级</li>';
    h += '<li><span>🏗️</span> 升级城防工事与扩充军备规模</li>';
    h += '</ul>';
    h += '</div>';
    h += '<div class="pvp-prestige-actions">';
    h += '<button type="button" class="btn ok pvp-prestige-btn" onclick="Game.go(\'mainQuest\')">前往任务提升声望</button>';
    h += '<button type="button" class="btn pvp-prestige-btn" onclick="Game.go(\'world\')">前往地图征战</button>';
    h += '</div>';
    h += '</div></div>';
    return h;
  }

  /** 保留编队和草稿；声望变化时刷新蒙层，达标后恢复操作。 */
  function render(v) {
    if (!v) return;
    if (key() !== context) start();
    var prestige = Math.max(0, Number(Core.state && Core.state.prestige) || 0), locked = prestigeLocked();
    var signature = JSON.stringify([state, battle, lobby, config, report, tab, busy, pending && pending.path, error, notice, drafts, estimate, prestige, locked]);
    if (rendered === signature && v.querySelector('.pvp-page')) { updateTimes(); return; }
    var focused = document.activeElement, focusId = focused && focused.id, scroll = v.scrollTop;
    rendered = signature;
    // inert 同时阻止鼠标、触摸和键盘进入预览内容，蒙层仅覆盖当前对战页。
    var h = '<section class="pvp-page' + (locked ? ' pvp-page-locked' : '') + '"><div class="pvp-content"' + (locked ? ' inert aria-disabled="true"' : '') + '><div class="pvp-head"><div><small>指挥演习 · 真人匹配</small><h2>1v1 匹配演习</h2></div><span class="pvp-badge">虚拟演习 · 实际兵力零损失</span></div>' + flash();
    if (state && (state.status === 'QUEUED' || state.status === 'ACCEPTING')) h += queueHtml();
    else if (battle && ['LOADING', 'COUNTDOWN', 'BATTLING', 'RECOVERING'].indexOf(battle.status) >= 0 && active()) h += battleHtml();
    else if (tab === 'report' && report) h += reportHtml();
    else if (!lobby || !config) { h += '<p>正在读取演习编队…</p>'; if (!busy) loadLobby(config ? config.citySlot : null); }
    else h += lobbyHtml();
    h += '</div>';
    if (locked) h += lockedHtml(prestige);
    v.innerHTML = h + '</section>'; v.scrollTop = scroll;
    if (focusId && !locked) { var next = document.getElementById(focusId); if (next) next.focus(); }
    updateTimes();
  }
  function clearArmy() {
    if (!config || !lobby) return;
    Object.keys(G.DATA.units).forEach(function (k) {
      config.army[k] = 0;
      var el = document.getElementById('pvp-count-' + k);
      if (el) el.value = 0;
    });
    var n = document.getElementById('pvp-selected');
    if (n) n.textContent = 0;
    estimate = null;
  }
  function fillMax() {
    if (!config || !lobby) return;
    config.army = defaultArmy(config.battlefield);
    Object.keys(G.DATA.units).forEach(function (k) {
      var el = document.getElementById('pvp-count-' + k);
      if (el) el.value = config.army[k] || 0;
    });
    var total = Object.values(config.army).reduce(function (a, b) { return a + (Number(b) || 0); }, 0);
    var n = document.getElementById('pvp-selected');
    if (n) n.textContent = total;
    estimate = null;
  }
  function lobbyHtml() {
    var available = lobby.available, locked = active(), total = Object.keys(config.army).reduce(function (sum, k) { return sum + (Number(config.army[k]) || 0); }, 0);
    var h = '<div class="pvp-record"><div class="pvp-record-badges"><span class="pvp-tag-win">胜 ' + lobby.record.wins + '</span><span class="pvp-tag-loss">负 ' + lobby.record.losses + '</span><span class="pvp-tag-draw">平 ' + lobby.record.draws + '</span></div><small class="pvp-record-note">普通匹配，无公开段位与奖励</small></div>';
    if (locked) return h + '<button class="btn ok" onclick="Game.Pvp.resume()">返回演习战场</button>';
    h += '<div class="pvp-form">' +
      '<label class="pvp-form-item"><span class="pvp-form-label">战场类别</span><select onchange="Game.Pvp.setField(this.value)"><option value="LAND_AIR"' + (config.battlefield === 'LAND_AIR' ? ' selected' : '') + '>陆空战</option><option value="SEA_AIR"' + (config.battlefield === 'SEA_AIR' ? ' selected' : '') + '>海空战</option></select></label>' +
      '<label class="pvp-form-item"><span class="pvp-form-label">来源城市</span><select onchange="Game.Pvp.setCity(this.value)">';
    available.cities.forEach(function (c) { h += '<option value="' + c.slot + '"' + (config.citySlot === c.slot ? ' selected' : '') + '>' + esc(c.name) + '</option>'; });
    h += '</select></label>' +
      '<label class="pvp-form-item pvp-form-officer"><span class="pvp-form-label">带兵将领</span><select onchange="Game.Pvp.setCommander(this.value)"><option value="">不带将领</option>';
    available.officers.forEach(function (o) { h += '<option value="' + o.id + '"' + (config.commanderId === o.id ? ' selected' : '') + '>' + esc(o.name) + ' · 军事 ' + o.attributes.military + ' / 防御 ' + o.attributes.defense + '</option>'; });
    h += '</select></label></div>';
    h += '<div class="pvp-cap-bar">' +
      '<div class="pvp-cap-stats"><span class="pvp-cap-title">本城带兵上限</span> <span class="pvp-cap-nums">' + available.cap + ' · 已选 <b id="pvp-selected">' + total + '</b></span></div>' +
      '<div class="pvp-cap-chief">司令官 <b>' + esc(available.chief || '未任命') + '</b></div>' +
      '<div class="pvp-cap-tools">' +
        '<button type="button" class="pvp-btn-tool" onclick="Game.Pvp.fillMax()" title="按本城各兵种可用比例填满上限">填满上限</button>' +
        '<button type="button" class="pvp-btn-tool" onclick="Game.Pvp.clearArmy()" title="参战数量全部归零">清空</button>' +
      '</div>' +
    '</div>';
    h += '<div class="pvp-table-wrap"><table class="pvp-table"><thead><tr><th class="col-unit">演习编队</th><th class="col-avail">本城可用</th><th class="col-count">参战数量</th><th class="col-action">默认战术</th></tr></thead><tbody>';
    Object.keys(G.DATA.units).forEach(function (k) {
      var u = unit(k); if (u.branch !== 'air' && u.branch !== (config.battlefield === 'SEA_AIR' ? 'sea' : 'land')) return;
      h += '<tr><td class="col-unit" title="' + esc(name(k)) + '">' + esc(name(k)) + '</td><td class="col-avail">' + (available.army[k] || 0) + '</td><td class="col-count"><input id="pvp-count-' + esc(k) + '" type="number" min="0" max="' + (available.army[k] || 0) + '" value="' + (config.army[k] || 0) + '" oninput="Game.Pvp.setCount(\'' + k + '\',this.value)"></td><td class="col-action">' + actionSelect('preset-' + k, config.defaults[k] && config.defaults[k].action || 'ADVANCE', 'Game.Pvp.setDefault(\'' + k + '\',this.value)', false) + '</td></tr>';
    });
    h += '</tbody></table></div><div class="pvp-actions"><button class="btn ok" onclick="Game.Pvp.queue()"' + disabled() + '>开始匹配</button><button class="btn" onclick="Game.Pvp.savePreset()"' + disabled() + '>保存编队 / 查看实力</button></div>';
    if (estimate) h += summary(estimate);
    if (state && state.cooldownUntil > now()) h += '<p>连续未确认对局，冷却至 ' + esc(new Date(G.GameTime ? G.GameTime.toWallTime(state.cooldownUntil) : state.cooldownUntil).toLocaleTimeString()) + '</p>';
    h += '<p class="pvp-muted">匹配前会重新核验并复制当前驻军、科技、配装和将领。等待时间暂无法估计。</p><h3>最近演习战报</h3>';
    if (!lobby.reports.length) h += '<p class="pvp-muted">尚无演习记录</p>';
    lobby.reports.forEach(function (r) { h += '<button class="pvp-history" onclick="Game.Pvp.report(\'' + r.sessionId + '\')">' + esc(resultNames[r.result]) + '<span>' + esc(new Date(r.createdAt).toLocaleString()) + ' →</span></button>'; });
    h += '<button class="btn" onclick="Game.Pvp.history()">查看最近50场</button>';
    return h;
  }
  function queueHtml() {
    var accepting = state.status === 'ACCEPTING';
    var e = state.waitEstimate, wait = e && e.available ? '近期等待中位数 ' + clock(e.medianMs) + '，中间50%区间 ' + clock(e.lowerMs) + '～' + clock(e.upperMs) : '等待时间暂无法估计';
    var isSea = state.snapshot && state.snapshot.battlefield === 'SEA_AIR';
    var fieldTag = isSea ? '海空演习' : '陆空演习';
    var h = '<div class="pvp-match' + (accepting ? ' pvp-match-locked' : ' pvp-match-searching') + '">';

    // 战术雷达视界
    h += '<div class="pvp-radar-viewport" aria-hidden="true">';
    h += '<div class="pvp-radar-disc">';
    h += '<div class="pvp-radar-grid">';
    h += '<div class="pvp-radar-ring ring-1"></div><div class="pvp-radar-ring ring-2"></div><div class="pvp-radar-ring ring-3"></div>';
    h += '<div class="pvp-radar-crosshair-h"></div><div class="pvp-radar-crosshair-v"></div>';
    h += '</div>';
    h += '<div class="pvp-radar-sweep"></div>';
    h += '<div class="pvp-radar-wave wave-1"></div><div class="pvp-radar-wave wave-2"></div>';
    h += '<div class="pvp-radar-blip blip-1"></div><div class="pvp-radar-blip blip-2"></div><div class="pvp-radar-blip blip-3"></div>';
    h += '<div class="pvp-radar-hub">';
    h += '<div class="pvp-radar-core"><span class="pvp-radar-tag">1 v 1</span><span class="pvp-radar-mode">' + fieldTag + '</span></div>';
    h += '</div>';
    h += '</div>';
    h += '</div>';

    // 搜索状态标题与信号波动指示条
    h += '<div class="pvp-match-status-wrap">';
    h += '<div class="pvp-signal-bars" aria-hidden="true"><span class="bar bar-1"></span><span class="bar bar-2"></span><span class="bar bar-3"></span><span class="bar bar-4"></span></div>';
    h += '<h3 class="pvp-match-title">' + (accepting ? '目标已锁定 · 匹配成功' : '正在寻找旗鼓相当的对手') + '</h3>';
    h += '</div>';

    // 战术计时器 HUD
    h += '<div class="pvp-timer-hud">';
    h += '<span class="pvp-timer-label">' + (accepting ? '确认倒计时' : '匹配用时') + '</span>';
    h += '<div id="pvp-clock" class="pvp-clock">' + (accepting ? clock(state.deadlineAt - now()) : clock(now() - state.queuedAt)) + '</div>';
    h += '</div>';

    // 动态搜索阶段提示与说明
    h += '<p id="pvp-search-hint" class="pvp-search-hint">' + (accepting ? '实力相近的对手 · ' + (isSea ? '海空战' : '陆空战') : '正在寻找旗鼓相当的对手') + '</p>';
    h += '<div class="pvp-search-footnote">仅匹配同大区主动排队的真人统帅 · ' + esc(wait) + '</div>';

    // 操作按钮
    h += '<div class="pvp-match-actions">';
    if (accepting) {
      h += '<button class="btn ok pvp-btn-accept" onclick="Game.Pvp.accept()"' + (busy || pending || state.accepted ? ' disabled' : '') + '>' + (state.accepted ? '已接受 · 等待对手入场' : '立即接受对局') + '</button>';
    }
    h += '<button class="btn pvp-btn-cancel" onclick="Game.Pvp.cancel()"' + disabled() + '>' + (accepting ? '退出确认' : '取消匹配') + '</button>';
    h += '</div>';

    // 出战战备快照档案
    if (state.snapshot) h += summary(state.snapshot);
    h += '</div>';
    return h;
  }
  function actionSelect(k, value, callback, locked) {
    return '<select id="pvp-action-' + esc(k) + '" onchange="' + callback + '"' + (locked ? ' disabled' : '') + '>' + Object.keys(actions).map(function (a) { return '<option value="' + a + '"' + (value === a ? ' selected' : '') + '>' + actions[a] + '</option>'; }).join('') + '</select>';
  }
  function battleHtml() {
    var b = battle, enabled = b.status === 'BATTLING' && !b.locked && !b.auto && !pending && now() < b.deadlineAt;
    var phase = b.status === 'LOADING' ? '等待双方进入战场' : b.status === 'COUNTDOWN' ? '入场倒计时' : b.status === 'RECOVERING' ? '正在恢复已到期回合' : '第 ' + b.round + ' / 24 回合';
    var h = '<div class="pvp-battle-head"><strong>' + esc(b.mine.playerName) + '　VS　' + esc(b.enemy.playerName) + '</strong><span>' + phase + ' · <b id="pvp-clock"></b></span></div>';
    h += '<div class="pvp-battle-sub"><span>我方带兵：' + esc(b.mine.commanderName) + '</span><span>敌方带兵：' + esc(b.enemy.commanderName) + '</span></div>';
    h += G.Battle.renderCommandBonusPanel({ attacker: b.mine.chief, defender: b.enemy.chief }, 'attacker');
    h += '<p class="pvp-rule">回合耗尽时按敌方初始兵力价值损失比例判定；差距≤5个百分点为平局。实际兵力损失：0。</p>';
    G.Battle._activeTactical = { attackerStats: b.attackerStats, defenderStats: b.defenderStats,
      attackerTech: b.mine.tech, defenderTech: b.enemy.tech };
    var rows = G.Battle.tacticalMarkerRows(b.myArmy, b.enemyArmy), indexes = {};
    rows.forEach(function (k, i) { indexes[k] = i; });
    h += '<div class="tactical-map pvp-map" style="height:' + (180 + Math.max(0, rows.length - 1) * 70) + 'px"><div class="pvp-baseline mine">我方底线</div><div class="pvp-baseline foe">敌方底线</div>';
    h += G.Battle.renderTacticalRangeBeams(b.myArmy, b.myPositions, b.enemyArmy, b.enemyPositions, b.initialDistance, indexes, b.mine.tech, b.enemy.tech, false);
    h += G.Battle.renderTacticalMarkers(b.myArmy, b.myPositions, b.initialDistance, 'mine', indexes, b.enemyArmy, b.enemyPositions, b.mine.tech, false);
    h += G.Battle.renderTacticalMarkers(b.enemyArmy, b.enemyPositions, b.initialDistance, 'foe', indexes, b.myArmy, b.myPositions, b.enemy.tech, false) + '</div>';
    h += '<div class="pvp-readiness"><span>' + (b.locked ? '我方已锁定' : b.auto ? '默认托管' : '我方指挥中') + '</span><span>' + (b.enemyReady ? '对手已准备' : '对手指挥中') + '</span></div>';
    if (b.status === 'LOADING') h += '<p>' + (b.loaded ? '指挥面板已就绪，等待对手进入' : '正在报告面板就绪') + '</p>';
    h += '<div class="pvp-table-wrap"><table class="pvp-table"><thead><tr><th class="col-battle-unit">我方兵种</th><th class="col-battle-remain">虚拟剩余</th><th class="col-battle-action">机动指令</th><th class="col-battle-focus">指定集火</th></tr></thead><tbody>';
    Object.keys(b.myArmy).forEach(function (k) {
      var o = drafts[k] || b.mine.defaults[k] || { action: 'ADVANCE' };
      h += '<tr><td class="col-battle-unit" title="' + esc(name(k)) + '">' + esc(name(k)) + '</td><td class="col-battle-remain">' + b.myArmy[k] + '</td><td class="col-battle-action">' + actionSelect(k, o.action, 'Game.Pvp.setOrder(\'' + k + '\',\'action\',this.value)', !enabled) + '</td><td class="col-battle-focus"><select id="pvp-focus-' + k + '" onchange="Game.Pvp.setOrder(\'' + k + '\',\'focus\',this.value)"' + (!enabled ? ' disabled' : '') + '><option value="">自动索敌</option>';
      Object.keys(b.enemyArmy).forEach(function (target) { h += '<option value="' + target + '"' + (o.focusTarget === target ? ' selected' : '') + '>' + esc(name(target)) + '</option>'; });
      h += '</select></td></tr>';
    });
    h += '</tbody></table></div><div class="pvp-actions"><button id="pvp-lock" class="btn ok" onclick="Game.Pvp.lock()">锁定本回合指令</button><button class="btn" onclick="Game.Pvp.auto()"' + (busy || pending || b.status !== 'BATTLING' || b.pendingTakeover ? ' disabled' : '') + '>' + (b.pendingTakeover ? '下一回合接管' : b.auto ? '接管下一回合' : '进入默认托管') + '</button><button class="btn danger" onclick="Game.Pvp.confirmSurrender()"' + disabled() + '>认输</button></div>';
    h += '<p class="pvp-muted">指令只作用于本回合。单方锁定不推进、不公开命令；离开页面后按最后保存草稿或默认战术继续。</p>';
    return h;
  }
  function pct(v) { return (100 * (Number(v) || 0)).toFixed(1) + '%'; }
  function reportHtml() {
    var r = report, h = '<div class="pvp-result"><h3>' + esc(resultNames[r.result] || r.result) + '</h3><p>' + esc(reasons[r.reason] || r.reason || '') + '</p><strong>实际兵力损失：0</strong><p>虚拟战果：我方造成 ' + pct(r.enemyLoss) + ' · 敌方造成 ' + pct(r.myLoss) + '</p><p>' + (r.round || 0) + ' 回合 · 用时 ' + clock(r.settledAt - r.createdAt) + '</p>';
    h += '<div class="pvp-actions"><button class="btn ok" onclick="Game.Pvp.again()">再次匹配</button><button class="btn" onclick="Game.Pvp.backLobby()">调整编队</button></div></div>';
    if (r.mine) h += summary(r.mine) + '<p>虚拟剩余：' + Object.keys(r.myArmy || {}).map(function (k) { return esc(name(k)) + ' × ' + r.myArmy[k]; }).join('　') + '</p>';
    if (r.enemy) h += '<details><summary>双方参战科技、配装与有效属性快照</summary><pre class="pvp-json">' + esc(JSON.stringify({ mine: r.mine, enemy: r.enemy }, null, 2)) + '</pre></details>';
    h += '<h3>逐回合复盘</h3><p class="pvp-muted">将领军事/防御与战斗技能在首次交战E生效，之后间隔两回合再触发（E、E+3、E+6…）；司令官属性常驻。效果来自引擎事件，不表示技能胜率贡献。</p>';
    (r.rounds || []).forEach(function (round) {
      var mineA = r.side === 'A', ownOrders = mineA ? round.ordersA : round.ordersB, enemyOrders = mineA ? round.ordersB : round.ordersA;
      h += '<details class="pvp-round"><summary>第 ' + round.round + ' 回合 · 首次交战 E=' + round.after.firstCombatRound + '</summary><p>我方执行：' + orderText(ownOrders, mineA ? round.sourcesA : round.sourcesB) + '</p><p>敌方执行：' + orderText(enemyOrders, mineA ? round.sourcesB : round.sourcesA) + '</p><div class="pvp-events">';
      round.events.forEach(function (e) { h += eventHtml(e, r.side); });
      h += '</div></details>';
    });
    return h;
  }
  function orderText(orders, sources) { return Object.keys(orders || {}).map(function (k) { var o = orders[k]; return esc(name(k)) + ' ' + esc(actions[o.action]) + (o.focusTarget ? ' → ' + esc(name(o.focusTarget)) : '') + ' [' + esc({ MANUAL: '人工锁定', DRAFT: '已保存草稿', AUTO: '默认托管', DEFAULT: '冻结默认' }[sources[k]] || sources[k]) + ']'; }).join('；'); }
  function eventHtml(e, side) {
    var p = e.side === side ? '我方' : '敌方', label = p + ' ' + (e.unit ? name(e.unit) : '将领');
    if (e.type === 'SKILL_EFFECT') return '<p>' + esc(label + '：' + ((G.DATA.officerSkills[e.skill] || {}).name || e.skill) + ' 生效 · ' + Math.round(e.value * 100) / 100) + '</p>';
    if (e.type === 'RICOCHET') return '<p>' + esc(label + ' 跳弹射击 → ' + name(e.target) + ' · 伤害 ' + Math.round(e.damage) + ' · 击毁 ' + e.kills) + '</p>';
    if (e.type === 'SKILL_WINDOW') return '<p>' + esc(p + ' ' + ((G.DATA.officerSkills[e.skill] || {}).name || e.skill) + ' Lv.' + e.level + '：' + (reasons[e.reason] || e.reason)) + '</p>';
    if (e.type === 'OFFICER_ATTRIBUTES') return '<p>' + esc(p + ' 将领周期属性：' + (e.active ? '生效' : '本回合不生效') + ' · 军事 ' + e.military + ' / 防御 ' + e.defense) + '</p>';
    if (e.type === 'MOVEMENT') return '<p>' + esc(label + ' 机动：' + e.from + ' → ' + e.to + ' · 速度 ' + Math.round(e.speed || 0) + ' · 闪电突击×' + (e.blitzMultiplier || 1)) + '</p>';
    if (e.type === 'INITIATIVE') return '<p>' + esc(label + ' 先手序号 ' + e.order + ' · 射程 ' + e.range + ' · 速度 ' + Math.round(e.speed) + ' · 闪电突击×' + e.blitzMultiplier) + '</p>';
    if (e.type === 'NO_FIRE') return '<p>' + esc(label + '：' + reasons[e.reason]) + '</p>';
    if (e.type === 'NO_COUNTER') return '<p>' + esc(label + ' 反击未生效：' + reasons[e.reason]) + '</p>';
    if (e.type === 'COUNTER') return '<p>' + esc(label + ' 反击 ' + name(e.target) + ' · 火力密度×' + (e.firepowerMultiplier || 1) + ' · 伤害 ' + Math.round(e.damage) + ' · 击毁 ' + e.kills) + '</p>';
    return '<p>' + esc(label + ' → ' + name(e.target) + ' · 距离 ' + e.distance + '/射程 ' + e.range + ' · 伤害 ' + Math.round(e.damage) + ' · 击毁 ' + e.kills) + '</p><small>'
      + esc('火力密度×' + (e.firepowerMultiplier || 1) + ' · 冲锋×' + e.frenzyMultiplier + ' · 压制 ' + pct(e.suppression) + ' · 破甲 ' + pct(e.pierce) + ' · 坚守×' + e.bulwarkMultiplier + ' · 学习攻击+' + Math.round(e.learnedAttack) + '（' + reasons[e.learnReason] + '） · 借甲防御+' + Math.round(e.borrowedDefense) + '（' + reasons[e.armorReason] + '）') + '</small>';
  }
  function history() {
    Promise.all([0, 1, 2, 3, 4].map(function (page) { return request('GET', 'reports?page=' + page); })).then(function (pages) { lobby.reports = [].concat.apply([], pages); show(); }).catch(function (e) { error = e.message; show(); });
  }
  G.Pvp = { start: start, render: render, resume: function () { recover().then(view); }, retry: retry, queue: queue, accept: accept, cancel: cancel,
    setField: setField, setCity: setCity, setCount: function (k, v) { setCount(k, v); var n = document.getElementById('pvp-selected'); if (n) n.textContent = Object.values(config.army).reduce(function (a, b) { return a + (Number(b) || 0); }, 0); },
    clearArmy: clearArmy, fillMax: fillMax,
    setDefault: setDefault, setCommander: function (v) { config.commanderId = v ? Number(v) : null; estimate = null; }, savePreset: savePreset,
    setOrder: setOrder, lock: function () { saveOrders(true); }, auto: auto, report: function (s) { loadReport(s, true); }, history: history, backLobby: backLobby,
    again: function () { backLobby().then(queue); }, confirmSurrender: function () {
      if (G.confirmDialog) G.confirmDialog('确认认输？本局记为演习负局，实际兵力不会损失。', surrender);
      else if (window.confirm('确认认输？实际兵力不会损失。')) surrender();
    }
  };
  Core.views.pvp = render;
  if (G.WS) {
    ['pvpQueueChanged', 'pvpMatchFound', 'pvpAccepted', 'pvpBattleReady', 'pvpRoundResolved', 'pvpFinished'].forEach(function (event) {
      G.WS.on(event, function (s) { if (!context || key() !== context || s.region !== G.API.client.serverId || state && s.version < state.version) return; recover(); });
    });
    G.WS.on('connected', function () { start(); });
    G.WS.on('incoming', function () { if (active()) { notice = '真实世界有部队来袭，可前往军情处理；演习继续默认执行。'; show(); } });
  }
})(window.Game);
