const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

function setup() {
  let now = 110000, nextTimer = 0;
  const timers = new Map();
  const tile = { id: 7, type: 'grainfield', level: 1, x: 15, y: 15,
    occupied: true, totalRes: 1000, mined: 200 };
  const march = { id: 223, targetId: '7', targetKind: 'wild_gather', gathering: true,
    gatherMode: 'manual', gatherStartAt: 100000, gatherEndAt: 130000,
    gatherAmount: 300, gatherRes: 'food', army: { truck: 1 } };
  tile.gatherView = {mined:100,total:800,remaining:700,current:100,load:300,percent:13,active:true,activeCount:1,harvested:0,hasHarvested:false,atCapacity:false,endAt:130000,tip:'采集中'};
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

test('list timers advance countdown only; harvested amounts change only with a server snapshot', () => {
 const f=setup(); f.game.World.renderView(f.view); const html=f.view.innerHTML;
 f.tick(); assert.equal(f.nodes['[data-wild-gather-progress]'].value,100);
 f.setNow(111000); f.tick();
 assert.match(f.marchStatus.textContent,/距采满 19秒$/);
 assert.equal(f.nodes['[data-wild-gather-amount]'].textContent,'已采集：100 / 800');
 assert.equal(f.nodes['[data-wild-gather-remaining]'].textContent,'700');
 assert.equal(f.view.innerHTML,html); assert.equal(f.view.scrollTop,123);
 f.tile.gatherView={...f.tile.gatherView,mined:300,current:300,remaining:500,percent:38,atCapacity:true,tip:'已达到负重上限，请收获'};
 f.setNow(130000);f.tick();
 assert.equal(f.nodes['[data-wild-gather-progress]'].value,300);
 f.setNow(999000);f.tick();assert.equal(f.nodes['[data-wild-gather-progress]'].value,300);
 assert.equal(f.tile.mined,200);
});

test('stationed countdown retains the sampled quantity until the next server response', () => {
 const f=setup(); f.state.world.marches=[];
 Object.assign(f.tile,{gathering:true,gatherStartAt:100000,gatherEndAt:180000});
 f.tile.gatherView={...f.tile.gatherView,mined:55,current:55,remaining:745,endAt:180000};
 f.game.World.renderView(f.view);f.setNow(111000);f.tick();
 assert.match(f.stationedStatus.textContent,/采集中 1分钟9秒$/);
 assert.equal(f.nodes['[data-wild-gather-amount]'].textContent,'已采集：55 / 800');
 f.setNow(280000);f.tick();assert.equal(f.nodes['[data-wild-gather-progress]'].value,55);
});

test('replacement server tiles update sampled active and harvested totals without altering old objects', () => {
 const f=setup();f.game.World.renderView(f.view);
 f.state.world.wildTiles=[{...f.tile,gatherView:{...f.tile.gatherView,total:600,mined:150,remaining:450,hasHarvested:true,harvested:300}}];
 f.tick();assert.equal(f.nodes['[data-wild-gather-amount]'].textContent,'已收获：300 · 已采集：150 / 600');
 assert.equal(f.nodes['[data-wild-gather-remaining]'].textContent,'450');assert.equal(f.tile.mined,200);
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

test('settled cargo uses server totals even when local march and resource fields disagree', () => {
 const f=setup();f.tile.gatherView={...f.tile.gatherView,active:false,hasHarvested:true,harvested:100,mined:0,total:700,remaining:700};
 f.march.gatherAmount=999999;f.tile.totalRes=999999;
 f.game.World.renderView(f.view);f.tick();
 assert.equal(f.nodes['[data-wild-gather-amount]'].textContent,'已收获：100');
 assert.equal(f.nodes['[data-wild-gather-remaining]'].textContent,'700');
});

test('zero and exhausted harvest snapshots are displayed without inventing another yield', () => {
 const f=setup();f.tile.gatherView={...f.tile.gatherView,active:false,hasHarvested:true,harvested:0,mined:0,total:0,remaining:0};
 f.game.World.renderView(f.view);f.tick();
 assert.equal(f.nodes['[data-wild-gather-amount]'].textContent,'已收获：0');
 assert.equal(f.nodes['[data-wild-gather-remaining]'].textContent,'0');
});

test('missing gather snapshots remain pending despite enough local fields to reconstruct a yield', () => {
 const f=setup();delete f.tile.gatherView;f.game.World.renderView(f.view);f.tick();
 assert.equal(f.nodes['[data-wild-gather-amount]'].textContent,'采集数量待同步');
 assert.equal(f.game.wildGatherProgress(f.tile,999999,[f.march]).mined,null);
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
