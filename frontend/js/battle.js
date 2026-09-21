/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;
  var Core = G.Core;

  function U(id) {
    if (D.units[id]) return D.units[id];
    if (D.forts && D.forts[id]) return D.forts[id];
    return null;
  }

  // Battle calculation logic has been removed.
  // The backend sends battle results via WebSocket; the frontend just displays them.

  var Battle = {
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
      var s = Core.state;
      var reports = s.reports || (s.reports = []);
      var h = '';
      h += '<div class="menu">';
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
        var token = G.API.getToken();
        G.API.getReports(50).then(function (list) {
          if (G.API.getToken() !== token || Core.state.reports !== reports) return;
          self._reportsHistoryLoading = null;
          if (!Array.isArray(list)) return;
          self._reportsHistoryLoaded = reports;
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
          Core.state.reports.sort(function (a, b) { return (b.time || 0) - (a.time || 0); });
          // 只局部更新一次，不再触发新的历史请求
          if (Core.route === 'reports') self.renderReportsList(v);
          else self.refreshUnread();
        }).catch(function () {
          if (self._reportsHistoryLoading === reports) self._reportsHistoryLoading = null;
        });
      }
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
        return self.syncUnread();
      }).catch(function (err) {
        if (G.API.getToken() !== token) return;
        G.toast(err && err.message ? err.message : '标记已读失败');
        return self.syncUnread();
      });
    },

    reportTitle: function (r) {
      var titles = { conquer: '征服报告', plunder: '掠夺报告', scout: '侦查报告' };
      if (titles[r.action]) return titles[r.action];
      // 历史战报未保存行动字段，只根据旧标题的行动前缀判断，避免误匹配玩家名。
      if (r.cityConquered || r.wildConquered) return '征服报告';
      var match = /^(征服|掠夺|占领野地|剿寇|攻城|战役|防守)/.exec(r.subject || '');
      if (match) return match[1] === '占领野地' ? '征服报告' : match[1] + '报告';
      return { bandit: '剿寇报告', npc: '攻城报告', campaign: '战役报告' }[r.targetType] || '战斗报告';
    },

    wildOccupation: function (r) {
      var subject = r.subject || '';
      var legacyWild = /^(占领野地|掠夺野地)/.test(subject);
      var isWild = r.targetType ? r.targetType === 'wild' : (legacyWild || r.wildConquered === true);
      if (!isWild) return null;
      var action = r.action || (/^占领野地/.test(subject) || r.wildConquered === true ? 'conquer' : /^掠夺野地/.test(subject) ? 'plunder' : '');
      if (action === 'plunder') return { text: '不占领（掠夺行动）', detail: '本次行动仅掠夺资源，不改变野地归属。', tone: '' };
      if (action !== 'conquer') return null;
      // Explicit settlement flags take precedence over victory and historical titles.
      var occupied = typeof r.wildConquered === 'boolean' ? r.wildConquered
        : typeof r.conquered === 'boolean' ? r.conquered
        : /^占领野地胜利·已占领(?:\s|$)/.test(subject) ? true : null;
      if (occupied === true) return { text: '占领成功', detail: '本次征服已成功占领该野地，结算时已纳入我方领地。', tone: 'w' };
      if (occupied === false || r.win === false) return {
        text: '未占领', tone: 'l',
        detail: r.win === false ? '本次战斗未获胜，未能占领该野地。' : '本次战斗获胜，但结算记录为未占领；战报未记录具体原因。'
      };
      return { text: '占领结果未记录', detail: '该历史战报仅记录战斗胜负，无法确认当时是否占领；可前往野地查看当前归属。', tone: '' };
    },

    renderReportCard: function (r) {
      var d = new Date(r.time);
      var ts = (d.getMonth() + 1) + '-' + String(d.getDate()).padStart(2, '0') + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
      var unread = !r.readAt;
      var h = '';
      h += '<div class="report-card ' + (r.win ? 'win' : 'lose') + (unread ? ' unread' : '') + '">';
      h += '<div class="rc-head" onclick="Game.Battle.toggleReport(\'' + r.id + '\')" style="cursor:pointer">';
      h += '<span class="rc-subject">' + (unread ? '<span class="unread-dot"></span>' : '') + this.reportTitle(r) + '</span>';
      h += '<span class="rc-time">' + ts + '</span>';
      h += '<span class="rc-result ' + (r.win ? 'w' : 'l') + '">' + (r.win ? '胜' : '败') + '</span>';
      h += '</div>';
      h += '<div class="rc-body" onclick="Game.Battle.toggleReport(\'' + r.id + '\')" style="cursor:pointer">';
      h += '<div class="rc-line">' + G.escapeHtml(r.attackerName || r.fromName || '我方') + ' → ' + G.escapeHtml(r.toName || '目标') + ' ' + G.escapeHtml(r.toCoord || '') + '</div>';
      var occupation = this.wildOccupation(r);
      if (occupation) h += '<div class="rc-line"><b>占领结果:</b> ' + occupation.text + '</div>';
      h += '</div>';
      h += '<div id="rdetail_' + r.id + '" class="rc-expand" style="display:none"></div>';
      h += '<div class="btn-row" style="margin-top:4px"><button class="btn sm" onclick="Game.Battle.viewReportDetail(\'' + r.id + '\')">查看完整战报</button></div>';
      h += '</div>';
      return h;
    },

    _formatRes: function (res) {
      if (!res) return '';
      var names = { food: '粮', steel: '钢', oil: '油', rare: '稀矿', gold: '金' };
      var parts = [];
      for (var k in res) {
        if (res[k] > 0) parts.push((names[k] || k) + G.fmt(res[k]));
      }
      return parts.join(' ');
    },

    renderScoutReportCard: function (r) {
      var d = new Date(r.time);
      var ts = (d.getMonth() + 1) + '-' + String(d.getDate()).padStart(2, '0') + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
      var data = r.data || {};
      var isDefense = data.perspective === 'defender';
      var isWin = isDefense ? data.intercepted : data.showCityInfo;
      var player = Core.state.player || {};
      var attackerName = data.attackerName || (isDefense ? '未知敌军' : (player.name || player.username || '我方'));
      var unread = !r.readAt;
      var h = '';
      h += '<div class="report-card ' + (isWin ? 'win' : 'lose') + (unread ? ' unread' : '') + '">';
      h += '<div class="rc-head" onclick="Game.Battle.toggleReport(\'' + r.id + '\')" style="cursor:pointer">';
      h += '<span class="rc-subject">' + (unread ? '<span class="unread-dot"></span>' : '') + '侦查报告</span>';
      h += '<span class="rc-time">' + ts + '</span>';
      h += '<span class="rc-result ' + (isWin ? 'w' : 'l') + '">' + (isDefense ? (isWin ? '已拦截' : '被侦查') : (isWin ? '胜' : '败')) + '</span>';
      h += '</div>';
      h += '<div class="rc-body" onclick="Game.Battle.toggleReport(\'' + r.id + '\')" style="cursor:pointer">';
      h += '<div class="rc-line">' + G.escapeHtml(attackerName) + ' → ' + G.escapeHtml(data.targetName || '目标') + ' (' + G.escapeHtml(String(data.x == null ? '?' : data.x)) + ',' + G.escapeHtml(String(data.y == null ? '?' : data.y)) + ')</div>';
      h += '</div>';
      h += '<div id="rdetail_' + r.id + '" class="rc-expand" style="display:none"></div>';
      h += '<div class="btn-row" style="margin-top:6px"><button class="btn sm" onclick="Game.Battle.viewReportDetail(\'' + r.id + '\')">查看完整战报</button></div>';
      h += '</div>';
      return h;
    },

    renderReportBoard: function (r, isInstant) {
      var esc = G.escapeHtml;
      var d = new Date(r.time);
      var ts = (d.getMonth() + 1) + '-' + String(d.getDate()).padStart(2, '0') + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0') + ':' + String(d.getSeconds()).padStart(2, '0');
      var h = '';
      h += '<div class="report-board ' + (r.win ? 'win' : 'lose') + '">';
      h += '<div class="rb-subject">【战斗报告】' + esc(r.subject || '交锋战情') + '</div>';
      h += '<div class="rb-meta-box">';
      h += '<div class="rb-line"><b>出发地:</b> ' + esc(r.fromName || '我方') + ' ' + esc(r.fromCoord || '') + '</div>';
      h += '<div class="rb-line"><b>目的地:</b> ' + esc(r.toName || '目标') + ' ' + esc(r.toCoord || '') + '</div>';
      h += '<div class="rb-line"><b>时　间:</b> ' + ts + '</div>';
      var occupation = this.wildOccupation(r);

      var resText = r.win ? '战斗大捷' : '战斗失利';
      if (r.cityConquered) resText = '征服成功';

      h += '<div class="rb-line"><b>' + (occupation ? '战斗结果' : '结　果') + ':</b> <span class="rb-res-badge ' + (r.win ? 'w' : 'l') + '">' + resText + '</span></div>';
      if (occupation) {
        h += '<div class="rb-line"><b>占领结果:</b> <span class="rb-res-badge ' + occupation.tone + '">' + occupation.text + '</span></div>';
        h += '<div class="rb-line">' + occupation.detail + '</div>';
      }
      h += '</div>';
      h += '<div class="rb-divider"></div>';
      var actName = ({
        bandit: '剿寇',
        npc: '攻城',
        player: (r.action === 'plunder' ? '掠夺' : '征服'),
        wild: (r.action === 'plunder' ? '野地掠夺' : '野地征服'),
        campaign: '战役'
      }[r.targetType] || '出征');
      var narrative = '一支部队对 ' + esc(r.toName || '目标') + ' ' + esc(r.toCoord || '') + ' 进行了' + actName + '。';
      narrative += (r.win ? ' 我方攻势势如破竹，战役获得胜利！' : ' 我方遭受强烈阻击，战役未能获胜。');
      h += '<div class="rb-narrative">' + narrative + '</div>';
      if (r.cityConquered) h += '<div class="rb-line" style="color:#d97706;font-weight:600">★ 已成功征服该城市</div>';
      var pl = this._formatRes(r.plunder);
      if (pl) h += '<div class="rb-line"><b>掠夺资源:</b> ' + pl + '</div>';
      if (r.exp > 0) h += '<div class="rb-line"><b>获得经验:</b> ' + G.fmt(r.exp) + '</div>';
      h += '<div class="rb-divider"></div>';
      h += '<div class="rb-side w">【我方军队】</div>';
      h += this.renderTroopCommander('mine', r.commanders && r.commanders.attacker);
      h += this.renderArmyUnits('mine', r.initialAttacker, r.survivorAttacker, r.roundLogs);
      h += '<div class="rb-divider"></div>';
      h += '<div class="rb-side l">【敌方军队】</div>';
      h += this.renderTroopCommander('enemy', r.commanders && r.commanders.defender);
      h += this.renderArmyUnits('enemy', r.initialDefender, r.survivorDefender, r.roundLogs);
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

    renderArmyUnits: function (side, initMap, survMap, roundLogs) {
      var esc = G.escapeHtml;
      var h = '';
      var has = false;

      function findUnitIdByName(name) {
        if (!name) return null;
        name = name.trim();
        for (var k in D.units) {
          var unit = D.units[k];
          // 历史战报只存旧类别名；完整名称首个“-”前保留该类别，兼容旧日志的兵力与战损反查。
          if (unit && (unit.name === name || unit.name.split('-')[0] === name)) return k;
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
            if (aUid && aSide === side) {
              logMaxCount[aUid] = Math.max(logMaxCount[aUid] || 0, aCount);
            }

            var dSide = (atkMatch[4] === '我' || atkMatch[4] === '我方') ? 'mine' : 'enemy';
            var dName = atkMatch[5].trim();
            var dBefore = parseInt(atkMatch[6], 10);
            var dKills = parseInt(atkMatch[7], 10);
            var dUid = findUnitIdByName(dName);
            if (dUid && dSide === side) {
              logMaxCount[dUid] = Math.max(logMaxCount[dUid] || 0, dBefore);
              logTotalKilled[dUid] = (logTotalKilled[dUid] || 0) + dKills;
            }
            continue;
          }

          // 模式 B: 移动/就位行 (无被击毁数据)
          // 例: "我方火箭(1054) 前进 200 距离->350"
          // 例: "我方卡车(1000) 前进0(射程内) 距离0"
          var moveMatch = line.match(/^(我方|敌方)(.+?)\((\d+)\)/);
          if (moveMatch) {
            var mSide = moveMatch[1] === '我方' ? 'mine' : 'enemy';
            if (mSide === side) {
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
        var u = U(uid);
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

    renderScoutDefenseReportBoard: function (r) {
      var data = r.data || {};
      var esc = G.escapeHtml;
      var intercepted = !!data.intercepted;
      var when = new Date(r.time);
      var timeText = (when.getMonth() + 1) + '-' + when.getDate() + ' ' +
        [when.getHours(), when.getMinutes(), when.getSeconds()].map(function (n) { return String(n).padStart(2, '0'); }).join(':');
      var resultText = intercepted ? '拦截成功，敌方未获取情报' : '敌方侦查成功，我方城市情报已被探查';
      var h = '<div class="report-board ' + (intercepted ? 'win' : 'lose') + '">';
      h += '<div class="rb-subject">【敌军侦查报告】' + esc(data.attackerName || '未知敌军') + '</div>';
      h += '<div class="rb-meta-box">';
      h += '<div class="rb-line"><b>来袭玩家:</b> ' + esc(data.attackerName || '未知敌军') + '</div>';
      h += '<div class="rb-line"><b>出发坐标:</b> (' + (data.fromX == null ? '?' : data.fromX) + ',' + (data.fromY == null ? '?' : data.fromY) + ')</div>';
      h += '<div class="rb-line"><b>被侦查城市:</b> ' + esc(data.targetName || '我方城市') + ' (' + data.x + ',' + data.y + ')</div>';
      h += '<div class="rb-line"><b>发生时间:</b> ' + timeText + '</div>';
      h += '<div class="rb-line"><b>拦截结果:</b> <span class="rb-res-badge ' + (intercepted ? 'w' : 'l') + '">' + resultText + '</span></div></div>';
      h += '<div class="rb-divider"></div><div class="rb-side w">【我方驻防侦察机】</div>';
      h += '<div class="rb-unit mine">驻守: ' + data.myScouts + ' 架 ➔ 幸存: ' + (data.myScouts - data.myLost) + ' 架（损失 ' + data.myLost + ' 架）</div>';
      h += this.renderScoutWounded(data);
      if (!data.myScouts) h += '<div class="rb-line rc-dim">我方未驻防侦察机，敌方未遭空中拦截。</div>';
      h += '<div class="rb-divider"></div><div class="rb-side l">【敌方来袭侦察机】</div>';
      h += '<div class="rb-unit enemy">出动: ' + data.enemyScouts + ' 架 ➔ 幸存: ' + (data.enemyScouts - data.enemyLost) + ' 架（击落 ' + data.enemyLost + ' 架）</div>';
      return h + '</div>';
    },

    renderScoutWounded: function (data) {
      if (!data.wounded || !data.wounded.scout) return '';
      return '<div class="rb-line">我方伤兵入营：侦察机 ' + G.fmt(data.wounded.scout) + ' 架（7天内可付费治疗）</div>';
    },

    renderScoutReportBoard: function (r) {
      if (r.data && r.data.perspective === 'defender') return this.renderScoutDefenseReportBoard(r);
      var esc = G.escapeHtml;
      var d = new Date(r.time);
      var ts = (d.getMonth() + 1) + '-' + String(d.getDate()).padStart(2, '0') + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0') + ':' + String(d.getSeconds()).padStart(2, '0');
      var data = r.data || {};
      var isWin = data.showCityInfo;
      var isZeroEnemy = (data.enemyScouts === 0 || !data.enemyScouts);
      var resultText = {
        overwhelming_defeat: '惨败（敌军势大）',
        close_match_loss: '失败（激战落败）',
        close_match_win: '险胜（激战获胜）',
        overwhelming_victory: (isZeroEnemy ? '大胜（无敌机拦截）' : '大胜（碾压全歼）')
      }[data.result] || (isWin ? (isZeroEnemy ? '大胜（无敌机拦截）' : '大胜') : '侦查失败');
      var rLv = (data.reconLevel != null) ? data.reconLevel : 0;
      var tierName = data.tierName || '常规侦查';

      var h = '';
      h += '<div class="report-board ' + (isWin ? 'win' : 'lose') + '">';
      h += '<div class="rb-subject">【侦查报告】' + esc(data.targetName || '?') + '</div>';
      h += '<div class="rb-meta-box">';
      h += '<div class="rb-line"><b>侦查目标:</b> ' + esc(data.targetName || '?') + ' (' + (data.x || 0) + ',' + (data.y || 0) + ')</div>';
      h += '<div class="rb-line"><b>发生时间:</b> ' + ts + '</div>';
      h += '<div class="rb-line"><b>侦查技术:</b> <span class="rb-tier-tag">Lv.' + rLv + ' ' + esc(tierName) + '</span>' + '</div>';
      h += this.renderScoutWounded(data);
      h += '<div class="rb-line"><b>侦查结果:</b> <span class="rb-res-badge ' + (isWin ? 'w' : 'l') + '">' + resultText + '</span></div>';
      h += '</div>';
      h += '<div class="rb-divider"></div>';
      h += '<div class="rb-side ' + (isWin ? 'w' : 'l') + '">【我方侦察机】</div>';
      h += '<div class="rb-unit mine">出动: ' + (data.myScouts || 0) + ' 架 ➔ 幸存: ' + ((data.myScouts || 0) - (data.myLost || 0)) + ' 架 <span class="rb-loss-tag">(' + (data.myLost > 0 ? '损失 -' + data.myLost : '零损失') + ')</span></div>';
      h += '<div class="rb-divider"></div>';
      h += '<div class="rb-side ' + (isWin ? 'l' : 'w') + '">【敌方侦察机】</div>';
      if (isZeroEnemy) {
        h += '<div class="rb-unit enemy">驻守: 0 架 ➔ 幸存: 0 架 <span class="rb-loss-tag">(空域畅通·无敌机拦截)</span></div>';
      } else {
        h += '<div class="rb-unit enemy">驻守: ' + (data.enemyScouts || 0) + ' 架 ➔ 幸存: ' + ((data.enemyScouts || 0) - (data.enemyLost || 0)) + ' 架 <span class="rb-loss-tag">(' + (data.enemyLost > 0 ? '击落 -' + data.enemyLost : '未击落') + ')</span></div>';
      }
      h += '<div class="rb-divider"></div>';
      if (data.combatLog && data.combatLog.length) {
        h += '<div class="rb-side">【空战记录】</div>';
        for (var i = 0; i < data.combatLog.length; i++) {
          h += '<div class="rb-line rc-dim">' + esc(data.combatLog[i]) + '</div>';
        }
        h += '<div class="rb-divider"></div>';
      }
      if (data.showCityInfo) {
        h += '<div class="rb-side w">【目标情报】</div>';
        if (data.commander) h += '<div class="rb-line"><b>统帅:</b> ' + esc(data.commander) + '</div>';
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

        // 城防工事 (Lv.1+)
        if (data.forts) {
          var fortStr = '';
          for (var fid in data.forts) {
            if (data.forts[fid] > 0) {
              var fu = D.forts && D.forts[fid];
              fortStr += (fu ? fu.name : fid) + 'x' + data.forts[fid] + ' ';
            }
          }
          h += '<div class="rb-line"><b>城防工事:</b> ' + (fortStr ? esc(fortStr.trim()) : '无防御工事') + '</div>';
        } else if (data.fortsVague) {
          h += '<div class="rb-line rc-dim"><b>城防工事:</b> ' + esc(data.fortsVague) + '</div>';
        }

        // 守军兵力 (Lv.0 模糊 / Lv.2+ 精确)
        if (data.army) {
          var armyUnits = [];
          for (var aid in data.army) {
            if (data.army[aid] > 0) {
              var au = U(aid);
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

        // 城市主要建筑 (Lv.3+)
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

        // 军事科研科技 (Lv.4+)
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

        // 驻留将领 (Lv.3 数量 / Lv.5 全维档案)
        if (data.officers && data.officers.length) {
          h += '<div class="rb-line"><b>驻留将领:</b> 共 ' + data.officers.length + ' 名</div>';
          for (var oi = 0; oi < data.officers.length; oi++) {
            var off = data.officers[oi];
            var starIcons = off.star ? '★'.repeat(off.star) : '';
            h += '<div class="rb-unit enemy" style="padding-left:12px;font-size:11px;">' +
                 '[' + (off.role === 'commander' ? '统帅' : '军官') + '] ' +
                 (starIcons ? '<span style="color:#ffe14a">' + starIcons + '</span> ' : '') +
                 esc(off.name || '无名将领') + ' Lv.' + (off.level || 1) +
                 ' (军' + (off.military || 0) + ' 学' + (off.knowledge || 0) + ' 后' + (off.logistics || 0) + ')' +
                 '</div>';
          }
        } else if (data.officerCount != null) {
          h += '<div class="rb-line"><b>驻留将领:</b> 共 ' + data.officerCount + ' 名 <span class="rb-dim">(将领档案需 Lv.5 解锁)</span></div>';
        }

        // 防守综合战力 (Lv.5+)
        if (data.defensePower != null) {
          h += '<div class="rb-line" style="color:#ffb870"><b>防守战力:</b> ' + G.fmt(data.defensePower) +
               ' <span class="rb-threat-badge">' + esc(data.threatLevel || '') + '</span></div>';
        }

        // 迷雾锁定提示 (仅对城市类目标展示)
        if (data.targetKind !== 'wild' && data.targetKind !== 'wild_gather') {
          var locks = [];
          if (rLv < 1) locks.push('外围城防工事 (需 侦察技术 Lv.1)');
          if (rLv < 2) locks.push('精确守军兵力与统帅 (需 侦察技术 Lv.2)');
          if (rLv < 3) locks.push('主要城建建筑等级 (需 侦察技术 Lv.3)');
          if (rLv < 4) locks.push('战略科技与可掠夺测算 (需 侦察技术 Lv.4)');
          if (rLv < 5) locks.push('将领全维档案与综合战力 (需 侦察技术 Lv.5)');
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

    toggleReport: function (reportId) {
      var box = document.getElementById('rdetail_' + reportId);
      if (!box) return;
      if (box.style.display === 'none') {
        var r = this.findReport(reportId);
        if (!r) return;
        if (r.type === 'scout') {
          box.innerHTML = this.renderScoutReportBoard(r);
        } else {
          box.innerHTML = this.renderReportBoard(r, false);
        }
        box.style.display = 'block';
        // 展开预览即视为已读
        this.markOneRead(reportId);
      } else {
        box.style.display = 'none';
      }
    },

    viewReportDetail: function (reportId) {
      var r = this.findReport(reportId);
      if (!r) { G.toast('战报不存在'); return; }
      this._viewReport = r;
      this.markOneRead(reportId);
      Core.history.push(Core.route);
      Core.route = 'reportDetail';
      Core.render();
    },

    findReport: function (reportId) {
      var reports = Core.state.reports || [];
      var key = String(reportId);
      for (var i = 0; i < reports.length; i++) {
        if (reports[i].id != null && String(reports[i].id) === key) return reports[i];
      }
      return null;
    },

    renderCommanderPanel: function (commanders) {
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

        var skills = Array.isArray(commander.skills) ? commander.skills : [];
        if (skills.length) {
          out += '<div class="commander-skills">';
          for (var i = 0; i < skills.length; i++) {
            var skill = skills[i] || {};
            out += '<div class="commander-skill"><b>' + esc(String(skill.name || '未知技能')) + ' Lv.' + esc(String(skill.level != null ? skill.level : 0)) + '</b>';
            if (skill.description) out += '<span>' + esc(String(skill.description)) + '</span>';
            out += '</div>';
          }
          out += '</div>';
        } else {
          out += '<div class="commander-no-skills">无技能信息</div>';
        }
        return out + '</div>';
      }

      h += renderSide('攻方将领', commanders && commanders.attacker, 'attacker');
      h += renderSide('守方将领', commanders && commanders.defender, 'defender');
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
     * 从军情打开已到达的战术战斗，并将所有存活单位初始为待命。
     * @param {number} marchId - 战斗关联的行军 ID
     */
    openTactical: function (marchId) {
      var self = this;
      G.API.getTacticalBattle(marchId).then(function (battle) {
        self._activeTactical = battle;
        self.resetTacticalOrders();
        Core.go('battle');
      }).catch(function (err) {
        G.toast(err.message || '战斗会话已结束或不可用');
      });
    },

    /** 为当前存活单位生成本回合默认待命命令。 */
    resetTacticalOrders: function () {
      var army = (this._activeTactical && this._activeTactical.attackerArmy) || {};
      this._tacticalOrders = {};
      Object.keys(army).forEach(function (unitId) {
        // 不写入显式命令，提交时由后端按兵种的默认推进规则处理。
        this._tacticalOrders[unitId] = { action: null, focusTarget: null };
      }, this);
      this.startTacticalTimer();
    },

    /** 设置一个兵种本回合的移动命令。 */
    setTacticalAction: function (unitId, action) {
      var order = this._tacticalOrders[unitId] || { focusTarget: null };
      order.action = action;
      this._tacticalOrders[unitId] = order;
      Core.render();
    },

    /** 设置一个兵种的可选集火目标；不选择时后端按常规规则索敌。 */
    setTacticalFocus: function (unitId, targetId) {
      var order = this._tacticalOrders[unitId] || { action: null };
      order.focusTarget = targetId || null;
      this._tacticalOrders[unitId] = order;
      Core.render();
    },

    /** 启动当前回合的十秒倒计时，倒计时结束自动提交现有命令。 */
    startTacticalTimer: function () {
      this.stopTacticalTimer();
      var battle = this._activeTactical;
      if (!battle || battle.finished) return;
      this._tacticalDeadline = Date.now() + 15000;
      var self = this;
      function update() {
        var remaining = Math.max(0, self._tacticalDeadline - Date.now());
        var countdown = document.getElementById('tacticalCountdown');
        if (countdown) countdown.textContent = '剩余 ' + Math.ceil(remaining / 1000) + ' 秒自动执行';
        if (remaining <= 0) {
          self.stopTacticalTimer();
          self.executeTacticalRound();
        }
      }
      update();
      this._tacticalTimer = setInterval(update, 250);
    },

    /** 离开战斗页或提交回合时清理倒计时，避免旧战斗继续发请求。 */
    stopTacticalTimer: function () {
      if (this._tacticalTimer) clearInterval(this._tacticalTimer);
      this._tacticalTimer = null;
      this._tacticalDeadline = 0;
    },

    /** 提交当前面板上的命令；一次请求只推进一回合。 */
    executeTacticalRound: function () {
      var self = this;
      var battle = this._activeTactical;
      if (!battle || battle.finished || this._tacticalSubmitting) return;
      this.stopTacticalTimer();
      this._tacticalSubmitting = true;
      G.API.commandTacticalBattle(battle.marchId, this._tacticalOrders).then(function (next) {
        self._activeTactical = next;
        self.resetTacticalOrders();
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '本回合指令未能执行');
        Core.render();
      }).finally(function () {
        self._tacticalSubmitting = false;
      });
    },

    /** 绘制地图两端的敌我部队、命令面板和最近的回合日志。 */
    renderTacticalBattle: function (v) {
      var battle = this._activeTactical;
      if (!battle) { G.go('alerts'); return; }
      var esc = G.escapeHtml;
      var distance = Math.max(1, battle.initialDistance || 1);
      var attacker = battle.attackerArmy || {};
      var defender = battle.defenderArmy || {};
      var attackerPositions = battle.attackerPositions || {};
      var defenderPositions = battle.defenderPositions || {};
      var orders = this._tacticalOrders || {};
      var h = '<div class="tactical-battle">';
      h += '<div class="tactical-head"><div><div class="title">战术指挥：' + esc(battle.targetName || '敌军') + '</div>';
      h += '<div class="desc">第 ' + (battle.round || 0) + '/' + (battle.maxRound || 30) + ' 回合 · 每次执行只结算一回合</div></div>';
      h += '<div class="tactical-head-actions"><span id="tacticalCountdown" class="tactical-countdown">剩余 15 秒自动执行</span><button class="btn sm" onclick="Game.go(\'alerts\')">返回军情</button></div></div>';
      h += '<div class="tactical-map"><div class="tactical-base mine-base">我军阵地</div><div class="tactical-base foe-base">敌军阵地</div><div class="tactical-axis"></div>';
      h += this.renderTacticalMarkers(attacker, attackerPositions, distance, 'mine');
      h += this.renderTacticalMarkers(defender, defenderPositions, distance, 'foe');
      h += '<div class="tactical-distance">战场宽度 ' + distance + '</div></div>';
      if (battle.finished) {
        var result = battle.result || {};
        h += '<div class="tactical-finish ' + (result.win ? 'win' : 'lose') + '"><b>' + (result.win ? '战斗胜利' : '战斗结束') + '</b><span>战果已写入战报，幸存部队将按原路线返程。</span></div>';
      } else {
        h += '<div class="zone-head">我军本回合命令</div><div class="tactical-orders">';
        Object.keys(attacker).forEach(function (unitId) {
          var unit = U(unitId) || {};
          var order = orders[unitId] || { action: null, focusTarget: null };
          var encodedId = JSON.stringify(unitId);
          h += '<div class="tactical-order-card"><div class="tactical-order-name">' + esc(unit.name || unitId) + ' <b>×' + attacker[unitId] + '</b></div><div class="tactical-actions">';
          [['ADVANCE', '前进'], ['RETREAT', '后退'], ['HOLD', '待命']].forEach(function (choice) {
            h += '<button class="btn sm ' + (order.action === choice[0] ? 'ok' : '') + '" onclick="Game.Battle.setTacticalAction(' + encodedId + ',\'' + choice[0] + '\')">' + choice[1] + '</button>';
          });
          h += '</div><label class="tactical-focus">集火 <select onchange="Game.Battle.setTacticalFocus(' + encodedId + ',this.value)">';
          h += '<option value="">常规索敌</option>';
          Object.keys(defender).forEach(function (targetId) {
            var target = U(targetId) || {};
            h += '<option value="' + esc(targetId) + '"' + (order.focusTarget === targetId ? ' selected' : '') + '>' + esc(target.name || targetId) + '</option>';
          });
          h += '</select></label></div>';
        });
        h += '</div><div class="btn-row tactical-execute"><button class="btn ok" onclick="Game.Battle.executeTacticalRound()">执行第 ' + ((battle.round || 0) + 1) + ' 回合</button></div>';
      }
      var logs = String(battle.log || '').trim().split('\n').filter(Boolean);
      h += '<div class="zone-head">战场记录</div><div class="blog tactical-log">';
      logs.slice(-24).forEach(function (line) { h += '<div class="logline">' + esc(line) + '</div>'; });
      h += '</div></div>';
      v.innerHTML = h;
      this.startTacticalTimer();
    },

    /** 根据服务端的一维战场坐标，在地图上渲染某一方的单位标记。 */
    renderTacticalMarkers: function (army, positions, distance, side) {
      var esc = G.escapeHtml;
      var html = '';
      Object.keys(army || {}).forEach(function (unitId) {
        var unit = U(unitId) || {};
        var position = positions[unitId] != null ? positions[unitId] : (side === 'mine' ? 0 : distance);
        var percent = Math.max(3, Math.min(97, Math.round(position / distance * 100)));
        html += '<div class="tactical-marker ' + side + '" style="left:' + percent + '%" title="' + esc(unit.name || unitId) + ' ×' + army[unitId] + '"><span>' + esc(unit.name || unitId) + '</span><b>×' + army[unitId] + '</b></div>';
      });
      return html;
    },

    renderReportDetail: function (v) {
      var r = this._viewReport;
      if (!r) { G.go('reports'); return; }
      var h = '';
      if (r.type === 'scout') {
        h += this.renderScoutReportBoard(r);
        h += '<div class="btn-row report-detail-actions" style="margin-top:10px">';
        h += '<button class="btn" onclick="Game.go(\'reports\')">↩ 返回战报</button>';
        h += '<button class="btn" onclick="Game.go(\'wounded\')">伤兵营</button>';
        h += '<button class="btn warn" onclick="Game.go(\'home\')">🏠 返回首页</button>';
        h += '</div>';
      } else {
        h += this.renderReportBoard(r, false);
        var logs = Array.isArray(r.roundLogs) ? r.roundLogs : [];
        var roundCount = 0;
        for (var i = 0; i < logs.length; i++) {
          if (String(logs[i] || '').indexOf('--') === 0) roundCount++;
        }
        var countText = roundCount > 0 ? ('共 ' + roundCount + ' 回合') : (logs.length > 0 ? (logs.length + ' 条记录') : '');
        var badgeHtml = countText ? '<span class="rb-details-count" style="font-size:11px;font-weight:normal;opacity:0.85;margin-left:4px;">(' + countText + ')</span>' : '';

        h += '<div class="report-details-toggle-wrap" style="margin:14px 0 6px;">';
        h += '<button type="button" class="btn report-details-toggle-btn" id="btnBattleDetails" onclick="Game.Battle.toggleBattleDetails()" style="width:100%;display:flex;align-items:center;justify-content:center;gap:4px;padding:8px 12px;font-size:13px;font-weight:600;">';
        h += '📜 查看战斗详情 ' + badgeHtml + ' <span class="rb-toggle-arrow">▾</span>';
        h += '</button>';
        h += '</div>';

        h += '<div id="battleDetailsBox" style="display:none;margin-top:8px;">';
        h += '<div class="zone-head">【回合战斗细节】</div>';
        h += this.renderCommanderPanel(r.commanders);
        h += '<div class="blog" style="max-height:480px;overflow-y:auto;">';
        for (var j = 0; j < logs.length; j++) {
          var line = String(logs[j] || '');
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
