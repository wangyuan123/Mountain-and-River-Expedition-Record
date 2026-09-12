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
