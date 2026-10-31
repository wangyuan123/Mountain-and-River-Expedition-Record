const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

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

test('医院详情保留伤兵入口，槽位只读取后端快照',()=>{
 const {G,modals,routes}=setup({command:3,hospital:1});
 G.Core.state.gameplay.groupSlots.army.used=2;
 assert.equal(G.Core.groupSlotsUsed('army'),2);
 G.Build.openBuildingDetailModal('hospital',null);assert.match(modals[0].innerHTML,/前往 伤兵营/);
 modals[0].querySelector('#bdetailRouteBtn').onclick();assert.deepEqual(routes,['wounded']);
 delete G.Core.state.buildings.hospital;assert.equal(G.Core.groupSlotsUsed('army'),2);
});

test('医疗卡片显示服务端缺失医院等级，本地改建筑不能解锁',()=>{
 const {G}=setup({lab:10,hospital:10});
 const entry={id:'hospital',name:'战地医院',required:8,actual:7,scope:'city',met:false};
 G.Core.state.gameplay.prerequisites.technologies.log_medical[9]={requirements:[entry],missing:[entry]};
 assert.match(G.Prerequisites.html('technologies','log_medical',10),/战地医院/);
 G.Core.state.buildings.hospital=999;
 assert.equal(G.Prerequisites.check('technologies','log_medical',10).missing[0].actual,7);
 G.Core.state.gameplay.prerequisites.technologies.log_medical[9]={requirements:[{...entry,actual:8,met:true}],missing:[]};
 assert.equal(G.Prerequisites.check('technologies','log_medical',10).missing.length,0);
});

test('医院各级前置使用后端逐级结果，不套用本地建筑状态',()=>{
 const {G}=setup();const levels=G.Core.state.gameplay.prerequisites.buildings.hospital;
 assert.equal(G.Prerequisites.check('buildings','hospital',1),levels[0]);
 assert.equal(G.Prerequisites.check('buildings','hospital',10),levels[9]);
 G.Core.state.buildings={command:10,house:10,academy:10,farm:10};
 assert.equal(G.Prerequisites.check('buildings','hospital',1),levels[0]);
});

test('拆除与升级期间的有效前置完全采用后端评估',()=>{
 const {G}=setup({hospital:8},[{id:'hospital',targetLevel:7}]);
 const missing={id:'hospital',required:8,actual:7,name:'战地医院',scope:'city',met:false};
 G.Core.state.gameplay.prerequisites.technologies.log_medical[9]={requirements:[missing],missing:[missing]};
 G.Core.state.constructions=[];
 assert.equal(G.Prerequisites.check('technologies','log_medical',10).missing[0].actual,7);
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
