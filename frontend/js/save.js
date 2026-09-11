/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var ATTR_MAX = 255;
  var OFFICER_MAX_LEVEL = 100;

  function expNeeded(level) {
    return level * 200;
  }

  function growAttr(attr) {
    return Math.min(ATTR_MAX, attr + 1 + Math.floor(Math.random() * 3));
  }

  var MULTI_SLOT = ['house', 'farm', 'refinery', 'oilfield', 'raremine', 'factory', 'depot'];

  function migrateBuildings(b) {
    if (!b || typeof b !== 'object') return b;
    delete b.barracks;
    for (var i = 0; i < MULTI_SLOT.length; i++) {
      var id = MULTI_SLOT[i];
      var v = b[id];
      if (Array.isArray(v)) {
        var arr = [];
        for (var j = 0; j < v.length; j++) if ((v[j] || 0) > 0) arr.push(v[j]);
        b[id] = arr;
      } else if (typeof v === 'number') {
        b[id] = v > 0 ? [v] : [];
      } else {
        b[id] = [];
      }
    }
    return b;
  }

  function migrateConstructions(list) {
    if (!Array.isArray(list)) return list;
    for (var i = 0; i < list.length; i++) {
      var job = list[i];
      if (!job || job.id == null) continue;
      if (MULTI_SLOT.indexOf(job.id) >= 0 && job.slot == null) job.slot = 0;
    }
    return list;
  }

  // ===== 存档逻辑：状态由后端管理，前端不再做本地存档/云端同步 =====
  // 保留 Save 对象作为兼容桩，所有方法为空操作或转发到 API。
  var Save = {
    syncing: false,
    dirty: false,
    // 兼容旧调用：云同步已由后端接管，此处为空操作
    syncToCloud: function () { return Promise.resolve(); },
    scheduleSync: function () { /* no-op */ },
    loadFromCloud: function () {
      if (G.API && G.API.isLoggedIn()) return G.API.getGameState();
      return Promise.resolve(null);
    }
  };

  // G.save：状态由后端管理，前端不再本地保存。保留为空操作以兼容大量现有调用。
  G.save = function () { /* no-op - state managed by backend */ return true; };

  // G.load：从后端拉取游戏状态；未登录时不创建本地状态。
  G.load = async function () {
    if (G.API && G.API.isLoggedIn()) {
      return await G.API.getGameState();
    }
    return null;
  };

  // G.reset：调用后端重置接口，返回新的游戏状态。
  G.reset = function () {
    if (G.API && G.API.isLoggedIn()) {
      return G.API.resetGame();
    }
    G.state = null;
    if (G.Core) G.Core.state = null;
    return Promise.resolve(null);
  };

  G.Save = Save;
  G.ATTR_MAX = ATTR_MAX;
  G.OFFICER_MAX_LEVEL = OFFICER_MAX_LEVEL;
  G.expNeeded = expNeeded;
  G.growAttr = growAttr;
})(window.Game);
