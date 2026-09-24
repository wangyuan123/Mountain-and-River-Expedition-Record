/* global window, document, localStorage */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var STORAGE_KEY = G.Constants.themeStorageKey;
  var THEMES = G.Constants.themes;

  var Theme = {
    THEMES: THEMES,

    get: function () {
      try {
        var saved = localStorage.getItem(STORAGE_KEY);
        // 仅用于迁移旧版主题偏好，不向界面输出旧标识。
        if (saved === '3gqq') {
          saved = 'blue-white-classic';
          try { localStorage.setItem(STORAGE_KEY, saved); } catch (e) { /* ignore */ }
        }
        if (saved) {
          for (var i = 0; i < THEMES.length; i++) {
            if (THEMES[i].id === saved) return saved;
          }
        }
      } catch (e) { /* ignore */ }
      return G.Constants.defaultTheme; // 默认采用经典家园风格
    },

    set: function (themeId, quiet) {
      var valid = false;
      if (themeId) {
        for (var vi = 0; vi < THEMES.length; vi++) {
          if (THEMES[vi].id === themeId) { valid = true; break; }
        }
      }
      if (!valid) themeId = G.Constants.defaultTheme;
      try {
        localStorage.setItem(STORAGE_KEY, themeId);
      } catch (e) { /* ignore */ }

      if (document.documentElement) {
        document.documentElement.setAttribute('data-theme', themeId);
      }
      if (document.body) {
        document.body.setAttribute('data-theme', themeId);
      }

      // 同步设置页的下拉框状态 (若存在)
      var sel = document.getElementById('themeSelector');
      if (sel && sel.value !== themeId) {
        sel.value = themeId;
      }

      // 查找当前主题名称
      var targetName = themeId;
      for (var i = 0; i < THEMES.length; i++) {
        if (THEMES[i].id === themeId) {
          targetName = THEMES[i].name;
          break;
        }
      }

      if (!quiet && G.toast) {
        G.toast('风格已切换: ' + targetName);
      }

      // 触发界面重新渲染以适应主题细节 (若核心引擎已就绪)
      if (G.Core && typeof G.Core.render === 'function' && G.Core.route) {
        G.Core.render();
      }
    },

    init: function () {
      var activeTheme = this.get();
      if (document.documentElement) {
        document.documentElement.setAttribute('data-theme', activeTheme);
      }
    },

    showModal: function () {
      var self = this;
      var curTheme = self.get();

      var existing = document.getElementById('themeModalMask');
      if (existing) existing.remove();

      var modal = document.createElement('div');
      modal.id = 'themeModalMask';
      modal.className = 'modal-mask';
      modal.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.6);z-index:99999;display:flex;align-items:center;justify-content:center;padding:16px;';

      var h = '';
      h += '<div class="modal-card theme-modal-card" style="max-width:400px;width:100%;background:var(--surface, #fff);border-radius:10px;padding:16px;box-shadow:0 12px 32px rgba(0,0,0,0.3);color:var(--ink, #222);">';
      h += '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;border-bottom:1px solid rgba(150,150,150,0.2);padding-bottom:8px;">';
      h += '<span style="font-weight:bold;font-size:16px;">🎨 选择界面风格</span>';
      h += '<button id="closeThemeModal" style="background:transparent;border:none;font-size:20px;cursor:pointer;color:inherit;line-height:1;">×</button>';
      h += '</div>';

      h += '<div style="display:flex;flex-direction:column;gap:8px;margin-bottom:14px;">';
      for (var i = 0; i < THEMES.length; i++) {
        var t = THEMES[i];
        var isCur = t.id === curTheme;
        var activeBorder = isCur ? 'border:2px solid #2563eb;background:rgba(37,99,235,0.08);' : 'border:1px solid rgba(150,150,150,0.3);background:transparent;';
        h += '<div class="theme-option-card" data-theme-id="' + t.id + '" style="padding:10px;border-radius:6px;cursor:pointer;' + activeBorder + 'display:flex;flex-direction:column;gap:3px;">';
        h += '<div style="display:flex;justify-content:space-between;align-items:center;">';
        h += '<span style="font-weight:600;font-size:14px;">' + (isCur ? '✓ ' : '') + t.name + '</span>';
        h += '<span style="font-size:11px;padding:1px 6px;border-radius:4px;background:rgba(150,150,150,0.15);">' + t.tag + '</span>';
        h += '</div>';
        h += '<div style="font-size:12px;opacity:0.75;line-height:1.4;">' + t.desc + '</div>';
        h += '</div>';
      }
      h += '</div>';

      h += '<div style="text-align:right;">';
      h += '<button id="confirmThemeClose" class="btn sm ok" style="padding:6px 16px;font-size:13px;cursor:pointer;">完成</button>';
      h += '</div>';
      h += '</div>';

      modal.innerHTML = h;
      document.body.appendChild(modal);

      var closeModal = function () {
        if (modal.parentNode) modal.parentNode.removeChild(modal);
      };

      var closeBtn = modal.querySelector('#closeThemeModal');
      if (closeBtn) closeBtn.onclick = closeModal;
      var confBtn = modal.querySelector('#confirmThemeClose');
      if (confBtn) confBtn.onclick = closeModal;
      modal.onclick = function (e) {
        if (e.target === modal) closeModal();
      };

      var cards = modal.querySelectorAll('.theme-option-card');
      cards.forEach(function (card) {
        card.onclick = function () {
          var selectedId = this.getAttribute('data-theme-id');
          self.set(selectedId);
          closeModal();
        };
      });
    }
  };

  // 立即初始化以确保首屏渲染无抖动
  Theme.init();

  G.Theme = Theme;
})(window.Game);
