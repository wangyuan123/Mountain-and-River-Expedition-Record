/* global window, document */
(function (G) {
  'use strict';
  var drawer = null, root = null, timer = null, data = null, generation = 0;
  var pending = false, dirty = false, failed = false, shape = '', signature = '', owner = '';
  var lastPoll = 0, lastGameTime = 0, lastAdvance = 0;
  function state() { return G.Core.state || {}; }
  function player() { return state().player || {}; }
  function now() { return G.gameNow(); }
  function monotonic() { return window.performance ? window.performance.now() : Date.now(); }
  function esc(value) { return G.escapeHtml(String(value == null ? '' : value)); }
  function identity() {
    return player().id + ':' + (G.API.getToken ? G.API.getToken() : '') + ':' + (G.API.client.serverId || '');
  }
  function currentCity() {
    var cities = (state().cityOverview || {}).cities || (data && data.cities) || [];
    var current = cities.find(function (c) { return c.current; }) || cities.find(function (c) { return c.main; });
    return String(player().activeCityId || (current && current.id) || '');
  }
  function alive() { return !!root && document.getElementById('playerDrawerMask') === drawer; }

  /** 只比较会改变增益或城市归属的状态，普通经济 tick 不额外发请求。 */
  function stateSignature() {
    return JSON.stringify([currentCity(), state().cityState, player().mobilizationExpiries,
      ((state().cityOverview || {}).cities || []).map(function (c) { return [c.id, c.name, c.x, c.y, c.shieldUntil]; })]);
  }
  function countdown(until, time) {
    var seconds = Math.max(0, Math.ceil((until - time) / 1000));
    var days = Math.floor(seconds / 86400);
    function pad(n) { return String(n).padStart(2, '0'); }
    return (days ? days + '天 ' : '') + pad(Math.floor(seconds % 86400 / 3600)) + ':' +
      pad(Math.floor(seconds % 3600 / 60)) + ':' + pad(seconds % 60);
  }

  /** 隐藏已到期条目并排序；生效或被覆盖状态始终使用后端快照。 */
  function activeEffects(snapshot, time) {
    var effects = snapshot.effects.filter(function (e) { return e.until > time; });
    return effects.slice().sort(function (a, b) { return a.until - b.until || a.id.localeCompare(b.id); });
  }

  function cityLabel(city) { return city ? city.name + '（' + city.x + ', ' + city.y + '）' : '当前城市'; }
  function row(effect) {
    var description = effect.kind === 'shield' ? '免受侦查、攻击和宣战'
      : effect.kind === 'march' ? '新发起行军速度 +' + effect.percent + '%'
        : '新出征带兵上限 +' + effect.percent + '% · 全账号所有城市';
    return '<section aria-label="' + esc(effect.name) + '"><p><img src="' + esc(effect.icon) +
      '" width="24" height="24" alt=""> <strong>' + esc(effect.name) + '</strong> · <span data-buff-status="' +
      esc(effect.id) + '"></span><br>' + esc(description) + '<br>剩余 <span data-buff-remaining="' + esc(effect.id) +
      '"></span><br><span data-buff-end="' + esc(effect.id) + '"></span>' +
      (effect.status === 'covered' ? '<br>被更高档覆盖，仍在计时；高档结束后若本档未到期，将自动生效。' : '') + '</p></section>';
  }
  function cityRows(city, effects) {
    return '<section aria-label="' + esc(cityLabel(city)) + '"><p><strong>' + esc(cityLabel(city)) + '</strong></p>' +
      effects.map(row).join('') + '</section>';
  }
  function rules() {
    return '<details data-buff-fold="rules"><summary data-buff-key="rules">规则说明</summary>' +
      '<p>护盾仅保护对应城市，不保护野地；期间不能向其他玩家发起军事操作。不同护盾时长可累加。</p>' +
      '<p>行军令在仓库启用后影响本城新发起行军；同档刷新为至少剩余1小时，不累加1小时。高档替换低档，高档生效时不能使用低档。速度+50%不等于时间缩短50%。</p>' +
      '<p>战争动员对全账号新出征生效，不增加守城人数。同档每张累加24小时，异档独立计时，只取最高加成；被覆盖档位仍在计时。</p>' +
      '<p>到期不追溯改变已出征队伍或已开始战斗。新手保护独立展示；对单支队伍的直接加速、干扰、撤退拖延、宣战加速与建造加速等即时效果不计入本模块。</p></details>';
  }

  /** 仅增益集合变化时重建内容，保存折叠和焦点；逐秒文本更新不替换节点。 */
  function replace(html) {
    var opened = {}, focused = document.activeElement;
    var focusKey = focused && focused.getAttribute && focused.getAttribute('data-buff-key');
    root.querySelectorAll('[data-buff-fold]').forEach(function (node) { opened[node.getAttribute('data-buff-fold')] = node.open; });
    var body = drawer.querySelector('.drawer-body'), scroll = body && body.scrollTop;
    root.innerHTML = html;
    root.querySelectorAll('[data-buff-fold]').forEach(function (node) {
      node.open = !!opened[node.getAttribute('data-buff-fold')];
    });
    if (focusKey) root.querySelectorAll('[data-buff-key]').forEach(function (node) {
      if (node.getAttribute('data-buff-key') === focusKey) node.focus({ preventScroll: true });
    });
    if (body) body.scrollTop = scroll;
  }
  function render() {
    if (!alive()) return;
    var time = now();
    if (time !== lastGameTime) { lastGameTime = time; lastAdvance = monotonic(); }
    var stalled = monotonic() - lastAdvance > 5000;
    var effects = data ? activeEffects(data, time) : [];
    var current = data && data.cities.find(function (c) { return String(c.id) === currentCity(); });
    var nextShape = JSON.stringify([data && data.cities, currentCity(), effects, failed]);
    if (nextShape !== shape) {
      shape = nextShape;
      var active = effects.filter(function (e) { return e.status === 'active'; });
      var covered = effects.filter(function (e) { return e.status === 'covered'; });
      var html = '<div class="drawer-section-title">道具增益' + (data ? ' · 生效中 ' + active.length + ' 项' +
        (covered.length ? ' · 被覆盖 ' + covered.length + ' 项' : '') : '') + '</div>';
      if (!data) html += '<p role="status">' + (failed ? '增益信息暂不可用' : '正在读取增益信息…') + '</p>';
      if (failed) html += '<p role="status">' + (data ? '状态待同步 · ' : '') +
        '<button type="button" class="drawer-link-btn" data-buff-key="retry" data-buff-action="retry">点击重试</button></p>';
      if (data) {
        html += '<p data-buff-clock="" role="status"></p>';
        var account = active.filter(function (e) { return e.scope === 'account'; });
        html += '<section aria-label="全账号"><p><strong>全账号</strong></p>' +
          (account.length ? account.map(row).join('') : '<p>暂无生效中的账号道具增益</p>');
        if (covered.length) html += '<details data-buff-fold="tiers"><summary data-buff-key="tiers">其他动员档位（' +
          covered.length + '）</summary>' + covered.map(row).join('') + '</details>';
        html += '</section><section aria-label="当前城市"><p><strong>当前城市 · ' + esc(cityLabel(current)) + '</strong></p>';
        var local = effects.filter(function (e) { return e.scope === 'city' && String(e.cityId) === currentCity(); });
        html += local.length ? local.map(row).join('') : '<p>本城暂无生效中的道具增益</p>';
        html += '</section>';
        var others = data.cities.filter(function (c) {
          return String(c.id) !== currentCity() && effects.some(function (e) { return e.cityId === c.id; });
        });
        if (others.length) {
          var nearest = effects.find(function (e) { return e.scope === 'city' && String(e.cityId) !== currentCity(); });
          var nearestCity = data.cities.find(function (c) { return c.id === nearest.cityId; });
          html += '<details data-buff-fold="cities"><summary data-buff-key="cities">其他城市（' + others.length +
            '座）· 最近到期：' + esc(nearestCity.name + ' ' + nearest.name) + '，剩余 <span data-buff-remaining="' +
            esc(nearest.id) + '"></span></summary>' + others.map(function (c) {
              return cityRows(c, effects.filter(function (e) { return e.cityId === c.id; }));
            }).join('') + '</details>';
        }
        if (!effects.length) html += '<p>暂无生效中的道具增益，可在仓库使用护盾、行军令或战争动员令。</p>';
        html += rules();
      }
      html += '<p><button type="button" class="drawer-link-btn" data-buff-key="depot" data-buff-action="depot">前往仓库</button></p>';
      replace(html);
    }
    var byId = {};
    effects.forEach(function (e) { byId[e.id] = e; });
    root.querySelectorAll('[data-buff-remaining]').forEach(function (node) {
      var e = byId[node.getAttribute('data-buff-remaining')];
      if (e) node.textContent = countdown(e.until, time);
    });
    root.querySelectorAll('[data-buff-status]').forEach(function (node) {
      var e = byId[node.getAttribute('data-buff-status')];
      if (e) node.textContent = e.status === 'covered' ? '被覆盖' : e.until - time <= 600000 ? '即将结束' : '生效中';
    });
    root.querySelectorAll('[data-buff-end]').forEach(function (node) {
      var e = byId[node.getAttribute('data-buff-end')];
      if (e) node.textContent = stalled ? '暂停期间暂无法确定结束时间' : '预计结束：' +
        new Date(G.GameTime.toWallTime(e.until)).toLocaleString('zh-CN', { hour12: false, timeZone: 'Asia/Shanghai' }) + '（北京时间）';
    });
    var clock = root.querySelector('[data-buff-clock]');
    if (clock) clock.textContent = stalled ? '游戏计时已暂停（未收到新时钟），等待同步' : '';
  }

  /** 同时只请求一次，账号/城市切换和关闭后的响应一律丢弃。失败保留已知截止点。 */
  function refresh() {
    if (!alive()) return;
    if (owner !== identity()) { unmount(); return; }
    if (pending) { dirty = true; return; }
    pending = true; lastPoll = monotonic();
    var revision = generation, city = currentCity();
    G.API.client.get('/game/item-buffs', { silent: true }).then(function (snapshot) {
      if (revision !== generation || !alive() || owner !== identity() || city !== currentCity()) return;
      if (!snapshot || snapshot.playerId !== player().id || !Array.isArray(snapshot.effects) || !Array.isArray(snapshot.cities))
        throw new Error('增益响应无效');
      G.GameTime.sync(snapshot.serverTime);
      data = snapshot; failed = false; render();
    }).catch(function () {
      if (revision === generation && alive() && owner === identity() && city === currentCity()) { failed = true; render(); }
    }).finally(function () {
      if (revision !== generation) return;
      pending = false;
      if (dirty) { dirty = false; refresh(); }
    });
  }
  function tick() {
    if (!alive() || identity() !== owner) { unmount(); return; }
    render();
    if (!pending && monotonic() - lastPoll >= 30000) refresh();
  }
  function foreground() { if (!document.hidden) refresh(); }
  function connected() { refresh(); }
  function disconnected() { failed = true; render(); }
  /** 每次关闭、重开或切换账号都释放监听器，并使尚未完成的请求失效。 */
  function unmount() {
    generation++;
    if (timer != null) clearInterval(timer);
    timer = null;
    window.removeEventListener('focus', foreground);
    document.removeEventListener('visibilitychange', foreground);
    if (G.WS && G.WS.off) { G.WS.off('connected', connected); G.WS.off('disconnected', disconnected); }
    if (root) { root.onclick = null; root.innerHTML = ''; }
    root = drawer = data = null; pending = dirty = failed = false; shape = '';
  }

  G.ItemBuffs = {
    placeholder: function () { return '<div class="drawer-section" id="drawerItemBuffs" aria-label="道具增益"></div>'; },
    /** 只在档案打开期间维护一份账号增益快照和逐秒文本计时器。 */
    mount: function (element) {
      unmount(); drawer = element; root = element.querySelector('#drawerItemBuffs');
      if (!root) return;
      owner = identity(); signature = stateSignature(); lastGameTime = now(); lastAdvance = monotonic();
      root.onclick = function (event) {
        var action = event.target.closest('[data-buff-action]');
        if (!action) return;
        if (action.getAttribute('data-buff-action') === 'retry') refresh();
        else { G.Main.closePlayerDrawer(); G.go('depot'); }
      };
      window.addEventListener('focus', foreground);
      document.addEventListener('visibilitychange', foreground);
      if (G.WS && G.WS.on) { G.WS.on('connected', connected); G.WS.on('disconnected', disconnected); }
      render(); refresh(); timer = setInterval(tick, 1000);
    },
    unmount: unmount,
    /** API 应用新游戏状态后调用；城市/增益变化立即校准，普通经济变动不请求。 */
    stateChanged: function () {
      if (!alive()) return;
      if (identity() !== owner) { unmount(); return; }
      var next = stateSignature();
      if (next !== signature) { signature = next; render(); refresh(); }
    }
  };
})(window.Game);
