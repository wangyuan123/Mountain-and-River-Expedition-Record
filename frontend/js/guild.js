/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';
  var Core = G.Core;

  function esc(value) { return G.escapeHtml(String(value == null ? '' : value)); }

  var Guild = {
    mine: null,
    list: [],
    loading: false,
    loaded: false,
    loadError: '',
    presenceLoading: false,
    presenceUpdatedAt: 0,

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
      var input = document.getElementById('guildName');
      var name = input ? input.value.trim() : '';
      G.API.createGuild(name).then(function () {
        G.toast('军团创建成功');
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

    render: function (v) {
      if (!this.loaded && !this.loading) this.load();
      var h = '<div class="title">- 军团指挥部 -</div>';
      h += '<div class="desc">集结盟友、审核成员申请，并以全体成员声望争夺军团排名。</div>';
      if (this.loading && !this.loaded) { v.innerHTML = h + '<div class="panel">正在加载军团信息...</div>'; return; }
      if (this.loadError) {
        v.innerHTML = h + '<div class="panel">' + esc(this.loadError) + '<div class="btn-row"><button class="btn ok" onclick="Game.Guild.reload()">重新加载</button></div></div>';
        return;
      }
      h += this.mine && this.mine.joined ? this.renderMine() : this.renderRecruitment();
      h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
      v.innerHTML = h;
      if (Date.now() - this.presenceUpdatedAt > 15000) this.refreshPresence();
    },

    renderRecruitment: function () {
      var h = '<div class="panel guild-create"><div class="zone-head">创建军团</div>';
      h += '<div class="edit-row"><label>军团名称</label><input id="guildName" class="qty" maxlength="16" placeholder="2-16 个字符"></div>';
      h += '<div class="btn-row"><button class="btn ok" onclick="Game.Guild.create()">创建军团</button></div></div>';
      h += '<div class="zone-head">=== 可申请军团 ===</div><div class="guild-list">';
      if (!this.list.length) h += '<div class="panel">暂无军团，成为第一位团长吧。</div>';
      for (var i = 0; i < this.list.length; i++) {
        var g = this.list[i];
        h += '<div class="guild-card"><div class="guild-card-head"><b>' + esc(g.icon || '⚑') + ' ' + esc(g.name) + '</b><span>★' + G.fmt(g.prestige || 0) + '</span></div>';
        h += '<div class="guild-muted">团长：' + esc(g.leaderName) + ' · ' + g.members + '/' + g.maxMembers + ' 人</div>';
        h += '<div class="guild-notice">' + esc(g.notice || '暂无公告') + '</div>';
        h += '<button class="tcard-btn tcard-btn-ok" onclick="Game.Guild.apply(' + g.id + ')">申请加入</button></div>';
      }
      return h + '</div>';
    },

    renderMine: function () {
      var g = this.mine;
      var h = '<div class="panel guild-overview"><div class="guild-card-head"><b>' + esc(g.icon || '⚑') + ' ' + esc(g.name) + '</b><span>★' + G.fmt(g.prestige || 0) + '</span></div>';
      h += '<div class="guild-muted">团长：' + esc(g.leaderName) + ' · 成员 ' + ((g.members && g.members.length) || 0) + '/' + g.maxMembers + '</div>';
      if (g.isManager) {
        h += '<div class="edit-row"><label>军团名称</label><input id="guildCustomName" class="qty" maxlength="16" value="' + esc(g.name || '') + '"></div>';
        h += '<div class="edit-row"><label>军团图标</label><input id="guildIcon" class="qty" maxlength="4" value="' + esc(g.icon || '⚑') + '"></div>';
        h += '<div class="edit-row"><label>军团公告</label><input id="guildNotice" class="qty" maxlength="200" value="' + esc(g.notice || '') + '"></div>';
        h += '<div class="btn-row"><button class="btn ok sm" onclick="Game.Guild.saveSettings()">保存名称/图标</button><button class="btn ok sm" onclick="Game.Guild.saveNotice()">保存公告</button></div>';
      } else h += '<div class="guild-notice">' + esc(g.notice || '暂无公告') + '</div>';
      h += '</div><div class="zone-head">=== 军团成员 ===</div><div class="desc">在线状态自动更新；可通过写信联系成员或约定上线时间。</div><div class="guild-list">';
      var members = g.members || [];
      for (var i = 0; i < members.length; i++) {
        var m = members[i];
        var role = m.role === 'leader' ? '团长' : (m.role === 'admin' ? '管理员' : '成员');
        h += '<div class="guild-member guild-member-presence"><div class="guild-member-info"><b>' + esc(m.name) + '</b> <span class="guild-role">' + role + '</span> <span id="guildPresence_' + m.playerId + '">' + this.presenceHtml(m.online) + '</span><div class="guild-muted">' + esc(m.cityName || '新城市') + ' · ★' + G.fmt(m.prestige || 0) + '</div></div>';
        h += '<button class="tcard-btn" onclick="Game.Guild.contact(' + m.playerId + ')">写信</button>';
        if (g.isLeader && m.role !== 'leader') h += '<button class="tcard-btn tcard-btn-ok" onclick="Game.Guild.updateRole(' + m.playerId + ',\'' + (m.role === 'admin' ? 'member' : 'admin') + '\')">' + (m.role === 'admin' ? '取消管理员' : '任命管理员') + '</button>';
        if (g.isLeader && m.role !== 'leader') h += ' <button class="tcard-btn tcard-btn-warn" onclick="Game.Guild.transferLeadership(' + m.playerId + ')">转让团长</button>';
        if ((g.isLeader || g.role === 'admin') && m.role !== 'leader') h += ' <button class="tcard-btn tcard-btn-warn" onclick="Game.Guild.remove(' + m.playerId + ')">移出</button>';
        h += '</div>';
      }
      h += '</div>';
      h += '<div class="zone-head">=== 军团外交 ===</div><div class="desc">敌对军团成员可直接征服、掠夺；友好军团成员不能宣战或交战。</div><div class="guild-list">';
      var relations = g.relations || [];
      var relationByGuild = {};
      for (var ri = 0; ri < relations.length; ri++) relationByGuild[relations[ri].guildId] = relations[ri].status;
      var candidates = this.list || [];
      if (!candidates.length) h += '<div class="panel">暂无其他军团可设置关系。</div>';
      for (var gi = 0; gi < candidates.length; gi++) {
        var other = candidates[gi];
        if (other.id === g.id) continue;
        var status = relationByGuild[other.id] || 'neutral';
        var statusText = status === 'hostile' ? '敌对' : (status === 'friendly' ? '友好' : '中立');
        h += '<div class="guild-member"><div><b>' + esc(other.icon || '⚑') + ' ' + esc(other.name) + '</b><div class="guild-muted">当前关系：' + statusText + '</div></div>';
        if (g.isManager) {
          h += '<div><button class="tcard-btn tcard-btn-warn" onclick="Game.Guild.updateRelation(' + other.id + ',\'hostile\')">设为敌对</button> <button class="tcard-btn tcard-btn-ok" onclick="Game.Guild.updateRelation(' + other.id + ',\'friendly\')">设为友好</button> <button class="tcard-btn" onclick="Game.Guild.updateRelation(' + other.id + ',\'neutral\')">设为中立</button></div>';
        }
        h += '</div>';
      }
      h += '</div>';
      if ((g.isLeader || g.role === 'admin') && g.applications && g.applications.length) {
        h += '<div class="zone-head">=== 入团申请 ===</div><div class="guild-list">';
        for (var j = 0; j < g.applications.length; j++) {
          var a = g.applications[j];
          h += '<div class="guild-member"><div><b>' + esc(a.name) + '</b><div class="guild-muted">★' + G.fmt(a.prestige || 0) + '</div></div>';
          h += '<div><button class="tcard-btn tcard-btn-ok" onclick="Game.Guild.review(' + a.id + ',true)">同意</button> <button class="tcard-btn" onclick="Game.Guild.review(' + a.id + ',false)">拒绝</button></div></div>';
        }
        h += '</div>';
      }
      h += '<div class="btn-row"><button class="btn warn" onclick="Game.Guild.leave()">' + (g.isLeader ? '解散军团' : '退出军团') + '</button></div>';
      return h;
    }
  };

  G.Guild = Guild;
  Core.views.guild = function (v) { Guild.render(v); };
  window.setInterval(function () { Guild.refreshPresence(); }, 15000);
})(window.Game);
