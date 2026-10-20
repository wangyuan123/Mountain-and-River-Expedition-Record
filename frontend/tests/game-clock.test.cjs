const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  let wall = 1700000000000;
  const context = vm.createContext({ Date: class extends Date { static now() { return wall; } },
    document: { querySelectorAll() { return []; }, getElementById() { return null; } },
    console, setTimeout, clearTimeout, setInterval() {}, clearInterval() {},
    localStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
    location: { port: '80', hostname: 'localhost' } });
  context.window = context;
  function load(file) { vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/', file), 'utf8'), context); }
  load('constants.js'); load('ws-client.js'); load('api-client.js');
  return { context, G: context.Game, advance(ms) { wall += ms; } };
}

test('宕机后全部活动和进度保持最后服务器样本，重连继续而非按真实时间跳完', () => {
  const { G, advance } = setup();
  const before = G.gameNow();
  G.GameTime.sync(before);
  const deadlines = [15000, 60000, 3600000].map(duration => before + duration);
  advance(2 * 86400000);
  assert.deepEqual(deadlines.map(end => end - G.gameNow()), [15000, 60000, 3600000]);
  assert.equal(G.GameTime.toWallTime(deadlines[0]), before + 2 * 86400000 + 15000, '预计到达时刻应顺延停服时长');
  G.WS.handleMessage(JSON.stringify({ type: 'clock', gameTime: before + 1000 }));
  assert.deepEqual(deadlines.map(end => end - G.gameNow()), [14000, 59000, 3599000]);
  G.WS.handleMessage(JSON.stringify({ type: 'tick', gameTime: before + 500, data: {} }));
  assert.equal(G.gameNow(), before + 1000, '迟到响应不能倒退游戏时间');
});

test('API同步跨域游戏时间，切换账号和大区清除旧时钟', async () => {
  const { context, G } = setup();
  context.fetch = async () => ({ ok: true, status: 200, headers: { get: name => name === 'X-Game-Time' ? '100000' : null },
    text: async () => '{"ok":true}' });
  const api = new G.ApiClient('/api');
  await api.get('/game/state', { silent: true });
  assert.equal(G.gameNow(), 100000);
  api.setToken('new-token', 'new-player');
  G.GameTime.sync(50000);
  assert.equal(G.gameNow(), 50000);
  api.setServer({ id: 'another' });
  G.GameTime.sync(20000);
  assert.equal(G.gameNow(), 20000);
});
