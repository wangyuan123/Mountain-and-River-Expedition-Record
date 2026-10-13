const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const document = { activeElement: null, body: { style: {}, appendChild(el) { this.panel = el; el.isConnected = true; } }, querySelector() { return null; }, addEventListener() {}, removeEventListener() {} };
  document.createElement = () => ({ listeners: {}, innerHTML: '', addEventListener(key, cb) { this.listeners[key] = cb; }, querySelector() { return { scrollTop: 0, focus() {}, dataset: {} }; }, querySelectorAll() { return []; }, contains() { return false; }, remove() { document.body.panel = null; } });
  const G = { escapeHtml: v => String(v).replace(/</g, '&lt;'), toast() {}, Core: { state: { tech: { unit_refit: 3 }, resources: { steel: 100000, oil: 100000, rare: 100000 }, items: { ue_infantry_helmet: 1, ue_infantry_radio: 1 } }, render() {}, views: {} }, API: { client: {}, getGameState: () => Promise.resolve(G.Core.state) } };
  const context = { window: { Game: G, setInterval: () => 1, clearInterval() {} }, document, Date, console, setTimeout, clearTimeout };
  vm.createContext(context);
  ['constants', 'data'].forEach(name => vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/' + name + '.js'), 'utf8'), context));
  G.Core.state.unitEquipment = { loadouts: {}, versions: {}, snapshot: {}, facilities: { infantry: 6 }, queue: [], catalog: {
    ue_infantry_helmet: { key: 'ue_infantry_helmet', unit: 'infantry', slot: 'protection', name: '加强钢盔包', level: 1, effects: { defense: 8 }, cost: { steel: 300, oil: 0, rare: 0 }, seconds: 300 },
    ue_infantry_vest: { key: 'ue_infantry_vest', unit: 'infantry', slot: 'protection', name: '防破片护具套', level: 3, effects: { defense: 8, hp: 12, speed: -6 }, cost: { steel: 600, oil: 0, rare: 20 }, seconds: 900 },
    ue_infantry_radio: { key: 'ue_infantry_radio', unit: 'infantry', slot: 'support', name: '班排无线电组', level: 2, effects: { ground: 5, battleSpeed: 5 }, cost: { steel: 600, oil: 100, rare: 30 }, seconds: 600 }
  } };
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/unit-equipment.js'), 'utf8'), context);
  function click(action, key) { document.body.panel.listeners.click({ target: { closest: () => ({ dataset: { ue: action, key }, disabled: false }) } }); }
  return { G, document, context, click, html: () => document.body.panel.innerHTML };
}
async function settle() { await new Promise(resolve => setImmediate(resolve)); }

