/* global window */
(function (G) {
  'use strict';
  var pending = new Set(), alerted = new Set(), account = null;
  function state() { return (G.Core && G.Core.state) || G.state || {}; }
  function data() {
    var player = (state().player || {}).id;
    if (account !== player) { account = player; alerted.clear(); pending.clear(); }
    return (state().world || {}).regionalRadar || { cities: [], contacts: [], history: [], unread: 0, revision: 0 };
  }
  function escape(value) { return G.escapeHtml(String(value == null ? '' : value)); }
  function city() {
    var current = (state().player || {}).activeCityId;
    return (data().cities || []).find(function (sensor) { return String(sensor.cityId) === String(current); });
  }
  function contacts() {
    var direct = (state().world || {}).incoming || [];
    return (data().contacts || []).filter(function (contact) {
      return !direct.some(function (incoming) { return incoming.marchId != null && String(incoming.marchId) === String(contact.marchId); });
    });
  }
  function changed() {
    if (G.WorldMap && G.WorldMap.refreshRadar) G.WorldMap.refreshRadar();
    if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
    if (G.Core && G.Core.refreshTop) G.Core.refreshTop();
    if (G.Core && G.Core.route === 'alerts') G.Core.render();
  }
  function apply(next, notify) {
    if (!next || !state().world) return false;
    var player = (state().player || {}).id;
    if (account !== player) { account = player; alerted.clear(); pending.clear(); }
    if (Number(next.revision) < Number(data().revision)) return false;
    state().world.regionalRadar = next;
    var live = contacts();
    if (notify) (next.alerts || []).forEach(function (alert) {
      var match = live.find(function (item) { return String(item.contactId) === String(alert.contactId); });
      if (!match || alerted.has(String(alert.contactId))) return;
      alerted.add(String(alert.contactId));
      var peers = (match.threatenedPlayers || []).map(function (peer) { return peer.name; }).join('、') || '团友';
      var grouped = live.filter(function (item) { return item.reminderGroup === match.reminderGroup; });
      G.toast('团友预警：' + peers + '的「' + (match.targetName || '目标') + '」' +
        (match.threatKind === 'scout' ? '遭到侦察' : '遭到进攻') + (grouped.length > 1 ? '，共' + grouped.length + '支来袭' : '') + '，请查看军情');
      if (G.WorldMap && G.WorldMap.refreshRadar) G.WorldMap.refreshRadar(true, match.threatKind);
    });
    changed();
    return true;
  }
  function request(promise, notify) {
    var token = G.API.getToken(), player = (state().player || {}).id;
    return promise.then(function (result) {
      if (G.API.getToken() !== token || (state().player || {}).id !== player) return null;
      apply(result, notify); return result;
    });
  }
  function toggle() {
    var sensor = city();
    if (!sensor || !sensor.level || pending.has(String(sensor.cityId))) return Promise.resolve();
    var key = String(sensor.cityId), wasEnabled = sensor.enabled;
    pending.add(key); changed();
    return request(G.API.setRadarEnabled(sensor.cityId, !wasEnabled), true).then(function (result) {
      if (!result) return;
      if (wasEnabled) G.toast('关闭后，本城停止收集团友预警军情及生成对应警报；你自己的城市受袭提醒不受影响。');
    }).catch(function (error) { G.toast(error.message || '联防雷达设置保存失败，请重试'); })
      .finally(function () { pending.delete(key); changed(); });
  }
  function buttonHtml() {
    var sensor = city(), busy = sensor && pending.has(String(sensor.cityId)), built = sensor && sensor.level > 0;
    var label = busy ? '联防雷达：保存中…' : !built ? '本城未建设联防雷达' : '联防雷达：' + (sensor.enabled ? '已开启' : '已关闭');
    return '<button type="button" class="page-back-button" data-map="radar" aria-pressed="' + !!(built && sensor.enabled) +
      '" aria-busy="' + !!busy + '" aria-label="' + escape(label) + '" title="' + escape(label) + '"' + (!built || busy ? ' disabled' : '') + '><span>' +
      (busy ? '雷达：保存中…' : !built ? '雷达：未建' : '雷达：' + (sensor.enabled ? '开' : '关')) +
      '</span></button>';
  }
  function timeText(value) { return value == null ? '未知' : new Date(G.GameTime ? G.GameTime.toWallTime(Number(value)) : Number(value)).toLocaleString('zh-CN', { hour12: false }); }
  /** 只解释当前账号已授权的监测状态；没有警报不等于覆盖区内没有敌军或目标没有守军。 */
  function helpHtml() {
    var sensor = city(), sensors = (data().cities || []).filter(function (s) { return s.level > 0 && s.enabled; });
    var reason = !sensors.length ? '本账号暂无已建成且开启的联防雷达，请先建设或开启。'
      : data().joinedGuild === false ? '尚未加入军团，联防雷达暂不生成团友预警。'
      : contacts().length ? '目前已发现符合条件的团友威胁，具体情报以授权等级为准。'
      : '暂无符合条件的可见团友威胁；需敌军路线经过已开启的覆盖区，且目标威胁团友城市、驻军或野地。自己的直接来袭单独显示。';
    return '<details><summary>雷达用途与当前覆盖</summary><table><thead><tr><th>升级项目</th><th>实际用途</th></tr></thead><tbody>'
      + '<tr><td>城防雷达</td><td>配合地图扫描与科研前置；侦查战守方首回合每级+2先手点。不增加联防覆盖或普通部队攻防。基本来袭提醒无需建造。</td></tr>'
      + '<tr><td>联防雷达</td><td>建筑等级决定覆盖半径；需主动开启。监测威胁团友的完整行军路线是否经过覆盖区，无需等待敌军进入。</td></tr>'
      + '<tr><td>雷达预警科技</td><td>决定来袭与联防情报详细度，不扩大覆盖半径，不公开未经授权的守军。</td></tr>'
      + '<tr><td>联络中心</td><td>联防雷达和相关科技的建筑前置，不直接增加情报明细或外交权限。</td></tr></tbody></table>'
      + '<p>当前城市：' + (sensor && sensor.level > 0 ? '联防雷达Lv.' + sensor.level + '，覆盖半径 ' + Number(sensor.radius) + ' 格，' + (sensor.enabled ? '已开启' : '已关闭') : '未建成联防雷达')
      + '；账号已开启 ' + sensors.length + ' 座监测城。</p><p>' + escape(reason)
      + '</p><p>具体原因以最近一次同步状态为准。未发现预警不代表安全；历史记录为监测失效前快照。</p>'
      + '<button type="button" class="btn sm" onclick="Game.go(\'buildArmy\')">前往建设</button> <button type="button" class="btn sm" onclick="Game.go(\'tech\')">前往科研</button></details>';
  }
  function cards(history) {
    var list = history ? data().history || [] : contacts();
    var renderedGroups = new Set();
    if (!list.length) return '<div class="term-empty">' + (history ? '暂无历史团友预警。' : '暂无团友预警。') + '</div>';
    return list.map(function (contact) {
      var peers = (contact.threatenedPlayers || []).map(function (peer) { return peer.name; }).join('、') || '团友';
      var status = { monitoring_disabled: '监测已关闭', monitoring_lost: '监测失效', withdrawn: '已撤军', ended: '已结束' }[contact.status];
      if (!history) status = contact.inBattle ? '交战中' : contact.waitingForBattle ? '等待战斗' : '行军中';
      var source = (history ? contact.snapshotSources || [] : contact.sources || []).map(function (sensor) { return sensor.name; }).join('、');
      var group = list.filter(function (item) { return item.reminderGroup === contact.reminderGroup; });
      var groupHtml = '';
      if (!history && group.length > 1 && !renderedGroups.has(contact.reminderGroup)) {
        renderedGroups.add(contact.reminderGroup);
        var earliest = Math.min.apply(null, group.map(function (item) { return Number(item.arriveAt); }));
        groupHtml = '<div class="term-row"><b>同一目标' + (contact.threatKind === 'scout' ? '侦察' : '进攻') + ' · 共' + group.length + '支来袭</b> · 最早抵达：' +
          (G.World && G.World.alertCountdown ? G.World.alertCountdown(earliest) : escape(timeText(earliest))) + '</div>';
      }
      var html = groupHtml + '<div class="term-alert-card menu-item type-' + (contact.threatKind === 'scout' ? 'scout' : 'enemy') + '"><div class="term-row"><span class="term-badge ' + (contact.threatKind === 'scout' ? 'warn' : 'danger') + '">' +
        (contact.threatKind === 'scout' ? '[团友侦察]' : '[团友敌袭]') + '</span> <b>' + escape(peers) + '</b> · ' + escape(contact.targetName || '目标') + ' · ' + escape(status) + '</div>';
      html += '<div class="term-row term-row-sub">' + (history ? '关闭／失效时预计抵达：' + escape(timeText(contact.arriveAt)) + ' · 快照：' + escape(timeText(contact.snapshotAt))
        : '预计抵达：' + (G.World && G.World.alertCountdown ? G.World.alertCountdown(contact.arriveAt) : escape(timeText(contact.arriveAt))) + ' · 监测城市：' + escape(source)) + '</div>';
      html += '<div class="term-row term-row-sub">首次发现：' + escape(timeText(contact.firstSeenAt)) + (history ? ' · 快照监测城市：' + escape(source) : '') + (contact.read ? ' · 已读' : ' · 未读') + '</div>';
      html += '<button class="btn sm" type="button" onclick="Game.Radar.locate(' + Number(contact.targetX) + ',' + Number(contact.targetY) + ')">定位目标</button> ';
      html += '<button class="btn sm" type="button" onclick="Game.Radar.detail(' + Number(contact.contactId) + ',' + !!history + ')">查看详情</button> ';
      if (!history) {
        html += '<button class="btn sm" type="button" onclick="Game.Radar.share(' + Number(contact.contactId) + ')">军情分享</button> ';
        if (contact.canSupport) html += '<button class="btn sm" type="button" onclick="Game.Radar.support(' + Number(contact.contactId) + ')">增援</button> ';
      }
      return html + '</div>';
    }).join('');
  }
  function detail(id, history) {
    var contact = (history ? data().history || [] : contacts()).find(function (item) { return Number(item.contactId) === Number(id); });
    if (!contact) return;
    return request(G.API.readRadarContact(id), false).then(function (result) {
      if (!result) return;
      var latest = contacts().concat(data().history || []).find(function (item) { return Number(item.contactId) === Number(id); }) || (history ? contact : null);
      if (!latest) { G.toast('监测权限已失效，请刷新军情'); return; }
      var previous = document.getElementById('regionalRadarDetail'); if (previous) previous.remove();
      var mask = document.createElement('div'), focus = document.activeElement;
      mask.id = 'regionalRadarDetail'; mask.className = 'modal-mask'; mask.setAttribute('role', 'dialog'); mask.setAttribute('aria-modal', 'true'); mask.setAttribute('aria-labelledby', 'regionalRadarDetailTitle');
      mask.innerHTML = '<div class="modal-card"><div class="modal-title" id="regionalRadarDetailTitle">团友预警详情' + (latest.status !== 'active' ? '（历史快照）' : '') + '</div><div class="modal-body"><div class="term-expanded-intel">' + G.World.renderIncomingIntelligence(latest) + '</div></div><div class="modal-foot"><button type="button" class="btn" data-radar-close>关闭</button></div></div>';
      function close() { mask.remove(); if (focus && focus.isConnected && focus.focus) focus.focus(); }
      var button = mask.querySelector('[data-radar-close]'); button.onclick = close;
      mask.onclick = function (event) { if (event.target === mask || event.target.closest('[data-intel-coordinate]')) close(); };
      mask.onkeydown = function (event) { if (event.key === 'Escape') close(); };
      document.body.appendChild(mask); button.focus();
    }).catch(function (error) { G.toast(error.message); });
  }
  function circle(sensor, radius) {
    var points = [];
    for (var index = 0; index < 192; index++) {
      var angle = index * Math.PI * 2 / 192;
      points.push({ x: Number(sensor.x) + Math.cos(angle) * radius, y: Number(sensor.y) + Math.sin(angle) * radius });
    }
    return points;
  }
  function clippedCircle(sensor, radius, size) {
    var points = circle(sensor, radius);
    ['left', 'right', 'top', 'bottom'].forEach(function (edge) {
      var horizontal = edge === 'left' || edge === 'right', axis = horizontal ? 'x' : 'y';
      var limit = edge === 'right' || edge === 'bottom' ? size : 0;
      function inside(point) { return limit === 0 ? point[axis] >= 0 : point[axis] <= size; }
      var output = [];
      points.forEach(function (point, index) {
        var previous = points[(index + points.length - 1) % points.length];
        if (inside(point) !== inside(previous)) {
          var fraction = (limit - previous[axis]) / (point[axis] - previous[axis]);
          output.push({ x: previous.x + (point.x - previous.x) * fraction, y: previous.y + (point.y - previous.y) * fraction });
        }
        if (inside(point)) output.push(point);
      });
      points = output;
    });
    return points;
  }
  G.Radar = {
    data: data, city: city, contacts: contacts, apply: apply, toggle: toggle, buttonHtml: buttonHtml, cards: cards, helpHtml: helpHtml,
    unread: function () { return contacts().filter(function (contact) { return !contact.read; }).length; },
    refresh: function () { return request(G.API.getRadar(), false); },
    loadHistory: function () {
      var previous = data(), before = previous.nextBeforeId;
      var token = G.API.getToken(), player = (state().player || {}).id;
      if (!before) return Promise.resolve();
      return G.API.getRadar(before).then(function (next) {
        if (G.API.getToken() !== token || (state().player || {}).id !== player) return;
        if (Number(next.revision) < Number(data().revision)) return;
        next.history = (data().history || []).concat(next.history || []).filter(function (item, index, list) {
          return list.findIndex(function (other) { return String(other.contactId) === String(item.contactId); }) === index;
        });
        apply(next, false);
      }).catch(function (error) { G.toast(error.message); });
    },
    detail: detail,
    share: function (id) { return G.API.shareIncomingReport('radar-' + id).then(function () { G.toast('区域军情已分享到军团频道'); }).catch(function (error) { G.toast(error.message); }); },
    locate: function (x, y) { if (Number.isFinite(x) && Number.isFinite(y)) G.World.locateTarget(x, y); },
    support: function (id) {
      var contact = contacts().find(function (item) { return Number(item.contactId) === Number(id); });
      if (contact && contact.canSupport && G.Cities && G.Cities.openSupport) G.Cities.openSupport(contact.targetOwnerId, 'reinforce', contact.targetId);
    },
    circle: circle, clippedCircle: clippedCircle
  };
})(window.Game = window.Game || {});
