const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const rules = JSON.parse(fs.readFileSync(path.join(__dirname,
  '../../backend/src/main/resources/game/tech-building-prerequisites.json'), 'utf8'));

function setup(buildings = {}, constructions = []) {
  const toasts = [], recruits = [];
  const state = { buildings, constructions, army: { destroyer: 7 },
    resources: { food: 100000, steel: 100000, oil: 100000, rare: 100000 }, officers: [] };
  const context = vm.createContext({
    document: { getElementById: id => ['qty_destroyer', 'qty_scout'].includes(id) ? { value: '1' } : null },
    setInterval: () => 1, clearInterval: () => {},
    Game: { fmt: String, escapeHtml: String, toast: value => toasts.push(value),
      resourceIconHtml: key => key,
      API: { armyQueue: async () => ({ queue: [] }), recruit: async (unit, count) => {
        recruits.push({ unit, count }); return { success: true, queueId: 9 };
      } },
      Core: { state, views: {}, route: 'army', render: () => {},
        civilianPopulation: () => 1000, populationCapacity: () => 1200,
        popFree: () => 1000, populationGrowthPerHour: () => 36,
        armyCap: () => 1200, foodPerHour: () => 0, getOfficerByRole: () => null,
        buildMul: () => 1, trainMul: () => 1,
        buildingLevels: id => Array.isArray(buildings[id]) ? buildings[id] : [buildings[id] || 0]
      }
    }
  });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'prerequisites.js', 'build.js', 'army.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  context.Game.Prerequisites.setRules(rules);
  return { G: context.Game, toasts, recruits };
}

test('造船厂加入军事建筑，四种海军的制造设施切换到造船厂', () => {
  const { G } = setup();
  assert.ok(G.Build.GROUPS.army.order.includes('shipyard'));
  assert.equal(G.DATA.buildings.shipyard.slots, 1);
  assert.equal(G.DATA.buildings.shipyard.cat, 'army');
  assert.equal(G.Build.SPECIAL_ROUTES.shipyard, 'army');
  assert.equal(G.DATA.buildings.port.name, '军港');
  for (const id of ['destroyer', 'sub', 'battleship', 'carrier']) {
    assert.equal(G.DATA.units[id].build, 'shipyard');
  }
  assert.equal(G.DATA.units.fighter.build, 'airport');
  assert.equal(G.DATA.units.infantry.build, 'factory');
});

test('完整建筑与科技前置可从零逐步满足，不产生升级环路', () => {
  const buildings = {};
  for (const id of Object.keys(rules.buildings)) buildings[id] = 0;
  let changed;
  do {
    changed = false;
    for (const [id, definition] of Object.entries(rules.buildings)) {
      const next = definition.levels[buildings[id]];
      if (next && next.requires.every(r => buildings[r.building] >= r.level)) {
        buildings[id]++; changed = true;
      }
    }
  } while (changed);
  for (const id of Object.keys(rules.buildings)) assert.equal(buildings[id], 10, `${id} 存在升级死锁`);
  const { G } = setup(buildings);
  for (const [id, definition] of Object.entries(rules.technologies)) {
    for (let n = 1; n <= definition.maxLevel; n++) {
      assert.equal(G.Prerequisites.check('technologies', id, n).missing.length, 0, `${id} Lv.${n}`);
    }
  }
});

test('造船厂/军港升级弹窗条件与舰船动力研发同步显示联动建筑', () => {
  const { G } = setup({ command: 10, heavyfactory: 10, refinery: 10, transit: 10, lab: 10, oilfield: 10, port: 5, shipyard: 4 });
  assert.equal(G.Prerequisites.check('buildings', 'port', 6).missing[0].id, 'shipyard');
  assert.equal(G.Prerequisites.check('buildings', 'shipyard', 6).missing[0].id, 'port');
  assert.equal(G.Prerequisites.check('technologies', 'nav_engine', 5).missing[0].id, 'shipyard');
  assert.match(G.Prerequisites.html('buildings', 'port', 6), /造船厂/);
});

test('缺造船厂或军港时前端显示全部缺项，并拦截直接调用征召', () => {
  for (const buildings of [{}, { port: 1 }, { shipyard: 1 }]) {
    const { G, recruits, toasts } = setup(buildings);
    const check = G.Prerequisites.checkRecruitment('destroyer');
    assert.equal(check.missing.length, Object.keys(buildings).length ? 1 : 2);
    const view = { innerHTML: '' };
    G.Army.renderView(view);
    const card = view.innerHTML.split('id="unit-card-destroyer"')[1].split('id="unit-card-sub"')[0];
    assert.match(card, /menu-item lock/);
    assert.match(card, /征召前置：造船厂/);
    assert.match(card, /军港/);
    assert.match(card, /前往建造/);
    assert.doesNotMatch(card, />征召<\/button>/);
    assert.match(card, />解散<\/button>/);
    G.Army.recruit('destroyer', 'qty_destroyer');
    assert.equal(recruits.length, 0);
    assert.match(toasts[0], /需本城/);
  }
});

