const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { randomUUID } = require('node:crypto');

function harness() {
  let elapsed = 0;
  const storage = new Map([['wargame_token', 'account-token']]);
  const calls = [], events = {}, nodes = {};
  const context = {
    console, TypeError, performance: { now: () => elapsed },
    localStorage: { getItem: k => storage.get(k) || null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) },
    document: { getElementById: id => nodes[id] || null, addEventListener: (key, fn) => { events[key] = fn; } },
    addEventListener: (key, fn) => { events[key] = fn; }, crypto: { randomUUID },
    setInterval() { return 1; }, clearInterval() {}, setTimeout() { return 1; }, clearTimeout() {},
    Game: { Core: { views: {}, history: [], render() {} }, escapeHtml: s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;'), toast() {} },
    fetch(url, options) {
      return new Promise(resolve => calls.push({ url, options, reply(data, status = 200) {
        resolve({ ok: status < 400, status, text: async () => JSON.stringify(data) });
      } }));
    }
  };
  context.window = context;
  vm.createContext(context);
  const load = file => vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  load('api-client.js');
  const client = new context.Game.ApiClient('/api');
  context.Game.API = { client, getToken: () => client.getToken(), getUsername: () => 'alice', isLoggedIn: () => !!client.getToken() };
  load('protection.js');
  return { G: context.Game, calls, events, nodes, storage, advance(ms) { elapsed += ms; } };
}
const permitted = () => ({ enabled: true, canPlay: true, serverNow: 100000, allowedUntil: 3700000, leaseUntil: 160000, minor: true });
const drain = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };

test('unverified clients cannot read gameplay but can access account services', async () => {
  const { G, calls } = harness();
  await assert.rejects(G.API.client.get('/game/state'), { code: 'PLAY_SESSION_EXPIRED' });
  assert.equal(calls.length, 0);
  const request = G.API.client.get('/auth/deletion-preview');
  calls[0].reply({ cooldownDays: 7 });
  assert.equal((await request).cooldownDays, 7);
});

test('game permission refusal clears world state but preserves account credentials', async () => {
  const { G, calls } = harness();
  G.Protection.adopt(permitted()); G.Protection.blocked = false;
  G.state = G.Core.state = { world: {} };
  const request = G.API.client.get('/game/state');
  calls[0].reply({ code: 'PLAY_WINDOW_CLOSED', error: '当前不在游戏时段' }, 403);
  await assert.rejects(request, { code: 'PLAY_WINDOW_CLOSED' });
  assert.equal(G.API.getToken(), 'account-token');
  assert.equal(G.Core.route, 'protection');
  assert.equal(G.state, null);
});

test('leaving during a pending session start cannot reopen gameplay', async () => {
  const { G, calls } = harness();
  const entering = G.Protection.enter();
  calls[0].reply(permitted()); await drain();
  assert.equal(calls[1].url, '/api/play-sessions');
  G.Protection.show(); calls[1].reply(permitted());
  assert.equal(await entering, false);
  assert.equal(G.Protection.blocked, true);
  assert.equal(G.Protection.session(), '');
});

test('a previous enter request cannot clear a newer pending enter request', async () => {
  const { G, calls } = harness();
  const old = G.Protection.enter(); G.Protection.stop();
  const current = G.Protection.enter();
  calls[0].reply(permitted()); await old;
  assert.equal(G.Protection.entering, current);
  calls[1].reply({ canPlay: false });
  assert.equal(await current, false);
});

test('elapsed monotonic time expires the lease and never automatically reclaims it', async () => {
  const { G, calls, advance } = harness();
  G.Protection.adopt(permitted()); G.Protection.blocked = false;
  advance(60000); G.Protection.tick();
  assert.equal(G.Protection.blocked, true);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/api/anti-addiction/status');
  calls[0].reply(permitted()); await drain();
  assert.equal(G.Protection.blocked, true);
  assert.equal(calls.some(c => c.url === '/api/play-sessions'), false);
});

test('late gameplay data and a replaced cross-tab session cannot restore the world', async () => {
  const { G, calls, events } = harness();
  G.Protection.adopt(permitted()); G.Protection.blocked = false;
  const request = G.API.client.get('/game/state');
  events.storage({ key: G.Protection.key(), oldValue: 'one', newValue: 'two' });
  calls[0].reply({ world: { secret: true } });
  await assert.rejects(request, /已忽略旧页面响应/);
  assert.equal(G.state, null);
  assert.equal(G.Protection.blocked, true);
});

test('guardian account names are escaped before rendering', async () => {
  const { G, calls, nodes } = harness();
  nodes.guardianPanel = {};
  G.Protection.guardian();
  calls[0].reply([{ playerId: 1, username: '<img src=x onerror=alert(1)>', usedSeconds: 0, dailyLimitSeconds: 3600, endMinute: 1260, chatAllowed: true }]);
  await drain();
  assert.match(nodes.guardianPanel.innerHTML, /&lt;img/);
  assert.doesNotMatch(nodes.guardianPanel.innerHTML, /<img/);
});

test('ordinary gameplay business errors do not revoke an otherwise valid session', async () => {
  const { G, calls } = harness();
  G.Protection.adopt(permitted()); G.Protection.blocked = false;
  const request = G.API.client.post('/game/shop/buy', {});
  calls[0].reply({ code: 'INSUFFICIENT_RESOURCES', error: '资源不足' }, 400);
  await assert.rejects(request, { code: 'INSUFFICIENT_RESOURCES' });
  assert.equal(G.Protection.canRequest(), true);
});
