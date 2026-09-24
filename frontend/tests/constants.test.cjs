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
  assert.equal(routes[routes.indexOf('army') + 1], 'battleDefaults');
  assert.equal(new Set(constants.shopItems.map(item => item.id)).size, constants.shopItems.length);
  assert.equal(new Set(constants.rechargePackages.map(item => item.id)).size, constants.rechargePackages.length);
  assert.equal(constants.mapRadiusOptions.at(-1).v, 0);
  assert.equal(constants.speedUpOrder.at(-1), 'speedUp72h');
  assert.equal(constants.equipmentNames.recruit_defense_weapon, '列兵护身盾');
});

test('pages load constants before dependent scripts', () => {
  for (const name of ['index.html', 'classic-home-preview.html']) {
    const html = fs.readFileSync(path.join(__dirname, '..', name), 'utf8');
    assert.ok(html.indexOf('js/constants.js') < html.indexOf('js/core.js'));
  }
});
