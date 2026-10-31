const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
function runtime() {
  const elements = {}, applied = [], calls = [], toasts = [], timers = new Map();
  elements.screen = { inert: false };
  const view = { innerHTML: '', querySelectorAll: () => [], insertBefore(el) { elements[el.id] = el; } };
  elements.view = view;
  let token = 'account-a';
  const ctx = { console, Promise, setTimeout: () => 1, clearTimeout() {}, setInterval(callback, delay) { timers.set(1, {callback, delay}); return 1; }, clearInterval(id) { timers.delete(id); },
    document: { getElementById: id => elements[id] || null, querySelector: () => null,
      createElement() { return { innerHTML: '', setAttribute() {}, querySelectorAll: () => [], remove() { delete elements[this.id]; } }; } } };
  ctx.window = ctx;
  ctx.Game = { Core: { views: {}, route: 'home', state: { player: { id: 1, citySlot: 0 }, world: {} }, renderTop() {} }, toast(message) { toasts.push(message); },
    API: { getToken: () => token, isLoggedIn: () => !!token, applyState: s => applied.push(s), client: {
      get() { const request = deferred(); calls.push({ method: 'GET', request }); return request.promise; },
      post(url, body) { const request = deferred(); calls.push({ method: 'POST', url, body, request }); return request.promise; }
    } }, go(route) { this.Core.route = route; } };
  vm.createContext(ctx);
  require('./load-constants.cjs')(ctx);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8'), ctx);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/onboarding.js'), 'utf8'), ctx);
  return { G: ctx.Game, calls, applied, elements, toasts, timers, document: ctx.document, ctx, setAccount(id) { token = 'account-' + id; ctx.Game.Core.state.player.id = id; } };
}
function data(extra = {}) { return { enrolled: true, paused: false, done: false, completed: 0, objectives: [], supplies: [], current: null, checks: {}, optionalTasks: [], ...extra }; }


test('late responses from a previous account cannot revive its guide or balances', async () => {
  const r = runtime(), old = r.G.Onboarding.init();
  r.setAccount(2); const next = r.G.Onboarding.init();
  r.calls[1].request.resolve(data({ paused: true })); await next;
  r.calls[0].request.resolve(data({ paused: false })); await old;
  assert.equal(r.G.Onboarding.state.data.paused, true);
  assert.equal(r.elements.onboardingBar, undefined);
});

test('a pre-mutation poll cannot undo pause and duplicate mutations are ignored', async () => {
  const r = runtime(), pending = r.G.Onboarding.init();
  const pause = r.G.Onboarding.mutate('pause', { paused: true });
  await r.G.Onboarding.refresh(); await r.G.Onboarding.mutate('pause', { paused: true });
  assert.equal(r.calls.length, 2);
  r.calls[1].request.resolve(data({ paused: true })); await pause;
  r.calls[0].request.resolve(data({ paused: false })); await pending;
  assert.equal(r.G.Onboarding.state.data.paused, true);
});

test('exploration rewards show task badge and old account claims cannot update new state', async () => {
  const r = runtime();
  r.G.Onboarding.state.data = data({ done: true, optionalTasks: [{ id: 'gather', status: 'completed' }] });
  assert.equal(r.G.Onboarding.hasSupply(), true);
  const pending = r.G.Onboarding.mutate('tasks/claim', { taskId: 'gather' });
  await r.G.Onboarding.mutate('tasks/claim', { taskId: 'gather' });
  assert.equal(r.calls.length, 1);
  r.setAccount(2); r.G.Onboarding.stop();
  r.calls[0].request.resolve(data({ state: { resources: { steel: 999 } } })); await pending;
  assert.equal(r.applied.length, 0);
});

test('core guidance is nonblocking and contains no gather or reading countdown', async () => {
  const r = runtime(), pending = r.G.Onboarding.init();
  r.calls[0].request.resolve(data({ current: { id: 'base', title: '建设基地', body: '任意建筑' }, objectives: [{ id: 'base' }] })); await pending;
  assert.match(r.elements.onboardingBar.innerHTML, /前往建设|稍后再看/);
  assert.doesNotMatch(r.elements.onboardingBar.innerHTML, /aria-modal|本步奖励|采集|倒计时/);
  assert.equal(r.elements.screen.inert, false);
  assert.equal(r.timers.size, 1);
});

test('dispatch hides the floating guide and leaving restores it without pausing progress', async () => {
  const r = runtime(), pending = r.G.Onboarding.init();
  r.calls[0].request.resolve(data({ current: { id: 'scout', title: '执行侦察' }, objectives: [{ id: 'scout' }] })); await pending;
  assert.ok(r.elements.onboardingBar);
  r.G.Core.route = 'dispatch'; r.G.Onboarding.render();
  assert.equal(r.elements.onboardingBar, undefined);
  assert.equal(r.G.Onboarding.state.data.paused, false);
  r.G.Core.route = 'world'; r.G.Onboarding.render();
  assert.ok(r.elements.onboardingBar);
});

