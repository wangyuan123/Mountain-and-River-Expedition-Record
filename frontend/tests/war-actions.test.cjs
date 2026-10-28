const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function fixture(extra = {}) {
  let now = 100000, renders = 0, wakes = 0;
  const timers = new Map(), nodes = new Map(), buttons = [], calls = [];
  const body = {
    children: [],
    appendChild(node) { node.parentNode = this; this.children.push(node); },
    removeChild(node) { this.children.splice(this.children.indexOf(node), 1); node.parentNode = null; }
  };
  const target = { kind: 'player', id: 2, ownerId: 2, name: '目标城', x: 101, y: 100, warAt: 101000, warEndAt: 103000, ...extra };
  const state = {
    player: { id: 1, activeCityId: 1 }, reports: [],
    world: { cityPos: { x: 100, y: 100 }, pos: { x: 100, y: 100 }, wildTiles: [], bandits: [], playerCities: [target], marches: [], _expandedPlayerIds: { 2: true } }
  };
  const view = {
    html: '',
    get innerHTML() { return this.html; },
    set innerHTML(value) {
      this.html = value;
      nodes.clear();
      for (const match of value.matchAll(/id="(war-(?:start-)?timer-\d+)"[^>]*>([^<]*)/g)) nodes.set(match[1], { textContent: match[2] });
    },
    insertAdjacentHTML() {}
  };
  const game = {
    DATA: { world: { size: 200, viewRadius: 8 }, wildTypes: {}, units: {}, resources: {} },
    Core: { state, route: 'world', views: {}, render() { renders++; game.World.renderView(view); } },
    WorldView: { ensure: () => true },
    API: { getToken: () => 'test', getMapTarget: async () => ({ ...target }) },
    MapChunks: function () { this.invalidate = () => {}; },
    fmt: String, escapeHtml: String, toast: message => calls.push(['toast', message]), go: route => calls.push(['go', route])
  };
  const context = vm.createContext({
    Game: game, Date: class extends Date { constructor(...args) { super(...(args.length ? args : [now])); } static now() { return now; } },
    localStorage: { getItem: () => null, setItem() {} },
    confirm: () => assert.fail('劝降应使用游戏主题弹窗'),
    document: { hidden: false, body, createElement: () => ({
      controls: {}, addEventListener() {},
      querySelector(selector) { return this.controls[selector] || (this.controls[selector] = { focus() {} }); }
    }), getElementById: id => nodes.get(id) || null },
    setInterval(fn) { const id = timers.size + 1; timers.set(id, fn); return id; },
    clearInterval(id) { timers.delete(id); }
  });
  context.window = context;
  require('./load-constants.cjs')(context);
  for (const file of ['world.js', 'world-map.js']) {
    let source = fs.readFileSync(path.join(__dirname, '../js', file), 'utf8');
    if (file === 'world-map.js') {
      source = source.replace("owner = '', mode = 'map'", "owner = 'test:1', mode = 'map'");
      source = source.replace('  G.WorldMap={', '  G.TestMapView=MapView;\n  G.WorldMap={');
    }
    vm.runInContext(source, context, { filename: file });
  }
  game.WorldMap.isMap = () => false;
  const notes = [];
  const map = Object.create(game.TestMapView.prototype);
  map.selected = target;
  map.detailSeq = 0;
  map.detail = {
    hidden: false, html: '',
    get innerHTML() { return this.html; },
    set innerHTML(value) { this.html = value; buttons.length = 0; notes.length = 0; },
    querySelector: () => ({ appendChild: button => buttons.push(button), insertAdjacentHTML: (pos, html) => notes.push(html) })
  };
  map.wake = () => wakes++;
  return {
    game, state, target, view, map, buttons, notes, calls, timers, nodes, context,
    setTime(value) { now = value; },
    tick() { for (const fn of [...timers.values()]) fn(); },
    renderList() { game.World.renderView(view); },
    labels() { return buttons.map(button => button.textContent); },
    get dialog() { return body.children.at(-1); },
    get renders() { return renders; }, get wakes() { return wakes; }
  };
}

