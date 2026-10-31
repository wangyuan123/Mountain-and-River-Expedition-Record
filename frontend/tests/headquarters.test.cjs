const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

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
      { id: 1, name: '市长', role: 'mayor', star: 3, level: 10, logistics: 100, knowledge: 90, skills: [{ id: 'finance', lv: 5 }] },
      { id: 2, name: '城市指挥官', role: 'commander', star: 4, level: 10, military: 100, defense: 100, skills: [{ id: 'leadership', lv: 5 }] },
      { id: 3, name: '<司令官>', role: 'commander_in_chief', star: 5, level: 10, baseMilitary: 219, military: 237, defense: 103, logistics: 83, knowledge: 200, skills: [{ id: 'frenzy', lv: 5 }, { id: 'leadership', lv: 5 }] }
    ],
    buildings: { headquarters: built ? 1 : 0, command: 1 }, tech: { attack_tech: 2 }, resources: {}, items: {}
  };
  context.Game.Core.state.officers[2].chiefPercents = { military: 23.7, defense: 10.3, logistics: 8.3, knowledge: 20 };
  context.Game.Core.state.gameplay.groupSlots.army.used = 5;
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
  assert.equal(G.Core.getCommanderSkills().leadership, 5);
  assert.equal(G.Core.getCommanderSkills().frenzy, undefined);
  assert.equal(G.Core.getMayorSkills().finance, 5);
});

test('未建司令部时任命按钮可解释原因，并提供军工建设入口', async () => {
  const G = setup(false);
  const messages = [];
  let requests = 0;
  G.toast = message => messages.push(message);
  G.API.appointOfficer = () => { requests++; return Promise.resolve({ success: true }); };
  const view = { innerHTML: '' };
  G.Officer.renderView(view);
  assert.match(view.innerHTML, /任司令官（需建司令部）/);
  assert.match(view.innerHTML, /Game.go\('buildArmy'\).*前往建造司令部/);
  assert.doesNotMatch(view.innerHTML, /disabled[^>]*onclick="[^"]*commander_in_chief/);
  await G.Officer.appoint('2', 'commander_in_chief');
  assert.equal(requests, 0);
  assert.deepEqual(messages, ['请先到军工建造司令部，再任命司令官']);
});

test('司令部建成后可以把指挥官改任司令官，并刷新岗位显示', async () => {
  const G = setup();
  const messages = [];
  let renders = 0;
  G.toast = message => messages.push(message);
  G.Core.render = () => { renders++; };
  G.API.appointOfficer = async (id, role) => {
    assert.equal(id, '2');
    assert.equal(role, 'commander_in_chief');
    G.Core.state.officers[2].role = 'idle';
    G.Core.state.officers[1].role = role;
    return { success: true, message: '任命成功！城市指挥官 出任 司令官' };
  };
  await G.Officer.appoint('2', 'commander_in_chief');
  assert.equal(renders, 1);
  assert.match(messages[0], /城市指挥官 出任 司令官/);
  assert.match(G.Officer.renderChiefBoard(), /城市指挥官/);
});

test('司令部拆除后仍可取消任命，行军军官则明确提示不能任命', async () => {
  const G = setup(false);
  const messages = [];
  let requests = 0;
  G.toast = message => messages.push(message);
  G.Core.render = () => {};
  G.API.appointOfficer = async () => { requests++; return { success: true, message: '取消任命成功' }; };
  await G.Officer.appoint('3', 'commander_in_chief');
  assert.equal(requests, 1);
  assert.equal(messages[0], '取消任命成功');
  G.Core.state.officers[1].role = 'march';
  await G.Officer.appoint('2', 'commander_in_chief');
  assert.equal(requests, 1);
  assert.equal(messages[1], '军官正在行军，无法任命');
});

