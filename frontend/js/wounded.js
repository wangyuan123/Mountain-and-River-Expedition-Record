/* global window, document */
window.Game = window.Game || {};
(function (G) {
  'use strict';
  var Core = G.Core;
  var Camp = {
    data: null, city: '', quantities: {}, pending: false, timer: null, request: 0,
    stop: function () {
      if (this.timer) window.clearInterval(this.timer);
      this.timer = null;
      this.request++;
    },
    renderView: function (view) {
      this.stop();
      this.view = view;
      this.token = G.API.getToken();
      this.data = null;
      this.city = '';
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
    refresh: function () {
      var self = this, token = this.token, request = ++this.request;
      return G.API.getWounded().then(function (data) {
        if (request !== self.request || G.API.getToken() !== token || Core.route !== 'wounded') return;
        self.data = data;
        self.paint();
      }).catch(function (err) {
        if (request !== self.request || G.API.getToken() !== token || Core.route !== 'wounded') return;
        if (!self.data) document.getElementById('wounded-content').innerHTML = '<div class="desc">' + G.escapeHtml(err.message || '伤兵加载失败') +
          '</div><button class="btn" onclick="Game.Wounded.refresh()">重新加载</button>';
      });
    },
    selectCity: function (key) { this.city = key; this.paint(); },
    paint: function () {
      var root = document.getElementById('wounded-content');
      if (!root || !this.data) return;
      // 定时刷新不打断数量输入；治疗前服务端会再次校验数量和期限。
      if (document.activeElement && root.contains && root.contains(document.activeElement) && document.activeElement.tagName === 'INPUT') return;
      root.innerHTML = this.renderContent(this.data);
    },
    renderContent: function (data) {
      var self = this, esc = G.escapeHtml, groups = {};
      (data.batches || []).forEach(function (batch) {
        var key = batch.cityX + ',' + batch.cityY;
        if (!groups[key]) groups[key] = { name: batch.cityName, count: 0, batches: [] };
        groups[key].count += batch.count;
        groups[key].batches.push(batch);
      });
      var keys = Object.keys(groups);
      if (!groups[this.city]) this.city = keys[0] || '';
      var h = '<div class="wounded-overview"><div><span>待救治部队</span><strong>' + G.fmt(data.total || 0) + '</strong></div>' +
        '<div><span>医疗技术 Lv.' + data.medicalLevel + '</span><strong>' + (data.medicalLevel * 5) + '%</strong></div></div>';
      h += '<div class="wounded-notice"><b>请在受伤后 7 天内治疗</b><p>每批伤兵独立计时，超过 7 天未治疗将全部消失。资源不足时也不会暂停计时。</p>' +
        '<p>医疗技术每级回收 5%（最高 50%），参战军官急救每级额外回收 3%（最高 15%）。比例在战斗结束时确定，无基础回收率。</p>' +
        '<p>黄金或钻石均可立即治疗，恢复数量相同。黄金为制造资源总价的 10%（每单位向上取整），钻石按每 100 黄金折算 1 颗、不足 1 颗按 1 颗计。</p></div>';
      var balance = Core.state.resources || {};
      h += '<div class="desc">可用黄金 ' + G.fmt(balance.gold || 0) + ' · 钻石 ' + G.fmt(balance.diamond || 0) + '，余额不足时可减少治疗数量或换一种货币。</div>';
      if (!keys.length) return h + '<div class="wounded-empty">暂无可治疗的伤兵<p>新战斗中可救治的损失部队会在这里按城市记录。</p></div>';
      h += '<div class="wounded-cities" aria-label="选择伤兵所属城市">';
      keys.forEach(function (key) {
        h += '<button class="btn sm' + (key === self.city ? ' ok' : '') + '" aria-pressed="' + (key === self.city) + '" onclick="Game.Wounded.selectCity(\'' + key + '\')">' + esc(groups[key].name) +
          ' (' + key + ') · ' + G.fmt(groups[key].count) + '</button>';
      });
      h += '</div>';
      groups[this.city].batches.forEach(function (batch) {
        var unit = G.DATA.units[batch.unit];
        var qty = Math.min(batch.count, self.quantities[batch.id] || batch.count);
        self.quantities[batch.id] = qty;
        var remaining = Math.max(0, batch.expiresAt - data.serverTime);
        var hours = Math.ceil(remaining / 3600000);
        var expiry = hours >= 24 ? Math.floor(hours / 24) + '天' + (hours % 24) + '小时' : (hours > 1 ? hours + '小时' : '不足1小时');
        h += '<div class="wounded-card"><div class="wounded-card-head"><b>' + esc(unit ? unit.name : batch.unit) + '</b><strong>' + G.fmt(batch.count) + '</strong></div>' +
          '<div class="wounded-meta">本次回收 ' + batch.recoveryPercent + '% · <span class="' + (hours <= 24 ? 'wounded-urgent' : '') + '">剩余 ' + expiry + '</span></div>' +
          '<div class="wounded-controls"><label>治疗数量 <input class="qty" type="number" id="wounded-qty-' + batch.id + '" min="1" max="' + batch.count + '" value="' + qty + '" oninput="Game.Wounded.changeQuantity(' + batch.id + ',this.value)"></label>' +
          '<div id="wounded-pay-' + batch.id + '" class="btn-row">' + self.paymentButtons(batch, qty) + '</div></div></div>';
      });
      return h;
    },
    paymentButtons: function (batch, count) {
      var resources = Core.state.resources || {};
      var gold = batch.goldPerUnit * count, diamond = Math.ceil(gold / 100);
      var valid = Number.isInteger(count) && count > 0 && count <= batch.count;
      return ['gold', 'diamond'].map(function (currency) {
        var price = currency === 'gold' ? gold : diamond;
        var disabled = !valid || Camp.pending || (resources[currency] || 0) < price;
        var label = currency === 'gold' ? '黄金' : '钻石';
        return '<button class="btn sm"' + (disabled ? ' disabled' : '') + ' onclick="Game.Wounded.heal(' + batch.id + ',\'' + currency + '\')">' + label + '治疗 · ' + (valid ? G.fmt(price) : '—') + '</button>';
      }).join('');
    },
    changeQuantity: function (id, value) {
      var batch = this.data.batches.find(function (b) { return b.id === id; });
      if (!batch) return;
      var count = Number(value);
      this.quantities[id] = count;
      document.getElementById('wounded-pay-' + id).innerHTML = this.paymentButtons(batch, count);
    },
    heal: function (id, currency) {
      if (this.pending) return;
      var count = this.quantities[id];
      if (!Number.isInteger(count) || count <= 0) { G.toast('请输入有效的治疗数量'); return; }
      var self = this, token = this.token;
      this.pending = true;
      this.request++; // 忽略治疗前仍在返回的旧伤兵列表。
      this.paint();
      return G.API.healWounded(id, count, currency).then(function (result) {
        if (G.API.getToken() !== token) return;
        if (!result.success) throw new Error(result.message || '治疗失败');
        self.data = result.camp;
        delete self.quantities[id];
        G.toast(result.message || '治疗完成，部队已归队');
        Core.refreshTop();
      }).catch(function (err) {
        if (G.API.getToken() === token) G.toast(err.message || '治疗失败，请刷新伤兵营确认结果');
      }).finally(function () {
        self.pending = false;
        if (Core.route === 'wounded' && G.API.getToken() === token) { self.paint(); self.refresh(); }
      });
    }
  };
  G.Wounded = Camp;
  Core.views.wounded = function (view) { Camp.renderView(view); };
})(window.Game);
