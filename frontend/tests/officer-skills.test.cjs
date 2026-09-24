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

test('Core.armyCap 仅受军衔、满级围墙和统帅技能影响，兼容旧 supply', () => {
  const { Core, D } = setupGame();
  D.militaryRanks.forEach((rank) => {
    assert.equal(rank.baseCap, rank.tier * 50000);
  });
  Core.state = {
    player: { militaryRank: 1 },
    buildings: { command: 1, staff: 0 },
    officers: [
      { id: 1, role: 'commander', level: 1, skills: [] }
    ]
  };

  assert.equal(Core.armyCap(), 50000);
  Core.state.buildings.command = 10;
  Core.state.buildings.staff = 10;
  Core.state.officers[0].level = 100;
  assert.equal(Core.armyCap(), 50000);

  Core.state.player.militaryRank = 17;
  assert.equal(Core.armyCap(), 850000);
  Core.state.buildings.wall = 9;
  assert.equal(Core.armyCap(), 850000);
  Core.state.buildings.wall = 10;
  assert.equal(Core.armyCap(), 950000);

  Core.state.officers[0].skills = [{ id: 'leadership', lv: 5 }];
  assert.equal(Core.armyCap(), 1140000);

  Core.state.officers[0].skills = [{ id: 'supply', lv: 5 }];
  assert.equal(Core.armyCap(), 1140000);
  Core.state.officers[0].role = 'mayor';
  assert.equal(Core.armyCap(), 950000);
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
  // 5级反击伤害系数为 50% (0.10 * 5)
  assert.equal(Math.round(Core.skillBonus('counter') * 100) / 100, 0.50);

  // 1级反击伤害系数为 10%
  Core.state.officers[0].skills = [{ id: 'counter', lv: 1 }];
  assert.equal(Math.round(Core.skillBonus('counter') * 100) / 100, 0.10);
});

test('数据定义包含师夷长技 learn，且每级攻击参考系数为6%', () => {
  const { Core, D } = setupGame();
  assert.ok(D.officerSkills.learn, '应有 learn 技能');
  assert.equal(D.officerSkills.learn.name, '师夷长技');
  assert.match(D.officerSkills.learn.desc, /单次最高敌方同名兵种攻击30%/);

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
  assert.match(D.officerSkills.borrow_armor.desc, /单次最高敌方同名兵种防御30%/);

  Core.state = {
    officers: [
      { id: 1, role: 'commander', level: 1, skills: [{ id: 'borrow_armor', lv: 5 }] }
    ]
  };
  assert.equal(Math.round(Core.skillBonus('borrow_armor') * 100) / 100, 0.30);
});

test('通用技能书保留随机学习效果，所有正式技能均生成对应指定技能书', () => {
  const { D } = setupGame();
  const skillIds = [
    'frenzy', 'bulwark', 'blitz', 'suppress', 'pierce', 'leadership', 'medic',
    'harvest', 'construct', 'finance', 'research', 'ration', 'counter', 'learn', 'borrow_armor'
  ];

  assert.equal(D.items.skillBook.name, '通用技能书');
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
  assert.match(modal.innerHTML, /攻击力额外\+10%\/级，第1、4、7…回合触发/);
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
