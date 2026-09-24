const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');

function sandbox(extra = {}) {
  const context = {
    console, TypeError,
    document: { readyState: 'loading', activeElement: null, addEventListener() {},
      // Pixi probes canvas blend support while loading; rendering itself is tested in a browser.
      createElement(tag) {
        if (tag !== 'canvas') return { style: {} };
        return { width: 0, height: 0, style: {}, getContext() {
          return { fillRect() {}, drawImage() {}, getImageData() { return { data: [0, 0, 0, 0] }; } };
        } };
      },
      getElementById() { return null; }, querySelector() { return null; } },
    localStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
    location: { port: '80', protocol: 'http:', host: 'localhost' },
    setTimeout(fn) { fn(); return 1; }, clearTimeout() {},
    setInterval() { return 1; }, clearInterval() {}, ...extra
  };
  context.window = context;
  context.Game = context.Game || {};
  return vm.createContext(context);
}

function load(context, file) {
  vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), context, { filename: file });
}

test('page scripts initialize in HTML order and expose the extracted profile actions', () => {
  const context = sandbox();
  const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
  for (const match of html.matchAll(/<script src="([^"?]+)/g)) load(context, match[1]);
  assert.equal(typeof context.Game.Main.openPlayerDrawer, 'function');
  assert.equal(typeof context.Game.Main.doLogin, 'function');
  assert.equal(typeof context.Game.MainView.navBar, 'function');
  assert.equal(typeof context.Game.WorldView.ensure, 'function');
});

test('GET retries a transient network failure', async () => {
  let calls = 0;
  const context = sandbox({ fetch() {
    if (++calls === 1) return Promise.reject(new TypeError('response lost'));
    return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('{"gold":5}') });
  } });
  load(context, 'js/api-client.js');
  const client = new context.Game.ApiClient('/api');
  assert.equal((await client.get('/game/state', { silent: true })).gold, 5);
  assert.equal(calls, 2);
});

for (const method of ['POST', 'PUT', 'DELETE']) {
  test(`${method} is never replayed after an uncertain result, even with explicit retries`, async () => {
    let calls = 0;
    const context = sandbox({ fetch() { calls++; return Promise.reject(new TypeError('response lost')); } });
    load(context, 'js/api-client.js');
    const client = new context.Game.ApiClient('/api');
    client.setStateCache({ diamond: 500 });
    await assert.rejects(client.request(method, '/game/shop/buy', {}, { silent: true, retry: 5 }), /结果尚未确认/);
    assert.equal(calls, 1);
    assert.equal(client.getCachedState(), null);
  });
}

test('an optimistic conflict surfaces once without replaying the purchase', async () => {
  let calls = 0;
  const context = sandbox({ fetch() {
    calls++;
    return Promise.resolve({ status: 409, ok: false,
      text: () => Promise.resolve('{"error":"状态冲突，请刷新"}') });
  } });
  load(context, 'js/api-client.js');
  await assert.rejects(new context.Game.ApiClient('/api').post('/game/shop/buy', {}, { silent: true }), /状态冲突/);
  assert.equal(calls, 1);
});

test('a scout report reaches the existing UI handler and duplicate reports are ignored', () => {
  let unreadRefreshes = 0;
  const context = sandbox({ Game: { state: { reports: [] },
    Battle: { refreshUnread() { unreadRefreshes++; } } } });
  load(context, 'js/ws-client.js');
  load(context, 'js/ws-handlers.js');
  const message = JSON.stringify({ type: 'scoutReport', data: { id: 42, type: 'scout', data: { x: 10, y: 10 } } });
  context.Game.WS.handleMessage(message);
  context.Game.WS.handleMessage(message);
  assert.equal(context.Game.state.reports.length, 1);
  assert.equal(context.Game.state.reports[0].readAt, 0);
  assert.equal(unreadRefreshes, 2);
});

test('changing map position discards an older response and keeps newer marching state', async () => {
  const responses = [];
  const context = sandbox({ Game: {
    state: { world: { marches: [{ id: 9 }] } },
    API: { getToken: () => 'alice', getWorldView: () => new Promise(resolve => responses.push(resolve)) }
  } });
  load(context, 'js/world-view.js');
  const old = context.Game.WorldView.load(10, 10, 3);
  const current = context.Game.WorldView.load(20, 20, 5);
  responses[1]({ wildTiles: [{ id: 2 }], marches: [] });
  assert.equal(await current, true);
  responses[0]({ wildTiles: [{ id: 1 }] });
  assert.equal(await old, false);
  assert.equal(context.Game.state.world.wildTiles[0].id, 2);
  assert.equal(context.Game.state.world.marches[0].id, 9);
  assert.equal(context.Game.state.world.view.radius, 5);
});

