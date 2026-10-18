/* global window, document */
window.Game = window.Game || {};
(function (G) {
  'use strict';
  var Core = G.Core;
  function cityId() { return Core.state && Core.state.player && Core.state.player.activeCityId; }
  var Pool = {
    data: null, quantities: {}, pending: false, timer: null, request: 0, attempt: null,
    stop: function () {
      if (this.timer) window.clearInterval(this.timer);
      this.timer = null;
      this.request++;
      this.closeModal();
    },
    closeModal: function () {
      var modal = document.getElementById('prisoner-modal');
      if (modal) modal.remove();
    },
    renderView: function (view) {
      this.stop();
      this.token = G.API.getToken(); this.cityId = cityId();
      this.storageKey = 'prisoner-operation-' + Core.state.player.id + '-' + this.cityId;
      this.data = null; this.quantities = {}; this.pending = false; this.attempt = null;
      try { this.attempt = JSON.parse(window.localStorage.getItem(this.storageKey) || 'null'); } catch (e) {}
      view.innerHTML = '<div class="title">俘虏池</div><div id="prisoner-content" aria-live="polite">正在加载战俘…</div>';
      this.refresh();
      var self = this;
      this.timer = window.setInterval(function () {
        if (Core.route !== 'prisoners' || view.isConnected === false) { self.stop(); return; }
        if (!self.pending) self.refresh();
      }, 5000);
    },
    matches: function (token, city) { return G.API.getToken() === token && cityId() === city && Core.route === 'prisoners'; },
    refresh: function () {
      var self = this, token = this.token, city = this.cityId, request = ++this.request;
      return G.API.getPrisoners().then(function (data) {
        if (request !== self.request || !self.matches(token, city) || data.cityId !== city) return;
        self.data = data; self.paint();
      }).catch(function (err) {
        if (request !== self.request || !self.matches(token, city)) return;
        var root = document.getElementById('prisoner-content');
        if (!self.data && root) root.innerHTML = '<div class="desc">' + G.escapeHtml(err.message || '俘虏池加载失败') + '</div><button class="btn" onclick="Game.Prisoners.refresh()">重新加载</button>';
      });
    },
    paint: function () {
      var root = document.getElementById('prisoner-content');
      if (!root || !this.data) return;
      if (!this.pending && document.activeElement && root.contains(document.activeElement) && document.activeElement.tagName === 'INPUT') return;
      root.innerHTML = this.renderContent(this.data);
    },
    renderContent: function (data) {
      var self = this, esc = G.escapeHtml;
      var h = '<div class="prisoner-summary"><div><span>' + esc(data.cityName) + ' · 战俘营 Lv.' + data.campLevel + '</span><strong>' + G.fmt(data.total) + ' / ' + G.fmt(data.capacity) + '</strong></div>' +
        '<div><span>战俘收编 Lv.' + data.educationLevel + '</span><strong>俘获比例 ' + (data.ratePermille / 10).toFixed(1) + '%</strong></div>' +
        '<div><span>本城黄金</span><strong>' + G.fmt((Core.state.resources || {}).gold || 0) + '</strong></div></div>';
      if (this.attempt) h += '<div class="prisoner-notice">上次' + (this.attempt.action === 'recruit' ? '招募' : '释放') + '结果尚未确认：' + esc((G.DATA.units[this.attempt.unit] || {}).name || this.attempt.unit) + ' × ' + G.fmt(this.attempt.count) +
        '<button class="btn sm"' + (this.pending ? ' disabled' : '') + ' onclick="Game.Prisoners.retry()">重试确认结果</button></div>';
      if (!data.capacity || data.total >= data.capacity) h += '<div class="prisoner-notice">' + (!data.capacity ? '本城尚无战俘营' : '本城俘虏池已满') +
        '<button class="btn sm" onclick="Game.go(\'buildArmy\');Game.Build.openBuildingDetailModal(\'prisoner_camp\')">' + (!data.capacity ? '建造战俘营' : '升级战俘营') + '</button></div>';
      if (data.admissionCapacity != null && data.admissionCapacity < data.capacity) h += '<div class="desc">降级施工中 · 收容上限 ' + G.fmt(data.admissionCapacity) + '</div>';
      if (!(data.units || []).length) return h + '<div class="prisoner-empty">本城暂无战俘</div>';
      (data.units || []).forEach(function (unit) {
        var def = G.DATA.units[unit.unit] || { name: unit.unit };
        var qty = Object.prototype.hasOwnProperty.call(self.quantities, unit.unit) ? self.quantities[unit.unit] : 1;
        self.quantities[unit.unit] = qty;
        h += '<div class="prisoner-unit"><div class="prisoner-unit-head"><b>' + esc(def.name) + '</b><strong>' + G.fmt(unit.count) + '</strong></div>' +
          '<div class="desc">黄金单价 ' + G.fmt(unit.goldPerUnit) + (unit.unavailableReason ? ' · ' + esc(unit.unavailableReason) : '') + '</div>' +
          '<div class="prisoner-controls"><label>数量 <input class="qty" type="number" min="1" max="' + unit.count + '" value="' + (Number.isFinite(qty) ? qty : '') + '"' + (self.pending || self.attempt ? ' disabled' : '') + ' oninput="Game.Prisoners.changeQuantity(\'' + unit.unit + '\',this.value)"></label>' +
          '<div class="btn-row" id="prisoner-actions-' + unit.unit + '">' + self.buttons(unit, qty) + '</div></div></div>';
      });
      return h;
    },
    buttons: function (unit, count) {
      var valid = Number.isInteger(count) && count > 0 && count <= unit.count;
      var disabled = !valid || this.pending || this.attempt;
      var cost = unit.goldPerUnit * count;
      return '<button class="btn sm ok"' + (disabled || unit.unavailableReason || cost > ((Core.state.resources || {}).gold || 0) ? ' disabled' : '') + ' onclick="Game.Prisoners.confirm(\'recruit\',\'' + unit.unit + '\')">黄金招募 · ' + (valid ? G.fmt(cost) : '待输入') + '</button>' +
        '<button class="btn sm warn"' + (disabled ? ' disabled' : '') + ' onclick="Game.Prisoners.confirm(\'release\',\'' + unit.unit + '\')">释放</button>';
    },
    changeQuantity: function (unit, value) {
      if (this.pending || this.attempt) return;
      var item = this.data.units.find(function (u) { return u.unit === unit; });
      if (!item) return;
      this.quantities[unit] = Number(value);
      document.getElementById('prisoner-actions-' + unit).innerHTML = this.buttons(item, this.quantities[unit]);
    },
    /** 数量和归队城市在确认时冻结；释放必须在摘要弹窗再次确认，无资源奖励。 */
    confirm: function (action, unit) {
      if (this.pending || this.attempt || !this.data || !this.matches(this.token, this.cityId)) return;
      var item = this.data.units.find(function (u) { return u.unit === unit; }), count = this.quantities[unit];
      if (!item || !Number.isInteger(count) || count <= 0 || count > item.count) { G.toast('请输入有效数量'); return; }
      if (action === 'recruit' && item.unavailableReason) { G.toast(item.unavailableReason); return; }
      var operation = { action: action, unit: unit, count: count, operationKey: 'prisoner_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 14) };
      this.closeModal();
      var mask = document.createElement('div'); mask.id = 'prisoner-modal'; mask.className = 'modal-mask';
      mask.innerHTML = '<div class="modal-card prisoner-modal" role="dialog" aria-modal="true" aria-labelledby="prisoner-modal-title"><div class="title" id="prisoner-modal-title">' + (action === 'recruit' ? '确认黄金招募' : '确认释放战俘') + '</div>' +
        '<p>' + G.escapeHtml((G.DATA.units[unit] || {}).name || unit) + ' × ' + G.fmt(count) + '</p><p>' + (action === 'recruit' ? '黄金 ' + G.fmt(item.goldPerUnit * count) + ' · 归队城市 ' + G.escapeHtml(this.data.cityName) : '所属城市 ' + G.escapeHtml(this.data.cityName) + ' · 释放后无法撤回，无资源奖励') + '</p>' +
        '<div class="btn-row"><button class="btn" id="prisoner-cancel">取消</button><button class="btn ' + (action === 'recruit' ? 'ok' : 'warn') + '" id="prisoner-confirm">' + (action === 'recruit' ? '支付黄金并招募' : '确定释放') + '</button></div></div>';
      document.body.appendChild(mask);
      var self = this;
      document.getElementById('prisoner-cancel').onclick = function () { self.closeModal(); };
      document.getElementById('prisoner-confirm').onclick = function () { self.closeModal(); self.submit(operation); };
    },
    saveAttempt: function () {
      try {
        if (this.attempt) window.localStorage.setItem(this.storageKey, JSON.stringify(this.attempt));
        else window.localStorage.removeItem(this.storageKey);
      } catch (e) {}
    },
    retry: function () { if (this.attempt) return this.submit(this.attempt); },
    /** 未确认的写请求保持原凭证与数量，重试先读服务端收据，不根据刷新后的库存重新发起。 */
    submit: function (operation) {
      if (this.pending || !this.matches(this.token, this.cityId)) return;
      var self = this, token = this.token, city = this.cityId;
      this.pending = true; this.request++; this.attempt = operation; this.saveAttempt(); this.paint();
      return G.API.prisonerOperation(operation.action, operation.unit, operation.count, operation.operationKey).then(function (result) {
        if (!self.matches(token, city)) return;
        if (!result.success) throw new Error(result.message || '操作失败');
        self.attempt = null; self.saveAttempt(); self.data = result.pool;
        delete self.quantities[operation.unit]; G.toast(result.message); Core.refreshTop();
      }).catch(function (err) {
        if (!self.matches(token, city)) return;
        if (err.status >= 400 && err.status < 500 && !err.uncertain) { self.attempt = null; self.saveAttempt(); }
        G.toast(err.message || '结果尚未确认，请重试确认');
      }).finally(function () {
        if (!self.matches(token, city)) return;
        self.pending = false; self.paint(); self.refresh();
      });
    }
  };
  G.Prisoners = Pool;
  Core.views.prisoners = function (view) { Pool.renderView(view); };
})(window.Game);
