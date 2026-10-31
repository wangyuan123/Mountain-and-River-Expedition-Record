const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function setup() {
  let now = 1000;
  const timers = new Map();
  let timerCount = 0;
  let focused = '';
  let removed = 0;
  const view = { innerHTML: '' };
  const countdowns = { tacticalCountdown: {}, tacticalUnitCommandCountdown: {} };
  const document = {
    activeElement: null,
    body: { appendChild() {} },
    getElementById(id) {
      if (countdowns[id]) return countdowns[id];
      if ((id.startsWith('tacticalTab-') || id === 'tacticalLogToggle') && view.innerHTML.includes('id="' + id + '"')) {
        return { focus() { focused = id; } };
      }
      if (id === 'tacticalUnitCommand' && view.innerHTML.includes('id="tacticalUnitCommand"')) {
        return { querySelector: () => ({ focus() { focused = 'popup'; } }), remove() { removed++; } };
      }
      return null;
    },
    querySelectorAll() {
      return ['rocket~a', 'rocket~b', 'truck'].map((id) => ({
        getAttribute: () => id,
        focus() { focused = id; }
      }));
    }
  };
  const Game = {
    Core: { route: 'battle', state: {}, views: {}, firepowerMul: () => 1, render() { Game.Battle.renderTacticalBattle(view); }, go(route) { this.route = route; this.render(); } },
    DATA: {
      units: { rocket: { name: '火箭车', range: 1600, spd: 5 }, truck: { name: '卡车', range: 0, spd: 8 }, htank: { name: '重型坦克', range: 320, spd: 6 } },
      forts: { howitzer: { name: '榴弹炮', range: 1850, cat: 'fort' } }
    },
    escapeHtml, fmt: String, toast() {},
    getUnitModelIconHtml: (id) => '<span>' + id + '</span>'
  };
  const context = vm.createContext({
    Game, window: { Game }, document,
    Date: class extends Date { static now() { return now; } },
    setInterval(fn) { timers.set(++timerCount, fn); return timerCount; }, clearInterval(id) { timers.delete(id); }
  });
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/battle.js'), 'utf8'), context);
  Game.Battle._activeTactical = {
    marchId: 42, round: 2, side: 'attacker', action: 'conquer', initialDistance: 3000,
    attackerArmy: { 'rocket~a': 10, 'rocket~b': 20, truck: 5 }, defenderArmy: { htank: 8, truck: 0 },
    attackerPositions: { 'rocket~a': 0, 'rocket~b': 0 }, defenderPositions: { htank: 1500 },
    attackerStats:{'rocket~a':{range:1600,speed:5},'rocket~b':{range:1600,speed:5}}, defaultActions: { rocket: 'HOLD', truck: 'ADVANCE' }, orders: {}, roundDeadlineAt: 16000, log: ''
  };
  Game.Battle.resetTacticalOrders();
  Game.Core.render();
  function run(attribute, value) {
    const code = attribute.replaceAll('&quot;', '"').replaceAll('&#39;', "'").replaceAll('&amp;', '&');
    vm.runInNewContext('(function(){' + code + '}).call({value})', { Game, value });
  }
  return { Game, view, document, countdowns, run, tick() { for (const fn of [...timers.values()]) fn(); }, setNow(value) { now = value; }, focused: () => focused, removed: () => removed, timers: () => timers.size, timerCount: () => timerCount };
}

function popupHtml(view) {
  return view.innerHTML.slice(view.innerHTML.indexOf('id="tacticalUnitCommand"'));
}

test('协防面板按来源显示独立军官，同兵种命令与来源筛选互不影响', () => {
  const { Game, view } = setup();
  Game.Battle._activeTactical.unitContexts = {
    'rocket~a': { title: '【本城·守将张某】', military: 100, defense: 80, firstCombatRound: 2, skills: {} },
    'rocket~b': { title: '【援军·玩家A·将领李某】', military: 20, defense: 30, firstCombatRound: 0, skills: {} }
  };
  Game.Battle._activeTactical.formationEvents = [
    { type: 'MOVEMENT', round: 2, formationId: 'formation-1', formation: '【本城·守将张某】', unit: 'rocket', countBefore: 10, from: 0, to: 300 },
    { type: 'FIRE', round: 2, batch: 1, formationId: 'formation-2', formation: '【援军·玩家A·将领李某】', unit: 'rocket', countBefore: 20, target: '敌军', targetUnit: 'htank', damage: 12 }
  ];
  Game.Battle._tacticalCommandView = 'text'; Game.Core.render();
  assert.match(view.innerHTML, /参战来源与独立加成（2）/);
  assert.match(view.innerHTML, /将领军事 100／防御 80/); assert.match(view.innerHTML, /将领军事 20／防御 30/);
  const before = JSON.stringify(Game.Battle._tacticalOrders);
  Game.Battle._formationLogSource = 'formation-2';
  const filtered = Game.Battle.renderFormationEvents(Game.Battle._activeTactical.formationEvents);
  assert.match(filtered, /攻击 敌军/); assert.doesNotMatch(filtered, /移动300/);
  assert.equal(JSON.stringify(Game.Battle._tacticalOrders), before);
});

