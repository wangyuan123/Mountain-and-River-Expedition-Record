/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';
  G.gameNow = G.gameNow || function () { return Date.now(); };

  var D = G.DATA;
  var Core = G.Core;

  /** 我的领地按当前城市筛选，地图上的occupied仍表示同账号所有城市的归属。 */
  function currentCityWild(s, target) {
    return target.occupied && (target.occupiedCitySlot == null ? 0 : target.occupiedCitySlot) === ((s.player && s.player.citySlot) || 0);
  }

  function esc(s) {
    if (G && typeof G.escapeHtml === 'function') return G.escapeHtml(s);
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function dist(x1, y1, x2, y2) {
    return Math.hypot(x1 - x2, y1 - y2);
  }

  function distanceText(distance) {
    return Number(Number(distance).toFixed(2));
  }

  function armyText(army, isJapanese) {
    var arr = [];
    for (var id in army) {
      var u = (isJapanese && D.japaneseUnits && D.japaneseUnits[id]) || D.units[id] || (D.forts && D.forts[id]);
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
    /**
     * 按稳定城市／目标 ID 更新列表后分发地图行动，劝降独立于出征处理。
     * @param {Object} target - 点击时重新读取的目标状态
     * @param {string} action - 地图行动类型
     */
    mapAction: function (target, action) {
      var keys = G.Constants.worldTargetKeys;
      var key = keys[target.kind];
      if (!key) return;
      var list = Core.state.world[key] || (Core.state.world[key] = []);
      var idx = list.findIndex(function (t) { return String(t.id) === String(target.id); });
      if (idx < 0) { idx = list.length; list.push(target); } else list[idx] = target;
      if (target.selfCity) { G.go('home'); return; }
      if (action === 'surrender') { if (target.kind === 'player') this.offerSurrender(idx); return; }
      if (action === 'declare') { this.declareWar(target.kind, idx); return; }
      if (target.kind === 'wild') {
        if (action === 'scout') this.scoutWild(idx);
        else if (action === 'station') this.dispatchWild(idx, 'station');
        else if (action === 'gatherDispatch') this.dispatchWild(idx, 'gather');
        else if (action === 'gather') this.startGatherWild(idx);
        else if (action === 'harvest') this.harvestWild(idx);
        else if (action === 'recall') this.recallWild(idx);
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
      s.world._scan = { r: D.world.viewRadius, at: G.gameNow() };
      if (G.WorldMap && typeof G.WorldMap.focusCoordinate === 'function') {
        G.WorldMap.focusCoordinate(x, y);
      }
      Core.render();
    },

    /**
     * 从军情等入口定位到目标坐标，并在切换地图路由后恢复该坐标视角。
     * @param {number|string} x - 目标横坐标
     * @param {number|string} y - 目标纵坐标
     */
    locateTarget: function (x, y) {
      var nx = Number(x), ny = Number(y);
      if (!Number.isFinite(nx) || !Number.isFinite(ny)) return;
      var size = (D.world && D.world.size) || 200;
      nx = G.clamp(Math.round(nx), 0, size - 1);
      ny = G.clamp(Math.round(ny), 0, size - 1);

      // 进入地图会按默认规则重置视角，因此路由切换必须先完成。
      if (Core.route !== 'world') {
        Game.go('world');
      }

      var s = Core.state;
      if (s && s.world) {
        s.world._searchTarget = null;
        s.world._searchCoord = nx + ',' + ny;
        s.world._mapPos = { x: nx, y: ny };
        s.world._scan = { r: D.world.viewRadius, at: G.gameNow() };
      }
      if (G.WorldMap && typeof G.WorldMap.focusCoordinate === 'function') {
        G.WorldMap.focusCoordinate(nx, ny);
      }
      if (!G.WorldMap || !G.WorldMap.isMap()) {
        Core.render();
      }
      G.toast('已定位至 (' + nx + ',' + ny + ')');
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
      } else if (kind === 'npc' || kind === 'bandit') {
        var bandits = s.world.bandits || [];
        target = bandits
          .filter(function (n) { return !n.defeated; })
          .map(function (n) { return { n: n, d: dist(px, py, n.x, n.y) }; })
          .sort(function (a, b) { return a.d - b.d; })[0];
        if (target) target = target.n;
      } else if (kind === 'player') {
        var now = G.gameNow();
        target = (s.world.playerCities || [])
          .filter(function (p) { return !p.coolAt || p.coolAt < now; })
          .map(function (p) { return { p: p, d: dist(px, py, p.x, p.y) }; })
          .sort(function (a, b) { return a.d - b.d; })[0];
        if (target) target = target.p;
      } else if (kind === 'owned') {
        target = (s.world.wildTiles || [])
          .filter(function (t) { return currentCityWild(s, t); })
          .map(function (t) { return { t: t, d: dist(px, py, t.x, t.y) }; })
          .sort(function (a, b) { return a.d - b.d; })[0];
        if (target) target = target.t;
      }
      if (!target) { G.toast('没有可跳转的目标'); return; }
      if (kind === 'owned') {
        s.world._activeTab = 'owned';
      } else if (kind === 'wild') {
        s.world._activeTab = 'wild';
      } else if (kind === 'npc' || kind === 'bandit') {
        s.world._activeTab = 'npc';
      } else if (kind === 'player') {
        s.world._activeTab = 'player';
      }
      this.jumpTo(target.x, target.y);
      G.toast('已跳转至 (' + target.x + ',' + target.y + ') 距 ' + distanceText(dist(px, py, target.x, target.y)) + ' 格');
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
      if (!s.world._scan) s.world._scan = { r: n, at: G.gameNow() };
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
      s.world._scan = { r: D.world.viewRadius, at: G.gameNow() };
      // 搜索不受视野过滤影响，只加载坐标处的基础目标信息；详细情报仍需侦查
      if (G.API && G.API.getCoordinateTarget) {
        G.API.getCoordinateTarget(x, y).then(function (target) {
          s.world._searchTarget = target && target.kind ? target : null;
          if (target && target.kind) {
            s.world._activeTab = target.kind === 'wild' && target.occupied ? 'owned' : 'all';
            var listKey = target.kind === 'player' ? 'playerCities' :
              (target.kind === 'bandit' ? 'bandits' : (target.kind === 'simulated_npc' ? 'simulatedNpcCities' : 'wildTiles'));
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

    /** 按当前战争窗口及军团外交规则检查玩家城攻击资格，再打开出征页。 */
    attack: function (kind, idx, action) {
      if (kind === 'npc') { G.toast('NPC城市/要塞已取消'); return; }
      var s = Core.state;
      var target;
      if (kind === 'bandit') target = s.world.bandits[idx];
      else if (kind === 'simulated_npc') target = s.world.simulatedNpcCities[idx];
      else if (kind === 'player') target = s.world.playerCities[idx];
      if (!target) return;
      var targetShielded = (target.shieldUntil != null ? target.shieldUntil > G.gameNow() : target.cityState === 'shield') || (target.coolAt > G.gameNow());
      if (kind === 'player' && targetShielded) {
        G.toast((target.name || '目标城市') + '处于护盾期间，无法发起侦查或攻击');
        return;
      }
      if (kind === 'player' && target.coolAt > G.gameNow()) {
        var mins = Math.ceil((target.coolAt - G.gameNow()) / 60000);
        G.toast('该玩家处于保护期,剩 ' + mins + ' 分钟');
        return;
      }
      if (kind === 'player' && action !== 'scout') {
        if (target.guildRelation === 'friendly') {
          G.toast('友好军团成员之间不能讨伐');
          return;
        }
        // 敌对军团可持续交战，与地图按钮及后端权限保持一致。
        if (target.guildRelation !== 'hostile') {
          var now = G.gameNow();
          if (!target.warAt || target.warAt > now) {
            G.toast('需先宣战并等待倒计时结束才能攻击玩家');
            return;
          }
          if (!(target.warEndAt > now)) {
            G.toast('战争已结束,需重新宣战');
            return;
          }
        }
      }
      s.world._dispatchTarget = this.prepareCampaign({ kind: kind, idx: idx, action: action || 'campaign', target: target });
      G.go('dispatch');
    },

    /**
     * 向列表或地图解析出的真实玩家城市发送劝降邀请，确认前检查城市是否可移交。
     * @param {number} idx - 当前玩家城市列表中的索引
     */
    offerSurrender: function (idx) {
      var s = Core.state, target = s.world && s.world.playerCities && s.world.playerCities[idx];
      if (!target || !target.id || target.selfCity) return;
      if (target.readyAt > G.gameNow()) { G.toast('目标城市尚未建成'); return; }
      if (target.surrenderFrozen) { G.toast('该城市正在移交中'); return; }
      this.showConfirm({
        title: '发送劝降提议',
        message: '向“' + target.name + '”（' + target.x + ',' + target.y + '）发送劝降提议？',
        subMessage: '对方有 24 小时答复；接受后城市冻结，并在 24 小时后完成移交。',
        okText: '发送劝降电报',
        onConfirm: function () {
          G.API.offerSurrender(target.id).then(function () {
            G.toast('劝降电报已送达，对方有 24 小时答复');
            Core.render();
          }).catch(function (err) { G.toast(err.message || '发送劝降失败'); });
        }
      });
    },

    scoutWild: function (idx) {
      var s = Core.state;
      var t = s.world.wildTiles[idx];
      if (!t) return;
      s.world._dispatchTarget = { kind: 'wild', idx: idx, action: 'scout', target: t };
      G.go('dispatch');
    },

    attackWild: function (idx, action) {
      var s = Core.state;
      var t = s.world.wildTiles[idx];
      if (!t) return;
      if (t.occupied) { G.toast('已占领'); return; }
      s.world._dispatchTarget = this.prepareCampaign({ kind: 'wild', idx: idx, action: action || 'campaign', target: t });
      G.go('dispatch');
    },

    /**
     * 将旧攻击入口转为讨伐草稿；任务预选仅作用于下一次对应目标的普通讨伐入口。
     * @param {Object} dt - 本次出征目标和配置
     * @returns {Object} 兼容旧动作、任务预选与五种出征模式的草稿
     */
    prepareCampaign: function (dt) {
      if (!dt) return;
      if (dt.action === 'intercept_scout') return dt;
      if (!dt.action) dt.action = 'campaign';
      var modes = (G.Constants && G.Constants.campaignModes) || [];
      var legacy = modes.some(function (mode) { return mode.action === dt.action; });
      if (legacy) {
        dt.campaignAction = dt.action;
        dt.occupyTarget = dt.action === 'conquer';
        dt.action = 'campaign';
      }
      if (dt.action !== 'campaign') return dt;
      var taskMode = Core.state.world._campaignTaskMode;
      if (!legacy && !dt.campaignAction && typeof dt.occupyTarget !== 'boolean' && taskMode && taskMode.kind === dt.kind) {
        dt.occupyTarget = taskMode.occupyTarget;
        delete Core.state.world._campaignTaskMode;
      }
      if (typeof dt.occupyTarget !== 'boolean') dt.occupyTarget = true;
      if (!modes.some(function (mode) { return mode.action === dt.campaignAction; })) {
        dt.campaignAction = dt.occupyTarget ? 'conquer' : 'plunder';
      }
      var wild = dt.target || (Core.state.world.wildTiles || [])[dt.idx];
      var type = dt.kind === 'wild' && wild && (D.wildTypes || {})[wild.type];
      // 无资源地块不能掠夺，但允许不取资源的短回合袭击；旧占领布尔值继续供任务兼容。
      if (dt.kind === 'wild' && (!type || !type.res) && dt.campaignAction === 'plunder') dt.campaignAction = 'conquer';
      dt.occupyTarget = dt.campaignAction === 'conquer';
      if (!dt.conquerMode) dt.conquerMode = 'withdraw';
      return dt;
    },

    /**
     * 路线预览和正式提交共用此映射，campaign 仅在前端存在。
     * @param {Object} dt - 本次出征草稿
     * @returns {string} 后端支持并冻结到行军中的动作
     */
    resolveDispatchAction: function (dt) {
      this.prepareCampaign(dt);
      return dt.action === 'campaign' ? dt.campaignAction : dt.action;
    },

    /** 根据目标与处置模式生成说明，不推测尚未侦察的库存和民心。 */
    campaignDescription: function (dt) {
      var action = this.resolveDispatchAction(dt);
      var mode = G.Constants.campaignModes.find(function (item) { return item.action === action; });
      if (mode && mode.description) return mode.description;
      if (dt.kind === 'wild') {
        var tile = dt.target || Core.state.world.wildTiles[dt.idx];
        var type = tile && D.wildTypes[tile.type];
        if (!type || !type.res) return '该地块没有可夺取资源，无法选择掠夺。征服胜利后取得地块归属，也可选择佯攻、袭扰或游击打击守军。';
        if (action === 'conquer') return '胜利后占领该野地，之后可驻军采集；本次战斗不直接带走野地资源。';
        var remaining = Math.max(0, (tile.totalRes || 0) - (tile.mined || 0));
        return '胜利后按幸存部队负重夺取剩余资源，不改变野地归属，部队自动返城。' +
          (remaining === 0 ? '剩余资源为0，本次胜利不会取得野地资源。' : '');
      }
      if (action === 'plunder') return '胜利后取得仓库保护外的非黄金资源，不接管城市，也不俘虏指挥官；胜利结算经验为占领模式的一半。';
      if (dt.kind === 'player') return '胜利后尝试接管城市：需要敌城战后民心为0，且我方城市数量未达军衔上限。成功时保留建筑、建设队列和普通资源，清除其余军事资产。未能接管时取得全部普通资源，含黄金。忠诚为0的敌方参战指挥官可被俘虏。';
      return '该目标无法保留为我方领地，胜利后会清除或刷新；征服胜利后缴获全部普通资源，含黄金。';
    },

    /** 只更新战后处置区域，保留已填兵力、指挥官、战斗方式和携带资源。 */
    updateCampaignControls: function () {
      var dt = Core.state.world._dispatchTarget;
      if (!dt || dt.action !== 'campaign' || typeof document === 'undefined') return;
      var action = this.resolveDispatchAction(dt);
      var select = document.getElementById('dpCampaignAction');
      if (select) select.value = action;
      var name = G.Constants.dispatchActionNames[action];
      var heading = document.getElementById('dpCampaignHeading');
      if (heading) heading.textContent = name + '说明';
      var limit = document.getElementById('dpCampaignLimit');
      if (limit) limit.textContent = '最多 ' + G.Constants.battleRoundLimits[action] + ' 回合';
      var description = document.getElementById('dpCampaignDescription');
      if (description) description.textContent = this.campaignDescription(dt);
      var troops = document.getElementById('dpCampaignTroops');
      if (troops) troops.hidden = dt.kind !== 'wild' || action !== 'conquer';
      var summary = document.getElementById('dpCampaignSummary');
      if (summary) summary.textContent = '本次出征：' + name + ' · ' + (action === 'conquer' ? '尝试占领' : action === 'plunder' ? '仅夺取资源' : '不获取资源') + ' · ' +
        (dt.kind === 'wild' && action === 'conquer' && dt.conquerMode === 'occupy' ? '原地驻守' : '自动返城');
    },

    /** 兼容旧任务入口，转为对应的征服或掠夺选择。 */
    setOccupyTarget: function (enabled) {
      this.setCampaignAction(enabled ? 'conquer' : 'plunder');
    },

    /** 切换真实出征动作并使旧路线失效，只更新局部控件以保留表单。 */
    setCampaignAction: function (action) {
      var dt = Core.state.world._dispatchTarget;
      if (!dt || dt.action !== 'campaign' || this._launching) return;
      if (!G.Constants.campaignModes.some(function (mode) { return mode.action === action; })) return;
      dt.campaignAction = action;
      this.prepareCampaign(dt);
      this.updateCampaignControls();
      if (this._refreshDispatchRoute) this._refreshDispatchRoute();
    },

    /**
     * 展示随当前主题切换的确认弹窗，只有确认按钮执行操作；取消、遮罩和 Esc 均关闭。
     * @param {Object} opts - 标题、正文、按钮文案及确认回调
     */
    showConfirm: function (opts) {
      if (typeof document === 'undefined') {
        if (confirm((opts.title ? opts.title + '\n\n' : '') + opts.message)) {
          if (opts.onConfirm) opts.onConfirm();
        }
        return;
      }
      var mask = document.createElement('div');
      mask.className = 'modal-mask confirm-dialog-mask';
      var previousFocus = document.activeElement;
      var title = esc(opts.title || '操作确认');
      var msg = esc(opts.message || '');
      var sub = opts.subMessage ? '<p class="confirm-dialog-submessage">' + esc(opts.subMessage) + '</p>' : '';
      var okText = esc(opts.okText || '确定');
      var cancelText = esc(opts.cancelText || '取消');
      var okClass = opts.danger ? 'btn btn-danger' : 'btn btn-primary';
      mask.innerHTML =
        '<div class="modal-card confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="worldConfirmTitle">' +
          '<div class="modal-title" id="worldConfirmTitle">' + title + '</div>' +
          '<div class="modal-body confirm-dialog-message">' +
            '<p>' + msg + '</p>' + sub +
          '</div>' +
          '<div class="modal-foot confirm-dialog-actions">' +
            '<button type="button" class="btn btn-secondary" id="confirmCancelBtn">' + cancelText + '</button>' +
            '<button type="button" class="' + okClass + '" id="confirmOkBtn">' + okText + '</button>' +
          '</div>' +
        '</div>';
      // 原生全屏只展示全屏元素的子树，地图全屏时将弹窗挂在该元素内。
      (document.fullscreenElement || document.body).appendChild(mask);
      var closed = false;
      var cleanup = function () {
        if (closed) return;
        closed = true;
        if (mask.parentNode) mask.parentNode.removeChild(mask);
        if (previousFocus && previousFocus.isConnected && previousFocus.focus) previousFocus.focus();
      };
      var cancel = mask.querySelector('#confirmCancelBtn');
      var ok = mask.querySelector('#confirmOkBtn');
      cancel.onclick = cleanup;
      ok.onclick = function () {
        if (closed) return;
        cleanup();
        if (typeof opts.onConfirm === 'function') opts.onConfirm();
      };
      mask.onclick = function (e) { if (e.target === mask) cleanup(); };
      mask.onkeydown = function (e) {
        if (e.key === 'Escape') cleanup();
        if (e.key === 'Tab' && ((e.shiftKey && document.activeElement === cancel) || (!e.shiftKey && document.activeElement === ok))) {
          e.preventDefault();
          (e.shiftKey ? ok : cancel).focus();
        }
      };
      if (cancel.focus) cancel.focus();
    },

    /** 新手保护按账号生效，仅在用户确认本次军事行动后传递退出意图。 */
    hasNewbieProtection: function () {
      var s = Core.state || {};
      return Math.max((s.player || {}).newbieProtectionUntil || 0, (s.cityState || {}).newbieProtectionUntil || 0) > G.gameNow();
    },

    /** 取消不发送请求；切换账号或出发城市后，旧确认也不能继续提交。 */
    confirmPlayerWarfare: function (needed, send) {
      if (!needed || !this.hasNewbieProtection()) { send(false); return; }
      var actor = Core.state.player || {};
      var playerId = actor.id, citySlot = actor.citySlot;
      this.showConfirm({
        title: '确认退出新手保护',
        message: '参与玩家战争将立即永久结束账号所有城市的新手保护，无法恢复。',
        subMessage: '首次进入保护7天；之后声望不足10000可延长，最长14天。正常建设和打NPC无需退出。',
        okText: '退出保护并继续', cancelText: '保留保护', danger: true,
        onConfirm: function () {
          var current = Core.state.player || {};
          if (current.id !== playerId || current.citySlot !== citySlot) { G.toast('出发城市已改变，请重新操作'); return; }
          send(true);
        }
      });
    },

    /** 展示出征城市的带兵上限构成，与 Core.armyCap 的实时规则保持一致。 */
    showArmyCapInfo: function () {
      var state = Core.state || {};
      var tier = (state.player && state.player.militaryRank) || 1;
      var rank = G.getMilitaryRankTierInfo ? G.getMilitaryRankTierInfo(tier) : { name: '列兵', baseCap: 25000 };
      var rankBase = rank.baseCap || 25000;
      var skills = Core.getCommanderSkills ? Core.getCommanderSkills() : {};
      var skillLevel = Math.max(skills.leadership || 0, skills.supply || 0);
      var skillBonus = Core.skillBonus ? Math.max(Core.skillBonus('leadership'), Core.skillBonus('supply')) : 0;
      var skillPercent = Math.round(skillBonus * 100);
      var total = Core.armyCap();
      var mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.setAttribute('role', 'dialog');
      mask.setAttribute('aria-modal', 'true');
      mask.setAttribute('aria-labelledby', 'dispatchArmyCapTitle');
      mask.innerHTML =
        '<div class="modal-card dispatch-cap-modal">' +
          '<div class="modal-title" id="dispatchArmyCapTitle">带兵上限说明</div>' +
          '<div class="modal-body">' +
            '<p>列兵基础上限为 25,000，上尉及以下每阶增加 25,000；少校为 300,000，此后每阶增加 50,000，上将为 600,000。</p>' +
            '<p>当前军衔：' + esc(rank.name) + '，基础上限 ' + G.fmt(rankBase) + '。</p>' +
            '<p>三军统帅：任命指挥官后每级额外 +4%，最高 +20%；当前 Lv.' + skillLevel + '，加成 +' + skillPercent + '%。</p>' +
            '<p><strong>当前上限 = ' + G.fmt(rankBase) + ' × (1 + ' + skillPercent + '%) = ' + G.fmt(total) + '</strong></p>' +
            '<p>市政厅、作战参谋部和指挥官等级不直接增加上限；单次出征还需有足够的当前城市可用驻军。</p>' +
          '</div>' +
          '<div class="modal-foot"><button type="button" class="btn btn-primary" id="dispatchCapClose">知道了</button></div>' +
        '</div>';
      document.body.appendChild(mask);
      var close = function () { if (mask.parentNode) mask.parentNode.removeChild(mask); };
      var closeButton = mask.querySelector('#dispatchCapClose');
      closeButton.onclick = close;
      mask.onclick = function (event) { if (event.target === mask) close(); };
      mask.onkeydown = function (event) { if (event.key === 'Escape') close(); };
      closeButton.focus();
    },

    dispatchWild: function (idx, action) {
      var s = Core.state;
      var t = s.world.wildTiles[idx];
      if (!t) return;
      s.world._dispatchTarget = { kind: action === 'gather' ? 'wild_gather' : 'wild', idx: idx, action: action || 'station', target: t };
      G.go('dispatch');
      if (action === 'gather' && G.Onboarding && G.Onboarding.showTaskHint) G.Onboarding.showTaskHint('gather');
    },

    abandonWild: function (idx) {
      var s = Core.state;
      var t = s.world.wildTiles[idx];
      if (!t || !t.occupied) return;
      var wtDef = (G.DATA && G.DATA.wildTypes && G.DATA.wildTypes[t.type]) || {};
      var wtName = wtDef.name || '野地';
      var hasGarrison = t.garrison && Object.values(t.garrison).some(function (v) { return v > 0; });
      this.showConfirm({
        title: '⚠️ 放弃领地确认',
        danger: true,
        okText: '确认放弃',
        cancelText: '保留领地',
        message: '确定要放弃【' + wtName + ' (' + t.x + ', ' + t.y + ')】吗？',
        subMessage: '放弃后领地将恢复为中立未占领状态' + (hasGarrison ? '，驻扎的部队将自动撤回主城' : '') + '。',
        onConfirm: function () {
          G.API.wildAbandon(t.id).then(function (res) {
            if (G.WorldMap) G.WorldMap.invalidate();
            G.toast(res && res.message ? res.message : '已放弃领地');
            Core.render();
          }).catch(function (err) {
            G.toast(err.message || '操作失败');
          });
        }
      });
    },

    recallWild: function (idx) {
      var s = Core.state;
      var t = s.world.wildTiles[idx];
      if (!t || !t.occupied) return;
      var wtDef = (G.DATA && G.DATA.wildTypes && G.DATA.wildTypes[t.type]) || {};
      var wtName = wtDef.name || '野地';
      this.showConfirm({
        title: '🛡 撤回驻军确认',
        danger: false,
        okText: '确认撤回',
        cancelText: '取消',
        message: '确定要撤回驻扎在【' + wtName + ' (' + t.x + ', ' + t.y + ')】的部队吗？',
        subMessage: '驻军将按原路线返回各自出发城市，抵达后部队与采集资源入库。军情页可查看返城进度，野地仍保留您的占领归属。',
        onConfirm: function () {
          G.API.wildRecall(t.id).then(function (res) {
            if (G.WorldMap) G.WorldMap.invalidate();
            G.toast(res && res.message ? res.message : '驻军已开始返城');
            Core.render();
          }).catch(function (err) {
            G.toast(err.message || '操作失败');
          });
        }
      });
    },

    startGatherWild: function (idx) {
      var s = Core.state;
      var t = s.world.wildTiles[idx];
      if (!t || !t.occupied) return;
      if (t.gathering || t.gatherHarvested != null) { G.toast('驻军已有采集任务，请先完成收获和回城'); return; }
      var wt = G.DATA.wildTypes[t.type];
      if (!wt || !wt.res) { G.toast('该野地无资源可采集'); return; }
      var remaining = (t.totalRes || 0) - (t.mined || 0);
      if (remaining <= 0) { G.toast('该野地资源已耗尽'); return; }
      var hasGarrison = t.garrison && Object.values(t.garrison).some(function (v) { return v > 0; });
      if (!hasGarrison) {
        G.toast('野地暂无驻军，请先【派遣】部队进驻');
        return;
      }
      if (document.getElementById('stationedGatherModal')) return;
      if (G.Onboarding && G.Onboarding.showTaskHint) G.Onboarding.showTaskHint('gather');
      var tileId = t.id;
      var mask = document.createElement('div');
      mask.id = 'stationedGatherModal';
      mask.className = 'modal-mask';
      mask.setAttribute('role', 'dialog');
      mask.setAttribute('aria-modal', 'true');
      mask.setAttribute('aria-labelledby', 'stationedGatherTitle');
      mask.innerHTML = '<div class="modal-card stationed-gather-modal" style="max-width:480px;width:92%;">' +
        '<div class="modal-title" id="stationedGatherTitle">驻军采集准备 · ' + esc(wt.name || '野地') + '</div>' +
        '<fieldset class="panel dispatch-gather-modes"><legend>采集模式</legend>' +
        '<label class="dispatch-gather-mode"><input type="radio" name="stationedGatherMode" value="auto" checked />' +
          '<span><strong>采集全自动</strong><span>采集完成后自动收获并自动按原路线回城</span></span></label>' +
        '<label class="dispatch-gather-mode"><input type="radio" name="stationedGatherMode" value="manual" />' +
          '<span><strong>采集全手动</strong><span>需要玩家自主终止采集任务，需要亲自下达命令选择部队回城</span></span></label>' +
        '<div class="desc">全手动点击“收获”终止采集，之后点击“部队回城”。资源抵达城市后入库，返城不改变野地归属。</div></fieldset>' +
        '<div class="modal-foot"><button class="btn btn-secondary" data-gather-cancel>取消</button>' +
        '<button class="btn btn-primary" data-gather-start>开始采集</button></div></div>';
      document.body.appendChild(mask);
      var pending = false;
      var startButton = mask.querySelector('[data-gather-start]');
      var cancelButton = mask.querySelector('[data-gather-cancel]');
      var close = function () { if (!pending && mask.parentNode) mask.parentNode.removeChild(mask); };
      cancelButton.onclick = close;
      mask.onclick = function (event) { if (event.target === mask) close(); };
      mask.onkeydown = function (event) { if (event.key === 'Escape') close(); };
      startButton.onclick = function () {
        if (pending) return;
        var selected = mask.querySelector('input[name="stationedGatherMode"]:checked');
        pending = true; startButton.disabled = true; cancelButton.disabled = true;
        G.API.wildStartGather(tileId, selected ? selected.value : 'auto').then(function (res) {
          pending = false; close();
          if (G.WorldMap) G.WorldMap.invalidate();
          G.toast(res && res.message ? res.message : '已开始就地采集');
          Core.render();
        }).catch(function (err) {
          pending = false; startButton.disabled = false; cancelButton.disabled = false;
          G.toast(err.message || '开启采集失败');
        });
      };
      startButton.focus();
    },

    harvestWild: function (idx) {
      var s = Core.state;
      var t = s.world.wildTiles[idx];
      if (!t || !t.occupied) return;
      G.API.wildHarvest(t.id).then(function (res) {
        if (G.WorldMap) G.WorldMap.invalidate();
        G.toast(res && res.message ? res.message : '已收获，部队留守等待回城命令');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '收获失败');
      });
    },

    gatherWild: function (idx) {
      var s = Core.state;
      var t = s.world.wildTiles[idx];
      if (!t || !t.occupied) return;
      var hasGarrison = t.garrison && Object.values(t.garrison).some(function (v) { return v > 0; });
      if (hasGarrison) {
        this.startGatherWild(idx);
      } else {
        this.dispatchWild(idx, 'station');
      }
    },

    toggleIncomingExpand: function (idx) {
      var im = Core.state.world.incoming[idx];
      if (im) { im.expanded = !im.expanded; G.Core.render(); }
    },

    startIncomingBattle: function (idx) {
      var incoming = Core.state && Core.state.world && Core.state.world.incoming && Core.state.world.incoming[idx];
      if (!incoming || !incoming.marchId) {
        G.toast('该来袭记录不支持战术指挥');
        return;
      }
      G.Battle.openTactical(incoming.marchId);
    },

    declareWar: function (kind, idx) {
      var s = Core.state;
      var abandonNewbieProtection = this.hasNewbieProtection();
      var actorId = s.player && s.player.id, actorSlot = s.player && s.player.citySlot;
      var target;
      if (kind === 'player') target = s.world.playerCities[idx];
      if (!target) return;
      var targetShielded = (target.shieldUntil != null ? target.shieldUntil > G.gameNow() : target.cityState === 'shield') || (target.coolAt > G.gameNow());
      if (targetShielded) {
        G.toast((target.name || '目标城市') + '处于护盾期间，无法发起侦查或攻击');
        return;
      }

      var prepareSec = 2 * 3600;
      var warSec = 48 * 3600;
      var now = new Date();
      var attackTime = new Date(now.getTime() + prepareSec * 1000);
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
      var targetPlayer = target.playerName || target.ownerName || target.player || target.name;
      mask.innerHTML =
        '<div class="modal-card dw-confirm">' +
          '<div class="dw-title">' +
            '<span class="dw-icon" aria-hidden="true">⚔</span>' +
            '<div class="dw-title-copy"><div class="dw-title-heading">宣战确认</div>' +
              '<div class="dw-title-target">对玩家「' + esc(targetPlayer) + '」发起战争</div></div>' +
          '</div>' +
          '<div class="modal-body dw-body">' +
            // 敌城信息
            '<div class="dw-target">' +
              '<div class="dw-target-row">' +
                '<span class="dw-emoji">🏰</span>' +
                '<span class="dw-name">' + esc(target.name) + '</span>' +
                (targetPlayer && targetPlayer !== target.name ? '<span class="dw-owner" style="font-size:12px;color:var(--muted);margin-left:6px">(玩家: ' + esc(targetPlayer) + ')</span>' : '') +
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
                (abandonNewbieProtection ? '<li>确认宣战将立即永久退出账号所有城市的新手保护，无法恢复。</li>' : '') +
                '<li>备战期内双方都无法进攻,只能侦察</li>' +
                '<li>对方同时也可对你发起进攻,务必留守部队</li>' +
                '<li>48 小时交战期结束自动恢复和平</li>' +
              '</ul>' +
            '</div>' +
          '</div>' +
          '<div class="modal-foot dw-foot">' +
            '<button class="btn dw-btn-cancel" id="dwCancel">再想想</button>' +
            '<button class="btn dw-btn-ok" id="dwOk"><span>⚔</span>' + (abandonNewbieProtection ? '退出保护并宣战' : '确认宣战') + '</button>' +
          '</div>' +
        '</div>';
      document.body.appendChild(mask);

      var close = function () { if (mask.parentNode) mask.parentNode.removeChild(mask); };
      mask.querySelector('#dwCancel').onclick = close;
      mask.addEventListener('click', function (e) { if (e.target === mask) close(); });

      mask.querySelector('#dwOk').onclick = function () {
        close();
        if ((Core.state.player && Core.state.player.id) !== actorId || (Core.state.player && Core.state.player.citySlot) !== actorSlot) { G.toast('出发城市已改变，请重新操作'); return; }
        G.API.declareWar(target.id, abandonNewbieProtection).then(function () {
          if (G.WorldMap) G.WorldMap.invalidate();
          G.toast('宣战成功! ' + fmtLeft(prepareSec) + '后开打,持续 ' + fmtLeft(warSec));
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '宣战失败');
        });
      };
    },

    /** 更新战争倒计时，在备战开始或战争结束的边界重新计算列表行动。 */
    startWarTimer: function () {
      if (this._warTimer) return;
      var self = this;
      this._warTimer = setInterval(function () {
        var s = Core.state;
        if (!s || !s.world || Core.route !== 'world' || (G.WorldMap && G.WorldMap.isMap())) {
          clearInterval(self._warTimer);
          self._warTimer = null;
          return;
        }
        var now = G.gameNow();
        // 时间变化不会修改服务器记录，必须主动重绘才能移除过期的进攻按钮。
        if (self._warNextTransition && now >= self._warNextTransition) {
          Core.render();
          return;
        }
        var hasActive = false;
        for (var i = 0; i < s.world.playerCities.length; i++) {
          var p = s.world.playerCities[i];
          if (!p.warAt) continue;
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
            var startButton = document.getElementById('war-start-timer-' + i);
            if (startButton) {
              var startRemain = Math.max(0, p.warAt - now);
              startButton.textContent = '开战倒计时 ' + Math.floor(startRemain / 60000) + ':' + String(Math.floor((startRemain % 60000) / 1000)).padStart(2, '0');
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
      var total = 0;
      for (var id in army) {
        var u = D.units[id];
        if (u && u.load) total += army[id] * u.load * (G.UnitEquipment ? G.UnitEquipment.mul(G.UnitEquipment.snapshot(), id, 'load') : 1);
      }
      return Math.floor(total * (Core.loadMul ? Core.loadMul() : 1));
    },

    calcDispatchSpeed: function (army) {
      var slowestSpd = Infinity;
      var slowestUnitId = null;
      var slowestUnitName = '';
      var unitCount = 0;
      var totalTroops = 0;
      var unitsInfo = [];

      for (var uid in army) {
        var count = parseInt(army[uid], 10) || 0;
        if (count <= 0) continue;
        var u = D.units[uid];
        if (!u) continue;

        unitCount++;
        totalTroops += count;

        var baseSpd = u.spd || 1;
        var techMul = (typeof Core !== 'undefined' && Core.spdMul) ? Core.spdMul(u.cat, uid) : 1;
        var effSpd = baseSpd * techMul * (G.UnitEquipment ? G.UnitEquipment.speed(G.UnitEquipment.snapshot(), uid, false) : 1);

        unitsInfo.push({
          uid: uid,
          name: u.name,
          cat: u.cat,
          baseSpd: baseSpd,
          techMul: techMul,
          effSpd: effSpd,
          count: count
        });

        if (effSpd < slowestSpd) {
          slowestSpd = effSpd;
          slowestUnitId = uid;
          slowestUnitName = u.name;
        }
      }

      if (unitCount === 0) {
        return { slowestSpd: null, slowestUnitId: null, slowestUnitName: '', unitCount: 0, totalTroops: 0, units: [] };
      }

      return {
        slowestSpd: slowestSpd,
        slowestUnitId: slowestUnitId,
        slowestUnitName: slowestUnitName,
        unitCount: unitCount,
        totalTroops: totalTroops,
        units: unitsInfo
      };
    },

    calcDispatchMarchTime: function (distance, slowestSpd, speedMul) {
      if (!slowestSpd || slowestSpd <= 0 || distance == null || distance < 0) return null;
      var mul = speedMul || 1.0;
      var secPerGrid = (D.world && D.world.marchSecPerGrid) || 18;
      var sec = Math.max(1, Math.ceil((distance * secPerGrid) / (slowestSpd * mul)));
      return sec;
    },

    /**
     * 计算迎头截击敌方高空侦察机的相遇拦截点与行军耗时。
     * 根据敌机剩余到达时间、敌机航向与我方截击编队（空军/防空车）移速的相对运动迎面相遇方程求解。
     */
    calcInterceptionPlan: function (army, dt) {
      var s = Core.state;
      var cp = (s.world && (s.world.cityPos || s.world.pos)) || { x: 342, y: 681 };
      var inc = (dt && dt.interceptTarget) || (dt && dt.target) || {};
      var now = typeof G.gameNow === 'function' ? G.gameNow() : Date.now();

      var enemyFromX = inc.fromX != null ? inc.fromX : (dt && dt.targetX != null ? dt.targetX : (cp.x - 30));
      var enemyFromY = inc.fromY != null ? inc.fromY : (dt && dt.targetY != null ? dt.targetY : (cp.y - 30));
      var enemyDuration = inc.duration || (inc.arriveAt && inc.startAt ? (inc.arriveAt - inc.startAt) : 180000);
      var enemyArriveAt = inc.arriveAt || (now + 60000);
      var enemyStartAt = inc.startAt || (enemyArriveAt - enemyDuration);

      // 敌机航行进度 (0 ~ 0.95)
      var totalDur = Math.max(1000, enemyArriveAt - enemyStartAt);
      var progress = Math.max(0, Math.min(0.95, (now - enemyStartAt) / totalDur));
      var enemyCurX = enemyFromX + (cp.x - enemyFromX) * progress;
      var enemyCurY = enemyFromY + (cp.y - enemyFromY) * progress;

      // 敌机与我方城市的剩余直线距离与剩余秒数
      var remDist = Math.max(0.5, dist(enemyCurX, enemyCurY, cp.x, cp.y));
      var remSec = Math.max(1.0, (enemyArriveAt - now) / 1000.0);
      var vEnemy = Math.max(0.05, remDist / remSec); // 敌机速度（格/秒）

      // 我方拦截编队移速计算
      var speedRes = this.calcDispatchSpeed(army || {});
      var cs = s.cityState || {};
      var hasBoost = cs.marchBoostUntil && cs.marchBoostUntil > now;
      var speedMul = hasBoost ? 1.5 : 1.0;
      var secPerGrid = (D.world && D.world.marchSecPerGrid) || 18;
      var slowestSpd = speedRes && speedRes.slowestSpd ? speedRes.slowestSpd : 6.0;
      var vOur = Math.max(0.05, (slowestSpd * speedMul) / secPerGrid); // 我方截击航速（格/秒）

      // 相向迎面相遇方程：t_meet = remDist / (vOur + vEnemy)
      var tMeet = remDist / (vOur + vEnemy);
      var sec = Math.max(3, Math.min(Math.floor(remSec), Math.ceil(tMeet)));
      var interceptDist = Math.max(1.0, Math.min(remDist * 0.95, vOur * sec));

      // 截击空域坐标
      var interceptX = Math.round(cp.x + (enemyCurX - cp.x) * (interceptDist / remDist));
      var interceptY = Math.round(cp.y + (enemyCurY - cp.y) * (interceptDist / remDist));

      // 计算防空战力与成功率
      var totalAntiAir = 0;
      for (var uid in (army || {})) {
        var count = army[uid] || 0;
        if (count > 0 && D.units[uid]) {
          totalAntiAir += (D.units[uid].atkAir || 0) * count;
        }
      }

      return {
        targetX: interceptX,
        targetY: interceptY,
        distance: interceptDist,
        seconds: sec,
        roundSeconds: sec * 2,
        durationMs: sec * 1000,
        vOur: vOur,
        vEnemy: vEnemy,
        totalAntiAir: totalAntiAir,
        slowestSpd: slowestSpd
      };
    },

    /**
     * 计算出征所需的石油。
     * 保留每 100 格的基础油耗口径，最终扣油为原规则向上取整后的 10 倍。
     * 出征不再受粮食限制，只有按路线距离和预扣趟数计算的油耗会影响能否出发。
     * @param {Object} army - 按兵种键聚合的出征数量。
     * @param {number} distance - 单程直线距离，以地图格边长为单位，保留小数。
     * @param {number} legs - 需要预扣的行军趟数。
     * @returns {number} 预扣的石油总量。
     */
    calcDispatchFuel: function (army, distance, legs) {
      var oil = 0;
      var routeDistance = Math.max(0, Number(distance) || 0);
      var routeLegs = Math.max(1, Number(legs) || 1);
      for (var uid in army || {}) {
        var count = Math.max(0, Number(army[uid]) || 0);
        var unit = D.units[uid];
        if (!unit || count <= 0) continue;
        oil += count * (Number(unit.marchOil) || 0) * (G.UnitEquipment ? G.UnitEquipment.mul(G.UnitEquipment.snapshot(), uid, 'oil') : 1) * routeDistance / 100;
      }
      // 先汇总全部趟数并向上取整，再乘 10，与后端实际扣油保持一致。
      return Math.ceil(oil * routeLegs) * 10;
    },

    fmtDuration: function (sec) {
      if (sec == null) return '--';
      var s = Math.max(0, Math.round(sec));
      if (s < 60) return s + ' 秒';
      var m = Math.floor(s / 60);
      var remS = s % 60;
      if (m < 60) {
        return remS > 0 ? (m + ' 分 ' + remS + ' 秒') : (m + ' 分钟');
      }
      var h = Math.floor(m / 60);
      var remM = m % 60;
      return h + ' 小时 ' + (remM > 0 ? (remM + ' 分 ') : '') + (remS > 0 ? (remS + ' 秒') : '');
    },

    onDispatchSliderChange: function (uid, val) {
      var num = parseInt(val, 10);
      if (isNaN(num)) num = 0;
      var inputEl = document.getElementById('dqty_' + uid);
      if (inputEl) inputEl.value = num;

      var sliderEl = document.getElementById('dslider_' + uid);
      if (sliderEl) {
        var max = parseInt(sliderEl.max, 10) || 0;
        sliderEl.value = num;
        var pct = max > 0 ? Math.min(100, Math.max(0, (num / max) * 100)) : 0;
        sliderEl.style.setProperty('--p', pct.toFixed(1) + '%');
      }
      this.updateDispatchStats();
      if (typeof this._refreshDispatchRoute === 'function') this._refreshDispatchRoute();
    },

    /**
     * 将当前出征界面展示的所有兵种设置为 1 个单位。
     * 只处理有库存且当前可出征的兵种，侦查任务仅设置选中方式的部队。
     * @returns {void}
     */
    setAllDispatchUnitsToOne: function () {
      for (var uid in D.units) {
        var inputEl = document.getElementById('dqty_' + uid);
        if (!inputEl) continue;
        var max = parseInt(inputEl.max, 10) || 0;
        if (max > 0) this.onDispatchSliderChange(uid, 1);
      }
      this.updateDispatchStats();
      if (typeof this._refreshDispatchRoute === 'function') this._refreshDispatchRoute();
    },

    /**
     * 将当前出征界面展示的所有兵种重置为 0 个单位。
     * @returns {void}
     */
    resetDispatchUnits: function () {
      for (var uid in D.units) {
        var inputEl = document.getElementById('dqty_' + uid);
        if (!inputEl) continue;
        var max = parseInt(inputEl.max, 10) || 0;
        if (max > 0) this.onDispatchSliderChange(uid, 0);
      }
      this.updateDispatchStats();
      if (typeof this._refreshDispatchRoute === 'function') this._refreshDispatchRoute();
    },

    onDispatchInputChange: function (uid, val) {
      var sliderEl = document.getElementById('dslider_' + uid);
      if (!sliderEl) return;
      var max = parseInt(sliderEl.max, 10) || 0;
      if (val === '') {
        sliderEl.value = 0;
        sliderEl.style.setProperty('--p', '0%');
        this.updateDispatchStats();
        if (typeof this._refreshDispatchRoute === 'function') this._refreshDispatchRoute();
        return;
      }
      var num = parseInt(val, 10);
      if (isNaN(num)) num = 0;
      var clamped = Math.min(Math.max(0, num), max);
      sliderEl.value = clamped;
      var pct = max > 0 ? Math.min(100, Math.max(0, (clamped / max) * 100)) : 0;
      sliderEl.style.setProperty('--p', pct.toFixed(1) + '%');
      this.updateDispatchStats();
      if (typeof this._refreshDispatchRoute === 'function') this._refreshDispatchRoute();
    },

    /**
     * 刷新出征编队的兵力、负重、行军时间与补给预估。
     * @returns {void}
     */
    updateDispatchStats: function () {
      if (typeof document === 'undefined' || !document.getElementById) return;
      var s = Core.state;
      var dt = s && s.world && s.world._dispatchTarget;
      var currentArmy = {};
      var selectedTroops = 0;
      for (var k in D.units) {
        var input = document.getElementById('dqty_' + k);
        if (input) {
          var n = parseInt(input.value, 10) || 0;
          if (n > 0) {
            currentArmy[k] = n;
            selectedTroops += n;
          }
        }
      }

      var armyCap = typeof Core.armyCap === 'function' ? Math.max(0, Number(Core.armyCap()) || 0) : 0;
      var troopCountEl = document.getElementById('estDispatchTroops');
      if (troopCountEl) {
        troopCountEl.textContent = G.fmt(selectedTroops) + ' / ' + G.fmt(armyCap);
        troopCountEl.style.color = selectedTroops > armyCap ? 'var(--danger, #c53030)' : '';
      }

      var maxMarches = typeof Core.maxMarches === 'function' ? Core.maxMarches() : (4 + ((s.buildings && s.buildings.headquarters) || 0));
      var activeMarches = (s.world && s.world.marches) || (Core.state && Core.state.world && Core.state.world.marches) || [];
      var queueCountEl = document.getElementById('estDispatchQueues');
      if (queueCountEl) {
        queueCountEl.textContent = activeMarches.length + ' / ' + maxMarches;
        queueCountEl.style.color = activeMarches.length >= maxMarches ? 'var(--danger, #c53030)' : '';
      }

      // 1. 更新负重
      var estLoadEl = document.getElementById('estLoad');
      if (estLoadEl) {
        estLoadEl.textContent = G.fmt(this.calcDispatchLoad(currentArmy));
      }

      // 联网拦截使用服务端部署计划，客户端估算仅供离线演示。
      if (dt && dt.action === 'intercept_scout') {
        var online = G.API && G.API.client && typeof G.API.client.post === 'function';
        var plan = online ? this._currentRoute : this.calcInterceptionPlan(currentArmy, dt);
        dt.calculatedPlan = plan;
        var speed = this.calcDispatchSpeed(currentArmy);
        var power = Object.keys(currentArmy).reduce(function (sum, uid) { return sum + (D.units[uid].atkAir || 0) * currentArmy[uid]; }, 0);
        var values = {
          estInterceptPower: G.fmt(Math.round(power)), estInterceptRate: selectedTroops ? '以实际交战结果为准' : '--',
          estMarchDist: plan ? Number(plan.distance).toFixed(1) + ' 格 [交火点 ' + plan.targetX + ',' + plan.targetY + ']' : '--',
          estMarchSpeed: speed.slowestSpd ? speed.slowestSpd.toFixed(1) + ' 格/单位' : '--',
          estMarchTimeOneWay: plan ? this.fmtDuration(plan.seconds) + ' (部署到达)' : (selectedTroops ? '等待服务端计算' : '请选择拦截部队'),
          estMarchTimeRound: plan && plan.clashAt ? this.fmtDuration(Math.max(0, Math.ceil((plan.clashAt - G.gameNow()) / 1000))) + ' (预计交火)' : '--',
          estSupplyOil: plan ? G.fmt(plan.oil != null ? plan.oil : this.calcDispatchFuel(currentArmy, plan.distance, 2)) : '--',
          estSupplyLeg: '预扣往返截击油耗',
          estMarchSpeedTip: plan ? (online ? (plan.opening === 'ambush'
            ? '伏击拦截：预计提前到位，准备 5 秒后伏击待发。首次交火我方优先，敌军存活部队同回合可还击。'
            : '紧急迎击：本次按常规顺序交战；部署时间不足或该来袭行军已承受过伏击先手。') : '离线估算 · 正式部署以服务端为准')
            : (this._dispatchRouteError || '选择兵力后计算部署时间与交火时间')
        };
        Object.keys(values).forEach(function (id) { var el = document.getElementById(id); if (el) el.textContent = values[id]; });
        return;
      }

      // 2. 行军情报卡片
      var statsCard = document.getElementById('dispatchStatsCard');
      if (!statsCard) return;

      var speedRes = this.calcDispatchSpeed(currentArmy);

      // 目标与距离计算
      var cp = (s.world && (s.world.cityPos || s.world.pos)) || { x: 0, y: 0 };
      var targetX = dt && dt.target ? dt.target.x : 0;
      var targetY = dt && dt.target ? dt.target.y : 0;
      var marchDist = (this._currentRoute && this._currentRoute.distance != null)
        ? this._currentRoute.distance
        : dist(cp.x, cp.y, targetX, targetY);

      // 行军加速状态
      var now = G.gameNow();
      var cs = s.cityState || {};
      var hasBoost = cs.marchBoostUntil && cs.marchBoostUntil > now;
      var speedMul = hasBoost ? 1.5 : 1.0;

      var distEl = document.getElementById('estMarchDist');
      var speedEl = document.getElementById('estMarchSpeed');
      var timeOneWayEl = document.getElementById('estMarchTimeOneWay');
      var timeRoundEl = document.getElementById('estMarchTimeRound');
      var speedTipEl = document.getElementById('estMarchSpeedTip');
      var boostTagEl = document.getElementById('estMarchBoostTag');
      var supplyOilEl = document.getElementById('estSupplyOil');
      var supplyLegEl = document.getElementById('estSupplyLeg');

      if (distEl) distEl.textContent = distanceText(marchDist) + ' 格';

      if (boostTagEl) {
        boostTagEl.style.display = hasBoost ? 'inline-block' : 'none';
      }

      if (!speedRes.slowestSpd) {
        if (speedEl) speedEl.textContent = '--';
        if (timeOneWayEl) timeOneWayEl.textContent = '请选择出征部队';
        if (timeRoundEl) timeRoundEl.textContent = '--';
        if (supplyOilEl) supplyOilEl.textContent = '--';
        if (supplyLegEl) supplyLegEl.textContent = '请选择部队后计算补给';
        if (speedTipEl) speedTipEl.textContent = '未选择出征兵力，暂无法计算行军时间与全军移速';
        return;
      }

      var sec = this.calcDispatchMarchTime(marchDist, speedRes.slowestSpd, speedMul);
      var roundSec = sec * 2;
      var isStation = dt && dt.action === 'station';
      var supplyLegs = isStation ? 1 : 2;
      var fuel = this.calcDispatchFuel(currentArmy, marchDist, supplyLegs);

      var effSpdStr = speedRes.slowestSpd.toFixed(1);
      if (effSpdStr.endsWith('.0')) effSpdStr = effSpdStr.slice(0, -2);
      if (speedEl) {
        speedEl.textContent = effSpdStr + ' 格/单位';
      }
      if (timeOneWayEl) {
        timeOneWayEl.textContent = this.fmtDuration(sec);
      }
      if (timeRoundEl) {
        timeRoundEl.textContent = isStation ? '驻防不返程' : this.fmtDuration(roundSec);
      }
      if (supplyOilEl) supplyOilEl.textContent = G.fmt(fuel);
      if (supplyLegEl) supplyLegEl.textContent = isStation ? '预扣单程补给（进驻）' : '预扣往返补给（返程已备）';

      if (speedTipEl) {
        var slowestUnit = speedRes.units.find(function(u) { return u.uid === speedRes.slowestUnitId; });
        var techBonus = slowestUnit ? Math.round((slowestUnit.techMul - 1) * 100) : 0;
        var techStr = techBonus > 0 ? (' 含科技+' + techBonus + '%') : '';

        var tip = '';
        if (speedRes.unitCount > 1) {
          tip = '协同行军：受限于最慢兵种【' + speedRes.slowestUnitName + '】(基速 ' + (slowestUnit ? slowestUnit.baseSpd : '') + (techStr ? ' ·' + techStr : '') + ')，全军保持统一速度出发与到达';
        } else {
          tip = '单一兵种【' + speedRes.slowestUnitName + '】全速推进 (基速 ' + (slowestUnit ? slowestUnit.baseSpd : '') + (techStr ? ' ·' + techStr : '') + ')';
        }
        if (hasBoost) {
          tip += ' · ⚡行军加速生效中 (+50%)';
        }
        speedTipEl.textContent = tip;
      }
    },

    updateEstLoad: function () {
      this.updateDispatchStats();
    },

    cancelDispatch: function () {
      var target = Core.state.world._dispatchTarget;
      var isInterception = target && target.action === 'intercept_scout';
      if (this._disposeInterceptionPreview) this._disposeInterceptionPreview();
      delete Core.state.world._dispatchTarget;
      if (isInterception) {
        G.go(target.returnToMap ? 'world' : 'alerts');
      } else {
        G.go('world');
      }
    },

    /**
     * 校验当前编队并提交出征请求。
     * @returns {void}
     */
    launchDispatch: function () {
      var s = Core.state;
      var dt = s.world._dispatchTarget;
      if (!dt) { G.go('world'); return; }
      if (dt.kind === 'npc') { G.toast('NPC城市/要塞已取消'); G.go('world'); return; }
      var activeMarches = (s.world && s.world.marches) || (Core.state && Core.state.world && Core.state.world.marches) || [];
      var maxMarches = typeof Core.maxMarches === 'function' ? Core.maxMarches() : (4 + ((s.buildings && s.buildings.headquarters) || 0));
      if (activeMarches.length >= maxMarches) {
        G.toast('当前城池出征队伍已达上限 (' + activeMarches.length + '/' + maxMarches + ')，请等待部队返城或升级司令部');
        return;
      }

      // 侦查拦截只允许空军和装甲车；普通敌袭拦截开放全部兵种，与行军是否来自旧记录无关。
      if (dt.action === 'intercept_scout') {
        var scoutInterception = this.isScoutInterception(dt);
        var interceptArmy = {};
        var hasUnits = false;
        var totalAntiAir = 0;
        for (var uid in D.units) {
          var el = document.getElementById('dqty_' + uid);
          if (!el) continue;
          var n = parseInt(el.value, 10) || 0;
          if (n > 0) {
            var udef = D.units[uid];
            if (scoutInterception && !this.isInterceptUnit(uid, udef)) {
              G.toast('侦查拦截仅支持配置空军或装甲车');
              return;
            }
            interceptArmy[uid] = n;
            hasUnits = true;
            totalAntiAir += (udef.atkAir || 0) * n;
          }
        }
        if (!hasUnits) {
          G.toast(scoutInterception ? '请选配至少 1 架空军或 1 辆装甲车出城拦截' : '请至少选择一种兵种出城拦截');
          return;
        }

        var selectedTroops = Object.values(interceptArmy).reduce(function (total, count) { return total + count; }, 0);
        var hasArmyCap = typeof Core.armyCap === 'function';
        var armyCap = hasArmyCap ? Math.max(0, Number(Core.armyCap()) || 0) : 0;
        if (hasArmyCap && selectedTroops > armyCap) {
          G.toast('出征兵力超过带兵上限 ' + (typeof G.fmt === 'function' ? G.fmt(armyCap) : armyCap));
          return;
        }

        var commEl = document.querySelector('input[name="dpOfficer"]:checked');
        var commanderId = null;
        if (commEl && commEl.value && commEl.value !== 'none') {
          commanderId = parseInt(commEl.value, 10);
          if (isNaN(commanderId)) commanderId = null;
        }

        var onlinePreview = G.API && G.API.client && typeof G.API.client.post === 'function';
        var previewRequest = this.interceptionPreviewRequest(interceptArmy, dt);
        if (onlinePreview && (this._dispatchRoutePending || this._dispatchRouteError || !this._currentRoute
            || this._interceptionPreviewKey !== JSON.stringify(previewRequest)
            || G.gameNow() - Number(this._currentRoute.plannedAt || 0) >= 5000)) {
          G.toast(this._dispatchRouteError || '部署计划正在更新，请稍候再出城拦截');
          if (this._refreshDispatchRoute && !this._dispatchRoutePending) this._refreshDispatchRoute(true);
          return;
        }
        var plan = onlinePreview ? this._currentRoute : this.calcInterceptionPlan(interceptArmy, dt);
        var cp = (s.world && (s.world.cityPos || s.world.pos)) || { x: 342, y: 681 };
        var now = typeof G.gameNow === 'function' ? G.gameNow() : Date.now();

        var dispatchRequest = {
          targetKind: dt.marchId != null ? 'incoming_march' : 'incoming_scout',
          targetId: dt.marchId != null ? dt.marchId : dt.incomingId || (dt.target && dt.target.id),
          action: 'intercept_scout',
          army: interceptArmy,
          commanderId: commanderId,
          unitEquipmentVersion: plan.unitEquipmentVersion,
          incomingIdx: dt.incomingIdx,
          marchId: dt.marchId,
          targetX: plan.targetX,
          targetY: plan.targetY
        };

        var self = this;
        var apiPromise = (G.API && G.API.worldDispatch) ? G.API.worldDispatch(dispatchRequest) : Promise.resolve();
        return apiPromise.then(function (res) {
          // 正式接口已应用完整权威状态，不能再次扣兵扣油或追加一条本地截击行军。
          if (G.API && G.API.worldDispatch) {
            if (Core.state.world) delete Core.state.world._dispatchTarget;
            if (self._disposeInterceptionPreview) self._disposeInterceptionPreview();
            G.toast('截击部队已出发，部署到位后等待敌军进入交火点；交战后发送双方拦截战报');
            if (G.WorldMap && G.WorldMap.invalidate) G.WorldMap.invalidate();
            G.go('world');
            return;
          }
          if (Core.state.world._dispatchTarget === dt) delete Core.state.world._dispatchTarget;
          delete s.world._dispatchTarget;

          var fuel = self.calcDispatchFuel(interceptArmy, plan.distance, 2);
          if (s.resources && s.resources.oil != null && fuel > 0) {
            s.resources.oil = Math.max(0, s.resources.oil - fuel);
          }

          var baseArmy = s.army || (s.barracks && s.barracks.army);
          if (baseArmy) {
            for (var buid in interceptArmy) {
              baseArmy[buid] = Math.max(0, (baseArmy[buid] || 0) - interceptArmy[buid]);
            }
          }

          var marchId = (res && res.id) || ('intercept_' + Date.now());
          if (!s.world.marches) s.world.marches = [];
          var existingMarch = s.world.marches.find(function (m) { return String(m.id) === String(marchId); });
          if (!existingMarch) {
            var marchRecord = {
              id: marchId,
              targetKind: dt.marchId != null ? 'incoming_march' : 'incoming_scout',
              targetId: dt.marchId || dt.incomingId || (dt.target && dt.target.id) || 1,
              targetName: (scoutInterception ? '拦截敌方侦察机 [' : '拦截敌军行军 [') + plan.targetX + ',' + plan.targetY + ']',
              fromX: cp.x,
              fromY: cp.y,
              originX: cp.x,
              originY: cp.y,
              targetX: plan.targetX,
              targetY: plan.targetY,
              distance: plan.distance,
              startAt: now,
              arriveAt: now + plan.durationMs,
              duration: plan.durationMs,
              baseDurationMs: plan.durationMs,
              army: interceptArmy,
              action: 'intercept_scout',
              commanderId: commanderId,
              returning: false,
              version: 1
            };
            s.world.marches.push(marchRecord);
          }

          G.toast('截击部队已出发，前往 [' + plan.targetX + ',' + plan.targetY + '] 拦截敌军行军！');
          if (G.WorldMap && G.WorldMap.invalidate) G.WorldMap.invalidate();
          G.go('world');
        }).catch(function (err) {
          G.toast((err && err.message) || '出城拦截派遣失败');
        });
      }
      var dispatchAction = this.resolveDispatchAction(dt);
      if (dt.action === 'campaign' && (this._dispatchRoutePending || this._dispatchRouteError)) {
        // 路线拒绝需要说明跨海、运力或港口等具体原因，避免提示玩家反复重试。
        var routeError = typeof this._dispatchRouteError === 'string' ? this._dispatchRouteError : '行军路线校验失败，请重新选择部队后重试';
        G.toast(this._dispatchRoutePending ? '行军路线正在更新，请稍候再出征' : routeError);
        return;
      }
      var target;
      if (dt.kind === 'bandit') target = dt.target || s.world.bandits[dt.idx];
      else if (dt.kind === 'simulated_npc') target = dt.target || s.world.simulatedNpcCities[dt.idx];
      else if (dt.kind === 'player') target = dt.target || s.world.playerCities[dt.idx];
      else if (dt.kind === 'wild' || dt.kind === 'wild_gather') {
        var wt = dt.target || s.world.wildTiles[dt.idx];
        if (wt) {
          var wtd = G.DATA.wildTypes[wt.type] || { name: '野地' };
          target = { id: wt.id, name: wtd.name + ' Lv.' + wt.level, x: wt.x, y: wt.y, ownerId: wt.ownerId };
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
      if (dt.action === 'scout') {
        var reconUnits = Object.keys(customArmy);
        if (reconUnits.length !== 1 || !['scout', 'recon_car'].includes(reconUnits[0])) {
          G.toast('每次侦查只能派遣一种侦查部队'); return;
        }
        if (reconUnits[0] === 'recon_car' && dt.groundRecon && !dt.groundRecon.available) {
          G.toast(dt.groundRecon.pending ? '正在确认地面侦查路线，请稍候' : dt.groundRecon.error); return;
        }
      }

      var selectedTroops = Object.values(customArmy).reduce(function (total, count) { return total + count; }, 0);
      var hasArmyCap = typeof Core.armyCap === 'function';
      var armyCap = hasArmyCap ? Math.max(0, Number(Core.armyCap()) || 0) : 0;
      var format = typeof G.fmt === 'function' ? G.fmt : String;
      // 前端提前反馈，后端仍会在扣除资源与兵力前复核，防止绕过界面请求。
      if (hasArmyCap && selectedTroops > armyCap) {
        G.toast('出征兵力超过带兵上限 ' + format(armyCap) + '（当前选择 ' + format(selectedTroops) + '）');
        return;
      }

      var commEl = document.querySelector('input[name="dpOfficer"]:checked');
      var commanderId = null;
      if (commEl && commEl.value && commEl.value !== 'none') {
        commanderId = parseInt(commEl.value, 10);
        if (isNaN(commanderId)) commanderId = null;
      }
      if (commanderId != null) {
        var s = Core.state;
        var busyOfficerIds = new Set();
        var marchesList = (s.world && s.world.marches) || (Core.state && Core.state.world && Core.state.world.marches) || [];
        marchesList.forEach(function (m) {
          if (m.commanderId != null) {
            busyOfficerIds.add(Number(m.commanderId));
            busyOfficerIds.add(String(m.commanderId));
          }
        });
        var targetOfficer = (s.officers || []).find(function (o) { return Number(o.id) === commanderId; });
        if (targetOfficer && (targetOfficer.role === 'march' || targetOfficer.role === 'mayor' || targetOfficer.role === 'commander' || targetOfficer.role === 'commander_in_chief' || busyOfficerIds.has(commanderId) || busyOfficerIds.has(String(commanderId)))) {
          G.toast('该军官正在执行行军任务或已任要职，无法出征');
          return;
        }
      }

      var carryRes = {};
      var resKeys = G.Constants.resourceKeys;
      for (var i = 0; i < resKeys.length; i++) {
        var rk = resKeys[i];
        carryRes[rk] = 0;
        var rel = document.getElementById('dcarry_' + rk);
        if (rel) carryRes[rk] = parseInt(rel.value, 10) || 0;
      }

      var dispatchRequest = {
        unitEquipmentVersion: this._currentRoute && this._currentRoute.unitEquipmentVersion,
        targetKind: dt.kind,
        targetId: target.id,
        action: dispatchAction,
        army: customArmy,
        commanderId: commanderId,
        carryRes: carryRes
      };
      if (dt.kind === 'wild_gather') {
        var gatherModeEl = document.querySelector('input[name="dpGatherMode"]:checked');
        dispatchRequest.gatherMode = gatherModeEl ? gatherModeEl.value : (dt.gatherMode || 'auto');
      }
      if (!['player', 'wild_gather'].includes(dt.kind) && dispatchRequest.action !== 'scout' && dispatchRequest.action !== 'station') {
        var battleModeEl = document.querySelector('input[name="dpBattleMode"]:checked');
        dispatchRequest.battleMode = battleModeEl ? battleModeEl.value : (dt.battleMode || (dt.kind === 'wild' ? 'auto' : 'manual'));
      }
      if (dt.action === 'campaign') {
        // 关闭占领时不能提交草稿中暂存的野地驻守值。
        dispatchRequest.conquerMode = dt.kind === 'wild' && dispatchAction === 'conquer' && dt.conquerMode === 'occupy' ? 'occupy' : 'withdraw';
      }

      var playerWarfare = (dt.kind === 'player' && !target.selfCity && dispatchAction !== 'transport')
        || (dt.kind === 'wild' && target.ownerId != null && String(target.ownerId) !== String((s.player || {}).id));
      this.confirmPlayerWarfare(playerWarfare, function (confirmed) {
        if (Core.state.world._dispatchTarget !== dt) return;
        if(G.World._launching)return;G.World._launching=true;
        if (confirmed) dispatchRequest.abandonNewbieProtection = true;
        G.API.worldDispatch(dispatchRequest).then(function () {
          // API 可能已整体替换状态并保留同一草稿引用，两个状态对象都要清除。
          if (Core.state.world._dispatchTarget === dt) delete Core.state.world._dispatchTarget;
          delete s.world._dispatchTarget;
          G.toast('部队出征');
          // 触发每日任务进度
          var act = dispatchRequest.action;
          // 侦查任务在侦查部队抵达并生成报告后，由 WebSocket 事件计数，不能在出发时完成。
          if (act === 'gather' && G.Task && G.Task.Quests) G.Task.Quests.onEvent('gather');
          else if ((act === 'conquer' || act === 'plunder' || act === 'attack' || act === 'occupy') && G.Task && G.Task.Quests) G.Task.Quests.onEvent('attack');
          G.go('world');
        }).catch(function (err) {
          G.toast(err.message || '出征失败');
        }).finally(function(){G.World._launching=false;});
      });
    },

    /** 记住玩家的采集模式，任务推荐也允许自由选择。 */
    setGatherMode: function (mode) {
      var target = Core.state.world._dispatchTarget;
      if (target && target.kind === 'wild_gather') target.gatherMode = mode === 'manual' ? 'manual' : 'auto';
    },

    /** 保留当前出征的 PvE 战斗方式，界面重新渲染后仍展示玩家选择。 */
    setBattleMode: function (mode) {
      var target = Core.state.world._dispatchTarget;
      if (target) target.battleMode = mode === 'auto' ? 'auto' : 'manual';
    },

    /** 部队去向独立于占领开关，关闭时暂存选择但不让它参与提交。 */
    setConquerMode: function (mode) {
      var target = Core.state.world._dispatchTarget;
      if (target && target.kind === 'wild' && target.action === 'campaign' && !this._launching) {
        target.conquerMode = mode === 'occupy' ? 'occupy' : 'withdraw';
        this.updateCampaignControls();
      }
    },

    /** 全手动终止仅保留当前收获，部队继续在野地等待独立的回城命令。 */
    stopGatherMarch: function (marchId) {
      return G.API.stopGatherMarch(marchId).then(function () {
        G.toast('已终止采集，部队原地待命，请下达回城命令');
        if (G.WorldMap) G.WorldMap.invalidate();
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '终止采集失败');
      });
    },

    returnGatherMarch: function (marchId) {
      return G.API.cancelMarch(marchId).then(function () {
        G.toast('部队已按原路线返城，抵达后资源入库');
        if (G.WorldMap) G.WorldMap.invalidate();
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '下达回城命令失败');
      });
    },

    gatherMarchStatus: function (march) {
      var modeName = march.gatherMode === 'manual' ? '采集全手动' : '采集全自动';
      if (march.returning) return modeName + ' · 正在返城';
      if (march.gatherStopped) return modeName + ' · 已终止采集，等待回城命令';
      if (march.gathering) {
        if (G.gameNow() >= march.gatherEndAt) return modeName + (march.gatherMode === 'manual'
          ? ' · 已采满，等待手动终止' : ' · 采集完成，等待返城');
        return modeName + ' · 采集中，距采满 ' + this.fmtMarchTime(march.gatherEndAt);
      }
      return modeName + ' · 前往野地 ' + this.fmtMarchTime(march.arriveAt);
    },

    gatherMarchActions: function (march, className) {
      if (march.gatherMode !== 'manual' || march.returning) return '';
      var handler = march.gatherStopped ? 'returnGatherMarch' : march.gathering ? 'stopGatherMarch' : null;
      if (!handler) return '';
      return '<button type="button" class="' + esc(className || 'btn ok sm') + '" onclick="Game.World.' + handler + '(' + Number(march.id) + ')">' +
        (march.gatherStopped ? '部队回城' : '终止采集') + '</button>';
    },

    renderGatherMarches: function (tileId, className) {
      var self = this;
      return (Core.state.world.marches || []).filter(function (march) {
        return march.targetKind === 'wild_gather' && String(march.targetId) === String(tileId) && !march.returning;
      }).map(function (march) {
        return '<div class="wild-gather-march"><div data-gather-march-id="' + Number(march.id) + '">' + esc(self.gatherMarchStatus(march)) + '</div>' +
          '<div class="d">部队 #' + Number(march.id) + '：' + esc(armyText(march.army)) + '</div>' +
          self.gatherMarchActions(march, className) + '</div>';
      }).join('');
    },

    cancelMarch: function (marchId) {
      G.API.cancelMarch(marchId).then(function (result) {
        G.toast(result && result.message || '部队已撤回，正在返城，抵达后归还部队和携带资源');
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '取消行军失败');
      });
    },

    dismissSupport: function (marchId) {
      G.API.client.post('/game/world/support/dismiss', { marchId: marchId }).then(function (data) {
        G.API.applyState(data.state); Core.render(); G.toast('援军已开始返城');
      }).catch(function (err) { G.toast(err.message || '遣返失败'); });
    },

    /** 返城的采集任务仍属于移动阶段，不能被旧采集标记拦住。 */
    marchInTransit: function (march, now) {
      return !!march && !march.stationed && !march.inBattle && !march.battleId && !march.waitingForBattle &&
        (march.returning || (!march.gathering && !march.gatherStopped)) &&
        march.startAt != null && (!march.returning && march.deployAt != null ? march.deployAt : march.arriveAt) != null &&
        Number(march.startAt) <= now && Number((!march.returning && march.deployAt != null ? march.deployAt : march.arriveAt)) > now;
    },

    /** 加速入口只接受带服务端版本的可移动快照。 */
    canAccelerateMarch: function (march, now) {
      return this.marchInTransit(march, now) && Number(!march.returning && march.deployAt != null ? march.deployAt : march.arriveAt) - now > G.Constants.marchAcceleration.minRemainingMs &&
        Number.isSafeInteger(march.version) && !(march.returning ? march.returnAccelerated : march.outboundAccelerated);
    },

    /** 以秒向上取整展示预估移动时间，与抵达倒计时一致。 */
    marchTimeText: function (milliseconds) {
      var seconds = Math.max(0, Math.ceil(milliseconds / 1000));
      var minutes = Math.floor(seconds / 60);
      return minutes ? minutes + '分' + (seconds % 60 ? seconds % 60 + '秒' : '') : seconds + '秒';
    },

    /** 确认本队加速的消耗和收益，阶段变化后必须关闭弹窗重新确认。 */
    speedUpMarch: function (marchId) {
      var self = this;
      var state = Core.state;
      var march = (state.world.marches || []).find(function (m) { return String(m.id) === String(marchId); });
      if (!this.canAccelerateMarch(march, G.gameNow())) { G.toast('该行军当前无法加速，请刷新军情'); return; }
      if (this._marchSpeedUpClose && this._marchSpeedUpClose() === false) return;
      var returning = !!march.returning, version = march.version;
      var player = state.player || {};
      var identity = String(player.id) + ':' + String(player.activeCityId);
      var token = G.API.getToken ? G.API.getToken() : null;
      var route = Core.route;
      var pending = false, failed = false, closed = false, timer = null;
      var title = returning ? '加速回城' : '加速行军';
      var mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.setAttribute('role', 'dialog');
      mask.setAttribute('aria-modal', 'true');
      mask.setAttribute('aria-labelledby', 'marchSpeedUpTitle');
      mask.innerHTML = '<div class="modal-card march-speedup-dialog">' +
        '<div class="modal-title" id="marchSpeedUpTitle">' + title + '</div><div class="modal-body">' +
        '<p class="march-speedup-target">目的地：' + esc(march.targetName || '目标') + ' (' + esc(march.targetX) + ',' + esc(march.targetY) + ')</p>' +
        '<p>剩余时间：<b data-march-before></b> &rarr; <b data-march-after></b></p>' +
        '<p>预计节省：<b data-march-saved></b></p><p data-march-stock></p>' +
        '<p class="desc">仅加速本队当前路段，去程与回程各限一次。</p>' +
        '<p class="march-speedup-status" data-march-status role="status" aria-live="polite"></p></div>' +
        '<div class="modal-foot"><button type="button" class="btn" data-march-cancel>取消</button>' +
        '<button type="button" class="btn ok" data-march-confirm>确认加速</button></div></div>';
      document.body.appendChild(mask);
      var cancel = mask.querySelector('[data-march-cancel]'), ok = mask.querySelector('[data-march-confirm]');
      var status = mask.querySelector('[data-march-status]');
      var previousFocus = document.activeElement;
      function dispose() {
        closed = true;
        if (timer != null) clearInterval(timer);
        if (mask.parentNode) mask.parentNode.removeChild(mask);
        if (self._marchSpeedUpClose === close) self._marchSpeedUpClose = null;
        if (previousFocus && previousFocus.isConnected && previousFocus.focus) previousFocus.focus();
      }
      function close() {
        if (pending) return false;
        dispose();
        return true;
      }
      this._marchSpeedUpClose = close;
      function sameContext() {
        var currentPlayer = (Core.state || {}).player || {};
        return identity === String(currentPlayer.id) + ':' + String(currentPlayer.activeCityId) &&
          (!G.API.getToken || token === G.API.getToken());
      }
      function update() {
        // 切换城市、账号或离开情报后移除旧确认；已发送的请求仍由服务端完成。
        if (!sameContext() || Core.route !== route) { dispose(); return { valid: false, stock: 0 }; }
        if (closed || failed) return { valid: false, stock: 0 };
        if (pending) return;
        var current = Core.state || {};
        var live = ((current.world || {}).marches || []).find(function (m) { return String(m.id) === String(marchId); });
        var valid = live && !!live.returning === returning && live.version === version && self.canAccelerateMarch(live, G.gameNow());
        var stock = Math.max(0, Number((current.items || {}).marchOrd) || 0);
        var remaining = Math.max(0, Number(!march.returning && march.deployAt != null ? march.deployAt : march.arriveAt) - G.gameNow());
        var after = Math.min(remaining, Math.max(G.Constants.marchAcceleration.minRemainingMs,
          Math.ceil(remaining / G.Constants.marchAcceleration.speedMultiplier)));
        mask.querySelector('[data-march-before]').textContent = self.marchTimeText(remaining);
        mask.querySelector('[data-march-after]').textContent = self.marchTimeText(after);
        mask.querySelector('[data-march-saved]').textContent = self.marchTimeText(remaining - after);
        mask.querySelector('[data-march-stock]').textContent = '行军令：消耗 1 枚，库存 ' + stock + ' 枚';
        ok.disabled = !valid;
        ok.textContent = stock ? '确认加速' : '前往商城';
        status.textContent = !valid ? '行军状态已变化，请关闭后重新选择。' : stock ? '' : '行军令库存不足';
        return { valid: valid, stock: stock };
      }
      cancel.onclick = close;
      mask.onclick = function (event) { if (event.target === mask) close(); };
      mask.onkeydown = function (event) {
        if (event.key === 'Escape') close();
        if (event.key === 'Tab') {
          event.preventDefault();
          (document.activeElement === cancel && !ok.disabled ? ok : cancel).focus();
        }
      };
      ok.onclick = function () {
        if (closed || pending || failed) return;
        var check = update();
        if (!check.valid) return;
        if (!check.stock) { close(); G.go('shop'); return; }
        pending = true;
        ok.disabled = cancel.disabled = true;
        ok.textContent = '加速中…';
        status.textContent = '';
        G.API.speedUpMarch(Number(marchId), returning, version).then(function (result) {
          pending = false;
          dispose();
          if (!sameContext()) return;
          G.toast(result.message || (returning ? '回城已加速' : '行军已加速'));
          if (G.WorldMap) G.WorldMap.invalidate();
          Core.render();
        }).catch(function (error) {
          // 结果不明也只读取状态，禁止自动重放消耗请求或继续使用旧确认快照。
          pending = false;
          if (closed || !sameContext()) { dispose(); return; }
          failed = true;
          cancel.disabled = false;
          ok.textContent = '确认加速';
          ok.disabled = true;
          status.textContent = (error.message || '加速失败') + '；请关闭后重新查看军情。';
          if (G.API.getGameState) G.API.getGameState(true).then(function (latest) {
            if (!sameContext()) return;
            G.API.applyState(latest);
            Core.render();
          }).catch(function () {});
        });
      };
      update();
      timer = setInterval(update, 1000);
      (ok.disabled ? cancel : ok).focus();
    },

    fmtMarchTime: function (arriveAt) {
      var remain = Math.max(0, Math.ceil((arriveAt - G.gameNow()) / 1000));
      if (remain <= 0) return '到达!';
      var m = Math.floor(remain / 60);
      var s = remain % 60;
      return m > 0 ? m + '分' + s + '秒' : s + '秒';
    },

    /** 切换侦查方式时清空旧兵力，重新计算对应的速度、补给和路线。 */
    setReconMode: function (mode) {
      var dt = Core.state.world._dispatchTarget;
      if (!dt || dt.action !== 'scout' || !['air', 'land'].includes(mode)) return;
      dt.reconMode = mode;
      dt.defaultArmy = {};
      Core.render();
    },

    /** 路线请求失败可手动重试，保留当前选兵数量；重试不自动发送出征请求。 */
    retryGroundRecon: function () {
      var dt = Core.state.world._dispatchTarget;
      if (!dt || dt.action !== 'scout' || (dt.groundRecon && dt.groundRecon.pending)) return;
      ['scout', 'recon_car'].forEach(function (unit) {
        var input = document.getElementById('dqty_' + unit);
        if (input) {
          dt.defaultArmy = {};
          dt.defaultArmy[unit] = Math.max(0, parseInt(input.value, 10) || 0);
        }
      });
      delete dt.groundRecon;
      Core.render();
    },

    /** 通过只读路线接口确认地面侦查资格，异步结果只更新原目标、原城市的表单。 */
    checkGroundRecon: function (dt, target) {
      if (!G.API || !G.API.client || !(Number(Core.state.army.recon_car) > 0)) return;
      var cp = Core.state.world.cityPos || Core.state.world.pos || {};
      var player = Core.state.player || {};
      var key = [player.id, player.activeCityId, player.citySlot, cp.x, cp.y, dt.kind, target.id].join('|');
      if (dt.groundRecon && dt.groundRecon.key === key) return;
      var status = dt.groundRecon = { key: key, pending: true };
      function finish(available, error) {
        if (Core.state.world._dispatchTarget !== dt || dt.groundRecon !== status) return;
        var currentPlayer = Core.state.player || {};
        var currentPos = Core.state.world.cityPos || Core.state.world.pos || {};
        if ([currentPlayer.id, currentPlayer.activeCityId, currentPlayer.citySlot, currentPos.x, currentPos.y, dt.kind, target.id].join('|') !== key) return;
        status.pending = false; status.available = available; status.error = error;
        var currentView = document.getElementById('dpReconMode');
        if (currentView && currentView.isConnected) {
          var radio = currentView.querySelector('input[value="land"]');
          if (radio) radio.disabled = !available;
          var message = currentView.querySelector('[data-ground-recon-status]');
          if (message) message.textContent = available ? '地面侦查可抵达目标' : error;
          var retry = currentView.querySelector('[data-ground-recon-retry]');
          if (retry) retry.hidden = available;
        }
      }
      G.API.client.post('/game/world/route', { targetKind: dt.kind, targetId: target.id,
        action: 'scout', army: { recon_car: 1 } }, { silent: true }).then(function () {
        finish(true, '');
      }).catch(function (error) {
        finish(false, error.message || '地面侦查路线暂时无法确认，请重试');
      });
    },

    renderDispatch: function (v) {
      var s = Core.state;
      var dt = s.world._dispatchTarget;
      if (!dt) { G.go('world'); return; }
      if (dt.kind === 'npc') { G.go('world'); return; }
      this.prepareCampaign(dt);
      var target;
      if (dt.kind === 'bandit') target = dt.target || s.world.bandits[dt.idx];
      else if (dt.kind === 'simulated_npc') target = dt.target || s.world.simulatedNpcCities[dt.idx];
      else if (dt.kind === 'player') target = dt.target || s.world.playerCities[dt.idx];
      else if (dt.kind === 'wild' || dt.kind === 'wild_gather') {
        var wtd = dt.target || s.world.wildTiles[dt.idx];
        if (wtd) {
          var wtdInfo = G.DATA.wildTypes[wtd.type] || { name: '野地', res: null, icon: '🪨' };
          target = { id: wtd.id, name: wtdInfo.name, level: wtd.level, x: wtd.x, y: wtd.y, army: wtd.garrison, _wildRef: wtd, _wildType: wtdInfo };
        }
      } else if (dt.kind === 'incoming_scout') {
        target = dt.target || {
          id: dt.incomingId || ('inc_' + dt.incomingIdx),
          name: dt.targetName || '敌方高空侦察机编队',
          x: dt.targetX != null ? dt.targetX : (s.world.cityPos ? s.world.cityPos.x : 0),
          y: dt.targetY != null ? dt.targetY : (s.world.cityPos ? s.world.cityPos.y : 0)
        };
      }
      if (!target) { G.go('world'); return; }

      var isWildConquer = dt.kind === 'wild';
      var isGather = dt.kind === 'wild_gather';
      var isScout = dt.action === 'scout';
      var isInterception = dt.action === 'intercept_scout';
      var sArmy = (s && s.army) || (s && s.barracks && s.barracks.army) || {};
      if (isScout && !dt.reconMode) {
        dt.reconMode = Number(sArmy.scout) > 0 || !Number(sArmy.recon_car) ? 'air' : 'land';
      }
      var scoutUnit = dt.reconMode === 'land' ? 'recon_car' : 'scout';
      var isStation = dt.action === 'station';
      var isCampaign = dt.action === 'campaign';
      var actionNames = G.Constants.dispatchActionNames || {};
      var actionName = isInterception ? '出城拦截' : (actionNames[dt.action] || '讨伐');
      var realMarchInterception = isInterception && dt.marchId != null;
      var scoutInterception = isInterception && this.isScoutInterception(dt);
      var actionDesc = isInterception
        ? (scoutInterception
          ? '侦查拦截仅可派遣空军或装甲车，交火后按实际战斗结果生成双方拦截战报。'
          : '选择任意可用兵种组成截击编队，在城外交火并按双方实际兵力、将领与战术结算；若敌军突围获胜，其幸存部队沿原行军路线继续前进。')
        : isScout
        ? '派遣侦查部队前往目标，抵达后进行侦查并生成情报报告；仅与对应方式的侦查部队交火，幸存部队自动返城。'
        : isStation
        ? '派遣部队行军进驻已占领野地，进驻后可驻防防守并就地开启资源采集。'
        : isGather
        ? '派遣部队前往已占领野地采集资源，采集量取决于部队负重；可选择全自动或全手动，资源返城后入库。'
        : '';
      var headingTitle = isInterception ? '出城拦截 · 截击出征' : (isStation ? '派遣进驻' : (isGather ? '采集派遣' : '出征准备'));
      var headingSub = isInterception ? (scoutInterception ? '可选配空军或装甲车 · 截击侦查部队' : '配置截击编队 · 敌军抵达前完成交火') : '确认目标 · 配置部队';
      var h = '<div class="dispatch-preparation' + (isInterception ? ' mode-interception' : '') + '">';
      h += '<div class="dispatch-preparation-heading"><h2>' + headingTitle + '</h2><span>' + headingSub + '</span></div>';
      h += '<div class="panel">';
      h += '<div class="bfield">行动: <b style="color:' + (isInterception ? 'var(--danger, #ef4444)' : 'var(--accent)') + '">' + actionName + '</b> | 目标: <b>' + target.name + '</b>';
      if (dt.kind !== 'player' && target.level) h += ' Lv.' + target.level;
      h += ' (' + target.x + ',' + target.y + ')</div>';
      if (isInterception) {
        var incTarget = dt.interceptTarget || {};
        var remTime = incTarget.arriveAt ? Math.max(0, Math.ceil((incTarget.arriveAt - G.gameNow()) / 1000)) : 45;
        var m = Math.floor(remTime / 60);
        var sec = remTime % 60;
        var countdownStr = (m < 10 ? '0' : '') + m + ':' + (sec < 10 ? '0' : '') + sec;
        h += '<div class="d" style="color:var(--gold)">🎯 目标状态: ' + (realMarchInterception ? '敌军行军正在抵近，预计 ' : '敌方侦察机正在抵近，预计 ') + countdownStr + ' 到达</div>';
        h += '<div class="d" style="color:var(--text-sub, #94a3b8)">🛡️ 拦截后双方将收到城外交火战报；敌军突围获胜时，幸存部队沿原路线继续行进。</div>';
      }
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
      } else if (!isInterception) {
      var scouted = getScouted(target.x, target.y);
      if (scouted) {
        var isJpn = dt.kind === 'npc' || dt.kind === 'bandit' || dt.kind === 'simulated_npc' || (target.name && (target.name.indexOf('日寇') >= 0 || target.name.indexOf('据点') >= 0));
        h += '<div class="d">守军: ' + armyText(scouted.data.army, isJpn) + '</div>';
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
      if (isInterception) {
        h += '<div id="dispatchRoute" class="desc" aria-live="polite">' + (realMarchInterception ? '已锁定敌军来袭路线，选择部队后计算城外交火点与截击时间' : '已锁定敌机抵近航向，出动截击部队将在城外空域建立防空拦截网') + '</div>';
      } else {
        h += '<div id="dispatchRoute" class="desc" aria-live="polite">选择兵力后计算实际路线和时间</div>';
        h += '<div class="desc">舰队沿海航行；跨海陆军需要运输机，每架提供 80 运力。旧内陆城市保留海军补给通道。</div>';
      }
      if (actionDesc) h += '<div class="d" style="color:var(--gold);margin-top:4px">' + actionDesc + '</div>';
      h += '</div>';

      if (isCampaign) {
        var occupyOnly = dt.kind === 'wild' && !target._wildType.res;
        var conquerMode = dt.conquerMode === 'occupy' ? 'occupy' : 'withdraw';
        h += '<section class="dispatch-campaign-settings" aria-label="出征方式">';
        h += '<div class="dispatch-campaign-choice"><label for="dpCampaignAction">出征方式<small>选择本次行动的目的</small></label>';
        h += '<select id="dpCampaignAction" aria-describedby="dpCampaignDescription" onchange="Game.World.setCampaignAction(this.value)">';
        G.Constants.campaignModes.forEach(function (mode) {
          h += '<option value="' + mode.action + '"' + (dt.campaignAction === mode.action ? ' selected' : '') + (occupyOnly && mode.action === 'plunder' ? ' disabled' : '') + '>' + mode.name + '</option>';
        });
        h += '</select></div>';
        h += '<div class="dispatch-campaign-hint" role="status" aria-live="polite" aria-atomic="true"><div class="dispatch-campaign-hint-heading"><strong id="dpCampaignHeading">' + G.Constants.dispatchActionNames[dt.campaignAction] + '说明</strong><span id="dpCampaignLimit">最多 ' + G.Constants.battleRoundLimits[dt.campaignAction] + ' 回合</span></div>';
        h += '<p id="dpCampaignDescription">' + esc(this.campaignDescription(dt)) + '</p></div>';
        if (dt.kind === 'wild') {
          h += '<div id="dpCampaignTroops"' + (!dt.occupyTarget ? ' hidden' : '') + '><div class="dispatch-campaign-subtitle">占领成功后部队</div>';
          h += '<label class="dispatch-gather-mode"><input type="radio" name="dpConquerMode" value="withdraw"' + (conquerMode === 'withdraw' ? ' checked' : '') + ' onchange="Game.World.setConquerMode(this.value)">';
          h += '<span><strong>自动返城</strong><span>幸存部队按原路线返城，野地仍归我方</span></span></label>';
          h += '<label class="dispatch-gather-mode"><input type="radio" name="dpConquerMode" value="occupy"' + (conquerMode === 'occupy' ? ' checked' : '') + ' onchange="Game.World.setConquerMode(this.value)">';
          h += '<span><strong>原地驻守</strong><span>幸存部队留在野地驻守，后续可手动撤回</span></span></label></div>';
        }
        h += '</section>';
      }

      if (isScout) {
        var ground = dt.groundRecon || {};
        h += '<div class="panel" id="dpReconMode"><b>侦查方式</b><div class="btn-row" style="flex-wrap:wrap">';
        h += '<label><input type="radio" name="dpReconMode" value="air"' + (dt.reconMode === 'air' ? ' checked' : '') + (!Number(sArmy.scout) ? ' disabled' : '') + ' onchange="Game.World.setReconMode(this.value)"> 空中侦查 · 侦察机：快速／可跨海</label>';
        h += '<label><input type="radio" name="dpReconMode" value="land"' + (dt.reconMode === 'land' ? ' checked' : '') + (!Number(sArmy.recon_car) || !ground.available ? ' disabled' : '') + ' onchange="Game.World.setReconMode(this.value)"> 地面侦查 · 侦查装甲车：装甲耐久／可用于攻防</label>';
        h += '</div><div class="desc" data-ground-recon-status>' + esc(!Number(sArmy.recon_car) ? '城内没有侦查装甲车' : ground.available ? '地面侦查可抵达目标' : ground.error || '正在确认地面侦查路线…') + '</div>';
        h += '<button type="button" class="btn" data-ground-recon-retry' + (!ground.error || ground.pending ? ' hidden' : '') + ' onclick="Game.World.retryGroundRecon()">重新检查陆路</button></div>';
      }

      h += '<div class="dispatch-section-heading"><span>兵种配置 <small>' + (scoutInterception ? '可选配空军或装甲车' : '选择出征数量') + '</small></span><span class="dispatch-unit-actions">';
      h += '<button type="button" class="btn dispatch-set-all" onclick="Game.World.setAllDispatchUnitsToOne()">全部设置1单位</button>';
      h += '<button type="button" class="btn dispatch-reset" onclick="Game.World.resetDispatchUnits()">重置</button>';
      h += '</span></div>';
      h += '<div class="panel">';
      var defaultArmy = (dt && dt.defaultArmy) ? Object.assign({}, dt.defaultArmy) : {};
      var defaultLoad = this.calcDispatchLoad(defaultArmy);
      var hasAny = false;
      for (var uid in D.units) {
        var have = sArmy[uid] || 0;
        if (have <= 0) continue;
        if (isScout && uid !== scoutUnit) continue;
        if (scoutInterception && !this.isInterceptUnit(uid, D.units[uid])) continue;
        hasAny = true;
        var u = D.units[uid];
        var loadHint = u.logistic ? ' (辎重' + u.load + '/辆)' : u.load > 0 ? ' (负重' + u.load + '/人)' : '';
        var supplyBadge = ' <span class="dispatch-unit-supply" title="每100格油耗 ' + (u.marchOil || 0) + '">油耗 ' + (u.marchOil || 0) + '</span>';
        var antiAirBadge = (u.atkAir != null) ? ' <span class="dispatch-unit-antiair" title="对空作战火力 ' + u.atkAir + '">对空 ' + u.atkAir + '</span>' : '';
        var techMul = (typeof Core !== 'undefined' && Core.spdMul) ? Core.spdMul(u.cat, uid) : 1;
        var techBonus = Math.round((techMul - 1) * 100);
        var effSpd = (u.spd * techMul * (G.UnitEquipment ? G.UnitEquipment.speed(G.UnitEquipment.snapshot(), uid, false) : 1)).toFixed(1);
        if (effSpd.endsWith('.0')) effSpd = effSpd.slice(0, -2);
        var spdBadge = techBonus > 0
          ? ' <span class="dispatch-unit-spd has-tech" title="基础移速 ' + u.spd + '（战场机动 ' + Math.round(u.spd * 50) + '），科技加成 +' + techBonus + '%">移速 ' + effSpd + ' <small class="tech-tag">⚡+' + techBonus + '%</small></span>'
          : ' <span class="dispatch-unit-spd" title="基础移速 ' + u.spd + '（战场机动 ' + Math.round(u.spd * 50) + '）">移速 ' + effSpd + '</span>';
        var initialVal = Math.min(have, Math.max(0, parseInt(defaultArmy[uid], 10) || 0));
        var sliderVal = initialVal;
        var pct = have > 0 ? ((sliderVal / have) * 100).toFixed(1) : 0;
        var sliderId = 'dslider_' + uid;
        h += '<div class="dispatch-unit-row">';
        h += '<div class="dispatch-unit-info">';
        var uName = (G.unitDisplayName ? G.unitDisplayName(u.name) : (function (name) {
          var codeMatch = name.match(/[（(]([^）)]+)[）)]/);
          var code = codeMatch ? codeMatch[1].trim() : '';
          var base = name.indexOf('-') > 0 ? name.split('-')[0].trim() : name.replace(/[（(].*?[）)]/, '').trim();
          return code ? base + '(' + code + ')' : base;
        })(u.name));
        h += '<span class="dispatch-unit-name" title="' + esc(u.name) + '">' + uName + loadHint + spdBadge + antiAirBadge + supplyBadge + '</span>';
        h += '<span class="dispatch-unit-have">城内' + G.fmt(have) + '</span>';
        h += '</div>';
        h += '<div class="dispatch-unit-control">';
        h += '<input class="qty recruit-qty" id="dqty_' + uid + '" type="number" min="0" max="' + have + '" value="' + initialVal + '" oninput="Game.World.onDispatchInputChange(\'' + uid + '\',this.value)" onchange="var v=parseInt(this.value,10);if(isNaN(v)||v<0){this.value=0;}else if(v>' + have + '){this.value=' + have + ';}Game.World.onDispatchInputChange(\'' + uid + '\',this.value);" />';
        h += '<div class="recruit-slider-wrap">';
        h += '<input type="range" class="recruit-slider" id="' + sliderId + '" min="0" max="' + have + '" value="' + sliderVal + '" style="--p:' + pct + '%" oninput="Game.World.onDispatchSliderChange(\'' + uid + '\',this.value)" />';
        h += '</div>';
        h += '</div>';
        h += '</div>';
      }
      if (!hasAny) {
        if (scoutInterception) {
          h += '<div class="desc" style="color:#ef4444;padding:8px 12px;border:1px dashed rgba(239,68,68,0.5);margin:6px 0;background:rgba(239,68,68,0.06);border-radius:4px">';
          h += '⚠️ 城内暂无可用的空军或装甲车！<br><small style="color:#94a3b8">侦查拦截仅支持配置【空军】（在空军基地生产）或【装甲车】（在军工厂制造）。请征召对应兵种以拦截侦查部队。</small>';
          h += '</div>';
        } else if (isScout) {
          h += '<div class="desc">城内没有可用侦查部队，请制造侦察机或侦查装甲车</div>';
        } else {
          h += '<div class="desc">城内无可用部队,请先征兵。</div>';
        }
      }
      h += '<div class="dispatch-stats-card" id="dispatchStatsCard">';
      h += '  <div class="dispatch-stats-header">';
      h += '    <span class="dispatch-stats-title">' + (isInterception ? (scoutInterception ? '防空截击战力评估' : '截击编队状态') : '行军与编队军情') + '</span>';
      h += '    <span class="dispatch-boost-tag" id="estMarchBoostTag" style="display:none">⚡ 行军加速生效中 (+50%)</span>';
      h += '  </div>';
      h += '  <div class="dispatch-stats-grid">';
      h += '    <div class="dispatch-stat-item">';
      h += '      <span class="dispatch-stat-label">行军距离</span>';
      h += '      <strong class="dispatch-stat-val" id="estMarchDist">--</strong>';
      h += '    </div>';
      h += '    <div class="dispatch-stat-item">';
      h += '      <span class="dispatch-stat-label">全军基准移速</span>';
      h += '      <strong class="dispatch-stat-val" id="estMarchSpeed">--</strong>';
      h += '    </div>';
      h += '    <button type="button" class="dispatch-stat-item dispatch-cap-action" onclick="Game.World.showArmyCapInfo()" aria-label="查看带兵上限计算说明">';
      h += '      <span class="dispatch-stat-label">出征兵力 / 带兵上限</span>';
      h += '      <strong class="dispatch-stat-val" id="estDispatchTroops">' + G.fmt(Object.values(defaultArmy).reduce(function (total, count) { return total + count; }, 0)) + ' / ' + G.fmt(typeof Core.armyCap === 'function' ? Core.armyCap() : 0) + '</strong>';
      h += '    </button>';
      h += '    <div class="dispatch-stat-item">';
      h += '      <span class="dispatch-stat-label">出征队伍 / 队列上限</span>';
      h += '      <strong class="dispatch-stat-val" id="estDispatchQueues">' + ((s.world && s.world.marches) || []).length + ' / ' + (typeof Core.maxMarches === 'function' ? Core.maxMarches() : (4 + ((s.buildings && s.buildings.headquarters) || 0))) + '</strong>';
      h += '    </div>';
      if (scoutInterception) {
        h += '    <div class="dispatch-stat-item antiair-power highlight">';
        h += '      <span class="dispatch-stat-label">截击防空总战力</span>';
        h += '      <strong class="dispatch-stat-val highlight" id="estInterceptPower">0</strong>';
        h += '    </div>';
        h += '    <div class="dispatch-stat-item intercept-rate highlight">';
        h += '      <span class="dispatch-stat-label">拦截战斗判定</span>';
        h += '      <strong class="dispatch-stat-val highlight" id="estInterceptRate">--</strong>';
        h += '    </div>';
      }
      h += '    <div class="dispatch-stat-item highlight">';
      h += '      <span class="dispatch-stat-label">' + (isInterception ? '预计部署耗时' : '预计单程耗时') + '</span>';
      h += '      <strong class="dispatch-stat-val highlight" id="estMarchTimeOneWay">--</strong>';
      h += '    </div>';
      h += '    <div class="dispatch-stat-item">';
      h += '      <span class="dispatch-stat-label">' + (isInterception ? '距预计交火' : '预计往返总耗时') + '</span>';
      h += '      <strong class="dispatch-stat-val" id="estMarchTimeRound">--</strong>';
      h += '    </div>';
      h += '    <div class="dispatch-stat-item supply-oil">';
      h += '      <span class="dispatch-stat-label">行军油耗</span>';
      h += '      <strong class="dispatch-stat-val" id="estSupplyOil">--</strong>';
      h += '    </div>';
      h += '  </div>';
      h += '  <div class="dispatch-supply-note" id="estSupplyLeg">请选择部队后计算补给</div>';
      h += '  <div class="dispatch-stats-tip" id="estMarchSpeedTip">请配置出征兵力以计算行军时间</div>';
      if (!isScout && !isInterception) {
        h += '  <div class="dispatch-stats-footer">';
        h += '    <span>当前编队预估辎重: <b id="estLoad">' + G.fmt(defaultLoad) + '</b></span>';
        h += '  </div>';
      }
      h += '</div>';
      h += '</div>';

      if (isGather) {
        var gatherMode = dt.gatherMode === 'manual' ? 'manual' : 'auto';
        h += '<fieldset class="panel dispatch-gather-modes"><legend>采集模式</legend>';
        h += '<label class="dispatch-gather-mode"><input type="radio" name="dpGatherMode" value="auto"' + (gatherMode === 'auto' ? ' checked' : '') + ' onchange="Game.World.setGatherMode(this.value)" />';
        h += '<span><strong>采集全自动</strong><span>采集完成后自动收获并自动按原路线回城</span></span></label>';
        h += '<label class="dispatch-gather-mode"><input type="radio" name="dpGatherMode" value="manual"' + (gatherMode === 'manual' ? ' checked' : '') + ' onchange="Game.World.setGatherMode(this.value)" />';
        h += '<span><strong>采集全手动</strong><span>需要玩家自主终止采集任务，需要亲自下达命令选择部队回城</span></span></label>';
        h += '<div class="desc">' + '全手动采满后仍在野地等待；可提前终止并保留已采资源，再点击“部队回城”。返城不改变野地归属。' + '</div></fieldset>';
      }
      if (!isGather && !isScout && !isStation && dt.kind !== 'player') {
        var battleMode = dt.battleMode || (dt.kind === 'wild' ? 'auto' : 'manual');
        h += '<fieldset class="panel dispatch-gather-modes"><legend>战斗方式</legend>';
        h += '<label class="dispatch-gather-mode"><input type="radio" name="dpBattleMode" value="manual"' + (battleMode === 'manual' ? ' checked' : '') + ' onchange="Game.World.setBattleMode(this.value)" />';
        h += '<span><strong>手动指挥</strong><span>可以手动进入战场指挥战斗</span></span></label>';
        h += '<label class="dispatch-gather-mode"><input type="radio" name="dpBattleMode" value="auto"' + (battleMode === 'auto' ? ' checked' : '') + ' onchange="Game.World.setBattleMode(this.value)" />';
        h += '<span><strong>自动结算</strong><span>进入战斗后系统直接完成战斗结算</span></span></label></fieldset>';
      }
      // 任何行军(侦查/讨伐/采集)都可以带指挥官
      //  侦查: 遭遇战按周期接入军事、防御和战斗技能
      //  讨伐: 攻击/技能加成
      //  采集: 后勤属性提升采集速度 (1 + logistics/100, 上限 ×3.0)
      if (true) {
        h += '<div class="zone-head">-- 指挥官选择 --</div>';
        h += '<div class="panel">';
        if (isInterception) h += '<div class="desc">指挥官随队出战可提供军事攻击与对空战术技能加成，显著提升拦截火网杀伤力与确保击落概率。</div>';
        if (isScout) h += '<div class="desc">随队将领军事、防御与战斗技能在首次交火时生效，之后间隔两回合再触发（第1、4、7、10回合；每点军事／防御+1%攻击／防御）。装备、套装计入属性；不派遣将领时无随队将领加成。</div>';
        if (isScout) h += '<div class="desc">双方先手按有效速度＋5×自身侦察技术等级比较；守方雷达仅首回合每级+5点。同值时奇数回合出征方先手，偶数回合守方先手。</div>';
        var marchingCommanderIds = new Set();
        var activeMarches = (s.world && s.world.marches) || (Core.state && Core.state.world && Core.state.world.marches) || [];
        activeMarches.forEach(function (m) {
          if (m.commanderId != null) {
            marchingCommanderIds.add(Number(m.commanderId));
            marchingCommanderIds.add(String(m.commanderId));
          }
        });

        // 仅展示未出征、未担任市长或防守指挥官的空闲将领；已出征军官不再提供选择，防止误选后提示失败
        var availableOfficers = (s.officers || []).filter(function (o) {
          if (!o) return false;
          if (o.role === 'mayor' || o.role === 'commander' || o.role === 'commander_in_chief' || o.role === 'march') return false;
          if (marchingCommanderIds.has(Number(o.id)) || marchingCommanderIds.has(String(o.id))) return false;
          return true;
        });

        // 默认选择可用将领中军事属性最高者；属性相同则选择列表中的第一位。
        var bestOfficerIndex = -1;
        var bestMilitary = -Infinity;
        for (var bi = 0; bi < availableOfficers.length; bi++) {
          var military = Number(availableOfficers[bi].military) || 0;
          if (bestOfficerIndex < 0 || military > bestMilitary) {
            bestOfficerIndex = bi;
            bestMilitary = military;
          }
        }
        var foundC = bestOfficerIndex >= 0;
        if (!availableOfficers.length) {
          h += '<div class="desc">' + ((s.officers && s.officers.length) ? '暂无空闲将领可选（其他将领正在出征或已任要职）' : '无军官可用') + '</div>';
        }
        for (var oi = 0; oi < availableOfficers.length; oi++) {
          var o = availableOfficers[oi];
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
          h += esc(o.name) + ' <span style="color:' + (D.starColor[o.star] || '#bbb') + '">' + starStr + '</span>';
          h += ' Lv.' + o.level + ' 将' + o.military + ' 军' + o.logistics + ' 智' + o.knowledge;
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
        if (!foundC) h += '<div class="desc">(当前无空闲将领可选，建议招募将领或等待出征队伍返城以获得战力与经验加成)</div>';
        h += '</div>';

        if (!isInterception) {
          h += '<div class="zone-head">-- 携带资源 (部队负重内) --</div>';
          h += '<div class="panel">';
          h += '<div class="desc">携带资源随军出征,战胜则返还并计入战利品,战败则丢失。</div>';
          var resOrder = (G.Constants && G.Constants.resourceKeys) || ['food', 'steel', 'oil', 'rare', 'gold'];
          for (var ri = 0; ri < resOrder.length; ri++) {
            var rk = resOrder[ri];
            var rinfo = (D.resources && D.resources[rk]) || {};
            var haveR = (s.resources && s.resources[rk]) || 0;
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
      }

      if (isCampaign) h += '<div class="desc" id="dpCampaignSummary" aria-live="polite"></div>';
      var activeMarchesList = (s.world && s.world.marches) || (Core.state && Core.state.world && Core.state.world.marches) || [];
      var maxMarchesLimit = typeof Core.maxMarches === 'function' ? Core.maxMarches() : (4 + ((s.buildings && s.buildings.headquarters) || 0));
      var isQueueFull = activeMarchesList.length >= maxMarchesLimit;
      var dispatchBtnText = isQueueFull
        ? ('队列已满 (' + activeMarchesList.length + '/' + maxMarchesLimit + ')')
        : (isInterception ? '出城拦截 · 立即截击!' : (isScout ? '派出侦查部队' : (isGather ? '出发采集!' : isCampaign ? '出征讨伐' : '出发!')));
      h += '<button class="btn ok' + (isQueueFull ? ' btn-disabled' : '') + '" onclick="Game.World.launchDispatch()" style="font-size:16px;padding:8px 24px">' + dispatchBtnText + '</button>';
      h += '<button class="btn warn" onclick="Game.World.cancelDispatch()">返回</button>';
      h += '</div>';
      v.innerHTML = h;
      this.updateCampaignControls();
      if (this._disposeInterceptionPreview) this._disposeInterceptionPreview();
      this._interceptionPreviewKey = null;
      this._currentRoute = null;
      this._refreshDispatchRoute = null;
      this._dispatchRoutePending = false;
      this._dispatchRouteError = false;
      this.updateDispatchStats();
      if (isScout) this.checkGroundRecon(dt, target);
      if (isInterception && G.API && G.API.client && v.querySelector) {
        var self = this;
        this.bindRoutePreview(v, v.querySelector('#dispatchRoute'), function () {
          var army = {};
          v.querySelectorAll('[id^="dqty_"]').forEach(function (el) { var n = Math.max(0, parseInt(el.value, 10) || 0); if (n) army[el.id.slice(5)] = n; });
          return self.interceptionPreviewRequest(army, dt);
        }, { interception: true, updateOnInput: true });
        this._disposeInterceptionPreview = v._disposeRoutePreview;
      }
      if (!isInterception && G.API && G.API.client && v.querySelector) this.bindRoutePreview(v, v.querySelector('#dispatchRoute'), function () {
        var army = {};
        v.querySelectorAll('[id^="dqty_"]').forEach(function (el) { army[el.id.slice(5)] = Math.max(0, parseInt(el.value, 10) || 0); });
        return { targetKind: dt.kind, targetId: target.id, action: World.resolveDispatchAction(dt), army: army };
      }, { start: s.player && s.player.cityName, end: target.name + (target.level ? ' Lv.' + target.level : '') });
    },

    /** 按兵种键稳定排序构造拦截预览请求，数量变化也必须重算油耗。 */
    interceptionPreviewRequest: function (army, dt) {
      var sortedArmy = {};
      Object.keys(army).sort().forEach(function (id) { if (army[id] > 0) sortedArmy[id] = army[id]; });
      return { targetKind: dt.marchId != null ? 'incoming_march' : 'incoming_scout',
        targetId: dt.marchId != null ? dt.marchId : dt.incomingId || (dt.target && dt.target.id),
        action: 'intercept_scout', army: sortedArmy };
    },

    /**
     * 预览当前编队路线，并清理容器的旧绑定；失败原因供出征拦截提示。
     * @param {Element} container - 出征表单容器
     * @param {Element} hint - 路线预览与失败原因的展示区域
     * @param {Function} request - 获取当前编队路线请求参数
     * @param {Object} labels - 起终点名称与预览选项
     * @returns {void}
     */
    bindRoutePreview: function(container,hint,request,labels) {
      if(!hint||!G.API||!G.API.client)return;
      if(typeof container._disposeRoutePreview==='function')container._disposeRoutePreview();
      labels=Object.assign({},labels);
      var timer,refreshTimer,sequence=0,terrain=null,currentRoute=null,lastRouteKey=null,isLoading=false,lastRouteFailed=false,disposed=false;
      var self = this;
      if(!labels.interception&&G.DispatchRoute)G.DispatchRoute.loadTerrain().then(function(data){
        terrain=data;
        if(!disposed&&hint.isConnected&&currentRoute)G.DispatchRoute.render(hint,currentRoute,labels,terrain);
      });
      /**
       * 生成路线计算所需的稳定键。
       * 数量变化只影响编队统计，不改变路线几何；普通路线仅在兵种集合变化时重绘；拦截还需使用数量校验与权威油耗。
       * @param {Object} payload - 路线请求参数
       * @returns {string} 路线决定因素的稳定键
       */
      function routeKey(payload){
        if (labels.interception) return JSON.stringify(payload);
        var army=payload&&payload.army||{},units=[];
        Object.keys(army).sort().forEach(function(uid){
          if(Math.max(0,parseInt(army[uid],10)||0)>0)units.push(uid);
        });
        return [payload&&payload.targetKind||'',payload&&payload.targetId||'',payload&&payload.action||'',units.join(',')].join('|');
      }
      /**
       * 在路线决定因素变化时重新请求预览，否则仅刷新下方的动态统计。
       * @param {boolean} force - 是否忽略路线键并强制请求
       * @returns {void}
       */
      function update(force){
        // #view 会复用于多次出征；旧预览节点移除后不能再改写全局提交状态。
        if(disposed||!hint.isConnected)return;
        var payload=request(),nextRouteKey=routeKey(payload);
        labels.action=payload.action;
        var hasArmy = Object.keys(payload.army || {}).some(function(uid){
          return Math.max(0, parseInt(payload.army[uid], 10) || 0) > 0;
        });
        if (!hasArmy) {
          clearTimeout(timer);clearTimeout(refreshTimer);
          sequence++;
          lastRouteKey=nextRouteKey;
          isLoading=false;
          lastRouteFailed=false;
          currentRoute=null;
          self._currentRoute=null;
          self._dispatchRoutePending=false;
          self._dispatchRouteError=false;
          hint.setAttribute('aria-busy','false');
          hint.textContent='选择兵力后计算实际路线和时间';
          self.updateDispatchStats();
          return;
        }
        if(!force&&nextRouteKey===lastRouteKey&&(!lastRouteFailed||isLoading)){
          self.updateDispatchStats();
          return;
        }
        lastRouteKey=nextRouteKey;
        isLoading=true;
        lastRouteFailed=false;
        clearTimeout(timer);clearTimeout(refreshTimer);var seq=++sequence;currentRoute=null;
        // 模式切换后禁止正式提交继续携带旧预览的装备版本或路线统计。
        self._currentRoute=null;
        self._dispatchRoutePending=true;
        self._dispatchRouteError=false;
        hint.setAttribute('aria-busy','true');
        var badge=hint.querySelector('.dispatch-map-header > span');
        if(badge)badge.textContent='正在更新路线…';
        else hint.innerHTML='<div class="dispatch-map-card dispatch-map-empty">正在计算行军路线与抵达时间…</div>';
        timer=setTimeout(function(){
          if(disposed||!hint.isConnected)return;
          G.API.client.post('/game/world/route',payload,{silent:true}).then(function(route){
            if(disposed||!hint.isConnected||seq!==sequence)return;
            currentRoute=route;
            self._currentRoute = route;
            self._dispatchRoutePending=false;
            isLoading=false;
            hint.setAttribute('aria-busy','false');
            if (labels.interception) {
              self._interceptionPreviewKey = JSON.stringify(payload);
              var deploy = new Date(route.deployAt), clash = new Date(route.clashAt);
              hint.textContent = '服务端部署计划 · ' + (route.opening === 'ambush' ? '伏击拦截' : '紧急迎击')
                + ' · 部署到达 ' + deploy.toLocaleTimeString() + ' · 预计交火 ' + clash.toLocaleTimeString() + ' · 设伏准备 5 秒（游戏时间）';
            } else if(G.DispatchRoute)G.DispatchRoute.render(hint,route,labels,terrain);
            else hint.textContent='行军距离 '+distanceText(route.distance)+' 格 · 预计 '+route.seconds+' 秒';
            self.updateDispatchStats();
            if (labels.interception) {
              clearTimeout(refreshTimer);
              refreshTimer = setTimeout(function () { update(true); }, 5000);
            }
          }).catch(function(e){
            if(!disposed&&hint.isConnected&&seq===sequence){
              isLoading=false;
              lastRouteFailed=true;
              hint.setAttribute('aria-busy','false');
              hint.textContent=e.message||'路线计算失败，请重新选择部队后重试';
              self._currentRoute = null;
              self._dispatchRoutePending=false;
              self._dispatchRouteError=hint.textContent;
              self.updateDispatchStats();
            }
          });
        },300);
      }
      self._refreshDispatchRoute = function(force){ update(!!force); };
      function onChange(){update(false);}
      // 重新打开或重绘出征页时解绑旧监听，同时使未完成的旧请求失效。
      container._disposeRoutePreview=function(){
        disposed=true;sequence++;clearTimeout(timer);clearTimeout(refreshTimer);
        if(container.removeEventListener){
          container.removeEventListener('change',onChange);
          if(labels.updateOnInput)container.removeEventListener('input',onChange);
        }
      };
      // 数量输入的 input 事件只更新统计；change 事件用于检查兵种集合是否真的变化。
      container.addEventListener('change',onChange);
      // 城市支援弹窗即时更新，避免确认按钮点击时因输入失焦触发预览布局变化。
      if(labels.updateOnInput)container.addEventListener('input',onChange);
      update(true);
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
      // 视野半径: 优先用 _scan.r (用户可选), 0 = 全图; 兜底用 W.viewRadius (默认8格)
      var defaultRadius = (W && W.viewRadius) || 8;
      var storedRadius = (function () {
        try { return parseInt(localStorage.getItem('wg_viewRadius') || '', 10); } catch (e) { return NaN; }
      })();
      if (s.world._scan && typeof s.world._scan.r === 'number') {
        // 若旧缓存或旧会话残留了旧默认值3格，自动迁移为8格
        if (s.world._scan.r === 3) s.world._scan.r = defaultRadius;
        // _scan.r 优先 (用户已交互过), 同步到 localStorage 以便刷新后保持
        if (!isNaN(storedRadius) && storedRadius !== s.world._scan.r) {
          try { localStorage.setItem('wg_viewRadius', String(s.world._scan.r)); } catch (e) {}
        }
      } else {
        // 首次进入: 用 localStorage 备份或默认 (默认8格; 旧默认值3格自动升级为8格)
        var initR = (!isNaN(storedRadius) && storedRadius > 0 && storedRadius !== 3) ? storedRadius : defaultRadius;
        s.world._scan = { r: initR, at: G.gameNow() };
        try { localStorage.setItem('wg_viewRadius', String(initR)); } catch (e) {}
      }
      var scanR = s.world._scan.r;
      if (!G.WorldView.ensure(px, py, scanR)) {
        v.innerHTML = '<div class="card">正在加载地图区域… <button class="btn" onclick="Game.World.refreshMapData()">刷新</button></div>';
        return;
      }
      var activeTab = s.world._activeTab || 'all';

      function mapIcon(key, extraClass) {
        if (G && typeof G.mapIconHtml === 'function') {
          return G.mapIconHtml(key, extraClass);
        }
        var map = (G && G.MapIcons) || {
          city: '<svg class="map-icon map-icon-city" viewBox="0 0 16 16" aria-hidden="true"><path d="M1 14h14v1.2H1V14z" fill="currentColor" opacity="0.9"/><path d="M2 7h2v6H2V7zm10 0h2v6h-2V7z" fill="currentColor" opacity="0.85"/><path d="M4 8.5h8v4.5H9.5V10c0-.8-.7-1.5-1.5-1.5s-1.5.7-1.5 1.5v3H4V8.5z" fill="currentColor"/><path d="M1.5 5.5h3V7h-3V5.5zm10 0h3V7h-3V5.5z" fill="currentColor"/><path d="M5.5 3h5v5.5h-5V3z" fill="currentColor" opacity="0.9"/><path d="M5 3l3-2 3 2H5z" fill="currentColor"/><circle cx="8" cy="5.2" r="0.9" fill="#ffffff" opacity="0.9"/></svg>',
          view: '<svg class="map-icon map-icon-view" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" stroke-width="1.4"/><circle cx="8" cy="8" r="2.2" fill="currentColor" fill-opacity="0.3" stroke="currentColor" stroke-width="1"/><circle cx="8" cy="8" r="1.1" fill="currentColor"/><path d="M8 1v3.2M8 11.8V15M1 8h3.2M11.8 8H15" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>',
          wild: '<svg class="map-icon map-icon-wild" viewBox="0 0 16 16" aria-hidden="true"><path d="M1 14h14v1.2H1V14z" fill="currentColor" opacity="0.85"/><path d="M1.5 13.5L6.2 4.2c.4-.8 1.2-.8 1.6 0l4.2 8.3c.3.6-.1 1-.8 1H2.3c-.7 0-1.1-.4-.8-1z" fill="currentColor" fill-opacity="0.8"/><path d="M7 4.2l3.8 8.8H7V4.2z" fill="#ffffff" fill-opacity="0.25"/><path d="M8.5 13.5l3.2-5.5c.3-.5.9-.5 1.2 0l2.3 4.5c.2.4-.1 1-.6 1h-5.5c-.4 0-.7-.6-.6-1z" fill="currentColor" fill-opacity="0.95"/><path d="M12.3 8l2 5h-2V8z" fill="#ffffff" fill-opacity="0.3"/><path d="M3.8 13.5l1.4-2.5h-.7l1.1-2h-.6l.9-1.6.9 1.6h-.6l1.1 2h-.7l1.4 2.5H3.8z" fill="currentColor"/></svg>',
          bandit: '<svg class="map-icon map-icon-bandit" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="6.5" fill="none" stroke="currentColor" stroke-width="1.2" stroke-dasharray="2.5 1.5" opacity="0.6"/><path d="M2.2 2.2l4.8 4.8-1 1-4.8-4.8 1-1zm6.8 6.8l4.8 4.8-1 1-4.8-4.8 1-1z" fill="currentColor"/><path d="M13.8 2.2l-4.8 4.8 1 1 4.8-4.8-1-1zm-6.8 6.8l-4.8 4.8 1 1 4.8-4.8-1-1z" fill="currentColor"/><path d="M2.5 2.5L13.5 13.5M13.5 2.5L2.5 13.5" stroke="#ffffff" stroke-width="0.75" opacity="0.4"/><circle cx="8" cy="8" r="2" fill="currentColor"/><circle cx="8" cy="8" r="0.9" fill="#ffffff"/></svg>',
          player: '<svg class="map-icon map-icon-player" viewBox="0 0 16 16" aria-hidden="true"><path d="M1 14.2h14v1.2H1v-1.2z" fill="currentColor" opacity="0.85"/><path d="M1.5 5.5h3v8.5h-3V5.5zm0-2h1v1.5h-1V3.5zm2 0h1v1.5h-1V3.5z" fill="currentColor"/><path d="M11.5 5.5h3v8.5h-3V5.5zm0-2h1v1.5h-1V3.5zm2 0h1v1.5h-1V3.5z" fill="currentColor"/><path d="M4.5 7h7v7h-7V7zm1-4.5h1.2v1.5H5.5V2.5zm2 0h1v1.5h-1V2.5zm1.8 0h1.2v1.5h-1.2V2.5z" fill="currentColor"/><path d="M6.5 14v-3.5c0-.8.7-1.5 1.5-1.5s1.5.7 1.5 1.5V14H6.5z" fill="#ffffff" fill-opacity="0.35"/><rect x="2.5" y="7" width="1" height="2" fill="#ffffff" fill-opacity="0.4"/><rect x="12.5" y="7" width="1" height="2" fill="#ffffff" fill-opacity="0.4"/></svg>',
          territory: '<svg class="map-icon map-icon-territory" viewBox="0 0 16 16" aria-hidden="true"><path d="M1.5 14h13v1.2h-13V14z" fill="currentColor" opacity="0.85"/><path d="M3 14c.5-1.2 2-2 5-2s4.5.8 5 2H3z" fill="currentColor" fill-opacity="0.4"/><path d="M3.8 2.5l.8-1.7.8 1.7-.8.5-.8-.5z" fill="currentColor"/><path d="M4.2 2.5h.8v11.5h-.8V2.5z" fill="currentColor"/><path d="M5 2.8h9.5l-2.8 3.5 2.8 3.5H5V2.8z" fill="currentColor"/><path d="M7.8 4.8l.6 1.2 1.3.2-1 .9.2 1.3-1.1-.6-1.1.6.2-1.3-1-.9 1.3-.2.5-1.2z" fill="#ffffff" fill-opacity="0.85"/></svg>',
          home: '<svg class="map-icon map-icon-home" viewBox="0 0 16 16" aria-hidden="true"><path d="M1 14h14v1.2H1V14z" fill="currentColor" opacity="0.85"/><path d="M2.5 7h11v7h-11V7z" fill="currentColor" fill-opacity="0.75"/><path d="M8 1.2L1.8 6.5h12.4L8 1.2z" fill="currentColor"/><path d="M8 7.5l.7 1.4 1.5.2-1.1 1 .3 1.5-1.4-.7-1.4.7.3-1.5-1.1-1 1.5-.2.7-1.4z" fill="#ffffff"/><path d="M6.8 14v-2c0-.7.5-1.2 1.2-1.2s1.2.5 1.2 1.2v2H6.8z" fill="#ffffff" fill-opacity="0.4"/></svg>'
        };
        var raw = map[key] || '';
        if (!extraClass || !raw) return raw;
        return raw.replace('class="map-icon ', 'class="map-icon ' + extraClass + ' ');
      }
      var sortMode = s.world._sortMode || 'distance';

      function esc(str) {
        return String(str == null ? '' : str).replace(/[&<>"']/g, function (c) {
          return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
      }

      var wildAll = (s.world.wildTiles || []).map(function (t, i) {
        return { kind: 'wild', t: t, i: i, d: dist(px, py, t.x, t.y) };
      });
      var banditAll = (s.world.bandits || []).map(function (n, i) {
        return { kind: 'bandit', n: n, i: i, d: dist(px, py, n.x, n.y) };
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
            commanderName: (s.player && (s.player.name || s.player.username)) || '',
            militaryRank: s.player && s.player.militaryRank,
            militaryRankName: s.player && s.player.militaryRankName,
            prestige: (s.player && s.player.prestige) || 0, _selfCity: true
          }
        });
      }
      // scanR = 0 表示全图, 跳过距离过滤; > 0 时按半径过滤
      // 列表距离改为直线，情报可见范围仍与服务端原有扫描规则一致。
      var inRange = scanR > 0 ? function (x) {
        var target = x.t || x.n || x.p;
        return Math.abs(px-target.x)+Math.abs(py-target.y) <= scanR;
      } : function () { return true; };
      // 坐标搜索结果可在视野外显示，但只展示基础信息；详细情报仍需侦查
      var wildsNearby = wildAll.filter(function (x) { return (inRange(x) || x.t._searchOnly) && !x.t.occupied; });
      var npcsNearby = banditAll
        .filter(function (x) { return inRange(x) || x.n._searchOnly; })
        .filter(function (x) { return !x.n.defeated; });
      var playersNearby = playerAll.filter(function (x) { return inRange(x) || x.p._searchOnly; });
      var owned = wildAll.filter(function (x) { return currentCityWild(s, x.t); });

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

      function targetCardKey(kind, target) {
        return kind + ':' + (target.id != null ? target.id : target.x + ',' + target.y);
      }

      function targetCardExpanded(key) {
        return Object.prototype.hasOwnProperty.call(s.world._expandedTargetIds || {}, key);
      }

      // 野地卡
      function renderWildCard(it, ownedView) {
        var t = it.t;
        // 后端 wild_tiles.type 历史上可能写入过 D.wildTypes 里没有的旧值, 找不到时回退到通用占位
        var wt = D.wildTypes[t.type] || { name: '野地', res: null, icon: '🪨' };
        // 列表野地没有 kind；补齐地图目标类型，无模型地块仍使用类型图标。
        var iconPath = (G.WorldMap && typeof G.WorldMap.icon === 'function') ? G.WorldMap.icon(Object.assign({}, t, { kind: 'wild' })) || wt.icon : wt.icon;
        var isImg = /\.svg$|\.png$|\.jpg$|\.gif$|\.webp$/i.test(iconPath);
        var icon = isImg
          ? '<img class="tcard-icon" src="' + iconPath + '" alt="' + wt.name + '"/>'
          : '<span class="tcard-emoji">' + iconPath + '</span>';
        var remain = (t.totalRes || 0) - (t.mined || 0);
        var resLine = wt.res
          ? '<div class="tcard-meta">' + D.resources[wt.res].name + ' <b>' + G.fmt(remain) + '</b>/' + G.fmt(t.totalRes || 0) + '</div>'
          : '<div class="tcard-meta tcard-muted">无资源 · 仅供占领</div>';
        // 守军属于侦察隐私，列表地图不展示；只有侦察流程会返回相关情报。
        var coordinateLine = '<div class="tcard-meta">坐标：(' + t.x + ',' + t.y + ')</div>';
        var occupantLine = t.occupiedByName
          ? '<div class="tcard-meta">统帅：<b>' + esc(t.occupiedByName) + '</b></div>' +
            '<div class="tcard-meta">军衔：<b>' + esc(t.occupiedByRankName || '列兵') + '</b></div>' +
            '<div class="tcard-meta">玩家声望：' + G.fmt(t.occupiedByPrestige || 0) + '</div>'
          : '';

        if (ownedView) {
          var hasGarrison = t.garrison && Object.values(t.garrison).some(function(v){ return v > 0; });
          var isGathering = Boolean(t.gathering);
          var statusHtml = '';
          var actions = '';

          if (isGathering) {
            var autoGather = t.gatherMode === 'auto';
            var gatherTip = G.gameNow() >= t.gatherEndAt ? (autoGather ? '等待自动收获返城' : '已采满，请收获') : '采集中 ' + (t.gatherEndAt ? World.fmtMarchTime(t.gatherEndAt) : '');
            statusHtml = '<div class="tcard-status tcard-status-busy">' + G.resourceIconHtml(t.gatherRes || wt.res) + ' ' + (autoGather ? '采集全自动' : '采集全手动') + ' · ' + gatherTip + '</div>';
            actions = autoGather ? '' : '<button class="tcard-btn tcard-btn-ok" onclick="Game.World.harvestWild(' + it.i + ')">收获</button>';
          } else if (t.gatherHarvested != null && hasGarrison) {
            statusHtml = '<div class="tcard-status">已收获 ' + G.fmt(t.gatherHarvested) + ' 资源 · 等待回城命令，抵达后入库</div>';
            actions = '<button class="tcard-btn tcard-btn-ok" onclick="Game.World.recallWild(' + it.i + ')">部队回城</button>';
          } else if (hasGarrison) {
            statusHtml = '<div class="tcard-status" style="color:var(--good,#4caf50);font-size:11px;">🛡 驻守中</div>';
            actions = (wt.res && remain > 0 ? '<button class="tcard-btn tcard-btn-ok" onclick="Game.World.startGatherWild(' + it.i + ')">采集</button>' : '') +
                      '<button class="tcard-btn" onclick="Game.World.recallWild(' + it.i + ')">撤回</button>' +
                      '<button class="tcard-btn tcard-btn-warn" onclick="Game.World.abandonWild(' + it.i + ')">放弃</button>';
          } else {
            var im = (s.world.marches || []).find(function(m){ return m.targetKind === 'wild' && m.action === 'station' && String(m.targetId) === String(t.id) && !m.returning; });
            if (im) {
              statusHtml = '<div class="tcard-status tcard-status-busy">进驻行军中 ' + World.fmtMarchTime(im.arriveAt) + '</div>';
            } else {
              statusHtml = '<div class="tcard-status tcard-muted">暂无驻守任务</div>';
            }
            actions = '<button class="tcard-btn tcard-btn-ok" onclick="Game.World.dispatchWild(' + it.i + ',\'station\')">派遣</button>' +
                      '<button class="tcard-btn tcard-btn-warn" onclick="Game.World.abandonWild(' + it.i + ')">放弃</button>';
          }
          statusHtml = World.renderGatherMarches(t.id, 'tcard-btn tcard-btn-ok') + statusHtml;
          if (wt.res && remain > 0) {
            actions = '<button class="tcard-btn tcard-btn-ok" onclick="Game.World.dispatchWild(' + it.i + ',\'gather\')">派兵采集</button>' + actions;
          }
          var ownedKey = targetCardKey('wild', t);
          return '<div class="tcard tcard-owned tcard-collapsible' + (targetCardExpanded(ownedKey) ? ' tcard-expanded' : '') + '" data-target-key="' + esc(ownedKey) + '">' +
            '<button type="button" class="tcard-mini-row" aria-expanded="' + targetCardExpanded(ownedKey) + '" onclick="Game.World.toggleTargetCard(this.parentElement)">' + icon +
              '<span class="tcard-mini-name">' + esc(wt.name) + ' <span class="tcard-lv">Lv.' + t.level + '</span></span>' +
              '<span class="tcard-mini-dist">' + mapIcon('city', 'tcard-mini-icon') + t.x + ',' + t.y + '</span>' +
              (isGathering ? '<span class="tcard-mini-badge tcard-mini-shield">' + (t.gatherMode !== 'auto' && t.gatherEndAt && G.gameNow() >= t.gatherEndAt ? '待收获' : '采集中') + '</span>' : (t.gatherHarvested != null && hasGarrison ? '<span class="tcard-mini-badge tcard-mini-warn">待回城</span>' : '')) +
              '<span class="tcard-mini-caret">▾</span>' +
            '</button>' +
            '<div class="tcard-expand">' +
              coordinateLine + occupantLine + resLine + statusHtml +
              '<div class="tcard-actions">' + actions + '</div>' +
            '</div>' +
          '</div>';
        }

        // 侦察只是获取守军情报, 不应作为行动的前置门槛 —— 玩家可选择盲打
        var acts = '<button class="tcard-btn tcard-btn-warn" onclick="Game.World.attackWild(' + it.i + ',\'campaign\')">讨伐</button>' +
                   '<button class="tcard-btn" onclick="Game.World.scoutWild(' + it.i + ')">' + (t.scouted ? '再侦察' : '侦察') + '</button>';
        var wildKey = targetCardKey('wild', t);
        return '<div class="tcard tcard-wild tcard-collapsible' + (targetCardExpanded(wildKey) ? ' tcard-expanded' : '') + '" data-target-key="' + esc(wildKey) + '">' +
          '<button type="button" class="tcard-mini-row" aria-expanded="' + targetCardExpanded(wildKey) + '" onclick="Game.World.toggleTargetCard(this.parentElement)">' + icon +
            '<span class="tcard-mini-name">' + esc(wt.name) + ' <span class="tcard-lv">Lv.' + t.level + '</span></span>' +
            '<span class="tcard-mini-dist">' + mapIcon('view', 'tcard-mini-icon') + distanceText(it.d) + '格</span>' +
            '<span class="tcard-mini-caret">▾</span>' +
          '</button>' +
          '<div class="tcard-expand">' +
            coordinateLine + occupantLine + resLine +
            '<div class="tcard-actions">' + acts + '</div>' +
          '</div>' +
        '</div>';
      }

      // 陆地寇卡：不需要宣战，可直接进攻或侦察
      function renderNpcCard(it) {
        var n = it.n;
        var seaNpc = !!n.sea;
        var vessel = n.level >= 21 || n.name.indexOf('航母') >= 0 ? 'carrier'
          : n.level >= 11 && n.name.indexOf('海域守军') >= 0 ? 'battleship'
          : n.level >= 4 && n.name.indexOf('海域守军') >= 0 ? 'destroyer'
          : n.name.indexOf('海域守军') >= 0 ? 'sub'
          : n.name.indexOf('潜艇') >= 0 ? 'sub'
          : n.name.indexOf('驱逐舰') >= 0 ? 'destroyer' : 'battleship';
        var nScouted = getScouted(n.x, n.y);
        var info = nScouted
          ? '<div class="tcard-meta">守军 ' + armyText(nScouted.data.army, true) + '</div>' +
            (nScouted.data.resources ? '<div class="tcard-meta tcard-res">资源 ' + G.fmt(nScouted.data.resources.gold || 0) + '金 ' + G.fmt(nScouted.data.resources.food || 0) + '粮</div>' : '')
          : '<div class="tcard-meta tcard-muted">敌情未知 · 需侦查</div>';
        var actions = n.defeated
          ? '<span class="tcard-badge tcard-badge-done">已击败</span>'
          : '<button class="tcard-btn tcard-btn-warn" onclick="Game.World.attack(\'' + it.kind + '\',' + it.i + ',\'campaign\')">讨伐</button>' +
            '<button class="tcard-btn" onclick="Game.World.attack(\'' + it.kind + '\',' + it.i + ',\'scout\')">侦察</button>';
        var cmdLine = n.commanderName
          ? '<div class="tcard-meta">敌将 <b style="color:var(--accent,#e0a040)">' + esc(n.commanderName) + '</b></div>'
          : '';
        var npcKey = targetCardKey('bandit', n);
        var baseName = seaNpc ? '日寇舰队' : '寇';
        var cardTitle = baseName + (n.level != null ? ' · ' + esc(n.level) + '级' : '');
        return '<div class="tcard tcard-npc tcard-collapsible' + (n.defeated ? ' tcard-done' : '') + (targetCardExpanded(npcKey) ? ' tcard-expanded' : '') + '" data-target-key="' + esc(npcKey) + '">' +
          '<button type="button" class="tcard-mini-row" aria-expanded="' + targetCardExpanded(npcKey) + '" onclick="Game.World.toggleTargetCard(this.parentElement)">' +
            (seaNpc ? '<img class="tcard-icon" src="img/npc/japanese-navy/' + vessel + '.webp" alt="">' : '<span class="tcard-emoji tcard-mini-icon">' + mapIcon('bandit') + '</span>') +
            '<span class="tcard-mini-name">' + esc(cardTitle) + '</span>' +
            '<span class="tcard-mini-dist">' + mapIcon('view', 'tcard-mini-icon') + distanceText(it.d) + '格</span>' +
            '<span class="tcard-mini-caret">▾</span>' +
          '</button>' +
          '<div class="tcard-expand">' +
            cmdLine + info +
            '<div class="tcard-actions">' + actions + '</div>' +
          '</div>' +
        '</div>';
      }

      // 玩家城卡 (紧凑列表 + 点击展开)
      // 默认只显示: 图标 + 名字 + 坐标 + 状态徽章, 单行可堆 2-3 个
      // 点击整行展开为完整卡 (含统帅、状态、驻军、行动按钮)
      function renderPlayerCard(it) {
        var p = it.p;
        // 列表城市记录没有 kind；使用大地图的城市图标映射，保持两种视图一致。
        var cityIconPath = (G.WorldMap && typeof G.WorldMap.icon === 'function')
          ? G.WorldMap.icon(Object.assign({}, p, { kind: 'player' }))
          : 'img/cities/player-city-preview-map.png';
        var now = G.gameNow();
        // 主城坐标是本城的最终判定，避免旧 player_cities.ownerId 残留造成误判。
        var isSelfCity = p._selfCity || p.selfCity ||
          (cityPos.x != null && p.x === cityPos.x && p.y === cityPos.y) ||
          (s.player && p.ownerId && String(p.ownerId) === String(s.player.id));
        var cityShielded = p.shieldUntil != null ? p.shieldUntil > now : p.cityState === 'shield';
        // 自己的分城不继承当前城市的护盾，只有坐标匹配的当前城市读取本地状态。
        if (isSelfCity && p.x === cityPos.x && p.y === cityPos.y && G.Core.getCityStatus) cityShielded = G.Core.getCityStatus() === 'shield';
        var cooling = p.coolAt > now;
        // 普通玩家的攻击资格只在完整、未过期的个人战争窗口内生效。
        var warDeclared = !isSelfCity && p.warAt > 0 && p.warEndAt > now;
        var warActive = !isSelfCity && p.guildRelation !== 'friendly' && (p.guildRelation === 'hostile' || (warDeclared && now >= p.warAt));
        var preWar = warDeclared && p.guildRelation !== 'hostile' && p.guildRelation !== 'friendly' && now < p.warAt;
        function fmtMs(ms) {
          var wh = Math.floor(ms / 3600000), wm = Math.floor((ms % 3600000) / 60000), ws = Math.floor((ms % 60000) / 1000);
          return wh > 0 ? wh + ':' + String(wm).padStart(2, '0') + ':' + String(ws).padStart(2, '0') : wm + ':' + String(ws).padStart(2, '0');
        }
        var remStr = '';
        if (warActive && p.guildRelation !== 'hostile') remStr = fmtMs(p.warEndAt - now);
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

        var myPrestige = (s.player && s.player.prestige != null)
          ? s.player.prestige
          : (s.prestige != null ? s.prestige : null);
        var displayPrestige = (isSelfCity && myPrestige != null)
          ? myPrestige
          : (p.prestige != null ? p.prestige : 0);

        // ---- 展开态内容 ----
        var pScouted = getScouted(p.x, p.y);
        var stateMap = G.Constants.cityStateNames;
        var expandHtml =
          '<div class="tcard-expand">' +
            '<div class="tcard-meta">城市 ' + esc(p.name || '未知城市') + '</div>' +
            '<div class="tcard-meta">统帅：<b>' + esc(p.commanderName || p.playerName || '未知') + '</b></div>' +
            '<div class="tcard-meta">军衔：<b>' + esc(p.militaryRankName || (G.getMilitaryRankTierInfo ? G.getMilitaryRankTierInfo(p.militaryRank || 1).name : '列兵')) + '</b></div>' +
            '<div class="tcard-meta">状态 <b>' + (stateMap[p.cityState] || '和平') + '</b></div>' +
            '<div class="tcard-meta">声望：' + G.fmt(displayPrestige) + '</div>' +
            (warActive ? (p.guildRelation === 'hostile' ? '<div class="tcard-meta">敌对军团，可直接交战</div>' : '<div class="tcard-meta" id="war-timer-' + it.i + '">交战剩余 ' + remStr + '</div>') : '') +
            (preWar ? '<div class="tcard-meta" id="war-timer-' + it.i + '">宣战倒计时 ' + remStr + '</div>' : '') +
            '<div class="tcard-actions">' + renderPlayerActions(it, p, cooling, warActive, preWar) + '</div>' +
          '</div>';

        // 只在顶部 mini-row 上绑点击切换, 内容区(展开后的按钮/信息)点击不再冒泡到这里
        // 展开状态用对象存储: Object[pid] = true; 渲染时用 in / hasOwnProperty 判断
        var exp = s.world._expandedPlayerIds || {};
        var isExp = Object.prototype.hasOwnProperty.call(exp, String(p.id));
        return '<div class="tcard tcard-player tcard-player-mini tcard-collapsible' + (isExp ? ' tcard-expanded' : '') + '" data-player-id="' + p.id + '">' +
          '<div class="tcard-mini-row" onclick="Game.World.togglePlayerCard(this.parentElement)">' +
            '<span class="tcard-city-icon' + (cityShielded ? ' is-shielded' : '') + '"><img class="tcard-icon" src="' + cityIconPath + '" alt="城市"/>' + (cityShielded ? '<span class="tcard-city-shield" title="护盾生效">盾</span>' : '') + '</span>' +
            '<span class="tcard-mini-name">' + esc(p.name) + '</span>' +
            combatIcon +
            '<span class="tcard-mini-prestige">声望：' + G.fmt(displayPrestige) + '</span>' +
            '<span class="tcard-mini-coord">(' + p.x + ',' + p.y + ')</span>' +
            '<span class="tcard-mini-dist">' + distanceText(it.d) + '格</span>' +
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
          if (p.readyAt > G.gameNow()) return '<button class="tcard-btn" disabled>城市建设中</button>';
          var currentCity = String((s.player || {}).activeCityId) === String(p.id) ||
            (s.cityOverview && s.cityOverview.cities || []).some(function (city) { return city.current && String(city.id) === String(p.id); });
          return '<button class="tcard-btn tcard-btn-ok" onclick="event.stopPropagation();Game.Cities.enter(' + p.id + ')">进入城市</button>' +
            (currentCity ? '' : '<button class="tcard-btn" onclick="event.stopPropagation();Game.Cities.openTransfer(' + p.id + ',\'transport\')">运输</button>' +
              '<button class="tcard-btn" onclick="event.stopPropagation();Game.Cities.openTransfer(' + p.id + ',\'rebase\')">派遣</button>');
        }
        if (p._readonlyTarget) {
          return '<button class="tcard-btn" disabled>需靠近后侦察</button>';
        }
        if (p.surrenderFrozen) {
          return '<span class="tcard-meta">归降移交中 · 城市只读</span><button class="tcard-btn" disabled>暂不可操作</button>';
        }
        if (p.guildmate && !(p.readyAt > G.gameNow())) {
          return '<button class="tcard-btn" onclick="event.stopPropagation();Game.Cities.openSupport(' + Number(p.ownerId) + ',\'transport\',' + p.id + ')">运输</button>' +
            '<button class="tcard-btn tcard-btn-ok" onclick="event.stopPropagation();Game.Cities.openSupport(' + Number(p.ownerId) + ',\'reinforce\',' + p.id + ')">驻防</button>' +
            '<button class="tcard-btn" onclick="event.stopPropagation();Game.World.offerSurrender(' + it.i + ')">劝降</button>';
        }
        if (cooling) {
          return '<button class="tcard-btn" onclick="event.stopPropagation();Game.World.offerSurrender(' + it.i + ')">劝降</button>' +
                 '<button class="tcard-btn" onclick="event.stopPropagation();Game.World.attack(\'player\',' + it.i + ',\'scout\')">侦察</button>';
        } else if (p.guildRelation === 'friendly') {
          return '<span class="tcard-meta">友好军团成员不可宣战或交战</span>' +
                 '<button class="tcard-btn" onclick="event.stopPropagation();Game.World.offerSurrender(' + it.i + ')">劝降</button>' +
                 '<button class="tcard-btn" onclick="event.stopPropagation();Game.World.attack(\'player\',' + it.i + ',\'scout\')">侦察</button>';
        } else if (warActive) {
          return '<button class="tcard-btn tcard-btn-warn" onclick="event.stopPropagation();Game.World.attack(\'player\',' + it.i + ',\'campaign\')">讨伐</button>' +
                 '<button class="tcard-btn" onclick="event.stopPropagation();Game.World.offerSurrender(' + it.i + ')">劝降</button>' +
                 '<button class="tcard-btn" onclick="event.stopPropagation();Game.World.attack(\'player\',' + it.i + ',\'scout\')">侦察</button>';
        } else if (preWar) {
          return '<button class="tcard-btn" id="war-start-timer-' + it.i + '" disabled>开战倒计时 ' + (function(){
            var ms = p.warAt - G.gameNow();
            var wm=Math.floor(ms/60000),ws=Math.floor((ms%60000)/1000);
            return wm+':'+String(ws).padStart(2,'0');
          })() + '</button><button class="tcard-btn" onclick="event.stopPropagation();Game.World.offerSurrender(' + it.i + ')">劝降</button>' +
                 '<button class="tcard-btn" onclick="event.stopPropagation();Game.World.attack(\'player\',' + it.i + ',\'scout\')">侦察</button>';
        } else {
          return '<button class="tcard-btn" onclick="event.stopPropagation();Game.World.offerSurrender(' + it.i + ')">劝降</button>' +
                 '<button class="tcard-btn tcard-btn-warn" onclick="event.stopPropagation();Game.World.declareWar(\'player\',' + it.i + ')">宣战</button>' +
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
           '<span class="msb-loc">' + mapIcon('city') + '城市 (' + cityX + ',' + cityY + ')</span>' +
           (viewMoved ? '<span class="msb-sep">·</span><span class="msb-loc msb-view">' + mapIcon('view') + '视角 (' + px + ',' + py + ')</span>' : '') +
           '<span class="msb-sep">·</span>' +
           '<span class="msb-meta">地图 ' + W.size + '×' + W.size + '</span>' +
           '<span class="msb-sep">·</span>' +
           '<span class="msb-meta">范围：' + (scanR > 0 ? scanR + '格' : '全图') + '</span>' +
           '</div>';

      // 2. 工具栏：搜索 + 快捷跳转
      // 查看范围（格数）；0 表示全图。
      var curRadius = scanR;
      var radiusOptions = G.Constants.mapRadiusOptions;
      var radSel = '<select class="qty msb-radius" id="viewRadiusSel" onchange="Game.World.setViewRadius(this.value)" aria-label="地图查看范围" title="以当前视角为中心，选择查看范围" style="display:none !important;">' +
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
              '<button class="qt-btn" onclick="Game.World.locateClosest(\'wild\')" title="跳转最近未占领野地">' + mapIcon('wild') + '最近野地</button>' +
              '<button class="qt-btn" onclick="Game.World.locateClosest(\'bandit\')" title="跳转最近日寇">' + mapIcon('bandit') + '最近日寇</button>' +
              '<button class="qt-btn" onclick="Game.World.locateClosest(\'player\')" title="跳转最近玩家">' + mapIcon('player') + '最近玩家</button>' +
              '<button class="qt-btn" onclick="Game.World.locateClosest(\'owned\')" title="跳转最近已占领野地">' + mapIcon('territory') + '我的领地</button>' +
            '</div>' +
            '</div>';

      // 3. Tabs
      var total = wildsNearby.length + npcsNearby.length + playersNearby.length;
      h += '<div class="map-tabs">';
      var ownedCount = s.wilds && s.wilds.ownedCount != null ? s.wilds.ownedCount : owned.length;
      var ownedLimit = s.wilds && s.wilds.cap != null ? '/' + s.wilds.cap : '';
      if (activeTab === 'owned') {
        h += '<div class="map-tab active" onclick="Game.World.setTab(\'owned\')">本城已占 <span class="mt-count">' + ownedCount + ownedLimit + '</span></div>' +
             '<div class="map-tab map-tab-back" onclick="Game.World.setTab(\'all\')" title="返回查看周边全部目标" style="flex:1.5;color:var(--accent);">‹ 返回全部目标</div>';
      } else {
        h += '<div class="map-tab ' + (activeTab === 'all' ? 'active' : '') + '" onclick="Game.World.setTab(\'all\')">全部 <span class="mt-count">' + total + '</span></div>' +
             '<div class="map-tab ' + (activeTab === 'wild' ? 'active' : '') + '" onclick="Game.World.setTab(\'wild\')">野地 <span class="mt-count">' + wildsNearby.length + '</span></div>' +
             '<div class="map-tab ' + (activeTab === 'npc' ? 'active' : '') + '" onclick="Game.World.setTab(\'npc\')">日寇 <span class="mt-count">' + npcsNearby.length + '</span></div>' +
             '<div class="map-tab ' + (activeTab === 'player' ? 'active' : '') + '" onclick="Game.World.setTab(\'player\')">玩家 <span class="mt-count">' + playersNearby.length + '</span></div>' +
             '<div class="map-tab ' + (activeTab === 'owned' ? 'active' : '') + '" onclick="Game.World.setTab(\'owned\')">本城已占 <span class="mt-count">' + ownedCount + ownedLimit + '</span></div>';
      }
      h += '<div class="map-tabs-sort">' +
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
             '<div class="me-text">' + (activeTab === 'owned' ? '暂无已占领的野地' : '视野内无可操作目标') + '</div>' +
             '<div class="me-hint">' + (activeTab === 'owned' ? '可前往世界地图侦查并讨伐占领野地' : '点击上方「最近野/寇/玩家/我占」快速跳转,或用底部方向键探索') + '</div>' +
             '</div>';
      } else {
        h += '<div class="tcard-grid">' + cards.join('') + '</div>';
      }

      // 5. 底部固定方向栏
      h += '<div class="map-control-bar">' +
           '<button class="mcb-side mcb-home" onclick="Game.World.jumpToMyCity()" title="回我城">' + mapIcon('home') + '我城</button>' +
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
      if (G.WorldMap) v.insertAdjacentHTML('afterbegin', '<div class="world-map-list-switch"><button type="button" class="page-back-button" onclick="Game.WorldMap.setMode(\'map\')"><span>查看大地图 ›</span></button></div>');
      var now = G.gameNow();
      this._warNextTransition = 0;
      for (var wi = 0; wi < s.world.playerCities.length; wi++) {
        var wp = s.world.playerCities[wi];
        if (!wp.selfCity && wp.guildRelation !== 'hostile' && wp.guildRelation !== 'friendly' && wp.warAt > 0 && wp.warEndAt > now) {
          var transition = wp.warAt > now ? wp.warAt : wp.warEndAt;
          if (!this._warNextTransition || transition < this._warNextTransition) this._warNextTransition = transition;
        }
      }
      if (this._warNextTransition) this.startWarTimer();
      else if (this._warTimer) {
        clearInterval(this._warTimer);
        this._warTimer = null;
      }
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

    toggleTargetCard: function (rowEl) {
      if (!rowEl) return;
      var key = rowEl.getAttribute('data-target-key');
      if (!key) return;
      var world = Core.state.world;
      if (!world._expandedTargetIds) world._expandedTargetIds = {};
      var expanded = rowEl.classList.toggle('tcard-expanded');
      if (expanded) world._expandedTargetIds[key] = true;
      else delete world._expandedTargetIds[key];
      rowEl.querySelector('.tcard-mini-row').setAttribute('aria-expanded', String(expanded));
    },

    alertCountdown: function (arriveAt) {
      var deadline = Number(arriveAt);
      if (!Number.isFinite(deadline)) return '<b>时间未知</b>';
      var text = deadline <= G.gameNow() ? '已到达，等待战斗结果' : this.fmtMarchTime(deadline);
      return '<b class="alert-countdown" data-arrive-at="' + deadline + '">' + text + '</b>';
    },

    stopAlertTimer: function () {
      if (this._alertTimer != null) clearInterval(this._alertTimer);
      this._alertTimer = null;
    },

    startAlertTimer: function (view) {
      this.stopAlertTimer();
      if (!view.querySelectorAll || (!view.querySelectorAll('[data-arrive-at]').length && !view.querySelectorAll('[data-gather-march-id]').length && !view.querySelectorAll('[data-progress-bar]').length)) return;
      var self = this;
      this._alertTimer = setInterval(function () {
        if (Core.route !== 'alerts' || view.isConnected === false) {
          self.stopAlertTimer();
          return;
        }
        var now = G.gameNow();
        view.querySelectorAll('[data-arrive-at]').forEach(function (el) {
          var deadline = Number(el.getAttribute('data-arrive-at'));
          var text = deadline <= now ? '已到达，等待战斗结果' : self.fmtMarchTime(deadline);
          if (el.textContent !== text) el.textContent = text;
        });
        view.querySelectorAll('[data-progress-bar]').forEach(function (el) {
          if (!el.className || el.className.indexOf('term-pbar') < 0 || el.getAttribute('data-progress-bar') !== 'true') return;
          var deadline = Number(el.getAttribute('data-progress-deadline'));
          var duration = Number(el.getAttribute('data-progress-duration')) || 180000;
          var departAt = deadline - duration;
          var pct = deadline <= now ? 100 : Math.max(0, Math.min(99, Math.round(((now - departAt) / duration) * 100)));
          var barText = self.renderTextProgressBar(pct);
          if (el.textContent !== barText) el.textContent = barText;
        });
        view.querySelectorAll('[data-gather-march-id]').forEach(function (el) {
          var march = (Core.state.world.marches || []).find(function (item) {
            return String(item.id) === el.getAttribute('data-gather-march-id');
          });
          if (march) el.textContent = self.gatherMarchStatus(march);
        });
        view.querySelectorAll('[data-accelerate-id]').forEach(function (button) {
          var march = (Core.state.world.marches || []).find(function (item) {
            return String(item.id) === button.getAttribute('data-accelerate-id');
          });
          button.disabled = !self.canAccelerateMarch(march, now);
        });
      }, 1000);
    },

    hasBattleAlert: function () {
      var s = Core.state && Core.state.world;
      if (!s) return false;
      if (s._newBattleAlert) return true;
      var marches = s.marches || [];
      if (!marches.length) return false;
      var now = G.gameNow();
      var ack = s._acknowledgedMarchArrivals || {};
      for (var i = 0; i < marches.length; i++) {
        var m = marches[i];
        if (!m || m.returning) continue;
        var arrived = m.arriveAt != null && isFinite(Number(m.arriveAt)) && now >= Number(m.arriveAt);
        var inBattleOrWaiting = m.inBattle || m.battleId != null || m.waitingForBattle;
        if (arrived || inBattleOrWaiting) {
          var idKey = String(m.id != null ? m.id : (m.fromX + ':' + m.fromY + '->' + m.targetX + ':' + m.targetY));
          if (!ack[idKey]) return true;
        }
      }
      return false;
    },

    triggerBattleAlert: function (march) {
      var s = Core.state && Core.state.world;
      if (!s) return;
      s._newBattleAlert = true;
      if (march && march.id != null) march._arrivalAlerted = true;
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
    },

    acknowledgeAlerts: function () {
      var s = Core.state && Core.state.world;
      if (!s) return;
      s._newBattleAlert = false;
      s._alertsViewedAt = G.gameNow();
      s.alertsViewed = true;
      var marches = s.marches || [];
      var ack = s._acknowledgedMarchArrivals || (s._acknowledgedMarchArrivals = {});
      var now = G.gameNow();
      for (var i = 0; i < marches.length; i++) {
        var m = marches[i];
        if (!m) continue;
        var arrived = m.arriveAt != null && isFinite(Number(m.arriveAt)) && now >= Number(m.arriveAt);
        if (arrived || m.inBattle || m.battleId != null || m.waitingForBattle) {
          var idKey = String(m.id != null ? m.id : (m.fromX + ':' + m.fromY + '->' + m.targetX + ':' + m.targetY));
          ack[idKey] = true;
        }
      }
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
    },

    /**
     * 离线演示的截击抵达处理；联网行军及双方战报只由服务端结算。
     */
    resolveInterceptionArrival: function (march) {
      if (!march || march.action !== 'intercept_scout') return;
      if (G.API && G.API.worldDispatch) return;
      var s = Core.state;
      var now = typeof G.gameNow === 'function' ? G.gameNow() : Date.now();
      var cp = (s.world && (s.world.cityPos || s.world.pos)) || { x: 342, y: 681 };

      if (!march.returning) {
        // 第一阶段：抵达拦截空域，发生空战交火
        var totalAntiAir = 0;
        var army = march.army || {};
        for (var uid in army) {
          var udef = D.units[uid];
          if (udef) totalAntiAir += (udef.atkAir || 0) * (army[uid] || 0);
        }
        var shotDown = (totalAntiAir >= 10 || Boolean(army.fighter) || Boolean(army.armored));

        if (shotDown) {
          // 击落敌方高空侦察机，从 incoming 中移除
          if (s.world && s.world.incoming) {
            s.world.incoming = s.world.incoming.filter(function (im) {
              if (march.targetId != null && (String(im.id) === String(march.targetId) || String(im.marchId) === String(march.targetId))) {
                return false;
              }
              return true;
            });
          }
          G.toast('🎯【截击空战大捷】我方截击编队在 [' + march.targetX + ',' + march.targetY + '] 空域成功击落敌方高空侦察机！敌方侦查企图彻底瓦解！');
        } else {
          G.toast('⚠️ 我方截击编队火力不足，未能完全击落敌机！');
        }

        // 部队完成交火，掉头返航
        march.returning = true;
        march.fromX = march.targetX;
        march.fromY = march.targetY;
        march.originX = march.targetX;
        march.originY = march.targetY;
        march.targetX = cp.x;
        march.targetY = cp.y;
        var returnDur = march.duration || march.baseDurationMs || 30000;
        march.startAt = now;
        march.arriveAt = now + returnDur;

        if (G.WorldMap && G.WorldMap.invalidate) G.WorldMap.invalidate();
        if (G.Core && G.Core.render) G.Core.render();
      } else {
        // 第二阶段：返航归建基地，归还兵力入库
        var returnArmy = march.army || {};
        var baseArmy = s.army || (s.barracks && s.barracks.army);
        if (baseArmy) {
          for (var ruid in returnArmy) {
            baseArmy[ruid] = (baseArmy[ruid] || 0) + (returnArmy[ruid] || 0);
          }
        }
        if (s.world && s.world.marches) {
          s.world.marches = s.world.marches.filter(function (m) {
            return String(m.id) !== String(march.id);
          });
        }
        G.toast('🛬 截击机群已完成拦截巡航任务，安全返航归建基地。');
        if (G.WorldMap && G.WorldMap.invalidate) G.WorldMap.invalidate();
        if (G.Core && G.Core.render) G.Core.render();
      }
    },

    checkMarchArrivals: function () {
      var s = Core.state && Core.state.world;
      if (!s) return false;
      var marches = (s.marches) || (Core.state && Core.state.world && Core.state.world.marches) || [];
      var now = typeof G.gameNow === 'function' ? G.gameNow() : Date.now();
      var ack = s._acknowledgedMarchArrivals || {};
      var hasNew = false;
      for (var i = 0; i < marches.length; i++) {
        var m = marches[i];
        if (!m) continue;
        var arrived = m.arriveAt != null && isFinite(Number(m.arriveAt)) && now >= Number(m.arriveAt);
        if (m.action === 'intercept_scout' && arrived) {
          if (!(G.API && G.API.worldDispatch)) {
            this.resolveInterceptionArrival(m);
            hasNew = true;
          }
          continue;
        }
        if (m.returning) continue;
        var inBattleOrWaiting = m.inBattle || m.battleId != null || m.waitingForBattle;
        if (arrived || inBattleOrWaiting) {
          var idKey = String(m.id != null ? m.id : (m.fromX + ':' + m.fromY + '->' + m.targetX + ':' + m.targetY));
          if (!ack[idKey] && !m._arrivalAlerted) {
            m._arrivalAlerted = true;
            hasNew = true;
          }
        }
      }
      if (hasNew) {
        s._newBattleAlert = true;
        if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
      }
      return hasNew;
    },

    renderTextProgressBar: function (pct) {
      var p = Math.max(0, Math.min(100, Math.round(pct)));
      var filled = Math.min(10, Math.max(0, Math.floor(p / 10)));
      var bar = '';
      for (var i = 0; i < filled; i++) bar += '■';
      for (var j = filled; j < 10; j++) bar += '□';
      return '[' + bar + '] ' + p + '%';
    },

    setAlertsFilter: function (filter) {
      this._alertsFilter = filter;
      if (G.Core && G.Core.render) G.Core.render();
    },

    refreshAlerts: function () {
      var self = this;
      var t0 = Date.now();
      if (G.API && G.API.getGameState) {
        G.API.getGameState(true).then(function (state) {
          if (G.API.applyState) G.API.applyState(state);
          self._radarLatency = Math.max(8, Date.now() - t0);
          if (G.toast) G.toast('雷达信号已刷新 (' + self._radarLatency + 'ms)');
          if (G.Core && G.Core.render) G.Core.render();
        }).catch(function () {
          self._radarLatency = Math.max(12, Date.now() - t0);
          if (G.toast) G.toast('雷达信号已刷新 (' + self._radarLatency + 'ms)');
          if (G.Core && G.Core.render) G.Core.render();
        });
      } else {
        self._radarLatency = 12;
        if (G.toast) G.toast('雷达信号已刷新 (12ms)');
        if (G.Core && G.Core.render) G.Core.render();
      }
    },

    /**
     * 获取玩家所有城市及其当前护盾状态（支持主城与多座分城）
     */
    getPlayerCities: function () {
      var s = Core.state;
      var list = [];
      var overviewCities = (s.cityOverview && s.cityOverview.cities) || [];
      if (overviewCities.length > 0) {
        for (var i = 0; i < overviewCities.length; i++) {
          var c = overviewCities[i];
          list.push({
            id: c.id != null ? c.id : ('city_' + i),
            slot: c.slot != null ? c.slot : (c.citySlot != null ? c.citySlot : i),
            name: c.name || (c.main ? '主基地' : ('分城 ' + i)),
            x: c.x != null ? c.x : (s.world && s.world.cityPos ? s.world.cityPos.x : 0),
            y: c.y != null ? c.y : (s.world && s.world.cityPos ? s.world.cityPos.y : 0),
            main: Boolean(c.main || c.citySlot === 0),
            current: Boolean(c.current),
            shieldUntil: Number(c.shieldUntil != null ? c.shieldUntil : (c.current && s.cityState ? s.cityState.shieldUntil : 0))
          });
        }
      } else {
        // 保底当前主基地
        var p = s.player || {};
        var cpos = (s.world && s.world.cityPos) || { x: 342, y: 681 };
        var cs = s.cityState || {};
        list.push({
          id: 'main',
          slot: 0,
          name: p.cityName || '主基地',
          x: cpos.x,
          y: cpos.y,
          main: true,
          current: true,
          shieldUntil: Number(cs.shieldUntil || 0)
        });
      }
      return list;
    },

    /**
     * 点击开启护盾入口（军情卡片[开罩]或快捷键触发）
     * @param {number|string} [incomingIdxOrCityId] - 军情索引或指定城市标识
     */
    activateCityShield: function (incomingIdxOrCityId) {
      var s = Core.state;
      var preferredCityId = null;
      if (typeof incomingIdxOrCityId === 'number' && s.world && s.world.incoming) {
        var im = s.world.incoming[incomingIdxOrCityId];
        if (im) {
          var cities = this.getPlayerCities();
          var matched = cities.find(function (c) {
            return (im.targetX != null && im.targetY != null && c.x === im.targetX && c.y === im.targetY) ||
              (im.targetName && (c.name === im.targetName || im.targetName.indexOf(c.name) >= 0));
          });
          if (matched) preferredCityId = matched.id;
        }
      } else if (typeof incomingIdxOrCityId === 'string') {
        preferredCityId = incomingIdxOrCityId;
      }
      return this.openCityShieldModal(preferredCityId);
    },

    /**
     * 确认并执行为所选城市开启护盾，扣除对应钻石
     * @param {Array<string|number>} selectedCityIds - 选中的城市ID列表
     * @returns {Promise<any>}
     */
    confirmCityShield: function (selectedCityIds) {
      if (!selectedCityIds || !selectedCityIds.length) {
        G.toast('请至少选择一座需要开启护盾的城市！');
        return Promise.resolve({ success: false, reason: 'no_city_selected' });
      }

      selectedCityIds = selectedCityIds.filter(function (id, index, ids) {
        return ids.map(String).indexOf(String(id)) === index;
      });
      var s = Core.state;
      var diamondPrice = (G.Constants && G.Constants.shieldPricePerCity) || 200;
      var totalCost = selectedCityIds.length * diamondPrice;
      var r = s.resources || {};
      var curDiamond = r.diamond != null ? Number(r.diamond) : 0;

      if (curDiamond < totalCost) {
        var msg = '⚠️ 钻石余额不足！开启 ' + selectedCityIds.length + ' 座城市护盾需 ' + totalCost + ' 钻石，当前仅拥有 ' + curDiamond + ' 钻石。';
        G.toast(msg);
        return Promise.resolve({ success: false, reason: 'insufficient_diamond', cost: totalCost, diamond: curDiamond });
      }

      if (this._cityShieldPending) return this._cityShieldPending;
      if (!G.API || !G.API.client || !G.API.client.post) {
        G.toast('护盾服务暂不可用，请稍后重试');
        return Promise.resolve({ success: false, reason: 'unavailable' });
      }
      var self = this;
      // 护盾必须由服务端扣钻并持久化；失败时保留原状态，不能显示本地虚假的免战时间。
      this._cityShieldPending = G.API.client.post('/game/cities/shield', { cityIds: selectedCityIds }).then(function (result) {
        if (!result || !result.success || !result.state) throw new Error('护盾未能开启，请刷新后重试');
        G.API.applyState(result.state);
        G.toast('🛡️ 已成功为 ' + result.cities.join('、') + ' 共 ' + result.count + ' 座城市开启 8 小时城防护盾，扣除 💎 ' + result.cost + ' 钻石！');
        if (G.WorldView && G.WorldView.invalidate) G.WorldView.invalidate();
        if (G.WorldMap && G.WorldMap.invalidate) G.WorldMap.invalidate();
        if (Core.render) Core.render();
        return result;
      }).catch(function (err) {
        G.toast(err.message || '护盾开启失败，请稍后重试');
        return { success: false, reason: 'request_failed' };
      }).finally(function () {
        self._cityShieldPending = null;
      });
      return this._cityShieldPending;
    },

    /**
     * 打开选择单个城市或所有城市开启护盾的弹窗 (Modern Clean Text-SLG)
     */
    openCityShieldModal: function (preferredCityId) {
      var self = this;
      var cities = this.getPlayerCities();
      if (typeof document === 'undefined') {
        // Node.js 或测试环境下安全交互
        var defaultIds = preferredCityId ? [preferredCityId] : cities.map(function (c) { return c.id; });
        return this.confirmCityShield(defaultIds);
      }

      // 移除可能存在的旧弹窗
      var old = document.getElementById('termCityShieldMask');
      if (old && old.parentNode) old.parentNode.removeChild(old);

      var s = Core.state;
      var r = s.resources || {};
      var curDiamond = r.diamond != null ? Number(r.diamond) : 0;
      var diamondPrice = (G.Constants && G.Constants.shieldPricePerCity) || 200;
      var now = G.gameNow();

      // 默认选中
      var selectedMap = {};
      if (preferredCityId) {
        selectedMap[preferredCityId] = true;
      } else {
        var currentCity = cities.find(function (c) { return c.current; }) || cities[0];
        if (currentCity) selectedMap[currentCity.id] = true;
      }

      var mask = document.createElement('div');
      mask.id = 'termCityShieldMask';
      mask.className = 'modal-mask';
      mask.setAttribute('role', 'dialog');
      mask.setAttribute('aria-modal', 'true');

      function renderContent() {
        var selectedCount = Object.keys(selectedMap).filter(function (k) { return selectedMap[k]; }).length;
        var totalCost = selectedCount * diamondPrice;
        var isAllSelected = selectedCount > 0 && selectedCount === cities.length;

        var h = '<div class="modal-card term-shield-dialog">';
        h += '  <div class="term-shield-heading">';
        h += '    <span>🛡️ 城防护盾系统 · 选择开启目标</span>';
        h += '    <button type="button" class="btn sm" id="termShieldCloseBtn" style="padding:0 6px;">✕</button>';
        h += '  </div>';

        h += '  <div class="term-shield-body">';
        h += '    <div class="term-shield-tip">';
        h += '      • <b>单城独立防护</b>: 护盾针对单个城市独立生效，生效期间该城免疫敌袭与侦查。<br>';
        h += '      • <b>消耗规则</b>: 💎 ' + diamondPrice + ' 钻石 / 座城（时长 8 小时，生效中开启则自动叠加）。';
        h += '    </div>';

        h += '    <div class="term-shield-toolbar">';
        h += '      <label style="cursor:pointer;display:inline-flex;align-items:center;gap:6px;font-weight:600;">';
        h += '        <input type="checkbox" id="termShieldSelectAll" ' + (isAllSelected ? 'checked' : '') + '> [全选所有城市 (' + cities.length + '座)]';
        h += '      </label>';
        h += '      <span style="color:#94a3b8;font-size:11px;">已选: ' + selectedCount + ' / ' + cities.length + '</span>';
        h += '    </div>';

        h += '    <div class="term-shield-city-list">';
        cities.forEach(function (c) {
          var isChecked = Boolean(selectedMap[c.id]);
          var isShielded = c.shieldUntil && c.shieldUntil > now;
          var remStr = '';
          if (isShielded) {
            var rem = Math.ceil((c.shieldUntil - now) / 1000);
            var m = Math.floor(rem / 60), sRem = rem % 60;
            remStr = m > 0 ? (m + '分' + sRem + '秒') : (sRem + '秒');
          }

          h += '    <div class="term-shield-city-row ' + (isChecked ? 'selected' : '') + '" data-city-row-id="' + c.id + '">';
          h += '      <div class="term-shield-city-left">';
          h += '        <input type="checkbox" class="term-city-chk" data-city-id="' + c.id + '" ' + (isChecked ? 'checked' : '') + '>';
          h += '        <div class="term-shield-city-info">';
          h += '          <div class="term-shield-city-name">' + esc(c.name) + ' [' + c.x + ',' + c.y + '] ' + (c.main ? '<span style="color:#38bdf8;font-size:11px;">[主城]</span>' : '<span style="color:#f59e0b;font-size:11px;">[分城]</span>') + (c.current ? ' <span style="color:#10b981;font-size:11px;">[当前]</span>' : '') + '</div>';
          h += '          <div class="term-shield-city-state ' + (isShielded ? 'active' : 'none') + '">';
          h += isShielded ? ('🛡️ 护盾生效中 (剩余 ' + remStr + '，开启将叠加8小时)') : '⚠️ 无护盾保护 (极易遭外部侦查与打击)';
          h += '          </div>';
          h += '        </div>';
          h += '      </div>';
          h += '      <div style="font-size:11px;color:#fb923c;font-weight:600;">💎 ' + diamondPrice + '</div>';
          h += '    </div>';
        });
        h += '    </div>';

        h += '    <div class="term-shield-summary">';
        h += '      <div>已选 <b>' + selectedCount + '</b> 座城市 | 需消耗: <b style="color:#fb923c;">💎 ' + totalCost + '</b></div>';
        h += '      <div>当前钻石: <b style="color:#38bdf8;">💎 ' + G.fmt(curDiamond) + '</b></div>';
        h += '    </div>';

        h += '    <div class="modal-foot" style="display:flex;justify-content:flex-end;gap:8px;padding:0;">';
        h += '      <button type="button" class="btn sm" id="termShieldCancelBtn">取消</button>';
        h += '      <button type="button" class="btn ok sm" id="termShieldConfirmBtn"' + (selectedCount === 0 ? ' disabled' : '') + '>';
        h += selectedCount > 0 ? ('确认开启护盾 (💎 ' + totalCost + ')') : '请勾选城市';
        h += '      </button>';
        h += '    </div>';

        h += '  </div>';
        h += '</div>';

        mask.innerHTML = h;

        var closeBtn = mask.querySelector('#termShieldCloseBtn');
        if (closeBtn) closeBtn.onclick = close;
        var cancelBtn = mask.querySelector('#termShieldCancelBtn');
        if (cancelBtn) cancelBtn.onclick = close;

        var allChk = mask.querySelector('#termShieldSelectAll');
        if (allChk) {
          allChk.onchange = function () {
            var checked = allChk.checked;
            cities.forEach(function (c) {
              selectedMap[c.id] = checked;
            });
            renderContent();
          };
        }

        mask.querySelectorAll('.term-shield-city-row').forEach(function (row) {
          row.onclick = function (e) {
            if (e.target && e.target.tagName === 'INPUT') return;
            var cid = row.getAttribute('data-city-row-id');
            selectedMap[cid] = !selectedMap[cid];
            renderContent();
          };
        });

        mask.querySelectorAll('.term-city-chk').forEach(function (chk) {
          chk.onchange = function () {
            var cid = chk.getAttribute('data-city-id');
            selectedMap[cid] = chk.checked;
            renderContent();
          };
        });

        var confirmBtn = mask.querySelector('#termShieldConfirmBtn');
        if (confirmBtn) {
          confirmBtn.onclick = function () {
            var selectedIds = Object.keys(selectedMap).filter(function (k) { return selectedMap[k]; });
            self.confirmCityShield(selectedIds).then(function (res) {
              if (res && res.success) {
                close();
              }
            });
          };
        }
      }

      function close() {
        if (mask.parentNode) mask.parentNode.removeChild(mask);
      }

      renderContent();
      document.body.appendChild(mask);
      return mask;
    },

    openDefenseFormation: function () {
      if (G.BattleDefaults && typeof G.BattleDefaults.open === 'function') {
        G.BattleDefaults.open();
      } else {
        G.go('battleDefaults');
      }
    },

    /**
     * 来袭卡片与军团求援弹窗共用详情展示，权限始终取分享者的服务端雷达投影。
     * @param {Object} im - 已按雷达等级裁剪的来袭军情
     * @returns {string} 转义后的军情详情 HTML
     */
    renderIncomingIntelligence: function (im) {
      var s = Core.state;
      var cityPos = (s.world && s.world.cityPos) || {};
      var attackerName = esc(im.attackerName || im.sourcePlayer || im.fromName || '未知敌军');
      var targetX = im.targetX != null ? im.targetX : cityPos.x;
      var targetY = im.targetY != null ? im.targetY : cityPos.y;
      var targetName = esc(im.targetName || (s.player && (s.player.cityName || s.player.username)) || '我方主城');
      function coordinateHtml(x, y, label) {
        var text = '(' + esc(x != null ? x : '?') + ',' + esc(y != null ? y : '?') + ')';
        if (x == null || y == null || String(x).trim() === '' || String(y).trim() === '' ||
            !Number.isFinite(Number(x)) || !Number.isFinite(Number(y))) return text;
        return '<a href="#" class="coord-link" data-intel-coordinate title="点击定位' + label +
          '" onclick="event.preventDefault();event.stopPropagation();Game.World.locateTarget(' + Number(x) + ',' + Number(y) + ')">' + text + '</a>';
      }
      var h = '';
      if (im.intelligenceType === 'field_interception') {
        // 野外急报使用独立白名单；即使混入旧版高级载荷也不能显示精确数量和加成。
        function officerText(key) {
          return esc(im[key] || '未知') + (im[key + 'Star'] ? ' ' + esc(im[key + 'Star']) + '星' : '') +
            (im[key + 'Level'] ? ' Lv.' + esc(im[key + 'Level']) : '');
        }
        h += '<div>情报来源: 野外目视与前线急报（无雷达支援）</div>';
        h += '<div>拦截玩家: <b>' + attackerName + '</b></div>';
        h += '<div>敌方统帅: ' + esc(im.sourceCommanderName || im.fromName || '未知') + '</div>';
        h += '<div>遭拦截队伍: 行军 #' + esc(im.interceptedMarchId) + '</div>';
        h += '<div>敌军出发地: ' + coordinateHtml(im.fromX, im.fromY, '敌军出发地') + '</div>';
        h += '<div>拦截交火位置: ' + coordinateHtml(im.targetX, im.targetY, '拦截交火位置') + '</div>';
        h += '<div>兵种: ' + esc((im.unitTypes || []).map(function (uid) { return (D.units[uid] || {}).name || uid; }).join('、') || '未知') + '</div>';
        h += '<div>兵力区间: ' + esc(im.strengthBand || '未知') + '（目视估计）</div>';
        h += '<div>带兵将领: ' + officerText('commander') + '</div>';
        h += '<div>司令官: ' + officerText('commandChief') + '</div>';
        h += '<div>无法确认精确编成、科技、装备及将领属性；突围获胜后沿原路线继续行进。</div>';
        return h;
      }
      var radar = Math.max(0, Math.min(10, Number(im.radarLevel) || 0));
      h += '    <div>雷达情报等级: Lv.' + radar + '</div>';
      if (radar >= 1) {
        h += '    <div>来袭玩家: <b>' + attackerName + '</b></div>';
        h += '    <div>行动: ' + esc(G.Constants.dispatchActionNames[im.action] || '未知') + '</div>';
      }
      if (radar >= 2) h += '    <div>出发地: ' + coordinateHtml(im.fromX, im.fromY, '出发地') + '</div>';
      h += '    <div>目的地: ' + coordinateHtml(targetX, targetY, '目的地') + ' ' + targetName + '</div>';
      if (radar >= 3) {
        var branches = { land: '陆军', sea: '海军', air: '空军' };
        h += '    <div>军种: ' + esc((im.branches || []).map(function (b) { return branches[b] || b; }).join('、') || '未知') + '</div>';
      }
      if (radar >= 4) h += '    <div>兵力区间: ' + esc(im.strengthBand || '未知') + '</div>';
      if (radar >= 5) h += '    <div>兵种: ' + esc((im.unitTypes || []).map(function (uid) { return (D.units[uid] || {}).name || uid; }).join('、') || '未知') + '</div>';
      if (radar >= 6) h += '    <div>总兵力: ' + (im.totalTroops != null ? G.fmt(im.totalTroops) : '未知') + '</div>';
      if (radar >= 7) Object.keys(im.army || {}).forEach(function (uid) {
        h += '    <div>' + esc((D.units[uid] || {}).name || uid) + ' ×' + G.fmt(im.army[uid]) + '</div>';
      });
      if (radar >= 8) h += '    <div>统帅: ' + esc(im.commander || '未知') + (im.commanderStar ? ' ' + esc(im.commanderStar) + '星' : '') + (im.commanderLevel ? ' Lv.' + esc(im.commanderLevel) : '') + '</div>';
      if (radar >= 9) {
        var techIntel = Object.keys(im.combatTech || {}).map(function (id) { return ((D.techs[id] || {}).name || id) + ' Lv.' + im.combatTech[id]; });
        h += '    <div>战斗科技: ' + esc(techIntel.join('、') || '未知') + '</div>';
        var gearIntel = Object.keys(im.unitEquipment || {}).filter(function (key) { return key.indexOf(':item:') >= 0; }).map(function (key) {
          var itemId = key.split(':item:')[1];
          var item = ((Core.state.unitEquipment || {}).catalog || {})[itemId];
          return item ? item.name : itemId;
        });
        h += '    <div>已配装装备: ' + esc(im.unitEquipment ? (gearIntel.join('、') || '无') : '未知') + '</div>';
        h += '    <div>' + esc(im.intelligenceSource || '旧来袭缺少高级情报') + '</div>';
      }
      if (radar >= 10) {
        // 来袭情报不公开带兵将领的有效属性和技能，兼容旧载荷时也不展示。
        var chief = im.commandBonus;
        h += '    <div>司令官: ' + esc(chief ? (chief.name || '未任命') + '（军事 ' + chief.military + ' / 防御 ' + chief.defense + ' / 后勤 ' + chief.logistics + ' / 学识 ' + chief.knowledge + '）' : '未知') + '</div>';
      }
      return h;
    },

    /**
     * 军团成员点击聊天战报后查询授权快照；只读弹窗不绑定接收者的来袭索引或迎战操作。
     * @param {string} token - 服务端生成的随机分享凭证
     * @returns {Promise<Object|undefined>} 读取到的分享快照
     */
    viewSharedIncomingReport: function (token) {
      var self = this;
      return G.API.sharedIncomingReport(token).then(function (report) {
        var im = report.incoming;
        if (!im) throw new Error('分享军情内容不完整');
        var previous = document.getElementById('sharedIncomingReportModal');
        if (previous) previous.remove();
        var mask = document.createElement('div');
        mask.id = 'sharedIncomingReportModal';
        mask.className = 'modal-mask';
        mask.setAttribute('role', 'dialog');
        mask.setAttribute('aria-modal', 'true');
        mask.setAttribute('aria-labelledby', 'sharedIncomingReportTitle');
        // 快照抵达时间不能证明当前仍在来袭，过期时提示向求援者确认最新战况。
        var deadline = Number(im.arriveAt);
        var timing = Number.isFinite(deadline) && deadline > 0
          ? '预计到达时间: ' + esc(new Date(G.GameTime ? G.GameTime.toWallTime(deadline) : deadline).toLocaleString()) : '预计到达时间: 未知';
        if (Number.isFinite(deadline) && deadline > 0 && deadline <= G.gameNow()) timing += '（已过，请确认最新战况）';
        var sharedAt = Number(report.sharedAt);
        var countdown = '';
        if (Number.isFinite(deadline) && deadline > 0 && Number.isFinite(sharedAt) && sharedAt > 0) {
          var remaining = Math.max(0, Math.ceil((deadline - sharedAt) / 1000));
          countdown = '<div>到达倒计时（分享时）: ' + Math.floor(remaining / 60) + '分' + remaining % 60 + '秒</div>';
        }
        var status = im.inBattle ? '交战中' : (im.waitingForBattle ? '候战中' : (im.arrived ? '已到达' : '行军中'));
        mask.innerHTML = '<div class="modal-card">' +
          '<div class="modal-title" id="sharedIncomingReportTitle">求援战报 · 来袭军情</div>' +
          '<div class="modal-body">' +
            '<p>求援者: ' + esc(report.ownerName || '军团成员') + '</p>' +
            '<p>分享时间: ' + esc(new Date(report.sharedAt).toLocaleString()) + '（情报以分享时为准）</p>' +
            '<div class="term-expanded-intel">' + self.renderIncomingIntelligence(im) +
              '<div>分享时状态: ' + status + '</div>' + countdown + '<div>' + timing + '</div></div>' +
          '</div><div class="modal-foot"><button type="button" class="btn" data-incoming-close>关闭</button></div></div>';
        var priorFocus = document.activeElement;
        var close = function () {
          mask.remove();
          if (priorFocus && priorFocus.isConnected && priorFocus.focus) priorFocus.focus();
        };
        var button = mask.querySelector('[data-incoming-close]');
        button.onclick = close;
        mask.querySelectorAll('[data-intel-coordinate]').forEach(function (link) {
          link.addEventListener('click', function () { mask.remove(); });
        });
        mask.onclick = function (event) { if (event.target === mask) close(); };
        mask.onkeydown = function (event) { if (event.key === 'Escape') close(); };
        document.body.appendChild(mask);
        button.focus();
        return report;
      }).catch(function (err) { G.toast(err.message || '分享军情加载失败'); });
    },

    /**
     * 一键呼叫军团救援：由服务端保存当前可见军情，并向军团发送可点击的求援战报
     * @param {number} [incomingIdx] - 军情索引，未传时自动挑选当前最紧急的军情
     * @returns {Promise<Object>} 发送结果；失败时提示原因并返回 success: false
     */
    requestGuildAid: function (incomingIdx) {
      var s = Core.state;
      var player = s.player || {};
      // 游戏状态通过 player.guild 携带当前军团，无需先进入军团大厅加载缓存。
      var guild = player.guild || s.guild || {};
      if (!guild.id && !player.guildId) {
        G.toast('您尚未加入任何军团，无法向军团发送求援！');
        return Promise.resolve({ success: false, reason: '未加入军团' });
      }

      var incomingList = (s.world && s.world.incoming) || [];
      if (incomingIdx == null || incomingIdx === undefined) {
        // 自动挑选最紧急的威胁：优先即将来袭的敌军，其次侦查
        var foundIdx = -1;
        var minRem = Infinity;
        var now = G.gameNow();
        for (var i = 0; i < incomingList.length; i++) {
          var item = incomingList[i];
          if (item.intelligenceType === 'field_interception') continue;
          var rem = (item.arriveAt || 0) - now;
          var weight = (item.action === 'scout' ? 1000000 : 0) + Math.max(0, rem);
          if (weight < minRem) {
            minRem = weight;
            foundIdx = i;
          }
        }
        if (foundIdx >= 0) {
          incomingIdx = foundIdx;
        } else {
          G.toast('当前防空雷达未检测到来袭或侦查威胁，无需呼叫救援！');
          return Promise.resolve({ noThreat: true });
        }
      }

      var im = incomingList[incomingIdx];
      if (im && im.intelligenceType === 'field_interception') {
        G.toast('野外遭遇请指挥在途部队，本城驻防救援不适用');
        return Promise.resolve({ noThreat: true });
      }
      if (!im) {
        G.toast('未找到对应的军情信息！');
        return Promise.resolve({ notFound: true });
      }

      var isScout = im.action === 'scout';
      var successTip = isScout
        ? '已向军团频道分享侦察求援战报，成员可点击查看来袭军情！'
        : '已向军团频道分享敌袭求援战报，成员可点击查看来袭军情！';

      var sendPromise;
      if (G.Chat && typeof G.Chat.shareIncoming === 'function') {
        sendPromise = G.Chat.shareIncoming(im.id);
      } else if (G.API && typeof G.API.shareIncomingReport === 'function') {
        sendPromise = G.API.shareIncomingReport(im.id);
      } else {
        sendPromise = Promise.reject(new Error('军团军情分享服务暂未就绪'));
      }

      return sendPromise.then(function (res) {
        G.toast(successTip);
        return { success: true, res: res };
      }).catch(function (err) {
        // 求援必须由军团聊天接口确认送达，网络或身份校验失败时不能提示成功。
        var errMsg = (err && (err.message || (err.response && err.response.data && err.response.data.message))) || '求援发送失败，请稍后重试';
        G.toast('⚠️ ' + errMsg);
        return { success: false, reason: errMsg };
      });
    },

    applyElectronicJamming: function (idx) {
      G.toast('⚡ 防空雷达已释放全频段电子干扰，降低敌方侦察精度与探查范围！');
    },

    interceptScoutAircraft: function (idx) {
      G.toast('🎯 防空导弹与高射火炮阵地已锁定敌方高空侦察机，开火拦截！');
    },

    /**
     * 判断拦截目标是否为侦查；旧记录缺少行军 ID 时仍按侦查处理。
     * @param {Object} target - 拦截出征目标，包含原来袭情报
     * @returns {boolean} 是否限制为装甲车和空军
     */
    isScoutInterception: function (target) {
      return target.marchId == null || (target.interceptTarget || {}).action === 'scout';
    },

    /** 判断兵种是否可用于侦查拦截（空军或装甲车）。 */
    isInterceptUnit: function (uid, u) {
      if (!u) return false;
      if (u.cat === 'air' || u.branch === 'air') return true;
      if (uid === 'armored' || uid === 'recon_car') return true;
      if (u.name && (u.name.indexOf('防空') >= 0 || (u.name.indexOf('装甲车') >= 0 && u.atkAir > 10))) return true;
      return false;
    },

    /**
     * 进入出城拦截出征界面，保留来袭动作以区分侦查和普通敌袭的兵种限制。
     * @param {number|Object} idx - 来袭军情索引或情报对象
     * @param {boolean} returnToMap - 取消时是否返回地图
     */
    openScoutInterception: function (idx, returnToMap) {
      var s = Core.state;
      if (!s) return;
      if (!s.world) s.world = {};
      var incoming = (s.world && s.world.incoming) || [];
      var im = idx && typeof idx === 'object' ? idx : incoming[idx];
      if (!im && typeof idx !== 'undefined') {
        im = incoming.find(function (item) { return item.id === idx || item.marchId === idx; });
      }
      if (!im) {
        G.toast('未找到该来袭行军记录');
        return;
      }
      var attackerName = im.attackerName || im.sourcePlayer || im.fromName || '未知敌军';
      var targetX = im.fromX != null ? im.fromX : (s.world.cityPos ? s.world.cityPos.x : 0);
      var targetY = im.fromY != null ? im.fromY : (s.world.cityPos ? s.world.cityPos.y : 0);
      var isRealMarch = im.marchId != null;
      var targetLabel = isRealMarch && im.action !== 'scout' ? ' 的行军部队' : ' 的侦察编队';

      s.world._dispatchTarget = {
        kind: 'incoming_scout',
        action: 'intercept_scout',
        returnToMap: returnToMap === true,
        incomingIdx: idx,
        incomingId: im.id,
        marchId: im.marchId,
        targetName: attackerName + targetLabel,
        target: {
          id: im.id || ('scout_inc_' + idx),
          name: attackerName + (targetLabel === ' 的行军部队' ? targetLabel : ' 的高空侦察机编队'),
          x: targetX,
          y: targetY,
          level: 1,
          attackerName: attackerName,
          arriveAt: im.arriveAt,
          duration: im.duration
        },
        targetX: targetX,
        targetY: targetY,
        interceptTarget: im
      };

      G.go('dispatch');
    },

    thankAlly: function (allyName) {
      G.toast('已向盟友 ' + (allyName || '') + ' 发送答谢战讯与军需补给！');
    },

    /** 野外遭遇卡只提供情报与交火坐标，不开放城防护盾、城市迎战或军团驻防入口。 */
    renderFieldInterceptionAlert: function (im, index, now) {
      var arrived = Number(im.arriveAt) <= now;
      var unitNames = (im.unitTypes || []).map(function (uid) { return (D.units[uid] || {}).name || uid; }).join('、');
      var h = '<div class="term-alert-card is-clickable menu-item type-enemy" onclick="Game.World.toggleIncomingExpand(' + index + ')">';
      h += '<div class="term-row term-row-title"><div class="term-left"><span class="term-badge danger">[遭遇拦截]</span>';
      h += '<span class="term-name">行军 #' + esc(im.interceptedMarchId) + ' 遭到 ' + esc(im.fromName || '未知敌军') + ' 截击</span>';
      h += '<span class="term-expand-icon">' + (im.expanded ? '▼' : '▶') + '</span></div>';
      h += '<div class="term-right term-time">' + (arrived ? '交火结算中' : this.alertCountdown(im.arriveAt) + ' 后遭遇') + '</div></div>';
      h += '<div class="term-row term-row-sub"><div class="term-left term-info-flow">';
      h += '<span class="term-troops">' + esc(unitNames || '兵种未知') + ' · 兵力约 ' + esc(im.strengthBand || '未知') + '</span>';
      h += '<span class="term-target">交火位置: [' + esc(im.targetX == null ? '?' : im.targetX) + ',' + esc(im.targetY == null ? '?' : im.targetY) + ']</span>';
      h += '<span>野外急报 · 无雷达支援</span></div></div>';
      if (im.expanded) h += '<div class="term-expanded-intel">' + this.renderIncomingIntelligence(im) + '</div>';
      return h + '</div>';
    },

    renderAlerts: function (v) {
      this.acknowledgeAlerts();
      var self = this;
      var s = Core.state;
      var marches = s.world.marches || [];
      var incoming = s.world.incoming || [];
      var reinforcements = s.world.reinforcements || [];
      var now = G.gameNow();
      var filter = this._alertsFilter || 'all';

      // 敌袭只统计非侦查来袭，数量与对应列表的筛选口径保持一致。
      var enemyIncoming = incoming.filter(function (im) { return im.action !== 'scout'; });
      var scoutIncoming = incoming.filter(function (im) { return im.action === 'scout'; });
      var scoutMarches = marches.filter(function (m) { return m.action === 'scout'; });
      var totalScoutCount = scoutIncoming.length + scoutMarches.length;
      var totalEnemyCount = enemyIncoming.length;
      var totalMarchCount = marches.length;
      var totalGarrisonCount = reinforcements.length + marches.filter(function (m) { return m.stationed; }).length;
      var totalAllCount = incoming.length + marches.length + reinforcements.length;

      // 雷达覆盖与防务状态
      var radarLvl = Number((s.buildings && s.buildings.radar) || 0);
      var radarKm = radarLvl > 0 ? (radarLvl * 12) + 'KM' : '120KM';
      var allCities = this.getPlayerCities();
      var shieldedCities = allCities.filter(function (c) { return c.shieldUntil && c.shieldUntil > now; });
      var hasShield = shieldedCities.length > 0;
      var shieldStatusText = '';
      if (shieldedCities.length === allCities.length && allCities.length > 1) {
        shieldStatusText = '<span class="term-defense-state safe">🛡️全城护盾中 (' + allCities.length + '城全部受保)</span>';
      } else if (shieldedCities.length > 0) {
        var minRem = Math.min.apply(null, shieldedCities.map(function (c) { return c.shieldUntil - now; }));
        var sRem = Math.max(0, Math.ceil(minRem / 1000));
        var sH = Math.floor(sRem / 3600);
        var sM = Math.floor((sRem % 3600) / 60);
        shieldStatusText = '<span class="term-defense-state safe">🛡️护盾保护中 (' + (allCities.length > 1 ? shieldedCities.length + '/' + allCities.length + '城, ' : '') + (sH > 0 ? sH + 'h ' : '') + sM + 'm)</span>';
      } else {
        shieldStatusText = '<span class="term-defense-state">防务: 无护盾保护</span>';
      }

      var h = '<div class="alerts-terminal">';

      // 顶部全域监控头
      h += '<div class="term-header">';
      h += '  <div class="term-status-line">';
      h += '    <span class="term-badge-tag">[全域预警监控]</span>';
      h += '    <span class="term-status-ok">正常运行</span>';
      h += '    <span class="term-sep">|</span>';
      h += '    <span class="term-radar-range">雷达范围: ' + radarKm + '</span>';
      h += '    <span class="term-sep">|</span>';
      h += '    ' + shieldStatusText;
      h += '  </div>';

      // 字符过滤标签
      h += '  <div class="term-tabs-row">';
      h += '    <button type="button" class="term-tab ' + (filter === 'all' ? 'active' : '') + '" onclick="Game.World.setAlertsFilter(\'all\')">[全部(' + totalAllCount + ')]</button>';
      h += '    <button type="button" class="term-tab ' + (filter === 'enemy' ? 'active' : '') + '" onclick="Game.World.setAlertsFilter(\'enemy\')">[敌袭] 敌军来袭 (' + totalEnemyCount + ')</button>';
      h += '    <button type="button" class="term-tab ' + (filter === 'scout' ? 'active' : '') + '" onclick="Game.World.setAlertsFilter(\'scout\')">[侦察(' + totalScoutCount + ')]</button>';
      h += '    <button type="button" class="term-tab ' + (filter === 'mine' ? 'active' : '') + '" onclick="Game.World.setAlertsFilter(\'mine\')">[我军(' + totalMarchCount + ')]</button>';
      h += '    <button type="button" class="term-tab ' + (filter === 'garrison' ? 'active' : '') + '" onclick="Game.World.setAlertsFilter(\'garrison\')">[驻防(' + totalGarrisonCount + ')]</button>';
      h += '  </div>';
      h += '</div>';

      h += '<div class="term-divider">-------------------------------------------------------------------------</div>';
      h += '<div class="term-alert-list">';

      // 1. 敌军来袭 (incoming)
      var showIncoming = (filter === 'all' || filter === 'enemy' || filter === 'scout');
      if (showIncoming) {
        if ((filter === 'enemy' && !totalEnemyCount) || (filter === 'all' && !incoming.length)) {
          h += '<div class="term-empty">暂无敌方来袭军情。</div>';
        } else {
          for (var ii = 0; ii < incoming.length; ii++) {
            var im = incoming[ii];
            var isScout = im.action === 'scout';
            if (filter === 'enemy' && isScout) continue;
            if (filter === 'scout' && !isScout) continue;
            if (im.intelligenceType === 'field_interception') {
              h += this.renderFieldInterceptionAlert(im, ii, now);
              continue;
            }

            var rem = Math.max(0, Math.ceil((im.arriveAt - now) / 1000));
            var isArrived = Boolean(im.arrived) || (Boolean(im.marchId) && rem <= 0);
            var attackerName = G.escapeHtml(im.attackerName || im.sourcePlayer || im.fromName || '未知敌军');
            var targetX = im.targetX != null ? im.targetX : (s.world.cityPos ? s.world.cityPos.x : '?');
            var targetY = im.targetY != null ? im.targetY : (s.world.cityPos ? s.world.cityPos.y : '?');
            var targetName = G.escapeHtml(im.targetName || (s.player && (s.player.cityName || s.player.username)) || '我方主城');

            var badgeText = isScout ? '[侦察]' : '[敌袭!]';
            var badgeCls = isScout ? 'warn' : 'danger';
            var nameHtml = attackerName === '未知敌军'
              ? '未知敌军 来袭'
              : (attackerName + (im.guildName ? ' (' + G.escapeHtml(im.guildName) + ')' : '') + (attackerName.indexOf('来袭') >= 0 ? '' : ' 来袭'));

            var verb = isArrived ? (im.waitingForBattle ? '候战中' : '待迎战') : (isScout ? '抵近' : '抵达');
            var tStr = this.alertCountdown(im.arriveAt);

            // 使用服务端起止时间计算真实行程，并交给每秒刷新；旧来袭缺少出发时间时才回退时长。
            var duration = im.startAt != null && Number(im.arriveAt) > Number(im.startAt)
              ? Number(im.arriveAt) - Number(im.startAt) : (Number(im.duration) || 180000);
            var pct = isArrived ? 100 : Math.max(0, Math.min(99, Math.round((1 - (Number(im.arriveAt) - now) / duration) * 100)));
            var pbar = this.renderTextProgressBar(pct);

            // 距离计算
            var fromX = im.fromX != null ? im.fromX : 0;
            var fromY = im.fromY != null ? im.fromY : 0;
            var dx = targetX !== '?' ? (Number(targetX) - fromX) : 0;
            var dy = targetY !== '?' ? (Number(targetY) - fromY) : 0;
            var distKm = (Math.max(1, Math.round(Math.sqrt(dx * dx + dy * dy))) * 1.5).toFixed(1) + 'km';

            // 兵力显示
            var troopsText = '未知';
            if (im.totalTroops != null && !isNaN(Number(im.totalTroops))) {
              var nTroops = Number(im.totalTroops);
              troopsText = nTroops >= 10000 ? '~' + (nTroops / 10000).toFixed(1).replace(/\.0$/, '') + 'w' : nTroops.toLocaleString();
            } else if (im.strengthBand) {
              troopsText = '~' + im.strengthBand;
            } else if (im.army) {
              var sumT = 0;
              for (var uk in im.army) sumT += Number(im.army[uk] || 0);
              if (sumT > 0) troopsText = sumT >= 10000 ? '~' + (sumT / 10000).toFixed(1).replace(/\.0$/, '') + 'w' : sumT.toLocaleString();
            }

            h += '<div class="term-alert-card is-clickable menu-item type-' + (isScout ? 'scout' : 'enemy') + '" onclick="Game.World.toggleIncomingExpand(' + ii + ')">';

            // 第1行: [Badge] 玩家名 (军团)                 01:42 抵达
            h += '  <div class="term-row term-row-title">';
            h += '    <div class="term-left">';
            h += '      <span class="term-badge ' + badgeCls + '">' + badgeText + '</span>';
            h += '      <span class="term-name">' + nameHtml + '</span>';
            h += '      <span class="term-expand-icon">' + (im.expanded ? '▼' : '▶') + '</span>';
            h += '    </div>';
            h += '    <div class="term-right term-time">';
            if (im.waitingForBattle) {
              h += '      <span class="term-status danger">已到达，候战中</span>';
            } else if (isArrived) {
              h += '      <span class="term-status danger">已到达！待迎战</span>';
            } else {
              h += '      ' + tStr + ' <span class="term-verb">' + verb + '</span>';
            }
            h += '    </div>';
            h += '  </div>';

            // 第2行: [■■■■■■■□□□] 75% | 距: 48.2km | 兵力: ~15.2w | 目标: 主基地[342,681]      [开罩] [布阵] [军团求援]
            h += '  <div class="term-row term-row-sub">';
            h += '    <div class="term-left term-info-flow">';
            if (isScout) {
              h += '      <span class="term-troops">侦察机 1架 (高空)</span>';
              h += '      <span class="term-sep">|</span>';
              h += '      <span class="term-target">目标: ' + targetName + '</span>';
            } else {
              h += '      <span class="term-pbar" data-progress-bar="true" data-progress-deadline="' + im.arriveAt + '" data-progress-duration="' + duration + '">' + pbar + '</span>';
              h += '      <span class="term-sep">|</span>';
              h += '      <span class="term-dist">距: ' + distKm + '</span>';
              if (troopsText !== '未知') {
                h += '      <span class="term-sep">|</span>';
                h += '      <span class="term-troops">兵力: ' + troopsText + '</span>';
              }
              h += '      <span class="term-sep">|</span>';
              h += '      <span class="term-target">目标: ' + targetName + '[' + targetX + ',' + targetY + ']</span>';
            }
            h += '    </div>';

            // 动作按钮 (点触即发)
            h += '    <div class="term-right term-actions" onclick="event.stopPropagation()">';
            if (isArrived && im.marchId) {
              h += '      <button type="button" class="btn ok sm" onclick="Game.World.startIncomingBattle(' + ii + ')">进入战斗</button>';
            } else if (isScout) {
              h += '      <button type="button" class="btn sm term-btn danger" onclick="Game.World.openScoutInterception(' + ii + ')">出城拦截</button>';
              h += '      <button type="button" class="btn sm term-btn aid" onclick="Game.World.requestGuildAid(' + ii + ')">呼叫救援</button>';
            } else {
              if (im.marchId != null) {
                h += '      <button type="button" class="btn sm term-btn danger" onclick="Game.World.openScoutInterception(' + ii + ')">出城拦截</button>';
              }
              h += '      <button type="button" class="btn sm term-btn danger" onclick="Game.World.activateCityShield(' + ii + ')">开罩</button>';
              h += '      <button type="button" class="btn sm term-btn info" onclick="Game.World.openDefenseFormation()">布阵</button>';
              h += '      <button type="button" class="btn sm term-btn aid" onclick="Game.World.requestGuildAid(' + ii + ')">呼叫救援</button>';
            }
            h += '    </div>';
            h += '  </div>';

            // 展开的雷达详细情报
            if (im.expanded) {
              h += '  <div class="term-expanded-intel">';
              h += this.renderIncomingIntelligence(im);
              if (im.waitingForBattle) {
                h += '    <div class="cost urgent">敌军已到达城下，等待本城上一场战斗结束。</div>';
              } else if (isArrived && im.marchId) {
                h += '    <div class="cost urgent" style="color:var(--danger)">敌军已到达城下！</div>';
                h += '    <div class="btn-row" onclick="event.stopPropagation()"><button class="btn ok sm" onclick="Game.World.startIncomingBattle(' + ii + ')">进入战斗</button></div>';
              } else if (isArrived) {
                h += '    <div class="cost urgent" style="color:var(--danger)">敌军已到达城下，正在自动结算。</div>';
              } else {
                h += '    <div class="cost urgent">到达倒计时: ' + tStr + '</div>';
              }
              if (isScout) {
                h += '    <div class="term-intercept-banner" style="margin-top:6px;padding:6px 8px;background:rgba(239,68,68,0.08);border:1px dashed rgba(239,68,68,0.3);border-radius:3px;display:flex;align-items:center;justify-content:space-between;gap:8px;">';
                h += '      <span style="color:var(--text, #e2e8f0);font-size:11px;">🛡️ <b>防空迎击</b>: 在线指挥官可派遣空军或装甲车出城截击，全歼敌机瓦解侦查！</span>';
                h += '      <button type="button" class="btn sm term-btn danger" onclick="event.stopPropagation();Game.World.openScoutInterception(' + ii + ')">✈️ 立即出城拦截</button>';
                h += '    </div>';
              }
              h += '    <div class="term-aid-banner">';
              h += '      <span style="color:var(--text, #94a3b8);font-size:11px;">📢 <b>战情互通</b>: 一键将当前' + (isScout ? '侦查' : '来袭') + '军情发送至军团频道，呼叫盟友救援</span>';
              h += '      <button type="button" class="btn sm term-btn aid" onclick="event.stopPropagation();Game.World.requestGuildAid(' + ii + ')">一键呼叫军团救援</button>';
              h += '    </div>';
              h += '  </div>';
            }

            h += '</div>';
          }
        }
      }

      // 2. 我军行军 (marches)
      var showMarches = (filter === 'all' || filter === 'mine' || filter === 'scout');
      if (showMarches) {
        if (!marches.length && (filter === 'mine' || (!incoming.length && !reinforcements.length))) {
          h += '<div class="term-empty">暂无行军中的部队。</div>';
        } else {
          for (var mi = 0; mi < marches.length; mi++) {
            var m = marches[mi];
            var isMarchScout = m.action === 'scout';
            if (filter === 'scout' && !isMarchScout) continue;

            var isGather = m.targetKind === 'wild_gather';
            var isGatherInField = isGather && !m.returning;
            var remain = Math.max(0, Math.ceil((m.arriveAt - now) / 1000));
            var timeStr = (isGatherInField || m.stationed) ? '' : this.alertCountdown(m.arriveAt);
            var kindName = m.returning ? '返城' : m.action === 'intercept_scout' ? '出城拦截' : m.stationed ? '驻防' : isGather ? '采集' : ({ conquer: '征服', plunder: '掠夺', feint: '佯攻', harass: '袭扰', guerrilla: '游击', campaign: '讨伐', scout: '侦查', transport: '运输', rebase: '调遣', reinforce: '驻防行军' }[m.action] || '出征');

            var fromX = m.fromX != null ? m.fromX : '?';
            var fromY = m.fromY != null ? m.fromY : '?';
            var toX = m.targetX != null ? m.targetX : '?';
            var toY = m.targetY != null ? m.targetY : '?';
            var distance = m.distance != null ? distanceText(m.distance) : '?';
            var targetName = m.targetName || '目标';
            var originName = m.originName || '';

            var autoBattle = m.targetKind !== 'player' && (m.battleMode === 'auto' || (m.battleMode == null && m.targetKind === 'wild'));
            var canEnterBattle = m.action !== 'intercept_scout' && !autoBattle && !isGather && !m.returning && remain <= 0 && m.action !== 'scout' && m.action !== 'transport' && m.action !== 'rebase' && m.action !== 'reinforce';
            var canLocate = m.targetX != null && m.targetY != null && !isNaN(Number(m.targetX)) && !isNaN(Number(m.targetY));

            var badgeText = m.returning ? '[返航]' : m.stationed ? '[驻防]' : isGather ? '[采集]' : isMarchScout ? '[侦查]' : '[出征]';
            var badgeCls = m.returning ? 'cyan' : m.stationed ? 'blue' : isGather ? 'teal' : isMarchScout ? 'warn' : 'ok';

            // 进度条
            var marchDuration = (m.arriveAt && m.startAt) ? (m.arriveAt - m.startAt) : (m.duration || 180000);
            var mPct = remain <= 0 ? 100 : Math.max(0, Math.min(99, Math.round((1 - remain * 1000 / marchDuration) * 100)));
            var mPbar = this.renderTextProgressBar(mPct);

            var destCoordHtml = canLocate
              ? '<a href="javascript:void(0)" class="coord-link" onclick="Game.World.locateTarget(' + m.targetX + ',' + m.targetY + ')" title="点击定位目的地" style="color:var(--accent);text-decoration:underline">' + toX + ',' + toY + '</a>'
              : (toX + ',' + toY);

            // 统计总兵力
            var totalArmyCount = 0;
            for (var ak in m.army) totalArmyCount += Number(m.army[ak] || 0);
            var troopStr = totalArmyCount > 0
              ? totalArmyCount.toLocaleString() + '兵力'
              : armyText(m.army);

            h += '<div class="term-alert-card menu-item type-' + (m.returning ? 'return' : m.stationed ? 'station' : 'march') + '">';

            // 第1行
            h += '  <div class="term-row term-row-title">';
            h += '    <div class="term-left">';
            h += '      <span class="term-badge ' + badgeCls + '">' + badgeText + '</span>';
            h += '      <span class="term-name">' + kindName + '->' + G.escapeHtml(targetName) + (m.returning ? ' (撤自' + G.escapeHtml(originName || '原地') + ')' : '') + '</span>';
            h += '    </div>';
            h += '    <div class="term-right term-time">';
            if (!m.returning && m.action === 'intercept_scout' && m.deployAt != null) {
              var phase = now < m.deployAt ? '出城部署' : m.interceptionOpening !== 'ambush' ? '紧急迎击' : now < m.readyAt ? '设伏准备' : '伏击待发';
              h += '      <span class="term-status">' + phase + ' · ' + timeStr + ' 交火</span>';
            } else if (m.stationed) {
              h += '      <span class="term-status ok">已驻防</span>';
            } else if (isGatherInField) {
              h += '      <span class="term-status teal">采集中</span>';
            } else if (m.waitingForBattle) {
              h += '      <span class="term-status danger">候战中</span>';
            } else if (m.inBattle || m.battleId || canEnterBattle) {
              h += '      <span class="term-status danger">交火中</span>';
            } else {
              h += '      ' + timeStr + ' <span class="term-verb">' + (m.returning ? '归仓' : '到达') + '</span>';
            }
            h += '    </div>';
            h += '  </div>';

            // 第2行: 进度条 + 距离 + 兵力 + 操作按钮
            h += '  <div class="term-row term-row-sub">';
            h += '    <div class="term-left term-info-flow">';
            if (!isGatherInField) {
              h += '      <span class="term-pbar" data-progress-bar="true" data-progress-deadline="' + m.arriveAt + '" data-progress-duration="' + marchDuration + '">' + mPbar + '</span>';
              h += '      <span class="term-sep">|</span>';
            }
            h += '      <span class="term-dist">距' + distance + '格 (' + fromX + ',' + fromY + '->' + destCoordHtml + ')</span>';
            h += '      <span class="term-sep">|</span>';
            h += '      <span class="term-troops">' + troopStr + '</span>';
            if (isGatherInField) {
              h += '      <span class="term-sep">|</span>';
              h += '      <span class="cost" data-gather-march-id="' + Number(m.id) + '">' + esc(this.gatherMarchStatus(m)) + '</span>';
            }
            h += '    </div>';

            // 按钮操作
            h += '    <div class="term-right term-actions">';
            if (canLocate) {
              h += '<button type="button" class="btn sm" onclick="Game.World.locateTarget(' + m.targetX + ',' + m.targetY + ')">定位</button>';
            }
            if (this.canAccelerateMarch(m, now)) {
              h += '<button type="button" class="btn ok sm" data-accelerate-id="' + Number(m.id) + '" onclick="Game.World.speedUpMarch(' + Number(m.id) + ')">' + (m.returning ? '加速回城' : '[⚡加速50%] 加速行军') + '</button>';
            } else if (this.marchInTransit(m, now) && (m.returning ? m.returnAccelerated : m.outboundAccelerated)) {
              h += '<button type="button" class="btn sm" disabled>已加速</button>';
            }
            if ((remain > 0 || m.stationed || m.action === 'reinforce' || m.action === 'transport') && !m.returning && !m.gathering && !m.gatherStopped && !(m.inBattle || m.battleId)) {
              h += '<button type="button" class="btn warn sm" onclick="Game.World.cancelMarch(\'' + m.id + '\')">召回</button>';
            }
            if (isGather && !m.returning) {
              var gatherActs = this.gatherMarchActions(m);
              if (gatherActs) h += gatherActs;
            } else if (m.inBattle || m.battleId || canEnterBattle) {
              h += '<button type="button" class="btn ok sm" onclick="Game.Battle.openTactical(' + m.id + ')">进入战斗</button>';
            }
            h += '    </div>';
            h += '  </div>';

            h += '</div>';
          }
        }
      }

      // 3. 驻防盟友 (reinforcements)
      var showGarrison = (filter === 'all' || filter === 'garrison');
      if (showGarrison) {
        if (!reinforcements.length && filter === 'garrison') {
          h += '<div class="term-empty">暂无军团驻军。</div>';
        } else {
          for (var ri = 0; ri < reinforcements.length; ri++) {
            var support = reinforcements[ri];
            h += '<div class="term-alert-card menu-item type-station">';
            h += '  <div class="term-row term-row-title">';
            h += '    <div class="term-left">';
            h += '      <span class="term-badge blue">[驻防]</span>';
            h += '      <span class="term-name">' + esc(support.senderName) + ' (盟友驻防)</span>';
            h += '    </div>';
            h += '    <div class="term-right term-time">';
            h += '      <span class="term-status ok">已入驻</span>';
            h += '    </div>';
            h += '  </div>';
            h += '  <div class="term-row term-row-sub">';
            h += '    <div class="term-left term-info-flow">';
            h += '      <span class="term-troops">兵力: ' + armyText(support.army) + '</span>';
            h += '    </div>';
            h += '    <div class="term-right term-actions">';
            h += '      <button type="button" class="btn sm term-btn info" onclick="Game.World.thankAlly(\'' + esc(support.senderName) + '\')">答谢盟友</button>';
            h += '      <button type="button" class="btn warn sm" onclick="Game.World.dismissSupport(' + Number(support.id) + ')">遣返</button>';
            h += '    </div>';
            h += '  </div>';
            h += '</div>';
          }
        }
      }

      h += '</div>'; // end term-alert-list

      // 底部操作与经典按键行
      h += '<div class="term-divider">-------------------------------------------------------------------------</div>';
      h += '<div class="term-footer">';
      h += '  <div class="term-footer-left">';
      h += '    <span class="menu-item back term-key-action" onclick="Game.go(\'home\')">[0] 返回指挥大厅</span>';
      h += '    <span class="term-key-action" onclick="Game.World.refreshAlerts()">[R] 刷新雷达信号</span>';
      h += '    <span class="term-key-action" onclick="Game.World.requestGuildAid()">[S] 一键呼叫救援</span>';
      h += '  </div>';
      h += '  <div class="term-footer-right">';
      h += '    <span class="term-poll-info">自动轮询: 3s</span>';
      h += '    <span class="term-latency">响应: ' + (this._radarLatency || 12) + 'ms</span>';
      h += '  </div>';
      h += '</div>';

      h += '</div>'; // end alerts-terminal

      v.innerHTML = h;
      this.startAlertTimer(v);
    }
  };

  if (typeof window !== 'undefined' && typeof document !== 'undefined' && typeof document.addEventListener === 'function') {
    document.addEventListener('keydown', function (e) {
      var t = e.target;
      if (t && (t.tagName === 'INPUT' || t.tagName === 'SELECT' || t.tagName === 'TEXTAREA')) return;
      if (window.Game && window.Game.Core && window.Game.Core.route === 'alerts') {
        if (e.key === '0') {
          e.preventDefault();
          window.Game.go('home');
        } else if (e.key === 'r' || e.key === 'R') {
          e.preventDefault();
          if (window.Game.World && window.Game.World.refreshAlerts) window.Game.World.refreshAlerts();
        } else if (e.key === 's' || e.key === 'S') {
          e.preventDefault();
          if (window.Game.World && window.Game.World.requestGuildAid) window.Game.World.requestGuildAid();
        } else if (e.key === '1') {
          e.preventDefault();
          if (window.Game.World && window.Game.World.setAlertsFilter) window.Game.World.setAlertsFilter('enemy');
        } else if (e.key === '2') {
          e.preventDefault();
          if (window.Game.World && window.Game.World.setAlertsFilter) window.Game.World.setAlertsFilter('scout');
        } else if (e.key === '3') {
          e.preventDefault();
          if (window.Game.World && window.Game.World.setAlertsFilter) window.Game.World.setAlertsFilter('mine');
        } else if (e.key === '4') {
          e.preventDefault();
          if (window.Game.World && window.Game.World.setAlertsFilter) window.Game.World.setAlertsFilter('garrison');
        }
      }
    });
  }

  G.World = World;
  Core.views.world = function (v) { World.renderView(v); };
  Core.views.dispatch = function (v) { World.renderDispatch(v); };
  Core.views.alerts = function (v) { World.renderAlerts(v); };
})(window.Game);
