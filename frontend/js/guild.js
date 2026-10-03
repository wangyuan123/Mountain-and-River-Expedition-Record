/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';
  var Core = G.Core;

  function esc(value) { return G.escapeHtml(String(value == null ? '' : value)); }

  var iconPresets = [
    { id: 'g01', name: '苍鹰', file: 'eagle.svg' },
    { id: 'g02', name: '山河', file: 'mountain.svg' },
    { id: 'g03', name: '星芒', file: 'compass.svg' },
    { id: 'g04', name: '海卫', file: 'anchor.svg' },
    { id: 'g05', name: '铁翼', file: 'wings.svg' },
    { id: 'g06', name: '烽火', file: 'flame.svg' }
  ];

  function presetFor(icon) { return iconPresets.find(function (preset) { return preset.id === icon; }); }

  function iconHtml(icon) {
    var preset = presetFor(icon);
    if (preset) return '<img class="guild-emblem" src="img/guild/' + preset.file + '" alt="' + preset.name + '徽章">';
    return '<span class="guild-emblem-legacy">' + esc(icon || '⚑') + '</span>';
  }

  function goldIconHtml() {
    return '<img class="res-icon-img guild-gold-icon" src="img/resources/models/gold.webp" alt="黄金"/>';
  }

  function iconPicker(fieldId, icon) {
    var selected = icon || 'g01';
    var html = '<div class="edit-row guild-icon-row"><label>军团图标</label><input type="hidden" id="' + fieldId + '" value="' + esc(selected) + '">' +
      '<div class="guild-icon-picker" role="group" aria-label="军团图标预设">';
    iconPresets.forEach(function (preset) {
      html += '<button type="button" class="guild-icon-option" data-icon="' + preset.id + '" aria-pressed="' + (selected === preset.id) + '" aria-label="' + preset.name + '徽章" title="' + preset.name + '" onclick="Game.Guild.selectIcon(\'' + fieldId + '\',\'' + preset.id + '\')">' +
        iconHtml(preset.id) + '<span>' + preset.name + '</span></button>';
    });
    return html + '</div><label class="guild-custom-label" for="' + fieldId + 'Custom">自定义字符</label>' +
      '<input id="' + fieldId + 'Custom" class="qty guild-custom-input" maxlength="4" placeholder="最多 4 字符" value="' + (presetFor(selected) ? '' : esc(selected)) + '" oninput="Game.Guild.customIcon(\'' + fieldId + '\')"></div>';
  }

  var Guild = {
    iconHtml: iconHtml,
    mine: null,
    list: [],
    loading: false,
    loaded: false,
    loadError: '',
    presenceLoading: false,
    presenceUpdatedAt: 0,
    currentTab: 'members',

    setTab: function (tab) {
      this.currentTab = tab;
      var tabs = ['chat', 'members', 'rankings', 'diplomacy', 'manage'];
      tabs.forEach(function (t) {
        var btn = document.getElementById('guildTabBtn_' + t);
        var panel = document.getElementById('guildTabPanel_' + t);
        if (btn) {
          if (t === tab) {
            btn.classList.add('active');
            try {
              if (typeof btn.scrollIntoView === 'function') {
                btn.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
              }
            } catch (_) {}
          } else {
            btn.classList.remove('active');
          }
        }
        if (panel) {
          panel.style.display = (t === tab) ? 'block' : 'none';
        }
      });
      if (tab === 'chat' && G.Chat && G.Chat.loadGuildHistory) {
        G.Chat.loadGuildHistory();
      }
    },

    presenceHtml: function (online) {
      var state = online === true ? 'online' : (online === false ? 'offline' : 'unknown');
      return '<span class="member-presence ' + state + '">' + ({ online: '● 在线', offline: '○ 离线', unknown: '○ 状态未知' })[state] + '</span>';
    },

    refreshPresence: function () {
      if (Core.route !== 'guild' || document.hidden || this.loading || this.presenceLoading || !this.mine || !this.mine.joined) return;
      var guildId = this.mine.id;
      this.presenceLoading = true;
      this.presenceUpdatedAt = Date.now();
      G.API.getMyGuild().then(function (data) {
        if (!Guild.mine || Guild.mine.id !== guildId) return;
        var current = data && data.joined && data.id === guildId;
        var members = current ? data.members || [] : [];
        (Guild.mine.members || []).forEach(function (m) {
          var fresh = members.find(function (item) { return item.playerId === m.playerId; });
          m.online = fresh ? fresh.online : null;
        });
        Guild.updatePresenceLabels();
      }).catch(function () {
        if (!Guild.mine || Guild.mine.id !== guildId) return;
        (Guild.mine.members || []).forEach(function (m) { m.online = null; });
        Guild.updatePresenceLabels();
      }).then(function () { Guild.presenceLoading = false; });
    },

    updatePresenceLabels: function () {
      if (Core.route !== 'guild') return;
      (this.mine.members || []).forEach(function (m) {
        var el = document.getElementById('guildPresence_' + m.playerId);
        if (el) el.innerHTML = Guild.presenceHtml(m.online);
      });
    },

    contact: function (playerId) {
      var member = (this.mine.members || []).find(function (m) { return m.playerId === playerId; });
      if (!member) return;
      G.go('mail');
      G.Mail.compose();
      var recipient = document.getElementById('mailTo');
      if (recipient) recipient.value = member.name;
    },

    load: function (force) {
      if (this.loading || (this.loaded && !force)) return;
      this.loading = true;
      this.loadError = '';
      Promise.all([G.API.getMyGuild(), G.API.getGuilds()]).then(function (data) {
        Guild.mine = data[0];
        Guild.list = data[1] || [];
        Guild.loaded = true;
        Guild.loading = false;
        Guild.presenceUpdatedAt = Date.now();
        Core.render();
      }).catch(function (err) {
        Guild.loaded = true;
        Guild.loading = false;
        Guild.loadError = err.message || '军团信息加载失败';
        G.toast(Guild.loadError);
        Core.render();
      });
    },

    create: function () {
      var cost = 10000;
      var currentGold = (Core.state && Core.state.resources && typeof Core.state.resources.gold === 'number') ? Core.state.resources.gold : null;
      var input = document.getElementById('guildName');
      var name = input ? input.value.trim() : '';
      if (!name) {
        G.toast('请输入军团名称');
        return;
      }
      if (currentGold !== null && currentGold < cost) {
        G.toast('黄金不足，创建军团需消耗 10,000 黄金 (当前拥有 ' + G.fmt(currentGold) + ')');
        return;
      }
      var icon = document.getElementById('guildCreateIcon');
      G.API.createGuild(name, icon ? icon.value : 'g01').then(function () {
        G.toast('军团创建成功，已消耗 10,000 黄金');
        Guild.reload();
      }).catch(function (err) { G.toast(err.message || '创建失败'); });
    },

    apply: function (guildId) {
      G.API.applyGuild(guildId).then(function (data) {
        G.toast(data.message || '申请已发送');
      }).catch(function (err) { G.toast(err.message || '申请失败'); });
    },

    review: function (id, approved) {
      G.API.reviewGuildApplication(id, approved).then(function (data) {
        G.toast(data.message || '已处理');
        Guild.reload();
      }).catch(function (err) { G.toast(err.message || '操作失败'); });
    },

    saveNotice: function () {
      var input = document.getElementById('guildNotice');
      G.API.updateGuildNotice(input ? input.value : '').then(function () {
        G.toast('公告已保存');
        Guild.reload();
      }).catch(function (err) { G.toast(err.message || '保存失败'); });
    },

    saveSettings: function () {
      var name = document.getElementById('guildCustomName');
      var icon = document.getElementById('guildIcon');
      G.API.updateGuildSettings(name ? name.value : '', icon ? icon.value : '').then(function () {
        G.toast('军团设置已保存');
        Guild.reload();
      }).catch(function (err) { G.toast(err.message || '保存失败'); });
    },

    selectIcon: function (fieldId, icon) {
      var field = document.getElementById(fieldId);
      if (!field || !presetFor(icon)) return;
      field.value = icon;
      var picker = field.parentNode;
      picker.querySelectorAll('.guild-icon-option').forEach(function (button) {
        button.setAttribute('aria-pressed', String(button.dataset.icon === icon));
      });
      picker.querySelector('.guild-custom-input').value = '';
    },

    customIcon: function (fieldId) {
      var field = document.getElementById(fieldId);
      var input = document.getElementById(fieldId + 'Custom');
      if (!field || !input) return;
      field.value = input.value.trim();
      field.parentNode.querySelectorAll('.guild-icon-option').forEach(function (button) {
        button.setAttribute('aria-pressed', 'false');
      });
    },

    updateRelation: function (guildId, status) {
      G.API.updateGuildRelation(guildId, status).then(function (data) {
        G.toast(data.message || '军团关系已更新');
        Guild.reload();
      }).catch(function (err) { G.toast(err.message || '关系设置失败'); });
    },

    updateRole: function (playerId, role) {
      G.API.updateGuildRole(playerId, role).then(function () {
        G.toast(role === 'admin' ? '已任命管理员' : '已取消管理员');
        Guild.reload();
      }).catch(function (err) { G.toast(err.message || '角色设置失败'); });
    },

    /** 转让团长后本人成为普通成员，可继续申请账号注销。 */
    transferLeadership: function (playerId) {
      if (this.transferring) return;
      var member = (this.mine && this.mine.members || []).find(function (m) { return m.playerId === playerId; });
      if (!member || !window.confirm('将团长转让给“' + member.name + '”？你将成为普通成员。')) return;
      this.transferring = true;
      G.API.transferGuildLeadership(playerId).then(function (data) {
        G.toast(data.message || '团长已转让');
        Guild.reload();
      }).catch(function (err) { G.toast(err.message || '转让失败，请刷新确认'); })
        .finally(function () { Guild.transferring = false; });
    },

    remove: function (playerId) {
      if (!window.confirm('确定将该成员移出军团吗？')) return;
      G.API.removeGuildMember(playerId).then(function (data) {
        G.toast(data.message || '已移出成员');
        Guild.reload();
      }).catch(function (err) { G.toast(err.message || '操作失败'); });
    },

    /** 团长与普通成员共用退出接口，二次确认需明确区分解散和退出。 */
    leave: function () {
      if (!this.mine || !this.mine.joined || document.getElementById('guildLeaveConfirm')) return;
      var isLeader = this.mine.isLeader;
      var trigger = document.activeElement;
      var mask = document.createElement('div');
      mask.id = 'guildLeaveConfirm';
      mask.className = 'modal-mask guild-confirm-mask';
      mask.innerHTML = '<section class="modal-card guild-confirm" role="dialog" aria-modal="true" aria-labelledby="guildConfirmTitle" aria-describedby="guildConfirmDesc">' +
        '<div class="modal-title" id="guildConfirmTitle">' + (isLeader ? '解散军团' : '退出军团') + '</div>' +
        '<div class="modal-body"><p class="guild-confirm-name">' + esc(this.mine.name || '当前军团') + '</p>' +
        '<p id="guildConfirmDesc">' + (isLeader ? '确定解散该军团吗？解散后不可恢复。' : '确定退出该军团吗？退出后需要重新申请加入。') + '</p>' +
        (isLeader && this.mine.members && this.mine.members.length > 1 ? '<p class="guild-confirm-note">请先移交团长或移出全部成员。</p>' : '') +
        '<p class="guild-confirm-error" role="alert" hidden></p></div>' +
        '<div class="modal-foot"><button type="button" class="guild-confirm-cancel">取消</button>' +
        '<button type="button" class="guild-confirm-submit">' + (isLeader ? '确认解散' : '确认退出') + '</button></div></section>';
      document.body.appendChild(mask);
      var cancel = mask.querySelector('.guild-confirm-cancel');
      var submit = mask.querySelector('.guild-confirm-submit');
      var error = mask.querySelector('.guild-confirm-error');
      var pending = false;
      function close() {
        if (pending) return;
        document.removeEventListener('keydown', onKey, true);
        mask.remove();
        if (trigger && trigger.isConnected) trigger.focus();
      }
      function onKey(event) {
        event.stopPropagation();
        if (event.key === 'Escape') { event.preventDefault(); close(); }
        if (event.key === 'Tab') {
          event.preventDefault();
          (document.activeElement === cancel ? submit : cancel).focus();
        }
      }
      document.addEventListener('keydown', onKey, true);
      mask.onclick = function (event) { if (event.target === mask) close(); };
      cancel.onclick = close;
      submit.onclick = function () {
        if (pending) return;
        pending = true;
        submit.disabled = true;
        submit.textContent = '处理中…';
        G.API.leaveGuild().then(function (data) {
          pending = false;
          close();
          G.toast(data.message || (isLeader ? '军团已解散' : '已退出军团'));
          Guild.reload();
        }).catch(function (err) {
          pending = false;
          submit.disabled = false;
          submit.textContent = isLeader ? '确认解散' : '确认退出';
          error.textContent = err.message || '操作失败';
          error.hidden = false;
        });
      };
      cancel.focus();
    },

    reload: function () {
      this.mine = null;
      this.loaded = false;
      this.load(true);
      if (G.API && G.API.getGameState) G.API.getGameState(true).then(G.API.applyState);
    },

    toggleEditNotice: function () {
      var d = document.getElementById('guildNoticeDisplay');
      var b = document.getElementById('guildNoticeEditBox');
      if (!d || !b) return;
      var isEditing = b.style.display !== 'none';
      b.style.display = isEditing ? 'none' : 'block';
      d.style.display = isEditing ? 'block' : 'none';
    },

    render: function (v) {
      if (!this.loaded && !this.loading) this.load();
      if (this.loading && !this.loaded) {
        v.innerHTML = '<div class="guild-wrap"><div class="panel" style="margin:10px 0;">正在读取军团档案...</div></div>';
        return;
      }
      if (this.loadError) {
        v.innerHTML = '<div class="guild-wrap"><div class="panel" style="margin:10px 0;">' + esc(this.loadError) + '<div class="btn-row" style="margin-top:8px"><button class="btn ok sm" onclick="Game.Guild.reload()">重新连接</button></div></div></div>';
        return;
      }
      var h = '<div class="guild-wrap">';
      h += this.mine && this.mine.joined ? this.renderMine() : this.renderRecruitment();
      h += '</div>';
      v.innerHTML = h;
      if (this.currentTab) {
        try {
          var activeBtn = document.getElementById('guildTabBtn_' + this.currentTab);
          if (activeBtn && typeof activeBtn.scrollIntoView === 'function') {
            activeBtn.scrollIntoView({ block: 'nearest', inline: 'nearest' });
          }
        } catch (_) {}
      }
      if (Date.now() - this.presenceUpdatedAt > 15000) this.refreshPresence();
    },

    renderRecruitment: function () {
      var s = Core.state || {};
      var p = s.player || {};

      var h = '';
      // 1. 顶部提示卡片
      h += '<div class="guild-header-card">';
      h += '<div class="guild-home-emblem-wrap"><span style="font-size:32px;">🪖</span></div>';
      h += '<div class="guild-header-info">';
      h += '<div class="guild-header-title">远征军团大厅</div>';
      h += '<div class="guild-header-meta" style="margin-top:4px;">';
      h += '<span>长官: <strong>' + esc(p.name || '指挥官') + '</strong></span>';
      h += '<span>当前身份: <strong class="text-muted">尚未加入任何军团</strong></span>';
      h += '<span>个人声望: <strong class="text-green">★' + G.fmt(p.prestige || 0) + '</strong></span>';
      h += '</div>';
      h += '</div>';
      h += '</div>';

      // 2. 招募公告
      h += '<div class="guild-notice-card">';
      h += '<div class="guild-notice-head"><div class="guild-notice-title">📢 战区同盟招募告示</div></div>';
      h += '<div class="guild-notice-body">战火纷飞，独木难支。长官可加入现有同盟军团携手征战，或组建番号招募全服战友开创伟业！</div>';
      h += '</div>';

      // 3. 【全服军团招募榜】
      h += '<div class="guild-members-card">';
      h += '<div class="guild-card-section-title"><span>【全服军团列表】</span><span style="font-size:12px;color:var(--muted)">共 ' + this.list.length + ' 支兵团</span></div>';
      if (!this.list.length) {
        h += '<div style="padding:16px 14px;color:var(--muted);font-size:12.5px;">战区内暂无其他军团，长官可组建第一支远征雄狮！</div>';
      }
      for (var i = 0; i < this.list.length; i++) {
        var g = this.list[i];
        h += '<div class="guild-member-row">';
        h += '<div class="guild-member-main">';
        h += '<span style="display:inline-flex;align-items:center;">' + iconHtml(g.icon) + '</span>';
        h += '<span class="guild-member-name" style="font-size:14px;">' + esc(g.name) + '</span>';
        h += '<span style="color:#27ae60;font-weight:700;">★' + G.fmt(g.prestige || 0) + '</span>';
        h += '<span class="guild-member-details">团长: ' + esc(g.leaderName) + ' · 规模: ' + g.members + '/' + g.maxMembers + '人</span>';
        if (g.notice) h += '<span style="font-size:11.5px;color:var(--muted);margin-left:4px;">「' + esc(g.notice) + '」</span>';
        h += '</div>';
        h += '<div class="guild-member-actions">';
        h += '<button type="button" class="guild-op-btn ok" onclick="Game.Guild.apply(' + g.id + ')">[申请加入]</button>';
        h += '</div>';
        h += '</div>';
      }
      h += '</div>';

      // 4. 【创建新军团】
      var currentGold = (Core.state && Core.state.resources && Core.state.resources.gold) || 0;
      h += '<div class="panel guild-create" style="margin-top:12px;">';
      h += '<div class="zone-head">创建新军团</div>';
      h += '<div class="edit-row"><label>军团名称</label><input id="guildName" class="qty" maxlength="16" placeholder="2-16 个字符"></div>';
      h += '<div class="edit-row" style="align-items:center;"><label>创建消耗</label><span style="display:inline-flex;align-items:center;gap:4px;font-weight:700;color:var(--gold,#d97706);">' + goldIconHtml() + ' 10,000 黄金</span><span style="font-size:12px;color:var(--muted);margin-left:8px;">(当前拥有: ' + G.fmt(currentGold) + ')</span></div>';
      h += iconPicker('guildCreateIcon', 'g01');
      h += '<div class="btn-row" style="margin-top:10px;"><button type="button" class="btn ok" onclick="Game.Guild.create()">创建军团 (消耗10,000黄金)</button></div>';
      h += '</div>';

      // 底部
      h += '<div class="guild-home-foot">';
      h += '<button type="button" class="guild-op-btn" onclick="Game.go(\'home\')">[返回主菜单]</button>';
      h += '</div>';

      return h;
    },

    renderMine: function () {
      var g = this.mine || {};
      var members = g.members || [];
      var relations = g.relations || [];
      var applications = g.applications || [];

      // 军团声望由军团成员声望总和组成
      var memberPrestigeSum = 0;
      for (var pi = 0; pi < members.length; pi++) {
        memberPrestigeSum += (members[pi].prestige || 0);
      }
      var totalPrestige = memberPrestigeSum;
      if (g.prestige != null && g.prestige > totalPrestige) {
        totalPrestige = g.prestige;
      }

      var roleName = g.role === 'leader' ? '团长' : (g.role === 'admin' ? '管理员' : '成员');
      var badgeClass = g.role === 'leader' ? 'leader' : (g.role === 'admin' ? 'admin' : 'member');
      var activeTab = this.currentTab || 'members';

      var h = '';

      // 1. 军团核心卡片：团徽、名称、声望（明确标注由成员总和组成）、团长与人数
      h += '<div class="guild-header-card">';
      h += '<div class="guild-home-emblem-wrap">' + iconHtml(g.icon) + '</div>';
      h += '<div class="guild-header-info">';
      h += '<div class="guild-header-title-row">';
      h += '<div class="guild-header-title">' + esc(g.name || '军团') + '</div>';
      h += '<span class="guild-wap-badge ' + badgeClass + '">' + roleName + '</span>';
      h += '</div>';
      h += '<div class="guild-header-prestige">军团声望: <strong>★' + G.fmt(totalPrestige) + '</strong><span class="guild-prestige-tip">（由全体成员声望总和组成）</span></div>';
      h += '<div class="guild-header-meta">';
      h += '<span>团长: <strong>' + esc(g.leaderName || '未知') + '</strong></span>';
      h += '<span>编制人数: <strong>' + members.length + '/' + (g.maxMembers || 30) + ' 人</strong></span>';
      h += '</div>';
      h += '</div>';
      h += '</div>';

      // 2. 军团公告卡片
      h += '<div class="guild-notice-card">';
      h += '<div class="guild-notice-head">';
      h += '<div class="guild-notice-title">📢 军团公告</div>';
      if (g.isManager) {
        h += '<button type="button" class="guild-op-btn" onclick="Game.Guild.toggleEditNotice()">[修改公告]</button>';
      }
      h += '</div>';
      h += '<div id="guildNoticeDisplay" class="guild-notice-body">' + esc(g.notice || '暂无军团公告。') + '</div>';
      if (g.isManager) {
        h += '<div id="guildNoticeEditBox" style="display:none;margin-top:8px;">';
        h += '<textarea id="guildNotice" class="qty" style="width:100%;min-height:56px;box-sizing:border-box;margin-bottom:6px;font:inherit;" maxlength="200">' + esc(g.notice || '') + '</textarea>';
        h += '<div style="display:flex;gap:8px;">';
        h += '<button type="button" class="btn ok sm" onclick="Game.Guild.saveNotice()">保存公告</button>';
        h += '<button type="button" class="btn sm" onclick="Game.Guild.toggleEditNotice()">取消</button>';
        h += '</div>';
        h += '</div>';
      }
      h += '</div>';

      // 3. 分栏导航栏 (清晰划分：军团频道 / 成员列表 / 全服排名 / 同盟外交 / 军团政务)
      var appBadge = (applications.length > 0 && (g.isLeader || g.role === 'admin')) ? '<span class="guild-tab-badge">' + applications.length + '</span>' : '';
      h += '<div class="guild-tabs-nav">';
      h += '<button type="button" id="guildTabBtn_chat" class="guild-tab-btn ' + (activeTab === 'chat' ? 'active' : '') + '" onclick="Game.Guild.setTab(\'chat\')">💬 军团频道</button>';
      h += '<button type="button" id="guildTabBtn_members" class="guild-tab-btn ' + (activeTab === 'members' ? 'active' : '') + '" onclick="Game.Guild.setTab(\'members\')">👥 军团成员 (' + members.length + ')</button>';
      h += '<button type="button" id="guildTabBtn_rankings" class="guild-tab-btn ' + (activeTab === 'rankings' ? 'active' : '') + '" onclick="Game.Guild.setTab(\'rankings\')">🏆 全服排名</button>';
      h += '<button type="button" id="guildTabBtn_diplomacy" class="guild-tab-btn ' + (activeTab === 'diplomacy' ? 'active' : '') + '" onclick="Game.Guild.setTab(\'diplomacy\')">⚔️ 同盟外交</button>';
      h += '<button type="button" id="guildTabBtn_manage" class="guild-tab-btn ' + (activeTab === 'manage' ? 'active' : '') + '" onclick="Game.Guild.setTab(\'manage\')">⚙️ 军团政务' + appBadge + '</button>';
      h += '</div>';

      // ================= TAB: 军团聊天频道 =================
      h += '<div id="guildTabPanel_chat" class="guild-tab-panel" style="' + (activeTab === 'chat' ? '' : 'display:none;') + '">';
      h += '<div class="chat-terminal" style="margin-bottom:12px;">';
      h += '<div class="chat-term-header">';
      h += '<div class="term-header-left">';
      h += '<span class="term-led"></span>';
      h += '<span class="term-title">COMM-LINK // 军团加密内频</span>';
      h += '</div>';
      h += '<div class="term-header-right">';
      h += '<span class="term-freq">FREQ: GUILD-' + (g.id || 0) + ' · ENCRYPTED</span>';
      h += '<span class="term-tag">ONLINE</span>';
      h += '</div>';
      h += '</div>';
      h += '<div class="chat-box" id="guildChatBox" style="min-height:220px;max-height:360px;">';
      var guildMsgs = (G.Chat && G.Chat.recentGuild) ? G.Chat.recentGuild(50) : [];
      for (var gi = 0; gi < guildMsgs.length; gi++) {
        if (G.Chat && G.Chat.renderGuildMessageHtml) {
          h += G.Chat.renderGuildMessageHtml(guildMsgs[gi], gi > 0 ? guildMsgs[gi - 1] : null);
        }
      }
      h += '</div>';
      var guildCd = (G.Chat && G.Chat.getGuildCooldown) ? G.Chat.getGuildCooldown() : 0;
      h += '<div class="chat-input-bar">';
      h += '<span class="chat-prompt">&gt;</span>';
      h += '<input class="chat-input" id="guildChatInput" type="text" maxlength="80" enterkeyhint="send" placeholder="输入军团内部战讯... (最多 80 字，Enter 发送)" autocomplete="off" onkeydown="if(event.key===\'Enter\'){Game.Main.sendGuildChat(\'guildChatInput\',\'guildChatSendBtn\');}"/>';
      h += '<button class="chat-send' + (guildCd > 0 ? ' disabled' : '') + '" id="guildChatSendBtn"' + (guildCd > 0 ? ' disabled' : '') + ' onclick="Game.Main.sendGuildChat(\'guildChatInput\',\'guildChatSendBtn\')">' + (guildCd > 0 ? guildCd + 's' : '发送') + '</button>';
      h += '</div>';
      h += '</div>';
      h += '</div>';

      // ================= TAB 1: 成员列表 =================
      h += '<div id="guildTabPanel_members" class="guild-tab-panel" style="' + (activeTab === 'members' ? '' : 'display:none;') + '">';
      h += '<div class="guild-members-card">';
      h += '<div class="guild-card-section-title">';
      h += '<span>【军团成员名录】</span>';
      h += '<span style="font-size:12px;color:var(--muted)">共 ' + members.length + ' 人</span>';
      h += '</div>';
      h += '<div class="guild-members-list">';
      for (var i = 0; i < members.length; i++) {
        var m = members[i];
        var mRole = m.role === 'leader' ? '团长' : (m.role === 'admin' ? '管理员' : '成员');
        var mBadge = m.role === 'leader' ? 'leader' : (m.role === 'admin' ? 'admin' : 'member');
        h += '<div class="guild-member-row">';
        h += '<div class="guild-member-main">';
        h += '<span id="guildPresence_' + m.playerId + '" class="guild-wap-presence">' + this.presenceHtml(m.online) + '</span>';
        h += '<span class="guild-member-name">' + esc(m.name) + '</span>';
        h += '<span class="guild-wap-badge ' + mBadge + '">' + mRole + '</span>';
        h += '<span class="guild-member-details">' + esc(m.cityName || '主城') + ' · ★' + G.fmt(m.prestige || 0) + '</span>';
        h += '</div>';

        h += '<div class="guild-member-actions">';
        h += '<button type="button" class="guild-op-btn" onclick="Game.Guild.contact(' + m.playerId + ')">[写信]</button>';
        if (g.isLeader && m.role !== 'leader') {
          h += '<button type="button" class="guild-op-btn" onclick="Game.Guild.updateRole(' + m.playerId + ',\'' + (m.role === 'admin' ? 'member' : 'admin') + '\')">' + (m.role === 'admin' ? '[取消管理]' : '[设为管理]') + '</button>';
          h += '<button type="button" class="guild-op-btn warn" onclick="Game.Guild.transferLeadership(' + m.playerId + ')">[转让团长]</button>';
        }
        if ((g.isLeader || g.role === 'admin') && m.role !== 'leader') {
          h += '<button type="button" class="guild-op-btn danger" onclick="Game.Guild.remove(' + m.playerId + ')">[移出]</button>';
        }
        h += '</div>';
        h += '</div>';
      }
      h += '</div>';
      h += '</div>';
      h += '</div>';

      // ================= TAB 2: 全服排名 =================
      h += '<div id="guildTabPanel_rankings" class="guild-tab-panel" style="' + (activeTab === 'rankings' ? '' : 'display:none;') + '">';
      h += '<div class="guild-members-card">';
      h += '<div class="guild-card-section-title">';
      h += '<span>【全服军团荣誉榜】</span>';
      h += '<span style="font-size:12px;color:var(--muted)">按军团总声望排序</span>';
      h += '</div>';

      // 构建并排序军团榜单
      var rankedList = (this.list || []).slice();
      var foundCurrent = false;
      for (var rk = 0; rk < rankedList.length; rk++) {
        if (rankedList[rk].id === g.id) {
          rankedList[rk] = Object.assign({}, rankedList[rk], {
            prestige: totalPrestige,
            members: members.length,
            icon: g.icon,
            name: g.name
          });
          foundCurrent = true;
          break;
        }
      }
      if (!foundCurrent && g.id) {
        rankedList.push({
          id: g.id,
          name: g.name,
          icon: g.icon,
          leaderName: g.leaderName,
          prestige: totalPrestige,
          members: members.length,
          maxMembers: g.maxMembers || 30
        });
      }
      rankedList.sort(function (a, b) {
        return (b.prestige || 0) - (a.prestige || 0);
      });

      if (!rankedList.length) {
        h += '<div style="padding:16px 14px;color:var(--muted);font-size:12.5px;">暂无军团排名数据。</div>';
      } else {
        h += '<div class="guild-members-list">';
        for (var ri = 0; ri < rankedList.length; ri++) {
          var rkGuild = rankedList[ri];
          var isSelf = (rkGuild.id === g.id);
          var rankNum = ri + 1;
          var badgeContent = rankNum === 1 ? '🥇' : (rankNum === 2 ? '🥈' : (rankNum === 3 ? '🥉' : rankNum));
          var badgeRankClass = rankNum <= 3 ? ('top-' + rankNum) : '';

          // 外交关系标签
          var relTag = '';
          if (isSelf) {
            relTag = '<span class="guild-dip-tag friendly">本团</span>';
          } else {
            var relItem = relations.find(function (r) { return r.guildId === rkGuild.id; });
            var relStatus = relItem ? relItem.status : 'neutral';
            if (relStatus === 'friendly') relTag = '<span class="guild-dip-tag friendly">盟友</span>';
            else if (relStatus === 'hostile') relTag = '<span class="guild-dip-tag hostile">敌对</span>';
            else relTag = '<span class="guild-dip-tag neutral">中立</span>';
          }

          h += '<div class="guild-member-row" style="' + (isSelf ? 'background:rgba(37,99,235,0.04);border-color:rgba(37,99,235,0.2);' : '') + '">';
          h += '<div class="guild-member-main">';
          h += '<span class="guild-rank-badge ' + badgeRankClass + '">' + badgeContent + '</span>';
          h += '<span style="display:inline-flex;align-items:center;">' + iconHtml(rkGuild.icon) + '</span>';
          h += '<span class="guild-member-name">' + esc(rkGuild.name) + (isSelf ? ' <span style="font-size:11px;color:var(--accent,#2563eb);font-weight:700;">(本军团)</span>' : '') + '</span>';
          h += relTag;
          h += '<span style="color:#27ae60;font-weight:700;">★' + G.fmt(rkGuild.prestige || 0) + '</span>';
          h += '<span class="guild-member-details">团长: ' + esc(rkGuild.leaderName || '未知') + ' · 规模: ' + (rkGuild.members || 1) + '/' + (rkGuild.maxMembers || 30) + '人</span>';
          h += '</div>';
          h += '</div>';
        }
        h += '</div>';
      }
      h += '</div>';
      h += '</div>';

      // ================= TAB 3: 同盟外交 =================
      h += '<div id="guildTabPanel_diplomacy" class="guild-tab-panel" style="' + (activeTab === 'diplomacy' ? '' : 'display:none;') + '">';
      h += '<div class="guild-notice-card" style="margin-bottom:10px;">';
      h += '<div class="guild-notice-head"><div class="guild-notice-title">⚔️ 战区外交立场说明</div></div>';
      h += '<div class="guild-notice-body">长官可在此界定全服各军团的外交立场：设为<strong>友好同盟</strong>或<strong>交战敌对</strong>。中立军团保持常规接触。</div>';
      h += '</div>';

      h += '<div class="guild-members-card">';
      h += '<div class="guild-card-section-title">';
      h += '<span>【全服其他军团外交关系】</span>';
      h += '<span style="font-size:12px;color:var(--muted)">' + (g.isManager ? '管理层可调整关系' : '仅团长/管理员可调整') + '</span>';
      h += '</div>';

      var otherGuilds = (this.list || []).filter(function (og) { return og.id !== g.id; });
      if (!otherGuilds.length) {
        h += '<div style="padding:16px 14px;color:var(--muted);font-size:12.5px;">战区内暂无其他军团。</div>';
      } else {
        h += '<div class="guild-members-list">';
        for (var di = 0; di < otherGuilds.length; di++) {
          var og = otherGuilds[di];
          var ogRel = relations.find(function (r) { return r.guildId === og.id; });
          var ogStatus = ogRel ? ogRel.status : 'neutral';

          var statusBadge = '';
          if (ogStatus === 'friendly') {
            statusBadge = '<span class="guild-dip-tag friendly">友好同盟</span>';
          } else if (ogStatus === 'hostile') {
            statusBadge = '<span class="guild-dip-tag hostile">敌对交战</span>';
          } else {
            statusBadge = '<span class="guild-dip-tag neutral">中立状态</span>';
          }

          h += '<div class="guild-member-row">';
          h += '<div class="guild-member-main">';
          h += '<span style="display:inline-flex;align-items:center;">' + iconHtml(og.icon) + '</span>';
          h += '<span class="guild-member-name">' + esc(og.name) + '</span>';
          h += statusBadge;
          h += '<span class="guild-member-details">团长: ' + esc(og.leaderName || '未知') + ' · ★' + G.fmt(og.prestige || 0) + ' · ' + (og.members || 1) + '人</span>';
          h += '</div>';

          if (g.isManager) {
            h += '<div class="guild-member-actions">';
            if (ogStatus !== 'friendly') {
              h += '<button type="button" class="guild-op-btn ok" onclick="Game.Guild.updateRelation(' + og.id + ',\'friendly\')">[设为友好]</button>';
            }
            if (ogStatus !== 'hostile') {
              h += '<button type="button" class="guild-op-btn danger" onclick="Game.Guild.updateRelation(' + og.id + ',\'hostile\')">[设为敌对]</button>';
            }
            if (ogStatus !== 'neutral') {
              h += '<button type="button" class="guild-op-btn" onclick="Game.Guild.updateRelation(' + og.id + ',\'neutral\')">[重置中立]</button>';
            }
            h += '</div>';
          }
          h += '</div>';
        }
        h += '</div>';
      }
      h += '</div>';
      h += '</div>';

      // ================= TAB 4: 军团政务 =================
      h += '<div id="guildTabPanel_manage" class="guild-tab-panel" style="' + (activeTab === 'manage' ? '' : 'display:none;') + '">';

      // 4.1 新兵入伍申请审批
      if (g.isManager) {
        h += '<div class="guild-members-card" style="margin-bottom:12px;">';
        h += '<div class="guild-card-section-title">';
        h += '<span>【新兵入伍申请审查】</span>';
        h += '<span style="font-size:12px;' + (applications.length ? 'color:#ef4444;font-weight:700;' : 'color:var(--muted);') + '">待审核 ' + applications.length + ' 份</span>';
        h += '</div>';
        if (!applications.length) {
          h += '<div style="padding:16px 14px;color:var(--muted);font-size:12.5px;">当前暂无待审核的入团申请。</div>';
        } else {
          h += '<div class="guild-members-list">';
          for (var ai = 0; ai < applications.length; ai++) {
            var app = applications[ai];
            h += '<div class="guild-member-row">';
            h += '<div class="guild-member-main">';
            h += '<b>' + esc(app.name) + '</b>';
            h += '<span style="color:#27ae60;font-weight:700;margin-left:4px;">★' + G.fmt(app.prestige || 0) + '</span>';
            h += '</div>';
            h += '<div class="guild-member-actions">';
            h += '<button type="button" class="guild-op-btn ok" onclick="Game.Guild.review(' + app.id + ',true)">[同意入伍]</button>';
            h += '<button type="button" class="guild-op-btn danger" onclick="Game.Guild.review(' + app.id + ',false)">[拒绝申请]</button>';
            h += '</div>';
            h += '</div>';
          }
          h += '</div>';
        }
        h += '</div>';

        // 4.2 军团设置（修改名称与团徽）
        h += '<div class="panel guild-create" style="margin-bottom:12px;">';
        h += '<div class="zone-head">军团番号与团徽修改</div>';
        h += '<div class="edit-row"><label>军团名称</label><input id="guildCustomName" class="qty" maxlength="16" value="' + esc(g.name || '') + '"></div>';
        h += iconPicker('guildIcon', g.icon || 'g01');
        h += '<div class="btn-row" style="margin-top:8px;"><button type="button" class="btn ok sm" onclick="Game.Guild.saveSettings()">保存名称与团徽</button></div>';
        h += '</div>';
      } else {
        h += '<div class="guild-notice-card" style="margin-bottom:12px;">';
        h += '<div class="guild-notice-head"><div class="guild-notice-title">⚙️ 军团政务权限说明</div></div>';
        h += '<div class="guild-notice-body">长官当前职位为普通成员。入伍申请审查、番号与团徽修改仅限团长与管理员操作。</div>';
        h += '</div>';
      }

      // 4.3 军团脱离与解散
      h += '<div class="guild-members-card">';
      h += '<div class="guild-card-section-title"><span>【军团解散与脱离】</span></div>';
      h += '<div style="padding:12px 14px;display:flex;align-items:center;justify-content:space-between;gap:8px;">';
      h += '<div>';
      h += '<div style="font-size:13px;font-weight:700;color:var(--ink,#1e293b);">' + (g.isLeader ? '解散军团' : '退出军团') + '</div>';
      h += '<div style="font-size:12px;color:var(--muted);margin-top:2px;">' + (g.isLeader ? '注销军团番号，不可撤销。需先移交团长或移出所有成员。' : '退出后脱离该军团编制，个人声望移出军团总额。') + '</div>';
      h += '</div>';
      h += '<button type="button" class="guild-op-btn danger" onclick="Game.Guild.leave()">' + (g.isLeader ? '[解散军团]' : '[退出军团]') + '</button>';
      h += '</div>';
      h += '</div>';

      h += '</div>'; // end guildTabPanel_manage

      // 底部操作栏：返回主菜单
      h += '<div class="guild-home-foot">';
      h += '<div style="font-size:12px;color:var(--muted)">Strategies of the Flaming Plains · 远征军团系统</div>';
      h += '<button type="button" class="guild-op-btn" onclick="Game.go(\'home\')">[返回主菜单]</button>';
      h += '</div>';

      return h;
    }
  };

  G.Guild = Guild;
  Core.views.guild = function (v) { Guild.render(v); };
  window.setInterval(function () { Guild.refreshPresence(); }, 15000);
})(window.Game);
