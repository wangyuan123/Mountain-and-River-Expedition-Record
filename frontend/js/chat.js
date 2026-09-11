/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var log = [];
  var listeners = [];
  var MAX = 80;

  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  function fmtTime(ts) {
    var d = new Date(ts);
    return pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }

  function toMessage(data) {
    // 后端系统消息: { playerId: 0, username: "系统", type: "system" }
    var isSystem = data && (data.type === 'system' || data.playerId === 0 || data.username === '系统');
    return {
      id: data.id,
      ts: data.ts,
      type: isSystem ? 'system' : 'player',
      username: isSystem ? '系统' : String(data.username || '玩家'),
      content: String(data.content || '')
    };
  }

  function hasMessage(id) {
    for (var i = 0; i < log.length; i++) {
      if (String(log[i].id) === String(id)) return true;
    }
    return false;
  }

  function renderMessage(msg) {
    var div = document.createElement('div');
    div.className = 'chat-msg' + (msg.type === 'system' ? ' chat-msg-system' : '');
    div.setAttribute('data-type', msg.type);

    var time = document.createElement('span');
    time.className = 'chat-time';
    time.textContent = fmtTime(msg.ts);
    var text = document.createElement('span');
    text.className = 'chat-text';
    if (msg.type === 'system') {
      // 兼容历史宣战消息：世界频道只展示宣战双方，不展示战争时长说明。
      var displayContent = msg.content.replace(/[，,]?\s*备战\s*6\s*小时[，,]?\s*交战\s*24\s*小时\s*$/i, '');
      text.textContent = displayContent;
    } else {
      text.textContent = '🗣️ ' + msg.username + ': ' + msg.content;
    }
    div.appendChild(time);
    div.appendChild(text);
    return div;
  }

  var Chat = {
    log: log,
    MAX: MAX,

    loadHistory: function () {
      if (!G.API || !G.API.worldChatHistory) return Promise.resolve();
      return G.API.worldChatHistory().then(function (data) {
        var messages = (data && data.messages) || [];
        log.length = 0;
        for (var i = 0; i < messages.length; i++) log.push(toMessage(messages[i]));
        if (G.Core && G.Core.route === 'home') G.Core.refreshContent();
      }).catch(function () {
        // 历史加载失败不影响游戏主界面；发送时会给出具体错误。
      });
    },

    receive: function (data) {
      if (!data || data.id == null || hasMessage(data.id)) return;
      var msg = toMessage(data);
      log.push(msg);
      if (log.length > MAX) log.splice(0, log.length - MAX);
      Chat._appendToBox(msg);
      for (var i = 0; i < listeners.length; i++) {
        try { listeners[i](msg); } catch (e) { /* noop */ }
      }
    },

    send: function (content) {
      content = String(content == null ? '' : content)
        .replace(/[\u0000-\u001F\u007F-\u009F\u200B-\u200F\uFEFF]/g, '')
        .replace(/^\s+|\s+$/g, '');
      if (!content) return Promise.reject(new Error('消息不能为空'));
      if (!G.API || !G.API.sendWorldChat) return Promise.reject(new Error('聊天服务不可用'));
      // 消息由服务端持久化并通过 WebSocket 广播；不做本地伪造或乐观插入。
      return G.API.sendWorldChat(content);
    },

    recent: function (n) {
      return log.slice(-(n || 20));
    },

    on: function (fn) {
      listeners.push(fn);
      return function () {
        var i = listeners.indexOf(fn);
        if (i >= 0) listeners.splice(i, 1);
      };
    },

    _appendToBox: function (msg) {
      var box = document.getElementById('worldChatBox');
      if (!box) return;
      box.appendChild(renderMessage(msg));
      while (box.children.length > 25) box.removeChild(box.firstChild);
      box.scrollTop = box.scrollHeight;
    }
  };

  G.Chat = Chat;
  G.fmtChatTime = fmtTime;
})(window.Game);
