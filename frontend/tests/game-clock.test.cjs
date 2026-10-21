const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  let wall = 1700000000000;
  let monotonic = 0;
  const context = vm.createContext({ Date: class extends Date { static now() { return wall; } },
    performance: { now() { return monotonic; } },
    document: { querySelectorAll() { return []; }, getElementById() { return null; } },
    console, setTimeout, clearTimeout, setInterval() {}, clearInterval() {},
    localStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
    location: { port: '80', hostname: 'localhost' } });
  context.window = context;
  function load(file) { vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/', file), 'utf8'), context); }
  load('constants.js'); load('ws-client.js'); load('api-client.js');
  return { context, G: context.Game, advance(ms) { wall += ms; monotonic += ms; }, advanceWall(ms) { wall += ms; } };
}

test('地图动画在已确认的游戏时间之间逐帧移动，业务时间不外推', () => {
  const { G, advance } = setup();
  G.GameTime.sync(10000);
  advance(1000);
  G.GameTime.sync(11000);
  const frames = [];
  for (let frame = 0; frame < 60; frame++) {
    frames.push(G.GameTime.animationNow());
    advance(1000 / 60);
  }
  assert.equal(new Set(frames).size, 60, '服务器一秒一个样本时，动画每帧仍需移动');
  assert.equal(frames[0], 10000);
  assert.ok(frames.every(time => time >= 10000 && time <= 11000));
  assert.equal(G.gameNow(), 11000, '动画插值不能改变抵达和结算所用时间');
  advance(2 * 86400000);
  assert.equal(G.GameTime.animationNow(), 11000, '失联后不能超出最后确认的时间');
});

for (const intervals of [
  [1020, 1035, 1010, 1080, 1020, 1040, 1180, 1005],
  [1000, 1010, 2020, 1005, 1000, 2010, 980, 1000]
]) test('服务器检查点有推送抖动时，预热后的行军动画不会周期性停顿：' + intervals.join(','), () => {
  const { G, advance } = setup();
  let receivedAt = 0, checkpoint = 10000, previous = checkpoint, pausedFrames = 0;
  G.GameTime.sync(checkpoint);
  for (let sample = 0; sample < 32; sample++) {
    const interval = intervals[sample % intervals.length];
    // 每次到帧前送达同一检查点的 HTTP/WS 响应，模拟并发和乱序。
    for (let frame = 1; frame <= 72; frame++) {
      advance(interval / 72);
      receivedAt += interval / 72;
      G.GameTime.sync(checkpoint);
      G.GameTime.sync(checkpoint - 100);
      const current = G.GameTime.animationNow();
      assert.ok(current >= previous && current <= checkpoint, '动画必须单调且不超出已确认时间');
      if (sample >= 4 && current === previous) pausedFrames++;
      previous = current;
    }
    checkpoint = 10000 + receivedAt;
    G.GameTime.sync(checkpoint);
  }
  assert.equal(pausedFrames, 0, '推送晚几十至几百毫秒时，不能每轮追平样本后原地等待');
  const beforeOutage = G.GameTime.animationNow();
  advance(5000);
  assert.equal(G.GameTime.animationNow(), checkpoint, '没有新样本时缓冲耗尽后必须停止');
  assert.ok(beforeOutage < checkpoint);
});

test('相同及迟到样本不打断动画，墙钟校准不改变动画进度', () => {
  const { G, advance, advanceWall } = setup();
  G.GameTime.sync(10000);
  advance(1000);
  G.GameTime.sync(11000);
  advance(500);
  const halfway = G.GameTime.animationNow();
  assert.ok(halfway > 10000 && halfway < 11000);
  advanceWall(86400000);
  assert.equal(G.GameTime.animationNow(), halfway);
  for (const sample of [11000, 10500, null, '', 'invalid']) G.GameTime.sync(sample);
  advance(750);
  assert.equal(G.GameTime.animationNow(), 11000, '重复响应不能反复重启动画造成停滞');
});

test('断线重连的动画从停止处继续，切换账号清除旧动画时间', () => {
  const { G, advance } = setup();
  G.GameTime.sync(10000);
  advance(2 * 86400000);
  assert.equal(G.GameTime.animationNow(), 10000);
  G.GameTime.sync(20000);
  advance(500);
  assert.ok(G.GameTime.animationNow() > 10000 && G.GameTime.animationNow() < 20000);
  advance(750);
  assert.equal(G.GameTime.animationNow(), 20000);
  G.GameTime.reset();
  G.GameTime.sync(5000);
  assert.equal(G.GameTime.animationNow(), 5000);
  assert.equal(G.gameNow(), 5000);
});

test('抖动缓冲有上限，重连从停点继续且账号切换清除采样间隔', () => {
  const { G, advance } = setup();
  G.GameTime.sync(10000);
  advance(2000); G.GameTime.sync(12000);
  advance(1500);
  assert.ok(G.GameTime.animationNow() < 12000, '较慢推送应保留更长播放缓冲');
  advance(1000);
  assert.equal(G.GameTime.animationNow(), 12000, '缓冲耗尽后不能继续向未确认时间推进');
  advance(86400000);
  G.GameTime.sync(14000);
  assert.equal(G.GameTime.animationNow(), 12000, '重连不能瞬移或倒退');
  advance(2500);
  assert.equal(G.GameTime.animationNow(), 14000, '长断线不能无限扩大播放缓冲');
  G.GameTime.reset(); G.GameTime.sync(5000);
  advance(1000); G.GameTime.sync(6000);
  advance(1250);
  assert.equal(G.GameTime.animationNow(), 6000, '账号切换后重新采样，不能沿用上个账号的慢速节奏');
});

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
