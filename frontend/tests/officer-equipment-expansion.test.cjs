const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;
const config = JSON.parse(fs.readFileSync(path.join(__dirname, '../../backend/src/main/resources/officer-equipment.json'), 'utf8'));

function setup() {
  const calls = [];
  const game = { Core: { state: { resources: { diamond: 10000 }, items: {}, officers: [] }, views: {}, render() {} },
    API: { officerEquipment(action, payload) { calls.push([action, payload]); return Promise.resolve({ success: true, message: '完成' }); } },
    toast() {}, escapeHtml: s => String(s == null ? '' : s), iconHtml: s => s };
  const context = vm.createContext({ window: null, Game: game, document: { getElementById() { return null; } }, console }); context.window = context;
  for (const file of ['constants.js', 'data.js', 'officer.js', 'shop.js', 'depot.js']) vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  return { game: context.Game, calls, context };
}

test('共享目录包含六档、24整箱、288件，每箱固定12个不同部位且等级正确', () => {
  const { game: g } = setup(); const C = g.Constants;
  assert.equal(Object.keys(C.equipmentSets).length, 24); assert.equal(Object.keys(C.equipmentItems).length, 288);
  assert.deepEqual(Array.from(Object.values(C.equipmentTiers), t => t.level), [1, 20, 40, 60, 80, 100]);
  const boxes = C.shopItems.filter(item => item.equipmentSet); assert.equal(boxes.length, 24);
  for (const box of boxes) {
    const set = C.equipmentSets[box.equipmentSet];
    assert.equal(set.keys.length, 12); assert.equal(new Set(set.keys.map(k => C.equipmentItems[k].slot)).size, 12);
    assert.equal(g.DATA.items[box.id].desc, box.desc); assert.match(box.desc, /固定获得十二件/);
  }
});

test('装备目录只采用服务器数值，不暴露本地套装计算器', () => {
  const { game: g } = setup();
  assert.equal(g.EquipmentCatalog.bonuses, undefined);
  const catalog = g.Core.state.gameplay.catalog;
  const key = Object.keys(catalog.equipmentItems)[0];
  assert.deepEqual(g.Constants.equipmentItems[key].attrs, catalog.equipmentItems[key].attrs);
});

test('混搭提交装备意图获取后端净变化，未报价时不推导结果或扣库存', async () => {
  const { game: g } = setup();
  const keys = g.Constants.equipmentSets.marshal_military.keys.slice(0, 6).concat(g.Constants.equipmentSets.marshal_defense.keys.slice(6));
  const requests = [];
  g.API.getToken = () => 'token';
  g.API.client = { post: async (path, body) => { requests.push([path, body]); return { net: { military: -34, defense: 57 }, bonus: {}, allowed: true }; } };
  const officer = { id: 7 };
  assert.equal(g.EquipmentCatalog.preview(officer, keys).pending, true);
  await new Promise(setImmediate);
  assert.equal(g.EquipmentCatalog.preview(officer, keys).net.defense, 57);
  assert.equal(requests[0][0], '/game/preview');
  assert.equal(requests[0][1].action, 'equipment');
  assert.deepEqual(Array.from(requests[0][1].keys), Array.from(keys));
  assert.deepEqual(g.Core.state.items, {});
});

test('装备突破219后仍按基础属性分配培养点，基础已满时阻止继续加点', async () => {
  const { game: g } = setup();
  g.ATTR_MAX = 219;
  const officer = { id: 1, military: 260, baseMilitary: 200, attrPoints: 100, assignable: { military: 19 } };
  g.Core.state.officers = [officer];
  const assignments = [];
  g.API.assignOfficerAttr = async (id, attr, count) => {
    assignments.push({ id, attr, count });
    return { success: true };
  };
  g.Officer.addAttr(1, 'military', 'confirm', 19);
  await Promise.resolve();
  assert.deepEqual(assignments, [{ id: 1, attr: 'military', count: 19 }]);
  officer.assignable.military = 0;
  officer.baseMilitary = 219;
  officer.military = 279;
  g.Officer.addAttr(1, 'military', 'confirm', 1);
  assert.equal(assignments.length, 1);
  delete officer.baseMilitary;
  officer.military = 210; officer.assignable.military = 9;
  g.Officer.addAttr(1, 'military', 'confirm', 9);
  await Promise.resolve();
  assert.equal(assignments[1].count, 9);
});

test('十二部位详情提供整套和替换，取消整备等级和培养成本，不再展示混搭配装模块', () => {
  const { game: g } = setup();
  const o = { id: 1, name: '测试', level: 60, role: 'idle', equipment: [], preparation: {} };
  g.Core.state.items.brigadier_defense_helmet = 1;
  const html = g.Officer.renderEquipment(o, g.Core.state);
  assert.equal((html.match(/class="officer-equipment-row"/g) || []).length, 12);
  assert.equal((html.match(/class="officer-equipment-card"/g) || []).length, 12);
  assert.equal((html.match(/class="officer-equipment-group-title"/g) || []).length, 3);
  assert.match(html, /officer-equipment-select/);
  assert.doesNotMatch(html, /officer-equipment-prep-row|部位整备|确认整备|零件|培养成本/);
  assert.match(html, /officer-equipment-collapsible/);
  assert.match(html, /准将卫戍盾|准将防御套装/); assert.match(html, /准将守备钢盔/);
  assert.doesNotMatch(html, /保存与应用混搭配装|保存当前混搭|配装方案|save-loadout|equip-loadout/);
  assert.match(html, /穿戴整套十二件/); assert.doesNotMatch(html, /同套三件激活效果/);
});

