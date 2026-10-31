const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('./server-gameplay.cjs').vm;
const fs = require('node:fs');
const path = require('node:path');

function setup() {
  const nodes = { 'prisoner-content': { innerHTML: '', contains: () => false } };
  const G = {
    Core: { views: {}, route: 'prisoners', state: { player: { id: 7, activeCityId: 11 }, resources: { gold: 5000 } }, refreshTop() {} },
    DATA: { units: { infantry: { name: '步兵' } } },
    Constants: {}, fmt: String, escapeHtml: s => String(s).replace(/</g, '&lt;').replace(/>/g, '&gt;'),
    toast() {},
    API: { getToken: () => 'token', getPrisoners: async () => ({ cityId: 11, cityName: '主城', campLevel: 2, educationLevel: 4, ratePermille: 4, total: 8, capacity: 4000, units: [{ unit: 'infantry', count: 8, goldPerUnit: 10, unavailableReason: '' }] }) }
  };
  const ctx = vm.createContext({ Game: G, window: { Game: G, localStorage: { getItem: () => null, setItem() {}, removeItem() {} } }, document: { activeElement: null, getElementById: id => nodes[id] }, setInterval: () => 1, clearInterval() {}, Date, Math });
  require('./server-gameplay.cjs').install(ctx);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/prisoners.js'), 'utf8'), ctx);
  return { G, nodes };
}

test('pool renders city capacity, rate, price and partial quantities', () => {
  const { G } = setup();
  const html = G.Prisoners.renderContent({ cityId: 11, cityName: '主城', campLevel: 2, educationLevel: 4, ratePermille: 4, total: 8, capacity: 4000, units: [{ unit: 'infantry', count: 8, goldPerUnit: 10, unavailableReason: '' }] });
  assert.match(html, /主城/);
  assert.match(html, /俘获比例 0\.4%/);
  assert.match(html, /黄金单价 10/);
  assert.match(html, /max="8"/);
});

test('recruit and release controls are disabled when production is unavailable', () => {
  const { G } = setup();
  const html = G.Prisoners.buttons({ unit: 'infantry', count: 8, goldPerUnit: 10, unavailableReason: '需本城工厂' }, 2);
  assert.match(html, /disabled/);
  assert.match(html, /释放/);
});
