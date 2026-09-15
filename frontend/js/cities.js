/* global window, document */
window.Game = window.Game || {};
(function (G) {
  'use strict';
  var focusBefore = null;
  var timer = null;
  var panelMode = 'list';
  function state() { return (G.Core && G.Core.state) || G.state || {}; }
  function esc(value) { return G.escapeHtml(String(value == null ? '' : value)); }
  function overview() { return state().cityOverview || { cities: [], count: 1, cap: 1 }; }
  function rankTip(info) { return info.nextRankName ? '晋升' + info.nextRankName + '后，可拥有 ' + info.nextCap + ' 座城市' : '已达到最高城市上限'; }
  function remaining(time) { return Math.max(1, Math.ceil((time - Date.now()) / 60000)) + ' 分钟'; }
  function error(e) { G.toast(e.message || '操作失败，请重试'); }
  function contents(html) {
    var content = document.getElementById('cityDialogContent');
    if (content) content.innerHTML = html;
  }
  function dismissKey(event) {
    if (event.key === 'Escape') G.Cities.close();
    if (event.key !== 'Tab') return;
    var dialog = document.getElementById('cityDialog');
    if (!dialog) return;
    var nodes = dialog.querySelectorAll('button:not(:disabled), input, select');
    if (!nodes.length) return;
    var first = nodes[0], last = nodes[nodes.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  }
  function drawList() {
    panelMode = 'list';
    var info = overview();
    var html = '<div class="city-overview"><b>我的城市 ' + info.count + ' / ' + info.cap + '</b><span>' + esc(info.rankName || '') + '</span></div>';
    html += '<div class="city-list">';
    info.cities.forEach(function (city) {
      var pending = city.readyAt > Date.now();
      html += '<div class="city-list-row' + (city.current ? ' selected' : '') + '">';
      html += '<button class="city-choice" data-ready-at="' + city.readyAt + '" data-city-id="' + city.id + '" onclick="Game.Cities.select(' + city.id + ')"' + (pending || city.current ? ' disabled' : '') + '>';
      html += '<span class="city-choice-heading"><b>' + esc(city.name) + '</b><span class="city-tag">' + (city.coastal ? '沿海' : city.legacyNaval ? '海军补给' : city.main ? '主城' : '分城') + '</span><span>Lv.' + city.level + '</span></span>';
      html += '<span class="city-choice-meta">坐标 ' + city.x + ',' + city.y + '<span class="city-ready-label">' + (pending ? '建设中 · ' + remaining(city.readyAt) : city.current ? '当前城市' : '点击切换') + '</span></span>';
      if (city.incoming) html += '<span class="city-danger">敌军来袭！</span>';
      html += '</button>';
      if (!city.current && !pending) html += '<button class="btn sm" onclick="Game.Cities.transferForm(' + city.id + ')">调遣</button>';
      html += '</div>';
    });
    html += '</div><p class="city-hint">' + esc(rankTip(info)) + '</p>';
    html += info.count < info.cap ? '<button class="btn ok city-wide" onclick="Game.Cities.foundForm()">＋ 建立新城（剩余 ' + (info.cap - info.count) + ' 个名额）</button>' : '<p class="city-hint">城市数量已达上限</p>';
    html += '<button class="btn city-wide" onclick="Game.Cities.close();Game.go(\'mainQuest\')">查看军衔</button>';
    contents(html);
  }
  G.Cities = {
    switching: false,
    nav: function () {
      var s = state(), p = s.player || {}, info = overview();
      var alert = info.cities.some(function (city) { return city.incoming; });
      return '<button type="button" class="navitem city-switch" onclick="Game.Cities.open()" aria-haspopup="dialog" aria-label="切换城市" title="当前城市：' + esc(p.cityName || '新城市') + '（' + (p.mainCity === false ? '分城' : '主城') + '）"><span class="navlabel">切换</span>' + (alert ? '<i class="city-alert-dot" aria-label="有城市遭到攻击"></i>' : '') + '</button>';
    },
    close: function () {
      if (this.switching) return;
      var mask = document.getElementById('cityDialogMask');
      if (mask) mask.remove();
      if (timer) { clearInterval(timer); timer = null; }
      document.removeEventListener('keydown', dismissKey);
      if (focusBefore && focusBefore.isConnected && focusBefore.focus) focusBefore.focus();
    },
    open: function () {
      if (this.switching) return;
      this.close(); focusBefore = document.activeElement;
      var mask = document.createElement('div'); mask.id = 'cityDialogMask'; mask.className = 'city-dialog-mask';
      mask.innerHTML = '<section id="cityDialog" class="city-dialog" role="dialog" aria-modal="true" aria-labelledby="cityDialogTitle"><div class="city-dialog-heading"><b id="cityDialogTitle">城市切换</b><button class="btn sm" onclick="Game.Cities.close()" aria-label="关闭城市列表">✕</button></div><div id="cityDialogContent"></div></section>';
      mask.addEventListener('click', function (event) { if (event.target === mask) G.Cities.close(); });
      document.body.appendChild(mask); drawList();
      document.addEventListener('keydown', dismissKey);
      mask.querySelector('button').focus();
      timer = setInterval(function () {
        mask.querySelectorAll('[data-ready-at]').forEach(function (button) {
          var readyAt = Number(button.getAttribute('data-ready-at'));
          var city = overview().cities.find(function (c) { return String(c.id) === button.getAttribute('data-city-id'); });
          if (!city || city.current) return;
          button.disabled = readyAt > Date.now();
          button.querySelector('.city-ready-label').textContent = button.disabled ? '建设中 · ' + remaining(readyAt) : '点击切换';
        });
      }, 1000);
      G.API.client.get('/game/cities', { silent: true }).then(function (data) {
        if (!document.getElementById('cityDialogMask') || G.Cities.switching) return;
        state().cityOverview = data; if (panelMode === 'list') drawList();
      }).catch(error);
    },
    enter: function (cityId) {
      var city = overview().cities.find(function (c) { return c.id === cityId; });
      if (city && city.current) { G.go('home'); return; }
      this.select(cityId, true);
    },
    select: function (cityId, goHome) {
      if (this.switching) return;
      var city = overview().cities.find(function (c) { return c.id === cityId; });
      if (!city || city.current || city.readyAt > Date.now()) return;
      this.switching = true;
      G.API.client.invalidateCityRequests();
      contents('<p class="city-hint" role="status">正在切换到 ' + esc(city.name) + '…</p>');
      G.API.client.post('/game/cities/switch', { cityId: cityId }).then(function (data) {
        G.API.applyState(data.state);
        if (G.WorldView) G.WorldView.invalidate();
        // Discard city-specific operation drafts. Public pages (mail, guild, reports) stay open.
        if (G.World) { G.World._dispatchTarget = null; }
        var drawer = document.getElementById('playerDrawerMask'); if (drawer) drawer.remove();
        G.Cities.switching = false; G.Cities.close();
        if (goHome) G.Core.route = 'home';
        if (['mail', 'guild', 'reports', 'mainQuest', 'shop', 'recharge'].indexOf(G.Core.route) >= 0) {
          G.Core.renderTop(); if (G.Main) G.Main.renderNavBar();
        } else G.Core.render();
        G.toast('已切换至 ' + city.name);
      }).catch(function (e) { G.Cities.switching = false; drawList(); error(e); });
    },
    foundForm: function () {
      panelMode = 'found';
      var info = overview();
      if (info.count >= info.cap) return;
      var html = '<p class="city-hint">平原、草原可建平原城市，丘陵可建山城；靠近海岸的平原、丘陵、沙滩可建海城。每次建城必须有连续完整的 2×2（共4格）可建地块，建设期间占用名额。</p>';
      html += '<button class="btn ok city-wide" onclick="Game.Cities.close();Game.go(\'world\');Game.WorldMap.setMode(\'map\')">前往地图选择沿海空地</button>';
      html += '<p>建城费用：粮食 5,000 · 钢铁 10,000 · 石油 5,000 · 稀矿 2,000 · 黄金 10,000。建设需 30 分钟，费用从当前城市扣除。</p>';
      html += '<p class="city-hint">建成后拥有1级市政厅、民居、农田和炼钢厂，50人口，以及五种资源各1,000；驻军需自行训练或调遣。</p>';
      if (!(info.sites || []).length) {
        html += '<p class="city-hint">尚无合适地块，请先在地图占领森林或丘陵。</p><button class="btn ok" onclick="Game.Cities.close();Game.go(\'world\')">前往地图</button>';
      } else {
        html += '<form id="cityFoundForm"><label>城市名称<input name="name" class="qty" maxlength="12" required placeholder="1—12个中英文、数字或下划线"></label><label>建城位置<select name="wildId">';
        info.sites.forEach(function (site) { html += '<option value="' + site.id + '">' + (site.type === 'forest' ? '森林' : '丘陵') + ' (' + site.x + ',' + site.y + ')</option>'; });
        html += '</select></label><button class="btn ok city-wide" type="submit">支付资源并开始建城</button></form>';
      }
      html += '<button class="btn city-wide" onclick="Game.Cities.back()">返回城市列表</button>'; contents(html);
      var form = document.getElementById('cityFoundForm');
      if (form) form.addEventListener('submit', function (event) {
        event.preventDefault(); var button = form.querySelector('button'); if (button.disabled) return;
        button.disabled = true;
        G.API.client.post('/game/cities', { name: form.elements.name.value.trim(), wildId: Number(form.elements.wildId.value) }).then(function (data) {
          G.API.applyState(data.state); if (G.WorldView) G.WorldView.invalidate();
          drawList(); G.Core.refreshTop(); G.toast(data.message);
        }).catch(function (e) { button.disabled = false; error(e); });
      });
    },
    back: drawList,
    transferForm: function (cityId) {
      panelMode = 'transfer';
      var s = state(), city = overview().cities.find(function (c) { return c.id === cityId; });
      if (!city || city.current) return;
      var html = '<p>从 <b>' + esc(s.player.cityName) + '</b> 前往 <b>' + esc(city.name) + '</b></p><form id="cityTransferForm"><label>任务<select name="action"><option value="rebase">调遣：部队及资源留驻目标城</option><option value="transport">运输：送达资源后部队返回</option></select></label><div class="city-transfer-grid">';
      Object.keys(s.army || {}).forEach(function (key) {
        if (s.army[key] <= 0) return;
        var def = G.DATA.units[key];
        html += '<label>' + esc(def ? def.name : key) + '（' + s.army[key] + '）<input class="qty" data-unit="' + esc(key) + '" type="number" min="0" max="' + s.army[key] + '" step="1" value="0"></label>';
      });
      html += '</div><p class="city-hint">至少选择一种部队。携带资源需要卡车等运输单位，行军从当前城市出发。</p><div class="city-transfer-grid">';
      [['food','粮食'],['steel','钢铁'],['oil','石油'],['rare','稀矿'],['gold','黄金']].forEach(function (pair) {
        html += '<label>' + pair[1] + '<input class="qty" data-resource="' + pair[0] + '" type="number" min="0" max="' + (s.resources[pair[0]] || 0) + '" step="1" value="0"></label>';
      });
      html += '</div><p id="cityTransferLoad" class="city-hint" aria-live="polite">携带资源 0 / 部队负重 0</p><label>随行军官<select name="commander"><option value="">不携带军官</option>';
      (s.officers || []).filter(function (o) { return o.role !== 'mayor' && o.role !== 'march'; }).forEach(function (o) { html += '<option value="' + o.id + '">' + esc(o.name) + '</option>'; });
      html += '</select></label><div id="cityRoutePreview" class="city-hint" aria-live="polite"></div><button type="submit" class="btn ok city-wide">确认派遣</button></form><button class="btn city-wide" onclick="Game.Cities.back()">返回城市列表</button>'; contents(html);
      var routeForm=document.getElementById('cityTransferForm');
      if(G.World&&G.World.bindRoutePreview)G.World.bindRoutePreview(routeForm,document.getElementById('cityRoutePreview'),function(){var army={};routeForm.querySelectorAll('[data-unit]').forEach(function(el){army[el.dataset.unit]=Math.max(0,parseInt(el.value,10)||0);});return {targetKind:'player',targetId:cityId,action:routeForm.elements.action.value,army:army};},{start:s.player.cityName,end:city.name});
      document.getElementById('cityTransferForm').addEventListener('input', function () {
        var load = 0, carry = 0;
        this.querySelectorAll('[data-unit]').forEach(function (input) { load += Number(input.value) * (G.DATA.units[input.dataset.unit].load || 0); });
        this.querySelectorAll('[data-resource]').forEach(function (input) { carry += Number(input.value); });
        var hint = document.getElementById('cityTransferLoad');
        hint.textContent = '携带资源 ' + carry + ' / 部队负重 ' + load;
        hint.style.color = carry > load ? 'var(--danger)' : '';
      });
      document.getElementById('cityTransferForm').addEventListener('submit', function (event) {
        event.preventDefault(); var form = event.target, button = form.querySelector('button'); if (button.disabled) return;
        var army = {}, resources = {};
        form.querySelectorAll('[data-unit]').forEach(function (input) { if (Number(input.value) > 0) army[input.dataset.unit] = Number(input.value); });
        form.querySelectorAll('[data-resource]').forEach(function (input) { resources[input.dataset.resource] = Number(input.value); });
        button.disabled = true;
        G.API.client.post('/game/world/dispatch', { targetKind: 'player', targetId: cityId, action: form.elements.action.value, army: army, carryRes: resources, commanderId: Number(form.elements.commander.value) || null }).then(function (data) {
          G.API.applyState(data.state); G.Cities.close(); G.Core.render(); G.toast('部队已出发，可在地图查看行军进度');
        }).catch(function (e) { button.disabled = false; error(e); });
      });
    }
  };
})(window.Game);
