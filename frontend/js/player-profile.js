/* global window, document */
window.Game = window.Game || {};
(function (G) {
  'use strict';
  var Core = G.Core;
  var D = G.DATA;

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
      var rankInfo = G.getMilitaryRankTierInfo ? G.getMilitaryRankTierInfo(rankTier) : { name: '列兵', tier: 1, baseCap: 1000, isMax: false };
      var rankTitle = rankInfo.name;
      var cityName = G.escapeHtml(p.cityName || '未命名主城');
      var posX = p.cityPosX != null ? p.cityPosX : (p.posX || 0);
      var posY = p.cityPosY != null ? p.cityPosY : (p.posY || 0);
      var curMorale = Core.morale ? Core.morale() : 70;
      var curTax = s.tax != null ? s.tax : 30;

      var rankProgressHtml = '';
      if (rankInfo.isMax) {
        rankProgressHtml = '<div class="drawer-rank-tip" style="margin-top:8px;font-size:12px;color:var(--ok);background:rgba(82,196,26,0.1);padding:6px 10px;border-radius:6px;display:flex;align-items:center;justify-content:space-between;">'
          + '<span>🎖️ 已晋升至最高统帅军衔【上将】</span>'
          + '<span>⭐ MAX</span>'
          + '</div>';
      } else {
        var needed = (rankInfo.nextPrestige || 0) - prestige;
        var range = (rankInfo.nextPrestige || 0) - (rankInfo.minPrestige || 0);
        var curInRange = prestige - (rankInfo.minPrestige || 0);
        var pct = Math.min(100, Math.max(0, Math.round((curInRange / (range || 1)) * 100)));
        rankProgressHtml = '<div class="drawer-rank-tip" style="margin-top:8px;font-size:12px;background:rgba(255,255,255,0.04);padding:8px 10px;border-radius:6px;border:1px solid rgba(255,255,255,0.08);cursor:pointer;" onclick="var m=document.getElementById(\'playerDrawerMask\');if(m)m.remove();Game.go(\'mainQuest\');">'
          + '<div style="display:flex;justify-content:space-between;margin-bottom:4px;">'
          + '  <span style="color:var(--muted);">下一军衔: <b style="color:var(--accent);">【' + rankInfo.nextName + '】</b></span>'
          + '  <span style="color:var(--accent);font-size:11px;">前往晋升任务 ›</span>'
          + '</div>'
          + '<div style="width:100%;height:5px;background:rgba(0,0,0,0.3);border-radius:3px;overflow:hidden;">'
          + '  <div style="width:' + pct + '%;height:100%;background:linear-gradient(90deg, #d4a359, #ffc53d);border-radius:3px;"></div>'
          + '</div>'
          + '</div>';
      }

      var div = document.createElement('div');
      div.id = 'playerDrawerMask';
      div.className = 'player-drawer-mask';
      div.setAttribute('role', 'dialog');
      div.setAttribute('aria-modal', 'true');

      var html = '<div class="player-drawer" onclick="event.stopPropagation()">'
        + '<div class="drawer-header">'
        + '  <div class="drawer-header-title">指挥官档案</div>'
        + '  <button class="drawer-close-btn" onclick="Game.Main.closePlayerDrawer()" title="关闭">✕</button>'
        + '</div>'
        + '<div class="drawer-body">'
        + '  <div class="drawer-profile-card">'
        + '    <div class="drawer-avatar-wrap" onclick="Game.Main.openAvatarPicker()" title="点击自定义/更换头像">'
        + '      <img class="drawer-avatar" id="drawerAvatarImg" src="' + currentAvatar + '" alt="头像" onerror="this.src=\'img/avatars/commander-8.svg\'"/>'
        + '      <div class="avatar-edit-badge">📷 更换</div>'
        + '    </div>'
        + '    <div class="drawer-profile-meta">'
        + '      <div class="drawer-profile-name">' + nameStr + '</div>'
        + '      <div class="drawer-online-status"><span class="online-dot"></span> 在线 - 5G</div>'
        + '      <div class="drawer-badge-row">'
        + '        <span class="drawer-faction-tag ' + faction + '">' + factionName + '</span>'
        + '        <span class="drawer-uid-tag">UID: ' + (p.id || '---') + '</span>'
        + '      </div>'
        + '    </div>'
        + '  </div>'
        + '  <div class="drawer-section">'
        + '    <div class="drawer-section-title">🎖️ 统帅军衔与资产</div>'
        + '    <div class="drawer-stat-grid">'
        + '      <div class="drawer-stat-box">'
        + '        <div class="stat-k">统帅军衔</div>'
        + '        <div class="stat-v highlight">' + rankTitle + '</div>'
        + '      </div>'
        + '      <div class="drawer-stat-box">'
        + '        <div class="stat-k">声望值</div>'
        + '        <div class="stat-v">' + G.fmt(prestige) + '</div>'
        + '      </div>'
        + '      <div class="drawer-stat-box">'
        + '        <div class="stat-k">带兵上限</div>'
        + '        <div class="stat-v highlight">' + G.fmt(Core.armyCap ? Core.armyCap() : 0) + '</div>'
        + '      </div>'
        + '      <div class="drawer-stat-box" onclick="Game.Main.closePlayerDrawer();Game.go(\'recharge\')" style="cursor:pointer;" title="点击充值钻石">'
        + '        <div class="stat-k">钻石资产</div>'
        + '        <div class="stat-v diamond-text">💎 ' + G.fmt(diamond) + '</div>'
        + '      </div>'
        + '    </div>'
        + rankProgressHtml
        + '  </div>'
        + '  <div class="drawer-section">'
        + '    <div class="drawer-section-title">🏛️ 领地首府现状</div>'
        + '    <div class="drawer-info-list">'
        + '      <div class="drawer-info-item">'
        + '        <span class="info-k">主城名称</span>'
        + '        <span class="info-v">' + cityName + ' <a class="drawer-link-btn" onclick="Game.Main.promptRenameCityInDrawer()">[修改]</a></span>'
        + '      </div>'
        + '      <div class="drawer-info-item">'
        + '        <span class="info-k">首府坐标</span>'
        + '        <span class="info-v">(' + posX + ', ' + posY + ')</span>'
        + '      </div>'
        + '      <div class="drawer-info-item">'
        + '        <span class="info-k">领地民心</span>'
        + '        <span class="info-v">' + curMorale + ' / 100</span>'
        + '      </div>'
        + '      <div class="drawer-info-item">'
        + '        <span class="info-k">当前税率</span>'
        + '        <span class="info-v">' + curTax + '%</span>'
        + '      </div>'
        + '    </div>'
        + '  </div>'
        + '  <div class="drawer-section">'
        + '    <div class="drawer-section-title">⚡ 指挥官快捷操作</div>'
        + '    <div class="drawer-actions-grid">'
        + '      <button class="btn sm" onclick="Game.Main.openAvatarPicker()">🖼️ 更换个性头像</button>'
        + '      <button class="btn sm" onclick="Game.Main.promptRenameCityInDrawer()">🏛️ 变更主城名称</button>'
        + '    </div>'
        + '  </div>'
        + '</div>'
        + '<div class="drawer-footer">'
        + '  <button class="drawer-footer-btn theme-switch-action" onclick="Game.Main.closePlayerDrawer();if(Game.Theme)Game.Theme.showModal();">'
        + '    <span class="btn-emoji">🎨</span>'
        + '    <span class="btn-lbl">切换主题</span>'
        + '  </button>'
        + '  <button class="drawer-footer-btn settings-action" onclick="Game.Main.closePlayerDrawer();Game.go(\'settings\');">'
        + '    <span class="btn-emoji">⚙️</span>'
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
      var newName = prompt('请输入新主城名称 (最多12字):', oldName);
      if (newName === null) return;
      newName = newName.trim();
      if (!newName) { G.toast('城市名不能为空'); return; }
      var safeRe = /^[A-Za-z0-9_\u4e00-\u9fa5·\s]{1,12}$/;
      if (!safeRe.test(newName)) { G.toast('城市名仅限中英文/数字/下划线，最多12字'); return; }

      if (G.API && G.API.setCityName) {
        G.API.setCityName(newName).then(function () {
          p.cityName = newName;
          G.toast('主城名称已修改');
          Game.Main.closePlayerDrawer();
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '主城名称修改失败');
        });
      } else {
        p.cityName = newName;
        G.toast('主城名称已修改');
        Game.Main.closePlayerDrawer();
        Core.render();
      }
    },

  };
})(window.Game);
