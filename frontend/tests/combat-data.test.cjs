const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const context = vm.createContext({ window: { Game: {} } });
vm.runInContext(fs.readFileSync(path.join(root, 'frontend/js/data.js'), 'utf8'), context);
const data = JSON.parse(JSON.stringify(context.window.Game.DATA));
const source = (name) => fs.readFileSync(path.join(root,
  'backend/src/main/java/com/wargame/model/constants', name + '.java'), 'utf8');

function costMap(text) {
  return Object.fromEntries([...text.matchAll(/"(\w+)"\s*,\s*(\d+)/g)]
    .map((match) => [match[1], Number(match[2])]));
}

// 检查两个运行时的契约，防止界面仍显示旧造价、射程或跨海类别。
test('all displayed unit stats and recruitment costs match server definitions', () => {
  const pattern = /m\.put\("([^"]+)", new UnitDef\("([^"]+)", "([^"]+)", "([^"]+)",\s*([\d.,\s]+), "([^"]+)",\s*Map\.of\(([^)]*)\), (null|"[^"]+"), "([^"]+)"([\s\S]*?)\)\);/g;
  const matched = [];
  for (const match of source('UnitDef').matchAll(pattern)) {
    const [, id, key, name, cat, numbers, build, costs, strongVs, branch, options] = match;
    assert.equal(id, key);
    const stats = numbers.split(',').map((value) => Number(value.trim()));
    assert.equal(stats.length, 12, id);
    const expected = { name, cat, build, branch, cost: costMap(costs), strongVs: JSON.parse(strongVs) };
    ['atkGround', 'atkAir', 'atkSea', 'atkFort', 'def', 'hp', 'spd', 'range', 'food', 'marchOil', 'marchFood', 'pop'].forEach((field, i) => { expected[field] = stats[i]; });
    for (const [field, value] of Object.entries(expected)) assert.deepEqual(data.units[id][field], value, id + '.' + field);
    const optional = options.split(',').map((value) => value.trim()).filter(Boolean);
    if (optional.length) {
      assert.equal(Boolean(data.units[id].logistic), optional[0] === 'true', id + '.logistic');
      if (optional[1] !== 'null') assert.equal(data.units[id].load, Number(optional[1]), id + '.load');
      if (optional.length === 3) assert.equal(data.units[id].autoAdvance, optional[2] === 'true', id + '.autoAdvance');
    }
    assert.ok(data.combatRoles[id], id + ' needs a player-facing role');
    matched.push(id);
  }
  assert.deepEqual(matched.sort(), Object.keys(data.units).sort());
});

test('all displayed fort stats and costs match server definitions', () => {
  const pattern = /"([^"]+)", new FortDef\("[^"]+", "([^"]+)", "[^"]+",\s*([\d,\s]+),\s*Map\.of\(([^)]*)\)/g;
  const matched = [];
  for (const [, id, name, numbers, costs] of source('FortDef').matchAll(pattern)) {
    assert.equal(data.forts[id].name, name);
    const stats = numbers.split(',').map((value) => Number(value.trim()));
    assert.equal(stats.length, 8, id);
    ['atkGround', 'atkAir', 'atkSea', 'atkFort', 'def', 'hp', 'range', 'spd'].forEach((field, i) => assert.equal(data.forts[id][field], stats[i], id + '.' + field));
    assert.deepEqual(data.forts[id].cost, costMap(costs), id + '.cost');
    assert.ok(data.combatRoles[id]);
    matched.push(id);
  }
  assert.deepEqual(matched.sort(), Object.keys(data.forts).sort());
});
