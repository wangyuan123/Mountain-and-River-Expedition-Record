/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var Core = G.Core;

  // 商城商品数据：基于游戏仓库道具 & 军事战争题材扩展
  var SHOP_CATS = [
    { id: 'all',     name: '全部' },
    { id: 'officer', name: '军官' },
    { id: 'resource',name: '资源' },
    { id: 'util',    name: '功能' },
    { id: 'gift',    name: '礼包' }
  ];

  var SHOP_ITEMS = [
    // —— 军官道具 ——
    { id: 'expBook',    cat: 'officer',  name: '经验书',     icon: '📘', desc: '军官使用,获得500经验',           price: 30,   stock: null, tag: '热销' },
    { id: 'expBookAdv', cat: 'officer',  name: '高级经验书', icon: '📕', desc: '军官使用,获得3000经验',          price: 150,  stock: null, tag: '' },
    { id: 'skillBook',  cat: 'officer',  name: '技能书',     icon: '📗', desc: '为军官学习新技能',               price: 80,   stock: null, tag: '' },
    { id: 'loyaltyBox', cat: 'officer',  name: '忠诚宝箱',   icon: '🎁', desc: '军官忠诚度+20,提升留任意愿',     price: 50,   stock: null, tag: '' },
    { id: 'renameCard', cat: 'officer',  name: '改名卡',     icon: '🏷️', desc: '为军官更换新名字',               price: 60,   stock: null, tag: '' },
    { id: 'recruitOrd', cat: 'officer',  name: '征募令',     icon: '🎖️', desc: '刷新军校,保底出现一名五星军官',   price: 500,  stock: 3,    tag: '稀有' },
    { id: 'starUp',     cat: 'officer',  name: '星耀符',     icon: '✨', desc: '军官升星,属性大幅成长',          price: 300,  stock: null, tag: '' },

    // —— 军官装备宝箱（整套装备，打开直接获得3件装备并激活套装属性）——
    { id: 'box_recruit_military',  cat: 'officer', name: '列兵军事装备箱', icon: '📦', desc: '开启获得整套列兵军事装备(军刀/臂章/作训服)，激活军事+3', price: 200,  stock: null, tag: '低级套装' },
    { id: 'box_recruit_logistics', cat: 'officer', name: '列兵后勤装备箱', icon: '📦', desc: '开启获得整套列兵后勤装备(工具包/通行证/工作服)，激活后勤+3', price: 200,  stock: null, tag: '低级套装' },
    { id: 'box_recruit_knowledge', cat: 'officer', name: '列兵学识装备箱', icon: '📦', desc: '开启获得整套列兵学识装备(笔记本/学员章/学员服)，激活学识+3', price: 200,  stock: null, tag: '低级套装' },
    { id: 'box_officer_military',  cat: 'officer', name: '校官军事装备箱', icon: '🎁', desc: '开启获得整套校官军事装备(军刀/勋章/军服)，激活军事+15',     price: 1200, stock: null, tag: '中级套装' },
    { id: 'box_officer_logistics', cat: 'officer', name: '校官后勤装备箱', icon: '🎁', desc: '开启获得整套校官后勤装备(补给箱/调度章/军需服)，激活后勤+15', price: 1200, stock: null, tag: '中级套装' },
    { id: 'box_officer_knowledge', cat: 'officer', name: '校官学识装备箱', icon: '🎁', desc: '开启获得整套校官学识装备(战术罗盘/参谋章/参谋服)，激活学识+15', price: 1200, stock: null, tag: '中级套装' },
    { id: 'box_marshal_military',  cat: 'officer', name: '元帅军事装备箱', icon: '👑', desc: '开启获得整套元帅军事装备(佩剑/将星/礼服)，激活军事+30+全属性+5', price: 5000, stock: null, tag: '满级套装' },
    { id: 'box_marshal_logistics', cat: 'officer', name: '元帅后勤装备箱', icon: '👑', desc: '开启获得整套元帅后勤装备(辎重车/军需印/长袍)，激活后勤+30+全属性+5', price: 5000, stock: null, tag: '满级套装' },
    { id: 'box_marshal_knowledge', cat: 'officer', name: '元帅学识装备箱', icon: '👑', desc: '开启获得整套元帅学识装备(望远镜/军师印/军礼服)，激活学识+30+全属性+5', price: 5000, stock: null, tag: '满级套装' },

    // —— 资源道具 ——
    { id: 'goldBox',    cat: 'resource', name: '黄金箱',     icon: '🪙', desc: '开启获得1000~5000黄金',        price: 80,   stock: null, tag: '' },
    { id: 'resBox',     cat: 'resource', name: '资源箱',     icon: '📦', desc: '开启获得粮钢油稀各500',        price: 120,  stock: null, tag: '热销' },
    { id: 'steelPack',  cat: 'resource', name: '钢铁大礼包', icon: '🔩', desc: '立即获得20000钢铁',            price: 200,  stock: null, tag: '' },
    { id: 'supplyPack', cat: 'resource', name: '战备补给包', icon: '🌾', desc: '粮钢油稀各8000,适合长期发展',  price: 350,  stock: null, tag: '超值' },
    { id: 'resourcePack500w', cat: 'resource', name: '资源大礼包', icon: '🎁', desc: '粮食/钢铁/石油/稀矿各500万', price: 5000, stock: null, tag: '豪华' },

    // —— 功能道具 - 加速符（建筑施工 / 军队生产通用）——
    { id: 'speedUp10m', cat: 'util', name: '10分加速符',icon: '⚡', desc: '立即缩短10分钟建筑/造兵时间',     price: 30,   stock: null, tag: '' },
    { id: 'speedUp1h',  cat: 'util', name: '1时加速符', icon: '⚡', desc: '立即缩短1小时建筑/造兵时间',     price: 100,  stock: null, tag: '热销' },
    { id: 'speedUp5h',  cat: 'util', name: '5时加速符', icon: '⚡', desc: '立即缩短5小时建筑/造兵时间',     price: 400,  stock: null, tag: '' },
    { id: 'speedUp12h', cat: 'util', name: '12时加速符',icon: '⚡', desc: '立即缩短12小时建筑/造兵时间',    price: 800,  stock: null, tag: '' },
    { id: 'speedUp24h', cat: 'util', name: '24时加速符',icon: '⚡', desc: '立即缩短24小时建筑/造兵时间',    price: 1500, stock: null, tag: '推荐' },
    { id: 'speedUp36h', cat: 'util', name: '36时加速符',icon: '⚡', desc: '立即缩短36小时建筑/造兵时间',    price: 2000, stock: null, tag: '' },
    { id: 'speedUp48h', cat: 'util', name: '48时加速符',icon: '⚡', desc: '立即缩短48小时建筑/造兵时间',    price: 2500, stock: null, tag: '超值' },
    { id: 'speedUp72h', cat: 'util', name: '72时加速符',icon: '⚡', desc: '立即缩短72小时建筑/造兵时间',    price: 3500, stock: null, tag: '限时' },
    { id: 'shield',    cat: 'util', name: '护盾',     icon: '🛡️', desc: '使用后8小时免受玩家攻击',         price: 200,  stock: null, tag: '' },
    { id: 'marchOrd',  cat: 'util', name: '行军令',   icon: '🚩', desc: '行军速度+50%,持续1小时',          price: 100,  stock: null, tag: '' },
    { id: 'cloak',     cat: 'util', name: '反侦察符', icon: '🕶️', desc: '降低被敌方侦察成功率,持续6小时',  price: 80,   stock: null, tag: '' },
    { id: 'populationOrder', cat: 'util', name: '人口动员令', icon: '👥', desc: '使用后立即增加500空闲人口,不超过人口上限', price: 100, stock: null, tag: '推荐' },

    // —— 礼包 ——
    { id: 'newbiePack', cat: 'gift', name: '新手礼包',   icon: '🎁', desc: '开7倍:粮20000/钢20000/油10000/稀5000/金3000', price: 99,   stock: 1, tag: '限时' },
    { id: 'monthCard',  cat: 'gift', name: '钻石月卡',   icon: '💳', desc: '立即得300钻,30天内每日登录送100钻',           price: 1500, stock: 1, tag: '推荐' },
    { id: 'warChest',   cat: 'gift', name: '战备月卡',   icon: '🎖️', desc: '立即得500钻+15个常用道具组合',                price: 888,  stock: 1, tag: '超值' },
    { id: 'annivPack',  cat: 'gift', name: '周年庆大礼', icon: '🎉', desc: '钻石×2000 + 道具×20 + 限定头衔',              price: 1999, stock: 1, tag: '限定' }
  ];

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
        } else if (it.cat === 'gift' || it.cat === 'officer' || it.cat === 'res' || it.cat === 'util') {
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
