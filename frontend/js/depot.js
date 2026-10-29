(function (G) {
  var Core = G.Core;
  var D = G.DATA;

  var EQUIPMENT_NAMES = G.Constants.equipmentNames;
  var SLOT_ICONS = G.Constants.equipmentSlotIcons;

  /** 只接受共享目录中的装备键，所有六档十二部位使用相同元数据。 */
  function equipmentInfo(itemId) { return G.Constants.equipmentItems[itemId] || null; }

  /** 军衔礼包名称和承诺金额只读取本账号服务端回执，避免商城调价改变已发礼包文案。 */
  function rankGiftInfo(itemId) {
    var rewards = (Core.state.rankDiamondGifts || {}).rewards || [];
    return rewards.find(function (reward) { return reward.itemKey === itemId; }) || null;
  }

  function itemInfo(itemId) { return rankGiftInfo(itemId) || D.items[itemId] || equipmentInfo(itemId); }

  function esc(value) { return G.escapeHtml ? G.escapeHtml(String(value)) : String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;'); }

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
    var w = s.cityState || {};
    var parts = [];
    if (w.shieldUntil && w.shieldUntil > G.gameNow()) {
      parts.push('🛡️ 护盾 ' + G.formatDuration((w.shieldUntil - G.gameNow()) / 1000));
    }
    if (w.marchBoostUntil && w.marchBoostUntil > G.gameNow()) {
      parts.push('🚩 行军加速 +' + (w.marchBoostPercent == null ? 50 : w.marchBoostPercent) + '% ' + G.formatDuration((w.marchBoostUntil - G.gameNow()) / 1000));
    }
    return parts;
  }

  var Depot = {
    equipmentTier: '', equipmentBranch: '', equipmentSlot: '',
    filterEquipment: function (key, value) { this[key] = value; Core.render(); },
    clearEquipmentFilters: function () {
      this.equipmentTier = '';
      this.equipmentBranch = '';
      this.equipmentSlot = '';
      Core.render();
    },

    /** 切换仓库物品分类（保存在当前状态，刷新后仍保持本次选择） */
    setTab: function (cat) {
      var valid = G.Constants.depotCategoryOrder;
      if (valid.indexOf(cat) < 0) cat = valid[0];
      Core.state._depotTab = cat;
      Core.render();
    },
    /** 晋升奖励入口固定打开资源道具，避免玩家停留在其他仓库分类找不到礼包。 */
    openRankGifts: function () {
      Core.state._depotTab = 'resource';
      G.go('depot');
    },
    _depotItem: null,    // 当前正在使用的 itemId（用于选军官/输入等二次确认）
    _pendingOfficer: null,

    // 入口：使用道具 - 全部走后端 API 持久化
    useItem: function (itemId) {
      var s = Core.state;
      s.items = s.items || {};
      if ((s.items[itemId] || 0) <= 0) { G.toast('道具数量不足'); return; }
      var info = itemInfo(itemId);
      if (!info) return;

      if (itemId === 'mobilizationOrder' || itemId === 'advancedMobilizationOrder' || itemId === 'rapidMobilizationOrder') {
        G.Mobilization.open(itemId);
        return;
      }

      if (itemId === 'warSpeedOrder' || itemId === 'signalJammingOrder' || itemId === 'feintOrder') {
        G.toast(itemId === 'warSpeedOrder' ? '请在本城发起宣战的目标城市中选择“加速备战”' : '请在情报中的对应敌军详情使用');
        G.go(itemId === 'warSpeedOrder' ? 'world' : 'alerts');
        return;
      }

      if (itemId === 'teleportNormal' || itemId === 'teleportAdvanced') {
        G.CityTeleport.open(itemId);
        return;
      }

      if (itemId === 'cityRenameCard') {
        G.Main.promptRenameCityInDrawer();
        return;
      }

      if (itemId === 'commanderRenameCard') {
        G.Main.promptRenameCommander();
        return;
      }

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
      var playerId = (Core.state.player || {}).id;
      return G.API.depotUse(itemId, officerId, newName).then(function (resp) {
        if ((Core.state.player || {}).id !== playerId) return;
        if (resp && resp.success === false) {
          G.toast(resp.message || '使用失败');
          if (resp.state) G.API.applyState(resp.state);
          return;
        }
        var info = itemInfo(itemId);
        var isBox = (info && info.isBox) || itemId.indexOf('box_') === 0 || itemId === 'newbiePack';
        var isResource = (info && info.cat === 'res') || itemId.indexOf('res_') === 0 || itemId.indexOf('gold_') === 0;
        if (G.Sound && typeof G.Sound.play === 'function') {
          if (isBox) G.Sound.play('chest');
          else if (isResource) G.Sound.play('coins');
        }
        G.toast(resp.message || '使用成功');
        if (itemId.indexOf('rank_diamond_pack_') === 0) {
          Core.state._rankGiftResult = { playerId: playerId, message: resp.message,
            nextCertificateTier: resp.nextCertificateTier, nextCertificateName: resp.nextCertificateName };
        }
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
        if ((Core.state.player || {}).id !== playerId) return;
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
            if (G.Onboarding && G.Onboarding.refresh) G.Onboarding.refresh();
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

      if (itemId === 'universalSkillBook') {
        G.Officer.openUniversalSkillBook(officerId);
        return;
      }

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

      if (itemId === 'expBook' || itemId === 'expBookAdv' || itemId === 'expBookRapid' || itemId === 'expBookMax') {
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
      if (Number(o.nameRenameAvailableAt) > G.gameNow()) {
        G.toast('这名军官今天已改名，请明日 0:00 后再试');
        return;
      }
      var el = document.getElementById('renameInput');
      var name;
      try {
        name = G.normalizeDisplayName(el ? el.value : '', 12, '军官名称');
      } catch (error) {
        G.toast(error.message);
        return;
      }
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

      var giftResult = s._rankGiftResult;
      if (giftResult && giftResult.playerId === (s.player || {}).id) {
        h += '<div class="panel" role="status"><p>' + esc(giftResult.message || '礼包钻石已到账') + '</p>';
        var nextTier = giftResult.nextCertificateTier;
        // 服务端仅给可购买的档位；页面再次核对当前库存，避免重复展示已购买入口。
        if (nextTier && Number(nextTier) > Number((s.player || {}).militaryRank || 1) &&
            !(s.items['rank_certificate_' + nextTier] > 0) && G.Shop) {
          h += '<button type="button" class="btn" onclick="Game.Shop.openRankCertificate(' + Number(nextTier) + ')">前往商城购买' + esc(giftResult.nextCertificateName || '') + '</button>';
        }
        h += '</div>';
      }

      if (G.Mobilization) h += '<div class="panel"><h3>账号动员</h3>' + G.Mobilization.widget() + '</div>';
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

      var cats = G.Constants.depotCategories;
      var catOrder = G.Constants.depotCategoryOrder;
      var activeCat = catOrder.indexOf(s._depotTab) >= 0 ? s._depotTab : catOrder[0];
      h += '<div class="depot-tabs" role="tablist" aria-label="仓库物品分类">';
      for (var ti = 0; ti < catOrder.length; ti++) {
        var tabCat = catOrder[ti];
        h += '<button class="depot-tab' + (tabCat === activeCat ? ' active' : '') + '" role="tab" aria-selected="' + (tabCat === activeCat ? 'true' : 'false') + '" onclick="Game.Depot.setTab(\'' + tabCat + '\')">' + cats[tabCat] + '</button>';
      }
      h += '</div>';
      for (var ci = 0; ci < catOrder.length; ci++) {
        var cat = catOrder[ci];
        if (cat !== activeCat) continue;
        h += '<div class="menu">';
        var hasInCat = false;

        if (cat === 'unitEquipment' && G.UnitEquipment) {
          h += G.UnitEquipment.inventoryHtml(); hasInCat = true;
        } else if (cat === 'equipment') {
          var self = this;
          var hasActiveFilter = Boolean(self.equipmentTier || self.equipmentBranch || self.equipmentSlot);

          h += '<div class="depot-equip-filter-bar">';
          h += '<div class="depot-equip-filter-tag"><svg class="depot-equip-filter-tag-icon" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg><span>装备细分</span></div>';
          h += '<div class="depot-equip-filter-controls">';
          [['equipmentTier', '档次', G.Constants.equipmentTiers], ['equipmentBranch', '分支', G.Constants.equipmentBranches], ['equipmentSlot', '部位', G.Constants.equipmentSlots]].forEach(function (filter) {
            var filterKey = filter[0];
            var filterLabel = filter[1];
            var filterOpts = filter[2];
            var isCurActive = Boolean(self[filterKey]);
            h += '<label class="depot-equip-filter-item">';
            h += '<span class="depot-equip-filter-label">' + filterLabel + '</span>';
            h += '<select class="depot-equip-filter-select' + (isCurActive ? ' is-active' : '') + '" onchange="Game.Depot.filterEquipment(\'' + filterKey + '\',this.value)">';
            h += '<option value="">全部</option>';
            Object.keys(filterOpts).forEach(function (key) {
              var val = filterOpts[key];
              var optName = val.name || val;
              h += '<option value="' + key + '"' + (self[filterKey] === key ? ' selected' : '') + '>' + optName + '</option>';
            });
            h += '</select>';
            h += '</label>';
          });

          if (hasActiveFilter) {
            h += '<button type="button" class="depot-equip-filter-reset" onclick="Game.Depot.clearEquipmentFilters()" title="重置全部装备筛选">✕ 重置</button>';
          }
          h += '</div>';
          h += '</div>';

          var eqKeys = Object.keys(s.items || {}).filter(function (k) {
            var info = equipmentInfo(k);
            return info && (s.items[k] || 0) > 0 && (!self.equipmentTier || info.tier === self.equipmentTier) && (!self.equipmentBranch || info.branch === self.equipmentBranch) && (!self.equipmentSlot || info.slot === self.equipmentSlot);
          });
          var tierOrder = G.Constants.equipmentTierOrder;
          var branchOrder = G.Constants.equipmentBranchOrder;
          var slotOrder = G.Constants.equipmentSlotOrder;
          eqKeys.sort(function (a, b) {
            var infoA = equipmentInfo(a);
            var infoB = equipmentInfo(b);
            var tDiff = (tierOrder[infoA.tier] || 0) - (tierOrder[infoB.tier] || 0);
            if (tDiff !== 0) return tDiff;
            var bDiff = (branchOrder[infoA.branch] || 0) - (branchOrder[infoB.branch] || 0);
            if (bDiff !== 0) return bDiff;
            return (slotOrder[infoA.slot] || 0) - (slotOrder[infoB.slot] || 0);
          });

          var previousSet = null;
          for (var ei = 0; ei < eqKeys.length; ei++) {
            var eqId = eqKeys[ei];
            var eqInfo = equipmentInfo(eqId);
            var eqCnt = s.items[eqId];
            if (eqInfo.setKey !== previousSet) {
              if (previousSet) h += '</div></details>';
              var set = G.Constants.equipmentSets[eqInfo.setKey];
              var completeCount = Math.min.apply(null, set.keys.map(function (key) { return s.items[key] || 0; }));
              h += '<details class="depot-set-group"' + (hasActiveFilter ? ' open' : '') + '>';
              h += '<summary class="depot-set-summary">';
              h += '<div class="depot-set-summary-left">';
              h += '<span class="depot-set-arrow" aria-hidden="true">▶</span>';
              h += '<span class="depot-set-title">' + set.name + ' · 仓库完整套数 ' + completeCount + '</span>';
              h += '</div>';
              h += '<span class="depot-set-badge' + (completeCount > 0 ? ' is-complete' : '') + '">';
              h += completeCount > 0 ? ('成套 ' + completeCount + ' 套') : '未成套';
              h += '</span>';
              h += '</summary>';
              h += '<div class="depot-set-body">';
              previousSet = eqInfo.setKey;
            }
            hasInCat = true;
            h += '<div class="menu-item ok">';
            h += '<div class="depot-item-head">' +
              '<div class="depot-item-title"><span class="n">' + eqInfo.icon + ' ' + eqInfo.name + '</span> <span class="lv">×' + eqCnt + '</span></div>' +
              '<button type="button" class="btn depot-btn" onclick="Game.Depot.useItem(\'' + eqId + '\')">[选择军官穿戴]</button>' +
            '</div>';
            h += '<div class="d">' + eqInfo.desc + '</div>';
            h += '</div>';
          }
          if (previousSet) h += '</div></details>';
        } else {
          var catalog = Object.assign({}, D.items);
          ((s.rankDiamondGifts || {}).rewards || []).forEach(function (gift) { catalog[gift.itemKey] = gift; });
          for (var iid in catalog) {
            var info = catalog[iid];
            if (info.cat !== cat) continue;
            var cnt = s.items[iid] || 0;
            // 数量为 0 的道具不展示，避免空道具占用列表空间。
            if (cnt <= 0) continue;
            hasInCat = true;
            var cls = cnt > 0 ? 'menu-item ok' : 'menu-item lock';
            var btnHtml = '';
            if (cnt > 0) {
              if (iid.indexOf('rank_certificate_') === 0) {
                btnHtml = '<button type="button" class="btn depot-btn" onclick="Game.go(\'mainQuest\')">[前往晋升军衔]</button>';
              } else if (iid === 'officerPreparationParts') {
                btnHtml = '<button type="button" class="btn depot-btn" onclick="Game.go(\'officer\')">[前往军官整备]</button>';
              } else if (iid === 'mobilizationOrder' || iid === 'advancedMobilizationOrder' || iid === 'rapidMobilizationOrder') {
                btnHtml = '<button type="button" class="btn depot-btn" onclick="Game.Depot.useItem(\'' + iid + '\')">[使用动员令]</button>';
              } else {
                var btnLabel = iid === 'newbiePack' || iid.indexOf('rank_diamond_pack_') === 0 ? '开启礼包' : iid === 'cityRenameCard' ? '修改城市名' : (info.isBox || iid.indexOf('box_') === 0) ? '开启宝箱' : (iid === 'expBook' || iid === 'expBookAdv' || iid === 'expBookRapid' || iid === 'expBookMax' || iid === 'loyaltyBox' || iid === 'renameCard' || iid === 'skillBook' || iid === 'universalSkillBook' || iid.indexOf('skillBook_') === 0 || iid === 'starUp') ? '选择军官使用' : '使用';
                btnHtml = '<button type="button" class="btn depot-btn" onclick="Game.Depot.useItem(\'' + iid + '\')">[' + btnLabel + ']</button>';
              }
            }
            h += '<div class="' + cls + '">';
            h += '<div class="depot-item-head">' +
              '<div class="depot-item-title"><span class="n">' + G.iconHtml(info.icon, info.name) + ' ' + esc(info.name) + '</span> <span class="lv">×' + cnt + '</span></div>' +
              btnHtml +
            '</div>';
            h += '<div class="d">' + esc(info.desc) + '</div>';
            h += '</div>';
          }
        }

        if (!hasInCat) {
          if (cat === 'equipment') {
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
      h += '<div class="d">' + (isEquip ? '穿戴 ' : '使用 ') + '<b>' + G.iconHtml(info.icon, info.name) + ' ' + info.name + '</b> (剩余 <b>' + ((s.items && s.items[itemId]) || 0) + '</b> 个)</div>';
      h += '<div class="d" style="color:var(--muted)">' + info.desc + '</div>';
      h += '</div>';
      h += '<div class="menu">';
      if (!s.officers || !s.officers.length) {
        h += '<div class="desc">暂无军官,请前往【军工】→【军校】招募</div>';
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
        var fullSkills = itemId === 'universalSkillBook' && (o.skills || []).length >= 3;
        if (fullSkills) {
          canEquip = false;
          equipTip = '（技能已满3个，请先废弃一个）';
        }
        var itemCls = canEquip ? 'menu-item ok' : 'menu-item lock';
        var clickAttr = canEquip ? 'onclick="Game.Depot.useOnOfficer(\'' + o.id + '\')"' : 'onclick="Game.toast(\'军官等级不足，需达到Lv.' + info.level + '\')"';
        if (fullSkills) clickAttr = 'onclick="Game.toast(\'技能已满3个，请先废弃一个\')"';
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
        h += '<span class="n" style="color:' + (D.starColor[o.star] || '#bbb') + '">' + G.escapeHtml(o.name) + '</span> ';
        h += '<span class="stars">' + starStr + '</span> ';
        h += '<span class="lv">Lv.' + o.level + (o.role === 'mayor' ? ' [市长]' : (o.role === 'commander' ? ' [司令]' : (o.role === 'commander_in_chief' ? ' [司令官]' : ''))) + (itemId === 'universalSkillBook' ? ' · 技能 ' + (o.skills || []).length + '/3' : '') + equipTip + '</span> ';
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
      h += '<div class="d">为 <b>' + G.escapeHtml(o.name) + '</b> 更换新名字</div>';
      h += '<div class="edit-row" style="margin-top:6px"><label>新名字</label><input id="renameInput" class="qty" style="width:100%" value="' + G.escapeHtml(o.name) + '" /></div>';
      h += '<div class="d" style="color:var(--muted);margin-top:4px">最多12个字符，可用标点、符号和 emoji；不能换行。</div>';
      h += '<div class="d" style="color:var(--muted);margin-top:4px">每天可修改一次，按游戏时间的北京时间 0:00 重置，停服顺延。' + (Number(o.nameRenameAvailableAt) > G.gameNow() ? '这名军官今日已修改。' : '') + '</div>';
      h += '<div class="d" style="color:var(--muted);margin-top:4px">改名后将消耗 1 张【军官改名卡】,不可撤销</div>';
      h += '<div class="btn-row" style="margin-top:6px"><button type="button" class="btn depot-btn" onclick="Game.Depot.confirmRename()">[确认改名]</button><button type="button" class="btn depot-btn warn" onclick="Game.Depot.cancelRename()">[取消(退回军官改名卡)]</button></div>';
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
