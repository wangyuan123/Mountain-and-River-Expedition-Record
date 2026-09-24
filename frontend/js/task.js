/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  // —— 工具 ——
  function fmtRemain(ms) {
    if (ms <= 0) return '即将';
    var s = Math.floor(ms / 1000);
    var h = Math.floor(s / 3600);
    var m = Math.floor((s % 3600) / 60);
    var ss = s % 60;
    if (h > 0) return h + '时' + m + '分';
    if (m > 0) return m + '分' + ss + '秒';
    return ss + '秒';
  }

  // —— 1. 活动与任务 ——
  function renderActivities() {
    return '<div class="zone-head"><span class="zone-title">🎁 活动与任务</span><span class="zone-sub">暂无数据</span></div><div class="empty-hint">暂无活动与任务数据</div>';
  }

  // —— 2. 战情速递 ——
  function renderAlerts(s) {
    var world = s.world || {};
    var cityState = s.cityState || {};
    var marches = world.marches || [];
    var incoming = world.incoming || [];
    var cons = s.constructions || [];
    var now = Date.now();
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

  // —— 3. 今日战果 ——
  function renderTodayStats() {
    return '<div class="zone-head"><span class="zone-title">🏆 今日战果</span><span class="zone-sub">暂无数据</span></div><div class="empty-hint">暂无今日战果数据</div>';
  }

  G.Task = G.Task || {};
  G.Task.renderActivities = renderActivities;
  G.Task.renderAlerts = renderAlerts;
  G.Task.renderTodayStats = renderTodayStats;
})(window.Game);
