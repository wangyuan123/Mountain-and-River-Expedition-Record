/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';
  G.gameNow = G.gameNow || function () { return Date.now(); };

  var Core = G.Core;

  // ============================================================
  //  本地状态 (内存, 不再持久化到 localStorage; 后端是单一来源)
  // ============================================================
  var mails = [];          // 当前 folder 下的邮件
  var curFolder = 'inbox'; // inbox | outbox | system | unread
  var curMailId = null;
  var view = 'list';       // list | read | compose
  var unreadNum = 0;       // 缓存: 用于导航栏红点
  var nextCursor = null;
  var hasMore = false;
  var detailRequestId = 0;
  var listRequestId = 0;
  var currentDetail = null;
  var currentCursor = null;
  var previousCursors = [];
  var pageLoaded = false;
  var identity = null;
  var listInFlight = null; // 并发去重: 同一 folder 只发一次 list
  var expiryTimer = null;
  var lastLoadedAt = 0;
  var viewEl = null;       // 当前 view 元素 (由 Core 传入)

  // ============================================================
  //  收件人历史 (本地 UX 状态, 与游戏数据分离; 按账号隔离)
  // ============================================================
  var HISTORY_MAX = G.Constants.mailHistoryMax;

  function _userKey() {
    try { return (G.API && G.API.getUsername && G.API.getUsername()) || 'guest'; }
    catch (e) { return 'guest'; }
  }
  function _historyKey() { return 'wg.mail.recipients.v1.' + (G.Servers ? G.Servers.current().id : 'jiangsu-1') + '.' + _userKey(); }

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
    if (G.DATA && G.DATA.resources && G.DATA.resources[type]) return G.resourceIconHtml(type);
    if (G.DATA && G.DATA.units && G.DATA.units[type]) return '🪖';
    return '🎁';
  }
  function attachLabel(type) {
    var resMap = { gold: '黄金', diamond: '钻石', food: '粮食', steel: '钢铁', oil: '石油', rare: '稀矿' };
    if (resMap[type]) return resMap[type];
    if (G.unitDisplayName) return G.unitDisplayName(type);
    if (G.DATA && G.DATA.units && G.DATA.units[type]) return G.DATA.units[type].name || type;
    return type;
  }
  function toast(msg) { if (G.toast) G.toast(msg); }
  function surrenderRemaining(deadline) {
    var ms = Math.max(0, Number(deadline || 0) - G.gameNow());
    var hours = Math.floor(ms / 3600000), mins = Math.floor((ms % 3600000) / 60000);
    return hours + '小时' + pad2(mins) + '分';
  }

  // ============================================================
  //  数据加载
  // ============================================================
  function contextKey() { return G.API.retentionContext ? G.API.retentionContext() : G.API.getToken ? G.API.getToken() : _userKey(); }
  function resetRetentionCache() {
    if (expiryTimer && typeof clearTimeout === 'function') clearTimeout(expiryTimer);
    expiryTimer = null; lastLoadedAt = 0;
    mails = []; currentDetail = null; curMailId = null; unreadNum = 0;
    nextCursor = null; currentCursor = null; previousCursors = []; hasMore = false;
    pageLoaded = false; listInFlight = null; view = 'list'; curFolder = 'inbox';
    detailRequestId++; listRequestId++; identity = null;
  }
  function ensureIdentity() {
    var key = contextKey();
    if (identity !== key) { resetRetentionCache(); identity = key; }
    return key;
  }

  /** 只替换当前页；请求序号与账号大区一起校验，旧文件夹响应不能覆盖新页面。 */
  function loadFolder(folder, cursor) {
    var key = ensureIdentity();
    curFolder = folder;
    var request = ++listRequestId;
    currentDetail = null; detailRequestId++;
    var pending = G.API.listMail(folder, cursor || null, 20).then(function (data) {
      if (contextKey() !== key || request !== listRequestId || curFolder !== folder) return;
      mails = (data && data.mails) || [];
      currentCursor = cursor || null;
      pageLoaded = true;
      lastLoadedAt = Date.now();
      nextCursor = data && data.nextCursor || null;
      hasMore = !!(data && data.hasMore);
      unreadNum = (data && typeof data.unread === 'number') ? data.unread : mails.filter(function (m) {
        return folder === 'inbox' && !m.read;
      }).length;
      // 刷新导航栏红点 (Core.go 时 navbar 是在 loadFolder 之前渲染的, 此时 unread=0)
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
      render();
    }).catch(function (err) {
      if (contextKey() !== key || request !== listRequestId) return;
      toast('加载电报失败: ' + (err.message || err));
    }).finally(function () { if (listInFlight === pending) listInFlight = null; });
    listInFlight = pending;
    return listInFlight;
  }

  function loadMore() {
    if (!hasMore || !nextCursor || listInFlight) return Promise.resolve();
    previousCursors.push(currentCursor);
    if (previousCursors.length > 100) previousCursors.shift();
    return loadFolder(curFolder, nextCursor);
  }

  function refreshUnread() {
    var key = ensureIdentity();
    return G.API.mailUnread().then(function (data) {
      if (contextKey() !== key) return;
      unreadNum = (data && data.unread) || 0;
      // 触发导航栏重绘
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
    }).catch(function () { /* 静默 */ });
  }

  // ============================================================
  //  公共 API (挂在 G.Mail)
  // ============================================================
  var Mail = {
    resetRetentionCache: resetRetentionCache,
    /** 未读数, 同步返回缓存值 (由后端推送 / folder 加载更新) */
    unread: function () { ensureIdentity(); return unreadNum; },

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
      currentDetail = null;
      detailRequestId++;
      loadFolder(curFolder, currentCursor);
    },

    /** 切换文件夹 */
    setFolder: function (f) {
      ensureIdentity();
      curFolder = f;
      view = 'list';
      curMailId = null;
      detailRequestId++;
      mails = [];
      currentDetail = null; previousCursors = [];
      loadFolder(f);
    },

    /** 打开一封 (同时调用后端标记已读) */
    open: function (id) {
      var key = ensureIdentity();
      currentDetail = null;
      // onclick 模板把 m.id 用单引号包起来 -> JS 端收到的是字符串; 但后端 Long 序列化为数字,
      // 严格相等 (===) 会失败, 导致 view='read' 立即被 render() 的回退逻辑重置回 'list' (点击无效)
      curMailId = (typeof id === 'string' && /^-?\d+$/.test(id)) ? Number(id) : id;
      view = 'read';
      var requestId = ++detailRequestId;
      // 本地先翻为已读, 避免 UI 闪烁
      for (var i = 0; i < mails.length; i++) {
        if (mails[i].id === curMailId) { mails[i].read = true; break; }
      }
      render();
      var current = mails.find(function (m) { return m.id === curMailId; });
      currentDetail = current ? Object.assign({}, current) : null;
      if (current && G.API.getMailDetail) {
        G.API.getMailDetail(curMailId).then(function (detail) {
          if (contextKey() !== key || requestId !== detailRequestId || view !== 'read' || String(curMailId) !== String(current.id)) return;
          currentDetail = Object.assign({}, detail, currentDetail && currentDetail.surrender ? { surrender: currentDetail.surrender } : {});
          render();
        }).catch(function (err) {
          if (contextKey() === key && requestId === detailRequestId && view === 'read') {
            toast(err.message || '邮件详情加载失败');
            if (/已过期|不存在/.test(err.message || '')) { mails = mails.filter(function (m) { return String(m.id) !== String(curMailId); }); currentDetail = null; view = 'list'; curMailId = null; refreshUnread(); render(); }
          }
        });
      }
      if (current && current.surrenderOfferId) {
        G.API.getSurrenderOffer(current.surrenderOfferId).then(function (offer) {
          if (contextKey() !== key || requestId !== detailRequestId || view !== 'read' || curMailId !== current.id) return;
          if (currentDetail) currentDetail.surrender = offer; render();
        }).catch(function () { /* stale or cancelled offer */ });
      }
      // 异步标后端
      G.API.markMailRead(curMailId).then(function () { if (contextKey() === key) return refreshUnread(); }).catch(function () { /* ignore */ });
    },

    /**
     * 处理劝降答复；接受前再次确认 24 小时冻结和完整城内资产移交范围。
     * @param {number} offerId - 劝降邀请 ID
     * @param {string} decision - accept 或 reject
     */
    respondSurrender: function (offerId, decision) {
      var key = ensureIdentity(), mailId = curMailId;
      function submit() {
        if (contextKey() !== key) return;
        G.API.respondSurrender(offerId, decision).then(function (data) {
          if (contextKey() !== key) return;
          toast(decision === 'accept' ? '已接受劝降，城市将在 24 小时后完成移交' : '已拒绝劝降');
          var current = currentDetail;
          if (current && String(current.id) === String(mailId)) current.surrender = data;
          render();
        }).catch(function (err) { if (contextKey() === key) toast(err.message || '处理劝降失败'); });
      }
      if (decision === 'accept') {
        var current = currentDetail;
        var offer = current && current.surrender;
        var city = offer ? (offer.cityName + '（' + offer.cityX + ',' + offer.cityY + '）') : '该城市';
        G.World.showConfirm({
          title: '接受劝降确认',
          message: '确认接受对 ' + city + ' 的劝降？接受后城市立即冻结 24 小时，倒计时结束后永久移交。',
          subMessage: '建筑、普通资源、部队、城防、军官及军官装备、本城所属野地随城转移；双方均不能撤销。账号科技、军衔、声望、任务、军团关系、其他城市领地、钻石和账号道具不转移。',
          danger: true,
          okText: '接受并开始移交',
          onConfirm: submit
        });
        return;
      }
      submit();
    },

    /** 发送 (compose 页调用) */
    send: function (to, subject, body, attach) {
      var key = ensureIdentity();
      return G.API.sendMail(to, subject, body, attach).then(function (data) {
        if (contextKey() === key) toast('已发送给 ' + to);
        return data;
      });
    },

    /** 领取附件，成功后同步本地领取状态并刷新详情。 */
    claimAttach: function (id) {
      var key = ensureIdentity();
      G.API.claimMailAttach(id).then(function (data) {
        if (contextKey() !== key) return;
        if (data && data.success) {
          toast('附件已领取');
          // 按钮传入字符串 ID，接口返回数字 ID；统一比较类型才能更新对应邮件。
          for (var i = 0; i < mails.length; i++) {
            if (String(mails[i].id) === String(id)) { mails[i].claimed = true; break; }
          }
          if (currentDetail && String(currentDetail.id) === String(id)) currentDetail.claimed = true;
          render();
        } else if (data) {
          if (/过期/.test(data.message || '')) {
            var expiredMail = mails.find(function (mail) { return String(mail.id) === String(id); });
            if (expiredMail) expiredMail.attachmentExpired = true;
            if (currentDetail && String(currentDetail.id) === String(id)) currentDetail.attachmentExpired = true;
            render();
          }
          toast(data.message || data.error || '领取失败');
        }
      }).catch(function (err) {
        if (contextKey() === key) toast('领取失败: ' + (err.message || err));
      });
    },

    /** 删除一封 (仅收件人能删) */
    delete: function (id) {
      var key = ensureIdentity();
      G.API.deleteMail(id).then(function () {
        if (contextKey() !== key) return;
        toast('已删除');
        view = 'list';
        curMailId = null;
        detailRequestId++;
        return loadFolder(curFolder);
      }).then(function () { if (contextKey() === key) return refreshUnread(); }).catch(function (err) {
        if (contextKey() === key) toast('删除失败: ' + (err.message || err));
      });
    },

    loadMore: loadMore,
    previousPage: function () {
      if (listInFlight || !previousCursors.length) return;
      return loadFolder(curFolder, previousCursors.pop());
    },
    firstPage: function () { previousCursors = []; return loadFolder(curFolder, null); },

    // 内部: 写信 / 回复共用
    _sendCompose: function () {
      var key = ensureIdentity();
      var to = (document.getElementById('mailTo') || {}).value;
      var subject = (document.getElementById('mailSubject') || {}).value;
      var body = (document.getElementById('mailBody') || {}).value;
      if (!to || !subject) { toast('收件人与主题不可为空'); return; }
      Mail.send(to, subject, body, []).then(function () {
        if (contextKey() !== key) return;
        pushHistory(to);  // 发送成功才入历史 (失败的不要污染)
        view = 'list';
        loadFolder('outbox').then(refreshUnread);
      }).catch(function (err) {
        if (contextKey() === key) toast(err.message || '发送失败');
      });
    },
    _sendReply: function () {
      var key = ensureIdentity();
      var el = document.getElementById('mailReplyInput');
      var body = el ? el.value : '';
      if (!body) { toast('请输入回复内容'); return; }
      var cur = null;
      for (var i = 0; i < mails.length; i++) if (mails[i].id === curMailId) { cur = mails[i]; break; }
      if (!cur) return;
      Mail.send(cur.from, 'Re: ' + cur.subject, body, []).then(function () {
        if (contextKey() !== key) return;
        // 回复也视为对收件人的一次接触, 收入历史
        pushHistory(cur.from);
        toast('已回复给 ' + cur.from);
      }).catch(function (err) {
        if (contextKey() === key) toast(err.message || '回复失败');
      });
    },

    // ============================================================
    //  视图渲染
    // ============================================================
    renderView: function (v) {
      ensureIdentity();
      var reentered = viewEl !== v;
      viewEl = v;
      // 触发初次加载 (默认 inbox)
      if ((!pageLoaded || reentered && view === 'list' || view === 'list' && Date.now() - lastLoadedAt >= 60000) && !listInFlight) {
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
    var now = Date.now(), nextExpiry = Infinity;
    // 离线停留的正文、领取按钮和列表也遵守真实期限；只保留一个到期定时器。
    mails = mails.filter(function (mail) { return !mail.retentionExpiresAt || mail.retentionExpiresAt > now; });
    if (currentDetail && currentDetail.retentionExpiresAt && currentDetail.retentionExpiresAt <= now) {
      currentDetail = null; curMailId = null; view = 'list'; detailRequestId++;
    }
    mails.concat(currentDetail ? [currentDetail] : []).forEach(function (mail) {
      if (mail.retentionExpiresAt) nextExpiry = Math.min(nextExpiry, mail.retentionExpiresAt);
      if (!mail.claimed && mail.attachmentExpiresAt) {
        if (mail.attachmentExpiresAt <= now) mail.attachmentExpired = true;
        else nextExpiry = Math.min(nextExpiry, mail.attachmentExpiresAt);
      }
    });
    if (expiryTimer && typeof clearTimeout === 'function') clearTimeout(expiryTimer);
    expiryTimer = null;
    if (isFinite(nextExpiry) && typeof setTimeout === 'function') expiryTimer = setTimeout(function () { expiryTimer = null; render(); refreshUnread(); }, Math.min(2147483647, Math.max(1, nextExpiry - now)));
    // 在 innerHTML 重写前抓取当前输入, 之后还原, 防止 WS tick / 顶层 render 把表单刷掉
    var formState = captureFormState();
    var h = '';
    h += '<div class="title">- 战时电报 -</div>';

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
      h += '<span class="mail-compose" onclick="Game.Mail.compose()">✏️ 发电报</span>';
      h += '</div>';
      h += '<div class="mail-retention-note">已读且附件处理完、业务结束后保留 30 天；无待领附件的未读邮件保留 60 天。未领资产附件受保护，限时赠礼以领取截止时间为准；到期也会从发件箱移除。</div>';

      if (!mails.length) {
        h += '<div class="mail-empty">📭 此文件夹暂无电报</div>';
      } else {
        h += '<div class="mail-list">';
        for (var j = 0; j < mails.length; j++) {
          var m = mails[j];
          var readCls = m.read ? ' read' : ' unread';
          var sysCls = m.system ? ' sys' : '';
          var attachHtml = !m.claimed && !m.attachmentExpired && (m.hasAttachment || (m.attach && m.attach.length)) ? '<span class="mail-attach">🎁</span>' : '';
          var other = (curFolder === 'inbox' || curFolder === 'system' || curFolder === 'unread') ? m.from : m.to;
          var subject = escapeHtml(m.subject || '(无主题)');
          var previewSource = m.bodyPreview != null ? m.bodyPreview : (m.body || '');
          var preview = escapeHtml(previewSource.slice(0, 36));
          h += '<div class="mail-item' + readCls + sysCls + '" onclick="Game.Mail.open(\'' + m.id + '\')">'
            + '<div class="mail-item-line1">'
            +   '<span class="mail-from">' + escapeHtml(other) + '</span>'
            +   '<span class="mail-time">' + fmtShort(m.ts) + '</span>'
            + '</div>'
            + '<div class="mail-item-line2">'
            +   '<span class="mail-subject">' + (m.read ? '' : '● ') + subject + '</span>'
            +   attachHtml
            + '</div>'
            + '<div class="mail-preview">' + preview + (previewSource.length > 36 ? '…' : '') + (m.attachmentExpired ? '（附件已过期）' : '') + '</div>'
            + '</div>';
        }
        h += '</div>';
      }
      if (previousCursors.length) h += '<button class="btn" onclick="Game.Mail.previousPage()">上一页</button>';
      if (currentCursor) h += '<button class="btn" onclick="Game.Mail.firstPage()">最新邮件</button>';
      if (hasMore) h += '<button class="btn" onclick="Game.Mail.loadMore()">下一页</button>';
    } else if (view === 'read') {
      var cur = currentDetail;
      if (!cur) for (var k = 0; k < mails.length; k++) if (mails[k].id === curMailId) { cur = mails[k]; break; }
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
      if (cur.attachmentExpiresAt && !cur.claimed) h += '<div class="mail-detail-meta">附件领取截止：' + fmtTime(cur.attachmentExpiresAt) + '</div>';
      if (cur.surrender) {
        var offer = cur.surrender;
        var deadline = offer.status === 'PENDING' ? offer.responseExpiresAt : offer.transferDeadlineAt;
        var phase = offer.status === 'PENDING' ? '答复剩余 ' + surrenderRemaining(deadline) : offer.status === 'ACCEPTED' ? '移交剩余 ' + surrenderRemaining(deadline) : '状态：' + offer.status;
        h += '<div class="mail-attach-box surrender-offer-box"><b>劝降流程</b><div class="mail-attach-list">' + escapeHtml(phase) + '</div>';
        if (offer.viewerCanRespond) {
          h += '<button class="mail-claim" onclick="Game.Mail.respondSurrender(' + offer.offerId + ',\'accept\')">接受劝降</button>';
          h += '<button class="mail-del" onclick="Game.Mail.respondSurrender(' + offer.offerId + ',\'reject\')">拒绝劝降</button>';
        }
        h += '</div>';
      }
      if (cur.attach && cur.attach.length) {
        h += '<div class="mail-attach-box">';
        h += '<div class="mail-attach-title">📎 附件</div>';
        var parts = cur.attach.map(function (a) {
          return attachIcon(a.type) + ' ' + attachLabel(a.type) + ' ×' + a.qty;
        }).join('  ');
        h += '<div class="mail-attach-list">' + parts + '</div>';
        if (cur.attachmentExpired) {
          h += '<span class="mail-claimed">附件已过期</span>';
        } else if (cur.claimed) {
          h += '<span class="mail-claimed">已领取</span>';
        } else {
          h += '<button class="mail-claim" onclick="Game.Mail.claimAttach(\'' + cur.id + '\')">领取附件</button>';
        }
        h += '</div>';
      } else if (cur.attachmentExpired) {
        h += '<div class="mail-attach-box"><div class="mail-attach-title">附件已过期</div></div>';
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
      h += '<div class="mail-field"><label>主题:</label><input class="mail-input" id="mailSubject" placeholder="电报主题" maxlength="40" /></div>';
      h += '<div class="mail-field mail-body-field"><label>正文:</label><textarea class="mail-textarea" id="mailBody" placeholder="写点什么..."></textarea></div>';
      h += '<div class="mail-compose-actions"><button class="mail-send" onclick="Game.Mail._sendCompose()">📨 发送电报</button></div>';
      h += '</div>';
    }

    h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
    viewEl.innerHTML = h;
    restoreFormState(formState);
  }

  G.Mail = Mail;
  Core.views.mail = function (v) { Mail.renderView(v); };
})(window.Game);
