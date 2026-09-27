/* global window, document */
(function (G) {
  'use strict';
  var Core = G.Core;
  var state = { data: null, timer: null, pending: null, busy: false, epoch: 0, error: '', snoozedFor: '', guidedAction: null, actionNote: '', expanded: false, screenWasInert: false, reportReading: null };
  var labels = G.Constants.resourceNames;
  var plans = G.Constants.onboardingPlans;

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
          // 军校只是招募步骤的前置，建成后也要重新提示玩家前往军校招募。
          (state.data && state.data.current && state.data.current.id === 'officer' &&
            !state.data.checks.academy && data.checks && data.checks.academy) ||
          (state.data && state.data.current && data.current && state.data.current.id !== data.current.id)) {
        state.snoozedFor = ''; state.guidedAction = null; state.actionNote = '';
      }
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
    state.timer = null; state.pending = null; state.data = null; state.error = ''; state.busy = false;
    state.snoozedFor = ''; state.guidedAction = null; state.actionNote = ''; state.expanded = false;
    closeModal();
  }
  function init() {
    stop();
    return refresh();
  }

  function actionStarted(route, id) {
    var data = state.data, guided = state.guidedAction;
    if (!data || !data.current || data.paused || data.done || !guided ||
        state.snoozedFor !== data.current.id || guided.route !== route ||
        (guided.building && guided.building !== id)) return Promise.resolve(null);
    if (route === 'army' && ((data.current.id === 'train' && !['infantry', 'truck'].includes(id)) ||
        (data.current.id === 'recon' && id !== 'scout'))) return Promise.resolve(null);
    if (route === 'tech' && data.current.id === 'recon' && id !== 'recon_level') return Promise.resolve(null);
    if (route === 'buildArmy' || route === 'buildRes') {
      // 建筑命令下达后保持弹窗收起，玩家需要在施工页面查看倒计时并使用加速；
      // 完工事件或轮询确认对应检查项后，refresh() 会重新打开弹窗。
      state.guidedAction = null;
      state.actionNote = '施工命令已下达。建筑完成后会自动勾选并返回指引。';
      closeModal();
      syncPolling();
      return Promise.resolve(null);
    }
    if (route === 'army') {
      // 造兵命令下达后保持弹窗收起，玩家需要在生产队列查看倒计时并使用加速；
      // 生产完成并由轮询确认目标完成后，refresh() 才重新打开弹窗。
      state.actionNote = '生产命令已下达。部队完成后会自动勾选并返回指引。';
      closeModal();
      syncPolling();
      return Promise.resolve(null);
    }
    if (route === 'tech') {
      // 科技命令下达后保持弹窗收起，玩家需要在研发队列查看倒计时并使用加速；
      // 研发完成并由轮询或完成事件确认后，refresh() 才重新打开弹窗。
      state.guidedAction = null;
      state.actionNote = '研发命令已下达。科技完成后会自动勾选并返回指引。';
      closeModal();
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
      if (action === 'pause' || action === 'skip') state.actionNote = '';
      return data;
    }).catch(function (err) {
      if (key === context()) { state.error = err.message || '操作失败，请刷新确认结果'; G.toast(state.error); }
      return null;
    }).finally(function () { if (key === context()) { state.busy = false; syncPolling(); render(); } });
  }

  function actions(objective) {
    if (objective.id === 'base') return buildButton('升级前线指挥部', '前线指挥部', 'buildArmy', 'command');
    if (objective.id === 'farm') return buildButton('升级农田', '农田', 'buildRes', 'farm');
    if (objective.id === 'factory') return buildButton('建造战地兵工厂', '战地兵工厂', 'buildArmy', 'factory');
    if (objective.id === 'infantry') return routeButton('前往生产步兵', 'army', 'infantry');
    if (objective.id === 'train') return routeButton('前往生产卡车', 'army', 'truck');
    if (objective.id === 'lab') return buildButton('建造国防研究所', '国防研究所', 'buildArmy', 'lab');
    if (objective.id === 'reconTech') return routeButton('研究侦察技术', 'tech', 'recon_level');
    if (objective.id === 'recon') return routeButton('生产侦察机', 'army', 'scout');
    if (objective.id === 'officer') return (state.data && state.data.checks && state.data.checks.academy)
      ? routeButton('前往陆军讲武堂招募军官', 'academy')
      : buildButton('先建造陆军讲武堂', '陆军讲武堂', 'buildArmy', 'academy');
    if (objective.id === 'scout' || objective.id === 'occupy' || objective.id === 'gather') {
      return button(objective.id === 'gather' ? '派出采集队' : '选择推荐资源点', 'target', objective.id);
    }
    if (objective.id === 'report') return routeButton('查看战报', 'alerts');
    if (objective.id === 'develop') return routeButton('前往资源建筑', 'buildRes');
    if (objective.id === 'plan') return Object.keys(plans).map(function (key) { return button(plans[key][0], 'plan', key); }).join('');
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

  /** 主动返回只结束阅读等待并刷新真实任务进度，不代替服务端完成判定。 */
  function returnFromReportReading(reading) {
    if (!reading || state.reportReading !== reading || reading.key !== context()) return;
    clearReportReading();
    state.snoozedFor = '';
    render();
    return refresh();
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
      notice.innerHTML = '<span class="ob-eyebrow">前线指挥部 · 战报阅读</span>' +
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
    var visible = !readingReport && !waitingForScoutReturn && Core.route !== 'onboarding' && currentKey !== state.snoozedFor &&
      ((data && data.enrolled && !data.paused && (!data.done || hasSupply())) || (!data && state.error));
    if (!visible) closeModal();
    else {
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
          '<strong id="obDialogTitle">' + esc(current ? current.title : '行动目标已完成') + '</strong></div>' +
          '<span class="ob-count">' + completed + ' / ' + total + '</span></div>' +
          '<div class="ob-progress" role="progressbar" aria-label="新手行动进度" aria-valuemin="0" aria-valuemax="' + total + '" aria-valuenow="' + completed + '"><span style="width:' + percent + '%"></span></div>' +
          (completed === 0 ? '<p class="ob-intro">先升级资源建筑与军工设施，再生产部队、招募军官、侦察并采集资源。操作完成后目标会自动更新。</p>' : '') +
          '<p id="obDialogDesc">' + esc(current ? current.body : '所有行动步骤已完成。') + '</p>' +
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
          button(remaining.length ? '跳过指引 · 领取剩余补给' : '跳过指引', 'skip') + '</div>';
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
        (plans[data.plan] ? routeButton(plans[data.plan][0], plans[data.plan][1]) : '') + '</div></section>';
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

  function go(route, building) {
    clearReportReading();
    state.snoozedFor = state.data && state.data.current ? state.data.current.id : 'supplies';
    state.guidedAction = ['buildArmy', 'buildRes', 'army', 'tech'].includes(route) ? { route: route, building: building || '' } : null;
    state.actionNote = '';
    closeModal();
    if (route === 'alerts' && state.snoozedFor === 'report') startReportReading();
    G.go(route);
    if (!building) return;
    var target = document.querySelector('[data-building="' + building + '"]');
    if (target) { target.scrollIntoView({ behavior: 'smooth', block: 'center' }); target.classList.add('ob-target'); setTimeout(function () { target.classList.remove('ob-target'); }, 2500); }
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
      world._dispatchTarget = { kind: action === 'gather' ? 'wild_gather' : 'wild', idx: idx, target: target,
        action: action === 'scout' ? 'scout' : action === 'gather' ? 'gather' : 'conquer' };
      // 引导要求资源随部队返城入库，这次推荐采集固定使用全自动，避免停在野地等待手动命令。
      if (action === 'gather') {
        world._dispatchTarget.onboardingGather = true;
        world._dispatchTarget.gatherMode = 'auto';
      }
      state.snoozedFor = state.data && state.data.current ? state.data.current.id : 'supplies';
      state.guidedAction = null; state.actionNote = '';
      closeModal();
      G.go('dispatch');
      // 只预填本次推荐编队，仍由玩家核对情报、数量和路线后确认出征。
      if (G.World && G.World.onDispatchInputChange) {
        document.querySelectorAll('[id^="dqty_"]').forEach(function (input) {
          var unit = input.id.slice(5);
          var desired = action === 'scout' ? (unit === 'scout' ? 1 : 0)
            : action === 'gather' ? (unit === 'truck' ? 2 : 0) : (unit === 'infantry' ? 50 : 0);
          input.value = Math.min(Number(input.max) || 0, desired);
          G.World.onDispatchInputChange(unit, input.value);
        });
      }
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
        else if (action === 'plan') mutate('plan', { plan: value });
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
    state: state, mutate: mutate, findTarget: findTarget, actionStarted: actionStarted };
})(window.Game);
