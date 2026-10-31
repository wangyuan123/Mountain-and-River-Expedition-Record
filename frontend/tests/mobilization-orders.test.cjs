const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

const tiers = [
  ['mobilizationOrder', '战争动员令', 10, 80],
  ['advancedMobilizationOrder', '高级战争动员令', 25, 220],
  ['rapidMobilizationOrder', '极速战争动员令', 50, 500]
];
const day = 86400000;
function setup(action = 'conquer', kind = 'wild') {
  const requests = [], purchases = [], uses = [], toasts = [];
  const nodes = {};
  let time = Date.UTC(2026, 9, 28, 0, 0);
  const target = { id: 7, type: 'forest', name: '测试目标', level: 1, x: 15, y: 15, totalRes: 1000 };
  const state = {
    player: { id: 1, mobilizationCycle: 1 }, tech: {}, resources: { diamond: 5000 }, officers: [], army: { infantry: 200, scout: 200, truck: 200 }, items: {},
    world: { pos: { x: 10, y: 10 }, marches: [], wildTiles: [target], bandits: [target], playerCities: [target],
      _dispatchTarget: { kind, target, idx: 0, action } }
  };
  const document = {
    activeElement: null,
    getElementById: id => nodes[id] || null,
    querySelector: () => null,
    createElement() {
      return { remove() { delete nodes[this.id]; }, querySelector() { return null; } };
    },
    body: { appendChild(mask) {
      nodes[mask.id] = mask;
      for (const match of mask.innerHTML.matchAll(/id="([^"]+)"/g)) {
        nodes[match[1]] = { value: '1', disabled: false, focus() {} };
      }
    } }
  };
  const context = vm.createContext({
    Promise, console, document,
    Game: {
      Core: { state, views: {}, ordinaryArmyCap: () => 101, armyCap: () => state.gameplay.armyCap, maxMarches: () => 10, render() {} },
      gameNow: () => time, fmt: String, escapeHtml: String, toast: message => toasts.push(message), go() {},
      API: {
        worldDispatch: body => { requests.push(body); return Promise.resolve(); },
        shopBuy: id => { purchases.push(id); return Promise.resolve({ success: true }); },
        depotUse: (...args) => { uses.push(args); return Promise.resolve({ success: true, message: '动员已生效' }); }
      }
    }
  });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'icons.js', 'shop.js', 'mobilization.js', 'depot.js', 'world.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  state.gameplay.armyCap=101;
  context.Game.ServerRules.preview=async intent=>({addedHours:({1:24,2:48})[intent.count],until:time+day,currentPercent:0,armyCap:111});
  context.Game.gameNow = () => time;
  return { game: context.Game, state, nodes, requests, purchases, uses, toasts, context,
    now: () => time, advance: ms => { time += ms; } };
}

test('三档道具在仓库二次确认后启用，数量与幂等标识传给后端，购买价格保留', async () => {
  const f = setup();
  for (const [id, name, percent, price] of tiers) {
    const product = f.game.Shop.items().find(item => item.id === id);
    assert.equal(product.name, name);
    assert.equal(product.price, price);
    assert.equal(product.desc, f.game.DATA.items[id].desc);
    assert.match(product.desc, /每张持续24小时/);
    assert.match(product.desc, new RegExp('提高' + percent + '%'));
    f.game.Shop.buy(id);
    f.state.items[id] = 3;
    const view = {};
    f.game.Depot.setTab('util');
    f.game.Depot.renderView(view);
    assert.match(view.innerHTML, /使用动员令/);
    const before = f.uses.length;
    f.game.Depot.useItem(id);
    await new Promise(setImmediate);
    assert.equal(f.uses.length, before, '打开弹窗不消耗');
    assert.match(f.nodes.mobilizationUseMask.innerHTML, /本账号所有城市的新出征行军/);
    assert.match(f.nodes.mobilizationUseMask.innerHTML, /守城全员参战，不受带兵上限限制/);
    assert.match(f.nodes.mobilizationUsePreview.innerHTML, /新增：24小时/);
    f.nodes.mobilizationUseCount.value = '2';
    f.nodes.mobilizationUseCount.oninput();
    await new Promise(setImmediate);
    assert.match(f.nodes.mobilizationUsePreview.innerHTML, /新增：48小时/);
    f.nodes.mobilizationUseConfirm.onclick();
    f.nodes.mobilizationUseConfirm.onclick();
    await new Promise(setImmediate);
    assert.equal(f.uses.length, before + 1, '重复点击只提交一次');
    assert.equal(f.uses.at(-1)[0], id);
    assert.equal(f.uses.at(-1)[4], 2);
    assert.match(f.uses.at(-1)[5], /^[A-Za-z0-9_-]{8,64}$/);
    assert.equal(f.state.items[id], 3, '库存由服务端状态更新');
  }
});

test('同档剩余时间累加预览，低档被高档覆盖时明确提示', async () => {
  const f = setup();
  f.state.items.mobilizationOrder = 5;
  f.state.gameplay.armyCap=151;
  f.game.ServerRules.preview=async()=>({until:f.now()+104400000,addedHours:24,currentPercent:50,armyCap:151});
  f.game.Depot.useItem('mobilizationOrder');
  await new Promise(setImmediate);
  assert.match(f.nodes.mobilizationUsePreview.innerHTML, /1天 5小时 0分钟/);
  assert.match(f.nodes.mobilizationUsePreview.innerHTML, /暂被更高档动员覆盖/);
  assert.match(f.nodes.mobilizationUsePreview.innerHTML, /151 → 151/);
  f.nodes.mobilizationUseCancel.onclick();
  assert.equal(f.nodes.mobilizationUseMask, undefined);
  assert.equal(f.uses.length, 0);
});

