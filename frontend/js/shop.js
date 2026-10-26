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

  /** 新手礼包开启后仍保留零库存键，账号刷新或换城市不会重新获得限购资格。 */
  function alreadyPurchased(item) {
    return item.id === 'newbiePack' && Object.prototype.hasOwnProperty.call(Core.state.items || {}, item.id);
  }

  /** 计算加速符与护盾的折合小时单价与立省比例，直观呈现性价比。 */
  function itemEfficiencyText(it) {
    if (!it || !it.id) return '';
    var speedMatch = it.id.match(/^speedUp(\d+)(m|h)$/);
    if (speedMatch) {
      var num = parseInt(speedMatch[1], 10);
      var unit = speedMatch[2];
      var hours = unit === 'm' ? num / 60 : num;
      var rate = (it.price / hours).toFixed(1).replace(/\.0$/, '');
      if (hours >= 5) {
        var baseRate = 100;
        var savePct = Math.round((1 - (it.price / hours) / baseRate) * 100);
        return '折合 ' + rate + '钻/时 · 省' + savePct + '%';
      }
      return '折合 ' + rate + '钻/时';
    }
    var shieldMatch = it.id.match(/^shield(\d+)?h?$/);
    if (shieldMatch) {
      var sHours = it.id === 'shield' ? 8 : parseInt(shieldMatch[1], 10);
      if (sHours) {
        var sRate = (it.price / sHours).toFixed(1).replace(/\.0$/, '');
        if (sHours >= 8) {
          var sBaseRate = 30;
          var sSavePct = Math.round((1 - (it.price / sHours) / sBaseRate) * 100);
          return '折合 ' + sRate + '钻/时 · 省' + sSavePct + '%';
        }
        return '折合 ' + sRate + '钻/时';
      }
    }
    return '';
  }

  var Shop = {
    curCat: 'all',
    subCat: 'all',
    keyword: '',
    equipmentTier: '',
    equipmentBranch: '',

    filterEquipment: function (key, value) { this[key] = value; Core.render(); },

    setCat: function (cat) {
      this.curCat = cat;
      this.subCat = 'all';
      this.equipmentTier = '';
      this.equipmentBranch = '';
      Core.render();
    },

    setSubCat: function (sub) {
      this.subCat = sub;
      Core.render();
    },

    setKeyword: function (kw) {
      this.keyword = kw || '';
      Core.render();
    },

    items: function () {
      var self = this;
      var kw = (self.keyword || '').trim().toLowerCase();
      return SHOP_ITEMS.filter(function (it) {
        if (self.curCat !== 'all' && it.cat !== self.curCat) return false;

        // 军官二级分类筛选
        if (self.curCat === 'officer' && self.subCat && self.subCat !== 'all') {
          if (self.subCat === 'gear' && !it.equipmentSet) return false;
          if (self.subCat === 'skill' && !it.id.startsWith('skillBook') && it.id !== 'universalSkillBook') return false;
          if (self.subCat === 'progression' && (it.equipmentSet || it.id.startsWith('skillBook') || it.id === 'universalSkillBook')) return false;
        }

        // 套装装备筛选
        if (it.equipmentSet) {
          var set = G.Constants.equipmentSets[it.equipmentSet];
          if (self.equipmentTier && self.equipmentTier !== set.tier) return false;
          if (self.equipmentBranch && self.equipmentBranch !== set.branch) return false;
        } else if (self.equipmentTier || self.equipmentBranch) {
          return false;
        }

        // 关键字模糊过滤（匹配商品名、描述、标签）
        if (kw) {
          var nameMatch = it.name && it.name.toLowerCase().indexOf(kw) !== -1;
          var descMatch = it.desc && it.desc.toLowerCase().indexOf(kw) !== -1;
          var tagMatch = it.tag && it.tag.toLowerCase().indexOf(kw) !== -1;
          if (!nameMatch && !descMatch && !tagMatch) return false;
        }

        return true;
      });
    },

    promptRecharge: function (itemName, price, diamond) {
      var mask = document.createElement('div');
      mask.className = 'modal-mask confirm-dialog-mask';
      mask.innerHTML = '<div class="modal-card confirm-dialog" role="dialog" aria-modal="true">' +
        '<div class="modal-title">💎 钻石不足</div>' +
        '<div class="modal-body confirm-dialog-message">' +
          '<p>购买【' + itemName + '】需要 <b>💎 ' + price + '</b>，您当前拥有 <b>💎 ' + diamond + '</b>。</p>' +
          '<p class="confirm-dialog-submessage">是否前往充值获取更多钻石？</p>' +
        '</div>' +
        '<div class="modal-foot">' +
          '<button type="button" class="btn" id="shopRechargeCancel">暂不充值</button>' +
          '<button type="button" class="btn ok" id="shopRechargeGo">前往充值</button>' +
        '</div></div>';
      var close = function () { mask.remove(); };
      var cancel = mask.querySelector('#shopRechargeCancel');
      if (cancel) cancel.onclick = close;
      var go = mask.querySelector('#shopRechargeGo');
      if (go) {
        go.onclick = function () {
          close();
          Game.go('recharge');
        };
      }
      mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
      document.body.appendChild(mask);
    },

    openBuyModal: function (id) {
      if (typeof document === 'undefined' || typeof document.createElement !== 'function' || !document.body || typeof document.querySelectorAll !== 'function') {
        this.buy(id, 1);
        return;
      }
      var it = null;
      for (var i = 0; i < SHOP_ITEMS.length; i++) {
        if (SHOP_ITEMS[i].id === id) { it = SHOP_ITEMS[i]; break; }
      }
      if (!it) return;
      if (alreadyPurchased(it)) {
        G.toast('新手礼包每个账号限购1份');
        return;
      }
      var s = Core.state;
      var r = s.resources || {};
      var diamond = r.diamond != null ? r.diamond : 0;
      if (diamond < it.price) {
        this.promptRecharge(it.name, it.price, diamond);
        return;
      }

      var existingModal = document.getElementById('shopBuyModal');
      if (existingModal) existingModal.remove();

      var owned = (s.items && s.items[it.id]) || 0;
      var maxAffordable = Math.min(999, Math.max(1, Math.floor(diamond / it.price)));
      var count = 1;
      var isOneOff = (it.id === 'newbiePack' || (it.stock && it.stock === 1));

      var mask = document.createElement('div');
      mask.id = 'shopBuyModal';
      mask.className = 'modal-mask shop-buy-mask';

      var renderModalContent = function () {
        var totalCost = it.price * count;
        var canAfford = diamond >= totalCost;
        var isHighValue = totalCost >= 500;

        var html = '<div class="modal-card shop-buy-dialog" role="dialog" aria-modal="true">' +
          '<div class="modal-title"><span>🛒 购买军需道具</span><button type="button" class="modal-close-btn" id="shopModalCloseX">✕</button></div>' +
          '<div class="modal-body shop-buy-body">' +
            '<div class="shop-buy-item-row">' +
              '<div class="shop-buy-item-icon">' + G.iconHtml(it.icon, it.name) + '</div>' +
              '<div class="shop-buy-item-info">' +
                '<div class="shop-buy-item-name">' + it.name + (it.tag ? ' <span class="shop-tag">' + it.tag + '</span>' : '') + '</div>' +
                '<div class="shop-buy-item-price">单价：💎 ' + it.price + '</div>' +
                '<div class="shop-buy-item-owned">仓库已有：' + owned + ' 个</div>' +
              '</div>' +
            '</div>' +
            '<div class="shop-buy-item-desc">' + it.desc + '</div>';

        if (isHighValue) {
          html += '<div class="shop-high-value-warn">⚠️ <b>高额钻石消费确认</b>：本次购买将扣除 <b>💎 ' + totalCost + '</b> 且操作不可撤销，请核对确认。</div>';
        }

        if (!isOneOff) {
          html += '<div class="shop-stepper-wrap">' +
            '<span class="shop-stepper-label">购买数量：</span>' +
            '<div class="shop-stepper">' +
              '<button type="button" class="shop-step-btn" id="shopStepMinus">-</button>' +
              '<input type="number" class="shop-step-input" id="shopStepInput" min="1" max="' + maxAffordable + '" value="' + count + '">' +
              '<button type="button" class="shop-step-btn" id="shopStepPlus">+</button>' +
            '</div>' +
            '<div class="shop-quick-steppers">' +
              '<button type="button" class="shop-quick-btn" data-val="5">+5</button>' +
              '<button type="button" class="shop-quick-btn" data-val="10">+10</button>' +
              '<button type="button" class="shop-quick-btn" data-val="max">最大(' + maxAffordable + ')</button>' +
            '</div>' +
          '</div>';
        }

        html += '<div class="shop-buy-summary">' +
            '<div class="summary-line"><span>总计费用：</span><b class="total-diamond">💎 ' + totalCost + '</b></div>' +
            '<div class="summary-line"><span>当前拥有钻石：</span><span>💎 ' + diamond + '</span></div>' +
            '<div class="summary-line"><span>购买后剩余：</span><span>💎 ' + (canAfford ? (diamond - totalCost) : '不足') + '</span></div>' +
          '</div>' +
          '</div>' +
          '<div class="modal-foot">' +
            '<button type="button" class="btn" id="shopBuyCancel">取消</button>' +
            '<button type="button" class="btn ok' + (canAfford ? '' : ' disabled') + '" id="shopBuySubmit"' + (canAfford ? '' : ' disabled') + '>' + (canAfford ? '确认购买' : '钻石不足') + '</button>' +
          '</div></div>';

        mask.innerHTML = html;
        bindModalEvents();
      };

      var closeModal = function () {
        mask.remove();
      };

      var bindModalEvents = function () {
        var closeX = mask.querySelector('#shopModalCloseX');
        if (closeX) closeX.onclick = closeModal;
        var cancelBtn = mask.querySelector('#shopBuyCancel');
        if (cancelBtn) cancelBtn.onclick = closeModal;

        var minusBtn = mask.querySelector('#shopStepMinus');
        var plusBtn = mask.querySelector('#shopStepPlus');
        var input = mask.querySelector('#shopStepInput');

        if (minusBtn) {
          minusBtn.onclick = function () {
            if (count > 1) { count--; renderModalContent(); }
          };
        }
        if (plusBtn) {
          plusBtn.onclick = function () {
            if (count < maxAffordable) { count++; renderModalContent(); }
          };
        }
        if (input) {
          input.onchange = function () {
            var val = parseInt(input.value, 10);
            if (isNaN(val) || val < 1) val = 1;
            if (val > maxAffordable) val = maxAffordable;
            count = val;
            renderModalContent();
          };
        }

        var quickBtns = mask.querySelectorAll('.shop-quick-btn');
        for (var q = 0; q < quickBtns.length; q++) {
          quickBtns[q].onclick = function () {
            var val = this.getAttribute('data-val');
            if (val === 'max') {
              count = maxAffordable;
            } else {
              var add = parseInt(val, 10) || 0;
              count = Math.min(maxAffordable, count + add);
            }
            renderModalContent();
          };
        }

        var submitBtn = mask.querySelector('#shopBuySubmit');
        if (submitBtn) {
          submitBtn.onclick = function () {
            closeModal();
            Shop.buy(it.id, count);
          };
        }
      };

      mask.addEventListener('click', function (e) {
        if (e.target === mask) closeModal();
      });
      mask.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') closeModal();
      });

      document.body.appendChild(mask);
      renderModalContent();
    },

    buy: function (id, count) {
      var it = null;
      for (var i = 0; i < SHOP_ITEMS.length; i++) {
        if (SHOP_ITEMS[i].id === id) { it = SHOP_ITEMS[i]; break; }
      }
      if (!it) return;
      if (alreadyPurchased(it)) {
        G.toast('新手礼包每个账号限购1份');
        return;
      }

      var buyCount = (count && count > 1) ? parseInt(count, 10) : 1;
      var s = Core.state;
      var r = s.resources || (s.resources = {});
      var diamond = r.diamond != null ? r.diamond : 0;
      var totalCost = it.price * buyCount;
      if (diamond < totalCost) {
        if (typeof document !== 'undefined' && typeof document.createElement === 'function' && document.body) {
          this.promptRecharge(it.name, totalCost, diamond);
        } else {
          G.toast('钻石不足,无法购买');
        }
        return;
      }

      // 调用后端 API 持久化购买
      G.API.shopBuy(id, buyCount).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '购买失败');
          return;
        }
        if (it.cat === 'gift') {
          G.toast('已购买 ' + it.name + ',请在仓库的资源道具中开启');
        } else if (buyCount > 1) {
          G.toast('已购买 ' + it.name + ' ×' + buyCount);
        } else {
          G.toast('已购买 ' + it.name + ' ×1');
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

      // 搜索框
      h += '<div class="shop-filter-bar">';
      h += '<input type="text" class="shop-search-input" placeholder="🔍 搜索商品名称或效果..." value="' + (this.keyword ? this.keyword.replace(/"/g, '&quot;') : '') + '" oninput="Game.Shop.setKeyword(this.value)">';
      if (this.keyword) {
        h += '<button type="button" class="shop-clear-search" onclick="Game.Shop.setKeyword(\'\')">✕ 清空</button>';
      }
      h += '</div>';

      // 军官二级分类标签（按设计文档：全部、套装装备箱、技能秘籍、军官进阶）
      if (this.curCat === 'officer') {
        var subCats = [
          { id: 'all', name: '全部' },
          { id: 'gear', name: '套装装备箱' },
          { id: 'skill', name: '技能秘籍' },
          { id: 'progression', name: '军官进阶' }
        ];
        h += '<div class="shop-subtabs">';
        for (var sc = 0; sc < subCats.length; sc++) {
          var scItem = subCats[sc];
          var scCls = 'shop-subtab' + (scItem.id === (this.subCat || 'all') ? ' active' : '');
          h += '<span class="' + scCls + '" onclick="Game.Shop.setSubCat(\'' + scItem.id + '\')">' + scItem.name + '</span>';
        }
        h += '</div>';
      }

      // 套装档次与分支下拉（在 all 分类，或 officer 分类且子分类为 all/gear 时展示）
      if (this.curCat === 'all' || (this.curCat === 'officer' && (this.subCat === 'all' || this.subCat === 'gear'))) {
        h += '<label>套装档次 <select onchange="Game.Shop.filterEquipment(\'equipmentTier\',this.value)"><option value="">全部档次</option>';
        Object.keys(G.Constants.equipmentTiers).forEach(function (key) { h += '<option value="' + key + '"' + (self.equipmentTier === key ? ' selected' : '') + '>' + G.Constants.equipmentTiers[key].name + '</option>'; });
        h += '</select></label><label>套装分支 <select onchange="Game.Shop.filterEquipment(\'equipmentBranch\',this.value)"><option value="">全部分支</option>';
        Object.keys(G.Constants.equipmentBranches).forEach(function (key) { h += '<option value="' + key + '"' + (self.equipmentBranch === key ? ' selected' : '') + '>' + G.Constants.equipmentBranches[key] + '</option>'; });
        h += '</select></label>';
      }

      // 商品网格
      h += '<div class="shop-grid">';
      if (list.length === 0) {
        h += '<div class="shop-empty-tip">未找到匹配的商品</div>';
      }
      for (var j = 0; j < list.length; j++) {
        var it = list[j];
        var purchased = alreadyPurchased(it);
        var canBuy = diamond >= it.price && !purchased;
        var tagHtml = it.tag ? '<span class="shop-tag">' + it.tag + '</span>' : '';
        var stockHtml = '';
        if (it.stock != null) {
          stockHtml = '<span class="shop-stock">每个账号限购 ' + it.stock + ' 份' + (purchased ? ' · 已购买' : '') + '</span>';
        } else if (it.cat === 'gift' || it.cat === 'officer' || it.cat === 'res' || it.cat === 'resource' || it.cat === 'util') {
          stockHtml = '<span class="shop-stock">无限购</span>';
        }
        var owned = (s.items && s.items[it.id]) || 0;
        var eff = itemEfficiencyText(it);
        var effHtml = eff ? '<div class="shop-efficiency">' + eff + '</div>' : '';

        h += '<div class="shop-card">';
        h += '<div class="shop-card-head">' + tagHtml + '<div class="shop-icon">' + G.iconHtml(it.icon, it.name) + '</div></div>';
        h += '<div class="shop-card-name">' + it.name + '</div>';
        h += '<div class="shop-card-desc">' + it.desc + '</div>';
        h += effHtml;
        h += '<div class="shop-card-owned"><span class="shop-owned">已有: ' + owned + '</span></div>';
        if (it.equipmentSet) {
          var set = G.Constants.equipmentSets[it.equipmentSet];
          h += '<details><summary>查看十二件清单与共鸣</summary>';
          set.keys.forEach(function (key) { var d = G.Constants.equipmentItems[key]; h += '<p>' + d.name + ' ×1 · ' + d.desc + '</p>'; });
          for (var stage = 0; stage < 4; stage++) h += '<p>' + ((stage + 1) * 3) + '件追加：' + G.Constants.equipmentBranches[set.branch] + '+' + set.config.resonance_increment_main[stage] + '，全属性+' + set.config.resonance_increment_all[stage] + '</p>';
          h += '</details>';
        }
        h += '<div class="shop-card-foot">';
        h += '<span class="shop-price">💎 ' + it.price + '</span>';
        var buyAction = it.id === 'newbiePack' ? 'Game.Shop.buy(\'newbiePack\')' : 'Game.Shop.openBuyModal(\'' + it.id + '\')';
        h += '<button class="shop-buy' + (canBuy ? '' : ' disabled') + '"' + (canBuy ? '' : ' disabled') + ' onclick="' + buyAction + '">' + (purchased ? '已购买' : '购买') + '</button>';
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
