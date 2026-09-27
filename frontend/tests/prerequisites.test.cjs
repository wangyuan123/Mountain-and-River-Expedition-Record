const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const rules = JSON.parse(fs.readFileSync(path.join(__dirname,
  '../../backend/src/main/resources/game/tech-building-prerequisites.json'), 'utf8'));

function environment(buildings, constructions, coastalPortLevel) {
  const context = {
    window: { Game: {
      Core: { state: { buildings, constructions: constructions || [], coastalPortLevel } },
      DATA: { buildings: {} }
    } }
  };
  context.window.window = context.window;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../js/prerequisites.js'), 'utf8'), context);
  const api = context.window.Game.Prerequisites;
  api.setRules(rules);
  return api;
}

test('attack research reports every missing factory without adding duplicate building levels', () => {
  const api = environment({ lab: 7, lightfactory: 7, heavyfactory: 6,
    factory: [4, 2], staff: 5 });
  const missing = api.check('technologies', 'attack_tech', 7).missing;
  assert.deepEqual(Array.from(missing, item => [item.id, item.required, item.actual]),
    [['heavyfactory', 7, 6], ['factory', 5, 4]]);
});

test('demolition of a single building lowers the displayed available level', () => {
  const api = environment({ lab: 1, radar: 1 },
    [{ id: 'radar', slot: null, targetLevel: 0 }]);
  const missing = api.check('technologies', 'recon_level', 1).missing;
  assert.equal(missing.length, 1);
  assert.equal(missing[0].id, 'radar');
  assert.equal(missing[0].actual, 0);
});

test('laboratory level 10 checks the best owned coastal port', () => {
  const buildings = { command: 10, refinery: 9, depot: 5, staff: 7 };
  assert.equal(environment(buildings, [], 7).check('buildings', 'lab', 10)
    .missing.find(item => item.id === 'port').actual, 7);
  assert.equal(environment(buildings, [], 8).check('buildings', 'lab', 10)
    .missing.some(item => item.id === 'port'), false);
});
