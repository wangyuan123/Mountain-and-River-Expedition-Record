const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

test('实力保护阻止宣战和讨伐，但不阻止侦察', () => {
  const toasts = [];
  const target = { id: 2, name: '前线', powerProtection: { allowed: false, reason: '双方编队战力差距超过10倍' } };
  const state = { player: { id: 1 }, world: { playerCities: [target] } };
  const game = { Core: { state, views: {} }, Constants: {}, toast: message => toasts.push(message), gameNow: () => 1000,
    go: page => toasts.push(page), DATA: {} };
  const context = vm.createContext({ Game: game, Date });
  context.window = context;
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/world.js'), 'utf8'), context);
  game.World.declareWar('player', 0);
  game.World.attack('player', 0, 'campaign');
  assert.deepEqual(toasts, ['双方编队战力差距超过10倍', '双方编队战力差距超过10倍']);
  game.World.prepareCampaign = value => value;
  game.World.attack('player', 0, 'scout');
  assert.equal(toasts.at(-1), 'dispatch');
});
