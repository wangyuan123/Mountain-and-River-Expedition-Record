const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

function setupGame() {
  const context = vm.createContext({
    Date,
    console,
    Math,
    window: null,
    document: {
      createElement: () => ({ classList: { contains: () => false, add: () => {}, remove: () => {} } }),
      body: { appendChild: () => {} },
      getElementById: () => null
    },
    Game: {
      DATA: {},
      Core: {},
      fmt: (n) => String(n),
      expNeeded: () => 100,
      toast: () => {}
    }
  });
  context.window = context;
  context.G = context.Game;

  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/icons.js'), 'utf8'), context);
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/core.js'), 'utf8'), context);

  context.Core = context.Game.Core;
  context.D = context.Game.DATA;
  return context;
}

function setupOfficerGame() {
  const context = setupGame();
  context.Game.API = {};
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/officer.js'), 'utf8'), context);
  return context;
}

test('数据定义包含三军统帅和军屯自给，旧补给兼容指向三军统帅，且所有技能均为4字名称', () => {
  const { D } = setupGame();
  assert.ok(D.officerSkills.leadership, '应有 leadership 技能');
  assert.equal(D.officerSkills.leadership.name, '三军统帅');
  assert.ok(D.officerSkills.ration, '应有 ration 技能');
  assert.equal(D.officerSkills.ration.name, '军屯自给');
  assert.ok(D.officerSkills.supply, '应有 supply 兼容定义');
  assert.equal(D.officerSkills.supply.name, '三军统帅');

  // 验证所有技能均为4个汉字
  for (const [key, def] of Object.entries(D.officerSkills)) {
    assert.equal(def.name.length, 4, `技能 ${key} 的名称「${def.name}」应为4个字`);
  }
});

test('Core.armyCap 仅受 使用服务器结果', () => { const {Core}=setupGame(); Core.state={player:{militaryRank:17},officers:[]}; Object.assign(Core.state.gameplay,{armyCap:720123,ordinaryArmyCap:600123}); assert.equal(Core.armyCap(),720123); assert.equal(Core.ordinaryArmyCap(),600123); Core.state.player.militaryRank=1; Core.state.buildings={wall:10}; assert.equal(Core.armyCap(),720123); });

test('Core.foodPerHour 受到 使用服务器结果', () => { const {Core}=setupGame(); Core.state={army:{infantry:100},officers:[],foodSupply:{totalPerHour:17,ownPerHour:17,supportPerHour:0}}; assert.equal(Core.foodPerHour(),17); Core.state.army.infantry=999; assert.equal(Core.foodPerHour(),17); });

test('数据定义包含绝境反击 使用服务器结果', () => { const {Core,D}=setupGame(); Core.state={officers:[{role:'commander',skillBonuses:{counter:1},skills:[]}]}; assert.equal(Core.skillBonus('counter'),1); assert.match(D.officerSkills.counter.desc,/100%反击/); Core.state.officers[0].skills=[{id:'counter',lv:1}]; assert.equal(Core.skillBonus('counter'),1); });

test('数据定义包含师夷长技 使用服务器结果', () => { const {Core,D}=setupGame(); assert.match(D.officerSkills.learn.desc,/30%/); Core.state={officers:[{role:'commander',skillBonuses:{learn:.3}}]}; assert.equal(Core.skillBonus('learn'),.3); delete Core.state.officers[0].skillBonuses; assert.equal(Core.skillBonus('learn'),0); });

test('数据定义包含借甲御敌 使用服务器结果', () => { const {Core,D}=setupGame(); assert.match(D.officerSkills.borrow_armor.desc,/30%/); Core.state={officers:[{role:'commander',skillBonuses:{borrow_armor:.3}}]}; assert.equal(Core.skillBonus('borrow_armor'),.3); delete Core.state.officers[0].skillBonuses; assert.equal(Core.skillBonus('borrow_armor'),0); });

