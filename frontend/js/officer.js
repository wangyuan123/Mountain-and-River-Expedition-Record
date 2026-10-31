/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';
  G.gameNow = G.gameNow || function () { return Date.now(); };

  var D = G.DATA;
  var Core = G.Core;
  var skillDetailModal = null;
  var universalSkillModal = null;
  var universalSkillPending = false;
  var academyAnimTimers = [];

  /** 按服务端额度显示刷新入口；跨日只重置日次数，未结束的轮次冷却继续保留。 */
  function academyRefreshHtml(academy) {
    academy = academy || {};
    var now = G.gameNow();
    var roundLimit = academy.refreshRoundLimit || 30;
    var dailyLimit = academy.refreshDailyLimit || 100;
    var cooldownExpired = academy.refreshAt > 0 && academy.refreshAt <= now;
    var roundCount = cooldownExpired ? 0 : (academy.refreshRoundCount || 0);
    var dailyCount = academy.refreshDailyResetAt > 0 && academy.refreshDailyResetAt <= now
      ? 0 : (academy.refreshDailyCount || 0);
    var remaining = Math.max(0, (academy.refreshAt - now) / 1000);
    var h = '<div class="desc">本轮已刷新 ' + roundCount + '/' + roundLimit +
      ' 次 · 今日已刷新 ' + dailyCount + '/' + dailyLimit + ' 次</div>';
    h += '<div class="academy-refresh-row">';
    if (dailyCount >= dailyLimit) {
      h += '<button type="button" class="btn academy-refresh-btn cooling" disabled>今日刷新已达' + dailyLimit + '次（游戏时间次日0点重置，停服顺延）</button>';
    } else if (remaining > 0) {
      h += '<button type="button" class="btn academy-refresh-btn cooling" disabled>' +
        '<span class="refresh-icon">⏳</span> 本轮已用满' + roundLimit + '次，刷新休整中 (' + G.formatDuration(remaining) + '后可再次刷新)</button>';
    } else {
      var isRefreshing = Officer && Officer._isAcademyRefreshing;
      if (isRefreshing) {
        h += '<button type="button" class="btn ok academy-refresh-btn cooling" disabled>' +
          '<span class="refresh-icon">⏳</span> 调遣将领中...</button>';
      } else {
        h += '<button type="button" class="btn ok academy-refresh-btn with-ripple" onclick="Game.Officer.onRefreshClick(event)">' +
          '<span class="btn-ripple-container"></span><span class="refresh-icon">⟳</span> 刷新候选人 (200金)</button>';
      }
    }
    var isFast = Officer && Officer.isFastRefresh ? Officer.isFastRefresh() : false;
    h += '<label class="academy-fast-refresh-label" title="勾选后刷新军校将跳过调遣动画，立即展示候选名单">' +
      '<input type="checkbox" id="academy-fast-refresh-checkbox" class="academy-fast-refresh-input"' + (isFast ? ' checked' : '') + ' onchange="Game.Officer.toggleFastRefresh(this.checked)">' +
      '<span>极速刷新</span>' +
      '</label>';
    h += '</div>';
    return h;
  }

  function starIcons(star) {
    var filled = Math.min(5, Math.max(0, Number(star) || 0));
    var h = '<span class="officer-gem-stars" role="img" aria-label="' + filled + '星军官">';
    for (var i = 0; i < 5; i++) {
      h += '<span class="officer-gem-star' + (i < filled ? '' : ' is-empty') + '" aria-hidden="true"></span>';
    }
    return h + '</span>';
  }

  /** 培养上限只约束原始属性；兼容没有基础属性字段的历史状态。 */
  function baseAttribute(officer, key) {
    var baseKey = 'base' + key.charAt(0).toUpperCase() + key.slice(1);
    return Number(officer[baseKey] != null ? officer[baseKey] : officer[key]) || 0;
  }

  /** 五星军官的稀有度取当前最高属性；军事或防御满值优先于其他满值。 */
  function officerRarity(o) {
    if (Number(o.star) !== 5) return null;
    if (Number(o.military) === 219 || Number(o.defense) === 219) {
      return { className: 'rarity-ur', label: '极度稀有（UR）' };
    }
    if (Number(o.logistics) === 219 || Number(o.knowledge) === 219) {
      return { className: 'rarity-ssr', label: '超稀有（SSR）' };
    }
    var highest = Math.max(Number(o.military) || 0, Number(o.defense) || 0,
      Number(o.logistics) || 0, Number(o.knowledge) || 0);
    return highest >= 210 && highest <= 218
      ? { className: 'rarity-sr', label: '稀有（SR）' }
      : null;
  }

  /** 统一生成已招募卡片与军校候选卡片使用的星级边框装饰：仅5星军官展示钻石边框与角饰，并根据品质差异化呈现，4星及以下为普通卡片。 */
  function officerFrame(o) {
    var star = Math.min(5, Math.max(1, Number(o.star) || 1));
    var tierClass = star === 5 ? 'tier-diamond' : 'tier-normal';
    var rarity = officerRarity(o);
    var rarityAttr = '';
    var cornerSuffix = '';
    if (star === 5) {
      if (rarity && rarity.className === 'rarity-ur') {
        rarityAttr = ' data-rarity="ur"';
        cornerSuffix = ' corner-ur';
      } else if (rarity && rarity.className === 'rarity-ssr') {
        rarityAttr = ' data-rarity="ssr"';
        cornerSuffix = ' corner-ssr';
      } else if (rarity && rarity.className === 'rarity-sr') {
        rarityAttr = ' data-rarity="sr"';
        cornerSuffix = ' corner-sr';
      } else {
        rarityAttr = ' data-rarity="base5"';
        cornerSuffix = ' corner-base5';
      }
    }
    var ribbon = rarity
      ? '<span class="tier-ribbon ' + rarity.className + '">' + rarity.label + '</span>'
      : '';
    var corners = star === 5
      ? '<span class="card-corner corner-tl' + cornerSuffix + '"></span><span class="card-corner corner-tr' + cornerSuffix + '"></span>' +
        '<span class="card-corner corner-bl' + cornerSuffix + '"></span><span class="card-corner corner-br' + cornerSuffix + '"></span>'
      : '';
    return { tierClass: tierClass, ribbon: ribbon, corners: corners, rarityAttr: rarityAttr };
  }

  function renderCandidateCardHtml(o, i, s) {
    s = s || Core.state || {};
    var cost = o.recruitCost;
    var can = !!(s.resources && s.resources.gold >= cost);
    var frame = officerFrame(o);
    var cls = (can ? 'menu-item ok' : 'menu-item lock') + ' officer-card ' + frame.tierClass + ' academy-candidate-card' + (can ? '' : ' is-locked');

    var mil = Number(o.military) || 0;
    var def = Number(o.defense) || 0;
    var log = Number(o.logistics) || 0;
    var kno = Number(o.knowledge) || 0;
    var maxVal = Math.max(mil, def, log, kno);
    var isPeakMil = mil === maxVal && mil > 0;
    var isPeakDef = def === maxVal && def > 0;
    var isPeakLog = log === maxVal && log > 0;
    var isPeakKno = kno === maxVal && kno > 0;

    var skillHtml = '';
    if (o.skills && o.skills.length) {
      var skItems = [];
      for (var si = 0; si < o.skills.length; si++) {
        var sItem = o.skills[si];
        if (!sItem) continue;
        var skObj = sItem.id && D.officerSkills ? D.officerSkills[sItem.id] : null;
        var skName = (skObj && skObj.name) || sItem.name || sItem.id || '特长';
        var skLv = skObj ? '满级' : (sItem.lv ? 'Lv' + sItem.lv : '');
        skItems.push(skName + (skLv ? ' ' + skLv : ''));
      }
      if (skItems.length) {
        skillHtml = '<span class="candidate-skill-tag" title="技能: ' + G.escapeHtml(skItems.join(', ')) + '">⚡ ' + G.escapeHtml(skItems[0]) + (skItems.length > 1 ? ' +' + (skItems.length - 1) : '') + '</span>';
      }
    }
    if (!skillHtml) {
      skillHtml = '<span class="candidate-skill-tag is-empty">无特长技能</span>';
    }

    var recruitTitle = can
      ? '招募 ' + G.escapeHtml(o.name) + ' (消耗 ' + cost + ' 黄金)'
      : '黄金不足 (需 ' + cost + ' 黄金)';
    var cardClickAction = can
      ? 'Game.Officer.recruit(' + i + ')'
      : 'event.stopPropagation();Game.toast(\'黄金不足，无法招募\');';

    var recruitBtnHtml = can
      ? '<button type="button" class="link-act candidate-recruit-btn" onclick="event.stopPropagation();Game.Officer.recruit(' + i + ')" title="' + recruitTitle + '">' +
          '<span class="btn-recruit-text">招募</span><span class="btn-recruit-cost">🪙' + cost + '</span></button>'
      : '<button type="button" class="link-act disabled candidate-recruit-btn is-disabled" disabled onclick="event.stopPropagation();Game.toast(\'黄金不足，无法招募\');" title="' + recruitTitle + '">' +
          '<span class="btn-recruit-text">招募</span><span class="btn-recruit-cost">🪙' + cost + '</span></button>';

    var h = '<div class="' + cls + '" id="academy-candidate-' + i + '" data-officer-index="' + i + '" data-star="' + o.star + '"' + frame.rarityAttr + ' role="button" tabindex="0" onclick="' + cardClickAction + '" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){event.preventDefault();' + cardClickAction + '}" title="' + recruitTitle + '">' + frame.corners;
    h += '<div class="candidate-header-row">';
    h += '<div class="candidate-title-group">';
    h += '<span class="candidate-name n" style="color:' + (D.starColor[o.star] || '#fff') + '">' + G.escapeHtml(o.name) + '</span>';
    h += '<span class="candidate-stars stars">' + starIcons(o.star) + '</span>';
    h += '</div>';
    h += '<div class="candidate-badge-group">';
    if (frame.ribbon) {
      h += frame.ribbon;
    } else {
      h += '<span class="candidate-star-pill star-' + o.star + '">' + o.star + '星</span>';
    }
    h += '</div>';
    h += '</div>';

    h += '<div class="candidate-stats-grid">';
    h += '<div class="c-stat-pill pill-mil' + (isPeakMil ? ' is-peak' : '') + '" title="军事: ' + mil + '"><span class="c-stat-lbl">军</span><span class="c-stat-val">' + mil + '</span></div>';
    h += '<div class="c-stat-pill pill-def' + (isPeakDef ? ' is-peak' : '') + '" title="防御: ' + def + '"><span class="c-stat-lbl">防</span><span class="c-stat-val">' + def + '</span></div>';
    h += '<div class="c-stat-pill pill-log' + (isPeakLog ? ' is-peak' : '') + '" title="后勤: ' + log + '"><span class="c-stat-lbl">后</span><span class="c-stat-val">' + log + '</span></div>';
    h += '<div class="c-stat-pill pill-kno' + (isPeakKno ? ' is-peak' : '') + '" title="学识: ' + kno + '"><span class="c-stat-lbl">学</span><span class="c-stat-val">' + kno + '</span></div>';
    h += '</div>';

    h += '<div class="candidate-footer-row">';
    h += '<div class="candidate-skills-wrap">' + skillHtml + '</div>';
    h += '<div class="officer-recruit-act">' + recruitBtnHtml + '</div>';
    h += '</div>';

    h += '</div>';
    return h;
  }

  function roleText(r) {
    return G.Constants.officerRoles[r] || '闲置';
  }

  function equipmentName(itemId) { return G.Constants.equipmentNames[itemId] || itemId || '装备'; }

  function skillText(o) {
    if (!o || !o.skills || !o.skills.length) return '';
    var str = Core.formatSkills ? Core.formatSkills(o.skills) : '';
    if (str) return ' 技能: ' + str;
    var parts = [];
    for (var i = 0; i < o.skills.length; i++) {
      var item = o.skills[i];
      if (!item) continue;
      var sk = item.id ? D.officerSkills[item.id] : null;
      if (sk) parts.push(sk.name + '（满级）');
      else if (item.name) parts.push(item.name + (item.lv ? 'Lv' + item.lv : ''));
    }
    return parts.length ? ' 技能: ' + parts.join('/') : '';
  }

  function closeSkillDetailModal() {
    if (skillDetailModal && skillDetailModal.parentNode) skillDetailModal.parentNode.removeChild(skillDetailModal);
    skillDetailModal = null;
  }

  function officerIconHtml(info) {
    return info && G.iconHtml ? G.iconHtml(info.icon, info.name) : '';
  }

  /** 指定书和通用书允许技能自由组合，共用重复技能与槽位校验；历史 supply 按三军统帅识别，库存由各入口及服务端校验。 */
  function skillLearningReason(officer, skillId) {
    var entry = officer && officer.skillLearning && officer.skillLearning[skillId];
    return entry ? entry.reason : '学习资格待同步';
  }

  /** 已学技能统一展示原满级效果，只保留查看与废弃操作。 */
  function skillRowHtml(officerId, skillIdx, skill) {
    var info = D.officerSkills[skill.id];
    return '<button type="button" class="officer-skill-detail-trigger" onclick="Game.Officer.showSkillDetail(\'' + skill.id + '\')" title="查看技能详情">' + officerIconHtml(info) + ' ' + info.name + '（满级）</button>'
      + ' <button type="button" class="btn depot-btn warn" onclick="Game.Officer.forgetSkill(\'' + officerId + '\',' + skillIdx + ',\'ask\')">[废弃]</button>';
  }

  function findOfficer(officers, id) {
    if (!officers || id == null) return null;
    var strId = String(id);
    for (var i = 0; i < officers.length; i++) {
      if (officers[i] && String(officers[i].id) === strId) {
        return officers[i];
      }
    }
    return null;
  }

  var AcademyAudio = {
    ctx: null,
    getCtx: function () {
      if (!AcademyAudio.ctx && typeof window !== 'undefined') {
        var AC = window.AudioContext || window.webkitAudioContext;
        if (AC) {
          try { AcademyAudio.ctx = new AC(); } catch (e) {}
        }
      }
      if (AcademyAudio.ctx && AcademyAudio.ctx.state === 'suspended') {
        try { AcademyAudio.ctx.resume().catch(function () {}); } catch (e) {}
      }
      return AcademyAudio.ctx;
    },
    isAudible: function () {
      if (typeof document !== 'undefined' && document.hidden) return false;
      if (G.Sound && G.Sound.get) {
        var s = G.Sound.get();
        return s.enabled && s.volume > 0;
      }
      return true;
    },
    getVolume: function () {
      if (G.Sound && G.Sound.get) {
        var s = G.Sound.get();
        return Math.max(0, Math.min(1, s.volume != null ? s.volume : 0.4));
      }
      return 0.4;
    },
    playStamp: function () {
      if (!AcademyAudio.isAudible()) return;
      var ctx = AcademyAudio.getCtx();
      if (!ctx || ctx.state !== 'running') return;
      try {
        var vol = AcademyAudio.getVolume();
        var now = ctx.currentTime;

        var osc = ctx.createOscillator();
        var gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(140, now);
        osc.frequency.exponentialRampToValueAtTime(42, now + 0.11);
        gain.gain.setValueAtTime(0.35 * vol, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.12);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start(now);
        osc.stop(now + 0.12);

        var oscM = ctx.createOscillator();
        var gainM = ctx.createGain();
        oscM.type = 'triangle';
        oscM.frequency.setValueAtTime(320, now);
        oscM.frequency.exponentialRampToValueAtTime(80, now + 0.06);
        gainM.gain.setValueAtTime(0.18 * vol, now);
        gainM.gain.exponentialRampToValueAtTime(0.001, now + 0.07);
        oscM.connect(gainM);
        gainM.connect(ctx.destination);
        oscM.start(now);
        oscM.stop(now + 0.07);
      } catch (e) {}
    },
    playFlip: function () {
      if (!AcademyAudio.isAudible()) return;
      var ctx = AcademyAudio.getCtx();
      if (!ctx || ctx.state !== 'running') return;
      try {
        var vol = AcademyAudio.getVolume();
        var now = ctx.currentTime;

        var osc = ctx.createOscillator();
        var gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(680, now);
        osc.frequency.exponentialRampToValueAtTime(380, now + 0.035);
        gain.gain.setValueAtTime(0.08 * vol, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.035);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start(now);
        osc.stop(now + 0.035);
      } catch (e) {}
    },
    playFiveStar: function () {
      if (!AcademyAudio.isAudible()) return;
      var ctx = AcademyAudio.getCtx();
      if (!ctx || ctx.state !== 'running') return;
      try {
        var vol = AcademyAudio.getVolume();
        var now = ctx.currentTime;

        var freqs = [523.25, 659.25, 783.99, 1046.50];
        freqs.forEach(function (f, idx) {
          var osc = ctx.createOscillator();
          var gain = ctx.createGain();
          osc.type = (idx === 3) ? 'sine' : 'triangle';
          var startTime = now + idx * 0.03;
          var duration = 0.55 - idx * 0.05;
          osc.frequency.setValueAtTime(f, startTime);
          gain.gain.setValueAtTime(0.18 * vol, startTime);
          gain.gain.exponentialRampToValueAtTime(0.001, startTime + duration);
          osc.connect(gain);
          gain.connect(ctx.destination);
          osc.start(startTime);
          osc.stop(startTime + duration);
        });

        var shimmer = ctx.createOscillator();
        var shimmerGain = ctx.createGain();
        shimmer.type = 'sine';
        shimmer.frequency.setValueAtTime(1200, now);
        shimmer.frequency.exponentialRampToValueAtTime(2600, now + 0.28);
        shimmerGain.gain.setValueAtTime(0.12 * vol, now);
        shimmerGain.gain.exponentialRampToValueAtTime(0.001, now + 0.3);
        shimmer.connect(shimmerGain);
        shimmerGain.connect(ctx.destination);
        shimmer.start(now);
        shimmer.stop(now + 0.3);
      } catch (e) {}
    }
  };

  var Officer = {
    Audio: AcademyAudio,
    /** 按当前岗位规则解释内政、带兵与常驻加成，复用通用说明弹窗。 */
    showPositionDetails: function () {
      if (document.getElementById('officerPositionDetailsModal')) return;
      var opener = document.activeElement;
      var chiefPercent = G.ServerRules.value('catalog', {}).roleRates.chiefPercentPerPoint;
      var researchPercent = G.ServerRules.value('catalog', {}).roleRates.researchPercentPerPoint;
      var mask = document.createElement('div');
      mask.id = 'officerPositionDetailsModal';
      mask.className = 'modal-mask';
      mask.innerHTML = '<section class="modal-card" role="dialog" aria-modal="true" aria-labelledby="officerPositionDetailsTitle">'
        + '<div class="modal-title"><span id="officerPositionDetailsTitle">职位详情</span>'
        + '<button type="button" class="modal-close-btn" id="officerPositionDetailsCloseX" aria-label="关闭职位详情">✕</button></div>'
        + '<div class="modal-body" tabindex="0" aria-label="三个职位的详细说明">'
        + '<p>每座城市各设一名市长、一名指挥官和一名司令官。同一军官不能兼任，改任会释放原岗位；替换任职者后，原任职者转为待命。行军中的军官不能改任。</p>'
        + '<h3>👑 执政市长 · 发展内政</h3><ul>'
        + '<li><strong>后勤：</strong>每点为本城粮食、钢铁、石油、稀矿的基础产出增加1%加成，与对应生产科技加成相加，再计算其他产量倍率。</li>'
        + '<li><strong>学识：</strong>每点为黄金税收增加1%加成，并提高科研速度' + researchPercent + '%。科研提速不等于等比例缩短工期。</li>'
        + '<li><strong>内政技能：</strong>屯田增产提高资源产出，精明理财提高黄金税收，工程营造缩短建筑工期，格物致知提高科研速度，军屯自给降低全城养兵耗粮。所有技能均使用满级效果。</li>'
        + '<li><strong>生效范围：</strong>只作用于任职城市；装备与套装共鸣计入有效属性。建筑、科研工期在开工时确定，之后换任不会重算已开工队列。</li>'
        + '<li><strong>选择建议：</strong>优先后勤、学识与内政技能；市长的军事、防御和战斗技能不会自动给守军加成。</li></ul>'
        + '<h3>⚔️ 作战指挥官 · 带兵作战</h3><ul>'
        + '<li><strong>守城职责：</strong>作为本城守将，为本城参战编队提供自己的军事、防御和适用战斗技能。守城部队全部参战，不受出征带兵上限限制。</li>'
        + '<li><strong>攻防属性：</strong>实际带兵将领每点军事提供1%攻击加成，每点防御提供1%防御加成；从首次接战回合E起，在E、E+3、E+6等回合生效，间隔两个回合关闭。技能按各自说明触发，并非所有技能都采用同一周期。</li>'
        + '<li><strong>出征带兵上限：</strong>本城指挥官的“三军统帅”额外提高20%；军事属性本身不增加带兵上限。出征仍受军衔等规则限制。</li>'
        + '<li><strong>出征将领：</strong>出征战斗使用实际选择的随队将领；城内指挥官不会自动给每支出征队伍叠加攻防或战斗技能。</li>'
        + '<li><strong>援军独立：</strong>援军按各自随行将领计算属性和技能，不借用本城指挥官。装备与套装共鸣计入所属将领有效属性。</li>'
        + '<li><strong>选择建议：</strong>优先军事、防御和适合兵种战术的技能；带兵将领的后勤不直接增加部队生命，学识不直接增加战场速度。</li></ul>'
        + '<h3>🎖️ 司令官 · 常驻统筹</h3><ul>'
        + '<li><strong>任命条件：</strong>本城司令部1级建成后解锁。司令官任职期间不能带队出征，需先解除任命。</li>'
        + '<li><strong>四项属性：</strong>军事提高攻击，防御提高防御，后勤提高生命，学识提高战场移动速度；每点对应属性提供' + chiefPercent + '%加成。例如后勤100，生命倍率为1.1，即生命+10%。</li>'
        + '<li><strong>范围与时点：</strong>本城出征部队与本城防守方在所有回合享受加成；防守方援军共享被驻军城市的司令官，不叠加援军来源城市的司令官。开战时锁定快照，之后换任或换装不改变已开始的战斗。</li>'
        + '<li><strong>装备与技能：</strong>装备和套装共鸣计入四项有效属性；司令官技能不参与战斗、带兵上限或内政加成。常驻倍率可与科技、带兵将领生效回合的倍率叠乘。</li>'
        + '<li><strong>机动边界：</strong>学识影响战场移动和同射程下的先手，不增加射程，也不缩短大地图行军时间；固定工事不会因此移动。</li>'
        + '<li><strong>司令部升级：</strong>同时出征队伍数为基础5支＋司令部等级，满级15支；等级不重复放大司令官属性，也不提高单队带兵上限。拆除后暂停新战斗的加成，重建后恢复，仍可取消原任命。</li>'
        + '<li><strong>选择建议：</strong>重视装备后的四项属性；想提高全军生命，可优先为司令官配置后勤装备。</li></ul>'
        + '</div><div class="modal-foot"><button type="button" class="btn" id="officerPositionDetailsClose">知道了</button></div></section>';
      var closeX = mask.querySelector('#officerPositionDetailsCloseX');
      var closeButton = mask.querySelector('#officerPositionDetailsClose');
      var close = function () {
        mask.remove();
        if (opener && opener.isConnected !== false && typeof opener.focus === 'function') opener.focus();
      };
      closeX.onclick = close;
      closeButton.onclick = close;
      mask.addEventListener('click', function (event) { if (event.target === mask) close(); });
      mask.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') { event.preventDefault(); close(); }
        // 保留正文的键盘滚动焦点，只在首尾按钮之间循环，避免操作遮罩后的任命按钮。
        if (event.key === 'Tab' && event.shiftKey && document.activeElement === closeX) {
          event.preventDefault(); closeButton.focus();
        } else if (event.key === 'Tab' && !event.shiftKey && document.activeElement === closeButton) {
          event.preventDefault(); closeX.focus();
        }
      });
      document.body.appendChild(mask);
      closeX.focus();
    },

    /** 复用科技页说明弹窗，将招募费用、批次概率和刷新额度集中展示。 */
    showAcademyInfo: function () {
      if (document.getElementById('academyInfoModal')) return;
      var academy = Core.state.academy || {};
      var mask = document.createElement('div');
      mask.id = 'academyInfoModal';
      mask.className = 'modal-mask';
      var probability = '';
      if (typeof academy.fiveStarBatchChance === 'number') {
        probability = '当前整批概率为 <b>' + Number((academy.fiveStarBatchChance * 100).toFixed(2)) + '%</b>。';
      }
      if (typeof academy.baseFiveStarBatchChance === 'number' && typeof academy.militaryTheoryBonus === 'number') {
        // 概率拆分直接读取服务端，避免说明弹窗与实际刷新规则各自计算。
        probability += '军校基础 ' + Number((academy.baseFiveStarBatchChance * 100).toFixed(2)) +
          '% ＋ 军事理论 Lv.' + (academy.militaryTheoryLevel || 0) + ' 加成 ' +
          Number((academy.militaryTheoryBonus * 100).toFixed(2)) + '个百分点。';
      }
      mask.innerHTML =
        '<div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="academyInfoTitle">' +
          '<div class="modal-title" id="academyInfoTitle">军校招募说明</div>' +
          '<div class="modal-body"><ol>' +
            '<li><strong>招募条件：</strong>新建军校后才能招募军官，军校等级按当前城市计算。</li>' +
            '<li><strong>刷新与费用：</strong>消耗200黄金刷新候选人名单，每次刷新' + (academy.candidateCount || 10) + '名候选人；每名候选人招募费 = 星级×80 金。</li>' +
            '<li><strong>五星名将：</strong>每批只判定一次五星概率，每批最多1名五星，五星军官使用名将姓名。' + probability + '</li>' +
            '<li><strong>概率成长：</strong>军校与军事理论每级各增加0.3个百分点，各自满级提供3个百分点，两者满级合计6%。</li>' +
            '<li><strong>生效时点：</strong>以上概率适用于下一次普通刷新，已生成名单不随升级变化；征募令等道具沿用自身保底规则。</li>' +
            '<li><strong>每轮额度：</strong>每轮最多' + (academy.refreshRoundLimit || 30) + '次，用满后冷却1小时；冷却结束后开启下一轮。</li>' +
            '<li><strong>每日额度：</strong>每日最多' + (academy.refreshDailyLimit || 100) + '次，按游戏时间的北京时间0点重置，停服顺延；账号各城市共享刷新额度，跨日不会提前结束尚未完成的轮次冷却。</li>' +
          '</ol></div>' +
          '<div class="modal-foot"><button type="button" class="btn" id="academyInfoClose">知道了</button></div>' +
        '</div>';
      var trigger = document.activeElement;
      var close = function () {
        if (mask.parentNode) mask.parentNode.removeChild(mask);
        if (trigger && trigger.isConnected && trigger.focus) trigger.focus();
      };
      document.body.appendChild(mask);
      var closeButton = mask.querySelector('#academyInfoClose');
      closeButton.onclick = close;
      mask.addEventListener('click', function (event) { if (event.target === mask) close(); });
      mask.onkeydown = function (event) { if (event.key === 'Escape') close(); };
      closeButton.focus();
    },

    _isAcademyRefreshing: false,
    _fastRefresh: null,

    isFastRefresh: function () {
      if (Officer._fastRefresh == null) {
        try {
          Officer._fastRefresh = (typeof window !== 'undefined' && window.localStorage && window.localStorage.getItem('sf_academy_fast_refresh') === 'true');
        } catch (e) {
          Officer._fastRefresh = false;
        }
      }
      return !!Officer._fastRefresh;
    },

    toggleFastRefresh: function (val) {
      Officer._fastRefresh = !!val;
      try {
        if (typeof window !== 'undefined' && window.localStorage) {
          window.localStorage.setItem('sf_academy_fast_refresh', String(Officer._fastRefresh));
        }
      } catch (e) {}
    },

    /** 静默刷新额度与倒计时，不重建候选人列表或打断招募操作。 */
    updateAcademyRefresh: function () {
      var region = document.getElementById('academy-refresh-status');
      if (region && Core.state) {
        var html = academyRefreshHtml(Core.state.academy);
        if (region.innerHTML !== html) region.innerHTML = html;
      }
    },

    onRefreshClick: function (e) {
      if (e && e.currentTarget && typeof document !== 'undefined') {
        var btn = e.currentTarget;
        var rippleContainer = btn.querySelector ? btn.querySelector('.btn-ripple-container') : null;
        if (rippleContainer && btn.getBoundingClientRect) {
          var rect = btn.getBoundingClientRect();
          var size = Math.max(rect.width, rect.height) * 2.2;
          var clientX = (e.clientX != null) ? e.clientX : (rect.left + rect.width / 2);
          var clientY = (e.clientY != null) ? e.clientY : (rect.top + rect.height / 2);
          var x = clientX - rect.left;
          var y = clientY - rect.top;
          var wave = document.createElement('span');
          wave.className = 'water-ripple-wave';
          wave.style.width = size + 'px';
          wave.style.height = size + 'px';
          wave.style.left = (x - size / 2) + 'px';
          wave.style.top = (y - size / 2) + 'px';
          rippleContainer.appendChild(wave);
          setTimeout(function () {
            if (wave && wave.parentNode) wave.parentNode.removeChild(wave);
          }, 700);
        }
      }

      if (Officer.isFastRefresh() || typeof document === 'undefined' || !document.getElementById('academy-candidates-region')) {
        return Officer.refreshAcademy();
      }
      return Officer.refreshAcademyWithAnimation();
    },

    clearAcademyAnimTimers: function () {
      for (var i = 0; i < academyAnimTimers.length; i++) {
        clearTimeout(academyAnimTimers[i]);
      }
      academyAnimTimers = [];
    },

    skipAcademyAnim: function () {
      if (!Officer._isAcademyRefreshing) return;
      Officer.clearAcademyAnimTimers();
      Officer.finishAcademyAnim();
    },

    finishAcademyAnim: function () {
      Officer.clearAcademyAnimTimers();
      Officer._isAcademyRefreshing = false;
      G.toast('军校已刷新');
      if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
      Core.render();
    },

    refreshAcademyWithAnimation: function () {
      if (Officer._isAcademyRefreshing) return;
      var region = document.getElementById('academy-candidates-region');
      if (!region) {
        return Officer.refreshAcademy();
      }

      Officer._isAcademyRefreshing = true;
      Officer.clearAcademyAnimTimers();
      Officer.updateAcademyRefresh();

      // 第一阶段：展示机密档案袋与雷达扫描
      var dossierHtml = '<div class="academy-dispatch-dossier" onclick="Game.Officer.skipAcademyAnim()" role="button" tabindex="0" title="点击任意处跳过动画">' +
        '<div class="dossier-skip-tip">点击跳过 ⏩</div>' +
        '<div class="dossier-folder">' +
          '<div class="dossier-header">' +
            '<span class="dossier-badge">WAR DEPARTMENT · 机密调令</span>' +
            '<span class="dossier-code">NO.AC-2026</span>' +
          '</div>' +
          '<div class="dossier-crest">🎖️</div>' +
          '<div class="dossier-title">战区将校晋升调遣指令</div>' +
          '<div class="dossier-typing-text" id="academyDossierTyping">正在联络各战区司令部...</div>' +
          '<div class="dossier-scan-radar"><div class="radar-sweep"></div></div>' +
        '</div>' +
        '<div class="dossier-seal-wrap">' +
          '<div class="dossier-seal-stamp">' +
            '<div class="seal-inner">' +
              '<span class="seal-star">★ ★ ★</span>' +
              '<span class="seal-text">批准调派</span>' +
              '<span class="seal-sub">TOP SECRET</span>' +
            '</div>' +
          '</div>' +
        '</div>' +
      '</div>';

      region.innerHTML = dossierHtml;

      var textTimer = setTimeout(function () {
        var typingElem = document.getElementById('academyDossierTyping');
        if (typingElem) typingElem.innerHTML = '调阅将校考评档案与战功...';
      }, 300);
      academyAnimTimers.push(textTimer);

      var stampTimer = setTimeout(function () {
        AcademyAudio.playStamp();
      }, 350);
      academyAnimTimers.push(stampTimer);

      var minDelayPromise = new Promise(function (resolve) {
        var t = setTimeout(resolve, 600);
        academyAnimTimers.push(t);
      });

      var apiPromise = G.API.refreshAcademy();

      return Promise.all([apiPromise, minDelayPromise]).then(function (results) {
        var resp = results[0];
        if (!resp || resp.success === false) {
          Officer._isAcademyRefreshing = false;
          Officer.clearAcademyAnimTimers();
          G.toast((resp && resp.message) || '刷新失败');
          Core.render();
          return;
        }

        var s = Core.state;
        var list = (s && s.academy && s.academy.list) || [];
        if (!list.length) {
          Officer.finishAcademyAnim();
          return;
        }

        var flipGridHtml = '<div class="zone-head">候选人名单 (点击卡片直接招募)</div>';
        flipGridHtml += '<div class="academy-candidates-grid menu academy-anim-cards-grid" id="academyAnimCardsGrid" onclick="Game.Officer.skipAcademyAnim()" title="点击跳过动画">';

        list.forEach(function (o, i) {
          var isFiveStar = Number(o.star) === 5;
          var cardFrontHtml = renderCandidateCardHtml(o, i, s);
          flipGridHtml += '<div class="academy-anim-card-wrap' + (isFiveStar ? ' has-five-star' : '') + '" id="academy-anim-wrap-' + i + '">' +
            '<div class="card-flip-inner">' +
              '<div class="card-face-back">' +
                '<div class="card-back-crest">🎖️</div>' +
                '<div class="card-back-label">军校调令</div>' +
                '<div class="card-back-stars">★★★★★</div>' +
              '</div>' +
              '<div class="card-face-front">' +
                cardFrontHtml +
                (isFiveStar ? '<div class="star5-burst-glow"></div>' : '') +
              '</div>' +
            '</div>' +
          '</div>';
        });

        flipGridHtml += '</div>';
        region.innerHTML = flipGridHtml;

        var currentDelay = 40;
        var flipStep = 45;

        for (var idx = 0; idx < list.length; idx++) {
          (function (cardIdx, delayTime) {
            var t = setTimeout(function () {
              var wrap = document.getElementById('academy-anim-wrap-' + cardIdx);
              if (wrap) {
                wrap.classList.add('is-flipped');
                if (list[cardIdx] && Number(list[cardIdx].star) === 5) {
                  wrap.classList.add('is-star5-spotlight');
                  AcademyAudio.playFiveStar();
                } else {
                  AcademyAudio.playFlip();
                }
              }
            }, delayTime);
            academyAnimTimers.push(t);
          })(idx, currentDelay);

          currentDelay += flipStep;
          if (Number(list[idx].star) === 5) {
            currentDelay += 200; // 5星名将悬停特写0.2s
          }
        }

        var finishDelay = currentDelay + 250;
        var endTimer = setTimeout(function () {
          Officer.finishAcademyAnim();
        }, finishDelay);
        academyAnimTimers.push(endTimer);

      }).catch(function (err) {
        Officer._isAcademyRefreshing = false;
        Officer.clearAcademyAnimTimers();
        G.toast(err.message || '刷新失败');
        Core.render();
      });
    },

    refreshAcademy: function () {
      return G.API.refreshAcademy().then(function (resp) {
        if (!resp || resp.success === false) {
          G.toast((resp && resp.message) || '刷新失败');
          Core.render();
          return;
        }
        G.toast('军校已刷新');
        if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '刷新失败');
      });
    },

    recruit: function (idx) {
      G.API.recruitOfficer(idx).then(function () {
        G.toast('招募成功');
        if (G.Task && G.Task.Quests) G.Task.Quests.onEvent('recruit');
        if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '招募失败');
      });
    },

    /** 司令官任命受本城司令部与行军状态限制；条件不足时直接说明原因。 */
    appoint: function (officerId, role) {
      var s = Core.state || {};
      var officer = (s.officers || []).find(function (o) { return String(o.id) === String(officerId); });
      if (role === 'commander_in_chief' && officer) {
        if (officer.role === 'march') {
          G.toast('军官正在行军，无法任命');
          return Promise.resolve(null);
        }
        // 司令部拆除后仍允许解除现任司令官，不能用建造条件锁住取消操作。
        if (officer.role !== role && !(s.buildings && Number(s.buildings.headquarters) > 0)) {
          G.toast('请先到军工建造司令部，再任命司令官');
          return Promise.resolve(null);
        }
      }
      return G.API.appointOfficer(officerId, role).then(function (resp) {
        G.toast((resp && resp.message) || '任命成功');
        if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '任命失败');
      });
    },

    dismiss: function (officerId, action) {
      var wrap = document.getElementById('dismiss-row');
      if (action === 'confirm') {
        return G.API.dismissOfficer(officerId).then(function (resp) {
          if (resp && resp.success === false) {
            G.toast(resp.message || '解雇失败', 'error');
            return;
          }
          if (String(Officer._detailOfficerId) === String(officerId) || (Core.state && String(Core.state._detailOfficerId) === String(officerId))) {
            Officer._detailOfficerId = null;
            if (Core.state) Core.state._detailOfficerId = null;
            G.go('officer');
          } else {
            Core.render();
          }
          G.toast('解雇成功', 'success');
        }).catch(function (err) {
          G.toast(err.message || '解雇失败', 'error');
        });
      }
      if (action === 'cancel') {
        if (wrap) {
          wrap.innerHTML = '<button type="button" class="btn depot-btn warn" onclick="Game.Officer.dismiss(\'' + officerId + '\',\'ask\')">[解雇]</button>';
        }
        return;
      }
      if (wrap) {
        wrap.innerHTML = '<p>确认解雇该军官？</p><button type="button" class="btn depot-btn warn" onclick="Game.Officer.dismiss(\'' + officerId + '\',\'confirm\')">[确认解雇]</button> <button type="button" class="btn depot-btn" onclick="Game.Officer.dismiss(\'' + officerId + '\',\'cancel\')">[取消]</button>';
      }
    },

    dismissList: function (officerId, action) {
      var wrap = document.getElementById('dismiss-row-' + officerId);
      if (action === 'confirm') {
        return G.API.dismissOfficer(officerId).then(function (resp) {
          if (resp && resp.success === false) {
            G.toast(resp.message || '解雇失败', 'error');
            return;
          }
          Core.render();
          G.toast('解雇成功', 'success');
        }).catch(function (err) {
          G.toast(err.message || '解雇失败', 'error');
        });
      }
      if (action === 'cancel') {
        if (wrap) {
          wrap.innerHTML = '<button type="button" class="btn depot-btn warn" onclick="event.stopPropagation();Game.Officer.dismissList(\'' + officerId + '\',\'ask\')">[解雇]</button>';
        }
        return;
      }
      if (wrap) {
        wrap.innerHTML = '<button type="button" class="btn depot-btn warn" onclick="event.stopPropagation();Game.Officer.dismissList(\'' + officerId + '\',\'confirm\')">[确认解雇]</button> <button type="button" class="btn depot-btn" onclick="event.stopPropagation();Game.Officer.dismissList(\'' + officerId + '\',\'cancel\')">[取消]</button>';
      }
    },

    reward: function (officerId) {
      G.API.rewardOfficer(officerId).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '赏赐失败');
          return;
        }
        G.toast((resp && resp.message) || '赏赐成功');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '赏赐失败');
      });
    },

    forgetSkill: function (officerId, skillIdx, action) {
      var s = Core.state;
      var o = findOfficer(s.officers, officerId);
      if (!o || !o.skills || skillIdx >= o.skills.length) { G.toast('技能不存在'); return; }
      var sk = o.skills[skillIdx];
      var skInfo = D.officerSkills[sk.id];
      var skName = skInfo ? skInfo.name : '技能';
      var wrap = document.getElementById('skill-row-' + skillIdx);
      if (action === 'confirm') {
        G.API.abandonSkill(officerId, skillIdx).then(function (resp) {
          if (resp && resp.success === false) {
            G.toast(resp.message || '废弃失败');
            return;
          }
          G.toast((resp && resp.message) || ('已废弃 ' + skName));
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '废弃失败');
        });
        return;
      }
      if (action === 'cancel') {
        if (wrap) {
          wrap.innerHTML = skillRowHtml(officerId, skillIdx, sk);
        }
        return;
      }
      if (wrap) {
        wrap.innerHTML = '<b style="color:var(--accent-dark)">' + skName + '（满级）' + '</b> <span class="d">' + skInfo.desc + '</span> <button type="button" class="btn depot-btn warn" onclick="Game.Officer.forgetSkill(\'' + officerId + '\',' + skillIdx + ',\'confirm\')">[确认废弃]</button> <button type="button" class="btn depot-btn" onclick="Game.Officer.forgetSkill(\'' + officerId + '\',' + skillIdx + ',\'cancel\')">[取消]</button>';
      }
    },

    /** 兼容仍持有旧事件的页面，不再打开升级弹窗或提交升级请求。 */
    openSkillUpgrade: function () {
      G.toast('技能已统一为满级，无需升级');
    },

    /** 返回正式技能与当前军官的学习资格；历史 supply 仅参与已掌握判断。 */
    universalSkillOptions: function (officerId, category) {
      var officer = findOfficer((Core.state && Core.state.officers) || [], officerId);
      if (!officer) return [];
      return Object.keys(D.officerSkills).filter(function (id) {
        if (id === 'supply') return false;
        var domestic = ['harvest', 'construct', 'finance', 'research', 'ration'].indexOf(id) >= 0;
        return !category || category === 'all' || (category === 'domestic' ? domestic : !domestic);
      }).map(function (id) {
        var reason = skillLearningReason(officer, id)
          || (!((Core.state.items || {}).universalSkillBook > 0) ? '通用技能书不足' : '');
        return { id: id, info: D.officerSkills[id], reason: reason };
      });
    },

    /** 仓库与军官详情共用自选流程；只有确认时提交请求，不在客户端扣库存。 */
    openUniversalSkillBook: function (officerId) {
      if (universalSkillPending) return;
      var officer = findOfficer((Core.state && Core.state.officers) || [], officerId);
      if (!officer) { G.toast('军官不存在'); return; }
      if ((officer.skills || []).length >= 3) { G.toast('技能已满3个，请先废弃一个'); return; }
      if (!((Core.state.items || {}).universalSkillBook > 0)) { G.toast('通用技能书不足'); return; }
      Officer.closeUniversalSkillBook();
      var mask = document.createElement('div');
      var selected = '';
      var category = 'all';
      var esc = G.escapeHtml;
      mask.className = 'modal-mask';
      mask.innerHTML = '<div class="modal-card" role="dialog" aria-modal="true" aria-label="通用技能书自选学习">'
        + '<div class="modal-title">通用技能书 · 自选学习</div><div class="modal-body">'
        + '<p>' + esc(officer.name) + ' · 技能 ' + (officer.skills || []).length + '/3 · 通用技能书 ×' + Core.state.items.universalSkillBook + '</p>'
        + '<p>学习后直接获得满级技能，无需升级。</p>'
        + '<label>技能分类 <select name="skillCategory"><option value="all">全部</option><option value="combat">战斗</option><option value="domestic">内政</option></select></label>'
        + '<div data-skill-options></div><p data-skill-summary>请选择一个技能</p></div>'
        + '<div class="modal-foot"><button type="button" class="btn" data-skill-cancel>取消</button>'
        + '<button type="button" class="btn ok" data-skill-confirm disabled>确认学习</button></div></div>';
      document.body.appendChild(mask);
      universalSkillModal = mask;
      var options = mask.querySelector('[data-skill-options]');
      var summary = mask.querySelector('[data-skill-summary]');
      var confirm = mask.querySelector('[data-skill-confirm]');
      var cancel = mask.querySelector('[data-skill-cancel]');
      var filter = mask.querySelector('[name="skillCategory"]');
      function renderOptions() {
        var choices = Officer.universalSkillOptions(officerId, category);
        options.innerHTML = choices.map(function (choice) {
          return '<p><label><input type="radio" name="universalSkill" value="' + esc(choice.id) + '"'
            + (choice.reason ? ' disabled' : '') + (selected === choice.id ? ' checked' : '') + '> '
            + esc(choice.info.name) + '（满级）' + (choice.reason ? '（' + esc(choice.reason) + '）' : '')
            + '</label><br><small>' + esc(choice.info.desc) + '</small></p>';
        }).join('');
        summary.textContent = selected ? '消耗通用技能书 ×1，为【' + officer.name + '】学习【' + D.officerSkills[selected].name + '】满级' : '请选择一个技能';
        confirm.disabled = !selected || universalSkillPending;
      }
      options.onchange = function (event) {
        var choice = Officer.universalSkillOptions(officerId, category).find(function (entry) { return entry.id === event.target.value; });
        if (universalSkillPending || !choice || choice.reason) return;
        selected = choice.id;
        renderOptions();
      };
      filter.onchange = function () { category = filter.value; selected = ''; renderOptions(); };
      cancel.onclick = Officer.closeUniversalSkillBook;
      mask.onclick = function (event) { if (event.target === mask) Officer.closeUniversalSkillBook(); };
      confirm.onclick = function () {
        if (universalSkillPending || !selected) return Promise.resolve();
        var choice = Officer.universalSkillOptions(officerId, category).find(function (entry) { return entry.id === selected; });
        if (!choice || choice.reason) { G.toast(choice ? choice.reason : '请选择有效技能'); selected = ''; renderOptions(); return Promise.resolve(); }
        universalSkillPending = true;
        confirm.disabled = true;
        cancel.disabled = true;
        filter.disabled = true;
        return G.API.depotUse('universalSkillBook', officerId, null, selected).then(function (resp) {
          if (!resp || resp.success !== true) {
            G.toast((resp && resp.message) || '学习失败');
            return;
          }
          universalSkillPending = false;
          Officer.closeUniversalSkillBook();
          G.toast(resp.message || '自选技能学习成功');
          Core.render();
        }).catch(function () {
          G.toast('请求结果待确认，正在刷新技能和库存，请勿重复使用');
          return G.API.getGameState(true).then(function (state) {
            G.API.applyState(state);
            universalSkillPending = false;
            Officer.closeUniversalSkillBook();
            Core.render();
          }).catch(function () {
            confirm.textContent = '请刷新页面确认结果';
          });
        }).finally(function () {
          if (universalSkillPending && confirm.textContent === '请刷新页面确认结果') return;
          universalSkillPending = false;
          if (universalSkillModal === mask) {
            cancel.disabled = false;
            filter.disabled = false;
            selected = '';
            renderOptions();
          }
        });
      };
      renderOptions();
    },

    closeUniversalSkillBook: function () {
      if (universalSkillPending) return;
      if (universalSkillModal && universalSkillModal.parentNode) universalSkillModal.parentNode.removeChild(universalSkillModal);
      universalSkillModal = null;
    },

    learnSkill: function (officerId) {
      G.API.learnSkill(officerId).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '学习失败');
          return;
        }
        G.toast((resp && resp.message) || '学习成功');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '学习失败');
      });
    },

    availableSpecificSkillBooks: function () {
      var items = (Core.state && Core.state.items) || {};
      return Object.keys(D.items).filter(function (itemId) {
        return itemId.indexOf('skillBook_') === 0 && items[itemId] > 0;
      }).map(function (itemId) {
        return { itemId: itemId, info: D.items[itemId], count: items[itemId] };
      });
    },

    /** 提交前再次校验当前军官的学习资格；服务端仍以锁内最新状态决定是否扣书。 */
    useSpecificSkillBook: function (officerId, itemId) {
      var book = D.items[itemId];
      if (!book || itemId.indexOf('skillBook_') !== 0) {
        G.toast('指定技能书不存在');
        return Promise.resolve();
      }
      var officer = findOfficer((Core.state && Core.state.officers) || [], officerId);
      var reason = skillLearningReason(officer, itemId.slice('skillBook_'.length));
      if (reason) {
        G.toast(reason);
        return Promise.resolve({ success: false, message: reason });
      }
      return G.API.depotUse(itemId, officerId).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '使用失败');
          return resp;
        }
        G.toast((resp && resp.message) || ('已学习「' + book.name.replace('技能书', '') + '」'));
        Core.render();
        return resp;
      }).catch(function (err) {
        G.toast(err.message || '使用失败');
      });
    },

    upgradeStar: function (officerId, button) {
      var officer = findOfficer(Core.state && Core.state.officers, officerId);
      if (!officer) { G.toast('军官不存在'); return Promise.resolve(); }
      if (officer.star >= 5) { G.toast('该军官已满星'); return Promise.resolve(); }
      if (!Core.state.items || (Core.state.items.starUp || 0) < 1) { G.toast('星耀符不足'); return Promise.resolve(); }
      if (Officer._starUpPending) return Promise.resolve();
      Officer._starUpPending = true;
      if (button) button.disabled = true;
      return G.API.depotUse('starUp', officerId).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '升星失败');
          return resp;
        }
        G.toast((resp && resp.message) || (resp && resp.upgraded === false ? '升星失败，已消耗1枚星耀符' : '升星成功'));
        Core.render();
        return resp;
      }).catch(function (err) {
        G.toast(err.message || '升星失败');
      }).finally(function () {
        Officer._starUpPending = false;
        if (button) button.disabled = false;
      });
    },

    openStarUpShop: function () {
      if (G.Shop) G.Shop.curCat = 'officer';
      G.go('shop');
    },

    /** 从军官详情直接打开包含套装装备箱的军官商城分类。 */
    openEquipmentShop: function () {
      if (G.Shop) G.Shop.curCat = 'officer';
      G.go('shop');
    },

    useExpBook: function (officerId, itemId, count) {
      G.API.useExpBook(officerId, itemId, count).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '使用失败');
          return;
        }
        G.toast((resp && resp.message) || '已使用经验书');
        if (Core.currentView === 'depotUse') {
          var s = Core.state;
          var curItem = s._depotSelectOfficer || (Game.Depot && Game.Depot._depotItem);
          if (curItem && (!s.items || (s.items[curItem] || 0) <= 0)) {
            if (Game.Depot) Game.Depot._depotItem = null;
            s._depotSelectOfficer = null;
            G.go('depot');
            return;
          }
        }
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '使用失败');
      });
    },

    openExpBookModal: function (officerId, defaultItemId) {
      Officer.closeExpBookModal();
      var s = Core.state;
      var o = findOfficer(s.officers, officerId);
      if (!o) { G.toast('军官不存在'); return; }
      if (o.level >= G.OFFICER_MAX_LEVEL) { G.toast('该军官已达满级'); return; }

      var items = s.items || {};
      var bNormal = items.expBook || 0;
      var bAdv = items.expBookAdv || 0;
      var bRapid = items.expBookRapid || 0;
      var bMax = items.expBookMax || 0;
      var totalBooks = bNormal + bAdv + bRapid + bMax;

      var mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.id = 'expBookModalMask';

      if (totalBooks <= 0) {
        mask.innerHTML = '<div class="modal-card expbook-modal" style="max-width:380px;width:92%">'
          + '<div class="spicker-head" style="display:flex;justify-content:space-between;align-items:center;font-weight:bold;font-size:16px;margin-bottom:12px">'
          + '<span>📖 使用经验书</span>'
          + '<span style="cursor:pointer;font-size:20px;line-height:1;color:var(--muted)" onclick="Game.Officer.closeExpBookModal()">×</span>'
          + '</div>'
          + '<div class="panel" style="text-align:center;padding:20px 10px">'
          + '<div style="font-size:32px;margin-bottom:8px">📚</div>'
          + '<div style="font-size:14px;font-weight:bold;margin-bottom:4px">背包中暂无经验书</div>'
          + '<div class="d" style="color:var(--muted);font-size:12px">可在商城中购买【经验书】、【高级经验书】、【极速经验书】或【满级经验书】</div>'
          + '</div>'
          + '<div class="btn-row" style="margin-top:14px;justify-content:center;gap:10px">'
          + '<button class="btn sm ok" onclick="Game.Officer.closeExpBookModal();Game.go(\'shop\')">前往商城</button>'
          + '<button class="btn sm" onclick="Game.Officer.closeExpBookModal()">关闭</button>'
          + '</div>'
          + '</div>';
        document.body.appendChild(mask);
        Officer._expBookMask = mask;
        mask.addEventListener('click', function (e) { if (e.target === mask) Officer.closeExpBookModal(); });
        return;
      }

      var bookList = [
        { id: 'expBook', name: '经验书', icon: '📘', exp: (G.ServerRules.value('catalog', {}).experienceBooks || {}).expBook, cnt: bNormal },
        { id: 'expBookAdv', name: '高级经验书', icon: '📕', exp: (G.ServerRules.value('catalog', {}).experienceBooks || {}).expBookAdv, cnt: bAdv },
        { id: 'expBookRapid', name: '极速经验书', icon: '📕', exp: (G.ServerRules.value('catalog', {}).experienceBooks || {}).expBookRapid, cnt: bRapid },
        { id: 'expBookMax', name: '满级经验书', icon: '📙', exp: 0, isMax: true, cnt: bMax }
      ];

      var selectedId = (defaultItemId && (items[defaultItemId] || 0) > 0)
        ? defaultItemId
        : (bNormal > 0 ? 'expBook' : (bAdv > 0 ? 'expBookAdv' : (bRapid > 0 ? 'expBookRapid' : (bMax > 0 ? 'expBookMax' : 'expBook'))));
      var selectedBook = bookList.find(function (b) { return b.id === selectedId; }) || bookList[0];
      var maxCount = selectedBook.isMax ? Math.min(1, selectedBook.cnt) : selectedBook.cnt;
      var curQty = Math.max(1, Math.min(1, maxCount));

      var html = '<div class="modal-card expbook-modal" style="max-width:420px;width:92%">'
        + '<div class="spicker-head" style="display:flex;justify-content:space-between;align-items:center;font-weight:bold;font-size:16px;margin-bottom:10px">'
        + '<span>📖 使用经验书</span>'
        + '<span style="cursor:pointer;font-size:20px;line-height:1;color:var(--muted)" onclick="Game.Officer.closeExpBookModal()">×</span>'
        + '</div>'
        + '<div class="d" style="margin-bottom:10px">目标军官: <b style="color:' + (D.starColor[o.star] || '#333') + '">' + G.escapeHtml(o.name) + '</b> (Lv.' + o.level + ' · 当前经验 ' + (o.exp || 0) + '/' + G.expNeeded(o.level) + ')</div>'
        + '<div style="display:flex;gap:10px;margin-bottom:12px" id="expBookCards"></div>'
        + '<div class="expbook-slider-row" id="expBookSliderRow">'
        + '<button class="btn sm" id="expBookBtnDec" style="min-width:32px;padding:2px 8px;font-weight:bold">-</button>'
        + '<input class="qty" id="expBookQtyInput" type="number" min="1" max="' + maxCount + '" value="' + curQty + '" style="width:54px;text-align:center;padding:3px 2px" />'
        + '<button class="btn sm" id="expBookBtnInc" style="min-width:32px;padding:2px 8px;font-weight:bold">+</button>'
        + '<div class="recruit-slider-wrap">'
        + '<input type="range" class="recruit-slider" id="expBookSlider" min="1" max="' + maxCount + '" value="' + curQty + '" />'
        + '</div>'
        + '<button class="btn sm" id="expBookBtnMax" style="padding:2px 8px;font-size:12px">MAX</button>'
        + '</div>'
        + '<div class="expbook-summary" id="expBookSummary"></div>'
        + '<div class="btn-row" style="margin-top:14px;justify-content:flex-end;gap:8px">'
        + '<button class="btn sm" onclick="Game.Officer.closeExpBookModal()">取消</button>'
        + '<button class="btn sm ok" id="expBookConfirmBtn">确认使用</button>'
        + '</div>'
        + '</div>';

      mask.innerHTML = html;
      document.body.appendChild(mask);
      Officer._expBookMask = mask;
      mask.addEventListener('click', function (e) { if (e.target === mask) Officer.closeExpBookModal(); });

      function renderCards() {
        var cardsContainer = mask.querySelector('#expBookCards');
        if (!cardsContainer) return;
        var ch = '';
        bookList.forEach(function (b) {
          var isAct = b.id === selectedId;
          var isDis = b.cnt <= 0;
          ch += '<div class="expbook-card' + (isAct ? ' active' : '') + '" data-bid="' + b.id + '" style="' + (isDis ? 'opacity:0.45;cursor:not-allowed;' : '') + '">'
            + '<div style="font-size:24px;margin-bottom:4px">' + b.icon + '</div>'
            + '<div style="font-weight:bold;font-size:13px">' + b.name + '</div>'
            + '<div style="font-size:12px;color:var(--accent);margin:2px 0">' + (b.isMax ? '直升满级(Lv.100)' : ('+' + b.exp + ' 经验/本')) + '</div>'
            + '<div style="font-size:12px;color:' + (b.cnt > 0 ? '#b3832f' : 'var(--muted)') + ';font-weight:600">拥有: ' + b.cnt + ' 本</div>'
            + '</div>';
        });
        cardsContainer.innerHTML = ch;
        cardsContainer.querySelectorAll('.expbook-card').forEach(function (card) {
          card.onclick = function () {
            var bid = this.getAttribute('data-bid');
            var book = bookList.find(function (b) { return b.id === bid; });
            if (!book || book.cnt <= 0) return;
            selectedId = bid;
            selectedBook = book;
            maxCount = book.isMax ? Math.min(1, book.cnt) : book.cnt;
            if (curQty > maxCount) curQty = maxCount;
            if (curQty < 1) curQty = 1;
            renderCards();
            updateSliderAndSummary();
          };
        });
      }

      function updateSliderAndSummary() {
        var sliderRow = mask.querySelector('#expBookSliderRow');
        var slider = mask.querySelector('#expBookSlider');
        var input = mask.querySelector('#expBookQtyInput');
        var summary = mask.querySelector('#expBookSummary');
        var confirmBtn = mask.querySelector('#expBookConfirmBtn');

        var quote = G.ServerRules.cachedPreview({ action: 'experience', objectId: Number(o.id), id: selectedId, count: selectedBook.isMax ? 1 : curQty }, function () {
          if (Officer._expBookMask === mask && mask.isConnected !== false) updateSliderAndSummary();
        });
        if (confirmBtn) confirmBtn.disabled = !quote;
        if (!quote) { if (summary) summary.textContent = '正在获取经验预览…'; return; }

        if (selectedBook.isMax) {
          curQty = 1;
          if (sliderRow) sliderRow.style.display = 'none';
          var upgraded = quote.upgraded;
          var points = quote.points;
          var levelChangeHtml = '<span style="color:#2e7d32;font-weight:bold">Lv.' + o.level + ' → Lv.' + G.OFFICER_MAX_LEVEL + ' (直升满级 +' + upgraded + ' 级)</span>'
            + '<br><span style="color:var(--accent);font-size:12px">立即获得 +' + points + ' 点可分配属性，经验归 0</span>';

          if (summary) {
            summary.innerHTML = '<div style="display:flex;justify-content:space-between;margin-bottom:4px">'
              + '<span>使用道具: <b>' + selectedBook.icon + ' ' + selectedBook.name + ' × 1</b></span>'
              + '<span style="color:var(--accent);font-weight:bold">直升满级</span>'
              + '</div>'
              + '<div>升级预测: ' + levelChangeHtml + '</div>';
          }
          if (confirmBtn) {
            confirmBtn.textContent = '确认使用 (1本)';
          }
          return;
        }

        if (sliderRow) sliderRow.style.display = 'flex';

        if (slider) {
          slider.max = maxCount;
          slider.min = 1;
          slider.value = curQty;
          var pct = maxCount > 1 ? Math.round(((curQty - 1) / (maxCount - 1)) * 100) : 100;
          slider.style.setProperty('--p', pct + '%');
        }
        if (input) {
          input.max = maxCount;
          input.min = 1;
          input.value = curQty;
        }

        var totalGain = quote.gain, simExp = quote.remainingExp, simLv = quote.level, upLevels = quote.upgraded;

        var levelChangeHtml = '';
        if (upLevels > 0) {
          levelChangeHtml = '<span style="color:#2e7d32;font-weight:bold">Lv.' + o.level + ' → Lv.' + simLv + ' (可升 +' + upLevels + ' 级)</span>';
          if (simLv < G.OFFICER_MAX_LEVEL) {
            levelChangeHtml += '<br><span style="color:var(--muted);font-size:12px">升至 Lv.' + simLv + ' 后剩余经验: ' + simExp + ' / ' + G.expNeeded(simLv) + '</span>';
          } else {
            levelChangeHtml += '<br><span style="color:#2e7d32;font-size:12px">已可直达满级 (Lv.' + G.OFFICER_MAX_LEVEL + ')</span>';
          }
        } else {
          var needMore = quote.neededForNext;
          levelChangeHtml = '<span>Lv.' + o.level + ' (经验: ' + (o.exp || 0) + ' → ' + simExp + ' / ' + G.expNeeded(o.level) + ')</span>'
            + '<br><span style="color:var(--muted);font-size:12px">距升至 Lv.' + (o.level + 1) + ' 还需 ' + (needMore > 0 ? needMore : 0) + ' 经验</span>';
        }

        if (summary) {
          summary.innerHTML = '<div style="display:flex;justify-content:space-between;margin-bottom:4px">'
            + '<span>使用道具: <b>' + selectedBook.icon + ' ' + selectedBook.name + ' × ' + curQty + '</b></span>'
            + '<span style="color:var(--accent);font-weight:bold">+' + totalGain + ' 经验</span>'
            + '</div>'
            + '<div>升级预测: ' + levelChangeHtml + '</div>';
        }

        if (confirmBtn) {
          confirmBtn.textContent = '确认使用 (' + curQty + '本)';
        }
      }

      var slider = mask.querySelector('#expBookSlider');
      var input = mask.querySelector('#expBookQtyInput');
      var btnDec = mask.querySelector('#expBookBtnDec');
      var btnInc = mask.querySelector('#expBookBtnInc');
      var btnMax = mask.querySelector('#expBookBtnMax');
      var confirmBtn = mask.querySelector('#expBookConfirmBtn');

      if (slider) {
        slider.oninput = function () {
          curQty = parseInt(this.value, 10) || 1;
          if (curQty < 1) curQty = 1;
          if (curQty > maxCount) curQty = maxCount;
          updateSliderAndSummary();
        };
      }
      if (input) {
        input.oninput = function () {
          var val = parseInt(this.value, 10);
          if (isNaN(val) || val < 1) val = 1;
          if (val > maxCount) val = maxCount;
          curQty = val;
          updateSliderAndSummary();
        };
        input.onchange = function () {
          var val = parseInt(this.value, 10);
          if (isNaN(val) || val < 1) val = 1;
          if (val > maxCount) val = maxCount;
          curQty = val;
          updateSliderAndSummary();
        };
      }
      if (btnDec) {
        btnDec.onclick = function () {
          if (curQty > 1) {
            curQty--;
            updateSliderAndSummary();
          }
        };
      }
      if (btnInc) {
        btnInc.onclick = function () {
          if (curQty < maxCount) {
            curQty++;
            updateSliderAndSummary();
          }
        };
      }
      if (btnMax) {
        btnMax.onclick = function () {
          curQty = maxCount;
          updateSliderAndSummary();
        };
      }
      if (confirmBtn) {
        confirmBtn.onclick = function () {
          Officer.closeExpBookModal();
          Officer.useExpBook(officerId, selectedId, curQty);
        };
      }

      renderCards();
      updateSliderAndSummary();
    },

    closeExpBookModal: function () {
      if (Officer._expBookMask && Officer._expBookMask.parentNode) {
        Officer._expBookMask.parentNode.removeChild(Officer._expBookMask);
      }
      Officer._expBookMask = null;
    },

    promptRename: function (officerId) {
      if (typeof document === 'undefined' || !document.createElement || !document.body) return;
      Officer.closeRenameModal();

      var s = Core.state || {};
      var o = findOfficer(s.officers, officerId);
      if (!o) { G.toast('军官不存在'); return; }
      var availableAt = Number(o.nameRenameAvailableAt) || 0;

      var trigger = document.activeElement;
      var cardCount = (s.items && s.items.renameCard) || 0;
      var goldCount = (s.resources && s.resources.gold) || 0;
      var esc = G.escapeHtml || function (v) { return String(v); };

      var costTip = '';
      if (cardCount > 0) {
        costTip = '<span style="color:var(--accent)">拥有军官改名卡 ×' + cardCount + '，将优先消耗 1 张【军官改名卡】</span>';
      } else if (goldCount >= 60) {
        costTip = '<span style="color:var(--gold)">未持有军官改名卡，将消耗 60 黄金 (当前黄金: ' + (G.fmt ? G.fmt(goldCount) : goldCount) + ')</span>';
      } else {
        costTip = '<span style="color:var(--danger)">军官改名卡或黄金不足（需 1 张军官改名卡或 60 黄金，当前黄金: ' + (G.fmt ? G.fmt(goldCount) : goldCount) + '）</span>';
      }

      var mask = document.createElement('div');
      mask.id = 'officerRenameModal';
      mask.className = 'modal-mask account-confirm-mask';
      mask.innerHTML = '<div class="modal-card account-confirm officer-rename-dialog" role="dialog" aria-modal="true" aria-labelledby="officerRenameTitle">'
        + '<div class="modal-title" id="officerRenameTitle">修改军官名称</div>'
        + '<form id="officerRenameForm">'
        + '<div class="modal-body">'
        + '<div class="d" style="margin-bottom:8px">当前军官: <b style="color:' + (D.starColor[o.star] || 'inherit') + '">' + esc(o.name) + ' ' + starIcons(o.star) + '</b> (Lv.' + o.level + ')</div>'
        + '<div class="d" style="margin-bottom:10px">' + costTip + '</div>'
        + '<label for="officerRenameInput" style="font-size:13px;font-weight:bold;margin-bottom:4px;display:block">新军官名称</label>'
        + '<input id="officerRenameInput" name="officerName" class="qty" type="text" style="width:100%;box-sizing:border-box;margin-bottom:6px" aria-required="true" autocomplete="off" aria-describedby="officerRenameHint" value="' + esc(o.name) + '">'
        + '<p id="officerRenameHint" style="margin:4px 0 0;font-size:12px;color:var(--muted)">最多12个字符，可用标点、符号和 emoji；不能换行。每天可修改一次，按游戏时间的北京时间 0:00 重置，停服顺延。' + (availableAt > G.gameNow() ? '这名军官今日已修改。' : '') + '</p>'
        + '</div>'
        + '<div class="modal-foot" style="display:flex;justify-content:flex-end;gap:8px;margin-top:14px">'
        + '<button type="button" class="btn depot-btn account-confirm-cancel">取消</button>'
        + '<button type="submit" class="btn depot-btn ok account-confirm-submit"' + (cardCount <= 0 && goldCount < 60 ? ' disabled title="军官改名卡或黄金不足"' : '') + '>确认修改</button>'
        + '</div>'
        + '</form>'
        + '</div>';

      var input = mask.querySelector('#officerRenameInput');
      var cancel = mask.querySelector('.account-confirm-cancel');
      var submit = mask.querySelector('.account-confirm-submit');
      var form = mask.querySelector('#officerRenameForm');
      var saving = false;

      function close(restoreFocus) {
        if (saving) return;
        document.removeEventListener('keydown', onKey, true);
        if (mask.parentNode) mask.parentNode.removeChild(mask);
        if (restoreFocus && trigger && trigger.isConnected && trigger.focus) trigger.focus();
      }

      function onKey(event) {
        event.stopPropagation();
        if (event.key === 'Escape') {
          event.preventDefault();
          close(true);
        }
        if (event.key === 'Tab') {
          var controls = [input, cancel, submit].filter(function (control) { return control && !control.disabled; });
          if (!controls.length) return;
          if (event.shiftKey && document.activeElement === controls[0]) {
            event.preventDefault();
            controls[controls.length - 1].focus();
          } else if (!event.shiftKey && document.activeElement === controls[controls.length - 1]) {
            event.preventDefault();
            controls[0].focus();
          }
        }
      }

      cancel.onclick = function () { close(true); };
      mask.onclick = function (event) { if (event.target === mask) close(true); };
      document.addEventListener('keydown', onKey, true);

      form.onsubmit = function (event) {
        event.preventDefault();
        if (saving) return;
        if (availableAt > G.gameNow()) { G.toast('这名军官今天已改名，请明日 0:00 后再试'); return; }
        var newName;
        try {
          newName = G.normalizeDisplayName(input.value, 12, '军官名称');
        } catch (error) {
          G.toast(error.message);
          if (input.focus) input.focus();
          return;
        }
        if (newName === o.name) {
          G.toast('新名称与当前军官名称相同');
          if (input.focus) input.focus();
          return;
        }

        saving = true;
        input.disabled = true;
        cancel.disabled = true;
        submit.disabled = true;

        G.API.renameOfficer(o.id, newName).then(function (resp) {
          saving = false;
          if (resp && resp.success === false) {
            G.toast(resp.message || '改名失败');
            input.disabled = false;
            cancel.disabled = false;
            submit.disabled = false;
            if (input.focus) input.focus();
            return;
          }
          G.toast((resp && resp.message) || '军官名称已修改');
          close(false);
          Core.render();
        }).catch(function (err) {
          saving = false;
          G.toast((err && err.message) || '网络异常，改名失败');
          input.disabled = false;
          cancel.disabled = false;
          submit.disabled = false;
          if (input.focus) input.focus();
        });
      };

      document.body.appendChild(mask);
      if (input.focus) input.focus();
      if (input.select) input.select();
    },

    openRenameModal: function (officerId) {
      return Officer.promptRename(officerId);
    },

    closeRenameModal: function () {
      var modal = document.getElementById('officerRenameModal');
      if (modal && modal.parentNode) modal.parentNode.removeChild(modal);
    },

    levelUp: function (officerId, all) {
      G.API.officerLevelUp(officerId, all).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '升级失败');
          return;
        }
        G.toast((resp && resp.message) || '升级成功');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '升级失败');
      });
    },

    unequip: function (officerId, itemId) {
      G.API.unequipOfficer(officerId, itemId).then(function (resp) {
        if (resp && resp.success === false) { G.toast(resp.message || '卸下失败'); return; }
        G.toast(resp.message || '已卸下装备');
        Core.render();
      }).catch(function (err) { G.toast(err.message || '卸下失败'); });
    },

    /** 使用基础属性计算可分配空间，装备和套装加成不占培养上限。 */
    addAttr: function (officerId, attr, action, customVal) {
      var s = Core.state;
      var o = findOfficer(s.officers, officerId);
      if (!o) return;
      var wrap = document.getElementById('attr-add-' + attr);
      var attrNames = G.Constants.officerAttributes;
      if (action === 'confirm') {
        var input = document.getElementById('attr-input-' + attr);
        var n = customVal !== undefined ? customVal : (input ? parseInt(input.value, 10) : 0);
        if (isNaN(n) || n < 1) { G.toast('请输入有效点数'); return; }
        if (n > (o.attrPoints || 0)) { G.toast('点数不足，仅有 ' + (o.attrPoints || 0) + ' 点'); return; }
        var cur = baseAttribute(o, attr);
        if (n > ((o.assignable || {})[attr] || 0)) { G.toast('超出服务器允许分配的点数'); return; }
        if (n < 1) { G.toast(attrNames[attr] + '已满'); return; }
        G.API.assignOfficerAttr(officerId, attr, n).then(function (resp) {
          if (resp && resp.success === false) {
            G.toast(resp.message || '加点失败');
            return;
          }
          G.toast((resp && resp.message) || (attrNames[attr] + ' +' + n));
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '加点失败');
        });
        return;
      }
      if (action === 'setVal') {
        var input = document.getElementById('attr-input-' + attr);
        if (input && customVal !== undefined) {
          input.value = customVal;
          input.focus();
        }
        return;
      }
      if (action === 'cancel') {
        if (wrap) wrap.innerHTML = '';
        return;
      }
      var avail = o.attrPoints || 0;
      var cur = baseAttribute(o, attr);
      var maxAdd = (o.assignable || {})[attr] || 0;
      if (maxAdd < 1) { G.toast(attrNames[attr] + '已满或无可用点数'); return; }
      if (wrap) {
        var quick5 = Math.min(5, maxAdd);
        var quickBtns = '';
        if (maxAdd > 1) {
          quickBtns += '<button class="btn sm" style="padding:1px 5px;margin-right:2px" onclick="Game.Officer.addAttr(\'' + officerId + '\',\'' + attr + '\',\'setVal\',1)">+1</button>';
          if (maxAdd >= 5) {
            quickBtns += '<button class="btn sm" style="padding:1px 5px;margin-right:2px" onclick="Game.Officer.addAttr(\'' + officerId + '\',\'' + attr + '\',\'setVal\',' + quick5 + ')">+' + quick5 + '</button>';
          }
          quickBtns += '<button class="btn sm" style="padding:1px 5px;margin-right:4px" onclick="Game.Officer.addAttr(\'' + officerId + '\',\'' + attr + '\',\'setVal\',' + maxAdd + ')">MAX</button>';
        }
        wrap.innerHTML = '<span style="display:inline-flex;align-items:center;gap:3px;margin-left:6px">'
          + quickBtns
          + '<input id="attr-input-' + attr + '" type="number" class="qty" style="width:48px;text-align:center;padding:2px 4px" min="1" max="' + maxAdd + '" value="' + Math.min(1, maxAdd) + '"> '
          + '<button class="btn sm ok" onclick="Game.Officer.addAttr(\'' + officerId + '\',\'' + attr + '\',\'confirm\')">确定</button> '
          + '<button class="btn sm" onclick="Game.Officer.addAttr(\'' + officerId + '\',\'' + attr + '\',\'cancel\')">取消</button>'
          + '</span>';
        var inp = document.getElementById('attr-input-' + attr);
        if (inp) inp.focus();
      }
    },

    wash: function (officerId, action) {
      var s = Core.state;
      var o = findOfficer(s.officers, officerId);
      if (!o) return;
      var oName = o.name;
      var wrap = document.getElementById('wash-row');
      if (action === 'confirm') {
        var cost = 200;
        if (((s.resources && s.resources.gold) || 0) < cost) { G.toast('黄金不足(需 ' + cost + ')'); return; }
        G.API.washOfficer(officerId).then(function (resp) {
          if (resp && resp.success === false) {
            G.toast(resp.message || '洗点失败');
            return;
          }
          G.toast((resp && resp.message) || (oName + ' 已洗点，退还已记录的加点'));
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '洗点失败');
        });
        return;
      }
      if (action === 'cancel') {
        if (wrap) {
          wrap.innerHTML = '<button type="button" class="btn depot-btn warn" onclick="Game.Officer.wash(\'' + officerId + '\',\'ask\')">[洗点(200金)]</button>';
        }
        return;
      }
      if (wrap) {
        wrap.innerHTML = '<button type="button" class="btn depot-btn warn" onclick="Game.Officer.wash(\'' + officerId + '\',\'confirm\')">[确认洗点(消耗200金)]</button> <button type="button" class="btn depot-btn" onclick="Game.Officer.wash(\'' + officerId + '\',\'cancel\')">[取消]</button>';
      }
    },

    /**
     * 军校招募页 (route: 'academy')
     * <p>
     * 只显示刷名单 + 招募候选人。点击军校建筑进入。
     */
    renderAcademyView: function (v) {
      var s = Core.state;
      var academyLv = s.buildings.academy || 0;
      var h = '';

      h += '<div class="desc">军校 Lv.' + academyLv;
      if (academyLv > 0) {
        h += ' · 每批' + (s.academy.candidateCount || 10) + '人';
        // 概览只保留下一次整批概率，概率构成与成长规则收进说明。
        if (typeof s.academy.fiveStarBatchChance === 'number') {
          h += ' · 五星名将概率 <b>' + Number((s.academy.fiveStarBatchChance * 100).toFixed(2)) + '%</b>';
        }
      }
      h += ' <span role="button" tabindex="0" class="link-act" onclick="Game.Officer.showAcademyInfo()" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){event.preventDefault();Game.Officer.showAcademyInfo();}">？招募说明</span></div>';

      if (academyLv <= 0) {
        h += '<div class="panel"><div class="d">尚未建造军校,无法招募军官。请到 <b>军工</b> 建造 <b>军校</b> 后再来。</div></div>';
      } else {
        h += '<div id="academy-refresh-status">' + academyRefreshHtml(s.academy) + '</div>';

        h += '<div id="academy-candidates-region">';
        if (!s.academy.list || !s.academy.list.length) {
          h += '<div class="desc">军校暂无候选人,请刷新。</div>';
        } else {
          h += '<div class="zone-head">候选人名单 (点击卡片直接招募)</div>';
          h += '<div class="academy-candidates-grid menu">';
          s.academy.list.forEach(function (o, i) {
            h += renderCandidateCardHtml(o, i, s);
          });
          h += '</div>';
        }
        h += '</div>';
      }

      h += '<div class="menu-item back" onclick="Game.go(\'buildArmy\')">[0] 返回军工</div>';
      v.innerHTML = h;
    },

    /** 展示本城司令官的有效属性倍率；技能保留在档案，岗位不传播技能。 */
    renderChiefBoard: function () {
      var chief = Core.getOfficerByRole ? Core.getOfficerByRole('commander_in_chief') : null;
      var enabled = !!(Core.state && Core.state.buildings && Core.state.buildings.headquarters);
      var esc = G.escapeHtml;
      var h = '<div class="officer-board-slot slot-commander' + (chief ? ' is-clickable' : '') + '"'
        + (chief ? ' onclick="Game.Officer.showDetail(\'' + chief.id + '\')" onkeydown="Game.Officer.activateBoardSlot(event,\'' + chief.id + '\')" role="button" tabindex="0"' : '') + '>';
      h += '<div class="board-slot-head"><span class="board-slot-role">🎖️ 司令部 · 司令官</span></div>';
      if (chief) {
        h += '<div class="board-slot-officer">' + esc(chief.name) + ' ' + starIcons(chief.star) + '</div>';
        [['military', '攻击'], ['defense', '防御'], ['logistics', '生命'], ['knowledge', '机动']].forEach(function (entry) {
          h += '<div class="board-slot-detail">' + entry[1] + ' <b>+' + Number((chief.chiefPercents || {})[entry[0]] || 0).toFixed(1) + '%</b></div>';
        });
        if (!enabled) h += '<div class="board-slot-detail">司令部未建成，加成暂停</div>';
      } else {
        h += '<div class="board-slot-vacant">暂未任命司令官</div><div class="board-slot-detail">'
          + (enabled ? '在下方选择将领点击【任司令官】' : '先到军工建造司令部') + '</div>';
      }
      if (!enabled) h += '<button type="button" class="btn-card-action" onclick="event.stopPropagation();Game.go(\'buildArmy\')">前往建造司令部</button>';
      return h + '<div class="board-slot-detail">本城全部战斗常驻；装备、套装计入，技能不参与</div></div>';
    },

    /**
     * 军官管理页 (route: 'officer')，展示独立岗位与已招募将领。
     * 4星黄金与5星钻石军官沿用原有卡片设计。
     */
    renderView: function (v) {
      var s = Core.state || {};
      var officers = s.officers || [];
      var esc = G.escapeHtml || function (val) { return String(val == null ? '' : val); };
      var h = '';

      h += '<div class="officer-mgmt-wrap">';
      h += this.renderEquipmentOverview(s);

      // 顶部标题与快速招募入口
      h += '<div class="officer-mgmt-header">';
      h += '<div class="officer-mgmt-title-row">';
      h += '<div class="officer-mgmt-title">🎖️ 参谋部 · 军官管理</div>';
      h += '<button type="button" class="link-act" aria-haspopup="dialog" onclick="Game.Officer.showPositionDetails()">职位详情</button>';
      h += '<button type="button" class="officer-mgmt-recruit-btn" onclick="Game.go(\'academy\')" title="前往军校招募新将领">招募将领 &gt;</button>';
      h += '</div>';
      h += '<div class="officer-mgmt-desc">已招募将领 <b>' + officers.length + '</b> 名。委任市长提升城池资源产能，委任指挥官加持部队攻防与带兵上限。司令官四项属性增益本城全部战斗，装备与套装计入，技能不参与。</div>';
      h += '</div>';

      // 三个独立岗位各占一个军官，司令官不替代市长或城市指挥官。
      var mayor = Core.getOfficerByRole ? Core.getOfficerByRole('mayor') : null;
      var cmd = Core.getOfficerByRole ? Core.getOfficerByRole('commander') : null;

      h += '<div class="officer-board-grid">';

      // 市长看板
      h += '<div class="officer-board-slot slot-mayor' + (mayor ? ' is-clickable' : '') + '"' +
        (mayor ? ' onclick="Game.Officer.showDetail(\'' + mayor.id + '\')" onkeydown="Game.Officer.activateBoardSlot(event,\'' + mayor.id + '\')" role="button" tabindex="0" title="点击查看 ' + esc(mayor.name) + ' 详情"' : '') + '>';
      h += '<div class="board-slot-head">';
      h += '<span class="board-slot-role">👑 执政市长</span>';
      if (mayor) {
        var mayorMax = mayor.level >= (G.OFFICER_MAX_LEVEL || 100);
        h += '<span class="card-level-badge' + (mayorMax ? ' is-max' : '') + '">Lv.' + mayor.level + '</span>';
      }
      h += '</div>';
      if (mayor) {
        h += '<div class="board-slot-officer">';
        h += '<span>' + esc(mayor.name) + '</span> ';
        h += '<span class="card-officer-stars">' + starIcons(mayor.star) + '</span>';
        h += '</div>';
        h += '<div class="board-slot-detail">后勤 <b>' + (mayor.logistics || 0) + '</b> · 学识 <b>' + (mayor.knowledge || 0) + '</b> (后勤增益资源产出，学识增益黄金与科研速度)</div>';
      } else {
        h += '<div class="board-slot-vacant">暂未任命市长</div>';
        h += '<div class="board-slot-detail">在下方列表中选择将领点击【任市长】即可任命</div>';
      }
      h += '</div>';

      // 指挥官看板
      h += '<div class="officer-board-slot slot-commander' + (cmd ? ' is-clickable' : '') + '"' +
        (cmd ? ' onclick="Game.Officer.showDetail(\'' + cmd.id + '\')" onkeydown="Game.Officer.activateBoardSlot(event,\'' + cmd.id + '\')" role="button" tabindex="0" title="点击查看 ' + esc(cmd.name) + ' 详情"' : '') + '>';
      h += '<div class="board-slot-head">';
      h += '<span class="board-slot-role">⚔️ 作战指挥官</span>';
      if (cmd) {
        var cmdMax = cmd.level >= (G.OFFICER_MAX_LEVEL || 100);
        h += '<span class="card-level-badge' + (cmdMax ? ' is-max' : '') + '">Lv.' + cmd.level + '</span>';
      }
      h += '</div>';
      if (cmd) {
        h += '<div class="board-slot-officer">';
        h += '<span>' + esc(cmd.name) + '</span> ';
        h += '<span class="card-officer-stars">' + starIcons(cmd.star) + '</span>';
        h += '</div>';
        var cSkills = skillText(cmd);
        h += '<div class="board-slot-detail">军事 <b>' + (cmd.military || 0) + '</b>' + (cSkills ? ' · ' + esc(cSkills.replace(/^\s*技能:\s*/, '')) : '') + '</div>';
      } else {
        h += '<div class="board-slot-vacant">暂未任命指挥官</div>';
        h += '<div class="board-slot-detail">在下方列表中选择将领点击【任指挥官】提升部队战斗力</div>';
      }
      h += '</div>';

      h += this.renderChiefBoard();
      h += '</div>'; // .officer-board-grid

      // 我的军官 标题栏
      h += '<div class="officer-mgmt-section-head">';
      h += '<div class="officer-mgmt-section-title"><span>🎖️ 我的军官</span><span class="officer-mgmt-count-badge">' + officers.length + ' 名</span></div>';
      h += '</div>';

      // 军官卡片列表
      if (!officers.length) {
        h += '<div class="menu-item" style="text-align:center;padding:24px 16px;color:var(--muted, #64748b)">';
        h += '暂无军官。请到 <b>军工</b> → <b>军校</b> 招募。';
        h += '</div>';
      } else {
        h += '<div class="officer-cards-list">';
        officers.forEach(function (o) {
          var star = Math.min(5, Math.max(1, o.star || 1));
          var maxLv = G.OFFICER_MAX_LEVEL || 100;
          var isMax = (o.level || 1) >= maxLv;
          var need = G.expNeeded ? G.expNeeded(o.level || 1) : 1000;
          var pct = isMax ? 100 : Math.floor(((o.exp || 0) / need) * 100);

          // 星级保留现有边框与角饰；称号只由五星军官的当前属性决定。
          var tierClass = 'tier-normal';
          var frame = officerFrame(o);
          tierClass = frame.tierClass;
          var tierRibbon = frame.ribbon;
          var cornersHtml = frame.corners;

          // 职位徽章
          var roleBadge = '<span class="card-role-badge role-idle">待命</span>';
          if (o.role === 'mayor') {
            roleBadge = '<span class="card-role-badge role-mayor">👑 市长</span>';
          } else if (o.role === 'commander') {
            roleBadge = '<span class="card-role-badge role-commander">⚔️ 指挥官</span>';
          } else if (o.role === 'commander_in_chief') {
            roleBadge = '<span class="card-role-badge role-commander">🎖️ 司令官</span>';
          } else if (o.role === 'march') {
            roleBadge = '<span class="card-role-badge">🚩 行军中</span>';
          }

          // 技能标签构建
          var skillTagsHtml = '';
          if (o.skills && o.skills.length) {
            for (var si = 0; si < o.skills.length; si++) {
              var sItem = o.skills[si];
              if (!sItem) continue;
              var skObj = (sItem.id && D.officerSkills) ? D.officerSkills[sItem.id] : null;
              var skName = (skObj && skObj.name) || sItem.name || sItem.id || '技能';
              skillTagsHtml += '<span class="card-skill-tag">⚡ ' + esc(skName) + (skObj ? '（满级）' : '') + '</span>';
            }
          }
          if (!skillTagsHtml) {
            skillTagsHtml = '<span class="card-skill-tag-empty">暂无特长技能</span>';
          }

          h += '<div class="officer-card ' + tierClass + '"' + frame.rarityAttr + ' onclick="Game.Officer.showDetail(\'' + o.id + '\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){event.preventDefault();Game.Officer.showDetail(\'' + o.id + '\');}" role="button" tabindex="0" title="点击查看 ' + esc(o.name) + ' 详情">';
          h += cornersHtml;
          h += '<div class="officer-card-inner">';

          // 头部：段位、将领名、星级与职位/等级徽章
          h += '<div class="card-header-row">';
          h += '<div class="card-officer-title">';
          h += '<span class="card-officer-name">' + esc(o.name) + '</span>';
          h += '</div>';

          h += '<div class="card-badge-group">';
          h += roleBadge;
          h += '<span class="card-level-badge' + (isMax ? ' is-max' : '') + '">Lv.' + o.level + '</span>';
          h += '</div>';
          h += '</div>';

          // 次头部：星级与稀有度标签
          h += '<div class="card-sub-header-row">';
          h += '<span class="card-officer-stars">' + starIcons(o.star) + '</span>';
          if (tierRibbon) h += tierRibbon;
          h += '</div>';

          // 四维属性矩阵
          h += '<div class="card-stats-grid">';
          h += '<div class="card-stat-pill pill-mil"><span class="stat-pill-label">军事</span><span class="stat-pill-value">' + (o.military || 0) + '</span></div>';
          h += '<div class="card-stat-pill pill-def"><span class="stat-pill-label">防御</span><span class="stat-pill-value">' + (o.defense || 0) + '</span></div>';
          h += '<div class="card-stat-pill pill-log"><span class="stat-pill-label">后勤</span><span class="stat-pill-value">' + (o.logistics || 0) + '</span></div>';
          h += '<div class="card-stat-pill pill-kno"><span class="stat-pill-label">学识</span><span class="stat-pill-value">' + (o.knowledge || 0) + '</span></div>';
          h += '</div>';

          // 忠诚度与俸禄行
          h += '<div class="card-sub-info-row">';
          h += '<span class="card-sub-info-item">❤️ 忠诚 <b>' + (o.loyalty || 0) + '</b></span>';
          h += '<span class="card-sub-info-item">🪙 俸禄 <b>' + (o.salary || 0) + '</b> 金/h</span>';
          h += '</div>';

          // 技能展示
          h += '<div class="card-skills-section">';
          h += '<span class="card-skills-title">技能:</span>';
          h += skillTagsHtml;
          h += '</div>';

          // 经验条
          h += '<div class="card-exp-row">';
          h += '<div class="card-exp-track"><div class="card-exp-fill" style="width:' + Math.min(100, pct) + '%"></div></div>';
          h += '<div class="card-exp-text">';
          if (isMax) {
            h += '<span>经验值</span><span class="card-exp-max-badge">★ 已达最高等级上限</span>';
          } else {
            h += '<span>经验 ' + (o.exp || 0) + '/' + need + '</span><span>' + pct + '%</span>';
          }
          h += '</div>';
          h += '</div>';

          // 底部操作与详情提示
          h += '<div class="card-footer-actions">';
          h += '<div class="card-action-btns">';
          if (G.PlayGuide) h += '<button type="button" class="btn-card-action" onclick="event.stopPropagation();Game.PlayGuide.openOfficerPreview(\'' + o.id + '\')">任命效果</button>';
          if (o.role === 'mayor') {
            h += '<button type="button" class="btn-card-action btn-dismiss" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'mayor\')">取消市长任命</button>';
            h += '<button type="button" class="btn-card-action btn-appoint-commander" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'commander\')">任指挥官</button>';
          } else if (o.role === 'commander') {
            h += '<button type="button" class="btn-card-action btn-appoint-mayor" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'mayor\')">任市长</button>';
            h += '<button type="button" class="btn-card-action btn-dismiss" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'commander\')">取消指挥官任命</button>';
          } else {
            h += '<button type="button" class="btn-card-action btn-appoint-mayor" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'mayor\')">任市长</button>';
            h += '<button type="button" class="btn-card-action btn-appoint-commander" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'commander\')">任指挥官</button>';
          }
          if (o.role === 'commander_in_chief') {
            h += '<button type="button" class="btn-card-action btn-dismiss" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'commander_in_chief\')">取消司令官任命</button>';
          } else {
            var chiefHint = o.role === 'march' ? '（行军中）' : !(s.buildings && Number(s.buildings.headquarters) > 0) ? '（需建司令部）' : '';
            h += '<button type="button" class="btn-card-action btn-appoint-commander" onclick="event.stopPropagation();Game.Officer.appoint(\'' + o.id + '\',\'commander_in_chief\')">任司令官' + chiefHint + '</button>';
          }
          h += '</div>';
          h += '<span class="card-detail-hint">详情 &gt;</span>';
          h += '</div>';

          h += '</div>'; // .officer-card-inner
          h += '</div>'; // .officer-card
        });
        h += '</div>'; // .officer-cards-list
      }

      h += '<div class="menu-item back" onclick="Game.go(\'buildArmy\')" style="margin-top:16px">[0] 返回军工</div>';
      h += '</div>'; // .officer-mgmt-wrap

      v.innerHTML = h;
    },

    showDetail: function (officerId) {
      Officer._detailOfficerId = officerId;
      if (Core.state) Core.state._detailOfficerId = officerId;
      Core.go('officerDetail');
    },

    activateBoardSlot: function (event, officerId) {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      event.preventDefault();
      this.showDetail(officerId);
    },

    equip: function (officerId, itemId) {
      G.API.equipOfficer(officerId, itemId).then(function (resp) {
        if (resp && resp.success === false) { G.toast(resp.message || '装备失败'); return; }
        G.toast('装备成功');
        Core.render();
      }).catch(function (err) { G.toast(err.message || '装备失败'); });
    },

    unequip: function (officerId, itemId) {
      G.API.unequipOfficer(officerId, itemId).then(function (resp) {
        if (resp && resp.success === false) { G.toast(resp.message || '卸下失败'); return; }
        G.toast('已卸下装备');
        Core.render();
      }).catch(function (err) { G.toast(err.message || '卸下失败'); });
    },

    equipmentName: function (itemId) {
      var names = G.Constants.equipmentNames;
      return names[itemId] || itemId;
    },

    _equipmentChoices: {},

    /** 展开状态按军官保存到临时界面状态，换装返回新状态后不自动收起。 */
    toggleEquipmentDetails: function (officerId, expanded) {
      if (!Core.state) return;
      var expandedOfficers = Core.state._expandedOfficerEquipment || (Core.state._expandedOfficerEquipment = {});
      expandedOfficers[officerId] = expanded;
    },

    /** 页面选择只改变预览；确认按钮才向服务端提交穿戴。 */
    equipmentAction: function (action, payload) {
      return G.API.officerEquipment(action, payload).then(function (r) {
        G.toast(r.message || (r.success === false ? '操作失败' : '操作成功'));
        Core.render();
      }).catch(function (err) { G.toast(err.message || '操作失败'); });
    },

    chooseEquipment: function (officerId, key, value) {
      var c = this._equipmentChoices[officerId] || (this._equipmentChoices[officerId] = {});
      c[key] = value; Core.render();
    },

    renderEquipmentOverview: function (s) {
      var p = s.officerEquipment;
      if (!p) return '';
      var esc = G.escapeHtml || function (x) { return String(x); };
      var h = '<details class="officer-equipment-collapsible officer-equipment-overview-details"><summary class="officer-equipment-summary">装备收藏 · 图鉴 ' + (p.collection || []).length + '/288</summary>';
      h += '<div class="officer-equipment-collapsible-body">';
      h += '<details class="officer-equipment-collapsible-inner"><summary class="officer-equipment-subsummary">展示成就（不增加属性）</summary><div class="officer-achievements-list">';
      var descriptions = { first: '任一军官穿齐同套十二件', branches: '四名军官同时穿齐四个分支', collection: '历史获得全部288件', marshal: '任一军官穿齐元帅同套十二件', empire: '四名军官穿齐四分支元帅十二件套' };
      (p.achievements || []).forEach(function (a) {
        h += '<div class="officer-achievement-row' + (a.unlocked ? ' is-unlocked' : '') + '"><span class="achievement-icon-name">' + (a.unlocked ? '★ ' : '☆ ') + esc(a.name) + '</span><span class="achievement-desc">· ' + descriptions[a.id] + '</span></div>';
      });
      h += '</div></details><details class="officer-equipment-collapsible-inner"><summary class="officer-equipment-subsummary">历史获得图鉴</summary><div class="officer-collection-list">';
      var known = p.collection || [];
      Object.keys(G.Constants.equipmentSets).forEach(function (key) {
        var set = G.Constants.equipmentSets[key];
        var setGotCount = set.keys.filter(function (k) { return known.indexOf(k) >= 0; }).length;
        h += '<details class="officer-set-subdetails"><summary>' + set.name + ' ' + setGotCount + '/12</summary><div class="officer-set-sublist">';
        set.keys.forEach(function (k) {
          var has = known.indexOf(k) >= 0;
          h += '<span class="officer-item-pill' + (has ? ' is-got' : '') + '">' + (has ? '已获得 ' : '未获得 ') + G.Constants.equipmentNames[k] + '</span>';
        });
        h += '</div></details>';
      });
      h += '</div></details></div></details>';
      return h;
    },

    /** 十二件装备默认折叠，仅在玩家主动展开后保留当前军官的展开状态。 */
    renderEquipment: function (o, s) {
      var C = G.Constants, cfg = C.officerEquipmentConfig;
      var esc = G.escapeHtml || function (v) { return String(v == null ? '' : v); };
      var equipped = o.equipment || [], bySlot = {}, choices = this._equipmentChoices[o.id] || {};
      var locked = o.role === 'march';
      equipped.forEach(function (e) { bySlot[e.slot] = e; });
      function netText(keys) {
        var preview = G.EquipmentCatalog.preview(o, keys);
        if (preview.pending) return '正在获取属性预览…';
        var net = preview.net;
        return cfg.branches.map(function (b) { return b.name + ' ' + (net[b.key] >= 0 ? '+' : '') + net[b.key]; }).join(' · ');
      }
      var expanded = !!(s._expandedOfficerEquipment && s._expandedOfficerEquipment[o.id]);
      var h = '<details class="officer-detail-section officer-equipment officer-equipment-collapsible"' + (expanded ? ' open' : '')
        + ' ontoggle="Game.Officer.toggleEquipmentDetails(\'' + o.id + '\',this.open)"><summary class="officer-equipment-summary"><span>军官十二件套</span><span>点击展开 / 收起</span></summary><div class="officer-equipment-collapsible-body">';
      h += '<div class="officer-equipment-tip">一箱固定给齐十二件，同套3/6/9/12件累计共鸣。' + (locked ? '<span class="officer-equipment-locked-hint">军官行军期间不可换装。</span>' : '装备属性固定，无需升级；替换装备返还仓库。') + '</div>';
      var groups = { core: '核心装备', clothing: '防护穿着', kit: '随身军具' }, lastGroup = '';
      cfg.slots.forEach(function (slot) {
        if (slot.group !== lastGroup) {
          h += '<div class="officer-equipment-group-title"><span class="officer-equipment-group-bar"></span>' + groups[slot.group] + '</div>';
          lastGroup = slot.group;
        }
        var e = bySlot[slot.key];
        h += '<div class="officer-equipment-card">';
        h += '<div class="officer-equipment-row"><span class="officer-equipment-slot">' + slot.name + '</span><div class="officer-equipment-info">';
        if (e) {
          var bonuses = cfg.branches.filter(function (b) { return (e[b.key] || 0) > 0; }).map(function (b) { return b.name + ' +' + e[b.key]; });
          if (!bonuses.length) bonuses = cfg.branches.map(function (b) { return b.name + ' +' + (e[b.key] || 0); });
          h += '<div class="officer-equipment-item-title"><strong class="officer-equipment-item-name">' + esc(C.equipmentNames[e.itemId] || e.itemId) + '</strong><span class="officer-equipment-bonuses">' + bonuses.join(' · ') + '</span></div>';
        } else {
          h += '<div class="officer-equipment-item-title"><span class="officer-equipment-empty">未装备</span></div>';
        }
        h += '</div>';
        if (e) {
          h += '<button type="button" class="btn sm depot-btn warn officer-equipment-unequip-btn"' + (locked ? ' disabled' : '') + ' onclick="Game.Officer.unequip(\'' + o.id + '\',\'' + e.itemId + '\')">卸下</button>';
        }
        h += '</div>';

        var options = Object.keys(C.equipmentItems).filter(function (k) { return C.equipmentItems[k].slot === slot.key && (s.items || {})[k] > 0; });
        if (options.length) {
          var choice = choices[slot.key] || '';
          h += '<div class="officer-equipment-sub-row officer-equipment-replace-row">';
          h += '<label class="officer-equipment-replace-label"><span class="officer-equipment-sub-label">替换</span>';
          h += '<select class="officer-equipment-select" aria-label="' + slot.name + '替换" onchange="Game.Officer.chooseEquipment(\'' + o.id + '\',\'' + slot.key + '\',this.value)"><option value="">选择装备（' + options.length + '件可用）</option>';
          options.forEach(function (k) { var d = C.equipmentItems[k]; h += '<option value="' + k + '"' + (choice === k ? ' selected' : '') + '>' + d.name + ' ×' + s.items[k] + '（军官Lv.' + d.level + '）</option>'; });
          h += '</select>';
          if (choice && options.indexOf(choice) >= 0) {
            h += '<button type="button" class="btn sm officer-equipment-btn"' + (locked || o.level < C.equipmentItems[choice].level ? ' disabled' : '') + ' onclick="Game.Officer.equip(\'' + o.id + '\',\'' + choice + '\')">确认替换</button>';
          }
          h += '</label>';
          if (choice && options.indexOf(choice) >= 0) {
            var next = equipped.filter(function (v) { return v.slot !== slot.key; }).map(function (v) { return v.itemId; }); next.push(choice);
            h += '<div class="officer-equipment-preview">替换后净变化：' + netText(next) + '</div>';
          }
          h += '</div>';
        }
        h += '</div>';
      });
      if (!Object.keys(C.equipmentItems).some(function (k) { return (s.items || {})[k] > 0; })) h += '<div class="officer-equipment-empty-tip">背包中暂无可穿戴的套装装备；装备箱固定给齐十二件，开启后可在此穿戴。</div>';
      var counts = {};
      equipped.forEach(function (e) { var d = C.equipmentItems[e.itemId]; if (d) counts[d.setKey] = (counts[d.setKey] || 0) + 1; });
      var countKeys = Object.keys(counts);
      if (countKeys.length > 0 || (o.setBonuses || []).length > 0) {
        h += '<div class="officer-equipment-resonance-section">';
        if (countKeys.length > 0) {
          h += '<div class="officer-equipment-set-chips">';
          countKeys.forEach(function (key) {
            var count = counts[key], set = C.equipmentSets[key];
            h += '<div class="officer-equipment-set-chip"><span class="chip-name">' + set.name + '</span><span class="chip-count">' + count + '/12</span><span class="chip-stage">' + (count < 12 ? '下一档 ' + (Math.floor(count / 3) + 1) * 3 + '件' : '完整套装') + '</span></div>';
          });
          h += '</div>';
        }
        (o.setBonuses || []).forEach(function (b) { h += '<div class="officer-set-bonus">★ ' + esc(b.setName) + '：' + esc(b.description) + '</div>'; });
        h += '</div>';
      }
      var setChoice = choices.set || countKeys[0] || 'recruit_military';
      h += '<details class="officer-equipment-collapsible"' + (choices.set ? ' open' : '') + '><summary class="officer-equipment-summary">整套穿戴与属性预览</summary>';
      h += '<div class="officer-equipment-collapsible-body">';
      h += '<div class="officer-equipment-form-row"><label class="officer-equipment-form-label">选择套装</label><select class="officer-equipment-select" aria-label="选择整套装备" onchange="Game.Officer.chooseEquipment(\'' + o.id + '\',\'set\',this.value)">';
      Object.keys(C.equipmentSets).forEach(function (key) { var set = C.equipmentSets[key]; h += '<option value="' + key + '"' + (key === setChoice ? ' selected' : '') + '>' + set.name + ' · 军官Lv.' + set.level + '</option>'; });
      h += '</select></div>';
      var selected = C.equipmentSets[setChoice], missing = selected.keys.filter(function (k) { return !equipped.some(function (e) { return e.itemId === k; }) && !((s.items || {})[k] > 0); });
      h += '<div class="officer-equipment-preview-box">';
      h += '<div class="preview-line">穿戴后净变化：<strong>' + netText(selected.keys) + '</strong></div>';
      h += '<div class="preview-line ' + (missing.length ? 'is-missing' : 'is-ready') + '">' + (missing.length ? '仓库缺少：' + missing.map(function (k) { return C.equipmentNames[k]; }).join('、') : '十二件已备齐') + '</div>';
      h += '</div>';
      h += '<div class="officer-equipment-actions-row">';
      h += '<button type="button" class="btn sm officer-equipment-btn"' + (locked || missing.length || o.level < selected.level ? ' disabled' : '') + ' onclick="Game.Officer.equipmentAction(\'equip-set\',{officerId:\'' + o.id + '\',setKey:\'' + setChoice + '\'})">穿戴整套十二件</button>';
      h += '<button type="button" class="btn sm depot-btn"' + (locked || !equipped.length ? ' disabled' : '') + ' onclick="Game.Officer.equipmentAction(\'unequip-all\',{officerId:\'' + o.id + '\'})">整套卸下</button>';
      h += '</div></div></details>';
      h += '<div class="officer-detail-actions"><button type="button" class="btn sm depot-btn" onclick="Game.Officer.openEquipmentShop()">前往军官商城选购</button></div>';
      return h + this.renderEquipmentOverview(s) + '</div></details>';
    },

    renderDetail: function (v) {
      var s = Core.state;
      var detailId = (s && s._detailOfficerId) || Officer._detailOfficerId;
      var o = findOfficer(s.officers, detailId);
      if (!o) { G.toast('军官不存在'); G.go('officer'); return; }
      if (s && !s._detailOfficerId && detailId) s._detailOfficerId = detailId;
      Officer._detailOfficerId = detailId;
      var esc = G.escapeHtml || function (value) { return String(value == null ? '' : value); };
      var need = G.expNeeded(o.level);
      var pct = o.level >= G.OFFICER_MAX_LEVEL ? 100 : Math.max(0, Math.min(100, Math.floor((o.exp || 0) / need * 100)));
      var loy = o.loyalty || 0;
      var loyLabel = loy >= 80 ? '忠诚' : (loy >= 50 ? '稳定' : (loy >= 30 ? '动摇' : '危险'));
      var rarity = officerRarity(o);
      var h = '';
      h += '<div class="officer-detail">';
      h += '<header class="officer-detail-hero">';
      h += '<div class="officer-detail-identity">';
      h += '<div class="officer-detail-kicker">' + (rarity ? esc(rarity.label) : '山河远征录 · 军官档案') + '</div>';
      h += '<div class="officer-detail-name-row"><h1>' + esc(o.name) + '</h1><button type="button" class="officer-rename-trigger" onclick="Game.Officer.promptRename(\'' + o.id + '\')" title="修改军官名称" aria-label="修改军官名称">✏️ 修改名称</button></div>';
      h += '<div class="officer-detail-stars">' + starIcons(o.star) + '</div>';
      h += '<div class="officer-detail-meta"><span>Lv.' + o.level + (o.level >= G.OFFICER_MAX_LEVEL ? ' · 满级' : '') + '</span><span class="officer-detail-role">' + esc(roleText(o.role)) + '</span></div>';
      h += '</div></header>';
      h += '<section class="officer-detail-promotion">';
      h += '<div><strong>星级晋升</strong>';
      if (o.star < 5) {
        var starUps = (s.items && s.items.starUp) || 0;
        h += '<small>星耀符: ' + starUps + '枚 · 升星失败率' + G.Constants.starUpFailureRates[o.star - 1] + '% · 每次消耗1枚</small></div>';
        h += '<div class="officer-detail-actions">';
        h += '<button type="button" class="btn sm depot-btn"' + (starUps ? ' onclick="Game.Officer.upgradeStar(\'' + o.id + '\',this)"' : ' disabled title="星耀符不足"') + '>[升星至' + (o.star + 1) + '★]</button>';
        h += '<button type="button" class="btn sm depot-btn" onclick="Game.Officer.openStarUpShop()">[商城购买星耀符]</button></div>';
      } else {
        h += '<small>已达5星上限</small></div>';
      }
      h += '</section>';

      if (o.record) {
        var totalB = (o.record.battlesWon || 0) + (o.record.battlesLost || 0);
        var winRate = totalB > 0 ? Math.floor((o.record.battlesWon || 0) / totalB * 100) : 0;
        h += '<section class="officer-detail-section"><div class="officer-detail-section-head"><h2>作战履历</h2><span>参战 ' + totalB + ' 次</span></div>';
        h += '<div class="officer-record-grid"><div><strong>' + (o.record.battlesWon || 0) + '</strong><span>胜场</span></div><div><strong>' + (o.record.battlesLost || 0) + '</strong><span>败场</span></div><div><strong>' + (o.record.enemiesDefeated || 0) + '</strong><span>击毁敌军</span></div><div><strong>' + winRate + '%</strong><span>胜率</span></div></div></section>';
      }

      if (G.PlayGuide) h += G.PlayGuide.officerPreview(o);
      var canAdd = (o.attrPoints || 0) > 0;
      h += '<section class="officer-detail-section"><div class="officer-detail-section-head"><h2>军官能力</h2><span>' + (canAdd ? '可分配点 <b>' + o.attrPoints + '</b>' : '基础能力上限 ' + G.ATTR_MAX) + '</span></div>';
      h += '<div class="officer-attr-grid">';
      var attrs = [
        { key: 'military', label: '军事', value: o.military || 0, effect: o.role === 'commander' ? '指挥官 · 周期回合攻击 +' + (o.military || 0) + '%' : '' },
        { key: 'defense', label: '防御', value: o.defense || 0, effect: o.role === 'commander' ? '指挥官 · 周期回合防御 +' + (o.defense || 0) + '%' : '' },
        { key: 'logistics', label: '后勤', value: o.logistics || 0, effect: o.role === 'mayor' ? '市长 · 资源 +' + (o.logistics || 0) + '%' : '' },
        { key: 'knowledge', label: '学识', value: o.knowledge || 0, effect: o.role === 'mayor' ? '市长 · 黄金 +' + (o.knowledge || 0) + '% · 科研速度 +' + Number(o.researchKnowledgePercent || 0).toFixed(1) + '%' : '' }
      ];
      for (var ai = 0; ai < attrs.length; ai++) {
        var attr = attrs[ai];
        if (o.role === 'commander_in_chief') {
          var chiefEffects = { military: '攻击', defense: '防御', logistics: '生命', knowledge: '机动' };
          attr.effect = '司令官 · ' + chiefEffects[attr.key] + ' +' + Number((o.chiefPercents || {})[attr.key] || 0).toFixed(1) + '%（常驻）';
        }
        var base = baseAttribute(o, attr.key);
        // 总属性已由后端统一计入装备和共鸣，此处只做拆分，不再次叠加目录数值。
        var equipmentBonus = Math.max(0, attr.value - base);
        h += '<div class="officer-attr-row"><div class="officer-attr-main"><span>' + attr.label + '</span><strong>' + attr.value + '</strong>';
        h += equipmentBonus > 0 ? '<small title="基础属性 + 装备加成（含套装共鸣）">（' + base + ' + <span class="officer-attr-equipment-bonus">' + equipmentBonus + '</span>）</small>' : '<small>/ ' + G.ATTR_MAX + '</small>';
        if (canAdd && base < G.ATTR_MAX) h += '<button type="button" class="officer-attr-add" aria-label="增加' + attr.label + '" onclick="Game.Officer.addAttr(\'' + o.id + '\',\'' + attr.key + '\')">+</button>';
        h += '</div>';
        h += '<div class="officer-attr-track"><span style="width:' + Math.max(0, Math.min(100, base / G.ATTR_MAX * 100)) + '%"></span></div>';
        h += '<span id="attr-add-' + attr.key + '" class="officer-attr-editor"></span>';
        if (attr.effect) h += '<small class="officer-attr-effect">' + attr.effect + '</small>';
        h += '</div>';
      }
      if (o.legacyAttrAllocation) h += '<p>历史加点保留原值，此后分配的属性点可洗点退还。</p>';
      h += '</div><div id="wash-row" class="officer-detail-actions officer-wash-row">';
      h += '<button type="button" class="btn depot-btn warn" onclick="Game.Officer.wash(\'' + o.id + '\',\'ask\')">[洗点(200金)]</button>';
      h += '</div></section>';

      h += this.renderEquipment(o, s);

      var bNormal = (s.items && s.items.expBook) || 0;
      var bAdv = (s.items && s.items.expBookAdv) || 0;
      var bRapid = (s.items && s.items.expBookRapid) || 0;
      var bMax = (s.items && s.items.expBookMax) || 0;
      var bookDescParts = [];
      if (bNormal > 0 || (bAdv === 0 && bRapid === 0 && bMax === 0)) bookDescParts.push('初级: ' + bNormal + '本');
      if (bAdv > 0) bookDescParts.push('高级: ' + bAdv + '本');
      if (bRapid > 0) bookDescParts.push('极速: ' + bRapid + '本');
      if (bMax > 0) bookDescParts.push('满级: ' + bMax + '本');
      h += '<section class="officer-detail-section"><div class="officer-detail-section-head"><h2>等级培养</h2><span>' + bookDescParts.join(' · ') + '</span></div>';
      if (o.level < G.OFFICER_MAX_LEVEL) {
        var canUpgrade = (o.exp || 0) >= need;
        var canUpCount = o.availableUpgradeCount || 0;

        h += '<div class="officer-exp-caption"><strong>Lv.' + o.level + '</strong><span>经验 ' + (o.exp || 0) + ' / ' + need + ' · ' + pct + '%</span><strong>Lv.' + (o.level + 1) + '</strong></div>';
        h += '<div class="officer-detail-progress"><span style="width:' + pct + '%"></span></div>';
        h += '<div class="officer-detail-actions">';
        if (canUpgrade) {
          h += '<button class="btn ok sm" onclick="Game.Officer.levelUp(\'' + o.id + '\')">⚡ 升级 (Lv.' + (o.level + 1) + ')</button>';
          if (canUpCount > 1) {
            h += '<button class="btn ok sm" onclick="Game.Officer.levelUp(\'' + o.id + '\', true)">🚀 一键升' + canUpCount + '级 (至Lv.' + (o.level + canUpCount) + ')</button>';
          }
        } else {
          h += '<button class="btn sm" disabled title="经验不足以升至下一级">⚡ 升级</button>';
        }
        var totalBooks = bNormal + bAdv + bRapid + bMax;
        h += '<button class="btn sm' + (totalBooks > 0 ? ' ok' : '') + '" onclick="Game.Officer.openExpBookModal(\'' + o.id + '\')">📖 使用经验书' + (totalBooks > 0 ? ' (余' + totalBooks + '本)' : '(无)') + '</button>';
        h += '</div>';
      } else {
        h += '<div class="officer-detail-note">已达等级上限 (' + G.OFFICER_MAX_LEVEL + '级)</div>';
      }
      h += '</section>';

      var specificSkillBooks = Officer.availableSpecificSkillBooks();
      var specificSkillBookCount = specificSkillBooks.reduce(function (total, book) { return total + book.count; }, 0);
      h += '<section class="officer-detail-section officer-detail-skills"><div class="officer-detail-section-head"><h2>战术技能 <small>' + (o.skills ? o.skills.length : 0) + '/3</small></h2><span>普通 ' + ((s.items && s.items.skillBook) || 0) + '本 · 通用 ' + ((s.items && s.items.universalSkillBook) || 0) + '本 · 指定 ' + specificSkillBookCount + '本</span></div>';
      h += '<p>技能书用途：普通书随机学习新技能；通用书自选学习；已有指定书学习对应技能。全部直接获得满级效果，无需升级。技能满3项时，先废弃一项才能学习新技能。</p>';
      h += '<p><a href="#officer-skill-combinations" onclick="Game.Officer.showSkillCombinations();return false;">技能组合介绍</a></p>';
      if (!o.skills || !o.skills.length) {
        h += '<div class="officer-detail-note">暂无技能</div>';
      } else {
        for (var si = 0; si < o.skills.length; si++) {
          var sk = D.officerSkills[o.skills[si].id];
          if (!sk) continue;
          h += '<div id="skill-row-' + si + '" class="officer-skill-row">' + skillRowHtml(o.id, si, o.skills[si]) + '</div>';
        }
      }
      if (!o.skills || o.skills.length < 3) {
        h += '<div class="officer-detail-actions"><button class="btn sm"' + ((s.items && s.items.skillBook > 0) ? '' : ' disabled title="普通技能书不足"') + ' onclick="Game.Officer.learnSkill(\'' + o.id + '\')">随机学习 · 普通技能书 ×1</button>'
          + '<button class="btn sm"' + ((s.items && s.items.universalSkillBook > 0) ? '' : ' disabled title="通用技能书不足"') + ' onclick="Game.Officer.openUniversalSkillBook(\'' + o.id + '\')">自选学习 · 通用技能书 ×1</button></div>';
      } else {
        h += '<div class="officer-detail-note">技能已满3个，请先废弃一个技能再学习</div>';
      }
      if (specificSkillBooks.length) {
        h += '<div class="officer-detail-subhead">指定技能书</div><div class="officer-detail-actions">';
        for (var bi = 0; bi < specificSkillBooks.length; bi++) {
          var book = specificSkillBooks[bi];
          var reason = skillLearningReason(o, book.itemId.slice('skillBook_'.length));
          var disabled = reason ? ' disabled title="' + esc(reason) + '"' : '';
          var click = reason ? '' : ' onclick="Game.Officer.useSpecificSkillBook(\'' + o.id + '\',\'' + book.itemId + '\')"';
          h += '<button type="button" class="btn depot-btn' + (reason ? '' : ' ok') + '"' + click + disabled + '>[' + officerIconHtml(book.info) + ' 使用' + book.info.name + ' ×' + book.count + ']' + (reason ? '（' + esc(reason) + '）' : '') + '</button>';
        }
        h += '</div>';
      }
      h += '</section>';

      h += '<section class="officer-detail-section officer-detail-loyalty"><div class="officer-detail-section-head"><h2>忠诚与俸禄</h2><span>每小时结算</span></div>';
      h += '<div class="officer-loyalty-heading"><div><small>忠诚度</small><strong class="' + (loy >= 80 ? 'is-high' : loy >= 50 ? 'is-mid' : 'is-low') + '">' + loy + '<span>/100 · ' + loyLabel + '</span></strong></div><div><small>俸禄</small><strong>' + (o.salary || 0) + '<span>金 / 小时</span></strong></div></div>';
      h += '<div class="officer-detail-progress loyalty"><span style="width:' + Math.max(0, Math.min(100, loy)) + '%"></span></div>';
      h += '<div class="officer-detail-note">忠诚度不随闲置或任职自然变化；俸禄自动从本城黄金扣除。连续欠薪满1分钟游戏时间后扣2点忠诚，此后每5秒游戏时间扣2点，恢复支付后重新计时。守城指挥官忠诚为0时，在敌方开启占领的讨伐中守城失败，会被敌方俘虏。</div>';
      var nowMs = G.gameNow();
      var rewardCost = o.rewardCost;
      var cooling = o.rewardCoolAt && nowMs < o.rewardCoolAt;
      if (cooling) {
        h += '<div class="officer-detail-actions"><button class="btn sm" disabled>赏赐冷却中 ' + G.formatDuration((o.rewardCoolAt - nowMs) / 1000) + '</button></div>';
      } else {
        h += '<div class="officer-detail-actions"><button class="btn sm" onclick="Game.Officer.reward(\'' + o.id + '\')">赏赐 · ' + rewardCost + '金</button><span class="officer-detail-note">提升 5-10 点忠诚</span></div>';
      }
      h += '</section>';

      h += '<section class="officer-detail-section officer-detail-last"><div class="officer-detail-section-head"><h2>人事管理</h2></div>';
      h += '<div id="dismiss-row" class="officer-detail-actions">';
      h += '<button type="button" class="btn depot-btn" onclick="Game.Officer.promptRename(\'' + o.id + '\')">[修改名称]</button>';
      h += '<button type="button" class="btn depot-btn warn" onclick="Game.Officer.dismiss(\'' + o.id + '\',\'ask\')">[解雇]</button>';
      h += '</div></section>';

      h += '<button type="button" class="officer-detail-bottom-back" onclick="Game.go(\'officer\')">返回军官列表 ↑</button>';
      h += '</div>';
      v.innerHTML = h;
    },

    /** 展示推荐的三技能搭配，仅供玩家参考，不改变已学技能或消耗技能书。 */
    showSkillCombinations: function () {
      closeSkillDetailModal();
      var esc = G.escapeHtml || function (value) { return String(value); };
      var rows = (D.officerSkillCombinations || []).map(function (combo) {
        var names = combo.skills.map(function (id) { return D.officerSkills[id].name; });
        return '<tr><td>' + esc(combo.direction) + '</td><td>' + esc(names.join('＋')) + '</td><td>' + esc(combo.tactics) + '</td></tr>';
      }).join('');
      var mask = document.createElement('div');
      mask.className = 'modal-mask officer-skill-detail-mask';
      mask.innerHTML = '<section class="modal-card officer-skill-detail-modal" role="dialog" aria-modal="true" aria-labelledby="officerSkillCombinationsTitle">'
        + '<div class="officer-skill-detail-head"><h2 id="officerSkillCombinationsTitle">技能组合介绍</h2>'
        + '<button type="button" class="officer-skill-detail-close" aria-label="关闭技能组合介绍">×</button></div>'
        + '<div class="officer-skill-detail-body"><p>每名军官最多学习3项技能，以下为推荐搭配，所有技能均可自由组合。</p>'
        + '<table><thead><tr><th scope="col">搭配方向</th><th scope="col">三技能组合</th><th scope="col">核心打法</th></tr></thead><tbody>'
        + rows + '</tbody></table></div></section>';
      document.body.appendChild(mask);
      skillDetailModal = mask;
      mask.querySelector('.officer-skill-detail-close').onclick = closeSkillDetailModal;
      mask.addEventListener('click', function (event) { if (event.target === mask) closeSkillDetailModal(); });
      mask.addEventListener('keydown', function (event) { if (event.key === 'Escape') closeSkillDetailModal(); });
      var closeButton = mask.querySelector('.officer-skill-detail-close');
      if (closeButton && closeButton.focus) closeButton.focus();
    },

    showSkillDetail: function (skillId) {
      var skill = D.officerSkills && D.officerSkills[skillId];
      if (!skill) { G.toast('技能不存在'); return; }
      if (typeof document === 'undefined' || !document.createElement || !document.body) return;

      closeSkillDetailModal();
      var esc = G.escapeHtml || function (value) { return String(value); };
      var iconHtml = (skill.icon && G.iconHtml) ? G.iconHtml(skill.icon, skill.name) + ' ' : '';
      var mask = document.createElement('div');
      mask.className = 'modal-mask officer-skill-detail-mask';
      mask.innerHTML = '<section class="modal-card officer-skill-detail-modal" role="dialog" aria-modal="true" aria-labelledby="officerSkillDetailTitle">' +
        '<div class="officer-skill-detail-head">' +
          '<div><div class="officer-skill-detail-kicker">军官技能</div><h2 id="officerSkillDetailTitle">' + iconHtml + esc(skill.name) + '</h2></div>' +
          '<button type="button" class="officer-skill-detail-close" aria-label="关闭技能详情">×</button>' +
        '</div>' +
        '<div class="officer-skill-detail-body">' +
          '<div class="officer-skill-detail-level">技能效果 <b>满级</b></div>' +
          '<div class="officer-skill-detail-section"><h3>技能说明</h3><p>' + esc(skill.desc || '暂无技能说明') + '</p></div>' +
        '</div>' +
      '</section>';
      document.body.appendChild(mask);
      skillDetailModal = mask;

      var close = function () { closeSkillDetailModal(); };
      var closeButton = mask.querySelector('.officer-skill-detail-close');
      if (closeButton) closeButton.onclick = close;
      mask.addEventListener('click', function (event) { if (event.target === mask) close(); });
    },

    closeSkillDetailModal: closeSkillDetailModal
  };

  G.Officer = Officer;
  Core.views.academy = function (v) { Officer.renderAcademyView(v); };
  Core.views.officer = function (v) { Officer.renderView(v); };
  Core.views.officerDetail = function (v) { Officer.renderDetail(v); };
})(window.Game);
