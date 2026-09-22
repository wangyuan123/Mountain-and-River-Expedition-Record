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
        spdMul: function (cat) {
          var catKey = { inf: null, arm: 'arm_engine', air: 'air_engine', nav: 'nav_engine' }[cat];
          if (!catKey) return 1;
          return 1 + 0.05 * (this.state.tech[catKey] || 0);
        }
      }
    },
    ...extra
  });
  c.window = c;
  c.Core = c.Game.Core;

  // Load data.js and world.js
  for (const file of ['data.js', 'world.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), c);
  }
  return c;
}

test('calcDispatchSpeed selects slowest unit speed among multiple unit types', () => {
  const { Game: g } = setup();

  // Single unit: scout (base speed 11)
  const res1 = g.World.calcDispatchSpeed({ scout: 5 });
  assert.equal(res1.slowestSpd, 11);
  assert.equal(res1.slowestUnitId, 'scout');
  assert.equal(res1.slowestUnitName, g.DATA.units.scout.name);
  assert.equal(res1.unitCount, 1);

  // Mixed units: scout (11) and infantry (3)
  // Army always travels together, so slowest speed (3) must be chosen
  const res2 = g.World.calcDispatchSpeed({ scout: 10, infantry: 50 });
  assert.equal(res2.slowestSpd, 3);
  assert.equal(res2.slowestUnitId, 'infantry');
  assert.equal(res2.slowestUnitName, g.DATA.units.infantry.name);
  assert.equal(res2.unitCount, 2);

  // Mixed units: ltank (6), motor (7), truck (6) -> slowest is ltank (6)
  const res3 = g.World.calcDispatchSpeed({ ltank: 20, motor: 30, truck: 10 });
  assert.equal(res3.slowestSpd, 6);
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

  // ltank base speed = 6. With arm_engine lv 2 (1.10x) -> effective speed = 6.6
  const resArm = g.World.calcDispatchSpeed({ ltank: 10 });
  assert.ok(Math.abs(resArm.slowestSpd - 6.6) < 1e-6);

  // scout base speed = 11. With air_engine lv 4 (1.20x) -> effective speed = 13.2
  const resAir = g.World.calcDispatchSpeed({ scout: 5 });
  assert.ok(Math.abs(resAir.slowestSpd - 13.2) < 1e-6);

  // Combination: ltank (6.6) + scout (13.2) -> slowest is ltank (6.6)
  const resMixed = g.World.calcDispatchSpeed({ ltank: 10, scout: 5 });
  assert.ok(Math.abs(resMixed.slowestSpd - 6.6) < 1e-6);
  assert.equal(resMixed.slowestUnitId, 'ltank');

  // Infantry has no engine tech, remains base speed 3
  const resWithInf = g.World.calcDispatchSpeed({ ltank: 10, scout: 5, infantry: 20 });
  assert.equal(resWithInf.slowestSpd, 3);
  assert.equal(resWithInf.slowestUnitId, 'infantry');
});

test('calcDispatchMarchTime computes march seconds accurately with distance and speed boost', () => {
  const { Game: g } = setup();

  // Distance: 10 tiles, Speed: 3 (infantry), secPerGrid: 9
  // ceil(10 * 9 / 3) = 30 seconds
  const t1 = g.World.calcDispatchMarchTime(10, 3, 1.0);
  assert.equal(t1, 30);

  // Distance: 15 tiles, Speed: 6.6 (ltank with tech)
  // 15 * 9 / 6.6 = 135 / 6.6 ≈ 20.45 -> ceil = 21 seconds
  const t2 = g.World.calcDispatchMarchTime(15, 6.6, 1.0);
  assert.equal(t2, 21);

  // With 50% march boost (speedMul = 1.5)
  // 10 * 9 / (3 * 1.5) = 90 / 4.5 = 20 seconds
  const t3 = g.World.calcDispatchMarchTime(10, 3, 1.5);
  assert.equal(t3, 20);

  // Minimum duration is 1 second
  const t4 = g.World.calcDispatchMarchTime(0, 14, 1.0);
  assert.equal(t4, 1);
});

test('calcDispatchFuel uses distance and route legs for oil consumption', () => {
  const { Game: g } = setup();

  // 10 格、30 秒单程：步兵无油耗；特种兵装备和坦克均消耗石油。
  const oneWay = g.World.calcDispatchFuel({ infantry: 10, special: 2, ltank: 3 }, 10, 1);
  assert.equal(oneWay, 2);

  // 普通出征预扣往返油耗，驻防只需要单程油耗。
  const roundTrip = g.World.calcDispatchFuel({ infantry: 10, special: 2, ltank: 3 }, 10, 2);
  assert.equal(roundTrip, 3);
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

  assert.equal(g.World.fmtDuration(45), '45 秒');
  assert.equal(g.World.fmtDuration(60), '1 分钟');
  assert.equal(g.World.fmtDuration(75), '1 分 15 秒');
  assert.equal(g.World.fmtDuration(3665), '1 小时 1 分 5 秒');
});

test('renderDispatch defaults all available units to 1', () => {
  const c = setup();
  c.Game.Core.state.world = { pos: { x: 10, y: 10 }, wildTiles: [] };
  c.Game.Core.state.army = { scout: 10, infantry: 50, ltank: 5, truck: 20 };
  c.Game.Core.state.officers = [];
  c.Game.Core.state.resources = {};
  c.Game.World.mapAction({ kind: 'wild', id: 5, type: 'grainfield', level: 2, x: 103, y: 105 }, 'conquer');
  const v = { innerHTML: '' };
  c.Game.World.renderDispatch(v);

  assert.match(v.innerHTML, /id="dqty_scout"[^>]*value="1"/);
  assert.match(v.innerHTML, /id="dqty_infantry"[^>]*value="1"/);
  assert.match(v.innerHTML, /id="dqty_ltank"[^>]*value="1"/);
  assert.match(v.innerHTML, /id="dqty_truck"[^>]*value="1"/);
  assert.match(v.innerHTML, /行军油耗/);
  assert.doesNotMatch(v.innerHTML, /行军粮耗|每5分钟粮耗/);
});
