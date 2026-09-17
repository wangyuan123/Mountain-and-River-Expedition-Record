/* global window, document, localStorage, performance */
(function (G) {
  'use strict';
  function esc(value) { return G.escapeHtml(String(value == null ? '' : value)); }
  function el(id) { return document.getElementById(id); }
  function stamp(value) { return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) + '（北京时间）' : '待开放日历确认'; }
  function secret() { return window.crypto.randomUUID().replace(/-/g, '') + window.crypto.randomUUID().replace(/-/g, ''); }
  var P = {
    blocked: true, data: null, timer: null, heartbeatBusy: false, generation: 0, message: '', entering: null,
    key: function () { return 'wargame_play_session_' + encodeURIComponent(G.API.getUsername()); },
    session: function () { return localStorage.getItem(this.key()) || ''; },
    now: function () { return this.serverTime + (performance.now() - this.syncedAt); },
    adopt: function (data) { this.data = data; this.serverTime = data.serverNow; this.syncedAt = performance.now(); },
    isGamePath: function (path) { return path.indexOf('/game/') === 0 || path === '/auth/tutorial/dismiss'; },
    isAccessError: function (code) {
      return ['REAL_NAME_REQUIRED', 'IDENTITY_UNAVAILABLE', 'GUARDIAN_CONSENT_REQUIRED', 'GUARDIAN_RESTRICTED',
        'CALENDAR_UNAVAILABLE', 'PLAY_WINDOW_CLOSED', 'PLAY_TIME_EXHAUSTED', 'PLAY_SESSION_EXPIRED'].indexOf(code) !== -1;
    },
    error: function () { var e = new Error('当前游戏会话已结束'); e.code = 'PLAY_SESSION_EXPIRED'; return e; },
    canRequest: function () {
      return !this.blocked && this.data && (this.data.enabled === false || (this.now() < this.data.allowedUntil && this.now() < this.data.leaseUntil));
    },

    /** 清游戏态而保留账号凭据，注销、申诉和监护服务在休息期间继续可用。 */
    stop: function () {
      this.blocked = true; this.generation++; this.entering = null;
      if (this.timer) clearInterval(this.timer);
      this.timer = null;
      if (G.WS) G.WS.disconnect();
      if (G.Onboarding) G.Onboarding.stop();
      if (G.World && G.World.stopAlertTimer) G.World.stopAlertTimer();
      if (G.World && G.World._warTimer) { clearInterval(G.World._warTimer); G.World._warTimer = null; }
      if (G.Wounded) G.Wounded.stop();
      if (G.WorldView && G.WorldView.invalidate) G.WorldView.invalidate();
      if (G.WorldMap) G.WorldMap.unmount();
      if (G.Main && G.Main.closePlayerDrawer) G.Main.closePlayerDrawer();
      if (G.Cities) G.Cities.switching = false;
      G.API.client.invalidateCityRequests();
      G.state = null; G.Core.state = null; G.Core.history = [];
      var banner = el('wsStatusBanner'); if (banner) banner.style.display = 'none';
    },
    reset: function () { this.stop(); this.data = null; this.message = ''; },

    /** 只由明确进入游戏或启动页面调用；失效心跳不自动抢回另一设备的会话。 */
    enter: function () {
      if (this.entering) return this.entering;
      var self = this, token = G.API.getToken(), generation = this.generation;
      this.message = '';
      this.entering = G.API.client.get('/anti-addiction/status', { retry: 0 }).then(function (data) {
        if (token !== G.API.getToken() || generation !== self.generation) return false;
        self.adopt(data);
        if (!data.canPlay) { self.show(); return false; }
        var session = data.sessionActive ? self.session() : secret();
        return G.API.client.post('/play-sessions', { sessionSecret: session }).then(function (result) {
          // 用户退出或切换页面后，迟到的许可不能重新打开游戏。
          if (token !== G.API.getToken() || generation !== self.generation) return false;
          localStorage.setItem(self.key(), session);
          self.adopt(result); self.blocked = false; self.startTimer();
          return true;
        });
      }).catch(function (err) {
        if (token === G.API.getToken() && generation === self.generation && G.API.isLoggedIn()) { self.message = err.message; self.show(); }
        return false;
      }).finally(function () { if (generation === self.generation) self.entering = null; });
      return this.entering;
    },
    show: function () {
      this.stop(); G.Core.route = 'protection'; G.Core.render();
    },
    open: function () {
      var self = this;
      // 主动离开游戏结束租约；服务端通过心跳失效兜底处理丢失的退出请求。
      var end = G.API.client.post('/play-sessions/end', {}, { silent: true, retry: 0 });
      this.show();
      end.catch(function () {}).then(function () { return self.refresh(); });
    },
    refresh: function () {
      var self = this, token = G.API.getToken();
      return G.API.client.get('/anti-addiction/status', { retry: 0 }).then(function (data) {
        if (token !== G.API.getToken()) return;
        self.adopt(data); if (self.blocked) G.Core.render();
      }).catch(function (err) { self.message = err.message; if (self.blocked && G.API.isLoggedIn()) G.Core.render(); });
    },
    denied: function (err) {
      if (err.code === 'CHAT_RESTRICTED' || err.code === 'PAYMENT_DISABLED') return;
      this.message = err.message || '游戏会话已结束，请重新确认游戏时间。';
      this.show();
    },
    startTimer: function () {
      if (this.timer) clearInterval(this.timer);
      this.lastHeartbeat = performance.now(); this.warned = {};
      var self = this;
      this.timer = setInterval(function () { self.tick(); }, 1000);
    },
    /** 本机时钟变更不延长倒计时；真正许可仍由每次服务端授权决定。 */
    tick: function () {
      if (this.blocked || !this.data || this.data.enabled === false) return;
      if (!this.canRequest()) { this.denied(this.error()); this.refresh(); return; }
      var seconds = Math.ceil((this.data.allowedUntil - this.now()) / 1000);
      var status = el('playTimeStatus');
      if (status) status.textContent = this.data.minor ? '未成年人模式 · 剩余 ' + Math.ceil(seconds / 60) + ' 分钟' : '实名与健康游戏';
      if (this.data.minor) {
        var threshold = seconds <= 60 ? 60 : (seconds <= 300 ? 300 : (seconds <= 900 ? 900 : 0));
        if (threshold && !this.warned[threshold]) {
          this.warned[900] = true;
          if (threshold <= 300) this.warned[300] = true;
          if (threshold <= 60) this.warned[60] = true;
          G.toast('游戏将在 ' + Math.ceil(seconds / 60) + ' 分钟内结束。离线期间战局继续，城池仍可能受到攻击。');
        }
      }
      if (performance.now() - this.lastHeartbeat >= 20000 && !this.heartbeatBusy) {
        this.heartbeatBusy = true; this.lastHeartbeat = performance.now();
        var self = this, generation = this.generation;
        G.API.client.post('/play-sessions/heartbeat', {}, { silent: true, retry: 0, timeout: 10000 }).then(function (data) {
          if (generation === self.generation && !self.blocked) self.adopt(data);
        }).catch(function (err) {
          if (generation === self.generation && err.status && err.status < 500) self.denied(err);
        }).finally(function () { self.heartbeatBusy = false; });
      }
    },
    banner: function () {
      return '<button class="protection-banner" id="playTimeStatus" onclick="Game.Protection.open()">' +
        (this.data && this.data.minor ? '未成年人模式 · 点击查看游戏时间' : '实名与健康游戏') + '</button>';
    },

    verify: function () {
      var field = el('identityProof'), button = el('verifyIdentity');
      if (!field || !field.value.trim()) return;
      var self = this, proof = field.value.trim(), token = G.API.getToken();
      field.value = ''; button.disabled = true;
      G.API.client.post('/identity/verify', { proof: proof }, { retry: 0, timeout: 15000 }).then(function (data) {
        if (token !== G.API.getToken()) return;
        self.adopt(data); self.message = data.canPlay ? '实名核验成功，请确认规则后进入游戏。' : '实名核验成功，当前游戏许可请查看下方状态。'; G.Core.render();
      }).catch(function (err) {
        if (token !== G.API.getToken()) return;
        self.message = err.uncertain ? '核验结果待确认，请刷新状态后再操作。' : err.message; G.Core.render();
      }).finally(function () { proof = ''; if (el('verifyIdentity')) el('verifyIdentity').disabled = false; });
    },
    guardian: function () {
      G.API.client.get('/guardian/children', { retry: 0 }).then(function (children) {
        var target = el('guardianPanel'); if (!target) return;
        var h = '<h3>家长监护</h3><p>仅显示已核验监护关系的账号；设置不能超过法定上限。</p>';
        if (!children.length) h += '<p>暂无已核验的监护关系。可通过下方帮助入口申请核验。</p>';
        children.forEach(function (child) {
          var id = Number(child.playerId);
          h += '<form class="guardian-card" onsubmit="event.preventDefault();Game.Protection.saveGuardian(' + id + ')"><b>' + esc(child.username) + '</b>' +
            '<p>今日累计 ' + Math.floor(child.usedSeconds / 60) + ' 分钟 · 真实付费未开放</p>' +
            '<label>每日分钟数 <input id="guardianMinutes' + id + '" type="number" min="0" max="60" value="' + child.dailyLimitSeconds / 60 + '" required></label>' +
            '<label>结束时间 <input id="guardianEnd' + id + '" type="time" min="20:00" max="21:00" value="' + Math.floor(child.endMinute / 60) + ':' + String(child.endMinute % 60).padStart(2, '0') + '" required></label>' +
            '<label><input id="guardianPause' + id + '" type="checkbox"' + (child.paused ? ' checked' : '') + '> 暂停游戏</label>' +
            '<label><input id="guardianChat' + id + '" type="checkbox"' + (child.chatAllowed ? ' checked' : '') + '> 允许聊天与发消息</label>' +
            '<button class="btn" type="submit">保存设置</button></form>';
        });
        target.innerHTML = h;
      }).catch(function (err) { if (el('guardianPanel')) el('guardianPanel').textContent = err.message; });
    },
    saveGuardian: function (id) {
      var time = el('guardianEnd' + id).value.split(':');
      G.API.client.post('/guardian/restrictions', { playerId: id, dailyLimitSeconds: Number(el('guardianMinutes' + id).value) * 60,
        endMinute: Number(time[0]) * 60 + Number(time[1]), paused: el('guardianPause' + id).checked, chatAllowed: el('guardianChat' + id).checked })
        .then(function () { G.toast('监护设置已保存'); }).catch(function (err) { G.toast(err.message); });
    },
    help: function () {
      var kind = el('protectionRequestType').value;
      G.API.client.post('/protection/requests', { kind: kind, requestId: window.crypto.randomUUID() }, { retry: 0 })
        .then(function (data) { if (el('protectionHelpResult')) el('protectionHelpResult').textContent = '申请已登记，编号：' + data.id + '。请通过运营方客服渠道跟进。'; })
        .catch(function (err) { if (el('protectionHelpResult')) el('protectionHelpResult').textContent = err.message; });
    },
    render: function (view) {
      var d = this.data || {}, logged = G.API.isLoggedIn();
      var h = '<section class="protection-page"><h2>实名与健康游戏</h2>';
      h += '<p>未成年人仅可在周五、周六、周日及法定节假日的北京时间 20:00—21:00 游戏，具体以已公布的开放日历为准。家长设置可进一步收紧。</p>';
      h += '<p>离线期间战局继续，城池仍可能受到攻击。战斗、行军或军团事务不会延长游戏时间。</p>';
      if (this.message) h += '<p class="protection-message" role="status">' + esc(this.message) + '</p>';
      if (logged) {
        h += '<div class="panel"><b>' + esc(G.API.getUsername()) + '</b><p>' + esc(d.message || '正在确认游戏许可，请刷新状态。') + '</p>';
        h += '<p>实名状态：' + esc({ VERIFIED: '已核验', EXPIRED: '需要重新核验', UNVERIFIED: '未核验' }[d.identityStatus] || '待确认') + '</p>';
        if (d.minor) h += '<p>下次开放：' + esc(stamp(d.nextWindowStart)) + '</p>';
        h += '<div class="btn-row"><button class="btn" onclick="Game.Protection.refresh()">刷新状态</button>';
        if (d.canPlay) h += '<button class="btn ok" onclick="Game.Main.startGame()">进入游戏</button>';
        h += '</div></div>';
        if (d.identityStatus !== 'VERIFIED') {
          h += '<div class="panel"><h3>实名认证</h3><p>仅在正式核验服务中提交必要身份信息。本页不收集姓名、身份证或照片。</p>';
          if (d.localFixtures) h += '<p>本机测试身份：DEMO-ADULT（成年）、DEMO-PARENT（家长）、DEMO-TEEN（2010年出生）、DEMO-CHILD（2016年出生）。儿童同意与关系均为虚构测试数据。</p>';
          if (d.authorizationUrl) h += '<p><a href="' + esc(d.authorizationUrl) + '" target="_blank" rel="noopener noreferrer">前往实名核验服务</a></p>';
          if (d.providerAvailable) h += '<label for="identityProof">核验服务返回的凭据</label><input id="identityProof" type="password" autocomplete="off" maxlength="2048"><button id="verifyIdentity" class="btn" onclick="Game.Protection.verify()">提交核验</button>';
          else h += '<p>实名服务暂不可用，当前无法进入游戏。你仍可办理账号与隐私事项。</p>';
          h += '</div>';
        }
        h += '<div class="panel"><button class="btn" onclick="Game.Protection.guardian()">家长监护管理</button><div id="guardianPanel"></div></div>';
        h += '<div class="panel"><h3>帮助与权利申请</h3><p>仅登记申请类型与账号编号，请勿在游戏聊天中提交证件或其他隐私材料。</p>' +
          '<label for="protectionRequestType">申请类型</label><select id="protectionRequestType"><option value="IDENTITY">身份申诉/更正</option><option value="GUARDIAN">监护关系核验/申诉</option><option value="REFUND">消费退款</option><option value="PRIVACY">个人信息权利</option><option value="REPORT">投诉举报</option></select>' +
          '<button class="btn" onclick="Game.Protection.help()">登记申请</button><p id="protectionHelpResult" role="status"></p>';
        if (d.supportUrl) h += '<a href="' + esc(d.supportUrl) + '" target="_blank" rel="noopener noreferrer">联系运营方客服</a>';
        else h += '<p>运营方客服渠道尚未配置，登记不代表已经完成审核。</p>';
        h += '</div><div class="btn-row"><button class="btn" onclick="Game.Account.open()">注销账号</button><button class="btn" onclick="Game.Main.logout(\'exit\')">退出账号</button></div>';
      } else h += '<button class="btn" onclick="Game.go(\'login\')">返回登录</button>';
      h += '<p><a href="privacy.html" target="_blank" rel="noopener">实名与儿童个人信息说明</a></p></section>';
      view.innerHTML = h;
    }
  };
  G.Protection = P;
  G.Core.views.protection = function (view) { P.render(view); };
  if (window.addEventListener) window.addEventListener('storage', function (event) {
    if (event.key === P.key() && event.oldValue !== event.newValue && !P.blocked) {
      P.denied({ message: '其他页面已更新游戏会话，请重新确认。' });
    }
  });
  document.addEventListener('visibilitychange', function () { if (!document.hidden) P.tick(); });
})(window.Game);
