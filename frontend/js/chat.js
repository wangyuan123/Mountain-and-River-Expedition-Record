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
    var isSystem = data && (data.type === 'system' || data.playerId === 0 || (data.playerId == null && data.username === '系统'));
    return {
      id: data.id,
      ts: data.ts,
      playerId: data && data.playerId != null ? data.playerId : null,
      type: isSystem ? 'system' : (data && data.type ? data.type : 'player'),
      username: isSystem ? '系统' : String((data && data.username) || '玩家'),
      content: String((data && data.content) || '')
    };
  }

  function isSelfMessage(msg) {
    if (!msg || msg.type === 'system' || msg.type === 'battle') return false;
    var curPlayer = (G.state && G.state.player) || (G.Core && G.Core.state && G.Core.state.player) || null;
    if (curPlayer) {
      if (msg.playerId != null && curPlayer.id != null) {
        if (String(msg.playerId) === String(curPlayer.id)) return true;
      }
      if (msg.username) {
        if (curPlayer.username && msg.username === curPlayer.username) return true;
        if (curPlayer.name && msg.username === curPlayer.name) return true;
      }
    }
    try {
      var localUser = (G.API && G.API.client && typeof G.API.client.getUsername === 'function')
        ? G.API.client.getUsername()
        : (typeof localStorage !== 'undefined' ? localStorage.getItem('wargame_username') : '');
      if (localUser && msg.username && msg.username === localUser) return true;
    } catch (e) {}
    return false;
  }

  function hasMessage(id) {
    for (var i = 0; i < log.length; i++) {
      if (String(log[i].id) === String(id)) return true;
    }
    return false;
  }

  function renderMessage(msg) {
    var div = document.createElement('div');
    var isSys = msg.type === 'system';
    var isBattle = msg.type === 'battle';
    var self = isSelfMessage(msg);
    div.className = 'chat-msg' +
      (isSys ? ' chat-msg-system' : (isBattle ? ' chat-msg-battle' : '')) +
      (self ? ' chat-msg-self' : '');
    div.setAttribute('data-type', msg.type || 'player');
    if (self) div.setAttribute('data-self', '1');

    var time = document.createElement('span');
    time.className = 'chat-time';
    time.textContent = '[' + fmtTime(msg.ts) + ']';

    var tag = document.createElement('span');
    tag.className = 'chat-tag ' + (isSys ? 'tag-system' : (isBattle ? 'tag-battle' : 'tag-world'));
    tag.textContent = isSys ? '[系统]' : (isBattle ? '[战报]' : '[世界]');

    div.appendChild(time);
    div.appendChild(tag);

    if (isSys) {
      // 兼容历史宣战消息：世界频道只展示宣战双方，不展示战争时长说明。
      var displayContent = (msg.content || '').replace(/[，,]?\s*备战\s*6\s*小时[，,]?\s*交战\s*24\s*小时\s*$/i, '');
      var content = document.createElement('span');
      content.className = 'chat-content chat-content-system';
      content.textContent = displayContent;
      div.appendChild(content);
    } else {
      var sender = document.createElement('span');
      sender.className = 'chat-sender';
      sender.textContent = (msg.username || '玩家') + ':';

      var content = document.createElement('span');
      content.className = 'chat-content' + (self ? ' chat-content-self' : '');
      content.textContent = msg.content || '';

      div.appendChild(sender);
      div.appendChild(content);
    }
    return div;
  }

  function renderMessageHtml(m) {
    if (!m) return '';
    var isSys = m.type === 'system';
    var isBattle = m.type === 'battle';
    var self = isSelfMessage(m);
    var tagClass = isSys ? 'tag-system' : (isBattle ? 'tag-battle' : 'tag-world');
    var tagText = isSys ? '[系统]' : (isBattle ? '[战报]' : '[世界]');
    var timeStr = '[' + fmtTime(m.ts) + ']';
    var safeContent = String(m.content == null ? '' : m.content)
      .replace(/[&<>"']/g, function (c) {
        return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
      });

    var h = '<div class="chat-msg' +
      (isSys ? ' chat-msg-system' : (isBattle ? ' chat-msg-battle' : '')) +
      (self ? ' chat-msg-self' : '') +
      '" data-type="' + (m.type || 'player') + '"' +
      (self ? ' data-self="1"' : '') + '>';
    h += '<span class="chat-time">' + timeStr + '</span>';
    h += '<span class="chat-tag ' + tagClass + '">' + tagText + '</span>';

    if (isSys) {
      var displayContent = safeContent.replace(/[，,]?\s*备战\s*6\s*小时[，,]?\s*交战\s*24\s*小时\s*$/i, '');
      h += '<span class="chat-content chat-content-system">' + displayContent + '</span>';
    } else {
      var safeName = String(m.username == null ? '玩家' : m.username)
        .replace(/[&<>"']/g, function (c) {
          return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
      h += '<span class="chat-sender">' + safeName + ':</span>';
      h += '<span class="chat-content' + (self ? ' chat-content-self' : '') + '">' + safeContent + '</span>';
    }
    h += '</div>';
    return h;
  }

  var COOLDOWN_SEC = 5;
  var cdRemaining = 0;
  var cdTimer = null;
  var lastSentText = '';
  var lastSentTime = 0;

  function updateSendBtnUI() {
    var btn = document.getElementById('worldChatSendBtn');
    if (!btn) return;
    if (cdRemaining > 0) {
      btn.disabled = true;
      btn.classList.add('disabled');
      btn.textContent = cdRemaining + 's';
    } else {
      btn.disabled = false;
      btn.classList.remove('disabled');
      btn.textContent = '发送';
    }
  }

  function startCooldown(sec) {
    if (cdTimer) {
      clearInterval(cdTimer);
      cdTimer = null;
    }
    cdRemaining = (sec == null ? COOLDOWN_SEC : Math.max(1, parseInt(sec, 10) || COOLDOWN_SEC));
    updateSendBtnUI();
    cdTimer = setInterval(function () {
      cdRemaining--;
      if (cdRemaining <= 0) {
        cdRemaining = 0;
        clearInterval(cdTimer);
        cdTimer = null;
      }
      updateSendBtnUI();
    }, 1000);
  }

  function getCooldown() {
    return cdRemaining;
  }

  function isSpamDuplicate(content) {
    if (!content) return false;
    var trimmed = String(content).trim();
    if (lastSentText && trimmed.toLowerCase() === lastSentText.toLowerCase()) {
      if (Date.now() - lastSentTime < 60000) {
        return true;
      }
    }
    return false;
  }

  function recordSent(content) {
    lastSentText = String(content || '').trim();
    lastSentTime = Date.now();
    startCooldown(COOLDOWN_SEC);
  }

  var Chat = {
    log: log,
    MAX: MAX,
    COOLDOWN_SEC: COOLDOWN_SEC,

    getCooldown: getCooldown,
    isSpamDuplicate: isSpamDuplicate,
    recordSent: recordSent,
    startCooldown: startCooldown,
    updateSendBtnUI: updateSendBtnUI,

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
    },

    renderMessage: renderMessage,
    renderMessageHtml: renderMessageHtml,
    isSelf: isSelfMessage
  };

  G.Chat = Chat;
  G.fmtChatTime = fmtTime;
})(window.Game);
