/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var log = [];
  var listeners = [];
  var MAX = G.Constants.chatMax;
  var MIN_WORLD_CHAT_PRESTIGE = G.Constants.chatMinPrestige;
  // 连续消息只显示昵称和内容，间隔五分钟或跨天时插入一次时间分隔线。
  var CHAT_TIME_GAP_MS = 5 * 60 * 1000;

  /** 世界频道发言按当前玩家声望开放，历史消息始终可读。 */
  function canSend() {
    var state = G.state || (G.Core && G.Core.state);
    return !!state && Number(state.prestige) >= MIN_WORLD_CHAT_PRESTIGE;
  }

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
      content: String((data && data.content) || ''),
      avatar: data && data.avatar ? String(data.avatar) : ''
    };
  }

  /**
   * 返回消息可用的预设头像，避免把外部地址直接写入聊天图片。
   * @param {string} avatar - 服务端返回的头像路径
   * @param {boolean} self - 是否为当前玩家消息
   * @returns {string} 可渲染的头像路径
   */
  function avatarSrc(avatar, self) {
    var presets = (G.Constants && G.Constants.presetAvatars) || [];
    for (var i = 0; i < presets.length; i++) {
      if (presets[i].src === avatar) return avatar;
    }
    if (self && G.Core && G.Core.getCurrentAvatar) return G.Core.getCurrentAvatar();
    return presets.length ? presets[0].src : '';
  }

  function sameDay(firstTs, secondTs) {
    var first = new Date(firstTs);
    var second = new Date(secondTs);
    return first.getFullYear() === second.getFullYear() &&
      first.getMonth() === second.getMonth() && first.getDate() === second.getDate();
  }

  /**
   * 判断聊天记录是否需要插入时间分隔线。
   * @param {Object} msg - 当前消息
   * @param {Object|null} previous - 上一条消息
   * @returns {boolean} 是否展示时间
   */
  function shouldShowTime(msg, previous) {
    if (!previous) return true;
    var gap = Number(msg.ts) - Number(previous.ts);
    return !sameDay(msg.ts, previous.ts) || gap >= CHAT_TIME_GAP_MS;
  }

  function formatSeparatorTime(ts) {
    var date = new Date(ts);
    var time = fmtTime(ts);
    var now = new Date();
    if (sameDay(ts, now.getTime())) return '今天 ' + time;
    return (date.getMonth() + 1) + '月' + date.getDate() + '日 ' + time;
  }

  function appendAvatar(container, msg, self) {
    if (msg.type === 'system') return;
    var img = document.createElement('img');
    img.className = 'chat-avatar';
    img.src = avatarSrc(msg.avatar, self);
    img.alt = (msg.username || '玩家') + '的统帅头像';
    img.onerror = function () {
      this.onerror = null;
      this.src = avatarSrc('', false);
    };
    container.appendChild(img);
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

  function renderMessage(msg, previous) {
    var div = document.createElement('div');
    var isSys = msg.type === 'system';
    var isBattle = msg.type === 'battle';
    var self = isSelfMessage(msg);
    div.className = 'chat-msg' +
      (isSys ? ' chat-msg-system' : (isBattle ? ' chat-msg-battle' : '')) +
      (self ? ' chat-msg-self' : '');
    div.setAttribute('data-type', msg.type || 'player');
    if (self) div.setAttribute('data-self', '1');

    if (shouldShowTime(msg, previous)) {
      var separator = document.createElement('div');
      separator.className = 'chat-time-separator';
      separator.textContent = formatSeparatorTime(msg.ts);
      div.appendChild(separator);
    }

    if (isSys) {
      // 兼容历史宣战消息：世界频道只展示宣战双方，不展示战争时长说明。
      var systemTag = document.createElement('span');
      systemTag.className = 'chat-tag tag-system';
      systemTag.textContent = '[系统]';
      div.appendChild(systemTag);
      var displayContent = (msg.content || '').replace(/[，,]?\s*备战\s*6\s*小时[，,]?\s*交战\s*24\s*小时\s*$/i, '');
      var content = document.createElement('span');
      content.className = 'chat-content chat-content-system';
      content.textContent = displayContent;
      div.appendChild(content);
    } else {
      appendAvatar(div, msg, self);
      var body = document.createElement('div');
      body.className = 'chat-msg-body';
      var sender = document.createElement('span');
      sender.className = 'chat-sender';
      sender.textContent = (msg.username || '玩家') + ':';

      var content = document.createElement('span');
      content.className = 'chat-content' + (self ? ' chat-content-self' : '');
      content.textContent = msg.content || '';

      body.appendChild(sender);
      body.appendChild(content);
      div.appendChild(body);
    }
    return div;
  }

  function renderMessageHtml(m, previous) {
    if (!m) return '';
    var isSys = m.type === 'system';
    var isBattle = m.type === 'battle';
    var self = isSelfMessage(m);
    var tagText = isSys ? '[系统]' : (isBattle ? '[战报]' : '[世界]');
    var safeContent = String(m.content == null ? '' : m.content)
      .replace(/[&<>"']/g, function (c) {
        return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
      });

    var h = '<div class="chat-msg' +
      (isSys ? ' chat-msg-system' : (isBattle ? ' chat-msg-battle' : '')) +
      (self ? ' chat-msg-self' : '') +
      '" data-type="' + (m.type || 'player') + '"' +
      (self ? ' data-self="1"' : '') + '>';
    if (shouldShowTime(m, previous)) {
      h += '<div class="chat-time-separator">' + formatSeparatorTime(m.ts) + '</div>';
    }

    if (isSys) {
      var displayContent = safeContent.replace(/[，,]?\s*备战\s*6\s*小时[，,]?\s*交战\s*24\s*小时\s*$/i, '');
      h += '<span class="chat-tag tag-system">' + tagText + '</span>';
      h += '<span class="chat-content chat-content-system">' + displayContent + '</span>';
    } else {
      var selfAvatar = avatarSrc(m.avatar, self)
        .replace(/[&<>"']/g, function (c) {
          return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
      var safeName = String(m.username == null ? '玩家' : m.username)
        .replace(/[&<>"']/g, function (c) {
          return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
      h += '<img class="chat-avatar" src="' + selfAvatar + '" alt="' + safeName + '的统帅头像" onerror="this.onerror=null;this.src=\'' + avatarSrc('', false) + '\'">';
      h += '<div class="chat-msg-body">';
      h += '<span class="chat-sender">' + safeName + ':</span>';
      h += '<span class="chat-content' + (self ? ' chat-content-self' : '') + '">' + safeContent + '</span>';
      h += '</div>';
    }
    h += '</div>';
    return h;
  }

  var COOLDOWN_SEC = G.Constants.chatCooldownSec;
  var cdRemaining = 0;
  var cdTimer = null;
  var lastSentText = '';
  var lastSentTime = 0;

  function updateSendBtnUI() {
    var btn = document.getElementById('worldChatSendBtn');
    if (!btn) return;
    var input = document.getElementById('worldChatInput');
    if (input) {
      input.disabled = !canSend();
      input.placeholder = canSend() ? '输入电报简讯... (最多 80 字，Enter 发送)' : '声望达到 10,000 后可发言';
    }
    if (!canSend()) {
      btn.disabled = true;
      btn.classList.add('disabled');
      btn.textContent = '只读';
    } else if (cdRemaining > 0) {
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

  var guildLog = [];
  var guildListeners = [];
  var guildCdRemaining = 0;
  var guildCdTimer = null;
  var lastSentGuildText = '';
  var lastSentGuildTime = 0;

  function toGuildMessage(data) {
    var isSystem = data && (data.type === 'system' || data.playerId === 0 || (data.playerId == null && data.username === '系统'));
    return {
      id: data.id,
      guildId: data.guildId,
      ts: data.ts,
      playerId: data && data.playerId != null ? data.playerId : null,
      type: isSystem ? 'system' : (data && data.type ? data.type : 'player'),
      role: data && data.role ? data.role : (isSystem ? 'system' : 'member'),
      username: isSystem ? '系统' : String((data && data.username) || '战友'),
      content: String((data && data.content) || ''),
      avatar: data && data.avatar ? String(data.avatar) : ''
    };
  }

  function hasGuildMessage(id) {
    for (var i = 0; i < guildLog.length; i++) {
      if (String(guildLog[i].id) === String(id)) return true;
    }
    return false;
  }

  function renderGuildMessageHtml(m, previous) {
    if (!m) return '';
    var isSys = m.type === 'system';
    var self = isSelfMessage(m);
    var safeContent = String(m.content == null ? '' : m.content)
      .replace(/[&<>"']/g, function (c) {
        return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
      });

    var h = '<div class="chat-msg' +
      (isSys ? ' chat-msg-system' : '') +
      (self ? ' chat-msg-self' : '') +
      '" data-type="' + (m.type || 'player') + '"' +
      (self ? ' data-self="1"' : '') + '>';
    if (shouldShowTime(m, previous)) {
      h += '<div class="chat-time-separator">' + formatSeparatorTime(m.ts) + '</div>';
    }

    if (isSys) {
      h += '<span class="chat-tag tag-system">[系统]</span>';
      h += '<span class="chat-content chat-content-system">' + safeContent + '</span>';
    } else {
      var selfAvatar = avatarSrc(m.avatar, self)
        .replace(/[&<>"']/g, function (c) {
          return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
      var safeName = String(m.username == null ? '战友' : m.username)
        .replace(/[&<>"']/g, function (c) {
          return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
      var rName = m.role === 'leader' ? '团长' : (m.role === 'admin' ? '管理' : '成员');
      var rClass = m.role === 'leader' ? 'tag-guild-leader' : (m.role === 'admin' ? 'tag-guild-admin' : 'tag-guild-member');
      h += '<img class="chat-avatar" src="' + selfAvatar + '" alt="' + safeName + '的统帅头像" onerror="this.onerror=null;this.src=\'' + avatarSrc('', false) + '\'">';
      h += '<div class="chat-msg-body">';
      h += '<div class="chat-msg-header">';
      h += '<span class="chat-tag ' + rClass + '">[' + rName + ']</span>';
      h += '<span class="chat-sender">' + safeName + ':</span>';
      h += '</div>';
      h += '<span class="chat-content' + (self ? ' chat-content-self' : '') + '">' + safeContent + '</span>';
      h += '</div>';
    }
    h += '</div>';
    return h;
  }

  function updateGuildSendBtnUI() {
    var btns = [document.getElementById('guildChatSendBtn'), document.getElementById('chatPageGuildSendBtn')];
    btns.forEach(function (btn) {
      if (!btn) return;
      if (guildCdRemaining > 0) {
        btn.disabled = true;
        btn.classList.add('disabled');
        btn.textContent = guildCdRemaining + 's';
      } else {
        btn.disabled = false;
        btn.classList.remove('disabled');
        btn.textContent = '发送';
      }
    });
  }

  function startGuildCooldown(sec) {
    if (guildCdTimer) {
      clearInterval(guildCdTimer);
      guildCdTimer = null;
    }
    guildCdRemaining = (sec == null ? COOLDOWN_SEC : Math.max(1, parseInt(sec, 10) || COOLDOWN_SEC));
    updateGuildSendBtnUI();
    guildCdTimer = setInterval(function () {
      guildCdRemaining--;
      if (guildCdRemaining <= 0) {
        guildCdRemaining = 0;
        clearInterval(guildCdTimer);
        guildCdTimer = null;
      }
      updateGuildSendBtnUI();
    }, 1000);
  }

  var Chat = {
    log: log,
    guildLog: guildLog,
    MAX: MAX,
    MIN_WORLD_CHAT_PRESTIGE: MIN_WORLD_CHAT_PRESTIGE,
    COOLDOWN_SEC: COOLDOWN_SEC,

    canSend: canSend,
    getCooldown: getCooldown,
    getGuildCooldown: function () { return guildCdRemaining; },
    isSpamDuplicate: isSpamDuplicate,
    recordSent: recordSent,
    startCooldown: startCooldown,
    startGuildCooldown: startGuildCooldown,
    updateSendBtnUI: updateSendBtnUI,
    updateGuildSendBtnUI: updateGuildSendBtnUI,

    loadHistory: function () {
      if (!G.API || !G.API.worldChatHistory) return Promise.resolve();
      return G.API.worldChatHistory().then(function (data) {
        var messages = (data && data.messages) || [];
        log.length = 0;
        for (var i = 0; i < messages.length; i++) log.push(toMessage(messages[i]));
        // 历史返回时只更新消息列表，保留玩家已输入的草稿与页面布局。
        var box = document.getElementById('worldChatBox');
        if (box && G.Core && (G.Core.route === 'home' || G.Core.route === 'chat')) {
          var visible = log.slice(G.Core.route === 'chat' ? -50 : -20);
          box.innerHTML = visible.map(function (msg, index) {
            return renderMessageHtml(msg, index ? visible[index - 1] : null);
          }).join('');
          var panel = document.getElementById('chatMessagesPanel');
          if (!panel || !panel.hidden) box.scrollTop = box.scrollHeight;
        }
      }).catch(function () {
        // 历史加载失败不影响游戏主界面；发送时会给出具体错误。
      });
    },

    loadGuildHistory: function () {
      if (!G.API || !G.API.getGuildChatHistory) return Promise.resolve();
      return G.API.getGuildChatHistory().then(function (data) {
        var messages = (data && data.messages) || [];
        guildLog.length = 0;
        for (var i = 0; i < messages.length; i++) guildLog.push(toGuildMessage(messages[i]));

        var boxes = [document.getElementById('guildChatBox'), document.getElementById('chatPageGuildBox')];
        boxes.forEach(function (box) {
          if (!box) return;
          var visible = guildLog.slice(-50);
          box.innerHTML = visible.map(function (msg, index) {
            return renderGuildMessageHtml(msg, index ? visible[index - 1] : null);
          }).join('');
          box.scrollTop = box.scrollHeight;
        });
      }).catch(function () {
        // 历史加载失败不影响主流程
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

    receiveGuild: function (data) {
      if (!data || data.id == null || hasGuildMessage(data.id)) return;
      var msg = toGuildMessage(data);
      guildLog.push(msg);
      if (guildLog.length > MAX) guildLog.splice(0, guildLog.length - MAX);
      Chat._appendGuildToBox(msg);
      for (var i = 0; i < guildListeners.length; i++) {
        try { guildListeners[i](msg); } catch (e) { /* noop */ }
      }
    },

    send: function (content) {
      if (!canSend()) return Promise.reject(new Error('声望达到 10000 后才能在世界频道发言'));
      content = String(content == null ? '' : content)
        .replace(/[\u0000-\u001F\u007F-\u009F\u200B-\u200F\uFEFF]/g, '')
        .replace(/^\s+|\s+$/g, '');
      if (!content) return Promise.reject(new Error('消息不能为空'));
      if (!G.API || !G.API.sendWorldChat) return Promise.reject(new Error('聊天服务不可用'));
      // 消息由服务端持久化并通过 WebSocket 广播；不做本地伪造或乐观插入。
      return G.API.sendWorldChat(content);
    },

    sendGuild: function (content) {
      content = String(content == null ? '' : content)
        .replace(/[\u0000-\u001F\u007F-\u009F\u200B-\u200F\uFEFF]/g, '')
        .replace(/^\s+|\s+$/g, '');
      if (!content) return Promise.reject(new Error('消息不能为空'));
      if (guildCdRemaining > 0) return Promise.reject(new Error('发言过于频繁，请等待冷却'));
      if (!G.API || !G.API.sendGuildChat) return Promise.reject(new Error('军团聊天服务不可用'));
      lastSentGuildText = content;
      lastSentGuildTime = Date.now();
      startGuildCooldown(COOLDOWN_SEC);
      return G.API.sendGuildChat(content).then(function (res) {
        var msgData = (res && res.data) || res;
        if (msgData && msgData.id != null) {
          Chat.receiveGuild(msgData);
        }
        return res;
      });
    },

    recent: function (n) {
      return log.slice(-(n || 20));
    },

    recentGuild: function (n) {
      return guildLog.slice(-(n || 50));
    },

    on: function (fn) {
      listeners.push(fn);
      return function () {
        var i = listeners.indexOf(fn);
        if (i >= 0) listeners.splice(i, 1);
      };
    },

    onGuild: function (fn) {
      guildListeners.push(fn);
      return function () {
        var i = guildListeners.indexOf(fn);
        if (i >= 0) guildListeners.splice(i, 1);
      };
    },

    _appendToBox: function (msg) {
      var box = document.getElementById('worldChatBox');
      if (!box) return;
      var panel = document.getElementById('chatMessagesPanel');
      // 隐藏面板的布局高度为零，不能据此判断玩家是否正在阅读历史消息。
      var atBottom = !(panel && panel.hidden) && box.scrollHeight - box.scrollTop - box.clientHeight < 40;
      var previous = log.length > 1 ? log[log.length - 2] : null;
      box.appendChild(renderMessage(msg, previous));
      var limit = G.Core && G.Core.route === 'chat' ? 50 : 25;
      while (box.children.length > limit) box.removeChild(box.firstChild);
      if (atBottom) box.scrollTop = box.scrollHeight;
    },

    _appendGuildToBox: function (msg) {
      var boxes = [document.getElementById('guildChatBox'), document.getElementById('chatPageGuildBox')];
      boxes.forEach(function (box) {
        if (!box) return;
        var atBottom = (box.scrollHeight == null || box.clientHeight == null) ? true : (box.scrollHeight - box.scrollTop - box.clientHeight < 40);
        var previous = guildLog.length > 1 ? guildLog[guildLog.length - 2] : null;
        var msgHtml = renderGuildMessageHtml(msg, previous);
        if (typeof box.insertAdjacentHTML === 'function') {
          box.insertAdjacentHTML('beforeend', msgHtml);
        } else {
          var wrapper = document.createElement('div');
          wrapper.innerHTML = msgHtml;
          if (wrapper.firstElementChild) {
            box.appendChild(wrapper.firstElementChild);
          } else if (wrapper.children && wrapper.children.length > 0) {
            for (var ci = 0; ci < wrapper.children.length; ci++) box.appendChild(wrapper.children[ci]);
          } else {
            box.innerHTML = (box.innerHTML || '') + msgHtml;
          }
        }
        if (box.children && box.children.length > 50) {
          while (box.children.length > 50) box.removeChild(box.firstChild);
        }
        if (atBottom && box.scrollHeight != null) box.scrollTop = box.scrollHeight;
      });
    },

    renderMessage: renderMessage,
    renderMessageHtml: renderMessageHtml,
    renderGuildMessageHtml: renderGuildMessageHtml,
    isSelf: isSelfMessage
  };

  G.Chat = Chat;
  G.fmtChatTime = fmtTime;
})(window.Game);
