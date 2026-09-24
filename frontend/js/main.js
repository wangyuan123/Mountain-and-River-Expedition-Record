/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

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
    guestMode: false,
    _selectedAvatar: null,
    _landscapeNavCollapsed: readLandscapeNavigationState(),

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
        var label = toggle.querySelector('.nav-collapse-label');
        if (label) label.textContent = collapsed ? '展开导航' : '收起导航';
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
      var mayor = Core.getOfficerByRole('mayor');
      var cap = Core.capacity();
      var foodProduction = Core.produceOf('farm');
      var foodConsumption = Core.foodPerHour();
      var rates = {
        food: foodProduction - foodConsumption,
        steel: Core.produceOf('refinery'),
        oil: Core.produceOf('oilfield'),
        rare: Core.produceOf('raremine'),
        gold: Math.floor(Core.civilianPopulation() * (s.tax / 100) * (1 + (mayor ? mayor.knowledge / 100 : 0)) * (1 + (Core.mayorSkillBonus ? Core.mayorSkillBonus('finance') : 0)) * 2)
      };
      var caps = { food: cap.food, steel: cap.steel, oil: cap.oil, rare: cap.rare, gold: 999999 };
      var info = D.resources[key];
      if (!info) return;
      G.MainView.showResourceDetail(key, info.name, info.icon, r[key] || 0, caps[key] || 999999, rates[key] || 0, foodProduction, foodConsumption);
    },

    showPopulationDetail: function () {
      G.MainView.showPopulationDetailModal();
    },

    showLoginMsg: function (msg, isError) {
      var el = document.getElementById('loginMsg');
      if (!el) return;
      el.textContent = msg;
      el.style.color = isError ? '#ff5a5a' : '#7fc4ff';
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
        self.showLoginMsg('登录成功,加载游戏...', false);
        self.guestMode = false;
        return self.startGame();
      }).catch(function (err) {
        self.showLoginMsg(err && err.message ? err.message : '登录失败', true);
      }).finally(function () { self._loginBusy = false; });
    },

    doRegister: function () {
      var self = this;
      var u = (document.getElementById('loginUser').value || '').trim();
      var p = document.getElementById('loginPass').value || '';
      if (!u || !p) { this.showLoginMsg('请输入用户名和密码', true); return; }
      if (u.length < 3) { this.showLoginMsg('用户名至少3位', true); return; }
      if (p.length < 6) { this.showLoginMsg('密码至少6位', true); return; }
      this.showLoginMsg('注册中...', false);
      G.API.register(u, p).then(function () {
        self.showLoginMsg('注册成功,加载游戏...', false);
        self.guestMode = false;
        return self.startGame();
      }).catch(function (err) {
        self.showLoginMsg(err && err.message ? err.message : '注册失败', true);
      });
    },

    guestPlay: function () {
      var self = this;
      this.showLoginMsg('创建游客账号...', false);
      G.API.createGuest().then(function () {
        self.guestMode = true;
        return self.startGame();
      }).catch(function (err) {
        self.showLoginMsg(err && err.message ? err.message : '服务器不可用', true);
      });
    },

    startGame: function () {
      var self = this;
      // 先建立游戏许可；账号登录本身不授予游戏数据访问权限。
      var permit = G.Protection ? G.Protection.enter() : Promise.resolve(true);
      return permit.then(function (allowed) {
        if (!allowed) return null;
        return G.load();
      }).then(function (state) {
        if (!state || (G.Protection && !G.Protection.canRequest())) return;
        G.state = state;
        Core.state = G.state;
        Core.init();
        Main.renderNavBar();
        Core.route = 'home';
        Core.render();
        if (G.MainQuest) G.MainQuest.init();
        if (G.Chat) G.Chat.loadHistory();
        if (G.Mail) G.Mail.seed();
        if (G.Task && G.Task.Quests) { G.Task.Quests.init(); G.Task.Quests.onEvent('login', 1, true); }
        // Connect WebSocket for real-time updates (skip guest mode - no valid JWT)
        if (G.WS && !Main.guestMode) {
          G.WS.connect();
        }
      }).catch(function (err) {
        if (G.Protection && G.API.isLoggedIn()) G.Protection.denied(err);
        if (G.toast && !(G.Protection && G.Protection.blocked)) G.toast('加载游戏状态失败');
        console.warn('[Main] startGame load failed:', err);
      });
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
      var cityEl = document.getElementById('epCityName');
      var cityName = cityEl ? cityEl.value.trim() : '';
      var safeRe = /^[A-Za-z0-9_\u4e00-\u9fa5·\s]{1,12}$/;
      if (cityName && !safeRe.test(cityName)) { G.toast('城市名仅限中英文/数字/下划线，最多12字'); return; }
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
      var box = document.getElementById('editCommanderBox');
      if (!box) return;
      box.style.display = box.style.display === 'none' ? 'block' : 'none';
    },

    saveCommander: function () {
      var s = Core.state;
      var cmdEl = document.getElementById('epCommander');
      var cmdName = cmdEl ? cmdEl.value.trim() : '';
      if (!cmdName) { G.toast('统帅名不能为空'); return; }
      if (cmdName.length > 8) { G.toast('统帅名最多8个字'); return; }
      if (!/^[A-Za-z0-9_\u4e00-\u9fa5·]{1,8}$/.test(cmdName)) { G.toast('统帅名仅限中英文/数字/下划线'); return; }
      s.player.name = cmdName;
      G.toast('统帅名已保存');
      document.getElementById('editCommanderBox').style.display = 'none';
      Core.render();
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

    sendChat: function () {
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
        G.toast('发言冷却中，请等待 ' + G.Chat.getCooldown() + ' 秒');
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
        var match = msg.match(/(\d+)\s*秒/);
        if (match && G.Chat && G.Chat.startCooldown) {
          var s = parseInt(match[1], 10);
          if (s > 0 && s <= 300) G.Chat.startCooldown(s);
        } else if (G.Chat && G.Chat.updateSendBtnUI) {
          G.Chat.updateSendBtnUI();
        } else if (btn) {
          btn.disabled = false;
          btn.classList.remove('disabled');
        }
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
      var scrollTop = previous ? previous.scrollTop : 0;
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
      if (landscapeNav) {
        viewport.scrollTop = scrollTop;
        if (routeChanged) {
          var active = viewport.querySelector('.navitem.active');
          if (active) {
            var bounds = active.getBoundingClientRect();
            var frame = viewport.getBoundingClientRect();
            if (bounds.top < frame.top) viewport.scrollTop += bounds.top - frame.top;
            else if (bounds.bottom > frame.bottom) viewport.scrollTop += bounds.bottom - frame.bottom;
          }
        }
        return;
      }
      if (routeChanged) {
        pages.forEach(function (el, index) {
          if (el.querySelector('.navitem.active')) page = index;
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
