/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var Core = G.Core;

  // ============================================================
  //  本地状态 (内存, 不再持久化到 localStorage; 后端是单一来源)
  // ============================================================
  var mails = [];          // 当前 folder 下的邮件
  var curFolder = 'inbox'; // inbox | outbox | system | unread
  var curMailId = null;
  var view = 'list';       // list | read | compose
  var unreadNum = 0;       // 缓存: 用于导航栏红点
  var listInFlight = null; // 并发去重: 同一 folder 只发一次 list
  var viewEl = null;       // 当前 view 元素 (由 Core 传入)

  // ============================================================
  //  收件人历史 (本地 UX 状态, 与游戏数据分离; 按账号隔离)
  // ============================================================
  var HISTORY_MAX = 20;

  function _userKey() {
    try { return (G.API && G.API.getUsername && G.API.getUsername()) || 'guest'; }
    catch (e) { return 'guest'; }
  }
  function _historyKey() { return 'wg.mail.recipients.v1.' + _userKey(); }

  function loadHistory() {
    try {
      var raw = localStorage.getItem(_historyKey());
      var arr = raw ? JSON.parse(raw) : null;
      return Array.isArray(arr) ? arr : [];
    } catch (e) { return []; }
  }
  function saveHistory(arr) {
    try { localStorage.setItem(_historyKey(), JSON.stringify(arr)); }
    catch (e) { /* noop */ }
  }
  /** 发送成功后把收件人加入历史 (去重, 最新在前, 上限 HISTORY_MAX) */
  function pushHistory(name) {
    if (!name) return;
    var arr = loadHistory();
    var i = arr.indexOf(name);
    if (i >= 0) arr.splice(i, 1); // 已存在则先去掉旧的
    arr.unshift(name);             // 最新的放最前
    if (arr.length > HISTORY_MAX) arr = arr.slice(0, HISTORY_MAX);
    saveHistory(arr);
  }

  // ============================================================
  //  工具
  // ============================================================
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  function fmtTime(ts) {
    var d = new Date(ts);
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
      + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }
  function fmtShort(ts) {
    var d = new Date(ts);
    var now = new Date();
    if (d.toDateString() === now.toDateString()) return pad2(d.getHours()) + ':' + pad2(d.getMinutes());
    return pad2(d.getMonth() + 1) + '/' + pad2(d.getDate());
  }
  function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function attachIcon(type) {
    return { gold: '🪙', diamond: '💎', food: '🌾', steel: '🔩', oil: '🛢️', rare: '💠' }[type] || '🎁';
  }
  function attachLabel(type) {
    return { gold: '黄金', diamond: '钻石', food: '粮食', steel: '钢铁', oil: '石油', rare: '稀矿' }[type] || type;
  }
  function toast(msg) { if (G.toast) G.toast(msg); }

  // ============================================================
  //  数据加载
  // ============================================================
  function loadFolder(folder) {
    listInFlight = G.API.listMail(folder).then(function (data) {
      mails = (data && data.mails) || [];
      unreadNum = (data && typeof data.unread === 'number') ? data.unread : mails.filter(function (m) {
        return folder === 'inbox' && !m.read;
      }).length;
      // 刷新导航栏红点 (Core.go 时 navbar 是在 loadFolder 之前渲染的, 此时 unread=0)
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
      render();
    }).catch(function (err) {
      toast('加载邮件失败: ' + (err.message || err));
    });
    return listInFlight;
  }

  function refreshUnread() {
    return G.API.mailUnread().then(function (data) {
      unreadNum = (data && data.unread) || 0;
      // 触发导航栏重绘
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
    }).catch(function () { /* 静默 */ });
  }

  // ============================================================
  //  公共 API (挂在 G.Mail)
  // ============================================================
  var Mail = {
    /** 未读数, 同步返回缓存值 (由后端推送 / folder 加载更新) */
    unread: function () { return unreadNum; },

    /** 重新拉取未读数 (供 WS 收到新邮件时调用) */
    refreshUnread: refreshUnread,

    /** 首次进入邮件模块时拉一次 (兼容旧 main.js 调用 Mail.seed) */
    seed: function () {
      return refreshUnread();
    },

    /** 写信 */
    compose: function () {
      view = 'compose';
      render();
    },

    /** 是否处于写信/回复状态 (供 WS / 其它模块判断是否要打断重渲染) */
    isComposing: function () { return view === 'compose'; },
    isReplying: function () { return view === 'read'; },

    /** 返回列表 */
    back: function () {
      view = 'list';
      curMailId = null;
      render();
    },

    /** 切换文件夹 */
    setFolder: function (f) {
      curFolder = f;
      view = 'list';
      curMailId = null;
      loadFolder(f);
    },

    /** 打开一封 (同时调用后端标记已读) */
    open: function (id) {
      // onclick 模板把 m.id 用单引号包起来 -> JS 端收到的是字符串; 但后端 Long 序列化为数字,
      // 严格相等 (===) 会失败, 导致 view='read' 立即被 render() 的回退逻辑重置回 'list' (点击无效)
      curMailId = (typeof id === 'string' && /^-?\d+$/.test(id)) ? Number(id) : id;
      view = 'read';
      // 本地先翻为已读, 避免 UI 闪烁
      for (var i = 0; i < mails.length; i++) {
        if (mails[i].id === curMailId) { mails[i].read = true; break; }
      }
      render();
      // 异步标后端
      G.API.markMailRead(curMailId).then(refreshUnread).catch(function () { /* ignore */ });
    },

    /** 发送 (compose 页调用) */
    send: function (to, subject, body, attach) {
      return G.API.sendMail(to, subject, body, attach).then(function (data) {
        toast('已发送给 ' + to);
        return data;
      });
    },

    /** 领取附件 */
    claimAttach: function (id) {
      G.API.claimMailAttach(id).then(function (data) {
        if (data && data.success) {
          toast('附件已领取');
          // 本地同步 claimed
          for (var i = 0; i < mails.length; i++) {
            if (mails[i].id === id) { mails[i].claimed = true; break; }
          }
          render();
        } else if (data && data.error) {
          toast(data.error);
        }
      }).catch(function (err) {
        toast('领取失败: ' + (err.message || err));
      });
    },

    /** 删除一封 (仅收件人能删) */
    delete: function (id) {
      G.API.deleteMail(id).then(function () {
        toast('已删除');
        view = 'list';
        curMailId = null;
        return loadFolder(curFolder);
      }).then(refreshUnread).catch(function (err) {
        toast('删除失败: ' + (err.message || err));
      });
    },

    // 内部: 写信 / 回复共用
    _sendCompose: function () {
      var to = (document.getElementById('mailTo') || {}).value;
      var subject = (document.getElementById('mailSubject') || {}).value;
      var body = (document.getElementById('mailBody') || {}).value;
      if (!to || !subject) { toast('收件人与主题不可为空'); return; }
      Mail.send(to, subject, body, []).then(function () {
        pushHistory(to);  // 发送成功才入历史 (失败的不要污染)
        view = 'list';
        loadFolder('outbox').then(refreshUnread);
      }).catch(function (err) {
        toast(err.message || '发送失败');
      });
    },
    _sendReply: function () {
      var el = document.getElementById('mailReplyInput');
      var body = el ? el.value : '';
      if (!body) { toast('请输入回复内容'); return; }
      var cur = null;
      for (var i = 0; i < mails.length; i++) if (mails[i].id === curMailId) { cur = mails[i]; break; }
      if (!cur) return;
      Mail.send(cur.from, 'Re: ' + cur.subject, body, []).then(function () {
        // 回复也视为对收件人的一次接触, 收入历史
        pushHistory(cur.from);
        toast('已回复给 ' + cur.from);
      }).catch(function (err) {
        toast(err.message || '回复失败');
      });
    },

    // ============================================================
    //  视图渲染
    // ============================================================
    renderView: function (v) {
      viewEl = v;
      // 触发初次加载 (默认 inbox)
      if (!mails.length && !listInFlight) {
        loadFolder(curFolder);
      }
      render();
    }
  };

  // ============================================================
  //  渲染 (内部)
  // ============================================================
  function captureFormState() {
    // 防御层: 即便有其它代码路径 (WS tick / refreshState / 顶层 render) 触发了
    // view 元素 innerHTML 重写, 也要保留用户已经输入的收件人/主题/正文/回复内容,
    // 以及焦点位置, 避免 "边打字边被刷掉" 的体验问题。
    if (!viewEl) return null;
    var ae = document.activeElement;
    var focusedId = ae && ae.id ? ae.id : null;
    var selStart = null, selEnd = null;
    if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA') && typeof ae.selectionStart === 'number') {
      try { selStart = ae.selectionStart; selEnd = ae.selectionEnd; } catch (e) { /* IE quirks */ }
    }
    return {
      to:        (document.getElementById('mailTo')        || {}).value,
      subject:   (document.getElementById('mailSubject')   || {}).value,
      body:      (document.getElementById('mailBody')      || {}).value,
      reply:     (document.getElementById('mailReplyInput')|| {}).value,
      focusedId: focusedId,
      selStart:  selStart,
      selEnd:    selEnd
    };
  }
  function restoreFormState(st) {
    if (!st) return;
    function setVal(id, v) {
      if (v == null) return;
      var el = document.getElementById(id);
      if (el) el.value = v;
    }
    setVal('mailTo', st.to);
    setVal('mailSubject', st.subject);
    setVal('mailBody', st.body);
    setVal('mailReplyInput', st.reply);
    if (st.focusedId) {
      var f = document.getElementById(st.focusedId);
      if (f) {
        try { f.focus(); } catch (e) { /* noop */ }
        if (st.selStart != null && typeof f.setSelectionRange === 'number' && f.setSelectionRange) {
          try { f.setSelectionRange(st.selStart, st.selEnd); } catch (e) { /* noop */ }
        }
      }
    }
  }

  function render() {
    if (!viewEl) return;
    // 在 innerHTML 重写前抓取当前输入, 之后还原, 防止 WS tick / 顶层 render 把表单刷掉
    var formState = captureFormState();
    var h = '';
    h += '<div class="title">- 战时邮件 -</div>';

    if (view === 'list') {
      h += '<div class="mail-tabs">';
      var tabs = [
        { id: 'inbox',  name: '收件箱', n: curFolder === 'inbox' ? mails.filter(function (m) { return !m.read; }).length : unreadNum },
        { id: 'outbox', name: '发件箱', n: 0 },
        { id: 'system', name: '系统',   n: 0 },
        { id: 'unread', name: '未读',   n: curFolder === 'unread' ? mails.length : unreadNum }
      ];
      for (var i = 0; i < tabs.length; i++) {
        var t = tabs[i];
        var cls = 'mail-tab' + (t.id === curFolder ? ' active' : '');
        var nBadge = t.n > 0 ? '<span class="mail-num">' + t.n + '</span>' : '';
        h += '<span class="' + cls + '" onclick="Game.Mail.setFolder(\'' + t.id + '\')">'
          + t.name + nBadge + '</span>';
      }
      h += '<span class="mail-compose" onclick="Game.Mail.compose()">✏️ 写信</span>';
      h += '</div>';

      if (!mails.length) {
        h += '<div class="mail-empty">📭 此文件夹暂无邮件</div>';
      } else {
        h += '<div class="mail-list">';
        for (var j = 0; j < mails.length; j++) {
          var m = mails[j];
          var readCls = m.read ? ' read' : ' unread';
          var sysCls = m.system ? ' sys' : '';
          var attachHtml = (m.attach && m.attach.length) ? '<span class="mail-attach">🎁</span>' : '';
          var other = (curFolder === 'inbox' || curFolder === 'system' || curFolder === 'unread') ? m.from : m.to;
          var subject = escapeHtml(m.subject || '(无主题)');
          var preview = escapeHtml((m.body || '').slice(0, 36));
          h += '<div class="mail-item' + readCls + sysCls + '" onclick="Game.Mail.open(\'' + m.id + '\')">'
            + '<div class="mail-item-line1">'
            +   '<span class="mail-from">' + escapeHtml(other) + '</span>'
            +   '<span class="mail-time">' + fmtShort(m.ts) + '</span>'
            + '</div>'
            + '<div class="mail-item-line2">'
            +   '<span class="mail-subject">' + (m.read ? '' : '● ') + subject + '</span>'
            +   attachHtml
            + '</div>'
            + '<div class="mail-preview">' + preview + (m.body && m.body.length > 36 ? '…' : '') + '</div>'
            + '</div>';
        }
        h += '</div>';
      }
    } else if (view === 'read') {
      var cur = null;
      for (var k = 0; k < mails.length; k++) if (mails[k].id === curMailId) { cur = mails[k]; break; }
      if (!cur) { view = 'list'; render(); return; }
      h += '<div class="mail-toolbar">';
      h += '<span class="mail-back" onclick="Game.Mail.back()">← 返回</span>';
      h += '<span class="mail-del" onclick="Game.Mail.delete(\'' + cur.id + '\')">🗑 删除</span>';
      h += '</div>';
      h += '<div class="mail-detail">';
      h += '<div class="mail-detail-subject">' + escapeHtml(cur.subject || '(无主题)') + '</div>';
      h += '<div class="mail-detail-meta">';
      h += '<span><b>发件人:</b> ' + escapeHtml(cur.from) + '</span>';
      h += '<span><b>收件人:</b> ' + escapeHtml(cur.to) + '</span>';
      h += '<span><b>时间:</b> ' + fmtTime(cur.ts) + '</span>';
      h += '</div>';
      h += '<div class="mail-detail-body">' + escapeHtml(cur.body || '').replace(/\n/g, '<br>') + '</div>';
      if (cur.attach && cur.attach.length) {
        h += '<div class="mail-attach-box">';
        h += '<div class="mail-attach-title">📎 附件</div>';
        var parts = cur.attach.map(function (a) {
          return attachIcon(a.type) + ' ' + attachLabel(a.type) + ' ×' + a.qty;
        }).join('  ');
        h += '<div class="mail-attach-list">' + parts + '</div>';
        if (cur.claimed) {
          h += '<span class="mail-claimed">已领取</span>';
        } else {
          h += '<button class="mail-claim" onclick="Game.Mail.claimAttach(\'' + cur.id + '\')">领取附件</button>';
        }
        h += '</div>';
      }
      // 系统邮件不允许回复
      if (!cur.system && cur.from) {
        h += '<div class="mail-reply-bar">';
        h += '<input class="mail-reply-input" id="mailReplyInput" placeholder="回复 ' + escapeHtml(cur.from) + '..." />';
        h += '<button class="mail-reply-btn" onclick="Game.Mail._sendReply()">发送</button>';
        h += '</div>';
      }
      h += '</div>';
    } else if (view === 'compose') {
      h += '<div class="mail-toolbar"><span class="mail-back" onclick="Game.Mail.back()">← 返回</span></div>';
      h += '<div class="mail-compose-box">';
      h += '<div class="mail-field"><label>收件人:</label>';
      h += '<input class="mail-input" id="mailTo" list="mailToList" placeholder="输入玩家名" autocomplete="off" />';
      h += '<datalist id="mailToList">';
      var hist = loadHistory();
      if (hist.length === 0) {
        h += '<option value="(暂无历史, 请直接输入玩家名)" disabled></option>';
      } else {
        for (var n = 0; n < hist.length; n++) {
          h += '<option value="' + escapeHtml(hist[n]) + '"></option>';
        }
      }
      h += '</datalist></div>';
      if (hist.length > 0) {
        h += '<div class="mail-recent-hint">📜 近期收件人: '
          + hist.slice(0, 5).map(function (x) { return escapeHtml(x); }).join('、')
          + (hist.length > 5 ? ' 等' : '') + '</div>';
      }
      h += '<div class="mail-field"><label>主题:</label><input class="mail-input" id="mailSubject" placeholder="邮件主题" maxlength="40" /></div>';
      h += '<div class="mail-field mail-body-field"><label>正文:</label><textarea class="mail-textarea" id="mailBody" placeholder="写点什么..."></textarea></div>';
      h += '<div class="mail-compose-actions"><button class="mail-send" onclick="Game.Mail._sendCompose()">📨 发送</button></div>';
      h += '</div>';
    }

    h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
    viewEl.innerHTML = h;
    restoreFormState(formState);
  }

  G.Mail = Mail;
  Core.views.mail = function (v) { Mail.renderView(v); };
})(window.Game);
