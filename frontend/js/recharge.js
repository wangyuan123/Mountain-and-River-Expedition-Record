/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  /** 兼容缓存页面中的旧充值操作，所有操作只提示未开放。 */
  function unavailable() {
    G.toast(G.Constants.rechargeUnavailableMessage);
  }

  G.Recharge = {
    setTab: unavailable,
    selectChannel: unavailable,
    selectPackage: unavailable,
    closeReceipt: unavailable,

    /** 旧页面即使直接确认充值，也不调用接口、不生成到账凭证。 */
    confirmPay: function () {
      unavailable();
      return Promise.resolve({ success: false, code: 'PAYMENT_DISABLED', message: G.Constants.rechargeUnavailableMessage });
    },

    /** 防止旧路由状态或直接渲染恢复充值表单。 */
    renderView: function (view) {
      view.innerHTML = '<div class="title">暂未开放充值</div><div class="panel" role="status">' + G.Constants.rechargeUnavailableMessage + '</div>';
    }
  };

  G.Core.views.recharge = function (view) { G.Recharge.renderView(view); };
})(window.Game);
