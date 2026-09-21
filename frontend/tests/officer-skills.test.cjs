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
      body: { appendChild: () => {} }
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

  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/core.js'), 'utf8'), context);

  context.Core = context.Game.Core;
  context.D = context.Game.DATA;
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

test('Core.armyCap 受到指挥官统帅技能提升，且旧 supply 兼容生效', () => {
  const { Core } = setupGame();
  Core.state = {
    player: { militaryRank: 1 },
    buildings: { command: 1, staff: 0 },
    officers: [
      { id: 1, role: 'commander', level: 1, skills: [] }
    ]
  };

  // base = (1000 + 1000) * 1 * (1 + 0.025) = 2050
  const baseCap = Core.armyCap();
  assert.equal(baseCap, 2050);

  // 统帅 Lv.5 (+20%)
  Core.state.officers[0].skills = [{ id: 'leadership', lv: 5 }];
  assert.equal(Core.armyCap(), 2460);

  // 旧 supply Lv.5 (+20%)
  Core.state.officers[0].skills = [{ id: 'supply', lv: 5 }];
  assert.equal(Core.armyCap(), 2460);
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
