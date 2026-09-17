/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var Core = G.Core;
  var CATEGORIES = [
    { id: 'diamond', name: '钻石充值' },
    { id: 'marshal', name: '元帅礼包' },
    { id: 'monthly', name: '月卡特权' }
  ];
  var PACKAGES = [
    { id: 'p6', cat: 'diamond', name: '试玩补给', icon: '💎', rmb: 6, diamond: 60, desc: '适合首次体验充值' },
    { id: 'p30', cat: 'diamond', name: '少将补给', icon: '💠', rmb: 30, diamond: 330, desc: '额外赠送30钻石', bonus: '赠30' },
    { id: 'p98', cat: 'diamond', name: '中将补给', icon: '💠', rmb: 98, diamond: 1080, desc: '额外赠送100钻石', bonus: '赠100' },
    { id: 'p198', cat: 'diamond', name: '上将补给', icon: '💎', rmb: 198, diamond: 2230, desc: '额外赠送250钻石', bonus: '赠250' },
    { id: 'p328', cat: 'diamond', name: '大将补给', icon: '💎', rmb: 328, diamond: 3780, desc: '额外赠送500钻石', bonus: '赠500' },
    { id: 'p648', cat: 'diamond', name: '统帅补给', icon: '💎', rmb: 648, diamond: 7680, desc: '额外赠送1200钻石', bonus: '赠1200' },
    { id: 'p1280', cat: 'marshal', name: '元帅礼包', icon: '🎖️', rmb: 1280, diamond: 15800, desc: '钻石、资源与稀有道具组合礼包', bonus: '豪华' },
    { id: 'mk30', cat: 'monthly', name: '钻石月卡', icon: '💳', rmb: 30, diamond: 300, desc: '立即获得300钻石，持续领取月卡福利' },
    { id: 'wk98', cat: 'monthly', name: '战备月卡', icon: '🎖️', rmb: 98, diamond: 980, desc: '立即获得980钻石，享受战备补给特权' }
  ];

  var Recharge = {
    curTab: 'diamond',
    selChannel: '模拟支付',
    selected: null,

    setTab: function (tab) {
      this.curTab = tab;
      this.selected = null;
      Core.render();
    },

    selectChannel: function (id) {
      this.selChannel = id;
      Core.render();
    },

    selectPackage: function (id) {
      this.selected = id;
      Core.render();
    },

    confirmPay: function (pkgId) {
      var id = pkgId || this.selected;
      var pkg = null;
      for (var i = 0; i < PACKAGES.length; i++) {
        if (PACKAGES[i].id === id) { pkg = PACKAGES[i]; break; }
      }
      if (!pkg) { G.toast('请先选择充值档位'); return; }
      G.API.shopRecharge(pkg.id, pkg.rmb, pkg.diamond, this.selChannel).then(function (resp) {
        if (!resp || resp.success === false) {
          G.toast((resp && resp.message) || '充值失败');
          return;
        }
        Recharge.selected = null;
        G.toast('模拟充值成功，' + pkg.name + '已到账 +' + resp.diamonds + '钻石');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '充值失败');
      });
    },

    renderView: function (v) {
      if (G.Protection && (!G.Protection.data || G.Protection.data.enabled !== false)) {
        v.innerHTML = '<div class="title">充值服务未开放</div><div class="panel">当前未开放真实支付或模拟充值。你可继续使用正常玩法获得的资源。</div>';
        return;
      }
      var self = this;
      var h = '<div class="title">- 战时补给站 -</div>';
      h += '<div class="rech-empty">模拟支付环境，点击确认后钻石立即到账。</div>';
      h += '<div class="rech-tabs">';
      for (var i = 0; i < CATEGORIES.length; i++) {
        var cat = CATEGORIES[i];
        h += '<span class="rech-tab' + (cat.id === self.curTab ? ' active' : '') + '" onclick="Game.Recharge.setTab(\'' + cat.id + '\')">' + cat.name + '</span>';
      }
      h += '</div>';
      h += '<div class="rech-channels">';
      h += '<span class="rech-channel active">模拟支付</span>';
      h += '</div>';
      h += '<div class="rech-grid' + (self.curTab === 'monthly' ? ' monthly' : '') + '">';
      for (var j = 0; j < PACKAGES.length; j++) {
        var p = PACKAGES[j];
        if (p.cat !== self.curTab) continue;
        var selected = p.id === self.selected;
        h += '<div class="rech-card' + (selected ? ' selected' : '') + '" onclick="Game.Recharge.selectPackage(\'' + p.id + '\')">';
        h += '<div class="rech-card-head"><span class="rech-card-icon">' + p.icon + '</span>';
        if (p.bonus) h += '<span class="rech-tag gold">' + p.bonus + '</span>';
        h += '</div>';
        h += '<div class="rech-card-name">' + p.name + '</div>';
        h += '<div class="rech-card-price">¥' + p.rmb + '</div>';
        h += '<div class="rech-card-amount">💎 ' + p.diamond + '</div>';
        h += '<div class="rech-card-desc">' + p.desc + '</div>';
        h += '</div>';
      }
      h += '</div>';
      h += '<div class="rech-pay-bar">';
      h += '<button class="rech-pay-btn" onclick="Game.Recharge.confirmPay()">' + (self.selected ? '确认充值' : '请选择充值档位') + '</button>';
      h += '<span class="rech-pay-tip">当前为模拟支付，不会产生真实扣款</span>';
      h += '</div>';
      h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
      v.innerHTML = h;
    }
  };

  G.Recharge = Recharge;
  Core.views.recharge = function (v) { Recharge.renderView(v); };
})(window.Game);
