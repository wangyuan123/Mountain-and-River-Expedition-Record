/* global window, document */
window.Game = window.Game || {};
(function (G) {
  'use strict';
  var Core = G.Core;
  var D = G.DATA;

  // 兵种实体图标映射 (使用军事实体 SVG 图标)
  var UNIT_ICON = {
    infantry: 'img/units/infantry.svg',
    motor: 'img/units/motor.svg',
    truck: 'img/units/truck.svg',
    armored: 'img/units/armored.svg',
    ltank: 'img/units/ltank.svg',
    htank: 'img/units/htank.svg',
    assault: 'img/units/assault.svg',
    rocket: 'img/units/rocket.svg',
    scout: 'img/units/scout.svg',
    special: 'img/units/special.svg',
    fighter: 'img/units/fighter.svg',
    bomber: 'img/units/bomber.svg',
    transport: 'img/units/transport.svg',
    destroyer: 'img/units/destroyer.svg',
    sub: 'img/units/sub.svg',
    battleship: 'img/units/battleship.svg',
    carrier: 'img/units/carrier.svg'
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
      return '<div class="army-summary-empty">暂无可用部队，前往 <a onclick="Game.go(\'army\')">军队</a> 征召</div>';
    }
    // 数量从大到小排序，前 8 个优先
    arr.sort(function (a, b) { return b.cnt - a.cnt; });
    var top = arr.slice(0, 8);
    var html = '';
    for (var i = 0; i < top.length; i++) {
      var u = top[i];
      var rawIcon = UNIT_ICON[u.id] || '⚔';
      var iconHtml = /\.svg$|\.png$|\.jpg$|\.webp$/i.test(rawIcon)
        ? '<img class="army-summary-icon-img" src="' + rawIcon + '" alt="' + G.escapeHtml(u.name) + '"/>'
        : rawIcon;
      html += '<div class="army-summary-item">'
            + '<span class="army-summary-icon">' + iconHtml + '</span>'
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
        } else if (o.role === 'march') {
          roleTag = '<span class="home-officer-role">行军中</span>';
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
    h += '<div class="title">- 烽原战策 -</div>';
    h += '<div class="desc">请登录或注册，游戏进度由服务器自动保存</div>';
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
    { key: '1', label: '资源', route: 'buildRes' },
    { key: '2', label: '军事', route: 'buildArmy' },
    { key: '3', label: '军队', route: 'army' },
    { key: '4', label: '科技', route: 'tech' },
    { key: '5', label: '地图', route: 'world' },
    { key: '6', label: '军情', route: 'alerts' },
    { key: '7', label: '战报', route: 'reports' },
    { key: '8', label: '邮件', route: 'mail' },
    { key: '9', label: '任务', route: 'mainQuest' },
    { key: '0', label: '军团', route: 'guild' },
    { key: '·', label: '仓库', route: 'depot' }
  ];

  function navBar() {
    var s = Core.state;
    if (!s || !s.world) return '';
    var hasIncoming = s.world.incoming && s.world.incoming.length > 0;
    var items = [];
    var homeActive = Core.route === 'home' ? ' active' : '';
    items.push('<div class="navitem home-tab' + homeActive + '" data-route="home" onclick="Game.go(\'home\')"><span class="navlabel">首页</span></div>');
    if (G.Cities) items.push(G.Cities.nav());
    for (var i = 0; i < NAV_ITEMS.length; i++) {
      var it = NAV_ITEMS[i];
      var action = 'Game.go(\'' + it.route + '\')';
      var active = (Core.route === it.route || (Core.route === 'wounded' && it.route === 'army')) ? ' active' : '';
      var alertCls = (it.route === 'alerts' && hasIncoming) ? ' alert' : '';
      var mailUnread = (it.route === 'mail' && G.Mail && G.Mail.unread && G.Mail.unread() > 0) ? G.Mail.unread() : 0;
      var mailBadge = mailUnread ? '<span class="nav-badge">' + mailUnread + '</span>' : '';
      var reportsUnread = (it.route === 'reports' && G.Battle && G.Battle.unreadCount && G.Battle.unreadCount() > 0) ? G.Battle.unreadCount() : 0;
      var reportsBadge = reportsUnread ? '<span class="nav-badge">' + (reportsUnread > 99 ? '99+' : reportsUnread) + '</span>' : '';
      // 主线任务红点
      var questBadge = '';
      if (it.route === 'mainQuest' && G.MainQuest && G.MainQuest.hasUnclaimed && G.MainQuest.hasUnclaimed()) {
        questBadge = '<span class="nav-badge alert-dot">!</span>';
      }
      items.push('<div class="navitem' + active + alertCls + '" data-route="' + it.route + '" onclick="' + action + '"><span class="navnum">[' + it.key + ']</span><span class="navlabel">' + (it.icon ? '<img class="nav-icon" src="' + it.icon + '" alt="' + it.label + '"/>' : it.label) + '</span>' + mailBadge + reportsBadge + questBadge + '</div>');
    }
    // 每页两排七列，超过十四个入口才分页。
    var pageSize = 14;
    var pages = [];
    var dots = [];
    for (var page = 0; page < Math.ceil(items.length / pageSize); page++) {
      pages.push('<div class="nav-page" role="group" aria-label="第' + (page + 1) + '组导航">' + items.slice(page * pageSize, (page + 1) * pageSize).join('') + '</div>');
      dots.push('<button type="button" class="nav-page-dot" data-nav-page="' + page + '" aria-label="切换到第' + (page + 1) + '组导航"></button>');
    }
    return '<div class="nav-viewport" aria-label="' + (pages.length > 1 ? '左右滑动查看更多导航' : '功能导航') + '">' + pages.join('') + '</div>' +
      (pages.length > 1 ? '<div class="nav-pages" aria-label="导航分页">' + dots.join('') + '</div>' : '');
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
    var titleIcon = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(icon)
      ? '<img class="res-icon-img" src="' + icon + '" alt="' + name + '" style="width:20px;height:20px;margin-right:6px;vertical-align:middle;display:inline-block;" />'
      : (icon ? '<span style="margin-right:6px;">' + icon + '</span>' : '');
    modal.innerHTML =
      '<div class="modal-card" style="max-width:380px">' +
        '<div class="modal-title" style="display:flex;align-items:center;justify-content:center;">' + titleIcon + name + '详情</div>' +
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
            '<div class="pop-stat-box">' +
              '<div class="pop-stat-head">' +
                '<span class="pop-stat-label">当前平民</span>' +
                '<button class="btn ok sm pop-call-btn" id="popRecruitQuickBtn" title="消耗人口动员令立即召集平民">召集</button>' +
              '</div>' +
              '<span class="pop-stat-val" id="popCivilianVal">-</span>' +
            '</div>' +
            '<div class="pop-stat-box"><span class="pop-stat-label">民居标称容量</span><span class="pop-stat-val" id="popCapVal">-</span></div>' +
            '<div class="pop-stat-box"><span class="pop-stat-label">民心容纳上限</span><span class="pop-stat-val highlight" id="popEffCapVal">-</span></div>' +
            '<div class="pop-stat-box"><span class="pop-stat-label">自然增长速度</span><span class="pop-stat-val positive" id="popGrowthVal">-</span></div>' +
          '</div>' +

          '<div class="pop-recruit-section">' +
            '<div class="pop-section-title">' +
              '<span>👥 召集人口与平民动员</span>' +
              '<span class="pop-recruit-badge" id="popOrderCountBadge">拥有动员令: 0 张</span>' +
            '</div>' +
            '<div class="pop-recruit-card">' +
              '<div class="pop-recruit-info">' +
                '<div class="pop-recruit-desc">消耗仓库中的【人口动员令】，立即自四方动员 <b class="positive">+500</b> 空闲平民进城（受民居容量限制）。</div>' +
              '</div>' +
              '<button class="btn ok pop-recruit-action-btn" id="popRecruitBtn">立即召集 (+500)</button>' +
            '</div>' +
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

      // Population Order
      var popOrders = (s.items && s.items.populationOrder) || 0;
      var badgeEl = modal.querySelector('#popOrderCountBadge');
      if (badgeEl) {
        badgeEl.textContent = '拥有动员令: ' + popOrders + ' 张';
        if (popOrders > 0) {
          badgeEl.className = 'pop-recruit-badge has-items';
        } else {
          badgeEl.className = 'pop-recruit-badge';
        }
      }
      var mainRecruitBtn = modal.querySelector('#popRecruitBtn');
      if (mainRecruitBtn) {
        if (popOrders > 0) {
          mainRecruitBtn.textContent = '立即召集 (+500)';
          mainRecruitBtn.className = 'btn ok pop-recruit-action-btn';
        } else {
          mainRecruitBtn.textContent = '获取动员令 (去商城)';
          mainRecruitBtn.className = 'btn sub pop-recruit-action-btn';
        }
      }
      var quickBtnEl = modal.querySelector('#popRecruitQuickBtn');
      if (quickBtnEl) {
        quickBtnEl.textContent = popOrders > 0 ? '召集(+500)' : '召集';
      }

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

    function handleRecruitPopulation() {
      var civ = Core.civilianPopulation();
      var cap = Core.populationCapacity();
      if (civ >= cap) {
        G.toast('民居容量已达上限 (' + G.fmt(civ) + '/' + G.fmt(cap) + ')，请先扩建民居！');
        return;
      }
      var s = Core.state || {};
      var items = s.items || {};
      var popOrders = items.populationOrder || 0;
      if (popOrders <= 0) {
        if (confirm('仓库中暂无【人口动员令】（每张使用可立即增加500空闲平民）。\n是否立即前往商城购买？')) {
          modal.remove();
          Game.go('shop');
        }
        return;
      }

      var quickBtn = modal.querySelector('#popRecruitQuickBtn');
      var mainBtn = modal.querySelector('#popRecruitBtn');
      if (quickBtn) quickBtn.disabled = true;
      if (mainBtn) mainBtn.disabled = true;

      G.API.depotUse('populationOrder', null, null).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '召集失败');
          if (resp.state) G.API.applyState(resp.state);
          return;
        }
        G.toast((resp && resp.message) || '👥 召集成功，空闲平民 +500！');
        if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
        if (Core.route === 'home') Core.render();
        refreshModal();
      }).catch(function (err) {
        G.toast((err && err.message) || '召集失败');
      }).finally(function () {
        if (quickBtn) quickBtn.disabled = false;
        if (mainBtn) mainBtn.disabled = false;
      });
    }

    var quickRecruitBtn = modal.querySelector('#popRecruitQuickBtn');
    if (quickRecruitBtn) quickRecruitBtn.onclick = handleRecruitPopulation;

    var mainRecruitBtn = modal.querySelector('#popRecruitBtn');
    if (mainRecruitBtn) mainRecruitBtn.onclick = handleRecruitPopulation;

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

    window.__refreshPopDetailModal = refreshModal;
    var closeModal = function () {
      window.__refreshPopDetailModal = null;
      modal.remove();
    };
    modal.querySelector('#closePopDetail').onclick = closeModal;
    modal.addEventListener('click', function (e) { if (e.target === modal) closeModal(); });

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
    var statusClass = cs.status ? ' ' + cs.status : ' peace';
    h += '<span class="city-status-tag' + statusClass + '" data-status="' + (cs.status || 'peace') + '">' + cs.text + '</span>';
    h += '</div>';
    h += '<div id="editCityBox" class="edit-profile-box" style="display:none">';
    h += '<div class="edit-row"><label>城市名</label><input id="epCityName" class="qty" style="width:100%" maxlength="12" value="' + G.escapeHtml(s.player.cityName || '新城市') + '" placeholder="留空则用默认名称"></div>';
    h += '<div class="btn-row" style="margin-top:4px"><button class="btn ok sm" onclick="Game.Main.saveCity()">保存</button><button class="btn sm" onclick="Game.Main.toggleEditCity()">取消</button></div>';
    h += '</div>';
    h += '</div>';

    h += '<div id="homeAlertsWrap">';
    if (alertCount > 0) {
      h += '<div class="home-alert" onclick="Game.go(\'alerts\')">⚔ 军情警讯 ' + alertCount + ' 起 (行军' + marches.length + '/来袭' + incoming.length + ') ></div>';
    }
    if (reportsCount > 0) {
      h += '<div class="home-alert rep" onclick="Game.go(\'reports\')">📋 战报 ' + reportsCount + ' 条 ></div>';
    }
    h += '</div>';

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

    // 军队总览（活动与任务块已迁移到顶部菜单"任务"页内）
    h += '<div class="zone-head"><span class="zone-title">🪖 军队总览</span><span class="zone-sub">带兵上限 ' + G.fmt(Core.armyCap()) + '</span></div>';
    h += '<div class="army-summary" onclick="Game.go(\'army\')">';
    h += renderArmySummaryList();
    h += '</div>';
    h += '<div class="army-summary-foot" onclick="Game.go(\'army\')">';
    var totArmy = 0;
    var sArmy = (Core.state && Core.state.army) || {};
    for (var ak in sArmy) totArmy += sArmy[ak] || 0;
    var cmd2 = Core.getOfficerByRole('commander');
    h += '总兵力 <b class="home-army-tot" style="color:var(--accent)">' + G.fmt(totArmy) + '</b> / ' + G.fmt(Core.armyCap());
    h += '  ·  指挥官: <b>' + (cmd2 ? G.escapeHtml(cmd2.name) : '未任命') + '</b>';
    h += '  <span class="army-go">详情 ></span>';
    h += '</div>';

    h += '<div class="zone-head"><span class="zone-title">资源</span></div>';
    h += '<div class="res-grid">';
    var resKeys = ['food', 'steel', 'oil', 'rare', 'gold'];
    for (var ri = 0; ri < resKeys.length; ri++) {
      var rk = resKeys[ri];
      var rinfo = D.resources[rk];
      var cur = r[rk] || 0;
      var maxR = caps[rk] || 999999;
      var net = nets[rk] || 0;
      var sign = net >= 0 ? '+' : '';
      h += '<div class="res-card" data-res-card="' + rk + '" role="button" tabindex="0" title="' + rinfo.name + ' 当前: ' + G.fmt(cur) + ' (' + sign + G.fmt(net) + '/h)" onclick="Game.Main.showResourceDetail(\'' + rk + '\')" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.Main.showResourceDetail(\'' + rk + '\');event.preventDefault();}">';
      var iconHtml = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(rinfo.icon)
        ? '<img class="res-icon-img" src="' + rinfo.icon + '" alt="' + rinfo.name + '"/>'
        : '<span class="res-icon ri-' + rk + '">' + rinfo.icon + '</span>';
      h += '<div class="res-summary">' + iconHtml + '<span class="res-name">' + rinfo.name + ':</span><span class="res-main"><span class="res-cur">' + G.fmt(cur) + '</span></span><span class="res-rate' + (net < 0 ? ' neg' : '') + '">' + sign + G.fmt(net) + '/h</span></div>';
      h += '</div>';
    }
    var popIcon = '<img class="res-icon-img" src="img/res-pop.svg" alt="平民"/>';
    var curMorale = Core.morale();
    var curResent = Core.resentment();
    h += '<div class="res-card" data-res-card="pop" role="button" tabindex="0" title="平民 当前: ' + G.fmt(Core.civilianPopulation()) + '/' + G.fmt(Core.populationCapacity()) + ' (+' + G.fmt(Core.populationGrowthPerHour()) + '/h)" onclick="Game.Main.showPopulationDetail()" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){Game.Main.showPopulationDetail();event.preventDefault();}">';
    h += '<div class="res-summary">' + popIcon + '<span class="res-name">平民:</span><span class="res-main"><span class="res-cur">' + G.fmt(Core.civilianPopulation()) + '</span><span class="res-slash">/</span><span class="res-max">' + G.fmt(Core.populationCapacity()) + '</span></span><span class="res-rate">+' + G.fmt(Core.populationGrowthPerHour()) + '/h</span></div>';
    h += '<div class="d">可征召 ' + G.fmt(Core.popFree()) + ' · 民心 ' + curMorale + (curResent > 0 ? ' <span style="color:#d9534f">(怨' + curResent + ')</span>' : '') + '</div>';
    h += '</div>';
    h += '</div>';

    // 待办事项
    h += '<div id="homeTodosWrap">' + (G.Task ? G.Task.renderTodos(s) : '') + '</div>';

    // —— 世界聊天频道 ——
    h += '<div class="zone-head"><span class="zone-title">📡 世界频道</span><span class="zone-sub">实时通联</span></div>';
    h += '<div class="chat-terminal">';
    h += '<div class="chat-term-header">';
    h += '<div class="term-header-left">';
    h += '<span class="term-led"></span>';
    h += '<span class="term-title">COMM-LINK // 战术公频</span>';
    h += '</div>';
    h += '<div class="term-header-right">';
    h += '<span class="term-freq">CH-01 · 144.80 MHz</span>';
    h += '<span class="term-tag">ONLINE</span>';
    h += '</div>';
    h += '</div>';
    h += '<div class="chat-box" id="worldChatBox">';
    var msgs = (G.Chat && G.Chat.recent) ? G.Chat.recent(20) : [];
    for (var mi = 0; mi < msgs.length; mi++) {
      if (G.Chat && G.Chat.renderMessageHtml) {
        h += G.Chat.renderMessageHtml(msgs[mi]);
      } else {
        var m = msgs[mi];
        var safeName = String(m.username == null ? '玩家' : m.username)
          .replace(/[&<>"']/g, function (c) {
            return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
          });
        var safeContent = String(m.content == null ? '' : m.content)
          .replace(/[&<>"']/g, function (c) {
            return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
          });
        var isSelfMsg = (G.Chat && G.Chat.isSelf) ? G.Chat.isSelf(m) : false;
        h += '<div class="chat-msg' + (isSelfMsg ? ' chat-msg-self' : '') + '" data-type="' + (m.type || 'player') + '"' + (isSelfMsg ? ' data-self="1"' : '') + '>' +
          '<span class="chat-time">[' + G.fmtChatTime(m.ts) + ']</span>' +
          '<span class="chat-tag tag-world">[世界]</span>' +
          '<span class="chat-sender">' + safeName + ':</span>' +
          '<span class="chat-content' + (isSelfMsg ? ' chat-content-self' : '') + '">' + safeContent + '</span>' +
          '</div>';
      }
    }
    h += '</div>';

    var cd = (G.Chat && G.Chat.getCooldown) ? G.Chat.getCooldown() : 0;
    h += '<div class="chat-input-bar">';
    h += '<span class="chat-prompt">&gt;</span>';
    h += '<input class="chat-input" id="worldChatInput" type="text" maxlength="80" placeholder="输入电报简讯... (最多 80 字，Enter 发送)" autocomplete="off" onkeydown="if(event.key===&quot;Enter&quot;){Game.Main.sendChat();}"/>';
    h += '<button class="chat-send' + (cd > 0 ? ' disabled' : '') + '" id="worldChatSendBtn"' + (cd > 0 ? ' disabled' : '') + ' onclick="Game.Main.sendChat()">' + (cd > 0 ? cd + 's' : '发送') + '</button>';
    h += '</div>';
    h += '</div>';

    v.innerHTML = h;
    var chatBoxEl = document.getElementById('worldChatBox');
    if (chatBoxEl) chatBoxEl.scrollTop = chatBoxEl.scrollHeight;
  };

  Core.views.settings = function (v) {
    var h = '';
    h += '<div class="title">- 设置 -</div>';

    h += '<div class="zone-head"><span class="zone-title">游戏设置</span></div>';
    h += '<div class="panel">';
    var curTheme = (G.Theme && G.Theme.get) ? G.Theme.get() : 'blue-white';
    h += '<div class="btn-row" style="margin-bottom:8px;align-items:center;">';
    h += '<span style="flex:1;font-size:14px">🎨 界面风格</span>';
    h += '<select id="themeSelector" style="padding:4px 8px;font-size:13px;border-radius:4px;" onchange="if(Game.Theme)Game.Theme.set(this.value)">';
    if (G.Theme && G.Theme.THEMES) {
      for (var ti = 0; ti < G.Theme.THEMES.length; ti++) {
        var tObj = G.Theme.THEMES[ti];
        var isSel = tObj.id === curTheme ? ' selected' : '';
        h += '<option value="' + tObj.id + '"' + isSel + '>' + tObj.name + '</option>';
      }
    } else {
      h += '<option value="blue-white">晴空蓝白 (推荐)</option>';
      h += '<option value="paper">战术公文沙盘风</option>';
      h += '<option value="dark">战术夜航终端黑</option>';
    }
    h += '</select>';
    h += '</div>';
    h += '</div>';

    h += '<div class="zone-head"><span class="zone-title">账号信息</span></div>';
    h += '<div class="panel">';
    if (G.API && G.API.isLoggedIn()) {
      h += '<div class="d">登录账号: <b>' + G.escapeHtml(G.API.getUsername() || '未知') + '</b></div>';
      h += '<div class="d">游戏进度由服务器自动保存</div>';
      h += '<div class="btn-row" style="margin-top:6px">';
      h += '<button class="btn sm" onclick="Game.Main.logout()">切换账号</button>';
      h += '</div>';
    } else if (G.Main && G.Main.guestMode) {
      h += '<div class="d">当前模式: <b style="color:var(--muted)">游客模式</b></div>';
      h += '<div class="d">请登录账号继续游戏</div>';
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

    h += '<div class="zone-head"><span class="zone-title">关于</span></div>';
    h += '<div class="panel">';
    h += '<div class="d">烽原战策 - 文字战争策略游戏</div>';
    h += '<div class="d">版本: 1.0.0</div>';
    h += '</div>';

    if (G.API && G.API.isLoggedIn()) {
      h += '<div style="margin-top:16px">';
      h += '<button class="btn warn" style="width:100%;padding:12px;font-size:16px;color:#fff;background:var(--danger);border:0;border-radius:8px" onclick="Game.Main.logout(\'exit\')">退出登录</button>';
      h += '</div>';

      h += '<div class="zone-head" style="margin-top:18px;color:var(--danger)"><span class="zone-title">危险操作</span></div>';
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

  var PRESET_AVATARS = [
    { id: 'commander-8', name: '萌系指挥官', role: '休闲', src: 'img/avatars/commander-8.svg' },
    { id: 'commander-1', name: '陆军上将', role: '全军统帅', src: 'img/avatars/commander-1.svg' },
    { id: 'commander-2', name: '装甲指挥官', role: '装甲先锋', src: 'img/avatars/commander-2.svg' },
    { id: 'commander-3', name: '王牌飞行员', role: '空中制霸', src: 'img/avatars/commander-3.svg' },
    { id: 'commander-4', name: '海军提督', role: '深海巨舰', src: 'img/avatars/commander-4.svg' },
    { id: 'commander-5', name: '战术参谋长', role: '战役规划', src: 'img/avatars/commander-5.svg' },
    { id: 'commander-6', name: '特战先锋', role: '敌后奇袭', src: 'img/avatars/commander-6.svg' },
    { id: 'commander-7', name: '最高元帅', role: '荣誉勋章', src: 'img/avatars/commander-7.svg' }
  ];

  function getCurrentAvatar() {
    var s = Core.state || {};
    var p = s.player || {};
    var uname = p.username || '';
    var localAvatar = '';
    try {
      if (uname) localAvatar = localStorage.getItem('wargame_avatar_' + uname) || '';
      if (!localAvatar) localAvatar = localStorage.getItem('wargame_avatar_default') || '';
    } catch (e) {}
    return p.avatar || localAvatar || 'img/avatars/commander-8.svg';
  }

  function getMilitaryRankTitle(prestige) {
    if (G.getMilitaryRankInfo) {
      return G.getMilitaryRankInfo(prestige).name;
    }
    return '列兵';
  }

  function silentUpdateHome(tickData) {
    var v = document.getElementById('view');
    if (!v || Core.route !== 'home') return;
    var s = Core.state;
    if (!s) return;
    var r = s.resources || {};
    var mayor = Core.getOfficerByRole('mayor');

    // 1. 城市状态与坐标更新
    var statusTag = v.querySelector('.city-status-tag');
    if (statusTag) {
      var cs = Core.cityStatusText();
      var wantClass = 'city-status-tag ' + (cs.status || 'peace');
      if (statusTag.className !== wantClass) statusTag.className = wantClass;
      if (statusTag.getAttribute('data-status') !== (cs.status || 'peace')) {
        statusTag.setAttribute('data-status', cs.status || 'peace');
      }
      if (statusTag.textContent !== cs.text) statusTag.textContent = cs.text;
    }

    // 2. 军情与战报警讯增量更新
    var alertsWrap = document.getElementById('homeAlertsWrap');
    if (alertsWrap) {
      var marches = (s.world && s.world.marches) || [];
      var incoming = (s.world && s.world.incoming) || [];
      var alertCount = marches.length + incoming.length;
      var reportsCount = (s.reports || []).length;
      var alertHtml = '';
      if (alertCount > 0) {
        alertHtml += '<div class="home-alert" onclick="Game.go(\'alerts\')">⚔ 军情警讯 ' + alertCount + ' 起 (行军' + marches.length + '/来袭' + incoming.length + ') ></div>';
      }
      if (reportsCount > 0) {
        alertHtml += '<div class="home-alert rep" onclick="Game.go(\'reports\')">📋 战报 ' + reportsCount + ' 条 ></div>';
      }
      if (alertsWrap.innerHTML !== alertHtml) {
        alertsWrap.innerHTML = alertHtml;
      }
    }

    // 3. 总兵力更新
    var totArmyEl = v.querySelector('.home-army-tot');
    if (totArmyEl) {
      var totArmy = 0;
      var sArmy = s.army || {};
      for (var ak in sArmy) totArmy += sArmy[ak] || 0;
      var wantArmyText = G.fmt(totArmy);
      if (totArmyEl.textContent !== wantArmyText) totArmyEl.textContent = wantArmyText;
    }

    // 4. 资源卡片更新 (粮/钢/油/稀/金)
    var netFood = Core.produceOf('farm') - Core.foodPerHour();
    var netSteel = Core.produceOf('refinery');
    var netOil = Core.produceOf('oilfield');
    var netRare = Core.produceOf('raremine');
    var goldRate = Math.floor(Core.civilianPopulation() * (s.tax / 100) * (1 + (mayor ? mayor.knowledge / 100 : 0)) * 2);
    var cap = Core.capacity();
    var caps = { food: cap.food, steel: cap.steel, oil: cap.oil, rare: cap.rare, gold: 999999 };
    var nets = { food: netFood, steel: netSteel, oil: netOil, rare: netRare, gold: goldRate };
    var resKeys = ['food', 'steel', 'oil', 'rare', 'gold'];

    for (var ri = 0; ri < resKeys.length; ri++) {
      var rk = resKeys[ri];
      var card = v.querySelector('.res-card[data-res-card="' + rk + '"]');
      if (!card) continue;
      var cur = r[rk] || 0;
      var net = nets[rk] || 0;
      var sign = net >= 0 ? '+' : '';

      var curEl = card.querySelector('.res-cur');
      if (curEl) {
        var formattedCur = G.fmt(cur);
        if (curEl.textContent !== formattedCur) curEl.textContent = formattedCur;
      }

      var rateEl = card.querySelector('.res-rate');
      if (rateEl) {
        var rateText = sign + G.fmt(net) + '/h';
        if (rateEl.textContent !== rateText) rateEl.textContent = rateText;
        if (net < 0) {
          if (!rateEl.classList.contains('neg')) rateEl.classList.add('neg');
        } else {
          if (rateEl.classList.contains('neg')) rateEl.classList.remove('neg');
        }
      }

      var rinfo = D.resources[rk] || {};
      card.title = (rinfo.name || rk) + ' 当前: ' + G.fmt(cur) + ' (' + sign + G.fmt(net) + '/h)';
    }

    // 5. 平民卡片更新
    var popCard = v.querySelector('.res-card[data-res-card="pop"]');
    if (popCard) {
      var civ = Core.civilianPopulation();
      var pCap = Core.populationCapacity();
      var growth = Core.populationGrowthPerHour();
      var curMorale = Core.morale();
      var curResent = Core.resentment();

      var popCur = popCard.querySelector('.res-cur');
      if (popCur) {
        var formattedCiv = G.fmt(civ);
        if (popCur.textContent !== formattedCiv) popCur.textContent = formattedCiv;
      }

      var popMax = popCard.querySelector('.res-max');
      if (popMax) {
        var formattedCap = G.fmt(pCap);
        if (popMax.textContent !== formattedCap) popMax.textContent = formattedCap;
      }

      var popRate = popCard.querySelector('.res-rate');
      if (popRate) {
        var popRateText = '+' + G.fmt(growth) + '/h';
        if (popRate.textContent !== popRateText) popRate.textContent = popRateText;
      }

      var popDesc = popCard.querySelector('.d');
      if (popDesc) {
        var newPopDesc = '可征召 ' + G.fmt(Core.popFree()) + ' · 民心 ' + curMorale + (curResent > 0 ? ' <span style="color:#d9534f">(怨' + curResent + ')</span>' : '');
        if (popDesc.innerHTML !== newPopDesc) popDesc.innerHTML = newPopDesc;
      }

      popCard.title = '平民 当前: ' + G.fmt(civ) + '/' + G.fmt(pCap) + ' (+' + G.fmt(growth) + '/h)';
    }

    // 6. 待办事项增量更新 (仅替换内部局部容器，不破坏周边DOM)
    var todosWrap = document.getElementById('homeTodosWrap');
    if (todosWrap && G.Task && G.Task.renderTodos) {
      var newTodos = G.Task.renderTodos(s);
      if (todosWrap.innerHTML !== newTodos) {
        todosWrap.innerHTML = newTodos;
      }
    }

    // 7. 若民情政务弹窗正开着，同步更新弹窗内数值
    if (typeof window.__refreshPopDetailModal === 'function') {
      window.__refreshPopDetailModal();
    }
  }

  G.MainView = {
    navBar: navBar,
    showResourceDetail: showResourceDetail,
    showPopulationDetailModal: showPopulationDetailModal,
    getCurrentAvatar: getCurrentAvatar,
    getMilitaryRankTitle: getMilitaryRankTitle,
    presetAvatars: PRESET_AVATARS,
    silentUpdateHome: silentUpdateHome
  };

  if (G.Main) {
    G.Main.silentUpdateHome = silentUpdateHome;
  }
})(window.Game);
