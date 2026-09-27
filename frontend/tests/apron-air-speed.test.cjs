const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');

function loadFrontendContext() {
  const context = vm.createContext({
    window: { Game: {} },
    document: {
      createElement: () => ({ style: {} }),
      getElementById: () => null
    }
  });

  const files = [
    'frontend/js/constants.js',
    'frontend/js/data.js',
    'frontend/js/core.js'
  ];

  for (const f of files) {
    const code = fs.readFileSync(path.join(root, f), 'utf8');
    vm.runInContext(code, context);
  }

  return context;
}

test('战备起飞场定义校验: airSpdBonus 为 3，移除 airCap', () => {
  const ctx = loadFrontendContext();
  const D = ctx.window.Game.DATA;
  const apron = D.buildings.apron;

  assert.ok(apron, 'apron 建筑必须存在');
  assert.equal(apron.airSpdBonus, 3, 'airSpdBonus 应为 3%');
  assert.equal(apron.airCap, undefined, 'airCap 必须已被彻底移除');
  assert.match(apron.desc, /航速/, '描述中应包含航速加成信息');
});

test('Core.spdMul 计算: 战备起飞场为空军提供每级 +3% 航速加成，与其他军种隔离', () => {
  const ctx = loadFrontendContext();
  const Core = ctx.window.Game.Core;

  Core.state = {
    tech: { air_engine: 0, arm_engine: 2 },
    buildings: { apron: 0 }
  };

  // 0 级起飞场，0 级科技 -> 1.0
  assert.equal(Core.spdMul('air'), 1.0);

  // 5 级起飞场 -> 1.0 + 5 * 0.03 = 1.15
  Core.state.buildings.apron = 5;
  assert.equal(Math.round(Core.spdMul('air') * 100) / 100, 1.15);

  // 10 级起飞场 -> 1.0 + 10 * 0.03 = 1.30
  Core.state.buildings.apron = 10;
  assert.equal(Math.round(Core.spdMul('air') * 100) / 100, 1.30);

  // 10 级起飞场 + 4 级喷气推进科技 -> 1.0 + 0.20 + 0.30 = 1.50
  Core.state.tech.air_engine = 4;
  assert.equal(Math.round(Core.spdMul('air') * 100) / 100, 1.50);

  // 步兵与装甲车不受起飞场影响
  assert.equal(Core.spdMul('inf'), 1.0);
  // arm_engine 2 级 -> 1 + 2 * 0.05 = 1.10
  assert.equal(Math.round(Core.spdMul('arm') * 100) / 100, 1.10);
});
