const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function snapshot(overrides = {}) {
  return { enrolled: true, paused: false, done: false, current: null, completed: 0,
    objectives: [], supplies: [], ...overrides };
}

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

function setup(initial) {
  const timers = new Map();
  const nodes = new Map();
  let nextTimer = 1;
  let reads = 0;
  const view = { parentNode: { insertBefore(node) { nodes.set(node.id, node); } } };
  const G = {
    Core: { state: { player: { id: 1 } }, route: 'home', views: {} },
    API: {
      getToken: () => 'token', isLoggedIn: () => true,
      client: { get: async () => { reads++; return initial; }, post: async () => initial }
    },
    toast() {}
  };
  const context = vm.createContext({
    window: { Game: G },
    document: {
      getElementById: id => id === 'view' ? view : nodes.get(id),
      createElement: () => ({ querySelectorAll: () => [], remove() { nodes.delete(this.id); } })
    },
    setInterval(callback, delay) { const id = nextTimer++; timers.set(id, { callback, delay }); return id; },
    clearInterval(id) { timers.delete(id); }
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/onboarding.js'), 'utf8'), context);
  return { G, timers, nodes, reads: () => reads };
}

test('未参加、暂停和完成的引导只做首次读取，不开启轮询', async () => {
  for (const data of [
    snapshot({ enrolled: false }), snapshot({ paused: true }), snapshot({ done: true }),
    snapshot({ done: true, supplies: [{ available: true, claimed: false }] })
  ]) {
    const { G, timers, reads } = setup(data);
    await G.Onboarding.init();
    assert.equal(reads(), 1);
    assert.equal(timers.size, 0);
  }
});

test('参加中的引导每五秒刷新，重复刷新不叠加定时器或在途请求', async () => {
  const { G, timers } = setup(snapshot());
  await G.Onboarding.init();
  assert.equal(timers.size, 1);
  const timer = [...timers.values()][0];
  assert.equal(timer.delay, 5000);
  const pending = deferred();
  let reads = 0;
  G.API.client.get = () => { reads++; return pending.promise; };
  const first = timer.callback();
  assert.equal(G.Onboarding.refresh(), first);
  assert.equal(timer.callback(), first);
  assert.equal(reads, 1);
  pending.resolve(snapshot());
  await first;
  assert.equal(timers.size, 1);
});

test('轮询发现未参加、暂停或完成时停止后续请求并移除提示', async () => {
  for (const change of [{ enrolled: false }, { paused: true }, { done: true }]) {
    const { G, timers, nodes } = setup(snapshot());
    await G.Onboarding.init();
    assert.ok(nodes.has('onboardingBar'));
    G.API.client.get = async () => snapshot(change);
    await [...timers.values()][0].callback();
    assert.equal(timers.size, 0);
    assert.equal(G.Onboarding.state.timer, null);
    assert.equal(nodes.has('onboardingBar'), false);
  }
});

test('暂停停止轮询，延迟旧响应不能恢复轮询，恢复提示后重新启动', async () => {
  const { G, timers } = setup(snapshot());
  await G.Onboarding.init();
  const old = deferred();
  G.API.client.get = () => old.promise;
  const polling = [...timers.values()][0].callback();
  const pause = deferred();
  G.API.client.post = () => pause.promise;
  const mutation = G.Onboarding.mutate('pause', { paused: true });
  assert.equal(timers.size, 0);
  G.API.client.get = () => { throw new Error('操作期间不应刷新'); };
  await G.Onboarding.refresh();
  pause.resolve(snapshot({ paused: true }));
  await mutation;
  old.resolve(snapshot());
  await polling;
  assert.equal(G.Onboarding.state.data.paused, true);
  assert.equal(timers.size, 0);
  G.API.client.post = async () => snapshot();
  await G.Onboarding.mutate('pause', { paused: false });
  assert.equal(timers.size, 1);
});

test('主动开启行动会启动轮询，选择发展方向完成后停止', async () => {
  const { G, timers } = setup(snapshot({ enrolled: false }));
  await G.Onboarding.init();
  G.API.client.post = async () => snapshot();
  await G.Onboarding.mutate('start');
  assert.equal(timers.size, 1);
  G.API.client.post = async () => snapshot({ done: true, plan: 'economy' });
  await G.Onboarding.mutate('plan', { plan: 'economy' });
  assert.equal(timers.size, 0);
});

test('暂停操作失败时保持原状态并恢复轮询', async () => {
  const { G, timers } = setup(snapshot());
  await G.Onboarding.init();
  G.API.client.post = async () => { throw new Error('请求失败'); };
  await G.Onboarding.mutate('pause', { paused: true });
  assert.equal(G.Onboarding.state.data.paused, false);
  assert.equal(timers.size, 1);
});

test('退出后在途响应不能再次启动轮询', async () => {
  const { G, timers } = setup(snapshot());
  await G.Onboarding.init();
  const pending = deferred();
  G.API.client.get = () => pending.promise;
  const request = [...timers.values()][0].callback();
  G.Onboarding.stop();
  pending.resolve(snapshot());
  await request;
  assert.equal(timers.size, 0);
  assert.equal(G.Onboarding.state.data, null);
});
