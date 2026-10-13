const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function setup() {
  let now = 1_000_000, nextTimer = 0, token = 'player-one';
  const timers = new Map(), modals = [], calls = [], toasts = [], routes = [];
  const march = { id: 17, version: 4, returning: false, startAt: now - 120000, arriveAt: now + 1761000,
    fromX: 0, fromY: 105, targetX: 377, targetY: 248, targetKind: 'npc', targetName: '雇佣兵营 Lv.29',
    action: 'scout', army: { scout: 50 }, outboundAccelerated: false, returnAccelerated: false };
  const state = { player: { id: 1, activeCityId: 1 }, items: { marchOrd: 3 }, world: { marches: [march], incoming: [] } };
  const document = { activeElement: null, getElementById() { return null; }, querySelectorAll() { return []; } };
  const body = { appendChild(mask) { modals.push(mask); mask.parentNode = body; },
    removeChild(mask) { modals.splice(modals.indexOf(mask), 1); mask.parentNode = null; } };
  document.body = body;
  document.createElement = () => {
    const nodes = {};
    return { nodes, setAttribute() {}, querySelector(selector) {
      if (!nodes[selector]) nodes[selector] = { disabled: false, textContent: '', focus() { document.activeElement = this; } };
      return nodes[selector];
    } };
  };
  const context = vm.createContext({ console, Promise, Math, Number, parseInt,
    Date: class extends Date { static now() { return now; } }, document,
    setInterval(fn) { timers.set(++nextTimer, fn); return nextTimer; }, clearInterval(id) { timers.delete(id); },
    Game: { fmt: String, escapeHtml: value => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;'),
      toast: message => toasts.push(message), go(route) { routes.push(route); this.Core.route = route; },
      Core: { route: 'alerts', state, views: {}, render() {} },
      API: { getToken: () => token, speedUpMarch(...args) {
        return new Promise((resolve, reject) => calls.push({ args, resolve, reject }));
      } }
    }
  });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'icons.js', 'world.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  return { context, game: context.Game, state, march, modals, calls, toasts, routes, timers,
    tick(milliseconds = 0) { now += milliseconds; for (const fn of [...timers.values()]) fn(); },
    switchToken(value) { token = value; },
    open() { context.Game.World.speedUpMarch(march.id); return modals.at(-1); }
  };
}

const flush = () => new Promise(setImmediate);

test('alerts expose stage-specific acceleration and disable an already accelerated leg', () => {
  const { game, march, state } = setup();
  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /加速行军/);
  march.returning = true;
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /加速回城/);
  march.returnAccelerated = true;
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /disabled>已加速/);
  assert.doesNotMatch(view.innerHTML, /data-accelerate-id/);
  state.world.marches = [{ ...march, returning: false, gathering: true }];
  game.World.renderAlerts(view);
  assert.doesNotMatch(view.innerHTML, /data-accelerate-id/);
});

test('only moving snapshots with version and more than ten seconds remaining are eligible', () => {
  const { game, march } = setup();
  const now = 1_000_000;
  assert.equal(game.World.canAccelerateMarch(march, now), true);
  for (const patch of [{ stationed: true }, { inBattle: true }, { waitingForBattle: true }, { battleId: 9 },
    { gathering: true }, { gatherStopped: true }, { version: undefined }, { startAt: now + 1 },
    { arriveAt: now + 10000 }, { arriveAt: now - 1 }, { outboundAccelerated: true }]) {
    assert.equal(game.World.canAccelerateMarch({ ...march, ...patch }, now), false, JSON.stringify(patch));
  }
  assert.equal(game.World.canAccelerateMarch({ ...march, returning: true, gathering: true, gatherStopped: true }, now), true);
});

test('confirmation previews target, inventory and exact screenshot example; cancellation spends nothing', () => {
  const f = setup(), modal = f.open();
  assert.match(modal.innerHTML, /雇佣兵营 Lv.29.*377,248/);
  assert.equal(modal.nodes['[data-march-before]'].textContent, '29分21秒');
  assert.equal(modal.nodes['[data-march-after]'].textContent, '19分34秒');
  assert.equal(modal.nodes['[data-march-saved]'].textContent, '9分47秒');
  assert.equal(modal.nodes['[data-march-stock]'].textContent, '行军令：消耗 1 枚，库存 3 枚');
  modal.nodes['[data-march-cancel]'].onclick();
  assert.equal(f.calls.length, 0);
  assert.equal(f.modals.length, 0);
  assert.equal(f.timers.size, 0);
});

