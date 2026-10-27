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
    if (G.Protection && !G.Protection.canRequest()) return null;
    if (state && typeof state === 'object') {
      if (G.GameTime) G.GameTime.sync(state.serverTime);
      // 兼容后端字段：username -> name
      if (state.player && state.player.username != null && state.player.name == null) {
        state.player.name = state.player.username;
      }
      var oldState = G.state || (G.Core && G.Core.state) || null;
      // 战报列表按需加载；同一账号的普通状态刷新不能清掉刚收到的战报。
      if (oldState && oldState.player && state.player && oldState.player.id === state.player.id) {
        if (!Array.isArray(state.reports) && Array.isArray(oldState.reports)) state.reports = oldState.reports;
      }
      var cityChanged = oldState && oldState.player && state.player &&
        (oldState.player.id !== state.player.id || oldState.player.activeCityId !== state.player.activeCityId);
      if (oldState && oldState.player && state.player && oldState.player.id === state.player.id && oldState.world && state.world &&
          oldState.world.regionalRadar && Number(oldState.world.regionalRadar.revision) > Number((state.world.regionalRadar || {}).revision || 0)) {
        state.world.regionalRadar = oldState.world.regionalRadar;
      }
      client.cityId = state.player && state.player.activeCityId || null;
      if (oldState && !cityChanged) {
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

    /** 兵种配装与制备接口；保留城市上下文，过期响应不能覆盖已切换的城市。 */
    unitEquipmentRequest: function (path, body) {
      var token = client.getToken(), city = client.cityId;
      return client.post('/game/unit-equipment' + path, body).then(function (data) {
        if (client.getToken() === token && client.cityId === city && data && data.state) applyState(data.state);
        return data;
      });
    },
    saveUnitLoadout: function (unit, slots, version) { return this.unitEquipmentRequest('/' + encodeURIComponent(unit) + '/loadout', { slots: slots, version: version }); },
    manufactureUnitEquipment: function (item) { return this.unitEquipmentRequest('/manufacture', { item: item }); },
    cancelUnitEquipmentOrder: function (id) { return this.unitEquipmentRequest('/orders/' + id + '/cancel', {}); },

    // ===== Token / 登录态（委托给 client）=====
    getToken: function () { return client.getToken(); },
    retentionContext: function () { return client.serverId + ':' + client.getToken(); },
    setToken: function (token, username) { return client.setToken(token, username); },
    clearToken: function () { return client.clearToken(); },
    isLoggedIn: function () { return client.isLoggedIn(); },
    getUsername: function () { return client.getUsername(); },

    // 工具：用最新状态整体替换 G.state
    applyState: applyState,

    getPrerequisites: function () {
      return client.get('/game/prerequisites');
    },

    // ==================== 鉴权 ====================

    register: function (username, password, agreementVersion) {
      return client.post('/auth/register', { username: username, password: password, agreementVersion: agreementVersion, serverId: client.serverId })
        .then(function (data) {
          if (data.token) client.setToken(data.token, data.username);
          client.invalidateStateCache();
          return data; // 待注销登录仅返回恢复凭据，不建立游戏会话。
        });
    },

    login: function (username, password) {
      return client.post('/auth/login', { username: username, password: password, serverId: client.serverId })
        .then(function (data) {
          if (data.token) client.setToken(data.token, data.username);
          client.invalidateStateCache();
          return data; // 待注销登录仅返回恢复凭据，不建立游戏会话。
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
      // 结束服务端租约，失败时仍在客户端退出，租约超时会自动失效。
      if (G.Protection) client.post('/play-sessions/end', {}, { silent: true, retry: 0 }).catch(function () {});
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

    deletionPreview: function () { return client.get('/auth/deletion-preview', { timeout: 15000 }); },

    /** 注销写请求不重试；结果不明时由只读状态查询确认。 */
    disableAccount: function (password, confirm, requestId) {
      return client.post('/auth/disable', { password: password, confirm: confirm, requestId: requestId },
        { timeout: 15000, preserveSession: true });
    },

    deletionStatus: function (username, password) {
      return client.post('/auth/deletion-status', { username: username, password: password, serverId: client.serverId },
        { timeout: 15000, preserveSession: true });
    },

    recoverAccount: function (recoveryToken) {
      return client.post('/auth/recover', { recoveryToken: recoveryToken, confirm: true },
        { timeout: 15000, preserveSession: true }).then(function (data) {
          client.setToken(data.token, data.username);
          return data;
        });
    },

    // ==================== 游戏状态 ====================

    getWorldView: function (x, y, radius) {
      return client.get('/game/world/view?x=' + encodeURIComponent(x)
        + '&y=' + encodeURIComponent(y) + '&radius=' + encodeURIComponent(radius), { silent: true });
    },

    getMapChunk: function (cx, cy) {
      return client.get('/game/world/map/chunk?cx=' + cx + '&cy=' + cy, { silent: true, noCache: true, retry: 0, timeout: 10000 });
    },

    getMapTarget: function (kind, id) {
      return client.get('/game/world/map/target?kind=' + encodeURIComponent(kind) + '&id=' + encodeURIComponent(id), { silent: true, noCache: true, timeout: 10000 });
    },

    getGameState: function (force) {
      if (G.Protection && !G.Protection.canRequest()) return Promise.reject(G.Protection.error());
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
    getReports: function (limit, cursor) {
      var n = Math.max(1, Math.min(100, parseInt(limit, 10) || 50));
      var url = '/game/reports?paged=true&limit=' + n;
      if (cursor) url += '&cursor=' + encodeURIComponent(cursor);
      return client.get(url);
    },

    /** 按坐标读取自己的有效侦察快照，不依赖战报分页或浏览器缓存。 */
    getScoutIntelligence: function (x, y) {
      return client.get('/game/reports/scout-intelligence?x=' + encodeURIComponent(x) + '&y=' + encodeURIComponent(y), { noCache: true, silent: true });
    },

    getReportDetail: function (id) {
      return client.get('/game/reports/' + encodeURIComponent(id), { noCache: true });
    },
    starReport: function (id) { return client.post('/game/reports/' + encodeURIComponent(id) + '/star', {}); },
    unstarReport: function (id) { return client.delete('/game/reports/' + encodeURIComponent(id) + '/star', { silent: true }); },

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

    setDisplayName: function (name) {
      return client.post('/game/settings/display-name', { name: name })
        .then(extractState).then(applyState);
    },

    setAvatar: function (avatar) {
      return client.post('/game/settings/avatar', { avatar: avatar })
        .then(extractState).then(applyState);
    },

    setHomeModuleOrder: function (order) {
      return client.post('/game/settings/home-modules', { order: order });
    },

    // ==================== 军团 ====================

    getMyGuild: function () { return client.get('/game/guild/mine', { noCache: true }); },
    getGuilds: function () { return client.get('/game/guild/list', { noCache: true }); },
    createGuild: function (name, icon) { return client.post('/game/guild', { name: name, icon: icon }); },
    applyGuild: function (guildId) { return client.post('/game/guild/' + guildId + '/apply', {}); },
    reviewGuildApplication: function (id, approved) { return client.post('/game/guild/applications/' + id + '/review', { approved: approved }); },
    updateGuildNotice: function (notice) { return client.post('/game/guild/notice', { notice: notice }); },
    updateGuildSettings: function (name, icon) { return client.post('/game/guild/settings', { name: name, icon: icon }); },
    updateGuildRelation: function (guildId, status) { return client.post('/game/guild/relations/' + guildId, { status: status }); },
    updateGuildRole: function (playerId, role) { return client.post('/game/guild/members/' + playerId + '/role', { role: role }); },
    removeGuildMember: function (playerId) { return client.post('/game/guild/members/' + playerId + '/remove', {}); },
    transferGuildLeadership: function (playerId) { return client.post('/game/guild/members/' + playerId + '/transfer', {}); },
    leaveGuild: function () { return client.post('/game/guild/leave', {}); },
    getGuildChatHistory: function () { return client.get('/game/guild/chat/history', { noCache: true }); },
    sendGuildChat: function (content) { return client.post('/game/guild/chat/send', { content: content }); },
    shareIncomingReport: function (id) { return client.post('/game/incoming/' + encodeURIComponent(id) + '/share', {}); },
    getRadar: function (beforeId) { return client.get('/game/world/radar' + (beforeId ? '?beforeId=' + encodeURIComponent(beforeId) : ''), { noCache: true }); },
    setRadarEnabled: function (cityId, enabled) { return client.post('/game/world/radar/setting', { cityId: cityId, enabled: enabled }); },
    readRadarContact: function (id) { return client.post('/game/world/radar/' + encodeURIComponent(id) + '/read', {}); },
    sharedIncomingReport: function (token) { return client.get('/game/incoming/shared/' + encodeURIComponent(token), { noCache: true }); },
    // ==================== 建筑 ====================

    buildUpgrade: function (building, slot) {
      return client.post('/game/build/upgrade', { building: building, slot: slot })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    buildDismantle: function (building, slot) {
      return client.post('/game/build/dismantle', { building: building, slot: slot })
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

    buildFreeSpeedUp: function (queueId) {
      return client.post('/game/build/free-speedup', { queueId: queueId })
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

    getBattleDefaults: function () {
      return client.get('/game/army/battle-defaults', { noCache: true });
    },

    saveBattleDefaults: function (defaults) {
      return client.post('/game/army/battle-defaults', defaults);
    },

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

    getWounded: function () {
      return client.get('/game/army/wounded', { noCache: true });
    },

    getPrisoners: function () {
      return client.get('/game/prisoners', { noCache: true, silent: true });
    },

    /** 服务端重放同一操作凭证；异城或异账号响应不能覆盖当前页面状态。 */
    prisonerOperation: function (action, unit, count, operationKey) {
      var token = client.getToken(), cityId = client.cityId;
      return client.post('/game/prisoners/' + action, { unit: unit, count: count, operationKey: operationKey }, { timeout: 15000 })
        .then(function (data) {
          if (client.getToken() === token && client.cityId === cityId && data && data.success && data.state) applyState(data.state);
          return data;
        });
    },

    /** 治疗响应只更新发起治疗的账号和城市，切城后的页面保留自己的状态。 */
    healWounded: function (id, count, currency) {
      var token = client.getToken(), cityId = client.cityId;
      return client.post('/game/army/wounded/' + id + '/heal', { count: count, currency: currency })
        .then(function (data) {
          if (client.getToken() === token && client.cityId === cityId && data && data.success && data.state) applyState(data.state);
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

    freeArmySpeedUp: function (queueId) {
      return client.post('/game/army/queue/' + queueId + '/free-speedup', {})
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    // ==================== 科技 ====================

    techUpgrade: function (tech) {
      return client.post('/game/tech/upgrade', { tech: tech })
        .then(extractState).then(applyState);
    },

    techCancel: function (queueId) {
      return client.post('/game/tech/cancel', { queueId: queueId })
        .then(extractState).then(applyState);
    },

    techSpeedUp: function (itemId, queueId, count) {
      return client.post('/game/tech/speedup', { itemId: itemId, queueId: queueId, count: count })
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
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
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

    upgradeSkill: function (officerId, skillIdx, itemId) {
      return client.post('/game/officer/upgrade-skill', { officerId: officerId, skillIdx: skillIdx, itemId: itemId })
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

    renameOfficer: function (officerId, name) {
      return client.post('/game/officer/rename', { officerId: officerId, name: name })
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

    /** 十二件整套操作及整备使用服务端事务，返回状态统一应用。 */
    officerEquipment: function (action, payload) {
      return client.post('/game/officer-equipment/' + action, payload).then(function (data) {
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

    stopGatherMarch: function (marchId) {
      return client.post('/game/world/stop-gather', { marchId: marchId })
        .then(extractState).then(applyState);
    },

    cancelMarch: function (marchId) {
      return client.post('/game/world/cancel-march', { marchId: marchId }).then(function (data) {
        if (data && data.state) applyState(data.state);
        return data;
      });
    },

    /** 携带确认时的阶段和版本，消耗请求不自动重试。 */
    previewMarchOrder: function (marchId, itemKey) {
      return client.post('/game/world/order-preview', { marchId: marchId, itemKey: itemKey });
    },

    previewWarOrder: function (targetCityId) {
      return client.post('/game/world/war-order-preview', { targetCityId: targetCityId });
    },

    speedUpWar: function (targetCityId, confirmationToken) {
      var token = client.getToken(), city = client.cityId;
      return client.post('/game/world/speedup-war', { targetCityId: targetCityId, confirmationToken: confirmationToken }, { timeout: 15000 })
        .then(function (data) {
          if (client.getToken() === token && client.cityId === city && data && data.state) applyState(data.state);
          return data;
        });
    },

    speedUpMarch: function (marchId, returning, version, confirmationToken) {
      var token = client.getToken(), city = client.cityId;
      return client.post('/game/world/speedup-march', { marchId: marchId, returning: returning, version: version, confirmationToken: confirmationToken }, { timeout: 15000 })
        .then(function (data) {
          if (client.getToken() === token && client.cityId === city && data && data.state) applyState(data.state);
          return data;
        });
    },

    /** 对指定来袭行军使用一次计谋；确认时携带抵达时间，防止旧情报消耗道具。 */
    applyMarchStratagem: function (marchId, itemKey, expectedArriveAt, confirmationToken) {
      var token = client.getToken(), city = client.cityId;
      return client.post('/game/world/march-stratagem', {
        marchId: Number(marchId), itemKey: itemKey, expectedArriveAt: Number(expectedArriveAt), confirmationToken: confirmationToken
      }, { timeout: 15000 }).then(function (data) {
        if (client.getToken() === token && client.cityId === city && data && data.state) applyState(data.state);
        return data;
      });
    },

    /** 获取已到达部队的逐回合战场状态。 */
    getTacticalBattle: function (marchId) {
      return client.get('/game/world/battle?marchId=' + encodeURIComponent(marchId));
    },

    /** 提交本回合所有已选择兵种的战术命令，并取得下一回合战场快照。 */
    commandTacticalBattle: function (marchId, orders, round) {
      return client.post('/game/world/battle/command?marchId=' + encodeURIComponent(marchId), {
        orders: orders || {},
        round: Number.isFinite(Number(round)) ? Number(round) : null
      })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    declareWar: function (targetCityId, abandonNewbieProtection) {
      return client.post('/game/world/declare-war', { targetCityId: targetCityId, abandonNewbieProtection: abandonNewbieProtection === true })
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

    wildScout: function (wildTileId, abandonNewbieProtection) {
      return client.post('/game/wild/scout', { wildTileId: wildTileId, abandonNewbieProtection: abandonNewbieProtection === true })
        .then(extractState).then(applyState);
    },

    // dispatch: { army, commanderId, carryRes }（征服/采集需要派遣兵力）
    wildConquer: function (wildTileId, dispatch) {
      var body = { wildTileId: wildTileId };
      if (dispatch) {
        body.army = dispatch.army || {};
        body.commanderId = dispatch.commanderId || null;
        body.carryRes = dispatch.carryRes || {};
        body.conquerMode = dispatch.conquerMode || 'withdraw';
        body.abandonNewbieProtection = dispatch.abandonNewbieProtection === true;
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

    /** 获取当前大区的分页排行榜及当前玩家或军团的全榜名次。 */
    getLeaderboard: function (type, metric, page) {
      return client.get('/game/leaderboard?type=' + encodeURIComponent(type) +
        '&metric=' + encodeURIComponent(metric) + '&page=' + encodeURIComponent(page),
        { silent: true, noCache: true });
    },

    getRankInfo: function () {
      return client.get('/game/rank/info', { noCache: true });
    },

    startRankExam: function () {
      return client.post('/game/rank/exam/start', {});
    },

    submitRankExam: function (attemptId, answers) {
      return client.post('/game/rank/exam/submit', { attemptId: attemptId, answers: answers });
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

    claimDailyCheckin: function () {
      return client.post('/game/quest/daily-checkin', {})
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
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

    claimStage: function (stageId) {
      return client.post('/game/quest/claim-stage', { stageId: stageId })
        .then(function (data) {
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

    wildStartGather: function (wildTileId, gatherMode) {
      return client.post('/game/wild/gather', { wildTileId: wildTileId, gatherMode: gatherMode })
        .then(extractState).then(applyState);
    },

    wildHarvest: function (wildTileId) {
      return client.post('/game/wild/harvest', { wildTileId: wildTileId })
        .then(extractState).then(applyState);
    },

    wildRecall: function (wildTileId) {
      return client.post('/game/wild/recall', { wildTileId: wildTileId })
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
        if (data && data.success !== false && data.vip && G.Vip) G.Vip.applyStatus(data.vip);
        return data;
      });
    },

    shopBuy: function (itemId, count) {
      var body = { itemId: itemId };
      if (count && count > 1) body.count = count;
      return client.post('/game/shop/buy', body)
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    // ==================== VIP 特勤统帅部 ====================

    vipStatus: function () {
      return client.get('/game/vip/status');
    },

    vipClaimDaily: function (targetCitySlot) {
      return client.post('/game/vip/daily-salary/claim', { targetCitySlot: targetCitySlot })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    vipClaimPack: function (targetLevel, targetCitySlot) {
      return client.post('/game/vip/level-pack/claim', { targetLevel: targetLevel, targetCitySlot: targetCitySlot })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    vipClaimAllPacks: function (targetCitySlot) {
      return client.post('/game/vip/level-pack/claim-all', { targetCitySlot: targetCitySlot })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    vipClaimMilestone: function (milestoneCents, targetCitySlot) {
      return client.post('/game/vip/milestone/claim', { milestoneCents: milestoneCents, targetCitySlot: targetCitySlot })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    vipAppeaseAll: function (appeaseType) {
      return client.post('/game/vip/appease-all', { appeaseType: appeaseType })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    vipConvertSkillBook: function (targetSkillId, count) {
      return client.post('/game/vip/skill-convert', { targetSkillId: targetSkillId, count: count })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    vipToggleBadge: function (visible) {
      return client.post('/game/vip/badge-toggle', { visible: visible })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    vipSetAvatarFrame: function (frameId) {
      return client.post('/game/vip/avatar-frame', { frameId: frameId })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    vipSetCustomAvatarUrl: function (avatarUrl) {
      return client.post('/game/vip/custom-avatar-url', { avatarUrl: avatarUrl })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    vipUploadCustomAvatarDataUrl: function (dataUrl) {
      return client.post('/game/vip/custom-avatar-dataurl', { dataUrl: dataUrl })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    cancelCityShield: function (cityId) {
      return client.post('/game/cities/shield/cancel', { cityId: cityId })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    // ==================== 仓库 ====================

    depotUse: function (itemId, officerId, newName, skillId) {
      var body = { itemId: itemId };
      if (officerId != null) body.officerId = officerId;
      if (newName != null) body.newName = newName;
      if (skillId != null) body.skillId = skillId;
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
    shareBattleReport: function (id, channel, recipientId) {
      return client.post('/game/reports/' + encodeURIComponent(id) + '/share', { channel: channel, recipientId: recipientId });
    },
    sharedBattleReport: function (token) {
      return client.get('/game/reports/shared/' + encodeURIComponent(token), { noCache: true });
    },

    sendWorldChat: function (content) {
      return client.post('/game/chat/send', { content: content }, { silent: true });
    },

    privateChatPlayer: function (username) {
      return client.get('/game/chat/private/player?username=' + encodeURIComponent(username), { silent: true });
    },
    privateChatConversations: function () {
      return client.get('/game/chat/private/conversations', { silent: true });
    },
    privateChatOpen: function (peerId) {
      return client.post('/game/chat/private/' + peerId + '/open', {}, { silent: true });
    },
    privateChatDelete: function (peerId) {
      return client.delete('/game/chat/private/' + peerId, { silent: true });
    },
    privateChatClear: function (peerId) {
      return client.post('/game/chat/private/' + peerId + '/clear', {}, { silent: true });
    },
    privateChatHistory: function (peerId) {
      return client.get('/game/chat/private/' + peerId + '/history', { silent: true });
    },
    privateChatRead: function (peerId, throughId) {
      return client.post('/game/chat/private/' + peerId + '/read', { throughId: throughId }, { silent: true });
    },
    sendPrivateChat: function (peerId, content) {
      return client.post('/game/chat/private/send', { recipientId: peerId, content: content }, { silent: true });
    },

    // 好友、申请和隐私设置由服务端按登录账号保存。
    friends: function () { return client.get('/game/friends', { silent: true, noCache: true }); },
    friendSearch: function (query) { return client.get('/game/friends/search?query=' + encodeURIComponent(query), { silent: true }); },
    friendProfile: function (id) { return client.get('/game/friends/' + id, { silent: true }); },
    friendAdd: function (id) { return client.post('/game/friends/' + id, { groupId: 0 }, { silent: true }); },
    friendRemove: function (id) { return client.delete('/game/friends/' + id, { silent: true }); },
    friendMove: function (id, groupId) { return client.put('/game/friends/' + id + '/group', { groupId: groupId }, { silent: true }); },
    friendBlock: function (id, blocked) {
      return blocked ? client.post('/game/friends/' + id + '/block', {}, { silent: true }) : client.delete('/game/friends/' + id + '/block', { silent: true });
    },
    friendGroupSave: function (id, name) {
      return id ? client.put('/game/friends/groups/' + id, { name: name }, { silent: true }) : client.post('/game/friends/groups', { name: name }, { silent: true });
    },
    friendGroupDelete: function (id) { return client.delete('/game/friends/groups/' + id, { silent: true }); },
    friendRequestAccept: function (id) { return client.post('/game/friends/requests/' + id + '/accept', {}, { silent: true }); },
    friendRequestDismiss: function (id) { return client.delete('/game/friends/requests/' + id, { silent: true }); },
    friendSettings: function (settings) { return client.put('/game/friends/settings', settings, { silent: true }); },

    // ==================== 邮件 ====================

    // 拉取邮件列表: folder = inbox | outbox | system | unread
    listMail: function (folder, cursor, limit) {
      var query = '?folder=' + encodeURIComponent(folder || 'inbox') + '&limit=' + Math.max(1, Math.min(100, Number(limit) || 20));
      if (cursor) query += '&cursor=' + encodeURIComponent(cursor);
      return client.get('/game/mail/list' + query);
    },

    getMailDetail: function (mailId) {
      return client.get('/game/mail/' + encodeURIComponent(mailId), { noCache: true });
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
    },

    getSurrenderOffer: function (offerId) {
      return client.get('/game/surrender/' + encodeURIComponent(offerId), { silent: true, noCache: true });
    },

    offerSurrender: function (cityId) {
      return client.post('/game/surrender/offer', { cityId: cityId }).then(function (data) {
        if (data && data.state) applyState(data.state);
        return data;
      });
    },

    respondSurrender: function (offerId, decision) {
      return client.post('/game/surrender/respond', { offerId: offerId, decision: decision }).then(function (data) {
        if (data && data.state) applyState(data.state);
        return data;
      });
    },

    // ===== 交易所 / 市场 API =====
    getMarketOverview: function (resType, page, size) {
      var query = '?resourceType=' + encodeURIComponent(resType || 'all') +
                  '&page=' + (page || 0) +
                  '&size=' + (size || 20);
      return client.get('/game/market/overview' + query);
    },

    getMyMarketOrders: function () {
      return client.get('/game/market/my-orders');
    },

    createMarketOrder: function (resourceType, amount, pricePerUnit) {
      return client.post('/game/market/order/create', {
        resourceType: resourceType,
        amount: amount,
        pricePerUnit: pricePerUnit
      }).then(function (data) {
        if (data && data.state) applyState(data.state);
        return data;
      });
    },

    buyMarketOrder: function (orderId) {
      return client.post('/game/market/order/buy', { orderId: orderId })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    cancelMarketOrder: function (orderId) {
      return client.post('/game/market/order/cancel', { orderId: orderId })
        .then(function (data) {
          if (data && data.state) applyState(data.state);
          return data;
        });
    },

    systemExchange: function (fromRes, toRes, amount) {
      return client.post('/game/market/exchange', {
        fromRes: fromRes,
        toRes: toRes,
        amount: amount
      }).then(function (data) {
        if (data && data.state) applyState(data.state);
        return data;
      });
    }
  };

  // 401 时跳转登录页（运行时 Core 已就绪）
  client.onUnauthorized = function () {
    if (G.Account) { G.Account.endSession(); return; }
    if (G.WS) G.WS.disconnect();
    G.state = null;
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
