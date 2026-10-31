const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('./server-gameplay.cjs').vm;
const fs = require('node:fs');
const path = require('node:path');
function setup() {
  let token = 'alice';
  const nodes = { 'wounded-content': { innerHTML: '', contains: () => false } };
  const G = {
    Core: { views: {}, route: 'wounded', state: {
      resources: { gold: 10000, diamond: 100 }, player: { cityName: '<主城>', activeCityId: 1, citySlot: 0 },
      cityOverview: { cities: [{ id: 1, name: '<主城>', x: 10, y: 10, current: true }] }
    }, refreshTop() {} },
    DATA: { units: { scout: { name: '侦察机' } } },
    fmt: String, escapeHtml: s => String(s).replace(/</g, '&lt;').replace(/>/g, '&gt;'), toast() {},
    API: { getToken: () => token, getWounded: async () => ({ batches: [], total: 0, medicalLevel: 10 }) }
  };
  let stopped = 0;
  const ctx = vm.createContext({ Game: G, document: { activeElement: null, getElementById: id => nodes[id] },
    setInterval: () => 1, clearInterval: () => stopped++ });
  ctx.window = ctx;
  require('./load-constants.cjs')(ctx);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/wounded.js'), 'utf8'), ctx);
  G.Wounded.token = token;
  G.Wounded.cityId = 1;
  const data = { total: 30, cityId: 1, citySlot: 0, cityName: '<主城>', cityX: 10, cityY: 10, medicalLevel: 10, serverTime: 1000, batches: [
    { id: 1, citySlot: 0, unit: 'scout', count: 10, cityName: '<主城>', cityX: 10, cityY: 10, recoveryPercent: 65, expiresAt: 86401000, goldPerUnit: 12 },
    { id: 2, citySlot: 0, unit: 'scout', count: 20, cityName: '迁城前名称', cityX: 20, cityY: 20, recoveryPercent: 50, expiresAt: 9000, goldPerUnit: 12 },
    { id: 3, citySlot: 1, unit: 'scout', count: 99, cityName: '分城', cityX: 30, cityY: 30, recoveryPercent: 50, expiresAt: 9000, goldPerUnit: 12 }
  ] };
  for (const batch of data.batches) batch.preview = { count: batch.count, gold: 120, diamond: 1, maxGold: batch.count, maxDiamond: batch.count, originalGold: 120 };
  G.Wounded.data = data;
  return { G, data, nodes, document: ctx.document, setToken: value => token = value, stopped: () => stopped };
}

function mountControls(nodes, id) {
  nodes['wounded-qty-' + id] = { value: '', disabled: false };
  nodes['wounded-range-' + id] = { value: '', disabled: false, style: { setProperty(name, value) { this[name] = value; } } };
  nodes['wounded-pay-' + id] = { innerHTML: '' };
  nodes['wounded-warning-' + id] = { textContent: '', hidden: true };
}

test('unit filtering and expiry sorting retain independent batches and city totals', () => {
  const { G, data } = setup();
  data.batches.push({ ...data.batches[0], id: 4, unit: 'infantry', count: 7, expiresAt: 20000 });
  G.Wounded.unitFilter = 'scout';
  const html = G.Wounded.renderContent(data);
  assert.match(html, /当前筛选待救治合计 30 · 2 个独立批次/);
  assert.ok(html.indexOf('wounded-qty-2') < html.indexOf('wounded-qty-1'));
  assert.doesNotMatch(html, /wounded-qty-4|wounded-qty-3/);
  assert.equal(data.batches[0].count, 10);
  assert.equal(data.batches[1].count, 20);
  G.Wounded.unitFilter = '';
  G.Wounded.sortOrder = 'newest';
  const latest = G.Wounded.renderContent(data);
  assert.ok(latest.indexOf('wounded-qty-4') < latest.indexOf('wounded-qty-2'));
});

test('camp follows the active city, ignores other city slots, and does not split batches by historical coordinates', () => {
  const { G, data } = setup();
  let html = G.Wounded.renderContent(data);
  assert.match(html, /7 天|超过 7 天未治疗将全部消失/);
  assert.match(html, /最高 65% 回收/);
  assert.match(html, /当前救治城市：&lt;主城&gt; \(10,10\)/);
  assert.match(html, /&lt;主城&gt;/);
  assert.doesNotMatch(html, /<主城>/);
  assert.match(html, /wounded-qty-1/);
  assert.match(html, /wounded-qty-2/);
  assert.doesNotMatch(html, /wounded-qty-3/);
  assert.doesNotMatch(html, /wounded-cities/);
  assert.match(html, /待救治部队<\/span><strong>30<\/strong>/);
  assert.match(html, /Game.Cities.open\(\)/);
  G.Wounded.quantities[2] = 3;
  html = G.Wounded.renderContent(data);
  assert.match(html, /wounded-qty-2[^>]+value="3"/);
  assert.match(html, /剩余 8秒/);
  assert.match(html, /剩余 1天0小时0分钟0秒/);
  assert.match(html, /wounded-qty-1/);
});