test('a map response from the previous login cannot update a different account', async () => {
  let finish;
  let token = 'alice';
  const context = sandbox({ Game: { state: { world: {} }, API: {
    getToken: () => token, getWorldView: () => new Promise(resolve => { finish = resolve; })
  } } });
  load(context, 'js/world-view.js');
  const request = context.Game.WorldView.load(10, 10, 0);
  token = 'bob';
  finish({ wildTiles: [{ id: 1 }] });
  assert.equal(await request, false);
  assert.equal(context.Game.state.world.wildTiles, undefined);
});

test('a selected dispatch keeps its target when the visible map array is replaced', async () => {
  let request;
  const world = { npcCities: [{ id: 41, name: 'Selected', x: 10, y: 10 }] };
  const context = sandbox({ Game: {
    DATA: { units: { infantry: {} } },
    Core: { state: { world }, views: {} }, go() {}, toast() {},
    API: { worldDispatch(body) { request = body; return Promise.resolve({ success: true }); } }
  } });
  context.document.getElementById = id => id === 'dqty_infantry' ? { value: '10' } : null;
  load(context, 'js/world.js');
  context.Game.World.attack('npc', 0, 'plunder');
  world.npcCities = [{ id: 99, name: 'Different region' }];
  context.Game.World.launchDispatch();
  assert.equal(request.targetId, 41);
  await Promise.resolve();
});

test('dispatch blocks an army selection above the troop cap before requesting the server', () => {
  let requests = 0;
  let message = '';
  const world = { npcCities: [{ id: 41, name: 'Selected', x: 10, y: 10 }] };
  const context = sandbox({ Game: {
    DATA: { units: { infantry: {} } },
    Core: { state: { world }, views: {}, armyCap: () => 9 }, go() {},
    toast(text) { message = text; },
    API: { worldDispatch() { requests++; return Promise.resolve({ success: true }); } }
  } });
  context.document.getElementById = id => id === 'dqty_infantry' ? { value: '10' } : null;
  load(context, 'js/world.js');
  context.Game.World.attack('npc', 0, 'plunder');
  context.Game.World.launchDispatch();

  assert.equal(requests, 0);
  assert.equal(message, '出征兵力超过带兵上限 9（当前选择 10）');
});


test('resource buildings share town hall capacity including new construction reservations', () => {
  const context = sandbox();
  for (const file of ['js/data.js', 'js/core.js', 'js/build.js']) load(context, file);
  const core = context.Game.Core;
  core.state = { buildings: { command: 10, farm: Array(31).fill(1), house: Array(20).fill(1) }, constructions: [] };
  assert.equal(core.groupSlotsCap('res'), 32);
  assert.equal(core.groupSlotsCap('army'), 32);
  assert.equal(core.groupSlotsRemaining('res'), 1);
  core.state.constructions = [{ id: 'refinery', slot: 0, targetLevel: 1 }];
  assert.equal(core.groupSlotsRemaining('res'), 0);
  core.state.constructions = [{ id: 'farm', slot: 0, targetLevel: 2 }];
  assert.equal(core.groupSlotsRemaining('res'), 1);
  core.state.buildings = { command: 0, farm: Array(8).fill(1), refinery: Array(8).fill(1), oilfield: Array(8).fill(1), raremine: Array(8).fill(1) };
  core.state.constructions = [];
  assert.equal(core.groupSlotsCap('res'), 12);
  assert.equal(core.groupSlotsUsed('res'), 32);
  assert.equal(core.groupSlotsRemaining('res'), 0);
  for (const [level, cap] of [[0, 12], [1, 14], [5, 22], [9, 30], [10, 32], [11, 32]]) {
    core.state.buildings.command = level;
    assert.equal(core.groupSlotsCap('res'), cap);
  }
  for (const type of ['farm', 'refinery', 'oilfield', 'raremine']) {
    assert.equal(context.Game.DATA.buildings[type].slots, 32);
  }
});


test('military quota shares 32 slots while functional buildings remain single-instance', () => {
  const context = sandbox();
  for (const file of ['js/data.js', 'js/core.js', 'js/build.js']) load(context, file);
  const { Core: core, DATA: data, Build: build } = context.Game;
  core.state = { buildings: { command: 10, house: Array(20).fill(1), factory: Array(5).fill(1), depot: Array(5).fill(1) }, constructions: [] };
  assert.equal(core.groupSlotsUsed('army'), 31);
  core.state.constructions = [{ id: 'radar', slot: null, targetLevel: 1 }];
  assert.equal(core.groupSlotsRemaining('army'), 0);
  assert.equal(core.groupSlotsRemaining('res'), 32);
  core.state.constructions = [{ id: 'house', slot: 0, targetLevel: 2 }];
  assert.equal(core.groupSlotsRemaining('army'), 1);
  for (const [level, cap] of [[0,12], [1,14], [5,22], [10,32], [11,32]]) {
    core.state.buildings.command = level;
    assert.equal(core.groupSlotsCap('army'), cap);
  }
  for (const type of build.GROUPS.army.order) {
    assert.equal(data.buildings[type].slots, ['house', 'factory', 'depot'].includes(type) ? 32 : 1);
  }
});

