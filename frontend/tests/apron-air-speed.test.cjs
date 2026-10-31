const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

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

test('战备机场定义校验: airSpdBonus 为 3，移除 airCap', () => {
  const ctx = loadFrontendContext();
  const D = ctx.window.Game.DATA;
  const apron = D.buildings.apron;

  assert.ok(apron, 'apron 建筑必须存在');
  assert.equal(apron.airSpdBonus, 3, 'airSpdBonus 应为 3%');
  assert.equal(apron.airCap, undefined, 'airCap 必须已被彻底移除');
  assert.match(apron.desc, /空军世界行军速度\+3%\/级/, '描述应明确空军世界行军的速度加成');
  assert.match(apron.desc, /不提高战场移动速度/);
});

test('城市不再提供前端行军倍率公式，机场加速只由路线接口决定', () => {
  const G = loadFrontendContext().window.Game;
  assert.equal(G.Core.spdMul, undefined);
  assert.equal(G.Core.atkMul, undefined);
  G.Core.state = { buildings: { apron: 10 }, tech: { air_engine: 10 } };
  assert.equal(G.Core.spdMul, undefined);
});

test('世界行军基础说明由服务端兵种目录提供', () => {
  const G = loadFrontendContext().window.Game;
  const motion = require('./server-gameplay.cjs').fixture.gameplay.catalog.unitMotion;
  assert.ok(motion.infantry.marchSecondsPerGrid > 0);
  assert.ok(motion.special.marchSecondsPerGrid > 0);
  assert.equal(G.Core.spdMul, undefined);
});

test('战术移速读取下一回合服务端快照，不从当前科技或技能推导', () => {
  const ctx = loadFrontendContext(), G = ctx.window.Game;
  vm.runInContext(fs.readFileSync(path.join(root, 'frontend/js/battle.js'), 'utf8'), ctx);
  G.Battle._activeTactical = { attackerStats: { infantry: { speed: 6.7 } }, attackerTech: {} };
  assert.equal(G.Battle.getEffectiveSpeed('infantry'), 6.7);
  G.Core.state = { tech: { arm_engine: 10 }, officers: [] };
  assert.equal(G.Battle.getEffectiveSpeed('infantry'), 6.7);
  assert.equal(G.Battle.getEffectiveSpeed('scout'), 0);
});
