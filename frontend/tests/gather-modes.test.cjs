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
  const toasts = [];
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
      go: route => routes.push(route), toast: message => toasts.push(message),
      Core: { state, views: {}, armyCap: () => 100, render() {} },
      API: {
        worldDispatch: request => { requests.push(request); return Promise.resolve(); },
        stopGatherMarch: id => { commands.push(['stop', id]); return Promise.resolve(); },
        cancelMarch: id => { commands.push(['return', id]); return Promise.resolve(); },
        wildAbandon: id => { commands.push(['abandon', id]); return Promise.resolve(); },
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
  return { context, game: context.Game, state, target, requests, commands, routes, modals, toasts, selectMode: value => { selectedMode = value; } };
}

test('gather preparation exposes only manual instructions without automatic controls', () => {
  const { game, state, routes } = setup();
  const view = { innerHTML: '' };
  assert.equal(routes.at(-1), 'dispatch');
  assert.equal(state.world._dispatchTarget.gatherMode, 'manual');
  game.World.renderDispatch(view);
  assert.match(view.innerHTML, /手动采集/);
  assert.match(view.innerHTML, /手动点击“收获”，再点击“部队回城”/);
  assert.match(view.innerHTML, /放弃野地需另行手动操作/);
  assert.doesNotMatch(view.innerHTML, /dpGatherMode|dpGatherAfterAction|全自动|自动废弃/);
});

test('gather alert titles distinguish outbound, harvesting, full, harvested and returning phases', () => {
  const { game, state } = setup();
  const now = 1000; game.gameNow = () => now;
  const march = { id: 42, targetKind: 'wild_gather', action: 'gather', targetX: 15, targetY: 15,
    army: { truck: 1 }, arriveAt: 5000, startAt: 0, gatherEndAt: 2000 };
  state.world.marches = [march];
  const view = { innerHTML: '' };
  for (const [properties, expected] of [
    [{}, '前往野地'], [{ gathering: true }, '采集中'], [{ gatherEndAt: 999 }, '待收获'],
    [{ gathering: false, gatherStopped: true }, '已收获待回城'], [{ returning: true }, '返城中']
  ]) {
    Object.assign(march, properties);
    game.World.renderAlerts(view);
    assert.ok(view.innerHTML.includes('data-gather-phase-id="42">' + expected + '</span>'), expected);
    if (expected === '前往野地' || expected === '返城中') assert.match(view.innerHTML, /data-progress-bar="true"/);
  }
});

test('normal and stale gathering drafts always submit manual and retain without losing troops', async () => {
  for (const mode of ['manual', 'auto', undefined]) {
    const { game, state, requests, selectMode } = setup();
    state.world._dispatchTarget.gatherMode = mode;
    state.world._dispatchTarget.gatherAfterAction = 'abandon';
    selectMode('auto');
    game.World.launchDispatch();
    await new Promise(setImmediate);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].gatherMode, 'manual');
    assert.equal(requests[0].gatherAfterAction, 'retain');
    assert.equal(requests[0].targetKind, 'wild_gather');
    assert.equal(requests[0].action, 'gather');
    assert.equal(requests[0].army.truck, 2);
  }
});

test('other dispatch actions do not display or submit gathering modes', async () => {
  const { game, state, requests } = setup();
  state.world._dispatchTarget.kind = 'wild';
  state.world._dispatchTarget.action = 'station';
  const view = { innerHTML: '' };
  game.World.renderDispatch(view);
  assert.doesNotMatch(view.innerHTML, /dpGatherMode|手动采集/);
  game.World.launchDispatch();
  await new Promise(setImmediate);
  assert.equal(requests.length, 1);
  assert.equal(Object.hasOwn(requests[0], 'gatherMode'), false);
  assert.equal(Object.hasOwn(requests[0], 'gatherAfterAction'), false);
});

test('legacy automatic tasks show manual harvest and return states without pending automation', () => {
  const { game, state } = setup();
  const march = { id: 21, targetKind: 'wild_gather', targetId: 7, targetName: '粮田', army: { truck: 1 },
    gatherMode: 'auto', gatherAfterAction: 'abandon', gathering: true, gatherEndAt: Date.now() - 1 };
  state.world.marches = [march];
  assert.match(game.World.renderGatherMarches(7), /已采满，请收获/);
  assert.doesNotMatch(game.World.renderGatherMarches(7), /自动废弃|自动返城|全自动/);
  march.gathering = false;
  march.returning = true;
  march.arriveAt = Date.now() + 60000;
  const view = { innerHTML: '' };
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /正在返城/);
  assert.doesNotMatch(view.innerHTML, /stopGatherMarch|returnGatherMarch/);
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
  assert.match(view.innerHTML, /手动采集.*采集中/);
  assert.match(view.innerHTML, /stopGatherMarch[(]21[)]/);
  assert.doesNotMatch(view.innerHTML, /returnGatherMarch|进入战斗|等待战斗结果|取消行军/);
  march.gatherEndAt = Date.now() - 1;
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /已采满，请收获/);
  assert.match(view.innerHTML, /stopGatherMarch[(]21[)]/);
  march.gathering = false;
  march.gatherStopped = true;
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /已收获 100 粮食，等待回城命令/);
  assert.match(view.innerHTML, /returnGatherMarch[(]21[)]/);
  assert.doesNotMatch(view.innerHTML, /stopGatherMarch|进入战斗/);
  march.returning = true;
  march.arriveAt = Date.now() + 60000;
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /返城/);
  assert.doesNotMatch(view.innerHTML, /stopGatherMarch|returnGatherMarch|进入战斗/);
});

