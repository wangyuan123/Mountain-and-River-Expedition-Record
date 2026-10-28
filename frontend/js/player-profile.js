/* global window, document */
window.Game = window.Game || {};
(function (G) {
  'use strict';
  G.gameNow = G.gameNow || function () { return Date.now(); };
  var Core = G.Core;
  var D = G.DATA;
  var protectionTimer = null;

  function stopProtectionTimer() {
    if (protectionTimer != null) clearInterval(protectionTimer);
    protectionTimer = null;
  }

  /** 档案打开时逐秒计算真实剩余时间，并读取最新状态以同步延期或退出。 */
  function startProtectionTimer(drawer) {
    stopProtectionTimer();
    var section = document.getElementById('drawerNewbieProtection');
    if (!section || !Core.newbieProtectionInfo) return;
    var playerId = ((Core.state || {}).player || {}).id;
    function update() {
      if (document.getElementById('playerDrawerMask') !== drawer || !Core.state || (Core.state.player || {}).id !== playerId) {
        stopProtectionTimer();
        return;
      }
      var info = Core.newbieProtectionInfo();
      section.hidden = !info.active;
      var countdown = document.getElementById('drawerNewbieProtectionCountdown');
      if (countdown) countdown.textContent = '还剩' + info.countdown;
    }
    protectionTimer = setInterval(update, 1000);
    update();
  }

  function drawerAmount(value) {
    var amount = Number(value) || 0;
    var display = amount >= 100000000 ? Math.floor(amount / 1000000) / 100 + '亿'
      : amount >= 1000000 ? Math.floor(amount / 1000) / 10 + '万' : G.fmt(amount);
    return '<span title="' + G.fmt(amount) + '">' + display + '</span>';
  }

  function updateDrawerHeight() {
    if (typeof window === 'undefined') return;
    var vh = window.innerHeight || 800;
    if (window.visualViewport && window.visualViewport.height) {
      vh = Math.round(window.visualViewport.height);
    }
    if (typeof document !== 'undefined' && document.documentElement && document.documentElement.style) {
      document.documentElement.style.setProperty('--drawer-height', vh + 'px');
    }
    if (typeof document !== 'undefined') {
      var dr = document.querySelector ? document.querySelector('.player-drawer') : null;
      if (dr && dr.style) {
        dr.style.height = vh + 'px';
        dr.style.maxHeight = vh + 'px';
      }
      var mask = document.getElementById ? document.getElementById('playerDrawerMask') : null;
      if (mask && mask.style) {
        mask.style.height = vh + 'px';
        mask.style.maxHeight = vh + 'px';
      }
    }
  }

  if (typeof window !== 'undefined' && window.addEventListener) {
    updateDrawerHeight();
    window.addEventListener('resize', updateDrawerHeight, { passive: true });
    window.addEventListener('orientationchange', updateDrawerHeight, { passive: true });
    if (window.visualViewport && window.visualViewport.addEventListener) {
      window.visualViewport.addEventListener('resize', updateDrawerHeight, { passive: true });
    }
  }

  G.PlayerProfile = {
    updateDrawerHeight: updateDrawerHeight,
    openPlayerDrawer: function () {
      stopProtectionTimer();
      var mask = document.getElementById('playerDrawerMask');
      if (mask) mask.remove();

      var s = Core.state || {};
      var p = s.player || {};
      var r = s.resources || {};
      var diamond = r.diamond != null ? r.diamond : 0;
      var prestige = s.prestige != null ? s.prestige : (p.prestige != null ? p.prestige : 0);
      var currentAvatar = G.MainView.getCurrentAvatar();
      var nameStr = G.escapeHtml(p.name || p.username || '指挥官');
      var faction = p.faction || 'allies';
      var factionName = (D.factions && D.factions[faction]) ? D.factions[faction].name : '同盟国';
      var rankTier = p.militaryRank || 1;
      var rankInfo = G.getMilitaryRankTierInfo ? G.getMilitaryRankTierInfo(rankTier) : { name: '列兵', tier: 1, baseCap: 25000, isMax: false };
      var rankTitle = rankInfo.name;
      // 与首页保持一致，展示当前选中城市的名称与坐标。
      var cityName = G.escapeHtml(p.cityName || '新城市');
      var cityPos = s.world && (s.world.cityPos || s.world.pos) || {};
      var posX = cityPos.x != null ? cityPos.x : (p.cityPosX != null ? p.cityPosX : (p.posX || 0));
      var posY = cityPos.y != null ? cityPos.y : (p.cityPosY != null ? p.cityPosY : (p.posY || 0));
      var curMorale = Core.morale ? Core.morale() : 70;
      var curTax = s.tax != null ? s.tax : 30;

      var rankProgressHtml = '';
      if (rankInfo.isMax) {
        rankProgressHtml = '<div class="drawer-rank-tip drawer-rank-max">'
          + '<span>已达最高军衔</span><b>' + G.escapeHtml(rankTitle) + '</b>'
          + '</div>';
      } else {
        var needed = (rankInfo.nextPrestige || 0) - prestige;
        var range = (rankInfo.nextPrestige || 0) - (rankInfo.minPrestige || 0);
        var curInRange = prestige - (rankInfo.minPrestige || 0);
        var pct = Math.min(100, Math.max(0, Math.round((curInRange / (range || 1)) * 100)));
        rankProgressHtml = '<button type="button" class="drawer-rank-tip" onclick="Game.Main.closePlayerDrawer();Game.go(\'mainQuest\');">'
          + '<span class="drawer-rank-heading"><span>下一军衔 · <b>' + G.escapeHtml(rankInfo.nextName || '') + '</b></span><span class="drawer-rank-link">晋升任务 ›</span></span>'
          + '<span class="drawer-rank-track" aria-hidden="true"><span style="width:' + pct + '%"></span></span>'
          + '<span class="drawer-rank-note">' + (needed > 0 ? '进度 ' + pct + '% · 还需 ' + G.fmt(needed) + ' 声望' : '声望已达标，查看晋升条件') + '</span>'
          + '</button>';
      }

      var div = document.createElement('div');
      div.id = 'playerDrawerMask';
      div.className = 'player-drawer-mask';
      div.setAttribute('role', 'dialog');
      div.setAttribute('aria-modal', 'true');
      div.setAttribute('aria-labelledby', 'playerDrawerTitle');

      var vipLevel = Number(p.vipLevel) || 0;
      var vipBadgeVisible = p.vipBadgeVisible !== false;
      var vipBadgeHtml = (vipLevel > 0 && vipBadgeVisible)
        ? '<span class="topbar-vip-badge' + (vipLevel >= 4 ? ' vip-badge-gold' : '') + '">VIP ' + vipLevel + '</span>'
        : '';
      var avatarFrameClassName = G.avatarFrameClass ? G.avatarFrameClass(p.avatarFrame) : '';
      var avatarFrameClass = avatarFrameClassName ? ' ' + avatarFrameClassName : '';

      var html = '<div class="player-drawer" onclick="event.stopPropagation()">'
        + '<div class="drawer-header">'
        + '  <div class="drawer-header-title" id="playerDrawerTitle">指挥官档案</div>'
        + '  <button type="button" class="drawer-close-btn" onclick="Game.Main.closePlayerDrawer()" aria-label="关闭指挥官档案">✕</button>'
        + '</div>'
        + '<div class="drawer-body">'
        + '  <div class="drawer-profile-card">'
        + '    <button type="button" class="drawer-avatar-wrap' + avatarFrameClass + '" onclick="Game.Main.openAvatarPicker()" aria-label="更换头像">'
        + '      <img class="drawer-avatar" id="drawerAvatarImg" src="' + currentAvatar + '" alt="头像" onerror="this.onerror=null;this.src=Game.Constants.presetAvatars[0].src"/>'
        + '      <span class="avatar-edit-badge">更换</span>'
        + '    </button>'
        + '    <div class="drawer-profile-meta">'
        + '      <div class="drawer-profile-name-row"><div class="drawer-profile-name">' + nameStr + '</div><button type="button" class="drawer-link-btn" onclick="Game.Main.promptRenameCommander()">改名</button></div>'
        + '      <div class="drawer-online-status">' + G.WS.statusHtml() + '</div>'
        + '      <div class="drawer-badge-row">'
        + '        <span class="drawer-faction-tag ' + faction + '">' + factionName + '</span>'
        + '        <span class="drawer-uid-tag">UID: ' + (p.id || '---') + '</span>'
        + vipBadgeHtml
        + '      </div>'
        + '    </div>'
        + '  </div>'
        + '  <div id="drawerNewbieProtection" hidden><section class="drawer-section" aria-label="新手保护">'
        + '    <div class="drawer-section-title">新手保护</div>'
        + '    <div class="home-alert" role="status"><strong id="drawerNewbieProtectionCountdown"></strong>'
        + '      <div>' + G.escapeHtml(Core.newbieProtectionInfo ? Core.newbieProtectionInfo().rules : '') + '</div>'
        + '    </div>'
        + '  </section></div>'
        + '  <div class="drawer-section">'
        + '    <div class="drawer-section-title">军衔与资产</div>'
        + '    <div class="drawer-assets-card">'
        + '    <div class="drawer-stat-grid">'
        + '      <button type="button" class="drawer-stat-box drawer-stat-action" onclick="Game.Main.showRankCapInfo()" aria-label="查看军衔与带兵上限说明">'
        + '        <div class="stat-k">统帅军衔</div>'
        + '        <div class="stat-v highlight drawer-rank-value">' + G.renderMilitaryRankIcon(rankInfo.tier) + G.escapeHtml(rankTitle) + '</div>'
        + '      </button>'
        + '      <div class="drawer-stat-box">'
        + '        <div class="stat-k">声望值</div>'
        + '        <div class="stat-v">' + drawerAmount(prestige) + '</div>'
        + '      </div>'
        + '      <div class="drawer-stat-box" title="最强单支现有编队的静态估值，不代表实际胜率">'
        + '        <div class="stat-k">编队战力</div>'
        + '        <div class="stat-v">' + (p.formationPower == null ? '—' : drawerAmount(p.formationPower)) + '</div>'
        + '      </div>'
        + '      <div class="drawer-stat-box" title="现有部队及建筑、科技、军官和装备的综合估值，不包含声望">'
        + '        <div class="stat-k">综合实力</div>'
        + '        <div class="stat-v">' + (p.overallPower == null ? '—' : drawerAmount(p.overallPower)) + '</div>'
        + '      </div>'
        + '      <button type="button" class="drawer-stat-box drawer-stat-action" onclick="Game.Main.showRankCapInfo()" aria-label="查看军衔与带兵上限说明">'
        + '        <div class="stat-k">带兵上限</div>'
        + '        <div class="stat-v highlight">' + drawerAmount(Core.armyCap ? Core.armyCap() : 0) + '</div>'
        + '      </button>'
        + '      <button type="button" class="drawer-stat-box drawer-stat-action" onclick="Game.Main.closePlayerDrawer();Game.go(\'recharge\')" aria-label="钻石 ' + G.fmt(diamond) + '，前往充值">'
        + '        <span class="stat-k">钻石 <span aria-hidden="true">›</span></span>'
        + '        <span class="stat-v diamond-text">' + drawerAmount(diamond) + '</span>'
        + '      </button>'
        + '      <button type="button" class="drawer-stat-box drawer-stat-action" onclick="Game.Main.closePlayerDrawer();if(Game.Vip)Game.Vip.openModal();" aria-label="特勤统帅部 VIP ' + vipLevel + '，点击查看">'
        + '        <span class="stat-k">特勤统帅部 <span aria-hidden="true">›</span></span>'
        + '        <span class="stat-v highlight">VIP ' + vipLevel + '</span>'
        + '      </button>'
        + '    </div>'
        + rankProgressHtml
        + '    </div>'
        + '  </div>'
        + '  <div class="drawer-section">'
        + '    <div class="drawer-section-title">当前城市</div>'
        + '    <div class="drawer-info-list">'
        + '      <div class="drawer-info-item">'
        + '        <span class="info-k">城市名称</span>'
        + '        <span class="info-v drawer-city-name"><span>' + cityName + '</span><button type="button" class="drawer-link-btn" onclick="Game.Main.promptRenameCityInDrawer()">修改</button></span>'
        + '      </div>'
        + '      <div class="drawer-info-item">'
        + '        <span class="info-k">城市坐标</span>'
        + '        <span class="info-v">(' + posX + ', ' + posY + ')</span>'
        + '      </div>'
        + '      <div class="drawer-info-item">'
        + '        <span class="info-k">民心</span>'
        + '        <span class="info-v">' + curMorale + ' / 100</span>'
        + '      </div>'
        + '      <div class="drawer-info-item">'
        + '        <span class="info-k">当前税率</span>'
        + '        <span class="info-v">' + curTax + '%</span>'
        + '      </div>'
        + '    </div>'
        + '  </div>'
        + '</div>'
        + '<div class="drawer-footer">'
        + '  <button class="drawer-footer-btn theme-switch-action" onclick="Game.Main.closePlayerDrawer();if(Game.Theme)Game.Theme.showModal();">'
        + '    <span class="btn-lbl">切换主题</span>'
        + '  </button>'
        + '  <button class="drawer-footer-btn settings-action" onclick="Game.Main.closePlayerDrawer();Game.go(\'settings\');">'
        + '    <span class="btn-lbl">系统设置</span>'
        + '  </button>'
        + '</div>'
        + '</div>';

      var militaryAwards = ((s.officerEquipment || {}).achievements || []).filter(function (a) { return a.unlocked; });
      if (militaryAwards.length) {
        var awardsHtml = '<div class="drawer-section"><details class="drawer-awards-details"><summary>军备展示成就 (' + militaryAwards.length + ')</summary>' + militaryAwards.map(function (a) { return '<p>★ ' + G.escapeHtml(a.name) + '</p>'; }).join('') + '</details></div>';
        html = html.replace('</div><div class="drawer-footer">', awardsHtml + '</div><div class="drawer-footer">');
      }
      div.innerHTML = html;
      div.onclick = function (e) {
        if (e.target === div) Game.Main.closePlayerDrawer();
      };
      div.onkeydown = function (e) {
        if (e.key === 'Escape') {
          e.preventDefault();
          Game.Main.closePlayerDrawer();
        }
      };
      if (document.body && document.body.classList) {
        document.body.classList.add('drawer-open');
      }
      document.body.appendChild(div);
      updateDrawerHeight();
      startProtectionTimer(div);

      requestAnimationFrame(function () {
        div.classList.add('active');
        updateDrawerHeight();
        var dr = div.querySelector('.player-drawer');
        if (dr) dr.classList.add('open');
      });
    },

    closePlayerDrawer: function () {
      stopProtectionTimer();
      if (document.body && document.body.classList) {
        document.body.classList.remove('drawer-open');
      }
      var mask = document.getElementById('playerDrawerMask');
      if (!mask) return;
      mask.classList.remove('active');
      var dr = mask.querySelector('.player-drawer');
      if (dr) dr.classList.remove('open');
      setTimeout(function () {
        if (mask && mask.parentNode) mask.parentNode.removeChild(mask);
      }, 250);
    },

    /** 展示各军衔的基础带兵上限，以及当前城市加成后的实际上限。 */
    showRankCapInfo: function () {
      var existing = document.getElementById('rankCapInfoMask');
      if (existing) return;
      var rankInfo = G.getMilitaryRankTierInfo((Core.state.player && Core.state.player.militaryRank) || 1);
      var ranks = D.militaryRanks;
      var skills = Core.getCommanderSkills ? Core.getCommanderSkills() : {};
      var skillLevel = Math.max(skills.leadership || 0, skills.supply || 0);
      var skillBonus = Core.skillBonus ? Math.max(Core.skillBonus('leadership'), Core.skillBonus('supply')) : 0;
      var skillPercent = Math.round(skillBonus * 100);
      var total = Core.armyCap ? Core.armyCap() : (rankInfo.baseCap || 25000);
      var previousFocus = document.activeElement;
      var rows = ranks.map(function (rank) {
        var current = rank.tier === rankInfo.tier;
        return '<tr' + (current ? ' class="rank-cap-current" aria-current="true"' : '') + '>'
          + '<td>' + rank.tier + '</td><th scope="row"><span class="rank-cap-label">' + G.renderMilitaryRankIcon(rank.tier) + G.escapeHtml(rank.name) + (current ? ' <span>（当前）</span>' : '') + '</span></th>'
          + '<td>' + G.fmt(rank.baseCap) + '</td></tr>';
      }).join('');
      var mask = document.createElement('div');
      mask.id = 'rankCapInfoMask';
      mask.className = 'modal-mask rank-cap-mask';
      mask.setAttribute('role', 'dialog');
      mask.setAttribute('aria-modal', 'true');
      mask.setAttribute('aria-labelledby', 'rankCapInfoTitle');
      mask.innerHTML = '<div class="modal-card rank-cap-dialog">'
        + '<div class="rank-cap-header"><h2 id="rankCapInfoTitle">军衔与带兵上限</h2>'
        + '<button type="button" class="rank-cap-close" aria-label="关闭军衔说明">✕</button></div>'
        + '<div class="modal-body rank-cap-body">'
        + '<div class="rank-cap-summary">当前军衔 <strong class="rank-cap-label">' + G.renderMilitaryRankIcon(rankInfo.tier) + G.escapeHtml(rankInfo.name) + '</strong> · 军衔基础上限 <strong>' + G.fmt(rankInfo.baseCap) + '</strong></div>'
        + '<p>三军统帅 Lv.' + skillLevel + '（+' + skillPercent + '%）。</p>'
        + '<p><strong>当前实际带兵上限：' + G.fmt(total) + '</strong></p>'
        + '<p>各军衔的基础上限如下，实际出征仍需当前城市有足够驻军：</p>'
        + '<table class="rank-cap-table"><thead><tr><th scope="col">等级</th><th scope="col">军衔</th><th scope="col">基础上限</th></tr></thead><tbody>' + rows + '</tbody></table>'
        + '</div><div class="modal-foot"><button type="button" class="btn btn-primary rank-cap-done">知道了</button></div>'
        + '</div>';
      document.body.appendChild(mask);
      var close = function () {
        if (mask.parentNode) mask.parentNode.removeChild(mask);
        if (previousFocus && previousFocus.isConnected) previousFocus.focus();
      };
      var closeButton = mask.querySelector('.rank-cap-close');
      closeButton.onclick = close;
      mask.querySelector('.rank-cap-done').onclick = close;
      mask.onclick = function (event) { if (event.target === mask) close(); };
      mask.onkeydown = function (event) { if (event.key === 'Escape') close(); };
      closeButton.focus();
    },

    openAvatarPicker: function () {
      var existing = document.getElementById('avatarPickerMask');
      if (existing) existing.remove();

      var core = (G && G.Core) || (typeof Core !== 'undefined' ? Core : null);
      var p = (core && core.state && core.state.player) ? core.state.player : {};
      var vipLevel = Number(p.vipLevel) || 0;
      var curCustomAvatar = (p.customAvatar || '').trim();
      var curAvatar = G.MainView ? G.MainView.getCurrentAvatar() : '';
      if (!curCustomAvatar && G.MainView && G.MainView.presetAvatars) {
        if (!G.MainView.presetAvatars.some(function (avatar) { return avatar.src === curAvatar; })) {
          curAvatar = G.MainView.presetAvatars[0].src;
        }
      }
      var modal = document.createElement('div');
      modal.id = 'avatarPickerMask';
      modal.className = 'modal-mask avatar-picker-mask';
      modal.style.zIndex = '10500';

      var previewHint = curCustomAvatar ? '当前使用的自定义统帅肖像' : '当前选择的头像';

      var customSectionHtml = '';
      if (vipLevel >= 5) {
        customSectionHtml = '<div class="avatar-vip-custom-box" style="margin:10px 0 14px 0;padding:10px 12px;background:rgba(212,175,55,0.08);border:1px solid rgba(212,175,55,0.3);border-radius:6px;">'
          + '<div style="font-size:12px;font-weight:700;color:#fcd34d;margin-bottom:8px;display:flex;align-items:center;justify-content:space-between;">'
          + '  <span>🎖️ 自定义统帅形象 (VIP 5+ 专属特权)</span>'
          + (curCustomAvatar ? '<span style="font-size:11px;color:#38bdf8;font-weight:normal;">当前生效中</span>' : '')
          + '</div>'
          + '<div style="display:flex;gap:6px;margin-bottom:8px;">'
          + '  <input type="text" id="avatarPickerVipUrl" placeholder="输入外链图片地址 (http/https)" value="' + (G.escapeHtml ? G.escapeHtml(curCustomAvatar) : curCustomAvatar) + '" style="flex:1;padding:5px 8px;font-size:12px;background:var(--surface);border:1px solid var(--border);border-radius:4px;color:var(--text);">'
          + '  <button type="button" class="btn sm" onclick="Game.Main.applyCustomAvatarUrl()">应用链接</button>'
          + '</div>'
          + '<div style="display:flex;gap:6px;align-items:center;justify-content:space-between;flex-wrap:wrap;">'
          + '  <div style="display:flex;gap:6px;align-items:center;">'
          + '    <input type="file" id="avatarPickerVipFile" accept="image/png,image/jpeg,image/webp" style="font-size:11px;max-width:180px;">'
          + '    <button type="button" id="avatarPickerVipUploadBtn" class="btn sm ok" onclick="Game.Main.uploadCustomAvatarFile()">本地上传</button>'
          + '  </div>'
          + (curCustomAvatar ? '  <button type="button" class="btn sm" onclick="Game.Main.clearCustomAvatar()">恢复预设头像</button>' : '')
          + '</div>'
          + '</div>';
      }

      var html = '<div class="modal-dialog avatar-picker-dialog" onclick="event.stopPropagation()">'
        + '<div class="modal-header">'
        + '  <div class="modal-title">选择指挥官头像</div>'
        + '  <button class="modal-close-btn" onclick="Game.Main.closeAvatarPicker()">✕</button>'
        + '</div>'
        + '<div class="modal-body">'
        + '  <div class="avatar-preview-box">'
        + '    <img id="avatarPreviewImg" class="avatar-preview-img" src="' + curAvatar + '" alt="头像预览" onerror="this.onerror=null;this.src=Game.Constants.presetAvatars[0].src"/>'
        + '    <div class="avatar-preview-hint" id="avatarPreviewHint">' + previewHint + '</div>'
        + '  </div>'
        + customSectionHtml
        + '  <div class="avatar-picker-subtitle">历史军人预设头像</div>'
        + '  <div class="avatar-presets-grid" id="avatarPresetsGrid">';

      for (var i = 0; i < G.MainView.presetAvatars.length; i++) {
        var av = G.MainView.presetAvatars[i];
        var isSel = (!curCustomAvatar && av.src === curAvatar) ? ' selected' : '';
        html += '<div class="avatar-preset-item' + isSel + '" data-src="' + av.src + '" onclick="Game.Main.selectPresetAvatar(\'' + av.src + '\')">'
          + '<img class="avatar-preset-img" src="' + av.src + '" alt="' + av.name + '"/>'
          + '<div class="avatar-preset-name">' + av.name + '</div>'
          + '</div>';
      }

      html += '  </div>'
        + '  <div style="margin-top:12px;padding-top:8px;border-top:1px dashed var(--border);display:flex;justify-content:space-between;align-items:center;">'
        + '    <span style="font-size:12px;color:var(--muted);">支持佩戴统帅专属头像框与自定义形象</span>'
        + '    <button type="button" class="btn sm" onclick="Game.Main.closeAvatarPicker();if(Game.Vip)Game.Vip.openModal(\'cosmetics\');">🎖️ 前往特勤装扮 &gt;</button>'
        + '  </div>'
        + '</div>'
        + '<div class="modal-footer" style="display:flex;justify-content:flex-end;gap:10px;">'
        + '  <button class="btn" onclick="Game.Main.closeAvatarPicker()">取消</button>'
        + '  <button class="btn ok" onclick="Game.Main.saveSelectedAvatar()">应用此头像</button>'
        + '</div>'
        + '</div>';

      modal.innerHTML = html;
      modal.onclick = function (e) {
        if (e.target === modal) Game.Main.closeAvatarPicker();
      };
      document.body.appendChild(modal);
      Game.Main._selectedAvatar = curAvatar;
    },

    selectPresetAvatar: function (src) {
      if (!G.MainView.presetAvatars.some(function (avatar) { return avatar.src === src; })) return;
      Game.Main._selectedAvatar = src;
      var pImg = document.getElementById('avatarPreviewImg');
      if (pImg) pImg.src = src;
      var pHint = document.getElementById('avatarPreviewHint');
      if (pHint) pHint.textContent = '已选择预设头像（点击应用生效）';
      var grid = document.getElementById('avatarPresetsGrid');
      if (grid) {
        var items = grid.querySelectorAll('.avatar-preset-item');
        for (var i = 0; i < items.length; i++) {
          if (items[i].getAttribute('data-src') === src) {
            items[i].classList.add('selected');
          } else {
            items[i].classList.remove('selected');
          }
        }
      }
    },

    applyCustomAvatarUrl: function () {
      var input = document.getElementById('avatarPickerVipUrl');
      if (!input || !input.value.trim()) {
        G.toast('请输入外链图片地址');
        return Promise.reject(new Error('请输入外链图片地址'));
      }
      var url = input.value.trim();
      if (!/^https?:\/\//i.test(url)) {
        G.toast('图片链接需以 http:// 或 https:// 开头');
        return Promise.reject(new Error('图片链接需以 http:// 或 https:// 开头'));
      }
      if (!G.API || !G.API.vipSetCustomAvatarUrl) {
        G.toast('自定义头像服务不可用');
        return Promise.reject(new Error('自定义头像服务不可用'));
      }
      return G.API.vipSetCustomAvatarUrl(url).then(function (res) {
        G.toast(res.message || '自定义头像链接已生效！');
        var core = (G && G.Core) || (typeof Core !== 'undefined' ? Core : null);
        if (core && core.state && core.state.player) {
          core.state.player.customAvatar = url;
        }
        var pImg = document.getElementById('avatarPreviewImg');
        if (pImg) pImg.src = url;
        var topImg = document.querySelector('.topbar-avatar');
        if (topImg) topImg.src = url;
        var drawerImg = document.getElementById('drawerAvatarImg');
        if (drawerImg) drawerImg.src = url;
        if (G.Main && G.Main.openAvatarPicker) G.Main.openAvatarPicker();
        if (core && core.renderTop) core.renderTop();
        return res;
      }).catch(function (err) {
        G.toast(err.message || '头像链接设置失败');
        throw err;
      });
    },

    /** 读取并上传本地头像，上传期间防止重复提交，成功后同步各处预览。 */
    uploadCustomAvatarFile: function () {
      var fileInput = document.getElementById('avatarPickerVipFile');
      if (fileInput && fileInput.disabled) return;
      if (!fileInput || !fileInput.files || !fileInput.files[0]) {
        G.toast('请先选择要上传的本地图片');
        return;
      }
      var file = fileInput.files[0];
      // 与后端的原始图片大小限制保持一致，避免读完文件后才被服务器拒绝。
      if (file.size > 1024 * 1024) {
        G.toast('图片大小不能超过 1MB');
        return;
      }
      if (!G.API || !G.API.vipUploadCustomAvatarDataUrl) {
        G.toast('自定义头像上传服务不可用');
        return;
      }
      var uploadButton = document.getElementById('avatarPickerVipUploadBtn');
      fileInput.disabled = true;
      if (uploadButton) {
        uploadButton.disabled = true;
        uploadButton.textContent = '上传中…';
      }
      return new Promise(function (resolve, reject) {
        var reader = new FileReader();
        reader.onload = function (e) { resolve(e.target.result); };
        reader.onerror = function () { reject(new Error('图片读取失败，请重新选择图片')); };
        reader.onabort = function () { reject(new Error('图片读取已取消，请重试')); };
        reader.readAsDataURL(file);
      }).then(function (dataUrl) {
        return G.API.vipUploadCustomAvatarDataUrl(dataUrl);
      }).then(function (res) {
        var newUrl = (res && res.customAvatar) || (res && res.data && res.data.customAvatar);
        if (!newUrl) throw new Error('头像上传结果无效，请重试');
        G.toast(res.message || '自定义头像已上传并生效！');
        var core = (G && G.Core) || (typeof Core !== 'undefined' ? Core : null);
        if (core && core.state && core.state.player) {
          core.state.player.customAvatar = newUrl;
        }
        var pImg = document.getElementById('avatarPreviewImg');
        if (pImg) pImg.src = newUrl;
        var topImg = document.querySelector('.topbar-avatar');
        if (topImg) topImg.src = newUrl;
        var drawerImg = document.getElementById('drawerAvatarImg');
        if (drawerImg) drawerImg.src = newUrl;
        if (G.Main && G.Main.openAvatarPicker) G.Main.openAvatarPicker();
        if (core && core.renderTop) core.renderTop();
        return res;
      }).catch(function (err) {
        G.toast(err.message || '头像上传失败');
      }).finally(function () {
        fileInput.disabled = false;
        if (uploadButton) {
          uploadButton.disabled = false;
          uploadButton.textContent = '本地上传';
        }
      });
    },

    clearCustomAvatar: function () {
      if (!G.API || !G.API.vipSetCustomAvatarUrl) return Promise.resolve();
      return G.API.vipSetCustomAvatarUrl('').then(function () {
        G.toast('已恢复为预设头像');
        var core = (G && G.Core) || (typeof Core !== 'undefined' ? Core : null);
        if (core && core.state && core.state.player) {
          core.state.player.customAvatar = '';
        }
        if (G.Main && G.Main.openAvatarPicker) G.Main.openAvatarPicker();
        if (core && core.renderTop) core.renderTop();
      }).catch(function (err) {
        G.toast(err.message || '恢复预设头像失败');
        throw err;
      });
    },

    saveSelectedAvatar: function () {
      var target = Game.Main._selectedAvatar;
      if (!G.MainView.presetAvatars.some(function (avatar) { return avatar.src === target; })) return;

      var core = (G && G.Core) || (typeof Core !== 'undefined' ? Core : null);
      var s = (core && core.state) || {};
      var p = s.player || {};
      var uname = p.username || '';

      if (p.customAvatar) {
        p.customAvatar = '';
        if (G.API && G.API.vipSetCustomAvatarUrl) {
          G.API.vipSetCustomAvatarUrl('').catch(function () {});
        }
      }

      try {
        if (uname) localStorage.setItem('wargame_avatar_' + uname, target);
      } catch (e) {}

      if (p) p.avatar = target;

      if (G.API && G.API.setAvatar) {
        G.API.setAvatar(target).catch(function (e) {
          console.warn('Sync avatar to server failed (saved locally):', e);
        });
      }

      var topImg = document.querySelector('.topbar-avatar');
      if (topImg) topImg.src = target;

      var drawerImg = document.getElementById('drawerAvatarImg');
      if (drawerImg) drawerImg.src = target;

      Game.Main.closeAvatarPicker();
      G.toast('头像已成功更换！');
    },

    closeAvatarPicker: function () {
      var modal = document.getElementById('avatarPickerMask');
      if (modal) modal.remove();
    },

    /** 统帅名是游戏内展示名，改名不会更改登录用户名或邮件收件人地址。 */
    promptRenameCommander: function () {
      if (document.getElementById('renameCommanderModal')) return;
      var p = (Core.state && Core.state.player) || {};
      var cardCount = Number(Core.state && Core.state.items && Core.state.items.commanderRenameCard) || 0;
      var availableAt = Number(p.commanderNameRenameAvailableAt) || 0;
      var cooldownTip = availableAt > Date.now()
        ? '冷却中，可再次改名时间：' + new Date(availableAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) + '（北京时间）。'
        : Number(p.commanderRenameCount) > 0 ? '已满30天冷却，可使用改名卡。' : '首次使用不限时间，无需等待。';
      var trigger = document.activeElement;
      var mask = document.createElement('div');
      mask.id = 'renameCommanderModal';
      mask.className = 'modal-mask account-confirm-mask';
      mask.innerHTML = '<div class="modal-card account-confirm city-rename-dialog" role="dialog" aria-modal="true" aria-labelledby="renameCommanderTitle">'
        + '<div class="modal-title" id="renameCommanderTitle">修改统帅名</div>'
        + '<form id="renameCommanderForm"><div class="modal-body">'
        + '<label for="renameCommanderInput">游戏内统帅名</label>'
        + '<input id="renameCommanderInput" name="name" type="text" aria-required="true" autocomplete="off" aria-describedby="renameCommanderHint">'
        + '<p id="renameCommanderHint">最多8个字符，可用标点、符号和 emoji；登录用户名不变。</p>'
        + '<p>持有统帅改名卡 ×' + cardCount + '，每次成功消耗1张；暂不在商城出售。</p>'
        + '<p>' + cooldownTip + ' 此后每次改名须距上次成功改名满30天（现实时间）。</p>'
        + (cardCount > 0 ? '' : '<p>统帅改名卡不足，请先领取系统赠礼邮件附件或查看仓库。</p>') + '</div>'
        + '<div class="modal-foot"><button type="button" class="account-confirm-cancel">取消</button>'
        + '<button type="submit" class="account-confirm-submit">保存名称</button></div></form></div>';
      var input = mask.querySelector('#renameCommanderInput');
      var cancel = mask.querySelector('.account-confirm-cancel');
      var submit = mask.querySelector('.account-confirm-submit');
      var saving = false;
      input.value = p.name || p.username || '';
      submit.disabled = cardCount <= 0 || availableAt > Date.now();

      function close(restoreFocus) {
        if (saving) return;
        document.removeEventListener('keydown', onKey, true);
        mask.remove();
        if (restoreFocus && trigger && trigger.isConnected) trigger.focus();
      }
      function onKey(event) {
        event.stopPropagation();
        if (event.key === 'Escape') { event.preventDefault(); close(true); }
        if (event.key === 'Tab') {
          var controls = [input, cancel, submit].filter(function (control) { return !control.disabled; });
          if (event.shiftKey && document.activeElement === controls[0]) {
            event.preventDefault(); controls[controls.length - 1].focus();
          } else if (!event.shiftKey && document.activeElement === controls[controls.length - 1]) {
            event.preventDefault(); controls[0].focus();
          }
        }
      }
      cancel.onclick = function () { close(true); };
      mask.onclick = function (event) { if (event.target === mask) close(true); };
      mask.querySelector('#renameCommanderForm').onsubmit = function (event) {
        event.preventDefault();
        if (saving) return;
        if (cardCount <= 0) { G.toast('统帅改名卡不足，请先领取邮件附件或查看仓库'); return; }
        if (availableAt > Date.now()) { G.toast(cooldownTip); return; }
        var name;
        try {
          name = G.normalizeDisplayName(input.value, 8, '统帅名');
        } catch (error) {
          G.toast(error.message);
          input.focus();
          return;
        }
        if (name === p.name) { G.toast('新名称与当前统帅名相同'); return; }
        saving = true;
        submit.disabled = true;
        G.API.setDisplayName(name).then(function () {
          saving = false;
          close(false);
          Game.Main.closePlayerDrawer();
          G.toast('统帅名已保存，消耗1张统帅改名卡');
          if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
          Core.render();
        }).catch(function (error) {
          saving = false;
          submit.disabled = false;
          G.toast(error.message || '统帅名保存失败');
          input.focus();
        });
      };
      document.body.appendChild(mask);
      document.addEventListener('keydown', onKey, true);
      input.focus();
    },

    promptRenameCityInDrawer: function () {
      if (document.getElementById('renameCityModal')) return;
      var s = Core.state || {};
      var p = s.player || {};
      var oldName = p.cityName || '';
      var availableAt = Number(p.cityNameRenameAvailableAt) || 0;
      var cardCount = Number(s.items && s.items.cityRenameCard) || 0;
      var goldCount = Number(s.resources && s.resources.gold) || 0;
      var costTip = cardCount > 0 ? '持有城市改名卡 ×' + cardCount + '，将优先消耗 1 张。'
        : goldCount >= 60 ? '未持有城市改名卡，将消耗 60 黄金。' : '城市改名卡或黄金不足（需 1 张改名卡或 60 黄金）。';
      var trigger = document.activeElement;
      var mask = document.createElement('div');
      mask.id = 'renameCityModal';
      mask.className = 'modal-mask account-confirm-mask';
      mask.innerHTML = '<div class="modal-card account-confirm city-rename-dialog" role="dialog" aria-modal="true" aria-labelledby="renameCityTitle">'
        + '<div class="modal-title" id="renameCityTitle">修改城市名称</div>'
        + '<form id="renameCityForm">'
        + '<div class="modal-body"><label for="renameCityInput">新城市名称</label>'
        + '<input id="renameCityInput" name="cityName" type="text" maxlength="12" aria-required="true" autocomplete="off" aria-describedby="renameCityHint">'
        + '<p id="renameCityHint">最多12个字符，可用标点、符号和 emoji；不能换行。每天可修改一次，按游戏时间的北京时间 0:00 重置，停服顺延。'
        + (availableAt > G.gameNow() ? '这座城市今日已修改，请明日再试。' : '') + '</p>'
        + '<p class="city-rename-cost">' + costTip + '</p></div>'
        + '<div class="modal-foot"><button type="button" class="account-confirm-cancel">取消</button>'
        + '<button type="submit" class="account-confirm-submit"' + (cardCount <= 0 && goldCount < 60 ? ' disabled title="城市改名卡或黄金不足"' : '') + '>保存名称</button></div>'
        + '</form></div>';
      var input = mask.querySelector('#renameCityInput');
      var cancel = mask.querySelector('.account-confirm-cancel');
      var submit = mask.querySelector('.account-confirm-submit');
      var saving = false;
      input.value = oldName;

      function close(restoreFocus) {
        if (saving) return;
        document.removeEventListener('keydown', onKey, true);
        mask.remove();
        if (restoreFocus && trigger && trigger.isConnected) trigger.focus();
      }
      function onKey(event) {
        event.stopPropagation();
        if (event.key === 'Escape') { event.preventDefault(); close(true); }
        if (event.key === 'Tab') {
          var controls = [input, cancel, submit].filter(function (control) { return !control.disabled; });
          if (event.shiftKey && document.activeElement === controls[0]) {
            event.preventDefault(); controls[controls.length - 1].focus();
          } else if (!event.shiftKey && document.activeElement === controls[controls.length - 1]) {
            event.preventDefault(); controls[0].focus();
          }
        }
      }
      cancel.onclick = function () { close(true); };
      mask.onclick = function (event) { if (event.target === mask) close(true); };
      mask.querySelector('#renameCityForm').onsubmit = function (event) {
        event.preventDefault();
        if (saving) return;
        if (availableAt > G.gameNow()) { G.toast('这座城市今天已改名，请明日 0:00 后再试'); return; }
        if (cardCount <= 0 && goldCount < 60) { G.toast('城市改名卡或黄金不足（需 1 张改名卡或 60 黄金）'); return; }
        var newName;
        try {
          newName = G.normalizeDisplayName(input.value, 12, '城市名称');
        } catch (error) {
          G.toast(error.message);
          input.focus();
          return;
        }
        if (newName === (p.cityName || '新城市')) {
          G.toast('新城市名称与当前名称相同');
          input.focus();
          return;
        }

        function saved() {
          saving = false;
          p.cityName = newName;
          close(false);
          G.toast('城市名称已修改');
          Game.Main.closePlayerDrawer();
          Core.render();
        }
        if (!G.API || !G.API.setCityName) { saved(); return; }
        saving = true;
        submit.disabled = true;
        G.API.setCityName(newName).then(saved).catch(function (err) {
          saving = false;
          submit.disabled = false;
          G.toast(err.message || '城市名称修改失败');
          input.focus();
        });
      };
      document.body.appendChild(mask);
      document.addEventListener('keydown', onKey, true);
      input.focus();
    },

  };
})(window.Game);
