const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function setup(extra = {}) {
  const c = vm.createContext({
    console,
    Promise,
    Math,
    Date,
    parseInt,
    document: {
      getElementById: () => null,
      createElement: () => ({ style: {}, setAttribute: () => {} })
    },
    Game: {
      DATA: {},
      fmt: (n) => String(n),
      go: () => {},
      toast: () => {},
      Core: {
        state: {
          tech: {},
          world: { pos: { x: 10, y: 10 } },
          cityState: {}
        },
        views: {},

      }
    },
    ...extra
  });
  c.window = c;
  c.Core = c.Game.Core;

  // 使用真实 Core 移速计算，验证出征页面传入兵种 ID。
  for (const file of ['constants.js', 'data.js', 'core.js', 'world.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), c);
  }
  c.Game.Core.state = { officers: [], army: {}, tech: {}, buildings: {}, world: { pos: { x: 10, y: 10 } }, cityState: {} };
  return c;
}

test('infantry personal cargo contributes to dispatch load and shares logistics technology', () => {
  const { Game: g } = setup();
  assert.equal(g.World.calcDispatchLoad({ infantry: 1 }), 10);
  assert.equal(g.World.calcDispatchLoad({ infantry: 50 }), g.World.calcDispatchLoad({ truck: 1 }));
  assert.equal(g.World.calcDispatchLoad({ infantry: 100, truck: 2 }), 2000);
  g.Core.state.tech.log_load = 10;
  assert.equal(g.World.calcDispatchLoad({ infantry: 100 }), 1500);
  assert.equal(g.World.calcDispatchLoad({ infantry: 100, truck: 2 }), 3000);
  g.Core.state.tech.log_load = 1;
  assert.equal(g.World.calcDispatchLoad({ infantry: 10 }), 105);
});

test('calcDispatchSpeed selects slowest unit speed among multiple unit types', () => {
  const { Game: g } = setup();

  // 侦察机基础速度9。
  const res1 = g.World.calcDispatchSpeed({ scout: 5 });
  assert.equal(res1.slowestSpd, 9);
  assert.equal(res1.slowestUnitId, 'scout');
  assert.equal(res1.slowestUnitName, g.DATA.units.scout.name);
  assert.equal(res1.unitCount, 1);

  // 混编按最慢兵种：侦察机9、步兵2，整队采用步兵速度。
  const res2 = g.World.calcDispatchSpeed({ scout: 10, infantry: 50 });
  assert.equal(res2.slowestSpd, 2);
  assert.equal(res2.slowestUnitId, 'infantry');
  assert.equal(res2.slowestUnitName, g.DATA.units.infantry.name);
  assert.equal(res2.unitCount, 2);

  // 轻坦与卡车基础速度均为4，同速时保留遍历到的首个兵种。
  const res3 = g.World.calcDispatchSpeed({ ltank: 20, truck: 10 });
  assert.equal(res3.slowestSpd, 4);
  assert.equal(res3.slowestUnitId, 'ltank');
  assert.equal(res3.slowestUnitName, g.DATA.units.ltank.name);

  // Empty army
  const res4 = g.World.calcDispatchSpeed({});
  assert.equal(res4.slowestSpd, null);
  assert.equal(res4.unitCount, 0);

  // Army with zero counts
  const res5 = g.World.calcDispatchSpeed({ infantry: 0, scout: 0 });
  assert.equal(res5.slowestSpd, null);
});

test('calcDispatchSpeed factors in engine technology speed bonuses', () => {
  const c = setup();
  const { Game: g } = c;

  // Set technology: arm_engine level 2 (+10%), air_engine level 4 (+20%)
  g.Core.state.tech.arm_engine = 2; // +10%
  g.Core.state.tech.air_engine = 4; // +20%

  // 轻坦基础4，燃烧引擎2级后为4.4。
  const resArm = g.World.calcDispatchSpeed({ ltank: 10 });
  assert.ok(Math.abs(resArm.slowestSpd - 4.4) < 1e-6);

  // 侦察机基础9，喷气推进4级后为10.8。
  const resAir = g.World.calcDispatchSpeed({ scout: 5 });
  assert.ok(Math.abs(resAir.slowestSpd - 10.8) < 1e-6);

  // 轻坦4.4与侦察机10.8混编，采用轻坦速度。
  const resMixed = g.World.calcDispatchSpeed({ ltank: 10, scout: 5 });
  assert.ok(Math.abs(resMixed.slowestSpd - 4.4) < 1e-6);
  assert.equal(resMixed.slowestUnitId, 'ltank');

  // 步兵也接燃烧引擎，基础2在2级科技下为2.2。
  const resWithInf = g.World.calcDispatchSpeed({ ltank: 10, scout: 5, infantry: 20 });
  assert.equal(resWithInf.slowestSpd, 2.2);
  assert.equal(resWithInf.slowestUnitId, 'infantry');

  // 特种兵与卡车共用陆军科技，地图行军预估同步获得加成。
  assert.ok(Math.abs(g.World.calcDispatchSpeed({ special: 10 }).slowestSpd - 6.6) < 1e-6);
  assert.ok(Math.abs(g.World.calcDispatchSpeed({ truck: 10 }).slowestSpd - 4.4) < 1e-6);
});

test('calcDispatchMarchTime computes march seconds accurately with distance and speed boost', () => {
  const { Game: g } = setup();

  // Distance: 10 tiles, Speed: 3, secPerGrid: 18
  // ceil(10 * 18 / 3) = 60 seconds
  const t1 = g.World.calcDispatchMarchTime(10, 3, 1.0);
  assert.equal(t1, 60);

  // 距离15格，轻坦2级引擎速度4.4：ceil(15 × 18 / 4.4) = 62秒。
  const t2 = g.World.calcDispatchMarchTime(15, 4.4, 1.0);
  assert.equal(t2, 62);

  // With 50% march boost (speedMul = 1.5)
  // 10 * 18 / (3 * 1.5) = 180 / 4.5 = 40 seconds
  const t3 = g.World.calcDispatchMarchTime(10, 3, 1.5);
  assert.equal(t3, 40);

  // Minimum duration is 1 second
  const t4 = g.World.calcDispatchMarchTime(0, 14, 1.0);
  assert.equal(t4, 1);
});

test('calcDispatchFuel multiplies rounded distance and route-leg oil consumption by ten', () => {
  const { Game: g } = setup();

  // 单程原始油耗 1.4，先取整为 2 再乘 10；步兵不贡献油耗。
  const oneWay = g.World.calcDispatchFuel({ infantry: 10, special: 2, ltank: 3 }, 10, 1);
  assert.equal(oneWay, 20);

  // 普通出征预扣往返油耗，驻防只需要单程油耗。
  const roundTrip = g.World.calcDispatchFuel({ infantry: 10, special: 2, ltank: 3 }, 10, 2);
  assert.equal(roundTrip, 30);
  assert.equal(g.World.calcDispatchFuel({ infantry: 10 }, 10, 2), 0);
});

test('all units define non-negative march fuel while infantry remains fuel-free', () => {
  const { Game: g } = setup();

  for (const unit of Object.values(g.DATA.units)) {
    assert.equal(Number.isInteger(unit.marchOil), true, unit.name + ' must define marchOil');
    assert.ok(unit.marchOil >= 0, unit.name + ' marchOil must not be negative');
  }
  assert.equal(g.DATA.units.infantry.marchOil, 0);
  assert.ok(g.DATA.units.special.marchOil > 0);
});

test('fmtDuration formats duration nicely', () => {
  const { Game: g } = setup();

  assert.equal(g.World.fmtDuration(45), '45秒');
  assert.equal(g.World.fmtDuration(60), '1分钟0秒');
  assert.equal(g.World.fmtDuration(75), '1分钟15秒');
  assert.equal(g.World.fmtDuration(3665), '1小时1分钟5秒');
});

test('renderDispatch defaults all available units to 0 and exposes a one-unit shortcut', () => {
  const c = setup();
  c.Game.Core.state.world = { pos: { x: 10, y: 10 }, wildTiles: [] };
  c.Game.Core.state.army = { scout: 10, infantry: 50, ltank: 5, truck: 20 };
  c.Game.Core.state.officers = [];
  c.Game.Core.state.resources = {};
  c.Game.World.mapAction({ kind: 'wild', id: 5, type: 'grainfield', level: 2, x: 103, y: 105 }, 'conquer');
  const v = { innerHTML: '' };
  c.Game.World.renderDispatch(v);

  assert.match(v.innerHTML, /id="dqty_scout"[^>]*value="0"/);
  assert.match(v.innerHTML, /id="dqty_infantry"[^>]*value="0"/);
  assert.match(v.innerHTML, /id="dqty_ltank"[^>]*value="0"/);
  assert.match(v.innerHTML, /id="dqty_truck"[^>]*value="0"/);
  assert.ok(v.innerHTML.includes('步兵(M1) (负重10/人)'));
  assert.ok(v.innerHTML.includes('卡车(CCKW-353) (辎重500/辆)'));
  assert.match(v.innerHTML, /全部设置1单位/);
  assert.match(v.innerHTML, /重置/);
  assert.match(v.innerHTML, /行军油耗/);
  assert.ok(v.innerHTML.includes('携带资源 (部队负重内)'));
  assert.doesNotMatch(v.innerHTML, /行军粮耗|每5分钟粮耗/);
});

test('setAllDispatchUnitsToOne updates visible unit inputs and sliders', () => {
  const c = setup();
  const elements = {};
  for (const [uid, max] of [['scout', 10], ['infantry', 50], ['ltank', 5], ['truck', 20]]) {
    elements['dqty_' + uid] = { value: '0', max: String(max) };
    elements['dslider_' + uid] = { value: '0', max: String(max), style: { setProperty() {} } };
  }
  c.document = { getElementById: id => elements[id] || null };

  c.Game.World.setAllDispatchUnitsToOne();

  for (const uid of ['scout', 'infantry', 'ltank', 'truck']) {
    assert.equal(elements['dqty_' + uid].value, 1);
    assert.equal(elements['dslider_' + uid].value, 1);
  }
});

test('resetDispatchUnits clears visible unit inputs and sliders', () => {
  const c = setup();
  const elements = {};
  for (const [uid, max] of [['scout', 10], ['infantry', 50], ['ltank', 5], ['truck', 20]]) {
    elements['dqty_' + uid] = { value: '1', max: String(max) };
    elements['dslider_' + uid] = { value: '1', max: String(max), style: { setProperty() {} } };
  }
  c.document = { getElementById: id => elements[id] || null };

  c.Game.World.resetDispatchUnits();

  for (const uid of ['scout', 'infantry', 'ltank', 'truck']) {
    assert.equal(elements['dqty_' + uid].value, 0);
    assert.equal(elements['dslider_' + uid].value, 0);
  }
});

test('dispatch estimates and boost descriptions follow each active march order tier', () => {
  const c = setup(), g = c.Game;
  const elements = { dispatchStatsCard: {}, dqty_infantry: { value: '50' },
    estMarchTimeOneWay: {}, estMarchBoostTag: { style: {} }, estMarchSpeedTip: {} };
  c.document.getElementById = id => elements[id] || null;
  g.Core.state.world._dispatchTarget = { action: 'conquer', target: { x: 30, y: 10 } };
  g.Core.state.cityState.marchBoostUntil = g.gameNow() + 3600000;
  for (const percent of [10, 25, 50]) {
    g.Core.state.cityState.marchBoostPercent = percent;
    g.World.updateDispatchStats();
    const seconds = Math.ceil(20 * g.DATA.world.marchSecPerGrid / (2 * (1 + percent / 100)));
    assert.equal(elements.estMarchTimeOneWay.textContent, g.World.fmtDuration(seconds));
    assert.ok(elements.estMarchBoostTag.textContent.includes('+' + percent + '%'));
    assert.ok(elements.estMarchSpeedTip.textContent.includes('+' + percent + '%'));
  }
  g.Core.state.cityState.marchBoostUntil = g.gameNow() - 1;
  g.World.updateDispatchStats();
  assert.equal(elements.estMarchBoostTag.style.display, 'none');
  assert.equal(elements.estMarchTimeOneWay.textContent, g.World.fmtDuration(180));
});
