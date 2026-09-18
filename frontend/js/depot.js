(function (G) {
  var Core = G.Core;
  var D = G.DATA;

  var EQUIPMENT_NAMES = {
    recruit_military_weapon: '列兵军刀', recruit_military_badge: '列兵臂章', recruit_military_coat: '列兵作训服',
    recruit_logistics_weapon: '列兵工具包', recruit_logistics_badge: '列兵通行证', recruit_logistics_coat: '列兵工作服',
    recruit_knowledge_weapon: '列兵笔记本', recruit_knowledge_badge: '列兵学员章', recruit_knowledge_coat: '列兵学员服',
    officer_military_weapon: '校官军刀', officer_military_badge: '校官勋章', officer_military_coat: '校官军服',
    officer_logistics_weapon: '校官补给箱', officer_logistics_badge: '校官调度章', officer_logistics_coat: '校官军需服',
    officer_knowledge_weapon: '校官战术罗盘', officer_knowledge_badge: '校官参谋章', officer_knowledge_coat: '校官参谋服',
    marshal_military_weapon: '元帅佩剑', marshal_military_badge: '元帅将星', marshal_military_coat: '元帅礼服',
    marshal_logistics_weapon: '元帅辎重车', marshal_logistics_badge: '元帅军需印', marshal_logistics_coat: '元帅长袍',
    marshal_knowledge_weapon: '元帅望远镜', marshal_knowledge_badge: '元帅军师印', marshal_knowledge_coat: '元帅军礼服'
  };

  var SLOT_ICONS = { weapon: '🗡️', badge: '🎖️', coat: '🦺' };

  function equipmentInfo(itemId) {
    var m = /^(recruit|officer|marshal)_(military|logistics|knowledge)_(weapon|badge|coat)$/.exec(itemId || '');
    if (!m) return null;
    var tiers = {
      recruit: { name: '列兵', level: 1, main: 5, sub: 1 },
      officer: { name: '校官', level: 40, main: 15, sub: 3 },
      marshal: { name: '元帅', level: 100, main: 30, sub: 5 }
    };
    var branchNames = { military: '军事', logistics: '后勤', knowledge: '学识' };
    var slotNames = { weapon: '武器', badge: '徽章', coat: '外套' };
    var t = tiers[m[1]];
    var bName = branchNames[m[2]];
    var sName = slotNames[m[3]];
    var realName = EQUIPMENT_NAMES[itemId] || (t.name + bName + sName);
    var setBonusText = m[1] === 'marshal' ? (bName + '+30，全属性+5') : (bName + '+' + (m[1] === 'recruit' ? '3' : '15'));
    return {
      name: realName,
      icon: SLOT_ICONS[m[3]] || '🎖️',
      cat: 'equipment',
      tier: m[1],
      tierName: t.name,
      branch: m[2],
      branchName: bName,
      slot: m[3],
      slotName: sName,
      level: t.level,
      desc: t.name + bName + '套装·' + sName + '：Lv.' + t.level + '可穿戴，' + bName + '+' + t.main + '，其余属性各+' + t.sub + '。集齐同套3件激活套装效果（' + setBonusText + '）。'
    };
  }

  function fmt(n) { return G.fmt ? G.fmt(n) : n; }
  function fmtTime(ts) {
    var d = new Date(ts);
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }

  function getBuildQueue() {
    var s = Core.state;
    if (!Array.isArray(s.constructions)) s.constructions = [];
    return s.constructions;
  }
  // 当前生效状态描述
  function statusText() {
    var s = Core.state;
    var w = s.world || {};
    var parts = [];
    if (w.shieldUntil && w.shieldUntil > Date.now()) {
      var sh = Math.ceil((w.shieldUntil - Date.now()) / 3600000);
      parts.push('🛡️ 护盾 ' + sh + 'h');
    }
    if (w.marchBoostUntil && w.marchBoostUntil > Date.now()) {
      var mh = Math.ceil((w.marchBoostUntil - Date.now()) / 3600000);
      parts.push('🚩 行军加速 ' + mh + 'h');
    }
    return parts;
  }

  var Depot = {
    /** 切换仓库物品分类（保存在当前状态，刷新后仍保持本次选择） */
    setTab: function (cat) {
      var valid = ['jewelry', 'equipment', 'officer', 'resource', 'util'];
      if (valid.indexOf(cat) < 0) cat = 'jewelry';
      Core.state._depotTab = cat;
      Core.render();
    },
    _depotItem: null,    // 当前正在使用的 itemId（用于选军官/输入等二次确认）
    _pendingOfficer: null,

    // 入口：使用道具 - 全部走后端 API 持久化
    useItem: function (itemId) {
      var s = Core.state;
      s.items = s.items || {};
      if ((s.items[itemId] || 0) <= 0) { G.toast('道具数量不足'); return; }
      var info = D.items[itemId] || equipmentInfo(itemId);
      if (!info) return;

      if (info.cat === 'equipment') {
        Depot._depotItem = itemId;
        s._depotSelectOfficer = itemId;
        G.go('depotUse');
        return;
      }

      // 加速符类：走专用 build/speedup 接口(后端会减少施工剩余时间)
      if (itemId.indexOf('speedUp') === 0 && info.seconds) {
        var q = getBuildQueue();
        if (!q.length) { G.toast('当前无施工中建筑'); return; }
        G.API.buildSpeedUp(itemId).then(function (resp) {
          if (resp && resp.success === false) {
            G.toast(resp.message || '加速失败');
            if (resp.state) G.API.applyState(resp.state);
            return;
          }
          G.toast(resp.message || ('⚡ ' + info.name + ' 使用成功'));
          if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '加速失败');
        });
        return;
      }

      // 改名卡需要先选军官 + 输入新名字，保留 UI 流程
      if (itemId === 'renameCard') {
        Depot._depotItem = itemId;
        s._depotSelectOfficer = itemId;
        G.go('depotUse');
        return;
      }
      // 装备宝箱 / 宝箱类：直接开箱，不需选军官
      if (info.isBox || itemId.indexOf('box_') === 0) {
        Depot._callBackend(itemId, null, null);
        return;
      }
      // 征募令：直接调用后端刷新军校
      if (itemId === 'recruitOrd') {
        Depot._callBackend(itemId, null, null);
        return;
      }
      // 其它军官培养类：先选军官
      if (info.cat === 'officer') {
        Depot._depotItem = itemId;
        s._depotSelectOfficer = itemId;
        G.go('depotUse');
        return;
      }
      // 资源类 / 功能类：直接调用后端
      Depot._callBackend(itemId, null, null);
    },

    // 统一后端调用 - 所有数据持久化由后端处理
    _callBackend: function (itemId, officerId, newName) {
      G.API.depotUse(itemId, officerId, newName).then(function (resp) {
        if (resp && resp.success === false) {
          G.toast(resp.message || '使用失败');
          if (resp.state) G.API.applyState(resp.state);
          return;
        }
        G.toast(resp.message || '使用成功');
        if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
        if (Core.currentView === 'depotUse') {
          var s = Core.state;
          if (!s.items || (s.items[itemId] || 0) <= 0) {
            Depot._depotItem = null;
            s._depotSelectOfficer = null;
            G.go('depot');
            return;
          }
        }
        Core.render();
      }).catch(function (err) {
        G.toast(err.message || '使用失败');
      });
    },

    useResourceItem: function (itemId) {
      // 已废弃：保留方法名以兼容可能的旧引用，统一走 _callBackend
      Depot._callBackend(itemId, null, null);
    },

    useUtilItem: function (itemId) {
      // 加速符：仍走 buildSpeedUp（已有专用接口，且需要传入 itemKey）
      var s = Core.state;
      if (itemId.indexOf('speedUp') === 0 && D.items[itemId] && D.items[itemId].seconds) {
        if ((s.items[itemId] || 0) <= 0) { G.toast(D.items[itemId].name + ' 数量不足'); return; }
        var q = getBuildQueue();
        var res = s.research;
        if (!q.length && !res) { G.toast('当前无施工中建筑或研发中科技'); return; }
        if (res && !q.length) {
          G.API.techSpeedUp(itemId, res.queueId, 1).then(function (resp) {
            if (resp && resp.success === false) {
              G.toast(resp.message || '加速失败');
              if (resp.state) G.API.applyState(resp.state);
              return;
            }
            G.toast(resp.message || ('⚡ ' + D.items[itemId].name + ' 使用成功'));
            Core.render();
          }).catch(function (err) {
            G.toast(err.message || '加速失败');
          });
          return;
        }
        G.API.buildSpeedUp(itemId).then(function (resp) {
          if (resp && resp.success === false) {
            G.toast(resp.message || '加速失败');
            if (resp.state) G.API.applyState(resp.state);
            return;
          }
          G.toast(resp.message || ('⚡ ' + D.items[itemId].name + ' 使用成功'));
          if (G.MainQuest && G.MainQuest.refresh) G.MainQuest.refresh();
          Core.render();
        }).catch(function (err) {
          G.toast(err.message || '加速失败');
        });
        return;
      }
      // 护盾 / 行军令：走 depot use 接口
      Depot._callBackend(itemId, null, null);
    },

    useRecruitOrd: function () {
      Depot._callBackend('recruitOrd', null, null);
    },

    // 对军官使用道具 - 统一后端调用
    useOnOfficer: function (officerId) {
      var s = Core.state;
      var itemId = s._depotSelectOfficer || Depot._depotItem;
      if (!itemId) { G.toast('请先选择道具'); G.go('depot'); return; }

      // 改名卡：先选完军官再让用户输入新名字
      if (itemId === 'renameCard') {
        var o = null;
        for (var i = 0; i < (s.officers || []).length; i++) {
          if (s.officers[i] && String(s.officers[i].id) === String(officerId)) { o = s.officers[i]; break; }
        }
        if (!o) { G.toast('军官不存在'); G.go('depot'); return; }
        Depot._pendingOfficer = o;
        G.go('depotRename');
        return;
      }

      if (equipmentInfo(itemId)) {
        G.API.equipOfficer(officerId, itemId).then(function (resp) {
          if (resp && resp.success === false) {
            G.toast(resp.message || '装备失败');
            return;
          }
          G.toast((resp && resp.message) || '穿戴成功');
          Depot._depotItem = null;
          s._depotSelectOfficer = null;
          G.go('depot');
        }).catch(function (err) {
          G.toast(err.message || '装备失败');
        });
        return;
      }

      if (itemId === 'expBook' || itemId === 'expBookAdv' || itemId === 'expBookMax') {
        if (Game.Officer && Game.Officer.openExpBookModal) {
          Game.Officer.openExpBookModal(officerId, itemId);
          return;
        }
      }

      Depot._callBackend(itemId, officerId, null);
    },

    confirmRename: function () {
      var o = Depot._pendingOfficer;
      if (!o) { G.go('depot'); return; }
      var el = document.getElementById('renameInput');
      var name = el ? el.value.trim() : '';
      if (!name) { G.toast('请输入新名字'); return; }
      if (name.length > 12) { G.toast('名字不超过 12 字符'); return; }
      // 后端原子：扣改名卡 + 改名字 + 返回最新 state
      Depot._callBackend('renameCard', o.id, name);
      Depot._pendingOfficer = null;
    },

    cancelRename: function () {
      var s = Core.state;
      s.items.renameCard = (s.items.renameCard || 0) + 1;
      Depot._pendingOfficer = null;
      G.go('depot');
    },

    // —— 视图 ——
    renderView: function (v) {
      var s = Core.state;
      s.items = s.items || {};
      var h = '';
      h += '<div class="title">- 仓库 -</div>';

      // 状态条：生效中的功能道具
      var status = statusText();
      h += '<div class="panel">';
      var totalItems = 0;
      for (var k in s.items) totalItems += (s.items[k] || 0);
      h += '<div class="d">道具总数: <b>' + totalItems + '</b> 件</div>';
      if (status.length) {
        h += '<div class="d" style="color:var(--accent);font-weight:600">生效中: ' + status.join('  ·  ') + '</div>';
      } else {
        h += '<div class="d" style="color:var(--muted)">当前无功能道具生效</div>';
      }
      h += '</div>';

      var cats = { jewelry: '珠宝珍品', equipment: '军官装备', officer: '军官道具', resource: '资源道具', util: '功能道具' };
      var catOrder = ['jewelry', 'equipment', 'officer', 'resource', 'util'];
      var activeCat = catOrder.indexOf(s._depotTab) >= 0 ? s._depotTab : 'jewelry';
      h += '<div class="depot-tabs" role="tablist" aria-label="仓库物品分类">';
      for (var ti = 0; ti < catOrder.length; ti++) {
        var tabCat = catOrder[ti];
        h += '<button class="depot-tab' + (tabCat === activeCat ? ' active' : '') + '" role="tab" aria-selected="' + (tabCat === activeCat ? 'true' : 'false') + '" onclick="Game.Depot.setTab(\'' + tabCat + '\')">' + cats[tabCat] + '</button>';
      }
      h += '</div>';
      for (var ci = 0; ci < catOrder.length; ci++) {
        var cat = catOrder[ci];
        if (cat !== activeCat) continue;
        h += '<div class="zone-head">=== ' + cats[cat] + ' ===</div>';
        h += '<div class="menu">';
        var hasInCat = false;

        if (cat === 'equipment') {
          var eqKeys = Object.keys(s.items || {}).filter(function (k) {
            return equipmentInfo(k) && (s.items[k] || 0) > 0;
          });
          var tierOrder = { recruit: 1, officer: 2, marshal: 3 };
          var branchOrder = { military: 1, logistics: 2, knowledge: 3 };
          var slotOrder = { weapon: 1, badge: 2, coat: 3 };
          eqKeys.sort(function (a, b) {
            var infoA = equipmentInfo(a);
            var infoB = equipmentInfo(b);
            var tDiff = (tierOrder[infoA.tier] || 0) - (tierOrder[infoB.tier] || 0);
            if (tDiff !== 0) return tDiff;
            var bDiff = (branchOrder[infoA.branch] || 0) - (branchOrder[infoB.branch] || 0);
            if (bDiff !== 0) return bDiff;
            return (slotOrder[infoA.slot] || 0) - (slotOrder[infoB.slot] || 0);
          });

          for (var ei = 0; ei < eqKeys.length; ei++) {
            var eqId = eqKeys[ei];
            var eqInfo = equipmentInfo(eqId);
            var eqCnt = s.items[eqId];
            hasInCat = true;
            h += '<div class="menu-item ok">';
            h += '<span class="n">' + eqInfo.icon + ' ' + eqInfo.name + '</span> <span class="lv">×' + eqCnt + '</span>';
            h += '<div class="d">' + eqInfo.desc + '</div>';
            h += '<div class="btn-row"><button class="btn ok sm" onclick="Game.Depot.useItem(\'' + eqId + '\')">选择军官穿戴</button></div>';
            h += '</div>';
          }
        } else {
          for (var iid in D.items) {
            var info = D.items[iid];
            if (info.cat !== cat) continue;
            var cnt = s.items[iid] || 0;
            // 数量为 0 的道具不展示，避免空道具占用列表空间。
            if (cnt <= 0) continue;
            hasInCat = true;
            var cls = cnt > 0 ? 'menu-item ok' : 'menu-item lock';
            h += '<div class="' + cls + '">';
            h += '<span class="n">' + info.icon + ' ' + info.name + '</span> <span class="lv">×' + cnt + '</span>';
            h += '<div class="d">' + info.desc + '</div>';
            if (cnt > 0) {
              if (info.cat === 'jewelry' && !info.isBox && iid.indexOf('box_') !== 0) {
                h += '<div class="btn-row"><button class="btn ok sm" onclick="Game.go(\'mainQuest\')">前往晋升军衔</button></div>';
              } else {
                var btnLabel = (info.isBox || iid.indexOf('box_') === 0) ? '开启宝箱' : (iid === 'expBook' || iid === 'expBookAdv' || iid === 'expBookMax' || iid === 'loyaltyBox' || iid === 'renameCard' || iid === 'skillBook' || iid === 'starUp') ? '选择军官使用' : '使用';
                h += '<div class="btn-row"><button class="btn ok sm" onclick="Game.Depot.useItem(\'' + iid + '\')">' + btnLabel + '</button></div>';
              }
            }
            h += '</div>';
          }
        }

        if (!hasInCat) {
          if (cat === 'jewelry') {
            h += '<div class="desc">暂无珠宝珍品，派遣部队前往野地采集可探得各类稀世珠宝</div>';
          } else if (cat === 'equipment') {
            h += '<div class="desc">暂无军官装备，可在商城购买装备宝箱开启获得</div>';
          } else {
            h += '<div class="desc">暂无此类道具</div>';
          }
        }
        h += '</div>';
      }

      h += '<div class="menu-item back" onclick="Game.go(\'home\')">[0] 返回主菜单</div>';
      v.innerHTML = h;
    },

    renderUse: function (v) {
      var s = Core.state;
      var itemId = s._depotSelectOfficer || Depot._depotItem;
      if (!itemId) { G.go('depot'); return; }
      var info = D.items[itemId] || equipmentInfo(itemId);
      if (!info) { G.go('depot'); return; }
      var isEquip = info.cat === 'equipment';
      var h = '';
      h += '<div class="title">- ' + (isEquip ? '选择穿戴军官' : '选择军官') + ' -</div>';
      h += '<div class="panel">';
      h += '<div class="d">' + (isEquip ? '穿戴 ' : '使用 ') + '<b>' + info.icon + ' ' + info.name + '</b> (剩余 <b>' + ((s.items && s.items[itemId]) || 0) + '</b> 个)</div>';
      h += '<div class="d" style="color:var(--muted)">' + info.desc + '</div>';
      h += '</div>';
      h += '<div class="menu">';
      if (!s.officers || !s.officers.length) {
        h += '<div class="desc">暂无军官,请前往【军事】→【军校】招募</div>';
      }
      for (var i = 0; i < (s.officers || []).length; i++) {
        var o = s.officers[i];
        var starStr = '';
        for (var si = 0; si < o.star; si++) starStr += '★';
        var canEquip = true;
        var equipTip = '';
        if (isEquip) {
          if ((o.level || 1) < info.level) {
            canEquip = false;
            equipTip = ' <span style="color:var(--danger)">(需Lv.' + info.level + ')</span>';
          }
        }
        var itemCls = canEquip ? 'menu-item ok' : 'menu-item lock';
        var clickAttr = canEquip ? 'onclick="Game.Depot.useOnOfficer(\'' + o.id + '\')"' : 'onclick="Game.toast(\'军官等级不足，需达到Lv.' + info.level + '\')"';
        var skillStr = (Core && Core.skillText) ? Core.skillText(o) : '';
        if (!skillStr && o.skills && o.skills.length) {
          var skillParts = [];
          for (var si = 0; si < o.skills.length; si++) {
            var skItem = o.skills[si];
            if (typeof skItem === 'string') {
              var sDef = D.officerSkills && D.officerSkills[skItem];
              skillParts.push(sDef ? sDef.name : skItem);
            } else if (skItem && typeof skItem === 'object') {
              var sDef = D.officerSkills && D.officerSkills[skItem.id];
              var sName = sDef ? sDef.name : (skItem.name || skItem.id || '技能');
              skillParts.push(sName + (skItem.lv ? 'Lv' + skItem.lv : ''));
            }
          }
          if (skillParts.length) skillStr = ' · 技能:' + skillParts.join('/');
        }

        var curEquipTip = '';
        if (isEquip && Array.isArray(o.equipment)) {
          for (var eqi = 0; eqi < o.equipment.length; eqi++) {
            var eq = o.equipment[eqi];
            if (eq && eq.slot === info.slot) {
              var oldEqName = EQUIPMENT_NAMES[eq.itemId] || (eq.itemId ? eq.itemId : '原装备');
              curEquipTip = '<div style="color:var(--accent);font-size:12px;margin-top:2px">已装备[' + info.slotName + ']: ' + oldEqName + '（更换后原装备自动退回背包）</div>';
              break;
            }
          }
        }

        h += '<div class="' + itemCls + '" style="cursor:pointer" ' + clickAttr + '>';
        h += '<span class="n" style="color:' + (D.starColor[o.star] || '#bbb') + '">' + o.name + '</span> ';
        h += '<span class="stars">' + starStr + '</span> ';
        h += '<span class="lv">Lv.' + o.level + (o.role === 'mayor' ? ' [市长]' : (o.role === 'commander' ? ' [司令]' : '')) + equipTip + '</span> ';
        h += '<div class="d">将' + o.military + ' 军' + o.logistics + ' 智' + o.knowledge + ' 忠' + (o.loyalty || 0) + skillStr + '</div>';
        if (curEquipTip) h += curEquipTip;
        h += '</div>';
      }
      h += '</div>';
      h += '<div class="menu-item back" onclick="Game.go(\'depot\')">[0] 返回仓库</div>';
      v.innerHTML = h;
    },

    renderRename: function (v) {
      var o = Depot._pendingOfficer;
      if (!o) { G.go('depot'); return; }
      var h = '';
      h += '<div class="title">- 军官改名 -</div>';
      h += '<div class="panel">';
      h += '<div class="d">为 <b>' + o.name + '</b> 更换新名字</div>';
      h += '<div class="edit-row" style="margin-top:6px"><label>新名字</label><input id="renameInput" class="qty" style="width:100%" maxlength="12" value="' + o.name + '" /></div>';
      h += '<div class="d" style="color:var(--muted);margin-top:4px">改名后将消耗 1 张【改名卡】,不可撤销</div>';
      h += '<div class="btn-row" style="margin-top:6px"><button class="btn ok sm" onclick="Game.Depot.confirmRename()">确认改名</button><button class="btn sm" onclick="Game.Depot.cancelRename()">取消(退回改名卡)</button></div>';
      h += '</div>';
      h += '<div class="menu-item back" onclick="Game.Depot.cancelRename()">[0] 返回</div>';
      v.innerHTML = h;
    }
  };

  G.Depot = Depot;
  Core.views.depot = function (v) { Depot.renderView(v); };
  Core.views.depotUse = function (v) { Depot.renderUse(v); };
  Core.views.depotRename = function (v) { Depot.renderRename(v); };
})(window.Game);
