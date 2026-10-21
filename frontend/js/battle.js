/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';
  G.gameNow = G.gameNow || function () { return Date.now(); };

  var D = G.DATA;
  var Core = G.Core;

  function U(id, isJapanese) {
    var original = String(id || ''); id = original.split('~')[0];
    if (id === 'wall') return { name: '城墙', range: 0, spd: 0, cat: 'fort' };
    var unit = isJapanese && D.japaneseUnits && D.japaneseUnits[id] || D.units[id] || D.forts && D.forts[id];
    if (!unit) return null;
    return original.indexOf('~') < 0 ? unit : Object.assign({}, unit, { name: unit.name + '（援军配装组 ' + original.split('~')[1] + '）' });
  }

  /** 从战报快照提取技能加成，仅旧版“每级”描述按快照等级换算，避免用当前配置改写历史数值。 */
  function commanderSkillBonus(skill, scouting) {
    var bonus = String(skill.description || '').match(/(\d+(?:\.\d+)?)%(\/级)?/);
    var value;
    if (bonus) {
      // 首个百分比是技能效果，后续百分比可能是上限或计算细则，不展示在简报中。
      value = Number(bonus[1]) * (bonus[2] ? Math.max(0, Number(skill.level) || 0) : 1);
    } else if (scouting && skill.id === 'blitz') {
      // 兼容未保存技能描述的旧侦查快照，沿用原有交火速度换算。
      value = 6 * Math.max(0, Math.min(5, Number(skill.level) || 0));
    } else {
      return '';
    }
    var label = G.Constants.battleSkillBonusLabels[skill.name] || '技能加成 ';
    if (scouting && skill.id === 'blitz') label = '侦查交火速度 +';
    return label + (Math.round(value * 100) / 100) + '%';
  }

  // Battle calculation logic has been removed.
  // The backend sends battle results via WebSocket; the frontend just displays them.

  var Battle = {
    _reportDetails: {},
    _reportDetailOrder: [],
    _reportDetailMeta: {},
    _reportsPreviousCursors: [],
    _reportsPageCursor: null,
    _reportsNextCursor: null,
    _reportsHasMore: false,
    retentionContext: function () { return G.API.retentionContext ? G.API.retentionContext() : G.API.getToken ? G.API.getToken() : ''; },
    resetRetentionCache: function () {
      this._reportDetails = {}; this._reportDetailMeta = {}; this._reportDetailOrder = [];
      this._viewReport = null; this._reportsNextCursor = null; this._reportsPageCursor = null;
      this._reportsPreviousCursors = []; this._reportsHasMore = false;
      this._reportsHistoryLoaded = null; this._reportsHistoryLoading = null;
      this._reportsPageRequest = (this._reportsPageRequest || 0) + 1;
      this._unreadRequest = (this._unreadRequest || 0) + 1;
      this._cacheIdentity = null;
      if (Core.state) { Core.state.reports = []; Core.state.unreadReportCount = 0; }
    },
    ensureRetentionContext: function () {
      var key = this.retentionContext();
      if (this._cacheIdentity != null && this._cacheIdentity !== key) this.resetRetentionCache();
      this._cacheIdentity = key;
      return key;
    },
    /** 优先使用服务端总数，不受当前已加载战报条数限制。 */
    unreadCount: function () {
      if (G.state && typeof G.state.unreadReportCount === 'number') return G.state.unreadReportCount;
      var reports = (G.state && G.state.reports) || [];
      var n = 0;
      for (var i = 0; i < reports.length; i++) {
        if (!reports[i].readAt) n++;
      }
      return n;
    },

    /** 在导航红点处更新战报未读数 */
    refreshUnread: function () {
      var navs = document.querySelectorAll('#navbar .navitem');
      for (var i = 0; i < navs.length; i++) {
        var nav = navs[i];
        if (nav.getAttribute('data-route') !== 'reports') continue;
        var old = nav.querySelector('.nav-badge');
        if (old) old.remove();
        var n = this.unreadCount();
        if (n > 0) {
          var span = document.createElement('span');
          span.className = 'nav-badge';
          span.textContent = n > 99 ? '99+' : String(n);
          nav.appendChild(span);
        }
      }
    },

    /** 和邮件一样，在新报告到达时重新获取未读总数。 */
    syncUnread: function () {
      if (!G.API || !G.API.getUnreadReports) return Promise.resolve();
      var self = this;
      var token = G.API.getToken();
      var request = this._unreadRequest = (this._unreadRequest || 0) + 1;
      return G.API.getUnreadReports().then(function (data) {
        if (G.API.getToken() !== token || request !== self._unreadRequest || !G.state) return;
        if (data && typeof data.unreadCount === 'number') G.state.unreadReportCount = data.unreadCount;
        self.refreshUnread();
      }).catch(function () {});
    },

    renderReportsList: function (v) {
      this.ensureRetentionContext();
      this._viewReport = null;
      var s = Core.state;
      if (this._refreshOnReturn || Date.now() - (this._reportsRefreshedAt || 0) > 60000) {
        this._reportsHistoryLoaded = null; this._refreshOnReturn = false;
      }
      if (s.reports) {
        var visible = s.reports.filter(function (r) { return r.starred || !r.summaryExpiresAt || r.summaryExpiresAt > Date.now(); });
        if (visible.length !== s.reports.length) s.reports = visible;
      }
      var reports = s.reports || (s.reports = []);
      var h = '';
      h += '<div class="menu">';
      h += '<div class="rc-line">普通战报详情保留 30 天，随后战果摘要再保留 30 天；每个账号每个大区可收藏 50 份。</div>';
      var unread = this.unreadCount();
      if (unread > 0) {
        h += '<div class="btn-row" style="margin-bottom:6px">';
        h += '<span style="flex:1;font-size:13px;color:var(--muted)">未读战报 <b style="color:var(--danger)">' + unread + '</b> 条</span>';
        h += '<button class="btn sm" onclick="Game.Battle.markAllRead()">全部标为已读</button>';
        h += '</div>';
      }
      for (var i = 0; i < reports.length; i++) {
        var r = reports[i];
        if (r.type === 'scout') {
          h += this.renderScoutReportCard(r);
        } else {
          h += this.renderReportCard(r);
        }
      }
      if (this._reportsPreviousCursors.length) h += '<button class="btn" onclick="Game.Battle.previousReportsPage()">上一页</button>';
      if (this._reportsPageCursor) h += '<button class="btn" onclick="Game.Battle.firstReportsPage()">最新战报</button>';
      if (this._reportsHasMore) h += '<button class="btn" onclick="Game.Battle.loadMoreReports()">下一页</button>';
      if (!reports.length) {
        h += '<div class="reports-empty" id="reportsEmpty">' +
          '<div class="reports-empty-icon">⚔</div>' +
          '<div class="reports-empty-title">暂无战报</div>' +
          '<div class="reports-empty-text">这里还没有发生战斗或侦查行动</div>' +
          '<div class="reports-empty-hint">出征、侦查或遭遇来袭后，战报会自动记录在这里</div>' +
          '<button class="btn reports-empty-btn" onclick="Game.go(\'world\')">前往地图</button>' +
        '</div>';
      }
      h += '</div>';
      h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
      v.innerHTML = h;
      // 渲染后立即刷新导航红点
      this.refreshUnread();
      // 每份账号列表加载一次历史，即使已经先收到实时战报，也不能漏掉离线战报。
      if (G.API && typeof G.API.getReports === 'function' && this._reportsHistoryLoaded !== reports && this._reportsHistoryLoading !== reports) {
        var self = this;
        this._reportsHistoryLoading = reports;
        var token = this.retentionContext();
        var request = this._reportsPageRequest = (this._reportsPageRequest || 0) + 1;
        G.API.getReports(20, this._reportsPageCursor).then(function (page) {
          if (self.retentionContext() !== token || request !== self._reportsPageRequest) return;
          self._reportsHistoryLoading = null;
          var list = Array.isArray(page) ? page : page && page.items;
          if (!Array.isArray(list)) return;
          self._reportsRefreshedAt = Date.now();
          self._reportsHasMore = Array.isArray(page) ? list.length >= 20 : !!page.hasMore;
          self._reportsNextCursor = Array.isArray(page) ? (list.length ? String(list[list.length - 1].time || 0) + ':' + String(list[list.length - 1].id) : null) : page.nextCursor;
          if (!Array.isArray(page)) Core.state.reports = list.slice(0, 100);
          if (!Array.isArray(Core.state.reports)) Core.state.reports = [];
          // 合并去重
          var existingIds = {};
          for (var j = 0; j < Core.state.reports.length; j++) {
            if (Core.state.reports[j].id != null) existingIds[String(Core.state.reports[j].id)] = true;
          }
          for (var k = 0; k < list.length; k++) {
            if (list[k].id != null && existingIds[String(list[k].id)]) continue;
            Core.state.reports.push(list[k]);
          }
          // 按时间倒序
          Core.state.reports.sort(function (a, b) { return (b.time || 0) - (a.time || 0) || Number(b.id || 0) - Number(a.id || 0); });
          if (Core.state.reports.length > 100) Core.state.reports.length = 100;
          self._reportsHistoryLoaded = Core.state.reports;
          // 只局部更新一次，不再触发新的历史请求
          if (Core.route === 'reports') self.renderReportsList(v);
          else self.refreshUnread();
        }).catch(function () {
          if (self._reportsHistoryLoading === reports) self._reportsHistoryLoading = null;
        });
      }
    },

    loadMoreReports: function () {
      if (!this._reportsHasMore || !this._reportsNextCursor || !G.API.getReports) return Promise.resolve();
      this._reportsPreviousCursors.push(this._reportsPageCursor);
      if (this._reportsPreviousCursors.length > 100) this._reportsPreviousCursors.shift();
      this._reportsPageCursor = this._reportsNextCursor;
      this._reportsHistoryLoaded = null;
      Core.state.reports = [];
      Core.render();
    },
    previousReportsPage: function () {
      if (!this._reportsPreviousCursors.length) return;
      this._reportsPageCursor = this._reportsPreviousCursors.pop(); this._reportsHistoryLoaded = null;
      Core.state.reports = []; Core.render();
    },
    firstReportsPage: function () {
      this._reportsPageCursor = null; this._reportsPreviousCursors = []; this._reportsHistoryLoaded = null;
      Core.state.reports = []; Core.render();
    },

    cacheReportDetail: function (report) {
      this.ensureRetentionContext();
      var id = String(report.id);
      var size = encodeURIComponent(JSON.stringify(report)).replace(/%[0-9A-F]{2}/g, 'x').length;
      if (size > 5 * 1024 * 1024) return report;
      this._reportDetails[id] = report;
      this._reportDetailMeta[id] = { bytes: size, expiresAt: Math.min(Date.now() + 60000, report.starred ? Infinity : Number(report.detailsAvailable === false ? report.summaryExpiresAt : report.detailExpiresAt) || Infinity) };
      this._reportDetailOrder = this._reportDetailOrder.filter(function (key) { return key !== id; });
      this._reportDetailOrder.push(id);
      var self = this;
      function totalBytes() { return self._reportDetailOrder.reduce(function (n, key) { return n + self._reportDetailMeta[key].bytes; }, 0); }
      while (this._reportDetailOrder.length > 5 || totalBytes() > 5 * 1024 * 1024) {
        var removed = this._reportDetailOrder.shift(); delete this._reportDetails[removed]; delete this._reportDetailMeta[removed];
      }
      return report;
    },

    reportDetail: function (report) {
      var context = this.ensureRetentionContext();
      var cached = this._reportDetails[String(report.id)];
      var meta = this._reportDetailMeta[String(report.id)];
      if (cached && meta && meta.expiresAt > Date.now()) return Promise.resolve(cached);
      delete this._reportDetails[String(report.id)]; delete this._reportDetailMeta[String(report.id)];
      this._reportDetailOrder = this._reportDetailOrder.filter(function (key) { return key !== String(report.id); });
      if (!G.API.getReportDetail) return Promise.resolve(report);
      var self = this;
      return G.API.getReportDetail(report.id).then(function (detail) {
        if (self.retentionContext() !== context) throw new Error('账号或大区已切换');
        return self.cacheReportDetail(detail);
      }).catch(function (err) {
        if (self.retentionContext() === context && /过期|不存在/.test(err.message || '')) {
          Core.state.reports = (Core.state.reports || []).filter(function (r) { return String(r.id) !== String(report.id); });
          self._reportsHistoryLoaded = null; self.syncUnread();
        }
        throw err;
      });
    },

    /** 实时推送只进入摘要窗口，完整日志归入同一个有界详情缓存。 */
    summarizeLiveReport: function (report) {
      this.cacheReportDetail(report);
      var keys = ['id', 'type', 'time', 'readAt', 'starred', 'detailExpiresAt', 'summaryExpiresAt', 'subject', 'action', 'originalAction', 'perspective', 'attackerName', 'defenderName', 'fromName', 'toName', 'fromCoord', 'toCoord', 'interceptCoord', 'win', 'attackerWon', 'result', 'targetType', 'cityConquered', 'wildConquered', 'wildOccupation', 'occupationFailure', 'intercepted', 'attackerContinued', 'attackerRetreated', 'defenderRetreated', 'interceptionOpening', 'targetName', 'x', 'y', 'showCityInfo', 'detailLevel', 'scoutMode', 'isCity'];
      // 与服务端卡片投影一致，保留真实胜负、接管结论和部队去向，不能从当前状态推断。
      keys = keys.concat(['attackerWin', 'troopDisposition', 'wildOccupationReason', 'conquered', 'cityTakeoverReason', 'targetSea', 'sea', 'scoutType']);
      function pick(source) { var out = {}; keys.forEach(function (key) { if (source[key] != null) out[key] = source[key]; }); return out; }
      var summary = pick(report);
      if (report.data && typeof report.data === 'object') summary.data = pick(report.data);
      summary.summaryOnly = true; summary.detailsAvailable = true;
      this._reportsHistoryLoaded = null;
      return summary;
    },

    toggleStar: function (reportId) {
      var report = this.findReport(reportId);
      if (!report || !G.API.starReport) return;
      if (report.starred && report.detailExpiresAt && report.detailExpiresAt <= Date.now() && typeof window.confirm === 'function' && !window.confirm('取消收藏后将按原期限过期，详情可能立即不可用。确定取消？')) return;
      var context = this.retentionContext();
      var self = this;
      var action = report.starred ? G.API.unstarReport(reportId) : G.API.starReport(reportId);
      action.then(function () {
        if (self.retentionContext() !== context) return;
        report.starred = !report.starred;
        delete self._reportDetails[String(reportId)]; delete self._reportDetailMeta[String(reportId)];
        self._reportDetailOrder = self._reportDetailOrder.filter(function (key) { return key !== String(reportId); });
        self._reportsHistoryLoaded = null;
        G.toast(report.starred ? '已收藏战报' : '已取消收藏');
        if (Core.route === 'reports') Core.render();
      }).catch(function (err) { G.toast(err.message || '操作失败'); });
    },

    /** 一键全部已读，以请求前的列表为准，保留请求期间新到达的战报。 */
    markAllRead: function () {
      if (!G.API || !G.API.markAllReportsRead) return;
      var self = this;
      var token = G.API.getToken();
      var reports = ((G.state && G.state.reports) || []).slice();
      return G.API.markAllReportsRead().then(function (data) {
        if (G.API.getToken() !== token) return;
        if (!data || !data.success) throw new Error((data && data.message) || '操作失败');
        self._unreadRequest = (self._unreadRequest || 0) + 1;
        for (var i = 0; i < reports.length; i++) {
          if (!reports[i].readAt) reports[i].readAt = Date.now();
        }
        if (G.state && typeof data.unreadCount === 'number') G.state.unreadReportCount = data.unreadCount;
        G.toast('已全部标为已读');
        if (Core.route === 'reports' || Core.route === 'reportDetail') Core.render();
        self.refreshUnread();
        if (G.Onboarding) G.Onboarding.refresh();
        return self.syncUnread();
      }).catch(function (err) {
        if (G.API.getToken() !== token) return;
        G.toast(err && err.message ? err.message : '操作失败');
        return self.syncUnread();
      });
    },

    /** 服务端确认后标记已读；失败时保留提示，避免刷新后未读数字反弹。 */
    markOneRead: function (reportId) {
      if (reportId == null) return;
      var report = this.findReport(reportId);
      if (!report || report.readAt) return;
      var self = this;
      // 兼容旧服务端推送的临时战报。
      if (String(reportId).indexOf('battle-') === 0) {
        report.readAt = Date.now();
        this.refreshUnread();
        return;
      }
      if (!G.API || !G.API.markReportRead) return;
      var token = G.API.getToken();
      return G.API.markReportRead(reportId).then(function (data) {
        if (G.API.getToken() !== token) return;
        if (!data || !data.success) throw new Error((data && data.message) || '标记已读失败');
        self._unreadRequest = (self._unreadRequest || 0) + 1;
        report.readAt = data.readAt || Date.now();
        if (G.state && typeof data.unreadCount === 'number') G.state.unreadReportCount = data.unreadCount;
        self.refreshUnread();
        if (G.Onboarding) G.Onboarding.refresh();
        return self.syncUnread();
      }).catch(function (err) {
        if (G.API.getToken() !== token) return;
        G.toast(err && err.message ? err.message : '标记已读失败');
        return self.syncUnread();
      });
    },

    reportTitle: function (r) {
      if (r.action === 'intercept_scout') return '拦截战报';
      var titles = G.Constants.battleReportTitles;
      if (titles[r.action]) return titles[r.action];
      // 历史战报未保存行动字段，只根据旧标题的行动前缀判断，避免误匹配玩家名。
      if (r.cityConquered || r.wildConquered) return '讨伐报告';
      var match = /^(征服|掠夺|占领野地|剿寇|攻城|战役|防守)/.exec(r.subject || '');
      if (match) return /^(征服|掠夺|占领野地)$/.test(match[1]) ? '讨伐报告' : match[1] + '报告';
      return { bandit: '剿寇报告', npc: '攻城报告', campaign: '战役报告' }[r.targetType] || '战斗报告';
    },

    /**
     * 将战报中的原始坐标格式化为安全的显示文本；缺失坐标时不显示空括号。
     * @param {string} coord - 服务端保存的坐标
     * @returns {string} 带括号的 HTML 安全文本
     */
    reportCoord: function (coord) {
      return coord ? ' (' + G.escapeHtml(coord) + ')' : '';
    },

    /**
     * 按回合标题统计实战回合，沿用战斗详情中的日志格式。
     * @param {string[]} logs - 战斗回合日志
     * @returns {number} 已记录的回合数
     */
    reportRoundCount: function (logs) {
      if (!Array.isArray(logs)) return 0;
      return logs.filter(function (line) {
        return String(line || '').indexOf('--') === 0;
      }).length;
    },

    /**
     * 保留服务端战斗日志的时间顺序；双方阵营通过行样式区分。
     * @param {string[]} logs - 当前视角的战斗日志
     * @returns {string[]} 与服务端一致的战斗日志顺序
     */
    groupRoundSideLogs: function (logs) {
      return Array.isArray(logs) ? logs.slice() : [];
    },

    /**
     * 将已结算的交火日志精简为伤害、击毁数和目标距离；原始日志仍供历史兵力恢复使用。
     * @param {string} line - 服务端保存的单行日志，可包含机动及多个目标的战果
     * @returns {string} 尚未进行 HTML 转义的展示文本
     */
    formatCombatLogLine: function (line) {
      var text = String(line || '');
      if (!/伤害\d/.test(text)) return text;
      return text.split(/[；;]/).map(function (part) {
        var compact = part.replace(/\s*(?:对(?:地|空|海|工事)攻击|(?:本次)?原始火力|剩余攻击额度|有效射程)\s*\d+(?:\.\d+)?%?/g, '')
          .replace(/[，,]\s*$/, '').trim();
        // 从最后一个目标阵营标记定位战果，兼容名称内的型号括号，并去掉目标后的倍率细则。
        var hit = compact.match(/^(.*)(敌方?|我方?)(.+?)\((\d+)\)(?:\s*\[[^\]]*\])*\s+伤害(\d+)\s+击毁(\d+)(?:\s+目标距离(\d+))?\s*$/);
        if (hit) {
          var action = hit[1].replace(/余伤攻击$/, Number(hit[5]) > 0 ? '势不可挡，继续攻击' : '继续攻击');
          return action + hit[2].charAt(0) + '方' + hit[3] + '，造成' + hit[5] + '点伤害，击毁' + hit[6]
            + (hit[7] != null ? '，目标距离' + hit[7] : '');
        }
        // 城墙没有兵种击毁数，保留已扣除的伤害及剩余生命，后续攻墙同样使用连击文案。
        var wall = compact.match(/^(.*?)(余伤攻击|攻击)(敌方?|我方?)城墙[，,]\s*伤害(\d+)(.*)$/);
        if (wall) {
          var wallAction = wall[2] === '余伤攻击' && Number(wall[4]) > 0 ? '势不可挡，继续攻击' : '攻击';
          return wall[1] + wallAction + wall[3].charAt(0) + '方城墙，造成' + wall[4] + '点伤害'
            + wall[5].replace(/[，,\s]*目标距离(\d+)/, '，目标距离$1');
        }
        return compact;
      }).join('；');
    },

    /**
     * 原始战斗日志固定以攻方为“我方”；仅在展示时转换守方视角，保留原文供历史兵力解析。
     * @param {string} line - 服务端保存的单行攻方日志
     * @param {Object} report - 含接收者视角和胜负的战报
     * @returns {string} 当前接收者视角的展示文本，尚未进行 HTML 转义
     */
    formatReportCombatLogLine: function (line, report) {
      // 先统一攻击目标的“我/敌”简称，再转换阵营，兼容反击、余伤攻击和旧型号名称。
      var text = this.formatCombatLogLine(line);
      if (!this.isDefenderReport(report)) return text;
      if (/^(我方|敌方)(将领加成|司令官)[:：]/.test(text)) {
        // 技能中的“敌方防御”等描述相对于施放者，仅交换将领归属。
        return (text.indexOf('我方') === 0 ? '敌方' : '我方') + text.slice(2);
      }
      if (/^(我方|敌方)/.test(text)) {
        var sides = { '我方': '敌方', '敌方': '我方', '距我': '距敌', '距敌': '距我' };
        return text.replace(/我方|敌方|距我(?=\d)|距敌(?=\d)/g, function (side) { return sides[side]; });
      }
      if (/^攻方伤兵入营/.test(text)) return text.replace(/^攻方/, '敌方');
      if (/^守方伤兵入营/.test(text)) return text.replace(/^守方/, '我方');
      // 经验和战利品记录属于攻方，不能作为守方奖励展示。
      if (/^获得经验\s*[:：]/.test(text)) return '敌方' + text;
      if (/^✗|^★.*(?:胜利|成功)[!！]/.test(text)) {
        var won = this.battleReportWon(report);
        var interception = this.interceptionOutcome(report);
        return (won ? '★ ' : '✗ ') + (interception ? interception.result : (won ? '防守成功' : '防守失败')) + '！';
      }
      if (/^★/.test(text)) {
        return '敌方战果：' + text.slice(1).trim().replace(/我方|敌方/g, function (side) {
          return side === '我方' ? '敌方' : '我方';
        });
      }
      return text;
    },

    wildOccupation: function (r) {
      var subject = r.subject || '';
      var legacyWild = /^(占领野地|掠夺野地)/.test(subject);
      var isWild = r.targetType ? r.targetType === 'wild' : (legacyWild || r.wildConquered === true);
      if (!isWild) return null;
      var action = r.action || (/^占领野地/.test(subject) || r.wildConquered === true ? 'conquer' : /^掠夺野地/.test(subject) ? 'plunder' : '');
      if (['feint', 'harass', 'guerrilla'].includes(action)) return { text: '未占领（骚扰打击）', detail: '本次' + G.Constants.dispatchActionNames[action] + '不获取资源、不改变野地归属，幸存部队自动返城。', tone: '' };
      if (action === 'plunder') return { text: '未占领（仅夺取资源）', detail: '本次讨伐仅夺取资源，不改变野地归属。', tone: '' };
      if (action !== 'conquer') return null;
      // 实际占领快照优先于胜负和旧标题，不能从战斗胜利推测归属。
      var occupied = typeof r.wildConquered === 'boolean' ? r.wildConquered
        : typeof r.conquered === 'boolean' ? r.conquered
        : /^占领野地胜利·已占领(?:\s|$)/.test(subject) ? true : null;
      if (occupied === true) return { text: '占领成功', detail: '本次讨伐已成功占领该野地，结算时已纳入我方领地。', tone: 'w' };
      if (occupied === false || r.win === false) return {
        text: '未占领', tone: 'l',
        detail: r.win === false ? '本次战斗未获胜，未能占领该野地。' : r.wildOccupationReason || '本次战斗获胜，但结算记录为未占领；战报未记录具体原因。'
      };
      return { text: '占领结果未记录', detail: '该历史战报仅记录战斗胜负，无法确认当时是否占领；可前往野地查看当前归属。', tone: '' };
    },

    /**
     * 城市接管必须有实际接管判定快照；旧征服奖励标记不代表取得领地。
     * @param {Object} r - 战报数据
     * @returns {{result: string, detail: string}} 结果徽章与战报详情文案
     */
    conquestOutcome: function (r) {
      if (this.cityTakenOver(r)) return { result: '接管成功', detail: '★ 已接管该城市，建筑、建设队列与资源保留' };
      var targetName = String(r.toName || r.targetName || '');
      var isFleet = r.targetSea === true || r.sea === true ||
        /舰队|航母编队|潜艇支队|驱逐舰队/.test(targetName);
      if (isFleet) return { result: '已击败', detail: '★ 已击败该舰队' };
      return { result: '已击败', detail: '★ 已击败该目标' };
    },

    /** 新接管结算同时保存资格原因，历史 cityConquered 不补算为城权转移。 */
    cityTakenOver: function (r) {
      return r.targetType === 'player' && r.action !== 'plunder' && r.cityConquered === true && r.cityTakeoverReason === '';
    },

    /**
     * 判断战报是否应按守城方视角展示；无 perspective 的旧玩家战报通过当前账号与来袭玩家名兼容判断。
     * @param {Object} r - 战报数据
     * @returns {boolean} 当前玩家是否为守城方
     */
    isDefenderReport: function (r) {
      if (r.perspective === 'defender') return true;
      if (r.perspective === 'attacker' || r.targetType !== 'player') return false;
      var player = (Core.state && Core.state.player) || {};
      var ownName = player.username || player.name;
      return !!(ownName && r.attackerName && String(ownName) !== String(r.attackerName));
    },

    /**
     * 读取接收者视角的战斗胜负，并为旧版攻方胜负字段补充守方反转逻辑。
     * @param {Object} r - 战报数据
     * @returns {boolean} 战报接收者是否获胜
     */
    battleReportWon: function (r) {
      var defense = this.isDefenderReport(r);
      if (typeof r.attackerWin === 'boolean') return defense ? !r.attackerWin : r.attackerWin;
      if (defense && !r.perspective) return !r.win;
      return !!r.win;
    },

    /** 按拦截方与来袭方视角描述城外交火；突围不代表已完成侦查或攻城。 */
    interceptionOutcome: function (r) {
      if (r.action !== 'intercept_scout') return null;
      var defense = this.isDefenderReport(r);
      var won = this.battleReportWon(r);
      var disposition = r.troopDisposition;
      var movement = { continuing: '幸存部队沿原行军路线继续前往原目标', returning: '幸存部队返城', destroyed: '无幸存部队' }[disposition] || '部队去向未记录';
      return {
        result: defense ? (won ? '拦截成功' : '拦截失败') : (won ? '突围成功' : '突围失败'),
        movement: movement,
        detail: defense
          ? (won ? '我方出城截击，已阻止敌方本次来袭行军。' : '敌方突破我方拦截，幸存部队沿原路线继续前往原目标。')
          : (won ? '我方突破敌方城外拦截，幸存部队沿原路线继续行进；抵达目标后再执行原任务。' : '我方遭遇敌方城外截击，未能突破拦截，本次来袭行军已被阻止。')
      };
    },

    /**
     * 读取当前战报接收者视角中的敌方统帅，兼容未保存双方统帅快照的历史战报。
     * @param {Object} r - 战报数据
     * @returns {string} 敌方统帅名称或未参战、未记录提示
     */
    enemyCommanderName: function (r) {
      if (!r.commanders || typeof r.commanders !== 'object') return '统帅信息未记录';
      var commander = r.commanders[this.isDefenderReport(r) ? 'attacker' : 'defender'];
      return commander && commander.name ? commander.name : '无统帅参战';
    },

    /** 只解释本场持久化的已知规则版本，历史战报不推定伏击。 */
    interceptionOpeningText: function (r) {
      var opening = r.action === 'intercept_scout' && r.interceptionOpening;
      if (!opening || opening.version !== 'interception-ambush-v1-20261021') return '';
      if (opening.mode !== 'ambush') return opening.mode === 'urgent' ? '紧急迎击 · 按常规顺序交战' : '';
      return '伏击先手 · ' + (this.isDefenderReport(r) ? '设伏完成，我方取得首次交火先手' : '遭遇伏击，敌方取得首次交火先手')
        + ' · 首次交火第 ' + Number(opening.firstCombatRound) + ' 回合 · 伏击距离 ' + Number(opening.distance);
    },

    renderReportCard: function (r) {
      if (r.summaryPending) return this.renderPendingReportCard(r);
      var d = new Date(r.time);
      var ts = (d.getMonth() + 1) + '-' + String(d.getDate()).padStart(2, '0') + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
      var unread = !r.readAt;
      var won = this.battleReportWon(r);
      var interception = this.interceptionOutcome(r);
      var h = '';
      h += '<div class="report-card ' + (won ? 'win' : 'lose') + (unread ? ' unread' : '') + '">';
      h += '<div class="rc-head" onclick="Game.Battle.toggleReport(\'' + r.id + '\')" style="cursor:pointer">';
      h += '<span class="rc-subject">' + (unread ? '<span class="unread-dot"></span>' : '') + this.reportTitle(r) + '</span>';
      h += '<span class="rc-time">' + ts + '</span>';
      h += '<span class="rc-result ' + (won ? 'w' : 'l') + '">' + (interception ? interception.result : (won ? '胜' : '败')) + '</span>';
      h += '</div>';
      h += '<div class="rc-body" onclick="Game.Battle.toggleReport(\'' + r.id + '\')" style="cursor:pointer">';
      h += '<div class="rc-line">' + G.escapeHtml(r.attackerName || r.fromName || '我方') + ' → ' + G.escapeHtml(r.toName || '目标') + this.reportCoord(r.toCoord) + '</div>';
      if (interception) h += '<div class="rc-line">拦截地点' + this.reportCoord(r.interceptCoord) + ' · ' + interception.movement + '</div>';
      var openingText = this.interceptionOpeningText(r);
      if (openingText) h += '<div class="rc-line">' + G.escapeHtml(openingText) + '</div>';
      var occupation = this.wildOccupation(r);
      if (occupation) h += '<div class="rc-line"><b>占领结果:</b> ' + occupation.text + '</div>';
      h += '</div>';
      h += '<div id="rdetail_' + r.id + '" class="rc-expand" style="display:none"></div>';
      h += '<div class="btn-row" style="margin-top:4px"><button id="rcta_' + r.id + '" class="btn sm" onclick="Game.Battle.viewReportDetail(\'' + r.id + '\')">' + (r.detailsAvailable === false ? '查看战果摘要' : '查看完整战报') + '</button><button class="btn sm" onclick="Game.Battle.toggleStar(\'' + r.id + '\')">' + (r.starred ? '取消收藏' : '收藏战报') + '</button>' + (r.detailsAvailable === false ? '' : '<button class="btn sm" onclick="Game.Battle.shareReport(\'' + r.id + '\')" style="margin-left:6px;background:var(--accent,#8c6d3b);color:#fff">分享战报</button>') + '</div>';
      h += '</div>';
      return h;
    },

    _formatRes: function (res) {
      if (!res) return '';
      var names = G.Constants.battleResourceNames;
      var parts = [];
      for (var k in res) {
        if (res[k] > 0) parts.push((names[k] || k) + G.fmt(res[k]));
      }
      return parts.join(' ');
    },

    /**
     * 渲染接收者与敌军城市的结算快照；历史战报或 NPC 未记录的民心、民怨项不补零展示。
     * @param {Object} report - 战报数据。
     * @param {boolean} defense - 当前玩家是否为防守方。
     * @param {string} resourceText - 已格式化的资源战果。
     * @returns {string} 战果结算 HTML；旧战报返回空字符串。
     */
    renderBattleSettlement: function (report, defense, resourceText) {
      var hasSettlement = report.prestigeAfter != null || report.moraleAfter != null
        || report.resentmentAfter != null || report.enemyResentmentAfter != null
        || report.recoveryPercent != null || report.losses != null;
      if (!hasSettlement) return '';
      var fmt = typeof G.fmt === 'function' ? G.fmt : String;
      var signed = function (value) {
        var amount = Number(value) || 0;
        return (amount >= 0 ? '+' : '') + fmt(amount);
      };
      var recoveryPercent = Math.max(0, Number(report.recoveryPercent) || 0);
      var recoveredCount = Math.max(0, Number(report.recoveredCount) || 0);
      var losses = Math.max(0, Number(report.losses) || 0);
      var h = '<div class="rb-settlement"><div class="rb-settlement-title">【战果结算】</div><div class="rb-settlement-grid">';
      if (resourceText) h += '<div class="rb-settlement-item"><span>资源' + (defense ? '损失' : '战果') + '</span><b>' + resourceText + '</b></div>';
      if (!defense && report.exp > 0) h += '<div class="rb-settlement-item"><span>将领经验</span><b>+' + fmt(report.exp) + '</b></div>';
      if (report.prestigeAfter != null || report.prestigeChange != null) h += '<div class="rb-settlement-item"><span>声望变化</span><b>' + signed(report.prestigeChange) + ' <small>当前 ' + fmt(Math.max(0, Number(report.prestigeAfter) || 0)) + '</small></b></div>';
      if (report.moraleAfter != null || report.moraleChange != null) h += '<div class="rb-settlement-item"><span>民心变化</span><b>' + signed(report.moraleChange) + ' <small>当前 ' + fmt(Math.max(0, Number(report.moraleAfter) || 0)) + '</small></b></div>';
      if (report.enemyMoraleAfter != null || report.enemyMoraleChange != null) {
        h += '<div class="rb-settlement-item"><span>敌军城市民心变化</span><b>' + signed(report.enemyMoraleChange) + ' <small>战后 ' + fmt(Math.max(0, Number(report.enemyMoraleAfter) || 0)) + '</small></b></div>';
      }
      if (report.resentmentAfter != null || report.resentmentChange != null) {
        h += '<div class="rb-settlement-item"><span>民怨变化</span><b>' + signed(report.resentmentChange) + ' <small>战后 ' + fmt(Math.max(0, Number(report.resentmentAfter) || 0)) + '</small></b></div>';
      }
      if (report.enemyResentmentAfter != null || report.enemyResentmentChange != null) {
        h += '<div class="rb-settlement-item"><span>敌军城市民怨变化</span><b>' + signed(report.enemyResentmentChange) + ' <small>战后 ' + fmt(Math.max(0, Number(report.enemyResentmentAfter) || 0)) + '</small></b></div>';
      }
      h += '<div class="rb-settlement-item"><span>本方战损</span><b>' + fmt(losses) + '</b></div>';
      h += '<div class="rb-settlement-item"><span>伤兵回收</span><b>' + recoveryPercent + '% <small>入营 ' + fmt(recoveredCount) + '</small></b></div>';
      var snapshot = report.prisoners;
      var totalAccepted = snapshot ? (Number(snapshot.acceptedTotal) || 0) : 0;
      var hasPrisoners = totalAccepted > 0;
      if (!hasPrisoners && snapshot && snapshot.accepted) {
        hasPrisoners = Object.values(snapshot.accepted).some(function (v) { return Number(v) > 0; });
      }
      if (!hasPrisoners) {
        var checkRows = function (rows) {
          if (!rows) return false;
          return Object.keys(rows).some(function (k) { return Number((rows[k] || {}).captured) > 0; });
        };
        hasPrisoners = checkRows(report.attackerBreakdown) || checkRows(report.defenderBreakdown);
      }
      if (hasPrisoners) {
        h += '<div class="rb-settlement-item"><span>俘虏结算</span><b>候选 ' + fmt(snapshot.candidateTotal || 0) + ' · 收容 ' + fmt(totalAccepted) + '</b></div>';
        h += '</div>';
        h += '<details class="rb-prisoner-details"><summary>战俘与战损分账明细（收容 ' + fmt(totalAccepted) + '）</summary>';
        if (snapshot.cityName) {
          h += '<div class="rb-settlement-note" style="padding-left:0;padding-right:0;">实际收容城市：' + G.escapeHtml(snapshot.cityName) + ' · 比例 ' + ((Number(snapshot.ratePermille) || 0) / 10).toFixed(1) + '% · 容量 ' + fmt(snapshot.capacity || 0) + '</div>';
        }
        var breakdown = defense ? report.defenderBreakdown : report.attackerBreakdown;
        var enemyBreakdown = defense ? report.attackerBreakdown : report.defenderBreakdown;
        var renderBreakdown = function (title, rows) {
          if (!rows) return '';
          var out = '<div class="rb-prisoner-breakdown"><b>' + title + '</b>';
          var count = 0;
          Object.keys(rows).forEach(function (unit) {
            var row = rows[unit] || {};
            var def = G.DATA && G.DATA.units && G.DATA.units[String(unit).split('~')[0]];
            var captured = Number(row.captured) || 0;
            var parts = [
              '损失 ' + fmt(row.losses || 0),
              '伤兵 ' + fmt(row.wounded || 0)
            ];
            if (captured > 0) {
              parts.push('被俘 ' + fmt(captured));
            }
            parts.push('永久 ' + fmt(row.permanent || 0));
            out += '<div><span>' + G.escapeHtml(def ? def.name : unit) + '</span><span>' + parts.join(' · ') + '</span></div>';
            count++;
          });
          return count > 0 ? out + '</div>' : '';
        };
        h += renderBreakdown('本方战损分账', breakdown) + renderBreakdown('敌方战损分账', enemyBreakdown);
        h += '<div class="rb-settlement-note" style="padding-left:0;padding-right:0;">被俘部队只显示在胜方接收城市的俘虏池，败方不能招募；未收容数量已计入永久损失。</div>';
        h += '</details>';
        h += '<div class="rb-settlement-note">伤兵将在 7 天内保留，可前往伤兵营治疗归队。</div></div>';
      } else {
        h += '</div><div class="rb-settlement-note">伤兵将在 7 天内保留，可前往伤兵营治疗归队。</div></div>';
      }
      return h;
    },

    renderScoutReportCard: function (r) {
      if (r.summaryPending) return this.renderPendingReportCard(r);
      var d = new Date(r.time);
      var ts = (d.getMonth() + 1) + '-' + String(d.getDate()).padStart(2, '0') + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
      var data = r.data || {};
      var isDefense = data.perspective === 'defender';
      var isWin = isDefense ? data.intercepted : data.showCityInfo;
      var isDraw = data.result === 'draw';
      var player = Core.state.player || {};
      var attackerName = data.attackerName || (isDefense ? '未知敌军' : (player.name || player.username || '我方'));
      var unread = !r.readAt;
      var h = '';
      h += '<div class="report-card ' + (isDraw ? 'draw' : (isWin ? 'win' : 'lose')) + (unread ? ' unread' : '') + '">';
      h += '<div class="rc-head" onclick="Game.Battle.toggleReport(\'' + r.id + '\')" style="cursor:pointer">';
      h += '<span class="rc-subject">' + (unread ? '<span class="unread-dot"></span>' : '') + '侦查报告</span>';
      h += '<span class="rc-time">' + ts + '</span>';
      h += '<span class="rc-result ' + (isDraw ? 'draw' : (isWin ? 'w' : 'l')) + '">' + (isDraw ? (isDefense ? '平·被侦查' : '平·已获情报') : (isDefense ? (isWin ? '已拦截' : '被侦查') : (isWin ? '侦查成功' : '侦查失败'))) + '</span>';
      h += '</div>';
      h += '<div class="rc-body" onclick="Game.Battle.toggleReport(\'' + r.id + '\')" style="cursor:pointer">';
      h += '<div class="rc-line">' + G.escapeHtml(attackerName) + ' → ' + G.escapeHtml(data.targetName || '目标') + ' (' + G.escapeHtml(String(data.x == null ? '?' : data.x)) + ',' + G.escapeHtml(String(data.y == null ? '?' : data.y)) + ')</div>';
      h += '<div class="rc-line">' + this.scoutReportInfo(data).mode + '</div>';
      h += '</div>';
      h += '<div id="rdetail_' + r.id + '" class="rc-expand" style="display:none"></div>';
      h += '<div class="btn-row" style="margin-top:6px"><button id="rcta_' + r.id + '" class="btn sm" onclick="Game.Battle.viewReportDetail(\'' + r.id + '\')">' + (r.detailsAvailable === false ? '查看侦查摘要' : '查看完整战报') + '</button><button class="btn sm" onclick="Game.Battle.toggleStar(\'' + r.id + '\')">' + (r.starred ? '取消收藏' : '收藏战报') + '</button></div>';
      h += '</div>';
      return h;
    },

    /**
     * 按接收战报玩家的视角渲染结果、叙述、兵力与随军将领。
     * @param {Object} r - 战报数据
     * @param {boolean} isInstant - 是否为即时展示
     * @returns {string} 战报详情 HTML
     */
    renderReportBoard: function (r, isInstant) {
      var esc = G.escapeHtml;
      var d = new Date(r.time);
      var ts = (d.getMonth() + 1) + '-' + String(d.getDate()).padStart(2, '0') + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0') + ':' + String(d.getSeconds()).padStart(2, '0');
      var defense = this.isDefenderReport(r);
      var won = this.battleReportWon(r);
      var interception = this.interceptionOutcome(r);
      var h = '';
      h += '<div class="report-board ' + (won ? 'win' : 'lose') + '">';
      h += '<div class="rb-subject">【' + this.reportTitle(r) + '】' + esc(r.subject || '交锋战情') + '</div>';
      h += '<div class="rb-meta-box">';
      h += '<div class="rb-line"><b>出发地:</b> ' + esc(r.fromName || '我方') + this.reportCoord(r.fromCoord) + '</div>';
      h += '<div class="rb-line"><b>目的地:</b> ' + esc(r.toName || '目标') + this.reportCoord(r.toCoord) + '</div>';
      if (interception) {
        var openingText = this.interceptionOpeningText(r);
        if (openingText) h += '<div class="rb-line"><b>交火战术:</b> ' + esc(openingText) + '</div>';
        h += '<div class="rb-line"><b>拦截地点:</b>' + this.reportCoord(r.interceptCoord) + '</div>';
        h += '<div class="rb-line"><b>来袭部队:</b> ' + esc(r.attackerName || '未知敌军') + '</div>';
        h += '<div class="rb-line"><b>拦截部队:</b> ' + esc(r.defenderName || '未知敌军') + '</div>';
        h += '<div class="rb-line"><b>交战身份:</b> ' + (defense ? '我方出城拦截 · 敌方来袭突围' : '我方来袭突围 · 敌方出城拦截') + '</div>';
        if (r.originalAction === 'scout') h += '<div class="rb-line"><b>原行军任务:</b> 侦查 · 抵达原目标后执行</div>';
        h += '<div class="rb-line"><b>部队去向:</b> ' + interception.movement + '</div>';
      }
      // 新版战报保存了攻守视角与双方统帅快照；旧版仍沿用原详情布局，避免将敌军区名称提前到摘要中。
      if (r.perspective === 'attacker' || r.perspective === 'defender') {
        h += '<div class="rb-line"><b>敌方统帅:</b> ' + esc(this.enemyCommanderName(r)) + '</div>';
      }
      h += '<div class="rb-line"><b>时　间:</b> ' + ts + '</div>';
      var dispatchAction = r.action || (/^占领野地/.test(r.subject || '') ? 'conquer' : /^掠夺野地/.test(r.subject || '') ? 'plunder' : '');
      if (dispatchAction === 'conquer' || dispatchAction === 'plunder') {
        h += '<div class="rb-line"><b>行动配置:</b> ' + (dispatchAction === 'conquer' ? '尝试占领' : '仅夺取资源') + '</div>';
      }
      if (['feint', 'harass', 'guerrilla'].includes(dispatchAction)) {
        h += '<div class="rb-line"><b>行动配置:</b> ' + G.Constants.dispatchActionNames[dispatchAction] + ' · 不获取资源 · 最多 ' + G.Constants.battleRoundLimits[dispatchAction] + ' 回合</div>';
      }
      var occupation = defense ? null : this.wildOccupation(r);

      var conquestOutcome = this.conquestOutcome(r);
      var resText = defense ? (won ? '防守成功' : '防守失败') : (won ? '战斗大捷' : '战斗失利');
      if (interception) resText = interception.result;
      var takenOver = this.cityTakenOver(r);
      var defeatedNpc = won && (r.targetType === 'npc' || r.targetType === 'bandit');
      if (!defense && (takenOver || defeatedNpc)) resText = conquestOutcome.result;

      h += '<div class="rb-line"><b>' + (occupation ? '战斗结果' : '结　果') + ':</b> <span class="rb-res-badge ' + (won ? 'w' : 'l') + '">' + resText + '</span></div>';
      if (occupation) {
        h += '<div class="rb-line"><b>占领结果:</b> <span class="rb-res-badge ' + occupation.tone + '">' + occupation.text + '</span></div>';
        h += '<div class="rb-line">' + occupation.detail + '</div>';
        // 仅展示新战报持久化的实际部队去向；历史记录不能从当前驻军反推。
        var troopText = { stationed: '原地驻守', returning: '部队返城', destroyed: '无幸存部队' }[r.troopDisposition];
        if (troopText) h += '<div class="rb-line"><b>部队去向:</b> ' + troopText + '</div>';
      }
      if (r.targetType === 'player' && (dispatchAction === 'conquer' || dispatchAction === 'plunder')) {
        var takeoverText = takenOver ? '已接管' : dispatchAction === 'plunder' ? '未接管（仅夺取资源）'
          : typeof r.cityTakeoverReason === 'string' || (defense ? won : !won) ? '未接管' : '接管结果未记录';
        h += '<div class="rb-line"><b>接管结果:</b> ' + takeoverText + '</div>';
      }
      h += '</div>';
      h += '<div class="rb-divider"></div>';
      var actName = ({
        bandit: '讨伐',
        npc: '讨伐',
        player: '讨伐',
        wild: '讨伐',
        campaign: '战役'
      }[r.targetType] || '出征');
      var roundCount = this.reportRoundCount(r.roundLogs);
      var roundText = roundCount > 0 ? '，历经' + roundCount + '回合' : '';
      var narrative;
      if (interception) {
        narrative = '双方部队在城外拦截地点' + this.reportCoord(r.interceptCoord) + '发生交火' + roundText + '。' + interception.detail;
      } else if (defense) {
        narrative = esc(r.attackerName || '敌军') + ' 对我方城市 ' + esc(r.toName || '目标') + this.reportCoord(r.toCoord) + ' 发起了' + actName + roundText + '。';
        narrative += won ? ' 我方防守成功，已击退来袭部队！' : ' 我方防守失败，来袭部队突破了城防。';
      } else {
        // 使用战报保存的随军将领快照，缺少姓名时以我方部队表述。
        var commander = r.commanders && r.commanders.attacker;
        var armyName = commander && commander.name ? '我方 ' + esc(commander.name) + '将领率军 对 ' : '我方部队对 ';
        narrative = armyName + esc(r.toName || '目标') + this.reportCoord(r.toCoord) + ' 进行了' + actName + roundText + '。';
        narrative += won ? ' 我方攻势势如破竹，战役获得胜利！' : ' 我方遭受强烈阻击，战役未能获胜。';
      }
      h += '<div class="rb-narrative">' + narrative + '</div>';
      if (!defense && (takenOver || defeatedNpc)) h += '<div class="rb-line" style="color:#d97706;font-weight:600">' + conquestOutcome.detail + '</div>';
      if (defense && takenOver) h += '<div class="rb-line">城市已被敌方接管</div>';
      if (r.cityTakeoverReason) h += '<div class="rb-line"><b>未接管原因:</b> ' + esc(r.cityTakeoverReason) + '</div>';
      if (r.capturedOfficerId != null) h += '<div class="rb-line"><b>' + (defense ? '被俘指挥官' : '俘虏敌将') + ':</b> ' + esc(r.capturedOfficerName || '敌方指挥官') + (defense ? '，已转入敌方参谋部' : '，已转入我方参谋部，装备与培养数据完整保留') + '</div>';
      var pl = this._formatRes(r.plunder);
      var settlement = this.renderBattleSettlement(r, defense, pl);
      if (settlement) h += settlement;
      else {
        if (pl) h += '<div class="rb-line"><b>' + (defense ? '损失资源' : '掠夺资源') + ':</b> ' + pl + '</div>';
        if (!defense && r.exp > 0) h += '<div class="rb-line"><b>获得经验:</b> ' + G.fmt(r.exp) + '</div>';
      }
      var enemyName = defense ? (r.attackerName || '') : (r.toName || r.defenderName || '');
      var enemyIsJapanese = r.targetType === 'bandit' || r.targetType === 'npc' ||
        (enemyName && (enemyName.indexOf('日寇') >= 0 || enemyName.indexOf('流寇') >= 0 || enemyName.indexOf('据点') >= 0));
      h += '<div class="rb-divider"></div>';
      h += '<div class="rb-side w">【我方军队】</div>';
      h += this.renderTroopCommander('mine', r.commanders && r.commanders[defense ? 'defender' : 'attacker']);
      h += this.renderArmyUnits('mine', r[defense ? 'initialDefender' : 'initialAttacker'], r[defense ? 'survivorDefender' : 'survivorAttacker'], r.roundLogs, defense ? 'enemy' : 'mine', false);
      h += '<div class="rb-divider"></div>';
      h += '<div class="rb-side l">【敌方军队】</div>';
      h += this.renderTroopCommander('enemy', r.commanders && r.commanders[defense ? 'attacker' : 'defender']);
      h += this.renderArmyUnits('enemy', r[defense ? 'initialAttacker' : 'initialDefender'], r[defense ? 'survivorAttacker' : 'survivorDefender'], r.roundLogs, defense ? 'mine' : 'enemy', enemyIsJapanese);
      h += '</div>';
      return h;
    },

    renderTroopCommander: function (side, cmd) {
      var esc = G.escapeHtml;
      var h = '<div class="rb-cmd-banner ' + side + '">';
      if (!cmd || !cmd.name) {
        h += '<div class="rb-cmd-row"><span class="rb-cmd-tag">🎖️ 随军将领:</span> <span class="rb-cmd-val rc-dim">无将领参战</span></div>';
      } else {
        var name = cmd.name || '未命名将领';
        var lv = cmd.level != null ? cmd.level : 0;
        var mil = cmd.military != null ? cmd.military : 0;
        var baseMil = cmd.baseMilitary != null ? cmd.baseMilitary : mil;
        var milText = '军事 ' + mil + (baseMil !== mil ? ' (基础' + baseMil + ')' : '');

        h += '<div class="rb-cmd-row">';
        h += '<span class="rb-cmd-tag">🎖️ 随军将领:</span> ';
        h += '<span class="rb-cmd-name"><b>' + esc(name) + '</b> <span class="rb-cmd-lv">Lv.' + lv + '</span></span> ';
        h += '<span class="rb-cmd-mil">(' + milText + ')</span>';
        h += '</div>';

        if (Array.isArray(cmd.skills) && cmd.skills.length > 0) {
          var skillStrs = [];
          for (var i = 0; i < cmd.skills.length; i++) {
            var sk = cmd.skills[i];
            if (sk && sk.name) {
              skillStrs.push(esc(sk.name) + ' Lv.' + (sk.level != null ? sk.level : 1));
            }
          }
          if (skillStrs.length) {
            h += '<div class="rb-cmd-skills"><span class="rc-dim">将领特技:</span> ' + skillStrs.join('、') + '</div>';
          }
        }
      }
      h += '</div>';
      return h;
    },

    /**
     * 渲染一方兵力；logSide 指定原始战斗日志中的攻守称谓，支持防守视角交换敌我。
     * @param {string} side - 页面展示侧 mine/enemy
     * @param {Object} initMap - 初始兵力
     * @param {Object} survMap - 幸存兵力
     * @param {string[]} roundLogs - 原始回合日志
     * @param {string} [logSide] - 日志中的对应侧 mine/enemy
     * @returns {string} 兵力 HTML
     */
    renderArmyUnits: function (side, initMap, survMap, roundLogs, logSide, isJapanese) {
      var esc = G.escapeHtml;
      var h = '';
      var has = false;
      var sourceSide = logSide || side;

      function findUnitIdByName(name) {
        if (!name) return null;
        name = name.trim();
        // 已保存的纯文本战报不会随改名更新；反查时将旧通称映射到当前通称，避免遗漏兵力与战损。
        if (D.units.rocket && /^火箭(?:-|$)/.test(name)) {
          name = name.replace(/^火箭/, D.units.rocket.name.split('-')[0]);
        }
        for (var k in D.units) {
          var unit = D.units[String(k).split('~')[0]];
          // 同时接受完整名称和首个“-”前的兵种通称，兼容不同版本的日志。
          if (unit && (unit.name === name || unit.name.split('-')[0] === name)) return k;
        }
        if (D.japaneseUnits) {
          for (var jk in D.japaneseUnits) {
            var junit = D.japaneseUnits[jk];
            if (junit && (junit.name === name || junit.name.split('-')[0] === name)) return jk;
          }
        }
        if (D.forts) {
          for (var fk in D.forts) {
            if (D.forts[fk] && D.forts[fk].name === name) return fk;
          }
        }
        return null;
      }

      // 1. 从 roundLogs 全面深度解析所有战斗行动，提取双方初始最高兵力与被击毁战损
      var logMaxCount = {};
      var logTotalKilled = {};

      if (Array.isArray(roundLogs)) {
        for (var i = 0; i < roundLogs.length; i++) {
          var line = String(roundLogs[i] || '').trim();
          if (!line || line.indexOf('--') === 0 || line.indexOf('★') === 0 || line.indexOf('✗') === 0) continue;

          // 模式 A: 攻击行动行 (包含攻方兵力、被击方兵力 beforeKill、以及具体击毁数 kills)
          // 例: "敌方重型坦克(33)炮击我轻型坦克(11) [相克 贴脸] 伤害1125 击毁8"
          // 例: "我方侦察机(835)侦察敌榴弹炮(69) [贴脸] 伤害445 击毁5"
          var atkMatch = line.match(/^(我方|敌方)(.+?)\((\d+)\).*?(我|敌)(.+?)\((\d+)\).*?击毁(\d+)/);
          if (atkMatch) {
            var aSide = atkMatch[1] === '我方' ? 'mine' : 'enemy';
            var aName = atkMatch[2].trim();
            var aCount = parseInt(atkMatch[3], 10);
            var aUid = findUnitIdByName(aName);
            if (aUid && aSide === sourceSide) {
              logMaxCount[aUid] = Math.max(logMaxCount[aUid] || 0, aCount);
            }

            var dSide = (atkMatch[4] === '我' || atkMatch[4] === '我方') ? 'mine' : 'enemy';
            var dName = atkMatch[5].trim();
            var dBefore = parseInt(atkMatch[6], 10);
            var dKills = parseInt(atkMatch[7], 10);
            var dUid = findUnitIdByName(dName);
            if (dUid && dSide === sourceSide) {
              logMaxCount[dUid] = Math.max(logMaxCount[dUid] || 0, dBefore);
              logTotalKilled[dUid] = (logTotalKilled[dUid] || 0) + dKills;
            }
            continue;
          }

          // 模式 B: 移动/就位行 (无被击毁数据)
          // 例: "我方反坦克火箭车(1054) 前进 200 距离->350"
          // 例: "我方卡车(1000) 前进0(射程内) 距离0"
          var moveMatch = line.match(/^(我方|敌方)(.+?)\((\d+)\)/);
          if (moveMatch) {
            var mSide = moveMatch[1] === '我方' ? 'mine' : 'enemy';
            if (mSide === sourceSide) {
              var mName = moveMatch[2].trim();
              var mCount = parseInt(moveMatch[3], 10);
              var mUid = findUnitIdByName(mName);
              if (mUid) {
                logMaxCount[mUid] = Math.max(logMaxCount[mUid] || 0, mCount);
              }
            }
          }
        }
      }

      // 2. 汇总所有参战兵种 (initMap、survMap 以及日志中记录的所有参战单位)
      var unitKeys = [];
      var seen = {};

      function registerKey(k) {
        if (k && !seen[k]) {
          seen[k] = true;
          unitKeys.push(k);
        }
      }

      if (initMap) {
        for (var ik in initMap) if (initMap[ik] > 0) registerKey(ik);
      }
      if (survMap) {
        for (var sk in survMap) if (survMap[sk] > 0) registerKey(sk);
      }
      for (var lk in logMaxCount) if (logMaxCount[lk] > 0) registerKey(lk);
      for (var tk in logTotalKilled) if (logTotalKilled[tk] > 0) registerKey(tk);

      // 3. 渲染每个兵种: 例如 卡车: 1000 -> 1000 (-0) 或 轻型坦克: 11 -> 3 (-8)
      for (var j = 0; j < unitKeys.length; j++) {
        var uid = unitKeys[j];
        var u = U(uid, isJapanese);
        if (!u) continue;
        has = true;

        var surv = (survMap && survMap[uid]) ? survMap[uid] : 0;
        var initFromMap = (initMap && initMap[uid] != null) ? initMap[uid] : null;
        var maxObs = logMaxCount[uid] || 0;
        var killed = logTotalKilled[uid] || 0;

        // 计算初始数量:
        // 若 initMap 存在，以 initMap 和日志中的最高观察值/伤亡逆推值中的最大者为准；
        // 若 initMap 不存在（历史旧战报），由日志最高值或 (幸存 + 击毁数) 逆推；
        var init = initFromMap != null
          ? Math.max(initFromMap, maxObs, surv + killed)
          : Math.max(maxObs, surv + killed, surv);

        var loss = Math.max(0, init - surv);

        var lossHtml = loss > 0
          ? '<span class="rb-loss-val lost">(-' + G.fmt(loss) + ')</span>'
          : '<span class="rb-loss-val zero">(-0)</span>';

        h += '<div class="rb-unit ' + side + '">';
        h += '<span class="rb-u-name">' + esc(u.name) + '</span>: ';
        h += '<span class="rb-u-init">' + G.fmt(init) + '</span>';
        h += ' <span class="rb-u-arrow">-></span> ';
        h += '<span class="rb-u-surv' + (surv === 0 ? ' zero' : '') + '">' + G.fmt(surv) + '</span> ';
        h += lossHtml;
        h += '</div>';
      }

      if (!has) {
        h += '<div class="rb-unit rc-dim">无兵力记录</div>';
      }
      return h;
    },

    renderSurvivors: function (side, map) {
      return this.renderArmyUnits(side, null, map);
    },

    /** 新报告使用按兵种快照；旧报告缺字段时按侦察机数量恢复，保留历史报告可读性。 */
    scoutReportInfo: function (data) {
      var ground = data.scoutUnit === 'recon_car' || data.reconMode === 'land';
      var unit = ground ? 'recon_car' : 'scout';
      function count(map, fallback) {
        return Math.max(0, Number(map && map[unit] != null ? map[unit] : fallback) || 0);
      }
      var my = count(data.myArmy, data.myScouts), enemy = count(data.enemyArmy, data.enemyScouts);
      var myAlive = count(data.mySurvivors, my - (Number(data.myLost) || 0));
      var enemyAlive = count(data.enemySurvivors, enemy - (Number(data.enemyLost) || 0));
      return { unit: unit, name: ground ? '侦查装甲车' : '侦察机', measure: ground ? '辆' : '架',
        mode: ground ? '地面侦查' : '空中侦查', logTitle: ground ? '地面遭遇记录' : '空中遭遇记录',
        my: my, enemy: enemy, myAlive: myAlive, enemyAlive: enemyAlive,
        myLost: Math.max(0, my - myAlive), enemyLost: Math.max(0, enemy - enemyAlive), ground: ground };
    },

    /** 数量和战损按报告中的侦查方式展示，守方视角已由服务端交换双方快照。 */
    renderScoutForces: function (data, defense) {
      var info = this.scoutReportInfo(data);
      var h = '<div class="rb-divider"></div><div class="rb-side">【我方' + (defense ? '驻防' : '') + info.name + '】</div>';
      h += '<div class="rb-unit mine">' + (defense ? '驻守: ' : '出动: ') + info.my + ' ' + info.measure + ' ➔ 幸存: ' + info.myAlive + ' ' + info.measure + '（损失 ' + info.myLost + ' ' + info.measure + '）</div>';
      h += this.renderScoutWounded(data);
      if (defense && !info.my) h += '<div class="rb-line rc-dim">我方未驻防' + info.name + '，敌方未遭' + (info.ground ? '地面' : '空中') + '拦截。</div>';
      h += '<div class="rb-divider"></div><div class="rb-side">【敌方' + (defense ? '来袭' : '驻防') + info.name + '】</div>';
      h += '<div class="rb-unit enemy">' + (defense ? '出动: ' : '驻守: ') + info.enemy + ' ' + info.measure + ' ➔ 幸存: ' + info.enemyAlive + ' ' + info.measure + '（' + (info.ground ? '损失车辆 ' : '击落 ') + info.enemyLost + ' ' + info.measure + '）</div>';
      if (!defense && !info.enemy) h += '<div class="rb-line rc-dim">无对应' + (info.ground ? '地面' : '空中') + '拦截，成功获取情报。</div>';
      return h;
    },

    renderScoutEncounter: function (data) {
      if (!data.combatLog || !data.combatLog.length) return '';
      var h = '<div class="rb-divider"></div><div class="rb-side">【' + this.scoutReportInfo(data).logTitle + '】</div>';
      data.combatLog.forEach(function (line) {
        // 一条快照对应一个回合，回合内加成和反击逐行展示并分别转义。
        String(line).split('\n').forEach(function (part) {
          h += '<div class="rb-line rc-dim">' + G.escapeHtml(part) + '</div>';
        });
      });
      return h;
    },

    /** 根据报告保存的规则说明先手，仅新规则快照展示，避免把历史战损解释成新先手规则。 */
    renderScoutInitiative: function (data, perspective) {
      var snapshot = data.reconInitiative;
      if (!snapshot || ['recon-initiative-v2-20261015', 'recon-initiative-v3-20261016', 'recon-initiative-v5-20261019', 'recon-initiative-v6-20261019'].indexOf(snapshot.version) < 0) return '';
      var current = snapshot.version !== 'recon-initiative-v2-20261015';
      // 先手加值按战报保存的版本解释，避免本次雷达调整改变历史报告含义。
      var radarPerLevel = snapshot.version === 'recon-initiative-v6-20261019' ? 2
        : (snapshot.version === 'recon-initiative-v5-20261019' ? 1 : (current ? 5 : 2));
      var defending = perspective === 'defender';
      var mine = defending ? snapshot.defenderReconLevel : snapshot.attackerReconLevel;
      var enemy = defending ? snapshot.attackerReconLevel : snapshot.defenderReconLevel;
      return '<div class="rb-line rc-dim">侦查先手：双方按有效速度＋' + (current ? '5×' : '') + '自身侦察技术等级比较；守方雷达仅首回合每级+' + radarPerLevel + '点。同值时奇数回合出征方先手，偶数回合守方先手。</div>'
        + '<div class="rb-line rc-dim">我方侦察技术 Lv.' + G.escapeHtml(String(mine))
        + ' / 敌方侦察技术 Lv.' + G.escapeHtml(String(enemy)) + '；'
        + (defending ? '我方' : '敌方') + '雷达首回合先手+' + G.escapeHtml(String(Number(snapshot.defenderRadarLevel) * radarPerLevel)) + '。</div>';
    },

    /** 新侦查报告展示实际随队将领和技能周期；历史报告没有快照时保持原显示。 */
    renderScoutCommanders: function (data, perspective) {
      if (!data.commanders) return '';
      return '<div class="rb-line rc-dim">将领军事、防御与战斗技能：首次交火即生效，之后间隔两回合再触发（侦查第1、4、7、10回合）。每点军事、防御分别增加1%攻击、防御；装备、套装计入总属性。</div>'
        + this.renderCommanderPanel(data.commanders, perspective, true);
    },

    renderScoutDefenseReportBoard: function (r) {
      var data = r.data || {};
      var esc = G.escapeHtml;
      var info = this.scoutReportInfo(data);
      var intercepted = !!data.intercepted;
      var isDraw = data.result === 'draw';
      var when = new Date(r.time);
      var timeText = (when.getMonth() + 1) + '-' + when.getDate() + ' ' +
        [when.getHours(), when.getMinutes(), when.getSeconds()].map(function (n) { return String(n).padStart(2, '0'); }).join(':');
      var resultText = isDraw ? '战平，敌方已获取情报' : (intercepted ? '拦截成功，敌方未获取情报' : '敌方侦查成功，我方城市情报已被探查');
      var h = '<div class="report-board ' + (isDraw ? 'draw' : (intercepted ? 'win' : 'lose')) + '">';
      h += '<div class="rb-subject">【敌军侦查报告】' + esc(data.attackerName || '未知敌军') + '</div>';
      h += '<div class="rb-meta-box">';
      h += '<div class="rb-line"><b>侦查方式:</b> ' + info.mode + '</div>';
      h += '<div class="rb-line"><b>来袭玩家:</b> ' + esc(data.attackerName || '未知敌军') + '</div>';
      h += '<div class="rb-line"><b>出发坐标:</b> (' + (data.fromX == null ? '?' : data.fromX) + ',' + (data.fromY == null ? '?' : data.fromY) + ')</div>';
      h += '<div class="rb-line"><b>被侦查城市:</b> ' + esc(data.targetName || '我方城市') + ' (' + data.x + ',' + data.y + ')</div>';
      h += '<div class="rb-line"><b>发生时间:</b> ' + timeText + '</div>';
      h += '<div class="rb-line"><b>拦截结果:</b> <span class="rb-res-badge ' + (isDraw ? 'd' : (intercepted ? 'w' : 'l')) + '">' + resultText + '</span></div></div>';
      h += this.renderCommandBonusPanel(data.commandBonuses, 'defender');
      h += this.renderScoutCommanders(data, 'defender');
      h += this.renderScoutInitiative(data, 'defender');
      if (G.UnitEquipment) h += G.UnitEquipment.reportHtml(data.unitEquipment);
      h += this.renderScoutForces(data, true);
      h += this.renderScoutEncounter(data);
      return h + '</div>';
    },

    renderScoutWounded: function (data) {
      var h = '';
      ['scout', 'recon_car'].forEach(function (unit) {
        var count = Math.max(0, Number(data.wounded && data.wounded[unit]) || 0);
        if (count) h += '<div class="rb-line">我方伤兵入营：' + (unit === 'scout' ? '侦察机 ' : '侦查装甲车 ') + G.fmt(count) + (unit === 'scout' ? ' 架' : ' 辆') + '（7天内可付费治疗）</div>';
      });
      return h;
    },

    renderScoutReportBoard: function (r) {
      if (r.data && r.data.perspective === 'defender') return this.renderScoutDefenseReportBoard(r);
      var esc = G.escapeHtml;
      var d = new Date(r.time);
      var ts = (d.getMonth() + 1) + '-' + String(d.getDate()).padStart(2, '0') + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0') + ':' + String(d.getSeconds()).padStart(2, '0');
      var data = r.data || {};
      var info = this.scoutReportInfo(data);
      var isWin = data.showCityInfo;
      var isDraw = data.result === 'draw';
      var isZeroEnemy = (info.enemy === 0);
      var resultText = {
        overwhelming_defeat: '惨败（敌军势大）',
        close_match_loss: '失败（激战落败）',
        draw: '战平（双方' + info.name + '均存活）',
        close_match_win: '险胜（激战获胜）',
        overwhelming_victory: (isZeroEnemy ? '大胜（无对应拦截）' : '大胜（碾压全歼）')
      }[data.result] || (isWin ? (isZeroEnemy ? '大胜（无对应拦截）' : '大胜') : '侦查失败');
      var rLv = (data.reconLevel != null) ? data.reconLevel : 0;
      var tenLevelIntel = data.reconIntelligenceVersion === 'recon-intelligence-v2-20261019';
      var tierName = data.tierName || '常规侦查';

      var h = '';
      h += '<div class="report-board ' + (isDraw ? 'draw' : (isWin ? 'win' : 'lose')) + '">';
      h += '<div class="rb-subject">【侦查报告】' + esc(data.targetName || '?') + '</div>';
      h += '<div class="rb-meta-box">';
      h += '<div class="rb-line"><b>侦查目标:</b> ' + esc(data.targetName || '?') + ' (' + (data.x || 0) + ',' + (data.y || 0) + ')</div>';
      h += '<div class="rb-line"><b>发生时间:</b> ' + ts + '</div>';
      h += '<div class="rb-line"><b>侦查方式:</b> ' + info.mode + '</div>';
      h += '<div class="rb-line"><b>侦查技术:</b> <span class="rb-tier-tag">Lv.' + rLv + ' ' + esc(tierName) + '</span>' + '</div>';
      h += '<div class="rb-line"><b>侦查结果:</b> <span class="rb-res-badge ' + (isDraw ? 'd' : (isWin ? 'w' : 'l')) + '">' + resultText + '</span></div>';
      h += '<div class="rb-line"><b>情报获取:</b> ' + (isWin ? '成功获取当前科技允许的情报' : '侦查部队未能传回情报') + '</div>';
      h += '</div>';
      h += this.renderCommandBonusPanel(data.commandBonuses, 'attacker');
      h += this.renderScoutCommanders(data, 'attacker');
      h += this.renderScoutInitiative(data, 'attacker');
      if (G.UnitEquipment) h += G.UnitEquipment.reportHtml(data.unitEquipment);
      h += this.renderScoutForces(data, false);
      h += '<div class="rb-divider"></div>';
      if (data.combatLog && data.combatLog.length) {
        h += this.renderScoutEncounter(data);
        h += '<div class="rb-divider"></div>';
      }
      if (data.showCityInfo) {
        h += '<div class="rb-side w">【目标情报】</div>';
        if (data.commander) h += '<div class="rb-line"><b>统帅:</b> ' + esc(data.commander) + (tenLevelIntel && data.commanderStar ? ' ' + data.commanderStar + '星' : '') + (tenLevelIntel && data.commanderLevel ? ' Lv.' + data.commanderLevel : '') + '</div>';
        if (data.prestige != null) h += '<div class="rb-line"><b>声望:</b> ' + G.fmt(data.prestige) + '</div>';
        if (data.cityDesc) h += '<div class="rb-line"><b>简介:</b> ' + esc(data.cityDesc) + '</div>';
        if (data.lastActive) h += '<div class="rb-line"><b>活跃:</b> ' + esc(data.lastActive) + '</div>';

        // 基础资源
        if (data.resources) {
          var resStr = '';
          if (data.resources.food) resStr += '粮' + G.fmt(data.resources.food) + ' ';
          if (data.resources.steel) resStr += '钢' + G.fmt(data.resources.steel) + ' ';
          if (data.resources.oil) resStr += '油' + G.fmt(data.resources.oil) + ' ';
          if (data.resources.rare) resStr += '稀' + G.fmt(data.resources.rare) + ' ';
          if (data.resources.gold) resStr += '金' + G.fmt(data.resources.gold) + ' ';
          if (resStr) h += '<div class="rb-line"><b>资源储量:</b> ' + resStr.trim() + '</div>';
        }

        // 可掠夺测算 (Lv.4+)
        if (data.plunderable) {
          var pStr = '';
          if (data.plunderable.food) pStr += '粮' + G.fmt(data.plunderable.food) + ' ';
          if (data.plunderable.steel) pStr += '钢' + G.fmt(data.plunderable.steel) + ' ';
          if (data.plunderable.oil) pStr += '油' + G.fmt(data.plunderable.oil) + ' ';
          if (data.plunderable.rare) pStr += '稀' + G.fmt(data.plunderable.rare) + ' ';
          if (data.plunderable.gold) pStr += '金' + G.fmt(data.plunderable.gold) + ' ';
          h += '<div class="rb-line" style="color:#ffe14a"><b>预计可掠夺:</b> ' + (pStr.trim() || '无防守资源溢出(全受仓库保护)') +
               (data.warehouseProtection > 0 ? ' <span class="rb-dim">(地窖保护 ' + G.fmt(data.warehouseProtection) + ')</span>' : '') + '</div>';
        }

        // 新报告Lv.1仅识别类型，Lv.2才展示数量和城墙等级；工事没有独立等级。
        if (data.wallLevel != null) h += '<div class="rb-line"><b>城墙:</b> ' + (data.wallLevel > 0 ? 'Lv.' + data.wallLevel : '无城墙') + '</div>';
        else if (data.wallPresent != null) h += '<div class="rb-line"><b>城墙:</b> ' + (data.wallPresent ? '已建成（等级未解锁）' : '无城墙') + '</div>';
        if (data.forts) {
          var fortStr = '';
          for (var fid in data.forts) {
            if (data.forts[fid] > 0) {
              var fu = D.forts && D.forts[fid];
              fortStr += (fu ? fu.name : fid) + 'x' + data.forts[fid] + ' ';
            }
          }
          h += '<div class="rb-line"><b>城防工事:</b> ' + (fortStr ? esc(fortStr.trim()) : '无防御工事') + '</div>';
        } else if (data.fortTypes) {
          h += '<div class="rb-line"><b>城防类型:</b> ' + esc(data.fortTypes.map(function (id) { return (D.forts[id] || {}).name || id; }).join('、') || '无城防工事') + '（数量未解锁）</div>';
        } else if (data.fortsVague) {
          h += '<div class="rb-line rc-dim"><b>城防工事:</b> ' + esc(data.fortsVague) + '</div>';
        }

        // 新报告Lv.3精确守军；野地仍在Lv.1解锁，历史报告按字段展示。
        var isJpnTarget = data.targetKind === 'npc' || data.targetKind === 'bandit' ||
          (data.targetName && (data.targetName.indexOf('日寇') >= 0 || data.targetName.indexOf('流寇') >= 0 || data.targetName.indexOf('据点') >= 0));
        if (data.army) {
          var armyUnits = [];
          for (var aid in data.army) {
            if (data.army[aid] > 0) {
              var au = U(aid, isJpnTarget);
              armyUnits.push(esc((au ? au.name : aid) + 'x' + data.army[aid]));
            }
          }
          if (armyUnits.length) {
            // 精确情报按兵种分行，避免兵种较多时因容器宽度而混排。
            h += '<div class="rb-line"><b>守军编制:</b> ' + armyUnits[0] + '</div>';
            for (var ai = 1; ai < armyUnits.length; ai++) {
              h += '<div class="rb-line">' + armyUnits[ai] + '</div>';
            }
          } else {
            h += '<div class="rb-line"><b>守军编制:</b> 无驻防部队</div>';
          }
        } else if (data.armyVague) {
          h += '<div class="rb-line rc-dim"><b>守军编制:</b> ' + esc(data.armyVague) + '</div>';
        }

        // 城市主要建筑（新报告Lv.5解锁，历史报告依据字段快照显示）
        if (data.buildings) {
          var bStr = '';
          for (var bid in data.buildings) {
            var binfo = D.buildings && D.buildings[bid];
            var blv = data.buildings[bid];
            if (blv > 0) {
              bStr += (binfo ? binfo.name : bid) + 'Lv.' + blv + ' ';
            }
          }
          if (bStr) h += '<div class="rb-line"><b>主要建筑:</b> ' + esc(bStr.trim()) + '</div>';
        }

        // 科研科技（新报告Lv.7解锁）
        if (data.techs) {
          var tStr = '';
          for (var tid in data.techs) {
            if (D.techs[tid] && data.techs[tid] > 0) {
              var tinfo = D.techs && D.techs[tid];
              tStr += (tinfo ? tinfo.name : tid) + 'Lv.' + data.techs[tid] + ' ';
            }
          }
          if (tStr) h += '<div class="rb-line"><b>军事科研:</b> ' + esc(tStr.trim()) + '</div>';
        }

        // 驻留将领（新报告Lv.6数量、Lv.9档案）
        if (data.officers && data.officers.length) {
          h += '<div class="rb-line"><b>驻留将领:</b> 共 ' + data.officers.length + ' 名</div>';
          for (var oi = 0; oi < data.officers.length; oi++) {
            var off = data.officers[oi];
            var starIcons = off.star ? '★'.repeat(off.star) : '';
            h += '<div class="rb-unit enemy" style="padding-left:12px;font-size:11px;">' +
                 '[' + (off.role === 'commander' ? '统帅' : off.role === 'commander_in_chief' ? '司令官' : '军官') + '] ' +
                 (starIcons ? '<span style="color:#ffe14a">' + starIcons + '</span> ' : '') +
                 esc(off.name || '无名将领') + ' Lv.' + (off.level || 1) +
                 ' (军' + (off.military || 0) + ' 学' + (off.knowledge || 0) + ' 后' + (off.logistics || 0) + ')' +
                 '</div>';
          }
        } else if (data.officerCount != null) {
          h += '<div class="rb-line"><b>驻留将领:</b> 共 ' + data.officerCount + ' 名 <span class="rb-dim">(将领档案需 Lv.' + (tenLevelIntel ? 9 : 5) + ' 解锁)</span></div>';
        }

        // 防守综合战力（新报告Lv.10解锁）
        if (data.defensePower != null) {
          h += '<div class="rb-line" style="color:#ffb870"><b>防守战力:</b> ' + G.fmt(data.defensePower) +
               ' <span class="rb-threat-badge">' + esc(data.threatLevel || '') + '</span></div>';
        }

        // 迷雾锁定提示 (仅对城市类目标展示)
        if (data.targetKind !== 'wild' && data.targetKind !== 'wild_gather') {
          var locks = [];
          // 历史报告按保存的五级权限解释，新报告才使用十级解锁。
          if (tenLevelIntel) {
            var unlocks = ['城防类型', '城防数量与等级', '精确守军兵力', '守城将领姓名星级等级', '主要建筑等级', '驻城将领人数', '账号科技', '可掠夺资源测算', '驻城将领档案', '综合战力评估'];
            unlocks.forEach(function (label, index) { if (rLv < index + 1) locks.push(label + ' (需 侦察技术 Lv.' + (index + 1) + ')'); });
          } else {
          if (rLv < 1) locks.push('外围城防工事 (需 侦察技术 Lv.1)');
          if (rLv < 2) locks.push('精确守军兵力与统帅 (需 侦察技术 Lv.2)');
          if (rLv < 3) locks.push('主要城建建筑等级 (需 侦察技术 Lv.3)');
          if (rLv < 4) locks.push('战略科技与可掠夺测算 (需 侦察技术 Lv.4)');
          if (rLv < 5) locks.push('将领全维档案与综合战力 (需 侦察技术 Lv.5)');
          }
          if (locks.length) {
            h += '<div class="rb-fog-box">';
            h += '<div class="rb-line rc-dim" style="font-size:12px;"><b>🔒 侦测迷雾（未达标情报）:</b></div>';
            for (var li = 0; li < locks.length; li++) {
              h += '<div class="rb-fog-item rc-dim">• ' + esc(locks[li]) + '</div>';
            }
            h += '</div>';
          }
        }
      } else {
        h += '<div class="rb-line rc-dim">★ 未能获取目标内部情报</div>';
      }
      h += '</div>';
      return h;
    },

    renderForceList: function (side, startMap, lossMap, isWinner) {
      var esc = G.escapeHtml;
      var h = '';
      var hasAny = false;
      for (var uid in startMap) {
        if (startMap[uid] > 0) {
          hasAny = true;
          var u = U(uid);
          if (!u) continue;
          var startN = startMap[uid] || 0;
          var lossN = (lossMap && lossMap[uid]) || 0;
          var endN = Math.max(0, startN - lossN);
          h += '<div class="rb-unit ' + side + '">' + esc(u.name) + ': ' + G.fmt(startN) + ' -> ' + G.fmt(endN) + '( -' + G.fmt(lossN) + ' )</div>';
        }
      }
      if (!hasAny) h += '<div class="rb-unit rc-dim">无兵力记录</div>';
      return h;
    },

    /**
     * 切换战报卡片预览，并让卡片底部入口与当前展开状态保持一致。
     * @param {string|number} reportId - 战报 ID
     */
    toggleReport: function (reportId) {
      var box = document.getElementById('rdetail_' + reportId);
      var actionButton = document.getElementById('rcta_' + reportId);
      if (!box) return;
      if (box.style.display === 'none') {
        var r = this.findReport(reportId);
        if (!r) return;
        var self = this;
        var needsFetch = (r.summaryOnly || r.detailsAvailable === true && !Array.isArray(r.roundLogs)
          && !(r.data && (Array.isArray(r.data.roundLogs) || Array.isArray(r.data.combatLog)))) && G.API.getReportDetail;
        if (needsFetch) {
          box.innerHTML = '<div class="rc-line">正在加载战报详情…</div>';
          this.reportDetail(r).then(function (detail) {
            if (box.style.display !== 'block') return;
            box.innerHTML = self.renderReportPreview(detail);
          }).catch(function (err) { box.innerHTML = '<div class="rc-line">' + G.escapeHtml(err.message || '详情加载失败') + '</div>'; });
        } else {
          box.innerHTML = this.renderReportPreview(r);
        }
        box.style.display = 'block';
        if (actionButton) actionButton.innerHTML = r.detailsAvailable === false ? '查看战果摘要' : (r.type === 'scout' ? '查看侦查详情' : '查看战斗详情');
        // 展开预览即视为已读
        this.markOneRead(reportId);
      } else {
        box.style.display = 'none';
        if (actionButton) actionButton.innerHTML = '查看完整战报';
      }
    },

    renderReportPreview: function (report) {
      if (report.summaryUnavailable) return '<div class="rb-line">详细日志已过期，旧战果摘要尚未通过完整性校验，原始记录暂时保留等待核查。</div>';
      var html = report.type === 'scout' ? this.renderScoutReportBoard(report) : this.renderReportBoard(report, false);
      if (report.detailsAvailable === false) html += '<div class="rb-line rc-dim">详细日志已过期，以上为保留的战果摘要。</div>';
      return html;
    },

    /** 旧记录尚未完成摘要回填时，不用缺失字段推断胜负或历史兵力。 */
    renderPendingReportCard: function (report) {
      return '<div class="report-card"><div class="rc-head">战报摘要整理中</div><div class="rc-body">' + G.escapeHtml(report.targetName || report.data && report.data.targetName || '历史战报') + '</div><button class="btn sm" onclick="Game.Battle.viewReportDetail(\'' + report.id + '\')">查看战报</button></div>';
    },

    /** 分享菜单保留论坛入口，私聊可选择最近会话或输入完整玩家名。 */
    shareReport: function (reportId) {
      var old = document.getElementById('reportShareDialog');
      if (old) old.remove();
      var dialog = document.createElement('dialog');
      dialog.id = 'reportShareDialog';
      dialog.innerHTML = '<h3>分享战报</h3><div class="btn-row"><button class="btn" data-channel="forum">论坛</button><button class="btn" data-channel="world">世界聊天</button><button class="btn" data-channel="guild">军团聊天</button></div><h4>私聊</h4><select id="reportSharePeer"><option value="">选择最近会话</option></select><input id="reportShareName" maxlength="50" placeholder="或输入完整玩家名"><button class="btn" data-channel="private">分享到私聊</button><button class="btn" data-close>取消</button>';
      document.body.appendChild(dialog);
      dialog.showModal();
      dialog.querySelector('[data-close]').onclick = function () { dialog.close(); dialog.remove(); };
      G.API.privateChatConversations().then(function (rows) {
        rows.forEach(function (c) {
          var option = document.createElement('option');
          option.value = c.peer.id; option.textContent = c.peer.username;
          dialog.querySelector('select').appendChild(option);
        });
      }).catch(function () {});
      dialog.querySelectorAll('[data-channel]').forEach(function (button) {
        button.onclick = function () {
          var channel = button.getAttribute('data-channel');
          if (channel === 'forum') { Core.openForum({ shareReportId: reportId }); dialog.close(); dialog.remove(); return; }
          var name = dialog.querySelector('input').value.trim();
          var selected = dialog.querySelector('select').value;
          if (channel === 'private' && !name && !selected) { G.toast('请选择或输入私聊玩家'); return; }
          button.disabled = true;
          var recipient = channel === 'private' && name ? G.API.privateChatPlayer(name) : Promise.resolve({ id: selected ? Number(selected) : null });
          recipient.then(function (peer) { return G.API.shareBattleReport(reportId, channel, peer.id); }).then(function () {
            G.toast('战报已分享'); dialog.close(); dialog.remove();
          }).catch(function (err) { G.toast(err.message || '分享失败'); }).finally(function () { button.disabled = false; });
        };
      });
    },

    /** 接收方读取分享快照，不修改持有人的战报或已读状态。 */
    viewSharedReport: function (token) {
      var context = this.ensureRetentionContext();
      return G.API.sharedBattleReport(token).then(function (report) {
        if (G.Battle.retentionContext() !== context) return;
        G.Battle._viewReport = report;
        Core.history.push(Core.route);
        Core.route = 'reportDetail';
        Core.render();
      }).catch(function (err) { G.toast(err.message || '分享战报加载失败'); });
    },

    viewReportDetail: function (reportId) {
      var r = this.findReport(reportId);
      if (!r) { G.toast('战报不存在'); return; }
      this.markOneRead(reportId);
      var self = this;
      var needsFetch = (r.summaryOnly || r.detailsAvailable === true && !Array.isArray(r.roundLogs)
        && !(r.data && (Array.isArray(r.data.roundLogs) || Array.isArray(r.data.combatLog)))) && G.API.getReportDetail;
      function show(detail) {
        self._refreshOnReturn = true;
        self._viewReport = detail;
        Core.history.push(Core.route);
        Core.route = 'reportDetail';
        Core.render();
      }
      if (!needsFetch) { show(r); return; }
      this.reportDetail(r).then(show).catch(function (err) { G.toast(err.message || '战报详情加载失败'); });
    },

    findReport: function (reportId) {
      var reports = Core.state.reports || [];
      var key = String(reportId);
      for (var i = 0; i < reports.length; i++) {
        if (reports[i].id != null && String(reports[i].id) === key) return reports[i];
      }
      return null;
    },

    /** 司令官与带兵将领分开展示；战报只读开战快照，旧战报无字段时省略。 */
    renderCommandBonusPanel: function (bonuses, perspective) {
      if (!bonuses) return '';
      var esc = G.escapeHtml;
      var defense = perspective === 'defender';
      var h = '<div class="commander-panel command-bonus-panel">';
      [['我方司令官', defense ? 'defender' : 'attacker'], ['敌方司令官', defense ? 'attacker' : 'defender']].forEach(function (entry) {
        var chief = bonuses[entry[1]];
        h += '<div class="commander-card"><div class="commander-card-title">' + entry[0] + '</div>';
        if (!chief || chief.officerId == null) {
          h += '<div class="commander-empty">未任命，常驻加成为0</div>';
        } else {
          h += '<div class="commander-name">' + esc(chief.name || '未命名司令官') + '</div>';
          [['military', '攻击'], ['defense', '防御'], ['logistics', '生命'], ['knowledge', '机动']].forEach(function (attr) {
            var rate = chief.rate == null ? G.Constants.commandBonusRate : Number(chief.rate);
            h += '<div>' + attr[1] + ' +' + (Math.max(0, Number(chief[attr[0]]) || 0) * rate * 100).toFixed(1) + '%</div>';
          });
          h += '<div class="commander-no-skills">装备、套装计入；技能不参与</div>';
        }
        h += '</div>';
      });
      return h + '</div>';
    },

    renderCommanderPanel: function (commanders, perspective, scouting) {
      var esc = G.escapeHtml;
      var h = '<div class="commander-panel">';

      function renderSide(title, commander, sideClass) {
        var out = '<div class="commander-card ' + sideClass + '">';
        out += '<div class="commander-card-title">' + title + '</div>';
        if (!commander) {
          out += '<div class="commander-empty">无将领参战</div></div>';
          return out;
        }

        var name = commander.name != null ? commander.name : '未命名将领';
        var level = commander.level != null ? commander.level : 0;
        var military = commander.military != null ? commander.military : 0;
        var baseMilitary = commander.baseMilitary != null ? commander.baseMilitary : military;
        out += '<div class="commander-name">' + esc(String(name)) + ' <span>Lv.' + esc(String(level)) + '</span></div>';
        out += '<div class="commander-military">总军事：' + esc(String(military));
        if (baseMilitary !== military) out += ' <span class="commander-base">（基础：' + esc(String(baseMilitary)) + '）</span>';
        out += '</div>';
        if (commander.defense != null) {
          var defense = commander.defense;
          var baseDefense = commander.baseDefense != null ? commander.baseDefense : defense;
          out += '<div class="commander-defense">总防御：' + esc(String(defense));
          if (baseDefense !== defense) out += ' <span class="commander-base">（基础：' + esc(String(baseDefense)) + '）</span>';
          out += '</div>';
        }

        var skills = Array.isArray(commander.skills) ? commander.skills : [];
        if (skills.length) {
          out += '<div class="commander-skills">';
          for (var i = 0; i < skills.length; i++) {
            var skill = skills[i] || {};
            var level = skill.level != null ? skill.level : 0;
            var description = commanderSkillBonus(skill, scouting);
            out += '<div class="commander-skill"><b>' + esc(String(skill.name || '未知技能')) + ' Lv.' + esc(String(level)) + '</b>';
            if (description) out += '<span>' + esc(description) + '</span>';
            out += '</div>';
          }
          out += '</div>';
        } else {
          out += '<div class="commander-no-skills">无技能信息</div>';
        }
        return out + '</div>';
      }

      if (perspective === 'defender') {
        h += renderSide('我方将领', commanders && commanders.defender, 'defender');
        h += renderSide('敌方将领', commanders && commanders.attacker, 'attacker');
      } else {
        h += renderSide('我方将领', commanders && commanders.attacker, 'attacker');
        h += renderSide('敌方将领', commanders && commanders.defender, 'defender');
      }
      return h + '</div>';
    },

    toggleBattleDetails: function () {
      var box = document.getElementById('battleDetailsBox');
      var btn = document.getElementById('btnBattleDetails');
      if (!box) return;
      var isHidden = box.style.display === 'none';
      box.style.display = isHidden ? 'block' : 'none';
      if (btn) {
        var countBadge = btn.querySelector('.rb-details-count');
        var countHtml = countBadge ? countBadge.outerHTML : '';
        if (isHidden) {
          btn.innerHTML = '📜 收起战斗详情 ' + countHtml + ' <span class="rb-toggle-arrow">▴</span>';
          btn.classList.add('active');
        } else {
          btn.innerHTML = '📜 查看战斗详情 ' + countHtml + ' <span class="rb-toggle-arrow">▾</span>';
          btn.classList.remove('active');
        }
      }
    },

    /**
     * 从军情打开已到达的战术战斗，恢复本场指令；未下令兵种使用账号默认战术。
     * @param {number} marchId - 战斗关联的行军 ID
     */
    openTactical: function (marchId) {
      var self = this;
      return G.API.getTacticalBattle(marchId).then(function (battle) {
        if (battle.cancelled) {
          G.toast(battle.message);
          if (G.API.getGameState) return G.API.getGameState(true).then(function (state) {
            G.API.applyState(state);
            Core.go('alerts');
          });
          Core.go('alerts');
          return;
        }
        self.resetTacticalPanel(battle.marchId);
        self._activeTactical = battle;
        self.resetTacticalOrders();
        Core.go('battle');
      }).catch(function (err) {
        G.toast(err.message || '战斗会话已结束或不可用');
      });
    },

    /** 初始化一次面板访问；回合恢复命令不重置当前 Tab 或日志展开状态。 */
    resetTacticalPanel: function (marchId) {
      this.stopTacticalTimer();
      this.clearTacticalUnitCommand();
      this._tacticalVisitVersion = (this._tacticalVisitVersion || 0) + 1;
      this._tacticalPanelMarchId = marchId;
      this._tacticalCommandView = 'battlefield';
      this._tacticalLogExpanded = false;
      this._tacticalDeadlineKey = null;
      this._tacticalDeadline = 0;
      this._tacticalRetryAt = 0;
      this._tacticalAutoExecuteFailed = false;
      this._tacticalAutoExecuteError = '';
      this._tacticalSubmitting = false;
    },

    /** 切换当前视图，只改变展示和焦点，不恢复或提交共享草稿。 */
    setTacticalCommandView: function (view) {
      if (view !== 'battlefield' && view !== 'text') return;
      this.clearTacticalUnitCommand();
      this._tacticalCommandView = view;
      Core.render();
      var tab = document.getElementById('tacticalTab-' + view);
      if (tab) tab.focus({ preventScroll: true });
    },

    /** 双 Tab 使用自动激活的方向键与首尾键，隐藏面板不参与焦点导航。 */
    onTacticalTabKeydown: function (event) {
      var view;
      if (event.key === 'Home') view = 'battlefield';
      else if (event.key === 'End') view = 'text';
      else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
        view = this._tacticalCommandView === 'text' ? 'battlefield' : 'text';
      } else return;
      event.preventDefault();
      this.setTacticalCommandView(view);
    },

    /** 两个视图共享记录展开状态，重绘不改变回合截止时间。 */
    toggleTacticalLog: function () {
      this._tacticalLogExpanded = !this._tacticalLogExpanded;
      Core.render();
      var button = document.getElementById('tacticalLogToggle');
      if (button) button.focus({ preventScroll: true });
    },

    /** 判断兵种是否属于仍存活的可指挥我军，装备组按基础兵种统一下令。 */
    canEditTacticalUnit: function (unitId) {
      var battle = this._activeTactical;
      if (!battle || battle.finished || this._tacticalSubmitting || unitId === 'wall') return false;
      var baseId = String(unitId).split('~')[0];
      return Object.keys(battle.attackerArmy || {}).some(function (id) {
        return String(id).split('~')[0] === baseId && battle.attackerArmy[id] > 0;
      });
    },

    /** 从服务端恢复本场持久指令，回合推进或重新打开面板都沿用玩家最后一次下令。 */
    resetTacticalOrders: function () {
      var battle = this._activeTactical || {};
      var army = battle.attackerArmy || {};
      var savedOrders = battle.orders || {};
      this._tacticalOrdersMarchId = battle.marchId;
      this._tacticalOrders = {};
      this.clearTacticalUnitCommand();
      this._tacticalAutoExecuteFailed = false;
      this._tacticalAutoExecuteError = '';
      this._tacticalRetryAt = 0;
      Object.keys(army).forEach(function (unitId) {
        if (army[unitId] <= 0) return;
        var baseId = String(unitId).split('~')[0];
        var order = savedOrders[baseId] || savedOrders[unitId] || {};
        // 同兵种的装备组共用面板命令，避免批量指令与单兵种指令互相覆盖。
        this._tacticalOrders[baseId] = { action: order.action || null, focusTarget: order.focusTarget || null };
      }, this);
    },

    /**
     * 为仍存活的我军兵种打开快捷指挥；装备组沿用同兵种统一命令。
     * @param {string} unitId - 点击的战场单位 ID，可包含装备组后缀。
     */
    openTacticalUnitCommand: function (unitId) {
      if (this._tacticalCommandView === 'text' || !this.canEditTacticalUnit(unitId)) return;
      var baseId = String(unitId).split('~')[0];
      this._tacticalCommandUnitId = baseId;
      this._tacticalCommandTriggerId = unitId;
      Core.render();
    },

    /** 清理挂在 body 下的快捷弹窗，不重绘页面，供回合结算和页面离开时调用。 */
    clearTacticalUnitCommand: function () {
      this._tacticalCommandUnitId = null;
      if (this._tacticalCommandPopup) this._tacticalCommandPopup.remove();
      this._tacticalCommandPopup = null;
    },

    /** 关闭快捷指挥并恢复战场图标焦点；关闭本身不修改任何命令。 */
    closeTacticalUnitCommand: function () {
      var triggerId = this._tacticalCommandTriggerId;
      this.clearTacticalUnitCommand();
      Core.render();
      var markers = document.querySelectorAll('[data-tactical-command-unit]');
      for (var i = 0; i < markers.length; i++) {
        if (markers[i].getAttribute('data-tactical-command-unit') === triggerId) {
          markers[i].focus({ preventScroll: true });
          break;
        }
      }
    },

    /** 限制快捷弹窗内的键盘焦点，并阻止战场快捷键误触页面导航。 */
    onTacticalCommandKeydown: function (event) {
      event.stopPropagation();
      if (event.key === 'Escape') {
        event.preventDefault();
        this.closeTacticalUnitCommand();
        return;
      }
      if (event.key !== 'Tab') return;
      var controls = event.currentTarget.querySelectorAll('button:not(:disabled),select:not(:disabled)');
      var first = controls[0];
      var last = controls[controls.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    },

    /**
     * 仅为本场可指挥的我军生成点击与键盘入口；城墙和纯展示城防不下达命令。
     * @param {string} unitId - 战场单位 ID。
     * @param {string} side - 地图阵营，mine 表示玩家视角的我军。
     * @returns {string} 追加到图标或城防徽标上的 HTML 属性。
     */
    tacticalCommandAttributes: function (unitId, side) {
      var battle = this._activeTactical;
      if (side !== 'mine' || unitId === 'wall' || !battle || battle.finished || this._tacticalSubmitting
        || !(battle.attackerArmy || {})[unitId] || battle.attackerArmy[unitId] <= 0) return '';
      var esc = G.escapeHtml;
      var encodedId = esc(JSON.stringify(unitId));
      return ' role="button" tabindex="0" aria-haspopup="dialog" aria-controls="tacticalUnitCommand"'
        + ' aria-label="指挥' + esc((U(unitId) || {}).name || unitId) + '" data-tactical-command-unit="' + esc(unitId) + '"'
        + ' onclick="Game.Battle.openTacticalUnitCommand(' + encodedId + ')"'
        + ' onkeydown="if(event.key===\'Enter\'||event.key===\' \'){event.preventDefault();Game.Battle.openTacticalUnitCommand(' + encodedId + ');}"';
    },

    /** 地图图标展示共享草稿的简短状态；纯展示城防不伪装为可指挥兵种。 */
    tacticalOrderStatus: function (unitId, side) {
      var battle = this._activeTactical;
      if (side !== 'mine' || !battle || !(battle.attackerArmy || {})[unitId]) return '';
      var baseId = String(unitId).split('~')[0];
      var order = (this._tacticalOrders || {})[baseId] || {};
      var action = order.action || (battle.defaultActions || {})[baseId] || 'ADVANCE';
      var label = { ADVANCE: '前进', RETREAT: '后退', HOLD: '待命' }[action];
      var targetName = order.focusTarget === 'wall' ? '城墙' : (U(order.focusTarget) || {}).name || order.focusTarget;
      return label + (order.focusTarget ? ' · 集火' + String(targetName).slice(0, 4) : '');
    },

    /** 设置一个兵种的移动命令，提交后在本场后续回合持续生效。 */
    setTacticalAction: function (unitId, action) {
      if (!this.canEditTacticalUnit(unitId) || ['ADVANCE', 'RETREAT', 'HOLD'].indexOf(action) < 0) return;
      var baseId = String(unitId).split('~')[0];
      var order = this._tacticalOrders[baseId] || { focusTarget: null };
      order.action = action;
      this._tacticalOrders[baseId] = order;
      if (this._tacticalCommandUnitId) this.closeTacticalUnitCommand();
      else Core.render();
    },

    /**
     * 为当前所有存活的我军兵种设置相同的移动命令，保留各自的集火目标。
     * @param {string} action - 要下达的移动命令：ADVANCE、RETREAT 或 HOLD。
     */
    setAllTacticalActions: function (action) {
      var battle = this._activeTactical;
      if (!battle || battle.finished || this._tacticalSubmitting || ['ADVANCE', 'RETREAT', 'HOLD'].indexOf(action) < 0) return;
      var army = battle.attackerArmy || {};
      Object.keys(army).forEach(function (unitId) {
        if (!army[unitId] || army[unitId] <= 0) return;
        var baseId = String(unitId).split('~')[0];
        var order = this._tacticalOrders[baseId] || { focusTarget: null };
        order.action = action;
        this._tacticalOrders[baseId] = order;
      }, this);
      Core.render();
    },

    /** 设置一个兵种的可选集火目标；不选择时后端按常规规则索敌。 */
    setTacticalFocus: function (unitId, targetId) {
      if (!this.canEditTacticalUnit(unitId)) return;
      var battle = this._activeTactical;
      if (targetId === 'wall') {
        if (battle.side === 'defender' || battle.battlePhase !== 'ATTACK_WALL'
          || !battle.wall || Number(battle.wall.hpRemaining) <= 0) return;
      } else if (targetId && !((battle.defenderArmy || {})[targetId] > 0)
        && !((battle.forts || (battle.wall && battle.wall.forts) || {})[targetId] > 0 && battle.side !== 'defender')) return;
      var baseId = String(unitId).split('~')[0];
      var order = this._tacticalOrders[baseId] || { action: null };
      // 指定集火目标时需要显式机动命令，否则服务端无法区分本回合的集火覆盖。
      if (targetId && !order.action) {
        order.action = (this._activeTactical && this._activeTactical.defaultActions || {})[baseId] || 'ADVANCE';
      }
      order.focusTarget = targetId || null;
      this._tacticalOrders[baseId] = order;
      if (this._tacticalCommandUnitId) this.closeTacticalUnitCommand();
      else Core.render();
    },

    /** 按服务端回合截止时间更新显示；失败重试也使用同一计时器。 */
    updateTacticalCountdown: function () {
      var battle = this._activeTactical;
      var remaining = Math.max(0, (this._tacticalRetryAt || this._tacticalDeadline) - G.gameNow());
      var label = battle && battle.finished ? '已结束' : this._tacticalSubmitting ? '结算中…'
        : this._tacticalAutoExecuteFailed && !this._tacticalRetryAt ? '等待手动重试'
          : remaining > 0 ? Math.ceil(remaining / 1000) + ' 秒'
            : this._tacticalAutoExecuteFailed ? '正在重试…' : '结算中…';
      ['tacticalCountdown', 'tacticalUnitCommandCountdown'].forEach(function (id) {
        var countdown = document.getElementById(id);
        if (countdown) countdown.textContent = label;
      });
      return remaining;
    },

    /** 每场每回合仅保留一个计时器；Tab、弹窗与日志重绘不延长截止时间。 */
    startTacticalTimer: function () {
      var battle = this._activeTactical;
      if (!battle || battle.finished || this._tacticalSubmitting) {
        this.stopTacticalTimer();
        this.updateTacticalCountdown();
        return;
      }
      var serverDeadline = Number(battle.roundDeadlineAt);
      var key = battle.marchId + ':' + battle.round;
      if (this._tacticalDeadlineKey !== key) {
        this._tacticalDeadlineKey = key;
        // 兼容旧会话时也只在首次进入该回合生成截止时间，不能随重绘续期。
        this._tacticalDeadline = Number.isFinite(serverDeadline) && serverDeadline > 0 ? serverDeadline : G.gameNow() + 15000;
      }
      var self = this;
      function update() {
        if (Core.route && Core.route !== 'battle') { self.stopTacticalTimer(); return; }
        var remaining = self.updateTacticalCountdown();
        if (remaining <= 0 && (!self._tacticalAutoExecuteFailed || self._tacticalRetryAt)) self.executeTacticalRound(true);
      }
      if (!this._tacticalTimer && (!this._tacticalAutoExecuteFailed || this._tacticalRetryAt)) this._tacticalTimer = setInterval(update, 250);
      update();
    },

    /** 离开战斗页或提交回合时清理倒计时，避免旧战斗继续发请求。 */
    stopTacticalTimer: function () {
      if (this._tacticalTimer) clearInterval(this._tacticalTimer);
      this._tacticalTimer = null;
      if (Core.route && Core.route !== 'battle') {
        this.clearTacticalUnitCommand();
        this._tacticalDeadline = 0;
        this._tacticalDeadlineKey = null;
        this._tacticalRetryAt = 0;
        this._tacticalCommandView = 'battlefield';
        this._tacticalLogExpanded = false;
        this._tacticalPanelMarchId = null;
        this._tacticalSubmitting = false;
        // 离开后返回的旧请求不应恢复页面、命令或自动重试。
        this._tacticalVisitVersion = (this._tacticalVisitVersion || 0) + 1;
      }
    },

    /** 提交当前面板上的命令；一次请求只推进一回合。 */
    executeTacticalRound: function (automatic) {
      var self = this;
      var battle = this._activeTactical;
      if (!battle || battle.finished || this._tacticalSubmitting) return;
      if (this._tacticalPanelMarchId !== battle.marchId) {
        this.resetTacticalPanel(battle.marchId);
        if (this._tacticalOrdersMarchId !== battle.marchId) this.resetTacticalOrders();
      }
      // 截止时间到达时关闭旧回合弹窗，避免结算期间继续编辑已提交的命令。
      this.clearTacticalUnitCommand();
      this.stopTacticalTimer();
      this._tacticalSubmitting = true;
      this._tacticalRetryAt = 0;
      this._tacticalAutoExecuteFailed = false;
      this._tacticalAutoExecuteError = '';
      var visit = this._tacticalVisitVersion;
      var submittedOrders = JSON.parse(JSON.stringify(this._tacticalOrders));
      Core.render();
      function isCurrent() {
        return self._tacticalVisitVersion === visit && self._activeTactical === battle
          && (!Core.route || Core.route === 'battle');
      }
      return G.API.commandTacticalBattle(battle.marchId, submittedOrders, battle.round).then(function (next) {
        if (!isCurrent()) return;
        self._tacticalSubmitting = false;
        self._activeTactical = next;
        // 两种视图共用最终战果和日志，普通战斗也保留结束画面。
        self.resetTacticalOrders();
        Core.render();
      }).catch(function (err) {
        if (!isCurrent()) return;
        self._tacticalSubmitting = false;
        // 自动结算遇到临时服务端/网络错误时必须重试；否则会永久卡在旧回合。
        self._tacticalAutoExecuteFailed = true;
        self._tacticalAutoExecuteError = err.message || '本回合指令未能执行';
        var retryable = !err.status || err.status >= 500;
        if (retryable) {
          self._tacticalRetryAt = G.gameNow() + 5000;
        }
        if (!automatic || !retryable) G.toast(self._tacticalAutoExecuteError);
        Core.render();
      });
    },

    /** 绘制地图两端的敌我部队、命令面板和最近的回合日志。 */
    renderTacticalBattle: function (v) {
      var self = this;
      var battle = this._activeTactical;
      if (!battle) { G.go('alerts'); return; }
      if (this._tacticalPanelMarchId !== battle.marchId) {
        this.resetTacticalPanel(battle.marchId);
        if (this._tacticalOrdersMarchId !== battle.marchId) this.resetTacticalOrders();
      }
      var commandView = this._tacticalCommandView || 'battlefield';
      var disabled = this._tacticalSubmitting ? ' disabled' : '';
      var esc = G.escapeHtml;
      var distance = Math.max(1, battle.initialDistance || 1);
      var attacker = battle.attackerArmy || {};
      var defender = Object.assign({}, battle.defenderArmy || {});
      var attackerPositions = battle.attackerPositions || {};
      var defenderPositions = Object.assign({}, battle.defenderPositions || {});
      var mapAttacker = attacker;
      var mapAttackerPositions = attackerPositions;

      // 1. 如果战斗数据中包含独立城防武器（battle.forts 或 battle.wall.forts），按攻守视角合并入战术显示
      var battleForts = battle.forts || (battle.wall && battle.wall.forts) || null;
      if (battleForts && typeof battleForts === 'object') {
        if (battle.side === 'defender') {
          mapAttacker = Object.assign({}, mapAttacker);
          mapAttackerPositions = Object.assign({}, mapAttackerPositions);
          Object.keys(battleForts).forEach(function (fId) {
            if (battleForts[fId] > 0 && mapAttacker[fId] == null) {
              mapAttacker[fId] = battleForts[fId];
              mapAttackerPositions[fId] = 0;
            }
          });
        } else {
          defender = Object.assign({}, defender);
          defenderPositions = Object.assign({}, defenderPositions);
          Object.keys(battleForts).forEach(function (fId) {
            if (battleForts[fId] > 0 && defender[fId] == null) {
              defender[fId] = battleForts[fId];
              defenderPositions[fId] = distance;
            }
          });
        }
      }

      // 2. 检查双方阵营是否拥有四大城防要塞武器（防空炮、榴弹炮、反坦克炮、碉堡）
      var hasFortWeapon = function (army) {
        if (!army) return false;
        return Object.keys(army).some(function (id) {
          if (!army[id] || army[id] <= 0) return false;
          var u = U(id);
          return (u && u.cat === 'fort') || (D.forts && !!D.forts[id]);
        });
      };
      var fortOnMine = hasFortWeapon(mapAttacker);
      var fortOnFoe = hasFortWeapon(defender);

      // 3. 墙体作为前端要塞几何目标：
      // - 若有显式城墙数据且生命未归零，或阵营中部署了四大城防工事要塞武器，城墙模型图必须矗立于阵地后方；
      // - 仅当城墙生命被明确攻破摧毁（hpRemaining === 0 且无尚存城防武器）时才从战场移除。
      var wallExplicitlyAlive = battle.wall && Number(battle.wall.level || 0) > 0 && (battle.wall.hpRemaining == null || Number(battle.wall.hpRemaining) > 0);
      var wallExplicitlyDestroyed = battle.wall && Number(battle.wall.level || 0) > 0 && Number(battle.wall.hpRemaining) === 0;
      var wallOnMap = !wallExplicitlyDestroyed && (wallExplicitlyAlive || fortOnMine || fortOnFoe);

      var effectiveWallSnapshot = battle.wall;
      if (!effectiveWallSnapshot && (fortOnMine || fortOnFoe)) {
        effectiveWallSnapshot = { level: 10, maxHp: 0, hpRemaining: null, defense: 0, virtual: true };
      }

      var attackingWall = wallOnMap && battle.battlePhase === 'ATTACK_WALL';
      if (wallOnMap && battle.side !== 'defender') {
        defender.wall = 1;
        defenderPositions.wall = distance;
      } else if (wallOnMap) {
        mapAttacker = Object.assign({}, attacker, { wall: 1 });
        mapAttackerPositions = Object.assign({}, attackerPositions, { wall: 0 });
      }
      var orders = this._tacticalOrders || {};
      var roundNo = Math.max(0, Number(battle.round) || 0);
      // 服务端提供真实上限；旧视图缺字段时按保存的战斗类型展示。
      var battleAction = String(battle.action || 'conquer').replace(/^tactical_/, '');
      var maxRound = Math.max(1, Number(battle.maxRound) || G.Constants.battleRoundLimits[battleAction] || G.Constants.battleRoundLimits.conquer);
      var markerRows = this.tacticalMarkerRows(mapAttacker, defender);
      var markerRowIndexes = {};
      markerRows.forEach(function (unitId, index) { markerRowIndexes[unitId] = index; });
      // 行号以双方现存兵种的并集为准，保证相同兵种始终隔战场正对。
      var mapHeight = 250 + Math.max(0, markerRows.length - 1) * 37;
      var attackerTech = battle.attackerTech || (Core.state && Core.state.tech) || {};
      var defenderTech = battle.defenderTech || {};
      // 老会话缺科技快照时火力按零级展示，不能套用账号刚研发完成的等级。
      var attackerFirepower = Core.firepowerMul(battle.attackerTech || {});
      var attackerSkills = battle.attackerSkills || {};
      var defenderSkills = battle.defenderSkills || {};
      var foeIsJapanese = battle.targetKind === 'npc' || battle.targetKind === 'bandit' ||
        (battle.targetName && (battle.targetName.indexOf('日寇') >= 0 || battle.targetName.indexOf('流寇') >= 0 || battle.targetName.indexOf('据点') >= 0));
      var h = '<div class="tactical-battle">';
      h += '<div class="tactical-head"><div><div class="title">' + (battle.side === 'defender' ? '防守战术指挥：' : '战术指挥：') + esc(battle.targetName || '敌军') + '</div>';
      h += '<div class="desc">所有兵种接敌后不可越线，空军也必须消灭敌方前排后才能继续向后推进。</div>';
      h += '<div class="desc">未指定目标时优先攻击射程内的敌方同类型兵种，否则攻击射程内最近的敌军；也可手动指定射程内的任意敌军。</div>';
      h += '<div class="desc">已提交的指令在本场后续所有回合持续生效，直到再次修改。地图战场点击我军图标下令，文本战场使用兵种卡片。</div></div></div>';
      h += '<div class="btn-row"><button class="btn sm" onclick="Game.go(\'alerts\')">返回军情</button></div>';
      h += this.renderWallPanel(battle.wall, battle.terminalReason);
      h += '<div class="tactical-turn-status"><div><span class="tactical-turn-label">当前回合</span><b>第 ' + roundNo + ' 回合</b><small>/ 共 ' + maxRound + ' 回合</small></div>';
      h += '<div><span class="tactical-turn-label">' + (this._tacticalRetryAt ? '重试倒计时' : '本回合倒计时') + '</span><b id="tacticalCountdown" class="tactical-countdown"></b><small>已选命令在倒计时结束后提交，也可点击下方执行按钮</small></div></div>';
      h += this.renderCommandBonusPanel({ attacker: battle.attackerCommandBonus, defender: battle.defenderCommandBonus }, 'attacker');
      h += '<div class="army-tabs" role="tablist" aria-label="战场显示方式">';
      [['battlefield', '地图战场'], ['text', '文本战场']].forEach(function (tab) {
        var selected = commandView === tab[0];
        h += '<button type="button" class="army-tab' + (selected ? ' active' : '') + '" role="tab" id="tacticalTab-' + tab[0]
          + '" aria-selected="' + selected + '" aria-controls="tacticalPanel-' + tab[0] + '" tabindex="' + (selected ? '0' : '-1')
          + '" onclick="Game.Battle.setTacticalCommandView(\'' + tab[0] + '\')" onkeydown="Game.Battle.onTacticalTabKeydown(event)">' + tab[1] + '</button>';
      });
      h += '</div><div role="tabpanel" id="tacticalPanel-' + commandView + '" aria-labelledby="tacticalTab-' + commandView + '">';
      var mapWallClass = wallOnMap ? (battle.side === 'defender' ? ' has-wall-left' : ' has-wall-right') : '';
      if (commandView === 'battlefield') {
        h += '<div class="tactical-map' + mapWallClass + '" style="--tactical-map-height:' + mapHeight + 'px"><div class="tactical-base mine-base">我军阵地</div><div class="tactical-base foe-base">敌军阵地</div><div class="tactical-axis"></div>';
        h += this.renderTacticalRangeBeams(mapAttacker, mapAttackerPositions, defender, defenderPositions, distance, markerRowIndexes, attackerTech, defenderTech, foeIsJapanese, effectiveWallSnapshot);
        h += this.renderTacticalMarkers(mapAttacker, mapAttackerPositions, distance, 'mine', markerRowIndexes, defender, defenderPositions, attackerTech, false, effectiveWallSnapshot);
        h += this.renderTacticalMarkers(defender, defenderPositions, distance, 'foe', markerRowIndexes, mapAttacker, mapAttackerPositions, defenderTech, foeIsJapanese, effectiveWallSnapshot);
        h += '<div class="tactical-distance">战场宽度 ' + distance + ' · 🎯 绿色光带为有效射程已接敌</div></div>';
      }
      var quickCommandHtml = '';
      if (!battle.finished) {
        var activeUnitIds = Object.keys(attacker).filter(function (unitId) { return attacker[unitId] > 0; });
        var allCommandsHtml = '<div class="tactical-command-head"><div class="zone-head">我军本回合命令</div><div class="tactical-command-shortcuts">';
        [['HOLD', '全部待命'], ['ADVANCE', '全部前进'], ['RETREAT', '全部后退']].forEach(function (choice) {
          var selected = activeUnitIds.length > 0 && activeUnitIds.every(function (unitId) {
            var baseId = String(unitId).split('~')[0];
            var order = orders[baseId] || orders[unitId] || {};
            return (order.action || (battle.defaultActions || {})[baseId] || 'ADVANCE') === choice[0];
          });
          allCommandsHtml += '<button class="btn sm' + (selected ? ' ok' : '') + '"' + disabled + ' aria-pressed="' + selected + '" onclick="Game.Battle.setAllTacticalActions(\'' + choice[0] + '\')">' + choice[1] + '</button>';
        });
        allCommandsHtml += '</div></div>';
        if (commandView === 'text') h += '<div class="tactical-orders">';
        var commandedTypes = {};
        activeUnitIds.filter(function (id) {
          var type = String(id).split('~')[0];
          if (commandedTypes[type]) return false;
          commandedTypes[type] = true;
          return true;
        }).forEach(function (unitId) {
          var unit = U(unitId) || {};
          var baseId = String(unitId).split('~')[0];
          if (commandView !== 'text' && self._tacticalCommandUnitId !== baseId) return;
          var groupedCount = Object.keys(attacker).filter(function (id) { return String(id).split('~')[0] === baseId; }).reduce(function (sum, id) { return sum + (attacker[id] || 0); }, 0);
          var order = orders[baseId] || orders[unitId] || { action: null, focusTarget: null };
          var shownAction = order.action || (battle.defaultActions || {})[baseId] || 'ADVANCE';
          var encodedId = esc(JSON.stringify(baseId));
          var baseRange = unit.range != null ? Number(unit.range) : 0;
          var range = self.getEffectiveRange(unitId, attackerTech);
          var baseSpd = unit.spd != null ? Number(unit.spd) : 0;
          var spd = self.getEffectiveSpeed(unitId, attackerTech, attackerSkills);
          var myPos = attackerPositions[unitId] != null ? Number(attackerPositions[unitId]) : 0;

          // 计算最近存活敌军距离与接敌状态
          var minEnemyDist = null;
          Object.keys(defender).forEach(function (tId) {
            if (defender[tId] > 0) {
              var tPos = defenderPositions[tId] != null ? Number(defenderPositions[tId]) : distance;
              var d = Math.abs(tPos - myPos);
              if (minEnemyDist === null || d < minEnemyDist) minEnemyDist = d;
            }
          });

          var inRange = range > 0 && minEnemyDist !== null && minEnemyDist <= range;
          var statusHtml = '';
          if (range === 0) {
            statusHtml = '<div class="tactical-order-range-status none">无武器射程（支援后勤单位）</div>';
          } else if (minEnemyDist === null) {
            statusHtml = '<div class="tactical-order-range-status">暂无存活敌军目标</div>';
          } else if (inRange) {
            statusHtml = '<div class="tactical-order-range-status in-range">🎯 已进入射程（' + (range > baseRange ? '实战射程 ' + range + '，' : '') + '距最近敌军 ' + minEnemyDist + '，可开火）</div>';
          } else {
            statusHtml = '<div class="tactical-order-range-status out-range">⏳ 距最近敌军 ' + minEnemyDist + '（' + (range > baseRange ? '实战射程 ' + range + '，' : '') + '还差 ' + (minEnemyDist - range) + ' 进射程，建议前进）</div>';
          }

          var rangeBonus = range > baseRange ? ' <small class="tech-tag" title="基础射程 ' + baseRange + '，武器射程科技 +' + Math.round((range - baseRange) / baseRange * 100) + '%">+' + (range - baseRange) + '</small>' : '';
          var spdBonus = spd > baseSpd ? ' <small class="tech-tag">+' + (Math.round((spd - baseSpd) * 10) / 10) + '</small>' : '';

          var cardHtml = '<div class="tactical-order-card"><div class="tactical-order-header">'
            + '<div class="tactical-order-name">' + esc(unit.name || unitId) + ' <b>×' + groupedCount + '</b> · 同兵种统一命令</div></div>'
            + '<div class="tactical-order-metrics">'
            + '<span class="tactical-metric-pill range" title="有效交火射程: ' + range + '（基础: ' + baseRange + (range > baseRange ? '，科技 +' + Math.round((range - baseRange) / baseRange * 100) + '%' : '') + '）">射程 <b>' + range + '</b>' + rangeBonus + '</span>'
            + '<span class="tactical-metric-pill spd" title="单回合机动推进 ' + Math.round(spd * 50) + ' 距离（基础移速: ' + baseSpd + '）">移速 <b>' + spd + '</b>' + spdBonus + '</span>'
            + '<span class="tactical-metric-pill" title="与武器攻击独立相乘，主动、余伤与反击均生效">行动火力 <b>×' + attackerFirepower.toFixed(2) + '</b></span>'
            + '</div>'
            + statusHtml
            + '<div class="tactical-actions">';
          [['ADVANCE', '前进'], ['RETREAT', '后退'], ['HOLD', '待命']].forEach(function (choice) {
            var selected = shownAction === choice[0];
            cardHtml += '<button class="btn sm' + (selected ? ' ok' : '') + '"' + disabled + ' aria-pressed="' + selected + '" onclick="Game.Battle.setTacticalAction(' + encodedId + ',\'' + choice[0] + '\')">' + choice[1] + '</button>';
          });
          cardHtml += '</div><label class="tactical-focus">集火 <select' + disabled + ' onchange="Game.Battle.setTacticalFocus(' + encodedId + ',this.value)">';
          cardHtml += '<option value="">' + (attackingWall && battle.side !== 'defender' ? '默认索敌（攻击城墙）' : '默认索敌（同兵种优先，否则最近）') + '</option>';
          Object.keys(defender).forEach(function (targetId) {
            // 清军阶段墙体只作为战场背景目标展示，必须等服务端切入攻墙阶段后才可集火。
            if (targetId === 'wall' && (!attackingWall || battle.side === 'defender')) return;
            if (defender[targetId] <= 0) return;
            var target = U(targetId, foeIsJapanese) || {};
            var tPos = defenderPositions[targetId] != null ? Number(defenderPositions[targetId]) : distance;
            var tDist = Math.abs(tPos - myPos);
            var tInRange = range > 0 && tDist <= range;
            var statusBadge = tInRange ? ' [🎯可开火 距' + tDist + ']' : ' [超出射程 距' + tDist + ']';
            cardHtml += '<option value="' + esc(targetId) + '"' + (order.focusTarget === targetId ? ' selected' : '') + '>'
              + esc(target.name || targetId) + statusBadge + '</option>';
          });
          cardHtml += '</select></label></div>';
          if (commandView === 'text') h += cardHtml;
          // 快捷弹窗复用同一份卡片，兵力、选中命令和集火候选保持一致。
          if (commandView === 'battlefield' && !self._tacticalSubmitting && self._tacticalCommandUnitId === baseId) quickCommandHtml = cardHtml;
        });
        if (commandView === 'text') h += '</div>';
      }
      h += '</div>';
      var inactiveView = commandView === 'text' ? 'battlefield' : 'text';
      h += '<div role="tabpanel" id="tacticalPanel-' + inactiveView + '" aria-labelledby="tacticalTab-' + inactiveView + '" hidden></div>';
      if (battle.finished) {
        var result = battle.result || {};
        var finishedText = battle.terminalReason === 'WALL_BREACHED' ? '城墙已攻破，征服战胜利'
          : battle.terminalReason === 'WALL_NOT_BREACHED' ? '守军已清除，但未能破墙' : (result.win ? '战斗胜利' : '战斗结束');
        h += '<div class="tactical-finish ' + (result.win ? 'win' : 'lose') + '"><b>' + finishedText + '</b><span>战果已写入战报，幸存部队将按原路线返程。</span>'
          + '<button class="btn sm" onclick="Game.go(\'alerts\')" aria-label="返回军情">返回军情</button></div>';
      } else {
        h += allCommandsHtml;
        h += '<div class="btn-row tactical-execute"><button class="btn ok"' + disabled + ' onclick="Game.Battle.executeTacticalRound()">'
          + (this._tacticalSubmitting ? '正在提交…' : this._tacticalAutoExecuteFailed ? '重试第 ' + (roundNo + 1) + ' 回合' : '执行第 ' + (roundNo + 1) + ' 回合') + '</button></div>';
        if (this._tacticalSubmitting) h += '<p role="status">本回合命令正在提交，请稍候。</p>';
        if (this._tacticalAutoExecuteFailed) {
          h += '<div class="tactical-turn-status failed" role="status">本回合执行失败：' + esc(this._tacticalAutoExecuteError || '请求未完成')
            + (this._tacticalRetryAt ? '；5 秒后自动重试，也可点击执行按钮重试。' : '；请手动重试。') + '</div>';
        }
      }
      if (quickCommandHtml) {
        h += '<div class="modal-mask" id="tacticalUnitCommand" onclick="if(event.target===this)Game.Battle.closeTacticalUnitCommand();" onkeydown="Game.Battle.onTacticalCommandKeydown(event)">'
          + '<section class="modal-card" role="dialog" aria-modal="true" aria-labelledby="tacticalUnitCommandTitle">'
          + '<div class="modal-title" id="tacticalUnitCommandTitle">兵种指挥</div><div class="modal-body">' + quickCommandHtml
          + '<p>选择后加入本回合命令，倒计时结束时执行，同兵种统一下令。</p>'
          + '<p>本回合倒计时：<b id="tacticalUnitCommandCountdown"></b></p></div>'
          + '<div class="modal-foot"><button type="button" class="btn" onclick="Game.Battle.closeTacticalUnitCommand()">关闭</button></div></section></div>';
      } else {
        this._tacticalCommandUnitId = null;
      }
      var logs = String(battle.log || '').trim().split('\n').filter(Boolean);
      h += '<div class="btn-row"><button type="button" class="btn sm" id="tacticalLogToggle" aria-expanded="' + !!this._tacticalLogExpanded
        + '" aria-controls="tacticalLog" onclick="Game.Battle.toggleTacticalLog()">战场记录（' + logs.length + '）' + (this._tacticalLogExpanded ? ' 收起' : ' 展开') + '</button></div>';
      h += '<div id="tacticalLog"' + (this._tacticalLogExpanded ? '' : ' hidden') + '><div class="blog tactical-log">';
      logs.slice(-24).forEach(function (line) { h += '<div class="logline">' + esc(self.formatCombatLogLine(line)) + '</div>'; });
      h += '</div></div></div>';
      if (this._tacticalCommandPopup) this._tacticalCommandPopup.remove();
      this._tacticalCommandPopup = null;
      v.innerHTML = h;
      if (quickCommandHtml) {
        var popup = document.getElementById('tacticalUnitCommand');
        // 与现有弹窗一样挂在 body 下，避免页面文字按钮规则覆盖弹窗的原生按钮样式。
        if (popup) document.body.appendChild(popup);
        this._tacticalCommandPopup = popup;
        var control = popup && (popup.querySelector('[aria-pressed="true"]') || popup.querySelector('button,select'));
        if (control) control.focus({ preventScroll: true });
      }
      this.startTacticalTimer();
    },

    /** 展示本场墙体快照及终局原因；取整只影响文字，伤害与剩余耐久由服务端计算。 */
    renderWallPanel: function (wall, terminalReason) {
      if (!wall) return '';
      var hp = Math.max(0, Number(wall.hpRemaining) || 0);
      var maxHp = Math.max(0, Number(wall.maxHp) || 0);
      var phase = wall.level === 0 ? '无城墙' : wall.phase === 'BREACHED' ? '城墙已攻破'
        : wall.phase === 'ATTACK_WALL' ? '攻击城墙' : '清除守军';
      var percent = maxHp > 0 ? Math.max(0, Math.min(100, hp / maxHp * 100)) : 0;
      var h = '<div class="conquest-wall-panel"><b>🏰 ' + phase + ' · 城墙 Lv.' + wall.level + '</b>'
        + '<span>墙体生命 ' + Math.ceil(hp).toLocaleString() + ' / ' + maxHp.toLocaleString()
        + ' · 墙体防御 ' + Number(wall.defense || 0) + '</span>'
        + '<div class="conquest-wall-hp" role="progressbar" aria-label="墙体生命" aria-valuemin="0" aria-valuemax="' + maxHp
        + '" aria-valuenow="' + hp + '"><div style="width:' + percent + '%"></div></div>';
      if (terminalReason === 'WALL_NOT_BREACHED') h += '<span>守军已清除，但未在期限内破墙</span>';
      else if (wall.level > 0 && hp > 0) h += '<small>清除守军与工事后，射程内余伤可继续攻墙；破墙后才可赢得征服战。</small>';
      return h + '</div>';
    },

    /**
     * 计算单位在当前战术环境下的有效射程（受武器射程科技加成）。
     */
    getEffectiveRange: function (unitId, techMap) {
      var unit = U(unitId) || {};
      var baseRange = unit.range != null ? Number(unit.range) : 0;
      if (baseRange <= 0) return 0;
      var tech = techMap || (this._activeTactical && this._activeTactical.attackerTech)
        || (Core.state && Core.state.tech) || {};
      var weaponRangeLv = Number(tech.weapon_range || 0);
      return Math.floor(baseRange * (1 + 0.05 * weaponRangeLv));
    },

    /**
     * 计算下一回合的有效机动速度；科技常驻，闪电突击沿用服务端持久化的交战周期。
     * 首次交战在机动结束后确认，因此尚未交战时的机动不预先享受技能加成。
     * @param {string} unitId - 兵种 ID。
     * @param {Object} [techMap] - 战斗科技快照。
     * @param {Object} [skillsMap] - 指挥官技能快照。
     * @returns {number} 下一回合机动速度，保留一位小数。
     */
    getEffectiveSpeed: function (unitId, techMap, skillsMap, commandBonus) {
      var unit = U(unitId) || {};
      var baseSpd = unit.spd != null ? Number(unit.spd) : 0;
      if (baseSpd <= 0) return 0;
      var tech = techMap || (this._activeTactical && this._activeTactical.attackerTech)
        || (Core.state && Core.state.tech) || {};
      var skills = skillsMap || (this._activeTactical && this._activeTactical.attackerSkills) || {};
      var cat = unit.cat;
      var catKey = G.Constants.unitSpeedTechKeys[String(unitId).split('~')[0]] || G.Constants.unitTechKeys[cat];
      var engLv = catKey ? Number(tech[catKey] || 0) : 0;
      var battle = this._activeTactical || {};
      var firstCombatRound = Math.max(0, Number(battle.firstCombatRound) || 0);
      var nextRound = Math.max(0, Number(battle.round) || 0) + 1;
      var skillRoundActive = firstCombatRound > 0 && nextRound >= firstCombatRound
        && (nextRound - firstCombatRound) % 3 === 0;
      var blitzLv = skillRoundActive ? Math.max(0, Math.min(5, Number(skills.blitz) || 0)) : 0;
      var chief = commandBonus || battle.attackerCommandBonus || {};
      var rate = chief.rate == null ? G.Constants.commandBonusRate : Number(chief.rate);
      var chiefSpeedMul = 1 + Math.max(0, Number(chief.knowledge) || 0) * rate;
      return Math.round(baseSpd * (G.UnitEquipment ? G.UnitEquipment.speed(tech, unitId, true) : 1) * (1 + 0.05 * engLv) * chiefSpeedMul * (1 + 0.06 * blitzLv) * 10) / 10;
    },

    /**
     * 按兵种配置的固定顺序建立双方共用行序；仅一方拥有的兵种也占一整行。
     * 行号按兵种 ID 匹配，盟军与日军的不同型号名称不影响同类型对齐。
     * @param {Object} attacker - 进攻方按兵种统计的当前兵力。
     * @param {Object} defender - 防守方按兵种统计的当前兵力。
     * @returns {string[]} 按显示顺序排列的现存兵种 ID。
     */
    tacticalMarkerRows: function (attacker, defender) {
      var rows = [];
      [attacker, defender].forEach(function (army) {
        Object.keys(army || {}).forEach(function (unitId) {
          if (army[unitId] > 0 && rows.indexOf(unitId) === -1) rows.push(unitId);
        });
      });
      var unitOrder = Object.keys(D.units || {}).concat(Object.keys(D.forts || {}));
      rows.sort(function (left, right) {
        var leftIndex = unitOrder.indexOf(left);
        var rightIndex = unitOrder.indexOf(right);
        if (leftIndex < 0) leftIndex = unitOrder.length;
        if (rightIndex < 0) rightIndex = unitOrder.length;
        return leftIndex - rightIndex || left.localeCompare(right);
      });
      return rows;
    },

    /**
     * 绘制战场各兵种的有效射程覆盖光带，直观展示双方火力范围与接敌状态。
     */
    renderTacticalRangeBeams: function (attacker, attackerPositions, defender, defenderPositions, distance, rowIndexes, attackerTech, defenderTech, foeIsJapanese, wallSnapshot) {
      var self = this;
      var esc = G.escapeHtml;
      var html = '';
      var dist = Math.max(1, Number(distance) || 1);
      var rows = rowIndexes || {};
      var hasWallOnField = !!(wallSnapshot && Number(wallSnapshot.level || 0) > 0 && (wallSnapshot.virtual || wallSnapshot.hpRemaining == null || Number(wallSnapshot.hpRemaining) > 0));
      var FORT_ANCHORS = {
        flak: 'calc(8.96% - 19px)',
        howitzer: 'calc(28.75% - 19px)',
        antitank: 'calc(70.94% - 19px)',
        bunker: 'calc(90.83% - 19px)'
      };

      Object.keys(rows).forEach(function (unitId) {
        var rowIndex = rows[unitId];
        var unit = U(unitId) || {};
        var baseRange = unit.range != null ? unit.range : 0;
        if (baseRange <= 0) return;
        var isFort = (unit.cat === 'fort') || (D.forts && !!D.forts[unitId]);
        var fortAnchor = isFort ? FORT_ANCHORS[unitId] : null;

        // 1. 我军射程光带 (向右延伸，含科技加成)
        var myCount = attacker && attacker[unitId];
        if (myCount > 0) {
          var range = self.getEffectiveRange(unitId, attackerTech);
          var myPos = (attackerPositions && attackerPositions[unitId] != null) ? Number(attackerPositions[unitId]) : 0;
          if (isFort && hasWallOnField) {
            myPos = 0; // 我方守城，城防武器紧贴左侧要塞城墙发射
          }
          var myReach = Math.min(dist, myPos + range);
          var myPosRatio = Math.max(0, Math.min(1, myPos / dist));
          var myReachRatio = Math.max(0, Math.min(1, myReach / dist));
          var mySpanRatio = Math.max(0.01, myReachRatio - myPosRatio);

          var myLeftPct = Math.max(3, Math.min(97, Math.round(myPosRatio * 100)));
          var myReachPct = Math.max(3, Math.min(97, Math.round(myReachRatio * 100)));
          var myWidthPct = Math.max(1, myReachPct - myLeftPct);

          var myLeftCalc = 'calc(var(--battle-left-pad, 4%) + (100% - var(--battle-left-pad, 4%) - var(--battle-right-pad, 4%)) * ' + myPosRatio.toFixed(4) + ')';
          var myWidthCalc = 'calc((100% - var(--battle-left-pad, 4%) - var(--battle-right-pad, 4%)) * ' + mySpanRatio.toFixed(4) + ')';

          // 判定是否有存活敌军进入该兵种射程
          var myInRange = false;
          var nearestFoeDist = null;
          Object.keys(defender || {}).forEach(function (foeId) {
            if (defender[foeId] > 0) {
              var foePos = (defenderPositions && defenderPositions[foeId] != null) ? Number(defenderPositions[foeId]) : dist;
              var d = Math.abs(foePos - myPos);
              if (nearestFoeDist === null || d < nearestFoeDist) nearestFoeDist = d;
              if (d <= range) myInRange = true;
            }
          });

          var bonusHint = range > baseRange ? ' (含科技+' + (range - baseRange) + ')' : '';
          var myTitle = esc(unit.name || unitId) + (isFort ? ' 🏰城防射程: ' : ' 我军射程: ') + range + bonusHint + '（覆盖至坐标 ' + myReach + '）'
            + (nearestFoeDist != null ? '，距最近敌军 ' + nearestFoeDist + (myInRange ? ' [🎯已在射程内]' : ' [未进入射程]') : '');

          var beamStyle = 'left:' + myLeftPct + '%;width:' + myWidthPct + '%;left:' + myLeftCalc + ';width:' + myWidthCalc + ';--marker-row:' + rowIndex
            + (fortAnchor ? ';--fort-anchor-top:' + fortAnchor : '');

          if (isFort) {
            var myStrokeColor = myInRange ? '#4ade80' : '#f59e0b';
            var myFillColor = myInRange ? 'rgba(74, 222, 128, 0.35)' : 'rgba(245, 158, 11, 0.28)';
            var myGradId = 'fortGrad_mine_' + String(unitId).replace(/[^a-zA-Z0-9]/g, '_') + '_' + rowIndex;
            var formattedMyCount = typeof G.fmt === 'function' ? G.fmt(myCount) : myCount;
            html += '<div class="tactical-range-beam fort-arc mine' + (myInRange ? ' in-range' : '') + '" style="' + beamStyle + '" title="' + myTitle + '"' + self.tacticalCommandAttributes(unitId, 'mine') + '>'
              + '<span class="fort-arc-origin-badge" title="' + esc(unit.name || unitId) + ' ×' + formattedMyCount + '">×' + esc(String(formattedMyCount)) + '</span>'
              + '<svg viewBox="0 0 100 40" preserveAspectRatio="none" class="fort-arc-svg">'
              + '<defs><linearGradient id="' + myGradId + '" x1="0" y1="0" x2="0" y2="1">'
              + '<stop offset="0%" stop-color="' + myFillColor + '"/>'
              + '<stop offset="100%" stop-color="' + myFillColor + '" stop-opacity="0.02"/>'
              + '</linearGradient></defs>'
              + '<path d="M 0,20 Q 50,2 100,20 L 100,20 L 0,20 Z" fill="url(#' + myGradId + ')"/>'
              + '<path d="M 0,20 Q 50,2 100,20" fill="none" stroke="' + myStrokeColor + '" stroke-width="2.5" class="fort-arc-line"/>'
              + '</svg>'
              + '<span class="range-beam-label fort-arc-label">🏰 ' + esc(unit.name || unitId) + ' 射程 ' + range + (myInRange ? ' 🎯' : '')
              + (self.tacticalOrderStatus(unitId, 'mine') ? ' · ' + esc(self.tacticalOrderStatus(unitId, 'mine')) : '') + '</span></div>';
          } else {
            // 普通兵种光带仅作为纯粹火力覆盖区间，避免与兵种图标下方独立的黑色背景白色字体徽章重复显示
            html += '<div class="tactical-range-beam mine' + (myInRange ? ' in-range' : '') + '" style="' + beamStyle + '" title="' + myTitle + '"></div>';
          }
        }

        // 2. 敌军射程光带 (向左延伸，含科技加成)
        var foeCount = defender && defender[unitId];
        if (foeCount > 0) {
          var foeUnit = U(unitId, foeIsJapanese) || unit;
          var foeRange = self.getEffectiveRange(unitId, defenderTech);
          var foePos = (defenderPositions && defenderPositions[unitId] != null) ? Number(defenderPositions[unitId]) : dist;
          if (isFort && hasWallOnField) {
            foePos = dist; // 敌方守城，城防武器紧贴右侧要塞城墙发射
          }
          var foeReach = Math.max(0, foePos - foeRange);
          var foePosRatio = Math.max(0, Math.min(1, foePos / dist));
          var foeReachRatio = Math.max(0, Math.min(1, foeReach / dist));
          var foeSpanRatio = Math.max(0.01, foePosRatio - foeReachRatio);

          var foeLeftPct = Math.max(3, Math.min(97, Math.round(foeReachRatio * 100)));
          var foeOrigPct = Math.max(3, Math.min(97, Math.round(foePosRatio * 100)));
          var foeWidthPct = Math.max(1, foeOrigPct - foeLeftPct);

          var foeLeftCalc = 'calc(var(--battle-left-pad, 4%) + (100% - var(--battle-left-pad, 4%) - var(--battle-right-pad, 4%)) * ' + foeReachRatio.toFixed(4) + ')';
          var foeWidthCalc = 'calc((100% - var(--battle-left-pad, 4%) - var(--battle-right-pad, 4%)) * ' + foeSpanRatio.toFixed(4) + ')';

          // 判定是否有存活我军进入该敌军兵种射程
          var foeInRange = false;
          var nearestMineDist = null;
          Object.keys(attacker || {}).forEach(function (mineId) {
            if (attacker[mineId] > 0) {
              var mPos = (attackerPositions && attackerPositions[mineId] != null) ? Number(attackerPositions[mineId]) : 0;
              var d = Math.abs(foePos - mPos);
              if (nearestMineDist === null || d < nearestMineDist) nearestMineDist = d;
              if (d <= foeRange) foeInRange = true;
            }
          });

          var foeTitle = esc(foeUnit.name || unitId) + (isFort ? ' 🏰城防射程: ' : ' 敌军射程: ') + foeRange + '（覆盖至坐标 ' + foeReach + '）'
            + (nearestMineDist != null ? '，距最近我军 ' + nearestMineDist + (foeInRange ? ' [⚠️已覆盖我军]' : ' [未覆盖我军]') : '');

          var foeBeamStyle = 'left:' + foeLeftPct + '%;width:' + foeWidthPct + '%;left:' + foeLeftCalc + ';width:' + foeWidthCalc + ';--marker-row:' + rowIndex
            + (fortAnchor ? ';--fort-anchor-top:' + fortAnchor : '');

          if (isFort) {
            var foeStrokeColor = foeInRange ? '#f87171' : '#f97316';
            var foeFillColor = foeInRange ? 'rgba(239, 68, 68, 0.35)' : 'rgba(249, 115, 22, 0.28)';
            var foeGradId = 'fortGrad_foe_' + String(unitId).replace(/[^a-zA-Z0-9]/g, '_') + '_' + rowIndex;
            var formattedFoeCount = typeof G.fmt === 'function' ? G.fmt(foeCount) : foeCount;
            html += '<div class="tactical-range-beam fort-arc foe' + (foeInRange ? ' in-range' : '') + '" style="' + foeBeamStyle + '" title="' + foeTitle + '">'
              + '<span class="fort-arc-origin-badge" title="' + esc(foeUnit.name || unitId) + ' ×' + formattedFoeCount + '">×' + esc(String(formattedFoeCount)) + '</span>'
              + '<svg viewBox="0 0 100 40" preserveAspectRatio="none" class="fort-arc-svg">'
              + '<defs><linearGradient id="' + foeGradId + '" x1="0" y1="0" x2="0" y2="1">'
              + '<stop offset="0%" stop-color="' + foeFillColor + '"/>'
              + '<stop offset="100%" stop-color="' + foeFillColor + '" stop-opacity="0.02"/>'
              + '</linearGradient></defs>'
              + '<path d="M 0,20 Q 50,2 100,20 L 100,20 L 0,20 Z" fill="url(#' + foeGradId + ')"/>'
              + '<path d="M 0,20 Q 50,2 100,20" fill="none" stroke="' + foeStrokeColor + '" stroke-width="2.5" class="fort-arc-line"/>'
              + '</svg>'
              + '<span class="range-beam-label fort-arc-label">🏰 ' + esc(foeUnit.name || unitId) + ' 射程 ' + foeRange + (foeInRange ? ' ⚠️' : '') + '</span></div>';
          } else {
            // 普通兵种光带仅作为纯粹火力覆盖区间，避免与兵种图标下方独立的黑色背景白色字体徽章重复显示
            html += '<div class="tactical-range-beam foe' + (foeInRange ? ' in-range' : '') + '" style="' + foeBeamStyle + '" title="' + foeTitle + '"></div>';
          }
        }
      });

      return html;
    },

    /**
     * 根据服务端的一维战场坐标，使用紧凑图标与实时兵力渲染同一方单位。
     * @param {Object} army - 按兵种统计的当前兵力。
     * @param {Object} positions - 按兵种记录的一维战场坐标。
     * @param {number} distance - 战场总宽度。
     * @param {string} side - 当前阵营标识。
     * @param {Object} [rowIndexes] - 兵种 ID 到统一纵向行号的映射。
     * @param {Object} [opponentArmy] - 对手存活部队数据（用于射程检测）。
     * @param {Object} [opponentPositions] - 对手部队坐标（用于射程检测）。
     * @param {Object} [techMap] - 科技加成字典（用于计算实际射程）。
     * @param {Object} [wallSnapshot] - 城墙独立耐久快照，用于渲染墙体图标与状态条。
     * @returns {string} 战场单位徽标的 HTML。
     */
    renderTacticalMarkers: function (army, positions, distance, side, rowIndexes, opponentArmy, opponentPositions, techMap, isJapanese, wallSnapshot) {
      var self = this;
      var esc = G.escapeHtml;
      var html = '';
      var markers = [];
      var dist = Math.max(1, Number(distance) || 1);
      var hasWallOnField = !!(wallSnapshot && Number(wallSnapshot.level || 0) > 0 && (wallSnapshot.virtual || wallSnapshot.hpRemaining == null || Number(wallSnapshot.hpRemaining) > 0));
      Object.keys(army || {}).forEach(function (unitId) {
        if (!army[unitId] || army[unitId] <= 0) return;
        var unit = U(unitId, isJapanese) || {};
        var isFortWeapon = unitId !== 'wall' && ((unit.cat === 'fort') || (D.forts && !!D.forts[unitId]));
        // 当战场存在要塞城墙时，四大城防武器架设于城墙棱堡之上，由微缩城墙模型与弧形射程光带起点徽章统一标示编制数量与射程，避免在走廊重复堆叠地面方块
        if (hasWallOnField && isFortWeapon) return;
        var position = positions && positions[unitId] != null ? positions[unitId] : (side === 'mine' ? 0 : dist);
        markers.push({ unitId: unitId, unit: unit, position: position, count: army[unitId] });
      });
      markers.forEach(function (marker, index) {
        var rowIndex = rowIndexes && rowIndexes[marker.unitId] != null ? rowIndexes[marker.unitId] : index;
        var isWall = marker.unitId === 'wall';
        var isMine = side === 'mine';
        var wallImg = isMine ? 'img/cities/tactical-city-wall-right.webp?v=20261015-forward' : 'img/cities/tactical-city-wall-left.webp?v=20261015-forward';
        var wallAlt = isMine ? '守方要塞城墙（城门朝右）' : '攻方敌军城墙（城门朝左）';
        var wallIconHtml = '<span class="tactical-unit-icon tactical-wall-icon" data-legacy-src="img/cities/player-city-wall-front.webp"><img src="' + wallImg + '" data-legacy-src="img/cities/player-city-wall-front.webp" alt="' + wallAlt + '"/></span>';
        var icon = isWall ? wallIconHtml : typeof G.getUnitModelIconHtml === 'function'
          ? G.getUnitModelIconHtml(String(marker.unitId).split('~')[0], marker.unit.name || marker.unitId, 'tactical-unit-icon')
          : (typeof G.getUnitIconHtml === 'function'
            ? G.getUnitIconHtml(String(marker.unitId).split('~')[0], marker.unit.name || marker.unitId, 'tactical-unit-icon')
            : esc(marker.unit.name || marker.unitId).charAt(0));
        var count = typeof G.fmt === 'function' ? G.fmt(marker.count) : marker.count;
        var baseRange = marker.unit.range != null ? marker.unit.range : 0;
        var range = typeof Game.Battle.getEffectiveRange === 'function'
          ? Game.Battle.getEffectiveRange(marker.unitId, techMap)
          : (baseRange * (1 + 0.05 * (Number(techMap && techMap.weapon_range) || 0)));

        // 计算与对手存活部队的射程关系
        var inRange = false;
        var nearestOppDist = null;
        if (opponentArmy) {
          var myPos = Number(marker.position) || 0;
          Object.keys(opponentArmy).forEach(function (oppId) {
            if (opponentArmy[oppId] > 0) {
              var oppPos = (opponentPositions && opponentPositions[oppId] != null) ? Number(opponentPositions[oppId]) : (side === 'mine' ? dist : 0);
              var d = Math.abs(oppPos - myPos);
              if (nearestOppDist === null || d < nearestOppDist) nearestOppDist = d;
              if (range > 0 && d <= range) inRange = true;
            }
          });
        }

        var bonusHint = range > baseRange ? '（科技加成 +' + (range - baseRange) + '）' : '';
        var statusDesc = range === 0 ? '，无武器射程' : (nearestOppDist != null ? (inRange ? '，距' + (side === 'mine' ? '敌军 ' : '我军 ') + nearestOppDist + '（🎯已在射程内）' : '，距' + (side === 'mine' ? '敌军 ' : '我军 ') + nearestOppDist + '（未进入射程）') : '');
        var wallHp = isWall && wallSnapshot ? Math.max(0, Number(wallSnapshot.hpRemaining) || 0) : 0;
        var wallMaxHp = isWall && wallSnapshot ? Math.max(0, Number(wallSnapshot.maxHp) || 0) : 0;
        var wallDefense = isWall && wallSnapshot ? Math.max(0, Number(wallSnapshot.defense) || 0) : 0;
        var wallMaxDefense = isWall && wallSnapshot ? Math.max(1, Number(wallSnapshot.level || 0) * 20) : 0;
        var wallHpPercent = wallMaxHp > 0 ? Math.max(0, Math.min(100, wallHp / wallMaxHp * 100)) : 0;
        var wallDefensePercent = wallMaxDefense > 0 ? Math.max(0, Math.min(100, wallDefense / wallMaxDefense * 100)) : 0;
        var title = esc(marker.unit.name || marker.unitId) + (isWall
          ? '，生命 ' + Math.ceil(wallHp).toLocaleString() + '/' + wallMaxHp.toLocaleString() + '，防御 ' + wallDefense
          : ' ×' + marker.count)
          + '，战场位置 ' + marker.position
          + '，射程 ' + range + bonusHint + statusDesc;

        var rangePillClass = 'tactical-marker-range' + (inRange ? ' in-range' : (range === 0 ? ' no-range' : ''));
        var rangeLabel = range > 0 ? (inRange ? '🎯' + range : '射程 ' + range) : '无射程';
        var orderStatus = self.tacticalOrderStatus(marker.unitId, side);

        var posRatio = Math.max(0, Math.min(1, (Number(marker.position) || 0) / dist));
        var hasWallOnField = !!(wallSnapshot && Number(wallSnapshot.level || 0) > 0 && (wallSnapshot.virtual || wallSnapshot.hpRemaining == null || Number(wallSnapshot.hpRemaining) > 0));
        var isFortUnit = (marker.unit.cat === 'fort') || (D.forts && !!D.forts[marker.unitId]);
        var fortAnchor = isFortUnit ? {
          flak: 'calc(8.96% - 17px)',
          howitzer: 'calc(28.75% - 17px)',
          antitank: 'calc(70.94% - 17px)',
          bunker: 'calc(90.83% - 17px)'
        }[marker.unitId] : null;
        var coordinateLeft = function (ratio) {
          return 'calc(var(--battle-left-pad, 4%) + (100% - var(--battle-left-pad, 4%) - var(--battle-right-pad, 4%)) * ' + ratio.toFixed(6) + ')';
        };
        var markerLeft = coordinateLeft(posRatio);
        // 共用前排中线限定显示边界：图标各退半宽加7.5px，贴近及同坐标时边缘始终相隔15px。
        // 仅调整图标位置，射程、接触伤害和指令仍使用服务端真实坐标。
        if (!isWall && opponentArmy) {
          var ownPositions = Object.keys(army || {}).filter(function (id) { return army[id] > 0 && id !== 'wall'; }).map(function (id) {
            return Number(positions && positions[id] != null ? positions[id] : (isMine ? 0 : dist));
          });
          var foePositions = Object.keys(opponentArmy).filter(function (id) { return opponentArmy[id] > 0 && id !== 'wall'; }).map(function (id) {
            return Number(opponentPositions && opponentPositions[id] != null ? opponentPositions[id] : (isMine ? dist : 0));
          });
          if (ownPositions.length && foePositions.length) {
            var ownFront = isMine ? Math.max.apply(Math, ownPositions) : Math.min.apply(Math, ownPositions);
            var foeFront = isMine ? Math.min.apply(Math, foePositions) : Math.max.apply(Math, foePositions);
            var contactRatio = Math.max(0, Math.min(1, (ownFront + foeFront) / 2 / dist));
            var contactLimit = 'calc(' + coordinateLeft(contactRatio) + (isMine ? ' - ' : ' + ') + 'var(--tactical-icon-half, 11.25px)' + (isMine ? ' - ' : ' + ') + '7.5px)';
            markerLeft = (isMine ? 'min(' : 'max(') + markerLeft + ', ' + contactLimit + ')';
          }
        }

        var markerPositionStyle = isWall
          ? (isMine ? 'left:0;right:auto;--marker-row:' + rowIndex : 'right:0;left:auto;--marker-row:' + rowIndex)
          : (fortAnchor && hasWallOnField
            ? (isMine ? 'left:calc(var(--battle-left-pad, 112px) - 22px);top:' + fortAnchor + ';--marker-row:' + rowIndex : 'right:calc(var(--battle-right-pad, 112px) - 22px);left:auto;top:' + fortAnchor + ';--marker-row:' + rowIndex)
            : 'left:' + markerLeft + ';--marker-row:' + rowIndex);
        html += '<div class="tactical-marker ' + side + (isWall ? ' tactical-wall-marker' : '') + '" data-unit-id="' + esc(marker.unitId) + '" style="' + markerPositionStyle + '" title="' + title + '" data-in-range="' + inRange + '"' + self.tacticalCommandAttributes(marker.unitId, side) + '>'
          + icon
          + (isWall
            ? (wallSnapshot && !wallSnapshot.virtual && wallSnapshot.maxHp > 0
              ? '<div class="tactical-wall-overlay">'
                + '<b>城墙 Lv.' + (wallSnapshot ? Number(wallSnapshot.level || 0) : 10) + '</b>'
                + '<span class="tactical-wall-stat">生命 ' + Math.ceil(wallHp).toLocaleString() + ' / ' + wallMaxHp.toLocaleString() + '</span>'
                + '<span class="tactical-wall-bar hp" role="progressbar" aria-label="城墙生命" aria-valuemin="0" aria-valuemax="' + wallMaxHp + '" aria-valuenow="' + wallHp + '"><i style="width:' + wallHpPercent + '%"></i></span>'
                + '<span class="tactical-wall-stat">防御 ' + wallDefense + ' / ' + wallMaxDefense + '</span>'
                + '<span class="tactical-wall-bar defense" role="progressbar" aria-label="城墙防御" aria-valuemin="0" aria-valuemax="' + wallMaxDefense + '" aria-valuenow="' + wallDefense + '"><i style="width:' + wallDefensePercent + '%"></i></span>'
                + '</div>'
              : '')
            : '<b>×' + esc(String(count)) + '</b><span class="' + rangePillClass + '">' + esc(rangeLabel)
              + (orderStatus ? ' · ' + esc(orderStatus) : '') + '</span>')
          + '</div>';
      });
      return html;
    },

    renderReportDetail: function (v) {
      var r = this._viewReport;
      if (!r) { G.go('reports'); return; }
      if (r.expiresAt && r.expiresAt <= Date.now()) { this._viewReport = null; v.innerHTML = '<div class="rb-line">分享战报已过期</div>'; return; }
      if (!r.starred && r.detailsAvailable !== false && r.detailExpiresAt && r.detailExpiresAt <= Date.now()) {
        this._viewReport = null; this._reportsHistoryLoaded = null; G.go('reports'); return;
      }
      var h = '';
      if (r.detailsAvailable === false) {
        h += '<div class="rb-line rc-dim">详细战斗日志已过期，以下为保留的战果摘要。</div>';
        h += this.renderReportPreview(r);
        h += '<div class="btn-row report-detail-actions"><button class="btn" onclick="Game.go(\'reports\')">↩ 返回战报</button><button class="btn warn" onclick="Game.go(\'home\')">🏠 返回首页</button></div>';
        v.innerHTML = h;
        return;
      }
      if (r.type === 'scout') {
        h += this.renderScoutReportBoard(r);
        h += '<div class="btn-row report-detail-actions" style="margin-top:10px">';
        h += '<button class="btn" onclick="Game.go(\'reports\')">↩ 返回战报</button>';
        h += '<button class="btn" onclick="Game.go(\'wounded\')">伤兵营</button>';
        h += '<button class="btn warn" onclick="Game.go(\'home\')">🏠 返回首页</button>';
        h += '</div>';
      } else {
        h += this.renderReportBoard(r, false);
        // 规则版本保留在原始战报中供复盘，页面只展示与战场相关的初始距离。
        var initialDistance = '';
        var logs = Array.isArray(r.roundLogs) ? r.roundLogs.filter(function (line) {
          var text = String(line || '').trim();
          if (/^战场初始距离\s*[:：]/.test(text)) {
            if (!initialDistance) initialDistance = text;
            return false;
          }
          return !/^规则版本\s*[:：]/.test(text) && text.indexOf('战场部署完成。') !== 0;
        }) : [];
        var roundCount = this.reportRoundCount(logs);
        var countText = roundCount > 0 ? ('共 ' + roundCount + ' 回合') : (logs.length > 0 ? (logs.length + ' 条记录') : '');
        var badgeHtml = countText ? '<span class="rb-details-count" style="font-size:11px;font-weight:normal;opacity:0.85;margin-left:4px;">(' + countText + ')</span>' : '';

        h += '<div class="report-details-toggle-wrap" style="margin:14px 0 6px;">';
        h += '<button type="button" class="btn report-details-toggle-btn active" id="btnBattleDetails" onclick="Game.Battle.toggleBattleDetails()" style="width:100%;display:flex;align-items:center;justify-content:center;gap:4px;padding:8px 12px;font-size:13px;font-weight:600;">';
        h += '📜 收起战斗详情 ' + badgeHtml + ' <span class="rb-toggle-arrow">▴</span>';
        h += '</button>';
        h += '</div>';

        h += '<div id="battleDetailsBox" style="display:block;margin-top:8px;">';
        h += '<div class="zone-head">【回合战斗细节】</div>';
        h += this.renderCommandBonusPanel(r.commandBonuses, this.isDefenderReport(r) ? 'defender' : 'attacker');
        h += this.renderWallPanel(r.wall, r.terminalReason);
        if (G.UnitEquipment) h += G.UnitEquipment.reportHtml(r.unitEquipment);
        h += this.renderCommanderPanel(r.commanders, this.isDefenderReport(r) ? 'defender' : 'attacker');
        if (initialDistance) h += '<div class="rb-initial-distance">' + G.escapeHtml(initialDistance) + '</div>';
        h += '<div class="blog" style="max-height:480px;overflow-y:auto;">';
        var displayLogs = this.groupRoundSideLogs(logs);
        for (var j = 0; j < displayLogs.length; j++) {
          var line = this.formatReportCombatLogLine(displayLogs[j], r);
          var lineCls = 'logline';
          if (line.indexOf('我方将领加成：') === 0) lineCls += ' mine commander-bonus';
          else if (line.indexOf('敌方将领加成：') === 0) lineCls += ' enemy commander-bonus';
          else if (line.indexOf('我方') === 0) lineCls += ' mine';
          else if (line.indexOf('敌方') === 0) lineCls += ' enemy';
          else if (line.indexOf('★') === 0) lineCls += ' win';
          else if (line.indexOf('✗') === 0) lineCls += ' lose';
          else if (line.indexOf('--') === 0) lineCls += ' round';
          h += '<div class="' + lineCls + '">' + G.escapeHtml(line) + '</div>';
        }
        if (!logs.length) {
          h += '<div class="logline rc-dim" style="text-align:center;padding:8px;">暂无详细回合记录</div>';
        }
        h += '</div>';
        h += '</div>';

        h += '<div class="btn-row report-detail-actions" style="margin-top:10px">';
        h += '<button class="btn" onclick="Game.go(\'reports\')">↩ 返回战报</button>';
        h += '<button class="btn" onclick="Game.go(\'wounded\')">伤兵营</button>';
        h += '<button class="btn warn" onclick="Game.go(\'home\')">🏠 返回首页</button>';
        h += '</div>';
      }
      v.innerHTML = h;
    }
  };

  G.Battle = Battle;
  Core.views.battle = function (v) { Battle.renderTacticalBattle(v); };
  Core.views.report = function (v) { G.go('reports'); };
  Core.views.reports = function (v) { Battle.renderReportsList(v); };
  Core.views.reportDetail = function (v) { Battle.renderReportDetail(v); };
})(window.Game);
