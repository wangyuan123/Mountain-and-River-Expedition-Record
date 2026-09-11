/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;
  var Core = G.Core;

  function costText(cost) {
    var arr = [];
    var emojiMap = (G.DATA && G.DATA.resEmoji) || {};
    for (var k in cost) {
      var ico = emojiMap[k] || G.DATA.resources[k].icon || k;
      arr.push(ico + cost[k]);
    }
    return arr.join(' ');
  }

  function fortCost(id, n) {
    var f = D.forts[id];
    var cost = {};
    for (var k in f.cost) cost[k] = f.cost[k] * n;
    return cost;
  }

  function readQty(inputId) {
    var el = document.getElementById(inputId);
    if (!el) return 1;
    var n = parseInt(el.value, 10);
    if (isNaN(n) || n <= 0) return 1;
    return n;
  }

  function fortCap() {
    return (Core.state.buildings.wall || 0) * 800;
  }

  function totalForts() {
    var s = Core.state, sum = 0;
    for (var id in D.forts) sum += (s.forts[id] || 0);
    return sum;
  }

  function armyEmpty() {
    var s = Core.state;
    for (var id in s.army) if (s.army[id] > 0) return false;
    return true;
  }

  var Fort = {
    build: function (id, inputId) {
      var n = readQty(inputId);
      G.API.buildFort(id, n).then(function () {
        G.toast('修筑 ' + D.forts[id].name + ' x' + n);
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '修筑失败');
      });
    },

    dismantle: function (id, inputId) {
      var n = readQty(inputId);
      G.API.dismantleFort(id, n).then(function () {
        G.toast('拆除 ' + D.forts[id].name + ' x' + n);
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '拆除失败');
      });
    },

    renderView: function (v) {
      var s = Core.state;
      var wallLv = s.buildings.wall || 0;
      var h = '';
      h += '<div class="title">- 城防工事 -</div>';
      h += '<div class="desc">围墙 Lv.' + wallLv + '  城防上限 ' + totalForts() + '/' + fortCap() + '。城防在守城战中作为防御单位参战,会被击毁。需先建围墙。</div>';
      h += '<div class="desc">守城战:敌军来袭时由后端真实战斗自动触发。</div>';
      h += '<div class="menu">';
      var idx = 0;
      Object.keys(D.forts).forEach(function (id) {
        idx++;
        var f = D.forts[id];
        var have = s.forts[id] || 0;
        var inpId = 'fqty_' + id;
        h += '<div class="menu-item ok">';
        h += '<span class="num">[' + idx + ']</span> ';
        h += '<span class="n">' + f.name + '</span> ';
        h += '<span class="lv">x' + have + '</span>';
        h += '<div class="d">攻' + f.atk + ' 防' + f.def + ' 血' + f.hp + ' 射程' + f.range + '</div>';
        h += '<div class="d">' + f.desc + '</div>';
        if (f.strongVs && D.units[f.strongVs]) h += '<div class="d">相克: ' + D.units[f.strongVs].name + ' (×1.5)</div>';
        h += '<div class="cost">单价: ' + costText(f.cost) + '</div>';
        h += '<div class="btn-row">';
        h += '<input class="qty" id="' + inpId + '" type="number" min="1" value="10" style="width:70px" />';
        h += '<button class="btn" onclick="Game.Fort.build(\'' + id + '\',\'' + inpId + '\')">修筑</button>';
        if (have > 0) h += '<button class="btn warn" onclick="Game.Fort.dismantle(\'' + id + '\',\'' + inpId + '\')">拆除</button>';
        h += '</div>';
        h += '</div>';
      });
      h += '</div>';
      h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
      v.innerHTML = h;
    }
  };

  G.Fort = Fort;
  Core.views.fort = function (v) { Fort.renderView(v); };
})(window.Game);