for (const scenario of [
  { name: '军事区无施工', route: 'buildArmy', jobs: [] },
  { name: '军事区单栋建筑升级', route: 'buildArmy', jobs: [{ id: 'radar', slot: null, fromLevel: 1, targetLevel: 2 }] },
  { name: '军事区单栋建筑拆除', route: 'buildArmy', jobs: [{ id: 'radar', slot: null, fromLevel: 1, targetLevel: 0, action: 'dismantle' }] },
  { name: '军事区多栋建筑升级', route: 'buildArmy', jobs: [{ id: 'house', slot: 0, fromLevel: 1, targetLevel: 2 }] },
  { name: '军事区多栋建筑拆除', route: 'buildArmy', jobs: [{ id: 'house', slot: 0, fromLevel: 1, targetLevel: 0, action: 'dismantle' }] },
  { name: '资源区建筑施工', route: 'buildRes', jobs: [{ id: 'farm', slot: 0, fromLevel: 1, targetLevel: 2 }] }
]) {
  test(`${scenario.name}可以完整渲染建筑页`, () => {
    const context = sandbox();
    context.self = context; // 浏览器的全局 self 指向 window，而不是 Build。
    for (const file of ['js/data.js', 'js/core.js', 'js/build.js']) load(context, file);
    const core = context.Game.Core;
    core.state = {
      buildings: { command: 2, radar: 1, house: [1], farm: [1] },
      tech: {}, officers: [], items: {},
      constructions: scenario.jobs.map(job => ({ ...job, finishesAt: Date.now() + 60000 }))
    };
    const view = { innerHTML: '' };
    core.views[scenario.route](view);
    assert.match(view.innerHTML, /返回主菜单/);
    assert.match(view.innerHTML, /data-building="(?:command|farm)"/);
    if (scenario.jobs.length) {
      const job = scenario.jobs[0];
      const start = view.innerHTML.indexOf(`data-building="${job.id}"`);
      assert.ok(start >= 0);
      const card = view.innerHTML.slice(start + 'data-building="'.length).split('data-building="')[0];
      assert.match(card, /Game\.Build\.(?:onSlotClick|showJobDetails)/);
      assert.match(card, job.action === 'dismantle' ? /拆除中/ : /升级中|施工中/);
    }
  });
}


function incomingContext() {
  const context = sandbox();
  for (const file of ['js/data.js', 'js/core.js', 'js/world.js', 'js/main-view.js', 'js/ws-client.js', 'js/ws-handlers.js']) load(context, file);
  const G = context.Game;
  G.state = { player: { id: 2 }, resources: {}, world: { incoming: [], marches: [], alertsViewed: true } };
  G.Core.state = G.state;
  G.Core.route = 'alerts';
  G.Core.refreshTop = () => {};
  G.Core.silentUpdate = () => {};
  G.Main = { renderNavBar() { G._nav = G.MainView.navBar(); } };
  G.toast = text => { G._toast = text; };
  return G;
}

test('incoming tick updates the list and keeps the military alert red until the threat is gone', () => {
  const G = incomingContext();
  const attack = { id: 'march-7', fromName: '<敌军>', targetName: '我方城', targetX: 100, targetY: 100,
    arriveAt: Date.now() + 60000, army: { infantry: 10 }, action: 'plunder' };
  G.WS.emit('tick', { incoming: [attack] });
  assert.match(G._nav, /class="navitem active alert" data-route="alerts"/);
  const view = { innerHTML: '' };
  G.World.renderAlerts(view);
  assert.match(view.innerHTML, /敌军来袭 \(1\)/);
  assert.match(view.innerHTML, /&lt;敌军&gt; 来袭/);
  G.state.world.incoming[0].expanded = true;
  G.WS.emit('tick', { incoming: [{ ...attack, expanded: false }] });
  assert.equal(G.state.world.incoming[0].expanded, true);
  G.World.renderAlerts(view);
  assert.match(view.innerHTML, /我方城/);
  assert.match(view.innerHTML, /x10/);
  G.WS.emit('tick', { incoming: [] });
  assert.doesNotMatch(G._nav, /class="navitem active alert" data-route="alerts"/);
  G.World.renderAlerts(view);
  assert.match(view.innerHTML, /暂无敌方来袭情报/);
});

