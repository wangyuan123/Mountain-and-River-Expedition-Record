/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;
  var Core = G.Core;

  function techCost(id) {
    var t = D.techs[id];
    var lv = Core.state.tech[id] || 0;
    var mul = Math.pow(t.growth, lv);
    var cost = {};
    for (var k in t.baseCost) cost[k] = Math.floor(t.baseCost[k] * mul);
    return cost;
  }

  function costText(cost) {
    var arr = [];
    var emojiMap = (G.DATA && G.DATA.resEmoji) || {};
    for (var k in cost) {
      var ico = emojiMap[k] || G.DATA.resources[k].icon || k;
      arr.push(ico + cost[k]);
    }
    return arr.join(' ');
  }

  var branchOrder = ['指挥', '步兵', '装甲', '航空', '航海', '后勤', '侦察'];

  var Tech = {
    research: function (id) {
      G.API.techUpgrade(id).then(function () {
        G.toast(D.techs[id].name + ' 研究完成');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '研究失败');
      });
    },

    renderView: function (v) {
      var s = Core.state;
      var labLv = s.buildings.lab || 0;
      var h = '';
      h += '<div class="title">- 科研中心 -</div>';
      h += '<div class="desc">科研中心 Lv.' + labLv + '。科技分7大分支共21项,高级科技需更高科研中心。研究瞬时完成。</div>';

      var idx = 0;
      h += '<div class="menu">';
      branchOrder.forEach(function (branch) {
        h += '<div class="zone-head">=== ' + branch + '科技 ===</div>';
        Object.keys(D.techs).forEach(function (id) {
          var t = D.techs[id];
          if (t.branch !== branch) return;
          idx++;
          var lv = s.tech[id] || 0;
          var locked = labLv < t.labReq;
          var maxed = lv >= t.max;
          var cost = techCost(id);
          var can = !locked && !maxed && Core.costEnough(cost);
          var cls = can ? 'menu-item ok' : 'menu-item lock';
          h += '<div class="' + cls + '" onclick="Game.Tech.research(\'' + id + '\')">';
          h += '<span class="num">[' + idx + ']</span> ';
          h += '<span class="n">' + t.name + '</span> ';
          h += '<span class="lv">Lv.' + lv + '/' + t.max + '</span>';
          var pctMap = { cap: 10, load: 20, food_save: -5, train: 10, build: -5, medical: 5 };
          var pct = pctMap[t.affect] !== undefined ? pctMap[t.affect] : 5;
          var cur = lv * pct;
          h += '<div class="d">' + t.desc + ' (当前 ' + (cur > 0 ? '+' : '') + cur + (t.affect === 'recon' || t.affect === 'radar' ? ' 级' : '%') + ')</div>';
          if (locked) h += '<div class="cost">需科研中心 Lv.' + t.labReq + '</div>';
          else if (maxed) h += '<div class="cost">已满级</div>';
          else h += '<div class="cost">下一级: ' + costText(cost) + '</div>';
          h += '</div>';
        });
      });
      h += '</div>';
      h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
      v.innerHTML = h;
    }
  };

  G.Tech = Tech;
  Core.views.tech = function (v) { Tech.renderView(v); };
  G.techCost = techCost;
})(window.Game);
