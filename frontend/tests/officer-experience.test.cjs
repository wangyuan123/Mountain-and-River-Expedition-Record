const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function loadGame() {
  const context = vm.createContext({ window: null, Math });
  context.window = context;
  require('./load-constants.cjs')(context);
  const saveSource = fs.readFileSync(path.join(__dirname, '..', 'js', 'save.js'), 'utf8');
  vm.runInContext(saveSource, context, { filename: 'save.js' });
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

test('local officer experience uses real curve across stages and at max level', () => {
  const context = loadGame();
  context.document = { getElementById: () => null };
  context.Game.DATA = {};
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/core.js'), 'utf8'), context);
  context.Game.toast = () => {};
  for (const [start, experience, expectedLevel, expectedRemaining] of [
    [19, 12074, 21, 4274], [19, 12075, 22, 0],
    [49, 232800, 52, 0], [79, 1263750, 82, 0],
    [50, 100333, 51, 22833], [80, 100333, 80, 100333],
    [99, 956324, 99, 956324], [99, 956325, 100, 0], [99, 1000000, 100, 0],
    [1, 2281050, 62, 40375], [1, 20000000, 99, 600575], [1, 20355750, 100, 0]
  ]) {
    const officer = { id: 1, name: '测试军官', level: start, exp: experience - 1, attrPoints: 7 };
    context.Game.Core.state = { officers: [officer] };
    context.Game.Core.addOfficerExp(1, 1);
    assert.equal(officer.level, expectedLevel);
    assert.equal(officer.exp, expectedRemaining);
    assert.equal(officer.attrPoints, 7 + expectedLevel - start);
  }
});