test('arrived incoming player march opens the same tactical battle for defender command', () => {
  const G = incomingContext();
  const view = { innerHTML: '' };
  let openedMarchId = null;
  G.Battle = { openTactical: marchId => { openedMarchId = marchId; } };
  G.state.world.incoming = [{
    id: 'march-19', marchId: 19, fromName: '敌方', targetName: '我方城',
    targetX: 100, targetY: 100, arriveAt: Date.now() - 1,
    army: { infantry: 10 }, action: 'plunder', expanded: true
  }];

  G.World.renderAlerts(view);

  assert.match(view.innerHTML, /进入战斗/);
  assert.match(view.innerHTML, /Game\.World\.startIncomingBattle\(0\)/);
  G.World.startIncomingBattle(0);
  assert.equal(openedMarchId, 19);
});

test('incoming events force a fresh state and cancellation does not announce another attack', async () => {
  const G = incomingContext();
  let calls = 0;
  const attack = { id: 'march-8', fromName: '敌方', arriveAt: Date.now() + 60000 };
  G.API = { getGameState(force) {
    assert.equal(force, true);
    calls++;
    return Promise.resolve({ resources: {}, world: { marches: [], incoming: calls === 1 ? [attack] : [] } });
  } };
  G.Core.render = () => G.Main.renderNavBar();
  G.WS.emit('incoming', { event: 'started', fromName: '敌方' });
  await new Promise(resolve => setImmediate(resolve));
  assert.match(G._toast, /敌军来袭/);
  assert.equal(G.state.world.incoming.length, 1);
  assert.match(G._nav, /class="navitem active alert"/);
  G.WS.emit('incoming', { event: 'cancelled', fromName: '敌方' });
  await new Promise(resolve => setImmediate(resolve));
  assert.match(G._toast, /已撤回/);
  assert.equal(G.state.world.incoming.length, 0);
  assert.equal(calls, 2);
});

test('军情倒计时每秒更新，延迟后按真实时间校正，重新渲染和离开页面清理计时器', () => {
  let now = 100000;
  let nextId = 0;
  const timers = new Map();
  const context = sandbox({
    Date: class extends Date { static now() { return now; } },
    setInterval(fn, ms) { assert.equal(ms, 1000); timers.set(++nextId, fn); return nextId; },
    clearInterval(id) { timers.delete(id); }
  });
  for (const file of ['js/data.js', 'js/core.js', 'js/world.js']) load(context, file);
  const G = context.Game;
  G.Core.route = 'alerts';
  G.Core.state = { buildings: {}, world: { marches: [], incoming: [
    { id: 'march-1', fromName: '敌军', arriveAt: 103000, expanded: true, army: { infantry: 10 } }
  ] } };
  const nodes = [0, 1].map(() => ({ textContent: '3秒', getAttribute() { return '103000'; } }));
  let renders = 0;
  let html = '';
  const view = { isConnected: true, querySelectorAll() { return nodes; },
    set innerHTML(value) { renders++; html = value; } };
  G.World.renderAlerts(view);
  assert.equal((html.match(/data-arrive-at="103000"/g) || []).length, 2);
  assert.equal(timers.size, 1);
  for (const [time, expected] of [[101000, '2秒'], [102000, '1秒'], [103000, '已到达，等待战斗结果']]) {
    now = time;
    [...timers.values()][0]();
    nodes.forEach(node => assert.equal(node.textContent, expected));
  }
  assert.equal(renders, 1, '计时器只能修改数字，不能重建情报列表');
  assert.equal(G.Core.state.world.incoming.length, 1, '前端归零不能自行结算或移除来袭');
  G.World.renderAlerts(view);
  assert.equal(timers.size, 1, '后端刷新后不能叠加计时器');
  now += 20000;
  [...timers.values()][0]();
  assert.equal(nodes[0].textContent, '已到达，等待战斗结果');
  G.Core.route = 'home';
  [...timers.values()][0]();
  assert.equal(timers.size, 0);
  G.Core.route = 'alerts';
  G.World.startAlertTimer(view);
  view.isConnected = false;
  [...timers.values()][0]();
  assert.equal(timers.size, 0);
});

test('导航栏将科技和切换置于末尾，军情显示为情报', () => {
  const context = sandbox();
  for (const file of ['js/data.js', 'js/core.js', 'js/cities.js', 'js/main-view.js']) load(context, file);
  const G = context.Game;
  G.state = { player: { id: 1, cityName: '主城' }, world: { incoming: [] } };
  G.Core.state = G.state;
  G.Core.route = 'home';
  const navHtml = G.MainView.navBar();

  const labels = [...navHtml.matchAll(/class="navlabel">([^<]+)<\/span>/g)].map(m => m[1]);
  assert.deepEqual(labels, [
    '首页', '资源', '军事', '军队', '战术', '地图', '情报', '战报', '邮件', '任务', '军团', '仓库', '科技', '切换'
  ]);
  assert.doesNotMatch(navHtml, /军情/);
});
