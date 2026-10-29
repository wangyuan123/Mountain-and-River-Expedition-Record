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
      API: { getToken: () => token, previewMarchOrder() {
        return { then(callback) {
          callback({ version: march.version, returning: march.returning, ratio: 0.5,
            commanderName: '未任命', knowledge: 0, baseRatio: 0.5, knowledgeBonus: 0, cap: 0.8,
            confirmationToken: 'preview-token' });
          return { catch() {} };
        } };
      }, speedUpMarch(...args) {
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

test('asynchronous preview must complete before confirmation and is ignored after closing', async () => {
  const f = setup();
  let resolvePreview;
  f.game.API.previewMarchOrder = () => new Promise(resolve => { resolvePreview = resolve; });
  const modal = f.open();
  assert.equal(modal.nodes['[data-march-confirm]'].disabled, true);
  modal.nodes['[data-march-confirm]'].onclick();
  assert.equal(f.calls.length, 0);
  modal.nodes['[data-march-cancel]'].onclick();
  resolvePreview({ version: 4, returning: false, ratio: 0.8, confirmationToken: 'late' });
  await flush();
  assert.equal(f.modals.length, 0);
  assert.equal(f.calls.length, 0);
});

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

test('only moving snapshots with version and more than thirty seconds remaining are eligible', () => {
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
  assert.equal(modal.nodes['[data-march-before]'].textContent, '29分钟21秒');
  assert.equal(modal.nodes['[data-march-after]'].textContent, '14分钟41秒');
  assert.equal(modal.nodes['[data-march-saved]'].textContent, '14分钟41秒');
  assert.equal(modal.nodes['[data-march-stock]'].textContent, '极速行军令：消耗 1 枚，库存 3 枚');
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
  assert.deepEqual(f.calls[0].args, [17, false, 4, 'preview-token', 'marchOrd']);
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

test('alert countdown disables acceleration when the remaining time reaches thirty seconds', () => {
  const f = setup();
  const button = { disabled: false, getAttribute() { return '17'; } };
  const countdown = { textContent: '', getAttribute() { return String(f.march.arriveAt); } };
  const view = { isConnected: true, querySelectorAll(selector) {
    return selector === '[data-arrive-at]' ? [countdown] : selector === '[data-accelerate-id]' ? [button] : [];
  } };
  f.game.World.startAlertTimer(view);
  f.tick(1731000);
  assert.equal(button.disabled, true);
});

test('interception acceleration shortens the displayed deployment countdown while keeping the enemy crossing time', async () => {
  const f = setup(), now = 1_000_000;
  Object.assign(f.march, { action: 'intercept_scout', targetKind: 'incoming_march',
    startAt: now - 60000, deployAt: now + 120000, readyAt: now + 125000,
    arriveAt: now + 180000, interceptionOpening: 'ambush' });
  const view = { innerHTML: '', querySelectorAll: () => [] };
  f.game.Core.render = () => f.game.World.renderAlerts(view);
  f.game.Core.render();
  assert.match(view.innerHTML, /data-arrive-at="1120000"[^>]*>2分钟0秒<\/b> 后到位/);
  assert.match(view.innerHTML, /data-arrive-at="1180000"[^>]*>3分钟0秒<\/b> 后交火/);
  assert.match(view.innerHTML, /data-progress-deadline="1120000" data-progress-duration="180000"/);
  const modal = f.open();
  assert.match(modal.innerHTML, /加速出城部署/);
  assert.match(modal.innerHTML, /到位后等待敌军/);
  assert.equal(modal.nodes['[data-march-before]'].textContent, '2分钟0秒');
  assert.equal(modal.nodes['[data-march-after]'].textContent, '1分钟0秒');
  modal.nodes['[data-march-confirm]'].onclick();
  // 模拟 API 应用服务端快照：加速压缩部署时间轴，交火时间仍由敌军位置决定。
  Object.assign(f.march, { version: 5, startAt: now - 30000, deployAt: now + 60000,
    readyAt: now + 65000, outboundAccelerated: true });
  f.calls[0].resolve({ success: true, message: '行军已加速' }); await flush();
  assert.match(view.innerHTML, /data-arrive-at="1060000"[^>]*>1分钟0秒<\/b> 后到位/);
  assert.match(view.innerHTML, /data-arrive-at="1180000"[^>]*>3分钟0秒<\/b> 后交火/);
  assert.match(view.innerHTML, /data-progress-deadline="1060000" data-progress-duration="90000"/);
  assert.match(view.innerHTML, /disabled>已加速/);
});

test('deployed interceptors show complete movement progress and retain a separate combat countdown', () => {
  const f = setup(), now = 1_000_000;
  Object.assign(f.march, { action: 'intercept_scout', startAt: now - 60000,
    deployAt: now - 1000, readyAt: now + 4000, arriveAt: now + 30000, interceptionOpening: 'ambush' });
  const view = { innerHTML: '', querySelectorAll: () => [] };
  f.game.World.renderAlerts(view);
  assert.match(view.innerHTML, /设伏准备/);
  assert.match(view.innerHTML, /4秒<\/b> 后就绪/);
  assert.match(view.innerHTML, /30秒<\/b> 后交火/);
  assert.match(view.innerHTML, /data-progress-deadline="999000" data-progress-duration="59000">[^<]*100%/);
  assert.doesNotMatch(view.innerHTML, /data-accelerate-id/);
  f.march.interceptionOpening = 'urgent';
  f.game.World.renderAlerts(view);
  assert.match(view.innerHTML, /紧急迎击 · 已到位/);
  assert.doesNotMatch(view.innerHTML, /后就绪/);
});

test('alert timer advances interception deployment, preparation and waiting without a state push', () => {
  const f = setup(), now = 1_000_000;
  Object.assign(f.march, { action: 'intercept_scout', deployAt: now + 2000,
    readyAt: now + 7000, arriveAt: now + 20000, interceptionOpening: 'ambush' });
  const attributes = { 'data-interception-march-id': '17', 'data-interception-phase': 'deploying' };
  const status = { innerHTML: '', getAttribute: name => attributes[name], setAttribute(name, value) { attributes[name] = value; } };
  const progressAttrs = { 'data-progress-bar': 'true', 'data-progress-deadline': String(f.march.deployAt),
    'data-progress-duration': String(f.march.deployAt - f.march.startAt) };
  const progress = { className: 'term-pbar', textContent: '', getAttribute: name => progressAttrs[name] };
  const view = { isConnected: true, querySelectorAll(selector) {
    return selector === '[data-interception-march-id]' ? [status] : selector === '[data-progress-bar]' ? [progress] : [];
  } };
  f.game.World.startAlertTimer(view);
  f.tick(2000);
  assert.equal(attributes['data-interception-phase'], 'preparing');
  assert.match(status.innerHTML, /设伏准备/);
  assert.match(status.innerHTML, /5秒<\/b> 后就绪/);
  assert.match(progress.textContent, /100%/);
  f.tick(5000);
  assert.equal(attributes['data-interception-phase'], 'waiting');
  assert.match(status.innerHTML, /伏击待发 · 已到位/);
  f.tick(13000);
  assert.equal(attributes['data-interception-phase'], 'combat');
  assert.equal(status.innerHTML, '交火中');
});

test('interceptor return trips use return arrival rather than an old deployment time', () => {
  const f = setup(), now = 1_000_000;
  Object.assign(f.march, { action: 'intercept_scout', returning: true, startAt: now - 10000,
    deployAt: now - 5000, readyAt: now, arriveAt: now + 31000 });
  const view = { innerHTML: '', querySelectorAll: () => [] };
  f.game.World.renderAlerts(view);
  assert.match(view.innerHTML, /data-progress-deadline="1031000" data-progress-duration="41000"/);
  assert.match(view.innerHTML, /加速回城/);
  assert.doesNotMatch(view.innerHTML, /data-interception-march-id|后到位|后交火/);
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
  await context.Game.API.speedUpMarch(17, true, 4, 'preview-token');
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/game/world/speedup-march');
  assert.deepEqual({ ...requests[0].body }, { marchId: 17, returning: true, version: 4, confirmationToken: 'preview-token' });
  assert.equal(context.Game.Core.state.items.marchOrd, 2);
  assert.equal(context.Game.Core.state.world.marches[0].arriveAt, 3000);
});

test('tier changes ignore stale previews and submit only the selected inventory key', async () => {
  const f = setup(), pending = [];
  Object.assign(f.state.items, { basicMarchOrd: 2, advancedMarchOrd: 4 });
  f.game.API.previewMarchOrder = (id, key) => new Promise(resolve => pending.push({ key, resolve }));
  const modal = f.open(), select = modal.nodes['[data-march-tier]'];
  assert.match(modal.innerHTML, /初级行军令.*高级行军令.*极速行军令/);
  assert.equal(select.value, 'basicMarchOrd');
  select.value = 'advancedMarchOrd';
  select.onchange();
  assert.equal(modal.nodes['[data-march-confirm]'].disabled, true);
  pending[1].resolve({ version: 4, returning: false, ratio: 0.25, itemKey: 'advancedMarchOrd',
    commanderName: '未任命', knowledge: 0, baseRatio: 0.25, knowledgeBonus: 0, cap: 0.55,
    confirmationToken: 'advanced-token' });
  await flush();
  assert.equal(modal.nodes['[data-march-stock]'].textContent, '高级行军令：消耗 1 枚，库存 4 枚');
  assert.equal(modal.nodes['[data-march-after]'].textContent, f.game.World.marchTimeText(Math.ceil(1761000 * 0.75)));
  pending[0].resolve({ version: 4, returning: false, ratio: 0.1, confirmationToken: 'stale-basic-token' });
  await flush();
  modal.nodes['[data-march-confirm]'].onclick();
  assert.deepEqual(f.calls[0].args, [17, false, 4, 'advanced-token', 'advancedMarchOrd']);
  assert.equal(select.disabled, true);
});

test('keyboard navigation includes the tier selector', () => {
  const f = setup(), modal = f.open(), select = modal.nodes['[data-march-tier]'];
  const cancel = modal.nodes['[data-march-cancel]'], ok = modal.nodes['[data-march-confirm]'];
  ok.focus();
  modal.onkeydown({ key: 'Tab', preventDefault() {} });
  assert.equal(f.context.document.activeElement, select);
  modal.onkeydown({ key: 'Tab', shiftKey: true, preventDefault() {} });
  assert.equal(f.context.document.activeElement, ok);
  cancel.focus();
  modal.onkeydown({ key: 'Tab', shiftKey: true, preventDefault() {} });
  assert.equal(f.context.document.activeElement, select);
});

test('API carries the selected march order tier to the server', async () => {
  const requests = [];
  const context = vm.createContext({ Game: { Core: {}, ApiClient: class {
    getToken() { return 'player-one'; }
    post(url, body) { requests.push({ url, body }); return Promise.resolve({ success: true }); }
  } } });
  context.window = context;
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/api.js'), 'utf8'), context);
  await context.Game.API.speedUpMarch(17, false, 4, 'basic-token', 'basicMarchOrd');
  assert.deepEqual({ ...requests[0].body }, { marchId: 17, returning: false, version: 4,
    confirmationToken: 'basic-token', itemKey: 'basicMarchOrd' });
});
