const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  let now = 110000, nextTimer = 0;
  const timers = new Map();
  const tile = { id: 7, type: 'grainfield', level: 1, x: 15, y: 15,
    occupied: true, totalRes: 1000, mined: 200 };
  const march = { id: 223, targetId: '7', targetKind: 'wild_gather', gathering: true,
    gatherMode: 'manual', gatherStartAt: 100000, gatherEndAt: 130000,
    gatherAmount: 300, gatherRes: 'food', army: { truck: 1 } };
  const state = { player: {}, reports: [], world: {
    pos: { x: 10, y: 10 }, _activeTab: 'owned', _expandedTargetIds: { 'wild:7': true },
    wildTiles: [tile], playerCities: [], bandits: [], marches: [march]
  } };
  const game = { Core: { state, route: 'world', views: {} },
    WorldView: { ensure: () => true }, fmt: String };
  const context = vm.createContext({ Game: game, Date,
    document: { hidden: false },
    setInterval(run, delay) { const id = ++nextTimer; timers.set(id, { run, delay }); return id; },
    clearInterval(id) { timers.delete(id); }
  });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'icons.js', 'world.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  game.gameNow = () => now;
  const nodes = {};
  for (const name of ['remaining', 'total', 'amount', 'progress', 'tip']) {
    nodes['[data-wild-gather-' + name + ']'] = { textContent: '' };
  }
  const panel = { getAttribute: () => '7', querySelector: selector => nodes[selector] };
  const marchStatus = { getAttribute: () => '223', textContent: '' };
  const stationedStatus = { getAttribute: () => '7', textContent: '' };
  const view = { innerHTML: '', isConnected: true, scrollTop: 123,
    querySelectorAll(selector) {
      if (selector.includes(',')) return [panel];
      if (selector === '[data-wild-gather-id]') return [panel];
      if (selector === '[data-gather-march-id]') return [marchStatus];
      if (selector === '[data-gather-stationed-id]') return tile.gathering ? [stationedStatus] : [];
      return [];
    }
  };
  return { game, state, tile, march, timers, view, nodes, marchStatus, stationedStatus, context,
    setNow(value) { now = value; },
    tick() { for (const timer of [...timers.values()]) timer.run(); }
  };
}

test('owned list renders live collected amounts and a progress bar against the wild total', () => {
  const f = setup();
  f.game.World.renderView(f.view);
  assert.match(f.view.innerHTML, /tcard-owned tcard-collapsible tcard-expanded/);
  assert.doesNotMatch(f.view.innerHTML, /粮食 剩余/);
  assert.match(f.view.innerHTML, /已采集：100 \/ 800/);
  assert.match(f.view.innerHTML, /累计采集：10秒/);
  assert.match(f.view.innerHTML, /<progress data-wild-gather-progress[^>]*max="800" value="100"/);
  assert.equal(f.timers.size, 1);
  assert.equal([...f.timers.values()][0].delay, 1000);
});

test('list timer updates dispatched countdown, resource quantities and progress every second without rendering', () => {
  const f = setup();
  f.game.World.renderView(f.view);
  const initialHtml = f.view.innerHTML;
  f.tick();
  assert.match(f.marchStatus.textContent, /距采满 20秒$/);
  assert.equal(f.nodes['[data-wild-gather-progress]'].value, 100);
  f.setNow(111000); f.tick();
  assert.match(f.marchStatus.textContent, /距采满 19秒$/);
  assert.match(f.marchStatus.textContent, /累计采集：11秒/);
  assert.equal(f.nodes['[data-wild-gather-amount]'].textContent, '已采集：110 / 800');
  assert.equal(f.nodes['[data-wild-gather-remaining]'].textContent, '690');
  assert.equal(f.nodes['[data-wild-gather-progress]'].value, 110);
  assert.equal(f.nodes['[data-wild-gather-progress]'].max, 800);
  assert.equal(f.view.innerHTML, initialHtml);
  assert.equal(f.view.scrollTop, 123);
  assert.equal(f.state.world._expandedTargetIds['wild:7'], true);
  f.setNow(130000); f.tick();
  assert.match(f.marchStatus.textContent, /已采满，请收获$/);
  assert.equal(f.nodes['[data-wild-gather-amount]'].textContent, '已采集：300 / 800');
  assert.match(f.nodes['[data-wild-gather-tip]'].textContent, /已达到负重上限/);
  f.setNow(999000); f.tick();
  assert.match(f.marchStatus.textContent, /累计采集：30秒/);
  assert.equal(f.nodes['[data-wild-gather-progress]'].value, 300);
  assert.equal(f.nodes['[data-wild-gather-remaining]'].textContent, '500');
});

