/* global window, document */
window.Game = window.Game || {};
(function (G) {
  'use strict';
  var day = 86400000;
  var tiers = (G.Constants.mobilizationOrders || []).map(function (tier) {
    return { id: tier.id, name: tier.name, percent: tier.bonusPercent };
  });
  var lastContext = '';
  var lastBonus = -1;
  var refreshPending = false;
  function now() { return G.gameNow ? G.gameNow() : Date.now(); }
  function player() { return G.Core.state && G.Core.state.player || {}; }
  function esc(value) { return G.escapeHtml ? G.escapeHtml(String(value)) : String(value); }
  function fmt(value) { return G.fmt ? G.fmt(value) : String(value); }

  /** 各档独立到期，同一时刻只使用最高档；保留被覆盖档位供玩家查看。 */
  function info() {
    var time = now();
    var expiries = player().mobilizationExpiries || {};
    var active = tiers.map(function (tier) {
      return { id: tier.id, name: tier.name, percent: tier.percent, until: Number(expiries[tier.id]) || 0 };
    }).filter(function (tier) { return tier.until > time; });
    var current = active.length ? active[active.length - 1] : null;
    return { active: active, current: current, percent: current ? current.percent : 0,
      key: current ? 'mobilization:' + (player().mobilizationCycle || 0) : 'default' };
  }

  function countdown(until) {
    var remaining = until - now();
    if (remaining <= 0) return '已结束';
    if (remaining < 60000) return '不足1分钟';
    var minutes = Math.floor(remaining / 60000);
    var days = Math.floor(minutes / 1440);
    var hours = Math.floor(minutes % 1440 / 60);
    return (days ? days + '天 ' : '') + hours + '小时 ' + minutes % 60 + '分钟';
  }

  function endTime(until) {
    var wallTime = G.GameTime && G.GameTime.toWallTime ? G.GameTime.toWallTime(until) : until;
    return new Date(wallTime).toLocaleString('zh-CN', { hour12: false, timeZone: 'Asia/Shanghai' }) + '（北京时间）';
  }

  function content() {
    var state = info();
    if (!state.current) return '<p>动员未启用</p>';
    var current = state.current;
    var html = '<p><strong>' + esc(current.name) + '生效中 · 带兵上限＋' + current.percent + '%</strong><br>剩余：' +
      countdown(current.until) + '<br>结束：' + esc(endTime(current.until)) + '</p>';
    if (state.active.length > 1) {
      html += '<details><summary>查看其他档位剩余时间</summary>';
      state.active.forEach(function (tier) {
        if (tier.id !== current.id) html += '<p>' + esc(tier.name) + '＋' + tier.percent + '% · 剩余 ' + countdown(tier.until) +
          '<br>结束：' + esc(endTime(tier.until)) + '<br>当前被更高档效果覆盖，仍正常计时</p>';
      });
      html += '</details>';
    }
    return html;
  }

  function widget() { return '<div data-mobilization-status>' + content() + '</div>'; }

  /** 仅原位更新分钟文本；到期立即更新状态和配置上下文，不整页重绘正在编辑的表单。 */
  function tick() {
    if (typeof document === 'undefined') return;
    var state = info();
    var context = player().id + ':' + state.key;
    var changed = context !== lastContext || lastBonus !== state.percent;
    lastContext = context;
    lastBonus = state.percent;
    if (document.querySelectorAll) {
      document.querySelectorAll('[data-mobilization-status]').forEach(function (node) {
        // 保留展开状态，分钟变化时不把其他档位重新折叠。
        var details = node.querySelector('details');
        var open = details && details.open;
        var html = content();
        if (node._mobilizationHtml !== html) {
          node.innerHTML = html;
          node._mobilizationHtml = html;
          details = node.querySelector('details');
          if (details && open) details.open = true;
        }
      });
      document.querySelectorAll('[data-mobilization-cap]').forEach(function (node) {
        node.textContent = fmt(G.Core.armyCap());
      });
    }
    if (changed) {
      if (G.BattleDefaults && G.BattleDefaults.refreshMobilization) G.BattleDefaults.refreshMobilization();
      if (G.World && document.getElementById('estDispatchTroops')) G.World.updateDispatchStats();
    }
  }

  /** 重新显示页面时校准服务端时钟和跨设备状态；本地到期判断始终不依赖轮询成功。 */
  function refresh() {
    tick();
    if (refreshPending || !player().id || !G.API || !G.API.getGameState) return;
    refreshPending = true;
    var account = player().id;
    G.API.getGameState(true).then(function (state) {
      if (account === player().id && state && state.player && state.player.id === account) G.API.applyState(state);
      tick();
    }).catch(function () {}).finally(function () { refreshPending = false; });
  }

  function requestId() {
    return window.crypto && window.crypto.randomUUID ? window.crypto.randomUUID() :
      'mob-' + Date.now() + '-' + Math.random().toString(36).slice(2);
  }

  /** 使用前显示实际范围、同档续期与被覆盖提醒；最终确认才提交扣库存请求。 */
  function open(itemId) {
    if (document.getElementById('mobilizationUseMask')) return;
    var tier = tiers.find(function (entry) { return entry.id === itemId; });
    var stock = Number((G.Core.state.items || {})[itemId]) || 0;
    if (!tier || stock <= 0) { G.toast('动员令数量不足'); return; }
    var account = player().id;
    var trigger = document.activeElement;
    var mask = document.createElement('div');
    mask.id = 'mobilizationUseMask';
    mask.className = 'modal-mask';
    mask.innerHTML = '<div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="mobilizationUseTitle">' +
      '<div class="modal-title" id="mobilizationUseTitle">使用' + esc(tier.name) + '</div><div class="modal-body">' +
      '<p>带兵上限＋' + tier.percent + '%；每张持续24小时。</p>' +
      '<label for="mobilizationUseCount">使用数量（库存 ' + stock + '）</label> ' +
      '<input type="number" id="mobilizationUseCount" min="1" max="' + Math.min(stock, 10000) + '" step="1" value="1">' +
      '<div id="mobilizationUsePreview"></div>' +
      '<p>范围：本账号所有城市的新出征行军（含侦查、采集、运输、调遣、驻防）与本城防守出战。各城市按自身军衔与统帅技能计算。</p>' +
      '<p>同档累加时长，异档分别计时、只取最高加成。离线正常计时；已出征队伍与已开始战斗不受后续到期影响。</p>' +
      '<p>首次启用复制默认防守编队；动员期间单独保存，全部到期后恢复默认编队。</p>' +
      '<p id="mobilizationUseMessage" role="status"></p></div><div class="modal-foot">' +
      '<button type="button" class="btn" id="mobilizationUseCancel">取消</button>' +
      '<button type="button" class="btn ok" id="mobilizationUseConfirm">确认使用</button></div></div>';
    document.body.appendChild(mask);
    var input = document.getElementById('mobilizationUseCount');
    var submit = document.getElementById('mobilizationUseConfirm');
    var cancel = document.getElementById('mobilizationUseCancel');
    var preview = document.getElementById('mobilizationUsePreview');
    var message = document.getElementById('mobilizationUseMessage');
    var pending = false;
    var uncertain = false;
    var id = requestId();
    var submittedCount = null;
    function close() {
      if (pending) return;
      if (uncertain) { message.textContent = '使用结果尚未确认，请重试确认；相同请求不会重复扣除。'; return; }
      mask.remove();
      if (trigger && trigger.isConnected && trigger.focus) trigger.focus();
    }
    function update() {
      var count = Number(input.value);
      var valid = Number.isInteger(count) && count >= 1 && count <= Math.min(stock, 10000);
      submit.disabled = pending || !valid;
      if (!valid) { preview.textContent = '请输入库存范围内的整数数量。'; return; }
      var time = now();
      var until = Number((player().mobilizationExpiries || {})[itemId]) || 0;
      var end = Math.max(time, until) + count * day;
      var ordinary = G.Core.ordinaryArmyCap ? G.Core.ordinaryArmyCap() : G.Core.armyCap();
      var current = info();
      var percent = Math.max(tier.percent, current.percent);
      preview.innerHTML = '<p>本档当前剩余：' + (until > time ? countdown(until) : '未启用') +
        '<br>新增：' + count * 24 + '小时<br>使用后本档剩余：' + countdown(end) +
        '<br>预计结束：' + esc(endTime(end)) + '<br>当前城市带兵上限：' + fmt(G.Core.armyCap()) + ' → ' +
        fmt(Math.floor(ordinary * (100 + percent) / 100)) + '</p>' +
        (current.percent > tier.percent ? '<p>提醒：本档暂被更高档动员覆盖，仍正常计时，不会提高当前加成。</p>' : '');
      submit.textContent = '确认使用，消耗 ' + count + ' 张';
    }
    input.oninput = function () { id = requestId(); update(); };
    cancel.onclick = close;
    mask.onclick = function (event) { if (event.target === mask) close(); };
    mask.onkeydown = function (event) { if (event.key === 'Escape') close(); };
    submit.onclick = function () {
      if (pending || submit.disabled) return;
      if (account !== player().id) { message.textContent = '账号已切换，请关闭弹窗重新使用。'; return; }
      var count = uncertain ? submittedCount : Number(input.value);
      if (!Number.isInteger(count) || count < 1 || count > Math.min(stock, 10000)) return;
      submittedCount = count;
      pending = true;
      submit.disabled = true;
      input.disabled = true;
      cancel.disabled = true;
      G.API.depotUse(itemId, null, null, null, count, id).then(function (response) {
        if (response && response.success === false) { var rejected = new Error(response.message || '使用失败'); rejected.uncertain = false; throw rejected; }
        uncertain = false;
        pending = false;
        mask.remove();
        G.toast(response.message || '动员已生效');
        if (account === player().id) { G.Core.render(); tick(); }
      }).catch(function (error) {
        uncertain = error.uncertain !== false && !(error.status >= 400 && error.status < 500);
        if (!uncertain) input.disabled = false;
        message.textContent = (error.message || '网络异常') + (uncertain ? '；可重试确认，同一请求不会重复扣除。' : '');
      }).finally(function () {
        pending = false;
        submit.disabled = false;
        cancel.disabled = false;
        submit.textContent = uncertain ? '重试确认（不会重复扣除）' : '确认使用';
      });
    };
    update();
    submit.focus();
  }

  G.Mobilization = { info: info, countdown: countdown, widget: widget, tick: tick, refresh: refresh, open: open };
  if (typeof window.setInterval === 'function') window.setInterval(tick, 1000);
  if (typeof document !== 'undefined' && document.addEventListener) {
    document.addEventListener('visibilitychange', function () { if (!document.hidden) refresh(); });
  }
})(window.Game);
