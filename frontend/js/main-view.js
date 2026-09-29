/* global window, document */
window.Game = window.Game || {};
(function (G) {
  'use strict';
  var Core = G.Core;
  var D = G.DATA;

  // 首页总览与军队页共用写实武器模型；没有模型时回退到通用图标。
  var UNIT_MODEL = G.UNIT_MODEL || {};
  var HOME_MODULE_IDS = ['officers', 'army', 'resources', 'chat'];
  var HOME_MODULE_NAMES = { officers: '军官将领', army: '军队总览', resources: '资源', chat: '世界聊天' };
  var homeModulePending = null;
  var homeModuleSaveQueue = Promise.resolve();

  function homeModuleStorageKey() {
    var username = G.API && G.API.getUsername ? G.API.getUsername() : '';
    return 'wargame_home_modules_' + (username || (Core.state && Core.state.player && Core.state.player.username) || 'guest');
  }

  function normalizeHomeModuleOrder(saved) {
    if (!Array.isArray(saved)) saved = [];
    var order = [];
    saved.concat(HOME_MODULE_IDS).forEach(function (id) {
      if (HOME_MODULE_IDS.indexOf(id) !== -1 && order.indexOf(id) === -1) order.push(id);
    });
    return order;
  }

  function legacyHomeModuleOrder() {
    var saved;
    try { saved = JSON.parse(window.localStorage.getItem(homeModuleStorageKey())); } catch (e) { return null; }
    return Array.isArray(saved) && saved.length ? normalizeHomeModuleOrder(saved) : null;
  }

  function homeModuleOrder() {
    var player = Core.state && Core.state.player;
    if (player && homeModulePending && homeModulePending.playerId === player.id) return homeModulePending.order;
    if (player && Array.isArray(player.homeModuleOrder)) return normalizeHomeModuleOrder(player.homeModuleOrder);
    return legacyHomeModuleOrder() || HOME_MODULE_IDS.slice();
  }

  function persistHomeModuleOrder(order) {
    var player = Core.state && Core.state.player;
    if (!player || !G.API || !G.API.setHomeModuleOrder) return;
    var playerId = player.id;
    var username = G.API.getUsername ? G.API.getUsername() : '';
    var savedOrder = order.slice();
    var legacyKey = homeModuleStorageKey();
    homeModulePending = { playerId: playerId, order: savedOrder };
    homeModuleSaveQueue = homeModuleSaveQueue.catch(function () {}).then(function () {
      if (!Core.state || !Core.state.player || Core.state.player.id !== playerId ||
          (G.API.getUsername && G.API.getUsername() !== username)) {
        if (homeModulePending && homeModulePending.playerId === playerId &&
            homeModulePending.order.join(',') === savedOrder.join(',')) homeModulePending = null;
        return;
      }
      return G.API.setHomeModuleOrder(savedOrder).then(function (response) {
        if (Core.state && Core.state.player && Core.state.player.id === playerId) {
          Core.state.player.homeModuleOrder = normalizeHomeModuleOrder(response.homeModuleOrder);
        }
        if (Core.state && Core.state.player && Core.state.player.id === playerId &&
            homeModulePending && homeModulePending.playerId === playerId &&
            homeModulePending.order.join(',') === savedOrder.join(',')) {
          homeModulePending = null;
        }
        try { window.localStorage.removeItem(legacyKey); } catch (e) { /* 旧版缓存可留待下次清理。 */ }
      }).catch(function (error) {
        if (homeModulePending && homeModulePending.playerId === playerId &&
            homeModulePending.order.join(',') === savedOrder.join(',')) {
          homeModulePending = null;
          if (G.toast) G.toast('首页排序保存失败，请重新调整后重试');
        }
      });
    });
  }

  function homeModuleHandle(id) {
    return '<button type="button" class="home-module-handle" aria-label="调整' + HOME_MODULE_NAMES[id] + '顺序" title="拖动排序，或用上下方向键移动">⠿</button>';
  }

  function renderHomeModules(modules) {
    return '<div id="homeModuleList" class="home-module-list">' + homeModuleOrder().map(function (id) {
      return '<section class="home-module" data-home-module="' + id + '">' + modules[id] + '</section>';
    }).join('') + '<span class="home-module-status" role="status" aria-live="polite"></span></div>';
  }

  function setupHomeModuleSorting(root) {
    if (!root || !root.addEventListener) return;
    var drag = null;
    var scrollFrame = null;
    var view = root.closest && root.closest('#view');

    function currentOrder() {
      return Array.prototype.map.call(root.querySelectorAll('[data-home-module]'), function (section) {
        return section.getAttribute('data-home-module');
      });
    }

    function saveOrder() {
      persistHomeModuleOrder(currentOrder());
    }

    function announce(section) {
      var status = root.querySelector('.home-module-status');
      if (status) status.textContent = HOME_MODULE_NAMES[section.getAttribute('data-home-module')] + '已移至第 ' + (currentOrder().indexOf(section.getAttribute('data-home-module')) + 1) + ' 位';
    }

    function reorderAtPoint(x, y) {
      var hit = document.elementFromPoint(x, y);
      var target = hit && hit.closest ? hit.closest('[data-home-module]') : null;
      if (!target || !root.contains(target) || target === drag.section) return;
      var rect = target.getBoundingClientRect();
      root.insertBefore(drag.section, y < rect.top + rect.height / 2 ? target : target.nextSibling);
    }

    function autoScroll() {
      scrollFrame = null;
      if (!drag || !drag.active) return;
      var scrollView = view && view.scrollHeight > view.clientHeight ? view : null;
      var bounds = scrollView ? scrollView.getBoundingClientRect() : { top: 0, bottom: window.innerHeight };
      var edge = 64;
      var speed = drag.y < bounds.top + edge ? -14 : (drag.y > bounds.bottom - edge ? 14 : 0);
      if (!speed) return;
      if (scrollView) scrollView.scrollTop += speed;
      else if (window.scrollBy) window.scrollBy(0, speed);
      reorderAtPoint(drag.x, drag.y);
      if (window.requestAnimationFrame) scrollFrame = window.requestAnimationFrame(autoScroll);
    }

    function onPointerMove(event) {
      if (!drag || event.pointerId !== drag.pointerId) return;
      drag.x = event.clientX;
      drag.y = event.clientY;
      if (!drag.active && Math.hypot(drag.x - drag.startX, drag.y - drag.startY) < 6) return;
      drag.active = true;
      drag.section.classList.add('home-module-dragging');
      reorderAtPoint(drag.x, drag.y);
      if (scrollFrame === null && window.requestAnimationFrame) scrollFrame = window.requestAnimationFrame(autoScroll);
      event.preventDefault();
    }

    function onPointerEnd(event) {
      if (!drag || event.pointerId !== drag.pointerId) return;
      document.removeEventListener('pointermove', onPointerMove);
      document.removeEventListener('pointerup', onPointerEnd);
      document.removeEventListener('pointercancel', onPointerEnd);
      if (scrollFrame !== null && window.cancelAnimationFrame) window.cancelAnimationFrame(scrollFrame);
      drag.section.classList.remove('home-module-dragging');
      if (event.type === 'pointercancel') {
        drag.originalOrder.forEach(function (id) {
          root.insertBefore(root.querySelector('[data-home-module="' + id + '"]'), root.querySelector('.home-module-status'));
        });
      } else if (drag.active && currentOrder().join(',') !== drag.originalOrder.join(',')) {
        saveOrder();
        announce(drag.section);
      }
      drag = null;
      scrollFrame = null;
    }

    root.addEventListener('pointerdown', function (event) {
      var handle = event.target.closest && event.target.closest('.home-module-handle');
      if (!handle || (event.pointerType === 'mouse' && event.button !== 0)) return;
      var section = handle.closest('[data-home-module]');
      drag = { section: section, pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, x: event.clientX, y: event.clientY, active: false, originalOrder: currentOrder() };
      document.addEventListener('pointermove', onPointerMove, { passive: false });
      document.addEventListener('pointerup', onPointerEnd);
      document.addEventListener('pointercancel', onPointerEnd);
      event.preventDefault();
    });

    root.addEventListener('keydown', function (event) {
      var handle = event.target.closest && event.target.closest('.home-module-handle');
      if (!handle || (event.key !== 'ArrowUp' && event.key !== 'ArrowDown')) return;
      var section = handle.closest('[data-home-module]');
      var sibling = event.key === 'ArrowUp' ? section.previousElementSibling : section.nextElementSibling;
      if (!sibling || !sibling.hasAttribute('data-home-module')) return;
      event.preventDefault();
      root.insertBefore(section, event.key === 'ArrowUp' ? sibling : sibling.nextSibling);
      saveOrder();
      announce(section);
      handle.focus();
    });
  }

  function renderArmySummaryList() {
    var s = Core.state || {};
    var arr = [];
    var army = s.army || {};
    var ids = Object.keys(army);
    for (var i = 0; i < ids.length; i++) {
      var id = ids[i];
      var cnt = army[id] || 0;
      if (cnt <= 0) continue;
      var ud = D.units && D.units[id];
      arr.push({ id: id, name: ud ? ud.name : id, cnt: cnt, branch: ud ? ud.branch : '' });
    }
    // 没有兵力时显示空态
    if (!arr.length) {
      return '<div class="army-summary-empty">暂无可用部队，前往 <a onclick="Game.go(\'army\')">军队</a> 征召</div>';
    }
    // 数量从大到小排序；首页按三行横向浏览，全屏视图复用这份完整列表。
    arr.sort(function (a, b) { return b.cnt - a.cnt; });
    var html = '';
    for (var i = 0; i < arr.length; i++) {
      var u = arr[i];
      var rawIcon = UNIT_MODEL[u.id] || (G.UNIT_ICON && G.UNIT_ICON[u.id]) || '⚔';
      var iconHtml = /\.svg$|\.png$|\.jpg$|\.webp$/i.test(rawIcon)
        ? '<img class="army-summary-icon-img" src="' + rawIcon + '" alt="' + G.escapeHtml(u.name) + '"/>'
        : rawIcon;
      var displayName = (G && typeof G.unitDisplayName === 'function')
        ? G.unitDisplayName(u.name)
        : (function (name) {
          var codeMatch = name.match(/[（(]([^）)]+)[）)]/);
          var code = codeMatch ? codeMatch[1].trim() : '';
          var base = name.indexOf('-') > 0 ? name.split('-')[0].trim() : name.replace(/[（(].*?[）)]/, '').trim();
          return code ? base + '(' + code + ')' : base;
        })(u.name);
      var unitClick = ' role="button" tabindex="0" title="' + G.escapeHtml(u.name) + ' × ' + G.fmt(u.cnt) + '" aria-label="查看' + G.escapeHtml(u.name) + '详情"' +
        ' onclick="Game.MainView.showUnitDetailModal(\'' + G.escapeHtml(u.id) + '\', event)"' +
        ' onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.MainView.showUnitDetailModal(\'' + G.escapeHtml(u.id) + '\', event);event.preventDefault();}"';
      html += '<div class="army-summary-item"' + unitClick + '>'
        + '<span class="army-summary-icon">' + iconHtml + '</span>'
        + '<span class="army-summary-name">' + G.escapeHtml(displayName) + '</span>'
        + '<span class="army-summary-cnt">' + G.fmt(u.cnt) + '</span>'
        + '</div>';
    }
    return html;
  }

  /** 展开当前城市的全部可用兵种，保留兵种卡片原有的详情入口。 */
  function showArmySummaryFullscreen() {
    if (typeof document === 'undefined' || !document.createElement || !document.body) return;
    var previousFocus = document.activeElement;
    var modal = document.createElement('div');
    modal.className = 'modal-mask army-summary-mask';
    modal.innerHTML =
      '<section class="modal-card army-summary-dialog" role="dialog" aria-modal="true" aria-labelledby="armySummaryTitle">' +
      '<div class="modal-title army-summary-dialog-title" id="armySummaryTitle"><span>🪖 军队总览</span><button type="button" class="army-summary-close" aria-label="关闭军队总览">✕</button></div>' +
      '<div class="modal-body army-summary-dialog-body"><div class="army-summary army-summary-expanded" aria-label="全部可用兵种">' + renderArmySummaryList() + '</div></div>' +
      '</section>';
    document.body.appendChild(modal);

    var close = function () {
      document.removeEventListener('keydown', onKeydown);
      if (modal.parentNode) modal.parentNode.removeChild(modal);
      if (previousFocus && previousFocus.focus) previousFocus.focus();
    };
    var onKeydown = function (event) { if (event.key === 'Escape') close(); };
    modal.querySelector('.army-summary-close').onclick = close;
    modal.addEventListener('click', function (event) { if (event.target === modal) close(); });
    document.addEventListener('keydown', onKeydown);
    modal.querySelector('.army-summary-close').focus();
  }

  function showUnitDetailModal(id, ev) {
    if (ev && ev.stopPropagation) ev.stopPropagation();
    var unit = D.units && D.units[id];
    if (!unit || typeof document === 'undefined' || !document.createElement || !document.body) return;

    var esc = G.escapeHtml;
    var count = (Core.state && Core.state.army && Core.state.army[id]) || 0;
    var rawIcon = UNIT_MODEL[id] || (G.UNIT_ICON && G.UNIT_ICON[id]) || '';
    var iconHtml = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(rawIcon)
      ? '<img class="unit-detail-icon" src="' + esc(rawIcon) + '" alt="' + esc(unit.name) + '" />'
      : '<span class="unit-detail-icon unit-detail-icon-text">' + esc(rawIcon || '⚔') + '</span>';
    var role = (D.combatRoles && D.combatRoles[id]) || '暂无战斗定位说明';
    var stats = [
      ['对地攻击', unit.atkGround], ['对空攻击', unit.atkAir], ['对海攻击', unit.atkSea], ['对工事攻击', unit.atkFort],
      ['防御', unit.def], ['生命', unit.hp], ['速度', unit.spd], ['射程', unit.range],
      ['常驻耗粮', (unit.food || 0) + '/小时'], ['行军油耗', (unit.marchOil || 0) + '/100格'],
      ['行军粮耗', (unit.marchFood || 0) + '/5分钟'], ['人口占用', unit.pop || 0]
    ];
    var statHtml = '';
    for (var i = 0; i < stats.length; i++) {
      statHtml += '<div class="unit-detail-stat"><span>' + esc(stats[i][0]) + '</span><b>' + esc(String(stats[i][1])) + '</b></div>';
    }

    var modal = document.createElement('div');
    modal.className = 'modal-mask unit-detail-mask';
    modal.innerHTML =
      '<section class="modal-card unit-detail-card" role="dialog" aria-modal="true" aria-labelledby="unitDetailTitle">' +
      '<div class="modal-title unit-detail-title" id="unitDetailTitle"><span>兵种详情</span><button type="button" class="unit-detail-close" aria-label="关闭兵种详情">✕</button></div>' +
      '<div class="modal-body unit-detail-body">' +
      '<div class="unit-detail-overview">' + iconHtml + '<div class="unit-detail-overview-copy"><h2>' + esc(unit.name) + '</h2><div class="unit-detail-count">当前兵力：<b>' + G.fmt(count) + '</b></div></div></div>' +
      '<div class="unit-detail-section"><h3>历史信息</h3><p>' + esc(unit.history || '暂无历史信息') + '</p></div>' +
      '<div class="unit-detail-section"><h3>战斗定位</h3><p>' + esc(role) + '</p></div>' +
      '<div class="unit-detail-section"><h3>属性信息</h3><div class="unit-detail-stats">' + statHtml + '</div></div>' +
      '</div>' +
      '</section>';
    document.body.appendChild(modal);

    var close = function () { if (modal.parentNode) modal.parentNode.removeChild(modal); };
    var closeBtn = modal.querySelector('.unit-detail-close');
    if (closeBtn) closeBtn.onclick = close;
    modal.addEventListener('click', function (event) { if (event.target === modal) close(); });
  }

  function renderOfficerSummaryCard() {
    var s = Core.state || {};
    var rawOfficers = s.officers || [];
    var officers = rawOfficers.slice();

    // 按照等级降序、星级降序、三维属性总和降序排序
    officers.sort(function (a, b) {
      var lvDiff = (b.level || 1) - (a.level || 1);
      if (lvDiff !== 0) return lvDiff;
      var starDiff = (b.star || 1) - (a.star || 1);
      if (starDiff !== 0) return starDiff;
      var statA = (a.military || 0) + (a.logistics || 0) + (a.knowledge || 0);
      var statB = (b.military || 0) + (b.logistics || 0) + (b.knowledge || 0);
      return statB - statA;
    });

    var totalCount = officers.length;
    var html = '';

    html += '<div class="zone-head">' + homeModuleHandle('officers')
      + '<span class="zone-title">🎖️ 军官将领</span>'
      + '<span class="zone-sub">已招募 ' + totalCount + ' 名</span>'
      + '<span class="home-officer-go zone-head-action" onclick="event.stopPropagation();Game.go(\'academy\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){event.stopPropagation();Game.go(\'academy\');event.preventDefault();}" role="button" tabindex="0" title="点击前往陆军讲武堂 · 招募将领">去招募 &gt;</span>'
      + '</div>';
    html += '<div class="home-officer-card">';

    if (totalCount === 0) {
      html += '<div class="home-officer-empty">'
        + '<span class="home-officer-empty-icon">🎖️</span>'
        + '<div class="home-officer-empty-info">'
        + '<div class="home-officer-empty-title">暂未招募将领</div>'
        + '<div class="home-officer-empty-desc">前往陆军讲武堂招募将领，委任市长与指挥官以提升城防与产能</div>'
        + '</div>'
        + '<span class="home-officer-go" onclick="Game.go(\'academy\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.go(\'academy\');event.preventDefault();}" role="button" tabindex="0" title="前往陆军讲武堂 · 招募将领">去讲武堂招募 &gt;</span>'
        + '</div>';
    } else {
      html += '<div class="home-officer-list">';
      for (var i = 0; i < officers.length; i++) {
        var o = officers[i];
        var starColor = (D.starColor && D.starColor[o.star]) || '#ffe14a';

        // 职位显示
        var roleTag = '';
        if (o.role === 'mayor') {
          roleTag = '<span class="home-officer-role role-mayor">市长</span>';
        } else if (o.role === 'march') {
          roleTag = '<span class="home-officer-role">行军中</span>';
        } else if (o.role === 'commander') {
          roleTag = '<span class="home-officer-role role-commander">指挥官</span>';
        } else {
          roleTag = '<span class="home-officer-role role-idle">闲置</span>';
        }

        // 技能摘要
        var skillSummary = Core.formatSkills ? Core.formatSkills(o.skills) : '';
        var firstSkill = o.skills && o.skills.find(function (skill) { return skill; });
        var skillId = typeof firstSkill === 'string' ? firstSkill : firstSkill && firstSkill.id;
        var skillDef = D.officerSkills && D.officerSkills[skillId];
        var skillIcon = skillDef && skillDef.icon || '✦';

        html += '<div class="home-officer-item home-officer-item-action" role="button" tabindex="0" title="查看' + G.escapeHtml(o.name || '军官') + '详情" aria-label="查看' + G.escapeHtml(o.name || '军官') + '详情" onclick="Game.Officer.showDetail(\'' + o.id + '\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.Officer.showDetail(\'' + o.id + '\');event.preventDefault();}">';
        html += '<div class="home-officer-top-row">';
        html += '<div class="home-officer-identity">';
        html += roleTag;
        html += '<span class="home-officer-name" style="color:' + starColor + '">' + G.escapeHtml(o.name || '军官') + '</span>';
        html += '</div>';
        html += '<span class="home-officer-level">Lv.' + (o.level || 1) + (o.level >= (G.OFFICER_MAX_LEVEL || 100) ? '<small>(满)</small>' : '') + '</span>';
        html += '</div>';

        html += '<div class="home-officer-bottom-row">';
        html += '<div class="home-officer-stats">';
        html += '<span class="home-officer-stat"><span class="stat-lbl">军事</span><b class="stat-val mil">' + (o.military || 0) + '</b></span>';
        html += '<span class="home-officer-stat"><span class="stat-lbl">后勤</span><b class="stat-val log">' + (o.logistics || 0) + '</b></span>';
        html += '<span class="home-officer-stat"><span class="stat-lbl">学识</span><b class="stat-val kno">' + (o.knowledge || 0) + '</b></span>';
        html += '</div>';
        if (skillSummary) {
          html += '<div class="home-officer-skill" title="' + G.escapeHtml(skillSummary) + '"><span aria-hidden="true">' + G.escapeHtml(skillIcon) + '</span> ' + G.escapeHtml(skillSummary) + '</div>';
        }
        html += '</div>';

        html += '</div>';
      }
      html += '</div>';

      var mayor = Core.getOfficerByRole('mayor');
      var cmd = Core.getOfficerByRole('commander');
      html += '<div class="home-officer-foot">';
      html += '<div class="home-officer-foot-text">';
      html += '共 <b>' + totalCount + '</b> 名将领 · 市长: <b>' + (mayor ? G.escapeHtml(mayor.name) : '未任命') + '</b> · 指挥官: <b>' + (cmd ? G.escapeHtml(cmd.name) : '未任命') + '</b>';
      html += '</div>';
      html += '<span class="home-officer-go" onclick="Game.go(\'officer\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.go(\'officer\');event.preventDefault();}" role="button" tabindex="0" title="前往参谋部 · 军官管理">参谋部 &gt;</span>';
      html += '</div>';
    }

    html += '</div>';
    return html;
  }

  Core.views.login = function (v) {
    var h = '';
    h += '<main class="login-page"><div class="login-shell">';
    h += '<section class="login-hero" aria-labelledby="loginTitle">';
    h += '<div class="login-hero-art" aria-hidden="true"><img class="login-hero-tank" src="img/units/models/htank.webp" alt="">';
    h += '<img class="login-hero-infantry login-hero-infantry-left" src="img/units/models/infantry.webp" alt="">';
    h += '<img class="login-hero-infantry login-hero-infantry-rear" src="img/units/models/infantry.webp" alt="">';
    h += '<img class="login-hero-infantry login-hero-infantry-right" src="img/units/models/infantry.webp" alt="">';
    h += '<img class="login-hero-fighter login-hero-fighter-front" src="img/units/models/fighter.webp" alt="">';
    h += '<img class="login-hero-fighter login-hero-fighter-back" src="img/units/models/fighter.webp" alt=""></div>';
    h += '<div class="login-hero-top"><span class="login-emblem" aria-hidden="true">✦</span><span>山河远征录 <span class="login-hero-divider">/</span> 战地档案</span><span class="login-hero-serial">NO. 001 — FRONTLINE</span></div>';
    h += '<div class="login-hero-copy"><span class="login-kicker">STRATEGY · COMMAND · CONQUEST</span>';
    h += '<h1 id="loginTitle">山河远征录</h1><p class="login-hero-subtitle">烽烟已起，等待你的指令。</p>';
    h += '<div class="login-hero-rule" aria-hidden="true"><span></span><span></span><span></span></div>';
    h += '<p class="login-hero-description">建立防线，调度军团，争夺每一寸山河。<br>从这里开始，书写属于你的战役。</p></div>';
    h += '<div class="login-hero-bottom"><span>战区指挥部 · 作战终端</span><span>◆ &nbsp; 全线待命</span></div>';
    h += '</section>';
    h += '<section class="login-access" aria-label="账号登录与注册"><div class="login-access-inner">';
    h += '<div class="login-access-heading"><span class="login-access-index">01 / ACCOUNT ACCESS</span><span class="login-access-mark" aria-hidden="true">✦</span></div>';
    if (G.Account && G.Account.recovery) {
      h += G.Account.recoveryPanel();
    } else {
      h += '<div class="login-access-title"><span class="login-access-eyebrow" id="loginModeEyebrow">指挥官身份验证</span><h2 id="loginModeTitle">账号登录</h2><p id="loginModeDescription">使用指挥官账号继续你的远征。</p></div>';
      if (G.Account) h += G.Account.loginPanel();
      h += '<form class="login-form" id="loginForm" data-auth-mode="login" onsubmit="Game.Main.submitAuth(); return false;">';
      var servers = G.Servers ? G.Servers.list : [{ id: 'jiangsu-1', name: '江苏一区' }];
      var selectedServer = G.Servers ? G.Servers.current().id : 'jiangsu-1';
      var selectedName = (servers.find(function (server) { return server.id === selectedServer; }) || servers[0]).name;
      h += '<div class="edit-row"><label id="loginServerLabel" for="loginServer">服务器大区</label>';
      h += '<div class="login-server-picker" onkeydown="Game.Main.handleServerKey(event)" onfocusout="Game.Main.leaveServerMenu(event)">';
      h += '<button type="button" id="loginServer" class="login-server-trigger" aria-labelledby="loginServerLabel loginServerValue" aria-haspopup="listbox" aria-expanded="false" aria-controls="loginServerOptions" onclick="Game.Main.toggleServerMenu(this)"><span id="loginServerValue">' + G.escapeHtml(selectedName) + '</span><span class="login-server-chevron" aria-hidden="true"></span></button>';
      h += '<div id="loginServerOptions" class="login-server-options" role="listbox" aria-labelledby="loginServerLabel" hidden>';
      servers.forEach(function (server) { h += '<button type="button" role="option" tabindex="-1" aria-selected="' + (server.id === selectedServer ? 'true' : 'false') + '" data-server-id="' + G.escapeHtml(server.id) + '" onclick="Game.Main.chooseServer(this)">' + G.escapeHtml(server.name) + '</button>'; });
      h += '</div></div></div>';
      h += '<div class="edit-row"><label for="loginUser">用户名 <span>CALLSIGN</span></label><input id="loginUser" class="qty" name="username" autocomplete="username" maxlength="32" placeholder="输入指挥官代号 · 3-32 位"></div>';
      h += '<div class="edit-row"><label for="loginPass">密码 <span>ACCESS CODE</span></label><input id="loginPass" class="qty" name="password" autocomplete="current-password" type="password" maxlength="64" placeholder="输入通行密码 · 6-64 位"></div>';
      h += '<div class="edit-row login-register-only" id="loginConfirmRow" hidden><label for="loginPassConfirm">确认密码 <span>CONFIRM CODE</span></label><input id="loginPassConfirm" class="qty" name="passwordConfirm" autocomplete="new-password" type="password" maxlength="64" placeholder="再次输入通行密码"></div>';
      h += '<div class="login-actions"><button class="btn ok login-submit" id="loginPrimaryAction" type="submit">登 录 <span aria-hidden="true">→</span></button></div>';
      h += '<div id="loginMsg" class="login-feedback" role="status" aria-live="polite"></div>';
      h += '<nav class="login-account-links" aria-label="账号操作"><button type="button" id="authModeSwitch" onclick="Game.Main.toggleAuthMode()">注册账号</button><span aria-hidden="true"></span><button type="button" onclick="Game.Main.showForgotPassword()">忘记密码</button></nav>';
      h += '</form>';
      h += '<div class="login-access-foot"><span class="login-signal" aria-hidden="true"></span>战局进度由服务器自动保存</div>';
    }
    h += '</div></section></div></main>';
    // TODO：恢复防沉迷后取消以下登录页说明与入口的注释。
    // h += '<p>进入游戏前须完成实名认证。未成年人仅在规定日期的20:00—21:00游戏。</p>';
    // h += '<p><a href="privacy.html" target="_blank" rel="noopener">实名与儿童个人信息说明</a> · <button class="btn" onclick="Game.go(\'protection\')">防沉迷与帮助</button></p>';
    v.innerHTML = h;
  };

  var NAV_ITEMS = G.Constants.navItems;

  function navBar() {
    var s = Core.state;
    if (!s || !s.world) return '';
    var hasIncoming = s.world.incoming && s.world.incoming.length > 0;
    var items = [];
    var homeActive = Core.route === 'home' ? ' active' : '';
    items.push('<div class="navitem home-tab' + homeActive + '" data-route="home" onclick="Game.go(\'home\')"><span class="navlabel">首页</span></div>');
    for (var i = 0; i < NAV_ITEMS.length; i++) {
      var it = NAV_ITEMS[i];
      var action = 'Game.go(\'' + it.route + '\')';
      var active = (Core.route === it.route || (Core.route === 'wounded' && it.route === 'army') || (Core.route === 'officerDetail' && it.route === 'officer')) ? ' active' : '';
      var alertCls = (it.route === 'alerts' && hasIncoming) ? ' alert' : '';
      var mailUnread = (it.route === 'mail' && G.Mail && G.Mail.unread && G.Mail.unread() > 0) ? G.Mail.unread() : 0;
      var mailBadge = mailUnread ? '<span class="nav-badge">' + mailUnread + '</span>' : '';
      var reportsUnread = (it.route === 'reports' && G.Battle && G.Battle.unreadCount && G.Battle.unreadCount() > 0) ? G.Battle.unreadCount() : 0;
      var reportsBadge = reportsUnread ? '<span class="nav-badge">' + (reportsUnread > 99 ? '99+' : reportsUnread) + '</span>' : '';
      // 主线任务红点
      var questBadge = '';
      if (it.route === 'mainQuest' && G.MainQuest && G.MainQuest.hasUnclaimed && G.MainQuest.hasUnclaimed()) {
        questBadge = '<span class="nav-badge alert-dot">!</span>';
      }
      items.push('<div class="navitem' + active + alertCls + '" data-route="' + it.route + '" onclick="' + action + '"><span class="navnum">[' + it.key + ']</span><span class="navlabel">' + (it.icon ? '<img class="nav-icon" src="' + it.icon + '" alt="' + it.label + '"/>' : it.label) + '</span>' + mailBadge + reportsBadge + questBadge + '</div>');
    }
    if (G.Cities) items.splice(items.length - 2, 0, G.Cities.nav());
    // 每页两排八列，超过十六个入口才分页。
    var pageSize = 16;
    var pages = [];
    var dots = [];
    for (var page = 0; page < Math.ceil(items.length / pageSize); page++) {
      pages.push('<div class="nav-page" role="group" aria-label="第' + (page + 1) + '组导航">' + items.slice(page * pageSize, (page + 1) * pageSize).join('') + '</div>');
      dots.push('<button type="button" class="nav-page-dot" data-nav-page="' + page + '" aria-label="切换到第' + (page + 1) + '组导航"></button>');
    }
    return '<button type="button" class="nav-collapse-toggle" data-nav="collapse" aria-controls="gameNavViewport" aria-expanded="true" aria-label="收起导航" title="收起导航"><span class="nav-collapse-icon" aria-hidden="true">‹</span></button>' +
      '<div id="gameNavViewport" class="nav-viewport" aria-label="' + (pages.length > 1 ? '左右滑动查看更多导航' : '功能导航') + '">' + pages.join('') + '</div>' +
      (pages.length > 1 ? '<div class="nav-pages" aria-label="导航分页">' + dots.join('') + '</div>' : '');
  }

  function showResourceDetail(key, name, icon, current, cap, rate, production, consumption) {
    var modal = document.createElement('div');
    modal.className = 'modal-mask';
    var percent = cap > 0 ? Math.min(100, Math.floor(current / cap * 100)) : 0;
    var isCapped = current >= cap;
    var detail = '';
    if (key === 'food') {
      detail =
        '<div class="res-detail-section-title">粮食流向</div>' +
        '<div class="res-detail-value"><span>农田生产</span><b class="positive">+' + G.fmt(production) + '/小时' + (isCapped ? ' (已达上限暂停增产)' : '') + '</b></div>' +
        '<div class="res-detail-value"><span>军队消耗</span><b class="neg">-' + G.fmt(consumption) + '/小时</b></div>' +
        '<div class="res-detail-net"><span>实际每小时净变化</span><b class="' + (rate < 0 ? 'neg' : 'positive') + '">' + (rate >= 0 ? '+' : '') + G.fmt(rate) + '/小时</b></div>' +
        '<div class="res-detail-tip">' + (isCapped ? '粮食储量已达上限，农田暂停额外增产并维持满额。若军队消耗大于产能，储量将持续减少。' : '粮食净变化 = 农田生产 − 军队消耗。净变化为负时，储量会持续减少。') + '</div>';
    } else if (key === 'gold') {
      var s = Core.state || {};
      var mayor = Core.getOfficerByRole ? Core.getOfficerByRole('mayor') : null;
      var financeBonus = Core.mayorSkillBonus ? Core.mayorSkillBonus('finance') : 0;
      var taxRate = Math.floor(Core.civilianPopulation() * ((s.tax != null ? s.tax : 30) / 100) * (1 + (mayor ? mayor.knowledge / 100 : 0)) * (1 + financeBonus) * 2);
      var salary = Core.officerSalaryPerHour ? Core.officerSalaryPerHour() : 0;
      detail =
        '<div class="res-detail-section-title">黄金收支</div>' +
        '<div class="res-detail-value"><span>平民税收</span><b class="positive">+' + G.fmt(taxRate) + '/小时' + (isCapped ? ' (已达上限暂停增加)' : '') + '</b></div>' +
        (salary > 0 ? '<div class="res-detail-value"><span>军官薪资</span><b class="neg">-' + G.fmt(salary) + '/小时</b></div>' : '') +
        '<div class="res-detail-net"><span>实际每小时净变化</span><b class="' + (rate < 0 ? 'neg' : 'positive') + '">' + (rate >= 0 ? '+' : '') + G.fmt(rate) + '/小时</b></div>' +
        '<div class="res-detail-tip">' + (isCapped ? '黄金已达储量上限（999,999），税收停止增加并维持满额。' : '黄金主要来源于平民税收，受税率与市长知识、理财技能影响。') + '</div>';
    } else {
      var bType = key === 'steel' ? 'refinery' : (key === 'oil' ? 'oilfield' : 'raremine');
      var baseProd = Core.produceOf ? Core.produceOf(bType) : (production || 0);
      if (isCapped) {
        detail =
          '<div class="res-detail-value"><span>理论产能</span><b>+' + G.fmt(baseProd) + '/小时</b></div>' +
          '<div class="res-detail-value"><span>实际每小时净产出</span><b class="highlight">0/小时 (储量已满)</b></div>' +
          '<div class="res-detail-tip">当前储量已达上限，建筑已暂停生产。升级资源建筑可提高储量上限与产量。</div>';
      } else {
        detail = '<div class="res-detail-value"><span>每小时净产出</span><b class="' + (rate < 0 ? 'neg' : '') + '">' + (rate >= 0 ? '+' : '') + G.fmt(rate) + '/小时</b></div>';
      }
    }
    var titleIcon = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(icon)
      ? '<img class="res-icon-img" src="' + icon + '" alt="' + name + '" style="width:20px;height:20px;margin-right:6px;vertical-align:middle;display:inline-block;" />'
      : (icon ? '<span style="margin-right:6px;">' + icon + '</span>' : '');
    modal.innerHTML =
      '<div class="modal-card" style="max-width:380px">' +
      '<div class="modal-title" style="display:flex;align-items:center;justify-content:center;">' + titleIcon + name + '详情</div>' +
      '<div class="modal-body">' +
      '<div class="res-detail-value"><span>当前储量</span><b>' + G.fmt(current) + '</b></div>' +
      '<div class="res-detail-value"><span>资源上限</span><b>' + G.fmt(cap) + '</b></div>' +
      detail +
      '<div class="res-detail-bar"><span style="width:' + percent + '%"></span></div>' +
      '<div class="res-detail-percent">储量使用率 ' + percent + '%</div>' +
      '</div>' +
      '<div class="modal-foot"><button class="btn ok" id="closeResourceDetail">关闭</button></div>' +
      '</div>';
    document.body.appendChild(modal);
    modal.querySelector('#closeResourceDetail').onclick = function () { modal.remove(); };
    modal.addEventListener('click', function (e) { if (e.target === modal) modal.remove(); });
  }

  function showPopulationDetailModal() {
    var modal = document.createElement('div');
    modal.className = 'modal-mask';

    modal.innerHTML =
      '<div class="modal-card pop-detail-modal" style="max-width:440px">' +
      '<div class="modal-title">👥 平民与民情政务</div>' +
      '<div class="modal-body">' +
      '<div class="pop-detail-summary">' +
      '<div class="pop-stat-box">' +
      '<div class="pop-stat-head">' +
      '<span class="pop-stat-label">当前平民</span>' +
      '<button class="btn ok sm pop-call-btn" id="popRecruitQuickBtn" title="消耗人口动员令立即召集平民">召集</button>' +
      '</div>' +
      '<span class="pop-stat-val" id="popCivilianVal">-</span>' +
      '</div>' +
      '<div class="pop-stat-box"><span class="pop-stat-label">兵舍标称容量</span><span class="pop-stat-val" id="popCapVal">-</span></div>' +
      '<div class="pop-stat-box"><span class="pop-stat-label">民心容纳上限</span><span class="pop-stat-val highlight" id="popEffCapVal">-</span></div>' +
      '<div class="pop-stat-box"><span class="pop-stat-label">自然增长速度</span><span class="pop-stat-val positive" id="popGrowthVal">-</span></div>' +
      '</div>' +

      '<div class="pop-recruit-section">' +
      '<div class="pop-section-title">' +
      '<span>👥 召集人口与平民动员</span>' +
      '<span class="pop-recruit-badge" id="popOrderCountBadge">拥有动员令: 0 张</span>' +
      '</div>' +
      '<div class="pop-recruit-card">' +
      '<div class="pop-recruit-info">' +
      '<div class="pop-recruit-desc">消耗军需物资库中的【人口动员令】，立即自四方动员 <b class="positive">+500</b> 空闲平民进城（受集结兵舍容量限制）。</div>' +
      '</div>' +
      '<button class="btn ok pop-recruit-action-btn" id="popRecruitBtn">立即召集 (+500)</button>' +
      '</div>' +
      '</div>' +

      '<div class="pop-sentiment-section">' +
      '<div class="pop-bar-header">' +
      '<span>❤️ 民心值：<b id="popMoraleNum">70</b> / 100</span>' +
      '<span class="pop-status-badge" id="popMoraleBadge">安居乐业</span>' +
      '</div>' +
      '<div class="pop-progress-bar morale-bar"><div class="pop-progress-fill" id="popMoraleFill" style="width:70%"></div></div>' +
      '<div class="pop-bar-header" style="margin-top:10px">' +
      '<span>🔥 民怨值：<b id="popResentNum">0</b> / 100</span>' +
      '<span class="pop-status-badge resentment-badge" id="popResentBadge">风平浪静</span>' +
      '</div>' +
      '<div class="pop-progress-bar resentment-bar"><div class="pop-progress-fill" id="popResentFill" style="width:0%"></div></div>' +
      '<div class="pop-bar-hint">民心决定城市的实际人口容纳率与增长速度；长期重税(>50%)滋生民怨并压抑民心。</div>' +
      '</div>' +

      '<div class="pop-tax-section">' +
      '<div class="pop-section-title">' +
      '<span>💰 调节城市税率</span>' +
      '<span class="pop-current-tax">当前税率：<b id="popCurTaxText">30%</b></span>' +
      '</div>' +
      '<div class="pop-slider-container">' +
      '<div class="pop-slider-labels">' +
      '<span>0% (免税)</span>' +
      '<span id="popSliderNum" class="slider-num-callout">30%</span>' +
      '<span>100% (重税)</span>' +
      '</div>' +
      '<div class="recruit-slider-wrap">' +
      '<input type="range" class="recruit-slider tax-range-slider" id="popTaxSlider" min="0" max="100" step="1" value="30">' +
      '</div>' +
      '</div>' +
      '<div class="pop-tax-preview">' +
      '<div class="pop-preview-row"><span>预计黄金税收：</span><b class="positive" id="popPrevGold">+60/h</b></div>' +
      '<div class="pop-preview-row"><span>预期目标民心：</span><b id="popPrevMorale">70</b></div>' +
      '<div class="pop-preview-row"><span>预期民心容纳：</span><b id="popPrevCap">100 / 100</b></div>' +
      '<div class="pop-tax-warning" id="popTaxWarn">⚖️ 标准税赋：民心平稳，黄金与人口保持平衡发展。</div>' +
      '</div>' +
      '<button class="btn ok pop-action-btn" id="popSaveTaxBtn">应用税率 (30%)</button>' +
      '</div>' +

      '<div class="pop-appease-section">' +
      '<div class="pop-section-title">🕊️ 开仓赈民与安抚民情</div>' +
      '<div class="appease-card-grid">' +
      '<div class="appease-card">' +
      '<div class="appease-card-head">' +
      '<span class="appease-card-name">' + G.resourceIconHtml('gold') + ' 黄金赈民</span>' +
      '<span class="appease-card-effect">民心 +10 · 民怨 -5</span>' +
      '</div>' +
      '<div class="appease-card-desc">开仓放粮赈济平民，抚慰民情。</div>' +
      '<div class="appease-card-cost">消耗：<span id="appeaseGoldCost">1,000</span> 黄金 <small id="appeaseGoldRemain"></small></div>' +
      '<button class="btn sub appease-btn" id="popAppeaseGoldBtn">开仓赈灾</button>' +
      '</div>' +
      '<div class="appease-card highlight">' +
      '<div class="appease-card-head">' +
      '<span class="appease-card-name">💎 钻石特赦</span>' +
      '<span class="appease-card-effect">民心 +25 · 民怨 -20</span>' +
      '</div>' +
      '<div class="appease-card-desc">大赦天下并重金赏赐，迅速平息怨愤。</div>' +
      '<div class="appease-card-cost">消耗：<span>20</span> 钻石 <small id="appeaseDiamondRemain"></small></div>' +
      '<button class="btn ok appease-btn" id="popAppeaseDiamondBtn">特赦犒赏</button>' +
      '</div>' +
      '</div>' +
      '</div>' +
      '</div>' +
      '<div class="modal-foot">' +
      '<button class="btn sub" id="closePopDetail">关闭</button>' +
      '</div>' +
      '</div>';

    document.body.appendChild(modal);

    function updateTaxPreview(taxVal) {
      var s = Core.state || {};
      var civ = Core.civilianPopulation();
      var cap = Core.populationCapacity();
      var resent = Core.resentment();
      var mayor = Core.getOfficerByRole('mayor');
      var mayorKnow = (mayor && mayor.knowledge) ? mayor.knowledge : 0;

      modal.querySelector('#popSliderNum').textContent = taxVal + '%';
      if (sliderEl) sliderEl.style.setProperty('--p', taxVal + '%');

      var prevFinanceBonus = Core.mayorSkillBonus ? Core.mayorSkillBonus('finance') : 0;
      var prevGold = Math.round(civ * (taxVal / 100.0) * (1 + mayorKnow / 100.0) * (1 + prevFinanceBonus) * 2);
      modal.querySelector('#popPrevGold').textContent = '+' + G.fmt(prevGold) + '/h';

      var targetMorale = Math.max(0, Math.min(100, 100 - taxVal - resent));
      modal.querySelector('#popPrevMorale').textContent = targetMorale;

      var targetEffCap = cap <= 0 ? 0 : Math.max(10, Math.round(cap * Math.min(1.0, targetMorale / 70.0)));
      var pct = cap > 0 ? Math.round(targetEffCap / cap * 100) : 100;
      modal.querySelector('#popPrevCap').textContent = G.fmt(targetEffCap) + ' / ' + G.fmt(cap) + ' (' + pct + '%)';

      var warnEl = modal.querySelector('#popTaxWarn');
      if (taxVal > 50) {
        warnEl.className = 'pop-tax-warning danger';
        warnEl.textContent = '⚠️ 重税苛敛：民心将持续下挫，每小时滋生民怨，平民将逃离城市！';
      } else if (taxVal <= 20) {
        warnEl.className = 'pop-tax-warning positive';
        warnEl.innerHTML = G.resourceIconHtml('food') + ' 轻徭薄赋：民心大幅上升，民怨加速消退，平民快速增长！';
      } else {
        warnEl.className = 'pop-tax-warning';
        warnEl.textContent = '⚖️ 标准税赋：民心平稳，黄金税收与人口保持平衡发展。';
      }

      var saveBtn = modal.querySelector('#popSaveTaxBtn');
      if (saveBtn) {
        saveBtn.textContent = '应用税率 (' + taxVal + '%)';
      }
    }

    function refreshModal() {
      var s = Core.state || {};
      var r = s.resources || {};
      var civ = Core.civilianPopulation();
      var cap = Core.populationCapacity();
      var effCap = Core.effectiveCapacity();
      var growth = Core.populationGrowthPerHour();
      var morale = Core.morale();
      var resent = Core.resentment();
      var curTax = Core.tax();

      modal.querySelector('#popCivilianVal').textContent = G.fmt(civ);
      modal.querySelector('#popCapVal').textContent = G.fmt(cap);
      var growthEl = modal.querySelector('#popGrowthVal');
      if (growthEl) {
        if (civ >= effCap && growth === 0) {
          growthEl.textContent = '+0/h (已达上限)';
          growthEl.className = 'pop-stat-val';
        } else {
          growthEl.textContent = (growth >= 0 ? '+' : '') + G.fmt(growth) + '/h';
          growthEl.className = 'pop-stat-val positive';
        }
      }

      // Morale
      modal.querySelector('#popMoraleNum').textContent = morale;
      modal.querySelector('#popMoraleFill').style.width = Math.min(100, Math.max(0, morale)) + '%';
      var moraleBadge = modal.querySelector('#popMoraleBadge');
      if (morale >= 80) {
        moraleBadge.className = 'pop-status-badge badge-high';
        moraleBadge.textContent = '民心归附';
      } else if (morale >= 60) {
        moraleBadge.className = 'pop-status-badge badge-mid';
        moraleBadge.textContent = '安居乐业';
      } else if (morale >= 40) {
        moraleBadge.className = 'pop-status-badge badge-warn';
        moraleBadge.textContent = '民有怨言';
      } else {
        moraleBadge.className = 'pop-status-badge badge-danger';
        moraleBadge.textContent = '民不聊生';
      }

      // Resentment
      modal.querySelector('#popResentNum').textContent = resent;
      modal.querySelector('#popResentFill').style.width = Math.min(100, Math.max(0, resent)) + '%';
      var resentBadge = modal.querySelector('#popResentBadge');
      if (resent <= 0) {
        resentBadge.className = 'pop-status-badge resentment-badge badge-calm';
        resentBadge.textContent = '风平浪静';
      } else if (resent <= 30) {
        resentBadge.className = 'pop-status-badge resentment-badge badge-warn';
        resentBadge.textContent = '暗流涌动';
      } else if (resent <= 60) {
        resentBadge.className = 'pop-status-badge resentment-badge badge-danger';
        resentBadge.textContent = '民怨沸腾';
      } else {
        resentBadge.className = 'pop-status-badge resentment-badge badge-rebel';
        resentBadge.textContent = '暴动在即';
      }

      // Tax
      modal.querySelector('#popCurTaxText').textContent = curTax + '%';
      var slider = modal.querySelector('#popTaxSlider');
      if (slider && !slider._userInteracting) {
        slider.value = curTax;
        updateTaxPreview(curTax);
      }

      // Costs
      var goldCost = Math.max(1000, Math.min(10000, civ * 2));
      var currentGold = r.gold || 0;
      var currentDiamond = r.diamond || 0;
      modal.querySelector('#appeaseGoldCost').textContent = G.fmt(goldCost);
      modal.querySelector('#appeaseGoldRemain').textContent = '(余: ' + G.fmt(currentGold) + ')';
      modal.querySelector('#appeaseDiamondRemain').textContent = '(余: ' + G.fmt(currentDiamond) + ')';

      // Population Order
      var popOrders = (s.items && s.items.populationOrder) || 0;
      var badgeEl = modal.querySelector('#popOrderCountBadge');
      if (badgeEl) {
        badgeEl.textContent = '拥有动员令: ' + popOrders + ' 张';
        if (popOrders > 0) {
          badgeEl.className = 'pop-recruit-badge has-items';
        } else {
          badgeEl.className = 'pop-recruit-badge';
        }
      }
      var mainRecruitBtn = modal.querySelector('#popRecruitBtn');
      if (mainRecruitBtn) {
        if (popOrders > 0) {
          mainRecruitBtn.textContent = '立即召集 (+500)';
          mainRecruitBtn.className = 'btn ok pop-recruit-action-btn';
        } else {
          mainRecruitBtn.textContent = '获取动员令 (去商城)';
          mainRecruitBtn.className = 'btn sub pop-recruit-action-btn';
        }
      }
      var quickBtnEl = modal.querySelector('#popRecruitQuickBtn');
      if (quickBtnEl) {
        quickBtnEl.textContent = popOrders > 0 ? '召集(+500)' : '召集';
      }

      var goldBtn = modal.querySelector('#popAppeaseGoldBtn');
      if (currentGold < goldCost) {
        goldBtn.disabled = true;
        goldBtn.classList.add('disabled');
      } else {
        goldBtn.disabled = false;
        goldBtn.classList.remove('disabled');
      }

      var diaBtn = modal.querySelector('#popAppeaseDiamondBtn');
      if (currentDiamond < 20) {
        diaBtn.disabled = true;
        diaBtn.classList.add('disabled');
      } else {
        diaBtn.disabled = false;
        diaBtn.classList.remove('disabled');
      }
    }

    function handleRecruitPopulation() {
      var civ = Core.civilianPopulation();
      var cap = Core.populationCapacity();
      if (civ >= cap) {
        G.toast('集结兵舍容量已达上限 (' + G.fmt(civ) + '/' + G.fmt(cap) + ')，请先扩建集结兵舍！');
        return;
      }
      var s = Core.state || {};
      var items = s.items || {};
      var popOrders = items.populationOrder || 0;
      if (popOrders <= 0) {
        if (confirm('仓库中暂无【人口动员令】（每张使用可立即增加500空闲平民）。\n是否立即前往商城购买？')) {
          modal.remove();
          Game.go('shop');
        }
        return;
      }

      var quickBtn = modal.querySelector('#popRecruitQuickBtn');
      var mainBtn = modal.querySelector('#popRecruitBtn');
      if (quickBtn) quickBtn.disabled = true;
      if (mainBtn) mainBtn.disabled = true;

      G.API.depotUse('populationOrder', null, null).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '召集失败');
          if (resp.state) G.API.applyState(resp.state);
          return;
        }
        G.toast((resp && resp.message) || '👥 召集成功，空闲平民 +500！');
        if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
        if (Core.route === 'home') Core.render();
        refreshModal();
      }).catch(function (err) {
        G.toast((err && err.message) || '召集失败');
      }).finally(function () {
        if (quickBtn) quickBtn.disabled = false;
        if (mainBtn) mainBtn.disabled = false;
      });
    }

    var quickRecruitBtn = modal.querySelector('#popRecruitQuickBtn');
    if (quickRecruitBtn) quickRecruitBtn.onclick = handleRecruitPopulation;

    var mainRecruitBtn = modal.querySelector('#popRecruitBtn');
    if (mainRecruitBtn) mainRecruitBtn.onclick = handleRecruitPopulation;

    var slider = modal.querySelector('#popTaxSlider');
    slider.oninput = function () {
      slider._userInteracting = true;
      updateTaxPreview(parseInt(this.value, 10) || 0);
    };
    slider.onchange = function () {
      slider._userInteracting = false;
    };

    modal.querySelector('#popSaveTaxBtn').onclick = function () {
      var val = parseInt(slider.value, 10) || 0;
      var btn = this;
      btn.disabled = true;
      G.API.setTax(val).then(function () {
        G.toast('税率已成功设置为 ' + val + '%');
        if (Core.route === 'home') Core.render();
        refreshModal();
      }).catch(function (err) {
        G.toast(err && err.message ? err.message : '设置税率失败');
      }).finally(function () {
        btn.disabled = false;
      });
    };

    modal.querySelector('#popAppeaseGoldBtn').onclick = function () {
      var btn = this;
      btn.disabled = true;
      G.API.appease('gold').then(function (res) {
        G.toast(res && res.message ? res.message : '安抚民心成功！');
        if (Core.route === 'home') Core.render();
        refreshModal();
      }).catch(function (err) {
        G.toast(err && err.message ? err.message : '安抚失败');
      }).finally(function () {
        btn.disabled = false;
      });
    };

    modal.querySelector('#popAppeaseDiamondBtn').onclick = function () {
      var btn = this;
      btn.disabled = true;
      G.API.appease('diamond').then(function (res) {
        G.toast(res && res.message ? res.message : '特赦与犒赏成功！');
        if (Core.route === 'home') Core.render();
        refreshModal();
      }).catch(function (err) {
        G.toast(err && err.message ? err.message : '特赦失败');
      }).finally(function () {
        btn.disabled = false;
      });
    };

    window.__refreshPopDetailModal = refreshModal;
    var closeModal = function () {
      window.__refreshPopDetailModal = null;
      modal.remove();
    };
    modal.querySelector('#closePopDetail').onclick = closeModal;
    modal.addEventListener('click', function (e) { if (e.target === modal) closeModal(); });

    refreshModal();
  }

  Core.views.home = function (v) {
    var s = Core.state;
    var r = s.resources;
    var mayor = Core.getOfficerByRole('mayor');
    var cmd = Core.getOfficerByRole('commander');
    var marches = s.world.marches || [];
    var incoming = s.world.incoming || [];
    var alertCount = marches.length + incoming.length;
    var reportsCount = (s.reports || []).length;

    var cityName = s.player.cityName || '新城市';

    var h = '';

    h += '<div class="city-head">';
    h += '<div class="city-row">';
    h += '<div class="city-cell"><span class="city-label">城市</span><b>' + G.escapeHtml(cityName) + '</b><span class="city-edit" onclick="Game.Main.toggleEditCity()">✎</span></div>';
    var cs = Core.cityStatusText();
    var cityPos = s.world.cityPos || s.world.pos;
    h += '<div class="city-cell city-coord"><span class="city-label">坐标</span>(' + cityPos.x + ',' + cityPos.y + ')</div>';
    var statusClass = cs.status ? ' ' + cs.status : ' peace';
    h += '<span class="city-status-tag' + statusClass + '" data-status="' + (cs.status || 'peace') + '">' + cs.text + '</span>';
    h += '</div>';
    h += '<div id="editCityBox" class="edit-profile-box" style="display:none">';
    h += '<div class="edit-row"><label>城市名</label><input id="epCityName" class="qty" style="width:100%" maxlength="12" value="' + G.escapeHtml(s.player.cityName || '新城市') + '" placeholder="留空则用默认名称"></div>';
    h += '<div class="btn-row" style="margin-top:4px"><button class="btn ok sm" onclick="Game.Main.saveCity()">保存</button><button class="btn sm" onclick="Game.Main.toggleEditCity()">取消</button></div>';
    h += '</div>';
    h += '</div>';

    h += '<div id="homeAlertsWrap">';
    if (alertCount > 0) {
      h += '<div class="home-alert" onclick="Game.go(\'alerts\')">⚔ 军情警讯 ' + alertCount + ' 起 (行军' + marches.length + '/来袭' + incoming.length + ') ></div>';
    }
    if (reportsCount > 0) {
      h += '<div class="home-alert rep" onclick="Game.go(\'reports\')">📋 战报 ' + reportsCount + ' 条 ></div>';
    }
    h += '</div>';

    var netFood = Core.produceOf('farm') - Core.foodPerHour();
    var netSteel = Core.produceOf('refinery');
    var netOil = Core.produceOf('oilfield');
    var netRare = Core.produceOf('raremine');
    var financeBonus = Core.mayorSkillBonus ? Core.mayorSkillBonus('finance') : 0;
    var goldRate = Math.floor(Core.civilianPopulation() * (s.tax / 100) * (1 + (mayor ? mayor.knowledge / 100 : 0)) * (1 + financeBonus) * 2);
    var cap = Core.capacity();
    var caps = { food: cap.food, steel: cap.steel, oil: cap.oil, rare: cap.rare, gold: 999999 };
    var nets = { food: netFood, steel: netSteel, oil: netOil, rare: netRare, gold: goldRate };

    // 各模块单独生成，再按当前玩家保存的顺序组合。
    var fixedHtml = h;
    var modules = {};
    h = '';
    h += renderOfficerSummaryCard();
    modules.officers = h;
    h = '';

    // 军队总览（活动与任务块已迁移到顶部菜单"任务"页内）
    h += '<div class="zone-head">' + homeModuleHandle('army') + '<span class="zone-title">🪖 军队总览</span><span class="zone-sub">带兵上限 ' + G.fmt(Core.armyCap()) + '</span><button type="button" class="army-summary-expand" title="全屏展开军队总览" aria-label="全屏展开军队总览" onclick="Game.MainView.showArmySummaryFullscreen()"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5"/></svg></button><span class="army-dispatch-go zone-head-action" role="button" tabindex="0" onclick="Game.go(\'world\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.go(\'world\');event.preventDefault();}">去出征 &gt;</span></div>';
    h += '<div class="army-summary" role="region" tabindex="0" aria-label="军队总览，三排排列，左右滑动查看全部兵种">';
    h += renderArmySummaryList();
    h += '</div>';
    h += '<div class="army-summary-foot" onclick="Game.go(\'army\')">';
    var totArmy = 0;
    var sArmy = (Core.state && Core.state.army) || {};
    for (var ak in sArmy) totArmy += sArmy[ak] || 0;
    var cmd2 = Core.getOfficerByRole('commander');
    h += '<span class="army-summary-strength">总兵力 <b class="home-army-tot" style="color:var(--accent)">' + G.fmt(totArmy) + '</b> / ' + G.fmt(Core.armyCap()) + '</span>';
    h += '<span class="army-summary-commander"><span class="army-summary-commander-label">指挥官:</span> <b>' + (cmd2 ? G.escapeHtml(cmd2.name) : '未任命') + '</b></span>';
    h += '<span class="army-go">详情 &gt;</span>';
    h += '</div>';
    modules.army = h;
    h = '';

    h += '<div class="zone-head">' + homeModuleHandle('resources') + '<span class="zone-title">资源</span></div>';
    h += '<div class="res-grid">';
    var resKeys = G.Constants.resourceKeysWithGold;
    for (var ri = 0; ri < resKeys.length; ri++) {
      var rk = resKeys[ri];
      var rinfo = D.resources[rk];
      var cur = r[rk] || 0;
      var maxR = caps[rk] || 999999;
      var net = Core.resourceNetRate ? Core.resourceNetRate(rk) : (nets[rk] || 0);
      var sign = net >= 0 ? '+' : '';
      var isCapped = cur >= maxR;
      var titleText = (rinfo.name || rk) + ' 当前: ' + G.fmt(cur) + (isCapped ? ' (已达上限' + (net === 0 ? '，停止产出' : '，' + sign + G.fmt(net) + '/h') + ')' : ' (' + sign + G.fmt(net) + '/h)');
      var rateClass = net < 0 ? ' neg' : (isCapped && net === 0 ? ' capped' : '');
      h += '<div class="res-card" data-res-card="' + rk + '" role="button" tabindex="0" title="' + titleText + '" onclick="Game.Main.showResourceDetail(\'' + rk + '\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.Main.showResourceDetail(\'' + rk + '\');event.preventDefault();}">';
      var iconHtml = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(rinfo.icon)
        ? '<img class="res-icon-img" src="' + rinfo.icon + '" alt="' + rinfo.name + '"/>'
        : '<span class="res-icon ri-' + rk + '">' + rinfo.icon + '</span>';
      h += '<div class="res-summary">' + iconHtml + '<span class="res-name">' + rinfo.name + ':</span><span class="res-main"><span class="res-cur">' + G.fmt(cur) + '</span></span><span class="res-rate' + rateClass + '">' + sign + G.fmt(net) + '/h</span></div>';
      h += '</div>';
    }
    var popIcon = '<img class="res-icon-img" src="img/resources/models/pop.webp" alt="平民"/>';
    var curMorale = Core.morale();
    var curResent = Core.resentment();
    var civPop = Core.civilianPopulation();
    var popCap = Core.populationCapacity();
    var effCap = Core.effectiveCapacity ? Core.effectiveCapacity() : popCap;
    var popGrowth = Core.populationGrowthPerHour();
    var isPopCapped = civPop >= effCap;
    var popRateText = (popGrowth >= 0 ? '+' : '') + G.fmt(popGrowth) + '/h';
    var popRateClass = popGrowth < 0 ? ' neg' : (isPopCapped && popGrowth === 0 ? ' capped' : '');
    var popTitle = '平民 当前: ' + G.fmt(civPop) + '/' + G.fmt(popCap) + (isPopCapped ? ' (已达上限' + (popGrowth === 0 ? '，停止增长' : '，' + popRateText) + ')' : ' (' + popRateText + ')');
    h += '<div class="res-card" data-res-card="pop" role="button" tabindex="0" title="' + popTitle + '" onclick="Game.Main.showPopulationDetail()" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.Main.showPopulationDetail();event.preventDefault();}">';
    h += '<div class="res-summary">' + popIcon + '<span class="res-name">平民:</span><span class="res-main"><span class="res-cur">' + G.fmt(civPop) + '</span><span class="res-slash">/</span><span class="res-max">' + G.fmt(popCap) + '</span></span><span class="res-rate' + popRateClass + '">' + popRateText + '</span></div>';
    h += '<div class="d">可征召 ' + G.fmt(Core.popFree()) + ' · 民心 ' + curMorale + (curResent > 0 ? ' <span style="color:#d9534f">(怨' + curResent + ')</span>' : '') + '</div>';
    h += '</div>';
    h += '</div>';
    modules.resources = h;
    h = '';

    // —— 世界聊天频道 ——
    h += '<div class="zone-head">' + homeModuleHandle('chat') + '<span class="zone-title">📡 世界聊天</span><span class="zone-sub">实时通联</span></div>';
    h += '<div class="chat-terminal">';
    h += '<div class="chat-term-header">';
    h += '<div class="term-header-left">';
    h += '<span class="term-led"></span>';
    h += '<span class="term-title">COMM-LINK // 战术公频</span>';
    h += '</div>';
    h += '<div class="term-header-right">';
    h += '<span class="term-freq">CH-01 · 144.80 MHz</span>';
    h += '<span class="term-tag">ONLINE</span>';
    h += '</div>';
    h += '</div>';
    h += '<div class="chat-box" id="worldChatBox">';
    var msgs = (G.Chat && G.Chat.recent) ? G.Chat.recent(20) : [];
    for (var mi = 0; mi < msgs.length; mi++) {
      if (G.Chat && G.Chat.renderMessageHtml) {
        h += G.Chat.renderMessageHtml(msgs[mi]);
      } else {
        var m = msgs[mi];
        var safeName = String(m.username == null ? '玩家' : m.username)
          .replace(/[&<>"']/g, function (c) {
            return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
          });
        var safeContent = String(m.content == null ? '' : m.content)
          .replace(/[&<>"']/g, function (c) {
            return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
          });
        var isSelfMsg = (G.Chat && G.Chat.isSelf) ? G.Chat.isSelf(m) : false;
        h += '<div class="chat-msg' + (isSelfMsg ? ' chat-msg-self' : '') + '" data-type="' + (m.type || 'player') + '"' + (isSelfMsg ? ' data-self="1"' : '') + '>' +
          '<span class="chat-time">[' + G.fmtChatTime(m.ts) + ']</span>' +
          '<span class="chat-tag tag-world">[世界]</span>' +
          '<span class="chat-sender">' + safeName + ':</span>' +
          '<span class="chat-content' + (isSelfMsg ? ' chat-content-self' : '') + '">' + safeContent + '</span>' +
          '</div>';
      }
    }
    h += '</div>';

    var cd = (G.Chat && G.Chat.getCooldown) ? G.Chat.getCooldown() : 0;
    var chatReadOnly = !G.Chat || !G.Chat.canSend || !G.Chat.canSend();
    h += '<div class="chat-input-bar">';
    h += '<span class="chat-prompt">&gt;</span>';
    h += '<input class="chat-input" id="worldChatInput" type="text" maxlength="80" placeholder="' + (chatReadOnly ? '声望达到 10,000 后可发言' : '输入电报简讯... (最多 80 字，Enter 发送)') + '"' + (chatReadOnly ? ' disabled' : '') + ' autocomplete="off" onkeydown="if(event.key===&quot;Enter&quot;){Game.Main.sendChat();}"/>';
    h += '<button class="chat-send' + (chatReadOnly || cd > 0 ? ' disabled' : '') + '" id="worldChatSendBtn"' + (chatReadOnly || cd > 0 ? ' disabled' : '') + ' onclick="Game.Main.sendChat()">' + (chatReadOnly ? '只读' : (cd > 0 ? cd + 's' : '发送')) + '</button>';
    h += '</div>';
    h += '</div>';

    modules.chat = h;
    v.innerHTML = fixedHtml + renderHomeModules(modules);
    setupHomeModuleSorting(v.querySelector && v.querySelector('#homeModuleList'));
    if (s.player && s.player.homeModuleOrder == null &&
        !(homeModulePending && homeModulePending.playerId === s.player.id)) {
      var legacyOrder = legacyHomeModuleOrder();
      if (legacyOrder) persistHomeModuleOrder(legacyOrder);
    }
    var chatBoxEl = document.getElementById('worldChatBox');
    if (chatBoxEl) chatBoxEl.scrollTop = chatBoxEl.scrollHeight;
  };

  Core.views.settings = function (v) {
    var h = '';
    h += '<div class="title">- 设置 -</div>';

    h += '<div class="zone-head"><span class="zone-title">游戏设置</span></div>';
    h += '<div class="panel">';
    var curTheme = (G.Theme && G.Theme.get) ? G.Theme.get() : 'blue-white-classic';
    h += '<div class="btn-row" style="margin-bottom:8px;align-items:center;">';
    h += '<span style="flex:1;font-size:14px">🎨 界面风格</span>';
    h += '<select id="themeSelector" style="padding:4px 8px;font-size:13px;border-radius:4px;" onchange="if(Game.Theme)Game.Theme.set(this.value)">';
    if (G.Theme && G.Theme.THEMES) {
      for (var ti = 0; ti < G.Theme.THEMES.length; ti++) {
        var tObj = G.Theme.THEMES[ti];
        var isSel = tObj.id === curTheme ? ' selected' : '';
        h += '<option value="' + tObj.id + '"' + isSel + '>' + tObj.name + '</option>';
      }
    } else {
      h += '<option value="blue-white-classic">战术经典蓝白风（推荐）</option>';
      h += '<option value="paper">战术公文沙盘风</option>';
      h += '<option value="dark">战术夜航终端黑</option>';
    }
    h += '</select>';
    h += '</div>';
    h += '</div>';

    if (G.Sound) h += G.Sound.renderSettings();

    h += '<div class="zone-head"><span class="zone-title">账号与安全</span></div>';
    h += '<div class="panel">';
    if (G.API && G.API.isLoggedIn()) {
      h += '<div class="d">登录账号: <b>' + G.escapeHtml(G.API.getUsername() || '未知') + '</b></div>';
      h += '<div class="d">游戏进度由服务器自动保存</div>';
      h += '<div class="btn-row" style="margin-top:6px">';
      h += '<button class="btn sm" onclick="Game.Main.logout()">切换账号</button>';
      if (G.Protection && G.Protection.data && G.Protection.data.enabled !== false) {
        h += '<button class="btn sm" onclick="Game.Protection.open()">实名、防沉迷与家长监护</button>';
      }
      h += '</div>';
    } else if (G.Main && G.Main.guestMode) {
      h += '<div class="d">当前模式: <b style="color:var(--muted)">游客模式</b></div>';
      h += '<div class="d">请登录账号继续游戏</div>';
      h += '<div class="btn-row" style="margin-top:6px">';
      h += '<button class="btn sm ok" onclick="Game.go(\'login\')">登录/注册账号</button>';
      h += '</div>';
    } else {
      h += '<div class="d">未登录</div>';
      h += '<div class="btn-row" style="margin-top:6px">';
      h += '<button class="btn sm ok" onclick="Game.go(\'login\')">登录</button>';
      h += '</div>';
    }
    h += '</div>';

    h += '<div class="zone-head"><span class="zone-title">关于</span></div>';
    h += '<div class="panel">';
    h += '<div class="d">山河远征录 - 文字战争策略游戏</div>';
    h += '<div class="d">版本: 1.0.0</div>';
    h += '</div>';

    if (G.API && G.API.isLoggedIn()) {
      h += '<div style="margin-top:16px">';
      h += '<button class="btn warn" style="width:100%;padding:12px;font-size:16px;color:#fff;background:var(--danger);border:0;border-radius:8px" onclick="Game.Main.logout(\'exit\')">退出登录</button>';
      h += '</div>';

      if (!(G.Main && G.Main.guestMode) && (G.API.getUsername() || '').indexOf('游客_') !== 0) {
        h += '<div class="zone-head" style="margin-top:18px;color:var(--danger)"><span class="zone-title">危险操作</span></div>';
        h += '<div class="panel" style="border-left:3px solid var(--danger)">';
        h += '<div class="d" style="color:var(--danger)">注销账号</div>';
        h += '<div class="d" style="font-size:12px;color:var(--muted)">';
        h += '申请后进入恢复期，到期将永久清理游戏进度。查看详情后需验证密码并确认操作。';
        h += '</div>';
        h += '<div class="btn-row" style="margin-top:6px">';
        h += '<button class="btn sm account-delete-entry" onclick="Game.Main.openDisableAccount()">注销账号</button>';
        h += '</div>';
        h += '</div>';
      }
    }

    h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
    v.innerHTML = h;
  };

  var PRESET_AVATARS = G.Constants.presetAvatars;

  function getCurrentAvatar() {
    return Core.getCurrentAvatar();
  }

  function getMilitaryRankTitle(prestige) {
    if (G.getMilitaryRankInfo) {
      return G.getMilitaryRankInfo(prestige).name;
    }
    return '列兵';
  }

  function silentUpdateHome(tickData) {
    var v = document.getElementById('view');
    if (!v || Core.route !== 'home') return;
    var s = Core.state;
    if (!s) return;
    var r = s.resources || {};
    var mayor = Core.getOfficerByRole('mayor');

    // 1. 城市状态与坐标更新
    var statusTag = v.querySelector('.city-status-tag');
    if (statusTag) {
      var cs = Core.cityStatusText();
      var wantClass = 'city-status-tag ' + (cs.status || 'peace');
      if (statusTag.className !== wantClass) statusTag.className = wantClass;
      if (statusTag.getAttribute('data-status') !== (cs.status || 'peace')) {
        statusTag.setAttribute('data-status', cs.status || 'peace');
      }
      if (statusTag.textContent !== cs.text) statusTag.textContent = cs.text;
    }

    // 2. 军情与战报警讯增量更新
    var alertsWrap = document.getElementById('homeAlertsWrap');
    if (alertsWrap) {
      var marches = (s.world && s.world.marches) || [];
      var incoming = (s.world && s.world.incoming) || [];
      var alertCount = marches.length + incoming.length;
      var reportsCount = (s.reports || []).length;
      var alertHtml = '';
      if (alertCount > 0) {
        alertHtml += '<div class="home-alert" onclick="Game.go(\'alerts\')">⚔ 军情警讯 ' + alertCount + ' 起 (行军' + marches.length + '/来袭' + incoming.length + ') ></div>';
      }
      if (reportsCount > 0) {
        alertHtml += '<div class="home-alert rep" onclick="Game.go(\'reports\')">📋 战报 ' + reportsCount + ' 条 ></div>';
      }
      if (alertsWrap.innerHTML !== alertHtml) {
        alertsWrap.innerHTML = alertHtml;
      }
    }

    // 3. 总兵力更新
    var totArmyEl = v.querySelector('.home-army-tot');
    if (totArmyEl) {
      var totArmy = 0;
      var sArmy = s.army || {};
      for (var ak in sArmy) totArmy += sArmy[ak] || 0;
      var wantArmyText = G.fmt(totArmy);
      if (totArmyEl.textContent !== wantArmyText) totArmyEl.textContent = wantArmyText;
    }

    // 4. 资源卡片更新 (粮/钢/油/稀/金)
    var netFood = Core.produceOf('farm') - Core.foodPerHour();
    var netSteel = Core.produceOf('refinery');
    var netOil = Core.produceOf('oilfield');
    var netRare = Core.produceOf('raremine');
    var financeBonus = Core.mayorSkillBonus ? Core.mayorSkillBonus('finance') : 0;
    var goldRate = Math.floor(Core.civilianPopulation() * (s.tax / 100) * (1 + (mayor ? mayor.knowledge / 100 : 0)) * (1 + financeBonus) * 2);
    var cap = Core.capacity();
    var caps = { food: cap.food, steel: cap.steel, oil: cap.oil, rare: cap.rare, gold: 999999 };
    var nets = { food: netFood, steel: netSteel, oil: netOil, rare: netRare, gold: goldRate };
    var resKeys = G.Constants.resourceKeysWithGold;

    for (var ri = 0; ri < resKeys.length; ri++) {
      var rk = resKeys[ri];
      var card = v.querySelector('.res-card[data-res-card="' + rk + '"]');
      if (!card) continue;
      var cur = r[rk] || 0;
      var maxR = caps[rk] || 999999;
      var net = Core.resourceNetRate ? Core.resourceNetRate(rk) : (nets[rk] || 0);
      var sign = net >= 0 ? '+' : '';
      var isCapped = cur >= maxR;

      var curEl = card.querySelector('.res-cur');
      if (curEl) {
        var formattedCur = G.fmt(cur);
        if (curEl.textContent !== formattedCur) curEl.textContent = formattedCur;
      }

      var rateEl = card.querySelector('.res-rate');
      if (rateEl) {
        var rateText = sign + G.fmt(net) + '/h';
        if (rateEl.textContent !== rateText) rateEl.textContent = rateText;
        if (net < 0) {
          if (!rateEl.classList.contains('neg')) rateEl.classList.add('neg');
          if (rateEl.classList.contains('capped')) rateEl.classList.remove('capped');
        } else if (isCapped && net === 0) {
          if (rateEl.classList.contains('neg')) rateEl.classList.remove('neg');
          if (!rateEl.classList.contains('capped')) rateEl.classList.add('capped');
        } else {
          if (rateEl.classList.contains('neg')) rateEl.classList.remove('neg');
          if (rateEl.classList.contains('capped')) rateEl.classList.remove('capped');
        }
      }

      var rinfo = D.resources[rk] || {};
      card.title = (rinfo.name || rk) + ' 当前: ' + G.fmt(cur) + (isCapped ? ' (已达上限' + (net === 0 ? '，停止产出' : '，' + sign + G.fmt(net) + '/h') + ')' : ' (' + sign + G.fmt(net) + '/h)');
    }

    // 5. 平民卡片更新
    var popCard = v.querySelector('.res-card[data-res-card="pop"]');
    if (popCard) {
      var civ = Core.civilianPopulation();
      var pCap = Core.populationCapacity();
      var effCap = Core.effectiveCapacity ? Core.effectiveCapacity() : pCap;
      var growth = Core.populationGrowthPerHour();
      var curMorale = Core.morale();
      var curResent = Core.resentment();
      var isPopCapped = civ >= effCap;

      var popCur = popCard.querySelector('.res-cur');
      if (popCur) {
        var formattedCiv = G.fmt(civ);
        if (popCur.textContent !== formattedCiv) popCur.textContent = formattedCiv;
      }

      var popMax = popCard.querySelector('.res-max');
      if (popMax) {
        var formattedCap = G.fmt(pCap);
        if (popMax.textContent !== formattedCap) popMax.textContent = formattedCap;
      }

      var popRateText = (growth >= 0 ? '+' : '') + G.fmt(growth) + '/h';
      var popRate = popCard.querySelector('.res-rate');
      if (popRate) {
        if (popRate.textContent !== popRateText) popRate.textContent = popRateText;
        if (growth < 0) {
          if (!popRate.classList.contains('neg')) popRate.classList.add('neg');
          if (popRate.classList.contains('capped')) popRate.classList.remove('capped');
        } else if (isPopCapped && growth === 0) {
          if (popRate.classList.contains('neg')) popRate.classList.remove('neg');
          if (!popRate.classList.contains('capped')) popRate.classList.add('capped');
        } else {
          if (popRate.classList.contains('neg')) popRate.classList.remove('neg');
          if (popRate.classList.contains('capped')) popRate.classList.remove('capped');
        }
      }

      var popDesc = popCard.querySelector('.d');
      if (popDesc) {
        var newPopDesc = '可征召 ' + G.fmt(Core.popFree()) + ' · 民心 ' + curMorale + (curResent > 0 ? ' <span style="color:#d9534f">(怨' + curResent + ')</span>' : '');
        if (popDesc.innerHTML !== newPopDesc) popDesc.innerHTML = newPopDesc;
      }

      popCard.title = '平民 当前: ' + G.fmt(civ) + '/' + G.fmt(pCap) + (isPopCapped ? ' (已达上限' + (growth === 0 ? '，停止增长' : '，' + popRateText) + ')' : ' (' + popRateText + ')');
    }

    // 6. 若民情政务弹窗正开着，同步更新弹窗内数值
    if (typeof window.__refreshPopDetailModal === 'function') {
      window.__refreshPopDetailModal();
    }
  }

  G.MainView = {
    navBar: navBar,
    renderArmySummaryList: renderArmySummaryList,
    showArmySummaryFullscreen: showArmySummaryFullscreen,
    renderOfficerSummaryCard: renderOfficerSummaryCard,
    showUnitDetailModal: showUnitDetailModal,
    showResourceDetail: showResourceDetail,
    showPopulationDetailModal: showPopulationDetailModal,
    getCurrentAvatar: getCurrentAvatar,
    getMilitaryRankTitle: getMilitaryRankTitle,
    presetAvatars: PRESET_AVATARS,
    silentUpdateHome: silentUpdateHome
  };

  if (G.Main) {
    G.Main.silentUpdateHome = silentUpdateHome;
  }
})(window.Game);
