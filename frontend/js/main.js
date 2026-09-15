/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var Core = G.Core;
  var D = G.DATA;

  var Main = {
    guestMode: false,
    _selectedAvatar: null,

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
        gold: Math.floor(Core.civilianPopulation() * (s.tax / 100) * (1 + (mayor ? mayor.knowledge / 100 : 0)) * 2)
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
      var self = this;
      var u = (document.getElementById('loginUser').value || '').trim();
      var p = (document.getElementById('loginPass').value || '').trim();
      if (!u || !p) { this.showLoginMsg('请输入用户名和密码', true); return; }
      this.showLoginMsg('登录中...', false);
      G.API.login(u, p).then(function () {
        self.showLoginMsg('登录成功,加载游戏...', false);
        self.guestMode = false;
        return self.startGame();
      }).catch(function (err) {
        self.showLoginMsg(err && err.message ? err.message : '登录失败', true);
      });
    },

    doRegister: function () {
      var self = this;
      var u = (document.getElementById('loginUser').value || '').trim();
      var p = (document.getElementById('loginPass').value || '').trim();
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
      // 通过 API 加载游戏状态，整体替换 G.state（不与旧状态合并）
      return G.load().then(function (state) {
        G.state = state;
        Core.state = G.state;
        Core.init();
        Main.renderNavBar();
        Core.route = 'home';
        Core.render();
        // Connect WebSocket for real-time updates (skip guest mode - no valid JWT)
        if (G.WS && !Main.guestMode) {
          G.WS.connect();
        }
      }).catch(function (err) {
        if (G.toast) G.toast('加载游戏状态失败');
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

    // ============================================================
    //  注销账号：二次确认弹窗 + 提交后清空本地会话
    // ============================================================

    openDisableAccount: function () {
      if (!G.API || !G.API.isLoggedIn()) {
        G.toast('当前未登录账号，无需注销');
        return;
      }
      if (G.Main && G.Main.guestMode) {
        G.toast('游客模式无账号，无需注销');
        return;
      }
      var existing = document.getElementById('disableAccountModal');
      if (existing) existing.remove();

      var mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.id = 'disableAccountModal';
      mask.innerHTML =
        '<div class="modal-card" style="max-width:380px">' +
          '<div class="modal-title" style="color:var(--danger)">⚠ 注销账号</div>' +
          '<div class="modal-body">' +
            '<div style="font-size:13px;line-height:1.6;color:var(--ink)">' +
              '注销后账号将进入 <b>7 天恢复期</b>。<br>' +
              '• 宽限期内重新登录可自动恢复；<br>' +
              '• 超出 7 天将永久清理账号数据，且该用户名不可再用；<br>' +
              '• 注销会立即撤销当前会话。' +
            '</div>' +
            '<div class="edit-row" style="margin-top:10px"><label>当前密码</label>' +
              '<input id="disablePassword" class="qty" style="width:100%" type="password" maxlength="64" placeholder="请输入当前登录密码">' +
            '</div>' +
            '<div class="edit-row"><label>确认操作</label>' +
              '<input id="disableConfirm" class="qty" style="width:100%" maxlength="16" placeholder="请输入 确认注销">' +
            '</div>' +
            '<div id="disableMsg" style="margin-top:6px;font-size:12px;color:var(--muted)">输入"确认注销"以继续</div>' +
          '</div>' +
          '<div class="btn-row" style="margin-top:10px">' +
            '<button class="btn sm" onclick="Game.Main.closeDisableAccount()">取消</button>' +
            '<button class="btn sm warn2" style="background:#b03020;color:#fff" onclick="Game.Main.submitDisableAccount()">确认注销</button>' +
          '</div>' +
        '</div>';
      document.body.appendChild(mask);
      setTimeout(function () {
        var pwd = document.getElementById('disablePassword');
        if (pwd) pwd.focus();
      }, 50);
    },

    closeDisableAccount: function () {
      var mask = document.getElementById('disableAccountModal');
      if (mask) mask.remove();
    },

    submitDisableAccount: function () {
      var pwdEl = document.getElementById('disablePassword');
      var confEl = document.getElementById('disableConfirm');
      var msgEl = document.getElementById('disableMsg');
      var password = pwdEl ? pwdEl.value : '';
      var confirmText = confEl ? confEl.value.trim() : '';
      if (!password) {
        if (msgEl) { msgEl.textContent = '请输入当前密码'; msgEl.style.color = 'var(--danger)'; }
        return;
      }
      if (confirmText !== '确认注销') {
        if (msgEl) { msgEl.textContent = '请输入"确认注销"以继续'; msgEl.style.color = 'var(--danger)'; }
        return;
      }

      var self = this;
      G.API.disableAccount(password, confirmText).then(function (data) {
        self.closeDisableAccount();
        // 关闭 WS、清空状态、回到登录页
        if (G.WS) G.WS.disconnect();
        G.state = null;
        Core.state = null;
        self.guestMode = false;
        Core.history = [];
        Core.route = 'login';
        Core.render();
        var cooldown = data && data.cooldownDays ? data.cooldownDays : 7;
        G.toast('账号已注销，' + cooldown + ' 天内登录可恢复');
      }).catch(function (err) {
        if (msgEl) {
          msgEl.textContent = (err && err.message) ? err.message : '注销失败';
          msgEl.style.color = 'var(--danger)';
        }
      });
    },

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
      // 普通 tick 不重建导航，避免打断手势或把玩家正在浏览的分页拉回首页。
      if (bar._navHtml === html && !routeChanged) return;
      bar.innerHTML = html;
      bar._navHtml = html;
      bar._navRoute = Core.route;
      var viewport = bar.querySelector('.nav-viewport');
      if (!viewport) return;
      var pages = viewport.querySelectorAll('.nav-page');
      var dots = bar.querySelectorAll('.nav-page-dot');
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

    // ================================================================
    // 新手引导
    // ================================================================
    shouldShowTutorial: function (state) {
      try {
        // 跳过状态以服务端玩家数据为准，刷新或更换设备后仍然有效。
        if (state && state.player && state.player.tutorialDismissed) return false;
        // 资源仍处于初始范围(每种 <= 12万) 才视为新玩家
        var r = (state && state.resources) || {};
        var max = Math.max(r.food || 0, r.steel || 0, r.oil || 0, r.rare || 0);
        return max <= 120000;
      } catch (e) { return false; }
    },

    showTutorial: function () {
      var modal = document.createElement('div');
      modal.className = 'modal-mask';
      modal.innerHTML =
        '<div class="modal-card" style="max-width:520px">' +
          '<div class="modal-title">欢迎来到烽原战策</div>' +
          '<div class="modal-body" style="line-height:1.7;font-size:14px">' +
            '<p>1. <b>资源</b>([1]): 升级农场/炼油厂/钢/稀矿,提升每小时产量。</p>' +
            '<p>2. <b>军事</b>([2]): 建造兵营、兵工厂、解锁高级兵种。</p>' +
            '<p>3. <b>军官学院</b>: 消耗 <b>200 黄金/次</b> 刷新候选,招幕将领出战。</p>' +
            '<p>4. <b>地图</b>([6]): 扫描周围资源/流寇/玩家主城。宣战前需 6 小时备战 + 24 小时战争。</p>' +
            '<p>5. <b>军饷</b>: 系统按<b>每小时</b>从黄金中扣除军官薪资总额,金币不足时武将忠诚度会下降,请保持税源。</p>' +
          '</div>' +
          '<div class="modal-foot">' +
            '<button class="btn ok" id="tutOk">明白了</button>' +
            '<button class="btn" id="tutDismiss">不再提示</button>' +
          '</div>' +
        '</div>';
      document.body.appendChild(modal);
      var close = function () {
        if (modal.parentNode) modal.parentNode.removeChild(modal);
      };
      modal.querySelector('#tutOk').onclick = close;
      modal.querySelector('#tutDismiss').onclick = function () {
        var button = modal.querySelector('#tutDismiss');
        button.disabled = true;
        if (!G.API || !G.API.dismissTutorial) {
          G.toast('引导状态保存失败，请稍后重试');
          button.disabled = false;
          return;
        }
        G.API.dismissTutorial().then(function () {
          if (G.state && G.state.player) G.state.player.tutorialDismissed = true;
          close();
        }).catch(function () {
          G.toast('引导状态保存失败，请稍后重试');
          button.disabled = false;
        });
      };
    }
  };

  Object.assign(Main, G.PlayerProfile);
  G.Main = Main;

  function boot() {
    if (G.API.isLoggedIn()) {
      // 已登录：通过 API 异步加载游戏状态后再初始化
      G.load().then(function (state) {
        G.state = state;
        Core.state = G.state;
        Core.init();
        Main.renderNavBar();
        Core.route = 'home';
        Core.render();
        // 主线任务 + 新手引导系统
        if (G.MainQuest) G.MainQuest.init();
        // 加载世界频道历史，实时消息由 WebSocket 广播
        if (G.Chat) G.Chat.loadHistory();
        // 初始化邮件种子
        if (G.Mail) G.Mail.seed();
        // 新手引导：仅对首次登录且资源等级很低的玩家展示一次
        if (Main.shouldShowTutorial(state)) {
          setTimeout(function () { Main.showTutorial(); }, 600);
        }
        // 初始化每日任务；登录进度照常计入，但页面刷新不弹任务提示。
        if (G.Task && G.Task.Quests) {
          G.Task.Quests.init();
          G.Task.Quests.onEvent('login', 1, true);
        }
        // Connect WebSocket for real-time updates (skip guest tokens - no valid JWT)
        var token = G.API.getToken();
        if (G.WS && token && token.indexOf('guest_') !== 0) {
          G.WS.connect();
        }
      }).catch(function (err) {
        console.warn('[Main] boot load failed:', err);
        Core.bindKeys();
        Core.route = 'login';
        Core.render();
      });
    } else {
      Core.bindKeys();
      Core.route = 'login';
      Core.render();
    }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})(window.Game);