test('同名编队保持独立标识，显示贡献合计取已展示分项之和', () => {
  const { Game } = setup();
  const rows = ['formation-1', 'formation-2'].map(id => ({ formationId: id, formation: '同名将领', kind: 'support', unit: 'rocket',
    initial: 10, survivors: 7, losses: 3, wounded: 2, damage: 1.6 }));
  const html = Game.Battle.renderFormationContributions(rows);
  assert.match(html, /formation-1/); assert.match(html, /formation-2/);
  assert.match(html, /守方合计<\/th><td>20<\/td><td>3.2<\/td><td>6<\/td><td>4<\/td><td>14/);
});

test('贡献小数按伤害类型统一分配尾差，拆组不增伤且不改写服务端数据', () => {
  const { Game } = setup();
  const rows = ['formation-1', 'formation-2'].map(id => ({ formationId: id, kind: 'support', unit: 'rocket', damage: .018,
    unitDamage: .006, wallDamage: .006, fortDamage: .006 }));
  const before = JSON.stringify(rows), displayed = Game.Battle.formationDamageDisplay(rows);
  for (const key of ['unitDamage', 'wallDamage', 'fortDamage']) assert.equal(displayed.reduce((n, row) => n + Math.round(row[key] * 100), 0), 1);
  assert.equal(displayed.reduce((n, row) => n + Math.round(row.damage * 100), 0), 3);
  assert.equal(JSON.stringify(rows), before);
});

test('后台已结束的战斗通过只读请求恢复终局，停止旧回合重试', async () => {
  const { Game, view, timers } = setup();
  const final = { ...Game.Battle._activeTactical, round: 16, finished: true,
    attackerArmy: { rocket: 25 }, wall: { level: 10, hpRemaining: 0, maxHp: 2500000 },
    terminalReason: 'WALL_BREACHED', result: { win: true }, log: '最终回合' };
  let commands = 0, reads = 0;
  Game.API = {
    commandTacticalBattle() { commands++; return Promise.reject(Object.assign(new Error('当前行军没有可指挥的战斗'), { status: 400 })); },
    getTacticalBattle() { reads++; return Promise.resolve(final); }
  };
  await Game.Battle.executeTacticalRound();
  assert.equal(Game.Battle._activeTactical, final);
  assert.match(view.innerHTML, /征服大捷/);
  assert.doesNotMatch(view.innerHTML, /重试第|请手动重试/);
  assert.equal(timers(), 0);
  await Game.Battle.executeTacticalRound();
  assert.equal(commands, 1);
  assert.equal(reads, 1);
});

test('不可恢复的历史战斗提供战报入口，不再重放命令或伪造胜负', async () => {
  const { Game, view, timers } = setup();
  let commands = 0;
  Game.API = {
    commandTacticalBattle() { commands++; return Promise.reject(Object.assign(new Error('行军任务不存在'), { status: 400 })); },
    getTacticalBattle() { return Promise.reject(new Error('行军任务不存在')); }
  };
  await Game.Battle.executeTacticalRound();
  assert.match(view.innerHTML, /查看战报确认最终战果/);
  assert.doesNotMatch(view.innerHTML, /重试第|请手动重试/);
  assert.equal(Game.Battle._activeTactical.finished, undefined);
  assert.equal(timers(), 0);
  await Game.Battle.executeTacticalRound();
  assert.equal(commands, 1);
});

test('实时战场记录沿用战报的伤害和连击文案，保留服务端原始日志', () => {
  const { Game, view } = setup();
  const raw = '我方火箭车(100)齐射敌重型坦克(1) 对地攻击200 本次原始火力20000 伤害1000 击毁1 剩余攻击额度40% 有效射程1600 目标距离300；余伤攻击敌步兵(20) 对地攻击200 伤害600 击毁10 剩余攻击额度0% 有效射程1600 目标距离500';
  Game.Battle._activeTactical.log = raw;
  Game.Core.render();
  const log = view.innerHTML.split('<div class="blog tactical-log">')[1];
  assert.match(log, /齐射敌方重型坦克，造成1000点伤害，击毁1，目标距离300；势不可挡，继续攻击敌方步兵，造成600点伤害，击毁10，目标距离500/);
  assert.doesNotMatch(log, /对地攻击|原始火力|剩余攻击额度|有效射程/);
  assert.equal(Game.Battle._activeTactical.log, raw);
});

test('点击战场装备组打开同兵种快捷指挥，展示统一兵力、默认命令和集火距离', () => {
  const { Game, view, run, focused } = setup();
  const marker = view.innerHTML.match(/data-tactical-command-unit="rocket~b"[^>]+/)[0];
  run(marker.match(/onclick="([^"]+)"/)[1]);
  const popup = popupHtml(view);
  assert.equal(Game.Battle._tacticalCommandUnitId, 'rocket');
  assert.match(popup, /role="dialog" aria-modal="true"/);
  assert.match(popup, /×30/);
  assert.match(popup, /aria-pressed="true"[^>]*>待命/);
  assert.match(popup, /集火 <select/);
  assert.match(popup, /重型坦克 \[🎯可开火 距1500\]/);
  assert.equal((popup.match(/class="tactical-order-card"/g) || []).length, 1);
  assert.equal(Game.Battle._tacticalOrders.rocket.action, null, '打开弹窗不生成命令');
  assert.equal(focused(), 'popup');
});

