/* global window, document */
(function (G) {
  'use strict';

  function element(id) { return document.getElementById(id); }
  function escape(value) { return G.escapeHtml(String(value == null ? '' : value)); }
  function deadline(value) {
    return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) + '（北京时间）';
  }
  function requestId() {
    if (window.crypto && window.crypto.randomUUID) return window.crypto.randomUUID();
    return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
  }

  var Account = {
    recovery: null,
    notice: null,
    submitting: false,
    uncertain: false,
    opening: false,

    /** 截止时间来自服务端；结果卡片持续显示，不依赖短暂 Toast。 */
    loginPanel: function () {
      if (!this.notice) return '';
      return '<div class="panel account-result" role="status"><b>' + escape(this.notice.title) + '</b>' +
        (this.notice.recoverUntil ? '<p>可恢复至：' + escape(deadline(this.notice.recoverUntil)) + '</p>' : '') +
        '<p>' + escape(this.notice.message) + '</p></div>';
    },

    clearNotice: function () { this.notice = null; this.recovery = null; },

    /** 清空账号会话并让已有请求过期；主题等非账号偏好继续保留。 */
    endSession: function (notice, preserveStoredToken) {
      this.notice = notice || { title: '登录状态已失效', message: '请重新登录验证账号状态。' };
      this.recovery = null;
      if (G.WS) G.WS.disconnect();
      if (!preserveStoredToken) G.API.clearToken();
      else G.API.client.invalidateCityRequests();
      if (G.World && G.World.stopAlertTimer) G.World.stopAlertTimer();
      if (G.World && G.World._warTimer) { clearInterval(G.World._warTimer); G.World._warTimer = null; }
      if (G.WorldView && G.WorldView.invalidate) G.WorldView.invalidate();
      if (G.Main && G.Main.closePlayerDrawer) G.Main.closePlayerDrawer();
      if (G.Wounded && G.Wounded.stop) G.Wounded.stop();
      G.state = null;
      G.Core.state = null;
      G.Core.history = [];
      G.Main.guestMode = false;
      G.Core.route = 'login';
      G.Core.render();
    },

    open: function () {
      if (this.opening || element('disableAccountModal')) return;
      if (!G.API.isLoggedIn() || G.Main.guestMode || G.API.getUsername().indexOf('游客_') === 0) {
        G.toast('请使用正式账号登录后操作');
        return;
      }
      var self = this;
      var token = G.API.getToken();
      this.opening = true;
      G.API.deletionPreview().then(function (preview) {
        if (token !== G.API.getToken()) return;
        self.preview = preview;
        self.username = preview.username;
        self.requestId = requestId();
        self.uncertain = false;
        self.sessionChanged = false;
        self.showModal(preview);
      }).catch(function (err) { G.toast(err.message || '无法加载注销信息'); })
        .finally(function () { self.opening = false; });
    },

    /** 模态框独立于实时重绘，限制焦点且不允许回车触发危险提交。 */
    showModal: function (preview) {
      var self = this;
      this.previousFocus = document.activeElement;
      var mask = document.createElement('div');
      mask.id = 'disableAccountModal';
      mask.className = 'modal-mask account-modal';
      mask.innerHTML = '<div class="modal-card account-card" role="dialog" aria-modal="true" aria-labelledby="accountTitle">' +
        '<div class="modal-title" id="accountTitle">注销账号</div>' +
        '<div class="modal-body"><p>当前账号：<b>' + escape(preview.username) + '</b></p>' +
        '<p>申请后将退出所有设备。你有 <b>' + escape(preview.cooldownDays) + ' 天</b>时间恢复账号，到期后游戏进度无法找回。</p>' +
        '<ul class="account-consequences"><li>清理全部 ' + escape(preview.cityCount) + ' 座城池、部队、军官、装备、道具和资源，包括金币、钻石余额。</li>' +
        '<li>恢复期内战局照常运行，城池仍可能遭到攻击；恢复不会回滚损失。</li>' +
        '<li>注销完成后，该用户名不可重新注册。</li></ul>' +
        (preview.singleMemberGuild ? '<p>你是单人军团团长，到期后军团将一并解散。</p>' : '') +
        (preview.blocker ? '<p class="account-error">' + escape(preview.blocker) + '</p><button class="btn" id="accountGuild">前往军团管理</button>' : '') +
        '<div class="edit-row"><label for="disablePassword">当前密码</label><input id="disablePassword" class="qty" type="password" maxlength="64" autocomplete="current-password" aria-describedby="disableMsg"></div>' +
        '<div class="edit-row"><label for="disableConfirm">确认操作</label><input id="disableConfirm" class="qty" maxlength="16" placeholder="请输入“确认注销”" autocomplete="off"></div>' +
        '<div id="disableMsg" role="status" aria-live="polite">输入密码和“确认注销”以继续。</div></div>' +
        '<div class="btn-row"><button class="btn" id="accountCancel">暂不注销</button>' +
        '<button class="btn account-danger" id="accountSubmit" disabled>申请注销</button></div></div>';
      document.body.appendChild(mask);
      element('accountCancel').onclick = function () { self.close(); };
      element('accountSubmit').onclick = function () { self.submit(); };
      if (element('accountGuild')) element('accountGuild').onclick = function () { self.close(); G.go('guild'); };
      element('disablePassword').oninput = element('disableConfirm').oninput = function () { self.updateButton(); };
      mask.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') { event.preventDefault(); self.close(); }
        if (event.key === 'Enter' && event.target.tagName === 'INPUT') event.preventDefault();
        if (event.key === 'Tab') {
          var items = mask.querySelectorAll('button:not(:disabled), input:not(:disabled)');
          if (!items.length) { event.preventDefault(); return; }
          var first = items[0], last = items[items.length - 1];
          if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
          else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        }
      });
      element('accountCancel').focus();
    },

    close: function () {
      if (this.submitting) return;
      var mask = element('disableAccountModal');
      if (mask) {
        if (element('disablePassword')) element('disablePassword').value = '';
        mask.remove();
      }
      this.uncertain = false;
      if (this.previousFocus && this.previousFocus.isConnected) this.previousFocus.focus();
    },

    updateButton: function () {
      var submit = element('accountSubmit');
      if (!submit) return;
      submit.disabled = this.submitting || !!this.preview.blocker || !element('disablePassword').value ||
        (!this.uncertain && element('disableConfirm').value.trim() !== '确认注销');
      submit.textContent = this.submitting ? '正在确认…' : (this.uncertain ? '查询注销状态' : '申请注销');
      element('accountCancel').disabled = this.submitting;
      element('disablePassword').disabled = this.submitting;
      element('disableConfirm').disabled = this.submitting;
    },

    accepted: function (data) {
      this.submitting = false;
      this.close();
      try { localStorage.removeItem('wargame_avatar_' + this.username); } catch (ignore) {}
      this.endSession({ title: data.status === 'RECOVERY_EXPIRED' ? '已超过恢复期限' : '注销申请已受理',
        recoverUntil: data.status === 'RECOVERY_EXPIRED' ? null : data.recoverUntil,
        message: data.status === 'RECOVERY_EXPIRED' ? '该账号无法恢复，游戏数据将自动清理。' :
          '在截止时间之前，可通过登录入口验证身份并选择恢复账号。到期后无法恢复，游戏数据将自动清理。' });
    },

    /** 响应丢失只执行凭据验证后的状态查询，绝不重放注销写请求。 */
    submit: function () {
      if (this.submitting) return;
      var password = element('disablePassword').value;
      var confirm = element('disableConfirm').value.trim();
      if (!password || (!this.uncertain && confirm !== '确认注销') || this.preview.blocker) return;
      var self = this;
      var username = this.username;
      this.submitting = true;
      this.updateButton();
      function query() { return G.API.deletionStatus(username, password); }
      var operation = this.uncertain ? query() : G.API.disableAccount(password, confirm, this.requestId).catch(function (err) {
        if (!err.uncertain && err.status !== 401) throw err;
        self.uncertain = true;
        return query();
      });
      operation.then(function (data) {
        if (self.sessionChanged) return;
        if (data.status === 'PENDING_DELETION' || data.status === 'RECOVERY_EXPIRED') {
          self.accepted(data);
        } else {
          self.uncertain = false;
          element('disableMsg').textContent = '账号仍为正常状态，未查到注销申请。可重新确认后提交。';
        }
      }).catch(function (err) {
        if (self.sessionChanged) return;
        if (err.code === 'GUILD_TRANSFER_REQUIRED') self.preview.blocker = err.message;
        if (element('disableMsg')) element('disableMsg').textContent = self.uncertain ?
          '操作结果待确认。请点击“查询注销状态”，或稍后通过登录入口验证。' : (err.message || '操作失败');
      }).finally(function () {
        password = '';
        self.submitting = false;
        if (self.sessionChanged) {
          self.close();
          self.endSession({ title: '账号已切换', message: '请通过原账号登录入口确认注销状态。' }, true);
          return;
        }
        self.updateButton();
      });
    },

    showRecovery: function (data) {
      this.recovery = data;
      this.notice = null;
      this.recovering = false;
      G.Core.route = 'login';
      G.Core.render();
    },

    recoveryPanel: function () {
      var data = this.recovery;
      return '<div class="title">恢复账号</div><div class="panel account-result"><b>' + escape(data.username) + ' 正在申请注销</b>' +
        '<p>可恢复至：' + escape(deadline(data.recoverUntil)) + '</p>' +
        '<p>恢复后可继续使用当前游戏进度，恢复期内的战斗结果仍然有效。</p>' +
        '<div class="btn-row"><button class="btn" id="keepDeletion" onclick="Game.Account.keepDeletion()">保持注销并返回</button>' +
        '<button class="btn ok" id="recoverAccount" onclick="Game.Account.recover()">恢复账号并登录</button></div>' +
        '<p id="recoveryMsg" role="status" aria-live="polite"></p></div>';
    },

    keepDeletion: function () {
      if (this.recovering) return;
      this.recovery = null;
      G.Core.render();
    },

    recover: function () {
      if (this.recovering || !this.recovery) return;
      var self = this;
      this.recovering = true;
      element('recoverAccount').disabled = element('keepDeletion').disabled = true;
      element('recoveryMsg').textContent = '正在恢复…';
      G.API.recoverAccount(this.recovery.recoveryToken).then(function () {
        self.clearNotice();
        G.Main.guestMode = false;
        return G.Main.startGame();
      }).catch(function (err) {
        if (element('recoveryMsg')) element('recoveryMsg').textContent = err.uncertain ?
          '恢复结果待确认，请返回登录重新验证。不会自动重复恢复操作。' : err.message;
      }).finally(function () {
        self.recovering = false;
        if (element('recoverAccount')) element('recoverAccount').disabled = false;
        if (element('keepDeletion')) element('keepDeletion').disabled = false;
      });
    }
  };
  G.Account = Account;

  if (window.addEventListener) window.addEventListener('storage', function (event) {
    if (event.key !== G.API.tokenKey || event.oldValue === event.newValue) return;
    if (Account.submitting) {
      if (event.newValue) { Account.sessionChanged = true; G.API.client.invalidateCityRequests(); }
      return;
    }
    Account.close();
    // 其他标签登录新账号时只清本页状态，不擦掉它刚写入的新 Token。
    Account.endSession({ title: '账号状态已更新', message: '其他页面已退出或切换账号，请重新进入。' }, true);
  });
})(window.Game);
