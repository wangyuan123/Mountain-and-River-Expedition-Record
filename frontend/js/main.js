/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var Core = G.Core;
  var D = G.DATA;

  // 兵种图标（emoji 简写，可被运营替换为 SVG 资源）
  var UNIT_ICON = {
    infantry: '🪖', motor: '🛺', truck: '🚚', armored: '🚙',
    ltank: '🛡', htank: '🛡', assault: '💥', rocket: '🚀',
    scout: '🛩', special: '🪂', fighter: '✈️', bomber: '🛩',
    transport: '🛫', destroyer: '🚢', sub: '🛥', battleship: '🛳', carrier: '🛳'
  };

  function renderArmySummaryList() {
    var s = Core.state || {};
    var arr = [];
    var army = s.army || {};
    var ids = Object.keys(army);
    for (var i = 0; i < ids.length; i++) {
      var id = ids[i];
      var cnt = army[id] || 0;
      if (cnt <= 0) continue;
      var ud = D.units && D.units[id];
      arr.push({ id: id, name: ud ? ud.name : id, cnt: cnt, branch: ud ? ud.branch : '' });
    }
    // 没有兵力时显示空态
    if (!arr.length) {
      return '<div class="army-summary-empty">暂无可用部队，前往 <a onclick="Game.go(\'army\')">造兵</a> 征召</div>';
    }
    // 数量从大到小排序，前 8 个优先
    arr.sort(function (a, b) { return b.cnt - a.cnt; });
    var top = arr.slice(0, 8);
    var html = '';
    for (var i = 0; i < top.length; i++) {
      var u = top[i];
      html += '<div class="army-summary-item">'
            + '<span class="army-summary-icon">' + (UNIT_ICON[u.id] || '⚔') + '</span>'
            + '<span class="army-summary-name">' + G.escapeHtml(u.name) + '</span>'
            + '<span class="army-summary-cnt">' + G.fmt(u.cnt) + '</span>'
            + '</div>';
    }
    if (arr.length > 8) {
      html += '<div class="army-summary-more">还有 ' + (arr.length - 8) + ' 种部队…</div>';
    }
    return html;
  }

  function renderOfficerSummaryCard() {
    var s = Core.state || {};
    var rawOfficers = s.officers || [];
    var officers = rawOfficers.slice();

    // 按照等级降序、星级降序、三维属性总和降序排序
    officers.sort(function (a, b) {
      var lvDiff = (b.level || 1) - (a.level || 1);
      if (lvDiff !== 0) return lvDiff;
      var starDiff = (b.star || 1) - (a.star || 1);
      if (starDiff !== 0) return starDiff;
      var statA = (a.military || 0) + (a.logistics || 0) + (a.knowledge || 0);
      var statB = (b.military || 0) + (b.logistics || 0) + (b.knowledge || 0);
      return statB - statA;
    });

    var totalCount = officers.length;
    var html = '';

    html += '<div class="zone-head"><span class="zone-title">🎖️ 军官将领</span><span class="zone-sub">已招募 ' + totalCount + ' 名</span></div>';
    html += '<div class="home-officer-card" onclick="Game.go(\'officer\')" role="button" tabindex="0" title="点击前往参谋部 · 军官管理">';

    if (totalCount === 0) {
      html += '<div class="home-officer-empty">'
            + '<span class="home-officer-empty-icon">🎖️</span>'
            + '<div class="home-officer-empty-info">'
            + '<div class="home-officer-empty-title">暂未招募将领</div>'
            + '<div class="home-officer-empty-desc">前往军校招募名将，委任市长与指挥官以提升城防与产能</div>'
            + '</div>'
            + '<span class="home-officer-go">前往招募 &gt;</span>'
            + '</div>';
    } else {
      var displayCount = Math.min(3, totalCount);
      var topOfficers = officers.slice(0, displayCount);

      html += '<div class="home-officer-list">';
      for (var i = 0; i < topOfficers.length; i++) {
        var o = topOfficers[i];
        var starColor = (D.starColor && D.starColor[o.star]) || '#ffe14a';

        // 星级显示
        var starsHtml = '';
        var starNum = Math.min(5, Math.max(1, o.star || 1));
        for (var sIdx = 0; sIdx < starNum; sIdx++) starsHtml += '★';
        for (var sIdx2 = starNum; sIdx2 < 5; sIdx2++) starsHtml += '☆';

        // 职位显示
        var roleTag = '';
        if (o.role === 'mayor') {
          roleTag = '<span class="home-officer-role role-mayor">市长</span>';
        } else if (o.role === 'commander') {
          roleTag = '<span class="home-officer-role role-commander">指挥官</span>';
        } else {
          roleTag = '<span class="home-officer-role role-idle">闲置</span>';
        }

        // 技能摘要
        var skillSummary = Core.formatSkills ? Core.formatSkills(o.skills) : '';

        html += '<div class="home-officer-item">';
        html += '<div class="home-officer-top-row">';
        html += '<div class="home-officer-identity">';
        html += roleTag;
        html += '<span class="home-officer-name" style="color:' + starColor + '">' + G.escapeHtml(o.name || '军官') + '</span>';
        html += '<span class="home-officer-stars" style="color:' + starColor + '">' + starsHtml + '</span>';
        html += '</div>';
        html += '<span class="home-officer-level">Lv.' + (o.level || 1) + (o.level >= (G.OFFICER_MAX_LEVEL || 100) ? '<small>(满)</small>' : '') + '</span>';
        html += '</div>';

        html += '<div class="home-officer-bottom-row">';
        html += '<div class="home-officer-stats">';
        html += '<span class="home-officer-stat"><span class="stat-lbl">军事</span><b class="stat-val mil">' + (o.military || 0) + '</b></span>';
        html += '<span class="home-officer-stat"><span class="stat-lbl">后勤</span><b class="stat-val log">' + (o.logistics || 0) + '</b></span>';
        html += '<span class="home-officer-stat"><span class="stat-lbl">学识</span><b class="stat-val kno">' + (o.knowledge || 0) + '</b></span>';
        html += '</div>';
        if (skillSummary) {
          html += '<div class="home-officer-skill" title="' + G.escapeHtml(skillSummary) + '">⚡ ' + G.escapeHtml(skillSummary) + '</div>';
        }
        html += '</div>';

        html += '</div>';
      }
      html += '</div>';

      var mayor = Core.getOfficerByRole('mayor');
      var cmd = Core.getOfficerByRole('commander');
      html += '<div class="home-officer-foot">';
      html += '<div class="home-officer-foot-text">';
      if (totalCount > 3) {
        html += '拥有 <b>' + totalCount + '</b> 名将领 (展示等级最高前3名) · 市长: <b>' + (mayor ? G.escapeHtml(mayor.name) : '未任命') + '</b> · 指挥官: <b>' + (cmd ? G.escapeHtml(cmd.name) : '未任命') + '</b>';
      } else {
        html += '共 <b>' + totalCount + '</b> 名将领 · 市长: <b>' + (mayor ? G.escapeHtml(mayor.name) : '未任命') + '</b> · 指挥官: <b>' + (cmd ? G.escapeHtml(cmd.name) : '未任命') + '</b>';
      }
      html += '</div>';
      html += '<span class="home-officer-go">参谋部 &gt;</span>';
      html += '</div>';
    }

    html += '</div>';
    return html;
  }

  Core.views.login = function (v) {
    var h = '';
    h += '<div class="title">- 山河远征 -</div>';
    h += '<div class="desc">请登录或注册以同步存档到云端</div>';
    h += '<div class="panel">';
    h += '<div class="edit-row"><label>用户名</label><input id="loginUser" class="qty" style="width:100%" maxlength="32" placeholder="3-32位字符"></div>';
    h += '<div class="edit-row"><label>密码</label><input id="loginPass" class="qty" style="width:100%" type="password" maxlength="64" placeholder="6-64位"></div>';
    h += '<div class="btn-row" style="margin-top:8px">';
    h += '<button class="btn ok" onclick="Game.Main.doLogin()">登录</button>';
    h += '<button class="btn" onclick="Game.Main.doRegister()">注册</button>';
    h += '</div>';
    h += '<div id="loginMsg" style="margin-top:6px;font-size:13px"></div>';
    h += '</div>';
    h += '<div class="menu-item back" onclick="Game.Main.guestPlay()">[0] 游客模式</div>';
    v.innerHTML = h;
  };

  var NAV_ITEMS = [
    { key: '1', label: '资源区', route: 'buildRes' },
    { key: '2', label: '军事区', route: 'buildArmy' },
    { key: '3', label: '军情', route: 'alerts' },
    { key: '4', label: '战报', route: 'reports' },
    { key: '5', label: '邮件', route: 'mail' },
    { key: '6', label: '地图', route: 'world' },
    { key: '7', label: '造兵', route: 'army' },
    { key: '8', label: '任务', route: 'mainQuest' },
    { key: '0', label: '军团', route: 'guild' },
    { key: '·', label: '仓库', route: 'depot' }
  ];

  function navBar() {
    var s = Core.state;
    if (!s || !s.world) return '';
    var hasIncoming = s.world.incoming && s.world.incoming.length > 0 && !s.world.alertsViewed;
    var h = '';
    var homeActive = Core.route === 'home' ? ' active' : '';
    h += '<div class="navitem home-tab' + homeActive + '" data-route="home" onclick="Game.go(\'home\')"><span class="navlabel">首</span></div>';
    for (var i = 0; i < NAV_ITEMS.length; i++) {
      var it = NAV_ITEMS[i];
      var action = it.route === '__save__' ? 'Game.save();Game.toast(\'已存档\')'
        : 'Game.go(\'' + it.route + '\')';
      var active = Core.route === it.route ? ' active' : '';
      var alertCls = (it.route === 'alerts' && hasIncoming) ? ' alert' : '';
      var mailUnread = (it.route === 'mail' && G.Mail && G.Mail.unread && G.Mail.unread() > 0) ? G.Mail.unread() : 0;
      var mailBadge = mailUnread ? '<span class="nav-badge">' + mailUnread + '</span>' : '';
      // 主线任务红点
      var questBadge = '';
      if (it.route === 'mainQuest' && G.MainQuest && G.MainQuest.hasUnclaimed && G.MainQuest.hasUnclaimed()) {
        questBadge = '<span class="nav-badge alert-dot">!</span>';
      }
      h += '<div class="navitem' + active + alertCls + '" data-route="' + it.route + '" onclick="' + action + '"><span class="navnum">[' + it.key + ']</span><span class="navlabel">' + (it.icon ? '<img class="nav-icon" src="' + it.icon + '" alt="' + it.label + '"/>' : it.label) + '</span>' + mailBadge + questBadge + '</div>';
    }
    return h;
  }

  function showResourceDetail(key, name, icon, current, cap, rate, production, consumption) {
    var modal = document.createElement('div');
    modal.className = 'modal-mask';
    var percent = cap > 0 ? Math.min(100, Math.floor(current / cap * 100)) : 0;
    var detail = '';
    if (key === 'food') {
      detail =
        '<div class="res-detail-section-title">粮食流向</div>' +
        '<div class="res-detail-value"><span>农田生产</span><b class="positive">+' + G.fmt(production) + '/小时</b></div>' +
        '<div class="res-detail-value"><span>军队消耗</span><b class="neg">-' + G.fmt(consumption) + '/小时</b></div>' +
        '<div class="res-detail-net"><span>每小时净变化</span><b class="' + (rate < 0 ? 'neg' : 'positive') + '">' + (rate >= 0 ? '+' : '') + G.fmt(rate) + '/小时</b></div>' +
        '<div class="res-detail-tip">粮食净变化 = 农田生产 − 军队消耗。净变化为负时，储量会持续减少。</div>';
    } else {
      detail = '<div class="res-detail-value"><span>每小时净产出</span><b class="' + (rate < 0 ? 'neg' : '') + '">' + (rate >= 0 ? '+' : '') + G.fmt(rate) + '/小时</b></div>';
    }
    modal.innerHTML =
      '<div class="modal-card" style="max-width:380px">' +
        '<div class="modal-title">' + icon + ' ' + name + '详情</div>' +
        '<div class="modal-body">' +
          '<div class="res-detail-value"><span>当前储量</span><b>' + G.fmt(current) + '</b></div>' +
          '<div class="res-detail-value"><span>资源上限</span><b>' + G.fmt(cap) + '</b></div>' +
          detail +
          '<div class="res-detail-bar"><span style="width:' + percent + '%"></span></div>' +
          '<div class="res-detail-percent">储量使用率 ' + percent + '%</div>' +
        '</div>' +
        '<div class="modal-foot"><button class="btn ok" id="closeResourceDetail">关闭</button></div>' +
      '</div>';
    document.body.appendChild(modal);
    modal.querySelector('#closeResourceDetail').onclick = function () { modal.remove(); };
    modal.addEventListener('click', function (e) { if (e.target === modal) modal.remove(); });
  }

  function showPopulationDetailModal() {
    var modal = document.createElement('div');
    modal.className = 'modal-mask';

    modal.innerHTML =
      '<div class="modal-card pop-detail-modal" style="max-width:440px">' +
        '<div class="modal-title">👥 平民与民情政务</div>' +
        '<div class="modal-body">' +
          '<div class="pop-detail-summary">' +
            '<div class="pop-stat-box"><span class="pop-stat-label">当前平民</span><span class="pop-stat-val" id="popCivilianVal">-</span></div>' +
            '<div class="pop-stat-box"><span class="pop-stat-label">民居标称容量</span><span class="pop-stat-val" id="popCapVal">-</span></div>' +
            '<div class="pop-stat-box"><span class="pop-stat-label">民心容纳上限</span><span class="pop-stat-val highlight" id="popEffCapVal">-</span></div>' +
            '<div class="pop-stat-box"><span class="pop-stat-label">自然增长速度</span><span class="pop-stat-val positive" id="popGrowthVal">-</span></div>' +
          '</div>' +

          '<div class="pop-sentiment-section">' +
            '<div class="pop-bar-header">' +
              '<span>❤️ 民心值：<b id="popMoraleNum">70</b> / 100</span>' +
              '<span class="pop-status-badge" id="popMoraleBadge">安居乐业</span>' +
            '</div>' +
            '<div class="pop-progress-bar morale-bar"><div class="pop-progress-fill" id="popMoraleFill" style="width:70%"></div></div>' +
            '<div class="pop-bar-header" style="margin-top:10px">' +
              '<span>🔥 民怨值：<b id="popResentNum">0</b> / 100</span>' +
              '<span class="pop-status-badge resentment-badge" id="popResentBadge">风平浪静</span>' +
            '</div>' +
            '<div class="pop-progress-bar resentment-bar"><div class="pop-progress-fill" id="popResentFill" style="width:0%"></div></div>' +
            '<div class="pop-bar-hint">民心决定城市的实际人口容纳率与增长速度；长期重税(>50%)滋生民怨并压抑民心。</div>' +
          '</div>' +

          '<div class="pop-tax-section">' +
            '<div class="pop-section-title">' +
              '<span>💰 调节城市税率</span>' +
              '<span class="pop-current-tax">当前税率：<b id="popCurTaxText">30%</b></span>' +
            '</div>' +
            '<div class="pop-slider-container">' +
              '<div class="pop-slider-labels">' +
                '<span>0% (免税)</span>' +
                '<span id="popSliderNum" class="slider-num-callout">30%</span>' +
                '<span>100% (重税)</span>' +
              '</div>' +
              '<div class="recruit-slider-wrap">' +
                '<input type="range" class="recruit-slider tax-range-slider" id="popTaxSlider" min="0" max="100" step="1" value="30">' +
              '</div>' +
            '</div>' +
            '<div class="pop-tax-preview">' +
              '<div class="pop-preview-row"><span>预计黄金税收：</span><b class="positive" id="popPrevGold">+60/h</b></div>' +
              '<div class="pop-preview-row"><span>预期目标民心：</span><b id="popPrevMorale">70</b></div>' +
              '<div class="pop-preview-row"><span>预期民心容纳：</span><b id="popPrevCap">100 / 100</b></div>' +
              '<div class="pop-tax-warning" id="popTaxWarn">⚖️ 标准税赋：民心平稳，黄金与人口保持平衡发展。</div>' +
            '</div>' +
            '<button class="btn ok pop-action-btn" id="popSaveTaxBtn">应用税率 (30%)</button>' +
          '</div>' +

          '<div class="pop-appease-section">' +
            '<div class="pop-section-title">🕊️ 开仓赈民与安抚民情</div>' +
            '<div class="appease-card-grid">' +
              '<div class="appease-card">' +
                '<div class="appease-card-head">' +
                  '<span class="appease-card-name">🌾 黄金赈民</span>' +
                  '<span class="appease-card-effect">民心 +10 · 民怨 -5</span>' +
                '</div>' +
                '<div class="appease-card-desc">开仓放粮赈济平民，抚慰民情。</div>' +
                '<div class="appease-card-cost">消耗：<span id="appeaseGoldCost">1,000</span> 黄金 <small id="appeaseGoldRemain"></small></div>' +
                '<button class="btn sub appease-btn" id="popAppeaseGoldBtn">开仓赈灾</button>' +
              '</div>' +
              '<div class="appease-card highlight">' +
                '<div class="appease-card-head">' +
                  '<span class="appease-card-name">💎 钻石特赦</span>' +
                  '<span class="appease-card-effect">民心 +25 · 民怨 -20</span>' +
                '</div>' +
                '<div class="appease-card-desc">大赦天下并重金赏赐，迅速平息怨愤。</div>' +
                '<div class="appease-card-cost">消耗：<span>20</span> 钻石 <small id="appeaseDiamondRemain"></small></div>' +
                '<button class="btn ok appease-btn" id="popAppeaseDiamondBtn">特赦犒赏</button>' +
              '</div>' +
            '</div>' +
          '</div>' +
        '</div>' +
        '<div class="modal-foot">' +
          '<button class="btn sub" id="closePopDetail">关闭</button>' +
        '</div>' +
      '</div>';

    document.body.appendChild(modal);

    function updateTaxPreview(taxVal) {
      var s = Core.state || {};
      var civ = Core.civilianPopulation();
      var cap = Core.populationCapacity();
      var resent = Core.resentment();
      var mayor = Core.getOfficerByRole('mayor');
      var mayorKnow = (mayor && mayor.knowledge) ? mayor.knowledge : 0;

      modal.querySelector('#popSliderNum').textContent = taxVal + '%';
      var sliderEl = modal.querySelector('#popTaxSlider');
      if (sliderEl) sliderEl.style.setProperty('--p', taxVal + '%');

      var prevGold = Math.round(civ * (taxVal / 100.0) * (1 + mayorKnow / 100.0) * 2);
      modal.querySelector('#popPrevGold').textContent = '+' + G.fmt(prevGold) + '/h';

      var targetMorale = Math.max(0, Math.min(100, 100 - taxVal - resent));
      modal.querySelector('#popPrevMorale').textContent = targetMorale;

      var targetEffCap = cap <= 0 ? 0 : Math.max(10, Math.round(cap * Math.min(1.0, targetMorale / 70.0)));
      var pct = cap > 0 ? Math.round(targetEffCap / cap * 100) : 100;
      modal.querySelector('#popPrevCap').textContent = G.fmt(targetEffCap) + ' / ' + G.fmt(cap) + ' (' + pct + '%)';

      var warnEl = modal.querySelector('#popTaxWarn');
      if (taxVal > 50) {
        warnEl.className = 'pop-tax-warning danger';
        warnEl.textContent = '⚠️ 重税苛敛：民心将持续下挫，每小时滋生民怨，平民将逃离城市！';
      } else if (taxVal <= 20) {
        warnEl.className = 'pop-tax-warning positive';
        warnEl.textContent = '🌾 轻徭薄赋：民心大幅上升，民怨加速消退，平民快速增长！';
      } else {
        warnEl.className = 'pop-tax-warning';
        warnEl.textContent = '⚖️ 标准税赋：民心平稳，黄金税收与人口保持平衡发展。';
      }

      var saveBtn = modal.querySelector('#popSaveTaxBtn');
      if (saveBtn) {
        saveBtn.textContent = '应用税率 (' + taxVal + '%)';
      }
    }

    function refreshModal() {
      var s = Core.state || {};
      var r = s.resources || {};
      var civ = Core.civilianPopulation();
      var cap = Core.populationCapacity();
      var effCap = Core.effectiveCapacity();
      var growth = Core.populationGrowthPerHour();
      var morale = Core.morale();
      var resent = Core.resentment();
      var curTax = Core.tax();

      modal.querySelector('#popCivilianVal').textContent = G.fmt(civ);
      modal.querySelector('#popCapVal').textContent = G.fmt(cap);
      modal.querySelector('#popEffCapVal').textContent = G.fmt(effCap);
      modal.querySelector('#popGrowthVal').textContent = '+' + G.fmt(growth) + '/h';

      // Morale
      modal.querySelector('#popMoraleNum').textContent = morale;
      modal.querySelector('#popMoraleFill').style.width = Math.min(100, Math.max(0, morale)) + '%';
      var moraleBadge = modal.querySelector('#popMoraleBadge');
      if (morale >= 80) {
        moraleBadge.className = 'pop-status-badge badge-high';
        moraleBadge.textContent = '民心归附';
      } else if (morale >= 60) {
        moraleBadge.className = 'pop-status-badge badge-mid';
        moraleBadge.textContent = '安居乐业';
      } else if (morale >= 40) {
        moraleBadge.className = 'pop-status-badge badge-warn';
        moraleBadge.textContent = '民有怨言';
      } else {
        moraleBadge.className = 'pop-status-badge badge-danger';
        moraleBadge.textContent = '民不聊生';
      }

      // Resentment
      modal.querySelector('#popResentNum').textContent = resent;
      modal.querySelector('#popResentFill').style.width = Math.min(100, Math.max(0, resent)) + '%';
      var resentBadge = modal.querySelector('#popResentBadge');
      if (resent <= 0) {
        resentBadge.className = 'pop-status-badge resentment-badge badge-calm';
        resentBadge.textContent = '风平浪静';
      } else if (resent <= 30) {
        resentBadge.className = 'pop-status-badge resentment-badge badge-warn';
        resentBadge.textContent = '暗流涌动';
      } else if (resent <= 60) {
        resentBadge.className = 'pop-status-badge resentment-badge badge-danger';
        resentBadge.textContent = '民怨沸腾';
      } else {
        resentBadge.className = 'pop-status-badge resentment-badge badge-rebel';
        resentBadge.textContent = '暴动在即';
      }

      // Tax
      modal.querySelector('#popCurTaxText').textContent = curTax + '%';
      var slider = modal.querySelector('#popTaxSlider');
      if (slider && !slider._userInteracting) {
        slider.value = curTax;
        updateTaxPreview(curTax);
      }

      // Costs
      var goldCost = Math.max(1000, Math.min(10000, civ * 2));
      var currentGold = r.gold || 0;
      var currentDiamond = r.diamond || 0;
      modal.querySelector('#appeaseGoldCost').textContent = G.fmt(goldCost);
      modal.querySelector('#appeaseGoldRemain').textContent = '(余: ' + G.fmt(currentGold) + ')';
      modal.querySelector('#appeaseDiamondRemain').textContent = '(余: ' + G.fmt(currentDiamond) + ')';

      var goldBtn = modal.querySelector('#popAppeaseGoldBtn');
      if (currentGold < goldCost) {
        goldBtn.disabled = true;
        goldBtn.classList.add('disabled');
      } else {
        goldBtn.disabled = false;
        goldBtn.classList.remove('disabled');
      }

      var diaBtn = modal.querySelector('#popAppeaseDiamondBtn');
      if (currentDiamond < 20) {
        diaBtn.disabled = true;
        diaBtn.classList.add('disabled');
      } else {
        diaBtn.disabled = false;
        diaBtn.classList.remove('disabled');
      }
    }

    var slider = modal.querySelector('#popTaxSlider');
    slider.oninput = function () {
      slider._userInteracting = true;
      updateTaxPreview(parseInt(this.value, 10) || 0);
    };
    slider.onchange = function () {
      slider._userInteracting = false;
    };

    modal.querySelector('#popSaveTaxBtn').onclick = function () {
      var val = parseInt(slider.value, 10) || 0;
      var btn = this;
      btn.disabled = true;
      G.API.setTax(val).then(function () {
        G.toast('税率已成功设置为 ' + val + '%');
        if (Core.route === 'home') Core.render();
        refreshModal();
      }).catch(function (err) {
        G.toast(err && err.message ? err.message : '设置税率失败');
      }).finally(function () {
        btn.disabled = false;
      });
    };

    modal.querySelector('#popAppeaseGoldBtn').onclick = function () {
      var btn = this;
      btn.disabled = true;
      G.API.appease('gold').then(function (res) {
        G.toast(res && res.message ? res.message : '安抚民心成功！');
        if (Core.route === 'home') Core.render();
        refreshModal();
      }).catch(function (err) {
        G.toast(err && err.message ? err.message : '安抚失败');
      }).finally(function () {
        btn.disabled = false;
      });
    };

    modal.querySelector('#popAppeaseDiamondBtn').onclick = function () {
      var btn = this;
      btn.disabled = true;
      G.API.appease('diamond').then(function (res) {
        G.toast(res && res.message ? res.message : '特赦与犒赏成功！');
        if (Core.route === 'home') Core.render();
        refreshModal();
      }).catch(function (err) {
        G.toast(err && err.message ? err.message : '特赦失败');
      }).finally(function () {
        btn.disabled = false;
      });
    };

    modal.querySelector('#closePopDetail').onclick = function () { modal.remove(); };
    modal.addEventListener('click', function (e) { if (e.target === modal) modal.remove(); });

    refreshModal();
  }

  Core.views.home = function (v) {
    var s = Core.state;
    var r = s.resources;
    var mayor = Core.getOfficerByRole('mayor');
    var cmd = Core.getOfficerByRole('commander');
    var marches = s.world.marches || [];
    var incoming = s.world.incoming || [];
    var alertCount = marches.length + incoming.length;
    var reportsCount = (s.reports || []).length;

    var cityName = s.player.cityName || '新城市';

    var h = '';

    h += '<div class="city-head">';
    h += '<div class="city-row">';
    h += '<div class="city-cell"><span class="city-label">城市</span><b>' + G.escapeHtml(cityName) + '</b><span class="city-edit" onclick="Game.Main.toggleEditCity()">✎</span></div>';
    var cs = Core.cityStatusText();
    var cityPos = s.world.cityPos || s.world.pos;
    h += '<div class="city-cell city-coord"><span class="city-label">坐标</span>(' + cityPos.x + ',' + cityPos.y + ')</div>';
    h += '<span class="city-status-tag" style="color:' + cs.color + '">' + cs.text + '</span>';
    h += '</div>';
    h += '<div id="editCityBox" class="edit-profile-box" style="display:none">';
    h += '<div class="edit-row"><label>城市名</label><input id="epCityName" class="qty" style="width:100%" maxlength="12" value="' + G.escapeHtml(s.player.cityName || '新城市') + '" placeholder="留空则用默认名称"></div>';
    h += '<div class="btn-row" style="margin-top:4px"><button class="btn ok sm" onclick="Game.Main.saveCity()">保存</button><button class="btn sm" onclick="Game.Main.toggleEditCity()">取消</button></div>';
    h += '</div>';
    h += '</div>';

    if (alertCount > 0) {
      h += '<div class="home-alert" onclick="Game.go(\'alerts\')">⚔ 军情警讯 ' + alertCount + ' 起 (行军' + marches.length + '/来袭' + incoming.length + ') ></div>';
    }
    if (reportsCount > 0) {
      h += '<div class="home-alert rep" onclick="Game.go(\'reports\')">📋 战报 ' + reportsCount + ' 条 ></div>';
    }

    var netFood = Core.produceOf('farm') - Core.foodPerHour();
    var netSteel = Core.produceOf('refinery');
    var netOil = Core.produceOf('oilfield');
    var netRare = Core.produceOf('raremine');
    var goldRate = Math.floor(Core.civilianPopulation() * (s.tax / 100) * (1 + (mayor ? mayor.knowledge / 100 : 0)) * 2);
    var cap = Core.capacity();
    var caps = { food: cap.food, steel: cap.steel, oil: cap.oil, rare: cap.rare, gold: 999999 };
    var nets = { food: netFood, steel: netSteel, oil: netOil, rare: netRare, gold: goldRate };

    // 军官将领卡片（展示等级最高的1-3个军官，点击跳转参谋部军官管理）
    h += renderOfficerSummaryCard();

    // 军队详情（活动与任务块已迁移到顶部菜单"任务"页内）
    h += '<div class="zone-head"><span class="zone-title">🪖 军队详情</span><span class="zone-sub">带兵上限 ' + G.fmt(Core.armyCap()) + '</span></div>';
    h += '<div class="army-summary" onclick="Game.go(\'army\')">';
    h += renderArmySummaryList();
    h += '</div>';
    h += '<div class="army-summary-foot" onclick="Game.go(\'army\')">';
    var totArmy = 0;
    var sArmy = (Core.state && Core.state.army) || {};
    for (var ak in sArmy) totArmy += sArmy[ak] || 0;
    var cmd2 = Core.getOfficerByRole('commander');
    h += '总兵力 <b style="color:var(--accent)">' + G.fmt(totArmy) + '</b> / ' + G.fmt(Core.armyCap());
    h += '  ·  指挥官: <b>' + (cmd2 ? G.escapeHtml(cmd2.name) : '未任命') + '</b>';
    h += '  <span class="army-go">详情 ></span>';
    h += '</div>';

    h += '<div class="zone-head">资源</div>';
    h += '<div class="res-grid">';
    var resKeys = ['food', 'steel', 'oil', 'rare', 'gold'];
    for (var ri = 0; ri < resKeys.length; ri++) {
      var rk = resKeys[ri];
      var rinfo = D.resources[rk];
      var cur = r[rk] || 0;
      var maxR = caps[rk] || 999999;
      var net = nets[rk] || 0;
      var sign = net >= 0 ? '+' : '';
      h += '<div class="res-card" role="button" tabindex="0" onclick="Game.Main.showResourceDetail(\'' + rk + '\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.Main.showResourceDetail(\'' + rk + '\');event.preventDefault();}">';
      var iconHtml = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(rinfo.icon)
        ? '<img class="res-icon-img" src="' + rinfo.icon + '" alt="' + rinfo.name + '"/>'
        : '<span class="res-icon ri-' + rk + '">' + rinfo.icon + '</span>';
      h += '<div class="res-summary">' + iconHtml + '<span class="res-name">' + rinfo.name + '：</span><span class="res-main"><span class="res-cur">' + G.fmt(cur) + '</span></span><span class="res-rate' + (net < 0 ? ' neg' : '') + '">' + sign + G.fmt(net) + '/h</span></div>';
      h += '</div>';
    }
    var popIcon = '<img class="res-icon-img" src="img/population.svg" alt="人口"/>';
    var curMorale = Core.morale();
    var curResent = Core.resentment();
    h += '<div class="res-card" role="button" tabindex="0" onclick="Game.Main.showPopulationDetail()" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.Main.showPopulationDetail();event.preventDefault();}">';
    h += '<div class="res-summary">' + popIcon + '<span class="res-name">平民：</span><span class="res-main"><span class="res-cur">' + G.fmt(Core.civilianPopulation()) + '</span><span class="res-slash">/</span><span class="res-max">' + G.fmt(Core.populationCapacity()) + '</span></span><span class="res-rate">+' + G.fmt(Core.populationGrowthPerHour()) + '/h</span></div>';
    h += '<div class="d">可征召 ' + G.fmt(Core.popFree()) + ' · 民心 ' + curMorale + (curResent > 0 ? ' <span style="color:#d9534f">(怨' + curResent + ')</span>' : '') + '</div>';
    h += '</div>';
    h += '</div>';

    // 代办事项 + 战情速递
    h += (G.Task ? G.Task.renderTodos(s) : '');
    h += (G.Task ? G.Task.renderAlerts(s) : '');

    // —— 世界聊天频道 ——
    h += '<div class="zone-head"><span class="zone-title">📡 世界频道</span><span class="zone-sub">实时战况</span></div>';
    h += '<div class="chat-box" id="worldChatBox">';
    var msgs = (G.Chat && G.Chat.recent) ? G.Chat.recent(15) : [];
    for (var mi = 0; mi < msgs.length; mi++) {
      var m = msgs[mi];
      var safeName = String(m.username == null ? '玩家' : m.username)
        .replace(/[&<>"']/g, function (c) {
          return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
      var safeContent = String(m.content == null ? '' : m.content)
        .replace(/[&<>"']/g, function (c) {
          return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
      h += '<div class="chat-msg" data-type="' + m.type + '">' +
        '<span class="chat-time">' + G.fmtChatTime(m.ts) + '</span>' +
        '<span class="chat-text">🗣️ ' + safeName + ': ' + safeContent + '</span>' +
        '</div>';
    }
    h += '</div>';

    h += '<div class="chat-input-bar">';
    h += '<input class="chat-input" id="worldChatInput" type="text" maxlength="80" placeholder="向世界频道发言 (最多 80 字)" autocomplete="off" onkeydown="if(event.key===&quot;Enter&quot;){Game.Main.sendChat();}"/>';
    h += '<button class="chat-send" onclick="Game.Main.sendChat()">发送</button>';
    h += '</div>';

    // 今日战果
    h += (G.Task ? G.Task.renderTodayStats(s) : '');

    v.innerHTML = h;
  };

  Core.views.settings = function (v) {
    var s = Core.state;
    var h = '';
    h += '<div class="title">- 设置 -</div>';

    h += '<div class="zone-head">游戏设置</div>';
    h += '<div class="panel">';
    h += '<div class="btn-row" style="margin-bottom:6px">';
    h += '<span style="flex:1;font-size:14px">手动存档</span>';
    h += '<button class="btn sm ok" onclick="Game.save();Game.toast(\'已存档\')">存档</button>';
    h += '</div>';
    h += '<div class="btn-row" style="margin-bottom:6px">';
    h += '<span style="flex:1;font-size:14px">云同步</span>';
    h += '<button class="btn sm" onclick="Game.Main.cloudSync()">上传云端</button>';
    h += '</div>';
    h += '<div class="btn-row">';
    h += '<span style="flex:1;font-size:14px;color:var(--danger)">重置游戏</span>';
    h += '<button class="btn sm warn" onclick="Game.Main.confirmReset()">重置</button>';
    h += '</div>';
    h += '</div>';

    h += '<div class="zone-head">账号信息</div>';
    h += '<div class="panel">';
    if (G.API && G.API.isLoggedIn()) {
      h += '<div class="d">登录账号: <b>' + G.escapeHtml(G.API.getUsername() || '未知') + '</b></div>';
      h += '<div class="d">同步状态: 已登录 (云端可同步)</div>';
      h += '<div class="btn-row" style="margin-top:6px">';
      h += '<button class="btn sm" onclick="Game.Main.logout()">切换账号</button>';
      h += '</div>';
    } else if (G.Main && G.Main.guestMode) {
      h += '<div class="d">当前模式: <b style="color:var(--muted)">游客模式</b></div>';
      h += '<div class="d">存档仅保存在本地,换设备将丢失</div>';
      h += '<div class="btn-row" style="margin-top:6px">';
      h += '<button class="btn sm ok" onclick="Game.go(\'login\')">登录/注册账号</button>';
      h += '</div>';
    } else {
      h += '<div class="d">未登录</div>';
      h += '<div class="btn-row" style="margin-top:6px">';
      h += '<button class="btn sm ok" onclick="Game.go(\'login\')">登录</button>';
      h += '</div>';
    }
    h += '</div>';

    h += '<div class="zone-head">关于</div>';
    h += '<div class="panel">';
    h += '<div class="d">山河远征 - 策略战争游戏</div>';
    h += '<div class="d">版本: 1.0.0</div>';
    h += '</div>';

    if (G.API && G.API.isLoggedIn()) {
      h += '<div style="margin-top:16px">';
      h += '<button class="btn warn" style="width:100%;padding:12px;font-size:16px;color:#fff;background:var(--danger);border:0;border-radius:8px" onclick="Game.Main.logout()">退出登录</button>';
      h += '</div>';

      h += '<div class="zone-head" style="margin-top:18px;color:var(--danger)">危险操作</div>';
      h += '<div class="panel" style="border-left:3px solid var(--danger)">';
      h += '<div class="d" style="color:var(--danger)">注销账号</div>';
      h += '<div class="d" style="font-size:12px;color:var(--muted)">';
      h += '注销后账号将进入 7 天恢复期，期间重新登录即可恢复；超出恢复期后将永久清理所有游戏数据，且该用户名不可重新注册。';
      h += '</div>';
      h += '<div class="btn-row" style="margin-top:6px">';
      h += '<button class="btn sm warn2" style="background:#b03020;color:#fff;border:0" onclick="Game.Main.openDisableAccount()">注销账号</button>';
      h += '</div>';
      h += '</div>';
    }

    h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
    v.innerHTML = h;
  };

  var Main = {
    guestMode: false,

    showResourceDetail: function (key) {
      var s = Core.state || {};
      var r = s.resources || {};
      var mayor = Core.getOfficerByRole('mayor');
      var cap = Core.capacity();
      var foodProduction = Core.produceOf('farm');
      var foodConsumption = Core.foodPerHour();
      var rates = {
        food: foodProduction - foodConsumption,
        steel: Core.produceOf('refinery'),
        oil: Core.produceOf('oilfield'),
        rare: Core.produceOf('raremine'),
        gold: Math.floor(Core.civilianPopulation() * (s.tax / 100) * (1 + (mayor ? mayor.knowledge / 100 : 0)) * 2)
      };
      var caps = { food: cap.food, steel: cap.steel, oil: cap.oil, rare: cap.rare, gold: 999999 };
      var info = D.resources[key];
      if (!info) return;
      showResourceDetail(key, info.name, info.icon, r[key] || 0, caps[key] || 999999, rates[key] || 0, foodProduction, foodConsumption);
    },

    showPopulationDetail: function () {
      showPopulationDetailModal();
    },

    showLoginMsg: function (msg, isError) {
      var el = document.getElementById('loginMsg');
      if (!el) return;
      el.textContent = msg;
      el.style.color = isError ? '#ff5a5a' : '#7fc4ff';
    },

    doLogin: function () {
      var self = this;
      var u = (document.getElementById('loginUser').value || '').trim();
      var p = (document.getElementById('loginPass').value || '').trim();
      if (!u || !p) { this.showLoginMsg('请输入用户名和密码', true); return; }
      this.showLoginMsg('登录中...', false);
      G.API.login(u, p).then(function () {
        self.showLoginMsg('登录成功,加载游戏...', false);
        self.guestMode = false;
        return self.startGame();
      }).catch(function (err) {
        self.showLoginMsg(err && err.message ? err.message : '登录失败', true);
      });
    },

    doRegister: function () {
      var self = this;
      var u = (document.getElementById('loginUser').value || '').trim();
      var p = (document.getElementById('loginPass').value || '').trim();
      if (!u || !p) { this.showLoginMsg('请输入用户名和密码', true); return; }
      if (u.length < 3) { this.showLoginMsg('用户名至少3位', true); return; }
      if (p.length < 6) { this.showLoginMsg('密码至少6位', true); return; }
      this.showLoginMsg('注册中...', false);
      G.API.register(u, p).then(function () {
        self.showLoginMsg('注册成功,加载游戏...', false);
        self.guestMode = false;
        return self.startGame();
      }).catch(function (err) {
        self.showLoginMsg(err && err.message ? err.message : '注册失败', true);
      });
    },

    guestPlay: function () {
      var self = this;
      this.showLoginMsg('创建游客账号...', false);
      G.API.createGuest().then(function () {
        self.guestMode = true;
        return self.startGame();
      }).catch(function (err) {
        self.showLoginMsg(err && err.message ? err.message : '服务器不可用', true);
      });
    },

    startGame: function () {
      var self = this;
      // 通过 API 加载游戏状态，整体替换 G.state（不与旧状态合并）
      return G.load().then(function (state) {
        G.state = state;
        Core.state = G.state;
        Core.init();
        Main.renderNavBar();
        Core.route = 'home';
        Core.render();
        // Connect WebSocket for real-time updates (skip guest mode - no valid JWT)
        if (G.WS && !Main.guestMode) {
          G.WS.connect();
        }
      }).catch(function (err) {
        if (G.toast) G.toast('加载游戏状态失败');
        console.warn('[Main] startGame load failed:', err);
      });
    },

    logout: function () {
      if (!confirm('退出登录?')) return;
      if (G.WS) G.WS.disconnect();
      G.API.logout();
      G.state = null;
      Core.state = null;
      Core.route = 'login';
      Core.render();
    },

    toggleEditCity: function () {
      var box = document.getElementById('editCityBox');
      if (!box) return;
      box.style.display = box.style.display === 'none' ? 'block' : 'none';
    },

    saveCity: function () {
      var s = Core.state;
      var cityEl = document.getElementById('epCityName');
      var cityName = cityEl ? cityEl.value.trim() : '';
      var safeRe = /^[A-Za-z0-9_\u4e00-\u9fa5·\s]{1,12}$/;
      if (cityName && !safeRe.test(cityName)) { G.toast('城市名仅限中英文/数字/下划线，最多12字'); return; }
      G.API.setCityName(cityName).then(function () {
        G.toast('城市名已保存');
        var box = document.getElementById('editCityBox');
        if (box) box.style.display = 'none';
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '城市名保存失败');
      });
    },

    toggleEditCommander: function () {
      var box = document.getElementById('editCommanderBox');
      if (!box) return;
      box.style.display = box.style.display === 'none' ? 'block' : 'none';
    },

    saveCommander: function () {
      var s = Core.state;
      var cmdEl = document.getElementById('epCommander');
      var cmdName = cmdEl ? cmdEl.value.trim() : '';
      if (!cmdName) { G.toast('统帅名不能为空'); return; }
      if (cmdName.length > 8) { G.toast('统帅名最多8个字'); return; }
      if (!/^[A-Za-z0-9_\u4e00-\u9fa5·]{1,8}$/.test(cmdName)) { G.toast('统帅名仅限中英文/数字/下划线'); return; }
      s.player.name = cmdName;
      G.toast('统帅名已保存');
      document.getElementById('editCommanderBox').style.display = 'none';
      Core.render();
    },

    toggleEditDesc: function () {
      var box = document.getElementById('editDescBox');
      if (!box) return;
      box.style.display = box.style.display === 'none' ? 'block' : 'none';
    },

    saveDesc: function () {
      var s = Core.state;
      var descEl = document.getElementById('epCityDesc');
      var desc = descEl ? descEl.value.trim() : '';
      if (desc.length > 30) { G.toast('城市简介最多30字'); return; }
      s.cityDesc = desc;
      G.toast('城市简介已保存');
      document.getElementById('editDescBox').style.display = 'none';
      Core.render();
    },

    confirmReset: function () {
      if (!confirm('确认重置存档?所有进度将丢失!')) return;
      G.toast('正在重置...');
      G.reset().then(function () {
        Core.history = [];
        Core.route = 'home';
        G.toast('已重置');
        Core.render();
      }).catch(function (err) {
        G.toast(err && err.message ? err.message : '重置失败');
      });
    },

    // ============================================================
    //  注销账号：二次确认弹窗 + 提交后清空本地会话
    // ============================================================

    openDisableAccount: function () {
      if (!G.API || !G.API.isLoggedIn()) {
        G.toast('当前未登录账号，无需注销');
        return;
      }
      if (G.Main && G.Main.guestMode) {
        G.toast('游客模式无账号，无需注销');
        return;
      }
      var existing = document.getElementById('disableAccountModal');
      if (existing) existing.remove();

      var mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.id = 'disableAccountModal';
      mask.innerHTML =
        '<div class="modal-card" style="max-width:380px">' +
          '<div class="modal-title" style="color:var(--danger)">⚠ 注销账号</div>' +
          '<div class="modal-body">' +
            '<div style="font-size:13px;line-height:1.6;color:var(--ink)">' +
              '注销后账号将进入 <b>7 天恢复期</b>。<br>' +
              '• 宽限期内重新登录可自动恢复；<br>' +
              '• 超出 7 天将永久清理账号数据，且该用户名不可再用；<br>' +
              '• 注销会立即撤销当前会话。' +
            '</div>' +
            '<div class="edit-row" style="margin-top:10px"><label>当前密码</label>' +
              '<input id="disablePassword" class="qty" style="width:100%" type="password" maxlength="64" placeholder="请输入当前登录密码">' +
            '</div>' +
            '<div class="edit-row"><label>确认操作</label>' +
              '<input id="disableConfirm" class="qty" style="width:100%" maxlength="16" placeholder="请输入 确认注销">' +
            '</div>' +
            '<div id="disableMsg" style="margin-top:6px;font-size:12px;color:var(--muted)">输入"确认注销"以继续</div>' +
          '</div>' +
          '<div class="btn-row" style="margin-top:10px">' +
            '<button class="btn sm" onclick="Game.Main.closeDisableAccount()">取消</button>' +
            '<button class="btn sm warn2" style="background:#b03020;color:#fff" onclick="Game.Main.submitDisableAccount()">确认注销</button>' +
          '</div>' +
        '</div>';
      document.body.appendChild(mask);
      setTimeout(function () {
        var pwd = document.getElementById('disablePassword');
        if (pwd) pwd.focus();
      }, 50);
    },

    closeDisableAccount: function () {
      var mask = document.getElementById('disableAccountModal');
      if (mask) mask.remove();
    },

    submitDisableAccount: function () {
      var pwdEl = document.getElementById('disablePassword');
      var confEl = document.getElementById('disableConfirm');
      var msgEl = document.getElementById('disableMsg');
      var password = pwdEl ? pwdEl.value : '';
      var confirmText = confEl ? confEl.value.trim() : '';
      if (!password) {
        if (msgEl) { msgEl.textContent = '请输入当前密码'; msgEl.style.color = 'var(--danger)'; }
        return;
      }
      if (confirmText !== '确认注销') {
        if (msgEl) { msgEl.textContent = '请输入"确认注销"以继续'; msgEl.style.color = 'var(--danger)'; }
        return;
      }

      var self = this;
      G.API.disableAccount(password, confirmText).then(function (data) {
        self.closeDisableAccount();
        // 关闭 WS、清空状态、回到登录页
        if (G.WS) G.WS.disconnect();
        G.state = null;
        Core.state = null;
        self.guestMode = false;
        Core.history = [];
        Core.route = 'login';
        Core.render();
        var cooldown = data && data.cooldownDays ? data.cooldownDays : 7;
        G.toast('账号已注销，' + cooldown + ' 天内登录可恢复');
      }).catch(function (err) {
        if (msgEl) {
          msgEl.textContent = (err && err.message) ? err.message : '注销失败';
          msgEl.style.color = 'var(--danger)';
        }
      });
    },

    sendChat: function () {
      var el = document.getElementById('worldChatInput');
      if (!el) return;
      var text = el.value;
      if (!G.Chat || typeof G.Chat.send !== 'function') {
        G.toast('世界频道暂未加载');
        return;
      }
      G.Chat.send(text).then(function () {
        el.value = '';
      }).catch(function (err) {
        G.toast(err && err.message ? err.message : '发送失败');
      });
    },

    cloudSync: function () {
      if (!G.API || !G.API.isLoggedIn()) {
        G.toast('请先登录账号');
        return;
      }
      G.toast('正在同步...');
      // 状态已由后端管理，"同步"即从后端重新拉取最新状态
      G.API.getGameState(true).then(function (state) {
        G.state = state;
        if (G.Core) G.Core.state = G.state;
        G.toast('同步成功');
        if (G.Core) G.Core.render();
      }).catch(function () {
        G.toast('同步失败,请稍后重试');
      });
    },

    renderNavBar: function () {
      var bar = G.$('navbar');
      if (!bar) return;
      bar.innerHTML = navBar();
    },

    // ================================================================
    // 新手引导
    // ================================================================
    shouldShowTutorial: function (state) {
      try {
        // 跳过状态以服务端玩家数据为准，刷新或更换设备后仍然有效。
        if (state && state.player && state.player.tutorialDismissed) return false;
        // 资源仍处于初始范围(每种 <= 12万) 才视为新玩家
        var r = (state && state.resources) || {};
        var max = Math.max(r.food || 0, r.steel || 0, r.oil || 0, r.rare || 0);
        return max <= 120000;
      } catch (e) { return false; }
    },

    showTutorial: function () {
      var modal = document.createElement('div');
      modal.className = 'modal-mask';
      modal.innerHTML =
        '<div class="modal-card" style="max-width:520px">' +
          '<div class="modal-title">欢迎来到山河远征</div>' +
          '<div class="modal-body" style="line-height:1.7;font-size:14px">' +
            '<p>1. <b>资源区</b>([1]): 升级农场/炼油厂/钢/稀矿,提升每小时产量。</p>' +
            '<p>2. <b>军事区</b>([2]): 建造兵营、兵工厂、解锁高级兵种。</p>' +
            '<p>3. <b>军官学院</b>: 消耗 <b>200 黄金/次</b> 刷新候选,招幕将领出战。</p>' +
            '<p>4. <b>地图</b>([6]): 扫描周围资源/流寇/玩家主城。宣战前需 6 小时备战 + 24 小时战争。</p>' +
            '<p>5. <b>军饷</b>: 系统按<b>每小时</b>从黄金中扣除军官薪资总额,金币不足时武将忠诚度会下降,请保持税源。</p>' +
          '</div>' +
          '<div class="modal-foot">' +
            '<button class="btn ok" id="tutOk">明白了</button>' +
            '<button class="btn" id="tutDismiss">不再提示</button>' +
          '</div>' +
        '</div>';
      document.body.appendChild(modal);
      var close = function () {
        if (modal.parentNode) modal.parentNode.removeChild(modal);
      };
      modal.querySelector('#tutOk').onclick = close;
      modal.querySelector('#tutDismiss').onclick = function () {
        var button = modal.querySelector('#tutDismiss');
        button.disabled = true;
        if (!G.API || !G.API.dismissTutorial) {
          G.toast('引导状态保存失败，请稍后重试');
          button.disabled = false;
          return;
        }
        G.API.dismissTutorial().then(function () {
          if (G.state && G.state.player) G.state.player.tutorialDismissed = true;
          close();
        }).catch(function () {
          G.toast('引导状态保存失败，请稍后重试');
          button.disabled = false;
        });
      };
    }
  };

  G.Main = Main;

  function boot() {
    if (G.API.isLoggedIn()) {
      // 已登录：通过 API 异步加载游戏状态后再初始化
      G.load().then(function (state) {
        G.state = state;
        Core.state = G.state;
        Core.init();
        Main.renderNavBar();
        Core.route = 'home';
        Core.render();
        // 主线任务 + 新手引导系统
        if (G.MainQuest) G.MainQuest.init();
        // 加载世界频道历史，实时消息由 WebSocket 广播
        if (G.Chat) G.Chat.loadHistory();
        // 初始化邮件种子
        if (G.Mail) G.Mail.seed();
        // 新手引导：仅对首次登录且资源等级很低的玩家展示一次
        if (Main.shouldShowTutorial(state)) {
          setTimeout(function () { Main.showTutorial(); }, 600);
        }
        // 初始化每日任务；登录进度照常计入，但页面刷新不弹任务提示。
        if (G.Task && G.Task.Quests) {
          G.Task.Quests.init();
          G.Task.Quests.onEvent('login', 1, true);
        }
        // Connect WebSocket for real-time updates (skip guest tokens - no valid JWT)
        var token = G.API.getToken();
        if (G.WS && token && token.indexOf('guest_') !== 0) {
          G.WS.connect();
        }
      }).catch(function (err) {
        console.warn('[Main] boot load failed:', err);
        Core.bindKeys();
        Core.route = 'login';
        Core.render();
      });
    } else {
      Core.bindKeys();
      Core.route = 'login';
      Core.render();
    }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})(window.Game);
