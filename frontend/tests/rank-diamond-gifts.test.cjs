const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

function gift(tier = 2, diamonds = 40) {
  return {
    tier, itemKey: 'rank_diamond_pack_' + tier, name: '上等兵晋升钻石大礼包', diamonds,
    cat: 'resource', icon: 'img/items/newbie-pack.svg', count: 1, openedAt: null,
    desc: '开启获得' + diamonds + '钻石，计入账号共享余额；可用于购买下士晋升凭证',
    nextCertificateTier: tier + 1, nextCertificateName: '下士晋升凭证'
  };
}

function rank(promotionReward = gift()) {
  return {
    tier: 1, name: '列兵', baseCap: 25000, nextTier: 2, nextName: '上等兵', nextBaseCap: 50000,
    prestige: 10000, reqPrestige: 10000, npcVictories: 10, reqNpcVictories: 10,
    certificate: { name: '上等兵晋升凭证', owned: 1, required: 1, source: '商城购买' },
    certificateEnough: true, canPromote: true, examPassed: true,
    exam: { status: 'passed', questionCount: 5, requiredCorrect: 4 }, promotionReward
  };
}

function setup() {
  const routes = [];
  const toasts = [];
  const G = {
    Core: {
      state: { player: { id: 1, militaryRank: 1 }, items: {}, resources: { diamond: 0 }, rankInfo: rank() },
      route: 'mainQuest', views: {}, render() {}
    },
    API: { getQuestList: async () => ({ chapters: [] }) },
    toast: message => toasts.push(message), go: route => routes.push(route),
    fmt: String, iconHtml: icon => icon, renderMilitaryRankIcon: () => ''
  };
  const context = vm.createContext({ Game: G, Date, console,
    setInterval() {}, clearInterval() {}, localStorage: { getItem() { return null; }, removeItem() {} },
    document: { getElementById() { return null; }, querySelectorAll() { return []; } }
  });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'shop.js', 'depot.js', 'main-quest.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  const view = {};
  G.MainQuest.state.playerId = 1;
  const renderRank = async () => {
    G.MainQuest.renderQuestView(view);
    await new Promise(resolve => setImmediate(resolve));
    return view.innerHTML;
  };
  const renderDepot = () => { G.Depot.renderView(view); return view.innerHTML; };
  return { G, context, routes, toasts, renderRank, renderDepot };
}

test('授衔前使用服务器的下一阶补给金额预告，上将显示荣耀奖励', async () => {
  const { G, renderRank } = setup();
  let html = await renderRank();
  assert.match(html, /晋升奖励：上等兵晋升钻石大礼包，内含 40 钻石；可购买下士晋升凭证/);
  assert.match(html, /授衔成功后入仓/);
  G.Core.state.rankInfo = rank({ ...gift(17, 5000), name: '上将荣耀钻石大礼包', nextCertificateName: undefined });
  html = await renderRank();
  assert.match(html, /内含 5000 钻石；最高军衔荣耀奖励/);
});

test('授衔成功后提示入仓并直达资源道具，开启后清除过期入仓提示', async () => {
  const { G, routes, toasts, renderRank } = setup();
  const reward = gift();
  G.API.promoteRank = async () => {
    G.Core.state.player.militaryRank = 2;
    G.Core.state.rankInfo = { tier: 2, name: '上等兵', baseCap: 50000, isMax: true };
    G.Core.state.rankDiamondGifts = { rewards: [reward], unopenedCount: 1 };
    return { success: true, promotionReward: reward, message: '上等兵晋升钻石大礼包已入仓' };
  };
  await G.MainQuest.doPromoteRank();
  assert.match(toasts.at(-1), /已入仓/);
  assert.equal(G.Core.state.resources.diamond, 0);
  let html = await renderRank();
  assert.match(html, /【上等兵晋升钻石大礼包】已入仓，开启后获得 40 钻石/);
  assert.match(html, /Game.Depot.openRankGifts\(\)/);
  G.Core.state._depotTab = 'equipment';
  G.Depot.openRankGifts();
  assert.equal(G.Core.state._depotTab, 'resource');
  assert.equal(routes.at(-1), 'depot');
  reward.openedAt = Date.now(); reward.count = 0;
  G.Core.state.rankDiamondGifts.unopenedCount = 0;
  html = await renderRank();
  assert.doesNotMatch(html, /已入仓|Game.Depot.openRankGifts/);
});