test('treatment buttons use the camp city gold and shared diamond snapshot', () => {
  const { G, data } = setup();
  data.resources = { gold: 0, diamond: 100 };
  const html = G.Wounded.renderContent(data);
  assert.match(html, /本城可用黄金 0 · 账号钻石 100/);
  const buttons = G.Wounded.paymentButtons(data.batches[0], 10);
  assert.match(buttons, /disabled[^>]+gold/);
  assert.doesNotMatch(buttons, /disabled[^>]+diamond/);
});

test('gold and diamonds show equivalent quantity with different prices; invalid quantity is disabled', () => {
  const { G, data } = setup();
  let html = G.Wounded.paymentButtons(data.batches[0], 10);
  assert.match(html, /黄金治疗 · 120/);
  assert.match(html, /钻石治疗 · 1/);
  G.Core.state.resources.gold = 0;
  html = G.Wounded.paymentButtons(data.batches[0], 10);
  assert.match(html, /disabled[^>]+gold/);
  assert.doesNotMatch(html, /disabled[^>]+diamond/);
  for (const n of [0, -1, 11, 1.5, NaN]) {
    assert.equal((G.Wounded.paymentButtons(data.batches[0], n).match(/disabled/g) || []).length, 2);
  }
});

test('diamond fees round up once per 1000 total gold and MAX stops at the affordable boundary', () => {
  const { G, data } = setup();
  const batch = data.batches[0];
  batch.goldPerUnit = 1;
  batch.count = 2000;
  data.resources = { gold: 2000, diamond: 1 };
  assert.match(G.Wounded.renderContent(data), /每 1000 基础黄金折算 1 颗/);
  for (const [count, cost, disabled] of [[1, 1, false], [999, 1, false], [1000, 1, false], [1001, 2, true]]) {
    batch.preview = { count, gold: count, diamond: cost, maxGold: 2000, maxDiamond: 1000 };
    const buttons = G.Wounded.paymentButtons(batch, count);
    assert.match(buttons, new RegExp('钻石治疗 · ' + cost));
    assert.equal(/disabled[^>]+heal\(1,'diamond'\)/.test(buttons), disabled);
  }
  assert.equal(G.Wounded.maxAffordable(batch, 'diamond'), 1000);
});

test('quantity input and slider stay linked, use whole counts, and never exceed the batch', () => {
  const { G, data, nodes } = setup();
  mountControls(nodes, 1);
  const html = G.Wounded.renderContent(data);
  assert.match(html, /type="range" id="wounded-range-1" min="0" max="10" step="1"/);
  assert.match(html, /wounded-qty-1[^>]+oninput="Game.Wounded.changeQuantity\(1,this.value\)"/);
  assert.match(html, /wounded-range-1[^>]+oninput="Game.Wounded.changeQuantity\(1,this.value\)"/);
  G.Wounded.changeQuantity(1, '4');
  assert.equal(G.Wounded.quantities[1], 4);
  assert.equal(nodes['wounded-qty-1'].value, 4);
  assert.equal(nodes['wounded-range-1'].value, 4);
  assert.equal(nodes['wounded-range-1'].style['--p'], '40%');
  assert.match(nodes['wounded-pay-1'].innerHTML, /黄金治疗 · —/);
  data.batches[0].preview = { count: 4, gold: 48, diamond: 1, maxGold: 10, maxDiamond: 10 };
  G.Wounded.updateControls(data.batches[0], 4);
  assert.match(nodes['wounded-pay-1'].innerHTML, /黄金治疗 · 48/);
  for (const [value, expected] of [['99', 10], ['-3', 0], ['2.8', 2], ['', 0], ['bad', 0]]) {
    G.Wounded.changeQuantity(1, value);
    assert.equal(G.Wounded.quantities[1], expected);
    assert.equal(nodes['wounded-qty-1'].value, expected);
    assert.equal(nodes['wounded-range-1'].value, expected);
  }
});

