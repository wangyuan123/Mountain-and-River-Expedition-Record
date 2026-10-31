#!/usr/bin/env python3
"""从服务端军备目录生成前端配置块；--check 仅核对一致性，不写文件。"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'backend/src/main/resources/officer-equipment.json'
TARGET = ROOT / 'frontend/js/constants.js'
START = '  // OFFICER_EQUIPMENT_CATALOG_START'
END = '  // OFFICER_EQUIPMENT_CATALOG_END'
config = json.loads(SOURCE.read_text())
public = {k: config[k] for k in ['branches', 'slots', 'tiers', 'new_slot_branch_names']}
body = r'''
  var C = G.Constants;
  C.officerEquipmentConfig = __CONFIG__;
  C.equipmentNames = {}; C.equipmentItems = {}; C.equipmentSets = {};
  C.equipmentTiers = {}; C.equipmentTierOrder = {}; C.equipmentBranches = {}; C.equipmentBranchOrder = {};
  C.equipmentSlots = {}; C.equipmentSlotOrder = {};
  C.equipmentSlotIcons = { weapon: '🗡️', badge: '🎖️', coat: '🦺', helmet: '🪖', gloves: '🧤', belt: '🎒', trousers: '👖', boots: '🥾', backpack: '🎒', radio: '📻', map: '🗺️', watch: '⌚' };
  var cfg = C.officerEquipmentConfig;
  cfg.branches.forEach(function (b, i) { C.equipmentBranches[b.key] = b.name; C.equipmentBranchOrder[b.key] = i + 1; });
  cfg.slots.forEach(function (s, i) { C.equipmentSlots[s.key] = s.name; C.equipmentSlotOrder[s.key] = i + 1; });
  cfg.tiers.forEach(function (t) {
    C.equipmentTiers[t.key] = { name: t.name, level: t.required_level, main: t.base_main.core, sub: t.core_each_secondary };
    C.equipmentTierOrder[t.key] = t.progression_order;
    cfg.branches.forEach(function (b) {
      var set = t.key + '_' + b.key, keys = [], setName = t.name + b.name + '套装';
      cfg.slots.forEach(function (s, i) {
        var key = set + '_' + s.key, core = s.group === 'core';
        var name = core ? t.core_names[b.key][i] : t.name + cfg.new_slot_branch_names[b.key][s.key];
        var main = t.base_main[s.budget], sub = core ? t.core_each_secondary : t.new_slot_each_secondary;
        var attrs = {}; cfg.branches.forEach(function (attr) { attrs[attr.key] = attr.key === b.key ? main : sub; });
        C.equipmentNames[key] = name; keys.push(key);
        C.equipmentItems[key] = { id: key, name: name, cat: 'equipment', tier: t.key, tierName: t.name, branch: b.key, branchName: b.name,
          slot: s.key, slotName: s.name, level: t.required_level, setKey: set,
          icon: C.equipmentSlotIcons[s.key], attrs: attrs,
          desc: setName + '·' + s.name + '：军官Lv.' + t.required_level + '可穿戴，' + b.name + '+' + main + '，其余属性各+' + sub + '；同套3/6/9/12件累计激活共鸣' };
      });
      C.equipmentSets[set] = { id: set, name: setName, tier: t.key, branch: b.key, keys: keys, level: t.required_level, config: t };
    });
  });
  C.shopItems = C.shopItems.filter(function (i) { return i.id.indexOf('box_') !== 0 || !C.equipmentSets[i.id.slice(4)]; });
  Object.keys(C.equipmentSets).forEach(function (key) {
    var set = C.equipmentSets[key];
    C.shopItems.push({ id: 'box_' + key, cat: 'officer', name: set.name.replace('套装', '装备箱'), icon: 'img/items/officer-gear-box.svg',
      desc: '固定获得十二件装备，每部位一件；军官Lv.' + set.level + '可穿戴；同套3/6/9/12件共鸣',
      price: set.config.box_diamonds, stock: null, tag: '十二件整套', equipmentSet: key });
  });
  /** 预览与后端相同的固定属性和累计共鸣；历史整备不参与属性计算；只计算选择，不扣库存。 */
  function bonuses(keys) {
    var result = { military: 0, defense: 0, logistics: 0, knowledge: 0 }, sets = {}, slots = {};
    keys.forEach(function (key) {
      var d = C.equipmentItems[key]; if (!d || slots[d.slot]) return;
      slots[d.slot] = true; sets[d.setKey] = (sets[d.setKey] || 0) + 1;
      cfg.branches.forEach(function (b) { result[b.key] += d.attrs[b.key]; });
    });
    Object.keys(sets).forEach(function (key) {
      var set = C.equipmentSets[key], t = set.config;
      for (var i = 0; i < 4; i++) if (sets[key] >= (i + 1) * 3) {
        cfg.branches.forEach(function (b) { result[b.key] += t.resonance_increment_all[i]; });
        result[set.branch] += t.resonance_increment_main[i];
      }
    });
    return result;
  }
  G.EquipmentCatalog = {
    bonuses: bonuses,
    preview: function (officer, keys) {
      var current = bonuses((officer.equipment || []).map(function (e) { return e.itemId || e.itemKey; }));
      var next = bonuses(keys), net = {};
      cfg.branches.forEach(function (b) { net[b.key] = next[b.key] - current[b.key]; });
      return { bonus: next, net: net };
    }
  };
'''
block = START + body.replace('__CONFIG__', json.dumps(public, ensure_ascii=False, separators=(',', ':'))) + END
source = TARGET.read_text()
if START in source:
    begin = source.index(START); finish = source.index(END, begin) + len(END)
    updated = source[:begin] + block + source[finish:]
else:
    updated = source.replace('})(window.Game);', block + '\n})(window.Game);')
if '--check' in sys.argv:
    if source != updated:
        raise SystemExit('军备前后端目录不一致，请运行 scripts/generate-officer-equipment-catalog.py')
    print('军备前后端目录一致：24套、288件。')
else:
    TARGET.write_text(updated)
