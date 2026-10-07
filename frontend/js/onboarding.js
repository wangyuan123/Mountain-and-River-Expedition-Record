/* global window, document */
(function (G) {
  'use strict';
  var Core = G.Core;
  var state = { data: null, timer: null, pending: null, busy: false, epoch: 0, error: '', snoozedFor: '', guidedAction: null, actionNote: '', expanded: false, screenWasInert: false, reportReading: null, gatherTracking: null, recommendedGatherTargetId: null };
  var labels = G.Constants.resourceNames;
  var currentSpotlightTarget = null;
  var graduationSpotlightTimer = null;

  var spotlightListenersAttached = false;

  function reacquireSpotlightTarget() {
    var guided = state.guidedAction;
    if (guided && typeof document !== 'undefined') {
      if (guided.route === 'army' && guided.building) {
        var uCard = (document.getElementById && document.getElementById('unit-card-' + guided.building)) ||
          (document.querySelector && document.querySelector('[data-unit="' + guided.building + '"]'));
        if (uCard) {
          var rBtn = uCard.querySelector ? (uCard.querySelector('.recruit-btn:not(.warn)') || uCard.querySelector('button.recruit-btn')) : null;
          return rBtn || uCard;
        }
      }
      if (guided.route === 'tech' && guided.building) {
        var tCard = (document.querySelector && document.querySelector('[data-tech="' + guided.building + '"]')) ||
          (document.getElementById && document.getElementById('tech-card-' + guided.building));
        if (tCard) {
          var resBtn = tCard.querySelector ? (tCard.querySelector('button.ok') || tCard.querySelector('button')) : null;
          return resBtn || tCard;
        }
      }
      if ((guided.route === 'buildArmy' || guided.route === 'buildRes') && guided.building) {
        var pItem = (document.querySelector && document.querySelector('.build-picker-item[data-building="' + guided.building + '"]')) ||
          (document.getElementById && document.getElementById('build-picker-' + guided.building));
        if (pItem) {
          var pBtn = pItem.querySelector ? (pItem.querySelector('.build-picker-action button') || pItem.querySelector('button')) : null;
          return pBtn || pItem;
        }
        var bUpBtn = document.getElementById ? document.getElementById('bdetailUpBtn') : null;
        if (bUpBtn) return bUpBtn;
      }
      if (guided.route === 'academy') {
        var officerCard = (document.querySelector && document.querySelector('.officer-card.ok')) ||
          (document.querySelector && document.querySelector('.officer-card'));
        if (officerCard) {
          var recruitAct = officerCard.querySelector ? (
            officerCard.querySelector('.officer-recruit-act .link-act:not(.disabled)') ||
            officerCard.querySelector('.link-act:not(.disabled)')
          ) : null;
          return recruitAct || officerCard;
        }
      }
    }
    var refreshed = document.querySelector ? document.querySelector('.army-free-speedup-btn, .army-queue-btn, #buildQueueBar button, button[onclick*="openSpeedUpPicker"], button[onclick*="launchDispatch"]') : null;
    return refreshed;
  }

  function updateSpotlightFramePosition() {
    if (!currentSpotlightTarget || !currentSpotlightTarget.getBoundingClientRect) return;
    if (typeof document !== 'undefined' && document.body && document.body.contains && !document.body.contains(currentSpotlightTarget)) {
      var reacquired = reacquireSpotlightTarget();
      if (reacquired && typeof document !== 'undefined' && document.body && document.body.contains && document.body.contains(reacquired)) {
        showSpotlight(reacquired);
        return;
      }
      clearSpotlight();
      return;
    }
    var mask = document.getElementById('onboardingSpotlightMask');
    if (!mask) return;
    var rect = currentSpotlightTarget.getBoundingClientRect();
    if (rect.width === 0 && rect.height === 0) return;

    var view = typeof document !== 'undefined' && document.getElementById ? document.getElementById('view') : null;
    var vRect = view && view.getBoundingClientRect ? view.getBoundingClientRect() : null;
    // 任务入口位于内容滚动区之外，毕业高亮应以整个视口判断可见性。
    if (currentSpotlightTarget.closest && currentSpotlightTarget.closest('#navbar')) vRect = null;
    var winH = typeof window !== 'undefined' ? (window.innerHeight || (document.documentElement && document.documentElement.clientHeight) || 800) : 800;
    var minTop = vRect ? vRect.top : 0;
    var maxBottom = vRect ? Math.min(winH, vRect.bottom) : winH;

    // 如果目标已被滚出可视区域，将镂空框移至屏幕外，同时保持遮罩显示覆盖全屏，防止遮罩消失或错位在顶栏
    if (rect.bottom <= minTop || rect.top >= maxBottom) {
      if (!mask.style) mask.style = {};
      mask.style.top = '-999px';
      mask.style.left = '-999px';
      mask.style.width = '10px';
      mask.style.height = '10px';
      mask.style.display = 'block';
      return;
    }

    var pad = 4;
    if (!mask.style) mask.style = {};
    mask.style.top = Math.floor(rect.top - pad) + 'px';
    mask.style.left = Math.floor(rect.left - pad) + 'px';
    mask.style.width = Math.ceil(rect.width + pad * 2) + 'px';
    mask.style.height = Math.ceil(rect.height + pad * 2) + 'px';
    mask.style.display = 'block';
  }

  function handleSpotlightGlobalClick(e) {
    if (e.target && e.target.closest && (
      e.target.closest('.modal-mask') ||
      e.target.closest('.spicker') ||
      e.target.closest('.spicker-row') ||
      e.target.closest('.army-queue-btn') ||
      e.target.closest('button[onclick*="SpeedUp"]') ||
      e.target.closest('button[onclick*="speedUp"]') ||
      e.target.closest('button[onclick*="accelerateJob"]') ||
      e.target.closest('button[onclick*="launchDispatch"]') ||
      e.target.closest('.officer-recruit-act') ||
      e.target.closest('.link-act')
    )) {
      return;
    }
    if (!currentSpotlightTarget || !currentSpotlightTarget.getBoundingClientRect) return;
    if (typeof document !== 'undefined' && document.body && document.body.contains && !document.body.contains(currentSpotlightTarget)) {
      var refreshed = reacquireSpotlightTarget();
      if (refreshed && typeof document !== 'undefined' && document.body && document.body.contains && document.body.contains(refreshed)) {
        showSpotlight(refreshed);
      } else {
        clearSpotlight();
        return;
      }
    }
    var rect = currentSpotlightTarget.getBoundingClientRect();

    var view = typeof document !== 'undefined' && document.getElementById ? document.getElementById('view') : null;
    var vRect = view && view.getBoundingClientRect ? view.getBoundingClientRect() : null;
    var winH = typeof window !== 'undefined' ? (window.innerHeight || (document.documentElement && document.documentElement.clientHeight) || 800) : 800;
    var minTop = vRect ? vRect.top : 0;
    var maxBottom = vRect ? Math.min(winH, vRect.bottom) : winH;

    // 如果目标滚出了可视区域，用户在页面上点击时自动平滑回滚到目标居中
    if (rect.bottom <= minTop || rect.top >= maxBottom) {
      if (currentSpotlightTarget.scrollIntoView) {
        currentSpotlightTarget.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
      }
      e.stopPropagation();
      e.preventDefault();
      return;
    }

    var pad = 12;
    if (e.clientX >= rect.left - pad && e.clientX <= rect.right + pad &&
        e.clientY >= rect.top - pad && e.clientY <= rect.bottom + pad) {
      return;
    }
    if (currentSpotlightTarget.contains && currentSpotlightTarget.contains(e.target)) {
      return;
    }
    e.stopPropagation();
    e.preventDefault();
  }

  function attachSpotlightListeners() {
    if (spotlightListenersAttached || typeof window === 'undefined' || !window.addEventListener) return;
    spotlightListenersAttached = true;
    window.addEventListener('click', handleSpotlightGlobalClick, true);
    window.addEventListener('pointerdown', handleSpotlightGlobalClick, true);
    window.addEventListener('scroll', updateSpotlightFramePosition, true);
    window.addEventListener('resize', updateSpotlightFramePosition, true);
  }

  function detachSpotlightListeners() {
    if (!spotlightListenersAttached || typeof window === 'undefined' || !window.removeEventListener) return;
    spotlightListenersAttached = false;
    window.removeEventListener('click', handleSpotlightGlobalClick, true);
    window.removeEventListener('pointerdown', handleSpotlightGlobalClick, true);
    window.removeEventListener('scroll', updateSpotlightFramePosition, true);
    window.removeEventListener('resize', updateSpotlightFramePosition, true);
  }

  function showSpotlight(target) {
    if (!target) return;
    if (Array.isArray(target)) target = target[0];
    if (!target) return;
    var bar = typeof document !== 'undefined' && document.getElementById ? document.getElementById('onboardingBar') : null;
    if (bar && (!bar.contains || !bar.contains(target))) {
      return;
    }
    var mask = document.getElementById('onboardingSpotlightMask');
    if (!mask) {
      mask = document.createElement('div');
      mask.id = 'onboardingSpotlightMask';
      mask.className = 'ob-spotlight-mask';
      mask.setAttribute('aria-hidden', 'true');
      if (document.body && document.body.appendChild) {
        document.body.appendChild(mask);
      }
    }
    if (currentSpotlightTarget && currentSpotlightTarget !== target) {
      if (currentSpotlightTarget.classList && currentSpotlightTarget.classList.remove) {
        currentSpotlightTarget.classList.remove('ob-spotlight-focus');
      }
    }
    currentSpotlightTarget = target;
    if (target.classList && target.classList.add) {
      target.classList.add('ob-spotlight-focus');
    }
    if (target.scrollIntoView) {
      target.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
    }
    var p = target.parentElement;
    while (p && p !== document.body) {
      if (p.scrollHeight > p.clientHeight && p.getBoundingClientRect && target.getBoundingClientRect) {
        var tRect = target.getBoundingClientRect();
        var pRect = p.getBoundingClientRect();
        var offset = (tRect.top + tRect.height / 2) - (pRect.top + pRect.height / 2);
        if (Math.abs(offset) > 10) {
          p.scrollTop += offset;
        }
      }
      p = p.parentElement;
    }
    attachSpotlightListeners();
    updateSpotlightFramePosition();
    setTimeout(updateSpotlightFramePosition, 40);
    setTimeout(updateSpotlightFramePosition, 150);
  }

  function clearSpotlight() {
    if (graduationSpotlightTimer) clearTimeout(graduationSpotlightTimer);
    graduationSpotlightTimer = null;
    detachSpotlightListeners();
    if (currentSpotlightTarget) {
      if (currentSpotlightTarget.classList && currentSpotlightTarget.classList.remove) {
        currentSpotlightTarget.classList.remove('ob-spotlight-focus');
      }
      currentSpotlightTarget = null;
    }
    var mask = document.getElementById('onboardingSpotlightMask');
    if (mask) {
      if (mask.remove) mask.remove();
      else if (mask.parentNode && mask.parentNode.removeChild) mask.parentNode.removeChild(mask);
    }
  }

  function getBuildingLevels(id) {
    if (Core.buildingLevels) return Core.buildingLevels(id);
    if (!Core.state || !Core.state.buildings) return [];
    var v = Core.state.buildings[id];
    if (v == null) return [];
    if (Array.isArray(v)) return v;
    return [v];
  }

  function getBuildingLevel(id) {
    if (Core.buildingLevel) return Core.buildingLevel(id);
    var arr = getBuildingLevels(id);
    var sum = 0;
    for (var i = 0; i < arr.length; i++) sum += arr[i] || 0;
    return sum;
  }

  function findRecommendedDevelopBuilding() {
    var candidates = ['refinery', 'oilfield', 'raremine'];
    for (var i = 0; i < candidates.length; i++) {
      var id = candidates[i];
      if (getBuildingLevel(id) < 1) {
        return id;
      }
    }
    return 'farm';
  }

  /** 仅在当前账号仍参与引导时高亮确认按钮，延迟检查也必须遵守跳过状态。 */
  function canSpotlightConfirm(key) {
    var data = state.data;
    return key === context() && Core.state && G.API.isLoggedIn() &&
      data && data.enrolled === true && !data.paused && !data.done && !state.busy;
  }

  function hookBuildConfirm() {
    if (!G.Build || !G.Build.confirmUpgrade || G.Build._onboardingHooked) return;
    G.Build._onboardingHooked = true;
    var origConfirm = G.Build.confirmUpgrade;
    G.Build.confirmUpgrade = function (id, slotIdx) {
      var res = origConfirm.apply(this, arguments);
      var key = context();
      if (!canSpotlightConfirm(key)) return res;
      var checkOkBtn = function () {
        if (!canSpotlightConfirm(key)) return;
        var cuOk = document.getElementById ? document.getElementById('cuOk') : null;
        if (cuOk && !cuOk.disabled) {
          showSpotlight(cuOk);
        }
      };
      checkOkBtn();
      setTimeout(checkOkBtn, 50);
      return res;
    };
  }
  hookBuildConfirm();

  function hookTechConfirm() {
    if (!G.Tech || !G.Tech.confirmResearch || G.Tech._onboardingHooked) return;
    G.Tech._onboardingHooked = true;
    var origConfirm = G.Tech.confirmResearch;
    G.Tech.confirmResearch = function (id) {
      var res = origConfirm.apply(this, arguments);
      var key = context();
      if (!canSpotlightConfirm(key)) return res;
      var checkOkBtn = function () {
        if (!canSpotlightConfirm(key)) return;
        var cuOk = document.getElementById ? document.getElementById('cuOk') : null;
        if (cuOk && !cuOk.disabled) {
          showSpotlight(cuOk);
        }
      };
      checkOkBtn();
      setTimeout(checkOkBtn, 50);
      return res;
    };
  }
  hookTechConfirm();

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function context() { return [state.epoch, G.API.getToken(), Core.state && Core.state.player && Core.state.player.id].join(':'); }
  function button(label, action, value, disabled) {
    return '<button class="btn" data-ob-action="' + action + '" data-ob-value="' + esc(value || '') + '"' +
      (disabled || state.busy ? ' disabled' : '') + '>' + esc(label) + '</button>';
  }
  function routeButton(label, route, building) { return button(label, 'route', route + ':' + (building || '')); }
  function buildButton(label, name, route, building) {
    var pending = (Core.state && Core.state.constructions || []).some(function (job) {
      return job.id === building && job.action !== 'dismantle';
    });
    return routeButton(pending ? '施工中 · 查看' + name : label, route, building);
  }
  function hasSupply() { return state.data && (state.data.supplies || []).some(function (s) { return s.available && !s.claimed; }); }
  function applyBalances(data) {
    if (!data || !data.balances || !Core.state || !Core.state.resources) return;
    Object.keys(data.balances).forEach(function (key) { Core.state.resources[key] = data.balances[key]; });
    if (Core.renderTop) Core.renderTop();
  }

  function completedCheckChanged(previous, next) {
    var objective = previous && previous.current && previous.current.id;
    if (!objective || !next) return false;
    var before = (previous.objectives || []).find(function (item) { return item.id === objective; });
    var after = (next.objectives || []).find(function (item) { return item.id === objective; });
    return !!(before && after && !before.complete && after.complete);
  }

  /** 只有已参加、未暂停且未完成的行动需要轮询；领取剩余补给由操作响应更新。 */
  function syncPolling() {
    var data = state.data;
    var active = Core.state && G.API.isLoggedIn() && data && data.enrolled === true && !data.paused && !data.done && !state.busy;
    if (!active) {
      if (state.timer) clearInterval(state.timer);
      state.timer = null;
    } else if (!state.timer) {
      state.timer = setInterval(refresh, 5000);
    }
  }

  /** 防止换账号后的旧响应复活提示；定时刷新只改目标栏，不重绘正在编辑的游戏表单。 */
  function refresh() {
    if (!Core.state || !G.API.isLoggedIn()) { stop(); return Promise.resolve(null); }
    if (state.busy) return Promise.resolve(state.data);
    // 暂停/恢复等操作尚未完成时，不发起可能读到旧状态的刷新请求。
    if (state.pending) return state.pending;
    var key = context();
    var request = G.API.client.get('/game/onboarding', { silent: true }).then(function (data) {
      if (key !== context()) return null;
      state.error = '';
      if (completedCheckChanged(state.data, data) ||
          // 召回或侦察失败后仍需返回本步，避免没有完成目标时弹窗一直保持收起。
          (state.data && state.data.current && state.data.current.id === 'scout' &&
            state.data.waitingForScoutReturn && !data.waitingForScoutReturn) ||
          // 民居是军工厂前置，建成后重新提示建造军工厂
          (state.data && state.data.current && state.data.current.id === 'factory' &&
            !state.data.checks.house && data.checks && data.checks.house) ||
          // 军需仓库是军工科技研发中心前置，建成后重新提示建造军工科技研发中心
          (state.data && state.data.current && state.data.current.id === 'lab' &&
            !state.data.checks.depot && data.checks && data.checks.depot) ||
          // 防空雷达站是侦察技术前置，建成后重新提示前往科技页研发
          (state.data && state.data.current && state.data.current.id === 'reconTech' &&
            !state.data.checks.radar && data.checks && data.checks.radar) ||
          // 军校只是招募步骤的前置，建成后也要重新提示玩家前往军校招募。
          (state.data && state.data.current && state.data.current.id === 'officer' &&
            !state.data.checks.academy && data.checks && data.checks.academy) ||
          (state.data && state.data.current && data.current && state.data.current.id !== data.current.id)) {
        state.snoozedFor = ''; state.guidedAction = null; state.actionNote = '';
        clearSpotlight();
      }
      if (data && data.done) clearSpotlight();
      if (JSON.stringify(state.data) !== JSON.stringify(data)) { state.data = data; render(); }
      applyBalances(data);
      syncPolling();
      return data;
    }).catch(function (err) {
      if (key === context()) { state.error = err.message || '行动状态暂时无法读取'; render(); }
      return null;
    }).finally(function () { if (state.pending === request) state.pending = null; });
    state.pending = request;
    return request;
  }

  function stop() {
    state.epoch++;
    if (state.timer) clearInterval(state.timer);
    clearReportReading();
    clearGatherTracking();
    state.recommendedGatherTargetId = null;
    clearSpotlight();
    state.timer = null; state.pending = null; state.data = null; state.error = ''; state.busy = false;
    state.snoozedFor = ''; state.guidedAction = null; state.actionNote = ''; state.expanded = false;
    closeModal();
  }
  function init() {
    stop();
    hookBuildConfirm();
    hookTechConfirm();
    return refresh();
  }

  function actionStarted(route, id) {
    var data = state.data, guided = state.guidedAction;
    var isCurrentArmy = route === 'army' && data && data.current && (
      (data.current.id === 'infantry' && id === 'infantry') ||
      (data.current.id === 'train' && ['infantry', 'truck'].includes(id)) ||
      (data.current.id === 'recon' && id === 'scout')
    );
    if (!data || !data.current || data.paused || data.done) return Promise.resolve(null);
    if (!isCurrentArmy) {
      if (!guided || state.snoozedFor !== data.current.id || guided.route !== route ||
          (guided.building && guided.building !== id)) return Promise.resolve(null);
      if (route === 'tech' && data.current.id === 'reconTech' && id !== 'recon_level') return Promise.resolve(null);
    }
    if (route === 'buildArmy' || route === 'buildRes') {
      // 建筑命令下达后保持弹窗收起，玩家需要在施工页面查看倒计时并使用加速；
      // 完工事件或轮询确认对应检查项后，refresh() 会重新打开弹窗。
      state.guidedAction = null;
      state.actionNote = '施工命令已下达。建筑完成后会自动勾选并返回指引。';
      closeModal();
      syncPolling();
      var locateSpeedUp = function () {
        var qBar = document.getElementById ? document.getElementById('buildQueueBar') : null;
        var speedBtn = (qBar && qBar.querySelector && (
          qBar.querySelector('button[onclick*="accelerateJob"]') ||
          qBar.querySelector('.btn.ok') ||
          qBar.querySelector('button')
        )) || (document.querySelector && (
          document.querySelector('#buildQueueBar button[onclick*="accelerateJob"]') ||
          document.querySelector('#buildQueueBar .btn.ok') ||
          document.querySelector('#buildQueueBar button')
        ));
        if (speedBtn) {
          showSpotlight(speedBtn);
        }
      };
      locateSpeedUp();
      setTimeout(locateSpeedUp, 60);
      return Promise.resolve(null);
    }
    if (route === 'army') {
      // 造兵命令下达后保持弹窗收起，玩家需要在生产队列查看倒计时并使用加速；
      // 生产完成并由轮询确认目标完成后，refresh() 才重新打开弹窗。
      state.snoozedFor = data.current.id;
      state.guidedAction = null;
      state.actionNote = '生产命令已下达。部队完成后会自动勾选并返回指引。';
      closeModal();
      if (G.Army && G.Army.setTab) {
        G.Army.setTab('queue');
      }
      var locateQueueItem = function () {
        var queueItem = (document.querySelector && document.querySelector('.army-queue-item[data-unit="' + id + '"]')) ||
          (document.querySelector && document.querySelector('.army-queue-item')) ||
          (document.getElementById && document.getElementById('army-panel-queue'));
        if (!queueItem) return;
        highlightTarget(queueItem);
        var speedBtn = (queueItem.querySelector && (
          queueItem.querySelector('button[onclick*="freeSpeedUp"]') ||
          queueItem.querySelector('.army-free-speedup-btn') ||
          queueItem.querySelector('button[onclick*="openSpeedUpPicker"]') ||
          queueItem.querySelector('.army-queue-actions button:not(.warn)') ||
          queueItem.querySelector('.army-queue-btn:not(.warn)') ||
          queueItem.querySelector('button')
        )) || queueItem;
        showSpotlight(speedBtn);
      };
      locateQueueItem();
      setTimeout(locateQueueItem, 60);
      setTimeout(locateQueueItem, 200);
      syncPolling();
      return Promise.resolve(null);
    }
    if (route === 'tech') {
      // 科技命令下达后保持弹窗收起，玩家需要在研发队列查看倒计时并使用加速；
      // 研发完成并由轮询或完成事件确认后，refresh() 才重新打开弹窗。
      state.snoozedFor = data.current.id;
      state.guidedAction = null;
      state.actionNote = '研发命令已下达。科技完成后会自动勾选并返回指引。';
      closeModal();
      var locateSpeedUp = function () {
        var speedBtn = (document.querySelector && (
          document.querySelector('button[onclick*="openSpeedUpPicker"]') ||
          document.querySelector('#tech-panel button[onclick*="openSpeedUpPicker"]') ||
          document.querySelector('.tech-panel button.ok')
        )) || null;
        if (speedBtn) {
          showSpotlight(speedBtn);
        }
      };
      locateSpeedUp();
      setTimeout(locateSpeedUp, 60);
      setTimeout(locateSpeedUp, 200);
      syncPolling();
      return Promise.resolve(null);
    }
    state.snoozedFor = ''; state.guidedAction = null;
    state.actionNote = route === 'tech' ? '研发命令已下达，研究完成后会自动更新目标。' : '生产命令已下达，部队交付后会自动更新目标。';
    render();
    return refresh();
  }

  function mutate(action, body) {
    if (state.busy) return Promise.resolve(null);
    state.busy = true;
    syncPolling();
    // 使已经在途的轮询失效，避免旧的暂停/领取状态覆盖刚完成的操作。
    state.epoch++; state.pending = null;
    var key = context();
    render();
    return G.API.client.post('/game/onboarding/' + action, body || {}).then(function (data) {
      if (key !== context()) return null;
      state.data = data; state.error = '';
      if (data.state) { G.API.applyState(data.state); delete data.state; Core.renderTop(); }
      applyBalances(data);
      if (action === 'claim') G.toast('补给已送达主城');
      if (action === 'skip') G.toast('行动补给已送达主城，可继续主线任务');
      if (action === 'recover') G.toast('援军已抵达主城：30步兵、1侦察机、2卡车');
      if (action === 'pause' || action === 'skip') {
        state.actionNote = '';
        clearSpotlight();
      }
      return data;
    }).catch(function (err) {
      if (key === context()) { state.error = err.message || '操作失败，请刷新确认结果'; G.toast(state.error); }
      return null;
    }).finally(function () { if (key === context()) { state.busy = false; syncPolling(); render(); } });
  }

  function actions(objective) {
    if (objective.id === 'base') return buildButton('升级市政厅', '市政厅', 'buildArmy', 'command');
    if (objective.id === 'farm') return buildButton('升级农田', '农田', 'buildRes', 'farm');
    if (objective.id === 'factory') return (state.data && state.data.checks && state.data.checks.house === false)
      ? buildButton('先建造民居', '民居', 'buildArmy', 'house')
      : buildButton('建造军工厂', '军工厂', 'buildArmy', 'factory');
    if (objective.id === 'infantry') return routeButton('前往生产步兵', 'army', 'infantry');
    if (objective.id === 'train') return routeButton('前往生产卡车', 'army', 'truck');
    if (objective.id === 'lab') return (state.data && state.data.checks && state.data.checks.depot === false)
      ? buildButton('先建造军需仓库', '军需仓库', 'buildArmy', 'depot')
      : buildButton('建造军工科技研发中心', '军工科技研发中心', 'buildArmy', 'lab');
    if (objective.id === 'reconTech') return (state.data && state.data.checks && state.data.checks.radar === false)
      ? buildButton('先建造防空雷达站', '防空雷达站', 'buildArmy', 'radar')
      : routeButton('研究侦察技术', 'tech', 'recon_level');
    if (objective.id === 'recon') return routeButton('生产侦察机', 'army', 'scout');
    if (objective.id === 'officer') return (state.data && state.data.checks && state.data.checks.academy)
      ? routeButton('前往军校招募军官', 'academy')
      : buildButton('先建造军校', '军校', 'buildArmy', 'academy');
    if (objective.id === 'scout' || objective.id === 'occupy' || objective.id === 'gather') {
      return button(objective.id === 'gather' ? '派出采集队' : '选择推荐资源点', 'target', objective.id);
    }
    if (objective.id === 'report') return routeButton('查看战报', 'reports');
    if (objective.id === 'develop') {
      var recBuilding = findRecommendedDevelopBuilding();
      var bName = (G.DATA && G.DATA.buildings && G.DATA.buildings[recBuilding] && G.DATA.buildings[recBuilding].name) ||
        (recBuilding === 'refinery' ? '炼钢厂' : recBuilding === 'oilfield' ? '石油基地' : recBuilding === 'raremine' ? '稀矿厂' : '农田');
      var isNew = getBuildingLevel(recBuilding) === 0;
      return buildButton(isNew ? ('建造' + bName) : ('升级' + bName), bName, 'buildRes', recBuilding);
    }
    if (objective.id === 'plan') return button('开启新的征程', 'finish');
    return '';
  }

  function checklist(o) {
    return '<div class="ob-step-status">' + (o.exempt ? '已毕业账号豁免本步，不补发奖励' : o.complete ? '已完成，奖励已到账' : '完成本步后自动发放奖励') + '</div>';
  }

  function closeModal() {
    var modal = document.getElementById('onboardingBar');
    if (modal) modal.remove();
    var screen = document.getElementById('screen');
    if (screen && modal) screen.inert = state.screenWasInert;
  }

  function clearReportReading() {
    if (state.reportReading) clearInterval(state.reportReading.timer);
    state.reportReading = null;
    var notice = document.getElementById('onboardingReportCountdown');
    if (notice) notice.remove();
  }

  function clearGatherTracking() {
    if (state.gatherTracking) clearInterval(state.gatherTracking.timer);
    state.gatherTracking = null;
    var notice = document.getElementById('onboardingGatherCountdown');
    if (notice) notice.remove();
  }

  /** 主动返回只结束阅读等待并刷新真实任务进度，不代替服务端完成判定。 */
  function returnFromReportReading(reading) {
    if (!reading || state.reportReading !== reading || reading.key !== context()) return;
    clearReportReading();
    state.snoozedFor = '';
    render();
    return refresh();
  }

  function finishGatherNow() {
    clearGatherTracking();
    state.recommendedGatherTargetId = null;
    state.snoozedFor = '';
    return mutate('finish-gather').then(function () {
      render();
    });
  }

  function calculateGatherRemainingSeconds(march) {
    var now = Date.now();
    if (!march) return 0;
    if (march.returning) {
      return Math.max(0, Math.ceil((Number(march.arriveAt || 0) - now) / 1000));
    }
    if (march.gathering) {
      var gatherEnd = Number(march.gatherEndAt || 0);
      var gatherRem = Math.max(0, gatherEnd - now);
      var oneWay = Math.max(1000, Number(march.arriveAt || 0) - Number(march.startAt || 0));
      return Math.max(0, Math.ceil((gatherRem + oneWay) / 1000));
    }
    var outboundRem = Math.max(0, Number(march.arriveAt || 0) - now);
    var legDur = Math.max(1000, Number(march.arriveAt || 0) - Number(march.startAt || 0));
    var gatherDur = 60000;
    return Math.max(0, Math.ceil((outboundRem + gatherDur + legDur) / 1000));
  }

  function findActiveGatherMarch() {
    var marches = (Core.state && Core.state.world && Core.state.world.marches) || [];
    var gatherMarches = marches.filter(function (m) {
      if (!m) return false;
      var isGather = m.targetKind === 'wild_gather' || (m.action === 'gather' && m.targetKind === 'wild');
      if (!isGather) return false;
      if (state.recommendedGatherTargetId != null) {
        return String(m.targetId) === String(state.recommendedGatherTargetId);
      }
      return true;
    });
    if (!gatherMarches.length) return null;
    gatherMarches.sort(function (a, b) {
      return calculateGatherRemainingSeconds(a) - calculateGatherRemainingSeconds(b);
    });
    return gatherMarches[0];
  }

  function updateGatherCountdown() {
    var tracking = state.gatherTracking, data = state.data;
    if (!tracking) return false;
    if (tracking.key !== context() || !Core.state || !G.API.isLoggedIn() || Core.route === 'login' ||
        !data || !data.enrolled || data.paused || data.done || !data.current || data.current.id !== 'gather') {
      clearGatherTracking();
      return false;
    }
    var march = findActiveGatherMarch();
    var seconds = 0;
    if (march) {
      seconds = calculateGatherRemainingSeconds(march);
      tracking.lastKnownSeconds = seconds;
    } else if (tracking.lastKnownSeconds > 0) {
      var elapsed = Math.ceil((Date.now() - tracking.startedAt) / 1000);
      seconds = Math.max(0, tracking.lastKnownSeconds - elapsed);
    } else if (data.waitingForGather) {
      seconds = 30; // 兜底显示等待
    }

    if (!seconds && !data.waitingForGather && !march) {
      clearGatherTracking();
      state.snoozedFor = '';
      return false;
    }

    var notice = document.getElementById('onboardingGatherCountdown');
    if (!notice) {
      notice = document.createElement('aside');
      notice.id = 'onboardingGatherCountdown';
      notice.className = 'ob-gather-countdown';
      notice.setAttribute('role', 'status');
      notice.setAttribute('aria-live', 'polite');
      notice.setAttribute('aria-atomic', 'true');
      notice.innerHTML = '<span class="ob-eyebrow">前进基地 · 采集行动</span>' +
        '<div class="ob-report-countdown-body">' +
        '<strong class="ob-report-seconds"></strong><span>后自动完成返城</span>' +
        '<div class="ob-gather-actions">' +
        '<button type="button" class="btn ob-gather-finish">立即结束采集并返回</button>' +
        '<button type="button" class="btn ob-gather-return">返回指引</button>' +
        '</div></div>';
      notice.querySelector('.ob-gather-finish').onclick = function (event) {
        if (event) { event.preventDefault(); event.stopPropagation(); }
        return finishGatherNow();
      };
      notice.querySelector('.ob-gather-return').onclick = function (event) {
        if (event) { event.preventDefault(); event.stopPropagation(); }
        clearGatherTracking();
        state.snoozedFor = '';
        render();
        return refresh();
      };
      if (document.body) document.body.appendChild(notice);
      else { var view = document.getElementById('view'); view.insertBefore(notice, view.firstChild); }
    }
    var counter = notice.querySelector('.ob-report-seconds');
    var label = (seconds > 0 ? seconds : 0) + 's';
    if (counter && counter.textContent !== label) counter.textContent = label;
    return true;
  }

  function startGatherTracking() {
    clearGatherTracking();
    var march = findActiveGatherMarch();
    var initialSeconds = march ? calculateGatherRemainingSeconds(march) : 60;
    var tracking = { key: context(), startedAt: Date.now(), lastKnownSeconds: initialSeconds, timer: null };
    state.gatherTracking = tracking;
    tracking.timer = setInterval(function () {
      if (state.gatherTracking !== tracking) return;
      if (tracking.key !== context()) { clearGatherTracking(); return; }
      if (!updateGatherCountdown()) { render(); refresh(); }
    }, 250);
    updateGatherCountdown();
  }

  function updateReportReading() {
    var reading = state.reportReading, data = state.data;
    if (!reading) return false;
    if (reading.key !== context() || !Core.state || !G.API.isLoggedIn() || Core.route === 'login' ||
        !data || !data.enrolled || data.paused || data.done) {
      clearReportReading();
      return false;
    }
    var seconds = Math.max(0, Math.ceil((reading.deadline - Date.now()) / 1000));
    if (!seconds) {
      clearReportReading();
      state.snoozedFor = '';
      return false;
    }
    var notice = document.getElementById('onboardingReportCountdown');
    if (!notice) {
      notice = document.createElement('aside');
      notice.id = 'onboardingReportCountdown';
      notice.className = 'ob-report-countdown';
      notice.setAttribute('role', 'status');
      notice.setAttribute('aria-live', 'polite');
      notice.setAttribute('aria-atomic', 'true');
      notice.innerHTML = '<span class="ob-eyebrow">市政厅 · 战报阅读</span>' +
        '<div class="ob-report-countdown-body"><strong class="ob-report-seconds"></strong><span>后自动返回</span>' +
        '<button type="button" class="btn ob-report-return">返回新手指引</button></div>';
      notice.querySelector('.ob-report-return').onclick = function () { return returnFromReportReading(reading); };
      if (document.body) document.body.appendChild(notice);
      else { var view = document.getElementById('view'); view.insertBefore(notice, view.firstChild); }
    }
    var counter = notice.querySelector('.ob-report-seconds');
    var label = seconds + 's';
    if (counter.textContent !== label) counter.textContent = label;
    return true;
  }

  function startReportReading() {
    clearReportReading();
    var reading = { key: context(), deadline: Date.now() + 20000, timer: null };
    state.reportReading = reading;
    reading.timer = setInterval(function () {
      if (state.reportReading !== reading) return;
      if (reading.key !== context()) { clearReportReading(); return; }
      if (!updateReportReading()) { render(); refresh(); }
    }, 250);
    updateReportReading();
  }

  function modalKeydown(event) {
    var modal = document.getElementById('onboardingBar');
    if (!modal) return;
    var focusable = modal.querySelectorAll('button:not(:disabled)');
    if (!focusable.length) { event.preventDefault(); event.stopPropagation(); return; }
    var first = focusable[0], last = focusable[focusable.length - 1];
    if (!modal.contains(document.activeElement)) { event.preventDefault(); event.stopPropagation(); first.focus(); return; }
    if (/^[0-9*]$/.test(event.key) || event.key === 'Backspace') {
      event.preventDefault(); event.stopPropagation(); return;
    }
    if (event.key !== 'Tab') return;
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  }

  if (document.addEventListener) document.addEventListener('keydown', modalKeydown, true);

  /** 弹窗在屏幕容器外，inert 与遮罩同时阻止背景的鼠标和键盘操作。进入实际操作页后暂时收起，目标变化时再次弹出。 */
  function render() {
    var bar = document.getElementById('onboardingBar');
    if (!Core.state || Core.route === 'login' || !G.API.isLoggedIn()) { stop(); return; }
    var data = state.data;
    var readingReport = updateReportReading();
    var currentKey = data && data.current ? data.current.id : 'supplies';
    // 侦察报告在到达野地时就会送达，任务弹窗必须等服务端确认侦察机返城。
    var waitingForScoutReturn = data && data.waitingForScoutReturn && currentKey === 'scout';
    // 采集任务进行中或在途返城时，右侧展示倒计时弹窗，主指引弹窗保持收起。
    if (data && data.enrolled && !data.paused && !data.done && currentKey === 'gather' && (data.waitingForGather || findActiveGatherMarch())) {
      if (!state.gatherTracking) startGatherTracking();
    }
    var trackingGather = updateGatherCountdown();
    var visible = !readingReport && !trackingGather && !waitingForScoutReturn && Core.route !== 'onboarding' && currentKey !== state.snoozedFor &&
      ((data && data.enrolled && !data.paused && !data.done) || (!data && state.error));
    if (!visible) closeModal();
    else {
      clearSpotlight();
      if (!bar) {
        bar = document.createElement('section'); bar.id = 'onboardingBar'; bar.className = 'modal-mask ob-mask';
        bar.innerHTML = '<div class="ob-dialog" role="dialog" aria-modal="true" aria-labelledby="obDialogTitle" aria-describedby="obDialogDesc"></div>';
        if (document.body) document.body.appendChild(bar);
        else { var view = document.getElementById('view'); view.insertBefore(bar, view.firstChild); }
        var screen = document.getElementById('screen');
        if (screen) { state.screenWasInert = screen.inert; screen.inert = true; }
      }
      var dialog = bar.querySelector ? bar.querySelector('.ob-dialog') || bar : bar;
      if (!data) {
        dialog.innerHTML = '<div class="ob-bar-heading"><strong id="obDialogTitle">新手行动暂时无法读取</strong></div>' +
          '<p id="obDialogDesc" class="ob-error" role="alert">' + esc(state.error) + '</p><div class="ob-actions">' + button('重新检查', 'refresh') + '</div>';
      } else {
        var current = data.current;
        var completed = Math.max(0, Number(data.completed) || 0);
        var total = data.objectives.length;
        var percent = total ? Math.min(100, Math.round(completed / total * 100)) : 0;
        var remaining = (data.supplies || []).filter(function (s) { return !s.claimed; });
        var skipRewards = {};
        remaining.forEach(function (supply) {
          Object.keys(labels).forEach(function (resource) {
            skipRewards[resource] = (skipRewards[resource] || 0) + Number((supply.resources || {})[resource] || 0);
          });
        });
        dialog.innerHTML = '<div class="ob-bar-heading"><div><span class="ob-eyebrow">新手战役 · 前进基地行动</span>' +
          '<strong id="obDialogTitle">' + esc(current && current.id === 'plan' ? '基地已准备好，接下来由你指挥' : current ? current.title : '行动目标已完成') + '</strong></div>' +
          '<span class="ob-count">' + completed + ' / ' + total + '</span></div>' +
          '<div class="ob-progress" role="progressbar" aria-label="新手行动进度" aria-valuemin="0" aria-valuemax="' + total + '" aria-valuenow="' + completed + '"><span style="width:' + percent + '%"></span></div>' +
          (completed === 0 ? '<p class="ob-intro">先升级资源建筑与军工设施，再生产部队、招募军官、侦察并采集资源。操作完成后目标会自动更新。</p>' : '') +
          '<p id="obDialogDesc">' + esc(current && current.id === 'plan' ? '指挥官，做得不错！你已经迈出了建设与远征的第一步。友情提示：打开「任务」Tab，通过主线任务继续探索新玩法；完成任务后，记得领取奖励。按自己的节奏出发吧！' : current ? current.body : '所有行动步骤已完成。') + '</p>' +
          (current && state.actionNote ? '<p class="ob-action-note" role="status">' + esc(state.actionNote) + '</p>' : '') +
          (current ? checklist(current) : '') +
          (current ? '<p class="ob-reward">本步奖励：' + esc(rewardText(current.reward, current.diamond)) + '</p>' : '') +
          '<p class="ob-completion-reward">全部完成：' + esc(rewardText(data.completionReward, data.completionDiamond)) + '</p>' +
          (remaining.length ? '<p class="ob-skip-reward">跳过后立即获得未领行动补给：' + esc(rewardText(skipRewards)) + '</p>' : '') +
          (state.expanded ? '<div class="ob-modal-roadmap" aria-label="行动目标">' + data.objectives.map(function (objective, index) {
            return '<div><span>' + (index + 1) + '. ' + esc(objective.title) + '</span><span class="' + (objective.complete ? 'ob-complete' : '') + '">' +
              (objective.complete ? '已完成' : '待完成') + '</span></div>';
          }).join('') + '</div>' : '') +
          '<div class="ob-actions">' + (current ? actions(current) : '') +
          button(state.expanded ? '收起目标' : '查看全部目标', 'expand') +
          (current && current.id === 'plan' ? '' : button(remaining.length ? '跳过指引 · 领取剩余补给' : '跳过指引', 'skip')) + '</div>';
      }
      bind(dialog);
      if (dialog.querySelector && !dialog.contains(document.activeElement)) {
        var firstButton = dialog.querySelector('button:not(:disabled)');
        if (firstButton) firstButton.focus();
      }
    }
    if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
    if (Core.route === 'onboarding') draw(document.getElementById('view'));
  }

  function rewardText(resources, diamond) {
    resources = resources || {};
    var text = Object.keys(labels).filter(function (key) { return Number(resources[key] || 0) > 0; })
      .map(function (key) { return labels[key] + ' ' + resources[key]; });
    if (Number(diamond || 0) > 0) text.push('钻石 ' + diamond);
    return text.join(' · ') || '无';
  }
  function draw(view) {
    if (!view) return;
    var data = state.data;
    var h = '<div class="onboarding-view"><div class="ob-heading"><img src="img/resources/models/food.webp" alt="" width="40" height="40">' +
      '<div><div class="title">前进基地行动</div><p>跟随真实行动熟悉建设、生产、出征与采集；完成后继续主线任务领取资源奖励。</p></div></div>';
    if (state.error) h += '<p class="ob-error" role="alert">' + esc(state.error) + '</p>' + button('重新检查', 'refresh');
    if (!data) h += '<p>正在读取行动记录…</p>';
    else if (!data.enrolled) h += '<p>接管基地，完成资源建设、军工生产、侦察占领与首次采集；老指挥官也可直接领取行动补给并继续主线。</p>' +
      '<div class="ob-actions">' + button('开启行动', 'start') + button('跳过指引 · 领取全部补给', 'skip') + '</div>';
    else {
      h += '<div class="ob-roadmap"><span>① 资源与军工建设</span><span>② 造兵、招募与侦察</span><span>③ 占领与采集</span><span>④ 主线任务</span></div>';
      h += '<div class="ob-overview"><span>已完成 ' + data.completed + ' / ' + data.objectives.length + '</span>' +
        (data.done ? '' : button(data.paused ? '恢复弹窗' : '暂停弹窗', 'pause', data.paused ? 'false' : 'true') + button('跳过指引 · 领取剩余补给', 'skip')) + '</div>' +
        '<p class="ob-muted">补给送达主城。跳过会一次性发放未领取的行动补给，已领部分不重复发放。</p>';
      if (data.done) h += '<section class="ob-graduation"><h2>' + (data.skipped ? '已跳过前进基地行动' : '前进基地行动完成') + '</h2><p>' +
        (data.skipped ? '行动补给已送达。' : '你已掌握基础资源建设、军工生产与野地采集。') + '下一步按主线章节任务继续发展，完成任务后记得领取资源奖励。</p>' +
        '<div class="ob-actions">' + routeButton('前往主线任务 · 领取奖励', 'mainQuest') +
        '</div></section>';
      if (Core.state.player && Core.state.player.citySlot !== 0) h += '<p class="ob-error">当前是分城。前进基地行动在主城进行。</p>' + button('切回主城', 'homeCity');
      if (data.current) h += '<section class="ob-current"><h2>当前目标 · ' + esc(data.current.title) + '</h2><p>' + esc(data.current.body) +
        '</p>' + checklist(data.current) + '<div class="ob-actions">' + actions(data.current) + '</div></section>';
      if (data.recoveryAvailable) h += '<section class="ob-supply"><strong>重新整备</strong><p>一次性援军：30步兵、1侦察机、2卡车。</p>' + button('接收援军', 'recover') + '</section>';
      h += '<section class="ob-objectives"><h2>行动进度与奖励</h2>';
      data.objectives.forEach(function (o, index) {
        var current = data.current && data.current.id === o.id;
        h += '<details class="ob-objective"><summary><span>' + (index + 1) + '. ' + esc(o.title) +
          '</span><span class="' + (o.complete ? 'ob-complete' : '') + '">' + (o.exempt ? '已毕业豁免' : o.complete ? '已完成' : '待完成') + '</span></summary>' +
          '<p>' + esc(o.body) + (o.exempt ? '' : '<p class="ob-step-reward">完成奖励：' + esc(rewardText(o.reward, o.diamond)) + '</p>') + checklist(o) +
          '<div class="ob-actions">' + (!o.complete && (o.id !== 'plan' || current) ? actions(o) : '') + '</div></details>';
      });
      h += '</section>';
    }
    h += '</div>';
    view.innerHTML = h; bind(view);
  }

  function highlightTarget(target) {
    if (!target) return;
    if (target.scrollIntoView) target.scrollIntoView({ behavior: 'smooth', block: 'center' });
    if (target.classList && target.classList.add) {
      target.classList.add('ob-target');
      setTimeout(function () {
        if (target.classList && target.classList.remove) target.classList.remove('ob-target');
      }, 2500);
    }
  }

  function go(route, building) {
    clearReportReading();
    state.snoozedFor = state.data && state.data.current ? state.data.current.id : 'supplies';
    state.guidedAction = ['buildArmy', 'buildRes', 'army', 'tech'].includes(route) ? { route: route, building: building || '' } : null;
    state.actionNote = '';
    closeModal();
    // 新版入口使用战报页；保留情报页兼容分支，避免旧页面状态中的引导倒计时丢失。
    if ((route === 'reports' || route === 'alerts') && state.snoozedFor === 'report') startReportReading();
    G.go(route);

    if (route === 'tech' && building) {
      var branch = (G.DATA && G.DATA.techs && G.DATA.techs[building] && G.DATA.techs[building].branch) || (building === 'recon_level' ? '侦察' : '军事');
      if (G.Tech && G.Tech.setTab) G.Tech.setTab(branch);
      var locateTech = function () {
        var techCard = (document.querySelector && document.querySelector('[data-tech="' + building + '"]')) ||
          (document.getElementById && document.getElementById('tech-card-' + building));
        if (!techCard) return;
        highlightTarget(techCard);
        var researchBtn = techCard.querySelector ? (
          techCard.querySelector('button.ok') ||
          techCard.querySelector('button')
        ) : null;
        showSpotlight(researchBtn || techCard);
      };
      locateTech();
      setTimeout(locateTech, 60);
      setTimeout(locateTech, 180);
      return;
    }

    if (route === 'army') {
      if (G.Army && G.Army.setTab) G.Army.setTab('units');
      if (building) {
        if (G.Army && G.Army.setUnitExpanded) {
          G.Army.setUnitExpanded(building, true);
          if (Core.render) Core.render();
        }
        var locateArmyTargets = function () {
          var unitCard = (document.getElementById && document.getElementById('unit-card-' + building)) ||
            (document.querySelector && document.querySelector('[data-unit="' + building + '"]'));
          if (!unitCard) return;
          highlightTarget(unitCard);
          var qtyInput = document.getElementById && document.getElementById('qty_' + building);
          if (qtyInput) {
            var maxVal = Number(qtyInput.max) || 0;
            var targetQty = building === 'infantry' ? (maxVal > 0 ? Math.min(1, maxVal) : 1)
              : (building === 'truck' ? (maxVal > 0 ? Math.min(2, maxVal) : 2) : 1);
            if (targetQty > 0) {
              qtyInput.value = targetQty;
              if (G.Army && G.Army.onInputChange) {
                G.Army.onInputChange(building, targetQty);
              }
            }
          }
          var recruitRow = unitCard.querySelector ? unitCard.querySelector('.recruit-row') : null;
          var recruitBtn = null;
          var allRecruitBtns = (unitCard.querySelectorAll && unitCard.querySelectorAll('.recruit-btn')) || [];
          for (var b = 0; b < allRecruitBtns.length; b++) {
            if (allRecruitBtns[b].classList && !allRecruitBtns[b].classList.contains('warn')) {
              recruitBtn = allRecruitBtns[b];
              break;
            }
          }
          if (!recruitBtn && unitCard.querySelector) {
            recruitBtn = unitCard.querySelector('.recruit-btn:not(.warn)') || unitCard.querySelector('button.recruit-btn');
          }
          var scrollTarget = recruitBtn || recruitRow || unitCard;
          if (scrollTarget && scrollTarget.scrollIntoView) {
            scrollTarget.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
          }
          if (recruitBtn) {
            showSpotlight(recruitBtn);
          } else {
            showSpotlight(unitCard);
          }
        };
        locateArmyTargets();
        setTimeout(locateArmyTargets, 60);
        setTimeout(locateArmyTargets, 180);
        setTimeout(locateArmyTargets, 350);
      }
      return;
    }

    if (route === 'academy') {
      var locateAcademy = function () {
        var officerCard = (document.querySelector && document.querySelector('.officer-card.ok')) ||
          (document.querySelector && document.querySelector('.officer-card'));
        if (officerCard) {
          highlightTarget(officerCard);
          var recruitBtn = officerCard.querySelector ? (
            officerCard.querySelector('.officer-recruit-act .link-act:not(.disabled)') ||
            officerCard.querySelector('.link-act:not(.disabled)') ||
            officerCard.querySelector('.officer-recruit-act')
          ) : null;
          showSpotlight(recruitBtn || officerCard);
        } else {
          var refreshBtn = document.querySelector && document.querySelector('.academy-refresh-btn:not([disabled])');
          if (refreshBtn) {
            showSpotlight(refreshBtn);
          }
        }
      };
      locateAcademy();
      setTimeout(locateAcademy, 60);
      setTimeout(locateAcademy, 180);
      return;
    }

    if (route === 'buildArmy' || route === 'buildRes') {
      if (!building) return;
      var groupKey = route === 'buildRes' ? 'res' : 'army';
      var isUnbuilt = getBuildingLevel(building) === 0;
      if (isUnbuilt && G.Build && G.Build.openBuildPicker) {
        G.Build.openBuildPicker(groupKey);
        var locatePicker = function () {
          var pickerItem = (document.querySelector && document.querySelector('.build-picker-item[data-building="' + building + '"]')) ||
            (document.getElementById && document.getElementById('build-picker-' + building));
          if (pickerItem) {
            highlightTarget(pickerItem);
            var pickerBtn = pickerItem.querySelector ? pickerItem.querySelector('.build-picker-action button, button') : null;
            showSpotlight(pickerBtn || pickerItem);
          }
        };
        locatePicker();
        setTimeout(locatePicker, 60);
      } else {
        var locateExisting = function () {
          var target = document.querySelector && document.querySelector('[data-building="' + building + '"]');
          if (target) {
            highlightTarget(target);
          }
          if (G.Build && G.Build.openBuildingDetailModal) {
            var modal = document.querySelector && document.querySelector('.bdetail-modal');
            if (!modal) {
              var arr = getBuildingLevels(building);
              var targetSlot = arr.length > 0 ? 0 : null;
              G.Build.openBuildingDetailModal(building, targetSlot);
            }
            setTimeout(function () {
              var upBtn = document.getElementById && document.getElementById('bdetailUpBtn');
              if (upBtn) {
                showSpotlight(upBtn);
              }
            }, 60);
          } else if (target) {
            showSpotlight(target);
          }
        };
        locateExisting();
      }
      return;
    }

    if (building) {
      var genericTarget = document.querySelector && document.querySelector('[data-building="' + building + '"]');
      highlightTarget(genericTarget);
    }
  }

  function findTarget(action) {
    if (state.busy) return;
    if (Core.state.player.citySlot !== 0) { G.toast('请从城市切换入口切回主城'); return; }
    var key = context(); state.busy = true; render();
    return G.API.client.post('/game/onboarding/target', { gather: action === 'gather' }).then(function (target) {
      if (key !== context()) return;
      var world = Core.state.world;
      var list = world.wildTiles || (world.wildTiles = []);
      var idx = list.findIndex(function (t) { return String(t.id) === String(target.id); });
      if (idx < 0) { idx = list.length; list.push(target); } else list[idx] = target;
      var sArmy = (Core.state && Core.state.army) || {};
      var defaultArmy = {};
      if (action === 'scout') {
        defaultArmy.scout = sArmy.scout != null ? Math.min(Number(sArmy.scout) || 0, 1) : 1;
      } else if (action === 'gather') {
        defaultArmy.truck = sArmy.truck != null ? Math.min(Number(sArmy.truck) || 0, 2) : 2;
      } else {
        defaultArmy.infantry = sArmy.infantry != null ? Math.min(Number(sArmy.infantry) || 0, 50) : 50;
      }
      world._dispatchTarget = { kind: action === 'gather' ? 'wild_gather' : 'wild', idx: idx, target: target,
        action: action === 'scout' ? 'scout' : action === 'gather' ? 'gather' : 'conquer',
        defaultArmy: defaultArmy };
      // 引导要求资源随部队返城入库，这次推荐采集固定使用全自动，避免停在野地等待手动命令。
      if (action === 'gather') {
        state.recommendedGatherTargetId = target.id;
        world._dispatchTarget.onboardingGather = true;
        world._dispatchTarget.gatherMode = 'auto';
        startGatherTracking();
      }
      state.snoozedFor = state.data && state.data.current ? state.data.current.id : 'supplies';
      state.guidedAction = null; state.actionNote = '';
      closeModal();
      G.go('dispatch');
      // 只预填本次推荐编队，仍由玩家核对情报、数量和路线后确认出征。
      if (G.World && G.World.onDispatchInputChange) {
        document.querySelectorAll('[id^="dqty_"]').forEach(function (input) {
          var unit = input.id.slice(5);
          var desired = defaultArmy[unit] != null ? defaultArmy[unit] : 0;
          input.value = Math.min(Number(input.max) || 0, desired);
          G.World.onDispatchInputChange(unit, input.value);
        });
      }
      if (G.World && typeof G.World._refreshDispatchRoute === 'function') {
        G.World._refreshDispatchRoute();
      }
      var locateDispatchBtn = function () {
        var dispatchBtn = document.querySelector && document.querySelector('button[onclick*="launchDispatch"]');
        if (dispatchBtn) {
          highlightTarget(dispatchBtn);
          showSpotlight(dispatchBtn);
        }
      };
      locateDispatchBtn();
      setTimeout(locateDispatchBtn, 60);
      setTimeout(locateDispatchBtn, 180);
      setTimeout(locateDispatchBtn, 350);
    }).catch(function (err) { if (key === context()) { state.error = err.message; G.toast(err.message); } })
      .finally(function () { if (key === context()) { state.busy = false; render(); } });
  }

  function bind(root) {
    root.querySelectorAll('[data-ob-action]').forEach(function (el) {
      el.onclick = function () {
        var action = el.dataset.obAction, value = el.dataset.obValue;
        if (action === 'route') { var dest = value.split(':'); go(dest[0], dest[1]); }
        else if (action === 'expand') { state.expanded = !state.expanded; render(); }
        else if (action === 'pause') mutate('pause', { paused: value === 'true' });
        else if (action === 'claim') mutate('claim', { supplyId: value });
        else if (action === 'finish') {
          mutate('finish').then(function (data) {
            if (!data || !data.done) return;
            state.snoozedFor = 'supplies';
            closeModal();
            if (Core.route === 'onboarding') G.go('home');
            var taskTab = document.querySelector('#navbar [data-route="mainQuest"]');
            if (!taskTab) return;
            // 毕业提示仅指示入口，不再锁定玩家操作；五秒后解除聚光灯。
            showSpotlight(taskTab);
            detachSpotlightListeners();
            graduationSpotlightTimer = setTimeout(clearSpotlight, 5000);
          });
        }
        else if (action === 'target') findTarget(value);
        else if (action === 'refresh') refresh();
        else if (action === 'homeCity') {
          var cities = Core.state.cityOverview && Core.state.cityOverview.cities || [];
          var home = cities.find(function (c) { return c.citySlot === 0 || c.main; });
          if (home && G.Cities) G.Cities.select(home.id); else G.toast('请从城市切换入口选择主城');
        } else mutate(action);
      };
    });
  }

  Core.views.onboarding = function (view) { draw(view); refresh(); };
  G.Onboarding = { init: init, refresh: refresh, stop: stop, render: render, hasSupply: hasSupply,
    state: state, mutate: mutate, findTarget: findTarget, actionStarted: actionStarted, go: go,
    finishGather: finishGatherNow, startGatherTracking: startGatherTracking, clearGatherTracking: clearGatherTracking,
    showSpotlight: showSpotlight, clearSpotlight: clearSpotlight, findRecommendedDevelopBuilding: findRecommendedDevelopBuilding,
    getCurrentSpotlightTarget: function () { return currentSpotlightTarget; },
    getCurrentSpotlightTargets: function () { return currentSpotlightTarget ? [currentSpotlightTarget] : []; } };
})(window.Game);
