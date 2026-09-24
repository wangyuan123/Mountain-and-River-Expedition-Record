/* global window, document */
(function (G) {
  'use strict';
  var Core = G.Core;
  var state = { data: null, timer: null, pending: null, busy: false, epoch: 0, error: '' };
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
  function hasSupply() { return state.data && (state.data.supplies || []).some(function (s) { return s.available && !s.claimed; }); }

  function updatePolling() {
    var data = state.data;
    var active = data && data.enrolled && (!data.done || (data.supplies || []).some(function (s) { return !s.claimed; }));
    if (!active && state.timer) { clearInterval(state.timer); state.timer = null; }
    if (active && !state.timer) state.timer = setInterval(refresh, 5000);
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
    if (state.busy) return Promise.resolve(state.data);
    if (state.pending) return state.pending;
    var key = context();
    var request = G.API.client.get('/game/onboarding', { silent: true }).then(function (data) {
      if (key !== context()) return null;
      state.error = '';
      if (JSON.stringify(state.data) !== JSON.stringify(data)) { state.data = data; render(); }
      updatePolling();
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
    state.timer = null; state.pending = null; state.data = null; state.error = ''; state.busy = false;
    var bar = document.getElementById('onboardingBar');
    if (bar) bar.remove();
  }
  function init() {
    stop();
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
      if (action === 'claim') G.toast('补给已送达主城');
      if (action === 'recover') G.toast('援军已抵达主城：30步兵、1侦察机、2卡车');
      updatePolling();
      return data;
    }).catch(function (err) {
      if (key === context()) { state.error = err.message || '操作失败，请刷新确认结果'; G.toast(state.error); }
      return null;
    }).finally(function () { if (key === context()) { state.busy = false; syncPolling(); render(); } });
  }

  function actions(objective) {
    var checks = state.data.checks || {};
    if (objective.id === 'base') {
      return (!checks.command ? routeButton('升级市政厅', 'buildArmy', 'command') : '') +
        (!checks.farm ? routeButton('改善农田', 'buildRes', 'farm') : '') +
        (!checks.factory ? routeButton('建造军工厂', 'buildArmy', 'factory') : '');
    }
    if (objective.id === 'recon') {
      return (!checks.lab ? routeButton('建科研中心', 'buildArmy', 'lab') : '') +
        (!checks.reconTech ? routeButton('研究侦察技术', 'tech') : '') +
        (!checks.scout ? routeButton('生产侦察机', 'army') : '');
    }
    if (objective.id === 'scout' || objective.id === 'occupy' || objective.id === 'gather') {
      return button(objective.id === 'gather' ? '派出采集队' : '选择补给点', 'target', objective.id) + routeButton('查看军情', 'alerts');
    }
    if (objective.id === 'plan') {
      return Object.keys(plans).map(function (key) { return button(plans[key][0], 'plan', key); }).join('');
    }
    return routeButton(objective.id === 'report' ? '查看战报' : '前往安排', objective.route, objective.building);
  }

  function checklist(o) {
    var c = state.data.checks || {}, items = [];
    if (o.id === 'base') items = [['command', '市政厅2级'], ['farm', '农田总等级2'], ['factory', '军工厂1级']];
    if (o.id === 'train') items = [['infantry', '步兵生产完成'], ['truck', '卡车生产完成']];
    if (o.id === 'recon') items = [['lab', '科研中心1级'], ['reconTech', '侦察技术1级'], ['scout', '侦察机生产完成']];
    return items.length ? '<ul class="ob-checks">' + items.map(function (i) {
      return '<li class="' + (c[i[0]] ? 'ob-complete' : '') + '">' + (c[i[0]] ? '已完成 · ' : '待完成 · ') + i[1] + '</li>';
    }).join('') + '</ul>' : '';
  }

  function render() {
    var bar = document.getElementById('onboardingBar');
    if (!Core.state || Core.route === 'login') { stop(); return; }
    var data = state.data;
    var visible = data && data.enrolled && !data.paused && (!data.done || hasSupply()) && Core.route !== 'onboarding';
    if (!visible) { if (bar) bar.remove(); }
    else {
      if (!bar) {
        bar = document.createElement('section'); bar.id = 'onboardingBar'; bar.className = 'ob-bar';
        var view = document.getElementById('view'); view.parentNode.insertBefore(bar, view);
      }
      var current = data.current;
      bar.innerHTML = '<div class="ob-bar-heading"><strong>' + esc(current ? current.title : '前进基地行动已完成') + '</strong>' +
        '<span>' + data.completed + '/' + data.objectives.length + '</span></div>' +
        '<p>' + esc(current ? current.body : '还有行动补给等待领取。') + '</p>' +
        '<div class="ob-actions">' + (current ? actions(current) : '') +
        button(hasSupply() ? '行动与补给 · 可领取' : '行动与补给', 'route', 'onboarding:') + button('暂停提示', 'pause', 'true') + '</div>';
      bind(bar);
    }
    if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
    if (Core.route === 'onboarding') draw(document.getElementById('view'));
  }

  function rewardText(resources) {
    return Object.keys(labels).map(function (key) { return labels[key] + ' ' + resources[key]; }).join(' · ');
  }
  function draw(view) {
    if (!view) return;
    var data = state.data;
    var h = '<div class="onboarding-view"><div class="ob-heading"><img src="img/resources/models/food.webp" alt="" width="40" height="40">' +
      '<div><div class="title">前进基地行动</div><p>整备部队，控制附近的补给点，建立持续补给。</p></div></div>';
    if (state.error) h += '<p class="ob-error" role="alert">' + esc(state.error) + '</p>' + button('重新检查', 'refresh');
    if (!data) h += '<p>正在读取行动记录…</p>';
    else if (!data.enrolled) h += '<p>接管基地，完成第一次侦察、占领与采集。</p>' + button('开启行动', 'start');
    else {
      h += '<div class="ob-overview"><span>已完成 ' + data.completed + ' / ' + data.objectives.length + '</span>' +
        button(data.paused ? '恢复提示' : '暂停 / 跳过提示', 'pause', data.paused ? 'false' : 'true') + '</div>' +
        '<p class="ob-muted">补给送达主城。暂停提示后，行动成果和补给资格继续保留。</p>';
      if (data.done) h += '<p class="ob-success">行动完成 · ' + esc(plans[data.plan] ? plans[data.plan][0] : '') + '</p>' +
        routeButton('继续发展', plans[data.plan] ? plans[data.plan][1] : 'home');
      if (Core.state.player && Core.state.player.citySlot !== 0) h += '<p class="ob-error">当前是分城。前进基地行动在主城进行。</p>' + button('切回主城', 'homeCity');
      if (data.current) h += '<section class="ob-current"><h2>当前目标 · ' + esc(data.current.title) + '</h2><p>' + esc(data.current.body) +
        '</p>' + checklist(data.current) + '<div class="ob-actions">' + actions(data.current) + '</div></section>';
      h += '<section class="ob-supplies"><h2>行动补给</h2>';
      data.supplies.forEach(function (s) {
        h += '<div class="ob-supply"><div><strong>' + esc(s.title) + '</strong><p>' + esc(rewardText(s.resources)) + '</p></div>' +
          button(s.claimed ? '已领取' : s.available ? '领取补给' : '目标未达成', 'claim', s.id, s.claimed || !s.available) + '</div>';
      });
      if (data.recoveryAvailable) h += '<div class="ob-supply"><div><strong>重新整备</strong><p>一次性援军：30步兵、1侦察机、2卡车。</p></div>' + button('接收援军', 'recover') + '</div>';
      h += '</section><section class="ob-objectives"><h2>行动进度</h2>';
      data.objectives.forEach(function (o, index) {
        var current = data.current && data.current.id === o.id;
        h += '<details class="ob-objective"><summary><span>' + (index + 1) + '. ' + esc(o.title) +
          '</span><span class="' + (o.complete ? 'ob-complete' : '') + '">' + (o.complete ? '已完成' : '待完成') + '</span></summary>' +
          '<p>' + esc(o.body) + '</p>' + checklist(o) +
          '<div class="ob-actions">' + (!o.complete && (o.id !== 'plan' || current) ? actions(o) : '') + '</div></details>';
      });
      h += '</section>';
    }
    h += '</div>';
    view.innerHTML = h; bind(view);
  }

  function go(route, building) {
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
    state: state, mutate: mutate, findTarget: findTarget };
})(window.Game);