test('敌我不同兵种同坐标时标记也留出间距且我军图标仍可打开指挥弹窗', () => {
  const { Game, view, run } = setup();
  const battle = Game.Battle._activeTactical;
  battle.attackerArmy = { rocket: 10 };
  battle.defenderArmy = { htank: 8 };
  battle.attackerPositions = { rocket: 1500 };
  battle.defenderPositions = { htank: 1500 };
  Game.Battle.resetTacticalOrders();
  Game.Core.render();

  const mineMarker = view.innerHTML.match(/<div class="tactical-marker mine" data-unit-id="rocket"[^>]+>/)[0];
  const foeMarker = view.innerHTML.match(/<div class="tactical-marker foe" data-unit-id="htank"[^>]+>/)[0];
  assert.match(mineMarker, /left:min\([^;]+ - var\(--tactical-icon-half, 11.25px\) - 7.5px\)\)/);
  assert.match(foeMarker, /left:max\([^;]+ \+ var\(--tactical-icon-half, 11.25px\) \+ 7.5px\)\)/);
  assert.match(mineMarker, /data-tactical-command-unit="rocket"/);
  assert.doesNotMatch(foeMarker, /data-tactical-command-unit=/);

  run(mineMarker.match(/onclick="([^"]+)"/)[1]);
  assert.equal(Game.Battle._tacticalCommandUnitId, 'rocket');
  assert.match(view.innerHTML, /id="tacticalUnitCommand"/);
});

test('快捷前进、后退、待命分别更新同一份命令，保留集火并返回所点图标', () => {
  for (const [action, label] of [['ADVANCE', '前进'], ['RETREAT', '后退'], ['HOLD', '待命']]) {
    const { Game, view, run, focused } = setup();
    Game.Battle._tacticalOrders.rocket.focusTarget = 'htank';
    Game.Battle.openTacticalUnitCommand('rocket~b');
    const button = popupHtml(view).match(new RegExp('<button[^>]+onclick="([^"]+)"[^>]*>' + label))[1];
    run(button);
    assert.equal(Game.Battle._tacticalOrders.rocket.action, action);
    assert.equal(Game.Battle._tacticalOrders.rocket.focusTarget, 'htank');
    assert.equal(Game.Battle._tacticalOrders.truck.action, null);
    assert.equal(Game.Battle._tacticalCommandUnitId, null);
    assert.doesNotMatch(view.innerHTML, /id="tacticalUnitCommand"/);
    assert.match(view.innerHTML, new RegExp('tactical-marker-range[^>]*>[^<]* · ' + label + ' · 集火重型坦克'));
    assert.equal(focused(), 'rocket~b');
  }
});

test('快捷集火沿用默认移动命令，可以重新打开查看、清除目标，超射程提示与下方一致', () => {
  const { Game, view, run } = setup();
  Game.Battle._activeTactical.defenderPositions.htank = 2000;
  Game.Battle.openTacticalUnitCommand('rocket~a');
  assert.match(popupHtml(view), /超出射程 距2000/);
  run(popupHtml(view).match(/onchange="([^"]+)"/)[1], 'htank');
  assert.equal(Game.Battle._tacticalOrders.rocket.action, 'HOLD');
  assert.equal(Game.Battle._tacticalOrders.rocket.focusTarget, 'htank');
  assert.equal(Game.Battle._tacticalCommandUnitId, null);
  Game.Battle.openTacticalUnitCommand('rocket~a');
  assert.match(popupHtml(view), /option value="htank" selected/);
  run(popupHtml(view).match(/onchange="([^"]+)"/)[1], '');
  assert.equal(Game.Battle._tacticalOrders.rocket.focusTarget, null);
  assert.equal(Game.Battle._tacticalOrders.rocket.action, 'HOLD');
});

test('敌军、城墙、阵亡单位和结算期间不提供快捷指挥，防守视角仍指挥我军', () => {
  const { Game, view } = setup();
  const Battle = Game.Battle;
  assert.equal(Battle.tacticalCommandAttributes('htank', 'foe'), '');
  for (const id of ['wall', 'htank', 'missing']) {
    Battle.openTacticalUnitCommand(id);
    assert.equal(Battle._tacticalCommandUnitId, null);
  }
  Battle._activeTactical.side = 'defender';
  Battle.openTacticalUnitCommand('rocket~a');
  assert.match(popupHtml(view), /兵种指挥/);
  Battle.closeTacticalUnitCommand();
  for (const state of ['finished', '_tacticalSubmitting']) {
    const owner = state === 'finished' ? Battle._activeTactical : Battle;
    owner[state] = true;
    if (state === 'finished') assert.equal(Battle.tacticalCommandAttributes('rocket~a', 'mine'), '');
    Battle.openTacticalUnitCommand('rocket~a');
    assert.equal(Battle._tacticalCommandUnitId, null);
    owner[state] = false;
  }
  Battle._activeTactical.attackerArmy['rocket~a'] = 0;
  Battle._activeTactical.attackerArmy['rocket~b'] = 0;
  Battle.openTacticalUnitCommand('rocket~a');
  assert.equal(Battle._tacticalCommandUnitId, null);
});

