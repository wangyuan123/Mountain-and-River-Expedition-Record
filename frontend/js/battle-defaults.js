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

  /** 服务端锁定状态覆盖所有城市；本地军情用于页面打开期间及时禁用编辑。 */
  function isLocked() {
    if (!defaults) return false;
    var world = Core.state && Core.state.world || {};
    var attacking = (world.marches || []).some(function (march) {
      return !march.returning && !march.gathering &&
        (march.action === 'conquer' || march.action === 'plunder' ||
          march.action == null || String(march.action).indexOf('tactical') === 0);
    });
    var defending = (world.incoming || []).some(function (march) { return march.inBattle; });
    return !!defaults.locked || attacking || defending;
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
    G.API.getBattleDefaults().then(function (saved) {
      if (currentRequest !== requestId) return;
      if (playerId !== (Core.state && Core.state.player && Core.state.player.id)) return;
      defaults = saved;
      if (defaults.sortieArmy === undefined) defaults.sortieArmy = null;
      message = '';
      if (Core.route === 'battleDefaults') Core.render();
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

  function sortieCap() {
    return typeof Core.sortieCap === 'function' ? Core.sortieCap() : Number(defaults && defaults.sortieCap) || 0;
  }

  function sortieTotal() {
    return Object.keys(defaults.sortieArmy || {}).reduce(function (total, unitId) {
      return total + defaults.sortieArmy[unitId];
    }, 0);
  }

  function sortieAvailable(unitId) {
    return Math.max(0, Number(Core.state && Core.state.army && Core.state.army[unitId]) || 0);
  }

  /** 单兵种最多使用其驻军及扣除其他已选兵种后的剩余迎战额度。 */
  function sortieUnitMax(unitId) {
    var configured = defaults.sortieArmy && defaults.sortieArmy[unitId] || 0;
    return Math.min(sortieAvailable(unitId), Math.max(0, sortieCap() - sortieTotal() + configured));
  }

  /** 换城或驻军、加成变化后，仅调整本地草稿；与服务端选兵一致，先截驻军再按比例限额。 */
  function fitSortieArmy() {
    if (!defaults || defaults.sortieArmy == null) return false;
    var units = Object.keys(defaults.sortieArmy).sort();
    var changed = false;
    units.forEach(function (unitId) {
      var count = Math.min(defaults.sortieArmy[unitId], sortieAvailable(unitId));
      if (count !== defaults.sortieArmy[unitId]) changed = true;
      defaults.sortieArmy[unitId] = count;
    });
    var total = sortieTotal();
    var cap = sortieCap();
    if (total > cap) {
      var selected = {};
      units.forEach(function (unitId) {
        selected[unitId] = defaults.sortieArmy[unitId];
        defaults.sortieArmy[unitId] = Math.floor(defaults.sortieArmy[unitId] * cap / total);
      });
      var remaining = cap - sortieTotal();
      // 按兵种 ID 分配整数余数，零兵种保持不出战，且不超过对应驻军。
      units.forEach(function (unitId) {
        if (remaining > 0 && defaults.sortieArmy[unitId] < selected[unitId]) {
          defaults.sortieArmy[unitId]++;
          remaining--;
        }
      });
      changed = true;
    }
    return changed;
  }

  /** 拖动时原位同步两种输入及其他兵种额度，避免整页渲染打断滑动操作。 */
  function syncSortieControls() {
    Object.keys(G.DATA.units).forEach(function (unitId) {
      var maximum = sortieUnitMax(unitId);
      ['range', 'number'].forEach(function (type) {
        var input = document.getElementById('battle-defaults-sortie-' + type + '-' + unitId);
        if (!input) return;
        input.max = String(maximum);
        input.value = String(defaults.sortieArmy[unitId] || 0);
        input.disabled = type === 'range' && maximum === 0;
        // 复用军队页滑动条样式，额度变化时同时更新已选比例的填充进度。
        if (type === 'range') input.style.setProperty('--p', (maximum > 0 ? Number(input.value) / maximum * 100 : 0).toFixed(1) + '%');
      });
      var limit = document.getElementById('battle-defaults-sortie-limit-' + unitId);
      if (limit) limit.textContent = String(maximum);
    });
    var total = document.getElementById('battle-defaults-sortie-total');
    if (total) total.textContent = String(sortieTotal());
    var remaining = document.getElementById('battle-defaults-sortie-remaining');
    if (remaining) remaining.textContent = String(Math.max(0, sortieCap() - sortieTotal()));
    var cap = document.getElementById('battle-defaults-sortie-cap');
    if (cap) cap.textContent = String(sortieCap());
  }

  /** 空配置表示自动迎战，手动配置的 0 明确表示该兵种不出城。 */
  function setAutomaticSortie(automatic) {
    if (isLocked()) return;
    if (!defaults) return;
    defaults.sortieArmy = automatic ? null : {};
    updateMessage('有未保存的修改');
    Core.render();
  }

  function sortieError() {
    if (!defaults || defaults.sortieArmy == null) return '';
    var invalid = Object.keys(defaults.sortieArmy).some(function (unitId) {
      var count = defaults.sortieArmy[unitId];
      return !Number.isSafeInteger(count) || count < 0;
    });
    if (invalid) return '迎战兵力必须为非负整数';
    if (sortieTotal() > sortieCap()) return '迎战兵力超过出城迎战上限 ' + sortieCap();
    return '';
  }

  /** 滑动条和数字输入共用上限；超额截断，非法数字恢复上一有效值。 */
  function setSortieCount(unitId, value) {
    if (isLocked()) return;
    if (!defaults || defaults.sortieArmy == null || !G.DATA.units[unitId]) return;
    var adjusted = fitSortieArmy();
    var count = Number(value);
    if (!Number.isSafeInteger(count) || count < 0) {
      syncSortieControls();
      updateMessage('迎战兵力必须为非负整数，已恢复有效数量' + (adjusted ? '；编队已按当前驻军及上限调整，请保存' : ''));
      return;
    }
    var maximum = sortieUnitMax(unitId);
    defaults.sortieArmy[unitId] = Math.min(count, maximum);
    syncSortieControls();
    updateMessage(count > maximum ? '已限制到该兵种可选上限 ' + maximum + '，有未保存的修改' :
      adjusted ? '已按当前驻军和迎战上限调整编队，有未保存的修改' : '有未保存的修改');
  }

  function currentPreferences() {
    return { outgoing: defaults.outgoing, defending: defaults.defending, sortieArmy: defaults.sortieArmy };
  }

  /** 保存攻守两套完整配置，后端按当前登录账号校验并持久化。 */
  function save() {
    if (!defaults || saving) return;
    if (isLocked()) { updateMessage(lockedMessage); return; }
    // 保存前按最新驻军和迎战额度同步表单，再一次性提交当前可见的三项配置。
    if (fitSortieArmy()) {
      syncSortieControls();
    }
    var error = sortieError();
    if (error) { updateMessage(error); return; }
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
      // 编辑自动选兵会重新渲染按钮，保存结束后必须恢复当前 DOM 中的按钮。
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
      '<div class="desc">按兵种统一设置迎战数量与攻守默认行动，保存后用于后续迎战及离线战斗。</div>';
    if (!defaults) {
      v.innerHTML = html + '<div class="panel"><div class="desc">正在加载默认战术…</div>' +
        '<div id="battle-defaults-message" role="status">' + G.escapeHtml(message) + '</div>' +
        '<button type="button" class="btn sm" onclick="Game.BattleDefaults.retry()">重试加载</button></div>';
      load();
      return;
    }
    var locked = isLocked();
    if (!locked && fitSortieArmy()) message = '已按当前驻军和迎战上限调整编队，请保存生效';
    var automaticSortie = defaults.sortieArmy == null;
    html += '<div class="panel battle-defaults-panel"><h3>出城迎战兵力与默认行动</h3>' +
      (locked ? '<div class="desc" role="status">' + lockedMessage + '。<button type="button" class="btn sm" onclick="Game.BattleDefaults.retry()">刷新状态</button></div>' : '') +
      '<div class="desc">当前城市迎战上限 <strong id="battle-defaults-sortie-cap">' + sortieCap() + '</strong>。</div>' +
      '<details class="battle-defaults-help"><summary>迎战规则与战斗说明</summary>' +
      '<div class="desc">迎战上限 = 基础出兵上限 × 1.5 + 各类加成。</div>' +
      '<div class="desc">此编队用于敌军来袭时迎战，使用守城战斗的默认行动；主动攻击仍在出征页面选兵。' +
      '配置全账号共用，实际按被攻击城市的驻军及加成计算；兵力不足按实际数量出战，超限按比例缩减。工事不占名额，未选驻军不参战。</div>' +
      '<div class="desc">关闭自动选兵后，可拖动滑动条或输入数量；各兵种合计不能超过迎战上限。切换城市或驻军、上限变化时会调整本地编队，保存后生效。</div>' +
      '<div class="desc">战斗规则：离线战斗时，兵种优先攻击射程内的敌方同类型兵种；同类型兵种不在射程内或不存在时，攻击射程内最近的敌方兵种。在线指挥时，可指定攻击射程内的任意敌方兵种。战斗中手动指令只覆盖当前回合。</div></details>' +
      '<label class="battle-defaults-sortie-mode"><input type="checkbox"' + (automaticSortie ? ' checked' : '') + (locked ? ' disabled' : '') +
      ' onchange="Game.BattleDefaults.setAutomaticSortie(this.checked)"> 按驻军比例自动选兵（不超过迎战上限）</label>';
    html += '<div class="desc battle-defaults-summary">' + (automaticSortie ? '自动迎战：开战时按驻军比例选兵。例如：城内有步兵6万、坦克4万，迎战上限5万时，会派步兵3万、坦克2万，其余留城；驻军未超过上限时全部迎战。' :
      '已配置 <strong id="battle-defaults-sortie-total">' + sortieTotal() + '</strong>；剩余额度 <strong id="battle-defaults-sortie-remaining">' +
      Math.max(0, sortieCap() - sortieTotal()) + '</strong>；全部设为 0 时，仅工事参与防守。') + '</div>';
    html += '<div class="battle-defaults-head"><span>兵种 / 驻军</span><span>迎战数量</span><span>出城战斗</span><span>守城战斗</span></div>';
    // 每个兵种只渲染一行，迎战数量和两套行动共用同一份草稿及保存入口。
    Object.keys(G.DATA.units).forEach(function (unitId) {
      var unit = G.DATA.units[unitId];
      var available = sortieAvailable(unitId);
      var configured = automaticSortie ? '' : (defaults.sortieArmy[unitId] || 0);
      var maximum = sortieUnitMax(unitId);
      var progress = (maximum > 0 ? Number(configured || 0) / maximum * 100 : 0).toFixed(1) + '%';
      html += '<div class="battle-defaults-row" data-unit-id="' + unitId + '"><span class="battle-defaults-name" title="' + G.escapeHtml(unit.name) + '">' +
        G.escapeHtml(G.unitDisplayName ? G.unitDisplayName(unit.name) : unit.name) +
        '<small>当前驻军 ' + available + ' · 可选上限 <span id="battle-defaults-sortie-limit-' + unitId + '">' + maximum + '</span></small></span>' +
        '<div class="battle-defaults-sortie-cell"><span class="battle-defaults-mobile-label">迎战数量</span>' +
        '<div class="battle-defaults-sortie-controls"><div class="recruit-slider-wrap"><input type="range" class="recruit-slider" style="--p:' + progress + '" id="battle-defaults-sortie-range-' + unitId + '" min="0" step="1" max="' + maximum +
        '" value="' + (configured || 0) + '" aria-label="' + G.escapeHtml(unit.name + '迎战数量滑动条') + '"' +
        (automaticSortie || maximum === 0 || locked ? ' disabled' : '') +
        ' oninput="Game.BattleDefaults.setSortieCount(\'' + unitId + '\',this.value)"></div>' +
        '<input type="number" id="battle-defaults-sortie-number-' + unitId + '" min="0" step="1" max="' + maximum +
        '" value="' + configured + '" aria-label="' + G.escapeHtml(unit.name + '迎战数量') + '"' +
        (automaticSortie || locked ? ' disabled' : '') + (automaticSortie ? ' placeholder="自动"' : '') +
        ' oninput="Game.BattleDefaults.setSortieCount(\'' + unitId + '\',this.value)"></div></div>';
      ['outgoing', 'defending'].forEach(function (side) {
        var label = side === 'outgoing' ? '出城战斗' : '守城战斗';
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
    toggleActionMenu: toggleActionMenu,
    chooseAction: chooseAction,
    handleActionKey: handleActionKey,
    leaveActionMenu: leaveActionMenu,
    setAction: setAction,
    setAutomaticSortie: setAutomaticSortie,
    setSortieCount: setSortieCount,
    save: save,
    retry: function () { loading = false; load(); }
  };
  Core.views.battleDefaults = render;
})(window.Game);
