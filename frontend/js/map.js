/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;
  var Core = G.Core;

  /** 旧战役只展示后端目录和准入状态，不在客户端解锁或重建奖励结算。 */
  function zoneUnlocked(zoneId) {
    return !!(G.ServerRules.value('campaignAccess', {})[zoneId] || {}).allowed;
  }

  function enemyText(enemy) {
    var arr = [];
    for (var id in enemy) arr.push(D.units[id].name + 'x' + enemy[id]);
    return arr.join(' ');
  }

  var Map = {
    refreshUnlock: function () {},

    enterStage: function (zoneId) {
      var access = G.ServerRules.value('campaignAccess', {})[zoneId];
      G.toast(access && access.reason || '战役状态待同步');
    },

    renderView: function (v) {
      var h = '';
      h += '<div class="title">- 战场地图 -</div>';
      h += '<div class="desc">战役资料仅供浏览。请通过世界地图侦察、出征与占领资源野地。</div>';

      h += '<div class="zone-head">=== 战役 ===</div>';
      h += '<div class="menu">';
      Object.keys(D.zones).forEach(function (zoneId) {
        var z = D.zones[zoneId];
        var access = G.ServerRules.value('campaignAccess', {})[zoneId];
        h += '<div class="zone-head">--- ' + z.name + ' ---</div>';
        h += '<div class="desc">' + z.desc + '</div>';
        h += '<div class="desc">' + (access && access.reason || '战役状态待同步') + '</div>';
        z.stages.forEach(function (stage, sIdx) {
          h += '<div class="menu-item lock" onclick="Game.Map.enterStage(\'' + zoneId + '\',' + sIdx + ')">';
          h += '<span class="n">' + stage.name + '</span><span class="lv">仅供浏览</span>';
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
      G.toast('请通过世界地图讨伐占领野地');
    },

    abandon: function (idx) {
      G.toast('请通过世界地图放弃野地');
    },

    renderView: function (v) {
      var s = Core.state;
      var wilds = s.wilds || {};
      var commandLv = wilds.commandLevel == null ? '待同步' : wilds.commandLevel;
      var cap = wilds.cap == null ? '待同步' : wilds.cap;
      var owned = wilds.owned || [];
      var h = '';
      h += '<div class="title">- 野地占领 -</div>';
      h += '<div class="desc">本城市政厅 Lv.' + commandLv + '  野地上限 ' + owned.length + '/' + cap + '。占领资源野地后需派遣部队采集。</div>';

      h += '<div class="zone-head">=== 已占领 ===</div>';
      h += '<div class="menu">';
      if (!owned.length) h += '<div class="desc">暂无本城所属野地。</div>';
      owned.forEach(function (w, i) {
        var wt = D.wildTypes[w.type];
        // 占领记录没有 kind；补齐地图目标类型，无模型地块仍使用类型图标。
        var iconPath = (G.WorldMap && typeof G.WorldMap.icon === 'function') ? G.WorldMap.icon(Object.assign({}, w, { kind: 'wild' })) || wt.icon : wt.icon;
        var wtOwnedIcon = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(iconPath)
          ? '<img class="wt-icon" src="' + iconPath + '" alt="' + wt.name + '"/>'
          : iconPath;
        h += '<div class="menu-item ok">';
        h += '<span class="n">' + wtOwnedIcon + ' ' + wt.name + ' Lv.' + w.level + '</span>';
        if (wt.res) {
          h += '<span class="lv">可采集 ' + G.DATA.resources[wt.res].name + ' · 剩余 ' + (w.remainingRes == null ? '待同步' : w.remainingRes) + '</span>';
        } else {
          h += '<span class="lv" style="color:#888">无资源</span>';
        }
        h += '<div class="btn-row"><button class="btn warn" onclick="Game.Wild.abandon(' + i + ')">废弃</button></div>';
        h += '</div>';
      });
      h += '</div>';

      h += '<div class="zone-head">=== 侦察与占领 ===</div>';
      h += '<div class="menu-item ok" onclick="Game.Wild.scout()"><span class="num">[侦]</span> 派出侦察(需侦查部队)</div>';
      if (wilds._scout) {
        var sc = wilds._scout;
        var wt = D.wildTypes[sc.type];
        var scIconPath = (G.WorldMap && typeof G.WorldMap.icon === 'function') ? G.WorldMap.icon(Object.assign({}, sc, { kind: 'wild' })) || wt.icon : wt.icon;
        var wtScoutIcon = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(scIconPath)
          ? '<img class="wt-icon" src="' + scIconPath + '" alt="' + wt.name + '"/>'
          : scIconPath;
        h += '<div class="menu-item ok">';
        h += '<span class="n">' + wtScoutIcon + ' ' + wt.name + ' Lv.' + sc.level + '</span>';
        h += '<div class="d">守军: ' + enemyText(sc.garrison) + '</div>';
        if (wt.res) {
          h += '<div class="cost">可采集: ' + G.DATA.resources[wt.res].name + ' · 剩余 ' + (sc.remainingRes == null ? '待同步' : sc.remainingRes) + '</div>';
        } else {
          h += '<div class="cost">无可采集资源</div>';
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