test('普通技能书保留随机学习效果，新增独立通用书且所有正式技能均生成指定书', () => {
  const { D } = setupGame();
  const skillIds = [
    'frenzy', 'bulwark', 'blitz', 'suppress', 'pierce', 'leadership', 'medic',
    'harvest', 'construct', 'finance', 'research', 'ration', 'counter', 'learn', 'borrow_armor'
  ];

  assert.equal(D.items.skillBook.name, '普通技能书');
  assert.equal(D.items.universalSkillBook.name, '通用技能书');
  assert.match(D.items.universalSkillBook.desc, /自选.*满级.*无需升级/);
  assert.match(D.items.skillBook.desc, /随机学习/);
  assert.equal(D.items.skillBook_supply, undefined, '历史 supply 别名不应生成指定技能书');

  for (const skillId of skillIds) {
    const book = D.items[`skillBook_${skillId}`];
    assert.ok(book, `应生成 ${skillId} 的指定技能书`);
    assert.equal(book.skillId, skillId);
    assert.equal(book.name, `${D.officerSkills[skillId].name}技能书`);
    assert.match(book.desc, /直接学习/);
  }
});

// 测试夹具只声明服务端允许的技能，不根据本地技能列表重建学习规则。
function allowSkills(Game, overrides = {}) {
  const choices = Object.fromEntries(Object.keys(Game.DATA.officerSkills).map(id => [id, {allowed:true,reason:''}]));
  for (const officer of Game.Core.state.officers || []) officer.skillLearning = Object.assign({}, choices, overrides);
}

function setupUniversalBook() {
  const context = setupOfficerGame();
  const { Game } = context;
  const nodes = {};
  for (const selector of ['[data-skill-options]', '[data-skill-summary]', '[data-skill-confirm]', '[data-skill-cancel]', '[name="skillCategory"]']) {
    nodes[selector] = { disabled: false, textContent: '' };
  }
  let modal;
  let removed = 0;
  let renders = 0;
  const messages = [];
  Game.escapeHtml = value => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
  Game.toast = message => messages.push(message);
  Game.Core.render = () => { renders++; };
  Game.Core.state = { items: { universalSkillBook: 2, skillBook: 3 }, officers: [{ id: 7, name: '<将领>', skills: [] }] };
  allowSkills(Game);
  context.document.createElement = () => ({
    querySelector: selector => nodes[selector],
    parentNode: { removeChild: () => { removed++; } }
  });
  context.document.body.appendChild = element => { modal = element; };
  return { context, Game, nodes, messages, modal: () => modal, removed: () => removed, renders: () => renders };
}

test('自选技能池包含23项、识别历史别名、自由组合、满槽及库存，并支持分类', () => {
  const { Game } = setupUniversalBook();
  assert.equal(Game.Officer.universalSkillOptions(7, 'all').length, 23);
  assert.equal(Game.Officer.universalSkillOptions(7, 'domestic').length, 5);
  assert.equal(Game.Officer.universalSkillOptions(7, 'combat').length, 18);
  assert.equal(Game.Officer.universalSkillOptions(7).some(choice => choice.id === 'supply'), false);
  Game.Core.state.officers[0].skills = [{ id: 'supply', lv: 4 }, { id: 'bulwark', lv: 2 }];
  allowSkills(Game, {leadership:{allowed:false,reason:'已掌握'}});
  const choices = Game.Officer.universalSkillOptions(7);
  assert.equal(choices.find(choice => choice.id === 'leadership').reason, '已掌握');
  assert.equal(choices.find(choice => choice.id === 'borrow_armor').reason, '');
  Game.Core.state.officers[0].skills.push({ id: 'frenzy', lv: 1 });
  allowSkills(Game, {medic:{allowed:false,reason:'技能已满3个，请先废弃一个'}});
  assert.match(Game.Officer.universalSkillOptions(7).find(choice => choice.id === 'medic').reason, /已满3个/);
  Game.Core.state.officers[0].skills = [];
  Game.Core.state.items.universalSkillBook = 0;
  allowSkills(Game);
  assert.ok(Game.Officer.universalSkillOptions(7).every(choice => choice.reason === '通用技能书不足'));
  assert.equal(Game.Officer.universalSkillOptions(999).length, 0);
});

test('自选弹窗只在确认时扣书，重复点击只提交一次，成功后关闭刷新', async () => {
  const harness = setupUniversalBook();
  const { Game, nodes } = harness;
  const requests = [];
  let resolveRequest;
  Game.API.depotUse = (...args) => {
    requests.push(args);
    return new Promise(resolve => { resolveRequest = resolve; });
  };
  Game.Officer.openUniversalSkillBook(7);
  assert.match(harness.modal().innerHTML, /&lt;将领>/);
  assert.equal(nodes['[data-skill-confirm]'].disabled, true);
  assert.match(nodes['[data-skill-options]'].innerHTML, /value="frenzy"/);
  assert.equal(requests.length, 0);
  nodes['[data-skill-options]'].onchange({ target: { value: 'frenzy' } });
  assert.match(nodes['[data-skill-summary]'].textContent, /全军冲锋.*满级/);
  assert.equal(Game.Core.state.items.universalSkillBook, 2);
  const pending = nodes['[data-skill-confirm]'].onclick();
  await nodes['[data-skill-confirm]'].onclick();
  Game.Officer.closeUniversalSkillBook();
  assert.equal(harness.removed(), 0);
  assert.deepEqual(requests, [['universalSkillBook', 7, null, 'frenzy']]);
  resolveRequest({ success: true, message: '学习成功' });
  await pending;
  assert.equal(harness.removed(), 1);
  assert.equal(harness.renders(), 1);
});

