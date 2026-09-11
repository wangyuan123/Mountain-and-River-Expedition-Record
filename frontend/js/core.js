/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;

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

  var Core = {
    state: null,
    route: 'home',
    history: [],

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
        } else if (k === '#') {
          G.toast('已存档');
        } else if (k === '*') {
          if (G.Core.route === 'home') G.Main.confirmReset();
          else G.go('home');
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
        return { text: '免战状态', color: 'var(--accent)', detail: '剩余' + min + '分钟' };
      }
      if (status === 'war') {
        var warMin = cs.warUntil && cs.warUntil > now
          ? Math.ceil((cs.warUntil - now) / 60000)
          : 0;
        return { text: '战争', color: 'var(--danger)', detail: warMin ? '剩余' + warMin + '分钟' : '有敌军来袭' };
      }
      return { text: '和平', color: 'var(--accent)', detail: '正常发展' };
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
      return used;
    },

    groupSlotsCap: function (groupKey) {
      var base = (D.groupSlots && D.groupSlots[groupKey]) || 10;
      var commandLv = this.buildingLevel('command');
      return base + commandLv * 2;
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
      return sum;
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
      var allMul = 1 + 0.05 * (s.tech.cmd_attack || 0);
      var catKey = { inf: 'inf_attack', arm: 'arm_attack', air: 'air_attack', nav: 'nav_attack' }[cat];
      var catMul = 1 + 0.05 * (s.tech[catKey] || 0);
      return allMul * catMul * (1 + cmdMil / 100);
    },

    defMul: function (cat, isMine) {
      var s = this.state;
      var allMul = 1 + 0.05 * (s.tech.cmd_defense || 0);
      var catKey = { inf: 'inf_defense', arm: 'arm_defense', air: 'air_defense', nav: 'nav_defense' }[cat];
      var catMul = 1 + 0.05 * (s.tech[catKey] || 0);
      var wallMul = (isMine && cat !== 'air') ? (1 + 0.05 * (s.buildings.wall || 0)) : 1;
      return allMul * catMul * wallMul;
    },

    hpMul: function (cat) {
      var s = this.state;
      var allMul = 1 + 0.05 * (s.tech.cmd_hp || 0);
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
      return 1 - 0.05 * (this.state.tech.log_build || 0);
    },

    medicalMul: function () {
      var v = 0.05 * (this.state.tech.log_medical || 0);
      return Math.min(0.9, v);
    },

    armyCap: function () {
      var s = this.state;
      var cmd = this.getOfficerByRole('commander');
      var cmdLv = cmd ? (cmd.level || 1) : 1;
      var staffLv = this.buildingLevel('staff');
      var base = 500 + s.player.level * 100;
      return Math.floor(base * (1 + staffLv * 0.05) * (1 + cmdLv * 0.02));
    },

    addExp: function (n) {
      var p = this.state.player;
      p.exp += n;
      var titles = D.officerTitles;
      while (p.exp >= p.level * 200) {
        p.exp -= p.level * 200;
        p.level += 1;
        this.state.resources.gold += 100;
        var ti = Math.min(p.level, titles.length - 1);
        p.title = titles[ti];
        G.toast('晋升! Lv.' + p.level + ' ' + p.title + '  +100金');
      }
    },

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

    skillBonus: function (skillId) {
      var skills = this.getCommanderSkills();
      var lv = skills[skillId] || 0;
      if (lv <= 0) return 0;
      var rates = {
        frenzy: 0.10, bulwark: 0.10, blitz: 0.15, suppress: 0.08,
        pierce: 0.12, supply: 0.20, medic: 0.15, combo: 0.08
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
      if (route !== this.route) this.history.push(this.route);
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
      if (route === 'alerts' && this.state && this.state.world) {
        this.state.world.alertsViewed = true;
      }
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
      this.render();
    },

    back: function () {
      if (this.history.length) this.route = this.history.pop();
      else this.route = 'home';
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
      var gameLv = p.level || 1;
      var vipLv = p.vipLevel != null ? p.vipLevel : (p.vip != null ? p.vip : 0);
      var diamond = r.diamond != null ? r.diamond : 0;
      var prestige = s.prestige != null ? s.prestige : (p.prestige != null ? p.prestige : 0);
      var avatarSvg = '<svg viewBox="0 0 32 32" width="28" height="28" aria-hidden="true">' +
        '<defs><linearGradient id="avG" x1="0" y1="0" x2="0" y2="1">' +
        '<stop offset="0%" stop-color="#7ec8ff"/><stop offset="100%" stop-color="#3a6fb5"/>' +
        '</linearGradient></defs>' +
        '<circle cx="16" cy="16" r="15" fill="url(#avG)" stroke="#1a3a66" stroke-width="1.5"/>' +
        '<circle cx="16" cy="12" r="5" fill="#fff" opacity="0.92"/>' +
        '<path d="M5 28 C 7 20, 25 20, 27 28 Z" fill="#fff" opacity="0.92"/>' +
        '</svg>';
      var tooltipHtml = '<div class="avatar-tip">' +
        '<div class="tip-row"><span class="tip-k">游戏等级</span><span class="tip-v">Lv.' + gameLv + '</span></div>' +
        '<div class="tip-row"><span class="tip-k">VIP 等级</span><span class="tip-v">Lv.' + vipLv + '</span></div>' +
        '</div>';
      var html = '';
      html += '<div class="top-row">' +
        '<div class="player-bar">' +
        '<span class="avatar">' + avatarSvg + tooltipHtml + '</span>' +
        '<span class="name">' + escapeHtml(p.name || p.username || '') + '</span>' +
        '<span class="prestige-tag" title="声望"><span class="ps-icon">★</span>' + fmt(prestige) + '</span>' +
        '</div>' +
        '<div class="player-bar-right">' +
        '<span class="diamond" title="充值" onclick="Game.go(\'recharge\')">💎 ' + fmt(diamond) + '</span>' +
        '<span class="shop-btn" title="商城" onclick="Game.go(\'shop\')">商</span>' +
        '<span class="icon-btn" title="设置" onclick="Game.go(\'settings\')"><img class="icon-btn-img" src="img/settings.svg" alt="设置"/></span>' +
        '</div>' +
        '</div>';
      var marches = s.world.marches || [];
      var incoming = s.world.incoming || [];
      var alertCount = marches.length + incoming.length;
      if (alertCount > 0) {
        html += '<div class="march-bar" onclick="Game.go(\'alerts\')" style="cursor:pointer">⚔ 军情 ' + alertCount + ' 起 (行军' + marches.length + '/来袭' + incoming.length + ') 点击查看</div>';
      }
      top.innerHTML = html;
    },

    render: function () {
      this.renderTop();
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
      var v = $('view');
      var fn = this.views[this.route] || this.views.home;
      v.innerHTML = '';
      fn.call(this, v);
      var foot = $('footbar');
      foot.innerHTML = this.footer();
    },

    // Refresh only the top bar (resources, gold, etc.)
    refreshTop: function () {
      this.renderTop();
    },

    // Refresh only the current view content (without re-rendering the entire page)
    refreshContent: function () {
      var v = $('view');
      if (v && this.route) {
        var fn = this.views[this.route] || this.views.home;
        v.innerHTML = '';
        fn.call(this, v);
      }
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

    footer: function () {
      var map = {
        login: '登录/注册 或 [0]游客模式',
        home: '[1-9]导航 [*]重置',
        buildRes: '[1-9]升级 [0]返回',
        buildArmy: '[1-9]升级 [0]返回',
        fort: '修筑/拆除城防 [0]返回',
        army: '点击征召/解散 [0]返回',
        officer: '点击招募/任命/查看详情 [0]返回',
        officerDetail: '查看军官详情 [0]返回',
        tech: '[1-6]研究 [0]返回',
        map: '点击挑战 [0]返回',
        wild: '点击占领/废弃 [0]返回',
        world: '方向键移动 输入坐标定位 [0]返回',
        dispatch: '选配兵力/军官/辎重 [0]返回',
        alerts: '查看军情 [0]返回',
        reports: '点击展开 [0]返回',
        reportDetail: '返回战报列表/主菜单',
        battle: '[1]立即结算/下一回合 [0]撤退',
        report: '[1]再战 [0]返回地图',
        depot: '查看和使用道具 [0]返回',
        depotUse: '选择军官使用道具 [0]返回',
        settings: '游戏设置与账号管理 [0]返回'
      };
      return map[this.route] || '[0]返回';
    },

    views: {}
  };

  G.escapeHtml = escapeHtml;
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
