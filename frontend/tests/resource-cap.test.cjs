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

test('Core.resourceNetRate 在钢铁达到或超过容量上限时返回 0', () => {
  const { Core } = setupCore({
    resources: { steel: 200000 },
    buildings: { refinery: 1 } // cap = 200,000
  });
  assert.equal(Core.capacity().steel, 200000);
  assert.equal(Core.resourceNetRate('steel'), 0, '钢铁达到上限200,000时实际净产出必须为0');
});

test('Core.resourceNetRate 在钢铁未达上限时返回理论产出', () => {
  const { Core } = setupCore({
    resources: { steel: 50000 },
    buildings: { refinery: 1 } // produceOf('refinery') = 40
  });
  assert.equal(Core.resourceNetRate('steel'), 40, '钢铁未达上限时应返回正常产出40');
});

test('Core.resourceNetRate 粮食达到上限且产出覆盖消耗时净变化为 0', () => {
  const { Core } = setupCore({
    resources: { food: 200000 },
    buildings: { farm: 1 }, // farm produce = 40
    army: {} // consumption = 0
  });
  assert.equal(Core.resourceNetRate('food'), 0, '粮食满仓且无超额消耗时净变化应为0');
});

test('Core.resourceNetRate 黄金达到999,999上限时税收停止增加', () => {
  const { Core } = setupCore({
    resources: { gold: 999999 },
    population: { civilian: 2400, capacity: 2400, effectiveCapacity: 2400 },
    tax: 30,
    officers: [] // 0 salary
  });
  assert.equal(Core.resourceNetRate('gold'), 0, '黄金达上限且无军官薪资时实际净变化应为0');
});

test('Core.resourceNetRate 黄金达到上限且税收不足以抵扣军官薪资时净变化为负差额', () => {
  const { Core } = setupCore({
    resources: { gold: 999999 },
    population: { civilian: 2400, capacity: 2400, effectiveCapacity: 2400 },
    tax: 0, // 0 tax income
    officers: [{ salary: 50 }]
  });
  assert.equal(Core.resourceNetRate('gold'), -50, '免税且黄金达上限时，净变化应为 -50 薪资');
});