test('currency MAX uses server affordability and never submits or derives a price from unit cost', () => {
  const { G, data, nodes } = setup(), batch = data.batches[0];
  batch.count = 20410; batch.goldPerUnit = 1;
  batch.preview = { count: 337, gold: 25612, diamond: 26, maxGold: 337, maxDiamond: 3460 };
  data.resources = { gold: 25625, diamond: 263 };
  mountControls(nodes, 1);
  G.API.healWounded = () => assert.fail('MAX must not submit treatment');
  G.Wounded.selectMax(1, 'gold');
  assert.equal(G.Wounded.quantities[1], 337);
  assert.equal(nodes['wounded-range-1'].value, 337);
  assert.match(nodes['wounded-pay-1'].innerHTML, /黄金治疗 · 25612/);
  G.Wounded.selectMax(1, 'diamond');
  assert.equal(G.Wounded.quantities[1], 3460);
  assert.equal(nodes['wounded-qty-1'].value, 3460);
  assert.match(nodes['wounded-pay-1'].innerHTML, /钻石治疗 · —/);
  batch.preview = { count: 3460, gold: 262960, diamond: 263, maxGold: 337, maxDiamond: 3460 };
  G.Wounded.updateControls(batch, 3460);
  assert.match(nodes['wounded-pay-1'].innerHTML, /钻石治疗 · 263/);
  data.resources.gold = 9999999;
  assert.equal(G.Wounded.maxAffordable(batch, 'gold'), 337, 'local balance cannot rebuild eligibility');
});

test('warning explains gold, diamond, and combined shortages and clears when affordable', () => {
  const { G, data, nodes } = setup();
  data.batches[0].goldPerUnit = 120;
  mountControls(nodes, 1);
  for (const [resources, expected, goldDisabled, diamondDisabled] of [
    [{ gold: 600, diamond: 2 }, /^黄金余额不足/, true, false],
    [{ gold: 1200, diamond: 1 }, /^钻石余额不足/, false, true],
    [{ gold: 600, diamond: 1 }, /^黄金和钻石余额均不足.*点击对应 MAX/, true, true],
    [{ gold: 1200, diamond: 2 }, /^$/, false, false]
  ]) {
    data.resources = resources;
    data.batches[0].preview = { count: 10, gold: 1200, diamond: 2, maxGold: goldDisabled ? 5 : 10, maxDiamond: diamondDisabled ? 8 : 10 };
    G.Wounded.changeQuantity(1, '10');
    const warning = nodes['wounded-warning-1'];
    assert.match(warning.textContent, expected);
    assert.equal(warning.hidden, !goldDisabled && !diamondDisabled);
    const buttons = nodes['wounded-pay-1'].innerHTML;
    assert.equal(/disabled[^>]+heal\(1,'gold'\)/.test(buttons), goldDisabled);
    assert.equal(/disabled[^>]+heal\(1,'diamond'\)/.test(buttons), diamondDisabled);
  }
});

test('zero affordability keeps MAX selection at zero across refresh and explains replenishment', () => {
  const { G, data, nodes } = setup();
  data.resources = { gold: 11, diamond: 0 };
  data.batches[0].preview = { count: 0, gold: 0, diamond: 0, maxGold: 0, maxDiamond: 0 };
  mountControls(nodes, 1);
  for (const currency of ['gold', 'diamond']) {
    G.Wounded.selectMax(1, currency);
    assert.equal(G.Wounded.quantities[1], 0);
    assert.equal(nodes['wounded-range-1'].value, 0);
    assert.match(nodes['wounded-warning-1'].textContent, /黄金和钻石余额均不足.*无法治疗任何伤兵.*补充/);
    assert.equal((nodes['wounded-pay-1'].innerHTML.match(/disabled/g) || []).length, 2);
    assert.match(G.Wounded.renderContent(data), /wounded-qty-1[^>]+value="0"/);
  }
});

test('balances, buttons, and warnings refresh while an input is focused', () => {
  const { G, data, nodes, document } = setup();
  data.batches[0].goldPerUnit = 120;
  mountControls(nodes, 1);
  G.Wounded.changeQuantity(1, '10');
  nodes['wounded-content'].innerHTML = 'keep focused controls';
  nodes['wounded-content'].contains = () => true;
  nodes['wounded-balance'] = { textContent: '' };
  document.activeElement = { tagName: 'INPUT' };
  data.resources = { gold: 600, diamond: 1 };
  data.batches[0].preview = { count: 10, gold: 1200, diamond: 2, maxGold: 5, maxDiamond: 8 };
  G.Wounded.paint();
  assert.equal(nodes['wounded-content'].innerHTML, 'keep focused controls');
  assert.equal(nodes['wounded-qty-1'].value, 10);
  assert.match(nodes['wounded-balance'].textContent, /黄金 600 · 账号钻石 1/);
  assert.equal(nodes['wounded-warning-1'].hidden, false);
  assert.match(nodes['wounded-warning-1'].textContent, /黄金和钻石余额均不足/);
  assert.equal((nodes['wounded-pay-1'].innerHTML.match(/disabled/g) || []).length, 2);
  data.resources = { gold: 1200, diamond: 2 };
  data.batches[0].preview = { count: 10, gold: 1200, diamond: 2, maxGold: 10, maxDiamond: 10 };
  G.Wounded.paint();
  assert.equal(nodes['wounded-warning-1'].hidden, true);
  assert.doesNotMatch(nodes['wounded-pay-1'].innerHTML, /disabled/);
  data.batches[0].count = 6;
  G.Wounded.paint();
  assert.equal(nodes['wounded-qty-1'].value, 6);
  assert.equal(nodes['wounded-qty-1'].max, 6);
  assert.equal(nodes['wounded-range-1'].value, 6);
  assert.equal(nodes['wounded-range-1'].max, 6);
});

