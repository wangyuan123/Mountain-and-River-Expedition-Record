const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setupCore(customState = {}) {
  const context = vm.createContext({
    console,
    Math,
    Date,
    parseInt,
    parseFloat,
    String,
    Boolean,
    Object,
    Array,
    document: {
      getElementById: () => null,
      createElement: () => ({ style: {}, setAttribute: () => {} })
    },
    Game: {
      fmt: (n) => String(n),
      escapeHtml: (str) => str || '',
      go: () => {}
    }
  });
  context.window = context;

  const dataSrc = fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8');
  const coreSrc = fs.readFileSync(path.join(__dirname, '../js/core.js'), 'utf8');
  require('./load-constants.cjs')(context);
  vm.runInContext(dataSrc, context);
  vm.runInContext(coreSrc, context);

  const Core = context.Game.Core;
  Core.state = Object.assign({
    resources: { food: 0, steel: 0, oil: 0, rare: 0, gold: 0 },
    buildings: { farm: 1, refinery: 1, oilfield: 1, raremine: 1, house: 2 },
    army: {},
    tech: {},
    officers: [],
    tax: 30,
    morale: 70,
    resentment: 0,
    population: { civilian: 0, capacity: 2400, effectiveCapacity: 2400 }
  }, customState);

  return { context, Core, Game: context.Game };
}

test('food panel uses receiving-city garrison supply and live production instead of inventory alone', () => {
  const { Core } = setupCore({ army: { infantry: 10 },
    foodSupply: { ownPerHour: 5, supportPerHour: 50, totalPerHour: 55, productionPerHour: 420 } });
  assert.equal(Core.foodPerHour(), 55);
  assert.equal(Core.foodProductionPerHour(), 420);
  assert.equal(Core.resourceNetRate('food'), 365);
  Core.state.foodSupply = { ownPerHour: 5, supportPerHour: 0, totalPerHour: 5, productionPerHour: 420 };
  assert.equal(Core.foodPerHour(), 5);
});

test('construction mayor reduces duration while resource cost remains technology-only', () => {
  const { Core, context, Game } = setupCore({ tech: { log_build: 10 } });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/build.js'), 'utf8'), context);
  Core.mayorSkillBonus = id => id === 'construct' ? .2 : 0;
  assert.equal(Core.buildCostMul(), .5);
  assert.equal(Core.buildMul(), .4);
  assert.equal(Game.buildCost('farm', 0).steel, 40);
});

test('actual resource rates include war and shield production modifiers for every basic resource', () => {
  const { Core } = setupCore({ world: { incoming: [{}] }, cityState: {} });
  for (const [key, normal] of Object.entries({ food: 600, steel: 600, oil: 500, rare: 500 })) {
    assert.equal(Core.resourceNetRate(key), normal * .7, key);
  }
  Core.state.world.incoming = [];
  Core.state.cityState.shieldUntil = Date.now() + 3600000;
  for (const [key, normal] of Object.entries({ food: 600, steel: 600, oil: 500, rare: 500 })) {
    assert.equal(Core.resourceNetRate(key), normal * 1.1, key);
  }
});

test('Core.populationGrowthPerHour 在平民达到有效容纳上限时返回 0', () => {
  const { Core } = setupCore({
    population: { civilian: 2400, capacity: 2400, effectiveCapacity: 2400, growthPerHour: 72 }
  });
  assert.equal(Core.populationGrowthPerHour(), 0, '满额平民增长速度应为 0');
});

test('Core.populationGrowthPerHour 在平民未达上限时返回正常增长速度', () => {
  const { Core } = setupCore({
    population: { civilian: 1000, capacity: 2400, effectiveCapacity: 2400, growthPerHour: 72 }
  });
  assert.equal(Core.populationGrowthPerHour(), 72, '未满平民应保留正常增长速度');
});

test('Core.resourceNetRate 在钢铁达到或超过生产容量时返回 0', () => {
  const { Core } = setupCore({
    resources: { steel: 200000 },
    buildings: { refinery: 1 } // production capacity = 200,000
  });
  assert.equal(Core.capacity().steel, 200000);
  assert.equal(Core.inventoryCapacity().steel, 1000000000);
  assert.equal(Core.resourceNetRate('steel'), 0, '钢铁达到生产容量200,000时实际净产出必须为0');
});

test('Core.resourceNetRate 在钢铁未达生产容量时返回理论产出', () => {
  const { Core } = setupCore({
    resources: { steel: 50000 },
    buildings: { refinery: 1 } // produceOf('refinery') = 600
  });
  assert.equal(Core.resourceNetRate('steel'), 600, '钢铁未达上限时应返回正常产出600');
});

test('Core.resourceNetRate 粮食达到生产容量且产出覆盖消耗时净变化为 0', () => {
  const { Core } = setupCore({
    resources: { food: 200000 },
    buildings: { farm: 1 }, // farm production capacity = 200,000
    army: {} // consumption = 0
  });
  assert.equal(Core.resourceNetRate('food'), 0, '粮食满仓且无超额消耗时净变化应为0');
});

test('Core 黄金生产容量为20,000,000，库存巡检阈值仍为10亿', () => {
  const { Core } = setupCore();
  assert.equal(Core.capacity().gold, 20000000);
  assert.equal(Core.inventoryCapacity().gold, 1000000000);
});

for (const gold of [999999, 1000000, 19999999]) {
  test(`Core.resourceNetRate 黄金库存${gold}低于2000万时保持税收`, () => {
    const { Core } = setupCore({
      resources: { gold },
      population: { civilian: 2400, capacity: 2400, effectiveCapacity: 2400 }
    });
    assert.equal(Core.resourceNetRate('gold'), 1440);
  });
}

for (const gold of [20000000, 20000001, 1000000010]) {
  test(`Core.resourceNetRate 黄金库存${gold}达到或超过2000万时暂停税收且不截断`, () => {
    const { Core } = setupCore({
      resources: { gold },
      population: { civilian: 2400, capacity: 2400, effectiveCapacity: 2400 }
    });
    assert.equal(Core.resourceNetRate('gold'), 0);
    assert.equal(Core.state.resources.gold, gold);
  });
}

test('Core.resourceNetRate 黄金达到生产容量时暂停税收，薪资继续扣除', () => {
  const { Core } = setupCore({
    resources: { gold: 20000000 },
    population: { civilian: 2400, capacity: 2400, effectiveCapacity: 2400 },
    tax: 30,
    officers: [{ salary: 50 }]
  });
  assert.equal(Core.resourceNetRate('gold'), -50);
  Core.state.resources.gold = 19999950;
  assert.equal(Core.resourceNetRate('gold'), 1390, '库存回落后恢复税收并继续扣除薪资');
});

test('Core.resourceNetRate 黄金达到生产容量且税收不足以抵扣军官薪资时净变化为负差额', () => {
  const { Core } = setupCore({
    resources: { gold: 20000000 },
    population: { civilian: 2400, capacity: 2400, effectiveCapacity: 2400 },
    tax: 0, // 0 tax income
    officers: [{ salary: 50 }]
  });
  assert.equal(Core.resourceNetRate('gold'), -50, '免税且黄金达上限时，净变化应为 -50 薪资');
});
