/* global window, document */
(function (G) {
  'use strict';
  var data = null;
  var dataOwner = '';
  var loadVersion = 0;
  var profileVersion = 0;
  var modal = null;
  var pendingRequests = {};
  function node(id) { return document.getElementById(id); }
  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function owner() {
    var player = G.Core.state && G.Core.state.player;
    return String(G.API.client && G.API.client.serverId || '') + ':' + String(player && player.id || '');
  }
  function notify(text) { if (G.toast) G.toast(text); }
  function fail(err) { notify(err.message || '操作失败，请重试'); }
  function status(p) { return !p.available ? '账号不可用' : p.blocked ? '已拉黑' : p.online ? '在线' : '离线'; }
  function name(p) { return p.displayName || p.username; }
  function peerButton(p) {
    var avatar = G.Chat.avatarSrc ? G.Chat.avatarSrc(p.avatar, false) : '';
    return '<button type="button" class="private-conversation" onclick="Game.Friends.view(' + Number(p.id) + ')">' +
      '<img class="chat-avatar" src="' + esc(avatar) + '" alt=""><span class="private-conversation-body"><span class="private-conversation-name">' + esc(name(p)) +
      '<span class="private-peer-status">' + status(p) + '</span></span><span class="private-conversation-preview">玩家名：' + esc(p.username) + ' · ID：' + Number(p.id) + '</span></span></button>';
  }

  /** 列表只重建变化内容，保留分组展开状态和滚动位置。 */
  function paint() {
    var list = node('friendList');
    if (!list || !data || dataOwner !== owner()) return;
    var snapshot = JSON.stringify(data);
    if (list._friendSnapshot === snapshot) return;
    var expanded = {};
    list.querySelectorAll('details[data-friend-group]').forEach(function (el) { expanded[el.getAttribute('data-friend-group')] = el.open; });
    var top = list.scrollTop;
    var html = '<section aria-label="好友申请"><h2>好友申请</h2>';
    if (!data.requests.length) html += '<p>暂无待处理申请</p>';
    data.requests.forEach(function (r) {
      html += '<div class="panel">' + peerButton(r.peer) + '<div class="btn-row">';
      if (!r.outgoing) html += '<button type="button" class="btn sm ok" onclick="Game.Friends.request(' + Number(r.id) + ',true,this)">同意</button>';
      html += '<button type="button" class="btn sm" onclick="Game.Friends.request(' + Number(r.id) + ',false,this)">' + (r.outgoing ? '撤回申请' : '拒绝') + '</button>' + (r.outgoing ? '<span>等待对方同意</span>' : '') + '</div></div>';
    });
    html += '</section>';
    data.groups.forEach(function (group) {
      var rows = data.contacts.filter(function (p) { return p.friend && !p.blocked && Number(p.groupId) === Number(group.id); });
      html += '<details data-friend-group="' + Number(group.id) + '"' + (expanded[group.id] !== false ? ' open' : '') + '><summary>' + esc(group.name) + ' (' + rows.length + ')</summary>';
      if (Number(group.id)) html += '<div class="btn-row"><button type="button" class="btn sm" onclick="Game.Friends.editGroup(' + Number(group.id) + ')">重命名分组</button><button type="button" class="btn sm" onclick="Game.Friends.deleteGroup(' + Number(group.id) + ')">删除分组</button></div>';
      html += rows.length ? rows.map(peerButton).join('') : '<p>暂无好友</p>';
      html += '</details>';
    });
    var blocked = data.contacts.filter(function (p) { return p.blocked; });
    html += '<details data-friend-group="blocked"' + (expanded.blocked ? ' open' : '') + '><summary>黑名单 (' + blocked.length + ')</summary>' + (blocked.length ? blocked.map(peerButton).join('') : '<p>黑名单为空</p>') + '</details>';
    list.innerHTML = html;
    list._friendSnapshot = snapshot;
    list.scrollTop = top;
  }

  /** 弹窗复用项目原生样式，错误保留在原弹窗内，并限制键盘焦点在当前弹窗。 */
  function showModal(title, body, actions) {
    if (modal) modal.close();
    var trigger = document.activeElement;
    var mask = document.createElement('div');
    mask.id = 'friendModal';
    mask.className = 'modal-mask';
    mask.innerHTML = '<section class="modal-card" role="dialog" aria-modal="true" aria-labelledby="friendModalTitle"><div class="modal-title" id="friendModalTitle">' + esc(title) +
      '</div><div class="modal-body">' + body + '<div class="btn-row" id="friendModalActions"></div><p id="friendModalError" role="alert" hidden></p></div><div class="modal-foot"><button type="button" class="btn" id="friendModalClose">关闭</button></div></section>';
    document.body.appendChild(mask);
    var busy = false;
    var context = { mask: mask, close: close };
    modal = context;
    function close() {
      if (busy) return;
      ++profileVersion;
      document.removeEventListener('keydown', onKey, true);
      mask.remove();
      if (modal === context) modal = null;
      if (trigger && trigger.isConnected) trigger.focus();
    }
    function onKey(event) {
      event.stopPropagation();
      if (event.key === 'Escape') { event.preventDefault(); close(); }
      if (event.key !== 'Tab') return;
      var controls = Array.from(mask.querySelectorAll('button:not([disabled]),input:not([disabled]),select:not([disabled])'));
      if (!controls.length) { event.preventDefault(); return; }
      var index = controls.indexOf(document.activeElement);
      if (event.shiftKey && index <= 0) { event.preventDefault(); controls[controls.length - 1].focus(); }
      else if (!event.shiftKey && (index < 0 || index === controls.length - 1)) { event.preventDefault(); controls[0].focus(); }
    }
    document.addEventListener('keydown', onKey, true);
    mask.onclick = function (event) { if (event.target === mask) close(); };
    mask.querySelector('#friendModalClose').onclick = close;
    actions.forEach(function (action) {
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'btn sm';
      button.textContent = action.label;
      button.disabled = !!action.disabled;
      button.onclick = function () {
        if (busy || action.disabled) return;
        busy = true;
        var controls = Array.from(mask.querySelectorAll('button,input,select'));
        var disabled = controls.map(function (el) { return el.disabled; });
        controls.forEach(function (el) { el.disabled = true; });
        var error = mask.querySelector('#friendModalError');
        error.hidden = true;
        // 完成请求后先解锁，再执行可能打开下一弹窗的回调。
        return Promise.resolve().then(function () { return action.run(mask); }).then(function (next) {
          busy = false;
          if (typeof next === 'function') { close(); return next(); }
          if (next !== false) close();
        }).catch(function (err) {
          error.textContent = err.message || '操作失败，请重试';
          error.hidden = false;
        }).finally(function () {
          busy = false;
          controls.forEach(function (el, i) { el.disabled = disabled[i]; });
        });
      };
      mask.querySelector('#friendModalActions').appendChild(button);
    });
    var focus = mask.querySelector('input,select,button:not([disabled])');
    if (focus) focus.focus();
    return mask;
  }

  function confirmAction(title, text, run) {
    showModal(title, '<p>' + esc(text) + '</p>', [{ label: '确认' + title, run: function () {
      return run().then(function () { notify('操作成功'); return F.refresh(); });
    } }]);
  }

  var F = G.Friends = {
    render: function () {
      return '<div class="private-chat-panel"><div class="btn-row"><button type="button" class="btn sm" onclick="Game.Friends.find()">添加好友</button><button type="button" class="btn sm" onclick="Game.Friends.editGroup()">新建分组</button><button type="button" class="btn sm" onclick="Game.Friends.settings()">隐私设置</button><button type="button" class="btn sm" onclick="Game.Friends.refresh()">刷新</button></div><div class="private-conversations" id="friendList" aria-live="polite">正在加载好友…</div></div>';
    },
    /** 请求代次和账号标识共同隔离慢响应，切换账号后不复用上个账号的好友资料。 */
    refresh: function () {
      if (!node('friendList')) return Promise.resolve();
      var version = ++loadVersion;
      var account = owner();
      if (dataOwner !== account) {
        data = null;
        node('friendList').textContent = '正在加载好友…';
        node('friendList')._friendSnapshot = null;
      }
      return G.API.friends().then(function (result) {
        if (version !== loadVersion || account !== owner()) return false;
        data = result; dataOwner = account; paint();
        return true;
      }).catch(function (err) {
        if (version === loadVersion && account === owner() && node('friendList')) {
          node('friendList').textContent = (err.message || '好友加载失败') + '，请点击刷新重试。';
          node('friendList')._friendSnapshot = null;
        }
        return false;
      });
    },
    find: function () {
      showModal('添加好友', '<label for="friendSearchInput">玩家名或 ID</label><input id="friendSearchInput" maxlength="50" autocomplete="off"><p>输入完整玩家名或玩家 ID，查找后可查看资料并添加好友。</p>', [{ label: '查找玩家', run: function (mask) {
        var query = mask.querySelector('#friendSearchInput').value.trim();
        if (!query) throw new Error('请输入玩家名或 ID');
        return G.API.friendSearch(query).then(function (p) { return function () { F.showProfile(p); }; });
      } }]);
    },
    view: function (id) {
      var version = ++profileVersion;
      var account = owner();
      return Promise.all([G.API.friendProfile(id), G.API.friends()]).then(function (result) {
        if (version !== profileVersion || account !== owner()) return;
        data = result[1]; dataOwner = account; paint();
        F.showProfile(result[0]);
      }).catch(fail);
    },
    showProfile: function (p) {
      var rank = G.DATA && G.DATA.militaryRanks && G.DATA.militaryRanks[(p.militaryRank || 1) - 1];
      var body = '<p><b>' + esc(name(p)) + '</b> · ' + status(p) + '</p><p>玩家名：' + esc(p.username) + '</p><p>ID：' + Number(p.id) + '</p><p>等级：' + Number(p.level || 1) + ' · 军衔：' + esc(rank ? rank.name : p.militaryRank) + '</p><p>声望：' + Number(p.prestige || 0) + '</p><p>城市：' + esc(p.cityName || '暂无') + '</p>';
      var actions = [];
      if (p.friend) {
        var groups = data && dataOwner === owner() ? data.groups : [{ id: 0, name: '我的好友' }];
        body += '<label for="friendGroupSelect">好友分组</label><select id="friendGroupSelect">' + groups.map(function (g) {
          return '<option value="' + Number(g.id) + '"' + (Number(p.groupId) === Number(g.id) ? ' selected' : '') + '>' + esc(g.name) + '</option>';
        }).join('') + '</select>';
        actions.push({ label: '保存分组', run: function (mask) {
          return G.API.friendMove(p.id, Number(mask.querySelector('#friendGroupSelect').value)).then(function () { notify('分组已保存'); return F.refresh(); });
        } });
        actions.push({ label: '删除好友', run: function () { return function () {
          confirmAction('删除好友', '确定删除“' + name(p) + '”吗？双方将解除好友关系，已有会话内容保留。', function () { return G.API.friendRemove(p.id).then(function () { return G.PrivateChat.refresh(); }); });
        }; } });
      } else {
        var waiting = p.requestState === 'OUTGOING' || p.requestState === 'INCOMING';
        if (p.requestState === 'INCOMING') body += '<p>对方已发来好友申请，请在好友申请列表中处理。</p>';
        actions.push({ label: waiting ? (p.requestState === 'OUTGOING' ? '已发送申请' : '待处理申请') : '添加好友', disabled: waiting || p.blocked || !p.available, run: function () {
          return G.API.friendAdd(p.id).then(function (result) {
            notify(result.pending ? '好友申请已发送，等待对方同意' : '已添加好友');
            return F.refresh().then(function () { return function () { return F.view(p.id); }; });
          });
        } });
      }
      actions.push({ label: '发起聊天', disabled: p.blocked || !p.available, run: function () { return function () { return G.PrivateChat.start(p.username, p.id); }; } });
      actions.push({ label: '清空会话内容', run: function () { return function () {
        confirmAction('清空会话', '确定清空与“' + name(p) + '”的会话内容吗？只清空自己可见的历史，清空后无法恢复。', function () {
          return G.API.privateChatClear(p.id).then(function () { return G.PrivateChat.cleared(p.id); });
        });
      }; } });
      actions.push({ label: p.blocked ? '解除拉黑' : '拉黑', run: function () { return function () {
        confirmAction(p.blocked ? '解除拉黑' : '拉黑', p.blocked ? '解除对“' + name(p) + '”的拉黑？' : '拉黑“' + name(p) + '”后，双方将无法发送私聊，待处理的好友申请也会取消。', function () {
          return G.API.friendBlock(p.id, !p.blocked).then(function () { return G.PrivateChat.refresh(); });
        });
      }; } });
      showModal('玩家资料', body, actions);
    },
    editGroup: function (id) {
      var group = data && dataOwner === owner() && data.groups.find(function (g) { return Number(g.id) === Number(id); });
      var mask = showModal(id ? '重命名分组' : '新建分组', '<label for="friendGroupName">分组名称</label><input id="friendGroupName" maxlength="20" autocomplete="off"><p>1 至 20 个字符，最多 20 个自定义分组。</p>', [{ label: '保存分组', run: function (dialog) {
        var value = dialog.querySelector('#friendGroupName').value.trim();
        if (!value) throw new Error('请输入分组名称');
        return G.API.friendGroupSave(id, value).then(function () { notify('分组已保存'); return F.refresh(); });
      } }]);
      if (group) mask.querySelector('#friendGroupName').value = group.name;
    },
    deleteGroup: function (id) {
      confirmAction('删除分组', '删除分组后，其中的好友将移回“我的好友”。', function () { return G.API.friendGroupDelete(id); });
    },
    request: function (id, accept, button) {
      if (pendingRequests[id]) return Promise.resolve();
      pendingRequests[id] = true;
      button.disabled = true;
      return (accept ? G.API.friendRequestAccept(id) : G.API.friendRequestDismiss(id)).then(function () {
        notify(accept ? '已同意好友申请' : '申请已处理');
        return F.refresh();
      }).catch(fail).finally(function () { delete pendingRequests[id]; button.disabled = false; });
    },
    settings: function () {
      return F.refresh().then(function (loaded) {
        if (!loaded || !data || dataOwner !== owner()) return;
        var settings = data.settings;
        var body = '<label for="friendRequestPolicy">好友申请权限</label><select id="friendRequestPolicy">' +
          [['APPROVAL', '需要同意申请'], ['ANYONE', '允许直接添加'], ['NONE', '拒绝好友申请']].map(function (item) {
            return '<option value="' + item[0] + '"' + (settings.friendRequestPolicy === item[0] ? ' selected' : '') + '>' + item[1] + '</option>';
          }).join('') + '</select><p><label><input id="friendBlockStrangers" type="checkbox"' + (settings.blockStrangerMessages ? ' checked' : '') + '>屏蔽陌生人私信</label></p><p>开启后仅接收好友私信，世界、军团和系统消息不受影响。设置按账号保存。</p>';
        showModal('隐私设置', body, [{ label: '保存设置', run: function (mask) {
          return G.API.friendSettings({ friendRequestPolicy: mask.querySelector('#friendRequestPolicy').value, blockStrangerMessages: mask.querySelector('#friendBlockStrangers').checked })
            .then(function () { notify('隐私设置已保存'); return F.refresh(); });
        } }]);
      });
    }
  };
})(window.Game);