test('any construction and infantry queue can be explored while orders finish', async () => {
  const r = runtime();
  r.G.Onboarding.state.data = data({ current: { id: 'base' }, objectives: [] });
  await r.G.Onboarding.actionStarted('buildArmy', 'house');
  assert.equal(r.G.Onboarding.state.snoozedFor, 'base');
  assert.equal(r.elements.screen.inert, false);
  r.G.Onboarding.state.data.current = { id: 'infantry' };
  let tab; r.G.Army = { setTab(value) { tab = value; } };
  await r.G.Onboarding.actionStarted('army', 'infantry');
  assert.equal(tab, 'queue'); assert.equal(r.G.Onboarding.state.snoozedFor, 'infantry');
});

test('scout report advances immediately even if scout is still returning', async () => {
  const r = runtime();
  r.G.Onboarding.state.data = data({ scouting: true, current: { id: 'scout' }, objectives: [{ id: 'scout', complete: false }] });
  r.G.Onboarding.state.snoozedFor = 'scout';
  const pending = r.G.Onboarding.refresh();
  r.calls[0].request.resolve(data({ scouting: false, current: { id: 'occupy', title: '首战', body: '讨伐' }, objectives: [{ id: 'scout', complete: true }, { id: 'occupy', complete: false }] })); await pending;
  assert.match(r.elements.onboardingBar.innerHTML, /首战/);
  assert.equal(r.G.Onboarding.state.snoozedFor, '');
  assert.equal(r.elements.screen.inert, false);
});

test('opening reports adds no waiting timer and server graduation closes guide', async () => {
  const r = runtime();
  r.G.Onboarding.state.data = data({ current: { id: 'report' }, objectives: [] });
  r.G.Onboarding.go('reports');
  assert.equal(r.G.Core.route, 'reports');
  assert.equal(r.G.Onboarding.state.reportReading, undefined);
  assert.equal(r.elements.onboardingReportCountdown, undefined);
  const pending = r.G.Onboarding.refresh(); r.calls[0].request.resolve(data({ done: true })); await pending;
  assert.equal(r.elements.onboardingBar, undefined); assert.equal(r.timers.size, 0);
  assert.match(r.toasts.at(-1), /任务/);
});

test('recommended scout and campaign formations are prefilled without dispatching', async () => {
  for (const action of ['scout', 'occupy']) {
    const r = runtime(); r.G.Core.state.army = { infantry: 50, scout: 1 };
    const pending = r.G.Onboarding.findTarget(action);
    r.calls[0].request.resolve({ id: 17, kind: 'wild', type: 'grainfield', x: 12, y: 10 }); await pending;
    const target = r.G.Core.state.world._dispatchTarget;
    assert.equal(target.action, action === 'scout' ? 'scout' : 'campaign');
    assert.equal(target.defaultArmy[action === 'scout' ? 'scout' : 'infantry'], action === 'scout' ? 1 : 50);
    assert.equal(target.battleMode, 'auto'); assert.equal(r.calls.length, 1);
  }
});

test('task gathering prefills a station dispatch and waits for manual gathering', async () => {
  const r = runtime(); r.G.Core.state.army = { infantry: 50, truck: 1 };
  const pending = r.G.Onboarding.findTarget('gather');
  assert.equal(r.calls[0].body.gather, true);
  r.calls[0].request.resolve({ id: 17, kind: 'wild', type: 'grainfield', occupied: true, x: 12, y: 10 }); await pending;
  const target = r.G.Core.state.world._dispatchTarget;
  assert.equal(target.kind, 'wild'); assert.equal(target.action, 'station');
  assert.equal(target.onboardingGather, undefined); assert.equal(target.gatherMode, undefined); assert.equal(target.gatherAfterAction, undefined);
  assert.equal(target.defaultArmy.truck, 1); assert.equal(target.defaultArmy.infantry, undefined); assert.equal(r.G.Core.route, 'dispatch');
  assert.equal(r.G.Onboarding.finishGather, undefined);
});

test('gathering can prefill infantry when no transport units are available', async () => {
  for (const count of [1, 17, 50, 100]) {
    const r = runtime(); r.G.Core.state.army = { infantry: count, truck: 0, transport: 0 };
    const pending = r.G.Onboarding.findTarget('gather');
    assert.equal(r.calls[0].body.gather, true);
    r.calls[0].request.resolve({ id: 17, kind: 'wild', type: 'grainfield', occupied: true, x: 12, y: 10 }); await pending;
    const target = r.G.Core.state.world._dispatchTarget;
    assert.equal(target.defaultArmy.infantry, Math.min(count, 50));
    assert.equal(target.defaultArmy.truck, undefined);
    assert.equal(target.kind, 'wild'); assert.equal(target.action, 'station'); assert.equal(target.gatherMode, undefined); assert.equal(target.gatherAfterAction, undefined);
    assert.equal(r.G.Core.route, 'dispatch'); assert.equal(r.toasts.length, 0);
  }
});

