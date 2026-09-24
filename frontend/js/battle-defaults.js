/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var Core = G.Core;
  var defaults = null;
  var playerId = null;
  var loading = false;
  var saving = false;
  var message = '';
  var requestId = 0;

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
      message = '';
      if (Core.route === 'battleDefaults') Core.render();
    }).catch(function (error) {
      if (currentRequest !== requestId) return;
      updateMessage(error.message || '默认战术加载失败，请重试');
    }).finally(function () { if (currentRequest === requestId) loading = false; });
  }

  /** 修改当前草稿；只有点击保存后，离线战斗才使用新设置。 */
  function setAction(side, unitId, action) {
    if (!defaults || !defaults[side] || !Object.prototype.hasOwnProperty.call(defaults[side], unitId)) return;
    if (['ADVANCE', 'HOLD', 'RETREAT'].indexOf(action) < 0) return;
    defaults[side][unitId] = action;
    updateMessage('有未保存的修改');
  }

  /** 保存攻守两套完整配置，后端按当前登录账号校验并持久化。 */
  function save() {
    if (!defaults || saving) return;
    saving = true;
    var button = document.getElementById('battle-defaults-save');
    if (button) button.disabled = true;
    var snapshot = JSON.parse(JSON.stringify(defaults));
    return G.API.saveBattleDefaults(snapshot).then(function () {
      if (playerId !== (Core.state && Core.state.player && Core.state.player.id)) return;
      updateMessage(JSON.stringify(defaults) === JSON.stringify(snapshot) ? '已保存，后续离线战斗将按此战术执行' : '已保存；仍有未保存的修改');
    }).catch(function (error) {
      updateMessage(error.message || '保存失败，请重试');
    }).finally(function () {
      saving = false;
      if (button) button.disabled = false;
    });
  }

  function render(v) {
    var currentPlayerId = Core.state && Core.state.player && Core.state.player.id;
    if (currentPlayerId !== playerId) {
      requestId++;
      loading = false;
      playerId = currentPlayerId;
      defaults = null;
      message = '';
    }
    var html = '<div class="title">- 默认战术 -</div>' +
      '<div class="desc">按兵种设置出城战斗与守城战斗的默认行动。离线自动结算同样生效；战斗中手动指令只覆盖当前回合。</div>';
    if (!defaults) {
      v.innerHTML = html + '<div class="panel"><div class="desc">正在加载默认战术…</div>' +
        '<div id="battle-defaults-message" role="status">' + G.escapeHtml(message) + '</div>' +
        '<button type="button" class="btn sm" onclick="Game.BattleDefaults.retry()">重试加载</button></div>';
      load();
      return;
    }
    html += '<div class="panel battle-defaults-panel"><div class="battle-defaults-head">' +
      '<span>兵种</span><span>出城战斗</span><span>守城战斗</span></div>';
    Object.keys(G.DATA.units).forEach(function (unitId) {
      var unit = G.DATA.units[unitId];
      html += '<div class="battle-defaults-row"><span class="battle-defaults-name" title="' + G.escapeHtml(unit.name) + '">' +
        G.escapeHtml(G.unitDisplayName ? G.unitDisplayName(unit.name) : unit.name) + '</span>';
      ['outgoing', 'defending'].forEach(function (side) {
        var label = side === 'outgoing' ? '出城战斗' : '守城战斗';
        var selected = defaults[side] && defaults[side][unitId];
        html += '<label><span class="battle-defaults-mobile-label">' + label + '</span><select aria-label="' +
          G.escapeHtml(unit.name + label) + '" onchange="Game.BattleDefaults.setAction(\'' + side + '\',\'' + unitId + '\',this.value)">';
        [['ADVANCE', '前进'], ['HOLD', '待命'], ['RETREAT', '后退']].forEach(function (choice) {
          html += '<option value="' + choice[0] + '"' + (selected === choice[0] ? ' selected' : '') + '>' + choice[1] + '</option>';
        });
        html += '</select></label>';
      });
      html += '</div>';
    });
    html += '<div class="battle-defaults-footer"><button type="button" id="battle-defaults-save" class="btn ok" onclick="Game.BattleDefaults.save()">保存默认战术</button>' +
      '<span id="battle-defaults-message" role="status">' + G.escapeHtml(message) + '</span></div></div>';
    v.innerHTML = html;
  }

  G.BattleDefaults = {
    setAction: setAction,
    save: save,
    retry: function () { loading = false; load(); }
  };
  Core.views.battleDefaults = render;
})(window.Game);
