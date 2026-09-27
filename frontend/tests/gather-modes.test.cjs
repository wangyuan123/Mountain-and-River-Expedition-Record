const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function setup() {
  const requests = [];
  const commands = [];
  const routes = [];
  const modals = [];
  let selectedMode = null;
  const body = {
    appendChild(modal) { modals.push(modal); modal.parentNode = body; },
    removeChild(modal) { modals.splice(modals.indexOf(modal), 1); modal.parentNode = null; }
  };
  const state = {
    player: {}, tech: {}, resources: {}, officers: [], army: { truck: 10 },
    world: { pos: { x: 10, y: 10 }, wildTiles: [], playerCities: [], marches: [], incoming: [] }
  };
  const context = vm.createContext({
    console, Promise, Math, Date, parseInt,
    document: {
      body,
      createElement() {
        const nodes = {};
        return { nodes, setAttribute() {}, querySelector(selector) {
          if (selector.includes('stationedGatherMode')) return { value: selectedMode || 'auto' };
          return nodes[selector] || (nodes[selector] = { focus() {} });
        } };
      },
      getElementById: id => id === 'dqty_truck' ? { value: '2' } : modals.find(modal => modal.id === id),
      querySelector: selector => selector.includes('dpGatherMode') && selectedMode ? { value: selectedMode } : null
    },
    Game: {
      DATA: {}, fmt: String, escapeHtml: value => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;'),
      go: route => routes.push(route), toast() {},
      Core: { state, views: {}, armyCap: () => 100, render() {} },
      API: {
        worldDispatch: request => { requests.push(request); return Promise.resolve(); },
        stopGatherMarch: id => { commands.push(['stop', id]); return Promise.resolve(); },
        cancelMarch: id => { commands.push(['return', id]); return Promise.resolve(); },
        wildStartGather: (id, mode) => { requests.push({ wildTileId: id, gatherMode: mode }); return Promise.resolve(); }
      }
    }
  });
  context.window = context;
  context.Core = context.Game.Core;
  for (const file of ['constants.js', 'data.js', 'icons.js', 'world.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  const target = { kind: 'wild', id: 7, type: 'grainfield', level: 1, x: 15, y: 15, occupied: true, totalRes: 1000, mined: 0 };
  context.Game.World.mapAction(target, 'gatherDispatch');
  return { game: context.Game, state, target, requests, commands, routes, modals, selectMode: value => { selectedMode = value; } };
}

test('gather preparation shows requested descriptions and defaults to automatic mode', () => {
  const { game, state, routes } = setup();
  const view = { innerHTML: '' };
  assert.equal(routes.at(-1), 'dispatch');
  assert.equal(state.world._dispatchTarget.kind, 'wild_gather');
  game.World.renderDispatch(view);
  assert.match(view.innerHTML, /name="dpGatherMode" value="auto" checked/);
  assert.match(view.innerHTML, /采集完成后自动收获并自动按原路线回城/);
  assert.match(view.innerHTML, /需要玩家自主终止采集任务，需要亲自下达命令选择部队回城/);
  game.World.setGatherMode('manual');
  game.World.renderDispatch(view);
  assert.match(view.innerHTML, /name="dpGatherMode" value="manual" checked/);
  assert.doesNotMatch(view.innerHTML, /name="dpGatherMode" value="auto" checked/);
  assert.match(view.innerHTML, /返城不改变野地归属/);
  assert.doesNotMatch(view.innerHTML, /value="manual"[^>]*disabled/);
});

test('onboarding gathering locks automatic mode through rendering, selection changes, and dispatch', async () => {
  const { game, state, requests, selectMode } = setup();
  state.world._dispatchTarget.onboardingGather = true;
  state.world._dispatchTarget.gatherMode = 'manual';
  const view = { innerHTML: '' };
  game.World.renderDispatch(view);
  assert.match(view.innerHTML, /name="dpGatherMode" value="auto" checked/);
  assert.match(view.innerHTML, /class="dispatch-gather-mode is-disabled"/);
  assert.match(view.innerHTML, /name="dpGatherMode" value="manual" disabled/);
  assert.doesNotMatch(view.innerHTML, /value="manual" checked/);
  assert.match(view.innerHTML, /新手采集固定为全自动/);
  game.World.setGatherMode('manual');
  assert.equal(state.world._dispatchTarget.gatherMode, 'auto');
  game.World.renderDispatch(view);
  assert.match(view.innerHTML, /value="manual" disabled/);
  selectMode('manual');
  game.World.launchDispatch();
  await new Promise(setImmediate);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].gatherMode, 'auto');
  assert.equal(requests[0].action, 'gather');
});

