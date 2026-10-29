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
        shopBuy(id, count) { calls.push(count && count > 1 ? ['buy', id, count] : ['buy', id]); return Promise.resolve({ success: true }); },
        shopRecharge(id) { calls.push(['recharge', id]); return Promise.resolve({ success: true, diamonds: 60 }); },
        depotUse(id, officerId, newName) { calls.push(['use', id, officerId, newName]); return Promise.resolve({ success: true, message: '礼包已开启' }); }
      },
      toast(message) { toasts.push(message); },
      fmt: String,
      iconHtml(icon) { return icon; }
    }
  });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'shop.js', 'recharge.js', 'depot.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  return { game: context.Game, calls, toasts };
}

test('16档晋升凭证按对应军衔上架，前后端价格和仓库说明一致', async () => {
  const { game, calls } = setup();
  const prices = [20, 40, 60, 80, 100, 150, 200, 300, 400, 600, 800, 1000, 1200, 1500, 2000, 2500];
  game.Shop.setCat('rank');
  const products = game.Shop.items();
  assert.equal(products.length, 16);
  assert.ok(game.Constants.shopCategories.some(category => category.id === 'rank' && category.name === '晋升凭证'));
  const backend = fs.readFileSync(path.join(__dirname, '../../backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java'), 'utf8');
  const serverPrices = Array.from(backend.matchAll(/new RankInfo\(\s*(\d+),\s*"[^"]+",\s*\d+,\s*\d+,\s*\d+,\s*(\d+)\)/g),
    match => [Number(match[1]), Number(match[2])]);
  assert.deepEqual(serverPrices, [[1, 0], ...prices.map((price, index) => [index + 2, price])]);
  for (let index = 0; index < products.length; index++) {
    const product = products[index];
    const item = game.DATA.items[product.id];
    assert.equal(product.id, 'rank_certificate_' + (index + 2));
    assert.equal(product.rankTier, index + 2);
    assert.equal(product.price, prices[index]);
    assert.equal(product.name, item.name);
    assert.equal(product.desc, item.desc);
    assert.equal(item.cat, 'util');
    assert.match(product.desc, /商城「晋升凭证」购买/);
    assert.doesNotMatch(product.desc, /掉率|级寇|级流寇/);
    game.Shop.buy(product.id);
    await Promise.resolve();
  }
  assert.deepEqual(calls, prices.map((price, index) => ['buy', 'rank_certificate_' + (index + 2)]));
  const view = {};
  game.Shop.renderView(view);
  assert.match(view.innerHTML, /每档最多持有1张未使用凭证/);
  assert.match(view.innerHTML, /NPC不再掉落/);
});

test('晋升凭证拒绝重复、过期档位和批量购买，缺钻不发送请求', async () => {
  const { game, calls, toasts } = setup();
  game.Core.state.player = { militaryRank: 3 };
  game.Core.state.items.rank_certificate_4 = 5;
  game.Shop.buy('rank_certificate_2');
  game.Shop.buy('rank_certificate_3');
  game.Shop.buy('rank_certificate_4');
  game.Shop.buy('rank_certificate_5', 2);
  game.Shop.buy('rank_certificate_02');
  assert.equal(calls.length, 0);
  assert.match(toasts[0], /已获军衔/);
  assert.match(toasts[2], /已持有/);
  assert.match(toasts[3], /每次只能购买1张/);
  game.Shop.setCat('rank');
  const view = {};
  game.Shop.renderView(view);
  assert.match(view.innerHTML, /disabled[^>]*>已获军衔<\/button>/);
  assert.match(view.innerHTML, /disabled[^>]*>已持有<\/button>/);
  game.Core.state.resources.diamond = 79;
  game.Shop.buy('rank_certificate_5');
  assert.equal(calls.length, 0);
  assert.match(toasts.at(-1), /钻石不足/);
  game.Core.state.resources.diamond = 80;
  game.Core.state.items.rank_certificate_5 = 0;
  game.Shop.buy('rank_certificate_5');
  await Promise.resolve();
  assert.deepEqual(calls, [['buy', 'rank_certificate_5']]);
});

test('晋升入口清除旧筛选并定位所需凭证，库存中凭证继续跳转晋升', () => {
  const { game } = setup();
  const routes = [];
  game.go = route => routes.push(route);
  game.Shop.curCat = 'officer';
  game.Shop.keyword = '资源';
  game.Shop.equipmentTier = 'marshal';
  game.Shop.openRankCertificate(9);
  assert.equal(game.Shop.curCat, 'rank');
  assert.equal(game.Shop.keyword, '中尉晋升凭证');
  assert.equal(game.Shop.equipmentTier, '');
  assert.deepEqual(Array.from(game.Shop.items(), item => item.id), ['rank_certificate_9']);
  assert.deepEqual(routes, ['shop']);
  game.Core.state.items.rank_certificate_9 = 1;
  game.Depot.setTab('util');
  const view = {};
  game.Depot.renderView(view);
  assert.match(view.innerHTML, /中尉晋升凭证/);
  assert.match(view.innerHTML, /mainQuest/);
});

test('资源商品价格、商城和仓库说明一致，批量收益随档位递增', () => {
  const { game } = setup();
  const packs = [
    ['goldBox', 80, '开启获得5万黄金'],
    ['resBox', 80, '开启获得粮钢油稀各5万'],
    ['foodPack', 200, '开启获得60万粮食'],
    ['steelPack', 200, '开启获得60万钢铁'],
    ['oilPack', 200, '开启获得60万石油'],
    ['rarePack', 200, '开启获得60万稀矿'],
    ['supplyPack', 500, '开启获得粮钢油稀各40万'],
    ['resourcePack100w', 1150, '粮食/钢铁/石油/稀矿各100万'],
    ['resourcePack200w', 2200, '粮食/钢铁/石油/稀矿各200万'],
    ['resourcePack350w', 3700, '粮食/钢铁/石油/稀矿各350万'],
    ['resourcePack500w', 5000, '粮食/钢铁/石油/稀矿各500万']
  ];
  for (const [id, price, description] of packs) {
    const item = game.Shop.items().find(product => product.id === id);
    assert.equal(item.price, price);
    assert.equal(item.desc, description);
    assert.equal(game.DATA.items[id].desc, description);
  }
  assert.ok(200000 / 80 < 1600000 / 500);
  const efficiency = [[1600000, 500], [4000000, 1150], [8000000, 2200],
    [14000000, 3700], [20000000, 5000]].map(([amount, price]) => amount / price);
  assert.ok(efficiency.every((value, index) => index === 0 || value > efficiency[index - 1]));
});

test('新增资源礼包可从商城购买并在仓库开启', async () => {
  const { game, calls } = setup();
  const ids = ['foodPack', 'oilPack', 'rarePack', 'resourcePack100w', 'resourcePack200w', 'resourcePack350w'];
  for (const id of ids) {
    game.Shop.buy(id);
    game.Core.state.items[id] = 1;
    game.Depot.setTab('resource');
    const view = {};
    game.Depot.renderView(view);
    assert.ok(view.innerHTML.includes("Game.Depot.useItem('" + id + "')"));
    game.Depot.useItem(id);
    await Promise.resolve();
  }
  assert.deepEqual(calls, ids.flatMap(id => [['buy', id], ['use', id, null, null]]));
});

test('五档护盾商城和仓库定义一致，购买与使用传递对应道具', async () => {
  const { game, calls } = setup();
  game.Shop.curCat = 'util';
  const view = {};
  game.Shop.renderView(view);
  const tiers = [['shield2h', 2, 60], ['shield', 8, 200], ['shield12h', 12, 280], ['shield24h', 24, 500], ['shield72h', 72, 1350]];
  let previousHourlyPrice = Infinity;
  for (const [id, hours, price] of tiers) {
    const product = game.Shop.items().find(item => item.id === id);
    const inventory = game.DATA.items[id];
    assert.equal(product.name, hours + '小时护盾');
    assert.equal(product.price, price);
    assert.equal(product.name, inventory.name);
    assert.equal(product.desc, inventory.desc);
    assert.equal(inventory.cat, 'util');
    assert.ok(view.innerHTML.includes(product.name));
    assert.ok(price / hours < previousHourlyPrice);
    previousHourlyPrice = price / hours;
    game.Shop.buy(id);
    game.Core.state.items[id] = 1;
    game.Depot.setTab('util');
    const depotView = {};
    game.Depot.renderView(depotView);
    assert.ok(depotView.innerHTML.includes(product.name));
    assert.ok(depotView.innerHTML.includes("Game.Depot.useItem('" + id + "')"));
    game.Depot.useItem(id);
    await Promise.resolve();
  }
  assert.deepEqual(calls, tiers.flatMap(([id]) => [['buy', id], ['use', id, null, null]]));
});

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

test('充值页关闭后所有新旧档位均不会发起充值', async () => {
  const { game, calls } = setup();
  const view = {};
  game.Recharge.renderView(view);
  assert.doesNotMatch(view.innerHTML, /月卡|monthly/);
  assert.match(view.innerHTML, /暂未开放充值，敬请期待/);
  for (const id of ['mk30', 'wk98']) game.Recharge.confirmPay(id);
  assert.equal(calls.length, 0);

  game.Recharge.confirmPay('p6');
  await Promise.resolve();
  assert.deepEqual(calls, []);
});

test('普通书与通用书独立上架且通用书购买只入仓', async () => {
  const { game, calls } = setup();
  const ordinary = game.Constants.shopItems.find(item => item.id === 'skillBook');
  const universal = game.Constants.shopItems.find(item => item.id === 'universalSkillBook');
  assert.equal(ordinary.name, '普通技能书');
  assert.equal(ordinary.price, 80);
  assert.equal(universal.price, 800);
  assert.equal(universal.tag, '自选');
  assert.match(universal.desc, /Lv\.1.*不用于升级/);
  game.Shop.buy('universalSkillBook');
  await Promise.resolve();
  assert.deepEqual(calls, [['buy', 'universalSkillBook']]);
  assert.equal(game.DATA.items.universalSkillBook.cat, 'officer');
});

test('批量购买道具传递数量参数并触发批量提示', async () => {
  const { game, calls, toasts } = setup();
  game.Shop.buy('speedUp10m', 5);
  await Promise.resolve();
  assert.deepEqual(calls, [['buy', 'speedUp10m', 5]]);
  assert.match(toasts[0], /已购买 10分加速符 ×5/);

  // 钻石不足以支付批量总额时拒绝
  game.Core.state.resources.diamond = 50; // 5个10分加速符需150钻石
  game.Shop.buy('speedUp10m', 5);
  assert.match(toasts[1], /钻石不足/);
  assert.equal(calls.length, 1);
});

test('关键字搜索与军官二级分类正确过滤商品列表', () => {
  const { game } = setup();

  // 1. 搜索过滤
  game.Shop.curCat = 'all';
  game.Shop.setKeyword('加速符');
  const speedups = game.Shop.items();
  assert.ok(speedups.length >= 8);
  assert.ok(speedups.every(it => it.name.includes('加速符')));

  game.Shop.setKeyword('元帅');
  const marshalItems = game.Shop.items();
  assert.ok(marshalItems.length > 0);
  assert.ok(marshalItems.every(it => it.name.includes('元帅') || (it.desc && it.desc.includes('元帅'))));

  game.Shop.setKeyword('');

  // 2. 军官二级分类：套装装备箱
  game.Shop.setCat('officer');
  game.Shop.setSubCat('gear');
  const gearItems = game.Shop.items();
  assert.ok(gearItems.length >= 12);
  assert.ok(gearItems.every(it => Boolean(it.equipmentSet)));

  // 3. 军官二级分类：技能秘籍
  game.Shop.setSubCat('skill');
  const skillItems = game.Shop.items();
  assert.ok(skillItems.length >= 10);
  assert.ok(skillItems.every(it => it.id.startsWith('skillBook') || it.id === 'universalSkillBook'));

  // 4. 军官二级分类：养成与进阶
  game.Shop.setSubCat('progression');
  const progItems = game.Shop.items();
  assert.ok(progItems.length > 0);
  assert.ok(progItems.every(it => !it.equipmentSet && !it.id.startsWith('skillBook') && it.id !== 'universalSkillBook'));
  assert.ok(progItems.some(it => it.id === 'starUp'));
  assert.ok(progItems.some(it => it.id === 'expBook'));
});

test('商品卡片正确展示仓库存量且征募令为无限购', () => {
  const { game } = setup();
  game.Core.state.items = { speedUp10m: 12, recruitOrd: 2 };
  game.Shop.setCat('all');
  game.Shop.setKeyword('');

  const view = {};
  game.Shop.renderView(view);

  // 验证存量展示（符合设计文档 <span class="shop-owned">已有: X</span> 规范）
  assert.match(view.innerHTML, /<span class="shop-owned">已有: 12<\/span>/);
  assert.match(view.innerHTML, /<span class="shop-owned">已有: 2<\/span>/);

  // 验证性价比与折扣标识展示
  assert.match(view.innerHTML, /class="shop-efficiency">折合 180钻\/时<\/div>/);

  // 验证军官二级分类标签命名严格匹配设计规范
  game.Shop.setCat('officer');
  const officerView = {};
  game.Shop.renderView(officerView);
  assert.match(officerView.innerHTML, />套装装备箱<\/span>/);
  assert.match(officerView.innerHTML, />技能秘籍<\/span>/);
  assert.match(officerView.innerHTML, />军官进阶<\/span>/);

  // 验证征募令无限购
  const recruitOrd = game.Shop.items().find(it => it.id === 'recruitOrd');
  assert.equal(recruitOrd.stock, null);
  assert.match(view.innerHTML, /征募令/);
  // 不再有“每个账号限购 3 份”
  assert.doesNotMatch(view.innerHTML, /每个账号限购 3 份/);
});

test('三档行军令展示对应速度和价格，购买、仓库使用均传递独立库存键', async () => {
  const { game, calls } = setup();
  game.Shop.curCat = 'util';
  const tiers = [['basicMarchOrd', '初级行军令', 10, 100], ['advancedMarchOrd', '高级行军令', 25, 250], ['marchOrd', '极速行军令', 50, 500]];
  const view = {};
  game.Shop.renderView(view);
  for (const [id, name, percent, price] of tiers) {
    const product = game.Shop.items().find(item => item.id === id);
    assert.equal(product.name, name);
    assert.equal(product.price, price);
    assert.equal(product.stock, null);
    assert.equal(product.desc, game.DATA.items[id].desc);
    assert.ok(product.desc.includes('行军速度+' + percent + '%'));
    assert.ok(view.innerHTML.includes(name));
    game.Shop.buy(id);
    game.Core.state.items[id] = 1;
    game.Depot.useItem(id);
    await Promise.resolve();
  }
  assert.deepEqual(calls, tiers.flatMap(([id]) => [['buy', id], ['use', id, null, null]]));
});

test('极速经验书可购买入仓并选择军官进入对应档位的用书弹窗', async () => {
  const { game, calls } = setup();
  const opened = [], routes = [];
  game.Officer = { openExpBookModal(...args) { opened.push(args); } };
  game.go = route => routes.push(route);
  game.Shop.buy('expBookRapid');
  await Promise.resolve();
  assert.deepEqual(calls, [['buy', 'expBookRapid']]);
  game.Core.state.items.expBookRapid = 2;
  game.Depot.setTab('officer');
  const view = {};
  game.Depot.renderView(view);
  assert.match(view.innerHTML, /极速经验书/);
  assert.match(view.innerHTML, /expBookRapid'\)">\[选择军官使用\]/);
  game.Depot.useItem('expBookRapid');
  assert.deepEqual(routes, ['depotUse']);
  game.Depot.useOnOfficer(17);
  assert.deepEqual(opened, [[17, 'expBookRapid']]);
  assert.deepEqual(calls, [['buy', 'expBookRapid']]);
});

test('商城展示充值未开放，钻石不足提示不再引导充值', () => {
  const { game, calls, toasts } = setup();
  const view = {};
  game.Shop.renderView(view);
  assert.match(view.innerHTML, /我的钻石/);
  assert.match(view.innerHTML, /💎 5000/);
  assert.match(view.innerHTML, /暂未开放充值/);
  assert.doesNotMatch(view.innerHTML, /前往充值/);
  game.Shop.promptRecharge('测试道具', 100, 50);
  assert.match(toasts.at(-1), /钻石不足.*100.*50.*暂未开放充值，敬请期待/);
  assert.deepEqual(calls, []);
});
