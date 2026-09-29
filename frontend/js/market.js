/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var Core = G.Core;

  var Market = {
    curTab: 'hall', // 'hall' | 'my' | 'system'
    filterRes: 'all',
    page: 0,
    overview: null,
    myOrders: null,
    loading: false,

    setTab: function (tab) {
      this.curTab = tab;
      this.refresh();
    },

    setFilter: function (resType) {
      this.filterRes = resType;
      this.page = 0;
      this.loadOverview();
    },

    loadOverview: function () {
      var self = this;
      self.loading = true;
      G.API.getMarketOverview(self.filterRes, self.page, 20).then(function (data) {
        self.overview = data;
        self.loading = false;
        Core.refreshContent();
      }).catch(function (err) {
        self.loading = false;
        G.toast(err && err.message ? err.message : '获取市场数据失败');
      });
    },

    loadMyOrders: function () {
      var self = this;
      self.loading = true;
      G.API.getMyMarketOrders().then(function (list) {
        self.myOrders = list || [];
        self.loading = false;
        Core.refreshContent();
      }).catch(function (err) {
        self.loading = false;
        G.toast(err && err.message ? err.message : '获取我的货架失败');
      });
    },

    refresh: function () {
      if (this.curTab === 'hall') {
        this.loadOverview();
      } else if (this.curTab === 'my') {
        this.loadMyOrders();
        // 同时更新 overview 统计信息
        var self = this;
        G.API.getMarketOverview('all', 0, 1).then(function (data) {
          self.overview = data;
          Core.refreshContent();
        }).catch(function () {});
      } else {
        // system tab
        Core.refreshContent();
      }
    },

    // 购买订单
    buyOrder: function (orderId, totalGold, resName, amount) {
      var s = Core.state;
      var curGold = (s.resources && s.resources.gold) || 0;
      if (curGold < totalGold) {
        G.toast('黄金不足 (需 ' + totalGold + ' 黄金)');
        return;
      }
      var self = this;
      if (!confirm('确认花费 ' + totalGold + ' 黄金购买 ' + amount + ' ' + resName + ' 吗？')) return;

      G.API.buyMarketOrder(orderId).then(function (resp) {
        G.toast('购买成功！已获得 ' + resName + ' ×' + amount);
        self.refresh();
      }).catch(function (err) {
        G.toast(err && err.message ? err.message : '购买失败');
      });
    },

    // 撤回挂单
    cancelOrder: function (orderId) {
      var self = this;
      G.World.showConfirm({
        title: '下架挂单',
        message: '确认下架此挂单吗？上架资源将如数返还。',
        okText: '确认下架',
        onConfirm: function () {
          G.API.cancelMarketOrder(orderId).then(function () {
            G.toast('挂单已成功下架，资源已返还');
            self.refresh();
          }).catch(function (err) {
            G.toast(err && err.message ? err.message : '下架失败');
          });
        }
      });
    },

    // 上架商品
    doCreateOrder: function () {
      var resEl = document.getElementById('marketSellRes');
      var amtEl = document.getElementById('marketSellAmt');
      var priceEl = document.getElementById('marketSellPrice');
      if (!resEl || !amtEl || !priceEl) return;

      var resType = resEl.value;
      var amount = parseInt(amtEl.value, 10);
      var price = parseInt(priceEl.value, 10);

      if (isNaN(amount) || amount <= 0) { G.toast('请输入有效的出售数量'); return; }
      if (isNaN(price) || price <= 0) { G.toast('请输入有效的单价'); return; }

      var s = Core.state;
      var current = (s.resources && s.resources[resType]) || 0;
      if (current < amount) {
        var rname = G.DATA.resources[resType] ? G.DATA.resources[resType].name : resType;
        G.toast(rname + '存量不足');
        return;
      }

      var self = this;
      G.API.createMarketOrder(resType, amount, price).then(function (resp) {
        G.toast('挂单已成功发布到市场！');
        amtEl.value = '';
        self.refresh();
      }).catch(function (err) {
        G.toast(err && err.message ? err.message : '发布挂单失败');
      });
    },

    // 执行系统资源战略调配
    doSystemExchange: function () {
      var fromEl = document.getElementById('sysExFrom');
      var toEl = document.getElementById('sysExTo');
      var amtEl = document.getElementById('sysExAmt');
      if (!fromEl || !toEl || !amtEl) return;

      var fromRes = fromEl.value;
      var toRes = toEl.value;
      var amt = parseInt(amtEl.value, 10);

      if (fromRes === toRes) { G.toast('调配源与目标资源不能相同'); return; }
      if (isNaN(amt) || amt <= 0) { G.toast('请输入有效的调配数量'); return; }

      var s = Core.state;
      var cur = (s.resources && s.resources[fromRes]) || 0;
      if (cur < amt) {
        var fname = G.DATA.resources[fromRes] ? G.DATA.resources[fromRes].name : fromRes;
        G.toast(fname + '不足');
        return;
      }

      var self = this;
      G.API.systemExchange(fromRes, toRes, amt).then(function (resp) {
        var d = resp.exchange;
        var fn = G.DATA.resources[d.fromRes] ? G.DATA.resources[d.fromRes].name : d.fromRes;
        var tn = G.DATA.resources[d.toRes] ? G.DATA.resources[d.toRes].name : d.toRes;
        G.toast('战略调配完成：消耗 ' + fn + ' ' + d.cost + '，获得 ' + tn + ' ' + d.gain);
        amtEl.value = '';
        Core.render();
      }).catch(function (err) {
        G.toast(err && err.message ? err.message : '调配失败');
      });
    },

    updateSystemPreview: function () {
      var fromEl = document.getElementById('sysExFrom');
      var toEl = document.getElementById('sysExTo');
      var amtEl = document.getElementById('sysExAmt');
      var prevEl = document.getElementById('sysExPreview');
      if (!fromEl || !toEl || !amtEl || !prevEl) return;

      var s = Core.state;
      var exLv = s.buildings.exchange || 0;
      var rate = 0.50 + exLv * 0.04;
      var amt = parseInt(amtEl.value, 10) || 0;
      var gain = Math.floor(amt * rate);
      var toName = G.DATA.resources[toEl.value] ? G.DATA.resources[toEl.value].name : toEl.value;

      prevEl.textContent = '预计获得: ' + G.fmt(gain) + ' ' + toName + ' (调配率 ' + (rate * 100).toFixed(0) + '%)';
    },

    renderView: function (v) {
      var s = Core.state;
      if (!s) return;
      var exLv = s.buildings.exchange || 0;
      if (exLv <= 0) {
        v.innerHTML = '<div class="title">- 交易所 -</div>' +
          '<div style="padding:40px 20px;text-align:center;color:#888">' +
            '<div style="font-size:32px;margin-bottom:12px">🏛</div>' +
            '<div>当前城池尚未建造交易所，无法使用交易功能</div>' +
            '<div style="margin-top:16px"><button class="btn ok" onclick="Game.go(\'buildArmy\')">前往军事区建造</button></div>' +
          '</div>';
        return;
      }

      // 初次加载数据
      if (!this.overview && !this.loading) {
        this.loadOverview();
      }

      var ratePct = ((0.50 + exLv * 0.04) * 100).toFixed(0);
      var taxPct = Math.max(5, 10 - (exLv - 1) * 0.5).toFixed(1);
      var depotCount = Core.buildingLevels('depot').filter(function (lv) { return lv > 0; }).length;
      var slotCap = 2 + Math.floor(exLv / 2) + depotCount;
      var usedSlots = (this.overview && this.overview.usedSlots != null) ? this.overview.usedSlots : 0;

      var h = '';
      h += '<div class="title">- 交易所 -</div>';
      h += '<div class="desc" style="text-align:center;margin-bottom:12px">' +
        '交易所 Lv.' + exLv + ' · 战略调配比率 <b>' + ratePct + '%</b> · 市场税率 <b>' + taxPct + '%</b> · 挂单槽位 <b>' + usedSlots + '/' + slotCap + '</b>' +
      '</div>';

      // 顶部 Tab 栏
      h += '<div class="market-tabs" style="display:flex;gap:6px;margin-bottom:14px;border-bottom:1px solid rgba(255,255,255,0.1);padding-bottom:8px">';
      h += '<button class="btn sm' + (this.curTab === 'hall' ? ' ok' : '') + '" style="flex:1" onclick="Game.Market.setTab(\'hall\')">🛒 市场大厅</button>';
      h += '<button class="btn sm' + (this.curTab === 'my' ? ' ok' : '') + '" style="flex:1" onclick="Game.Market.setTab(\'my\')">📦 我的货架 (' + usedSlots + '/' + slotCap + ')</button>';
      h += '<button class="btn sm' + (this.curTab === 'system' ? ' ok' : '') + '" style="flex:1" onclick="Game.Market.setTab(\'system\')">⚖️ 战略调配</button>';
      h += '</div>';

      if (this.curTab === 'hall') {
        h += this.renderHallTab();
      } else if (this.curTab === 'my') {
        h += this.renderMyTab(usedSlots, slotCap);
      } else {
        h += this.renderSystemTab(ratePct);
      }

      h += '<div class="menu" style="margin-top:20px">';
      h += '<div class="menu-item back" onclick="Game.go(\'buildArmy\')">[0] 返回军事区</div>';
      h += '</div>';

      v.innerHTML = h;
    },

    renderHallTab: function () {
      var self = this;
      var h = '';

      // 资源筛选条
      var filterTypes = [
        { id: 'all', name: '全部' },
        { id: 'food', name: '粮食' },
        { id: 'steel', name: '钢铁' },
        { id: 'oil', name: '石油' },
        { id: 'rare', name: '稀矿' }
      ];
      h += '<div style="display:flex;gap:4px;margin-bottom:12px;overflow-x:auto">';
      for (var f = 0; f < filterTypes.length; f++) {
        var ft = filterTypes[f];
        var isAct = self.filterRes === ft.id;
        h += '<button class="btn sm' + (isAct ? ' ok' : '') + '" style="padding:2px 10px;font-size:12px" onclick="Game.Market.setFilter(\'' + ft.id + '\')">' + ft.name + '</button>';
      }
      h += '</div>';

      var orders = (self.overview && self.overview.orders) || [];
      if (self.loading) {
        h += '<div style="padding:40px;text-align:center;color:#888">正在加载全服市场订单...</div>';
      } else if (!orders.length) {
        h += '<div class="menu-item" style="padding:30px;text-align:center;color:#888">市场大厅当前暂无该类上架物资，您可以前往【我的货架】上架出售</div>';
      } else {
        var myPlayerId = Core.state.player ? Core.state.player.id : null;
        for (var i = 0; i < orders.length; i++) {
          var o = orders[i];
          var isSelf = (myPlayerId != null && o.sellerId === myPlayerId);
          var resDef = G.DATA.resources[o.resourceType] || {};
          var resName = resDef.name || o.resourceType;
          var resIcon = G.resourceIconHtml(o.resourceType);

          h += '<div class="menu-item ok" style="margin-bottom:8px;padding:8px 12px;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px">';
          h += '<div>';
          h += '<div style="font-weight:bold;font-size:14px;display:flex;align-items:center;gap:4px">';
          h += resIcon + ' ' + resName + ' × ' + G.fmt(o.amount);
          if (isSelf) h += ' <span style="font-size:11px;color:var(--accent);border:1px solid var(--accent);border-radius:3px;padding:0 3px">我上架的</span>';
          h += '</div>';
          h += '<div class="d" style="font-size:12px;color:#888;margin-top:2px">';
          h += '出售方: ' + G.escapeHtml(o.sellerName) + ' · 单价: <b style="color:var(--gold,#b3832f)">' + o.pricePerUnit + '</b> 黄金';
          h += '</div>';
          h += '</div>';

          h += '<div style="text-align:right">';
          h += '<div style="font-size:13px;font-weight:bold;color:var(--gold,#b3832f);margin-bottom:4px">总价: ' + G.fmt(o.totalPrice) + ' 黄金</div>';
          if (isSelf) {
            h += '<button class="btn sm warn" onclick="Game.Market.cancelOrder(' + o.id + ')">撤销下架</button>';
          } else {
            h += '<button class="btn sm ok" onclick="Game.Market.buyOrder(' + o.id + ',' + o.totalPrice + ',\'' + resName + '\',' + o.amount + ')">购买</button>';
          }
          h += '</div>';
          h += '</div>';
        }
      }
      return h;
    },

    renderMyTab: function (usedSlots, slotCap) {
      var s = Core.state;
      var canList = usedSlots < slotCap;
      var h = '';

      // 上架表单面板
      h += '<div class="zone-head">📦 发布新挂单</div>';
      h += '<div class="menu-item" style="padding:10px 14px;margin-bottom:16px">';
      h += '<div class="d" style="margin-bottom:8px">货架槽位: <b>' + usedSlots + ' / ' + slotCap + '</b> · 基础2槽位 + 交易所每2级+1 + 仓库有效槽位</div>';
      h += '<div style="display:flex;gap:6px;flex-wrap:wrap;align-items:center">';
      h += '<select class="qty" id="marketSellRes"' + (canList ? '' : ' disabled') + ' style="padding:4px">';
      h += '<option value="food">粮食 (存量 ' + G.fmt(s.resources.food || 0) + ')</option>';
      h += '<option value="steel">钢铁 (存量 ' + G.fmt(s.resources.steel || 0) + ')</option>';
      h += '<option value="oil">石油 (存量 ' + G.fmt(s.resources.oil || 0) + ')</option>';
      h += '<option value="rare">稀矿 (存量 ' + G.fmt(s.resources.rare || 0) + ')</option>';
      h += '</select>';
      h += '<input class="qty" id="marketSellAmt" type="number" min="1" placeholder="数量" style="width:90px;padding:4px"' + (canList ? '' : ' disabled') + ' />';
      h += '<input class="qty" id="marketSellPrice" type="number" min="1" placeholder="单价(黄金)" style="width:90px;padding:4px"' + (canList ? '' : ' disabled') + ' />';
      h += '<button class="btn sm ok"' + (canList ? '' : ' disabled') + ' onclick="Game.Market.doCreateOrder()">' + (canList ? '发布上架' : '货架已满') + '</button>';
      h += '</div>';
      h += '</div>';

      // 我的挂单列表
      var list = this.myOrders || [];
      h += '<div class="zone-head">📋 我的挂单记录 (' + list.length + ')</div>';
      if (!list.length) {
        h += '<div class="desc" style="padding:16px;text-align:center;color:#888">暂无上架记录</div>';
      } else {
        for (var i = 0; i < list.length; i++) {
          var it = list[i];
          var rdef = G.DATA.resources[it.resourceType] || {};
          var rname = rdef.name || it.resourceType;
          var statusText = it.status === 'ACTIVE' ? '<span style="color:var(--accent,#526b4d)">出售中</span>' :
                          (it.status === 'SOLD' ? '<span style="color:var(--gold,#b3832f)">已售出 (买家: ' + G.escapeHtml(it.buyerName || '其他玩家') + ')</span>' : '<span style="color:#888">已撤单</span>');

          h += '<div class="menu-item" style="padding:8px 12px;margin-bottom:8px;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:6px">';
          h += '<div>';
          h += '<div style="font-weight:bold">' + G.resourceIconHtml(it.resourceType) + ' ' + rname + ' × ' + G.fmt(it.amount) + ' · ' + statusText + '</div>';
          h += '<div class="d" style="font-size:12px;color:#888;margin-top:2px">';
          h += '单价: ' + it.pricePerUnit + ' 黄金 · 总价: ' + G.fmt(it.totalPrice) + ' 黄金 (税率 ' + ((it.taxRate || 0.1) * 100).toFixed(1) + '%)';
          h += '</div>';
          h += '</div>';

          if (it.status === 'ACTIVE') {
            h += '<button class="btn sm warn" onclick="Game.Market.cancelOrder(' + it.id + ')">撤销下架</button>';
          }
          h += '</div>';
        }
      }
      return h;
    },

    renderSystemTab: function (ratePct) {
      var s = Core.state;
      var h = '';
      h += '<div class="zone-head">⚖️ 战略物资调配（系统直兑）</div>';
      h += '<div class="menu-item" style="padding:14px;line-height:1.6">';
      h += '<div class="desc" style="margin-bottom:10px">通过司令部官方物资储备进行战略互换，解决战备资源失衡。当前等级调配比率为 <b>' + ratePct + '%</b>（升级交易所可提升折损比率，最高可达 86%~90%）。</div>';

      h += '<div style="display:flex;align-items:center;gap:8px;margin-bottom:10px;flex-wrap:wrap">';
      h += '<div style="display:flex;align-items:center;gap:4px">';
      h += '<span style="font-size:12px;color:#888">支付:</span>';
      h += '<select class="qty" id="sysExFrom" onchange="Game.Market.updateSystemPreview()" style="padding:4px">';
      h += '<option value="food">粮食 (' + G.fmt(s.resources.food || 0) + ')</option>';
      h += '<option value="steel">钢铁 (' + G.fmt(s.resources.steel || 0) + ')</option>';
      h += '<option value="oil">石油 (' + G.fmt(s.resources.oil || 0) + ')</option>';
      h += '<option value="rare">稀矿 (' + G.fmt(s.resources.rare || 0) + ')</option>';
      h += '</select>';
      h += '</div>';

      h += '<span style="font-weight:bold;color:var(--accent)">➔</span>';

      h += '<div style="display:flex;align-items:center;gap:4px">';
      h += '<span style="font-size:12px;color:#888">换取:</span>';
      h += '<select class="qty" id="sysExTo" onchange="Game.Market.updateSystemPreview()" style="padding:4px">';
      h += '<option value="steel">钢铁 (' + G.fmt(s.resources.steel || 0) + ')</option>';
      h += '<option value="food">粮食 (' + G.fmt(s.resources.food || 0) + ')</option>';
      h += '<option value="oil">石油 (' + G.fmt(s.resources.oil || 0) + ')</option>';
      h += '<option value="rare">稀矿 (' + G.fmt(s.resources.rare || 0) + ')</option>';
      h += '</select>';
      h += '</div>';
      h += '</div>';

      h += '<div style="display:flex;align-items:center;gap:8px;margin-bottom:12px">';
      h += '<input class="qty" id="sysExAmt" type="number" min="1" value="1000" oninput="Game.Market.updateSystemPreview()" style="width:120px;padding:5px" placeholder="调配数量" />';
      h += '<button class="btn ok" onclick="Game.Market.doSystemExchange()">立即兑换</button>';
      h += '</div>';

      h += '<div id="sysExPreview" style="font-size:13px;color:var(--gold,#b3832f);font-weight:bold">预计获得: 0 (调配率 ' + ratePct + '%)</div>';
      h += '</div>';
      return h;
    }
  };

  // 挂载到 Core.views.exchange
  if (Core && Core.views) {
    Core.views.exchange = function (v) { Market.renderView(v); };
  }

  G.Market = Market;
})(window.Game);