test('双建筑完成后解锁海军征召，生产按造船厂等级计算', async () => {
  const { G, recruits } = setup({ port: 10, shipyard: 5 });
  assert.equal(G.Prerequisites.checkRecruitment('destroyer').missing.length, 0);
  const view = { innerHTML: '' };
  G.Army.renderView(view);
  const card = view.innerHTML.split('id="unit-card-destroyer"')[1].split('id="unit-card-sub"')[0];
  assert.match(card, /menu-item ok/);
  assert.match(card, /造船厂 Lv.5/);
  assert.match(card, /并行 2 条/);
  assert.match(card, />征召<\/button>/);
  G.Army.recruit('destroyer', 'qty_destroyer');
  assert.deepEqual(recruits, [{ unit: 'destroyer', count: 1 }]);
  await Promise.resolve();
});

test('在建或正在拆除的造船厂不能作为征召前置，升级保留已完成等级', () => {
  for (const [buildings, targetLevel, expected] of [
    [{ port: 1 }, 1, false], [{ port: 1, shipyard: 1 }, 0, false], [{ port: 2, shipyard: 1 }, 2, true]
  ]) {
    const { G } = setup(buildings, [{ id: 'shipyard', targetLevel }]);
    assert.equal(G.Prerequisites.checkRecruitment('destroyer').missing.length === 0, expected);
  }
  const { G } = setup({ shipyard: 1, port: 1 }, [{ id: 'port', targetLevel: 0 }]);
  assert.equal(G.Prerequisites.checkRecruitment('destroyer').missing[0].id, 'port');
});

test('新增海军前置不影响地面及空军制造设施', () => {
  const { G } = setup({ factory: 1, airport: 1 });
  for (const id of ['infantry', 'scout', 'fighter', 'bomber', 'transport']) {
    assert.equal(G.Prerequisites.checkRecruitment(id).missing.length, 0);
  }
});

test('军工厂只生产地面部队，全部空军兵种使用空军基地', () => {
  const { G } = setup({ factory: 10 });
  assert.equal(G.DATA.buildings.factory.desc, '生产步兵、地面装备');
  assert.match(G.DATA.buildings.airport.desc, /侦察机/);
  for (const [id, unit] of Object.entries(G.DATA.units)) {
    if (unit.build === 'factory') assert.equal(unit.branch, 'land', id);
    if (unit.branch === 'air') assert.equal(unit.build, 'airport', id);
  }
});

test('缺空军基地时侦察机锁定，军工厂不能替代其征召前置', () => {
  for (const [buildings, constructions] of [
    [{ factory: 10 }, []],
    [{ factory: 10 }, [{ id: 'airport', targetLevel: 1 }]],
    [{ factory: 10, airport: 1 }, [{ id: 'airport', targetLevel: 0 }]]
  ]) {
    const { G, recruits, toasts } = setup(buildings, constructions);
    assert.equal(G.Prerequisites.checkRecruitment('scout').missing[0].id, 'airport');
    const view = { innerHTML: '' };
    G.Army.renderView(view);
    const card = view.innerHTML.split('id="unit-card-scout"')[1].split('id="unit-card-')[0];
    assert.match(card, /menu-item lock/);
    assert.match(card, /空军基地/);
    assert.doesNotMatch(card, />征召<\/button>/);
    G.Army.recruit('scout', 'qty_scout');
    assert.equal(recruits.length, 0);
    assert.match(toasts[0], /空军基地/);
  }
});

test('侦察机产能由空军基地等级决定，军工厂等级不增加其生产速度', async () => {
  const { G, recruits } = setup({ factory: 10, airport: 5 });
  const view = { innerHTML: '' };
  G.Army.renderView(view);
  const card = view.innerHTML.split('id="unit-card-scout"')[1].split('id="unit-card-')[0];
  assert.match(card, /空军基地 Lv.5/);
  assert.match(card, /并行 2 条/);
  assert.match(card, /平均速度 ×1.25/);
  assert.doesNotMatch(card, /军工厂 Lv.10/);
  G.Army.recruit('scout', 'qty_scout');
  assert.deepEqual(recruits, [{ unit: 'scout', count: 1 }]);
  await Promise.resolve();
});
