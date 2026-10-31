const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('./server-gameplay.cjs').vm;
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

test('dispatch no longer exposes browser speed, travel time or fuel formulas', () => {
  const { Game: g } = setup();
  for (const name of ['calcDispatchSpeed', 'calcDispatchMarchTime', 'calcDispatchFuel', 'calcInterceptionPlan']) assert.equal(g.World[name], undefined);
  g.World._currentRoute = { cargoLimit: 12345, oil: 678, seconds: 234 };
  assert.equal(g.World.calcDispatchLoad(), 12345);
  g.Core.state.tech.log_load = 10;
  assert.equal(g.World.calcDispatchLoad(), 12345);
  g.World._currentRoute = null;
  assert.equal(g.World.calcDispatchLoad(), null);
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
    g.World._currentRoute = {cargoLimit:500,distance:20,seconds:173,oil:37,boostPercent:percent};
    g.World.updateDispatchStats();
    assert.equal(elements.estMarchTimeOneWay.textContent, g.World.fmtDuration(173));
    assert.ok(elements.estMarchBoostTag.textContent.includes('+' + percent + '%'));
    assert.match(elements.estMarchSpeedTip.textContent, /服务端/);
  }
  g.Core.state.cityState.marchBoostUntil = g.gameNow() - 1;
  g.World._currentRoute=null;
  g.World.updateDispatchStats();
  assert.equal(elements.estMarchBoostTag.hidden,true);
  assert.equal(elements.estMarchTimeOneWay.textContent,'等待服务端计算');
});
