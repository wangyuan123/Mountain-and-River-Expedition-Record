/* global window, document, Game */
(function (G) {
  'use strict';
  G.gameNow = G.gameNow || function () { return Date.now(); };

  var Core = G.Core;
  var D = G.DATA;

  var state = { chapters: [] };

  function loadQuests() {
    var playerId = (Core.state && Core.state.player || {}).id;
    return G.API.getQuestList().then(function (data) {
      if (!isCurrentPlayer(playerId)) return [];
      state.chapters = data && data.chapters ? data.chapters : [];
      return state.chapters;
    });
  }

  function claimQuest(questId) { return G.API.claimQuest(questId); }
  function claimStage(stageId) { return G.API.claimStage(stageId); }

  function shortNum(n) {
    return n >= 10000 ? (n / 10000).toFixed(1) + '万' : String(n);
  }

  // ====================================================================
  //  主线任务面板
  // ====================================================================
  function renderQuestView(v) {
    var playerId = (Core.state && Core.state.player || {}).id;
    if (state.playerId !== playerId) {
      state.playerId = playerId;
      state.chapters = [];
      state.paper = null;
      state.answers = [];
      state.lastExamResult = null;
      stopExamTimer();
    }
    if (state.chapters.length) {
      drawQuestView(v);
    } else {
      v.innerHTML = '<div class="title">主线任务</div><div class="desc">加载中...</div>';
    }
    loadQuests().then(function () {
      if (isCurrentPlayer(playerId) && Core.route === 'mainQuest') drawQuestView(v);
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
    });
    if (G.Onboarding) G.Onboarding.refresh();
    var requestVersion = ++rankRequestVersion;
    if (G.API.getRankInfo) G.API.getRankInfo().then(function (rank) {
      // 交卷或授衔可能先于旧资格请求返回，只接受当前账号的最新请求。
      if (!isCurrentPlayer(playerId) || requestVersion !== rankRequestVersion) return;
      G.GameTime.sync(rank.serverTime);
      Core.state.rankInfo = rank;
      if (Core.route === 'mainQuest') drawQuestView(v);
    }).catch(function (err) {
      if (isCurrentPlayer(playerId) && requestVersion === rankRequestVersion) G.toast(err.message || '晋升资格加载失败');
    });
  }

  var examTimer = null;
  var rankBusy = false;
  var rankRequestVersion = 0;

  function isCurrentPlayer(playerId) {
    return !!Core.state && (Core.state.player || {}).id === playerId;
  }

  function stopExamTimer() {
    if (examTimer) window.clearInterval(examTimer);
    examTimer = null;
  }

  function doPromoteRank() {
    if (rankBusy) return;
    rankBusy = true;
    ++rankRequestVersion;
    var playerId = (Core.state.player || {}).id;
    return G.API.promoteRank().then(function (res) {
      if (!isCurrentPlayer(playerId)) return;
      ++rankRequestVersion;
      G.toast(res.message || '晋升成功');
      state.paper = null;
      state.answers = [];
      state.lastExamResult = null;
      stopExamTimer();
      return loadQuests();
    }).catch(function (err) {
      G.toast(err.message || '晋升失败');
    }).finally(function () {
      rankBusy = false;
      if (Core && Core.render) Core.render();
    });
  }

  function answerKey(paper) {
    return 'rank-exam:' + paper.playerId + ':' + paper.attemptId;
  }

  /** 恢复服务端同一份试卷；本地只保存选项，不参与判分。 */
  function startRankExam() {
    if (rankBusy) return;
    rankBusy = true;
    ++rankRequestVersion;
    var playerId = (Core.state.player || {}).id;
    return G.API.startRankExam().then(function (paper) {
      if (!isCurrentPlayer(playerId)) return;
      ++rankRequestVersion;
      paper.playerId = playerId;
      G.GameTime.sync(paper.serverTime);
      state.paper = paper;
      state.answers = [];
      try { state.answers = JSON.parse(window.localStorage.getItem(answerKey(paper)) || '[]'); } catch (e) {}
      if (!Array.isArray(state.answers)) state.answers = [];
      state.answers = paper.questions.map(function (q, i) {
        var answer = state.answers[i];
        return Number.isInteger(answer) && answer >= 0 && answer < q.options.length ? answer : null;
      });
    }).catch(function (err) {
      G.toast(err.message || '考试暂不可用');
    }).finally(function () {
      rankBusy = false;
      if (Core.render) Core.render();
    });
  }

  function chooseAnswer(index, value) {
    if (!state.paper || !isCurrentPlayer(state.paper.playerId) || rankBusy
        || !Number.isInteger(index) || index < 0 || index >= state.paper.questions.length
        || !Number.isInteger(value) || value < 0 || value >= state.paper.questions[index].options.length) return;
    state.answers[index] = value;
    try { window.localStorage.setItem(answerKey(state.paper), JSON.stringify(state.answers)); } catch (e) {}
    updateExamClock();
  }

  function answeredCount() {
    if (!state.paper) return 0;
    return state.paper.questions.filter(function (q, i) {
      return Number.isInteger(state.answers[i]) && state.answers[i] >= 0 && state.answers[i] < q.options.length;
    }).length;
  }

  function remainingSeconds() {
    return state.paper ? Math.max(0, Math.ceil((state.paper.expiresAt - G.gameNow()) / 1000)) : 0;
  }

  /** 交卷失败保留答题记录，成功后只保存服务器返回的成绩。 */
  function submitRankExam() {
    if (rankBusy || !state.paper || !isCurrentPlayer(state.paper.playerId)) return;
    if (remainingSeconds() > 0 && answeredCount() < state.paper.questions.length) {
      G.toast('请完成所有题目后交卷');
      return;
    }
    rankBusy = true;
    ++rankRequestVersion;
    updateExamClock();
    var paper = state.paper;
    return G.API.submitRankExam(paper.attemptId, state.answers.slice()).then(function (res) {
      if (!isCurrentPlayer(paper.playerId)) return;
      ++rankRequestVersion;
      state.lastExamResult = { playerId: paper.playerId, tier: paper.tier, data: res };
      G.GameTime.sync(res.rankInfo.serverTime);
      Core.state.rankInfo = res.rankInfo;
      try { window.localStorage.removeItem(answerKey(paper)); } catch (e) {}
      state.paper = null;
      state.answers = [];
      stopExamTimer();
      G.toast(res.message);
    }).catch(function (err) { G.toast(err.message || '交卷失败，请重试'); })
      .finally(function () { rankBusy = false; if (Core.render) Core.render(); });
  }

  function updateExamClock() {
    if (Core.route !== 'mainQuest' || !state.paper || !isCurrentPlayer(state.paper.playerId)) {
      stopExamTimer();
      return;
    }
    var seconds = remainingSeconds();
    var clock = document.getElementById('rank-exam-clock');
    if (clock) clock.textContent = seconds ? G.formatDuration(seconds) : '已超时';
    var count = document.getElementById('rank-exam-count');
    if (count) count.textContent = answeredCount() + '/' + state.paper.questions.length;
    document.querySelectorAll('.rank-exam-question input').forEach(function (input) {
      input.disabled = rankBusy || seconds === 0;
    });
    var button = document.getElementById('rank-exam-submit');
    if (button) {
      button.disabled = rankBusy || (seconds > 0 && answeredCount() < state.paper.questions.length);
      button.textContent = rankBusy ? '提交中...' : seconds ? '交卷' : '结束考试';
    }
  }

  function renderExamPaper(paper) {
    var h = '<section class="rank-exam" aria-label="军事晋升笔试">';
    h += '<div class="rank-exam-heading"><div>' + G.renderMilitaryRankIcon(paper.tier) + '<h2>' + escapeHtml(paper.rankName) + ' · 军事笔试</h2></div>';
    h += '<div class="rank-exam-time">剩余 <b id="rank-exam-clock">' + (remainingSeconds() ? G.formatDuration(remainingSeconds()) : '已超时') + '</b></div></div>';
    h += '<div class="rank-exam-meta">单选题 · ' + paper.questions.length + '题 · 合格线 ' + paper.requiredCorrect + '题</div>';
    paper.questions.forEach(function (q, i) {
      h += '<fieldset class="rank-exam-question"><legend>' + (i + 1) + '. ' + escapeHtml(q.prompt) + '</legend>';
      q.options.forEach(function (option, n) {
        h += '<label><input type="radio" name="rank-question-' + i + '" value="' + n + '"' +
          (state.answers[i] === n ? ' checked' : '') + (rankBusy || remainingSeconds() === 0 ? ' disabled' : '') +
          ' onchange="Game.MainQuest.chooseAnswer(' + i + ',' + n + ')">' +
          '<span>' + String.fromCharCode(65 + n) + '. ' + escapeHtml(option) + '</span></label>';
      });
      h += '</fieldset>';
    });
    h += '<div class="rank-exam-actions"><span>已答 <b id="rank-exam-count">' + answeredCount() + '/' + paper.questions.length + '</b></span>';
    h += '<button type="button" class="btn" onclick="Game.MainQuest.leaveExam()">返回任务</button>';
    h += '<button type="button" id="rank-exam-submit" class="btn ok" onclick="Game.MainQuest.submitRankExam()" disabled>交卷</button></div></section>';
    return h;
  }

  function leaveExam() {
    state.paper = null;
    stopExamTimer();
    if (Core.render) Core.render();
  }

  function rankRequirement(label, actual, required, enough) {
    var pct = required > 0 ? Math.min(100, Math.round(actual / required * 100)) : 100;
    return '<div class="rank-requirement"><div><span>' + escapeHtml(label) + '</span><b class="' +
      (enough ? 'rank-met' : 'rank-unmet') + '">' + G.fmt(actual) + ' / ' + G.fmt(required) +
      '</b></div><div class="quest-progress-bar"><div class="quest-progress-fill" style="width:' + pct + '%"></div></div></div>';
  }

  /** 晋升界面只展示服务端计算的资格，不在浏览器重新判定考试合格。 */
  function renderRankQuestCard() {
    var s = Core.state || {};
    var p = s.player || {};
    var rank = s.rankInfo;
    if (state.paper && state.paper.playerId === p.id) return renderExamPaper(state.paper);
    if (!rank) return '<section class="rank-promotion"><h2>军衔晋升</h2><div>加载中...</div></section>';
    var h = '<section class="rank-promotion"><div class="rank-exam-heading"><div>' + G.renderMilitaryRankIcon(rank.tier) +
      '<h2>' + escapeHtml(rank.name) + ' · 第' + rank.tier + '/17阶</h2></div><span>基础出兵 ' + G.fmt(rank.baseCap) + '</span></div>';
    if (s.cityOverview) {
      h += '<div class="city-hint">城市名额 ' + s.cityOverview.count + '/' + s.cityOverview.cap;
      if (rank.nextCityRankName) h += ' · ' + escapeHtml(rank.nextCityRankName) + '可拥有' + rank.nextExpansionCap + '座';
      h += '</div>';
    }
    if (rank.isMax) return h + '<div class="rank-met">已达最高军衔</div></section>';
    h += '<div class="rank-target">下一阶：<b>' + escapeHtml(rank.nextName) + '</b> · 基础出兵 ' + G.fmt(rank.nextBaseCap) + '</div>';
    h += rankRequirement('声望', rank.prestige, rank.reqPrestige, rank.prestigeEnough);
    h += rankRequirement('累计剿寇胜场', rank.npcVictories, rank.reqNpcVictories, rank.victoriesEnough);
    var cert = rank.certificate;
    h += rankRequirement(cert.name, cert.owned, cert.required, rank.certificateEnough);
    h += '<div class="rank-source">' + escapeHtml(cert.source) + '</div>';
    var exam = rank.exam || {};
    var cooling = exam.retryAt > G.gameNow();
    var statuses = { not_started: '未参加', active: '进行中', failed: '未合格', expired: '已超时', passed: '已合格' };
    h += '<div class="rank-exam-status"><span>军事笔试 · ' + exam.questionCount + '题，至少答对' + exam.requiredCorrect + '题</span>' +
      '<b class="' + (rank.examPassed ? 'rank-met' : 'rank-unmet') + '">' + (statuses[exam.status] || '未参加') + '</b></div>';
    if (state.lastExamResult && state.lastExamResult.playerId === p.id && state.lastExamResult.tier === rank.nextTier) {
      var result = state.lastExamResult.data;
      h += '<div class="rank-score" role="status">本次成绩：' + result.correct + '/' + result.total + ' · ' + escapeHtml(result.message) + '</div>';
    }
    h += '<div class="rank-promotion-actions">';
    if (cooling) {
      h += '<span>下次考试：' + escapeHtml(new Date(G.GameTime ? G.GameTime.toWallTime(exam.retryAt) : exam.retryAt).toLocaleTimeString()) + '</span>' +
        '<button type="button" class="btn" onclick="Game.Core.render()">刷新资格</button>';
    } else if (rank.canPromote) {
      h += '<button type="button" class="btn ok" onclick="Game.MainQuest.doPromoteRank()"' + (rankBusy ? ' disabled' : '') + '>授衔晋升</button>';
    } else {
      h += '<button type="button" class="btn ok" onclick="Game.MainQuest.startRankExam()"' +
        (!rank.canTakeExam || rankBusy ? ' disabled' : '') + '>' + (exam.status === 'active' ? '继续军事笔试' : '参加军事笔试') + '</button>';
    }
    h += '<button type="button" class="btn" onclick="Game.go(\'world\')">前往剿寇</button></div></section>';
    return h;
  }

  function drawQuestView(v) {
    if (!v) return;
    var h = '<div class="main-quest-view">';
    h += '<div class="title">战略主线与晋升</div>';
    h += '<div class="desc main-quest-summary">完成多阶段主线目标，获取海量战备资源、黄金与钻石通关大礼包；晋升统帅军衔拓展城池与出兵容量！</div>';
    h += '<div class="ob-actions" style="margin-bottom:12px;"><button class="btn" onclick="Game.go(\'depot\')">📦 打开我的物资仓库</button></div>';

    h += renderExplorationTasks();

    // 军衔晋升专区
    h += renderRankQuestCard();

    h += '<div class="zone-head"><span class="zone-title">📜 主线进阶任务</span><span class="zone-sub">阶段渐进挑战</span></div>';
    for (var ci = 0; ci < state.chapters.length; ci++) {
      var ch = state.chapters[ci];
      var total = ch.totalCount || ch.quests.length;
      var claimed = ch.claimedCount || 0;
      var completed = ch.completedCount || 0;
      var stageStatus = ch.status || 'in_progress';
      var isLocked = stageStatus === 'locked';
      var isClaimed = stageStatus === 'claimed';
      var canClaimStage = ch.canClaimStage || false;
      var pct = total ? Math.round(claimed / total * 100) : 0;

      var panelClass = 'panel' + (isLocked ? ' quest-stage-locked-panel' : '');
      h += '<div class="' + panelClass + '">';
      h += '<div class="quest-chapter-head" style="display:flex;justify-content:space-between;align-items:center;">';
      h += '  <div class="quest-chapter-name" style="font-size:16px;font-weight:bold;color:var(--ink);display:flex;align-items:center;gap:8px;">';
      h += '    <span>' + escapeHtml(ch.name) + '</span>';
      if (isClaimed) {
        h += '    <span class="quest-badge ok">✓ 阶段已通关</span>';
      } else if (canClaimStage) {
        h += '    <span class="quest-badge warn" style="animation:pulse 1.5s infinite;">🎁 阶段大奖可领取</span>';
      } else if (isLocked) {
        h += '    <span class="quest-badge">🔒 未解锁</span>';
      } else {
        h += '    <span class="quest-badge" style="background:rgba(212,163,89,0.2);color:#926829;">挑战中</span>';
      }
      h += '  </div>';
      h += '  <div class="quest-chapter-progress" style="font-size:13px;font-weight:bold;">' + claimed + '/' + total + ' (' + pct + '%)</div>';
      h += '</div>';
      h += '<div class="quest-chapter-intro" style="font-size:13px;color:var(--muted);margin-bottom:8px;">' + escapeHtml(ch.intro) + '</div>';
      h += '<div class="quest-progress-bar" style="margin-bottom:10px;"><div class="quest-progress-fill" style="width:' + pct + '%"></div></div>';

      // 阶段专属通关大礼包专区
      var giftDef = (D.items && D.items[ch.giftKey]) || { name: ch.giftName || '阶段专属礼包', icon: '🎁', desc: '各类海量资源+黄金+钻石' };
      h += '<div class="quest-stage-gift-card">';
      h += '  <div class="quest-stage-gift-info">';
      h += '    <div class="quest-stage-gift-icon">' + giftDef.icon + '</div>';
      h += '    <div class="quest-stage-gift-details">';
      h += '      <div class="quest-stage-gift-title">阶段通关大奖：【' + escapeHtml(ch.giftName || giftDef.name) + '】</div>';
      h += '      <div class="quest-stage-gift-desc">' + escapeHtml(giftDef.desc) + '</div>';
      h += '    </div>';
      h += '  </div>';
      h += '  <div class="quest-stage-gift-action">';
      if (isClaimed) {
        h += '    <div class="quest-stage-claimed-tip">';
        h += '      <span>✓ 礼包已入库</span>';
        h += '      <button class="btn sm" onclick="Game.go(\'depot\')">去开启</button>';
        h += '    </div>';
      } else if (canClaimStage) {
        h += '    <button class="quest-stage-claim-btn" data-stage-id="' + ch.id + '">🎁 领取阶段大奖</button>';
      } else if (isLocked) {
        h += '    <button class="btn sm" disabled style="opacity:0.5;">🔒 完成前一阶段解锁</button>';
      } else {
        h += '    <span style="font-size:12px;color:var(--muted);">完成本阶段全部任务即可领取</span>';
      }
      h += '  </div>';
      h += '</div>';

      // 子任务列表
      if (!isLocked) {
        for (var qj = 0; qj < ch.quests.length; qj++) {
          h += renderQuestItem(ch.quests[qj]);
        }
      } else {
        h += '<div style="padding:10px;text-align:center;color:var(--muted);font-size:12px;background:rgba(0,0,0,0.03);border-radius:6px;margin-top:6px;">';
        h += '  🔒 本阶段任务目前处于锁定状态，请先完成上一阶段的所有任务并领取通关大礼包以解锁！';
        h += '</div>';
      }

      h += '</div>';
    }
    h += '</div>';
    v.innerHTML = h;
    updateExamClock();
    if (state.paper) {
      if (!examTimer) examTimer = window.setInterval(updateExamClock, 1000);
    }
    bindClaimHandlers();
  }

  function renderQuestItem(q) {
    var status = q.status || 'in_progress';
    var progress = q.progress || 0;
    var target = q.target || 1;
    var pct = Math.min(100, Math.round(progress / target * 100));

    var badge = '';
    var canClaim = false;
    if (status === 'claimed') {
      badge = '<span class="quest-badge ok">✓ 已领取</span>';
    } else if (status === 'completed') {
      badge = '<span class="quest-badge warn">可领取</span>';
      canClaim = true;
    } else if (status === 'locked') {
      badge = '<span class="quest-badge">未解锁</span>';
    } else {
      badge = '<span class="quest-badge">进行中</span>';
    }

    var rewardText = formatReward(q.reward);
    var destination = (G.Constants.mainQuestDestinations || {})[q.id];

    return '<div class="quest-row" data-quest-id="' + q.id + '">' +
      '<div class="quest-row-top">' +
        '<div class="quest-title">' + escapeHtml(q.title) + badge + '</div>' +
        '<div class="quest-reward">' + rewardText + '</div>' +
      '</div>' +
      '<div class="quest-desc">' + escapeHtml(q.desc) + '</div>' +
      (q.id === 'q3_2' && G.Prerequisites ? G.Prerequisites.recruitmentHtml('destroyer') : '') +
      '<div class="quest-progress-row">' +
        '<div class="quest-progress-bar small"><div class="quest-progress-fill" style="width:' + pct + '%"></div></div>' +
        '<div class="quest-progress-text">' + progress + '/' + target + '</div>' +
      '</div>' +
      (canClaim ?
        '<button class="btn sm ok quest-claim-btn" data-id="' + q.id + '">领取奖励</button>' :
        status === 'in_progress' && (destination || campaignTaskMode(q.id)) ?
          '<button type="button" class="btn sm quest-claim-btn" data-quest-goto="' + escapeHtml(q.id) +
          '" aria-label="去完成：' + escapeHtml(q.title) + '">去完成</button>' : '') +
    '</div>';
  }

  /** 只导航进行中的任务，复用现有页面和弹窗，不代替玩家执行任务操作。 */
  function gotoQuest(id) {
    var quest = state.chapters.flatMap(function (chapter) { return chapter.quests || []; })
      .find(function (q) { return q.id === id; });
    if (!quest || (quest.status || 'in_progress') !== 'in_progress' || !Core.state) return;
    if (campaignTaskMode(id)) { gotoCampaign(id); return; }
    var destination = (G.Constants.mainQuestDestinations || {})[id];
    if (!destination) return;
    if (destination === 'commanderName') { G.Main.promptRenameCommander(); return; }
    if (destination === 'cityName') {
      // 任务要求修改主城名，分城中先让玩家选择主城，避免引导改错城市。
      if ((Core.state.player || {}).mainCity === false) {
        G.Cities.open();
        G.toast('请先切换至主城，再从任务入口修改主城名称');
      } else G.Main.promptRenameCityInDrawer();
      return;
    }
    if (destination === 'cities') { G.Cities.open(); return; }
    if (destination === 'rank') {
      var panel = document.querySelector('.rank-promotion');
      if (panel) panel.scrollIntoView({ block: 'start' });
      return;
    }
    if (destination === 'forum') { Core.openForum(); return; }
    if (destination === 'wild' || destination === 'player' || destination === 'npc') {
      Core.state.world = Core.state.world || {};
      Core.state.world._activeTab = destination;
      G.go('world');
      if (G.WorldMap) G.WorldMap.setMode('list');
      return;
    }
    if (destination === 'officer' && !(Core.state.officers || []).length) destination = 'academy';
    G.go(destination);
    // 玩家可能上次停留在生产队列，征兵任务应回到可选择兵种的界面。
    if (destination === 'army' && G.Army) G.Army.setTab('units');
  }

  /** 玩法探索位于任务页前部，各任务独立解锁，点击时才提供首次提示与定位。 */
  function renderExplorationTasks() {
    var guide = G.Onboarding && G.Onboarding.state.data;
    var h = '<section class="panel exploration-tasks"><div class="zone-head"><span class="zone-title">玩法探索</span><span class="zone-sub">按自己的节奏体验</span></div>';
    h += '<button class="btn sm" onclick="Game.go(\'onboarding\')">' + (guide && guide.done ? '查看新手行动记录' : '查看核心行动指引') + '</button>';
    if (!guide) return h + '<p>正在读取探索任务…</p></section>';
    var statuses = { locked: '准备条件', available: '可探索', in_progress: '进行中', completed: '可领取', claimed: '已领取' };
    (guide.optionalTasks || []).forEach(function (task) {
      var label = statuses[task.status] || '可探索';
      h += '<div class="quest-row" data-exploration-id="' + escapeHtml(task.id) + '"><div class="quest-row-top"><div class="quest-title">' + escapeHtml(task.title) +
        '<span class="quest-badge">' + label + '</span></div><div class="quest-reward">' + formatReward(task.reward) + '</div></div><p class="quest-desc">' + escapeHtml(task.body) + '</p>';
      if (task.status === 'locked') h += '<p class="ob-muted">' + escapeHtml(task.unlockHint) + '</p>';
      if (task.status === 'completed') h += '<button class="btn sm ok" data-exploration-claim="' + escapeHtml(task.id) + '">领取奖励</button>';
      else if (task.status !== 'claimed') h += '<button class="btn sm" data-exploration-start="' + escapeHtml(task.id) + '">' + (task.status === 'locked' ? '查看准备指引' : task.status === 'in_progress' ? '查看采集' : '去完成') + '</button>';
      h += '</div>';
    });
    return h + '</section>';
  }

  /** 任务事件仍区分两种战果，只给下一次对应目标的讨伐草稿预选处置模式。 */
  function campaignTaskMode(id) {
    return {
      q2_4: { kind: 'wild', occupyTarget: false },
      q2_5: { kind: 'wild', occupyTarget: true },
      q4_3: { kind: 'player', occupyTarget: false },
      q4_4: { kind: 'player', occupyTarget: true }
    }[id];
  }

  /**
   * 从进行中的任务进入对应目标列表，预选下一次讨伐的占领配置。
   * @param {string} id - 主线任务标识
   * @returns {void}
   */
  function gotoCampaign(id) {
    var quest = state.chapters.flatMap(function (chapter) { return chapter.quests || []; })
      .find(function (q) { return q.id === id; });
    var mode = campaignTaskMode(id);
    if (!mode || !quest || (quest.status || 'in_progress') !== 'in_progress') return;
    Core.state.world._campaignTaskMode = mode;
    Core.state.world._activeTab = mode.kind;
    G.go('world');
    if (G.WorldMap) G.WorldMap.setMode('list');
    G.toast('请选择' + (mode.kind === 'wild' ? '野地' : '玩家城市') + '讨伐；本次任务预选「' + (mode.occupyTarget ? '征服' : '掠夺') + '」');
  }

  var EQUIPMENT_NAMES = G.Constants.equipmentNames || {};

  function itemLabel(key) {
    if (!key) return '';
    if (D && D.items && D.items[key] && D.items[key].name) {
      return D.items[key].name;
    }
    if (EQUIPMENT_NAMES[key]) {
      return EQUIPMENT_NAMES[key];
    }
    return key;
  }

  function formatReward(r) {
    if (!r) return '';
    var parts = [];
    if (r.food) parts.push('粮' + shortNum(r.food));
    if (r.steel) parts.push('钢' + shortNum(r.steel));
    if (r.oil) parts.push('油' + shortNum(r.oil));
    if (r.rare) parts.push('稀' + shortNum(r.rare));
    if (r.gold) parts.push('金' + shortNum(r.gold));
    if (r.skillBook) parts.push('技能书×' + r.skillBook);
    if (r.expBook) parts.push('经验书×' + r.expBook);
    if (r.itemKey && r.itemCount) parts.push(itemLabel(r.itemKey) + '×' + r.itemCount);
    if (parts.length === 0) return '无';
    return '奖励: ' + parts.join(' ');
  }

  function bindClaimHandlers() {
    document.querySelectorAll('[data-quest-goto]').forEach(function (button) {
      button.onclick = function () { gotoQuest(button.getAttribute('data-quest-goto')); };
    });
    document.querySelectorAll('[data-exploration-start]').forEach(function (button) {
      button.onclick = function () { G.Onboarding.beginTask(button.getAttribute('data-exploration-start')); };
    });
    document.querySelectorAll('[data-exploration-claim]').forEach(function (button) {
      button.onclick = function () { G.Onboarding.mutate('tasks/claim', { taskId: button.getAttribute('data-exploration-claim') }); };
    });
    // 单项任务领取
    var btns = document.querySelectorAll('.quest-claim-btn[data-id]');
    for (var i = 0; i < btns.length; i++) {
      btns[i].onclick = function () {
        var id = this.getAttribute('data-id');
        var self = this;
        self.disabled = true;
        claimQuest(id).then(function (r) {
          if (G.Sound && typeof G.Sound.play === 'function') G.Sound.play('coins');
          G.toast((r && r.message) || '已领取');
          return loadQuests();
        }).then(function () {
          Core.render();
        }).catch(function (err) {
          G.toast(err && err.message ? err.message : '领取失败');
          self.disabled = false;
        });
      };
    }

    // 阶段大奖领取
    var stageBtns = document.querySelectorAll('.quest-stage-claim-btn');
    for (var j = 0; j < stageBtns.length; j++) {
      stageBtns[j].onclick = function () {
        var stageId = this.getAttribute('data-stage-id');
        var self = this;
        self.disabled = true;
        claimStage(stageId).then(function (r) {
          if (G.Sound && typeof G.Sound.play === 'function') G.Sound.play('coins');
          var msg = (r && r.message) || '阶段通关大礼包已发放到您的仓库背包！';
          G.toast(msg);
          return loadQuests();
        }).then(function () {
          Core.render();
        }).catch(function (err) {
          G.toast(err && err.message ? err.message : '领取失败');
          self.disabled = false;
        });
      };
    }
  }

  // ====================================================================
  //  注册主路由
  // ====================================================================
  function registerRoutes() {
    if (G.Core && G.Core.views && !G.Core.views.mainQuest) {
      G.Core.views.mainQuest = renderQuestView;
    }
  }

  // ====================================================================
  //  初始化钩子 (在 main.js 启动后调用)
  // ====================================================================
  function init() {
    registerRoutes();
    state.chapters = [];
    return Promise.all([loadQuests().catch(function () { return []; }), G.Onboarding.init()])
      .then(function (results) {
        if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
        return { quests: results[0], guide: results[1] };
      });
  }

  function refresh() {
    return G.Onboarding.refresh();
  }

  function hasUnclaimed() {
    if (G.Onboarding && G.Onboarding.hasSupply()) return true;
    for (var i = 0; i < state.chapters.length; i++) {
      var ch = state.chapters[i];
      if (ch.status === 'completed' || ch.canClaimStage) return true;
      var qs = ch.quests || [];
      for (var j = 0; j < qs.length; j++) {
        if (qs[j].status === 'completed') return true;
      }
    }
    return false;
  }

  function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  G.MainQuest = {
    init: init,
    refresh: refresh,
    loadQuests: loadQuests,
    renderQuestView: renderQuestView,
    drawQuestView: drawQuestView,
    hasUnclaimed: hasUnclaimed,
    doPromoteRank: doPromoteRank,
    startRankExam: startRankExam,
    submitRankExam: submitRankExam,
    chooseAnswer: chooseAnswer,
    gotoCampaign: gotoCampaign,
    gotoQuest: gotoQuest,
    leaveExam: leaveExam,
    state: state
  };
})(window.Game);
