/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;

  // 上线时统一替换为实际运营主体和已取得的备案号，并关闭模拟标记。
  var SITE_INFO = {
    operator: '山河远征网络科技有限公司',
    icpNumber: '京ICP备00000000号-1',
    isPlaceholder: true
  };

  function $(id) { return document.getElementById(id); }

  function fmt(n) {
    return Math.floor(n).toString();
  }

  function fmtTime(seconds) {
    if (seconds < 60) return seconds + '秒';
    var m = Math.floor(seconds / 60);
    var s = seconds % 60;
    return m + '分' + (s > 0 ? s + '秒' : '');
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
  function rand(a, b) { return a + Math.floor(Math.random() * (b - a + 1)); }

  function escapeHtml(str) {
    if (str == null) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

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
      var links = document.querySelectorAll('#view .menu-item');
      if (links.length > 0) {
        var el = links[n - 1];
        if (el && typeof el.onclick === 'function') { el.onclick(); return; }
      }
      var navs = document.querySelectorAll('#navbar .navitem');
      var nv = navs[n - 1];
      if (nv && typeof nv.onclick === 'function') { nv.onclick(); return; }
    },

    tick: function () {},

    getCityStatus: function () {
      var s = this.state;
      if (!s.cityState) s.cityState = { status: 'peace', warTarget: null, warUntil: 0, shieldUntil: 0, peaceUntil: 0 };
      var cs = s.cityState;
      var now = Date.now();
      var incoming = (s.world && s.world.incoming) || [];
      if ((cs.shieldUntil && now < cs.shieldUntil) || (cs.peaceUntil && now < cs.peaceUntil)) return 'shield';
      if (incoming.length > 0) return 'war';
      return 'peace';
    },

    cityStatusText: function () {
      var status = this.getCityStatus();
      var s = this.state;
      var cs = s.cityState;
      var now = Date.now();
      if (status === 'shield') {
        var safeUntil = Math.max(cs.shieldUntil || 0, cs.peaceUntil || 0);
        var min = Math.ceil((safeUntil - now) / 60000);
        return { status: 'shield', text: '免战状态', color: 'var(--accent)', detail: '剩余' + min + '分钟' };
      }
      if (status === 'war') {
        var warMin = cs.warUntil && cs.warUntil > now
          ? Math.ceil((cs.warUntil - now) / 60000)
          : 0;
        return { status: 'war', text: '战争', color: 'var(--danger)', detail: warMin ? '剩余' + warMin + '分钟' : '有敌军来袭' };
      }
      return { status: 'peace', text: '和平', color: 'var(--accent)', detail: '正常发展' };
    },

    enterWarState: function (durationHours) {
      var s = this.state;
      if (!s.cityState) s.cityState = { status: 'peace', warTarget: null, warUntil: 0, shieldUntil: 0, peaceUntil: 0 };
      s.cityState.status = 'war';
      s.cityState.warUntil = Date.now() + (durationHours || 1) * 3600 * 1000;
    },

    capacity: function () {
      var caps = {
        food: this.buildingLevel('farm') * 200000,
        steel: this.buildingLevel('refinery') * 200000,
        oil: this.buildingLevel('oilfield') * 200000,
        rare: this.buildingLevel('raremine') * 200000,
        gold: 999999
      };
      return caps;
    },

    protectCap: function () {
      var depotLv = this.buildingLevel('depot');
      var capMul = 1 + 0.10 * (this.state.tech.log_warehouse || 0);
      return Math.floor(depotLv * D.buildings.depot.protectPer * capMul);
    },

    resBonusMul: function () {
      var s = this.state;
      var mayor = this.getOfficerByRole('mayor');
      var mayorLogi = mayor ? mayor.logistics : 0;
      var bonus = 1 + 0.05 * (s.tech.log_production || 0) + mayorLogi / 100;
      var harvestBonus = this.mayorSkillBonus ? this.mayorSkillBonus('harvest') : 0;
      if (harvestBonus > 0) bonus *= (1 + harvestBonus);
      bonus *= 1 + 0.03 * (s.buildings.transit || 0);
      return bonus;
    },

    produceOf: function (id) {
      var s = this.state;
      var b = D.buildings[id];
      if (!b || !b.produces) return 0;
      var arr = this.buildingLevels(id);
      var total = 0;
      for (var i = 0; i < arr.length; i++) {
        var lv = arr[i] || 0;
        if (lv <= 0) continue;
        var curve = lv * (1 + 0.12 * (lv - 1));
        total += b.baseProduce * curve;
      }
      return Math.floor(total * this.resBonusMul());
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

    groupSlotsUsed: function (groupKey) {
      var s = this.state;
      var used = 0;
      var order = (G.Build && G.Build.GROUPS && G.Build.GROUPS[groupKey]) ? G.Build.GROUPS[groupKey].order : [];
      for (var i = 0; i < order.length; i++) {
        var id = order[i];
        var arr = this.buildingLevels(id);
        for (var j = 0; j < arr.length; j++) {
          if (arr[j] > 0) used++;
        }
      }
      if (groupKey === 'res' || groupKey === 'army') {
        var jobs = s.constructions || (s.construction ? [s.construction] : []);
        jobs.forEach(function (job) {
          if (order.indexOf(job.id) >= 0 && job.targetLevel === 1) {
            var arr = Core.buildingLevels(job.id);
            var curLv = (job.slot != null) ? (arr[job.slot] || 0) : (arr[0] || 0);
            if (curLv === 0) used++;
          }
        });
      }
      return used;
    },

    groupSlotsCap: function (groupKey) {
      var base = (D.groupSlots && D.groupSlots[groupKey]) || 10;
      var commandLv = this.buildingLevel('command');
      return Math.min(32, base + Math.max(0, commandLv) * 2);
    },

    groupSlotsRemaining: function (groupKey) {
      return Math.max(0, this.groupSlotsCap(groupKey) - this.groupSlotsUsed(groupKey));
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
      var s = this.state, sum = 0;
      for (var id in s.army) sum += (D.units[id] ? D.units[id].food : 0) * s.army[id];
      var techLv = this.techLevel ? this.techLevel('log_food') : 0;
      var foodSave = 1 - 0.05 * techLv;
      if (foodSave < 0.5) foodSave = 0.5;
      var rationBonus = this.mayorSkillBonus ? this.mayorSkillBonus('ration') : 0;
      if (rationBonus > 0) {
        foodSave *= Math.max(0.1, 1.0 - rationBonus);
      }
      if (foodSave < 0.1) foodSave = 0.1;
      return Math.round(sum * foodSave);
    },

    popMax: function () {
      return this.buildingLevel('house') * D.buildings.house.popPer;
    },

    popUsed: function () {
      var s = this.state, sum = 0;
      for (var id in s.army) sum += (D.units[id] ? D.units[id].pop : 0) * s.army[id];
      return sum;
    },

    civilianPopulation: function () {
      return Math.max(0, (this.state.population && this.state.population.civilian) || 0);
    },

    populationCapacity: function () {
      return (this.state.population && this.state.population.capacity != null)
        ? this.state.population.capacity : this.popMax();
    },

    popFree: function () { return this.civilianPopulation(); },

    populationGrowthPerHour: function () {
      return (this.state.population && this.state.population.growthPerHour != null)
        ? this.state.population.growthPerHour : this.populationCapacity() * 0.03;
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
      if (this.state && this.state.population && this.state.population.effectiveCapacity != null) {
        return this.state.population.effectiveCapacity;
      }
      var cap = this.populationCapacity();
      var m = this.morale();
      return cap <= 0 ? 0 : Math.max(10, Math.round(cap * Math.min(1.0, m / 70.0)));
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
          parts.push(name + (lv !== '' ? 'Lv' + lv : ''));
        }
      }
      return parts.join('/');
    },

    skillText: function (o) {
      if (!o || !o.skills || !o.skills.length) return '';
      var s = this.formatSkills(o.skills);
      return s ? ' · 技能:' + s : '';
    },

    atkMul: function (cat) {
      var s = this.state;
      var cmd = this.getOfficerByRole('commander');
      var cmdMil = cmd ? cmd.military : 0;
      var allMul = 1 + 0.10 * (s.tech.attack_tech || 0);
      return allMul * (1 + cmdMil / 100);
    },

    defMul: function (cat, isMine) {
      var s = this.state;
      var allMul = 1 + 0.10 * (s.tech.defense_tech || 0);
      var catMul = 1;
      var wallMul = (isMine && cat !== 'air') ? (1 + 0.05 * (s.buildings.wall || 0)) : 1;
      return allMul * catMul * wallMul;
    },

    rangeMul: function () { return 1 + 0.10 * (this.state.tech.weapon_range || 0); },

    hpMul: function (cat) {
      var s = this.state;
      var allMul = 1 + 0.10 * (s.tech.cmd_hp || 0);
      return allMul;
    },

    spdMul: function (cat) {
      var s = this.state;
      var catKey = { inf: null, arm: 'arm_engine', air: 'air_engine', nav: 'nav_engine' }[cat];
      if (!catKey) return 1;
      return 1 + 0.05 * (s.tech[catKey] || 0);
    },

    trainMul: function () {
      return 1 + 0.10 * (this.state.tech.log_train || 0);
    },

    buildMul: function () {
      var mul = 1 - 0.05 * (this.state.tech.log_build || 0);
      var constructBonus = this.mayorSkillBonus ? this.mayorSkillBonus('construct') : 0;
      if (constructBonus > 0) mul *= (1 - constructBonus);
      return Math.max(0.35, mul);
    },

    medicalMul: function () {
      var v = 0.05 * (this.state.tech.log_medical || 0);
      return Math.max(0, Math.min(0.5, v));
    },

    armyCap: function () {
      var s = this.state || {};
      var p = s.player || {};
      var rankTier = p.militaryRank || 1;
      var rankInfo = G.getMilitaryRankTierInfo ? G.getMilitaryRankTierInfo(rankTier) : { baseCap: 50000 };
      var rankBase = rankInfo.baseCap || 50000;
      // 只有当前城市围墙达到满级才一次性加成，统帅技能继续放大最终基础容量。
      var wallBonus = this.buildingLevel('wall') >= 10 ? 100000 : 0;
      var leadBonus = 0;
      if (this.skillBonus) {
        leadBonus = Math.max(this.skillBonus('leadership'), this.skillBonus('supply'));
      }
      return Math.floor((rankBase + wallBonus) * (1 + leadBonus));
    },

    addExp: function () {},

    addOfficerExp: function (officerId, n) {
      var s = this.state;
      var o = null;
      for (var i = 0; i < s.officers.length; i++) {
        if (s.officers[i] && String(s.officers[i].id) === String(officerId)) { o = s.officers[i]; break; }
      }
      if (!o) return;
      if (o.level >= G.OFFICER_MAX_LEVEL) return;
      o.exp = (o.exp || 0) + n;
      var need = G.expNeeded(o.level);
      var grew = 0;
      while (o.exp >= need && o.level < G.OFFICER_MAX_LEVEL) {
        o.exp -= need;
        o.level += 1;
        o.attrPoints = (o.attrPoints || 0) + 1;
        need = G.expNeeded(o.level);
        grew++;
      }
      if (o.level >= G.OFFICER_MAX_LEVEL) o.exp = 0;
      if (grew > 0) G.toast(o.name + ' 升至 Lv.' + o.level + ' (+' + grew + '级)');
    },

    costEnough: function (cost) {
      var r = this.state.resources;
      for (var k in cost) {
        if (k === 'pop') continue;
        if ((r[k] || 0) < cost[k]) return false;
      }
      return true;
    },

    payCost: function (cost) {
      if (!this.costEnough(cost)) return false;
      var r = this.state.resources;
      for (var k in cost) {
        if (k === 'pop') continue;
        r[k] = (r[k] || 0) - cost[k];
      }
      return true;
    },

    prestigeFromCost: function (cost) {
      var total = 0;
      for (var k in cost) if (k !== 'pop') total += Number(cost[k]) || 0;
      return total > 0 ? Math.max(1, Math.floor(total / 100)) : 0;
    },

    getCommanderSkills: function () {
      var cmd = this.getOfficerByRole('commander');
      if (!cmd || !cmd.skills) return {};
      var map = {};
      for (var i = 0; i < cmd.skills.length; i++) map[cmd.skills[i].id] = cmd.skills[i].lv;
      return map;
    },

    getMayorSkills: function () {
      var mayor = this.getOfficerByRole('mayor');
      if (!mayor || !mayor.skills) return {};
      var map = {};
      for (var i = 0; i < mayor.skills.length; i++) {
        if (mayor.skills[i] && mayor.skills[i].id) {
          map[mayor.skills[i].id] = mayor.skills[i].lv;
        }
      }
      return map;
    },

    mayorSkillBonus: function (skillId) {
      var skills = this.getMayorSkills();
      var lv = skills[skillId] || 0;
      if (lv <= 0) return 0;
      var rates = {
        ration: 0.16, harvest: 0.10, construct: 0.04, finance: 0.04, research: 0.04
      };
      return (rates[skillId] || 0) * lv;
    },

    skillBonus: function (skillId) {
      var skills = this.getCommanderSkills();
      var lv = skills[skillId] || 0;
      if (lv <= 0) return 0;
      if (skillId === 'counter') {
        return 0.10 * Math.min(5, lv);
      }
      var rates = {
        frenzy: 0.10, bulwark: 0.10, blitz: 0.06, suppress: 0.06,
        pierce: 0.06, leadership: 0.04, supply: 0.04, medic: 0.03,
        ration: 0.16, harvest: 0.10, construct: 0.04, finance: 0.04, research: 0.04,
        learn: 0.06, borrow_armor: 0.06
      };
      return (rates[skillId] || 0) * lv;
    },

    addPrestige: function (cost) {
      var gain = this.prestigeFromCost(cost);
      if (gain <= 0) return 0;
      this.state.prestige = (this.state.prestige || 0) + gain;
      return gain;
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

    go: function (route) {
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
      var view = $('view');
      if (view) view.scrollTop = 0;
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
      this.render();
    },

    renderTop: function () {
      var top = $('topbar');
      if (!top) return;
      if (!this.state) { top.innerHTML = ''; return; }
      var s = this.state;
      var p = s.player || {};
      var r = s.resources || {};
      var diamond = r.diamond != null ? r.diamond : 0;
      var uname = p.username || '';
      var localAvatar = '';
      try {
        if (uname) localAvatar = localStorage.getItem('wargame_avatar_' + uname) || '';
        if (!localAvatar) localAvatar = localStorage.getItem('wargame_avatar_default') || '';
      } catch (e) {}
      var avatarUrl = p.avatar || localAvatar || 'img/avatars/commander-8.svg';
      var nameStr = escapeHtml(p.name || p.username || '指挥官');

      var html = '';
      html += '<div class="top-row">' +
        '<div class="topbar-player-entry" role="button" tabindex="0" onclick="if(Game.Main&&Game.Main.openPlayerDrawer)Game.Main.openPlayerDrawer();" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){if(Game.Main&&Game.Main.openPlayerDrawer)Game.Main.openPlayerDrawer();event.preventDefault();}" title="点击展开指挥官档案、主题与设置">' +
        '<div class="topbar-avatar-wrap">' +
        '<img class="topbar-avatar" src="' + avatarUrl + '" alt="头像" onerror="this.src=\'img/avatars/commander-8.svg\'"/>' +
        '</div>' +
        '<div class="topbar-player-meta">' +
        '<div class="topbar-player-name">' + nameStr + '</div>' +
        '<div class="topbar-online-status">' +
        G.WS.statusHtml() +
        '</div>' +
        '</div>' +
        '</div>' +
        '<div class="player-bar-right">' +
        '<span class="diamond" title="充值" onclick="Game.go(\'recharge\')">💎 ' + fmt(diamond) + '</span>' +
        '<button class="icon-btn shop-btn topbar-shop-btn" title="商城" onclick="Game.go(\'shop\')">' +
        '<img class="icon-btn-img" src="img/shop.svg" alt="商城"/>' +
        '</button>' +
        '</div>' +
        '</div>';
      var marches = s.world.marches || [];
      var incoming = s.world.incoming || [];
      var alertCount = marches.length + incoming.length;
      if (alertCount > 0) {
        html += '<div class="march-bar" onclick="Game.go(\'alerts\')" style="cursor:pointer">⚔ 军情 ' + alertCount + ' 起 (行军' + marches.length + '/来袭' + incoming.length + ') 点击查看</div>';
      }
      if (G.Protection) html += G.Protection.banner();
      top.innerHTML = html;
    },

    render: function () {
      if (G.Protection && G.Protection.blocked && this.route !== 'login' && this.route !== 'protection') {
        this.route = G.API.isLoggedIn() ? 'protection' : 'login';
        this.state = null; G.state = null;
      }
      if (G.WorldMap && (this.route !== 'world' || !G.WorldMap.isMap())) G.WorldMap.unmount();
      if (this.route !== 'alerts' && G.World && G.World.stopAlertTimer) G.World.stopAlertTimer();
      if (this.route !== 'battle' && G.Battle && G.Battle.stopTacticalTimer) G.Battle.stopTacticalTimer();
      if (this.route !== 'wounded' && G.Wounded) G.Wounded.stop();
      if (this.route !== 'tech' && G.Tech && G.Tech.stopTimer) G.Tech.stopTimer();
      this.renderTop();
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
      var v = $('view');
      var fn = this.views[this.route] || this.views.home;
      if (v) {
        if (!(this.route === 'world' && G.WorldMap && G.WorldMap.isMap() && G.WorldMap.mounted(v))) v.innerHTML = '';
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
      if (!view || view.querySelector('.page-backbar')) return;
      var bar = document.createElement('div');
      bar.className = 'page-backbar';
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'page-back-button';
      button.setAttribute('aria-label', '返回上一步');
      button.innerHTML = '<span>[‹ 返回上一步]</span>';
      button.addEventListener('click', function () { Core.back(); });
      bar.appendChild(button);
      view.insertBefore(bar, view.firstChild);
    },

    // Refresh top bar smoothly without destroying DOM tree or avatar
    refreshTop: function () {
      var top = $('topbar');
      if (!top) return;
      if (!this.state) { top.innerHTML = ''; return; }
      var diamondEl = top.querySelector('.diamond');
      if (!diamondEl) {
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
      var marches = (s.world && s.world.marches) || [];
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

      // 1. Static / transactional pages: skip DOM updates completely.
      // Data is already synced in G.state, topbar resources are updated by refreshTop.
      var staticRoutes = {
        shop: 1, depot: 1, depotUse: 1, depotRename: 1,
        officer: 1, officerDetail: 1, academy: 1,
        tech: 1, settings: 1, battleDefaults: 1,
        reports: 1, reportDetail: 1, battle: 1, report: 1,
        mail: 1, recharge: 1, login: 1,
        guild: 1, map: 1, wild: 1, dispatch: 1
      };
      if (staticRoutes[route]) {
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

      // 4. Army production: handled by army.js own queue timer
      if (route === 'army' || route === 'wounded') {
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
      var map = {
        // TODO：恢复防沉迷后改回“实名注册 · 健康游戏”。
        login: '登录账号 · 开启远征',
        protection: '账号服务在休息期间仍可办理',
        home: '',
        buildRes: '[1-9]升级 [0]返回',
        buildArmy: '[1-9]升级 [0]返回',
        fort: '修筑/拆除城防 [0]返回',
        army: '点击征召/解散 [0]返回',
        officer: '点击招募/任命/查看详情 [0]返回',
        officerDetail: '查看军官详情 [0]返回',
        tech: '[1-6]研究 [0]返回',
        map: '点击挑战 [0]返回',
        wild: '点击占领/废弃 [0]返回',
        world: '拖动浏览 · 双指缩放 · 点击目标查看详情',
        dispatch: '选配兵力/军官/辎重 [0]返回',
        alerts: '查看情报 [0]返回',
        reports: '点击展开 [0]返回',
        reportDetail: '返回战报列表/主菜单',
        battle: '[1]立即结算/下一回合 [0]撤退',
        report: '[1]再战 [0]返回地图',
        depot: '查看和使用道具 [0]返回',
        depotUse: '选择军官使用道具 [0]返回',
        settings: '游戏设置与账号管理 [0]返回'
      };
      var html = '';
      if (this.state && this.route !== 'login') {
        var items = [
          { route: 'home', label: '首页', icon: '⌂' },
          { route: 'world', label: '地图', icon: '◎' },
          { route: 'mainQuest', label: '任务', icon: '⚑' },
          { route: 'mail', label: '邮件', icon: '✉' },
          { route: 'settings', label: '设置', icon: '⚙' }
        ];
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
      var hint = Object.prototype.hasOwnProperty.call(map, this.route) ? map[this.route] : '[0]返回';
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
  G.rand = rand;
  G.fmtTime = fmtTime;
  G.formatSkills = function (skills) { return Core.formatSkills(skills); };
  G.skillText = function (o) { return Core.skillText(o); };
  G.toast = function (m, type) { Core.toast(m, type); };
  G.go = function (r) { Core.go(r); };
  G.back = function () { Core.back(); };
})(window.Game);
