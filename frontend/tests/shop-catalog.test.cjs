const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const calls = [];
  const toasts = [];
  const context = vm.createContext({
    window: null,
    Game: {
      Core: { state: { resources: { diamond: 5000 }, items: {} }, views: {}, render() {} },
      API: {
        shopBuy(id) { calls.push(['buy', id]); return Promise.resolve({ success: true }); },
        shopRecharge(id) { calls.push(['recharge', id]); return Promise.resolve({ success: true, diamonds: 60 }); },
        depotUse(id, officerId, newName) { calls.push(['use', id, officerId, newName]); return Promise.resolve({ success: true, message: '礼包已开启' }); }
      },
      toast(message) { toasts.push(message); },
      iconHtml(icon) { return icon; }
    }
  });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'shop.js', 'recharge.js', 'depot.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  return { game: context.Game, calls, toasts };
}

test('新手礼包展示固定资源明细，购买后提示在仓库开启', async () => {
  const { game, calls, toasts } = setup();
  const gift = game.Constants.shopItems.find(item => item.id === 'newbiePack');
  assert.equal(gift.price, 99);
  assert.equal(gift.stock, 1);
  assert.equal(gift.desc, '开启获得：粮食×20000、钢铁×20000、石油×10000、稀矿×5000、黄金×3000');
  assert.equal(gift.desc, game.DATA.items.newbiePack.desc);
  assert.equal(game.DATA.items.newbiePack.cat, 'resource');
  const view = {};
  game.Shop.renderView(view);
  assert.doesNotMatch(view.innerHTML, /7倍/);
  assert.match(view.innerHTML, /每个账号限购 1 份/);
  game.Shop.buy('newbiePack');
  await Promise.resolve();
  assert.deepEqual(calls, [['buy', 'newbiePack']]);
  assert.match(toasts[0], /仓库的资源道具中开启/);
  assert.doesNotMatch(toasts[0], /邮件/);
});

test('新手礼包开启后的零库存仍显示已购买，切换城市不能重新购买', () => {
  const { game, calls } = setup();
  game.Core.state.items.newbiePack = 0;
  game.Core.state.player = { citySlot: 1 };
  const view = {};
  game.Shop.renderView(view);
  assert.match(view.innerHTML, /disabled[^>]*onclick="Game\.Shop\.buy\('newbiePack'\)">已购买<\/button>/);
  game.Shop.buy('newbiePack');
  assert.equal(calls.length, 0);
  game.Core.state.items.populationOrder = 0;
  game.Shop.buy('populationOrder');
  assert.deepEqual(calls, [['buy', 'populationOrder']]);
});

test('仓库资源分类可开启新手礼包，数量为零时不能重复开启', async () => {
  const { game, calls } = setup();
  game.Core.state.items.newbiePack = 1;
  game.Depot.setTab('resource');
  const view = {};
  game.Depot.renderView(view);
  assert.match(view.innerHTML, /新手礼包/);
  assert.match(view.innerHTML, /Game\.Depot\.useItem\('newbiePack'\)">\[开启礼包\]<\/button>/);
  game.Depot.useItem('newbiePack');
  await Promise.resolve();
  assert.deepEqual(calls, [['use', 'newbiePack', null, null]]);
  game.Core.state.items.newbiePack = 0;
  game.Depot.useItem('newbiePack');
  assert.equal(calls.length, 1);
  game.Depot.renderView(view);
  assert.doesNotMatch(view.innerHTML, /新手礼包/);
});

test('商城不展示停用礼包，仍可购买其他商品', async () => {
  const { game, calls } = setup();
  const view = {};
  game.Shop.renderView(view);
  assert.doesNotMatch(view.innerHTML, /周年庆|月卡/);
  for (const id of ['annivPack', 'monthCard', 'warChest']) game.Shop.buy(id);
  assert.equal(calls.length, 0);

  game.Shop.buy('populationOrder');
  await Promise.resolve();
  assert.deepEqual(calls, [['buy', 'populationOrder']]);
});

test('珠宝商品与道具已移除，军衔凭证仍可在功能分类中找到', () => {
  const { game, calls } = setup();
  const view = {};
  game.Shop.renderView(view);
  assert.doesNotMatch(view.innerHTML, /珠宝|夜明珠/);
  assert.equal(game.Constants.shopCategories.some(category => category.id === 'jewelry'), false);
  assert.equal(game.Constants.depotCategoryOrder.includes('jewelry'), false);
  for (const id of ['box_gem', 'box_gem_primary', 'box_gem_medium', 'box_gem_senior', 'box_gem_supreme', 'box_gem_grand']) {
    game.Shop.buy(id);
  }
  assert.equal(calls.length, 0);
  assert.equal(Object.keys(game.DATA.items).some(key => /^(gem_|box_gem)/.test(key)), false);
  for (const rank of game.DATA.militaryRanks) {
    if (rank.tier > 1) assert.equal(game.DATA.items['rank_certificate_' + rank.tier].cat, 'util');
  }
  for (const [key, item] of Object.entries(game.DATA.items)) {
    if (key.startsWith('stage_gift_')) assert.doesNotMatch(item.desc, /珠宝|宝石|夜明珠/);
  }
});

test('充值页移除月卡分类，旧档位不会发起充值', async () => {
  const { game, calls } = setup();
  const view = {};
  game.Recharge.renderView(view);
  assert.doesNotMatch(view.innerHTML, /月卡|monthly/);
  assert.match(view.innerHTML, /试玩补给/);
  for (const id of ['mk30', 'wk98']) game.Recharge.confirmPay(id);
  assert.equal(calls.length, 0);

  game.Recharge.confirmPay('p6');
  await Promise.resolve();
  assert.deepEqual(calls, [['recharge', 'p6']]);
});
