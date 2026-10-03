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

  function score(entry, metric) {
    if (metric === 'militaryRank') return esc(entry.militaryRankName);
    return G.fmt(metric === 'members' ? entry.members : entry.prestige);
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
            '<strong>' + esc(mine.name) + '</strong>' +
          '</div>' +
        '</div>' +
        '<div class="ranking-mine-stat">' +
          '<span>名次</span>' +
          '<strong class="ranking-mine-rank-val' + myRankClass + '">' + myRankMedal + '第 ' + G.fmt(mine.rank) + ' 名</strong>' +
        '</div>' +
        '<div class="ranking-mine-stat">' +
          '<span>' + (this.metric === 'members' ? '成员' : (this.metric === 'militaryRank' ? '军衔' : '声望')) + '</span>' +
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
        var primaryLabel = this.metric === 'members' ? '成员人数' : (this.metric === 'militaryRank' ? '军衔' : (guilds ? '总声望' : '声望'));
        var secondaryLabel = guilds ? (this.metric === 'members' ? '总声望' : '成员人数') : (this.metric === 'militaryRank' ? '声望' : '军衔');
        h += '<table class="ranking-table"><caption class="ranking-sr-only">' + BOARDS[this.type].label + '</caption>' +
          '<thead><tr>' +
            '<th scope="col">名次</th>' +
            '<th scope="col">' + (guilds ? '军团' : '玩家') + '</th>' +
            '<th scope="col">' + primaryLabel + '</th>' +
            '<th class="ranking-secondary" scope="col">' + secondaryLabel + '</th>' +
          '</tr></thead><tbody>';

        data.entries.forEach(function (entry) {
          var isMine = mine && entry.id === mine.id;
          var detail = guilds ? '团长 · ' + (entry.leaderName || '暂无') : (entry.guildName || '未加入军团');
          var secondary = guilds ? score(entry, Rankings.metric === 'members' ? 'prestige' : 'members') : score(entry, Rankings.metric === 'militaryRank' ? 'prestige' : 'militaryRank');
          var rankMedal = entry.rank === 1 ? '🥇' : (entry.rank === 2 ? '🥈' : (entry.rank === 3 ? '🥉' : ''));

          var secondaryHtml;
          if (!guilds && Rankings.metric !== 'militaryRank' && entry.militaryRankName) {
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
            '<td><span class="' + posClass + '">' +
              (rankMedal ? '<span class="ranking-medal">' + rankMedal + '</span>' : '') +
              '<span class="ranking-num">' + entry.rank + '</span>' +
            '</span></td>' +
            '<td><div class="ranking-identity">' + portrait(entry, guilds) +
              '<div>' +
                '<div class="ranking-name">' + esc(entry.name) + (isMine ? '<span class="ranking-you">' + (guilds ? '本团' : '我') + '</span>' : '') + '</div>' +
                '<span class="ranking-detail">' + (guilds ? '🛡️ ' : (entry.guildName ? '🛡️ ' : '')) + esc(detail) + '</span>' +
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
          '声望榜按声望、军衔依次降序排列；军衔榜按军衔、声望依次降序排列。两项均相同，按玩家入驻顺序排列。') +
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
