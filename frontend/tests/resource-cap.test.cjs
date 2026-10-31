const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { vm } = require('./server-gameplay.cjs');

function setup() {
  const c = vm.createContext({ window: null, document: { getElementById() { return null; } } });
  c.window = c;
  for (const file of ['constants', 'data', 'core', 'build']) vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/' + file + '.js'), 'utf8'), c);
  c.Game.Core.state = { buildings: { farm: 10, house: 10 }, resources: { food: 999999999, steel: 999999999 }, tech: { log_build: 10 }, officers: [], tax: 100 };
  return c.Game;
}

test('resource production, expenses and net rates display the server ledger verbatim', () => {
  const G = setup(), core = G.Core;
  core.state.resourceRates = { productionPerHour: { food: 420, steel: 0 }, expensePerHour: { gold: 50 }, netPerHour: { food: 365, steel: 0, gold: -50 } };
  core.state.foodSupply = { ownPerHour: 5, supportPerHour: 50, totalPerHour: 55, productionPerHour: 420 };
  assert.equal(core.resourceNetRate('food'), 365);
  assert.equal(core.resourceNetRate('gold'), -50);
  assert.equal(core.resourceNetRate('steel'), 0);
  assert.equal(core.foodPerHour(), 55);
  assert.equal(core.foodProductionPerHour(), 420);
  assert.equal(core.cityProductionPerHour('farm'), 420);
  assert.equal(core.officerSalaryPerHour(), 50);
  core.state.tax = 0; core.state.buildings = {}; core.state.tech = {};
  assert.equal(core.resourceNetRate('food'), 365, 'local inputs cannot reconstruct or modify the server rate');
});

test('missing ledger data never falls back to local resource formulas', () => {
  const core = setup().Core;
  assert.equal(core.resourceNetRate('food'), 0);
  assert.equal(core.foodProductionPerHour(), 0);
  assert.equal(core.officerSalaryPerHour(), 0);
});

test('population capacity and growth consume server snapshots even at full local stock', () => {
  const core = setup().Core;
  core.state.population = { civilian: 2400, capacity: 2400, effectiveCapacity: 1680, growthPerHour: -20 };
  assert.equal(core.populationCapacity(), 2400);
  assert.equal(core.effectiveCapacity(), 1680);
  assert.equal(core.populationGrowthPerHour(), -20);
  core.state.population.growthPerHour = 0;
  assert.equal(core.populationGrowthPerHour(), 0);
});

test('production caps, inventory caps and warehouse protection are independent server values', () => {
  const G = setup();
  Object.assign(G.Core.state.gameplay, { capacity: { food: 200000, gold: 20000000 }, inventoryCapacity: { food: 1000000000, gold: 1000000000 }, protectCap: 15000 });
  assert.equal(G.Core.capacity().gold, 20000000);
  assert.equal(G.Core.inventoryCapacity().gold, 1000000000);
  assert.equal(G.Core.protectCap(), 15000);
  G.Core.state.buildings.depot = [10, 10, 10];
  assert.equal(G.Core.protectCap(), 15000);
});

test('building cost and duration use server quotes, including VIP and mayor effects', () => {
  const G = setup();
  G.Core.state.gameplay.buildings.farm.levels[0] = { cost: { steel: 40 }, duration: 19, refund: {} };
  assert.equal(G.buildCost('farm', 0).steel, 40);
  assert.equal(G.ServerRules.level('buildings', 'farm', 0).duration, 19);
  for (const name of ['buildCostMul', 'buildMul', 'atkMul', 'defMul', 'hpMul', 'spdMul', 'loadMul', 'medicalMul']) assert.equal(G.Core[name], undefined);
});
