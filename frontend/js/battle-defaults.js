/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var Core = G.Core;
  var defaults = null;
  var playerId = null;
  var cityId = null;
  var loading = false;
  var saving = false;
  var message = '';
  var requestId = 0;
  var activePicker = null;
  var actionChoices = [['ADVANCE', '前进'], ['HOLD', '待命'], ['RETREAT', '后退']];
  var lockedMessage = '战斗或进攻行军期间无法修改默认战术';

  /** 状态推送提供账号级锁定资格，不根据客户端行军列表重建业务限制。 */
  function isLocked() {
    if (!defaults) return false;
    var gameplay = Core.state && Core.state.gameplay;
    return gameplay && typeof gameplay.battleDefaultsLocked === 'boolean'
      ? gameplay.battleDefaultsLocked : !!defaults.locked;
  }

  /** 菜单始终锚定在页面内的选项框，避免横屏或缩放时原生弹层使用不同坐标系。 */
  function toggleActionMenu(button) {
    if (isLocked()) return;
    var picker = button.closest('.battle-defaults-picker');
    var wasOpen = activePicker === picker;
    closeActionMenu();
    if (wasOpen) return;
    activePicker = picker;
    var menu = picker.querySelector('[role="listbox"]');
    menu.style.maxHeight = '';
    menu.hidden = false;
    button.setAttribute('aria-expanded', 'true');
    picker.classList.add('is-open');
    var bounds = button.getBoundingClientRect();
    var view = document.getElementById('view');
    var viewport = view ? view.getBoundingClientRect() : { top: 0, bottom: window.innerHeight };
    var below = Math.min(viewport.bottom, window.innerHeight) - bounds.bottom;
    var above = bounds.top - Math.max(viewport.top, 0);
    var openAbove = below < menu.getBoundingClientRect().height + 4 && above > below;
    picker.classList.toggle('opens-above', openAbove);
    // 可用空间来自视口坐标，换回元素尺寸后再限制高度，兼容页面缩放。
    var scale = bounds.height / button.offsetHeight || 1;
    menu.style.maxHeight = Math.max(0, (openAbove ? above : below) / scale - 8) + 'px';
    var selected = menu.querySelector('[aria-selected="true"]') || menu.firstElementChild;
    if (selected) selected.focus({ preventScroll: true });
    document.addEventListener('pointerdown', dismissActionMenu);
    window.addEventListener('resize', dismissActionMenu);
  }

  function closeActionMenu(restoreFocus) {
    if (!activePicker) return;
    var picker = activePicker;
    activePicker = null;
    picker.querySelector('[role="listbox"]').hidden = true;
    picker.classList.remove('is-open', 'opens-above');
    var button = picker.querySelector('.battle-defaults-trigger');
    button.setAttribute('aria-expanded', 'false');
    document.removeEventListener('pointerdown', dismissActionMenu);
    window.removeEventListener('resize', dismissActionMenu);
    if (restoreFocus) button.focus({ preventScroll: true });
  }

  function dismissActionMenu(event) {
    if (activePicker && (event.type === 'resize' || !activePicker.contains(event.target))) closeActionMenu();
  }

  function leaveActionMenu(event) {
    if (activePicker && !activePicker.contains(event.relatedTarget)) closeActionMenu();
  }

  /** 保留方向键、首尾跳转和 Escape 操作，选择后只更新草稿并将焦点还给选项框。 */
  function handleActionKey(event) {
    var picker = event.currentTarget;
    if (event.key === 'Escape' && activePicker === picker) {
      event.preventDefault();
      closeActionMenu(true);
      return;
    }
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].indexOf(event.key) < 0) return;
    event.preventDefault();
    var wasOpen = activePicker === picker;
    if (!wasOpen) toggleActionMenu(picker.querySelector('.battle-defaults-trigger'));
    var options = Array.prototype.slice.call(picker.querySelectorAll('[role="option"]'));
    var index = options.indexOf(document.activeElement);
    if (event.key === 'Home') index = 0;
    else if (event.key === 'End') index = options.length - 1;
    else if (wasOpen) index = (index + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length;
    if (options[index]) options[index].focus({ preventScroll: true });
  }

  function chooseAction(option, side, unitId, action) {
    if (isLocked()) return;
    setAction(side, unitId, action);
    var picker = option.closest('.battle-defaults-picker');
    var button = picker.querySelector('.battle-defaults-trigger');
    button.querySelector('.battle-defaults-value').textContent = option.textContent;
    button.setAttribute('aria-label', button.getAttribute('data-label') + '：' + option.textContent);
    picker.querySelectorAll('[role="option"]').forEach(function (item) {
      item.setAttribute('aria-selected', item === option ? 'true' : 'false');
    });
    closeActionMenu(true);
  }

  function updateMessage(text) {
    message = text;
    var element = document.getElementById('battle-defaults-message');
    if (element) element.textContent = text;
  }

  /** 加载账号级战术；切换账号时丢弃旧草稿，避免把别人的配置展示给当前玩家。 */
  function load() {
    if (loading) return;
    loading = true;
    var currentRequest = ++requestId;
    function accept(saved) {
      if (currentRequest !== requestId) return;
      if (playerId !== (Core.state && Core.state.player && Core.state.player.id)) return;
      defaults = saved;
      message = '';
      loading = false;
      if (Core.route === 'battleDefaults') Core.render();
    }
    G.API.getBattleDefaults().then(function (saved) {
      if (currentRequest !== requestId) return;
      if (playerId !== (Core.state && Core.state.player && Core.state.player.id)) return;
      accept(saved);
    }).catch(function (error) {
      if (currentRequest !== requestId) return;
      updateMessage(error.message || '默认战术加载失败，请重试');
    }).finally(function () { if (currentRequest === requestId) loading = false; });
  }

  /** 修改当前草稿；只有点击保存后，离线战斗才使用新设置。 */
  function setAction(side, unitId, action) {
    if (isLocked()) return;
    if (!defaults || !defaults[side] || !Object.prototype.hasOwnProperty.call(defaults[side], unitId)) return;
    if (['ADVANCE', 'HOLD', 'RETREAT'].indexOf(action) < 0) return;
    defaults[side][unitId] = action;
    updateMessage('有未保存的修改');
  }

  /** 出征容量变化只更新说明，不重置与动员无关的攻守行动草稿。 */
  function refreshMobilization() {
    var cap = document.getElementById('battle-defaults-dispatch-cap');
    if (cap && defaults) cap.textContent = String(dispatchCap());
    return false;
  }

  function dispatchCap() {
    return typeof Core.armyCap === 'function' ? Core.armyCap() : Number(defaults && defaults.sortieCap) || 0;
  }

  function currentPreferences() {
    return { outgoing: defaults.outgoing, defending: defaults.defending };
  }

  /** 保存攻守两套完整配置，后端按当前登录账号校验并持久化。 */
  function save() {
    if (!defaults || saving) return;
    if (isLocked()) { updateMessage(lockedMessage); return; }
    saving = true;
    var savingRequest = requestId;
    var button = document.getElementById('battle-defaults-save');
    if (button) button.disabled = true;
    var snapshot = JSON.parse(JSON.stringify(currentPreferences()));
    return G.API.saveBattleDefaults(snapshot).then(function () {
      if (savingRequest !== requestId || playerId !== (Core.state && Core.state.player && Core.state.player.id)) return;
      updateMessage(JSON.stringify(currentPreferences()) === JSON.stringify(snapshot) ? '已保存，后续迎战和离线战斗将按此配置执行' : '已保存；仍有未保存的修改');
    }).catch(function (error) {
      if (savingRequest !== requestId) return;
      updateMessage(error.message || '保存失败，请重试');
      if (error.message && error.message.indexOf('行军期间无法修改') >= 0) {
        defaults.locked = true;
        if (Core.route === 'battleDefaults') Core.render();
      }
    }).finally(function () {
      if (savingRequest !== requestId) return;
      saving = false;
      // 页面刷新可能重新渲染按钮，保存结束后必须恢复当前 DOM 中的按钮。
      var currentButton = document.getElementById('battle-defaults-save');
      if (currentButton) currentButton.disabled = isLocked();
    });
  }

  function render(v) {
    closeActionMenu();
    var currentPlayerId = Core.state && Core.state.player && Core.state.player.id;
    var currentCityId = Core.state && Core.state.player && Core.state.player.activeCityId;
    if (currentPlayerId !== playerId || currentCityId !== cityId) {
      requestId++;
      loading = false;
      saving = false;
      playerId = currentPlayerId;
      cityId = currentCityId;
      defaults = null;
      message = '';
    }
    var html = '<div class="title">- 默认战术 -</div>' +
      '<div class="desc">按兵种设置出征与守城的默认行动，保存后用于后续战斗及离线结算。</div>';
    if (!defaults) {
      v.innerHTML = html + '<div class="panel"><div class="desc">正在加载默认战术…</div>' +
        '<div id="battle-defaults-message" role="status">' + G.escapeHtml(message) + '</div>' +
        '<button type="button" class="btn sm" onclick="Game.BattleDefaults.retry()">重试加载</button></div>';
      load();
      return;
    }
    var locked = isLocked();
    html += '<div class="panel battle-defaults-panel"><h3>出征与守城默认行动</h3>' +
      (locked ? '<div class="desc" role="status">' + lockedMessage + '。<button type="button" class="btn sm" onclick="Game.BattleDefaults.retry()">刷新状态</button></div>' : '') +
      '<div class="desc"><strong>守城：不受带兵上限限制。</strong>开战时，本城全部可战部队与已抵达的有效援军参战，无需设置迎战数量。</div>' +
      '<div class="desc"><strong>出征：受带兵上限限制。</strong>当前城市每支出征队伍上限 <strong id="battle-defaults-dispatch-cap">' + dispatchCap() +
      '</strong>，由军衔、三军统帅技能与动员加成决定；在出征页面选择兵力。</div>' +
      '<details class="battle-defaults-help"><summary>参战范围与战斗说明</summary>' +
      '<div class="desc">参战名单在开战时锁定：战斗期间新造、返城或新抵达的部队，从下一场战斗开始参战。伤兵、在途部队不加入本场；城内卡车、运输机等后勤部队同样参战并承担战损，工事按现有规则参战。</div>' +
      '<div class="desc">各支援军全部参战，独立计算自己的军官、技能与装备加成；公共战斗科技沿用守城城市配置。同兵种的行动与集火指令由城主统一下达。</div>' +
      '<div class="desc">城市发生战斗后，禁止从该城市发起任何新出征（包括侦查、采集、运输、调遣和驻防），战斗结束后解除锁定。</div>' +
      '<div class="desc">默认行动全账号共用，动员只提高出征上限，不改变守城人数或切换默认行动。战斗或进攻行军期间无法修改默认战术。</div>' +
      '<div class="desc">战斗规则：离线战斗时，兵种优先攻击射程内的敌方同类型兵种；同类型兵种不在射程内或不存在时，攻击射程内最近的敌方兵种。在线指挥时，可指定攻击射程内的任意敌方兵种。战斗中已提交的手动指令在本场后续回合持续生效，直到再次修改，不改写账号默认战术。</div></details>';
    html += '<div class="battle-defaults-head"><span>兵种 / 城内兵力</span><span>守城参战</span><span>出征战斗</span><span>守城战斗</span></div>';
    // 保留原有列布局，将数量输入替换为只读参战范围，避免出现仍可筛选守军的误导。
    Object.keys(G.DATA.units).forEach(function (unitId) {
      var unit = G.DATA.units[unitId];
      var available = Math.max(0, Number(Core.state && Core.state.army && Core.state.army[unitId]) || 0);
      html += '<div class="battle-defaults-row" data-unit-id="' + unitId + '"><span class="battle-defaults-name" title="' + G.escapeHtml(unit.name) + '">' +
        G.escapeHtml(G.unitDisplayName ? G.unitDisplayName(unit.name) : unit.name) +
        '<small>当前城内 ' + available + '</small></span>' +
        '<div><span class="battle-defaults-mobile-label">守城参战</span>全部参战</div>';
      ['outgoing', 'defending'].forEach(function (side) {
        var label = side === 'outgoing' ? '出征战斗' : '守城战斗';
        var selected = defaults[side] && defaults[side][unitId];
        var selectedChoice = actionChoices.find(function (choice) { return choice[0] === selected; }) || actionChoices[0];
        var menuId = 'battle-defaults-' + side + '-' + unitId;
        html += '<div><span class="battle-defaults-mobile-label">' + label + '</span>' +
          '<div class="battle-defaults-picker" onkeydown="Game.BattleDefaults.handleActionKey(event)" onfocusout="Game.BattleDefaults.leaveActionMenu(event)">' +
          '<button type="button" class="battle-defaults-trigger" data-label="' + G.escapeHtml(unit.name + label) +
          '" aria-label="' + G.escapeHtml(unit.name + label + '：' + selectedChoice[1]) + '" aria-haspopup="listbox" aria-expanded="false" aria-controls="' + menuId +
          '"' + (locked ? ' disabled' : '') + ' onclick="Game.BattleDefaults.toggleActionMenu(this)"><span class="battle-defaults-value">' + selectedChoice[1] + '</span><span class="battle-defaults-chevron" aria-hidden="true"></span></button>' +
          '<div id="' + menuId + '" class="battle-defaults-options" role="listbox" aria-label="' + G.escapeHtml(unit.name + label) + '" hidden>';
        actionChoices.forEach(function (choice) {
          html += '<button type="button" role="option" tabindex="-1" aria-selected="' + (selectedChoice[0] === choice[0] ? 'true' : 'false') +
            '" onclick="Game.BattleDefaults.chooseAction(this,\'' + side + '\',\'' + unitId + '\',\'' + choice[0] + '\')">' + choice[1] + '</button>';
        });
        html += '</div></div></div>';
      });
      html += '</div>';
    });
    html += '<div class="battle-defaults-footer"><button type="button" id="battle-defaults-save" class="btn ok"' + (saving || locked ? ' disabled' : '') + ' onclick="Game.BattleDefaults.save()">保存默认战术</button>' +
      '<span id="battle-defaults-message" role="status">' + G.escapeHtml(message) + '</span></div></div>';
    v.innerHTML = html;
  }

  G.BattleDefaults = {
    refreshMobilization: refreshMobilization,
    open: function () { G.go('battleDefaults'); },
    toggleActionMenu: toggleActionMenu,
    chooseAction: chooseAction,
    handleActionKey: handleActionKey,
    leaveActionMenu: leaveActionMenu,
    setAction: setAction,
    save: save,
    retry: function () { loading = false; load(); }
  };
  Core.views.battleDefaults = render;
})(window.Game);