test('Escape 关闭不修改命令，Tab 与 Shift+Tab 在弹窗内循环', () => {
  const { Game, document, view } = setup();
  const before = JSON.stringify(Game.Battle._tacticalOrders);
  Game.Battle.openTacticalUnitCommand('rocket~a');
  let focus = '';
  let prevented = 0;
  const first = { focus() { focus = 'first'; } };
  const last = { focus() { focus = 'last'; } };
  const event = { key: 'Tab', shiftKey: false, currentTarget: { querySelectorAll: () => [first, last] }, stopPropagation() {}, preventDefault() { prevented++; } };
  document.activeElement = last;
  Game.Battle.onTacticalCommandKeydown(event);
  assert.equal(focus, 'first');
  event.shiftKey = true;
  document.activeElement = first;
  Game.Battle.onTacticalCommandKeydown(event);
  assert.equal(focus, 'last');
  event.key = 'Escape';
  Game.Battle.onTacticalCommandKeydown(event);
  assert.equal(prevented, 3);
  assert.equal(JSON.stringify(Game.Battle._tacticalOrders), before);
  assert.doesNotMatch(view.innerHTML, /id="tacticalUnitCommand"/);
});

test('弹窗与面板共用截止时间，到时关闭弹窗并只提交一回合', async () => {
  const { Game, view, countdowns, setNow, tick, removed } = setup();
  const calls = [];
  let resolveRound;
  Game.API = { commandTacticalBattle(marchId, orders, round) {
    calls.push({ marchId, round, orders: JSON.parse(JSON.stringify(orders)) });
    return new Promise((resolve) => { resolveRound = resolve; });
  } };
  Game.Battle.setTacticalAction('rocket', 'RETREAT');
  Game.Battle.openTacticalUnitCommand('rocket~a');
  setNow(5000);
  tick();
  assert.equal(countdowns.tacticalCountdown.textContent, '11秒');
  assert.equal(countdowns.tacticalUnitCommandCountdown.textContent, '11秒');
  setNow(16000);
  tick();
  assert.equal(removed(), 1);
  assert.equal(Game.Battle._tacticalCommandUnitId, null);
  Game.Battle.openTacticalUnitCommand('rocket~a');
  Game.Battle.executeTacticalRound();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].marchId, 42);
  assert.equal(calls[0].round, 2);
  assert.equal(calls[0].orders.rocket.action, 'RETREAT');
  resolveRound({ ...Game.Battle._activeTactical, round: 3, roundDeadlineAt: 31000, orders: calls[0].orders });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(Game.Battle._tacticalOrders.rocket.action, 'RETREAT');
  assert.doesNotMatch(view.innerHTML, /id="tacticalUnitCommand"/);
  assert.match(view.innerHTML, /data-tactical-command-unit="rocket~a"/);
  Game.Battle.openTacticalUnitCommand('rocket~a');
  assert.match(view.innerHTML, /id="tacticalUnitCommand"/);
});

test('快捷弹窗遵守城墙集火阶段限制，已消失兵种的弹窗随重绘清除', () => {
  const { Game, view } = setup();
  const battle = Game.Battle._activeTactical;
  battle.wall = { level: 10, maxHp: 2500000, hpRemaining: 2500000, defense: 200 };
  battle.battlePhase = 'CLEAR_DEFENDERS';
  Game.Battle.openTacticalUnitCommand('rocket~a');
  assert.doesNotMatch(popupHtml(view), /option value="wall"/);
  battle.battlePhase = 'ATTACK_WALL';
  Game.Core.render();
  assert.match(popupHtml(view), /option value="wall"/);
  battle.attackerArmy = { truck: 5 };
  Game.Core.render();
  assert.equal(Game.Battle._tacticalCommandUnitId, null);
  assert.doesNotMatch(view.innerHTML, /id="tacticalUnitCommand"/);
});

test('离开战场时清理外层弹窗，重新打开战场不会恢复旧弹窗', () => {
  const { Game, removed } = setup();
  Game.Battle.openTacticalUnitCommand('rocket~a');
  Game.Core.route = 'alerts';
  Game.Battle.stopTacticalTimer();
  assert.equal(removed(), 1);
  assert.equal(Game.Battle._tacticalCommandUnitId, null);
  assert.equal(Game.Battle._tacticalCommandPopup, null);
});

