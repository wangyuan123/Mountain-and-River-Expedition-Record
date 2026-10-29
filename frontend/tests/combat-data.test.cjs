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

test('infantry carries small personal cargo while keeping its combat role and truck advantage', () => {
  assert.equal(data.units.infantry.load, 10);
  assert.equal(data.units.infantry.logistic, false);
  assert.notEqual(data.units.infantry.autoAdvance, false);
  assert.equal(data.units.infantry.load * 50, data.units.truck.load);
  assert.match(data.combatRoles.infantry, /每人基础负重10/);
});

test('approved recruitment population keeps large ships and long-range firepower at their agreed tiers', () => {
  const population = Object.fromEntries(Object.entries(data.units).map(([id, unit]) => [id, unit.pop]));
  assert.deepEqual(population, {
    infantry: 1, truck: 1, armored: 2, recon_car: 2, ltank: 2, htank: 3, assault: 3, rocket: 5,
    scout: 1, special: 1, fighter: 2, bomber: 5, transport: 3,
    destroyer: 10, sub: 8, battleship: 20, carrier: 100
  });
  assert.equal(data.units.carrier.pop * 100, 10000);
  assert.equal(data.units.battleship.pop * 100, 2000);
});

test('land recruitment costs distinguish steel armor from oil intensive rocket artillery', () => {
  const landUnits = Object.values(data.units).filter((unit) => unit.branch === 'land');
  const rocket = data.units.rocket;
  for (const resource of ['food', 'oil', 'rare']) {
    // 资源分工允许重坦钢耗超过火箭车；火箭车仍是高粮、油、稀矿成本的陆军远程火力。
    assert.equal(rocket.cost[resource], Math.max(...landUnits.map((unit) => unit.cost[resource] || 0)), resource);
    assert.ok(landUnits.every((unit) => unit === rocket || rocket.cost[resource] > (unit.cost[resource] || 0)), resource);
  }
  assert.ok(data.units.htank.cost.steel > rocket.cost.steel);
  const weightedCost = (unit) => unit.cost.steel + 2 * unit.cost.oil + 4 * unit.cost.rare;
  assert.ok(landUnits.every((unit) => unit === rocket || weightedCost(rocket) > weightedCost(unit)));
});

test('capital ship recruitment scales all four resources from the revised rocket cost', () => {
  const { rocket, battleship, carrier } = data.units;
  assert.equal(rocket.cost.oil, 450);
  assert.equal(rocket.cost.rare, 120);
  // 用户指定的倍数同时覆盖粮食、钢铁、石油和稀矿，避免只按加权总价保持比例。
  for (const resource of ['food', 'steel', 'oil', 'rare']) {
    assert.equal(battleship.cost[resource], rocket.cost[resource] * 20, resource + ' battleship');
    assert.equal(carrier.cost[resource], battleship.cost[resource] * 2, resource + ' carrier');
  }
});

test('military technology bonuses are 5% per level in server and client definitions', () => {
  const serverTechs = source('TechDef');
  const entries = [
    ['attack_tech', '全军攻击 +5%/级，最高 +50%'],
    ['defense_tech', '全军防御 +5%/级，最高 +50%'],
    ['weapon_range', '全军武器射程 +5%/级，最高 +50%'],
    ['cmd_hp', '军队生命 +5%/级，最高 +50%']
  ];

  for (const [key, description] of entries) {
    assert.equal(data.techs[key].desc, description, key + ' client description');
    assert.ok(serverTechs.split('\n').some((line) =>
      line.includes('m.put("' + key + '"') && line.includes('"' + description + '"')), key + ' server description');
    assert.equal(data.techs[key].max, 10, key + ' max level');
  }
});

test('all technology names, branches, descriptions, levels and effect types match server definitions', () => {
  const definitions = [...source('TechDef').matchAll(/m\.put\("([^"]+)", new TechDef\("([^"]+)", "([^"]+)", "([^"]+)", "([^"]+)",\s*(\d+), (\d+), Map\.of\([^)]*\), ([\d.]+), "([^"]+)"\)\);/g)];
  assert.deepEqual(definitions.map(match => match[1]).sort(), Object.keys(data.techs).sort());
  for (const [, id, key, name, branch, description, maxLevel, labReq, growth, effect] of definitions) {
    assert.equal(id, key);
    assert.equal(data.techs[id].name, name, id + ' name');
    assert.equal(data.techs[id].branch, branch, id + ' branch');
    assert.equal(data.techs[id].desc, description, id + ' description');
    assert.equal(data.techs[id].max, Number(maxLevel), id + ' max level');
    assert.equal(data.techs[id].labReq, Number(labReq), id + ' lab requirement');
    assert.equal(data.techs[id].growth, Number(growth), id + ' cost growth');
    assert.equal(data.techs[id].affect, effect, id + ' effect');
  }
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

test('rocket role names exactly all threefold armor counter targets', () => {
  assert.match(data.combatRoles.rocket, /轻坦、重坦、防空装甲车、侦查装甲车、突击炮均×3/);
  assert.match(data.combatRoles.rocket, /其他目标无额外克制倍率/);
  assert.doesNotMatch(data.combatRoles.rocket, /特种兵×0\.6|对装甲×1\.75/);
});

test('rocket retains fractional anti-air attack in stats, Japanese units and homepage role', () => {
  assert.equal(data.units.rocket.atkAir, 11.5);
  assert.equal(data.japaneseUnits.rocket.atkAir, 11.5);
  assert.match(data.combatRoles.rocket, /对空攻击11\.5/);
  assert.match(data.combatRoles.rocket, /有限防空自卫能力/);
  assert.doesNotMatch(data.combatRoles.rocket, /对空火力极弱/);
});

test('rocket range and homepage role use the reduced 1600 range for both factions', () => {
  assert.equal(data.units.rocket.range, 1600);
  assert.equal(data.japaneseUnits.rocket.range, 1600);
  assert.match(data.combatRoles.rocket, /基础射程1600/);
});

test('armored role names its 2.5-fold anti-air counters', () => {
  assert.match(data.combatRoles.armored, /对战斗机、轰炸机均×2\.5/);
  assert.match(data.combatRoles.armored, /对步兵类无额外克制倍率/);
});
