const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const scrolls = [], focuses = [];
  function element(selector) { return { scrollTop: 0, dataset: {}, focus(options) { focuses.push({ selector, options }); }, scrollIntoView(options) { scrolls.push({ selector, options }); }, querySelector(child) { return element(selector + ' ' + child); } }; }
  const document = { activeElement: null, body: { style: {}, appendChild(el) { this.panel = el; el.isConnected = true; } }, querySelector() { return null; }, addEventListener() {}, removeEventListener() {} };
  document.createElement = () => ({ listeners: {}, innerHTML: '', addEventListener(key, cb) { this.listeners[key] = cb; }, querySelector(selector) { return element(selector); }, querySelectorAll() { return []; }, contains() { return false; }, remove() { document.body.panel = null; } });
  const G = { escapeHtml: v => String(v).replace(/</g, '&lt;'), toast() {},
    Prerequisites: { checkRecruitment: () => ({ eligible: true }), recruitmentHtml: () => '' },
    Core: { state: { tech: { unit_refit: 5 }, resources: { steel: 100000, oil: 100000, rare: 100000 }, items: { ue_infantry_helmet: 1, ue_infantry_radio: 1 } }, render() {}, views: {} },
    API: { client: {}, getGameState: () => Promise.resolve(G.Core.state) } };
  const context = { window: { Game: G, setInterval: () => 1, clearInterval() {} }, document, Date, console, setTimeout, clearTimeout };
  vm.createContext(context);
  ['constants', 'data'].forEach(name => vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/' + name + '.js'), 'utf8'), context));
  G.Core.state.unitEquipment = { loadouts: {}, versions: {}, snapshot: {}, facilities: { infantry: 6 }, queue: [], catalog: {
    ue_infantry_helmet: { key: 'ue_infantry_helmet', unit: 'infantry', slot: 'protection', name: '加强钢盔包', level: 1, facilityLevel: 2, effects: { defense: 8 }, cost: { steel: 300, oil: 0, rare: 0 }, seconds: 300 },
    ue_infantry_vest: { key: 'ue_infantry_vest', unit: 'infantry', slot: 'protection', name: '防破片护具套', level: 7, facilityLevel: 8, effects: { defense: 8, hp: 12, speed: -6 }, cost: { steel: 600, oil: 0, rare: 20 }, seconds: 900 },
    ue_infantry_radio: { key: 'ue_infantry_radio', unit: 'infantry', slot: 'support', name: '班排无线电组', level: 5, facilityLevel: 6, effects: { ground: 5, battleSpeed: 5 }, cost: { steel: 600, oil: 100, rare: 30 }, seconds: 600 }
  } };
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/unit-equipment.js'), 'utf8'), context);
  function click(action, key) { document.body.panel.listeners.click({ target: { closest: () => ({ dataset: { ue: action, key }, disabled: false }) } }); }
  return { G, document, context, click, scrolls, focuses, html: () => document.body.panel.innerHTML };
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
test('clicking every equipment slot reveals its matching choices and focuses selection', () => {
  const { G, click, html, scrolls, focuses } = setup();
  G.UnitEquipment.open('infantry');
  assert.equal(scrolls.length, 0);
  for (const [slot, label, expected, absent] of [
    ['protection', '防护', '加强钢盔包', '班排无线电组'],
    ['combat', '作战', null, '加强钢盔包'],
    ['support', '保障', '班排无线电组', '加强钢盔包']
  ]) {
    click('slot', slot);
    const choices = html().match(/<section class="unit-equipment-choices">([\s\S]*?)<\/section>/)[1];
    assert.ok(choices.includes('<h3>' + label + '装备</h3>'));
    if (expected) assert.ok(choices.includes(expected));
    assert.ok(!choices.includes(absent));
    assert.equal(scrolls.at(-1).selector, '.unit-equipment-choices');
    assert.equal(scrolls.at(-1).options.block, 'start');
    assert.equal(focuses.at(-1).selector, '.unit-equipment-choices [data-ue="choose"]:not([disabled])');
    assert.equal(focuses.at(-1).options.preventScroll, true);
  }
  assert.equal(scrolls.length, 3);
  assert.equal(G.UnitEquipment.hasUnsaved(), false);
});
test('opening a specific summary slot reveals choices and selecting stock updates its draft', () => {
  const { G, click, html, scrolls } = setup();
  G.UnitEquipment.open('infantry', 'support');
  assert.equal(scrolls.length, 1);
  assert.equal(scrolls[0].selector, '.unit-equipment-choices');
  const choices = html().match(/<section class="unit-equipment-choices">([\s\S]*?)<\/section>/)[1];
  assert.ok(choices.includes('班排无线电组'));
  assert.ok(!choices.includes('加强钢盔包'));
  click('choose', 'ue_infantry_radio');
  assert.equal(G.UnitEquipment.hasUnsaved(), true);
  assert.equal(G.Core.state.unitEquipment.loadouts.infantry, undefined);
  assert.match(html(), /草稿已选/);
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
  assert.match(html(), /<dt>对地攻击<\/dt><dd>11\.55<\/dd>/);
  assert.match(html(), /周期属性与技能/);
  assert.doesNotMatch(html(), /本城城墙/);
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

test('解锁快照单独变化时刷新制备资格并保留已持有装备的配装草稿', () => {
  const { G, context, click, html } = setup(); const handlers = {};
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/prerequisites.js'), 'utf8'), context);
  G.WS = { on(name, handler) { handlers[name] = handler; } }; G.state = G.Core.state;
  G.Core.refreshTop = () => {}; G.Core.silentUpdate = () => {};
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/ws-handlers.js'), 'utf8'), context);
  const requirement = { kind: 'building', id: 'factory', name: '军工厂', required: 1, current: 0, scope: 'city', met: false };
  const blocked = { unitId: 'infantry', ruleVersion: 'unit-unlock-20261020-v4', eligible: false, requirements: [requirement], missing: [requirement] };
  G.state.unitUnlocks = { infantry: blocked };
  G.UnitEquipment.open('infantry'); click('choose', 'ue_infantry_helmet');
  assert.equal(G.UnitEquipment.hasUnsaved(), true);
  assert.match(html(), /data-ue="manufacture" data-key="ue_infantry_helmet" disabled/);
  assert.match(html(), /前往建设/);
  const available = { ...blocked, eligible: true, requirements: [{ ...requirement, current: 1, met: true }], missing: [] };
  handlers.tick({ unitUnlocks: { infantry: available } });
  assert.match(html(), /data-ue="manufacture" data-key="ue_infantry_helmet">制备一件/);
  assert.equal(G.UnitEquipment.hasUnsaved(), true);
  handlers.tick({ unitUnlocks: { infantry: blocked } });
  assert.match(html(), /data-ue="manufacture" data-key="ue_infantry_helmet" disabled/);
  assert.match(html(), /data-ue="choose" data-key="ue_infantry_helmet">选择装备/);
  assert.equal(G.UnitEquipment.hasUnsaved(), true);
});

test('科技完成与资格快照同批推送时使用新科技刷新装备制备', () => {
  const { G, context, html } = setup(); const handlers = {};
  G.WS = { on(name, handler) { handlers[name] = handler; } }; G.state = G.Core.state;
  G.Core.refreshTop = () => {}; G.Core.silentUpdate = () => {};
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/ws-handlers.js'), 'utf8'), context);
  G.state.tech.unit_refit = 0;
  G.UnitEquipment.open('infantry');
  assert.match(html(), /data-ue="manufacture" data-key="ue_infantry_helmet" disabled/);
  handlers.tick({ tech: { unit_refit: 1 }, unitEquipment: G.state.unitEquipment, unitUnlocks: { infantry: { eligible: true } } });
  assert.match(html(), /data-ue="manufacture" data-key="ue_infantry_helmet">制备一件/);
  handlers.tick({ tech: { unit_refit: 0 } });
  assert.match(html(), /data-ue="manufacture" data-key="ue_infantry_helmet" disabled/);
});

test('unit equipment drawer styles adapt to active themes and avoid hardcoded dark background', () => {
  const css = fs.readFileSync(path.join(__dirname, '../css/unit-equipment.css'), 'utf8');
  assert.doesNotMatch(css, /background:\s*var\(--panel,\s*var\(--bg,\s*#222b2c\)\)/);
  assert.doesNotMatch(css, /color:\s*var\(--text,\s*#eee\)/);
  assert.doesNotMatch(css, /border-bottom:\s*1px solid var\(--border,\s*#43494e\)/);
  assert.match(css, /\.unit-equipment-drawer\s*\{[^}]*background:\s*var\(--surface/);
  assert.match(css, /\.unit-equipment-drawer\s*\{[^}]*color:\s*var\(--ink/);
  assert.match(css, /\[data-theme\|="blue-white"\]\s*\.unit-equipment-overlay\s*\{[^}]*--surface:\s*#ffffff/);
  assert.match(css, /\[data-theme="paper"\]\s*\.unit-equipment-overlay\s*\{[^}]*--surface:\s*#f6f5f0/);
  assert.match(css, /\[data-theme="dark"\]\s*\.unit-equipment-overlay\s*\{[^}]*--surface:\s*#111724/);

  const themeCss = fs.readFileSync(path.join(__dirname, '../css/themes.css'), 'utf8');
  assert.match(themeCss, /\[data-theme\|="blue-white"\][^{]*\.unit-equipment-overlay/);
  assert.match(themeCss, /\[data-theme="paper"\][^{]*\.unit-equipment-overlay/);
  assert.match(themeCss, /\[data-theme="dark"\][^{]*\.unit-equipment-overlay/);
});

test('zero-flash verification: no repeated slide animations and isUserEditing protects modal', () => {
  const css = fs.readFileSync(path.join(__dirname, '../css/unit-equipment.css'), 'utf8');
  assert.doesNotMatch(css, /ueSlideIn/);
  assert.doesNotMatch(css, /ueFadeIn/);
  assert.doesNotMatch(css, /animation:\s*ueSlideIn/);
  assert.doesNotMatch(css, /animation:\s*ueFadeIn/);

  const { G, context } = setup();
  let handlers = {};
  G.WS = { on(name, handler) { handlers[name] = handler; } };
  G.state = G.Core.state;
  let refreshCalls = 0;
  G.Core.refreshTop = () => {};
  G.Core.route = 'army';
  G.Core.refreshContent = () => { refreshCalls++; };
  delete G.Core.silentUpdate;

  // When unit equipment overlay is present, isUserEditing is true -> skip refreshContent
  context.document.querySelector = (sel) => {
    if (sel === '.unit-equipment-overlay') return { classList: ['unit-equipment-overlay'] };
    return null;
  };
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/ws-handlers.js'), 'utf8'), context);
  handlers.tick({});
  assert.equal(refreshCalls, 0);

  // When overlay is closed, refreshContent is allowed to run
  context.document.querySelector = () => null;
  handlers.tick({});
  assert.equal(refreshCalls, 1);
});

test('refresh only updates order countdown in-place when equipment state is unchanged', () => {
  const { G, document } = setup();
  G.Core.state.unitEquipment.queue = [{ id: 1, itemKey: 'ue_infantry_vest', startAt: Date.now() - 1000, endAt: Date.now() + 50000 }];
  G.UnitEquipment.open('infantry');
  const panel = document.body.panel;
  let panelInnerHTMLSets = 0;
  let val = panel.innerHTML;
  Object.defineProperty(panel, 'innerHTML', {
    get() { return val; },
    set(v) { panelInnerHTMLSets++; val = v; }
  });

  // Call refresh without state change: should NOT rewrite panel.innerHTML
  G.UnitEquipment.refresh();
  assert.equal(panelInnerHTMLSets, 0, 'panel.innerHTML should not be overwritten when state is unchanged');

  // Now change an item in inventory: state changed, full render should occur
  G.Core.state.items.ue_infantry_vest = 1;
  G.UnitEquipment.refresh();
  assert.equal(panelInnerHTMLSets, 1, 'panel.innerHTML should be updated when inventory changes');
});




test('ten level equipment uses its independent facility requirement in manufacturing preview', () => {
  const { G, click, html } = setup();
  G.Core.state.tech.unit_refit = 10;
  G.Core.state.unitEquipment.facilities.infantry = 10;
  const recipe = G.Core.state.unitEquipment.catalog.ue_infantry_vest;
  recipe.level = 10;
  recipe.facilityLevel = 10;
  G.UnitEquipment.open('infantry');
  click('slot', 'protection');
  assert.match(html(), /武器装备 Lv.10/);
  assert.doesNotMatch(html(), /Lv.20|undefined/);
  assert.match(html(), /data-key="ue_infantry_vest">制备一件/);
  G.Core.state.unitEquipment.facilities.infantry = 9;
  G.UnitEquipment.refresh();
  assert.match(html(), /data-key="ue_infantry_vest" disabled/);
});
