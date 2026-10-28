const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const tiers = [
  ['mobilizationOrder', '战争动员令', 10, 80, 111],
  ['advancedMobilizationOrder', '高级战争动员令', 25, 220, 126],
  ['rapidMobilizationOrder', '极速战争动员令', 50, 500, 151]
];

function setup(action = 'conquer', kind = 'wild') {
  const requests = [], purchases = [], uses = [], toasts = [], routes = [];
  const nodes = {};
  const target = { id: 7, type: 'forest', name: '测试目标', level: 1, x: 15, y: 15, totalRes: 1000 };
  const state = {
    player: {}, tech: {}, resources: { diamond: 5000 }, officers: [], army: { infantry: 200, scout: 200, truck: 200 }, items: {},
    world: { pos: { x: 10, y: 10 }, marches: [], wildTiles: [target], bandits: [target], playerCities: [target],
      _dispatchTarget: { kind, target, idx: 0, action } }
  };
  const context = vm.createContext({
    Promise, console, document: { getElementById: id => nodes[id] || null, querySelector: () => null },
    Game: {
      Core: { state, views: {}, armyCap: () => 101, maxMarches: () => 10, render() {} },
      fmt: String, toast: message => toasts.push(message), go: route => routes.push(route),
      API: {
        worldDispatch: body => { requests.push(body); return Promise.resolve(); },
        shopBuy: id => { purchases.push(id); return Promise.resolve({ success: true }); },
        depotUse: id => { uses.push(id); return Promise.resolve({ success: true }); }
      }
    }
  });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'icons.js', 'shop.js', 'depot.js', 'world.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  return { game: context.Game, state, nodes, requests, purchases, uses, toasts, routes };
}

test('三档战争动员令可购买入仓，商城与仓库名称、说明和价格一致', async () => {
  const f = setup();
  for (const [id, name, percent, price] of tiers) {
    const item = f.game.Shop.items().find(product => product.id === id);
    const depotItem = f.game.DATA.items[id];
    assert.equal(item.name, name);
    assert.equal(item.price, price);
    assert.equal(item.cat, 'util');
    assert.equal(item.name, depotItem.name);
    assert.equal(item.desc, depotItem.desc);
    assert.match(item.desc, new RegExp('提高' + percent + '%'));
    f.game.Shop.buy(id);
    f.state.items[id] = 1;
    f.game.Depot.setTab('util');
    const view = {};
    f.game.Depot.renderView(view);
    assert.match(view.innerHTML, /出征时选择/);
    f.game.Depot.useItem(id);
    await Promise.resolve();
  }
  assert.deepEqual(f.purchases, tiers.map(t => t[0]));
  assert.deepEqual(f.uses, []);
  assert.equal(f.routes.at(-1), 'world');
});

test('进攻准备展示三档库存并禁用缺货项，切换动员令保留编队', () => {
  const f = setup();
  f.state.items.mobilizationOrder = 2;
  f.nodes.dqty_infantry = { value: '110' };
  const view = {};
  f.game.World.renderDispatch(view);
  assert.ok(view.innerHTML.includes('战争动员令 +10%（库存 2）'));
  assert.match(view.innerHTML, /value="advancedMobilizationOrder" disabled/);
  f.game.World.setMobilizationOrder('mobilizationOrder');
  assert.equal(f.state.world._dispatchTarget.mobilizationOrderItem, 'mobilizationOrder');
  assert.equal(f.nodes.dqty_infantry.value, '110');
  f.game.World.setMobilizationOrder('rapidMobilizationOrder');
  assert.equal(f.state.world._dispatchTarget.mobilizationOrderItem, 'mobilizationOrder');
});

for (const [id, name, percent, price, cap] of tiers) {
  test(name + '预览取整后的临时上限，允许上限内编队并提交对应道具', async () => {
    const f = setup();
    f.state.items[id] = 1;
    f.state.world._dispatchTarget.mobilizationOrderItem = id;
    f.nodes.dqty_infantry = { value: String(cap) };
    f.nodes.dpMobilizationOrder = { value: id };
    f.nodes.estDispatchTroops = { style: {} };
    f.nodes.dpMobilizationSummary = {};
    f.game.World.updateDispatchStats();
    assert.equal(f.nodes.estDispatchTroops.textContent, cap + ' / ' + cap);
    assert.match(f.nodes.dpMobilizationSummary.textContent, new RegExp('本次上限 ' + cap));
    f.game.World.launchDispatch();
    await new Promise(setImmediate);
    assert.equal(f.requests.length, 1);
    assert.equal(f.requests[0].mobilizationOrderItem, id);
    assert.equal(f.requests[0].army.infantry, cap);
    assert.equal(f.state.items[id], 1, '前端不能自行扣除库存');
  });

  test(name + '不能叠加突破本档上限', () => {
    const f = setup();
    f.state.items[id] = 5;
    f.state.world._dispatchTarget.mobilizationOrderItem = id;
    f.nodes.dqty_infantry = { value: String(cap + 1) };
    f.game.World.launchDispatch();
    assert.equal(f.requests.length, 0);
    assert.match(f.toasts.at(-1), new RegExp('超过带兵上限 ' + cap));
  });
}

test('失效库存不提供临时上限，超编时提前提示道具不足', () => {
  const f = setup();
  f.state.world._dispatchTarget.mobilizationOrderItem = 'rapidMobilizationOrder';
  f.nodes.dqty_infantry = { value: '120' };
  f.nodes.estDispatchTroops = { style: {} };
  f.game.World.updateDispatchStats();
  assert.equal(f.nodes.estDispatchTroops.textContent, '120 / 101');
  f.game.World.launchDispatch();
  assert.equal(f.requests.length, 0);
  assert.match(f.toasts.at(-1), /数量不足/);
});

test('侦查、采集、进驻和运输不展示或提交旧草稿中的战争动员令', async () => {
  for (const [action, kind, unit] of [['scout', 'wild', 'scout'], ['gather', 'wild_gather', 'truck'],
    ['station', 'wild', 'infantry'], ['transport', 'player', 'truck']]) {
    const f = setup(action, kind);
    f.state.items.rapidMobilizationOrder = 1;
    f.state.world._dispatchTarget.mobilizationOrderItem = 'rapidMobilizationOrder';
    f.nodes['dqty_' + unit] = { value: '1' };
    const view = {};
    f.game.World.renderDispatch(view);
    assert.doesNotMatch(view.innerHTML, /dpMobilizationOrder/);
    f.game.World.launchDispatch();
    await new Promise(setImmediate);
    assert.equal(f.requests.length, 1, action);
    assert.equal(Object.hasOwn(f.requests[0], 'mobilizationOrderItem'), false, action);
  }
});
