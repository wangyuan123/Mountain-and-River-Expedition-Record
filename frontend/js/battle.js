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
    /** 计算战报未读数（只统计内存里的列表） */
    unreadCount: function () {
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
          span.className = 'nav-badge alert-dot';
          span.textContent = n > 99 ? '99+' : String(n);
          nav.appendChild(span);
        }
      }
    },

    renderReportsList: function (v) {
      var s = Core.state;
      var reports = s.reports || [];
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
      // 内存里没有战报时只拉取一次历史，避免空列表导致渲染递归。
      if (!reports.length && G.API && typeof G.API.getReports === 'function' && !this._reportsHistoryLoaded && !this._reportsHistoryLoading) {
        var self = this;
        this._reportsHistoryLoading = true;
        G.API.getReports(50).then(function (list) {
          self._reportsHistoryLoading = false;
          self._reportsHistoryLoaded = true;
          if (!Array.isArray(list)) return;
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
          self._reportsHistoryLoading = false;
          self._reportsHistoryLoaded = true;
        });
      }
    },

    /** 一键全部已读 */
    markAllRead: function () {
      if (!G.API || !G.API.markAllReportsRead) return;
      var self = this;
      G.API.markAllReportsRead().then(function () {
        var reports = (G.state && G.state.reports) || [];
        for (var i = 0; i < reports.length; i++) {
          if (!reports[i].readAt) reports[i].readAt = Date.now();
        }
        G.toast('已全部标为已读');
        if (Core.route === 'reports' || Core.route === 'reportDetail') {
          Core.render();
        } else {
          self.refreshUnread();
        }
      }).catch(function (err) {
        G.toast(err && err.message ? err.message : '操作失败');
      });
    },

    /** 标记单条已读（本地 + 后端） */
    markOneRead: function (reportId) {
      if (reportId == null) return;
      var key = String(reportId);
      var reports = (G.state && G.state.reports) || [];
      var localChanged = false;
      for (var i = 0; i < reports.length; i++) {
        if (String(reports[i].id) === key && !reports[i].readAt) {
          reports[i].readAt = Date.now();
          localChanged = true;
          break;
        }
      }
      if (localChanged) this.refreshUnread();
      // 战斗战报以 "battle-" 开头的伪 ID 不调后端（仅本地标记）
      if (key.indexOf('battle-') === 0) return;
      if (G.API && G.API.markReportRead) {
        G.API.markReportRead(reportId).catch(function () {});
      }
    },

    renderReportCard: function (r) {
      var d = new Date(r.time);
      var ts = (d.getMonth() + 1) + '-' + String(d.getDate()).padStart(2, '0') + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
      var unread = !r.readAt;
      var h = '';
      h += '<div class="report-card ' + (r.win ? 'win' : 'lose') + (unread ? ' unread' : '') + '">';
      h += '<div class="rc-head" onclick="Game.Battle.toggleReport(\'' + r.id + '\')" style="cursor:pointer">';
      h += '<span class="rc-subject">' + (unread ? '<span class="unread-dot"></span>' : '') + G.escapeHtml(r.subject || '战斗报告') + '</span>';
      h += '<span class="rc-time">' + ts + '</span>';
      h += '<span class="rc-result ' + (r.win ? 'w' : 'l') + '">' + (r.win ? '胜' : '败') + '</span>';
      h += '</div>';
      h += '<div class="rc-body" onclick="Game.Battle.toggleReport(\'' + r.id + '\')" style="cursor:pointer">';
      h += '<div class="rc-line">' + G.escapeHtml(r.fromName || '我方') + ' → ' + G.escapeHtml(r.toName || '目标') + ' ' + G.escapeHtml(r.toCoord || '') + '</div>';
      var act = { bandit: '剿寇', npc: '攻城', player: '征服/掠夺', wild: '野地', campaign: '战役' }[r.targetType] || '出征';
      h += '<div class="rc-line rc-dim">' + act + ' · ' + (r.win ? '我方获胜' : '我方失败') + (r.cityConquered ? ' · 已征服' : '') + '</div>';
      var pl = this._formatRes(r.plunder);
      if (pl) h += '<div class="rc-line">掠夺: ' + pl + '</div>';
      h += '<div class="rc-line">' + this._formatForce('我军', r.survivorAttacker) + ' / ' + this._formatForce('敌军', r.survivorDefender) + '</div>';
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

    _formatForce: function (label, map) {
      if (!map) return label + ' -';
      var parts = [];
      for (var uid in map) {
        if (map[uid] > 0) {
          var u = U(uid);
          parts.push((u ? u.name : uid) + 'x' + G.fmt(map[uid]));
        }
      }
      return label + ' ' + (parts.length ? parts.join(' ') : '-');
    },

    renderScoutReportCard: function (r) {
      var d = new Date(r.time);
      var ts = (d.getMonth() + 1) + '-' + String(d.getDate()).padStart(2, '0') + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
      var data = r.data || {};
      var isWin = data.showCityInfo;
      var resultText = {
        overwhelming_defeat: '惨败（敌军势大）',
        close_match_loss: '失败（激战落败）',
        close_match_win: '险胜（激战获胜）',
        overwhelming_victory: '大胜（碾压全歼）'
      }[data.result] || '未知';
      var unread = !r.readAt;
      var h = '';
      h += '<div class="report-card ' + (isWin ? 'win' : 'lose') + (unread ? ' unread' : '') + '">';
      h += '<div class="rc-head" onclick="Game.Battle.toggleReport(\'' + r.id + '\')" style="cursor:pointer">';
      h += '<span class="rc-subject">' + (unread ? '<span class="unread-dot"></span>' : '') + '侦查报告</span>';
      h += '<span class="rc-time">' + ts + '</span>';
      h += '<span class="rc-result ' + (isWin ? 'w' : 'l') + '">' + (isWin ? '胜' : '败') + '</span>';
      h += '</div>';
      h += '<div class="rc-body" onclick="Game.Battle.toggleReport(\'' + r.id + '\')" style="cursor:pointer">';
      h += '<div class="rc-line">侦查 ' + G.escapeHtml(data.targetName || '?') + ' (' + (data.x || 0) + ',' + (data.y || 0) + ')</div>';
      h += '<div class="rc-line rc-dim">' + resultText + '</div>';
      h += '<div class="rc-line">我方侦察机 ' + (data.myScouts || 0) + '->' + ((data.myScouts || 0) - (data.myLost || 0)) + '(损' + (data.myLost || 0) + ') / 敌方侦察机 ' + (data.enemyScouts || 0) + '->' + ((data.enemyScouts || 0) - (data.enemyLost || 0)) + '(歼' + (data.enemyLost || 0) + ')</div>';
      h += '</div>';
      h += '<div id="rdetail_' + r.id + '" class="rc-expand" style="display:none"></div>';
      h += '<div class="btn-row" style="margin-top:4px"><button class="btn sm" onclick="Game.Battle.viewReportDetail(\'' + r.id + '\')">查看完整战报</button></div>';
      h += '</div>';
      return h;
    },

    renderReportBoard: function (r, isInstant) {
      var esc = G.escapeHtml;
      var d = new Date(r.time);
      var ts = (d.getMonth() + 1) + '-' + String(d.getDate()).padStart(2, '0') + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0') + ':' + String(d.getSeconds()).padStart(2, '0');
      var h = '';
      h += '<div class="report-board ' + (r.win ? 'win' : 'lose') + '">';
      h += '<div class="rb-subject">主题: ' + esc(r.subject || '战斗报告') + '</div>';
      h += '<div class="rb-line">出发地: ' + esc(r.fromName || '我方') + ' ' + esc(r.fromCoord || '') + '</div>';
      h += '<div class="rb-line">目的地: ' + esc(r.toName || '目标') + ' ' + esc(r.toCoord || '') + '</div>';
      h += '<div class="rb-line">时间: ' + ts + '</div>';
      h += '<div class="rb-divider"></div>';
      var narrative = '一支部队对' + esc(r.toName || '目标') + ' ' + esc(r.toCoord || '') + '进行了' + ({ bandit: '剿寇', npc: '攻城', player: '征服/掠夺', wild: '野地', campaign: '战役' }[r.targetType] || '出征') + '。';
      narrative += '我方' + (r.win ? '战斗胜利！' : '战斗失败！');
      h += '<div class="rb-narrative">' + narrative + '</div>';
      if (r.cityConquered) h += '<div class="rb-line" style="color:var(--gold)">★ 已征服该城市</div>';
      var pl = this._formatRes(r.plunder);
      if (pl) h += '<div class="rb-line">掠夺资源: ' + pl + '</div>';
      if (r.exp > 0) h += '<div class="rb-line">获得经验: ' + G.fmt(r.exp) + '</div>';
      h += '<div class="rb-divider"></div>';
      h += '<div class="rb-side w">[ 我方幸存部队 ]</div>';
      h += this.renderSurvivors('mine', r.survivorAttacker);
      h += '<div class="rb-divider"></div>';
      h += '<div class="rb-side ' + (r.win ? 'l' : 'w') + '">[ 敌方幸存部队 ]</div>';
      h += this.renderSurvivors('enemy', r.survivorDefender);
      h += '</div>';
      return h;
    },

    renderSurvivors: function (side, map) {
      var esc = G.escapeHtml;
      var h = '';
      var has = false;
      if (map) {
        for (var uid in map) {
          if (map[uid] > 0) {
            var u = U(uid);
            if (!u) continue;
            has = true;
            h += '<div class="rb-unit ' + side + '">' + esc(u.name) + ': ' + G.fmt(map[uid]) + '</div>';
          }
        }
      }
      if (!has) h += '<div class="rb-unit rc-dim">无兵力记录</div>';
      return h;
    },

    renderScoutReportBoard: function (r) {
      var esc = G.escapeHtml;
      var d = new Date(r.time);
      var ts = (d.getMonth() + 1) + '-' + String(d.getDate()).padStart(2, '0') + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0') + ':' + String(d.getSeconds()).padStart(2, '0');
      var data = r.data || {};
      var isWin = data.showCityInfo;
      var resultText = {
        overwhelming_defeat: '惨败（敌军势大）',
        close_match_loss: '失败（激战落败）',
        close_match_win: '险胜（激战获胜）',
        overwhelming_victory: '大胜（碾压全歼）'
      }[data.result] || '未知';
      var resultColor = isWin ? 'var(--ok)' : 'var(--danger)';
      var h = '';
      h += '<div class="report-board ' + (isWin ? 'win' : 'lose') + '">';
      h += '<div class="rb-subject">主题: 侦查报告</div>';
      h += '<div class="rb-line">侦查目标: ' + esc(data.targetName || '?') + ' (' + (data.x || 0) + ',' + (data.y || 0) + ')</div>';
      h += '<div class="rb-line">时间: ' + ts + '</div>';
      h += '<div class="rb-divider"></div>';
      h += '<div class="rb-line">侦查结果: <b style="color:' + resultColor + '">' + resultText + '</b></div>';
      h += '<div class="rb-divider"></div>';
      h += '<div class="rb-side ' + (isWin ? 'w' : 'l') + '">[ 我方侦察机 ]</div>';
      h += '<div class="rb-unit mine">侦察机: ' + (data.myScouts || 0) + ' -> ' + ((data.myScouts || 0) - (data.myLost || 0)) + ' ( -' + (data.myLost || 0) + ' )</div>';
      h += '<div class="rb-divider"></div>';
      h += '<div class="rb-side ' + (isWin ? 'l' : 'w') + '">[ 敌方侦察机 ]</div>';
      h += '<div class="rb-unit enemy">侦察机: ' + (data.enemyScouts || 0) + ' -> ' + ((data.enemyScouts || 0) - (data.enemyLost || 0)) + ' ( -' + (data.enemyLost || 0) + ' )</div>';
      h += '<div class="rb-divider"></div>';
      if (data.combatLog && data.combatLog.length) {
        h += '<div class="rb-line" style="color:var(--gold)">★ 侦查战斗过程:</div>';
        for (var i = 0; i < data.combatLog.length; i++) {
          h += '<div class="rb-line rc-dim">' + esc(data.combatLog[i]) + '</div>';
        }
        h += '<div class="rb-divider"></div>';
      }
      if (data.showCityInfo) {
        h += '<div class="rb-side w">[ ★ 城市情报 ]</div>';
        if (data.commander) h += '<div class="rb-line">统帅: ' + esc(data.commander) + '</div>';
        if (data.prestige) h += '<div class="rb-line">声望: ' + G.fmt(data.prestige) + '</div>';
        if (data.cityDesc) h += '<div class="rb-line">简介: ' + esc(data.cityDesc) + '</div>';
        if (data.lastActive) h += '<div class="rb-line">最后活跃: ' + esc(data.lastActive) + '</div>';
        if (data.army) {
          var armyStr = '';
          for (var aid in data.army) {
            if (data.army[aid] > 0) {
              var au = U(aid);
              armyStr += (au ? au.name : aid) + 'x' + data.army[aid] + ' ';
            }
          }
          if (armyStr) h += '<div class="rb-line">守军: ' + esc(armyStr.trim()) + '</div>';
        }
        if (data.forts) {
          var fortStr = '';
          for (var fid in data.forts) {
            if (data.forts[fid] > 0) {
              var fu = D.forts && D.forts[fid];
              fortStr += (fu ? fu.name : fid) + 'x' + data.forts[fid] + ' ';
            }
          }
          if (fortStr) h += '<div class="rb-line">城防: ' + esc(fortStr.trim()) + '</div>';
        }
        if (data.resources) {
          var resStr = '';
          if (data.resources.food) resStr += '粮' + G.fmt(data.resources.food) + ' ';
          if (data.resources.steel) resStr += '钢' + G.fmt(data.resources.steel) + ' ';
          if (data.resources.oil) resStr += '油' + G.fmt(data.resources.oil) + ' ';
          if (data.resources.rare) resStr += '稀' + G.fmt(data.resources.rare) + ' ';
          if (data.resources.gold) resStr += '金' + G.fmt(data.resources.gold) + ' ';
          if (resStr) h += '<div class="rb-line">资源: ' + resStr.trim() + '</div>';
        }
        if (data.buildings) {
          var bStr = '';
          for (var bid in data.buildings) {
            var binfo = D.buildings[bid];
            var blv = data.buildings[bid];
            if (Array.isArray(blv)) {
              var totalLv = 0;
              for (var bs = 0; bs < blv.length; bs++) totalLv += blv[bs];
              if (totalLv > 0) bStr += (binfo ? binfo.name : bid) + 'Lv.' + totalLv + ' ';
            } else if (blv > 0) {
              bStr += (binfo ? binfo.name : bid) + 'Lv.' + blv + ' ';
            }
          }
          if (bStr) h += '<div class="rb-line">建筑: ' + bStr.trim() + '</div>';
        }
        if (data.techs) {
          var tStr = '';
          for (var tid in data.techs) {
            if (data.techs[tid] > 0) {
              var tinfo = D.techs[tid];
              tStr += (tinfo ? tinfo.name : tid) + 'Lv.' + data.techs[tid] + ' ';
            }
          }
          if (tStr) h += '<div class="rb-line">科技: ' + tStr.trim() + '</div>';
        }
        if (data.officers && data.officers.length) {
          h += '<div class="rb-line">军官: ' + data.officers.length + '名</div>';
        }
      } else {
        h += '<div class="rb-line rc-dim">未获得城市情报</div>';
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

    renderReportDetail: function (v) {
      var r = this._viewReport;
      if (!r) { G.go('reports'); return; }
      var h = '';
      if (r.type === 'scout') {
        h += this.renderScoutReportBoard(r);
        h += '<div class="btn-row" style="margin-top:8px">';
        h += '<button class="btn" onclick="Game.go(\'reports\')">返回战报列表</button>';
        h += '<button class="btn warn" onclick="Game.go(\'home\')">返回主菜单</button>';
        h += '</div>';
      } else {
        h += this.renderReportBoard(r, false);
        h += '<div class="zone-head">-- 每回合战斗细节 --</div>';
        h += this.renderCommanderPanel(r.commanders);
        h += '<div class="blog" style="max-height:none">';
        var logs = Array.isArray(r.roundLogs) ? r.roundLogs : [];
        for (var i = 0; i < logs.length; i++) {
          var line = String(logs[i] || '');
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
        h += '</div>';
        h += '<div class="btn-row" style="margin-top:8px">';
        h += '<button class="btn" onclick="Game.go(\'reports\')">返回战报列表</button>';
        h += '<button class="btn warn" onclick="Game.go(\'home\')">返回主菜单</button>';
        h += '</div>';
      }
      v.innerHTML = h;
    }
  };

  G.Battle = Battle;
  Core.views.battle = function (v) { G.go('reports'); };
  Core.views.report = function (v) { G.go('reports'); };
  Core.views.reports = function (v) { Battle.renderReportsList(v); };
  Core.views.reportDetail = function (v) { Battle.renderReportDetail(v); };
})(window.Game);