test('商城装备箱详情在独立弹窗展示，支持关闭、重新打开且不会发起购买', () => {
  const { game: g, context, calls } = setup();
  const modals = [];
  const document = context.document;
  const opener = { focus() { document.activeElement = this; } };
  document.activeElement = opener;
  document.body = { appendChild(modal) { modals.push(modal); } };
  document.getElementById = id => modals.find(modal => modal.id === id) || null;
  document.createElement = () => {
    const buttons = {};
    return {
      events: {}, innerHTML: '',
      querySelector(selector) { return buttons[selector] || (buttons[selector] = { focus() { document.activeElement = this; } }); },
      addEventListener(event, handler) { this.events[event] = handler; },
      remove() { modals.splice(modals.indexOf(this), 1); }
    };
  };
  g.Shop.curCat = 'officer'; g.Shop.equipmentTier = 'general'; g.Shop.equipmentBranch = 'knowledge';
  assert.equal(g.Shop.items().length, 1); assert.equal(g.Shop.items()[0].id, 'box_general_knowledge');
  const v = {}; g.Shop.renderView(v);
  assert.match(v.innerHTML, /Game\.Shop\.showEquipmentDetails\('box_general_knowledge'\)/);
  assert.match(v.innerHTML, />查看详情<\/button>/);
  assert.doesNotMatch(v.innerHTML, /<details|上将校时军表|12件追加/);
  g.Shop.showEquipmentDetails('box_general_knowledge');
  assert.equal(modals.length, 1);
  const modal = modals[0];
  assert.match(modal.innerHTML, /role="dialog" aria-modal="true"/);
  assert.match(modal.innerHTML, /上将学识装备箱详情/);
  assert.match(modal.innerHTML, /上将校时军表/);
  assert.equal((modal.innerHTML.match(/<li>/g) || []).length, 12);
  for (const count of [3, 6, 9, 12]) assert.ok(modal.innerHTML.includes(count + '件追加'));
  modal.events.click({ target: modal.querySelector('#shopEquipmentDetailCloseX') });
  assert.equal(modals.length, 1, '点击弹窗内容不关闭');
  modal.events.keydown({ key: 'Escape', preventDefault() {} });
  assert.equal(modals.length, 0);
  assert.equal(document.activeElement, opener);
  g.Shop.showEquipmentDetails('box_sergeant_defense');
  g.Shop.showEquipmentDetails('box_general_knowledge');
  assert.equal(modals.length, 1, '切换装备箱只保留一个详情弹窗');
  assert.doesNotMatch(modals[0].innerHTML, /士官防御装备箱/);
  modals[0].querySelector('#shopEquipmentDetailClose').onclick();
  assert.equal(modals.length, 0);
  g.Shop.showEquipmentDetails('box_general_knowledge');
  modals[0].events.click({ target: modals[0] });
  assert.equal(modals.length, 0);
  g.Shop.showEquipmentDetails('not-an-equipment-box');
  assert.equal(modals.length, 0);
  assert.deepEqual(calls, []);
});

test('仓库按套聚合与部位筛选，已取消的军备零件不再展示', () => {
  const { game: g } = setup(); const set = g.Constants.equipmentSets.sergeant_logistics;
  set.keys.forEach(k => { g.Core.state.items[k] = 1; });
  g.Depot.setTab('equipment'); const v = {}; g.Depot.renderView(v);
  assert.match(v.innerHTML, /士官后勤套装 · 仓库完整套数 1/);
  g.Depot.equipmentSlot = 'radio'; g.Depot.renderView(v);
  assert.match(v.innerHTML, /士官调度电台/); assert.doesNotMatch(v.innerHTML, /士官勤务军靴/);
  g.Core.state.items.officerPreparationParts = 50; g.Depot.setTab('officer'); g.Depot.renderView(v);
  assert.doesNotMatch(v.innerHTML, /前往军官整备|军备零件/);
});

test('收藏保留图鉴和成就，旧状态中的零件任务及整备不会生成入口', () => {
  const { game: g, calls } = setup();
  assert.equal(g.Officer.prepareEquipment, undefined);
  assert.equal(config.preparation, undefined);
  const html = g.Officer.renderEquipmentOverview({ officerEquipment: { parts: 40, collection: [], daily: [{ id: 'train', name: '训练', progress: 100, required: 100, claimed: true }], weeklyProgress: 15, weeklyClaimed: false, achievements: [{ id: 'first', name: '初装成军', unlocked: true }] } });
  assert.match(html, /★ 初装成军/); assert.match(html, /0\/288/);
  assert.doesNotMatch(html, /领取|零件|整备|日常|周常/);
  assert.deepEqual(calls, []);
});
