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

  var branchOrder = ['军事', '机动', '后勤', '侦察'];
  var activeBranch = '军事';

  var Tech = {
    setTab: function (branch) {
      if (branchOrder.indexOf(branch) < 0) return;
      activeBranch = branch;
      Core.render();
      var tab = document.getElementById('tech-tab-' + branchOrder.indexOf(branch));
      if (tab) tab.focus({ preventScroll: true });
    },

    tabKey: function (event, index) {
      var next = index;
      if (event.key === 'ArrowRight') next = (index + 1) % branchOrder.length;
      else if (event.key === 'ArrowLeft') next = (index + branchOrder.length - 1) % branchOrder.length;
      else if (event.key === 'Home') next = 0;
      else if (event.key === 'End') next = branchOrder.length - 1;
      else return;
      event.preventDefault();
      Tech.setTab(branchOrder[next]);
    },

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
      h += '<div class="desc">科研中心 Lv.' + labLv + ' · ' + branchOrder.length + ' 类科技，共 ' + Object.keys(D.techs).length + ' 项。研究即时完成，高级科技需提升科研中心等级。</div>';
      h += '<div class="tech-tabs" role="tablist" aria-label="科技分类">';
      branchOrder.forEach(function (branch, index) {
        var selected = branch === activeBranch;
        h += '<button type="button" id="tech-tab-' + index + '" class="tech-tab' + (selected ? ' active' : '') + '" role="tab" aria-selected="' + selected + '" aria-controls="tech-panel" tabindex="' + (selected ? '0' : '-1') + '" onclick="Game.Tech.setTab(\'' + branch + '\')" onkeydown="Game.Tech.tabKey(event,' + index + ')">' + branch + '科技</button>';
      });
      h += '</div>';

      var idx = 0;
      h += '<div class="menu tech-panel" id="tech-panel" role="tabpanel" aria-labelledby="tech-tab-' + branchOrder.indexOf(activeBranch) + '" tabindex="0">';
      branchOrder.forEach(function (branch) {
        Object.keys(D.techs).forEach(function (id) {
          var t = D.techs[id];
          if (t.branch !== branch) return;
          idx++;
          if (branch !== activeBranch) return;
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
          var pctMap = { cap: 10, load: 20, food_save: -5, train: 10, build: -5, medical: 5, range_all: 5 };
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