test('攻守进入均默认地图，仅当前视图挂载交互内容，公共操作只有一份', () => {
  for (const side of ['attacker', 'defender']) {
    const { Game, view } = setup();
    const Battle = Game.Battle;
    Battle._activeTactical.side = side;
    Game.Core.render();
    assert.equal(Battle._tacticalCommandView, 'battlefield');
    assert.match(view.innerHTML, /role="tablist"/);
    assert.match(view.innerHTML, /id="tacticalTab-battlefield" aria-selected="true"/);
    assert.match(view.innerHTML, /id="tacticalPanel-text"[^>]*hidden><[/]div>/);
    assert.match(view.innerHTML, /class="tactical-map/);
    assert.doesNotMatch(view.innerHTML, /class="tactical-order-card"/);
    assert.ok(view.innerHTML.indexOf('id="tacticalCountdown"') < view.innerHTML.indexOf('role="tablist"'));
    for (const mode of ['text', 'battlefield']) {
      Battle.setTacticalCommandView(mode);
      assert.equal(view.innerHTML.split('onclick="Game.Battle.executeTacticalRound()"').length - 1, 1);
      assert.equal((view.innerHTML.match(/id="tacticalCountdown"/g) || []).length, 1);
      assert.ok(view.innerHTML.indexOf('tactical-command-head') > view.innerHTML.indexOf('id="tacticalPanel-' + mode + '"'));
    }
  }
});

test('地图与文本双向同步同兵种动作和集火，全军命令保留目标且切换不请求接口', () => {
  const { Game, view, focused } = setup();
  const Battle = Game.Battle;
  Game.API = new Proxy({}, { get() { throw new Error('切换不能请求接口'); } });
  Battle.openTacticalUnitCommand('rocket~b');
  Battle.setTacticalAction('rocket~b', 'RETREAT');
  Battle.setTacticalCommandView('text');
  assert.equal(focused(), 'tacticalTab-text');
  assert.doesNotMatch(view.innerHTML, /class="tactical-map|data-tactical-command-unit|id="tacticalUnitCommand"/);
  assert.equal((view.innerHTML.match(/class="tactical-order-card"/g) || []).length, 2);
  assert.match(view.innerHTML, /×30/);
  assert.match(view.innerHTML, /aria-pressed="true"[^>]*>后退/);
  Battle.setTacticalFocus('rocket', 'htank');
  Battle.setTacticalCommandView('battlefield');
  assert.match(view.innerHTML, /后退 · 集火重型坦克/);
  Battle.openTacticalUnitCommand('rocket~a');
  assert.match(popupHtml(view), /option value="htank" selected/);
  assert.match(popupHtml(view), /aria-pressed="true"[^>]*>后退/);
  Battle.setTacticalCommandView('text');
  Battle.openTacticalUnitCommand('rocket~a');
  assert.equal(Battle._tacticalCommandUnitId, null);
  Battle.setAllTacticalActions('HOLD');
  assert.equal(Battle._tacticalOrders.rocket.focusTarget, 'htank');
  assert.equal(Battle._tacticalOrders.truck.action, 'HOLD');
  Battle.setTacticalFocus('rocket', '');
  assert.equal(Battle._tacticalOrders.rocket.action, 'HOLD');
  assert.equal(Battle._tacticalOrders.rocket.focusTarget, null);
  assert.deepEqual(Object.keys(Battle._tacticalOrders), ['rocket', 'truck']);
});

test('方向键与Home/End切换后焦点留在Tab，切换清除图标弹窗', () => {
  const { Game, view, focused } = setup();
  const Battle = Game.Battle;
  Battle.openTacticalUnitCommand('rocket~a');
  let prevented = 0;
  for (const [key, mode] of [['ArrowRight', 'text'], ['ArrowLeft', 'battlefield'], ['End', 'text'], ['Home', 'battlefield']]) {
    Battle.onTacticalTabKeydown({ key, preventDefault() { prevented++; } });
    assert.equal(Battle._tacticalCommandView, mode);
    assert.equal(focused(), 'tacticalTab-' + mode);
    assert.doesNotMatch(view.innerHTML, /id="tacticalUnitCommand"/);
    assert.match(view.innerHTML, new RegExp('id="tacticalTab-' + mode + '" aria-selected="true"[^>]*tabindex="0"'));
  }
  assert.equal(prevented, 4);
});

test('快速切换、日志和重绘共用一个计时器，不延长服务端截止时间', () => {
  const { Game, view, countdowns, setNow, timers, timerCount } = setup();
  const Battle = Game.Battle;
  Battle._activeTactical.log = '首条记录\n次条记录';
  Game.Core.render();
  assert.match(view.innerHTML, /战场记录（2） 展开/);
  assert.match(view.innerHTML, /id="tacticalLog" hidden/);
  setNow(12000);
  for (let i = 0; i < 10; i++) {
    Battle.setTacticalCommandView(i % 2 ? 'battlefield' : 'text');
    Battle.toggleTacticalLog();
    Game.Core.render();
  }
  assert.equal(countdowns.tacticalCountdown.textContent, '4秒');
  assert.equal(Battle._tacticalDeadline, 16000);
  assert.equal(timers(), 1);
  assert.equal(timerCount(), 1);
  Battle.toggleTacticalLog();
  Battle.setTacticalCommandView('text');
  assert.match(view.innerHTML, /id="tacticalLogToggle" aria-expanded="true"/);
  assert.doesNotMatch(view.innerHTML, /id="tacticalLog" hidden/);
});

test('旧会话缺截止时间时仅初始化一次十二秒窗口，切换视图不延长', () => {
  const { Game, setNow } = setup();
  const Battle = Game.Battle;
  delete Battle._activeTactical.roundDeadlineAt;
  Battle.resetTacticalPanel(42);
  Game.Core.render();
  assert.equal(Battle._tacticalDeadline, 13000);
  setNow(12000);
  Battle.setTacticalCommandView('text');
  assert.equal(Battle._tacticalDeadline, 13000);
});

test('提交期间两视图禁用命令和执行，但Tab可切换，下一回合保留视图和日志状态', async () => {
  const { Game, view, setNow, tick, timers } = setup();
  const Battle = Game.Battle;
  const calls = [];
  let complete;
  Game.API = { commandTacticalBattle(marchId, orders, round) {
    calls.push({ marchId, orders, round });
    return new Promise(resolve => { complete = resolve; });
  } };
  Battle.setTacticalAction('rocket', 'RETREAT');
  Battle.setTacticalFocus('rocket', 'htank');
  Battle.toggleTacticalLog();
  const before = JSON.stringify(Battle._tacticalOrders);
  setNow(16000);
  tick();
  assert.equal(timers(), 0);
  for (const mode of ['text', 'battlefield', 'text']) {
    Battle.setTacticalCommandView(mode);
    assert.match(view.innerHTML, /命令正在提交/);
    const commands = [...view.innerHTML.matchAll(/<(button|select)[^>]*(?:setTacticalAction|setAllTacticalActions|setTacticalFocus|executeTacticalRound)[^>]*>/g)];
    assert.ok(commands.length >= 4);
    for (const [tag] of commands) assert.match(tag, / disabled/);
    for (const [tab] of view.innerHTML.matchAll(/<button[^>]*role="tab"[^>]*>/g)) assert.doesNotMatch(tab, / disabled/);
    Battle.setTacticalAction('rocket', 'ADVANCE');
    Battle.setTacticalFocus('rocket', '');
    Battle.setAllTacticalActions('HOLD');
    Battle.openTacticalUnitCommand('rocket~a');
    Battle.executeTacticalRound();
    tick();
  }
  assert.equal(JSON.stringify(Battle._tacticalOrders), before);
  assert.equal(calls.length, 1);
  complete({ ...Battle._activeTactical, round: 3, roundDeadlineAt: 31000, orders: calls[0].orders });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(Battle._tacticalCommandView, 'text');
  assert.equal(Battle._tacticalLogExpanded, true);
  assert.equal(Battle._tacticalDeadline, 31000);
  assert.equal(timers(), 1);
  assert.equal(Battle._tacticalSubmitting, false);
  assert.match(view.innerHTML, /option value="htank" selected/);
  assert.match(view.innerHTML, /aria-pressed="true"[^>]*>后退/);
});

test('网络失败在公共区域显示，共享单一重试计时器且手动重试不产生重复请求', async () => {
  const { Game, view, setNow, tick, timers } = setup();
  const Battle = Game.Battle;
  let calls = 0;
  let complete;
  Game.API = { commandTacticalBattle() {
    calls++;
    return calls === 1 ? Promise.reject(new Error('网络暂不可用')) : new Promise(resolve => { complete = resolve; });
  } };
  setNow(16000);
  tick();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(Battle._tacticalRetryAt, 21000);
  assert.equal(timers(), 1);
  for (const mode of ['text', 'battlefield', 'text']) {
    Battle.setTacticalCommandView(mode);
    assert.match(view.innerHTML, /本回合执行失败：网络暂不可用/);
    assert.match(view.innerHTML, /5秒后自动重试/);
    assert.match(view.innerHTML, /id="tacticalLog" hidden/);
  }
  setNow(20000);
  tick();
  assert.equal(calls, 1);
  const pending = Battle.executeTacticalRound();
  setNow(21000);
  tick();
  Battle.executeTacticalRound();
  assert.equal(calls, 2);
  assert.equal(timers(), 0);
  complete({ ...Battle._activeTactical, round: 3, roundDeadlineAt: 36000, orders: {} });
  await pending;
  assert.equal(Battle._tacticalRetryAt, 0);
  assert.equal(Battle._tacticalAutoExecuteFailed, false);
  assert.equal(timers(), 1);
});

test('自动重试到期仅提交一次；业务失败停止自动重试但保留手动执行入口', async () => {
  for (const status of [503, 409]) {
    const { Game, view, setNow, tick, timers } = setup();
    const Battle = Game.Battle;
    let calls = 0;
    Game.API = { commandTacticalBattle() {
      calls++;
      return calls === 1 ? Promise.reject(Object.assign(new Error('暂未执行'), { status })) : new Promise(() => {});
    } };
    setNow(16000);
    tick();
    await new Promise(resolve => setImmediate(resolve));
    Battle.setTacticalCommandView('text');
    setNow(21000);
    tick();
    tick();
    assert.equal(calls, status === 503 ? 2 : 1);
    assert.equal(timers(), 0);
    if (status === 409) {
      assert.match(view.innerHTML, /请手动重试/);
      assert.match(view.innerHTML, /重试第 3 回合/);
    }
  }
});

test('重新进入和更换战斗恢复地图默认，新会话命令不继承旧草稿', async () => {
  const { Game, view, timers } = setup();
  const Battle = Game.Battle;
  const original = structuredClone(Battle._activeTactical);
  Game.API = { getTacticalBattle: () => Promise.resolve(original) };
  Battle.setTacticalCommandView('text');
  Battle.toggleTacticalLog();
  Battle.setTacticalAction('rocket', 'RETREAT');
  Game.Core.route = 'alerts';
  Battle.stopTacticalTimer();
  assert.equal(timers(), 0);
  Battle.openTactical(42);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(Battle._tacticalCommandView, 'battlefield');
  assert.equal(Battle._tacticalLogExpanded, false);
  assert.equal(Battle._tacticalOrders.rocket.action, null);
  Battle.setTacticalCommandView('text');
  Battle.setTacticalAction('rocket', 'HOLD');
  Battle._activeTactical = { ...original, marchId: 43, orders: { rocket: { action: 'ADVANCE', focusTarget: 'htank' } } };
  Game.Core.render();
  assert.equal(Battle._tacticalCommandView, 'battlefield');
  assert.equal(Battle._tacticalOrders.rocket.action, 'ADVANCE');
  assert.equal(Battle._tacticalOrders.rocket.focusTarget, 'htank');
  assert.match(view.innerHTML, /前进 · 集火重型坦克/);
  assert.equal(timers(), 1);
});

test('离开或切换战斗后到达的旧提交响应不覆盖当前会话或触发重试', async () => {
  for (const reject of [false, true]) {
    const { Game, timers } = setup();
    const Battle = Game.Battle;
    let finish;
    Game.API = { commandTacticalBattle: () => new Promise((resolve, fail) => { finish = reject ? fail : resolve; }) };
    const pending = Battle.executeTacticalRound();
    const old = Battle._activeTactical;
    Game.Core.route = 'alerts';
    Battle.stopTacticalTimer();
    Game.Core.route = 'battle';
    const current = { ...old, marchId: 99, round: 5, roundDeadlineAt: 50000, orders: { rocket: { action: 'HOLD' } } };
    Battle._activeTactical = current;
    Game.Core.render();
    finish(reject ? new Error('旧请求失败') : { ...old, round: 3 });
    await pending;
    assert.equal(Battle._activeTactical, current);
    assert.equal(Battle._tacticalOrders.rocket.action, 'HOLD');
    assert.equal(Battle._tacticalAutoExecuteFailed, false);
    assert.equal(Battle._tacticalSubmitting, false);
    assert.equal(Battle._tacticalDeadline, 50000);
    assert.equal(timers(), 1);
  }
});

test('战斗结束停止计时并清理弹窗，两视图共享最终结果和可展开记录', async () => {
  const { Game, view, tick, timers } = setup();
  const Battle = Game.Battle;
  Game.API = { commandTacticalBattle: () => Promise.resolve({ ...Battle._activeTactical, finished: true, result: { win: true }, log: '终局记录' }) };
  Battle.openTacticalUnitCommand('rocket~a');
  await Battle.executeTacticalRound();
  for (const mode of ['text', 'battlefield']) {
    Battle.setTacticalCommandView(mode);
    Battle.toggleTacticalLog();
    assert.match(view.innerHTML, /战斗胜利/);
    assert.match(view.innerHTML, /终局记录/);
    assert.doesNotMatch(view.innerHTML, /executeTacticalRound|setTacticalAction|setAllTacticalActions|<select|id="tacticalUnitCommand"/);
    assert.equal(timers(), 0);
    tick();
  }
});

test('两个入口的城墙集火候选与阶段、攻守权限相同，纯展示工事不能下令', () => {
  const { Game, view } = setup();
  const Battle = Game.Battle;
  const battle = Battle._activeTactical;
  battle.wall = { level: 10, maxHp: 2500000, hpRemaining: 2500000 };
  for (const side of ['attacker', 'defender']) {
    battle.side = side;
    battle.forts = { howitzer: 5 };
    for (const phase of ['CLEAR_DEFENDERS', 'ATTACK_WALL']) {
      battle.battlePhase = phase;
      for (const mode of ['battlefield', 'text']) {
        Battle.setTacticalCommandView(mode);
        if (mode === 'battlefield') Battle.openTacticalUnitCommand('rocket~a');
        const html = mode === 'battlefield' ? popupHtml(view) : view.innerHTML;
        const canFocus = side === 'attacker' && phase === 'ATTACK_WALL';
        assert.equal(html.includes('option value="wall"'), canFocus);
        Battle.setTacticalFocus('rocket', '');
        Battle.setTacticalFocus('rocket', 'wall');
        assert.equal(Battle._tacticalOrders.rocket.focusTarget, canFocus ? 'wall' : null);
      }
    }
  }
  Battle.setTacticalCommandView('battlefield');
  assert.equal(Battle.tacticalCommandAttributes('howitzer', 'mine'), '');
  Battle.openTacticalUnitCommand('howitzer');
  assert.equal(Battle._tacticalCommandUnitId, null);
  assert.equal(Battle.canEditTacticalUnit('wall'), false);
});

test('护盾拦截战场请求时刷新军情并保持关闭指挥界面', async () => {
  const { Game } = setup();
  const routes = [], toasts = [];
  let refreshed = false;
  Game.Core.go = route => routes.push(route);
  Game.toast = text => toasts.push(text);
  const active = Game.Battle._activeTactical;
  Game.API = {
    getTacticalBattle: async () => ({ cancelled: true, reason: 'shield', message: '护盾期间不可发生交火' }),
    getGameState: async force => { assert.equal(force, true); return { world: { incoming: [] } }; },
    applyState: state => { assert.deepEqual(state.world.incoming, []); refreshed = true; }
  };
  await Game.Battle.openTactical(42);
  assert.ok(refreshed);
  assert.deepEqual(routes, ['alerts']);
  assert.equal(Game.Battle._activeTactical, active);
  assert.deepEqual(toasts, ['护盾期间不可发生交火']);
});

test('战斗详情日志中攻击事件后显示敌军兵种战损标签【敌军 兵种 -xx】', () => {
  const { Game } = setup();
  Game.DATA.units.ltank = { name: '轻型坦克-斯图亚特（M5A1）', range: 220, spd: 4 };
  Game.DATA.units.infantry = { name: '步兵-加兰德步枪兵（M1）', range: 160, spd: 2 };

  // 1. 单攻击者攻击敌军轻型坦克
  const events1 = [
    { type: 'LOSSES', round: 5, batch: 1, formationId: 'f-def', formation: '【守军】', unit: 'ltank', countBefore: 20000, losses: 10000, survivors: 10000 },
    { type: 'FIRE', round: 5, batch: 1, formationId: 'f-atk', formation: '【我军】', unit: 'rocket', countBefore: 200, target: '【守军】', targetFormationId: 'f-def', targetUnit: 'ltank', damage: 89360, targetLosses:10000, skillWindow: false }
  ];
  Game.Battle._formationLogSource = '';
  Game.Battle._formationLogUnit = '';
  const html1 = Game.Battle.renderFormationEvents(events1);
  assert.match(html1, /造成 89360 有效伤害 【敌军 轻型坦克 -10000】/);

  // 2. 周期标记为真时仍只展示伤害和战损，避免暗示额外技能触发。
  const events2 = [
    { type: 'LOSSES', round: 5, batch: 1, formationId: 'f-city', formation: '【本城·守将郑海】', unit: 'infantry', countBefore: 1000000, losses: 8000, survivors: 992000 },
    { type: 'FIRE', round: 5, batch: 1, formationId: 'f-atk', formation: '【攻方·将领今朝有酒_219极品军官】', unit: 'rocket', countBefore: 200000, target: '【本城·守将郑海】', targetFormationId: 'f-city', targetUnit: 'infantry', damage: 89360378, targetLosses:8000, skillWindow: true }
  ];
  const html2 = Game.Battle.renderFormationEvents(events2);
  assert.match(html2, /造成 89360378 有效伤害 【敌军 步兵 -8000】/);
  assert.doesNotMatch(html2, /本来源技能窗口生效/);
  const roundReport = { perspective: 'attacker', formationEvents: events2, roundLogs: [
    '-- 第5回合 --', '我方将领加成：全军冲锋 +50%攻击'
  ] };
  const roundLogs = Game.Battle.buildFormationRoundLogs(roundReport).map(line => line.text).join('\n');
  assert.match(roundLogs, /我方将领加成：全军冲锋 \+50%攻击/);
  assert.match(roundLogs, /造成 89360378 有效伤害 【敌军 步兵 -8000】/);
  assert.doesNotMatch(roundLogs, /本来源技能窗口生效/);
  assert.equal(events2[1].skillWindow, true);

  // 3. 来源筛选时，即使 LOSSES 被过滤，攻击事件依然能准确显示敌军兵种战损
  Game.Battle._formationLogSource = 'f-atk';
  const htmlFiltered = Game.Battle.renderFormationEvents(events2);
  assert.match(htmlFiltered, /造成 89360378 有效伤害 【敌军 步兵 -8000】/);
  assert.doesNotMatch(htmlFiltered, /同批受击/);
  Game.Battle._formationLogSource = '';

  // 4. 多攻击者协同攻击同一目标，战损按伤害比例守恒分配
  const eventsMulti = [
    { type: 'LOSSES', round: 3, batch: 1, formationId: 'f-target', formation: '【守方】', unit: 'ltank', countBefore: 1000, losses: 100, survivors: 900 },
    { type: 'FIRE', round: 3, batch: 1, formationId: 'f-a', formation: '【攻方甲】', unit: 'htank', countBefore: 100, target: '【守方】', targetFormationId: 'f-target', targetUnit: 'ltank', damage: 300, targetLosses:30 },
    { type: 'FIRE', round: 3, batch: 1, formationId: 'f-b', formation: '【攻方乙】', unit: 'htank', countBefore: 100, target: '【守方】', targetFormationId: 'f-target', targetUnit: 'ltank', damage: 700, targetLosses:70 }
  ];
  const htmlMulti = Game.Battle.renderFormationEvents(eventsMulti);
  assert.match(htmlMulti, /攻方甲[\s\S]*?造成 300 有效伤害 【敌军 轻型坦克 -30】/);
  assert.match(htmlMulti, /攻方乙[\s\S]*?造成 700 有效伤害 【敌军 轻型坦克 -70】/);

  // 5. 攻打城墙不追加敌军兵种标签
  const eventsWall = [
    { type: 'WALL', round: 4, batch: 1, formationId: 'f-a', formation: '【攻方】', unit: 'howitzer', damage: 5000 }
  ];
  const htmlWall = Game.Battle.renderFormationEvents(eventsWall);
  assert.match(htmlWall, /攻击城墙，造成 5000 有效伤害。/);
  assert.doesNotMatch(htmlWall, /【敌军/);
});
