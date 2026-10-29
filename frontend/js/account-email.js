/* global window, document */
(function (G) {
  'use strict';
  function el(id) { return document.getElementById(id); }
  function esc(value) { return G.escapeHtml(String(value == null ? '' : value)); }
  function value(id) { return el(id) ? el(id).value : ''; }
  function context() { return G.API.client.serverId + ':' + G.API.getToken(); }
  function email(id) { return value(id).trim().toLowerCase(); }
  function validEmail(address) { return /^[a-z0-9][a-z0-9._-]{0,63}@qq\.com$/.test(address); }
  function passwordError(password, confirmation) {
    var bytes = unescape(encodeURIComponent(password)).length;
    if (!password.trim() || password.length < 6 || password.length > 64 || bytes > 72) return '密码需为 6—64 个字符，且不超过 72 个 UTF-8 字节';
    return password !== confirmation ? '两次输入的密码不一致' : '';
  }
  function field(id, label, type, autocomplete, placeholder, hint) {
    return '<div class="edit-row account-email-row"><label for="' + id + '">' + label + (hint ? '<span class="account-email-label-hint">' + hint + '</span>' : '') +
      '</label><input class="qty account-email-input" id="' + id +
      '" type="' + (type || 'text') + '" autocomplete="' + (autocomplete || 'off') + '" maxlength="' + (type === 'email' ? 71 : 64) +
      '" placeholder="' + (placeholder || '') + '"></div>';
  }
  var Email = {
    data: null, registration: null, modal: null, timers: {},
    /** 切区、切账号和退出时丢弃全部临时凭据，不持久化密码、验证码或换绑凭据。 */
    reset: function () {
      this.clearRegistration(); this.data = null; this.close(true);
      Object.keys(this.timers).forEach(function (id) { clearInterval(Email.timers[id]); });
      this.timers = {};
    },
    clearRegistration: function () { this.registration = null; if (el('registerEmailCode')) el('registerEmailCode').value = ''; },
    countdown: function (id, receipt) {
      clearInterval(this.timers[id]);
      var until = Date.now() + Math.max(0, receipt.resendAt - receipt.serverNow);
      function update() {
        var button = el(id), remaining = Math.max(0, Math.ceil((until - Date.now()) / 1000));
        if (!button) { clearInterval(Email.timers[id]); return; }
        button.disabled = remaining > 0 || !!(Email.modal && (Email.modal.busy || Email.modal.uncertain));
        button.textContent = remaining ? remaining + ' 秒后重发' : '获取验证码';
        if (!remaining) clearInterval(Email.timers[id]);
      }
      this.timers[id] = setInterval(update, 1000); update();
    },
    sendRegistration: function () {
      if (this.sendingRegistration) return;
      var username = value('loginUser').trim(), address = email('registerEmail'), scope = context();
      if (username.length < 3 || username.length > 32) { G.Main.showLoginMsg('请先填写 3—32 位用户名', true); return; }
      if (!validEmail(address)) { G.Main.showLoginMsg('请输入完整的 @qq.com 邮箱', true); return; }
      this.sendingRegistration = true; el('registerEmailSend').disabled = true;
      G.API.accountEmail('/register/email-code', { username: username, email: address }).then(function (receipt) {
        if (scope !== context() || username !== value('loginUser').trim() || address !== email('registerEmail')) return;
        Email.registration = { username: username, email: address, server: G.API.client.serverId,
          challengeId: receipt.challengeId, expiresAt: Date.now() + receipt.expiresAt - receipt.serverNow };
        Email.countdown('registerEmailSend', receipt);
        G.Main.showLoginMsg('发送请求已提交，请检查邮箱及垃圾箱。验证码 5 分钟内有效。', false);
      }).catch(function (error) { if (scope === context()) G.Main.showLoginMsg(error.message || '发送失败，请稍后重试', true); })
        .finally(function () { Email.sendingRegistration = false; if (!Email.registration && el('registerEmailSend')) el('registerEmailSend').disabled = false; });
    },
    /** 注册资料改变后不能继续沿用先前签发的验证流程。 */
    registrationProof: function () {
      var proof = this.registration;
      if (!proof || proof.username !== value('loginUser').trim() || proof.email !== email('registerEmail') ||
          proof.server !== G.API.client.serverId || proof.expiresAt <= Date.now()) throw new Error('请先为当前用户名和 QQ 邮箱获取验证码');
      var code = value('registerEmailCode').trim();
      if (!/^\d{6}$/.test(code)) throw new Error('请输入 6 位邮箱验证码');
      return { email: proof.email, challengeId: proof.challengeId, emailCode: code };
    },
    validatePassword: passwordError,
    loadStatus: function () {
      var scope = context();
      return G.API.accountEmail('/email').then(function (data) { if (scope === context()) Email.data = data; return data; });
    },
    onLogin: function () {
      if (!G.API.isLoggedIn() || G.API.getUsername().indexOf('游客_') === 0) return;
      var scope = context();
      this.loadStatus().then(function (data) {
        if (scope !== context() || data.emailBound) return;
        var key = 'wargame_email_reminder:' + G.API.client.serverId + ':' + G.API.getUsername();
        var day = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' });
        try { if (window.localStorage.getItem(key) === day) return; window.localStorage.setItem(key, day); } catch (ignore) {}
        G.toast('建议在设置 → 账号与安全中绑定 QQ 邮箱，忘记密码时可找回账号');
      }).catch(function () {});
    },
    settings: function () {
      if (G.API.getUsername().indexOf('游客_') === 0) return '';
      var data = this.data;
      return '<div class="settings-account-sub-row"><span>QQ 邮箱：' + esc(data ? (data.emailBound ? data.maskedEmail : '未绑定') : '待查询') +
        '</span> <button type="button" class="btn sm" onclick="Game.AccountEmail.openSettings()">' + (data && data.emailBound ? '更换邮箱' : '绑定 / 查看') + '</button></div>';
    },
    openSettings: function () {
      var scope = context();
      this.loadStatus().then(function (data) { if (scope === context()) Email.open(data.emailBound ? 'change' : 'bind'); })
        .catch(function (error) { G.toast(error.message || '无法读取邮箱状态'); });
    },
    /** 弹窗独立于游戏重绘，异步结果必须属于同一账号、大区和弹窗实例。 */
    open: function (mode) {
      this.close(true);
      var state = { mode: mode, stage: 'old', scope: context(), busy: false, challenge: null, changeToken: null, previousFocus: document.activeElement };
      this.modal = state;
      var mask = document.createElement('div'); mask.id = 'accountEmailModal'; mask.className = 'modal-mask account-email-mask';
      var title = mode === 'reset' ? '找回密码' : mode === 'change' ? '更换 QQ 邮箱' : '绑定 QQ 邮箱';
      var subtitle = mode === 'reset' ? '验证绑定的 QQ 邮箱后重置登录密码' :
        mode === 'change' ? '先验证原邮箱身份，再绑定新的 QQ 邮箱' : '绑定后可用于接收安全通知及找回账号密码';
      var serverName = (G.Servers && typeof G.Servers.current === 'function' && G.Servers.current()) ?
        G.Servers.current().name : (G.API && G.API.client ? G.API.client.serverId : '');

      var body = '<div class="account-email-server-badge">' +
        '<span class="account-email-server-icon">🌐</span>' +
        '<span class="account-email-server-label">当前大区：</span>' +
        '<strong class="account-email-server-name">' + esc(serverName) + '</strong>' +
      '</div>';

      if (mode === 'reset') {
        body += field('securityUsername', '用户名', 'text', 'username', '请输入您的游戏用户名');
      }
      if (mode !== 'reset') {
        body += field('securityPassword', '当前密码', 'password', 'current-password', '请输入当前登录密码以验证身份');
      }
      if (mode === 'change') {
        var masked = (this.data && this.data.maskedEmail) ? this.data.maskedEmail : '';
        body += '<div class="account-email-banner-tip">' +
          '<span>已绑定原邮箱：<strong>' + esc(masked) + '</strong></span>' +
          '<span class="account-email-banner-sub">先验证原邮箱，验证通过后再验证新邮箱。</span>' +
        '</div>';
      }
      body += field('securityEmail', mode === 'change' ? '新 QQ 邮箱' : 'QQ 邮箱', 'email', 'email', mode === 'change' ? '请输入新的 QQ 邮箱' : '例如: 123456@qq.com');

      var codeLabel = mode === 'change' ? '原邮箱验证码' : '邮箱验证码';
      body += '<div class="edit-row account-email-row account-email-code-row">' +
        '<label for="securityCode">' + codeLabel + '</label>' +
        '<div class="account-email-code-group">' +
          '<input class="qty account-email-input" id="securityCode" type="text" autocomplete="one-time-code" maxlength="6" inputmode="numeric" placeholder="6 位数字验证码">' +
          '<button type="button" class="btn account-email-send-btn" id="securitySend">获取验证码</button>' +
        '</div>' +
      '</div>';

      if (mode === 'reset') {
        body += field('securityNewPassword', '新密码', 'password', 'new-password', '请输入 6—64 位新密码', '6—64位');
        body += field('securityConfirmPassword', '确认新密码', 'password', 'new-password', '请再次输入新密码');
        body += '<div class="account-email-support-box">' +
          '<div class="account-email-support-icon">💡</div>' +
          '<div class="account-email-support-main">' +
            '<div class="account-email-support-tip">未绑定邮箱或邮箱无法使用？</div>' +
            '<div class="account-email-support-desc">未绑定邮箱或邮箱无法使用时，请通过官方渠道联系支持。</div>' +
            '<div id="securitySupport" class="account-email-support-action"></div>' +
          '</div>' +
        '</div>';
      }

      var submitText = mode === 'change' ? '验证原邮箱' : mode === 'reset' ? '重置密码' : '确认绑定';

      mask.innerHTML = '<div class="modal-card account-email-card" role="dialog" aria-modal="true" aria-labelledby="securityTitle">' +
        '<div class="account-email-head">' +
          '<div class="account-email-title-wrap">' +
            '<div class="modal-title account-email-title" id="securityTitle">' + title + '</div>' +
            '<div class="account-email-subtitle">' + subtitle + '</div>' +
          '</div>' +
          '<button type="button" class="account-email-close-btn" id="securityCloseBtn" aria-label="关闭">✕</button>' +
        '</div>' +
        '<div class="modal-body account-email-body">' +
          body +
          '<p id="securityMsg" role="status" aria-live="polite" class="account-email-msg"></p>' +
        '</div>' +
        '<div class="modal-foot account-email-foot">' +
          '<button type="button" class="btn account-email-btn-cancel" id="securityCancel">取消</button>' +
          '<button type="button" class="btn ok account-email-btn-submit" id="securitySubmit">' + submitText + '</button>' +
        '</div>' +
      '</div>';

      document.body.appendChild(mask);
      el('securityCode').maxLength = 6; el('securityCode').setAttribute('inputmode', 'numeric');
      el('securitySend').onclick = function () { Email.send(state); };
      el('securitySubmit').onclick = function () { Email.submit(state); };
      el('securityCancel').onclick = function () { Email.close(); };
      if (el('securityCloseBtn')) el('securityCloseBtn').onclick = function () { Email.close(); };
      ['securityUsername', 'securityEmail', 'securityPassword'].forEach(function (id) {
        if (el(id)) el(id).oninput = function () {
          if (id === 'securityEmail' && state.mode === 'change' && state.stage === 'old') return;
          state.challenge = null; el('securityCode').value = '';
        };
      });
      mask.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') { event.preventDefault(); Email.close(); }
        if (event.key === 'Enter') event.preventDefault();
        if (event.key === 'Tab') {
          var controls = mask.querySelectorAll('button:not(:disabled), input:not(:disabled), a[href]');
          var first = controls[0], last = controls[controls.length - 1];
          if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
          else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        }
      });
      mask.addEventListener('click', function (event) {
        if (event.target === mask && !state.busy) {
          Email.close();
        }
      });
      (el('securityUsername') || el('securityPassword')).focus();
      if (mode === 'reset') G.API.accountEmail('/server').then(function (data) {
        if (!Email.active(state)) return;
        var url = data.accountSecurity && data.accountSecurity.supportUrl;
        if (url && /^https?:\/\//.test(url)) el('securitySupport').innerHTML = '<a class="account-email-support-btn" href="' + esc(url) + '" target="_blank" rel="noopener">联系官方客服 ↗</a>';
      }).catch(function () {});
    },
    active: function (state) { return this.modal === state && state.scope === context() && !!el('accountEmailModal'); },
    /** 服务端先断开 WebSocket 时，保留本页正在等待的最终 HTTP 结果。 */
    handleSessionExpiry: function () {
      var state = this.modal;
      if (!state || !state.busy || !this.active(state) || state.mode !== 'change' || state.stage !== 'new') return false;
      state.sessionRevoked = true;
      return true;
    },
    close: function (force) {
      var state = this.modal;
      if (state && state.busy && !force) return;
      var mask = el('accountEmailModal'); if (mask) mask.remove();
      clearInterval(this.timers.securitySend); this.modal = null;
      if (state && state.previousFocus && state.previousFocus.isConnected) state.previousFocus.focus();
      if (!force && state && state.sessionRevoked && state.scope === context()) {
        G.Account.endSession({ title: '登录状态已更新', message: '请重新登录后确认邮箱绑定状态。' });
      }
    },
    message: function (state, text) {
      if (!this.active(state)) return;
      var node = el('securityMsg');
      if (!node) return;
      node.textContent = text || '';
      if (!text) {
        node.className = 'account-email-msg';
        return;
      }
      var isNotice = /成功|已提交|已发送|有效/.test(text);
      node.className = 'account-email-msg ' + (isNotice ? 'account-email-msg-success' : 'account-email-msg-error');
    },
    lock: function (state, busy) {
      state.busy = busy;
      if (!this.active(state)) return;
      el('accountEmailModal').querySelectorAll('input').forEach(function (input) {
        input.disabled = busy || (state.stage === 'new' && (input.id === 'securityPassword' || input.id === 'securityEmail'));
      });
      el('securitySubmit').disabled = busy || !!state.uncertain;
      el('securityCancel').disabled = busy;
      if (el('securityCloseBtn')) el('securityCloseBtn').disabled = busy;
      el('securitySend').disabled = busy || !!state.uncertain || !!(state.resendUntil && state.resendUntil > Date.now());
    },
    send: function (state) {
      if (!this.active(state) || state.busy || state.uncertain) return;
      var address = email('securityEmail'), username = value('securityUsername').trim();
      if ((state.mode !== 'change' || state.stage === 'new') && !validEmail(address)) { this.message(state, '请输入完整的 @qq.com 邮箱'); return; }
      var path, body = {};
      if (state.mode === 'reset') { path = '/password/reset-code'; body = { username: username, email: address }; }
      else if (state.mode === 'bind') { path = '/email/bind-code'; body = { currentPassword: value('securityPassword'), email: address }; }
      else if (state.stage === 'old') { path = '/email/change/old-code'; body = { currentPassword: value('securityPassword') }; }
      else { path = '/email/change/new-code'; body = { changeToken: state.changeToken }; }
      this.lock(state, true);
      G.API.accountEmail(path, body).then(function (receipt) {
        if (!Email.active(state)) return;
        state.challenge = receipt.challengeId; state.address = address; state.username = username;
        state.resendUntil = Date.now() + receipt.resendAt - receipt.serverNow;
        Email.countdown('securitySend', receipt); Email.message(state, receipt.message || '发送请求已提交，请检查邮箱及垃圾箱');
      }).catch(function (error) { Email.message(state, error.message || '发送失败，请重试'); }).finally(function () { Email.lock(state, false); });
    },
    submit: function (state) {
      if (!this.active(state) || state.busy || state.uncertain) return;
      var code = value('securityCode').trim(), address = email('securityEmail');
      if (!state.challenge || !/^\d{6}$/.test(code)) { this.message(state, '请先获取并填写 6 位邮箱验证码'); return; }
      if (!validEmail(address)) { this.message(state, '请输入完整的 @qq.com 邮箱'); return; }
      if (state.mode === 'reset') {
        var error = passwordError(value('securityNewPassword'), value('securityConfirmPassword'));
        if (error) { this.message(state, error); return; }
      }
      var path, body = { challengeId: state.challenge, emailCode: code };
      if (state.mode === 'reset') { path = '/password/reset'; Object.assign(body, { username: value('securityUsername').trim(), email: address, newPassword: value('securityNewPassword') }); }
      else if (state.mode === 'bind') { path = '/email/bind'; Object.assign(body, { currentPassword: value('securityPassword'), email: address }); }
      else if (state.stage === 'old') { path = '/email/change/authorize'; Object.assign(body, { currentPassword: value('securityPassword'), newEmail: address }); }
      else { path = '/email/change'; body.changeToken = state.changeToken; }
      this.lock(state, true);
      G.API.accountEmail(path, body).then(function (data) {
        if (!Email.active(state)) return;
        if (state.mode === 'change' && state.stage === 'old') {
          state.stage = 'new'; state.changeToken = data.changeToken; state.challenge = null; state.resendUntil = 0;
          el('securityPassword').value = ''; el('securityPassword').disabled = true; el('securityEmail').disabled = true;
          el('securityCode').value = '';
          var codeLabel = (document.querySelector && document.querySelector('label[for="securityCode"]')) ||
            (el('securityCode') && el('securityCode').previousElementSibling);
          if (codeLabel) codeLabel.textContent = '新邮箱验证码';
          if (el('securityCode') && el('securityCode').previousElementSibling) el('securityCode').previousElementSibling.textContent = '新邮箱验证码';
          el('securitySubmit').textContent = '确认更换邮箱'; clearInterval(Email.timers.securitySend); el('securitySend').textContent = '获取验证码';
          Email.message(state, '原邮箱验证成功，请获取并填写新邮箱验证码'); return;
        }
        state.busy = false; state.sessionRevoked = false; Email.close();
        if (state.mode === 'bind') { Email.data = data; G.toast('QQ 邮箱绑定成功'); G.Core.render(); }
        else G.Account.endSession({ title: state.mode === 'reset' ? '密码已重置' : '邮箱已更换', message: '全部旧会话已失效，请使用账号密码重新登录。' });
      }).catch(function (error) {
        if (error.uncertain && Email.active(state)) {
          state.uncertain = true;
          Email.message(state, '操作结果待确认。请关闭弹窗并尝试登录；密码重置请使用新密码，邮箱状态可在重新登录后查询。');
        } else Email.message(state, error.message || '操作失败，请稍后重试');
      }).finally(function () { Email.lock(state, false); });
    }
  };
  G.AccountEmail = Email;
})(window.Game);
