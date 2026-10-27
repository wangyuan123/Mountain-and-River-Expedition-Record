/* global window, document */
window.Game = window.Game || {};
(function (G) {
  'use strict';
  var Core = G.Core;
  var D = G.DATA;

  // 首页总览与军队页共用写实武器模型；没有模型时回退到通用图标。
  var UNIT_MODEL = G.UNIT_MODEL || {};
  var HOME_MODULE_IDS = (G.Constants && G.Constants.homeModuleIds) || ['officers', 'army', 'resources', 'chat'];
  var HOME_MODULE_NAMES = (G.Constants && G.Constants.homeModuleNames) || { officers: '军官将领', army: '军队总览', resources: '后勤', chat: '世界聊天' };
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

  /** 按基础到高级的兵种顺序生成总览；首页三排按行阅读，全屏复用同一顺序。 */
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
    // 复用军队页的兵种进阶顺序，未配置的兵种保留在末尾，不受兵力数量影响。
    var unitOrder = G.Constants.armyUnitOrder;
    arr.sort(function (a, b) {
      var aIndex = unitOrder.indexOf(a.id);
      var bIndex = unitOrder.indexOf(b.id);
      return (aIndex < 0 ? unitOrder.length : aIndex) - (bIndex < 0 ? unitOrder.length : bIndex);
    });
    var columns = Math.ceil(arr.length / 3);
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
      // 固定三排的横向列表先填满一行；全屏视图由 CSS 恢复自适应换行。
      var position = ' style="--army-summary-column:' + (i % columns + 1) + ';--army-summary-row:' + (Math.floor(i / columns) + 1) + '"';
      html += '<div class="army-summary-item"' + position + unitClick + '>'
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

  /** 首页城防摘要；四类工事固定展示，便于快速看到尚未修筑的防线缺口。 */
  function renderFortSummaryList() {
    var s = Core.state || {};
    var forts = s.forts || {};
    var ids = Object.keys(D.forts || {});
    if (!ids.length) {
      return '<div class="fort-summary-empty">暂无城防工事数据，前往 <a onclick="Game.go(\'fort\')">城防</a> 修筑</div>';
    }

    var html = '';
    for (var i = 0; i < ids.length; i++) {
      var id = ids[i];
      var fort = D.forts[id] || {};
      var count = Number(forts[id]) || 0;
      var rawIcon = (G.UNIT_MODEL && G.UNIT_MODEL[id]) || (G.UNIT_ICON && G.UNIT_ICON[id]) || '🏰';
      var iconHtml = /\.svg$|\.png$|\.jpg$|\.webp$/i.test(rawIcon)
        ? '<img class="fort-summary-icon-img" src="' + rawIcon + '" alt="' + G.escapeHtml(fort.name || id) + '"/>'
        : G.escapeHtml(rawIcon);
      var position = ' style="--fort-summary-column:' + (i + 1) + ';--fort-summary-row:1"';
      var title = (fort.name || id) + ' × ' + G.fmt(count) + ' · ' + (fort.desc || '');
      var fortClick = ' role="button" tabindex="0" title="' + G.escapeHtml(title) + '" aria-label="查看' + G.escapeHtml(fort.name || id) + '详情"' +
        ' onclick="Game.MainView.showUnitDetailModal(\'' + G.escapeHtml(id) + '\', event)"' +
        ' onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.MainView.showUnitDetailModal(\'' + G.escapeHtml(id) + '\', event);event.preventDefault();}"';
      html += '<div class="fort-summary-item' + (count > 0 ? '' : ' is-empty') + '"' + position + fortClick + '>' +
        '<span class="fort-summary-icon" aria-hidden="true">' + iconHtml + '</span>' +
        '<span class="fort-summary-name">' + G.escapeHtml(fort.name || id) + '</span>' +
        '<span class="fort-summary-cnt">×' + G.fmt(count) + '</span>' +
        '<span class="fort-summary-role">' + G.escapeHtml(fort.desc || '固定城防工事') + '</span>' +
        '</div>';
    }
    return html;
  }

  function fortSummaryStats() {
    var s = Core.state || {};
    var buildings = s.buildings || {};
    var wall = (D.buildings && D.buildings.wall) || {};
    var wallLevel = Math.max(0, Number(buildings.wall) || 0);
    var capacityPerLevel = Number(wall.fortCapPerLevel) || 3000;
    var fortCapacity = wallLevel * capacityPerLevel;
    var totalForts = 0;
    var forts = s.forts || {};
    Object.keys(D.forts || {}).forEach(function (id) { totalForts += Number(forts[id]) || 0; });
    var wallLevelForStats = Math.min(10, wallLevel);
    // 总览读取当前城墙与账号加固科技；工事共享容量不受加固科技影响。
    var reinforcement = Core.wallMul();
    return {
      wallLevel: wallLevel,
      totalForts: totalForts,
      fortCapacity: fortCapacity,
      wallHp: wallLevelForStats * (Number(wall.wallHpPerLevel) || 250000) * reinforcement,
      wallDefense: Math.round(wallLevelForStats * (Number(wall.wallDefensePerLevel) || 20) * reinforcement)
    };
  }

  /** 与军队总览一致提供全屏查看，低屏宽下也能完整阅读四类城防。 */
  function showFortSummaryFullscreen() {
    if (typeof document === 'undefined' || !document.createElement || !document.body) return;
    var previousFocus = document.activeElement;
    var modal = document.createElement('div');
    modal.className = 'modal-mask fort-summary-mask';
    modal.innerHTML =
      '<section class="modal-card fort-summary-dialog" role="dialog" aria-modal="true" aria-labelledby="fortSummaryTitle">' +
      '<div class="modal-title fort-summary-dialog-title" id="fortSummaryTitle"><span>🏰 城防总览</span><button type="button" class="fort-summary-close" aria-label="关闭城防总览">✕</button></div>' +
      '<div class="modal-body fort-summary-dialog-body"><div class="fort-summary fort-summary-expanded" aria-label="全部城防工事">' + renderFortSummaryList() + '</div></div>' +
      '</section>';
    document.body.appendChild(modal);

    var close = function () {
      document.removeEventListener('keydown', onKeydown);
      if (modal.parentNode) modal.parentNode.removeChild(modal);
      if (previousFocus && previousFocus.focus) previousFocus.focus();
    };
    var onKeydown = function (event) {
      if (event.key === 'Escape' && !(document.querySelector && document.querySelector('.unit-detail-mask'))) close();
    };
    modal.querySelector('.fort-summary-close').onclick = close;
    modal.addEventListener('click', function (event) { if (event.target === modal) close(); });
    document.addEventListener('keydown', onKeydown);
    modal.querySelector('.fort-summary-close').focus();
  }

  /**
   * 复用详情弹窗展示兵种或固定城防的历史、定位及基础属性。
   * @param {string} id - 兵种或城防类型
   * @param {Event} [ev] - 入口事件，阻止冒泡触发其他导航
   */
  function showUnitDetailModal(id, ev) {
    if (ev && ev.stopPropagation) ev.stopPropagation();
    var unit = D.units && D.units[id];
    var fort = !unit && D.forts && D.forts[id];
    unit = unit || fort;
    if (!unit || typeof document === 'undefined' || !document.createElement || !document.body) return;

    var esc = G.escapeHtml;
    var stock = Core.state && (fort ? Core.state.forts : Core.state.army);
    var count = (stock && stock[id]) || 0;
    var detailTitle = fort ? '城防详情' : '兵种详情';
    var rawIcon = UNIT_MODEL[id] || (G.UNIT_ICON && G.UNIT_ICON[id]) || '';
    var iconHtml = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(rawIcon)
      ? '<img class="unit-detail-icon" src="' + esc(rawIcon) + '" alt="' + esc(unit.name) + '" />'
      : '<span class="unit-detail-icon unit-detail-icon-text">' + esc(rawIcon || '⚔') + '</span>';
    var role = (D.combatRoles && D.combatRoles[id]) || (unit && unit.desc) || '暂无战斗定位说明';
    var battleSpd = (unit.spd || 0) * 50;
    var secPerGrid = (D.world && D.world.marchSecPerGrid) || 18;
    var marchSec = (secPerGrid / (unit.spd || 1)).toFixed(1).replace(/\.0$/, '');
    var spdTip = fort ? '固定城防工事，不进行战场机动或大地图行军' : '战场单回合机动：' + battleSpd + ' 距离（基速档位 ' + (unit.spd || 0) + '）；大地图基础行军：' + marchSec + '秒/格';
    var stats = [
      ['对地攻击', unit.atkGround], ['对空攻击', unit.atkAir], ['对海攻击', unit.atkSea], ['对工事攻击', unit.atkFort],
      ['防御', unit.def], ['生命', unit.hp], ['移速', battleSpd, spdTip], ['射程', unit.range]
    ];
    if (!fort) stats = stats.concat([
      ['常驻耗粮', (unit.food || 0) + '/小时'], ['行军油耗', (unit.marchOil || 0) + '/100格'],
      ['行军粮耗', (unit.marchFood || 0) + '/5分钟'], ['人口占用', unit.pop || 0]
    ]);
    var statHtml = '';
    for (var i = 0; i < stats.length; i++) {
      var itemTitle = stats[i][2] ? ' title="' + esc(stats[i][2]) + '"' : '';
      statHtml += '<div class="unit-detail-stat"' + itemTitle + '><span>' + esc(stats[i][0]) + '</span><b>' + esc(String(stats[i][1])) + '</b></div>';
    }

    var previousFocus = document.activeElement;
    var modal = document.createElement('div');
    modal.className = 'modal-mask unit-detail-mask';
    modal.innerHTML =
      '<section class="modal-card unit-detail-card" role="dialog" aria-modal="true" aria-labelledby="unitDetailTitle">' +
      '<div class="modal-title unit-detail-title" id="unitDetailTitle"><span>' + detailTitle + '</span><button type="button" class="unit-detail-close" aria-label="关闭' + detailTitle + '">✕</button></div>' +
      '<div class="modal-body unit-detail-body">' +
      '<div class="unit-detail-overview">' + iconHtml + '<div class="unit-detail-overview-copy"><h2>' + esc(unit.name) + '</h2><div class="unit-detail-count">' + (fort ? '当前部署：' : '当前兵力：') + '<b>' + G.fmt(count) + '</b></div></div></div>' +
      '<div class="unit-detail-section"><h3>历史信息</h3><p>' + esc(unit.history || '暂无历史信息') + '</p></div>' +
      '<div class="unit-detail-section"><h3>战斗定位</h3><p>' + esc(role) + '</p></div>' +
      '<div class="unit-detail-section"><h3>属性信息</h3><div class="unit-detail-stats">' + statHtml + '</div></div>' +
      '</div>' +
      '</section>';
    document.body.appendChild(modal);

    var close = function () {
      document.removeEventListener('keydown', onKeydown);
      if (modal.parentNode) modal.parentNode.removeChild(modal);
      if (previousFocus && previousFocus.focus) previousFocus.focus();
    };
    var onKeydown = function (event) { if (event.key === 'Escape') close(); };
    var closeBtn = modal.querySelector('.unit-detail-close');
    if (closeBtn) closeBtn.onclick = close;
    modal.addEventListener('click', function (event) { if (event.target === modal) close(); });
    document.addEventListener('keydown', onKeydown);
    if (closeBtn && closeBtn.focus) closeBtn.focus();
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
      + '<span class="zone-title">军官将领</span>'
      + '<span class="zone-sub">已招募 ' + totalCount + ' 名</span>'
      + '<span class="home-officer-go zone-head-action" onclick="event.stopPropagation();Game.go(\'academy\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){event.stopPropagation();Game.go(\'academy\');event.preventDefault();}" role="button" tabindex="0" title="点击前往军校 · 招募将领">去招募 &gt;</span>'
      + '</div>';
    html += '<div class="home-officer-card">';

    if (totalCount === 0) {
      html += '<div class="home-officer-empty">'
        + '<span class="home-officer-empty-icon">🎖️</span>'
        + '<div class="home-officer-empty-info">'
        + '<div class="home-officer-empty-title">暂未招募将领</div>'
        + '<div class="home-officer-empty-desc">前往军校招募将领，委任市长与指挥官以提升城防与产能</div>'
        + '</div>'
        + '<span class="home-officer-go" onclick="Game.go(\'academy\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.go(\'academy\');event.preventDefault();}" role="button" tabindex="0" title="前往军校 · 招募将领">去军校招募 &gt;</span>'
        + '</div>';
    } else {
      html += '<div class="home-officer-list">';
      for (var i = 0; i < officers.length; i++) {
        var o = officers[i];
        var starColor = (D.starColor && D.starColor[o.star]) || '#ffe14a';

        // 职位显示（闲置不打标以省出空间展示军官姓名，特殊职位突出显示）
        var roleTag = '';
        if (o.role === 'mayor') {
          roleTag = '<span class="home-officer-role role-mayor">市长</span>';
        } else if (o.role === 'march') {
          roleTag = '<span class="home-officer-role role-march">行军</span>';
        } else if (o.role === 'commander') {
          roleTag = '<span class="home-officer-role role-commander">指挥官</span>';
        } else if (o.role === 'commander_in_chief') {
          roleTag = '<span class="home-officer-role role-commander">司令官</span>';
        }

        // 技能摘要
        var skillSummary = Core.formatSkills ? Core.formatSkills(o.skills) : '';
        var firstSkill = o.skills && o.skills.find(function (skill) { return skill; });
        var skillId = typeof firstSkill === 'string' ? firstSkill : firstSkill && firstSkill.id;
        var skillDef = D.officerSkills && D.officerSkills[skillId];
        var skillIcon = skillDef && skillDef.icon || '✦';

        var isMax = (o.level || 1) >= (G.OFFICER_MAX_LEVEL || 100);
        var levelHtml = '<span class="home-officer-level' + (isMax ? ' is-max' : '') + '">Lv.' + (o.level || 1) + (isMax ? '<small class="max-badge">(满)</small>' : '') + '</span>';

        html += '<div class="home-officer-item home-officer-item-action' + (roleTag ? ' is-assigned' : '') + '" role="button" tabindex="0" title="查看' + G.escapeHtml(o.name || '军官') + '详情" aria-label="查看' + G.escapeHtml(o.name || '军官') + '详情" onclick="Game.Officer.showDetail(\'' + o.id + '\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.Officer.showDetail(\'' + o.id + '\');event.preventDefault();}">';
        html += '<div class="home-officer-top-row">';
        html += '<div class="home-officer-identity">';
        if (roleTag) html += roleTag;
        html += '<span class="home-officer-name" style="color:' + starColor + '">' + G.escapeHtml(o.name || '军官') + '</span>';
        html += '</div>';
        html += levelHtml;
        html += '</div>';

        html += '<div class="home-officer-bottom-row">';
        html += '<div class="home-officer-stats">';
        html += '<span class="home-officer-stat" title="军事 ' + (o.military || 0) + '"><span class="stat-lbl mil">军</span><b class="stat-val mil">' + (o.military || 0) + '</b></span>';
        html += '<span class="home-officer-stat" title="后勤 ' + (o.logistics || 0) + '"><span class="stat-lbl log">勤</span><b class="stat-val log">' + (o.logistics || 0) + '</b></span>';
        html += '<span class="home-officer-stat" title="学识 ' + (o.knowledge || 0) + '"><span class="stat-lbl kno">学</span><b class="stat-val kno">' + (o.knowledge || 0) + '</b></span>';
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
    var isMaintenance = G.Main && G.Main.isMaintenanceActive && G.Main.isMaintenanceActive();
    if (isMaintenance) {
      h += '<div class="login-hero-maintenance-tag" role="status"><span class="login-maintenance-pulse" aria-hidden="true"></span><span>战区通讯整备中 · MAINTENANCE</span></div>';
    }
    h += '<div class="login-hero-rule" aria-hidden="true"><span></span><span></span><span></span></div>';
    h += '<p class="login-hero-description">建立防线，调度军团，争夺每一寸山河。<br>从这里开始，书写属于你的战役。</p></div>';
    h += '<div class="login-hero-bottom"><span>战区指挥部 · 作战终端</span><span>' + (isMaintenance ? '⚠ &nbsp; 链路维护中' : '◆ &nbsp; 全线待命') + '</span></div>';
    h += '</section>';
    h += '<section class="login-access" aria-label="账号登录与注册"><div class="login-access-inner">';
    h += '<div class="login-access-heading"><span class="login-access-index">01 / ACCOUNT ACCESS</span><span class="login-access-mark" aria-hidden="true">✦</span></div>';
    if (G.Account && G.Account.recovery) {
      h += G.Account.recoveryPanel();
    } else {
      if (isMaintenance && G.Main && G.Main.renderMaintenanceNotice) {
        h += G.Main.renderMaintenanceNotice();
      }
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
      h += '<div class="edit-row"><label for="loginPass">密码 <span>ACCESS CODE</span></label><div class="login-password-wrap"><input id="loginPass" class="qty" name="password" autocomplete="current-password" type="password" maxlength="64" placeholder="输入通行密码 · 6-64 位"><button type="button" class="login-password-toggle" aria-label="显示密码" aria-pressed="false" onclick="Game.Main.togglePasswordVisibility(\'loginPass\', this)">👁</button></div></div>';
      h += '<div class="edit-row login-register-only" id="loginConfirmRow" hidden><label for="loginPassConfirm">确认密码 <span>CONFIRM CODE</span></label><div class="login-password-wrap"><input id="loginPassConfirm" class="qty" name="passwordConfirm" autocomplete="new-password" type="password" maxlength="64" placeholder="再次输入通行密码"><button type="button" class="login-password-toggle" aria-label="显示密码" aria-pressed="false" onclick="Game.Main.togglePasswordVisibility(\'loginPassConfirm\', this)">👁</button></div></div>';
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
    var hasIncoming = (s.world.incoming && s.world.incoming.length > 0) || (G.Radar && G.Radar.unread() > 0);
    var items = [];
    var homeActive = Core.route === 'home' ? ' active' : '';
    items.push('<div class="navitem home-tab' + homeActive + '" data-route="home" onclick="Game.go(\'home\')"><span class="navlabel">首页</span></div>');
    for (var i = 0; i < NAV_ITEMS.length; i++) {
      var it = NAV_ITEMS[i];
      var action = 'Game.go(\'' + it.route + '\')';
      var active = (Core.route === it.route || (Core.route === 'wounded' && it.route === 'army') || (Core.route === 'officerDetail' && it.route === 'officer')) ? ' active' : '';
      var hasBattleAlert = G.World && G.World.hasBattleAlert ? G.World.hasBattleAlert() : false;
      var alertCls = (it.route === 'alerts' && (hasIncoming || hasBattleAlert)) ? ' alert' : '';
      var chatPrivateUnread = (it.route === 'chat' && G.PrivateChat && G.PrivateChat.unread) ? G.PrivateChat.unread() : 0;
      var friendRequestsUnread = (it.route === 'chat' && G.Friends && G.Friends.unread) ? G.Friends.unread() : 0;
      var chatUnread = chatPrivateUnread + friendRequestsUnread;
      var chatBadge = chatUnread > 0 ? '<span class="nav-badge">' + (chatUnread > 99 ? '99+' : chatUnread) + '</span>' : '';
      var mailUnread = (it.route === 'mail' && G.Mail && G.Mail.unread && G.Mail.unread() > 0) ? G.Mail.unread() : 0;
      var mailBadge = mailUnread ? '<span class="nav-badge">' + mailUnread + '</span>' : '';
      var reportsUnread = (it.route === 'reports' && G.Battle && G.Battle.unreadCount && G.Battle.unreadCount() > 0) ? G.Battle.unreadCount() : 0;
      var reportsBadge = reportsUnread ? '<span class="nav-badge">' + (reportsUnread > 99 ? '99+' : reportsUnread) + '</span>' : '';
      // 主线任务红点
      var questBadge = '';
      if (it.route === 'mainQuest' && G.MainQuest && G.MainQuest.hasUnclaimed && G.MainQuest.hasUnclaimed()) {
        questBadge = '<span class="nav-badge alert-dot">!</span>';
      }
      var alertBadge = '';
      if (it.route === 'alerts' && (hasIncoming || hasBattleAlert)) {
        alertBadge = '<span class="nav-badge alert-dot">!</span>';
      }
      items.push('<div class="navitem' + active + alertCls + '" data-route="' + it.route + '" onclick="' + action + '"><span class="navnum">[' + it.key + ']</span><span class="navlabel">' + (it.icon ? '<img class="nav-icon" src="' + it.icon + '" alt="' + it.label + '"/>' : it.label) + '</span>' + chatBadge + mailBadge + reportsBadge + questBadge + alertBadge + '</div>');
    }
    if (G.Cities) items.splice(items.length - 3, 0, G.Cities.nav());
    // 排名与商城固定相邻放在第二组；缺少城市切换模块也不前移这两个入口。
    var pageSize = 16;
    var shopItem = items.pop();
    var rankingItem = items.pop();
    var groups = [];
    for (var offset = 0; offset < items.length; offset += pageSize) groups.push(items.slice(offset, offset + pageSize));
    if (groups.length < 2) groups.push([]);
    groups[1].push(rankingItem, shopItem);
    var pages = [];
    var dots = [];
    for (var page = 0; page < groups.length; page++) {
      pages.push('<div class="nav-page" role="group" aria-label="第' + (page + 1) + '组导航">' + groups[page].join('') + '</div>');
      dots.push('<button type="button" class="nav-page-dot" data-nav-page="' + page + '" aria-label="切换到第' + (page + 1) + '组导航"></button>');
    }
    return '<button type="button" class="nav-collapse-toggle" data-nav="collapse" aria-controls="gameNavViewport" aria-expanded="true" aria-label="收起导航" title="收起导航"><span class="nav-collapse-icon" aria-hidden="true">‹</span></button>' +
      '<div id="gameNavViewport" class="nav-viewport" aria-label="' + (pages.length > 1 ? '左右滑动查看更多导航' : '功能导航') + '">' + pages.join('') + '</div>' +
      (pages.length > 1 ? '<div class="nav-pages" aria-label="导航分页">' + dots.join('') + '</div>' : '');
  }

  function showResourceDetail(key, name, icon, current, productionCap, inventoryCap, rate, production, consumption) {
    var modal = document.createElement('div');
    modal.className = 'modal-mask';
    var percent = inventoryCap > 0 ? Math.min(100, Math.floor(current / inventoryCap * 100)) : 0;
    var isProductionCapped = current >= productionCap;
    var detail = '';
    if (key === 'food') {
      var supply = (Core.state || {}).foodSupply;
      var supportBreakdown = supply && supply.supportPerHour > 0
        ? '<div class="res-detail-value"><span>其中本城部队</span><b class="neg">-' + G.fmt(supply.ownPerHour) + '/小时</b></div>' +
          '<div class="res-detail-value"><span>其中外来驻军（本城承担）</span><b class="neg">-' + G.fmt(supply.supportPerHour) + '/小时</b></div>' : '';
      detail =
        '<div class="res-detail-section-title">粮食流向</div>' +
        '<div class="res-detail-value"><span>农田生产</span><b class="positive">+' + G.fmt(production) + '/小时' + (isProductionCapped ? ' (已达生产容量)' : '') + '</b></div>' +
        '<div class="res-detail-value"><span>军队消耗</span><b class="neg">-' + G.fmt(consumption) + '/小时</b></div>' +
        supportBreakdown +
        '<div class="res-detail-net"><span>实际每小时净变化</span><b class="' + (rate < 0 ? 'neg' : 'positive') + '">' + (rate >= 0 ? '+' : '') + G.fmt(rate) + '/小时</b></div>' +
        '<div class="res-detail-tip">' + (isProductionCapped ? '粮食已达到生产容量，农田暂停自然增产；其他途径仍可能使库存暂时超过10亿，超限后按每3小时巡检规则处理。' : '粮食净变化 = 农田生产 − 军队消耗。净变化为负时，储量会持续减少。') + '</div>';
    } else if (key === 'gold') {
      var s = Core.state || {};
      var mayor = Core.getOfficerByRole ? Core.getOfficerByRole('mayor') : null;
      var financeBonus = Core.mayorSkillBonus ? Core.mayorSkillBonus('finance') : 0;
      var taxRate = Math.floor(Core.civilianPopulation() * ((s.tax != null ? s.tax : 30) / 100) * (1 + (mayor ? mayor.knowledge / 100 : 0)) * (1 + financeBonus) * 2);
      var salary = Core.officerSalaryPerHour ? Core.officerSalaryPerHour() : 0;
      detail =
        '<div class="res-detail-section-title">黄金收支</div>' +
        '<div class="res-detail-value"><span>平民税收</span><b class="positive">+' + G.fmt(taxRate) + '/小时' + (isProductionCapped ? ' (已达生产容量)' : '') + '</b></div>' +
        (salary > 0 ? '<div class="res-detail-value"><span>军官薪资</span><b class="neg">-' + G.fmt(salary) + '/小时</b></div>' : '') +
        '<div class="res-detail-net"><span>实际每小时净变化</span><b class="' + (rate < 0 ? 'neg' : 'positive') + '">' + (rate >= 0 ? '+' : '') + G.fmt(rate) + '/小时</b></div>' +
        '<div class="res-detail-tip">' + (isProductionCapped ? '黄金已达到生产容量，税收暂停增加；超过10亿后按每3小时巡检规则分阶段处理。' : '黄金主要来源于平民税收，受税率与市长知识、理财技能影响。') + '</div>';
    } else {
      var bType = key === 'steel' ? 'refinery' : (key === 'oil' ? 'oilfield' : 'raremine');
      var baseProd = Core.produceOf ? Core.produceOf(bType) : (production || 0);
      if (isProductionCapped) {
        detail =
          '<div class="res-detail-value"><span>理论产能</span><b>+' + G.fmt(baseProd) + '/小时</b></div>' +
          '<div class="res-detail-value"><span>实际每小时净产出</span><b class="highlight">0/小时 (生产容量已满)</b></div>' +
          '<div class="res-detail-tip">当前储量已达生产容量，建筑已暂停自然生产；超过10亿后按每3小时巡检规则分阶段处理。</div>';
      } else {
        detail = '<div class="res-detail-value"><span>每小时净产出</span><b class="' + (rate < 0 ? 'neg' : '') + '">' + (rate >= 0 ? '+' : '') + G.fmt(rate) + '/小时</b></div>';
      }
    }
    var titleIcon = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(icon)
      ? '<img class="res-icon-img" src="' + icon + '" alt="' + name + '" style="width:20px;height:20px;margin-right:6px;vertical-align:middle;display:inline-block;" />'
      : (icon ? '<span style="margin-right:6px;">' + icon + '</span>' : '');
    modal.innerHTML =
      '<div class="modal-card res-detail-modal" style="max-width:380px">' +
      '<div class="modal-title" style="display:flex;align-items:center;justify-content:space-between;">' +
      '<span class="res-detail-title-text" style="display:inline-flex;align-items:center;">' + titleIcon + name + '详情</span>' +
      '<button type="button" class="modal-close-btn" id="closeResourceDetailX" aria-label="关闭">✕</button>' +
      '</div>' +
      '<div class="modal-body res-detail-body">' +
      '<div class="res-detail-value"><span>当前储量</span><b>' + G.fmt(current) + '</b></div>' +
      '<div class="res-detail-value"><span>生产容量</span><b>' + G.fmt(productionCap) + '</b></div>' +
      '<div class="res-detail-value"><span>库存巡检阈值</span><b>' + G.fmt(inventoryCap) + '</b></div>' +
      detail +
      '<div class="res-detail-bar"><span style="width:' + percent + '%"></span></div>' +
      '<div class="res-detail-percent">储量使用率 ' + percent + '%</div>' +
      '</div>' +
      '<div class="modal-foot"><button class="btn ok" id="closeResourceDetail">关闭</button></div>' +
      '</div>';
    document.body.appendChild(modal);
    var closeResModal = function () {
      modal.remove();
    };
    var closeBtn = modal.querySelector('#closeResourceDetail');
    if (closeBtn) closeBtn.onclick = closeResModal;
    var closeX = modal.querySelector('#closeResourceDetailX');
    if (closeX) closeX.onclick = closeResModal;
    modal.addEventListener('click', function (e) { if (e.target === modal) closeResModal(); });
    modal.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeResModal(); });
  }

  function showPopulationDetailModal() {
    var modal = document.createElement('div');
    modal.className = 'modal-mask';
    var recruitInFlight = false;

    modal.innerHTML =
      '<div class="modal-card pop-detail-modal" style="max-width:440px">' +
      '<div class="modal-title" style="display:flex;align-items:center;justify-content:space-between;">' +
      '<span class="pop-detail-title-text" style="display:inline-flex;align-items:center;">👥 平民与民情政务</span>' +
      '<button type="button" class="modal-close-btn" id="closePopDetailX" aria-label="关闭">✕</button>' +
      '</div>' +
      '<div class="modal-body">' +
      '<div class="pop-detail-summary">' +
      '<div class="pop-stat-box">' +
      '<div class="pop-stat-head">' +
      '<span class="pop-stat-label">当前平民</span>' +
      '<button class="btn ok sm pop-call-btn" id="popRecruitQuickBtn" title="消耗人口动员令立即召集平民">召集</button>' +
      '</div>' +
      '<span class="pop-stat-val" id="popCivilianVal">-</span>' +
      '</div>' +
      '<div class="pop-stat-box"><span class="pop-stat-label">民居标称容量</span><span class="pop-stat-val" id="popCapVal">-</span></div>' +
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
      '<div class="pop-recruit-desc">消耗军需仓库中的【人口动员令】，立即自四方动员 <b class="positive">+500</b> 空闲平民进城（受民居容量限制）。</div>' +
      '</div>' +
      '<button class="btn ok pop-recruit-action-btn" id="popRecruitBtn">立即召集 (+500)</button>' +
      '</div>' +
      '<div class="pop-recruit-card">' +
      '<div class="pop-recruit-info">' +
      '<span class="pop-recruit-badge" id="popEmergencyOrderCountBadge">拥有紧急动员令: 0 张</span>' +
      '<div class="pop-recruit-desc">消耗【紧急人口动员令】，立即增加当前城市人口容量上限 <b class="positive">30%</b> 的空闲平民（不超过民居容量）。</div>' +
      '</div>' +
      '<button class="btn ok pop-recruit-action-btn" id="popEmergencyRecruitBtn">紧急召集 (+30%)</button>' +
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
      '<div class="pop-bar-hint">民心决定城市的实际人口容纳率与增长速度；长期重税(>50%)滋生民怨。守城战败按战前民心增加民怨：71～100 时征服 +3、掠夺 +2；41～70 时征服 +2；0～40 时征服 +1；民心≤70 时掠夺不增加，佯攻、袭扰、游击均不增加民怨。</div>' +
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
      '<button class="btn ok" id="closePopDetail">关闭</button>' +
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
      var sliderEl = modal.querySelector('#popTaxSlider');
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
      modal.querySelector('#popEffCapVal').textContent = G.fmt(effCap);
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
      var emergencyOrders = (s.items && s.items.emergencyPopulationOrder) || 0;
      var emergencyBadge = modal.querySelector('#popEmergencyOrderCountBadge');
      emergencyBadge.textContent = '拥有紧急动员令: ' + emergencyOrders + ' 张';
      emergencyBadge.className = 'pop-recruit-badge' + (emergencyOrders > 0 ? ' has-items' : '');
      var emergencyBtn = modal.querySelector('#popEmergencyRecruitBtn');
      var emergencyAdded = Math.min(Math.floor(cap * 30 / 100), Math.max(0, cap - civ));
      emergencyBtn.textContent = emergencyOrders > 0 ? '紧急召集 (+' + G.fmt(emergencyAdded) + ')' : '获取紧急动员令 (去商城)';
      emergencyBtn.className = 'btn ' + (emergencyOrders > 0 ? 'ok' : 'sub') + ' pop-recruit-action-btn';
      [quickBtnEl, mainRecruitBtn, emergencyBtn].forEach(function (btn) {
        if (btn) btn.disabled = recruitInFlight;
      });

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

    /** 按所选动员令召集；数量与人口由后端结算，缺货时使用主题确认框前往商城。 */
    function handleRecruitPopulation(itemId) {
      if (recruitInFlight) return;
      var civ = Core.civilianPopulation();
      var cap = Core.populationCapacity();
      if (civ >= cap) {
        G.toast('民居容量已达上限 (' + G.fmt(civ) + '/' + G.fmt(cap) + ')，请先扩建民居！');
        return;
      }
      var s = Core.state || {};
      var items = s.items || {};
      var info = D.items[itemId];
      var emergency = itemId === 'emergencyPopulationOrder';
      var popOrders = items[itemId] || 0;
      if (popOrders <= 0) {
        G.World.showConfirm({
          title: '获取' + info.name,
          message: '仓库中暂无【' + info.name + '】（' + info.desc + '）。是否立即前往商城购买？',
          okText: '前往商城',
          onConfirm: function () {
            closeModal();
            G.go('shop');
          }
        });
        return;
      }

      recruitInFlight = true;
      refreshModal();
      var expectedAdded = Math.min(emergency ? Math.floor(cap * 30 / 100) : 500, Math.max(0, cap - civ));

      G.API.depotUse(itemId, null, null).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '召集失败');
          if (resp.state) G.API.applyState(resp.state);
          return;
        }
        G.toast((resp && resp.message) || '👥 召集成功，空闲平民 +' + expectedAdded + '！');
        if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
        if (Core.route === 'home') Core.render();
      }).catch(function (err) {
        G.toast((err && err.message) || '召集失败');
      }).finally(function () {
        recruitInFlight = false;
        refreshModal();
      });
    }

    var quickRecruitBtn = modal.querySelector('#popRecruitQuickBtn');
    if (quickRecruitBtn) quickRecruitBtn.onclick = function () { handleRecruitPopulation('populationOrder'); };

    var mainRecruitBtn = modal.querySelector('#popRecruitBtn');
    if (mainRecruitBtn) mainRecruitBtn.onclick = function () { handleRecruitPopulation('populationOrder'); };

    var emergencyRecruitBtn = modal.querySelector('#popEmergencyRecruitBtn');
    emergencyRecruitBtn.onclick = function () { handleRecruitPopulation('emergencyPopulationOrder'); };

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
    var closePopBtn = modal.querySelector('#closePopDetail');
    if (closePopBtn) closePopBtn.onclick = closeModal;
    var closePopX = modal.querySelector('#closePopDetailX');
    if (closePopX) closePopX.onclick = closeModal;
    modal.addEventListener('click', function (e) { if (e.target === modal) closeModal(); });
    modal.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeModal(); });

    refreshModal();
  }

  Core.views.home = function (v) {
    var s = Core.state;
    var r = s.resources;
    var mayor = Core.getOfficerByRole('mayor');
    var cmd = Core.getOfficerByRole('commander');
    var marches = Core.militaryAlertMarches();
    var incoming = s.world.incoming || [];
    var alertCount = marches.length + incoming.length;
    var reportsCount = (s.reports || []).length;

    var cityName = s.player.cityName || '新城市';

    var h = '';

    h += '<div class="city-head">';
    h += '<div class="city-row">';
    h += '<div class="city-cell"><span class="city-label">城市</span><b>' + G.escapeHtml(cityName) + '</b>' + (s.player.cityFrozen ? '<span class="city-edit" title="归降移交中，城市只读">🔒</span>' : '<span class="city-edit" onclick="Game.Main.toggleEditCity()">✎</span>') + '</div>';
    var cs = Core.cityStatusText();
    var cityPos = s.world.cityPos || s.world.pos;
    h += '<div class="city-cell city-coord"><span class="city-label">坐标</span>(' + cityPos.x + ',' + cityPos.y + ')</div>';
    var statusClass = cs.status ? ' ' + cs.status : ' peace';
    h += '<span class="city-status-tag' + statusClass + '" data-status="' + (cs.status || 'peace') + '">' + cs.text + '</span>';
    h += '</div>';
    h += '<div id="newbieProtectionNotice" class="home-alert newbie-protection-notice" role="status"' + (shouldShowNewbieProtectionNotice() ? '' : ' hidden') + '>' +
      '<span id="newbieProtectionNoticeText">' + (shouldShowNewbieProtectionNotice() ? G.escapeHtml(cs.detail) : '') + '</span>' +
      '<button type="button" class="drawer-close-btn newbie-protection-close" aria-label="永久关闭首页新手保护提示" title="关闭首页提示" onclick="Game.MainView.dismissNewbieProtectionNotice()">✕</button></div>';
    if (s.player.cityFrozen) h += '<div class="home-alert" role="status">城市正在归降移交，经营和军事操作已冻结；预计 ' + new Date(G.GameTime ? G.GameTime.toWallTime(s.player.cityTransferDeadlineAt || G.gameNow()) : Number(s.player.cityTransferDeadlineAt || Date.now())).toLocaleString() + ' 完成（停服顺延）。</div>';
    h += '<div id="editCityBox" class="edit-profile-box" style="display:none">';
    h += '<div class="edit-row"><label>城市名</label><input id="epCityName" class="qty" style="width:100%" maxlength="12" value="' + G.escapeHtml(s.player.cityName || '新城市') + '" placeholder="留空则用默认名称"></div>';
    h += '<div class="d" style="color:var(--muted);margin-top:4px">最多12个字符，可用标点、符号和 emoji；每天可修改一次，按游戏时间的北京时间 0:00 重置，停服顺延。' + (Number(s.player.cityNameRenameAvailableAt) > (G.gameNow ? G.gameNow() : Date.now()) ? '这座城市今日已修改。' : '') + '</div>';
    h += '<div class="d" style="color:var(--muted);margin-top:4px">' + ((Number(s.items && s.items.cityRenameCard) || 0) > 0 ? '优先消耗 1 张城市改名卡。' : '消耗 60 黄金。') + '</div>';
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

    if (G.Vip && G.Vip.renderHomeCard && (!G.Vip.isHomeCardVisible || G.Vip.isHomeCardVisible())) {
      h += G.Vip.renderHomeCard(s);
    }

    var netFood = Core.foodProductionPerHour() - Core.foodPerHour();
    var netSteel = Core.produceOf('refinery');
    var netOil = Core.produceOf('oilfield');
    var netRare = Core.produceOf('raremine');
    var financeBonus = Core.mayorSkillBonus ? Core.mayorSkillBonus('finance') : 0;
    var goldRate = Math.floor(Core.civilianPopulation() * (s.tax / 100) * (1 + (mayor ? mayor.knowledge / 100 : 0)) * (1 + financeBonus) * 2);
    var cap = Core.capacity();
    var caps = { food: cap.food, steel: cap.steel, oil: cap.oil, rare: cap.rare, gold: cap.gold };
    var nets = { food: netFood, steel: netSteel, oil: netOil, rare: netRare, gold: goldRate };

    // 各模块单独生成，再按当前玩家保存的顺序组合。
    var fixedHtml = h;
    var modules = {};
    h = '';
    h += renderOfficerSummaryCard();
    modules.officers = h;
    h = '';

    // 军队总览（活动与任务块已迁移到顶部菜单"任务"页内）
    h += '<div class="zone-head">' + homeModuleHandle('army') + '<span class="zone-title"><img class="home-overview-icon" src="' + UNIT_MODEL.special + '" alt="" aria-hidden="true" draggable="false"/> 军队总览</span><span class="zone-sub">带兵上限 ' + G.fmt(Core.armyCap()) + '</span><span class="zone-head-actions"><button type="button" class="army-summary-expand" title="全屏展开军队总览" aria-label="全屏展开军队总览" onclick="Game.MainView.showArmySummaryFullscreen()"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5"/></svg></button><span class="army-dispatch-go zone-head-action" role="button" tabindex="0" onclick="Game.go(\'world\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.go(\'world\');event.preventDefault();}">去出征 &gt;</span></span></div>';
    h += '<div class="army-summary" role="region" tabindex="0" aria-label="军队总览，三排排列，左右滑动查看全部兵种">';
    h += renderArmySummaryList();
    h += '</div>';
    h += '<div class="army-summary-foot" onclick="Game.go(\'army\')">';
    var totArmy = 0;
    var sArmy = (Core.state && Core.state.army) || {};
    for (var ak in sArmy) totArmy += sArmy[ak] || 0;
    var cmd2 = Core.getOfficerByRole('commander');
    h += '<span class="army-summary-strength">可用兵力 <b class="home-army-tot" style="color:var(--accent)">' + G.fmt(totArmy) + '</b> / ' + G.fmt(Core.armyCap()) + '</span>';
    h += '<span class="army-summary-commander"><span class="army-summary-commander-label">指挥官:</span> <b>' + (cmd2 ? G.escapeHtml(cmd2.name) : '未任命') + '</b></span>';
    h += '<span class="army-go">详情 &gt;</span>';
    h += '</div>';
    // 伤兵仅在付费治疗后归队；独立提示，不并入兵种数量或可用兵力。
    var woundedCount = Number(Core.state && Core.state.woundedCount) || 0;
    if (woundedCount > 0) {
      h += '<div class="desc">待救治伤兵 <b>' + G.fmt(woundedCount) + '</b>（不计入可用兵力），需在 <a href="#wounded" onclick="Game.go(\'wounded\');return false;">伤兵营</a> 消耗黄金或钻石治疗后归队。</div>';
    }

    var fortStats = fortSummaryStats();
    h += '<div class="zone-head fort-summary-zone-head"><span class="zone-title"><img class="home-overview-icon" src="img/buildings/garden/wall.webp" alt="" aria-hidden="true" draggable="false"/> 城防总览</span><span class="zone-sub">城防 Lv.' + G.fmt(fortStats.wallLevel) + ' · 工事 ' + G.fmt(fortStats.totalForts) + '/' + G.fmt(fortStats.fortCapacity) + '</span><span class="zone-head-actions"><button type="button" class="fort-summary-expand" title="全屏展开城防总览" aria-label="全屏展开城防总览" onclick="Game.MainView.showFortSummaryFullscreen()"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5"/></svg></button><span class="fort-summary-go zone-head-action" role="button" tabindex="0" onclick="Game.go(\'fort\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.go(\'fort\');event.preventDefault();}">去城防 &gt;</span></span></div>';
    h += '<div class="fort-summary" role="region" tabindex="0" aria-label="城防总览，单排横向排列，左右滑动查看全部工事">';
    h += renderFortSummaryList();
    h += '</div>';
    h += '<div class="fort-summary-foot" role="button" tabindex="0" onclick="Game.go(\'fort\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.go(\'fort\');event.preventDefault();}">';
    h += '<span class="fort-summary-wall">墙体 <b>' + G.fmt(fortStats.wallHp) + '</b> 生命 · <b>' + G.fmt(fortStats.wallDefense) + '</b> 防御</span>';
    h += '<span class="fort-summary-capacity">工事容量 <b>' + G.fmt(fortStats.totalForts) + '</b> / ' + G.fmt(fortStats.fortCapacity) + '</span>';
    h += '</div>';
    modules.army = h;
    h = '';

    h += '<div class="zone-head">' + homeModuleHandle('resources') + '<span class="zone-title"><img class="home-overview-icon" src="' + D.resources.gold.icon + '" alt="" aria-hidden="true" draggable="false"/> 资源</span></div>';
    h += '<div class="res-grid">';
    var resKeys = G.Constants.resourceKeysWithGold;
    for (var ri = 0; ri < resKeys.length; ri++) {
      var rk = resKeys[ri];
      var rinfo = D.resources[rk];
      var cur = r[rk] || 0;
      var maxR = caps[rk] || (rk === 'gold' ? 20000000 : 999999);
      var net = Core.resourceNetRate ? Core.resourceNetRate(rk) : (nets[rk] || 0);
      var sign = net >= 0 ? '+' : '';
      var isCapped = cur >= maxR;
      var titleText = (rinfo.name || rk) + ' 当前: ' + G.fmt(cur) + (isCapped ? ' (已达生产容量' + (net === 0 ? '，停止产出' : '，' + sign + G.fmt(net) + '/h') + ')' : ' (' + sign + G.fmt(net) + '/h)');
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
    h += renderWorldChat(20);

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

  /**
   * 首页与独立聊天页共用世界频道，消息与发送规则保持一致。
   * @param {number} limit - 首次渲染展示的消息数量
   * @returns {string} 世界频道终端 HTML
   */
  function renderWorldChat(limit) {
    var h = '';
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
    var msgs = (G.Chat && G.Chat.recent) ? G.Chat.recent(limit || 20) : [];
    for (var mi = 0; mi < msgs.length; mi++) {
      if (G.Chat && G.Chat.renderMessageHtml) {
        h += G.Chat.renderMessageHtml(msgs[mi], mi > 0 ? msgs[mi - 1] : null);
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
    h += '<input class="chat-input" id="worldChatInput" type="text" maxlength="80" enterkeyhint="send" placeholder="' + (chatReadOnly ? '声望达到 10,000 后可发言' : '善言结善缘 恶语伤人心') + '"' + (chatReadOnly ? ' disabled' : '') + ' autocomplete="off" onkeydown="if(event.key===&quot;Enter&quot;){Game.Main.sendChat();}"/>';
    h += '<button class="chat-send' + (chatReadOnly || cd > 0 ? ' disabled' : '') + '" id="worldChatSendBtn"' + (chatReadOnly || cd > 0 ? ' disabled' : '') + ' onclick="Game.Main.sendChat()">' + (chatReadOnly ? '只读' : (cd > 0 ? G.formatDuration(cd) : '发送')) + '</button>';
    h += '</div>';
    h += '</div>';
    return h;
  }

  /**
   * 切换聊天内容区，保留论坛路由、聊天草稿与消息滚动位置。
   * @param {string} tab - messages、friends 或 forum
   */
  function selectChatTab(tab) {
    if (['messages', 'friends', 'forum'].indexOf(tab) < 0) return;
    var page = document.querySelector('.chat-page');
    if (!page) return;
    var messages = page.querySelector('#chatMessagesPanel');
    var forum = page.querySelector('#chatForumPanel');
    var friends = page.querySelector('#chatFriendsPanel');
    var selected = tab === 'forum' ? forum : tab === 'friends' ? friends : messages;
    if (!selected || !selected.hidden) return;
    var box = document.getElementById('worldChatBox');
    if (tab !== 'messages') {
      if (box && !messages.hidden) {
        messages._chatScrollTop = box.scrollTop;
        messages._chatAtBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
      }
    }
    if (tab === 'forum') {
      // 首次打开才加载论坛，后续仅隐藏面板，避免重置论坛登录和浏览位置。
      if (!forum.querySelector('iframe')) {
        var frame = document.createElement('iframe');
        frame.className = 'chat-forum-frame';
        frame.title = '山河论坛';
        frame.referrerPolicy = 'no-referrer';
        frame.src = Core.getForumUrl();
        forum.appendChild(frame);
      }
    }
    messages.hidden = tab !== 'messages';
    forum.hidden = tab !== 'forum';
    if (friends) friends.hidden = tab !== 'friends';
    if (tab === 'messages' && box) {
      box.scrollTop = messages._chatAtBottom ? box.scrollHeight : (messages._chatScrollTop || 0);
    }
    page.querySelector('#chatPageTitle').textContent = tab === 'friends' ? '山河战友' : tab === 'forum' ? '山河论坛' : (selectedChatChannel === 'private' ? '玩家私聊' : selectedChatChannel === 'guild' ? '军团频道' : '世界频道');
    page.querySelector('#chatPageSubtitle').textContent = tab === 'friends' ? '好友与分组' : tab === 'forum' ? '玩家交流' : (selectedChatChannel === 'private' ? '仅双方可见' : selectedChatChannel === 'guild' ? '战术内频' : '实时通联');
    if (tab === 'friends' && G.Friends) {
      if (G.Friends.acknowledge) G.Friends.acknowledge();
      G.Friends.refresh();
    }
    if (tab === 'messages' && G.PrivateChat && G.PrivateChat.active) G.PrivateChat.refresh();
    var tabs = page.querySelectorAll('[data-chat-tab]');
    for (var i = 0; i < tabs.length; i++) {
      var active = tabs[i].getAttribute('data-chat-tab') === tab;
      tabs[i].classList.toggle('active', active);
      if (active) tabs[i].setAttribute('aria-current', 'page');
      else tabs[i].removeAttribute('aria-current');
    }
  }

  /** 游戏状态直接携带 player.guild，无需先访问军团大厅加载详情缓存。 */
  function chatGuild() {
    var player = Core.state && Core.state.player;
    if (player && player.guild && player.guild.id) return player.guild;
    if (player && player.guildId) return { id: player.guildId };
    // 有完整玩家状态时，缺少 guild 即表示未加入，不能沿用已退出的缓存。
    if (player) return null;
    return G.Guild && G.Guild.mine && G.Guild.mine.joined ? G.Guild.mine : null;
  }

  /** 仅军团身份变化时重建频道，普通状态刷新保留草稿、消息节点和滚动位置。 */
  function syncGuildChatPanel() {
    var box = document.getElementById('chatGuildChannelWrap');
    if (!box) return;
    var guild = chatGuild();
    var id = guild ? String(guild.id) : '';
    if (box.getAttribute('data-chat-guild-id') === id) return;
    if (G.Chat && G.Chat.guildLog) G.Chat.guildLog.length = 0;
    box.setAttribute('data-chat-guild-id', id);
    box.innerHTML = renderGuildChatInPage();
    if (guild && selectedChatChannel === 'guild' && G.Chat) G.Chat.loadGuildHistory();
  }

  function renderGuildChatInPage() {
    var g = chatGuild();
    var hasGuild = !!g;
    var h = '';
    if (!hasGuild) {
      h += '<div style="flex:1 1 0;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:24px;text-align:center;color:var(--muted);min-height:0;">';
      h += '<span style="font-size:36px;margin-bottom:8px;">🪖</span>';
      h += '<p style="font-size:14px;font-weight:600;color:var(--ink);margin:0 0 6px;">尚未加入任何军团</p>';
      h += '<p style="font-size:12px;margin:0 0 16px;">加入或创建军团后即可启用加密战术内频，与全团战友实时通联。</p>';
      h += '<button type="button" class="btn ok" onclick="Game.go(\'guild\')">前往军团大厅</button>';
      h += '</div>';
      return h;
    }

    h += '<div class="chat-terminal" style="flex:1 1 0;display:flex;flex-direction:column;min-height:0;">';
    h += '<div class="chat-term-header">';
    h += '<div class="term-header-left">';
    h += '<span class="term-led"></span>';
    h += '<span class="term-title">COMM-LINK // 军团加密内频</span>';
    h += '</div>';
    h += '<div class="term-header-right">';
    h += '<span class="term-freq">FREQ: GUILD-' + (g.id || 0) + ' · ENCRYPTED</span>';
    h += '<span class="term-tag">ONLINE</span>';
    h += '</div>';
    h += '</div>';
    h += '<div class="chat-box" id="chatPageGuildBox" style="flex:1 1 0;min-height:0;">';
    var guildMsgs = (G.Chat && G.Chat.recentGuild) ? G.Chat.recentGuild(50) : [];
    for (var gi = 0; gi < guildMsgs.length; gi++) {
      if (G.Chat && G.Chat.renderGuildMessageHtml) {
        h += G.Chat.renderGuildMessageHtml(guildMsgs[gi], gi > 0 ? guildMsgs[gi - 1] : null);
      }
    }
    h += '</div>';
    var guildCd = (G.Chat && G.Chat.getGuildCooldown) ? G.Chat.getGuildCooldown() : 0;
    h += '<div class="chat-input-bar">';
    h += '<span class="chat-prompt">&gt;</span>';
    h += '<input class="chat-input" id="chatPageGuildInput" type="text" maxlength="80" enterkeyhint="send" placeholder="善言结善缘 恶语伤人心" autocomplete="off" onkeydown="if(event.key===\'Enter\'){Game.Main.sendGuildChat(\'chatPageGuildInput\',\'chatPageGuildSendBtn\');}"/>';
    h += '<button class="chat-send' + (guildCd > 0 ? ' disabled' : '') + '" id="chatPageGuildSendBtn"' + (guildCd > 0 ? ' disabled' : '') + ' onclick="Game.Main.sendGuildChat(\'chatPageGuildInput\',\'chatPageGuildSendBtn\')">' + (guildCd > 0 ? G.formatDuration(guildCd) : '发送') + '</button>';
    h += '</div>';
    h += '</div>';
    return h;
  }

  function renderChatMessagesPanel() {
    var h = '<div style="display:flex;flex-direction:column;flex:1 1 0;min-height:0;min-width:0;">';
    h += '<div class="chat-channel-switcher">';
    h += '<button type="button" id="chatChanBtn_world" class="chat-channel-btn active" onclick="Game.MainView.selectChatChannel(\'world\')">🌐 公频 · 世界</button>';
    h += '<button type="button" id="chatChanBtn_guild" class="chat-channel-btn" onclick="Game.MainView.selectChatChannel(\'guild\')">🪖 内频 · 军团</button>';
    h += '<button type="button" id="chatChanBtn_private" class="chat-channel-btn" onclick="Game.MainView.selectChatChannel(\'private\')">✉ 私聊<span id="privateChatUnread"></span></button>';
    h += '</div>';
    h += '<div id="chatWorldChannelWrap" style="display:flex;flex:1 1 0;flex-direction:column;min-height:0;min-width:0;">' + renderWorldChat(50) + '</div>';
    h += '<div id="chatGuildChannelWrap" data-chat-guild-id="' + (chatGuild() ? Number(chatGuild().id) : '') + '" style="display:none;flex:1 1 0;flex-direction:column;min-height:0;min-width:0;">' + renderGuildChatInPage() + '</div>';
    h += '<div id="chatPrivateChannelWrap" style="display:none;flex:1 1 0;flex-direction:column;min-height:0;">' + (G.PrivateChat ? G.PrivateChat.render() : '') + '</div>';
    h += '</div>';
    return h;
  }

  var selectedChatChannel = 'world';

  function selectChatChannel(channel) {
    selectedChatChannel = channel;
    syncGuildChatPanel();
    var privateBox = document.getElementById('chatPrivateChannelWrap');
    var privateButton = document.getElementById('chatChanBtn_private');
    if (privateBox) privateBox.style.display = channel === 'private' ? 'flex' : 'none';
    if (privateButton) privateButton.classList.toggle('active', channel === 'private');
    if (G.PrivateChat) G.PrivateChat.active = channel === 'private';
    if (channel === 'private') {
      ['chatWorldChannelWrap', 'chatGuildChannelWrap'].forEach(function (id) {
        var el = document.getElementById(id); if (el) el.style.display = 'none';
      });
      ['chatChanBtn_world', 'chatChanBtn_guild'].forEach(function (id) {
        var el = document.getElementById(id); if (el) el.classList.remove('active');
      });
      document.getElementById('chatPageTitle').textContent = '玩家私聊';
      document.getElementById('chatPageSubtitle').textContent = '仅双方可见';
      if (G.PrivateChat) G.PrivateChat.showList();
      return;
    }
    var worldBox = document.getElementById('chatWorldChannelWrap');
    var guildBox = document.getElementById('chatGuildChannelWrap');
    var btnWorld = document.getElementById('chatChanBtn_world');
    var btnGuild = document.getElementById('chatChanBtn_guild');
    var title = document.getElementById('chatPageTitle');
    var sub = document.getElementById('chatPageSubtitle');
    if (channel === 'guild') {
      if (worldBox) worldBox.style.display = 'none';
      if (guildBox) guildBox.style.display = 'flex';
      if (btnWorld) btnWorld.classList.remove('active');
      if (btnGuild) btnGuild.classList.add('active');
      if (title) title.textContent = '军团频道';
      if (sub) sub.textContent = '战术内频';
      if (G.Chat && G.Chat.loadGuildHistory) G.Chat.loadGuildHistory();
    } else {
      if (worldBox) worldBox.style.display = 'flex';
      if (guildBox) guildBox.style.display = 'none';
      if (btnWorld) btnWorld.classList.add('active');
      if (btnGuild) btnGuild.classList.remove('active');
      if (title) title.textContent = '世界频道';
      if (sub) sub.textContent = '实时通联';
    }
  }

  /** 独立聊天页默认展示消息；好友和论坛按需加载，切换时保留消息草稿。 */
  Core.views.chat = function (v) {
    // 后台状态刷新保留聊天输入和滚动位置，实时消息由 Chat 增量追加。
    if (v.querySelector && v.querySelector('.chat-page')) {
      syncGuildChatPanel();
      if (G.Chat) G.Chat.updateSendBtnUI();
      if (G.Chat) G.Chat.updateGuildSendBtnUI();
      return;
    }
      var friendReqCount = G.Friends && G.Friends.unread ? G.Friends.unread() : 0;
      var friendBadgeHtml = '<span class="tab-badge" id="friendRequestTabBadge"' + (friendReqCount > 0 ? '' : ' style="display:none;"') + '>' + (friendReqCount > 99 ? '99+' : friendReqCount) + '</span>';
      var pmUnreadCount = G.PrivateChat && G.PrivateChat.unread ? G.PrivateChat.unread() : 0;
      var pmBadgeHtml = '<span class="tab-badge" id="chatMessagesTabBadge"' + (pmUnreadCount > 0 ? '' : ' style="display:none;"') + '>' + (pmUnreadCount > 99 ? '99+' : pmUnreadCount) + '</span>';
      v.innerHTML = '<section class="chat-page" aria-label="聊天">' +
        '<header class="chat-page-header page-backbar">' +
        '<button type="button" class="chat-back-button" onclick="Game.back()" aria-label="返回上一步">‹</button>' +
        '<h1 id="chatPageTitle">世界频道</h1><span id="chatPageSubtitle">实时通联</span></header>' +
        '<div id="chatMessagesPanel" class="chat-tab-panel" aria-label="消息">' + renderChatMessagesPanel() + '</div>' +
        '<div id="chatForumPanel" class="chat-tab-panel" aria-label="论坛" hidden></div>' +
        '<div id="chatFriendsPanel" class="chat-tab-panel" aria-label="山河战友" hidden>' + (G.Friends && G.Friends.render ? G.Friends.render() : '') + '</div>' +
        '<nav class="chat-bottom-tabs" aria-label="聊天导航">' +
        '<button type="button" class="chat-bottom-tab active" data-chat-tab="messages" aria-controls="chatMessagesPanel" aria-current="page" onclick="Game.MainView.selectChatTab(\'messages\')"><span aria-hidden="true">☏</span>消息' + pmBadgeHtml + '</button>' +
        '<button type="button" class="chat-bottom-tab" data-chat-tab="friends" aria-controls="chatFriendsPanel" onclick="Game.MainView.selectChatTab(\'friends\')"><span aria-hidden="true">♙</span>山河战友' + friendBadgeHtml + '</button>' +
        '<button type="button" class="chat-bottom-tab" data-chat-tab="forum" aria-controls="chatForumPanel" onclick="Game.MainView.selectChatTab(\'forum\')"><span aria-hidden="true">▤</span>论坛</button>' +
        '</nav></section>';
    var box = document.getElementById('worldChatBox');
    if (box) box.scrollTop = box.scrollHeight;
    selectedChatChannel = 'world';
    if (G.PrivateChat) { G.PrivateChat.active = false; G.PrivateChat.refresh(); }
  };

  Core.views.settings = function (v) {
    var h = '';
    h += '<div class="settings-view">';

    h += '<div class="settings-card">';
    h += '<div class="settings-row settings-row-between">';
    h += '<span class="settings-label"><span class="settings-icon">🎨</span> 界面风格</span>';
    var curTheme = (G.Theme && G.Theme.get) ? G.Theme.get() : 'blue-white-classic';
    h += '<select id="themeSelector" class="settings-select" onchange="if(Game.Theme)Game.Theme.set(this.value)">';
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

    var vipCardVisible = (!G.Vip || typeof G.Vip.isHomeCardVisible !== 'function') ? true : G.Vip.isHomeCardVisible();
    h += '<div class="settings-row settings-row-between" style="margin-top:8px;padding-top:8px;border-top:1px dashed var(--line-light, rgba(0, 0, 0, 0.08));">';
    h += '<div class="settings-label-group">';
    h += '<span class="settings-label"><span class="settings-icon">🎖️</span> 首页特勤部</span>';
    h += '<span class="settings-subtext" style="display:block;margin-top:2px;">在首页展示特勤统帅部与每日军饷快捷入口</span>';
    h += '</div>';
    h += '<select id="homeVipCardSelector" class="settings-select" aria-label="首页特勤部卡片显示开关" onchange="if(Game.Vip&&Game.Vip.setHomeCardVisible)Game.Vip.setHomeCardVisible(this.value===\'1\')">';
    h += '<option value="1"' + (vipCardVisible ? ' selected' : '') + '>开启 (默认)</option>';
    h += '<option value="0"' + (!vipCardVisible ? ' selected' : '') + '>关闭</option>';
    h += '</select>';
    h += '</div>';
    h += '</div>';

    if (G.Sound) h += G.Sound.renderSettings();

    h += '<div class="settings-card settings-account-card">';
    h += '<div class="settings-card-head">';
    h += '<span class="settings-card-title"><span class="settings-icon">👤</span> 账号与安全</span>';
    if (G.API && G.API.isLoggedIn()) {
      h += '<span class="settings-status-pill success">云端已同步</span>';
      h += '</div>';
      h += '<div class="settings-account-body">';
      h += '<div class="settings-account-user-row">';
      h += '<span class="settings-account-name">当前登录：<b>' + G.escapeHtml(G.API.getUsername() || '未知') + '</b></span>';
      h += '<button class="btn sm" onclick="Game.Main.logout()">切换账号</button>';
      h += '</div>';
      if (G.Protection && G.Protection.data && G.Protection.data.enabled !== false) {
        h += '<div class="settings-account-sub-row">';
        h += '<button class="btn sm settings-protection-btn" onclick="Game.Protection.open()">🛡️ 实名、防沉迷与家长监护</button>';
        h += '</div>';
      }
      h += '</div>';
    } else if (G.Main && G.Main.guestMode) {
      h += '<span class="settings-status-pill warning">游客模式</span>';
      h += '</div>';
      h += '<div class="settings-account-body">';
      h += '<div class="settings-account-user-row">';
      h += '<span class="settings-subtext">建议登录账号以保证进度不丢失</span>';
      h += '<button class="btn sm ok" onclick="Game.go(\'login\')">登录/注册</button>';
      h += '</div>';
      h += '</div>';
    } else {
      h += '<span class="settings-status-pill muted">未登录</span>';
      h += '</div>';
      h += '<div class="settings-account-body">';
      h += '<div class="settings-account-user-row">';
      h += '<span class="settings-subtext">登录后可保存多端游戏进度</span>';
      h += '<button class="btn sm ok" onclick="Game.go(\'login\')">立即登录</button>';
      h += '</div>';
      h += '</div>';
    }
    h += '</div>';

    h += '<div class="settings-card settings-about-card">';
    h += '<div class="settings-about-row">';
    h += '<div class="settings-app-info">';
    h += '<span class="settings-app-name">山河远征录</span>';
    h += '<span class="settings-app-ver">v1.0.0</span>';
    h += '<span class="settings-app-tag">文字战争策略</span>';
    h += '</div>';
    if (G.API && G.API.isLoggedIn()) {
      h += '<button class="btn sm warn settings-exit-btn" onclick="Game.Main.logout(\'exit\')">退出登录</button>';
    }
    h += '</div>';

    if (G.API && G.API.isLoggedIn()) {
      if (!(G.Main && G.Main.guestMode) && (G.API.getUsername() || '').indexOf('游客_') !== 0) {
        h += '<div class="settings-danger-row">';
        h += '<div class="settings-danger-info">';
        h += '<span class="settings-danger-title">注销账号</span>';
        h += '<span class="settings-danger-desc">永久注销并清理本账号所有游戏进度</span>';
        h += '</div>';
        h += '<button class="btn sm account-delete-entry" onclick="Game.Main.openDisableAccount()">注销账号</button>';
        h += '</div>';
      }
    }
    h += '</div>';

    h += '</div>';

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

  var hiddenProtectionNotices = {};

  /** 关闭偏好仅属于当前账号，不能让其他账号继承；不改变服务端保护状态。 */
  function protectionNoticeStorageKey() {
    var player = (Core.state || {}).player || {};
    var id = player.id != null ? player.id : player.username;
    return id != null ? 'wargame_newbie_notice_hidden_' + id : null;
  }

  function shouldShowNewbieProtectionNotice() {
    if (!Core.newbieProtectionInfo().active) return false;
    var key = protectionNoticeStorageKey();
    if (!key) return true;
    if (hiddenProtectionNotices[key]) return false;
    try { return window.localStorage.getItem(key) !== '1'; } catch (e) { return true; }
  }

  /** 只在二次确认后隐藏首页说明，档案中始终可查看有效保护的倒计时。 */
  function dismissNewbieProtectionNotice() {
    var key = protectionNoticeStorageKey();
    if (!key || !shouldShowNewbieProtectionNotice()) return;
    G.World.showConfirm({
      title: '永久关闭首页提示？',
      message: '是否永久关闭当前账号的首页新手保护提示？关闭后，可点击头像，在「指挥官档案」中查看新手保护倒计时和规则。',
      subMessage: '此操作仅关闭首页提示，新手保护仍然生效。',
      okText: '永久关闭提示',
      cancelText: '保留提示',
      onConfirm: function () {
        // 确认期间若切换账号，不能把旧账号的选择应用到新账号。
        if (protectionNoticeStorageKey() !== key) return;
        hiddenProtectionNotices[key] = true;
        var saved = false;
        try { window.localStorage.setItem(key, '1'); saved = true; } catch (e) {}
        var notice = document.getElementById('newbieProtectionNotice');
        if (notice) notice.hidden = true;
        G.toast(saved ? '首页提示已关闭，可点击头像在指挥官档案中查看保护倒计时' : '首页提示已关闭；浏览器无法保存设置，刷新后可能重新显示。倒计时可在指挥官档案中查看');
      }
    });
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
    var protectionNotice = document.getElementById('newbieProtectionNotice');
    if (protectionNotice) {
      protectionNotice.hidden = !shouldShowNewbieProtectionNotice();
      var protectionText = document.getElementById('newbieProtectionNoticeText');
      if (!protectionNotice.hidden && protectionText) protectionText.textContent = Core.cityStatusText().detail;
    }

    // 2. 军情与战报警讯增量更新
    var alertsWrap = document.getElementById('homeAlertsWrap');
    if (alertsWrap) {
      var marches = Core.militaryAlertMarches();
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
    var netFood = Core.foodProductionPerHour() - Core.foodPerHour();
    var netSteel = Core.produceOf('refinery');
    var netOil = Core.produceOf('oilfield');
    var netRare = Core.produceOf('raremine');
    var financeBonus = Core.mayorSkillBonus ? Core.mayorSkillBonus('finance') : 0;
    var goldRate = Math.floor(Core.civilianPopulation() * (s.tax / 100) * (1 + (mayor ? mayor.knowledge / 100 : 0)) * (1 + financeBonus) * 2);
    var cap = Core.capacity();
    var caps = { food: cap.food, steel: cap.steel, oil: cap.oil, rare: cap.rare, gold: cap.gold };
    var nets = { food: netFood, steel: netSteel, oil: netOil, rare: netRare, gold: goldRate };
    var resKeys = G.Constants.resourceKeysWithGold;

    for (var ri = 0; ri < resKeys.length; ri++) {
      var rk = resKeys[ri];
      var card = v.querySelector('.res-card[data-res-card="' + rk + '"]');
      if (!card) continue;
      var cur = r[rk] || 0;
      var maxR = caps[rk] || (rk === 'gold' ? 20000000 : 999999);
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
      card.title = (rinfo.name || rk) + ' 当前: ' + G.fmt(cur) + (isCapped ? ' (已达生产容量' + (net === 0 ? '，停止产出' : '，' + sign + G.fmt(net) + '/h') + ')' : ' (' + sign + G.fmt(net) + '/h)');
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
    selectChatTab: selectChatTab,
    selectChatChannel: selectChatChannel,
    renderArmySummaryList: renderArmySummaryList,
    showArmySummaryFullscreen: showArmySummaryFullscreen,
    renderFortSummaryList: renderFortSummaryList,
    showFortSummaryFullscreen: showFortSummaryFullscreen,
    renderOfficerSummaryCard: renderOfficerSummaryCard,
    showUnitDetailModal: showUnitDetailModal,
    showResourceDetail: showResourceDetail,
    showPopulationDetailModal: showPopulationDetailModal,
    getCurrentAvatar: getCurrentAvatar,
    getMilitaryRankTitle: getMilitaryRankTitle,
    dismissNewbieProtectionNotice: dismissNewbieProtectionNotice,
    presetAvatars: PRESET_AVATARS,
    silentUpdateHome: silentUpdateHome
  };

  if (G.Main) {
    G.Main.silentUpdateHome = silentUpdateHome;
  }
})(window.Game);
