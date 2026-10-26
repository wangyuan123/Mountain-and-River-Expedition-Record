const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const config = JSON.parse(fs.readFileSync(path.join(__dirname, '../../backend/src/main/resources/officer-equipment.json'), 'utf8'));

function setup() {
  const calls = [];
  const game = { Core: { state: { resources: { diamond: 10000 }, items: {}, officers: [] }, views: {}, render() {} },
    API: { officerEquipment(action, payload) { calls.push([action, payload]); return Promise.resolve({ success: true, message: '完成' }); } },
    toast() {}, escapeHtml: s => String(s == null ? '' : s), iconHtml: s => s };
  const context = vm.createContext({ window: null, Game: game, document: { getElementById() { return null; } }, console }); context.window = context;
  for (const file of ['constants.js', 'data.js', 'officer.js', 'shop.js', 'depot.js']) vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  return { game: context.Game, calls };
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

test('六档四分支的前端基础、整备、四档共鸣与服务端配置一致', () => {
  const { game: g } = setup();
  for (const t of config.tiers) for (const b of config.branches) {
    const set = g.Constants.equipmentSets[t.key + '_' + b.key];
    const result = g.EquipmentCatalog.bonuses(set.keys, {});
    for (const a of config.branches) assert.equal(result[a.key], a.key === b.key ? t.expected_full_set.main : t.expected_full_set.each_secondary);
    const prep = Object.fromEntries(config.slots.map(s => [s.key, 5]));
    assert.equal(g.EquipmentCatalog.bonuses(set.keys, prep)[b.key], t.expected_full_set.main + 12 * t.effective_preparation_cap);
  }
});

test('同档混搭分别计算共鸣，替换预览不会扣库存', () => {
  const { game: g } = setup();
  const military = g.Constants.equipmentSets.marshal_military.keys;
  const defense = g.Constants.equipmentSets.marshal_defense.keys;
  const mix = military.slice(0, 6).concat(defense.slice(6));
  const pure = g.EquipmentCatalog.bonuses(military, {}), mixed = g.EquipmentCatalog.bonuses(mix, {});
  const preview = g.EquipmentCatalog.preview({ equipment: military.map(k => ({ itemId: k })) }, mix);
  assert.equal(preview.net.military, mixed.military - pure.military);
  assert.equal(preview.net.defense, mixed.defense - pure.defense);
  assert.deepEqual(g.Core.state.items, {});
});

test('十二部位详情提供整套、替换、培养成本及混搭方案，并支持新档新部位', () => {
  const { game: g } = setup();
  const o = { id: 1, name: '测试', level: 60, role: 'idle', equipment: [], preparation: {} };
  g.Core.state.items.brigadier_defense_helmet = 1;
  const html = g.Officer.renderEquipment(o, g.Core.state);
  assert.equal((html.match(/class="officer-equipment-row"/g) || []).length, 12);
  assert.equal((html.match(/class="officer-equipment-card"/g) || []).length, 12);
  assert.equal((html.match(/class="officer-equipment-group-title"/g) || []).length, 3);
  assert.match(html, /officer-equipment-select/);
  assert.match(html, /officer-equipment-prep-row/);
  assert.match(html, /officer-equipment-collapsible/);
  assert.match(html, /准将卫戍盾|准将防御套装/); assert.match(html, /准将守备钢盔/);
  assert.match(html, /20零件 \+ 1000主城黄金/); assert.match(html, /保存当前混搭/);
  assert.match(html, /穿戴整套十二件/); assert.doesNotMatch(html, /同套三件激活效果/);
});

test('商城可筛选新档套装，并展示完整十二件清单', () => {
  const { game: g } = setup(); g.Shop.curCat = 'officer'; g.Shop.equipmentTier = 'general'; g.Shop.equipmentBranch = 'knowledge';
  assert.equal(g.Shop.items().length, 1); assert.equal(g.Shop.items()[0].id, 'box_general_knowledge');
  const v = {}; g.Shop.renderView(v); assert.match(v.innerHTML, /查看十二件清单与共鸣/);
  assert.match(v.innerHTML, /上将校时军表/); assert.match(v.innerHTML, /12件追加/);
});

test('仓库按套聚合与部位筛选，军备零件展示培养入口', () => {
  const { game: g } = setup(); const set = g.Constants.equipmentSets.sergeant_logistics;
  set.keys.forEach(k => { g.Core.state.items[k] = 1; });
  g.Depot.setTab('equipment'); const v = {}; g.Depot.renderView(v);
  assert.match(v.innerHTML, /士官后勤套装 · 仓库完整套数 1/);
  g.Depot.equipmentSlot = 'radio'; g.Depot.renderView(v);
  assert.match(v.innerHTML, /士官调度电台/); assert.doesNotMatch(v.innerHTML, /士官勤务军靴/);
  g.Core.state.items.officerPreparationParts = 50; g.Depot.setTab('officer'); g.Depot.renderView(v);
  assert.match(v.innerHTML, /前往军官整备/);
});

test('培养提交预期等级，任务界面区分日常周常和永久成就', async () => {
  const { game: g, calls } = setup();
  await g.Officer.prepareEquipment(1, 'radio', 2);
  assert.equal(calls[0][0], 'prepare'); assert.equal(calls[0][1].expectedLevel, 2);
  const html = g.Officer.renderEquipmentOverview({ officerEquipment: { parts: 40, collection: [], daily: [{ id: 'train', name: '训练', progress: 100, required: 100, claimed: true }], weeklyProgress: 15, weeklyClaimed: false, achievements: [{ id: 'first', name: '初装成军', unlocked: true }] } });
  assert.match(html, /已领取/); assert.match(html, /领取240零件/); assert.match(html, /★ 初装成军/); assert.match(html, /0\/288/);
});
