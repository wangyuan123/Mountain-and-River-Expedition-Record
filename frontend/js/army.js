/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';
  G.gameNow = G.gameNow || function () { return Date.now(); };

  var D = G.DATA;
  var Core = G.Core;
  var queue = [];
  var refreshTimer = null;
  var currentTab = 'units';
  var lastRoute = null;
  var expandedUnits = {};
  var disbandModal = null;
  var renderedUnlocks = '';

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

  function costHtml(cost) {
    var arr = [];
    for (var k in cost) {
      if (k === 'pop') arr.push('人' + cost[k]);
      else arr.push(G.resourceIconHtml(k) + cost[k]);
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
    return G.formatDuration(seconds);
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
    var now = G.gameNow();
    if (!queue.length) {
      el.innerHTML = '<div class="desc" style="text-align:center;padding:24px 0;">' +
        '<div style="font-size:14px;color:var(--muted);margin-bottom:12px;">' + (syncError || '暂无进行中的生产队列') + '</div>' +
        '<button type="button" class="btn ok sm" onclick="Game.Army.setTab(\'units\')">前往征召部队</button>' +
      '</div>';
      return;
    }
    // 汇总玩家拥有的加速符
    var s = Core.state || {};
    var owned = [];
    G.Constants.speedUpOrder.forEach(function (k) {
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
      // 只有剩余时间不超过 5 分钟时才显示免费加速；其余情况统一走普通加速流程。
      var speedBtn;
      if (canFreeSpeedUp(item)) {
        speedBtn = '<button type="button" class="btn ok sm army-queue-btn army-free-speedup-btn" title="免费加速" onclick="Game.Army.freeSpeedUp(' + item.id + ')">⚡ 免费加速</button>';
      } else if (totalSpeed > 0) {
        speedBtn = '<button type="button" class="btn ok sm army-queue-btn" title="加速生产" onclick="Game.Army.openSpeedUpPicker(' + item.id + ')">⚡加速</button>';
      } else {
        speedBtn = '<button type="button" class="btn sm army-queue-btn" title="加速生产" onclick="Game.Army.openSpeedUpPicker(' + item.id + ')">⚡加速</button>';
      }
      var iconHtml = (G && typeof G.getUnitModelIconHtml === 'function')
        ? G.getUnitModelIconHtml(item.unitType, unit.name, 'army-queue-icon')
        : (G && typeof G.getUnitIconHtml === 'function' ? G.getUnitIconHtml(item.unitType, unit.name, 'army-queue-icon') : '');
      h += '<div class="menu-item ok army-queue-item" data-unit="' + item.unitType + '" id="army-queue-item-' + item.id + '">' +
        '<div class="army-queue-info">' +
          iconHtml +
          '<div class="army-queue-text">' +
            '<span class="n" title="' + G.escapeHtml(unit.name) + '">' + getUnitDisplayName(unit.name) + ' <b class="army-queue-qty">x' + item.count + '</b></span>' +
            '<span class="lv">' + (item.startedAt > now ? '排队等待 ' + formatSeconds(Math.ceil((item.startedAt - now) / 1000)) + ' · ' : '') + '交付剩余 ' + formatSeconds(left) + '</span>' +
          '</div>' +
        '</div>' +
        '<div class="army-queue-actions">' +
          speedBtn +
          '<button type="button" class="btn warn sm army-queue-btn" title="取消生产" onclick="Game.Army.cancelProduction(' + item.id + ')">取消</button>' +
        '</div>' +
      '</div>';
    });
    el.innerHTML = h;

    var ob = G.Onboarding && G.Onboarding.state;
    var obBar = typeof document !== 'undefined' && document.getElementById ? document.getElementById('onboardingBar') : null;
    if (currentTab === 'queue' && !obBar && ob && !ob.guidedAction && ob.snoozedFor && ob.data && ob.data.current && ob.data.current.id === ob.snoozedFor) {
      var unitType = ob.snoozedFor;
      var currentItem = el.querySelector('.army-queue-item[data-unit="' + unitType + '"]');
      var speedEl = currentItem ? (currentItem.querySelector('.army-free-speedup-btn') || currentItem.querySelector('.army-queue-btn')) : null;
      if (speedEl && G.Onboarding.showSpotlight) {
        G.Onboarding.showSpotlight(speedEl);
      }
    } else if (currentTab === 'units' && !obBar && ob && ob.guidedAction && ob.guidedAction.route === 'army' && ob.guidedAction.building) {
      var uCard = document.getElementById('unit-card-' + ob.guidedAction.building);
      if (uCard) {
        var rBtn = uCard.querySelector('.recruit-btn:not(.warn)') || uCard.querySelector('button.recruit-btn');
        var curTarget = G.Onboarding.getCurrentSpotlightTarget && G.Onboarding.getCurrentSpotlightTarget();
        if (rBtn && curTarget !== rBtn && G.Onboarding.showSpotlight) {
          G.Onboarding.showSpotlight(rBtn);
        }
      }
    } else if (obBar && G.Onboarding && G.Onboarding.clearSpotlight) {
      G.Onboarding.clearSpotlight();
    }
  }

  function canFreeSpeedUp(item) {
    if (!item || !item.finishesAt) return false;
    var rem = Math.max(0, Math.ceil((item.finishesAt - G.gameNow()) / 1000));
    return rem <= 300;
  }

  var freeSpeedUpPending = {};
  function freeSpeedUp(queueId) {
    if (freeSpeedUpPending[queueId]) return Promise.resolve(null);
    freeSpeedUpPending[queueId] = true;
    return G.API.freeArmySpeedUp(queueId).then(function (resp) {
      if (!resp || !resp.success) {
        G.toast((resp && resp.message) || '免费加速失败');
        return;
      }
      G.toast(resp.message || '⚡ 免费加速成功，部队生产完成！');
      if (G.Onboarding && G.Onboarding.clearSpotlight) G.Onboarding.clearSpotlight();
      if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
      if (G.Onboarding && G.Onboarding.refresh) G.Onboarding.refresh();
      updateQueue(false);
      if (Core.route === 'army') Core.render();
    }).catch(function (err) {
      G.toast(err.message || '免费加速失败');
    }).finally(function () {
      delete freeSpeedUpPending[queueId];
    });
  }

  // 弹出加速符选择面板
  function openSpeedUpPicker(queueId) {
    var s = Core.state || {};
    var owned = [];
    G.Constants.speedUpOrder.forEach(function (k) {
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
    var now = G.gameNow();
    var remSec = qItem && qItem.finishesAt ? Math.max(0, Math.ceil((qItem.finishesAt - now) / 1000)) : 0;
    var remText = formatSeconds(remSec);

    // 弹窗：复用 .modal-mask / .modal-card 风格
    var mask = document.createElement('div');
    mask.className = 'modal-mask';
    mask.innerHTML = '<div class="modal-card spicker" style="max-width:350px">'
      + '<div class="spicker-head">⚡ 选择加速符 <span class="spicker-close" onclick="Game.Army.closeSpeedUpPicker()">×</span></div>'
      + '<div class="spicker-sub">目标: ' + G.escapeHtml(unitLabel) + ' · 剩余 ' + remText + '</div>'
      + '<div class="spicker-list"></div>'
      + '<div class="spicker-foot"><button type="button" class="btn sm" onclick="Game.Army.closeSpeedUpPicker()">取消</button></div>'
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

  function showArmyDetailModal() {
    if (typeof document === 'undefined' || !document.createElement || !document.body) return;
    var existing = document.getElementById('armyDetailModal');
    if (existing && existing.parentNode && existing.parentNode.removeChild) {
      existing.parentNode.removeChild(existing);
    }
    var previousFocus = document.activeElement;
    var civ = Core.civilianPopulation ? Core.civilianPopulation() : 0;
    var civCap = Core.populationCapacity ? Core.populationCapacity() : 0;
    var popFree = Core.popFree ? Core.popFree() : 0;
    var popGrowth = Core.populationGrowthPerHour ? Core.populationGrowthPerHour() : 0;
    var totalArmy = Army.totalArmy ? Army.totalArmy() : 0;
    var armyCap = Core.armyCap ? Core.armyCap() : 0;
    var foodPerHour = Core.foodPerHour ? Core.foodPerHour() : 0;
    var cmd = Core.getOfficerByRole ? Core.getOfficerByRole('commander') : null;

    var mask = document.createElement('div');
    mask.id = 'armyDetailModal';
    mask.className = 'modal-mask army-rules-mask';
    mask.innerHTML =
      '<div class="modal-card army-rules-dialog" role="dialog" aria-modal="true" aria-labelledby="armyRulesTitle">' +
        '<div class="modal-title army-rules-title" id="armyRulesTitle">' +
          '<span>🎖 兵种整编与战备规则说明</span>' +
          '<button type="button" class="army-rules-close" aria-label="关闭说明">✕</button>' +
        '</div>' +
        '<div class="modal-body army-rules-body">' +
          '<div class="army-rules-section">' +
            '<div class="army-rules-sec-title">👥 平民人口与征兵返还</div>' +
            '<div class="army-rules-sec-desc">' +
              '• <strong>当前平民状态：</strong>本城平民 <b>' + G.fmt(civ) + ' / ' + G.fmt(civCap) + '</b>，可征召空闲平民 <b>' + G.fmt(popFree) + '</b>，自然增长率 <b>+' + G.fmt(popGrowth) + '/h</b>。<br/>' +
              '• <strong>征召与解散返还：</strong>创建征兵队列时立即扣除对应平民人口；若在生产队列中<strong>取消征兵</strong>或在部队卡片中<strong>解散部队</strong>，将<strong>全额返还</strong>平民人口。<br/>' +
              '• <strong>人口增长机制：</strong>民居等级决定平民上限，保持城市低怨恨度与充足粮食可保障人口高速增长。' +
            '</div>' +
          '</div>' +
          '<div class="army-rules-section">' +
            '<div class="army-rules-sec-title">🪖 编制规模与军事指挥官</div>' +
            '<div class="army-rules-sec-desc">' +
              '• <strong>带兵上限：</strong>当前本城驻军 <b>' + G.fmt(totalArmy) + ' / ' + G.fmt(armyCap) + '</b>。带兵上限受城市指挥所等级与统帅科技共同影响。<br/>' +
              '• <strong>指挥官任命：</strong>' + (cmd ? '当前已任命【<b>' + G.escapeHtml(cmd.name) + '</b>】担任军事指挥官。' : '当前<b>未任命军事指挥官</b>。') + '任命指挥官可依据其统御值大幅提升本城部队编制上限，并在城市保卫战中提供攻击、防御等全军战力加成。' +
            '</div>' +
          '</div>' +
          '<div class="army-rules-section">' +
            '<div class="army-rules-sec-title">🌾 养兵粮饷与维持消耗</div>' +
            '<div class="army-rules-sec-desc">' +
              '• <strong>常驻粮耗：</strong>现役驻军每小时消耗粮食 <b>-' + G.fmt(foodPerHour) + '/h</b>。<br/>' +
              '• <strong>断粮惩罚：</strong>当城市粮食储量降为 0 时，全军陷入饥饿状态，部队士气与战斗力将大幅衰减（降低 50% 以上），请注意保持粮仓储备充足。' +
            '</div>' +
          '</div>' +
          '<div class="army-rules-section">' +
            '<div class="army-rules-sec-title">🎯 四维打击与协同克制</div>' +
            '<div class="army-rules-sec-desc">' +
              '• <strong>多维打击：</strong>战斗包含【对地】、【对空】、【对海】、【对工事】四类独立打击属性。<br/>' +
              '• <strong>协同搭配：</strong>所有兵种均具备基础自卫火力，但在非擅长领域攻击效率极低；建议出征与驻防时搭配防空机炮、装甲护航与攻坚单位，避免单一兵种被克制歼灭。' +
            '</div>' +
          '</div>' +
          '<div class="army-rules-section">' +
            '<div class="army-rules-sec-title">⚡ 行军补给与速度限制</div>' +
            '<div class="army-rules-sec-desc">' +
              '• <strong>行军速度：</strong>混合编队的大地图行军速度取决于编队中<strong>最慢兵种</strong>；长途奔袭或紧急拦截建议选用高机动轻装部队。<br/>' +
              '• <strong>行军补给：</strong>部队外出作战需按格数持续消耗燃油，按耗时消耗口粮，出征前请确保物资配给充盈。' +
            '</div>' +
          '</div>' +
        '</div>' +
        '<div class="modal-foot army-rules-foot">' +
          '<button type="button" class="btn ok army-rules-confirm-btn">我知道了</button>' +
        '</div>' +
      '</div>';
    document.body.appendChild(mask);

    var close = function () {
      if (document.removeEventListener) document.removeEventListener('keydown', onKeydown);
      if (mask.parentNode && mask.parentNode.removeChild) mask.parentNode.removeChild(mask);
      if (previousFocus && previousFocus.focus) previousFocus.focus();
    };
    var onKeydown = function (e) { if (e.key === 'Escape') close(); };
    var closeBtn = mask.querySelector ? mask.querySelector('.army-rules-close') : null;
    var confirmBtn = mask.querySelector ? mask.querySelector('.army-rules-confirm-btn') : null;
    if (closeBtn) closeBtn.onclick = close;
    if (confirmBtn) confirmBtn.onclick = close;
    if (mask.addEventListener) mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
    if (document.addEventListener) document.addEventListener('keydown', onKeydown);
    if (confirmBtn && confirmBtn.focus) confirmBtn.focus();
  }

  var Army = {
    setTab: setTab,
    showArmyDetailModal: showArmyDetailModal,
    showArmyRulesModal: showArmyDetailModal,
    canFreeSpeedUp: canFreeSpeedUp,
    freeSpeedUp: freeSpeedUp,
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
      var prerequisites = G.Prerequisites && G.Prerequisites.checkRecruitment(id);
      if (!prerequisites) { G.toast('兵种条件待同步，请刷新后重试'); return; }
      if (prerequisites.missing.length) {
        G.toast(prerequisites.missing.map(G.Prerequisites.missingText).join('；'));
        return;
      }
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
        if (G.Onboarding && G.Onboarding.actionStarted) G.Onboarding.actionStarted('army', id);
        return updateQueue(false);
      }).catch(function (err) { G.toast(err.message || '征召失败'); });
    },

    cancelProduction: function (queueId) {
      if (!window.confirm('确定要取消这项生产吗？取消后将返还资源与平民。')) return;
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
      var card = typeof document !== 'undefined' && document.getElementById ? document.getElementById('unit-card-' + id) : null;
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
      }
    },

    /** 只在资格快照变化后重绘兵种卡片，保留展开状态与生产队列标签。 */
    refreshUnlocks: function () {
      if (Core.route !== 'army' || renderedUnlocks === JSON.stringify((Core.state || {}).unitUnlocks || {})) return;
      Core.refreshContent();
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

      var civ = Core.civilianPopulation();
      var civCap = Core.populationCapacity();
      var popFree = Core.popFree();
      var popGrowth = Core.populationGrowthPerHour();
      var totalArmy = this.totalArmy();
      var armyCap = Core.armyCap();
      var foodPerHour = Core.foodPerHour();

      var h = '<div class="title">- 兵种整编 -</div>';
      h += '<div class="army-overview-card">' +
        '<div class="army-ov-header">' +
          '<div class="army-ov-title-wrap">' +
            '<span class="army-ov-badge">战备概览</span>' +
            '<span class="army-ov-cmd" title="' + (cmd ? ('守城指挥官: ' + G.escapeHtml(cmd.name)) : '当前城市未任命指挥官') + '">' +
              (cmd ? ('🎖 指挥官: <b>' + G.escapeHtml(cmd.name) + '</b>') : '⚠ <span class="army-ov-cmd-none">未任命指挥官</span>') +
            '</span>' +
          '</div>' +
          '<button type="button" class="army-rules-btn" onclick="Game.Army.showArmyDetailModal()" title="查看兵种整编与战备规则说明">' +
            '<span>查看详情说明</span>' +
            '<span class="army-rules-icon">ℹ</span>' +
          '</button>' +
        '</div>' +
        '<div class="army-ov-grid">' +
          '<div class="army-ov-cell">' +
            '<div class="army-ov-k">平民人口</div>' +
            '<div class="army-ov-v"><span class="army-ov-main">' + G.fmt(civ) + '</span><span class="army-ov-cap">/' + G.fmt(civCap) + '</span></div>' +
            '<div class="army-ov-sub">可征 ' + G.fmt(popFree) + ' · +' + G.fmt(popGrowth) + '/h</div>' +
          '</div>' +
          '<div class="army-ov-cell">' +
            '<div class="army-ov-k">现役部队</div>' +
            '<div class="army-ov-v"><span class="army-ov-main">' + G.fmt(totalArmy) + '</span><span class="army-ov-cap">/' + G.fmt(armyCap) + '</span></div>' +
            '<div class="army-ov-sub">' + (cmd ? '将领统率' : '守备编制') + '</div>' +
          '</div>' +
          '<div class="army-ov-cell">' +
            '<div class="army-ov-k">养兵耗粮</div>' +
            '<div class="army-ov-v army-ov-food">-' + G.fmt(foodPerHour) + '<small>/h</small></div>' +
            '<div class="army-ov-sub">驻军开销</div>' +
          '</div>' +
        '</div>' +
      '</div>';

      h += '<div class="army-tabs">' +
        '<div id="army-tab-units" class="army-tab' + (!isQueueTab ? ' active' : '') + '" onclick="Game.Army.setTab(\'units\')">军队</div>' +
        '<div id="army-tab-queue" class="army-tab' + (isQueueTab ? ' active' : '') + '" onclick="Game.Army.setTab(\'queue\')">生产队列 <span id="army-queue-badge" class="army-tab-badge" style="display:' + badgeStyle + '">' + badgeText + '</span></div>' +
        '<button class="army-tab" onclick="Game.go(\'wounded\')">伤兵营' + (s.woundedCount ? ' (' + G.fmt(s.woundedCount) + ')' : '') + '</button>' +
        '<button class="army-tab" onclick="Game.go(\'prisoners\')">俘虏池' + (s.prisonerCount ? ' (' + G.fmt(s.prisonerCount) + ')' : '') + '</button>' +
      '</div>';

      // 军队面板（所有兵种直接合并展示，取消分类分区）
      h += '<div id="army-panel-units" style="display:' + (!isQueueTab ? 'block' : 'none') + ';">';
      h += '<div class="menu">';
      var allUnits = G.Constants.armyUnitOrder;
      allUnits.forEach(function (id) {
        var u = D.units[id];
        if (!u) return;
        var have = (s.army && s.army[id]) || 0;
        var stats = getBuildStats(u.build);
        var sumLv = stats.sumLevel;
        var prerequisites = G.Prerequisites && G.Prerequisites.checkRecruitment(id);
        var can = stats.maxBatch > 0 && (prerequisites && prerequisites.eligible);
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
        var battleSpd = (u.spd || 0) * 50;
        var secPerGrid = (D.world && D.world.marchSecPerGrid) || 18;
        var marchSec = (secPerGrid / (u.spd || 1)).toFixed(1).replace(/\.0$/, '');
        var spdTip = '战场单回合机动：' + battleSpd + ' 距离（基速档位 ' + (u.spd || 0) + '）；大地图基础行军：' + marchSec + '秒/格';
        h += '<div class="d">对地' + u.atkGround + ' 对空' + u.atkAir + ' 对海' + u.atkSea + ' 对工事' + u.atkFort + ' 防' + u.def + ' 血' + u.hp + ' <span title="' + spdTip + '">移速' + battleSpd + '</span> 射程' + u.range + ' 常驻耗粮' + u.food + '/h</div>';
        h += '<div class="d">火力密度：行动火力 ×' + Core.firepowerMul().toFixed(1) + '，与攻击科技独立相乘</div>';
        h += '<div class="d">行军补给：油耗' + (u.marchOil || 0) + '/100格，粮耗' + (u.marchFood || 0) + '/5分钟</div>';
        if (can) {
          var parallel = stats.parallel;
          h += '<div class="d unit-prod-stat">生产: 基础 ' + (30 + Math.floor(((u.cost.food || 0) + u.cost.steel + u.cost.oil + u.cost.rare) / 10)) + '秒/个，并行 ' + parallel + ' 条 (按栋独立), 平均速度 ×' + stats.avgSpeed.toFixed(2) + '</div>';
          h += '<div class="desc batch-hint">当前最多可征召 ' + maxRecruit + ' 个（受可用平民和资源限制）；本城设施与已完成科技共同决定兵种准入，生产设施与训练科技影响速度和并行数。</div>';
        }
        h += '</div>';

        // 配装摘要不随属性详情折叠，配置归属账号而非本城兵数。
        if (G.UnitEquipment) h += G.UnitEquipment.summary(id, true);

        // 所有兵种展示同一服务端评估的全部条件与当前进度。
        if (G.Prerequisites) h += G.Prerequisites.recruitmentHtml(id);

        // 常驻展示的单价与征召解散操作行
        h += '<div class="cost">单价: ' + costHtml(Object.assign({ pop: u.pop }, u.cost)) + '</div>';
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
          h += '<div class="cost">' + (prerequisites && prerequisites.missing.length
            ? prerequisites.missing.map(G.Prerequisites.missingText).join('；') : prerequisites ? '生产设施尚未可用' : '兵种条件待同步') + '</div>';
          if (have > 0) h += '<button type="button" class="btn warn recruit-btn" onclick="Game.Army.openDisbandModal(\'' + id + '\')">解散</button>';
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
      renderedUnlocks = JSON.stringify(s.unitUnlocks || {});
      updateQueue(false);
      if (!refreshTimer) refreshTimer = window.setInterval(function () { if (Core.route === 'army') updateQueue(false); }, 5000);
    }
  };

  G.Army = Army;
  Core.views.army = function (v) { Army.renderView(v); };
  G.unitCost = unitCost;
})(window.Game);