test('pending treatment disables quantity controls and MAX cannot change the submitted count', () => {
  const { G, data, nodes } = setup();
  mountControls(nodes, 1);
  G.Wounded.changeQuantity(1, '5');
  G.Wounded.pending = true;
  G.Wounded.updateControls(data.batches[0], 5);
  assert.equal(nodes['wounded-qty-1'].disabled, true);
  assert.equal(nodes['wounded-range-1'].disabled, true);
  assert.equal((nodes['wounded-pay-1'].innerHTML.match(/disabled/g) || []).length, 4);
  G.Wounded.selectMax(1, 'diamond');
  G.Wounded.changeQuantity(1, '1');
  assert.equal(G.Wounded.quantities[1], 5);
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

test('delayed camp response cannot replace another city even before the page rerenders', async () => {
  const { G, data, nodes } = setup();
  let resolve;
  G.API.getWounded = () => new Promise(done => resolve = done);
  const request = G.Wounded.refresh();
  G.Core.state.player.activeCityId = 2;
  G.Core.state.player.citySlot = 1;
  resolve({ ...data, total: 999 });
  await request;
  assert.equal(G.Wounded.data.total, 30);
  assert.equal(nodes['wounded-content'].innerHTML, '');
});

test('switching city resets pending treatment and old completion cannot overwrite the new city operation', async () => {
  const { G, data } = setup();
  const treatments = [];
  G.API.healWounded = () => new Promise(resolve => treatments.push(resolve));
  G.Wounded.quantities[1] = 5;
  const oldTreatment = G.Wounded.heal(1, 'gold');
  assert.equal(G.Wounded.pending, true);
  G.Core.state.player.activeCityId = 2;
  G.Core.state.player.citySlot = 1;
  const branchCamp = { ...data, cityId: 2, citySlot: 1, cityName: '分城', total: 20,
    batches: [{ ...data.batches[1], id: 4, citySlot: 1 }] };
  G.API.getWounded = async () => branchCamp;
  G.Wounded.renderView({ innerHTML: '', isConnected: true });
  await Promise.resolve();
  assert.equal(G.Wounded.pending, false);
  assert.equal(G.Wounded.data, branchCamp);
  G.Wounded.quantities[4] = 5;
  const newTreatment = G.Wounded.heal(4, 'gold');
  assert.equal(treatments.length, 2);
  treatments[0]({ success: true, camp: { ...data, total: 25 } });
  await oldTreatment;
  assert.equal(G.Wounded.data, branchCamp);
  assert.equal(G.Wounded.pending, true);
  treatments[1]({ success: true, camp: branchCamp });
  await newTreatment;
  assert.equal(G.Wounded.pending, false);
});


test('VIP total gold rounding keeps all ten affordable while diamonds use the original price', () => {
  const { G, data } = setup();
  const batch = { id: 1, count: 10, goldPerUnit: 4, originalGoldPerUnit: 5, vipDiscountPercent: 30, preview: { count:10, gold:35, diamond:1, maxGold:10, maxDiamond:10, originalGold:50 } };
  data.resources = { gold: 35, diamond: 1 };
  assert.equal(G.Wounded.treatmentPrice(batch, 10, 'gold'), 35);
  assert.equal(G.Wounded.maxAffordable(batch, 'gold'), 10);
  assert.doesNotMatch(G.Wounded.paymentButtons(batch, 10), /disabled[^>]+gold/);
  const larger = { ...batch, count: 220, goldPerUnit: 3, vipDiscountPercent: 40, preview: { count:220, gold:660, diamond:2, maxGold:11, maxDiamond:200 } };
  assert.equal(G.Wounded.treatmentPrice(larger, 220, 'gold'), 660);
  assert.equal(G.Wounded.treatmentPrice(larger, 220, 'diamond'), 2);
  assert.equal(G.Wounded.maxAffordable(larger, 'diamond'), 200);
});

test('frozen wounded keep their remaining window visible and pause treatment buttons', () => {
  const { G, data } = setup();
  data.batches.forEach(batch => { batch.frozen = true; });
  assert.match(G.Wounded.renderContent(data), /归降移交中，救治暂停 · 剩余期限/);
  const buttons = G.Wounded.paymentButtons(data.batches[0], 10);
  assert.equal((buttons.match(/ disabled/g) || []).length, 4);
});
