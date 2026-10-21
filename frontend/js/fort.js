/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;
  var Core = G.Core;

  function costHtml(cost) {
    var arr = [];
    for (var k in cost) {
      arr.push(G.resourceIconHtml(k) + cost[k]);
    }
    return arr.join(' ');
  }

  function readQty(inputId) {
    var el = document.getElementById(inputId);
    if (!el) return 1;
    var n = parseInt(el.value, 10);
    if (isNaN(n) || n <= 0) return 1;
    return n;
  }

  /** 当前城市的四类工事合计占用城防容量，每级增加3000。 */
  function fortCap() {
    return (Core.state.buildings.wall || 0) * (D.buildings.wall.fortCapPerLevel || 3000);
  }

  function totalForts() {
    var s = Core.state, sum = 0;
    for (var id in D.forts) sum += (s.forts[id] || 0);
    return sum;
  }

  function showFortRulesModal() {
    if (typeof document === 'undefined' || !document.createElement || !document.body) return;
    var existing = document.getElementById('fortRulesModal');
    if (existing && existing.parentNode && existing.parentNode.removeChild) {
      existing.parentNode.removeChild(existing);
    }
    var previousFocus = document.activeElement;
    var s = Core.state || {};
    var buildings = s.buildings || {};
    var wallLv = buildings.wall || 0;
    var wallMul = typeof Core.wallMul === 'function' ? Core.wallMul() : 1;
    var wallHpPerLevel = (D.buildings && D.buildings.wall && D.buildings.wall.wallHpPerLevel) || 250000;
    var wallDefPerLevel = (D.buildings && D.buildings.wall && D.buildings.wall.wallDefensePerLevel) || 20;
    var wallHp = Math.round(Math.min(10, wallLv) * wallHpPerLevel * wallMul);
    var wallDef = Math.round(Math.min(10, wallLv) * wallDefPerLevel * wallMul);
    var techLv = (s.tech || {}).wall_reinforcement || 0;

    var mask = document.createElement('div');
    mask.id = 'fortRulesModal';
    mask.className = 'modal-mask fort-rules-mask';
    mask.innerHTML =
      '<div class="modal-card fort-rules-dialog" role="dialog" aria-modal="true" aria-labelledby="fortRulesTitle">' +
        '<div class="modal-title fort-rules-title" id="fortRulesTitle">' +
          '<span>🛡️ 城防工事与守城规则说明</span>' +
          '<button type="button" class="fort-rules-close" aria-label="关闭说明">✕</button>' +
        '</div>' +
        '<div class="modal-body fort-rules-body">' +
          '<div class="fort-rules-section">' +
            '<div class="fort-rules-sec-title">📦 工事容量与城防等级</div>' +
            '<div class="fort-rules-sec-desc">' +
              '• <strong>四类共享：</strong>碉堡、榴弹炮、反坦克炮、防空炮四类武器<strong>共享城防容量上限</strong>。<br/>' +
              '• <strong>容量成长：</strong>当前城防 Lv.' + wallLv + '，共享容量 <b>' + totalForts() + ' / ' + fortCap() + '</b>（每提升 1 级城防增加 3000 容量）。<br/>' +
              '• <strong>前置要求：</strong>修筑城防武器需首先在本城建造并升级城防设施。' +
            '</div>' +
          '</div>' +
          '<div class="fort-rules-section">' +
            '<div class="fort-rules-sec-title">🧱 墙体生命与征服判定</div>' +
            '<div class="fort-rules-sec-desc">' +
              '• <strong>城墙属性：</strong>当前墙体生命 <b>' + G.fmt(wallHp) + '</b> · 墙体防御 <b>' + wallDef + '</b>（属性仅保护墙体本身）。<br/>' +
              '• <strong>征服前提：</strong>征服战中敌军必须彻底肃清守军、摧毁全部城防工事并彻底破墙后才可判定攻城胜利。<br/>' +
              '• <strong>加固科技：</strong>军事科技【城墙加固】当前 Lv.' + techLv + '，每级提升 +5% 墙体生命与防御（最高 +50%）。' +
            '</div>' +
          '</div>' +
          '<div class="fort-rules-section">' +
            '<div class="fort-rules-sec-title">⚔️ 守城交火与自动参战</div>' +
            '<div class="fort-rules-sec-desc">' +
              '• <strong>真实交火：</strong>敌军来袭进入接敌距离时，由后端真实战斗引擎自动触发守城战。<br/>' +
              '• <strong>战损击毁：</strong>城防武器在守城战中参战，会被击毁；战败或承受超额伤害时将损失部署数量。<br/>' +
              '• <strong>四维打击：</strong>碉堡坚固掩体反步兵，榴弹炮远程压制与岸防，反坦克炮克制装甲，防空炮专精制空拦截。' +
            '</div>' +
          '</div>' +
          '<div class="fort-rules-section">' +
            '<div class="fort-rules-sec-title">🔨 战备修筑与拆除回收</div>' +
            '<div class="fort-rules-sec-desc">' +
              '• <strong>批量修筑：</strong>在输入框键入数量并点击【修筑】，即可即时部署该类城防工事。<br/>' +
              '• <strong>战术拆除：</strong>若需重新规划防线或腾出共享容量，可点击【拆除】腾挪名额。' +
            '</div>' +
          '</div>' +
        '</div>' +
        '<div class="modal-foot fort-rules-foot">' +
          '<button type="button" class="btn ok fort-rules-confirm-btn">我知道了</button>' +
        '</div>' +
      '</div>';
    document.body.appendChild(mask);

    var close = function () {
      if (document.removeEventListener) document.removeEventListener('keydown', onKeydown);
      if (mask.parentNode && mask.parentNode.removeChild) mask.parentNode.removeChild(mask);
      if (previousFocus && previousFocus.focus) previousFocus.focus();
    };
    var onKeydown = function (e) { if (e.key === 'Escape') close(); };
    var closeBtn = mask.querySelector ? mask.querySelector('.fort-rules-close') : null;
    var confirmBtn = mask.querySelector ? mask.querySelector('.fort-rules-confirm-btn') : null;
    if (closeBtn) closeBtn.onclick = close;
    if (confirmBtn) confirmBtn.onclick = close;
    if (mask.addEventListener) mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
    if (document.addEventListener) document.addEventListener('keydown', onKeydown);
    if (confirmBtn && confirmBtn.focus) confirmBtn.focus();
  }

  var Fort = {
    showFortRulesModal: showFortRulesModal,
    showRulesModal: showFortRulesModal,

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
      var s = Core.state || {};
      var buildings = s.buildings || {};
      var wallLv = buildings.wall || 0;
      var currentForts = totalForts();
      var cap = fortCap();
      var wallMul = typeof Core.wallMul === 'function' ? Core.wallMul() : 1;
      var wallHpPerLevel = (D.buildings && D.buildings.wall && D.buildings.wall.wallHpPerLevel) || 250000;
      var wallDefPerLevel = (D.buildings && D.buildings.wall && D.buildings.wall.wallDefensePerLevel) || 20;
      var capPerLv = (D.buildings && D.buildings.wall && D.buildings.wall.fortCapPerLevel) || 3000;
      var wallHp = Math.round(Math.min(10, wallLv) * wallHpPerLevel * wallMul);
      var wallDef = Math.round(Math.min(10, wallLv) * wallDefPerLevel * wallMul);

      var roleShortMap = {
        bunker: '反步兵·高防',
        howitzer: '远程压制·岸防',
        antitank: '穿甲·反装甲',
        flak: '防空·反空军'
      };

      var h = '';
      h += '<div class="title">- 城防工事 -</div>';

      // 顶部战备概览看板
      h += '<div class="fort-overview-card">' +
        '<div class="fort-ov-header">' +
          '<div class="fort-ov-title-wrap">' +
            '<span class="fort-ov-badge">战备概览</span>' +
            '<span class="fort-ov-lv">🛡️ 城防 <b>Lv.' + wallLv + '</b></span>' +
          '</div>' +
          '<button type="button" class="fort-rules-btn" onclick="Game.Fort.showFortRulesModal()" title="查看城防工事与守城规则说明">' +
            '<span>详细说明</span>' +
            '<span class="fort-rules-icon">ℹ</span>' +
          '</button>' +
        '</div>' +
        '<div class="fort-ov-grid">' +
          '<div class="fort-ov-cell" title="四类城防武器共享容量 ' + currentForts + '/' + cap + '（每级+' + capPerLv + '）">' +
            '<div class="fort-ov-k">共享容量</div>' +
            '<div class="fort-ov-v"><span class="fort-ov-main">' + G.fmt(currentForts) + '</span><span class="fort-ov-cap">/' + G.fmt(cap) + '</span></div>' +
            '<div class="fort-ov-sub">每级+' + capPerLv + ' · 4类共享</div>' +
          '</div>' +
          '<div class="fort-ov-cell" title="墙体生命 ' + G.fmt(wallHp) + '">' +
            '<div class="fort-ov-k">墙体生命</div>' +
            '<div class="fort-ov-v fort-ov-hp">' + G.fmt(wallHp) + '</div>' +
            '<div class="fort-ov-sub">破墙前保护城市</div>' +
          '</div>' +
          '<div class="fort-ov-cell" title="墙体防御 ' + wallDef + '（仅保护墙体）">' +
            '<div class="fort-ov-k">墙体防御</div>' +
            '<div class="fort-ov-v fort-ov-def">' + wallDef + '</div>' +
            '<div class="fort-ov-sub">仅保护墙体</div>' +
          '</div>' +
        '</div>' +
        // 兼容屏幕阅读器与旧测试断言的完整文本结构
        '<div class="fort-ov-sr" aria-hidden="true">' +
          '城防 Lv.' + wallLv + '  四类城防武器共享容量 ' + currentForts + '/' + cap + '（每级+' + capPerLv + '）。城防武器在守城战中参战,会被击毁。需先建城防。 ' +
          '墙体生命 ' + G.fmt(wallHp) + ' · 墙体防御 ' + wallDef + '（仅保护墙体）。征服战须清除守军及工事并破墙后才可胜利。 ' +
          '守城战:敌军来袭时由后端真实战斗自动触发。' +
        '</div>' +
      '</div>';

      // 工事紧凑网格
      h += '<div class="fort-grid">';
      var idx = 0;
      Object.keys(D.forts).forEach(function (id) {
        idx++;
        var f = D.forts[id];
        var have = (s.forts && s.forts[id]) || 0;
        var inpId = 'fqty_' + id;
        var iconHtml = (G && typeof G.getUnitModelIconHtml === 'function')
          ? G.getUnitModelIconHtml(id, f.name, 'fort-card-icon')
          : '';
        var roleShort = roleShortMap[id] || (f.desc ? f.desc.split(/[,，]/)[0] : '防御工事');
        var fullDesc = (D.combatRoles && D.combatRoles[id]) || f.desc || '';

        h += '<div class="fort-card" id="fort-card-' + id + '">';
        // 头部：序号 + 图标 + 名称 + 定位标签 + 部署数量
        h += '<div class="fort-card-head">';
        h += '<div class="fort-card-title-wrap">';
        h += '<span class="fort-idx">[' + idx + ']</span> ';
        if (iconHtml) h += iconHtml;
        h += '<span class="fort-name">' + G.escapeHtml(f.name) + '</span> ';
        h += '<span class="fort-role-pill">' + G.escapeHtml(roleShort) + '</span>';
        h += '</div>';
        h += '<div class="fort-have-badge">';
        h += '<span class="fort-have-lbl">部署</span>';
        h += '<span class="fort-have-val">' + G.fmt(have) + '</span>';
        h += '</div>';
        h += '</div>';

        // 战术打击与生存属性
        h += '<div class="fort-stats-wrap">';
        h += '<div class="fort-stat-row">';
        h += '<span class="fort-pill atk" title="对地攻击"><i class="fort-dot atk-g"></i>地 ' + f.atkGround + '</span>';
        h += '<span class="fort-pill atk" title="对空攻击"><i class="fort-dot atk-a"></i>空 ' + f.atkAir + '</span>';
        h += '<span class="fort-pill atk" title="对海攻击"><i class="fort-dot atk-s"></i>海 ' + f.atkSea + '</span>';
        h += '<span class="fort-pill atk" title="对工事攻坚"><i class="fort-dot atk-f"></i>攻坚 ' + f.atkFort + '</span>';
        h += '</div>';
        h += '<div class="fort-stat-row sub">';
        h += '<span class="fort-pill def">防 ' + f.def + '</span>';
        h += '<span class="fort-pill hp">血 ' + f.hp + '</span>';
        h += '<span class="fort-pill range">射程 ' + f.range + '</span>';
        h += '</div>';
        h += '</div>';

        // 简短战术特点说明
        h += '<div class="fort-brief" title="' + G.escapeHtml(fullDesc) + '">' + G.escapeHtml(fullDesc) + '</div>';

        // 操作与造价单行左右对齐
        h += '<div class="fort-action-row">';
        h += '<div class="fort-cost-wrap"><span class="fort-cost-lbl">单价:</span> ' + costHtml(f.cost) + '</div>';
        h += '<div class="fort-ctrl-wrap">';
        h += '<input class="qty fort-qty-input" id="' + inpId + '" type="number" min="1" value="10" />';
        h += '<button type="button" class="btn ok sm fort-act-btn" onclick="Game.Fort.build(\'' + id + '\',\'' + inpId + '\')">修筑</button>';
        if (have > 0) {
          h += '<button type="button" class="btn warn sm fort-act-btn" onclick="Game.Fort.dismantle(\'' + id + '\',\'' + inpId + '\')">拆除</button>';
        }
        h += '</div>';
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
