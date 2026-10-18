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
    assert.match(view.innerHTML, /有效速度＋(5×)?自身侦察技术等级/);
    assert.match(view.innerHTML, /守方雷达仅首回合每级\+[25]点/);
    assert.match(view.innerHTML, /奇数回合出征方先手，偶数回合守方先手/);
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
  assert.doesNotMatch(html, /commander-panel|总防御|首次交火/);
});

test('recon reports show actual leaders and the first combat cycle in both perspectives', () => {
  const { G } = setup();
  const commanders = {
    attacker: { name: '随队<将领>', level: 12, baseMilitary: 70, military: 88, baseDefense: 40, defense: 43,
      skills: [{ id: 'frenzy', name: '全军冲锋', level: 5, description: '从首次交战回合起生效（若第4回合首次交战，则在第4、7、10回合生效）' },
        { id: 'blitz', name: '闪电突击', level: 3 }] },
    defender: { name: '目标分城守将', level: 9, military: 55, defense: 77, skills: [] }
  };
  for (const unit of ['scout', 'recon_car']) {
    const data = { scoutUnit: unit, result: 'draw', commanders,
      myArmy: { [unit]: 100 }, mySurvivors: { [unit]: 80 }, enemyArmy: { [unit]: 100 }, enemySurvivors: { [unit]: 70 },
      combatLog: ['第1回合：交火\n我方加成<script>\n敌方绝境反击'] };
    const attack = G.Battle.renderScoutReportBoard({ time: Date.now(), data });
    assert.match(attack, /首次交火即生效，之后间隔两回合再触发/);
    assert.match(attack, /第1、4、7、10回合/);
    assert.match(attack, /总军事：88/); assert.match(attack, /总防御：43/);
    assert.match(attack, /基础：70/); assert.match(attack, /基础：40/);
    assert.match(attack, /全军冲锋 Lv\.5/);
    assert.match(attack, /<b>闪电突击 Lv\.3<\/b><span>侦查交火速度 \+18%<\/span>/);
    assert.doesNotMatch(attack, /参与先手排序|其他回合沿用常驻速度/);
    assert.match(attack, /随队&lt;将领&gt;/); assert.match(attack, /我方加成&lt;script&gt;/);
    assert.match(attack, /交火<\/div><div[^>]*>我方加成/);
    assert.doesNotMatch(attack, /<script>|若第4回合首次交战/);
    assert.ok(attack.indexOf('我方将领') < attack.indexOf('随队&lt;将领&gt;'));
    assert.ok(attack.indexOf('随队&lt;将领&gt;') < attack.indexOf('敌方将领'));
    const defense = G.Battle.renderScoutReportBoard({ time: Date.now(), data: { ...data, perspective: 'defender' } });
    assert.ok(defense.indexOf('我方将领') < defense.indexOf('目标分城守将'));
    assert.ok(defense.indexOf('目标分城守将') < defense.indexOf('敌方将领'));
    assert.ok(defense.indexOf('敌方将领') < defense.indexOf('随队&lt;将领&gt;'));
  }
  const empty = G.Battle.renderScoutCommanders({ commanders: { attacker: null, defender: null } }, 'attacker');
  assert.equal((empty.match(/无将领参战/g) || []).length, 2);
});

test('initiative reports explain opening radar and alternate ties using saved research from each perspective', () => {
  const { G } = setup();
  const initiative = { version: 'recon-initiative-v2-20261015', attackerReconLevel: 4, defenderReconLevel: 2, defenderRadarLevel: 3 };
  for (const unit of ['scout', 'recon_car']) {
    const data = { scoutUnit: unit, result: 'draw', showCityInfo: true, reconInitiative: initiative,
      myScouts: 100, myLost: 10, enemyScouts: 100, enemyLost: 10,
      combatLog: ['第1回合：交火\n本回合先手：敌方\n敌方雷达预警：先手+6（仅首回合）', '第2回合：交火\n本回合先手：我方'] };
    // 报告展示保存值，不能读取玩家现在的科技来解释历史顺序。
    G.Core.state.tech = { recon_level: 5, recon_radar: 0 };
    const attack = G.Battle.renderScoutReportBoard({ time: Date.now(), data });
    assert.match(attack, /有效速度＋自身侦察技术等级/);
    assert.match(attack, /守方雷达仅首回合每级\+2点/);
    assert.match(attack, /奇数回合出征方先手，偶数回合守方先手/);
    assert.match(attack, /我方侦察技术 Lv\.4 \/ 敌方侦察技术 Lv\.2；敌方雷达首回合先手\+6/);
    const defense = G.Battle.renderScoutReportBoard({ time: Date.now(), data: { ...data, perspective: 'defender' } });
    assert.match(defense, /我方侦察技术 Lv\.2 \/ 敌方侦察技术 Lv\.4；我方雷达首回合先手\+6/);
    assert.equal(G.Battle.renderScoutInitiative({ ...data, reconInitiative: undefined }, 'attacker'), '');
    assert.equal(G.Battle.renderScoutInitiative({ ...data, reconInitiative: { ...initiative, version: 'historical-rule' } }, 'attacker'), '');
  }
});

test('vehicle uses armor engine, avoids apron, and keeps documented oil consumption', () => {
  const { G } = setup();
  const base = G.World.calcDispatchSpeed({ recon_car: 10 }).slowestSpd;
  assert.equal(base, 5);
  G.Core.state.buildings.apron = 10;
  G.Core.state.tech.air_engine = 10;
  assert.equal(G.World.calcDispatchSpeed({ recon_car: 10 }).slowestSpd, 5);
  G.Core.state.tech.arm_engine = 10;
  assert.equal(G.World.calcDispatchSpeed({ recon_car: 10 }).slowestSpd, 7.5);
  assert.equal(G.World.calcDispatchFuel({ recon_car: 10 }, 100, 2), 600);
});
