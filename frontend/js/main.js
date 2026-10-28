/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';
  G.gameNow = G.gameNow || function () { return Date.now(); };

  var Core = G.Core;
  var D = G.DATA;
  var LANDSCAPE_NAV_STORAGE_KEY = G.Constants.landscapeNavStorageKey;

  function landscapeNavigationEnabled() {
    return !!(window.matchMedia && window.matchMedia('(orientation: landscape) and (min-width: 480px)').matches);
  }

  function readLandscapeNavigationState() {
    try { return window.localStorage.getItem(LANDSCAPE_NAV_STORAGE_KEY) === '1'; } catch (e) { return false; }
  }

  function writeLandscapeNavigationState(collapsed) {
    try { window.localStorage.setItem(LANDSCAPE_NAV_STORAGE_KEY, collapsed ? '1' : '0'); } catch (e) {}
  }

  var Main = {
    registrationAgreementVersion: '2026-10-28-v2',
    guestMode: false,
    _selectedAvatar: null,
    _landscapeNavCollapsed: readLandscapeNavigationState(),
    _serverMaintenance: null,

    isMaintenanceActive: function () {
      return !!(this._serverMaintenance && this._serverMaintenance.active);
    },

    setServerMaintenance: function (info) {
      info = info || {};
      this._serverMaintenance = {
        active: true,
        status: info.status != null ? info.status : 500,
        message: info.message || '服务器维护中...',
        raw: info.raw || '',
        time: info.time || Date.now()
      };
    },

    clearServerMaintenance: function () {
      this._serverMaintenance = null;
    },

    isServerError: function (err) {
      if (!err) return false;
      if (err.isServerDown) return true;
      if (typeof err.status === 'number' && err.status >= 500) return true;
      if (err.code === 'SERVER_ERROR' || err.code === 'SERVER_MAINTENANCE') return true;
      if (G.Constants && G.Constants.protectionAccessErrorCodes && G.Constants.protectionAccessErrorCodes.indexOf(err.code) !== -1) {
        return false;
      }
      if (err.code === 'INVALID_CREDENTIALS' || err.code === 'USER_NOT_FOUND' || err.code === 'RECOVERY_REQUIRED') {
        return false;
      }
      if (typeof err === 'object' && (err.name === 'TypeError' || err instanceof TypeError)) return true;
      var msg = String(err.message || '');
      if (msg.indexOf('网络错误') !== -1 ||
          msg.indexOf('Failed to fetch') !== -1 ||
          msg.indexOf('NetworkError') !== -1 ||
          msg.indexOf('连接失败') !== -1 ||
          msg.indexOf('连接中断') !== -1 ||
          msg.indexOf('请求失败 (5') !== -1 ||
          msg.indexOf('服务器维护') !== -1 ||
          msg.indexOf('维护中') !== -1 ||
          msg.indexOf('Handler dispatch failed') !== -1 ||
          msg.indexOf('NoClassDefFoundError') !== -1) {
        return true;
      }
      return false;
    },

    renderMaintenanceNotice: function () {
      var m = this._serverMaintenance || {};
      var serverName = (G.Servers && G.Servers.current) ? G.Servers.current().name : '默认战区';
      var loggedIn = G.API && G.API.isLoggedIn && G.API.isLoggedIn();
      var username = loggedIn ? (G.API.getUsername ? G.API.getUsername() : '') : '';
      var timeStr = m.time ? new Date(m.time).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '刚刚';

      var h = '<div class="login-maintenance-card" role="alert" aria-live="polite">';
      h += '<div class="login-maintenance-header">';
      h += '<span class="login-maintenance-badge"><span class="login-maintenance-pulse" aria-hidden="true"></span> 服务器维护中</span>';
      h += '<span class="login-maintenance-code">' + (m.status ? 'HTTP ' + m.status : 'OFFLINE') + '</span>';
      h += '</div>';

      h += '<h3 class="login-maintenance-title">战区指挥部通讯维护中</h3>';
      h += '<p class="login-maintenance-desc">当前战区服务器正在进行维护检修或通讯链路升级，前线指挥终端暂缓接入。<br><strong>指挥官请放心，您的城池数据、资源与战局进度已由系统安全留存。</strong>维护完成后将第一时间恢复连接。</p>';

      h += '<div class="login-maintenance-meta">';
      h += '<span>目标大区：<strong>' + G.escapeHtml(serverName) + '</strong></span>';
      h += '<span class="login-maintenance-time">最近检测：' + G.escapeHtml(timeStr) + '</span>';
      h += '</div>';

      if (loggedIn && username) {
        h += '<div class="login-maintenance-user-bar">';
        h += '<span>已就绪指挥官：<strong>' + G.escapeHtml(username) + '</strong></span>';
        h += '<button type="button" class="login-maintenance-link-btn" onclick="Game.Main.logout(\'switch\')">切换账号</button>';
        h += '</div>';
      }

      h += '<div class="login-maintenance-actions">';
      h += '<button type="button" id="maintenanceRetryBtn" class="btn ok login-maintenance-retry" onclick="Game.Main.retryConnection()"><span class="retry-icon" aria-hidden="true">⟳</span> 检查服务器状态</button>';
      h += '<button type="button" class="btn login-maintenance-dismiss" onclick="Game.Main.clearServerMaintenance(); Game.Core.render();">关闭公告</button>';
      h += '</div>';

      h += '</div>';
      return h;
    },

    retryConnection: function () {
      var btn = document.getElementById('maintenanceRetryBtn');
      if (btn) {
        btn.disabled = true;
        btn.innerHTML = '<span class="spinner-inline" aria-hidden="true"></span> 正在检测战区通讯...';
      }
      var self = this;
      var client = (G.API && G.API.client) ? G.API.client : null;
      var checkPromise = client
        ? client.get('/anti-addiction/status', { retry: 0, timeout: 6000, silent: true })
        : Promise.reject(new Error('网络不可用'));

      return checkPromise.then(function () {
        self.clearServerMaintenance();
        if (G.toast) G.toast('战区通讯已恢复连接！欢迎归队，指挥官。');
        if (G.API && G.API.isLoggedIn && G.API.isLoggedIn()) {
          return self.startGame();
        } else {
          Core.route = 'login';
          Core.render();
        }
      }).catch(function (err) {
        if (btn) {
          btn.disabled = false;
          btn.innerHTML = '<span class="retry-icon" aria-hidden="true">⟳</span> 检查服务器状态';
        }
        if (self._serverMaintenance) {
          self._serverMaintenance.time = Date.now();
        }
        var timeEl = document.querySelector('.login-maintenance-time');
        if (timeEl) {
          timeEl.textContent = '最近检测：' + new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }) + '（仍不可用）';
        }
        if (G.toast) G.toast('战区服务器仍在维护中，请稍候再试。');
      });
    },

    syncLandscapeNavState: function () {
      var screen = document.getElementById('screen');
      if (!screen) return;
      var collapsed = this._landscapeNavCollapsed;
      screen.classList.toggle('nav-collapsed', collapsed);
      var toggle = document.querySelector('[data-nav="collapse"]');
      if (toggle) {
        toggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
        toggle.setAttribute('aria-label', collapsed ? '展开导航' : '收起导航');
        toggle.setAttribute('title', collapsed ? '展开导航' : '收起导航');
        var icon = toggle.querySelector('.nav-collapse-icon');
        if (icon) icon.textContent = collapsed ? '›' : '‹';
      }
    },

    toggleLandscapeNav: function () {
      if (!landscapeNavigationEnabled()) return;
      this._landscapeNavCollapsed = !this._landscapeNavCollapsed;
      writeLandscapeNavigationState(this._landscapeNavCollapsed);
      this.syncLandscapeNavState();
    },

    showResourceDetail: function (key) {
      var s = Core.state || {};
      var r = s.resources || {};
      var productionCaps = Core.capacity();
      var inventoryCaps = Core.inventoryCapacity();
      var foodProduction = Core.foodProductionPerHour();
      var foodConsumption = Core.foodPerHour();
      var productionCap = productionCaps[key] != null ? productionCaps[key] : (key === 'gold' ? 20000000 : 0);
      var inventoryCap = inventoryCaps[key] || 1000000000;
      var rate = Core.resourceNetRate ? Core.resourceNetRate(key) : 0;
      var info = D.resources[key];
      if (!info) return;
      G.MainView.showResourceDetail(key, info.name, info.icon, r[key] || 0, productionCap, inventoryCap, rate, foodProduction, foodConsumption);
    },

    showPopulationDetail: function () {
      G.MainView.showPopulationDetailModal();
    },

    showLoginMsg: function (msg, isError) {
      var el = document.getElementById('loginMsg');
      if (!el) return;
      el.textContent = msg;
      el.dataset.state = isError ? 'error' : 'info';
    },

    submitAuth: function () {
      var form = document.getElementById('loginForm');
      if (form && form.dataset.authMode === 'register') this.doRegister();
      else this.doLogin();
    },

    toggleAuthMode: function () {
      var form = document.getElementById('loginForm');
      this.setAuthMode(form && form.dataset.authMode === 'register' ? 'login' : 'register');
    },

    togglePasswordVisibility: function (inputId, button) {
      var input = document.getElementById(inputId);
      if (!input) return;
      var visible = input.type === 'text';
      input.type = visible ? 'password' : 'text';
      if (button) {
        button.setAttribute('aria-pressed', visible ? 'false' : 'true');
        button.setAttribute('aria-label', visible ? '显示密码' : '隐藏密码');
      }
    },

    setAuthMode: function (mode) {
      var form = document.getElementById('loginForm');
      if (!form) return;
      var registering = mode === 'register';
      var confirmRow = document.getElementById('loginConfirmRow');
      var confirmInput = document.getElementById('loginPassConfirm');
      var usernameInput = document.getElementById('loginUser');
      var passwordInput = document.getElementById('loginPass');
      var primary = document.getElementById('loginPrimaryAction');
      var switchButton = document.getElementById('authModeSwitch');
      form.dataset.authMode = registering ? 'register' : 'login';
      if (confirmRow) confirmRow.hidden = !registering;
      if (document.getElementById('registerEmailFields')) document.getElementById('registerEmailFields').hidden = !registering;
      if (document.getElementById('registerEmail')) document.getElementById('registerEmail').disabled = !registering;
      if (document.getElementById('registerEmailCode')) document.getElementById('registerEmailCode').disabled = !registering;
      if (!registering && G.AccountEmail) G.AccountEmail.clearRegistration();
      if (confirmInput && !registering) confirmInput.value = '';
      if (passwordInput) passwordInput.setAttribute('autocomplete', registering ? 'new-password' : 'current-password');
      if (document.getElementById('loginModeEyebrow')) document.getElementById('loginModeEyebrow').textContent = registering ? '建立指挥官档案' : '指挥官身份验证';
      if (document.getElementById('loginModeTitle')) document.getElementById('loginModeTitle').textContent = registering ? '注册账号' : '账号登录';
      if (document.getElementById('loginModeDescription')) document.getElementById('loginModeDescription').textContent = registering ? '创建账号，开启你的远征。' : '使用指挥官账号继续你的远征。';
      if (primary) primary.innerHTML = registering ? '注 册 <span aria-hidden="true">＋</span>' : '登 录 <span aria-hidden="true">→</span>';
      if (switchButton) switchButton.textContent = registering ? '返回登录' : '注册账号';
      this.showLoginMsg('', false);
      if (registering && usernameInput && !usernameInput.value) usernameInput.focus();
      else if (registering && passwordInput && !passwordInput.value) passwordInput.focus();
      else if (registering && confirmInput) confirmInput.focus();
      else if (passwordInput) passwordInput.focus();
    },

    showForgotPassword: function () {
      if (G.AccountEmail) G.AccountEmail.open('reset');
    },

    selectServer: function (id) {
      try {
        G.Servers.select(id);
        this.clearServerMaintenance();
        this.showLoginMsg('', false);
      }
      catch (error) { this.showLoginMsg(error.message, true); }
    },

    toggleServerMenu: function (button) {
      var picker = button.closest('.login-server-picker');
      if (this._serverPicker === picker) { this.closeServerMenu(); return; }
      this.closeServerMenu();
      var menu = picker.querySelector('.login-server-options');
      this._serverPicker = picker;
      menu.style.maxHeight = '';
      menu.hidden = false;
      button.setAttribute('aria-expanded', 'true');
      var bounds = button.getBoundingClientRect();
      var scrollArea = picker.closest('.login-access-inner');
      var frame = scrollArea ? scrollArea.getBoundingClientRect() : { top: 0, bottom: window.innerHeight };
      var below = Math.min(window.innerHeight, frame.bottom) - bounds.bottom;
      var above = bounds.top - Math.max(0, frame.top);
      var openAbove = below < menu.offsetHeight + 4 && above > below;
      picker.classList.toggle('opens-above', openAbove);
      menu.style.maxHeight = Math.min(220, Math.max(38, (openAbove ? above : below) - 8)) + 'px';
      (menu.querySelector('[aria-selected="true"]') || menu.firstElementChild).focus({ preventScroll: true });
      document.addEventListener('pointerdown', this.dismissServerMenu);
      window.addEventListener('resize', this.dismissServerMenu);
    },

    closeServerMenu: function (restoreFocus) {
      var picker = this._serverPicker;
      if (!picker) return;
      this._serverPicker = null;
      picker.querySelector('.login-server-options').hidden = true;
      picker.classList.remove('opens-above');
      var button = picker.querySelector('.login-server-trigger');
      button.setAttribute('aria-expanded', 'false');
      document.removeEventListener('pointerdown', this.dismissServerMenu);
      window.removeEventListener('resize', this.dismissServerMenu);
      if (restoreFocus) button.focus({ preventScroll: true });
    },

    dismissServerMenu: function (event) {
      if (Main._serverPicker && (event.type === 'resize' || !Main._serverPicker.contains(event.target))) Main.closeServerMenu();
    },

    leaveServerMenu: function (event) {
      if (this._serverPicker && !this._serverPicker.contains(event.relatedTarget)) this.closeServerMenu();
    },

    handleServerKey: function (event) {
      var picker = event.currentTarget;
      if (event.key === 'Escape' && this._serverPicker === picker) {
        event.preventDefault();
        this.closeServerMenu(true);
        return;
      }
      if (['ArrowDown', 'ArrowUp', 'Home', 'End'].indexOf(event.key) < 0) return;
      event.preventDefault();
      var wasOpen = this._serverPicker === picker;
      if (!wasOpen) this.toggleServerMenu(picker.querySelector('.login-server-trigger'));
      var options = Array.prototype.slice.call(picker.querySelectorAll('[role="option"]'));
      var index = options.indexOf(document.activeElement);
      if (event.key === 'Home') index = 0;
      else if (event.key === 'End') index = options.length - 1;
      else if (wasOpen) index = (index + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length;
      if (options[index]) options[index].focus({ preventScroll: true });
    },

    chooseServer: function (option) {
      this.selectServer(option.dataset.serverId);
      var picker = option.closest('.login-server-picker');
      picker.querySelector('#loginServerValue').textContent = option.textContent;
      picker.querySelectorAll('[role="option"]').forEach(function (item) {
        item.setAttribute('aria-selected', item === option ? 'true' : 'false');
      });
      this.closeServerMenu(true);
    },

    doLogin: function () {
      if (this._loginBusy) return;
      var self = this;
      var u = (document.getElementById('loginUser').value || '').trim();
      var p = document.getElementById('loginPass').value || '';
      if (!u || !p) { this.showLoginMsg('请输入用户名和密码', true); return; }
      this._loginBusy = true;
      this.showLoginMsg('登录中...', false);
      G.API.login(u, p).then(function (data) {
        if (data.status === 'RECOVERY_REQUIRED') {
          G.Account.showRecovery(data);
          return;
        }
        if (G.Account) G.Account.clearNotice();
        self.clearServerMaintenance();
        self.showLoginMsg('登录成功,加载游戏...', false);
        self.guestMode = false;
        // 普通登录不展示新账号欢迎简报，也清除未完成注册流程遗留的标记。
        self._showWelcomeAfterStart = false;
        return self.startGame();
      }).catch(function (err) {
        if (self.isServerError(err)) {
          self.setServerMaintenance({
            active: true,
            status: err.status || 500,
            code: err.code || 'SERVER_ERROR',
            message: '服务器维护中...',
            raw: err.message
          });
          Core.render();
          if (G.toast) G.toast('战区服务器维护中，暂无法连接');
        } else {
          self.showLoginMsg(err && err.message ? err.message : '登录失败', true);
        }
      }).finally(function () { self._loginBusy = false; });
    },

    doRegister: function () {
      if (this._registerBusy) return;
      var u = (document.getElementById('loginUser').value || '').trim();
      var p = document.getElementById('loginPass').value || '';
      var confirmInput = document.getElementById('loginPassConfirm');
      if (!u || !p) { this.showLoginMsg('请输入用户名和密码', true); return; }
      if (u.length < 3) { this.showLoginMsg('用户名至少3位', true); return; }
      if (p.length < 6) { this.showLoginMsg('密码至少6位', true); return; }
      if (confirmInput && !confirmInput.value) { this.showLoginMsg('请再次输入密码', true); return; }
      if (confirmInput && p !== confirmInput.value) { this.showLoginMsg('两次输入的密码不一致', true); return; }
      try {
        var proof = G.AccountEmail.registrationProof();
        var passwordError = G.AccountEmail.validatePassword(p, confirmInput.value);
        if (passwordError) throw new Error(passwordError);
        this.openRegistrationAgreement(u, p, proof);
      } catch (error) { this.showLoginMsg(error.message, true); }
    },

    openRegistrationAgreement: function (username, password, emailProof) {
      if (document.getElementById('registrationAgreementModal')) return;
      var self = this;
      var trigger = document.activeElement;
      var mask = document.createElement('div');
      mask.id = 'registrationAgreementModal';
      mask.className = 'modal-mask registration-agreement-mask';
      mask.innerHTML = '<div class="modal-card registration-agreement-card" role="dialog" aria-modal="true" aria-labelledby="registrationAgreementTitle" aria-describedby="registrationAgreementIntro">' +
        '<div class="modal-title" id="registrationAgreementTitle">注册前请阅读并确认</div>' +
        '<div class="modal-body registration-agreement-body">' +
        '<p id="registrationAgreementIntro" class="registration-agreement-intro">注册《山河远征录》前，请完整阅读以下免责声明与用户协议。协议版本：<b>' + this.registrationAgreementVersion + '</b></p>' +
        '<section><h3>一、重要提示与免责声明</h3><p>游戏内城池、资源、军队、军官、战斗结果和排名均为虚拟数据，不构成现实财产或收益承诺。因不可抗力、网络故障、系统维护、第三方服务故障或恶意攻击造成的服务中断、延迟或数据丢失，运营方将在合理范围内修复和补救；法律法规禁止免责的情形除外。请合理安排游戏时间，避免沉迷。</p></section>' +
        '<section><h3>二、账号注册与安全</h3><p>你应使用本人可以合法使用的真实、准确信息注册，并妥善保管账号和密码。不得出租、出借、买卖或共享账号，不得冒用他人身份。发现异常登录或密码泄露时，请立即修改密码并联系官方渠道。注册须验证本人 QQ 邮箱，用于密码找回及必要安全通知；邮箱不公开展示，处理方式见隐私说明。</p></section>' +
        '<section><h3>三、游戏行为规范</h3><p>不得利用外挂、脚本、自动化工具、数据抓取、恶意刷量、伪造请求、攻击服务器或其他技术手段破坏公平性和服务稳定性；不得发布违法、侵权、骚扰、欺诈、赌博、暴力或其他违反公序良俗的内容。违反约定时，运营方可按影响程度采取限制功能、回收异常收益、冻结或注销账号等措施。</p></section>' +
        '<section><h3>四、隐私、实名与未成年人保护</h3><p>运营方会按照隐私政策处理账号、设备、日志、游戏行为和安全风控信息，用于提供服务、维护安全、改进产品及履行法定义务。游戏可能依法接入实名认证、防沉迷和未成年人保护服务；未完成核验或达到时间限制时，可能无法进入游戏。未成年人应在监护人同意和指导下使用服务。</p><p><a href="privacy.html" target="_blank" rel="noopener">查看实名与儿童个人信息说明</a></p></section>' +
        '<section><h3>五、协议变更与终止</h3><p>运营方会根据业务、安全或合规需要更新本协议，重大变更会通过游戏内公告或登录页面提示。你可以停止使用服务或按页面流程注销账号。</p></section>' +
        '<label class="registration-agreement-check"><input id="registrationAgreementCheck" type="checkbox"><span>我已阅读、理解并同意以上免责声明、用户协议及隐私政策，并确认具备相应民事行为能力；如为未成年人，已获得监护人同意和指导。</span></label>' +
        '<p id="registrationAgreementError" class="registration-agreement-error" role="alert" aria-live="polite"></p>' +
        '</div><div class="modal-foot"><button type="button" class="btn" id="registrationAgreementCancel">暂不注册</button><button type="button" class="btn ok" id="registrationAgreementSubmit" disabled>同意并注册</button></div></div>';
      document.body.appendChild(mask);
      var check = document.getElementById('registrationAgreementCheck');
      var submit = document.getElementById('registrationAgreementSubmit');
      check.onchange = function () { submit.disabled = !check.checked || !!self._registerBusy; };
      document.getElementById('registrationAgreementCancel').onclick = function () { self.closeRegistrationAgreement(); };
      submit.onclick = function () {
        if (!check.checked || self._registerBusy) return;
        try {
          var currentProof = G.AccountEmail.registrationProof();
          if (JSON.stringify(currentProof) !== JSON.stringify(emailProof) || document.getElementById('loginUser').value.trim() !== username || document.getElementById('loginPass').value !== password) throw new Error('注册资料已变化，请重新确认');
        } catch (error) { self.closeRegistrationAgreement(); self.showLoginMsg(error.message, true); return; }
        self._registerBusy = true;
        submit.disabled = true;
        document.getElementById('registrationAgreementCancel').disabled = true;
        self.closeRegistrationAgreement();
        self.showLoginMsg('注册中...', false);
        G.API.register(username, password, self.registrationAgreementVersion, emailProof).then(function () {
        self.showLoginMsg('注册成功,加载游戏...', false);
        self.guestMode = false;
        // 欢迎简报只由账号创建成功触发，已有账号的行动指引由服务端进度控制。
        self._showWelcomeAfterStart = true;
        return self.startGame();
        }).catch(function (err) {
          self.showLoginMsg(err && err.message ? err.message : '注册失败', true);
        }).finally(function () { self._registerBusy = false; });
      };
      mask.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') { event.preventDefault(); self.closeRegistrationAgreement(); }
        if (event.key === 'Tab') {
          var items = mask.querySelectorAll('button:not(:disabled), input:not(:disabled)');
          if (!items.length) return;
          var first = items[0], last = items[items.length - 1];
          if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
          else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        }
      });
      mask._registrationTrigger = trigger;
      check.focus();
    },

    closeRegistrationAgreement: function () {
      var mask = document.getElementById('registrationAgreementModal');
      var trigger = mask && mask._registrationTrigger;
      if (mask) mask.remove();
      if (trigger && trigger.isConnected) trigger.focus();
    },

    guestPlay: function () {
      var self = this;
      this.showLoginMsg('创建游客账号...', false);
      G.API.createGuest().then(function () {
        self.clearServerMaintenance();
        self.guestMode = true;
        self._showWelcomeAfterStart = true;
        return self.startGame();
      }).catch(function (err) {
        if (self.isServerError(err)) {
          self.setServerMaintenance({
            active: true,
            status: err.status || 500,
            code: err.code || 'SERVER_ERROR',
            message: '服务器维护中...',
            raw: err.message
          });
          Core.render();
          if (G.toast) G.toast('战区服务器维护中，暂无法连接');
        } else {
          self.showLoginMsg(err && err.message ? err.message : '服务器不可用', true);
        }
      });
    },

    startGame: function () {
      var self = this;
      if (G.AccountEmail) G.AccountEmail.onLogin();
      // 先建立游戏许可；账号登录本身不授予游戏数据访问权限。
      var permit = G.Protection ? G.Protection.enter() : Promise.resolve(true);
      return permit.then(function (allowed) {
        if (!allowed) return null;
        var rulesRequest = G.API.getPrerequisites ? G.API.getPrerequisites().then(function (rules) {
          if (G.Prerequisites) G.Prerequisites.setRules(rules);
        }).catch(function () { return null; }) : Promise.resolve(null);
        return Promise.all([G.load(), rulesRequest]).then(function (values) { return values[0]; });
      }).then(function (state) {
        if (!state || (G.Protection && !G.Protection.canRequest())) return;
        self.clearServerMaintenance();
        G.state = state;
        Core.state = G.state;
        Core.init();
        Main.renderNavBar();
        Core.route = 'home';
        Core.render();
        if (G.Pvp) G.Pvp.start();
        if (G.MainQuest) G.MainQuest.init();
        if (G.Chat) G.Chat.loadHistory();
        if (G.Mail) G.Mail.seed();
        if (G.PrivateChat) G.PrivateChat.refresh();
        if (G.Friends) G.Friends.refresh();
        if (G.Task && G.Task.Quests) { G.Task.Quests.init(); G.Task.Quests.onEvent('login', 1, true); }
        if (Main._showWelcomeAfterStart) {
          Main._showWelcomeAfterStart = false;
          Main.showWelcomeIntro();
        }
        // Connect WebSocket for real-time updates (skip guest mode - no valid JWT)
        if (G.WS && !Main.guestMode) {
          G.WS.connect();
        }
      }).catch(function (err) {
        if (self.isServerError(err)) {
          self.setServerMaintenance({
            active: true,
            status: err.status || 500,
            code: err.code || 'SERVER_ERROR',
            message: '服务器维护中...',
            raw: err.message
          });
          Core.route = 'login';
          Core.render();
          if (G.toast) G.toast('战区服务器维护中，请稍后重试');
        } else {
          if (G.Protection && G.API.isLoggedIn()) G.Protection.denied(err);
          if (G.toast && !(G.Protection && G.Protection.blocked)) G.toast('加载游戏状态失败');
        }
        console.warn('[Main] startGame load failed:', err);
      });
    },

    showWelcomeIntro: function () {
      if (document.getElementById('welcomeIntroModal')) return;
      var mask = document.createElement('div');
      mask.id = 'welcomeIntroModal'; mask.className = 'modal-mask welcome-intro-mask';
      mask.innerHTML = '<div class="welcome-intro-card" role="dialog" aria-modal="true" aria-labelledby="welcomeIntroTitle">' +
        '<div class="welcome-intro-kicker">前进基地 · 作战简报</div>' +
        '<h2 id="welcomeIntroTitle">指挥官，欢迎来到《山河远征录》</h2>' +
        '<p>烽烟席卷山河，你奉命接管一座尚待发展的前线基地。建设资源设施、组建部队、侦察周边，并将野地资源运回城内，让这里成为远征的起点。</p>' +
        '<p>市政厅已经准备好第一批行动指引，现在由你下达第一道命令。</p>' +
        '<div class="welcome-intro-actions"><button type="button" class="btn ok" id="welcomeStart">接管基地 · 开始指引</button><button type="button" class="btn" id="welcomeExplore">自行探索</button></div>' +
        '</div>';
      document.body.appendChild(mask);
      var close = function () { if (mask.parentNode) mask.parentNode.removeChild(mask); };
      mask.querySelector('#welcomeStart').onclick = close;
      mask.querySelector('#welcomeExplore').onclick = function () { close(); if (G.Onboarding && G.Onboarding.mutate) G.Onboarding.mutate('skip'); };
    },

    logout: function (mode) {
      if (document.getElementById('switchAccountModal')) return;
      var isExit = mode === 'exit';
      var title = isExit ? '退出登录' : '切换账号';
      var trigger = document.activeElement;
      var mask = document.createElement('div');
      mask.id = 'switchAccountModal';
      mask.className = 'modal-mask account-confirm-mask';
      mask.innerHTML =
        '<div class="modal-card account-confirm" role="dialog" aria-modal="true" aria-labelledby="accountConfirmTitle" aria-describedby="accountConfirmDesc">' +
          '<div class="modal-title" id="accountConfirmTitle">' + title + '</div>' +
          '<div class="modal-body">' +
            '<div class="account-confirm-current"><span>当前账号</span><b id="accountConfirmName"></b></div>' +
            '<p id="accountConfirmDesc">' + (isExit ? '退出后将返回登录页。' : '将退出当前账号，返回登录页选择其他账号。') + '</p>' +
            '<p class="account-confirm-note">游戏进度保留在当前账号中。</p>' +
          '</div>' +
          '<div class="modal-foot">' +
            '<button type="button" class="account-confirm-cancel">取消</button>' +
            '<button type="button" class="account-confirm-submit">' + (isExit ? '确认退出' : '切换账号') + '</button>' +
          '</div>' +
        '</div>';
      mask.querySelector('#accountConfirmName').textContent = G.API.getUsername() || '当前账号';
      var cancel = mask.querySelector('.account-confirm-cancel');
      var submit = mask.querySelector('.account-confirm-submit');
      function close(restoreFocus) {
        document.removeEventListener('keydown', onKey, true);
        mask.remove();
        if (restoreFocus && trigger && trigger.isConnected) trigger.focus();
      }
      function onKey(e) {
        // 弹窗打开期间不触发游戏页面的数字键/返回快捷键。
        e.stopPropagation();
        if (e.key === 'Escape') { e.preventDefault(); close(true); }
        if (e.key === 'Tab') {
          e.preventDefault();
          (document.activeElement === cancel ? submit : cancel).focus();
        }
      }
      cancel.onclick = function () { close(true); };
      mask.onclick = function (e) { if (e.target === mask) close(true); };
      submit.onclick = function () {
        submit.disabled = true;
        close(false);
        if (G.WS) G.WS.disconnect();
        G.API.logout();
        G.state = null;
        Core.state = null;
        Core.route = 'login';
        Core.render();
      };
      document.body.appendChild(mask);
      document.addEventListener('keydown', onKey, true);
      cancel.focus();
    },

    toggleEditCity: function () {
      var box = document.getElementById('editCityBox');
      if (!box) return;
      box.style.display = box.style.display === 'none' ? 'block' : 'none';
    },

    saveCity: function () {
      var s = Core.state;
      if (Number(s.player.cityNameRenameAvailableAt) > G.gameNow()) {
        G.toast('这座城市今天已改名，请明日 0:00 后再试');
        return;
      }
      if ((Number(s.items && s.items.cityRenameCard) || 0) <= 0 && (Number(s.resources && s.resources.gold) || 0) < 60) {
        G.toast('城市改名卡或黄金不足（需 1 张改名卡或 60 黄金）');
        return;
      }
      var cityEl = document.getElementById('epCityName');
      var rawName = cityEl ? cityEl.value : '';
      var cityName = (!rawName || !rawName.trim()) ? '新城市' : rawName;
      try {
        cityName = G.normalizeDisplayName(cityName, 12, '城市名称');
      } catch (error) {
        G.toast(error.message);
        return;
      }
      G.API.setCityName(cityName).then(function () {
        G.toast('城市名已保存');
        var box = document.getElementById('editCityBox');
        if (box) box.style.display = 'none';
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '城市名保存失败');
      });
    },

    toggleEditCommander: function () {
      G.Main.promptRenameCommander();
    },

    saveCommander: function () {
      G.Main.promptRenameCommander();
    },

    toggleEditDesc: function () {
      var box = document.getElementById('editDescBox');
      if (!box) return;
      box.style.display = box.style.display === 'none' ? 'block' : 'none';
    },

    saveDesc: function () {
      var s = Core.state;
      var descEl = document.getElementById('epCityDesc');
      var desc = descEl ? descEl.value.trim() : '';
      if (desc.length > 30) { G.toast('城市简介最多30字'); return; }
      s.cityDesc = desc;
      G.toast('城市简介已保存');
      document.getElementById('editDescBox').style.display = 'none';
      Core.render();
    },

    /** 注销交互由独立模块维护，避免主流程实时重绘覆盖密码输入。 */
    openDisableAccount: function () { G.Account.open(); },

    /** 服务端冷却提示兼容统一时长和旧版秒数，恢复按钮冷却时不丢失分钟等高位单位。 */
    sendChat: function () {
      if (G.Chat && G.Chat.canSend && !G.Chat.canSend()) {
        G.toast('声望达到 10000 后才能在世界频道发言');
        return;
      }
      var el = document.getElementById('worldChatInput');
      if (!el) return;
      var text = (el.value || '').trim();
      if (!text) {
        G.toast('消息不能为空');
        return;
      }
      if (text.length > 80) {
        G.toast('消息不能超过 80 字');
        return;
      }
      if (G.Chat && G.Chat.getCooldown && G.Chat.getCooldown() > 0) {
        G.toast('发言冷却中，请等待 ' + G.formatDuration(G.Chat.getCooldown()));
        return;
      }
      if (G.Chat && G.Chat.isSpamDuplicate && G.Chat.isSpamDuplicate(text)) {
        G.toast('请勿连续发送相同内容刷屏');
        return;
      }
      if (!G.Chat || typeof G.Chat.send !== 'function') {
        G.toast('世界频道暂未加载');
        return;
      }

      var btn = document.getElementById('worldChatSendBtn');
      if (btn) {
        btn.disabled = true;
        btn.classList.add('disabled');
      }

      G.Chat.send(text).then(function () {
        el.value = '';
        if (G.Chat && G.Chat.recordSent) {
          G.Chat.recordSent(text);
        }
      }).catch(function (err) {
        var msg = err && err.message ? err.message : '发送失败';
        G.toast(msg);
        var match = msg.match(/(?:(\d+)\s*天)?\s*(?:(\d+)\s*小时)?\s*(?:(\d+)\s*分钟)?\s*(\d+)\s*秒/);
        if (match && G.Chat && G.Chat.startCooldown) {
          var seconds = Number(match[1] || 0) * 86400 + Number(match[2] || 0) * 3600 + Number(match[3] || 0) * 60 + Number(match[4]);
          if (seconds > 0 && seconds <= 300) G.Chat.startCooldown(seconds);
        } else if (G.Chat && G.Chat.updateSendBtnUI) {
          G.Chat.updateSendBtnUI();
        } else if (btn) {
          btn.disabled = false;
          btn.classList.remove('disabled');
        }
      });
    },

    sendGuildChat: function (inputId, btnId) {
      var inId = inputId || 'guildChatInput';
      var el = document.getElementById(inId);
      if (!el) return;
      var text = (el.value || '').trim();
      if (!text) {
        G.toast('消息不能为空');
        return;
      }
      if (text.length > 80) {
        G.toast('消息不能超过 80 字');
        return;
      }
      if (G.Chat && G.Chat.getGuildCooldown && G.Chat.getGuildCooldown() > 0) {
        G.toast('发言冷却中，请等待 ' + G.formatDuration(G.Chat.getGuildCooldown()));
        return;
      }
      if (!G.Chat || typeof G.Chat.sendGuild !== 'function') {
        G.toast('军团聊天服务暂未就绪');
        return;
      }

      var btn = document.getElementById(btnId || 'guildChatSendBtn');
      if (btn) {
        btn.disabled = true;
        btn.classList.add('disabled');
      }

      G.Chat.sendGuild(text).then(function () {
        el.value = '';
        if (G.Chat && G.Chat.updateGuildSendBtnUI) G.Chat.updateGuildSendBtnUI();
      }).catch(function (err) {
        G.toast(err.message || '发送失败');
        if (G.Chat && G.Chat.updateGuildSendBtnUI) G.Chat.updateGuildSendBtnUI();
      });
    },

    renderNavBar: function () {
      var bar = G.$('navbar');
      if (!bar) return;
      var html = G.MainView.navBar();
      var routeChanged = bar._navRoute !== Core.route;
      var previous = bar.querySelector('.nav-viewport');
      var page = previous && previous.clientWidth ? Math.round(previous.scrollLeft / previous.clientWidth) : 0;
      var landscapeNav = landscapeNavigationEnabled();
      var previousPages = previous ? previous.querySelectorAll('.nav-page') : [];
      var pageScrolls = Array.prototype.map.call(previousPages, function (el) { return el.scrollTop; });
      this.syncLandscapeNavState();
      // 普通 tick 不重建导航，避免打断手势或把玩家正在浏览的分页拉回首页。
      if (bar._navHtml === html && !routeChanged) return;
      bar.innerHTML = html;
      bar._navHtml = html;
      bar._navRoute = Core.route;
      var collapseButton = bar.querySelector('[data-nav="collapse"]');
      if (collapseButton) collapseButton.onclick = function () { Main.toggleLandscapeNav(); };
      this.syncLandscapeNavState();
      var viewport = bar.querySelector('.nav-viewport');
      if (!viewport) return;
      var pages = viewport.querySelectorAll('.nav-page');
      var dots = bar.querySelectorAll('.nav-page-dot');
      if (routeChanged) {
        pages.forEach(function (el, index) {
          if (el.querySelector('.navitem.active')) page = index;
        });
      }
      // 横屏与竖屏均横向分页；横屏每页单独保留纵向滚动位置。
      if (landscapeNav) {
        pages.forEach(function (el, index) {
          el.scrollTop = pageScrolls[index] || 0;
          var active = routeChanged && el.querySelector('.navitem.active');
          if (!active) return;
          var bounds = active.getBoundingClientRect();
          var frame = el.getBoundingClientRect();
          if (bounds.top < frame.top) el.scrollTop += bounds.top - frame.top;
          else if (bounds.bottom > frame.bottom) el.scrollTop += bounds.bottom - frame.bottom;
        });
      }
      function updateDots() {
        var current = viewport.clientWidth ? Math.round(viewport.scrollLeft / viewport.clientWidth) : page;
        dots.forEach(function (dot, index) {
          dot.classList.toggle('active', index === current);
          dot.setAttribute('aria-current', index === current ? 'true' : 'false');
        });
      }
      viewport.scrollLeft = Math.min(page, pages.length - 1) * viewport.clientWidth;
      viewport.addEventListener('scroll', updateDots, { passive: true });
      dots.forEach(function (dot, index) {
        dot.onclick = function () {
          viewport.scrollTo({ left: index * viewport.clientWidth, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
        };
      });
      updateDots();
    },

  };

  Object.assign(Main, G.PlayerProfile);
  G.Main = Main;

  function boot() {
    Core.bindKeys();
    if (G.API.isLoggedIn()) Main.startGame();
    else { Core.route = 'login'; Core.render(); }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})(window.Game);
