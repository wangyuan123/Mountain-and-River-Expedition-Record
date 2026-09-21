const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const design = JSON.parse(fs.readFileSync(path.join(root, 'docs/balance/attack-design.json'), 'utf8'));
const context = vm.createContext({ window: { Game: {} } });
vm.runInContext(fs.readFileSync(path.join(root, 'frontend/js/data.js'), 'utf8'), context);
const definitions = { ...context.window.Game.DATA.units, ...context.window.Game.DATA.forts };

/** 资源权重是本项目的平衡假设，后续还需等原始资源、人口和生产时间对照。 */
function budget(unit) {
  return Object.entries(design.economyWeights).reduce((sum, [key, weight]) => sum + (unit.cost[key] || 0) * weight, 0);
}

/** 等预算、满编、不还击的理论齐射轮数；实际战斗另测射程、移动、战损与随机取整。 */
function derive(unit, profile) {
  // 当前所有单位保留最低火力；baseline 表示弱项自卫，不参与主武器齐射效率推导。
  if (profile.baseline) return design.minimumAttack;
  // 指定的兵种比例优先于齐射预算，向上取整保留整数属性；基础字段另行校验。
  if (profile.relativeTo) return Math.ceil(definitions[profile.relativeTo][profile.field] * profile.factor + profile.offset);
  const target = definitions[profile.target];
  const effectiveHealth = target.hp * (1 + target.def * 0.05);
  return Math.max(design.minimumAttack, Math.round(effectiveHealth * budget(unit) / (budget(target) * profile.volleys * profile.multiplier)));
}

const attacks = Object.fromEntries(Object.entries(design.profiles).map(([id, profile]) => [id,
  Object.fromEntries(Object.entries(profile.attacks).map(([field, input]) => [field, derive(definitions[id], input)]))]));

if (process.argv.includes('--json')) {
  process.stdout.write(JSON.stringify(attacks, null, 2) + '\n');
} else {
  console.log('| 单位 | 对地 | 对空 | 对海 | 对工事 | 定位 |');
  console.log('| --- | ---: | ---: | ---: | ---: | --- |');
  for (const [id, fields] of Object.entries(attacks)) {
    console.log(`| ${definitions[id].name} | ${Object.values(fields).join(' | ')} | ${design.profiles[id].role} |`);
    for (const [field, value] of Object.entries(fields)) {
      if (definitions[id][field] !== value) {
        console.error(`${id}.${field}: 配置 ${definitions[id][field]}，推导 ${value}`);
        process.exitCode = 1;
      }
    }
  }
  for (const [id, relation] of Object.entries(design.rangeRelations || {})) {
    const expected = Math.ceil(definitions[relation.relativeTo].range * relation.factor + relation.offset);
    if (definitions[id].range !== expected) {
      console.error(`${id}.range: 配置 ${definitions[id].range}，比例要求 ${expected}`);
      process.exitCode = 1;
    }
  }
}
