const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

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

test('Core.armyCap 仅受军衔和统帅技能影响，城防不增加上限且兼容旧 supply', () => {
  const { Core, D } = setupGame();
  D.militaryRanks.forEach((rank) => {
    assert.equal(rank.baseCap, [25000, 50000, 75000, 100000, 125000, 150000, 175000, 200000, 225000, 250000, 300000, 350000, 400000, 450000, 500000, 550000, 600000][rank.tier - 1]);
  });
  Core.state = {
    player: { militaryRank: 1 },
    buildings: { command: 1, staff: 0 },
    officers: [
      { id: 1, role: 'commander', level: 1, skills: [] }
    ]
  };

  assert.equal(Core.armyCap(), 25000);
  assert.equal(Core.sortieCap(), 25000);
  Core.state.buildings.command = 10;
  Core.state.buildings.staff = 10;
  Core.state.officers[0].level = 100;
  assert.equal(Core.armyCap(), 25000);
  assert.equal(Core.sortieCap(), 25000);

  Core.state.player.militaryRank = 17;
  assert.equal(Core.armyCap(), 600000);
  Core.state.buildings.wall = 9;
  assert.equal(Core.armyCap(), 600000);
  Core.state.buildings.wall = 10;
  assert.equal(Core.armyCap(), 600000);

  Core.state.officers[0].skills = [{ id: 'leadership', lv: 5 }];
  assert.equal(Core.armyCap(), 720000);
  assert.equal(Core.sortieCap(), 720000);

  Core.state.officers[0].skills = [{ id: 'supply', lv: 5 }];
  assert.equal(Core.armyCap(), 720000);
  assert.equal(Core.sortieCap(), 720000);
  Core.state.officers[0].role = 'mayor';
  assert.equal(Core.armyCap(), 600000);
});

test('Core.foodPerHour 受到市长军屯技能降低', () => {
  const { Core, D } = setupGame();
  D.units.infantry = { food: 1 };
  Core.state = {
    army: { infantry: 100 },
    tech: { log_food: 0 },
    officers: [
      { id: 1, role: 'mayor', level: 1, skills: [] }
    ]
  };

  assert.equal(Core.foodPerHour(), 100);

  // 市长配置 军屯 Lv.5 (-80%)
  Core.state.officers[0].skills = [{ id: 'ration', lv: 5 }];
  assert.equal(Core.foodPerHour(), 20);
});

test('数据定义包含绝境反击 counter，且 skillBonus 正常生效', () => {
  const { Core, D } = setupGame();
  assert.ok(D.officerSkills.counter, '应有 counter 技能');
  assert.equal(D.officerSkills.counter.name, '绝境反击');

  Core.state = {
    officers: [
      { id: 1, role: 'commander', level: 1, skills: [{ id: 'counter', lv: 5 }] }
    ]
  };
  // 与服务端一致：每级增加 20 个百分点，满级按剩余兵力总伤害的 100% 反击。
  for (let level = 1; level <= 5; level++) {
    Core.state.officers[0].skills = [{ id: 'counter', lv: level }];
    assert.equal(Math.round(Core.skillBonus('counter') * 100), level * 20);
  }
  assert.match(D.officerSkills.counter.desc, /当前剩余兵力总伤害的20%\/级反击，最高100%/);
  for (const id of ['frenzy', 'bulwark', 'blitz', 'suppress', 'pierce', 'counter', 'learn', 'borrow_armor']) {
    assert.match(D.officerSkills[id].desc, /首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效）/);
  }
});

test('数据定义包含师夷长技 learn，且每级攻击参考系数为6%', () => {
  const { Core, D } = setupGame();
  assert.ok(D.officerSkills.learn, '应有 learn 技能');
  assert.equal(D.officerSkills.learn.name, '师夷长技');
  assert.match(D.officerSkills.learn.desc, /单次最高为敌军对应攻击的30%/);

  Core.state = {
    officers: [
      { id: 1, role: 'commander', level: 1, skills: [{ id: 'learn', lv: 5 }] }
    ]
  };
  assert.equal(Math.round(Core.skillBonus('learn') * 100) / 100, 0.30);
});

test('数据定义包含借甲御敌 borrow_armor，且每级防御参考系数为6%', () => {
  const { Core, D } = setupGame();
  assert.ok(D.officerSkills.borrow_armor, '应有 borrow_armor 技能');
  assert.equal(D.officerSkills.borrow_armor.name, '借甲御敌');
  assert.match(D.officerSkills.borrow_armor.desc, /单次最高为敌军同名兵种防御的30%/);

  Core.state = {
    officers: [
      { id: 1, role: 'commander', level: 1, skills: [{ id: 'borrow_armor', lv: 5 }] }
    ]
  };
  assert.equal(Math.round(Core.skillBonus('borrow_armor') * 100) / 100, 0.30);
});

