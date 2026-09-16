/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;
  var Core = G.Core;

  function zoneUnlocked(zoneId) {
    var z = D.zones[zoneId];
    if (!z || !z.unlock) return true;
    var p = Core.state.progress[z.unlock.zone] || {};
    var st = p[z.unlock.stage] || {};
    return !!st.cleared;
  }

  function stageState(zoneId, stageIdx) {
    var p = Core.state.progress[zoneId] || {};
    var stage = D.zones[zoneId].stages[stageIdx];
    return p[stage.id] || { cleared: false, stars: 0 };
  }

  function enemyText(enemy) {
    var arr = [];
    for (var id in enemy) arr.push(D.units[id].name + 'x' + enemy[id]);
    return arr.join(' ');
  }

  var Map = {
    refreshUnlock: function () {},

    enterStage: function (zoneId, stageIdx) {
      if (!zoneUnlocked(zoneId)) { G.toast('战区未解锁'); return; }
      var stage = D.zones[zoneId].stages[stageIdx];
      var prevCleared = stageIdx === 0 || (stageState(zoneId, stageIdx - 1).cleared);
      if (!prevCleared) { G.toast('需先通关上一关'); return; }
      G.toast('战斗由后端自动处理');
    },

    renderView: function (v) {
      var h = '';
      h += '<div class="title">- 战场地图 -</div>';
      h += '<div class="desc">推进战役解锁新战区。资源野地可在世界地图中侦察与占领。</div>';

      h += '<div class="zone-head">=== 战役 ===</div>';
      h += '<div class="menu">';
      Object.keys(D.zones).forEach(function (zoneId) {
        var z = D.zones[zoneId];
        var unlocked = zoneUnlocked(zoneId);
        h += '<div class="zone-head">--- ' + z.name + ' ---' + (unlocked ? '' : ' (锁定)') + '</div>';
        if (!unlocked) {
          var u = z.unlock;
          h += '<div class="desc">解锁条件: 通关 ' + D.zones[u.zone].name + ' 第' + u.stage + '关</div>';
          return;
        }
        h += '<div class="desc">' + z.desc + '</div>';
        z.stages.forEach(function (stage, sIdx) {
          var st = stageState(zoneId, sIdx);
          var prevOk = sIdx === 0 || stageState(zoneId, sIdx - 1).cleared;
          var can = prevOk;
          var cls = can ? 'menu-item ok' : 'menu-item lock';
          h += '<div class="' + cls + '" onclick="Game.Map.enterStage(\'' + zoneId + '\',' + sIdx + ')">';
          h += '<span class="n">' + stage.name + '</span> ';
          if (st.cleared) {
            var stars = '';
            for (var i = 0; i < 3; i++) stars += i < st.stars ? '★' : '☆';
            h += '<span class="stars">' + stars + '</span>';
          } else if (can) {
            h += '<span class="lv">可挑战</span>';
          } else {
            h += '<span class="lv">未解锁</span>';
          }
          h += '<div class="d">敌军: ' + enemyText(stage.enemy) + '</div>';
          h += '<div class="cost">奖励: 粮' + stage.reward.food + ' 钢' + stage.reward.steel + ' 油' + stage.reward.oil + ' 稀' + stage.reward.rare + ' 金' + stage.reward.gold + ' 经验' + stage.reward.exp + '</div>';
          h += '</div>';
        });
      });
      h += '</div>';
      h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
      v.innerHTML = h;
    }
  };

  var Wild = {
    scout: function () {
      G.toast('请通过世界地图侦察野地');
    },

    occupy: function () {
      G.toast('请通过世界地图征服野地');
    },

    abandon: function (idx) {
      G.toast('请通过世界地图放弃野地');
    },

    renderView: function (v) {
      var s = Core.state;
      var staffLv = s.buildings.staff || 0;
      var cap = s.wilds.cap + staffLv;
      var h = '';
      h += '<div class="title">- 野地占领 -</div>';
      h += '<div class="desc">参谋部 Lv.' + staffLv + '  野地上限 ' + s.wilds.owned.length + '/' + cap + '。占领后持续产出对应资源。</div>';

      h += '<div class="zone-head">=== 已占领 ===</div>';
      h += '<div class="menu">';
      if (!s.wilds.owned.length) h += '<div class="desc">暂无野地。</div>';
      s.wilds.owned.forEach(function (w, i) {
        var wt = D.wildTypes[w.type];
        var wtOwnedIcon = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(wt.icon)
          ? '<img class="wt-icon" src="' + wt.icon + '" alt="' + wt.name + '"/>'
          : wt.icon;
        h += '<div class="menu-item ok">';
        h += '<span class="n">' + wtOwnedIcon + ' ' + wt.name + ' Lv.' + w.level + '</span>';
        if (wt.res) {
          h += '<span class="lv">产 ' + G.DATA.resources[wt.res].name + ' +' + (20 * w.level) + '/h</span>';
        } else {
          h += '<span class="lv" style="color:#888">无资源</span>';
        }
        h += '<div class="btn-row"><button class="btn warn" onclick="Game.Wild.abandon(' + i + ')">废弃</button></div>';
        h += '</div>';
      });
      h += '</div>';

      h += '<div class="zone-head">=== 侦察与占领 ===</div>';
      h += '<div class="menu-item ok" onclick="Game.Wild.scout()"><span class="num">[侦]</span> 派出侦察(需侦察机)</div>';
      if (s.wilds._scout) {
        var sc = s.wilds._scout;
        var wt = D.wildTypes[sc.type];
        var wtScoutIcon = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(wt.icon)
          ? '<img class="wt-icon" src="' + wt.icon + '" alt="' + wt.name + '"/>'
          : wt.icon;
        h += '<div class="menu-item ok">';
        h += '<span class="n">' + wtScoutIcon + ' ' + wt.name + ' Lv.' + sc.level + '</span>';
        h += '<div class="d">守军: ' + enemyText(sc.garrison) + '</div>';
        if (wt.res) {
          h += '<div class="cost">占领后产出: ' + G.DATA.resources[wt.res].name + ' +' + (20 * sc.level) + '/h</div>';
        } else {
          h += '<div class="cost">占领后无资源产出</div>';
        }
        h += '<div class="btn-row"><button class="btn" onclick="Game.Wild.occupy()">出兵占领</button></div>';
        h += '</div>';
      }
      h += '<div class="menu-item back" onclick="Game.go(\'map\')">[0] 返回地图</div>';
      v.innerHTML = h;
    }
  };

  G.Map = Map;
  G.Wild = Wild;
  Core.views.map = function (v) { Map.renderView(v); };
  Core.views.wild = function (v) { Wild.renderView(v); };
  G.zoneUnlocked = zoneUnlocked;
})(window.Game);
