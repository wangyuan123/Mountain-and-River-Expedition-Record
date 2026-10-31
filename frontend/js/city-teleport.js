/* global window, document */
(function (G) {
  'use strict';
  var session = 0, busy = false, focusBefore = null;
  function state() { return G.Core.state; }
  function identity() { return (G.API.getToken ? G.API.getToken() : '') + ':' + ((state().player || {}).activeCityId || ''); }
  function esc(value) { return G.escapeHtml(String(value == null ? '' : value)); }
  function dismissKey(event) {
    if (event.key === 'Escape') G.CityTeleport.close();
    if (event.key !== 'Tab') return;
    var mask = document.getElementById('cityTeleportMask');
    if (!mask) return;
    var nodes = mask.querySelectorAll('button:not(:disabled), input:not(:disabled), select:not(:disabled)');
    if (!nodes.length) return;
    var first = nodes[0], last = nodes[nodes.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  }

  G.CityTeleport = {
    isBusy: function () { return busy; },
    close: function () {
      if (busy) return;
      session++;
      var mask = document.getElementById('cityTeleportMask');
      if (mask) mask.remove();
      document.removeEventListener('keydown', dismissKey);
      if (focusBefore && focusBefore.isConnected && focusBefore.focus) focusBefore.focus();
    },
    /** 仓库、城市管理及地图选点共用确认界面；打开和预览均不扣道具。 */
    open: function (itemId, x, y) {
      if (busy) return;
      if (G.Cities) G.Cities.close();
      this.close(); focusBefore = document.activeElement;
      var owner = identity(), activeCity = (state().player || {}).activeCityId;
      var city = ((state().cityOverview || {}).cities || []).find(function (c) { return String(c.id) === String(activeCity); });
      var normal = Number((state().items || {}).teleportNormal || 0), advanced = Number((state().items || {}).teleportAdvanced || 0);
      var quadrant = ((G.DATA.world || {}).quadrantSize || 400), max = ((G.DATA.world || {}).size || 800) - 2;
      var fields = G.Constants.battlefields;
      var fieldOptions = fields.map(function (f) { return '<option value="' + f.id + '">' + f.name + '</option>'; }).join('');
      var mask = document.createElement('div'); mask.id = 'cityTeleportMask'; mask.className = 'city-dialog-mask';
      mask.innerHTML = '<section class="city-dialog" role="dialog" aria-modal="true" aria-labelledby="cityTeleportTitle">' +
        '<div class="city-dialog-heading"><b id="cityTeleportTitle">城市传送</b><button type="button" class="btn sm" data-close aria-label="关闭传送">✕</button></div>' +
        '<p>传送城市：<b>' + esc(city ? city.name : (state().player || {}).cityName) + '</b>' + (city ? ' · (' + city.x + ', ' + city.y + ')' : '') + '</p>' +
        '<p class="city-hint">目的地以坐标为左上角，连续 2×2（共4格）必须全部为开放地图内的空闲陆地，避开城市、据点和野地。传送不占新城市名额。</p>' +
        '<p>本城有海军、海军生产队列、军港或造船厂（含建设中）时，只能迁往沿海或海岛；普通传送也会自动筛选。没有这些资产的城市可迁往任意合法陆地。道具可在商城查看所需钻石与当前余额，余额不足时请先积攒。</p>' +
        '<p class="city-hint">建设中、交战中或归降移交中不能传送；请先让本城外派部队返城，并结束来袭、运输、调遣和驻防。城市资产及已有道具效果随城保留；港口使用条件按新位置判断。</p>' +
        '<section aria-label="迁城条件检查"><div data-readiness role="status">正在检查本城全部迁城条件…</div><button type="button" class="btn sm" data-recheck>重新检查迁城条件</button></section>' +
        '<form id="cityTeleportForm"><label>目的战场<select name="battlefield">' + fieldOptions + '</select></label><label>传送道具<select name="itemId"><option value="teleportNormal">普通传送 · 随机位置（持有 ' + normal + '）</option><option value="teleportAdvanced">高级传送 · 指定坐标（持有 ' + advanced + '）</option></select></label>' +
        '<div data-coordinates class="city-transfer-grid"><label>X 坐标<input name="x" type="number" min="0" max="' + max + '" step="1" aria-label="传送X坐标"></label>' +
        '<label>Y 坐标<input name="y" type="number" min="0" max="' + max + '" step="1" aria-label="传送Y坐标"></label></div>' +
        '<button type="button" class="btn city-wide" data-preview>检查目的地</button>' +
        '<p data-status role="status" aria-live="polite"></p><button type="submit" class="btn ok city-wide" data-confirm>消耗1个道具并传送</button></form>' +
        '<button type="button" class="btn city-wide" data-shop>前往商城购买传送道具</button></section>';
      document.body.appendChild(mask);
      mask.addEventListener('click', function (event) { if (event.target === mask) G.CityTeleport.close(); });
      mask.querySelector('[data-close]').onclick = function () { G.CityTeleport.close(); };
      mask.querySelector('[data-shop]').onclick = function () { if (!busy) { G.CityTeleport.close(); G.go('shop'); } };
      document.addEventListener('keydown', dismissKey);
      var form = mask.querySelector('form'), type = form.elements.itemId, battlefield = form.elements.battlefield;
      var initialX = x == null ? (city ? city.x : 0) : x, initialY = y == null ? (city ? city.y : 0) : y;
      battlefield.value = fields.find(function (f) { return f.column === (initialX >= quadrant ? 1 : 0) && f.row === (initialY >= quadrant ? 1 : 0); }).id;
      type.value = itemId === 'teleportAdvanced' || itemId === 'teleportNormal' ? itemId : (advanced > 0 ? 'teleportAdvanced' : 'teleportNormal');
      if (x != null) form.elements.x.value = String(x);
      if (y != null) form.elements.y.value = String(y);
      var coordinates = mask.querySelector('[data-coordinates]'), preview = mask.querySelector('[data-preview]');
      var status = mask.querySelector('[data-status]'), confirm = mask.querySelector('[data-confirm]');
      var revision = 0, validated = null, currentSession = session, cityReady = false, readinessRevision = 0;
      function alive() { return currentSession === session && identity() === owner && document.getElementById('cityTeleportMask') === mask; }
      function target() {
        var rawX = form.elements.x.value.trim(), rawY = form.elements.y.value.trim();
        var tx = Number(rawX), ty = Number(rawY);
        var field = fields.find(function (f) { return f.id === battlefield.value; });
        if (!field || !rawX || !rawY || !Number.isInteger(tx) || !Number.isInteger(ty)
            || tx < field.column * quadrant || ty < field.row * quadrant
            || tx > (field.column + 1) * quadrant - 2 || ty > (field.row + 1) * quadrant - 2) return null;
        return { x: tx, y: ty };
      }
      function change() {
        revision++; validated = null;
        var specified = type.value === 'teleportAdvanced';
        var field = fields.find(function (f) { return f.id === battlefield.value; });
        form.elements.x.min = field.column * quadrant; form.elements.x.max = (field.column + 1) * quadrant - 2;
        form.elements.y.min = field.row * quadrant; form.elements.y.max = (field.row + 1) * quadrant - 2;
        coordinates.hidden = !specified; coordinates.style.display = specified ? '' : 'none'; preview.hidden = !specified;
        form.elements.x.disabled = !specified; form.elements.y.disabled = !specified;
        var count = specified ? advanced : normal;
        confirm.disabled = !cityReady || specified || count < 1;
        status.textContent = count < 1 ? '此道具数量不足，请先在商城购买。' : specified ? '请检查目的地，四格须完整位于' + field.name + '。X：' + form.elements.x.min + '～' + form.elements.x.max + '；Y：' + form.elements.y.min + '～' + form.elements.y.max + '。' : '将在' + field.name + '内随机选择合法四格落点；失败不扣道具。';
        confirm.textContent = specified ? '消耗1个高级传送并传送到指定位置' : '消耗1个普通传送并随机传送';
      }
      form.addEventListener('input', change); type.onchange = change; battlefield.onchange = change;
      /** 服务端一次列出所有阻塞项；刷新检查不改动坐标和已填写的道具选择。 */
      function checkReadiness() {
        if (busy || !alive()) return;
        var request = ++readinessRevision;
        cityReady = false; confirm.disabled = true;
        var panel = mask.querySelector('[data-readiness]');
        panel.textContent = '正在检查本城全部迁城条件…';
        G.API.client.get('/game/cities/teleport/readiness', { silent: true }).then(function (data) {
          if (!alive() || request !== readinessRevision) return;
          cityReady = data.ready === true;
          var names = { construction: '城市已建成', surrender: '没有归降移交', departures: '没有本城外派部队', arrivals: '没有来袭或驻防等关联部队', battle: '没有交战', npc: '没有日寇来袭' };
          panel.innerHTML = '<ul>' + (data.checks || []).map(function (check) {
            return '<li>' + (check.passed ? '已通过：' + esc(names[check.key] || check.key) : '待处理：' + esc(check.message)
              + ' <button type="button" class="btn sm" data-resolve="' + esc(check.route) + '">前往查看</button>') + '</li>';
          }).join('') + '</ul><p>' + (data.requiresCoast ? '本城有海军资产：目的地还须为沿海或海岛。' : '本城无海军资产：可选择任意合法陆地。')
            + '道具库存：普通 ' + normal + '、高级 ' + advanced + '；目的地四格占地另行检查，正式提交仍会重新校验。</p>';
          panel.querySelectorAll('[data-resolve]').forEach(function (button) {
            button.onclick = function () { var route = button.getAttribute('data-resolve'); G.CityTeleport.close(); G.go(route); };
          });
          var point = target(), specified = type.value === 'teleportAdvanced';
          confirm.disabled = !cityReady || (specified ? advanced < 1 || !validated || !point || point.x !== validated.x || point.y !== validated.y : normal < 1);
        }).catch(function (e) {
          if (!alive() || request !== readinessRevision) return;
          panel.textContent = e.message || '迁城条件查询失败，请重新检查';
        });
      }
      mask.querySelector('[data-recheck]').onclick = checkReadiness;
      preview.onclick = function () {
        if (busy || !alive()) return;
        var point = target(); validated = null; confirm.disabled = true;
        if (!point) { status.textContent = '请输入所选战场范围内的整数坐标：X ' + form.elements.x.min + '～' + form.elements.x.max + '，Y ' + form.elements.y.min + '～' + form.elements.y.max + '。'; return; }
        var request = ++revision;
        status.textContent = '正在检查四格占地与城市状态…';
        G.API.client.get('/game/cities/teleport/site?battlefield=' + battlefield.value + '&x=' + point.x + '&y=' + point.y, { silent: true }).then(function (data) {
          if (!alive() || request !== revision) return;
          validated = data.valid ? point : null; confirm.disabled = !cityReady || !validated || advanced < 1;
          status.textContent = data.valid ? '目的地 (' + point.x + ', ' + point.y + ') 满足四格条件；提交时将再次校验。' + (advanced < 1 ? '高级传送数量不足。' : '') : '不能传送：' + data.reason;
        }).catch(function (e) { if (alive() && request === revision) status.textContent = e.message || '检查失败，请重试'; });
      };
      form.onsubmit = function (event) {
        event.preventDefault();
        if (busy || confirm.disabled || !alive()) return;
        var point = target(), specified = type.value === 'teleportAdvanced';
        if (specified && (!validated || !point || point.x !== validated.x || point.y !== validated.y)) { change(); return; }
        var body = { itemId: type.value, battlefield: battlefield.value };
        if (specified) { body.x = point.x; body.y = point.y; }
        busy = true; confirm.disabled = true; preview.disabled = true; type.disabled = true;
        battlefield.disabled = true;
        form.elements.x.disabled = true; form.elements.y.disabled = true; status.textContent = '正在传送，请稍候…';
        G.API.client.post('/game/cities/teleport', body, { retry: 0 }).then(function (data) {
          busy = false;
          if (!alive()) return;
          if (data.success === false) throw new Error(data.message || '传送失败');
          // 城市 ID 没变，但旧地图/状态请求仍含原坐标，成功后必须主动使其失效。
          G.API.client.invalidateCityRequests(); G.API.applyState(data.state);
          if (G.World) G.World._dispatchTarget = null;
          if (G.WorldView) G.WorldView.invalidate();
          if (G.WorldMap) G.WorldMap.invalidate();
          G.CityTeleport.close();
          if (G.WorldMap) G.WorldMap.focusCoordinate(data.x, data.y);
          G.go('world');
          if (G.WorldMap) G.WorldMap.setMode('map');
          G.toast(data.message);
        }).catch(function (e) {
          busy = false;
          if (!alive()) return;
          type.disabled = false; battlefield.disabled = false; preview.disabled = false; change(); status.textContent = e.message || '传送失败，请重新检查目的地';
        });
      };
      change(); checkReadiness(); mask.querySelector('[data-close]').focus();
      if (type.value === 'teleportAdvanced' && x != null && y != null) preview.onclick();
    }
  };
})(window.Game = window.Game || {});
