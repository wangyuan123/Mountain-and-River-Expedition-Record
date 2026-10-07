/* global window, document */
(function (G) {
  'use strict';
  var peer = null;
  var messages = [];
  var dialog = false;
  var statusTimer = null;
  var drafts = {};
  var generation = 0;
  var sending = false;
  var nextSendAt = 0;
  var unreadNum = 0;
  function node(id) { return document.getElementById(id); }
  function escape(text) {
    return String(text == null ? '' : text).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function error(err) { if (G.toast) G.toast(err.message || '私聊加载失败，请重试'); }
  function visible() {
    return dialog && P.active && G.Core.route === 'chat' && node('chatMessagesPanel') && !node('chatMessagesPanel').hidden;
  }
  /** 只确认当前可见会话的消息，隐藏频道和论坛中的消息保持未读。 */
  function markRead() {
    if (!peer || !messages.length || !visible()) return Promise.resolve();
    return G.API.privateChatRead(peer.id, messages[messages.length - 1].id);
  }
  function statusText(target) {
    if (target.available === false) return '账号不可用';
    return target.online ? '在线' : '离线';
  }
  function paintTitle() {
    var title = node('privateChatTitle');
    var text = peer ? peer.username + ' · ' + statusText(peer) : '';
    if (title && title.textContent !== text) title.textContent = text;
    if (node('privateChatInput')) node('privateChatInput').disabled = !peer || peer.available === false;
    if (node('privateChatSend')) node('privateChatSend').disabled = sending || !peer || peer.available === false;
  }
  function panels() {
    if (node('privateChatListPanel')) node('privateChatListPanel').hidden = dialog;
    if (node('privateChatDialogPanel')) node('privateChatDialogPanel').hidden = !dialog;
    paintTitle();
  }
  function pollStatus() {
    if (!statusTimer && typeof setInterval === 'function') {
      statusTimer = setInterval(function () {
        if (P.active && G.Core.route === 'chat' && node('chatMessagesPanel') && !node('chatMessagesPanel').hidden) P.refresh();
      }, 15000);
    }
  }
  function paint() {
    var box = node('privateChatBox');
    if (box) {
      var html = messages.map(function (m, i) { return G.Chat.renderMessageHtml(m, i ? messages[i - 1] : null); }).join('');
      // 15 秒轮询可能返回相同历史；保留消息节点、头像与文本选区，避免面板闪动。
      if (box._privateChatHtml === html) return;
      var atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
      box.innerHTML = html;
      box._privateChatHtml = html;
      if (atBottom) box.scrollTop = box.scrollHeight;
    }
  }
  function paintConversations(rows) {
    var total = rows.reduce(function (sum, c) { return sum + Math.max(0, Number(c.unread) || 0); }, 0);
    // 未读缓存独立于私聊 DOM，其他页面也能显示聊天导航角标。
    if (total !== unreadNum) {
      unreadNum = total;
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
    }
    if (node('privateChatUnread')) node('privateChatUnread').textContent = total ? ' (' + total + ')' : '';
    var list = node('privateChatConversations');
    if (!list) return;
    rows.forEach(function (c) {
      if (peer && String(peer.id) === String(c.peer.id)) { Object.assign(peer, c.peer); paintTitle(); }
    });
    // 列表未变化也不替换按钮节点，保留键盘焦点和列表阅读位置。
    var snapshot = JSON.stringify(rows);
    if (list._privateChatRows === snapshot) return;
    list._privateChatRows = snapshot;
    list.innerHTML = '';
    rows.forEach(function (c) {
      var button = document.createElement('button');
      var row = document.createElement('div');
      row.className = 'private-conversation-row';
      var remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'private-conversation-delete';
      remove.textContent = '删除';
      remove.onclick = function () { return P.remove(c.peer.id); };
      button.type = 'button';
      button.className = 'private-conversation';
      button.innerHTML = '<img class="chat-avatar" src="' + escape(G.Chat.avatarSrc ? G.Chat.avatarSrc(c.peer.avatar, false) : '') + '" alt=""><span class="private-conversation-body"><span class="private-conversation-name">' + escape(c.peer.username) + '<span class="private-peer-status">' + statusText(c.peer) + '</span></span><span class="private-conversation-preview">' + escape(c.lastMessage ? c.lastMessage.content : '还没有消息，点击开始聊天') + '</span></span>' + (c.unread ? '<span class="private-unread-badge">' + Number(c.unread) + '</span>' : '');
      var dragged = false;
      var startX = 0;
      var startY = 0;
      button.onpointerdown = function (event) { startX = event.clientX; startY = event.clientY; dragged = false; };
      button.onpointerup = function (event) {
        var dx = event.clientX - startX;
        if (Math.abs(dx) > 35 && Math.abs(dx) > Math.abs(event.clientY - startY)) {
          dragged = true;
          row.classList.toggle('swiped', dx < 0);
        }
      };
      button.onclick = function () { if (dragged) { dragged = false; return; } return P.open(c.peer); };
      row.appendChild(remove);
      row.appendChild(button);
      list.appendChild(row);
    });
    if (!rows.length) list.textContent = '还没有私聊，输入玩家名开始交流。';
  }
  var P = G.PrivateChat = {
    active: false,
    unread: function () { return unreadNum; },
    /** 删除只作用于自己的会话列表，由服务端保存隐藏边界。 */
    remove: function (peerId) {
      return G.API.privateChatDelete(peerId).then(function () {
        delete drafts[peerId];
        return P.refresh();
      }).catch(error);
    },
    render: function () {
      dialog = false;
      return '<div id="privateChatListPanel" class="private-chat-panel"><div class="chat-input-bar"><input class="chat-input" id="privateChatPlayer" aria-label="玩家名" maxlength="50" placeholder="输入完整玩家名" onkeydown="if(event.key===\'Enter\')Game.PrivateChat.find()"><button class="chat-send" onclick="Game.PrivateChat.find()">发起私聊</button></div>' +
        '<div id="privateChatConversations" class="private-conversations"></div></div>' +
        '<div id="privateChatDialogPanel" class="private-chat-panel" hidden><div class="private-dialog-header"><button class="chat-back-button" onclick="Game.PrivateChat.showList()" aria-label="返回私聊列表">‹</button><h2 id="privateChatTitle"></h2></div>' +
        '<div class="chat-box" id="privateChatBox" style="flex:1;min-height:0;"></div>' +
        '<div class="chat-input-bar"><input class="chat-input" id="privateChatInput" aria-label="私聊消息" maxlength="80" placeholder="私聊消息，最多 80 字" onkeydown="if(event.key===\'Enter\')Game.PrivateChat.send()" disabled><button class="chat-send" id="privateChatSend" onclick="Game.PrivateChat.send()" disabled>发送</button></div></div>';
    },
    /** 频道入口始终显示多玩家列表，返回列表不清除消息未读。 */
    showList: function () {
      if (peer && node('privateChatInput')) drafts[peer.id] = node('privateChatInput').value;
      peer = null;
      dialog = false;
      messages = [];
      ++generation;
      panels();
      pollStatus();
      return P.refresh();
    },
    /** 头像入口优先使用稳定玩家 ID，历史昵称改名后仍能进入正确会话。 */
    start: function (username, playerId) {
      var targetRequest = playerId ? Promise.resolve({ id: Number(playerId), username: username }) : G.API.privateChatPlayer(username);
      return targetRequest.then(function (target) {
        G.go('chat');
        G.MainView.selectChatChannel('private');
        return P.open(target);
      }).catch(error);
    },
    find: function () {
      var input = node('privateChatPlayer');
      if (!input || !input.value.trim()) { error(new Error('请输入玩家名')); return; }
      return G.API.privateChatPlayer(input.value.trim()).then(P.open).catch(error);
    },
    /** 切换时保存草稿；通过请求代次防止旧会话的慢响应覆盖新会话。 */
    open: function (target) {
      if (peer && node('privateChatInput')) drafts[peer.id] = node('privateChatInput').value;
      peer = Object.assign({}, target);
      dialog = true;
      messages = [];
      panels();
      pollStatus();
      if (node('privateChatInput')) { node('privateChatInput').disabled = false; node('privateChatInput').value = drafts[peer.id] || ''; }
      paintTitle();
      paint();
      var version = ++generation;
      // 先持久化空会话，再拉取列表；未发送消息的头像入口也能保留会话。
      var opening = G.API.privateChatOpen ? G.API.privateChatOpen(target.id) : Promise.resolve(target);
      return opening.then(function (updated) {
        if (version !== generation) return;
        Object.assign(peer, updated);
        paintTitle();
        return P.refresh();
      }).catch(error);
    },
    refresh: function () {
      if (!G.API || !G.API.privateChatConversations) return Promise.resolve();
      var current = dialog ? peer : null;
      var version = ++generation;
      var history = current ? G.API.privateChatHistory(current.id).then(function (data) {
        if (version !== generation) return;
        // 合并请求期间到达的实时消息，避免历史响应抹去新消息。
        var merged = {};
        messages.concat(data.messages || []).forEach(function (m) { merged[m.id] = m; });
        messages = Object.keys(merged).map(function (id) { return merged[id]; }).sort(function (a, b) { return a.id - b.id; }).slice(-50);
        paint();
        return markRead();
      }) : Promise.resolve();
      return history.then(function () { return G.API.privateChatConversations(); }).then(function (rows) {
        if (version === generation) paintConversations(rows);
      }).catch(error);
    },
    receive: function (m) {
      if (!m || m.id == null) return;
      var me = G.Core.state && G.Core.state.player;
      if (me && String(m.recipientId) === String(me.id) && (!visible() || !peer || String(m.playerId) !== String(peer.id)) && G.toast) {
        G.toast(m.username + ' 发来私聊，请到聊天 → 私聊查看');
      }
      if (peer && (String(m.playerId) === String(peer.id) || String(m.recipientId) === String(peer.id))) {
        if (!messages.some(function (existing) { return String(existing.id) === String(m.id); })) {
          messages.push(m); messages = messages.slice(-50); paint();
        }
      }
      P.refresh();
    },
    /** 发送结果只清空对应会话草稿；切换会话不改变正在发送的收件人。 */
    send: function () {
      var input = node('privateChatInput');
      if (!peer || !dialog || peer.available === false || !input || sending) return;
      var text = input.value.trim();
      if (!text) { error(new Error('消息不能为空')); return; }
      if (Date.now() < nextSendAt) { error(new Error('发言过于频繁，请等待 5 秒冷却')); return; }
      var target = peer;
      sending = true;
      if (node('privateChatSend')) node('privateChatSend').disabled = true;
      return G.API.sendPrivateChat(target.id, text).then(function (m) {
        nextSendAt = Date.now() + 5000;
        drafts[target.id] = '';
        if (peer && peer.id === target.id && input.value.trim() === text) input.value = '';
        P.receive(m);
      }).catch(error).finally(function () {
        sending = false;
        paintTitle();
      });
    }
  };
  // 事件委托兼容增量追加的聊天消息，同时提供键盘入口。
  if (document.addEventListener) {
    function activate(event) {
      if (event.type === 'keydown' && event.key !== 'Enter' && event.key !== ' ') return;
      var target = event.target;
      var username = target && target.getAttribute && target.getAttribute('data-private-username');
      if (!username) return;
      event.preventDefault();
      P.start(username, target.getAttribute('data-private-player-id'));
    }
    document.addEventListener('click', activate);
    document.addEventListener('keydown', activate);
  }
})(window.Game);
