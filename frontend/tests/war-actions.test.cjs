const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function fixture(extra = {}) {
  let now = 100000, renders = 0, wakes = 0;
  const timers = new Map(), nodes = new Map(), buttons = [], calls = [];
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
    Game: game, Date: { now: () => now },
    localStorage: { getItem: () => null, setItem() {} },
    document: { hidden: false, createElement: () => ({}), getElementById: id => nodes.get(id) || null },
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
  const map = Object.create(game.TestMapView.prototype);
  map.selected = target;
  map.detailSeq = 0;
  map.detail = {
    hidden: false, html: '',
    get innerHTML() { return this.html; },
    set innerHTML(value) { this.html = value; buttons.length = 0; },
    querySelector: () => ({ appendChild: button => buttons.push(button), insertAdjacentHTML() {} })
  };
  map.wake = () => wakes++;
  return {
    game, state, target, view, map, buttons, calls, timers, nodes, context,
    setTime(value) { now = value; },
    tick() { for (const fn of [...timers.values()]) fn(); },
    renderList() { game.World.renderView(view); },
    labels() { return buttons.map(button => button.textContent); },
    get renders() { return renders; }, get wakes() { return wakes; }
  };
}

test('列表跨过开战和结束边界时自动切换行动，并保留展开状态', () => {
  const f = fixture();
  f.renderList();
  assert.match(f.view.innerHTML, /开战倒计时 0:01/);
  assert.doesNotMatch(f.view.innerHTML, /Game.World.attack\('player',0,'campaign'\)/);
  f.setTime(101000);
  f.tick();
  assert.match(f.view.innerHTML, /Game.World.attack\('player',0,'campaign'\)/);
  assert.doesNotMatch(f.view.innerHTML, />征服<|>掠夺</);
  assert.match(f.view.innerHTML, /tcard-expanded" data-player-id="2"/);
  f.setTime(102000);
  f.tick();
  assert.equal(f.nodes.get('war-timer-0').textContent, '交战剩余 0:01');
  assert.equal(f.renders, 1);
  f.setTime(103000);
  f.tick();
  assert.doesNotMatch(f.view.innerHTML, /Game.World.attack\('player',0,'(?:campaign|conquer|plunder)'\)/);
  assert.match(f.view.innerHTML, /Game.World.declareWar\('player',0\)/);
  assert.equal(f.timers.size, 0);
  assert.equal(f.renders, 2);
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
  assert.deepEqual(f.labels(), ['侦察']);
  f.setTime(101000);
  f.map.updateWarDetail();
  assert.deepEqual(f.labels(), ['侦察', '讨伐']);
  f.setTime(103000);
  f.map.updateWarDetail();
  assert.deepEqual(f.labels(), ['侦察', '宣战']);
  assert.equal(f.map.detailTransitionAt, 0);
  assert.equal(f.wakes, 2);
  f.map.updateWarDetail();
  assert.equal(f.wakes, 2);
});

test('后台页面恢复时，相同详情响应也会刷新已经到期的按钮', async () => {
  const f = fixture({ warAt: 90000 });
  f.map.renderDetail(f.target);
  assert.deepEqual(f.labels(), ['侦察', '讨伐']);
  f.context.document.hidden = true;
  f.setTime(103000);
  f.map.updateWarDetail();
  assert.deepEqual(f.labels(), ['侦察', '讨伐']);
  f.context.document.hidden = false;
  f.map.loadDetail(f.target, true);
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(f.labels(), ['侦察', '宣战']);
});

test('点击时取得战争结束的新状态后，详情立即恢复宣战入口', async () => {
  const f = fixture({ warAt: 90000 });
  f.map.renderDetail(f.target);
  f.setTime(103000);
  f.buttons.find(button => button.textContent === '讨伐').onclick();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(f.labels(), ['侦察', '宣战']);
  assert.equal(f.state.world._dispatchTarget, undefined);
  assert.deepEqual(f.calls, [['toast', '战争已结束,需重新宣战']]);
});

test('敌对军团的按钮与点击校验一致，过期个人宣战不阻止出征', () => {
  for (const war of [{ warAt: 90000, warEndAt: 99000 }, { warAt: 0, warEndAt: 0 }]) {
    const f = fixture({ guildRelation: 'hostile', ...war });
    f.renderList();
    f.map.renderDetail(f.target);
    assert.deepEqual(f.labels(), ['侦察', '讨伐']);
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
  assert.deepEqual(f.labels(), ['侦察']);
  assert.doesNotMatch(f.view.innerHTML, /Game.World.(?:attack\('player',0,'(?:campaign|conquer|plunder)'|declareWar)/);
  for (const action of ['campaign', 'conquer', 'plunder']) f.game.World.attack('player', 0, action);
  assert.equal(f.state.world._dispatchTarget, undefined);
  assert.ok(f.calls.every(call => call[0] === 'toast' && call[1].includes('友好军团')));
});
