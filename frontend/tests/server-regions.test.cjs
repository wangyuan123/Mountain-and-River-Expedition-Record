const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(fetchResponse) {
  const stored = new Map([['wargame_token', 'legacy-token']]);
  const requests = [];
  const listeners = {};
  const sockets = [];
  class Socket { constructor(url) { this.url = url; this.readyState = 0; sockets.push(this); } close() { this.readyState = 3; } }
  const context = vm.createContext({ console, Date, Math, Promise, Map, Set, TypeError,
    location: { port: '80', hostname: 'localhost', host: 'localhost', protocol: 'http:' },
    document: { getElementById: () => null, querySelectorAll: () => [] },
    localStorage: { getItem: key => stored.get(key) || null, setItem: (key, value) => stored.set(key, value), removeItem: key => stored.delete(key) },
    fetch: async (url, options) => {
      requests.push({ url, options });
      if (fetchResponse) return fetchResponse();
      return { ok: true, status: 200, text: async () => JSON.stringify({ token: 'new-token', username: 'alice' }) };
    },
    WebSocket: Socket, setTimeout, clearTimeout,
    addEventListener: (name, handler) => { listeners[name] = handler; }, Game: {}
  });
  context.window = context;
  const load = name => vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', name), 'utf8'), context);
  require('./load-constants.cjs')(context);
  load('servers.js'); load('api-client.js'); load('api.js'); load('ws-client.js');
  return { game: context.Game, stored, requests, sockets, listeners };
}

test('江苏一区沿用旧令牌，注册和登录明确提交大区', async () => {
  const { game, stored, requests } = setup();
  assert.equal(game.Servers.current().name, '江苏一区');
  assert.equal(game.API.getToken(), 'legacy-token');
  await game.API.register('alice', 'password', 'agreement');
  assert.equal(requests[0].url, '/api/auth/register');
  assert.equal(requests[0].options.headers['X-Game-Server'], 'jiangsu-1');
  assert.equal(JSON.parse(requests[0].options.body).serverId, 'jiangsu-1');
  assert.equal(stored.get('wargame_token_server'), 'jiangsu-1');
  await game.API.login('alice', 'password');
  assert.equal(JSON.parse(requests[1].options.body).serverId, 'jiangsu-1');
});

test('切换大区清理旧令牌并将 API 路由到独立实例', async () => {
  const { game, stored, requests, sockets } = setup();
  game.Servers.list.push({ id: 'jiangsu-2', name: '江苏二区', apiBase: '/regions/jiangsu-2/api', wsBase: '/regions/jiangsu-2/ws' });
  assert.throws(() => game.Servers.select('missing'), /不存在/);
  game.Servers.select('jiangsu-2');
  assert.equal(stored.has('wargame_token'), false);
  assert.equal(game.API.getToken(), '');
  assert.equal(game.API.client.baseURL, '/regions/jiangsu-2/api');
  await game.API.login('alice', 'password');
  assert.equal(requests[0].url, '/regions/jiangsu-2/api/auth/login');
  assert.equal(requests[0].options.headers['X-Game-Server'], 'jiangsu-2');
  assert.equal(JSON.parse(requests[0].options.body).serverId, 'jiangsu-2');
  assert.equal(stored.get('wargame_token_server'), 'jiangsu-2');
  game.WS.connect();
  assert.match(sockets[0].url, /^ws:\/\/localhost\/regions\/jiangsu-2\/ws\/game\?token=new-token/);
  game.Servers.select('jiangsu-1');
  assert.equal(game.API.getToken(), '');
});

test('另一个标签切换大区后当前标签清除旧区页面状态', () => {
  const { game, listeners } = setup();
  game.Servers.list.push({ id: 'jiangsu-2', name: '江苏二区', apiBase: '/regions/jiangsu-2/api', wsBase: '/regions/jiangsu-2/ws' });
  let renders = 0;
  game.Core = { state: { player: { id: 1 } }, route: 'home', render() { renders++; } };
  listeners.storage({ key: 'wargame_server', newValue: 'jiangsu-2' });
  assert.equal(game.Servers.current().id, 'jiangsu-2');
  assert.equal(game.API.getToken(), '');
  assert.equal(game.Core.route, 'login');
  assert.equal(game.Core.state, null);
  assert.equal(renders, 1);
});

test('切区期间旧区登录响应不能写入新区令牌', async () => {
  let finish;
  const response = new Promise(resolve => { finish = resolve; });
  const { game, stored } = setup(() => response);
  game.Servers.list.push({ id: 'jiangsu-2', name: '江苏二区', apiBase: '/regions/jiangsu-2/api', wsBase: '/regions/jiangsu-2/ws' });
  const login = game.API.login('alice', 'password');
  game.Servers.select('jiangsu-2');
  finish({ ok: true, status: 200, text: async () => JSON.stringify({ token: 'old-region-token', username: 'alice' }) });
  await assert.rejects(login, /已忽略旧页面响应/);
  assert.equal(stored.has('wargame_token'), false);
  assert.equal(game.API.getToken(), '');
});