test('stationed list countdown advances each second, then remains at its load limit', () => {
  const f = setup();
  f.state.world.marches = [];
  Object.assign(f.tile, { gathering: true, gatherMode: 'auto', garrison: { truck: 1 },
    gatherStartAt: 100000, gatherEndAt: 180000, gatherLoad: 400 });
  f.game.World.renderView(f.view);
  f.tick();
  assert.match(f.stationedStatus.textContent, /采集中 1分钟10秒$/);
  f.setNow(111000); f.tick();
  assert.match(f.stationedStatus.textContent, /采集中 1分钟9秒$/);
  assert.match(f.stationedStatus.textContent, /累计采集：11秒/);
  assert.equal(f.nodes['[data-wild-gather-amount]'].textContent, '已采集：55 / 800');
  f.setNow(180000); f.tick();
  assert.match(f.stationedStatus.textContent, /已采满，请收获$/);
  assert.equal(f.nodes['[data-wild-gather-progress]'].value, 400);
  f.setNow(280000); f.tick();
  assert.match(f.stationedStatus.textContent, /累计采集：1分钟20秒/);
  assert.equal(f.nodes['[data-wild-gather-progress]'].value, 400);
});

test('list refresh reads replacement server objects and separates matching active and harvested teams', () => {
  const f = setup();
  f.game.World.renderView(f.view);
  f.state.world.wildTiles = [{ ...f.tile, mined: 400 }];
  f.state.world.marches = [f.march, { ...f.march, id: 224, gatherAmount: 150 },
    { ...f.march, targetId: '8' }, { ...f.march, targetKind: 'wild' },
    { ...f.march, gathering: false }, { ...f.march, returning: true },
    { ...f.march, gatherStopped: true }];
  f.tick();
  assert.equal(f.nodes['[data-wild-gather-amount]'].textContent, '已收获：300 · 已采集：150 / 600');
  assert.equal(f.nodes['[data-wild-gather-remaining]'].textContent, '450');
  assert.equal(f.tile.mined, 200, '前端估算不回写服务端累计量');
});

test('list redraw replaces its timer and leaving the view, entering the map or hiding the page stops updates', () => {
  const f = setup();
  f.game.World.renderView(f.view);
  f.game.World.renderView(f.view);
  assert.equal(f.timers.size, 1);
  f.context.document.hidden = true;
  f.tick();
  assert.equal(f.nodes['[data-wild-gather-amount]'].textContent, '');
  f.context.document.hidden = false;
  f.game.Core.route = 'home'; f.tick();
  assert.equal(f.timers.size, 0);
  f.game.Core.route = 'world';
  f.game.World.renderView(f.view);
  f.view.isConnected = false; f.tick();
  assert.equal(f.timers.size, 0);
  f.view.isConnected = true;
  f.game.World.renderView(f.view);
  f.game.WorldMap = { isMap: () => true, render() {} };
  f.game.World.renderView(f.view);
  assert.equal(f.timers.size, 0);
});

test('harvesting preserves actual cargo and reads the settled remaining stock without deducting it twice', () => {
  const f = setup();
  f.game.World.renderView(f.view);
  f.state.world.wildTiles = [{ ...f.tile, totalRes: 700, mined: 0 }];
  f.state.world.marches = [{ ...f.march, gathering: false, gatherStopped: true, gatherAmount: 100 }];
  f.tick();
  assert.equal(f.nodes['[data-wild-gather-amount]'].textContent, '已收获：100');
  assert.equal(f.nodes['[data-wild-gather-remaining]'].textContent, '700');
  assert.equal(f.nodes['[data-wild-gather-tip]'].textContent, '已收获，等待回城命令');
  assert.equal(f.nodes['[data-wild-gather-progress]'].hidden, true);
  assert.match(f.marchStatus.textContent, /已收获 100 粮食，等待回城命令/);
  f.setNow(999000); f.tick();
  assert.equal(f.nodes['[data-wild-gather-amount]'].textContent, '已收获：100');
  assert.equal(f.nodes['[data-wild-gather-remaining]'].textContent, '700');
  f.game.World.renderView(f.view);
  assert.match(f.view.innerHTML, /剩余资源：<b data-wild-gather-remaining>700<\/b>/);
  assert.match(f.view.innerHTML, /已收获：100/);
  assert.match(f.view.innerHTML, /returnGatherMarch\(223\)/);
  assert.doesNotMatch(f.view.innerHTML, /stopGatherMarch\(223\)/);
});

