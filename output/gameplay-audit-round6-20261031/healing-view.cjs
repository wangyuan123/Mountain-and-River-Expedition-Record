const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const observations = JSON.parse(fs.readFileSync(path.join(__dirname, 'observations.json'), 'utf8'));
const G = {
  Core: { views: {}, state: { resources: {} } },
  API: {}, DATA: { units: {} }, fmt: String, escapeHtml: String
};
const context = vm.createContext({ Game: G, document: {} });
context.window = context;
vm.runInContext(fs.readFileSync(path.join(root, 'frontend/js/wounded.js'), 'utf8'), context);
const results = {};
for (const key of ['vip_healing_quotes', 'vip_healing_rounding_quotes']) {
  const quote = observations[key];
  const batch = { id: 1, unit: quote.unit, count: quote.count, goldPerUnit: quote.goldPerUnit };
  G.Wounded.data = { resources: { gold: quote.goldCost, diamond: 1 } };
  const buttons = G.Wounded.paymentButtons(batch, batch.count);
  results[key] = {
    backendGoldCost: quote.goldCost,
    backendDiamondCost: quote.diamondCost,
    displayedGoldCost: Number(buttons.match(/黄金治疗 · (\d+)/)[1]),
    displayedDiamondCost: Number(buttons.match(/钻石治疗 · (\d+)/)[1]),
    goldMaxWithExactGoldBalance: G.Wounded.maxAffordable(batch, 'gold'),
    diamondMaxWithOneDiamond: G.Wounded.maxAffordable(batch, 'diamond'),
    count: batch.count
  };
}
fs.writeFileSync(path.join(__dirname, 'healing-view.json'), JSON.stringify(results, null, 2) + '\n');
process.stdout.write(JSON.stringify(results, null, 2) + '\n');
