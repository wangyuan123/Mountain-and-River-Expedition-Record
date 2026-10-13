const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
function setup() {
  const calls = [], listeners = {}, timers = new Map(); let nextTimer = 1, token = 'one';
  const view = { innerHTML: '', scrollTop: 0, querySelector() { return this.innerHTML.includes('pvp-page') ? {} : null; } };
  const defaults = { infantry: { action: 'ADVANCE' }, truck: { action: 'HOLD' } };
  const snapshot = { army: { infantry: 100 }, defaults, power: 1000, chief: { name: '' }, tech: {}, commanderName: '将领', playerName: '玩家' };
  let status = { status: 'IDLE', version: 1, region: 'jiangsu-1', serverTime: Date.now() };
  let battle = { ...status, status: 'BATTLING', sessionId: 's', side: 'B', round: 1, mine: snapshot, enemy: snapshot, myArmy: { infantry: 100 }, enemyArmy: { infantry: 100 }, myPositions: { infantry: 0 }, enemyPositions: { infantry: 1000 }, initialDistance: 1000, loaded: true, draft: {}, revision: 0, deadlineAt: Date.now() + 15000 };
  let handler;
  const Game = {
    Core: { route: 'pvp', views: {} }, Constants: { pvp: { heartbeatMs: 10000, queuePollMs: 3000, battlePollMs: 2000 } },
    DATA: { units: { infantry: { name: '步兵', branch: 'land' }, truck: { name: '卡车', branch: 'land' } }, officerSkills: {} },
    escapeHtml: String, WS: { connected: true, on(name, fn) { listeners[name] = fn; } },
    Battle: { renderCommandBonusPanel() { return ''; }, tacticalMarkerRows() { return ['infantry']; }, renderTacticalRangeBeams() { return ''; }, renderTacticalMarkers(army, positions) { return '<span data-position="' + positions.infantry + '"></span>'; } },
    API: { client: { serverId: 'jiangsu-1', isLoggedIn() { return !!token; }, getToken() { return token; }, request(method, url, body, options) {
      calls.push({ method, url, body, options }); if (handler) { const r = handler(method, url, body); if (r) return r; }
      if (url.includes('/status')) return Promise.resolve(JSON.parse(JSON.stringify(status)));
      if (url.includes('/battle?')) return Promise.resolve(JSON.parse(JSON.stringify(battle)));
      if (url.includes('/lobby')) return Promise.resolve({ preset: { citySlot: 0, battlefield: 'LAND_AIR', army: { infantry: 100 }, commanderId: null, defaults }, presetVersion: 0, record: { wins: 0, losses: 0, draws: 0 }, status, reports: [], available: { cities: [{ slot: 0, name: '主城' }], army: { infantry: 100, truck: 30 }, cap: 1000, officers: [], chief: '无' } });
      if (url.includes('/preset')) return Promise.resolve({ status, snapshot, presetVersion: 1 });
      if (url.includes('/queue')) { status = { ...status, status: 'QUEUED', version: 2, ticketId: 't', queuedAt: Date.now() - 123000, snapshot }; return Promise.resolve(status); }
      if (url.includes('/orders')) { battle = { ...battle, revision: body.revision, draft: body.orders, locked: body.lock, version: battle.version + 1 }; return Promise.resolve(battle); }
      return Promise.resolve(battle);
    } } },
    go(route) { this.Core.route = route; this.Pvp.render(view); }
  };
  const context = vm.createContext({ window: { Game, crypto: { randomUUID() { return 'request_' + nextTimer++; } }, confirm() { return true; } }, document: { activeElement: null, getElementById(id) { return id === 'view' ? view : null; } }, setInterval(fn) { timers.set(nextTimer, fn); return nextTimer++; }, setTimeout(fn) { timers.set(nextTimer, fn); return nextTimer++; }, clearTimeout(id) { timers.delete(id); }, Date, console });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/pvp.js'), 'utf8'), context);
  return { Game, calls, view, timers, listeners, get status() { return status; }, set status(s) { status = s; }, get battle() { return battle; }, set battle(b) { battle = b; }, setHandler(h) { handler = h; }, setToken(t) { token = t; } };
}
async function settle() { for (let i = 0; i < 15; i++) await Promise.resolve(); }

