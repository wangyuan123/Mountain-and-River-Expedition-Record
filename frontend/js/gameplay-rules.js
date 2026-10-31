/* global window */
window.Game = window.Game || {};
(function (G) {
  'use strict';

  /** 读取后端快照。缓存按状态对象隔离，失败时不启用客户端公式回退。 */
  G.ServerRules = {
    value: function (key, fallback) { var state = G.Core && G.Core.state || {}; return state.gameplay && state.gameplay[key] != null ? state.gameplay[key] : fallback; },
    level: function (table, id, level) {
      var definition = this.value(table, {})[id];
      return definition && definition.levels && definition.levels[level] || {};
    },
    effect: function (id, level) { return (this.value('buildingEffects', {})[id] || [])[level] || {}; },
    preview: async function (intent) {
      if (!G.API || !G.API.client) return Promise.reject(new Error('预览接口尚未就绪'));
      var token = G.API.getToken(), city = G.API.client.cityId, server = G.API.client.serverId;
      return G.API.client.post('/game/preview', intent, { silent: true }).then(function (response) {
        if (G.API.getToken() !== token || G.API.client.cityId !== city || G.API.client.serverId !== server) throw new Error('账号或城市已切换，请重新预览');
        return response;
      });
    },
    /** 余额或规则推送后废弃在途报价，旧响应不能重新填回缓存。 */
    invalidate: function () { this._revision = (this._revision || 0) + 1; this._quotes = {}; this._pending = {}; this._listeners = {}; },
    cachedPreview: function (intent, ready) {
      var state = G.Core.state, key = JSON.stringify(intent);
      if (this._state !== state) { this._state = state; this.invalidate(); }
      if (this._quotes[key]) return this._quotes[key];
      // 同一报价可能供多个装备卡片使用，每个使用者都需要在响应后刷新。
      if (ready) (this._listeners[key] || (this._listeners[key] = [])).push(ready);
      if (!this._pending[key]) {
        var self = this, revision = this._revision;
        this._pending[key] = this.preview(intent).then(function (result) {
          if (G.Core.state !== state || self._state !== state || self._revision !== revision) return;
          self._quotes[key] = result;
          var listeners = self._listeners[key] || [];
          delete self._pending[key]; delete self._listeners[key];
          listeners.forEach(function (callback) { callback(result); });
        }).catch(function (error) {
          if (G.Core.state !== state || self._state !== state || self._revision !== revision) return;
          delete self._pending[key];
          delete self._listeners[key];
          G.toast(error.message || '预览获取失败，请重试');
        });
      }
      return null;
    }
  };

  /** 数值目录完全来自认证状态；本地文案、图标与排序只参与展示。 */
  G.ServerRules.installCatalog = function (state) {
    var catalog = state.gameplay && state.gameplay.catalog;
    if (!catalog) return;
    var C = G.Constants, D = G.DATA;
    C.officerEquipmentConfig = catalog.equipmentConfig;
    C.starUpFailureRates = catalog.starUpFailurePercents;
    Object.keys(C.equipmentItems).forEach(function (key) { delete C.equipmentItems[key]; });
    Object.assign(C.equipmentItems, catalog.equipmentItems);
    Object.assign(C.equipmentSets, catalog.equipmentSets);
    var cfg = catalog.equipmentConfig;
    cfg.branches.forEach(function (b, i) { C.equipmentBranches[b.key] = b.name; C.equipmentBranchOrder[b.key] = i + 1; });
    cfg.slots.forEach(function (slot, i) { C.equipmentSlots[slot.key] = slot.name; C.equipmentSlotOrder[slot.key] = i + 1; });
    cfg.tiers.forEach(function (t) { C.equipmentTiers[t.key] = { name: t.name, level: t.required_level }; C.equipmentTierOrder[t.key] = t.progression_order; });
    Object.keys(C.equipmentItems).forEach(function (key) {
      var item = C.equipmentItems[key]; item.icon = C.equipmentSlotIcons[item.slot]; C.equipmentNames[key] = item.name; D.items[key] = item;
    });
    Object.keys(C.equipmentSets).forEach(function (key) {
      var set = C.equipmentSets[key], id = 'box_' + key;
      var item = C.shopItems.find(function (entry) { return entry.id === id; });
      if (!item) { item = { id: id, cat: 'officer', name: set.name.replace('套装', '装备箱'), icon: 'img/items/officer-gear-box.svg', equipmentSet: key, stock: null }; C.shopItems.push(item); }
      item.price = catalog.shopPrices[id]; item.desc = '固定获得十二件装备，每部位一件；军官Lv.' + set.level + '可穿戴；同套3/6/9/12件共鸣';
      D.items[id] = Object.assign({ isBox: true }, item);
    });
    C.shopItems.forEach(function (item) { item.price = catalog.shopPrices[item.id]; });
    Object.keys(catalog.units).forEach(function (id) { Object.assign(D.units[id] || (D.units[id] = {}), catalog.units[id]); });
    Object.keys(catalog.buildings).forEach(function (id) { Object.assign(D.buildings[id] || (D.buildings[id] = {}), catalog.buildings[id]); });
    Object.keys(catalog.technologies).forEach(function (id) {
      var t = catalog.technologies[id]; Object.assign(D.techs[id] || (D.techs[id] = {}), t, { max: t.maxLevel, growth: t.costGrowth, baseCost: t.cost, affect: t.effect });
    });
    Object.keys(catalog.skills).forEach(function (id) { Object.assign(D.officerSkills[id] || (D.officerSkills[id] = {}), catalog.skills[id]); });
    Object.keys(catalog.forts).forEach(function (id) { Object.assign(D.forts[id] || (D.forts[id] = {}), catalog.forts[id]); });
    Object.keys(catalog.items).forEach(function (id) {
      var item = catalog.items[id]; Object.assign(D.items[id] || (D.items[id] = {}), item, { seconds: item.speedUpSeconds });
    });
    catalog.ranks.forEach(function (rank, index) { Object.assign(D.militaryRanks[index] || (D.militaryRanks[index] = {}), rank); });
    D.zones = catalog.campaigns;
    Object.keys(D.japaneseUnits).forEach(function (id) {
      var unit = D.japaneseUnits[id], name = unit.name, history = unit.history;
      Object.assign(unit, D.units[id], { name: name, history: history });
    });
    G.ATTR_MAX = catalog.attrMax; G.OFFICER_MAX_LEVEL = catalog.officerMaxLevel;
  };
})(window.Game);
