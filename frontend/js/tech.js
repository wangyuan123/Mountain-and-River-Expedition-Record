/* global window, document, confirm */
window.Game = window.Game || {};

(function (G) {
  'use strict';
  G.gameNow = G.gameNow || function () { return Date.now(); };

  var D = G.DATA;
  var Core = G.Core;

  var branchOrder = G.Constants.techBranches;
  var activeBranch = '军事';
  var timerId = null;

  /** 只读取后端生成的等级报价，取消客户端费用与工期曲线。 */
  function techCost(id, level) {
    if (level == null) level = Core.state.tech[id] || 0;
    return G.ServerRules.level('technologies', id, level).cost || {};
  }

  function techDuration(id, level) {
    if (level == null) level = Core.state.tech[id] || 0;
    return G.ServerRules.level('technologies', id, level).duration;
  }

  function formatTime(seconds) {
    return G.formatDuration(seconds);
  }

  function timeText(seconds) {
    return G.formatDuration(seconds);
  }

  function costHtml(cost) {
    var arr = [];
    for (var k in cost) {
      arr.push(G.resourceIconHtml(k) + cost[k]);
    }
    return arr.join(' ');
  }

  /**
   * 打开科技研发规则说明弹窗。
   * 说明按玩家计算工期时的先后顺序排列，避免把长规则直接铺在科技页顶部。
   */
  function showResearchInfo() {
    var existing = document.getElementById('techResearchInfoModal');
    if (existing) return;

    var mask = document.createElement('div');
    mask.id = 'techResearchInfoModal';
    mask.className = 'modal-mask';
    mask.innerHTML =
      '<div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="techResearchInfoTitle">' +
        '<div class="modal-title" id="techResearchInfoTitle">🔬 科技研发说明</div>' +
        '<div class="modal-body">' +
          '<ol>' +
            '<li><strong>基础工期：</strong>资源科技首级2分钟、9→10级12天；普通科技首级3分钟、9→10级15天。机动科技首级5分钟，燃烧引擎末级20天，舰船动力和喷气推进末级均28天。前五级固定，6—10级按几何曲线递进并四舍五入到整分钟。</li>' +
            '<li><strong>研发中心：</strong>只解锁科技种类与可研究等级，不提供研发速度加成。Lv.1—5 分别累计开放 6、16、22、23、24 项科技，Lv.6—10 不新增种类。目标科技等级不能超过本城中心等级，仍须满足各项配套建筑条件；解锁不会直接获得科技，也不能跳级研究。</li>' +
            '<li><strong>市长学识：</strong>按开始研发所在城市的市长有效学识计算，每点学识使研发速度 +0.1%，100 点为 +10%；有效学识包含装备与套装加成。未任命市长时没有这项加成。</li>' +
            '<li><strong>市长技能：</strong>本城市长的「格物致知」每级使研发速度 +4%，最高 Lv.5 为 +20%；只有任命为市长才生效。</li>' +
            '<li><strong>工期计算：</strong>基础工期 ÷ 学识速度倍率 ÷ 格物技能速度倍率，最后四舍五入到整秒；两项速度倍率分别为“1 + 对应加成”，并相乘。工期最短5秒，不设24小时上限。</li>' +
            '<li><strong>生效时点：</strong>工期在点击开始研发时锁定。更换市长、调整学识或提升技能只影响新开始的研发；中心升级只提高新研究的准入范围，不改变已有科技效果或在研工期。</li>' +
            '<li><strong>加速与暂停：</strong>加速符按标注时长 × 使用数量扣减剩余时间，可直接完成研发；研发所在城市归降或移交期间暂停，完成时间按冻结时长顺延。</li>' +
            '<li><strong>开工条件：</strong>需要满足建筑和科技前置条件并备齐资源；各城市独立使用科研队列，VIP0—4每城1队、VIP5起每城2队。科技成果全账号共享，同一种科技不能在不同队列同时升级。</li>' +
            '<li><strong>黄金费用：</strong>所有科技每级均消耗黄金，按目标等级逐级增加。9→10级资源产量科技2000万、普通科技3000万、燃烧引擎5000万、喷气推进6500万、舰船动力8000万；市长及加速只缩短工期，不降低黄金费用。开工时一次扣除，取消按开工实际消耗返还80%，旧科研不按新价格补扣或重算退款。</li>' +
          '</ol>' +
        '</div>' +
        '<div class="modal-foot"><button type="button" class="btn" id="techResearchInfoClose">知道了</button></div>' +
      '</div>';
    document.body.appendChild(mask);

    var close = function () { if (mask.parentNode) mask.parentNode.removeChild(mask); };
    mask.querySelector('#techResearchInfoClose').onclick = close;
    mask.addEventListener('click', function (event) { if (event.target === mask) close(); });
    mask.onkeydown = function (event) { if (event.key === 'Escape') close(); };
  }

  /**
   * 生成科技卡片与研发确认文案，区分属性百分比、配方解锁、比例和未生效项目。
   * @param {Object} t 科技定义
   * @param {number} lv 当前科技等级
   * @returns {Object} 当前与下一级效果，以及可选的卡片说明
   */
  function getEffectDiffText(t, level) {
    var id = Object.keys(D.techs).find(function (key) { return D.techs[key] === t; });
    return G.ServerRules.level('technologies', id, level).effect || { curStr: '待同步', nextStr: '待同步' };
  }

  var Tech = {
    showResearchInfo: showResearchInfo,

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

      // 3. 中心等级同时控制种类开放与目标等级，配套设施仍按本城逐项校验。
      if (lv >= t.max) {
        G.toast('该科技已达最大等级');
        return;
      }
      var requirements = G.Prerequisites && G.Prerequisites.check('technologies', id, lv + 1);
      var missing = requirements && requirements.missing[0];
      var requiredLabLevel = G.ServerRules.level('technologies', id, lv).requiredLabLevel;
      if (missing || labLv < requiredLabLevel) {
        if (missing) G.Prerequisites.preview('technologies', id, lv + 1);
        else G.toast('需军工科技研发中心 Lv.' + requiredLabLevel);
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

      var mayor = Core.getOfficerByRole ? Core.getOfficerByRole('mayor') : null;
      var knowledge = mayor ? Math.max(0, mayor.knowledge || 0) : 0;
      var knowledgeSpeed = mayor && mayor.researchKnowledgePercent || 0;
      var skillSpeed = Core.mayorSkillBonus ? Core.mayorSkillBonus('research') * 100 : 0;
      var speedParts = [];
      if (knowledge > 0) speedParts.push('学识提速 ' + knowledgeSpeed.toFixed(1) + '%');
      if (skillSpeed > 0) speedParts.push('格物提速 ' + skillSpeed.toFixed(0) + '%');
      var speedText = speedParts.length ? (' (' + speedParts.join('，') + ')') : '';
      var durHtml = '<div class="cu-row"><span class="cu-ico">⏱</span><span class="cu-name">研发工期</span>' +
                    '<span class="cu-val">' + timeText(duration) + '<span class="cu-sub">' + speedText + '</span></span></div>';

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

      var now = G.gameNow();
      var remSec = res.finishesAt ? Math.max(0, Math.ceil((res.finishesAt - now) / 1000)) : 0;
      var remText = timeText(remSec);

      var rows = '';
      for (var j = 0; j < owned.length; j++) {
        var o = owned[j];
        var useCount = (res && res.speedUpCounts || {})[o.id] || 1;

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
        var now = G.gameNow();
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

      var now = G.gameNow();
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
      var access = G.researchAccess(labLv);
      var activeRes = s.research;

      var h = '';
      h += '<div class="desc">军工科技研发中心 Lv.' + labLv + ' · ' + branchOrder.length + ' 类科技，共 ' + Object.keys(D.techs).length + ' 项。' +
        '已开放 ' + access.unlocked.length + ' 项，科技可研究至 Lv.' + access.level + '，仍需配套建筑。中心升级不提速，研发速度仅受市长学识和格物致知影响。 ' +
        '<span role="button" tabindex="0" class="link-act" onclick="Game.Tech.showResearchInfo()" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){event.preventDefault();Game.Tech.showResearchInfo();}">查看研发说明</span></div>';

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
          var requirements = G.Prerequisites && G.Prerequisites.check('technologies', id, lv + 1);
          var missing = requirements && requirements.missing[0];
          var requiredLabLevel = G.ServerRules.level('technologies', id, lv).requiredLabLevel;
          var locked = !!missing || labLv < requiredLabLevel;
          var lockText = missing ? requirements.missing.map(G.Prerequisites.missingText).join('；') : '需军工科技研发中心 Lv.' + requiredLabLevel;
          var maxed = lv >= t.max;
          var cost = techCost(id, lv);
          var duration = techDuration(id, lv, labLv);
          var enough = !locked && !maxed && Core.costEnough(cost);

          var isResearching = activeRes && activeRes.techType === id;
          var isBusy = activeRes && !isResearching;

          var cls = 'menu-item tech-card';
          if (isResearching) cls += ' active is-researching';
          else if (enough && !isBusy) cls += ' ok is-ready';
          else if (maxed) cls += ' is-maxed';
          else cls += ' lock is-locked';

          var diff = getEffectDiffText(t, lv);

          h += '<div class="' + cls + '" data-tech="' + id + '" id="tech-card-' + id + '" onclick="Game.Tech.confirmResearch(\'' + id + '\')">';
          
          // 头部：序号、名称、等级与右侧动作按钮
          h += '  <div class="tech-card-header">';
          h += '    <div class="tech-title-group">';
          h += '      <span class="tech-num num">[' + idx + ']</span> ';
          h += '      <span class="tech-name n">' + G.escapeHtml(t.name) + '</span> ';
          h += '      <span class="tech-level lv">Lv.' + lv + '/' + t.max + '</span>';
          h += '    </div>';

          // 右侧操作标识 / 按钮
          h += '    <div class="tech-action-group">';
          if (isResearching) {
            var remSec = Math.max(0, Math.ceil((activeRes.finishesAt - G.gameNow()) / 1000));
            h += '      <span class="quest-badge warn tech-timer-badge" id="tech-badge-' + id + '">⏳ 研发中 ' + formatTime(remSec) + '</span>';
            h += '      <button type="button" class="btn sm ok tech-btn-action" onclick="event.stopPropagation();Game.Tech.openSpeedUpPicker()">⚡ 加速</button>';
          } else if (maxed) {
            h += '      <span class="quest-badge ok">✓ 已满级</span>';
          } else if (locked) {
            h += '      <span class="quest-badge" style="opacity:0.65;">前置未满足</span>';
          } else if (isBusy) {
            h += '      <button type="button" class="btn sm tech-btn-action" disabled style="opacity:0.65;" title="军工科技研发中心正忙">正忙</button>';
          } else if (enough) {
            h += '      <button type="button" class="btn sm ok tech-btn-action" onclick="event.stopPropagation();Game.Tech.confirmResearch(\'' + id + '\')">🔬 研发</button>';
          } else {
            h += '      <button type="button" class="btn sm tech-btn-action" style="opacity:0.65;" onclick="event.stopPropagation();Game.Tech.confirmResearch(\'' + id + '\')">资源不足</button>';
          }
          h += '    </div>';
          h += '  </div>';

          // 效果简述
          h += '  <div class="tech-desc d">' + t.desc + '</div>';

          // 状态与升级条件微型行
          h += '  <div class="tech-info-row">';
          h += '    <span class="tech-effect-tag"><span class="tech-effect-lbl">' + (diff.currentLabel || '当前加成') + ': </span><b style="color:var(--ink);">' + (diff.currentText || diff.curStr) + '</b></span>';
          if (locked) {
            h += '    <span class="tech-lock-pill cost">' + lockText + '</span>';
          } else if (maxed) {
            h += '    <span class="tech-meta-box cost" style="color:var(--ok);">已达科技上限</span>';
          } else {
            h += '    <span class="tech-meta-box cost"><span>下一级: ' + costHtml(cost) + '</span><span class="tech-duration-text">⏱ ' + timeText(duration) + '</span></span>';
          }
          h += '  </div>';

          // 尚未满足时展开全部直接条件和建设入口，避免逐个补齐后才发现下一项。
          if (!maxed && G.Prerequisites) {
            h += '<details class="prereq-details"' + (locked ? ' open' : '') + ' onclick="event.stopPropagation()">' +
              '<summary class="prereq-summary"><span>Lv.' + (lv + 1) + ' 建筑要求</span></summary>' +
              G.Prerequisites.html('technologies', id, lv + 1) +
              '<div class="prereq-preview-foot">' +
              '<button type="button" class="link-act prereq-preview-btn" onclick="event.stopPropagation();Game.Prerequisites.preview(\'technologies\',\'' +
              id + '\',' + Math.min(t.max, lv + 1) + ')">查看各级前置要求 &gt;</button>' +
              '</div></details>';
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
