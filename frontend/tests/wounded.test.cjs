const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
function setup() {
  let token = 'alice';
  const nodes = { 'wounded-content': { innerHTML: '', contains: () => false } };
  const G = {
    Core: { views: {}, route: 'wounded', state: { resources: { gold: 10000, diamond: 100 } }, refreshTop() {} },
    DATA: { units: { scout: { name: '侦察机' } } },
    fmt: String, escapeHtml: s => String(s).replace(/</g, '&lt;').replace(/>/g, '&gt;'), toast() {},
    API: { getToken: () => token, getWounded: async () => ({ batches: [], total: 0, medicalLevel: 10 }) }
  };
  let stopped = 0;
  const ctx = vm.createContext({ Game: G, document: { activeElement: null, getElementById: id => nodes[id] },
    setInterval: () => 1, clearInterval: () => stopped++ });
  ctx.window = ctx;
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/wounded.js'), 'utf8'), ctx);
  G.Wounded.token = token;
  const data = { total: 30, medicalLevel: 10, serverTime: 1000, batches: [
    { id: 1, unit: 'scout', count: 10, cityName: '<主城>', cityX: 10, cityY: 10, recoveryPercent: 65, expiresAt: 86401000, goldPerUnit: 12 },
    { id: 2, unit: 'scout', count: 20, cityName: '分城', cityX: 20, cityY: 20, recoveryPercent: 50, expiresAt: 9000, goldPerUnit: 12 }
  ] };
  G.Wounded.data = data;
  return { G, data, nodes, setToken: value => token = value, stopped: () => stopped };
}

test('camp explains expiry and medical rules, separates cities and preserves quantity on refresh', () => {
  const { G, data } = setup();
  let html = G.Wounded.renderContent(data);
  assert.match(html, /7 天|超过 7 天未治疗将全部消失/);
  assert.match(html, /最高 50%|最高 15%|无基础回收率/);
  assert.match(html, /&lt;主城&gt;/);
  assert.doesNotMatch(html, /<主城>/);
  assert.match(html, /wounded-qty-1/);
  assert.doesNotMatch(html, /wounded-qty-2/);
  G.Wounded.city = '20,20';
  G.Wounded.quantities[2] = 3;
  html = G.Wounded.renderContent(data);
  assert.match(html, /wounded-qty-2[^>]+value="3"/);
  assert.match(html, /剩余 不足1小时/);
  assert.doesNotMatch(html, /wounded-qty-1/);
});

test('gold and diamonds show equivalent quantity with different prices; invalid quantity is disabled', () => {
  const { G, data } = setup();
  let html = G.Wounded.paymentButtons(data.batches[0], 10);
  assert.match(html, /黄金治疗 · 120/);
  assert.match(html, /钻石治疗 · 2/);
  G.Core.state.resources.gold = 0;
  html = G.Wounded.paymentButtons(data.batches[0], 10);
  assert.match(html, /disabled[^>]+gold/);
  assert.doesNotMatch(html, /disabled[^>]+diamond/);
  for (const n of [0, -1, 11, 1.5, NaN]) {
    assert.equal((G.Wounded.paymentButtons(data.batches[0], n).match(/disabled/g) || []).length, 2);
  }
});

test('double-click submits once and failed treatment retains wounded for retry', async () => {
  const { G, data } = setup();
  let calls = 0, reject;
  G.Wounded.quantities[1] = 5;
  G.API.healWounded = () => { calls++; return new Promise((resolve, fail) => reject = fail); };
  G.Wounded.refresh = () => Promise.resolve();
  const request = G.Wounded.heal(1, 'gold');
  G.Wounded.heal(1, 'diamond');
  assert.equal(calls, 1);
  reject(new Error('黄金不足'));
  await request;
  assert.equal(G.Wounded.pending, false);
  assert.equal(G.Wounded.data.batches[0].count, data.batches[0].count);
});

test('successful treatment replaces camp with server counts and stale loads cannot overwrite it', async () => {
  const { G } = setup();
  G.Wounded.quantities[1] = 5;
  const loads = [];
  G.API.getWounded = () => new Promise(resolve => loads.push(resolve));
  const oldRequest = G.Wounded.refresh();
  G.API.healWounded = async () => ({ success: true, camp: { batches: [], total: 0, medicalLevel: 10 } });
  await G.Wounded.heal(1, 'diamond');
  // Fresh refresh is still pending; older responses were invalidated by treatment.
  assert.equal(G.Wounded.data.total, 0);
  loads[0]({ batches: [], total: 999, medicalLevel: 10 });
  await oldRequest;
  assert.equal(G.Wounded.data.total, 0);
  loads[1]({ batches: [], total: 0, medicalLevel: 10 });
  assert.equal(G.Wounded.pending, false);
});

test('response from previous account is ignored', async () => {
  const { G, setToken, nodes } = setup();
  let resolve;
  G.API.getWounded = () => new Promise(done => resolve = done);
  const request = G.Wounded.refresh();
  setToken('bob');
  resolve({ batches: [], total: 999, medicalLevel: 0 });
  await request;
  assert.equal(nodes['wounded-content'].innerHTML, '');
});
