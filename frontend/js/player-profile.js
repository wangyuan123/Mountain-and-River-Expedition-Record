/* global window, document */
window.Game = window.Game || {};
(function (G) {
  'use strict';
  var Core = G.Core;
  var D = G.DATA;

  function drawerAmount(value) {
    var amount = Number(value) || 0;
    var display = amount >= 100000000 ? Math.floor(amount / 1000000) / 100 + '亿'
      : amount >= 1000000 ? Math.floor(amount / 1000) / 10 + '万' : G.fmt(amount);
    return '<span title="' + G.fmt(amount) + '">' + display + '</span>';
  }

  G.PlayerProfile = {
    openPlayerDrawer: function () {
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
      var rankInfo = G.getMilitaryRankTierInfo ? G.getMilitaryRankTierInfo(rankTier) : { name: '列兵', tier: 1, baseCap: 50000, isMax: false };
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

      var html = '<div class="player-drawer" onclick="event.stopPropagation()">'
        + '<div class="drawer-header">'
        + '  <div class="drawer-header-title" id="playerDrawerTitle">指挥官档案</div>'
        + '  <button type="button" class="drawer-close-btn" onclick="Game.Main.closePlayerDrawer()" aria-label="关闭指挥官档案">✕</button>'
        + '</div>'
        + '<div class="drawer-body">'
        + '  <div class="drawer-profile-card">'
        + '    <button type="button" class="drawer-avatar-wrap" onclick="Game.Main.openAvatarPicker()" aria-label="更换头像">'
        + '      <img class="drawer-avatar" id="drawerAvatarImg" src="' + currentAvatar + '" alt="头像" onerror="this.src=\'img/avatars/commander-8.svg\'"/>'
        + '      <span class="avatar-edit-badge">更换</span>'
        + '    </button>'
        + '    <div class="drawer-profile-meta">'
        + '      <div class="drawer-profile-name">' + nameStr + '</div>'
        + '      <div class="drawer-online-status">' + G.WS.statusHtml() + '</div>'
        + '      <div class="drawer-badge-row">'
        + '        <span class="drawer-faction-tag ' + faction + '">' + factionName + '</span>'
        + '        <span class="drawer-uid-tag">UID: ' + (p.id || '---') + '</span>'
        + '      </div>'
        + '    </div>'
        + '  </div>'
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
        + '      <div class="drawer-stat-box">'
        + '        <div class="stat-k">带兵上限</div>'
        + '        <div class="stat-v highlight">' + drawerAmount(Core.armyCap ? Core.armyCap() : 0) + '</div>'
        + '      </div>'
        + '      <button type="button" class="drawer-stat-box drawer-stat-action" onclick="Game.Main.closePlayerDrawer();Game.go(\'recharge\')" aria-label="钻石 ' + G.fmt(diamond) + '，前往充值">'
        + '        <span class="stat-k">钻石 <span aria-hidden="true">›</span></span>'
        + '        <span class="stat-v diamond-text">' + drawerAmount(diamond) + '</span>'
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

      div.innerHTML = html;
      div.onclick = function (e) {
        if (e.target === div) Game.Main.closePlayerDrawer();
      };
      document.body.appendChild(div);

      requestAnimationFrame(function () {
        div.classList.add('active');
        var dr = div.querySelector('.player-drawer');
        if (dr) dr.classList.add('open');
      });
    },

    closePlayerDrawer: function () {
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
      var wallLevel = Core.buildingLevel ? Core.buildingLevel('wall') : 0;
      var wallBonus = wallLevel >= 10 ? 100000 : 0;
      var skills = Core.getCommanderSkills ? Core.getCommanderSkills() : {};
      var skillLevel = Math.max(skills.leadership || 0, skills.supply || 0);
      var skillBonus = Core.skillBonus ? Math.max(Core.skillBonus('leadership'), Core.skillBonus('supply')) : 0;
      var skillPercent = Math.round(skillBonus * 100);
      var total = Core.armyCap();
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
        + '<p>当前城市围墙 Lv.' + wallLevel + (wallBonus ? '（满级，+100,000）' : '（满级 Lv.10 后 +100,000）') + '；三军统帅 Lv.' + skillLevel + '（+' + skillPercent + '%）。</p>'
        + '<p><strong>当前实际带兵上限：(' + G.fmt(rankInfo.baseCap) + ' + ' + G.fmt(wallBonus) + ') × (1 + ' + skillPercent + '%) = ' + G.fmt(total) + '</strong></p>'
        + '<p>各军衔的基础上限如下；晋升后基础上限提高，实际出征仍需当前城市有足够驻军。</p>'
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

      var curAvatar = G.MainView.getCurrentAvatar();
      var modal = document.createElement('div');
      modal.id = 'avatarPickerMask';
      modal.className = 'modal-mask avatar-picker-mask';
      modal.style.zIndex = '10500';

      var html = '<div class="modal-dialog avatar-picker-dialog" onclick="event.stopPropagation()">'
        + '<div class="modal-header">'
        + '  <div class="modal-title">自定义指挥官头像</div>'
        + '  <button class="modal-close-btn" onclick="Game.Main.closeAvatarPicker()">✕</button>'
        + '</div>'
        + '<div class="modal-body">'
        + '  <div class="avatar-preview-box">'
        + '    <img id="avatarPreviewImg" class="avatar-preview-img" src="' + curAvatar + '" alt="头像预览" onerror="this.src=\'img/avatars/commander-8.svg\'"/>'
        + '    <div class="avatar-preview-hint">当前选择的头像</div>'
        + '  </div>'
        + '  <div class="avatar-picker-subtitle">🎖️ 预设军事指挥官头像</div>'
        + '  <div class="avatar-presets-grid" id="avatarPresetsGrid">';

      for (var i = 0; i < G.MainView.presetAvatars.length; i++) {
        var av = G.MainView.presetAvatars[i];
        var isSel = (av.src === curAvatar) ? ' selected' : '';
        html += '<div class="avatar-preset-item' + isSel + '" data-src="' + av.src + '" onclick="Game.Main.selectPresetAvatar(\'' + av.src + '\')">'
          + '<img class="avatar-preset-img" src="' + av.src + '" alt="' + av.name + '"/>'
          + '<div class="avatar-preset-name">' + av.name + '</div>'
          + '</div>';
      }

      html += '  </div>'
        + '  <div class="avatar-picker-subtitle" style="margin-top:14px;">🌐 或输入自定义图片网络 URL</div>'
        + '  <div class="avatar-custom-input-row">'
        + '    <input type="text" id="customAvatarInput" class="avatar-custom-input" placeholder="输入 https://... 图片地址" value="' + (curAvatar.indexOf('http') === 0 ? curAvatar : '') + '"/>'
        + '    <button class="btn sm" onclick="Game.Main.previewCustomAvatar()">预览</button>'
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
      Game.Main._selectedAvatar = src;
      var pImg = document.getElementById('avatarPreviewImg');
      if (pImg) pImg.src = src;
      var ipt = document.getElementById('customAvatarInput');
      if (ipt) ipt.value = '';
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

    previewCustomAvatar: function () {
      var ipt = document.getElementById('customAvatarInput');
      var val = ipt ? ipt.value.trim() : '';
      if (!val) { G.toast('请输入有效的图片链接'); return; }
      Game.Main._selectedAvatar = val;
      var pImg = document.getElementById('avatarPreviewImg');
      if (pImg) pImg.src = val;
      var grid = document.getElementById('avatarPresetsGrid');
      if (grid) {
        var items = grid.querySelectorAll('.avatar-preset-item');
        for (var i = 0; i < items.length; i++) items[i].classList.remove('selected');
      }
    },

    saveSelectedAvatar: function () {
      var target = Game.Main._selectedAvatar;
      var ipt = document.getElementById('customAvatarInput');
      if (ipt && ipt.value.trim()) {
        target = ipt.value.trim();
      }
      if (!target) target = 'img/avatars/commander-8.svg';

      var s = Core.state || {};
      var p = s.player || {};
      var uname = p.username || '';

      try {
        if (uname) localStorage.setItem('wargame_avatar_' + uname, target);
        localStorage.setItem('wargame_avatar_default', target);
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

    promptRenameCityInDrawer: function () {
      var s = Core.state || {};
      var p = s.player || {};
      var oldName = p.cityName || '';
      var newName = prompt('请输入新城市名称 (最多12字):', oldName);
      if (newName === null) return;
      newName = newName.trim();
      if (!newName) { G.toast('城市名不能为空'); return; }
      var safeRe = /^[A-Za-z0-9_\u4e00-\u9fa5·\s]{1,12}$/;
      if (!safeRe.test(newName)) { G.toast('城市名仅限中英文/数字/下划线，最多12字'); return; }

      if (G.API && G.API.setCityName) {
        G.API.setCityName(newName).then(function () {
          p.cityName = newName;
          G.toast('城市名称已修改');
          Game.Main.closePlayerDrawer();
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '城市名称修改失败');
        });
      } else {
        p.cityName = newName;
        G.toast('城市名称已修改');
        Game.Main.closePlayerDrawer();
        Core.render();
      }
    },

  };
})(window.Game);
