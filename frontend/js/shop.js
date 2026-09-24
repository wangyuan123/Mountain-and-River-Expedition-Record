/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var Core = G.Core;

  // 商城商品数据：基于游戏仓库道具 & 军事战争题材扩展
  var SHOP_CATS = G.Constants.shopCategories;

  // 指定技能书与 data.js 的技能表保持同步，旧 supply 仅为兼容别名，不单独上架。
  var SPECIFIC_SKILL_BOOKS = Object.keys(G.DATA.officerSkills).filter(function (skillId) {
    return skillId !== 'supply';
  }).map(function (skillId) {
    var skill = G.DATA.officerSkills[skillId];
    return {
      id: 'skillBook_' + skillId,
      cat: 'officer',
      name: skill.name + '技能书',
      icon: '📗',
      desc: '选择军官使用，直接学习「' + skill.name + '」Lv.1',
      price: 160,
      stock: null,
      tag: '指定'
    };
  });

  var SHOP_ITEMS = G.Constants.shopItems.concat(SPECIFIC_SKILL_BOOKS);

  var Shop = {
    curCat: 'all',

    items: function () {
      var self = this;
      if (self.curCat === 'all') return SHOP_ITEMS;
      return SHOP_ITEMS.filter(function (it) { return it.cat === self.curCat; });
    },

    setCat: function (cat) {
      this.curCat = cat;
      Core.render();
    },

    buy: function (id) {
      var it = null;
      for (var i = 0; i < SHOP_ITEMS.length; i++) {
        if (SHOP_ITEMS[i].id === id) { it = SHOP_ITEMS[i]; break; }
      }
      if (!it) return;
      var s = Core.state;
      var r = s.resources || (s.resources = {});
      var diamond = r.diamond != null ? r.diamond : 0;
      if (diamond < it.price) {
        G.toast('钻石不足,无法购买');
        return;
      }
      // 调用后端 API 持久化购买
      G.API.shopBuy(id).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '购买失败');
          return;
        }
        if (it.cat === 'gift') {
          G.toast('已购买 ' + it.icon + ' ' + it.name + ',请在邮件中查收');
        } else {
          G.toast('已购买 ' + it.icon + ' ' + it.name + ' ×1');
        }
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '购买失败');
      });
    },

    renderView: function (v) {
      var s = Core.state;
      var r = s.resources || {};
      var diamond = r.diamond != null ? r.diamond : 0;
      var list = this.items();
      var self = this;

      var h = '';
      h += '<div class="title">- 军需商城 -</div>';

      // 顶部信息条
      h += '<div class="shop-balance">';
      h += '<div class="bal-item"><span class="bal-label">我的钻石</span><span class="bal-value">💎 ' + diamond + '</span></div>';
      h += '<div class="bal-item"><span class="bal-label">充值</span><span class="bal-action" onclick="Game.go(\'recharge\')">前往充值</span></div>';
      h += '</div>';

      // 分类标签
      h += '<div class="shop-tabs">';
      for (var i = 0; i < SHOP_CATS.length; i++) {
        var c = SHOP_CATS[i];
        var cls = 'shop-tab' + (c.id === this.curCat ? ' active' : '');
        h += '<span class="' + cls + '" data-cat="' + c.id + '" onclick="Game.Shop.setCat(\'' + c.id + '\')">' + c.name + '</span>';
      }
      h += '</div>';

      // 商品网格
      h += '<div class="shop-grid">';
      for (var j = 0; j < list.length; j++) {
        var it = list[j];
        var have = (s.items && s.items[it.id]) || 0;
        var canBuy = diamond >= it.price;
        var tagHtml = it.tag ? '<span class="shop-tag">' + it.tag + '</span>' : '';
        var stockHtml = '';
        if (it.stock != null) {
          stockHtml = '<span class="shop-stock">限购 ' + it.stock + '</span>';
        } else if (it.cat === 'gift' || it.cat === 'officer' || it.cat === 'res' || it.cat === 'resource' || it.cat === 'util' || it.cat === 'jewelry') {
          stockHtml = '<span class="shop-stock">无限购</span>';
        }
        h += '<div class="shop-card">';
        h += '<div class="shop-card-head">' + tagHtml + '<div class="shop-icon">' + it.icon + '</div></div>';
        h += '<div class="shop-card-name">' + it.name + '</div>';
        h += '<div class="shop-card-desc">' + it.desc + '</div>';
        h += '<div class="shop-card-foot">';
        h += '<span class="shop-price">💎 ' + it.price + '</span>';
        h += '<button class="shop-buy' + (canBuy ? '' : ' disabled') + '"' + (canBuy ? '' : ' disabled') + ' onclick="Game.Shop.buy(\'' + it.id + '\')">购买</button>';
        h += '</div>';
        h += stockHtml ? '<div class="shop-card-stock">' + stockHtml + '</div>' : '';
        h += '</div>';
      }
      h += '</div>';

      h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
      v.innerHTML = h;
    }
  };

  G.Shop = Shop;
  Core.views.shop = function (v) { Shop.renderView(v); };
})(window.Game);
