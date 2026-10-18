/* global window, document, confirm */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;
  var Core = G.Core;

  var branchOrder = G.Constants.techBranches;
  var activeBranch = '军事';
  var timerId = null;

  function techCost(id, currentLv) {
    var t = D.techs[id];
    var lv = currentLv != null ? currentLv : ((Core.state && Core.state.tech && Core.state.tech[id]) || 0);
    var mul = Math.pow(t.growth, lv);
    var cost = {};
    for (var k in t.baseCost) cost[k] = Math.floor(t.baseCost[k] * mul);
    return cost;
  }

  function techDuration(id, currentLv, labLv) {
    var lv = currentLv != null ? currentLv : ((Core.state && Core.state.tech && Core.state.tech[id]) || 0);
    var lab = labLv != null ? labLv : ((Core.state && Core.state.buildings && Core.state.buildings.lab) || 0);
    var base = (id.indexOf('prod_') === 0 ? 7.5 : 30.0) * Math.pow(1.8, Math.max(0, lv));
    var labSpeed = 1.0 + 0.10 * Math.max(0, lab - 1);
    var mayorSpeed = 1.0 + (Core.mayorSkillBonus ? Core.mayorSkillBonus('research') : 0);
    var duration = Math.round(base / (labSpeed * mayorSpeed));
    return Math.max(5, Math.min(86400, duration));
  }

  function formatTime(seconds) {
    seconds = Math.max(0, Math.floor(seconds || 0));
    if (seconds >= 3600) {
      var h = Math.floor(seconds / 3600);
      var m = Math.floor((seconds % 3600) / 60);
      var s = seconds % 60;
      return (h < 10 ? '0' + h : h) + ':' + (m < 10 ? '0' + m : m) + ':' + (s < 10 ? '0' + s : s);
    } else {
      var m2 = Math.floor(seconds / 60);
      var s2 = seconds % 60;
      return (m2 < 10 ? '0' + m2 : m2) + ':' + (s2 < 10 ? '0' + s2 : s2);
    }
  }

  function timeText(seconds) {
    seconds = Math.max(0, Math.floor(seconds || 0));
    if (seconds < 60) return seconds + '秒';
    var m = Math.floor(seconds / 60);
    var s = seconds % 60;
    if (m < 60) return m + '分' + (s > 0 ? (s + '秒') : '');
    var h = Math.floor(m / 60);
    var remM = m % 60;
    return h + '时' + (remM > 0 ? (remM + '分') : '');
  }

  function costHtml(cost) {
    var arr = [];
    for (var k in cost) {
      arr.push(G.resourceIconHtml(k) + cost[k]);
    }
    return arr.join(' ');
  }

  /**
   * 生成科技卡片与研发确认文案，区分属性百分比、配方解锁、比例和未生效项目。
   * @param {Object} t 科技定义
   * @param {number} lv 当前科技等级
   * @returns {Object} 当前与下一级效果，以及可选的卡片说明
   */
  function getEffectDiffText(t, lv) {
    var level = Math.max(0, Math.min(t.max, lv));
    var nextLevel = Math.min(t.max, level + 1);
    // 同项线性累计，与攻击独立相乘；等级之间的实际输出增幅不是固定再乘 1.1。
    if (t.affect === 'firepower_all') {
      return {
        curStr: '+' + (level * 5) + '%（×' + (1 + level / 20).toFixed(2) + '）',
        nextStr: '+' + (nextLevel * 5) + '%（×' + (1 + nextLevel / 20).toFixed(2) + '）',
        currentLabel: '当前行动火力', effectLabel: '行动火力', pct: 5, unit: '%'
      };
    }
    // 配方权限不会直接提高部队属性；制造时还会检查城市生产设施等级。
    if (t.affect === 'unit_recipes') {
      return {
        curStr: level > 0 ? 'Lv.' + level + '及以下配方' : '未解锁',
        nextStr: 'Lv.' + nextLevel + '及以下配方',
        currentLabel: '当前可制造配方', effectLabel: '配方权限'
      };
    }
    // 先手使用点数，资源负重和仓库保护使用各自明确登记的百分比。
    if (t.affect === 'recon') {
      var intel = ['城防', '精确守军与统帅', '建筑与将领人数', '科技与可掠夺资源测算', '将领档案与战力'];
      return {
        curStr: '情报等级 Lv.' + level,
        nextStr: '情报等级 Lv.' + nextLevel,
        currentLabel: '当前可查看',
        effectLabel: '情报等级',
        currentText: (level > 0 ? '城市：基础资源、' + intel.slice(0, level).join('、') + '；野地：精确守军' : '基础资源；城市与野地的模糊守军') + '；侦查交火先手+' + (level * 5) + '点',
        pct: 1, unit: '级'
      };
    }
    if (t.affect === 'radar') {
      return {
        curStr: '+' + (level * 5) + '点',
        nextStr: '+' + (nextLevel * 5) + '点',
        currentLabel: '当前首回合拦截先手值加成', effectLabel: '首回合拦截先手值',
        pct: 5, unit: '点'
      };
    }
    // 千分比科技单独显示小数，避免浮点乘法出现0.30000000000000004%。
    if (t.affect === 'capture_permille') return { curStr: (level / 10).toFixed(1) + '%', nextStr: (nextLevel / 10).toFixed(1) + '%', currentLabel: '当前俘获比例', effectLabel: '俘获比例', pct: 0.1, unit: '%' };
    if (t.affect === 'medical') return { curStr: (level * 5) + '%', nextStr: (nextLevel * 5) + '%', currentLabel: '本科技伤兵回收率', effectLabel: '伤兵回收率', pct: 5, unit: '%' };
    var pctMap = G.Constants.techEffectPercent;
    var pct = pctMap[t.affect];
    // 未登记的效果不能默认编造为每级 5%，新增科技须明确展示规则。
    if (pct === undefined) return { curStr: '暂无效果说明', nextStr: '暂无效果说明' };
    var cur = level * pct;
    var next = nextLevel * pct;
    var unit = '%';
    var curStr = (cur > 0 ? '+' : '') + cur + unit;
    var nextStr = (next > 0 ? '+' : '') + next + unit;
    var effectLabels = { wall_stats: '城墙生命与防御', train: '兵种生产速度', build: '建筑资源消耗与基础工期', res_food: '城内粮食产量', res_steel: '城内钢铁产量', res_oil: '城内石油产量', res_rare: '城内稀矿产量', load: '全兵种负重', cap: '仓库掠夺保护额度' };
    var effectLabel = effectLabels[t.affect];
    return { curStr: curStr, nextStr: nextStr, pct: pct, unit: unit, effectLabel: effectLabel, currentLabel: effectLabel ? '当前' + effectLabel : null };
  }

  var Tech = {
    setTab: function (branch) {
      if (branchOrder.indexOf(branch) < 0) return;
      activeBranch = branch;
      Core.render();
      var tab = document.getElementById('tech-tab-' + branchOrder.indexOf(branch));
      if (tab) tab.focus({ preventScroll: true });
    },

    tabKey: function (event, index) {
      var next = index;
      if (event.key === 'ArrowRight') next = (index + 1) % branchOrder.length;
      else if (event.key === 'ArrowLeft') next = (index + branchOrder.length - 1) % branchOrder.length;
      else if (event.key === 'Home') next = 0;
      else if (event.key === 'End') next = branchOrder.length - 1;
      else return;
      event.preventDefault();
      Tech.setTab(branchOrder[next]);
    },

    confirmResearch: function (id) {
      var t = D.techs[id];
      if (!t) return;
      var s = Core.state || {};
      var labLv = (s.buildings && s.buildings.lab) || 0;
      var lv = (s.tech && s.tech[id]) || 0;

      // 1. 如果当前科技正在研发中，询问加速
      var activeRes = s.research;
      if (activeRes && activeRes.techType === id) {
        Tech.openSpeedUpPicker();
        return;
      }

      // 2. 如果其他科技正在研发中
      if (activeRes) {
        G.toast('军工科技研发中心正在研发【' + activeRes.name + '】，请等待完成或使用加速符');
        return;
      }

      // 3. 检查军工科技研发中心等级
      var missing = G.Prerequisites && G.Prerequisites.firstMissing('technologies', id, lv + 1);
      if (missing || labLv < t.labReq) {
        G.toast(missing ? G.Prerequisites.missingText(missing) : '需军工科技研发中心 Lv.' + t.labReq);
        return;
      }

      // 4. 检查是否满级
      if (lv >= t.max) {
        G.toast('该科技已达最大等级');
        return;
      }

      var toLv = lv + 1;
      var cost = techCost(id, lv);
      var duration = techDuration(id, lv, labLv);
      var enough = Core.costEnough(cost);

      var cur = s.resources || {};
      var costRows = '';
      for (var k in cost) {
        var resName = (G.DATA.resources[k] && G.DATA.resources[k].name) || k;
        var ico = G.resourceIconHtml(k);
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

      var diff = getEffectDiffText(t, lv);
      var effectHtml =
        '<div class="cu-row" style="background:rgba(212,163,89,0.08);padding:6px 8px;border-radius:4px;margin-bottom:6px;">' +
          '<span class="cu-ico">⚡</span>' +
          '<span class="cu-name" style="font-weight:bold;color:var(--ink);">' + (diff.effectLabel || '加成效果') + '</span>' +
          '<span class="cu-val" style="color:var(--accent);font-weight:bold;">' + diff.curStr + ' → ' + diff.nextStr + '</span>' +
        '</div>';

      var labDiscount = labLv > 1 ? (' (军工科技研发中心 Lv.' + labLv + ' 加速 -' + Math.min(80, (labLv - 1) * 10) + '%)') : '';
      var durHtml = '<div class="cu-row"><span class="cu-ico">⏱</span><span class="cu-name">研发工期</span>' +
                    '<span class="cu-val">' + timeText(duration) + '<span class="cu-sub">' + labDiscount + '</span></span></div>';

      var mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.innerHTML =
        '<div class="modal-card" style="max-width:350px">' +
          '<div class="modal-title">🔬 科技确认</div>' +
          '<div class="modal-body" style="font-size:13px">' +
            '<div class="cu-row cu-head">' +
              '<span class="cu-name">' + t.name + '  Lv.' + lv + ' → Lv.' + toLv + '</span>' +
            '</div>' +
            effectHtml +
            costRows +
            durHtml +
            (enough ? '' : '<div class="cu-warn">⚠ 资源不足，无法开始研发</div>') +
          '</div>' +
          '<div class="modal-foot">' +
            '<button class="btn" id="cuCancel">取消</button>' +
            '<button class="btn ok" id="cuOk"' + (enough ? '' : ' disabled') + '>开始研发</button>' +
          '</div>' +
        '</div>';
      document.body.appendChild(mask);

      var close = function () { if (mask.parentNode) mask.parentNode.removeChild(mask); };
      mask.querySelector('#cuCancel').onclick = close;
      mask.addEventListener('click', function (e) { if (e.target === mask) close(); });

      mask.querySelector('#cuOk').onclick = function () {
        var liveCost = techCost(id, lv);
        if (!Core.costEnough(liveCost)) {
          G.toast('资源不足，无法开始研发');
          close();
          return;
        }
        close();
        Tech.research(id);
      };
    },

    research: function (id) {
      G.API.techUpgrade(id).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '研发失败');
          return;
        }
        G.toast(resp.message || (D.techs[id].name + ' 已开始研发'));
        Core.render();
        if (G.Onboarding && G.Onboarding.actionStarted) G.Onboarding.actionStarted('tech', id);
      }).catch(function (err) {
        G.toast(err.message || '研发失败');
      });
    },

    confirmCancel: function (queueId) {
      var s = Core.state || {};
      var res = s.research;
      var name = res ? res.name : '科技';
      if (!confirm('确定要取消【' + name + '】的研发吗？\n将返还 80% 消耗的资源。')) return;
      G.API.techCancel(queueId).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '取消失败');
          return;
        }
        G.toast(resp.message || '已取消研发');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '取消失败');
      });
    },

    openSpeedUpPicker: function () {
      var s = Core.state || {};
      var res = s.research;
      if (!res) {
        G.toast('当前没有正在研发的科技项目');
        return;
      }
      var speedOrder = G.Constants.speedUpOrder;
      var owned = [];
      for (var i = 0; i < speedOrder.length; i++) {
        var sid = speedOrder[i];
        var cnt = (s.items && s.items[sid]) || 0;
        var itemDef = D.items[sid] || {};
        if (cnt > 0) owned.push({ id: sid, name: itemDef.name || sid, cnt: cnt, seconds: itemDef.seconds || 0 });
      }
      if (!owned.length) { G.toast('当前无加速符，可前往商城购买'); return; }

      var now = Date.now();
      var remSec = res.finishesAt ? Math.max(0, Math.ceil((res.finishesAt - now) / 1000)) : 0;
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
          '<div class="modal-title">⚡ 科技加速</div>' +
          '<div class="modal-body" style="font-size:13px;color:#666;margin-bottom:8px">目标: 【' + res.name + ' Lv.' + res.targetLevel + '】· 剩余 ' + remText + '</div>' +
          '<div class="spicker-list">' + rows + '</div>' +
          '<div class="modal-foot">' +
            '<button class="btn" id="spClose">取消</button>' +
          '</div>' +
        '</div>';
      document.body.appendChild(mask);

      var close = function () { if (mask.parentNode) mask.parentNode.removeChild(mask); };
      // 必须在真正的加速选择器内切换目标，不能继续镂空背景中的研发加速按钮。
      var guidedPicker = G.Onboarding && G.Onboarding.state && G.Onboarding.state.guidedAction && G.Onboarding.state.guidedAction.route === 'tech' && G.Onboarding.showSpotlight;
      if (guidedPicker) {
        mask.className += ' ob-guided-picker';
        var guideSpeedRow = mask.querySelector('.spicker-row[data-sid="speedUp1h"]') || mask.querySelector('.spicker-row');
        if (guideSpeedRow) G.Onboarding.showSpotlight(guideSpeedRow);
        var removePicker = close;
        close = function () {
          if (G.Onboarding.clearSpotlight) G.Onboarding.clearSpotlight();
          removePicker();
        };
      }
      mask.querySelector('#spClose').onclick = close;
      mask.addEventListener('click', function (e) { if (e.target === mask) close(); });

      var rows2 = mask.querySelectorAll('.spicker-row');
      for (var k = 0; k < rows2.length; k++) {
        rows2[k].onclick = function () {
          var sid = this.getAttribute('data-sid');
          var count = parseInt(this.getAttribute('data-count'), 10) || 1;
          close();
          G.API.techSpeedUp(sid, res.queueId, count).then(function (resp) {
            if (resp && resp.success === false) {
              G.toast(resp.message || '加速失败');
              return;
            }
            G.toast(resp.message || '加速成功');
            Core.render();
            if (G.Onboarding && G.Onboarding.refresh) G.Onboarding.refresh();
          }).catch(function (err) {
            G.toast(err.message || '加速失败');
          });
        };
      }
    },

    startTimer: function () {
      if (timerId) clearInterval(timerId);
      timerId = setInterval(function () {
        if (!Core || Core.route !== 'tech') {
          clearInterval(timerId);
          timerId = null;
          return;
        }
        var s = Core.state || {};
        var res = s.research;
        if (!res) {
          clearInterval(timerId);
          timerId = null;
          return;
        }
        var now = Date.now();
        var rem = Math.max(0, Math.ceil((res.finishesAt - now) / 1000));
        var timerEl = document.getElementById('tech-timer-text');
        var barEl = document.getElementById('tech-progress-bar');
        var badgeEl = document.getElementById('tech-badge-' + res.techType);

        if (timerEl) timerEl.textContent = '剩余 ' + formatTime(rem);
        if (badgeEl) badgeEl.textContent = '⏳ 研发中 ' + formatTime(rem);

        var total = res.durationSeconds || Math.max(1, (res.finishesAt - res.startedAt) / 1000);
        var elapsed = Math.max(0, total - rem);
        var pct = Math.min(100, Math.round(elapsed / total * 100));
        if (barEl) barEl.style.width = pct + '%';

        if (rem <= 0) {
          clearInterval(timerId);
          timerId = null;
          Core.refreshContent();
        }
      }, 1000);
    },

    stopTimer: function () {
      if (timerId) {
        clearInterval(timerId);
        timerId = null;
      }
    },

    renderActiveResearchCard: function (s) {
      var res = s.research;
      if (!res) {
        return '<div class="panel" style="margin-bottom:12px;padding:8px 12px;border:1px dashed rgba(212,163,89,0.3);background:rgba(212,163,89,0.03);border-radius:6px;font-size:12px;color:var(--muted);">' +
               '💡 军工科技研发中心空闲中：可自主选择一项战略技术开展科技研发' +
               '</div>';
      }

      var now = Date.now();
      var rem = Math.max(0, Math.ceil((res.finishesAt - now) / 1000));
      var total = res.durationSeconds || Math.max(1, (res.finishesAt - res.startedAt) / 1000);
      var elapsed = Math.max(0, total - rem);
      var pct = Math.min(100, Math.round(elapsed / total * 100));

      var h = '<div class="panel" style="margin-bottom:12px;border:1px solid rgba(82,196,26,0.35);background:linear-gradient(135deg,rgba(82,196,26,0.08),rgba(0,0,0,0.2));border-radius:8px;padding:12px 14px;">';
      h += '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;">';
      h += '  <div style="font-size:14px;font-weight:bold;color:var(--accent);display:flex;align-items:center;gap:6px;">';
      h += '    <span>🔬 正在研发：【' + G.escapeHtml(res.name) + ' Lv.' + res.targetLevel + '】</span>';
      h += '  </div>';
      h += '  <div style="font-size:13px;color:var(--ok);font-weight:bold;" id="tech-timer-text">剩余 ' + formatTime(rem) + '</div>';
      h += '</div>';

      h += '<div class="quest-progress-bar" style="height:7px;margin:6px 0 10px 0;background:rgba(0,0,0,0.25);border-radius:4px;overflow:hidden;">';
      h += '  <div class="quest-progress-fill" id="tech-progress-bar" style="width:' + pct + '%;background:var(--ok);height:100%;transition:width 0.3s ease;"></div>';
      h += '</div>';

      h += '<div style="display:flex;justify-content:flex-end;gap:8px;align-items:center;">';
      h += '  <button class="btn sm" style="padding:3px 12px;font-size:12px;" onclick="Game.Tech.confirmCancel(' + res.queueId + ')">取消研发</button>';
      h += '  <button class="btn sm ok" style="padding:3px 14px;font-size:12px;font-weight:bold;" onclick="Game.Tech.openSpeedUpPicker()">⚡ 加速研发</button>';
      h += '</div>';
      h += '</div>';

      return h;
    },

    renderView: function (v) {
      var s = Core.state || {};
      var labLv = (s.buildings && s.buildings.lab) || 0;
      var activeRes = s.research;

      var h = '';
      h += '<div class="title">- 军工科技研发中心 -</div>';
      h += '<div class="desc">军工科技研发中心 Lv.' + labLv + ' · ' + branchOrder.length + ' 类科技，共 ' + Object.keys(D.techs).length + ' 项。军工科技研发中心等级越高，研发耗时越短。</div>';

      // 1. 顶部正在研发卡片
      h += this.renderActiveResearchCard(s);

      // 2. 分类标签页
      h += '<div class="tech-tabs" role="tablist" aria-label="科技分类">';
      branchOrder.forEach(function (branch, index) {
        var selected = branch === activeBranch;
        h += '<button type="button" id="tech-tab-' + index + '" class="tech-tab' + (selected ? ' active' : '') + '" role="tab" aria-selected="' + selected + '" aria-controls="tech-panel" tabindex="' + (selected ? '0' : '-1') + '" onclick="Game.Tech.setTab(\'' + branch + '\')" onkeydown="Game.Tech.tabKey(event,' + index + ')">' + branch + '科技</button>';
      });
      h += '</div>';

      // 3. 科技卡片列表
      var idx = 0;
      h += '<div class="menu tech-panel" id="tech-panel" role="tabpanel" aria-labelledby="tech-tab-' + branchOrder.indexOf(activeBranch) + '" tabindex="0">';
      branchOrder.forEach(function (branch) {
        Object.keys(D.techs).forEach(function (id) {
          var t = D.techs[id];
          if (t.branch !== branch) return;
          idx++;
          if (branch !== activeBranch) return;

          var lv = (s.tech && s.tech[id]) || 0;
          var missing = G.Prerequisites && G.Prerequisites.firstMissing('technologies', id, lv + 1);
          var locked = !!missing || labLv < t.labReq;
          var lockText = missing ? G.Prerequisites.missingText(missing) : '需军工科技研发中心 Lv.' + t.labReq;
          var maxed = lv >= t.max;
          var cost = techCost(id, lv);
          var duration = techDuration(id, lv, labLv);
          var enough = !locked && !maxed && Core.costEnough(cost);

          var isResearching = activeRes && activeRes.techType === id;
          var isBusy = activeRes && !isResearching;

          var cls = 'menu-item';
          if (isResearching) cls += ' active';
          else if (enough && !isBusy) cls += ' ok';
          else cls += ' lock';

          var diff = getEffectDiffText(t, lv);

          h += '<div class="' + cls + '" data-tech="' + id + '" id="tech-card-' + id + '" onclick="Game.Tech.confirmResearch(\'' + id + '\')" style="display:flex;flex-direction:column;gap:4px;cursor:pointer;">';
          h += '  <div style="display:flex;justify-content:space-between;align-items:center;">';
          h += '    <div>';
          h += '      <span class="num">[' + idx + ']</span> ';
          h += '      <span class="n" style="font-weight:bold;font-size:14px;">' + t.name + '</span> ';
          h += '      <span class="lv" style="font-weight:bold;color:var(--accent);">Lv.' + lv + '/' + t.max + '</span>';
          h += '    </div>';

          // 右侧操作标识 / 按钮
          if (isResearching) {
            var remSec = Math.max(0, Math.ceil((activeRes.finishesAt - Date.now()) / 1000));
            h += '    <div style="display:flex;align-items:center;gap:6px;">';
            h += '      <span class="quest-badge warn" id="tech-badge-' + id + '">⏳ 研发中 ' + formatTime(remSec) + '</span>';
            h += '      <button class="btn sm ok" style="padding:2px 8px;font-size:12px;" onclick="event.stopPropagation();Game.Tech.openSpeedUpPicker()">⚡ 加速</button>';
            h += '    </div>';
          } else if (maxed) {
            h += '    <span class="quest-badge ok">✓ 已满级</span>';
          } else if (locked) {
            h += '    <span class="quest-badge" style="opacity:0.6;">前置未满足</span>';
          } else if (isBusy) {
            h += '    <button class="btn sm" disabled style="opacity:0.6;padding:2px 8px;font-size:12px;" title="军工科技研发中心正忙">正忙</button>';
          } else if (enough) {
            h += '    <button class="btn sm ok" style="padding:3px 12px;font-size:12px;font-weight:bold;" onclick="event.stopPropagation();Game.Tech.confirmResearch(\'' + id + '\')">🔬 研发</button>';
          } else {
            h += '    <button class="btn sm" style="padding:2px 8px;font-size:12px;opacity:0.6;" onclick="event.stopPropagation();Game.Tech.confirmResearch(\'' + id + '\')">资源不足</button>';
          }
          h += '  </div>';

          // 效果说明
          h += '  <div class="d" style="color:var(--muted);font-size:12px;">' + t.desc + (diff.currentLabel ? '<br>' : ' · ') + (diff.currentLabel || '当前加成') + ': <b style="color:var(--ink);">' + (diff.currentText || diff.curStr) + '</b></div>';

          // 费用与耗时展示
          if (locked) {
            h += '  <div class="cost" style="color:var(--danger);font-size:12px;">' + lockText + '</div>';
          } else if (maxed) {
            h += '  <div class="cost" style="color:var(--ok);font-size:12px;">已达到终极科技上限</div>';
          } else {
            h += '  <div style="display:flex;justify-content:space-between;align-items:center;font-size:12px;color:var(--muted);">';
            h += '    <span>下一级: ' + costHtml(cost) + '</span>';
            h += '    <span>⏱ 耗时: ' + timeText(duration) + '</span>';
            h += '  </div>';
          }

          if (!maxed && G.Prerequisites) {
            h += '<details class="prereq-details"' + (locked ? ' open' : '') +
              ' onclick="event.stopPropagation()"><summary>Lv.' + (lv + 1) + ' 建筑要求</summary>' +
              G.Prerequisites.html('technologies', id, lv + 1) + '</details>';
          }
          if (G.Prerequisites) {
            h += '<button type="button" class="btn sm" onclick="event.stopPropagation();Game.Prerequisites.preview(\'technologies\',\'' +
              id + '\',' + Math.min(t.max, lv + 1) + ')">查看各级要求</button>';
          }

          h += '</div>';
        });
      });
      h += '</div>';

      h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
      v.innerHTML = h;

      // 启动动态倒计时循环
      if (activeRes) {
        this.startTimer();
      } else {
        this.stopTimer();
      }
    }
  };

  G.Tech = Tech;
  Core.views.tech = function (v) { Tech.renderView(v); };
  G.techCost = techCost;
  G.techDuration = techDuration;
})(window.Game);
