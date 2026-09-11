/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;

  // —— 工具 ——
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
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
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  // —— 1. 活动与任务 ——
  function renderActivities() {
    return '<div class="zone-head"><span class="zone-title">🎁 活动与任务</span><span class="zone-sub">暂无数据</span></div><div class="empty-hint">暂无活动与任务数据</div>';
  }

  // —— 2. 待办事项 ——
  function renderTodos(s) {
    var items = [];
    var now = Date.now();
    var world = s.world || {};
    var cityState = s.cityState || {};
    var marches = world.marches || [];
    var incoming = world.incoming || [];
    var constructions = s.constructions || [];

    // 建造中
    for (var i = 0; i < constructions.length; i++) {
      var c = constructions[i];
      var bName = (D.buildings && D.buildings[c.id] && D.buildings[c.id].name) || c.id || '建筑';
      var remain = (c.finishesAt || 0) - now;
      items.push({
        icon: '🔨',
        text: '<b>' + esc(bName) + '</b> 升级中,剩余 ' + fmtRemain(remain),
        goto: 'buildRes',
        urgent: remain < 60000
      });
    }
    // 行军 / 采集
    for (var j = 0; j < marches.length; j++) {
      var m = marches[j];
      var actionName = ({ scout: '侦查', gather: '采集', attack: '出征', occupy: '占领', returnHome: '回城' })[m.action] || m.action || '行动';
      var target = m.targetName || (m.targetX != null ? '(' + m.targetX + ',' + m.targetY + ')' : '目标');
      var key = m.gathering ? 'gatherEndAt' : 'arriveAt';
      var remain2 = (m[key] || 0) - now;
      if (m.returning) actionName = '返程';
      items.push({
        icon: '⚔️',
        text: actionName + ' <b>' + esc(target) + '</b> · 剩余 ' + fmtRemain(remain2),
        goto: 'alerts',
        urgent: false
      });
    }
    // 来袭
    for (var k = 0; k < incoming.length; k++) {
      var a = incoming[k];
      var incRem = (a.arriveAt || 0) - now;
      items.push({
        icon: '⚠️',
        text: '<b>' + esc(a.fromName || '不明') + '</b> 正在来袭 · 剩余 ' + fmtRemain(incRem),
        goto: 'alerts',
        urgent: true
      });
    }
    // 护盾
    if (cityState.shieldUntil && cityState.shieldUntil > now) {
      items.push({
        icon: '🛡️',
        text: '护盾生效中 · 剩余 ' + fmtRemain(cityState.shieldUntil - now),
        goto: 'world',
        urgent: false
      });
    }
    // 邮件未读
    var unread = (G.Mail && G.Mail.unread) ? G.Mail.unread() : 0;
    if (unread > 0) {
      items.push({
        icon: '📬',
        text: '<b>' + unread + '</b> 封未读邮件 · 含系统通知和战报',
        goto: 'mail',
        urgent: unread >= 5
      });
    }
    // 仓库可用道具
    var depotItems = s.items || {};
    var hasItemCount = 0;
    var topItem = null;
    for (var dk in depotItems) {
      if (depotItems[dk] > 0) {
        hasItemCount++;
        if (!topItem) topItem = dk;
      }
    }
    if (hasItemCount > 0) {
      var topName = (D.items && D.items[topItem] && D.items[topItem].name) || topItem;
      items.push({
        icon: '📦',
        text: '仓库有 <b>' + hasItemCount + '</b> 件道具 · 含 <b>' + esc(topName) + '</b>',
        goto: 'depot',
        urgent: false
      });
    }

    var html = '<div class="zone-head"><span class="zone-title">📋 代办事项</span><span class="zone-sub">' + items.length + ' 项待处理</span></div>';
    if (items.length === 0) {
      html += '<div class="empty-hint">暂无待办,世界太平 ✨</div>';
    } else {
      // 紧急项置顶
      items.sort(function (a, b) { return (b.urgent ? 1 : 0) - (a.urgent ? 1 : 0); });
      var max = Math.min(items.length, 5);
      html += '<div class="todo-list">';
      for (var t = 0; t < max; t++) {
        var it = items[t];
        html += '<div class="todo-item' + (it.urgent ? ' urgent' : '') + '" onclick="Game.go(\'' + it.goto + '\')">';
        html += '<span class="todo-icon">' + it.icon + '</span>';
        html += '<span class="todo-text">' + it.text + '</span>';
        html += '<span class="todo-arrow">›</span>';
        html += '</div>';
      }
      if (items.length > max) {
        html += '<div class="todo-more" onclick="Game.go(\'alerts\')">查看全部 ' + items.length + ' 项 ›</div>';
      }
      html += '</div>';
    }
    return html;
  }

  // —— 3. 战情速递 ——
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

  // —— 4. 今日战果 ——
  function renderTodayStats() {
    return '<div class="zone-head"><span class="zone-title">🏆 今日战果</span><span class="zone-sub">暂无数据</span></div><div class="empty-hint">暂无今日战果数据</div>';
  }

  G.Task = G.Task || {};
  G.Task.renderActivities = renderActivities;
  G.Task.renderTodos = renderTodos;
  G.Task.renderAlerts = renderAlerts;
  G.Task.renderTodayStats = renderTodayStats;
})(window.Game);
