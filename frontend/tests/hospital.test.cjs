const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const rules = JSON.parse(fs.readFileSync(path.join(__dirname,
  '../../backend/src/main/resources/game/tech-building-prerequisites.json'), 'utf8'));

function setup(buildings = {}, constructions = []) {
  const toasts = [], modals = [], routes = [];
  const context = vm.createContext({
    document: {
      getElementById: () => null,
      createElement: () => {
        const nodes = {};
        return { innerHTML: '', style: {}, addEventListener() {}, remove() {},
          querySelector: id => nodes[id] || (nodes[id] = { style: {}, addEventListener() {} }) };
      },
      body: { appendChild: modal => modals.push(modal) }
    },
    localStorage: { getItem: () => null, setItem() {} },
    setInterval: () => 1, clearInterval() {}, setTimeout: () => 1, clearTimeout() {},
    Game: { fmt: String, escapeHtml: String, resourceIconHtml: key => key,
      toast: message => toasts.push(message), go: route => routes.push(route) }
  });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'core.js', 'prerequisites.js', 'build.js', 'tech.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  const G = context.Game;
  G.toast = message => toasts.push(message);
  G.go = route => routes.push(route);
  G.Core.render = () => {};
  G.Core.state = { buildings, constructions, tech: {}, items: {}, army: {}, officers: [],
    resources: { food: 1000000, steel: 1000000, oil: 1000000, rare: 1000000, gold: 1000000 } };
  G.Prerequisites.setRules(rules);
  return { G, toasts, modals, routes };
}

test('医院在军事区占一个卡槽，在建时预占，落成后可进入伤兵营', () => {
  const { G, modals, routes } = setup({ command: 3, hospital: 1 });
  assert.equal(G.Core.buildingGroup('hospital'), 'army');
  assert.equal(G.DATA.buildings.hospital.slots, 1);
  assert.equal(G.Core.groupSlotsUsed('army'), 2);
  G.Build.openBuildingDetailModal('hospital', null);
  assert.match(modals[0].innerHTML, /战地医院/);
  assert.match(modals[0].innerHTML, /前往 伤兵营/);
  modals[0].querySelector('#bdetailRouteBtn').onclick();
  assert.deepEqual(routes, ['wounded']);
  delete G.Core.state.buildings.hospital;
  G.Core.state.constructions = [{ id: 'hospital', targetLevel: 1 }];
  assert.equal(G.Core.groupSlotsUsed('army'), 2);
});

test('满级医疗科研要求医院8级，兵站10级仍会显示缺项，医院8级后无需兵站', () => {
  const { G } = setup({ lab: 10, house: 10, academy: 10, farm: 8, transit: 10, hospital: 7 });
  const missing = G.Prerequisites.check('technologies', 'log_medical', 10).missing;
  assert.deepEqual(Array.from(missing, entry => [entry.id, entry.required, entry.actual]), [['hospital', 8, 7]]);
  const html = G.Prerequisites.html('technologies', 'log_medical', 10);
  assert.match(html, /战地医院/);
  assert.doesNotMatch(html, /战地兵站/);
  G.Core.state.buildings.hospital = 8;
  G.Core.state.buildings.transit = 0;
  assert.equal(G.Prerequisites.check('technologies', 'log_medical', 10).missing.length, 0);
});

test('医院建造和升级显示全部配套缺项，升级10级允许配套落后', () => {
  const { G } = setup({ command: 2 });
  assert.deepEqual(Array.from(G.Prerequisites.check('buildings', 'hospital', 1).missing,
    entry => [entry.id, entry.required]), [['command', 3], ['house', 1], ['academy', 1], ['farm', 1]]);
  G.Core.state.buildings = { command: 10, house: 9, academy: 8, farm: 5 };
  assert.equal(G.Prerequisites.check('buildings', 'hospital', 10).missing.length, 0);
});

test('医院未竣工或拆除后等级不足时不能解锁医疗科研，升级保留已完成等级', () => {
  for (const [hospital, targetLevel, ready] of [[0, 1, false], [1, 0, false], [1, 2, true]]) {
    const { G } = setup({ lab: 3, house: 1, academy: 1, farm: 1, hospital },
      [{ id: 'hospital', targetLevel }]);
    assert.equal(G.Prerequisites.check('technologies', 'log_medical', 1).missing.length === 0, ready);
  }
  const { G } = setup({ lab: 10, house: 10, academy: 10, farm: 8, hospital: 8 },
    [{ id: 'hospital', targetLevel: 7 }]);
  assert.equal(G.Prerequisites.check('technologies', 'log_medical', 10).missing[0].actual, 7);
});

test('医疗科研卡片与确认入口提示战地医院，前往操作打开军事区医院详情', () => {
  const { G } = setup({ lab: 3, house: 1, academy: 1, farm: 1, transit: 10 });
  const previews = [];
  G.Prerequisites.preview = (...args) => previews.push(args);
  G.Tech.confirmResearch('log_medical');
  assert.deepEqual(previews, [['technologies', 'log_medical', 1]]);
  const view = { innerHTML: '' };
  G.Tech.setTab('后勤');
  G.Tech.renderView(view);
  assert.match(view.innerHTML, /需本城战地医院 Lv.1/);
  const actions = [];
  G.Core.go = route => actions.push(route);
  G.Build.openBuildingDetailModal = (id, slot) => actions.push([id, slot]);
  G.Prerequisites.goTo('hospital', 'city');
  assert.deepEqual(actions, ['buildArmy', ['hospital', null]]);
});

test('医院配置与设计配置保持一致，其他科技继续关联兵站', () => {
  const design = JSON.parse(fs.readFileSync(path.join(__dirname,
    '../../docs/balance/tech-building-prerequisites-design.json'), 'utf8'));
  assert.equal(design.version, rules.version);
  assert.deepEqual(design.buildings, rules.buildings);
  assert.deepEqual(design.technologies, rules.technologies);
  assert.ok(rules.technologies.log_food.levels[0].requires.some(entry => entry.building === 'transit'));
  const { G } = setup();
  assert.deepEqual(Object.keys(rules.buildings).sort(), Object.keys(G.DATA.buildings).sort());
});
