/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;
  var Core = G.Core;
  var queue = [];
  var refreshTimer = null;
  var currentTab = 'units';
  var lastRoute = null;
  var expandedUnits = {};
  var disbandModal = null;

  function setTab(tab) {
    currentTab = tab || 'units';
    var tabUnits = document.getElementById('army-tab-units');
    var tabQueue = document.getElementById('army-tab-queue');
    var panelUnits = document.getElementById('army-panel-units');
    var panelQueue = document.getElementById('army-panel-queue');
    if (tabUnits && tabQueue && panelUnits && panelQueue) {
      if (currentTab === 'queue') {
        tabUnits.classList.remove('active');
        tabQueue.classList.add('active');
        panelUnits.style.display = 'none';
        panelQueue.style.display = 'block';
        renderQueue();
      } else {
        tabUnits.classList.add('active');
        tabQueue.classList.remove('active');
        panelUnits.style.display = 'block';
        panelQueue.style.display = 'none';
      }
    }
  }

  function getUnitDisplayName(val) {
    if (G && typeof G.unitDisplayName === 'function') return G.unitDisplayName(val);
    var name = typeof val === 'object' && val.name ? val.name : String(val || '');
    var codeMatch = name.match(/[（(]([^）)]+)[）)]/);
    var code = codeMatch ? codeMatch[1].trim() : '';
    var base = name.indexOf('-') > 0 ? name.split('-')[0].trim() : name.replace(/[（(].*?[）)]/, '').trim();
    return code ? base + '(' + code + ')' : base;
  }

  function unitCost(id, n) {
    var u = D.units[id];
    var cost = {};
    for (var k in u.cost) cost[k] = u.cost[k] * n;
    cost.pop = u.pop * n;
    return cost;
  }

  function costText(cost) {
    var arr = [];
    var emojiMap = (G.DATA && G.DATA.resEmoji) || {};
    for (var k in cost) {
      if (k === 'pop') arr.push('人' + cost[k]);
      else arr.push((emojiMap[k] || G.DATA.resources[k].icon || k) + cost[k]);
    }
    return arr.join(' ');
  }

  function readQty(inputId, dflt) {
    var el = document.getElementById(inputId);
    if (!el) return dflt != null ? dflt : 1;
    var n = parseInt(el.value, 10);
    return isNaN(n) ? (dflt != null ? dflt : 1) : n;
  }

  function closeDisbandModal() {
    if (disbandModal && disbandModal.parentNode) disbandModal.parentNode.removeChild(disbandModal);
    disbandModal = null;
  }

  function maxRecruitable(unit) {
    var limit = Math.floor(Core.popFree() / unit.pop);
    var resources = (Core.state && Core.state.resources) || {};
    for (var key in unit.cost) {
      var perUnit = unit.cost[key] || 0;
      if (perUnit > 0) limit = Math.min(limit, Math.floor((resources[key] || 0) / perUnit));
    }
    return Math.max(0, limit);
  }

  // 方案B: 按栋独立计算后求和 - 给出该兵种对应的"训练建筑群"统计
  //   maxBatch = sum(每栋 lv × 10 × trainMul)
  //   parallel = sum(每栋 1 + lv/5)
  //   avgSpeed = (sum(每栋 laneSpeed × lanes)) / parallel
  //             其中 laneSpeed = 1 + min(4, lv × 0.05) + (trainMul - 1)
  function getBuildStats(buildKey) {
    var arr = Core.buildingLevels(buildKey) || [];
    var mul = Core.trainMul ? Core.trainMul() : 1;
    var maxBatch = 0, parallel = 0, weighted = 0;
    for (var i = 0; i < arr.length; i++) {
      var lv = arr[i] || 0;
      if (lv <= 0) continue;
      maxBatch += Math.floor(lv * 10 * mul);
      var p = 1 + Math.floor(lv / 5);
      parallel += p;
      var laneSpeed = 1 + Math.min(4, lv * 0.05) + Math.max(0, mul - 1);
      weighted += laneSpeed * p;
    }
    parallel = Math.max(0, Math.min(20, parallel));
    var avgSpeed = parallel > 0 ? weighted / parallel : 1;
    var rawMaxBatch = maxBatch;
    maxBatch = Math.min(maxBatch, 500);
    return { maxBatch: maxBatch, rawMaxBatch: rawMaxBatch, parallel: parallel, avgSpeed: avgSpeed, count: arr.length, sumLevel: arr.reduce(function (sum, lv) { return sum + (lv || 0); }, 0), trainMul: mul };
  }

  function formatSeconds(seconds) {
    seconds = Math.max(0, Math.floor(seconds || 0));
    var d = Math.floor(seconds / 86400);
    var h = Math.floor((seconds % 86400) / 3600);
    var m = Math.floor((seconds % 3600) / 60);
    var s = seconds % 60;
    var sStr = String(s).padStart(2, '0') + '秒';
    if (d > 0) {
      return d + '天' + h + '小时' + m + '分' + sStr;
    }
    if (h > 0) {
      return h + '小时' + m + '分' + sStr;
    }
    return m ? m + '分' + sStr : s + '秒';
  }

  function updateQueue(forceRender) {
    return G.API.armyQueue().then(function (data) {
      queue = (data && data.queue) || [];
      if (forceRender && Core.route === 'army') Core.render();
      else renderQueue();
      return queue;
    }).catch(function () {
      // 首次拉取失败时不能让“正在加载”永久停留；已本地入队的订单也应继续可见。
      if (Core.route === 'army') renderQueue('队列同步失败，稍后自动重试');
      return queue;
    });
  }

  function renderQueue(syncError) {
    var badge = document.getElementById('army-queue-badge');
    if (badge) {
      if (queue.length > 0) {
        badge.textContent = queue.length;
        badge.style.display = 'inline-block';
      } else {
        badge.style.display = 'none';
      }
    }
    var el = document.getElementById('army-production-queue');
    if (!el) return;
    var now = Date.now();
    if (!queue.length) {
      el.innerHTML = '<div class="desc" style="text-align:center;padding:24px 0;">' +
        '<div style="font-size:14px;color:var(--muted);margin-bottom:12px;">' + (syncError || '暂无进行中的生产队列') + '</div>' +
        '<button type="button" class="btn ok sm" onclick="Game.Army.setTab(\'units\')">[前往征召部队]</button>' +
      '</div>';
      return;
    }
    // 汇总玩家拥有的加速符
    var s = Core.state || {};
    var owned = [];
    ['speedUp10m','speedUp1h','speedUp5h','speedUp12h','speedUp24h','speedUp36h','speedUp48h','speedUp72h'].forEach(function (k) {
      var cnt = (s.items && s.items[k]) || 0;
      if (cnt > 0) {
        var info = (D.items && D.items[k]) || {};
        owned.push({ id: k, name: info.name || k, cnt: cnt, seconds: info.seconds || 0 });
      }
    });
    var totalSpeed = owned.reduce(function (a, b) { return a + b.cnt; }, 0);

    var h = '';
    queue.forEach(function (item) {
      var unit = D.units[item.unitType] || { name: item.unitType };
      var left = Math.max(0, Math.ceil((item.finishesAt - now) / 1000));
      // 加速按钮：拥有任意加速符时可点击；否则显示禁用提示，文字精简避免折行
      var speedBtn;
      if (totalSpeed > 0) {
        speedBtn = '<button type="button" class="btn ok sm army-queue-btn" title="加速生产" onclick="Game.Army.openSpeedUpPicker(' + item.id + ')">[⚡加速]</button>';
      } else {
        speedBtn = '<button type="button" class="btn sm army-queue-btn disabled" disabled title="背包暂无加速符">[⚡加速]</button>';
      }
      var iconHtml = (G && typeof G.getUnitModelIconHtml === 'function')
        ? G.getUnitModelIconHtml(item.unitType, unit.name, 'army-queue-icon')
        : (G && typeof G.getUnitIconHtml === 'function' ? G.getUnitIconHtml(item.unitType, unit.name, 'army-queue-icon') : '');
      h += '<div class="menu-item ok army-queue-item">' +
        '<div class="army-queue-info">' +
          iconHtml +
          '<div class="army-queue-text">' +
            '<span class="n" title="' + G.escapeHtml(unit.name) + '">' + getUnitDisplayName(unit.name) + ' <b class="army-queue-qty">x' + item.count + '</b></span>' +
            '<span class="lv">剩余 ' + formatSeconds(left) + '</span>' +
          '</div>' +
        '</div>' +
        '<div class="army-queue-actions">' +
          speedBtn +
          '<button type="button" class="btn warn sm army-queue-btn" title="取消生产" onclick="Game.Army.cancelProduction(' + item.id + ')">[取消]</button>' +
        '</div>' +
      '</div>';
    });
    el.innerHTML = h;
  }

  // 弹出加速符选择面板
  function openSpeedUpPicker(queueId) {
    var s = Core.state || {};
    var owned = [];
    ['speedUp10m','speedUp1h','speedUp5h','speedUp12h','speedUp24h','speedUp36h','speedUp48h','speedUp72h'].forEach(function (k) {
      var cnt = (s.items && s.items[k]) || 0;
      if (cnt > 0) {
        var info = (D.items && D.items[k]) || {};
        owned.push({ id: k, name: info.name || k, cnt: cnt, seconds: info.seconds || 0 });
      }
    });
    if (!owned.length) { G.toast('请先在商城购买加速符'); return; }

    // 找到当前 queue 中的目标订单（用于显示兵种名）
    var qItem = null;
    for (var i = 0; i < queue.length; i++) if (queue[i].id === queueId) { qItem = queue[i]; break; }
    var unitLabel = qItem ? (getUnitDisplayName(D.units[qItem.unitType] || qItem.unitType) + ' x' + qItem.count) : ('订单 #' + queueId);
    var now = Date.now();
    var remSec = qItem && qItem.finishesAt ? Math.max(0, Math.ceil((qItem.finishesAt - now) / 1000)) : 0;
    var remText = formatSeconds(remSec);

    // 弹窗：复用 .modal-mask / .modal-card 风格
    var mask = document.createElement('div');
    mask.className = 'modal-mask';
    mask.innerHTML = '<div class="modal-card spicker" style="max-width:350px">'
      + '<div class="spicker-head">⚡ 选择加速符 <span class="spicker-close" onclick="Game.Army.closeSpeedUpPicker()">×</span></div>'
      + '<div class="spicker-sub">目标: ' + G.escapeHtml(unitLabel) + ' · 剩余 ' + remText + '</div>'
      + '<div class="spicker-list"></div>'
      + '<div class="spicker-foot"><button type="button" class="btn sm" onclick="Game.Army.closeSpeedUpPicker()">[取消]</button></div>'
      + '</div>';
    document.body.appendChild(mask);

    var list = mask.querySelector('.spicker-list');
    owned.forEach(function (o) {
      var cardSec = o.seconds || 0;
      var need = 0;
      if (cardSec > 0 && remSec > 0) {
        need = Math.floor(remSec / cardSec);
        if (need === 0) need = 1;
      }
      var useCount = Math.min(need, o.cnt);
      if (useCount <= 0 && o.cnt > 0) useCount = 1;

      var row = document.createElement('div');
      row.className = 'spicker-row';
      row.setAttribute('data-sid', o.id);
      row.setAttribute('data-count', useCount);
      row.innerHTML = '<span class="spicker-name">⚡ ' + G.escapeHtml(o.name) + ' <small style="font-weight:normal;color:#8a7a58">(余' + o.cnt + ')</small></span>'
        + '<span class="spicker-cnt">需要消耗 ' + useCount + ' 张</span>';
      row.onclick = function () {
        var sid = this.getAttribute('data-sid');
        var cnt = parseInt(this.getAttribute('data-count'), 10) || 1;
        close();
        Game.Army.useSpeedUp(queueId, sid, cnt);
      };
      list.appendChild(row);
    });

    var close = function () { if (mask.parentNode) mask.parentNode.removeChild(mask); };
    Army._speedUpMask = mask;
    mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
  }

  function closeSpeedUpPicker() {
    if (Army._speedUpMask && Army._speedUpMask.parentNode) {
      Army._speedUpMask.parentNode.removeChild(Army._speedUpMask);
    }
    Army._speedUpMask = null;
  }

  function useSpeedUp(queueId, itemId, count) {
    count = count > 0 ? count : 1;
    G.API.armyQueueSpeedUp(queueId, itemId, count).then(function (resp) {
      if (resp && resp.success === false) {
        G.toast(resp.message || '加速失败');
        if (resp.state) G.API.applyState(resp.state);
        return;
      }
      G.toast(resp.message || ('⚡ 加速成功'));
      if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
      return updateQueue(true);
    }).catch(function (err) { G.toast(err.message || '加速失败'); });
  }

  var Army = {
    setTab: setTab,
    onSliderChange: function (id, val) {
      var num = parseInt(val, 10);
      if (isNaN(num)) num = 0;
      var inputEl = document.getElementById('qty_' + id);
      if (inputEl) inputEl.value = num;

      var sliderEl = document.getElementById('slider_' + id);
      if (sliderEl) {
        var max = parseInt(sliderEl.max, 10) || 0;
        var pct = max > 0 ? Math.min(100, Math.max(0, (num / max) * 100)) : 0;
        sliderEl.style.setProperty('--p', pct.toFixed(1) + '%');
      }
    },

    onInputChange: function (id, val) {
      var sliderEl = document.getElementById('slider_' + id);
      if (!sliderEl) return;
      var max = parseInt(sliderEl.max, 10) || 0;
      if (val === '') {
        sliderEl.value = 0;
        sliderEl.style.setProperty('--p', '0%');
        return;
      }
      var num = parseInt(val, 10);
      if (isNaN(num)) num = 0;
      var clamped = Math.min(Math.max(0, num), max);
      sliderEl.value = clamped;
      var pct = max > 0 ? Math.min(100, Math.max(0, (clamped / max) * 100)) : 0;
      sliderEl.style.setProperty('--p', pct.toFixed(1) + '%');
    },

    recruit: function (id, inputId) {
      var n = readQty(inputId, 0);
      var max = maxRecruitable(D.units[id]);
      if (max <= 0) {
        G.toast('当前可用平民或资源不足，无法征召');
        return;
      }
      if (n <= 0) {
        G.toast('请选择或输入大于 0 的征召数量');
        return;
      }
      if (n > max) {
        G.toast('当前最多可征召 ' + max + ' 个（受平民和资源限制）');
        return;
      }
      G.API.recruit(id, n).then(function (resp) {
        if (!resp || !resp.success) {
          G.toast((resp && resp.message) || '征召失败');
          return;
        }
        var queuedCount = resp.count || n;
        queue.push({
          id: resp.queueId,
          unitType: resp.unitType || id,
          count: queuedCount,
          finishesAt: resp.finishesAt,
          durationSeconds: resp.durationSeconds
        });
        G.toast(resp.message || ('已加入 ' + getUnitDisplayName(D.units[id].name) + ' 生产队列 x' + queuedCount));
        // 先立即显示本次订单；随后与后端队列同步，避免用户感到点击无反馈。
        renderQueue();
        if (Core.route === 'army') Core.render();
        return updateQueue(false);
      }).catch(function (err) { G.toast(err.message || '征召失败'); });
    },

    cancelProduction: function (queueId) {
      G.API.cancelArmyQueue(queueId).then(function () {
        G.toast('生产已取消，资源与平民已返还');
        return updateQueue(true);
      }).catch(function (err) { G.toast(err.message || '取消失败'); });
    },

    openSpeedUpPicker: openSpeedUpPicker,
    closeSpeedUpPicker: closeSpeedUpPicker,
    useSpeedUp: useSpeedUp,
    formatSeconds: formatSeconds,

    openDisbandModal: function (id) {
      var have = (Core.state && Core.state.army && Core.state.army[id]) || 0;
      if (have <= 0) {
        G.toast('当前没有可解散的部队');
        return;
      }

      closeDisbandModal();
      var unit = D.units[id] || { name: id };
      var unitName = getUnitDisplayName(unit.name);
      var mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.id = 'armyDisbandModalMask';
      mask.innerHTML = '<div class="modal-card army-disband-modal">' +
        '<div class="army-disband-head">' +
          '<div class="army-disband-title">解散部队</div>' +
          '<button type="button" class="army-disband-close" id="armyDisbandClose" aria-label="关闭">×</button>' +
        '</div>' +
        '<div class="army-disband-body">' +
          '<div class="army-disband-unit">' +
            '<span>兵种</span><b>' + G.escapeHtml(unitName) + '</b>' +
            '<span>当前数量</span><strong>' + G.fmt(have) + '</strong>' +
          '</div>' +
          '<div class="army-disband-quantity">' +
            '<div class="army-disband-label">解散数量 <span>1 - ' + G.fmt(have) + '</span></div>' +
            '<div class="army-disband-qty-row">' +
              '<input class="qty recruit-qty" id="armyDisbandQty" type="number" min="1" max="' + have + '" value="1" />' +
              '<div class="recruit-slider-wrap"><input type="range" class="recruit-slider" id="armyDisbandSlider" min="1" max="' + have + '" value="1" style="--p:' + (100 / have).toFixed(1) + '%" /></div>' +
            '</div>' +
          '</div>' +
          '<div class="army-disband-hint">解散后部队将永久离队，请确认数量。</div>' +
        '</div>' +
        '<div class="btn-row army-disband-actions">' +
          '<button type="button" class="btn sm" id="armyDisbandCancel">取消</button>' +
          '<button type="button" class="btn sm warn" id="armyDisbandConfirm">确定解散</button>' +
        '</div>' +
      '</div>';
      document.body.appendChild(mask);
      disbandModal = mask;

      var input = mask.querySelector('#armyDisbandQty');
      var slider = mask.querySelector('#armyDisbandSlider');
      var normalizeQty = function (value) {
        var amount = parseInt(value, 10);
        if (isNaN(amount)) amount = 1;
        amount = Math.min(have, Math.max(1, amount));
        input.value = amount;
        slider.value = amount;
        slider.style.setProperty('--p', ((amount / have) * 100).toFixed(1) + '%');
        return amount;
      };
      input.oninput = function () { normalizeQty(input.value); };
      input.onchange = function () { normalizeQty(input.value); };
      slider.oninput = function () { normalizeQty(slider.value); };
      mask.querySelector('#armyDisbandCancel').onclick = closeDisbandModal;
      mask.querySelector('#armyDisbandClose').onclick = closeDisbandModal;
      mask.querySelector('#armyDisbandConfirm').onclick = function () {
        var amount = normalizeQty(input.value);
        closeDisbandModal();
        return Army.disband(id, amount);
      };
      mask.onclick = function (event) { if (event.target === mask) closeDisbandModal(); };
    },

    closeDisbandModal: closeDisbandModal,

    disband: function (id, count) {
      var n = parseInt(count, 10);
      var have = (Core.state && Core.state.army && Core.state.army[id]) || 0;
      if (isNaN(n) || n <= 0) {
        G.toast('请输入大于 0 的解散数量');
        return Promise.resolve();
      }
      if (n > have) {
        G.toast('解散数量不能超过当前拥有的数量(' + have + ')');
        return Promise.resolve();
      }
      return G.API.dismiss(id, n).then(function () {
        G.toast('解散 ' + getUnitDisplayName(D.units[id].name) + ' x' + n);
        if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
        Core.render();
      }).catch(function (err) { G.toast(err.message || '解散失败'); });
    },

    totalArmy: function () {
      var s = Core.state, sum = 0;
      for (var id in s.army) sum += s.army[id];
      return sum;
    },

    toggleUnitCard: function (id, ev) {
      if (ev && ev.stopPropagation) ev.stopPropagation();
      expandedUnits[id] = !expandedUnits[id];
      var card = document.getElementById('unit-card-' + id);
      if (card) {
        var details = card.querySelector('.unit-card-details');
        var toggle = card.querySelector('.unit-card-toggle');
        if (expandedUnits[id]) {
          card.classList.add('expanded');
          if (details) details.style.display = 'block';
          if (toggle) {
            toggle.innerHTML = '收起 &#9652;';
            toggle.classList.add('active');
          }
        } else {
          card.classList.remove('expanded');
          if (details) details.style.display = 'none';
          if (toggle) {
            toggle.innerHTML = '详情 &#9662;';
            toggle.classList.remove('active');
          }
        }
      } else if (Core.route === 'army') {
        Core.render();
      }
    },

    isUnitExpanded: function (id) {
      return !!expandedUnits[id];
    },

    setUnitExpanded: function (id, val) {
      expandedUnits[id] = !!val;
    },

    renderView: function (v) {
      if (Core.route !== lastRoute) {
        currentTab = 'units';
        lastRoute = Core.route;
      }
      var s = Core.state;
      var cmd = Core.getOfficerByRole('commander');
      var isQueueTab = currentTab === 'queue';
      var badgeStyle = queue.length > 0 ? 'inline-block' : 'none';
      var badgeText = queue.length > 0 ? queue.length : '0';

      var h = '<div class="title">- 兵种整编 -</div>';
      h += '<div class="desc">平民: ' + Core.civilianPopulation() + '/' + Core.populationCapacity() + ' (可征召 ' + Core.popFree() + '，增长 +' + G.fmt(Core.populationGrowthPerHour()) + '/h)  带兵: ' + this.totalArmy() + '/' + Core.armyCap() + (cmd ? '  指挥官:' + cmd.name : '  未任命指挥官') + '</div>';
      h += '<div class="desc">养兵耗粮: ' + G.fmt(Core.foodPerHour()) + '/h；创建征兵队列立即扣除平民，取消或解散返还</div>';
      h += '<div class="desc">按目标使用对地、对空、对海或对工事攻击；所有兵种均有基础火力，弱项攻击效率很低，请搭配护航与攻坚部队。</div>';

      h += '<div class="army-tabs">' +
        '<div id="army-tab-units" class="army-tab' + (!isQueueTab ? ' active' : '') + '" onclick="Game.Army.setTab(\'units\')">军队</div>' +
        '<div id="army-tab-queue" class="army-tab' + (isQueueTab ? ' active' : '') + '" onclick="Game.Army.setTab(\'queue\')">生产队列 <span id="army-queue-badge" class="army-tab-badge" style="display:' + badgeStyle + '">' + badgeText + '</span></div>' +
        '<button class="army-tab" onclick="Game.go(\'wounded\')">伤兵营' + (s.woundedCount ? ' (' + G.fmt(s.woundedCount) + ')' : '') + '</button>' +
      '</div>';

      // 军队面板（所有兵种直接合并展示，取消分类分区）
      h += '<div id="army-panel-units" style="display:' + (!isQueueTab ? 'block' : 'none') + ';">';
      h += '<div class="menu">';
      var allUnits = [
        'infantry', 'motor', 'truck', 'armored', 'ltank', 'htank', 'assault', 'rocket',
        'scout', 'special', 'fighter', 'bomber', 'transport',
        'destroyer', 'sub', 'battleship', 'carrier'
      ];
      allUnits.forEach(function (id) {
        var u = D.units[id];
        if (!u) return;
        var have = (s.army && s.army[id]) || 0;
        var stats = getBuildStats(u.build);
        var sumLv = stats.sumLevel;
        var can = stats.maxBatch > 0;
        var maxRecruit = maxRecruitable(u);
        var inpId = 'qty_' + id;
        // 兵工厂展示: 1栋时显示 Lv.x, 多栋时显示 Lv.总和(共N栋) 让玩家清楚每个兵工厂独立计算
        var lvLabel = stats.count > 1 ? ('Lv.' + sumLv + ' (共' + stats.count + '栋)') : ('Lv.' + sumLv);
        var bName = (D.buildings[u.build] && D.buildings[u.build].name) || u.build;
        var isExpanded = !!expandedUnits[id];
        var cardCls = (can ? 'menu-item ok' : 'menu-item lock') + ' unit-card' + (isExpanded ? ' expanded' : '');

        var iconHtml = (G && typeof G.getUnitModelIconHtml === 'function')
          ? G.getUnitModelIconHtml(id, u.name, 'unit-card-icon')
          : (G && typeof G.getUnitIconHtml === 'function' ? G.getUnitIconHtml(id, u.name, 'unit-card-icon') : '');
        h += '<div id="unit-card-' + id + '" class="' + cardCls + '">';
        h += '<div class="unit-card-header" onclick="Game.Army.toggleUnitCard(\'' + id + '\', event)" title="点击展开/收起具体信息">';
        h += '<div class="unit-card-header-left">';
        h += iconHtml + '<span class="n" title="' + G.escapeHtml(u.name) + '">' + getUnitDisplayName(u.name) + '</span> <span class="lv">x' + have + '</span> <span class="blv">(' + bName + ' ' + lvLabel + ')</span>';
        h += '</div>';
        h += '<span class="unit-card-toggle' + (isExpanded ? ' active' : '') + '">' + (isExpanded ? '收起 &#9652;' : '详情 &#9662;') + '</span>';
        h += '</div>';

        // 默认隐藏的原型与生产等具体信息展示区
        h += '<div class="unit-card-details" style="display:' + (isExpanded ? 'block' : 'none') + ';">';
        if (u.history) h += '<div class="d unit-history">原型：' + G.escapeHtml(u.history) + '</div>';
        h += '<div class="d">' + G.escapeHtml((D.combatRoles && D.combatRoles[id]) || '') + '</div>';
        h += '<div class="d">对地' + u.atkGround + ' 对空' + u.atkAir + ' 对海' + u.atkSea + ' 对工事' + u.atkFort + ' 防' + u.def + ' 血' + u.hp + ' 速' + u.spd + ' 射程' + u.range + ' 常驻耗粮' + u.food + '/h</div>';
        h += '<div class="d">行军补给：油耗' + (u.marchOil || 0) + '/100格，粮耗' + (u.marchFood || 0) + '/5分钟</div>';
        if (can) {
          var parallel = stats.parallel;
          h += '<div class="d unit-prod-stat">生产: 基础 ' + (30 + Math.floor((u.cost.steel + u.cost.oil + u.cost.rare) / 10)) + '秒/个，并行 ' + parallel + ' 条 (按栋独立), 平均速度 ×' + stats.avgSpeed.toFixed(2) + '</div>';
          h += '<div class="desc batch-hint">当前最多可征召 ' + maxRecruit + ' 个（受可用平民和资源限制）；兵工厂等级与训练科技只影响生产速度和并行数。</div>';
        }
        h += '</div>';

        // 常驻展示的单价与征召解散操作行
        h += '<div class="cost">单价: ' + costText(Object.assign({ pop: u.pop }, u.cost)) + '</div>';
        if (can) {
          var sliderId = 'slider_' + id;
          var initialVal = maxRecruit > 0 ? 1 : 0;
          var pct = maxRecruit > 0 ? ((initialVal / maxRecruit) * 100).toFixed(1) : 0;
          h += '<div class="recruit-row" onclick="event.stopPropagation()">' +
            '<input class="qty recruit-qty" id="' + inpId + '" type="number" min="0" max="' + maxRecruit + '" value="' + initialVal + '"' + (maxRecruit <= 0 && have <= 0 ? ' disabled' : '') + ' oninput="Game.Army.onInputChange(\'' + id + '\',this.value)" onchange="var v=parseInt(this.value,10);if(isNaN(v)||v<0){this.value=0;}else if(v>' + maxRecruit + '){this.value=' + maxRecruit + ';}Game.Army.onInputChange(\'' + id + '\',this.value);" />' +
            '<div class="recruit-slider-wrap">' +
              '<input type="range" class="recruit-slider" id="' + sliderId + '" min="0" max="' + maxRecruit + '" value="' + initialVal + '"' + (maxRecruit <= 0 ? ' disabled' : '') + ' style="--p:' + pct + '%" oninput="Game.Army.onSliderChange(\'' + id + '\',this.value)" />' +
            '</div>' +
            '<button type="button" class="btn recruit-btn" onclick="Game.Army.recruit(\'' + id + '\',\'' + inpId + '\')">征召</button>';
          if (have > 0) h += '<button type="button" class="btn warn recruit-btn" onclick="Game.Army.openDisbandModal(\'' + id + '\')">解散</button>';
          h += '</div>';
        } else {
          h += '<div class="cost">需先建造 ' + bName + '</div>';
        }
        h += '</div>';
      });
      h += '</div>';
      h += '</div>';

      // 生产队列面板
      h += '<div id="army-panel-queue" style="display:' + (isQueueTab ? 'block' : 'none') + ';">';
      h += '<div id="army-production-queue"><div class="desc">正在加载...</div></div>';
      h += '</div>';

      v.innerHTML = h + '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
      updateQueue(false);
      if (!refreshTimer) refreshTimer = window.setInterval(function () { if (Core.route === 'army') updateQueue(false); }, 5000);
    }
  };

  G.Army = Army;
  Core.views.army = function (v) { Army.renderView(v); };
  G.unitCost = unitCost;
})(window.Game);