test('列表跨过开战和结束边界时自动切换行动，并保留展开状态', () => {
  const f = fixture();
  f.renderList();
  assert.match(f.view.innerHTML, /开战倒计时 1秒/);
  assert.doesNotMatch(f.view.innerHTML, /Game.World.attack\('player',0,'campaign'\)/);
  f.setTime(101000);
  f.tick();
  assert.match(f.view.innerHTML, /Game.World.attack\('player',0,'campaign'\)/);
  assert.doesNotMatch(f.view.innerHTML, />征服<|>掠夺</);
  assert.match(f.view.innerHTML, /tcard-expanded" data-player-id="2"/);
  f.setTime(102000);
  f.tick();
  assert.equal(f.nodes.get('war-timer-0').textContent, '交战剩余 1秒');
  assert.equal(f.renders, 1);
  f.setTime(103000);
  f.tick();
  assert.doesNotMatch(f.view.innerHTML, /Game.World.attack\('player',0,'(?:campaign|conquer|plunder)'\)/);
  assert.match(f.view.innerHTML, /Game.World.declareWar\('player',0\)/);
  assert.equal(f.timers.size, 0);
  assert.equal(f.renders, 2);
});

test('新手宣战明确告知永久退出，取消不发送，确认只授权本次请求', async () => {
  const f = fixture({ warAt: 0, warEndAt: 0 });
  const requests = [];
  f.state.player.newbieProtectionUntil = 200000;
  f.game.API.declareWar = async (...args) => { requests.push(args); };
  f.game.World.declareWar('player', 0);
  assert.match(f.dialog.innerHTML, /永久退出账号所有城市的新手保护，无法恢复/);
  assert.match(f.dialog.innerHTML, /退出保护并宣战/);
  f.dialog.querySelector('#dwCancel').onclick();
  assert.equal(requests.length, 0);
  f.game.World.declareWar('player', 0);
  f.dialog.querySelector('#dwOk').onclick();
  await new Promise(setImmediate);
  assert.deepEqual(requests, [[2, true]]);
});

test('战争结束只移除到期城市的进攻按钮，其余战争仍继续计时', () => {
  const f = fixture({ warAt: 90000 });
  f.state.world.playerCities.push({ ...f.target, id: 3, x: 102, warEndAt: 105000 });
  f.renderList();
  f.setTime(103000);
  f.tick();
  assert.doesNotMatch(f.view.innerHTML, /Game.World.attack\('player',0,'campaign'\)/);
  assert.match(f.view.innerHTML, /Game.World.attack\('player',1,'campaign'\)/);
  assert.equal(f.timers.size, 1);
  f.setTime(105000);
  f.tick();
  assert.equal(f.timers.size, 0);
});

test('缺少结束时间的个人宣战不能显示或打开进攻入口', () => {
  const f = fixture({ warAt: 90000, warEndAt: 0 });
  f.renderList();
  assert.doesNotMatch(f.view.innerHTML, /Game.World.attack\('player',0,'(?:campaign|conquer|plunder)'\)/);
  for (const action of ['campaign', 'conquer', 'plunder']) f.game.World.attack('player', 0, action);
  assert.equal(f.state.world._dispatchTarget, undefined);
  assert.ok(f.calls.every(call => call[0] === 'toast' && call[1].includes('战争已结束')));
});

test('离开地图后计时器停止，不重绘其他页面', () => {
  const f = fixture();
  f.renderList();
  f.game.Core.route = 'dispatch';
  f.setTime(103000);
  f.tick();
  assert.equal(f.timers.size, 0);
  assert.equal(f.renders, 0);
});

test('地图详情在时间边界切换按钮，无需接口数据变化', () => {
  const f = fixture();
  f.map.renderDetail(f.target);
  assert.deepEqual(f.labels(), ['劝降', '侦察']);
  f.setTime(101000);
  f.map.updateWarDetail();
  assert.deepEqual(f.labels(), ['劝降', '侦察', '讨伐']);
  f.setTime(103000);
  f.map.updateWarDetail();
  assert.deepEqual(f.labels(), ['劝降', '侦察', '宣战']);
  assert.equal(f.map.detailTransitionAt, 0);
  assert.equal(f.wakes, 2);
  f.map.updateWarDetail();
  assert.equal(f.wakes, 2);
});

test('后台页面恢复时，相同详情响应也会刷新已经到期的按钮', async () => {
  const f = fixture({ warAt: 90000 });
  f.map.renderDetail(f.target);
  assert.deepEqual(f.labels(), ['劝降', '侦察', '讨伐']);
  f.context.document.hidden = true;
  f.setTime(103000);
  f.map.updateWarDetail();
  assert.deepEqual(f.labels(), ['劝降', '侦察', '讨伐']);
  f.context.document.hidden = false;
  f.map.loadDetail(f.target, true);
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(f.labels(), ['劝降', '侦察', '宣战']);
});

test('点击时取得战争结束的新状态后，详情立即恢复宣战入口', async () => {
  const f = fixture({ warAt: 90000 });
  f.map.renderDetail(f.target);
  f.setTime(103000);
  f.buttons.find(button => button.textContent === '讨伐').onclick();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(f.labels(), ['劝降', '侦察', '宣战']);
  assert.equal(f.state.world._dispatchTarget, undefined);
  assert.deepEqual(f.calls, [['toast', '战争已结束,需重新宣战']]);
});

test('列表和大地图对相同外交及战争状态显示相同顺序的行动', () => {
  for (const scenario of [
    { guildRelation: 'neutral', warAt: 0, warEndAt: 0, labels: ['劝降', '侦察', '宣战'] },
    { guildRelation: 'hostile', warAt: 0, warEndAt: 0, labels: ['劝降', '侦察', '讨伐'] },
    { guildRelation: 'hostile', warAt: 90000, warEndAt: 99000, labels: ['劝降', '侦察', '讨伐'] },
    { guildRelation: 'friendly', warAt: 90000, warEndAt: 103000, labels: ['劝降', '侦察'] },
    { guildRelation: 'neutral', warAt: 90000, warEndAt: 103000, labels: ['劝降', '侦察', '讨伐'] }
  ]) {
    const f = fixture(scenario);
    f.renderList();
    f.map.renderDetail(f.target);
    const targetCard = f.view.innerHTML.split('data-player-id="2"')[1];
    const actions = targetCard.match(/<div class="tcard-actions">([\s\S]*?)<\/div>/)[1];
    const listLabels = [...actions.matchAll(/<button\b[^>]*>([^<]*)<\/button>/g)].map(match => match[1]);
    assert.deepEqual(listLabels, scenario.labels);
    assert.deepEqual(f.labels(), scenario.labels);
  }
});

test('敌对军团的按钮与点击校验一致，过期个人宣战不阻止出征', () => {
  for (const war of [{ warAt: 90000, warEndAt: 99000 }, { warAt: 0, warEndAt: 0 }]) {
    const f = fixture({ guildRelation: 'hostile', ...war });
    f.renderList();
    f.map.renderDetail(f.target);
    assert.deepEqual(f.labels(), ['劝降', '侦察', '讨伐']);
    assert.match(f.view.innerHTML, /敌对军团，可直接交战/);
    assert.match(f.map.detail.innerHTML, /敌对军团，可直接交战/);
    for (const action of ['campaign', 'conquer', 'plunder']) {
      f.game.World.attack('player', 0, action);
      assert.equal(f.state.world._dispatchTarget.action, 'campaign');
      assert.equal(f.game.World.resolveDispatchAction(f.state.world._dispatchTarget), action === 'campaign' ? 'conquer' : action);
    }
    assert.deepEqual(f.calls, [['go', 'dispatch'], ['go', 'dispatch'], ['go', 'dispatch']]);
  }
});

test('友好军团在有效个人战争期内仍不能显示或打开进攻入口', () => {
  const f = fixture({ guildRelation: 'friendly', warAt: 90000 });
  f.renderList();
  f.map.renderDetail(f.target);
  assert.deepEqual(f.labels(), ['劝降', '侦察']);
  assert.doesNotMatch(f.view.innerHTML, /Game.World.(?:attack\('player',0,'(?:campaign|conquer|plunder)'|declareWar)/);
  for (const action of ['campaign', 'conquer', 'plunder']) f.game.World.attack('player', 0, action);
  assert.equal(f.state.world._dispatchTarget, undefined);
  assert.ok(f.calls.every(call => call[0] === 'toast' && call[1].includes('友好军团')));
});

test('玩家城劝降入口不受战争状态、保护期及军团关系限制', () => {
  for (const extra of [
    { warAt: 0, warEndAt: 0 },
    { warAt: 90000, warEndAt: 99000, coolAt: 110000 },
    { guildRelation: 'friendly' },
    { guildRelation: 'hostile' },
    { guildmate: true }
  ]) {
    const f = fixture(extra);
    f.map.renderDetail(f.target);
    assert.equal(f.labels().filter(label => label === '劝降').length, 1);
    if (extra.guildmate) assert.deepEqual(f.labels(), ['劝降', '运输', '驻防']);
  }
});

test('自己、建设中、移交中的城市及非玩家目标不显示劝降', () => {
  for (const extra of [
    { selfCity: true }, { readyAt: 110000 }, { surrenderFrozen: true },
    { kind: 'simulated_npc' }, { kind: 'bandit' }, { kind: 'wild', type: 'swamp' }
  ]) {
    const f = fixture(extra);
    f.map.renderDetail(f.target);
    assert.ok(!f.labels().includes('劝降'));
    if (extra.surrenderFrozen) {
      assert.deepEqual(f.labels(), []);
      assert.match(f.map.detail.innerHTML, /归降移交中 · 城市只读/);
    }
  }
});

test('地图劝降点击读取最新城市，列表重排后仍向所选城市发送电报', async () => {
  const f = fixture({ warAt: 0, warEndAt: 0 });
  const offers = [];
  f.game.API.offerSurrender = async id => { offers.push(id); };
  f.game.API.getMapTarget = async (kind, id) => {
    assert.equal(kind, 'player');
    assert.equal(id, 2);
    return { ...f.target, name: '最新城名' };
  };
  f.map.renderDetail(f.target);
  f.state.world.playerCities.unshift({ ...f.target, id: 3, name: '其他城市' });
  f.buttons.find(button => button.textContent === '劝降').onclick();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(offers, []);
  assert.equal(f.dialog.className, 'modal-mask confirm-dialog-mask');
  assert.match(f.dialog.innerHTML, /最新城名/);
  assert.match(f.dialog.innerHTML, /24 小时答复/);
  assert.match(f.dialog.innerHTML, /24 小时后完成移交/);
  f.dialog.querySelector('#confirmOkBtn').onclick();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(offers, [2]);
  assert.equal(f.dialog, undefined);
  assert.equal(f.state.world._dispatchTarget, undefined);
  assert.deepEqual(f.calls, [['toast', '劝降电报已送达，对方有 24 小时答复']]);
});

test('地图劝降点击时已冻结或尚未建成，不确认也不发送邀请', async () => {
  for (const extra of [{ surrenderFrozen: true }, { readyAt: 110000 }]) {
    const f = fixture();
    f.game.World.showConfirm = () => assert.fail('不可移交的城市不应弹出确认');
    f.game.API.offerSurrender = () => assert.fail('不可移交的城市不应发送邀请');
    f.game.API.getMapTarget = async () => ({ ...f.target, ...extra });
    f.map.renderDetail(f.target);
    f.buttons.find(button => button.textContent === '劝降').onclick();
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(f.labels(), []);
    assert.deepEqual(f.calls, [['toast', extra.surrenderFrozen ? '该城市正在移交中' : '目标城市尚未建成']]);
  }
});

test('取消地图劝降确认后不发送电报', async () => {
  const f = fixture();
  f.game.API.offerSurrender = () => assert.fail('取消后不应发送邀请');
  f.map.renderDetail(f.target);
  f.buttons.find(button => button.textContent === '劝降').onclick();
  await new Promise(resolve => setImmediate(resolve));
  f.dialog.querySelector('#confirmCancelBtn').onclick();
  assert.equal(f.dialog, undefined);
  assert.deepEqual(f.calls, []);
  assert.equal(f.state.world._dispatchTarget, undefined);
});

test('玩家城市处于护盾期间，弹窗内侦查和讨伐按钮禁用且展示蓝色背景提示', () => {
  for (const shieldExtra of [
    { cityState: 'shield' },
    { shieldUntil: 105000 },
    { coolAt: 105000 },
    { guildRelation: 'hostile', cityState: 'shield' }
  ]) {
    const f = fixture({ name: '焚天灭地域', ...shieldExtra });
    f.map.renderDetail(f.target);
    assert.deepEqual(f.labels(), ['劝降', '侦察', '讨伐']);
    const scoutBtn = f.buttons.find(b => b.textContent === '侦察');
    const campaignBtn = f.buttons.find(b => b.textContent === '讨伐');
    const surrenderBtn = f.buttons.find(b => b.textContent === '劝降');
    assert.equal(scoutBtn.disabled, true);
    assert.equal(campaignBtn.disabled, true);
    assert.equal(surrenderBtn.disabled, false);
    assert.ok(f.notes.some(note => note.includes('world-map-shield-note') && note.includes('焚天灭地域处于护盾期间，无法发起侦查或攻击')));
  }
});

test('玩家城市护盾到期后自动恢复按钮可用并移除提示', () => {
  const f = fixture({ guildRelation: 'hostile', shieldUntil: 102000 });
  f.map.renderDetail(f.target);
  assert.equal(f.buttons.find(b => b.textContent === '侦察').disabled, true);
  assert.equal(f.buttons.find(b => b.textContent === '讨伐').disabled, true);
  assert.ok(f.notes.some(note => note.includes('world-map-shield-note')));

  f.setTime(102000);
  f.map.updateWarDetail();
  assert.equal(f.buttons.find(b => b.textContent === '侦察').disabled, false);
  assert.equal(f.buttons.find(b => b.textContent === '讨伐').disabled, false);
  assert.ok(!f.notes.some(note => note.includes('world-map-shield-note')));
});

test('护盾期间尝试对玩家城市发起军事操作弹出拦截提示', () => {
  const f = fixture({ name: '护盾防守城', cityState: 'shield' });
  f.map.renderDetail(f.target);
  const scoutBtn = f.buttons.find(b => b.textContent === '侦察');
  const campaignBtn = f.buttons.find(b => b.textContent === '讨伐');
  f.map.act('campaign', campaignBtn);
  f.map.act('scout', scoutBtn);
  assert.deepEqual(f.calls, [
    ['toast', '护盾防守城处于护盾期间，无法发起侦查或攻击'],
    ['toast', '护盾防守城处于护盾期间，无法发起侦查或攻击']
  ]);
});
