/* global window, document */
(function (G) {
  'use strict';
  var Core = G.Core;
  var state = { data: null, timer: null, pending: null, busy: false, epoch: 0, error: '', snoozedFor: '', guidedAction: null, actionNote: '' };
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

    // 目标滚出可视区域时暂时隐藏高亮框，避免在顶栏留下错位的提示。
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

  function attachSpotlightListeners() {
    if (spotlightListenersAttached || typeof window === 'undefined' || !window.addEventListener) return;
    spotlightListenersAttached = true;
    window.addEventListener('scroll', updateSpotlightFramePosition, true);
    window.addEventListener('resize', updateSpotlightFramePosition, true);
  }

  function detachSpotlightListeners() {
    if (!spotlightListenersAttached || typeof window === 'undefined' || !window.removeEventListener) return;
    spotlightListenersAttached = false;
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

  /** 仅在当前账号仍参与引导时高亮确认按钮，延迟检查也必须遵守跳过状态。 */
  function canSpotlightConfirm(key) {
    var data = state.data;
    return key === context() && Core.state && G.API.isLoggedIn() &&
      data && data.enrolled === true && !data.paused && !data.done && !state.busy &&
      state.guidedAction && ['buildArmy', 'buildRes'].includes(state.guidedAction.route);
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
  function hasSupply() { return state.data && (state.data.optionalTasks || []).some(function (t) { return t.status === 'completed'; }); }
  function applyBalances(data) {
    if (!data || !data.balances || !Core.state || !Core.state.resources) return;
    // 引导奖励只入主城，分城状态只能同步账号共享的钻石余额。
    Object.keys(data.balances).forEach(function (key) {
      if (!Core.state.player || !Core.state.player.citySlot || key === 'diamond') Core.state.resources[key] = data.balances[key];
    });
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

  /** 忽略旧账号响应，同账号的失效查询自动重读；定时刷新不重绘正在编辑的游戏表单。 */
  function refresh() {
    if (!Core.state || !G.API.isLoggedIn()) { stop(); return Promise.resolve(null); }
    if (state.busy) return Promise.resolve(state.data);
    // 暂停/恢复等操作尚未完成时，不发起可能读到旧状态的刷新请求。
    if (state.pending) return state.pending;
    var key = context();
    var revision = G.API.client.cityRevision;
    var request = G.API.client.get('/game/onboarding', { silent: true }).then(function (data) {
      if (key !== context()) return null;
      state.error = '';
      if (completedCheckChanged(state.data, data) ||
          (state.data && state.data.scouting && !data.scouting) ||
          (state.data && state.data.current && data.current && state.data.current.id !== data.current.id)) {
        state.snoozedFor = ''; state.guidedAction = null; state.actionNote = '';
        clearSpotlight();
      }
      if (data && data.done) {
        clearSpotlight();
        if (state.data && !state.data.done && !data.skipped) G.toast('新手行动完成！补给已到账，前往「任务」探索更多玩法。');
      }
      if (JSON.stringify(state.data) !== JSON.stringify(data)) { state.data = data; render(); }
      applyBalances(data);
      syncPolling();
      return data;
    }).catch(function (err) {
      if (key !== context()) return null;
      if (revision !== G.API.client.cityRevision) {
        // 重连或城市更新取消的只读请求应按新上下文重读，不显示为新手行动故障。
        if (state.pending === request) state.pending = null;
        return refresh();
      }
      state.error = err.message || '行动状态暂时无法读取'; render();
      return null;
    }).finally(function () { if (state.pending === request) state.pending = null; });
    state.pending = request;
    return request;
  }

  function stop() {
    state.epoch++;
    if (state.timer) clearInterval(state.timer);
    clearSpotlight();
    var hint = document.getElementById('onboardingTaskHint');
    if (hint) hint.remove();
    state.timer = null; state.pending = null; state.data = null; state.error = ''; state.busy = false;
    state.snoozedFor = ''; state.guidedAction = null; state.actionNote = '';
    closeModal();
  }
  function init() {
    stop();
    hookBuildConfirm();
    return refresh();
  }

  /** 命令下达后仅提示队列位置；目标由服务端完成事件推进，不要求额外确认。 */
  function actionStarted(route, id) {
    var data = state.data;
    if (!data || !data.current || data.paused || data.done) return Promise.resolve(null);
    var construction = (route === 'buildRes' || route === 'buildArmy') && data.current.id === 'base';
    var production = route === 'army' && data.current.id === 'infantry' && id === 'infantry';
    if (!construction && !production) return Promise.resolve(null);
    state.snoozedFor = data.current.id; state.guidedAction = null;
    state.actionNote = construction ? '施工中，可自由探索其他功能。' : '生产中，可自由探索其他功能。';
    closeModal(); clearSpotlight();
    if (production && G.Army && G.Army.setTab) G.Army.setTab('queue');
    syncPolling();
    return Promise.resolve(null);
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
      if (action === 'claim' || action === 'tasks/claim') {
        if (G.Sound && typeof G.Sound.play === 'function') G.Sound.play('coins');
      }
      if (action === 'claim') G.toast('补给已送达主城');
      if (action === 'skip') G.toast('行动补给已送达主城，可继续主线任务');
      if (action === 'recover') G.toast('援军已抵达主城：30步兵、1卡车及资源');
      if (action === 'tasks/claim') G.toast('探索任务奖励已到账');
      if (action === 'start' || (action === 'pause' && !data.paused)) state.snoozedFor = '';
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
    if (objective.id === 'base') return routeButton('前往建设', 'buildRes') + routeButton('军工建筑', 'buildArmy');
    if (objective.id === 'infantry') return routeButton('生产一名步兵', 'army', 'infantry');
    if (objective.id === 'recon_build') return routeButton('市政厅2级', 'buildArmy', 'command') + routeButton('军工厂2级', 'buildArmy', 'factory') + routeButton('城防雷达1级', 'buildArmy', 'radar') + routeButton('科研中心1级', 'buildArmy', 'lab');
    if (objective.id === 'recon_research') return routeButton('研究侦察技术1级', 'tech', 'recon_level');
    if (objective.id === 'recon_unit') return routeButton('生产侦查装甲车', 'army', 'recon_car');
    if (objective.id === 'scout' || objective.id === 'occupy') return button('选择推荐野地', 'target', objective.id);
    if (objective.id === 'report') return routeButton('查看获胜战报', 'reports');
    return '';
  }

  function closeModal() {
    var card = document.getElementById('onboardingBar');
    if (card) card.remove();
  }

  /** 轻量行动卡不遮罩、不设置 inert；玩家随时可以收起并在任务页继续查看。 */
  function render() {
    if (!Core.state || Core.route === 'login' || !G.API.isLoggedIn()) { stop(); return; }
    var data = state.data;
    var currentKey = data && data.current ? data.current.id : 'supplies';
    // 配兵页面需要完整操作区；离开后恢复当前提示，不改变完成事实或暂停偏好。
    var visible = Core.route !== 'dispatch' && Core.route !== 'onboarding' && Core.route !== 'mainQuest' &&
      !(Core.state.player && Core.state.player.citySlot) && currentKey !== state.snoozedFor &&
      !(data && data.scouting && currentKey === 'scout') &&
      ((data && data.enrolled && !data.paused && !data.done) || (!data && state.error));
    if (!visible) closeModal();
    else {
      var card = document.getElementById('onboardingBar');
      if (!card) {
        card = document.createElement('section'); card.id = 'onboardingBar'; card.className = 'ob-card';
        card.setAttribute('aria-label', '新手行动');
        if (document.body) document.body.appendChild(card);
        else { var view = document.getElementById('view'); view.insertBefore(card, view.firstChild); }
      }
      if (!data) card.innerHTML = '<strong>新手行动暂时无法读取</strong><p class="ob-error" role="alert">' + esc(state.error) + '</p>' + button('重新检查', 'refresh');
      else {
        var completed = Math.max(0, Number(data.completed) || 0), total = data.objectives.length;
        card.innerHTML = '<div class="ob-bar-heading"><strong>' + esc(data.current ? data.current.title : '前进基地行动') + '</strong><span>' + completed + ' / ' + total + '</span></div>' +
          '<p>' + esc(data.current ? data.current.body : '完成核心行动后，到任务页继续探索。') + '</p>' +
          '<div class="ob-actions">' + (data.current ? actions(data.current) : '') + button('稍后再看', 'dismiss') + button('跳过指引', 'skip') + '</div>' +
          (data.recoveryAvailable ? '<p>首战受挫？</p>' + button('接收一次援军', 'recover') : '');
      }
      bind(card);
    }
    if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
    if (Core.route === 'onboarding') draw(document.getElementById('view'));
    if (Core.route === 'mainQuest' && G.MainQuest && G.MainQuest.drawQuestView) G.MainQuest.drawQuestView(document.getElementById('view'));
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
    var h = '<div class="onboarding-view"><div class="title">前进基地行动</div><p>建设、组队、侦查生产线、研究、生产侦查车、侦察、首战、战果。采集与其他玩法在「任务」按需探索。</p>';
    if (state.error) h += '<p class="ob-error">' + esc(state.error) + '</p>' + button('重新检查', 'refresh');
    if (!data) h += '<p>正在读取行动记录…</p>';
    else if (!data.enrolled) h += button('开启行动', 'start') + button('跳过指引', 'skip');
    else {
      h += '<div class="ob-overview"><span>已完成 ' + data.completed + ' / ' + data.objectives.length + '</span>' +
        (data.done ? '' : button(data.paused ? '恢复提示' : '暂停提示', 'pause', data.paused ? 'false' : 'true') + button('跳过指引', 'skip')) + '</div>';
      if (data.done) h += '<section class="ob-graduation"><h2>' + (data.skipped ? '指引已跳过' : '新手行动完成') + '</h2><p>打开任务 Tab，继续主线章节任务，探索采集、科技、军官等玩法。</p>' + routeButton('前往任务 · 领取奖励', 'mainQuest') + '</section>';
      else if (data.current) h += '<section class="ob-current"><h2>' + esc(data.current.title) + '</h2><p>' + esc(data.current.body) + '</p>' + actions(data.current) + '</section>';
      if (Core.state.player && Core.state.player.citySlot !== 0) h += '<p>核心行动在主城进行。</p>' + button('切回主城', 'homeCity');
      if (data.recoveryAvailable) h += '<p>首战受挫？可领取一次援军。</p>' + button('接收援军', 'recover');
      h += '<section class="ob-objectives">' + data.objectives.map(function (o, index) {
        return '<div class="ob-objective">' + (index + 1) + '. ' + esc(o.title) + '<span class="ob-step-status"> · ' + (o.complete ? '已完成' : '待完成') + '</span></div>';
      }).join('') + '</section><p class="ob-muted">阶段补给自动到账。全部完成：' + esc(rewardText(data.completionReward, data.completionDiamond)) + '</p>';
    }
    view.innerHTML = h + '</div>'; bind(view);
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
    state.snoozedFor = state.data && state.data.current ? state.data.current.id : 'supplies';
    state.guidedAction = ['buildArmy', 'buildRes', 'army', 'tech'].includes(route) ? { route: route, building: building || '' } : null;
    state.actionNote = '';
    closeModal();
    G.go(route);
    // 延迟定位只属于本次导航，不能在换账号或切换页面后覆盖新的玩家操作。
    var navigationKey = context();
    function stillHere() { return navigationKey === context() && Core.route === route; }

    if (route === 'tech' && building) {
      var branch = (G.DATA && G.DATA.techs && G.DATA.techs[building] && G.DATA.techs[building].branch) || (building === 'recon_level' ? '侦察' : '军事');
      if (G.Tech && G.Tech.setTab) G.Tech.setTab(branch);
      var locateTech = function () {
        if (!stillHere()) return;
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
          if (!stillHere()) return;
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
        if (!stillHere()) return;
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
          if (!stillHere()) return;
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
              if (!stillHere()) return;
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

  /** 推荐目标只预填编队；采集优先运输单位，也允许具备基础负重的步兵。 */
  function findTarget(action) {
    if (state.busy) return;
    if (Core.state.player.citySlot !== 0) { G.toast('请从切城入口切回主城'); return; }
    var availableArmy = Core.state.army || {};
    var gatherUnit = action === 'gather' ? ['truck', 'transport', 'infantry'].find(function (unit) {
      return Number(availableArmy[unit]) > 0 && G.DATA.units[unit].load > 0;
    }) : null;
    if (action === 'gather' && !gatherUnit) {
      G.toast('采集需要有负重的部队，可征召步兵或卡车，或等待部队返城');
      go('army', 'truck');
      return Promise.resolve(null);
    }
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
        var scoutUnit = Number(sArmy.recon_car) > 0 ? 'recon_car' : Number(sArmy.scout) > 0 ? 'scout' : null;
        if (!scoutUnit) { G.toast('请先完成侦察研究并生产侦查装甲车'); go('army', 'recon_car'); return; }
        defaultArmy[scoutUnit] = 1;
      } else if (action === 'gather') {
        // 保留高运力编队优先级；没有运输单位时，最多预填50名步兵（基础运力500）。
        var gatherCount = gatherUnit === 'truck' ? 2 : gatherUnit === 'transport' ? 1 : 50;
        defaultArmy[gatherUnit] = Math.min(Number(sArmy[gatherUnit]) || 0, gatherCount);
      } else {
        defaultArmy.infantry = sArmy.infantry != null ? Math.min(Number(sArmy.infantry) || 0, 50) : 50;
      }
      world._dispatchTarget = { kind: action === 'gather' ? 'wild_gather' : 'wild', idx: idx, target: target,
        action: action === 'scout' ? 'scout' : action === 'gather' ? 'gather' : 'campaign',
        occupyTarget: true, battleMode: 'auto',
        defaultArmy: defaultArmy };
      // 采集统一手动收获和回城，新手推荐也不绕过玩家操作。
      if (action === 'gather') {
        world._dispatchTarget.gatherMode = 'manual';
        world._dispatchTarget.gatherAfterAction = 'retain';
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
        if (key !== context() || Core.route !== 'dispatch') return;
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

  /** 只在任务首次主动进入时显示提示；完成事实仍由服务端游戏事件记录。 */
  function beginTask(taskId) {
    var task = state.data && (state.data.optionalTasks || []).find(function (t) { return t.id === taskId; });
    if (!task || task.status === 'completed' || task.status === 'claimed' || state.busy) return Promise.resolve(null);
    return mutate('tasks/begin', { taskId: taskId }).then(function (data) {
      if (!data) return null;
      task = (data.optionalTasks || []).find(function (t) { return t.id === taskId; });
      if (!task) return null;
      if (task.status === 'locked') {
        G.toast(task.unlockHint);
        var buildings = { tech: 'lab', officer: 'academy', truck: 'factory' };
        if (buildings[taskId]) go('buildArmy', buildings[taskId]); else go('world');
      } else if (taskId === 'gather') {
        if (task.status === 'in_progress') go('world');
        else return findTarget('gather').then(function () { if (data.showHint) showTaskNotice(task); });
      }
      else {
        go(task.route, task.building);
        if (taskId === 'equipment' && G.UnitEquipment) G.UnitEquipment.open('infantry');
        graduationSpotlightTimer = setTimeout(clearSpotlight, 5000);
      }
      if (data.showHint) showTaskNotice(task);
      return data;
    });
  }

  /** 自行从地图进入采集时也给一次提示，保持页面与玩家当前目标不变。 */
  function showTaskHint(taskId) {
    var task = state.data && (state.data.optionalTasks || []).find(function (t) { return t.id === taskId; });
    if (!task || task.hintSeen || task.status === 'claimed' || task.status === 'completed' || state.busy) return;
    return mutate('tasks/begin', { taskId: taskId }).then(function (data) {
      if (data && data.showHint) showTaskNotice(task);
    });
  }

  /** 首次玩法说明由玩家主动关闭，避免长提示在短暂 toast 中来不及阅读。 */
  function showTaskNotice(task) {
    var old = document.getElementById('onboardingTaskHint');
    if (old) old.remove();
    state.snoozedFor = state.data && state.data.current ? state.data.current.id : 'supplies';
    closeModal();
    var notice = document.createElement('section'); notice.id = 'onboardingTaskHint'; notice.className = 'ob-card ob-task-hint';
    notice.setAttribute('aria-label', '玩法提示');
    notice.innerHTML = '<strong>' + esc(task.title || '玩法提示') + '</strong><p>' + esc(task.hint) + '</p><button class="btn sm">知道了</button>';
    var close = notice.querySelector && notice.querySelector('button');
    if (close) close.onclick = function () { notice.remove(); clearSpotlight(); };
    if (document.body) document.body.appendChild(notice);
    else { var view = document.getElementById('view'); view.insertBefore(notice, view.firstChild); }
  }

  function bind(root) {
    root.querySelectorAll('[data-ob-action]').forEach(function (el) {
      el.onclick = function () {
        var action = el.dataset.obAction, value = el.dataset.obValue;
        if (action === 'route') { var dest = value.split(':'); go(dest[0], dest[1]); }
        else if (action === 'dismiss') { state.snoozedFor = state.data && state.data.current ? state.data.current.id : 'supplies'; clearSpotlight(); closeModal(); }
        else if (action === 'pause') mutate('pause', { paused: value === 'true' });
        else if (action === 'claim') mutate('claim', { supplyId: value });
        else if (action === 'target') findTarget(value);
        else if (action === 'refresh') refresh();
        else if (action === 'homeCity') {
          var cities = Core.state.cityOverview && Core.state.cityOverview.cities || [];
          var home = cities.find(function (c) { return c.citySlot === 0 || c.main; });
          if (home && G.Cities) G.Cities.select(home.id); else G.toast('请从切城入口选择主城');
        } else mutate(action);
      };
    });
  }

  Core.views.onboarding = function (view) { draw(view); refresh(); };
  G.Onboarding = { init: init, refresh: refresh, stop: stop, render: render, hasSupply: hasSupply,
    state: state, mutate: mutate, beginTask: beginTask, showTaskHint: showTaskHint, findTarget: findTarget, actionStarted: actionStarted, go: go,
    showSpotlight: showSpotlight, clearSpotlight: clearSpotlight,
    getCurrentSpotlightTarget: function () { return currentSpotlightTarget; },
    getCurrentSpotlightTargets: function () { return currentSpotlightTarget ? [currentSpotlightTarget] : []; } };
})(window.Game);
