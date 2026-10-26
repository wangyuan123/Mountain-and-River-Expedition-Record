/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';
  G.gameNow = G.gameNow || function () { return Date.now(); };

  // —— 工具 ——
  function fmtRemain(ms) {
    if (ms <= 0) return '即将';
    return G.formatDuration(ms / 1000);
  }

  // —— 1. 战情速递 ——
  function renderAlerts(s) {
    var world = s.world || {};
    var cityState = s.cityState || {};
    var marches = world.marches || [];
    var incoming = world.incoming || [];
    var cons = s.constructions || [];
    var now = G.gameNow();
    var shieldRem = (cityState.shieldUntil || 0) > now ? cityState.shieldUntil - now : 0;

    var html = '<div class="zone-head"><span class="zone-title">🚨 战情速递</span><span class="zone-sub">实时军情</span></div>';
    html += '<div class="alert-grid">';
    html += '<div class="alert-cell" onclick="Game.go(\'alerts\')"><div class="alert-num' + (marches.length ? ' active' : '') + '">' + marches.length + '</div><div class="alert-label">行军中</div></div>';
    html += '<div class="alert-cell" onclick="Game.go(\'alerts\')"><div class="alert-num' + (incoming.length ? ' active danger' : '') + '">' + incoming.length + '</div><div class="alert-label">来袭中</div></div>';
    html += '<div class="alert-cell" onclick="Game.go(\'buildRes\')"><div class="alert-num' + (cons.length ? ' active' : '') + '">' + cons.length + '</div><div class="alert-label">建造中</div></div>';
    html += '<div class="alert-cell" onclick="Game.go(\'world\')"><div class="alert-num' + (shieldRem ? ' safe' : '') + '">' + (shieldRem ? fmtRemain(shieldRem) : '无') + '</div><div class="alert-label">护盾</div></div>';
    html += '</div>';
    return html;
  }

  // —— 2. 今日战果 ——
  function renderTodayStats() {
    return '<div class="zone-head"><span class="zone-title">🏆 今日战果</span><span class="zone-sub">暂无数据</span></div><div class="empty-hint">暂无今日战果数据</div>';
  }

  G.Task = G.Task || {};
  G.Task.renderAlerts = renderAlerts;
  G.Task.renderTodayStats = renderTodayStats;
})(window.Game);
