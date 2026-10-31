/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';
  G.gameNow = G.gameNow || function () { return Date.now(); };

  var D = G.DATA;

  var SITE_INFO = G.Constants.siteInfo;

  function $(id) { return document.getElementById(id); }

  function fmt(n) {
    return Math.floor(n).toString();
  }

  function fmtTime(seconds) {
    return G.formatDuration(seconds);
  }

  var BEIJING_TIME_FORMATTER = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  });

  /**
   * 将时间固定格式化为北京时间，避免跟随玩家设备的本地时区变化。
   * @param {Date} value - 要展示的时间点
   * @returns {string} 中文年月日与 24 小时制时分秒
   */
  function formatBeijingTime(value) {
    var parts = BEIJING_TIME_FORMATTER.formatToParts(value || new Date());
    var fields = {};
    parts.forEach(function (part) { fields[part.type] = part.value; });
    return fields.year + '年' + fields.month + '月' + fields.day + '日 ' +
      fields.hour + ':' + fields.minute + ':' + fields.second;
  }

  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  function escapeHtml(str) {
    if (str == null) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  /**
   * 校验游戏内展示名；允许可见符号，并与服务端统一按 Unicode 字符数计数。
   * @param {string} raw - 玩家输入的原始名称
   * @param {number} maxChars - 允许的最大字符数
   * @param {string} label - 错误提示中的名称类型
   * @returns {string} 规范化后的展示名
   */
  function normalizeDisplayName(raw, maxChars, label) {
    // 只移除首尾普通空格，避免将换行或制表符当成无害空白吞掉。
    var name = String(raw == null ? '' : raw).normalize('NFC').replace(/^ +| +$/g, '');
    if (!name) throw new Error(label + '不能为空');
    if (Array.from(name).length > maxChars) throw new Error(label + '最多' + maxChars + '个字符');
    // 普通空格可用于分词，其他分隔符和控制字符会产生换行、隐藏文字或方向伪装。
    if (/[\p{C}\p{Z}]/u.test(name.replace(/ /g, ''))) {
      throw new Error(label + '不能包含换行或不可见字符');
    }
    return name;
  }

  G.normalizeDisplayName = normalizeDisplayName;

  function unitDisplayName(val) {
    if (!val) return '';
    var name = typeof val === 'object' && val.name ? val.name : String(val);
    var codeMatch = name.match(/[（(]([^）)]+)[）)]/);
    var code = codeMatch ? codeMatch[1].trim() : '';
    var base = name.indexOf('-') > 0 ? name.split('-')[0].trim() : name.replace(/[（(].*?[）)]/, '').trim();
    return code ? base + '(' + code + ')' : base;
  }

  var Core = {
    state: null,
    route: 'home',
    history: [],
    clockTimer: null,
    formatBeijingTime: formatBeijingTime,

    init: function () {
      this.state = G.state;
      G.Map.refreshUnlock();
      this.bindKeys();
      this.render();
    },

    bindKeys: function () {
      document.addEventListener('keydown', function (e) {
        var t = e.target;
        if (t && (t.tagName === 'INPUT' || t.tagName === 'SELECT' || t.tagName === 'TEXTAREA')) return;
        var k = e.key;
        if (/^[0-9]$/.test(k)) {
          G.Core.pressNumber(parseInt(k, 10));
        } else if (k === '*') {
          if (G.Core.route !== 'home') G.go('home');
        } else if (k === 'Backspace') {
          G.back();
        }
      }, false);
    },

    pressNumber: function (n) {
      if (n === 0) { G.back(); return; }
      // 数字快捷键走真实 click，让按钮行为与委托音效共享同一用户手势。
      var links = document.querySelectorAll('#view .menu-item');
      if (links.length > 0) {
        var el = links[n - 1];
        if (el && typeof el.onclick === 'function') { if (el.click) el.click(); else el.onclick(); return; }
      }
      var navs = document.querySelectorAll('#navbar .navitem');
      var nv = navs[n - 1];
      if (nv && typeof nv.onclick === 'function') { if (nv.click) nv.click(); else nv.onclick(); return; }
    },

    tick: function () {},

    /**
     * 区分普通野地采集与真实交战；采集往返、采集中及停采待命不构成军情警报。
     * @param {Object} march - 服务端行军快照
     * @returns {boolean} 是否为无需警报的普通采集任务
     */
    isRoutineGatherMarch: function (march) {
      if (!march || march.inBattle || march.battleId != null || march.waitingForBattle) return false;
      return march.action === 'gather' || march.targetKind === 'wild_gather' || !!march.gathering || !!march.gatherStopped;
    },

    /**
     * 仅为警讯统计行军；军情列表和地图仍保留全部采集任务。
     * @returns {Object[]} 不含普通采集任务的行军快照
     */
    militaryAlertMarches: function () {
      return ((this.state && this.state.world && this.state.world.marches) || []).filter(function (march) {
        return march && !Core.isRoutineGatherMarch(march);
      });
    },

    /** 首页与档案共用服务端保护截止时间；显式归零优先于账号上的旧值。 */
    newbieProtectionInfo: function () {
      var s = this.state || {};
      var cs = s.cityState || {};
      var player = s.player || {};
      var until = Number(cs.newbieProtectionUntil != null ? cs.newbieProtectionUntil : player.newbieProtectionUntil) || 0;
      var now = G.gameNow();
      var remaining = Math.max(0, until - now);
      return {
        until: until,
        active: until > now,
        countdown: G.formatDuration(remaining / 1000),
        rules: '首次进入保护7天，声望不足10000延长，最长14天。可建设和打NPC，参与玩家战争须确认永久退出。'
      };
    },

    getCityStatus: function () {
      var s = this.state;
      if (!s.cityState) s.cityState = { status: 'peace', warUntil: 0, shieldUntil: 0, peaceUntil: 0 };
      var cs = s.cityState;
      var now = G.gameNow();
      var incoming = (s.world && s.world.incoming) || [];
      if (cs.newbieProtectionUntil > now || (cs.shieldUntil && now < cs.shieldUntil) || (cs.peaceUntil && now < cs.peaceUntil)) return 'shield';
      if (incoming.length > 0) return 'war';
      return 'peace';
    },

    cityStatusText: function () {
      var status = this.getCityStatus();
      var s = this.state;
      var cs = s.cityState;
      var now = G.gameNow();
      if (s.player && s.player.cityFrozen) {
        var transferRemaining = s.player.cityTransferDeadlineAt && s.player.cityTransferDeadlineAt > now
          ? s.player.cityTransferDeadlineAt - now : 0;
        return { status: 'surrender', text: '归降移交中', color: 'var(--warning)', detail: transferRemaining ? '剩余' + G.formatDuration(transferRemaining / 1000) : '等待服务器结算' };
      }
      if (status === 'shield') {
        if (cs.newbieProtectionUntil > now) {
          var protection = this.newbieProtectionInfo();
          return { status: 'shield', text: '新手保护', color: 'var(--accent)', detail: '还剩' + protection.countdown + '；' + protection.rules };
        }
        var safeUntil = Math.max(cs.shieldUntil || 0, cs.peaceUntil || 0);
        return { status: 'shield', text: '免战状态', color: 'var(--accent)', detail: '剩余' + G.formatDuration((safeUntil - now) / 1000) };
      }
      if (status === 'war') {
        var warRemaining = cs.warUntil && cs.warUntil > now
          ? cs.warUntil - now
          : 0;
        return { status: 'war', text: '战争', color: 'var(--danger)', detail: warRemaining ? '剩余' + G.formatDuration(warRemaining / 1000) : '有敌军来袭' };
      }
      return { status: 'peace', text: '和平', color: 'var(--accent)', detail: '正常发展' };
    },



    capacity: function () {
      return G.ServerRules.value('capacity', {});
    },

    inventoryCapacity: function () {
      return G.ServerRules.value('inventoryCapacity', {});
    },

    protectCap: function () {
      return G.ServerRules.value('protectCap', 0);
    },


    /** 城内四类产量分别读取科技，避免一项研究提高全部资源。 */


    produceOf: function (id) {
      return (G.ServerRules.value('production', {}))[id] || 0;
    },

    buildingLevels: function (id) {
      var v = this.state.buildings[id];
      if (v == null) return [];
      if (Array.isArray(v)) return v;
      return [v];
    },

    buildingLevel: function (id) {
      var arr = this.buildingLevels(id);
      var sum = 0;
      for (var i = 0; i < arr.length; i++) sum += arr[i] || 0;
      return sum;
    },

    /** 区域占位和剩余名额只读服务端，新建资格仍由执行事务校验。 */
    groupSlotsUsed: function (groupKey) {
      return (G.ServerRules.value('groupSlots', {})[groupKey] || {}).used || 0;
    },

    groupSlotsCap: function (groupKey) {
      return (G.ServerRules.value('groupSlots', {})[groupKey] || {}).cap || 0;
    },

    groupSlotsRemaining: function (groupKey) {
      return (G.ServerRules.value('groupSlots', {})[groupKey] || {}).remaining || 0;
    },

    buildingGroup: function (id) {
      var groups = G.Build && G.Build.GROUPS;
      if (!groups) return null;
      for (var gk in groups) {
        if (groups[gk].order.indexOf(id) >= 0) return gk;
      }
      return null;
    },

    foodPerHour: function () {
      return (this.state.foodSupply || {}).totalPerHour || 0;
    },

    /** 城市状态只调整自然生产，不改变建筑基础产能或野地采集。 */
    cityProductionPerHour: function (buildingId) {
      var resource = { farm: 'food', refinery: 'steel', oilfield: 'oil', raremine: 'rare' }[buildingId];
      return ((this.state.resourceRates || {}).productionPerHour || {})[resource] || 0;
    },

    /** 采用实际粮账产量，包含城市战时／保护期的生产倍率。 */
    foodProductionPerHour: function () {
      return (this.state.foodSupply || {}).productionPerHour || 0;
    },

    popMax: function () {
      return (this.state.population || {}).capacity || 0;
    },

    popUsed: function () {
      return G.ServerRules.value('militaryPopulation', 0);
    },

    civilianPopulation: function () {
      return Math.max(0, (this.state.population && this.state.population.civilian) || 0);
    },

    populationCapacity: function () {
      return (this.state.population || {}).capacity || 0;
    },

    popFree: function () { return this.civilianPopulation(); },

    populationGrowthPerHour: function () {
      return (this.state.population || {}).growthPerHour || 0;
    },

    officerSalaryPerHour: function () {
      return ((this.state.resourceRates || {}).expensePerHour || {}).gold || 0;
    },

    resourceNetRate: function (key) {
      return ((this.state.resourceRates || {}).netPerHour || {})[key] || 0;
    },

    morale: function () {
      return (this.state && this.state.morale != null) ? this.state.morale : 70;
    },

    resentment: function () {
      return (this.state && this.state.resentment != null) ? this.state.resentment : 0;
    },

    tax: function () {
      return (this.state && this.state.tax != null) ? this.state.tax : 30;
    },

    effectiveCapacity: function () {
      return (this.state.population || {}).effectiveCapacity || 0;
    },

    getOfficerByRole: function (role) {
      var list = this.state.officers;
      for (var i = 0; i < list.length; i++) if (list[i].role === role) return list[i];
      return null;
    },

    formatSkills: function (skills) {
      if (!skills || !skills.length) return '';
      var parts = [];
      for (var i = 0; i < skills.length; i++) {
        var item = skills[i];
        if (!item) continue;
        if (typeof item === 'string') {
          var sDef = D.officerSkills && D.officerSkills[item];
          parts.push(sDef ? sDef.name : item);
        } else if (typeof item === 'object') {
          var id = item.id || '';
          var sDef = D.officerSkills && D.officerSkills[id];
          var name = (sDef && sDef.name) ? sDef.name : (item.name || id || '技能');
          var lv = item.lv != null ? item.lv : (item.level != null ? item.level : '');
          parts.push(name + (sDef ? '（满级）' : (lv !== '' ? 'Lv' + lv : '')));
        }
      }
      return parts.join('/');
    },

    skillText: function (o) {
      if (!o || !o.skills || !o.skills.length) return '';
      var s = this.formatSkills(o.skills);
      return s ? ' · 技能:' + s : '';
    },

    /** 当前城市行动火力只显示服务端倍率；战斗页面使用其冻结快照。 */
    firepowerMul: function () {
      return G.ServerRules.value('multipliers', {}).firepower || 1;
    },

    /** 账号动员仅提高新出征容量；守城全员参战，不使用此上限。 */
    armyCap: function () {
      return G.ServerRules.value('armyCap', 0);
    },

    ordinaryArmyCap: function () {
      return G.ServerRules.value('ordinaryArmyCap', 0);
    },

    /** 兼容旧调用的出征容量别名；不得作为守城参战上限使用。 */
    sortieCap: function () {
      return this.armyCap();
    },

    /** 当前城池最大同时出征队伍数量：基础5支 + 司令部等级（0级5支，1级6支，满级15支） */
    maxMarches: function () {
      return G.ServerRules.value('maxMarches', 0);
    },

    addExp: function () {},



    costEnough: function (cost) {
      var r = this.state.resources;
      for (var k in cost) {
        if (k === 'pop') continue;
        if ((r[k] || 0) < cost[k]) return false;
      }
      return true;
    },





    getCommanderSkills: function () {
      var officer = this.getOfficerByRole('commander'), result = {};
      ((officer && officer.skills) || []).forEach(function (skill) { result[skill.id] = skill.lv; });
      return result;
    },

    getMayorSkills: function () {
      var officer = this.getOfficerByRole('mayor'), result = {};
      ((officer && officer.skills) || []).forEach(function (skill) { result[skill.id] = skill.lv; });
      return result;
    },

    mayorSkillBonus: function (skillId) {
      var mayor = this.getOfficerByRole('mayor');
      return mayor && mayor.skillBonuses && mayor.skillBonuses[skillId] || 0;
    },

    skillBonus: function (skillId) {
      var commander = this.getOfficerByRole('commander');
      return commander && commander.skillBonuses && commander.skillBonuses[skillId === 'supply' ? 'leadership' : skillId] || 0;
    },



    toast: function (msg, type) {
      var container = $('toast');
      if (!container) return;
      // Create an individual toast item for vertical stacking
      var item = document.createElement('div');
      item.className = 'toast-item' + (type ? ' toast-' + type : ' toast-info');
      item.textContent = msg;
      container.appendChild(item);
      container.style.display = 'flex';
      // Auto-dismiss after 3 seconds
      setTimeout(function () {
        item.classList.add('fade-out');
        setTimeout(function () {
          if (item.parentNode) item.parentNode.removeChild(item);
          if (container.children.length === 0) container.style.display = 'none';
        }, 300);
      }, 3000);
    },

    /**
     * 嵌入论坛与独立论坛入口共用登录及战报分享参数。
     * @param {Object} [options] - 可选的 shareReportId、boardId
     * @returns {string} 论坛访问地址
     */
    getForumUrl: function (options) {
      options = options || {};
      var token = localStorage.getItem('wargame_token') || (G.API && G.API.getToken ? G.API.getToken() : '');
      var base = (window.location.protocol + '//' + window.location.hostname + ':5174');
      var url = base;
      var params = [];
      if (token) params.push('token=' + encodeURIComponent(token));
      if (options.shareReportId) params.push('share_report_id=' + encodeURIComponent(options.shareReportId));
      if (options.boardId) params.push('boardId=' + encodeURIComponent(options.boardId));
      if (params.length) url += (url.indexOf('?') >= 0 ? '&' : '?') + params.join('&');
      return url;
    },

    openForum: function (options) {
      window.open(this.getForumUrl(options), '_blank');
    },

    go: function (route) {
      // 统一拦住钻石、商城和 VIP 等入口，不改变当前页面或返回历史。
      if (route === 'recharge') {
        G.toast(G.Constants.rechargeUnavailableMessage);
        return;
      }
      if (route === 'forum') {
        this.openForum();
        return;
      }
      if (route === 'world' && G.WorldMap) G.WorldMap.prepareEntry();
      if (route !== this.route) {
        this.history.push(this.route);
        var view = $('view');
        if (view) view.scrollTop = 0;
      }
      this.route = route;
      // 每次重新进入世界地图都以玩家自己的城市为中心，不沿用上次搜索/移动的视角
      if (route === 'world' && this.state && this.state.world) {
        var cityPos = this.state.world.cityPos || {};
        if (cityPos.x != null && cityPos.y != null) {
          this.state.world._mapPos = { x: cityPos.x, y: cityPos.y };
        } else {
          this.state.world._mapPos = null;
        }
        this.state.world._searchCoord = '';
      }
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
      this.render();
    },

    back: function () {
      if (this.history.length) this.route = this.history.pop();
      else this.route = 'home';
      if (this.route === 'world' && G.WorldMap) G.WorldMap.prepareEntry();
      var view = $('view');
      if (view) view.scrollTop = 0;
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
      this.render();
    },

    /** 以服务端持久化头像为准；旧缓存仅可回退到当前账号有效的新预设。 */
    getCurrentAvatar: function () {
      var p = (this.state || {}).player || {};
      if (p.customAvatar && p.customAvatar.trim().length > 0) return p.customAvatar.trim();
      var presets = G.Constants.presetAvatars;
      if (presets.some(function (preset) { return preset.src === p.avatar; })) return p.avatar;
      var localAvatar = '';
      try {
        if (p.username) localAvatar = localStorage.getItem('wargame_avatar_' + p.username) || '';
      } catch (e) {}
      // 旧账号的随机分配由数据库迁移一次性完成，展示层不重复随机或加载已删除的 SVG。
      return presets.some(function (preset) { return preset.src === localAvatar; }) ? localAvatar : presets[0].src;
    },

    renderTop: function () {
      var top = $('topbar');
      if (!top) return;
      if (!this.state) { top.innerHTML = ''; return; }
      var s = this.state;
      var p = s.player || {};
      var r = s.resources || {};
      var diamond = r.diamond != null ? r.diamond : 0;
      var avatarUrl = this.getCurrentAvatar();
      var nameStr = escapeHtml(p.name || p.username || '指挥官');

      var vipLevel = Number(p.vipLevel) || 0;
      var vipBadgeVisible = p.vipBadgeVisible !== false;
      var vipBadgeHtml = (vipLevel > 0 && vipBadgeVisible)
        ? '<span class="topbar-vip-badge' + (vipLevel >= 4 ? ' vip-badge-gold' : '') + '">VIP ' + vipLevel + '</span>'
        : '';
      var avatarFrameClassName = G.avatarFrameClass ? G.avatarFrameClass(p.avatarFrame) : '';
      var avatarFrameClass = avatarFrameClassName ? ' ' + avatarFrameClassName : '';

      var html = '';
      html += '<div class="top-row">' +
        '<div class="topbar-player-entry" role="button" tabindex="0" onclick="if(Game.Main&&Game.Main.openPlayerDrawer)Game.Main.openPlayerDrawer();" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){if(Game.Main&&Game.Main.openPlayerDrawer)Game.Main.openPlayerDrawer();event.preventDefault();}" title="点击展开指挥官档案、主题与设置">' +
        '<div class="topbar-avatar-wrap' + avatarFrameClass + '">' +
        '<img class="topbar-avatar" src="' + avatarUrl + '" alt="头像" onerror="this.onerror=null;this.src=Game.Constants.presetAvatars[0].src"/>' +
        (G.avatarFrameOverlay ? G.avatarFrameOverlay(p.avatarFrame) : '') +
        '</div>' +
        '<div class="topbar-player-meta">' +
        '<div class="topbar-player-name">' + nameStr + vipBadgeHtml + '</div>' +
        '<div class="topbar-online-status">' +
        ((G.WS && typeof G.WS.statusHtml === 'function') ? G.WS.statusHtml() : '') +
        '</div>' +
        '</div>' +
        '</div>' +
        '<div class="player-bar-right">' +
        '<span class="vip-entry-btn" title="战时特勤统帅部" onclick="if(Game.Vip)Game.Vip.openModal();">🎖️ 特勤部</span>' +
        '<span class="forum-entry-btn" title="进入战术参谋论坛" onclick="Game.Core.openForum()">🎖️ 论坛</span>' +
        '<span class="diamond" title="账号共享钻石；暂未开放充值，敬请期待" onclick="Game.go(\'recharge\')">💎 ' + fmt(diamond) + '</span>' +
        '</div>' +
        '</div>';
      var marches = this.militaryAlertMarches();
      var incoming = s.world.incoming || [];
      var alertCount = marches.length + incoming.length;
      if (alertCount > 0) {
        html += '<div class="march-bar" onclick="Game.go(\'alerts\')" style="cursor:pointer">⚔ 军情 ' + alertCount + ' 起 (行军' + marches.length + '/来袭' + incoming.length + ') 点击查看</div>';
      }
      if (G.Protection) html += G.Protection.banner();
      top.innerHTML = html;
      // 地图按钮可能挂在旧顶栏中，重绘后重新接回当前玩家栏。
      if (G.WorldMap && G.WorldMap.syncToolbar) G.WorldMap.syncToolbar();
    },

    render: function () {
      if (G.Protection && G.Protection.blocked && this.route !== 'login' && this.route !== 'protection') {
        this.route = G.API.isLoggedIn() ? 'protection' : 'login';
        this.state = null; G.state = null;
      }
      if (G.WorldMap && (this.route !== 'world' || !G.WorldMap.isMap())) G.WorldMap.unmount();
      if (this.route !== 'world' && G.World && G.World.stopListGatherTimer) G.World.stopListGatherTimer();
      if (this.route !== 'alerts' && G.World && G.World.stopAlertTimer) G.World.stopAlertTimer();
      if (this.route !== 'battle' && G.Battle && G.Battle.stopTacticalTimer) G.Battle.stopTacticalTimer();
      if (this.route !== 'wounded' && G.Wounded) G.Wounded.stop();
      if (this.route !== 'prisoners' && G.Prisoners) G.Prisoners.stop();
      if (this.route !== 'tech' && G.Tech && G.Tech.stopTimer) G.Tech.stopTimer();
      if (this.route !== 'buildRes' && this.route !== 'buildArmy' && G.Build && G.Build.stopQueueTimer) G.Build.stopQueueTimer();
      var screen = $('screen');
      if (screen && screen.classList) screen.classList.toggle('chat-open', this.route === 'chat');
      this.renderTop();
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
      var v = $('view');
      var fn = this.views[this.route] || this.views.home;
      if (v) {
        var chatMounted = this.route === 'chat' && v.querySelector('.chat-page');
        if (!chatMounted && !(this.route === 'world' && G.WorldMap && G.WorldMap.isMap() && G.WorldMap.mounted(v))) v.innerHTML = '';
        fn.call(this, v);
        // 每个功能页提供一致的返回入口。按钮放在页面渲染完成后插入，
        // 因此不会覆盖各模块自己的标题、筛选器或地图容器。
        if (this.route !== 'home' && this.route !== 'login' && this.route !== 'protection') this.renderBackButton(v);
      }
      var foot = $('footbar');
      if (foot) {
        foot.innerHTML = this.footer();
        this.startBeijingClock();
      }
      if (G.Onboarding) G.Onboarding.render();
    },

    updateBeijingClock: function () {
      var time = $('beijingTime');
      if (!time) return;
      var now = new Date();
      time.textContent = '北京时间：' + formatBeijingTime(now);
      time.dateTime = now.toISOString();
    },

    startBeijingClock: function () {
      this.updateBeijingClock();
      if (this.clockTimer) return;
      this.clockTimer = setInterval(function () { Core.updateBeijingClock(); }, 1000);
    },

    renderBackButton: function (view) {
      if (!view || view.querySelector('.page-backbar') || view.querySelector('.world-map-shell')) return;
      var bar = document.createElement('div');
      bar.className = 'page-backbar';
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'page-back-button';
      button.setAttribute('aria-label', '返回上一步');
      button.innerHTML = '<span>‹ 返回上一步</span>';
      button.addEventListener('click', function () { Core.back(); });
      bar.appendChild(button);
      var mapSwitch = view.querySelector('.world-map-list-switch');
      if (mapSwitch) bar.appendChild(mapSwitch);
      view.insertBefore(bar, view.firstChild);
    },

    // Refresh top bar smoothly without destroying DOM tree or avatar
    refreshTop: function () {
      var top = $('topbar');
      if (!top) return;
      if (!this.state) { top.innerHTML = ''; return; }
      var diamondEl = top.querySelector('.player-bar-right .diamond') || top.querySelector('.diamond');
      var forumBtn = top.querySelector('.forum-entry-btn');
      if (!diamondEl || !forumBtn) {
        this.renderTop();
        return;
      }
      var player = this.state.player || {};
      var vipLevel = Number(player.vipLevel) || 0;
      var vipBadge = top.querySelector('.topbar-vip-badge');
      var vipText = vipLevel > 0 && player.vipBadgeVisible !== false ? 'VIP ' + vipLevel : '';
      if ((vipBadge ? vipBadge.textContent : '') !== vipText) {
        this.renderTop();
        return;
      }
      // In-place diamond text update
      var s = this.state;
      var r = s.resources || {};
      var diamond = r.diamond != null ? r.diamond : 0;
      var newDiaText = '💎 ' + fmt(diamond);
      if (diamondEl.textContent !== newDiaText) {
        diamondEl.textContent = newDiaText;
      }
      // In-place march/alert bar update
      var marches = this.militaryAlertMarches();
      var incoming = (s.world && s.world.incoming) || [];
      var alertCount = marches.length + incoming.length;
      var marchBar = top.querySelector('.march-bar');
      if (alertCount > 0) {
        var alertText = '⚔ 军情 ' + alertCount + ' 起 (行军' + marches.length + '/来袭' + incoming.length + ') 点击查看';
        if (marchBar) {
          if (marchBar.textContent !== alertText) marchBar.textContent = alertText;
        } else {
          var barDiv = document.createElement('div');
          barDiv.className = 'march-bar';
          barDiv.setAttribute('onclick', "Game.go('alerts')");
          barDiv.style.cursor = 'pointer';
          barDiv.textContent = alertText;
          top.appendChild(barDiv);
        }
      } else if (marchBar) {
        marchBar.remove();
      }
    },

    // Refresh current view content smoothly (WITHOUT v.innerHTML = '' to eliminate blank flash)
    refreshContent: function () {
      var v = $('view');
      if (v && this.route) {
        var fn = this.views[this.route] || this.views.home;
        var prevScroll = v.scrollTop;
        fn.call(this, v);
        if (v.scrollTop !== prevScroll) {
          v.scrollTop = prevScroll;
        }
      }
    },

    // Silent background update for periodic ticks (Zero-Flash)
    silentUpdate: function (tickData) {
      if (!this.state) return;
      var route = this.route || 'home';

      if (route === 'chat' && G.Chat) {
        G.Chat.updateSendBtnUI();
        return;
      }

      if (route === 'academy' && G.Officer && G.Officer.updateAcademyRefresh) {
        G.Officer.updateAcademyRefresh();
        return;
      }

      // 军队属于静态页，但科技、建筑和地理资格变化仍须刷新征召卡片。
      // 生产倒计时独立更新；输入中的刷新由 tick 调用方延后。
      if (route === 'army') {
        if (G.Army && G.Army.refreshUnlocks) G.Army.refreshUnlocks();
        return;
      }

      // 1. Static / transactional pages: skip DOM updates completely.
      // Data is already synced in G.state, topbar resources are updated by refreshTop.
      if (G.Constants.staticRoutes[route]) {
        return;
      }

      // 2. City home page: precise in-place DOM patch
      if (route === 'home') {
        var homeUpdater = (G.Main && G.Main.silentUpdateHome) || (G.MainView && G.MainView.silentUpdateHome);
        if (homeUpdater) {
          homeUpdater(tickData);
        } else {
          this.refreshContent();
        }
        return;
      }

      // 3. Construction pages: update countdowns without destroying building cards
      if (route === 'buildRes' || route === 'buildArmy') {
        if (G.Build && G.Build.silentUpdateBuild) {
          G.Build.silentUpdateBuild(tickData);
        } else if (tickData && tickData.completedBuilds && tickData.completedBuilds.length > 0) {
          this.refreshContent();
        }
        return;
      }

      // 4. 恢复与战俘页面使用各自的轮询更新。
      if (route === 'wounded' || route === 'prisoners') {
        return;
      }

      // 5. World map uses internal animations; alerts need periodic redraw for countdowns
      if (route === 'world') {
        return;
      }

      // Fallback for any other pages
      this.refreshContent();
    },

    // Refresh only a specific section by ID
    refreshSection: function (sectionId) {
      var el = document.getElementById(sectionId);
      if (el) {
        // Re-render just this section
        // This is a simple implementation - for complex sections, full re-render may be needed
        this.render();
      }
    },

    /** 底部入口切换后回到页首，避免长页面切换后仍停留在页脚。 */
    footerNavigate: function (route) {
      if (route) G.go(route);
      var view = $('view');
      if (view) view.scrollTop = 0;
      window.scrollTo({ top: 0, behavior: 'instant' });
    },

    /** 登录页只展示站点信息；游戏内提供常用导航并标识当前页面。 */
    footer: function () {
      var map = G.Constants.footerHints;
      var html = '';
      if (this.state && this.route !== 'login') {
        var items = G.Constants.footerNavItems;
        html += '<nav class="footer-nav" aria-label="底部快捷导航">';
        for (var i = 0; i < items.length; i++) {
          var item = items[i];
          var current = this.route === item.route;
          html += '<button type="button" class="footer-nav-item"' + (current ? ' aria-current="page"' : '') +
            ' onclick="Game.Core.footerNavigate(\'' + item.route + '\')">' +
            '<span class="footer-nav-icon" aria-hidden="true">' + item.icon + '</span><span>' + item.label + '</span></button>';
        }
        html += '<button type="button" class="footer-nav-item" aria-label="返回顶部" onclick="Game.Core.footerNavigate()">' +
          '<span class="footer-nav-icon" aria-hidden="true">↑</span><span>顶部</span></button></nav>';
      }
      var hint = Object.prototype.hasOwnProperty.call(map, this.route) ? map[this.route] : '';
      if (hint) html += '<p class="footer-hint">' + escapeHtml(hint) + '</p>';
      var placeholder = SITE_INFO.isPlaceholder ? '（模拟）' : '';
      html += '<div class="footer-site-info">' +
        '<p class="footer-brand">山河远征录<span> · 文字战争策略游戏</span></p>' +
        '<p>运营主体：' + escapeHtml(SITE_INFO.operator) + placeholder + '</p>' +
        '<p><a class="footer-icp" href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">' +
        escapeHtml(SITE_INFO.icpNumber) + placeholder + '</a></p>' +
        (SITE_INFO.isPlaceholder ? '<p class="footer-placeholder">备案信息为演示占位，非真实备案</p>' : '') +
        '<p class="footer-beijing-time-wrap"><time id="beijingTime" class="footer-beijing-time" datetime="">北京时间：加载中…</time></p></div>';
      return html;
    },

    views: {}
  };

  G.escapeHtml = escapeHtml;
  G.unitDisplayName = unitDisplayName;
  G.Core = Core;
  G.$ = $;
  G.fmt = fmt;
  G.clamp = clamp;
  G.fmtTime = fmtTime;
  G.formatSkills = function (skills) { return Core.formatSkills(skills); };
  G.skillText = function (o) { return Core.skillText(o); };
  G.toast = function (m, type) { Core.toast(m, type); };
  G.go = function (r) { Core.go(r); };
  G.back = function () { Core.back(); };
})(window.Game);
