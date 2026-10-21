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
    // 4) 装备配置抽屉打开中 (避免后台全量刷新干扰正文与草稿)
    if (document.querySelector && document.querySelector('.unit-equipment-overlay')) {
      return true;
    }
    return false;
  }

  /** 同步服务端最新状态；编辑表单或阅读历史战报时，只更新顶栏和导航以保留当前内容。 */
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
      if (G.WorldMap && G.WorldMap.refreshMarches) G.WorldMap.refreshMarches();
      // 战报是历史快照，回城等后台事件无需重建正文，否则会丢失展开状态和阅读位置。
      // 新战报仍由 battle / scoutReport 事件单独更新列表。
      var readingReport = G.Core && (G.Core.route === 'reports' || G.Core.route === 'reportDetail');
      if (G.Core && !isUserEditing() && !readingReport) {
        G.Core.render();
      } else if (G.Core) {
        // 状态已同步；编辑或阅读中只刷新顶栏/导航，保留正文 DOM。
        if (G.Core.refreshTop) G.Core.refreshTop();
        if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
      }
    }).catch(function (e) {
      console.error('WS state refresh failed:', e);
    });
  }

  // tick handler - update resources, construction progress, march progress
  G.WS.on('support_update', refreshState);
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
    if (data.items) G.state.items = data.items;
    if (data.unitUnlocks) G.state.unitUnlocks = data.unitUnlocks;
    if (data.unitEquipment) {
      G.state.unitEquipment = data.unitEquipment;
    }
    if (data.constructions) {
      var previousConstructions = G.state.constructions || [];
      var previousBySlot = {};
      previousConstructions.forEach(function (job) {
        var key = String(job.id) + ':' + (job.slot == null ? '' : job.slot);
        previousBySlot[key] = job;
      });
      G.state.constructions = data.constructions.map(function (job) {
        if (job.queueId != null) return job;
        var key = String(job.id) + ':' + (job.slot == null ? '' : job.slot);
        var previous = previousBySlot[key];
        return previous && previous.queueId != null ? Object.assign({}, previous, job, { queueId: previous.queueId }) : job;
      });
    }
    if (data.marches && G.state.world) {
      var prevMap = {};
      (G.state.world.marches || []).forEach(function (m) {
        if (m && m.id != null) prevMap[String(m.id)] = m;
      });
      G.state.world.marches = data.marches.map(function (m) {
        var prev = prevMap[String(m.id)];
        return prev ? Object.assign({}, prev, m) : m;
      });
      if (G.World && G.World.checkMarchArrivals) G.World.checkMarchArrivals();
    }
    if (data.reinforcements && G.state.world) G.state.world.reinforcements = data.reinforcements;
    if (Array.isArray(data.incoming) && G.state.world) {
      var previousIncoming = G.state.world.incoming || [];
      data.incoming.forEach(function (attack) {
        var previous = previousIncoming.find(function (item) { return String(item.id) === String(attack.id); });
        if (previous && previous.expanded) attack.expanded = true;
      });
      G.state.world.incoming = data.incoming;
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
    }
    // 空数组也必须覆盖旧路线，避免返程结束后地图残留敌军标记。
    if (Array.isArray(data.returningEnemyMarches) && G.state.world) G.state.world.returningEnemyMarches = data.returningEnemyMarches;
    // 即使正在地图搜索框中输入，也要及时移除撤回的敌军并采用加速后的时间。
    if ((data.marches || Array.isArray(data.incoming) || Array.isArray(data.returningEnemyMarches)) && G.WorldMap && G.WorldMap.refreshMarches) G.WorldMap.refreshMarches();
    if (data.cityState) {
      G.state.cityState = data.cityState;
    }
    // 0 也须覆盖旧值，避免退出或到期后客户端继续显示保护。
    if (data.newbieProtectionUntil !== undefined) {
      var previousProtection = (G.state.player || {}).newbieProtectionUntil;
      if (G.state.player) G.state.player.newbieProtectionUntil = data.newbieProtectionUntil;
      if (G.state.cityState) G.state.cityState.newbieProtectionUntil = data.newbieProtectionUntil;
      if (previousProtection !== data.newbieProtectionUntil && G.WorldMap && G.WorldMap.invalidate) G.WorldMap.invalidate();
    }
    if (data.officers) {
      G.state.officers = data.officers;
    }
    if (data.research !== undefined) {
      if (G.Core && G.Core.state) G.Core.state.research = data.research;
      if (G.state) G.state.research = data.research;
    }
    if (data.tech) {
      if (G.Core && G.Core.state) G.Core.state.tech = data.tech;
      if (G.state) G.state.tech = data.tech;
    }
    // 同一推送中的科技、设施与兵种资格全部落定后再刷新，避免用旧科技渲染制备按钮。
    // 单独到达的资格快照也需更新，抽屉刷新会保留未保存的配装草稿。
    if ((data.unitEquipment || data.unitUnlocks || data.tech) && G.UnitEquipment) {
      G.UnitEquipment.refresh();
    }
    if (data.completedResearch && data.completedResearch.length > 0) {
      for (var ri = 0; ri < data.completedResearch.length; ri++) {
        G.toast(data.completedResearch[ri]);
      }
      if (G.Tech && G.Core && G.Core.route === 'tech') {
        G.Core.refreshContent();
      }
      if (G.Onboarding && G.Onboarding.refresh) G.Onboarding.refresh();
    }
    // 本 tick 内刚刚完成的建筑(后端在建筑倒计时归零时通过 tick 推送)
    if (data.completedBuilds && G.Task && G.Task.Quests && G.Task.Quests.onEvent) {
      for (var bi = 0; bi < data.completedBuilds.length; bi++) {
        // 每日任务的"升级建筑"任务要求等建筑真完成才 +1,
        // 避免"刚点升级就提示可领奖"的体验割裂。
        G.Task.Quests.onEvent('BUILD_DONE', data.completedBuilds[bi]);
      }
    }
    if (data.completedBuilds && data.completedBuilds.length && G.Onboarding && G.Onboarding.refresh) {
      G.Onboarding.refresh();
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
    if (data && (data.event === 'arrived' || data.event === 'scoutComplete')) {
      if (G.World && G.World.triggerBattleAlert) G.World.triggerBattleAlert(data);
    }
    // Show toast notification
    var messages = {
      arrived: '部队已到达目标: ' + (data.targetName || ''),
      scoutComplete: '侦查完成，情报已送达',
      gatherComplete: '采集完成，部队已自动按原路线返城，资源抵达后入库',
      gatherStopped: '采集已终止，部队原地待命，请下达回城命令',
      shieldBlocked: '目标城市护盾生效，部队正在返程',
      interceptCancelled: '拦截目标已离开，部队正在返城',
      interceptionRescheduled: '敌军行军时间变化，拦截交火时间已更新',
      returning: '部队已开始按原路线返城，抵达后部队与资源入库',
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
      G.state.reports.unshift(G.Battle && G.Battle.summarizeLiveReport ? G.Battle.summarizeLiveReport(data) : data);
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

  // 城市传送后使坐标缓存失效，同账号的其他标签页同步新的城市位置。
  G.WS.on('city_teleport', function (data) {
    if (!data) return;
    if (G.WorldView) G.WorldView.invalidate();
    if (G.WorldMap) G.WorldMap.invalidate();
    var player = (G.Core && G.Core.state && G.Core.state.player) || {};
    if (String(data.ownerId) !== String(player.id) || (G.CityTeleport && G.CityTeleport.isBusy())) return;
    if (G.CityTeleport) G.CityTeleport.close();
    if (G.API && G.API.client) G.API.client.invalidateCityRequests();
    refreshState();
  });

  // 城权变更后清除旧城市请求上下文，避免失守城的X-City-Id使后续状态请求一直失败。
  G.WS.on('city_ownership', function (data) {
    if (!data) return;
    if (G.API && G.API.client) {
      G.API.client.invalidateCityRequests();
      if (!data.gained) G.API.client.cityId = data.activeCityId || null;
    }
    if (G.WorldView) G.WorldView.invalidate();
    if (G.Cities && G.Cities.close) G.Cities.close();
    G.toast(data.gained ? '敌方城市已接管，可在城市管理中切换' : '城市失守，已为你保留可继续发展的主城');
    refreshState();
  });

  // battle handler - battle report
  G.WS.on('battle', function (data) {
    if (!data) return;
    if (G.World && G.World.triggerBattleAlert) G.World.triggerBattleAlert(data);
    if (data.win && G.state && G.state.world && Array.isArray(G.state.world.marches)) {
      var toX = data.toX != null ? data.toX : data.targetX;
      var toY = data.toY != null ? data.toY : data.targetY;
      G.state.world.marches.forEach(function (m) {
        if (m && m.returning) {
          if ((toX != null && m.originX === toX && m.originY === toY) || (data.marchId && String(m.id) === String(data.marchId))) {
            m.win = true;
            m.battleWon = true;
          }
        }
      });
    }
    if (data.action === 'intercept_scout') {
      var defending = data.perspective === 'defender';
      G.toast(defending ? (data.win ? '拦截成功，拦截战报已送达' : '拦截失败，敌军突破拦截后继续行进')
        : (data.win ? '突围成功，部队沿原路线继续行进' : '突围失败，拦截战报已送达'));
    } else if (data.win) {
      var plunder = data.plunder || {};
      var names = G.Constants.battleResourceNames;
      var resources = Object.keys(names).filter(function (key) {
        return Number(plunder[key]) > 0;
      }).map(function (key) {
        return names[key] + ' ' + G.fmt(plunder[key]);
      });
      var message = data.wildConquered ? '战斗胜利，野地已占领' : '战斗胜利';
      if (data.cityConquered && data.targetType === 'player') message += '，城市已接管';
      if (data.capturedOfficerId != null) message += '，俘虏敌将：' + (data.capturedOfficerName || '敌方指挥官');
      if (resources.length) message += '，获得资源：' + resources.join('、');
      G.toast(message);
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
        G.state.reports.unshift(G.Battle && G.Battle.summarizeLiveReport ? G.Battle.summarizeLiveReport(data) : data);
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
    if (!data) return;
    if (data.intelligenceType === 'field_interception') {
      // 先同步野外急报，避免等待全量查询时漏掉短途截击；终结事件立即移除旧卡片。
      var world = G.state && G.state.world;
      if (world) {
        var previous = (world.incoming || []).find(function (item) { return String(item.id) === String(data.id); });
        world.incoming = (world.incoming || []).filter(function (item) { return String(item.id) !== String(data.id); });
        if (data.event !== 'cancelled' && data.event !== 'resolved') {
          world.incoming.push(Object.assign({}, data, { expanded: previous ? !!previous.expanded : true }));
        }
      }
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
      if (G.WorldMap && G.WorldMap.refreshMarches) G.WorldMap.refreshMarches();
      if (!data.event || data.event === 'started') G.toast('野外急报：行军 #' + data.interceptedMarchId + ' 遭遇敌军拦截，请查看军情');
      else if (data.event === 'cancelled') G.toast('敌方拦截已取消，部队继续执行原行军');
      refreshState();
      return;
    }
    if (!data.event || data.event === 'started') {
      G.toast('警告: 敌军来袭! 来自' + (data.fromName || '未知'));
    } else if (data.event === 'cancelled') {
      G.toast((data.fromName || '敌军') + ' 已撤回行军');
    } else if (data.event === 'shieldBlocked') {
      G.toast('护盾已拦截来袭，敌军正在原路返回');
    }
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
    showWsBanner('连接已断开，计时暂停，正在重连...', 'var(--danger)');
  });

  // 首次连接保留登录请求；断线期间可能失去当前城市，重连时重新读取账号当前城市。
  G.WS.on('connected', function (data) {
    if (data && data.reconnected && G.API && G.API.client) {
      G.API.client.invalidateCityRequests();
      G.API.client.cityId = null;
    }
    if (G.PrivateChat) G.PrivateChat.refresh();
    if (G.Friends && G.Friends.refresh) G.Friends.refresh();
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

  G.WS.on('private_chat', function (data) {
    if (G.PrivateChat) G.PrivateChat.receive(data);
  });

  G.WS.on('guild_chat', function (data) {
    if (G.Chat && G.Chat.receiveGuild) G.Chat.receiveGuild(data);
  });

  G.WS.on('friend_request', function (data) {
    if (G.Friends && G.Friends.refresh) G.Friends.refresh();
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
