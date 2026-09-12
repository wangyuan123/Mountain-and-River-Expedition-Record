/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  // 创建统一请求处理器单例（不传参数，由 ApiClient 自动检测跨端口）
  var client = new G.ApiClient();

  // 从后端响应中提取游戏状态：
  // - GET /api/game/state 响应体本身就是状态
  // - 其余游戏操作响应形如 { ..., state: {...} }
  function extractState(data) {
    if (data && typeof data === 'object' && Object.prototype.hasOwnProperty.call(data, 'state')) {
      return data.state;
    }
    return data;
  }

  // 将后端返回的状态整体替换到 G.state / Core.state
  function applyState(state) {
    if (state && typeof state === 'object') {
      // 兼容后端字段：username -> name
      if (state.player && state.player.username != null && state.player.name == null) {
        state.player.name = state.player.username;
      }
      var oldState = G.state || (G.Core && G.Core.state) || null;
      // 战报列表按需加载；同一账号的普通状态刷新不能清掉刚收到的战报。
      if (oldState && oldState.player && state.player && oldState.player.id === state.player.id) {
        if (!Array.isArray(state.reports) && Array.isArray(oldState.reports)) state.reports = oldState.reports;
      }
      if (oldState) {
        // 保留前端挂在 state 顶层上的临时 UI 状态（如 _detailOfficerId, _depotSelectOfficer, _expandedBuildings 等），
        // 避免后端返回的全新 state 对象把 _xxx 临时字段覆盖丢失
        for (var sk in oldState) {
          if (Object.prototype.hasOwnProperty.call(oldState, sk) &&
              sk.charAt(0) === '_' &&
              !Object.prototype.hasOwnProperty.call(state, sk)) {
            state[sk] = oldState[sk];
          }
        }
        // 保留前端挂在 world 上的临时 UI 状态（坐标搜索框、地图视角等）
        var oldWorld = oldState.world || null;
        var newWorld = state.world || null;
        if (oldWorld && newWorld && oldWorld !== newWorld) {
          for (var wk in oldWorld) {
            if (Object.prototype.hasOwnProperty.call(oldWorld, wk) &&
                wk.charAt(0) === '_' &&
                !Object.prototype.hasOwnProperty.call(newWorld, wk)) {
              newWorld[wk] = oldWorld[wk];
            }
          }
        }
        // 保留前端挂在 wilds 上的临时 UI 状态
        var oldWilds = oldState.wilds || null;
        var newWilds = state.wilds || null;
        if (oldWilds && newWilds && oldWilds !== newWilds) {
          for (var xk in oldWilds) {
            if (Object.prototype.hasOwnProperty.call(oldWilds, xk) &&
                xk.charAt(0) === '_' &&
                !Object.prototype.hasOwnProperty.call(newWilds, xk)) {
              newWilds[xk] = oldWilds[xk];
            }
          }
        }
      }
      G.state = state;
      if (G.Core) G.Core.state = G.state;
    }
    return state;
  }

  var API = {
    // 保留原有 localStorage 键名（向后兼容）
    tokenKey: client.tokenKey,
    userKey: client.userKey,
    // 暴露底层客户端以便扩展
    client: client,

    // ===== Token / 登录态（委托给 client）=====
    getToken: function () { return client.getToken(); },
    setToken: function (token, username) { return client.setToken(token, username); },
    clearToken: function () { return client.clearToken(); },
    isLoggedIn: function () { return client.isLoggedIn(); },
    getUsername: function () { return client.getUsername(); },

    // 工具：用最新状态整体替换 G.state
    applyState: applyState,

    // ==================== 鉴权 ====================

    register: function (username, password) {
      return client.post('/auth/register', { username: username, password: password })
        .then(function (data) {
          client.setToken(data.token, data.username);
          client.invalidateStateCache();
          return data; // { token, username, playerId }
        });
    },

    login: function (username, password) {
      return client.post('/auth/login', { username: username, password: password })
        .then(function (data) {
          client.setToken(data.token, data.username);
          client.invalidateStateCache();
          return data; // { token, username, playerId }
        });
    },

    createGuest: function () {
      return client.post('/auth/guest', {})
        .then(function (data) {
          client.setToken(data.token, data.username);
          client.invalidateStateCache();
          return data; // { token, username, playerId }
        });
    },

    logout: function () {
      client.clearToken();
      client.invalidateStateCache();
    },

    getUserInfo: function () {
      // { playerId, username, faction, cityName }
      return client.get('/auth/me');
    },

    /** 将“跳过新手引导”持久化到当前账号。 */
    dismissTutorial: function () {
      return client.post('/auth/tutorial/dismiss', {});
    }, 

    /**
     * 注销当前账号。需要后端校验当前密码 + confirm 文案。
     * 成功后：Token 立即失效，本地登录态被清空，页面回到登录入口。
     */
    disableAccount: function (password, confirm) {
      return client.post('/auth/disable', { password: password, confirm: confirm })
        .then(function (data) {
          // 注销后立即吊销本地会话
          client.clearToken();
          client.invalidateStateCache();
          return data;
        });
    },

    // ==================== 游戏状态 ====================

    getWorldView: function (x, y, radius) {
      return client.get('/game/world/view?x=' + encodeURIComponent(x)
        + '&y=' + encodeURIComponent(y) + '&radius=' + encodeURIComponent(radius), { silent: true });
    },

    getGameState: function (force) {
      if (!force) {
        var cached = client.getCachedState();
        if (cached) return Promise.resolve(cached);
      }
      return client.get('/game/state', { noCache: !!force })
        .then(function (data) {
          client.setStateCache(data);
          return data;
        });
    },

    // 战报列表（侦查和战斗报告均由后端持久化）
    // 注意: client.get 第二个参数是 options 不是 params, query string 必须手拼
    getReports: function (limit) {
      var n = Math.max(1, Math.min(200, parseInt(limit, 10) || 50));
      return client.get('/game/reports?limit=' + n);
    },

    getUnreadReports: function () {
      return client.get('/game/reports/unread');
    },

    /** 标记单条战报已读。返回后端最新未读数。 */
    markReportRead: function (id) {
      return client.post('/game/reports/' + encodeURIComponent(id) + '/read', {})
        .then(function (data) { return data; });
    },

    /** 一键全部战报标记已读。 */
    markAllReportsRead: function () {
      return client.post('/game/reports/read-all', {})
        .then(function (data) { return data; });
    },

    setTax: function (tax) {
      return client.post('/game/settings/tax', { tax: tax })
        .then(extractState).then(applyState);
    },

    appease: function (type) {
      return client.post('/game/city/appease', { type: type })
        .then(function (res) {
          if (res && res.state) {
            applyState(res.state);
          }
          return res;
        });
    },

    resetGame: function () {
      return client.post('/game/settings/reset', { confirm: true })
        .then(extractState).then(applyState);
    },

    setCityName: function (cityName) {
      return client.post('/game/settings/city-name', { cityName: cityName })
        .then(extractState).then(applyState);
    },

    setAvatar: function (avatar) {
      return client.post('/game/settings/avatar', { avatar: avatar })
        .then(extractState).then(applyState);
    },

    // ==================== 军团 ====================

    getMyGuild: function () { return client.get('/game/guild/mine', { noCache: true }); },
    getGuilds: function () { return client.get('/game/guild/list', { noCache: true }); },
    createGuild: function (name) { return client.post('/game/guild', { name: name }); },
    applyGuild: function (guildId) { return client.post('/game/guild/' + guildId + '/apply', {}); },
    reviewGuildApplication: function (id, approved) { return client.post('/game/guild/applications/' + id + '/review', { approved: approved }); },
    updateGuildNotice: function (notice) { return client.post('/game/guild/notice', { notice: notice }); },
    updateGuildSettings: function (name, icon) { return client.post('/game/guild/settings', { name: name, icon: icon }); },
    updateGuildRole: function (playerId, role) { return client.post('/game/guild/members/' + playerId + '/role', { role: role }); },
    removeGuildMember: function (playerId) { return client.post('/game/guild/members/' + playerId + '/remove', {}); },
    leaveGuild: function () { return client.post('/game/guild/leave', {}); },
    // ==================== 建筑 ====================

    buildUpgrade: function (building, slot) {
      return client.post('/game/build/upgrade', { building: building, slot: slot })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    buildCancel: function (building, slot) {
      return client.post('/game/build/cancel', { building: building, slot: slot })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    buildSpeedUp: function (itemId, queueId, count) {
      var payload = { itemId: itemId || 'speedUp10m' };
      if (queueId != null) payload.queueId = queueId;
      if (count != null && count > 0) payload.count = count;
      return client.post('/game/build/speedup', payload)
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    // ==================== 军队 ====================

    recruit: function (unit, count) {
      return client.post('/game/army/recruit', { unit: unit, count: count })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    dismiss: function (unit, count) {
      return client.post('/game/army/dismiss', { unit: unit, count: count })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    armyQueue: function () {
      return client.get('/game/army/queue', { noCache: true });
    },

    cancelArmyQueue: function (queueId) {
      return client.post('/game/army/queue/' + queueId + '/cancel', {})
        .then(extractState).then(applyState);
    },

    armyQueueSpeedUp: function (queueId, itemId, count) {
      var payload = { itemId: itemId };
      if (count != null && count > 0) payload.count = count;
      return client.post('/game/army/queue/' + queueId + '/speedup', payload)
        .then(extractState).then(applyState);
    },

    // ==================== 科技 ====================

    techUpgrade: function (tech) {
      return client.post('/game/tech/upgrade', { tech: tech })
        .then(extractState).then(applyState);
    },

    // ==================== 军官 ====================

    refreshAcademy: function () {
      return client.post('/game/officer/refresh-academy', {})
        .then(extractState).then(applyState);
    },

    recruitOfficer: function (officerIdx) {
      return client.post('/game/officer/recruit', { officerIdx: officerIdx })
        .then(extractState).then(applyState);
    },

    appointOfficer: function (officerId, role) {
      return client.post('/game/officer/appoint', { officerId: officerId, role: role })
        .then(extractState).then(applyState);
    },

    dismissOfficer: function (officerId) {
      return client.post('/game/officer/dismiss', { officerId: officerId })
        .then(extractState).then(applyState);
    },

    rewardOfficer: function (officerId) {
      return client.post('/game/officer/reward', { officerId: officerId })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    learnSkill: function (officerId) {
      return client.post('/game/officer/learn-skill', { officerId: officerId })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    abandonSkill: function (officerId, skillIdx) {
      return client.post('/game/officer/abandon-skill', { officerId: officerId, skillIdx: skillIdx })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    useExpBook: function (officerId, itemId, count) {
      var payload = { officerId: officerId };
      if (itemId) payload.itemId = itemId;
      if (count && count > 0) payload.count = count;
      return client.post('/game/officer/use-exp-book', payload)
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    officerLevelUp: function (officerId, all) {
      return client.post('/game/officer/level-up', { officerId: officerId, all: !!all })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    assignOfficerAttr: function (officerId, attr, points) {
      return client.post('/game/officer/assign-attr', { officerId: officerId, attr: attr, points: points })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    washOfficer: function (officerId) {
      return client.post('/game/officer/wash', { officerId: officerId })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    equipOfficer: function (officerId, itemId) {
      return client.post('/game/officer/equip', { officerId: officerId, itemId: itemId })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    unequipOfficer: function (officerId, itemId) {
      return client.post('/game/officer/unequip', { officerId: officerId, itemId: itemId })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    officerAttributes: function (officerId) {
      return client.post('/game/officer/attributes', { officerId: officerId });
    },

    logout: function () {
      return client.post('/auth/logout', {});
    },

    // ==================== 城防 ====================

    buildFort: function (fort, count) {
      return client.post('/game/fort/build', { fort: fort, count: count })
        .then(extractState).then(applyState);
    },

    dismantleFort: function (fort, count) {
      return client.post('/game/fort/dismantle', { fort: fort, count: count })
        .then(extractState).then(applyState);
    },

    // ==================== 世界 ====================

    worldMove: function (direction) {
      return client.post('/game/world/move', { direction: direction })
        .then(extractState).then(applyState);
    },

    worldScan: function () {
      return client.post('/game/world/scan', {})
        .then(extractState).then(applyState);
    },

    worldDispatch: function (dispatchRequest) {
      return client.post('/game/world/dispatch', dispatchRequest)
        .then(extractState).then(applyState);
    },

    cancelMarch: function (marchId) {
      return client.post('/game/world/cancel-march', { marchId: marchId })
        .then(extractState).then(applyState);
    },

    declareWar: function (targetCityId) {
      return client.post('/game/world/declare-war', { targetCityId: targetCityId })
        .then(extractState).then(applyState);
    },

    getNearby: function () {
      // NearbyInfo
      return client.get('/game/world/nearby');
    },

    getCoordinateTarget: function (x, y) {
      return client.get('/game/world/coordinate?x=' + encodeURIComponent(x) + '&y=' + encodeURIComponent(y));
    },

    getWildTiles: function () {
      // WildTile[]
      return client.get('/game/world/wild-tiles');
    },

    // ==================== 野地 ====================

    wildScout: function (wildTileId) {
      return client.post('/game/wild/scout', { wildTileId: wildTileId })
        .then(extractState).then(applyState);
    },

    // dispatch: { army, commanderId, carryRes }（征服/采集需要派遣兵力）
    wildConquer: function (wildTileId, dispatch) {
      var body = { wildTileId: wildTileId };
      if (dispatch) {
        body.army = dispatch.army || {};
        body.commanderId = dispatch.commanderId || null;
        body.carryRes = dispatch.carryRes || {};
      }
      return client.post('/game/wild/conquer', body)
        .then(extractState).then(applyState);
    },

    wildGather: function (wildTileId, dispatch) {
      var body = { wildTileId: wildTileId };
      if (dispatch) {
        body.army = dispatch.army || {};
        body.commanderId = dispatch.commanderId || null;
        body.carryRes = dispatch.carryRes || {};
      }
      return client.post('/game/wild/gather', body)
        .then(extractState).then(applyState);
    },

    // ==================== 主线任务 + 新手引导 + 军衔任务 ====================

    getRankInfo: function () {
      return client.get('/game/rank/info');
    },

    promoteRank: function () {
      return client.post('/game/rank/promote', {})
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    getQuestList: function () {
      return client.get('/game/quest/list');
    },

    claimQuest: function (questId) {
      return client.post('/game/quest/claim', { questId: questId })
        .then(function (data) {
          // 后端 claim 已在响应里返回最新 state (含 items/resources),
          // 前端这里要把它合并到 G.state, 否则仓库里看不到新增的技能书等。
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    getGuide: function () {
      return client.get('/game/quest/guide');
    },

    advanceGuide: function () {
      return client.post('/game/quest/guide/advance', {})
        .then(function (data) {
          // 引导步骤也可能在 advance 时派发奖励, 后端会在响应里附 state,
          // 前端同步本地 state, 让仓库/资源立即反映。
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    skipGuide: function () {
      return client.post('/game/quest/guide/skip', {});
    },

    wildAbandon: function (wildTileId) {
      return client.post('/game/wild/abandon', { wildTileId: wildTileId })
        .then(extractState).then(applyState);
    },

    // ==================== 商城 ====================

    shopRecharge: function (pkgId, rmb, diamond, channel) {
      return client.post('/game/shop/recharge', {
        pkgId: pkgId,
        rmb: rmb,
        diamond: diamond,
        channel: channel
      }).then(function (data) {
        if (data && data.state) applyState(data.state);
        return data;
      });
    },

    shopBuy: function (itemId) {
      return client.post('/game/shop/buy', { itemId: itemId })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    // ==================== 仓库 ====================

    depotUse: function (itemId, officerId, newName) {
      var body = { itemId: itemId };
      if (officerId != null) body.officerId = officerId;
      if (newName != null) body.newName = newName;
      return client.post('/game/depot/use', body)
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    // ==================== 世界频道 ====================

    worldChatHistory: function () {
      return client.get('/game/chat/history', { silent: true });
    },

    sendWorldChat: function (content) {
      return client.post('/game/chat/send', { content: content }, { silent: true });
    },

    // ==================== 邮件 ====================

    // 拉取邮件列表: folder = inbox | outbox | system | unread
    listMail: function (folder) {
      return client.get('/game/mail/list?folder=' + (folder || 'inbox'));
    },

    // 未读数 (用于导航栏红点)
    mailUnread: function () {
      return client.get('/game/mail/unread-count');
    },

    // 发邮件: to / subject / body / attach (可选)
    sendMail: function (to, subject, body, attach) {
      var req = { to: to, subject: subject, body: body || '' };
      if (attach && attach.length) req.attach = attach;
      return client.post('/game/mail/send', req)
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    // 标记已读
    markMailRead: function (mailId) {
      return client.post('/game/mail/' + mailId + '/read', {});
    },

    // 领取附件
    claimMailAttach: function (mailId) {
      return client.post('/game/mail/' + mailId + '/claim', {})
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    // 删除一封 (仅收件人)
    deleteMail: function (mailId) {
      return client.delete('/game/mail/' + mailId, { silent: true });
    }
  };

  // 401 时跳转登录页（运行时 Core 已就绪）
  client.onUnauthorized = function () {
    if (G.toast) G.toast('登录已过期，请重新登录');
    if (G.Core) {
      G.Core.state = null;
      G.Core.route = 'login';
      if (G.Core.render) G.Core.render();
    }
  };

  // 网络错误回调（额外提示）
  client.onNetworkError = function () {
    if (G.toast) G.toast('网络异常，请稍后重试');
  };

  G.API = API;
})(window.Game);