test('dispatch submits the selected mode and legacy gathering still defaults to automatic', async () => {
  for (const mode of ['manual', 'auto', null]) {
    const { game, requests, selectMode } = setup();
    selectMode(mode);
    game.World.launchDispatch();
    await new Promise(setImmediate);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].gatherMode, mode || 'auto');
    assert.equal(requests[0].targetKind, 'wild_gather');
    assert.equal(requests[0].action, 'gather');
    assert.equal(requests[0].army.truck, 2);
  }
});

test('other dispatch actions do not display or submit gathering modes', async () => {
  const { game, state, requests, selectMode } = setup();
  state.world._dispatchTarget.kind = 'wild';
  state.world._dispatchTarget.action = 'station';
  selectMode('manual');
  const view = { innerHTML: '' };
  game.World.renderDispatch(view);
  assert.doesNotMatch(view.innerHTML, /dpGatherMode/);
  game.World.launchDispatch();
  await new Promise(setImmediate);
  assert.equal(requests.length, 1);
  assert.equal(Object.hasOwn(requests[0], 'gatherMode'), false);
});

test('PvE dispatch selects battle mode and automatic marches have no enter-battle action', async () => {
  const { game, state, requests } = setup();
  state.world._dispatchTarget.kind = 'wild';
  state.world._dispatchTarget.action = 'conquer';
  const view = { innerHTML: '' };
  game.World.renderDispatch(view);
  assert.match(view.innerHTML, /name="dpBattleMode" value="auto" checked/);
  assert.match(view.innerHTML, /可以手动进入战场指挥战斗/);
  assert.match(view.innerHTML, /进入战斗后系统直接完成战斗结算/);

  game.World.setBattleMode('manual');
  game.World.renderDispatch(view);
  assert.match(view.innerHTML, /name="dpBattleMode" value="manual" checked/);
  game.World.launchDispatch();
  await new Promise(setImmediate);
  assert.equal(requests[0].battleMode, 'manual');

  state.world.marches = [{ id: 31, targetKind: 'wild', targetName: '野地', action: 'conquer',
    battleMode: 'auto', army: { truck: 2 }, arriveAt: Date.now() - 1000 }];
  game.World.renderAlerts(view);
  assert.doesNotMatch(view.innerHTML, /openTactical\(31\)/);
  delete state.world.marches[0].battleMode;
  game.World.renderAlerts(view);
  assert.doesNotMatch(view.innerHTML, /openTactical\(31\)/);
});

test('military alerts keep manual gather, stopped, and returning stages distinct from battle', () => {
  const { game, state } = setup();
  const march = {
    id: 21, targetKind: 'wild_gather', targetId: 7, targetName: '粮田', action: 'gather',
    army: { truck: 2 }, gatherMode: 'manual', gathering: true, gatherAmount: 100, gatherRes: 'food',
    arriveAt: Date.now() - 60000, gatherEndAt: Date.now() + 60000
  };
  state.world.marches = [march];
  const view = { innerHTML: '' };
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /采集全手动.*采集中/);
  assert.match(view.innerHTML, /stopGatherMarch[(]21[)]/);
  assert.doesNotMatch(view.innerHTML, /returnGatherMarch|进入战斗|等待战斗结果|取消行军/);
  march.gatherEndAt = Date.now() - 1;
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /已采满，等待手动终止/);
  assert.match(view.innerHTML, /stopGatherMarch[(]21[)]/);
  march.gathering = false;
  march.gatherStopped = true;
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /已终止采集，等待回城命令/);
  assert.match(view.innerHTML, /returnGatherMarch[(]21[)]/);
  assert.doesNotMatch(view.innerHTML, /stopGatherMarch|进入战斗/);
  march.returning = true;
  march.arriveAt = Date.now() + 60000;
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /返城/);
  assert.doesNotMatch(view.innerHTML, /stopGatherMarch|returnGatherMarch|进入战斗/);
});

test('automatic gathering has no manual stop or return controls', () => {
  const { game, state } = setup();
  state.world.marches = [{ id: 22, targetKind: 'wild_gather', action: 'gather', gathering: true,
    army: { truck: 2 }, gatherEndAt: Date.now() - 1, arriveAt: Date.now() - 60000 }];
  const view = { innerHTML: '' };
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /采集全自动.*采集完成，等待返城/);
  assert.doesNotMatch(view.innerHTML, /stopGatherMarch|returnGatherMarch|进入战斗/);
});

test('stopping and returning send separate commands for the selected army', async () => {
  const { game, commands } = setup();
  await game.World.stopGatherMarch(21);
  assert.deepEqual(commands, [['stop', 21]]);
  await game.World.returnGatherMarch(21);
  assert.deepEqual(commands, [['stop', 21], ['return', 21]]);
});

