/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var Core = G.Core;

  // 商城商品数据：基于游戏仓库道具 & 军事战争题材扩展
  var SHOP_CATS = G.Constants.shopCategories;

  /** 商品目录在认证快照安装后读取，避免启动时缓存空装备目录或旧价格。 */
  function shopItems() {
    var prices = G.ServerRules.value('catalog', {}).shopPrices || {};
    var ranks = G.DATA.militaryRanks.filter(function (rank) { return rank.tier > 1; }).map(function (rank) {
      var id = 'rank_certificate_' + rank.tier, item = G.DATA.items[id] || {};
      return { id: id, cat: 'rank', name: item.name, icon: item.icon, desc: item.desc,
        price: prices[id], rankTier: rank.tier, stock: null, tag: '晋升' };
    });
    return G.Constants.shopItems.concat(ranks).filter(function (item) { return prices[item.id] != null; });
  }

  function esc(s) {
    if (typeof G.escapeHtml === 'function') return G.escapeHtml(s);
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  /** 新手礼包开启后仍保留零库存键，账号刷新或换城市不会重新获得限购资格。 */
  function alreadyPurchased(item) {
    return (G.ServerRules.value('shop', {})[item.id] || {}).purchased === true;
  }

  /** 已授衔或已有库存的凭证不可再购；最终校验由服务端账号锁内完成。 */
  function certificateUnavailableReason(item) {
    return (G.ServerRules.value('shop', {})[item.id] || {}).certificateReason || '';
  }

  /** 性价比文案由服务端目录统一生成。 */
  function itemEfficiencyText(item) {
    return (G.ServerRules.value('catalog', {}).shopEfficiency || {})[item.id] || '';
  }

  var Shop = {
    curCat: 'all',
    subCat: 'all',
    keyword: '',
    equipmentTier: '',
    equipmentBranch: '',
    openDropdown: null,

    filterEquipment: function (key, value) { this[key] = value; Core.render(); },

    selectEquipmentFilter: function (key, value) {
      this.openDropdown = null;
      this.filterEquipment(key, value);
    },

    clearEquipmentFilters: function () {
      this.openDropdown = null;
      this.equipmentTier = '';
      this.equipmentBranch = '';
      Core.render();
    },

    toggleDropdown: function (type, event) {
      if (event) {
        if (event.stopPropagation) event.stopPropagation();
        if (event.preventDefault) event.preventDefault();
      }
      this.openDropdown = this.openDropdown === type ? null : type;
      this.updateDropdowns();
    },

    closeDropdowns: function () {
      if (!this.openDropdown) return;
      this.openDropdown = null;
      this.updateDropdowns();
    },

    updateDropdowns: function () {
      var self = this;
      if (typeof document === 'undefined') return;
      ['tier', 'branch'].forEach(function (type) {
        var wrap = document.getElementById('shopEquipDropdownWrap_' + type);
        var menu = document.getElementById('shopEquipDropdownMenu_' + type);
        var trigger = document.getElementById('shopEquipDropdownTrigger_' + type);
        var isOpen = (self.openDropdown === type);
        if (wrap && wrap.classList) wrap.classList.toggle('open', isOpen);
        if (menu) {
          if (isOpen) {
            menu.hidden = false;
            if (typeof menu.removeAttribute === 'function') menu.removeAttribute('hidden');
          } else {
            menu.hidden = true;
            if (typeof menu.setAttribute === 'function') menu.setAttribute('hidden', '');
          }
        }
        if (trigger && typeof trigger.setAttribute === 'function') {
          trigger.setAttribute('aria-expanded', String(isOpen));
        }
      });
    },

    setCat: function (cat) {
      this.curCat = cat;
      this.subCat = 'all';
      this.equipmentTier = '';
      this.equipmentBranch = '';
      Core.render();
    },

    /** 从晋升条件直达对应凭证，不沿用之前的商城搜索或筛选条件。 */
    openRankCertificate: function (tier) {
      var item = shopItems().find(function (entry) { return entry.rankTier === tier; });
      if (!item) return;
      this.curCat = 'rank';
      this.subCat = 'all';
      this.keyword = item.name;
      this.equipmentTier = '';
      this.equipmentBranch = '';
      G.go('shop');
    },

    setSubCat: function (sub) {
      this.subCat = sub;
      Core.render();
    },

    setKeyword: function (kw) {
      this.keyword = kw || '';
      Core.render();
    },

    /** 只读展示所选装备箱的固定内容，独立弹窗避免展开清单挤高商城卡片。 */
    showEquipmentDetails: function (id) {
      var item = shopItems().find(function (entry) { return entry.id === id && entry.equipmentSet; });
      var set = item && G.Constants.equipmentSets[item.equipmentSet];
      if (!set || typeof document === 'undefined' || !document.body) return;
      var existing = document.getElementById('shopEquipmentDetailModal');
      if (existing) existing.remove();
      var opener = document.activeElement;
      var mask = document.createElement('div');
      mask.id = 'shopEquipmentDetailModal';
      mask.className = 'modal-mask';
      var html = '<section class="modal-card" role="dialog" aria-modal="true" aria-labelledby="shopEquipmentDetailTitle">' +
        '<div class="modal-title"><span id="shopEquipmentDetailTitle">' + esc(item.name) + '详情</span>' +
          '<button type="button" class="modal-close-btn" id="shopEquipmentDetailCloseX" aria-label="关闭装备详情">✕</button></div>' +
        '<div class="modal-body"><p>' + esc(item.desc) + '</p><h3>十二件装备清单</h3><ol>';
      set.keys.forEach(function (key) {
        var equipment = G.Constants.equipmentItems[key];
        html += '<li><strong>' + esc(equipment.name) + ' ×1</strong><p>' + esc(equipment.desc) + '</p></li>';
      });
      html += '</ol><h3>套装共鸣</h3><p>同套装备达到对应件数时激活，各档效果累计叠加。</p>';
      for (var stage = 0; stage < 4; stage++) {
        html += '<p>' + ((stage + 1) * 3) + '件追加：' + esc(G.Constants.equipmentBranches[set.branch]) + '+' +
          set.config.resonance_increment_main[stage] + '，全属性+' + set.config.resonance_increment_all[stage] + '</p>';
      }
      mask.innerHTML = html + '</div><div class="modal-foot"><button type="button" class="btn" id="shopEquipmentDetailClose">关闭</button></div></section>';
      var close = function () {
        mask.remove();
        if (opener && opener.isConnected !== false && typeof opener.focus === 'function') opener.focus();
      };
      var closeX = mask.querySelector('#shopEquipmentDetailCloseX');
      var closeButton = mask.querySelector('#shopEquipmentDetailClose');
      closeX.onclick = close;
      closeButton.onclick = close;
      mask.addEventListener('click', function (event) { if (event.target === mask) close(); });
      mask.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') { event.preventDefault(); close(); }
        // 键盘焦点留在弹窗内，关闭后返回原详情入口，不落入遮罩后的购买按钮。
        if (event.key === 'Tab' && event.shiftKey && document.activeElement === closeX) {
          event.preventDefault(); closeButton.focus();
        } else if (event.key === 'Tab' && !event.shiftKey && document.activeElement === closeButton) {
          event.preventDefault(); closeX.focus();
        }
      });
      document.body.appendChild(mask);
      closeX.focus();
    },

    items: function () {
      var self = this;
      var kw = (self.keyword || '').trim().toLowerCase();
      return shopItems().filter(function (it) {
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

    /** 钻石不足时保留价格说明，不再引导至充值页面。 */
    promptRecharge: function (itemName, price, diamond) {
      G.toast('钻石不足，购买【' + itemName + '】需要 ' + price + ' 钻石，当前拥有 ' + diamond + '。' + G.Constants.rechargeUnavailableMessage);
    },

    openBuyModal: function (id) {
      if (typeof document === 'undefined' || typeof document.createElement !== 'function' || !document.body || typeof document.querySelectorAll !== 'function') {
        this.buy(id, 1);
        return;
      }
      var it = null;
      for (var i = 0; i < shopItems().length; i++) {
        if (shopItems()[i].id === id) { it = shopItems()[i]; break; }
      }
      if (!it) return;
      if (certificateUnavailableReason(it)) {
        G.toast(certificateUnavailableReason(it) + '，无需购买该晋升凭证');
        return;
      }
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
      var maxAffordable = Math.max(1, (G.ServerRules.value('shop', {})[it.id] || {}).maxAffordable || 0);
      var count = 1;
      var isOneOff = (it.rankTier || it.id === 'newbiePack' || (it.stock && it.stock === 1));

      var mask = document.createElement('div');
      mask.id = 'shopBuyModal';
      mask.className = 'modal-mask shop-buy-mask';

      var renderModalContent = function () {
        var quote = G.ServerRules.cachedPreview({ action: 'shop', id: it.id, count: count }, function () {
          if (mask.isConnected !== false) renderModalContent();
        });
        var totalCost = quote ? quote.cost : '…';
        var canAfford = quote && quote.allowed;
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
            '<div class="summary-line"><span>购买后剩余：</span><span>💎 ' + (quote ? quote.remaining : '待报价') + '</span></div>' +
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
      for (var i = 0; i < shopItems().length; i++) {
        if (shopItems()[i].id === id) { it = shopItems()[i]; break; }
      }
      if (!it) return;
      if (certificateUnavailableReason(it)) {
        G.toast(certificateUnavailableReason(it) + '，无需购买该晋升凭证');
        return;
      }
      if (it.rankTier && count != null && Number(count) !== 1) {
        G.toast('晋升凭证每次只能购买1张');
        return;
      }
      if (alreadyPurchased(it)) {
        G.toast('新手礼包每个账号限购1份');
        return;
      }

      var buyCount = (count && count > 1) ? parseInt(count, 10) : 1;
      var s = Core.state;
      var r = s.resources || (s.resources = {});
      var diamond = r.diamond != null ? r.diamond : 0;
      // 购买只提交商品与数量，余额、单价和整笔费用由后端事务重算。
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

      // 顶部信息条
      h += '<div class="shop-balance">';
      h += '<div class="bal-item"><span class="bal-label">我的钻石</span><span class="bal-value">💎 ' + diamond + '</span></div>';
      h += '<div class="bal-item"><span class="bal-label">充值</span><span class="bal-action" onclick="Game.go(\'recharge\')">暂未开放充值</span></div>';
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
      if (this.curCat === 'rank') {
        h += '<p>按需购买对应军衔凭证，每档最多保留1张未使用凭证；NPC不再掉落。购买不直接晋升，仍需声望、累计胜场和笔试达标。</p>';
      }
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
        var hasActiveEquipFilter = Boolean(self.equipmentTier || self.equipmentBranch);

        var tierOptions = [{ key: '', name: '全部档次' }];
        Object.keys(G.Constants.equipmentTiers).forEach(function (key) {
          tierOptions.push({ key: key, name: G.Constants.equipmentTiers[key].name });
        });

        var branchOptions = [{ key: '', name: '全部分支' }];
        Object.keys(G.Constants.equipmentBranches).forEach(function (key) {
          branchOptions.push({ key: key, name: G.Constants.equipmentBranches[key] });
        });

        var curTierName = '全部档次';
        for (var ti0 = 0; ti0 < tierOptions.length; ti0++) {
          if (tierOptions[ti0].key === self.equipmentTier) {
            curTierName = tierOptions[ti0].name;
            break;
          }
        }

        var curBranchName = '全部分支';
        for (var bi0 = 0; bi0 < branchOptions.length; bi0++) {
          if (branchOptions[bi0].key === self.equipmentBranch) {
            curBranchName = branchOptions[bi0].name;
            break;
          }
        }

        h += '<div class="shop-equip-filter-bar">';
        h += '<div class="shop-equip-filter-tag"><svg class="shop-equip-filter-tag-icon" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg><span>套装细分</span></div>';
        h += '<div class="shop-equip-filter-controls">';

        // 1. 套装档次自定义下拉组件
        h += '<div class="shop-equip-filter-item">';
        h += '<span class="shop-equip-filter-label">套装档次</span>';
        h += '<div class="shop-custom-dropdown' + (self.openDropdown === 'tier' ? ' open' : '') + '" id="shopEquipDropdownWrap_tier">';
        h += '<button type="button" class="shop-custom-dropdown-trigger' + (self.equipmentTier ? ' is-active' : '') + '" id="shopEquipDropdownTrigger_tier" aria-haspopup="listbox" aria-expanded="' + (self.openDropdown === 'tier') + '" onclick="Game.Shop.toggleDropdown(\'tier\', event)">';
        h += '<span class="shop-dropdown-text">' + esc(curTierName) + '</span>';
        h += '<span class="shop-dropdown-arrow" aria-hidden="true">▾</span>';
        h += '</button>';
        h += '<div class="shop-custom-dropdown-menu" id="shopEquipDropdownMenu_tier" role="listbox"' + (self.openDropdown === 'tier' ? '' : ' hidden') + '>';
        for (var ti = 0; ti < tierOptions.length; ti++) {
          var tItem = tierOptions[ti];
          var isTierSel = (self.equipmentTier === tItem.key);
          h += '<div class="shop-dropdown-item' + (isTierSel ? ' selected' : '') + '" role="option" aria-selected="' + isTierSel + '" onclick="Game.Shop.selectEquipmentFilter(\'equipmentTier\',\'' + tItem.key + '\')">';
          h += '<span>' + esc(tItem.name) + '</span>';
          if (isTierSel) h += '<span class="shop-dropdown-item-check">✓</span>';
          h += '</div>';
        }
        h += '</div>';
        // 隐藏的原生 select 供辅助交互与兼容支持
        h += '<select class="shop-equip-filter-select' + (self.equipmentTier ? ' is-active' : '') + '" style="display:none;" tabindex="-1" aria-hidden="true" onchange="Game.Shop.filterEquipment(\'equipmentTier\',this.value)">';
        for (var ts = 0; ts < tierOptions.length; ts++) {
          h += '<option value="' + tierOptions[ts].key + '"' + (self.equipmentTier === tierOptions[ts].key ? ' selected' : '') + '>' + tierOptions[ts].name + '</option>';
        }
        h += '</select>';
        h += '</div>';
        h += '</div>';

        // 2. 套装分支自定义下拉组件
        h += '<div class="shop-equip-filter-item">';
        h += '<span class="shop-equip-filter-label">套装分支</span>';
        h += '<div class="shop-custom-dropdown' + (self.openDropdown === 'branch' ? ' open' : '') + '" id="shopEquipDropdownWrap_branch">';
        h += '<button type="button" class="shop-custom-dropdown-trigger' + (self.equipmentBranch ? ' is-active' : '') + '" id="shopEquipDropdownTrigger_branch" aria-haspopup="listbox" aria-expanded="' + (self.openDropdown === 'branch') + '" onclick="Game.Shop.toggleDropdown(\'branch\', event)">';
        h += '<span class="shop-dropdown-text">' + esc(curBranchName) + '</span>';
        h += '<span class="shop-dropdown-arrow" aria-hidden="true">▾</span>';
        h += '</button>';
        h += '<div class="shop-custom-dropdown-menu" id="shopEquipDropdownMenu_branch" role="listbox"' + (self.openDropdown === 'branch' ? '' : ' hidden') + '>';
        for (var bi = 0; bi < branchOptions.length; bi++) {
          var bItem = branchOptions[bi];
          var isBranchSel = (self.equipmentBranch === bItem.key);
          h += '<div class="shop-dropdown-item' + (isBranchSel ? ' selected' : '') + '" role="option" aria-selected="' + isBranchSel + '" onclick="Game.Shop.selectEquipmentFilter(\'equipmentBranch\',\'' + bItem.key + '\')">';
          h += '<span>' + esc(bItem.name) + '</span>';
          if (isBranchSel) h += '<span class="shop-dropdown-item-check">✓</span>';
          h += '</div>';
        }
        h += '</div>';
        // 隐藏的原生 select 供辅助交互与兼容支持
        h += '<select class="shop-equip-filter-select' + (self.equipmentBranch ? ' is-active' : '') + '" style="display:none;" tabindex="-1" aria-hidden="true" onchange="Game.Shop.filterEquipment(\'equipmentBranch\',this.value)">';
        for (var bs = 0; bs < branchOptions.length; bs++) {
          h += '<option value="' + branchOptions[bs].key + '"' + (self.equipmentBranch === branchOptions[bs].key ? ' selected' : '') + '>' + branchOptions[bs].name + '</option>';
        }
        h += '</select>';
        h += '</div>';
        h += '</div>';

        if (hasActiveEquipFilter) {
          h += '<button type="button" class="shop-equip-filter-reset" onclick="Game.Shop.clearEquipmentFilters()" title="重置套装筛选">✕ 重置</button>';
        }
        h += '</div>';
        h += '</div>';
      }

      // 商品网格
      h += '<div class="shop-grid">';
      if (list.length === 0) {
        h += '<div class="shop-empty-tip">未找到匹配的商品</div>';
      }
      for (var j = 0; j < list.length; j++) {
        var it = list[j];
        var purchased = alreadyPurchased(it);
        var certificateReason = certificateUnavailableReason(it);
        var canBuy = !!(G.ServerRules.value('shop', {})[it.id] || {}).allowed;
        var tagHtml = it.tag ? '<span class="shop-tag">' + it.tag + '</span>' : '';
        var stockHtml = '';
        if (it.rankTier) {
          stockHtml = '<span class="shop-stock">每档最多持有1张未使用凭证</span>';
        } else if (it.stock != null) {
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
          h += '<button type="button" class="link-act" aria-haspopup="dialog" aria-label="查看' + esc(it.name) + '详情" onclick="Game.Shop.showEquipmentDetails(\'' + it.id + '\')">查看详情</button>';
        }
        h += '<div class="shop-card-foot">';
        h += '<span class="shop-price">💎 ' + it.price + '</span>';
        var buyAction = it.id === 'newbiePack' ? 'Game.Shop.buy(\'newbiePack\')' : 'Game.Shop.openBuyModal(\'' + it.id + '\')';
        h += '<button class="shop-buy' + (canBuy ? '' : ' disabled') + '"' + (canBuy ? '' : ' disabled') + ' onclick="' + buyAction + '">' + (certificateReason || (purchased ? '已购买' : '购买')) + '</button>';
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
  if (typeof document !== 'undefined' && typeof document.addEventListener === 'function' && !Shop._docClickBound) {
    Shop._docClickBound = true;
    document.addEventListener('click', function (e) {
      if (Shop.openDropdown) {
        if (!e.target || !e.target.closest || !e.target.closest('.shop-custom-dropdown')) {
          Shop.closeDropdowns();
        }
      }
    });
  }
  Core.views.shop = function (v) { Shop.renderView(v); };
})(window.Game);
