/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var Core = G.Core;
  var BOARDS = G.Constants.rankingBoards;

  function esc(value) { return G.escapeHtml(String(value == null ? '' : value)); }

  function ownerKey() {
    var player = Core.state && Core.state.player;
    var server = G.Servers ? G.Servers.current().id : '';
    return server + ':' + (player ? player.id : '');
  }

  function portrait(entry, guilds) {
    if (guilds) return G.Guild.iconHtml(entry.icon);
    var avatars = G.Constants.presetAvatars;
    var src = avatars.some(function (avatar) { return avatar.src === entry.avatar; }) ? entry.avatar : avatars[0].src;
    return '<img class="ranking-avatar" src="' + esc(src) + '" alt="" loading="lazy">';
  }

  /** 两项战力只在输出时换算显示单位，保留接口原值及服务端名次。 */
  function score(entry, metric) {
    if (metric === 'militaryRank') return esc(entry.militaryRankName);
    var value = entry[metric];
    if (metric === 'formationPower' || metric === 'overallPower') value /= G.Constants.playerPowerDisplayDivisor;
    return Math.floor(Number(value) || 0).toLocaleString('en-US');
  }

  function metricName(metric, guilds) {
    return { members: '成员人数', militaryRank: '军衔', formationPower: '编队战力', overallPower: '综合实力' }[metric]
      || (guilds ? '总声望' : '统帅声望');
  }

  function rankBadge(entry) {
    if (!entry) return '';
    var tier = entry.militaryRank || 1;
    var name = entry.militaryRankName || (G.DATA && G.DATA.militaryRanks && G.DATA.militaryRanks[tier - 1] ? G.DATA.militaryRanks[tier - 1].name : '列兵');
    var icon = (G.renderMilitaryRankIcon && tier) ? G.renderMilitaryRankIcon(tier) : '';
    return '<span class="ranking-rank-badge" title="军衔：' + esc(name) + '">' +
      (icon ? '<span class="ranking-rank-ico">' + icon + '</span>' : '') +
      '<span class="ranking-rank-name">' + esc(name) + '</span>' +
    '</span>';
  }

  function honorTagsHtml(rank, isMine, guilds) {
    var tags = '';
    if (guilds) {
      if (rank === 1) {
        tags += '<span class="ranking-honor-tag tag-top1">第一军团</span><span class="ranking-stamp-seal">【霸主军团】</span>';
      } else if (rank === 2) {
        tags += '<span class="ranking-honor-tag tag-top2">副席军团</span>';
      } else if (rank === 3) {
        tags += '<span class="ranking-honor-tag tag-top3">精锐军团</span>';
      } else if (rank >= 4 && rank <= 10) {
        tags += '<span class="ranking-honor-tag tag-top10">十强军团</span>';
      }
    } else {
      if (rank === 1) {
        tags += '<span class="ranking-honor-tag tag-top1">战区榜首</span><span class="ranking-stamp-seal">【至尊统帅】</span>';
      } else if (rank === 2) {
        tags += '<span class="ranking-honor-tag tag-top2">战区副帅</span>';
      } else if (rank === 3) {
        tags += '<span class="ranking-honor-tag tag-top3">三军先锋</span>';
      } else if (rank >= 4 && rank <= 10) {
        tags += '<span class="ranking-honor-tag tag-top10">十强统帅</span>';
      }
    }
    if (isMine) {
      tags += '<span class="ranking-you">' + (guilds ? '本团' : '我') + '</span>';
    }
    return tags;
  }

  /** 前三名仿王者荣耀匹配载入框精雕外框（第1名钻石、第2名翡翠、第3名黄金） */
  function topFrameOverlay(rank) {
    if (rank === 1) {
      return '<div class="ranking-frame-layer ranking-frame-top1 pointer-events-none" aria-hidden="true">' +
        '<svg viewBox="0 0 600 60" preserveAspectRatio="none">' +
          '<defs>' +
            '<linearGradient id="rkDiamondGrad" x1="0%" y1="0%" x2="100%" y2="100%">' +
              '<stop offset="0%" stop-color="#38bdf8"/>' +
              '<stop offset="25%" stop-color="#e0f2fe"/>' +
              '<stop offset="50%" stop-color="#0284c7"/>' +
              '<stop offset="75%" stop-color="#ffffff"/>' +
              '<stop offset="100%" stop-color="#0369a1"/>' +
            '</linearGradient>' +
          '</defs>' +
          '<rect x="3" y="3" width="594" height="54" rx="8" fill="none" stroke="url(#rkDiamondGrad)" stroke-width="2.5" />' +
          '<rect x="6" y="6" width="588" height="48" rx="6" fill="none" stroke="#bae6fd" stroke-width="0.8" opacity="0.8" />' +
          '<path d="M 1 18 L 1 7 C 1 3 3 1 7 1 L 18 1 L 14 5 L 5 5 L 5 14 Z" fill="#0284c7" stroke="#e0f2fe" stroke-width="0.8" />' +
          '<polygon points="5,5 9,2 13,5 9,8" fill="#38bdf8" />' +
          '<path d="M 599 18 L 599 7 C 599 3 597 1 593 1 L 582 1 L 586 5 L 595 5 L 595 14 Z" fill="#0284c7" stroke="#e0f2fe" stroke-width="0.8" />' +
          '<polygon points="595,5 591,2 587,5 591,8" fill="#38bdf8" />' +
          '<path d="M 1 42 L 1 53 C 1 57 3 59 7 59 L 18 59 L 14 55 L 5 55 L 5 46 Z" fill="#0284c7" stroke="#e0f2fe" stroke-width="0.8" />' +
          '<path d="M 599 42 L 599 53 C 599 57 597 59 593 59 L 582 59 L 586 55 L 595 55 L 595 46 Z" fill="#0284c7" stroke="#e0f2fe" stroke-width="0.8" />' +
          '<g transform="translate(300, 2)">' +
            '<path d="M -24 0 Q -12 -5 0 -2 Q 12 -5 24 0 Q 12 -1 0 1 Q -12 -1 -24 0 Z" fill="#0284c7" stroke="#38bdf8" stroke-width="0.5" />' +
            '<polygon points="0,-4 7,-1 0,5 -7,-1" fill="#e0f2fe" stroke="#0284c7" stroke-width="0.8" />' +
            '<polygon points="0,-4 3,-1 0,5" fill="#ffffff" />' +
          '</g>' +
          '<g transform="translate(300, 57)"><polygon points="0,-3 6,0 0,3 -6,0" fill="#0284c7" stroke="#bae6fd" stroke-width="0.6" /></g>' +
        '</svg>' +
      '</div>';
    }
    if (rank === 2) {
      return '<div class="ranking-frame-layer ranking-frame-top2 pointer-events-none" aria-hidden="true">' +
        '<svg viewBox="0 0 600 60" preserveAspectRatio="none">' +
          '<defs>' +
            '<linearGradient id="rkEmeraldGrad" x1="0%" y1="0%" x2="100%" y2="100%">' +
              '<stop offset="0%" stop-color="#10b981"/>' +
              '<stop offset="30%" stop-color="#6ee7b7"/>' +
              '<stop offset="50%" stop-color="#059669"/>' +
              '<stop offset="85%" stop-color="#047857"/>' +
              '<stop offset="100%" stop-color="#064e3b"/>' +
            '</linearGradient>' +
          '</defs>' +
          '<rect x="3" y="3" width="594" height="54" rx="8" fill="none" stroke="url(#rkEmeraldGrad)" stroke-width="2.5" />' +
          '<rect x="6" y="6" width="588" height="48" rx="6" fill="none" stroke="#fde047" stroke-width="0.8" opacity="0.75" />' +
          '<path d="M 1 16 L 1 7 C 1 3 3 1 7 1 L 16 1 L 12 5 L 5 5 L 5 12 Z" fill="#047857" stroke="#a7f3d0" stroke-width="0.8" />' +
          '<circle cx="7" cy="7" r="2.5" fill="#34d399" stroke="#f59e0b" stroke-width="0.8" />' +
          '<path d="M 599 16 L 599 7 C 599 3 597 1 593 1 L 584 1 L 588 5 L 595 5 L 595 12 Z" fill="#047857" stroke="#a7f3d0" stroke-width="0.8" />' +
          '<circle cx="593" cy="7" r="2.5" fill="#34d399" stroke="#f59e0b" stroke-width="0.8" />' +
          '<path d="M 1 44 L 1 53 C 1 57 3 59 7 59 L 16 59 L 12 55 L 5 55 L 5 48 Z" fill="#047857" stroke="#a7f3d0" stroke-width="0.8" />' +
          '<path d="M 599 44 L 599 53 C 599 57 597 59 593 59 L 584 59 L 588 55 L 595 55 L 595 48 Z" fill="#047857" stroke="#a7f3d0" stroke-width="0.8" />' +
          '<g transform="translate(300, 2)">' +
            '<path d="M -20 0 Q -10 -4 0 -2 Q 10 -4 20 0 Z" fill="#047857" stroke="#f59e0b" stroke-width="0.6" />' +
            '<ellipse cx="0" cy="0" rx="6" ry="4" fill="#10b981" stroke="#fde047" stroke-width="1" />' +
            '<ellipse cx="-1.5" cy="-1" rx="2" ry="1.2" fill="#d1fae5" opacity="0.8" />' +
          '</g>' +
          '<g transform="translate(300, 57)"><ellipse cx="0" cy="0" rx="4" ry="2.5" fill="#059669" stroke="#f59e0b" stroke-width="0.6" /></g>' +
        '</svg>' +
      '</div>';
    }
    if (rank === 3) {
      return '<div class="ranking-frame-layer ranking-frame-top3 pointer-events-none" aria-hidden="true">' +
        '<svg viewBox="0 0 600 60" preserveAspectRatio="none">' +
          '<defs>' +
            '<linearGradient id="rkGoldGrad" x1="0%" y1="0%" x2="100%" y2="100%">' +
              '<stop offset="0%" stop-color="#f59e0b"/>' +
              '<stop offset="25%" stop-color="#fef08a"/>' +
              '<stop offset="50%" stop-color="#d97706"/>' +
              '<stop offset="75%" stop-color="#fbbf24"/>' +
              '<stop offset="100%" stop-color="#78350f"/>' +
            '</linearGradient>' +
          '</defs>' +
          '<rect x="3" y="3" width="594" height="54" rx="8" fill="none" stroke="url(#rkGoldGrad)" stroke-width="2.5" />' +
          '<rect x="6" y="6" width="588" height="48" rx="6" fill="none" stroke="#fef08a" stroke-width="0.8" opacity="0.7" />' +
          '<path d="M 1 16 L 1 7 C 1 3 3 1 7 1 L 16 1 L 12 5 L 5 5 L 5 12 Z" fill="#b45309" stroke="#fde047" stroke-width="0.8" />' +
          '<polygon points="5,5 9,2 13,5 9,8" fill="#fef08a" />' +
          '<path d="M 599 16 L 599 7 C 599 3 597 1 593 1 L 584 1 L 588 5 L 595 5 L 595 12 Z" fill="#b45309" stroke="#fde047" stroke-width="0.8" />' +
          '<polygon points="595,5 591,2 587,5 591,8" fill="#fef08a" />' +
          '<path d="M 1 44 L 1 53 C 1 57 3 59 7 59 L 16 59 L 12 55 L 5 55 L 5 48 Z" fill="#b45309" stroke="#fde047" stroke-width="0.8" />' +
          '<path d="M 599 44 L 599 53 C 599 57 597 59 593 59 L 584 59 L 588 55 L 595 55 L 595 48 Z" fill="#b45309" stroke="#fde047" stroke-width="0.8" />' +
          '<g transform="translate(300, 2)">' +
            '<path d="M -18 0 L -12 -5 L -6 -1 L 0 -7 L 6 -1 L 12 -5 L 18 0 Z" fill="#f59e0b" stroke="#fef08a" stroke-width="0.8" />' +
            '<circle cx="0" cy="-2" r="1.5" fill="#ef4444" />' +
          '</g>' +
          '<g transform="translate(300, 57)"><polygon points="0,-3 5,0 0,3 -5,0" fill="#d97706" stroke="#fef08a" stroke-width="0.6" /></g>' +
        '</svg>' +
      '</div>';
    }
    return '';
  }

  var Rankings = {
    type: 'players',
    metric: 'prestige',
    page: 1,
    data: null,
    loading: false,
    error: '',
    owner: '',
    requested: false,
    updatedAt: 0,
    requestId: 0,
    locateAfterLoad: false,
    dropdownOpen: false,

    toggleDropdown: function (e) {
      if (e && typeof e.stopPropagation === 'function') e.stopPropagation();
      this.dropdownOpen = !this.dropdownOpen;
      var menu = document.getElementById('rankingDropdownMenu');
      var btn = document.getElementById('rankingDropdownBtn');
      var wrap = document.getElementById('rankingDropdownWrap');
      if (menu) {
        if (this.dropdownOpen) {
          if (typeof menu.removeAttribute === 'function') menu.removeAttribute('hidden');
          menu.hidden = false;
        } else {
          if (typeof menu.setAttribute === 'function') menu.setAttribute('hidden', '');
          menu.hidden = true;
        }
      }
      if (btn && typeof btn.setAttribute === 'function') {
        btn.setAttribute('aria-expanded', String(this.dropdownOpen));
      }
      if (wrap && wrap.classList) {
        if (this.dropdownOpen) wrap.classList.add('open');
        else wrap.classList.remove('open');
      }
    },

    closeDropdown: function () {
      if (!this.dropdownOpen) return;
      this.dropdownOpen = false;
      var menu = document.getElementById('rankingDropdownMenu');
      var btn = document.getElementById('rankingDropdownBtn');
      var wrap = document.getElementById('rankingDropdownWrap');
      if (menu) {
        if (typeof menu.setAttribute === 'function') menu.setAttribute('hidden', '');
        menu.hidden = true;
      }
      if (btn && typeof btn.setAttribute === 'function') btn.setAttribute('aria-expanded', 'false');
      if (wrap && wrap.classList) wrap.classList.remove('open');
    },

    selectMetric: function (metric) {
      this.closeDropdown();
      this.setMetric(metric);
    },

    setType: function (type) {
      if (!BOARDS[type] || type === this.type) return;
      this.closeDropdown();
      this.type = type;
      this.metric = BOARDS[type].metrics[0].id;
      this.page = 1;
      this.data = null;
      this.locateAfterLoad = false;
      this.load();
    },

    setMetric: function (metric) {
      if (metric === this.metric || !BOARDS[this.type].metrics.some(function (item) { return item.id === metric; })) return;
      this.closeDropdown();
      this.metric = metric;
      this.page = 1;
      this.data = null;
      this.locateAfterLoad = false;
      this.load();
    },

    setPage: function (page, locate) {
      if (this.loading || !this.data || page < 1 || page > this.data.totalPages || page === this.page) return;
      this.page = page;
      this.data = null;
      this.locateAfterLoad = !!locate;
      this.load();
    },

    locateMine: function () {
      if (!this.data || !this.data.mine) return;
      var page = Math.ceil(this.data.mine.rank / this.data.pageSize);
      if (page !== this.page) { this.setPage(page, true); return; }
      var row = document.querySelector('.ranking-row-mine');
      if (row) { row.scrollIntoView({ block: 'center', behavior: 'smooth' }); row.focus({ preventScroll: true }); }
    },

    /** 加载榜单；切换榜单、大区或账号后，旧响应不能覆盖当前视图。 */
    load: function () {
      var requestId = ++this.requestId;
      var owner = ownerKey();
      var type = this.type;
      var metric = this.metric;
      this.owner = owner;
      this.requested = true;
      this.loading = true;
      this.error = '';
      this.update();
      return G.API.getLeaderboard(type, metric, this.page).then(function (data) {
        if (requestId !== Rankings.requestId || owner !== ownerKey()) return;
        if (!data || !Array.isArray(data.entries) || data.type !== type || data.metric !== metric) {
          throw new Error('排名数据加载失败');
        }
        Rankings.data = data;
        Rankings.page = data.page;
      }).catch(function (error) {
        if (requestId !== Rankings.requestId || owner !== ownerKey()) return;
        Rankings.error = error.message || '排名加载失败，请重试';
      }).finally(function () {
        if (requestId !== Rankings.requestId || owner !== ownerKey()) return;
        Rankings.loading = false;
        Rankings.updatedAt = Date.now();
        Rankings.update();
        if (Rankings.locateAfterLoad && Core.route === 'rankings' && !Rankings.error) {
          Rankings.locateAfterLoad = false;
          Rankings.locateMine();
        }
      });
    },

    update: function () {
      if (Core.route !== 'rankings') return;
      var view = document.getElementById('view');
      if (!view) return;
      this.renderView(view);
      Core.renderBackButton(view);
    },

    renderView: function (view) {
      // 全量重绘会创建收起的菜单，同步关闭旧状态，避免下一次点击只关闭已隐藏的菜单。
      this.closeDropdown();
      var guilds = this.type === 'guilds';
      var data = this.data;
      var mine = data && data.mine;
      var server = G.Servers ? G.Servers.current().name : '当前大区';
      var disabled = this.loading ? ' disabled' : '';

      var h = '<section class="rankings-page" aria-labelledby="rankingsTitle">' +
        '<header class="rankings-header">' +
          '<div class="rankings-header-main">' +
            '<div class="rankings-title-badge" aria-hidden="true">🎖️</div>' +
            '<div>' +
              '<h1 id="rankingsTitle">排名<span class="rankings-title-badge-text">战区统帅榜</span></h1>' +
              '<div class="rankings-subtitle">' +
                '<span class="rankings-server-tag">🚩 ' + esc(server) + '</span>' +
                '<span class="rankings-total-tag">' + (data ? '共 ' + G.fmt(data.total) + (guilds ? ' 个军团' : ' 位指挥官') : '正在统计…') + '</span>' +
              '</div>' +
            '</div>' +
          '</div>' +
          '<button type="button" class="ranking-icon-button" title="刷新排名" aria-label="刷新排名" onclick="Game.Rankings.load()"' + disabled + '>' +
            '<span aria-hidden="true" class="ranking-refresh-icon">↻</span>' +
            '<span class="ranking-refresh-text">刷新</span>' +
          '</button>' +
        '</header>';

      // 一级 Tab 导航
      h += '<div class="rankings-nav-group">' +
        '<nav class="ranking-tabs" aria-label="排行榜类型">';
      Object.keys(BOARDS).forEach(function (type) {
        var active = type === Rankings.type;
        h += '<button type="button" class="ranking-tab' + (active ? ' active' : '') + '" aria-pressed="' + active +
          '" onclick="Game.Rankings.setType(\'' + type + '\')">' + BOARDS[type].label + '</button>';
      });
      h += '</nav></div>';

      // 我的排名专属荣誉档案卡
      h += '<div class="ranking-mine" aria-label="' + (guilds ? '我的军团排名' : '我的排名') + '">';
      if (mine) {
        var myRankClass = mine.rank === 1 ? ' top-gold' : (mine.rank === 2 ? ' top-silver' : (mine.rank === 3 ? ' top-bronze' : ''));
        var myRankMedal = mine.rank === 1 ? '🥇 ' : (mine.rank === 2 ? '🥈 ' : (mine.rank === 3 ? '🥉 ' : ''));
        h += '<div class="ranking-mine-identity">' +
          '<div class="ranking-mine-avatar-wrap">' + portrait(mine, guilds) + '</div>' +
          '<div>' +
            '<span>' + (guilds ? '我的军团' : '我的统帅战位') + '</span>' +
            '<div class="ranking-mine-name-row"><strong>' + esc(mine.name) + '</strong>' + honorTagsHtml(mine.rank, true, guilds) + '</div>' +
          '</div>' +
        '</div>' +
        '<div class="ranking-mine-stat">' +
          '<span>名次</span>' +
          '<strong class="ranking-mine-rank-val' + myRankClass + '">' + myRankMedal + '第 ' + G.fmt(mine.rank) + ' 名</strong>' +
        '</div>' +
        '<div class="ranking-mine-stat">' +
          '<span>' + metricName(this.metric, guilds) + '</span>' +
          '<strong class="ranking-mine-score-val">' + score(mine, this.metric) + '</strong>' +
        '</div>';
        if (!guilds && mine.militaryRankName) {
          h += '<div class="ranking-mine-stat ranking-mine-rank-col">' +
            '<span>统帅军衔</span>' +
            '<div>' + rankBadge(mine) + '</div>' +
          '</div>';
        }
        h += '<button type="button" class="ranking-text-button ranking-locate-btn" onclick="Game.Rankings.locateMine()"' + disabled + '>🎯 查看名次</button>';
      } else {
        h += '<span class="ranking-mine-placeholder">' + (this.loading ? '正在获取排名…' : (guilds ? '暂无军团排名' : '暂无个人排名')) + '</span>';
        if (guilds && !this.loading && !this.error) {
          h += '<button type="button" class="ranking-text-button ranking-locate-btn" onclick="Game.go(\'guild\')">🛡️ 前往军团</button>';
        }
      }
      h += '</div>';

      // 二级筛选控制栏（自定义战术下拉框，解决原生 select 在部分视口下的弹窗错位问题）
      var metrics = BOARDS[this.type].metrics;
      var curMetric = metrics[0];
      for (var mi = 0; mi < metrics.length; mi++) {
        if (metrics[mi].id === Rankings.metric) {
          curMetric = metrics[mi];
          break;
        }
      }

      function getMetricIco(mid) {
        return mid === 'prestige' ? '🎖️ ' : (mid === 'militaryRank' ? '⚔️ ' : (mid === 'members' ? '👥 ' : '📊 '));
      }

      h += '<div class="ranking-toolbar">' +
        '<div class="ranking-toolbar-filters">' +
          '<label for="rankingDropdownBtn">榜单</label>' +
          '<div class="ranking-dropdown-wrap" id="rankingDropdownWrap">' +
            '<button type="button" class="ranking-dropdown-trigger" id="rankingDropdownBtn" aria-haspopup="listbox" aria-expanded="false" onclick="Game.Rankings.toggleDropdown(event)">' +
              '<span class="ranking-dropdown-current-text">' + getMetricIco(curMetric.id) + esc(curMetric.label) + '</span>' +
              '<span class="ranking-dropdown-arrow" aria-hidden="true">▾</span>' +
            '</button>' +
            '<div class="ranking-dropdown-menu" id="rankingDropdownMenu" role="listbox" aria-label="榜单切换" hidden>';
      metrics.forEach(function (metric) {
        var isSel = metric.id === Rankings.metric;
        h += '<div class="ranking-dropdown-item' + (isSel ? ' selected' : '') + '" role="option" aria-selected="' + isSel + '" onclick="Game.Rankings.selectMetric(\'' + metric.id + '\')">' +
          '<span class="dropdown-item-check">' + (isSel ? '✓' : '&nbsp;') + '</span>' +
          '<span class="dropdown-item-label">' + getMetricIco(metric.id) + esc(metric.label) + '</span>' +
        '</div>';
      });
      h += '</div>' +
            '<!-- 原生 select 保持在 DOM 供无障碍与测试兼容 -->' +
            '<select id="rankingMetric" class="ranking-sr-only" onchange="Game.Rankings.setMetric(this.value)" tabindex="-1" aria-hidden="true">';
      metrics.forEach(function (metric) {
        h += '<option value="' + metric.id + '"' + (metric.id === Rankings.metric ? ' selected' : '') + '>' + metric.label + '</option>';
      });
      h += '</select>' +
          '</div>' +
        '</div>' +
        '<span class="ranking-total">' + (data ? '共 ' + G.fmt(data.total) + (guilds ? ' 个军团' : ' 位玩家') : '') + '</span>' +
      '</div>';

      // 榜单表格结果区
      h += '<div class="ranking-results" aria-busy="' + this.loading + '" aria-live="polite">';
      if (this.error) {
        h += '<div class="ranking-status error" role="alert"><p>' + esc(this.error) + '</p><button type="button" class="ranking-text-button ranking-locate-btn" onclick="Game.Rankings.load()"' + disabled + '>重新加载</button></div>';
      } else if (!data) {
        h += '<div class="ranking-status" role="status">正在加载排名…</div>';
      } else if (!data.entries.length) {
        h += '<div class="ranking-status">' + (guilds ? '暂无军团上榜' : '暂无玩家上榜') + '</div>';
      } else {
        var primaryLabel = metricName(this.metric, guilds);
        var powerBoard = this.metric === 'formationPower' || this.metric === 'overallPower';
        var secondaryMetric = guilds ? (this.metric === 'members' ? 'prestige' : 'members')
          : (powerBoard ? (this.metric === 'formationPower' ? 'overallPower' : 'formationPower') : (this.metric === 'militaryRank' ? 'prestige' : 'militaryRank'));
        var secondaryLabel = metricName(secondaryMetric, guilds);
        h += '<table class="ranking-table"><caption class="ranking-sr-only">' + BOARDS[this.type].label + '</caption>' +
          '<thead><tr>' +
            '<th scope="col">名次</th>' +
            '<th scope="col">' + (guilds ? '军团' : '玩家') + '</th>' +
            '<th scope="col">' + primaryLabel + '</th>' +
            '<th class="ranking-secondary" scope="col">' + secondaryLabel + '</th>' +
          '</tr></thead><tbody>';

        data.entries.forEach(function (entry) {
          var isMine = mine && entry.id === mine.id;
          var detail = guilds ? '团长 · ' + (entry.leaderName || '暂无') : (entry.guildName ? '🛡️ ' + entry.guildName : '未加入军团') + (!guilds && entry.militaryRankName && Rankings.metric !== 'militaryRank' ? ' · 军衔 ' + entry.militaryRankName : '');
          var secondary = score(entry, secondaryMetric);
          var rankMedal = entry.rank === 1 ? '🥇' : (entry.rank === 2 ? '🥈' : (entry.rank === 3 ? '🥉' : ''));

          var secondaryHtml;
          if (!guilds && secondaryMetric === 'militaryRank' && entry.militaryRankName) {
            secondaryHtml = rankBadge(entry);
          } else {
            secondaryHtml = secondary;
          }

          var primaryHtml;
          if (!guilds && Rankings.metric === 'militaryRank') {
            primaryHtml = rankBadge(entry);
          } else {
            primaryHtml = '<strong>' + score(entry, Rankings.metric) + '</strong>';
          }

          var rowTopClass = entry.rank <= 3 ? ' ranking-row-top' + entry.rank : '';
          var posClass = 'ranking-position' + (entry.rank <= 3 ? ' ranking-top-' + entry.rank : '');

          h += '<tr class="ranking-row' + rowTopClass + (isMine ? ' ranking-row-mine' : '') + '"' + (isMine ? ' tabindex="-1" aria-label="我的排名"' : '') + '>' +
            '<td>' + topFrameOverlay(entry.rank) + '<span class="' + posClass + '">' +
              (rankMedal ? '<span class="ranking-medal">' + rankMedal + '</span>' : '') +
              '<span class="ranking-num">' + entry.rank + '</span>' +
            '</span></td>' +
            '<td><div class="ranking-identity">' + portrait(entry, guilds) +
              '<div>' +
                '<div class="ranking-name">' + (!guilds && !isMine ? '<button type="button" class="ranking-text-button" onclick="Game.Friends.view(' + Number(entry.id) + ')">' + esc(entry.name) + '</button>' : esc(entry.name)) + honorTagsHtml(entry.rank, isMine, guilds) + '</div>' +
                '<span class="ranking-detail">' + (guilds ? '🛡️ ' : '') + esc(detail) + '</span>' +
              '</div>' +
            '</div></td>' +
            '<td class="ranking-score">' + primaryHtml + '<span class="ranking-mobile-secondary">' + secondaryLabel + ' ' + secondary + '</span></td>' +
            '<td class="ranking-secondary">' + secondaryHtml + '</td>' +
          '</tr>';
        });
        h += '</tbody></table>';
      }
      h += '</div>';

      // 底部更新时间与分页导航
      if (data) {
        h += '<footer class="ranking-footer">' +
          '<span class="ranking-updated">战区情报更新于 ' + esc(new Date(data.updatedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })) + '</span>' +
          '<div class="ranking-pagination" aria-label="排名分页">' +
            '<button type="button" class="ranking-icon-button" title="上一页" aria-label="上一页" onclick="Game.Rankings.setPage(' + (this.page - 1) + ')"' + (this.loading || this.page <= 1 ? ' disabled' : '') + '>‹</button>' +
            '<span>' + this.page + ' / ' + data.totalPages + '</span>' +
            '<button type="button" class="ranking-icon-button" title="下一页" aria-label="下一页" onclick="Game.Rankings.setPage(' + (this.page + 1) + ')"' + (this.loading || this.page >= data.totalPages ? ' disabled' : '') + '>›</button>' +
          '</div>' +
        '</footer>';
      }

      // 排名规则
      h += '<details class="ranking-rules"><summary>📜 战区排名与授勋规则</summary><p>' +
        (guilds ? '总声望为本团上榜成员的声望之和，人数为上榜成员数。总声望相同时人数多者优先，人数相同时总声望高者优先；仍相同按军团创建顺序排列。' :
          '声望榜按声望、军衔排序；军衔榜按军衔、声望排序。编队战力为最强单支现有编队的静态估值；综合实力统计现有部队、建筑、科技、军官和装备，不计声望。战力仅供参考，实际战斗受克制和战术影响。同分按玩家入驻顺序排列。') +
        ' 仅统计当前大区内已建立角色、账号正常且未被封禁的玩家。点击刷新可重新统计。</p></details>';

      view.innerHTML = h + '</section>';
    }
  };

  G.Rankings = Rankings;
  Core.views.rankings = function (view) {
    if (Rankings.owner !== ownerKey()) {
      Rankings.requestId++;
      Rankings.type = 'players';
      Rankings.metric = 'prestige';
      Rankings.page = 1;
      Rankings.data = null;
      Rankings.loading = false;
      Rankings.error = '';
      Rankings.requested = false;
      Rankings.locateAfterLoad = false;
    }
    if (!Rankings.loading && (!Rankings.requested || Date.now() - Rankings.updatedAt > 30000)) Rankings.load();
    Rankings.renderView(view);
  };

  if (typeof document !== 'undefined' && document.addEventListener) {
    document.addEventListener('click', function (e) {
      var wrap = document.getElementById('rankingDropdownWrap');
      if (wrap && e.target && !wrap.contains(e.target)) {
        Rankings.closeDropdown();
      }
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') {
        Rankings.closeDropdown();
      }
    });
  }
})(window.Game);
