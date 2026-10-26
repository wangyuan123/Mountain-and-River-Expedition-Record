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
  function status(p) { return p.available === false ? '账号不可用' : p.blocked ? '已拉黑' : p.online ? '在线' : '离线'; }
  function name(p) { return p.displayName || p.username; }
  function peerButton(p) {
    var avatar = G.Chat.avatarSrc ? G.Chat.avatarSrc(p.avatar, false) : '';
    var statusClass = p.available === false ? 'offline' : p.blocked ? 'blocked' : p.online ? 'online' : 'offline';
    return '<button type="button" class="private-conversation friend-peer-row" onclick="Game.Friends.view(' + Number(p.id) + ')">' +
      '<div class="friend-avatar-wrap">' +
      '<img class="chat-avatar" src="' + esc(avatar) + '" alt="">' +
      '<span class="friend-status-dot ' + statusClass + '"></span>' +
      '</div>' +
      '<span class="private-conversation-body">' +
      '<span class="private-conversation-name">' +
      '<span class="friend-name-text">' + esc(name(p)) + '</span>' +
      '<span class="friend-status-pill ' + statusClass + '">' + status(p) + '</span>' +
      '</span>' +
      '<span class="private-conversation-preview">玩家名：' + esc(p.username) + ' · ID：' + Number(p.id) + '</span>' +
      '</span>' +
      '<span class="friend-peer-arrow" aria-hidden="true">›</span>' +
      '</button>';
  }

  var memAck = {};
  function getAckRequests() {
    try {
      if (typeof localStorage !== 'undefined' && localStorage.getItem) {
        var raw = localStorage.getItem('sf_ack_friends_' + owner());
        if (raw) return JSON.parse(raw);
      }
    } catch (e) {}
    return memAck[owner()] || {};
  }

  function saveAckRequests(map) {
    var k = owner();
    memAck[k] = map;
    try {
      if (typeof localStorage !== 'undefined' && localStorage.setItem) {
        localStorage.setItem('sf_ack_friends_' + k, JSON.stringify(map));
      }
    } catch (e) {}
  }

  function cleanAckRequests(currentRequests) {
    var ack = getAckRequests();
    var currentIds = {};
    if (Array.isArray(currentRequests)) {
      currentRequests.forEach(function (r) { if (!r.outgoing) currentIds[r.id] = true; });
    }
    var cleaned = {};
    for (var k in ack) {
      if (currentIds[k]) cleaned[k] = true;
    }
    saveAckRequests(cleaned);
  }

  function isFriendsPanelVisible() {
    var panel = node('chatFriendsPanel');
    return Boolean(panel && !panel.hidden && (!G.Core || G.Core.route === 'chat'));
  }

  function incomingRequestsCount() {
    if (!data || dataOwner !== owner() || !Array.isArray(data.requests)) return 0;
    var ack = getAckRequests();
    return data.requests.filter(function (r) { return !r.outgoing && !ack[r.id]; }).length;
  }

  function acknowledgeIncomingRequests() {
    if (!data || dataOwner !== owner() || !Array.isArray(data.requests)) return;
    var ack = getAckRequests();
    var changed = false;
    data.requests.forEach(function (r) {
      if (!r.outgoing && !ack[r.id]) {
        ack[r.id] = true;
        changed = true;
      }
    });
    if (changed) {
      saveAckRequests(ack);
      updateBadges();
    }
  }

  function updateBadges() {
    if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
    var tabBadge = node('friendRequestTabBadge');
    if (tabBadge) {
      var count = incomingRequestsCount();
      tabBadge.textContent = count > 99 ? '99+' : count;
      tabBadge.style.display = count > 0 ? '' : 'none';
    }
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
    var html = '<section class="friends-section" aria-label="好友申请">';
    html += '<div class="friends-section-header">';
    html += '<div class="friends-section-title-wrap"><span class="friends-section-icon">📬</span><span class="friends-section-title">好友申请</span></div>';
    html += '<span class="friends-section-badge' + (data.requests.length ? ' highlight' : '') + '">' + (data.requests.length ? data.requests.length + ' 条待处理' : '暂无待处理') + '</span>';
    html += '</div>';
    if (!data.requests.length) {
      html += '<div class="friend-empty-hint">暂无待处理申请</div>';
    } else {
      data.requests.forEach(function (r) {
        html += '<div class="friend-request-card">' +
          '<div class="friend-request-header">' + peerButton(r.peer) + '</div>' +
          '<div class="friend-request-footer">' +
          (r.outgoing
            ? '<span class="friend-request-wait-text">⏳ 等待对方同意</span><button type="button" class="btn sm friend-action-btn cancel" onclick="Game.Friends.request(' + Number(r.id) + ',false,this)">撤回申请</button>'
            : '<span class="friend-request-wait-text">申请添加战友</span><div class="friend-request-btn-group"><button type="button" class="btn sm friend-action-btn reject" onclick="Game.Friends.request(' + Number(r.id) + ',false,this)">拒绝</button><button type="button" class="btn sm ok friend-action-btn accept" onclick="Game.Friends.request(' + Number(r.id) + ',true,this)">同意</button></div>'
          ) +
          '</div></div>';
      });
    }
    html += '</section>';

    html += '<section class="friends-section" aria-label="战友分组">';
    data.groups.forEach(function (group) {
      var rows = data.contacts.filter(function (p) { return p.friend && !p.blocked && Number(p.groupId) === Number(group.id); });
      var isCustom = Boolean(Number(group.id));
      html += '<details class="friend-group-card" data-friend-group="' + Number(group.id) + '"' + (expanded[group.id] !== false ? ' open' : '') + '>';
      html += '<summary class="friend-group-summary">';
      html += '<div class="friend-group-summary-left">';
      html += '<span class="friend-group-chevron" aria-hidden="true">▾</span>';
      html += '<span class="friend-group-icon">👥</span>';
      html += '<span class="friend-group-name">' + esc(group.name) + '</span>';
      html += '<span class="friend-group-badge">(' + rows.length + ')</span>';
      html += '</div>';
      if (isCustom) {
        html += '<div class="friend-group-summary-actions">';
        html += '<button type="button" class="btn sm friend-group-btn" onclick="event.stopPropagation();Game.Friends.editGroup(' + Number(group.id) + ')">重命名分组</button>';
        html += '<button type="button" class="btn sm friend-group-btn danger" onclick="event.stopPropagation();Game.Friends.deleteGroup(' + Number(group.id) + ')">删除分组</button>';
        html += '</div>';
      }
      html += '</summary>';
      html += '<div class="friend-group-content">';
      html += rows.length ? rows.map(peerButton).join('') : '<div class="friend-empty-hint">暂无好友</div>';
      html += '</div>';
      html += '</details>';
    });

    var blocked = data.contacts.filter(function (p) { return p.blocked; });
    html += '<details class="friend-group-card blacklist-group" data-friend-group="blocked"' + (expanded.blocked ? ' open' : '') + '>';
    html += '<summary class="friend-group-summary">';
    html += '<div class="friend-group-summary-left">';
    html += '<span class="friend-group-chevron" aria-hidden="true">▾</span>';
    html += '<span class="friend-group-icon">🛡️</span>';
    html += '<span class="friend-group-name">黑名单</span>';
    html += '<span class="friend-group-badge">(' + blocked.length + ')</span>';
    html += '</div>';
    html += '</summary>';
    html += '<div class="friend-group-content">';
    html += blocked.length ? blocked.map(peerButton).join('') : '<div class="friend-empty-hint">黑名单为空</div>';
    html += '</div>';
    html += '</details>';
    html += '</section>';

    list.innerHTML = html;
    list._friendSnapshot = snapshot;
    list.scrollTop = top;
    if (isFriendsPanelVisible()) {
      acknowledgeIncomingRequests();
    } else {
      updateBadges();
    }
  }

  /** 弹窗复用项目原生样式，错误保留在原弹窗内，并限制键盘焦点在当前弹窗。 */
  function showModal(title, body, actions) {
    if (modal) modal.close();
    var trigger = document.activeElement;
    var mask = document.createElement('div');
    mask.id = 'friendModal';
    mask.className = 'modal-mask friend-modal-mask';
    mask.innerHTML = '<section class="modal-card friend-modal-card" role="dialog" aria-modal="true" aria-labelledby="friendModalTitle">' +
      '<div class="modal-title friend-modal-head" id="friendModalTitle">' +
      '<span class="friend-modal-title-text">' + esc(title) + '</span>' +
      '<button type="button" class="friend-modal-close-icon" id="friendModalHeaderClose" aria-label="关闭">✕</button>' +
      '</div>' +
      '<div class="modal-body friend-modal-body">' + body +
      '<div class="friend-modal-actions-wrap" id="friendModalActions"></div>' +
      '<p id="friendModalError" class="friend-modal-error" role="alert" hidden></p>' +
      '</div>' +
      '<div class="modal-foot friend-modal-foot">' +
      '<button type="button" class="btn friend-modal-foot-btn" id="friendModalClose">关闭</button>' +
      '</div>' +
      '</section>';
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
    var headClose = mask.querySelector('#friendModalHeaderClose');
    if (headClose) headClose.onclick = close;
    actions.forEach(function (action) {
      var button = document.createElement('button');
      button.type = 'button';
      var isPrimary = action.label === '发起聊天' || action.label === '添加好友' || action.label === '保存设置' || action.label === '查找玩家' || action.label.indexOf('确认') === 0;
      var isDanger = action.label === '删除好友' || action.label === '清空会话内容' || action.label === '拉黑';
      button.className = 'btn sm friend-modal-btn' + (isPrimary ? ' primary' : isDanger ? ' danger' : '');
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
    var focus = mask.querySelector('.modal-body input:not([disabled]), .modal-body select:not([disabled]), #friendModalActions button.primary:not([disabled]), #friendModalActions button:not([disabled]), #friendModalClose');
    if (focus) focus.focus();
    return mask;
  }

  function confirmAction(title, text, run) {
    showModal(title, '<div class="friend-confirm-msg">' + esc(text) + '</div>', [{ label: '确认' + title, run: function () {
      return run().then(function () { notify('操作成功'); return F.refresh(); });
    } }]);
  }

  var F = G.Friends = {
    acknowledge: function () {
      acknowledgeIncomingRequests();
    },
    unread: function () {
      return incomingRequestsCount();
    },
    render: function () {
      return '<div class="private-chat-panel friends-view-panel">' +
        '<div class="friends-toolbar">' +
        '<button type="button" class="btn sm friends-tb-btn primary" onclick="Game.Friends.find()"><span class="friends-tb-icon">➕</span><span>添加好友</span></button>' +
        '<button type="button" class="btn sm friends-tb-btn" onclick="Game.Friends.editGroup()"><span class="friends-tb-icon">📁</span><span>新建分组</span></button>' +
        '<button type="button" class="btn sm friends-tb-btn" onclick="Game.Friends.settings()"><span class="friends-tb-icon">⚙️</span><span>隐私设置</span></button>' +
        '<button type="button" class="btn sm friends-tb-btn refresh-btn" onclick="Game.Friends.refresh()"><span class="friends-tb-icon">🔄</span><span>刷新</span></button>' +
        '</div>' +
        '<div class="private-conversations friends-list-wrap" id="friendList" aria-live="polite">正在加载好友…</div>' +
        '</div>';
    },
    /** 请求代次和账号标识共同隔离慢响应，切换账号后不复用上个账号的好友资料。 */
    refresh: function () {
      if (!G.API || !G.API.friends) return Promise.resolve(false);
      var version = ++loadVersion;
      var account = owner();
      if (dataOwner !== account) {
        data = null;
        var el = node('friendList');
        if (el) {
          el.textContent = '正在加载好友…';
          el._friendSnapshot = null;
        }
      }
      return G.API.friends().then(function (result) {
        if (version !== loadVersion || account !== owner()) return false;
        data = result; dataOwner = account;
        cleanAckRequests(data.requests);
        paint();
        updateBadges();
        return true;
      }).catch(function (err) {
        if (version === loadVersion && account === owner()) {
          var el = node('friendList');
          if (el) {
            el.textContent = (err.message || '好友加载失败') + '，请点击刷新重试。';
            el._friendSnapshot = null;
          }
        }
        return false;
      });
    },
    find: function () {
      var body = '<div class="friend-modal-form">' +
        '<label for="friendSearchInput" class="friend-field-label">玩家名或指挥官 ID</label>' +
        '<div class="friend-input-wrap">' +
        '<span class="friend-input-icon">🔍</span>' +
        '<input id="friendSearchInput" class="friend-text-input" maxlength="50" autocomplete="off" placeholder="请输入完整玩家名或数字 ID">' +
        '</div>' +
        '<p class="friend-form-tip">输入完整玩家名或玩家 ID，查找后可查看资料并添加好友。</p>' +
        '</div>';
      showModal('添加好友', body, [{ label: '查找玩家', run: function (mask) {
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
      var avatar = G.Chat.avatarSrc ? G.Chat.avatarSrc(p.avatar, false) : '';
      var rankName = rank ? rank.name : (p.militaryRank || '列兵');
      var statusClass = !p.available ? 'offline' : p.blocked ? 'blocked' : p.online ? 'online' : 'offline';
      var body = '<div class="friend-profile-card">' +
        '<div class="friend-profile-hero">' +
        '<div class="friend-avatar-wrap lg">' +
        '<img class="chat-avatar" src="' + esc(avatar) + '" alt="">' +
        '<span class="friend-status-dot lg ' + statusClass + '"></span>' +
        '</div>' +
        '<div class="friend-profile-hero-info">' +
        '<div class="friend-profile-name-row">' +
        '<h3 class="friend-profile-name">' + esc(name(p)) + '</h3>' +
        '<span class="friend-status-pill ' + statusClass + '">' + status(p) + '</span>' +
        '</div>' +
        '<div class="friend-profile-sub">玩家名：' + esc(p.username) + ' · ID：' + Number(p.id) + '</div>' +
        '</div></div>' +
        '<div class="friend-profile-grid">' +
        '<div class="friend-profile-cell"><span class="cell-label">军衔</span><span class="cell-val">🎖️ ' + esc(rankName) + '</span></div>' +
        '<div class="friend-profile-cell"><span class="cell-label">等级</span><span class="cell-val">Lv. ' + Number(p.level || 1) + '</span></div>' +
        '<div class="friend-profile-cell"><span class="cell-label">声望</span><span class="cell-val">⭐ ' + Number(p.prestige || 0) + '</span></div>' +
        '<div class="friend-profile-cell"><span class="cell-label">主城</span><span class="cell-val">🏰 ' + esc(p.cityName || '暂无') + (p.cityX != null && p.cityY != null ? ' (' + Number(p.cityX) + ',' + Number(p.cityY) + ')' : (p.available ? ' · 未获得有效侦察坐标' : '')) + '</span></div>' +
        '</div>';
      var actions = [];
      // 只有服务端确认有效侦察报告后才会下发坐标，因此资料页不能绕过侦察定位。
      if (p.available && Number.isInteger(p.cityX) && Number.isInteger(p.cityY)) {
        actions.push({ label: '定位城市', run: function () { return function () { G.World.locateTarget(p.cityX, p.cityY); }; } });
      }
      if (p.friend) {
        var groups = data && dataOwner === owner() ? data.groups : [{ id: 0, name: '我的好友' }];
        body += '<div class="friend-profile-group-wrap">' +
          '<label for="friendGroupSelect" class="friend-field-label">所属分组</label>' +
          '<select id="friendGroupSelect" class="friend-select">' + groups.map(function (g) {
            return '<option value="' + Number(g.id) + '"' + (Number(p.groupId) === Number(g.id) ? ' selected' : '') + '>' + esc(g.name) + '</option>';
          }).join('') + '</select>' +
          '</div>';
        actions.push({ label: '保存分组', run: function (mask) {
          return G.API.friendMove(p.id, Number(mask.querySelector('#friendGroupSelect').value)).then(function () { notify('分组已保存'); return F.refresh(); });
        } });
        actions.push({ label: '删除好友', run: function () { return function () {
          confirmAction('删除好友', '确定删除“' + name(p) + '”吗？双方将解除好友关系，已有会话内容保留。', function () { return G.API.friendRemove(p.id).then(function () { return G.PrivateChat.refresh(); }); });
        }; } });
      } else {
        var waiting = p.requestState === 'OUTGOING' || p.requestState === 'INCOMING';
        if (p.requestState === 'INCOMING') body += '<div class="friend-profile-tip-box">对方已发来好友申请，请在好友申请列表中处理。</div>';
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
      body += '</div>';
      showModal('玩家资料', body, actions);
    },
    editGroup: function (id) {
      var group = data && dataOwner === owner() && data.groups.find(function (g) { return Number(g.id) === Number(id); });
      var body = '<div class="friend-modal-form">' +
        '<label for="friendGroupName" class="friend-field-label">分组名称</label>' +
        '<input id="friendGroupName" class="friend-text-input" maxlength="20" autocomplete="off" placeholder="请输入分组名称">' +
        '<p class="friend-form-tip">1 至 20 个字符，最多可创建 20 个自定义分组。</p>' +
        '</div>';
      var mask = showModal(id ? '重命名分组' : '新建分组', body, [{ label: '保存分组', run: function (dialog) {
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
        var body = '<div class="friend-modal-form">' +
          '<div class="friend-form-group">' +
          '<label for="friendRequestPolicy" class="friend-field-label">好友申请权限</label>' +
          '<select id="friendRequestPolicy" class="friend-select">' +
          [['APPROVAL', '需要同意申请（默认）'], ['ANYONE', '允许直接添加'], ['NONE', '拒绝所有好友申请']].map(function (item) {
            return '<option value="' + item[0] + '"' + (settings.friendRequestPolicy === item[0] ? ' selected' : '') + '>' + item[1] + '</option>';
          }).join('') + '</select>' +
          '</div>' +
          '<div class="friend-form-group">' +
          '<label class="friend-checkbox-card">' +
          '<input id="friendBlockStrangers" type="checkbox"' + (settings.blockStrangerMessages ? ' checked' : '') + '>' +
          '<span class="friend-checkbox-content">' +
          '<span class="friend-checkbox-title">屏蔽陌生人私信</span>' +
          '<span class="friend-checkbox-sub">开启后仅接收战友私聊，世界、军团和系统通联不受影响。</span>' +
          '</span>' +
          '</label>' +
          '</div>' +
          '</div>';
        showModal('隐私设置', body, [{ label: '保存设置', run: function (mask) {
          return G.API.friendSettings({ friendRequestPolicy: mask.querySelector('#friendRequestPolicy').value, blockStrangerMessages: mask.querySelector('#friendBlockStrangers').checked })
            .then(function () { notify('隐私设置已保存'); return F.refresh(); });
        } }]);
      });
    }
  };
})(window.Game);
