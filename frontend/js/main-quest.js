/* global window, document, Game */
(function (G) {
  'use strict';

  var Core = G.Core;
  var D = G.DATA;

  var state = { chapters: [] };

  function loadQuests() {
    return G.API.getQuestList().then(function (data) {
      state.chapters = data && data.chapters ? data.chapters : [];
      return state.chapters;
    });
  }

  function claimQuest(questId) { return G.API.claimQuest(questId); }

  function shortNum(n) {
    return n >= 10000 ? (n / 10000).toFixed(1) + '万' : String(n);
  }

  // ====================================================================
  //  主线任务面板
  // ====================================================================
  function renderQuestView(v) {
    if (state.chapters.length) {
      drawQuestView(v);
    } else {
      v.innerHTML = '<div class="title">主线任务</div><div class="desc">加载中...</div>';
    }
    loadQuests().then(function () {
      if (Core.route === 'mainQuest') drawQuestView(v);
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
    });
  }

  function doPromoteRank() {
    if (!G.API || !G.API.promoteRank) return;
    G.API.promoteRank().then(function (res) {
      if (res && res.message) {
        G.toast(res.message);
      } else {
        G.toast('晋升成功！');
      }
      if (Core && Core.render) Core.render();
    }).catch(function (err) {
      G.toast(err.message || '晋升失败');
    });
  }

  function renderRankQuestCard() {
    var s = Core.state || {};
    var p = s.player || {};
    var rankTier = p.militaryRank || 1;
    var rankInfo = G.getMilitaryRankTierInfo ? G.getMilitaryRankTierInfo(rankTier) : { name: '列兵', tier: 1, baseCap: 1000, isMax: false };
    var prestige = s.prestige != null ? s.prestige : (p.prestige != null ? p.prestige : 0);
    var items = s.items || {};

    var h = '<div class="panel" style="margin-bottom:14px;border:1px solid rgba(212,163,89,0.35);background:linear-gradient(135deg, rgba(212,163,89,0.06), rgba(0,0,0,0.2));border-radius:8px;padding:12px 14px;">';
    h += '<div class="quest-chapter-head" style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;">';
    h += '  <div class="quest-chapter-name" style="font-size:15px;color:var(--accent);font-weight:bold;display:flex;align-items:center;gap:6px;">';
    h += '    <span>🎖️</span> <span>统帅军衔 · 【' + escapeHtml(rankInfo.name) + '】</span>';
    h += '    <span style="font-size:11px;padding:2px 6px;border-radius:4px;background:rgba(212,163,89,0.2);color:var(--ink);">第 ' + rankInfo.tier + ' / 17 阶</span>';
    h += '  </div>';
    h += '  <div style="font-size:12px;color:var(--muted);">基础出兵容量: <b style="color:var(--ink);">' + G.fmt(rankInfo.baseCap) + '</b></div>';
    h += '</div>';

    if (s.cityOverview) {
      var cities = s.cityOverview;
      h += '<div class="city-hint">城市名额：<b>' + cities.count + ' / ' + cities.cap + '</b>（包含主城）';
      if (cities.nextRankName) h += ' · 晋升' + escapeHtml(cities.nextRankName) + '后可拥有 ' + cities.nextCap + ' 座';
      else h += ' · 已达城市数量上限';
      h += '</div>';
    }
    if (rankInfo.isMax) {
      h += '<div style="font-size:13px;color:var(--ok);padding:10px;background:rgba(82,196,26,0.1);border-radius:6px;margin-top:6px;display:flex;align-items:center;justify-content:space-between;">';
      h += '  <span>⭐ 已晋升至终极统帅军衔【上将】！基础出兵上限 20,000，享集团军出征极限！</span>';
      h += '  <span class="quest-badge ok">顶峰</span>';
      h += '</div>';
      h += '</div>';
      return h;
    }

    // 下一阶目标
    var nextTier = rankInfo.nextTier;
    var nextName = rankInfo.nextName;
    var nextBaseCap = rankInfo.nextBaseCap;
    var reqPrestige = rankInfo.nextPrestige || 0;
    var reqGems = rankInfo.reqGems || {};

    var prestigeEnough = prestige >= reqPrestige;
    var allGemsEnough = true;

    h += '<div style="font-size:12px;color:var(--muted);margin-bottom:10px;">';
    h += '  下一阶晋升: <b style="color:var(--accent);">【' + escapeHtml(nextName) + '】</b>（基础带兵容量提升至 <b style="color:var(--ink);">' + G.fmt(nextBaseCap) + '</b>）';
    h += '</div>';

    // 1. 声望要求
    var presPct = reqPrestige > 0 ? Math.min(100, Math.round(prestige / reqPrestige * 100)) : 100;
    h += '<div style="background:rgba(255,255,255,0.03);border-radius:6px;padding:8px 10px;margin-bottom:8px;border:1px solid rgba(255,255,255,0.05);">';
    h += '  <div style="display:flex;justify-content:space-between;align-items:center;font-size:12px;margin-bottom:4px;">';
    h += '    <span>👑 声望要求: <b>' + G.fmt(prestige) + '</b> / ' + G.fmt(reqPrestige) + '</span>';
    h += '    <span style="color:' + (prestigeEnough ? 'var(--ok)' : 'var(--danger)') + ';font-weight:bold;">' + (prestigeEnough ? '✓ 已达成' : ('差 ' + G.fmt(reqPrestige - prestige))) + '</span>';
    h += '  </div>';
    h += '  <div class="quest-progress-bar" style="margin:0;height:5px;"><div class="quest-progress-fill" style="width:' + presPct + '%;background:' + (prestigeEnough ? 'var(--ok)' : 'var(--accent)') + '"></div></div>';
    h += '</div>';

    // 2. 珠宝要求
    var gemKeys = Object.keys(reqGems);
    if (gemKeys.length > 0) {
      h += '<div style="font-size:12px;color:var(--ink);margin-bottom:6px;font-weight:bold;display:flex;justify-content:space-between;">';
      h += '  <span>💎 所需晋升珠宝 (野地采集产出):</span>';
      h += '  <span style="font-size:11px;color:var(--accent);cursor:pointer;" onclick="Game.go(\'world\')">前往野地采集 ›</span>';
      h += '</div>';
      h += '<div style="display:grid;grid-template-columns:repeat(auto-fill, minmax(130px, 1fr));gap:6px;margin-bottom:10px;">';
      for (var gi = 0; gi < gemKeys.length; gi++) {
        var gk = gemKeys[gi];
        var reqCnt = reqGems[gk];
        var ownedCnt = items[gk] || 0;
        var gDef = (D.items && D.items[gk]) || { name: gk, icon: '💎' };
        var isEnough = ownedCnt >= reqCnt;
        if (!isEnough) allGemsEnough = false;

        h += '<div style="background:rgba(0,0,0,0.25);border:1px solid ' + (isEnough ? 'rgba(82,196,26,0.3)' : 'rgba(255,255,255,0.08)') + ';border-radius:6px;padding:6px 8px;display:flex;align-items:center;justify-content:space-between;">';
        h += '  <div style="display:flex;align-items:center;gap:5px;font-size:12px;">';
        h += '    <span>' + gDef.icon + '</span>';
        h += '    <span>' + escapeHtml(gDef.name) + '</span>';
        h += '  </div>';
        h += '  <div style="font-size:12px;font-weight:bold;color:' + (isEnough ? 'var(--ok)' : 'var(--danger)') + '">';
        h += '    ' + ownedCnt + '/' + reqCnt + (isEnough ? ' ✓' : '');
        h += '  </div>';
        h += '</div>';
      }
      h += '</div>';
    }

    // 3. 晋升操作按钮
    var canPromote = prestigeEnough && allGemsEnough;
    h += '<div style="display:flex;justify-content:space-between;align-items:center;margin-top:10px;padding-top:8px;border-top:1px solid rgba(255,255,255,0.06);">';
    h += '  <div style="font-size:11px;color:var(--muted);">';
    if (canPromote) {
      h += '    <span style="color:var(--ok);">🎉 晋升条件已达成，可立即受衔！</span>';
    } else if (!prestigeEnough) {
      h += '    <span>声望尚不足，参加战役或击溃叛军可积累声望</span>';
    } else {
      h += '    <span>珠宝不足，派遣军队前往野地采集可获取各类珠宝</span>';
    }
    h += '  </div>';
    if (canPromote) {
      h += '  <button class="btn ok" style="padding:6px 18px;font-size:13px;font-weight:bold;" onclick="Game.MainQuest.doPromoteRank()">授衔晋升</button>';
    } else {
      h += '  <button class="btn" style="padding:6px 16px;font-size:13px;opacity:0.5;cursor:not-allowed;" disabled>条件不足</button>';
    }
    h += '</div>';

    h += '</div>';
    return h;
  }

  function drawQuestView(v) {
    var h = '<div class="main-quest-view">';
    h += '<div class="title">任务与军衔</div>';
    h += '<div class="desc main-quest-summary">晋升军衔解锁更强战略出兵容量，完成各章任务获得丰厚战备资源。</div>';
    h += '<div class="ob-actions"><button class="btn" onclick="Game.go(\'onboarding\')">前进基地行动与补给</button></div>';

    // 军衔晋升专区
    h += renderRankQuestCard();

    // 活动与任务（原首页"活动与任务"块迁到这里）
    h += (G.Task && G.Task.renderActivities) ? G.Task.renderActivities() : '';

    h += '<div class="zone-head"><span class="zone-title">📜 主线任务</span><span class="zone-sub">章节挑战</span></div>';
    for (var ci = 0; ci < state.chapters.length; ci++) {
      var ch = state.chapters[ci];
      var total = ch.quests.length;
      var claimed = 0, completed = 0;
      for (var qi = 0; qi < ch.quests.length; qi++) {
        if (ch.quests[qi].status === 'claimed') claimed++;
        if (ch.quests[qi].status === 'completed' || ch.quests[qi].status === 'claimed') completed++;
      }
      var pct = total ? Math.round(claimed / total * 100) : 0;
      h += '<div class="panel">';
      h += '<div class="quest-chapter-head">';
      h += '<div class="quest-chapter-name">' + escapeHtml(ch.name) + '</div>';
      h += '<div class="quest-chapter-progress">' + claimed + '/' + total + ' (' + pct + '%)</div>';
      h += '</div>';
      h += '<div class="quest-chapter-intro">' + escapeHtml(ch.intro) + '</div>';
      h += '<div class="quest-progress-bar"><div class="quest-progress-fill" style="width:' + pct + '%"></div></div>';

      for (var qj = 0; qj < ch.quests.length; qj++) {
        h += renderQuestItem(ch.quests[qj]);
      }
      h += '</div>';
    }
    h += '</div>';
    v.innerHTML = h;
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

    return '<div class="quest-row" data-quest-id="' + q.id + '">' +
      '<div class="quest-row-top">' +
        '<div class="quest-title">' + escapeHtml(q.title) + badge + '</div>' +
        '<div class="quest-reward">' + rewardText + '</div>' +
      '</div>' +
      '<div class="quest-desc">' + escapeHtml(q.desc) + '</div>' +
      '<div class="quest-progress-row">' +
        '<div class="quest-progress-bar small"><div class="quest-progress-fill" style="width:' + pct + '%"></div></div>' +
        '<div class="quest-progress-text">' + progress + '/' + target + '</div>' +
      '</div>' +
      (canClaim ?
        '<button class="btn sm ok quest-claim-btn" data-id="' + q.id + '">领取奖励</button>' :
        '') +
    '</div>';
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
    var btns = document.querySelectorAll('.quest-claim-btn');
    for (var i = 0; i < btns.length; i++) {
      btns[i].onclick = function () {
        var id = this.getAttribute('data-id');
        var self = this;
        self.disabled = true;
        claimQuest(id).then(function (r) {
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

  /**
   * 公开: 玩家操作后立即刷新引导状态 (避免等轮询)。
   * 用法: G.MainQuest && G.MainQuest.refresh();
   */
  function refresh() {
    return G.Onboarding.refresh();
  }

  function hasUnclaimed() {
    if (G.Onboarding && G.Onboarding.hasSupply()) return true;
    for (var i = 0; i < state.chapters.length; i++) {
      var qs = state.chapters[i].quests;
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
    hasUnclaimed: hasUnclaimed,
    doPromoteRank: doPromoteRank,
    state: state
  };
})(window.Game);