test('settled and active teams keep separate amounts, including zero harvest and exhausted wilds', () => {
  const f = setup();
  const harvested = { ...f.march, gathering: false, gatherStopped: true, gatherAmount: 100 };
  f.state.world.wildTiles = [{ ...f.tile, totalRes: 700, mined: 0 }];
  f.state.world.marches = [harvested, { ...f.march, id: 224 },
    { ...harvested, id: 225, returning: true }, { ...harvested, id: 226, targetId: '8' },
    { ...harvested, id: 227, targetKind: 'player' }];
  f.game.World.renderView(f.view); f.tick();
  assert.equal(f.nodes['[data-wild-gather-amount]'].textContent, '已收获：100 · 已采集：100 / 700');
  assert.equal(f.nodes['[data-wild-gather-remaining]'].textContent, '600');
  assert.equal(f.nodes['[data-wild-gather-progress]'].hidden, false);
  f.state.world.marches = [{ ...harvested, gatherAmount: 0 }];
  f.tick();
  assert.equal(f.nodes['[data-wild-gather-amount]'].textContent, '已收获：0');
  assert.equal(f.nodes['[data-wild-gather-tip]'].textContent, '已收获，等待回城命令');
  f.state.world.wildTiles[0].totalRes = 0;
  f.game.World.renderView(f.view);
  assert.match(f.view.innerHTML, /剩余资源：<b data-wild-gather-remaining>0<\/b>/);
  assert.match(f.view.innerHTML, /已收获：0/);
});

test('stationed harvest keeps its cargo visible while remaining stock comes from the latest wild', () => {
  const f = setup();
  f.state.world.marches = [];
  f.state.world.wildTiles = [{ ...f.tile, totalRes: 650, mined: 0,
    gatherHarvested: 150, garrison: { truck: 1 } }];
  f.game.World.renderView(f.view); f.tick();
  assert.equal(f.nodes['[data-wild-gather-amount]'].textContent, '已收获：150');
  assert.equal(f.nodes['[data-wild-gather-remaining]'].textContent, '650');
  assert.equal(f.nodes['[data-wild-gather-progress]'].hidden, true);
});

test('gather elapsed time uses the common duration format and excludes travel, future time and time after filling the load', () => {
  const { game } = setup();
  const start = 100000;
  const task = { gatherStartAt: start, gatherEndAt: start + 200000000 };
  for (const [seconds, text] of [
    [0, '0秒'], [1, '1秒'], [5, '5秒'], [61, '1分钟1秒'], [245, '4分钟5秒'],
    [3600, '1小时0分钟0秒'], [11045, '3小时4分钟5秒'],
    [86400, '1天0小时0分钟0秒'], [183845, '2天3小时4分钟5秒']
  ]) {
    assert.equal(game.gatherElapsedText(task, start + seconds * 1000), '累计采集：' + text);
  }
  assert.equal(game.gatherElapsedText(task, start - 1000), '累计采集：0秒');
  assert.equal(game.gatherElapsedText({ ...task, gatherEndAt: start + 60000 }, start + 999000), '累计采集：1分钟0秒');
  assert.equal(game.gatherElapsedText(task, start + 1999), '累计采集：1秒');
  assert.equal(game.gatherElapsedText({ gatherEndAt: task.gatherEndAt }, start + 1000), '');
  assert.doesNotMatch(game.World.gatherMarchStatus({ ...task, returning: true }), /累计采集/);
  assert.doesNotMatch(game.World.gatherMarchStatus({ arriveAt: start + 60000 }), /累计采集/);
});