test('legacy tasks without mode require manual harvest and return controls', () => {
  const { game, state } = setup();
  state.world.marches = [{ id: 22, targetKind: 'wild_gather', action: 'gather', gathering: true,
    army: { truck: 2 }, gatherEndAt: Date.now() - 1, arriveAt: Date.now() - 60000 }];
  const view = { innerHTML: '' };
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /手动采集.*已采满，请收获/);
  assert.match(view.innerHTML, /stopGatherMarch[(]22[)]/);
  assert.doesNotMatch(view.innerHTML, /returnGatherMarch|进入战斗/);
  state.world.marches[0].gatherStopped = true;
  state.world.marches[0].gathering = false;
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /已收获 0 资源，等待回城命令/);
  assert.match(view.innerHTML, /returnGatherMarch[(]22[)]/);
  assert.doesNotMatch(view.innerHTML, /完成后自动废弃/);
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
    { ...common, id: 26, gatherMode: 'auto', gatherAfterAction: 'abandon', gathering: true, gatherEndAt: Date.now() + 60000 },
    { ...common, id: 22, gatherStopped: true },
    { ...common, id: 23, targetKind: 'player', gatherStopped: true },
    { ...common, id: 24, targetId: 8, gatherStopped: true },
    { ...common, id: 25, returning: true, gatherStopped: true }
  ];
  const html = game.World.renderGatherMarches(7, 'world-map-button');
  assert.match(html, /stopGatherMarch[(]21[)]/);
  assert.match(html, /stopGatherMarch[(]26[)]/);
  assert.match(html, />收获<\/button>/);
  assert.doesNotMatch(html, />终止采集<\/button>/);
  assert.match(html, /returnGatherMarch[(]22[)]/);
  assert.doesNotMatch(html, /returnGatherMarch[(]2[345][)]/);
  assert.match(html, /部队 #21/);
  assert.match(html, /部队 #26/);
  assert.match(html, /部队 #22/);
  assert.match(game.World.gatherMarchStatus({ gatherMode: 'auto', gatherStopped: true,
    gatherAfterAction: 'abandon', gatherAfterActionResult: 'cancelled' }), /已收获 0 资源，等待回城命令$/);
});

test('gathering task countdown uses all four time units and keeps completion states', () => {
  const { game } = setup();
  const now = 100000;
  game.gameNow = () => now;
  const march = { gathering: true, gatherMode: 'auto' };
  for (const [remaining, expected] of [
    [178154000, '2天1小时29分钟14秒'],
    [86400000, '1天0小时0分钟0秒'],
    [3600000, '1小时0分钟0秒'],
    [60000, '1分钟0秒'],
    [1, '1秒']
  ]) {
    march.gatherEndAt = now + remaining;
    assert.match(game.World.gatherMarchStatus(march), new RegExp('采集中，距采满 ' + expected + '$'));
  }
  march.gatherEndAt = now;
  assert.match(game.World.gatherMarchStatus(march), /已采满，请收获/);
  march.gatherMode = 'manual';
  assert.match(game.World.gatherMarchStatus(march), /已采满，请收获/);
  march.gatherStopped = true;
  assert.match(game.World.gatherMarchStatus(march), /已收获 0 资源，等待回城命令/);
  march.returning = true;
  assert.match(game.World.gatherMarchStatus(march), /正在返城/);
});

test('territory list uses the same countdown for dispatched and stationed gathering', () => {
  const { game, state, target } = setup();
  const now = 100000;
  game.gameNow = () => now;
  game.WorldView = { ensure: () => true };
  state.world.wildTiles = [target];
  state.world._activeTab = 'owned';
  state.world.marches = [{ id: 223, targetKind: 'wild_gather', targetId: '7',
    gathering: true, gatherMode: 'auto', gatherEndAt: now + 178154000, army: { truck: 5000 } }];
  const view = { innerHTML: '' };
  game.World.renderView(view);
  assert.match(view.innerHTML, /距采满 2天1小时29分钟14秒/);
  assert.match(view.innerHTML, /部队 #223/);
  assert.match(view.innerHTML, /暂无独立驻防部队/);
  assert.doesNotMatch(view.innerHTML, /暂无驻守任务/);
  assert.doesNotMatch(view.innerHTML, /2969分14秒/);
  state.world.marches = [];
  target.garrison = { truck: 2 };
  target.gathering = true;
  target.gatherMode = 'auto';
  target.gatherEndAt = now + 178154000;
  game.World.renderView(view);
  assert.match(view.innerHTML, /采集中 2天1小时29分钟14秒/);
});

test('territory list hides abandon for both gathering flows until every team is recalled', () => {
  const { game, state, target } = setup();
  game.WorldView = { ensure: () => true };
  state.world.wildTiles = [target];
  state.world._activeTab = 'owned';
  const view = { innerHTML: '' };
  const march = { id: 223, targetKind: 'wild_gather', targetId: '7', army: { truck: 2 } };
  function canAbandon() {
    game.World.renderView(view);
    return /Game.World.abandonWild\(0\)/.test(view.innerHTML);
  }
  assert.equal(canAbandon(), true);
  for (const garrison of [{}, { truck: 2 }]) {
    target.garrison = garrison;
    for (const stage of [{}, { gathering: true, gatherEndAt: Date.now() + 60000 },
      { gathering: true, gatherEndAt: Date.now() - 1 }, { gatherStopped: true, gatherAmount: 0 }]) {
      state.world.marches = [{ ...march, ...stage }];
      assert.equal(canAbandon(), false, '独立采集队的去程、未收获及待回城阶段均隐藏放弃');
    }
    state.world.marches = [{ ...march, returning: true, gatherStopped: true }];
    assert.equal(canAbandon(), true, '下达回城命令后恢复放弃');
    state.world.marches.push({ ...march, id: 224, gatherStopped: true });
    assert.equal(canAbandon(), false, '仍有其他队伍待回城时不能放弃');
  }
  state.world.marches = [];
  target.gathering = true;
  assert.equal(canAbandon(), false);
  target.gathering = false;
  target.gatherHarvested = 0;
  assert.equal(canAbandon(), false, '驻军零收获仍需回城');
  target.gatherHarvested = null;
  assert.equal(canAbandon(), true);
  state.world.marches = [{ ...march, targetId: 8 }];
  assert.equal(canAbandon(), true, '其他野地的任务不影响当前领地');
});

test('ordinary garrison abandon confirmation explains return travel and arrival settlement', () => {
  const { game, state, target } = setup();
  state.world.wildTiles = [target];
  const confirmations = [];
  game.World.showConfirm = options => confirmations.push(options);
  target.garrison = { truck: 2 };
  game.World.abandonWild(0);
  assert.equal(confirmations.length, 1);
  assert.match(confirmations[0].subMessage, /按原路线返回各自出发城市，抵达后归还兵力/);
  target.garrison = {};
  game.World.abandonWild(0);
  assert.equal(confirmations.length, 2);
  assert.doesNotMatch(confirmations[1].subMessage, /驻军|兵力/);
});

test('stale abandon controls show the current stage and recheck before confirming', async () => {
  const { game, state, target, commands, toasts } = setup();
  state.world.wildTiles = [target];
  const confirmations = [];
  game.World.showConfirm = options => confirmations.push(options);
  const march = { id: 223, targetKind: 'wild_gather', targetId: '7', gathering: true };
  state.world.marches = [march];
  game.World.abandonWild(0);
  assert.match(toasts.at(-1), /尚未收获.*先收获/);
  assert.equal(confirmations.length, 0);
  march.gathering = false;
  march.gatherStopped = true;
  game.World.abandonWild(0);
  assert.match(toasts.at(-1), /已收获并留守.*先下达部队回城命令/);
  assert.doesNotMatch(toasts.at(-1), /正在采集/);
  assert.equal(confirmations.length, 0);
  march.returning = true;
  game.World.abandonWild(0);
  assert.equal(confirmations.length, 1);
  state.world.wildTiles = [{ ...target, gatherHarvested: 0 }];
  confirmations[0].onConfirm();
  assert.match(toasts.at(-1), /已收获并留守/);
  assert.equal(commands.length, 0);
  state.world.wildTiles[0].gatherHarvested = null;
  game.World.abandonWild(0);
  confirmations[1].onConfirm();
  await new Promise(setImmediate);
  assert.deepEqual(commands, [['abandon', 7]]);
});

test('stationed gathering opens a cancellable manual preparation dialog', () => {
  const { game, state, target, requests, modals } = setup();
  target.garrison = { truck: 2 };
  state.world.wildTiles = [target];
  game.World.startGatherWild(0);
  game.World.startGatherWild(0);
  assert.equal(modals.length, 1);
  assert.equal(requests.length, 0);
  assert.match(modals[0].innerHTML, /手动点击“收获”，再点击“部队回城”/);
  assert.match(modals[0].innerHTML, /放弃野地需另行手动操作/);
  assert.doesNotMatch(modals[0].innerHTML, /stationedGatherMode|全自动|自动收获/);
  modals[0].nodes['[data-gather-cancel]'].onclick();
  assert.equal(modals.length, 0);
  assert.equal(requests.length, 0);
});

test('stationed modal sends stable tile ID and manual mode only once', async () => {
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
    assert.deepEqual(requests, [{ wildTileId: 7, gatherMode: 'manual' }]);
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

test('owned territory list treats legacy and current tasks as manual', () => {
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
  assert.match(view.innerHTML, /手动采集/);
  assert.match(view.innerHTML, /Game.World.harvestWild/);
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
