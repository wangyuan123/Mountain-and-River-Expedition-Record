/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var D = G.DATA;
  var Core = G.Core;

  function dist(x1, y1, x2, y2) {
    return Math.abs(x1 - x2) + Math.abs(y1 - y2);
  }

  function armyText(army) {
    var arr = [];
    for (var id in army) {
      var u = D.units[id] || (D.forts && D.forts[id]);
      arr.push((u ? u.name : id) + 'x' + army[id]);
    }
    return arr.join(' ') || '无';
  }

  function fortText(forts) {
    if (!forts) return '';
    var arr = [];
    for (var id in forts) {
      if (forts[id] > 0) {
        var f = D.forts && D.forts[id];
        arr.push((f ? f.name : id) + 'x' + forts[id]);
      }
    }
    return arr.length ? arr.join(' ') : '';
  }

  function getScouted(x, y) {
    var s = Core.state;
    if (!s.reports) return null;
    for (var i = 0; i < s.reports.length; i++) {
      var rp = s.reports[i];
      if (rp.type === 'scout' && rp.data && rp.data.x === x && rp.data.y === y && rp.data.showCityInfo) return rp;
    }
    return null;
  }

  var World = {
    // The canvas keeps stable IDs. Legacy actions receive a freshly resolved record.
    mapAction: function (target, action) {
      var keys = { wild: 'wildTiles', player: 'playerCities', npc: 'npcCities', simulated_npc: 'simulatedNpcCities', bandit: 'bandits' };
      var key = keys[target.kind];
      if (!key) return;
      var list = Core.state.world[key] || (Core.state.world[key] = []);
      var idx = list.findIndex(function (t) { return String(t.id) === String(target.id); });
      if (idx < 0) { idx = list.length; list.push(target); } else list[idx] = target;
      if (target.selfCity) { G.go('home'); return; }
      if (action === 'declare') { this.declareWar(target.kind, idx); return; }
      if (target.kind === 'wild') {
        if (action === 'scout') this.scoutWild(idx);
        else if (action === 'gather') this.gatherWild(idx);
        else if (action === 'abandon') this.abandonWild(idx);
        else this.attackWild(idx, action);
      } else this.attack(target.kind, idx, action);
    },

    move: function (dx, dy) {
      var direction;
      if (dx === 0 && dy === -1) direction = 'up';
      else if (dx === 0 && dy === 1) direction = 'down';
      else if (dx === -1 && dy === 0) direction = 'left';
      else if (dx === 1 && dy === 0) direction = 'right';
      else return;
      // 清空 _mapPos, 让视角跟随后端真实位置 world.pos 移动
      if (Core.state && Core.state.world) {
        Core.state.world._mapPos = null;
        Core.state.world._searchTarget = null;
      }
      G.API.worldMove(direction).then(function () {
        // 后端 move 接口会同步返回完整 state (含 world.pos), applyState 已生效, 直接重渲染
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '移动失败');
      });
    },

    setTab: function (tab) {
      Core.state.world._activeTab = tab;
      Core.render();
    },

    setSort: function (mode) {
      Core.state.world._sortMode = mode;
      Core.render();
    },

    jumpTo: function (x, y) {
      var s = Core.state;
      s.world._searchTarget = null;
      s.world._mapPos = {
        x: G.clamp(x, 0, D.world.size - 1),
        y: G.clamp(y, 0, D.world.size - 1)
      };
      s.world._scan = { r: D.world.viewRadius, at: Date.now() };
      Core.render();
    },

    jumpToMyCity: function () {
      var s = Core.state;
      var cp = s.world.cityPos || s.world.pos;
      s.world._searchCoord = cp.x + ',' + cp.y;
      this.jumpTo(cp.x, cp.y);
    },

    locateClosest: function (kind, loaded) {
      if (!loaded) {
        var pos = Core.state.world.pos;
        G.WorldView.load(pos.x, pos.y, 0).then(function (applied) {
          if (applied) World.locateClosest(kind, true);
        }).catch(function (err) { G.toast(err.message || '目标查询失败'); });
        return;
      }
      var s = Core.state;
      var px = s.world.pos.x, py = s.world.pos.y;
      var target = null;
      if (kind === 'wild') {
        target = (s.world.wildTiles || [])
          .filter(function (t) { return !t.occupied; })
          .map(function (t) { return { t: t, d: dist(px, py, t.x, t.y) }; })
          .sort(function (a, b) { return a.d - b.d; })[0];
        if (target) target = target.t;
      } else if (kind === 'npc') {
        target = (s.world.npcCities || [])
          .filter(function (n) { return !n.defeated; })
          .map(function (n) { return { n: n, d: dist(px, py, n.x, n.y) }; })
          .sort(function (a, b) { return a.d - b.d; })[0];
        if (target) target = target.n;
      } else if (kind === 'player') {
        var now = Date.now();
        target = (s.world.playerCities || [])
          .filter(function (p) { return !p.coolAt || p.coolAt < now; })
          .map(function (p) { return { p: p, d: dist(px, py, p.x, p.y) }; })
          .sort(function (a, b) { return a.d - b.d; })[0];
        if (target) target = target.p;
      } else if (kind === 'owned') {
        target = (s.world.wildTiles || [])
          .filter(function (t) { return t.occupied; })
          .map(function (t) { return { t: t, d: dist(px, py, t.x, t.y) }; })
          .sort(function (a, b) { return a.d - b.d; })[0];
        if (target) target = target.t;
      }
      if (!target) { G.toast('没有可跳转的目标'); return; }
      this.jumpTo(target.x, target.y);
      G.toast('已跳转至 (' + target.x + ',' + target.y + ') 距 ' + dist(px, py, target.x, target.y) + ' 格');
    },

    scan: function () {
      G.API.worldScan().then(function () {
        G.toast('扫描完成');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '扫描失败');
      });
    },

    refreshMapData: function () {
      G.WorldView.invalidate();
      G.API.getGameState(true).then(function (state) {
        // 强制绕过本地缓存,用服务器最新状态替换当前地图数据。
        G.API.applyState(state);
        G.toast('地图数据已刷新');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '地图刷新失败');
      });
    },

    /**
     * 设置视野半径: v 接受 "3"|"5"|"8"|"10"|"0"
     * 0 = 全图 (跳过距离过滤); 持久化到 localStorage.wg_viewRadius
     */
    setViewRadius: function (v) {
      var n = parseInt(v, 10);
      if (isNaN(n) || n < 0) n = 0;
      var s = Core.state;
      if (!s.world._scan) s.world._scan = { r: n, at: Date.now() };
      else s.world._scan.r = n;
      try { localStorage.setItem('wg_viewRadius', String(n)); } catch (e) {}
      Core.render();
    },

    search: function () {
      var el = document.getElementById('searchCoord');
      if (!el) return;
      var v = el.value.trim();
      var m = v.match(/^(\d+)\s*[,，\s]\s*(\d+)$/);
      if (!m) { G.toast('请输入 x,y 格式,如 100,50'); return; }
      var x = G.clamp(parseInt(m[1], 10), 0, D.world.size - 1);
      var y = G.clamp(parseInt(m[2], 10), 0, D.world.size - 1);
      var s = Core.state;
      s.world._searchCoord = x + ',' + y;
      s.world._searchTarget = null;
      s.world._mapPos = { x: x, y: y };
      s.world._scan = { r: D.world.viewRadius, at: Date.now() };
      // 搜索不受视野过滤影响，只加载坐标处的基础目标信息；详细情报仍需侦查
      if (G.API && G.API.getCoordinateTarget) {
        G.API.getCoordinateTarget(x, y).then(function (target) {
          s.world._searchTarget = target && target.kind ? target : null;
          if (target && target.kind) {
            s.world._activeTab = target.kind === 'wild' && target.occupied ? 'owned' : 'all';
            var listKey = target.kind === 'player' ? 'playerCities' :
              (target.kind === 'npc' ? 'npcCities' : (target.kind === 'simulated_npc' ? 'simulatedNpcCities' : 'wildTiles'));
            if ((target.kind === 'player' || target.kind === 'simulated_npc') && typeof target.id === 'string') {
              G.toast('该坐标目标数据异常，无法宣战');
              Core.render();
              return;
            }
            var list = s.world[listKey] || [];
            var exists = false;
            for (var i = 0; i < list.length; i++) {
              if (String(list[i].id) === String(target.id)) {
                // 坐标搜索结果是最新的关系状态，覆盖列表中的旧缓存，避免残留“宣战中”。
                var oldUiState = {};
                for (var key in list[i]) {
                  if (key.indexOf('_') === 0) oldUiState[key] = list[i][key];
                }
                list[i] = target;
                for (var uiKey in oldUiState) list[i][uiKey] = oldUiState[uiKey];
                exists = true;
                break;
              }
            }
            if (!exists) {
              target._searchOnly = true;
              s.world[listKey] = list;
              s.world[listKey].push(target);
            }
          }
          G.toast(target && target.kind ? '已定位到目标 (' + x + ',' + y + ')' : '该坐标没有可见目标');
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '坐标查询失败');
          Core.render();
        });
      } else {
        G.toast('已定位到 (' + x + ',' + y + ')');
        Core.render();
      }
    },

    attack: function (kind, idx, action) {
      var s = Core.state;
      var target;
      if (kind === 'bandit') target = s.world.bandits[idx];
      else if (kind === 'npc') target = s.world.npcCities[idx];
      else if (kind === 'simulated_npc') target = s.world.simulatedNpcCities[idx];
      else if (kind === 'player') target = s.world.playerCities[idx];
      if (!target) return;
      if (target.defeated) { G.toast('目标已被击败,等待刷新'); return; }
      if (kind === 'player' && target.coolAt > Date.now()) {
        var mins = Math.ceil((target.coolAt - Date.now()) / 60000);
        G.toast('该玩家处于保护期,剩 ' + mins + ' 分钟');
        return;
      }
      if (kind === 'player' && action !== 'scout') {
        if (!target.warAt || target.warAt > Date.now()) {
          G.toast('需先宣战并等待倒计时结束才能攻击玩家');
          return;
        }
        if (target.warEndAt && target.warEndAt <= Date.now()) {
          G.toast('战争已结束,需重新宣战');
          return;
        }
      }
      if (!action) action = 'conquer';
      s.world._dispatchTarget = { kind: kind, idx: idx, action: action, target: target };
      G.go('dispatch');
    },

    scoutWild: function (idx) {
      var s = Core.state;
      var t = s.world.wildTiles[idx];
      if (!t) return;
      G.API.wildScout(t.id).then(function () {
        if (G.WorldMap) G.WorldMap.invalidate();
        G.toast('侦察完成');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '侦察失败');
      });
    },

    attackWild: function (idx, action) {
      var s = Core.state;
      var t = s.world.wildTiles[idx];
      if (!t) return;
      if (t.occupied) { G.toast('已占领'); return; }
      if (!action) action = 'conquer';
      s.world._dispatchTarget = { kind: 'wild', idx: idx, action: action, target: t };
      G.go('dispatch');
    },

    abandonWild: function (idx) {
      var s = Core.state;
      var t = s.world.wildTiles[idx];
      if (!t || !t.occupied) return;
      if (!confirm('放弃该资源野地?')) return;
      G.API.wildAbandon(t.id).then(function () {
        if (G.WorldMap) G.WorldMap.invalidate();
        G.toast('已放弃');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '操作失败');
      });
    },

    gatherWild: function (idx) {
      var s = Core.state;
      var t = s.world.wildTiles[idx];
      if (!t || !t.occupied) return;
      var wt = G.DATA.wildTypes[t.type];
      if (!wt || !wt.res) { G.toast('该野地无资源可采集'); return; }
      var remaining = (t.totalRes || 0) - (t.mined || 0);
      if (remaining <= 0) { G.toast('该野地资源已耗尽'); return; }
      s.world._dispatchTarget = { kind: 'wild_gather', idx: idx, action: 'gather', target: t };
      G.go('dispatch');
    },

    toggleIncomingExpand: function (idx) {
      var im = Core.state.world.incoming[idx];
      if (im) { im.expanded = !im.expanded; G.Core.render(); }
    },

    startIncomingBattle: function (idx) {
      G.toast('战斗由后端自动处理');
    },

    declareWar: function (kind, idx) {
      var s = Core.state;
      var target;
      if (kind === 'player') target = s.world.playerCities[idx];
      if (!target) return;

      var prepareSec = 6 * 3600;
      var warSec = 24 * 3600;
      var now = new Date();
      var attackTime = new Date(Date.now() + prepareSec * 1000);
      var endTime = new Date(attackTime.getTime() + warSec * 1000);
      var mePos = s.world.pos || { x: 0, y: 0 };
      var dist = Math.max(Math.abs((target.x || 0) - mePos.x), Math.abs((target.y || 0) - mePos.y));

      var fmtTime = function (d) {
        return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
      };
      var fmtLeft = function (sec) {
        var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60);
        return h > 0 ? h + '小时' + (m > 0 ? m + '分' : '') : m + '分';
      };

      var mask = document.createElement('div');
      mask.className = 'modal-mask';
      var esc = G.escapeHtml || function (s) { return s; };
      mask.innerHTML =
        '<div class="modal-card dw-confirm">' +
          '<div class="modal-title dw-title"><span class="dw-icon">⚔</span>对 ' + esc(target.name) + ' 宣战</div>' +
          '<div class="modal-body dw-body">' +
            // 敌城信息
            '<div class="dw-target">' +
              '<div class="dw-target-row">' +
                '<span class="dw-emoji">🏰</span>' +
                '<span class="dw-name">' + esc(target.name) + '</span>' +
              '</div>' +
              '<div class="dw-target-meta">' +
                '<span class="dw-meta-item">声望：' + G.fmt(target.prestige || 0) + '</span>' +
                '<span class="dw-meta-sep">·</span>' +
                '<span class="dw-meta-item">📍 (' + (target.x || 0) + ',' + (target.y || 0) + ')</span>' +
                '<span class="dw-meta-sep">·</span>' +
                '<span class="dw-meta-item">📏 ' + dist + '格</span>' +
              '</div>' +
            '</div>' +

            // 时间线
            '<div class="dw-section dw-timeline">' +
              '<div class="dw-section-label">⏱ 战争时间线</div>' +
              '<div class="dw-tl-row">' +
                '<div class="dw-tl-node dw-tl-now">' +
                  '<div class="dw-tl-dot dw-tl-dot-active"></div>' +
                  '<div class="dw-tl-time">' + fmtTime(now) + '</div>' +
                  '<div class="dw-tl-label">现在</div>' +
                '</div>' +
                '<div class="dw-tl-line"></div>' +
                '<div class="dw-tl-node">' +
                  '<div class="dw-tl-dot"></div>' +
                  '<div class="dw-tl-time">' + fmtTime(attackTime) + '</div>' +
                  '<div class="dw-tl-label">备战结束<br><b>可交战</b></div>' +
                '</div>' +
                '<div class="dw-tl-line"></div>' +
                '<div class="dw-tl-node">' +
                  '<div class="dw-tl-dot dw-tl-dot-end"></div>' +
                  '<div class="dw-tl-time">' + fmtTime(endTime) + '</div>' +
                  '<div class="dw-tl-label">战争结束<br><b>恢复和平</b></div>' +
                '</div>' +
              '</div>' +
              '<div class="dw-dur">' +
                '<span class="dw-dur-item">⏳ 备战 <b>' + fmtLeft(prepareSec) + '</b></span>' +
                '<span class="dw-dur-sep">→</span>' +
                '<span class="dw-dur-item">⚔ 交战 <b>' + fmtLeft(warSec) + '</b></span>' +
              '</div>' +
            '</div>' +

            // 风险提示
            '<div class="dw-warn">' +
              '<div class="dw-warn-title">⚠ 战争期间须知</div>' +
              '<ul class="dw-warn-list">' +
                '<li>备战期内双方都无法进攻,只能侦察</li>' +
                '<li>对方同时也可对你发起进攻,务必留守部队</li>' +
                '<li>失败会损失兵力、战败可能被征服</li>' +
                '<li>24 小时交战期结束自动恢复和平</li>' +
              '</ul>' +
            '</div>' +
          '</div>' +
          '<div class="modal-foot dw-foot">' +
            '<button class="btn dw-btn-cancel" id="dwCancel">再想想</button>' +
            '<button class="btn dw-btn-ok" id="dwOk"><span>⚔</span>确认宣战</button>' +
          '</div>' +
        '</div>';
      document.body.appendChild(mask);

      var close = function () { if (mask.parentNode) mask.parentNode.removeChild(mask); };
      mask.querySelector('#dwCancel').onclick = close;
      mask.addEventListener('click', function (e) { if (e.target === mask) close(); });

      mask.querySelector('#dwOk').onclick = function () {
        close();
        G.API.declareWar(target.id).then(function () {
          if (G.WorldMap) G.WorldMap.invalidate();
          G.toast('宣战成功! ' + fmtLeft(prepareSec) + '后开打,持续 ' + fmtLeft(warSec));
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '宣战失败');
        });
      };
    },

    startWarTimer: function () {
      if (this._warTimer) return;
      var self = this;
      this._warTimer = setInterval(function () {
        var s = Core.state;
        if (!s.world || !s.world.playerCities) return;
        var hasActive = false;
        for (var i = 0; i < s.world.playerCities.length; i++) {
          var p = s.world.playerCities[i];
          if (!p.warAt) continue;
          var now = Date.now();
          if (p.warEndAt && now >= p.warEndAt) continue;
          if (now < p.warEndAt) {
            hasActive = true;
            var el = document.getElementById('war-timer-' + i);
            if (el) {
              var remain = now < p.warAt ? p.warAt - now : p.warEndAt - now;
              var hh = Math.floor(remain / 3600000);
              var mm = Math.floor((remain % 3600000) / 60000);
              var ss = Math.floor((remain % 60000) / 1000);
              var str = hh > 0 ? hh + ':' + String(mm).padStart(2,'0') + ':' + String(ss).padStart(2,'0') : mm + ':' + String(ss).padStart(2,'0');
              var label = now < p.warAt ? '宣战倒计时 ' : '交战剩余 ';
              el.textContent = label + str;
            }
          }
        }
        if (!hasActive) {
          clearInterval(self._warTimer);
          self._warTimer = null;
        }
      }, 1000);
    },

    calcDispatchLoad: function (army) {
      var infLoad = 1 + 0.20 * (Core.state.tech.inf_load || 0);
      var total = 0;
      for (var id in army) {
        var u = D.units[id];
        if (u && u.load) total += army[id] * u.load * infLoad;
      }
      return Math.floor(total);
    },

    onDispatchSliderChange: function (uid, val) {
      var num = parseInt(val, 10);
      if (isNaN(num)) num = 0;
      var inputEl = document.getElementById('dqty_' + uid);
      if (inputEl) inputEl.value = num;

      var sliderEl = document.getElementById('dslider_' + uid);
      if (sliderEl) {
        var max = parseInt(sliderEl.max, 10) || 0;
        var pct = max > 0 ? Math.min(100, Math.max(0, (num / max) * 100)) : 0;
        sliderEl.style.setProperty('--p', pct.toFixed(1) + '%');
      }
      this.updateEstLoad();
    },

    onDispatchInputChange: function (uid, val) {
      var sliderEl = document.getElementById('dslider_' + uid);
      if (!sliderEl) return;
      var max = parseInt(sliderEl.max, 10) || 0;
      if (val === '') {
        sliderEl.value = 0;
        sliderEl.style.setProperty('--p', '0%');
        this.updateEstLoad();
        return;
      }
      var num = parseInt(val, 10);
      if (isNaN(num)) num = 0;
      var clamped = Math.min(Math.max(0, num), max);
      sliderEl.value = clamped;
      var pct = max > 0 ? Math.min(100, Math.max(0, (clamped / max) * 100)) : 0;
      sliderEl.style.setProperty('--p', pct.toFixed(1) + '%');
      this.updateEstLoad();
    },

    updateEstLoad: function () {
      var el = document.getElementById('estLoad');
      if (!el) return;
      var currentArmy = {};
      for (var k in D.units) {
        var input = document.getElementById('dqty_' + k);
        if (input) {
          var n = parseInt(input.value, 10) || 0;
          if (n > 0) currentArmy[k] = n;
        }
      }
      el.textContent = G.fmt(this.calcDispatchLoad(currentArmy));
    },

    cancelDispatch: function () {
      delete Core.state.world._dispatchTarget;
      G.go('world');
    },

    launchDispatch: function () {
      var s = Core.state;
      var dt = s.world._dispatchTarget;
      if (!dt) { G.go('world'); return; }
      var target;
      if (dt.kind === 'bandit') target = dt.target || s.world.bandits[dt.idx];
      else if (dt.kind === 'npc') target = dt.target || s.world.npcCities[dt.idx];
      else if (dt.kind === 'simulated_npc') target = dt.target || s.world.simulatedNpcCities[dt.idx];
      else if (dt.kind === 'player') target = dt.target || s.world.playerCities[dt.idx];
      else if (dt.kind === 'wild' || dt.kind === 'wild_gather') {
        var wt = dt.target || s.world.wildTiles[dt.idx];
        if (wt) {
          var wtd = G.DATA.wildTypes[wt.type];
          target = { id: wt.id, name: wtd.name + ' Lv.' + wt.level, x: wt.x, y: wt.y };
        }
      }
      if (!target) { G.toast('目标已失效'); G.go('world'); return; }

      var customArmy = {};
      var hasUnits = false;
      for (var uid in D.units) {
        var el = document.getElementById('dqty_' + uid);
        if (!el) continue;
        var n = parseInt(el.value, 10) || 0;
        if (n > 0) {
          customArmy[uid] = n;
          hasUnits = true;
        }
      }
      if (!hasUnits) { G.toast('请至少选择一种兵种出征'); return; }

      var commEl = document.querySelector('input[name="dpOfficer"]:checked');
      var commanderId = null;
      if (commEl && commEl.value && commEl.value !== 'none') {
        commanderId = parseInt(commEl.value, 10);
        if (isNaN(commanderId)) commanderId = null;
      }

      var carryRes = {};
      var resKeys = ['food', 'steel', 'oil', 'rare'];
      for (var i = 0; i < resKeys.length; i++) {
        var rk = resKeys[i];
        carryRes[rk] = 0;
        var rel = document.getElementById('dcarry_' + rk);
        if (rel) carryRes[rk] = parseInt(rel.value, 10) || 0;
      }

      var dispatchRequest = {
        targetKind: dt.kind,
        targetId: target.id,
        action: dt.action || 'conquer',
        army: customArmy,
        commanderId: commanderId,
        carryRes: carryRes
      };

      delete s.world._dispatchTarget;
      G.API.worldDispatch(dispatchRequest).then(function () {
        G.toast('部队出征');
        // 触发每日任务进度
        var act = dispatchRequest.action;
        // 侦查任务在侦查部队抵达并生成报告后，由 WebSocket 事件计数，不能在出发时完成。
        if (act === 'gather' && G.Task && G.Task.Quests) G.Task.Quests.onEvent('gather');
        else if ((act === 'conquer' || act === 'plunder' || act === 'attack' || act === 'occupy') && G.Task && G.Task.Quests) G.Task.Quests.onEvent('attack');
        G.go('world');
      }).catch(function (err) {
        G.toast(err.message || '出征失败');
        G.go('world');
      });
    },

    cancelMarch: function (marchId) {
      G.API.cancelMarch(marchId).then(function () {
        G.toast('行军已取消，部队和携带资源已返还');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '取消行军失败');
      });
    },

    fmtMarchTime: function (arriveAt) {
      var remain = Math.max(0, Math.ceil((arriveAt - Date.now()) / 1000));
      if (remain <= 0) return '到达!';
      var m = Math.floor(remain / 60);
      var s = remain % 60;
      return m > 0 ? m + '分' + s + '秒' : s + '秒';
    },

    renderDispatch: function (v) {
      var s = Core.state;
      var dt = s.world._dispatchTarget;
      if (!dt) { G.go('world'); return; }
      var target;
      if (dt.kind === 'bandit') target = dt.target || s.world.bandits[dt.idx];
      else if (dt.kind === 'npc') target = dt.target || s.world.npcCities[dt.idx];
      else if (dt.kind === 'simulated_npc') target = dt.target || s.world.simulatedNpcCities[dt.idx];
      else if (dt.kind === 'player') target = dt.target || s.world.playerCities[dt.idx];
      else if (dt.kind === 'wild' || dt.kind === 'wild_gather') {
        var wtd = dt.target || s.world.wildTiles[dt.idx];
        if (wtd) {
          var wtdInfo = G.DATA.wildTypes[wtd.type];
          target = { id: wtd.id, name: wtdInfo.name, level: wtd.level, x: wtd.x, y: wtd.y, army: wtd.garrison, _wildRef: wtd, _wildType: wtdInfo };
        }
      }
      if (!target) { G.go('world'); return; }

      var isWildConquer = dt.kind === 'wild';
      var isGather = dt.kind === 'wild_gather';
      var isScout = dt.action === 'scout';
      var actionNames = { conquer: '征服', plunder: '掠夺', scout: '侦查', gather: '采集' };
      var actionName = actionNames[dt.action] || '征服';
      var actionDesc = isGather
        ? '派遣部队前往已占领野地采集资源，采集量取决于部队负重，采集完成后自动返城。'
        : isWildConquer
          ? (dt.action === 'plunder'
            ? '击败野地守军后掠夺资源，根据幸存部队负重夺取野地资源，不占领该野地。'
            : (target._wildType.res
              ? '征服野地守军后占领该资源点，可派遣部队采集资源。'
              : '征服野地守军后占领该地块，扩张领土。'))
          : {
              conquer: '彻底攻占敌方城市，胜利后夺取全部资源（含黄金）',
              plunder: '资源争夺战，胜利后夺取仓库保护范围外的非黄金资源',
              scout: '派遣侦查机刺探敌方详情'
            }[dt.action] || '';
      var h = '';
      h += '<div class="title">- ' + (isGather ? '采集派遣' : '出征准备') + ' -</div>';
      h += '<div class="panel">';
      h += '<div class="bfield">行动: <b style="color:var(--accent)">' + actionName + '</b> | 目标: <b>' + target.name + '</b>';
      if (dt.kind !== 'player' && target.level) h += ' Lv.' + target.level;
      h += ' (' + target.x + ',' + target.y + ')</div>';
      if (isWildConquer || isGather) {
        var wtRef = target._wildRef;
        var wtInfo = target._wildType;
        var wildRemain = (wtRef.totalRes || 0) - (wtRef.mined || 0);
        var wtIcon2 = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(wtInfo.icon)
          ? '<img class="wt-icon" src="' + wtInfo.icon + '" alt="' + wtInfo.name + '"/>'
          : wtInfo.icon;
        var wtResName = wtInfo.res ? D.resources[wtInfo.res].name : '无资源';
        h += '<div class="d">类型: ' + wtIcon2 + ' ' + wtInfo.name + ' | 资源: ' + wtResName + '</div>';
        h += '<div class="cost">剩余资源: ' + G.fmt(wildRemain) + ' / ' + G.fmt(wtRef.totalRes || 0) + '</div>';
        if (isWildConquer) {
          if (wtRef.scouted) {
            h += '<div class="d">守军: ' + armyText(wtRef.garrison) + '</div>';
          } else {
            h += '<div class="d" style="color:#888">守军未知 - 需先侦察</div>';
          }
        }
        if (wtRef.occupied) {
          h += '<div class="d" style="color:var(--ok)">已占领</div>';
        } else {
          h += '<div class="d" style="color:#888">未占领</div>';
        }
      } else {
      var scouted = getScouted(target.x, target.y);
      if (scouted) {
        h += '<div class="d">守军: ' + armyText(scouted.data.army) + '</div>';
        var ft2 = fortText(scouted.data.forts);
        if (ft2) h += '<div class="d">城防: ' + ft2 + '</div>';
        if (scouted.data.resources) {
          h += '<div class="cost">资源: 粮' + G.fmt(scouted.data.resources.food || 0) + ' 钢' + G.fmt(scouted.data.resources.steel || 0) + ' 油' + G.fmt(scouted.data.resources.oil || 0) + ' 稀' + G.fmt(scouted.data.resources.rare || 0) + ' 金' + G.fmt(scouted.data.resources.gold || 0) + '</div>';
        }
        h += '<div class="d" style="color:var(--gold)">★ 已侦查 (情报来自侦查报告)</div>';
      } else {
        h += '<div class="d" style="color:#888">敌情未知 — 需先侦查才能获知守军、城防和资源详情</div>';
      }
      }
      var dispDist = dist(s.world.pos.x, s.world.pos.y, target.x, target.y);
      h += '<div class="desc">出征距离: <b>' + dispDist + '</b> 格 (当前坐标→目标坐标)</div>';
      h += '<div class="d" style="color:var(--gold);margin-top:4px">' + actionDesc + '</div>';
      h += '</div>';

      h += '<div class="zone-head">-- 兵种配置 (选择出征数量) --</div>';
      h += '<div class="panel">';
      var defaultLoad = this.calcDispatchLoad(s.army);
      var hasAny = false;
      // 征服/掠夺时默认编入 1 架侦察机，作为前置侦察与吸收首轮火力的炮灰；
      // 纯侦查行动仍只显示侦察机并默认 1 架，采集等行动保持全量配置。
      var sacrificialScout = dt.action === 'conquer' || dt.action === 'plunder';
      for (var uid in D.units) {
        var have = s.army[uid] || 0;
        if (have <= 0) continue;
        if (isScout && uid !== 'scout') continue;
        hasAny = true;
        var u = D.units[uid];
        var isLogi = u.logistic ? ' (辎重' + u.load + '/辆)' : '';
        var initialVal = (isScout || (sacrificialScout && uid === 'scout')) ? Math.min(have, 1) : have;
        var pct = have > 0 ? ((initialVal / have) * 100).toFixed(1) : 0;
        var sliderId = 'dslider_' + uid;
        h += '<div class="dispatch-unit-row">';
        h += '<div class="dispatch-unit-info">';
        h += '<span class="dispatch-unit-name">' + u.name + isLogi + '</span>';
        h += '<span class="dispatch-unit-have">城内' + G.fmt(have) + '</span>';
        h += '</div>';
        h += '<div class="dispatch-unit-control">';
        h += '<input class="qty recruit-qty" id="dqty_' + uid + '" type="number" min="0" max="' + have + '" value="' + initialVal + '" oninput="Game.World.onDispatchInputChange(\'' + uid + '\',this.value)" onchange="var v=parseInt(this.value,10);if(isNaN(v)||v<0){this.value=0;}else if(v>' + have + '){this.value=' + have + ';}Game.World.onDispatchInputChange(\'' + uid + '\',this.value);" />';
        h += '<div class="recruit-slider-wrap">';
        h += '<input type="range" class="recruit-slider" id="' + sliderId + '" min="0" max="' + have + '" value="' + initialVal + '" style="--p:' + pct + '%" oninput="Game.World.onDispatchSliderChange(\'' + uid + '\',this.value)" />';
        h += '</div>';
        h += '</div>';
        h += '</div>';
      }
      if (!hasAny) h += '<div class="desc">' + (isScout ? '城内无侦察机可用,请先制造侦察机。' : '城内无可用部队,请先征兵。') + '</div>';
      if (!isScout) h += '<div class="desc" style="margin-top:4px">当前编队预估辎重: <b id="estLoad">' + G.fmt(defaultLoad) + '</b></div>';
      h += '</div>';

      // 任何行军(侦查/征服/掠夺/采集)都可以带指挥官
      //  侦查: 减少遭遇战损失, 获得军官经验
      //  征服/掠夺: 攻击/技能加成
      //  采集: 后勤属性提升采集速度 (1 + logistics/100, 上限 ×3.0)
      if (true) {
        h += '<div class="zone-head">-- 指挥官选择 --</div>';
        h += '<div class="panel">';
        var officers = s.officers;
        // 默认选择军事属性最高的指挥官；属性相同则选择列表中的第一位。
        var bestOfficerIndex = -1;
        var bestMilitary = -Infinity;
        for (var bi = 0; bi < officers.length; bi++) {
          if (officers[bi].role === 'mayor') continue;
          var military = Number(officers[bi].military) || 0;
          if (bestOfficerIndex < 0 || military > bestMilitary) {
            bestOfficerIndex = bi;
            bestMilitary = military;
          }
        }
        var foundC = bestOfficerIndex >= 0;
        if (!officers.length) {
          h += '<div class="desc">无军官可用</div>';
        }
        for (var oi = 0; oi < officers.length; oi++) {
          var o = officers[oi];
          if (o.role === 'mayor') continue;
          var checked = oi === bestOfficerIndex ? ' checked' : '';
          var starStr = '';
          for (var si = 0; si < o.star; si++) starStr += '★';
          // 采集任务时显示后勤加成提示
          var extraHint = '';
          if (isGather && o.logistics != null) {
            var sp = (1 + o.logistics / 100);
            if (sp > 3) sp = 3;
            extraHint = ' <span style="color:#7fc4ff">(采集 ×' + sp.toFixed(2) + ')</span>';
          }
          h += '<div class="btn-row" style="margin-bottom:4px">';
          h += '<label style="font-size:14px;cursor:pointer">';
          h += '<input type="radio" name="dpOfficer" value="' + o.id + '"' + checked + ' /> ';
          h += o.name + ' <span style="color:' + (D.starColor[o.star] || '#bbb') + '">' + starStr + '</span>';
          h += ' Lv.' + o.level + ' 将' + o.military + ' 军' + o.logistics + ' 智' + o.knowledge;
          if (o.role === 'commander') h += ' [司令]';
          h += extraHint;
          h += '</label>';
          h += '</div>';
        }
        h += '<div class="btn-row" style="margin-bottom:4px">';
        h += '<label style="font-size:14px;cursor:pointer;color:#888">';
        h += '<input type="radio" name="dpOfficer" value="none"' + (!foundC ? ' checked' : '') + ' /> ';
        h += '不派遣将领 (无加成)';
        h += '</label>';
        h += '</div>';
        if (!foundC) h += '<div class="desc">(当前未任命司令,建议招募或选择将领出征以获得战力与经验加成)</div>';
        h += '</div>';

        h += '<div class="zone-head">-- 携带资源 (货辎队负重内) --</div>';
        h += '<div class="panel">';
        h += '<div class="desc">携带资源随军出征,战胜则返还并计入掠夺,战败则丢失。</div>';
        var resOrder = ['food', 'steel', 'oil', 'rare'];
        for (var ri = 0; ri < resOrder.length; ri++) {
          var rk = resOrder[ri];
          var rinfo = D.resources[rk] || {};
          var haveR = s.resources[rk] || 0;
          var iconHtml = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(rinfo.icon)
            ? '<img class="res-icon-img" src="' + rinfo.icon + '" alt="' + (rinfo.name || '') + '" style="margin-right:4px;vertical-align:middle;display:inline-block;" />'
            : (rinfo.icon ? '<span style="margin-right:4px;">' + rinfo.icon + '</span>' : '');
          h += '<div class="btn-row" style="margin-bottom:4px;display:flex;align-items:center;justify-content:space-between;">';
          h += '<span style="display:inline-flex;align-items:center;min-width:70px;font-size:14px">' + iconHtml + (rinfo.name || rk) + '</span>';
          h += '<span style="font-size:12px;color:#888;min-width:60px">库存 ' + G.fmt(haveR) + '</span>';
          h += '<input class="qty" id="dcarry_' + rk + '" type="number" min="0" max="' + haveR + '" value="0" style="width:80px" />';
          h += '</div>';
        }
        h += '</div>';
      }

      h += '<div class="btn-row" style="margin-top:8px">';
      h += '<button class="btn ok" onclick="Game.World.launchDispatch()" style="font-size:16px;padding:8px 24px">' + (isScout ? '派出侦查机' : (isGather ? '出发采集!' : '出发!')) + '</button>';
      h += '<button class="btn warn" onclick="Game.World.cancelDispatch()">返回</button>';
      h += '</div>';
      v.innerHTML = h;
    },

    renderView: function (v) {
      if (G.WorldMap && G.WorldMap.isMap()) { G.WorldMap.render(v); return; }
      var s = Core.state;
      var W = D.world;
      var cityPos = s.world.cityPos || {};
      // 视角位置：优先本次会话的浏览位置(_mapPos)；否则用后端持久化的位置(world.pos)；
      // 最后兜底用城市坐标(cityPos)。这保证方向键移动后能立即看到效果。
      var mapPos = s.world._mapPos || s.world.pos ||
          ((cityPos.x != null && cityPos.y != null) ? cityPos : { x: 0, y: 0 });
      var px = mapPos.x, py = mapPos.y;
      // 视野半径: 优先用 _scan.r (用户可选), 0 = 全图; 兜底用 W.viewRadius
      var storedRadius = (function () {
        try { return parseInt(localStorage.getItem('wg_viewRadius') || '', 10); } catch (e) { return NaN; }
      })();
      if (s.world._scan && typeof s.world._scan.r === 'number') {
        // _scan.r 优先 (用户已交互过), 同步到 localStorage 以便刷新后保持
        if (!isNaN(storedRadius) && storedRadius !== s.world._scan.r) {
          try { localStorage.setItem('wg_viewRadius', String(s.world._scan.r)); } catch (e) {}
        }
      } else {
        // 首次进入: 用 localStorage 备份或默认
        var initR = !isNaN(storedRadius) ? storedRadius : W.viewRadius;
        s.world._scan = { r: initR, at: Date.now() };
      }
      var scanR = s.world._scan.r;
      if (!G.WorldView.ensure(px, py, scanR)) {
        v.innerHTML = '<div class="card">正在加载地图区域… <button class="btn" onclick="Game.World.refreshMapData()">刷新</button></div>';
        return;
      }
      var activeTab = s.world._activeTab || 'all';
      var sortMode = s.world._sortMode || 'distance';

      function esc(str) {
        return String(str == null ? '' : str).replace(/[&<>"']/g, function (c) {
          return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
      }

      var wildAll = (s.world.wildTiles || []).map(function (t, i) {
        return { kind: 'wild', t: t, i: i, d: dist(px, py, t.x, t.y) };
      });
      var npcAll = (s.world.npcCities || []).map(function (n, i) {
        return { kind: 'npc', n: n, i: i, d: dist(px, py, n.x, n.y) };
      });
      var simulatedNpcAll = (s.world.simulatedNpcCities || []).map(function (n, i) {
        return { kind: 'simulated_npc', n: n, i: i, d: dist(px, py, n.x, n.y) };
      });
      var playerAll = (s.world.playerCities || []).map(function (p, i) {
        return { kind: 'player', p: p, i: i, d: dist(px, py, p.x, p.y) };
      });
      // 自己的主城也作为地图目标显示，便于定位后确认所在城市。
      if (cityPos.x != null && cityPos.y != null && !playerAll.some(function (it) { return it.p.x === cityPos.x && it.p.y === cityPos.y; })) {
        playerAll.push({
          kind: 'player',
          i: -1,
          d: dist(px, py, cityPos.x, cityPos.y),
          p: {
            id: 'self-city', name: (s.player && (s.player.cityName || s.player.name || s.player.username)) || '我的城市',
            x: cityPos.x, y: cityPos.y,
            prestige: (s.player && s.player.prestige) || 0, _selfCity: true
          }
        });
      }
      // scanR = 0 表示全图, 跳过距离过滤; > 0 时按半径过滤
      var inRange = scanR > 0 ? function (x) { return x.d <= scanR; } : function () { return true; };
      // 坐标搜索结果可在视野外显示，但只展示基础信息；详细情报仍需侦查
      var wildsNearby = wildAll.filter(function (x) { return (inRange(x) || x.t._searchOnly) && !x.t.occupied; });
      var npcsNearby = npcAll.concat(simulatedNpcAll)
        .filter(function (x) { return inRange(x) || x.n._searchOnly; })
        .filter(function (x) { return !x.n.defeated; });
      var playersNearby = playerAll.filter(function (x) { return inRange(x) || x.p._searchOnly; });
      var owned = wildAll.filter(function (x) { return x.t.occupied; });

      function lvOf(o) { return (o.t || o.n || o.p).level || 0; }
      function sortArr(arr) {
        if (sortMode === 'distance') arr.sort(function (a, b) { return a.d - b.d; });
        else if (sortMode === 'level-desc') arr.sort(function (a, b) { return lvOf(b) - lvOf(a); });
        else if (sortMode === 'level-asc') arr.sort(function (a, b) { return lvOf(a) - lvOf(b); });
        return arr;
      }
      wildsNearby = sortArr(wildsNearby);
      npcsNearby = sortArr(npcsNearby);
      playersNearby = sortArr(playersNearby);
      owned = sortArr(owned);

      // 野地卡
      function renderWildCard(it, ownedView) {
        var t = it.t;
        // 后端 wild_tiles.type 历史上可能写入过 D.wildTypes 里没有的旧值, 找不到时回退到通用占位
        var wt = D.wildTypes[t.type] || { name: '野地', res: null, icon: '🪨' };
        var isImg = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(wt.icon);
        var icon = isImg
          ? '<img class="tcard-icon" src="' + wt.icon + '" alt="' + wt.name + '"/>'
          : '<span class="tcard-emoji">' + wt.icon + '</span>';
        var remain = (t.totalRes || 0) - (t.mined || 0);
        var resLine = wt.res
          ? '<div class="tcard-meta">' + D.resources[wt.res].name + ' <b>' + G.fmt(remain) + '</b>/' + G.fmt(t.totalRes || 0) + '</div>'
          : '<div class="tcard-meta tcard-muted">无资源 · 仅供占领</div>';
        var guardLine = t.scouted
          ? '<div class="tcard-meta">守军 ' + armyText(t.garrison) + '</div>'
          : '<div class="tcard-meta tcard-muted">守军未知</div>';

        if (ownedView) {
          var gatherMarch = null;
          var allMarches = s.world.marches || [];
          for (var gmi = 0; gmi < allMarches.length; gmi++) {
            if (allMarches[gmi].targetKind === 'wild_gather' && allMarches[gmi].targetId === t.id) {
              gatherMarch = allMarches[gmi];
              break;
            }
          }
          var statusHtml = '';
          var actions = '';
          if (!wt.res) {
            actions = '<button class="tcard-btn tcard-btn-warn" onclick="Game.World.abandonWild(' + it.i + ')">放弃</button>';
          } else if (remain <= 0) {
            statusHtml = '<div class="tcard-status tcard-status-bad">已耗尽</div>';
            actions = '<button class="tcard-btn tcard-btn-warn" onclick="Game.World.abandonWild(' + it.i + ')">放弃</button>';
          } else if (gatherMarch) {
            if (gatherMarch.gathering && !gatherMarch.returning) {
              statusHtml = '<div class="tcard-status tcard-status-busy">采集中 ' + World.fmtMarchTime(gatherMarch.gatherEndAt) + '</div>';
            } else if (gatherMarch.returning) {
              statusHtml = '<div class="tcard-status tcard-status-busy">返程中 ' + World.fmtMarchTime(gatherMarch.arriveAt) + '</div>';
            } else {
              statusHtml = '<div class="tcard-status tcard-status-busy">行军中 ' + World.fmtMarchTime(gatherMarch.arriveAt) + '</div>';
            }
          } else {
            actions = '<button class="tcard-btn tcard-btn-ok" onclick="Game.World.gatherWild(' + it.i + ')">采集</button>' +
                      '<button class="tcard-btn tcard-btn-warn" onclick="Game.World.abandonWild(' + it.i + ')">放弃</button>';
          }
          return '<div class="tcard tcard-owned">' +
            '<div class="tcard-head">' + icon +
              '<div class="tcard-title">' + esc(wt.name) + ' <span class="tcard-lv">Lv.' + t.level + '</span></div>' +
              '<div class="tcard-dist" title="坐标">🏠 ' + t.x + ',' + t.y + '</div>' +
            '</div>' +
            resLine + statusHtml +
            '<div class="tcard-actions">' + actions + '</div>' +
          '</div>';
        }

        var acts = '';
        // 侦察只是获取守军情报, 不应作为行动的前置门槛 —— 玩家可选择盲打
        if (wt.res) {
          acts = '<button class="tcard-btn tcard-btn-ok" onclick="Game.World.attackWild(' + it.i + ',\'conquer\')">征服</button>' +
                 '<button class="tcard-btn tcard-btn-warn" onclick="Game.World.attackWild(' + it.i + ',\'plunder\')">掠夺</button>' +
                 '<button class="tcard-btn" onclick="Game.World.scoutWild(' + it.i + ')">' + (t.scouted ? '再侦察' : '侦察') + '</button>';
        } else {
          acts = '<button class="tcard-btn tcard-btn-ok" onclick="Game.World.attackWild(' + it.i + ',\'conquer\')">征服</button>' +
                 '<button class="tcard-btn" onclick="Game.World.scoutWild(' + it.i + ')">' + (t.scouted ? '再侦察' : '侦察') + '</button>';
        }
        return '<div class="tcard tcard-wild">' +
          '<div class="tcard-head">' + icon +
            '<div class="tcard-title">' + esc(wt.name) + ' <span class="tcard-lv">Lv.' + t.level + '</span></div>' +
            '<div class="tcard-dist" title="距离 ' + it.d + ' 格">📍 ' + it.d + '格</div>' +
          '</div>' +
          guardLine + resLine +
          '<div class="tcard-actions">' + acts + '</div>' +
        '</div>';
      }

      // 寇城卡：NPC 不需要宣战，可直接征服、掠夺或侦察
      function renderNpcCard(it) {
        var n = it.n;
        var nScouted = getScouted(n.x, n.y);
        var info = nScouted
          ? '<div class="tcard-meta">守军 ' + armyText(nScouted.data.army) + '</div>' +
            (nScouted.data.resources ? '<div class="tcard-meta tcard-res">资源 ' + G.fmt(nScouted.data.resources.gold || 0) + '金 ' + G.fmt(nScouted.data.resources.food || 0) + '粮</div>' : '')
          : '<div class="tcard-meta tcard-muted">敌情未知 · 需侦查</div>';
        var actions = n.defeated
          ? '<span class="tcard-badge tcard-badge-done">已征服</span>'
          : '<button class="tcard-btn tcard-btn-ok" onclick="Game.World.attack(\'' + it.kind + '\',' + it.i + ',\'conquer\')">征服</button>' +
            '<button class="tcard-btn tcard-btn-warn" onclick="Game.World.attack(\'' + it.kind + '\',' + it.i + ',\'plunder\')">掠夺</button>' +
            '<button class="tcard-btn" onclick="Game.World.attack(\'' + it.kind + '\',' + it.i + ',\'scout\')">侦察</button>';
        return '<div class="tcard tcard-npc' + (n.defeated ? ' tcard-done' : '') + '">' +
          '<div class="tcard-head">' +
            '<span class="tcard-emoji npc-icon">⚔</span>' +
            '<div class="tcard-title"><span class="npc-mark">NPC</span> ' + esc(n.name) + ' <span class="tcard-lv">Lv.' + n.level + '</span></div>' +
            '<div class="tcard-dist">📍 ' + it.d + '格</div>' +
          '</div>' +
          info +
          '<div class="tcard-actions">' + actions + '</div>' +
        '</div>';
      }

      // 玩家城卡 (紧凑列表 + 点击展开)
      // 默认只显示: 图标 + 名字 + 坐标 + 状态徽章, 单行可堆 2-3 个
      // 点击整行展开为完整卡 (含统帅、状态、驻军、行动按钮)
      function renderPlayerCard(it) {
        var p = it.p;
        var now = Date.now();
        // 主城坐标是本城的最终判定，避免旧 player_cities.ownerId 残留造成误判。
        var isSelfCity = p._selfCity || p.selfCity ||
          (cityPos.x != null && p.x === cityPos.x && p.y === cityPos.y) ||
          (s.player && p.ownerId && String(p.ownerId) === String(s.player.id));
        var cooling = p.coolAt > now;
        var warDeclared = !isSelfCity && p.warAt && p.warAt > 0;
        var warActive = warDeclared && now >= p.warAt && (!p.warEndAt || now < p.warEndAt);
        var preWar = warDeclared && now < p.warAt;
        function fmtMs(ms) {
          var wh = Math.floor(ms / 3600000), wm = Math.floor((ms % 3600000) / 60000), ws = Math.floor((ms % 60000) / 1000);
          return wh > 0 ? wh + ':' + String(wm).padStart(2, '0') + ':' + String(ws).padStart(2, '0') : wm + ':' + String(ws).padStart(2, '0');
        }
        var remStr = '';
        if (warActive && p.warEndAt) remStr = fmtMs(p.warEndAt - now);
        else if (preWar) remStr = fmtMs(p.warAt - now);

        // 名称后的交战标识：红色表示可直接进攻，灰色表示尚不可进攻。己方主城不显示。
        var combatIcon = isSelfCity ? '' : (warActive
          ? '<span class="tcard-combat-icon tcard-combat-icon-active" title="交战中：可直接进攻" aria-label="交战中">⚔</span>'
          : '<span class="tcard-combat-icon" title="' + (preWar ? '宣战中：等待开战' : '未宣战：需先宣战') + '" aria-label="' + (preWar ? '宣战中' : '未宣战') + '">⚔</span>');

        // ---- 紧凑头部 (单行) ----
        var miniBadge = '';
        if (isSelfCity) {
          miniBadge = '<span class="tcard-mini-badge tcard-mini-done">我的城市</span>';
        } else if (cooling) {
          var mins = Math.ceil((p.coolAt - now) / 60000);
          miniBadge = '<span class="tcard-mini-badge tcard-mini-shield">护盾' + mins + '分</span>';
        } else if (p.defeated) {
          miniBadge = '<span class="tcard-mini-badge tcard-mini-done">已击败</span>';
        } else if (warActive) {
          miniBadge = '<span class="tcard-mini-badge tcard-mini-war">交战中</span>';
        } else if (preWar) {
          miniBadge = '<span class="tcard-mini-badge tcard-mini-warn">宣战中</span>';
        } else if (p.cityState === 'shield') {
          miniBadge = '<span class="tcard-mini-badge tcard-mini-shield">护盾</span>';
        } else if (p.cityState === 'war') {
          miniBadge = '<span class="tcard-mini-badge tcard-mini-war">战争</span>';
        }

        // ---- 展开态内容 ----
        var pScouted = getScouted(p.x, p.y);
        var stateMap = { peace: '和平', war: '战争', shield: '护盾' };
        var expandHtml =
          '<div class="tcard-expand">' +
            '<div class="tcard-meta">城市 ' + esc(p.name || '未知城市') + '</div>' +
            '<div class="tcard-meta">状态 <b>' + (stateMap[p.cityState] || '和平') + '</b></div>' +
            '<div class="tcard-meta">声望：' + G.fmt(p.prestige || 0) + '</div>' +
            (warActive ? '<div class="tcard-meta">剩余 ' + remStr + '</div>' : '') +
            (preWar ? '<div class="tcard-meta">宣战后 ' + remStr + ' 开战</div>' : '') +
            '<div class="tcard-actions">' + renderPlayerActions(it, p, cooling, warActive, preWar) + '</div>' +
          '</div>';

        // 只在顶部 mini-row 上绑点击切换, 内容区(展开后的按钮/信息)点击不再冒泡到这里
        // 展开状态用对象存储: Object[pid] = true; 渲染时用 in / hasOwnProperty 判断
        var exp = s.world._expandedPlayerIds || {};
        var isExp = Object.prototype.hasOwnProperty.call(exp, String(p.id));
        return '<div class="tcard tcard-player tcard-player-mini' + (isExp ? ' tcard-expanded' : '') + '" data-player-id="' + p.id + '">' +
          '<div class="tcard-mini-row" onclick="Game.World.togglePlayerCard(this.parentElement)">' +
            '<span class="tcard-emoji">🏰</span>' +
            '<span class="tcard-mini-name">' + esc(p.name) + '</span>' +
            combatIcon +
            '<span class="tcard-mini-prestige">声望：' + G.fmt(p.prestige || 0) + '</span>' +
            '<span class="tcard-mini-coord">(' + p.x + ',' + p.y + ')</span>' +
            '<span class="tcard-mini-dist">' + it.d + '格</span>' +
            miniBadge +
            '<span class="tcard-mini-caret">▾</span>' +
          '</div>' +
          expandHtml +
        '</div>';
      }

      function renderPlayerActions(it, p, cooling, warActive, preWar) {
        var selfCity = p._selfCity || p.selfCity ||
          (cityPos.x != null && p.x === cityPos.x && p.y === cityPos.y) ||
          (s.player && p.ownerId && String(p.ownerId) === String(s.player.id));
        if (selfCity) {
          if (p.readyAt > Date.now()) return '<button class="tcard-btn" disabled>城市建设中</button>';
          return '<button class="tcard-btn tcard-btn-ok" onclick="event.stopPropagation();Game.Cities.enter(' + p.id + ')">进入城市</button>';
        }
        if (p._readonlyTarget) {
          return '<button class="tcard-btn" disabled>需靠近后侦察</button>';
        }
        if (cooling) {
          return '<button class="tcard-btn" onclick="event.stopPropagation();Game.World.attack(\'player\',' + it.i + ',\'scout\')">侦察</button>';
        } else if (warActive) {
          return '<button class="tcard-btn tcard-btn-ok" onclick="event.stopPropagation();Game.World.attack(\'player\',' + it.i + ',\'conquer\')">征服</button>' +
                 '<button class="tcard-btn tcard-btn-warn" onclick="event.stopPropagation();Game.World.attack(\'player\',' + it.i + ',\'plunder\')">掠夺</button>' +
                 '<button class="tcard-btn" onclick="event.stopPropagation();Game.World.attack(\'player\',' + it.i + ',\'scout\')">侦察</button>';
        } else if (preWar) {
          return '<button class="tcard-btn" disabled>开战倒计时 ' + (function(){
            var ms = p.warAt - Date.now();
            var wm=Math.floor(ms/60000),ws=Math.floor((ms%60000)/1000);
            return wm+':'+String(ws).padStart(2,'0');
          })() + '</button>' +
                 '<button class="tcard-btn" onclick="event.stopPropagation();Game.World.attack(\'player\',' + it.i + ',\'scout\')">侦察</button>';
        } else {
          return '<button class="tcard-btn tcard-btn-warn" onclick="event.stopPropagation();Game.World.declareWar(\'player\',' + it.i + ')">宣战</button>' +
                 '<button class="tcard-btn" onclick="event.stopPropagation();Game.World.attack(\'player\',' + it.i + ',\'scout\')">侦察</button>';
        }
      }

      var h = '';

      // 1. 状态条
      // 主定位显示固定的城市坐标(cityPos)，而不是可移动的视角位置(pos)。
      // 若当前视角被方向键/定位移动到了别处，再额外显示"视角"位置，避免玩家误以为城市坐标变了。
      var cityX = (cityPos.x != null) ? cityPos.x : px;
      var cityY = (cityPos.y != null) ? cityPos.y : py;
      var viewMoved = (cityPos.x != null && cityPos.y != null) && (cityPos.x !== px || cityPos.y !== py);
      h += '<div class="map-status-bar">' +
           '<span class="msb-loc">🏠 城市 (' + cityX + ',' + cityY + ')</span>' +
           (viewMoved ? '<span class="msb-sep">·</span><span class="msb-loc msb-view">📍 视角 (' + px + ',' + py + ')</span>' : '') +
           '<span class="msb-sep">·</span>' +
           '<span class="msb-meta">地图 ' + W.size + '×' + W.size + '</span>' +
           '<span class="msb-sep">·</span>' +
           '<span class="msb-meta">视野 ' + (scanR > 0 ? 'r=' + scanR : '全图') + '</span>' +
           '</div>';

      // 2. 工具栏：搜索 + 快捷跳转
      // 视野选择器选项: 3 (默认迷雾) / 5 / 8 / 10 / 0 (全图) ; 当前值回显
      var curRadius = scanR;
      var radiusOptions = [
        { v: 3,  label: 'r=3 迷雾' },
        { v: 5,  label: 'r=5 中等' },
        { v: 8,  label: 'r=8 广阔' },
        { v: 10, label: 'r=10 全屏' },
        { v: 0,  label: '全图' }
      ];
      var radSel = '<select class="qty msb-radius" id="viewRadiusSel" onchange="Game.World.setViewRadius(this.value)" title="视野半径">' +
                   radiusOptions.map(function (o) { return '<option value="' + o.v + '"' + (o.v === curRadius || (curRadius > 10 && o.v === 0) ? ' selected' : '') + '>' + o.label + '</option>'; }).join('') +
                   '</select>';
      h += '<div class="map-toolbar">' +
           '<div class="map-toolbar-row">' +
             '<input class="qty" id="searchCoord" type="text" value="' + esc(s.world._searchCoord || '') + '" placeholder="x,y 如 100,50" style="flex:1;min-width:0"/>' +
             '<button class="btn" onclick="Game.World.search()">定位</button>' +
             '<button class="btn" onclick="Game.World.scan()">扫描</button>' +
             radSel +
           '</div>' +
            '<div class="map-toolbar-quick">' +
              '<button class="qt-btn" onclick="Game.World.locateClosest(\'wild\')" title="跳转最近未占领野地">🪨 最近野地</button>' +
              '<button class="qt-btn" onclick="Game.World.locateClosest(\'npc\')" title="跳转最近流寇">⚔ 最近流寇</button>' +
              '<button class="qt-btn" onclick="Game.World.locateClosest(\'player\')" title="跳转最近玩家">🏰 最近玩家</button>' +
              '<button class="qt-btn" onclick="Game.World.locateClosest(\'owned\')" title="跳转最近已占领野地">🚩 我的领地</button>' +
            '</div>' +
            '</div>';

      // 3. Tabs
      var total = wildsNearby.length + npcsNearby.length + playersNearby.length;
      h += '<div class="map-tabs">' +
           '<div class="map-tab ' + (activeTab === 'all' ? 'active' : '') + '" onclick="Game.World.setTab(\'all\')">全部 <span class="mt-count">' + total + '</span></div>' +
           '<div class="map-tab ' + (activeTab === 'wild' ? 'active' : '') + '" onclick="Game.World.setTab(\'wild\')">野地 <span class="mt-count">' + wildsNearby.length + '</span></div>' +
           '<div class="map-tab ' + (activeTab === 'npc' ? 'active' : '') + '" onclick="Game.World.setTab(\'npc\')">流寇 <span class="mt-count">' + npcsNearby.length + '</span></div>' +
           '<div class="map-tab ' + (activeTab === 'player' ? 'active' : '') + '" onclick="Game.World.setTab(\'player\')">玩家 <span class="mt-count">' + playersNearby.length + '</span></div>' +
           '<div class="map-tab ' + (activeTab === 'owned' ? 'active' : '') + '" onclick="Game.World.setTab(\'owned\')">已占 <span class="mt-count">' + owned.length + '</span></div>' +
           '<div class="map-tabs-sort">' +
             '<select class="qty" onchange="Game.World.setSort(this.value)">' +
               '<option value="distance"' + (sortMode === 'distance' ? ' selected' : '') + '>近→远</option>' +
               '<option value="level-desc"' + (sortMode === 'level-desc' ? ' selected' : '') + '>高Lv</option>' +
               '<option value="level-asc"' + (sortMode === 'level-asc' ? ' selected' : '') + '>低Lv</option>' +
             '</select>' +
           '</div>' +
           '</div>';

      // 4. 目标卡片列表
      var cards = [];
      var searchTarget = s.world._searchTarget;
      function addCard(it, html) {
        var target = it.t || it.n || it.p;
        // 跨兵种/目标分类置顶定位结果，不改动原数组及行动按钮使用的索引。
        if (searchTarget && it.kind === searchTarget.kind &&
            target.x === searchTarget.x && target.y === searchTarget.y) {
          cards.unshift(html);
        } else {
          cards.push(html);
        }
      }
      if (activeTab === 'all' || activeTab === 'wild') wildsNearby.forEach(function (x) { addCard(x, renderWildCard(x, false)); });
      if (activeTab === 'all' || activeTab === 'npc') npcsNearby.forEach(function (x) { addCard(x, renderNpcCard(x)); });
      if (activeTab === 'all' || activeTab === 'player') playersNearby.forEach(function (x) { addCard(x, renderPlayerCard(x)); });
      if (activeTab === 'owned') owned.forEach(function (x) { addCard(x, renderWildCard(x, true)); });

      if (cards.length === 0) {
        h += '<div class="map-empty">' +
             '<div class="me-icon">🗺️</div>' +
             '<div class="me-text">视野内无可操作目标</div>' +
             '<div class="me-hint">点击上方「最近野/寇/玩家/我占」快速跳转,或用底部方向键探索</div>' +
             '</div>';
      } else {
        h += '<div class="tcard-grid">' + cards.join('') + '</div>';
      }

      // 5. 底部固定方向栏
      h += '<div class="map-control-bar">' +
           '<button class="mcb-side mcb-home" onclick="Game.World.jumpToMyCity()" title="回我城">🏠 我城</button>' +
           '<div class="mcb-dpad">' +
             '<button class="mcb-up" onclick="Game.World.move(0,-1)" title="北">↑</button>' +
             '<div class="mcb-mid">' +
               '<button class="mcb-left" onclick="Game.World.move(-1,0)" title="西">←</button>' +
               '<button class="mcb-refresh" onclick="Game.World.refreshMapData()" title="刷新地图数据">⟳</button>' +
               '<button class="mcb-right" onclick="Game.World.move(1,0)" title="东">→</button>' +
             '</div>' +
             '<button class="mcb-down" onclick="Game.World.move(0,1)" title="南">↓</button>' +
           '</div>' +
           '<button class="mcb-side mcb-back" onclick="Game.go(\'home\')" title="返回主菜单">↩ 首页</button>' +
           '</div>';

      v.innerHTML = h;
      if (G.WorldMap) v.insertAdjacentHTML('afterbegin', '<div class="world-map-list-switch"><button class="world-map-button" onclick="Game.WorldMap.setMode(\'map\')">返回大地图</button></div>');
      var hasWar = false;
      for (var wi = 0; wi < s.world.playerCities.length; wi++) {
        var wp = s.world.playerCities[wi];
        if (wp.warAt && wp.warEndAt && wp.warEndAt > Date.now()) { hasWar = true; break; }
      }
      if (hasWar) this.startWarTimer();
    },

    togglePlayerCard: function (rowEl) {
      if (!rowEl) return;
      // 状态持久化到 s.world._expandedPlayerIds, 让周期性 Core.render 也能保持展开
      var pid = rowEl.getAttribute('data-player-id');
      if (!pid) return;
      var s = Core.state;
      if (!s.world._expandedPlayerIds) s.world._expandedPlayerIds = {};
      // 用 Object 而不是 Set, 方便 JSON 序列化和比较
      var pidKey = String(pid);
      if (rowEl.classList.contains('tcard-expanded')) {
        rowEl.classList.remove('tcard-expanded');
        delete s.world._expandedPlayerIds[pidKey];
      } else {
        rowEl.classList.add('tcard-expanded');
        s.world._expandedPlayerIds[pidKey] = true;
      }
    },

    alertCountdown: function (arriveAt) {
      var deadline = Number(arriveAt);
      if (!Number.isFinite(deadline)) return '<b>时间未知</b>';
      var text = deadline <= Date.now() ? '已到达，等待战斗结果' : this.fmtMarchTime(deadline);
      return '<b class="alert-countdown" data-arrive-at="' + deadline + '">' + text + '</b>';
    },

    stopAlertTimer: function () {
      if (this._alertTimer != null) clearInterval(this._alertTimer);
      this._alertTimer = null;
    },

    startAlertTimer: function (view) {
      this.stopAlertTimer();
      if (!view.querySelectorAll || !view.querySelectorAll('[data-arrive-at]').length) return;
      var self = this;
      this._alertTimer = setInterval(function () {
        if (Core.route !== 'alerts' || view.isConnected === false) {
          self.stopAlertTimer();
          return;
        }
        var now = Date.now();
        view.querySelectorAll('[data-arrive-at]').forEach(function (el) {
          var deadline = Number(el.getAttribute('data-arrive-at'));
          var text = deadline <= now ? '已到达，等待战斗结果' : self.fmtMarchTime(deadline);
          if (el.textContent !== text) el.textContent = text;
        });
      }, 1000);
    },

    renderAlerts: function (v) {
      var s = Core.state;
      var marches = s.world.marches || [];
      var incoming = s.world.incoming || [];
      var h = '';
      h += '<div class="alerts-hero"><div class="title">军情警讯</div>';
      h += '<div class="alerts-summary"><span>行军中 <b>' + marches.length + '</b> 起</span><i></i><span class="' + (incoming.length ? 'danger' : '') + '">来袭 <b>' + incoming.length + '</b> 起</span></div></div>';

      h += '<div class="zone-head alerts-section-title"><span class="section-icon">➤</span> 我军行军 <em>' + marches.length + '</em></div>';
      h += '<div class="menu">';
      if (!marches.length) {
        h += '<div class="desc">暂无行军中的部队。</div>';
      } else {
        var now = Date.now();
        for (var mi = 0; mi < marches.length; mi++) {
          var m = marches[mi];
          var remain = Math.max(0, Math.ceil((m.arriveAt - now) / 1000));
          var timeStr = this.alertCountdown(m.arriveAt);
          var kindName = m.returning ? '返城' : ({ conquer: '征服', plunder: '掠夺', scout: '侦查', transport: '运输', rebase: '调遣' }[m.action] || '出征');
          var urgent = remain <= 10 ? 'urgent' : '';
          // 兼容字段缺失: 老存档/老推送可能没带 distance/originName 等
          var fromX = m.fromX != null ? m.fromX : '?';
          var fromY = m.fromY != null ? m.fromY : '?';
          var toX = m.targetX != null ? m.targetX : '?';
          var toY = m.targetY != null ? m.targetY : '?';
          var distance = m.distance != null ? m.distance : '?';
          var targetName = m.targetName || '目标';
          var originName = m.originName || '';
          h += '<div class="menu-item ' + (m.returning ? 'lock' : 'ok') + '">';
          h += '<span class="n">' + kindName + '->' + G.escapeHtml(targetName) + (m.returning ? ' (撤自' + G.escapeHtml(originName || '原地') + ')' : '') + '</span>';
          h += '<span class="lv">距' + distance + '格 (' + fromX + ',' + fromY + '->' + toX + ',' + toY + ')</span>';
          h += '<div class="d">兵力: ' + armyText(m.army) + '</div>';
          h += '<div class="cost ' + urgent + '">剩余: ' + timeStr + '</div>';
          if (remain > 0 && !m.returning) {
            h += '<div class="btn-row"><button class="btn warn sm" onclick="Game.World.cancelMarch(\'' + m.id + '\')">撤回</button></div>';
          }
          h += '</div>';
        }
      }
      h += '</div>';

      h += '<div class="zone-head alerts-section-title enemy-section"><span class="section-icon">⚠</span> 敌军来袭 (' + incoming.length + ') <em>' + incoming.length + '</em></div>';
      h += '<div class="menu">';
      if (!incoming.length) {
        h += '<div class="desc">暂无敌方来袭情报。</div>';
      } else {
        var now2 = Date.now();
        for (var ii = 0; ii < incoming.length; ii++) {
          var im = incoming[ii];
          var rem = Math.max(0, Math.ceil((im.arriveAt - now2) / 1000));
          var tStr = this.alertCountdown(im.arriveAt);
          var isArrived = im.arrived;
          // 兼容旧存档/旧推送只带 fromName 的来袭记录，避免界面出现 undefined。
          var attackerName = G.escapeHtml(im.attackerName || im.sourcePlayer || im.fromName || '未知敌军');
          var targetX = im.targetX != null ? im.targetX : (s.world.cityPos ? s.world.cityPos.x : '?');
          var targetY = im.targetY != null ? im.targetY : (s.world.cityPos ? s.world.cityPos.y : '?');
          var targetName = G.escapeHtml(im.targetName || (s.player && (s.player.cityName || s.player.username)) || '我方主城');
          h += '<div class="menu-item ' + (isArrived ? 'ok' : 'lock') + '" style="cursor:pointer" onclick="Game.World.toggleIncomingExpand(' + ii + ')">';
          h += '<span class="n">' + attackerName + ' 来袭</span>';
          if (isArrived) {
            h += '<span class="lv" style="color:var(--danger);font-weight:bold">已到达！待迎战</span>';
          } else {
            h += '<span class="lv">到达: ' + tStr + '</span>';
          }
          h += ' <span class="lv">' + (im.expanded ? '▼' : '▶') + '</span>';
          if (im.expanded) {
            h += '<div class="d" style="margin-top:4px;padding-left:8px;border-left:2px solid var(--border)">';
            h += '<div>来袭玩家: <b>' + attackerName + '</b></div>';
            h += '<div>出发地: (' + (im.fromX != null ? im.fromX : '?') + ',' + (im.fromY != null ? im.fromY : '?') + ')</div>';
            h += '<div>目的地: (' + targetX + ',' + targetY + ') ' + targetName + '</div>';
            h += '<div>统帅: <b>' + (im.commander || '未知') + '</b>';
            if (im.commanderStar) {
              var starStr2 = '';
              for (var si2 = 0; si2 < im.commanderStar; si2++) starStr2 += '★';
              h += ' <span style="color:var(--gold)">' + starStr2 + '</span>';
            }
            if (im.commanderLevel) h += ' Lv.' + im.commanderLevel;
            h += '</div>';
            h += '<div>兵种详情:</div>';
            var armyTotal = 0;
            for (var auid in im.army) {
              var au = D.units[auid];
              h += '<div style="padding-left:12px">- ' + (au ? au.name : auid) + ' x' + G.fmt(im.army[auid]) + '</div>';
              armyTotal += im.army[auid];
            }
            h += '<div>总兵力: <b style="color:var(--danger)">' + G.fmt(armyTotal) + '</b></div>';
            if (isArrived) {
              h += '<div class="cost urgent" style="color:var(--danger)">敌军已到达城下！</div>';
              h += '<div class="btn-row" onclick="event.stopPropagation()"><button class="btn ok" style="font-size:16px;padding:8px 24px" onclick="Game.World.startIncomingBattle(' + ii + ')">迎战！</button></div>';
            } else {
              h += '<div class="cost urgent">到达倒计时: ' + tStr + '</div>';
            }
            h += '</div>';
          }
          h += '</div>';
        }
      }
      h += '</div>';

      h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
      v.innerHTML = h;
      this.startAlertTimer(v);
    }
  };

  G.World = World;
  Core.views.world = function (v) { World.renderView(v); };
  Core.views.dispatch = function (v) { World.renderDispatch(v); };
  Core.views.alerts = function (v) { World.renderAlerts(v); };
})(window.Game);