test('普通技能书保留随机学习效果，新增独立通用书且所有正式技能均生成指定书', () => {
  const { D } = setupGame();
  const skillIds = [
    'frenzy', 'bulwark', 'blitz', 'suppress', 'pierce', 'leadership', 'medic',
    'harvest', 'construct', 'finance', 'research', 'ration', 'counter', 'learn', 'borrow_armor'
  ];

  assert.equal(D.items.skillBook.name, '普通技能书');
  assert.equal(D.items.universalSkillBook.name, '通用技能书');
  assert.match(D.items.universalSkillBook.desc, /自选.*Lv\.1.*不用于升级/);
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
  context.document.createElement = () => ({
    querySelector: selector => nodes[selector],
    parentNode: { removeChild: () => { removed++; } }
  });
  context.document.body.appendChild = element => { modal = element; };
  return { context, Game, nodes, messages, modal: () => modal, removed: () => removed, renders: () => renders };
}

test('自选技能池包含15项、识别历史别名、互斥、满槽及库存，并支持分类', () => {
  const { Game } = setupUniversalBook();
  assert.equal(Game.Officer.universalSkillOptions(7, 'all').length, 15);
  assert.equal(Game.Officer.universalSkillOptions(7, 'domestic').length, 5);
  assert.equal(Game.Officer.universalSkillOptions(7, 'combat').length, 10);
  assert.equal(Game.Officer.universalSkillOptions(7).some(choice => choice.id === 'supply'), false);
  Game.Core.state.officers[0].skills = [{ id: 'supply', lv: 4 }, { id: 'bulwark', lv: 2 }];
  const choices = Game.Officer.universalSkillOptions(7);
  assert.equal(choices.find(choice => choice.id === 'leadership').reason, '已掌握');
  assert.match(choices.find(choice => choice.id === 'borrow_armor').reason, /坚守阵地.*互斥/);
  Game.Core.state.officers[0].skills.push({ id: 'frenzy', lv: 1 });
  assert.match(Game.Officer.universalSkillOptions(7).find(choice => choice.id === 'medic').reason, /已满3个/);
  Game.Core.state.officers[0].skills = [];
  Game.Core.state.items.universalSkillBook = 0;
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
  assert.match(nodes['[data-skill-summary]'].textContent, /全军冲锋.*Lv\.1/);
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

test('军官技能列表仅显示可点击的技能名称，详情在弹窗内展示', () => {
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

  assert.match(view.innerHTML, /class="officer-skill-detail-trigger"[^>]*>全军冲锋 Lv\.2\/5<\/button>/);
  assert.match(view.innerHTML, /\[升级\]<\/button>.*\[废弃\]<\/button>/);
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
  assert.match(modal.innerHTML, /当前等级 <b>Lv\.2<\/b>/);
  assert.match(modal.innerHTML, /首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），全军攻击力额外\+10%\/级/);
});

test('军官技能升级只选择对应的指定技能书，满级禁用', async () => {
  const context = setupOfficerGame();
  const { Game } = context;
  Game.escapeHtml = value => String(value);
  Game.Core.state = {
    _detailOfficerId: 7,
    items: { skillBook_frenzy: 2, skillBook_finance: 3, skillBook: 4 },
    officers: [{
      id: 7, name: '军官', star: 1, level: 10, exp: 0, role: 'idle', loyalty: 100,
      military: 30, logistics: 30, defense: 30, knowledge: 30,
      skills: [{ id: 'frenzy', lv: 1 }]
    }]
  };
  let modal;
  const bookButton = { disabled: false };
  context.document.createElement = () => ({
    querySelector: selector => selector === '.skill-upgrade-book' ? bookButton : {},
    parentNode: { removeChild: () => {} }
  });
  context.document.body = { appendChild: node => { modal = node; } };
  let request;
  let renders = 0;
  Game.Core.render = () => { renders++; };
  Game.API.upgradeSkill = (...args) => {
    request = args;
    return Promise.resolve({ success: true, message: '升级成功' });
  };

  Game.Officer.openSkillUpgrade(7, 0);
  assert.match(modal.innerHTML, /全军冲锋技能书.*×2/);
  assert.doesNotMatch(modal.innerHTML, /精明理财技能书|通用技能书/);
  await bookButton.onclick();
  assert.deepEqual(request, [7, 0, 'skillBook_frenzy']);
  assert.equal(renders, 1);

  Game.Core.state.officers[0].skills[0].lv = 5;
  const view = { innerHTML: '' };
  Game.Officer.renderDetail(view);
  assert.match(view.innerHTML, /title="技能已满级">\[升级\]<\/button>/);
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

test('账号动员在统帅后的普通容量上计算，攻守一致且到期即时恢复', () => {
  const context = setupGame();
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/mobilization.js'), 'utf8'), context);
  let now = 1000;
  context.Game.gameNow = () => now;
  context.Core.state = {
    player: { militaryRank: 17, mobilizationCycle: 1, mobilizationExpiries: { rapidMobilizationOrder: 2000 } },
    officers: [{ role: 'commander', skills: [{ id: 'leadership', lv: 5 }] }]
  };
  assert.equal(context.Core.ordinaryArmyCap(), 720000);
  assert.equal(context.Core.armyCap(), 1080000);
  assert.equal(context.Core.sortieCap(), 1080000);
  now = 2000;
  assert.equal(context.Core.armyCap(), 720000);
  assert.equal(context.Core.sortieCap(), 720000);
});
