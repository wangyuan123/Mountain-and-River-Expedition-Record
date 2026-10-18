const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function snapshot(overrides = {}) {
  return { enrolled: true, paused: false, done: false, completed: 0,
    objectives: [], supplies: [], current: null, checks: {}, ...overrides };
}

function response(data) {
  const body = JSON.stringify(data);
  return { ok: true, status: 200, text: async () => body };
}

/** 使用实际登录、请求和连接模块，只控制网络响应顺序与浏览器环境。 */
function runtime() {
  const nodes = new Map();
  const timers = new Map();
  const requests = [];
  const sockets = [];
  const events = [];
  const storage = new Map([['wargame_token', 'old-account'], ['wargame_token_server', 'jiangsu-1']]);
  let nextTimer = 0;
  const gameState = { player: { id: 77, activeCityId: 11, citySlot: 0 }, resources: { steel: 100 }, world: {} };
  const screen = { inert: false };
  const view = { innerHTML: '', querySelectorAll: () => [], insertBefore(node) { nodes.set(node.id, node); } };
  const G = { DATA: {}, Core: { state: null, route: 'home', views: {}, init() {}, renderTop() {},
    render() { if (G.Onboarding) G.Onboarding.render(); } }, toast() {} };
  const context = vm.createContext({
    console, TypeError, Game: G, location: { port: '80', protocol: 'http:', host: 'localhost' },
    document: { readyState: 'loading', activeElement: null, addEventListener() {},
      querySelector: () => null, querySelectorAll: () => [],
      getElementById: id => id === 'view' ? view : id === 'screen' ? screen : nodes.get(id),
      createElement: () => ({ style: {}, setAttribute() {}, querySelectorAll: () => [],
        remove() { nodes.delete(this.id); } }) },
    localStorage: { getItem: key => storage.get(key) || null,
      setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
    setTimeout(callback, delay) { const id = ++nextTimer; timers.set(id, { callback, delay }); return id; },
    clearTimeout(id) { timers.delete(id); },
    setInterval(callback, delay) { const id = ++nextTimer; timers.set(id, { callback, delay }); return id; },
    clearInterval(id) { timers.delete(id); },
    fetch(url, options) {
      const request = { url, options };
      requests.push(request);
      if (url.endsWith('/game/onboarding')) {
        return new Promise((resolve, reject) => { request.resolve = resolve; request.reject = reject; });
      }
      const data = url.endsWith('/game/state') ? gameState :
        url.endsWith('/game/quest/list') ? { chapters: [] } : {};
      return Promise.resolve(response(data));
    },
    WebSocket: class {
      constructor(url) { this.url = url; this.readyState = 0; sockets.push(this); }
      open() { this.readyState = 1; this.onopen(); }
      close() { this.readyState = 3; this.onclose({ code: 1000 }); }
    }
  });
  context.window = context;
  require('./load-constants.cjs')(context);
  for (const file of ['api-client.js', 'api.js', 'ws-client.js', 'ws-handlers.js', 'save.js',
    'onboarding.js', 'main-quest.js', 'main.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context, { filename: file });
  }
  G.Main.renderNavBar = () => {};
  G.WS.on('connected', event => events.push(event));
  return { G, nodes, timers, sockets, events, screen, gameState, requests,
    reads: () => requests.filter(request => request.url.endsWith('/game/onboarding')) };
}

async function settle() { await new Promise(resolve => setImmediate(resolve)); }

function reconnect(r) {
  r.sockets.at(-1).close();
  const retry = r.timers.get(r.G.WS.reconnectTimer);
  assert.equal(retry.delay, 5000);
  retry.callback();
  r.sockets.at(-1).open();
}

test('first connection preserves login requests for unenrolled, paused and completed accounts', async () => {
  for (const data of [snapshot({ enrolled: false }), snapshot({ paused: true }), snapshot({ done: true })]) {
    const r = runtime();
    await r.G.Main.startGame();
    const pending = r.G.Onboarding.state.pending;
    const revision = r.G.API.client.cityRevision;
    r.sockets[0].open();
    r.reads()[0].resolve(response(data));
    await pending;
    assert.equal(r.G.API.client.cityRevision, revision);
    assert.equal(r.events[0].reconnected, false);
    assert.equal(r.reads().length, 1);
    assert.equal(r.G.Onboarding.state.error, '');
    assert.equal(r.G.Onboarding.state.data.enrolled, data.enrolled);
    assert.equal(r.nodes.has('onboardingBar'), false);
    assert.equal(r.screen.inert, false);
    assert.equal(r.G.Onboarding.state.timer, null);
  }
});

test('first connection still shows the guide and starts polling for enrolled accounts', async () => {
  const r = runtime();
  await r.G.Main.startGame();
  const pending = r.G.Onboarding.state.pending;
  r.sockets[0].open();
  r.reads()[0].resolve(response(snapshot()));
  await pending;
  assert.equal(r.reads().length, 1);
  assert.equal(r.G.Onboarding.state.error, '');
  assert.equal(r.nodes.has('onboardingBar'), true);
  assert.equal(r.screen.inert, false);
  assert.equal(r.timers.get(r.G.Onboarding.state.timer).delay, 5000);
});

test('reconnection invalidates the old city and silently rereads a pending guide', async () => {
  const r = runtime();
  await r.G.Main.startGame();
  const pending = r.G.Onboarding.state.pending;
  r.sockets[0].open();
  await settle();
  assert.equal(r.G.API.client.cityId, 11);
  const revision = r.G.API.client.cityRevision;
  r.gameState.player.activeCityId = 22;
  reconnect(r);
  assert.equal(r.events[1].reconnected, true);
  assert.equal(r.G.API.client.cityRevision, revision + 1);
  const stateRequest = r.requests.filter(request => request.url.endsWith('/game/state')).at(-1);
  assert.equal(stateRequest.options.headers['X-City-Id'], undefined);
  r.reads()[0].resolve(response(snapshot({ balances: { steel: 999 } })));
  await settle();
  assert.equal(r.G.API.client.cityId, 22);
  assert.equal(r.reads().length, 2);
  assert.equal(r.reads()[1].options.headers['X-City-Id'], '22');
  assert.equal(r.G.Onboarding.state.error, '');
  assert.equal(r.nodes.has('onboardingBar'), false);
  assert.equal(r.G.Core.state.resources.steel, 100);
  assert.equal(r.G.Onboarding.refresh(), r.G.Onboarding.state.pending);
  assert.equal(r.reads().length, 2);
  r.reads()[1].resolve(response(snapshot({ enrolled: false })));
  await pending;
  assert.equal(r.G.Onboarding.state.data.enrolled, false);
  assert.equal(r.G.Onboarding.state.pending, null);
  assert.equal(r.nodes.has('onboardingBar'), false);
  assert.equal(r.screen.inert, false);
});

test('a cancelled guide does not reread after logout or an account change', async () => {
  for (const change of [G => G.API.clearToken(), G => G.API.setToken('new-account')]) {
    const r = runtime();
    await r.G.Main.startGame();
    const pending = r.G.Onboarding.state.pending;
    change(r.G);
    r.reads()[0].resolve(response(snapshot()));
    await pending;
    assert.equal(r.reads().length, 1);
    assert.equal(r.G.Onboarding.state.data, null);
    assert.equal(r.G.Onboarding.state.error, '');
    assert.equal(r.nodes.has('onboardingBar'), false);
  }
});

test('a genuine error after the silent reread retains the retry entry', async () => {
  const r = runtime();
  await r.G.Main.startGame();
  const pending = r.G.Onboarding.state.pending;
  r.G.API.client.invalidateCityRequests();
  r.reads()[0].resolve(response(snapshot()));
  await settle();
  assert.equal(r.reads().length, 2);
  r.reads()[1].resolve({ ok: false, status: 503, text: async () => JSON.stringify({ error: '服务暂时不可用' }) });
  await pending;
  assert.equal(r.reads().length, 2);
  assert.equal(r.G.Onboarding.state.error, '服务暂时不可用');
  assert.match(r.nodes.get('onboardingBar').innerHTML, /重新检查/);
});

test('an intentional disconnect starts a fresh session without cancelling its guide', async () => {
  const r = runtime();
  await r.G.Main.startGame();
  r.sockets[0].open();
  r.reads()[0].resolve(response(snapshot({ enrolled: false })));
  await r.G.Onboarding.state.pending;
  r.G.WS.disconnect();
  r.G.API.setToken('next-account');
  await r.G.Main.startGame();
  const pending = r.G.Onboarding.state.pending;
  const revision = r.G.API.client.cityRevision;
  r.sockets[1].open();
  r.reads()[1].resolve(response(snapshot({ enrolled: false })));
  await pending;
  assert.equal(r.events[1].reconnected, false);
  assert.equal(r.G.API.client.cityRevision, revision);
  assert.equal(r.G.Onboarding.state.error, '');
  assert.equal(r.nodes.has('onboardingBar'), false);
});
