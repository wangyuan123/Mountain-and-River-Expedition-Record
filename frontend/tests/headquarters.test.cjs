const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(built = true) {
  const context = vm.createContext({
    window: null, console, Date, Math, setTimeout, clearTimeout,
    document: { getElementById() { return null; } },
    Game: {
      escapeHtml: value => String(value == null ? '' : value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'),
      fmt: String, expNeeded: () => 1000, API: {}, go() {}, toast() {}
    }
  });
  context.window = context;
  require('./load-constants.cjs')(context);
  for (const name of ['data', 'core', 'officer', 'battle', 'build']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/' + name + '.js'), 'utf8'), context);
  }
  context.Game.Core.state = {
    officers: [
      { id: 1, name: '市长', role: 'mayor', star: 3, level: 10, logistics: 100, knowledge: 90, skills: [{ id: 'finance', lv: 2 }] },
      { id: 2, name: '城市指挥官', role: 'commander', star: 4, level: 10, military: 100, defense: 100, skills: [{ id: 'leadership', lv: 3 }] },
      { id: 3, name: '<司令官>', role: 'commander_in_chief', star: 5, level: 10, baseMilitary: 219, military: 237, defense: 103, logistics: 83, knowledge: 200, skills: [{ id: 'frenzy', lv: 5 }, { id: 'leadership', lv: 5 }] }
    ],
    buildings: { headquarters: built ? 1 : 0, command: 1 }, tech: { attack_tech: 2 }, resources: {}, items: {}
  };
  return context.Game;
}

test('司令部有独立建筑与任命入口，现有两岗位仍可见', () => {
  const G = setup();
  assert.equal(G.DATA.buildings.headquarters.name, '司令部');
  assert.equal(G.Build.SPECIAL_ROUTES.headquarters, 'officer');
  assert.ok(G.Build.GROUPS.army.order.includes('headquarters'));
  const view = { innerHTML: '' };
  G.Officer.renderView(view);
  assert.match(view.innerHTML, /执政市长/);
  assert.match(view.innerHTML, /作战指挥官/);
  assert.match(view.innerHTML, /司令部 · 司令官/);
  assert.match(view.innerHTML, /取消司令官任命/);
  assert.match(view.innerHTML, /任司令官/);
  assert.match(view.innerHTML, /23\.7%/);
  assert.match(view.innerHTML, /&lt;司令官&gt;/);
});

test('岗位看板只展示有效属性倍率，司令官技能不会混入市长或指挥官技能', () => {
  const G = setup();
  const board = G.Officer.renderChiefBoard();
  assert.match(board, /装备、套装计入，技能不参与/);
  assert.doesNotMatch(board, /全军冲锋|领袖统率/);
  assert.equal(G.Core.getCommanderSkills().leadership, 3);
  assert.equal(G.Core.getCommanderSkills().frenzy, undefined);
  assert.equal(G.Core.getMayorSkills().finance, 2);
});

test('预览叠加装备后军事、防御与生命，司令部拆除后暂停，世界行军速度不受影响', () => {
  const G = setup();
  assert.ok(Math.abs(G.Core.atkMul('inf') - 1.1 * 2 * 1.237) < 1e-9);
  assert.ok(Math.abs(G.Core.defMul() - 1.103) < 1e-9);
  assert.ok(Math.abs(G.Core.hpMul('inf') - 1.083) < 1e-9);
  const speed = G.Core.spdMul('inf', 'infantry');
  G.Core.state.buildings.headquarters = 0;
  assert.equal(G.Core.commandBonusMul('military'), 1);
  assert.equal(G.Core.spdMul('inf', 'infantry'), speed);
  assert.match(G.Officer.renderChiefBoard(), /加成暂停/);
});

test('战场速度读取开战学识快照，城市当前司令官变化不改变预览', () => {
  const G = setup();
  G.Battle._activeTactical = { round: 0, attackerTech: {}, attackerSkills: {}, attackerCommandBonus: { knowledge: 200, rate: 0.001 } };
  const speed = G.Battle.getEffectiveSpeed('infantry');
  assert.equal(speed, 2.4);
  G.Core.state.officers[2].knowledge = 0;
  assert.equal(G.Battle.getEffectiveSpeed('infantry'), speed);
  assert.equal(G.Battle.getEffectiveSpeed('bunker'), 0);
  assert.equal(G.Battle.getEffectiveSpeed('infantry', {}, {}, { knowledge: 0 }), 2);
});

test('升级城防不改变部队防御预览，防御科技和司令官加成继续生效', () => {
  const G = setup();
  G.Core.state.tech.defense_tech = 5;
  const expected = 1.25 * 1.103;
  for (const level of [0, 1, 10, 20]) {
    G.Core.state.buildings.wall = level;
    assert.ok(Math.abs(G.Core.defMul() - expected) < 1e-9);
  }
  G.Core.state.buildings.headquarters = 0;
  assert.equal(G.Core.defMul(), 1.25);
});

test('战报保持原攻守身份，防守视角切换且旧战报无需新字段', () => {
  const G = setup();
  const bonuses = { attacker: { officerId: 1, name: '攻方司令', military: 100 }, defender: { officerId: 2, name: '守方司令', military: 200 } };
  const html = G.Battle.renderCommandBonusPanel(bonuses, 'defender');
  assert.ok(html.indexOf('守方司令') < html.indexOf('攻方司令'));
  assert.match(html, /20\.0%/);
  assert.match(html, /技能不参与/);
  assert.equal(G.Battle.renderCommandBonusPanel(null, 'attacker'), '');
});

test('出征队伍上限与司令部等级挂钩：未建5支，1级6支，每级递增1支，满级15支', () => {
  const G = setup(false);
  assert.equal(G.Core.maxMarches(), 5);

  G.Core.state.buildings.headquarters = 1;
  assert.equal(G.Core.maxMarches(), 6);

  G.Core.state.buildings.headquarters = 5;
  assert.equal(G.Core.maxMarches(), 10);

  G.Core.state.buildings.headquarters = 10;
  assert.equal(G.Core.maxMarches(), 15);

  // 验证建筑卡片渲染包含出征队伍
  const cardHtml = G.Build.renderBuilding('headquarters', G.DATA.buildings.headquarters, [], 0);
  assert.match(cardHtml, /出征队伍/);
  assert.match(cardHtml, /15 支/);
});
