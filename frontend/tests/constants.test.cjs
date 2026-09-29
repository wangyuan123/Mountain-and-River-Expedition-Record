const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function loadConstants() {
  const context = vm.createContext({ window: null });
  context.window = context;
  require('./load-constants.cjs')(context);
  return context.Game.Constants;
}

test('shared catalogs keep stable IDs and ordered navigation', () => {
  const constants = loadConstants();
  const routes = constants.navItems.map(item => item.route);
  const armyIndex = routes.indexOf('buildArmy');
  assert.equal(routes[armyIndex + 1], 'officer');
  assert.equal(constants.navItems[armyIndex + 1].label, '军官');
  assert.equal(routes.at(-2), 'battleDefaults');
  assert.equal(routes.at(-1), 'shop');
  assert.equal(new Set(constants.shopItems.map(item => item.id)).size, constants.shopItems.length);
  assert.equal(new Set(constants.rechargePackages.map(item => item.id)).size, constants.rechargePackages.length);
  assert.equal(constants.mapRadiusOptions.at(-1).v, 0);
  assert.equal(constants.speedUpOrder.at(-1), 'speedUp72h');
  assert.equal(constants.equipmentNames.recruit_defense_weapon, '列兵护身盾');
  assert.equal(constants.equipmentBranchOrder.defense, 2);
  const shopItemIds = new Set(constants.shopItems.map(item => item.id));
  assert.ok(shopItemIds.has('box_recruit_defense'), '商城应包含列兵防御装备箱');
  assert.ok(shopItemIds.has('box_officer_defense'), '商城应包含校官防御装备箱');
  assert.ok(shopItemIds.has('box_marshal_defense'), '商城应包含元帅防御装备箱');
});

test('pages load constants before dependent scripts', () => {
  for (const name of ['index.html', 'classic-home-preview.html']) {
    const html = fs.readFileSync(path.join(__dirname, '..', name), 'utf8');
    assert.ok(html.indexOf('js/constants.js') < html.indexOf('js/core.js'));
  }
});