test('owned tile controls distinguish multiple gathering armies from other target types', () => {
  const { game, state } = setup();
  const common = { targetKind: 'wild_gather', targetId: 7, gatherMode: 'manual', army: { truck: 2 } };
  state.world.marches = [
    { ...common, id: 21, gathering: true, gatherEndAt: Date.now() + 60000 },
    { ...common, id: 22, gatherStopped: true },
    { ...common, id: 23, targetKind: 'player', gatherStopped: true },
    { ...common, id: 24, targetId: 8, gatherStopped: true },
    { ...common, id: 25, returning: true, gatherStopped: true }
  ];
  const html = game.World.renderGatherMarches(7, 'world-map-button');
  assert.match(html, /stopGatherMarch[(]21[)]/);
  assert.match(html, /returnGatherMarch[(]22[)]/);
  assert.doesNotMatch(html, /returnGatherMarch[(]2[345][)]/);
  assert.match(html, /部队 #21/);
  assert.match(html, /部队 #22/);
});

test('stationed gathering opens a cancellable mode dialog without sending a request', () => {
  const { game, state, target, requests, modals } = setup();
  target.garrison = { truck: 2 };
  state.world.wildTiles = [target];
  game.World.startGatherWild(0);
  game.World.startGatherWild(0);
  assert.equal(modals.length, 1);
  assert.equal(requests.length, 0);
  assert.match(modals[0].innerHTML, /name="stationedGatherMode" value="auto" checked/);
  assert.match(modals[0].innerHTML, /采集完成后自动收获并自动按原路线回城/);
  assert.match(modals[0].innerHTML, /需要玩家自主终止采集任务，需要亲自下达命令选择部队回城/);
  assert.match(modals[0].innerHTML, /返城不改变野地归属/);
  modals[0].nodes['[data-gather-cancel]'].onclick();
  assert.equal(modals.length, 0);
  assert.equal(requests.length, 0);
});

test('stationed modal sends stable tile ID and selected mode only once', async () => {
  for (const mode of ['auto', 'manual']) {
    const { game, state, target, requests, modals, selectMode } = setup();
    target.garrison = { truck: 2 };
    state.world.wildTiles = [target];
    game.World.startGatherWild(0);
    selectMode(mode);
    state.world.wildTiles = [{ ...target, id: 99 }];
    const start = modals[0].nodes['[data-gather-start]'];
    start.onclick();
    start.onclick();
    assert.equal(start.disabled, true);
    assert.deepEqual(requests, [{ wildTileId: 7, gatherMode: mode }]);
    await new Promise(setImmediate);
    assert.equal(modals.length, 0);
  }
});

test('stationed modal recovers from errors and active or harvested tasks cannot restart', async () => {
  const { game, state, target, modals } = setup();
  target.garrison = { truck: 2 };
  state.world.wildTiles = [target];
  game.API.wildStartGather = () => Promise.reject(new Error('采集启动失败'));
  game.World.startGatherWild(0);
  const start = modals[0].nodes['[data-gather-start]'];
  start.onclick();
  await new Promise(setImmediate);
  assert.equal(modals.length, 1);
  assert.equal(start.disabled, false);
  modals[0].nodes['[data-gather-cancel]'].onclick();
  target.gathering = true;
  game.World.startGatherWild(0);
  assert.equal(modals.length, 0);
  target.gathering = false;
  target.gatherHarvested = 0;
  game.World.startGatherWild(0);
  assert.equal(modals.length, 0);
});

test('owned territory list follows automatic, manual, and harvested stationed states', () => {
  const { game, state, target } = setup();
  game.WorldView = { ensure: () => true };
  target.garrison = { truck: 2 };
  target.gathering = true;
  target.gatherEndAt = Date.now() - 1;
  state.world.wildTiles = [target];
  state.world._activeTab = 'owned';
  const view = { innerHTML: '' };
  target.gatherMode = 'auto';
  game.World.renderView(view);
  assert.match(view.innerHTML, /采集全自动/);
  assert.doesNotMatch(view.innerHTML, /Game.World.harvestWild/);
  target.gatherMode = 'manual';
  game.World.renderView(view);
  assert.match(view.innerHTML, /已采满，请收获/);
  assert.match(view.innerHTML, /Game.World.harvestWild/);
  target.gathering = false;
  target.gatherHarvested = 0;
  game.World.renderView(view);
  assert.match(view.innerHTML, /部队回城/);
  assert.match(view.innerHTML, /Game.World.recallWild/);
  assert.doesNotMatch(view.innerHTML, /Game.World.harvestWild|Game.World.startGatherWild/);
});