test('自选弹窗筛选清空选择，取消不调用接口，失败可以重新选择', async () => {
  const harness = setupUniversalBook();
  const { Game, nodes } = harness;
  let requests = 0;
  Game.API.depotUse = () => { requests++; return Promise.resolve({ success: false, message: '技能已满' }); };
  Game.Officer.openUniversalSkillBook(7);
  nodes['[data-skill-options]'].onchange({ target: { value: 'frenzy' } });
  nodes['[name="skillCategory"]'].value = 'domestic';
  nodes['[name="skillCategory"]'].onchange();
  assert.doesNotMatch(nodes['[data-skill-options]'].innerHTML, /value="frenzy"/);
  assert.equal(nodes['[data-skill-confirm]'].disabled, true);
  nodes['[data-skill-cancel]'].onclick();
  assert.equal(requests, 0);
  Game.Officer.openUniversalSkillBook(7);
  nodes['[data-skill-options]'].onchange({ target: { value: 'finance' } });
  await nodes['[data-skill-confirm]'].onclick();
  assert.equal(requests, 1);
  assert.equal(nodes['[data-skill-confirm]'].disabled, true);
  assert.equal(nodes['[data-skill-cancel]'].disabled, false);
  assert.equal(Game.Core.state.items.universalSkillBook, 2);
});

test('网络结果未知时只刷新状态，不重放学习请求', async () => {
  const harness = setupUniversalBook();
  const { Game, nodes } = harness;
  let writes = 0;
  let reads = 0;
  let applied;
  Game.API.depotUse = () => { writes++; return Promise.reject(new Error('timeout')); };
  Game.API.getGameState = force => { reads++; assert.equal(force, true); return Promise.resolve({ items: { universalSkillBook: 1 } }); };
  Game.API.applyState = state => { applied = state; };
  Game.Officer.openUniversalSkillBook(7);
  nodes['[data-skill-options]'].onchange({ target: { value: 'frenzy' } });
  await nodes['[data-skill-confirm]'].onclick();
  assert.equal(writes, 1);
  assert.equal(reads, 1);
  assert.equal(applied.items.universalSkillBook, 1);
  assert.equal(harness.removed(), 1);
});

test('网络请求与状态刷新均失败时禁止再次学习，提示刷新确认结果', async () => {
  const harness = setupUniversalBook();
  const { Game, nodes } = harness;
  let writes = 0;
  Game.API.depotUse = () => { writes++; return Promise.reject(new Error('timeout')); };
  Game.API.getGameState = () => Promise.reject(new Error('offline'));
  Game.Officer.openUniversalSkillBook(7);
  nodes['[data-skill-options]'].onchange({ target: { value: 'frenzy' } });
  await nodes['[data-skill-confirm]'].onclick();
  await nodes['[data-skill-confirm]'].onclick();
  assert.equal(writes, 1);
  assert.equal(nodes['[data-skill-confirm]'].disabled, true);
  assert.equal(nodes['[data-skill-confirm]'].textContent, '请刷新页面确认结果');
});

test('仓库使用接口独立传递目标技能并应用服务端库存，旧请求不附带技能参数', async () => {
  const { context, Game } = setupUniversalBook();
  const requests = [];
  const state = { player: { id: 1 }, officers: [{ id: 7, skills: [{ id: 'frenzy', lv: 1 }] }], items: { universalSkillBook: 1 } };
  Game.ApiClient = function () {
    this.getToken = () => 'test-token';
    this.post = (url, body) => { requests.push({ url, body }); return Promise.resolve({ success: true, state }); };
  };
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/api.js'), 'utf8'), context);
  await Game.API.depotUse('universalSkillBook', 7, null, 'frenzy');
  assert.equal(requests[0].url, '/game/depot/use');
  assert.deepEqual(JSON.parse(JSON.stringify(requests[0].body)), { itemId: 'universalSkillBook', officerId: 7, skillId: 'frenzy' });
  assert.equal(Game.Core.state, state);
  await Game.API.depotUse('skillBook', 7);
  assert.deepEqual(JSON.parse(JSON.stringify(requests[1].body)), { itemId: 'skillBook', officerId: 7 });
});