test('gathering still prefers transport aircraft over infantry without trucks', async () => {
  const r = runtime(); r.G.Core.state.army = { infantry: 50, transport: 2 };
  const pending = r.G.Onboarding.findTarget('gather');
  r.calls[0].request.resolve({ id: 17, kind: 'wild', type: 'grainfield', occupied: true, x: 12, y: 10 }); await pending;
  const target = r.G.Core.state.world._dispatchTarget;
  assert.equal(target.defaultArmy.transport, 1); assert.equal(target.defaultArmy.infantry, undefined);
});

test('gathering without load-bearing units opens recruitment instead of an empty harvest', async () => {
  const r = runtime(); r.G.Core.state.army = { infantry: 0, ltank: 50 };
  await r.G.Onboarding.findTarget('gather');
  assert.equal(r.calls.length, 0); assert.equal(r.G.Core.route, 'army');
  assert.match(r.toasts.at(-1), /卡车/);
  assert.equal(r.G.Core.state.world._dispatchTarget, undefined);
});

test('task first-use hint is shown only after server confirmation and click does not complete it', async () => {
  const r = runtime(), task = { id: 'truck', status: 'available', route: 'army', building: 'truck', hint: '卡车负重较高' };
  r.G.Onboarding.state.data = data({ done: true, optionalTasks: [task] });
  const pending = r.G.Onboarding.beginTask('truck');
  assert.equal(r.calls[0].url, '/game/onboarding/tasks/begin'); assert.equal(r.toasts.length, 0);
  r.calls[0].request.resolve(data({ done: true, showHint: true, optionalTasks: [task] })); await pending;
  assert.match(r.elements.onboardingTaskHint.innerHTML, /卡车负重较高/); assert.equal(r.G.Core.route, 'army');
  assert.equal(r.G.Onboarding.state.data.optionalTasks[0].status, 'available');
  const second = r.G.Onboarding.beginTask('truck'); r.calls[1].request.resolve(data({ done: true, showHint: false, optionalTasks: [task] })); await second;
  assert.equal(r.toasts.length, 0);
});

test('direct map entry gets a hint without changing the current dispatch', async () => {
  const r = runtime(), task = { id: 'gather', status: 'available', hint: '资源返城后入库' };
  r.G.Onboarding.state.data = data({ done: true, optionalTasks: [task] }); r.G.Core.route = 'dispatch';
  const draft = { target: 123 }; r.G.Core.state.world._dispatchTarget = draft;
  const pending = r.G.Onboarding.showTaskHint('gather');
  r.calls[0].request.resolve(data({ done: true, showHint: true, optionalTasks: [{ ...task, hintSeen: true }] })); await pending;
  assert.equal(r.G.Core.route, 'dispatch'); assert.equal(r.G.Core.state.world._dispatchTarget, draft);
  await r.G.Onboarding.showTaskHint('gather'); assert.equal(r.calls.length, 1);
});

test('in-progress gathering opens world overview without starting a second dispatch', async () => {
  const r = runtime(), task = { id: 'gather', status: 'in_progress' };
  r.G.Onboarding.state.data = data({ done: true, optionalTasks: [task] });
  const pending = r.G.Onboarding.beginTask('gather');
  r.calls[0].request.resolve(data({ done: true, showHint: false, optionalTasks: [task] })); await pending;
  assert.equal(r.G.Core.route, 'world'); assert.equal(r.calls.length, 1);
});

test('task tab displays optional gathering independently of locked main chapters', () => {
  const r = runtime(); r.G.DATA = {};
  r.document.querySelectorAll = () => [];
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/main-quest.js'), 'utf8'), r.ctx);
  r.G.Onboarding.state.data = data({ done: true, optionalTasks: [
    { id: 'gather', title: '首次采集', body: '运回资源', status: 'available', reward: { food: 5000 } },
    { id: 'tech', title: '研究科技', status: 'locked', unlockHint: '先建科研中心' },
    { id: 'truck', title: '生产卡车', status: 'completed', reward: {} }
  ] });
  r.G.MainQuest.drawQuestView(r.elements.view);
  assert.match(r.elements.view.innerHTML, /玩法探索/);
  assert.match(r.elements.view.innerHTML, /data-exploration-start="gather"/);
  assert.match(r.elements.view.innerHTML, /先建科研中心/);
  assert.match(r.elements.view.innerHTML, /data-exploration-claim="truck"/);
  assert.ok(r.elements.view.innerHTML.indexOf('玩法探索') < r.elements.view.innerHTML.indexOf('军衔晋升'));
});