test('网络结果不明时重试同一个请求，确认不重复扣除', async () => {
  const f = setup();
  f.state.items.mobilizationOrder = 1;
  f.game.API.depotUse = (...args) => { f.uses.push(args); return f.uses.length === 1 ? Promise.reject(new Error('网络异常')) : Promise.resolve({ success: true }); };
  f.game.Depot.useItem('mobilizationOrder');
  await new Promise(setImmediate);
  f.nodes.mobilizationUseConfirm.onclick();
  await new Promise(setImmediate);
  assert.equal(f.nodes.mobilizationUseCount.disabled, true);
  assert.match(f.nodes.mobilizationUseMessage.textContent, /不会重复扣除/);
  f.nodes.mobilizationUseConfirm.onclick();
  await new Promise(setImmediate);
  assert.equal(f.uses.length, 2);
  assert.equal(f.uses[0][5], f.uses[1][5]);
});

test('动员到期仅更新倒计时，当前档位和容量等待服务端快照', () => {
  const f=setup();
  f.state.gameplay.mobilization={percent:50,key:'rapid:1',current:{id:'rapidMobilizationOrder',name:'极速战争动员令',percent:50,until:f.now()+60000},active:[]};
  f.state.gameplay.armyCap=151;
  assert.match(f.game.Mobilization.widget(),/0小时 1分钟/);
  f.advance(60000);
  assert.match(f.game.Mobilization.widget(),/已结束/);
  assert.equal(f.game.Mobilization.info().percent,50,'到期不得在浏览器选择新的生效档位');
  f.state.gameplay.mobilization={percent:10,key:'mobilization:1',current:{id:'mobilizationOrder',name:'战争动员令',percent:10,until:f.now()+86400000},active:[]};
  f.state.gameplay.armyCap=111;
  assert.equal(f.game.Mobilization.info().percent,10);
  f.state.gameplay.mobilization={percent:0,key:'default',current:null,active:[]};
  assert.match(f.game.Mobilization.widget(),/动员未启用/);
});

for (const [id, name, percent] of tiers) {
  test(name + '的账号效果用于行军上限，行军不携带或消耗道具', async () => {
    const f = setup();
    f.state.player.mobilizationExpiries = { [id]: f.now() + day };
    const cap = Math.floor(101 * (100 + percent) / 100);
    f.state.gameplay.armyCap=cap;
    f.state.world._dispatchTarget.mobilizationOrderItem = id;
    f.nodes.dqty_infantry = { value: String(cap) };
    f.nodes.estDispatchTroops = { style: {} };
    const view = {};
    f.game.World.renderDispatch(view);
    assert.doesNotMatch(view.innerHTML, /dpMobilizationOrder/);
    assert.match(view.innerHTML, /动员令在仓库使用/);
    f.game.World.updateDispatchStats();
    assert.equal(f.nodes.estDispatchTroops.textContent, cap + ' / ' + cap);
    f.game.World.launchDispatch();
    await new Promise(setImmediate);
    assert.equal(f.requests.length, 1);
    assert.equal(Object.hasOwn(f.requests[0], 'mobilizationOrderItem'), false);
    assert.equal(f.requests[0].army.infantry, cap);
    assert.equal(f.uses.length, 0);
  });
}

test('增益到期后新出征立即按普通上限校验，库存不再授予额外容量', () => {
  const f = setup();
  f.state.items.rapidMobilizationOrder = 5;
  f.state.player.mobilizationExpiries = { rapidMobilizationOrder: f.now() + 1 };
  f.state.world._dispatchTarget.mobilizationOrderItem = 'rapidMobilizationOrder';
  f.nodes.dqty_infantry = { value: '120' };
  f.advance(1);
  f.game.World.launchDispatch();
  assert.equal(f.requests.length, 0);
  assert.match(f.toasts.at(-1), /超过带兵上限 101/);
});

test('侦查、采集、进驻、运输均读取统一账号上限，不提交旧道具草稿', async () => {
  for (const [action, kind, unit] of [['scout', 'wild', 'scout'], ['gather', 'wild_gather', 'truck'],
    ['station', 'wild', 'infantry'], ['transport', 'player', 'truck']]) {
    const f = setup(action, kind);
    f.state.player.mobilizationExpiries = { rapidMobilizationOrder: f.now() + day };
    f.state.gameplay.armyCap=151;
    f.state.world._dispatchTarget.mobilizationOrderItem = 'rapidMobilizationOrder';
    f.nodes['dqty_' + unit] = { value: '120' };
    const view = {};
    f.game.World.renderDispatch(view);
    assert.doesNotMatch(view.innerHTML, /dpMobilizationOrder/);
    f.game.World.launchDispatch();
    await new Promise(setImmediate);
    assert.equal(f.requests.length, 1, action);
    assert.equal(Object.hasOwn(f.requests[0], 'mobilizationOrderItem'), false);
  }
});