test('任命被服务器拒绝时显示错误并保留原岗位', async () => {
  const G = setup();
  const messages = [];
  G.toast = message => messages.push(message);
  G.Core.render = () => assert.fail('失败时不应渲染成功状态');
  G.API.appointOfficer = async () => { throw new Error('军官正在行军，无法任命'); };
  await G.Officer.appoint('2', 'commander_in_chief');
  assert.equal(G.Core.state.officers[1].role, 'commander');
  assert.deepEqual(messages, ['军官正在行军，无法任命']);
});

test('任命接口保留结果文案，仅成功响应应用最新城市状态', async () => {
  const G = setup();
  let response;
  G.ApiClient = class {
    post(url, body) {
      assert.equal(url, '/game/officer/appoint');
      assert.equal(body.role, 'commander_in_chief');
      return Promise.resolve(response);
    }
  };
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/api.js'), 'utf8'), vm.createContext({ window: { Game: G } }));
  const oldState = G.Core.state;
  response = { success: false, message: '军官不存在', state: { officers: [] } };
  await assert.rejects(G.API.appointOfficer('2', 'commander_in_chief'), /军官不存在/);
  assert.equal(G.Core.state, oldState);
  response = { success: true, message: '取消任命成功', state: { officers: [], buildings: {} } };
  assert.equal(await G.API.appointOfficer('3', 'commander_in_chief'), response);
  assert.equal(G.Core.state, response.state);
});

test('司令部拆除提示暂停，浏览器不保留攻防与世界行军公式', () => {
  const G = setup();
  for (const key of ['atkMul', 'defMul', 'hpMul', 'spdMul', 'commandBonusMul']) assert.equal(G.Core[key], undefined);
  G.Core.state.buildings.headquarters = 0;
  assert.match(G.Officer.renderChiefBoard(), /加成暂停/);
});

test('战场速度读取战斗快照，城市实时军官不能改写它', () => {
  const G = setup();
  G.Battle._activeTactical = { attackerStats: { infantry: { speed: 2.4 } } };
  assert.equal(G.Battle.getEffectiveSpeed('infantry'), 2.4);
  G.Core.state.officers[2].knowledge = 0;
  assert.equal(G.Battle.getEffectiveSpeed('infantry'), 2.4);
  assert.equal(G.Battle.getEffectiveSpeed('bunker'), 0);
});

test('城防等级和当前科技不能重建战场有效属性', () => {
  const G = setup();
  G.Battle._activeTactical = { attackerStats: { infantry: { range: 177, speed: 2.7 } } };
  G.Core.state.tech.defense_tech = 10; G.Core.state.buildings.wall = 10;
  assert.equal(G.Core.defMul, undefined);
  assert.equal(G.Battle.getEffectiveRange('infantry'), 177);
});

test('战报保持原攻守身份，防守视角切换且旧战报无需新字段', () => {
  const G = setup();
  const bonuses = { attacker: { officerId: 1, name: '攻方司令', military: 100 }, defender: { officerId: 2, name: '守方司令', military: 200, percents: { military: 20 } } };
  const html = G.Battle.renderCommandBonusPanel(bonuses, 'defender');
  assert.ok(html.indexOf('守方司令') < html.indexOf('攻方司令'));
  assert.match(html, /20\.0%/);
  assert.match(html, /技能不参与/);
  assert.equal(G.Battle.renderCommandBonusPanel(null, 'attacker'), '');
});

test('出征队伍上限来自服务端，改变本地司令部等级不会提高上限', () => {
  const G = setup(false);
  G.Core.state.gameplay.maxMarches = 9;
  assert.equal(G.Core.maxMarches(), 9);
  G.Core.state.buildings.headquarters = 10;
  assert.equal(G.Core.maxMarches(), 9);
  G.Core.state.gameplay.maxMarches = 15;
  assert.equal(G.Core.maxMarches(), 15);
  const card = G.Build.renderBuilding('headquarters', G.DATA.buildings.headquarters, [], 0);
  assert.match(card, /15 支/);
});
