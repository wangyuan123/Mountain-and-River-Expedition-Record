/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var questPanelOpen = false;
  var Quests = {
    state: null,

    init: function () {
      this.state = null;
    },

    ensureToday: function () {
      this.state = null;
    },

    onEvent: function () {},

    claim: function () {
      if (G.toast) G.toast('暂无每日任务数据');
      return { ok: false, reason: '暂无每日任务数据' };
    },

    isAllDone: function () { return false; },

    summary: function () { return { total: 0, done: 0, claimed: 0 }; }
  };

  Quests.manualRefresh = function () {
    if (G.toast) G.toast('暂无每日任务数据');
  };

  Quests.goto = function () {
    if (G.toast) G.toast('暂无每日任务数据');
  };

  Quests.togglePanel = function () {
    questPanelOpen = !questPanelOpen;
    if (G.Core && G.Core.render) G.Core.render();
  };

  function renderQuestCard() {
    return '<div class="quest-card" id="dailyQuestsAnchor"><div class="empty-hint">暂无每日任务数据</div></div>';
  }

  function renderQuestEntry() {
    return '<div class="act-card act-quest' + (questPanelOpen ? ' open' : '') + '" onclick="Game.Task.Quests.togglePanel()">' +
      '<div class="act-icon" style="color:#888">🎯</div><div class="act-body"><div class="act-title">每日任务</div>' +
      '<div class="act-desc">暂无每日任务数据</div></div><div class="act-cta quest-toggle">' + (questPanelOpen ? '▴' : '▾') + '</div></div>';
  }

  function renderQuestEmbed() {
    return questPanelOpen ? '<div class="quest-embed"><div class="empty-hint">暂无每日任务数据</div></div>' : '';
  }

  G.Task = G.Task || {};
  G.Task.Quests = Quests;
  G.Task.renderQuestCard = renderQuestCard;
  G.Task.renderQuestEntry = renderQuestEntry;
  G.Task.renderQuestEmbed = renderQuestEmbed;
})(window.Game);