test('zero inventory leads to shop without buying or sending an acceleration request', () => {
  const f = setup(); f.state.items.marchOrd = 0;
  const modal = f.open();
  assert.equal(modal.nodes['[data-march-confirm]'].textContent, '前往商城');
  modal.nodes['[data-march-confirm]'].onclick();
  assert.deepEqual(f.routes, ['shop']);
  assert.equal(f.calls.length, 0);
});

test('double confirmation and another open during submission send one request', async () => {
  const f = setup(), modal = f.open(), ok = modal.nodes['[data-march-confirm]'];
  ok.onclick(); ok.onclick(); f.game.World.speedUpMarch(17);
  assert.equal(f.modals.length, 1);
  assert.equal(f.calls.length, 1);
  assert.deepEqual(f.calls[0].args, [17, false, 4]);
  assert.equal(ok.disabled, true);
  assert.equal(modal.nodes['[data-march-cancel]'].disabled, true);
  f.calls[0].resolve({ message: '行军已加速' }); await flush();
  assert.equal(f.modals.length, 0);
  assert.equal(f.timers.size, 0);
  assert.deepEqual(f.toasts, ['行军已加速']);
});

for (const change of ['stage', 'version', 'arrival', 'missing']) {
  test(`confirmation invalidates after ${change} changes and cannot consume`, () => {
    const f = setup(), modal = f.open();
    if (change === 'stage') f.march.returning = true;
    if (change === 'version') f.march.version++;
    if (change === 'arrival') f.tick(1761000);
    if (change === 'missing') f.state.world.marches = [];
    f.tick();
    assert.equal(modal.nodes['[data-march-confirm]'].disabled, true);
    modal.nodes['[data-march-confirm]'].onclick();
    assert.equal(f.calls.length, 0);
  });
}

test('uncertain failure refreshes once and never revives or replays the old confirmation', async () => {
  const f = setup(); let refreshes = 0, applied = 0;
  f.game.API.getGameState = () => { refreshes++; return Promise.resolve(f.state); };
  f.game.API.applyState = () => applied++;
  const modal = f.open(), ok = modal.nodes['[data-march-confirm]'];
  ok.onclick(); f.calls[0].reject(new Error('结果尚未确认')); await flush();
  f.tick(1000); ok.onclick();
  assert.equal(ok.disabled, true);
  assert.equal(f.calls.length, 1);
  assert.equal(refreshes, 1); assert.equal(applied, 1);
  assert.match(modal.nodes['[data-march-status]'].textContent, /结果尚未确认/);
  modal.nodes['[data-march-cancel]'].onclick();
  assert.equal(f.timers.size, 0);
});

for (const change of ['city', 'account', 'route']) {
  test(`switching ${change} disposes pending confirmation and cannot revive it`, async () => {
    const f = setup(); let refreshes = 0;
    f.game.API.getGameState = () => { refreshes++; return Promise.resolve(f.state); };
    const modal = f.open(); modal.nodes['[data-march-confirm]'].onclick();
    if (change === 'city') f.state.player.activeCityId = 2;
    if (change === 'account') f.switchToken('player-two');
    if (change === 'route') f.game.Core.route = 'home';
    f.tick();
    assert.equal(f.modals.length, 0);
    f.calls[0].reject(new Error('旧页面响应')); await flush();
    assert.equal(f.modals.length, 0); assert.equal(refreshes, 0);
  });
}

test('alert countdown disables acceleration when the remaining time reaches ten seconds', () => {
  const f = setup();
  const button = { disabled: false, getAttribute() { return '17'; } };
  const countdown = { textContent: '', getAttribute() { return String(f.march.arriveAt); } };
  const view = { isConnected: true, querySelectorAll(selector) {
    return selector === '[data-arrive-at]' ? [countdown] : selector === '[data-accelerate-id]' ? [button] : [];
  } };
  f.game.World.startAlertTimer(view);
  f.tick(1751000);
  assert.equal(button.disabled, true);
});

test('API submits stage/version once and applies authoritative response inventory and clocks', async () => {
  const requests = [];
  const state = { player: { id: 1 }, items: { marchOrd: 2 }, world: { marches: [{ id: 17, arriveAt: 3000 }] } };
  const context = vm.createContext({ Game: { Core: {}, ApiClient: class {
    getToken() { return 'player-one'; }
    post(url, body) { requests.push({ url, body }); return Promise.resolve({ success: true, state }); }
  } } });
  context.window = context;
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/api.js'), 'utf8'), context);
  await context.Game.API.speedUpMarch(17, true, 4);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/game/world/speedup-march');
  assert.deepEqual({ ...requests[0].body }, { marchId: 17, returning: true, version: 4 });
  assert.equal(context.Game.Core.state.items.marchOrd, 2);
  assert.equal(context.Game.Core.state.world.marches[0].arriveAt, 3000);
});
