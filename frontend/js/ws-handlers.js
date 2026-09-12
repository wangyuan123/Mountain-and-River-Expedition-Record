/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  // ====================================================================
  //  用户编辑状态检测
  //  WS tick / 其它事件触发的全屏重渲染会把正在编辑的表单刷掉
  //  (典型场景: 用户在写信/回复时, 收件人/主题/正文输了一半被 tick 打断)
  //  这里集中判断, 在 "用户正在编辑" 时跳过内容重渲染, 只更新顶部资源栏和导航红点
  // ====================================================================
  function isUserEditing() {
    // 1) 任何 input/textarea 获得焦点 (写邮件、改军官名、改城市名 等)
    var ae = document.activeElement;
    if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA' || ae.isContentEditable)) {
      return true;
    }
    // 2) 个人资料编辑框打开中 (与原 tick handler 行为一致)
    var profileBox = document.querySelector('.edit-profile-box');
    if (profileBox && profileBox.style.display !== 'none') return true;
    // 3) 邮件模块: 处于写信 / 阅读 (含回复输入框) 状态时一律视为编辑中
    //    真正重渲染需要的只是"未读红点"和"最新 state", mail.js 自己已经处理
    if (G.Core && G.Core.route === 'mail' && G.Mail) {
      if (G.Mail.isComposing && G.Mail.isComposing()) return true;
      if (G.Mail.isReplying && G.Mail.isReplying()) return true;
    }
    return false;
  }

  // Refresh full state from server, sync G.state + Core.state, then re-render
  function refreshState() {
    if (!G.API || !G.API.getGameState) return;
    // force=true to bypass cache — WS events mean something changed
    G.API.getGameState(true).then(function (state) {
      if (G.API.applyState) {
        G.API.applyState(state);
      } else {
        G.state = state;
        if (G.Core) G.Core.state = G.state;
      }
      // 用户正在编辑表单时, 跳过 Core.render() 以免把表单刷掉
      // (mail.js 已用 captureFormState / restoreFormState 做防御层, 但能少一次 DOM 操作更好)
      if (G.Core && !isUserEditing()) {
        G.Core.render();
      } else if (G.Core) {
        // 编辑中: 仍刷新顶栏/导航, 但不动 content
        if (G.Core.refreshTop) G.Core.refreshTop();
        if (G.Core.renderNavBar) G.Core.renderNavBar();
      }
    }).catch(function (e) {
      console.error('WS state refresh failed:', e);
    });
  }

  // tick handler - update resources, construction progress, march progress
  G.WS.on('tick', function (data) {
    if (!G.state) return;
    // Ensure Core.state references the same object
    if (G.Core && G.Core.state !== G.state) G.Core.state = G.state;
    if (data.resources) {
      Object.assign(G.state.resources, data.resources);
    }
    if (data.population) {
      G.state.population = data.population;
    }
    if (data.constructions) {
      G.state.constructions = data.constructions;
    }
    if (data.marches && G.state.world) {
      G.state.world.marches = data.marches;
    }
    if (data.cityState) {
      G.state.cityState = data.cityState;
    }
    if (data.officers) {
      G.state.officers = data.officers;
    }
    // 本 tick 内刚刚完成的建筑(后端在建筑倒计时归零时通过 tick 推送)
    if (data.completedBuilds && G.Task && G.Task.Quests && G.Task.Quests.onEvent) {
      for (var bi = 0; bi < data.completedBuilds.length; bi++) {
        // 每日任务的"升级建筑"任务要求等建筑真完成才 +1,
        // 避免"刚点升级就提示可领奖"的体验割裂。
        G.Task.Quests.onEvent('BUILD_DONE', data.completedBuilds[bi]);
      }
    }
    // Re-render affected areas using silent/zero-flash update for seamless experience
    // Skip content refresh if user is editing (mail compose / reply / profile edit / focused input)
    if (G.Core) {
      G.Core.refreshTop();
      if (!isUserEditing()) {
        if (G.Core.silentUpdate) {
          G.Core.silentUpdate(data);
        } else if (G.Core.route) {
          G.Core.refreshContent();
        }
      }
    }
  });

  // march handler - march arrived, gathering complete, return arrived
  G.WS.on('march', function (data) {
    // Show toast notification
    var messages = {
      arrived: '部队已到达目标: ' + (data.targetName || ''),
      scoutComplete: '侦查完成，情报已送达',
      gatherComplete: '采集完成! 获得' + (data.amount || 0) + ' ' + (data.resource || ''),
      returned: '部队已返回城市' + (data.amount ? '，带回' + data.amount + ' ' + (data.resource || '') : '')
    };
    if (messages[data.event]) G.toast(messages[data.event]);
    if (data.event === 'scoutComplete' && G.Task && G.Task.Quests) {
      G.Task.Quests.onEvent('scout');
    }
    // Refresh full state from server
    refreshState();
  });

  // scoutReport handler - 侦查完成报告, 推入战报列表
  G.WS.on('scoutReport', function (data) {
    if (!data || !G.state) return;
    if (!Array.isArray(G.state.reports)) G.state.reports = [];
    // 去重(按 id), 避免 ws 重连或同一条报告被推多次
    var id = data.id;
    var exists = false;
    if (id != null) {
      for (var i = 0; i < G.state.reports.length; i++) {
        if (G.state.reports[i].id != null && String(G.state.reports[i].id) === String(id)) {
          exists = true;
          break;
        }
      }
    }
    if (!exists) {
      // 默认未读（若后端没带 readAt 字段）
      if (data.readAt == null) data.readAt = 0;
      G.state.reports.unshift(data);
      // 防止无限增长, 只保留最近 100 条
      if (G.state.reports.length > 100) G.state.reports.length = 100;
    }
    if (G.Battle && G.Battle.syncUnread) G.Battle.syncUnread();
    // 如果当前正停在战报页, 局部刷新即可
    if (G.Core && G.Core.route === 'reports' && G.Battle && G.Battle.renderReportsList) {
      G.Battle.renderReportsList(document.getElementById('content'));
    } else if (G.Battle && G.Battle.refreshUnread) {
      G.Battle.refreshUnread();
    }
  });

  // battle handler - battle report
  G.WS.on('battle', function (data) {
    if (!data) return;
    if (data.win) {
      G.toast('战斗胜利! 掠夺资源' + JSON.stringify(data.plunder || {}));
    } else {
      G.toast('战斗失利');
    }
    // 战斗报告也归入战报列表，标记为未读
    if (data && G.state) {
      if (!Array.isArray(G.state.reports)) G.state.reports = [];
      // 没有 id 时使用伪 id 避免重复
      if (data.id == null) data.id = 'battle-' + (data.time || Date.now());
      if (data.readAt == null) data.readAt = 0;
      // 去重
      var dup = false;
      for (var i = 0; i < G.state.reports.length; i++) {
        if (G.state.reports[i].id != null && String(G.state.reports[i].id) === String(data.id)) {
          dup = true; break;
        }
      }
      if (!dup) {
        G.state.reports.unshift(data);
        if (G.state.reports.length > 100) G.state.reports.length = 100;
      }
    }
    if (G.Battle && G.Battle.syncUnread) G.Battle.syncUnread();
    // 刷新战报页 / 红点
    if (G.Core && G.Core.route === 'reports' && G.Battle && G.Battle.renderReportsList) {
      G.Battle.renderReportsList(document.getElementById('content'));
    } else if (G.Battle && G.Battle.refreshUnread) {
      G.Battle.refreshUnread();
    }
    // 仍然要拉一次最新 state 以便部队/资源变化
    refreshState();
  });

  // incoming handler - being attacked
  G.WS.on('incoming', function (data) {
    G.toast('警告: 敌军来袭! 来自' + (data.fromName || '未知'));
    // Refresh state
    refreshState();
  });

  // ===== WebSocket connection status banner =====
  var _reconnectToastTimer = null;

  function showWsBanner(text, color) {
    var banner = document.getElementById('wsStatusBanner');
    if (!banner) return;
    banner.textContent = text;
    banner.style.background = color || 'var(--danger)';
    banner.style.display = 'block';
  }

  function hideWsBanner() {
    var banner = document.getElementById('wsStatusBanner');
    if (banner) banner.style.display = 'none';
  }

  // When WebSocket disconnects unexpectedly, show reconnect banner
  G.WS.on('disconnected', function () {
    showWsBanner('连接已断开，正在重连...', 'var(--danger)');
  });

  // connected handler - WebSocket reconnected
  G.WS.on('connected', function () {
    if (G.Battle) G.Battle._reportsHistoryLoaded = null;
    // Hide the disconnect banner and show a brief "reconnected" confirmation
    hideWsBanner();
    if (_reconnectToastTimer) clearTimeout(_reconnectToastTimer);
    showWsBanner('已重新连接', 'var(--accent)');
    _reconnectToastTimer = setTimeout(function () {
      hideWsBanner();
      _reconnectToastTimer = null;
    }, 2000);
    // Refresh full state
    refreshState();
  });

  G.WS.on('chat', function (data) {
    if (G.Chat && G.Chat.receive) G.Chat.receive(data);
  });

  // mail handler - 收到新邮件时刷新未读
  G.WS.on('mail', function (data) {
    // 后端推送新邮件到达事件, 优先刷新未读数 (更新导航红点)
    // 全量 refreshState 放在后面, 用户若正在写信/回复会被 isUserEditing 拦截
    if (G.Mail && G.Mail.refreshUnread) {
      G.Mail.refreshUnread();
    }
    refreshState();
  });
})(window.Game);
