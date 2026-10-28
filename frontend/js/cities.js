/* global window, document */
window.Game = window.Game || {};
(function (G) {
  'use strict';
  G.gameNow = G.gameNow || function () { return Date.now(); };
  var focusBefore = null;
  var timer = null;
  var panelMode = 'list';
  function state() { return (G.Core && G.Core.state) || G.state || {}; }
  function esc(value) { return G.escapeHtml(String(value == null ? '' : value)); }
  function overview() { return state().cityOverview || { cities: [], count: 1, cap: 1 }; }
  /** 说明军衔可解锁的容量；名额是否用满由城市数量单独提示。 */
  function rankTip(info) { return info.nextRankName ? '晋升' + info.nextRankName + '后，可拥有 ' + info.nextCap + ' 座城市' : '当前军衔最多可拥有 ' + info.cap + ' 座城市'; }
  function remaining(time) { return G.formatDuration((time - G.gameNow()) / 1000); }
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
      var pending = city.readyAt > G.gameNow();
      html += '<div class="city-list-row' + (city.current ? ' selected' : '') + '">';
      html += '<div class="city-row-main">';
      html += '<button type="button" class="city-choice" data-ready-at="' + city.readyAt + '" data-city-id="' + city.id + '" onclick="Game.Cities.select(' + city.id + ')"' + (pending || city.current ? ' disabled' : '') + '>';
      html += '<span class="city-choice-heading"><b>' + esc(city.name) + '</b><span class="city-tag">' + (city.coastal ? '沿海' : city.legacyNaval ? '海军补给' : city.main ? '主城' : '分城') + '</span><span>Lv.' + city.level + '</span></span>';
      html += '<span class="city-choice-meta">坐标 ' + city.x + ',' + city.y + '<span class="city-ready-label">' + (pending ? '建设中 · ' + remaining(city.readyAt) : city.frozen ? '归降移交中 · 只读' : city.current ? '当前城市' : '点击切换') + '</span></span>';
      if (city.incoming) html += '<span class="city-danger">敌军来袭！</span>';
      html += '</button>';
      if (!city.current && !pending && !city.frozen) {
        html += '<div class="city-row-actions"><button type="button" class="city-text-btn" onclick="Game.Cities.transferForm(' + city.id + ',\'transport\')">[运输]</button><button type="button" class="city-text-btn" onclick="Game.Cities.transferForm(' + city.id + ',\'rebase\')">[派遣]</button></div>';
      }
      html += '</div>';
      if (!pending && !city.frozen) {
        var ownFood = Math.ceil(Number(city.ownFoodPerHour) || 0);
        var supFood = Math.ceil(Number(city.supportFoodPerHour) || 0);
        var ownFoodStr = G.fmt ? G.fmt(ownFood) : ownFood;
        var supFoodStr = G.fmt ? G.fmt(supFood) : supFood;
        html += '<div class="city-row-extra">';
        html += '<label class="city-garrison-check"><input type="checkbox"' + (city.acceptGarrison ? ' checked' : '') + ' onchange="Game.Cities.setGarrison(' + city.id + ',this.checked)"><span>允许接收军团驻军（本城承担粮耗）</span></label>';
        html += '<div class="city-food-stat">本城部队粮耗 ' + ownFoodStr + '/小时 · 外来驻军粮耗 ' + supFoodStr + '/小时</div>';
        if (Number(city.garrisonFoodUnmet) > 0) {
          var unmet = Math.ceil(city.garrisonFoodUnmet);
          var unmetStr = G.fmt ? G.fmt(unmet) : unmet;
          html += '<div class="city-shortage-warn">曾发生补给短缺，未满足粮耗 ' + unmetStr + '；此数为历史记录，不是欠款。</div>';
        }
        html += '</div>';
      }
      html += '</div>';
    });
    html += '</div><p class="city-hint">' + esc(rankTip(info)) + '</p>';
    html += info.count < info.cap ? '<button class="btn ok city-wide" onclick="Game.Cities.foundForm()">＋ 建立新城（剩余 ' + (info.cap - info.count) + ' 个名额）</button>' : '<p class="city-hint">城市数量已达上限</p>';
    html += '<button class="btn city-wide" onclick="Game.Cities.close();Game.go(\'mainQuest\')">查看军衔</button>';
    html += '<button class="btn city-wide" onclick="Game.CityTeleport.open()">传送当前城市</button>';
    contents(html);
  }
  var supportTargets = [], supportRequest = 0;
  G.Cities = {
    switching: false,
    /** 每城独立授权；关闭后已驻扎部队继续参战、耗粮，可在军情中遣返。 */
    setGarrison: function (cityId, allowed) {
      G.API.client.post('/game/cities/garrison', { cityId: cityId, allowed: allowed }).then(function (data) {
        state().cityOverview = data; drawList();
        G.toast(allowed ? '已开放驻军接收，驻防粮耗由本城承担；缺粮时未参战驻军将自动返程' : '已关闭新驻军接收；现有驻军保留');
      }).catch(function (e) { error(e); drawList(); });
    },
    nav: function () {
      var s = state(), p = s.player || {}, info = overview();
      var alert = info.cities.some(function (city) { return city.incoming; });
      return '<button type="button" class="navitem city-switch" onclick="Game.Cities.open()" aria-haspopup="dialog" aria-label="切换城市" title="当前城市：' + esc(p.cityName || '新城市') + '（' + (p.mainCity === false ? '分城' : '主城') + '）"><span class="navlabel">切城</span>' + (alert ? '<i class="city-alert-dot" aria-label="有城市遭到攻击"></i>' : '') + '</button>';
    },
    close: function () {
      if (this.switching) return;
      supportTargets = []; supportRequest++;
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
      mask.innerHTML = '<section id="cityDialog" class="city-dialog" role="dialog" aria-modal="true" aria-labelledby="cityDialogTitle"><div class="city-dialog-heading"><b id="cityDialogTitle">切城</b><button class="btn sm" onclick="Game.Cities.close()" aria-label="关闭城市列表">✕</button></div><div id="cityDialogContent"></div></section>';
      mask.addEventListener('click', function (event) { if (event.target === mask) G.Cities.close(); });
      document.body.appendChild(mask); drawList();
      document.addEventListener('keydown', dismissKey);
      mask.querySelector('button').focus();
      timer = setInterval(function () {
        mask.querySelectorAll('[data-ready-at]').forEach(function (button) {
          var readyAt = Number(button.getAttribute('data-ready-at'));
          var city = overview().cities.find(function (c) { return String(c.id) === button.getAttribute('data-city-id'); });
          if (!city || city.current) return;
          button.disabled = readyAt > G.gameNow();
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
      if (G.CityTeleport && G.CityTeleport.isBusy()) return;
      if (G.CityTeleport) G.CityTeleport.close();
      if (G.UnitEquipment && G.UnitEquipment.isBusy()) return;
      if (G.UnitEquipment && G.UnitEquipment.hasUnsaved()) { G.UnitEquipment.close(false); return; }
      if (G.UnitEquipment) G.UnitEquipment.close(true);
      if (this.switching) return;
      var city = overview().cities.find(function (c) { return c.id === cityId; });
      if (!city || city.current || city.readyAt > G.gameNow()) return;
      this.switching = true;
      G.API.client.invalidateCityRequests();
      contents('<p class="city-hint" role="status">正在切换到 ' + esc(city.name) + '…</p>');
      G.API.client.post('/game/cities/switch', { cityId: cityId }).then(function (data) {
        G.API.applyState(data.state);
        if (G.WorldView) G.WorldView.invalidate();
        if (G.WorldMap) G.WorldMap.invalidate();
        // Discard city-specific operation drafts. Public pages (mail, guild, reports) stay open.
        if (G.World) { G.World._dispatchTarget = null; }
        if (G.Main && G.Main.closePlayerDrawer) { G.Main.closePlayerDrawer(); } else { var drawer = document.getElementById('playerDrawerMask'); if (drawer) drawer.remove(); if (document.body && document.body.classList) document.body.classList.remove('drawer-open'); }
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
      var html = '<p class="city-hint">地图空地建城无需临海，必须有连续完整的 2×2（共4格）未占用陆地；沿海城市可建港口，内陆城市不可建港口。已占领的丘陵野地也可建城，建设期间占用名额。</p>';
      html += '<button class="btn ok city-wide" onclick="Game.Cities.close();Game.go(\'world\');Game.WorldMap.setMode(\'map\')">前往地图选择空地</button>';
      html += '<p>建城费用：粮食 5,000 · 钢铁 10,000 · 石油 5,000 · 稀矿 2,000 · 黄金 10,000。建设需 30 分钟，费用从当前城市扣除。</p>';
      html += '<p class="city-hint">建成后拥有1级市政厅、民居、农田和炼钢厂，50人口，以及五种资源各1,000；驻军需自行训练或调遣。</p>';
      if (!(info.sites || []).length) {
        html += '<p class="city-hint">尚无已占领的丘陵野地，也可直接在地图选择空地建城。</p><button class="btn ok" onclick="Game.Cities.close();Game.go(\'world\')">前往地图</button>';
      } else {
        html += '<form id="cityFoundForm"><label>城市名称<input name="name" class="qty" maxlength="12" required placeholder="最多12个字符，可用标点、符号和 emoji"></label><label>建城位置<select name="wildId">';
        info.sites.forEach(function (site) { html += '<option value="' + site.id + '">' + (site.type === 'forest' ? '森林' : '丘陵') + ' (' + site.x + ',' + site.y + ')</option>'; });
        html += '</select></label><button class="btn ok city-wide" type="submit">支付资源并开始建城</button></form>';
      }
      html += '<button class="btn city-wide" onclick="Game.Cities.back()">返回城市列表</button>'; contents(html);
      var form = document.getElementById('cityFoundForm');
      if (form) form.addEventListener('submit', function (event) {
        event.preventDefault(); var button = form.querySelector('button'); if (button.disabled) return;
        var cityName = form.elements.name ? form.elements.name.value : '';
        try {
          cityName = G.normalizeDisplayName ? G.normalizeDisplayName(cityName, 12, '城市名称') : cityName.trim();
        } catch (err) {
          G.toast(err.message);
          return;
        }
        button.disabled = true;
        G.API.client.post('/game/cities', { name: cityName, wildId: Number(form.elements.wildId.value) }).then(function (data) {
          G.API.applyState(data.state); if (G.WorldView) G.WorldView.invalidate();
          if (G.WorldMap) G.WorldMap.invalidate();
          drawList(); G.Core.refreshTop(); G.toast(data.message);
        }).catch(function (e) { button.disabled = false; error(e); });
      });
    },
    back: drawList,
    /** 从列表或战略地图打开己方目标城的运输、派遣表单。 */
    openTransfer: function (cityId, action) {
      if (!document.getElementById('cityDialogMask')) this.open();
      this.transferForm(cityId, action);
    },
    /** 从成员名录或地图读取同军团成员的城市，服务端重验支援权限。 */
    openSupport: function (playerId, action, cityId) {
      this.open();
      panelMode = 'support';
      var request = ++supportRequest;
      contents('<p class="city-hint" role="status">正在读取成员城市...</p>');
      G.API.client.get('/game/world/support/cities?playerId=' + Number(playerId), { silent: true }).then(function (cities) {
        if (request !== supportRequest || !document.getElementById('cityDialogMask')) return;
        supportTargets = cities;
        var title = document.getElementById('cityDialogTitle');
        if (title) title.textContent = action === 'transport' ? '军团物资运输' : '军团驻防';
        if (cityId) { G.Cities.transferForm(cityId, action); return; }
        var html = '<div class="city-list">';
        cities.forEach(function (city) {
          if (action !== 'transport' && !city.acceptGarrison) return;
          html += '<div class="city-list-row"><div class="city-row-main"><button type="button" class="city-choice" onclick="Game.Cities.transferForm(' + Number(city.id) + ',\'' + (action === 'transport' ? 'transport' : 'reinforce') + '\')"><span class="city-choice-heading"><b>' + esc(city.name) + '</b></span><span class="city-choice-meta">坐标 ' + city.x + ',' + city.y + '</span></button></div></div>';
        });
        var eligible = cities.filter(function (city) { return action === 'transport' || city.acceptGarrison; });
        contents(html + '</div>' + (!eligible.length ? '<p class="city-hint">暂无开放接收的城市，请团友先在城市管理中开启驻军接收。</p>' : ''));
      }).catch(function (e) {
        if (request !== supportRequest) return;
        contents('<p class="city-hint" role="alert">' + esc(e.message || '成员城市读取失败') + '</p>');
      });
    },
    /** 运输只派后勤单位往返；派遣将选中的任意兵种留驻目标城市。 */
    transferForm: function (cityId, action) {
      panelMode = 'transfer';
      var s = state(), city = overview().cities.find(function (c) { return c.id === cityId; }) || supportTargets.find(function (c) { return c.id === cityId; });
      if (action === 'reinforce' && city && !city.acceptGarrison) { G.toast('目标城市未开放驻军接收，请团友先开启'); return; }
      if (!city || city.current || city.readyAt > G.gameNow()) return;
      var supporting = supportTargets.indexOf(city) >= 0;
      action = action === 'transport' ? 'transport' : supporting ? 'reinforce' : 'rebase';
      var transporting = action === 'transport';
      var html = '<p>从 <b>' + esc(s.player.cityName) + '</b> 前往 <b>' + esc(city.name) + '</b></p><form id="cityTransferForm"><input type="hidden" name="action" value="' + action + '"><p class="city-hint">' + (transporting ? '运输：卡车或运输机送达物资后返回出发城市。' : supporting ? '驻防部队和随队军官独立参战，由城主统一指挥；驻防粮耗由接收城市承担，缺粮时未参战驻军自动返程。可在军情中撤回。' : '派遣：选中的兵种抵达后留驻目标城市。') + '</p><div class="city-transfer-grid">';
      Object.keys(s.army || {}).forEach(function (key) {
        if (s.army[key] <= 0) return;
        if (transporting && key !== 'truck' && key !== 'transport') return;
        var def = G.DATA.units[key];
        if (transporting) {
          var stock = Math.max(0, Math.floor(Number(s.army[key]) || 0));
          html += '<div class="city-transfer-unit"><label for="city-transfer-unit-' + key + '">' + esc(def ? def.name : key) + '</label>' +
            '<span class="city-transfer-stock">城内 ' + esc(G.fmt ? G.fmt(stock) : stock) + ' · 剩余 <span data-unit-remaining="' + key + '">' + esc(G.fmt ? G.fmt(stock) : stock) + '</span></span>' +
            '<div class="city-transfer-resource-controls"><input class="qty recruit-qty" id="city-transfer-unit-' + key + '" data-unit="' + key + '" type="number" min="0" max="' + stock + '" step="1" value="0">' +
            '<div class="recruit-slider-wrap"><input class="recruit-slider" data-unit-range="' + key + '" type="range" min="0" max="' + stock + '" step="1" value="0" style="--p:0%" aria-label="' + esc(def ? def.name : key) + '运输数量滑动轴"></div></div></div>';
        } else html += '<label>' + esc(def ? def.name : key) + '（' + s.army[key] + '）<input class="qty" data-unit="' + esc(key) + '" type="number" min="0" max="' + s.army[key] + '" step="1" value="0"></label>';
      });
      html += '</div><p class="city-hint">' + (transporting ? '请选择卡车或运输机并填写物资数量。' : '至少选择一种兵种；携带资源需要相应运力。') + '</p><div class="city-transfer-grid">';
      [['food','粮食'],['steel','钢铁'],['oil','石油'],['rare','稀矿'],['gold','黄金']].forEach(function (pair) {
        var available = Math.max(0, Math.floor(Number((s.resources || {})[pair[0]]) || 0));
        html += '<div class="city-transfer-resource"><label for="city-transfer-' + pair[0] + '">' + pair[1] + '</label>' +
          '<span class="city-transfer-stock">城内 ' + esc(G.fmt ? G.fmt(available) : available) + ' · 剩余 <span data-resource-remaining="' + pair[0] + '">' + esc(G.fmt ? G.fmt(available) : available) + '</span></span>' +
          '<div class="city-transfer-resource-controls"><input class="qty recruit-qty" id="city-transfer-' + pair[0] + '" data-resource="' + pair[0] + '" type="number" min="0" max="' + available + '" step="1" value="0">' +
          '<div class="recruit-slider-wrap"><input class="recruit-slider" data-resource-range="' + pair[0] + '" type="range" min="0" max="' + available + '" step="1" value="0" style="--p:0%" aria-label="' + pair[1] + '运输数量滑动轴"' + (available ? '' : ' disabled') + '></div></div></div>';
      });
      html += '</div><p id="cityTransferLoad" class="city-hint" aria-live="polite">携带资源 0 / 部队负重 0</p><label>随行军官<select name="commander"><option value="">不指定</option>';
      var marchingCommanderIds = new Set();
      (((s.world && s.world.marches) || (G.Core && G.Core.state && G.Core.state.world && G.Core.state.world.marches)) || []).forEach(function (m) {
        if (m.commanderId != null) {
          marchingCommanderIds.add(Number(m.commanderId));
          marchingCommanderIds.add(String(m.commanderId));
        }
      });
      (s.officers || []).filter(function (o) {
        return o && o.role !== 'mayor' && o.role !== 'commander' && o.role !== 'march' &&
          !marchingCommanderIds.has(Number(o.id)) && !marchingCommanderIds.has(String(o.id));
      }).forEach(function (o) { html += '<option value="' + o.id + '">' + esc(o.name) + '</option>'; });
      html += '</select></label><div id="cityRoutePreview" class="city-hint" aria-live="polite"></div><button type="submit" class="btn ok city-wide">确认' + (transporting ? '运输' : supporting ? '驻防' : '派遣') + '</button></form><button class="btn city-wide" onclick="Game.Cities.' + (supporting ? 'close()">关闭' : 'back()">返回城市列表') + '</button>'; contents(html);
      var routeForm=document.getElementById('cityTransferForm');
      if(G.World&&G.World.bindRoutePreview)G.World.bindRoutePreview(routeForm,document.getElementById('cityRoutePreview'),function(){var army={};routeForm.querySelectorAll('[data-unit]').forEach(function(el){army[el.dataset.unit]=Math.max(0,parseInt(el.value,10)||0);});return {targetKind:'player',targetId:cityId,action:routeForm.elements.action.value,army:army};},{start:s.player.cityName,end:city.name,updateOnInput:true});
      document.getElementById('cityTransferForm').addEventListener('input', function (event) {
        var changed = event.target, dataset = changed.dataset || {};
        var kind = dataset.unitRange || dataset.unit ? 'unit' : 'resource';
        var key = kind === 'unit' ? (dataset.unitRange || dataset.unit) : (dataset.resourceRange || dataset.resource);
        if (key) {
          var number = this.querySelector('[data-' + kind + '="' + key + '"]');
          var range = this.querySelector('[data-' + kind + '-range="' + key + '"]');
          if (range) {
            // 已选数量与城内剩余互补，控件上限始终是出发城市的该项库存。
            var stock = Number(number.max), selected = Math.max(0, Math.min(stock, Math.floor(Number(changed.value) || 0)));
            number.value = range.value = String(selected);
            range.style.setProperty('--p', (stock ? selected / stock * 100 : 0).toFixed(1) + '%');
            var remaining = this.querySelector('[data-' + kind + '-remaining="' + key + '"]');
            remaining.textContent = G.fmt ? G.fmt(stock - selected) : String(stock - selected);
          }
        }
        var load = 0, carry = 0;
        this.querySelectorAll('[data-unit]').forEach(function (input) { load += Number(input.value) * (G.DATA.units[input.dataset.unit].load || 0) * (G.UnitEquipment ? G.UnitEquipment.mul(G.UnitEquipment.snapshot(), input.dataset.unit, 'load') : 1); });
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
        if (!Object.keys(army).length) { G.toast('请至少选择一种兵种'); return; }
        if (transporting && !Object.keys(resources).some(function (key) { return resources[key] > 0; })) { G.toast('请填写要运输的物资'); return; }
        var request = { unitEquipmentVersion: G.World && G.World._currentRoute && G.World._currentRoute.unitEquipmentVersion, targetKind: 'player', targetId: cityId, action: form.elements.action.value, army: army, carryRes: resources, commanderId: Number(form.elements.commander.value) || null };
        // 驻防他人城市属于军事参与，复用出征确认；取消时保留表单和保护。
        var send = function (confirmed) {
          if (button.disabled || document.getElementById('cityTransferForm') !== form) return;
          button.disabled = true;
          if (confirmed) request.abandonNewbieProtection = true;
          G.API.client.post('/game/world/dispatch', request).then(function (data) {
            G.API.applyState(data.state); G.Cities.close(); G.Core.render(); G.toast('部队已出发，可在地图查看行军进度');
          }).catch(function (e) { button.disabled = false; error(e); });
        };
        if (G.World && G.World.confirmPlayerWarfare) G.World.confirmPlayerWarfare(supporting && request.action === 'reinforce', send);
        else send(false);
      });
    }
  };
})(window.Game);
