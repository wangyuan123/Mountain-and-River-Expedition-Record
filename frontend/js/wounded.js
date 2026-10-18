/* global window, document */
window.Game = window.Game || {};
(function (G) {
  'use strict';
  var Core = G.Core;
  function currentCityId() { return (Core.state && Core.state.player || {}).activeCityId; }
  var Camp = {
    data: null, quantities: {}, pending: false, timer: null, request: 0, generation: 0,
    stop: function () {
      if (this.timer) window.clearInterval(this.timer);
      this.timer = null;
      this.request++;
      this.generation++;
      this.pending = false;
    },
    renderView: function (view) {
      this.stop();
      this.view = view;
      this.token = G.API.getToken();
      this.cityId = currentCityId();
      this.data = null;
      this.quantities = {};
      view.innerHTML = '<div class="title">伤兵营</div><div id="wounded-content" aria-live="polite">正在加载伤兵…</div>' +
        '<div class="menu-item back" onclick="Game.go(\'army\')">返回军队</div>';
      this.refresh();
      var self = this;
      this.timer = window.setInterval(function () {
        if (Core.route !== 'wounded' || view.isConnected === false) { self.stop(); return; }
        if (!self.pending) self.refresh();
      }, 5000);
    },
    /** 只接收打开本页的账号和城市响应，切城后不能覆盖新的伤兵列表。 */
    isCurrent: function (token, cityId) {
      return G.API.getToken() === token && currentCityId() === cityId && Core.route === 'wounded';
    },
    refresh: function () {
      var self = this, token = this.token, cityId = this.cityId, request = ++this.request;
      return G.API.getWounded().then(function (data) {
        if (request !== self.request || !self.isCurrent(token, cityId)) return;
        self.data = data;
        self.paint();
      }).catch(function (err) {
        if (request !== self.request || !self.isCurrent(token, cityId)) return;
        if (!self.data) document.getElementById('wounded-content').innerHTML = '<div class="desc">' + G.escapeHtml(err.message || '伤兵加载失败') +
          '</div><button class="btn" onclick="Game.Wounded.refresh()">重新加载</button>';
      });
    },
    paint: function () {
      var root = document.getElementById('wounded-content');
      if (!root || !this.data) return;
      // 保留输入焦点和滑动操作，但余额变化仍需立即更新费用、MAX 和不足提示。
      if (document.activeElement && root.contains && root.contains(document.activeElement) && document.activeElement.tagName === 'INPUT') {
        var balance = document.getElementById('wounded-balance'), self = this;
        if (balance) balance.textContent = this.balanceText();
        (this.data.batches || []).forEach(function (batch) { self.updateControls(batch, self.quantityFor(batch)); });
        return;
      }
      root.innerHTML = this.renderContent(this.data);
    },
    resources: function () { return this.data && this.data.resources || Core.state.resources || {}; },
    balanceText: function () {
      var balance = this.resources();
      return '本城可用黄金 ' + G.fmt(balance.gold || 0) + ' · 账号钻石 ' + G.fmt(balance.diamond || 0) + '，可拖动滑块、输入数量，或点击对应货币的 MAX。';
    },
    /** MAX 按各自余额独立计算；每颗钻石抵 1000 黄金，先取整钻石余额再换算预算。 */
    maxAffordable: function (batch, currency) {
      var balance = Math.max(0, Number(this.resources()[currency]) || 0);
      var budget = currency === 'diamond' ? Math.floor(balance) * 1000 : balance;
      return Math.min(batch.count, Math.floor(budget / batch.goldPerUnit));
    },
    /** 数量限于本批伤兵，允许 MAX 为 0；刷新不能把 0 重新变成整批数量。 */
    quantityFor: function (batch, value) {
      if (value === undefined) value = this.quantities[batch.id] === undefined ? batch.count : this.quantities[batch.id];
      var count = Number(value);
      return Math.max(0, Math.min(batch.count, Number.isFinite(count) ? Math.floor(count) : 0));
    },
    /** 一页只展示本城批次；迁城前的历史坐标不再生成额外的城市选项。 */
    renderContent: function (data) {
      var self = this, esc = G.escapeHtml;
      var batches = (data.batches || []).filter(function (batch) {
        return data.citySlot == null || Number(batch.citySlot) === Number(data.citySlot);
      });
      var total = batches.reduce(function (sum, batch) { return sum + (Number(batch.count) || 0); }, 0);
      var player = Core.state && Core.state.player || {};
      var cityName = data.cityName || player.cityName || '当前城市';
      var cityPosition = data.cityX != null && data.cityY != null ? ' (' + data.cityX + ',' + data.cityY + ')' : '';
      var h = '<div class="desc">当前救治城市：' + esc(cityName + cityPosition) + ' <button class="btn sm" onclick="Game.Cities.open()">切换城市</button></div>' +
        '<div class="wounded-overview"><div><span>待救治部队</span><strong>' + G.fmt(total) + '</strong></div>' +
        '<div><span>医疗技术 Lv.' + data.medicalLevel + '</span><strong>' + (data.medicalLevel * 5) + '%</strong></div></div>';
      h += '<div class="wounded-notice"><b>请在受伤后 7 天内治疗</b><p>每批伤兵独立计时，超过 7 天未治疗将全部消失。资源不足时也不会暂停计时。</p>' +
        '<p>医疗技术每级回收 5%（最高 50%），参战军官急救每级额外回收 3%（最高 15%）。比例在战斗结束时确定，无基础回收率。</p>' +
        '<p>黄金或钻石均可立即治疗，恢复数量相同。黄金为制造资源总价的 10%（每单位向上取整），钻石按每 1000 黄金折算 1 颗、不足 1 颗按 1 颗计。</p></div>';
      h += '<div class="desc" id="wounded-balance">' + self.balanceText() + '</div>';
      if (!batches.length) return h + '<div class="wounded-empty">本城暂无可治疗的伤兵<p>切换城市后可查看和治疗该城伤兵。</p></div>';
      batches.forEach(function (batch) {
        var unit = G.DATA.units[batch.unit];
        var qty = self.quantityFor(batch);
        var warning = self.balanceWarning(batch, qty);
        self.quantities[batch.id] = qty;
        var remaining = Math.max(0, batch.expiresAt - data.serverTime);
        var hours = Math.ceil(remaining / 3600000);
        var expiry = hours >= 24 ? Math.floor(hours / 24) + '天' + (hours % 24) + '小时' : (hours > 1 ? hours + '小时' : '不足1小时');
        h += '<div class="wounded-card"><div class="wounded-card-head"><b>' + esc(unit ? unit.name : batch.unit) + '</b><strong>' + G.fmt(batch.count) + '</strong></div>' +
          '<div class="wounded-meta">本次回收 ' + batch.recoveryPercent + '% · <span class="' + (hours <= 24 ? 'wounded-urgent' : '') + '">剩余 ' + expiry + '</span></div>' +
          '<div class="wounded-controls"><div class="wounded-quantity"><label>治疗数量 <input class="qty" type="number" id="wounded-qty-' + batch.id + '" min="0" max="' + batch.count + '" step="1" value="' + qty + '" aria-describedby="wounded-warning-' + batch.id + '" oninput="Game.Wounded.changeQuantity(' + batch.id + ',this.value)"' + (self.pending ? ' disabled' : '') + '> / ' + G.fmt(batch.count) + '</label>' +
          '<div class="recruit-slider-wrap"><input class="recruit-slider" type="range" id="wounded-range-' + batch.id + '" min="0" max="' + batch.count + '" step="1" value="' + qty + '" style="--p:' + (batch.count ? qty / batch.count * 100 : 0) + '%" aria-label="' + esc((unit ? unit.name : batch.unit) + '治疗数量') + '" aria-describedby="wounded-warning-' + batch.id + '" oninput="Game.Wounded.changeQuantity(' + batch.id + ',this.value)"' + (self.pending ? ' disabled' : '') + '></div>' +
          '<div id="wounded-warning-' + batch.id + '" class="wounded-balance-warning" role="status"' + (warning ? '' : ' hidden') + '>' + warning + '</div></div>' +
          '<div id="wounded-pay-' + batch.id + '" class="wounded-payments">' + self.paymentButtons(batch, qty) + '</div></div></div>';
      });
      return h;
    },
    paymentButtons: function (batch, count) {
      var resources = this.resources();
      var gold = batch.goldPerUnit * count, diamond = Math.ceil(gold / 1000);
      var valid = Number.isInteger(count) && count > 0 && count <= batch.count;
      return ['gold', 'diamond'].map(function (currency) {
        var price = currency === 'gold' ? gold : diamond;
        var disabled = !valid || Camp.pending || (resources[currency] || 0) < price;
        var label = currency === 'gold' ? '黄金' : '钻石';
        var maximum = Camp.maxAffordable(batch, currency);
        return '<div class="wounded-payment-row"><button type="button" class="btn sm"' + (Camp.pending ? ' disabled' : '') + ' aria-label="' + label + ' MAX" title="按' + label + '余额最多治疗 ' + G.fmt(maximum) + ' 名，仅设置数量" onclick="Game.Wounded.selectMax(' + batch.id + ',\'' + currency + '\')">' + label + ' MAX</button>' +
          '<button type="button" class="btn sm"' + (disabled ? ' disabled' : '') + ' onclick="Game.Wounded.heal(' + batch.id + ',\'' + currency + '\')">' + label + '治疗 · ' + (valid ? G.fmt(price) : '—') + '</button></div>';
      }).join('');
    },
    /** 两种支付方式独立提示；两者都不足时避免建议切换到同样不足的货币。 */
    balanceWarning: function (batch, count) {
      var goldMax = this.maxAffordable(batch, 'gold'), diamondMax = this.maxAffordable(batch, 'diamond');
      if (goldMax === 0 && diamondMax === 0) return '黄金和钻石余额均不足，当前无法治疗任何伤兵，请补充黄金或钻石。';
      var goldShort = count > goldMax || goldMax === 0, diamondShort = count > diamondMax || diamondMax === 0;
      if (goldShort && diamondShort) return '黄金和钻石余额均不足，请减少治疗数量，或点击对应 MAX 选择当前可治疗的最大数量。';
      if (goldShort) return goldMax === 0 ? '黄金余额不足，当前无法使用黄金治疗，请补充黄金或使用钻石治疗。' : '黄金余额不足，请减少治疗数量、点击黄金 MAX，或使用钻石治疗。';
      if (diamondShort) return diamondMax === 0 ? '钻石余额不足，当前无法使用钻石治疗，请补充钻石或使用黄金治疗。' : '钻石余额不足，请减少治疗数量、点击钻石 MAX，或使用黄金治疗。';
      return '';
    },
    /** 同步滑块、输入框、费用和提示，不重绘卡片，以保持连续拖动与键盘输入。 */
    updateControls: function (batch, count) {
      this.quantities[batch.id] = count;
      var input = document.getElementById('wounded-qty-' + batch.id);
      var range = document.getElementById('wounded-range-' + batch.id);
      var pay = document.getElementById('wounded-pay-' + batch.id);
      var warning = document.getElementById('wounded-warning-' + batch.id);
      if (input) { input.max = batch.count; input.value = count; input.disabled = this.pending; }
      if (range) {
        range.max = batch.count;
        range.value = count;
        range.disabled = this.pending;
        range.style.setProperty('--p', (batch.count ? count / batch.count * 100 : 0) + '%');
      }
      if (pay) pay.innerHTML = this.paymentButtons(batch, count);
      if (warning) { warning.textContent = this.balanceWarning(batch, count); warning.hidden = !warning.textContent; }
    },
    changeQuantity: function (id, value) {
      if (this.pending) return;
      var batch = this.data.batches.find(function (b) { return b.id === id; });
      if (!batch) return;
      this.updateControls(batch, this.quantityFor(batch, value));
    },
    /** 快选只修改数量，不提交治疗，也不自动选择或消耗另一种货币。 */
    selectMax: function (id, currency) {
      if (this.pending || (currency !== 'gold' && currency !== 'diamond')) return;
      var batch = this.data.batches.find(function (b) { return b.id === id; });
      if (batch) this.updateControls(batch, this.maxAffordable(batch, currency));
    },
    heal: function (id, currency) {
      if (this.pending || !this.isCurrent(this.token, this.cityId)) return;
      var count = this.quantities[id];
      if (!Number.isInteger(count) || count <= 0) { G.toast('请输入有效的治疗数量'); return; }
      var self = this, token = this.token, cityId = this.cityId, generation = this.generation;
      this.pending = true;
      this.request++; // 忽略治疗前仍在返回的旧伤兵列表。
      this.paint();
      return G.API.healWounded(id, count, currency).then(function (result) {
        if (generation !== self.generation || !self.isCurrent(token, cityId)) return;
        if (!result.success) throw new Error(result.message || '治疗失败');
        self.data = result.camp;
        delete self.quantities[id];
        G.toast(result.message || '治疗完成，部队已归队');
        Core.refreshTop();
      }).catch(function (err) {
        if (generation === self.generation && self.isCurrent(token, cityId)) G.toast(err.message || '治疗失败，请刷新伤兵营确认结果');
      }).finally(function () {
        // 旧页面的治疗结束不能清除新城市正在进行的治疗状态。
        if (generation !== self.generation) return;
        self.pending = false;
        if (self.isCurrent(token, cityId)) { self.paint(); self.refresh(); }
      });
    }
  };
  G.Wounded = Camp;
  Core.views.wounded = function (view) { Camp.renderView(view); };
})(window.Game);