test('仓库选择军官后打开同一自选弹窗，满槽军官不可使用', () => {
  const harness = setupUniversalBook();
  const { context, Game } = harness;
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/depot.js'), 'utf8'), context);
  Game.Core.state._depotSelectOfficer = 'universalSkillBook';
  Game.Depot.useOnOfficer(7);
  assert.match(harness.modal().innerHTML, /自选学习/);
  Game.Officer.closeUniversalSkillBook();
  Game.Core.state.officers[0].skills = [{ id: 'frenzy' }, { id: 'medic' }, { id: 'pierce' }];
  const view = {};
  Game.iconHtml = icon => icon;
  Game.Depot.renderUse(view);
  assert.match(view.innerHTML, /技能已满3个，请先废弃一个/);
  assert.doesNotMatch(view.innerHTML, /onclick="Game\.Depot\.useOnOfficer/);
});

test('军官技能页可列出并使用背包中的指定技能书', async () => {
  const { Game } = setupOfficerGame();
  Game.Core.state = {
    items: { skillBook_frenzy: 2, skillBook_finance: 1, skillBook: 3 },
    officers: [{ id: 7, skills: [] }]
  };
  Game.Core.render = () => {};

  allowSkills(Game);
  const books = Game.Officer.availableSpecificSkillBooks();
  assert.equal(JSON.stringify(books.map((book) => [book.itemId, book.count])), JSON.stringify([
    ['skillBook_frenzy', 2],
    ['skillBook_finance', 1]
  ]));

  let request;
  Game.API.depotUse = (itemId, officerId) => {
    request = { itemId, officerId };
    return Promise.resolve({ success: true, message: '学习成功' });
  };

  await Game.Officer.useSpecificSkillBook(7, 'skillBook_finance');
  assert.equal(request.itemId, 'skillBook_finance');
  assert.equal(request.officerId, 7);
});

test('军官技能列表显示图标和可点击的技能名称，详情在弹窗内展示', () => {
  const context = setupOfficerGame();
  const { Game } = context;
  Game.escapeHtml = value => String(value);
  Game.Core.state = {
    _detailOfficerId: 7,
    items: {},
    officers: [{
      id: 7, name: '古德里安', star: 5, level: 10, exp: 0, role: 'idle', loyalty: 100,
      military: 90, logistics: 80, defense: 70, knowledge: 60,
      skills: [{ id: 'frenzy', lv: 2 }]
    }]
  };
  const view = { innerHTML: '' };
  Game.Officer.renderDetail(view);

  assert.match(view.innerHTML, /class="officer-skill-detail-trigger"[^>]*><img[^>]*src="img\/skills\/frenzy\.svg"[^>]*> 全军冲锋（满级）<\/button>/);
  assert.doesNotMatch(view.innerHTML, /\[升级\]/);
  assert.match(view.innerHTML, /\[废弃\]<\/button>/);
  assert.doesNotMatch(view.innerHTML, /攻击力额外\+10%\/级/);

  const modalNodes = { '.officer-skill-detail-close': {} };
  let modal;
  context.document.createElement = () => ({
    querySelector: selector => modalNodes[selector] || null,
    addEventListener: () => {}
  });
  context.document.body = { appendChild: node => { modal = node; } };
  Game.Officer.showSkillDetail('frenzy', 2);

  assert.match(modal.innerHTML, /全军冲锋/);
  assert.match(modal.innerHTML, /技能效果 <b>满级<\/b>/);
  assert.match(modal.innerHTML, /首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），全军攻击力额外\+50%/);
});

test('指定技能书渲染图片，已掌握技能禁用，防御技能可自由组合', () => {
  const { Game } = setupOfficerGame();
  Game.escapeHtml = value => String(value);
  Game.Core.state = {
    _detailOfficerId: 7,
    items: { skillBook_frenzy: 3, skillBook_borrow_armor: 2, skillBook_medic: 1 },
    officers: [{
      id: 7, name: '军官', star: 5, level: 100, exp: 0, role: 'idle', loyalty: 90,
      military: 90, logistics: 80, defense: 70, knowledge: 60,
      skills: [{ id: 'frenzy', lv: 1 }, { id: 'bulwark', lv: 1 }]
    }]
  };
  allowSkills(Game, {frenzy:{allowed:false,reason:'已掌握'}});
  const view = { innerHTML: '' };
  Game.Officer.renderDetail(view);
  const buttons = view.innerHTML.match(/<button\b[^>]*>[\s\S]*?<\/button>/g);
  const combined = buttons.find(button => button.includes('使用借甲御敌技能书'));
  const duplicate = buttons.find(button => button.includes('使用全军冲锋技能书'));
  const available = buttons.find(button => button.includes('使用战地急救技能书'));
  assert.match(combined, /<img[^>]*src="img\/items\/skill-book\.svg"/);
  assert.doesNotMatch(combined, / disabled/);
  assert.match(combined, /onclick="Game\.Officer\.useSpecificSkillBook/);
  assert.match(duplicate, / disabled/);
  assert.match(duplicate, /已掌握/);
  assert.doesNotMatch(available, / disabled/);
  assert.match(available, /onclick="Game\.Officer\.useSpecificSkillBook/);
  assert.doesNotMatch(view.innerHTML, />\[img\/items\/skill-book\.svg/);
});

test('指定技能书允许双向组合并发送学习请求', async () => {
  const { Game } = setupOfficerGame();
  const officer = { id: 7, skills: [] };
  Game.Core.state = { items: { skillBook_borrow_armor: 2, skillBook_bulwark: 3 }, officers: [officer] };
  const requests = [];
  const messages = [];
  Game.toast = message => messages.push(message);
  Game.Core.render = () => {};
  Game.API.depotUse = (...args) => { requests.push(args); return Promise.resolve({ success: true }); };
  allowSkills(Game);
  for (const [owned, target] of [['bulwark', 'borrow_armor'], ['borrow_armor', 'bulwark']]) {
    officer.skills = [{ id: 'frenzy', lv: 1 }, { id: owned, lv: 1 }];
    await Game.Officer.useSpecificSkillBook(7, 'skillBook_' + target);
    assert.deepEqual(requests[requests.length - 1], ['skillBook_' + target, 7]);
  }
  assert.equal(requests.length, 2);
  assert.equal(messages.some(message => message.includes('互斥')), false);
  assert.equal(Game.Core.state.items.skillBook_borrow_armor, 2, '客户端不自行扣书');
});

test('取消技能升级，旧入口不消耗书籍且详情不再展示升级按钮', () => {
  const { Game } = setupOfficerGame();
  Game.Core.state = {
    _detailOfficerId: 7, items: { skillBook_frenzy: 2 },
    officers: [{ id: 7, name: '军官', star: 1, level: 10, exp: 0, role: 'idle', loyalty: 60,
      military: 30, logistics: 30, defense: 30, knowledge: 30, skills: [{ id: 'frenzy', lv: 1 }] }]
  };
  const messages = [];
  Game.toast = message => messages.push(message);
  Game.API.upgradeSkill = () => { throw new Error('不应发送升级请求'); };
  Game.Officer.openSkillUpgrade(7, 0);
  assert.match(messages[0], /满级.*无需升级/);
  assert.equal(Game.Core.state.items.skillBook_frenzy, 2);
  const view = { innerHTML: '' };
  Game.Officer.renderDetail(view);
  assert.match(view.innerHTML, /全军冲锋（满级）/);
  assert.doesNotMatch(view.innerHTML, /\[升级\]|学习Lv\.1|升级已有技能|闲置时忠诚缓慢恢复|任职时缓慢下降/);
  assert.match(view.innerHTML, /连续欠薪满1分钟游戏时间/);
});

test('军官详情使用星耀符升星，并处理库存不足和满星状态', async () => {
  const { Game } = setupOfficerGame();
  const officer = {
    id: 7, name: '军官', star: 2, level: 10, exp: 0, role: 'idle', loyalty: 100,
    military: 30, logistics: 30, defense: 30, knowledge: 30, skills: []
  };
  Game.Core.state = { _detailOfficerId: 7, items: { starUp: 1 }, officers: [officer] };
  let renders = 0;
  const toasts = [];
  Game.Core.render = () => { renders++; };
  Game.toast = message => { toasts.push(message); };
  const view = { innerHTML: '' };
  Game.Officer.renderDetail(view);
  assert.match(view.innerHTML, /星耀符: 1枚 · 升星失败率20% · 每次消耗1枚/);
  assert.match(view.innerHTML, /class="btn sm depot-btn"[^>]*onclick="Game\.Officer\.upgradeStar\('7',this\)"[^>]*>\[升星至3★\]/);
  assert.match(view.innerHTML, /\[商城购买星耀符\]/);
  for (const [star, rate] of [[1, 10], [2, 20], [3, 30], [4, 60]]) {
    officer.star = star;
    Game.Officer.renderDetail(view);
    assert.match(view.innerHTML, new RegExp(`升星失败率${rate}%`));
  }
  officer.star = 2;

  let requests = 0;
  let complete;
  Game.API.depotUse = (itemId, officerId) => {
    assert.equal(itemId, 'starUp');
    assert.equal(officerId, 7);
    requests++;
    return new Promise(resolve => { complete = resolve; });
  };
  const button = { disabled: false };
  const pending = Game.Officer.upgradeStar(7, button);
  await Game.Officer.upgradeStar(7, button);
  assert.equal(requests, 1);
  assert.equal(button.disabled, true);
  complete({ success: true, upgraded: false, message: '升星失败，已消耗1枚星耀符' });
  await pending;
  assert.equal(button.disabled, false);
  assert.equal(renders, 1);
  assert.equal(toasts.at(-1), '升星失败，已消耗1枚星耀符');

  Game.Core.state.items.starUp = 0;
  Game.Officer.renderDetail(view);
  assert.match(view.innerHTML, /class="btn sm depot-btn"[^>]*disabled title="星耀符不足">\[升星至3★\]/);
  Game.Shop = { curCat: 'all' };
  let route;
  Game.go = value => { route = value; };
  Game.Officer.openStarUpShop();
  assert.equal(Game.Shop.curCat, 'officer');
  assert.equal(route, 'shop');

  officer.star = 5;
  Game.Officer.renderDetail(view);
  assert.match(view.innerHTML, /已达5星上限/);
  assert.doesNotMatch(view.innerHTML, /\[升星至|\[商城购买星耀符\]/);
});

test('账号动员在统帅后 使用服务器结果', () => { const {Core,Game}=setupGame(); Core.state={player:{militaryRank:17,mobilizationExpiries:{rapidMobilizationOrder:2000}},officers:[]}; Object.assign(Core.state.gameplay,{ordinaryArmyCap:720000,armyCap:1080000}); assert.equal(Core.armyCap(),1080000); Game.gameNow=()=>99999999; assert.equal(Core.armyCap(),1080000); Core.state.gameplay.armyCap=720000; assert.equal(Core.armyCap(),720000); });

test('技能组合介绍展示六种推荐，支持关闭、遮罩、Esc和重复打开且不改变技能库存', () => {
  const { context, Game } = setupUniversalBook();
  const before = JSON.stringify(Game.Core.state);
  const masks = [];
  let removes = 0;
  context.document.createElement = () => {
    const close = { onclick: null, focus() {} };
    const handlers = {};
    const mask = { innerHTML: '', handlers, close,
      querySelector: () => close,
      addEventListener: (type, handler) => { handlers[type] = handler; },
      parentNode: { removeChild: () => { removes++; } }
    };
    return mask;
  };
  context.document.body.appendChild = mask => masks.push(mask);
  Game.Officer.showSkillCombinations();
  assert.match(masks[0].innerHTML, /搭配方向.*三技能组合.*核心打法/);
  assert.equal((masks[0].innerHTML.match(/<tr>/g) || []).length, 7);
  for (const combo of Game.DATA.officerSkillCombinations) {
    assert.match(masks[0].innerHTML, new RegExp(combo.direction));
    for (const id of combo.skills) assert.match(masks[0].innerHTML, new RegExp(Game.DATA.officerSkills[id].name));
  }
  Game.Officer.showSkillCombinations();
  assert.equal(removes, 1);
  masks[1].handlers.click({ target: {} });
  assert.equal(removes, 1);
  masks[1].handlers.click({ target: masks[1] });
  assert.equal(removes, 2);
  Game.Officer.showSkillCombinations();
  masks[2].close.onclick();
  assert.equal(removes, 3);
  Game.Officer.showSkillCombinations();
  masks[3].handlers.keydown({ key: 'Enter' });
  assert.equal(removes, 3);
  masks[3].handlers.keydown({ key: 'Escape' });
  assert.equal(removes, 4);
  assert.equal(JSON.stringify(Game.Core.state), before);
});