test('card summary is persistent and drawer has three slots with no equipment quantity', () => {
  const { G, html } = setup();
  assert.match(G.UnitEquipment.summary('infantry', true), /配置装备/);
  assert.equal(G.UnitEquipment.summary('motor', true), '');
  G.UnitEquipment.open('infantry');
  assert.match(html(), /本账号全部该兵种生效/);
  assert.equal((html().match(/data-ue="slot"/g) || []).length, 3);
  assert.doesNotMatch(html(), /<input|覆盖率|装配数量/);
  assert.match(html(), /role="dialog" aria-modal="true"/);
});
test('select only changes draft, save atomically submits slots and cancellation discards draft', async () => {
  const { G, click, html } = setup(); const requests = [];
  G.API.saveUnitLoadout = async (unit, slots, version) => { requests.push({ unit, slots: { ...slots }, version }); G.Core.state.unitEquipment.loadouts[unit] = { ...slots }; return { success: true }; };
  G.UnitEquipment.open('infantry'); click('choose', 'ue_infantry_helmet');
  assert.equal(G.Core.state.unitEquipment.loadouts.infantry, undefined);
  click('slot', 'support'); click('choose', 'ue_infantry_radio'); click('save'); await settle();
  assert.deepEqual(requests[0], { unit: 'infantry', slots: { protection: 'ue_infantry_helmet', support: 'ue_infantry_radio' }, version: 0 });
  assert.equal(G.UnitEquipment.hasUnsaved(), false);
  click('choose', ''); click('close'); assert.match(html(), /继续编辑/);
  click('continue'); click('reset'); assert.equal(G.UnitEquipment.hasUnsaved(), false);
  click('close');
});
test('save failure keeps draft and unowned equipment manufacturing never autoequips', async () => {
  const { G, click, html } = setup();
  G.API.saveUnitLoadout = async () => { throw new Error('装备库存不足'); };
  const made = []; G.API.manufactureUnitEquipment = async key => { made.push(key); return { success: true }; };
  G.UnitEquipment.open('infantry'); click('choose', 'ue_infantry_helmet'); click('save'); await settle();
  assert.match(html(), /装备库存不足/); assert.equal(G.UnitEquipment.hasUnsaved(), true);
  click('reset'); click('manufacture', 'ue_infantry_vest'); await settle();
  assert.deepEqual(made, ['ue_infantry_vest']); assert.equal(G.UnitEquipment.hasUnsaved(), false);
});
test('inventory distinguishes stock equipped and manufacture; report uses historical effects', () => {
  const { G } = setup();
  G.Core.state.unitEquipment.loadouts.infantry = { support: 'ue_infantry_radio' };
  G.Core.state.unitEquipment.queue = [{ itemKey: 'ue_infantry_vest' }];
  assert.match(G.UnitEquipment.inventoryHtml(), /已配置/); assert.match(G.UnitEquipment.inventoryHtml(), /制备中/);
  const report = G.UnitEquipment.reportHtml({ attacker: { '@ue:infantry:defense': 5, '@ue:infantry:item:ue_infantry_helmet': 1 }, defender: { '@ue:infantry~1:hp': 12 }, sources: { 1: { playerName: '盟友', army: { 'infantry~1': 10 } } } });
  assert.match(report, /防御\+5%/); assert.doesNotMatch(report, /防御\+8%/); assert.match(report, /盟友/);
  assert.match(report, /加强钢盔包/);
});
test('complete preview exposes all attack domains action costs capacity and conditions', () => {
  const { G, html } = setup(); G.Core.atkMul = () => 99; G.Core.commandBonusMul = () => 1.1; G.Core.state.tech.attack_tech = 1;
  G.UnitEquipment.open('infantry');
  ['完整属性', '对地攻击', '对空攻击', '对海攻击', '对工事攻击', '行军速度', '战场速度', '载重', '行军油耗', '行军粮耗', '反坦克火箭车伤害减免', '驱逐舰伤害减免'].forEach(label => assert.ok(html().includes(label), label));
  assert.match(html(), /<dt>对地攻击<\/dt><dd>12\.1<\/dd>/);
  assert.match(html(), /周期属性与技能/);
});
test('manufacturing blocks close while pending and state refresh preserves draft', async () => {
  const { G, document, click } = setup(); let finish;
  G.API.manufactureUnitEquipment = () => new Promise(resolve => { finish = resolve; });
  G.UnitEquipment.open('infantry'); click('choose', 'ue_infantry_helmet'); click('manufacture', 'ue_infantry_vest');
  assert.equal(G.UnitEquipment.isBusy(), true); G.UnitEquipment.close(true); assert.ok(document.body.panel);
  finish({ success: true }); await settle(); assert.equal(G.UnitEquipment.isBusy(), false);
  G.Core.state.unitEquipment.loadouts.infantry = { support: 'ue_infantry_radio' }; G.UnitEquipment.refresh();
  assert.equal(G.UnitEquipment.hasUnsaved(), true);
  click('reset'); assert.equal(G.UnitEquipment.hasUnsaved(), false);
});
test('travel and battle scenes combine speed correctly and oil costs add before rounding', () => {
  const { G } = setup(); const snap = { '@ue:truck:oil': -5, '@ue:truck:load': 20, '@ue:infantry:speed': -6, '@ue:infantry:battleSpeed': 5 };
  assert.equal(G.UnitEquipment.mul(snap, 'truck', 'oil'), .95);
  assert.equal(G.UnitEquipment.mul(snap, 'truck', 'load'), 1.2);
  assert.equal(G.UnitEquipment.speed(snap, 'infantry', true), .99);
  assert.equal(G.UnitEquipment.speed(snap, 'infantry', false), .94);
  assert.equal(G.UnitEquipment.speed({}, 'infantry', true), 1);
});
test('manufacture completion tick updates warehouse inventory and drawer without discarding draft', () => {
  const { G, context, click, html } = setup(); const handlers = {};
  G.WS = { on(name, handler) { handlers[name] = handler; } }; G.state = G.Core.state;
  G.Core.refreshTop = () => {}; G.Core.silentUpdate = () => {};
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/ws-handlers.js'), 'utf8'), context);
  G.UnitEquipment.open('infantry'); click('choose', 'ue_infantry_helmet');
  const equipment = { ...G.Core.state.unitEquipment, queue: [] };
  handlers.tick({ items: { ...G.state.items, ue_infantry_vest: 1 }, unitEquipment: equipment });
  assert.equal(G.state.items.ue_infantry_vest, 1);
  assert.match(html(), /防破片护具套/); assert.match(html(), /库存 1/);
  assert.equal(G.UnitEquipment.hasUnsaved(), true);
});
