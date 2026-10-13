const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const context = vm.createContext({ console, Promise, setTimeout, clearTimeout,
    document: { getElementById() { return null; } } });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'core.js', 'world.js', 'battle.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  const G = context.Game;
  G.Core.state = { player: { id: 1, citySlot: 0 }, army: { recon_car: 30, scout: 20 }, officers: [],
    buildings: {}, tech: {}, resources: {}, reports: [], cityState: {},
    world: { cityPos: { x: 10, y: 10 }, wildTiles: [], _dispatchTarget: {
      action: 'scout', kind: 'wild', target: { id: 1, x: 20, y: 10, type: 'oil', level: 1 } } } };
  G.fmt = String;
  return { G, context };
}

test('dispatch chooses available recon inventory and keeps a single selectable unit', () => {
  for (const [army, mode, unit] of [[{ scout: 2, recon_car: 3 }, 'air', 'scout'], [{ recon_car: 3 }, 'land', 'recon_car']]) {
    const { G } = setup();
    G.Core.state.army = army;
    const view = {};
    G.World.renderDispatch(view);
    assert.equal(G.Core.state.world._dispatchTarget.reconMode, mode);
    assert.ok(view.innerHTML.includes('id="dqty_' + unit + '"'));
    assert.equal((view.innerHTML.match(/id="dqty_/g) || []).length, 1);
    assert.match(view.innerHTML, /空中侦查/);
    assert.match(view.innerHTML, /地面侦查/);
    assert.match(view.innerHTML, /派出侦查部队/);
  }
});

test('changing recon mode clears the previous army and recalculates display', () => {
  const { G } = setup();
  const dt = G.Core.state.world._dispatchTarget;
  dt.reconMode = 'air'; dt.defaultArmy = { scout: 10 };
  let renders = 0;
  G.Core.render = () => renders++;
  G.World.setReconMode('land');
  assert.equal(dt.reconMode, 'land');
  assert.deepEqual(JSON.parse(JSON.stringify(dt.defaultArmy)), {});
  assert.equal(renders, 1);
  const view = {};
  G.World.renderDispatch(view);
  assert.match(view.innerHTML, /id="dqty_recon_car"/);
  assert.doesNotMatch(view.innerHTML, /id="dqty_scout"/);
});

test('ground route eligibility handles rejection and ignores responses from another target or city', async () => {
  const { G } = setup();
  const requests = [];
  G.API = { client: { post: (url, payload) => new Promise((resolve, reject) => requests.push({ url, payload, resolve, reject })) } };
  let dt = G.Core.state.world._dispatchTarget;
  G.World.checkGroundRecon(dt, dt.target);
  assert.equal(requests[0].url, '/game/world/route');
  assert.deepEqual(JSON.parse(JSON.stringify(requests[0].payload.army)), { recon_car: 1 });
  requests[0].reject(Error('地面侦查无法抵达，请使用侦察机'));
  await new Promise(setImmediate);
  assert.equal(dt.groundRecon.available, false);
  assert.match(dt.groundRecon.error, /请使用侦察机/);

  dt = G.Core.state.world._dispatchTarget = { action: 'scout', kind: 'wild', target: { id: 2 } };
  G.World.checkGroundRecon(dt, dt.target);
  G.Core.state.player.citySlot = 1;
  requests[1].resolve({ mode: 'land' });
  await new Promise(setImmediate);
  assert.equal(dt.groundRecon.available, undefined);
  G.World.checkGroundRecon(dt, dt.target);
  G.Core.state.world._dispatchTarget = {};
  requests[2].resolve({ mode: 'land' });
  await new Promise(setImmediate);
  assert.equal(dt.groundRecon.available, undefined);
});

test('vehicle report reads typed snapshots and uses vehicles in both perspectives', () => {
  const { G } = setup();
  const data = { scoutUnit: 'recon_car', reconMode: 'land', showCityInfo: true, result: 'draw',
    myArmy: { recon_car: 100 }, mySurvivors: { recon_car: 55 },
    enemyArmy: { recon_car: 120 }, enemySurvivors: { recon_car: 70 },
    // Deliberately conflicting legacy scalars verify the new per-unit snapshot wins.
    myScouts: 999, enemyScouts: 999, myLost: 0, enemyLost: 0,
    wounded: { recon_car: 10 }, combatLog: ['第1回合：双方交火'] };
  const report = { data, time: Date.now() };
  const html = G.Battle.renderScoutReportBoard(report);
  assert.match(html, /地面侦查/);
  assert.match(html, /100 辆 ➔ 幸存: 55 辆/);
  assert.match(html, /损失车辆 50 辆/);
  assert.match(html, /侦查装甲车 10 辆/);
  assert.match(html, /地面遭遇记录/);
  assert.match(html, /成功获取当前科技允许的情报/);
  assert.doesNotMatch(html, /999|架|击落/);
  const defense = G.Battle.renderScoutReportBoard({ ...report, data: { ...data, perspective: 'defender', intercepted: false } });
  assert.match(defense, /我方驻防侦查装甲车/);
  assert.match(defense, /敌方来袭侦查装甲车/);
  assert.match(defense, /地面遭遇记录/);
  assert.doesNotMatch(defense, /目标情报|侦查技术|架|击落/);
});

test('failed eligibility can be retried without losing the selected vehicles', async () => {
  const { G, context } = setup();
  const dt = G.Core.state.world._dispatchTarget;
  dt.reconMode = 'land';
  dt.groundRecon = { pending: false, available: false, error: '网络暂时不可用' };
  context.document.getElementById = id => id === 'dqty_recon_car' ? { value: '12' } : null;
  let rendered = 0, requested = 0;
  G.Core.render = () => rendered++;
  G.API = { client: { post: () => { requested++; return Promise.resolve({ mode: 'land' }); } } };
  G.World.retryGroundRecon();
  assert.equal(rendered, 1);
  assert.deepEqual(JSON.parse(JSON.stringify(dt.defaultArmy)), { recon_car: 12 });
  assert.equal(dt.groundRecon, undefined);
  G.World.checkGroundRecon(dt, dt.target);
  G.World.retryGroundRecon();
  assert.equal(rendered, 1, 'must not start another retry while pending');
  await new Promise(setImmediate);
  assert.equal(requested, 1);
  assert.equal(dt.groundRecon.available, true);
});

test('legacy aircraft reports remain readable without new fields', () => {
  const { G } = setup();
  const html = G.Battle.renderScoutReportBoard({ time: Date.now(), data: {
    myScouts: 10, myLost: 2, enemyScouts: 4, enemyLost: 1, showCityInfo: true, result: 'draw' } });
  assert.match(html, /空中侦查/);
  assert.match(html, /10 架 ➔ 幸存: 8 架/);
  assert.match(html, /4 架 ➔ 幸存: 3 架/);
  assert.match(html, /战平（双方侦察机均存活）/);
});

test('vehicle uses armor engine, avoids apron, and keeps documented oil consumption', () => {
  const { G } = setup();
  const base = G.World.calcDispatchSpeed({ recon_car: 10 }).slowestSpd;
  assert.equal(base, 7);
  G.Core.state.buildings.apron = 10;
  G.Core.state.tech.air_engine = 10;
  assert.equal(G.World.calcDispatchSpeed({ recon_car: 10 }).slowestSpd, 7);
  G.Core.state.tech.arm_engine = 10;
  assert.equal(G.World.calcDispatchSpeed({ recon_car: 10 }).slowestSpd, 10.5);
  assert.equal(G.World.calcDispatchFuel({ recon_car: 10 }, 100, 2), 60);
});
