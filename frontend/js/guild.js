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

    updateRole: function (playerId, role) {
      G.API.updateGuildRole(playerId, role).then(function () {
        G.toast(role === 'admin' ? '已任命管理员' : '已取消管理员');
        Guild.reload();
      }).catch(function (err) { G.toast(err.message || '角色设置失败'); });
    },

    remove: function (playerId) {
      if (!window.confirm('确定将该成员移出军团吗？')) return;
      G.API.removeGuildMember(playerId).then(function (data) {
        G.toast(data.message || '已移出成员');
        Guild.reload();
      }).catch(function (err) { G.toast(err.message || '操作失败'); });
    },

    leave: function () {
      if (!window.confirm('确定退出当前军团吗？')) return;
      G.API.leaveGuild().then(function (data) {
        G.toast(data.message || '已退出军团');
        Guild.reload();
      }).catch(function (err) { G.toast(err.message || '退出失败'); });
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
        if ((g.isLeader || g.role === 'admin') && m.role !== 'leader') h += ' <button class="tcard-btn tcard-btn-warn" onclick="Game.Guild.remove(' + m.playerId + ')">移出</button>';
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