test('仓库只用回执承诺金额展示并调用开包，到账后直达对应凭证', async () => {
  const { G, routes, renderDepot } = setup();
  const reward = gift(2, 57);
  G.Core.state.player.militaryRank = 2;
  G.Core.state.items[reward.itemKey] = 1;
  G.Core.state.rankDiamondGifts = { rewards: [reward], unopenedCount: 1 };
  G.Depot.openRankGifts();
  let html = renderDepot();
  assert.match(html, /上等兵晋升钻石大礼包/);
  assert.match(html, /开启获得57钻石/);
  assert.match(html, /下士晋升凭证/);
  assert.match(html, /开启礼包/);
  const requests = [];
  G.API.depotUse = async (...args) => {
    requests.push(args);
    G.Core.state.items[reward.itemKey] = 0;
    G.Core.state.resources.diamond = 57;
    reward.openedAt = Date.now(); reward.count = 0;
    G.Core.state.rankDiamondGifts.unopenedCount = 0;
    return { success: true, message: '钻石 +57，已计入账号共享余额', nextCertificateTier: 3, nextCertificateName: '下士晋升凭证' };
  };
  await G.Depot._callBackend(reward.itemKey, null, null);
  assert.deepEqual(requests, [[reward.itemKey, null, null]]);
  html = renderDepot();
  assert.match(html, /已计入账号共享余额/);
  assert.match(html, /Game.Shop.openRankCertificate\(3\)/);
  assert.doesNotMatch(html, /开启礼包/);
  G.Shop.openRankCertificate(3);
  assert.equal(routes.at(-1), 'shop');
  assert.deepEqual(Array.from(G.Shop.items(), item => item.id), ['rank_certificate_3']);
  G.Core.state.items.rank_certificate_3 = 1;
  assert.doesNotMatch(renderDepot(), /Game.Shop.openRankCertificate/);
});

test('已持有凭证或最高军衔开包只展示到账结果，礼包不进入商城', async () => {
  const { G, renderDepot } = setup();
  G.Depot.openRankGifts();
  for (const tier of [2, 17]) {
    const reward = gift(tier, tier === 17 ? 5000 : 40);
    G.Core.state.player.militaryRank = tier;
    G.Core.state.items[reward.itemKey] = 1;
    G.Core.state.rankDiamondGifts = { rewards: [reward], unopenedCount: 1 };
    G.API.depotUse = async () => ({ success: true, message: '礼包钻石已到账' });
    await G.Depot._callBackend(reward.itemKey, null, null);
    assert.match(renderDepot(), /礼包钻石已到账/);
    assert.doesNotMatch(renderDepot(), /Game.Shop.openRankCertificate/);
  }
  assert.ok(G.Shop.items().every(item => !item.id.startsWith('rank_diamond_pack_')));
});

test('开包失败保留库存与余额，切换账号后忽略旧账号的开包结果', async () => {
  const { G, renderDepot } = setup();
  const reward = gift();
  G.Core.state.items[reward.itemKey] = 1;
  G.Core.state.rankDiamondGifts = { rewards: [reward], unopenedCount: 1 };
  G.API.depotUse = async () => ({ success: false, message: '余额无法容纳奖励' });
  await G.Depot._callBackend(reward.itemKey, null, null);
  assert.equal(G.Core.state.items[reward.itemKey], 1);
  assert.equal(G.Core.state.resources.diamond, 0);
  assert.equal(G.Core.state._rankGiftResult, undefined);
  let resolveOpen;
  G.API.depotUse = () => new Promise(resolve => { resolveOpen = resolve; });
  const pending = G.Depot._callBackend(reward.itemKey, null, null);
  G.Core.state = { player: { id: 2 }, items: {}, resources: { diamond: 0 } };
  resolveOpen({ success: true, message: '旧账号钻石已到账', nextCertificateTier: 3 });
  await pending;
  assert.equal(G.Core.state._rankGiftResult, undefined);
  assert.doesNotMatch(renderDepot(), /旧账号钻石已到账/);
});

test('开包接口只提交物品 key，切换登录身份后不应用迟到的旧账号状态', async () => {
  const { G, context } = setup();
  let token = 'player-1-token';
  let resolveOpen;
  const requests = [];
  G.ApiClient = function () {
    this.getToken = () => token;
    this.post = (url, body) => {
      requests.push({ url, body });
      return new Promise(resolve => { resolveOpen = resolve; });
    };
  };
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/api.js'), 'utf8'), context);
  const pending = G.API.depotUse('rank_diamond_pack_2');
  assert.deepEqual(JSON.parse(JSON.stringify(requests)), [{ url: '/game/depot/use', body: { itemId: 'rank_diamond_pack_2' } }]);
  token = 'player-2-token';
  const secondPlayer = { player: { id: 2 }, resources: { diamond: 100 } };
  G.Core.state = secondPlayer;
  resolveOpen({ success: true, state: { player: { id: 1 }, resources: { diamond: 40 } } });
  await pending;
  assert.equal(G.Core.state, secondPlayer);
});
