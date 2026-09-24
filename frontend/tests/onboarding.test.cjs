const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
function runtime() {
  const elements = {}, applied = [], calls = [];
  const view = { innerHTML: '', querySelectorAll: () => [], parentNode: { insertBefore(el) { elements[el.id] = el; } } };
  elements.view = view;
  let token = 'account-a';
  const ctx = { console, Promise, setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {},
    document: { getElementById: id => elements[id] || null, querySelector: () => null,
      createElement() { return { innerHTML: '', querySelectorAll: () => [], remove() { delete elements[this.id]; } }; } } };
  ctx.window = ctx;
  ctx.Game = { Core: { views: {}, route: 'home', state: { player: { id: 1, citySlot: 0 }, world: {} }, renderTop() {} }, toast() {},
    API: { getToken: () => token, isLoggedIn: () => !!token, applyState: s => applied.push(s), client: {
      get() { const request = deferred(); calls.push({ method: 'GET', request }); return request.promise; },
      post(url, body) { const request = deferred(); calls.push({ method: 'POST', url, body, request }); return request.promise; }
    } }, go(route) { this.Core.route = route; } };
  vm.createContext(ctx);
  require('./load-constants.cjs')(ctx);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/onboarding.js'), 'utf8'), ctx);
  return { G: ctx.Game, calls, applied, elements, setAccount(id) { token = 'account-' + id; ctx.Game.Core.state.player.id = id; } };
}
function data(extra = {}) { return { enrolled: true, paused: false, done: false, completed: 0, objectives: [], supplies: [], current: null, checks: {}, ...extra }; }

test('a delayed response from the previous account cannot revive its guide', async () => {
  const r = runtime(); const old = r.G.Onboarding.init();
  r.setAccount(2); const next = r.G.Onboarding.init();
  r.calls[1].request.resolve(data({ paused: true })); await next;
  r.calls[0].request.resolve(data({ paused: false })); await old;
  assert.equal(r.G.Onboarding.state.data.paused, true);
  assert.equal(r.elements.onboardingBar, undefined);
});

test('a pre-mutation poll cannot undo a persisted pause and polling waits for mutations', async () => {
  const r = runtime(); const pending = r.G.Onboarding.init();
  const pause = r.G.Onboarding.mutate('pause', { paused: true });
  await r.G.Onboarding.refresh(); assert.equal(r.calls.length, 2);
  r.calls[1].request.resolve(data({ paused: true })); await pause;
  r.calls[0].request.resolve(data({ paused: false })); await pending;
  assert.equal(r.G.Onboarding.state.data.paused, true);
  assert.equal(r.G.Onboarding.state.busy, false);
});

test('duplicate clicks send one explicit supply request and stale rewards do not overwrite a new account', async () => {
  const r = runtime();
  const first = r.G.Onboarding.mutate('claim', { supplyId: 'base' });
  await r.G.Onboarding.mutate('claim', { supplyId: 'base' });
  assert.equal(r.calls.length, 1); assert.equal(r.calls[0].body.supplyId, 'base');
  r.setAccount(2); r.G.Onboarding.stop();
  r.calls[0].request.resolve(data({ state: { resources: { steel: 999 } } })); await first;
  assert.equal(r.applied.length, 0);
});

test('paused players can see and claim supplies without forcing the guide open', async () => {
  const r = runtime(); const pending = r.G.Onboarding.init();
  r.calls[0].request.resolve(data({ paused: true, supplies: [{ id: 'base', available: true, claimed: false }] })); await pending;
  assert.equal(r.G.Onboarding.hasSupply(), true); assert.equal(r.elements.onboardingBar, undefined);
  const claim = r.G.Onboarding.mutate('claim', { supplyId: 'base' });
  r.calls[1].request.resolve(data({ paused: true, supplies: [{ id: 'base', available: true, claimed: true }], state: { resources: { steel: 4000 } } })); await claim;
  assert.equal(r.applied[0].resources.steel, 4000); assert.equal(r.G.Onboarding.hasSupply(), false);
});

test('recommended gathering opens a returning gathering march rather than garrison deployment', async () => {
  const r = runtime(); const pending = r.G.Onboarding.findTarget('gather');
  assert.equal(r.calls[0].body.gather, true);
  r.calls[0].request.resolve({ id: 17, kind: 'wild', type: 'grainfield', occupied: true, x: 12, y: 10 }); await pending;
  const target = r.G.Core.state.world._dispatchTarget;
  assert.equal(target.kind, 'wild_gather'); assert.equal(target.action, 'gather'); assert.equal(target.target.id, 17);
  assert.equal(r.G.Core.route, 'dispatch');
});
