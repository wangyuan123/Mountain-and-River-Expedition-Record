const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const context = vm.createContext({
  window: { Game: { Core: { state: {}, views: {} } } }
});
vm.runInContext(fs.readFileSync(path.join(root, 'frontend/js/data.js'), 'utf8'), context);
vm.runInContext(fs.readFileSync(path.join(root, 'frontend/js/world.js'), 'utf8'), context);

const Game = context.window.Game;
const data = Game.DATA;

test('japaneseUnits dictionary contains all 17 unit types', () => {
  const unitKeys = Object.keys(data.units);
  assert.equal(unitKeys.length, 17);
  assert.deepEqual(Object.keys(data.japaneseUnits).sort(), unitKeys.sort());
});

test('japanese units inherit all combat, economic, and logistical attributes from allied units', () => {
  const checkFields = [
    'cat', 'branch', 'build', 'atkGround', 'atkAir', 'atkSea', 'atkFort',
    'def', 'hp', 'spd', 'range', 'food', 'marchOil', 'marchFood', 'pop',
    'strongVs', 'logistic', 'load', 'autoAdvance'
  ];

  for (const [id, allied] of Object.entries(data.units)) {
    const jpn = data.japaneseUnits[id];
    assert.ok(jpn, `Japanese unit ${id} should exist`);
    assert.notEqual(jpn.name, allied.name, `Japanese unit ${id} should have a distinct name`);
    assert.equal(jpn.name.split('-')[0], allied.name.split('-')[0], `Japanese unit ${id} prefix should match allied prefix`);

    // 属性 100% 一致验证
    for (const field of checkFields) {
      assert.deepEqual(jpn[field], allied[field], `${id}.${field} must match allied unit exactly`);
    }
    assert.deepEqual(jpn.cost, allied.cost, `${id}.cost must match allied unit exactly`);
  }
});

test('G.getUnit and G.getUnitName return respective units based on isJapanese flag', () => {
  const alliedInf = Game.getUnit('infantry', false);
  const jpnInf = Game.getUnit('infantry', true);

  assert.equal(alliedInf.name, '步兵-加兰德步枪兵（M1）');
  assert.equal(jpnInf.name, '步兵-三八式步枪兵（Type 38）');
  assert.equal(alliedInf.atkGround, jpnInf.atkGround);
  assert.equal(alliedInf.hp, jpnInf.hp);

  assert.equal(Game.getUnitName('fighter', false), '战斗机-野马（P-51）');
  assert.equal(Game.getUnitName('fighter', true), '战斗机-零式战斗机（A6M5）');
});

test('japanese unit names match backend JapaneseUnitDef.java definitions', () => {
  const javaFile = fs.readFileSync(path.join(root, 'backend/src/main/java/com/wargame/model/constants/JapaneseUnitDef.java'), 'utf8');
  for (const [id, jpn] of Object.entries(data.japaneseUnits)) {
    const match = javaFile.match(new RegExp(`m\\.put\\("${id}",\\s*"([^"]+)"\\)`));
    assert.ok(match, `Backend JapaneseUnitDef should have mapping for ${id}`);
    assert.equal(jpn.name, match[1], `Client Japanese unit name for ${id} must match server`);
  }
});
