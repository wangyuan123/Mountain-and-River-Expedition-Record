// Read-only audit: evaluate current browser code in an isolated VM. No API or database calls.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const context = vm.createContext({
  window: null, console, Date, Math,
  document: { getElementById() { return null; } },
  setInterval() { return 1; }, clearInterval() {}, setTimeout() { return 1; }, clearTimeout() {}
});
context.window = context;
for (const file of ['constants', 'icons', 'data', 'core', 'save', 'build', 'army']) {
  let source = read('frontend/js/' + file + '.js');
  // Export private helpers only inside this VM; repository sources are never changed.
  if (file === 'army') source = source.replace('G.Army = Army;', 'G.__auditTrainStats = getBuildStats; G.Army = Army;');
  if (file === 'build') source = source.replace('G.Build = Build;', 'G.__auditBuildDuration = buildDuration; G.__auditQueueCap = MAX_CONCURRENT; G.Build = Build;');
  vm.runInContext(source, context, { filename: file + '.js' });
}
const game = context.Game;
game.Core.state = {
  buildings: { factory: Array(10).fill(10), farm: [1], command: 10 },
  tech: {}, officers: [], player: { vipLevel: 2 },
  resources: { steel: 1000 }
};
const armySource = read('backend/src/main/java/com/wargame/service/ArmyService.java');
assert.match(armySource, /totalWeightedSpeed \/ activeLanes/);
const lanes = 10 * 3;
const serverAverageFromSource = (lanes * 1.5) / lanes;
const training = game.__auditTrainStats('factory');
assert.equal(training.avgSpeed, 2.25);
assert.equal(serverAverageFromSource, 1.5);

const vipSource = read('backend/src/main/java/com/wargame/config/VipConfig.java');
const vip2 = vipSource.match(/new VipLevelDef\(\s*2,\s*3000L,\s*"[^"]+",\s*(\d+),\s*\d+,\s*[\d.]+,\s*\d+,\s*\d+,\s*([\d.]+)/);
assert.ok(vip2);
const serverQueueFromConfig = Number(vip2[1]);
const vipSpeed = Number(vip2[2]);
assert.equal(game.__auditQueueCap, 3);
assert.equal(serverQueueFromConfig, 5);
const buildSource = read('backend/src/main/java/com/wargame/service/BuildService.java');
assert.match(buildSource, /mul \*= \(1\.0 - vipBonus\)/);
const frontDuration = game.__auditBuildDuration('farm', 0);
const serverDurationFromSource = Math.ceil(30 * (1 - vipSpeed));
assert.equal(frontDuration, 30);
assert.equal(serverDurationFromSource, 29);

game.Core.state.tech.log_build = 10;
const frontRefund = Math.floor(game.buildCost('farm', 0).steel * 0.3);
assert.match(buildSource, /Map<String, Integer> cost = calcBuildCost\(buildingType, 0\);\s*refundCosts\(playerId, cost, DISMANTLE_REFUND_RATIO\)/);
const serverRefundFromSource = Math.floor(game.DATA.buildings.farm.baseCost.steel * 0.3);
assert.equal(frontRefund, 12);
assert.equal(serverRefundFromSource, 24);

const results = {
  method: 'Current frontend functions in VM; backend comparisons derived from current source/config, not live API tests',
  findings: [
    { id: 'D1', scenario: '10 level-10 factories, no training tech', frontendAverageSpeed: training.avgSpeed, backendAverageSpeedFromSource: serverAverageFromSource },
    { id: 'D2', scenario: 'VIP2, level-1 farm construction, no tech or mayor bonus', frontendSeconds: frontDuration, backendSecondsFromSource: serverDurationFromSource },
    { id: 'D3', scenario: 'VIP2 concurrent construction limit', frontendLimit: game.__auditQueueCap, backendLimitFromConfig: serverQueueFromConfig },
    { id: 'D4', scenario: 'Dismantle level-1 farm, log_build=10', frontendSteelRefund: frontRefund, backendSteelRefundFromSource: serverRefundFromSource }
  ],
  frontendFiles: fs.readdirSync(path.join(root, 'frontend/js')).filter(file => file.endsWith('.js')).sort().map(file => {
    const source = read('frontend/js/' + file);
    return { path: 'frontend/js/' + file, lines: source.split('\n').length, sha256: crypto.createHash('sha256').update(source).digest('hex') };
  })
};
process.stdout.write(JSON.stringify(results, null, 2) + '\n');
