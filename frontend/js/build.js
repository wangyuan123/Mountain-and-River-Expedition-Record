/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;
  var Core = G.Core;

  function buildCost(id, fromLevel) {
    var b = D.buildings[id];
    var lv = (fromLevel == null) ? (Core.state.buildings[id] || 0) : fromLevel;
    var mul = Math.pow(b.growth, lv) * Core.buildMul();
    var cost = {};
    for (var k in b.baseCost) cost[k] = Math.floor(b.baseCost[k] * mul);
    return cost;
  }

  function buildMaxLevel(id) {
    if (id === 'command') return 10;
    return Math.min(10, (Core.state.buildings.command || 1));
  }

  function buildDuration(id, fromLevel) {
    var lv = ((fromLevel == null) ? (Core.state.buildings[id] || 0) : fromLevel) + 1;
    var techMul = Math.max(0.5, Core.buildMul());
    // 30秒基础, 每级 ×2.4, 封顶 24 小时
    //   Lv.1=30s  Lv.2=1.2分  Lv.3=2.9分  Lv.4=6.9分  Lv.5=16.6分
    //   Lv.6=40分  Lv.7=1.6时  Lv.8=3.8时   Lv.9=9.2时  Lv.10=22时
    var sec = 30 * Math.pow(2.4, lv - 1);
    return Math.min(86400, Math.ceil(sec * techMul));
  }

  function costText(cost) {
    var arr = [];
    var emojiMap = (G.DATA && G.DATA.resEmoji) || {};
    for (var k in cost) {
      // 纯文本按钮场景: 统一用 emoji, 不再用 icon 字段(可能是图片路径),
      // 否则会渲染成 "🛢️ img/oil.svg160" 这种字符串。
      var ico = emojiMap[k] || G.DATA.resources[k].icon || k;
      arr.push(ico + cost[k]);
    }
    return arr.join(' ');
  }

  function timeText(seconds) {
    seconds = Math.max(0, Math.ceil(seconds));
    var days = Math.floor(seconds / 86400);
    var hours = Math.floor((seconds % 86400) / 3600);
    var mins = Math.floor((seconds % 3600) / 60);
    var secs = seconds % 60;
    if (days > 0) return days + '天' + hours + '小时' + mins + '分';
    if (hours > 0) return hours + '小时' + mins + '分' + (secs > 0 ? (String(secs).padStart(2, '0') + '秒') : '');
    if (mins > 0) return mins + '分' + String(secs).padStart(2, '0') + '秒';
    return secs + '秒';
  }

  var Build = {
    init: function () {},

    getConstructions: function () {
      var s = Core.state;
      if (!Array.isArray(s.constructions)) {
        s.constructions = s.construction ? [s.construction] : [];
        s.construction = null;
      }
      return s.constructions;
    },

    upgrade: function (id, slotIdx) {
      var slot = (slotIdx != null) ? slotIdx : null;
      var self = this;
      G.API.buildUpgrade(id, slot).then(function (resp) {
        // 必须先检查后端 success 字段：失败时（如 slot 错位/已满/资源不足），
        // HTTP 仍然 200，但后端返回 success:false + message。
        // 之前的实现无条件 toast "开始升级" 误导玩家。
        if (resp && resp.success === false) {
          G.toast(resp.message || '升级失败');
          // 服务端失败时仍会附带最新 state，确保 UI 与数据库一致
          if (resp.state) G.API.applyState(resp.state);
          return;
        }
        G.toast(D.buildings[id].name + ' 开始升级');
        // 升级开始(注意：每日任务的"升级建筑"任务要求等真完成才 +1，
        // 这里只触发 BUILD_START, 不再直接 onEvent('build')。
        if (G.Task && G.Task.Quests) G.Task.Quests.onEvent('BUILD_START');
        if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '升级失败');
      });
    },

    /**
     * 施工详情弹窗 (展示当前建筑升级进度、剩余时间、加速与取消操作)
     */
    showJobDetails: function (buildingId, slotIdx) {
      var jobs = (Core.state.constructions || []).slice().sort(function (a, b) { return (a.finishesAt || 0) - (b.finishesAt || 0); });
      var job = null;
      var jobIdx = -1;
      for (var i = 0; i < jobs.length; i++) {
        if (jobs[i].id === buildingId) {
          if (slotIdx == null || jobs[i].slot === slotIdx) {
            job = jobs[i];
            jobIdx = i;
            break;
          }
        }
      }
      if (!job) {
        G.toast('该建筑当前未在施工中');
        return;
      }

      var b = D.buildings[job.id] || { name: job.id };
      var jobName = b.name;
      if (job.slot != null) jobName += ' #' + (job.slot + 1);

      var targetLv = job.targetLevel != null ? job.targetLevel : 1;
      var fromLv = targetLv > 1 ? (targetLv - 1) : 0;
      var lvText = fromLv === 0 ? ('新建为 Lv.' + targetLv) : ('Lv.' + fromLv + ' → Lv.' + targetLv);

      var now = Date.now();
      var remainSec = Math.max(0, Math.ceil((job.finishesAt - now) / 1000));
      var totalSec = (job.startedAt && job.finishesAt > job.startedAt) ? Math.max(1, Math.round((job.finishesAt - job.startedAt) / 1000)) : 0;
      var pct = 0;
      if (totalSec > 0) {
        var elapsedSec = Math.max(0, totalSec - remainSec);
        pct = Math.min(100, Math.max(0, Math.round((elapsedSec / totalSec) * 100)));
      } else {
        pct = remainSec === 0 ? 100 : 50;
      }

      var d = new Date(job.finishesAt);
      var pad = function (n) { return n < 10 ? '0' + n : '' + n; };
      var finishTimeStr = pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());

      // 汇总加速符数量
      var speedOrder = ['speedUp10m','speedUp1h','speedUp5h','speedUp12h','speedUp24h','speedUp36h','speedUp48h','speedUp72h'];
      var speedTotal = 0;
      for (var si = 0; si < speedOrder.length; si++) {
        speedTotal += (Core.state.items && Core.state.items[speedOrder[si]]) || 0;
      }

      var speedBtnHtml = speedTotal > 0
        ? '<button class="btn ok" id="detailSpeedUp">⚡ 加速 (×' + speedTotal + ')</button>'
        : '<button class="btn" disabled title="可在商城购买加速符">⚡ 加速 (无)</button>';

      var mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.innerHTML =
        '<div class="modal-card" style="max-width:340px">' +
          '<div class="modal-title">🏗 施工详情</div>' +
          '<div class="modal-body" style="font-size:13px">' +
            '<div class="cu-row cu-head">' +
              '<span class="cu-name">' + jobName + '</span>' +
              '<span class="cu-val" style="color:var(--accent,#526b4d)">' + lvText + '</span>' +
            '</div>' +
            '<div class="cu-row">' +
              '<span class="cu-ico">👷</span>' +
              '<span class="cu-name">施工队伍</span>' +
              '<span class="cu-val">施工队 ' + (jobIdx >= 0 ? (jobIdx + 1) : 1) + '</span>' +
            '</div>' +
            '<div class="cu-row">' +
              '<span class="cu-ico">⏱</span>' +
              '<span class="cu-name">剩余时间</span>' +
              '<span class="cu-val" id="detailRemainTime" style="font-weight:bold;color:var(--gold,#b3832f)">' + timeText(remainSec) + '</span>' +
            '</div>' +
            (totalSec > 0 ? (
              '<div class="cu-row">' +
                '<span class="cu-ico">⏳</span>' +
                '<span class="cu-name">总工期</span>' +
                '<span class="cu-val">' + timeText(totalSec) + '</span>' +
              '</div>'
            ) : '') +
            '<div class="cu-row">' +
              '<span class="cu-ico">🏁</span>' +
              '<span class="cu-name">预计完成</span>' +
              '<span class="cu-val">' + finishTimeStr + '</span>' +
            '</div>' +
            '<div style="margin:12px 0 4px">' +
              '<div style="display:flex;justify-content:space-between;font-size:12px;color:#777;margin-bottom:4px">' +
                '<span>施工进度</span>' +
                '<span id="detailPctText">' + pct + '%</span>' +
              '</div>' +
              '<div style="background:rgba(0,0,0,0.08);border-radius:6px;overflow:hidden;height:10px">' +
                '<div id="detailPctBar" style="background:var(--accent,#526b4d);width:' + pct + '%;height:100%;transition:width .3s"></div>' +
              '</div>' +
            '</div>' +
          '</div>' +
          '<div class="modal-foot">' +
            '<button class="btn warn" id="detailCancel">取消升级</button>' +
            speedBtnHtml +
            '<button class="btn" id="detailClose">关闭</button>' +
          '</div>' +
        '</div>';
      document.body.appendChild(mask);

      var close = function () {
        clearInterval(timer);
        if (mask.parentNode) mask.parentNode.removeChild(mask);
      };
      mask.querySelector('#detailClose').onclick = close;
      mask.addEventListener('click', function (e) { if (e.target === mask) close(); });

      var speedBtn = mask.querySelector('#detailSpeedUp');
      if (speedBtn) {
        speedBtn.onclick = function () {
          close();
          Game.Build.openSpeedUpPicker(job);
        };
      }

      var cancelBtn = mask.querySelector('#detailCancel');
      if (cancelBtn) {
        cancelBtn.onclick = function () {
          if (window.confirm('确定要取消该建筑的升级吗？取消后返还部分资源。')) {
            close();
            Game.Build.cancel(job.id, job.slot);
          }
        };
      }

      var timer = setInterval(function () {
        if (!mask.parentNode) { clearInterval(timer); return; }
        var curNow = Date.now();
        var curRemain = Math.max(0, Math.ceil((job.finishesAt - curNow) / 1000));
        var remainEl = mask.querySelector('#detailRemainTime');
        if (remainEl) remainEl.textContent = timeText(curRemain);
        if (totalSec > 0) {
          var curElapsed = Math.max(0, totalSec - curRemain);
          var curPct = Math.min(100, Math.max(0, Math.round((curElapsed / totalSec) * 100)));
          var pctText = mask.querySelector('#detailPctText');
          if (pctText) pctText.textContent = curPct + '%';
          var pctBar = mask.querySelector('#detailPctBar');
          if (pctBar) pctBar.style.width = curPct + '%';
        }
        if (curRemain <= 0) {
          clearInterval(timer);
          close();
          Core.render();
        }
      }, 1000);
    },

    /**
     * 施工队全忙弹窗 (展示占用队伍的建筑与倒计时)
     */
    showBusyJobs: function () {
      var jobs = (Core.state.constructions || []).slice().sort(function (a, b) { return (a.finishesAt || 0) - (b.finishesAt || 0); });
      Build._busyJobs = jobs;
      var now = Date.now();
      var mask = document.createElement('div');
      mask.className = 'modal-mask';

      var speedOrder = ['speedUp10m','speedUp1h','speedUp5h','speedUp12h','speedUp24h','speedUp36h','speedUp48h','speedUp72h'];
      var speedTotal = 0;
      for (var si = 0; si < speedOrder.length; si++) {
        speedTotal += (Core.state.items && Core.state.items[speedOrder[si]]) || 0;
      }

      var rows = '';
      for (var i = 0; i < jobs.length; i++) {
        var job = jobs[i];
        var jobDef = D.buildings[job.id] || { name: job.id };
        var jobName = jobDef.name;
        if (job.slot != null) jobName += ' #' + (job.slot + 1);
        var remainSec = Math.max(0, Math.ceil((job.finishesAt - now) / 1000));
        var speedBtn = speedTotal > 0
          ? '<button class="btn sm ok" style="padding:2px 8px;font-size:12px" onclick="Game.Build._busySpeedUp(' + i + ')">⚡ 加速</button>'
          : '<button class="btn sm" disabled style="padding:2px 8px;font-size:12px">⚡ 加速(无)</button>';
        rows +=
          '<div style="background:rgba(0,0,0,0.03);border:1px solid rgba(0,0,0,0.06);border-radius:6px;padding:8px 10px;margin-bottom:8px">' +
            '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:4px">' +
              '<span style="font-weight:bold;color:#333">施工队 ' + (i + 1) + '：' + jobName + '</span>' +
              '<span style="color:var(--accent,#526b4d);font-weight:600">Lv.' + (job.targetLevel || 1) + '</span>' +
            '</div>' +
            '<div style="display:flex;justify-content:space-between;align-items:center;font-size:12px">' +
              '<span style="color:#777">剩余时间: <b class="busy-remain-' + i + '" style="color:var(--gold,#b3832f)">' + timeText(remainSec) + '</b></span>' +
              '<div style="display:flex;gap:4px">' +
                speedBtn +
                '<button class="btn sm warn" style="padding:2px 8px;font-size:12px" onclick="Game.Build._busyCancel(\'' + job.id + '\',' + (job.slot == null ? 'null' : job.slot) + ')">取消</button>' +
              '</div>' +
            '</div>' +
          '</div>';
      }

      mask.innerHTML =
        '<div class="modal-card" style="max-width:340px">' +
          '<div class="modal-title">🏗 施工队全忙</div>' +
          '<div class="modal-body" style="font-size:13px">' +
            '<div class="cu-warn" style="margin-top:0;margin-bottom:10px">两支施工队均在作业中，无法开始新工程。请等待完工或使用加速符：</div>' +
            rows +
          '</div>' +
          '<div class="modal-foot">' +
            '<button class="btn" id="busyClose">关闭</button>' +
          '</div>' +
        '</div>';
      document.body.appendChild(mask);

      var close = function () {
        clearInterval(timer);
        if (mask.parentNode) mask.parentNode.removeChild(mask);
        Build._busyMask = null;
      };
      Build._busyMask = mask;
      mask.querySelector('#busyClose').onclick = close;
      mask.addEventListener('click', function (e) { if (e.target === mask) close(); });

      var timer = setInterval(function () {
        if (!mask.parentNode) { clearInterval(timer); return; }
        var curNow = Date.now();
        var allDone = true;
        for (var k = 0; k < jobs.length; k++) {
          var sRemain = Math.max(0, Math.ceil((jobs[k].finishesAt - curNow) / 1000));
          var el = mask.querySelector('.busy-remain-' + k);
          if (el) el.textContent = timeText(sRemain);
          if (sRemain > 0) allDone = false;
        }
        if (allDone) { close(); Core.render(); }
      }, 1000);
    },

    _busySpeedUp: function (jobIdx) {
      var targetJob = (Build._busyJobs && Build._busyJobs[jobIdx]) || (Core.state.constructions || [])[jobIdx];
      if (Build._busyMask && Build._busyMask.parentNode) {
        Build._busyMask.parentNode.removeChild(Build._busyMask);
      }
      Build._busyMask = null;
      Game.Build.openSpeedUpPicker(targetJob);
    },

    _busyCancel: function (building, slot) {
      if (window.confirm('确定要取消该工程吗？取消后返还部分资源。')) {
        if (Build._busyMask && Build._busyMask.parentNode) {
          Build._busyMask.parentNode.removeChild(Build._busyMask);
        }
        Build._busyMask = null;
        Game.Build.cancel(building, slot);
      }
    },

    /**
     * 升级前的二次确认弹窗
     * - 展示目标等级、所需资源、工期、当前持有资源
     * - 资源不足时"确认"按钮灰掉,点击给 toast 提示
     * - 资源足够时点击确认才真正调 upgrade()
     */
    confirmUpgrade: function (id, slotIdx) {
      var b = D.buildings[id];
      if (!b) return;

      // 若当前建筑已在施工中，直接转到施工详情
      var jobs = this.getConstructions();
      if (this.isBuilding(jobs, id, slotIdx)) {
        this.showJobDetails(id, slotIdx);
        return;
      }
      // 若施工队全满，转到施工队全忙提示
      if (jobs.length >= 2) {
        this.showBusyJobs();
        return;
      }

      // 计算当前 slot 的等级和目标等级
      var fromLv, label;
      if (slotIdx == null) {
        fromLv = Core.state.buildings[id] || 0;
        label = b.name;
      } else {
        var arrRaw = Core.state.buildings[id];
        var arr = Array.isArray(arrRaw) ? arrRaw : [];
        fromLv = arr[slotIdx] || 0;
        label = '#' + (slotIdx + 1) + ' ' + b.name;
      }
      var toLv = fromLv + 1;
      var max = buildMaxLevel(id);
      if (fromLv >= max) {
        G.toast(b.name + ' 已达最大等级');
        return;
      }

      var cost = buildCost(id, fromLv);
      var duration = buildDuration(id, fromLv);
      var enough = Core.costEnough(cost);

      // 当前资源快照,用于在弹窗里给玩家做"够/不够"的直观对比
      var emojiMap = (G.DATA && G.DATA.resEmoji) || {};
      var costRows = '';
      var cur = Core.state.resources || {};
      for (var k in cost) {
        var resName = (G.DATA.resources[k] && G.DATA.resources[k].name) || k;
        var ico = emojiMap[k] || (G.DATA.resources[k] && G.DATA.resources[k].icon) || k;
        var need = cost[k];
        var have = cur[k] || 0;
        var ok = have >= need;
        costRows +=
          '<div class="cu-row">' +
            '<span class="cu-ico">' + ico + '</span>' +
            '<span class="cu-name">' + resName + '</span>' +
            '<span class="cu-val' + (ok ? '' : ' cu-short') + '">需 ' + G.fmt(need) +
              ' <span class="cu-sub">(现 ' + G.fmt(have) + ')</span>' +
            '</span>' +
          '</div>';
      }

      // 工期展示: 单建筑升级在弹窗里也给出,玩家常问"我等多久"
      var durHtml = '<div class="cu-row"><span class="cu-ico">⏱</span><span class="cu-name">工期</span>' +
                    '<span class="cu-val">' + timeText(duration) + '</span></div>';

      var mask = document.createElement('div');
      mask.className = 'modal-mask';
      // 文案根据 fromLv 自适应：Lv.0 视为"新建"，其余视为"升级"
      var isNew = fromLv === 0;
      var title = isNew ? '🏗 新建确认' : '🏗 升级确认';
      var headText = isNew
        ? (label + '  新建为 Lv.' + toLv)
        : (label + '  Lv.' + fromLv + ' → Lv.' + toLv);
      var warnText = isNew ? '⚠ 资源不足,无法新建' : '⚠ 资源不足,无法升级';
      var okText = isNew ? '确认新建' : '确认升级';
      mask.innerHTML =
        '<div class="modal-card" style="max-width:340px">' +
          '<div class="modal-title">' + title + '</div>' +
          '<div class="modal-body" style="font-size:13px">' +
            '<div class="cu-row cu-head">' +
              '<span class="cu-name">' + headText + '</span>' +
            '</div>' +
            costRows +
            durHtml +
            (enough ? '' :
              '<div class="cu-warn">' + warnText + '</div>'
            ) +
          '</div>' +
          '<div class="modal-foot">' +
            '<button class="btn" id="cuCancel">取消</button>' +
            '<button class="btn ok" id="cuOk"' + (enough ? '' : ' disabled') + '>' + okText + '</button>' +
          '</div>' +
        '</div>';
      document.body.appendChild(mask);

      var close = function () { if (mask.parentNode) mask.parentNode.removeChild(mask); };
      var okBtn = mask.querySelector('#cuOk');
      var cancelBtn = mask.querySelector('#cuCancel');
      cancelBtn.onclick = close;
      mask.addEventListener('click', function (e) { if (e.target === mask) close(); });

      okBtn.onclick = function () {
        // 二次校验: 资源可能在玩家停留弹窗期间被扣减(其他操作/被掠夺)
        var liveCost = buildCost(id, fromLv);
        if (!Core.costEnough(liveCost)) {
          G.toast(isNew ? '资源不足,无法新建' : '资源不足,无法升级');
          // 不要立即关弹窗, 让玩家看到自己现在缺哪些; 但因为数字已红, 直接关掉体验也行
          close();
          return;
        }
        close();
        // 走真正的升级流程
        Build.upgrade(id, slotIdx == null ? null : slotIdx);
      };
    },

    cancel: function (building, slot) {
      G.API.buildCancel(building, slot).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '取消失败');
          if (resp.state) G.API.applyState(resp.state);
          return;
        }
        G.toast('已取消升级');
        if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '取消失败');
      });
    },

    exchange: function () {
      var s = Core.state;
      var lv = s.buildings.exchange || 0;
      var rate = 0.5 + lv * 0.05;
      var fromEl = document.getElementById('exFrom');
      var toEl = document.getElementById('exTo');
      var amtEl = document.getElementById('exAmt');
      if (!fromEl || !toEl || !amtEl) { G.toast('请填写兑换信息'); return; }
      var fromKey = fromEl.value;
      var toKey = toEl.value;
      if (fromKey === toKey) { G.toast('源与目标相同'); return; }
      var amt = parseInt(amtEl.value, 10);
      if (isNaN(amt) || amt <= 0) { G.toast('数量无效'); return; }
      if ((s.resources[fromKey] || 0) < amt) { G.toast(G.DATA.resources[fromKey].name + '不足'); return; }
      var gain = Math.floor(amt * rate);
      s.resources[fromKey] -= amt;
      s.resources[toKey] = (s.resources[toKey] || 0) + gain;
      G.toast('转换: ' + G.DATA.resources[fromKey].name + amt + ' -> ' + G.DATA.resources[toKey].name + gain);
      Core.render();
    },

    exchangePanel: function () {
      var s = Core.state;
      var lv = s.buildings.exchange || 0;
      var rate = (0.5 + lv * 0.05).toFixed(2);
      var h = '<div class="menu-item ok"><span class="n">资源互换</span> <span class="d">兑换比 ' + rate + '</span>';
      h += '<div class="btn-row" style="flex-wrap:wrap">';
      h += '<select class="qty" id="exFrom"><option value="food">粮</option><option value="steel">钢</option><option value="oil">油</option><option value="rare">稀</option></select>';
      h += '<span style="color:#ffe14a;padding:0 4px">-></span>';
      h += '<select class="qty" id="exTo"><option value="steel">钢</option><option value="food">粮</option><option value="oil">油</option><option value="rare">稀</option></select>';
      h += '</div>';
      h += '<div class="btn-row" style="margin-top:6px">';
      h += '<input class="qty" id="exAmt" type="number" min="1" value="100" />';
      h += '<button class="btn" onclick="Game.Build.exchange()">兑换</button>';
      h += '</div>';
      h += '</div>';

      h += '<div class="zone-head">上架资源到交易所</div>';
      h += '<div class="menu-item ok">';
      var depotLevels = Core.buildingLevels('depot');
      var depotSlots = depotLevels.filter(function(lv){ return lv > 0; }).length;
      var listCap = depotSlots + 1;
      s.exchangeList = s.exchangeList || [];
      var usedSlots = s.exchangeList.length;
      h += '<div class="d">上架资源供其他玩家购买，可设置出售数量和单价(黄金)</div>';
      h += '<div class="d" style="color:var(--gold)">上架槽位: ' + usedSlots + '/' + listCap + ' (仓库已建' + depotSlots + '槽，基础1+每槽+1)</div>';
      var canList = usedSlots < listCap;
      h += '<div class="btn-row" style="flex-wrap:wrap">';
      h += '<select class="qty" id="listRes"' + (canList ? '' : ' disabled') + '><option value="food">粮</option><option value="steel">钢</option><option value="oil">油</option><option value="rare">稀</option></select>';
      h += '<input class="qty" id="listAmt" type="number" min="1" value="100" placeholder="数量" style="width:70px"' + (canList ? '' : ' disabled') + ' />';
      h += '<input class="qty" id="listPrice" type="number" min="1" value="50" placeholder="单价" style="width:70px"' + (canList ? '' : ' disabled') + ' />';
      h += '<button class="btn sm"' + (canList ? '' : ' disabled') + ' onclick="Game.Build.listResource()">' + (canList ? '上架' : '槽位已满') + '</button>';
      h += '</div>';
      h += '</div>';

      s.exchangeList = s.exchangeList || [];
      h += '<div class="zone-head">我的上架列表 (' + s.exchangeList.length + ')</div>';
      h += '<div class="menu">';
      if (!s.exchangeList.length) {
        h += '<div class="desc">暂无上架资源</div>';
      } else {
        for (var i = 0; i < s.exchangeList.length; i++) {
          var item = s.exchangeList[i];
          var resName = G.DATA.resources[item.resKey] ? G.DATA.resources[item.resKey].name : item.resKey;
          h += '<div class="menu-item ok">';
          h += '<span class="n">' + resName + ' x' + item.amount + '</span> <span class="lv">单价' + item.price + '金</span>';
          h += '<div class="d">总价: ' + (item.amount * item.price) + '黄金  上架时间: ' + new Date(item.time).toLocaleString() + '</div>';
          h += '<div class="btn-row"><button class="btn sm warn" onclick="Game.Build.unlistResource(' + i + ')">下架</button></div>';
          h += '</div>';
        }
      }
      h += '</div>';

      var box = document.getElementById('exPanel');
      if (box) box.innerHTML = h; else G.toast('面板异常');
    },

    listResource: function () {
      var s = Core.state;
      var depotLevels = Core.buildingLevels('depot');
      var depotSlots = depotLevels.filter(function(lv){ return lv > 0; }).length;
      var listCap = depotSlots + 1;
      s.exchangeList = s.exchangeList || [];
      if (s.exchangeList.length >= listCap) { G.toast('上架槽位已满(仓库已建' + depotSlots + '槽，上限' + listCap + ')'); return; }
      var resEl = document.getElementById('listRes');
      var amtEl = document.getElementById('listAmt');
      var priceEl = document.getElementById('listPrice');
      if (!resEl || !amtEl || !priceEl) { G.toast('请填写上架信息'); return; }
      var resKey = resEl.value;
      var amt = parseInt(amtEl.value, 10);
      var price = parseInt(priceEl.value, 10);
      if (isNaN(amt) || amt <= 0) { G.toast('数量无效'); return; }
      if (isNaN(price) || price <= 0) { G.toast('单价无效'); return; }
      if ((s.resources[resKey] || 0) < amt) { G.toast(G.DATA.resources[resKey].name + '不足'); return; }
      s.resources[resKey] -= amt;
      s.exchangeList = s.exchangeList || [];
      s.exchangeList.push({
        id: 'ex_' + Date.now() + '_' + Math.floor(Math.random() * 9999),
        resKey: resKey,
        amount: amt,
        price: price,
        time: Date.now()
      });
      G.toast('已上架 ' + G.DATA.resources[resKey].name + ' x' + amt + ' 单价' + price + '金');
      this.exchangePanel();
    },

    unlistResource: function (idx) {
      var s = Core.state;
      s.exchangeList = s.exchangeList || [];
      if (idx < 0 || idx >= s.exchangeList.length) return;
      var item = s.exchangeList[idx];
      s.resources[item.resKey] = (s.resources[item.resKey] || 0) + item.amount;
      s.exchangeList.splice(idx, 1);
      G.toast('已下架，资源返还');
      this.exchangePanel();
    },

    isBuilding: function (jobs, id, slot) {
      for (var i = 0; i < jobs.length; i++) {
        if (jobs[i].id !== id) continue;
        if (slot == null && jobs[i].slot == null) return true;
        if (slot != null && jobs[i].slot === slot) return true;
      }
      return false;
    },

    firstFreeSlot: function (id, startSlot, maxSlots, jobs) {
      for (var slot = startSlot; slot < maxSlots; slot++) {
        if (!this.isBuilding(jobs, id, slot)) return slot;
      }
      return -1;
    },

    /**
     * 这些建筑卡片整张可点 (点空白处) 跳到对应功能页;
     * 卡片内的按钮 (升Lv / 新建 / 进入xx) 仍正常点击, 不会触发卡片跳转。
     * 满足 lv > 0 时才生效, 否则点空白处应触发升级弹窗 (无新内容则 noop)。
     */
    SPECIAL_ROUTES: {
      factory:  'army',     // 军工厂 → 兵种招募
      academy:  'academy',  // 军校   → 军官招募
      staff:    'officer',  // 参谋部 → 军官管理
      lab:      'tech',     // 研究所 → 科技
      wall:     'fort',     // 围墙   → 城防
      lightfactory: 'army', // 轻工厂/重工厂/机场/港口 也走 army 页 (兵种面板支持按 build 过滤)
      heavyfactory: 'army',
      airport:     'army',
      port:        'army'
    },

    /** 建筑类型 → 视觉图标（按 data.js 已有 emoji 风格统一） */
    BUILD_ICON: {
      command: '🏛️', house: '🏠', factory: '🏭', lightfactory: '🔧', heavyfactory: '⚙️',
      airport: '✈️', port: '⚓', academy: '🎓', staff: '🎖️', lab: '🔬',
      farm: '🌾', refinery: '🏭', oilfield: '🛢️', raremine: '⛏️', depot: '🏬',
      transit: '🚛', exchange: '🏦', radar: '📡', wall: '🧱', apron: '🛬', liaison: '📞'
    },

    /**
     * 切换卡片展开/收起：与地图玩家卡一致，仅头部可点切换。
     * 展开状态在 _expandedBuildings 上保存，重新渲染时保持。
     */
    toggleBuildingCard: function (cardEl) {
      if (!cardEl) return;
      var s = Core.state;
      s._expandedBuildings = s._expandedBuildings || {};
      var id = cardEl.getAttribute('data-building');
      if (!id) return;
      if (s._expandedBuildings[id]) {
        delete s._expandedBuildings[id];
        cardEl.classList.remove('bcard-expanded');
      } else {
        s._expandedBuildings[id] = true;
        cardEl.classList.add('bcard-expanded');
      }
    },

    /**
     * 渲染单张建筑卡片。
     * 默认折叠：只显示图标 + 名称 + 等级/槽位 + 简短概要。
     * 展开后：详细属性、每栋槽位操作、快捷入口、升级/新建按钮。
     */
    renderBuilding: function (id, b, jobs, idx) {
      var s = Core.state;
      var multi = b.slots > 1;
      var isExp = !!(s._expandedBuildings && s._expandedBuildings[id]);
      var spRoute = this.SPECIAL_ROUTES[id];
      var max = buildMaxLevel(id);
      var arr = multi ? Core.buildingLevels(id) : [];
      var totalLv = multi ? Core.buildingLevel(id) : (s.buildings[id] || 0);

      // ---- 头部摘要：图标 / 名称 / 等级 / 折叠箭头 ----
      var summary = '';
      if (multi) {
        var summaryLv = arr.length + '/' + b.slots + '栋 · 总Lv.' + totalLv;
        summary =
          '<span class="bcard-icon">' + (this.BUILD_ICON[id] || '🏗') + '</span>' +
          '<span class="bcard-name">' + b.name + '</span>' +
          '<span class="bcard-lv">' + summaryLv + '</span>';
      } else {
        var lv = s.buildings[id] || 0;
        summary =
          '<span class="bcard-icon">' + (this.BUILD_ICON[id] || '🏗') + '</span>' +
          '<span class="bcard-name">' + b.name + '</span>' +
          '<span class="bcard-lv">Lv.' + lv + (lv >= max ? '(满)' : '/' + max) + '</span>';
      }
      // 状态徽章：在建/待建
      var inProgress = 0;
      for (var ji = 0; ji < jobs.length; ji++) if (jobs[ji].id === id) inProgress++;
      if (inProgress > 0) {
        summary += '<span class="bcard-mini-badge bcard-mini-war">施工中 ' + inProgress + '</span>';
      } else if (multi && arr.length === 0) {
        summary += '<span class="bcard-mini-badge bcard-mini-muted">未建</span>';
      } else if (!multi && (s.buildings[id] || 0) === 0) {
        summary += '<span class="bcard-mini-badge bcard-mini-muted">未建</span>';
      }
      // 快捷入口提示
      if (spRoute && s.buildings[id]) {
        summary += '<span class="bcard-mini-badge bcard-mini-go" title="点名称可跳转">▸ 进入</span>';
      }
      summary += '<span class="bcard-caret">▾</span>';

      // ---- 展开区：详细属性 + 操作 ----
      var expand = '<div class="bcard-expand">';
      expand += '<div class="bcard-desc">' + b.desc + '</div>';

      // 关键属性（图标 + 名称 + 数值）
      var stats = '';
      if (b.produces) {
        stats += '<div class="bcard-stat"><span class="bcard-stat-ico">⛏</span><span class="bcard-stat-name">产出</span><span class="bcard-stat-val">' + G.DATA.resources[b.produces].name + ' ' + G.fmt(Core.produceOf(id)) + '/h</span></div>';
      }
      if (b.produces && b.slots && multi) stats += '<div class="bcard-stat"><span class="bcard-stat-ico">📦</span><span class="bcard-stat-name">资源上限</span><span class="bcard-stat-val">' + G.fmt(totalLv * 200000) + '</span></div>';
      if (b.produces && !multi) stats += '<div class="bcard-stat"><span class="bcard-stat-ico">📦</span><span class="bcard-stat-name">资源上限</span><span class="bcard-stat-val">' + G.fmt((s.buildings[id] || 0) * 200000) + '</span></div>';
      if (b.popPer) {
        var popBase = (s.buildings[id] || 0) * b.popPer;
        var popTotal = multi ? totalLv * b.popPer : popBase;
        stats += '<div class="bcard-stat"><span class="bcard-stat-ico">👥</span><span class="bcard-stat-name">人口上限</span><span class="bcard-stat-val">' + popTotal + '</span></div>';
      }
      if (b.protectPer) {
        var lvForCap = multi ? totalLv : (s.buildings[id] || 0);
        var protect = Math.floor(lvForCap * b.protectPer * (1 + 0.10 * (s.tech.log_warehouse || 0)));
        stats += '<div class="bcard-stat"><span class="bcard-stat-ico">🛡</span><span class="bcard-stat-name">掠夺保护</span><span class="bcard-stat-val">每资源保 ' + protect + '</span></div>';
      }
      if (b.defBonus) stats += '<div class="bcard-stat"><span class="bcard-stat-ico">🛡</span><span class="bcard-stat-name">守城防御</span><span class="bcard-stat-val">+' + ((s.buildings[id] || 0) * b.defBonus) + '%</span></div>';
      if (b.airCap) stats += '<div class="bcard-stat"><span class="bcard-stat-ico">✈</span><span class="bcard-stat-name">空军出击上限</span><span class="bcard-stat-val">' + ((s.buildings[id] || 0) * b.airCap) + '</span></div>';
      if (b.resBonus) stats += '<div class="bcard-stat"><span class="bcard-stat-ico">📈</span><span class="bcard-stat-name">全资源产出</span><span class="bcard-stat-val">+' + ((s.buildings[id] || 0) * b.resBonus) + '%</span></div>';
      if (id === 'staff') stats += '<div class="bcard-stat"><span class="bcard-stat-ico">🎖</span><span class="bcard-stat-name">带兵容量加成</span><span class="bcard-stat-val">+' + ((s.buildings[id] || 0) * 10) + '%</span></div>';
      if (id === 'liaison') stats += '<div class="bcard-stat"><span class="bcard-stat-ico">⭐</span><span class="bcard-stat-name">军官刷新</span><span class="bcard-stat-val">偏向高星 (Lv.' + (s.buildings[id] || 0) + ')</span></div>';
      if (stats) expand += '<div class="bcard-stats">' + stats + '</div>';

      // 快捷入口按钮
      var shortcuts = '';
      if (spRoute && s.buildings[id]) {
        var routeName = ({
          army: '兵种招募', academy: '军官招募', officer: '军官管理',
          tech: '科技研究', fort: '城防'
        })[spRoute] || spRoute;
        shortcuts += '<button class="btn sm ok bcard-shortcut" onclick="Game.go(\'' + spRoute + '\')">→ ' + routeName + '</button>';
      }
      if (id === 'exchange' && (s.buildings[id] || 0) > 0) {
        shortcuts += '<button class="btn sm bcard-shortcut" onclick="Game.Build.exchangePanel()">资源互换</button>';
      }
      if (shortcuts) expand += '<div class="bcard-shortcuts">' + shortcuts + '</div>';

      // 多槽位：每栋操作行
      if (multi) {
        for (var si = 0; si < arr.length; si++) {
          var slv = arr[si] || 0;
          var buildingThis = this.isBuilding(jobs, id, si);
          expand += '<div class="bcard-slot-row">';
          expand += '<span class="bcard-slot-tag">#' + (si + 1) + '</span>';
          expand += '<span class="bcard-slot-lv">Lv.' + slv + (slv >= max ? '(满)' : '/' + max) + '</span>';
          if (slv < max) {
            if (buildingThis) {
              expand += '<button class="btn sm" style="background:#8f7b53;border-color:#8f7b53" onclick="Game.Build.showJobDetails(\'' + id + '\',' + si + ')">施工中 (查看)</button>';
            } else if (jobs.length >= 2) {
              expand += '<button class="btn sm" onclick="Game.Build.showBusyJobs()">升 Lv.' + (slv + 1) + ' (队忙)</button>';
            } else {
              expand += '<button class="btn sm" onclick="Game.Build.confirmUpgrade(\'' + id + '\',' + si + ')">升 Lv.' + (slv + 1) + '</button>';
            }
          }
          expand += '</div>';
        }
        if (arr.length < b.slots) {
          var newSlot = this.firstFreeSlot(id, arr.length, b.slots, jobs);
          var groupKey = Core.buildingGroup(id);
          var groupRemain = groupKey ? Core.groupSlotsRemaining(groupKey) : 1;
          var targetSlot = newSlot >= 0 ? newSlot : arr.length;
          var newBtnAction = jobs.length >= 2 ? 'Game.Build.showBusyJobs()' : ('Game.Build.confirmUpgrade(\'' + id + '\',' + targetSlot + ')');
          expand += '<div class="bcard-slot-row bcard-slot-new">';
          expand += '<span class="bcard-slot-tag">+' + (arr.length + 1) + '</span>';
          expand += '<span class="bcard-slot-lv">空闲槽位</span>';
          expand += '<button class="btn sm" onclick="' + newBtnAction + '">新建1栋 (Lv.1)' + (groupRemain <= 0 ? ' 分组已满' : '') + '</button>';
          expand += '</div>';
        }
      } else {
        var lvN = s.buildings[id] || 0;
        var buildingThisN = this.isBuilding(jobs, id, null);
        if (lvN < max) {
          var btnLabel, btnAction;
          if (buildingThisN) {
            btnLabel = '本建筑施工中 (点击查看)';
            btnAction = 'Game.Build.showJobDetails(\'' + id + '\')';
          } else if (jobs.length >= 2) {
            btnLabel = '施工队全忙 (点击查看)';
            btnAction = 'Game.Build.showBusyJobs()';
          } else {
            btnLabel = '升级至 Lv.' + (lvN + 1);
            btnAction = 'Game.Build.confirmUpgrade(\'' + id + '\')';
          }
          expand += '<div class="bcard-actions">';
          expand += '<button class="btn ok" onclick="' + btnAction + '">' + btnLabel + '</button>';
          expand += '</div>';
        } else {
          expand += '<div class="bcard-actions"><span class="bcard-max-tip">已达最大等级</span></div>';
        }
      }
      expand += '</div>';

      // ---- 整张卡片 ----
      var h = '';
      h += '<div class="bcard' + (isExp ? ' bcard-expanded' : '') + (spRoute && s.buildings[id] ? ' bcard-go' : '') + '" data-building="' + id + '">';
      h += '<div class="bcard-head" onclick="Game.Build.toggleBuildingCard(this.parentElement)">';
      h += summary;
      h += '</div>';
      h += expand;
      h += '</div>';
      return h;
    },

    GROUPS: {
      res: { name: '资源', order: ['house', 'farm', 'refinery', 'oilfield', 'raremine', 'depot', 'transit', 'exchange'] },
      army: { name: '军事', order: ['command', 'factory', 'lightfactory', 'heavyfactory', 'port', 'academy', 'staff', 'lab', 'radar', 'wall', 'apron', 'liaison'] }
    },

    renderGroup: function (v, groupKey) {
      var s = Core.state;
      var jobs = this.getConstructions();
      var now = Date.now();
      var self = this;
      var g = this.GROUPS[groupKey];
      var h = '';
      h += '<div class="title">- ' + g.name + ' -</div>';
      var buildDisc = (1 - Core.buildMul()) * 100;
      var groupUsed = Core.groupSlotsUsed(groupKey);
      var groupCap = Core.groupSlotsCap(groupKey);
      h += '<div class="desc">分组槽位: ' + groupUsed + '/' + groupCap + ' (基础10 + 市政厅' + (s.buildings.command || 0) + '×2) | 市政厅限制建筑等级上限' + (buildDisc > 0 ? ' | 建筑加速 -' + buildDisc.toFixed(0) + '%' : '') + '</div>';
      h += '<div id="buildQueueBar">' + this.renderQueueHtml(jobs, s) + '</div>';

      var idx = 0;
      h += '<div class="menu">';
      h += '<div class="zone-head">=== ' + g.name + ' ===</div>';
      g.order.forEach(function (id) {
        idx++;
        h += self.renderBuilding(id, D.buildings[id], jobs, idx);
      });
      h += '</div>';
      var otherKey = groupKey === 'res' ? 'army' : 'res';
      var otherName = this.GROUPS[otherKey].name;
      h += '<div class="menu-item ok" onclick="Game.go(\'build' + (otherKey === 'res' ? 'Res' : 'Army') + '\')">前往' + otherName + ' ></div>';
      h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
      v.innerHTML = h;
    },

    renderResView: function (v) { this.renderGroup(v, 'res'); },
    renderArmyView: function (v) { this.renderGroup(v, 'army'); },

    // 弹出加速符选择器(智能计算最少需要消耗数量)
    openSpeedUpPicker: function (jobOrIdx) {
      var s = Core.state;
      var speedOrder = ['speedUp10m','speedUp1h','speedUp5h','speedUp12h','speedUp24h','speedUp36h','speedUp48h','speedUp72h'];
      var owned = [];
      for (var i = 0; i < speedOrder.length; i++) {
        var sid = speedOrder[i];
        var cnt = (s.items && s.items[sid]) || 0;
        var itemDef = D.items[sid] || {};
        if (cnt > 0) owned.push({ id: sid, name: itemDef.name || sid, cnt: cnt, seconds: itemDef.seconds || 0 });
      }
      if (!owned.length) { G.toast('当前无加速符,请前往商城购买'); return; }

      var job = null;
      if (jobOrIdx && typeof jobOrIdx === 'object') {
        job = jobOrIdx;
      } else if (typeof jobOrIdx === 'number') {
        var list = s.constructions || [];
        job = list[jobOrIdx];
      }
      if (!job) {
        var q = (s.constructions || []).slice().sort(function (a, b) { return (a.finishesAt || 0) - (b.finishesAt || 0); });
        job = q[0];
      }
      var jobDef = job && D.buildings[job.id];
      var jobName = (jobDef && jobDef.name) || (job && job.id) || '未知建筑';
      if (job && job.slot != null) jobName += ' #' + (job.slot + 1);
      var jobLabel = job ? (jobName + ' Lv.' + (job.targetLevel != null ? job.targetLevel : 0)) : '施工';

      var now = Date.now();
      var remSec = job && job.finishesAt ? Math.max(0, Math.ceil((job.finishesAt - now) / 1000)) : 0;
      var remText = timeText(remSec);

      var rows = '';
      for (var j = 0; j < owned.length; j++) {
        var o = owned[j];
        var cardSec = o.seconds || 0;
        var need = 0;
        if (cardSec > 0 && remSec > 0) {
          need = Math.floor(remSec / cardSec);
          if (need === 0) need = 1;
        }
        var useCount = Math.min(need, o.cnt);
        if (useCount <= 0 && o.cnt > 0) useCount = 1;

        rows += '<div class="spicker-row" data-sid="' + o.id + '" data-count="' + useCount + '">' +
                  '<span class="spicker-name">⚡ ' + o.name + ' <small style="font-weight:normal;color:#8a7a58">(余' + o.cnt + ')</small></span>' +
                  '<span class="spicker-cnt">需要消耗 ' + useCount + ' 张</span>' +
                '</div>';
      }

      var mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.innerHTML =
        '<div class="modal-card" style="max-width:350px">' +
          '<div class="modal-title">⚡ 选择加速符</div>' +
          '<div class="modal-body" style="font-size:13px;color:#666;margin-bottom:8px">目标: ' + jobLabel + ' · 剩余 ' + remText + '</div>' +
          '<div class="spicker-list">' + rows + '</div>' +
          '<div class="modal-foot">' +
            '<button class="btn" id="spClose">取消</button>' +
          '</div>' +
        '</div>';
      document.body.appendChild(mask);

      var close = function () { if (mask.parentNode) mask.parentNode.removeChild(mask); };
      mask.querySelector('#spClose').onclick = close;
      mask.addEventListener('click', function (e) { if (e.target === mask) close(); });

      // 选中某行后按计算的数量批量加速
      var rows2 = mask.querySelectorAll('.spicker-row');
      for (var k = 0; k < rows2.length; k++) {
        rows2[k].onclick = function () {
          var sid = this.getAttribute('data-sid');
          var count = parseInt(this.getAttribute('data-count'), 10) || 1;
          close();
          Build.speedUpJob(job, sid, count);
        };
      }
    },

    speedUpJob: function (job, itemId, count) {
      count = count > 0 ? count : 1;
      var qId = job ? job.queueId : null;
      var bId = job ? job.id : null;
      var slot = job ? job.slot : null;
      var info = D.items[itemId] || { name: '加速符' };
      G.API.buildSpeedUp(itemId, qId, count, bId, slot).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '加速失败');
          if (resp.state) G.API.applyState(resp.state);
          return;
        }
        var actualCount = (resp && resp.count) || count;
        G.toast(resp.message || ('⚡ ' + info.name + ' ×' + actualCount + ' 使用成功'));
        if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '加速失败');
      });
    },

    renderQueueHtml: function (jobs, s) {
      var now = Date.now();
      var qh = '';
      if (jobs && jobs.length) {
        for (var ji = 0; ji < jobs.length; ji++) {
          var job = jobs[ji];
          var jobDef = D.buildings[job.id];
          var jobName = (jobDef && jobDef.name) || job.id || '未知建筑';
          if (job.slot != null) jobName += ' #' + (job.slot + 1);
          var targetLevel = job.targetLevel != null ? job.targetLevel : 0;
          // 汇总所有加速符数量,只显示一个主按钮
          var speedTotal = 0;
          var speedOrder = ['speedUp10m','speedUp1h','speedUp5h','speedUp12h','speedUp24h','speedUp36h','speedUp48h','speedUp72h'];
          for (var si = 0; si < speedOrder.length; si++) {
            speedTotal += (s.items && s.items[speedOrder[si]]) || 0;
          }
          var speedBtn = speedTotal > 0
            ? '<button class="btn sm ok" style="margin-left:6px;padding:2px 10px;font-size:13px" onclick="Game.Build.openSpeedUpPicker(' + ji + ')">⚡ 加速 (×' + speedTotal + ')</button>'
            : '<button class="btn sm" disabled style="margin-left:6px;padding:2px 10px;font-size:13px" title="商城可购买加速符">⚡ 加速 (无)</button>';
          qh += '<div class="btimer' + (job.finishesAt - now <= 60000 ? ' urgent' : '') + '" style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:4px">';
          qh += '<span>施工队 ' + (ji + 1) + '：' + jobName + ' Lv.' + targetLevel + '，剩余 ' + timeText((job.finishesAt - now) / 1000) + '</span>';
          qh += speedBtn;
          qh += '</div>';
        }
        if (jobs.length < 2) qh += '<div class="bfield">施工队 ' + (jobs.length + 1) + ' 空闲：还可开始一项建筑升级</div>';
      } else {
        qh += '<div class="bfield">两支施工队空闲：可同时升级两项建筑</div>';
      }
      return qh;
    },

    silentUpdateBuild: function (tickData) {
      // 建筑升级完成或队列变化时，平滑更新全列表
      if (tickData && tickData.completedBuilds && tickData.completedBuilds.length > 0) {
        Core.refreshContent();
        return;
      }
      // 仅倒计时走动时，就地更新顶部施工队容器，不动建筑列表卡片
      var qBar = document.getElementById('buildQueueBar');
      if (qBar) {
        var s = Core.state || {};
        var jobs = this.getConstructions();
        var newQHtml = this.renderQueueHtml(jobs, s);
        if (qBar.innerHTML !== newQHtml) {
          qBar.innerHTML = newQHtml;
        }
      }
    }
  };

  G.Build = Build;
  Core.views.buildRes = function (v) { Build.renderResView(v); };
  Core.views.buildArmy = function (v) { Build.renderArmyView(v); };
  G.buildCost = buildCost;
  G.buildMaxLevel = buildMaxLevel;
})(window.Game);