test('lobby persists input across world refresh and queues only after versioned preset save', async () => {
  const s = setup(); await s.Game.Pvp.start(); s.Game.Pvp.render(s.view); await settle();
  s.Game.Pvp.setCount('infantry', '75'); s.Game.Pvp.setDefault('infantry', 'RETREAT'); s.Game.Pvp.render(s.view);
  assert.match(s.view.innerHTML, /value="75"/); assert.match(s.view.innerHTML, /RETREAT" selected/);
  s.Game.Pvp.queue(); await settle();
  const writes = s.calls.filter(c => c.method === 'POST'); assert.equal(writes[0].url, '/game/pvp/preset'); assert.equal(writes[1].url, '/game/pvp/queue');
  assert.equal(writes[1].body.presetVersion, 1); assert.equal(writes[1].body.army.infantry, 75);
  assert.match(s.view.innerHTML, /正在寻找旗鼓相当/); assert.match(s.view.innerHTML, /取消匹配/);
  assert.ok(writes.every(c => c.options.retry === 0));
});

test('accepted session automatically opens mirrored command panel and reconnect restores current round', async () => {
  const s = setup(); s.Game.Core.route = 'home'; s.status = { status: 'BATTLING', sessionId: 's', version: 10, region: 'jiangsu-1', serverTime: Date.now() }; s.battle.version = 10;
  await s.Game.Pvp.start(); await settle(); assert.equal(s.Game.Core.route, 'pvp'); assert.match(s.view.innerHTML, /data-position="0"/); assert.match(s.view.innerHTML, /data-position="1000"/);
  s.battle = { ...s.battle, round: 4, version: 12 }; s.status.version = 12; s.listeners.connected(); await settle(); assert.match(s.view.innerHTML, /第 4 \/ 24 回合/);
});

test('draft change saves revision and locking alone shows enemy preparation without advancing', async () => {
  const s = setup(); s.status = { status: 'BATTLING', sessionId: 's', version: 10, region: 'jiangsu-1', serverTime: Date.now() }; s.battle.version = 10;
  await s.Game.Pvp.start(); await settle(); s.Game.Pvp.setOrder('infantry', 'action', 'RETREAT');
  const callback = [...s.timers.values()].at(-1); callback(); await settle();
  const saved = s.calls.find(c => c.url.endsWith('/orders')); assert.equal(saved.body.revision, 1); assert.equal(saved.body.lock, false); assert.equal(saved.body.orders.infantry.action, 'RETREAT');
  assert.match(s.view.innerHTML, /指令已保存/);
  s.Game.Pvp.lock(); await settle(); const locked = s.calls.filter(c => c.url.endsWith('/orders')).at(-1);
  assert.equal(locked.body.revision, 2); assert.equal(locked.body.lock, true); assert.match(s.view.innerHTML, /我方已锁定/); assert.match(s.view.innerHTML, /第 1 \/ 24 回合/);
});

test('uncertain mutation queries state first and never automatically replays write', async () => {
  const s = setup(); s.status = { status: 'QUEUED', ticketId: 't', snapshot: { army: {}, chief: {}, defaults: {} }, queuedAt: Date.now(), version: 4, region: 'jiangsu-1' };
  await s.Game.Pvp.start(); await settle();
  s.setHandler((method, url) => { if (url.endsWith('/cancel')) return Promise.reject(Object.assign(new Error('结果尚未确认'), { uncertain: true })); });
  await s.Game.Pvp.cancel(); await settle();
  assert.equal(s.calls.filter(c => c.url.endsWith('/cancel')).length, 1); assert.ok(s.calls.at(-1).url.endsWith('/status')); assert.match(s.view.innerHTML, /使用原请求再次确认/);
});

test('old region websocket and late account responses cannot open another account session', async () => {
  const s = setup(); await s.Game.Pvp.start(); const count = s.calls.length;
  s.listeners.pvpBattleReady({ region: 'other', version: 100, sessionId: 'bad' }); await settle(); assert.equal(s.calls.length, count);
  let resolve;
  s.setHandler((method, url) => url.endsWith('/status') ? new Promise(r => { resolve = r; }) : null);
  s.listeners.connected(); await settle(); s.setToken('two');
  resolve({ status: 'BATTLING', sessionId: 'bad', region: 'jiangsu-1', version: 99 }); await settle();
  assert.ok(!s.calls.some(c => c.url.includes('sessionId=bad')));
});

test('a websocket update arriving during status read is queried again before loading deadline', async () => {
  const s = setup(); await s.Game.Pvp.start(); let resolve, once = true;
  s.setHandler((method, url) => {
    if (url.endsWith('/status') && once) { once = false; return new Promise(r => { resolve = r; }); }
    return null;
  });
  s.listeners.connected(); await settle();
  s.status = { status: 'LOADING', sessionId: 's', version: 8, region: 'jiangsu-1', deadlineAt: Date.now() + 20000 };
  s.battle = { ...s.battle, status: 'LOADING', loaded: false, version: 8 };
  s.listeners.pvpBattleReady({ ...s.status });
  resolve({ status: 'QUEUED', version: 7, region: 'jiangsu-1', ticketId: 't', snapshot: { army: {}, chief: {} }, queuedAt: Date.now() });
  await settle(); [...s.timers.values()].at(-1)(); await settle();
  assert.ok(s.calls.some(c => c.url.endsWith('/loaded')));
});

test('pending request holds immutable original payload when the player changes lobby inputs', async () => {
  const s = setup(); await s.Game.Pvp.start(); s.Game.Pvp.render(s.view); await settle();
  let sent;
  s.setHandler((method,url,body) => {
    if (url.endsWith('/preset')) { sent = body; return Promise.reject(Object.assign(new Error('network'), { uncertain: true })); }
    return null;
  });
  await s.Game.Pvp.savePreset(); s.Game.Pvp.setCount('infantry','20');
  assert.equal(sent.army.infantry,100);
});
