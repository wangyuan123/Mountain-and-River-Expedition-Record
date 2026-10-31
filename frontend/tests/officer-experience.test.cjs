const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

function loadGame() {
  const context = vm.createContext({ window: null, Math });
  context.window = context;
  require('./load-constants.cjs')(context);
  const saveSource = fs.readFileSync(path.join(__dirname, '..', 'js', 'save.js'), 'utf8');
  vm.runInContext(saveSource, context, { filename: 'save.js' });
  context.Game.state = structuredClone(require('./server-gameplay.cjs').fixture);
  return context;
}

function loadExpNeeded() {
  return loadGame().Game.expNeeded;
}

test('officer experience curve matches shared backend boundary costs and totals', () => {
  const expNeeded = loadExpNeeded();
  const fixture = fs.readFileSync(path.join(__dirname, '../../backend/src/test/resources/officer-experience.csv'), 'utf8');
  for (const row of fixture.trim().split(/\r?\n/).slice(1)) {
    const [level, expected, cumulative] = row.split(',').map(Number);
    assert.equal(expNeeded(level), expected, `level ${level}`);
    let total = 0;
    for (let currentLevel = 1; currentLevel < level; currentLevel++) total += expNeeded(currentLevel);
    assert.equal(total, cumulative, `cumulative to level ${level}`);
  }
});

test('officer experience costs increase through level 99 and total 20,355,750', () => {
  const expNeeded = loadExpNeeded();
  let total = 0;
  let previous = 0;
  for (let level = 1; level < 100; level++) {
    const need = expNeeded(level);
    assert.ok(need > previous, `cost must increase at level ${level}`);
    total += need;
    previous = need;
  }
  assert.equal(total, 20355750);
});

test('browser exposes no local experience, resource, prestige or random growth settlement', () => {
  const context = loadGame();
  context.document = { getElementById: () => null };
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/core.js'), 'utf8'), context);
  for (const name of ['addOfficerExp', 'payCost', 'addPrestige', 'enterWarState']) assert.equal(context.Game.Core[name], undefined);
  assert.equal(context.Game.growAttr, undefined);
});
